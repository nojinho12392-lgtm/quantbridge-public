from __future__ import annotations

from fastapi import APIRouter

from api.contracts.mobile_v1 import (
    BacktestResponse,
    ComparisonRecommendationsResponse,
    DriftAlertsResponse,
    IndustryRankingResponse,
    OrderFlowResponse,
    PortfolioRiskResponse,
    RebalanceResponse,
    ShadowAttributionResponse,
    SignalEventsResponse,
)
from api.services.risk_api import RiskApiService


def create_risk_router(service: RiskApiService) -> APIRouter:
    router = APIRouter()

    @router.get("/signals/events", response_model=SignalEventsResponse)
    def signal_events(market: str = "ALL", tickers: str = "", kinds: str = "", limit: int = 100):
        return service.signal_events(market=market, tickers=tickers, kinds=kinds, limit=limit)

    @router.get("/comparison/recommendations/{ticker}", response_model=ComparisonRecommendationsResponse)
    def comparison_recommendations(ticker: str, market: str = "ALL", limit: int = 8):
        return service.comparison_recommendations(ticker=ticker, market=market, limit=limit)

    @router.get("/backtest/{market}", response_model=BacktestResponse)
    def backtest(market: str):
        return service.backtest(market)

    @router.get("/smallcap-backtest/{market}", response_model=BacktestResponse)
    def smallcap_backtest(market: str):
        return service.smallcap_backtest(market)

    @router.get("/risk/drift", response_model=DriftAlertsResponse)
    def risk_drift():
        return service.risk_drift()

    @router.get("/risk/portfolio/{market}", response_model=PortfolioRiskResponse)
    def portfolio_risk(market: str, limit: int = 30):
        return service.portfolio_risk(market=market, limit=limit)

    @router.get("/rebalance/{market}", response_model=RebalanceResponse)
    def rebalance_report(market: str, limit: int = 50):
        return service.rebalance_report(market=market, limit=limit)

    @router.get("/shadow/attribution", response_model=ShadowAttributionResponse)
    def shadow_attribution(market: str = "ALL", limit: int = 50):
        return service.shadow_attribution(market=market, limit=limit)

    @router.get("/risk/industry", response_model=IndustryRankingResponse)
    def risk_industry(limit: int = 30):
        return service.risk_industry(limit=limit)

    @router.get("/risk/order-flow", response_model=OrderFlowResponse)
    def risk_order_flow(limit: int = 30):
        return service.risk_order_flow(limit=limit)

    return router
