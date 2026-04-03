#!/bin/bash

# ======================
# Experiment Configuration
# ======================
EXP_NAME="qwen3vl-2b-androidcontrol-high-exp"
BENCHMARK="androidcontrol-high-qwen3vl"

# ======================
# Path Configuration
# ======================
INPUT_FILE="output/${EXP_NAME}/${BENCHMARK}/predictions_judge.jsonl"
OUTPUT_FILE="output/${EXP_NAME}/${BENCHMARK}/metrics.json"

# ======================
# Run Metric Calculation
# ======================
python metric/androidcontrol_metric.py \
    --input_file ${INPUT_FILE} \
    --output_file ${OUTPUT_FILE} \
    --exp_name ${EXP_NAME} \
    --benchmark ${BENCHMARK}
