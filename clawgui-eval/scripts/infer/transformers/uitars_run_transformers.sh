#!/bin/bash
# ======================
# Experiment Configuration
# ======================
EXPERIMENT_NAME="uitars-exp0"

# ======================
# Model Configuration
# ======================
MODEL_TYPE="uitars"
MODEL_PATH="ByteDance-Seed/UI-TARS-1.5-7B"

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
TOP_K=1

# ======================
# Image Configuration
# ======================
MIN_PIXELS=78400    # 100*28*28
MAX_PIXELS=12845056  # 16384*28*28

# ======================
# GPU Configuration
# ======================
NUM_GPUS=8  # number of GPUs for parallel inference

# ======================
# Other Configuration
# ======================
TV_OR_VT="vt"  # input order: vt=image first, tv=text first
# screenspot-pro-uitars | screenspot-v2-uitars | uivision-uitars | mmbench-gui-uitars | osworld-g-uitars
BENCHMARK="osworld-g-uitars"
SYSTEM_PROMPT=""  # "default"=model's default, "call_user"=read from jsonl, ""=disabled
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
