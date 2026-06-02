"""Screenshot utilities for capturing HarmonyOS device screen."""

import base64
import os
import subprocess
import tempfile
import uuid
from dataclasses import dataclass
from io import BytesIO
from typing import Tuple

from PIL import Image
from phone_agent.hdc.connection import _run_hdc_command


@dataclass
class Screenshot:
    """Represents a captured screenshot."""

    base64_data: str
    width: int
    height: int
    is_sensitive: bool = False
    error_message: str = ""


def get_screenshot(device_id: str | None = None, timeout: int = 10) -> Screenshot:
    """
    Capture a screenshot from the connected HarmonyOS device.

    Args:
        device_id: Optional HDC device ID for multi-device setups.
        timeout: Timeout in seconds for screenshot operations.

    Returns:
        Screenshot object containing base64 data and dimensions.

    Note:
        If the screenshot fails (e.g., on sensitive screens like payment pages),
        a black fallback image is returned with is_sensitive=True.
    """
    temp_path = os.path.join(tempfile.gettempdir(), f"screenshot_{uuid.uuid4()}.png")
    hdc_prefix = _get_hdc_prefix(device_id)

    try:
        # Execute screenshot command
        # HarmonyOS HDC only supports JPEG format
        remote_path = "/data/local/tmp/tmp_screenshot.jpeg"

        # Try method 1: hdc shell screenshot (newer HarmonyOS versions)
        result = _run_hdc_command(
            hdc_prefix + ["shell", "screenshot", remote_path],
            capture_output=True,
            text=True,
            timeout=timeout,
        )

        # Check for screenshot failure. Not every HDC failure is a sensitive
        # screen; stale sessions and missing connect keys should be surfaced as
        # device errors instead of sending a black image to the model.
        output = result.stdout + result.stderr
        if "fail" in output.lower() or "error" in output.lower() or "not found" in output.lower():
            # Try method 2: snapshot_display (older versions or different devices)
            result = _run_hdc_command(
                hdc_prefix + ["shell", "snapshot_display", "-f", remote_path],
                capture_output=True,
                text=True,
                timeout=timeout,
            )
            output = result.stdout + result.stderr
            if "fail" in output.lower() or "error" in output.lower():
                return _create_fallback_screenshot(
                    is_sensitive=_is_sensitive_capture_failure(output),
                    error_message=_clean_error(output) or "HDC screenshot command failed.",
                )

        # Pull screenshot to local temp path
        # Note: remote file is JPEG, but PIL can open it regardless of local extension
        recv_result = _run_hdc_command(
            hdc_prefix + ["file", "recv", remote_path, temp_path],
            capture_output=True,
            text=True,
            timeout=5,
        )

        if not os.path.exists(temp_path):
            recv_output = recv_result.stdout + recv_result.stderr
            return _create_fallback_screenshot(
                is_sensitive=_is_sensitive_capture_failure(recv_output),
                error_message=_clean_error(recv_output) or "HDC did not return a screenshot file.",
            )

        # Read JPEG image and convert to PNG for model inference
        # PIL automatically detects the image format from file content
        img = Image.open(temp_path)
        width, height = img.size

        buffered = BytesIO()
        img.save(buffered, format="PNG")
        base64_data = base64.b64encode(buffered.getvalue()).decode("utf-8")

        # Cleanup
        os.remove(temp_path)

        return Screenshot(
            base64_data=base64_data, width=width, height=height, is_sensitive=False
        )

    except Exception as e:
        print(f"Screenshot error: {e}")
        return _create_fallback_screenshot(is_sensitive=False, error_message=str(e))


def _get_hdc_prefix(device_id: str | None) -> list:
    """Get HDC command prefix with optional device specifier."""
    if device_id:
        return ["hdc", "-t", device_id]
    return ["hdc"]


def _create_fallback_screenshot(is_sensitive: bool, error_message: str = "") -> Screenshot:
    """Create a black fallback image when screenshot fails."""
    default_width, default_height = 1080, 2400

    black_img = Image.new("RGB", (default_width, default_height), color="black")
    buffered = BytesIO()
    black_img.save(buffered, format="PNG")
    base64_data = base64.b64encode(buffered.getvalue()).decode("utf-8")

    return Screenshot(
        base64_data=base64_data,
        width=default_width,
        height=default_height,
        is_sensitive=is_sensitive,
        error_message=error_message,
    )


def _is_sensitive_capture_failure(output: str) -> bool:
    """Return True only for messages that look like screenshot protection."""
    text = (output or "").lower()
    connection_markers = [
        "bind tartget session is dead",
        "bind target session is dead",
        "need connect-key",
        "connect key",
        "no devices",
        "device not found",
        "not connected",
        "offline",
    ]
    if any(marker in text for marker in connection_markers):
        return False
    sensitive_markers = [
        "secure",
        "permission denied",
        "protected",
        "privacy",
        "sensitive",
        "not allow",
        "not permitted",
    ]
    return any(marker in text for marker in sensitive_markers)


def _clean_error(output: str) -> str:
    return " ".join((output or "").replace("\r", " ").replace("\n", " ").split())
