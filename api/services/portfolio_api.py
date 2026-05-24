from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from fastapi import HTTPException

from api.services.company_names import apply_localized_names


@dataclass(frozen=True)
class PortfolioApiService:
    cached: Callable
    invalidate: Callable[[str], None]
    load_portfolio: Callable[[str], tuple[dict, list[dict]]]
    price_snapshot_batch: Callable[[list[str], str], dict]
    price_metrics_batch: Callable[[list[str], str], dict]
    daily_change_batch: Callable[[list[str], str, dict], dict]
    naver_kr_quote_batch: Callable[[list[str]], dict]
    utc_now_iso: Callable[[], str]

    def portfolio(self, market: str) -> dict:
        safe_market = self._market(market)
        sheet = f"{safe_market}_Final_Portfolio"

        def load() -> dict:
            meta, stocks = self.load_portfolio(sheet)
            return apply_localized_names({"meta": meta, "stocks": stocks})

        return self.cached(f"port_{safe_market}", load, ttl=60)

    def portfolio_prices(self, market: str, tickers: str = "", limit: int = 30, refresh: bool = False) -> dict:
        safe_market = self._market(market)
        clean_limit = max(1, min(int(limit or 30), 100))
        requested = [
            str(ticker).strip().upper()
            for ticker in str(tickers or "").split(",")
            if str(ticker).strip()
        ][:clean_limit]
        if not requested:
            _, stocks = self.load_portfolio(f"{safe_market}_Final_Portfolio")
            requested = [
                str(stock.get("Ticker") or "").strip().upper()
                for stock in stocks
                if str(stock.get("Ticker") or "").strip()
            ][:clean_limit]

        cache_requested = sorted(dict.fromkeys(requested))
        cache_key = "portfolio_prices_{}_{}".format(safe_market, ",".join(cache_requested))
        if refresh:
            self.invalidate(cache_key)

        def load() -> dict:
            snapshots = self.price_snapshot_batch(cache_requested, safe_market)
            missing = [ticker for ticker in cache_requested if ticker not in snapshots]
            batch_metrics: dict[str, tuple[float | None, float | None]] = (
                self.price_metrics_batch(missing, safe_market) if missing else {}
            )
            daily_changes = self.daily_change_batch(cache_requested, safe_market, snapshots)
            naver_kr_quotes = self.naver_kr_quote_batch(cache_requested) if safe_market == "KR" else {}
            metrics = []
            for ticker in cache_requested:
                snapshot = snapshots.get(ticker)
                if snapshot:
                    current_price = snapshot.get("current_price")
                    return_1m = snapshot.get("return_1m")
                    updated_at = snapshot.get("updated_at")
                else:
                    current_price, return_1m = batch_metrics.get(ticker, (None, None))
                    updated_at = None
                daily_change, daily_horizon = daily_changes.get(ticker, (None, ""))
                naver_quote = naver_kr_quotes.get(ticker)
                if naver_quote:
                    current_price = naver_quote.get("current_price") or current_price
                    daily_change = naver_quote.get("daily_change_pct")
                    daily_horizon = str(naver_quote.get("daily_change_horizon") or daily_horizon or "")
                    updated_at = naver_quote.get("updated_at") or updated_at
                metrics.append({
                    "Ticker": ticker,
                    "Current_Price": current_price,
                    "Return_1M": return_1m,
                    "Daily_Change_Pct": daily_change,
                    "Daily_Change_Horizon": daily_horizon,
                    "Price_Updated_At": updated_at,
                })
            return {
                "market": safe_market,
                "metrics": metrics,
                "source": "storage",
                "updated_at": self.utc_now_iso(),
            }

        return self.cached(cache_key, load, ttl=30)

    @staticmethod
    def _market(market: str) -> str:
        safe_market = str(market or "").upper()
        if safe_market not in ("US", "KR"):
            raise HTTPException(400, "market must be US or KR")
        return safe_market
