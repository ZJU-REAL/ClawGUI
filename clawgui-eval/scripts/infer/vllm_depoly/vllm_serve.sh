#!/bin/bash
# Deploy N vLLM instances across 8 GPUs (GPUS_PER_INSTANCE GPUs each).
# Ports start at START_PORT — must match API_BASE in api inference scripts.

# ======================
# Configuration
# ======================
# Supported model paths:
#   qwen3vl   : Qwen/Qwen3-VL-2B-Instruct
#   qwen25vl  : Qwen/Qwen2.5-VL-7B-Instruct
#   maiui     : Tongyi-MAI/MAI-UI-2B
#   uitars    : ByteDance-Seed/UI-TARS-1.5-7B
#   uivenus15 : inclusionAI/UI-Venus-1.5-8B
#   guiowl15  : mPLUG/GUI-Owl-1.5-8B-Instruct
#   guig2     : inclusionAI/GUI-G2-7B
#   stepgui   : stepfun-ai/GELab-Zero-4B-preview

MODEL_PATH="ByteDance-Seed/UI-TARS-1.5-7B"
MODEL_NAME="UI-TARS-1.5-7B"     # --served-model-name; leave empty to omit
NUM_INSTANCES=4        # number of server instances to launch
GPUS_PER_INSTANCE=2    # GPUs allocated per instance (tensor parallel size)
START_PORT=22003            # instances get ports: 22003, 22004, 22005, 22006
LOG_DIR="$(dirname "$0")/logs"

# ======================
# Deploy
# ======================
mkdir -p "${LOG_DIR}"
bash "$(dirname "$0")/kill_vllm.sh"
EXTRA_ARGS=""
if [ -n "${MODEL_NAME}" ]; then
    EXTRA_ARGS="--served-model-name ${MODEL_NAME}"
fi

echo "Launching ${NUM_INSTANCES} vLLM instances (${GPUS_PER_INSTANCE} GPUs each)..."

for i in $(seq 0 $((NUM_INSTANCES - 1))); do
    GPU_START=$((i * GPUS_PER_INSTANCE))
    GPU_END=$((GPU_START + GPUS_PER_INSTANCE - 1))
    GPUS=$(seq -s, ${GPU_START} ${GPU_END})
    PORT=$((START_PORT + i))
    LOG_FILE="${LOG_DIR}/instance_${i}_port_${PORT}.log"

    echo "[instance ${i}] GPUs ${GPUS} -> port ${PORT}"
    CUDA_VISIBLE_DEVICES=${GPUS} nohup vllm serve "${MODEL_PATH}" \
        --host 127.0.0.1 \
        --port ${PORT} \
        --tensor-parallel-size ${GPUS_PER_INSTANCE} \
        --gpu-memory-utilization 0.8 \
        --max-num-seqs 32 \
        --trust-remote-code \
        ${EXTRA_ARGS} \
        > "${LOG_FILE}" 2>&1 &
    echo "  PID: $!"
    sleep 3
done

echo "All instances started. Logs: ${LOG_DIR}/"
