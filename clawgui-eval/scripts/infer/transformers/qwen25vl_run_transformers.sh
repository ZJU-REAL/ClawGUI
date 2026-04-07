#!/bin/bash
# ======================
# Experiment Configuration
# ======================
EXPERIMENT_NAME="qwen25vl-7b-exp1"

# ======================
# Model Configuration
# ======================
MODEL_TYPE="qwen25vl"
MODEL_PATH="Qwen/Qwen2.5-VL-7B-Instruct"

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
MIN_PIXELS=3136
MAX_PIXELS=12845056

# ======================
# GPU Configuration
# ======================
NUM_GPUS=8  # number of GPUs for parallel inference

# ======================
# Other Configuration
# ======================
TV_OR_VT="vt"  # input order: vt=image first, tv=text first
# screenspot-pro-qwen25vl | screenspot-v2-qwen25vl | uivision-qwen25vl | mmbench-gui-qwen25vl | osworld-g-qwen25vl
BENCHMARK="osworld-g-qwen25vl"
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
