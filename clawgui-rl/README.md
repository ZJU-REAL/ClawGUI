<div align="center">

# ClawGUI-RL: Scalable Online RL Infrastructure for GUI Agents

[![Python 3.12](https://img.shields.io/badge/Python-3.12-blue.svg)](https://www.python.org/downloads/release/python-3120/)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg)](https://opensource.org/licenses/Apache-2.0)
[![HuggingFace Model](https://img.shields.io/badge/🤗%20HuggingFace-ClawGUI--2B-yellow.svg)](https://huggingface.co/SugarVapeur/OpenGUI-2B)
[![ModelScope Model](https://img.shields.io/badge/🤖%20ModelScope-ClawGUI--2B-purple.svg)](https://www.modelscope.cn/models/SugarFree/OpenGUI-2B)
[![arXiv](https://img.shields.io/badge/arXiv-paper-red.svg)](https://arxiv.org/)

[English](README.md) | [中文](README_zh.md)

</div>


## 📚 Table of Contents

- [Overview](#-overview)
- [Architecture](#️-architecture)
- [GiGPO Algorithm](#-gigpo-algorithm)
- [Installation](#-installation)
- [Quick Start](#-quick-start)
  - [Virtual Environment Scaling](#1-virtual-environment-scaling)
  - [Real Device Training](#2-real-device-training)
  - [Convert Checkpoint to HuggingFace Format](#3-convert-checkpoint-to-huggingface-format)
  - [Visualize Episode Trajectories](#4-visualize-episode-trajectories)
- [How to Add a New Environment](#-how-to-add-a-new-environment)
- [Experimental Results](#-experimental-results)
- [Acknowledgements](#-acknowledgements)


## 📖 Overview

**ClawGUI-RL** is the training module of [ClawGUI](../README.md). It provides an open-source online RL infrastructure built specifically for GUI agents: parallel virtual environments or real physical devices, production-grade training stability, and GiGPO+PRM for step-level reward signals that go beyond what standard GRPO can offer.

**Key Features:**

- **GiGPO algorithm** — Replaces standard GRPO with GiGPO+PRM for fine-grained step-level scoring via anchor-state grouping, yielding stronger policy optimization on GUI tasks.
- **Parallel multi-environment training** — Runs dozens of Docker-based virtual Android environments simultaneously for fast, scalable data collection.
- **Real-device training support** — Trains directly on physical Android devices (or cloud phones) using the same API as virtual environments.
- **Multi-model support** — Out-of-the-box support for [MAI-UI](https://github.com/sugarandgugu/MAI-UI) and [GUI-Owl](https://github.com/sugarandgugu/GUI-Owl), with an extensible interface for other VLMs (e.g., Qwen3-VL family).
- **Dynamic sampling** — DAPO-style filtering discards trivial samples (all-correct or all-wrong groups) before backpropagation, keeping gradients informative throughout training.
- **Pluggable custom context** — Inject custom history, screenshots, or action spaces into the agent's observation without touching core training logic.
- **Environment restart & retry mechanisms** — Periodic container restarts and configurable retry logic keep long training runs stable without manual intervention.
- **Spare server rotation** — Automatically rotates across backend server URLs so a single unhealthy container never stalls training.
- **Episode trajectory recording & visualization** — Episodes are saved to `episode/`; use `scripts/episode_visualizer.py` to replay any rollout.


## 🏗️ Architecture

<div align="center">
<img src="assets/clawgui-rl-framework.png" width="80%" alt="ClawGUI-RL Architecture">
</div>

<div align="center">
<img src="assets/reward_curve.png" width="80%" alt="ClawGUI-2B Training Reward Curve">
</div>

ClawGUI-RL is built on [verl](https://github.com/volcengine/verl) with a **Ray single-controller + FSDP** distributed training architecture:

- **Ray single-controller** — A single Python process orchestrates rollout workers, training workers, and environment workers. All coordination is explicit and inspectable.
- **FSDP training workers** — Model parameters are sharded across GPUs for memory efficiency.
- **Hybrid vLLM engine** — vLLM handles token generation during rollout; training weights are synced back via `broadcast_from_vllm()` after each gradient step.
- **Multi-turn rollout loop** — Each episode is a multi-step conversation: the agent receives a screenshot, reasons, outputs an action (tap/swipe/type/terminate), and receives the next screenshot until termination or `max_steps`.


## 🧬 [GiGPO Algorithm](https://github.com/langfengq/verl-agent)

Standard GRPO assigns a single advantage score to an entire episode, which gives no credit signal to intermediate steps. ClawGUI-RL replaces GRPO with **GiGPO** (Group-in-Group Policy Optimization), which estimates per-step advantages without a learned critic.

GiGPO works in three stages:

**Stage 1 — Episode-level advantage.** Group all rollouts by their task prompt (same UID). Within each group, normalize episode returns using the group mean and standard deviation. This is equivalent to standard GRPO and provides a coarse signal about whether the overall episode succeeded.

**Stage 2 — Anchor-state grouping.** Within each episode group, cluster steps that reach the same intermediate state (identified by matching screenshot hash or action prefix). Steps sharing an anchor state form a sub-group — they faced identical observations and had their futures compared under identical conditions.

**Stage 3 — Step-level advantage.** Within each anchor-state sub-group, apply discounted return normalization. A step that leads to a higher discounted return than its peers in the same sub-group receives a positive advantage; a step that leads to a dead end receives a negative advantage. This gives dense per-step credit without training a separate value network.

The final advantage for each token is a blend of episode-level and step-level signals, weighted by a hyperparameter `λ`.

**PRM integration.** When `step_reward_judge=True`, a VLM-as-judge (the Process Reward Model) evaluates each intermediate step independently: given the screenshot before and after the action, it produces a score ∈ {0, 1}. This score augments the environment reward at each step, giving the optimizer a denser training signal than the sparse binary episode-success reward alone.


## 🔧 Installation

```bash
conda create -n opengui-rl python=3.12 -y
conda activate opengui-rl

pip3 install vllm==0.11.0

pip3 install flash-attn==2.7.4.post1 --no-build-isolation --no-cache-dir

pip install datasets

pip install -e .
```


## 🚀 Quick Start

ClawGUI-RL supports two training modes: **virtual environment scaling** (via Docker-based MobileWorld) and **real device training** (via physical or cloud Android phones).


### 1. Virtual Environment Scaling

#### Step 1 — Clone OpenGUI-Server

```bash
git clone https://github.com/sugarandgugu/ClawGUI-Server
```

#### Step 2 — Set up the server

Follow the installation guide in the ClawGUI-Server repository. Key steps include:

1. **Verify KVM support** — Ensure your machine supports KVM virtualization (`kvm-ok` or check `/dev/kvm`).
2. **Pull the Docker image** — Pull the MobileWorld Android emulator image as described in the OpenGUI-Server docs.
3. **Launch Docker containers** — Start one or more containers. Each running container exposes a backend URL (e.g., `http://127.0.0.1:PORT`).

After this step, each container provides an API backend URL that ClawGUI-RL will use to interact with the emulated phone.

#### Step 3 — Register environment URLs

Fill in your container backend URLs, one per line, into:

```
examples/env_server/mobileworld_server.txt
```

The number of lines equals the number of parallel environments. More URLs = more parallel environments = faster training.

**Example:**
```
http://127.0.0.1:5000
http://127.0.0.1:5001
http://127.0.0.1:5002
```

#### Step 4 — Download training data

Download the [hiyouga/geometry3k](https://huggingface.co/datasets/hiyouga/geometry3k) dataset to a local directory. This dataset is used by the data preprocessing pipeline as a curriculum data source.

```python
from datasets import load_dataset
ds = load_dataset("hiyouga/geometry3k")
ds.save_to_disk("~/data/geometry3k")
```

> If you have trouble accessing Hugging Face, use the mirror: `export HF_ENDPOINT=https://hf-mirror.com` before running.

#### Step 5 — Configure the training script

Open `examples/grpo_trainer/run_mobileworld.sh` and configure the parameters:

| Parameter | Description |
|-----------|-------------|
| `model_path` | Path to your local model weights (e.g., MAI-UI-2B or GUI-Owl-1.5-2B) |
| `model_type` | Model type: `mai_ui` or `gui_owl` |
| `n_gpus` | Number of GPUs to use for training |
| `history_length` | Number of previous steps included in the agent's context |
| `max_steps` | Maximum steps per episode |
| `total_epochs` | Number of training epochs |
| `train_data_size` | Batch size per training step |
| `group_size` | Number of rollouts per sample (GRPO group size) |
| `adv_estimator` | Advantage estimator: `grpo` or `gigpo` |
| `checkpoints_path` | Local directory to save model checkpoints |
| `data_source_dir` | Path to the downloaded geometry3k dataset directory |
| `server_file` | Path to the server URL list file (default: `../env_server/mobileworld_server.txt`) |
| `experiment_name` | Name of the experiment (used for logging) |
| `step_reward_judge` | Enable PRM-based step reward judge (`True`/`False`) |
| `step_reward_judge_base_url` | Base URL of the step reward judge API (required if enabled) |
| `step_reward_judge_model_name` | Model name for the step reward judge |
| `env_restart_enable` | Enable periodic environment container restart (`True`/`False`) |
| `env_restart_every_n_steps` | Restart containers every N training steps |
| `env_restart_wait` | Seconds to wait after issuing a restart command |

#### Step 6 — Install the logger

We use [SwanLab](https://swanlab.cn/) as the default training logger. Install it with:

```bash
pip install swanlab
```

You can replace it with other loggers (e.g., `wandb`, `tensorboard`) by modifying `trainer.logger` in the script.

#### Step 7 — Launch training

Before running, make sure the following key fields in the script are correctly set:

- **`CUDA_VISIBLE_DEVICES`** — Set to the GPU indices you want to use, e.g. `export CUDA_VISIBLE_DEVICES=4,5,6,7`
- **`data_source_dir`** — Set to the path where you downloaded geometry3k, e.g. `~/data/geometry3k`
- **`n_gpus`** — Should match the number of GPUs in `CUDA_VISIBLE_DEVICES`
- **`train_data_size`** and **`group_size`** — Adjust based on your GPU memory and number of environments. Note that the total number of environment URLs must be ≥ `train_data_size × group_size`
- **`step_reward_judge`** — For standard GRPO training, set to `False` (no PRM required)
- **`env_restart_enable`** — Can be set to `False` for initial testing; enable for long-running production runs

> **Important — Server count requirement:**
> The number of URLs in `mobileworld_server.txt` must be **≥ `train_batch_size` × `group_size`**. We strongly recommend registering **extra spare servers** beyond this minimum. Virtual containers are prone to errors (e.g., screenshot failures, task init errors, unhealthy device state). When an active server encounters an error, the system will automatically rotate to a spare server to keep training running without interruption.
>
> **Example:** If `train_batch_size=4` and `group_size=4`, you need at least 16 active servers. Registering 24 URLs gives you 8 spare servers for rotation.

```bash
bash examples/grpo_trainer/run_mobileworld.sh
```

> **Note:** We have verified GRPO and GiGPO training. We also provide scripts for GSPO and DAPO, but have not run experiments with them. Contributions and test results are welcome!

**GiGPO training** integrates a Process Reward Model (PRM) for finer-grained step-level reward signals, generally yielding better performance than GRPO. To use it:

```bash
bash examples/gigpo_trainer/run_mobileworld.sh
```


### 2. Real Device Training

Real-device training is a notoriously challenging problem in the industry. It involves app login verification, device management, and large-scale scaling concerns. ClawGUI-RL provides a validated end-to-end pipeline for physical Android devices. We have verified training on two real phones. Users can adapt this setup for cloud phones — the interaction protocol is identical.

#### Step 1 — Prepare your Android device

If you have already cloned OpenGUI-Server, connect a physical Android phone to your computer via USB.

> **Running on a remote server?** If your training runs on a remote server but you want to connect a local phone via USB, you can set up port forwarding between your local machine and the server. For detailed steps, refer to the real-device training section in the OpenGUI-Server documentation.

#### Step 2 — Enable developer mode & USB debugging

On your phone:
1. Go to **Settings → About Phone** and tap **Build Number** 10 times to unlock Developer Options.
2. In **Developer Options**, enable **USB Debugging**.

#### Step 3 — Install ADB Keyboard (optional but recommended)

ADB Keyboard allows programmatic text input via ADB, which is required for typing tasks.

```bash
adb install ADBKeyboard.apk
adb shell ime enable com.android.adbkeyboard/.AdbIME
```

#### Step 4 — Start the MobileWorld server

```bash
uv run mobile-world server
```

#### Step 5 — Test device connectivity

Use the OpenGUI-Server test script to verify that your device is recognized:

```bash
python test_true_device.py
```

Make sure the correct `device_id` is set (check with `adb devices`).

#### Step 6 — Register device backend URL

Fill the OpenGUI-Server API backend URL into:

```
examples/env_server/realdevice_server.txt
```

#### Step 7 — Configure training tasks

Write your training tasks (one task per line) into:

```
examples/env_server/realdevice_tasks.txt
```

#### Step 8 — Configure and launch training

Both **GRPO** and **GiGPO** are supported for real device training. Fill in your `device_id` and configure the scripts:

**GRPO:**
```bash
# Edit examples/grpo_trainer/run_realdevice.sh, then:
bash examples/grpo_trainer/run_realdevice.sh
```

**GiGPO (recommended):**
```bash
# Edit examples/gigpo_trainer/run_realdevice.sh, then:
bash examples/gigpo_trainer/run_realdevice.sh
```

Key parameters specific to real device training:

| Parameter | Description |
|-----------|-------------|
| `model_path` | Path to your model weights (MAI-UI-2B or GUI-Owl) |
| `model_type` | Model type: `mai_ui` or `gui_owl` |
| `device` | ADB device ID(s). Single: `DEVICE_ID`; Multiple (comma-separated): `DEVICE_ID1,DEVICE_ID2`; Auto-detect: `auto` |
| `server_file` | Path to `realdevice_server.txt` |
| `task_file` | Path to `realdevice_tasks.txt` |
| `data_source_dir` | Path to the geometry3k dataset (same as MobileWorld; leave empty to use default `~/data/geometry3k`) |
| `step_reward_judge` | Enable PRM step-level reward (`True`/`False`) |
| `step_reward_judge_base_url` | Step reward judge API URL |
| `step_reward_judge_model_name` | Step reward judge model name |
| `task_eval_judge` | Enable VLM-based task completion evaluation (`True`/`False`). When the agent outputs `answer`/`terminate`, the judge scores the result: score=1 → reward=1; score=0 → reward=0 |
| `task_eval_judge_base_url` | Task eval judge API URL |
| `task_eval_judge_model_name` | Task eval judge model name |
| `adv_estimator` | `grpo` or `gigpo` |
| `mode` | GiGPO normalization mode: `mean_norm` or `mean_std_norm` |
| `max_steps` | Maximum steps per episode (recommend 7 for real device) |
| `group_size` | Rollout group size per sample |
| `checkpoints_path` | Directory to save checkpoints |

> **Note:** Due to physical device limitations, we have not validated large-scale real-device training. We recommend cloud phones for large-scale experiments, as the interaction protocol is identical.


### 3. Convert Checkpoint to HuggingFace Format

After training, the saved checkpoint is in FSDP format. Use `scripts/model_merger.py` to convert it to a standard HuggingFace model:

```bash
python scripts/model_merger.py merge \
    --backend fsdp \
    --local_dir gigpo_maiui2b_exp1/global_step_15/actor \
    --target_dir gigpo_maiui2b_exp1/global_step_15/hf
```

### 4. Visualize Episode Trajectories

Training episodes are automatically saved to the `episode/` directory. You can replay and inspect any rollout trajectory with:

```bash
python scripts/episode_visualizer.py --episode_dir episode/<your_episode>
```


## 🧩 How to Add a New Environment

Want to scale on cloud phones, or add a completely new environment? Cloud phones follow the same protocol as real-device training — the difference is only that phones are hosted remotely.

You can implement a new environment by referencing the existing implementations:

- **MobileWorld**: `agent_system/environments/env_package/mobileworld/`
- **RealDevice**: `agent_system/environments/env_package/realdevice/`

Each environment package implements a standard interface for:
- Resetting the environment and returning the initial observation
- Executing an action and returning the next observation + reward signal
- Termination / episode end detection

This modular design also means you can train GUI-Spec models other than MAI-UI and GUI-Owl — including general VLMs from the Qwen3-VL series — by implementing the appropriate model adapter alongside the environment.


## 📈 Experimental Results

We release **ClawGUI-2B**, a GUI agent trained using the ClawGUI-RL framework with the GiGPO algorithm, based on MAI-UI-2B as the base model.

### MobileWorld Benchmark Results

| Category | Model | MobileWorld SR (GUI-Only) |
|----------|-------|:------------------------:|
| *Agentic Framework* | Claude-4.5-Sonnet + UI-Ins-7B | 47.8 |
| | Gemini-3-Pro + UI-Ins-7B | 55.6 |
| | GPT-5 + UI-Ins-7B | 54.0 |
| *End-to-End Model* | GUI-Owl-7B | 7.7 |
| | GUI-Owl-32B | 8.5 |
| | UI-Venus-7B | 8.5 |
| | UI-Venus-72B | 16.4 |
| | Qwen3-VL-8B | 9.4 |
| | Qwen3-VL-32B | 11.9 |
| | Qwen3-VL-235B-A22B | 12.8 |
| | Doubao-1.5-UI-TARS | 26.3 |
| | MAI-UI-2B (baseline) | 11.1 |
| | MAI-UI-8B | 19.7 |
| ***Ours*** | **ClawGUI-2B [GRPO]** | **14.5** |
| | **ClawGUI-2B [GiGPO]** | **17.1** |


## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).
