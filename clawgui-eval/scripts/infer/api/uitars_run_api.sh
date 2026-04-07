#!/bin/bash
# ======================
# Experiment Configuration
# ======================
EXPERIMENT_NAME="uitars-exp0"

# ======================
# Model Configuration
# ======================
MODEL_TYPE="uitars"

# ======================
# API Configuration
# ======================
BACKEND="api"
# comma-separated list of endpoints for load balancing across multiple server instances
API_BASE="http://127.0.0.1:22003/v1,http://127.0.0.1:22004/v1,http://127.0.0.1:22005/v1,http://127.0.0.1:22006/v1"
API_KEY=""  # leave empty for local vllm; set your API key for official APIs
MODEL_NAME="UI-TARS-1.5-7B"

# ======================
# Generation Configuration
# ======================
MAX_TOKENS=512
TEMPERATURE=0.0
TOP_P=1.0
TOP_K=1

# ======================
# Threading Configuration
# ======================
NUM_THREADS=64  # number of concurrent threads for API calls

# ======================
# Image Configuration
# ======================
MIN_PIXELS=78400    # 100*28*28
MAX_PIXELS=12845056  # 16384*28*28

# ======================
# Other Configuration
# ======================
TV_OR_VT="vt"  # input order: vt=image first, tv=text first
# screenspot-pro-uitars | screenspot-v2-uitars | uivision-uitars | mmbench-gui-uitars | osworld-g-uitars
BENCHMARK="osworld-g-uitars"
SYSTEM_PROMPT=""  # "default"=model's default, "call_user"=read from jsonl, ""=disabled

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
