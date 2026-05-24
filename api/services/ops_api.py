from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass


@dataclass(frozen=True)
class OpsApiService:
    cached: Callable
    clear_runtime_state: Callable[[], None]
    pipeline_runs_payload: Callable[[int], dict]
    research_health_payload: Callable[[int], dict]
    data_quality_payload: Callable[[int | None], dict]
    data_source_payload: Callable[[], dict]
    performance_payload: Callable[[int], dict]
    cache_warm_state_payload: Callable[[], dict]
    cache_warm_payload: Callable[[], dict]
    ops_health_payload: Callable[[int], dict]

    def pipeline_runs(self, limit: int = 20) -> dict:
        safe_limit = max(1, min(int(limit or 20), 100))
        return self.cached(f"ops_pipeline_runs_{safe_limit}", lambda: self.pipeline_runs_payload(safe_limit), ttl=15)

    def research_health(self, max_age_hours: int = 84) -> dict:
        safe_hours = max(1, min(int(max_age_hours or 84), 240))
        return self.cached(f"ops_research_health_{safe_hours}", lambda: self.research_health_payload(safe_hours), ttl=15)

    def data_quality(self, max_age_days: int = 0) -> dict:
        safe_days = None if int(max_age_days or 0) <= 0 else max(1, min(int(max_age_days), 90))
        cache_key = safe_days if safe_days is not None else "default"
        return self.cached(f"ops_data_quality_{cache_key}", lambda: self.data_quality_payload(safe_days), ttl=15)

    def data_sources(self) -> dict:
        return self.data_source_payload()

    def performance(self, limit: int = 40) -> dict:
        safe_limit = max(1, min(int(limit or 40), 200))
        return self.performance_payload(safe_limit)

    def cache_warm(self) -> dict:
        return self.cache_warm_payload()

    def cache_warm_state(self) -> dict:
        return self.cache_warm_state_payload()

    def health(self, max_research_age_hours: int = 84) -> dict:
        safe_hours = max(1, min(int(max_research_age_hours or 84), 240))
        return self.cached(f"ops_health_{safe_hours}", lambda: self.ops_health_payload(safe_hours), ttl=15)

    def cache_clear(self) -> dict:
        self.clear_runtime_state()
        return {"cleared": True}
