from __future__ import annotations

from fastapi import APIRouter

from api.contracts.mobile_v1 import EarningsCalendarResponse, EarningsResponse, MacroResponse
from api.services.calendar_api import CalendarApiService


def create_calendar_router(service: CalendarApiService) -> APIRouter:
    router = APIRouter()

    @router.get("/calendar/earnings", response_model=EarningsCalendarResponse)
    def calendar_earnings(market: str = "ALL", days: int = 180, limit: int = 200, refresh: bool = False):
        return service.calendar_earnings(market=market, days=days, limit=limit, refresh=refresh)

    @router.get("/earnings/calendar", response_model=EarningsCalendarResponse)
    def earnings_calendar(market: str = "ALL", days: int = 180, limit: int = 200, refresh: bool = False):
        return service.earnings_calendar(market=market, days=days, limit=limit, refresh=refresh)

    @router.get("/earnings/{market}", response_model=EarningsResponse)
    def earnings(market: str):
        return service.earnings(market)

    @router.get("/macro", response_model=MacroResponse)
    def macro():
        return service.macro()

    return router
