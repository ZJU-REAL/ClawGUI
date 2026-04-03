#!/bin/bash

# ======================
# Experiment Configuration
# ======================
EXP_NAME="uivenus-exp"

# ======================
# Model Configuration
# ======================
# options: maiui qwen3vl qwen25vl uitars stepgui uivenus uivenus15 guiowl15 guig2
MODEL_TYPE="uivenus"

# Whether to include refusal samples in the Overall accuracy denominator.
# ""                  = exclude (reported separately, default)
# "--include_refusal" = include
INCLUDE_REFUSAL=""

# ======================
# Path Configuration
# ======================
BENCHMARK="osworld-g-${MODEL_TYPE}"
INPUT_FILE="output/${EXP_NAME}/${BENCHMARK}/predictions_judge.jsonl"
OUTPUT_FILE="output/${EXP_NAME}/${BENCHMARK}/metrics.json"

# ======================
# Run Metric Calculation
# ======================
python metric/osworldg_metric.py \
    --input_file ${INPUT_FILE} \
    --output_file ${OUTPUT_FILE} \
    --exp_name ${EXP_NAME} \
    --benchmark ${BENCHMARK} \
    ${INCLUDE_REFUSAL}
