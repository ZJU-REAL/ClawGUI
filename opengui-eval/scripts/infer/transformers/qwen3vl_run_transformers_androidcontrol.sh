#!/bin/bash

# ======================
# Experiment Configuration
# ======================
EXPERIMENT_NAME="qwen3vl-2b-androidcontrol-high-exp"

# ======================
# Model Configuration
# ======================
MODEL_TYPE="qwen3vl"
MODEL_PATH="Qwen/Qwen3-VL-2B-Instruct"  # 请修改为实际模型路径

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
MIN_PIXELS=65536   # 可选：图片最小像素数，例如 224*224=50176
MAX_PIXELS=16777216   # 可选：图片最大像素数，例如 224*224=50176

# ======================
# GPU Configuration
# ======================
NUM_GPUS=8  # 使用的GPU数量，>1时启动多GPU推理

# ======================
# Other Configuration
# ======================
TV_OR_VT="tv"
BENCHMARK="androidcontrol-high-qwen3vl"
SYSTEM_PROMPT="call_user"  # 空字符串=不使用system_prompt, default=使用默认prompt, call_user=从jsonl读取

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
    --system_prompt ${SYSTEM_PROMPT} \
    --resume \
    --verbose