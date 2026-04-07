# ============================================================
# ClawGUI-Eval — Docker image
#
# Base: PyTorch 2.6 + CUDA 12.4 + Python 3.12
#
# Build:
#   docker build -t clawgui-eval .
#
# Run (see docker-compose.yml for a more convenient workflow):
#   docker run --gpus all \
#     -v /path/to/models:/models \
#     -v /path/to/data:/workspace/clawgui-eval/data \
#     -v /path/to/image:/workspace/clawgui-eval/image \
#     -v /path/to/output:/workspace/clawgui-eval/output \
#     clawgui-eval \
#     bash scripts/infer/transformers/qwen3vl_run_transformers.sh
# ============================================================

FROM pytorch/pytorch:2.6.0-cuda12.4-cudnn9-devel

# ---------- system packages ----------
RUN apt-get update && apt-get install -y --no-install-recommends \
        git \
        wget \
        unzip \
        build-essential \
        ninja-build \
        libgl1 \
    && rm -rf /var/lib/apt/lists/*

# ---------- project ----------
WORKDIR /workspace
COPY . clawgui-eval/
WORKDIR /workspace/clawgui-eval

# ---------- Python dependencies ----------
RUN pip install --no-cache-dir -r requirements.txt

# flash-attn: build from source (no-build-isolation required)
# This layer is slow to build but rarely changes — keep it last among pip installs.
RUN pip install --no-cache-dir --no-build-isolation flash-attn==2.8.1

# ---------- install package in editable mode ----------
RUN pip install --no-cache-dir -e .

# ---------- default command ----------
# Override with the actual inference/judge/metric script at `docker run` time.
CMD ["python", "main.py", "--help"]
