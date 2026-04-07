#!/bin/bash
# ======================
# Experiment Configuration
# ======================
EXPERIMENT_NAME="guiowl15-2b-exp1"

# ======================
# Model Configuration
# ======================
MODEL_TYPE="guiowl15"

# ======================
# API Configuration
# ======================
BACKEND="api"
# comma-separated list of endpoints for load balancing across multiple server instances
API_BASE="http://127.0.0.1:22003/v1,http://127.0.0.1:22004/v1,http://127.0.0.1:22005/v1,http://127.0.0.1:22006/v1"
API_KEY=""  # leave empty for local vllm; set your API key for official APIs
MODEL_NAME="GUI-Owl-1.5-2B"

# ======================
# Generation Configuration
# ======================
MAX_TOKENS=512
TEMPERATURE=0.01
TOP_P=0.01
TOP_K=1

# ======================
# Threading Configuration
# ======================
NUM_THREADS=64  # number of concurrent threads for API calls

# ======================
# Image Configuration
# ======================
MIN_PIXELS=200704   # 196*32*32
MAX_PIXELS=10035200  # 9800*32*32

# ======================
# Other Configuration
# ======================
TV_OR_VT="vt"  # input order: vt=image first, tv=text first
# screenspot-pro-guiowl15 | screenspot-v2-guiowl15 | uivision-guiowl15 | mmbench-gui-guiowl15 | osworld-g-guiowl15
BENCHMARK="screenspot-pro-guiowl15"
SYSTEM_PROMPT="call_user"  # "default"=model's default, "call_user"=read from jsonl, ""=disabled

# ======================
# Run Inference
# ======================
python main.py \
    --experiment_name ${EXPERIMENT_NAME} \
    --model_type ${MODEL_TYPE} \
    --model_path ${MODEL_NAME} \
    --backend ${BACKEND} \
    --api_base ${API_BASE} \
    --api_key "${API_KEY:-EMPTY}" \
    --model_name ${MODEL_NAME} \
    --max_tokens ${MAX_TOKENS} \
    --temperature ${TEMPERATURE} \
    --top_p ${TOP_P} \
    --top_k ${TOP_K} \
    --min_pixels ${MIN_PIXELS} \
    --max_pixels ${MAX_PIXELS} \
    --tv_or_vt ${TV_OR_VT} \
    --benchmark ${BENCHMARK} \
    --num_threads ${NUM_THREADS} \
    --system_prompt "${SYSTEM_PROMPT}" \
    --resume \
    --verbose
