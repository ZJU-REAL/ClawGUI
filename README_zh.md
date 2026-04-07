<div align="center">

<img src="assets/OpenGUI-Logo.png" height="140" alt="OpenGUI Logo">
<h1>OpenGUI：统一 GUI 智能体 Harness 系统</h1>

[![Python 3.12](https://img.shields.io/badge/Python-3.12-blue.svg)](https://www.python.org/downloads/release/python-3120/)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg)](https://opensource.org/licenses/Apache-2.0)
[![Stars](https://img.shields.io/github/stars/ZJU-REAL/OpenGUI?style=social)](https://github.com/ZJU-REAL/OpenGUI/stargazers)
[![HuggingFace Model](https://img.shields.io/badge/🤗%20HuggingFace-OpenGUI--2B-yellow.svg)](https://huggingface.co/SugarVapeur/OpenGUI-2B)
[![ModelScope Model](https://img.shields.io/badge/🤖%20ModelScope-OpenGUI--2B-purple.svg)](https://www.modelscope.cn/models/SugarFree/OpenGUI-2B)

[English](README.md) | [中文](README_zh.md)

</div>

---

<div align="center">
<b>你的下一代AI助手</b>
<table>
<tr>
<td align="center">
<video src="https://github.com/user-attachments/assets/cca75b33-4786-4f73-8c3f-ac3277831111" controls width="320"></video>
<br><b>OpenClaw-GUI 搜集信息总结歌手纷争</b>
</td>
<td align="center">
<video src="https://github.com/user-attachments/assets/75a6e68d-8880-4e77-9135-a409f1de787c" controls width="320"></video>
<br><b>OpenClaw-GUI 帮助用户解决网络问题</b>
</td>
</tr>
<tr>
<td align="center">
<video src="https://github.com/user-attachments/assets/bc486af2-23de-48d0-af30-aa7dbbd078a6" controls width="320"></video>
<br><b>OpenClaw-GUI 协助用户查火车票信息并返回</b>
</td>
<td align="center">
<video src="https://github.com/user-attachments/assets/c7155c5d-cdda-4784-94ec-e791a992979e" controls width="320"></video>
<br><b>OpenClaw-GUI 帮助用户解决评测的燃眉之急</b>
</td>
</tr>
</table>
</div>

---

## 新闻

+ [2026/4/8] 我们发布了 OpenGUI，包括三个模块：OpenClaw-GUI 用于智能体推理和评测，OpenGUI-RL 用于在线 RL 训练，OpenGUI-Eval 用于标准化评测。我们还开源了 OpenGUI-2B，这是一个使用 OpenGUI-RL 和 GiGPO 训练的 2B GUI 智能体，在 MobileWorld SR 上达到了 17.1，远超基线的 11.1。查看 [快速开始](#-快速开始) 部分以了解更多！

## 目录

- [概述](#-概述)
- [系统架构](#️-系统架构)
- [快速开始](#-快速开始)
  - [OpenClaw-GUI — 智能体推理](#-openclaw-gui--智能体推理)
  - [OpenGUI-RL — Online RL 训练](#-opengui-rl--online-rl-训练)
  - [OpenGUI-Eval — 评测](#-opengui-eval--评测)
- [路线图](#️-路线图)
- [致谢](#-致谢)
- [许可证](#-许可证)

---

## 📖 概述

**OpenGUI** 是一个面向 GUI 智能的全栈端到端 Agent Harness 系统，覆盖 GUI 智能体从**推理部署**、**标准化评测**到**在线强化学习训练**的完整生命周期，为研究者和工程师提供统一的、生产可用的基础设施。

| 模块 | 说明 |
|------|------|
| 🤖 **[OpenClaw-GUI](openclaw-gui/)** | GUI 智能体框架 — 通过 12+ 聊天平台以自然语言控制手机，并支持一句话启动标准化 GUI 模型评测 |
| 🚀 **[OpenGUI-RL](opengui-rl/)** | 可扩展 Online RL 训练基础设施 — 多环境并行训练、真机支持、GiGPO+PRM、Spare Server 轮转 |
| 📊 **[OpenGUI-Eval](opengui-eval/)** | 标准化 GUI Grounding 评测套件 — 6 个 Benchmark、11+ 模型，官方结果复现率 95%+ |
| 🏆 **OpenGUI-2B** | 基于 OpenGUI-RL 使用 GiGPO 训练的 2B GUI 智能体，MobileWorld SR 达到 **17.1**，大幅超越基线 **11.1** |

---

## 🏗️ 系统架构

<div align="center">
<img src="assets/opengui-framework.png" width="85%" alt="OpenGUI 系统架构图">
</div>

---

## 🚀 快速开始

```bash
git clone https://github.com/ZJU-REAL/OpenGUI.git
cd OpenGUI
```

OpenGUI 系统由三个紧密协作的模块组成，点击各模块查看完整安装与使用文档。

---

### 🤖 OpenClaw-GUI — 智能体推理 & 评测

> 📁 [`openclaw-gui/`](openclaw-gui/) · 📖 [完整文档](openclaw-gui/README.md) · [English](openclaw-gui/README_EN.md)

OpenClaw-GUI 是基于 OpenClaw 的 GUI 智能体框架，提供两大核心能力：**GUI 手机操控**和 **GUI 模型评测**。通过 12+ 聊天平台用自然语言远程控制手机，也可以一句话启动 opengui-eval 标准化评测。

- 📱 **跨平台支持** — Android（ADB）、鸿蒙（HDC）、iOS（XCTest）
- 🤖 **多模型接入** — AutoGLM、MAI-UI、GUI-Owl、Qwen-VL、UI-TARS，OpenAI 兼容 API
- 📊 **一句话评测** — 内置 opengui-eval 技能，一句自然语言即可完成环境检测 → 多 GPU 推理 → 判分 → 指标计算 → 结果对比
- 🧠 **个性化记忆** — 自动学习用户偏好，跨任务持续复用
- 📝 **Episode 记录** — 每次执行以结构化 Episode 保存，支持回放与数据集构建
- 🖥️ **Web UI** — Gradio 界面，支持设备管理、任务执行与记忆查看

<div align="center">
<img src="openclaw-gui/assets/openclaw-gui-logo.png" width="75%" alt="OpenClaw-GUI">
</div>

→ **[查看 OpenClaw-GUI 完整文档](openclaw-gui/README.md)**

---

### 🚀 OpenGUI-RL — Online RL 训练

> 📁 [`opengui-rl/`](opengui-rl/) · 📖 [完整文档](opengui-rl/README.md)

OpenGUI-RL 是面向 GUI 智能体训练的可扩展 Online RL 基础设施，支持虚拟环境大规模 Scaling 与真机训练。

- 🌐 **多环境并行** — 数十个 Docker 虚拟 Android 环境同时运行
- 📱 **真机训练** — 物理手机或云手机均可
- 🏆 **GiGPO + PRM** — 细粒度逐步奖励，策略优化优于标准 GRPO
- ♻️ **Spare Server 轮转** — 自动故障转移，训练不中断
- 🎬 **Episode 可视化** — 记录并回放任意训练轨迹

<div align="center">
<img src="opengui-rl/assets/opengui-rl-framework.png" width="75%" alt="OpenGUI-RL 架构图">
</div>

→ **[查看 OpenGUI-RL 完整文档](opengui-rl/README.md)**

---

### 📊 OpenGUI-Eval — 评测

> 📁 [`opengui-eval/`](opengui-eval/) · 📖 [完整文档](opengui-eval/README.md) · [🤗 HuggingFace](https://huggingface.co/datasets/johnzqlu/opengui-eval) · [🤖 ModelScope](https://modelscope.cn/datasets/Matrix0602/opengui-eval)

OpenGUI-Eval 是面向 GUI Grounding 模型的标准化评测框架，采用**推理 → 判断 → 指标**三阶段 Pipeline，对官方数据复现率达到 **95.8%**。

- 📊 **6 个 Benchmark** — ScreenSpot-Pro、ScreenSpot-V2、UIVision、MMBench-GUI、OSWorld-G、AndroidControl
- 🤖 **11+ 模型** — Qwen3-VL、Qwen2.5-VL、UI-TARS、MAI-UI、GUI-G2、UI-Venus、Gemini、Seed 1.8 等
- 🔌 **双后端** — 本地 GPU（transformers）或远端 API（OpenAI 兼容）
- ⚡ **多 GPU & 多线程** — 并行推理，支持断点续跑
- 🤖 **OpenClaw-GUI 集成** — 搭配 OpenClaw-GUI 使用，一句自然语言即可驱动完整评测流程

<div align="center">
<img src="opengui-eval/assets/openclaw-eval.png" width="75%" alt="OpenGUI-Eval 架构图">
</div>

→ **[查看 OpenGUI-Eval 完整文档](opengui-eval/README.md)**

---

## 🎯 路线图

- [x] **OpenClaw-GUI** — GUI 智能体框架，支持自然语言手机操控与评测
- [x] **OpenGUI-RL** — 可扩展的 Mobile Online RL 训练基础设施，支持 GiGPO + PRM
- [x] **OpenGUI-Eval** — 标准化 GUI Grounding 评测套件，6 个 Benchmark，官方复现率 95%+
- [x] **OpenGUI-2B** — 基于 GiGPO 训练的 2B GUI 智能体，MobileWorld SR 达到 17.1（基线 11.1）
- [ ] **真机部署 GUI-Claw** — 将 OpenClaw-GUI 直接部署在真实手机上，避免云端泄露隐私
- [ ] **Desktop Online RL** — 将 OpenGUI-RL 扩展至桌面环境，支持桌面端在线强化学习
- [ ] **Web Online RL** — 将 OpenGUI-RL 扩展至 Web 环境，支持网页端在线强化学习
- [ ] **更多 OpenClaw-GUI 技能** — 为 OpenClaw-GUI 添加更多可插拔技能，拓展能力边界
- [ ] **CLI & GUI 混合机制** — 探索命令行与 GUI 操作相结合的混合交互模式
- [ ] **实时 RL 集成** — 基于 OPD 算法，为 OpenGUI-RL 和 OpenClaw-GUI 引入实时强化学习能力

---

## 致谢

OpenGUI 基于以下优秀的开源项目构建，在此衷心感谢各项目的贡献者：

- [**verl-agent**](https://github.com/langfengq/verl-agent) 
- [**MAI-UI**](https://github.com/Tongyi-MAI/MAI-UI) 
- [**MobileWorld**](https://github.com/Tongyi-MAI/MobileWorld) 
- [**Mobile-Agent**](https://github.com/x-plug/mobileagent) 
- [**nanobot**](https://github.com/HKUDS/nanobot) 
- [**Open-AutoGLM**](https://github.com/zai-org/Open-AutoGLM) 

---

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
