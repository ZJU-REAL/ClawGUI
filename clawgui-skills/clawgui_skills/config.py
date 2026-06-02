"""Configuration for ClawGUI-Skills runtime."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


VALID_SKILL_MODES = {"off", "trace", "reuse", "evolve"}
VALID_BACKEND_MODES = {"auto", "model", "fallback"}


@dataclass
class SkillRuntimeConfig:
    """Runtime controls for optional skill reuse and evolution."""

    mode: str = "off"
    store_dir: str = "skill_store"
    retrieval_threshold: float = 0.35
    max_context_chars: int = 6000
    max_iterations: int = 2
    require_review: bool = False
    auto_generate: bool = True
    include_failure_examples: bool = True
    platform: str = "Android"
    generator_mode: str = "auto"
    verifier_mode: str = "auto"
    revision_mode: str = "auto"
    model_base_url: str = ""
    model_api_key: str = ""
    model_name: str = ""
    model_temperature: float = 0.0
    model_max_tokens: int = 3000

    def __post_init__(self) -> None:
        mode = (self.mode or "off").lower()
        if mode not in VALID_SKILL_MODES:
            raise ValueError(f"Unsupported skill mode: {self.mode}")
        self.mode = mode
        self.generator_mode = self._normalize_backend_mode(self.generator_mode, "generator_mode")
        self.verifier_mode = self._normalize_backend_mode(self.verifier_mode, "verifier_mode")
        self.revision_mode = self._normalize_backend_mode(self.revision_mode, "revision_mode")
        self.retrieval_threshold = max(0.0, min(1.0, float(self.retrieval_threshold)))
        self.max_context_chars = max(0, int(self.max_context_chars))
        self.max_iterations = max(1, int(self.max_iterations))
        self.model_temperature = float(self.model_temperature)
        self.model_max_tokens = max(256, int(self.model_max_tokens))

    @property
    def enabled(self) -> bool:
        return self.mode != "off"

    @property
    def inject_enabled(self) -> bool:
        return self.mode in {"reuse", "evolve"}

    @property
    def evolve_enabled(self) -> bool:
        return self.mode == "evolve"

    @property
    def store_path(self) -> Path:
        return Path(self.store_dir).expanduser().resolve()

    @property
    def has_model_config(self) -> bool:
        return bool(self.model_name and self.model_base_url)

    @staticmethod
    def _normalize_backend_mode(value: str, field_name: str) -> str:
        mode = (value or "auto").lower()
        if mode not in VALID_BACKEND_MODES:
            raise ValueError(f"Unsupported {field_name}: {value}")
        return mode
