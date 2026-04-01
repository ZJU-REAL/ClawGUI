# OpenGUI-Eval

GUI grounding evaluation framework with an **Infer → Judge → Metric** pipeline.

## Requirements

```
torch>=2.8.0
transformers>=4.57.3
```

## Download Benchmark Images

The benchmark images are hosted on Hugging Face. Download and extract them before running evaluation:

```bash
# Install huggingface_hub if needed
pip install huggingface_hub

# Download the image archive
huggingface-cli download johnzqlu/opengui-eval image.tar.gz --repo-type dataset --local-dir .

# Extract into opengui-eval/image/
tar -xzf image.tar.gz
```

After extraction, you should have the following structure:

```
opengui-eval/
└── image/
    ├── mmbench-gui/
    ├── osworld-g/
    ├── screenspot-pro/
    ├── screenspot-v2/
    └── uivision/
```

These images are required by all benchmarks during inference.

## Project Structure

```
opengui-eval/
├── main.py                          # inference entry point
├── inference/                       # model inferencers
│   ├── base_inferencer.py           # abstract base class
│   ├── qwen3vl_inferencer.py        # Qwen3-VL
│   ├── qwen25vl_inferencer.py       # Qwen2.5-VL
│   ├── maiui_inferencer.py          # MAI-UI
│   ├── stepgui_inferencer.py        # StepGUI
│   ├── guiowl15_inferencer.py       # GUI-Owl 1.5
│   ├── uitars_inferencer.py         # UI-TARS (inherits Qwen3-VL)
│   ├── guig2_inferencer.py          # GUI-G2 (inherits Qwen2.5-VL)
│   └── uivenus15_inferencer.py      # UI-Venus 1.5 (inherits Qwen3-VL)
├── judge/                           # evaluation judges
│   ├── base_judge.py                # abstract base class
│   ├── grounding_judge.py           # point-in-box judge (most benchmarks)
│   └── osworld_g_judge.py           # OSWorld-G judge (bbox/polygon/refusal)
├── metric/                          # metric computation
│   ├── base_metric.py
│   ├── screenspotpro_metric.py
│   └── uivision_metric.py
├── data/                            # benchmark data & prompt injection
│   ├── convert_any_models.py        # inject model-specific prompts
│   └── *.json                       # base & model-specific data files
├── scripts/
│   ├── infer/
│   │   ├── transformers/            # local GPU inference (1 script per model)
│   │   ├── api/                     # API inference (1 script per model)
│   │   └── vllm_depoly/             # vLLM server deployment
│   ├── judge/                       # judge scripts (1 per benchmark)
│   └── metric/                      # metric scripts
├── image/                           # benchmark images
└── output/                          # inference & judge outputs
```

## Supported Models

| Model Type | Model | Architecture |
|------------|-------|-------------|
| `qwen3vl` | Qwen3-VL | native |
| `qwen25vl` | Qwen2.5-VL | native |
| `maiui` | MAI-UI | native |
| `stepgui` | StepGUI (GELab-Zero) | native |
| `guiowl15` | GUI-Owl 1.5 | native |
| `uitars` | UI-TARS 1.5 | inherits Qwen3-VL |
| `guig2` | GUI-G2 | inherits Qwen2.5-VL |
| `uivenus15` | UI-Venus 1.5 | inherits Qwen3-VL |

## Supported Benchmarks

| Benchmark | Judge | Notes |
|-----------|-------|-------|
| `screenspot-pro` | `grounding_judge.py` | point-in-box, absolute coords |
| `screenspot-v2` | `grounding_judge.py` | point-in-box, absolute coords |
| `uivision` | `grounding_judge.py` | point-in-box, absolute coords |
| `mmbench-gui` | `grounding_judge.py` | point-in-box, absolute coords |
| `osworld-g` | `osworld_g_judge.py` | bbox + polygon + refusal samples |

## Quick Start

### 0. Prepare Data

Base data files live in `data/` (e.g. `screenspot-pro.json`). Run `convert_any_models.py` to inject model-specific prompts:

```bash
# all models, all benchmarks
python data/convert_any_models.py \
    --input data/screenspot-pro.json data/screenspot-v2.json data/uivision.json \
           data/mmbench-gui.json data/osworld-g.json

# specific models only
python data/convert_any_models.py --input data/screenspot-pro.json --models qwen3vl maiui
```

This generates files like `data/screenspot-pro-qwen3vl.json` and registers them in `main.py`.

For `osworld-g`, `uivenus15` and `guiowl15` automatically use refusal-aware prompts.

### 1. Infer

**Transformers backend** (local GPU, recommended):

```bash
scripts/infer/transformers/qwen3vl_run_transformers.sh
```

**API backend** (remote server via OpenAI-compatible API):

```bash
# first, deploy vLLM server instances
scripts/infer/vllm_depoly/vllm_serve.sh

# then run inference
scripts/infer/api/qwen3vl_run_api.sh
```

Edit the script to change `EXPERIMENT_NAME`, `BENCHMARK`, etc. Output goes to:

```
output/<experiment_name>/<benchmark>/predictions.jsonl
```

### 2. Judge

```bash
scripts/judge/screenspot-pro_run_judge.sh
```

Edit the script to set `EXP_NAME` and `MODEL_TYPE`. Output:

```
output/<experiment_name>/<benchmark>/predictions_judge.jsonl
```

Each record gets a `correct` field (true/false).

### 3. Metric

```bash
scripts/metric/run_metric_screenspot_pro.sh
```

Computes accuracy broken down by platform, UI type, etc.

## Script Parameters

### Inference (transformers)

| Parameter | Description | Default |
|-----------|-------------|---------|
| `EXPERIMENT_NAME` | experiment name (output directory) | — |
| `MODEL_TYPE` | model type key (see table above) | — |
| `MODEL_PATH` | HuggingFace model ID or local path | — |
| `BENCHMARK` | benchmark name (e.g. `screenspot-pro-qwen3vl`) | — |
| `NUM_GPUS` | number of GPUs for parallel inference | `8` |
| `MAX_TOKENS` | max generation tokens | `512` |
| `TEMPERATURE` | sampling temperature | `0.0` |
| `TOP_P` | nucleus sampling top_p | `1.0` |
| `TOP_K` | top-k sampling (-1 to disable) | `-1` |
| `TV_OR_VT` | input order: `vt`=image first, `tv`=text first | `vt` |
| `SYSTEM_PROMPT` | `"call_user"`=read from data, `"default"`=generic, `""`=disabled | varies |
| `USE_CACHE` | enable KV cache during generation | `true` |
| `MIN_PIXELS` / `MAX_PIXELS` | image resize bounds | model default |

### Inference (API)

Same as above, plus:

| Parameter | Description | Default |
|-----------|-------------|---------|
| `API_BASE` | comma-separated endpoint URLs for load balancing | — |
| `API_KEY` | API key (leave empty for local vLLM) | `""` |
| `MODEL_NAME` | served model name | — |
| `NUM_THREADS` | concurrent threads for API calls | `64` |

### Judge

| Parameter | Description |
|-----------|-------------|
| `EXP_NAME` | experiment name (must match inference output) |
| `MODEL_TYPE` | model type (selects the correct parser) |
| `INCLUDE_REFUSAL` | `""` to exclude refusal samples, `"--include_refusal"` to include (osworld-g only) |

## Adding a New Model

1. Create `inference/<name>_inferencer.py` inheriting from `BaseInferencer` (or an existing inferencer if the architecture is the same).

2. Implement: `_init_model()`, `_build_prompt()`, `_generate()`, `_post_process()`.

3. Register in `inference/__init__.py`:

```python
INFERENCER_REGISTRY = {
    ...
    "your_model": YourModelInferencer,
}
```

4. Add prompt injection logic in `data/convert_any_models.py` and run it to generate data files.

5. Add parsing logic in `judge/grounding_judge.py` (and `osworld_g_judge.py` if needed).

6. Create shell scripts in `scripts/infer/transformers/` and `scripts/infer/api/`.

## Data Format

Each input JSON item must have:

| Field | Required | Description |
|-------|----------|-------------|
| `id` | yes | unique sample identifier |
| `question` | yes | instruction text |
| `answer` | yes | ground-truth (bounding box coordinates) |
| `image` | yes | image file path |
| `image_size` | yes | `[width, height]` in pixels |
| `system_prompt` | no | list of system prompt strings (used when `SYSTEM_PROMPT="call_user"`) |
