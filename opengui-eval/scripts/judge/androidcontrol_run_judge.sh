#!/bin/bash

# ======================
# Experiment Configuration
# ======================
EXP_NAME="qwen3vl-2b-androidcontrol-high-exp"
BENCHMARK="androidcontrol-high-qwen3vl"

# ======================
# Model Configuration (可选)
# ======================
# 如果不指定,会自动从数据中检测
MODEL_TYPE="qwen3vl"

# ======================
# Path Configuration
# ======================
INPUT_FILE="output/${EXP_NAME}/${BENCHMARK}/predictions.jsonl"
# 输出文件格式会自动根据输入文件格式决定
# 如果输入是.jsonl,输出也是.jsonl
# 如果需要强制指定格式,可以加上后缀,如: predictions_eval.json
OUTPUT_FILE="output/${EXP_NAME}/${BENCHMARK}/predictions_judge.jsonl"

# ======================
# Run Judge
# ======================
if [ -z "${MODEL_TYPE}" ]; then
    # 不指定model_type,自动检测
    python judge/androidcontrol_judge.py \
        --input_file ${INPUT_FILE} \
        --output_file ${OUTPUT_FILE} \
        --exp_name ${EXP_NAME}
else
    # 指定model_type
    python judge/androidcontrol_judge.py \
        --input_file ${INPUT_FILE} \
        --output_file ${OUTPUT_FILE} \
        --exp_name ${EXP_NAME} \
        --model_type ${MODEL_TYPE}
fi
