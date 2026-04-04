<div align="center">

<img src="assets/slogan.png" width="80%" alt="OpenGUI Slogan">

<h1>
  <img src="assets/OpenGUI-Logo.png" height="45" alt="OpenGUI Logo" style="vertical-align:middle; margin-right:10px;">
  OpenGUI
</h1>

[![Python 3.12](https://img.shields.io/badge/Python-3.12-blue.svg)](https://www.python.org/downloads/release/python-3120/)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg)](https://opensource.org/licenses/Apache-2.0)
[![Stars](https://img.shields.io/github/stars/sugarandgugu/OpenGUI?style=social)](https://github.com/sugarandgugu/OpenGUI/stargazers)
[![HuggingFace Model](https://img.shields.io/badge/🤗%20HuggingFace-OpenGUI--2B-yellow.svg)](https://huggingface.co/)
[![ModelScope Model](https://img.shields.io/badge/🤖%20ModelScope-OpenGUI--2B-purple.svg)](https://modelscope.cn/)

[English](README.md) | [中文](README_zh.md)

</div>

---

## 📚 目录

- [概述](#-概述)
- [系统架构](#️-系统架构)
- [子项目](#-子项目)
  - [OpenClaw-GUI — 智能体推理](#-openclaw-gui--智能体推理)
  - [OpenGUI-Eval — 评测](#-opengui-eval--评测)
  - [OpenGUI-RL — Online RL 训练](#-opengui-rl--online-rl-训练)
- [快速开始](#-快速开始)
- [实验结果](#-实验结果)
- [致谢](#-致谢)

---

## 📖 概述

**OpenGUI** 是一个面向 GUI 智能的全栈端到端 Agent Harness 系统，覆盖 GUI 智能体从**推理部署**、**标准化评测**到**在线强化学习训练**的完整生命周期，为研究者和工程师提供统一的、生产可用的基础设施。

✨ **OpenGUI 提供什么：**

- 🤖 **[OpenClaw-GUI]** GUI 智能体推理框架，通过飞书、钉钉、Telegram 等聊天平台让用户以自然语言远程控制手机，配备个性化记忆系统与 Web UI。
- 📊 **[OpenGUI-Eval]** 标准化 GUI Grounding 评测套件，覆盖 6 个 Benchmark、11+ 模型，官方结果复现率高达 95%+。
- 🚀 **[OpenGUI-RL]** 可扩展的 Online RL 训练基础设施，支持多环境并行训练、真机训练、GiGPO+PRM 以及鲁棒的 Spare Server 轮转机制。
- 🏆 **[OpenGUI-2B]** 基于 OpenGUI-RL 框架使用 GiGPO 算法训练的 2B 端到端 GUI 智能体，在 MobileWorld SR 上达到 **17.1**，超越多个更大参数量的模型。

---

## 🏗️ 系统架构

<div align="center">
<img src="assets/opengui-framework.png" width="85%" alt="OpenGUI 系统架构图">
</div>

---

## 📦 子项目

### 🤖 OpenClaw-GUI — 智能体推理

> **路径：** [`openclaw-gui/`](openclaw-gui/)

OpenClaw-GUI 是基于 [OpenClaw](https://github.com/openclaw/openclaw) 的 GUI Agent 推理框架，集成 [nanobot](https://github.com/HKUDS/nanobot) 个人 AI 助手，让用户通过 12+ 聊天平台以自然语言操控手机。

**核心特性：**
- 💬 **多平台聊天控制** — 飞书、钉钉、Telegram、Discord、Slack、QQ 等
- 📱 **跨平台设备支持** — Android（ADB）、鸿蒙（HDC）、iOS（XCTest）
- 🧠 **个性化记忆系统** — 自动学习用户偏好，下次任务智能复用
- 🤖 **多模型支持** — AutoGLM、MAI-UI、GUI-Owl、Qwen-VL、UI-TARS，通过 OpenAI 兼容 API 接入
- 📝 **Episode 记录** — 每次任务执行以结构化 Episode 保存，支持回放与数据集构建
- 🖥️ **Web UI** — Gradio 界面，支持设备管理、任务执行可视化、记忆管理等

---

### 📊 OpenGUI-Eval — 评测

> **路径：** [`opengui-eval/`](opengui-eval/) | [🤗 HuggingFace](https://huggingface.co/datasets/johnzqlu/opengui-eval) | [🤖 ModelScope](https://modelscope.cn/datasets/Matrix0602/opengui-eval)

OpenGUI-Eval 是面向 GUI Grounding 模型的标准化评测框架，采用 **推理 → 判断 → 指标** 三阶段 Pipeline。

**核心特性：**
- 📊 **6 个 Benchmark** — ScreenSpot-Pro、ScreenSpot-V2、UIVision、MMBench-GUI、OSWorld-G、AndroidControl
- 🤖 **11+ 模型** — Qwen3-VL、Qwen2.5-VL、UI-TARS、MAI-UI、GUI-G2、UI-Venus、Gemini、Seed 1.8 等
- 🔌 **双后端支持** — 本地 GPU（transformers）或远端 API（OpenAI 兼容）
- ⚡ **多 GPU & 多线程** — 并行推理，支持自动断点续跑
- ✅ **95.8% 复现率** — 对所有支持模型官方数据进行了忠实复现

---

### 🚀 OpenGUI-RL — Online RL 训练

> **路径：** [`opengui-rl/`](opengui-rl/)

OpenGUI-RL 是面向 GUI 智能体的可扩展 Online RL 训练基础设施，支持虚拟环境大规模 Scaling 与真机训练。

**核心特性：**
- 🌐 **多环境并行训练** — 数十个虚拟环境同时运行
- 📱 **真机训练支持** — 物理手机或云手机均可
- 🤖 **多模型支持** — MAI-UI、GUI-Owl，可扩展至 Qwen3-VL 系列
- 🏆 **GiGPO + PRM** — 细粒度逐步奖励，策略优化效果更优
- ♻️ **Spare Server 轮转** — 自动故障转移，训练不中断
- 🔄 **环境重启与重试** — 内置稳定性机制，生产级可靠性
- 🎬 **Episode 可视化** — 记录并回放任意训练轨迹

---

## 🚀 快速开始

### 第 1 步 — 克隆仓库

```bash
git clone https://github.com/sugarandgugu/OpenGUI.git
cd OpenGUI
```

### 第 2 步 — 选择子项目

OpenGUI 由三个独立子项目组成，按需安装使用：

---

#### 🤖 OpenClaw-GUI（智能体推理）

```bash
cd openclaw-gui

# 创建并激活虚拟环境
uv venv .venv && source .venv/bin/activate

# 安装 phone_agent 和 nanobot
uv pip install -e .
uv pip install -e nanobot/

# 初始化配置
nanobot onboard

# 启动聊天平台控制网关
nanobot gateway

# 或启动 Web UI
python webui.py
```

> 设备连接（ADB / HDC / iOS）与聊天平台配置详见 [`openclaw-gui/README.md`](openclaw-gui/README.md)。

---

#### 📊 OpenGUI-Eval（评测）

```bash
cd opengui-eval

conda create -n opengui-eval python=3.12 -y
conda activate opengui-eval
pip install -r requirements.txt
pip install flash-attn==2.8.1 --no-build-isolation

# 下载 Benchmark 数据
huggingface-cli download johnzqlu/opengui-eval --repo-type dataset --local-dir .

# 推理 → 判断 → 指标
bash scripts/infer/transformers/qwen3vl_run_transformers.sh
bash scripts/judge/screenspot-pro_run_judge.sh
bash scripts/metric/run_metric_screenspot_pro.sh
```

> 完整 Benchmark 支持与参数说明详见 [`opengui-eval/README.md`](opengui-eval/README.md)。

---

#### 🚀 OpenGUI-RL（Online RL 训练）

```bash
cd opengui-rl

conda create -n opengui-rl python=3.12 -y
conda activate opengui-rl
pip3 install vllm==0.11.0
pip3 install flash-attn==2.7.4.post1 --no-build-isolation --no-cache-dir
pip install -e .
pip install swanlab

# 启动 OpenGUI-Server（虚拟环境）
# git clone https://github.com/sugarandgugu/OpenGUI-Server.git
# 将容器 URL 填入 examples/env_server/mobileworld_server.txt

# 下载 geometry3k 数据集
huggingface-cli download hiyouga/geometry3k --repo-type dataset --local-dir ~/data/geometry3k

# 启动训练（GRPO）
bash examples/grpo_trainer/run_mobileworld.sh

# 或使用 GiGPO（推荐）
bash examples/gigpo_trainer/run_mobileworld.sh
```

> 真机训练、参数说明与模型格式转换详见 [`opengui-rl/README.md`](opengui-rl/README.md)。

---

## 📈 实验结果

我们发布了 **OpenGUI-2B**，基于 OpenGUI-RL 框架使用 GiGPO 算法在 MAI-UI-2B 基础上训练得到。

### MobileWorld 基准测试（仅 GUI 模式 SR）

| 类别 | 模型 | SR |
|------|------|----|
| *Agentic Framework* | Claude-4.5-Sonnet + UI-Ins-7B | 47.8 |
| | Gemini-3-Pro + UI-Ins-7B | 55.6 |
| | GPT-5 + UI-Ins-7B | 54.0 |
| *端到端模型* | GUI-Owl-7B | 7.7 |
| | UI-Venus-72B | 16.4 |
| | Doubao-1.5-UI-TARS | 26.3 |
| | MAI-UI-2B（基线） | 11.1 |
| | MAI-UI-8B | 19.7 |
| ***我们的方法*** | **OpenGUI-2B [GRPO]** | **14.5** |
| | **OpenGUI-2B [GiGPO]** | **17.1** |

---

## 🙏 致谢

OpenGUI 基于以下优秀的开源项目构建，在此衷心感谢各项目的贡献者：

- [**verl-agent**](https://github.com/langfengq/verl-agent) — 底层 RL 训练引擎
- [**MAI-UI**](https://github.com/Tongyi-MAI/MAI-UI) — GUI-Spec 模型与 GUI 动作框架
- [**MobileWorld**](https://github.com/Tongyi-MAI/MobileWorld) — Android 模拟器环境
- [**Mobile-Agent**](https://github.com/x-plug/mobileagent) — 移动端智能体研究基础设施
- [**nanobot**](https://github.com/HKUDS/nanobot) — 个人 AI 助手与多平台聊天网关
- [**Open-AutoGLM**](https://github.com/zai-org/Open-AutoGLM) — 移动端 GUI Agent 框架

---

## 📄 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
