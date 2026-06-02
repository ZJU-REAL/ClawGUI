"""High-level runtime used by PhoneAgent, Web UI, and nanobot."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from clawgui_skills.config import SkillRuntimeConfig
from clawgui_skills.evolution import SkillEvolutionEngine
from clawgui_skills.generator import SkillGenerator
from clawgui_skills.intent import task_runtime_parameters
from clawgui_skills.model_io import SkillModelClient
from clawgui_skills.retriever import SkillRetriever
from clawgui_skills.schema import SkillFinishResult, SkillPrepareResult
from clawgui_skills.store import SkillStore
from clawgui_skills.verifier import IsolatedTrajectoryVerifier


class SkillRuntime:
    """Optional self-evolving skill runtime."""

    def __init__(self, config: SkillRuntimeConfig):
        self.config = config
        self.store = SkillStore(config.store_path)
        self.retriever = SkillRetriever()
        self.model_client = self._build_model_client()
        self.generator = SkillGenerator(
            self.store,
            model_client=self.model_client,
            mode=config.generator_mode,
        )
        self.verifier = IsolatedTrajectoryVerifier(
            model_client=self.model_client,
            mode=config.verifier_mode,
        )
        self.evolution = SkillEvolutionEngine(
            model_client=self.model_client,
            mode=config.revision_mode,
        )
        self.active_skill = None
        self.last_prepare: SkillPrepareResult | None = None

    def _build_model_client(self) -> SkillModelClient | None:
        if not self.config.has_model_config:
            return None
        return SkillModelClient(
            base_url=self.config.model_base_url,
            api_key=self.config.model_api_key,
            model_name=self.config.model_name,
            temperature=self.config.model_temperature,
            max_tokens=self.config.model_max_tokens,
        )

    def prepare(
        self,
        *,
        task: str,
        current_app: str = "",
        platform: str | None = None,
        screenshot: Any = None,
    ) -> SkillPrepareResult:
        platform = platform or self.config.platform

        if not self.config.enabled:
            result = SkillPrepareResult(mode=self.config.mode, status="off")
            self.last_prepare = result
            return result

        if self.config.mode == "trace":
            result = SkillPrepareResult(
                mode=self.config.mode,
                status="trace_only",
                summary="Skill trace mode is enabled; no skill context was injected.",
            )
            self.last_prepare = result
            return result

        skills = self.store.list_skills(include_disabled=False)
        match = self.retriever.best_match(
            task,
            skills,
            current_app=current_app,
            platform=platform,
            threshold=self.config.retrieval_threshold,
        )

        if match:
            skill = match.skill
            status = "reused"
            score = match.score
            reason = match.reason
        elif self.config.evolve_enabled and self.config.auto_generate:
            skill = self.generator.generate(
                task,
                current_app=current_app,
                platform=platform,
                screenshot=screenshot,
            )
            status = "generated"
            score = 0.0
            generator_source = self.generator.last_generation_source or "unknown"
            if generator_source == "paper_prompt_3step":
                generator_kind = "paper prompt generator (3-step)"
            elif generator_source == "fallback":
                generator_kind = "fallback generator"
            else:
                generator_kind = generator_source
            reason = f"no metadata match; generated initial skill package via {generator_kind}"
            if self.generator.last_generation_error and generator_source == "fallback":
                reason += f"; fallback reason: {self.generator.last_generation_error}"
        else:
            result = SkillPrepareResult(
                mode=self.config.mode,
                status="no_match",
                summary="No reusable skill matched the current task.",
            )
            self.last_prepare = result
            return result

        self.active_skill = skill
        context = skill.render_skill_context(
            max_chars=self.config.max_context_chars,
            include_failure_examples=self.config.include_failure_examples,
        )
        params = task_runtime_parameters(task)
        if params:
            context = _append_runtime_parameters(context, params, self.config.max_context_chars)
        summary = (
            f"{status}: {skill.display_name} "
            f"(id={skill.skill_id}, score={score:.2f}; {reason})"
        )
        result = SkillPrepareResult(
            mode=self.config.mode,
            status=status,
            context=context,
            skill_id=skill.skill_id,
            display_name=skill.display_name,
            retrieval_score=score,
            summary=summary,
            skill_root=str(skill.root),
        )
        self.last_prepare = result
        skill.record_run({
            "event": "prepare",
            "task": task,
            "mode": self.config.mode,
            "status": status,
            "retrieval_score": score,
            "current_app": current_app,
            "context_chars": len(context),
        })
        return result

    def finish(
        self,
        *,
        task: str,
        success: bool,
        result: str = "",
        trace_path: str | Path | None = None,
    ) -> SkillFinishResult:
        if not self.config.enabled or not self.active_skill:
            return SkillFinishResult(mode=self.config.mode, success=success, summary="No active skill.")

        skill = self.active_skill
        skill.record_iteration(success=success, summary=result or ("success" if success else "failed"))

        if success or not self.config.evolve_enabled:
            summary = f"Skill `{skill.display_name}` recorded {'success' if success else 'failure'}."
            return SkillFinishResult(
                mode=self.config.mode,
                success=success,
                skill_id=skill.skill_id,
                display_name=skill.display_name,
                summary=summary,
            )

        feedback = self.verifier.diagnose(
            instruction=task,
            trace_path=str(trace_path) if trace_path else None,
            success=False,
            result=result,
        )

        if self.config.require_review:
            skill.record_edit({
                "event": "pending_revision",
                "reason": feedback.root_cause,
                "feedback": feedback.to_dict(),
            })
            return SkillFinishResult(
                mode=self.config.mode,
                success=False,
                skill_id=skill.skill_id,
                display_name=skill.display_name,
                feedback=feedback.to_dict(),
                edits=[],
                summary="Verifier feedback saved; revision requires review.",
            )

        edits = self.evolution.refine(skill, feedback)
        return SkillFinishResult(
            mode=self.config.mode,
            success=False,
            skill_id=skill.skill_id,
            display_name=skill.display_name,
            feedback=feedback.to_dict(),
            edits=edits,
            summary=f"Skill `{skill.display_name}` revised after {feedback.failure_type}.",
        )

    def list_skill_summaries(self) -> list[dict[str, Any]]:
        return self.store.summaries()

    def render_skill_detail(self, skill_id: str) -> str:
        skill = self.store.get(skill_id)
        if not skill:
            return f"Skill not found: {skill_id}"
        plan_body, plan_revisions = _split_doc_revisions(skill.read_doc("plan.md"))
        backup_body, backup_revisions = _split_doc_revisions(skill.read_doc("backup.md"))
        recover_body, recover_revisions = _split_doc_revisions(skill.read_doc("recover.md"))

        parts = [
            f"# {skill.display_name}",
            f"- ID: `{skill.skill_id}`",
            f"- Intent: {skill.meta.task_intent}",
            f"- Apps: {', '.join(skill.meta.domain_app) or '-'}",
            f"- Success rate: {skill.meta.evolution_status.success_rate}",
            "",
            "**plan.md:**",
            _format_doc_block(plan_body),
            "",
            _format_revision_block("plan.md revisions", plan_revisions),
            "",
            "**backup.md:**",
            _format_doc_block(backup_body),
            "",
            _format_revision_block("backup.md revisions", backup_revisions),
            "",
            "**recover.md:**",
            _format_doc_block(recover_body),
            "",
            _format_revision_block("recover.md revisions", recover_revisions),
            "",
            "**failure_examples:**",
            _format_failure_examples(skill),
        ]
        return "\n".join(parts)


def _split_doc_revisions(text: str) -> tuple[str, list[str]]:
    """Separate original guidance from appended auto-revision notes."""
    text = _strip_doc_title(text.strip())
    if not text:
        return "", []

    pattern = re.compile(r"(?im)^\s*(?:##\s*)?(?:Auto Revision|Revision note):?\s*$")
    marked_pattern = re.compile(
        r"(?is)<!--\s*evoskill-[^>]+:start\s*-->\s*(.*?)\s*<!--\s*evoskill-[^>]+:end\s*-->"
    )
    marked = list(marked_pattern.finditer(text))
    if marked:
        first = marked[0]
        base = text[: first.start()].strip()
        revisions = [_strip_doc_title(match.group(1).strip()) for match in marked if match.group(1).strip()]
        return base, revisions

    matches = list(pattern.finditer(text))
    if not matches:
        return text.strip(), []

    base = text[: matches[0].start()].strip()
    revisions: list[str] = []
    for idx, match in enumerate(matches):
        start = match.end()
        end = matches[idx + 1].start() if idx + 1 < len(matches) else len(text)
        body = text[start:end].strip()
        if body:
            revisions.append(_strip_doc_title(body))
    return base, revisions


def _strip_doc_title(text: str) -> str:
    """Drop generic Markdown titles that are too prominent in the Web UI."""
    lines = text.splitlines()
    while lines and not lines[0].strip():
        lines.pop(0)
    if lines:
        first = lines[0].strip()
        normalized = first.lstrip("#").strip().lower()
        if normalized in {
            "plan",
            "backup locators",
            "backup",
            "recovery",
            "recover",
            "failure example",
        }:
            lines.pop(0)
    return "\n".join(lines).strip()


def _format_doc_block(text: str) -> str:
    text = text.strip() or "(empty)"
    return f"```markdown\n{text}\n```"


def _format_revision_block(label: str, revisions: list[str]) -> str:
    if not revisions:
        return ""
    parts = [f"**{label}:**"]
    for idx, revision in enumerate(revisions, 1):
        parts.append(f"\nRevision {idx}:")
        parts.append(f"```markdown\n{revision.strip()}\n```")
    return "\n".join(parts)


def _append_runtime_parameters(context: str, params: dict[str, str], max_chars: int) -> str:
    lines = [
        "",
        "",
        "## Current Task Parameters",
        "Use these current task parameters over any concrete examples stored in the skill package.",
    ]
    lines.extend(f"- {key}: {value}" for key, value in params.items())
    suffix = "\n".join(lines)
    updated = context.rstrip() + suffix
    if max_chars and len(updated) > max_chars:
        keep = max(0, max_chars - len(suffix) - 32)
        updated = context[:keep].rstrip() + "\n... [skill context truncated]" + suffix
    return updated


def _format_failure_examples(skill) -> str:
    names = skill.list_failure_examples()
    if not names:
        return "-"
    parts: list[str] = []
    for name in names:
        content = (skill.failure_examples_dir / name).read_text(encoding="utf-8").strip()
        content = _strip_doc_title(content)
        parts.append(f"\n{name}:")
        parts.append(f"```markdown\n{content or '(empty)'}\n```")
    return "\n".join(parts)
