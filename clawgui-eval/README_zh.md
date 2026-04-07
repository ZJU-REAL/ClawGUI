<div align="center">

<img src="assets/logo_crop.png" width="350" alt="ClawGUI-Eval Logo">

# ClawGUI-Eval：标准化 GUI Grounding 评测框架

[![Python 3.12](https://img.shields.io/badge/Python-3.12-blue.svg)](https://www.python.org/downloads/release/python-3120/)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg)](https://opensource.org/licenses/Apache-2.0)
[![HuggingFace Dataset](https://img.shields.io/badge/🤗%20HuggingFace-clawgui--eval-yellow.svg)](https://huggingface.co/datasets/johnzqlu/opengui-eval)
[![ModelScope Dataset](https://img.shields.io/badge/🤖%20ModelScope-clawgui--eval-purple.svg)](https://modelscope.cn/datasets/Matrix0602/opengui-eval)

[English](README.md) | [中文](README_zh.md)

</div>

---

## 📚 目录

- [概述](#-概述)
- [框架总览](#️-框架总览)
- [安装](#-安装)
- [下载数据](#-下载数据)
- [项目结构](#-项目结构)
- [支持的 Benchmark 与模型](#-支持的-benchmark-与模型)
- [复现经验](#-复现经验)
- [快速开始](#-快速开始)
- [脚本参数说明](#️-脚本参数说明)
- [添加新模型](#-添加新模型)
- [数据格式](#-数据格式)
- [复现结果](#-复现结果)
- [路线图](#️-路线图)
- [License](#-license)

---

## 📖 概述

**ClawGUI-Eval** 是 [ClawGUI](../README_zh.md) 的评测模块——衡量智能体学到了什么的地方。它提供标准化的 GUI Grounding 模型评测框架，采用 **Infer → Judge → Metric** 三阶段流水线，评估模型根据自然语言指令定位 UI 元素的准确性。

✨ **核心特性：**
- 🔌 **双后端支持** — 本地 GPU 推理（`transformers`）或远程 API 调用（OpenAI 兼容接口）
- 📊 **6 大 Benchmark** — ScreenSpot-Pro、ScreenSpot-V2、UIVision、MMBench-GUI、OSWorld-G、AndroidControl
- 🤖 **11+ 模型** — Qwen3-VL、Qwen2.5-VL、UI-TARS、MAI-UI、GUI-G2、UI-Venus、Gemini、Seed 1.8 等
- ⚡ **多 GPU & 多线程** — 并行推理，支持断点续推
- 🧩 **易于扩展** — 继承基类即可添加新模型
- ✅ **忠实复现** — 提供详细的官方 vs. 复现结果对比（[查看详情](#-复现结果)）
- 🌐 **前沿模型评测** — 成功复现 Gemini 3.0 Pro 和 Seed 1.8 在 ScreenSpot-Pro 上的官方结果，并新增 Gemini 3.1 Pro 评测
- 🤖 **ClawGUI-Agent 集成** — 搭配 [ClawGUI-Agent](../clawgui-agent) 使用，一句自然语言即可启动完整评测流程（环境检测 → 推理 → 判分 → 指标）。详见 [ClawGUI-Agent README](../clawgui-agent/README_CN.md#-clawgui-eval-评测)

---

## 🏗️ 框架总览

<div align="center">
<img src="assets/clawgui-eval-arch-zh.png" width="90%" alt="ClawGUI-Eval Architecture">
</div>

<div align="center">
<img src="assets/reproduction_by_benchmark.png" width="90%" alt="Reproduction Results by Benchmark">
</div>

---

## 🔧 安装

```bash
cd ClawGUI/clawgui-eval
```

```bash
conda create -n opengui python=3.12 -y
conda activate opengui
pip install -r requirements.txt
# 建议安装 flash-attn 提升推理性能
pip install flash-attn==2.8.1 --no-build-isolation
# 可选：vLLM 支持
pip install vllm==0.11.0
```

> 💡 **提示：** 如果从源码编译 `flash-attn` 太慢，可以从 [flash-attn releases 页面](https://github.com/Dao-AILab/flash-attention/releases) 下载预编译的 wheel 包直接安装。

---

## 📥 下载数据

评测所需的图片和数据文件托管在 **Hugging Face** 和 **ModelScope** 上，运行评测前请先下载。

**从 Hugging Face 下载：**

```bash
pip install -U huggingface_hub

# 如果 HF 访问困难，请使用镜像：
# export HF_ENDPOINT=https://hf-mirror.com

huggingface-cli download johnzqlu/opengui-eval --repo-type dataset --local-dir .
```

**从 ModelScope 下载：**

```bash
pip install -U modelscope

modelscope download --dataset Matrix0602/opengui-eval --local_dir .
```

下载后在 `clawgui-eval/` 目录下解压压缩包：

```bash
cd clawgui-eval
unzip image.zip
unzip data.zip
unzip output.zip
```

> ⚠️ **注意：** 所有 zip 文件（`image.zip`、`data.zip`、`output.zip`）都必须在 `clawgui-eval/` 目录下解压，以确保相对路径能正确解析。

| 文件 | 内容 |
|------|------|
| `image.zip` | Benchmark 评测图片（`image/` 目录） |
| `data.zip` | Benchmark 数据 & prompt 文件（`data/` 目录） |
| `output.zip` | 预计算的推理 & 评判结果（`output/` 目录） |

---

## 📁 项目结构

```
clawgui-eval/
├── 📄 main.py                          # 推理主入口
├── 📂 inference/                        # 模型推理器
│   ├── base_inferencer.py               # 抽象基类
│   ├── qwen3vl_inferencer.py            # Qwen3-VL
│   ├── qwen25vl_inferencer.py           # Qwen2.5-VL
│   ├── maiui_inferencer.py              # MAI-UI
│   ├── stepgui_inferencer.py            # StepGUI
│   ├── guiowl15_inferencer.py           # GUI-Owl 1.5
│   ├── guig2_inferencer.py              # GUI-G2
│   ├── uitars_inferencer.py             # UI-TARS（继承 Qwen2.5-VL）
│   ├── uivenus15_inferencer.py          # UI-Venus 1.5（继承 Qwen3-VL）
│   ├── uivenus_inferencer.py            # UI-Venus（继承 GUI-G2）
│   ├── gemini_inferencer.py             # Gemini（API，可选 Zoom）
│   └── seed_inferencer.py               # Seed 1.8（API，可选 Zoom）
├── 📂 judge/                            # 评判模块
│   ├── base_judge.py                    # 抽象基类
│   ├── grounding_judge.py               # 点击坐标评判器（大部分 benchmark）
│   ├── osworld_g_judge.py               # OSWorld-G 评判器（bbox/polygon/refusal）
│   └── androidcontrol_judge.py          # AndroidControl 评判器（多动作）
├── 📂 metric/                           # 指标计算
│   ├── base_metric.py
│   ├── screenspotpro_metric.py
│   ├── screenspotv2_metric.py
│   ├── mmbenchgui_metric.py
│   ├── osworldg_metric.py
│   ├── uivision_metric.py
│   └── androidcontrol_metric.py
├── 📂 data/                             # 数据 & prompt 注入
│   ├── convert_any_models.py            # 模型 prompt 注入脚本
│   └── *.json                           # 基础数据 & 模型专用数据
├── 📂 scripts/
│   ├── infer/
│   │   ├── transformers/                # 本地 GPU 推理脚本
│   │   ├── api/                         # API 推理脚本
│   │   └── vllm_depoly/                 # vLLM 服务部署
│   ├── judge/                           # 评判脚本（每个 benchmark 一个）
│   └── metric/                          # 指标计算脚本
├── 📂 image/                            # Benchmark 图片（需下载）
└── 📂 output/                           # 推理 & 评判输出
```

---

## 📊 支持的 Benchmark 与模型

### Benchmark

| Benchmark | ScreenSpot-Pro | ScreenSpot-V2 | UIVision | MMBench-GUI | OSWorld-G | AndroidControl |
|:---------:|:--------------:|:-------------:|:--------:|:-----------:|:---------:|:--------------:|
| 状态       | ✅              | ✅             | ✅        | ✅           | ✅         | ✅              |

### 开源模型

| 模型 Key | 模型名称 | 架构 | 坐标系统 | 输入顺序 | System Prompt | ScreenSpot-Pro | ScreenSpot-V2 | UIVision | MMBench-GUI | OSWorld-G | AndroidControl |
|---------|---------|-----|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| `qwen3vl` | Qwen3-VL | 独立实现 | `[0, 1000]` | `vt` | ✅ 需要 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `qwen25vl` | Qwen2.5-VL | 独立实现 | 绝对坐标 | `vt` | ✅ 需要 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `maiui` | MAI-UI | 独立实现 | `[0, 1000]` | `tv` | ✅ 需要 | ✅ | ✅ | ✅ | ✅ | ✅ | - |
| `stepgui` | StepGUI (GELab-Zero) | 独立实现 | `[0, 999]` | `vt` | ❌ 无 | ✅ | ✅ | ✅ | ✅ | ✅ | - |
| `guiowl15` | GUI-Owl 1.5 | 独立实现 | `[0, 1000]` | `vt` | ✅ 需要 | ✅ | ✅ | ✅ | ✅ | ✅ | - |
| `uitars` | UI-TARS 1.5 | 继承 Qwen2.5-VL | 绝对坐标 (smart_resize) | `vt` | ❌ 无 | ✅ | ✅ | ✅ | ✅ | ✅ | - |
| `guig2` | GUI-G2 | 继承 Qwen2.5-VL | `[0, 1000]` | `vt` | ❌ 无 | ✅ | ✅ | ✅ | ✅ | ✅ | - |
| `uivenus15` | UI-Venus 1.5 | 继承 Qwen3-VL | `[0, 1000]` | `vt` | ❌ 无 | ✅ | ✅ | ✅ | ✅ | ✅ | - |
| `uivenus` | UI-Venus | 继承 GUI-G2 | `[0, 1000]` | `vt` | ❌ 无 | ✅ | ✅ | ✅ | ✅ | ✅ | - |
| `gemini` | Gemini 3.x Pro | API（可选 Zoom） | `[0, 1000]` | `tv` | ✅ 内置 | ✅ | - | - | - | - | - |
| `seed` | Seed 1.8 | API（可选 Zoom） | `[0, 1000]` | `tv` | ✅ 内置 | ✅ | - | - | - | - | - |

### 前沿 / 闭源模型

我们还使用 **Zoom 范式**（裁剪后定位）在 ScreenSpot-Pro 上复现了前沿闭源模型的 GUI Grounding 结果。详见 [MAI-UI blog: A Practical Guide to GUI Grounding for Frontier Models](https://galvanized-jump-79a.notion.site/Why-your-AI-Agent-keeps-misclicking-A-Practical-Guide-to-GUI-Grounding-for-Frontier-Models-32630d140ad8808e895de98994dddb93)。

| 模型 | 坐标系统 | Zoom 范式 | SS-Pro 官方 | SS-Pro 复现 |
|------|:-:|:-:|:-:|:-:|
| Gemini 3.1 Pro | `[0, 1000]` | ✅ | 无（N/A） | 85.01 |
| Gemini 3.0 Pro | `[0, 1000]` | ✅ | 72.70 | **75.08** ✅ |
| Seed 1.8 | `[0, 1000]` | ✅ | 73.10 | **72.80** ✅ |

---

## 💡 复现经验

<details>
<summary><b>点击展开 9 条关键复现经验</b></summary>
<br>

#### 1. 🔀 Message 格式 (`tv_or_vt`)

不同模型对**图片和文本在输入消息中的顺序非常敏感**。框架提供 `TV_OR_VT` 参数控制：
- `vt` = 图片在前，文本在后（大部分模型的默认值）
- `tv` = 文本在前，图片在后（MAI-UI 需要此顺序）

#### 2. 🌡️ 温度 (Temperature)

Grounding 任务建议**始终设置 `TEMPERATURE=0.0`**（贪心解码）。

#### 3. 📝 Prompt 对齐

大多数 GUI Grounding 模型**对 prompt 格式高度敏感**，请确保与官方 prompt 模板严格对齐。

#### 4. 🖼️ 图片分辨率 (`MIN_PIXELS` / `MAX_PIXELS`)

模型对**图片分辨率范围敏感**，务必与官方值保持一致。

#### 5. 📊 采样参数 (`TOP_P` / `TOP_K`)

这些参数对 Grounding 结果**影响极小**，通常只有 ±0.1% 的波动。

#### 6. 📐 坐标系统

- **Qwen2.5-VL 系列** (qwen25vl, uitars) → 输出**绝对像素坐标**
- **Qwen3-VL 系列** (qwen3vl, guiowl15, uivenus15, maiui) → 输出 **[0, 1000] 归一化**坐标
- **GUI-G2 系列** (guig2, uivenus) → 输出 **[0, 1000] 归一化**边界框
- **StepGUI** → 输出 **[0, 999] 归一化**坐标

> 🔑 坐标解析格式不匹配是导致准确率为零的头号原因。

#### 7. 💬 System Prompt

- `qwen3vl`、`qwen25vl`、`guiowl15`、`maiui` → **需要**特定的 tool-call system prompt
- `uitars`、`guig2`、`uivenus`、`uivenus15`、`stepgui` → 将 prompt 注入到用户问题中

#### 8. 🪄 默认 System Prompt 的增益

仅仅加一句 `"You are a helpful assistant."` 就能在某些模型上**提升约 1% 的准确率**。

#### 9. 📱 AndroidControl：Scroll 方向约定

AndroidControl 的滚动方向以**屏幕为参考系**，部分模型输出的是**手指方向**，使用时务必确认并做相应转换。

</details>

---

## 🚀 快速开始

### 第 1 步：推理 (Infer)

#### 🖥️ Transformers 后端（本地 GPU）

```bash
bash scripts/infer/transformers/qwen3vl_run_transformers.sh
```

#### 🌐 API 后端（远程服务）

```bash
# 1. 先部署 vLLM 服务
bash scripts/infer/vllm_depoly/vllm_serve.sh

# 2. 运行推理
bash scripts/infer/api/qwen3vl_run_api.sh
```

### 第 2 步：评判 (Judge)

```bash
bash scripts/judge/screenspot-pro_run_judge.sh
bash scripts/judge/androidcontrol_run_judge.sh
```

### 第 3 步：指标计算 (Metric)

```bash
bash scripts/metric/run_metric_screenspot_pro.sh
bash scripts/metric/run_metric_androidcontrol.sh
```

---

## ⚙️ 脚本参数说明

### 🖥️ Transformers 后端

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `EXPERIMENT_NAME` | 实验名称（输出目录） | — |
| `MODEL_TYPE` | 模型类型（见上方模型表） | — |
| `MODEL_PATH` | HuggingFace 模型 ID 或本地路径 | — |
| `BENCHMARK` | Benchmark 名称 | — |
| `NUM_GPUS` | 并行推理 GPU 数 | `8` |
| `MAX_TOKENS` | 最大生成 token 数 | `512` |
| `TEMPERATURE` | 采样温度 | `0.0` |
| `TV_OR_VT` | 输入顺序：`vt`=图片在前，`tv`=文本在前 | `vt` |
| `SYSTEM_PROMPT` | `"call_user"`/`"default"`/`""` | 因模型而异 |
| `MIN_PIXELS` / `MAX_PIXELS` | 图片缩放像素范围 | 模型默认值 |

### 🌐 API 后端额外参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `API_BASE` | 逗号分隔的 API 端点列表 | — |
| `API_KEY` | API 密钥 | `""` |
| `MODEL_NAME` | 模型名称 | — |
| `NUM_THREADS` | API 并发线程数 | `64` |

---

## 🧩 添加新模型

1. 创建 `inference/<name>_inferencer.py`，继承 `BaseInferencer`。
2. 实现四个方法：`_init_model()`、`_build_prompt()`、`_generate()`、`_post_process()`。
3. 在 `inference/__init__.py` 中注册。
4. 在 `data/convert_any_models.py` 中添加 prompt 注入逻辑。
5. 在 `judge/grounding_judge.py` 中添加解析逻辑。
6. 在 `scripts/infer/` 下创建对应启动脚本。

---

## 📋 数据格式

| 字段 | 必需 | 说明 |
|------|------|------|
| `id` | ✅ | 样本唯一标识 |
| `question` | ✅ | 指令文本 |
| `answer` | ✅ | Ground truth（边界框坐标） |
| `image` | ✅ | 图片文件路径 |
| `image_size` | ✅ | `[宽, 高]`，像素单位 |
| `system_prompt` | ❌ | System prompt 列表 |

---

## 📈 复现结果

**总体复现率：46 / 48 = 95.8%**

> 判定标准：复现数值高于或等于官方数值，或绝对差值 ≤ 2%，则视为复现成功（✅）。

详细结果见英文版 README 或数据集页面：[🤗 HuggingFace](https://huggingface.co/datasets/johnzqlu/opengui-eval) | [🤖 ModelScope](https://modelscope.cn/datasets/Matrix0602/opengui-eval)

---

## 🗺️ 路线图

- [x] 支持 ScreenSpot-Pro、ScreenSpot-V2、UIVision、MMBench-GUI、OSWorld-G
- [x] 支持 AndroidControl（Qwen3-VL、Qwen2.5-VL）
- [x] Transformers & API 双后端推理
- [x] 多 GPU 并行推理，支持断点续推
- [x] 前沿模型复现（Gemini 3.1/3.0 Pro、Seed 1.8）Zoom 范式
- [ ] 集成 vLLM 离线推理（非 server 模式）
- [ ] 添加更多 GUI 专用模型
- [ ] GUI 离线导航评测（如 GUI-Odyssey）

---

## 📄 License

本项目采用 [Apache License 2.0](LICENSE) 开源协议。
