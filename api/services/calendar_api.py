from __future__ import annotations

from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from io import StringIO
import re
import sqlite3
from typing import Any

from fastapi import HTTPException
import pandas as pd
import requests

from api.services.company_names import apply_localized_names


@dataclass(frozen=True)
class EarningsCalendarDataProvider:
    api_sqlite_path: Any
    spreadsheet: Callable[[], Any]
    load_storage_df: Callable[[str, str | None], pd.DataFrame]
    load_table: Callable[[str], pd.DataFrame]
    sheet_values_to_df: Callable[[list, list], pd.DataFrame]
    clean_meta_value: Callable[[Any], str | None]
    safe_float: Callable[[Any], float | None]
    first_float: Callable[..., float | None]
    kr_code: Callable[[Any], str | None]
    record_data_source: Callable[..., None]

    def parse_calendar_date(self, value) -> datetime | None:
        text = self.clean_meta_value(value)
        if not text:
            return None
        candidates = [text]
        if " - " in text:
            candidates.extend(part.strip() for part in text.split(" - ") if part.strip())
        if "," in text and not re.search(r"^\d{1,2},", text):
            candidates.extend(part.strip() for part in text.split(",") if part.strip())
        for candidate in candidates:
            parsed = pd.to_datetime(candidate, errors="coerce", utc=False)
            if pd.isna(parsed):
                continue
            if hasattr(parsed, "to_pydatetime"):
                dt = parsed.to_pydatetime()
            else:
                dt = parsed
            if getattr(dt, "tzinfo", None) is not None:
                dt = dt.astimezone(timezone.utc).replace(tzinfo=None)
            return datetime(dt.year, dt.month, dt.day)
        return None

    @staticmethod
    def infer_market_from_ticker(ticker: str) -> str:
        normal = str(ticker or "").strip().upper()
        if normal.endswith((".KS", ".KQ")) or re.fullmatch(r"\d{6}", normal):
            return "KR"
        return "US"

    @staticmethod
    def normalize_us_calendar_ticker(ticker: str) -> str:
        return str(ticker or "").strip().upper().replace(".", "-")

    def extract_sp500_symbols(self, html: str) -> set[str]:
        table_match = re.search(
            r'<table[^>]*class="[^"]*wikitable[^"]*sortable[^"]*"[^>]*>(.*?)</table>',
            html,
            flags=re.IGNORECASE | re.DOTALL,
        )
        if not table_match:
            return set()
        table_html = table_match.group(1)
        symbols = re.findall(r'class="external text"[^>]*>\s*([A-Za-z.]+)\s*</a>', table_html)
        return {self.normalize_us_calendar_ticker(symbol) for symbol in symbols if symbol}

    @lru_cache(maxsize=1)
    def sp500_ticker_set(self) -> set[str]:
        try:
            response = requests.get(
                "https://en.wikipedia.org/wiki/List_of_S%26P_500_companies",
                headers={"User-Agent": "QuantBridge/1.0"},
                timeout=10,
            )
            response.raise_for_status()
            return self.extract_sp500_symbols(response.text)
        except Exception as exc:
            self.record_data_source("SP500_Constituents", "external_error", detail=f"{type(exc).__name__}: {exc}")
            return set()

    def us_calendar_allowed_tickers(self) -> set[str]:
        allowed: set[str] = set()
        portfolio = self.load_storage_df("US_Final_Portfolio", "US")
        if not portfolio.empty and "Ticker" in portfolio.columns:
            allowed.update(self.normalize_us_calendar_ticker(ticker) for ticker in portfolio["Ticker"].tolist())
        allowed.update(self.sp500_ticker_set())
        return {ticker for ticker in allowed if ticker}

    def earnings_calendar_sqlite_df(self) -> pd.DataFrame:
        path = self.api_sqlite_path
        if not path.exists():
            return pd.DataFrame()
        try:
            with sqlite3.connect(path) as conn:
                table = conn.execute(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name='earnings_calendar'"
                ).fetchone()
                if not table:
                    return pd.DataFrame()
                return pd.read_sql_query("SELECT * FROM earnings_calendar", conn)
        except Exception as exc:
            self.record_data_source("Earnings_Calendar", "sqlite_error", detail=f"{type(exc).__name__}: {exc}")
            return pd.DataFrame()

    def company_identity_lookup(self) -> dict[str, dict]:
        df = self.load_table("Company_Master")
        if df.empty or "Ticker" not in df.columns:
            return {}
        lookup: dict[str, dict] = {}
        for _, row in df.iterrows():
            ticker = str(row.get("Ticker") or "").strip().upper()
            if not ticker:
                continue
            row_dict = row.to_dict()
            lookup[ticker] = row_dict
            code = self.kr_code(ticker)
            if code:
                lookup.setdefault(code, row_dict)
        return lookup

    def load_earnings_calendar_frame(self) -> tuple[pd.DataFrame, str]:
        df = self.earnings_calendar_sqlite_df()
        if not df.empty:
            return df, "sqlite"

        df = self.load_storage_df("Earnings_Calendar", None)
        if not df.empty:
            return df, "storage"

        try:
            data = self.spreadsheet().worksheet("Earnings_Calendar").get_all_values()
        except Exception as exc:
            self.record_data_source("Earnings_Calendar", "sheet_error", detail=f"{type(exc).__name__}: {exc}")
            return pd.DataFrame(), "empty"
        if len(data) < 2:
            self.record_data_source("Earnings_Calendar", "sheet_empty", rows=0)
            return pd.DataFrame(), "empty"

        df = self.sheet_values_to_df(data[1:], data[0])
        if not df.empty:
            self.record_data_source("Earnings_Calendar", "sheet", rows=len(df))
            return df, "sheet"

        return pd.DataFrame(), "empty"

    def market_cap_text_to_float(self, value) -> float | None:
        text = self.clean_meta_value(value)
        if not text or text == "-":
            return None
        cleaned = text.replace("$", "").replace(",", "").strip().upper()
        match = re.match(r"^(-?\d+(?:\.\d+)?)([KMBT])?$", cleaned)
        if not match:
            return self.safe_float(cleaned)
        number = float(match.group(1))
        multiplier = {
            "K": 1e3,
            "M": 1e6,
            "B": 1e9,
            "T": 1e12,
            None: 1,
        }[match.group(2)]
        return number * multiplier

    def calendar_market_cap(self, market: str, *values) -> float | None:
        cap = self.first_float(*values)
        if cap is None:
            return None
        market_key = str(market or "").strip().upper()
        if market_key == "US" and abs(cap) < 1_000_000:
            return None
        if market_key == "KR" and abs(cap) < 100_000_000:
            return None
        return cap

    def extract_yfinance_earnings_date(self, calendar) -> datetime | None:
        raw = None
        if isinstance(calendar, dict):
            raw = calendar.get("Earnings Date")
        elif isinstance(calendar, pd.DataFrame) and not calendar.empty and "Earnings Date" in calendar.index:
            raw = calendar.loc["Earnings Date"].iloc[0]

        values = raw if isinstance(raw, (list, tuple, pd.Series, pd.DatetimeIndex)) else [raw]
        for value in values:
            parsed = self.parse_calendar_date(value)
            if parsed:
                return parsed
        return None

    def yahoo_earnings_calendar_seed_rows(self, day: datetime) -> list[dict]:
        """Fetch Yahoo's visible earnings table as ticker metadata only."""
        date_key = day.strftime("%Y-%m-%d")
        url = f"https://finance.yahoo.com/calendar/earnings?day={date_key}&offset=0&size=100"
        try:
            response = requests.get(url, timeout=8, headers={"User-Agent": "QuantBridge/1.0"})
            response.raise_for_status()
            tables = pd.read_html(StringIO(response.text))
        except Exception as exc:
            self.record_data_source(
                "Earnings_Calendar",
                "yahoo_calendar_error",
                detail=f"{date_key}: {type(exc).__name__}",
            )
            return []

        rows: list[dict] = []
        for table in tables:
            columns = {str(column).strip(): column for column in table.columns}
            if "Symbol" not in columns or "Company" not in columns:
                continue
            for _, row in table.iterrows():
                ticker = self.clean_meta_value(row.get(columns["Symbol"]))
                name = self.clean_meta_value(row.get(columns["Company"]))
                if not ticker or ticker == "-":
                    continue
                rows.append({
                    "Ticker": ticker.upper(),
                    "Name": name or ticker.upper(),
                    "Market": "US",
                    "Sector": None,
                    "MarketCap": self.market_cap_text_to_float(row.get(columns.get("Market Cap"))),
                    "Earnings_Call_Time": self.clean_meta_value(row.get(columns.get("Earnings Call Time"))),
                    "EPS_Estimate": self.clean_meta_value(row.get(columns.get("EPS Estimate"))),
                    "Yahoo_Page_Date": date_key,
                })
        return rows

    def verified_earnings_calendar_record(
        self,
        yf_module,
        seed: dict,
        today: datetime,
        end_date: datetime,
    ) -> dict | None:
        ticker = self.clean_meta_value(seed.get("Ticker"))
        if not ticker:
            return None
        ticker = ticker.upper()
        try:
            ticker_obj = yf_module.Ticker(ticker)
            calendar = ticker_obj.calendar
        except Exception as exc:
            self.record_data_source(
                "Earnings_Calendar",
                "yfinance_calendar_symbol_error",
                detail=f"{ticker}: {type(exc).__name__}",
            )
            return None

        event_date = self.extract_yfinance_earnings_date(calendar)
        if not event_date or event_date < today or event_date > end_date:
            return None

        eps_estimate = seed.get("EPS_Estimate")
        if eps_estimate is None and isinstance(calendar, dict):
            eps_estimate = calendar.get("Earnings Average")

        info: dict = {}
        if (
            seed.get("MarketCap") is None
            or not self.clean_meta_value(seed.get("Name"))
            or not self.clean_meta_value(seed.get("Sector"))
        ):
            try:
                info = ticker_obj.get_info() or {}
            except Exception as exc:
                self.record_data_source(
                    "Earnings_Calendar",
                    "yfinance_info_symbol_error",
                    detail=f"{ticker}: {type(exc).__name__}",
                )
                info = {}

        return {
            "Ticker": ticker,
            "Name": (
                self.clean_meta_value(seed.get("Name"))
                or self.clean_meta_value(info.get("longName"))
                or self.clean_meta_value(info.get("shortName"))
                or ticker
            ),
            "Market": "US",
            "Sector": self.clean_meta_value(seed.get("Sector")) or self.clean_meta_value(info.get("sector")),
            "MarketCap": self.first_float(seed.get("MarketCap"), info.get("marketCap")),
            "Next_Earnings_Date": event_date.strftime("%Y-%m-%d"),
            "Earnings_Call_Time": self.clean_meta_value(seed.get("Earnings_Call_Time")),
            "EPS_Estimate": self.clean_meta_value(eps_estimate),
        }

    def fetch_verified_earnings_calendar_df(self, today: datetime, days: int, limit: int) -> pd.DataFrame:
        # Empty-cache fallback: build a real date calendar from ticker-level calendars.
        # The scheduled main engine remains the primary source for the full universe.
        try:
            import yfinance as yf
        except Exception as exc:
            self.record_data_source("Earnings_Calendar", "yfinance_calendar_unavailable", detail=type(exc).__name__)
            return pd.DataFrame()

        safe_days = max(1, min(days, 366))
        end_date = today + timedelta(days=safe_days)
        candidates: dict[str, dict] = {}
        for row in self.yahoo_earnings_calendar_seed_rows(today):
            ticker = self.clean_meta_value(row.get("Ticker"))
            if ticker:
                candidates[ticker.upper()] = row
        for ticker in sorted(self.us_calendar_allowed_tickers()):
            candidates.setdefault(ticker, {"Ticker": ticker, "Market": "US"})

        candidate_rows = list(candidates.values())[:650]
        rows: list[dict] = []
        with ThreadPoolExecutor(max_workers=8) as executor:
            futures = [
                executor.submit(self.verified_earnings_calendar_record, yf, seed, today, end_date)
                for seed in candidate_rows
            ]
            for future in as_completed(futures):
                record = future.result()
                if record:
                    rows.append(record)
                if len(rows) >= limit:
                    break
        if not rows:
            self.record_data_source("Earnings_Calendar", "verified_calendar_empty", rows=0)
            return pd.DataFrame()
        frame = pd.DataFrame(rows).drop_duplicates(subset=["Ticker", "Next_Earnings_Date"])
        self.record_data_source("Earnings_Calendar", "yfinance_calendar_verified", rows=len(frame))
        return frame


@dataclass(frozen=True)
class EarningsCalendarPayloadBuilder:
    load_earnings_calendar_frame: Callable[[], tuple[pd.DataFrame, str]]
    fetch_verified_earnings_calendar_df: Callable[[datetime, int, int], pd.DataFrame]
    clean_dataframe_columns: Callable[[pd.DataFrame], pd.DataFrame]
    company_identity_lookup: Callable[[], dict[str, dict]]
    us_calendar_allowed_tickers: Callable[[], set[str]]
    parse_calendar_date: Callable[[Any], datetime | None]
    infer_market_from_ticker: Callable[[str], str]
    normalize_us_calendar_ticker: Callable[[str], str]
    kr_code: Callable[[Any], str | None]
    identity_payload_from_row: Callable[[dict, str, str, str], dict]
    first_text: Callable[..., str | None]
    first_float: Callable[..., float | None]
    calendar_market_cap: Callable[..., float | None]
    is_missing_kr_name: Callable[[object, object], bool]
    naver_kr_identity: Callable[[str], dict]
    safe_float: Callable[[Any], float | None]

    def payload(self, market: str, days: int, limit: int) -> dict:
        today = datetime.now(timezone(timedelta(hours=9))).replace(tzinfo=None)
        today = datetime(today.year, today.month, today.day)
        end_date = today + timedelta(days=days)

        df, source = self.load_earnings_calendar_frame()
        if df.empty and market in ("ALL", "US"):
            fallback_df = self.fetch_verified_earnings_calendar_df(today, days, limit)
            if not fallback_df.empty:
                df, source = fallback_df, "yfinance_verified_calendar"
        if df.empty:
            return {
                "items": [],
                "generated_at": datetime.now(timezone.utc).isoformat(),
                "source": source,
                "total": 0,
            }

        rows = self.build_rows(
            df,
            market,
            today,
            end_date,
            restrict_us_universe=source not in ("yahoo_finance_calendar", "yfinance_verified_calendar"),
        )
        if (
            not rows
            and source not in ("yahoo_finance_calendar", "yfinance_verified_calendar")
            and market in ("ALL", "US")
        ):
            fallback_df = self.fetch_verified_earnings_calendar_df(today, days, limit)
            if not fallback_df.empty:
                source = "yfinance_verified_calendar"
                rows = self.build_rows(
                    fallback_df,
                    market,
                    today,
                    end_date,
                    restrict_us_universe=False,
                )

        rows.sort(key=self.sort_key)
        rows = rows[:limit]
        return {
            "items": rows,
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "source": source,
            "total": len(rows),
        }

    def build_rows(
        self,
        df: pd.DataFrame,
        market: str,
        today: datetime,
        end_date: datetime,
        restrict_us_universe: bool = True,
    ) -> list[dict]:
        if df.empty:
            return []

        df = self.clean_dataframe_columns(df)
        ticker_col = next((c for c in ("Ticker", "ticker", "Symbol") if c in df.columns), None)
        date_col = next(
            (c for c in ("Next_Earnings_Date", "Next_Earnings", "Earnings_Date", "earnings_date") if c in df.columns),
            None,
        )
        if not ticker_col or not date_col:
            return []

        identities = self.company_identity_lookup()
        allowed_us_tickers = (
            self.us_calendar_allowed_tickers() if restrict_us_universe and market in ("ALL", "US") else set()
        )
        rows: list[dict] = []
        for _, row in df.iterrows():
            ticker = str(row.get(ticker_col) or "").strip().upper()
            if not ticker:
                continue
            date = self.parse_calendar_date(row.get(date_col))
            if not date or date < today or date > end_date:
                continue
            item_market = str(row.get("Market") or "").strip().upper() or self.infer_market_from_ticker(ticker)
            if market != "ALL" and item_market != market:
                continue
            if (
                allowed_us_tickers
                and item_market == "US"
                and self.normalize_us_calendar_ticker(ticker) not in allowed_us_tickers
            ):
                continue

            identity_row = identities.get(ticker) or identities.get(self.kr_code(ticker) or "")
            identity = self.identity_payload_from_row(
                identity_row or {},
                ticker,
                item_market,
                "Company_Master" if identity_row else "",
            )
            raw_name = self.first_text(row.get("Name"), identity.get("name"))
            sector = self.first_text(row.get("Sector"), identity.get("sector"))
            market_cap = self.first_float(
                self.calendar_market_cap(item_market, row.get("MarketCap"), row.get("MarketCap_Last")),
                self.calendar_market_cap(item_market, identity.get("market_cap")),
            )
            naver_identity = {}
            if item_market == "KR" and (
                self.is_missing_kr_name(raw_name, ticker)
                or market_cap is None
                or abs(market_cap) < 1_000_000
            ):
                naver_identity = self.naver_kr_identity(ticker)
            name = raw_name
            if item_market == "KR" and self.is_missing_kr_name(name, ticker):
                name = self.first_text(naver_identity.get("Name"), ticker)
            else:
                name = self.first_text(name, ticker)
            if item_market == "KR":
                market_cap = self.first_float(
                    self.calendar_market_cap(item_market, naver_identity.get("MarketCap")),
                    market_cap,
                )

            rows.append({
                "Ticker": ticker,
                "Name": name,
                "Market": item_market,
                "Sector": sector,
                "MarketCap": market_cap,
                "Next_Earnings_Date": date.strftime("%Y-%m-%d"),
                "Days_Until": (date - today).days,
            })
        return rows

    def sort_key(self, item: dict):
        market_cap = self.safe_float(item.get("MarketCap"))
        cap_order = -market_cap if market_cap is not None else float("inf")
        return (
            item.get("Next_Earnings_Date") or "",
            cap_order,
            item.get("Market") or "",
            item.get("Ticker") or "",
        )


@dataclass(frozen=True)
class CalendarApiService:
    cached: Callable
    earnings_calendar_response: Callable[[str, int, int, bool], dict]
    load_simple: Callable[[str, list[str]], list[dict]]
    enrich_kr_company_identities: Callable[[list[dict]], list[dict]]
    macro_payload: Callable[[], dict]

    def calendar_earnings(self, market: str = "ALL", days: int = 180, limit: int = 200, refresh: bool = False) -> dict:
        return apply_localized_names(self.earnings_calendar_response(market, days, limit, refresh))

    def earnings_calendar(self, market: str = "ALL", days: int = 180, limit: int = 200, refresh: bool = False) -> dict:
        return apply_localized_names(self.earnings_calendar_response(market, days, limit, refresh))

    def earnings(self, market: str) -> dict:
        safe_market = self._market(market)
        sheet = f"{safe_market}_Earnings_Momentum"
        num_cols = [
            "Surprise_Pct", "Signal_Strength", "Return_Since",
            "Volume_Surge", "Days_Since_Earnings",
            "Actual_EPS", "Estimated_EPS", "MarketCap", "Rank",
        ]

        def load() -> dict:
            stocks = self.load_simple(sheet, num_cols)
            if safe_market == "KR":
                stocks = self.enrich_kr_company_identities(stocks)
            return apply_localized_names({"stocks": stocks})

        return self.cached(f"earn_{safe_market}_v2", load)

    def macro(self) -> dict:
        return self.cached("macro", self.macro_payload)

    @staticmethod
    def _market(market: str) -> str:
        safe_market = str(market or "").upper()
        if safe_market not in ("US", "KR"):
            raise HTTPException(400, "market must be US or KR")
        return safe_market
