<div align="center">

# ClawGUI-RL：GUI 智能体 Online RL 训练基础设施

[![Python 3.12](https://img.shields.io/badge/Python-3.12-blue.svg)](https://www.python.org/downloads/release/python-3120/)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg)](https://opensource.org/licenses/Apache-2.0)
[![HuggingFace Model](https://img.shields.io/badge/🤗%20HuggingFace-ClawGUI--2B-yellow.svg)](https://huggingface.co/SugarVapeur/OpenGUI-2B)
[![ModelScope Model](https://img.shields.io/badge/🤖%20ModelScope-ClawGUI--2B-purple.svg)](https://www.modelscope.cn/models/SugarFree/OpenGUI-2B)
[![arXiv](https://img.shields.io/badge/arXiv-paper-red.svg)](https://arxiv.org/)

[English](README.md) | [中文](README_zh.md)

</div>

---

## 📚 目录

- [概述](#-概述)
- [架构](#️-架构)
- [安装](#-安装)
- [快速开始](#-快速开始)
  - [虚拟环境 Scaling（MobileWorld）](#1-虚拟环境-scalingmobileworld)
  - [真机训练](#2-真机训练)
- [如何添加新环境](#-如何添加新环境)
- [实验结果](#-实验结果)

---

## 📖 概述

**ClawGUI-RL** 是 [ClawGUI](../README_zh.md) 的训练模块——智能体诞生的地方。它是一个专为 GUI 智能体设计的开源 Online RL 训练基础设施，设计目标是可扩展、鲁棒且易于二次开发。

✨ **核心特性：**

- 🌐 **多环境并行训练** — 支持数十个虚拟环境同时并行训练，大幅提升数据采集效率与收敛速度。
- 📱 **真机训练支持** — 支持在真实 Android 手机上进行 RL 训练，同时兼容虚拟环境，为 GUI 智能体研究提供了新的可能性。
- 🤖 **多模型支持** — 开箱即用地支持 [MAI-UI](https://github.com/sugarandgugu/MAI-UI) 和 [GUI-Owl](https://github.com/sugarandgugu/GUI-Owl) 两类 GUI-Spec 模型，并提供简洁的扩展接口，支持 Qwen3-VL 系列等通用多模态大模型。
- 🔌 **可插拔自定义 Context** — Context 构建器完全模块化，用户可自由注入历史截图、动作空间、自定义信息等，无需修改核心训练逻辑。
- 🔄 **环境重启与重试机制** — 内置周期性容器重启与可配置重试逻辑，保障长时间训练的稳定性。
- ♻️ **Spare Server 轮转机制** — 自动在多个后端 URL 之间轮转，单个服务器异常不会阻塞训练进程。
- 🏆 **GiGPO 算法** — 集成 GiGPO 算法与过程奖励模型（PRM），实现细粒度的逐步打分，相比标准 GRPO 取得更优的策略优化效果。
- 🎬 **Episode 轨迹记录与可视化** — 训练过程中的 Episode 轨迹自动保存至 `episode/` 目录，可通过 `scripts/episode_visualizer.py` 对任意 Rollout 轨迹进行回放与检查。

---

## 🏗️ 架构

<div align="center">
<img src="assets/clawgui-rl-framework.png" width="80%" alt="ClawGUI-RL 架构图">
</div>

<div align="center">
<img src="assets/reward_curve.png" width="80%" alt="ClawGUI-2B 训练奖励曲线">
</div>

---

## 🔧 安装

```bash
conda create -n opengui-rl python=3.12 -y
conda activate opengui-rl

pip3 install vllm==0.11.0

pip3 install flash-attn==2.7.4.post1 --no-build-isolation --no-cache-dir

pip install datasets

pip install -e .
```

---

## 🚀 快速开始

ClawGUI-RL 支持两种训练模式：**虚拟环境 Scaling**（基于 Docker 的 MobileWorld 模拟器）和**真机训练**（物理或云手机）。

---

### 1. 虚拟环境 Scaling

#### 第 1 步 — 克隆 OpenGUI-Server

```bash
git clone https://github.com/sugarandgugu/OpenGUI-Server.git
```

#### 第 2 步 — 安装并启动服务

按照 OpenGUI-Server 仓库中的安装文档操作，主要步骤包括：

1. **确认 KVM 虚拟化支持** — 确保您的机器支持 KVM（可运行 `kvm-ok` 或检查 `/dev/kvm`）。
2. **拉取 Docker 镜像** — 按照文档拉取 MobileWorld Android 模拟器镜像。
3. **启动 Docker 容器** — 启动一个或多个容器，每个容器会提供一个后端 API URL（如 `http://127.0.0.1:PORT`）。

#### 第 3 步 — 注册环境 URL

将容器后端 URL 逐行填入：

```
examples/env_server/mobileworld_server.txt
```

#### 第 4 步 — 下载训练数据

```bash
huggingface-cli download hiyouga/geometry3k --repo-type dataset --local-dir ~/data/geometry3k
```

#### 第 5 步 — 配置训练脚本

打开 `examples/grpo_trainer/run_mobileworld.sh`，配置参数（详见英文 README）。

#### 第 6 步 — 安装日志工具

```bash
pip install swanlab
```

#### 第 7 步 — 启动训练

```bash
bash examples/grpo_trainer/run_mobileworld.sh
```

**GiGPO 训练（推荐）：**

```bash
bash examples/gigpo_trainer/run_mobileworld.sh
```

> **重要：** `mobileworld_server.txt` 中的 URL 数量必须 **≥ `train_batch_size` × `group_size`**，强烈建议额外注册若干 Spare Server。

---

### 2. 真机训练

真机训练是业界目前难以大规模解决的问题。ClawGUI-RL 提供了经过验证的端到端真机训练流程，目前已在两台真实手机上完成验证。用户后续可以使用云手机进行训练，其交互方式与真机完全相同。

#### 基本步骤

1. 通过 USB 将 Android 手机连接到机器
2. 开启开发者模式与 USB 调试
3. 安装 ADB Keyboard（可选但推荐）
4. 启动 MobileWorld Server：`uv run mobile-world server`
5. 测试设备连接：`python test_true_device.py`
6. 将后端 URL 填入 `examples/env_server/realdevice_server.txt`
7. 将训练任务填入 `examples/env_server/realdevice_tasks.txt`
8. 启动训练：

```bash
bash examples/gigpo_trainer/run_realdevice.sh  # 推荐 GiGPO
```

---

### 3. 将检查点转换为 HuggingFace 格式

```bash
python scripts/model_merger.py merge \
    --backend fsdp \
    --local_dir gigpo_maiui2b_exp1/global_step_15/actor \
    --target_dir gigpo_maiui2b_exp1/global_step_15/hf
```

### 4. 查看 Episode 轨迹

```bash
python scripts/episode_visualizer.py --episode_dir episode/<your_episode>
```

---

## 🧩 如何添加新环境

参考以下现有实现来开发新环境：

- **MobileWorld**：`agent_system/environments/env_package/mobileworld/`
- **RealDevice**：`agent_system/environments/env_package/realdevice/`

每个环境包实现了统一的标准接口，包括：环境重置、动作执行与奖励返回、Episode 终止检测。

---

## 📈 实验结果

我们发布了 **ClawGUI-2B**，这是一个基于 ClawGUI-RL 框架使用 GiGPO 算法训练的 GUI 智能体，以 MAI-UI-2B 为基础模型。

### MobileWorld 基准测试结果

| 类别 | 模型 | MobileWorld SR（仅 GUI） |
|------|------|:------------------------:|
| *Agentic Framework* | Claude-4.5-Sonnet + UI-Ins-7B | 47.8 |
| | Gemini-3-Pro + UI-Ins-7B | 55.6 |
| | GPT-5 + UI-Ins-7B | 54.0 |
| *端到端模型* | GUI-Owl-7B | 7.7 |
| | GUI-Owl-32B | 8.5 |
| | UI-Venus-7B | 8.5 |
| | UI-Venus-72B | 16.4 |
| | Qwen3-VL-8B | 9.4 |
| | Qwen3-VL-32B | 11.9 |
| | Qwen3-VL-235B-A22B | 12.8 |
| | Doubao-1.5-UI-TARS | 26.3 |
| | MAI-UI-2B（基线） | 11.1 |
| | MAI-UI-8B | 19.7 |
| ***我们的方法*** | **ClawGUI-2B [GRPO]** | **14.5** |
| | **ClawGUI-2B [GiGPO]** | **17.1** |

---

## 📄 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
