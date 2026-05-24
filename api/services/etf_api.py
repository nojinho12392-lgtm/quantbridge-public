from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from fastapi import HTTPException

from api.services.company_names import apply_localized_names


@dataclass(frozen=True)
class EtfApiService:
    cached: Callable
    invalidate: Callable[[str], None]
    payload_with_prices: Callable[..., dict]
    detail_from_records: Callable[[list[dict], str], dict | None]
    storage_records: Callable[[], list[dict]]
    enrich_price_fields: Callable[[list[dict]], list[dict]]

    def etfs(
        self,
        market: str = "ALL",
        category: str = "ALL",
        q: str = "",
        limit: int = 500,
        refresh: bool = False,
    ) -> dict:
        safe_market = str(market or "ALL").upper()
        safe_category = str(category or "ALL")
        safe_limit = max(1, min(int(limit or 500), 1000))
        clean_q = str(q or "").strip()
        cache_key = f"etfs_daily_v2_{safe_market}_{safe_category}_{clean_q}_{safe_limit}"
        if refresh:
            self.invalidate(cache_key)
        return self.cached(
            cache_key,
            lambda: apply_localized_names(
                self.payload_with_prices(
                    market=safe_market,
                    category=safe_category,
                    q=clean_q,
                    limit=safe_limit,
                )
            ),
            ttl=900,
        )

    def etf_detail(self, ticker: str, refresh: bool = False) -> dict:
        normal = str(ticker or "").strip().upper()
        if not normal:
            raise HTTPException(status_code=400, detail="ETF ticker is required")
        cache_key = f"etf_detail_daily_v2_{normal}"
        if refresh:
            self.invalidate(cache_key)

        def load() -> dict:
            item = self.detail_from_records(self.storage_records(), normal)
            if not item:
                raise HTTPException(status_code=404, detail="ETF not found")
            if isinstance(item.get("item"), dict):
                self.enrich_price_fields([item["item"]])
            return apply_localized_names(item)

        return self.cached(cache_key, load, ttl=900)
