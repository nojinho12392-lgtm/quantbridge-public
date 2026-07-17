from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from api.services.company_names import apply_localized_names


@dataclass(frozen=True)
class NewsPayloadBuilder:
    naver_configured: bool
    default_news_query: Callable[[str], str]
    naver_news_items: Callable[[str, str, int], list[dict]]
    rss_news_items: Callable[[str, int], tuple[list[dict], str]]
    diversify_news_items: Callable[[list[dict], int], list[dict]]
    news_pre_importance_score: Callable[[dict], float]
    news_importance_score: Callable[[dict], float]
    enrich_news_items: Callable[[list[dict], str], list[dict]]
    enrich_news_change_fields: Callable[[list[dict], str], list[dict]]
    news_public_items: Callable[[list[dict], str], list[dict]]
    news_query_plan: Callable[[str, str], list[str]]

    def payload(self, q: str = "", market: str = "ALL", limit: int = 30) -> dict:
        safe_market = str(market or "ALL").upper()
        if safe_market not in {"ALL", "US", "KR"}:
            safe_market = "ALL"
        safe_limit = max(1, min(int(limit or 30), 100))
        query = str(q or "").strip() or self.default_news_query(safe_market)

        external_items: list[dict] = []
        external_error = ""
        try:
            external_items = self.naver_news_items(query, safe_market, safe_limit)
        except Exception as exc:
            external_error = f"{type(exc).__name__}: {exc}"

        rss_items: list[dict] = []
        rss_error = ""
        if len(external_items) < safe_limit:
            rss_items, rss_error = self.rss_news_items(safe_market, safe_limit)
            if rss_error:
                external_error = "; ".join(part for part in [external_error, rss_error] if part)

        article_items = [
            item for item in (external_items + rss_items)
            if str(item.get("kind") or "").strip().lower() == "external_news"
            and str(item.get("url") or "").strip()
        ]
        raw_candidates = self.diversify_news_items(
            sorted(article_items, key=self.news_pre_importance_score, reverse=True),
            max(safe_limit * 2, 40),
        )
        combined = self.enrich_news_change_fields(
            self.enrich_news_items(raw_candidates, safe_market),
            safe_market,
        )
        combined = [
            item for item in combined
            if str(item.get("kind") or "").strip().lower() == "external_news"
            and str(item.get("url") or "").strip()
        ]
        if safe_market != "ALL":
            combined = [
                item for item in combined
                if str(item.get("market") or "").strip().upper() == safe_market
            ]
        combined = self.diversify_news_items(
            sorted(combined, key=self.news_importance_score, reverse=True),
            safe_limit,
        )
        combined = self.news_public_items(combined, safe_market)
        return {
            "configured": self.naver_configured,
            "query": query,
            "queries": self.news_query_plan(query, safe_market),
            "market": safe_market,
            "count": len(combined),
            "external_count": len(article_items),
            "rss_count": len(rss_items),
            "internal_count": 0,
            "external_error": external_error,
            "items": combined,
        }


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
