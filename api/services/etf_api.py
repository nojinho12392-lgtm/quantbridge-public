from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from fastapi import HTTPException

from api.services.company_names import apply_localized_names


@dataclass(frozen=True)
class EtfPriceEnricher:
    kr_code: Callable[[object], str | None]
    portfolio_price_snapshot_batch: Callable[[list[str], str], dict[str, dict]]
    portfolio_daily_change_batch: Callable[[list[str], str, dict[str, dict]], dict[str, tuple[float | None, str]]]
    portfolio_price_metrics_batch: Callable[[list[str], str], dict[str, tuple[float | None, float | None]]]
    first_float: Callable[..., float | None]

    def price_lookup_ticker(self, item: dict) -> tuple[str, str]:
        region = str(item.get("region") or item.get("Market") or item.get("market") or "US").strip().upper()
        ticker = str(item.get("ticker") or item.get("Ticker") or "").strip().upper()
        if region == "KR":
            code = self.kr_code(ticker)
            if code and not ticker.endswith((".KS", ".KQ")):
                ticker = f"{code}.KS"
        return region, ticker

    @staticmethod
    def price_change_from_return(current_price: float | None, return_1m: float | None) -> float | None:
        if current_price is None or return_1m is None or return_1m <= -0.999:
            return None
        base_price = current_price / (1 + return_1m)
        return current_price - base_price

    def enrich_price_fields(self, items: list[dict]) -> list[dict]:
        tickers_by_market: dict[str, list[str]] = {}
        lookup_by_item: list[tuple[dict, str, str]] = []
        for item in items:
            market, ticker = self.price_lookup_ticker(item)
            if not ticker:
                continue
            tickers_by_market.setdefault(market, []).append(ticker)
            lookup_by_item.append((item, market, ticker))

        snapshots_by_market: dict[str, dict[str, dict]] = {}
        history_metrics_by_market: dict[str, dict[str, tuple[float | None, float | None]]] = {}
        daily_changes_by_market: dict[str, dict[str, tuple[float | None, str]]] = {}
        for market, tickers in tickers_by_market.items():
            snapshots = self.portfolio_price_snapshot_batch(tickers, market)
            snapshots_by_market[market] = snapshots
            daily_changes_by_market[market] = self.portfolio_daily_change_batch(tickers, market, snapshots)
            missing = [
                ticker for ticker in tickers
                if not snapshots.get(ticker.upper())
                or (
                    snapshots[ticker.upper()].get("current_price") is None
                    or snapshots[ticker.upper()].get("return_1m") is None
                )
            ]
            history_metrics_by_market[market] = self.portfolio_price_metrics_batch(missing, market)

        for item, market, ticker in lookup_by_item:
            ticker_key = ticker.upper()
            snapshot = snapshots_by_market.get(market, {}).get(ticker.upper(), {})
            current_price = self.first_float(
                item.get("currentPrice"),
                item.get("Current_Price"),
                snapshot.get("current_price"),
            )
            return_1m = self.first_float(item.get("return1M"), item.get("Return_1M"), snapshot.get("return_1m"))
            if current_price is None or return_1m is None:
                history_price, history_return = history_metrics_by_market.get(market, {}).get(ticker_key, (None, None))
                current_price = current_price if current_price is not None else history_price
                return_1m = return_1m if return_1m is not None else history_return
            if current_price is not None:
                item["currentPrice"] = current_price
            if return_1m is not None:
                item["return1M"] = return_1m
            if snapshot.get("updated_at"):
                item["priceUpdatedAt"] = snapshot["updated_at"]
            if snapshot.get("source"):
                item["priceSource"] = snapshot["source"]
            price_change = self.price_change_from_return(current_price, return_1m)
            if price_change is not None:
                item["priceChange"] = price_change
            daily_change, daily_horizon = daily_changes_by_market.get(market, {}).get(ticker_key, (None, ""))
            if daily_change is not None:
                item["dailyChangePct"] = daily_change
                item["Daily_Change_Pct"] = daily_change
                daily_price_change = self.price_change_from_return(current_price, daily_change)
                if daily_price_change is not None:
                    item["dailyPriceChange"] = daily_price_change
                    item["Daily_Price_Change"] = daily_price_change
            if daily_horizon:
                item["dailyChangeHorizon"] = daily_horizon
                item["Daily_Change_Horizon"] = daily_horizon
        return items


@dataclass(frozen=True)
class EtfPayloadBuilder:
    payload_from_records: Callable[..., dict]
    storage_records: Callable[[], list[dict]]
    yahoo_search_records: Callable[[str, int], list[dict]]
    enrich_price_fields: Callable[[list[dict]], list[dict]]

    def payload_with_prices(self, *, market: str, category: str, q: str, limit: int) -> dict:
        payload = self.payload_from_records(
            self.storage_records(),
            market=market,
            category=category,
            q=q,
            limit=limit,
        )
        clean_q = str(q or "").strip()
        if clean_q:
            existing = {str(item.get("ticker") or "").strip().upper() for item in payload.get("items", [])}
            extra_items = []
            for item in self.yahoo_search_records(clean_q, limit=12):
                ticker = str(item.get("ticker") or "").strip().upper()
                if not ticker or ticker in existing:
                    continue
                if market != "ALL" and str(item.get("region") or "").upper() != market:
                    continue
                if category != "ALL" and str(item.get("category") or "") != category:
                    continue
                extra_items.append(item)
                existing.add(ticker)
            if extra_items:
                payload["items"] = list(payload.get("items", [])) + extra_items
                payload["count"] = len(payload["items"])
                payload["source"] = f"{payload.get('source') or 'etf'}+yahoo_search"
        payload["items"] = self.enrich_price_fields(payload.get("items", []))
        return payload


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
