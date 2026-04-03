#!/bin/bash

# ======================
# Experiment Configuration
# ======================
EXP_NAME="uivenus-exp"
BENCHMARK="screenspot-v2-uivenus"

# ======================
# Path Configuration
# ======================
INPUT_FILE="output/${EXP_NAME}/${BENCHMARK}/predictions_judge.jsonl"
OUTPUT_FILE="output/${EXP_NAME}/${BENCHMARK}/metrics.json"

# ======================
# Run Metric Calculation
# ======================
python metric/screenspotv2_metric.py \
    --input_file ${INPUT_FILE} \
    --output_file ${OUTPUT_FILE} \
    --exp_name ${EXP_NAME} \
    --benchmark ${BENCHMARK}
