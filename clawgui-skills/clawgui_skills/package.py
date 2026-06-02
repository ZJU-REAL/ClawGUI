"""Skill package representation and persistence."""

from __future__ import annotations

import json
import re
import shutil
from pathlib import Path
from typing import Any

from clawgui_skills.schema import SkillMeta, now_iso

META_FILENAME = "meta_info.json"
DOCS_DIRNAME = "docs"
FAILURE_EXAMPLES_DIRNAME = "failure_examples"
VERSIONS_DIRNAME = "versions"
PLAN_FILENAME = "plan.md"
BACKUP_FILENAME = "backup.md"
RECOVER_FILENAME = "recover.md"
EDITS_LOG = "edits.jsonl"
RUNS_LOG = "runs.jsonl"


def slugify(value: str, fallback: str = "skill") -> str:
    value = value.lower().strip()
    value = re.sub(r"[^a-z0-9\u4e00-\u9fff]+", "_", value)
    value = re.sub(r"_+", "_", value).strip("_")
    return value or fallback


def compact_display_name(task: str, max_words: int = 8) -> str:
    words = re.findall(r"[\w\u4e00-\u9fff]+", task)
    if not words:
        return "GUI Skill"
    if len(words) <= max_words:
        name = " ".join(words)
    else:
        name = " ".join(words[:max_words])
    return name[:60]


class SkillPackage:
    """A structured, editable GUI skill package."""

    def __init__(self, root: str | Path, meta: SkillMeta):
        self.root = Path(root)
        self.meta = meta

    @property
    def skill_id(self) -> str:
        return self.meta.skill_id

    @property
    def display_name(self) -> str:
        return self.meta.display_name

    @property
    def meta_path(self) -> Path:
        return self.root / META_FILENAME

    @property
    def docs_dir(self) -> Path:
        return self.root / DOCS_DIRNAME

    @property
    def failure_examples_dir(self) -> Path:
        return self.root / FAILURE_EXAMPLES_DIRNAME

    @property
    def versions_dir(self) -> Path:
        return self.root / VERSIONS_DIRNAME

    @property
    def plan_path(self) -> Path:
        return self.docs_dir / PLAN_FILENAME

    @property
    def backup_path(self) -> Path:
        return self.docs_dir / BACKUP_FILENAME

    @property
    def recover_path(self) -> Path:
        return self.docs_dir / RECOVER_FILENAME

    @classmethod
    def load(cls, root: str | Path) -> "SkillPackage":
        root = Path(root)
        meta = SkillMeta.from_dict(json.loads((root / META_FILENAME).read_text(encoding="utf-8")))
        return cls(root, meta)

    def ensure_layout(self) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        self.docs_dir.mkdir(parents=True, exist_ok=True)
        self.failure_examples_dir.mkdir(parents=True, exist_ok=True)
        self.versions_dir.mkdir(parents=True, exist_ok=True)

    def save_meta(self) -> None:
        self.ensure_layout()
        self.meta.updated_at = now_iso()
        self.meta_path.write_text(
            json.dumps(self.meta.to_dict(), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    def read_doc(self, name: str) -> str:
        path = self.docs_dir / name
        if not path.exists():
            return ""
        return path.read_text(encoding="utf-8")

    def write_doc(self, name: str, content: str) -> None:
        self.ensure_layout()
        (self.docs_dir / name).write_text(content, encoding="utf-8")

    def append_doc(self, name: str, content: str) -> None:
        self.ensure_layout()
        path = self.docs_dir / name
        with path.open("a", encoding="utf-8") as f:
            if path.exists() and path.stat().st_size > 0:
                f.write("\n")
            f.write(content)

    def list_failure_examples(self) -> list[str]:
        if not self.failure_examples_dir.exists():
            return []
        return sorted(p.name for p in self.failure_examples_dir.iterdir() if p.is_file())

    def add_failure_example(self, content: str, name: str | None = None) -> Path:
        self.ensure_layout()
        if name is None:
            name = f"failure_{len(self.list_failure_examples()) + 1:03d}.md"
        path = self.failure_examples_dir / name
        path.write_text(content, encoding="utf-8")
        return path

    def snapshot_version(self, reason: str = "") -> str:
        self.ensure_layout()
        idx = len([p for p in self.versions_dir.iterdir() if p.is_dir()]) + 1
        version_id = f"{idx:04d}"
        target = self.versions_dir / version_id
        target.mkdir(parents=True, exist_ok=True)
        for item in [self.meta_path, self.docs_dir, self.failure_examples_dir]:
            if not item.exists():
                continue
            dest = target / item.name
            if item.is_dir():
                shutil.copytree(item, dest, dirs_exist_ok=True)
            else:
                shutil.copy2(item, dest)
        (target / "snapshot.json").write_text(
            json.dumps({"created_at": now_iso(), "reason": reason}, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return version_id

    def record_edit(self, payload: dict[str, Any]) -> None:
        payload = {"timestamp": now_iso(), **payload}
        with (self.root / EDITS_LOG).open("a", encoding="utf-8") as f:
            f.write(json.dumps(payload, ensure_ascii=False) + "\n")

    def record_run(self, payload: dict[str, Any]) -> None:
        payload = {"timestamp": now_iso(), **payload}
        with (self.root / RUNS_LOG).open("a", encoding="utf-8") as f:
            f.write(json.dumps(payload, ensure_ascii=False) + "\n")

    def record_iteration(self, success: bool, summary: str = "") -> None:
        self.meta.evolution_status.usage_count += 1
        if success:
            self.meta.evolution_status.success_count += 1
        self.meta.evolution_status.last_used_at = now_iso()
        self.meta.evolution_status.last_updated_at = now_iso()
        if summary:
            self.record_run({"success": success, "summary": summary})
        self.save_meta()

    def render_skill_context(
        self,
        max_chars: int = 6000,
        include_failure_examples: bool = True,
    ) -> str:
        """Render a compact execution context for prompt injection."""
        sections: list[str] = [
            "## ClawGUI Skill",
            f"- Skill name: {self.display_name}",
            f"- Skill ID: {self.skill_id}",
            f"- Task intent: {self.meta.task_intent}",
        ]
        if self.meta.domain_app:
            sections.append(f"- Target apps: {', '.join(self.meta.domain_app)}")
        if self.meta.failure_history_summary:
            sections.append(f"- Historical failure lessons: {self.meta.failure_history_summary}")

        for label, path in (
            ("plan.md", self.plan_path),
            ("backup.md", self.backup_path),
            ("recover.md", self.recover_path),
        ):
            if path.exists():
                body = path.read_text(encoding="utf-8").strip()
                if body:
                    if label == "recover.md":
                        body = _soften_takeover_guidance(body)
                    sections.append(f"\n### {label}\n{body}")

        if include_failure_examples:
            for fname in self.list_failure_examples()[-3:]:
                body = (self.failure_examples_dir / fname).read_text(encoding="utf-8").strip()
                if body:
                    sections.append(f"\n### failure_examples/{fname}\n{body}")

        context = "\n".join(sections).strip()
        if max_chars and len(context) > max_chars:
            context = context[:max_chars].rstrip() + "\n... [skill context truncated]"
        return (
            "Use the following reusable GUI skill as procedural guidance. "
            "Adapt it to the current screen and ignore any step that is visibly inapplicable. "
            "Manual takeover is a last resort: request it only for explicit login, captcha, "
            "password, payment, or user-only verification screens, and never for ordinary "
            "chat, contact, search, or editor screens. "
            "Do not answer with the skill text or a plan only; after reasoning, output exactly one "
            "valid action in the current agent action space.\n\n"
            f"{context}"
        )

    def to_summary(self) -> dict[str, Any]:
        status = self.meta.evolution_status
        return {
            "skill_id": self.skill_id,
            "display_name": self.display_name,
            "task_intent": self.meta.task_intent,
            "domain_app": self.meta.domain_app,
            "platform": self.meta.platform,
            "enabled": self.meta.enabled,
            "usage_count": status.usage_count,
            "success_count": status.success_count,
            "success_rate": status.success_rate,
            "revision_count": status.revision_count,
            "updated_at": self.meta.updated_at,
            "root": str(self.root),
        }


def _soften_takeover_guidance(text: str) -> str:
    """Keep legacy recover.md files from over-triggering manual takeover."""
    replacements = {
        "If login, captcha, payment, or private data is required, ask for takeover instead of guessing.": (
            "Ask for takeover only when the current screen explicitly requires login, captcha, "
            "password, payment, or another user-only verification step."
        ),
        "If blocked by login, captcha, permission, or missing user data, ask for takeover instead of guessing.": (
            "Ask for takeover only when the current screen explicitly requires login, captcha, "
            "password, payment, or another user-only verification step."
        ),
        "If blocked by login, captcha, permission, or private data, ask for takeover instead of guessing.": (
            "Ask for takeover only when the current screen explicitly requires login, captcha, "
            "password, payment, or another user-only verification step."
        ),
    }
    for old, new in replacements.items():
        text = text.replace(old, new)

    guard = "- Do not classify ordinary chat, contact, search, editor, or permission-navigation screens as sensitive screens."
    if "ordinary chat" not in text and "sensitive screens" not in text:
        text = text.rstrip() + f"\n{guard}"
    return text
