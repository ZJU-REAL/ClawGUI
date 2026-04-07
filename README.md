<div align="center">

<img src="assets/OpenGUI-Logo.png" height="140" alt="OpenGUI Logo">
<h1>OpenGUI: A Unified GUI Agent Harness</h1>

[![Python 3.12](https://img.shields.io/badge/Python-3.12-blue.svg)](https://www.python.org/downloads/release/python-3120/)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg)](https://opensource.org/licenses/Apache-2.0)
[![Stars](https://img.shields.io/github/stars/sugarandgugu/OpenGUI?style=social)](https://github.com/sugarandgugu/OpenGUI/stargazers)
[![HuggingFace Model](https://img.shields.io/badge/🤗%20HuggingFace-OpenGUI--2B-yellow.svg)](https://huggingface.co/SugarVapeur/OpenGUI-2B)
[![ModelScope Model](https://img.shields.io/badge/🤖%20ModelScope-OpenGUI--2B-purple.svg)](https://www.modelscope.cn/models/SugarFree/OpenGUI-2B)

[English](README.md) | [中文](README_zh.md)

</div>

---

<div align="center">
<b>Your Next-Generation AI Assistant</b>
<table>
<tr>
<td align="center">
<video src="https://github.com/user-attachments/assets/6481f7ba-58f2-4f4b-9e97-e81b7b64a027" controls width="320"></video>
<br><b>OpenClaw-GUI Researches and Summarizes a Singer's Controversy</b>
</td>
<td align="center">
<video src="https://github.com/user-attachments/assets/5fbf2064-52d5-47a3-9fca-f1ecf28e08f8" controls width="320"></video>
<br><b>OpenClaw-GUI Helps Users Troubleshoot Network Issues</b>
</td>
</tr>
<tr>
<td align="center" colspan="2">
<video src="https://github.com/user-attachments/assets/277db5ce-717a-4df4-ae6b-b4697298aaaa" controls width="320"></video>
<br><b>OpenClaw-GUI Urgently Assists Users with Evaluation Tasks</b>
</td>
</tr>
</table>
</div>

---

## 📚 Table of Contents

- [Overview](#-overview)
- [Architecture](#️-architecture)
- [Quick Start](#-quick-start)
  - [OpenClaw-GUI — Agent Inference](#-openclaw-gui--agent-inference)
  - [OpenGUI-RL — Online RL Training](#-opengui-rl--online-rl-training)
  - [OpenGUI-Eval — Evaluation](#-opengui-eval--evaluation)
- [Roadmap](#️-roadmap)
- [Acknowledgements](#-acknowledgements)
- [License](#-license)

---

## 📖 Overview

**OpenGUI** is a full-stack, end-to-end agent harness system for GUI intelligence. It covers the complete lifecycle of a GUI agent — from **inference and deployment**, through **standardized evaluation**, to **online reinforcement learning training** — providing researchers and engineers with a unified, production-ready infrastructure.

| Module | Description |
|--------|-------------|
| 🤖 **[OpenClaw-GUI](openclaw-gui/)** | GUI agent framework — control mobile devices via natural language through 12+ chat platforms, and launch standardized GUI model evaluation with a single command |
| 🚀 **[OpenGUI-RL](opengui-rl/)** | Scalable online RL training infrastructure — parallel multi-environment training, real-device support, GiGPO with PRM, robust spare-server rotation |
| 📊 **[OpenGUI-Eval](opengui-eval/)** | Standardized GUI grounding evaluation suite — 6 benchmarks, 11+ models, 95%+ faithful reproduction of official results |
| 🏆 **OpenGUI-2B** | 2B GUI agent trained with OpenGUI-RL using GiGPO, achieving **17.1** MobileWorld SR — surpassing the baseline **11.1** by a large margin |

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

The OpenGUI system is organized into three tightly integrated modules. Click into each one for full installation and usage instructions.

---

### 🤖 OpenClaw-GUI — Agent Inference & Evaluation

> 📁 [`openclaw-gui/`](openclaw-gui/) · 📖 [Full Documentation](openclaw-gui/README.md) · [English](openclaw-gui/README_EN.md)

OpenClaw-GUI is a GUI agent framework built on OpenClaw, providing two core capabilities: **GUI phone control** and **GUI model evaluation**. Control mobile devices with natural language through 12+ chat platforms, or launch standardized opengui-eval benchmarks with a single command.

- 📱 **Cross-platform** — Android (ADB), HarmonyOS (HDC), iOS (XCTest)
- 🤖 **Multi-model** — AutoGLM, MAI-UI, GUI-Owl, Qwen-VL, UI-TARS via OpenAI-compatible API
- 📊 **One-command evaluation** — Built-in opengui-eval skill: say "benchmark qwen3vl on screenspot-pro" and it handles env check → multi-GPU inference → judging → metrics → result comparison
- 🧠 **Personalized memory** — Automatically learns user preferences and injects context across tasks
- 📝 **Episode recording** — Every task saved as structured episodes for replay and dataset building
- 🖥️ **Web UI** — Gradio interface for device management, task execution, and memory inspection

<div align="center">
<img src="openclaw-gui/assets/openclaw-gui-logo.png" width="75%" alt="OpenClaw-GUI">
</div>

→ **[Get started with OpenClaw-GUI](openclaw-gui/README.md)**

---

### 🚀 OpenGUI-RL — Online RL Training

> 📁 [`opengui-rl/`](opengui-rl/) · 📖 [Full Documentation](opengui-rl/README.md)

OpenGUI-RL is a scalable online RL infrastructure for GUI agent training, supporting both virtual environment scaling and real-device training.

- 🌐 **Parallel multi-environment** — Dozens of Docker-based virtual Android environments simultaneously
- 📱 **Real-device training** — Physical or cloud Android phones
- 🏆 **GiGPO + PRM** — Fine-grained step-level reward for better policy optimization than standard GRPO
- ♻️ **Spare server rotation** — Automatic failover keeps training running without interruption
- 🎬 **Episode visualization** — Record and replay any training trajectory

<div align="center">
<img src="opengui-rl/assets/opengui-rl-framework.png" width="75%" alt="OpenGUI-RL Architecture">
</div>

→ **[Get started with OpenGUI-RL](opengui-rl/README.md)**

---

### 📊 OpenGUI-Eval — Evaluation

> 📁 [`opengui-eval/`](opengui-eval/) · 📖 [Full Documentation](opengui-eval/README.md) · [🤗 Dataset](https://huggingface.co/datasets/johnzqlu/opengui-eval) · [🤖 ModelScope](https://modelscope.cn/datasets/Matrix0602/opengui-eval)

OpenGUI-Eval is a standardized GUI grounding evaluation framework with a three-stage **Infer → Judge → Metric** pipeline and a **95.8%** reproduction rate against official results.

- 📊 **6 benchmarks** — ScreenSpot-Pro, ScreenSpot-V2, UIVision, MMBench-GUI, OSWorld-G, AndroidControl
- 🤖 **11+ models** — Qwen3-VL, Qwen2.5-VL, UI-TARS, MAI-UI, GUI-G2, UI-Venus, Gemini, Seed 1.8, and more
- 🔌 **Dual backend** — Local GPU (`transformers`) or remote API (OpenAI-compatible)
- ⚡ **Multi-GPU & multi-thread** — Parallel inference with automatic resume
- 🤖 **OpenClaw-GUI integration** — Pair with OpenClaw-GUI to run the full pipeline via natural language

<div align="center">
<img src="opengui-eval/assets/opengui-eval.png" width="75%" alt="OpenGUI-Eval Architecture">
</div>

→ **[Get started with OpenGUI-Eval](opengui-eval/README.md)**

---

## 🗺️ Roadmap

- [ ] **On-device GUI-Claw** — Deploy OpenClaw-GUI directly on real phones to avoid cloud-based privacy leakage
- [ ] **Desktop Online RL** — Extend OpenGUI-RL to desktop environments for online reinforcement learning
- [ ] **Web Online RL** — Extend OpenGUI-RL to web environments for online reinforcement learning
- [ ] **More Skills for OpenClaw-GUI** — Add more pluggable skills to expand OpenClaw-GUI's capabilities
- [ ] **Hybrid CLI & GUI Mechanism** — Explore hybrid interaction combining command-line and GUI operations
- [ ] **Real-time RL for OpenGUI-RL & OpenClaw-GUI** — Integrate real-time reinforcement learning based on the OPD algorithm

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
