#!/bin/bash
# ======================
# Experiment Configuration
# ======================
EXP_NAME="guiowl15-8b-exp"

# ======================
# Model Configuration
# ======================
# options: maiui qwen3vl qwen25vl uitars stepgui uivenus uivenus15 guiowl15 guig2 seed gemini kimi
MODEL_TYPE="guiowl15"

# Whether to include refusal samples in the accuracy denominator.
# ""                = exclude (reported separately)
# "--include_refusal" = include (relevant for uivenus15/guiowl15 on osworld-g)
INCLUDE_REFUSAL="--include_refusal"

# ======================
# Benchmark List
# ======================
BENCHMARKS=(
    "osworld-g-${MODEL_TYPE}"
)

# ======================
# Run Judge for each benchmark
# ======================
for BENCHMARK in "${BENCHMARKS[@]}"; do
    INPUT_FILE="output/${EXP_NAME}/${BENCHMARK}/predictions.jsonl"
    OUTPUT_FILE="output/${EXP_NAME}/${BENCHMARK}/predictions_judge.jsonl"

    if [ ! -f "${INPUT_FILE}" ]; then
        echo "[SKIP] file not found: ${INPUT_FILE}"
        continue
    fi

    echo "=========================================="
    echo "Judging: ${EXP_NAME} / ${BENCHMARK}"
    echo "Input:   ${INPUT_FILE}"
    echo "Output:  ${OUTPUT_FILE}"
    echo "=========================================="

    python judge/osworld_g_judge.py \
        --input_file  "${INPUT_FILE}" \
        --output_file "${OUTPUT_FILE}" \
        --exp_name    "${EXP_NAME}" \
        --benchmark   "${BENCHMARK}" \
        --model_type  "${MODEL_TYPE}" \
        ${INCLUDE_REFUSAL}

    echo ""
done
