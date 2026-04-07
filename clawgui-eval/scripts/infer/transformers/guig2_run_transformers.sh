#!/bin/bash
# ======================
# Experiment Configuration
# ======================
EXPERIMENT_NAME="guig2-exp"

# ======================
# Model Configuration
# ======================
MODEL_TYPE="guig2"
MODEL_PATH="inclusionAI/GUI-G2-7B"

# ======================
# Backend Configuration
# ======================
BACKEND="transformers"

# ======================
# Generation Configuration
# ======================
MAX_TOKENS=512
TEMPERATURE=0.0
TOP_P=0.001
TOP_K=1

# ======================
# Image Configuration
# ======================
MIN_PIXELS=78400      # 100*28*28
MAX_PIXELS=12845056   # 16384*28*28

# ======================
# GPU Configuration
# ======================
NUM_GPUS=8  # number of GPUs for parallel inference

# ======================
# Other Configuration
# ======================
TV_OR_VT="vt"  # input order: vt=image first, tv=text first
# screenspot-pro-guig2 | screenspot-v2-guig2 | uivision-guig2 | mmbench-gui-guig2 | osworld-g-guig2
BENCHMARK="screenspot-v2-guig2"
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
