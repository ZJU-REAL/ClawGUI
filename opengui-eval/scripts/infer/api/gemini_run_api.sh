#!/bin/bash
# ======================
# Experiment Configuration
# ======================
EXPERIMENT_NAME="seed18-zoom-exp"
# EXPERIMENT_NAME="gemini-zoom-exp"


# ======================
# API Configuration
# ======================
BACKEND="api"
API_BASE=""
# seed 
API_KEY=""
MODEL_NAME=""

# ======================
# Threading Configuration
# ======================
NUM_THREADS=16  # number of concurrent threads for API calls

# ======================
# Zoom Configuration
# ======================
# --zoom enables two-stage Zoom-In grounding (default: disabled)
# crop ratio is hardcoded to 0.5 in the inferencer
ZOOM_FLAG="--zoom"

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
    --model_type seed \
    --model_path dummy \
    --backend ${BACKEND} \
    --api_base ${API_BASE} \
    --api_key "${API_KEY}" \
    --model_name "${MODEL_NAME}" \
    --benchmark ${BENCHMARK} \
    --num_threads ${NUM_THREADS} \
    ${ZOOM_FLAG} \
    --resume \
    --verbose
