<div align="center">

<h1>
  <img src="assets/OpenGUI-Logo.png" height="45" alt="OpenGUI Logo" style="vertical-align:bottom; margin-right:10px; margin-top:8px;">
  OpenGUI：统一 GUI 智能体 Harness 系统
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
- [快速开始](#-快速开始)
  - [OpenClaw-GUI — 智能体推理](#-openclaw-gui--智能体推理)
  - [OpenGUI-Eval — 评测](#-opengui-eval--评测)
  - [OpenGUI-RL — Online RL 训练](#-opengui-rl--online-rl-训练)
- [致谢](#-致谢)

---

## 📖 概述

**OpenGUI** 是一个面向 GUI 智能的全栈端到端 Agent Harness 系统，覆盖 GUI 智能体从**推理部署**、**标准化评测**到**在线强化学习训练**的完整生命周期，为研究者和工程师提供统一的、生产可用的基础设施。

| 模块 | 说明 |
|------|------|
| 🤖 **[OpenClaw-GUI](openclaw-gui/)** | GUI 智能体推理框架 — 通过飞书、钉钉、Telegram 等 12+ 聊天平台以自然语言控制手机，配备个性化记忆系统 |
| 📊 **[OpenGUI-Eval](opengui-eval/)** | 标准化 GUI Grounding 评测套件 — 6 个 Benchmark、11+ 模型，官方结果复现率 95%+ |
| 🚀 **[OpenGUI-RL](opengui-rl/)** | 可扩展 Online RL 训练基础设施 — 多环境并行训练、真机支持、GiGPO+PRM、Spare Server 轮转 |
| 🏆 **OpenGUI-2B** | 基于 GiGPO 训练的 2B GUI 智能体，MobileWorld SR 达到 **17.1** |

---

## 🏗️ 系统架构

<div align="center">
<img src="assets/opengui-framework.png" width="85%" alt="OpenGUI 系统架构图">
</div>

---

## 🚀 快速开始

```bash
git clone https://github.com/sugarandgugu/OpenGUI.git
cd OpenGUI
```

OpenGUI 由三个独立模块组成，点击各模块查看完整安装与使用文档。

---

### 🤖 OpenClaw-GUI — 智能体推理

> 📁 [`openclaw-gui/`](openclaw-gui/) · 📖 [完整文档](openclaw-gui/README.md)

OpenClaw-GUI 让你通过飞书、钉钉、Telegram、Discord、Slack、QQ 等聊天平台，用自然语言远程控制 Android / 鸿蒙 / iOS 设备。基于 [OpenClaw](https://github.com/openclaw/openclaw) 与 [nanobot](https://github.com/HKUDS/nanobot) 构建，支持 AutoGLM、MAI-UI、GUI-Owl、Qwen-VL、UI-TARS 等多种模型，通过 OpenAI 兼容 API 接入。内置个性化记忆系统，自动学习用户偏好并持续改进。每次任务执行以结构化 Episode 形式记录，便于回放与数据集构建。同时提供 Gradio Web UI 进行可视化交互。

<div align="center">
<img src="openclaw-gui/assets/openclaw-gui-logo.png" width="75%" alt="OpenClaw-GUI">
</div>

→ **[查看 OpenClaw-GUI 完整文档](openclaw-gui/README.md)**

---

### 📊 OpenGUI-Eval — 评测

> 📁 [`opengui-eval/`](opengui-eval/) · 📖 [完整文档](opengui-eval/README.md) · [🤗 HuggingFace](https://huggingface.co/datasets/johnzqlu/opengui-eval) · [🤖 ModelScope](https://modelscope.cn/datasets/Matrix0602/opengui-eval)

OpenGUI-Eval 是面向 GUI Grounding 模型的标准化评测框架，采用**推理 → 判断 → 指标**三阶段 Pipeline。覆盖 6 个 Benchmark（ScreenSpot-Pro、ScreenSpot-V2、UIVision、MMBench-GUI、OSWorld-G、AndroidControl），支持 11+ 模型，包括 Qwen3-VL、Qwen2.5-VL、UI-TARS、MAI-UI、GUI-G2、UI-Venus、Gemini、Seed 1.8 等。支持本地 GPU 与远端 API 双后端，多 GPU 并行推理，支持断点续跑。对官方数据的复现率达到 **95.8%**。

<div align="center">
<img src="opengui-eval/assets/opengui-eval.png" width="75%" alt="OpenGUI-Eval 架构图">
</div>

→ **[查看 OpenGUI-Eval 完整文档](opengui-eval/README.md)**

---

### 🚀 OpenGUI-RL — Online RL 训练

> 📁 [`opengui-rl/`](opengui-rl/) · 📖 [完整文档](opengui-rl/README.md)

OpenGUI-RL 是面向 GUI 智能体训练的可扩展 Online RL 基础设施。支持在数十个 Docker 虚拟 Android 环境中并行训练（基于 MobileWorld），同时支持真实手机或云手机的真机训练。集成 GiGPO 算法与 PRM 实现细粒度逐步奖励，内置 Spare Server 轮转自动故障转移，环境周期性重启保障稳定性，Episode 轨迹记录与可视化一体化。

<div align="center">
<img src="opengui-rl/assets/opengui-rl-framework.png" width="75%" alt="OpenGUI-RL 架构图">
</div>

→ **[查看 OpenGUI-RL 完整文档](opengui-rl/README.md)**

---

## 🙏 致谢

OpenGUI 基于以下优秀的开源项目构建，在此衷心感谢各项目的贡献者：

- [**verl-agent**](https://github.com/langfengq/verl-agent) 
- [**MAI-UI**](https://github.com/Tongyi-MAI/MAI-UI) 
- [**MobileWorld**](https://github.com/Tongyi-MAI/MobileWorld) 
- [**Mobile-Agent**](https://github.com/x-plug/mobileagent) 
- [**nanobot**](https://github.com/HKUDS/nanobot) 
- [**Open-AutoGLM**](https://github.com/zai-org/Open-AutoGLM) 

---

## 📄 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
