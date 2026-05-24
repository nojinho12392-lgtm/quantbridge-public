from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from api.services.company_names import apply_localized_names


@dataclass(frozen=True)
class NewsApiService:
    cached: Callable
    payload: Callable[[str, str, int], dict]
    cache_query_key: Callable[[str, str], str]

    def news_issues(self, q: str = "", market: str = "ALL", limit: int = 30) -> dict:
        safe_market = str(market or "ALL").upper()
        safe_limit = max(1, min(int(limit or 30), 100))
        cache_query = self.cache_query_key(q, safe_market)
        return self.cached(
            f"news_issues_{safe_market}_{safe_limit}_{cache_query}",
            lambda: apply_localized_names(self.payload(q, safe_market, safe_limit)),
            ttl=600,
        )
