<div align="center">

<img src="../assets/ClawGUI-Logo.png" height="120" alt="ClawGUI Logo">
<h1>ClawGUI-Skills：训练自由的 GUI 技能自进化层</h1>

[English](README.md) | [中文](README_zh.md)

</div>

**ClawGUI-Skills** 实现了我们论文 **《Reflect, Revise, Reuse: Training-Free Skill Evolution for GUI Agents》** 中提出并验证的自进化技能架构。它将 GUI 任务经验沉淀为结构化、可检索、可审计、可修订的技能包，让 PhoneAgent 在不训练参数的情况下复用和改进过程知识。

## 核心能力

- **结构化技能包**：每个技能包含 `meta_info.json`、`plan.md`、`backup.md`、`recover.md` 和 `failure_examples/`。
- **按需检索**：执行任务前只基于 metadata 检索，不把完整技能库塞进上下文。
- **技能注入**：命中技能后只注入 top-1 技能的精简上下文，并受 `skill_max_context_chars` 控制。
- **论文版生成与修订**：有模型配置时，技能生成走论文 prompt 的三阶段 `meta+plan -> backup -> recover` 流程；失败后由 isolated verifier 诊断，并通过 `skill_revise` prompt 调用受限文件工具修改特定技能文件。
- **失败案例积累**：每次失败会写入 `failure_examples/failure_xxx.md`，后续可作为少量经验注入。
- **版本与审计**：每次修订前保存 `versions/` 快照，并在 `edits.jsonl`、`runs.jsonl` 中记录演化过程。

## 技能模式

| 模式 | 行为 |
|------|------|
| `off` | 默认模式，不检索、不注入、不演化 |
| `trace` | 只记录执行轨迹，不注入技能上下文 |
| `reuse` | 检索已有技能，命中后注入，不自动修改技能 |
| `evolve` | 检索或构建技能包，失败后诊断、修订并记录 failure example；PhoneAgent 产生修订后可按迭代预算即时重试 |

## 后端选择

默认 `auto` 会复用 PhoneAgent 当前的 OpenAI-compatible 模型接口：接口可用时使用论文版 prompt generator / verifier / revision，不可用或离线测试时自动退回轻量 fallback。CLI 可用 `--skill-generator-mode`、`--skill-verifier-mode`、`--skill-revision-mode` 分别设为 `auto`、`model` 或 `fallback`。

## 技能包结构

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

## PhoneAgent 使用

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

agent.run("打开设置并开启蓝牙")
```

## Web UI 使用

```bash
cd clawgui-agent
python webui.py
```

在「配置管理」中选择技能模式：

- `reuse`：适合展示技能复用，不会改写技能包。
- `evolve`：适合展示技能自进化，失败后会生成诊断、修订文档并记录失败案例。

在「技能库」Tab 中可以查看：

- 技能包精简名称和 `skill_id`
- 使用次数、成功率、修订次数
- `plan.md`、`backup.md`、`recover.md`
- `failure_examples/`

## CLI 查看技能库

```bash
cd clawgui-skills
python -m clawgui_skills.cli --store ../clawgui-agent/skill_store list
python -m clawgui_skills.cli --store ../clawgui-agent/skill_store show <skill_id>
```

## 离线测试

不连接真机也可以测试技能包构建、检索和修订：

```bash
cd ClawGUI
$env:PYTHONPATH="clawgui-skills"
python clawgui-skills/examples/demo_reuse_offline.py
python clawgui-skills/examples/demo_evolve_offline.py
python -m pytest clawgui-skills/tests -q
```

## 安全边界

自进化修订只能通过受限文件工具操作当前技能包内的 `docs/plan.md`、`docs/backup.md`、`docs/recover.md` 和 `failure_examples/`。默认不会修改项目代码，也不会跨技能包写文件。
