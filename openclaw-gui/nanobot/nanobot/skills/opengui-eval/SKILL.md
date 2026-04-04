---
name: opengui-eval
description: "Run OpenGUI-Eval benchmarks on GUI grounding models: check environment, run inference with progress monitoring, judge predictions, compute metrics, and report results."
metadata: {"nanobot":{"emoji":"📊","requires":{"bins":["python3","nvidia-smi"]}}}
---

# OpenGUI-Eval -- GUI Grounding Model Evaluation

Evaluate GUI grounding models on standard benchmarks (ScreenSpot-Pro, ScreenSpot-v2, UIVision, MMBench-GUI, OSWorld-G, AndroidControl). The eval framework lives at `opengui-eval/` inside the workspace.

All commands below MUST be run with `cd opengui-eval` first (relative paths for `data/`, `image/`, `output/` depend on this).

---

## Phase 1: Environment Check

Before running any evaluation, execute these checks and report results to the user.

### GPU

```bash
nvidia-smi --query-gpu=index,name,memory.total,memory.free --format=csv,noheader
```

Count the lines to determine available GPU count. Use this as `NUM_GPUS` unless the user specifies otherwise.

### System resources

```bash
free -h | head -3
df -h .
```

### Python & CUDA

```bash
cd opengui-eval && python3 -c "import torch; print(f'torch={torch.__version__}, cuda={torch.cuda.is_available()}, gpus={torch.cuda.device_count()}')"
```

### FlashAttention-2

```bash
python3 -c "import flash_attn; print(f'flash_attn={flash_attn.__version__}')" 2>&1
```

**If flash_attn is NOT installed, you MUST clearly warn the user:**

> "FlashAttention-2 未安装。框架会自动降级为 SDPA（PyTorch 内置），可以正常运行但精度可能略有下降。**强烈建议安装 FlashAttention-2 以获得最佳精度**："
>
> `pip install flash-attn --no-build-isolation`
>
> 如果从源码编译太慢，可以从 https://github.com/Dao-AILab/flash-attention/releases 下载预编译 wheel 安装。

Always include this warning prominently in the environment check report if flash_attn is missing. Do not silently skip it.

### Data integrity

```bash
ls opengui-eval/data/ | head -5
ls opengui-eval/image/ | head -3
```

Both `data/` and `image/` must exist. If `image/` is missing, tell the user to download and unzip the dataset first.

### Model path

If the user provides a local MODEL_PATH, verify it exists:

```bash
ls <MODEL_PATH>/config.json 2>/dev/null && echo "OK" || echo "NOT FOUND"
```

Report all results to the user. If any critical check fails (no GPU, no CUDA, missing data/image), stop and explain what is needed.

---

## Phase 2: Run Evaluation

The full pipeline is: **Infer → Judge → Metric**. You MUST execute all three steps in sequence -- do NOT stop after Judge. The Metric step produces the final accuracy numbers that the user needs.

The user must provide:
- `MODEL_TYPE` -- which model architecture (see script table below)
- `MODEL_PATH` -- HuggingFace ID or local directory
- `EXPERIMENT_NAME` -- a label for this run (output goes to `output/<EXPERIMENT_NAME>/...`)
- `BENCHMARK` -- which benchmark to evaluate on (see naming rules below)

#### Supported models (transformers backend)

Only the following MODEL_TYPE values are supported. If the user requests a model not in this list, **do NOT proceed** -- tell them the model is not yet supported by opengui-eval and show this table for reference.

| MODEL_TYPE | Example HuggingFace IDs (tested sizes) | SS-Pro | SS-V2 | UIVision | MMBench-GUI | OSWorld-G | AndroidControl |
|------------|---------------------------------------|:--:|:--:|:--:|:--:|:--:|:--:|
| `qwen3vl` | `Qwen/Qwen3-VL-2B-Instruct`, `Qwen/Qwen3-VL-4B-Instruct`, `Qwen/Qwen3-VL-8B-Instruct` | Y | Y | Y | Y | Y | Y |
| `qwen25vl` | `Qwen/Qwen2.5-VL-3B-Instruct`, `Qwen/Qwen2.5-VL-7B-Instruct` | Y | Y | Y | Y | Y | Y |
| `maiui` | `Tongyi-MAI/MAI-UI-2B`, `Tongyi-MAI/MAI-UI-8B` | Y | Y | Y | Y | Y | - |
| `uitars` | `ByteDance-Seed/UI-TARS-1.5-7B` | Y | Y | Y | Y | Y | - |
| `uivenus15` | `inclusionAI/UI-Venus-1.5-2B`, `inclusionAI/UI-Venus-1.5-8B` | Y | Y | Y | Y | Y | - |
| `guiowl15` | `mPLUG/GUI-Owl-1.5-2B-Instruct`, `mPLUG/GUI-Owl-1.5-4B-Instruct`, `mPLUG/GUI-Owl-1.5-8B-Instruct` | Y | Y | Y | Y | Y | - |
| `guig2` | `inclusionAI/GUI-G2-7B` | Y | Y | Y | Y | Y | - |
| `stepgui` | `stepfun-ai/GELab-Zero-4B-preview` | Y | Y | Y | Y | Y | - |
| `uivenus` | `inclusionAI/UI-Venus-Ground-7B` | Y | Y | Y | Y | Y | - |

`-` means the combination is not supported. If the user requests a MODEL_TYPE + BENCHMARK combination marked `-`, refuse and explain it is not available.

Users can substitute different sizes within the same model family by changing the size in the HuggingFace ID (e.g. `Qwen3-VL-2B` → `Qwen3-VL-4B`). The MODEL_TYPE stays the same regardless of model size. Users can also provide a local path instead of a HuggingFace ID.

API-only models (`gemini`, `seed`) are NOT supported by this skill -- they require the API/vLLM backend which is outside scope.

### Step 1: Inference (transformers)

Script templates are at `opengui-eval/scripts/infer/transformers/`. Pick the one matching the MODEL_TYPE:

| Template script | MODEL_TYPE |
|-----------------|------------|
| `qwen3vl_run_transformers.sh` | `qwen3vl` |
| `qwen25vl_run_transformers.sh` | `qwen25vl` |
| `maiui_run_transformers.sh` | `maiui` |
| `uitars_run_transformers.sh` | `uitars` |
| `guiowl15_run_transformers.sh` | `guiowl15` |
| `guig2_run_transformers.sh` | `guig2` |
| `stepgui_run_transformers.sh` | `stepgui` |
| `uivenus15_run_transformers.sh` | `uivenus15` |
| `uivenus_run_transformers.sh` | `uivenus` |
| `qwen3vl_run_transformers_androidcontrol.sh` | `qwen3vl` (AndroidControl only) |

#### How to create and run the inference script

**Do NOT modify the template scripts in-place.** Follow this procedure:

1. Read the matching template script with `read_file` to get its full content.
2. Create directory `opengui-eval/scripts/generate_scripts/` if it does not exist.
3. Copy the content into a new script at `opengui-eval/scripts/generate_scripts/<EXPERIMENT_NAME>.sh`.
4. In the new script, only modify these variables at the top:

| Variable | What to set |
|----------|-------------|
| `EXPERIMENT_NAME` | User-provided experiment label |
| `MODEL_PATH` | User-provided model path or HuggingFace ID |
| `BENCHMARK` | User-provided or derived from dataset + MODEL_TYPE |
| `NUM_GPUS` | From Phase 1 GPU count (or user override) |

Leave all other variables (MAX_TOKENS, TEMPERATURE, TOP_P, TOP_K, MIN_PIXELS, MAX_PIXELS, TV_OR_VT, SYSTEM_PROMPT, USE_CACHE, etc.) at the template defaults unless the user explicitly asks to change them.

5. Run the generated script (see "Running inference with monitoring" below).

#### Benchmark naming

Format: `<dataset>-<MODEL_TYPE>`

Five dataset prefixes:
- `screenspot-pro-<MODEL_TYPE>`
- `screenspot-v2-<MODEL_TYPE>`
- `uivision-<MODEL_TYPE>`
- `mmbench-gui-<MODEL_TYPE>`
- `osworld-g-<MODEL_TYPE>`

Example: MODEL_TYPE=`qwen3vl` → BENCHMARK=`screenspot-pro-qwen3vl`

Same model family with different sizes (e.g. Qwen3 2B/4B/8B) share the same BENCHMARK; differentiate via EXPERIMENT_NAME and MODEL_PATH only.

AndroidControl uses: `androidcontrol-high-<MODEL_TYPE>` or `androidcontrol-low-<MODEL_TYPE>`.

#### Running inference with monitoring

Inference is long-running (minutes to hours). The `exec` tool has a 600s timeout, so use background execution with log redirection.

**Step 1a: Get total sample count**

```bash
cd opengui-eval && python3 -c "import json; print(len(json.load(open('data/<BENCHMARK>.json'))))"
```

Save this number as TOTAL_SAMPLES.

**Step 1b: Launch the generated script in background**

```bash
cd opengui-eval && mkdir -p output/<EXPERIMENT_NAME>/<BENCHMARK> && \
nohup bash scripts/generate_scripts/<EXPERIMENT_NAME>.sh \
  > output/<EXPERIMENT_NAME>/<BENCHMARK>/run.log 2>&1 &
echo "PID=$!"
```

Save the PID.

**Step 1c: Poll progress**

Periodically check (start at ~30s intervals, increase to ~60s as it stabilizes):

```bash
# Is the process still running?
ps -p <PID> -o pid=,stat=,etime= 2>/dev/null || echo "DONE"

# How many samples completed?
wc -l < opengui-eval/output/<EXPERIMENT_NAME>/<BENCHMARK>/predictions.jsonl 2>/dev/null || echo 0

# GPU utilization
nvidia-smi --query-gpu=index,utilization.gpu,memory.used,memory.total --format=csv,noheader

# Last few log lines
tail -5 opengui-eval/output/<EXPERIMENT_NAME>/<BENCHMARK>/run.log
```

Report progress to the user: "Completed X / TOTAL_SAMPLES samples (Y%), GPU utilization Z%".

**Step 1d: Detect completion**

Inference is done when:
- `ps -p <PID>` returns nothing (process exited), OR
- `predictions.jsonl` line count equals TOTAL_SAMPLES

When done, check the log tail for errors:

```bash
tail -30 opengui-eval/output/<EXPERIMENT_NAME>/<BENCHMARK>/run.log
```

If the exit was abnormal (errors in log), report to the user. The `--resume` flag means re-running will continue from where it stopped.

### Step 2: Judge

Judge script templates are at `opengui-eval/scripts/judge/`. Pick the one matching the benchmark:

| Template script | Benchmark prefix |
|-----------------|-----------------|
| `screenspot-pro_run_judge.sh` | `screenspot-pro` |
| `screenspot-v2_run_judge.sh` | `screenspot-v2` |
| `uivision_run_judge.sh` | `uivision` |
| `mmbench-gui_run_judge.sh` | `mmbench-gui` |
| `osworld-g_run_judge.sh` | `osworld-g` |
| `androidcontrol_run_judge.sh` | `androidcontrol` |

Same procedure as inference:

1. Read the matching template with `read_file`.
2. Copy to `opengui-eval/scripts/generate_scripts/<EXPERIMENT_NAME>_judge.sh`.
3. Modify only the variables at the top: `EXP_NAME` and `MODEL_TYPE` (the BENCHMARKS array is derived from MODEL_TYPE automatically in most templates).
4. Run it:

```bash
cd opengui-eval && bash scripts/generate_scripts/<EXPERIMENT_NAME>_judge.sh
```

Judging is fast (seconds), so run directly with `exec` -- no need for background execution.

**After Judge completes, immediately proceed to Step 3 (Metric). Do NOT stop here.**

### Step 3: Metric (REQUIRED)

Metric script templates are at `opengui-eval/scripts/metric/`. Pick the one matching the benchmark:

| Template script | Benchmark prefix |
|-----------------|-----------------|
| `run_metric_screenspot_pro.sh` | `screenspot-pro` |
| `run_metric_screenspot_v2.sh` | `screenspot-v2` |
| `run_metric_uivision.sh` | `uivision` |
| `run_metric_mmbench_gui.sh` | `mmbench-gui` |
| `run_metric_osworld_g.sh` | `osworld-g` |
| `run_metric_androidcontrol.sh` | `androidcontrol` |

Same procedure:

1. Read the matching template with `read_file`.
2. Copy to `opengui-eval/scripts/generate_scripts/<EXPERIMENT_NAME>_metric.sh`.
3. Modify the variables at the top: `EXP_NAME`, and `BENCHMARK` (or `MODEL_TYPE` if the template derives BENCHMARK from it).
4. Run it:

```bash
cd opengui-eval && bash scripts/generate_scripts/<EXPERIMENT_NAME>_metric.sh
```

---

## Phase 3: Report Results

After all three steps (Infer → Judge → Metric) complete:

1. Read `output/<EXPERIMENT_NAME>/<BENCHMARK>/metrics.json` with `read_file` and extract the accuracy numbers.
2. Read the tail of `run.log` to extract inference statistics (throughput, elapsed time).
3. Look up the official baseline from the reference table below for comparison.
4. Present results to the user as a **comparison table**, showing the user's result alongside the official baseline.

Example format:

```
=== Evaluation Results ===

Experiment:  qwen3vl-2b-screenspot-pro
Model:       Qwen/Qwen3-VL-2B-Instruct (qwen3vl)
Benchmark:   screenspot-pro-qwen3vl
GPUs:        8

| Metric    | Your Result | Official Baseline |
|-----------|-------------|-------------------|
| Accuracy  | 44.12%      | 48.50%            |

Sub-category breakdown:
  - Mobile:  XX.X%
  - Desktop: XX.X%
  - Web:     XX.X%

Inference: 12.3 samples/s, 103.4s total
```

If the user ran multiple benchmarks, present a **summary comparison table** at the end:

```
| Benchmark       | Your Result | Official Baseline |
|-----------------|-------------|-------------------|
| screenspot-pro  | XX.X%       | XX.X%             |
| screenspot-v2   | XX.X%       | XX.X%             |
| ...             | ...         | ...               |
```

### Official baseline reference

Use this table to look up official baselines for comparison. `-` means no official number is available.

| Model | ScreenSpot-Pro | ScreenSpot-V2 | UIVision | MMB-GUI | OSWorld-G |
|:------|:-:|:-:|:-:|:-:|:-:|
| GUI-G2-7B | 47.50 | 93.30 | - | - | - |
| GUI-Owl 1.5-2B | 57.80 | 89.70 | - | 72.17 | 52.80 |
| GUI-Owl 1.5-4B | 66.80 | 93.20 | - | 83.24 | 63.70 |
| GUI-Owl 1.5-8B | 71.10 | 93.70 | - | 82.52 | 65.80 |
| Qwen3-VL-2B | 48.50 | - | - | - | - |
| Qwen3-VL-4B | 59.50 | - | - | - | - |
| Qwen3-VL-8B | 54.60 | - | - | - | - |
| UI-TARS 1.5-7B | 49.60 | - | - | - | - |
| UI-Venus-7B | 50.80 | 94.10 | 26.50 | - | 58.80 |
| UI-Venus 1.5-2B | 57.70 | 92.80 | 44.80 | 80.30 | 59.40 |
| UI-Venus 1.5-8B | 68.40 | 95.90 | 46.50 | 88.10 | 69.70 |
| MAI-UI-2B | 57.40 | 92.50 | 30.30 | 82.60 | 52.00 |
| MAI-UI-8B | 65.80 | 95.20 | 40.70 | 88.80 | 60.10 |
| StepGUI-4B | 60.00 | 93.60 | - | 84.00 | 66.90 |

When looking up the baseline, match by model name and size. If the exact size is not in the table (e.g. user tested a new size), show the closest available sizes for reference. If no baseline exists (`-`), note that no official number is available for this combination.

---

## Pitfalls

- **Always `cd opengui-eval` first.** All relative paths (`data/`, `image/`, `output/`) depend on this.
- **Never omit `--benchmark`.** Without it, `main.py` runs ALL benchmarks in the registry, which takes extremely long.
- **`maiui` uses `TV_OR_VT=tv`**, not `vt`. Do not change this to match other models.
- **`--resume` is on by default.** Re-running the same experiment+benchmark continues from where it stopped rather than starting over.
