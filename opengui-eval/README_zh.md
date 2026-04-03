<div align="center">

<img src="assets/logo_crop.png" width="350" alt="OpenGUI-Eval Logo">

# OpenGUI-Eval: 统一的 GUI 评估框架

[![Python 3.12](https://img.shields.io/badge/Python-3.12-blue.svg)](https://www.python.org/downloads/release/python-3120/)
[![License](https://img.shields.io/badge/License-Apache_2.0-green.svg)](https://opensource.org/licenses/Apache-2.0)
[![HuggingFace Dataset](https://img.shields.io/badge/🤗%20HuggingFace-opengui--eval-yellow.svg)](https://huggingface.co/datasets/johnzqlu/opengui-eval)
[![ModelScope Dataset](https://img.shields.io/badge/🤖%20ModelScope-opengui--eval-purple.svg)](https://modelscope.cn/datasets/Matrix0602/opengui-eval)

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

**OpenGUI-Eval** 是一个标准化的 GUI Grounding 模型评测框架，采用 **Infer → Judge → Metric** 三阶段流水线，评估模型根据自然语言指令定位 UI 元素的准确性。

✨ **核心特性：**
- 🔌 **双后端支持** — 本地 GPU 推理（`transformers`）或远程 API 调用（OpenAI 兼容接口）
- 📊 **6 大 Benchmark** — ScreenSpot-Pro、ScreenSpot-V2、UIVision、MMBench-GUI、OSWorld-G、AndroidControl
- 🤖 **11+ 模型** — Qwen3-VL、Qwen2.5-VL、UI-TARS、MAI-UI、GUI-G2、UI-Venus、Gemini、Seed 1.8 等
- ⚡ **多 GPU & 多线程** — 并行推理，支持断点续推
- 🧩 **易于扩展** — 继承基类即可添加新模型
- ✅ **忠实复现** — 提供详细的官方 vs. 复现结果对比（[查看详情](#-复现结果)）
- 🌐 **前沿模型评测** — 成功复现 Gemini 3.0 Pro 和 Seed 1.8 在 ScreenSpot-Pro 上的官方结果，并新增 Gemini 3.1 Pro 评测

---

## 🏗️ 框架总览

<div align="center">
<img src="assets/opengui-eval.png" width="90%" alt="OpenGUI-Eval Architecture">
</div>

<div align="center">
<img src="assets/reproduction_by_benchmark.png" width="90%" alt="Reproduction Results by Benchmark">
</div>

---

## 🔧 安装

```bash
cd OpenGUI/opengui-eval
```

### 方式 A：Conda

```bash
conda create -n opengui-eval python=3.12 -y
conda activate opengui-eval
pip install -r requirements.txt
pip install flash-attn==2.8.1 --no-build-isolation
```

> 💡 **提示：** 如果从源码编译 `flash-attn` 太慢，可以从 [flash-attn releases 页面](https://github.com/Dao-AILab/flash-attention/releases) 下载预编译的 wheel 包直接安装。

### 方式 B：uv

请先确保已安装 [uv](https://docs.astral.sh/uv/)，然后执行：

```bash
uv sync
source .venv/bin/activate
uv pip install flash-attn==2.8.1 --no-build-isolation
```

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

下载后在 `opengui-eval/` 目录下解压压缩包：

```bash
cd opengui-eval
unzip image.zip
unzip data.zip
unzip output.zip
```

> ⚠️ **注意：** 所有 zip 文件（`image.zip`、`data.zip`、`output.zip`）都必须在 `opengui-eval/` 目录下解压，以确保相对路径能正确解析。

| 文件 | 内容 |
|------|------|
| `image.zip` | Benchmark 评测图片（`image/` 目录） |
| `data.zip` | Benchmark 数据 & prompt 文件（`data/` 目录） |
| `output.zip` | 预计算的推理 & 评判结果（`output/` 目录） |

---

## 📁 项目结构

```
opengui-eval/
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
| `maiui` | MAI-UI | 独立实现 | `[0, 1000]` | `tv` | ✅ 需要 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `stepgui` | StepGUI (GELab-Zero) | 独立实现 | `[0, 999]` | `vt` | ❌ 无 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `guiowl15` | GUI-Owl 1.5 | 独立实现 | `[0, 1000]` | `vt` | ✅ 需要 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `uitars` | UI-TARS 1.5 | 继承 Qwen2.5-VL | 绝对坐标 (smart_resize) | `vt` | ❌ 无 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `guig2` | GUI-G2 | 继承 Qwen2.5-VL | `[0, 1000]` | `vt` | ❌ 无 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `uivenus15` | UI-Venus 1.5 | 继承 Qwen3-VL | `[0, 1000]` | `vt` | ❌ 无 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `uivenus` | UI-Venus | 继承 GUI-G2 | `[0, 1000]` | `vt` | ❌ 无 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `gemini` | Gemini 3.x Pro | API（可选 Zoom） | `[0, 1000]` | `vt` | ✅ 内置 | ✅ | - | - | - | - | - |
| `seed` | Seed 1.8 | API（可选 Zoom） | `[0, 1000]` | `vt` | ✅ 内置 | ✅ | - | - | - | - | - |

### 前沿 / 闭源模型

我们还使用 **Zoom 范式**（裁剪后定位）在 ScreenSpot-Pro 上复现了前沿闭源模型的 GUI Grounding 结果。Zoom 范式的详细说明请参考 [MAI-UI blog: A Practical Guide to GUI Grounding for Frontier Models](https://galvanized-jump-79a.notion.site/Why-your-AI-Agent-keeps-misclicking-A-Practical-Guide-to-GUI-Grounding-for-Frontier-Models-32630d140ad8808e895de98994dddb93)。

| 模型 | 坐标系统 | Zoom 范式 | SS-Pro 官方 | SS-Pro 复现 |
|------|:-:|:-:|:-:|:-:|
| Gemini 3.1 Pro | `[0, 1000]` | ✅ | 无（N/A） | 85.01 |
| Gemini 3.0 Pro | `[0, 1000]` | ✅ | 72.70 | **75.08** ✅ |
| Seed 1.8 | `[0, 1000]` | ✅ | 73.10 | **72.80** ✅ |

> 📐 **坐标系统说明：**
> - **绝对坐标** — 输出为原始（或 smart_resize 后）图片的像素坐标
> - **[0, 1000]** — 输出归一化到 1000×1000 坐标空间，再映射回原图
> - **[0, 1]** — 输出为相对于原图尺寸的 [0, 1] 比例值
> - **[0, 999]** — 类似 [0, 1000]，但除数为 999

---

## 💡 复现经验

<details>
<summary><b>点击展开 9 条关键复现经验</b></summary>
<br>

#### 1. 🔀 Message 格式 (`tv_or_vt`)

不同模型对**图片和文本在输入消息中的顺序非常敏感**。框架提供 `TV_OR_VT` 参数控制：
- `vt` = 图片在前，文本在后（大部分模型的默认值）
- `tv` = 文本在前，图片在后（MAI-UI 需要此顺序）

> ⚠️ 务必与模型官方实现对齐，使用错误的顺序可能导致准确率显著下降。

#### 2. 🌡️ 温度 (Temperature)

Grounding 任务建议**始终设置 `TEMPERATURE=0.0`**（贪心解码）。非零温度会引入随机性，影响坐标精度。

#### 3. 📝 Prompt 对齐

大多数 GUI Grounding 模型**对 prompt 格式高度敏感**，请确保与官方 prompt 模板严格对齐。即使措辞上的微小差异也可能影响结果。`data/convert_any_models.py` 脚本已为所有支持的模型处理了 prompt 注入。

#### 4. 🖼️ 图片分辨率 (`MIN_PIXELS` / `MAX_PIXELS`)

模型对**图片分辨率范围敏感**，务必与官方值保持一致：
- 不同模型使用不同的默认分辨率
- 修改这些值可能导致准确率显著变化

#### 5. 📊 采样参数 (`TOP_P` / `TOP_K`)

这些参数对 Grounding 结果**影响极小** — 通常只有 ±0.1% 的波动，复现时无需过多关注。

#### 6. 📐 坐标系统

理解每个模型的输出坐标格式对正确解析至关重要：
- **Qwen2.5-VL 系列** (qwen25vl, uitars) → 输出**绝对像素坐标**
- **Qwen3-VL 系列** (qwen3vl, guiowl15, uivenus15, maiui) → 输出 **[0, 1000] 归一化**坐标
- **GUI-G2 系列** (guig2, uivenus) → 输出 **[0, 1000] 归一化**边界框
- **StepGUI** → 输出 **[0, 999] 归一化**坐标

> 🔑 坐标解析格式不匹配是导致准确率为零的头号原因。

#### 7. 💬 System Prompt

Qwen-VL 系列模型对 system prompt **非常敏感**：
- `qwen3vl`、`qwen25vl`、`guiowl15`、`maiui` → **需要**特定的 tool-call system prompt
- `uitars`、`guig2`、`uivenus`、`uivenus15`、`stepgui` → 将 prompt 注入到用户问题中

> 需要 system prompt 的模型请设置 `SYSTEM_PROMPT="call_user"`，prompt 内容已预注入到数据文件中。

#### 8. 🪄 默认 System Prompt 的增益

部分模型对哪怕最简单的 system prompt 也很敏感。仅仅加一句 `"You are a helpful assistant."` 就能在某些模型上**提升约 1% 的准确率**。如果模型官方代码中包含任何 system prompt，务必原样复现——即使看起来无关紧要。

#### 9. 📱 AndroidControl：Scroll 方向约定

AndroidControl 的滚动方向以**屏幕为参考系** — `scroll_direction=down` 表示屏幕向下滚动（内容上移）。然而，部分模型（基于人类手势数据训练）输出的是**手指方向** — 手指上划会导致屏幕向下滚动。使用时务必确认模型遵循哪种约定并做相应转换。

此外，自 OS-Atlas 起，后续大多数工作均使用 **7,708 条样本**的子集进行评测。对于 **Click 准确率**，GT 目标是从 AndroidControl 原始 accessibility tree 中解析出的**边界框**（point-in-box 判断）——这与 GUI-Odyssey 的评测方式不同，后者计算预测点与 GT 点之间的**欧几里得距离**，阈值为 **0.14**（按屏幕尺寸归一化）。

</details>

---

## 🚀 快速开始

### 第 1 步：推理 (Infer)

支持两种推理后端：

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

输出保存至：
```
output/<实验名>/<benchmark>/predictions.jsonl
```

### 第 2 步：评判 (Judge)

```bash
# GUI Grounding benchmarks
bash scripts/judge/screenspot-pro_run_judge.sh

# AndroidControl benchmark
bash scripts/judge/androidcontrol_run_judge.sh
```

每条记录会添加 `correct` 字段（true/false）。输出：
```
output/<实验名>/<benchmark>/predictions_judge.jsonl
```

### 第 3 步：指标计算 (Metric)

```bash
# GUI Grounding benchmarks
bash scripts/metric/run_metric_screenspot_pro.sh

# AndroidControl benchmark
bash scripts/metric/run_metric_androidcontrol.sh
```

按平台、UI 类型等维度统计准确率。

---

## ⚙️ 脚本参数说明

### 🖥️ Transformers 后端

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `EXPERIMENT_NAME` | 实验名称（输出目录） | — |
| `MODEL_TYPE` | 模型类型（见上方模型表） | — |
| `MODEL_PATH` | HuggingFace 模型 ID 或本地路径 | — |
| `BENCHMARK` | Benchmark 名称（如 `screenspot-pro-qwen3vl`） | — |
| `NUM_GPUS` | 并行推理 GPU 数 | `8` |
| `MAX_TOKENS` | 最大生成 token 数 | `512` |
| `TEMPERATURE` | 采样温度 | `0.0` |
| `TOP_P` | Nucleus sampling top-p | `1.0` |
| `TOP_K` | Top-k sampling（-1 禁用） | `-1` |
| `TV_OR_VT` | 输入顺序：`vt`=图片在前，`tv`=文本在前 | `vt` |
| `SYSTEM_PROMPT` | `"call_user"`=从数据读取，`"default"`=通用，`""`=禁用 | 因模型而异 |
| `USE_CACHE` | 生成时启用 KV cache | `true` |
| `MIN_PIXELS` / `MAX_PIXELS` | 图片缩放像素范围 | 模型默认值 |

### 🌐 API 后端

除上述参数外，还有：

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `API_BASE` | 逗号分隔的 API 端点列表（支持多实例负载均衡） | — |
| `API_KEY` | API 密钥（本地 vLLM 留空） | `""` |
| `MODEL_NAME` | API 服务的模型名称 | — |
| `NUM_THREADS` | API 并发线程数 | `64` |

### 🔍 评判参数

| 参数 | 说明 |
|------|------|
| `EXP_NAME` | 实验名称（需与推理输出一致） |
| `MODEL_TYPE` | 模型类型（选择对应的解析器） |
| `INCLUDE_REFUSAL` | `""` 排除 refusal 样本，`"--include_refusal"` 纳入（仅 OSWorld-G） |

---

## 🧩 添加新模型

1. 创建 `inference/<name>_inferencer.py`，继承 `BaseInferencer`（架构相同的可以继承已有 inferencer）。

2. 实现四个方法：`_init_model()`、`_build_prompt()`、`_generate()`、`_post_process()`。

3. 在 `inference/__init__.py` 中注册：
   ```python
   INFERENCER_REGISTRY = {
       ...
       "your_model": YourModelInferencer,
   }
   ```

4. 在 `data/convert_any_models.py` 中添加 prompt 注入逻辑，然后运行生成数据文件。

5. 在 `judge/grounding_judge.py`（以及 `osworld_g_judge.py`，如需要）中添加解析逻辑。

6. 在 `scripts/infer/transformers/` 和 `scripts/infer/api/` 下创建对应的启动脚本。

---

## 📋 数据格式

每条输入数据需包含以下字段：

| 字段 | 必需 | 说明 |
|------|------|------|
| `id` | ✅ | 样本唯一标识 |
| `question` | ✅ | 指令文本 |
| `answer` | ✅ | Ground truth（边界框坐标） |
| `image` | ✅ | 图片文件路径 |
| `image_size` | ✅ | `[宽, 高]`，像素单位 |
| `system_prompt` | ❌ | System prompt 字符串列表（`SYSTEM_PROMPT="call_user"` 时使用） |

---

## 📈 复现结果

OpenGUI-Eval 的核心目标之一是**忠实复现**官方公布的数值。下表对比了我们的复现结果与各 benchmark 的官方基准。

> 📂 **所有推理结果均已开源，欢迎查看：**
> [🤗 HuggingFace: johnzqlu/opengui-eval](https://huggingface.co/datasets/johnzqlu/opengui-eval) | [🤖 ModelScope: Matrix0602/opengui-eval](https://modelscope.cn/datasets/Matrix0602/opengui-eval)

> **判定标准：** 若复现数值**高于或等于**官方数值，或绝对差值 **≤ 1%**，则视为**复现成功**（✅）。`-` 表示无官方基准可供比较。

### GUI Grounding Benchmarks

| 模型 | SS-Pro 官方 | SS-Pro 复现 | SS-V2 官方 | SS-V2 复现 | UIVision 官方 | UIVision 复现 | MMB-GUI 官方 | MMB-GUI 复现 | OSWorld-G 官方 | OSWorld-G 复现 |
|:------|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| GUI-G2 | 47.50 | **47.75** ✅ | 93.30 | **93.32** ✅ | - | 25.99 | - | 79.33 | - | 58.63 |
| GUI-Owl 1.5-2B | 57.80 | 56.36 ❌ | 89.70 | **89.23** ✅ | - | 23.71 | 72.17 | **71.54** ✅ | 52.80 | **51.96** ✅ |
| GUI-Owl 1.5-4B | 66.80 | **66.16** ✅ | 93.20 | **92.53** ✅ | - | 29.97 | 83.24 | **82.94** ✅ | 63.70 | **65.10** ✅ |
| GUI-Owl 1.5-8B | 71.10 | 70.08 ❌ | 93.70 | **93.55** ✅ | - | 36.70 | 82.52 | **82.33** ✅ | 65.80 | **66.47** ✅ |
| Qwen3-VL-2B | 48.50 | 43.90 ❌ | - | 88.92 | - | 15.06 | - | 73.12 | - | 54.12 |
| Qwen3-VL-4B | 59.50 | **59.39** ✅ | - | 93.08 | - | 27.78 | - | 84.28 | - | 68.43 |
| Qwen3-VL-8B | 54.60 | **56.42** ✅ | - | 94.26 | - | 27.96 | - | 84.25 | - | 65.88 |
| Qwen2.5-VL-3B | - | 15.62 | - | 64.86 | - | 6.73 | - | 52.81 | - | 26.08 |
| Qwen2.5-VL-7B | - | 27.45 | - | 87.66 | - | 14.40 | - | 70.26 | - | 35.49 |
| UI-TARS 1.5-7B | 49.60 | 42.06 ❌ | - | 89.54 | - | 20.30 | - | 73.23 | - | 58.24 |
| UI-Venus-7B | 50.80 | **50.47** ✅ | 94.10 | **94.03** ✅ | 26.50 | **26.52** ✅ | - | 80.08 | 58.80 | **59.41** ✅ |
| UI-Venus 1.5-2B | 57.70 | **58.82** ✅ | 92.80 | **93.24** ✅ | 44.80 | **43.82** ✅ | 80.30 | **81.19** ✅ | 59.40 | **64.51** ✅ |
| UI-Venus 1.5-8B | 68.40 | **67.68** ✅ | 95.90 | **95.83** ✅ | 46.50 | **45.88** ✅ | 88.10 | **87.79** ✅ | 69.70 | **74.71** ✅ |
| MAI-UI-2B | 57.40 | **57.94** ✅ | 92.50 | **92.30** ✅ | 30.30 | **29.68** ✅ | 82.60 | **82.80** ✅ | 52.00 | **59.80** ✅ |
| MAI-UI-8B | 65.80 | 64.07 ❌ | 95.20 | **94.34** ✅ | 40.70 | **40.23** ✅ | 88.80 | **88.81** ✅ | 60.10 | **69.80** ✅ |
| StepGUI-4B | 60.00 | **59.14** ✅ | 93.60 | 91.98 ❌ | - | 29.90 | 84.00 | **83.03** ✅ | 66.90 | 65.69 ❌ |
| Gemini 3.0 Pro（Zoom，API） | 72.70 | **75.08** ✅ | - | - | - | - | - | - | - | - |
| Gemini 3.1 Pro（Zoom，API） | - | **85.01** | - | - | - | - | - | - | - | - |
| Seed 1.8（Zoom，API） | 73.10 | **72.80** ✅ | - | - | - | - | - | - | - | - |

**GUI Grounding 复现率（含官方基准的格子）：** 统计开源模型行，以及 Gemini 3.0 Pro、Seed 1.8 在 ScreenSpot-Pro（Zoom、API）上与官方可比的指标 — **41 / 46 = 89.1%**

> **关于 ScreenSpot-Pro 和 OSWorld-G 的偏差说明：** 部分模型（如 Qwen3-VL-2B、UI-TARS）在 ScreenSpot-Pro 和 OSWorld-G 上偏差较大，通常是由于官方未完整披露的图像预处理流程或评测配置差异所致。我们正在积极排查并改进。

### AndroidControl（HIGH Split — Step Success Rate）

AndroidControl 评测**离线导航**能力，包含多动作预测（点击、输入、滚动等）。目前支持 **Qwen3-VL** 和 **Qwen2.5-VL**。

| 模型 | AndroidControl HIGH SR（复现） |
|:------|:-:|
| Qwen3-VL-2B | 59.12 |
| Qwen2.5-VL-7B | 64.47 |

> **说明：** 上述模型的 AndroidControl 官方基准尚未公开，待官方数值发布后将及时补充对比。

---

## 🗺️ 路线图

- [x] 支持 ScreenSpot-Pro、ScreenSpot-V2、UIVision、MMBench-GUI、OSWorld-G
- [x] 支持 AndroidControl（Qwen3-VL、Qwen2.5-VL）
- [x] Transformers & API 双后端推理
- [x] 多 GPU 并行推理，支持断点续推
- [x] 前沿模型复现（Claude 4.5 Sonnet、Gemini 3.1/3.0 Pro、Seed 1.8）Zoom 范式
- [ ] 集成 vLLM 离线推理（非 server 模式）
- [ ] 添加更多 GUI 专用模型
- [ ] GUI 离线导航评测（如 GUI-Odyssey）

---

## 📄 License

本项目采用 [Apache License 2.0](LICENSE) 开源协议。
