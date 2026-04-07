"""
UI-TARS 1.5 inferencer.
Based on Qwen2.5-VL architecture; grounding prompt template is baked into the data files.
"""

from .qwen25vl_inferencer import Qwen25VLInferencer


class UITARSInferencer(Qwen25VLInferencer):
    """UI-TARS 1.5 inferencer — reuses Qwen25VLInferencer without modification."""
    pass
