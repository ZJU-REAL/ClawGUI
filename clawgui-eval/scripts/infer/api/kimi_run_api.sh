#!/bin/bash
# ======================
# Experiment Configuration
# ======================
EXPERIMENT_NAME="kimi-k2-5-exp"


# ======================
# API Configuration
# ======================
BACKEND="api"
API_BASE="https://api.moonshot.cn/v1"
API_KEY=""                    # set your Kimi API key here
MODEL_NAME="kimi-k2-5"        # or other Kimi vision model name

# ======================
# Threading Configuration
# ======================
NUM_THREADS=16  # number of concurrent threads for API calls

# ======================
# Zoom Configuration
# ======================
# --zoom enables two-stage Zoom-In grounding (default: disabled)
# crop ratio is 0.25 and resize to 1920x1080 in the inferencer
ZOOM_FLAG=""
# ZOOM_FLAG="--zoom"

# ======================
# Other Configuration
# ======================
# screenspot-pro | screenspot-v2 | uivision | mmbench-gui | osworld-g
BENCHMARK="screenspot-pro"

# ======================
# Run Inference
# ======================
python main.py \
    --experiment_name ${EXPERIMENT_NAME} \
    --model_type kimi \
    --model_path dummy \
    --backend ${BACKEND} \
    --api_base ${API_BASE} \
    --api_key "${API_KEY}" \
    --model_name "${MODEL_NAME}" \
    --benchmark ${BENCHMARK}-kimi \
    --num_threads ${NUM_THREADS} \
    ${ZOOM_FLAG} \
    --resume \
    --verbose
