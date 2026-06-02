<div align="center">
  <img src="assets/clawgui-agent-text.png" alt="ClawGUI-Agent Text" height="40">
</div>
<div align="center">
  <table style="border: none; border-collapse: collapse;">
    <tr>
      <td style="border: none; padding: 0;"><img src="assets/clawgui-agent-banner.png" alt="ClawGUI-Agent Banner" height="60" style="vertical-align: middle;"></td>
      <td style="border: none; padding: 0 0 0 12px; vertical-align: middle;"><h1 style="margin: 0;">ClawGUI-Agent：个人手机 GUI 助手</h1></td>
    </tr>
  </table>
  <p>
    <img src="https://img.shields.io/badge/python-≥3.10-blue" alt="Python">
    <img src="https://img.shields.io/badge/license-Apache%202.0-green" alt="License">
    <img src="https://img.shields.io/badge/platform-Android%20|%20HarmonyOS%20|%20iOS-orange" alt="Platform">
  </p>
</div>

[English](README.md) | [中文](README_CN.md)


**ClawGUI-Agent** 是 [ClawGUI](../README_zh.md) 的部署模块。基于 [OpenClaw](https://github.com/openclaw/openclaw) 构建，由 [nanobot](https://github.com/HKUDS/nanobot) 驱动，提供两大核心能力：**GUI 手机操控**和**一句话评测**。手机操控方面，框架驱动视觉语言模型（VLM）在 Android、鸿蒙、iOS 设备上完成"截屏 → 推理 → 操作"闭环自动化，可通过飞书、QQ、Telegram 等 12+ 聊天平台远程下发任务。评测方面，一句自然语言即可触发完整的 [ClawGUI-Eval](../clawgui-eval) 流程：环境检测、多 GPU 推理、判分、指标汇报，全程无需手写脚本。

## 📑 目录

- [核心特性](#-核心特性)
- [架构](#️-架构)
- [智能体工作原理](#-智能体工作原理)
- [快速开始](#-快速开始)
  - [环境要求](#环境要求)
  - [1. 安装](#1-安装)
  - [2. 初始化并编辑配置](#2-初始化并编辑配置)
  - [3. 连接 Android 设备](#3-连接-android-设备)
  - [4. 配置聊天平台（可选）](#4-配置聊天平台可选)
- [运行](#-运行)
- [ClawGUI-Eval 评测](#-clawgui-eval-评测)
- [GUI 手机操控能力](#-gui-手机操控能力)
  - [Web UI](#web-ui)
  - [记忆系统](#记忆系统)
  - [自进化技能模式](#自进化技能模式)
  - [支持的 GUI 模型](#支持的-gui-模型)
- [目录结构](#-目录结构)
- [许可证](#-许可证)

## ✨ 核心特性

- **nanobot 集成** — 通过飞书 / 钉钉 / Telegram / Discord / Slack / QQ 等 12+ 聊天平台远程控制手机，随时随地下发任务
- **GUI 手机操控** — 基于 OpenClaw 能力，AI 自主截屏、理解屏幕、执行点击/滑动/输入等 GUI 操作，完成复杂任务
- **ClawGUI-Eval 评测集成** — 内置 [ClawGUI-Eval](../clawgui-eval) 评测技能，用自然语言一句话即可启动 GUI Grounding 模型评测（环境检测 → 多 GPU 推理 → 判分 → 指标计算），自动监控进度并汇报结果与官方基线对比
- **多模型适配** — 支持 AutoGLM、Qwen VL、UI-TARS、MAI-UI、GUI-Owl 等多种 VLM，通过 OpenAI 兼容 API 接入
- **个性化记忆** — 自动学习用户偏好（联系人、常用 App、习惯），基于向量搜索的持久化记忆系统
- **自进化技能** — 可选启用 [ClawGUI-Skills](../clawgui-skills)，按需检索、构建、注入和修订 GUI 任务技能包
- **Episode 实时记录** — 每次任务执行过程（截图 + 模型输出 + 动作）以结构化 episode 形式保存，便于回放与数据集构建
- **Web UI** — 提供 Gradio Web 界面，支持设备管理、任务执行可视化、手动接管、记忆管理等

## 🏗️ 架构

<p align="center">
  <img src="assets/clawgui-agent-logo.png" alt="ClawGUI-Agent Architecture" width="800">
</p>

## 🔄 智能体工作原理

了解执行循环有助于配置与调试。`phone_agent/agent.py` 中的 `PhoneAgent.run()` 对每个任务执行以下闭环：

1. **截图** — 通过 ADB（`screencap`）、HDC 或 XCTest 捕获当前屏幕，具体取决于设备后端。
2. **记忆检索** — 在向量记忆库中检索与当前任务相关的历史记忆（联系人、App 知识、用户偏好）。最相关的 top-k 条记忆追加至系统 Context。
3. **技能检索** — 若 `skillMode` 为 `reuse` 或 `evolve`，在 [ClawGUI-Skills](../clawgui-skills) 技能库中按 metadata 检索最相关技能；`evolve` 模式下无命中会构建新技能包。
4. **历史构建** — 组装多轮对话历史：每个历史步骤贡献一个 `(用户: 截图+指令, 助手: 推理+动作)` 对，最多回溯 `history_length` 步。
5. **VLM 调用** — 将提示词（System Prompt + 历史 + 当前截图 + 任务指令 + 可选技能上下文）通过 OpenAI 兼容 API 发送给已配置的 GUI 模型。
6. **动作解析** — 从模型输出中提取结构化动作。不同模型使用不同的输出格式（`phone_agent/model/adapters.py` 中的 `autoglm`、`uitars`、`qwenvl`、`maiui`、`guiowl` 适配器）。
7. **坐标归一化** — 将模型输出的坐标转换为设备绝对像素坐标。AutoGLM 使用 `[0, 1000]` 归一化坐标；UI-TARS 使用 `smart_resize` 空间中的绝对像素坐标；Qwen-VL 使用绝对像素；MAI-UI 使用 `[0, 1000]`。
8. **动作执行** — 向设备后端发送动作：点击、长按、滑动、输入、Home、返回或任务完成。每种动作类型在 `phone_agent/actions/` 中有专属处理器。
9. **轨迹记录** — 若 `traceEnabled=True` 或 `skillMode=evolve`，将截图、推理过程和动作追加至 Episode Tracer，便于后续回放、诊断或训练数据导出。
10. **技能演化与记忆更新** — 任务完成后更新个性化记忆；若 `skillMode=evolve` 且任务失败，isolated verifier 会基于轨迹诊断并通过受限文件工具修订 `plan.md`、`backup.md` 或 `recover.md`，同时写入 failure example。

该循环持续运行，直到模型输出 `terminate` 或 `answer` 动作，或达到 `max_steps` 上限。

## 🚀 快速开始

### 环境要求

- **Python**：≥ 3.11
- **包管理器**：推荐 [uv](https://github.com/astral-sh/uv)，也支持 conda + pip

### 1. 安装

假设你已经 clone 了 ClawGUI 项目并处于根目录：

#### 方式一：uv（推荐）

```bash
cd clawgui-agent

# 创建虚拟环境
uv venv .venv --python 3.12

# 激活虚拟环境
source .venv/bin/activate

# 安装 phone_agent
uv pip install -e .

# 安装 nanobot
uv pip install -e nanobot/
```

#### 方式二：conda + pip

```bash
cd clawgui-agent

# 创建 conda 环境
conda create -n opengui python=3.12 -y
conda activate opengui

# 安装 phone_agent
pip install -e .

# 安装 nanobot
pip install -e nanobot/
```

### 2. 初始化并编辑配置

运行 onboard 向导生成默认配置：

```bash
nanobot onboard
```

然后编辑 `~/.nanobot/config.json`，以下是一份参考配置：

> 我们推荐使用 **autoglm-phone** 作为外部的 GUI 模型进行手机操控调用。

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
      "guiModelName": "qwen/qwen3.5-35b-a3b",
      "promptTemplateLang": "cn",
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

> **重要：`workspace` 路径设置**
>
> 请将 `workspace` 设置为 ClawGUI 项目的根目录（即同时包含 `clawgui-agent/` 和 `clawgui-eval/` 的目录）。内置评测技能通过此路径定位评测框架。例如项目位于 `/home/user/ClawGUI`，则填写 `"/home/user/ClawGUI"`。

#### GUI 工具参数说明

| 参数 | 说明 |
|------|------|
| `enable` | 是否启用 GUI 手机控制工具 |
| `deviceType` | 设备类型：`adb`（Android）或 `hdc`（鸿蒙） |
| `deviceId` | 指定设备 ID，`null` 则自动检测 |
| `maxSteps` | 单次任务最大执行步数 |
| `useExternalModel` | 是否使用外部 GUI 专用模型（推荐 `true`） |
| `guiBaseUrl` | 外部 GUI 模型的 API 地址 |
| `guiApiKey` | 外部 GUI 模型的 API Key |
| `guiModelName` | 外部 GUI 模型名称，搭配 guiBaseUrl 使用 |
| `promptTemplateLang` | 提示词语言：`cn` / `en` |
| `promptTemplateStyle` | 提示词风格：`autoglm` / `uitars` / `qwenvl` 等 |
| `traceEnabled` | 是否开启 Episode 记录 |
| `traceDir` | Episode 保存目录 |
| `skillMode` | 自进化技能模式：`off` / `trace` / `reuse` / `evolve` |
| `skillsDir` | 技能库目录，保存技能包、版本和审计日志 |
| `skillRetrievalThreshold` | 技能检索阈值，低于阈值不注入已有技能 |
| `skillMaxContextChars` | 单次注入的技能上下文字符预算 |
| `skillMaxIterations` | `evolve` 模式产生修订后允许的即时重试次数 |
| `skillRequireReview` | 是否只保存 verifier 反馈、等待人工确认后再修订 |

### 3. 连接 Android 设备

> 受控手机需要通过 USB/WIFI 等方式连接到安装了 ClawGUI-Agent 的服务端机器上。

#### Step 1: 安装 ADB

**方案 A：通过包管理器安装**

**macOS（推荐 brew）：**

```bash
brew install android-platform-tools
```

**Linux：**

```bash
sudo apt install android-tools-adb   # Ubuntu/Debian
```

**Windows：** 请参考此 [博客教程](https://blog.csdn.net/x2584179909/article/details/108319973) 下载并配置环境变量。

**方案 B：手动下载安装**

下载官方 [ADB platform-tools](https://developer.android.com/tools/releases/platform-tools) 并解压，然后将其添加到 PATH 环境变量：

**macOS / Linux：**

```bash
# 假设解压到 ~/Downloads/platform-tools
export PATH=${PATH}:~/Downloads/platform-tools
```

**Windows：** 将解压目录（如 `C:\platform-tools`）添加到系统 PATH 环境变量中。

#### Step 2: 连接手机并开启 USB 调试

1. **开启开发者模式**：进入 设置 > 关于手机 > 版本号，连续快速点击约 10 次，直到看到"您已处于开发者模式"提示
2. **开启 USB 调试**：进入 设置 > 开发者选项 > USB 调试，启用它（部分设备可能需要重启）
3. **验证连接**：

```bash
adb devices

# 预期输出：
# List of devices attached
# <your_device_id>   device
```

#### Step 3: 安装 ADB Keyboard（可选）

ADB Keyboard 用于文字输入。下载 [ADBKeyboard.apk](https://github.com/senzhk/ADBKeyBoard/blob/master/ADBKeyboard.apk) 并安装到设备：

```bash
adb install ADBKeyboard.apk
adb shell ime enable com.android.adbkeyboard/.AdbIME
```

> 注意：此步骤为可选，框架会在需要时自动检测并提示安装。

#### 其他平台（鸿蒙 / iOS）

请参考 [Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM) 的设备连接指南。

### 4. 配置聊天平台（可选）

如需通过聊天平台远程控制手机，在 `config.json` 的 `channels` 中启用对应平台并填写凭证。以下是飞书和 QQ 的配置示例：

#### 飞书（Feishu / Lark）

<details>
<summary>📖 点击展开详细步骤</summary>

- **Step 1**：打开 [飞书开放平台](https://open.feishu.cn/)，在首页点击**创建应用**，选择**企业自建应用**，填入应用名称和应用描述，并启用**机器人**功能。
- **Step 2**：点击左侧的**权限管理**，点击**开通权限**。
- **Step 3**：在文本框搜索并开通以下权限：`im:message`、`im:message.p2p_msg:readonly`、`cardkit:card:write`
  > 如果无法添加 `cardkit:card:write`，请在下方配置的 `channels.feishu` 中设置 `"streaming": false`。机器人仍可正常工作；回复使用普通的交互式卡片，无需逐个令牌流式传输。
- **Step 4**：点击左侧的**事件与回调**，点击**订阅方式**，选择**长时间接受事件**（需要运行 ClawGUI-Agent 建立连接）。
- **Step 5**：从左侧的**凭证和基础信息**找到 `App ID` 和 `App Secret`，拿到密钥。
- **Step 6**：点击**发布应用**。
- **Step 7**：打开飞书，随便进入一个群里，点击群聊的设置，点击**群机器人**，点击**添加机器人**，将刚刚创建的机器人加入群聊。
- **Step 8**：@机器人发送消息。

</details>

4. 最后在 `~/.nanobot/config.json` 中配置：

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

> `allowFrom` 设为 `[\"*\"]` 表示允许所有用户。如需限制，填入用户的 Open ID 列表。`groupPolicy` 设为 `"mention"` 表示在群聊中需 @机器人 才会响应。

#### QQ

1. 前往 [QQ 开放平台](https://q.qq.com/) 注册并创建机器人应用
2. 获取 `App ID` 和 `Secret`
3. 在 `~/.nanobot/config.json` 中配置：

```json
"qq": {
  "enabled": true,
  "appId": "YOUR_APP_ID",
  "secret": "YOUR_SECRET",
  "allowFrom": ["*"]
}
```

#### 其他平台

nanobot 还支持 Telegram、Discord、Slack、钉钉、企业微信、WhatsApp、Email 等 12+ 平台。在 `config.json` 的 `channels` 对应字段中设置 `"enabled": true` 并填写凭证即可。

## 🚀 运行

### 通过 nanobot 聊天控制手机

启动 nanobot gateway 服务：

```bash
nanobot gateway
```

启动后，即可在已配置的聊天平台（如飞书）中发送消息来操控手机，例如：

```
帮我打开微信给张三发消息说我晚点到
```

nanobot 会调用 `gui_execute` 工具，自动截屏 → VLM 推理 → 执行手机操作，循环直到任务完成。

## 📊 ClawGUI-Eval 评测

ClawGUI-Agent 内置 [ClawGUI-Eval](../clawgui-eval) 评测技能，一句自然语言即可完成从 GPU 环境检测、多 GPU 推理、判分到指标汇报的完整流程，无需手写任何脚本。

### 前置条件

1. **workspace 已正确设置**：`config.json` 中的 `workspace` 指向 ClawGUI 根目录（见上方配置说明）
2. **ClawGUI-Eval 环境已安装**：参考 [ClawGUI-Eval README](../clawgui-eval/README_zh.md) 完成安装和数据下载
3. **GPU 可用**：推理需要 NVIDIA GPU
4. **（推荐）安装 FlashAttention-2**：`pip install flash-attn --no-build-isolation`，未安装时框架会自动降级为 SDPA，但精度可能略有下降

### 使用方式

在 nanobot 对话中直接说即可，例如：

```
帮我测一下 qwen3vl 2b 模型在 screenspot-pro 上的指标
```

```
用 MAI-UI-8B 跑一下 uivision 和 osworld-g 的评测
```

nanobot 会自动完成以下流程：

1. **环境检测** — 检查 GPU、CUDA、FlashAttention-2、数据完整性
2. **推理** — 基于模板脚本生成运行脚本，后台启动多 GPU 并行推理，实时监控进度
3. **判分** — 自动选择对应的 judge 脚本执行
4. **指标计算** — 自动选择对应的 metric 脚本执行
5. **结果汇报** — 展示准确率、分项指标，并与官方基线对比

### 支持的评测模型

| 模型类型 | 示例 HuggingFace ID |
|---------|-------------------|
| `qwen3vl` | Qwen/Qwen3-VL-2B/4B/8B-Instruct |
| `qwen25vl` | Qwen/Qwen2.5-VL-3B/7B-Instruct |
| `maiui` | Tongyi-MAI/MAI-UI-2B/8B |
| `uitars` | ByteDance-Seed/UI-TARS-1.5-7B |
| `uivenus15` | inclusionAI/UI-Venus-1.5-2B/8B |
| `guiowl15` | mPLUG/GUI-Owl-1.5-2B/4B/8B-Instruct |
| `guig2` | inclusionAI/GUI-G2-7B |
| `stepgui` | stepfun-ai/GELab-Zero-4B-preview |
| `uivenus` | inclusionAI/UI-Venus-Ground-7B |

支持的 Benchmark：ScreenSpot-Pro、ScreenSpot-V2、UIVision、MMBench-GUI、OSWorld-G、AndroidControl


## 📱 GUI 手机操控能力

以下功能均属于 ClawGUI-Agent 的手机/设备操控能力，通过 `gui_execute` 工具驱动。

你也可以通过以下方式直接通过命令行调用 GUI Agent：

```bash
python main.py \
  --base-url https://open.bigmodel.cn/api/paas/v4/ \
  --model autoglm-phone \
  --apikey <YOUR_API_KEY> \
  --max-steps 100 \
  --lang cn \
  "Open QQ Music, play Justin Bieber's Baby and add it to favorites."
```

### Web UI

除了通过聊天平台控制，还可以使用 Web UI 直接操控：

```bash
python webui.py
```

默认在 `http://localhost:7860` 打开，支持：

- **设备管理**：连接/断开设备、查看设备状态
- **任务执行**：输入任务描述，实时查看截图和 AI 思考过程
- **手动接管**：遇到验证码等场景可切换手动操作
- **记忆管理**：查看/编辑/清除记忆数据
- **技能库管理**：查看技能包名称、`skill_id`、成功率、修订次数、文档和失败案例
- **配置面板**：图形化设置模型参数

### 记忆系统

框架内置个性化记忆系统（`phone_agent/memory/`）。每次任务完成后，智能体自动从对话中提取结构化事实——联系人姓名与关系、App 专属知识、用户习惯与偏好——以 JSON 记录 + numpy 向量嵌入的形式持久化存储。执行后续任务时，语义最相关的 top-k 条记忆被检索出来并注入 System Context，让智能体认识"张三是用户的同事"，或知道用户偏好哪个音乐 App。重复记忆会被检测并合并而非累积，保持记忆库精简。支持多用户命名空间隔离。

### 自进化技能模式

ClawGUI-Agent 可选接入 [ClawGUI-Skills](../clawgui-skills)，该模块实现我们论文 **《Reflect, Revise, Reuse: Training-Free Skill Evolution for GUI Agents》** 中提出并验证的训练自由 GUI 技能自进化架构。技能模式默认关闭，只有显式选择 `reuse` 或 `evolve` 时才会增加技能上下文。

| 模式 | 行为 |
|------|------|
| `off` | 默认模式，不检索、不注入、不演化 |
| `trace` | 只记录轨迹，为后续离线技能演化准备 |
| `reuse` | 检索已有技能包，命中后注入 `plan.md`、`backup.md`、`recover.md` 的精简上下文 |
| `evolve` | 检索或构建技能包；失败后基于轨迹诊断并修订特定技能文件，加入 failure examples；PhoneAgent 会在产生修订后按 `skillMaxIterations` 即时重试 |

技能包保存在 `skillsDir` 下：

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

在 Web UI 的「配置管理」中选择技能模式后，「对话控制」日志会显示命中的精简技能名、`skill_id`、检索分数、注入字符数、verifier 诊断和修订文件；「技能库」Tab 可查看具体技能包与演化历史。

默认 `auto` 后端会复用当前 PhoneAgent 的 OpenAI 兼容模型接口：有接口时技能构建采用论文版三阶段 prompt generator（`meta+plan -> backup -> recover`），失败诊断采用 isolated verifier prompt，修订采用 `skill_revise` prompt + 受限文件工具；离线测试或接口不可用时会退回轻量 fallback。CLI 可用 `--skill-generator-mode`、`--skill-verifier-mode`、`--skill-revision-mode` 分别设为 `auto`、`model` 或 `fallback`。

命令行也可以直接启用：

```bash
python main.py \
  --skill-mode evolve \
  --skills-dir skill_store \
  --skill-threshold 0.35 \
  --skill-max-iterations 2 \
  "打开设置并开启蓝牙"
```

### 支持的 GUI 模型

框架通过适配器模式支持多种视觉语言模型：

| 模型 | `promptTemplateStyle` | 来源 |
|------|----------------------|------|
| **AutoGLM-Phone-9B** | `autoglm` | 智谱 AI |
| **Doubao-1.5-UI-TARS** | `uitars` | 字节跳动 |
| **Qwen2.5-VL / Qwen3-VL** | `qwenvl` | 阿里云通义 |
| **MAI-UI** | `maiui` | 阿里云 |
| **GUI-Owl-7B/32B** | `guiowl` | mPLUG |

所有模型均通过 **OpenAI 兼容 API** 接入，可使用本地 vLLM / SGLang 部署，也可对接智谱 BigModel、阿里云百炼、OpenRouter 等云端服务。


## 📁 目录结构

```
ClawGUI-Agent/
├── main.py                      # CLI 命令行入口
├── webui.py                     # Gradio Web UI 入口
├── ios.py                       # iOS 专用 CLI 入口
├── setup.py                     # 包安装配置
├── requirements.txt             # Python 依赖
│
├── phone_agent/                 # 核心手机自动化包
│   ├── agent.py                 # PhoneAgent 主类（截图→VLM→动作闭环）
│   ├── agent_ios.py             # IOSPhoneAgent 类
│   ├── device_factory.py        # 设备类型工厂（ADB / HDC / XCTest）
│   ├── tracer.py                # Episode 执行追踪器
│   ├── skills_runtime.py        # ClawGUI-Skills 运行时桥接
│   ├── config/                  # 配置与提示词（8 个模板文件）
│   ├── model/                   # 模型客户端与适配器（5 个 VLM 适配器）
│   ├── adb/                     # Android ADB 设备控制
│   ├── hdc/                     # 鸿蒙 HDC 设备控制
│   ├── xctest/                  # iOS XCTest 设备控制
│   ├── actions/                 # 动作处理器（点击、滑动、输入等）
│   └── memory/                  # 个性化记忆系统（向量存储）
│
├── nanobot/                     # nanobot 子项目
│   ├── nanobot/                 # nanobot 核心包
│   │   ├── agent/               # 智能体核心 + GUI 工具
│   │   ├── channels/            # 12+ 聊天平台集成
│   │   ├── providers/           # 20+ LLM 提供商适配
│   │   └── skills/              # 可插拔技能（含 gui-mobile、clawgui-eval）
│   ├── pyproject.toml
│   └── README.md
│
├── examples/                    # 使用示例
└── scripts/                     # 部署验证脚本
```

## 📄 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可证。nanobot 子项目采用 [MIT License](nanobot/LICENSE)。
