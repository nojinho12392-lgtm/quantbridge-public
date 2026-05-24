from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from fastapi import HTTPException

from api.services.company_names import apply_localized_names


@dataclass(frozen=True)
class CalendarApiService:
    cached: Callable
    earnings_calendar_response: Callable[[str, int, int, bool], dict]
    load_simple: Callable[[str, list[str]], list[dict]]
    enrich_kr_company_identities: Callable[[list[dict]], list[dict]]
    macro_payload: Callable[[], dict]

    def calendar_earnings(self, market: str = "ALL", days: int = 180, limit: int = 200, refresh: bool = False) -> dict:
        return apply_localized_names(self.earnings_calendar_response(market, days, limit, refresh))

    def earnings_calendar(self, market: str = "ALL", days: int = 180, limit: int = 200, refresh: bool = False) -> dict:
        return apply_localized_names(self.earnings_calendar_response(market, days, limit, refresh))

    def earnings(self, market: str) -> dict:
        safe_market = self._market(market)
        sheet = f"{safe_market}_Earnings_Momentum"
        num_cols = [
            "Surprise_Pct", "Signal_Strength", "Return_Since",
            "Volume_Surge", "Days_Since_Earnings",
            "Actual_EPS", "Estimated_EPS", "MarketCap", "Rank",
        ]

        def load() -> dict:
            stocks = self.load_simple(sheet, num_cols)
            if safe_market == "KR":
                stocks = self.enrich_kr_company_identities(stocks)
            return apply_localized_names({"stocks": stocks})

        return self.cached(f"earn_{safe_market}_v2", load)

    def macro(self) -> dict:
        return self.cached("macro", self.macro_payload)

    @staticmethod
    def _market(market: str) -> str:
        safe_market = str(market or "").upper()
        if safe_market not in ("US", "KR"):
            raise HTTPException(400, "market must be US or KR")
        return safe_market
