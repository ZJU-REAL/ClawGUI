"""Inferencer module for GUI grounding models."""

from .base_inferencer import BaseInferencer
from .qwen3vl_inferencer import Qwen3VLInferencer
from .stepgui_inferencer import StepGUIInferencer
from .qwen25vl_inferencer import Qwen25VLInferencer
from .uivenus15_inferencer import UIVenus15Inferencer
from .maiui_inferencer import MAIUIInferencer
from .uitars_inferencer import UITARSInferencer
from .guiowl15_inferencer import GUIOwl15Inferencer
from .guig2_inferencer import GUIG2Inferencer
from .uivenus_inferencer import UIVenusInferencer
from .seed_inferencer import SeedInferencer
from .gemini_inferencer import GeminiInferencer
from .kimi_inferencer import KimiInferencer


INFERENCER_REGISTRY = {
    "qwen3vl"   : Qwen3VLInferencer,
    "stepgui"   : StepGUIInferencer,
    "qwen25vl"  : Qwen25VLInferencer,
    "uivenus15" : UIVenus15Inferencer,
    "maiui"     : MAIUIInferencer,
    "uitars"    : UITARSInferencer,
    "guiowl15"  : GUIOwl15Inferencer,
    "guig2"     : GUIG2Inferencer,
    "uivenus"   : UIVenusInferencer,
    "seed"      : SeedInferencer,
    "gemini"    : GeminiInferencer,
    "kimi"      : KimiInferencer,
}


def get_inferencer(model_type: str, model_path: str, backend: str = "transformers", **kwargs):
    """
    Instantiate and return an inferencer for the given model type.

    Args:
        model_type: Key in INFERENCER_REGISTRY (e.g. "qwen3vl", "maiui").
        model_path: Local model directory or HuggingFace repo id.
        backend: Inference backend — "transformers" or "api".
        **kwargs:
            - api_key: API key (backend="api")
            - api_base: Comma-separated endpoint URLs (backend="api")
            - model_name: Model name sent to the API (backend="api")
            - temperature, max_tokens, top_p, top_k: Generation params.
            - tv_or_vt: Input order — "tv" (text first) or "vt" (image first, default).
            - min_pixels, max_pixels: Image resize bounds.
    """
    if model_type not in INFERENCER_REGISTRY:
        raise ValueError(
            f"Unsupported model_type: '{model_type}'. "
            f"Available: {list(INFERENCER_REGISTRY.keys())}"
        )
    inferencer_class = INFERENCER_REGISTRY[model_type]
    return inferencer_class(model_type=model_type, model_path=model_path, backend=backend, **kwargs)


__all__ = [
    "BaseInferencer",
    "Qwen3VLInferencer",
    "StepGUIInferencer",
    "Qwen25VLInferencer",
    "UIVenus15Inferencer",
    "MAIUIInferencer",
    "UITARSInferencer",
    "GUIOwl15Inferencer",
    "GUIG2Inferencer",
    "UIVenusInferencer",
    "SeedInferencer",
    "GeminiInferencer",
    "KimiInferencer",
    "INFERENCER_REGISTRY",
    "get_inferencer",
]
