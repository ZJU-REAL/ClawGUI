#!/bin/bash
# ======================
# Experiment Configuration
# ======================
EXP_NAME="uivenus-exp"

# ======================
# Model Configuration
# ======================
# options: maiui qwen3vl qwen25vl uitars stepgui uivenus uivenus15 guiowl15 guig2 seed gemini kimi
MODEL_TYPE="uivenus"

# ======================
# Benchmark List
# ======================
BENCHMARKS=(
    "uivision-${MODEL_TYPE}"
)

# ======================
# Run Judge for each benchmark
# ======================
for BENCHMARK in "${BENCHMARKS[@]}"; do
    INPUT_FILE="output/${EXP_NAME}/${BENCHMARK}/predictions.jsonl"
    OUTPUT_FILE="output/${EXP_NAME}/${BENCHMARK}/predictions_judge.jsonl"

    echo "=========================================="
    echo "Judging: ${EXP_NAME} / ${BENCHMARK}"
    echo "Input:   ${INPUT_FILE}"
    echo "Output:  ${OUTPUT_FILE}"
    echo "=========================================="

    python judge/grounding_judge.py \
        --input_file ${INPUT_FILE} \
        --output_file ${OUTPUT_FILE} \
        --exp_name ${EXP_NAME} \
        --benchmark ${BENCHMARK} \
        --model_type ${MODEL_TYPE}

    echo ""
done
