from fastapi import APIRouter

from api.contracts.mobile_v1 import EtfDetailResponse, EtfListResponse


def create_etf_router(service) -> APIRouter:
    router = APIRouter()

    @router.get("/etfs", response_model=EtfListResponse)
    def etfs(market: str = "ALL", category: str = "ALL", q: str = "", limit: int = 500, refresh: bool = False):
        return service.etfs(market=market, category=category, q=q, limit=limit, refresh=refresh)

    @router.get("/etfs/{ticker}", response_model=EtfDetailResponse)
    def etf_detail(ticker: str, refresh: bool = False):
        return service.etf_detail(ticker=ticker, refresh=refresh)

    return router
