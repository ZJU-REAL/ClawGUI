from pathlib import Path
import json

import pytest

from clawgui_skills.config import SkillRuntimeConfig
from clawgui_skills.evolution import SkillEvolutionEngine
from clawgui_skills.generator import SkillGenerator
from clawgui_skills.model_io import SkillModelClient
from clawgui_skills.prompts import SKILL_GEN_SYSTEM_PROMPT
from clawgui_skills.runtime import SkillRuntime
from clawgui_skills.schema import VerifierFeedback
from clawgui_skills.store import SkillStore
from clawgui_skills.verifier import IsolatedTrajectoryVerifier


class FakeSkillModelClient(SkillModelClient):
    def __init__(self, responses, model_name: str = "fake-model"):
        super().__init__(client=object(), model_name=model_name)
        self.responses = list(responses)
        self.calls = []

    @property
    def available(self) -> bool:
        return True

    def chat(self, *, system_prompt: str, user_text: str, image=None, timeout: int = 300) -> str:
        self.calls.append({"system_prompt": system_prompt, "user_text": user_text, "image": image})
        if not self.responses:
            raise AssertionError("No fake model response left")
        return self.responses.pop(0)


class FailingSkillModelClient(SkillModelClient):
    def __init__(self):
        super().__init__(client=object(), model_name="fake-model")

    @property
    def available(self) -> bool:
        return True

    def chat(self, *, system_prompt: str, user_text: str, image=None, timeout: int = 300) -> str:
        raise RuntimeError("fake generator failure")


def test_evolve_generates_and_reuses_skill(tmp_path: Path):
    runtime = SkillRuntime(SkillRuntimeConfig(mode="evolve", store_dir=str(tmp_path)))

    first = runtime.prepare(task="Open Settings and turn on Bluetooth", current_app="Settings")
    assert first.status == "generated"
    assert first.skill_id
    assert "ClawGUI Skill" in first.context

    runtime.finish(task="Open Settings and turn on Bluetooth", success=True, result="ok")

    second = runtime.prepare(task="Open Settings and enable Bluetooth", current_app="Settings")
    assert second.status == "reused"
    assert second.skill_id == first.skill_id


def test_failed_evolve_writes_revision(tmp_path: Path):
    runtime = SkillRuntime(SkillRuntimeConfig(mode="evolve", store_dir=str(tmp_path)))
    prepared = runtime.prepare(task="Open Calendar and create a meeting", current_app="Calendar")
    result = runtime.finish(task="Open Calendar and create a meeting", success=False, result="failed")

    assert result.feedback
    assert result.edits
    skill_root = tmp_path / prepared.skill_id
    assert (skill_root / "failure_examples").exists()
    assert list((skill_root / "failure_examples").glob("failure_*.md"))
    assert (skill_root / "versions" / "0001").exists()


def test_off_mode_has_no_context(tmp_path: Path):
    runtime = SkillRuntime(SkillRuntimeConfig(mode="off", store_dir=str(tmp_path)))
    result = runtime.prepare(task="anything")
    assert result.status == "off"
    assert result.context == ""


def test_message_skill_reuses_when_only_message_changes(tmp_path: Path):
    runtime = SkillRuntime(SkillRuntimeConfig(mode="evolve", store_dir=str(tmp_path)))

    first = runtime.prepare(
        task="打开飞书，在ClawGUI这个群里发送test",
        current_app="System Home",
        platform="Android",
    )
    assert first.status == "generated"
    assert first.skill_id == "feishu_send_message"
    assert first.display_name == "Feishu - Send message"
    runtime.finish(task="打开飞书，在ClawGUI这个群里发送test", success=True, result="ok")

    second = runtime.prepare(
        task="打开飞书，在ClawGUI这个群里发送test reuse",
        current_app="System Home",
        platform="Android",
    )
    assert second.status == "reused"
    assert second.skill_id == first.skill_id
    assert second.retrieval_score >= 0.35


def test_direct_contact_message_skill_generates_concise_identity(tmp_path: Path):
    runtime = SkillRuntime(SkillRuntimeConfig(mode="evolve", store_dir=str(tmp_path)))

    first = runtime.prepare(
        task="打开飞书，给陈博凡发送hello",
        current_app="System Home",
        platform="Android",
    )
    assert first.status == "generated"
    assert first.skill_id == "feishu_send_message"
    assert first.display_name == "Feishu - Send message"
    assert "recipient: 陈博凡" in first.context
    assert "message: hello" in first.context


def test_message_skill_reuses_different_chat_target_as_parameter(tmp_path: Path):
    runtime = SkillRuntime(SkillRuntimeConfig(mode="evolve", store_dir=str(tmp_path)))

    first = runtime.prepare(task="打开飞书，在ClawGUI这个群里发送test", current_app="System Home")
    runtime.finish(task="打开飞书，在ClawGUI这个群里发送test", success=True, result="ok")

    second = runtime.prepare(task="打开飞书，在OtherGroup这个群里发送test", current_app="System Home")
    assert second.status == "reused"
    assert second.skill_id == first.skill_id
    assert second.retrieval_score >= 0.35
    assert "Current Task Parameters" in second.context
    assert "recipient: OtherGroup" in second.context


def test_skill_context_does_not_over_trigger_takeover(tmp_path: Path):
    runtime = SkillRuntime(SkillRuntimeConfig(mode="evolve", store_dir=str(tmp_path)))

    prepared = runtime.prepare(task="打开飞书，在ClawGUI这个群里发送test", current_app="System Home")
    skill_root = tmp_path / prepared.skill_id
    recover = (skill_root / "docs" / "recover.md").read_text(encoding="utf-8")

    assert "private data" not in recover
    assert "ordinary chat" in prepared.context
    assert "Ask for takeover only" in prepared.context


def test_legacy_recover_takeover_guidance_is_softened_in_context(tmp_path: Path):
    store = SkillStore(tmp_path)
    skill = store.create_skill(
        "Send a message to a chat/contact in Feishu",
        display_name="Feishu - Send message",
        domain_app=["Feishu"],
        keywords=["feishu", "send_message"],
        arguments=["recipient:<variable>", "message:<variable>"],
        recover=(
            "# Recovery\n\n"
            "- If login, captcha, payment, or private data is required, ask for takeover instead of guessing.\n"
        ),
    )

    context = skill.render_skill_context()
    assert "private data" not in context
    assert "Ask for takeover only" in context
    assert "ordinary chat" in context


def test_calendar_event_skill_reuses_different_event_parameters(tmp_path: Path):
    runtime = SkillRuntime(SkillRuntimeConfig(mode="evolve", store_dir=str(tmp_path)))

    first = runtime.prepare(
        task="Open Calendar and create a meeting titled Lab Sync tomorrow at 3pm",
        current_app="Calendar",
    )
    assert first.status == "generated"
    assert first.skill_id == "calendar_create_event"
    assert first.display_name == "Calendar - Create event"
    runtime.finish(
        task="Open Calendar and create a meeting titled Lab Sync tomorrow at 3pm",
        success=True,
        result="ok",
    )

    second = runtime.prepare(
        task="Open Calendar and create an event titled Paper Deadline next Friday at 10am",
        current_app="Calendar",
    )
    assert second.status == "reused"
    assert second.skill_id == first.skill_id
    assert second.retrieval_score >= 0.35
    assert "Current Task Parameters" in second.context
    assert "time:" in second.context


def test_settings_toggle_skill_reuses_different_setting_parameter(tmp_path: Path):
    runtime = SkillRuntime(SkillRuntimeConfig(mode="evolve", store_dir=str(tmp_path)))

    first = runtime.prepare(task="Open Settings and turn on Bluetooth", current_app="Settings")
    assert first.status == "generated"
    assert first.skill_id == "settings_toggle_setting"
    runtime.finish(task="Open Settings and turn on Bluetooth", success=True, result="ok")

    second = runtime.prepare(task="Open Settings and turn off Wi-Fi", current_app="Settings")
    assert second.status == "reused"
    assert second.skill_id == first.skill_id
    assert "setting_name: Wi-Fi" in second.context
    assert "state: off" in second.context


def test_search_open_skill_reuses_different_query_parameter(tmp_path: Path):
    runtime = SkillRuntime(SkillRuntimeConfig(mode="evolve", store_dir=str(tmp_path)))

    first = runtime.prepare(task="Open Chrome, search OpenAI, and open the first result", current_app="Chrome")
    assert first.status == "generated"
    assert first.skill_id == "chrome_search_and_open"
    runtime.finish(task="Open Chrome, search OpenAI, and open the first result", success=True, result="ok")

    second = runtime.prepare(task="Open Chrome, search ClawGUI, and open the official result", current_app="Chrome")
    assert second.status == "reused"
    assert second.skill_id == first.skill_id
    assert "query: ClawGUI" in second.context


def test_note_skill_reuses_different_note_content_parameter(tmp_path: Path):
    runtime = SkillRuntime(SkillRuntimeConfig(mode="evolve", store_dir=str(tmp_path)))

    first = runtime.prepare(task="Open Notes and create a note content buy milk", current_app="Notes")
    assert first.status == "generated"
    assert first.skill_id == "notes_create_note"
    runtime.finish(task="Open Notes and create a note content buy milk", success=True, result="ok")

    second = runtime.prepare(task="Open Notes and create a note content read paper", current_app="Notes")
    assert second.status == "reused"
    assert second.skill_id == first.skill_id
    assert "content: read paper" in second.context


def test_failed_evolve_rewrites_targeted_revision_section(tmp_path: Path):
    runtime = SkillRuntime(SkillRuntimeConfig(mode="evolve", store_dir=str(tmp_path)))
    prepared = runtime.prepare(task="Open Settings and tap Bluetooth", current_app="Settings")

    runtime.finish(task="Open Settings and tap Bluetooth", success=False, result="failed")
    first_detail = runtime.render_skill_detail(prepared.skill_id)
    runtime.finish(task="Open Settings and tap Bluetooth", success=False, result="failed again")
    second_detail = runtime.render_skill_detail(prepared.skill_id)

    skill_root = tmp_path / prepared.skill_id
    plan = (skill_root / "docs" / "plan.md").read_text(encoding="utf-8")
    assert plan.count("evoskill-plan-revision:start") == 1
    assert "Revision 1:" in second_detail
    assert "Revision 2:" not in second_detail
    assert "failure_001.md" in second_detail
    assert "failure_002.md" in second_detail
    assert first_detail


def test_model_generator_uses_three_step_paper_prompts(tmp_path: Path):
    fake = FakeSkillModelClient(
        [
            json.dumps(
                {
                    "skill_id": "skill_feishu_打开飞书_给陈博凡发送_hello",
                    "task_intent": "Send a message in Feishu",
                    "domain_app": ["Feishu"],
                    "platform": "Android",
                    "keywords": ["feishu", "send_message"],
                    "arguments": ["<recipient>", "<message>"],
                    "plan_md": "# Plan\n1. Open Feishu.\n2. Send the current message.",
                }
            ),
            json.dumps({"backup_md": "# Backup\n- Use search if the chat is not visible."}),
            json.dumps({"recover_md": "# Recovery\n- Dismiss notification permission if it appears."}),
        ]
    )
    skill = SkillGenerator(SkillStore(tmp_path), model_client=fake, mode="model").generate(
        "打开飞书，在ClawGUI这个群里发送test",
        current_app="System Home",
        platform="Android",
    )

    assert len(fake.calls) == 3
    assert "step 1 of 3" in fake.calls[0]["user_text"]
    assert "step 2 of 3" in fake.calls[1]["user_text"]
    assert "step 3 of 3" in fake.calls[2]["user_text"]
    assert skill.skill_id == "feishu_send_message"
    assert skill.display_name == "Feishu - Send message"
    assert "Open Feishu" in skill.read_doc("plan.md")
    assert "Use search" in skill.read_doc("backup.md")
    assert "Dismiss notification" in skill.read_doc("recover.md")
    edits_log = (skill.root / "edits.jsonl").read_text(encoding="utf-8")
    assert '"source": "paper_prompt_3step"' in edits_log


def test_model_generator_retries_when_first_step_is_not_json(tmp_path: Path):
    fake = FakeSkillModelClient(
        [
            'do(action="Launch", app="Feishu")',
            json.dumps(
                {
                    "skill_id": "skill_feishu_send_message",
                    "task_intent": "Send a message in Feishu",
                    "domain_app": ["Feishu"],
                    "platform": "Android",
                    "keywords": ["feishu", "send_message"],
                    "arguments": ["<recipient>", "<message>"],
                    "plan_md": "# Plan\n1. Open Feishu.\n2. Send the current message.",
                }
            ),
            json.dumps({"backup_md": "# Backup\n- Use search if the chat is not visible."}),
            json.dumps({"recover_md": "# Recovery\n- Dismiss notification permission if it appears."}),
        ]
    )

    skill = SkillGenerator(SkillStore(tmp_path), model_client=fake, mode="model").generate(
        "打开飞书，给陈博凡发送hello",
        current_app="System Home",
        screenshot=object(),
    )

    assert skill.skill_id == "feishu_send_message"
    assert len(fake.calls) == 4
    assert fake.calls[0]["image"] is None
    assert "not valid JSON" in fake.calls[1]["user_text"]
    assert "do(action=" in fake.calls[1]["user_text"]


def test_model_generator_salvages_text_plan_instead_of_fallback(tmp_path: Path):
    text_plan = (
        "Now I need to create the complete task plan. "
        "1. Launch the Feishu app. "
        "2. Navigate to the chat interface with the current recipient. "
        "3. Tap the input field. "
        "4. Type the current message. "
        "5. Send the message and verify it was sent."
    )
    fake = FakeSkillModelClient(
        [
            text_plan,
            text_plan,
            json.dumps({"backup_md": "# Backup\n- Use search if the chat is not visible."}),
            json.dumps({"recover_md": "# Recovery\n- Dismiss notification permission if it appears."}),
        ]
    )

    skill = SkillGenerator(SkillStore(tmp_path), model_client=fake, mode="model").generate(
        "打开飞书，给陈博凡发送Test",
        current_app="System Home",
    )

    assert skill.skill_id == "feishu_send_message"
    assert "Launch the Feishu app" in skill.read_doc("plan.md")
    edits_log = (skill.root / "edits.jsonl").read_text(encoding="utf-8")
    assert '"source": "paper_prompt_3step"' in edits_log
    assert "step1_text_plan" in edits_log


def test_model_generator_salvages_skill_field_outline_instead_of_fallback(tmp_path: Path):
    field_outline = (
        "Now I need to generate the complete skill package. "
        "I'll create a task list for the skill authoring process. "
        "I need to include: 1. skill_id with the appropriate name "
        "2. task_intent 3. domain apps 4. platform 5. keywords "
        "6. arguments 7. plan_md with numbered steps. "
        "The plan_md should launch Feishu, find the current recipient, "
        "type the current message, and verify the message was sent."
    )
    fake = FakeSkillModelClient(
        [
            field_outline,
            field_outline,
            json.dumps({"backup_md": "# Backup\n- Use search if the chat is not visible."}),
            json.dumps({"recover_md": "# Recovery\n- Go back once if navigation stalls."}),
        ]
    )

    skill = SkillGenerator(SkillStore(tmp_path), model_client=fake, mode="model").generate(
        "Open Feishu and send Test to the current recipient",
        current_app="System Home",
    )

    assert skill.skill_id == "feishu_send_message"
    assert "complete skill package" in skill.read_doc("plan.md")
    edits_log = (skill.root / "edits.jsonl").read_text(encoding="utf-8")
    assert '"source": "paper_prompt_3step"' in edits_log
    assert "step1_text_plan" in edits_log


def test_model_generator_rejects_contaminated_model_text(tmp_path: Path):
    raw_plan = (
        "Now I need to generate the complete skill package. Let me think about "
        "the skill ID. Looking at the screenshot, the message is already typed. "
        "Let me tap the send button to complete the task."
    )
    fake = FakeSkillModelClient([raw_plan, raw_plan])

    with pytest.raises(ValueError, match="did not return parseable JSON"):
        SkillGenerator(SkillStore(tmp_path), model_client=fake, mode="model").generate(
            "Open Feishu and send Test to the current recipient",
            current_app="System Home",
        )


def test_model_generator_rejects_contaminated_json_doc(tmp_path: Path):
    fake = FakeSkillModelClient(
        [
            json.dumps(
                {
                    "skill_id": "skill_feishu_send_message",
                    "task_intent": "Send a message in Feishu",
                    "domain_app": ["Feishu"],
                    "platform": "Android",
                    "keywords": ["feishu", "send_message"],
                    "arguments": ["<recipient>", "<message>"],
                    "plan_md": (
                        "# Plan\n\n"
                        "Now I need to generate the complete skill package. "
                        "Let me think about the skill ID. Looking at the screenshot, "
                        "the message is already typed. Let me tap the send button."
                    ),
                }
            )
        ]
    )

    with pytest.raises(ValueError, match="contaminated plan_md"):
        SkillGenerator(SkillStore(tmp_path), model_client=fake, mode="model").generate(
            "Open Feishu and send Test to the current recipient",
            current_app="System Home",
        )


def test_model_generator_wraps_text_backup_instead_of_fallback(tmp_path: Path):
    text_backup = (
        "Let me analyze the task and final plan. "
        "1. If the chat is not visible, open search and enter the current recipient. "
        "2. If the input field is not visible, verify the chat title and scroll to the latest messages. "
        "3. Alternative send controls may include a paper-plane icon or keyboard send button."
    )
    fake = FakeSkillModelClient(
        [
            json.dumps(
                {
                    "skill_id": "skill_feishu_send_message",
                    "task_intent": "Send a message in Feishu",
                    "domain_app": ["Feishu"],
                    "platform": "Android",
                    "keywords": ["feishu", "send_message"],
                    "arguments": ["<recipient>", "<message>"],
                    "plan_md": "# Plan\n1. Open Feishu.\n2. Send the current message.",
                }
            ),
            text_backup,
            text_backup,
            json.dumps({"recover_md": "# Recovery\n- Go back once if navigation stalls."}),
        ]
    )

    skill = SkillGenerator(SkillStore(tmp_path), model_client=fake, mode="model").generate(
        "鎵撳紑椋炰功锛岀粰闄堝崥鍑″彂閫乀est",
        current_app="System Home",
    )

    assert skill.skill_id == "feishu_send_message"
    assert "Let me analyze the task" in skill.read_doc("backup.md")
    assert skill.read_doc("backup.md").startswith("# Backup Locators")
    edits_log = (skill.root / "edits.jsonl").read_text(encoding="utf-8")
    assert '"source": "paper_prompt_3step"' in edits_log
    assert "backup_md_text_wrap" in edits_log


def test_auto_generator_records_fallback_when_model_generation_fails(tmp_path: Path):
    generator = SkillGenerator(
        SkillStore(tmp_path),
        model_client=FailingSkillModelClient(),
        mode="auto",
    )

    skill = generator.generate("打开飞书，给陈博凡发送hello", current_app="System Home")

    assert skill.skill_id == "feishu_send_message"
    edits_log = (skill.root / "edits.jsonl").read_text(encoding="utf-8")
    assert '"source": "fallback"' in edits_log
    assert "fake generator failure" in edits_log


def test_model_verifier_maps_isolated_feedback(tmp_path: Path):
    episode_dir = tmp_path / "trace" / "ep1"
    episode_dir.mkdir(parents=True)
    (episode_dir / "episode.json").write_text(
        json.dumps(
            {
                "episode": [
                    {
                        "step": 1,
                        "action": {"_metadata": "do", "action": "Launch", "app": "Feishu", "ignored": "secret"},
                        "finished": False,
                    }
                ]
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    fake = FakeSkillModelClient(
        [
            json.dumps(
                {
                    "task_success": False,
                    "failure_type": "locating_error",
                    "failed_step": 1,
                    "diagnosis": "The task stopped before opening the target chat.",
                    "root_cause": "The chat search step was missing.",
                    "suggestions": ["Open the chat list search before selecting a recipient."],
                    "state_assertions": ["Step 1 launched Feishu."],
                }
            )
        ]
    )

    feedback = IsolatedTrajectoryVerifier(model_client=fake, mode="model").diagnose(
        instruction="send message",
        trace_path=str(episode_dir),
        success=False,
        result="failed",
    )

    assert feedback.failure_type == "locating_error"
    assert feedback.failed_step == 1
    assert feedback.state_assertions == ["Step 1 launched Feishu."]
    assert '"ignored"' not in fake.calls[0]["user_text"]


def test_model_revision_executes_file_tools(tmp_path: Path):
    skill = SkillStore(tmp_path).create_skill(
        "Send a message to a chat/contact in Feishu",
        display_name="Feishu - Send message",
        domain_app=["Feishu"],
        keywords=["feishu", "send_message"],
        arguments=["recipient:<variable>", "message:<variable>"],
        plan="# Plan\n1. Open Feishu.\n",
        backup="# Backup\n",
        recover="# Recovery\n",
    )
    fake = FakeSkillModelClient(
        [
            """
<thinking>
The verifier says the search step is missing, so append a concrete recovery.
</thinking>
<tool_call>
{"name": "append_file", "arguments": {"path": "docs/recover.md", "content": "- If the chat is not visible, tap the search field and type the current recipient before selecting a result."}}
</tool_call>
DONE
"""
        ]
    )
    edits = SkillEvolutionEngine(model_client=fake, mode="model").refine(
        skill,
        VerifierFeedback(
            success=False,
            failure_type="locating_error",
            root_cause="The chat search step was missing.",
            diagnosis="failed",
            suggestions=["Open chat search before selecting the recipient."],
        ),
    )

    assert any(edit["event"] == "append_file" for edit in edits)
    assert any(edit["event"] == "create_failure_example" for edit in edits)
    assert "chat is not visible" in skill.read_doc("recover.md")
    assert skill.meta.evolution_status.revision_count == 1

