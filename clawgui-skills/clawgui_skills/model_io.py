"""Small OpenAI-compatible model helpers for ClawGUI-Skills."""

from __future__ import annotations

import base64
import io
import json
import re
from pathlib import Path
from typing import Any


class SkillModelClient:
    """Lazy OpenAI-compatible chat client used only when skill mode is active."""

    def __init__(
        self,
        *,
        base_url: str = "",
        api_key: str = "",
        model_name: str = "",
        temperature: float = 0.0,
        max_tokens: int = 3000,
        client: Any = None,
    ):
        self.base_url = base_url
        self.api_key = api_key or "EMPTY"
        self.model_name = model_name
        self.temperature = float(temperature)
        self.max_tokens = int(max_tokens)
        self._client = client

    @property
    def available(self) -> bool:
        return bool(self.model_name and (self._client is not None or self.base_url))

    def chat(
        self,
        *,
        system_prompt: str,
        user_text: str,
        image: Any = None,
        timeout: int = 300,
    ) -> str:
        if not self.available:
            raise RuntimeError("Skill model is not configured")

        content: list[dict[str, Any]] = [{"type": "text", "text": user_text}]
        image_url = image_to_data_url(image)
        if image_url:
            content.append({"type": "image_url", "image_url": {"url": image_url}})

        kwargs: dict[str, Any] = {
            "model": self.model_name,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": content},
            ],
            "temperature": self.temperature,
            "timeout": timeout,
        }
        if self._uses_completion_tokens():
            kwargs["max_completion_tokens"] = self.max_tokens
        else:
            kwargs["max_tokens"] = self.max_tokens

        response = self._openai_client().chat.completions.create(**kwargs)
        return response.choices[0].message.content or ""

    def _openai_client(self) -> Any:
        if self._client is None:
            from openai import OpenAI

            self._client = OpenAI(base_url=self.base_url, api_key=self.api_key)
        return self._client

    def _uses_completion_tokens(self) -> bool:
        name = self.model_name.lower()
        return "gpt" in name or "o1" in name or "o3" in name or "o4" in name


def parse_json_payload(raw: str) -> dict[str, Any]:
    """Parse strict JSON with common LLM-output fallbacks."""
    text = (raw or "").strip()
    if not text:
        raise ValueError("Empty model response")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    fence = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, flags=re.DOTALL)
    if fence:
        try:
            return json.loads(fence.group(1))
        except json.JSONDecodeError:
            text = fence.group(1)

    candidate = extract_json_object(text)
    if candidate is None:
        raise ValueError("No JSON object found in model response")
    try:
        return json.loads(candidate)
    except json.JSONDecodeError:
        pass

    try:
        from json_repair import repair_json
    except Exception as exc:
        raise ValueError(f"JSON parse failed and json_repair is unavailable: {exc}") from exc
    repaired = repair_json(candidate, return_objects=False)
    return json.loads(repaired)


def compact_raw_preview(raw: str, limit: int = 500) -> str:
    text = " ".join((raw or "").replace("\r", " ").replace("\n", " ").split())
    if len(text) > limit:
        return text[:limit].rstrip() + "... [truncated]"
    return text


def extract_json_object(text: str) -> str | None:
    start = text.find("{")
    if start < 0:
        return None
    depth = 0
    in_string = False
    escape = False
    for idx in range(start, len(text)):
        ch = text[idx]
        if in_string:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start : idx + 1]
    return None


def image_to_data_url(image: Any) -> str:
    """Convert common screenshot objects to an OpenAI image data URL."""
    if image is None:
        return ""
    if isinstance(image, str):
        if image.startswith("data:image/"):
            return image
        if _looks_base64(image):
            return f"data:image/png;base64,{image}"
        path = Path(image)
        if path.exists() and path.is_file():
            suffix = path.suffix.lower()
            mime = "image/jpeg" if suffix in {".jpg", ".jpeg"} else "image/png"
            return f"data:{mime};base64,{base64.b64encode(path.read_bytes()).decode('ascii')}"
        return ""
    base64_data = getattr(image, "base64_data", None)
    if isinstance(base64_data, str) and base64_data:
        return f"data:image/png;base64,{base64_data}"
    if isinstance(image, bytes):
        return f"data:image/png;base64,{base64.b64encode(image).decode('ascii')}"

    save = getattr(image, "save", None)
    if callable(save):
        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        return f"data:image/png;base64,{base64.b64encode(buffer.getvalue()).decode('ascii')}"
    return ""


def _looks_base64(value: str) -> bool:
    compact = value.strip()
    if len(compact) < 64:
        return False
    return bool(re.fullmatch(r"[A-Za-z0-9+/=\s]+", compact))
