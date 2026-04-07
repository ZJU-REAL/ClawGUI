"""Model client module for AI inference."""

from phone_agent.model.client import ModelClient, ModelConfig
from phone_agent.model.adapters import (
    ModelType,
    ModelAdapter,
    AutoGLMAdapter,
    UITarsAdapter,
    get_adapter,
    detect_model_type,
    get_adapter_for_model,
)

__all__ = [
    "ModelClient",
    "ModelConfig",
    "ModelType",
    "ModelAdapter",
    "AutoGLMAdapter",
    "UITarsAdapter",
    "get_adapter",
    "detect_model_type",
    "get_adapter_for_model",
]
