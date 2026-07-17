from __future__ import annotations

from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
import re
from typing import Any

from fastapi import HTTPException

from api.services.company_names import apply_localized_names


@dataclass(frozen=True)
class SectorPriceMetricBuilder:
    PRICE_REQUEST_LIMIT = 1800
    US_LIVE_QUOTE_LIMIT = 240
    FALLBACK_USDKRW = 1350.0

    stored_indicator_latest: Callable[[list[str]], dict[str, dict]]
    safe_float: Callable[[object], float | None]
    comparison_match_keys: Callable[[str], set[str]]
    portfolio_price_snapshot_batch: Callable[[list[str], str | None], dict[str, dict]]
    portfolio_price_metrics_batch: Callable[[list[str], str | None], dict[str, tuple[float | None, float | None]]]
    portfolio_daily_change_batch: Callable[[list[str], str, dict[str, dict]], dict[str, tuple[float | None, str]]]
    naver_kr_stock_price_batch: Callable[[list[str]], dict[str, dict]]
    yfinance_stock_quote_batch: Callable[[list[str], str], dict[str, dict]]
    allow_external_fetch: Callable[[], bool]

    def usdkrw_rate(self) -> float:
        try:
            stored = self.stored_indicator_latest(["KRW=X"]).get("KRW=X") or {}
            rate = self.safe_float(stored.get("value") or stored.get("close"))
            if rate is not None and rate > 0:
                return rate
        except Exception:
            pass
        return self.FALLBACK_USDKRW

    def market_cap_usd_sort_value(self, item: dict, usdkrw_rate: float) -> float | None:
        market_cap = self.safe_float(item.get("MarketCap"))
        if market_cap is None or market_cap <= 0:
            return None
        market = str(item.get("Market") or "").upper()
        currency = str(item.get("Currency") or "").upper()
        if market == "KR" or currency == "KRW":
            return market_cap / max(usdkrw_rate, 1.0)
        return market_cap

    def market_cap_weighted_value(self, items: list[dict], value_key: str, usdkrw_rate: float) -> float | None:
        weighted_sum = 0.0
        total_weight = 0.0
        equal_values: list[float] = []
        for item in items:
            value = self.safe_float(item.get(value_key))
            if value is None:
                continue
            equal_values.append(value)
            weight = self.market_cap_usd_sort_value(item, usdkrw_rate)
            if weight is None or weight <= 0:
                continue
            weighted_sum += value * weight
            total_weight += weight
        if total_weight > 0:
            return weighted_sum / total_weight
        return sum(equal_values) / len(equal_values) if equal_values else None

    def price_metric_map(
        self,
        market: str,
        tickers: list[str],
        live_tickers: list[str] | None = None,
    ) -> dict[str, dict]:
        requested = [
            str(ticker).strip().upper()
            for ticker in tickers
            if str(ticker or "").strip()
        ]
        requested = list(dict.fromkeys(requested))[:self.PRICE_REQUEST_LIMIT]
        if not requested:
            return {}

        alias_order = {
            ticker: [ticker] + sorted(self.comparison_match_keys(ticker) - {ticker})
            for ticker in requested
        }
        lookup_tickers = list(dict.fromkeys(alias for aliases in alias_order.values() for alias in aliases))
        snapshots = self.portfolio_price_snapshot_batch(lookup_tickers, market)
        missing = [ticker for ticker in lookup_tickers if ticker not in snapshots]
        batch_metrics = self.portfolio_price_metrics_batch(missing, market) if missing else {}
        daily_changes = self.portfolio_daily_change_batch(lookup_tickers, market, snapshots)
        missing_current = [
            ticker
            for ticker in missing
            if self.safe_float((batch_metrics.get(ticker) or (None, None))[0]) is None
        ]
        live_requested = [
            str(ticker).strip().upper()
            for ticker in (live_tickers if live_tickers is not None else requested)
            if str(ticker or "").strip()
        ]
        live_requested = list(dict.fromkeys(live_requested))
        naver_kr_quotes = (
            self.naver_kr_stock_price_batch(live_requested)
            if market == "KR" and live_tickers is not None and live_requested
            else {}
        )
        live_quote_tickers = (
            live_requested[:self.US_LIVE_QUOTE_LIMIT]
            if market != "KR"
            else missing_current[:self.US_LIVE_QUOTE_LIMIT]
        )
        yfinance_quotes = (
            self.yfinance_stock_quote_batch(live_quote_tickers, market)
            if market != "KR" and self.allow_external_fetch()
            else {}
        )
        metrics: dict[str, dict] = {}
        for ticker in requested:
            aliases = alias_order.get(ticker) or [ticker]
            snapshot = next((snapshots.get(alias) for alias in aliases if snapshots.get(alias)), None)
            if snapshot:
                current_price = snapshot.get("current_price")
                return_1m = snapshot.get("return_1m")
                updated_at = snapshot.get("updated_at")
            else:
                current_price, return_1m = next(
                    (
                        value
                        for alias in aliases
                        for value in [batch_metrics.get(alias)]
                        if value and self.safe_float(value[0]) is not None
                    ),
                    (None, None),
                )
                updated_at = None
            daily_change, daily_horizon = next(
                (
                    value
                    for alias in aliases
                    for value in [daily_changes.get(alias)]
                    if value and self.safe_float(value[0]) is not None
                ),
                (None, ""),
            )
            snapshot_daily_change, snapshot_daily_horizon = next(
                (
                    (self.safe_float(snap.get("daily_change_pct")), str(snap.get("daily_change_horizon") or ""))
                    for alias in aliases
                    for snap in [snapshots.get(alias)]
                    if snap and self.safe_float(snap.get("daily_change_pct")) is not None
                ),
                (None, ""),
            )
            if snapshot_daily_change is not None:
                daily_change = snapshot_daily_change
                daily_horizon = snapshot_daily_horizon or daily_horizon
            naver_quote = next((naver_kr_quotes.get(alias) for alias in aliases if naver_kr_quotes.get(alias)), None)
            if naver_quote:
                current_price = naver_quote.get("current_price") or current_price
                daily_change = naver_quote.get("daily_change_pct")
                daily_horizon = str(naver_quote.get("daily_change_horizon") or daily_horizon or "")
                updated_at = naver_quote.get("updated_at") or updated_at
            yfinance_quote = next((yfinance_quotes.get(alias) for alias in aliases if yfinance_quotes.get(alias)), None)
            if yfinance_quote:
                current_price = yfinance_quote.get("current_price") or current_price
                if yfinance_quote.get("daily_change_pct") is not None:
                    daily_change = yfinance_quote.get("daily_change_pct")
                    daily_horizon = str(yfinance_quote.get("daily_change_horizon") or daily_horizon or "")
                updated_at = yfinance_quote.get("updated_at") or updated_at
            metric = {
                "Current_Price": current_price,
                "Return_1M": return_1m,
                "Daily_Change_Pct": daily_change,
                "Daily_Change_Horizon": daily_horizon,
                "Price_Updated_At": updated_at,
                "Price_Source": (snapshot or {}).get("source"),
            }
            for key in self.comparison_match_keys(ticker):
                metrics[key] = metric
        return metrics


@dataclass(frozen=True)
class SectorDetailReconciler:
    stock_detail_service: Callable[[], Any]
    first_float: Callable[..., float | None]

    def reconcile_members(self, members: list[dict]) -> list[dict]:
        if not members:
            return members

        def load_member(item: dict) -> dict:
            ticker = str(item.get("Ticker") or "").strip().upper()
            if not ticker:
                return item
            try:
                detail = self.stock_detail_service().detail(
                    ticker=ticker,
                    period="6mo",
                    refresh=False,
                    profile=False,
                )
                info = detail.get("info") or {}
                updated = dict(item)
                updated["Current_Price"] = self.first_float(item.get("Current_Price"), info.get("current_price"))
                updated["Daily_Change_Pct"] = self.first_float(item.get("Daily_Change_Pct"), info.get("daily_change_pct"))
                updated["Daily_Change_Horizon"] = str(
                    item.get("Daily_Change_Horizon")
                    or info.get("daily_change_horizon")
                    or ""
                )
                updated["MarketCap"] = self.first_float(info.get("market_cap"), item.get("MarketCap"))
                updated["Price_Updated_At"] = item.get("Price_Updated_At") or info.get("price_updated_at")
                return updated
            except Exception:
                return item

        workers = max(1, min(12, len(members)))
        results: list[dict | None] = [None] * len(members)
        with ThreadPoolExecutor(max_workers=workers) as executor:
            futures = {
                executor.submit(load_member, item): index
                for index, item in enumerate(members)
            }
            for future in as_completed(futures):
                results[futures[future]] = future.result()
        return [item if item is not None else members[index] for index, item in enumerate(results)]


@dataclass(frozen=True)
class SectorThemeClassifier:
    theme_overrides: dict[str, str]
    theme_rules: list[dict]
    theme_seed_groups: dict[str, list[tuple]]
    kr_code: Callable[[object], str | None]
    localized_company_name: Callable[[object, object, object], str]
    load_table: Callable[..., Any]
    df_to_records: Callable[[Any], list[dict]]
    load_portfolio: Callable[[str], tuple[dict, list[dict]]]
    load_simple: Callable[[str, list[str]], list[dict]]

    @staticmethod
    def compact(value: str) -> str:
        return re.sub(r"[\s/_\-.]+", "", str(value or "").lower())

    def term_matches(self, term: str, raw_text: str, compact_text: str) -> bool:
        lower = str(term or "").lower().strip()
        if not lower:
            return False
        compact = self.compact(lower)
        if re.fullmatch(r"[a-z0-9]+", compact or "") and len(compact) <= 3:
            return re.search(rf"(?<![a-z0-9]){re.escape(lower)}(?![a-z0-9])", raw_text) is not None
        return lower in raw_text or compact in compact_text

    def theme_label(self, ticker: str, name: str, sector: str | None = None) -> str:
        return self.theme_labels(ticker, name, sector)[0]

    def theme_labels(
        self,
        ticker: str,
        name: str,
        sector: str | None = None,
        explicit_theme: str | None = None,
    ) -> list[str]:
        symbol = str(ticker or "").strip().upper().split(".")[0]
        code = self.kr_code(ticker)
        ticker_keys = {key for key in (symbol, code) if key}
        raw_text = " ".join([str(ticker or ""), str(name or ""), str(sector or "")]).lower()
        compact_text = self.compact(raw_text)
        labels: list[str] = []

        def add(label: str | None) -> None:
            clean = str(label or "").strip()
            if clean and clean not in labels:
                labels.append(clean)

        add(explicit_theme)

        for key in ticker_keys:
            if key in self.theme_overrides:
                add(self.theme_overrides[key])

        for rule in self.theme_rules:
            if ticker_keys & set(rule["tickers"]):
                add(str(rule["label"]))
                continue
            for term in rule["terms"]:
                if self.term_matches(str(term), raw_text, compact_text):
                    add(str(rule["label"]))
                    break

        if labels:
            return labels
        return [self.fallback_theme_label(sector)]

    @staticmethod
    def fallback_theme_label(sector: str | None = None) -> str:
        clean_sector = str(sector or "").strip()
        lower_sector = clean_sector.lower()
        if lower_sector in {"", "unclassified", "unknown", "none", "nan", "-"}:
            return "기타"
        if "semiconductor" in lower_sector or "반도체" in clean_sector:
            return "반도체 설계"
        if (
            "software" in lower_sector
            or "information technology" in lower_sector
            or lower_sector == "technology"
            or "소프트웨어" in clean_sector
        ):
            return "클라우드/SW"
        if "communication" in lower_sector or "telecom" in lower_sector or "통신" in clean_sector:
            return "통신"
        if "전기" in clean_sector and "전자" in clean_sector:
            return "전자/부품"
        if (
            "utility" in lower_sector
            or "utilities" in lower_sector
            or "유틸" in clean_sector
            or "전력" in clean_sector
        ):
            return "전력/유틸리티"
        if "real estate" in lower_sector or "부동산" in clean_sector or "리츠" in clean_sector:
            return "리츠/부동산"
        if "materials" in lower_sector or "소재" in clean_sector or "철강" in clean_sector:
            return "소재/철강"
        if "financial" in lower_sector or "금융" in clean_sector:
            return "은행"
        if "health" in lower_sector or "바이오" in clean_sector or "제약" in clean_sector:
            return "헬스케어"
        if "energy" in lower_sector or "에너지" in clean_sector:
            return "에너지"
        if "오락" in clean_sector or "문화" in clean_sector:
            return "미디어/엔터"
        if "IT 서비스" in clean_sector:
            return "IT 서비스"
        if "consumer" in lower_sector or "소비" in clean_sector:
            return "소비/리테일"
        if "industrial" in lower_sector or "산업" in clean_sector:
            return "기계/로봇"
        return clean_sector or "기타"

    def stock_key(self, ticker: str) -> str:
        code = self.kr_code(ticker)
        return code or str(ticker or "").strip().upper()

    @staticmethod
    def source_priority(source: str) -> int:
        return {"Portfolio": 0, "SmallCap": 1, "Score": 2, "Universe": 3, "ThemeSeed": 4}.get(
            str(source or ""),
            9,
        )

    @staticmethod
    def is_equity_row(row: dict) -> bool:
        ticker = str(row.get("Ticker") or row.get("ticker") or "").strip().upper()
        sector = str(row.get("Sector") or row.get("sector") or "").strip()
        category = str(row.get("category") or row.get("Category") or row.get("AssetClass") or "").strip()
        quote_type = str(row.get("quoteType") or row.get("QuoteType") or row.get("Type") or "").strip()
        name = str(row.get("Name") or row.get("name") or "").strip()
        combined = " ".join([ticker, name, sector, category, quote_type]).upper()
        if not ticker:
            return False
        if ticker.startswith("^") or ticker.startswith("."):
            return False
        if quote_type.upper() == "ETF" or category.upper() == "ETF":
            return False
        if sector.upper().startswith("ETF") or " ETF" in combined or combined.endswith("ETF"):
            return False
        etf_prefixes = (
            "KODEX", "TIGER", "ACE", "RISE", "SOL", "HANARO", "TIMEFOLIO",
            "KIWOOM", "KOSEF", "KBSTAR", "ARIRANG", "KINDEX", "PLUS", "FOCUS",
            "BNK", "1Q", "WOORI", "TREX", "마이다스",
        )
        upper_name = name.upper()
        if any(upper_name.startswith(prefix) for prefix in etf_prefixes):
            return False
        etf_terms = (" ETF", " ETN", "액티브", "레버리지", "인버스", "커버드콜", "합성")
        if any(term in combined for term in etf_terms):
            return False
        return True

    def theme_seed_rows(self, market: str) -> list[dict]:
        safe_market = str(market or "").upper()
        rows: list[dict] = []
        for label, members in self.theme_seed_groups.items():
            for ticker, name, item_market, sector in members:
                if str(item_market).upper() != safe_market:
                    continue
                rows.append({
                    "Ticker": ticker,
                    "Name": self.localized_company_name(ticker, name, safe_market),
                    "Market": safe_market,
                    "Sector": sector,
                    "Theme": label,
                    "Source": "ThemeSeed",
                })
        return rows

    def market_rows(self, market: str) -> list[dict]:
        market = market.upper()
        rows: list[dict] = []

        universe = self.load_table(f"{market}_Universe", ["MarketCap"])
        if not universe.empty:
            rows.extend(
                row
                for row in (
                    {**row, "Source": "Universe", "Market": row.get("Market") or market}
                    for row in self.df_to_records(universe)
                )
                if self.is_equity_row(row)
            )

        _, portfolio_rows = self.load_portfolio(f"{market}_Final_Portfolio")
        rows.extend(
            row
            for row in ({**row, "Source": "Portfolio", "Market": row.get("Market") or market} for row in portfolio_rows)
            if self.is_equity_row(row)
        )

        small_rows = self.load_simple(
            f"{market}_SmallCap_Gems",
            ["Rank", "MarketCap", "Total_Score", "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin", "Volume_Surge"],
        )
        rows.extend(
            row
            for row in ({**row, "Source": "SmallCap", "Market": row.get("Market") or market} for row in small_rows)
            if self.is_equity_row(row)
        )

        scored_rows = self.load_simple(
            f"{market}_Scored_Stocks",
            [
                "Rank", "MarketCap", "Total_Score", "Final_Score", "Combined_Score",
                "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin",
            ],
        )
        rows.extend(
            row
            for row in ({**row, "Source": "Score", "Market": row.get("Market") or market} for row in scored_rows)
            if self.is_equity_row(row)
        )
        rows.extend(self.theme_seed_rows(market))
        return rows


@dataclass(frozen=True)
class SectorThemePayloadBuilder:
    theme_order: list[str]
    price_request_limit: int
    detail_reconcile_limit: int
    sector_market_rows: Callable[[str], list[dict]]
    infer_market_from_ticker: Callable[[str], str]
    localized_company_name: Callable[[object, object, object], str]
    first_float: Callable[..., float | None]
    sector_theme_labels: Callable[..., list[str]]
    sector_stock_key: Callable[[str], str]
    sector_source_priority: Callable[[str], int]
    safe_float: Callable[[object], float | None]
    sector_compact: Callable[[str], str]
    sector_price_metric_map: Callable[..., dict[str, dict]]
    comparison_match_keys: Callable[[str], set[str]]
    comparison_currency: Callable[[str, str | None], str]
    sector_usdkrw_rate: Callable[[], float]
    sector_market_cap_weighted_value: Callable[[list[dict], str, float], float | None]
    sector_market_cap_usd_sort_value: Callable[[dict, float], float | None]
    sector_detail_reconcile_members: Callable[[list[dict]], list[dict]]
    utc_now_iso: Callable[[], str]

    def payload(
        self,
        market: str = "ALL",
        limit: int = 36,
        members: int = 120,
        focus_label: str | None = None,
    ) -> dict:
        safe_market = str(market or "ALL").upper()
        if safe_market not in {"ALL", "US", "KR"}:
            safe_market = "ALL"
        safe_limit = max(1, min(int(limit or 36), 60))
        safe_members = max(3, min(int(members or 120), 200))
        markets = ["US", "KR"] if safe_market == "ALL" else [safe_market]

        by_theme_key: dict[tuple[str, str], dict] = {}
        for current_market in markets:
            for raw_row in self.sector_market_rows(current_market):
                row = dict(raw_row)
                ticker = str(row.get("Ticker") or row.get("ticker") or "").strip().upper()
                if not ticker:
                    continue
                row["Ticker"] = ticker
                row["Market"] = str(row.get("Market") or self.infer_market_from_ticker(row["Ticker"])).upper()
                row["Name"] = self.localized_company_name(
                    row["Ticker"],
                    row.get("Name") or row.get("name") or row["Ticker"],
                    row.get("Market"),
                )
                row["Sector"] = row.get("Sector") or row.get("sector") or ""
                row["MarketCap"] = self.first_float(row.get("MarketCap"), row.get("market_cap"))
                row["Score_Value"] = self.first_float(
                    row.get("Total_Score"),
                    row.get("Final_Score"),
                    row.get("Combined_Score"),
                )
                themes = self.sector_theme_labels(
                    row["Ticker"],
                    str(row["Name"]),
                    str(row.get("Sector") or ""),
                    explicit_theme=row.get("Theme"),
                )
                for theme in themes:
                    themed_row = {**row, "Theme": theme}
                    key = (str(theme), self.sector_stock_key(ticker))
                    existing = by_theme_key.get(key)
                    if existing is None:
                        by_theme_key[key] = themed_row
                        continue
                    current_priority = self.sector_source_priority(str(themed_row.get("Source")))
                    existing_priority = self.sector_source_priority(str(existing.get("Source")))
                    if current_priority < existing_priority:
                        by_theme_key[key] = themed_row
                    elif (
                        current_priority == existing_priority
                        and self.safe_float(existing.get("MarketCap")) is None
                        and self.safe_float(themed_row.get("MarketCap")) is not None
                    ):
                        by_theme_key[key] = themed_row

        rows = list(by_theme_key.values())

        grouped: dict[str, list[dict]] = {}
        for row in rows:
            grouped.setdefault(str(row["Theme"]), []).append(row)

        focus_key = self.sector_compact(str(focus_label or ""))
        if focus_key:
            grouped = {
                label: group_rows
                for label, group_rows in grouped.items()
                if self.sector_compact(str(label)) == focus_key
            }
        price_requests: dict[str, list[str]] = {"US": [], "KR": []}
        live_price_requests: dict[str, list[str]] = {"US": [], "KR": []}
        if focus_key:
            for group_rows in grouped.values():
                for row in group_rows:
                    row_market = str(row.get("Market") or "").upper()
                    if row_market in price_requests:
                        ticker = str(row.get("Ticker") or "")
                        price_requests[row_market].append(ticker)
                        live_price_requests[row_market].append(ticker)
        for group_rows in grouped.values():
            for row in group_rows:
                row_market = str(row.get("Market") or "").upper()
                if row_market in price_requests and len(price_requests[row_market]) < self.price_request_limit:
                    price_requests[row_market].append(str(row.get("Ticker") or ""))

        price_maps = {
            current_market: self.sector_price_metric_map(
                current_market,
                list(dict.fromkeys(tickers)),
                live_tickers=list(dict.fromkeys(live_price_requests[current_market])) if focus_key else None,
            )
            for current_market, tickers in price_requests.items()
        }

        themes: list[dict] = []
        usdkrw_rate = self.sector_usdkrw_rate()
        for label, group_rows in grouped.items():
            enriched_members = []
            for row in group_rows:
                metric = {}
                row_market = str(row.get("Market") or "").upper()
                for key in self.comparison_match_keys(str(row.get("Ticker") or "")):
                    metric = price_maps.get(row_market, {}).get(key) or metric
                    if metric:
                        break
                daily_change = self.first_float(metric.get("Daily_Change_Pct"), row.get("Daily_Change_Pct"))
                daily_horizon = str(metric.get("Daily_Change_Horizon") or row.get("Daily_Change_Horizon") or "")
                return_1m = self.first_float(metric.get("Return_1M"), row.get("Return_1M"), row.get("Mom_1M"))
                current_price = self.first_float(metric.get("Current_Price"), row.get("Current_Price"))
                enriched_members.append({
                    "Ticker": row.get("Ticker"),
                    "Name": self.localized_company_name(row.get("Ticker"), row.get("Name"), row.get("Market")),
                    "Market": row.get("Market"),
                    "Sector": row.get("Sector"),
                    "Currency": self.comparison_currency(str(row.get("Ticker") or ""), str(row.get("Market") or "")),
                    "Source": row.get("Source"),
                    "MarketCap": row.get("MarketCap"),
                    "Current_Price": current_price,
                    "Daily_Change_Pct": daily_change,
                    "Daily_Change_Horizon": daily_horizon,
                    "Price_Updated_At": metric.get("Price_Updated_At") or row.get("Price_Updated_At"),
                    "Price_Source": metric.get("Price_Source") or row.get("Price_Source"),
                    "Return_1M": return_1m,
                    "Score_Value": row.get("Score_Value"),
                    "In_Portfolio": row.get("Source") == "Portfolio",
                    "In_SmallCap": row.get("Source") == "SmallCap",
                })

            coverage = [item for item in enriched_members if self.safe_float(item.get("Daily_Change_Pct")) is not None]
            one_month = [item for item in enriched_members if self.safe_float(item.get("Return_1M")) is not None]
            coverage_ratio = len(coverage) / len(enriched_members) if enriched_members else 0.0
            avg_change = self.sector_market_cap_weighted_value(coverage, "Daily_Change_Pct", usdkrw_rate)
            avg_return_1m = self.sector_market_cap_weighted_value(one_month, "Return_1M", usdkrw_rate)
            rising_count = sum(1 for item in coverage if (self.safe_float(item.get("Daily_Change_Pct")) or 0) > 0)
            movement_sorted_members = sorted(
                enriched_members,
                key=lambda item: (
                    self.safe_float(item.get("Daily_Change_Pct")) is None,
                    -abs(self.safe_float(item.get("Daily_Change_Pct")) or 0),
                    -(self.safe_float(item.get("MarketCap")) or 0),
                ),
            )
            market_cap_sorted_members = sorted(
                enriched_members,
                key=lambda item: (
                    self.sector_market_cap_usd_sort_value(item, usdkrw_rate) is None,
                    -(self.sector_market_cap_usd_sort_value(item, usdkrw_rate) or 0),
                    self.safe_float(item.get("Daily_Change_Pct")) is None,
                    -abs(self.safe_float(item.get("Daily_Change_Pct")) or 0),
                    str(item.get("Name") or item.get("Ticker") or ""),
                ),
            )
            display_members = [
                item for item in market_cap_sorted_members
                if self.safe_float(item.get("Daily_Change_Pct")) is not None
            ] or market_cap_sorted_members
            payload_members = display_members[:safe_members]
            payload_coverage = coverage
            payload_avg_change = avg_change
            payload_rising_count = rising_count
            payload_leader = next(
                (item for item in movement_sorted_members if self.safe_float(item.get("Daily_Change_Pct")) is not None),
                None,
            )
            if focus_key and self.sector_compact(str(label)) == focus_key:
                reconciled_members = self.sector_detail_reconcile_members(payload_members[:self.detail_reconcile_limit])
                payload_members = reconciled_members + payload_members[self.detail_reconcile_limit:]
                payload_coverage = [item for item in payload_members if self.safe_float(item.get("Daily_Change_Pct")) is not None]
            themes.append({
                "label": label,
                "market": safe_market,
                "member_count": len(enriched_members),
                "priced_count": len(payload_coverage),
                "missing_price_count": max(0, len(enriched_members) - len(payload_coverage)),
                "price_coverage_ratio": len(payload_coverage) / len(enriched_members) if enriched_members else coverage_ratio,
                "weighting_method": "market_cap_usd",
                "rising_count": payload_rising_count,
                "falling_count": len(payload_coverage) - payload_rising_count,
                "avg_change_pct": payload_avg_change,
                "avg_return_1m": avg_return_1m,
                "leader": payload_leader,
                "members": payload_members,
            })

        order_index = {label: index for index, label in enumerate(self.theme_order)}

        def top_eligible(item: dict) -> bool:
            return (
                int(item.get("member_count") or 0) >= 3
                and int(item.get("priced_count") or 0) >= 3
                and float(item.get("price_coverage_ratio") or 0) >= 0.7
                and self.safe_float(item.get("avg_change_pct")) is not None
            )

        themes.sort(
            key=lambda item: (
                not top_eligible(item),
                self.safe_float(item.get("avg_change_pct")) is None,
                -abs(self.safe_float(item.get("avg_change_pct")) or 0),
                order_index.get(str(item.get("label")), 99),
            )
        )
        return {
            "market": safe_market,
            "generated_at": self.utc_now_iso(),
            "count": min(len(themes), safe_limit),
            "items": themes[:safe_limit],
        }


@dataclass(frozen=True)
class SectorApiService:
    cached: Callable
    invalidate: Callable[[str], None]
    payload: Callable[..., dict]

    def sector_themes(self, market: str = "ALL", limit: int = 36, members: int = 120, refresh: bool = False) -> dict:
        safe_market = str(market or "ALL").upper()
        if safe_market not in {"ALL", "US", "KR"}:
            safe_market = "ALL"
        safe_limit = max(1, min(int(limit or 36), 72))
        safe_members = max(1, min(int(members or 120), 240))
        cache_key = f"sector_themes_{safe_market}_{safe_limit}_{safe_members}"
        if refresh:
            self.invalidate(cache_key)
        return self.cached(
            cache_key,
            lambda: apply_localized_names(self.payload(safe_market, safe_limit, safe_members)),
            ttl=900,
        )

    def sector_theme_summary(self, market: str = "ALL", limit: int = 36, refresh: bool = False) -> dict:
        safe_market = self._safe_market(market)
        safe_limit = max(1, min(int(limit or 36), 72))
        cache_key = f"sector_theme_summary_{safe_market}_{safe_limit}"
        if refresh:
            self.invalidate(cache_key)

        def load() -> dict:
            payload = self.sector_themes(safe_market, limit=safe_limit, members=12, refresh=refresh)
            return self._summary_payload(payload)

        return self.cached(cache_key, load, ttl=900)

    def sector_theme_detail(
        self,
        label: str,
        market: str = "ALL",
        members: int = 200,
        refresh: bool = False,
    ) -> dict:
        safe_label = str(label or "").strip()
        if not safe_label:
            raise HTTPException(400, "label is required")
        safe_market = self._safe_market(market)
        safe_members = max(1, min(int(members or 200), 240))
        label_key = self._label_key(safe_label)
        cache_key = f"sector_theme_detail_{safe_market}_{label_key}_{safe_members}"
        if refresh:
            self.invalidate(cache_key)

        def load() -> dict:
            wanted_keys = self._label_candidate_keys(safe_label)
            payload = self.payload(safe_market, 72, safe_members, safe_label)
            item = self._find_theme(payload, wanted_keys)
            if item is None and not refresh:
                summary_payload = self.sector_theme_summary(safe_market, limit=72, refresh=False)
                summary_item = self._find_theme(summary_payload, wanted_keys)
                if summary_item is not None:
                    resolved_label = str(summary_item.get("label") or safe_label)
                    payload = self.payload(safe_market, 72, safe_members, resolved_label)
                    item = self._find_theme(payload, {self._label_key(resolved_label), *wanted_keys})
            if item is None:
                raise HTTPException(404, "sector theme not found")
            return {
                "market": payload.get("market") or safe_market,
                "generated_at": payload.get("generated_at"),
                "item": apply_localized_names(self._trim_members(item, safe_members)),
            }

        return self.cached(cache_key, load, ttl=900)

    @classmethod
    def _safe_market(cls, market: str) -> str:
        safe_market = str(market or "ALL").upper()
        if safe_market not in {"ALL", "US", "KR"}:
            safe_market = "ALL"
        return safe_market

    @staticmethod
    def _label_key(label: object) -> str:
        return " ".join(str(label or "").strip().lower().split())

    @classmethod
    def _label_candidates(cls, label: object) -> list[str]:
        raw = str(label or "").strip()
        candidates: list[str] = []

        def add(value: object) -> None:
            clean = str(value or "").strip()
            if clean and clean not in candidates:
                candidates.append(clean)

        add(raw)
        for separator in ("/", "|", ",", "·"):
            if separator in raw:
                for part in raw.split(separator):
                    add(part)
        return candidates

    @classmethod
    def _label_candidate_keys(cls, label: object) -> set[str]:
        return {cls._label_key(candidate) for candidate in cls._label_candidates(label)}

    @classmethod
    def _focus_label(cls, label: object) -> str:
        return cls._label_candidates(label)[0]

    @classmethod
    def _find_theme(cls, payload: dict, wanted_keys: set[str]) -> dict | None:
        return next(
            (
                theme
                for theme in payload.get("items", [])
                if cls._label_key(theme.get("label")) in wanted_keys
            ),
            None,
        )

    @staticmethod
    def _trim_members(item: dict, members: int) -> dict:
        item_members = item.get("members")
        if not isinstance(item_members, list) or len(item_members) <= members:
            return item
        trimmed = dict(item)
        trimmed["members"] = item_members[:members]
        return trimmed

    @staticmethod
    def _summary_payload(payload: dict) -> dict:
        items = []
        for theme in payload.get("items", []):
            if not isinstance(theme, dict):
                continue
            summary = {
                key: value
                for key, value in theme.items()
                if key != "members"
            }
            summary["members"] = []
            items.append(summary)
        return {
            "market": payload.get("market") or "ALL",
            "generated_at": payload.get("generated_at"),
            "count": len(items),
            "items": items,
            "mode": "summary",
        }
