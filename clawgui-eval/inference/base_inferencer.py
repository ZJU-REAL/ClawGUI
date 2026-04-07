"""Abstract base class for all model inferencers."""

import importlib.util
import time
from abc import ABC, abstractmethod
from typing import Dict, Any
from PIL import Image


def _best_attn_implementation() -> str:
    if importlib.util.find_spec("flash_attn") is not None:
        print("Using flash_attention_2")
        return "flash_attention_2"
    else:
        print("Using sdpa")
    return "sdpa"


class BaseInferencer(ABC):
    """
    Base inferencer. Each model implements its own subclass.

    Subclasses must implement: _init_model, _build_prompt, _generate, _post_process.
    """

    def __init__(self, model_path: str, backend: str = "transformers", **kwargs):
        """
        Args:
            model_path: Local directory or HuggingFace repo id.
            backend: "transformers" or "api".
            **kwargs:
                - model_type (str, required): Registry key for this model.
                - system_prompt_mode (str): "" | "default" | "call_user".
                - api_key, api_base, model_name: API backend params.
                - temperature, max_tokens, top_p, top_k: Generation params.
                - tv_or_vt: "tv" (text first) | "vt" (image first, default).
                - min_pixels, max_pixels: Image resize bounds.
        """
        self.model_path = model_path
        self.backend = backend

        # Must be popped before passing kwargs to sub-classes
        self._model_type = kwargs.pop("model_type", "unknown")
        self._system_prompt_mode = kwargs.pop("system_prompt_mode", "")

        self.kwargs = kwargs
        self.tv_or_vt = kwargs.get("tv_or_vt", "vt")
        self.min_pixels = kwargs.get("min_pixels", None)
        self.max_pixels = kwargs.get("max_pixels", None)

        self.model = None
        self.processor = None
        self.tokenizer = None

        self._init_model()

    @abstractmethod
    def _init_model(self):
        """Load model weights and processor."""
        pass

    @abstractmethod
    def _build_prompt(self, question: str, image: Image.Image, system_prompts: list = None) -> Any:
        """
        Build model input from question and image.

        Args:
            question: Text question.
            image: PIL Image.
            system_prompts: Optional list of system prompt strings.

        Returns:
            Model input (format depends on backend/model).
        """
        pass

    @abstractmethod
    def _generate(self, inputs: Any) -> str:
        """
        Run inference.

        Args:
            inputs: Output of _build_prompt.

        Returns:
            Raw model output string.
        """
        pass

    @abstractmethod
    def _post_process(self, output: str) -> str:
        """
        Post-process raw model output.

        Args:
            output: Raw model output string.

        Returns:
            Cleaned output string.
        """
        pass

    def infer_single(self, sample: Dict[str, Any]) -> Dict[str, Any]:
        """
        Run inference on a single sample.

        Args:
            sample: {"id": str, "image": str (path), "question": str, ...}

        Returns:
            {"id": str, "model_type": str, "model_response": str, "inference_time": float}
        """
        start_time = time.time()
        sample_id = sample["id"]
        image_path = sample.get("image")
        question = sample.get("question", "")

        if not image_path:
            return {
                "id": sample_id,
                "model_type": self.model_type,
                "model_response": "",
                "inference_time": time.time() - start_time,
                "error": "image path is empty or missing"
            }

        try:
            image = Image.open(image_path).convert("RGB")

            system_prompts = None
            if self._system_prompt_mode == "call_user":
                system_prompts = sample.get("system_prompt", None)
            elif self._system_prompt_mode == "default":
                system_prompts = ["You are a helpful assistant."]

            inputs = self._build_prompt(question, image, system_prompts)
            output = self._generate(inputs)
            response = self._post_process(output)

            return {
                "id": sample_id,
                "model_type": self.model_type,
                "model_response": response,
                "inference_time": time.time() - start_time
            }

        except FileNotFoundError:
            return {
                "id": sample_id,
                "model_type": self.model_type,
                "model_response": "",
                "inference_time": time.time() - start_time,
                "error": f"image not found: {image_path}"
            }

        except Exception as e:
            return {
                "id": sample_id,
                "model_type": self.model_type,
                "model_response": "",
                "inference_time": time.time() - start_time,
                "error": str(e)
            }

    @property
    def model_type(self) -> str:
        return self._model_type

    @property
    def system_prompt_mode(self) -> str:
        return self._system_prompt_mode
