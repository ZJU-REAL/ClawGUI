# OpenGUI-Eval

GUI Grounding 评测框架，采用 **Infer → Judge → Metric** 三阶段流水线。

## 环境依赖

```
torch>=2.8.0
transformers>=4.57.3
```

## 下载 Benchmark 图片

评测所需的图片托管在 Hugging Face 上，运行评测前请先下载并解压：

```bash
# 安装 huggingface_hub（如未安装）
pip install huggingface_hub

# 下载图片压缩包
huggingface-cli download johnzqlu/opengui-eval image.tar.gz --repo-type dataset --local-dir .

# 解压到 opengui-eval/image/
tar -xzf image.tar.gz
```

解压后的目录结构如下：

```
opengui-eval/
└── image/
    ├── mmbench-gui/
    ├── osworld-g/
    ├── screenspot-pro/
    ├── screenspot-v2/
    └── uivision/
```

所有 benchmark 在推理时都需要这些图片。

## 项目结构

```
opengui-eval/
├── main.py                          # 推理主入口
├── inference/                       # 模型推理器
│   ├── base_inferencer.py           # 抽象基类
│   ├── qwen3vl_inferencer.py        # Qwen3-VL
│   ├── qwen25vl_inferencer.py       # Qwen2.5-VL
│   ├── maiui_inferencer.py          # MAI-UI
│   ├── stepgui_inferencer.py        # StepGUI
│   ├── guiowl15_inferencer.py       # GUI-Owl 1.5
│   ├── uitars_inferencer.py         # UI-TARS（继承 Qwen3-VL）
│   ├── guig2_inferencer.py          # GUI-G2（继承 Qwen2.5-VL）
│   └── uivenus15_inferencer.py      # UI-Venus 1.5（继承 Qwen3-VL）
├── judge/                           # 评判模块
│   ├── base_judge.py                # 抽象基类
│   ├── grounding_judge.py           # 点击坐标评判器（大部分 benchmark）
│   └── osworld_g_judge.py           # OSWorld-G 评判器（bbox/polygon/refusal）
├── metric/                          # 指标计算
│   ├── base_metric.py
│   ├── screenspotpro_metric.py
│   └── uivision_metric.py
├── data/                            # 数据 & prompt 注入
│   ├── convert_any_models.py        # 模型 prompt 注入脚本
│   └── *.json                       # 基础数据 & 模型专用数据
├── scripts/
│   ├── infer/
│   │   ├── transformers/            # 本地 GPU 推理（每个模型一个脚本）
│   │   ├── api/                     # API 推理（每个模型一个脚本）
│   │   └── vllm_depoly/             # vLLM 服务部署
│   ├── judge/                       # 评判脚本（每个 benchmark 一个）
│   └── metric/                      # 指标计算脚本
├── image/                           # benchmark 图片
└── output/                          # 推理 & 评判输出
```

## 支持的模型

| 模型类型 | 模型名称 | 架构 |
|---------|---------|------|
| `qwen3vl` | Qwen3-VL | 独立实现 |
| `qwen25vl` | Qwen2.5-VL | 独立实现 |
| `maiui` | MAI-UI | 独立实现 |
| `stepgui` | StepGUI (GELab-Zero) | 独立实现 |
| `guiowl15` | GUI-Owl 1.5 | 独立实现 |
| `uitars` | UI-TARS 1.5 | 继承 Qwen3-VL |
| `guig2` | GUI-G2 | 继承 Qwen2.5-VL |
| `uivenus15` | UI-Venus 1.5 | 继承 Qwen3-VL |

## 支持的 Benchmark

| Benchmark | 评判器 | 说明 |
|-----------|--------|------|
| `screenspot-pro` | `grounding_judge.py` | 点在框内判定，绝对坐标 |
| `screenspot-v2` | `grounding_judge.py` | 点在框内判定，绝对坐标 |
| `uivision` | `grounding_judge.py` | 点在框内判定，绝对坐标 |
| `mmbench-gui` | `grounding_judge.py` | 点在框内判定，绝对坐标 |
| `osworld-g` | `osworld_g_judge.py` | bbox + polygon + refusal 样本 |

## 快速开始

### 0. 数据准备

基础数据文件在 `data/` 目录下（如 `screenspot-pro.json`）。运行 `convert_any_models.py` 注入各模型专用 prompt：

```bash
# 所有模型、所有 benchmark
python data/convert_any_models.py \
    --input data/screenspot-pro.json data/screenspot-v2.json data/uivision.json \
           data/mmbench-gui.json data/osworld-g.json

# 只注入指定模型
python data/convert_any_models.py --input data/screenspot-pro.json --models qwen3vl maiui
```

生成的文件如 `data/screenspot-pro-qwen3vl.json`，会自动注册到 `main.py` 的 `BENCHMARK_DATA_MAP` 中。

对于 `osworld-g`，`uivenus15` 和 `guiowl15` 会自动切换为 refusal 感知的 prompt。

### 1. 推理 (Infer)

**Transformers 后端**（本地 GPU，推荐）：

```bash
scripts/infer/transformers/qwen3vl_run_transformers.sh
```

**API 后端**（通过 OpenAI 兼容接口调用远程服务）：

```bash
# 先部署 vLLM 服务
scripts/infer/vllm_depoly/vllm_serve.sh

# 再运行推理
scripts/infer/api/qwen3vl_run_api.sh
```

修改脚本中的 `EXPERIMENT_NAME`、`BENCHMARK` 等参数。输出路径：

```
output/<实验名>/<benchmark>/predictions.jsonl
```

### 2. 评判 (Judge)

```bash
scripts/judge/screenspot-pro_run_judge.sh
```

修改脚本中的 `EXP_NAME` 和 `MODEL_TYPE`。输出：

```
output/<实验名>/<benchmark>/predictions_judge.jsonl
```

每条记录会添加 `correct` 字段（true/false）。

### 3. 指标计算 (Metric)

```bash
scripts/metric/run_metric_screenspot_pro.sh
```

按平台、UI 类型等维度统计准确率。

## 脚本参数说明

### 推理参数（transformers 后端）

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `EXPERIMENT_NAME` | 实验名称（输出目录） | — |
| `MODEL_TYPE` | 模型类型（见上方模型表） | — |
| `MODEL_PATH` | HuggingFace 模型 ID 或本地路径 | — |
| `BENCHMARK` | benchmark 名称（如 `screenspot-pro-qwen3vl`） | — |
| `NUM_GPUS` | 并行推理 GPU 数 | `8` |
| `MAX_TOKENS` | 最大生成 token 数 | `512` |
| `TEMPERATURE` | 采样温度 | `0.0` |
| `TOP_P` | nucleus sampling top_p | `1.0` |
| `TOP_K` | top-k sampling（-1 禁用） | `-1` |
| `TV_OR_VT` | 输入顺序：`vt`=图片在前，`tv`=文本在前 | `vt` |
| `SYSTEM_PROMPT` | `"call_user"`=从数据读取，`"default"`=通用，`""`=禁用 | 因模型而异 |
| `USE_CACHE` | 生成时启用 KV cache | `true` |
| `MIN_PIXELS` / `MAX_PIXELS` | 图片缩放像素范围 | 模型默认值 |

### 推理参数（API 后端）

除上述参数外，还有：

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `API_BASE` | 逗号分隔的 API 端点列表（支持多实例负载均衡） | — |
| `API_KEY` | API 密钥（本地 vLLM 留空） | `""` |
| `MODEL_NAME` | API 服务的模型名称 | — |
| `NUM_THREADS` | API 并发线程数 | `64` |

### 评判参数

| 参数 | 说明 |
|------|------|
| `EXP_NAME` | 实验名称（需与推理输出一致） |
| `MODEL_TYPE` | 模型类型（选择对应的解析器） |
| `INCLUDE_REFUSAL` | `""` 排除 refusal 样本，`"--include_refusal"` 纳入（仅 osworld-g） |

## 添加新模型

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

## 数据格式

每条输入数据需包含以下字段：

| 字段 | 必需 | 说明 |
|------|------|------|
| `id` | 是 | 样本唯一标识 |
| `question` | 是 | 指令文本 |
| `answer` | 是 | ground truth（边界框坐标） |
| `image` | 是 | 图片文件路径 |
| `image_size` | 是 | `[宽, 高]`，像素单位 |
| `system_prompt` | 否 | system prompt 字符串列表（`SYSTEM_PROMPT="call_user"` 时使用） |
