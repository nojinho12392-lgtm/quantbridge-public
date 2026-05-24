from fastapi import APIRouter

from api.contracts.mobile_v1 import StockDetailResponse


def create_stock_router(service) -> APIRouter:
    router = APIRouter()

    @router.get("/stock/{ticker}", response_model=StockDetailResponse)
    def stock_detail(ticker: str, period: str = "6mo", refresh: bool = False, profile: bool = False):
        return service.detail(ticker=ticker, period=period, refresh=refresh, profile=profile)

    return router
