from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from fastapi import HTTPException


@dataclass(frozen=True)
class MarketApiService:
    cached: Callable
    invalidate: Callable[[str], None]
    market_indicators_payload: Callable[..., dict]
    history_payload: Callable[..., dict]
    selected_symbols: Callable[[str], list[str]]
    legacy_index_symbols: set[str]
    periods: dict[str, int]
    intervals: set[str]

    def market_indices(self, refresh: bool = False) -> dict:
        payload = self.market_indicators_payload(category="index_fx", refresh=refresh)
        indices = [
            {
                "symbol": item["symbol"],
                "label": item["label"],
                "value": item["value"],
                "change_abs": item.get("change_abs") or 0,
                "change_pct": item.get("change_pct") or 0,
                "updated_at": item.get("updated_at") or item.get("observed_at") or payload["updated_at"],
            }
            for item in payload["items"]
            if item["symbol"] in self.legacy_index_symbols
        ]
        return {"indices": indices, "updated_at": payload["updated_at"], "source": payload.get("source")}

    def market_indicators(self, category: str = "ALL", refresh: bool = False) -> dict:
        safe_category = str(category or "ALL").strip().lower()
        if safe_category not in {"all", "index_fx", "bond", "commodity", "crypto"}:
            raise HTTPException(400, "category must be ALL, index_fx, bond, commodity, or crypto")
        return self.market_indicators_payload(category=safe_category, refresh=refresh)

    def market_indicator_history(
        self,
        symbols: str = "",
        period: str = "1d",
        interval: str = "15m",
        refresh: bool = False,
    ) -> dict:
        safe_period = period if period in self.periods else "1d"
        safe_interval = interval if interval in self.intervals else "15m"
        wanted = self.selected_symbols(symbols)
        cache_key = f"market_indicator_history_v2_{','.join(wanted)}_{safe_period}_{safe_interval}"
        if refresh:
            self.invalidate(cache_key)
            return self.history_payload(wanted, safe_period, safe_interval, refresh=True)
        return self.cached(
            cache_key,
            lambda: self.history_payload(wanted, safe_period, safe_interval, refresh=refresh),
            ttl=300,
        )
