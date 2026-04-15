"""
Kimi K2.5 Inferencer (API-only).

Model characteristics:
- Coordinate system: [0, 1000] normalized
- Output format: (x, y)
- Uses a fixed system prompt
- Supports optional Zoom-In (two-stage crop-then-ground)

Zoom-In process:
  1. Stage 1: Model grounds on the original image
  2. Stage 2: Crop a region centered on the Stage-1 prediction
     (default 25% of width/height), resize to 1920×1080, then ground again
  3. Map Stage-2 relative coordinates back to original image space
"""

import re
import base64
import time
import random
from io import BytesIO
from PIL import Image
from typing import Any, Optional, Tuple
from .base_inferencer import BaseInferencer


# Retry configuration
MAX_RETRIES = 8
RETRY_BASE_DELAY = 2
RATE_LIMIT_WAIT = 30

# Default Zoom-In configuration
DEFAULT_CROP_RATIO = 0.25
ZOOM_RESIZE_W = 1920
ZOOM_RESIZE_H = 1080

# System prompt
KIMI_SYSTEM_PROMPT = """You are an expert UI element locator. Given a GUI image and a user's element description, provide your reasoning process first, finally provide the coordinates of the specified element as a single point. For elements with area, return the center point.

Give your reasoning process first, then output the coordinate pair ranging from 0 to 1000 exactly in format:
(x,y)"""


def _pil_to_base64(image: Image.Image) -> str:
    """Convert a PIL Image to a base64-encoded PNG string."""
    buf = BytesIO()
    image.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("utf-8")


def _parse_coord(text: str) -> Optional[Tuple[float, float]]:
    """
    Parse (x,y) coordinates (0-1000 range) from a model response string.
    Returns (x, y) on success, or None if parsing fails.
    """
    # Method 1: match (x,y) or (x, y) format
    pattern = r'\(\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*\)'
    matches = re.findall(pattern, text)
    if matches:
        x, y = matches[-1]
        return (float(x), float(y))

    # Method 2: match bare x,y format without parentheses
    pattern2 = r'(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)'
    matches2 = re.findall(pattern2, text)
    if matches2:
        x, y = matches2[-1]
        return (float(x), float(y))

    return None


def _crop_and_zoom(
    image: Image.Image,
    center_x: float,
    center_y: float,
    crop_ratio: float = DEFAULT_CROP_RATIO,
    resize_to: Optional[Tuple[int, int]] = (ZOOM_RESIZE_W, ZOOM_RESIZE_H),
) -> Tuple[Image.Image, dict]:
    """
    Crop around (center_x, center_y) which are in [0, 1000] coordinates.

    Args:
        image: Original PIL Image.
        center_x, center_y: Predicted point in [0, 1000].
        crop_ratio: Fraction of width/height to keep.
        resize_to: Target size after cropping (None to skip resize).

    Returns:
        (cropped_image, crop_info) where crop_info is used for coordinate mapping.
    """
    w, h = image.size
    cx = center_x / 1000.0 * w
    cy = center_y / 1000.0 * h
    cw = w * crop_ratio
    ch = h * crop_ratio

    left = cx - cw / 2
    top = cy - ch / 2
    right = cx + cw / 2
    bottom = cy + ch / 2

    # Clamp to image bounds (shift if needed to preserve crop size)
    if left < 0:
        right -= left; left = 0
    if top < 0:
        bottom -= top; top = 0
    if right > w:
        left -= (right - w); right = w
    if bottom > h:
        top -= (bottom - h); bottom = h
    left = max(0, left)
    top = max(0, top)
    right = min(w, right)
    bottom = min(h, bottom)

    cropped = image.crop((int(left), int(top), int(right), int(bottom)))
    crop_info = {
        "left": left, "top": top, "right": right, "bottom": bottom,
        "crop_width": right - left, "crop_height": bottom - top,
        "orig_width": w, "orig_height": h,
    }
    if resize_to is not None:
        cropped = cropped.resize(resize_to, Image.Resampling.LANCZOS)
        crop_info["resized_to"] = list(resize_to)
    return cropped, crop_info


def _map_zoomed_to_original(zx: float, zy: float, info: dict) -> Tuple[float, float]:
    """Map [0,1000] coords in the crop back to [0,1000] coords in the original image."""
    px = zx / 1000.0 * info["crop_width"] + info["left"]
    py = zy / 1000.0 * info["crop_height"] + info["top"]
    ox = max(0, min(1000, px / info["orig_width"] * 1000.0))
    oy = max(0, min(1000, py / info["orig_height"] * 1000.0))
    return ox, oy


class KimiInferencer(BaseInferencer):
    """
    Kimi K2.5 inferencer (API-only, with optional Zoom-In).
    """

    def __init__(self, model_path: str, backend: str = "api", **kwargs):
        if backend != "api":
            raise ValueError(f"KimiInferencer only supports 'api' backend, got '{backend}'")
        super().__init__(model_path, backend, **kwargs)

    def _init_model(self):
        """Initialize OpenAI-compatible API client."""
        from openai import OpenAI

        self.api_key = self.kwargs.get("api_key", "EMPTY")
        if not self.api_key or self.api_key.strip() == "":
            self.api_key = "EMPTY"

        api_base_str = self.kwargs.get("api_base", None)
        if api_base_str is None:
            raise ValueError("--api_base is required for Kimi API backend.")

        self.api_urls = [u.strip() for u in api_base_str.split(",") if u.strip()]
        self.model_name = self.kwargs.get("model_name", "kimi-k2-5")

        # Zoom config
        self.zoom_enabled = self.kwargs.get("zoom", False)
        self.crop_ratio = DEFAULT_CROP_RATIO
        self.resize_to = (ZOOM_RESIZE_W, ZOOM_RESIZE_H)

        self.temperature = self.kwargs.get("temperature", 0.0)
        self.max_tokens = self.kwargs.get("max_tokens", 32768)

        print(f"Loading Kimi model (API): {self.model_name}")
        print(f"  API endpoints ({len(self.api_urls)}):")
        for i, url in enumerate(self.api_urls):
            print(f"    [{i+1}] {url}")
        print(f"  Zoom: {'enabled (crop_ratio={}, resize={}x{})'.format(self.crop_ratio, ZOOM_RESIZE_W, ZOOM_RESIZE_H) if self.zoom_enabled else 'disabled'}")
        print(f"  Temperature: {self.temperature}")
        print(f"  Max Tokens: {self.max_tokens}")

        self.clients = []
        for url in self.api_urls:
            self.clients.append(OpenAI(
                api_key=self.api_key,
                base_url=url,
            ))
        print(f"  {len(self.clients)} API client(s) ready")

    def _build_prompt(self, question: str, image: Image.Image, system_prompts: list = None) -> Any:
        """Build Kimi API messages (fixed system prompt, image-last by default)."""
        img_b64 = _pil_to_base64(image)
        img_url = f"data:image/png;base64,{img_b64}"

        # Kimi K2.5 typically uses vt (image first) or tv (text first) depending on tuning.
        # We default to image-last (tv) like Gemini/Seed since frontier models often prefer it,
        # but allow override via tv_or_vt kwarg.
        tv_or_vt = self.kwargs.get("tv_or_vt", "tv")

        if tv_or_vt == "vt":
            messages = [
                {"role": "system", "content": KIMI_SYSTEM_PROMPT},
                {"role": "user", "content": [
                    {"type": "image_url", "image_url": {"url": img_url}},
                    {"type": "text", "text": f"The user's element description is: {question}"},
                ]},
            ]
        else:
            messages = [
                {"role": "system", "content": KIMI_SYSTEM_PROMPT},
                {"role": "user", "content": [
                    {"type": "text", "text": f"The user's element description is: {question}"},
                    {"type": "image_url", "image_url": {"url": img_url}},
                ]},
            ]
        return messages

    def _call_api(self, messages: list) -> Optional[str]:
        """Call API with retries and rate-limit handling. Returns content or None."""
        last_err = None
        for attempt in range(MAX_RETRIES):
            try:
                client = random.choice(self.clients)
                resp = client.chat.completions.create(
                    model=self.model_name,
                    messages=messages,
                    max_completion_tokens=self.max_tokens,
                    temperature=self.temperature,
                )
                return resp.choices[0].message.content.strip()
            except Exception as e:
                last_err = e
                err_s = str(e)
                if "429" in err_s or "rate limit" in err_s.lower():
                    wait = RATE_LIMIT_WAIT + RETRY_BASE_DELAY ** attempt
                    print(f"[Attempt {attempt+1}/{MAX_RETRIES}] Rate limit, waiting {wait:.0f}s ...")
                    time.sleep(wait)
                else:
                    print(f"[Attempt {attempt+1}/{MAX_RETRIES}] API error: {type(e).__name__}: {e}")
                    if attempt < MAX_RETRIES - 1:
                        time.sleep(RETRY_BASE_DELAY ** attempt)
        print(f"All {MAX_RETRIES} attempts failed. Last error: {last_err}")
        return None

    def _generate(self, inputs: Any) -> str:
        """Single-stage API call (no zoom). Returns raw content string."""
        content = self._call_api(inputs)
        return content if content is not None else ""

    def _post_process(self, output) -> str:
        if isinstance(output, str):
            return output.strip()
        return output

    # ------------------------------------------------------------------
    # Override infer_single to support two-stage Zoom-In
    # ------------------------------------------------------------------
    def infer_single(self, sample: dict) -> dict:
        start_time = time.time()
        sample_id = sample.get("id", "unknown")
        image_path = sample.get("image")
        question = sample.get("question", "")

        if not image_path:
            return self._error_result(sample_id, "image path is empty", start_time)

        try:
            image = Image.open(image_path).convert("RGB")
        except Exception as e:
            return self._error_result(sample_id, f"image load failed: {e}", start_time)

        # ---- Stage 1: ground on original image ----
        msgs1 = self._build_prompt(question, image)
        content1 = self._call_api(msgs1)
        if content1 is None:
            return self._error_result(sample_id, "API error in stage 1", start_time)

        # If zoom is disabled or stage-1 parse fails, return stage-1 result directly
        coord1 = _parse_coord(content1)
        if not self.zoom_enabled or coord1 is None:
            return {
                "id": sample_id,
                "model_type": self.model_type,
                "model_response": content1,
                "prediction": content1,
                "inference_time": time.time() - start_time,
            }

        # ---- Stage 2: crop around stage-1 prediction, ground again ----
        s1x, s1y = coord1
        cropped, crop_info = _crop_and_zoom(image, s1x, s1y, self.crop_ratio, self.resize_to)
        msgs2 = self._build_prompt(question, cropped)
        content2 = self._call_api(msgs2)

        # Fallback to stage-1 if stage-2 fails
        if content2 is None:
            return {
                "id": sample_id,
                "model_type": self.model_type,
                "model_response": content1,
                "prediction": content1,
                "inference_time": time.time() - start_time,
                "zoom_info": {
                    "zoom_enabled": True,
                    "stage1_coord": [s1x, s1y],
                    "stage2_api_failed": True,
                    "fallback": "stage1",
                },
            }

        coord2 = _parse_coord(content2)
        if coord2 is None:
            return {
                "id": sample_id,
                "model_type": self.model_type,
                "model_response": content1,
                "prediction": content1,
                "inference_time": time.time() - start_time,
                "zoom_info": {
                    "zoom_enabled": True,
                    "stage1_coord": [s1x, s1y],
                    "stage2_parse_failed": True,
                    "stage2_raw": content2,
                    "fallback": "stage1",
                },
            }

        # Map stage-2 coords back to original image
        fx, fy = _map_zoomed_to_original(coord2[0], coord2[1], crop_info)
        final_response = f"({int(round(fx))}, {int(round(fy))})"

        return {
            "id": sample_id,
            "model_type": self.model_type,
            "model_response": final_response,
            "prediction": final_response,
            "inference_time": time.time() - start_time,
            "zoom_info": {
                "zoom_enabled": True,
                "crop_ratio": self.crop_ratio,
                "zoom_resize": list(self.resize_to),
                "stage1_coord": [s1x, s1y],
                "stage1_raw": content1,
                "crop_info": {
                    "left": crop_info["left"],
                    "top": crop_info["top"],
                    "crop_width": crop_info["crop_width"],
                    "crop_height": crop_info["crop_height"],
                },
                "stage2_coord": [coord2[0], coord2[1]],
                "stage2_raw": content2,
                "final_coord": [fx, fy],
            },
        }

    def _error_result(self, sample_id, msg, start_time):
        return {
            "id": sample_id,
            "model_type": self.model_type,
            "model_response": "",
            "prediction": "",
            "inference_time": time.time() - start_time,
            "error": msg,
        }
