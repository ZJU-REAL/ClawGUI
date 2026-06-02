"""Data schemas for self-evolving GUI skills."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from typing import Any


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class EvolutionStatus:
    usage_count: int = 0
    success_count: int = 0
    revision_count: int = 0
    last_used_at: str = ""
    last_updated_at: str = ""

    @property
    def success_rate(self) -> float:
        if self.usage_count <= 0:
            return 0.0
        return round(self.success_count / self.usage_count, 4)

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["success_rate"] = self.success_rate
        return data

    @classmethod
    def from_dict(cls, data: dict[str, Any] | None) -> "EvolutionStatus":
        data = dict(data or {})
        data.pop("success_rate", None)
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})


@dataclass
class SkillMeta:
    skill_id: str
    display_name: str
    task_intent: str
    domain_app: list[str] = field(default_factory=list)
    platform: str = "Android"
    keywords: list[str] = field(default_factory=list)
    arguments: list[str] = field(default_factory=list)
    failure_history_summary: str = ""
    enabled: bool = True
    created_at: str = field(default_factory=now_iso)
    updated_at: str = field(default_factory=now_iso)
    evolution_status: EvolutionStatus = field(default_factory=EvolutionStatus)

    def to_dict(self) -> dict[str, Any]:
        return {
            "skill_id": self.skill_id,
            "display_name": self.display_name,
            "task_intent": self.task_intent,
            "domain_app": list(self.domain_app),
            "platform": self.platform,
            "keywords": list(self.keywords),
            "arguments": list(self.arguments),
            "failure_history_summary": self.failure_history_summary,
            "enabled": self.enabled,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "evolution_status": self.evolution_status.to_dict(),
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "SkillMeta":
        data = dict(data)
        data["evolution_status"] = EvolutionStatus.from_dict(data.get("evolution_status"))
        if "display_name" not in data:
            data["display_name"] = data.get("skill_id", "Untitled skill").replace("_", " ").title()
        return cls(**{k: v for k, v in data.items() if k in cls.__dataclass_fields__})


@dataclass
class RetrievalResult:
    skill_id: str
    display_name: str
    score: float
    reason: str = ""


@dataclass
class SkillPrepareResult:
    mode: str
    status: str
    context: str = ""
    skill_id: str = ""
    display_name: str = ""
    retrieval_score: float = 0.0
    summary: str = ""
    skill_root: str = ""

    @property
    def has_skill(self) -> bool:
        return bool(self.skill_id)


@dataclass
class VerifierFeedback:
    success: bool
    failure_type: str = ""
    failed_step: int | None = None
    root_cause: str = ""
    diagnosis: str = ""
    suggestions: list[str] = field(default_factory=list)
    state_assertions: list[str] = field(default_factory=list)
    raw: str = ""

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data.pop("raw", None)
        return data


@dataclass
class SkillFinishResult:
    mode: str
    success: bool
    skill_id: str = ""
    display_name: str = ""
    feedback: dict[str, Any] | None = None
    edits: list[dict[str, Any]] = field(default_factory=list)
    summary: str = ""
