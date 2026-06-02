"""PhoneAgent bridge for ClawGUI-Skills."""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any


def _ensure_clawgui_skills_importable() -> None:
    try:
        import clawgui_skills  # noqa: F401
        return
    except ImportError:
        pass

    repo_root = Path(__file__).resolve().parents[2]
    skills_src = repo_root / "clawgui-skills"
    if skills_src.exists():
        sys.path.insert(0, str(skills_src))


_ensure_clawgui_skills_importable()

from clawgui_skills.config import SkillRuntimeConfig
from clawgui_skills.runtime import SkillRuntime
from clawgui_skills.schema import SkillFinishResult, SkillPrepareResult


class PhoneAgentSkillRuntime:
    """Thin adapter that keeps PhoneAgent independent from skill internals."""

    def __init__(
        self,
        *,
        mode: str,
        store_dir: str,
        retrieval_threshold: float,
        max_context_chars: int,
        max_iterations: int,
        require_review: bool,
        platform: str = "Android",
        model_base_url: str = "",
        model_api_key: str = "",
        model_name: str = "",
        generator_mode: str = "auto",
        verifier_mode: str = "auto",
        revision_mode: str = "auto",
        model_temperature: float = 0.0,
        model_max_tokens: int = 3000,
    ):
        self.runtime = SkillRuntime(
            SkillRuntimeConfig(
                mode=mode,
                store_dir=store_dir,
                retrieval_threshold=retrieval_threshold,
                max_context_chars=max_context_chars,
                max_iterations=max_iterations,
                require_review=require_review,
                platform=platform,
                model_base_url=model_base_url,
                model_api_key=model_api_key,
                model_name=model_name,
                generator_mode=generator_mode,
                verifier_mode=verifier_mode,
                revision_mode=revision_mode,
                model_temperature=model_temperature,
                model_max_tokens=model_max_tokens,
            )
        )
        self.prepare_result: SkillPrepareResult | None = None
        self.finish_result: SkillFinishResult | None = None

    def prepare(
        self,
        *,
        task: str,
        current_app: str = "",
        screenshot: Any = None,
        platform: str = "Android",
    ) -> SkillPrepareResult:
        self.prepare_result = self.runtime.prepare(
            task=task,
            current_app=current_app,
            screenshot=screenshot,
            platform=platform,
        )
        return self.prepare_result

    def finish(
        self,
        *,
        task: str,
        success: bool,
        result: str = "",
        trace_path: str | Path | None = None,
    ) -> SkillFinishResult:
        self.finish_result = self.runtime.finish(
            task=task,
            success=success,
            result=result,
            trace_path=trace_path,
        )
        return self.finish_result


def append_text_to_messages(messages: list[dict[str, Any]], text: str) -> None:
    """Append skill context to the system or first textual user message."""
    if not text:
        return
    for msg in messages:
        role = msg.get("role", "")
        content = msg.get("content", "")
        if role == "system" and isinstance(content, str):
            msg["content"] = content + f"\n\n{text}"
            return
        if role == "user":
            if isinstance(content, str):
                msg["content"] = content + f"\n\n{text}"
                return
            if isinstance(content, list):
                for item in content:
                    if isinstance(item, dict) and item.get("type") == "text":
                        item["text"] = item.get("text", "") + f"\n\n{text}"
                        return
                content.append({"type": "text", "text": text})
                return
