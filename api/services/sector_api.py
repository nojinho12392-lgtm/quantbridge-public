from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from fastapi import HTTPException

from api.services.company_names import apply_localized_names


@dataclass(frozen=True)
class SectorApiService:
    cached: Callable
    invalidate: Callable[[str], None]
    payload: Callable[..., dict]

    def sector_themes(self, market: str = "ALL", limit: int = 36, members: int = 120, refresh: bool = False) -> dict:
        safe_market = str(market or "ALL").upper()
        if safe_market not in {"ALL", "US", "KR"}:
            safe_market = "ALL"
        safe_limit = max(1, min(int(limit or 36), 72))
        safe_members = max(1, min(int(members or 120), 240))
        cache_key = f"sector_themes_{safe_market}_{safe_limit}_{safe_members}"
        if refresh:
            self.invalidate(cache_key)
        return self.cached(
            cache_key,
            lambda: apply_localized_names(self.payload(safe_market, safe_limit, safe_members)),
            ttl=900,
        )

    def sector_theme_summary(self, market: str = "ALL", limit: int = 36, refresh: bool = False) -> dict:
        safe_market = self._safe_market(market)
        safe_limit = max(1, min(int(limit or 36), 72))
        cache_key = f"sector_theme_summary_{safe_market}_{safe_limit}"
        if refresh:
            self.invalidate(cache_key)

        def load() -> dict:
            payload = self.sector_themes(safe_market, limit=safe_limit, members=12, refresh=refresh)
            return self._summary_payload(payload)

        return self.cached(cache_key, load, ttl=900)

    def sector_theme_detail(
        self,
        label: str,
        market: str = "ALL",
        members: int = 200,
        refresh: bool = False,
    ) -> dict:
        safe_label = str(label or "").strip()
        if not safe_label:
            raise HTTPException(400, "label is required")
        safe_market = self._safe_market(market)
        safe_members = max(1, min(int(members or 200), 240))
        label_key = self._label_key(safe_label)
        cache_key = f"sector_theme_detail_{safe_market}_{label_key}_{safe_members}"
        if refresh:
            self.invalidate(cache_key)

        def load() -> dict:
            wanted_keys = self._label_candidate_keys(safe_label)
            payload = self.payload(safe_market, 72, safe_members, safe_label)
            item = self._find_theme(payload, wanted_keys)
            if item is None and not refresh:
                summary_payload = self.sector_theme_summary(safe_market, limit=72, refresh=False)
                summary_item = self._find_theme(summary_payload, wanted_keys)
                if summary_item is not None:
                    resolved_label = str(summary_item.get("label") or safe_label)
                    payload = self.payload(safe_market, 72, safe_members, resolved_label)
                    item = self._find_theme(payload, {self._label_key(resolved_label), *wanted_keys})
            if item is None:
                raise HTTPException(404, "sector theme not found")
            return {
                "market": payload.get("market") or safe_market,
                "generated_at": payload.get("generated_at"),
                "item": apply_localized_names(self._trim_members(item, safe_members)),
            }

        return self.cached(cache_key, load, ttl=900)

    @classmethod
    def _safe_market(cls, market: str) -> str:
        safe_market = str(market or "ALL").upper()
        if safe_market not in {"ALL", "US", "KR"}:
            safe_market = "ALL"
        return safe_market

    @staticmethod
    def _label_key(label: object) -> str:
        return " ".join(str(label or "").strip().lower().split())

    @classmethod
    def _label_candidates(cls, label: object) -> list[str]:
        raw = str(label or "").strip()
        candidates: list[str] = []

        def add(value: object) -> None:
            clean = str(value or "").strip()
            if clean and clean not in candidates:
                candidates.append(clean)

        add(raw)
        for separator in ("/", "|", ",", "·"):
            if separator in raw:
                for part in raw.split(separator):
                    add(part)
        return candidates

    @classmethod
    def _label_candidate_keys(cls, label: object) -> set[str]:
        return {cls._label_key(candidate) for candidate in cls._label_candidates(label)}

    @classmethod
    def _focus_label(cls, label: object) -> str:
        return cls._label_candidates(label)[0]

    @classmethod
    def _find_theme(cls, payload: dict, wanted_keys: set[str]) -> dict | None:
        return next(
            (
                theme
                for theme in payload.get("items", [])
                if cls._label_key(theme.get("label")) in wanted_keys
            ),
            None,
        )

    @staticmethod
    def _trim_members(item: dict, members: int) -> dict:
        item_members = item.get("members")
        if not isinstance(item_members, list) or len(item_members) <= members:
            return item
        trimmed = dict(item)
        trimmed["members"] = item_members[:members]
        return trimmed

    @staticmethod
    def _summary_payload(payload: dict) -> dict:
        items = []
        for theme in payload.get("items", []):
            if not isinstance(theme, dict):
                continue
            summary = {
                key: value
                for key, value in theme.items()
                if key != "members"
            }
            summary["members"] = []
            items.append(summary)
        return {
            "market": payload.get("market") or "ALL",
            "generated_at": payload.get("generated_at"),
            "count": len(items),
            "items": items,
            "mode": "summary",
        }
