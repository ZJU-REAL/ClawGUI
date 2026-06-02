"""Metadata-first skill retrieval with divergence-aware scoring."""

from __future__ import annotations

from dataclasses import dataclass

from clawgui_skills.intent import is_informative_token, task_signature, tokenize
from clawgui_skills.package import SkillPackage


@dataclass
class ScoredSkill:
    skill: SkillPackage
    score: float
    reason: str


class SkillRetriever:
    """Keyword/BM25-lite retriever adapted from EvoSkill-GUI.

    The score is recall-dominant and subtracts a divergence penalty only for
    informative concept tokens. Transient parameters such as message bodies,
    numbers, and quoted text should not prevent a skill from being reused.
    """

    def __init__(
        self,
        app_bonus: float = 0.15,
        keyword_bonus_step: float = 0.05,
        keyword_bonus_cap: float = 0.2,
        divergence_weight: float = 0.3,
    ):
        self.app_bonus = app_bonus
        self.keyword_bonus_step = keyword_bonus_step
        self.keyword_bonus_cap = keyword_bonus_cap
        self.divergence_weight = divergence_weight

    def retrieve(
        self,
        query: str,
        skills: list[SkillPackage],
        *,
        current_app: str = "",
        platform: str = "",
        threshold: float = 0.35,
        top_k: int = 3,
    ) -> list[ScoredSkill]:
        scored = [self.score(query, skill, current_app=current_app, platform=platform) for skill in skills]
        scored = [item for item in scored if item.skill.meta.enabled and item.score >= threshold]
        scored.sort(key=lambda item: item.score, reverse=True)
        return scored[:top_k]

    def best_match(
        self,
        query: str,
        skills: list[SkillPackage],
        *,
        current_app: str = "",
        platform: str = "",
        threshold: float = 0.35,
    ) -> ScoredSkill | None:
        matches = self.retrieve(
            query,
            skills,
            current_app=current_app,
            platform=platform,
            threshold=threshold,
            top_k=1,
        )
        return matches[0] if matches else None

    def score(
        self,
        query: str,
        skill: SkillPackage,
        *,
        current_app: str = "",
        platform: str = "",
    ) -> ScoredSkill:
        meta = skill.meta
        query_tokens = set(tokenize(query))
        doc_tokens = set(self._build_doc_tokens(skill))
        query_sig = task_signature(query)
        doc_sig = task_signature(
            meta.task_intent,
            apps=meta.domain_app,
            arguments=meta.arguments,
        )

        signature_overlap = query_sig.labels & doc_sig.labels
        signature_score = self._signature_score(query_sig.labels, doc_sig.labels)

        if not query_tokens or not doc_tokens:
            if signature_score:
                return ScoredSkill(
                    skill,
                    round(signature_score, 4),
                    f"signature_only={sorted(signature_overlap)}",
                )
            return ScoredSkill(skill, 0.0, "empty query or metadata")

        overlap = query_tokens & doc_tokens
        jaccard = len(overlap) / max(1, len(query_tokens | doc_tokens))
        recall = len(overlap) / max(1, len(query_tokens))
        base = 0.6 * recall + 0.4 * jaccard

        app_bonus = self._app_bonus(query, current_app, meta.domain_app)
        keyword_bonus = self._keyword_bonus(query, meta.keywords)
        divergence_penalty = self._divergence_penalty(query_tokens, doc_tokens, signature_score)
        platform_bonus = 0.04 if platform and meta.platform.lower() == platform.lower() else 0.0

        raw = max(base, signature_score) + app_bonus + keyword_bonus + platform_bonus - divergence_penalty
        score = max(0.0, min(1.0, raw))
        reason = (
            f"overlap={len(overlap)}, recall={recall:.2f}, jaccard={jaccard:.2f}, "
            f"signature={signature_score:.2f}, signature_overlap={sorted(signature_overlap)}, "
            f"divergence_penalty={divergence_penalty:.2f}, "
            f"app_bonus={app_bonus:.2f}, keyword_bonus={keyword_bonus:.2f}"
        )
        return ScoredSkill(skill, round(score, 4), reason)

    @staticmethod
    def _build_doc_tokens(skill: SkillPackage) -> list[str]:
        meta = skill.meta
        parts = [
            meta.task_intent,
            " ".join(meta.keywords or []),
            " ".join(meta.domain_app or []),
            " ".join(meta.arguments or []),
        ]
        return tokenize(" ".join(part for part in parts if part))

    @staticmethod
    def _signature_score(query_labels: set[str], doc_labels: set[str]) -> float:
        if not query_labels or not doc_labels:
            return 0.0
        overlap = query_labels & doc_labels
        score = len(overlap) / max(1, len(query_labels))
        query_ops = {x for x in query_labels if x.startswith("op:")}
        doc_ops = {x for x in doc_labels if x.startswith("op:")}
        query_apps = {x for x in query_labels if x.startswith("app:")}
        doc_apps = {x for x in doc_labels if x.startswith("app:")}
        if query_ops and query_ops & doc_ops:
            score = max(score, 0.72)
            if query_apps and query_apps & doc_apps:
                score = max(score, 0.86)
            if "op:send_message" in query_ops and "op:send_message" in doc_ops:
                score = max(score, 0.72)
            query_target = {x for x in query_labels if x.startswith("target:")}
            doc_target = {x for x in doc_labels if x.startswith("target:")}
            if query_target and query_target & doc_target:
                score = max(score, 0.86)
        return min(1.0, score)

    def _app_bonus(self, query: str, current_app: str, domain_apps: list[str]) -> float:
        if not domain_apps:
            return 0.0
        query_tokens = set(tokenize(query))
        current_tokens = set(tokenize(current_app))
        for app in domain_apps:
            app_tokens = set(tokenize(app))
            if app_tokens and (app_tokens & query_tokens or app_tokens & current_tokens):
                return self.app_bonus
        return 0.0

    def _keyword_bonus(self, query: str, keywords: list[str]) -> float:
        if not keywords:
            return 0.0
        normalized_query = " ".join(tokenize(query))
        hits = 0
        for keyword in keywords:
            key = " ".join(tokenize(keyword))
            if key and key in normalized_query:
                hits += 1
        return min(self.keyword_bonus_cap, hits * self.keyword_bonus_step)

    def _divergence_penalty(
        self,
        query_tokens: set[str],
        doc_tokens: set[str],
        signature_score: float,
    ) -> float:
        query_concepts = {t for t in query_tokens if is_informative_token(t)}
        doc_concepts = {t for t in doc_tokens if is_informative_token(t)}
        if not query_concepts:
            return 0.0
        missing_ratio = len(query_concepts - doc_concepts) / max(1, len(query_concepts))
        penalty = self.divergence_weight * missing_ratio
        if signature_score >= 0.66:
            penalty *= 0.25
        return penalty
