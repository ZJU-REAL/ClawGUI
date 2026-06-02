<div align="center">
  <img src="assets/clawgui-agent-text.png" alt="ClawGUI-Agent Text" height="40">
</div>
<div align="center">
  <table style="border: none; border-collapse: collapse;">
    <tr>
      <td style="border: none; padding: 0;"><img src="assets/clawgui-agent-banner.png" alt="ClawGUI-Agent Banner" height="60" style="vertical-align: middle;"></td>
      <td style="border: none; padding: 0 0 0 12px; vertical-align: middle;"><h1 style="margin: 0;">ClawGUI-Agent: Personal Phone GUI Assistant</h1></td>
    </tr>
  </table>
  <p>
    <img src="https://img.shields.io/badge/python-≥3.10-blue" alt="Python">
    <img src="https://img.shields.io/badge/license-Apache%202.0-green" alt="License">
    <img src="https://img.shields.io/badge/platform-Android%20|%20HarmonyOS%20|%20iOS-orange" alt="Platform">
  </p>
</div>

[English](README.md) | [中文](README_CN.md)


**ClawGUI-Agent** is the deployment module of [ClawGUI](../README.md). Built on [OpenClaw](https://github.com/openclaw/openclaw) and powered by [nanobot](https://github.com/HKUDS/nanobot), it provides two core capabilities: **GUI phone control** and **one-command evaluation**. For phone control, it drives a Vision-Language Model through a closed-loop "screenshot → reasoning → action" cycle to autonomously complete tasks on Android, HarmonyOS, and iOS devices — accessible from Feishu, QQ, Telegram, and 12+ other chat platforms. For evaluation, a single natural-language command triggers the full [ClawGUI-Eval](../clawgui-eval) pipeline: environment check, multi-GPU inference, judging, and metric reporting.

## 📑 Table of Contents

- [Key Features](#-key-features)
- [Architecture](#️-architecture)
- [How the Agent Works](#-how-the-agent-works)
- [Quick Start](#-quick-start)
  - [Requirements](#requirements)
  - [1. Installation](#1-installation)
  - [2. Initialize and Edit Configuration](#2-initialize-and-edit-configuration)
  - [3. Connect Android Device](#3-connect-android-device)
  - [4. Configure Chat Platforms (optional)](#4-configure-chat-platforms-optional)
- [Run](#-run)
- [ClawGUI-Eval Evaluation](#-clawgui-eval-evaluation)
- [GUI Phone Control](#-gui-phone-control)
  - [Web UI](#web-ui)
  - [Memory System](#memory-system)
  - [Self-Evolving Skill Mode](#self-evolving-skill-mode)
  - [Supported GUI Models](#supported-gui-models)
- [Directory Structure](#-directory-structure)
- [License](#-license)

## ✨ Key Features

- **nanobot Integration** — Remotely control phones from 12+ chat platforms including Feishu / DingTalk / Telegram / Discord / Slack / QQ, issue tasks anytime anywhere
- **GUI Phone Control** — Powered by OpenClaw, AI autonomously captures screenshots, understands the screen, and performs tap/swipe/type GUI actions to complete complex tasks
- **ClawGUI-Eval Integration** — Built-in [ClawGUI-Eval](../clawgui-eval) evaluation skill, launch GUI Grounding model benchmarks with natural language (environment check → multi-GPU inference → judging → metric calculation), with automatic progress monitoring and result comparison against official baselines
- **Multi-Model Support** — Compatible with AutoGLM, Qwen VL, UI-TARS, MAI-UI, GUI-Owl and more VLMs, connected via OpenAI-compatible API
- **Personalized Memory** — Automatically learns user preferences (contacts, frequently used apps, habits), with a vector-search-based persistent memory system
- **Self-evolving Skills** — Optionally enable [ClawGUI-Skills](../clawgui-skills) to retrieve, build, inject, and revise GUI task skill packages
- **Real-time Episode Recording** — Each task execution (screenshots + model outputs + actions) is saved as a structured episode, enabling replay and dataset construction
- **Web UI** — Gradio-based web interface for device management, task execution visualization, manual takeover, memory management and more

## 🏗️ Architecture

<p align="center">
  <img src="assets/clawgui-agent-logo.png" alt="ClawGUI-Agent Architecture" width="800">
</p>

## 🔄 How the Agent Works

Understanding the execution loop helps with configuration and debugging. `PhoneAgent.run()` in `phone_agent/agent.py` follows this cycle for each task:

1. **Screenshot** — Capture the current screen via ADB (`screencap`), HDC, or XCTest depending on the device backend.
2. **Memory retrieval** — Query the vector memory store for relevant memories from past interactions (contacts, app knowledge, user preferences). Top-k similar memories are appended to the system context.
3. **Skill retrieval** — If `skillMode` is `reuse` or `evolve`, retrieve the most relevant skill from [ClawGUI-Skills](../clawgui-skills) by metadata. In `evolve` mode, no-match tasks materialize a new skill package.
4. **History construction** — Assemble the multi-turn conversation history: each past step contributes a `(user: screenshot + instruction, assistant: reasoning + action)` pair, up to `history_length` steps back.
5. **VLM call** — Send the prompt (system prompt + history + current screenshot + task instruction + optional skill context) to the configured GUI model via OpenAI-compatible API.
6. **Action parsing** — Extract the structured action from the model output. Different models use different output formats (`autoglm`, `uitars`, `qwenvl`, `maiui`, `guiowl` adapters in `phone_agent/model/adapters.py`).
7. **Coordinate normalization** — Convert the model's output coordinates to absolute device pixels. AutoGLM uses `[0, 1000]` normalized coordinates; UI-TARS uses absolute pixel coordinates in `smart_resize` space; Qwen-VL uses absolute pixels; MAI-UI uses `[0, 1000]`.
8. **Action execution** — Send the action to the device backend: tap, long-press, swipe, type, home, back, or task-complete. Each action type has a dedicated handler in `phone_agent/actions/`.
9. **Trace recording** — If `traceEnabled=True` or `skillMode=evolve`, append the screenshot, reasoning, and action to the episode tracer for later replay, diagnosis, or training data export.
10. **Skill evolution and memory update** — After completion, update personalized memory. If `skillMode=evolve` and the task fails, an isolated verifier diagnoses the trace and uses restricted file tools to revise `plan.md`, `backup.md`, or `recover.md`, while adding a failure example.

This loop runs until the model outputs a `terminate` or `answer` action, or `max_steps` is reached.

## 🚀 Quick Start

### Requirements

- **Python**: ≥ 3.11
- **Package Manager**: [uv](https://github.com/astral-sh/uv) (recommended) or conda + pip

### 1. Installation

Assuming you have cloned the ClawGUI project and are in the root directory:

#### Option A: uv (recommended)

```bash
cd clawgui-agent

# Create virtual environment
uv venv .venv --python 3.12

# Activate
source .venv/bin/activate

# Install phone_agent
uv pip install -e .

# Install nanobot
uv pip install -e nanobot/
```

#### Option B: conda + pip

```bash
cd clawgui-agent

# Create conda environment
conda create -n opengui python=3.12 -y
conda activate opengui

# Install phone_agent
pip install -e .

# Install nanobot
pip install -e nanobot/
```

### 2. Initialize and Edit Configuration

Run the onboard wizard to generate default config:

```bash
nanobot onboard
```

Then edit `~/.nanobot/config.json`. Here is a reference configuration:

> We recommend using **autoglm-phone** as the external GUI model for phone control.

```json
{
  "agents": {
    "defaults": {
      "workspace": "/path/to/ClawGUI",
      "model": "glm-5",
      "provider": "zhipu",
      "maxTokens": 8192,
      "contextWindowTokens": 131072,
      "temperature": 0.1,
      "maxToolIterations": 40
    }
  },
  "providers": {
    "zhipu": {
      "apiKey": "YOUR_ZHIPU_API_KEY",
      "apiBase": "https://open.bigmodel.cn/api/paas/v4/"
    },
    "openrouter": {
      "apiKey": "YOUR_OPENROUTER_API_KEY",
      "apiBase": "https://openrouter.ai/api/v1"
    }
  },
  "tools": {
    "gui": {
      "enable": true,
      "deviceType": "adb",
      "deviceId": null,
      "maxSteps": 50,
      "useExternalModel": true,
      "guiBaseUrl": "https://openrouter.ai/api/v1",
      "guiApiKey": "YOUR_OPENROUTER_API_KEY",
      "guiModelName": "autoglm-phone",
      "promptTemplateLang": "en",
      "promptTemplateStyle": "autoglm",
      "traceEnabled": false,
      "traceDir": "gui_trace",
      "skillMode": "off",
      "skillsDir": "skill_store",
      "skillRetrievalThreshold": 0.35,
      "skillMaxContextChars": 6000,
      "skillMaxIterations": 2,
      "skillRequireReview": false
    },
    "exec": {
      "enable": true,
      "timeout": 60
    }
  }
}
```

> **Important: `workspace` Path Setting**
>
> Set `workspace` to the ClawGUI project root — the directory that contains both `clawgui-agent/` and `clawgui-eval/`. The built-in evaluation skill uses this path to locate the evaluation framework. For example, if your project lives at `/home/user/ClawGUI`, set `workspace` to `"/home/user/ClawGUI"`.

#### GUI Tool Parameters

| Parameter | Description |
|-----------|-------------|
| `enable` | Enable/disable the GUI phone control tool |
| `deviceType` | Device type: `adb` (Android) or `hdc` (HarmonyOS) |
| `deviceId` | Specific device ID, `null` for auto-detection |
| `maxSteps` | Maximum execution steps per task |
| `useExternalModel` | Use an external GUI-specific model (recommended `true`) |
| `guiBaseUrl` | API endpoint for the external GUI model |
| `guiApiKey` | API key for the external GUI model |
| `guiModelName` | External GUI model name, used with guiBaseUrl |
| `promptTemplateLang` | Prompt language: `cn` / `en` |
| `promptTemplateStyle` | Prompt template style: `autoglm` / `uitars` / `qwenvl` etc. |
| `traceEnabled` | Enable episode recording |
| `traceDir` | Episode save directory |
| `skillMode` | Self-evolving skill mode: `off` / `trace` / `reuse` / `evolve` |
| `skillsDir` | Skill library directory for packages, versions, and audit logs |
| `skillRetrievalThreshold` | Minimum retrieval score before injecting an existing skill |
| `skillMaxContextChars` | Character budget for injected skill context |
| `skillMaxIterations` | Maximum immediate retry attempts after an evolve-mode revision |
| `skillRequireReview` | Save verifier feedback without auto-editing until reviewed |

### 3. Connect Android Device

> The controlled phone must be connected (e.g. via USB) to the server machine where ClawGUI-Agent is installed.

#### Step 1: Install ADB

**Option A: Install via package manager**

**macOS (recommended: brew):**

```bash
brew install android-platform-tools
```

**Linux:**

```bash
sudo apt install android-tools-adb   # Ubuntu/Debian
```

**Windows:** See this [blog tutorial](https://blog.csdn.net/x2584179909/article/details/108319973) to download and configure PATH.

**Option B: Manual download**

Download the official [ADB platform-tools](https://developer.android.com/tools/releases/platform-tools) and extract it, then add it to your PATH:

**macOS / Linux:**

```bash
# Assuming extracted to ~/Downloads/platform-tools
export PATH=${PATH}:~/Downloads/platform-tools
```

**Windows:** Add the extracted directory (e.g. `C:\platform-tools`) to the system PATH environment variable.

#### Step 2: Connect Phone and Enable USB Debugging

1. **Enable Developer Mode**: Go to Settings > About Phone > Build Number, tap rapidly ~10 times until you see "You are now a developer"
2. **Enable USB Debugging**: Go to Settings > Developer Options > USB Debugging, toggle it on (some devices may require a restart)
3. **Verify connection**:

```bash
adb devices

# Expected output:
# List of devices attached
# <your_device_id>   device
```

#### Step 3: Install ADB Keyboard (optional)

ADB Keyboard is used for text input. Download [ADBKeyboard.apk](https://github.com/senzhk/ADBKeyBoard/blob/master/ADBKeyboard.apk) and install:

```bash
adb install ADBKeyboard.apk
adb shell ime enable com.android.adbkeyboard/.AdbIME
```

> Note: This step is optional. The framework will auto-detect and prompt installation when needed.

#### Other Platforms (HarmonyOS / iOS)

See the [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM) device connection guide.

### 4. Configure Chat Platforms (optional)

To remotely control the phone via chat platforms, enable the corresponding platform in `channels` within `config.json` and fill in credentials.

#### Feishu / Lark

<details>
<summary>📖 Click to expand setup steps</summary>

- **Step 1**: Go to [Feishu Open Platform](https://open.feishu.cn/), click **Create App** on the homepage, select **Enterprise Self-Built App**, fill in the app name and description, and enable the **Bot** capability.
- **Step 2**: Click **Permission Management** on the left sidebar, then click **Enable Permissions**.
- **Step 3**: Search for and enable the following permissions in the text box: `im:message`, `im:message.p2p_msg:readonly`, `cardkit:card:write`
  > If `cardkit:card:write` cannot be added, set `"streaming": false` in `channels.feishu` (see config below). The bot will still work normally; replies use regular interactive cards without token-by-token streaming.
- **Step 4**: Click **Event & Callback** on the left, click **Subscription Method**, and select **Persistent Event Reception** (requires ClawGUI-Agent to be running to establish the connection).
- **Step 5**: Go to **Credentials & Basic Info** on the left to get your `App ID` and `App Secret`.
- **Step 6**: Click **Publish App**.
- **Step 7**: Open Feishu, go to any group, click the group settings, click **Group Bots**, then **Add Bot**, and add the bot you just created to the group.
- **Step 8**: @mention the bot in the group and send a message.

</details>

4. Configure in `~/.nanobot/config.json`:

```json
"feishu": {
  "enabled": true,
  "appId": "YOUR_APP_ID",
  "appSecret": "YOUR_APP_SECRET",
  "encryptKey": "",
  "verificationToken": "",
  "allowFrom": ["*"],
  "groupPolicy": "mention"
}
```

> `allowFrom` set to `["*"]` allows all users. To restrict, provide a list of user Open IDs. `groupPolicy` set to `"mention"` means the bot only responds when @mentioned in groups.

#### QQ

1. Go to [QQ Open Platform](https://q.qq.com/) and create a bot application
2. Obtain the `App ID` and `Secret`
3. Configure in `~/.nanobot/config.json`:

```json
"qq": {
  "enabled": true,
  "appId": "YOUR_APP_ID",
  "secret": "YOUR_SECRET",
  "allowFrom": ["*"]
}
```

#### Other Platforms

nanobot also supports Telegram, Discord, Slack, DingTalk, WeCom, WhatsApp, Email and 12+ more platforms. Set `"enabled": true` in the corresponding `channels` field and fill in credentials.

## 🚀 Run

### Control Phone via nanobot Chat

Start the nanobot gateway service:

```bash
nanobot gateway
```

Once started, you can send messages on configured chat platforms (e.g. Feishu) to control the phone:

```
Open WeChat and send "I'll be late" to Zhang San
```

nanobot will invoke the `gui_execute` tool, automatically capturing screenshots → VLM reasoning → executing phone actions in a loop until the task is completed.

## 📊 ClawGUI-Eval Evaluation

ClawGUI-Agent includes a built-in [ClawGUI-Eval](../clawgui-eval) skill that turns natural language into a complete benchmark run — from GPU environment check through multi-GPU inference, judging, and metric reporting — without writing a single script.

### Prerequisites

1. **workspace correctly set**: `workspace` in `config.json` points to the ClawGUI root directory (see configuration above)
2. **ClawGUI-Eval environment installed**: Follow [ClawGUI-Eval README](../clawgui-eval/README.md) to install and download data
3. **GPU available**: Inference requires NVIDIA GPUs
4. **(Recommended) Install FlashAttention-2**: `pip install flash-attn --no-build-isolation` — the framework falls back to SDPA automatically if not installed, but precision may be slightly lower

### Usage

Simply say it in a nanobot conversation:

```
Benchmark qwen3vl 2b model on screenspot-pro
```

```
Run uivision and osworld-g evaluation with MAI-UI-8B
```

nanobot will automatically:

1. **Environment Check** — Check GPU, CUDA, FlashAttention-2, data integrity
2. **Inference** — Generate run scripts from templates, launch multi-GPU parallel inference in background, monitor progress in real-time
3. **Judging** — Automatically select and run the corresponding judge script
4. **Metric Calculation** — Automatically select and run the corresponding metric script
5. **Result Report** — Present accuracy, sub-category breakdowns, and comparison against official baselines

### Supported Evaluation Models

| Model Type | Example HuggingFace ID |
|------------|----------------------|
| `qwen3vl` | Qwen/Qwen3-VL-2B/4B/8B-Instruct |
| `qwen25vl` | Qwen/Qwen2.5-VL-3B/7B-Instruct |
| `maiui` | Tongyi-MAI/MAI-UI-2B/8B |
| `uitars` | ByteDance-Seed/UI-TARS-1.5-7B |
| `uivenus15` | inclusionAI/UI-Venus-1.5-2B/8B |
| `guiowl15` | mPLUG/GUI-Owl-1.5-2B/4B/8B-Instruct |
| `guig2` | inclusionAI/GUI-G2-7B |
| `stepgui` | stepfun-ai/GELab-Zero-4B-preview |
| `uivenus` | inclusionAI/UI-Venus-Ground-7B |

Supported Benchmarks: ScreenSpot-Pro, ScreenSpot-V2, UIVision, MMBench-GUI, OSWorld-G, AndroidControl


## 📱 GUI Phone Control

The following features are part of ClawGUI-Agent's phone/device control capabilities, driven by the `gui_execute` tool.

You can also invoke the GUI agent directly via command line:

```bash
python main.py \
  --base-url https://open.bigmodel.cn/api/paas/v4/ \
  --model autoglm-phone \
  --apikey <YOUR_API_KEY> \
  --max-steps 100 \
  --lang cn \
  "Open QQ Music, play Justin Bieber's Baby and add it to favorites. If it is already favorited, just play it. After it starts playing, pause it, then go back and play Bieber's Love Me."
```

### Web UI

In addition to chat platform control, you can use the Web UI directly:

```bash
python webui.py
```

Opens at `http://localhost:7860` by default, featuring:

- **Device Management**: Connect/disconnect devices, view device status
- **Task Execution**: Enter task descriptions, watch screenshots and AI reasoning in real-time
- **Manual Takeover**: Switch to manual control for scenarios like CAPTCHAs
- **Memory Management**: View/edit/clear memory data
- **Skill Library Management**: Inspect skill names, `skill_id`, success rate, revisions, docs, and failure examples
- **Configuration Panel**: Graphical model parameter settings

### Memory System

The framework includes a built-in personalized memory system (`phone_agent/memory/`). After each completed task, the agent extracts structured facts from the conversation — contact names and relationships, app-specific knowledge, user habits and preferences — and upserts them into a persistent store as JSON records with numpy vector embeddings. On subsequent tasks, the top-k most semantically similar memories are retrieved and injected into the system context, letting the agent recognize "Zhang San" as the user's colleague or know which music app the user prefers. Duplicate memories are detected and merged rather than accumulated, keeping the store lean. Multi-user isolation is supported via per-user namespaces.

### Self-Evolving Skill Mode

ClawGUI-Agent can optionally use [ClawGUI-Skills](../clawgui-skills), the training-free GUI skill evolution architecture proposed and validated in our paper **“Reflect, Revise, Reuse: Training-Free Skill Evolution for GUI Agents.”** Skill mode is disabled by default; context is added only when `reuse` or `evolve` is selected.

| Mode | Behavior |
|------|----------|
| `off` | Default. No retrieval, injection, or evolution |
| `trace` | Record trajectories for later offline skill evolution |
| `reuse` | Retrieve an existing skill and inject compact `plan.md`, `backup.md`, and `recover.md` context |
| `evolve` | Retrieve or build a skill package; after failure, diagnose the trace, revise targeted skill files, add failure examples, and let PhoneAgent retry immediately when a revision is produced |

Skill packages live under `skillsDir`:

```text
skill_store/<skill_id>/
  meta_info.json
  docs/plan.md
  docs/backup.md
  docs/recover.md
  failure_examples/
  versions/
  edits.jsonl
  runs.jsonl
```

In the Web UI, the configuration tab chooses the skill mode; the task log shows matched skill name, `skill_id`, retrieval score, injected context size, verifier diagnosis, and edited files. The skill library tab lets you inspect the actual package and evolution history.

The default `auto` backend reuses the current PhoneAgent OpenAI-compatible model endpoint: skill creation uses the paper's three-step prompt generator (`meta+plan -> backup -> recover`), failure diagnosis uses the isolated verifier prompt, and revision uses the `skill_revise` prompt plus restricted file tools. Offline tests or unavailable endpoints fall back to the lightweight implementation. CLI flags `--skill-generator-mode`, `--skill-verifier-mode`, and `--skill-revision-mode` accept `auto`, `model`, or `fallback`.

The CLI can enable it directly:

```bash
python main.py \
  --skill-mode evolve \
  --skills-dir skill_store \
  --skill-threshold 0.35 \
  --skill-max-iterations 2 \
  "Open Settings and turn on Bluetooth"
```

### Supported GUI Models

The framework supports multiple Vision-Language Models via an adapter pattern:

| Model | `promptTemplateStyle` | Provider |
|-------|----------------------|----------|
| **AutoGLM-Phone-9B** | `autoglm` | Zhipu AI |
| **Doubao-1.5-UI-TARS** | `uitars` | ByteDance |
| **Qwen2.5-VL / Qwen3-VL** | `qwenvl` | Alibaba Cloud |
| **MAI-UI** | `maiui` | Alibaba Cloud |
| **GUI-Owl-7B/32B** | `guiowl` | mPLUG |

All models are connected via **OpenAI-compatible API** and can be deployed locally with vLLM / SGLang, or connected to cloud services such as Zhipu BigModel, Alibaba Cloud Bailian, or OpenRouter.


## 📁 Directory Structure

```
ClawGUI-Agent/
├── main.py                      # CLI entry point
├── webui.py                     # Gradio Web UI entry point
├── ios.py                       # iOS CLI entry point
├── setup.py                     # Package setup
├── requirements.txt             # Python dependencies
│
├── phone_agent/                 # Core phone automation package
│   ├── agent.py                 # PhoneAgent main class (screenshot→VLM→action loop)
│   ├── agent_ios.py             # IOSPhoneAgent class
│   ├── device_factory.py        # Device type factory (ADB / HDC / XCTest)
│   ├── tracer.py                # Episode execution tracer
│   ├── skills_runtime.py        # ClawGUI-Skills runtime bridge
│   ├── config/                  # Configuration & prompts (8 template files)
│   ├── model/                   # Model clients & adapters (5 VLM adapters)
│   ├── adb/                     # Android ADB device control
│   ├── hdc/                     # HarmonyOS HDC device control
│   ├── xctest/                  # iOS XCTest device control
│   ├── actions/                 # Action handlers (tap, swipe, type, etc.)
│   └── memory/                  # Personalized memory system (vector store)
│
├── nanobot/                     # nanobot subproject
│   ├── nanobot/                 # nanobot core package
│   │   ├── agent/               # Agent core + GUI tool
│   │   ├── channels/            # 12+ chat platform integrations
│   │   ├── providers/           # 20+ LLM provider adapters
│   │   └── skills/              # Pluggable skills (gui-mobile, clawgui-eval)
│   ├── pyproject.toml
│   └── README.md
│
├── examples/                    # Usage examples
└── scripts/                     # Deployment & verification scripts
```

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE). The nanobot subproject is licensed under the [MIT License](nanobot/LICENSE).
