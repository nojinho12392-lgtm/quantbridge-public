from __future__ import annotations

import os
from typing import Callable


APP_SNAPSHOT_KEY_PREFIXES = (
    "port_",
    "portfolio_prices_",
    "sc_",
    "scored_",
    "search_universe_",
    "earn_",
    "earnings_calendar_",
    "signal_events_",
    "comparison_recommendations_",
    "market_indicators_",
    "market_indicator_history_",
    "etfs_daily_",
    "etf_detail_",
    "sector_theme_",
    "sector_themes_",
    "stock_",
    "news_issues_",
    "macro",
)


def env_enabled(name: str, default: bool = True) -> bool:
    raw = str(os.environ.get(name, "1" if default else "0")).strip().lower()
    return raw not in {"0", "false", "no", "off"}


class ApiSnapshotCache:
    def __init__(
        self,
        *,
        settings: object,
        repository: Callable[[], object],
        runtime_cached: Callable[..., object],
        runtime_invalidate: Callable[[str], None],
    ) -> None:
        self._settings = settings
        self._repository = repository
        self._runtime_cached = runtime_cached
        self._runtime_invalidate = runtime_invalidate

    def cached(self, key: str, loader, ttl: int | None = None, stale_ttl: int | None = None):
        effective_stale_ttl = 0 if ttl is not None and stale_ttl is None else stale_ttl

        def load_with_app_snapshot():
            snapshot = self.read_snapshot(key)
            if snapshot is not None:
                return snapshot
            result = loader()
            self.write_snapshot(key, result)
            return result

        return self._runtime_cached(key, load_with_app_snapshot, ttl=ttl, stale_ttl=effective_stale_ttl)

    def invalidate(self, key: str) -> None:
        self._runtime_invalidate(key)

    def read_snapshot(self, cache_key: str) -> object | None:
        if not env_enabled("QUANT_API_READ_APP_SNAPSHOTS", True):
            return None
        if not getattr(self._settings, "enable_postgres", False) or not self._is_app_snapshot_key(cache_key):
            return None
        try:
            return self._repository().read_app_api_snapshot(
                cache_key,
                max_age_seconds=self._app_snapshot_max_age_seconds(cache_key),
            )
        except Exception:
            return None

    def write_snapshot(self, cache_key: str, payload: object) -> None:
        if not env_enabled("QUANT_API_WRITE_APP_SNAPSHOTS", True):
            return
        if not getattr(self._settings, "enable_postgres", False) or not self._is_app_snapshot_key(cache_key):
            return
        try:
            self._repository().upsert_app_api_snapshot(cache_key, payload, source="api_precompute")
        except Exception:
            pass

    def _app_snapshot_max_age_seconds(self, cache_key: str | None = None) -> int | None:
        raw = str(os.environ.get("QUANT_API_APP_SNAPSHOT_MAX_AGE_SECONDS", "86400")).strip()
        if raw in {"", "0", "none", "None"}:
            return None
        try:
            max_age = max(60, int(raw))
        except ValueError:
            max_age = 86400
        if str(cache_key or "").startswith("stock_"):
            return min(max_age, 900)
        return max_age

    def _is_app_snapshot_key(self, key: str) -> bool:
        clean = str(key or "")
        if not clean:
            return False
        if clean.startswith(("news_image_url_", "naver_kr_stock_quotes_", "ops_", "research_")):
            return False
        return clean == "macro" or clean.startswith(APP_SNAPSHOT_KEY_PREFIXES)
