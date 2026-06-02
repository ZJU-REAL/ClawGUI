"""Initial skill package generation."""

from __future__ import annotations

import re
from typing import Any

from clawgui_skills.intent import infer_apps, normalize_slot, parse_message_task, task_frame
from clawgui_skills.intent import stable_task_intent, task_arguments, task_keywords
from clawgui_skills.model_io import SkillModelClient, compact_raw_preview, parse_json_payload
from clawgui_skills.prompts import (
    SKILL_GEN_BACKUP_USER_PROMPT,
    SKILL_GEN_META_PLAN_USER_PROMPT,
    SKILL_GEN_RECOVER_USER_PROMPT,
    SKILL_GEN_SYSTEM_PROMPT,
    render_template,
)
from clawgui_skills.store import SkillStore


class SkillGenerator:
    """Generate an initial structured skill package."""

    def __init__(
        self,
        store: SkillStore,
        *,
        model_client: SkillModelClient | None = None,
        mode: str = "auto",
    ):
        self.store = store
        self.model_client = model_client
        self.mode = (mode or "auto").lower()
        self.last_generation_source = ""
        self.last_generation_error = ""
        self.last_generation_raw_preview = ""
        self.last_generation_repairs: list[str] = []

    def generate(
        self,
        task: str,
        *,
        current_app: str = "",
        platform: str = "Android",
        screenshot: Any = None,
        initial_a11y: str | None = None,
    ):
        self.last_generation_source = ""
        self.last_generation_error = ""
        self.last_generation_raw_preview = ""
        self.last_generation_repairs = []
        if self._model_enabled:
            try:
                skill = self._generate_with_model(
                    task,
                    current_app=current_app,
                    platform=platform,
                    screenshot=screenshot,
                    initial_a11y=initial_a11y,
                )
                self.last_generation_source = "paper_prompt_3step"
                self._record_generation_source(skill)
                return skill
            except Exception as e:
                self.last_generation_error = self._format_generation_error(e)
                if self.mode == "model":
                    raise

        skill = self._generate_fallback(task, current_app=current_app, platform=platform)
        self.last_generation_source = "fallback"
        self._record_generation_source(skill)
        return skill

    @property
    def _model_enabled(self) -> bool:
        return self.mode in {"auto", "model"} and bool(self.model_client and self.model_client.available)

    def _generate_with_model(
        self,
        task: str,
        *,
        current_app: str = "",
        platform: str = "Android",
        screenshot: Any = None,
        initial_a11y: str | None = None,
    ):
        assert self.model_client is not None
        task_name = self._display_name(task, self._infer_apps(task, current_app))
        initial_a11y = self._truncate_a11y(initial_a11y or "")

        step1_prompt = render_template(
            SKILL_GEN_META_PLAN_USER_PROMPT,
            task_name=task_name,
            instruction=task,
            initial_a11y=initial_a11y or "(not available)",
        )
        raw1 = self.model_client.chat(
            system_prompt=self._skill_gen_system_prompt(),
            user_text=step1_prompt,
        )
        meta_plan = self._parse_or_retry_json(
            raw1,
            step_name="skill generation step 1/meta_plan",
            original_prompt=step1_prompt,
            expected_schema=(
                '{"skill_id":"skill_<short_slug>","task_intent":"...","domain_app":["..."],'
                '"platform":"Android","keywords":["..."],"arguments":["<recipient>"],"plan_md":"# Plan\\n1. ..."}'
            ),
            text_plan_fallback={
                "task": task,
                "current_app": current_app,
                "platform": platform,
            },
        )
        plan_md = str(meta_plan.get("plan_md") or "").strip()
        plan_md = _validate_generated_doc(plan_md, "plan_md")
        if not plan_md:
            raise ValueError("Model skill generator returned empty plan_md")

        step2_prompt = render_template(
            SKILL_GEN_BACKUP_USER_PROMPT,
            instruction=task,
            plan_md=plan_md,
        )
        raw2 = self.model_client.chat(
            system_prompt=self._skill_gen_system_prompt(),
            user_text=step2_prompt,
        )
        backup_payload = self._parse_or_retry_json(
            raw2,
            step_name="skill generation step 2/backup",
            original_prompt=step2_prompt,
            expected_schema='{"backup_md":"# Backup Locators\\n- ..."}',
            wrap_text_key="backup_md",
        )
        backup_md = _validate_generated_doc(str(backup_payload.get("backup_md") or "").strip(), "backup_md")

        step3_prompt = render_template(
            SKILL_GEN_RECOVER_USER_PROMPT,
            instruction=task,
            plan_md=plan_md,
            backup_md=backup_md or "(empty)",
        )
        raw3 = self.model_client.chat(
            system_prompt=self._skill_gen_system_prompt(),
            user_text=step3_prompt,
        )
        recover_payload = self._parse_or_retry_json(
            raw3,
            step_name="skill generation step 3/recover",
            original_prompt=step3_prompt,
            expected_schema='{"recover_md":"# Recovery\\n- ..."}',
            wrap_text_key="recover_md",
        )
        recover_md = _validate_generated_doc(str(recover_payload.get("recover_md") or "").strip(), "recover_md")

        apps = _clean_string_list(meta_plan.get("domain_app")) or self._infer_apps(task, current_app)
        model_arguments = _normalize_arguments(meta_plan.get("arguments"))
        arguments = task_arguments(task) or model_arguments
        frame = task_frame(task, apps=apps, arguments=arguments)
        canonical_intent = frame.stable_intent() or stable_task_intent(task, apps)
        display_name = self._display_name(task, apps, arguments=arguments)
        keywords = task_keywords(task, apps) or _clean_string_list(meta_plan.get("keywords"))[:20]

        return self.store.create_skill(
            canonical_intent,
            display_name=display_name,
            domain_app=apps,
            platform=str(meta_plan.get("platform") or platform or "Android"),
            keywords=keywords,
            arguments=arguments,
            skill_id=_canonical_skill_id(task, apps, arguments, fallback=meta_plan.get("skill_id") or display_name),
            plan=plan_md,
            backup=backup_md,
            recover=recover_md,
        )

    def _generate_fallback(
        self,
        task: str,
        *,
        current_app: str = "",
        platform: str = "Android",
    ):
        apps = self._infer_apps(task, current_app)
        display_name = self._display_name(task, apps)
        plan = self._plan(task, apps)
        backup = self._backup(apps)
        recover = self._recover()
        return self.store.create_skill(
            stable_task_intent(task, apps),
            display_name=display_name,
            domain_app=apps,
            platform=platform,
            keywords=task_keywords(task, apps),
            arguments=task_arguments(task),
            skill_id=_canonical_skill_id(task, apps, task_arguments(task), fallback=display_name),
            plan=plan,
            backup=backup,
            recover=recover,
        )

    def _record_generation_source(self, skill) -> None:
        payload = {
            "event": "generation_source",
            "source": self.last_generation_source,
            "mode": self.mode,
        }
        if self.last_generation_error:
            payload["fallback_reason"] = self.last_generation_error
        if self.last_generation_raw_preview:
            payload["raw_preview"] = self.last_generation_raw_preview
        if self.last_generation_repairs:
            payload["repairs"] = list(self.last_generation_repairs)
        skill.record_edit(payload)

    def _parse_or_retry_json(
        self,
        raw: str,
        *,
        step_name: str,
        original_prompt: str,
        expected_schema: str,
        text_plan_fallback: dict[str, str] | None = None,
        wrap_text_key: str = "",
    ) -> dict[str, Any]:
        try:
            return parse_json_payload(raw)
        except Exception as first_error:
            self.last_generation_raw_preview = compact_raw_preview(raw)
            if self.model_client is None:
                raise ValueError(self._json_error_message(step_name, first_error, raw)) from first_error

            retry_prompt = (
                "Your previous answer for "
                f"{step_name} was not valid JSON and could not be parsed.\n\n"
                f"Parser error: {first_error}\n\n"
                "Previous answer preview:\n"
                f"```\n{compact_raw_preview(raw, limit=1200)}\n```\n\n"
                "Rewrite the answer now. Return ONLY one strict JSON object. "
                "Do not include markdown fences, natural-language commentary, XML, tool calls, "
                "or GUI actions such as do(action=...).\n\n"
                "You are authoring reusable skill-package documents, not executing the phone task. "
                "Do not analyze the current screenshot, do not mention what is already typed, "
                "do not write first-person thoughts, and do not include concrete runtime values "
                "such as a specific recipient, group, message text, date, or query except as placeholders.\n\n"
                f"Expected schema example:\n{expected_schema}\n\n"
                "Original instruction:\n"
                f"{original_prompt}"
            )
            retry_raw = self.model_client.chat(
                system_prompt=self._json_repair_system_prompt(),
                user_text=retry_prompt,
            )
            try:
                return parse_json_payload(retry_raw)
            except Exception as second_error:
                self.last_generation_raw_preview = compact_raw_preview(retry_raw)
                if text_plan_fallback:
                    repaired = self._salvage_meta_plan_from_text(
                        retry_raw,
                        raw,
                        task=text_plan_fallback.get("task", ""),
                        current_app=text_plan_fallback.get("current_app", ""),
                        platform=text_plan_fallback.get("platform", "Android"),
                    )
                    if repaired:
                        self.last_generation_repairs.append("step1_text_plan")
                        return repaired
                if wrap_text_key:
                    wrapped_text = _select_skill_doc_text(retry_raw, raw)
                    if wrapped_text:
                        self.last_generation_repairs.append(f"{wrap_text_key}_text_wrap")
                        return {wrap_text_key: _ensure_doc_heading(wrapped_text, wrap_text_key)}
                raise ValueError(self._json_error_message(step_name, second_error, retry_raw)) from second_error

    def _salvage_meta_plan_from_text(
        self,
        *raw_candidates: str,
        task: str,
        current_app: str,
        platform: str,
    ) -> dict[str, Any] | None:
        text = _select_skill_doc_text(*raw_candidates)
        if not text:
            return None
        apps = self._infer_apps(f"{task}\n{text}", current_app)
        arguments = task_arguments(task)
        return {
            "skill_id": _canonical_skill_id(task, apps, arguments, fallback=self._display_name(task, apps)),
            "task_intent": stable_task_intent(task, apps),
            "domain_app": apps,
            "platform": platform or "Android",
            "keywords": task_keywords(task, apps),
            "arguments": arguments,
            "plan_md": _ensure_doc_heading(text, "plan_md"),
        }

    def _format_generation_error(self, error: Exception) -> str:
        message = str(error)
        if self.last_generation_raw_preview:
            message += f"; raw preview: {self.last_generation_raw_preview}"
        return message

    def _skill_gen_system_prompt(self) -> str:
        return SKILL_GEN_SYSTEM_PROMPT

    def _json_repair_system_prompt(self) -> str:
        return (
            "You are a JSON repair assistant for GUI skill-package generation. "
            "Return only strict JSON."
        )

    @staticmethod
    def _json_error_message(step_name: str, error: Exception, raw: str) -> str:
        return (
            f"{step_name} did not return parseable JSON: {error}; "
            f"raw preview: {compact_raw_preview(raw)}"
        )

    @staticmethod
    def _truncate_a11y(initial_a11y: str, limit: int = 4000) -> str:
        if len(initial_a11y) <= limit:
            return initial_a11y
        return initial_a11y[:limit] + "\n... [truncated]"

    @staticmethod
    def _infer_apps(task: str, current_app: str) -> list[str]:
        return infer_apps(task, current_app)

    @staticmethod
    def _display_name(task: str, apps: list[str], arguments: list[str] | None = None) -> str:
        frame = task_frame(task, apps=apps, arguments=arguments)
        short_task = frame.operation_display_name
        if not short_task:
            tokens = re.findall(r"[\w\u4e00-\u9fff]+", task)
            short_task = " ".join(tokens[:4]) if tokens else "GUI Task"
        if apps:
            return f"{apps[0]} - {short_task}"[:48]
        return short_task[:48]

    @staticmethod
    def _plan(task: str, apps: list[str]) -> str:
        app_hint = f" Target app candidates: {', '.join(apps)}." if apps else ""
        frame = task_frame(task, apps=apps)
        if frame.operation == "send_message":
            app = apps[0] if apps else "the messaging app"
            return (
                "# Plan\n\n"
                f"Task intent: send the current task message to the current task recipient in {app}.{app_hint}\n\n"
                f"1. If the current screen is not already in {app}, launch {app} with `Launch` or the visible app icon.\n"
                "2. Read the `Current Task Parameters` section for the latest recipient and message values.\n"
                "3. Navigate to the chat list or search entry, then search for the current recipient if it is not visible.\n"
                "4. Open the chat whose visible title matches the current recipient and verify the conversation header.\n"
                "5. Tap the message input field, type the current message, and use the visible send button or keyboard send action.\n"
                "6. Finish only after the current message is visible in the conversation as the latest sent message.\n"
            )
        if frame.operation == "create_event":
            app = apps[0] if apps else "the calendar app"
            return (
                "# Plan\n\n"
                f"Task intent: create a calendar event in {app}.{app_hint}\n\n"
                f"1. If the current screen is not already in {app}, launch {app} or navigate to it from the visible UI.\n"
                "2. Read `Current Task Parameters` for event_title, time, location, and other current values.\n"
                "3. Open the create/add-event flow using a visible add button, menu item, or calendar action.\n"
                "4. Fill each available field with the current task parameters, verifying focus before typing.\n"
                "5. Save only after the event details match the requested parameters.\n"
                "6. Finish only when the event is visible, saved, or the UI clearly confirms creation.\n"
            )
        if frame.operation == "toggle_setting":
            app = apps[0] if apps else "Settings"
            return (
                "# Plan\n\n"
                f"Task intent: change a setting in {app}.{app_hint}\n\n"
                f"1. If the current screen is not already in {app}, launch {app} or navigate there.\n"
                "2. Read `Current Task Parameters` for setting_name and desired state.\n"
                "3. Search or navigate to the setting using visible labels before using coordinates.\n"
                "4. Inspect the current switch/state and change it only if it does not match the desired state.\n"
                "5. Finish only when the target setting visibly matches the requested state.\n"
            )
        if frame.operation == "search_open":
            app = apps[0] if apps else "the target app"
            return (
                "# Plan\n\n"
                f"Task intent: search and open a result in {app}.{app_hint}\n\n"
                f"1. If the current screen is not already in {app}, launch or navigate to {app}.\n"
                "2. Read `Current Task Parameters` for query and target result values.\n"
                "3. Focus the visible search field or search icon, then type the current query.\n"
                "4. Wait for results, compare visible titles/snippets with the current result target if provided.\n"
                "5. Open the best matching result and verify the destination screen.\n"
                "6. Finish only after the requested result/page is visible.\n"
            )
        if frame.operation == "create_note":
            app = apps[0] if apps else "the notes app"
            return (
                "# Plan\n\n"
                f"Task intent: create a note in {app}.{app_hint}\n\n"
                f"1. If the current screen is not already in {app}, launch or navigate to {app}.\n"
                "2. Read `Current Task Parameters` for title and content values.\n"
                "3. Open the new-note/create flow using a visible add or compose control.\n"
                "4. Fill title/content fields with the current task parameters, verifying text appears correctly.\n"
                "5. Save or go back only when the note app clearly persists the note.\n"
                "6. Finish only after the created note is visible or saved.\n"
            )
        return (
            "# Plan\n\n"
            f"Task intent: {task}.{app_hint}\n\n"
            "1. Identify whether the current screen is already in the target app or target flow.\n"
            "2. If not, launch or navigate to the target app using visible app names, search, or home-screen icons.\n"
            "3. Progress one screen at a time and verify each transition from the screenshot.\n"
            "4. Prefer visible text labels and stable navigation controls over raw coordinate memory.\n"
            "5. Finish only when the target state is visible; otherwise continue with a conservative next action.\n"
        )

    @staticmethod
    def _backup(apps: list[str]) -> str:
        app_line = f"- App-specific hints should be checked first for: {', '.join(apps)}.\n" if apps else ""
        return (
            "# Backup Locators\n\n"
            f"{app_line}"
            "- For chat apps, search entry points may appear as a search icon, a top search field, or a plus/menu flow.\n"
            "- Chat send controls may be a paper-plane icon, a text button, or the keyboard enter/send key.\n"
            "- Calendar create controls may appear as plus buttons, floating action buttons, or header menu items.\n"
            "- Settings screens often expose search; use it when the target option is not visible.\n"
            "- Search result pages should be matched by visible titles, snippets, or destination headers.\n"
            "- Note editors may autosave; verify saved content after navigating back if no save button exists.\n"
            "- If a named button is missing, inspect nearby icons and section headers.\n"
            "- If a target list item is absent, scroll slowly and re-check after each scroll.\n"
            "- If text input does not focus, tap the field center once, then use the keyboard action.\n"
        )

    @staticmethod
    def _recover() -> str:
        return (
            "# Recovery\n\n"
            "- If the same action repeats without progress, choose a different route or go back once.\n"
            "- If a permission dialog appears, choose the option that allows the requested task when safe.\n"
            "- Ask for takeover only when the current screen explicitly requires login, captcha, password, payment, or another user-only verification step.\n"
            "- Do not classify ordinary chat lists, contacts, search pages, or message editors as sensitive screens.\n"
        )

    @staticmethod
    def _message_task(task: str) -> tuple[str, str] | None:
        return parse_message_task(task)

    @staticmethod
    def _clean_chat_target(raw: str) -> str:
        from clawgui_skills.intent import clean_chat_target
        return clean_chat_target(raw)


def _clean_string_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item).strip() for item in value if str(item).strip()]


def _normalize_arguments(value: Any) -> list[str]:
    arguments: list[str] = []
    for item in _clean_string_list(value):
        raw_key = item.split(":", 1)[0].strip().strip("<>{}[]() ")
        key = normalize_slot(raw_key)
        if not key:
            continue
        if key in {"contact", "chat", "target", "person", "user"}:
            key = "recipient"
        if key in {"text", "content", "body"}:
            key = "message"
        candidate = f"{key}:<variable>"
        if candidate not in arguments:
            arguments.append(candidate)
    return arguments[:20]


def _canonical_skill_id(
    task: str,
    apps: list[str],
    arguments: list[str],
    *,
    fallback: Any,
) -> str:
    frame = task_frame(task, apps=apps, arguments=arguments)
    app_slug = normalize_slot(apps[0]) if apps else ""
    operation_slug = _operation_slug(frame.operation)
    if operation_slug:
        if app_slug:
            return f"{app_slug}_{operation_slug}"
        return operation_slug
    return _normalize_model_skill_id(fallback, fallback="gui_skill")


def _operation_slug(operation: str) -> str:
    return {
        "send_message": "send_message",
        "create_event": "create_event",
        "toggle_setting": "toggle_setting",
        "search_open": "search_and_open",
        "create_note": "create_note",
    }.get(operation, "")


def _normalize_model_skill_id(value: Any, fallback: str) -> str:
    raw = str(value or fallback).strip()
    raw = re.sub(r"^skill_", "", raw, flags=re.I)
    raw = normalize_slot(raw)
    parts = raw.split("_")
    if len(parts) > 8:
        raw = "_".join(parts[:8])
    return raw or normalize_slot(fallback) or "gui_skill"


def _looks_like_skill_doc_text(raw: str) -> bool:
    text = (raw or "").strip()
    if len(text) < 40:
        return False
    lowered = text.lower()
    if _is_contaminated_skill_generation_text(text):
        return False
    plan_markers = [
        "step",
        "open",
        "launch",
        "navigate",
        "tap",
        "type",
        "send",
        "verify",
        "alternative",
        "backup",
        "fallback",
        "locator",
        "not visible",
        "recovery",
        "permission",
        "complete skill package",
        "skill package",
        "skill_id",
        "task_intent",
        "domain app",
        "domain apps",
        "plan_md",
        "required information",
    ]
    return any(marker in lowered for marker in plan_markers) or bool(re.search(r"(^|\n)\s*\d+[\.\)]\s+", text))


def _is_contaminated_skill_generation_text(raw: str) -> bool:
    text = (raw or "").strip()
    lowered = text.lower()
    hard_blocked = [
        "do(action=",
        "<tool_call>",
        "parsing action",
        "take_over",
        "sensitive screen",
        "无法截图",
        "need connect-key",
    ]
    if any(marker in lowered for marker in hard_blocked):
        return True

    contamination_markers = [
        "now i need",
        "let me",
        "i've been asked",
        "i have been asked",
        "looking at the screenshot",
        "looking at the current screen",
        "the current screen",
        "the user wants me",
        "appears to be already",
        "already typed",
        "tap the send button",
        "complete the task",
        "think about the skill id",
    ]
    contamination_count = sum(1 for marker in contamination_markers if marker in lowered)
    if contamination_count >= 2:
        return True

    repeated_generation = "now i need to generate the complete skill package"
    if lowered.count(repeated_generation) >= 2:
        return True
    if lowered.count("```") >= 2 and repeated_generation in lowered:
        return True
    return False


def _select_skill_doc_text(*candidates: str) -> str:
    for candidate in candidates:
        if _looks_like_skill_doc_text(candidate):
            return candidate.strip()
    return ""


def _validate_generated_doc(raw: str, key: str) -> str:
    text = (raw or "").strip()
    if not text:
        return ""
    if _is_contaminated_skill_generation_text(text):
        raise ValueError(f"Model skill generator returned contaminated {key}")
    return text


def _ensure_doc_heading(raw: str, key: str) -> str:
    text = (raw or "").strip()
    if text.startswith("#"):
        return text
    heading = {
        "plan_md": "# Plan",
        "backup_md": "# Backup Locators",
        "recover_md": "# Recovery",
    }.get(key, "# Notes")
    return f"{heading}\n\n{text}"
