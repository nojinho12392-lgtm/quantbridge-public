from __future__ import annotations

from fastapi import APIRouter

from api.contracts.mobile_v1 import ScoredResponse, SmallCapResponse
from api.services.ranking_api import RankingApiService


def create_ranking_router(service: RankingApiService) -> APIRouter:
    router = APIRouter()

    @router.get("/smallcap/{market}", response_model=SmallCapResponse)
    def smallcap(market: str):
        return service.smallcap(market)

    @router.get("/scored/{market}", response_model=ScoredResponse)
    def scored(market: str, limit: int = 200):
        return service.scored(market=market, limit=limit)

    return router
