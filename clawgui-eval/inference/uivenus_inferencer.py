"""
UI-Venus inferencer (GUI-G2 / Qwen2.5-VL stack).
Same inference path and normalized box output as GUIG2Inferencer; uses a distinct model_type
so predictions are stored under uivenus_infer and judged with guig2_parse.
"""

from .guig2_inferencer import GUIG2Inferencer


class UIVenusInferencer(GUIG2Inferencer):
    """UI-Venus — inherits GUI-G2 behavior; register as model_type ``uivenus``."""

    _inferencer_label = "UI-Venus"
