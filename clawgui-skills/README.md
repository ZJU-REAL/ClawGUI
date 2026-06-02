<div align="center">

<img src="../assets/ClawGUI-Logo.png" height="120" alt="ClawGUI Logo">
<h1>ClawGUI-Skills: Training-Free Self-Evolving GUI Skills</h1>

[English](README.md) | [中文](README_zh.md)

</div>

**ClawGUI-Skills** implements the self-evolving skill architecture proposed and validated in our paper **“Reflect, Revise, Reuse: Training-Free Skill Evolution for GUI Agents.”** It turns GUI task experience into structured, retrievable, auditable, and editable skill packages so PhoneAgent can reuse and revise procedural knowledge without parameter training.

## Features

- **Structured skill packages** with `meta_info.json`, `plan.md`, `backup.md`, `recover.md`, and `failure_examples/`.
- **Metadata-first retrieval** so the full skill library is never blindly injected into context.
- **Budgeted skill injection** with top-1 skill context and `skill_max_context_chars`.
- **Paper prompt pipeline**: with a configured model endpoint, skill generation follows the paper's three-step `meta+plan -> backup -> recover` prompts, and failed runs use the isolated verifier plus `skill_revise` prompt to edit packages through restricted file tools.
- **Failure examples** stored as reusable lessons for future tasks.
- **Versioning and audit logs** through `versions/`, `edits.jsonl`, and `runs.jsonl`.

## Modes

| Mode | Behavior |
|------|----------|
| `off` | Default. No retrieval, injection, or evolution |
| `trace` | Record trajectories only |
| `reuse` | Retrieve and inject existing skills, without editing them |
| `evolve` | Retrieve or build a skill package, diagnose and revise after failure, and let PhoneAgent retry immediately when a revision is produced |

## Backends

The default backend is `auto`: ClawGUI-Skills reuses PhoneAgent's current OpenAI-compatible model endpoint for the paper prompt generator, verifier, and revision flow when available, and falls back to the lightweight offline implementation for local tests. CLI flags `--skill-generator-mode`, `--skill-verifier-mode`, and `--skill-revision-mode` accept `auto`, `model`, or `fallback`.

## Skill Package Layout

```text
skill_store/
  send_message_wechat/
    meta_info.json
    docs/
      plan.md
      backup.md
      recover.md
    failure_examples/
      failure_001.md
    versions/
      0001/
    edits.jsonl
    runs.jsonl
```

## PhoneAgent Usage

```python
from phone_agent import PhoneAgent
from phone_agent.agent import AgentConfig

agent = PhoneAgent(
    agent_config=AgentConfig(
        skill_mode="evolve",
        skills_dir="skill_store",
        skill_retrieval_threshold=0.35,
        skill_max_context_chars=6000,
    )
)

agent.run("Open Settings and turn on Bluetooth")
```

## Web UI

```bash
cd clawgui-agent
python webui.py
```

Use the configuration tab to choose `off`, `trace`, `reuse`, or `evolve`. The task log shows the matched/generated skill name, `skill_id`, retrieval score, injected context size, verifier diagnosis, and edited files. The skill library tab lets you inspect skill packages and their evolution history.

## CLI

```bash
cd clawgui-skills
python -m clawgui_skills.cli --store ../clawgui-agent/skill_store list
python -m clawgui_skills.cli --store ../clawgui-agent/skill_store show <skill_id>
```

## Offline Tests

```bash
cd ClawGUI
PYTHONPATH=clawgui-skills python clawgui-skills/examples/demo_reuse_offline.py
PYTHONPATH=clawgui-skills python clawgui-skills/examples/demo_evolve_offline.py
PYTHONPATH=clawgui-skills python -m pytest clawgui-skills/tests -q
```

## Safety Boundary

Skill evolution uses restricted file tools scoped to the active skill package. It can edit only `docs/plan.md`, `docs/backup.md`, `docs/recover.md`, and `failure_examples/`; it does not modify project source code.
