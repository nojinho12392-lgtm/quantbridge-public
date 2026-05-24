from fastapi import APIRouter

from api.contracts.mobile_v1 import PortfolioPricesResponse, PortfolioResponse


def create_portfolio_router(service) -> APIRouter:
    router = APIRouter()

    @router.get("/portfolio/{market}", response_model=PortfolioResponse)
    def portfolio(market: str):
        return service.portfolio(market)

    @router.get("/portfolio/{market}/prices", response_model=PortfolioPricesResponse)
    def portfolio_prices(market: str, tickers: str = "", limit: int = 30, refresh: bool = False):
        return service.portfolio_prices(market=market, tickers=tickers, limit=limit, refresh=refresh)

    return router
