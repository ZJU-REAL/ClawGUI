<div align="center">

# OpenGUI-RL：GUI 智能体 Online RL 训练基础设施

[![Python 3.12](https://img.shields.io/badge/Python-3.12-blue.svg)](https://www.python.org/downloads/release/python-3120/)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg)](https://opensource.org/licenses/Apache-2.0)
[![HuggingFace Model](https://img.shields.io/badge/🤗%20HuggingFace-OpenGUI--2B-yellow.svg)](https://huggingface.co/)
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
- [致谢](#-致谢)

---

## 📖 概述

**OpenGUI-RL** 是一个专为 GUI 智能体设计的开源 Online RL 训练 Harness，设计目标是可扩展、鲁棒且易于二次开发。

✨ **核心特性：**

- 🌐 **多环境并行训练** — 支持数十个虚拟环境同时并行训练，大幅提升数据采集效率与收敛速度。
- 📱 **真机训练支持** — 支持在真实 Android 手机上进行 RL 训练，同时兼容虚拟环境，为 GUI 智能体研究提供了新的可能性。
- 🤖 **多模型支持** — 开箱即用地支持 [MAI-UI](https://github.com/sugarandgugu/MAI-UI) 和 [GUI-Owl](https://github.com/sugarandgugu/GUI-Owl) 两类 GUI-Spec 模型，并提供简洁的扩展接口，支持 Qwen3-VL 系列等通用多模态大模型。
- 🔌 **可插拔自定义 Context** — Context 构建器完全模块化，用户可自由注入历史截图、动作空间、自定义信息等，无需修改核心训练逻辑。
- 🔄 **环境重启与重试机制** — 内置周期性容器重启与可配置重试逻辑，保障长时间训练的稳定性，让框架真正具备生产可用性。
- ♻️ **Spare Server 轮转机制** — 自动在多个后端 URL 之间轮转，单个服务器异常不会阻塞训练进程，训练更加鲁棒。
- 🏆 **GiGPO 算法** — 集成 GiGPO 算法与过程奖励模型（PRM），实现细粒度的逐步打分，相比标准 GRPO 取得更优的策略优化效果。
- 🎬 **Episode 轨迹记录与可视化** — 训练过程中的 Episode 轨迹自动保存至 `episode/` 目录，用户可通过 `scripts/episode_visualizer.py` 对任意 Rollout 轨迹进行回放与检查。

---

## 🏗️ 架构

<div align="center">
<img src="assets/opengui-rl-framework.png" width="80%" alt="OpenGUI-RL 架构图">
</div>

<div align="center">
<img src="assets/reward_curve.png" width="80%" alt="OpenGUI-2B 训练奖励曲线">
</div>

---

## 🔧 安装

```bash
conda create -n opengui-rl python=3.12 -y
conda activate opengui-rl

pip3 install vllm==0.11.0

pip3 install flash-attn==2.7.4.post1 --no-build-isolation --no-cache-dir

pip install -e .
```

---

## 🚀 快速开始

OpenGUI-RL 支持两种训练模式：**虚拟环境 Scaling**（基于 Docker 的 MobileWorld 模拟器）和**真机训练**（物理或云手机）。

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

完成此步骤后，每个容器提供一个 API 后端 URL，OpenGUI-RL 将通过该 URL 与模拟手机进行交互。

#### 第 3 步 — 注册环境 URL

将容器后端 URL 逐行填入：

```
examples/env_server/mobileworld_server.txt
```

URL 的数量即为并行环境数量。URL 越多，并行环境越多，训练越快。

**示例：**
```
http://127.0.0.1:5000
http://127.0.0.1:5001
http://127.0.0.1:5002
```

#### 第 4 步 — 下载训练数据

下载 [hiyouga/geometry3k](https://huggingface.co/datasets/hiyouga/geometry3k) 数据集到本地，该数据集用于课程学习的数据预处理。

```bash
huggingface-cli download hiyouga/geometry3k --repo-type dataset --local-dir ~/data/geometry3k
```

#### 第 5 步 — 配置训练脚本

打开 `examples/grpo_trainer/run_mobileworld.sh`，配置以下参数：

| 参数 | 说明 |
|------|------|
| `model_path` | 本地模型权重路径（如 MAI-UI-2B 或 GUI-Owl-1.5-2B） |
| `model_type` | 模型类型：`mai_ui` 或 `gui_owl` |
| `n_gpus` | 训练使用的 GPU 数量 |
| `history_length` | 智能体 Context 中包含的历史步数 |
| `max_steps` | 每个 Episode 的最大步数 |
| `total_epochs` | 训练总轮数 |
| `train_data_size` | 每个训练步的批大小 |
| `group_size` | 每个样本的 Rollout 数量（GRPO Group Size） |
| `adv_estimator` | 优势估计器：`grpo` 或 `gigpo` |
| `checkpoints_path` | 模型检查点保存目录 |
| `data_source_dir` | 下载的 geometry3k 数据集路径 |
| `server_file` | 服务器 URL 列表文件路径（默认：`../env_server/mobileworld_server.txt`） |
| `experiment_name` | 实验名称（用于日志记录） |
| `step_reward_judge` | 是否启用基于 PRM 的逐步奖励评判（`True`/`False`） |
| `step_reward_judge_base_url` | 逐步奖励评判 API 的 Base URL（启用时必填） |
| `step_reward_judge_model_name` | 逐步奖励评判使用的模型名称 |
| `env_restart_enable` | 是否启用周期性容器重启（`True`/`False`） |
| `env_restart_every_n_steps` | 每隔 N 个训练步重启一次容器 |
| `env_restart_wait` | 发出重启指令后等待的秒数 |

#### 第 6 步 — 安装日志工具

我们默认使用 [SwanLab](https://swanlab.cn/) 作为训练日志工具：

```bash
pip install swanlab
```

您也可以通过修改脚本中的 `trainer.logger` 替换为其他日志工具（如 `wandb`、`tensorboard`）。

#### 第 7 步 — 启动训练

运行前请确认以下关键配置项已正确填写：

- **`CUDA_VISIBLE_DEVICES`** — 设置为实际使用的 GPU 编号，如 `export CUDA_VISIBLE_DEVICES=4,5,6,7`
- **`data_source_dir`** — 填入 geometry3k 数据集的本地路径，如 `~/data/geometry3k`
- **`n_gpus`** — 与 `CUDA_VISIBLE_DEVICES` 中的 GPU 数量保持一致
- **`train_data_size`** 和 **`group_size`** — 根据显存大小和环境数量调整；注意环境 URL 总数必须 ≥ `train_data_size × group_size`
- **`step_reward_judge`** — 标准 GRPO 训练时设为 `False`，无需配置 PRM
- **`env_restart_enable`** — 初次测试时可设为 `False`；长时间生产训练建议开启

> **重要 — 服务器数量要求：**
> `mobileworld_server.txt` 中的 URL 数量必须 **≥ `train_batch_size` × `group_size`**。强烈建议额外注册若干 **Spare Server**。虚拟容器容易出现截图失败、任务初始化错误、设备不健康等问题，当活跃服务器报错时系统将自动轮转至 Spare Server，保证训练不中断。
>
> **示例：** 若 `train_batch_size=4`，`group_size=4`，则至少需要 16 个活跃服务器，注册 24 个 URL 则有 8 个作为 Spare Server 待命。

```bash
bash examples/grpo_trainer/run_mobileworld.sh
```

> **说明：** 我们已验证 GRPO 和 GiGPO 的训练，欢迎尝试其他算法。

**GiGPO 训练**集成了过程奖励模型（PRM），提供更细粒度的逐步奖励信号，通常比 GRPO 效果更好：

```bash
bash examples/gigpo_trainer/run_mobileworld.sh
```

---

### 2. 真机训练

真机训练是业界目前难以大规模解决的问题，涉及大量 App 的登录验证与设备管理。OpenGUI-RL 提供了经过验证的端到端真机训练流程，目前已在两台真实手机上完成验证。用户后续可以使用云手机进行训练，其交互方式与真机完全相同。

#### 第 1 步 — 准备 Android 设备

克隆 OpenGUI-Server 后，通过 USB 数据线将 Android 手机连接到电脑。

> **在远程服务器上训练？** 如果训练在远程服务器上运行，而手机连接在本机，可以通过端口转发的方式进行连接，详细步骤请参考 OpenGUI-Server 文档中的真机训练部分。

#### 第 2 步 — 开启开发者模式与 USB 调试

在手机上操作：
1. 进入 **设置 → 关于手机**，连续点击**版本号** 10 次，解锁开发者选项。
2. 在**开发者选项**中，开启 **USB 调试**。

#### 第 3 步 — 安装 ADB Keyboard（可选但推荐）

ADB Keyboard 允许通过 ADB 进行程序化文本输入，文字类任务必须使用。

```bash
adb install ADBKeyboard.apk
adb shell ime enable com.android.adbkeyboard/.AdbIME
```

#### 第 4 步 — 启动 MobileWorld Server

```bash
uv run mobile-world server
```

#### 第 5 步 — 测试设备连接

使用 OpenGUI-Server 提供的测试脚本验证设备是否正常连接：

```bash
python test_true_device.py
```

请确认 `device_id` 正确（可通过 `adb devices` 查看）。

#### 第 6 步 — 注册设备后端 URL

将 OpenGUI-Server 提供的 API 后端地址填入：

```
examples/env_server/realdevice_server.txt
```

#### 第 7 步 — 配置训练任务

将训练任务逐行写入（每行一个任务描述）：

```
examples/env_server/realdevice_tasks.txt
```

#### 第 8 步 — 配置并启动训练

真机训练同时支持 **GRPO** 和 **GiGPO**，请填入您的 `device_id` 后运行对应脚本：

**GRPO：**
```bash
# 编辑 examples/grpo_trainer/run_realdevice.sh，然后运行：
bash examples/grpo_trainer/run_realdevice.sh
```

**GiGPO（推荐）：**
```bash
# 编辑 examples/gigpo_trainer/run_realdevice.sh，然后运行：
bash examples/gigpo_trainer/run_realdevice.sh
```

真机训练的关键参数说明：

| 参数 | 说明 |
|------|------|
| `model_path` | 模型权重路径（MAI-UI-2B 或 GUI-Owl） |
| `model_type` | 模型类型：`mai_ui` 或 `gui_owl` |
| `device` | ADB 设备 ID。单设备：`DEVICE_ID`；多设备（逗号分隔）：`DEVICE_ID1,DEVICE_ID2`；自动检测：`auto` |
| `server_file` | `realdevice_server.txt` 路径 |
| `task_file` | `realdevice_tasks.txt` 路径 |
| `data_source_dir` | geometry3k 数据集路径（与 MobileWorld 一致；留空则使用默认路径 `~/data/geometry3k`） |
| `step_reward_judge` | 是否启用 PRM 逐步奖励（`True`/`False`） |
| `step_reward_judge_base_url` | 逐步奖励评判 API URL |
| `step_reward_judge_model_name` | 逐步奖励评判模型名称 |
| `task_eval_judge` | 是否启用基于 VLM 的任务完成度评判（`True`/`False`）。智能体输出 `answer`/`terminate` 时触发：score=1 → reward=1；score=0 → reward=0 |
| `task_eval_judge_base_url` | 任务评判 API URL |
| `task_eval_judge_model_name` | 任务评判模型名称 |
| `adv_estimator` | 优势估计器：`grpo` 或 `gigpo` |
| `mode` | GiGPO 归一化模式：`mean_norm` 或 `mean_std_norm` |
| `max_steps` | 每个 Episode 最大步数（真机建议设为 7） |
| `group_size` | 每个样本的 Rollout Group 大小 |
| `checkpoints_path` | 检查点保存目录 |

> **注意：** 由于物理设备的限制，我们尚未对真机大规模训练进行充分验证。对于大规模实验，建议使用云手机，其交互协议与真机完全一致。

---

### 3. 将检查点转换为 HuggingFace 格式

训练完成后，保存的检查点为 FSDP 格式，需使用 `scripts/model_merger.py` 转换为标准 HuggingFace 模型格式：

```bash
python scripts/model_merger.py merge \
    --backend fsdp \
    --local_dir gigpo_maiui2b_exp1/global_step_15/actor \
    --target_dir gigpo_maiui2b_exp1/global_step_15/hf
```

### 4. 查看 Episode 轨迹

训练过程中的 Episode 轨迹自动保存至 `episode/` 目录，可通过以下命令回放和检查任意 Rollout 轨迹：

```bash
python scripts/episode_visualizer.py --episode_dir episode/<your_episode>
```

---

## 🧩 如何添加新环境

想要在云手机上大规模训练，或添加全新的环境？云手机的训练逻辑与真机完全相同，区别仅在于手机托管在云端。

您可以参考以下现有实现来开发新环境：

- **MobileWorld**：`agent_system/environments/env_package/mobileworld/`
- **RealDevice**：`agent_system/environments/env_package/realdevice/`

每个环境包实现了统一的标准接口，包括：
- 重置环境并返回初始观测
- 执行动作并返回下一步观测与奖励信号
- 检测 Episode 终止条件

该模块化设计同样支持训练 MAI-UI 和 GUI-Owl 之外的其他模型，包括 Qwen3-VL 系列等通用多模态大模型，只需同步实现对应的模型适配器即可。

---

## 📈 实验结果

我们发布了 **OpenGUI-2B**，这是一个基于 OpenGUI-RL 框架使用 GiGPO 算法训练的 GUI 智能体，以 MAI-UI-2B 为基础模型。

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
| ***我们的方法*** | **OpenGUI-2B [GRPO]** | **14.5** |
| | **OpenGUI-2B [GiGPO]** | **17.1** |

---

## 📄 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。
