from __future__ import annotations

from fastapi import APIRouter

from api.contracts.mobile_v1 import CacheClearResponse, CacheWarmResponse, OpsHealthResponse, OpsTableResponse
from api.services.ops_api import OpsApiService


def create_ops_router(service: OpsApiService) -> APIRouter:
    router = APIRouter()

    @router.get("/ops/pipeline-runs", response_model=OpsTableResponse)
    def ops_pipeline_runs(limit: int = 20):
        return service.pipeline_runs(limit=limit)

    @router.get("/ops/research-health", response_model=OpsTableResponse)
    def ops_research_health(max_age_hours: int = 84):
        return service.research_health(max_age_hours=max_age_hours)

    @router.get("/ops/data-quality", response_model=OpsTableResponse)
    def ops_data_quality(max_age_days: int = 0):
        return service.data_quality(max_age_days=max_age_days)

    @router.get("/ops/data-sources", response_model=OpsTableResponse)
    def ops_data_sources():
        return service.data_sources()

    @router.get("/ops/performance", response_model=OpsTableResponse)
    def ops_performance(limit: int = 40):
        return service.performance(limit=limit)

    @router.post("/ops/cache/warm", response_model=CacheWarmResponse)
    def ops_cache_warm():
        return service.cache_warm()

    @router.get("/ops/cache/warm", response_model=CacheWarmResponse)
    def ops_cache_warm_state():
        return service.cache_warm_state()

    @router.get("/ops/health", response_model=OpsHealthResponse)
    def ops_health(max_research_age_hours: int = 84):
        return service.health(max_research_age_hours=max_research_age_hours)

    @router.post("/cache/clear", response_model=CacheClearResponse)
    def cache_clear():
        return service.cache_clear()

    return router
