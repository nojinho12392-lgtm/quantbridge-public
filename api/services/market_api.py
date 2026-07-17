from __future__ import annotations

import math
import html
import re
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, time, timedelta, timezone
from typing import Any

from fastapi import HTTPException
import pandas as pd
import requests


@dataclass(frozen=True)
class MarketIndicatorPayloadBuilder:
    repository: Callable[[], Any]
    indicator_specs: Callable[[str], list[dict]]
    stored_indicator_latest: Callable[[list[str]], dict[str, dict]]
    stored_indicator_items: Callable[[list[dict], dict[str, dict]], list[dict]]
    merge_naver_domestic_indicator_quotes: Callable[[list[dict], list[dict]], list[dict]]
    allow_api_external_fetch: Callable[[], bool]
    utc_now_iso: Callable[[], str]
    merge_closed_regular_index_quotes: Callable[[list[dict], list[dict]], list[dict]]
    indicator_quote_from_yfinance: Callable[[Any, dict], dict | None]
    is_naver_domestic_interest_symbol: Callable[[str], bool]
    load_naver_market_indicator_quotes: Callable[[list[dict]], list[dict]]
    market_history_periods: dict[str, int]
    empty_frame: Callable[[], Any]
    stored_indicator_history_is_sparse: Callable[[Any, list[str], str], bool]
    fetch_indicator_history: Callable[[list[str], str, str], list[dict]]
    df_to_records: Callable[[Any], list[dict]]
    latest_naver_domestic_indicator_history_points: Callable[[list[str]], list[dict]]
    merge_indicator_history_records: Callable[[list[dict], list[dict]], list[dict]]
    market_indicator_specs: list[dict]
    safe_float: Callable[[object], float | None]
    filter_indicator_history_points_for_period: Callable[[str, list[dict], str], list[dict]]
    spec_public_fields: Callable[[dict], dict]

    def load_market_indicators(self, category: str, refresh: bool = False) -> dict:
        specs = self.indicator_specs(category)
        symbols = [spec["symbol"] for spec in specs]
        stored = self.stored_indicator_latest(symbols)
        if stored and not refresh:
            items = self.stored_indicator_items(specs, stored)
            if items:
                items = self.merge_naver_domestic_indicator_quotes(items, specs)
                if self.allow_api_external_fetch():
                    items = self.merge_closed_regular_index_quotes(items, specs)
                return {
                    "items": items,
                    "count": len(items),
                    "updated_at": self.utc_now_iso(),
                    "source": "storage",
                    "errors": [],
                }

        if not self.allow_api_external_fetch():
            items = self.stored_indicator_items(specs, stored)
            items = self.merge_naver_domestic_indicator_quotes(items, specs)
            return {
                "items": items,
                "count": len(items),
                "updated_at": self.utc_now_iso(),
                "source": "storage",
                "errors": [] if items else ["storage_miss"],
            }

        items = []
        errors = []

        try:
            import yfinance as yf
        except ImportError:
            yf = None
            errors.append("yfinance is not installed")

        if yf is not None:
            for spec in specs:
                if self.is_naver_domestic_interest_symbol(spec["symbol"]):
                    continue
                try:
                    quote = self.indicator_quote_from_yfinance(yf, spec)
                    if quote:
                        items.append(quote)
                except Exception as exc:
                    errors.append(f"{spec['symbol']}: {type(exc).__name__}")

        by_symbol = {item["symbol"]: item for item in items}
        for item in self.load_naver_market_indicator_quotes(specs):
            by_symbol[item["symbol"]] = item
        for spec in specs:
            if spec["symbol"] not in by_symbol and spec["symbol"] in stored:
                by_symbol[spec["symbol"]] = {**stored[spec["symbol"]], **self.spec_public_fields(spec)}

        items = sorted(by_symbol.values(), key=lambda item: int(item.get("sort_order") or 999))
        items = self.merge_closed_regular_index_quotes(items, specs)
        if items:
            try:
                self.repository().upsert_market_indicators(items, source="yfinance")
            except Exception:
                pass

        return {
            "items": items,
            "count": len(items),
            "updated_at": self.utc_now_iso(),
            "source": "yfinance+storage" if stored else "yfinance",
            "errors": errors[:8],
        }

    def market_indicator_history_payload(
        self,
        symbols: list[str],
        period: str,
        interval: str,
        refresh: bool = False,
    ) -> dict:
        start_at = datetime.now(timezone.utc) - timedelta(days=self.market_history_periods[period])
        stored = self.empty_frame()
        if not refresh or not self.allow_api_external_fetch():
            stored = self.repository().read_market_indicator_history(symbols=symbols, start_at=start_at)

        fetched: list[dict] = []
        if (
            self.allow_api_external_fetch()
            and (refresh or stored.empty or self.stored_indicator_history_is_sparse(stored, symbols, interval))
        ):
            fetched = self.fetch_indicator_history(symbols, period, interval)
            if fetched:
                try:
                    self.repository().upsert_market_indicators(fetched, source="yfinance")
                except Exception:
                    pass
                stored = self.empty_frame().__class__(fetched)

        if stored.empty:
            records: list[dict] = []
        else:
            records = self.df_to_records(stored)

        latest_points = self.latest_naver_domestic_indicator_history_points(symbols) if period == "1d" else []
        records = self.merge_indicator_history_records(records, latest_points)

        spec_by_symbol = {spec["symbol"]: spec for spec in self.market_indicator_specs}
        series = []
        for symbol in symbols:
            spec = spec_by_symbol.get(
                symbol,
                {"symbol": symbol, "label": symbol, "category": "", "region": "", "sort_order": 999},
            )
            points = [
                {
                    "timestamp": item.get("observed_at") or item.get("timestamp"),
                    "open": self.safe_float(item.get("open")),
                    "high": self.safe_float(item.get("high")),
                    "low": self.safe_float(item.get("low")),
                    "close": self.safe_float(item.get("close") or item.get("value")),
                    "volume": self.safe_float(item.get("volume")),
                }
                for item in records
                if str(item.get("symbol") or "").upper() == symbol
            ]
            points = [point for point in points if point["timestamp"] and point["close"] is not None]
            points = self.filter_indicator_history_points_for_period(symbol, points, period)
            series.append({**self.spec_public_fields(spec), "points": points})

        if not stored.empty:
            source = "storage" if not fetched else "yfinance"
        elif latest_points:
            source = "naver"
        else:
            source = "storage_miss" if not self.allow_api_external_fetch() else "yfinance"

        return {
            "period": period,
            "interval": interval,
            "series": series,
            "updated_at": self.utc_now_iso(),
            "source": source,
        }


@dataclass(frozen=True)
class MarketIndicatorStorageBuilder:
    market_indicator_specs: list[dict]
    market_indicator_symbols: list[str]
    us_regular_index_symbols: set[str]
    kr_regular_index_symbols: set[str]
    us_market_tz: Any
    kst: Any
    repository: Callable[[], Any]
    df_to_records: Callable[[Any], list[dict]]
    safe_float: Callable[[object], float | None]
    first_float: Callable[..., float | None]
    utc_now_iso: Callable[[], str]
    load_naver_market_indicator_quotes: Callable[[list[dict]], list[dict]]
    indicator_daily_close_quote_from_yfinance: Callable[..., dict | None]
    previous_regular_close_loader: Callable[[str, object], float | None] | None = None

    def stored_indicator_items(self, specs: list[dict], stored: dict[str, dict]) -> list[dict]:
        items = []
        for spec in specs:
            item = stored.get(spec["symbol"])
            if not item:
                continue
            items.append(self.with_regular_previous_close_change_from_storage({**item, **self.spec_public_fields(spec)}))
        return sorted(items, key=lambda item: int(item.get("sort_order") or 999))

    def with_regular_previous_close_change_from_storage(self, item: dict) -> dict:
        symbol = str(item.get("symbol") or "").strip().upper()
        if self.regular_index_zone(symbol) is None:
            return item
        current = self.first_float(item.get("value"), item.get("close"))
        previous_close_loader = self.previous_regular_close_loader or self.previous_regular_close_from_storage
        previous_close = previous_close_loader(
            symbol,
            item.get("observed_at") or item.get("updated_at"),
        )
        if current is None or previous_close is None or previous_close <= 0:
            return item
        return {
            **item,
            "change_abs": current - previous_close,
            "change_pct": current / previous_close - 1.0,
        }

    def previous_regular_close_from_storage(self, symbol: str, observed_at: object) -> float | None:
        zone = self.regular_index_zone(symbol)
        if zone is None:
            return None
        observed = self.indicator_observed_datetime({"observed_at": observed_at}) or datetime.now(timezone.utc)
        local_date = observed.astimezone(zone).date()
        try:
            df = self.repository().read_market_indicator_history(
                symbols=[symbol],
                start_at=observed - timedelta(days=10),
            )
        except Exception:
            return None
        if df.empty:
            return None
        candidates: list[tuple[datetime, float]] = []
        for record in self.df_to_records(df):
            record_time = self.indicator_observed_datetime(record)
            price = self.first_float(record.get("open"), record.get("close"), record.get("value"))
            if record_time is None or price is None:
                continue
            local_time = record_time.astimezone(zone)
            if local_time.date() < local_date:
                previous_price = self.first_float(record.get("close"), record.get("value"), record.get("open"))
                if previous_price is not None:
                    candidates.append((record_time, previous_price))
        if not candidates:
            return None
        candidates.sort(key=lambda item: item[0])
        return candidates[-1][1]

    def merge_naver_domestic_indicator_quotes(self, items: list[dict], specs: list[dict]) -> list[dict]:
        overrides = {item["symbol"]: item for item in self.load_naver_market_indicator_quotes(specs)}
        if not overrides:
            return items
        merged = [overrides.get(str(item.get("symbol") or ""), item) for item in items]
        return sorted(merged, key=lambda item: int(item.get("sort_order") or 999))

    def merge_closed_regular_index_quotes(self, items: list[dict], specs: list[dict]) -> list[dict]:
        closed_specs = [
            spec
            for spec in specs
            if self.should_use_latest_regular_close(spec["symbol"])
        ]
        if not closed_specs:
            return items

        try:
            import yfinance as yf
        except ImportError:
            return items

        existing_by_symbol = {str(item.get("symbol") or "").upper(): item for item in items}
        overrides = {}
        for spec in closed_specs:
            try:
                ticker = yf.Ticker(spec["symbol"])
                quote = self.indicator_daily_close_quote_from_yfinance(ticker, spec, regular_session=True)
                if quote and not self.indicator_quote_is_older(quote, existing_by_symbol.get(spec["symbol"])):
                    overrides[spec["symbol"]] = quote
            except Exception:
                continue
        if not overrides:
            return items
        merged = [overrides.get(str(item.get("symbol") or ""), item) for item in items]
        return sorted(merged, key=lambda item: int(item.get("sort_order") or 999))

    def indicator_specs(self, category: str = "all") -> list[dict]:
        if category in {"", "all", "ALL"}:
            return list(self.market_indicator_specs)
        return [spec for spec in self.market_indicator_specs if spec["category"] == category]

    @staticmethod
    def spec_public_fields(spec: dict) -> dict:
        return {
            "symbol": spec["symbol"],
            "label": spec["label"],
            "category": spec["category"],
            "region": spec["region"],
            "sort_order": spec["sort_order"],
        }

    def should_use_latest_regular_close(self, symbol: str) -> bool:
        symbol = str(symbol or "").strip().upper()
        if symbol not in self.us_regular_index_symbols:
            return False
        return not self.is_regular_index_session_open(symbol)

    def is_regular_index_session_open(self, symbol: str, now: datetime | None = None) -> bool:
        zone = self.regular_index_zone(symbol)
        if zone is None:
            return False
        local = (now or datetime.now(timezone.utc)).astimezone(zone)
        if local.weekday() >= 5:
            return False
        open_time, close_time = self.regular_index_session_times(symbol)
        return open_time <= local.time() <= close_time

    def regular_index_zone(self, symbol: str):
        symbol = str(symbol or "").strip().upper()
        if symbol in self.us_regular_index_symbols:
            return self.us_market_tz
        if symbol in self.kr_regular_index_symbols:
            return self.kst
        return None

    def regular_index_session_times(self, symbol: str) -> tuple[time, time]:
        symbol = str(symbol or "").strip().upper()
        if symbol in self.kr_regular_index_symbols:
            return time(9, 0), time(15, 30)
        return time(9, 30), time(16, 0)

    def daily_index_observed_at(self, index_value, symbol: str) -> str:
        zone = self.regular_index_zone(symbol) or timezone.utc
        _, close_time = self.regular_index_session_times(symbol)
        try:
            parsed = pd.to_datetime(index_value, errors="coerce")
            if pd.isna(parsed):
                return self.utc_now_iso()
            dt = parsed.to_pydatetime()
            local = dt.astimezone(zone) if dt.tzinfo else dt.replace(tzinfo=zone)
            observed = datetime(
                local.year,
                local.month,
                local.day,
                close_time.hour,
                close_time.minute,
                tzinfo=zone,
            )
            return observed.astimezone(timezone.utc).replace(microsecond=0).isoformat()
        except Exception:
            return self.utc_now_iso()

    def indicator_quote_is_older(self, candidate: dict | None, existing: dict | None) -> bool:
        candidate_time = self.indicator_observed_datetime(candidate)
        existing_time = self.indicator_observed_datetime(existing)
        return candidate_time is not None and existing_time is not None and candidate_time < existing_time

    @staticmethod
    def indicator_observed_datetime(item: dict | None) -> datetime | None:
        if not item:
            return None
        raw = item.get("observed_at") or item.get("updated_at") or item.get("timestamp")
        if raw is None:
            return None
        try:
            parsed = pd.to_datetime(raw, utc=True, errors="coerce")
            if pd.isna(parsed):
                return None
            return parsed.to_pydatetime()
        except Exception:
            return None

    def stored_indicator_latest(self, symbols: list[str]) -> dict[str, dict]:
        df = self.repository().read_market_indicator_latest(symbols=symbols)
        if df.empty:
            return {}
        records = self.df_to_records(df)
        return {
            str(item.get("symbol") or "").upper(): {
                "symbol": str(item.get("symbol") or "").upper(),
                "label": item.get("label"),
                "category": item.get("category"),
                "region": item.get("region"),
                "value": self.safe_float(item.get("value")),
                "change_abs": self.safe_float(item.get("change_abs")),
                "change_pct": self.safe_float(item.get("change_pct")),
                "close": self.safe_float(item.get("close") or item.get("value")),
                "observed_at": item.get("observed_at"),
                "updated_at": item.get("observed_at"),
                "source": item.get("source") or "storage",
            }
            for item in records
            if item.get("symbol") and self.safe_float(item.get("value")) is not None
        }

    def selected_indicator_symbols(self, raw: str) -> list[str]:
        allowed = set(self.market_indicator_symbols)
        requested = [item.strip().upper() for item in str(raw or "").split(",") if item.strip()]
        selected = [symbol for symbol in requested if symbol in allowed]
        return selected or list(self.market_indicator_symbols)

    @staticmethod
    def history_frame_for_symbol(raw: pd.DataFrame, symbol: str, single_symbol: bool) -> pd.DataFrame:
        if single_symbol and not isinstance(raw.columns, pd.MultiIndex):
            return raw
        if isinstance(raw.columns, pd.MultiIndex):
            level0 = set(map(str, raw.columns.get_level_values(0)))
            level1 = set(map(str, raw.columns.get_level_values(1)))
            if symbol in level0:
                return raw[symbol].copy()
            if symbol in level1:
                return raw.xs(symbol, axis=1, level=1).copy()
        return pd.DataFrame()


@dataclass(frozen=True)
class MarketIndicatorQuoteBuilder:
    safe_float: Callable[[object], float | None]
    fast_info_get: Callable[[Any, str], Any]
    should_use_latest_regular_close: Callable[[str], bool]
    daily_close_quote_from_yfinance: Callable[..., dict | None]
    is_regular_index_session_open: Callable[[str], bool]
    intraday_quote_from_yfinance: Callable[[Any, dict], dict | None]
    regular_index_zone: Callable[[str], Any]
    spec_public_fields: Callable[[dict], dict]
    utc_now_iso: Callable[[], str]
    previous_regular_close_loader: Callable[[Any, str], float | None]
    daily_index_observed_at: Callable[[Any, str], str]

    def indicator_quote_from_yfinance(self, yf, spec: dict) -> dict | None:
        ticker = yf.Ticker(spec["symbol"])
        if self.should_use_latest_regular_close(spec["symbol"]):
            daily_quote = self.daily_close_quote_from_yfinance(ticker, spec, regular_session=True)
            if daily_quote:
                return daily_quote

        if self.is_regular_index_session_open(spec["symbol"]):
            intraday_quote = self.intraday_quote_from_yfinance(ticker, spec)
            if intraday_quote:
                return intraday_quote

        fast_info = getattr(ticker, "fast_info", {}) or {}
        last = self.safe_float(self.fast_info_get(fast_info, "last_price"))
        previous = self.safe_float(self.fast_info_get(fast_info, "previous_close"))

        if last is None or previous is None or previous == 0:
            daily_quote = self.daily_close_quote_from_yfinance(
                ticker,
                spec,
                regular_session=self.regular_index_zone(spec["symbol"]) is not None,
            )
            if daily_quote:
                return daily_quote

        if last is None or previous is None or previous == 0:
            return None

        change_abs = last - previous
        payload = {
            **self.spec_public_fields(spec),
            "value": last,
            "change_abs": change_abs,
            "change_pct": change_abs / previous,
            "close": last,
            "observed_at": self.utc_now_iso(),
            "updated_at": datetime.now().strftime("%Y-%m-%d %H:%M"),
            "source": "yfinance",
        }
        if self.regular_index_zone(spec["symbol"]) is not None:
            session_open = self.is_regular_index_session_open(spec["symbol"])
            payload["is_regular_session_open"] = session_open
            payload["session"] = "open" if session_open else "closed"
        return payload

    def indicator_intraday_quote_from_yfinance(self, ticker, spec: dict) -> dict | None:
        symbol = str(spec.get("symbol") or "").strip().upper()
        zone = self.regular_index_zone(symbol)
        if zone is None:
            return None

        previous = self.previous_regular_close_loader(ticker, symbol)
        if previous is None or previous <= 0:
            return None

        today = datetime.now(timezone.utc).astimezone(zone).date()
        for interval in ("5m", "1m", "15m"):
            try:
                history = ticker.history(period="1d", interval=interval, auto_adjust=False)
            except Exception:
                continue
            if history is None or history.empty or "Close" not in history:
                continue
            frame = history.dropna(subset=["Close"])
            if frame.empty:
                continue
            ts = frame.index[-1]
            observed = pd.to_datetime(ts, utc=True, errors="coerce")
            if pd.isna(observed):
                continue
            observed_dt = observed.to_pydatetime().replace(microsecond=0)
            if observed_dt.astimezone(zone).date() != today:
                continue
            row = frame.iloc[-1]
            last = self.safe_float(row.get("Close"))
            if last is None:
                continue
            change_abs = last - previous
            return {
                **self.spec_public_fields(spec),
                "value": last,
                "change_abs": change_abs,
                "change_pct": change_abs / previous,
                "open": self.safe_float(row.get("Open")) or last,
                "high": self.safe_float(row.get("High")) or last,
                "low": self.safe_float(row.get("Low")) or last,
                "close": last,
                "volume": self.safe_float(row.get("Volume")),
                "observed_at": observed_dt.isoformat(),
                "updated_at": observed_dt.isoformat(),
                "source": f"yfinance_intraday_{interval}",
                "is_regular_session_open": True,
                "session": "open",
            }
        return None

    def previous_regular_close_from_yfinance(self, ticker, symbol: str) -> float | None:
        zone = self.regular_index_zone(symbol) or timezone.utc
        today = datetime.now(timezone.utc).astimezone(zone).date()
        try:
            history = ticker.history(period="10d", interval="1d", auto_adjust=False)
        except Exception:
            return None
        closes = history["Close"].dropna() if "Close" in history else pd.Series(dtype=float)
        if closes.empty:
            return None
        for index_value, close_value in reversed(list(closes.items())):
            observed = pd.to_datetime(index_value, utc=True, errors="coerce")
            if pd.isna(observed):
                continue
            if observed.to_pydatetime().astimezone(zone).date() < today:
                close = self.safe_float(close_value)
                if close is not None:
                    return close
        return None

    def indicator_daily_close_quote_from_yfinance(
        self,
        ticker,
        spec: dict,
        regular_session: bool = False,
    ) -> dict | None:
        history = ticker.history(period="10d", interval="1d", auto_adjust=False)
        if history is None or history.empty or "Close" not in history:
            return None
        frame = history.dropna(subset=["Close"])
        if frame.empty:
            return None

        row = frame.iloc[-1]
        last = self.safe_float(row.get("Close"))
        open_price = self.safe_float(row.get("Open"))
        previous = None
        if len(frame) >= 2:
            previous = self.safe_float(frame["Close"].iloc[-2])

        base = previous
        if last is None or base is None or base == 0:
            return None

        change_abs = last - base
        observed_at = self.daily_index_observed_at(frame.index[-1], spec["symbol"]) if regular_session else self.utc_now_iso()
        payload = {
            **self.spec_public_fields(spec),
            "value": last,
            "change_abs": change_abs,
            "change_pct": change_abs / base,
            "open": open_price if open_price is not None else last,
            "high": self.safe_float(row.get("High")) or last,
            "low": self.safe_float(row.get("Low")) or last,
            "close": last,
            "observed_at": observed_at,
            "updated_at": observed_at,
            "source": "yfinance_daily_close",
        }
        if regular_session:
            payload["is_regular_session_open"] = False
            payload["session"] = "closed"
        return payload


@dataclass(frozen=True)
class MarketIndicatorHistoryBuilder:
    market_indicator_specs: list[dict]
    market_history_periods: dict[str, int]
    naver_domestic_index_codes: dict[str, str]
    kst: Any
    load_naver_domestic_indicator_quotes: Callable[[list[dict]], list[dict]]
    fetch_naver_interest_rows: Callable[[str, int], list[dict]]
    naver_interest_observed_at: Callable[[str], str]
    history_frame_for_symbol: Callable[[Any, str, bool], Any]
    regular_index_zone: Callable[[str], Any]
    regular_index_session_times: Callable[[str], tuple[Any, Any]]
    indicator_observed_datetime: Callable[[dict | None], datetime | None]
    is_naver_domestic_interest_symbol: Callable[[str], bool]
    spec_public_fields: Callable[[dict], dict]
    safe_float: Callable[[object], float | None]

    def latest_naver_domestic_indicator_history_points(self, symbols: list[str]) -> list[dict]:
        wanted = {str(symbol or "").strip().upper() for symbol in symbols}
        specs = [
            spec
            for spec in self.market_indicator_specs
            if spec["symbol"] in wanted and spec["symbol"] in self.naver_domestic_index_codes
        ]
        if not specs:
            return []
        points = []
        for quote in self.load_naver_domestic_indicator_quotes(specs):
            observed_at = quote.get("observed_at")
            value = self.safe_float(quote.get("value") or quote.get("close"))
            if not observed_at or value is None:
                continue
            points.append({
                **self.spec_public_fields(quote),
                "value": value,
                "open": value,
                "high": value,
                "low": value,
                "close": value,
                "volume": None,
                "change_abs": self.safe_float(quote.get("change_abs")),
                "change_pct": self.safe_float(quote.get("change_pct")),
                "observed_at": observed_at,
                "updated_at": observed_at,
                "source": quote.get("source") or "naver",
            })
        return points

    @staticmethod
    def merge_indicator_history_records(records: list[dict], latest_points: list[dict]) -> list[dict]:
        if not latest_points:
            return records
        merged: dict[tuple[str, str], dict] = {}
        for item in [*records, *latest_points]:
            symbol = str(item.get("symbol") or "").strip().upper()
            observed_at = str(item.get("observed_at") or item.get("timestamp") or "").strip()
            if not symbol or not observed_at:
                continue
            merged[(symbol, observed_at)] = item
        return list(merged.values())

    def filter_indicator_history_points_for_period(self, symbol: str, points: list[dict], period: str) -> list[dict]:
        if period != "1d" or not points:
            return points
        zone = self.regular_index_zone(symbol)
        if zone is None:
            return points

        open_time, close_time = self.regular_index_session_times(symbol)
        parsed: list[tuple[datetime, dict]] = []
        for point in points:
            observed = self.indicator_observed_datetime({"observed_at": point.get("timestamp")})
            if observed is None:
                continue
            local = observed.astimezone(zone)
            if open_time <= local.time() <= close_time:
                parsed.append((observed, point))
        if not parsed:
            return points

        local_today = datetime.now(timezone.utc).astimezone(zone).date()
        available_dates = {observed.astimezone(zone).date() for observed, _ in parsed}
        target_date = local_today if local_today in available_dates else max(available_dates)
        filtered = [
            (observed, point)
            for observed, point in parsed
            if observed.astimezone(zone).date() == target_date
        ]
        filtered.sort(key=lambda item: item[0])
        return [point for _, point in filtered]

    def stored_indicator_history_is_sparse(self, stored: Any, symbols: list[str], interval: str) -> bool:
        if stored.empty or not str(interval).lower().endswith("m"):
            return False

        symbol_column = "symbol" if "symbol" in stored.columns else "Symbol" if "Symbol" in stored.columns else None
        close_column = next((name for name in ("close", "Close", "value", "Value") if name in stored.columns), None)
        time_column = next(
            (name for name in ("observed_at", "timestamp", "Updated_At", "Last_Updated") if name in stored.columns),
            None,
        )
        if symbol_column is None or close_column is None:
            return True

        domestic_intraday_symbols = {"^KS11", "^KQ11"}
        for symbol in symbols:
            rows = stored[stored[symbol_column].astype(str).str.upper() == symbol]
            if rows.empty:
                return True
            closes = []
            for value in rows[close_column].dropna().tolist():
                close = self.safe_float(value)
                if close is not None:
                    closes.append(round(close, 6))
            if self.is_naver_domestic_interest_symbol(symbol):
                continue
            if len(closes) < 8:
                return True
            if symbol in domestic_intraday_symbols and len(set(closes)) < 3:
                return True
            if time_column is not None:
                timestamps = pd.to_datetime(rows[time_column], utc=True, errors="coerce").dropna()
                if len(timestamps) >= 2:
                    span_seconds = (timestamps.max() - timestamps.min()).total_seconds()
                    if span_seconds < 30 * 60:
                        return True
        return False

    def fetch_indicator_history(self, symbols: list[str], period: str, interval: str) -> list[dict]:
        naver_interest_symbols = [symbol for symbol in symbols if self.is_naver_domestic_interest_symbol(symbol)]
        yfinance_symbols = [symbol for symbol in symbols if not self.is_naver_domestic_interest_symbol(symbol)]
        points = self.fetch_naver_domestic_interest_history(naver_interest_symbols, period, interval)

        try:
            import yfinance as yf
        except ImportError:
            return points

        if not yfinance_symbols:
            return points

        try:
            raw = yf.download(
                " ".join(yfinance_symbols),
                period=period,
                interval=interval,
                auto_adjust=False,
                progress=False,
                threads=True,
                group_by="ticker",
            )
        except Exception:
            raw = None

        spec_by_symbol = {spec["symbol"]: spec for spec in self.market_indicator_specs}
        fetched_symbols: set[str] = set()
        if raw is not None and not raw.empty:
            for symbol in yfinance_symbols:
                frame = self.history_frame_for_symbol(raw, symbol, len(yfinance_symbols) == 1)
                added = self.append_indicator_history_points(points, frame, symbol, interval, spec_by_symbol)
                if added:
                    fetched_symbols.add(symbol)

        for symbol in yfinance_symbols:
            if symbol in fetched_symbols:
                continue
            try:
                frame = yf.Ticker(symbol).history(period=period, interval=interval, auto_adjust=False)
            except Exception:
                continue
            self.append_indicator_history_points(points, frame, symbol, interval, spec_by_symbol)
        return points

    def append_indicator_history_points(
        self,
        points: list[dict],
        frame,
        symbol: str,
        interval: str,
        spec_by_symbol: dict[str, dict],
    ) -> bool:
        if frame is None or frame.empty or "Close" not in frame.columns:
            return False
        spec = spec_by_symbol.get(
            symbol,
            {"symbol": symbol, "label": symbol, "category": "index_fx", "region": "global", "sort_order": 999},
        )
        previous_close = None
        session_open_by_date: dict[object, float] = {}
        zone = self.regular_index_zone(symbol)
        added = False
        for ts, row in frame.dropna(subset=["Close"]).iterrows():
            close = self.safe_float(row.get("Close"))
            if close is None:
                continue
            observed = pd.to_datetime(ts, utc=True).to_pydatetime().replace(microsecond=0)
            open_price = self.safe_float(row.get("Open")) or close
            base_price = previous_close
            if zone is not None:
                session_date = observed.astimezone(zone).date()
                session_open_by_date.setdefault(session_date, open_price)
                base_price = session_open_by_date[session_date]
            change_abs = close - base_price if base_price else None
            points.append({
                **self.spec_public_fields(spec),
                "observed_at": observed.isoformat(),
                "timestamp": observed.isoformat(),
                "value": close,
                "open": open_price,
                "high": self.safe_float(row.get("High")) or close,
                "low": self.safe_float(row.get("Low")) or close,
                "close": close,
                "volume": self.safe_float(row.get("Volume")),
                "change_abs": change_abs,
                "change_pct": change_abs / base_price if base_price else None,
                "source": "yfinance",
                "interval": interval,
            })
            previous_close = close
            added = True
        return added

    def fetch_naver_domestic_interest_history(self, symbols: list[str], period: str, interval: str) -> list[dict]:
        if not symbols:
            return []

        spec_by_symbol = {spec["symbol"]: spec for spec in self.market_indicator_specs}
        days = self.market_history_periods.get(period, 1)
        start_at = datetime.now(self.kst) - timedelta(days=days)
        pages = max(1, min(math.ceil(max(days, 7) / 7) + 1, 20))
        points: list[dict] = []

        for symbol in symbols:
            spec = spec_by_symbol.get(symbol)
            if not spec:
                continue
            rows = self.fetch_naver_interest_rows(symbol, pages=pages)
            previous_close = None
            for row in reversed(rows):
                value = self.safe_float(row.get("value"))
                if value is None:
                    continue
                observed_at = self.naver_interest_observed_at(str(row.get("date") or ""))
                observed_dt = pd.to_datetime(observed_at, utc=True, errors="coerce")
                if pd.isna(observed_dt) or observed_dt.to_pydatetime() < start_at.astimezone(timezone.utc):
                    continue
                change_abs = self.safe_float(row.get("change_abs"))
                change_pct = self.safe_float(row.get("change_pct"))
                if previous_close is not None:
                    change_abs = value - previous_close
                    change_pct = change_abs / previous_close if previous_close else None
                points.append({
                    **self.spec_public_fields(spec),
                    "observed_at": observed_at,
                    "timestamp": observed_at,
                    "value": value,
                    "open": value,
                    "high": value,
                    "low": value,
                    "close": value,
                    "volume": None,
                    "change_abs": change_abs,
                    "change_pct": change_pct,
                    "source": "naver",
                    "interval": "1d" if interval != "1d" else interval,
                })
                previous_close = value
        return points


@dataclass(frozen=True)
class MarketIndicatorNaverBuilder:
    naver_domestic_index_codes: dict[str, str]
    naver_domestic_interest_codes: dict[str, str]
    kst: Any
    safe_float: Callable[[object], float | None]
    utc_now_iso: Callable[[], str]
    indicator_observed_datetime: Callable[[dict | None], datetime | None]
    regular_index_zone: Callable[[str], Any]
    regular_index_session_times: Callable[[str], tuple[Any, Any]]
    spec_public_fields: Callable[[dict], dict]

    def load_naver_market_indicator_quotes(self, specs: list[dict]) -> list[dict]:
        return [
            *self.load_naver_domestic_indicator_quotes(specs),
            *self.load_naver_domestic_interest_quotes(specs),
        ]

    def naver_domestic_index_observed_at(self, row: dict, symbol: str) -> str:
        traded_at = str(row.get("localTradedAt") or "").strip()
        if not traded_at:
            return self.utc_now_iso()
        observed = self.indicator_observed_datetime({"observed_at": traded_at})
        zone = self.regular_index_zone(symbol)
        if observed is None or zone is None:
            return traded_at

        local = observed.astimezone(zone)
        _, close_time = self.regular_index_session_times(symbol)
        status = str(row.get("marketStatus") or "").strip().upper()
        if status in {"CLOSE", "CLOSED"} or local.time() > close_time:
            close_observed = datetime(
                local.year,
                local.month,
                local.day,
                close_time.hour,
                close_time.minute,
                tzinfo=zone,
            )
            return close_observed.isoformat()
        return traded_at

    def load_naver_domestic_indicator_quotes(self, specs: list[dict]) -> list[dict]:
        wanted_specs = [spec for spec in specs if spec["symbol"] in self.naver_domestic_index_codes]
        if not wanted_specs:
            return []

        try:
            response = requests.get(
                "https://polling.finance.naver.com/api/realtime/domestic/index/KOSPI,KOSDAQ",
                timeout=5,
                headers={"User-Agent": "QuantBridge/1.0"},
            )
            response.raise_for_status()
            rows = response.json().get("datas") or []
        except Exception:
            return []

        rows_by_code = {
            str(row.get("symbolCode") or row.get("itemCode") or "").upper(): row
            for row in rows
            if isinstance(row, dict)
        }
        items = []
        for spec in wanted_specs:
            row = rows_by_code.get(self.naver_domestic_index_codes[spec["symbol"]])
            if not row:
                continue
            value = self.naver_indicator_float(row.get("closePriceRaw") or row.get("closePrice"))
            change_abs = self.naver_indicator_float(
                row.get("compareToPreviousClosePriceRaw") or row.get("compareToPreviousClosePrice")
            )
            change_pct_percent = self.naver_indicator_float(
                row.get("fluctuationsRatioRaw") or row.get("fluctuationsRatio")
            )
            if value is None:
                continue
            observed_at = self.naver_domestic_index_observed_at(row, spec["symbol"])
            items.append({
                **self.spec_public_fields(spec),
                "value": value,
                "change_abs": change_abs,
                "change_pct": change_pct_percent / 100 if change_pct_percent is not None else None,
                "close": value,
                "observed_at": observed_at,
                "updated_at": observed_at,
                "source": "naver",
            })
        return items

    def load_naver_domestic_interest_quotes(self, specs: list[dict]) -> list[dict]:
        wanted_specs = [spec for spec in specs if self.is_naver_domestic_interest_symbol(spec["symbol"])]
        if not wanted_specs:
            return []

        items = []
        for spec in wanted_specs:
            rows = self.fetch_naver_interest_rows(spec["symbol"], pages=1)
            if not rows:
                continue
            row = rows[0]
            value = self.safe_float(row.get("value"))
            if value is None:
                continue
            observed_at = self.naver_interest_observed_at(str(row.get("date") or ""))
            items.append({
                **self.spec_public_fields(spec),
                "value": value,
                "change_abs": self.safe_float(row.get("change_abs")),
                "change_pct": self.safe_float(row.get("change_pct")),
                "open": value,
                "high": value,
                "low": value,
                "close": value,
                "observed_at": observed_at,
                "updated_at": observed_at,
                "source": "naver",
            })
        return items

    def is_naver_domestic_interest_symbol(self, symbol: str) -> bool:
        return str(symbol or "").strip().upper() in self.naver_domestic_interest_codes

    def fetch_naver_interest_rows(self, symbol: str, pages: int = 1) -> list[dict]:
        code = self.naver_domestic_interest_codes.get(str(symbol or "").strip().upper())
        if not code:
            return []

        rows: list[dict] = []
        safe_pages = max(1, min(int(pages or 1), 20))
        for page in range(1, safe_pages + 1):
            try:
                response = requests.get(
                    "https://finance.naver.com/marketindex/interestDailyQuote.naver",
                    params={"marketindexCd": code, "page": page},
                    timeout=5,
                    headers={"User-Agent": "QuantBridge/1.0"},
                )
                response.raise_for_status()
                body = response.content.decode("euc-kr", errors="ignore")
            except Exception:
                continue
            rows.extend(self.parse_naver_interest_rows(body))
        return rows

    def parse_naver_interest_rows(self, body: str) -> list[dict]:
        parsed: list[dict] = []
        for match in re.finditer(r'<tr\s+class="(?P<direction>[^"]+)">(?P<body>.*?)</tr>', body or "", re.DOTALL):
            cells = re.findall(r"<td[^>]*>(.*?)</td>", match.group("body"), re.DOTALL)
            if len(cells) < 4:
                continue
            date_text = self.clean_html_cell(cells[0])
            value = self.naver_indicator_float(self.clean_html_cell(cells[1]))
            change_abs = self.naver_indicator_float(self.clean_html_cell(cells[2]))
            change_pct_percent = self.naver_indicator_float(self.clean_html_cell(cells[3]))
            if not date_text or value is None:
                continue

            direction = str(match.group("direction") or "").lower()
            if change_abs is not None:
                if direction == "down":
                    change_abs = -abs(change_abs)
                elif direction == "up":
                    change_abs = abs(change_abs)
            if change_pct_percent is not None and direction == "down":
                change_pct_percent = -abs(change_pct_percent)
            elif change_pct_percent is not None and direction == "up":
                change_pct_percent = abs(change_pct_percent)

            parsed.append({
                "date": date_text,
                "value": value,
                "change_abs": change_abs,
                "change_pct": change_pct_percent / 100 if change_pct_percent is not None else None,
            })
        return parsed

    @staticmethod
    def clean_html_cell(value: str) -> str:
        text = re.sub(r"<[^>]+>", " ", value or "")
        return re.sub(r"\s+", " ", html.unescape(text)).strip()

    def naver_interest_observed_at(self, date_text: str) -> str:
        try:
            observed = datetime.strptime(str(date_text).strip(), "%Y.%m.%d").replace(
                hour=15,
                minute=30,
                tzinfo=self.kst,
            )
            return observed.isoformat()
        except ValueError:
            return self.utc_now_iso()

    def naver_indicator_float(self, value) -> float | None:
        if value is None:
            return None
        text = str(value).replace(",", "").replace("%", "").replace("+", "").strip()
        return self.safe_float(re.sub(r"\s+", "", text))


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
