"""
Seed 1.8 Inferencer (API-only).

Model characteristics:
- Coordinate system: [0, 1000] normalized
- Output format: <point>x y</point>
- Uses a fixed system prompt
- Supports optional Zoom-In (two-stage crop-then-ground)

Zoom-In process (based on the MAI-UI blog):
  1. Stage 1: Model grounds on the original image
  2. Stage 2: Crop a region centered on the Stage-1 prediction
     (default 50% of width/height), then ground again on the crop
  3. Map Stage-2 relative coordinates back to original image space
"""

import re
import base64
import time
from io import BytesIO
from PIL import Image
from typing import Any, Optional, Tuple
from .base_inferencer import BaseInferencer


# Retry configuration
MAX_RETRIES = 8
RETRY_BASE_DELAY = 2
RATE_LIMIT_WAIT = 30

# Default Zoom-In configuration
DEFAULT_CROP_RATIO = 0.5  # crop region = 50% of original width/height

# System prompt (identical to the MAI-UI blog reference implementation)
SEED_SYSTEM_PROMPT = """You are an expert UI element locator. Given a GUI image and a user's element description, provide your reasoning process first, finally provide the coordinates of the specified element as a single <point>x y<point> point. For elements with area, return the center point.

Give your reasoning process first, then output the coordinate pair ranging from 0 to 1000 exactly in format:
<point>x y<point>"""


def _pil_to_base64(image: Image.Image) -> str:
    """Convert a PIL Image to a base64-encoded PNG string."""
    buf = BytesIO()
    image.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("utf-8")


def _parse_point(text: str) -> Optional[Tuple[float, float]]:
    """
    Parse <point>x y</point> from model output.
    Returns (x, y) in [0, 1000] or None on failure.
    """
    patterns = [
        r'<point>\s*(\d+(?:\.\d+)?)\s+(\d+(?:\.\d+)?)\s*</point>',
        r'<point>\s*(\d+(?:\.\d+)?)\s+(\d+(?:\.\d+)?)\s*<point>',
        r'<point>\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*</point>',
        r'<point>\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*<point>',
    ]
    for pat in patterns:
        m = re.search(pat, text, re.IGNORECASE)
        if m:
            return float(m.group(1)), float(m.group(2))
    return None


def _crop_and_zoom(
    image: Image.Image,
    center_x: float,
    center_y: float,
    crop_ratio: float = 0.5,
) -> Tuple[Image.Image, dict]:
    """
    Crop around (center_x, center_y) which are in [0, 1000] coordinates.

    Args:
        image: Original PIL Image.
        center_x, center_y: Predicted point in [0, 1000].
        crop_ratio: Fraction of width/height to keep (0.5 = 50%).

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

    # Clamp to image bounds (shift if needed)
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
    return cropped, crop_info


def _map_zoomed_to_original(zx: float, zy: float, info: dict) -> Tuple[float, float]:
    """Map [0,1000] coords in the crop back to [0,1000] coords in the original image."""
    px = zx / 1000.0 * info["crop_width"] + info["left"]
    py = zy / 1000.0 * info["crop_height"] + info["top"]
    ox = max(0, min(1000, px / info["orig_width"] * 1000.0))
    oy = max(0, min(1000, py / info["orig_height"] * 1000.0))
    return ox, oy


class SeedInferencer(BaseInferencer):
    """
    Seed 1.8 inferencer (API-only, with optional Zoom-In).
    """

    def __init__(self, model_path: str, backend: str = "api", **kwargs):
        if backend != "api":
            raise ValueError(f"SeedInferencer only supports 'api' backend, got '{backend}'")
        super().__init__(model_path, backend, **kwargs)

    def _init_model(self):
        """Initialize OpenAI-compatible API client."""
        from openai import OpenAI

        self.api_key = self.kwargs.get("api_key", "EMPTY")
        if not self.api_key or self.api_key.strip() == "":
            self.api_key = "EMPTY"

        api_base_str = self.kwargs.get("api_base", None)
        if api_base_str is None:
            raise ValueError("--api_base is required for Seed API backend.")

        self.api_urls = [u.strip() for u in api_base_str.split(",")]
        self.model_name = self.kwargs.get("model_name", "seed-1.8")

        # Zoom config (passed from main.py via kwargs)
        self.zoom_enabled = self.kwargs.get("zoom", False)
        self.crop_ratio = DEFAULT_CROP_RATIO  # hardcoded 0.5

        print(f"Loading Seed model (API): {self.model_name}")
        print(f"  API endpoints ({len(self.api_urls)}):")
        for i, url in enumerate(self.api_urls):
            print(f"    [{i+1}] {url}")
        print(f"  Zoom: {'enabled (crop_ratio={})'.format(self.crop_ratio) if self.zoom_enabled else 'disabled'}")

        self.clients = []
        for url in self.api_urls:
            self.clients.append(OpenAI(
                api_key=self.api_key,
                base_url=url,
            ))
        print(f"  {len(self.clients)} API client(s) ready")

    def _build_prompt(self, question: str, image: Image.Image, system_prompts: list = None) -> Any:
        """Build Seed API messages (fixed system prompt, image-last)."""
        img_b64 = _pil_to_base64(image)
        img_url = f"data:image/png;base64,{img_b64}"
        messages = [
            {"role": "user", "content": SEED_SYSTEM_PROMPT},
            {"role": "user", "content": f"The user's element description is: {question}"},
            {"role": "user", "content": [{"type": "image_url", "image_url": {"url": img_url}}]},
        ]
        return messages

    def _call_api(self, messages: list) -> Optional[str]:
        """Call API with retries and rate-limit handling. Returns content or None."""
        import random
        last_err = None
        for attempt in range(MAX_RETRIES):
            try:
                client = random.choice(self.clients)
                resp = client.chat.completions.create(
                    model=self.model_name,
                    messages=messages,
                    max_completion_tokens=self.kwargs.get("max_tokens", 32768),
                    temperature=self.kwargs.get("temperature", 0.0),
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
            image = Image.open(image_path)
        except Exception as e:
            return self._error_result(sample_id, f"image load failed: {e}", start_time)

        # ---- Stage 1: ground on original image ----
        msgs1 = self._build_prompt(question, image)
        content1 = self._call_api(msgs1)
        if content1 is None:
            return self._error_result(sample_id, "API error in stage 1", start_time)

        # If zoom is disabled or stage-1 parse fails, return stage-1 result directly
        coord1 = _parse_point(content1)
        if not self.zoom_enabled or coord1 is None:
            return {
                "id": sample_id,
                "model_type": self.model_type,
                "model_response": content1,
                "inference_time": time.time() - start_time,
            }

        # ---- Stage 2: crop around stage-1 prediction, ground again ----
        s1x, s1y = coord1
        cropped, crop_info = _crop_and_zoom(image, s1x, s1y, self.crop_ratio)
        msgs2 = self._build_prompt(question, cropped)
        content2 = self._call_api(msgs2)

        # Fallback to stage-1 if stage-2 fails
        if content2 is None:
            return {
                "id": sample_id,
                "model_type": self.model_type,
                "model_response": content1,
                "inference_time": time.time() - start_time,
            }

        coord2 = _parse_point(content2)
        if coord2 is None:
            # Stage-2 parse failed, fallback to stage-1
            return {
                "id": sample_id,
                "model_type": self.model_type,
                "model_response": content1,
                "inference_time": time.time() - start_time,
            }

        # Map stage-2 coords back to original image
        fx, fy = _map_zoomed_to_original(coord2[0], coord2[1], crop_info)
        final_response = f"<point>{int(round(fx))} {int(round(fy))}</point>"

        return {
            "id": sample_id,
            "model_type": self.model_type,
            "model_response": final_response,
            "inference_time": time.time() - start_time,
        }

    def _error_result(self, sample_id, msg, start_time):
        return {
            "id": sample_id,
            "model_type": self.model_type,
            "model_response": "",
            "inference_time": time.time() - start_time,
            "error": msg,
        }
