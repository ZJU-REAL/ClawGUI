#!/bin/bash
# ======================
# Experiment Configuration
# ======================
EXPERIMENT_NAME="guiowl15-8b-exp0"

# ======================
# Model Configuration
# ======================
MODEL_TYPE="guiowl15"
MODEL_PATH="mPLUG/GUI-Owl-1.5-8B-Instruct"

# ======================
# Backend Configuration
# ======================
BACKEND="transformers"

# ======================
# Generation Configuration
# ======================
MAX_TOKENS=512
TEMPERATURE=0.01
TOP_P=0.01
TOP_K=1

# ======================
# Image Configuration
# ======================
MIN_PIXELS=200704   # 196*32*32
MAX_PIXELS=10035200  # 9800*32*32

# ======================
# GPU Configuration
# ======================
NUM_GPUS=8  # number of GPUs for parallel inference

# ======================
# Other Configuration
# ======================
TV_OR_VT="vt"  # input order: vt=image first, tv=text first
# screenspot-pro-guiowl15 | screenspot-v2-guiowl15 | uivision-guiowl15 | mmbench-gui-guiowl15 | osworld-g-guiowl15
BENCHMARK="osworld-g-guiowl15"
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
