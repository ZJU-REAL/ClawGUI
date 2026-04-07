"""
Gemini Inferencer (API mode only)

Gemini model characteristics:
- Coordinate system: 0-1000 (normalized_int)
- Output format: (x,y)
- No resize; uses the original image (Stage 1)

Zoom-In two-stage pipeline (optional):
1. Stage 1: Infer directly on the original image, obtain 0-1000 coordinates
2. Stage 2: Crop a 1/4 region (25% of width/height) centered on the Stage 1 prediction,
   resize to 1920×1080, infer again to obtain 0-1000 coordinates
3. Map Stage 2 coordinates back to the original image coordinate space
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

# Zoom-In configuration
ZOOM_CROP_RATIO = 0.25  # 1/4 of original width and height
ZOOM_RESIZE_W = 1920
ZOOM_RESIZE_H = 1080

# System Prompt
GEMINI_SYSTEM_PROMPT = """You are an expert UI element locator. Given a GUI image and a user's element description, provide your reasoning process first, finally provide the coordinates of the specified element as a single point. For elements with area, return the center point.

Give your reasoning process first, then output the coordinate pair ranging from 0 to 1000 exactly in format:
(x,y)"""


def pil_to_base64(image: Image.Image) -> str:
    """Convert a PIL Image to a base64-encoded string."""
    buffer = BytesIO()
    image.save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("utf-8")


def parse_coord_from_response(response_text: str) -> Optional[Tuple[float, float]]:
    """
    Parse (x,y) coordinates (0-1000 range) from a model response string.
    Returns (x, y) on success, or None if parsing fails.
    """
    # Method 1: match (x,y) or (x, y) format
    pattern = r'\(\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*\)'
    matches = re.findall(pattern, response_text)
    if matches:
        x, y = matches[-1]
        return (float(x), float(y))

    # Method 2: match bare x,y format without parentheses
    pattern2 = r'(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)'
    matches2 = re.findall(pattern2, response_text)
    if matches2:
        x, y = matches2[-1]
        return (float(x), float(y))

    return None


def crop_and_zoom(
    image: Image.Image,
    center_x_norm: float,
    center_y_norm: float,
    crop_ratio: float = ZOOM_CROP_RATIO,
    resize_to: Tuple[int, int] = (ZOOM_RESIZE_W, ZOOM_RESIZE_H),
) -> Tuple[Image.Image, dict]:
    """
    Crop a region from the original image centered at the given point
    (0-1000 normalized coordinates), then resize.

    Args:
        image: Original PIL Image
        center_x_norm: Center x in 0-1000 coordinates
        center_y_norm: Center y in 0-1000 coordinates
        crop_ratio: Fraction of the original width/height to crop (0.25 = 1/4)
        resize_to: Target size (width, height) after cropping

    Returns:
        (cropped_resized_image, crop_info)
    """
    orig_width, orig_height = image.size

    # 0-1000 → pixel coordinates
    center_px = center_x_norm / 1000.0 * orig_width
    center_py = center_y_norm / 1000.0 * orig_height

    # Crop region size in pixels
    crop_w = orig_width * crop_ratio
    crop_h = orig_height * crop_ratio

    # Compute crop box
    left = center_px - crop_w / 2
    top = center_py - crop_h / 2
    right = center_px + crop_w / 2
    bottom = center_py + crop_h / 2

    # Boundary correction: shift instead of clamp to preserve crop size
    if left < 0:
        right -= left
        left = 0
    if top < 0:
        bottom -= top
        top = 0
    if right > orig_width:
        left -= (right - orig_width)
        right = orig_width
    if bottom > orig_height:
        top -= (bottom - orig_height)
        bottom = orig_height

    # Secondary clamp
    left = max(0.0, left)
    top = max(0.0, top)
    right = min(float(orig_width), right)
    bottom = min(float(orig_height), bottom)

    cropped = image.crop((int(left), int(top), int(right), int(bottom)))

    crop_info = {
        'left': left,
        'top': top,
        'right': right,
        'bottom': bottom,
        'crop_width': right - left,
        'crop_height': bottom - top,
        'orig_width': orig_width,
        'orig_height': orig_height,
    }

    if resize_to is not None:
        cropped = cropped.resize(resize_to, Image.Resampling.LANCZOS)
        crop_info['resized_to'] = list(resize_to)

    return cropped, crop_info


def convert_zoomed_coord_to_original(
    zoomed_x_norm: float,
    zoomed_y_norm: float,
    crop_info: dict,
) -> Tuple[float, float]:
    """
    Convert 0-1000 coordinates in the cropped image back to 0-1000 coordinates
    in the original image.

    Args:
        zoomed_x_norm: x coordinate in the cropped image (0-1000)
        zoomed_y_norm: y coordinate in the cropped image (0-1000)
        crop_info: crop metadata returned by crop_and_zoom

    Returns:
        (orig_x_norm, orig_y_norm): coordinates in the original image (0-1000)
    """
    # Cropped image 0-1000 → pixel coordinates within the crop region
    px_in_crop = zoomed_x_norm / 1000.0 * crop_info['crop_width']
    py_in_crop = zoomed_y_norm / 1000.0 * crop_info['crop_height']

    # Add crop offset → absolute pixel coordinates in the original image
    px_in_orig = crop_info['left'] + px_in_crop
    py_in_orig = crop_info['top'] + py_in_crop

    # Original pixel coordinates → 0-1000
    orig_x_norm = px_in_orig / crop_info['orig_width'] * 1000.0
    orig_y_norm = py_in_orig / crop_info['orig_height'] * 1000.0

    # Clamp
    orig_x_norm = max(0.0, min(1000.0, orig_x_norm))
    orig_y_norm = max(0.0, min(1000.0, orig_y_norm))

    return (orig_x_norm, orig_y_norm)


class GeminiInferencer(BaseInferencer):
    """
    Gemini Inferencer

    Characteristics:
    - Coordinate system: 0-1000 (relative coordinates)
    - Output format: (x,y)
    - No resize; uses the original image (Stage 1)
    - Optional Zoom-In two-stage strategy
    """

    def __init__(self, model_path: str, backend: str = "api", **kwargs):
        """Initialize the Gemini inferencer.

        Args:
            model_path: Model path (placeholder in API mode, can be ignored)
            backend: Inference backend; only "api" is supported
            **kwargs: Optional keyword arguments:
                - api_base: API base URL
                - api_key: API key
                - model_name: Model name
                - zoom: Whether to enable the Zoom-In two-stage strategy (default: False)
        """
        if backend != "api":
            raise ValueError(f"GeminiInferencer only supports the 'api' backend, got '{backend}'")

        self._api_base = kwargs.get('api_base', None)
        self._api_key = kwargs.get('api_key', None)
        self._model_name = kwargs.get('model_name', None)
        self._use_zoom = kwargs.get('zoom', False)

        super().__init__(model_path, backend, **kwargs)

    def _init_model(self):
        """Initialize the Gemini API client."""
        from openai import OpenAI

        self.api_key = self._api_key
        self.api_base = self._api_base
        self.model_name = self._model_name

        self.temperature = 0.0
        self.max_tokens = 32768

        print(f"[GeminiInferencer] Initializing Gemini API client")
        print(f"  📡 API Base: {self.api_base}")
        print(f"  🤖 Model Name: {self.model_name}")
        print(f"  🔑 API Key: {self.api_key[:20]}...")
        print(f"  🌡️ Temperature: {self.temperature}")
        print(f"  📝 Max Tokens: {self.max_tokens}")
        print(f"  🔍 Zoom-In: {'Enabled (crop={}, resize={}x{})'.format(ZOOM_CROP_RATIO, ZOOM_RESIZE_W, ZOOM_RESIZE_H) if self._use_zoom else 'Disabled'}")
        if self._api_base or self._api_key or self._model_name:
            print(f"  📌 (Using config passed via script)")
        else:
            print(f"  📌 (Using default config)")

        # Create OpenAI-compatible client
        self.client = OpenAI(
            api_key=self.api_key,
            base_url=self.api_base,
        )

        print(f"  ✅ API client initialized successfully")

    def _build_messages(self, question: str, image: Image.Image) -> Any:
        """Build the messages payload for a single inference call (internal helper)."""
        img_base64 = pil_to_base64(image)
        img_url = f"data:image/png;base64,{img_base64}"
        messages = [
            {"role": "user", "content": GEMINI_SYSTEM_PROMPT},
            {"role": "user", "content": f"The user's element description is: {question}"},
            {"role": "user", "content": [{"type": "image_url", "image_url": {"url": img_url}}]},
        ]
        return messages

    def _build_prompt(self, question: str, image: Image.Image, system_prompts: list = None) -> Any:
        """Build the Gemini API input (called by base_inferencer for the non-zoom path)."""
        return self._build_messages(question, image)

    def _generate(self, inputs: Any) -> dict:
        """Call the Gemini API to run inference.

        Args:
            inputs: Messages list in OpenAI format

        Returns:
            {"prediction": content, "response": response_dict} or None on failure
        """
        last_error = None

        for attempt in range(MAX_RETRIES):
            try:
                response = self.client.chat.completions.create(
                    model=self.model_name,
                    messages=inputs,
                    max_completion_tokens=self.max_tokens,
                    temperature=self.temperature,
                )
                content = response.choices[0].message.content.strip()
                print(response)

                try:
                    response_dict = response.model_dump()
                except Exception:
                    response_dict = str(response)

                return {"prediction": content, "response": response_dict}

            except Exception as e:
                last_error = e
                err_str = str(e)
                is_rate_limit = "429" in err_str or "limit_requests" in err_str or "rate limit" in err_str.lower()

                if is_rate_limit:
                    wait = RATE_LIMIT_WAIT + RETRY_BASE_DELAY ** attempt
                    print(f"[Attempt {attempt+1}/{MAX_RETRIES}] Rate limit (429), waiting {wait:.0f}s before retry...")
                    time.sleep(wait)
                else:
                    print(f"[Attempt {attempt+1}/{MAX_RETRIES}] API error: {type(e).__name__}: {e}")
                    if attempt < MAX_RETRIES - 1:
                        delay = RETRY_BASE_DELAY ** attempt
                        time.sleep(delay)

        print(f"All {MAX_RETRIES} attempts failed. Last error: {last_error}")
        return None

    def _post_process(self, output: str) -> str:
        """Post-process the output (pass-through)."""
        if isinstance(output, str):
            return output.strip()
        return output

    # ------------------------------------------------------------------
    # Helper methods
    # ------------------------------------------------------------------

    def _error_result(self, sample_id: str, start_time: float,
                      prediction: str, error: str) -> dict:
        inference_time = time.time() - start_time
        return {
            "id": sample_id,
            "model_type": self.model_type,
            "model_response": prediction,
            "prediction": prediction,
            "response": None,
            "inference_time": inference_time,
            "error": error,
        }

    # ------------------------------------------------------------------
    # Main inference entry point: infer_single
    # ------------------------------------------------------------------

    def infer_single(self, sample: dict) -> dict:
        """
        Run inference on a single sample.

        - zoom=False: single-stage, infer directly on the original image
        - zoom=True:  two-stage Zoom-In
            Stage 1: infer on original image → obtain 0-1000 coordinates
            Stage 2: crop 25% region centered on Stage 1 prediction → resize to 1920×1080
                     → infer again → map coordinates back to original image (0-1000)

        Args:
            sample: {"id", "image", "question", ...}

        Returns:
            Inference result dict
        """
        start_time = time.time()

        sample_id = sample.get("id", "unknown")
        image_path = sample.get("image")
        instruction = sample.get("question", "")

        # ---------- Path check ----------
        if not image_path:
            return self._error_result(sample_id, start_time, "Empty image path", "Empty image path")

        import os
        if not os.path.exists(image_path):
            msg = f"Image file not found: {image_path}"
            return self._error_result(sample_id, start_time, f"Error: {msg}", msg)

        try:
            original_image = Image.open(image_path).convert("RGB")
            orig_w, orig_h = original_image.size

            # Stage 1: infer on the original image (no resize for Gemini)
            messages_s1 = self._build_messages(instruction, original_image)
            result_s1 = self._generate(messages_s1)

            if result_s1 is None:
                return self._error_result(sample_id, start_time,
                                          "Error: API error in Stage 1", "API call failed (Stage 1)")

            pred_s1 = result_s1["prediction"]

            # ---------- Non-zoom mode: return directly ----------
            if not self._use_zoom:
                inference_time = time.time() - start_time
                return {
                    "id": sample_id,
                    "model_type": self.model_type,
                    "model_response": pred_s1,
                    "prediction": pred_s1,
                    "response": result_s1["response"],
                    "inference_time": inference_time,
                }

            # ---------- Zoom mode: Stage 2 ----------
            coord_s1 = parse_coord_from_response(pred_s1)

            if coord_s1 is None:
                # Stage 1 parsing failed; fall back to Stage 1 result
                print(f"  ⚠️  Stage 1 coord parse failed, fallback to Stage 1 result")
                inference_time = time.time() - start_time
                return {
                    "id": sample_id,
                    "model_type": self.model_type,
                    "model_response": pred_s1,
                    "prediction": pred_s1,
                    "response": result_s1["response"],
                    "inference_time": inference_time,
                    "zoom_info": {
                        "zoom_enabled": True,
                        "stage1_parse_failed": True,
                        "fallback": "stage1",
                    },
                }

            s1_x, s1_y = coord_s1
            print(f"  📐 Stage1 coord: ({s1_x:.1f}, {s1_y:.1f}) on {orig_w}×{orig_h}")

            # Crop 25% region centered on Stage 1 prediction and resize to 1920×1080
            image_s2, crop_info = crop_and_zoom(
                original_image,
                center_x_norm=s1_x,
                center_y_norm=s1_y,
                crop_ratio=ZOOM_CROP_RATIO,
                resize_to=(ZOOM_RESIZE_W, ZOOM_RESIZE_H),
            )
            print(f"  🔍 Stage2 crop: left={crop_info['left']:.1f}, top={crop_info['top']:.1f}, "
                  f"w={crop_info['crop_width']:.1f}, h={crop_info['crop_height']:.1f} "
                  f"-> {ZOOM_RESIZE_W}×{ZOOM_RESIZE_H}")

            messages_s2 = self._build_messages(instruction, image_s2)
            result_s2 = self._generate(messages_s2)

            if result_s2 is None:
                # Stage 2 failed; fall back to Stage 1 result
                print(f"  ⚠️  Stage 2 API failed, fallback to Stage 1 result")
                inference_time = time.time() - start_time
                return {
                    "id": sample_id,
                    "model_type": self.model_type,
                    "model_response": pred_s1,
                    "prediction": pred_s1,
                    "response": result_s1["response"],
                    "inference_time": inference_time,
                    "zoom_info": {
                        "zoom_enabled": True,
                        "stage1_coord": [s1_x, s1_y],
                        "stage2_api_failed": True,
                        "fallback": "stage1",
                    },
                }

            pred_s2 = result_s2["prediction"]
            coord_s2 = parse_coord_from_response(pred_s2)

            if coord_s2 is None:
                # Stage 2 parsing failed; fall back to Stage 1 result
                print(f"  ⚠️  Stage 2 coord parse failed, fallback to Stage 1 result")
                inference_time = time.time() - start_time
                return {
                    "id": sample_id,
                    "model_type": self.model_type,
                    "model_response": pred_s1,
                    "prediction": pred_s1,
                    "response": result_s1["response"],
                    "inference_time": inference_time,
                    "zoom_info": {
                        "zoom_enabled": True,
                        "stage1_coord": [s1_x, s1_y],
                        "stage2_parse_failed": True,
                        "stage2_raw": pred_s2,
                        "fallback": "stage1",
                    },
                }

            s2_x, s2_y = coord_s2

            # Map Stage 2 0-1000 coordinates back to original image 0-1000 coordinates
            final_x, final_y = convert_zoomed_coord_to_original(s2_x, s2_y, crop_info)

            # Build final prediction string (same format as raw model output, with remapped coords)
            final_prediction = f"({int(round(final_x))}, {int(round(final_y))})"

            print(f"  ✅ Stage2 coord: ({s2_x:.1f}, {s2_y:.1f}) -> original: ({final_x:.1f}, {final_y:.1f})")

            inference_time = time.time() - start_time
            return {
                "id": sample_id,
                "model_type": self.model_type,
                # model_response stores the remapped coordinate string; judge can parse it directly
                "model_response": final_prediction,
                "prediction": final_prediction,
                "response": result_s2["response"],
                "inference_time": inference_time,
                "zoom_info": {
                    "zoom_enabled": True,
                    "crop_ratio": ZOOM_CROP_RATIO,
                    "zoom_resize": [ZOOM_RESIZE_W, ZOOM_RESIZE_H],
                    "stage1_coord": [s1_x, s1_y],
                    "stage1_raw": pred_s1,
                    "crop_info": {
                        "left": crop_info['left'],
                        "top": crop_info['top'],
                        "crop_width": crop_info['crop_width'],
                        "crop_height": crop_info['crop_height'],
                    },
                    "stage2_coord": [s2_x, s2_y],
                    "stage2_raw": pred_s2,
                    "final_coord": [final_x, final_y],
                },
            }

        except FileNotFoundError as e:
            return self._error_result(sample_id, start_time,
                                      f"Error: Image file not found: {image_path}",
                                      f"Image file not found: {image_path}")
        except Exception as e:
            return self._error_result(sample_id, start_time,
                                      f"Error: {e}", str(e))
