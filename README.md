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

## 📚 Table of Contents

- [Overview](#-overview)
- [Architecture](#️-architecture)
- [Sub-projects](#-sub-projects)
  - [OpenClaw-GUI — Agent Inference](#-openclaw-gui--agent-inference)
  - [OpenGUI-Eval — Evaluation](#-opengui-eval--evaluation)
  - [OpenGUI-RL — Online RL Training](#-opengui-rl--online-rl-training)
- [Quick Start](#-quick-start)
- [Experimental Results](#-experimental-results)
- [Acknowledgements](#-acknowledgements)

---

## 📖 Overview

**OpenGUI** is a full-stack, end-to-end agent harness system for GUI intelligence. It covers the complete lifecycle of a GUI agent — from **inference and deployment**, through **standardized evaluation**, to **online reinforcement learning training** — providing researchers and engineers with a unified, production-ready infrastructure.

✨ **What OpenGUI offers:**

- 🤖 **[OpenClaw-GUI]** A GUI agent inference framework that lets users control mobile devices via natural language through popular chat platforms (Feishu, DingTalk, Telegram, etc.), powered by VLMs and a personalized memory system.
- 📊 **[OpenGUI-Eval]** A standardized GUI grounding evaluation suite covering 6 benchmarks and 11+ models, with a faithful 95%+ reproduction rate of official results.
- 🚀 **[OpenGUI-RL]** A scalable online RL training infrastructure supporting parallel multi-environment training, real-device training, GiGPO with PRM, and robust spare-server rotation.
- 🏆 **[OpenGUI-2B]** A state-of-the-art 2B GUI agent trained with OpenGUI-RL using GiGPO, achieving **17.1** MobileWorld SR — surpassing much larger models.

---

## 🏗️ Architecture

<div align="center">
<img src="assets/opengui-framework.png" width="85%" alt="OpenGUI System Architecture">
</div>

---

## 📦 Sub-projects

### 🤖 OpenClaw-GUI — Agent Inference

> **Path:** [`openclaw-gui/`](openclaw-gui/)

OpenClaw-GUI is a GUI agent inference framework built on [OpenClaw](https://github.com/openclaw/openclaw). It integrates [nanobot](https://github.com/HKUDS/nanobot) to enable users to control mobile devices with natural language instructions via 12+ chat platforms.

**Key features:**
- 💬 **Multi-platform chat control** — Feishu, DingTalk, Telegram, Discord, Slack, QQ, and more
- 📱 **Cross-platform device support** — Android (ADB), HarmonyOS (HDC), iOS (XCTest)
- 🧠 **Personalized memory system** — Learns user preferences and injects relevant context automatically
- 🤖 **Multi-model support** — AutoGLM, MAI-UI, GUI-Owl, Qwen-VL, UI-TARS via OpenAI-compatible API
- 📝 **Episode recording** — Every task execution is saved as structured episodes for replay and dataset building
- 🖥️ **Web UI** — Gradio-based interface for device management, task execution, and memory inspection

---

### 📊 OpenGUI-Eval — Evaluation

> **Path:** [`opengui-eval/`](opengui-eval/) | [🤗 Dataset](https://huggingface.co/datasets/johnzqlu/opengui-eval) | [🤖 ModelScope](https://modelscope.cn/datasets/Matrix0602/opengui-eval)

OpenGUI-Eval is a standardized evaluation framework for GUI grounding models, adopting a three-stage **Infer → Judge → Metric** pipeline.

**Key features:**
- 📊 **6 benchmarks** — ScreenSpot-Pro, ScreenSpot-V2, UIVision, MMBench-GUI, OSWorld-G, AndroidControl
- 🤖 **11+ models** — Qwen3-VL, Qwen2.5-VL, UI-TARS, MAI-UI, GUI-G2, UI-Venus, Gemini, Seed 1.8, and more
- 🔌 **Dual backend** — Local GPU via `transformers` or remote API via OpenAI-compatible endpoints
- ⚡ **Multi-GPU & multi-thread** — Parallel inference with automatic resume
- ✅ **95.8% reproduction rate** — Faithful reproduction of officially reported numbers across all supported models

---

### 🚀 OpenGUI-RL — Online RL Training

> **Path:** [`opengui-rl/`](opengui-rl/)

OpenGUI-RL is a scalable online RL infrastructure for training GUI agents, supporting both virtual environment scaling and real-device training.

**Key features:**
- 🌐 **Parallel multi-environment training** — Dozens of virtual environments in parallel
- 📱 **Real-device training** — Physical or cloud Android phones
- 🤖 **Multi-model support** — MAI-UI, GUI-Owl, and extensible to Qwen3-VL family
- 🏆 **GiGPO + PRM** — Fine-grained step-level reward for better policy optimization
- ♻️ **Spare server rotation** — Automatic failover for robust long-running training
- 🔄 **Environment restart & retry** — Built-in stability mechanisms for production-grade runs
- 🎬 **Episode visualization** — Record and replay any training trajectory

---

## 🚀 Quick Start

### Step 1 — Clone the repository

```bash
git clone https://github.com/sugarandgugu/OpenGUI.git
cd OpenGUI
```

### Step 2 — Choose your module

OpenGUI is composed of three independent sub-projects. Install and use them based on your need:

---

#### 🤖 OpenClaw-GUI (Agent Inference)

```bash
cd openclaw-gui

# Create and activate virtual environment
uv venv .venv && source .venv/bin/activate

# Install phone_agent and nanobot
uv pip install -e .
uv pip install -e nanobot/

# Initialize configuration
nanobot onboard

# Start the agent gateway (chat platform control)
nanobot gateway

# Or launch the Web UI
python webui.py
```

> For device connection (ADB / HDC / iOS) and chat platform configuration, refer to [`openclaw-gui/README.md`](openclaw-gui/README.md).

---

#### 📊 OpenGUI-Eval (Evaluation)

```bash
cd opengui-eval

conda create -n opengui-eval python=3.12 -y
conda activate opengui-eval
pip install -r requirements.txt
pip install flash-attn==2.8.1 --no-build-isolation

# Download benchmark data
huggingface-cli download johnzqlu/opengui-eval --repo-type dataset --local-dir .

# Run inference → judge → metric
bash scripts/infer/transformers/qwen3vl_run_transformers.sh
bash scripts/judge/screenspot-pro_run_judge.sh
bash scripts/metric/run_metric_screenspot_pro.sh
```

> For full benchmark support and parameter details, refer to [`opengui-eval/README.md`](opengui-eval/README.md).

---

#### 🚀 OpenGUI-RL (Online RL Training)

```bash
cd opengui-rl

conda create -n opengui-rl python=3.12 -y
conda activate opengui-rl
pip3 install vllm==0.11.0
pip3 install flash-attn==2.7.4.post1 --no-build-isolation --no-cache-dir
pip install -e .
pip install swanlab

# Set up OpenGUI-Server (virtual environments)
# git clone https://github.com/sugarandgugu/OpenGUI-Server.git
# Fill in examples/env_server/mobileworld_server.txt with container URLs

# Download geometry3k dataset
huggingface-cli download hiyouga/geometry3k --repo-type dataset --local-dir ~/data/geometry3k

# Launch training (GRPO)
bash examples/grpo_trainer/run_mobileworld.sh

# Or GiGPO (recommended)
bash examples/gigpo_trainer/run_mobileworld.sh
```

> For real-device training, parameter details and model conversion, refer to [`opengui-rl/README.md`](opengui-rl/README.md).

---

## 📈 Experimental Results

We release **OpenGUI-2B**, trained with OpenGUI-RL using the GiGPO algorithm on top of MAI-UI-2B.

### MobileWorld Benchmark (GUI-Only SR)

| Category | Model | SR |
|----------|-------|----|
| *Agentic Framework* | Claude-4.5-Sonnet + UI-Ins-7B | 47.8 |
| | Gemini-3-Pro + UI-Ins-7B | 55.6 |
| | GPT-5 + UI-Ins-7B | 54.0 |
| *End-to-End Model* | GUI-Owl-7B | 7.7 |
| | UI-Venus-72B | 16.4 |
| | Doubao-1.5-UI-TARS | 26.3 |
| | MAI-UI-2B (baseline) | 11.1 |
| | MAI-UI-8B | 19.7 |
| ***Ours*** | **OpenGUI-2B [GRPO]** | **14.5** |
| | **OpenGUI-2B [GiGPO]** | **17.1** |

---

## 🙏 Acknowledgements

OpenGUI is built upon the following excellent open-source projects. We sincerely thank their contributors:

- [**verl-agent**](https://github.com/langfengq/verl-agent) — The underlying RL training engine
- [**MAI-UI**](https://github.com/Tongyi-MAI/MAI-UI) — GUI-Spec model and GUI action framework
- [**MobileWorld**](https://github.com/Tongyi-MAI/MobileWorld) — Android emulator environment
- [**Mobile-Agent**](https://github.com/x-plug/mobileagent) — Mobile agent research and infrastructure
- [**nanobot**](https://github.com/HKUDS/nanobot) — Personal AI assistant and multi-platform gateway
- [**Open-AutoGLM**](https://github.com/zai-org/Open-AutoGLM) — GUI agent framework for mobile automation

---

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).
