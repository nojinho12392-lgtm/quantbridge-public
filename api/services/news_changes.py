from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import pandas as pd
import requests


KST = timezone(timedelta(hours=9))


@dataclass(frozen=True)
class NewsChangeEnricher:
    kr_code: Callable[[object], str]
    infer_market_from_ticker: Callable[[str], str]
    safe_float: Callable[[object], float | None]
    naver_indicator_float: Callable[[object], float | None]
    cached: Callable[..., object]
    portfolio_price_snapshot_batch: Callable[[list[str], str | None], dict[str, dict]]
    portfolio_daily_change_batch: Callable[[list[str], str, dict[str, dict]], dict[str, tuple[float | None, str]]]
    market_indicators_payload: Callable[..., dict]
    allow_external_fetch: Callable[[], bool]
    naver_kr_change_batch: Callable[[list[str]], dict[str, tuple[float | None, str]]] | None = None
    default_market_change: Callable[[dict, str], tuple[str, float] | None] | None = None

    NEWS_TICKER_MENTION_TERMS = {
        "NVDA": ("엔비디아", "nvidia", "nvda"),
        "TSLA": ("테슬라", "tesla", "tsla"),
        "AAPL": ("애플", "apple", "aapl"),
        "MSFT": ("마이크로소프트", "microsoft", "msft"),
        "GOOGL": ("알파벳", "구글", "alphabet", "google", "googl"),
        "AMZN": ("아마존", "amazon", "amzn"),
        "META": ("메타", "meta platforms", "meta"),
        "005930.KS": ("삼성전자", "samsung electronics"),
        "000660.KS": ("sk하이닉스", "하이닉스", "sk hynix"),
        "005380.KS": ("현대차", "현대자동차", "hyundai motor"),
    }
    NEWS_TICKER_DISPLAY_LABELS = {
        "NVDA": "엔비디아",
        "TSLA": "테슬라",
        "AAPL": "애플",
        "MSFT": "마이크로소프트",
        "GOOGL": "알파벳",
        "AMZN": "아마존",
        "META": "메타",
        "005930.KS": "삼성전자",
        "000660.KS": "SK하이닉스",
        "005380.KS": "현대차",
    }

    def news_price_ticker(self, value: str) -> str:
        ticker = str(value or "").strip().upper()
        if not ticker:
            return ""
        code = self.kr_code(ticker)
        if code and not ticker.endswith((".KS", ".KQ")):
            return f"{code}.KS"
        return ticker

    def news_ticker_terms(self, ticker: str) -> set[str]:
        normal = ticker.upper()
        direct_terms = {normal.casefold(), normal.replace(".KS", "").replace(".KQ", "").casefold()}
        mapped_terms = {term.casefold() for term in self.NEWS_TICKER_MENTION_TERMS.get(normal, ())}
        return {term for term in direct_terms | mapped_terms if term}

    def news_ticker_mention_index(self, item: dict, ticker: str) -> int | None:
        text = f"{item.get('title') or ''} {item.get('summary') or ''}".casefold()
        positions = [text.find(term) for term in self.news_ticker_terms(ticker)]
        positions = [position for position in positions if position >= 0]
        return min(positions) if positions else None

    def news_change_display_label(self, ticker: str) -> str:
        normal = ticker.upper()
        return self.NEWS_TICKER_DISPLAY_LABELS.get(normal) or normal.replace(".KS", "").replace(".KQ", "")

    @staticmethod
    def news_kr_quote_horizon(row: dict) -> str:
        traded_at = pd.to_datetime(row.get("localTradedAt"), errors="coerce")
        if pd.isna(traded_at):
            return "오늘"
        traded_dt = traded_at.to_pydatetime()
        if traded_dt.tzinfo is not None:
            traded_date = traded_dt.astimezone(KST).date()
        else:
            traded_date = traded_dt.date()
        return "오늘" if traded_date == datetime.now(KST).date() else "최근장"

    def naver_kr_stock_quote_rows(self, codes: list[str]) -> list[dict]:
        clean_codes = sorted({self.kr_code(code) for code in codes if self.kr_code(code)})
        if not clean_codes:
            return []
        headers = {
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X)",
            "Referer": "https://m.stock.naver.com/",
        }
        rows: list[dict] = []
        for offset in range(0, len(clean_codes), 50):
            chunk = clean_codes[offset:offset + 50]

            def load(chunk=chunk):
                try:
                    resp = requests.get(
                        f"https://polling.finance.naver.com/api/realtime/domestic/stock/{','.join(chunk)}",
                        headers=headers,
                        timeout=5,
                    )
                    if resp.status_code != 200:
                        return []
                    return [row for row in resp.json().get("datas") or [] if isinstance(row, dict)]
                except Exception:
                    return []

            rows.extend(self.cached(f"naver_kr_stock_quotes_{','.join(chunk)}", load, ttl=60))
        return rows

    def naver_kr_stock_change_batch(self, tickers: list[str]) -> dict[str, tuple[float | None, str]]:
        requested_by_code: dict[str, set[str]] = {}
        for ticker in tickers:
            normal = str(ticker or "").strip().upper()
            code = self.kr_code(normal)
            if not code:
                continue
            requested_by_code.setdefault(code, set()).add(normal)
        if not requested_by_code:
            return {}

        changes: dict[str, tuple[float | None, str]] = {}
        for row in self.naver_kr_stock_quote_rows(list(requested_by_code)):
            code = self.kr_code(row.get("itemCode") or row.get("symbolCode"))
            if not code:
                continue
            change_pct_percent = self.naver_indicator_float(
                row.get("fluctuationsRatioRaw") or row.get("fluctuationsRatio")
            )
            if change_pct_percent is None:
                close = self.naver_indicator_float(row.get("closePriceRaw") or row.get("closePrice"))
                change_abs = self.naver_indicator_float(
                    row.get("compareToPreviousClosePriceRaw") or row.get("compareToPreviousClosePrice")
                )
                if close is not None and change_abs is not None:
                    previous_close = close - change_abs
                    if previous_close > 0:
                        change_pct_percent = (change_abs / previous_close) * 100
            if change_pct_percent is None:
                continue

            exchange_info = row.get("stockExchangeType") or {}
            exchange = str(exchange_info.get("code") or row.get("stockExchangeName") or "").upper()
            suffix = ".KQ" if exchange in {"KQ", "KOSDAQ"} else ".KS"
            horizon = self.news_kr_quote_horizon(row)
            parsed = (change_pct_percent / 100, horizon)
            for requested in requested_by_code.get(code, set()):
                changes[requested] = parsed
            changes[f"{code}{suffix}"] = parsed
        return changes

    def naver_kr_stock_price_batch(self, tickers: list[str]) -> dict[str, dict]:
        requested_by_code: dict[str, set[str]] = {}
        for ticker in tickers:
            normal = str(ticker or "").strip().upper()
            code = self.kr_code(normal)
            if not code:
                continue
            requested_by_code.setdefault(code, set()).add(normal)
        if not requested_by_code:
            return {}

        quotes: dict[str, dict] = {}
        for row in self.naver_kr_stock_quote_rows(list(requested_by_code)):
            code = self.kr_code(row.get("itemCode") or row.get("symbolCode"))
            if not code:
                continue
            close = self.naver_indicator_float(row.get("closePriceRaw") or row.get("closePrice"))
            change_pct_percent = self.naver_indicator_float(
                row.get("fluctuationsRatioRaw") or row.get("fluctuationsRatio")
            )
            if change_pct_percent is None:
                change_abs = self.naver_indicator_float(
                    row.get("compareToPreviousClosePriceRaw") or row.get("compareToPreviousClosePrice")
                )
                if close is not None and change_abs is not None:
                    previous_close = close - change_abs
                    if previous_close > 0:
                        change_pct_percent = (change_abs / previous_close) * 100

            exchange_info = row.get("stockExchangeType") or {}
            exchange = str(exchange_info.get("code") or row.get("stockExchangeName") or "").upper()
            suffix = ".KQ" if exchange in {"KQ", "KOSDAQ"} else ".KS"
            payload = {
                "current_price": close,
                "daily_change_pct": change_pct_percent / 100 if change_pct_percent is not None else None,
                "daily_change_horizon": self.news_kr_quote_horizon(row),
                "updated_at": str(row.get("localTradedAt") or "").strip() or None,
                "source": "naver_realtime",
            }
            for requested in requested_by_code.get(code, set()):
                quotes[requested] = payload
            quotes[code] = payload
            quotes[f"{code}{suffix}"] = payload
        return quotes

    def news_change_candidates(self, item: dict) -> list[tuple[str, str]]:
        raw_values: list[tuple[str, bool]] = []
        raw_values.append((str(item.get("ticker") or ""), True))
        raw_values.extend((str(value or ""), False) for value in item.get("related_tickers") or [])

        candidates: list[tuple[int, int, int, str, str]] = []
        seen: set[str] = set()
        for order, (value, is_primary) in enumerate(raw_values):
            ticker = self.news_price_ticker(value)
            if not ticker or ticker in seen:
                continue
            mention_index = self.news_ticker_mention_index(item, ticker)
            if not is_primary and mention_index is None:
                continue
            seen.add(ticker)
            candidates.append((
                0 if is_primary else 1,
                mention_index if mention_index is not None else 999_999,
                order,
                self.infer_market_from_ticker(ticker),
                ticker,
            ))
        candidates.sort()
        return [(market, ticker) for _, _, _, market, ticker in candidates[:5]]

    def news_market_change_symbol(self, item: dict, safe_market: str) -> str:
        text = f"{item.get('title') or ''} {item.get('summary') or ''}".casefold()
        item_market = str(item.get("market") or safe_market or "ALL").upper()
        if "코스닥" in text:
            return "^KQ11"
        if item_market == "KR" or any(term in text for term in ("코스피", "국내", "원화", "삼성", "하이닉스")):
            return "^KS11"
        if any(term in text for term in ("s&p", "sp500", "s&p500")):
            return "^GSPC"
        if any(term in text for term in ("다우", "dow")):
            return "^DJI"
        return "^IXIC"

    def news_default_market_change(self, item: dict, safe_market: str) -> tuple[str, float] | None:
        try:
            payload = self.market_indicators_payload(category="index_fx", refresh=False)
        except Exception:
            return None
        symbol = self.news_market_change_symbol(item, safe_market)
        for quote in payload.get("items") or []:
            if str(quote.get("symbol") or "").upper() != symbol:
                continue
            change_pct = self.safe_float(quote.get("change_pct"))
            if change_pct is None:
                return None
            label = str(quote.get("label") or symbol)
            return label, change_pct
        return None

    @staticmethod
    def news_should_use_market_change(item: dict, candidates: list[tuple[str, str]]) -> bool:
        if not candidates:
            return True
        return str(item.get("impact_scope") or "").strip().lower() != "stock"

    def enrich_news_change_fields(self, items: list[dict], safe_market: str) -> list[dict]:
        candidates_by_item = [(item, self.news_change_candidates(item)) for item in items]
        tickers_by_market: dict[str, list[str]] = {}
        for _, candidates in candidates_by_item:
            for market, ticker in candidates:
                tickers_by_market.setdefault(market, []).append(ticker)

        snapshots_by_market: dict[str, dict[str, dict]] = {}
        daily_change_by_market: dict[str, dict[str, tuple[float | None, str]]] = {}
        for market, tickers in tickers_by_market.items():
            if market == "KR":
                naver_change_batch = self.naver_kr_change_batch or self.naver_kr_stock_change_batch
                naver_changes = naver_change_batch(tickers) if self.allow_external_fetch() else {}
                missing = [
                    str(ticker or "").strip().upper()
                    for ticker in tickers
                    if str(ticker or "").strip().upper() not in naver_changes
                ]
                if missing:
                    snapshots = self.portfolio_price_snapshot_batch(missing, market)
                    snapshots_by_market[market] = snapshots
                    storage_changes = self.portfolio_daily_change_batch(missing, market, snapshots)
                else:
                    snapshots_by_market[market] = {}
                    storage_changes = {}
                daily_change_by_market[market] = {**storage_changes, **naver_changes}
                continue

            snapshots = self.portfolio_price_snapshot_batch(tickers, market)
            snapshots_by_market[market] = snapshots
            daily_change_by_market[market] = self.portfolio_daily_change_batch(tickers, market, snapshots)

        enriched: list[dict] = []
        for item, candidates in candidates_by_item:
            updated = dict(item)
            matched = False
            for market, ticker in candidates:
                key = ticker.upper()
                daily_change, horizon = daily_change_by_market.get(market, {}).get(key, (None, ""))
                if daily_change is None:
                    continue
                updated["related_change_label"] = self.news_change_display_label(ticker)
                updated["related_change_pct"] = daily_change
                updated["related_change_horizon"] = horizon or "전장"
                matched = True
                break

            default_market_change = self.default_market_change or self.news_default_market_change
            if not matched and self.news_should_use_market_change(item, candidates):
                market_change = default_market_change(item, safe_market)
                if market_change:
                    label, change_pct = market_change
                    updated["related_change_label"] = label
                    updated["related_change_pct"] = change_pct
                    updated["related_change_horizon"] = "오늘"
            enriched.append(updated)
        return enriched
