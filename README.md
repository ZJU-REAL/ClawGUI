<div align="center">

<h1>
  <img src="assets/OpenGUI-Logo.png" height="45" alt="OpenGUI Logo" style="vertical-align:bottom; margin-right:10px; margin-top:8px;">
  OpenGUI: A Unified GUI Agent Harness
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
- [Quick Start](#-quick-start)
  - [OpenClaw-GUI — Agent Inference](#-openclaw-gui--agent-inference)
  - [OpenGUI-Eval — Evaluation](#-opengui-eval--evaluation)
  - [OpenGUI-RL — Online RL Training](#-opengui-rl--online-rl-training)
- [Acknowledgements](#-acknowledgements)

---

## 📖 Overview

**OpenGUI** is a full-stack, end-to-end agent harness system for GUI intelligence. It covers the complete lifecycle of a GUI agent — from **inference and deployment**, through **standardized evaluation**, to **online reinforcement learning training** — providing researchers and engineers with a unified, production-ready infrastructure.

| Module | Description |
|--------|-------------|
| 🤖 **[OpenClaw-GUI](openclaw-gui/)** | GUI agent inference framework — control mobile devices via natural language through Feishu, DingTalk, Telegram and 12+ chat platforms, powered by VLMs and a personalized memory system |
| 📊 **[OpenGUI-Eval](opengui-eval/)** | Standardized GUI grounding evaluation suite — 6 benchmarks, 11+ models, 95%+ faithful reproduction of official results |
| 🚀 **[OpenGUI-RL](opengui-rl/)** | Scalable online RL training infrastructure — parallel multi-environment training, real-device support, GiGPO with PRM, robust spare-server rotation |
| 🏆 **OpenGUI-2B** | State-of-the-art 2B GUI agent trained with GiGPO, achieving **17.1** MobileWorld SR |

---

## 🏗️ Architecture

<div align="center">
<img src="assets/opengui-framework.png" width="85%" alt="OpenGUI System Architecture">
</div>

---

## 🚀 Quick Start

```bash
git clone https://github.com/sugarandgugu/OpenGUI.git
cd OpenGUI
```

OpenGUI consists of three independent modules. Click into each one for full installation and usage instructions.

---

### 🤖 OpenClaw-GUI — Agent Inference

> 📁 [`openclaw-gui/`](openclaw-gui/) · 📖 [Full Documentation](openclaw-gui/README.md)

OpenClaw-GUI lets you control Android / HarmonyOS / iOS devices with natural language by sending messages through popular chat platforms (Feishu, DingTalk, Telegram, Discord, Slack, QQ, and more). Built on [OpenClaw](https://github.com/openclaw/openclaw) and [nanobot](https://github.com/HKUDS/nanobot), it supports AutoGLM, MAI-UI, GUI-Owl, Qwen-VL, and UI-TARS via OpenAI-compatible APIs. A built-in personalized memory system automatically learns your preferences and improves over time. Every task execution is recorded as a structured episode for replay and dataset building. A Gradio Web UI is also provided for interactive use.

<div align="center">
<img src="openclaw-gui/assets/openclaw-gui-logo.png" width="75%" alt="OpenClaw-GUI">
</div>

→ **[Get started with OpenClaw-GUI](openclaw-gui/README.md)**

---

### 📊 OpenGUI-Eval — Evaluation

> 📁 [`opengui-eval/`](opengui-eval/) · 📖 [Full Documentation](opengui-eval/README.md) · [🤗 Dataset](https://huggingface.co/datasets/johnzqlu/opengui-eval) · [🤖 ModelScope](https://modelscope.cn/datasets/Matrix0602/opengui-eval)

OpenGUI-Eval is a standardized evaluation framework for GUI grounding models, adopting a three-stage **Infer → Judge → Metric** pipeline. It covers 6 benchmarks (ScreenSpot-Pro, ScreenSpot-V2, UIVision, MMBench-GUI, OSWorld-G, AndroidControl) with 11+ supported models including Qwen3-VL, Qwen2.5-VL, UI-TARS, MAI-UI, GUI-G2, UI-Venus, Gemini, and Seed 1.8. Both local GPU and remote API backends are supported, with multi-GPU parallel inference and automatic resume. Reproduction rate: **95.8%**.

<div align="center">
<img src="opengui-eval/assets/opengui-eval.png" width="75%" alt="OpenGUI-Eval Architecture">
</div>

→ **[Get started with OpenGUI-Eval](opengui-eval/README.md)**

---

### 🚀 OpenGUI-RL — Online RL Training

> 📁 [`opengui-rl/`](opengui-rl/) · 📖 [Full Documentation](opengui-rl/README.md)

OpenGUI-RL is a scalable online RL infrastructure for GUI agent training. It supports parallel training across dozens of virtual Android environments (via Docker-based MobileWorld) and real-device training on physical or cloud phones. Includes GiGPO with PRM for fine-grained step-level reward, spare-server rotation for automatic failover, periodic environment restart for stability, and episode trajectory recording and visualization.

<div align="center">
<img src="opengui-rl/assets/opengui-rl-framework.png" width="75%" alt="OpenGUI-RL Architecture">
</div>

→ **[Get started with OpenGUI-RL](opengui-rl/README.md)**

---

## 🙏 Acknowledgements

OpenGUI is built upon the following excellent open-source projects. We sincerely thank their contributors:

- [**verl-agent**](https://github.com/langfengq/verl-agent) 
- [**MAI-UI**](https://github.com/Tongyi-MAI/MAI-UI) 
- [**MobileWorld**](https://github.com/Tongyi-MAI/MobileWorld) 
- [**Mobile-Agent**](https://github.com/x-plug/mobileagent) 
- [**nanobot**](https://github.com/HKUDS/nanobot) 
- [**Open-AutoGLM**](https://github.com/zai-org/Open-AutoGLM) 

---

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).
