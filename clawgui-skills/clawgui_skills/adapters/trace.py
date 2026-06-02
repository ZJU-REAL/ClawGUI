"""Adapters for ClawGUI PhoneAgent trace directories."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def load_trace(trace_path: str | Path | None) -> dict[str, Any]:
    if not trace_path:
        return {}
    path = Path(trace_path)
    if path.is_dir():
        path = path / "episode.json"
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def summarize_trace(trace: dict[str, Any], max_steps: int = 8) -> str:
    steps = trace.get("episode") or []
    lines = []
    for step in steps[-max_steps:]:
        action = step.get("action") or {}
        action_name = action.get("action") or action.get("action_type") or action.get("_metadata") or "unknown"
        finished = step.get("finished", False)
        lines.append(f"- step {step.get('step')}: action={action_name}, finished={finished}")
    return "\n".join(lines)
