"""Skill evolution helpers."""

from __future__ import annotations

import json
import re
from typing import Any

from clawgui_skills.file_tools import RestrictedSkillFileTools
from clawgui_skills.model_io import SkillModelClient
from clawgui_skills.package import SkillPackage
from clawgui_skills.prompts import SKILL_REVISE_PROMPT
from clawgui_skills.schema import VerifierFeedback, now_iso


class SkillEvolutionEngine:
    """Apply verifier feedback to a skill package through restricted tools.

    EvoSkill-GUI lets the execution agent edit skill files via sandboxed file
    tools. This offline engine mirrors the same write surface and produces
    deterministic targeted edits until an LLM-backed refiner is attached.
    """

    def __init__(
        self,
        *,
        model_client: SkillModelClient | None = None,
        mode: str = "auto",
    ):
        self.model_client = model_client
        self.mode = (mode or "auto").lower()

    def refine(self, skill: SkillPackage, feedback: VerifierFeedback) -> list[dict]:
        if feedback.success:
            return []

        skill.snapshot_version(reason=f"before revision: {feedback.failure_type}")
        tools = RestrictedSkillFileTools(skill)

        if self._model_enabled:
            try:
                edits = self._refine_with_model(skill, feedback, tools)
                if edits:
                    self._record_revision(skill, feedback, edits)
                    return edits
            except Exception:
                if self.mode == "model":
                    raise

        edits: list[dict] = []

        target_file, marker, section = self._targeted_revision(skill, feedback)
        edits.append(
            tools.replace_section(
                target_file,
                marker=marker,
                content=section,
                reason=feedback.root_cause,
            )
        )

        failure_example = self._failure_example(feedback)
        edits.append(tools.create_failure_example(failure_example, reason="persist verifier feedback"))

        self._record_revision(skill, feedback, edits)
        return edits

    @property
    def _model_enabled(self) -> bool:
        return self.mode in {"auto", "model"} and bool(self.model_client and self.model_client.available)

    def _refine_with_model(
        self,
        skill: SkillPackage,
        feedback: VerifierFeedback,
        tools: RestrictedSkillFileTools,
    ) -> list[dict[str, Any]]:
        assert self.model_client is not None
        prompt = self._build_revision_prompt(skill, feedback)
        raw = self.model_client.chat(
            system_prompt=SKILL_REVISE_PROMPT,
            user_text=prompt,
        )
        edits: list[dict[str, Any]] = []
        for name, arguments in _parse_tool_calls(raw):
            if name == "read_file" or name == "list_dir" or name == "search_file":
                tools.dispatch(name, arguments, reason="model-assisted skill revision")
                continue
            if name in {"write_file", "append_file"}:
                edits.append(tools.dispatch(name, arguments, reason="model-assisted skill revision"))
            elif name == "create_failure_example":
                edits.append(tools.dispatch(name, arguments, reason="model-assisted failure example"))

        if not any(edit.get("event") in {"write_file", "append_file"} for edit in edits):
            return []
        if not any(edit.get("event") == "create_failure_example" for edit in edits):
            edits.append(tools.create_failure_example(self._failure_example(feedback), reason="persist verifier feedback"))
        return edits

    def _build_revision_prompt(self, skill: SkillPackage, feedback: VerifierFeedback) -> str:
        files = {
            "docs/plan.md": skill.read_doc("plan.md"),
            "docs/backup.md": skill.read_doc("backup.md"),
            "docs/recover.md": skill.read_doc("recover.md"),
        }
        return (
            "# Skill package metadata\n"
            f"{json.dumps(skill.meta.to_dict(), ensure_ascii=False, indent=2)}\n\n"
            "# Current skill files\n"
            + "\n\n".join(
                f"## {path}\n```markdown\n{content.strip()}\n```"
                for path, content in files.items()
            )
            + "\n\n# Verifier feedback\n"
            f"{json.dumps(feedback.to_dict(), ensure_ascii=False, indent=2)}\n\n"
            "Edit the most relevant skill file(s) now. Prefer targeted rewrites of the concrete "
            "steps or locator/recovery rules that caused the failure."
        )

    def _record_revision(
        self,
        skill: SkillPackage,
        feedback: VerifierFeedback,
        edits: list[dict[str, Any]],
    ) -> None:
        summary = f"[{feedback.failure_type}] {feedback.root_cause[:160]}"
        skill.meta.failure_history_summary = (
            f"{skill.meta.failure_history_summary}\n{summary}".strip()
            if skill.meta.failure_history_summary
            else summary
        )
        skill.meta.evolution_status.revision_count += 1
        skill.meta.evolution_status.last_updated_at = now_iso()
        skill.save_meta()
        skill.record_edit({
            "event": "revision_summary",
            "feedback": json.dumps(feedback.to_dict(), ensure_ascii=False),
            "edits": edits,
        })

    def _targeted_revision(
        self,
        skill: SkillPackage,
        feedback: VerifierFeedback,
    ) -> tuple[str, str, str]:
        if feedback.failure_type == "grounding_error":
            return (
                "docs/backup.md",
                "evoskill-grounding-revision",
                self._grounding_revision(skill, feedback),
            )
        if feedback.failure_type == "recovery_error":
            return (
                "docs/recover.md",
                "evoskill-recovery-revision",
                self._recovery_revision(feedback),
            )
        return (
            "docs/plan.md",
            "evoskill-plan-revision",
            self._plan_revision(skill, feedback),
        )

    @staticmethod
    def _plan_revision(skill: SkillPackage, feedback: VerifierFeedback) -> str:
        lines = [
            "Revision note:",
            f"- Failure type: {feedback.failure_type}",
            f"- Root cause: {feedback.root_cause}",
            "- Updated guidance:",
            "  - Start by verifying whether the current screen is the target app or a launcher/home screen.",
        ]
        if skill.meta.domain_app:
            lines.append(f"  - If needed, launch one of: {', '.join(skill.meta.domain_app)}.")
        lines.extend(
            [
                "  - Convert the task into visible milestones and complete one milestone per action.",
                "  - Do not finish from a natural-language plan; finish only after the requested UI state is visible.",
            ]
        )
        lines.extend(f"  - {item}" for item in feedback.suggestions)
        return "\n".join(lines)

    @staticmethod
    def _grounding_revision(skill: SkillPackage, feedback: VerifierFeedback) -> str:
        lines = [
            "Revision note:",
            f"- Failure type: {feedback.failure_type}",
            f"- Root cause: {feedback.root_cause}",
            "- Updated locator guidance:",
            "  - Prefer exact visible text, content descriptions, and app header titles before coordinates.",
            "  - Re-check the screenshot after every tap before repeating the same coordinate.",
        ]
        if skill.meta.domain_app:
            lines.append(f"  - For {', '.join(skill.meta.domain_app)}, inspect search, chat title, input field, and send controls.")
        lines.extend(f"  - {item}" for item in feedback.suggestions)
        return "\n".join(lines)

    @staticmethod
    def _recovery_revision(feedback: VerifierFeedback) -> str:
        lines = [
            "Revision note:",
            f"- Failure type: {feedback.failure_type}",
            f"- Root cause: {feedback.root_cause}",
            "- Updated recovery guidance:",
            "  - If the same action repeats twice without visible progress, stop repeating it.",
            "  - Try an alternate route such as search, back once, or relaunch the target app.",
            "  - Ask for takeover only for explicit login, captcha, password, payment, or user-only verification screens.",
            "  - Do not classify ordinary chat, contact, search, editor, or permission-navigation screens as sensitive.",
        ]
        lines.extend(f"  - {item}" for item in feedback.suggestions)
        return "\n".join(lines)

    @staticmethod
    def _failure_example(feedback: VerifierFeedback) -> str:
        return (
            "# Failure Example\n\n"
            f"- time: {now_iso()}\n"
            f"- failure_type: {feedback.failure_type}\n"
            f"- failed_step: {feedback.failed_step if feedback.failed_step is not None else '-'}\n"
            f"- root_cause: {feedback.root_cause}\n\n"
            f"## Diagnosis\n{feedback.diagnosis}\n\n"
            f"## Suggestions\n"
            + "\n".join(f"- {item}" for item in feedback.suggestions)
        )


def _parse_tool_calls(text: str) -> list[tuple[str, dict[str, Any]]]:
    calls: list[tuple[str, dict[str, Any]]] = []
    for match in re.finditer(r"<tool_call>\s*(.*?)\s*</tool_call>", text or "", re.DOTALL):
        raw = match.group(1).strip()
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            continue
        name = str(payload.get("name") or "")
        arguments = payload.get("arguments") or {}
        if name and isinstance(arguments, dict):
            calls.append((name, arguments))
    if calls:
        return calls

    for match in re.finditer(r"\{[^{}]*\"name\"\s*:\s*\"[^\"]+\".*?\}", text or "", re.DOTALL):
        try:
            payload = json.loads(match.group(0))
        except json.JSONDecodeError:
            continue
        name = str(payload.get("name") or "")
        arguments = payload.get("arguments") or {}
        if name and isinstance(arguments, dict):
            calls.append((name, arguments))
    return calls
