"""GUI execution tracer — records each PhoneAgent episode to a structured directory."""

import base64
import json
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


class GUITracer:
    """
    Records PhoneAgent execution as structured episodes.

    Directory layout:
        <trace_dir>/
          <episode_id>/
            episode.json
            images/
              step0.png
              step1.png
              ...

    episode.json format:
        {
          "task_name": "...",
          "timestamp": "...",
          "episode": [
            {"step": 0, "model_output": "...", "action": {...}, "finished": false},
            ...
          ]
        }
    """

    def __init__(self, trace_dir: str = "gui_trace"):
        self.base_dir = Path(trace_dir).resolve()

        self._episode_id: str = ""
        self._task_name: str = ""
        self._start_timestamp: str = ""
        self._episode_dir: Path | None = None
        self._images_dir: Path | None = None
        self._steps: list[dict[str, Any]] = []

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def start_task(self, task: str, model: str = "") -> str:
        """Call at the beginning of PhoneAgent.run(). Returns episode_id."""
        self._episode_id = uuid.uuid4().hex[:8]
        self._task_name = task
        self._start_timestamp = _now()
        self._steps = []

        self._episode_dir = self.base_dir / self._episode_id
        self._images_dir = self._episode_dir / "images"
        self._episode_dir.mkdir(parents=True, exist_ok=True)
        self._images_dir.mkdir(parents=True, exist_ok=True)

        return self._episode_id

    def record_step(
        self,
        step: int,
        screenshot_base64: str,
        model_raw_output: str,
        action: dict[str, Any],
        finished: bool,
        **kwargs: Any,
    ) -> None:
        """Call at the end of PhoneAgent._execute_step()."""
        self._save_screenshot(step, screenshot_base64)
        self._steps.append({
            "step": step,
            "model_output": model_raw_output,
            "action": action,
            "finished": finished,
        })

    def end_task(self, result: str, total_steps: int) -> None:
        """Call when PhoneAgent.run() is about to return. Writes episode.json."""
        if not self._episode_dir:
            return
        episode = {
            "task_name": self._task_name,
            "timestamp": self._start_timestamp,
            "episode": self._steps,
        }
        episode_path = self._episode_dir / "episode.json"
        try:
            with open(episode_path, "w", encoding="utf-8") as f:
                json.dump(episode, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _save_screenshot(self, step: int, b64: str) -> Path | None:
        if not self._images_dir:
            return None
        path = self._images_dir / f"step{step}.png"
        try:
            path.write_bytes(base64.b64decode(b64))
            return path
        except Exception:
            return None


# ------------------------------------------------------------------
# Utilities
# ------------------------------------------------------------------

def _now() -> str:
    return datetime.now(timezone.utc).isoformat()
