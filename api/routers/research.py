from __future__ import annotations

from fastapi import APIRouter

from api.contracts.mobile_v1 import (
    MLBlendResponse,
    PolicyAdjustedRankingResponse,
    PolicyBacktestResponse,
    RemediationPlanResponse,
    ResearchPolicyResponse,
    ResearchQualityResponse,
)
from api.services.research_api import ResearchApiService


def create_research_router(service: ResearchApiService) -> APIRouter:
    router = APIRouter()

    @router.get("/research/factor-quality", response_model=ResearchQualityResponse)
    def research_factor_quality():
        return service.factor_quality()

    @router.get("/research/factor-policy", response_model=ResearchPolicyResponse)
    def research_factor_policy():
        return service.factor_policy()

    @router.get("/research/ml-blend", response_model=MLBlendResponse)
    def research_ml_blend():
        return service.ml_blend()

    @router.get("/research/policy-backtest", response_model=PolicyBacktestResponse)
    def research_policy_backtest():
        return service.policy_backtest()

    @router.get("/research/policy-adjusted-ranking", response_model=PolicyAdjustedRankingResponse)
    def research_policy_adjusted_ranking(market: str = "US", limit: int = 30):
        return service.policy_adjusted_ranking(market=market, limit=limit)

    @router.get("/research/remediation-plan", response_model=RemediationPlanResponse)
    def research_remediation_plan():
        return service.remediation_plan()

    return router
