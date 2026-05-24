from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from fastapi import HTTPException


@dataclass(frozen=True)
class RiskApiService:
    cached: Callable
    signal_events_payload: Callable[[str, str, str, int], dict]
    comparison_recommendation_payload: Callable[[str, str, int], dict]
    backtest_payload: Callable[[str, str], dict]
    risk_drift_payload: Callable[[], dict]
    portfolio_risk_payload: Callable[[str, int], dict]
    rebalance_payload: Callable[[str, int], dict]
    shadow_attribution_payload: Callable[[str, int], dict]
    industry_payload: Callable[[int], dict]
    order_flow_payload: Callable[[int], dict]

    def signal_events(self, market: str = "ALL", tickers: str = "", kinds: str = "", limit: int = 100) -> dict:
        cache_key = f"signal_events_{market}_{tickers}_{kinds}_{limit}"
        return self.cached(cache_key, lambda: self.signal_events_payload(market, tickers, kinds, limit), ttl=60)

    def comparison_recommendations(self, ticker: str, market: str = "ALL", limit: int = 8) -> dict:
        safe_ticker = str(ticker or "").strip().upper()
        if not safe_ticker:
            raise HTTPException(400, "ticker is required")
        cache_key = f"comparison_recommendations_{safe_ticker}_{market}_{limit}"
        return self.cached(cache_key, lambda: self.comparison_recommendation_payload(safe_ticker, market, limit), ttl=300)

    def backtest(self, market: str) -> dict:
        safe_market = self._market(market)
        return self.cached(
            f"backtest_{safe_market}",
            lambda: self.backtest_payload(f"{safe_market}_Backtest_Results", safe_market),
        )

    def smallcap_backtest(self, market: str) -> dict:
        safe_market = self._market(market)
        return self.cached(
            f"smallcap_backtest_{safe_market}",
            lambda: self.backtest_payload(f"{safe_market}_SmallCap_Backtest", safe_market),
        )

    def risk_drift(self) -> dict:
        return self.cached("risk_drift", self.risk_drift_payload)

    def portfolio_risk(self, market: str, limit: int = 30) -> dict:
        safe_market = self._market(market)
        safe_limit = max(1, min(int(limit or 30), 100))
        return self.cached(
            f"portfolio_risk_{safe_market}_{safe_limit}",
            lambda: self.portfolio_risk_payload(safe_market, safe_limit),
        )

    def rebalance_report(self, market: str, limit: int = 50) -> dict:
        safe_market = self._market(market)
        safe_limit = max(1, min(int(limit or 50), 200))
        return self.cached(
            f"rebalance_{safe_market}_{safe_limit}",
            lambda: self.rebalance_payload(safe_market, safe_limit),
        )

    def shadow_attribution(self, market: str = "ALL", limit: int = 50) -> dict:
        safe_market = str(market or "ALL").upper()
        if safe_market not in ("ALL", "US", "KR"):
            raise HTTPException(400, "market must be ALL, US, or KR")
        safe_limit = max(1, min(int(limit or 50), 200))
        return self.cached(
            f"shadow_attribution_{safe_market}_{safe_limit}",
            lambda: self.shadow_attribution_payload(safe_market, safe_limit),
        )

    def risk_industry(self, limit: int = 30) -> dict:
        safe_limit = max(1, min(int(limit or 30), 100))
        return self.cached(f"risk_industry_{safe_limit}", lambda: self.industry_payload(safe_limit))

    def risk_order_flow(self, limit: int = 30) -> dict:
        safe_limit = max(1, min(int(limit or 30), 100))
        return self.cached(f"risk_order_flow_{safe_limit}", lambda: self.order_flow_payload(safe_limit))

    @staticmethod
    def _market(market: str) -> str:
        safe_market = str(market or "").upper()
        if safe_market not in ("US", "KR"):
            raise HTTPException(400, "market must be US or KR")
        return safe_market
