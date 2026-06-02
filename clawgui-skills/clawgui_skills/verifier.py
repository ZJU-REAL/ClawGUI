"""Information-isolated trajectory verifier."""

from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path
from typing import Any

from clawgui_skills.adapters.trace import load_trace, summarize_trace
from clawgui_skills.model_io import SkillModelClient, parse_json_payload
from clawgui_skills.prompts import VERIFIER_SYSTEM_PROMPT, VERIFIER_USER_PROMPT, render_template
from clawgui_skills.schema import VerifierFeedback


class IsolatedTrajectoryVerifier:
    """Verifier with the same information boundary as the paper.

    It only consumes task instruction and sanitized trajectory artifacts. It does
    not inspect skill docs, executor chain-of-thought, or benchmark ground truth.
    """

    def __init__(
        self,
        *,
        model_client: SkillModelClient | None = None,
        mode: str = "auto",
        max_screenshots: int = 8,
    ):
        self.model_client = model_client
        self.mode = (mode or "auto").lower()
        self.max_screenshots = max(1, int(max_screenshots))
        self.previous_assertions: list[str] = []

    def diagnose(
        self,
        *,
        instruction: str,
        trace_path: str | None = None,
        trace: dict[str, Any] | None = None,
        success: bool = False,
        result: str = "",
    ) -> VerifierFeedback:
        if success:
            return VerifierFeedback(success=True, diagnosis="Task reported success.")

        if self._model_enabled:
            try:
                feedback = self._diagnose_with_model(
                    instruction=instruction,
                    trace_path=trace_path,
                    trace=trace,
                    result=result,
                )
                self.previous_assertions = list(feedback.state_assertions)
                return feedback
            except Exception:
                if self.mode == "model":
                    raise

        return self._diagnose_fallback(
            instruction=instruction,
            trace_path=trace_path,
            trace=trace,
            result=result,
        )

    def reset(self) -> None:
        self.previous_assertions = []

    @property
    def _model_enabled(self) -> bool:
        return self.mode in {"auto", "model"} and bool(self.model_client and self.model_client.available)

    def _diagnose_with_model(
        self,
        *,
        instruction: str,
        trace_path: str | None,
        trace: dict[str, Any] | None,
        result: str,
    ) -> VerifierFeedback:
        assert self.model_client is not None
        trace = trace if trace is not None else load_trace(trace_path)
        actions = _collect_sanitized_actions(trace)
        images = _collect_trace_images(trace_path, self.max_screenshots)
        prompt = render_template(
            VERIFIER_USER_PROMPT,
            instruction=instruction,
            oracle_block="",
            actions_block=_format_actions(actions),
            a11y_block="",
            final_state_block=_format_optional_block("Final screen / app / URL state", result),
            previous_assertions_block=_format_previous_assertions(self.previous_assertions),
        )
        raw = self._chat_with_trace_images(prompt, images)
        payload = parse_json_payload(raw)
        success = bool(payload.get("task_success", False))
        return VerifierFeedback(
            success=success,
            failure_type="none" if success else str(payload.get("failure_type") or "other"),
            failed_step=_coerce_failed_step(payload.get("failed_step")),
            root_cause=str(payload.get("root_cause") or ""),
            diagnosis=str(payload.get("diagnosis") or ""),
            suggestions=[str(item) for item in (payload.get("suggestions") or []) if item],
            state_assertions=[str(item) for item in (payload.get("state_assertions") or []) if item],
            raw=raw,
        )

    def _chat_with_trace_images(self, prompt: str, images: list[Path]) -> str:
        assert self.model_client is not None
        if not images:
            return self.model_client.chat(
                system_prompt=VERIFIER_SYSTEM_PROMPT,
                user_text=prompt,
            )

        content: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
        from clawgui_skills.model_io import image_to_data_url

        for path in images:
            image_url = image_to_data_url(str(path))
            if image_url:
                content.append({"type": "image_url", "image_url": {"url": image_url}})

        client = self.model_client._openai_client()
        kwargs: dict[str, Any] = {
            "model": self.model_client.model_name,
            "messages": [
                {"role": "system", "content": VERIFIER_SYSTEM_PROMPT},
                {"role": "user", "content": content},
            ],
            "temperature": self.model_client.temperature,
            "timeout": 300,
        }
        if self.model_client._uses_completion_tokens():
            kwargs["max_completion_tokens"] = self.model_client.max_tokens
        else:
            kwargs["max_tokens"] = self.model_client.max_tokens
        response = client.chat.completions.create(**kwargs)
        return response.choices[0].message.content or ""

    def _diagnose_fallback(
        self,
        *,
        instruction: str,
        trace_path: str | None = None,
        trace: dict[str, Any] | None = None,
        result: str = "",
    ) -> VerifierFeedback:
        trace = trace if trace is not None else load_trace(trace_path)
        steps = trace.get("episode") or []
        if not steps:
            return VerifierFeedback(
                success=False,
                failure_type="plan_error",
                root_cause="No executable trajectory was available for diagnosis.",
                diagnosis="The agent did not produce a usable trace, so the skill needs a clearer initial plan.",
                suggestions=[
                    "Clarify the first navigation step from the current screen.",
                    "Add a conservative recovery rule for missing trace or early termination.",
                ],
            )

        action_names = []
        for step in steps:
            action = step.get("action") or {}
            action_names.append(
                action.get("action")
                or action.get("action_type")
                or action.get("_metadata")
                or "unknown"
            )

        counts = Counter(action_names)
        repeated = counts.most_common(1)[0] if counts else ("unknown", 0)
        last_action = action_names[-1] if action_names else "unknown"
        trace_summary = summarize_trace(trace)

        if repeated[1] >= 3 and repeated[1] >= max(3, len(action_names) // 2):
            failure_type = "recovery_error"
            root = f"The trajectory repeated `{repeated[0]}` without enough progress."
            suggestions = [
                "Break repeated actions after two attempts.",
                "Add an alternate route from the last confirmed screen.",
            ]
        elif any(name in {"click", "tap", "long_press"} for name in action_names):
            failure_type = "grounding_error"
            root = "The task likely failed around element localization or target selection."
            suggestions = [
                "Use visible labels, nearby icons, and layout anchors as locator fallbacks.",
                "Prefer re-checking the screenshot before repeating a coordinate-based action.",
            ]
        else:
            failure_type = "plan_error"
            root = "The trajectory did not show a clear path to the requested end state."
            suggestions = [
                "Use a more explicit high-level route.",
                "Break the task into observable milestones.",
            ]

        return VerifierFeedback(
            success=False,
            failure_type=failure_type,
            root_cause=root,
            diagnosis=(
                f"Instruction: {instruction}\n"
                f"Result: {result or 'not provided'}\n"
                f"Trace summary:\n{trace_summary}\n\n"
                f"Root cause: {root}"
            ),
            suggestions=suggestions,
        )


def _collect_sanitized_actions(trace: dict[str, Any]) -> list[dict[str, Any]]:
    actions: list[dict[str, Any]] = []
    for idx, step in enumerate(trace.get("episode") or [], 1):
        action = step.get("action") or {}
        sanitized = _sanitize_action(action)
        actions.append(
            {
                "step": int(step.get("step") or idx),
                "action_json": json.dumps(sanitized, ensure_ascii=False),
                "tool_call": step.get("tool_call"),
            }
        )
    return actions


def _sanitize_action(action: dict[str, Any]) -> dict[str, Any]:
    allowed = {
        "_metadata",
        "action",
        "action_type",
        "element",
        "text",
        "app",
        "start",
        "end",
        "duration",
        "message",
        "coordinate",
        "coordinate2",
        "direction",
        "goal_status",
        "button",
        "action_name",
        "action_json",
    }
    return {key: value for key, value in action.items() if key in allowed}


def _format_actions(actions: list[dict[str, Any]]) -> str:
    if not actions:
        return "- (no actions captured)"
    lines = []
    for entry in actions:
        line = f"- step {entry['step']}: {entry['action_json']}"
        if entry.get("tool_call"):
            line += f" | tool_call={entry['tool_call']}"
        lines.append(line)
    return "\n".join(lines)


def _format_optional_block(title: str, content: str) -> str:
    content = (content or "").strip()
    if not content:
        return ""
    return f"# {title}\n{content}\n\n"


def _format_previous_assertions(assertions: list[str]) -> str:
    if not assertions:
        return ""
    lines = "\n".join(f"- {item}" for item in assertions)
    return f"# Previous-round assertions (your own, for continuity)\n{lines}\n\n"


def _coerce_failed_step(value: Any) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value)
    if isinstance(value, str) and value.strip().lstrip("-").isdigit():
        return int(value.strip())
    return None


def _collect_trace_images(trace_path: str | None, max_images: int) -> list[Path]:
    if not trace_path:
        return []
    path = Path(trace_path)
    root = path if path.is_dir() else path.parent
    images_dir = root / "images"
    if not images_dir.exists():
        return []
    files = sorted(
        [
            item
            for item in images_dir.iterdir()
            if item.is_file() and item.suffix.lower() in {".png", ".jpg", ".jpeg"}
        ],
        key=lambda p: _step_index(p.name),
    )
    if len(files) <= max_images:
        return files
    indices = sorted({0, len(files) - 1, *[
        round(i * (len(files) - 1) / max(1, max_images - 1))
        for i in range(max_images)
    ]})
    return [files[i] for i in indices[:max_images]]


def _step_index(name: str) -> int:
    match = re.search(r"(\d+)", name)
    return int(match.group(1)) if match else 0
