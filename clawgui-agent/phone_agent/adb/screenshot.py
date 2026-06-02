"""Screenshot utilities for capturing Android device screen."""

import base64
import os
import subprocess
import tempfile
import uuid
from dataclasses import dataclass
from io import BytesIO
from typing import Tuple

from PIL import Image


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
    Capture a screenshot from the connected Android device.

    Args:
        device_id: Optional ADB device ID for multi-device setups.
        timeout: Timeout in seconds for screenshot operations.

    Returns:
        Screenshot object containing base64 data and dimensions.

    Note:
        If the screenshot fails (e.g., on sensitive screens like payment pages),
        a black fallback image is returned with is_sensitive=True.
    """
    temp_path = os.path.join(tempfile.gettempdir(), f"screenshot_{uuid.uuid4()}.png")
    adb_prefix = _get_adb_prefix(device_id)

    try:
        # Execute screenshot command
        result = subprocess.run(
            adb_prefix + ["shell", "screencap", "-p", "/sdcard/tmp.png"],
            capture_output=True,
            text=True,
            timeout=timeout,
        )

        # Check for screenshot failure (sensitive screen)
        output = result.stdout + result.stderr
        if "Status: -1" in output or "Failed" in output:
            return _create_fallback_screenshot(
                is_sensitive=_is_sensitive_capture_failure(output),
                error_message=_clean_error(output) or "ADB screenshot command failed.",
            )

        # Pull screenshot to local temp path
        pull_result = subprocess.run(
            adb_prefix + ["pull", "/sdcard/tmp.png", temp_path],
            capture_output=True,
            text=True,
            timeout=5,
        )

        if not os.path.exists(temp_path):
            pull_output = pull_result.stdout + pull_result.stderr
            return _create_fallback_screenshot(
                is_sensitive=_is_sensitive_capture_failure(pull_output),
                error_message=_clean_error(pull_output) or "ADB did not return a screenshot file.",
            )

        # Read and encode image
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


def _get_adb_prefix(device_id: str | None) -> list:
    """Get ADB command prefix with optional device specifier."""
    if device_id:
        return ["adb", "-s", device_id]
    return ["adb"]


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
        "no devices",
        "device not found",
        "offline",
        "unauthorized",
        "more than one device",
        "closed",
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
        "status: -1",
    ]
    return any(marker in text for marker in sensitive_markers)


def _clean_error(output: str) -> str:
    return " ".join((output or "").replace("\r", " ").replace("\n", " ").split())
