"""Restricted file tools for skill revision."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from clawgui_skills.package import SkillPackage


ALLOWED_DOCS = {
    "docs/plan.md",
    "docs/backup.md",
    "docs/recover.md",
}


class RestrictedSkillFileTools:
    """Small, auditable file API scoped to one skill package."""

    def __init__(self, skill: SkillPackage):
        self.skill = skill

    def read_file(self, path: str) -> str:
        target = self._resolve(path, read_only=True)
        return target.read_text(encoding="utf-8") if target.exists() else ""

    def write_file(self, path: str, content: str, reason: str = "") -> dict[str, Any]:
        target = self._resolve(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        payload = {"event": "write_file", "file": self._relative(target), "reason": reason}
        self.skill.record_edit(payload)
        return payload

    def append_file(self, path: str, content: str, reason: str = "") -> dict[str, Any]:
        target = self._resolve(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("a", encoding="utf-8") as f:
            if target.exists() and target.stat().st_size > 0:
                f.write("\n")
            f.write(content)
        payload = {"event": "append_file", "file": self._relative(target), "reason": reason}
        self.skill.record_edit(payload)
        return payload

    def replace_section(
        self,
        path: str,
        *,
        marker: str,
        content: str,
        reason: str = "",
    ) -> dict[str, Any]:
        """Replace a marked section while preserving the rest of a skill doc."""

        original = self.read_file(path)
        section = _marked_section(marker, content)
        start_marker = f"<!-- {marker}:start -->"
        end_marker = f"<!-- {marker}:end -->"
        start = original.find(start_marker)
        end = original.find(end_marker)
        if start >= 0 and end >= start:
            end += len(end_marker)
            updated = original[:start].rstrip() + "\n\n" + section + "\n" + original[end:].lstrip()
        else:
            updated = original.rstrip() + "\n\n" + section + "\n"
        return self.write_file(path, updated.strip() + "\n", reason=reason)

    def list_dir(self, path: str = ".") -> list[str]:
        target = self._resolve_dir(path)
        if not target.exists():
            return []
        return sorted(p.name for p in target.iterdir())

    def search_file(self, path: str, query: str) -> list[str]:
        text = self.read_file(path)
        q = query.lower()
        return [line for line in text.splitlines() if q in line.lower()]

    def dispatch(self, name: str, arguments: dict[str, Any], reason: str = "") -> dict[str, Any]:
        """Execute a paper-style file tool call against this skill package."""
        args = dict(arguments or {})
        if name == "read_file":
            return {"event": "read_file", "output": self.read_file(args.get("path", ""))}
        if name == "write_file":
            return self.write_file(args.get("path", ""), args.get("content", ""), reason=reason)
        if name == "append_file":
            return self.append_file(args.get("path", ""), args.get("content", ""), reason=reason)
        if name == "list_dir":
            return {"event": "list_dir", "entries": self.list_dir(args.get("path", ".") or ".")}
        if name == "search_file":
            pattern = args.get("pattern", args.get("query", ""))
            return {
                "event": "search_file",
                "matches": self.search_file(args.get("path", ""), pattern),
            }
        if name == "create_failure_example":
            return self.create_failure_example(args.get("content", ""), reason=reason)
        raise ValueError(f"Unknown skill file tool: {name}")

    def create_failure_example(self, content: str, reason: str = "") -> dict[str, Any]:
        path = self.skill.add_failure_example(content)
        payload = {
            "event": "create_failure_example",
            "file": self._relative(path),
            "reason": reason,
        }
        self.skill.record_edit(payload)
        return payload

    def _resolve(self, path: str, read_only: bool = False) -> Path:
        clean = path.replace("\\", "/").strip("/")
        if clean not in ALLOWED_DOCS and not clean.startswith("failure_examples/"):
            raise ValueError(f"Path is outside the editable skill surface: {path}")
        if clean.startswith("failure_examples/") and not read_only:
            raise ValueError("Use create_failure_example() to create failure examples")
        target = (self.skill.root / clean).resolve()
        root = self.skill.root.resolve()
        if root not in target.parents and target != root:
            raise ValueError(f"Resolved path escapes skill package: {path}")
        return target

    def _resolve_dir(self, path: str) -> Path:
        clean = path.replace("\\", "/").strip("/")
        allowed_dirs = {"", ".", "docs", "failure_examples", "versions"}
        if clean not in allowed_dirs:
            raise ValueError(f"Directory is outside the readable skill surface: {path}")
        target = (self.skill.root / clean).resolve()
        root = self.skill.root.resolve()
        if root not in target.parents and target != root:
            raise ValueError(f"Resolved path escapes skill package: {path}")
        return target

    def _relative(self, path: Path) -> str:
        return path.resolve().relative_to(self.skill.root.resolve()).as_posix()


def _marked_section(marker: str, content: str) -> str:
    clean = marker.replace("--", "-").strip()
    body = content.strip()
    return f"<!-- {clean}:start -->\n{body}\n<!-- {clean}:end -->"
