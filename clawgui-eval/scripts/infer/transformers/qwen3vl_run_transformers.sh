#!/bin/bash
# ======================
# Experiment Configuration
# ======================
EXPERIMENT_NAME="qwen3vl-2b-exp0"

# ======================
# Model Configuration
# ======================
MODEL_TYPE="qwen3vl"
MODEL_PATH="Qwen/Qwen3-VL-2B-Instruct"

# ======================
# Backend Configuration
# ======================
BACKEND="transformers"

# ======================
# Generation Configuration
# ======================
MAX_TOKENS=512
TEMPERATURE=0.0
TOP_P=1.0
TOP_K=-1

# ======================
# Image Configuration
# ======================
MIN_PIXELS=65536
MAX_PIXELS=16777216

# ======================
# GPU Configuration
# ======================
NUM_GPUS=8  # number of GPUs for parallel inference

# ======================
# Other Configuration
# ======================
TV_OR_VT="vt"  # input order: vt=image first, tv=text first
# screenspot-pro-qwen3vl | screenspot-v2-qwen3vl | uivision-qwen3vl | mmbench-gui-qwen3vl | osworld-g-qwen3vl
BENCHMARK="osworld-g-qwen3vl"
SYSTEM_PROMPT="call_user"  # "default"=model's default, "call_user"=read from jsonl, ""=disabled
USE_CACHE=true  # enable KV cache during generation (true/false)

# ======================
# Run Inference
# ======================
python main.py \
    --experiment_name ${EXPERIMENT_NAME} \
    --model_type ${MODEL_TYPE} \
    --model_path ${MODEL_PATH} \
    --backend ${BACKEND} \
    --max_tokens ${MAX_TOKENS} \
    --temperature ${TEMPERATURE} \
    --top_p ${TOP_P} \
    --top_k ${TOP_K} \
    --min_pixels ${MIN_PIXELS} \
    --max_pixels ${MAX_PIXELS} \
    --tv_or_vt ${TV_OR_VT} \
    --benchmark ${BENCHMARK} \
    --num_gpus ${NUM_GPUS} \
    --system_prompt "${SYSTEM_PROMPT}" \
    --use_cache ${USE_CACHE} \
    --resume \
    --verbose
