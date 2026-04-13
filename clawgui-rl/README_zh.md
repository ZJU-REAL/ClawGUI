<div align="center">

# ClawGUI-RL：GUI 智能体 Online RL 训练基础设施

[![Python 3.12](https://img.shields.io/badge/Python-3.12-blue.svg)](https://www.python.org/downloads/release/python-3120/)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg)](https://opensource.org/licenses/Apache-2.0)
[![HuggingFace Model](https://img.shields.io/badge/🤗%20HuggingFace-ClawGUI--2B-yellow.svg)](https://huggingface.co/SugarVapeur/OpenGUI-2B)
[![ModelScope Model](https://img.shields.io/badge/🤖%20ModelScope-ClawGUI--2B-purple.svg)](https://www.modelscope.cn/models/SugarFree/OpenGUI-2B)
[![arXiv](https://img.shields.io/badge/arXiv-paper-red.svg)](https://arxiv.org/)

[English](README.md) | [中文](README_zh.md)

</div>


## 📚 目录

- [概述](#-概述)
- [架构](#️-架构)
- [GiGPO 算法](#-gigpo-算法)
- [安装](#-安装)
- [快速开始](#-快速开始)
  - [虚拟环境 Scaling（MobileWorld）](#1-虚拟环境-scalingmobileworld)
  - [真机训练](#2-真机训练)
  - [将检查点转换为 HuggingFace 格式](#3-将检查点转换为-huggingface-格式)
  - [查看 Episode 轨迹](#4-查看-episode-轨迹)
- [如何添加新环境](#-如何添加新环境)
- [实验结果](#-实验结果)


## 📖 概述

**ClawGUI-RL** 是 [ClawGUI](../README_zh.md) 的训练模块——智能体诞生的地方。它是一个专为 GUI 智能体设计的开源 Online RL 训练基础设施，支持并行虚拟环境和真实物理设备，并以 GiGPO+PRM 细粒度逐步奖励取代标准 GRPO，带来更强的策略优化效果。

**核心特性：**

- **GiGPO 算法** — 通过锚定状态分组（anchor-state grouping）实现细粒度的逐步奖励，相比标准 GRPO 取得更优的策略优化效果。
- **多环境并行训练** — 支持数十个虚拟环境同时并行训练，大幅提升数据采集效率与收敛速度。
- **真机训练支持** — 支持在真实 Android 手机上进行 RL 训练，同时兼容虚拟环境，为 GUI 智能体研究提供了新的可能性。
- **多模型支持** — 开箱即用地支持 [MAI-UI](https://github.com/sugarandgugu/MAI-UI) 和 [GUI-Owl](https://github.com/sugarandgugu/GUI-Owl) 两类 GUI-Spec 模型，并提供简洁的扩展接口，支持 Qwen3-VL 系列等通用多模态大模型。
- **动态采样** — DAPO 风格过滤机制，在反向传播前丢弃无信息样本（全正确或全错误的组），保证梯度的有效性。
- **可插拔自定义 Context** — Context 构建器完全模块化，用户可自由注入历史截图、动作空间、自定义信息等，无需修改核心训练逻辑。
- **环境重启与重试机制** — 内置周期性容器重启与可配置重试逻辑，保障长时间训练的稳定性。
- **Spare Server 轮转机制** — 自动在多个后端 URL 之间轮转，单个服务器异常不会阻塞训练进程。
- **Episode 轨迹记录与可视化** — 训练过程中的 Episode 轨迹自动保存至 `episode/` 目录，可通过 `scripts/episode_visualizer.py` 对任意 Rollout 轨迹进行回放与检查。


## 🏗️ 架构

<div align="center">
<img src="assets/clawgui-rl-framework.png" width="80%" alt="ClawGUI-RL 架构图">
</div>

<div align="center">
<img src="assets/reward_curve.png" width="80%" alt="ClawGUI-2B 训练奖励曲线">
</div>

ClawGUI-RL 基于 [verl](https://github.com/volcengine/verl)，采用 **Ray 单控制器 + FSDP** 分布式训练架构：

- **Ray 单控制器** — 单个 Python 进程统一协调 Rollout Worker、训练 Worker 和环境 Worker，全部协调逻辑显式可见。
- **FSDP 训练 Worker** — 模型参数跨 GPU 分片，显存占用更低。
- **vLLM 混合引擎** — Rollout 阶段由 vLLM 负责 Token 生成；每次梯度更新后，训练权重通过 `broadcast_from_vllm()` 同步回来。
- **多轮 Rollout 循环** — 每个 Episode 是一段多步对话：智能体接收截图、推理、输出动作（点击/滑动/输入/终止），循环直至终止或达到 `max_steps`。


## 🧬 [GiGPO 算法](https://github.com/langfengq/verl-agent)

标准 GRPO 为整个 Episode 分配一个单一的优势分数，无法对中间步骤提供信用分配信号。ClawGUI-RL 以 **GiGPO**（Group-in-Group Policy Optimization）取代 GRPO，在无需学习 Critic 的情况下估计逐步优势。

GiGPO 分三个阶段运作：

**第一阶段 — Episode 级优势。** 按任务 Prompt（相同 UID）将所有 Rollout 分组。在每个组内，使用组均值和标准差对 Episode Return 进行归一化。这等价于标准 GRPO，提供粗粒度的 Episode 成功信号。

**第二阶段 — 锚定状态分组。** 在每个 Episode 组内，对到达相同中间状态的步骤进行聚类（通过截图哈希或动作前缀匹配识别）。共享同一锚定状态的步骤构成一个子组——它们面对完全相同的观测，其未来在相同条件下被比较。

**第三阶段 — 步骤级优势。** 在每个锚定状态子组内，应用折扣回报归一化。相比同组其他步骤能带来更高折扣回报的步骤获得正优势；走向死路的步骤获得负优势。这在无需训练独立价值网络的情况下提供了密集的逐步信用。

每个 Token 的最终优势是 Episode 级与步骤级信号的加权融合，权重由超参数 `λ` 控制。

**PRM 集成。** 当 `step_reward_judge=True` 时，VLM 判官（过程奖励模型）会独立评估每个中间步骤：给定动作前后的截图，输出一个 {0, 1} 分数。该分数在每一步补充环境奖励，为优化器提供比稀疏二元 Episode 成功奖励更密集的训练信号。


## 🔧 安装

```bash
conda create -n opengui-rl python=3.12 -y
conda activate opengui-rl

pip3 install vllm==0.11.0

pip3 install flash-attn==2.7.4.post1 --no-build-isolation --no-cache-dir

pip install datasets

pip install -e .
```


## 🚀 快速开始

ClawGUI-RL 支持两种训练模式：**虚拟环境 Scaling**（基于 Docker 的 MobileWorld 模拟器）和**真机训练**（物理或云手机）。


### 1. 虚拟环境 Scaling（MobileWorld）

#### 第 1 步 — 克隆 ClawGUI-Server

```bash
git clone https://github.com/sugarandgugu/ClawGUI-Server
```

#### 第 2 步 — 安装并启动服务

按照 ClawGUI-Server 仓库中的安装文档操作，主要步骤包括：

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

> **注意：** 我们已验证 GRPO 和 GiGPO 训练。此外还提供了 GSPO 与 DAPO 的训练脚本，但尚未进行相关实验。欢迎您的测试与反馈！

> **重要：** `mobileworld_server.txt` 中的 URL 数量必须 **≥ `train_batch_size` × `group_size`**，强烈建议额外注册若干 Spare Server。容器存在截图失败、任务初始化异常等问题，系统会自动轮转到备用服务器，确保训练不中断。


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


## 🧩 如何添加新环境

参考以下现有实现来开发新环境：

- **MobileWorld**：`agent_system/environments/env_package/mobileworld/`
- **RealDevice**：`agent_system/environments/env_package/realdevice/`

每个环境包实现了统一的标准接口，包括：环境重置、动作执行与奖励返回、Episode 终止检测。

这种模块化设计同样意味着可以训练 MAI-UI 和 GUI-Owl 之外的其他模型——包括 Qwen3-VL 系列等通用多模态大模型——只需为目标模型实现对应的适配器即可。


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


## 📄 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
