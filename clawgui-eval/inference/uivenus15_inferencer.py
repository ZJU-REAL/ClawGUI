"""
UI-Venus 1.5 inferencer.
Based on Qwen3-VL architecture; outputs [0, 1000]-normalized coordinates in [x, y] format.
"""

from .qwen3vl_inferencer import Qwen3VLInferencer


class UIVenus15Inferencer(Qwen3VLInferencer):
    """UI-Venus 1.5 inferencer — reuses Qwen3VLInferencer without modification."""
    pass
