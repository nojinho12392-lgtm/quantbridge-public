from __future__ import annotations

from fastapi import APIRouter

from api.contracts.mobile_v1 import HealthResponse, ReadyResponse
from api.services.system_api import SystemApiService


def create_system_router(service: SystemApiService) -> APIRouter:
    router = APIRouter()

    @router.get("/health", response_model=HealthResponse)
    def health():
        return service.health()

    @router.get("/ready", response_model=ReadyResponse)
    def ready():
        return service.ready()

    return router
