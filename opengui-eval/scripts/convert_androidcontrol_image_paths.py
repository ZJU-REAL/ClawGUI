#!/usr/bin/env python3
"""
Convert AndroidControl JSON files:
1. Change absolute image paths to relative paths (like mmbench-gui format)
2. Copy images to opengui-eval/image/androidcontrol/

Source path example:
  /apdcephfs_gy2/share_303242896/harveyshen/data/gui_data/AndroidControl/converted/episode_27/screenshot_0.png
Target relative path:
  image/androidcontrol/episode_27/screenshot_0.png
Target copy destination:
  /apdcephfs/private_xufeitang/test_opengui/OpenGUI/opengui-eval/image/androidcontrol/episode_27/screenshot_0.png
"""

import json
import os
import shutil
from pathlib import Path

DATA_DIR = "/apdcephfs/private_xufeitang/test_opengui/OpenGUI/opengui-eval/data"
IMAGE_DST_ROOT = "/apdcephfs/private_xufeitang/test_opengui/OpenGUI/opengui-eval/image/androidcontrol"

JSON_FILES = [
    "androidcontrol_high_qwen3vl.json",
    "androidcontrol_low_qwen3vl.json",
    "androidcontrol_test_high_qwen25vl.json",
    "androidcontrol_test_low_qwen25vl.json",
]

# e.g. "/apdcephfs_gy2/.../AndroidControl/converted/episode_27/screenshot_0.png"
#   -> "image/androidcontrol/episode_27/screenshot_0.png"
PREFIX_TO_STRIP = "/apdcephfs_gy2/share_303242896/harveyshen/data/gui_data/AndroidControl/converted/"


def convert_path(abs_path: str) -> str:
    if not abs_path.startswith(PREFIX_TO_STRIP):
        raise ValueError(f"Unexpected image path prefix: {abs_path}")
    relative_tail = abs_path[len(PREFIX_TO_STRIP):]  # e.g. "episode_27/screenshot_0.png"
    return f"image/androidcontrol/{relative_tail}"


def main():
    os.makedirs(IMAGE_DST_ROOT, exist_ok=True)

    copied_images = set()

    for json_file in JSON_FILES:
        json_path = os.path.join(DATA_DIR, json_file)
        print(f"\nProcessing {json_file} ...")

        with open(json_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        for item in data:
            old_path = item["image"]
            new_rel_path = convert_path(old_path)

            # Copy image if not already copied
            if old_path not in copied_images:
                dst_path = os.path.join(
                    "/apdcephfs/private_xufeitang/test_opengui/OpenGUI/opengui-eval",
                    new_rel_path,
                )
                os.makedirs(os.path.dirname(dst_path), exist_ok=True)
                if not os.path.exists(dst_path):
                    shutil.copy2(old_path, dst_path)
                    print(f"  Copied: {old_path} -> {dst_path}")
                copied_images.add(old_path)

            item["image"] = new_rel_path

        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

        print(f"  Updated {len(data)} entries in {json_file}")

    print(f"\nDone! Copied {len(copied_images)} unique images total.")


if __name__ == "__main__":
    main()
