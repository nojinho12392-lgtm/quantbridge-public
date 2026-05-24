from __future__ import annotations

from fastapi import APIRouter

from api.contracts.mobile_v1 import SearchUniverseResponse
from api.services.search_api import SearchApiService


def create_search_router(service: SearchApiService) -> APIRouter:
    router = APIRouter()

    @router.get("/search/universe", response_model=SearchUniverseResponse)
    def search_universe(q: str = "", limit: int = 100):
        return service.search_universe(q=q, limit=limit)

    return router
