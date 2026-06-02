"""Prompt templates used by the EvoSkill paper-style runtime."""

from __future__ import annotations


SKILL_GEN_SYSTEM_PROMPT = (
    "You are an expert Android GUI skill author. Create reusable, procedural "
    "skill-package documents for a GUI agent. Return only the requested strict "
    "JSON object."
)



SKILL_GEN_META_PLAN_USER_PROMPT = """# Task
You will produce the META fields and `plan.md` of a skill package for the following task. This is step 1 of 3 in a sequential authoring process; the backup locator strategies and recovery rules will be authored separately in later steps. Do NOT split or truncate the plan to leave room for them -- write the plan as if it were the only document.

## Task name
{{ task_name }}

## Task instruction (verbatim)
{{ instruction }}

## Initial accessibility tree (truncated)
```
{{ initial_a11y }}
```

# Output requirements
Return STRICT JSON (no markdown fences, no commentary) with this schema:

{
  "skill_id": "skill_<short_slug>",
  "task_intent": "<concise restatement of the task goal in <=160 chars>",
  "domain_app": ["<App1>", "<App2>"],
  "platform": "Android",
  "keywords": ["<kw1>", "<kw2>", ...],
  "arguments": ["<arg1>", "<arg2>", ...],
  "plan_md": "<markdown content for plan.md>"
}

Guidelines for `plan_md`:
- Numbered list of steps starting from the home screen and ending with task completion (and any required final output / verification).
- Each step should reference a real Android UI element (text label, content-description, icon shape) and the action verb (tap / long-press / drag / type).
- Be COMPLETE and self-contained. Do NOT omit pitfalls or unusual edge conditions on the assumption that they belong in a separate document -- the backup / recover documents authored later are a SUPPLEMENT, not a place to offload core knowledge.
- If the task has a specific final output format (e.g. "answer with a single integer", "send the count via SMS"), state it explicitly in the final step.
- Recommended length: 8-15 numbered steps for typical Android tasks; longer if the task naturally requires it. Do NOT artificially shorten.

Guidelines for the meta fields:
- `skill_id`: lowercase snake_case slug, prefixed `skill_`. e.g. `skill_check_invoice_total`.
- `skill_id` and `task_intent` must describe the reusable skill, not one concrete task instance. Do not include contact names, group names, message text, dates, titles, search queries, or other runtime parameter values in `skill_id`.
- `task_intent`: succinct goal restatement.
- `domain_app`: list of Android app names involved.
- `keywords`: lowercased canonical keywords for retrieval.
- `arguments`: placeholders for parametric values (use angle brackets), e.g. `<recipient>`, `<message>`, `<query>`, `<event_title>`.
- In `plan.md`, refer to runtime parameters such as the current recipient/message/query instead of hard-coding concrete values from this single task.

Return ONLY the JSON object. Do NOT wrap it in code fences.
"""


SKILL_GEN_BACKUP_USER_PROMPT = """# Task
You will produce `backup.md` for a skill package. This is step 2 of 3 in a sequential authoring process. The plan has already been written (shown below) and is FINAL. Your job is to provide alternative locator strategies that the executor can fall back on when the primary locator named in the plan cannot be found.

## Task instruction (verbatim)
{{ instruction }}

## Final plan.md (do NOT modify; just provide alternates)
```
{{ plan_md }}
```

# Output requirements
Return STRICT JSON (no markdown fences, no commentary) with this schema:

{
  "backup_md": "<markdown content for backup.md>"
}

Guidelines for `backup.md`:
- For each UI element referenced in plan.md that COULD be hard to locate, provide one or more alternate strategies. Skip elements whose locator is obvious and stable (e.g. system-level back button).
- Use bullets organized per element. For each element, list:
  * primary text or content-description
  * 1-3 alternate strategies (synonym text, parent region + ordinal, scroll target, content-description, OCR keyword)
- Do NOT restate the plan steps; assume the reader has the plan in mind.
- Keep entries concrete (a real string the executor can match against), not vague (e.g. "look around the screen").
- It is OK for `backup.md` to be short or even empty if every plan step uses a stable locator. In that case return:
  `"backup_md": "(no alternate locators needed; all primary locators in plan.md are stable)"`

Return ONLY the JSON object. Do NOT wrap it in code fences.
"""


SKILL_GEN_RECOVER_USER_PROMPT = """# Task
You will produce `recover.md` for a skill package. This is step 3 of 3 in a sequential authoring process. The plan and backup have already been written (shown below) and are FINAL. Your job is to enumerate recovery rules for INTERRUPTIONS and ENVIRONMENT problems the agent may face during this specific task -- NOT to repeat plan steps or locator strategies.

## Task instruction (verbatim)
{{ instruction }}

## Final plan.md (do NOT modify)
```
{{ plan_md }}
```

## Final backup.md (do NOT modify)
```
{{ backup_md }}
```

# Output requirements
Return STRICT JSON (no markdown fences, no commentary) with this schema:

{
  "recover_md": "<markdown content for recover.md>"
}

Guidelines for `recover.md`:
- Cover only INTERRUPTIONS / ENVIRONMENT issues that are realistic for this task. Examples (use the ones that apply):
  * Cookie / consent banners
  * Permission dialogs (storage, location, notification)
  * Login walls and sign-in popups
  * "Open with" / app-chooser dialogs
  * Keyboard covering the action button
  * Network errors / offline state
  * Captchas / rate limiting
  * "Adaptive brightness", "Battery saver", or other system features that silently override the agent's effect
- Each entry should describe: the symptom (what the agent will SEE), and the recovery action (what to TAP / SWIPE / TYPE).
- Skip generic guidance that is not anchored to this task.
- It is OK for `recover.md` to be short. In that case return:
  `"recover_md": "(no task-specific interruptions anticipated)"`

Return ONLY the JSON object. Do NOT wrap it in code fences.
"""


VERIFIER_SYSTEM_PROMPT = """You are an independent VERIFIER for a GUI automation agent. Your job is to inspect the task instruction together with a sealed view of what happened on-screen, then judge whether the task succeeded.

You operate under STRICT INFORMATION ISOLATION. You will be shown ONLY:
1. The user's task instruction.
2. The screenshot sequence captured during execution.
3. The action log (action JSON only -- no thoughts, no rationale).
4. OCR / accessibility / DOM text where available.
5. The current URL / app / final screen state.
6. Your own assertions from the previous verification round (if any).

You will NEVER see the executor's reasoning, plan documents, or skill-package content. Do not speculate about them; rely on the observable evidence only.

You also do NOT know how the executor's playbook is internally organized. Do not reference any specific file names, document names, or internal structural categories of the executor (e.g. never write "plan.md", "backup.md", "recover.md", "skill.md", or phrases like "in the plan document"). Describe what the agent should DO differently in behavioral terms, not WHERE that knowledge should live.

Output a STRICT JSON diagnostic report. Be specific, ground every claim in the observable evidence (cite step numbers / screenshots), and propose concrete behavioral changes that a downstream maintainer can translate into edits.
"""


VERIFIER_USER_PROMPT = """# Task instruction
{{ instruction }}

{{ oracle_block }}
# Action log (sealed: action JSON only)
{{ actions_block }}

{{ a11y_block }}
{{ final_state_block }}
{{ previous_assertions_block }}
# Required output (STRICT JSON, no code fences, no commentary)
{
  "task_success": true | false,
  "failure_type": "none | planning_gap | locating_error | verification_miss | a11y_stale | env_error | other",
  "failed_step": <int or null, the 1-based step where things first went wrong>,
  "diagnosis": "<one-paragraph natural language description grounded in evidence>",
  "root_cause": "<concise root cause>",
  "suggestions": [
    "<concrete, actionable edit suggestion 1>",
    "<concrete, actionable edit suggestion 2>"
  ],
  "state_assertions": [
    "<verifiable assertion about the state at step X>",
    "..."
  ]
}

Rules:
- If `task_success` is true, set `failure_type` to "none" and `failed_step` to null.
- `suggestions` must be ACTIONABLE and BEHAVIORAL: describe what the agent should do differently (e.g. "wait for the dropdown to fully expand before tapping", "verify the attachment count equals 2 before sending"). Do NOT reference any specific file name, document name, or internal structural category of the executor's playbook (e.g. do NOT write "in plan.md", "add to recover.md", "update backup.md", or any analogous file-based instruction). You have not seen the executor's playbook and you do not know its structure.
- `state_assertions` should be reusable, succinct facts about the trajectory that you (the verifier) want to remember next round.
"""


SKILL_REVISE_PROMPT = """You are the executor agent in a self-evolving GUI automation system.
The previous rollout FAILED. Based on the verifier's feedback, you MUST edit the skill package files to fix the issue.

## Rules
1. You MUST call at least one file tool (`write_file` or `append_file`) to modify plan.md, backup.md, or recover.md. Do NOT just say DONE without editing.
2. Transform the verifier's suggestions into CONCRETE plan steps, locator strategies, or recovery rules. Do NOT copy-paste the feedback verbatim.
3. All file paths are relative to the skill package root.
4. When finished editing, output ONLY the literal token DONE on a new line.

## Output Format
For each tool call, output:
```
<thinking>
...your reasoning...
</thinking>
<tool_call>
{"name": "<tool_name>", "arguments": <args-json-object>}
</tool_call>
```

## Available Tools
- `read_file`         {"path": "docs/plan.md"}
- `write_file`        {"path": "docs/plan.md", "content": "..."}     # OVERWRITE
- `append_file`       {"path": "docs/recover.md", "content": "..."}
- `list_dir`          {"path": "docs"}
- `search_file`       {"path": "docs/plan.md", "pattern": "...", "is_regex": false}
- `create_failure_example`  {"title": "...", "content": "..."}

## Example
To rewrite plan.md:
```
<thinking>
The verifier said the plan missed the cookie-banner step. I'll insert a new step.
</thinking>
<tool_call>
{"name": "write_file", "arguments": {"path": "docs/plan.md", "content": "# Plan\\n1. Open app\\n2. Dismiss cookie banner\\n3. ..."}}
</tool_call>
```
"""


def render_template(template: str, **values: object) -> str:
    """Render the simple Jinja-style placeholders used by the paper prompts."""
    text = template
    for key, value in values.items():
        text = text.replace("{{ " + key + " }}", "" if value is None else str(value))
        text = text.replace("{{" + key + "}}", "" if value is None else str(value))
    return text

