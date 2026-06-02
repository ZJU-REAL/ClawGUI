"""Skill store management."""

from __future__ import annotations

from pathlib import Path

from clawgui_skills.package import BACKUP_FILENAME, PLAN_FILENAME, RECOVER_FILENAME
from clawgui_skills.package import SkillPackage, compact_display_name, slugify
from clawgui_skills.schema import SkillMeta


class SkillStore:
    """Persistent directory of structured GUI skills."""

    def __init__(self, root: str | Path = "skill_store"):
        self.root = Path(root).expanduser().resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def list_skills(self, include_disabled: bool = True) -> list[SkillPackage]:
        skills: list[SkillPackage] = []
        for child in sorted(self.root.iterdir()):
            if not child.is_dir():
                continue
            meta_path = child / "meta_info.json"
            if not meta_path.exists():
                continue
            try:
                skill = SkillPackage.load(child)
            except Exception:
                continue
            if include_disabled or skill.meta.enabled:
                skills.append(skill)
        return skills

    def summaries(self) -> list[dict]:
        return [skill.to_summary() for skill in self.list_skills()]

    def get(self, skill_id: str) -> SkillPackage | None:
        path = self.root / skill_id
        if not (path / "meta_info.json").exists():
            return None
        return SkillPackage.load(path)

    def create_skill(
        self,
        task: str,
        *,
        display_name: str | None = None,
        domain_app: list[str] | None = None,
        platform: str = "Android",
        keywords: list[str] | None = None,
        arguments: list[str] | None = None,
        skill_id: str | None = None,
        plan: str = "",
        backup: str = "",
        recover: str = "",
    ) -> SkillPackage:
        display_name = display_name or compact_display_name(task)
        base_id = slugify(skill_id or display_name)
        skill_id = self._unique_id(base_id)
        meta = SkillMeta(
            skill_id=skill_id,
            display_name=display_name,
            task_intent=task,
            domain_app=domain_app or [],
            platform=platform,
            keywords=keywords or self._derive_keywords(task, domain_app or []),
            arguments=arguments or [],
        )
        skill = SkillPackage(self.root / skill_id, meta)
        skill.ensure_layout()
        skill.write_doc(PLAN_FILENAME, plan or self._default_plan(task))
        skill.write_doc(BACKUP_FILENAME, backup or self._default_backup())
        skill.write_doc(RECOVER_FILENAME, recover or self._default_recover())
        skill.save_meta()
        skill.record_edit({
            "event": "create_skill",
            "file": "meta_info.json",
            "reason": "initial skill package materialized",
        })
        return skill

    def _unique_id(self, base_id: str) -> str:
        candidate = base_id
        index = 2
        while (self.root / candidate).exists():
            candidate = f"{base_id}_{index}"
            index += 1
        return candidate

    @staticmethod
    def _derive_keywords(task: str, apps: list[str]) -> list[str]:
        tokens = slugify(task).split("_")
        keywords = [t for t in tokens if len(t) >= 2][:12]
        for app in apps:
            app_token = slugify(app)
            if app_token and app_token not in keywords:
                keywords.append(app_token)
        return keywords

    @staticmethod
    def _default_plan(task: str) -> str:
        return (
            f"# Plan\n\n"
            f"Task intent: {task}\n\n"
            "1. Confirm the current app and screen state from the screenshot.\n"
            "2. Navigate toward the requested target using visible labels, icons, and app navigation patterns.\n"
            "3. Before every irreversible action, verify that the screen matches the task intent.\n"
            "4. Finish only after the requested end state is visible or strongly implied by the UI.\n"
        )

    @staticmethod
    def _default_backup() -> str:
        return (
            "# Backup Locators\n\n"
            "- If an exact text label is absent, use nearby icons, tab positions, and repeated layout patterns.\n"
            "- Prefer visible navigation controls over coordinate-only guesses.\n"
            "- If a target item is off-screen, scroll in small increments and re-check the screenshot.\n"
        )

    @staticmethod
    def _default_recover() -> str:
        return (
            "# Recovery\n\n"
            "- If the screen does not change after an action, wait once, then choose an alternate visible path.\n"
            "- If navigation enters an unrelated page, go back and resume from the last confirmed state.\n"
            "- Ask for takeover only when the current screen explicitly requires login, captcha, password, payment, or another user-only verification step.\n"
            "- Do not classify ordinary app navigation, search, contact, chat, or editor screens as sensitive screens.\n"
        )
