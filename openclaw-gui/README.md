<div align="center">
  <img src="assets/openclaw-gui-text.png" alt="OpenClaw-GUI Text" height="40">
</div>
<div align="center">
  <table style="border: none; border-collapse: collapse;">
    <tr>
      <td style="border: none; padding: 0;"><img src="assets/openclaw-gui-banner.png" alt="OpenClaw-GUI Banner" height="60" style="vertical-align: middle;"></td>
      <td style="border: none; padding: 0 0 0 12px; vertical-align: middle;"><h1 style="margin: 0;">OpenClaw-GUI: Personal Phone GUI Assistant</h1></td>
    </tr>
  </table>
  <p>
    <img src="https://img.shields.io/badge/python-≥3.10-blue" alt="Python">
    <img src="https://img.shields.io/badge/license-Apache%202.0-green" alt="License">
    <img src="https://img.shields.io/badge/platform-Android%20|%20HarmonyOS%20|%20iOS-orange" alt="Platform">
  </p>
</div>

[English](README.md) | [中文](README_CN.md)

---

**OpenClaw-GUI** is a GUI Agent framework built on [OpenClaw](https://github.com/openclaw/openclaw). By integrating the [nanobot](https://github.com/HKUDS/nanobot) personal AI assistant, it provides two core capabilities: **GUI phone control** and **GUI model evaluation**. Users can remotely control their phones via natural language on chat platforms like Feishu, QQ, and Telegram, or launch [opengui-eval](../opengui-eval) standardized evaluation pipelines with a single command. Under the hood, the framework leverages Vision-Language Models (VLMs) to understand screen content, plan and execute GUI actions (tap, swipe, type, etc.), forming a closed-loop "screenshot → reasoning → action" automation.

## 📑 Table of Contents

- [Key Features](#-key-features)
- [Architecture](#-architecture)
- [Quick Start](#-quick-start)
- [Run](#-run)
- [OpenGUI-Eval Evaluation](#-opengui-eval-evaluation)
- [GUI Phone Control](#-gui-phone-control) (Web UI / Memory System / Supported GUI Models)
- [Directory Structure](#-directory-structure)
- [License](#-license)

## ✨ Key Features

- 💬 **nanobot Integration** — Remotely control phones from 12+ chat platforms including Feishu / DingTalk / Telegram / Discord / Slack / QQ, issue tasks anytime anywhere

- 📱 **OpenClaw-GUI Phone Control** — Powered by OpenClaw, AI autonomously captures screenshots, understands the screen, and performs tap/swipe/type GUI actions to complete complex tasks

- 📊 **OpenGUI-Eval Evaluation Integration** — Built-in [opengui-eval](../opengui-eval) evaluation skill, launch GUI Grounding model benchmarks with natural language (environment check → multi-GPU inference → judging → metric calculation), with automatic progress monitoring and result comparison against official baselines

- 🧠 **Multi-Model Support** — Compatible with AutoGLM, Qwen VL, UI-TARS, MAI-UI, GUI-Owl and more VLMs, connected via OpenAI-compatible API

- 💾 **Personalized Memory** — Automatically learns user preferences (contacts, frequently used apps, habits), with a vector-search-based persistent memory system

- 📝 **Real-time Episode Recording** — Each task execution (screenshots + model outputs + actions) is saved as a structured episode, enabling replay and dataset construction

- 🖥️ **Web UI** — Gradio-based web interface for device management, task execution visualization, manual takeover, memory management and more

## 🏗️ Architecture

<p align="center">
  <img src="assets/openclaw-gui-logo.png" alt="OpenClaw-GUI Architecture" width="800">
</p>

## 🚀 Quick Start

### Requirements

- **Python**: ≥ 3.11
- **Package Manager**: [uv](https://github.com/astral-sh/uv) (recommended) or conda + pip

### 1. Installation

Assuming you have cloned the OpenGUI project and are in the root directory:

#### Option A: uv (recommended)

```bash
cd openclaw-gui

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
cd openclaw-gui

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

```json
{
  "agents": {
    "defaults": {
      "workspace": "/path/to/OpenGUI",
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
      "guiModelName": "qwen/qwen3.5-35b-a3b",
      "promptTemplateLang": "en",
      "promptTemplateStyle": "autoglm",
      "traceEnabled": false,
      "traceDir": "gui_trace"
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
> Set `workspace` to the root directory of your OpenGUI project (the directory containing both `openclaw-gui/` and `opengui-eval/`). This allows the built-in evaluation skill (opengui-eval) to locate the evaluation framework correctly. For example, if your project is at `/home/user/OpenGUI`, set it to `"/home/user/OpenGUI"`.

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

### 3. Connect Android Device

> The controlled phone must be connected (e.g. via USB) to the server machine where OpenClaw-GUI is installed.

#### Step 1: Install ADB

**macOS (recommended: brew):**

```bash
brew install android-platform-tools
```

**Linux:**

```bash
sudo apt install android-tools-adb   # Ubuntu/Debian
# Or download manually: https://developer.android.com/tools/releases/platform-tools
```

**Windows:** See the [official guide](https://developer.android.com/tools/releases/platform-tools) to download and configure PATH.

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
- **Step 4**: Click **Event & Callback** on the left, click **Subscription Method**, and select **Persistent Event Reception** (requires openclaw-gui to be running to establish the connection).
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

## 📊 OpenGUI-Eval Evaluation

OpenClaw-GUI has a built-in [opengui-eval](../opengui-eval) evaluation skill that drives standardized GUI Grounding model evaluation via natural language.

### Prerequisites

1. **workspace correctly set**: `workspace` in `config.json` points to the OpenGUI root directory (see configuration above)
2. **opengui-eval environment installed**: Follow [opengui-eval README](../opengui-eval/README.md) to install and download data
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

---

## 📱 GUI Phone Control

The following features are part of OpenClaw-GUI's phone/device control capabilities, driven by the `gui_execute` tool.

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
- **Configuration Panel**: Graphical model parameter settings

### Memory System

The framework includes a built-in personalized memory system that automatically extracts valuable information from conversations after each task (contacts, app preferences, user habits), stored persistently as JSON + numpy vector embeddings. On subsequent similar tasks, relevant memories are automatically injected into context for smarter personalized operations. Multi-user isolation is supported.

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

---

## 📁 Directory Structure

```
OpenClaw-GUI/
├── main.py                      # CLI entry point
├── webui.py                     # Gradio Web UI entry point
├── ios.py                       # iOS CLI entry point
├── setup.py                     # Package setup
├── requirements.txt             # Python dependencies
│
├── phone_agent/                 # Core phone automation package
│   ├── agent.py                 # PhoneAgent main class
│   ├── agent_ios.py             # IOSPhoneAgent class
│   ├── device_factory.py        # Device type factory
│   ├── tracer.py                # Episode execution tracer
│   ├── config/                  # Configuration & prompts
│   ├── model/                   # Model clients & adapters
│   ├── adb/                     # Android ADB device control
│   ├── hdc/                     # HarmonyOS HDC device control
│   ├── xctest/                  # iOS XCTest device control
│   ├── actions/                 # Action handlers
│   └── memory/                  # Personalized memory system
│
├── nanobot/                     # nanobot subproject
│   ├── nanobot/                 # nanobot core package
│   │   ├── agent/               # Agent core + GUI tool
│   │   ├── channels/            # 12+ chat platform integrations
│   │   ├── providers/           # 20+ LLM provider adapters
│   │   └── skills/              # Pluggable skills (gui-mobile, opengui-eval)
│   ├── pyproject.toml
│   └── README.md
│
├── examples/                    # Usage examples
└── scripts/                     # Deployment & verification scripts
```

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE). The nanobot subproject is licensed under the [MIT License](nanobot/LICENSE).
