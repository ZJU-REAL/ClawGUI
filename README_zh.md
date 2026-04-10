<div align="center">

<img src="assets/ClawGUI-Logo.png" height="140" alt="ClawGUI Logo">
<h1>ClawGUI：构建、评测与部署 GUI 智能体</h1>

[![Python 3.12](https://img.shields.io/badge/Python-3.12-blue.svg)](https://www.python.org/downloads/release/python-3120/)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg)](https://opensource.org/licenses/Apache-2.0)
[![Stars](https://img.shields.io/github/stars/ZJU-REAL/ClawGUI?style=social)](https://github.com/ZJU-REAL/ClawGUI/stargazers)
[![HuggingFace Model](https://img.shields.io/badge/🤗%20HuggingFace-ClawGUI--2B-yellow.svg)](https://huggingface.co/SugarVapeur/OpenGUI-2B)
[![ModelScope Model](https://img.shields.io/badge/🤖%20ModelScope-ClawGUI--2B-purple.svg)](https://www.modelscope.cn/models/SugarFree/OpenGUI-2B)
[![Project Page](https://img.shields.io/badge/🌐%20Project-Page-orange.svg)](https://zju-real.github.io/ClawGUI-Page/)

[English](README.md) | [中文](README_zh.md)

</div>


<div align="center">
<b>一套完整的 GUI 智能体研究框架：用 RL 训练、严格评测、真机部署。</b>
</div>

**ClawGUI-Agent 通过自然语言操控真实手机**

https://github.com/ZJU-REAL/ClawGUI-Page/releases/download/clawgui-video/clawgui-agent.mp4

**ClawGUI-RL 在线强化学习训练 GUI 智能体**

https://github.com/ZJU-REAL/ClawGUI-Page/releases/download/clawgui-video/clawgui-rl.mp4


## 新闻

+ 🔥 **[2026/4/8]** ClawGUI 正式发布——ClawGUI-RL（GiGPO）训练、ClawGUI-Eval 评测、ClawGUI-Agent 部署，三件套一次到位。基于该完整链路训练的 ClawGUI-2B 在 MobileWorld SR 上达到 **17.1**，大幅超越基线 **11.1**。查看 [快速开始](#-快速开始) 上手。

## 目录

- [概述](#-概述)
- [系统架构](#️-系统架构)
- [快速开始](#-快速开始)
  - [ClawGUI-RL — 构建](#-clawgui-rl--构建)
  - [ClawGUI-Eval — 评测](#-clawgui-eval--评测)
  - [ClawGUI-Agent — 部署](#-clawgui-agent--部署)
- [路线图](#️-路线图)
- [致谢](#-致谢)
- [许可证](#-许可证)


## 📖 概述

**ClawGUI** 是一个面向 GUI 智能体的研究框架，覆盖从**在线强化学习训练**、**标准化评测**到**真机部署**的完整生命周期。

构建一个有能力的 GUI 智能体，涉及三个紧密耦合却鲜少被同时解决的问题：需要一个在线训练环境、一套严格的评测基准来衡量模型学到了什么，以及一个能在真实设备上落地的部署系统。ClawGUI 将这三件事打通。

| 模块 | 角色 |
|------|------|
| 🚀 **[ClawGUI-RL](clawgui-rl/)** | **构建** — 在线 RL 训练 GUI 智能体：多环境并行、真机支持、GiGPO+PRM 细粒度逐步奖励 |
| 📊 **[ClawGUI-Eval](clawgui-eval/)** | **评测** — 衡量智能体学到了什么：6 个 Benchmark、11+ 模型，官方结果复现率 95.8% |
| 🤖 **[ClawGUI-Agent](clawgui-agent/)** | **部署** — 让智能体真正落地：通过 12+ 聊天平台以自然语言控制手机，内置一句话启动评测 |
| 🏆 **ClawGUI-2B** | 完整链路的验证：使用 ClawGUI-RL GiGPO 训练的 2B 智能体，MobileWorld SR 达到 **17.1**，大幅超越基线 **11.1** |


## 🏗️ 系统架构

<div align="center">
<img src="assets/clawgui-framework.png" width="85%" alt="ClawGUI 系统架构图">
</div>


## 🚀 快速开始

```bash
git clone https://github.com/ZJU-REAL/ClawGUI.git
cd ClawGUI
```

三个模块各自独立，拥有独立的环境。点击各模块查看完整安装与使用文档。


### 🚀 ClawGUI-RL — 构建

> 📁 [`clawgui-rl/`](clawgui-rl/) · 📖 [完整文档](clawgui-rl/README.md)

ClawGUI-RL 以在线强化学习训练 GUI 智能体。支持数十个 Docker 虚拟 Android 环境并行运行或直接在真机上训练，并以 GiGPO+PRM 细粒度逐步奖励取代标准 GRPO，带来更强的策略学习效果。

- **多环境并行** — 数十个 Docker 虚拟 Android 环境同时运行
- **真机训练** — 物理手机或云手机，使用相同 API
- **GiGPO + PRM** — 细粒度逐步奖励，策略优化优于标准 GRPO
- **Spare Server 轮转** — 自动故障转移，训练不中断
- **Episode 可视化** — 记录并回放任意训练轨迹

<div align="center">
<img src="clawgui-rl/assets/clawgui-rl-framework.png" width="80%" alt="ClawGUI-RL 架构图">
</div>

→ **[查看 ClawGUI-RL 完整文档](clawgui-rl/README.md)**


### 📊 ClawGUI-Eval — 评测

> 📁 [`clawgui-eval/`](clawgui-eval/) · 📖 [完整文档](clawgui-eval/README.md) · [🤗 HuggingFace](https://huggingface.co/datasets/johnzqlu/clawgui-eval) · [🤖 ModelScope](https://modelscope.cn/datasets/Matrix0602/clawgui-eval)

ClawGUI-Eval 为 GUI Grounding 研究提供可靠的测量基准。**推理 → 判断 → 指标**三阶段流水线涵盖 6 个 Benchmark、11+ 模型，对官方结果复现率达到 **95.8%**——让不同论文的数字真正具有可比性。

- **6 个 Benchmark** — ScreenSpot-Pro、ScreenSpot-V2、UIVision、MMBench-GUI、OSWorld-G、AndroidControl
- **11+ 模型** — Qwen3-VL、Qwen2.5-VL、UI-TARS、MAI-UI、GUI-G2、UI-Venus、Gemini、Seed 1.8 等
- **双后端** — 本地 GPU（transformers）或远端 API（OpenAI 兼容）
- **多 GPU & 多线程** — 并行推理，支持断点续跑
- **ClawGUI-Agent 集成** — 搭配 ClawGUI-Agent 使用，一句自然语言即可驱动完整评测流程

<div align="center">
<img src="clawgui-eval/assets/clawgui-eval-arch.png" width="80%" alt="ClawGUI-Eval 架构图">
</div>

→ **[查看 ClawGUI-Eval 完整文档](clawgui-eval/README.md)**


### 🤖 ClawGUI-Agent — 部署

> 📁 [`clawgui-agent/`](clawgui-agent/) · 📖 [完整文档](clawgui-agent/README_CN.md) · [English](clawgui-agent/README.md)

ClawGUI-Agent 打通从训练到生产的最后一环。基于 OpenClaw 构建，由 nanobot 驱动，可通过 12+ 聊天平台以自然语言控制 Android、鸿蒙或 iOS 设备，也可一句话触发完整的 ClawGUI-Eval 评测流程，无需手写脚本。

- **跨平台支持** — Android（ADB）、鸿蒙（HDC）、iOS（XCTest）
- **多模型接入** — AutoGLM、MAI-UI、GUI-Owl、Qwen-VL、UI-TARS，OpenAI 兼容 API
- **一句话评测** — 说"帮我测一下 qwen3vl 在 screenspot-pro 上的指标"，自动完成环境检测 → 多 GPU 推理 → 判分 → 指标计算 → 结果对比
- **个性化记忆** — 自动学习用户偏好，跨任务持续复用
- **Episode 记录** — 每次执行以结构化 Episode 保存，支持回放与数据集构建
- **Web UI** — Gradio 界面，支持设备管理、任务执行与记忆查看

<div align="center">
<img src="clawgui-agent/assets/clawgui-agent-logo.png" width="85%" alt="ClawGUI-Agent">
</div>

→ **[查看 ClawGUI-Agent 完整文档](clawgui-agent/README.md)**


## 🎯 路线图

- [x] **ClawGUI-Agent** — GUI 智能体框架，支持自然语言手机操控与评测
- [x] **ClawGUI-RL** — 可扩展的 Mobile Online RL 训练基础设施，支持 GiGPO + PRM
- [x] **ClawGUI-Eval** — 标准化 GUI Grounding 评测套件，6 个 Benchmark，官方复现率 95%+
- [x] **ClawGUI-2B** — 基于 GiGPO 训练的 2B GUI 智能体，MobileWorld SR 达到 17.1（基线 11.1）
- [ ] **真机部署 ClawGUI-Agent** — 将 ClawGUI-Agent 直接部署在真实手机上，避免云端泄露隐私
- [ ] **Desktop Online RL** — 将 ClawGUI-RL 扩展至桌面环境，支持桌面端在线强化学习
- [ ] **Web Online RL** — 将 ClawGUI-RL 扩展至 Web 环境，支持网页端在线强化学习
- [ ] **更多 ClawGUI-Agent 技能** — 为 ClawGUI-Agent 添加更多可插拔技能，拓展能力边界
- [ ] **CLI & GUI 混合机制** — 探索命令行与 GUI 操作相结合的混合交互模式
- [ ] **实时 RL 集成** — 基于 OPD 算法，为 ClawGUI-RL 和 ClawGUI-Agent 引入实时强化学习能力


## 🤝 参与贡献

欢迎任何形式的贡献——新模型支持、新 RL 环境、Bug 修复、文档改进。请参阅 [CONTRIBUTING.md](CONTRIBUTING.md) 了解如何开始、各模块具体指南以及 PR 要求。

<a href="https://github.com/ZJU-REAL/ClawGUI/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=ZJU-REAL/ClawGUI" />
</a>


## 🙏 致谢

ClawGUI 基于以下优秀的开源项目构建，在此衷心感谢各项目的贡献者：

- [**verl-agent**](https://github.com/langfengq/verl-agent)
- [**MAI-UI**](https://github.com/Tongyi-MAI/MAI-UI)
- [**MobileWorld**](https://github.com/Tongyi-MAI/MobileWorld)
- [**Mobile-Agent**](https://github.com/x-plug/mobileagent)
- [**nanobot**](https://github.com/HKUDS/nanobot)
- [**Open-AutoGLM**](https://github.com/zai-org/Open-AutoGLM)


## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
