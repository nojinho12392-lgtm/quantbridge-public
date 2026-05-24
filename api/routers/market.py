from fastapi import APIRouter

from api.contracts.mobile_v1 import (
    MarketIndicatorHistoryResponse,
    MarketIndicatorsResponse,
    MarketIndicesResponse,
)


def create_market_router(service) -> APIRouter:
    router = APIRouter()

    @router.get("/market/indices", response_model=MarketIndicesResponse)
    def market_indices(refresh: bool = False):
        return service.market_indices(refresh=refresh)

    @router.get("/market/indicators", response_model=MarketIndicatorsResponse)
    def market_indicators(category: str = "ALL", refresh: bool = False):
        return service.market_indicators(category=category, refresh=refresh)

    @router.get("/market/indicators/history", response_model=MarketIndicatorHistoryResponse)
    def market_indicator_history(
        symbols: str = "",
        period: str = "1d",
        interval: str = "15m",
        refresh: bool = False,
    ):
        return service.market_indicator_history(
            symbols=symbols,
            period=period,
            interval=interval,
            refresh=refresh,
        )

    return router
