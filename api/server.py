#!/usr/bin/env python3
"""
QuantBridge FastAPI Server
Reads Google Sheets (QuantBridge_Demo_Workbook) and serves JSON to the iOS app.

Run:
    uvicorn api.server:app --host 0.0.0.0 --port 8000 --reload
"""

import sys
import math
import os
import re
import html
import hashlib
import sqlite3
import requests
import time as _time
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from io import StringIO
from pathlib import Path
from datetime import datetime, time, timedelta, timezone
from email.utils import parsedate_to_datetime
from urllib.parse import urljoin, urlparse, urlunparse
from zoneinfo import ZoneInfo
from functools import lru_cache

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT))

from fastapi import FastAPI, HTTPException, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
import pandas as pd
from api.auth import db as _auth_db
from api.auth import init_db as _init_auth_db
from api.auth import router as auth_router
from api.contracts.mobile_v1 import (
    HealthResponse,
    MLBlendResponse,
    PolicyBacktestResponse,
    ReadyResponse,
    RemediationPlanResponse,
    ResearchPolicyResponse,
    ResearchQualityResponse,
    ScoredResponse,
    SmallCapResponse,
)
from api.routers.etfs import create_etf_router
from api.routers.calendar import create_calendar_router
from api.routers.market import create_market_router
from api.routers.news import create_news_router
from api.routers.ops import create_ops_router
from api.routers.portfolio import create_portfolio_router
from api.routers.risk import create_risk_router
from api.routers.search import create_search_router
from api.routers.sectors import create_sector_router
from api.routers.stock import create_stock_router
from api.services.cache_warm import ApiCacheWarmer
from api.services.company_names import (
    apply_localized_names as _apply_localized_names,
    localize_company_name_fields as _localize_company_name_fields,
    localized_company_name as _localized_company_name,
)
from api.services.calendar_api import CalendarApiService
from api.services.etf_api import EtfApiService
from api.services.market_api import MarketApiService
from api.services.news_api import NewsApiService
from api.services.ops_api import OpsApiService
from api.services.portfolio_api import PortfolioApiService
from api.services.risk_api import RiskApiService
from api.services.search_api import SearchApiService
from api.services.sector_api import SectorApiService
from api.runtime_state import (
    _cache,
    _cache_ts,
    _data_source_events,
    clear_runtime_state as _clear_runtime_state,
    data_source_payload as _data_source_payload,
    performance_payload as _performance_payload,
    cached as _runtime_cached,
    invalidate as _runtime_invalidate,
    record_data_source as _record_data_source,
    record_api_timing as _record_api_timing,
)
from api.services.etf_insights import detail_from_records as _etf_detail_from_records
from api.services.etf_insights import payload_from_records as _etf_payload_from_records
from api.services.news_impact import enrich_news_items as _enrich_news_items
from api.services.stock_detail import StockDetailService
from sheets_client import get_spreadsheet
from quantbridge.config import get_settings
from quantbridge.quality import build_data_quality_report
from quantbridge.storage import QuantRepository
from quantbridge.ticker_policy import is_banned_ticker

# ── Config ────────────────────────────────────────────────────────────────────

_SETTINGS    = get_settings()
_SPREADSHEET = _SETTINGS.spreadsheet_name


def _allow_api_external_fetch() -> bool:
    return bool(getattr(_SETTINGS, "api_allow_external_fetch", False))

app = FastAPI(title="QuantBridge API", version="1.0.0")
app.add_middleware(GZipMiddleware, minimum_size=1000)
app.add_middleware(
    CORSMiddleware,
    allow_origins=list(_SETTINGS.cors_origins) or ["*"],
    allow_credentials=_SETTINGS.allow_cors_credentials,
    allow_methods=["GET", "POST", "DELETE"],
    allow_headers=["*"],
)
app.include_router(auth_router)


@app.middleware("http")
async def _api_timing_middleware(request, call_next):
    started = _time.perf_counter()
    response = await call_next(request)
    elapsed_ms = (_time.perf_counter() - started) * 1000
    response.headers["X-Process-Time-Ms"] = f"{elapsed_ms:.1f}"
    if request.url.path not in {"/health", "/favicon.ico"}:
        _record_api_timing(request.method, request.url.path, response.status_code, elapsed_ms)
    return response


_APP_SNAPSHOT_KEY_PREFIXES = (
    "port_",
    "portfolio_prices_",
    "sc_",
    "scored_",
    "search_universe_",
    "earn_",
    "earnings_calendar_",
    "signal_events_",
    "comparison_recommendations_",
    "market_indicators_",
    "market_indicator_history_",
    "etfs_daily_",
    "etf_detail_",
    "sector_theme_",
    "sector_themes_",
    "stock_",
    "news_issues_",
    "macro",
)


def _env_enabled(name: str, default: bool = True) -> bool:
    raw = str(os.environ.get(name, "1" if default else "0")).strip().lower()
    return raw not in {"0", "false", "no", "off"}


def _app_snapshot_max_age_seconds(cache_key: str | None = None) -> int | None:
    raw = str(os.environ.get("QUANT_API_APP_SNAPSHOT_MAX_AGE_SECONDS", "86400")).strip()
    if raw in {"", "0", "none", "None"}:
        return None
    try:
        max_age = max(60, int(raw))
    except ValueError:
        max_age = 86400
    if str(cache_key or "").startswith("stock_"):
        return min(max_age, 900)
    return max_age


def _is_app_snapshot_key(key: str) -> bool:
    clean = str(key or "")
    if not clean:
        return False
    if clean.startswith(("news_image_url_", "naver_kr_stock_quotes_", "ops_", "research_")):
        return False
    return clean == "macro" or clean.startswith(_APP_SNAPSHOT_KEY_PREFIXES)


def _read_app_snapshot(cache_key: str) -> object | None:
    if not _env_enabled("QUANT_API_READ_APP_SNAPSHOTS", True):
        return None
    if not _SETTINGS.enable_postgres or not _is_app_snapshot_key(cache_key):
        return None
    try:
        return _repository().read_app_api_snapshot(
            cache_key,
            max_age_seconds=_app_snapshot_max_age_seconds(cache_key),
        )
    except Exception:
        return None


def _write_app_snapshot(cache_key: str, payload: object) -> None:
    if not _env_enabled("QUANT_API_WRITE_APP_SNAPSHOTS", True):
        return
    if not _SETTINGS.enable_postgres or not _is_app_snapshot_key(cache_key):
        return
    try:
        _repository().upsert_app_api_snapshot(cache_key, payload, source="api_precompute")
    except Exception:
        pass


def _cached(key: str, loader, ttl: int | None = None, stale_ttl: int | None = None):
    effective_stale_ttl = 0 if ttl is not None and stale_ttl is None else stale_ttl

    def load_with_app_snapshot():
        snapshot = _read_app_snapshot(key)
        if snapshot is not None:
            return snapshot
        result = loader()
        _write_app_snapshot(key, result)
        return result

    return _runtime_cached(key, load_with_app_snapshot, ttl=ttl, stale_ttl=effective_stale_ttl)


def _invalidate(key: str) -> None:
    _runtime_invalidate(key)


# ── Google Sheets client ──────────────────────────────────────────────────────

@lru_cache(maxsize=1)
def _spreadsheet():
    return get_spreadsheet(_SPREADSHEET)


@lru_cache(maxsize=1)
def _repository():
    return QuantRepository(_SETTINGS)


# ── Helpers ───────────────────────────────────────────────────────────────────

def _clean(val):
    """Convert NaN/None to Python None for valid JSON serialization."""
    if val is None:
        return None
    if isinstance(val, float) and math.isnan(val):
        return None
    return val



def _df_to_records(df: pd.DataFrame) -> list[dict]:
    df = df.where(df.notna(), None)
    return [
        _localize_company_name_fields({k: _clean(v) for k, v in row.items()})
        for row in df.to_dict("records")
    ]


def _load_storage_df(sheet_name: str, market: str | None = None) -> pd.DataFrame:
    """Return latest storage snapshot, or an empty frame when not bootstrapped yet."""
    try:
        df = _repository().read_dataframe(sheet_name, market=market)
    except Exception as exc:
        _record_data_source(sheet_name, "storage_error", market=market, detail=f"{type(exc).__name__}: {exc}")
        return pd.DataFrame()
    if df.empty:
        _record_data_source(sheet_name, "storage_empty", market=market, rows=0)
        return df
    df = _clean_dataframe_columns(df)
    if "Ticker" in df.columns:
        df = df[df["Ticker"].astype(str).str.strip() != ""]
    if "Rank" in df.columns:
        rank = pd.to_numeric(df["Rank"], errors="coerce")
        df = df[rank.notna()].copy()
        df["Rank"] = rank[rank.notna()]
    _record_data_source(sheet_name, "storage", market=market, rows=len(df))
    return df.reset_index(drop=True)


def _clean_dataframe_columns(df: pd.DataFrame) -> pd.DataFrame:
    """Drop blank/duplicate columns from oversized Sheets/storage ranges."""
    if df.empty:
        return df
    out = df.copy()
    columns = [str(col).strip() if col is not None else "" for col in out.columns]
    keep = []
    seen = set()
    for col in columns:
        if not col or col in seen:
            keep.append(False)
            continue
        seen.add(col)
        keep.append(True)
    out.columns = columns
    return out.loc[:, keep]


def _sheet_values_to_df(rows, header) -> pd.DataFrame:
    keep = []
    seen = set()
    for idx, raw_col in enumerate(header or []):
        col = str(raw_col).strip()
        if not col or col in seen:
            continue
        seen.add(col)
        keep.append((idx, col))
    if not keep:
        return pd.DataFrame()
    data = [[row[idx] if idx < len(row) else "" for idx, _ in keep] for row in rows]
    return pd.DataFrame(data, columns=[col for _, col in keep])


def _coerce(df: pd.DataFrame, cols: list[str]) -> pd.DataFrame:
    for col in cols:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    return df


PORTFOLIO_NUMERIC_COLS = [
    "Weight(%)", "Current_Price", "Return_1M", "Mom_1M", "Total_Score", "ROIC",
    "RevGrowth", "GrossMargin", "MarketCap", "Expected_Return", "Rank",
    "Previous_Rank", "Rank_Change",
]


def _normalize_portfolio_price_columns(df: pd.DataFrame) -> pd.DataFrame:
    if df.empty:
        return df

    aliases = {
        "Current_Price": ["Current_Price", "Price", "Last_Price", "Close", "End_Price"],
        "Return_1M": ["Return_1M", "1M_Return", "Return_1m", "One_Month_Return", "Mom_1M"],
    }
    for target, candidates in aliases.items():
        if target not in df.columns:
            df[target] = None
        for candidate in candidates:
            if candidate in df.columns:
                df[target] = df[target].combine_first(df[candidate])
    return df


def _us_equity_regular_session_phase(now: datetime | None = None) -> str:
    local = (now or datetime.now(timezone.utc)).astimezone(_US_MARKET_TZ)
    if local.weekday() >= 5:
        return "closed_day"
    open_time, close_time = time(9, 30), time(16, 0)
    if local.time() < open_time:
        return "pre_open"
    if local.time() <= close_time:
        return "open"
    return "after_close"


def _us_equity_local_date(now: datetime | None = None):
    return (now or datetime.now(timezone.utc)).astimezone(_US_MARKET_TZ).date()


def _drop_unsettled_us_daily_rows(frame: pd.DataFrame, market: str | None) -> pd.DataFrame:
    if str(market or "").strip().upper() != "US" or frame.empty or "date" not in frame.columns:
        return frame
    if _us_equity_regular_session_phase() != "pre_open":
        return frame
    today = _us_equity_local_date()
    dates = pd.to_datetime(frame["date"], errors="coerce").dt.date
    filtered = frame[dates < today]
    return filtered if not filtered.empty else frame


def _drop_unsettled_us_daily_series(series: pd.Series, market: str | None) -> pd.Series:
    if str(market or "").strip().upper() != "US" or series.empty:
        return series
    if _us_equity_regular_session_phase() != "pre_open":
        return series
    today = _us_equity_local_date()
    dates = pd.to_datetime(series.index, errors="coerce")
    keep = [pd.Timestamp(index).date() < today for index in dates]
    filtered = series[keep]
    return filtered if not filtered.empty else series


def _portfolio_price_metrics(ticker: str, market: str | None) -> tuple[float | None, float | None]:
    try:
        prices = _repository().read_prices(ticker, period="3mo", market=market)
    except Exception:
        return None, None
    return _portfolio_price_metrics_from_frame(prices, market=market)


def _portfolio_price_metrics_from_frame(prices: pd.DataFrame, market: str | None = None) -> tuple[float | None, float | None]:
    if prices.empty or "close" not in prices.columns:
        return None, None

    frame = prices.copy()
    frame["date"] = pd.to_datetime(frame["date"], errors="coerce")
    frame["close"] = pd.to_numeric(frame["close"], errors="coerce")
    frame = frame.dropna(subset=["date", "close"]).sort_values("date")
    frame = _drop_unsettled_us_daily_rows(frame, market)
    if frame.empty:
        return None, None

    current_price = float(frame["close"].iloc[-1])
    target_date = frame["date"].iloc[-1] - pd.Timedelta(days=30)
    base_rows = frame[frame["date"] <= target_date]
    if base_rows.empty:
        base_price = float(frame["close"].iloc[0]) if len(frame) > 1 else None
    else:
        base_price = float(base_rows["close"].iloc[-1])
    if base_price is None or base_price <= 0:
        return current_price, None
    return current_price, (current_price / base_price) - 1.0


def _portfolio_price_snapshot_batch(tickers: list[str], market: str | None) -> dict[str, dict]:
    clean_tickers = [str(ticker or "").strip().upper() for ticker in tickers if str(ticker or "").strip()]
    if not clean_tickers:
        return {}
    try:
        metrics = _repository().read_price_metrics(clean_tickers, market=market)
    except Exception:
        metrics = pd.DataFrame()
    if metrics.empty or "ticker" not in metrics.columns:
        return {}

    snapshots: dict[str, dict] = {}
    for _, row in metrics.iterrows():
        ticker = str(row.get("ticker") or "").strip().upper()
        if not ticker:
            continue
        payload = row.get("payload")
        if not isinstance(payload, dict):
            payload = {}
        as_of = row.get("as_of")
        updated_at = row.get("updated_at") or as_of
        if hasattr(as_of, "isoformat"):
            as_of = as_of.isoformat()
        if hasattr(updated_at, "isoformat"):
            updated_at = updated_at.isoformat()
        snapshots[ticker] = {
            "current_price": _safe_float(row.get("current_price")),
            "return_1m": _safe_float(row.get("return_1m")),
            "daily_change_pct": _safe_float(payload.get("daily_change_pct")),
            "daily_change_horizon": str(payload.get("daily_change_horizon") or ""),
            "previous_close": _safe_float(payload.get("previous_close")),
            "as_of": as_of,
            "updated_at": updated_at,
            "source": payload.get("source") or row.get("source"),
        }
    return snapshots


def _is_us_equity_regular_session_open(now: datetime | None = None) -> bool:
    return _us_equity_regular_session_phase(now) == "open"


def _should_use_last_regular_close_change(market: str | None) -> bool:
    return str(market or "").strip().upper() == "US" and not _is_us_equity_regular_session_open()


def _portfolio_price_metrics_batch(tickers: list[str], market: str | None) -> dict[str, tuple[float | None, float | None]]:
    clean_tickers = [str(ticker or "").strip().upper() for ticker in tickers if str(ticker or "").strip()]
    if not clean_tickers:
        return {}
    try:
        prices = _repository().read_prices_batch(clean_tickers, period="3mo", market=market)
    except Exception:
        prices = pd.DataFrame()

    if prices.empty or "ticker" not in prices.columns:
        return {
            ticker: _portfolio_price_metrics(ticker, market)
            for ticker in clean_tickers
        }

    metrics: dict[str, tuple[float | None, float | None]] = {}
    for ticker, group in prices.groupby("ticker", sort=False):
        metrics[str(ticker).strip().upper()] = _portfolio_price_metrics_from_frame(group, market=market)

    for ticker in clean_tickers:
        metrics.setdefault(ticker, (None, None))
    return metrics


def _daily_change_from_price_frame(
    prices: pd.DataFrame,
    snapshot: dict | None = None,
    market: str | None = None,
) -> tuple[float | None, str]:
    if prices.empty or "date" not in prices.columns or "close" not in prices.columns:
        return None, ""

    frame = prices.copy()
    frame["date"] = pd.to_datetime(frame["date"], errors="coerce")
    frame["close"] = pd.to_numeric(frame["close"], errors="coerce")
    frame = frame.dropna(subset=["date", "close"]).sort_values("date")
    frame = _drop_unsettled_us_daily_rows(frame, market)
    if len(frame) < 2:
        return None, ""

    snapshot = snapshot or {}
    snapshot_daily_change = _safe_float(snapshot.get("daily_change_pct"))
    if snapshot_daily_change is not None:
        return snapshot_daily_change, str(snapshot.get("daily_change_horizon") or "오늘")
    snapshot_price = _safe_float(snapshot.get("current_price"))
    snapshot_time = pd.to_datetime(
        snapshot.get("as_of") or snapshot.get("updated_at"),
        errors="coerce",
        utc=False,
    )
    latest_date = frame["date"].iloc[-1].date()
    latest_close = _safe_float(frame["close"].iloc[-1])
    previous_close = _safe_float(frame["close"].iloc[-2])
    current_price = latest_close
    base_price = previous_close
    horizon = "전장"

    if _should_use_last_regular_close_change(market):
        if current_price is None or base_price is None or base_price <= 0:
            return None, ""
        return (current_price / base_price) - 1.0, "전장"

    if snapshot_price is not None:
        current_price = snapshot_price
        if not pd.isna(snapshot_time):
            snapshot_date = snapshot_time.date()
            if snapshot_date > latest_date:
                base_price = latest_close
                horizon = "오늘"
            elif snapshot_date == latest_date:
                base_price = previous_close
                horizon = "오늘"
            else:
                current_price = latest_close
                base_price = previous_close
        elif latest_close is not None:
            if abs(snapshot_price - latest_close) / max(abs(latest_close), 1e-9) > 0.0005:
                base_price = latest_close
                horizon = "오늘"

    if current_price is None or base_price is None or base_price <= 0:
        return None, ""
    return (current_price / base_price) - 1.0, horizon


def _portfolio_daily_change_batch(
    tickers: list[str],
    market: str | None,
    snapshots: dict[str, dict] | None = None,
) -> dict[str, tuple[float | None, str]]:
    clean_tickers = [str(ticker or "").strip().upper() for ticker in tickers if str(ticker or "").strip()]
    if not clean_tickers:
        return {}
    try:
        prices = _repository().read_prices_batch(clean_tickers, period="10d", market=market)
    except Exception:
        prices = pd.DataFrame()

    snapshots = snapshots or {}
    if prices.empty or "ticker" not in prices.columns:
        return {ticker: (None, "") for ticker in clean_tickers}

    changes: dict[str, tuple[float | None, str]] = {}
    for ticker, group in prices.groupby("ticker", sort=False):
        key = str(ticker).strip().upper()
        changes[key] = _daily_change_from_price_frame(group, snapshots.get(key), market=market)

    for ticker in clean_tickers:
        changes.setdefault(ticker, (None, ""))
    return changes


def _enrich_portfolio_price_fields(
    records: list[dict],
    market: str | None,
    max_fetch: int | None = None,
) -> list[dict]:
    tickers = [str(row.get("Ticker") or "") for row in records]
    snapshots = _portfolio_price_snapshot_batch(tickers, market)
    daily_changes = _portfolio_daily_change_batch(tickers, market, snapshots)
    fetch_count = 0
    for row in records:
        ticker = str(row.get("Ticker") or "").strip()
        if not ticker:
            continue
        ticker_key = ticker.upper()
        current_price = _safe_float(row.get("Current_Price"))
        return_1m = _first_float(row.get("Return_1M"), row.get("Mom_1M"))
        snapshot = snapshots.get(ticker_key)
        if snapshot:
            current_price = snapshot["current_price"] if snapshot["current_price"] is not None else current_price
            return_1m = snapshot["return_1m"] if snapshot["return_1m"] is not None else return_1m
            if snapshot.get("updated_at"):
                row["Price_Updated_At"] = snapshot["updated_at"]
                row["Last_Updated"] = snapshot["updated_at"]
            if snapshot.get("source"):
                row["Price_Source"] = snapshot["source"]
                row["Source"] = row.get("Source") or snapshot["source"]
        elif current_price is None or return_1m is None:
            should_fetch = max_fetch is None or fetch_count < max_fetch
            if should_fetch:
                fetched_price, fetched_return = _portfolio_price_metrics(ticker, market)
                fetch_count += 1
                current_price = current_price if current_price is not None else fetched_price
                return_1m = return_1m if return_1m is not None else fetched_return
        row["Current_Price"] = current_price
        row["Return_1M"] = return_1m
        daily_change, daily_horizon = daily_changes.get(ticker_key, (None, ""))
        if daily_change is not None:
            row["Daily_Change_Pct"] = daily_change
            row["Daily_Change_Horizon"] = daily_horizon
    return records


def _rank_match_keys(ticker: str | None) -> set[str]:
    text = str(ticker or "").strip().upper()
    if not text:
        return set()
    keys = {text}
    code = _kr_code(text)
    if code:
        keys.update({code, f"{code}.KS", f"{code}.KQ"})
    return keys


def _enrich_rank_change_fields(records: list[dict], dataset: str, market: str | None) -> list[dict]:
    if not records:
        return records
    previous = _repository().read_previous_ranks(dataset, market=market)
    if previous.empty:
        return records

    previous_by_ticker: dict[str, int] = {}
    for _, row in previous.iterrows():
        previous_rank = _safe_float(row.get("Previous_Rank"))
        if previous_rank is None:
            continue
        for key in _rank_match_keys(row.get("Ticker")):
            previous_by_ticker.setdefault(key, int(previous_rank))

    if not previous_by_ticker:
        return records

    for row in records:
        current_rank = _safe_float(row.get("Rank"))
        if current_rank is None:
            continue
        previous_rank = None
        for key in _rank_match_keys(row.get("Ticker")):
            previous_rank = previous_by_ticker.get(key)
            if previous_rank is not None:
                break

        if previous_rank is None:
            row["Previous_Rank"] = None
            row["Rank_Change"] = None
            row["Rank_Status"] = "new"
            continue

        current = int(current_rank)
        change = previous_rank - current
        row["Previous_Rank"] = previous_rank
        row["Rank_Change"] = change
        row["Rank_Status"] = "up" if change > 0 else "down" if change < 0 else "same"
    return records


def _portfolio_display_overrides(records: list[dict], market: str | None) -> list[dict]:
    if str(market or "").upper() != "US":
        return records
    return [row for row in records if not is_banned_ticker(row.get("Ticker"))]


def _clean_meta_value(value) -> str | None:
    if value is None:
        return None
    if isinstance(value, float) and math.isnan(value):
        return None
    text = str(value).strip()
    if not text or text.lower() in {"nan", "nat", "none", "null"}:
        return None
    return text


def _meta_key(value) -> str:
    return re.sub(r"[^a-z0-9]+", "", str(value or "").lower())


def _first_frame_value(df: pd.DataFrame, keys: list[str]) -> str | None:
    if df.empty:
        return None
    for key in keys:
        if key not in df.columns:
            continue
        for value in df[key].tolist():
            cleaned = _clean_meta_value(value)
            if cleaned:
                return cleaned
    return None


def _summary_metric_value(summary: pd.DataFrame, metric: str) -> str | None:
    if summary.empty or "Metric" not in summary.columns:
        return None
    value_cols = [col for col in ("Value", "Metric_Value", "Result") if col in summary.columns]
    if not value_cols:
        return None
    targets = {_meta_key(metric), _meta_key(metric.replace("_", " "))}
    for _, row in summary.iterrows():
        if _meta_key(row.get("Metric")) not in targets:
            continue
        for col in value_cols:
            cleaned = _clean_meta_value(row.get(col))
            if cleaned:
                return cleaned
    return None


def _weighted_expected_return(portfolio: pd.DataFrame) -> str | None:
    if portfolio.empty or "Expected_Return" not in portfolio.columns or "Weight(%)" not in portfolio.columns:
        return None
    expected = pd.to_numeric(portfolio["Expected_Return"], errors="coerce")
    weights = pd.to_numeric(portfolio["Weight(%)"], errors="coerce")
    valid = expected.notna() & weights.notna()
    if not valid.any():
        return None
    value = float((expected[valid] * weights[valid]).sum())
    if not math.isfinite(value):
        return None
    return f"{value:.4f}"


def _portfolio_meta_aliases(meta: dict) -> dict:
    out = dict(meta)

    def copy_first(target: str, sources: list[str]) -> None:
        if _clean_meta_value(out.get(target)):
            return
        for source in sources:
            value = _clean_meta_value(out.get(source))
            if value:
                out[target] = value
                return

    copy_first("Cash_Weight", ["Cash Weight", "CashWeight"])
    copy_first("Cash Weight", ["Cash_Weight", "CashWeight"])
    copy_first("Generated", ["Generated_At", "Last_Updated"])
    copy_first("Generated_At", ["Generated", "Last_Updated"])
    copy_first("Expected_Return", ["Ann. Return (hist. est.)", "Ann. Return", "Expected Return"])
    copy_first("Ann. Return (hist. est.)", ["Expected_Return", "Ann. Return", "Expected Return"])
    copy_first("Regime", ["Macro Regime", "Macro_Regime"])
    copy_first("Macro_Regime", ["Regime", "Macro Regime"])
    return out


def _portfolio_meta_from_storage(market: str, portfolio: pd.DataFrame) -> dict:
    meta: dict[str, str] = {"Source": "storage"}
    summary = _load_storage_df(f"{market}_Final_Portfolio_Risk_Summary", market=market)

    cash_weight = _summary_metric_value(summary, "Cash_Weight")
    if cash_weight:
        meta["Cash_Weight"] = cash_weight
        meta["Cash Weight"] = cash_weight

    generated = (
        _first_frame_value(summary, ["Generated_At", "Generated"])
        or _summary_metric_value(summary, "Generated_At")
        or _summary_metric_value(summary, "Generated")
        or _first_frame_value(portfolio, ["Last_Updated"])
    )
    if generated:
        meta["Generated"] = generated
        meta["Generated_At"] = generated

    expected_return = _weighted_expected_return(portfolio)
    if expected_return:
        meta["Expected_Return"] = expected_return
        meta["Ann. Return (hist. est.)"] = expected_return

    return _portfolio_meta_aliases(meta)


def _kr_code(value) -> str:
    match = re.search(r"(\d{6})", str(value or "").strip().upper())
    return match.group(1) if match else ""


def _is_missing_kr_name(name, ticker="") -> bool:
    name_text = str(name or "").strip()
    ticker_text = str(ticker or "").strip()
    code = _kr_code(ticker_text)
    if not name_text:
        return True
    if ticker_text and name_text.upper() == ticker_text.upper():
        return True
    if code and name_text == code:
        return True
    return bool(re.fullmatch(r"\d{6}(?:\.(?:KS|KQ))?", name_text.upper()))


def _has_kr_suffix(ticker) -> bool:
    return bool(re.fullmatch(r"\d{6}\.(?:KS|KQ)", str(ticker or "").strip().upper()))


@lru_cache(maxsize=2048)
def _naver_kr_identity(code: str) -> dict:
    """Return Korean stock identity from Naver's free mobile quote metadata."""
    code = _kr_code(code)
    if not code:
        return {}
    headers = {
        "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X)",
        "Referer": "https://m.stock.naver.com/",
    }
    try:
        resp = requests.get(
            f"https://polling.finance.naver.com/api/realtime/domestic/stock/{code}",
            headers=headers,
            timeout=5,
        )
        if resp.status_code == 200:
            data = (resp.json().get("datas") or [{}])[0]
            name = str(data.get("stockName") or "").strip()
            exchange_info = data.get("stockExchangeType") or {}
            exchange = exchange_info.get("code") or data.get("stockExchangeName") or ""
            suffix = ".KQ" if str(exchange).upper() in {"KQ", "KOSDAQ"} else ".KS"
            market_cap = _safe_float(str(data.get("marketValueFullRaw") or "").replace(",", ""))
            payload = {"Ticker": f"{code}{suffix}", "Name": name}
            if exchange:
                payload["Exchange"] = str(exchange).upper()
            if market_cap is not None:
                payload["MarketCap"] = market_cap
            if name:
                return payload
    except Exception:
        pass

    try:
        resp = requests.get(
            f"https://m.stock.naver.com/api/stock/{code}/basic",
            headers=headers,
            timeout=5,
        )
        if resp.status_code != 200:
            return {}
        data = resp.json()
        name = str(data.get("stockName") or "").strip()
        exchange = (
            (data.get("stockExchangeType") or {}).get("code")
            or data.get("stockExchangeName")
            or ""
        )
        suffix = ".KQ" if str(exchange).upper() in {"KQ", "KOSDAQ"} else ".KS"
        return {"Ticker": f"{code}{suffix}", "Name": name} if name else {}
    except Exception:
        return {}


def _enrich_kr_company_identities(records: list[dict]) -> list[dict]:
    """Normalize Korean ticker suffixes and replace ticker-like names before serving clients."""
    enriched = []
    for row in records:
        item = dict(row)
        ticker = str(item.get("Ticker") or "").strip()
        name = str(item.get("Name") or "").strip()
        code = _kr_code(ticker or name)

        if not ticker and code:
            ticker = code

        if code and (_is_missing_kr_name(name, ticker) or not _has_kr_suffix(ticker)):
            ident = _naver_kr_identity(code)
            if ident:
                if not _has_kr_suffix(ticker):
                    ticker = ident["Ticker"]
                if _is_missing_kr_name(name, ticker):
                    name = ident["Name"]

        item["Ticker"] = ticker
        item["Name"] = name or code or ticker
        enriched.append(item)
    return enriched


@app.on_event("startup")
def startup():
    _init_auth_db()


# ── Sheet parsers ─────────────────────────────────────────────────────────────

def _load_portfolio(sheet_name: str) -> tuple[dict, list[dict]]:
    """
    Portfolio sheets have a key-value summary block before the column headers.
    Detect the header row as the first row with 3+ non-empty cells.
    """
    market = "KR" if sheet_name.startswith("KR_") else "US" if sheet_name.startswith("US_") else None
    storage_df = _load_storage_df(sheet_name, market=market)
    if not storage_df.empty:
        storage_df = _normalize_portfolio_price_columns(storage_df)
        storage_df = _coerce(storage_df, PORTFOLIO_NUMERIC_COLS)
        records = _enrich_portfolio_price_fields(_df_to_records(storage_df), market, max_fetch=0)
        records = _enrich_rank_change_fields(records, sheet_name, market)
        records = _portfolio_display_overrides(records, market)
        return _portfolio_meta_from_storage(market or "GLOBAL", storage_df), records

    ws   = _spreadsheet().worksheet(sheet_name)
    data = ws.get_all_values()
    if not data:
        _record_data_source(sheet_name, "sheet_empty", market=market, rows=0)
        return {}, []

    header_idx = next(
        (i for i, row in enumerate(data) if sum(1 for c in row if c.strip()) >= 3),
        0,
    )

    meta = {
        row[0].strip(): row[1].strip()
        for row in data[:header_idx]
        if len(row) >= 2 and row[0].strip() and row[1].strip()
    }
    meta = _portfolio_meta_aliases(meta)

    if header_idx >= len(data) - 1:
        _record_data_source(sheet_name, "sheet_empty", market=market, rows=0)
        return meta, []

    df = _sheet_values_to_df(data[header_idx + 1:], data[header_idx])
    df = df[df.apply(lambda r: r.str.strip().ne("").any(), axis=1)]
    if "Ticker" in df.columns:
        df = df[df["Ticker"].str.strip() != ""]

    df = _normalize_portfolio_price_columns(df)
    df = _coerce(df, PORTFOLIO_NUMERIC_COLS)

    _record_data_source(sheet_name, "sheet", market=market, rows=len(df))
    records = _enrich_portfolio_price_fields(_df_to_records(df.reset_index(drop=True)), market, max_fetch=0)
    records = _enrich_rank_change_fields(records, sheet_name, market)
    records = _portfolio_display_overrides(records, market)
    return meta, records


def _load_simple(sheet_name: str, num_cols: list[str]) -> list[dict]:
    """Simple sheets: row 0 = header, rest = data (no summary block)."""
    market = "KR" if sheet_name.startswith("KR_") else "US" if sheet_name.startswith("US_") else None
    storage_df = _load_storage_df(sheet_name, market=market)
    if not storage_df.empty:
        storage_df = _coerce(storage_df, num_cols)
        return _df_to_records(storage_df)

    ws   = _spreadsheet().worksheet(sheet_name)
    data = ws.get_all_values()
    if len(data) < 2:
        _record_data_source(sheet_name, "sheet_empty", market=market, rows=0)
        return []

    df = _sheet_values_to_df(data[1:], data[0])
    if "Ticker" in df.columns:
        df = df[df["Ticker"].str.strip() != ""]
    if "Rank" in df.columns:
        df = df[pd.to_numeric(df["Rank"], errors="coerce").notna()]

    df = _coerce(df, num_cols)
    _record_data_source(sheet_name, "sheet", market=market, rows=len(df))
    return _df_to_records(df.reset_index(drop=True))


def _load_table(sheet_name: str, num_cols: list[str] | None = None) -> pd.DataFrame:
    """Load a simple sheet as a DataFrame from storage first, then Sheets."""
    market = "KR" if sheet_name.startswith("KR_") else "US" if sheet_name.startswith("US_") else None
    df = _load_storage_df(sheet_name, market=market)
    if df.empty:
        try:
            data = _spreadsheet().worksheet(sheet_name).get_all_values()
        except Exception as exc:
            _record_data_source(sheet_name, "sheet_error", market=market, detail=f"{type(exc).__name__}: {exc}")
            return pd.DataFrame()
        if len(data) < 2:
            _record_data_source(sheet_name, "sheet_empty", market=market, rows=0)
            return pd.DataFrame()
        df = _sheet_values_to_df(data[1:], data[0])
        _record_data_source(sheet_name, "sheet", market=market, rows=len(df))
    if "Ticker" in df.columns:
        df = df[df["Ticker"].astype(str).str.strip() != ""]
    if num_cols:
        df = _coerce(df, num_cols)
    return df.reset_index(drop=True)


def _ticker_membership(sheet_names: list[str]) -> set[str]:
    tickers: set[str] = set()
    for sheet_name in sheet_names:
        try:
            if sheet_name.endswith("_Final_Portfolio"):
                _, records = _load_portfolio(sheet_name)
            else:
                records = _load_simple(sheet_name, [])
        except Exception:
            records = []
        tickers.update(str(row.get("Ticker") or "").strip() for row in records)
    return {ticker for ticker in tickers if ticker}


def _search_universe_payload(query: str = "", limit: int = 100) -> dict:
    frames: list[pd.DataFrame] = []
    q = str(query or "").strip()
    safe_limit = max(1, min(int(limit or 100), 200))
    for sheet_name, market in (("US_Universe", "US"), ("KR_Universe", "KR")):
        df = _load_table(sheet_name, ["MarketCap"])
        if df.empty:
            continue
        keep = [col for col in ["Ticker", "Name", "Market", "Sector", "MarketCap"] if col in df.columns]
        if "Ticker" not in keep:
            continue
        out = df[keep].copy()
        if "Name" not in out.columns:
            out["Name"] = out["Ticker"]
        if "Market" not in out.columns:
            out["Market"] = market
        if "Sector" not in out.columns:
            out["Sector"] = ""
        if "MarketCap" not in out.columns:
            out["MarketCap"] = None
        frames.append(out)

    etf_frame = _etf_search_universe_frame(q, safe_limit)
    if not etf_frame.empty:
        frames.append(etf_frame)

    if frames:
        universe = pd.concat(frames, ignore_index=True)
        universe = _coerce(universe, ["MarketCap"])
        universe["Ticker"] = universe["Ticker"].fillna("").astype(str).str.strip()
        universe["Name"] = universe["Name"].fillna("").astype(str).str.strip()
        universe["Market"] = universe["Market"].fillna("").astype(str).str.upper().str.strip()
        universe["Sector"] = universe["Sector"].fillna("").astype(str).str.strip()
        universe = universe[universe["Ticker"] != ""].drop_duplicates(subset=["Ticker"]).reset_index(drop=True)
    else:
        universe = pd.DataFrame(columns=["Ticker", "Name", "Market", "Sector", "MarketCap"])

    portfolio_set = _ticker_membership(["US_Final_Portfolio", "KR_Final_Portfolio"])
    gem_set = _ticker_membership(["US_SmallCap_Gems", "KR_SmallCap_Gems"])

    if q and not universe.empty:
        q_up = q.upper()
        q_low = q.lower()
        tickers = universe["Ticker"].str.upper()
        names = universe["Name"].str.lower()

        mask_exact = tickers == q_up
        mask_t_starts = tickers.str.startswith(q_up)
        mask_n_starts = names.str.startswith(q_low)
        mask_t_has = tickers.str.contains(q_up, regex=False)
        mask_n_has = names.str.contains(q_low, regex=False, na=False)
        mask = mask_exact | mask_t_starts | mask_n_starts | mask_t_has | mask_n_has

        result = universe[mask].copy()
        priority = pd.Series(4, index=result.index)
        priority[mask_n_has[mask]] = 4
        priority[mask_t_has[mask]] = 3
        priority[mask_n_starts[mask]] = 2
        priority[mask_t_starts[mask]] = 1
        priority[mask_exact[mask]] = 0
        result["_priority"] = priority
        result = result.sort_values(["_priority", "MarketCap"], ascending=[True, False], na_position="last")
        result = result.drop(columns=["_priority"]).head(safe_limit).reset_index(drop=True)
    elif not universe.empty:
        market_tops = []
        for market in ("KR", "US"):
            market_tops.append(
                universe[universe["Market"].str.upper() == market]
                .sort_values("MarketCap", ascending=False, na_position="last")
                .head(max(1, safe_limit // 2))
            )
        result = pd.concat(market_tops, ignore_index=True).head(safe_limit).reset_index(drop=True)
    else:
        result = universe

    if not result.empty:
        portfolio_codes = {_kr_code(ticker) for ticker in portfolio_set if _kr_code(ticker)}
        gem_codes = {_kr_code(ticker) for ticker in gem_set if _kr_code(ticker)}
        result["Rank"] = range(1, len(result) + 1)
        result["In_Portfolio"] = result["Ticker"].apply(
            lambda ticker: ticker in portfolio_set or (_kr_code(ticker) in portfolio_codes if _kr_code(ticker) else False)
        )
        result["In_SmallCap"] = result["Ticker"].apply(
            lambda ticker: ticker in gem_set or (_kr_code(ticker) in gem_codes if _kr_code(ticker) else False)
        )
        result["Currency"] = result["Market"].apply(lambda market: "KRW" if str(market).upper() == "KR" else "USD")
        result["Logo_URL"] = result.apply(
            lambda row: _default_logo_url(str(row.get("Ticker") or ""), str(row.get("Market") or "")),
            axis=1,
        )
        kr_mask = result["Market"].str.upper() == "KR"
        if kr_mask.any():
            kr_records = _enrich_kr_company_identities(_df_to_records(result[kr_mask]))
            kr_df = pd.DataFrame(kr_records)
            for idx, (_, row) in zip(result[kr_mask].index, kr_df.iterrows()):
                result.at[idx, "Ticker"] = row.get("Ticker") or result.at[idx, "Ticker"]
                result.at[idx, "Name"] = row.get("Name") or result.at[idx, "Name"]

    return {
        "query": q,
        "count": int(len(result)),
        "portfolio_count": len(portfolio_set),
        "smallcap_count": len(gem_set),
        "groups": _search_result_groups(result),
        "stocks": _df_to_records(result),
    }


def _search_result_group_name(row: dict) -> str:
    sector = str(row.get("Sector") or "").strip()
    ticker = str(row.get("Ticker") or "").strip().upper()
    if sector.upper().startswith("ETF"):
        return "ETF"
    if ticker.startswith("^") or ticker.endswith("=F") or ticker.endswith("=X"):
        return "지수"
    if sector:
        return "기업"
    return "기타"


def _search_result_groups(result: pd.DataFrame) -> list[dict]:
    if result.empty:
        return []
    records = _df_to_records(result)
    order = {"기업": 0, "ETF": 1, "지수": 2, "기타": 3}
    grouped: dict[str, list[dict]] = {}
    for record in records:
        grouped.setdefault(_search_result_group_name(record), []).append(record)
    return [
        {
            "label": label,
            "count": len(items),
            "tickers": [str(item.get("Ticker") or "") for item in items[:8]],
        }
        for label, items in sorted(grouped.items(), key=lambda item: order.get(item[0], 9))
    ]


def _is_yahoo_etf_search_query(query: str) -> bool:
    clean = str(query or "").strip()
    if not clean or len(clean) > 80:
        return False
    return bool(re.fullmatch(r"[A-Za-z0-9 .,&'’/\-]+", clean))


def _yahoo_etf_search_records(query: str, limit: int = 12) -> list[dict]:
    clean = str(query or "").strip()
    if not clean or not _is_yahoo_etf_search_query(clean):
        return []
    try:
        response = requests.get(
            "https://query1.finance.yahoo.com/v1/finance/search",
            params={"q": clean, "quotesCount": max(1, min(limit * 3, 30)), "newsCount": 0},
            timeout=6,
            headers={"User-Agent": "Qubit/1.0"},
        )
        response.raise_for_status()
        quotes = response.json().get("quotes") or []
    except Exception as exc:
        _record_data_source("ETF_Insights", "yahoo_search_error", detail=f"{clean}: {type(exc).__name__}")
        return []

    records: list[dict] = []
    for quote in quotes:
        quote_type = str(quote.get("quoteType") or quote.get("typeDisp") or "").strip().upper()
        symbol = str(quote.get("symbol") or "").strip().upper()
        if quote_type != "ETF" or not symbol or len(symbol) > 12:
            continue
        name = str(quote.get("longname") or quote.get("shortname") or symbol).strip()
        exchange = str(quote.get("exchDisp") or quote.get("exchange") or "").strip()
        theme = exchange or "ETF 검색"
        records.append(
            {
                "rank": 9000 + len(records),
                "ticker": symbol,
                "name": name,
                "region": "US",
                "category": "검색",
                "theme": theme,
                "summary": f"{name} 검색 결과입니다. 가격 차트와 ETF 기본 정보부터 확인하세요.",
                "expenseRatio": "확인 필요",
                "aum": "확인 필요",
                "distribution": "확인 필요",
                "outlook": "검색으로 추가된 ETF입니다. 가격 흐름, 추종 대상, 비용을 먼저 확인하세요.",
                "risk": "신규 또는 소형 ETF일 수 있으므로 유동성, 스프레드, 구성종목 데이터를 함께 점검하세요.",
                "holdings": [],
                "exposures": [],
                "asOf": _utc_now_iso(),
                "dataSource": "yahoo_search",
            }
        )
        if len(records) >= limit:
            break
    if records:
        _record_data_source("ETF_Insights", "yahoo_search", rows=len(records), detail=clean)
    return records


def _etf_search_universe_frame(query: str, limit: int) -> pd.DataFrame:
    clean = str(query or "").strip()
    if not clean:
        return pd.DataFrame(columns=["Ticker", "Name", "Market", "Sector", "MarketCap"])

    payload = _etf_payload_from_records(_etf_storage_records(), q=clean, limit=limit)
    records = list(payload.get("items") or [])
    seen = {str(item.get("ticker") or "").strip().upper() for item in records}
    for item in _yahoo_etf_search_records(clean, limit=12):
        ticker = str(item.get("ticker") or "").strip().upper()
        if ticker and ticker not in seen:
            records.append(item)
            seen.add(ticker)
    if not records:
        return pd.DataFrame(columns=["Ticker", "Name", "Market", "Sector", "MarketCap"])

    rows = []
    for item in records:
        ticker = str(item.get("ticker") or "").strip().upper()
        if not ticker:
            continue
        category = str(item.get("category") or "ETF").strip()
        theme = str(item.get("theme") or "").strip()
        rows.append(
            {
                "Ticker": ticker,
                "Name": str(item.get("name") or ticker).strip(),
                "Market": str(item.get("region") or "US").strip().upper(),
                "Sector": "ETF" + (f" · {category}" if category else "") + (f" · {theme}" if theme else ""),
                "MarketCap": None,
            }
        )
    return pd.DataFrame(rows, columns=["Ticker", "Name", "Market", "Sector", "MarketCap"])


def _load_backtest_df(sheet_name: str) -> pd.DataFrame:
    market = "KR" if sheet_name.startswith("KR_") else "US"
    df = _load_storage_df(sheet_name, market=market)
    if df.empty:
        try:
            data = _spreadsheet().worksheet(sheet_name).get_all_values()
        except Exception as exc:
            _record_data_source(sheet_name, "sheet_error", market=market, detail=f"{type(exc).__name__}: {exc}")
            return pd.DataFrame()
        header_idx = next(
            (
                idx for idx, row in enumerate(data)
                if "Date" in [str(col).strip() for col in row]
                and ("Net_Return" in row or "Return" in row or "Cumulative_Ret" in row)
            ),
            None,
        )
        if header_idx is None or header_idx + 1 >= len(data):
            _record_data_source(sheet_name, "sheet_empty", market=market, rows=0)
            return pd.DataFrame()
        df = _sheet_values_to_df(data[header_idx + 1:], data[header_idx])
        _record_data_source(sheet_name, "sheet", market=market, rows=len(df))

    if df.empty:
        return df
    if "Return" in df.columns and "Net_Return" not in df.columns:
        df = df.rename(columns={"Return": "Net_Return"})
    for col in ["Net_Return", "Cumulative_Ret", "Drawdown", "Gross_Return", "Turnover_Pct"]:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    if "Date" in df.columns:
        df["Date"] = pd.to_datetime(df["Date"], errors="coerce")
        df = df.dropna(subset=["Date"]).sort_values("Date")
    return df.reset_index(drop=True)


def _backtest_payload(sheet_name: str, market: str) -> dict:
    df = _load_backtest_df(sheet_name)
    summary = {
        "Market": market,
        "Sheet": sheet_name,
        "Periods": int(len(df)),
        "Latest_Date": "",
        "Cumulative_Ret": None,
        "Max_Drawdown": None,
        "Avg_Return": None,
    }
    if not df.empty:
        if "Date" in df.columns:
            summary["Latest_Date"] = str(df["Date"].iloc[-1].date())
        if "Cumulative_Ret" in df.columns:
            summary["Cumulative_Ret"] = _safe_float(df["Cumulative_Ret"].dropna().iloc[-1]) if not df["Cumulative_Ret"].dropna().empty else None
        if "Drawdown" in df.columns:
            summary["Max_Drawdown"] = _safe_float(df["Drawdown"].dropna().min()) if not df["Drawdown"].dropna().empty else None
        if "Net_Return" in df.columns:
            summary["Avg_Return"] = _safe_float(df["Net_Return"].dropna().mean()) if not df["Net_Return"].dropna().empty else None
    rows = df.tail(80).copy()
    if "Date" in rows.columns:
        rows["Date"] = rows["Date"].dt.strftime("%Y-%m-%d")
    return {"summary": summary, "rows": _df_to_records(rows)}


def _risk_drift_payload() -> dict:
    summary = _load_storage_df("Portfolio_Drift_Summary", market="GLOBAL")
    detail = _load_storage_df("Portfolio_Drift_Alert", market="GLOBAL")
    for df in (summary, detail):
        if df.empty:
            continue
        _coerce(df, [
            "Target_Weight", "Current_Weight", "Drift_Abs", "Drift_Pct",
            "Return_Since_Rebal", "Total_Drift", "Stocks_Rebalance",
            "Stocks_Watch", "Stocks_OK", "Days_Since_Rebal",
        ])
    return {
        "summaries": _df_to_records(summary),
        "items": _df_to_records(detail.sort_values("Drift_Abs", ascending=False).head(60)) if not detail.empty and "Drift_Abs" in detail.columns else _df_to_records(detail.head(60)),
    }


def _load_sectioned_sheet_table(sheet_name: str, section_title: str, num_cols: list[str] | None = None) -> pd.DataFrame:
    try:
        values = _spreadsheet().worksheet(sheet_name).get_all_values()
    except Exception:
        return pd.DataFrame()

    marker = f"-- {section_title} --"
    header_idx = None
    for idx, row in enumerate(values):
        first = str(row[0] if row else "").strip()
        if first == marker or first.strip("- ").strip() == section_title:
            header_idx = idx + 1
            break
    if header_idx is None or header_idx >= len(values):
        return pd.DataFrame()

    header = [str(col).strip() for col in values[header_idx]]
    rows = []
    for row in values[header_idx + 1:]:
        if not any(str(cell).strip() for cell in row):
            break
        first = str(row[0] if row else "").strip()
        if first.startswith("-- ") and first.endswith(" --"):
            break
        rows.append(row)
    if not rows:
        return pd.DataFrame(columns=header)

    df = _sheet_values_to_df(rows, header)
    if num_cols:
        _coerce(df, num_cols)
    return df.reset_index(drop=True)


def _load_report_dataset(
    dataset: str,
    *,
    market: str | None,
    num_cols: list[str],
    fallback_sheet: str | None = None,
    section_title: str | None = None,
) -> pd.DataFrame:
    df = _load_storage_df(dataset, market=market)
    if df.empty and fallback_sheet and section_title:
        df = _load_sectioned_sheet_table(fallback_sheet, section_title, num_cols)
    elif df.empty and fallback_sheet:
        df = _load_table(fallback_sheet, num_cols)
    if not df.empty:
        _coerce(df, num_cols)
    return df.reset_index(drop=True)


def _portfolio_risk_payload(market: str, limit: int = 30) -> dict:
    safe_market = market.upper()
    sheet = f"{safe_market}_Final_Portfolio_Risk"
    summary = _load_report_dataset(
        f"{safe_market}_Final_Portfolio_Risk_Summary",
        market=safe_market,
        fallback_sheet=sheet,
        section_title="Portfolio Risk Summary",
        num_cols=["Value"],
    )
    holdings = _load_report_dataset(
        f"{safe_market}_Final_Portfolio_Risk",
        market=safe_market,
        fallback_sheet=sheet,
        section_title="Holding Risk Contribution",
        num_cols=[
            "Portfolio_Weight", "Sleeve_Weight", "Asset_Vol", "Marginal_Risk",
            "Risk_Contribution", "Risk_Contribution_Pct", "Weight_Risk_Ratio",
            "Total_Score", "MarketCap",
        ],
    )
    sectors = _load_report_dataset(
        f"{safe_market}_Final_Portfolio_Risk_Sectors",
        market=safe_market,
        fallback_sheet=sheet,
        section_title="Sector Risk Contribution",
        num_cols=[
            "Holdings", "Sector_Weight", "Sector_Risk_Contribution",
            "Sector_Risk_Contribution_Pct", "Max_Holding_Risk_Contribution_Pct",
        ],
    )
    if not holdings.empty and "Risk_Contribution_Pct" in holdings.columns:
        holdings = holdings.sort_values("Risk_Contribution_Pct", ascending=False)
    if not sectors.empty and "Sector_Risk_Contribution_Pct" in sectors.columns:
        sectors = sectors.sort_values("Sector_Risk_Contribution_Pct", ascending=False)
    safe_limit = max(1, min(int(limit or 30), 100))
    return {
        "market": safe_market,
        "summary": _df_to_records(summary),
        "holdings": _df_to_records(holdings.head(safe_limit)),
        "sectors": _df_to_records(sectors.head(safe_limit)),
    }


def _rebalance_payload(market: str, limit: int = 50) -> dict:
    safe_market = market.upper()
    sheet = f"{safe_market}_Rebalance_Execution"
    summary = _load_report_dataset(
        f"{safe_market}_Rebalance_Execution_Summary",
        market=safe_market,
        fallback_sheet=sheet,
        section_title="Rebalance Execution Summary",
        num_cols=[
            "Portfolio_Value", "Current_Cash_Value", "Target_Cash_Value",
            "Projected_Cash_Value", "Gross_Buy_Value", "Gross_Sell_Value",
            "Net_Cash_Needed", "One_Way_Turnover", "Gross_Turnover",
            "Trade_Count", "Buy_Count", "Sell_Count", "Hold_Count",
            "Estimated_Fees", "Estimated_Slippage", "Estimated_Total_Cost",
            "Rebalance_Band", "Min_Trade_Value",
        ],
    )
    orders = _load_report_dataset(
        f"{safe_market}_Rebalance_Execution",
        market=safe_market,
        fallback_sheet=sheet,
        section_title="Suggested Orders",
        num_cols=[
            "Current_Weight", "Target_Weight", "Delta_Weight", "Current_Value",
            "Target_Value", "Trade_Value", "Executable_Trade_Value",
            "Estimated_Shares", "Price", "Fee_Est", "Slippage_Est", "Cost_Est",
        ],
    )
    if not orders.empty and "Executable_Trade_Value" in orders.columns:
        orders = orders.assign(_abs_trade=orders["Executable_Trade_Value"].abs())
        orders = orders.sort_values("_abs_trade", ascending=False).drop(columns=["_abs_trade"])
    safe_limit = max(1, min(int(limit or 50), 200))
    return {
        "market": safe_market,
        "summary": _df_to_records(summary),
        "orders": _df_to_records(orders.head(safe_limit)),
    }


def _shadow_attribution_payload(market: str = "ALL", limit: int = 50) -> dict:
    safe_market = str(market or "ALL").upper()
    summary = _load_report_dataset(
        "Shadow_Portfolio_Attribution_Summary",
        market=None,
        fallback_sheet="Shadow_Portfolio_Attribution_Summary",
        num_cols=[
            "Horizon_Trading_Days", "Holdings", "Coverage", "Invested_Weight",
            "Cash_Weight", "Actual_Return", "Benchmark_Return", "Alpha_Actual",
            "Stock_Excess_Contribution", "Cash_Opportunity_Cost", "Explained_Alpha",
            "Top_Contribution", "Worst_Contribution", "Top_Sector_Contribution",
            "Worst_Sector_Contribution", "Hit_Rate", "Positive_Rate", "Score_Return_IC",
        ],
    )
    detail = _load_report_dataset(
        "Shadow_Portfolio_Attribution",
        market=None,
        fallback_sheet="Shadow_Portfolio_Attribution",
        num_cols=[
            "Horizon_Trading_Days", "Rank", "Weight", "Sleeve_Weight", "Equal_Weight",
            "Total_Score", "Start_Price", "End_Price", "Stock_Return",
            "Benchmark_Return", "Actual_Contribution", "Sleeve_Contribution",
            "Equal_Contribution", "Benchmark_Contribution", "Excess_Contribution",
        ],
    )
    sectors = _load_report_dataset(
        "Shadow_Portfolio_Sector_Attribution",
        market=None,
        fallback_sheet="Shadow_Portfolio_Sector_Attribution",
        num_cols=[
            "Horizon_Trading_Days", "Holdings", "Coverage", "Sector_Weight",
            "Sector_Sleeve_Weight", "Mean_Return", "Weighted_Return",
            "Benchmark_Return", "Actual_Contribution", "Sleeve_Contribution",
            "Benchmark_Contribution", "Excess_Contribution", "Hit_Rate",
        ],
    )
    if safe_market in {"US", "KR"}:
        for frame in (summary, detail, sectors):
            if not frame.empty and "Market" in frame.columns:
                frame.drop(frame[frame["Market"].astype(str).str.upper() != safe_market].index, inplace=True)
    if not detail.empty and "Actual_Contribution" in detail.columns:
        detail = detail.assign(_abs_contribution=detail["Actual_Contribution"].abs())
        detail = detail.sort_values("_abs_contribution", ascending=False).drop(columns=["_abs_contribution"])
    if not sectors.empty and "Actual_Contribution" in sectors.columns:
        sectors = sectors.assign(_abs_contribution=sectors["Actual_Contribution"].abs())
        sectors = sectors.sort_values("_abs_contribution", ascending=False).drop(columns=["_abs_contribution"])
    safe_limit = max(1, min(int(limit or 50), 200))
    return {
        "market": safe_market,
        "summary": _df_to_records(summary.head(safe_limit)),
        "items": _df_to_records(detail.head(safe_limit)),
        "sectors": _df_to_records(sectors.head(safe_limit)),
    }


def _industry_payload(limit: int = 30) -> dict:
    df = _load_table("US_Industry_Ranking", [
        "Rank", "Stock_Count", "Mean_Return", "Breadth",
        "Mean_Return_Rank", "Breadth_Rank", "Combined_Rank", "Lookback_Days",
    ])
    if not df.empty and "Rank" in df.columns:
        df = df.sort_values("Rank")
    return {"items": _df_to_records(df.head(max(1, min(limit, 100))))}


def _order_flow_payload(limit: int = 30) -> dict:
    df = _load_table("KR_Dual_Net_Buyers", ["Rank", "Consecutive_Days", "Foreign_Net_Buy", "Inst_Net_Buy"])
    if not df.empty and "Rank" in df.columns:
        df = df.sort_values("Rank")
    return {"items": _df_to_records(df.head(max(1, min(limit, 100))))}


def _strip_news_html(value: str | None) -> str:
    text = re.sub(r"<[^>]+>", "", str(value or ""))
    return html.unescape(text).strip()


def _default_news_query(market: str) -> str:
    market = str(market or "").upper()
    if market == "KR":
        return "국내증시 코스피 코스닥 삼성전자 SK하이닉스"
    if market == "US":
        return "뉴욕증시 나스닥 S&P500 미국 주식 엔비디아 테슬라 애플"
    return "증시 주식 실적 반도체 환율"


def _default_news_queries(market: str) -> list[str]:
    market = str(market or "ALL").upper()
    if market == "KR":
        return [
            "국내증시 코스피 코스닥 외국인 기관",
            "삼성전자 SK하이닉스 반도체 AI",
            "현대차 배터리 바이오 조선 방산",
            "코스닥 성장주 실적 수급",
            "원달러 환율 한국은행 금리 채권",
        ]
    if market == "US":
        return [
            "뉴욕증시 나스닥 S&P500 연준 금리",
            "엔비디아 테슬라 애플 마이크로소프트 실적",
            "미국 반도체 AI 클라우드 빅테크",
            "미국 채권 금리 달러 유가",
            "미국 ETF 성장주 배당주",
        ]
    return [
        "뉴욕증시 나스닥 S&P500 연준 금리",
        "엔비디아 테슬라 애플 마이크로소프트 실적",
        "국내증시 코스피 코스닥 외국인 기관",
        "삼성전자 SK하이닉스 반도체 AI",
        "환율 원달러 유가 금 채권",
        "ETF 배당 성장주 가치주",
    ]


def _discovery_news_queries(market: str) -> list[str]:
    market = str(market or "ALL").upper()
    if market == "KR":
        return [
            "국내증시 속보 급락 급등",
            "한국 증시 시장 충격 리스크",
            "국내 주요 기업 실적 급등락",
        ]
    if market == "US":
        return [
            "뉴욕증시 속보 급락 급등",
            "월가 시장 충격 연준 리스크",
            "미국 주요 기업 실적 급등락",
        ]
    return [
        "증시 속보 금융시장 급락 급등",
        "시장 충격 리스크 위기 금융",
        "주요 기업 실적 전망 급등락",
    ]


def _market_news_query_direction(label: str, change_pct: float) -> str:
    if any(term in label for term in ("VIX", "환율", "달러", "국채", "채권", "유가", "원유", "금", "비트코인")):
        return "상승" if change_pct > 0 else "하락"
    return "급등" if change_pct > 0 else "급락"


def _market_news_query_threshold(item: dict) -> float:
    label = str(item.get("label") or "")
    category = str(item.get("category") or "").lower()
    symbol = str(item.get("symbol") or "").upper()
    if "VIX" in label or symbol == "^VIX":
        return 0.04
    if category in {"crypto", "commodity"}:
        return 0.025
    if category == "bond" or "국채" in label or "채권" in label:
        return 0.012
    if "환율" in label or "달러" in label:
        return 0.008
    return 0.010


def _market_news_query_allowed(item: dict, market: str) -> bool:
    market = str(market or "ALL").upper()
    if market == "ALL":
        return True
    label = str(item.get("label") or "")
    region = str(item.get("region") or "").lower()
    symbol = str(item.get("symbol") or "").upper()
    domestic = region == "domestic" or symbol in {"^KS11", "^KQ11"} or any(term in label for term in ("KOSPI", "KOSDAQ", "국고채", "회사채", "환율"))
    if market == "KR":
        return domestic or "달러" in label or "원유" in label or "유가" in label
    if market == "US":
        return not domestic or symbol in {"KRW=X"}
    return True


def _dynamic_market_news_queries(market: str, limit: int = 4) -> list[str]:
    try:
        payload = _market_indicators_payload(category="all", refresh=False)
    except Exception:
        return []
    rows = payload.get("items") if isinstance(payload, dict) else []
    if not isinstance(rows, list):
        return []

    candidates: list[tuple[float, str]] = []
    for item in rows:
        if not isinstance(item, dict) or not _market_news_query_allowed(item, market):
            continue
        label = str(item.get("label") or "").strip()
        change_pct = _safe_float(item.get("change_pct"))
        if not label or change_pct is None or not math.isfinite(change_pct):
            continue
        threshold = _market_news_query_threshold(item)
        if abs(change_pct) < threshold:
            continue
        direction = _market_news_query_direction(label, change_pct)
        query = f"{label} {direction} 이유"
        candidates.append((abs(change_pct), query))

    output: list[str] = []
    seen: set[str] = set()
    for _, query in sorted(candidates, reverse=True):
        key = query.lower()
        if key in seen:
            continue
        seen.add(key)
        output.append(query)
        if len(output) >= limit:
            break
    return output


def _news_query_plan(query: str, market: str) -> list[str]:
    value = str(query or "").strip()
    market = str(market or "ALL").upper()
    default_values = {_default_news_query(scope).strip().lower() for scope in ("ALL", "US", "KR")}
    if value and value.lower() not in default_values:
        return [value]
    queries = [
        *_default_news_queries(market),
        *_dynamic_market_news_queries(market, limit=3),
        *_discovery_news_queries(market)[:2],
    ]
    output: list[str] = []
    seen: set[str] = set()
    for current in queries:
        key = current.strip().lower()
        if not key or key in seen:
            continue
        seen.add(key)
        output.append(current)
    return output[:10]


def _news_cache_query_key(query: str, market: str) -> str:
    value = str(query or "").strip().lower()
    default_values = {_default_news_query(scope).strip().lower() for scope in ("ALL", "US", "KR")}
    if not value or value in default_values:
        return "default-link-analysis-v1"
    return f"link-analysis-v1:{value}"


def _news_market_matches(title: str, summary: str, market: str) -> bool:
    market = str(market or "ALL").upper()
    if market == "ALL":
        return True
    text = f"{title or ''} {summary or ''}".lower()
    if market == "US":
        excluded = [
            "삼성전자", "sk하이닉스", "코스피", "코스닥",
            "국내 증시", "한국 증시", "현대차",
        ]
        if any(term.lower() in text for term in excluded):
            return False
        included = [
            "미국", "뉴욕증시", "미 증시", "미증시", "나스닥", "s&p",
            "다우", "월가", "엔비디아", "nvidia", "테슬라", "애플",
            "apple", "마이크로소프트", "microsoft", "알파벳",
            "amazon", "아마존", "연준", "fed", "빅테크", "클라우드",
            "미국채", "미 국채", "달러", "유가", "ai",
        ]
        return any(term.lower() in text for term in included)
    if market == "KR":
        included = [
            "국내 증시", "한국 증시", "코스피", "코스닥", "삼성전자",
            "sk하이닉스", "현대차", "외국인", "기관", "한국거래소",
            "2차전지", "배터리", "바이오", "조선", "방산", "네이버",
            "카카오", "lg에너지솔루션", "한국은행", "원달러",
        ]
        return any(term.lower() in text for term in included)
    return True


def _internal_issue_news(market: str = "ALL", limit: int = 20) -> list[dict]:
    market = str(market or "ALL").upper()
    now = datetime.utcnow().replace(microsecond=0).isoformat()
    items: list[dict] = []

    if market == "ALL":
        try:
            macro = _macro_payload()
            regime = str(macro.get("Regime") or "NEUTRAL")
            items.append({
                "id": "internal-macro-regime",
                "title": f"시장 흐름은 {regime} 상태",
                "summary": (
                    f"V {macro.get('US_V_Weight', '-')} · Q {macro.get('US_Q_Weight', '-')} · "
                    f"M {macro.get('US_M_Weight', '-')} 기준으로 팩터 비중을 점검합니다."
                ),
                "source": "",
                "url": "",
                "published_at": str(macro.get("Generated") or now),
                "market": "GLOBAL",
                "ticker": "",
                "kind": "macro",
            })
        except Exception:
            pass

    markets = ["US", "KR"] if market == "ALL" else [market]
    for current_market in markets:
        try:
            earnings_rows = _load_simple(
                f"{current_market}_Earnings_Momentum",
                ["Signal_Strength", "Surprise_Pct", "Return_Since", "Days_Since_Earnings", "Rank"],
            )
            for row in sorted(
                earnings_rows,
                key=lambda item: _safe_float(item.get("Signal_Strength")) or -999,
                reverse=True,
            )[:3]:
                ticker = str(row.get("Ticker") or "")
                name = str(row.get("Name") or ticker)
                items.append({
                    "id": f"internal-earnings-{current_market}-{ticker}",
                    "title": f"{name}, 실적 모멘텀 상위 후보",
                    "summary": (
                        f"서프라이즈 {row.get('Surprise_Pct', '-')} · 발표 후 수익률 "
                        f"{row.get('Return_Since', '-')} · 시그널 {row.get('Signal_Strength', '-')}"
                    ),
                    "source": "",
                    "url": "",
                    "published_at": now,
                    "market": current_market,
                    "ticker": ticker,
                    "kind": "earnings",
                })
        except Exception:
            pass

        try:
            smallcap_rows = _load_simple(
                f"{current_market}_SmallCap_Gems",
                ["Rank", "Total_Score", "RevGrowth", "Volume_Surge", "MarketCap"],
            )
            if current_market == "KR":
                smallcap_rows = _enrich_kr_company_identities(smallcap_rows)
            for row in sorted(
                smallcap_rows,
                key=lambda item: _safe_float(item.get("Total_Score")) or -999,
                reverse=True,
            )[:3]:
                ticker = str(row.get("Ticker") or "")
                name = str(row.get("Name") or ticker)
                items.append({
                    "id": f"internal-smallcap-{current_market}-{ticker}",
                    "title": f"{name}, SmallCap 점수 상위",
                    "summary": (
                        f"총점 {row.get('Total_Score', '-')} · 성장 {row.get('RevGrowth', '-')} · "
                        f"거래량 {row.get('Volume_Surge', '-')}"
                    ),
                    "source": "",
                    "url": "",
                    "published_at": now,
                    "market": current_market,
                    "ticker": ticker,
                    "kind": "smallcap",
                })
        except Exception:
            pass

    try:
        drift = _risk_drift_payload().get("items") or []
        for row in drift[:5]:
            row_market = str(row.get("Market") or "")
            if market != "ALL" and row_market.upper() != market:
                continue
            ticker = str(row.get("Ticker") or "")
            name = str(row.get("Name") or ticker)
            items.append({
                "id": f"internal-drift-{row_market}-{ticker}",
                "title": f"{name}, 리밸런싱 드리프트 점검",
                "summary": (
                    f"상태 {row.get('Status') or row.get('Recommendation') or '-'} · "
                    f"드리프트 {row.get('Drift_Abs', '-')}"
                ),
                "source": "",
                "url": "",
                "published_at": now,
                "market": row_market or "GLOBAL",
                "ticker": ticker,
                "kind": "drift",
            })
    except Exception:
        pass

    try:
        if market in {"ALL", "KR"}:
            for row in _order_flow_payload(limit=5).get("items") or []:
                ticker = str(row.get("Ticker") or "")
                name = str(row.get("Name") or ticker)
                items.append({
                    "id": f"internal-order-flow-{ticker}",
                    "title": f"{name}, 외국인+기관 동시 순매수",
                    "summary": (
                        f"{row.get('Consecutive_Days', '-')}일 연속 · 외국인 "
                        f"{row.get('Foreign_Net_Buy', '-')} · 기관 {row.get('Inst_Net_Buy', '-')}"
                    ),
                    "source": "",
                    "url": "",
                    "published_at": now,
                    "market": "KR",
                    "ticker": ticker,
                    "kind": "order_flow",
                })
    except Exception:
        pass

    if not items:
        items.append({
            "id": "internal-news-ready",
            "title": "뉴스 피드 준비 완료",
            "summary": "NAVER_CLIENT_ID와 NAVER_CLIENT_SECRET을 서버 환경변수에 넣으면 네이버 뉴스 검색 결과가 이 화면에 함께 표시됩니다.",
            "source": "",
            "url": "",
            "published_at": now,
            "market": "GLOBAL",
            "ticker": "",
            "kind": "setup",
        })

    return items[:max(1, min(limit, 100))]


def _news_dedupe_key(item: dict) -> str:
    url = str(item.get("url") or "").strip().lower()
    if url:
        return url
    return str(item.get("title") or "").strip().lower()


def _html_attr(tag: str, attr: str) -> str:
    match = re.search(
        rf"\b{re.escape(attr)}\s*=\s*(['\"])(.*?)\1",
        str(tag or ""),
        flags=re.IGNORECASE | re.DOTALL,
    )
    if not match:
        return ""
    return html.unescape(match.group(2).strip())


def _normalize_news_image_url(value: str, page_url: str) -> str:
    url = html.unescape(str(value or "").strip())
    if not url or url.startswith("data:"):
        return ""
    return urljoin(page_url, url)


def _extract_news_image_url(body: str, page_url: str) -> str:
    for tag in re.findall(r"<meta\b[^>]*>", body or "", flags=re.IGNORECASE | re.DOTALL):
        key = (_html_attr(tag, "property") or _html_attr(tag, "name")).lower()
        if key in {"og:image", "og:image:url", "twitter:image", "twitter:image:src", "thumbnail"}:
            image_url = _normalize_news_image_url(_html_attr(tag, "content"), page_url)
            if image_url:
                return image_url

    for tag in re.findall(r"<link\b[^>]*>", body or "", flags=re.IGNORECASE | re.DOTALL):
        rel = _html_attr(tag, "rel").lower()
        if "image_src" in rel:
            image_url = _normalize_news_image_url(_html_attr(tag, "href"), page_url)
            if image_url:
                return image_url
    return ""


def _news_image_url_for_url(url: str) -> str:
    clean_url = str(url or "").strip()
    if not clean_url:
        return ""

    def _load() -> str:
        try:
            response = requests.get(
                clean_url,
                headers={
                    "User-Agent": (
                        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
                    ),
                    "Accept": "text/html,application/xhtml+xml",
                },
                timeout=4,
                allow_redirects=True,
            )
            if response.status_code >= 400:
                return ""
            return _extract_news_image_url(response.text[:220_000], response.url or clean_url)
        except Exception:
            return ""

    return _cached(f"news_image_url_{abs(hash(clean_url))}", _load, ttl=21_600)


def _enrich_news_image_fields(items: list[dict]) -> list[dict]:
    targets: list[tuple[int, list[str]]] = []
    for index, item in enumerate(items):
        if str(item.get("image_url") or "").strip():
            continue
        urls = [
            str(item.get("url") or "").strip(),
            str(item.get("naver_link") or "").strip(),
        ]
        urls = [url for url in dict.fromkeys(urls) if url]
        if urls:
            targets.append((index, urls))

    if not targets:
        return items

    enriched = [dict(item) for item in items]
    with ThreadPoolExecutor(max_workers=min(6, len(targets))) as executor:
        futures = {
            executor.submit(
                lambda candidate_urls: next(
                    (image for image in (_news_image_url_for_url(url) for url in candidate_urls) if image),
                    "",
                ),
                urls,
            ): index
            for index, urls in targets
        }
        for future in as_completed(futures):
            image_url = future.result()
            if image_url:
                enriched[futures[future]]["image_url"] = image_url
    return enriched


def _round_robin_news_buckets(buckets: list[list[dict]], limit: int) -> list[dict]:
    output: list[dict] = []
    seen: set[str] = set()
    max_len = max((len(bucket) for bucket in buckets), default=0)
    for index in range(max_len):
        for bucket in buckets:
            if index >= len(bucket):
                continue
            item = bucket[index]
            key = _news_dedupe_key(item)
            if key in seen:
                continue
            seen.add(key)
            output.append(item)
            if len(output) >= limit:
                return output
    return output


_NEWS_BREAKING_TERMS = (
    "속보", "급락", "급등", "폭락", "폭등", "쇼크", "위기", "리스크",
    "파산", "부도", "거래정지", "하한가", "상한가", "전쟁", "중동",
    "관세", "규제", "인하", "동결", "긴급", "surge", "plunge", "crash",
    "bankruptcy", "halt", "tariff", "risk",
)


def _news_published_datetime(item: dict) -> datetime | None:
    value = str(item.get("published_at") or "").strip()
    if not value:
        return None
    try:
        dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except Exception:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def _news_recency_score(item: dict) -> float:
    published = _news_published_datetime(item)
    if published is None:
        return 0.0
    age_hours = max(0.0, (datetime.now(timezone.utc) - published).total_seconds() / 3600.0)
    if age_hours <= 2:
        return 0.7
    if age_hours <= 6:
        return 0.55
    if age_hours <= 24:
        return 0.35
    if age_hours <= 72:
        return 0.15
    return 0.0


def _news_breaking_score(item: dict) -> float:
    text = f"{item.get('title') or ''} {item.get('summary') or ''}".casefold()
    hits = sum(1 for term in _NEWS_BREAKING_TERMS if term.casefold() in text)
    return min(0.9, hits * 0.28)


def _news_query_type_score(item: dict) -> float:
    query_type = str(item.get("query_type") or "").lower()
    if query_type == "dynamic":
        return 0.65
    if query_type == "discovery":
        return 0.42
    if query_type == "custom":
        return 0.25
    return 0.0


def _news_pre_importance_score(item: dict) -> float:
    kind = str(item.get("kind") or "").lower()
    internal_boost = {
        "earnings": 0.45,
        "order_flow": 0.38,
        "smallcap": 0.28,
        "drift": 0.20,
        "macro": 0.18,
    }.get(kind, 0.0)
    return _news_query_type_score(item) + _news_breaking_score(item) + _news_recency_score(item) + internal_boost


def _news_importance_score(item: dict) -> float:
    impact = _safe_float(item.get("impact_score")) or 0.0
    change = abs(_safe_float(item.get("related_change_pct")) or 0.0)
    confidence = str(item.get("impact_confidence") or "").lower()
    scope = str(item.get("impact_scope") or "").lower()
    confidence_boost = {"high": 0.35, "medium": 0.18, "low": 0.05}.get(confidence, 0.0)
    scope_boost = {"market": 0.25, "stock": 0.22, "sector": 0.18, "general": 0.0}.get(scope, 0.0)
    return (
        abs(impact) * 2.6
        + min(change * 18.0, 0.9)
        + _news_query_type_score(item)
        + _news_breaking_score(item)
        + _news_recency_score(item)
        + confidence_boost
        + scope_boost
    )


def _diversify_news_items(items: list[dict], limit: int) -> list[dict]:
    if limit <= 0:
        return []
    per_bucket_limit = max(2, math.ceil(limit / 10))
    selected: list[dict] = []
    seen: set[str] = set()
    bucket_counts: dict[str, int] = {}

    def bucket_key(item: dict) -> str:
        query = str(item.get("query") or "").strip().lower()
        if query:
            return f"query:{query}"
        kind = str(item.get("kind") or "").strip().lower()
        return f"kind:{kind}" if kind else "misc"

    for item in items:
        dedupe_key = _news_dedupe_key(item)
        if dedupe_key in seen:
            continue
        bucket = bucket_key(item)
        if bucket_counts.get(bucket, 0) >= per_bucket_limit:
            continue
        selected.append(item)
        seen.add(dedupe_key)
        bucket_counts[bucket] = bucket_counts.get(bucket, 0) + 1
        if len(selected) >= limit:
            return selected

    for item in items:
        dedupe_key = _news_dedupe_key(item)
        if dedupe_key in seen:
            continue
        selected.append(item)
        seen.add(dedupe_key)
        if len(selected) >= limit:
            break
    return selected


def _news_query_type(query: str, market: str) -> str:
    if query in _dynamic_market_news_queries(market, limit=10):
        return "dynamic"
    if query in _discovery_news_queries(market):
        return "discovery"
    return "core"


def _naver_news_items_for_query(
    query: str,
    market: str,
    limit: int,
    query_index: int = 0,
    query_type: str = "core",
) -> list[dict]:
    if not _SETTINGS.naver_client_id or not _SETTINGS.naver_client_secret:
        return []
    display = max(1, min(limit * 3, 100))
    response = requests.get(
        "https://openapi.naver.com/v1/search/news.json",
        params={"query": query, "display": display, "sort": "date"},
        headers={
            "X-Naver-Client-Id": _SETTINGS.naver_client_id,
            "X-Naver-Client-Secret": _SETTINGS.naver_client_secret,
        },
        timeout=8,
    )
    response.raise_for_status()
    items = []
    for index, row in enumerate(response.json().get("items") or []):
        title = _strip_news_html(row.get("title"))
        summary = _strip_news_html(row.get("description"))
        if not _news_market_matches(title, summary, market):
            continue
        published = str(row.get("pubDate") or "")
        try:
            published = parsedate_to_datetime(published).isoformat()
        except Exception:
            pass
        items.append({
            "id": f"naver-{query_index}-{index}-{row.get('link', '')}",
            "title": title,
            "summary": summary,
            "source": "Naver News",
            "url": row.get("originallink") or row.get("link") or "",
            "naver_link": row.get("link") or "",
            "published_at": published,
            "market": market,
            "ticker": "",
            "kind": "external_news",
            "query": query,
            "query_type": query_type,
        })
        if len(items) >= limit:
            break
    return items


def _naver_news_items(query: str, market: str, limit: int) -> list[dict]:
    queries = _news_query_plan(query, market)
    if len(queries) <= 1:
        return _naver_news_items_for_query(queries[0], market, limit, query_type="custom")
    per_query_limit = max(4, min(12, (limit // len(queries)) + 3))
    buckets = [
        _naver_news_items_for_query(
            current_query,
            market,
            per_query_limit,
            query_index=index,
            query_type=_news_query_type(current_query, market),
        )
        for index, current_query in enumerate(queries)
    ]
    return _round_robin_news_buckets(buckets, limit)


_RSS_NEWS_SOURCES = [
    ("Nasdaq", "https://www.nasdaq.com/feed/rssoutbound?category=Markets"),
]

_RSS_NEWS_MAX_AGE = timedelta(days=7)

_RSS_MARKET_KEYWORDS = (
    "stock", "stocks", "share", "shares", "market", "markets", "nasdaq", "s&p", "dow",
    "wall street", "futures", "treasury", "yield", "bond", "fed", "rate", "inflation",
    "earnings", "revenue", "guidance", "analyst", "sec filing", "etf", "oil", "gold",
    "dollar", "chip", "semiconductor", "ai", "openai", "nvidia", "tesla", "apple",
)


def _xml_child_text(node: ET.Element, name: str) -> str:
    for child in list(node):
        if child.tag.split("}")[-1].lower() == name.lower():
            return str(child.text or "").strip()
    return ""


def _xml_child_attr(node: ET.Element, name: str, attr: str) -> str:
    for child in list(node):
        if child.tag.split("}")[-1].lower() == name.lower():
            value = child.attrib.get(attr)
            if value:
                return str(value).strip()
    return ""


def _rss_datetime(value: str) -> str:
    parsed = _rss_published_datetime(value)
    if parsed is not None:
        return parsed.astimezone(timezone.utc).replace(microsecond=0).isoformat()
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _rss_published_datetime(value: str) -> datetime | None:
    text = str(value or "").strip()
    if not text:
        return None
    try:
        if "T" in text:
            parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            return parsed.astimezone(timezone.utc)
    except Exception:
        pass
    try:
        parsed = parsedate_to_datetime(text)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return parsed.astimezone(timezone.utc)
    except Exception:
        return None


def _rss_news_is_fresh(published: datetime | None) -> bool:
    if published is None:
        return True
    now = datetime.now(timezone.utc)
    if published > now + timedelta(hours=6):
        return False
    return now - published <= _RSS_NEWS_MAX_AGE


def _rss_news_is_market_relevant(title: str, summary: str, link: str) -> bool:
    combined = " ".join([title, summary, link]).lower()
    return any(keyword in combined for keyword in _RSS_MARKET_KEYWORDS)


def _normalize_url_key(value: str) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    try:
        parsed = urlparse(text)
    except Exception:
        return text.lower()
    return urlunparse((
        parsed.scheme.lower(),
        parsed.netloc.lower(),
        parsed.path.rstrip("/"),
        "",
        "",
        "",
    )).lower()


def _rss_news_items_for_source(source: str, url: str, market: str, limit: int) -> list[dict]:
    response = requests.get(
        url,
        timeout=8,
        headers={"User-Agent": "Mozilla/5.0 (compatible; QubitNews/1.0)"},
    )
    response.raise_for_status()
    root = ET.fromstring(response.content)
    items: list[dict] = []
    for node in root.iter():
        if node.tag.split("}")[-1].lower() != "item":
            continue
        title = _strip_news_html(_xml_child_text(node, "title"))
        summary = _strip_news_html(_xml_child_text(node, "description"))
        link = _xml_child_text(node, "link")
        if not title or not link:
            continue
        published_raw = _xml_child_text(node, "pubDate")
        published = _rss_published_datetime(published_raw)
        if not _rss_news_is_fresh(published):
            continue
        if not _rss_news_is_market_relevant(title, summary, link):
            continue
        if not _news_market_matches(title, summary, market):
            continue
        digest = hashlib.sha1(f"{source}|{link}|{title}".encode("utf-8")).hexdigest()[:16]
        items.append({
            "id": f"rss-{digest}",
            "title": title,
            "summary": summary,
            "source": source,
            "url": link,
            "image_url": "",
            "published_at": _rss_datetime(published_raw),
            "market": "US" if market == "ALL" else market,
            "ticker": "",
            "kind": "external_news",
            "query_type": "rss_fallback",
        })
        if len(items) >= limit:
            break
    return items


def _rss_news_items(market: str, limit: int) -> tuple[list[dict], str]:
    buckets: list[list[dict]] = []
    errors: list[str] = []
    per_source_limit = max(4, min(limit, 16))
    for source, url in _RSS_NEWS_SOURCES:
        try:
            rows = _rss_news_items_for_source(source, url, market, per_source_limit)
            if rows:
                buckets.append(rows)
        except Exception as exc:
            errors.append(f"{source}: {type(exc).__name__}: {exc}")
    rounded = _round_robin_news_buckets(buckets, limit * 2)
    unique: list[dict] = []
    seen: set[str] = set()
    for item in rounded:
        key = _normalize_url_key(str(item.get("url") or "")) or str(item.get("title") or "").strip().lower()
        if not key or key in seen:
            continue
        seen.add(key)
        unique.append(item)
    unique.sort(
        key=lambda item: _rss_published_datetime(str(item.get("published_at") or "")) or datetime.min.replace(tzinfo=timezone.utc),
        reverse=True,
    )
    return unique[:limit], "; ".join(errors)


def _news_price_ticker(value: str) -> str:
    ticker = str(value or "").strip().upper()
    if not ticker:
        return ""
    code = _kr_code(ticker)
    if code and not ticker.endswith((".KS", ".KQ")):
        return f"{code}.KS"
    return ticker


_NEWS_TICKER_MENTION_TERMS: dict[str, tuple[str, ...]] = {
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
_NEWS_TICKER_DISPLAY_LABELS: dict[str, str] = {
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


def _news_ticker_terms(ticker: str) -> set[str]:
    normal = ticker.upper()
    direct_terms = {normal.casefold(), normal.replace(".KS", "").replace(".KQ", "").casefold()}
    mapped_terms = {term.casefold() for term in _NEWS_TICKER_MENTION_TERMS.get(normal, ())}
    return {term for term in direct_terms | mapped_terms if term}


def _news_ticker_mention_index(item: dict, ticker: str) -> int | None:
    text = f"{item.get('title') or ''} {item.get('summary') or ''}".casefold()
    positions = [text.find(term) for term in _news_ticker_terms(ticker)]
    positions = [position for position in positions if position >= 0]
    return min(positions) if positions else None


def _news_change_display_label(ticker: str) -> str:
    normal = ticker.upper()
    return _NEWS_TICKER_DISPLAY_LABELS.get(normal) or normal.replace(".KS", "").replace(".KQ", "")


def _news_kr_quote_horizon(row: dict) -> str:
    traded_at = pd.to_datetime(row.get("localTradedAt"), errors="coerce")
    if pd.isna(traded_at):
        return "오늘"
    traded_dt = traded_at.to_pydatetime()
    if traded_dt.tzinfo is not None:
        traded_date = traded_dt.astimezone(_KST).date()
    else:
        traded_date = traded_dt.date()
    return "오늘" if traded_date == datetime.now(_KST).date() else "최근장"


def _naver_kr_stock_quote_rows(codes: list[str]) -> list[dict]:
    clean_codes = sorted({_kr_code(code) for code in codes if _kr_code(code)})
    if not clean_codes:
        return []
    headers = {
        "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X)",
        "Referer": "https://m.stock.naver.com/",
    }
    rows: list[dict] = []
    for offset in range(0, len(clean_codes), 50):
        chunk = clean_codes[offset:offset + 50]

        def _load(chunk=chunk):
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

        rows.extend(_cached(f"naver_kr_stock_quotes_{','.join(chunk)}", _load, ttl=60))
    return rows


def _naver_kr_stock_change_batch(tickers: list[str]) -> dict[str, tuple[float | None, str]]:
    requested_by_code: dict[str, set[str]] = {}
    for ticker in tickers:
        normal = str(ticker or "").strip().upper()
        code = _kr_code(normal)
        if not code:
            continue
        requested_by_code.setdefault(code, set()).add(normal)
    if not requested_by_code:
        return {}

    changes: dict[str, tuple[float | None, str]] = {}
    for row in _naver_kr_stock_quote_rows(list(requested_by_code)):
        code = _kr_code(row.get("itemCode") or row.get("symbolCode"))
        if not code:
            continue
        change_pct_percent = _naver_indicator_float(
            row.get("fluctuationsRatioRaw") or row.get("fluctuationsRatio")
        )
        if change_pct_percent is None:
            close = _naver_indicator_float(row.get("closePriceRaw") or row.get("closePrice"))
            change_abs = _naver_indicator_float(
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
        horizon = _news_kr_quote_horizon(row)
        parsed = (change_pct_percent / 100, horizon)
        for requested in requested_by_code.get(code, set()):
            changes[requested] = parsed
        changes[f"{code}{suffix}"] = parsed
    return changes


def _naver_kr_stock_price_batch(tickers: list[str]) -> dict[str, dict]:
    requested_by_code: dict[str, set[str]] = {}
    for ticker in tickers:
        normal = str(ticker or "").strip().upper()
        code = _kr_code(normal)
        if not code:
            continue
        requested_by_code.setdefault(code, set()).add(normal)
    if not requested_by_code:
        return {}

    quotes: dict[str, dict] = {}
    for row in _naver_kr_stock_quote_rows(list(requested_by_code)):
        code = _kr_code(row.get("itemCode") or row.get("symbolCode"))
        if not code:
            continue
        close = _naver_indicator_float(row.get("closePriceRaw") or row.get("closePrice"))
        change_pct_percent = _naver_indicator_float(
            row.get("fluctuationsRatioRaw") or row.get("fluctuationsRatio")
        )
        if change_pct_percent is None:
            change_abs = _naver_indicator_float(
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
            "daily_change_horizon": _news_kr_quote_horizon(row),
            "updated_at": str(row.get("localTradedAt") or "").strip() or None,
            "source": "naver_realtime",
        }
        for requested in requested_by_code.get(code, set()):
            quotes[requested] = payload
        quotes[code] = payload
        quotes[f"{code}{suffix}"] = payload
    return quotes


def _news_change_candidates(item: dict) -> list[tuple[str, str]]:
    raw_values: list[tuple[str, bool]] = []
    raw_values.append((str(item.get("ticker") or ""), True))
    raw_values.extend((str(value or ""), False) for value in item.get("related_tickers") or [])

    candidates: list[tuple[int, int, int, str, str]] = []
    seen: set[str] = set()
    for order, (value, is_primary) in enumerate(raw_values):
        ticker = _news_price_ticker(value)
        if not ticker or ticker in seen:
            continue
        mention_index = _news_ticker_mention_index(item, ticker)
        if not is_primary and mention_index is None:
            continue
        seen.add(ticker)
        candidates.append((
            0 if is_primary else 1,
            mention_index if mention_index is not None else 999_999,
            order,
            _infer_market_from_ticker(ticker),
            ticker,
        ))
    candidates.sort()
    return [(market, ticker) for _, _, _, market, ticker in candidates[:5]]


def _news_market_change_symbol(item: dict, safe_market: str) -> str:
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


def _news_default_market_change(item: dict, safe_market: str) -> tuple[str, float] | None:
    try:
        payload = _market_indicators_payload(category="index_fx", refresh=False)
    except Exception:
        return None
    symbol = _news_market_change_symbol(item, safe_market)
    for quote in payload.get("items") or []:
        if str(quote.get("symbol") or "").upper() != symbol:
            continue
        change_pct = _safe_float(quote.get("change_pct"))
        if change_pct is None:
            return None
        label = str(quote.get("label") or symbol)
        return label, change_pct
    return None


def _news_should_use_market_change(item: dict, candidates: list[tuple[str, str]]) -> bool:
    if not candidates:
        return True
    return str(item.get("impact_scope") or "").strip().lower() != "stock"


def _enrich_news_change_fields(items: list[dict], safe_market: str) -> list[dict]:
    candidates_by_item = [(item, _news_change_candidates(item)) for item in items]
    tickers_by_market: dict[str, list[str]] = {}
    for _, candidates in candidates_by_item:
        for market, ticker in candidates:
            tickers_by_market.setdefault(market, []).append(ticker)

    snapshots_by_market: dict[str, dict[str, dict]] = {}
    daily_change_by_market: dict[str, dict[str, tuple[float | None, str]]] = {}
    for market, tickers in tickers_by_market.items():
        if market == "KR":
            naver_changes = _naver_kr_stock_change_batch(tickers) if _allow_api_external_fetch() else {}
            missing = [
                str(ticker or "").strip().upper()
                for ticker in tickers
                if str(ticker or "").strip().upper() not in naver_changes
            ]
            if missing:
                snapshots = _portfolio_price_snapshot_batch(missing, market)
                snapshots_by_market[market] = snapshots
                storage_changes = _portfolio_daily_change_batch(missing, market, snapshots)
            else:
                snapshots_by_market[market] = {}
                storage_changes = {}
            daily_change_by_market[market] = {**storage_changes, **naver_changes}
            continue

        snapshots = _portfolio_price_snapshot_batch(tickers, market)
        snapshots_by_market[market] = snapshots
        daily_change_by_market[market] = _portfolio_daily_change_batch(tickers, market, snapshots)

    enriched: list[dict] = []
    for item, candidates in candidates_by_item:
        updated = dict(item)
        matched = False
        for market, ticker in candidates:
            key = ticker.upper()
            daily_change, horizon = daily_change_by_market.get(market, {}).get(key, (None, ""))
            if daily_change is None:
                continue
            updated["related_change_label"] = _news_change_display_label(ticker)
            updated["related_change_pct"] = daily_change
            updated["related_change_horizon"] = horizon or "전장"
            matched = True
            break

        if not matched and _news_should_use_market_change(item, candidates):
            market_change = _news_default_market_change(item, safe_market)
            if market_change:
                label, change_pct = market_change
                updated["related_change_label"] = label
                updated["related_change_pct"] = change_pct
                updated["related_change_horizon"] = "오늘"
        enriched.append(updated)
    return enriched


def _news_public_market_label(value: str, fallback: str = "ALL") -> str:
    market = str(value or fallback or "ALL").strip().upper()
    if market == "US":
        return "미국"
    if market == "KR":
        return "국내"
    if market == "GLOBAL":
        return "글로벌"
    return "시장"


def _news_public_impact_label(item: dict) -> str:
    label_ko = str(item.get("impact_label_ko") or "").strip()
    if label_ko:
        return label_ko
    label = str(item.get("impact_label") or "").strip().lower()
    if label == "positive":
        return "긍정"
    if label == "negative":
        return "부정"
    return "중립"


def _news_public_subject(item: dict, safe_market: str) -> str:
    related_tickers = [
        str(ticker or "").strip().upper()
        for ticker in (item.get("related_tickers") or [])
        if str(ticker or "").strip()
    ]
    ticker = str(item.get("ticker") or "").strip().upper()
    if ticker:
        related_tickers.insert(0, ticker)
    if related_tickers:
        return _news_change_display_label(related_tickers[0])

    for keyword in item.get("related_keywords") or []:
        keyword_text = str(keyword or "").strip()
        if keyword_text:
            return keyword_text

    return _news_public_market_label(str(item.get("market") or ""), safe_market)


def _news_public_title(item: dict, safe_market: str) -> str:
    impact = _news_public_impact_label(item)
    scope = str(item.get("impact_scope") or "").strip().lower()
    subject = _news_public_subject(item, safe_market)
    market = _news_public_market_label(str(item.get("market") or ""), safe_market)
    if scope == "stock":
        return f"{subject} {impact} 뉴스"
    if scope == "sector":
        return f"{subject} 관련 섹터 {impact} 뉴스"
    if scope == "market":
        return f"{market} 시장 {impact} 뉴스"
    return f"{market} 주요 {impact} 뉴스"


def _news_public_item(item: dict, safe_market: str) -> dict:
    """Return only link metadata and Qubit-owned analysis, not publisher body assets."""
    output = dict(item)
    output["title"] = _news_public_title(item, safe_market)
    output["summary"] = ""
    output["image_url"] = ""
    output["urlToImage"] = ""
    output["image"] = ""
    output["thumbnail"] = ""
    output["thumbnail_url"] = ""
    output["content_mode"] = "link_analysis"
    output.pop("naver_link", None)
    return output


def _news_public_items(items: list[dict], safe_market: str) -> list[dict]:
    return [_news_public_item(item, safe_market) for item in items]


def _news_payload(q: str = "", market: str = "ALL", limit: int = 30) -> dict:
    safe_market = str(market or "ALL").upper()
    if safe_market not in {"ALL", "US", "KR"}:
        safe_market = "ALL"
    safe_limit = max(1, min(int(limit or 30), 100))
    query = str(q or "").strip() or _default_news_query(safe_market)

    external_items: list[dict] = []
    external_error = ""
    try:
        external_items = _naver_news_items(query, safe_market, safe_limit)
    except Exception as exc:
        external_error = f"{type(exc).__name__}: {exc}"

    rss_items: list[dict] = []
    rss_error = ""
    if len(external_items) < safe_limit:
        rss_items, rss_error = _rss_news_items(safe_market, safe_limit)
        if rss_error:
            external_error = "; ".join(part for part in [external_error, rss_error] if part)

    article_items = [
        item for item in (external_items + rss_items)
        if str(item.get("kind") or "").strip().lower() == "external_news"
        and str(item.get("url") or "").strip()
    ]
    raw_candidates = _diversify_news_items(
        sorted(article_items, key=_news_pre_importance_score, reverse=True),
        max(safe_limit * 2, 40),
    )
    combined = _enrich_news_change_fields(_enrich_news_items(raw_candidates, safe_market), safe_market)
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
    combined = _diversify_news_items(
        sorted(combined, key=_news_importance_score, reverse=True),
        safe_limit,
    )
    combined = _news_public_items(combined, safe_market)
    return {
        "configured": bool(_SETTINGS.naver_client_id and _SETTINGS.naver_client_secret),
        "query": query,
        "queries": _news_query_plan(query, safe_market),
        "market": safe_market,
        "count": len(combined),
        "external_count": len(article_items),
        "rss_count": len(rss_items),
        "internal_count": 0,
        "external_error": external_error,
        "items": combined,
    }


def _signal_events_payload(
    market: str = "ALL",
    tickers: str = "",
    kinds: str = "",
    limit: int = 100,
) -> dict:
    safe_market = str(market or "ALL").upper()
    if safe_market not in {"ALL", "US", "KR", "GLOBAL"}:
        safe_market = "ALL"
    requested_tickers = [
        ticker.strip().upper()
        for ticker in str(tickers or "").split(",")
        if ticker.strip()
    ]
    requested_kinds = [
        kind.strip()
        for kind in str(kinds or "").split(",")
        if kind.strip()
    ]
    safe_limit = max(1, min(int(limit or 100), 500))
    df = _repository().read_signal_events(
        market=None if safe_market == "ALL" else safe_market,
        tickers=requested_tickers,
        kinds=requested_kinds,
        limit=safe_limit,
    )
    return {
        "market": safe_market,
        "count": int(len(df)),
        "generated_at": _utc_now_iso(),
        "items": _df_to_records(df) if not df.empty else [],
    }


def _comparison_currency(ticker: str, market: str | None) -> str:
    return "KRW" if str(market or "").upper() == "KR" or _kr_code(ticker) else "USD"


_SECTOR_THEME_ORDER = [
    "AI 칩/GPU",
    "AI 서버/네트워크",
    "AI 데이터센터/클라우드",
    "AI 소프트웨어",
    "AI 전력/냉각",
    "SMR",
    "원자력",
    "HBM",
    "메모리/낸드",
    "파운드리",
    "반도체 설계",
    "CPU/엣지칩",
    "반도체 소재",
    "전자/부품",
    "반도체 장비",
    "반도체 후공정/테스트",
    "클라우드/SW",
    "IT 서비스",
    "사이버보안",
    "보안/서비스",
    "핀테크/결제",
    "은행",
    "증권/자산운용",
    "보험",
    "전기차",
    "자동차",
    "자동차 부품",
    "배터리",
    "배터리 소재",
    "조선",
    "방산/항공",
    "기계/로봇",
    "헬스케어",
    "바이오/제약",
    "의료기기",
    "헬스케어 서비스",
    "에너지",
    "정유/화학",
    "전력/유틸리티",
    "클린에너지",
    "소비/리테일",
    "이커머스",
    "음식료/필수소비",
    "화장품/뷰티",
    "미디어/엔터",
    "게임",
    "여행/레저",
    "통신",
    "리츠/부동산",
    "건설/인프라",
    "소재/철강",
    "금속",
    "비금속",
    "운송/물류",
    "기타",
]

_SECTOR_THEME_OVERRIDES = {
    # KR exchange sectors are often broad. These overrides keep visible theme pages
    # aligned with how investors naturally group the companies.
    "196170": "바이오/제약",  # 알테오젠
    "310210": "바이오/제약",  # 보로노이
    "226950": "바이오/제약",  # 올릭스
    "347850": "바이오/제약",  # 디앤디파마텍
    "140410": "바이오/제약",  # 메지온
    "476830": "바이오/제약",  # 알지노믹스
    "115180": "바이오/제약",  # 큐리언트
    "358570": "바이오/제약",  # 지아이이노베이션
    "476060": "바이오/제약",  # 온코닉테라퓨틱스
    "372320": "바이오/제약",  # 큐로셀
    "389470": "바이오/제약",  # 인벤티지랩
    "199800": "바이오/제약",  # 툴젠
    "214450": "바이오/제약",  # 파마리서치
    "CRMD": "바이오/제약",
    "DCTH": "바이오/제약",
    "ORGO": "바이오/제약",
    "VRTX": "바이오/제약",
    "REGN": "바이오/제약",
    "ALNY": "바이오/제약",
    "RIGL": "바이오/제약",
    "BDX": "의료기기",
    "WST": "의료기기",
    "458870": "의료기기",
    "336570": "의료기기",
    "445680": "의료기기",
    "131970": "반도체 후공정/테스트",  # 두산테스나
    "067310": "반도체 후공정/테스트",  # 하나마이크론
    "490470": "반도체 설계",  # 세미파이브
    "420770": "반도체 장비",  # 기가비스
    "388210": "반도체 장비",  # 씨엠티엑스
    "064760": "반도체 장비",  # 티씨케이
    "074600": "반도체 장비",  # 원익QnC
    "012450": "방산/항공",
    "047810": "방산/항공",
    "064350": "방산/항공",
    "099320": "방산/항공",
    "272210": "방산/항공",
    "489790": "방산/항공",
    "079550": "방산/항공",
    "003570": "방산/항공",
    "329180": "조선",
    "010140": "조선",
    "042660": "조선",
    "009540": "조선",
    "443060": "조선",
    "439260": "조선",
    "460930": "조선",
    "101930": "조선",
    "012330": "자동차 부품",
    "204320": "자동차 부품",
    "011210": "자동차 부품",
    "005850": "자동차 부품",
    "015750": "자동차 부품",
    "437730": "자동차 부품",
    "448900": "자동차 부품",
    "125490": "자동차 부품",
    "005380": "자동차",
    "000270": "자동차",
    "011070": "전자/부품",
    "009150": "전자/부품",
    "353200": "전자/부품",
    "007810": "전자/부품",
    "066570": "전자/부품",
    "034220": "전자/부품",
    "043260": "전자/부품",
    "082920": "전자/부품",
    "213420": "전자/부품",
    "204270": "전자/부품",
    "046890": "전자/부품",
    "024850": "전자/부품",
    "088130": "전자/부품",
    "000150": "AI 전력/냉각",
    "000500": "AI 전력/냉각",
    "006340": "AI 전력/냉각",
    "058470": "반도체 후공정/테스트",
    "440110": "반도체 설계",
    "222800": "AI 서버/네트워크",
    "101490": "반도체 장비",
    "080220": "반도체 설계",
    "189300": "AI 서버/네트워크",
    "195870": "반도체 후공정/테스트",
    "032500": "AI 서버/네트워크",
    "050890": "AI 서버/네트워크",
    "138080": "AI 서버/네트워크",
    "036810": "반도체 장비",
    "425420": "반도체 후공정/테스트",
    "356860": "AI 서버/네트워크",
    "077360": "반도체 소재",
    "059090": "반도체 소재",
    "252990": "반도체 소재",
    "399720": "반도체 설계",
    "033640": "반도체 후공정/테스트",
    "200710": "반도체 설계",
    "033160": "반도체 소재",
    "018260": "IT 서비스",
    "307950": "IT 서비스",
    "064400": "IT 서비스",
    "012510": "IT 서비스",
    "023590": "IT 서비스",
    "181710": "IT 서비스",
    "053800": "사이버보안",
    "042000": "이커머스",
    "022100": "IT 서비스",
    "124500": "IT 서비스",
    "127120": "IT 서비스",
    "012750": "보안/서비스",
    "036830": "정유/화학",
    "300080": "AI 소프트웨어",
    "BZAI": "AI 칩/GPU",
    "NVEC": "반도체 설계",
    "052690": "전력/유틸리티",
    "051600": "전력/유틸리티",
    "015760": "전력/유틸리티",
    "036460": "전력/유틸리티",
    "267260": "AI 전력/냉각",
    "010120": "AI 전력/냉각",
    "298040": "AI 전력/냉각",
    "103590": "AI 전력/냉각",
    "062040": "AI 전력/냉각",
    "033100": "AI 전력/냉각",
    "001440": "AI 전력/냉각",
    "147830": "AI 전력/냉각",
    "065710": "AI 전력/냉각",
    "006910": "AI 전력/냉각",
    "032820": "AI 전력/냉각",
    "034020": "AI 전력/냉각",
    "178320": "AI 서버/네트워크",
    "007660": "AI 서버/네트워크",
    "218410": "AI 서버/네트워크",
    "267320": "AI 서버/네트워크",
    "003670": "배터리 소재",
    "247540": "배터리 소재",
    "066970": "배터리 소재",
    "278280": "배터리 소재",
    "078600": "배터리 소재",
    "450080": "배터리 소재",
    "361610": "배터리 소재",
    "020150": "배터리 소재",
    "005070": "배터리 소재",
    "348370": "배터리 소재",
    "365340": "배터리 소재",
    "032350": "여행/레저",
    "006730": "여행/레저",
    "025980": "여행/레저",
    "035250": "여행/레저",
    "030000": "미디어/엔터",
    "035760": "미디어/엔터",
    "253450": "미디어/엔터",
    "021240": "소비/리테일",
    "052400": "핀테크/결제",
    "204620": "여행/레저",
    "950170": "소비/리테일",
    "278470": "화장품/뷰티",
    "090430": "화장품/뷰티",
    "051900": "화장품/뷰티",
    "192820": "화장품/뷰티",
    "161890": "화장품/뷰티",
    "483650": "화장품/뷰티",
    "123330": "화장품/뷰티",
    "214150": "의료기기",
    "PSA": "리츠/부동산",
    "EXR": "리츠/부동산",
    "LVS": "여행/레저",
    "MAR": "여행/레저",
    "ADP": "IT 서비스",
    "ADSK": "클라우드/SW",
    "ROK": "기계/로봇",
    "WTW": "보험",
    "FISV": "핀테크/결제",
    "PAYS": "핀테크/결제",
    "EVER": "보험",
    "TLS": "사이버보안",
    "GOGO": "통신",
    "CURI": "미디어/엔터",
    "GAMB": "게임",
    "462870": "게임",
    "MAMA": "음식료/필수소비",
    "IDR": "소재/철강",
    "EPSN": "에너지",
    "PROP": "에너지",
    "ELA": "소비/리테일",
}

_SECTOR_THEME_RULES = [
    {
        "label": "AI 칩/GPU",
        "tickers": {"NVDA", "AMD", "AVGO", "MRVL", "ARM", "QCOM", "BZAI"},
        "terms": ["ai accelerator", "gpu", "npu", "ai chip", "ai semiconductor", "인공지능 반도체", "ai 반도체", "가속기"],
    },
    {
        "label": "AI 서버/네트워크",
        "tickers": {"ANET", "SMCI", "DELL", "HPE", "CSCO", "APH", "CIEN", "COHR", "LITE", "JBL"},
        "terms": ["ai server", "server rack", "ethernet", "network switch", "optical module", "ai 서버", "서버", "네트워크 장비", "광모듈"],
    },
    {
        "label": "AI 데이터센터/클라우드",
        "tickers": {"MSFT", "GOOGL", "GOOG", "AMZN", "META", "ORCL", "EQIX", "DLR"},
        "terms": ["data center", "datacenter", "cloud infrastructure", "hyperscale", "ai infrastructure", "데이터센터", "클라우드 인프라", "하이퍼스케일"],
    },
    {
        "label": "AI 소프트웨어",
        "tickers": {"PLTR", "SNOW", "CRM", "NOW", "ADBE", "DDOG", "NET", "AI", "PATH"},
        "terms": ["ai software", "generative ai", "data platform", "analytics platform", "ai agent", "생성형 ai", "ai 소프트웨어", "데이터 플랫폼"],
    },
    {
        "label": "AI 전력/냉각",
        "tickers": {"VRT", "ETN", "PWR", "GEV", "CEG", "VST", "NEE", "FIX", "TT", "CARR", "JCI"},
        "terms": ["power grid", "grid equipment", "cooling", "thermal management", "electrical equipment", "전력망", "냉각", "변압기", "전력기기"],
    },
    {
        "label": "SMR",
        "tickers": {"SMR", "OKLO", "NNE", "BWXT", "LEU", "034020", "052690", "032820", "083650", "094820", "105840"},
        "terms": ["small modular reactor", "smr", "microreactor", "advanced reactor", "소형모듈원전", "소형 원전", "차세대 원전", "마이크로 원자로"],
    },
    {
        "label": "원자력",
        "tickers": {"CEG", "VST", "CCJ", "LEU", "BWXT", "SMR", "OKLO", "NNE", "NXE", "DNN", "UUUU", "034020", "052690", "015760", "051600", "032820", "083650", "094820", "105840"},
        "terms": ["nuclear", "uranium", "reactor", "atomic energy", "원자력", "우라늄", "원자로", "핵연료"],
    },
    {
        "label": "HBM",
        "tickers": {"000660", "005930", "MU", "042700"},
        "terms": ["hbm", "high bandwidth memory", "고대역폭 메모리"],
    },
    {
        "label": "메모리/낸드",
        "tickers": {"WDC", "STX", "SNDK", "000660", "005930", "MU"},
        "terms": ["memory semiconductor", "dram", "nand", "ssd", "data storage", "hard disk", "메모리", "낸드", "스토리지 반도체"],
    },
    {
        "label": "파운드리",
        "tickers": {"TSM", "GFS", "UMC", "TSEM", "INTC", "005930", "000990"},
        "terms": ["foundry", "파운드리", "fab"],
    },
    {
        "label": "반도체 설계",
        "tickers": {"AMD", "QCOM", "ARM", "MRVL", "MPWR", "MCHP", "ADI", "TXN", "LSCC"},
        "terms": ["fabless", "chip design", "반도체 설계", "팹리스", "analog semiconductor", "아날로그 반도체"],
    },
    {
        "label": "CPU/엣지칩",
        "tickers": {"AMD", "INTC", "ARM", "QCOM", "AAPL", "NXPI", "ON"},
        "terms": ["cpu", "processor", "edge chip", "edge ai", "프로세서", "x86", "엣지칩"],
    },
    {
        "label": "반도체 장비",
        "tickers": {"ASML", "AMAT", "LRCX", "KLAC", "TER", "ONTO", "042700", "036930", "240810"},
        "terms": ["semiconductor equipment", "반도체 장비", "lithography", "노광", "wafer", "웨이퍼"],
    },
    {
        "label": "반도체 후공정/테스트",
        "tickers": {"131970", "067310", "036540", "166090", "GST", "TER"},
        "terms": ["semiconductor test", "chip test", "semiconductor packaging", "advanced packaging", "osat", "후공정", "반도체 테스트", "반도체 패키징"],
    },
    {
        "label": "사이버보안",
        "tickers": {"PANW", "CRWD", "ZS", "FTNT", "OKTA", "CYBR", "S"},
        "terms": ["cybersecurity", "security software", "zero trust", "사이버보안", "보안"],
    },
    {
        "label": "핀테크/결제",
        "tickers": {"V", "MA", "PYPL", "SQ", "AXP", "COF", "035720"},
        "terms": ["payment", "fintech", "card network", "결제", "핀테크", "카드"],
    },
    {
        "label": "은행",
        "tickers": {"JPM", "BAC", "WFC", "C", "USB", "PNC", "105560", "055550", "086790", "316140"},
        "terms": ["bank", "은행", "금융지주"],
    },
    {
        "label": "증권/자산운용",
        "tickers": {"GS", "MS", "SCHW", "IBKR", "BLK", "BX", "KKR", "006800", "039490", "071050"},
        "terms": ["asset management", "brokerage", "investment bank", "증권", "자산운용", "투자은행"],
    },
    {
        "label": "보험",
        "tickers": {"AIG", "MET", "PRU", "AFL", "TRV", "CB", "032830", "005830", "000810"},
        "terms": ["insurance", "insurer", "보험", "손해보험", "생명보험"],
    },
    {
        "label": "전기차",
        "tickers": {"TSLA", "RIVN", "LCID", "005380", "000270"},
        "terms": ["electric vehicle", "ev platform", "전기차"],
    },
    {
        "label": "자동차",
        "tickers": {"GM", "F", "TM", "005380", "000270"},
        "terms": ["automotive", "vehicle maker", "automaker", "자동차", "완성차", "모빌리티"],
    },
    {
        "label": "자동차 부품",
        "tickers": {"APTV", "BWA", "MGA", "012330", "011070", "018260", "204320"},
        "terms": ["auto parts", "mobility parts", "automotive parts", "자동차 부품", "차량 부품", "전장"],
    },
    {
        "label": "배터리",
        "tickers": {"373220", "006400", "QS", "PCRFY"},
        "terms": ["battery", "배터리", "2차전지", "양극재", "음극재"],
    },
    {
        "label": "배터리 소재",
        "tickers": {"ALB", "SQM", "051910", "096770", "003670", "247540", "066970", "278280"},
        "terms": ["lithium", "cathode", "anode", "battery material", "리튬", "양극재", "음극재", "분리막", "배터리 소재"],
    },
    {
        "label": "조선",
        "tickers": {"009540", "010140", "329180", "042660", "010620"},
        "terms": ["shipbuilding", "shipyard", "조선", "선박", "lng선"],
    },
    {
        "label": "방산/항공",
        "tickers": {"BA", "RTX", "LMT", "NOC", "GD", "HWM", "012450", "047810", "064350", "079550"},
        "terms": ["defense", "aerospace", "aircraft", "방산", "항공", "우주항공"],
    },
    {
        "label": "기계/로봇",
        "tickers": {"CAT", "DE", "HON", "GE", "ISRG", "TER", "277810", "454910", "034020"},
        "terms": ["machinery", "robot", "automation", "기계", "로봇", "자동화"],
    },
    {
        "label": "클라우드/SW",
        "tickers": {"CRM", "ADBE", "NOW", "SNOW", "WDAY", "INTU", "SHOP", "TEAM", "MDB", "NET", "DDOG"},
        "terms": ["software", "cloud", "saas", "소프트웨어", "클라우드", "saas"],
    },
    {
        "label": "바이오/제약",
        "tickers": {"LLY", "NVO", "JNJ", "MRK", "PFE", "ABBV", "AMGN", "GILD", "REGN", "BMY", "MRNA", "068270", "207940", "128940"},
        "terms": ["biotech", "pharma", "drug", "바이오", "제약", "신약"],
    },
    {
        "label": "의료기기",
        "tickers": {"TMO", "DHR", "ABT", "MDT", "SYK", "ISRG", "BSX", "EW", "214150"},
        "terms": ["medical device", "diagnostics", "life science tools", "의료기기", "진단"],
    },
    {
        "label": "헬스케어 서비스",
        "tickers": {"UNH", "ELV", "HUM", "CI", "CVS"},
        "terms": ["managed care", "health insurance", "healthcare service", "의료 서비스", "건강보험"],
    },
    {
        "label": "헬스케어",
        "tickers": {"LLY", "NVO", "UNH", "JNJ", "MRK", "PFE", "ABBV", "TMO", "DHR", "068270", "207940"},
        "terms": ["health", "biotech", "pharma", "drug", "헬스케어", "바이오", "제약", "의료"],
    },
    {
        "label": "에너지",
        "tickers": {"XOM", "CVX", "COP", "SLB", "EOG", "OXY"},
        "terms": ["oil & gas", "natural gas", "energy production", "oilfield", "에너지", "원유", "천연가스", "정유"],
    },
    {
        "label": "정유/화학",
        "tickers": {"DOW", "DD", "LYB", "010950", "011170", "051910", "096770"},
        "terms": ["chemical", "refining", "petrochemical", "화학", "정유", "석유화학"],
    },
    {
        "label": "전력/유틸리티",
        "tickers": {"NEE", "DUK", "SO", "AEP", "EXC", "PEG", "CEG", "015760"},
        "terms": ["utility", "electricity", "power grid", "전력", "유틸리티", "전력망"],
    },
    {
        "label": "클린에너지",
        "tickers": {"ENPH", "FSLR", "SEDG", "BEP", "336260", "112610"},
        "terms": ["clean energy", "solar", "renewable", "클린에너지", "태양광", "재생에너지"],
    },
    {
        "label": "이커머스",
        "tickers": {"AMZN", "SHOP", "MELI", "BABA", "EBAY", "CPNG", "035420", "035720"},
        "terms": ["e-commerce", "ecommerce", "online commerce", "이커머스", "온라인 쇼핑"],
    },
    {
        "label": "소비/리테일",
        "tickers": {"COST", "WMT", "HD", "LOW", "TGT", "TJX", "MCD", "NKE", "SBUX", "PG", "KO", "PEP"},
        "terms": ["retail", "consumer", "restaurant", "apparel", "소비", "리테일", "유통", "의류", "음식료"],
    },
    {
        "label": "화장품/뷰티",
        "tickers": {"278470", "090430", "051900", "192820", "161890", "483650", "123330", "241710"},
        "terms": ["cosmetics", "beauty", "skincare", "화장품", "뷰티", "스킨케어"],
    },
    {
        "label": "음식료/필수소비",
        "tickers": {"PG", "KO", "PEP", "PM", "MO", "MDLZ", "097950", "271560", "004370"},
        "terms": ["consumer staples", "food", "beverage", "필수소비", "음식료", "식품"],
    },
    {
        "label": "미디어/엔터",
        "tickers": {"NFLX", "DIS", "WBD", "PARA", "035720", "352820", "041510", "035900", "122870"},
        "terms": ["media", "entertainment", "streaming", "미디어", "엔터", "콘텐츠", "스트리밍"],
    },
    {
        "label": "게임",
        "tickers": {"RBLX", "EA", "TTWO", "036570", "259960", "251270", "293490"},
        "terms": ["game", "gaming", "게임"],
    },
    {
        "label": "여행/레저",
        "tickers": {"BKNG", "ABNB", "MAR", "HLT", "DAL", "UAL", "CCL", "NCLH", "034230", "008770"},
        "terms": ["travel", "hotel", "leisure", "airline", "여행", "레저", "호텔", "항공"],
    },
    {
        "label": "통신",
        "tickers": {"T", "VZ", "TMUS", "CHTR", "CMCSA", "017670", "030200", "032640"},
        "terms": ["telecom", "communication services", "wireless", "통신", "5g"],
    },
    {
        "label": "리츠/부동산",
        "tickers": {"PLD", "AMT", "EQIX", "DLR", "O", "SPG", "PSA", "330590", "088260"},
        "terms": ["reit", "real estate", "property", "리츠", "부동산"],
    },
    {
        "label": "건설/인프라",
        "tickers": {"VMC", "MLM", "URI", "000720", "006360", "028050", "047040"},
        "terms": ["construction", "infrastructure", "building materials", "건설", "인프라", "시멘트"],
    },
    {
        "label": "소재/철강",
        "tickers": {"NUE", "STLD", "FCX", "SCCO", "BHP", "RIO", "005490", "010130", "004020"},
        "terms": ["materials", "steel", "copper", "mining", "소재", "철강", "구리", "광산"],
    },
    {
        "label": "운송/물류",
        "tickers": {"UPS", "FDX", "UNP", "CSX", "NSC", "000120", "003490", "011200", "086280"},
        "terms": ["transport", "logistics", "railroad", "shipping", "운송", "물류", "해운"],
    },
]

_SECTOR_THEME_SEED_GROUPS: dict[str, list[tuple[str, str, str, str]]] = {
    "AI 칩/GPU": [
        ("NVDA", "NVIDIA", "US", "AI Accelerator"),
        ("AMD", "Advanced Micro Devices", "US", "AI Accelerator"),
        ("AVGO", "Broadcom", "US", "AI Accelerator"),
        ("MRVL", "Marvell Technology", "US", "AI Accelerator"),
        ("ARM", "Arm Holdings", "US", "AI Chip"),
        ("QCOM", "Qualcomm", "US", "AI Edge Chip"),
    ],
    "AI 서버/네트워크": [
        ("ANET", "Arista Networks", "US", "AI Networking"),
        ("SMCI", "Super Micro Computer", "US", "AI Server"),
        ("DELL", "Dell Technologies", "US", "AI Server"),
        ("HPE", "Hewlett Packard Enterprise", "US", "AI Server"),
        ("CSCO", "Cisco Systems", "US", "AI Networking"),
    ],
    "AI 데이터센터/클라우드": [
        ("MSFT", "Microsoft", "US", "Cloud Infrastructure"),
        ("GOOGL", "Alphabet", "US", "Cloud Infrastructure"),
        ("AMZN", "Amazon", "US", "Cloud Infrastructure"),
        ("META", "Meta Platforms", "US", "Cloud Infrastructure"),
        ("ORCL", "Oracle", "US", "Cloud Infrastructure"),
        ("EQIX", "Equinix", "US", "Data Center REIT"),
        ("DLR", "Digital Realty", "US", "Data Center REIT"),
    ],
    "AI 소프트웨어": [
        ("PLTR", "Palantir", "US", "AI Software"),
        ("SNOW", "Snowflake", "US", "Data Platform"),
        ("CRM", "Salesforce", "US", "AI Software"),
        ("NOW", "ServiceNow", "US", "AI Software"),
        ("ADBE", "Adobe", "US", "AI Software"),
        ("DDOG", "Datadog", "US", "Data Platform"),
        ("NET", "Cloudflare", "US", "AI Infrastructure Software"),
    ],
    "AI 전력/냉각": [
        ("VRT", "Vertiv", "US", "AI Power Cooling"),
        ("ETN", "Eaton", "US", "Power Equipment"),
        ("PWR", "Quanta Services", "US", "Grid Infrastructure"),
        ("GEV", "GE Vernova", "US", "Grid Infrastructure"),
        ("CEG", "Constellation Energy", "US", "AI Power"),
        ("VST", "Vistra", "US", "AI Power"),
        ("267260", "HD현대일렉트릭", "KR", "전력기기"),
        ("010120", "LS ELECTRIC", "KR", "전력기기"),
        ("298040", "효성중공업", "KR", "전력기기"),
        ("103590", "일진전기", "KR", "전력기기"),
        ("062040", "산일전기", "KR", "전력기기"),
    ],
    "SMR": [
        ("SMR", "NuScale Power", "US", "Small Modular Reactor"),
        ("OKLO", "Oklo", "US", "Advanced Reactor"),
        ("NNE", "Nano Nuclear Energy", "US", "Microreactor"),
        ("BWXT", "BWX Technologies", "US", "Nuclear Components"),
        ("LEU", "Centrus Energy", "US", "Nuclear Fuel"),
        ("034020", "두산에너빌리티", "KR", "SMR 기자재"),
        ("052690", "한전기술", "KR", "원전 설계"),
        ("032820", "우리기술", "KR", "원전 제어"),
        ("083650", "비에이치아이", "KR", "원전 기자재"),
        ("094820", "일진파워", "KR", "원전 정비"),
        ("105840", "우진", "KR", "원전 계측"),
    ],
    "원자력": [
        ("CEG", "Constellation Energy", "US", "Nuclear Utility"),
        ("VST", "Vistra", "US", "Nuclear Power"),
        ("CCJ", "Cameco", "US", "Uranium"),
        ("LEU", "Centrus Energy", "US", "Nuclear Fuel"),
        ("BWXT", "BWX Technologies", "US", "Nuclear Components"),
        ("SMR", "NuScale Power", "US", "Small Modular Reactor"),
        ("OKLO", "Oklo", "US", "Advanced Reactor"),
        ("NNE", "Nano Nuclear Energy", "US", "Microreactor"),
        ("NXE", "NexGen Energy", "US", "Uranium"),
        ("DNN", "Denison Mines", "US", "Uranium"),
        ("UUUU", "Energy Fuels", "US", "Uranium"),
        ("034020", "두산에너빌리티", "KR", "원전 기자재"),
        ("052690", "한전기술", "KR", "원전 설계"),
        ("015760", "한국전력", "KR", "전력/원전"),
        ("051600", "한전KPS", "KR", "원전 정비"),
        ("032820", "우리기술", "KR", "원전 제어"),
        ("083650", "비에이치아이", "KR", "원전 기자재"),
        ("094820", "일진파워", "KR", "원전 정비"),
        ("105840", "우진", "KR", "원전 계측"),
    ],
    "HBM": [
        ("000660", "SK하이닉스", "KR", "HBM"),
        ("005930", "삼성전자", "KR", "HBM"),
        ("MU", "Micron Technology", "US", "HBM Memory"),
        ("042700", "한미반도체", "KR", "HBM Equipment"),
    ],
    "메모리/낸드": [
        ("000660", "SK하이닉스", "KR", "Memory Semiconductor"),
        ("005930", "삼성전자", "KR", "Memory Semiconductor"),
        ("MU", "Micron Technology", "US", "Memory Semiconductor"),
        ("WDC", "Western Digital", "US", "Storage"),
        ("STX", "Seagate Technology", "US", "Storage"),
        ("SNDK", "SanDisk", "US", "Storage"),
    ],
    "파운드리": [
        ("TSM", "Taiwan Semiconductor Manufacturing", "US", "Foundry"),
        ("GFS", "GlobalFoundries", "US", "Foundry"),
        ("UMC", "United Microelectronics", "US", "Foundry"),
        ("TSEM", "Tower Semiconductor", "US", "Specialty Foundry"),
        ("INTC", "Intel", "US", "Integrated Foundry"),
        ("005930", "삼성전자", "KR", "Foundry"),
        ("000990", "DB하이텍", "KR", "Foundry"),
    ],
    "반도체 설계": [
        ("AMD", "Advanced Micro Devices", "US", "Fabless Semiconductor"),
        ("QCOM", "Qualcomm", "US", "Fabless Semiconductor"),
        ("ARM", "Arm Holdings", "US", "Fabless Semiconductor"),
        ("MRVL", "Marvell Technology", "US", "Fabless Semiconductor"),
        ("TXN", "Texas Instruments", "US", "Analog Semiconductor"),
        ("ADI", "Analog Devices", "US", "Analog Semiconductor"),
    ],
    "반도체 장비": [
        ("ASML", "ASML Holding", "US", "Semiconductor Equipment"),
        ("AMAT", "Applied Materials", "US", "Semiconductor Equipment"),
        ("LRCX", "Lam Research", "US", "Semiconductor Equipment"),
        ("KLAC", "KLA", "US", "Semiconductor Equipment"),
        ("042700", "한미반도체", "KR", "반도체 장비"),
    ],
    "클라우드/SW": [
        ("CRM", "Salesforce", "US", "Software"),
        ("ADBE", "Adobe", "US", "Software"),
        ("NOW", "ServiceNow", "US", "Software"),
        ("SNOW", "Snowflake", "US", "Software"),
        ("DDOG", "Datadog", "US", "Software"),
        ("NET", "Cloudflare", "US", "Software"),
    ],
    "사이버보안": [
        ("PANW", "Palo Alto Networks", "US", "Cybersecurity"),
        ("CRWD", "CrowdStrike", "US", "Cybersecurity"),
        ("ZS", "Zscaler", "US", "Cybersecurity"),
        ("FTNT", "Fortinet", "US", "Cybersecurity"),
        ("OKTA", "Okta", "US", "Cybersecurity"),
    ],
    "핀테크/결제": [
        ("V", "Visa", "US", "Payments"),
        ("MA", "Mastercard", "US", "Payments"),
        ("PYPL", "PayPal", "US", "Fintech"),
        ("SQ", "Block", "US", "Fintech"),
        ("AXP", "American Express", "US", "Payments"),
    ],
    "전기차": [
        ("TSLA", "Tesla", "US", "Electric Vehicle"),
        ("RIVN", "Rivian", "US", "Electric Vehicle"),
        ("LCID", "Lucid Group", "US", "Electric Vehicle"),
        ("005380", "현대차", "KR", "자동차"),
        ("000270", "기아", "KR", "자동차"),
    ],
    "배터리": [
        ("373220", "LG에너지솔루션", "KR", "배터리"),
        ("006400", "삼성SDI", "KR", "배터리"),
        ("QS", "QuantumScape", "US", "Battery"),
    ],
    "배터리 소재": [
        ("ALB", "Albemarle", "US", "Lithium"),
        ("SQM", "SQM", "US", "Lithium"),
        ("051910", "LG화학", "KR", "화학"),
        ("003670", "포스코퓨처엠", "KR", "배터리 소재"),
        ("247540", "에코프로비엠", "KR", "배터리 소재"),
        ("096770", "SK이노베이션", "KR", "정유/배터리"),
    ],
    "조선": [
        ("009540", "HD한국조선해양", "KR", "조선"),
        ("010140", "삼성중공업", "KR", "조선"),
        ("329180", "HD현대중공업", "KR", "조선"),
        ("042660", "한화오션", "KR", "조선"),
    ],
    "방산/항공": [
        ("LMT", "Lockheed Martin", "US", "Defense"),
        ("RTX", "RTX", "US", "Defense"),
        ("NOC", "Northrop Grumman", "US", "Defense"),
        ("BA", "Boeing", "US", "Aerospace"),
        ("012450", "한화에어로스페이스", "KR", "방산"),
        ("047810", "한국항공우주", "KR", "항공우주"),
        ("064350", "현대로템", "KR", "방산"),
    ],
    "바이오/제약": [
        ("LLY", "Eli Lilly", "US", "Pharma"),
        ("NVO", "Novo Nordisk", "US", "Pharma"),
        ("MRK", "Merck", "US", "Pharma"),
        ("ABBV", "AbbVie", "US", "Pharma"),
        ("AMGN", "Amgen", "US", "Biotech"),
        ("068270", "셀트리온", "KR", "바이오"),
        ("207940", "삼성바이오로직스", "KR", "바이오"),
    ],
    "의료기기": [
        ("TMO", "Thermo Fisher Scientific", "US", "Medical Tools"),
        ("DHR", "Danaher", "US", "Medical Tools"),
        ("ABT", "Abbott Laboratories", "US", "Medical Device"),
        ("MDT", "Medtronic", "US", "Medical Device"),
        ("ISRG", "Intuitive Surgical", "US", "Medical Device"),
    ],
    "정유/화학": [
        ("XOM", "Exxon Mobil", "US", "Energy"),
        ("CVX", "Chevron", "US", "Energy"),
        ("DOW", "Dow", "US", "Chemicals"),
        ("010950", "S-Oil", "KR", "정유"),
        ("011170", "롯데케미칼", "KR", "화학"),
    ],
    "전력/유틸리티": [
        ("NEE", "NextEra Energy", "US", "Utilities"),
        ("DUK", "Duke Energy", "US", "Utilities"),
        ("SO", "Southern Company", "US", "Utilities"),
        ("CEG", "Constellation Energy", "US", "Utilities"),
        ("015760", "한국전력", "KR", "전력"),
    ],
    "이커머스": [
        ("AMZN", "Amazon", "US", "E-Commerce"),
        ("SHOP", "Shopify", "US", "E-Commerce"),
        ("MELI", "MercadoLibre", "US", "E-Commerce"),
        ("CPNG", "Coupang", "US", "E-Commerce"),
        ("035420", "NAVER", "KR", "플랫폼"),
        ("035720", "카카오", "KR", "플랫폼"),
    ],
    "미디어/엔터": [
        ("NFLX", "Netflix", "US", "Streaming"),
        ("DIS", "Disney", "US", "Entertainment"),
        ("WBD", "Warner Bros. Discovery", "US", "Media"),
        ("352820", "하이브", "KR", "엔터"),
        ("041510", "에스엠", "KR", "엔터"),
        ("035900", "JYP Ent.", "KR", "엔터"),
    ],
    "게임": [
        ("RBLX", "Roblox", "US", "Gaming"),
        ("EA", "Electronic Arts", "US", "Gaming"),
        ("TTWO", "Take-Two Interactive", "US", "Gaming"),
        ("036570", "엔씨소프트", "KR", "게임"),
        ("259960", "크래프톤", "KR", "게임"),
        ("251270", "넷마블", "KR", "게임"),
    ],
    "소재/철강": [
        ("NUE", "Nucor", "US", "Steel"),
        ("FCX", "Freeport-McMoRan", "US", "Copper"),
        ("BHP", "BHP Group", "US", "Materials"),
        ("005490", "POSCO홀딩스", "KR", "철강"),
        ("010130", "고려아연", "KR", "비철금속"),
        ("004020", "현대제철", "KR", "철강"),
    ],
    "운송/물류": [
        ("UPS", "UPS", "US", "Logistics"),
        ("FDX", "FedEx", "US", "Logistics"),
        ("UNP", "Union Pacific", "US", "Railroad"),
        ("000120", "CJ대한통운", "KR", "물류"),
        ("003490", "대한항공", "KR", "항공"),
        ("011200", "HMM", "KR", "해운"),
    ],
}


def _sector_compact(value: str) -> str:
    return re.sub(r"[\s/_\-.]+", "", str(value or "").lower())


def _sector_term_matches(term: str, raw_text: str, compact_text: str) -> bool:
    lower = str(term or "").lower().strip()
    if not lower:
        return False
    compact = _sector_compact(lower)
    if re.fullmatch(r"[a-z0-9]+", compact or "") and len(compact) <= 3:
        return re.search(rf"(?<![a-z0-9]){re.escape(lower)}(?![a-z0-9])", raw_text) is not None
    return lower in raw_text or compact in compact_text


def _sector_theme_label(ticker: str, name: str, sector: str | None = None) -> str:
    return _sector_theme_labels(ticker, name, sector)[0]


def _sector_theme_labels(
    ticker: str,
    name: str,
    sector: str | None = None,
    explicit_theme: str | None = None,
) -> list[str]:
    symbol = str(ticker or "").strip().upper().split(".")[0]
    code = _kr_code(ticker)
    ticker_keys = {key for key in (symbol, code) if key}
    raw_text = " ".join([str(ticker or ""), str(name or ""), str(sector or "")]).lower()
    compact_text = _sector_compact(raw_text)
    labels: list[str] = []

    def add(label: str | None) -> None:
        clean = str(label or "").strip()
        if clean and clean not in labels:
            labels.append(clean)

    add(explicit_theme)

    for key in ticker_keys:
        if key in _SECTOR_THEME_OVERRIDES:
            add(_SECTOR_THEME_OVERRIDES[key])

    for rule in _SECTOR_THEME_RULES:
        if ticker_keys & set(rule["tickers"]):
            add(str(rule["label"]))
            continue
        for term in rule["terms"]:
            if _sector_term_matches(str(term), raw_text, compact_text):
                add(str(rule["label"]))
                break

    if labels:
        return labels
    return [_sector_fallback_theme_label(sector)]


def _sector_fallback_theme_label(sector: str | None = None) -> str:
    clean_sector = str(sector or "").strip()
    lower_sector = clean_sector.lower()
    if lower_sector in {"", "unclassified", "unknown", "none", "nan", "-"}:
        return "기타"
    if "semiconductor" in lower_sector or "반도체" in clean_sector:
        return "반도체 설계"
    if "software" in lower_sector or "information technology" in lower_sector or lower_sector == "technology" or "소프트웨어" in clean_sector:
        return "클라우드/SW"
    if "communication" in lower_sector or "telecom" in lower_sector or "통신" in clean_sector:
        return "통신"
    if "전기" in clean_sector and "전자" in clean_sector:
        return "전자/부품"
    if "utility" in lower_sector or "utilities" in lower_sector or "유틸" in clean_sector or "전력" in clean_sector:
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


def _comparison_item(row: dict, source: str, market: str | None) -> dict:
    ticker = str(row.get("Ticker") or row.get("ticker") or "").strip().upper()
    item_market = str(row.get("Market") or market or _infer_market_from_ticker(ticker)).strip().upper()
    score = _first_float(row.get("Total_Score"), row.get("Final_Score"), row.get("Combined_Score"))
    return {
        "Ticker": ticker,
        "Name": _localized_company_name(ticker, row.get("Name") or row.get("name") or ticker, item_market),
        "Market": item_market,
        "Sector": row.get("Sector") or ("SmallCap" if source == "SmallCap" else None),
        "Currency": _comparison_currency(ticker, item_market),
        "Source": source,
        "Score_Value": score,
        "Score_Text": f"{score:.0f}점" if source == "SmallCap" and score is not None else f"{score:.3f}" if score is not None else "-",
        "Expected_Return": _safe_float(row.get("Expected_Return")),
        "RevGrowth": _safe_float(row.get("RevGrowth")),
        "ROIC": _safe_float(row.get("ROIC")),
        "GrossMargin": _safe_float(row.get("GrossMargin")),
        "MarketCap": _safe_float(row.get("MarketCap")),
        "Weight(%)": _safe_float(row.get("Weight(%)")),
        "FCF_Margin": _safe_float(row.get("FCF_Margin")),
        "Volume_Surge": _safe_float(row.get("Volume_Surge")),
        "Last_Updated": row.get("Last_Updated") or row.get("Price_Updated_At"),
    }


def _comparison_match_keys(ticker: str) -> set[str]:
    text = str(ticker or "").strip().upper()
    if not text:
        return set()
    keys = {text}
    code = _kr_code(text)
    if code:
        keys.update({code, f"{code}.KS", f"{code}.KQ"})
    return keys


def _comparison_recommendation_payload(ticker: str, market: str = "ALL", limit: int = 8) -> dict:
    safe_market = str(market or "ALL").upper()
    if safe_market not in {"ALL", "US", "KR"}:
        safe_market = "ALL"
    safe_limit = max(1, min(int(limit or 8), 20))
    anchor_keys = _comparison_match_keys(ticker)
    markets = ["US", "KR"] if safe_market == "ALL" else [safe_market]

    items: list[dict] = []
    for current_market in markets:
        _, portfolio_rows = _load_portfolio(f"{current_market}_Final_Portfolio")
        items.extend(_comparison_item(row, "Portfolio", current_market) for row in portfolio_rows)
        small_rows = _load_simple(
            f"{current_market}_SmallCap_Gems",
            ["Rank", "MarketCap", "Total_Score", "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin", "Volume_Surge"],
        )
        items.extend(_comparison_item(row, "SmallCap", current_market) for row in small_rows)
        scored_rows = _load_simple(
            f"{current_market}_Scored_Stocks",
            ["Rank", "MarketCap", "Total_Score", "Final_Score", "Combined_Score", "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin"],
        )
        items.extend(_comparison_item(row, "Score", current_market) for row in scored_rows[:120])

    distinct: dict[str, dict] = {}
    for item in items:
        key = next(iter(_comparison_match_keys(item.get("Ticker") or "")), item.get("Ticker"))
        if key and key not in distinct:
            distinct[key] = item

    anchor = next((item for item in distinct.values() if _comparison_match_keys(item.get("Ticker") or "") & anchor_keys), None)
    if anchor is None:
        anchor = {
            "Ticker": str(ticker or "").strip().upper(),
            "Name": str(ticker or "").strip().upper(),
            "Market": None if safe_market == "ALL" else safe_market,
            "Sector": None,
            "Source": "Anchor",
        }

    def match_score(item: dict) -> tuple[float, str]:
        score = 0.0
        reasons = []
        if item.get("Market") and item.get("Market") == anchor.get("Market"):
            score += 2
            reasons.append("같은 시장")
        if item.get("Sector") and item.get("Sector") == anchor.get("Sector"):
            score += 5
            reasons.append("같은 섹터")
        if item.get("Source") == "Portfolio":
            score += 3
            reasons.append("핵심 후보")
        if item.get("Source") == "SmallCap":
            score += 2
            reasons.append("스몰캡 대조")
        item_score = _safe_float(item.get("Score_Value"))
        if item_score is not None:
            score += min(max(item_score, 0), 100) / 50
        anchor_cap = _safe_float(anchor.get("MarketCap"))
        item_cap = _safe_float(item.get("MarketCap"))
        if anchor_cap and item_cap and anchor_cap > 0 and item_cap > 0:
            cap_gap = abs(math.log(anchor_cap) - math.log(item_cap))
            if cap_gap < 1.0:
                score += 2
                reasons.append("비슷한 규모")
        if _safe_float(item.get("RevGrowth")) is not None and _safe_float(anchor.get("RevGrowth")) is not None:
            score += 1
            reasons.append("성장성 비교")
        return score, " · ".join(reasons[:3]) or "지표 비교"

    recommendations = []
    for item in distinct.values():
        if _comparison_match_keys(item.get("Ticker") or "") & anchor_keys:
            continue
        score, reason = match_score(item)
        enriched = dict(item)
        enriched["Match_Score"] = score
        enriched["Recommendation_Reason"] = reason
        recommendations.append(enriched)
    recommendations.sort(key=lambda row: (-row.get("Match_Score", 0), str(row.get("Name") or "")))
    return {
        "anchor": anchor,
        "count": min(len(recommendations), safe_limit),
        "items": recommendations[:safe_limit],
        "generated_at": _utc_now_iso(),
    }


# ── Endpoints ─────────────────────────────────────────────────────────────────

@app.get("/health", response_model=HealthResponse)
def health():
    return {"status": "ok", "ts": datetime.utcnow().isoformat()}


def _ready_payload() -> dict:
    checks: dict = {
        "api": "ok",
        "auth_store": "unknown",
        "sqlite": "unknown",
        "postgres": "disabled" if not _SETTINGS.enable_postgres else "unknown",
        "cache": {},
        "ts": datetime.utcnow().isoformat(),
    }

    try:
        with _auth_db() as conn:
            conn.execute("SELECT 1").fetchone()
        checks["auth_store"] = "ok"
        checks["sqlite"] = "ok"
    except Exception as exc:
        message = f"error: {type(exc).__name__}: {exc}"
        checks["auth_store"] = message
        checks["sqlite"] = message

    if _SETTINGS.enable_postgres:
        try:
            pg = _repository().postgres
            pg.ensure_schema()
            with pg._connect() as conn:
                identity_count = conn.execute("SELECT count(*) FROM company_identity").fetchone()[0]
                price_count = conn.execute("SELECT count(*) FROM price_ohlcv").fetchone()[0]
                price_metric_count = conn.execute("SELECT count(*) FROM latest_price_metrics").fetchone()[0]
            checks["postgres"] = "ok"
            checks["cache"] = {
                "company_identity": identity_count,
                "price_ohlcv": price_count,
                "latest_price_metrics": price_metric_count,
            }
        except Exception as exc:
            checks["postgres"] = f"error: {type(exc).__name__}: {exc}"

    ok = checks["auth_store"] == "ok" and checks["postgres"] in {"ok", "disabled"}
    checks["status"] = "ready" if ok else "not_ready"
    return checks


@app.get("/ready", response_model=ReadyResponse)
def ready():
    checks = _ready_payload()
    if checks.get("status") != "ready":
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=checks)
    return checks


def _sector_stock_key(ticker: str) -> str:
    code = _kr_code(ticker)
    return code or str(ticker or "").strip().upper()


def _sector_source_priority(source: str) -> int:
    return {"Portfolio": 0, "SmallCap": 1, "Score": 2, "Universe": 3, "ThemeSeed": 4}.get(str(source or ""), 9)


def _is_sector_equity_row(row: dict) -> bool:
    """Keep the sector tab focused on companies, not ETF products."""

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


def _sector_theme_seed_rows(market: str) -> list[dict]:
    safe_market = str(market or "").upper()
    rows: list[dict] = []
    for label, members in _SECTOR_THEME_SEED_GROUPS.items():
        for ticker, name, item_market, sector in members:
            if str(item_market).upper() != safe_market:
                continue
            rows.append({
                "Ticker": ticker,
                "Name": _localized_company_name(ticker, name, safe_market),
                "Market": safe_market,
                "Sector": sector,
                "Theme": label,
                "Source": "ThemeSeed",
            })
    return rows


def _sector_market_rows(market: str) -> list[dict]:
    market = market.upper()
    rows: list[dict] = []

    universe = _load_table(f"{market}_Universe", ["MarketCap"])
    if not universe.empty:
        rows.extend(
            row
            for row in ({**row, "Source": "Universe", "Market": row.get("Market") or market} for row in _df_to_records(universe))
            if _is_sector_equity_row(row)
        )

    _, portfolio_rows = _load_portfolio(f"{market}_Final_Portfolio")
    rows.extend(
        row
        for row in ({**row, "Source": "Portfolio", "Market": row.get("Market") or market} for row in portfolio_rows)
        if _is_sector_equity_row(row)
    )

    small_rows = _load_simple(
        f"{market}_SmallCap_Gems",
        ["Rank", "MarketCap", "Total_Score", "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin", "Volume_Surge"],
    )
    rows.extend(
        row
        for row in ({**row, "Source": "SmallCap", "Market": row.get("Market") or market} for row in small_rows)
        if _is_sector_equity_row(row)
    )

    scored_rows = _load_simple(
        f"{market}_Scored_Stocks",
        ["Rank", "MarketCap", "Total_Score", "Final_Score", "Combined_Score", "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin"],
    )
    rows.extend(
        row
        for row in ({**row, "Source": "Score", "Market": row.get("Market") or market} for row in scored_rows)
        if _is_sector_equity_row(row)
    )
    rows.extend(_sector_theme_seed_rows(market))
    return rows


def _yf_stock_symbol(ticker: str, market: str) -> str:
    normal = str(ticker or "").strip().upper()
    if market.upper() == "KR":
        code = _kr_code(normal)
        return f"{code}.KS" if code else normal
    return normal.replace(".", "-")


def _yf_close_frame(raw: pd.DataFrame, symbols: list[str]) -> pd.DataFrame:
    if raw.empty:
        return pd.DataFrame()
    if isinstance(raw.columns, pd.MultiIndex):
        levels = [set(map(str, raw.columns.get_level_values(level))) for level in range(raw.columns.nlevels)]
        if "Close" in levels[0]:
            frame = raw["Close"].copy()
        elif raw.columns.nlevels > 1 and "Close" in levels[1]:
            frame = raw.xs("Close", axis=1, level=1).copy()
        else:
            return pd.DataFrame()
    else:
        if "Close" not in raw.columns:
            return pd.DataFrame()
        close = raw["Close"].copy()
        frame = close.to_frame(name=symbols[0]) if isinstance(close, pd.Series) else close

    if isinstance(frame, pd.Series):
        frame = frame.to_frame(name=symbols[0])
    frame.columns = [str(column).strip().upper() for column in frame.columns]
    frame.index = pd.to_datetime(frame.index, errors="coerce")
    frame = frame[frame.index.notna()]
    return frame.sort_index()


def _yf_download_close_frame(
    yf,
    symbols: list[str],
    *,
    period: str,
    interval: str,
    batch_size: int = 45,
) -> pd.DataFrame:
    frames: list[pd.DataFrame] = []
    clean_symbols = [symbol for symbol in symbols if symbol]
    for index in range(0, len(clean_symbols), max(1, batch_size)):
        batch = clean_symbols[index:index + batch_size]
        try:
            raw = yf.download(
                batch,
                period=period,
                interval=interval,
                auto_adjust=False,
                progress=False,
                ignore_tz=False,
                threads=True,
                timeout=8,
            )
            frame = _yf_close_frame(raw, batch)
            if not frame.empty:
                frames.append(frame)
        except Exception:
            continue
    if not frames:
        return pd.DataFrame()
    merged = pd.concat(frames, axis=1)
    return merged.loc[:, ~merged.columns.duplicated()].sort_index()


def _yfinance_stock_quote_batch(tickers: list[str], market: str) -> dict[str, dict]:
    if market.upper() == "KR":
        return {}
    requested = [str(ticker or "").strip().upper() for ticker in tickers if str(ticker or "").strip()]
    if not requested:
        return {}
    symbol_by_ticker = {ticker: _yf_stock_symbol(ticker, market) for ticker in requested}
    try:
        import yfinance as yf
    except ImportError:
        return {}

    symbols = list(dict.fromkeys(symbol_by_ticker.values()))
    use_regular_close = _should_use_last_regular_close_change(market)
    quotes: dict[str, dict] = {}
    fallback_tickers = requested

    if not use_regular_close:
        def fast_quote(ticker: str, symbol: str) -> tuple[str, dict | None]:
            try:
                fast_info = getattr(yf.Ticker(symbol), "fast_info", {}) or {}
                last = _safe_float(_fast_info_get(fast_info, "last_price"))
                previous = _safe_float(_fast_info_get(fast_info, "previous_close"))
                if last is None or previous is None or previous <= 0:
                    return ticker, None
                return ticker, {
                    "current_price": last,
                    "daily_change_pct": (last / previous) - 1.0,
                    "daily_change_horizon": "오늘",
                    "updated_at": datetime.now(timezone.utc).isoformat(),
                    "source": "yfinance_fast_info",
                }
            except Exception:
                return ticker, None

        workers = max(1, min(24, len(requested)))
        with ThreadPoolExecutor(max_workers=workers) as executor:
            futures = {
                executor.submit(fast_quote, ticker, symbol_by_ticker[ticker]): ticker
                for ticker in requested
            }
            for future in as_completed(futures):
                ticker, payload = future.result()
                if not payload:
                    continue
                for key in _comparison_match_keys(ticker):
                    quotes[key] = payload
                quotes[ticker] = payload

        fallback_tickers = [ticker for ticker in requested if ticker not in quotes]
        if not fallback_tickers:
            return quotes

    fallback_symbols = list(dict.fromkeys(symbol_by_ticker[ticker] for ticker in fallback_tickers))
    intraday = pd.DataFrame() if use_regular_close else _yf_download_close_frame(yf, fallback_symbols, period="2d", interval="15m")
    daily = _yf_download_close_frame(yf, fallback_symbols, period="7d", interval="1d")

    for ticker in fallback_tickers:
        symbol = symbol_by_ticker[ticker]
        symbol_key = symbol.upper()
        current_price = None
        observed_at = None
        if symbol_key in intraday.columns:
            series = intraday[symbol_key].dropna().sort_index()
            if not series.empty:
                current_price = _safe_float(series.iloc[-1])
                observed_at = pd.Timestamp(series.index[-1])

        daily_series = daily[symbol_key].dropna().sort_index() if symbol_key in daily.columns else pd.Series(dtype=float)
        daily_series = _drop_unsettled_us_daily_series(daily_series, market)
        previous_close = None
        if (use_regular_close or current_price is None) and not daily_series.empty:
            current_price = _safe_float(daily_series.iloc[-1])
            observed_at = pd.Timestamp(daily_series.index[-1])

        if not daily_series.empty:
            observed_date = pd.Timestamp(observed_at).date() if observed_at is not None else None
            daily_dates = [pd.Timestamp(index).date() for index in daily_series.index]
            latest_daily_date = daily_dates[-1]
            if observed_date and observed_date > latest_daily_date:
                previous_close = _safe_float(daily_series.iloc[-1])
            elif observed_date and observed_date == latest_daily_date and len(daily_series) >= 2:
                previous_close = _safe_float(daily_series.iloc[-2])
            elif len(daily_series) >= 2:
                previous_close = _safe_float(daily_series.iloc[-2])
            else:
                previous_close = _safe_float(daily_series.iloc[-1])

        daily_change = None
        if current_price is not None and previous_close is not None and previous_close > 0:
            daily_change = (current_price / previous_close) - 1.0
        if current_price is None and daily_change is None:
            continue

        payload = {
            "current_price": current_price,
            "daily_change_pct": daily_change,
            "daily_change_horizon": "전장" if use_regular_close and daily_change is not None else "오늘" if daily_change is not None else "",
            "updated_at": observed_at.isoformat() if observed_at is not None else None,
            "source": "yfinance_daily_close" if use_regular_close else "yfinance_intraday",
        }
        for key in _comparison_match_keys(ticker):
            quotes[key] = payload
        quotes[ticker] = payload
    return quotes


_SECTOR_PRICE_REQUEST_LIMIT = 1800
_SECTOR_US_LIVE_QUOTE_LIMIT = 240
_SECTOR_DETAIL_RECONCILE_LIMIT = 40
_SECTOR_FALLBACK_USDKRW = 1350.0


def _sector_usdkrw_rate() -> float:
    try:
        stored = _stored_indicator_latest(["KRW=X"]).get("KRW=X") or {}
        rate = _safe_float(stored.get("value") or stored.get("close"))
        if rate is not None and rate > 0:
            return rate
    except Exception:
        pass
    return _SECTOR_FALLBACK_USDKRW


def _sector_market_cap_usd_sort_value(item: dict, usdkrw_rate: float) -> float | None:
    market_cap = _safe_float(item.get("MarketCap"))
    if market_cap is None or market_cap <= 0:
        return None
    market = str(item.get("Market") or "").upper()
    currency = str(item.get("Currency") or "").upper()
    if market == "KR" or currency == "KRW":
        return market_cap / max(usdkrw_rate, 1.0)
    return market_cap


def _sector_market_cap_weighted_value(items: list[dict], value_key: str, usdkrw_rate: float) -> float | None:
    weighted_sum = 0.0
    total_weight = 0.0
    equal_values: list[float] = []
    for item in items:
        value = _safe_float(item.get(value_key))
        if value is None:
            continue
        equal_values.append(value)
        weight = _sector_market_cap_usd_sort_value(item, usdkrw_rate)
        if weight is None or weight <= 0:
            continue
        weighted_sum += value * weight
        total_weight += weight
    if total_weight > 0:
        return weighted_sum / total_weight
    return sum(equal_values) / len(equal_values) if equal_values else None


def _sector_price_metric_map(
    market: str,
    tickers: list[str],
    live_tickers: list[str] | None = None,
) -> dict[str, dict]:
    requested = [
        str(ticker).strip().upper()
        for ticker in tickers
        if str(ticker or "").strip()
    ]
    requested = list(dict.fromkeys(requested))[:_SECTOR_PRICE_REQUEST_LIMIT]
    if not requested:
        return {}

    alias_order = {
        ticker: [ticker] + sorted(_comparison_match_keys(ticker) - {ticker})
        for ticker in requested
    }
    lookup_tickers = list(dict.fromkeys(alias for aliases in alias_order.values() for alias in aliases))
    snapshots = _portfolio_price_snapshot_batch(lookup_tickers, market)
    missing = [ticker for ticker in lookup_tickers if ticker not in snapshots]
    batch_metrics = _portfolio_price_metrics_batch(missing, market) if missing else {}
    daily_changes = _portfolio_daily_change_batch(lookup_tickers, market, snapshots)
    missing_current = [
        ticker
        for ticker in missing
        if _safe_float((batch_metrics.get(ticker) or (None, None))[0]) is None
    ]
    live_requested = [
        str(ticker).strip().upper()
        for ticker in (live_tickers if live_tickers is not None else requested)
        if str(ticker or "").strip()
    ]
    live_requested = list(dict.fromkeys(live_requested))
    naver_kr_quotes = (
        _naver_kr_stock_price_batch(live_requested)
        if market == "KR" and live_tickers is not None and live_requested
        else {}
    )
    live_quote_tickers = (
        live_requested[:_SECTOR_US_LIVE_QUOTE_LIMIT]
        if market != "KR"
        else missing_current[:_SECTOR_US_LIVE_QUOTE_LIMIT]
    )
    yfinance_quotes = (
        _yfinance_stock_quote_batch(live_quote_tickers, market)
        if market != "KR" and _allow_api_external_fetch()
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
                    if value and _safe_float(value[0]) is not None
                ),
                (None, None),
            )
            updated_at = None
        daily_change, daily_horizon = next(
            (
                value
                for alias in aliases
                for value in [daily_changes.get(alias)]
                if value and _safe_float(value[0]) is not None
            ),
            (None, ""),
        )
        snapshot_daily_change, snapshot_daily_horizon = next(
            (
                (_safe_float(snap.get("daily_change_pct")), str(snap.get("daily_change_horizon") or ""))
                for alias in aliases
                for snap in [snapshots.get(alias)]
                if snap and _safe_float(snap.get("daily_change_pct")) is not None
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
        for key in _comparison_match_keys(ticker):
            metrics[key] = metric
    return metrics


def _sector_themes_payload(
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
        for raw_row in _sector_market_rows(current_market):
            row = dict(raw_row)
            ticker = str(row.get("Ticker") or row.get("ticker") or "").strip().upper()
            if not ticker:
                continue
            row["Ticker"] = ticker
            row["Market"] = str(row.get("Market") or _infer_market_from_ticker(row["Ticker"])).upper()
            row["Name"] = _localized_company_name(row["Ticker"], row.get("Name") or row.get("name") or row["Ticker"], row.get("Market"))
            row["Sector"] = row.get("Sector") or row.get("sector") or ""
            row["MarketCap"] = _first_float(row.get("MarketCap"), row.get("market_cap"))
            row["Score_Value"] = _first_float(row.get("Total_Score"), row.get("Final_Score"), row.get("Combined_Score"))
            themes = _sector_theme_labels(
                row["Ticker"],
                str(row["Name"]),
                str(row.get("Sector") or ""),
                explicit_theme=row.get("Theme"),
            )
            for theme in themes:
                themed_row = {**row, "Theme": theme}
                key = (str(theme), _sector_stock_key(ticker))
                existing = by_theme_key.get(key)
                if existing is None:
                    by_theme_key[key] = themed_row
                    continue
                current_priority = _sector_source_priority(str(themed_row.get("Source")))
                existing_priority = _sector_source_priority(str(existing.get("Source")))
                if current_priority < existing_priority:
                    by_theme_key[key] = themed_row
                elif current_priority == existing_priority and _safe_float(existing.get("MarketCap")) is None and _safe_float(themed_row.get("MarketCap")) is not None:
                    by_theme_key[key] = themed_row

    rows = list(by_theme_key.values())

    grouped: dict[str, list[dict]] = {}
    for row in rows:
        grouped.setdefault(str(row["Theme"]), []).append(row)

    focus_key = _sector_compact(str(focus_label or ""))
    if focus_key:
        grouped = {
            label: group_rows
            for label, group_rows in grouped.items()
            if _sector_compact(str(label)) == focus_key
        }
    price_requests: dict[str, list[str]] = {"US": [], "KR": []}
    live_price_requests: dict[str, list[str]] = {"US": [], "KR": []}
    if focus_key:
        for label, group_rows in grouped.items():
            for row in group_rows:
                row_market = str(row.get("Market") or "").upper()
                if row_market in price_requests:
                    ticker = str(row.get("Ticker") or "")
                    price_requests[row_market].append(ticker)
                    live_price_requests[row_market].append(ticker)
    for group_rows in grouped.values():
        for row in group_rows:
            row_market = str(row.get("Market") or "").upper()
            if row_market in price_requests and len(price_requests[row_market]) < _SECTOR_PRICE_REQUEST_LIMIT:
                price_requests[row_market].append(str(row.get("Ticker") or ""))

    price_maps = {
        current_market: _sector_price_metric_map(
            current_market,
            list(dict.fromkeys(tickers)),
            live_tickers=list(dict.fromkeys(live_price_requests[current_market])) if focus_key else None,
        )
        for current_market, tickers in price_requests.items()
    }

    themes: list[dict] = []
    usdkrw_rate = _sector_usdkrw_rate()
    for label, group_rows in grouped.items():
        enriched_members = []
        for row in group_rows:
            metric = {}
            row_market = str(row.get("Market") or "").upper()
            for key in _comparison_match_keys(str(row.get("Ticker") or "")):
                metric = price_maps.get(row_market, {}).get(key) or metric
                if metric:
                    break
            daily_change = _first_float(metric.get("Daily_Change_Pct"), row.get("Daily_Change_Pct"))
            daily_horizon = str(metric.get("Daily_Change_Horizon") or row.get("Daily_Change_Horizon") or "")
            return_1m = _first_float(metric.get("Return_1M"), row.get("Return_1M"), row.get("Mom_1M"))
            current_price = _first_float(metric.get("Current_Price"), row.get("Current_Price"))
            enriched_members.append({
                "Ticker": row.get("Ticker"),
                "Name": _localized_company_name(row.get("Ticker"), row.get("Name"), row.get("Market")),
                "Market": row.get("Market"),
                "Sector": row.get("Sector"),
                "Currency": _comparison_currency(str(row.get("Ticker") or ""), str(row.get("Market") or "")),
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

        coverage = [item for item in enriched_members if _safe_float(item.get("Daily_Change_Pct")) is not None]
        one_month = [item for item in enriched_members if _safe_float(item.get("Return_1M")) is not None]
        coverage_ratio = len(coverage) / len(enriched_members) if enriched_members else 0.0
        avg_change = _sector_market_cap_weighted_value(coverage, "Daily_Change_Pct", usdkrw_rate)
        avg_return_1m = _sector_market_cap_weighted_value(one_month, "Return_1M", usdkrw_rate)
        rising_count = sum(1 for item in coverage if (_safe_float(item.get("Daily_Change_Pct")) or 0) > 0)
        movement_sorted_members = sorted(
            enriched_members,
            key=lambda item: (
                _safe_float(item.get("Daily_Change_Pct")) is None,
                -abs(_safe_float(item.get("Daily_Change_Pct")) or 0),
                -(_safe_float(item.get("MarketCap")) or 0),
            ),
        )
        market_cap_sorted_members = sorted(
            enriched_members,
            key=lambda item: (
                _sector_market_cap_usd_sort_value(item, usdkrw_rate) is None,
                -(_sector_market_cap_usd_sort_value(item, usdkrw_rate) or 0),
                _safe_float(item.get("Daily_Change_Pct")) is None,
                -abs(_safe_float(item.get("Daily_Change_Pct")) or 0),
                str(item.get("Name") or item.get("Ticker") or ""),
            ),
        )
        display_members = [
            item for item in market_cap_sorted_members
            if _safe_float(item.get("Daily_Change_Pct")) is not None
        ] or market_cap_sorted_members
        payload_members = display_members[:safe_members]
        payload_coverage = coverage
        payload_avg_change = avg_change
        payload_rising_count = rising_count
        payload_leader = next((item for item in movement_sorted_members if _safe_float(item.get("Daily_Change_Pct")) is not None), None)
        if focus_key and _sector_compact(str(label)) == focus_key:
            reconciled_members = _sector_detail_reconcile_members(payload_members[:_SECTOR_DETAIL_RECONCILE_LIMIT])
            payload_members = reconciled_members + payload_members[_SECTOR_DETAIL_RECONCILE_LIMIT:]
            payload_coverage = [item for item in payload_members if _safe_float(item.get("Daily_Change_Pct")) is not None]
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

    order_index = {label: index for index, label in enumerate(_SECTOR_THEME_ORDER)}

    def top_eligible(item: dict) -> bool:
        return (
            int(item.get("member_count") or 0) >= 3
            and int(item.get("priced_count") or 0) >= 3
            and float(item.get("price_coverage_ratio") or 0) >= 0.7
            and _safe_float(item.get("avg_change_pct")) is not None
        )

    themes.sort(
        key=lambda item: (
            not top_eligible(item),
            _safe_float(item.get("avg_change_pct")) is None,
            -abs(_safe_float(item.get("avg_change_pct")) or 0),
            order_index.get(str(item.get("label")), 99),
        )
    )
    return {
        "market": safe_market,
        "generated_at": _utc_now_iso(),
        "count": min(len(themes), safe_limit),
        "items": themes[:safe_limit],
    }


def signal_events(market: str = "ALL", tickers: str = "", kinds: str = "", limit: int = 100):
    cache_key = f"signal_events_{market}_{tickers}_{kinds}_{limit}"
    return _cached(cache_key, lambda: _signal_events_payload(market, tickers, kinds, limit), ttl=60)


def comparison_recommendations(ticker: str, market: str = "ALL", limit: int = 8):
    safe_ticker = str(ticker or "").strip().upper()
    if not safe_ticker:
        raise HTTPException(400, "ticker is required")
    cache_key = f"comparison_recommendations_{safe_ticker}_{market}_{limit}"
    return _cached(cache_key, lambda: _comparison_recommendation_payload(safe_ticker, market, limit), ttl=300)


@app.get("/smallcap/{market}", response_model=SmallCapResponse)
def smallcap(market: str):
    market = market.upper()
    if market not in ("US", "KR"):
        raise HTTPException(400, "market must be US or KR")

    sheet   = f"{market}_SmallCap_Gems"
    num_cols = [
        "ROIC", "RevGrowth", "Rev_Accel", "GrossMargin", "FCF_Margin",
        "Debt_EBITDA", "Volume_Surge", "SmallCap_Bonus", "Data_Confidence",
        "Total_Score", "MarketCap", "Rank", "Current_Price", "Return_1M",
        "Previous_Rank", "Rank_Change",
    ]

    def load():
        stocks = _load_simple(sheet, num_cols)
        if market == "KR":
            stocks = _enrich_kr_company_identities(stocks)
        stocks = _enrich_portfolio_price_fields(stocks, market, max_fetch=0)
        stocks = _enrich_rank_change_fields(stocks, sheet, market)
        return _apply_localized_names({"stocks": stocks})

    return _cached(f"sc_{market}", load, ttl=60, stale_ttl=0)


@app.get("/scored/{market}", response_model=ScoredResponse)
def scored(market: str, limit: int = 200):
    market = market.upper()
    if market not in ("US", "KR"):
        raise HTTPException(400, "market must be US or KR")
    safe_limit = max(1, min(int(limit or 200), 2000))

    sheet   = f"{market}_Scored_Stocks"
    num_cols = [
        "Value_Score", "Quality_Score", "Momentum_Score",
        "Total_Score", "Final_Score", "Score_Neutral", "ML_Score", "Combined_Score",
        "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin",
        "Debt_EBITDA", "PEG", "MarketCap", "Rank",
    ]

    def load():
        records = _load_simple(sheet, num_cols)
        for row in records:
            row["Market"] = row.get("Market") or market
        if market == "KR":
            records = _enrich_kr_company_identities(records)
        records = _enrich_portfolio_price_fields(records, market, max_fetch=0)
        return _apply_localized_names({"stocks": records[:safe_limit]})

    return _cached(f"scored_{market}_{safe_limit}", load, ttl=60, stale_ttl=0)


def search_universe(q: str = "", limit: int = 100):
    safe_limit = max(1, min(int(limit or 100), 200))
    cache_key = f"search_universe_{str(q or '').strip().lower()}_{safe_limit}"
    return _cached(cache_key, lambda: _apply_localized_names(_search_universe_payload(q, safe_limit)))


def backtest(market: str):
    market = market.upper()
    if market not in ("US", "KR"):
        raise HTTPException(400, "market must be US or KR")
    sheet = f"{market}_Backtest_Results"
    return _cached(f"backtest_{market}", lambda: _backtest_payload(sheet, market))


def smallcap_backtest(market: str):
    market = market.upper()
    if market not in ("US", "KR"):
        raise HTTPException(400, "market must be US or KR")
    sheet = f"{market}_SmallCap_Backtest"
    return _cached(f"smallcap_backtest_{market}", lambda: _backtest_payload(sheet, market))


def risk_drift():
    return _cached("risk_drift", _risk_drift_payload)


def portfolio_risk(market: str, limit: int = 30):
    market = market.upper()
    if market not in ("US", "KR"):
        raise HTTPException(400, "market must be US or KR")
    safe_limit = max(1, min(int(limit or 30), 100))
    return _cached(f"portfolio_risk_{market}_{safe_limit}", lambda: _portfolio_risk_payload(market, safe_limit))


def rebalance_report(market: str, limit: int = 50):
    market = market.upper()
    if market not in ("US", "KR"):
        raise HTTPException(400, "market must be US or KR")
    safe_limit = max(1, min(int(limit or 50), 200))
    return _cached(f"rebalance_{market}_{safe_limit}", lambda: _rebalance_payload(market, safe_limit))


def shadow_attribution(market: str = "ALL", limit: int = 50):
    safe_market = str(market or "ALL").upper()
    if safe_market not in ("ALL", "US", "KR"):
        raise HTTPException(400, "market must be ALL, US, or KR")
    safe_limit = max(1, min(int(limit or 50), 200))
    return _cached(
        f"shadow_attribution_{safe_market}_{safe_limit}",
        lambda: _shadow_attribution_payload(safe_market, safe_limit),
    )


def risk_industry(limit: int = 30):
    safe_limit = max(1, min(int(limit or 30), 100))
    return _cached(f"risk_industry_{safe_limit}", lambda: _industry_payload(safe_limit))


def risk_order_flow(limit: int = 30):
    safe_limit = max(1, min(int(limit or 30), 100))
    return _cached(f"risk_order_flow_{safe_limit}", lambda: _order_flow_payload(safe_limit))


def _parse_calendar_date(value) -> datetime | None:
    text = _clean_meta_value(value)
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


def _infer_market_from_ticker(ticker: str) -> str:
    normal = str(ticker or "").strip().upper()
    if normal.endswith((".KS", ".KQ")) or re.fullmatch(r"\d{6}", normal):
        return "KR"
    return "US"


def _normalize_us_calendar_ticker(ticker: str) -> str:
    return str(ticker or "").strip().upper().replace(".", "-")


def _extract_sp500_symbols(html: str) -> set[str]:
    table_match = re.search(
        r'<table[^>]*class="[^"]*wikitable[^"]*sortable[^"]*"[^>]*>(.*?)</table>',
        html,
        flags=re.IGNORECASE | re.DOTALL,
    )
    if not table_match:
        return set()
    table_html = table_match.group(1)
    symbols = re.findall(r'class="external text"[^>]*>\s*([A-Za-z.]+)\s*</a>', table_html)
    return {_normalize_us_calendar_ticker(symbol) for symbol in symbols if symbol}


@lru_cache(maxsize=1)
def _sp500_ticker_set() -> set[str]:
    try:
        response = requests.get(
            "https://en.wikipedia.org/wiki/List_of_S%26P_500_companies",
            headers={"User-Agent": "QuantBridge/1.0"},
            timeout=10,
        )
        response.raise_for_status()
        return _extract_sp500_symbols(response.text)
    except Exception as exc:
        _record_data_source("SP500_Constituents", "external_error", detail=f"{type(exc).__name__}: {exc}")
        return set()


def _us_calendar_allowed_tickers() -> set[str]:
    allowed: set[str] = set()
    portfolio = _load_storage_df("US_Final_Portfolio", market="US")
    if not portfolio.empty and "Ticker" in portfolio.columns:
        allowed.update(_normalize_us_calendar_ticker(ticker) for ticker in portfolio["Ticker"].tolist())
    allowed.update(_sp500_ticker_set())
    return {ticker for ticker in allowed if ticker}


def _earnings_calendar_sqlite_df() -> pd.DataFrame:
    path = _SETTINGS.api_sqlite_path
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
        _record_data_source("Earnings_Calendar", "sqlite_error", detail=f"{type(exc).__name__}: {exc}")
        return pd.DataFrame()


def _company_identity_lookup() -> dict[str, dict]:
    df = _load_table("Company_Master")
    if df.empty or "Ticker" not in df.columns:
        return {}
    lookup: dict[str, dict] = {}
    for _, row in df.iterrows():
        ticker = str(row.get("Ticker") or "").strip().upper()
        if not ticker:
            continue
        lookup[ticker] = row.to_dict()
        code = _kr_code(ticker)
        if code:
            lookup.setdefault(code, row.to_dict())
    return lookup


def _load_earnings_calendar_frame() -> tuple[pd.DataFrame, str]:
    df = _earnings_calendar_sqlite_df()
    if not df.empty:
        return df, "sqlite"

    df = _load_storage_df("Earnings_Calendar")
    if not df.empty:
        return df, "storage"

    try:
        data = _spreadsheet().worksheet("Earnings_Calendar").get_all_values()
    except Exception as exc:
        _record_data_source("Earnings_Calendar", "sheet_error", detail=f"{type(exc).__name__}: {exc}")
        return pd.DataFrame(), "empty"
    if len(data) < 2:
        _record_data_source("Earnings_Calendar", "sheet_empty", rows=0)
        return pd.DataFrame(), "empty"

    df = _sheet_values_to_df(data[1:], data[0])
    if not df.empty:
        _record_data_source("Earnings_Calendar", "sheet", rows=len(df))
        return df, "sheet"

    return pd.DataFrame(), "empty"


def _market_cap_text_to_float(value) -> float | None:
    text = _clean_meta_value(value)
    if not text or text == "-":
        return None
    cleaned = text.replace("$", "").replace(",", "").strip().upper()
    match = re.match(r"^(-?\d+(?:\.\d+)?)([KMBT])?$", cleaned)
    if not match:
        return _safe_float(cleaned)
    number = float(match.group(1))
    multiplier = {
        "K": 1e3,
        "M": 1e6,
        "B": 1e9,
        "T": 1e12,
        None: 1,
    }[match.group(2)]
    return number * multiplier


def _calendar_market_cap(market: str, *values) -> float | None:
    cap = _first_float(*values)
    if cap is None:
        return None
    market_key = str(market or "").strip().upper()
    if market_key == "US" and abs(cap) < 1_000_000:
        return None
    if market_key == "KR" and abs(cap) < 100_000_000:
        return None
    return cap


def _extract_yfinance_earnings_date(calendar) -> datetime | None:
    raw = None
    if isinstance(calendar, dict):
        raw = calendar.get("Earnings Date")
    elif isinstance(calendar, pd.DataFrame) and not calendar.empty and "Earnings Date" in calendar.index:
        raw = calendar.loc["Earnings Date"].iloc[0]

    values = raw if isinstance(raw, (list, tuple, pd.Series, pd.DatetimeIndex)) else [raw]
    for value in values:
        parsed = _parse_calendar_date(value)
        if parsed:
            return parsed
    return None


def _yahoo_earnings_calendar_seed_rows(day: datetime) -> list[dict]:
    """Fetch Yahoo's visible earnings table as ticker metadata only.

    Yahoo's HTML calendar currently returns the same undated table for multiple
    `day=` values in some environments. Treating the requested day as the event
    date creates false duplicates across the whole month, so this function only
    provides candidate tickers and enrichment fields. Actual dates are verified
    through ticker calendars before being exposed to the app.
    """
    date_key = day.strftime("%Y-%m-%d")
    url = f"https://finance.yahoo.com/calendar/earnings?day={date_key}&offset=0&size=100"
    try:
        response = requests.get(url, timeout=8, headers={"User-Agent": "QuantBridge/1.0"})
        response.raise_for_status()
        tables = pd.read_html(StringIO(response.text))
    except Exception as exc:
        _record_data_source("Earnings_Calendar", "yahoo_calendar_error", detail=f"{date_key}: {type(exc).__name__}")
        return []

    rows: list[dict] = []
    for table in tables:
        columns = {str(column).strip(): column for column in table.columns}
        if "Symbol" not in columns or "Company" not in columns:
            continue
        for _, row in table.iterrows():
            ticker = _clean_meta_value(row.get(columns["Symbol"]))
            name = _clean_meta_value(row.get(columns["Company"]))
            if not ticker or ticker == "-":
                continue
            rows.append({
                "Ticker": ticker.upper(),
                "Name": name or ticker.upper(),
                "Market": "US",
                "Sector": None,
                "MarketCap": _market_cap_text_to_float(row.get(columns.get("Market Cap"))),
                "Earnings_Call_Time": _clean_meta_value(row.get(columns.get("Earnings Call Time"))),
                "EPS_Estimate": _clean_meta_value(row.get(columns.get("EPS Estimate"))),
                "Yahoo_Page_Date": date_key,
            })
    return rows


def _verified_earnings_calendar_record(yf_module, seed: dict, today: datetime, end_date: datetime) -> dict | None:
    ticker = _clean_meta_value(seed.get("Ticker"))
    if not ticker:
        return None
    ticker = ticker.upper()
    try:
        ticker_obj = yf_module.Ticker(ticker)
        calendar = ticker_obj.calendar
    except Exception as exc:
        _record_data_source("Earnings_Calendar", "yfinance_calendar_symbol_error", detail=f"{ticker}: {type(exc).__name__}")
        return None

    event_date = _extract_yfinance_earnings_date(calendar)
    if not event_date or event_date < today or event_date > end_date:
        return None

    eps_estimate = seed.get("EPS_Estimate")
    if eps_estimate is None and isinstance(calendar, dict):
        eps_estimate = calendar.get("Earnings Average")

    info: dict = {}
    if seed.get("MarketCap") is None or not _clean_meta_value(seed.get("Name")) or not _clean_meta_value(seed.get("Sector")):
        try:
            info = ticker_obj.get_info() or {}
        except Exception as exc:
            _record_data_source("Earnings_Calendar", "yfinance_info_symbol_error", detail=f"{ticker}: {type(exc).__name__}")
            info = {}

    return {
        "Ticker": ticker,
        "Name": _clean_meta_value(seed.get("Name")) or _clean_meta_value(info.get("longName")) or _clean_meta_value(info.get("shortName")) or ticker,
        "Market": "US",
        "Sector": _clean_meta_value(seed.get("Sector")) or _clean_meta_value(info.get("sector")),
        "MarketCap": _first_float(seed.get("MarketCap"), info.get("marketCap")),
        "Next_Earnings_Date": event_date.strftime("%Y-%m-%d"),
        "Earnings_Call_Time": _clean_meta_value(seed.get("Earnings_Call_Time")),
        "EPS_Estimate": _clean_meta_value(eps_estimate),
    }


def _fetch_verified_earnings_calendar_df(today: datetime, days: int, limit: int) -> pd.DataFrame:
    # Empty-cache fallback: build a real date calendar from ticker-level calendars.
    # The scheduled main engine remains the primary source for the full universe.
    try:
        import yfinance as yf
    except Exception as exc:
        _record_data_source("Earnings_Calendar", "yfinance_calendar_unavailable", detail=type(exc).__name__)
        return pd.DataFrame()

    safe_days = max(1, min(days, 366))
    end_date = today + timedelta(days=safe_days)
    candidates: dict[str, dict] = {}
    for row in _yahoo_earnings_calendar_seed_rows(today):
        ticker = _clean_meta_value(row.get("Ticker"))
        if ticker:
            candidates[ticker.upper()] = row
    for ticker in sorted(_us_calendar_allowed_tickers()):
        candidates.setdefault(ticker, {"Ticker": ticker, "Market": "US"})

    candidate_rows = list(candidates.values())[:650]
    rows: list[dict] = []
    with ThreadPoolExecutor(max_workers=8) as executor:
        futures = [executor.submit(_verified_earnings_calendar_record, yf, seed, today, end_date) for seed in candidate_rows]
        for future in as_completed(futures):
            record = future.result()
            if record:
                rows.append(record)
            if len(rows) >= limit:
                break
    if not rows:
        _record_data_source("Earnings_Calendar", "verified_calendar_empty", rows=0)
        return pd.DataFrame()
    frame = pd.DataFrame(rows).drop_duplicates(subset=["Ticker", "Next_Earnings_Date"])
    _record_data_source("Earnings_Calendar", "yfinance_calendar_verified", rows=len(frame))
    return frame


def _build_earnings_calendar_rows(
    df: pd.DataFrame,
    market: str,
    today: datetime,
    end_date: datetime,
    restrict_us_universe: bool = True,
) -> list[dict]:
    if df.empty:
        return []

    df = _clean_dataframe_columns(df)
    ticker_col = next((c for c in ("Ticker", "ticker", "Symbol") if c in df.columns), None)
    date_col = next((c for c in ("Next_Earnings_Date", "Next_Earnings", "Earnings_Date", "earnings_date") if c in df.columns), None)
    if not ticker_col or not date_col:
        return []

    identities = _company_identity_lookup()
    allowed_us_tickers = _us_calendar_allowed_tickers() if restrict_us_universe and market in ("ALL", "US") else set()
    rows: list[dict] = []
    for _, row in df.iterrows():
        ticker = str(row.get(ticker_col) or "").strip().upper()
        if not ticker:
            continue
        date = _parse_calendar_date(row.get(date_col))
        if not date or date < today or date > end_date:
            continue
        item_market = str(row.get("Market") or "").strip().upper() or _infer_market_from_ticker(ticker)
        if market != "ALL" and item_market != market:
            continue
        if allowed_us_tickers and item_market == "US" and _normalize_us_calendar_ticker(ticker) not in allowed_us_tickers:
            continue

        identity_row = identities.get(ticker) or identities.get(_kr_code(ticker) or "")
        identity = _identity_payload_from_row(identity_row or {}, ticker, item_market, "Company_Master" if identity_row else "")
        raw_name = _first_text(row.get("Name"), identity.get("name"))
        sector = _first_text(row.get("Sector"), identity.get("sector"))
        market_cap = _first_float(
            _calendar_market_cap(item_market, row.get("MarketCap"), row.get("MarketCap_Last")),
            _calendar_market_cap(item_market, identity.get("market_cap")),
        )
        naver_identity = {}
        if item_market == "KR" and (
            _is_missing_kr_name(raw_name, ticker)
            or market_cap is None
            or abs(market_cap) < 1_000_000
        ):
            naver_identity = _naver_kr_identity(ticker)
        name = raw_name
        if item_market == "KR" and _is_missing_kr_name(name, ticker):
            name = _first_text(naver_identity.get("Name"), ticker)
        else:
            name = _first_text(name, ticker)
        if item_market == "KR":
            market_cap = _first_float(
                _calendar_market_cap(item_market, naver_identity.get("MarketCap")),
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


def _earnings_calendar_payload(market: str, days: int, limit: int) -> dict:
    today = datetime.now(timezone(timedelta(hours=9))).replace(tzinfo=None)
    today = datetime(today.year, today.month, today.day)
    end_date = today + timedelta(days=days)

    df, source = _load_earnings_calendar_frame()
    if df.empty and market in ("ALL", "US"):
        fallback_df = _fetch_verified_earnings_calendar_df(today, days, limit)
        if not fallback_df.empty:
            df, source = fallback_df, "yfinance_verified_calendar"
    if df.empty:
        return {
            "items": [],
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "source": source,
            "total": 0,
        }

    rows = _build_earnings_calendar_rows(
        df,
        market,
        today,
        end_date,
        restrict_us_universe=source not in ("yahoo_finance_calendar", "yfinance_verified_calendar"),
    )
    if not rows and source not in ("yahoo_finance_calendar", "yfinance_verified_calendar") and market in ("ALL", "US"):
        fallback_df = _fetch_verified_earnings_calendar_df(today, days, limit)
        if not fallback_df.empty:
            source = "yfinance_verified_calendar"
            rows = _build_earnings_calendar_rows(
                fallback_df,
                market,
                today,
                end_date,
                restrict_us_universe=False,
            )

    rows.sort(key=_earnings_calendar_sort_key)
    rows = rows[:limit]
    return {
        "items": rows,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "source": source,
        "total": len(rows),
    }


def _earnings_calendar_sort_key(item: dict):
    market_cap = _safe_float(item.get("MarketCap"))
    cap_order = -market_cap if market_cap is not None else float("inf")
    return (
        item.get("Next_Earnings_Date") or "",
        cap_order,
        item.get("Market") or "",
        item.get("Ticker") or "",
    )


def _earnings_calendar_response(market: str = "ALL", days: int = 180, limit: int = 200, refresh: bool = False):
    safe_market = str(market or "ALL").upper()
    if safe_market not in ("ALL", "US", "KR"):
        raise HTTPException(400, "market must be ALL, US, or KR")
    safe_days = max(1, min(int(days or 180), 366))
    safe_limit = max(1, min(int(limit or 200), 2000))
    cache_key = f"earnings_calendar_{safe_market}_{safe_days}_{safe_limit}"
    if refresh:
        _invalidate(cache_key)
    return _cached(
        cache_key,
        lambda: _earnings_calendar_payload(safe_market, safe_days, safe_limit),
        ttl=900,
    )


def calendar_earnings(market: str = "ALL", days: int = 180, limit: int = 200, refresh: bool = False):
    return _earnings_calendar_response(market=market, days=days, limit=limit, refresh=refresh)


def earnings_calendar(market: str = "ALL", days: int = 180, limit: int = 200, refresh: bool = False):
    return _earnings_calendar_response(market=market, days=days, limit=limit, refresh=refresh)


def earnings(market: str):
    market = market.upper()
    if market not in ("US", "KR"):
        raise HTTPException(400, "market must be US or KR")

    sheet   = f"{market}_Earnings_Momentum"
    num_cols = [
        "Surprise_Pct", "Signal_Strength", "Return_Since",
        "Volume_Surge", "Days_Since_Earnings",
        "Actual_EPS", "Estimated_EPS", "MarketCap", "Rank",
    ]

    def load():
        stocks = _load_simple(sheet, num_cols)
        if market == "KR":
            stocks = _enrich_kr_company_identities(stocks)
        return {"stocks": stocks}

    return _cached(f"earn_{market}_v2", load)


def macro():
    return _cached("macro", _macro_payload)


_MARKET_INDICATOR_SPECS = [
    {"symbol": "^IXIC", "label": "나스닥", "category": "index_fx", "region": "overseas", "sort_order": 10},
    {"symbol": "NQ=F", "label": "나스닥 100 선물", "category": "index_fx", "region": "overseas", "sort_order": 20},
    {"symbol": "^GSPC", "label": "S&P 500", "category": "index_fx", "region": "overseas", "sort_order": 30},
    {"symbol": "ES=F", "label": "S&P 500 선물", "category": "index_fx", "region": "overseas", "sort_order": 40},
    {"symbol": "RTY=F", "label": "러셀 2000 선물", "category": "index_fx", "region": "overseas", "sort_order": 50},
    {"symbol": "^DJI", "label": "다우존스", "category": "index_fx", "region": "overseas", "sort_order": 60},
    {"symbol": "^SOX", "label": "필라델피아 반도체", "category": "index_fx", "region": "overseas", "sort_order": 70},
    {"symbol": "^VIX", "label": "VIX", "category": "index_fx", "region": "overseas", "sort_order": 80},
    {"symbol": "KRW=X", "label": "달러 환율", "category": "index_fx", "region": "domestic", "sort_order": 90},
    {"symbol": "DX-Y.NYB", "label": "달러 인덱스", "category": "index_fx", "region": "overseas", "sort_order": 100},
    {"symbol": "^KS11", "label": "KOSPI", "category": "index_fx", "region": "domestic", "sort_order": 110},
    {"symbol": "^KQ11", "label": "KOSDAQ", "category": "index_fx", "region": "domestic", "sort_order": 120},
    {"symbol": "^IRX", "label": "미 3개월 국채", "category": "bond", "region": "overseas", "sort_order": 10},
    {"symbol": "^FVX", "label": "미 5년 국채", "category": "bond", "region": "overseas", "sort_order": 20},
    {"symbol": "^TNX", "label": "미 10년 국채", "category": "bond", "region": "overseas", "sort_order": 30},
    {"symbol": "^TYX", "label": "미 30년 국채", "category": "bond", "region": "overseas", "sort_order": 40},
    {"symbol": "IRR_GOVT03Y", "label": "국고채 3년", "category": "bond", "region": "domestic", "sort_order": 50},
    {"symbol": "IRR_CORP03Y", "label": "회사채 3년", "category": "bond", "region": "domestic", "sort_order": 60},
    {"symbol": "GC=F", "label": "금 선물", "category": "commodity", "region": "global", "sort_order": 10},
    {"symbol": "SI=F", "label": "은 선물", "category": "commodity", "region": "global", "sort_order": 20},
    {"symbol": "CL=F", "label": "WTI 원유", "category": "commodity", "region": "global", "sort_order": 30},
    {"symbol": "HG=F", "label": "구리", "category": "commodity", "region": "global", "sort_order": 40},
    {"symbol": "BTC-USD", "label": "비트코인", "category": "crypto", "region": "global", "sort_order": 10},
    {"symbol": "ETH-USD", "label": "이더리움", "category": "crypto", "region": "global", "sort_order": 20},
    {"symbol": "SOL-USD", "label": "솔라나", "category": "crypto", "region": "global", "sort_order": 30},
]
_MARKET_INDICATOR_SYMBOLS = [item["symbol"] for item in _MARKET_INDICATOR_SPECS]
_LEGACY_INDEX_SYMBOLS = {"^GSPC", "^IXIC", "^KS11", "^KQ11"}
_US_REGULAR_INDEX_SYMBOLS = {"^GSPC", "^IXIC", "^DJI", "^SOX", "^VIX"}
_KR_REGULAR_INDEX_SYMBOLS = {"^KS11", "^KQ11"}
_US_MARKET_TZ = ZoneInfo("America/New_York")
_NAVER_DOMESTIC_INDEX_CODES = {"^KS11": "KOSPI", "^KQ11": "KOSDAQ"}
_NAVER_DOMESTIC_INTEREST_CODES = {
    "IRR_GOVT03Y": "IRR_GOVT03Y",
    "IRR_CORP03Y": "IRR_CORP03Y",
}
_MARKET_HISTORY_PERIODS = {"1d": 1, "5d": 5, "1mo": 31, "3mo": 93}
_MARKET_HISTORY_INTERVALS = {"1m", "2m", "5m", "15m", "30m", "60m", "1h", "1d"}
_KST = timezone(timedelta(hours=9))


_INDEX_SPECS = [
    ("^GSPC", "S&P 500"),
    ("^IXIC", "NASDAQ"),
    ("^KS11", "KOSPI"),
    ("^KQ11", "KOSDAQ"),
]


def _market_indices_payload() -> dict:
    now = datetime.now().strftime("%Y-%m-%d %H:%M")
    try:
        import yfinance as yf
    except ImportError:
        return {"indices": [], "updated_at": now, "error": "yfinance is not installed"}

    items = []
    for symbol, label in _INDEX_SPECS:
        try:
            ticker = yf.Ticker(symbol)
            if _should_use_latest_regular_close(symbol):
                spec = {"symbol": symbol, "label": label, "category": "index_fx", "region": "global", "sort_order": 0}
                quote = _indicator_daily_close_quote_from_yfinance(ticker, spec, regular_session=True)
                if quote:
                    items.append({
                        "symbol": symbol,
                        "label": label,
                        "value": quote["value"],
                        "change_abs": quote["change_abs"],
                        "change_pct": quote["change_pct"],
                        "updated_at": quote["updated_at"],
                    })
                    continue

            fast_info = getattr(ticker, "fast_info", {}) or {}
            last = _safe_float(_fast_info_get(fast_info, "last_price"))
            previous = _safe_float(_fast_info_get(fast_info, "previous_close"))

            if last is None or previous is None or previous == 0:
                history = ticker.history(period="5d", interval="1d", auto_adjust=False)
                closes = history["Close"].dropna() if "Close" in history else pd.Series(dtype=float)
                if len(closes) >= 2:
                    last = _safe_float(closes.iloc[-1])
                    previous = _safe_float(closes.iloc[-2])

            if last is None or previous is None or previous == 0:
                continue

            change_abs = last - previous
            items.append({
                "symbol": symbol,
                "label": label,
                "value": last,
                "change_abs": change_abs,
                "change_pct": change_abs / previous,
                "updated_at": now,
            })
        except Exception:
            continue

    return {"indices": items, "updated_at": now}


def _fast_info_get(info, key: str):
    try:
        return info.get(key)
    except AttributeError:
        try:
            return info[key]
        except Exception:
            return None


def _market_indicators_payload(category: str = "all", refresh: bool = False) -> dict:
    cache_key = f"market_indicators_{category}"
    if refresh:
        _invalidate(cache_key)
        payload = _load_market_indicators(category, refresh=True)
        _write_app_snapshot(cache_key, payload)
        return payload
    return _cached(cache_key, lambda: _load_market_indicators(category, refresh=refresh), ttl=60, stale_ttl=0)


def _etf_storage_records() -> list[dict]:
    df = _load_storage_df("ETF_Insights", market=None)
    return [] if df.empty else _df_to_records(df)


def _etf_price_lookup_ticker(item: dict) -> tuple[str, str]:
    region = str(item.get("region") or item.get("Market") or item.get("market") or "US").strip().upper()
    ticker = str(item.get("ticker") or item.get("Ticker") or "").strip().upper()
    if region == "KR":
        code = _kr_code(ticker)
        if code and not ticker.endswith((".KS", ".KQ")):
            ticker = f"{code}.KS"
    return region, ticker


def _price_change_from_return(current_price: float | None, return_1m: float | None) -> float | None:
    if current_price is None or return_1m is None or return_1m <= -0.999:
        return None
    base_price = current_price / (1 + return_1m)
    return current_price - base_price


def _enrich_etf_price_fields(items: list[dict]) -> list[dict]:
    tickers_by_market: dict[str, list[str]] = {}
    lookup_by_item: list[tuple[dict, str, str]] = []
    for item in items:
        market, ticker = _etf_price_lookup_ticker(item)
        if not ticker:
            continue
        tickers_by_market.setdefault(market, []).append(ticker)
        lookup_by_item.append((item, market, ticker))

    snapshots_by_market: dict[str, dict[str, dict]] = {}
    history_metrics_by_market: dict[str, dict[str, tuple[float | None, float | None]]] = {}
    daily_changes_by_market: dict[str, dict[str, tuple[float | None, str]]] = {}
    for market, tickers in tickers_by_market.items():
        snapshots = _portfolio_price_snapshot_batch(tickers, market)
        snapshots_by_market[market] = snapshots
        daily_changes_by_market[market] = _portfolio_daily_change_batch(tickers, market, snapshots)
        missing = [
            ticker for ticker in tickers
            if not snapshots.get(ticker.upper())
            or (
                snapshots[ticker.upper()].get("current_price") is None
                or snapshots[ticker.upper()].get("return_1m") is None
            )
        ]
        history_metrics_by_market[market] = _portfolio_price_metrics_batch(missing, market)

    for item, market, ticker in lookup_by_item:
        ticker_key = ticker.upper()
        snapshot = snapshots_by_market.get(market, {}).get(ticker.upper(), {})
        current_price = _first_float(item.get("currentPrice"), item.get("Current_Price"), snapshot.get("current_price"))
        return_1m = _first_float(item.get("return1M"), item.get("Return_1M"), snapshot.get("return_1m"))
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
        price_change = _price_change_from_return(current_price, return_1m)
        if price_change is not None:
            item["priceChange"] = price_change
        daily_change, daily_horizon = daily_changes_by_market.get(market, {}).get(ticker_key, (None, ""))
        if daily_change is not None:
            item["dailyChangePct"] = daily_change
            item["Daily_Change_Pct"] = daily_change
            daily_price_change = _price_change_from_return(current_price, daily_change)
            if daily_price_change is not None:
                item["dailyPriceChange"] = daily_price_change
                item["Daily_Price_Change"] = daily_price_change
        if daily_horizon:
            item["dailyChangeHorizon"] = daily_horizon
            item["Daily_Change_Horizon"] = daily_horizon
    return items


def _etf_payload_with_prices(*, market: str, category: str, q: str, limit: int) -> dict:
    payload = _etf_payload_from_records(
        _etf_storage_records(),
        market=market,
        category=category,
        q=q,
        limit=limit,
    )
    clean_q = str(q or "").strip()
    if clean_q:
        existing = {str(item.get("ticker") or "").strip().upper() for item in payload.get("items", [])}
        extra_items = []
        for item in _yahoo_etf_search_records(clean_q, limit=12):
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
    payload["items"] = _enrich_etf_price_fields(payload.get("items", []))
    return payload


def _load_market_indicators(category: str, refresh: bool = False) -> dict:
    specs = _indicator_specs(category)
    symbols = [spec["symbol"] for spec in specs]
    stored = _stored_indicator_latest(symbols)
    if stored and not refresh:
        items = _stored_indicator_items(specs, stored)
        if items:
            items = _merge_naver_domestic_indicator_quotes(items, specs)
            if _allow_api_external_fetch():
                items = _merge_closed_regular_index_quotes(items, specs)
            return {
                "items": items,
                "count": len(items),
                "updated_at": _utc_now_iso(),
                "source": "storage",
                "errors": [],
            }

    if not _allow_api_external_fetch():
        items = _stored_indicator_items(specs, stored)
        items = _merge_naver_domestic_indicator_quotes(items, specs)
        return {
            "items": items,
            "count": len(items),
            "updated_at": _utc_now_iso(),
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
            if _is_naver_domestic_interest_symbol(spec["symbol"]):
                continue
            try:
                quote = _indicator_quote_from_yfinance(yf, spec)
                if quote:
                    items.append(quote)
            except Exception as exc:
                errors.append(f"{spec['symbol']}: {type(exc).__name__}")

    by_symbol = {item["symbol"]: item for item in items}
    for item in _load_naver_market_indicator_quotes(specs):
        by_symbol[item["symbol"]] = item
    for spec in specs:
        if spec["symbol"] not in by_symbol and spec["symbol"] in stored:
            by_symbol[spec["symbol"]] = {**stored[spec["symbol"]], **_spec_public_fields(spec)}

    items = sorted(by_symbol.values(), key=lambda item: int(item.get("sort_order") or 999))
    items = _merge_closed_regular_index_quotes(items, specs)
    if items:
        try:
            _repository().upsert_market_indicators(items, source="yfinance")
        except Exception:
            pass

    return {
        "items": items,
        "count": len(items),
        "updated_at": _utc_now_iso(),
        "source": "yfinance+storage" if stored else "yfinance",
        "errors": errors[:8],
    }


def _stored_indicator_items(specs: list[dict], stored: dict[str, dict]) -> list[dict]:
    items = []
    for spec in specs:
        item = stored.get(spec["symbol"])
        if not item:
            continue
        items.append(_with_regular_previous_close_change_from_storage({**item, **_spec_public_fields(spec)}))
    return sorted(items, key=lambda item: int(item.get("sort_order") or 999))


def _with_regular_previous_close_change_from_storage(item: dict) -> dict:
    symbol = str(item.get("symbol") or "").strip().upper()
    if _regular_index_zone(symbol) is None:
        return item
    current = _first_float(item.get("value"), item.get("close"))
    previous_close = _previous_regular_close_from_storage(symbol, item.get("observed_at") or item.get("updated_at"))
    if current is None or previous_close is None or previous_close <= 0:
        return item
    return {
        **item,
        "change_abs": current - previous_close,
        "change_pct": current / previous_close - 1.0,
    }


def _previous_regular_close_from_storage(symbol: str, observed_at: object) -> float | None:
    zone = _regular_index_zone(symbol)
    if zone is None:
        return None
    observed = _indicator_observed_datetime({"observed_at": observed_at}) or datetime.now(timezone.utc)
    local_date = observed.astimezone(zone).date()
    try:
        df = _repository().read_market_indicator_history(
            symbols=[symbol],
            start_at=observed - timedelta(days=10),
        )
    except Exception:
        return None
    if df.empty:
        return None
    candidates: list[tuple[datetime, float]] = []
    for record in _df_to_records(df):
        record_time = _indicator_observed_datetime(record)
        price = _first_float(record.get("open"), record.get("close"), record.get("value"))
        if record_time is None or price is None:
            continue
        local_time = record_time.astimezone(zone)
        if local_time.date() < local_date:
            previous_price = _first_float(record.get("close"), record.get("value"), record.get("open"))
            if previous_price is not None:
                candidates.append((record_time, previous_price))
    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0])
    return candidates[-1][1]


def _merge_naver_domestic_indicator_quotes(items: list[dict], specs: list[dict]) -> list[dict]:
    overrides = {item["symbol"]: item for item in _load_naver_market_indicator_quotes(specs)}
    if not overrides:
        return items
    merged = [overrides.get(str(item.get("symbol") or ""), item) for item in items]
    return sorted(merged, key=lambda item: int(item.get("sort_order") or 999))


def _merge_closed_regular_index_quotes(items: list[dict], specs: list[dict]) -> list[dict]:
    closed_specs = [
        spec
        for spec in specs
        if _should_use_latest_regular_close(spec["symbol"])
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
            quote = _indicator_daily_close_quote_from_yfinance(ticker, spec, regular_session=True)
            if quote and not _indicator_quote_is_older(quote, existing_by_symbol.get(spec["symbol"])):
                overrides[spec["symbol"]] = quote
        except Exception:
            continue
    if not overrides:
        return items
    merged = [overrides.get(str(item.get("symbol") or ""), item) for item in items]
    return sorted(merged, key=lambda item: int(item.get("sort_order") or 999))


def _load_naver_market_indicator_quotes(specs: list[dict]) -> list[dict]:
    return [
        *_load_naver_domestic_indicator_quotes(specs),
        *_load_naver_domestic_interest_quotes(specs),
    ]


def _naver_domestic_index_observed_at(row: dict, symbol: str) -> str:
    traded_at = str(row.get("localTradedAt") or "").strip()
    if not traded_at:
        return _utc_now_iso()
    observed = _indicator_observed_datetime({"observed_at": traded_at})
    zone = _regular_index_zone(symbol)
    if observed is None or zone is None:
        return traded_at

    local = observed.astimezone(zone)
    _, close_time = _regular_index_session_times(symbol)
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


def _load_naver_domestic_indicator_quotes(specs: list[dict]) -> list[dict]:
    wanted_specs = [spec for spec in specs if spec["symbol"] in _NAVER_DOMESTIC_INDEX_CODES]
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
        row = rows_by_code.get(_NAVER_DOMESTIC_INDEX_CODES[spec["symbol"]])
        if not row:
            continue
        value = _naver_indicator_float(row.get("closePriceRaw") or row.get("closePrice"))
        change_abs = _naver_indicator_float(
            row.get("compareToPreviousClosePriceRaw") or row.get("compareToPreviousClosePrice")
        )
        change_pct_percent = _naver_indicator_float(row.get("fluctuationsRatioRaw") or row.get("fluctuationsRatio"))
        if value is None:
            continue
        observed_at = _naver_domestic_index_observed_at(row, spec["symbol"])
        items.append({
            **_spec_public_fields(spec),
            "value": value,
            "change_abs": change_abs,
            "change_pct": change_pct_percent / 100 if change_pct_percent is not None else None,
            "close": value,
            "observed_at": observed_at,
            "updated_at": observed_at,
            "source": "naver",
        })
    return items


def _load_naver_domestic_interest_quotes(specs: list[dict]) -> list[dict]:
    wanted_specs = [spec for spec in specs if _is_naver_domestic_interest_symbol(spec["symbol"])]
    if not wanted_specs:
        return []

    items = []
    for spec in wanted_specs:
        rows = _fetch_naver_interest_rows(spec["symbol"], pages=1)
        if not rows:
            continue
        row = rows[0]
        value = _safe_float(row.get("value"))
        if value is None:
            continue
        observed_at = _naver_interest_observed_at(str(row.get("date") or ""))
        items.append({
            **_spec_public_fields(spec),
            "value": value,
            "change_abs": _safe_float(row.get("change_abs")),
            "change_pct": _safe_float(row.get("change_pct")),
            "open": value,
            "high": value,
            "low": value,
            "close": value,
            "observed_at": observed_at,
            "updated_at": observed_at,
            "source": "naver",
        })
    return items


def _is_naver_domestic_interest_symbol(symbol: str) -> bool:
    return str(symbol or "").strip().upper() in _NAVER_DOMESTIC_INTEREST_CODES


def _fetch_naver_interest_rows(symbol: str, pages: int = 1) -> list[dict]:
    code = _NAVER_DOMESTIC_INTEREST_CODES.get(str(symbol or "").strip().upper())
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
        rows.extend(_parse_naver_interest_rows(body))
    return rows


def _parse_naver_interest_rows(body: str) -> list[dict]:
    parsed: list[dict] = []
    for match in re.finditer(r'<tr\s+class="(?P<direction>[^"]+)">(?P<body>.*?)</tr>', body or "", re.DOTALL):
        cells = re.findall(r"<td[^>]*>(.*?)</td>", match.group("body"), re.DOTALL)
        if len(cells) < 4:
            continue
        date_text = _clean_html_cell(cells[0])
        value = _naver_indicator_float(_clean_html_cell(cells[1]))
        change_abs = _naver_indicator_float(_clean_html_cell(cells[2]))
        change_pct_percent = _naver_indicator_float(_clean_html_cell(cells[3]))
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


def _clean_html_cell(value: str) -> str:
    text = re.sub(r"<[^>]+>", " ", value or "")
    return re.sub(r"\s+", " ", html.unescape(text)).strip()


def _naver_interest_observed_at(date_text: str) -> str:
    try:
        observed = datetime.strptime(str(date_text).strip(), "%Y.%m.%d").replace(
            hour=15,
            minute=30,
            tzinfo=_KST,
        )
        return observed.isoformat()
    except ValueError:
        return _utc_now_iso()


def _naver_indicator_float(value) -> float | None:
    if value is None:
        return None
    text = str(value).replace(",", "").replace("%", "").replace("+", "").strip()
    return _safe_float(re.sub(r"\s+", "", text))


def _indicator_specs(category: str = "all") -> list[dict]:
    if category in {"", "all", "ALL"}:
        return list(_MARKET_INDICATOR_SPECS)
    return [spec for spec in _MARKET_INDICATOR_SPECS if spec["category"] == category]


def _spec_public_fields(spec: dict) -> dict:
    return {
        "symbol": spec["symbol"],
        "label": spec["label"],
        "category": spec["category"],
        "region": spec["region"],
        "sort_order": spec["sort_order"],
    }


def _indicator_quote_from_yfinance(yf, spec: dict) -> dict | None:
    ticker = yf.Ticker(spec["symbol"])
    if _should_use_latest_regular_close(spec["symbol"]):
        daily_quote = _indicator_daily_close_quote_from_yfinance(ticker, spec, regular_session=True)
        if daily_quote:
            return daily_quote

    if _is_regular_index_session_open(spec["symbol"]):
        intraday_quote = _indicator_intraday_quote_from_yfinance(ticker, spec)
        if intraday_quote:
            return intraday_quote

    fast_info = getattr(ticker, "fast_info", {}) or {}
    last = _safe_float(_fast_info_get(fast_info, "last_price"))
    previous = _safe_float(_fast_info_get(fast_info, "previous_close"))

    if last is None or previous is None or previous == 0:
        daily_quote = _indicator_daily_close_quote_from_yfinance(
            ticker,
            spec,
            regular_session=_regular_index_zone(spec["symbol"]) is not None,
        )
        if daily_quote:
            return daily_quote

    if last is None or previous is None or previous == 0:
        return None

    change_abs = last - previous
    payload = {
        **_spec_public_fields(spec),
        "value": last,
        "change_abs": change_abs,
        "change_pct": change_abs / previous,
        "close": last,
        "observed_at": _utc_now_iso(),
        "updated_at": datetime.now().strftime("%Y-%m-%d %H:%M"),
        "source": "yfinance",
    }
    if _regular_index_zone(spec["symbol"]) is not None:
        session_open = _is_regular_index_session_open(spec["symbol"])
        payload["is_regular_session_open"] = session_open
        payload["session"] = "open" if session_open else "closed"
    return payload


def _indicator_intraday_quote_from_yfinance(ticker, spec: dict) -> dict | None:
    symbol = str(spec.get("symbol") or "").strip().upper()
    zone = _regular_index_zone(symbol)
    if zone is None:
        return None

    previous = _previous_regular_close_from_yfinance(ticker, symbol)
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
        last = _safe_float(row.get("Close"))
        if last is None:
            continue
        change_abs = last - previous
        return {
            **_spec_public_fields(spec),
            "value": last,
            "change_abs": change_abs,
            "change_pct": change_abs / previous,
            "open": _safe_float(row.get("Open")) or last,
            "high": _safe_float(row.get("High")) or last,
            "low": _safe_float(row.get("Low")) or last,
            "close": last,
            "volume": _safe_float(row.get("Volume")),
            "observed_at": observed_dt.isoformat(),
            "updated_at": observed_dt.isoformat(),
            "source": f"yfinance_intraday_{interval}",
            "is_regular_session_open": True,
            "session": "open",
        }
    return None


def _previous_regular_close_from_yfinance(ticker, symbol: str) -> float | None:
    zone = _regular_index_zone(symbol) or timezone.utc
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
            close = _safe_float(close_value)
            if close is not None:
                return close
    return None


def _indicator_daily_close_quote_from_yfinance(ticker, spec: dict, regular_session: bool = False) -> dict | None:
    history = ticker.history(period="10d", interval="1d", auto_adjust=False)
    if history is None or history.empty or "Close" not in history:
        return None
    frame = history.dropna(subset=["Close"])
    if frame.empty:
        return None

    row = frame.iloc[-1]
    last = _safe_float(row.get("Close"))
    open_price = _safe_float(row.get("Open"))
    previous = None
    if len(frame) >= 2:
        previous = _safe_float(frame["Close"].iloc[-2])

    base = previous
    if last is None or base is None or base == 0:
        return None

    change_abs = last - base
    observed_at = _daily_index_observed_at(frame.index[-1], spec["symbol"]) if regular_session else _utc_now_iso()
    payload = {
        **_spec_public_fields(spec),
        "value": last,
        "change_abs": change_abs,
        "change_pct": change_abs / base,
        "open": open_price if open_price is not None else last,
        "high": _safe_float(row.get("High")) or last,
        "low": _safe_float(row.get("Low")) or last,
        "close": last,
        "observed_at": observed_at,
        "updated_at": observed_at,
        "source": "yfinance_daily_close",
    }
    if regular_session:
        payload["is_regular_session_open"] = False
        payload["session"] = "closed"
    return payload


def _should_use_latest_regular_close(symbol: str) -> bool:
    symbol = str(symbol or "").strip().upper()
    if symbol not in _US_REGULAR_INDEX_SYMBOLS:
        return False
    return not _is_regular_index_session_open(symbol)


def _is_regular_index_session_open(symbol: str, now: datetime | None = None) -> bool:
    zone = _regular_index_zone(symbol)
    if zone is None:
        return False
    local = (now or datetime.now(timezone.utc)).astimezone(zone)
    if local.weekday() >= 5:
        return False
    open_time, close_time = _regular_index_session_times(symbol)
    return open_time <= local.time() <= close_time


def _regular_index_zone(symbol: str):
    symbol = str(symbol or "").strip().upper()
    if symbol in _US_REGULAR_INDEX_SYMBOLS:
        return _US_MARKET_TZ
    if symbol in _KR_REGULAR_INDEX_SYMBOLS:
        return _KST
    return None


def _regular_index_session_times(symbol: str) -> tuple[time, time]:
    symbol = str(symbol or "").strip().upper()
    if symbol in _KR_REGULAR_INDEX_SYMBOLS:
        return time(9, 0), time(15, 30)
    return time(9, 30), time(16, 0)


def _daily_index_observed_at(index_value, symbol: str) -> str:
    zone = _regular_index_zone(symbol) or timezone.utc
    _, close_time = _regular_index_session_times(symbol)
    try:
        parsed = pd.to_datetime(index_value, errors="coerce")
        if pd.isna(parsed):
            return _utc_now_iso()
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
        return _utc_now_iso()


def _indicator_quote_is_older(candidate: dict | None, existing: dict | None) -> bool:
    candidate_time = _indicator_observed_datetime(candidate)
    existing_time = _indicator_observed_datetime(existing)
    return candidate_time is not None and existing_time is not None and candidate_time < existing_time


def _indicator_observed_datetime(item: dict | None) -> datetime | None:
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


def _stored_indicator_latest(symbols: list[str]) -> dict[str, dict]:
    df = _repository().read_market_indicator_latest(symbols=symbols)
    if df.empty:
        return {}
    records = _df_to_records(df)
    return {
        str(item.get("symbol") or "").upper(): {
            "symbol": str(item.get("symbol") or "").upper(),
            "label": item.get("label"),
            "category": item.get("category"),
            "region": item.get("region"),
            "value": _safe_float(item.get("value")),
            "change_abs": _safe_float(item.get("change_abs")),
            "change_pct": _safe_float(item.get("change_pct")),
            "close": _safe_float(item.get("close") or item.get("value")),
            "observed_at": item.get("observed_at"),
            "updated_at": item.get("observed_at"),
            "source": item.get("source") or "storage",
        }
        for item in records
        if item.get("symbol") and _safe_float(item.get("value")) is not None
    }


def _selected_indicator_symbols(raw: str) -> list[str]:
    allowed = set(_MARKET_INDICATOR_SYMBOLS)
    requested = [item.strip().upper() for item in str(raw or "").split(",") if item.strip()]
    selected = [symbol for symbol in requested if symbol in allowed]
    return selected or list(_MARKET_INDICATOR_SYMBOLS)


def _latest_naver_domestic_indicator_history_points(symbols: list[str]) -> list[dict]:
    wanted = {str(symbol or "").strip().upper() for symbol in symbols}
    specs = [
        spec
        for spec in _MARKET_INDICATOR_SPECS
        if spec["symbol"] in wanted and spec["symbol"] in _NAVER_DOMESTIC_INDEX_CODES
    ]
    if not specs:
        return []
    points = []
    for quote in _load_naver_domestic_indicator_quotes(specs):
        observed_at = quote.get("observed_at")
        value = _safe_float(quote.get("value") or quote.get("close"))
        if not observed_at or value is None:
            continue
        points.append({
            **_spec_public_fields(quote),
            "value": value,
            "open": value,
            "high": value,
            "low": value,
            "close": value,
            "volume": None,
            "change_abs": _safe_float(quote.get("change_abs")),
            "change_pct": _safe_float(quote.get("change_pct")),
            "observed_at": observed_at,
            "updated_at": observed_at,
            "source": quote.get("source") or "naver",
        })
    return points


def _merge_indicator_history_records(records: list[dict], latest_points: list[dict]) -> list[dict]:
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


def _market_indicator_history_payload(symbols: list[str], period: str, interval: str, refresh: bool = False) -> dict:
    start_at = datetime.now(timezone.utc) - timedelta(days=_MARKET_HISTORY_PERIODS[period])
    stored = pd.DataFrame()
    if not refresh or not _allow_api_external_fetch():
        stored = _repository().read_market_indicator_history(symbols=symbols, start_at=start_at)

    fetched: list[dict] = []
    if _allow_api_external_fetch() and (refresh or stored.empty or _stored_indicator_history_is_sparse(stored, symbols, interval)):
        fetched = _fetch_indicator_history(symbols, period, interval)
        if fetched:
            try:
                _repository().upsert_market_indicators(fetched, source="yfinance")
            except Exception:
                pass
            stored = pd.DataFrame(fetched)

    if stored.empty:
        records: list[dict] = []
    else:
        records = _df_to_records(stored)

    latest_points = _latest_naver_domestic_indicator_history_points(symbols) if period == "1d" else []
    records = _merge_indicator_history_records(records, latest_points)

    spec_by_symbol = {spec["symbol"]: spec for spec in _MARKET_INDICATOR_SPECS}
    series = []
    for symbol in symbols:
        spec = spec_by_symbol.get(symbol, {"symbol": symbol, "label": symbol, "category": "", "region": "", "sort_order": 999})
        points = [
            {
                "timestamp": item.get("observed_at") or item.get("timestamp"),
                "open": _safe_float(item.get("open")),
                "high": _safe_float(item.get("high")),
                "low": _safe_float(item.get("low")),
                "close": _safe_float(item.get("close") or item.get("value")),
                "volume": _safe_float(item.get("volume")),
            }
            for item in records
            if str(item.get("symbol") or "").upper() == symbol
        ]
        points = [point for point in points if point["timestamp"] and point["close"] is not None]
        points = _filter_indicator_history_points_for_period(symbol, points, period)
        series.append({**_spec_public_fields(spec), "points": points})

    if not stored.empty:
        source = "storage" if not fetched else "yfinance"
    elif latest_points:
        source = "naver"
    else:
        source = "storage_miss" if not _allow_api_external_fetch() else "yfinance"

    return {
        "period": period,
        "interval": interval,
        "series": series,
        "updated_at": _utc_now_iso(),
        "source": source,
    }


def _filter_indicator_history_points_for_period(symbol: str, points: list[dict], period: str) -> list[dict]:
    if period != "1d" or not points:
        return points
    zone = _regular_index_zone(symbol)
    if zone is None:
        return points

    open_time, close_time = _regular_index_session_times(symbol)
    parsed: list[tuple[datetime, dict]] = []
    for point in points:
        observed = _indicator_observed_datetime({"observed_at": point.get("timestamp")})
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


def _stored_indicator_history_is_sparse(stored: pd.DataFrame, symbols: list[str], interval: str) -> bool:
    if stored.empty or not str(interval).lower().endswith("m"):
        return False

    symbol_column = "symbol" if "symbol" in stored.columns else "Symbol" if "Symbol" in stored.columns else None
    close_column = next((name for name in ("close", "Close", "value", "Value") if name in stored.columns), None)
    time_column = next((name for name in ("observed_at", "timestamp", "Updated_At", "Last_Updated") if name in stored.columns), None)
    if symbol_column is None or close_column is None:
        return True

    domestic_intraday_symbols = {"^KS11", "^KQ11"}
    for symbol in symbols:
        rows = stored[stored[symbol_column].astype(str).str.upper() == symbol]
        if rows.empty:
            return True
        closes = []
        for value in rows[close_column].dropna().tolist():
            close = _safe_float(value)
            if close is not None:
                closes.append(round(close, 6))
        if _is_naver_domestic_interest_symbol(symbol):
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


def _fetch_indicator_history(symbols: list[str], period: str, interval: str) -> list[dict]:
    naver_interest_symbols = [symbol for symbol in symbols if _is_naver_domestic_interest_symbol(symbol)]
    yfinance_symbols = [symbol for symbol in symbols if not _is_naver_domestic_interest_symbol(symbol)]
    points = _fetch_naver_domestic_interest_history(naver_interest_symbols, period, interval)

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

    spec_by_symbol = {spec["symbol"]: spec for spec in _MARKET_INDICATOR_SPECS}
    fetched_symbols: set[str] = set()
    if raw is not None and not raw.empty:
        for symbol in yfinance_symbols:
            frame = _history_frame_for_symbol(raw, symbol, len(yfinance_symbols) == 1)
            added = _append_indicator_history_points(points, frame, symbol, interval, spec_by_symbol)
            if added:
                fetched_symbols.add(symbol)

    for symbol in yfinance_symbols:
        if symbol in fetched_symbols:
            continue
        try:
            frame = yf.Ticker(symbol).history(period=period, interval=interval, auto_adjust=False)
        except Exception:
            continue
        _append_indicator_history_points(points, frame, symbol, interval, spec_by_symbol)
    return points


def _append_indicator_history_points(
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
    zone = _regular_index_zone(symbol)
    added = False
    for ts, row in frame.dropna(subset=["Close"]).iterrows():
        close = _safe_float(row.get("Close"))
        if close is None:
            continue
        observed = pd.to_datetime(ts, utc=True).to_pydatetime().replace(microsecond=0)
        open_price = _safe_float(row.get("Open")) or close
        base_price = previous_close
        if zone is not None:
            session_date = observed.astimezone(zone).date()
            session_open_by_date.setdefault(session_date, open_price)
            base_price = session_open_by_date[session_date]
        change_abs = close - base_price if base_price else None
        points.append({
            **_spec_public_fields(spec),
            "observed_at": observed.isoformat(),
            "timestamp": observed.isoformat(),
            "value": close,
            "open": open_price,
            "high": _safe_float(row.get("High")) or close,
            "low": _safe_float(row.get("Low")) or close,
            "close": close,
            "volume": _safe_float(row.get("Volume")),
            "change_abs": change_abs,
            "change_pct": change_abs / base_price if base_price else None,
            "source": "yfinance",
            "interval": interval,
        })
        previous_close = close
        added = True
    return added


def _fetch_naver_domestic_interest_history(symbols: list[str], period: str, interval: str) -> list[dict]:
    if not symbols:
        return []

    spec_by_symbol = {spec["symbol"]: spec for spec in _MARKET_INDICATOR_SPECS}
    days = _MARKET_HISTORY_PERIODS.get(period, 1)
    start_at = datetime.now(_KST) - timedelta(days=days)
    pages = max(1, min(math.ceil(max(days, 7) / 7) + 1, 20))
    points: list[dict] = []

    for symbol in symbols:
        spec = spec_by_symbol.get(symbol)
        if not spec:
            continue
        rows = _fetch_naver_interest_rows(symbol, pages=pages)
        previous_close = None
        for row in reversed(rows):
            value = _safe_float(row.get("value"))
            if value is None:
                continue
            observed_at = _naver_interest_observed_at(str(row.get("date") or ""))
            observed_dt = pd.to_datetime(observed_at, utc=True, errors="coerce")
            if pd.isna(observed_dt) or observed_dt.to_pydatetime() < start_at.astimezone(timezone.utc):
                continue
            change_abs = _safe_float(row.get("change_abs"))
            change_pct = _safe_float(row.get("change_pct"))
            if previous_close is not None:
                change_abs = value - previous_close
                change_pct = change_abs / previous_close if previous_close else None
            points.append({
                **_spec_public_fields(spec),
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


def _history_frame_for_symbol(raw: pd.DataFrame, symbol: str, single_symbol: bool) -> pd.DataFrame:
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


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _macro_payload() -> dict:
    df = _load_storage_df("Macro_Regime", market="GLOBAL")
    if not df.empty and {"Key", "Value"}.issubset(df.columns):
        return {
            str(row["Key"]).strip(): str(row["Value"]).strip()
            for _, row in df.iterrows()
            if str(row["Key"]).strip()
        }
    ws = _spreadsheet().worksheet("Macro_Regime")
    return {
        r[0].strip(): r[1].strip()
        for r in ws.get_all_values()
        if len(r) >= 2 and r[0].strip()
    }


@app.get("/research/factor-quality", response_model=ResearchQualityResponse)
def research_factor_quality():
    num_cols = [
        "Mean_IC", "Positive_IC_Rate", "Mean_Top_Bottom_Spread",
        "Mean_Hit_Rate", "Snapshots", "Total_Observations",
        "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    ]

    def load():
        items = _load_simple("Signal_Quality_Gates", num_cols)
        status_counts: dict[str, int] = {}
        for item in items:
            status_text = str(item.get("Status") or "UNKNOWN").upper()
            status_counts[status_text] = status_counts.get(status_text, 0) + 1
        production_ready = [
            item for item in items
            if str(item.get("Production_Ready") or "").upper() in {"TRUE", "1", "YES"}
        ]
        proxy_items = [
            item for item in items
            if str(item.get("Evidence_Source") or "").upper() in {"PROXY_ONLY", "MIXED"}
        ]
        status_rank = {"FAIL": 0, "WATCH": 1, "INSUFFICIENT": 2, "PASS": 3}
        overall_status = "UNKNOWN"
        if items:
            overall_status = min(
                (str(item.get("Status") or "UNKNOWN").upper() for item in items),
                key=lambda value: status_rank.get(value, 9),
            )

        ranked = [
            item for item in items
            if _safe_float(item.get("Mean_IC")) is not None
        ]
        best_factors = sorted(
            ranked,
            key=lambda item: _safe_float(item.get("Mean_IC")) or -999,
            reverse=True,
        )[:5]
        weak_factors = sorted(
            ranked,
            key=lambda item: _safe_float(item.get("Mean_IC")) or 999,
        )[:5]
        return {
            "items": items,
            "overall_status": overall_status,
            "status_counts": status_counts,
            "warning_count": status_counts.get("WATCH", 0) + status_counts.get("FAIL", 0),
            "production_ready_count": len(production_ready),
            "proxy_evidence_count": len(proxy_items),
            "best_factors": best_factors,
            "weak_factors": weak_factors,
            "source": "Signal_Quality_Gates",
        }

    return _cached("research_factor_quality", load)


@app.get("/research/factor-policy", response_model=ResearchPolicyResponse)
def research_factor_policy():
    num_cols = [
        "Adjustment_Bias", "Suggested_Multiplier", "Mean_IC",
        "Positive_IC_Rate", "Snapshots", "Total_Observations",
        "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    ]

    def load():
        items = _load_simple("Factor_Weight_Policy", num_cols)
        status_counts: dict[str, int] = {}
        for item in items:
            status_text = str(item.get("Policy_Status") or "UNKNOWN").upper()
            status_counts[status_text] = status_counts.get(status_text, 0) + 1
        review_items = [
            item for item in items
            if str(item.get("Policy_Status") or "").upper() in {"REVIEW", "WATCH"}
        ]
        hold_items = [
            item for item in items
            if str(item.get("Policy_Status") or "").upper() == "HOLD"
        ]
        production_ready = [
            item for item in items
            if str(item.get("Production_Ready") or "").upper() in {"TRUE", "1", "YES"}
        ]
        proxy_items = [
            item for item in items
            if str(item.get("Evidence_Source") or "").upper() in {"PROXY_ONLY", "MIXED"}
        ]
        return {
            "items": items,
            "status_counts": status_counts,
            "review_count": len(review_items),
            "hold_count": len(hold_items),
            "production_ready_count": len(production_ready),
            "proxy_evidence_count": len(proxy_items),
            "review_items": review_items[:10],
            "source": "Factor_Weight_Policy",
            "mode": "observation_only",
        }

    return _cached("research_factor_policy", load)


def _ml_blend_status(item: dict | None) -> str:
    if not item:
        return "UNAVAILABLE"

    reason = str(item.get("ML_Weight_Reason") or "").strip().lower()
    ml_weight = _safe_float(item.get("ML_Weight"))
    rank_ic = _safe_float(item.get("Rank_IC"))

    if ml_weight is None:
        return "UNAVAILABLE"
    if ml_weight <= 0 or reason == "rank_ic_non_positive":
        return "ML_OFF"
    if reason == "rank_ic_unavailable":
        return "REVIEW"
    if rank_ic is not None and rank_ic >= 0.05:
        return "ML_STRONG"
    if rank_ic is not None and rank_ic < 0.02:
        return "ML_WEAK"
    return "ML_BASE"


@app.get("/research/ml-blend", response_model=MLBlendResponse)
def research_ml_blend():
    num_cols = [
        "Rank_IC", "ML_Weight", "Factor_Weight",
        "ML_Factor_Spearman", "ML_Factor_Pearson", "Predicted_Stocks",
    ]

    def load():
        try:
            items = _load_simple("ML_Blend_Report", num_cols)
        except Exception as exc:
            return {
                "status": "UNAVAILABLE",
                "generated_at": datetime.utcnow().isoformat(),
                "source": "ML_Blend_Report",
                "latest": None,
                "items": [],
                "error": f"{type(exc).__name__}: {exc}",
            }

        items = sorted(items, key=lambda item: str(item.get("Generated") or ""), reverse=True)
        for item in items:
            item["Status"] = _ml_blend_status(item)
        latest = items[0] if items else None
        return {
            "status": _ml_blend_status(latest),
            "generated_at": datetime.utcnow().isoformat(),
            "source": "ML_Blend_Report",
            "latest": latest,
            "items": items,
        }

    return _cached("research_ml_blend", load)


@app.get("/research/policy-backtest", response_model=PolicyBacktestResponse)
def research_policy_backtest():
    num_cols = [
        "Snapshots", "Total_Observations", "Base_Weighted_IC",
        "Policy_Weighted_IC", "IC_Delta", "Base_Top_Bottom_Spread",
        "Policy_Top_Bottom_Spread", "Spread_Delta", "Base_Hit_Rate",
        "Policy_Hit_Rate", "Turnover_Estimate", "Live_Snapshots",
        "Proxy_Snapshots", "Proxy_Ratio",
    ]

    def load():
        items = _load_simple("Factor_Policy_Backtest", num_cols)
        status_counts: dict[str, int] = {}
        for item in items:
            status_text = str(item.get("Status") or "UNKNOWN").upper()
            status_counts[status_text] = status_counts.get(status_text, 0) + 1
        improved = [item for item in items if str(item.get("Status") or "").upper() == "IMPROVED"]
        worse = [item for item in items if str(item.get("Status") or "").upper() == "WORSE"]
        production_ready = [
            item for item in items
            if str(item.get("Production_Ready") or "").upper() in {"TRUE", "1", "YES"}
        ]
        proxy_items = [
            item for item in items
            if str(item.get("Evidence_Source") or "").upper() in {"PROXY_ONLY", "MIXED"}
        ]
        ranked = [item for item in items if _safe_float(item.get("IC_Delta")) is not None]
        best = sorted(ranked, key=lambda item: _safe_float(item.get("IC_Delta")) or -999, reverse=True)[:5]
        weak = sorted(ranked, key=lambda item: _safe_float(item.get("IC_Delta")) or 999)[:5]
        return {
            "items": items,
            "status_counts": status_counts,
            "improved_count": len(improved),
            "worse_count": len(worse),
            "production_ready_count": len(production_ready),
            "proxy_evidence_count": len(proxy_items),
            "best_windows": best,
            "weak_windows": weak,
            "source": "Factor_Policy_Backtest",
            "mode": "observation_only",
        }

    return _cached("research_policy_backtest", load)


@app.get("/research/remediation-plan", response_model=RemediationPlanResponse)
def research_remediation_plan():
    num_cols = [
        "Priority", "Mean_IC", "Positive_IC_Rate", "Top_Bottom_Spread",
        "IC_Delta",
    ]

    def load():
        items = _load_simple("Factor_Remediation_Plan", num_cols)
        severity_counts: dict[str, int] = {}
        for item in items:
            severity = str(item.get("Severity") or "UNKNOWN").upper()
            severity_counts[severity] = severity_counts.get(severity, 0) + 1
        urgent_items = [
            item for item in items
            if str(item.get("Severity") or "").upper() in {"HIGH", "OBSERVE_HIGH"}
        ]
        production_ready = [
            item for item in items
            if str(item.get("Production_Ready") or "").upper() in {"TRUE", "1", "YES"}
        ]
        top_actions = sorted(
            items,
            key=lambda item: _safe_float(item.get("Priority")) or 999,
        )[:10]
        return {
            "items": items,
            "severity_counts": severity_counts,
            "urgent_count": len(urgent_items),
            "production_ready_count": len(production_ready),
            "top_actions": top_actions,
            "source": "Factor_Remediation_Plan",
            "mode": "observation_only",
        }

    return _cached("research_remediation_plan", load)


def _run_scripts(payload: dict) -> list[str]:
    scripts: list[str] = []
    for step in payload.get("steps") or []:
        if isinstance(step, str):
            scripts.append(step)
    for result in payload.get("results") or []:
        if isinstance(result, dict) and result.get("script"):
            scripts.append(str(result["script"]))
    return list(dict.fromkeys(scripts))


def _run_kind(scripts: list[str]) -> str:
    script_set = set(scripts)
    if {
        "pipeline/14_factor_ic_report.py",
        "pipeline/15_signal_quality_gate.py",
        "pipeline/16_factor_weight_policy.py",
        "pipeline/17_factor_policy_backtest.py",
    }.issubset(script_set):
        return "research_quality"
    if "tools/warm_detail_cache.py" in script_set:
        return "full_pipeline"
    return "pipeline"


def _run_elapsed(payload: dict) -> float | None:
    total = 0.0
    seen = False
    for result in payload.get("results") or []:
        if not isinstance(result, dict):
            continue
        value = _safe_float(result.get("elapsed_sec"))
        if value is None:
            continue
        total += value
        seen = True
    return round(total, 3) if seen else None


def _pipeline_runs_payload(limit: int = 20) -> dict:
    safe_limit = max(1, min(int(limit or 20), 100))
    df = _repository().read_pipeline_runs(limit=safe_limit)
    items: list[dict] = []
    if not df.empty:
        for row in df.to_dict("records"):
            payload = row.get("payload") if isinstance(row.get("payload"), dict) else {}
            scripts = _run_scripts(payload)
            items.append({
                "run_id": row.get("run_id"),
                "runner": row.get("runner"),
                "status": row.get("status"),
                "started_at": row.get("started_at"),
                "finished_at": row.get("finished_at"),
                "kind": _run_kind(scripts),
                "scripts": scripts,
                "elapsed_sec": _run_elapsed(payload),
            })

    status_counts: dict[str, int] = {}
    for item in items:
        status_text = str(item.get("status") or "unknown").lower()
        status_counts[status_text] = status_counts.get(status_text, 0) + 1

    latest_research = next(
        (item for item in items if item.get("kind") == "research_quality"),
        None,
    )
    return {
        "items": items,
        "latest_research_quality": latest_research,
        "status_counts": status_counts,
        "source": "pipeline_runs",
    }


def _parse_iso_datetime(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except Exception:
        return None


def _age_hours(value: str | None) -> float | None:
    dt = _parse_iso_datetime(value)
    if dt is None:
        return None
    now = datetime.now(dt.tzinfo) if dt.tzinfo else datetime.utcnow()
    return round((now - dt).total_seconds() / 3600.0, 3)


def _research_health_payload(max_age_hours: int = 84) -> dict:
    safe_hours = max(1, min(int(max_age_hours or 84), 240))
    runs = _pipeline_runs_payload(limit=100)
    latest = runs.get("latest_research_quality")

    if not latest:
        return {
            "healthy": False,
            "status": "MISSING",
            "reason": "No research-quality run has been recorded yet.",
            "max_age_hours": safe_hours,
            "age_hours": None,
            "latest_research_quality": None,
        }

    status_text = str(latest.get("status") or "unknown").lower()
    finished_at = latest.get("finished_at")
    age = _age_hours(finished_at)
    if status_text != "success":
        return {
            "healthy": False,
            "status": "FAILED",
            "reason": f"Latest research-quality run status is {status_text}.",
            "max_age_hours": safe_hours,
            "age_hours": age,
            "latest_research_quality": latest,
        }
    if age is None:
        return {
            "healthy": False,
            "status": "UNKNOWN_AGE",
            "reason": "Latest research-quality run has no finished_at timestamp.",
            "max_age_hours": safe_hours,
            "age_hours": None,
            "latest_research_quality": latest,
        }
    if age > safe_hours:
        return {
            "healthy": False,
            "status": "STALE",
            "reason": f"Latest successful research-quality run is {age:.1f}h old.",
            "max_age_hours": safe_hours,
            "age_hours": age,
            "latest_research_quality": latest,
        }
    return {
        "healthy": True,
        "status": "OK",
        "reason": f"Latest research-quality run succeeded {age:.1f}h ago.",
        "max_age_hours": safe_hours,
        "age_hours": age,
        "latest_research_quality": latest,
    }


def _check(name: str, status_text: str, message: str, detail: dict | None = None) -> dict:
    return {
        "name": name,
        "status": status_text,
        "message": message,
        "detail": detail or {},
    }


def _dataset_check(name: str, loader, min_rows: int = 1) -> dict:
    try:
        rows = loader()
        count = len(rows or [])
        status_text = "OK" if count >= min_rows else "FAIL"
        message = f"{count} rows available" if status_text == "OK" else f"{count} rows, expected at least {min_rows}"
        return _check(name, status_text, message, {"rows": count, "min_rows": min_rows})
    except Exception as exc:
        return _check(name, "FAIL", f"{type(exc).__name__}: {exc}")


def _core_dataset_checks() -> list[dict]:
    return [
        _dataset_check("US portfolio", lambda: _load_portfolio("US_Final_Portfolio")[1], min_rows=1),
        _dataset_check("KR portfolio", lambda: _load_portfolio("KR_Final_Portfolio")[1], min_rows=1),
        _dataset_check("US smallcap", lambda: _load_simple("US_SmallCap_Gems", ["Rank", "Total_Score"]), min_rows=1),
        _dataset_check("KR smallcap", lambda: _load_simple("KR_SmallCap_Gems", ["Rank", "Total_Score"]), min_rows=1),
        _dataset_check("Signal quality gates", lambda: _load_simple("Signal_Quality_Gates", ["Snapshots"]), min_rows=1),
    ]


def _macro_check() -> dict:
    try:
        data = _macro_payload()
        regime = str(data.get("Regime") or "").strip()
        if not regime:
            return _check("Macro regime", "WARN", "Macro_Regime exists but Regime is blank.", {"keys": len(data)})
        return _check("Macro regime", "OK", f"Regime={regime}", {"keys": len(data), "regime": regime})
    except Exception as exc:
        return _check("Macro regime", "FAIL", f"{type(exc).__name__}: {exc}")


def _storage_config_checks() -> list[dict]:
    checks = []
    has_google_key_json = bool(str(_SETTINGS.google_key_json or "").strip())
    has_google_key_file = _SETTINGS.google_key_path.exists()
    checks.append(_check(
        "Google key",
        "OK" if (has_google_key_file or has_google_key_json) else "WARN",
        (
            "Google key file exists." if has_google_key_file
            else "Google key JSON secret is configured." if has_google_key_json
            else "Google key file/JSON secret not found."
        ),
        {"path": str(_SETTINGS.google_key_path), "json_secret": has_google_key_json},
    ))
    parquet_exists = _SETTINGS.data_lake_dir.exists()
    if _SETTINGS.enable_parquet:
        checks.append(_check(
            "Parquet lake",
            "OK" if parquet_exists else "WARN",
            "Data lake directory exists." if parquet_exists else "Data lake directory not found yet.",
            {"enabled": True, "path": str(_SETTINGS.data_lake_dir)},
        ))
    else:
        checks.append(_check(
            "Parquet lake",
            "OK",
            "Parquet is disabled for this runtime.",
            {"enabled": False, "path": str(_SETTINGS.data_lake_dir), "exists": parquet_exists},
        ))
    checks.append(_check(
        "PostgreSQL mode",
        "OK" if _SETTINGS.enable_postgres else "WARN",
        "PostgreSQL is enabled." if _SETTINGS.enable_postgres else "PostgreSQL is disabled; using Sheets/Parquet fallback where available.",
        {"enabled": _SETTINGS.enable_postgres},
    ))
    return checks


def _factor_quality_check() -> dict:
    try:
        items = _load_simple("Signal_Quality_Gates", [
            "Mean_IC", "Positive_IC_Rate", "Mean_Top_Bottom_Spread",
            "Mean_Hit_Rate", "Snapshots", "Total_Observations",
            "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
        ])
    except Exception as exc:
        return _check("Signal quality", "WARN", f"{type(exc).__name__}: {exc}")

    if not items:
        return _check("Signal quality", "WARN", "No signal quality rows available yet.")

    counts: dict[str, int] = {}
    for item in items:
        value = str(item.get("Status") or "UNKNOWN").upper()
        counts[value] = counts.get(value, 0) + 1
    worst = min(counts, key=lambda value: {"FAIL": 0, "WATCH": 1, "INSUFFICIENT": 2, "PASS": 3}.get(value, 9))
    status_text = "WARN" if worst in {"FAIL", "WATCH", "INSUFFICIENT", "UNKNOWN"} else "OK"
    return _check(
        "Signal quality",
        status_text,
        f"Worst gate={worst}; {len(items)} factor/horizon rows.",
        {"rows": len(items), "status_counts": counts, "worst_gate": worst},
    )


def _data_quality_payload(max_age_days: int | None = None) -> dict:
    return build_data_quality_report(
        lambda dataset, market: _repository().read_dataframe(dataset, market=market),
        max_age_days=max_age_days,
    )


def _data_quality_check(max_age_days: int | None = None) -> dict:
    try:
        payload = _data_quality_payload(max_age_days=max_age_days)
    except Exception as exc:
        return _check("Data quality", "FAIL", f"{type(exc).__name__}: {exc}")
    status_text = str(payload.get("status") or "UNKNOWN").upper()
    if status_text == "OK":
        check_status = "OK"
    elif status_text == "DEGRADED":
        check_status = "WARN"
    else:
        check_status = "FAIL"
    return _check(
        "Data quality",
        check_status,
        f"status={status_text}; datasets={len(payload.get('datasets', []))}",
        {
            "status": payload.get("status"),
            "status_counts": payload.get("status_counts", {}),
            "datasets": [
                {
                    "dataset": item.get("dataset"),
                    "market": item.get("market"),
                    "status": item.get("status"),
                    "rows": item.get("rows"),
                }
                for item in payload.get("datasets", [])
            ],
        },
    )


def _data_source_check() -> dict:
    payload = _data_source_payload()
    summary = payload.get("summary", {})
    fallback_count = int(summary.get("sheet", 0)) + int(summary.get("sheet_empty", 0)) + int(summary.get("sheet_error", 0))
    storage_error_count = int(summary.get("storage_error", 0))
    if storage_error_count:
        return _check(
            "Data source fallback",
            "WARN",
            f"{storage_error_count} dataset(s) hit storage errors before fallback.",
            payload,
        )
    if fallback_count:
        return _check(
            "Data source fallback",
            "WARN",
            f"{fallback_count} dataset(s) used Sheets fallback in this API process.",
            payload,
        )
    if payload.get("count", 0):
        return _check("Data source fallback", "OK", "All observed dataset reads used storage or empty storage.", payload)
    return _check("Data source fallback", "WARN", "No dataset reads observed yet.", payload)


def _ops_health_payload(max_research_age_hours: int = 84) -> dict:
    started = datetime.utcnow()
    checks: list[dict] = []

    ready_payload = _ready_payload()
    ready_ok = ready_payload.get("status") == "ready"
    checks.append(_check(
        "API readiness",
        "OK" if ready_ok else "FAIL",
        f"ready status={ready_payload.get('status')}",
        ready_payload,
    ))
    checks.extend(_storage_config_checks())
    checks.append(_macro_check())
    checks.extend(_core_dataset_checks())
    checks.append(_data_source_check())
    checks.append(_data_quality_check())
    checks.append(_factor_quality_check())

    research = _research_health_payload(max_age_hours=max_research_age_hours)
    research_status = "OK" if research.get("healthy") else ("FAIL" if research.get("status") == "FAILED" else "WARN")
    checks.append(_check(
        "Research-quality job",
        research_status,
        str(research.get("reason") or research.get("status") or "unknown"),
        research,
    ))

    counts: dict[str, int] = {}
    for check in checks:
        status_text = str(check.get("status") or "UNKNOWN").upper()
        counts[status_text] = counts.get(status_text, 0) + 1

    if counts.get("FAIL", 0):
        overall = "FAIL"
    elif counts.get("WARN", 0):
        overall = "DEGRADED"
    else:
        overall = "OK"

    elapsed_ms = round((datetime.utcnow() - started).total_seconds() * 1000, 2)
    return {
        "healthy": overall == "OK",
        "status": overall,
        "generated_at": datetime.utcnow().isoformat(),
        "elapsed_ms": elapsed_ms,
        "status_counts": counts,
        "checks": checks,
        "config": {
            "postgres_enabled": _SETTINGS.enable_postgres,
            "parquet_enabled": _SETTINGS.enable_parquet,
            "pipeline_runner": _SETTINGS.pipeline_runner,
            "spreadsheet_name": _SETTINGS.spreadsheet_name,
        },
    }


def ops_pipeline_runs(limit: int = 20):
    safe_limit = max(1, min(int(limit or 20), 100))
    return _cached(f"ops_pipeline_runs_{safe_limit}", lambda: _pipeline_runs_payload(safe_limit), ttl=15)


def ops_research_health(max_age_hours: int = 84):
    safe_hours = max(1, min(int(max_age_hours or 84), 240))
    return _cached(f"ops_research_health_{safe_hours}", lambda: _research_health_payload(safe_hours), ttl=15)


def ops_data_quality(max_age_days: int = 0):
    safe_days = None if int(max_age_days or 0) <= 0 else max(1, min(int(max_age_days), 90))
    cache_key = safe_days if safe_days is not None else "default"
    return _cached(f"ops_data_quality_{cache_key}", lambda: _data_quality_payload(safe_days), ttl=15)


def ops_data_sources():
    return _data_source_payload()


def ops_health(max_research_age_hours: int = 84):
    safe_hours = max(1, min(int(max_research_age_hours or 84), 240))
    return _cached(f"ops_health_{safe_hours}", lambda: _ops_health_payload(safe_hours), ttl=15)


def cache_clear():
    """Force-invalidate all cached data (call after running the pipeline)."""
    _clear_runtime_state()
    return {"cleared": True}


# ── Stock detail (price history + company info) ───────────────────────────────

def _safe_float(v) -> float | None:
    try:
        f = float(v)
        return None if math.isnan(f) else f
    except (TypeError, ValueError):
        return None


def _clean_text(v) -> str | None:
    if v is None:
        return None
    text = str(v).strip()
    return text or None


def _first_float(*values) -> float | None:
    for value in values:
        parsed = _safe_float(value)
        if parsed is not None:
            return parsed
    return None


def _first_text(*values) -> str | None:
    for value in values:
        text = _clean_text(value)
        if text:
            return text
    return None


def _has_value(value) -> bool:
    if value is None:
        return False
    if isinstance(value, float) and math.isnan(value):
        return False
    if isinstance(value, str) and not value.strip():
        return False
    return True


def _infer_market(ticker: str) -> str:
    return "KR" if re.fullmatch(r"\d{6}(?:\.(?:KS|KQ))?", str(ticker or "").strip().upper()) else "US"


def _default_logo_url(ticker: str, market: str) -> str:
    normal = str(ticker or "").strip().upper()
    if market == "KR":
        code = _kr_code(normal)
        if not code:
            return ""
        overrides = {
            "064400": "https://www.lgcns.com/etc.clientlibs/lgcns/clientlibs/clientlib-site/resources/image/common/logo-og-0807.png",
            "267250": f"https://file.alphasquare.co.kr/media/images/stock_logo/kr/{code}.png",
        }
        return overrides.get(code) or f"https://static.toss.im/png-icons/securities/icn-sec-fill-{code}.png"
    return f"https://financialmodelingprep.com/image-stock/{normal}.png" if normal else ""


def _identity_payload_from_row(row: dict, ticker: str, market: str, dataset: str) -> dict:
    normal = str(ticker or "").strip().upper()
    payload = {
        "market": market,
        "ticker": normal,
        "name": _localized_company_name(normal, _first_text(row.get("Name"), row.get("name")), market),
        "sector": _first_text(row.get("Sector"), row.get("sector")),
        "market_cap": _first_float(row.get("MarketCap"), row.get("MarketCap_Last"), row.get("market_cap")),
        "currency": "KRW" if market == "KR" else "USD",
        "exchange": _first_text(row.get("Exchange"), row.get("exchange")),
        "logo_url": _default_logo_url(normal, market),
        "logo_source": "kr_logo_fallback" if market == "KR" else "financialmodelingprep",
        "source_dataset": dataset,
        "current_price": _first_float(row.get("Price_Last"), row.get("current_price")),
        "pe_ratio": _first_float(row.get("PER"), row.get("PER_Last"), row.get("pe_ratio"), row.get("trailingPE")),
        "forward_pe": _first_float(row.get("ForwardPER"), row.get("Forward_PE"), row.get("forward_pe")),
        "price_to_sales": _first_float(row.get("PSR"), row.get("PriceToSales"), row.get("price_to_sales")),
        "price_to_book": _first_float(row.get("PBR"), row.get("PBR_Last"), row.get("price_to_book")),
        "total_revenue": _first_float(row.get("Revenue"), row.get("Revenue_Last"), row.get("total_revenue")),
        "revenue_growth": _first_float(
            row.get("RevenueGrowth"), row.get("RevGrowth"), row.get("RevGrowth_Last"), row.get("revenue_growth")
        ),
        "gross_margin": _first_float(row.get("GrossMargin"), row.get("GrossMargin_Last"), row.get("gross_margin")),
        "operating_margin": _first_float(
            row.get("OperatingMargin"), row.get("OperatingMargin_Last"), row.get("operating_margin")
        ),
        "debt_to_equity": _first_float(
            row.get("DebtToEquity"), row.get("DebtToEquity_Last"), row.get("debt_to_equity")
        ),
        "return_on_equity": _first_float(row.get("ROE"), row.get("ROE_Last"), row.get("return_on_equity")),
    }
    return {k: v for k, v in payload.items() if _has_value(v)}


def _merge_identity_payload(primary: dict | None, fallback: dict | None, ticker: str = "") -> dict:
    merged: dict = {}
    for source in (primary or {}, fallback or {}):
        for key, value in source.items():
            if not _has_value(value):
                continue
            current = merged.get(key)
            if key == "name" and _has_value(current):
                if _is_missing_kr_name(current, ticker) and not _is_missing_kr_name(value, ticker):
                    merged[key] = value
                continue
            if not _has_value(current):
                merged[key] = value
    return merged


def _identity_has_valuation(identity: dict | None) -> bool:
    if not identity:
        return False
    return any(
        _first_float(identity.get(key)) is not None
        for key in ("pe_ratio", "PER", "PER_Last", "price_to_book", "PBR", "PBR_Last")
    )


def _identity_from_storage(ticker: str, market: str) -> dict:
    normal = str(ticker or "").strip().upper()
    datasets = [
        (f"{market}_Final_Portfolio", market),
        (f"{market}_SmallCap_Gems", market),
        (f"{market}_Scored_Stocks", market),
        (f"{market}_Universe", market),
        ("Company_Master", None),
    ]
    identity = _identity_payload_from_row({}, normal, market, "")
    found = False
    for dataset, dataset_market in datasets:
        df = _load_storage_df(dataset, market=dataset_market)
        if df.empty or "Ticker" not in df.columns:
            continue
        hit = df[df["Ticker"].astype(str).str.upper() == normal]
        if hit.empty and market == "KR":
            code = _kr_code(normal)
            hit = df[df["Ticker"].astype(str).map(_kr_code) == code] if code else hit
        if hit.empty:
            continue
        row = hit.iloc[0].to_dict()
        identity = _merge_identity_payload(identity, _identity_payload_from_row(row, normal, market, dataset), normal)
        found = True
    return identity if found else _identity_payload_from_row({}, normal, market, "")


def _price_records_from_yfinance(raw: pd.DataFrame) -> list[dict]:
    if raw.empty:
        return []
    if isinstance(raw.columns, pd.MultiIndex):
        raw.columns = raw.columns.get_level_values(0)
    prices = []
    for d, row in raw.iterrows():
        try:
            prices.append({
                "date": str(d.date()),
                "open": float(row["Open"]),
                "high": float(row["High"]),
                "low": float(row["Low"]),
                "close": float(row["Close"]),
                "volume": _safe_float(row.get("Volume")),
            })
        except (KeyError, ValueError, TypeError):
            pass
    return prices


def _info_from_cached(identity: dict | None, prices: list[dict]) -> dict:
    identity = identity or {}
    closes = [p.get("close") for p in prices if _safe_float(p.get("close")) is not None]
    highs = [p.get("high") for p in prices if _safe_float(p.get("high")) is not None]
    lows = [p.get("low") for p in prices if _safe_float(p.get("low")) is not None]
    info = {
        "ticker": identity.get("ticker") or identity.get("Ticker"),
        "market": identity.get("market") or identity.get("Market"),
        "name": _localized_company_name(identity.get("ticker") or identity.get("Ticker"), identity.get("name") or identity.get("Name"), identity.get("market") or identity.get("Market")),
        "sector": identity.get("sector") or identity.get("Sector") or "",
        "currency": identity.get("currency"),
        "exchange": identity.get("exchange"),
        "logo_url": identity.get("logo_url"),
        "logo_source": identity.get("logo_source"),
        "market_cap": _first_float(identity.get("market_cap"), identity.get("MarketCap"), identity.get("MarketCap_Last")),
        "current_price": _first_float(closes[-1] if closes else None, identity.get("current_price"), identity.get("Price_Last")),
        "prev_close": _safe_float(closes[-2]) if len(closes) >= 2 else None,
        "week52_high": max(highs) if highs else None,
        "week52_low": min(lows) if lows else None,
        "pe_ratio": _first_float(identity.get("pe_ratio"), identity.get("PER"), identity.get("PER_Last")),
        "forward_pe": _first_float(identity.get("forward_pe"), identity.get("ForwardPER"), identity.get("Forward_PE")),
        "price_to_sales": _first_float(identity.get("price_to_sales"), identity.get("PSR"), identity.get("PriceToSales")),
        "price_to_book": _first_float(identity.get("price_to_book"), identity.get("PBR"), identity.get("PBR_Last")),
        "total_revenue": _first_float(identity.get("total_revenue"), identity.get("Revenue"), identity.get("Revenue_Last")),
        "revenue_growth": _first_float(
            identity.get("revenue_growth"), identity.get("RevenueGrowth"),
            identity.get("RevGrowth"), identity.get("RevGrowth_Last"),
        ),
        "gross_margin": _first_float(
            identity.get("gross_margin"), identity.get("GrossMargin"), identity.get("GrossMargin_Last"),
        ),
        "operating_margin": _first_float(
            identity.get("operating_margin"), identity.get("OperatingMargin"), identity.get("OperatingMargin_Last"),
        ),
        "debt_to_equity": _first_float(
            identity.get("debt_to_equity"), identity.get("DebtToEquity"), identity.get("DebtToEquity_Last"),
        ),
        "return_on_equity": _first_float(identity.get("return_on_equity"), identity.get("ROE"), identity.get("ROE_Last")),
    }
    return {k: v for k, v in info.items() if v not in (None, "")}


def _safe_int(v) -> int | None:
    try:
        if v is None:
            return None
        return int(float(v))
    except (TypeError, ValueError):
        return None


def _merge_company_profile(info: dict, full: dict) -> dict:
    info.update({
        "name": _localized_company_name(info.get("ticker"), _clean_text(full.get("longName") or full.get("shortName") or info.get("name")), info.get("market")),
        "industry": _clean_text(full.get("industry")),
        "sector": _clean_text(full.get("sector") or info.get("sector")),
        "country": _clean_text(full.get("country")),
        "city": _clean_text(full.get("city")),
        "exchange": _clean_text(full.get("exchange") or full.get("fullExchangeName") or info.get("exchange")),
        "website": _clean_text(full.get("website")),
        "employees": _safe_int(full.get("fullTimeEmployees")),
        "pe_ratio": _first_float(full.get("trailingPE"), info.get("pe_ratio")),
        "forward_pe": _first_float(full.get("forwardPE"), info.get("forward_pe")),
        "price_to_sales": _first_float(full.get("priceToSalesTrailing12Months"), info.get("price_to_sales")),
        "price_to_book": _first_float(full.get("priceToBook"), info.get("price_to_book")),
        "beta": _first_float(full.get("beta"), info.get("beta")),
        "total_revenue": _first_float(full.get("totalRevenue"), info.get("total_revenue")),
        "revenue_growth": _first_float(full.get("revenueGrowth"), info.get("revenue_growth")),
        "gross_margin": _first_float(full.get("grossMargins"), info.get("gross_margin")),
        "operating_margin": _first_float(full.get("operatingMargins"), info.get("operating_margin")),
        "profit_margin": _first_float(full.get("profitMargins"), info.get("profit_margin")),
        "ebitda_margin": _first_float(full.get("ebitdaMargins"), info.get("ebitda_margin")),
        "ebitda": _first_float(full.get("ebitda"), info.get("ebitda")),
        "free_cashflow": _first_float(full.get("freeCashflow"), info.get("free_cashflow")),
        "total_debt": _first_float(full.get("totalDebt"), info.get("total_debt")),
        "debt_to_equity": _first_float(full.get("debtToEquity"), info.get("debt_to_equity")),
        "return_on_equity": _first_float(full.get("returnOnEquity"), info.get("return_on_equity")),
        "target_mean_price": _first_float(full.get("targetMeanPrice"), info.get("target_mean_price")),
        "recommendation": _clean_text(full.get("recommendationKey")),
    })
    raw_desc = full.get("longBusinessSummary") or ""
    if raw_desc:
        info["description"] = str(raw_desc)[:900]
    return {k: v for k, v in info.items() if v not in (None, "")}
_stock_detail_service = StockDetailService(
    repository=_repository,
    cached=_cached,
    infer_market=_infer_market,
    utc_now_iso=_utc_now_iso,
    identity_has_valuation=_identity_has_valuation,
    identity_from_storage=_identity_from_storage,
    merge_identity_payload=_merge_identity_payload,
    info_from_cached=_info_from_cached,
    df_to_records=_df_to_records,
    price_records_from_yfinance=_price_records_from_yfinance,
    first_float=_first_float,
    merge_company_profile=_merge_company_profile,
    allow_external_fetch=_allow_api_external_fetch,
    kr_quote_batch=_naver_kr_stock_price_batch,
)


def _sector_detail_reconcile_members(members: list[dict]) -> list[dict]:
    if not members:
        return members

    def load_member(item: dict) -> dict:
        ticker = str(item.get("Ticker") or "").strip().upper()
        if not ticker:
            return item
        try:
            detail = _stock_detail_service.detail(
                ticker=ticker,
                period="6mo",
                refresh=False,
                profile=False,
            )
            info = detail.get("info") or {}
            updated = dict(item)
            updated["Current_Price"] = _first_float(item.get("Current_Price"), info.get("current_price"))
            updated["Daily_Change_Pct"] = _first_float(item.get("Daily_Change_Pct"), info.get("daily_change_pct"))
            updated["Daily_Change_Horizon"] = str(item.get("Daily_Change_Horizon") or info.get("daily_change_horizon") or "")
            updated["MarketCap"] = _first_float(info.get("market_cap"), item.get("MarketCap"))
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


_portfolio_api_service = PortfolioApiService(
    cached=_cached,
    invalidate=_invalidate,
    load_portfolio=lambda sheet: _load_portfolio(sheet),
    price_snapshot_batch=lambda tickers, market: _portfolio_price_snapshot_batch(tickers, market),
    price_metrics_batch=lambda tickers, market: _portfolio_price_metrics_batch(tickers, market),
    daily_change_batch=lambda tickers, market, snapshots: _portfolio_daily_change_batch(tickers, market, snapshots),
    naver_kr_quote_batch=_naver_kr_stock_price_batch,
    utc_now_iso=_utc_now_iso,
)
_sector_api_service = SectorApiService(
    cached=_cached,
    invalidate=_invalidate,
    payload=lambda market, limit, members, focus_label=None: _sector_themes_payload(
        market,
        limit,
        members,
        focus_label,
    ),
)
_etf_api_service = EtfApiService(
    cached=_cached,
    invalidate=_invalidate,
    payload_with_prices=lambda **kwargs: _etf_payload_with_prices(**kwargs),
    detail_from_records=lambda records, ticker: _etf_detail_from_records(records, ticker),
    storage_records=lambda: _etf_storage_records(),
    enrich_price_fields=lambda items: _enrich_etf_price_fields(items),
)
_news_api_service = NewsApiService(
    cached=_cached,
    payload=lambda q, market, limit: _news_payload(q, market, limit),
    cache_query_key=lambda q, market: _news_cache_query_key(q, market),
)
_market_api_service = MarketApiService(
    cached=_cached,
    invalidate=_invalidate,
    market_indicators_payload=lambda **kwargs: _market_indicators_payload(**kwargs),
    history_payload=lambda symbols, period, interval, refresh=False: _market_indicator_history_payload(
        symbols,
        period,
        interval,
        refresh=refresh,
    ),
    selected_symbols=lambda symbols: _selected_indicator_symbols(symbols),
    legacy_index_symbols=_LEGACY_INDEX_SYMBOLS,
    periods=_MARKET_HISTORY_PERIODS,
    intervals=_MARKET_HISTORY_INTERVALS,
)
_search_api_service = SearchApiService(
    cached=_cached,
    payload=lambda q, limit: _search_universe_payload(q, limit),
)
_calendar_api_service = CalendarApiService(
    cached=_cached,
    earnings_calendar_response=lambda market, days, limit, refresh=False: _earnings_calendar_response(
        market=market,
        days=days,
        limit=limit,
        refresh=refresh,
    ),
    load_simple=lambda sheet, cols: _load_simple(sheet, cols),
    enrich_kr_company_identities=lambda rows: _enrich_kr_company_identities(rows),
    macro_payload=lambda: _macro_payload(),
)
_risk_api_service = RiskApiService(
    cached=_cached,
    signal_events_payload=lambda market, tickers, kinds, limit: _signal_events_payload(market, tickers, kinds, limit),
    comparison_recommendation_payload=lambda ticker, market, limit: _comparison_recommendation_payload(ticker, market, limit),
    backtest_payload=lambda sheet, market: _backtest_payload(sheet, market),
    risk_drift_payload=lambda: _risk_drift_payload(),
    portfolio_risk_payload=lambda market, limit: _portfolio_risk_payload(market, limit),
    rebalance_payload=lambda market, limit: _rebalance_payload(market, limit),
    shadow_attribution_payload=lambda market, limit: _shadow_attribution_payload(market, limit),
    industry_payload=lambda limit: _industry_payload(limit),
    order_flow_payload=lambda limit: _order_flow_payload(limit),
)
_ops_api_service = OpsApiService(
    cached=_cached,
    clear_runtime_state=_clear_runtime_state,
    pipeline_runs_payload=lambda limit: _pipeline_runs_payload(limit),
    research_health_payload=lambda max_age_hours: _research_health_payload(max_age_hours),
    data_quality_payload=lambda max_age_days: _data_quality_payload(max_age_days),
    data_source_payload=lambda: _data_source_payload(),
    performance_payload=lambda limit: _performance_payload(limit),
    cache_warm_state_payload=lambda: _cache_warm_state(),
    cache_warm_payload=lambda: _start_cache_warm("manual"),
    ops_health_payload=lambda max_research_age_hours: _ops_health_payload(max_research_age_hours),
)

_DETAIL_WARM_STOCK_LIMIT = 80
_DETAIL_WARM_ETF_LIMIT = 30


def _cache_warm_state() -> dict:
    return _api_cache_warmer.state()


def _start_cache_warm(reason: str = "manual") -> dict:
    return _api_cache_warmer.start(reason)


def _cache_warm_sector_detail_labels(market: str, limit: int = 18) -> list[str]:
    try:
        payload = _sector_api_service.sector_theme_summary(market=market, limit=72)
        labels = [
            str(item.get("label") or "").strip()
            for item in (payload.get("items") or [])
            if isinstance(item, dict) and str(item.get("label") or "").strip()
        ]
        return list(dict.fromkeys(labels))[:limit]
    except Exception:
        return [label for label in _SECTOR_THEME_ORDER[:limit] if label]


def _cache_warm_jobs() -> list[tuple[str, object]]:
    jobs: list[tuple[str, object]] = [
        ("portfolio/us", lambda: _portfolio_api_service.portfolio("us")),
        ("portfolio/kr", lambda: _portfolio_api_service.portfolio("kr")),
        ("smallcap/us", lambda: smallcap("US")),
        ("smallcap/kr", lambda: smallcap("KR")),
        ("earnings/us", lambda: _calendar_api_service.earnings("us")),
        ("earnings/kr", lambda: _calendar_api_service.earnings("kr")),
        ("calendar/earnings", lambda: _calendar_api_service.calendar_earnings(market="ALL", days=180, limit=2000)),
        ("signals/events", lambda: _risk_api_service.signal_events(market="ALL", limit=120)),
        ("market/indices", lambda: _market_api_service.market_indices()),
        ("market/indicators", lambda: _market_api_service.market_indicators(category="all")),
        ("news/issues", lambda: _news_api_service.news_issues(q="", market="ALL", limit=40)),
        ("etfs", lambda: _etf_api_service.etfs(limit=500)),
        ("sectors/themes/summary:ALL:36", lambda: _sector_api_service.sector_theme_summary(market="ALL", limit=36)),
        ("sectors/themes/summary:US:36", lambda: _sector_api_service.sector_theme_summary(market="US", limit=36)),
        ("sectors/themes/summary:KR:36", lambda: _sector_api_service.sector_theme_summary(market="KR", limit=36)),
        ("sectors/themes:ALL:36", lambda: _sector_api_service.sector_themes(market="ALL", limit=36, members=12)),
        ("sectors/themes:US:36", lambda: _sector_api_service.sector_themes(market="US", limit=36, members=12)),
        ("sectors/themes:KR:36", lambda: _sector_api_service.sector_themes(market="KR", limit=36, members=12)),
        ("sectors/themes/summary:ALL", lambda: _sector_api_service.sector_theme_summary(market="ALL", limit=72)),
        ("sectors/themes/summary:US", lambda: _sector_api_service.sector_theme_summary(market="US", limit=72)),
        ("sectors/themes/summary:KR", lambda: _sector_api_service.sector_theme_summary(market="KR", limit=72)),
        ("sectors/themes:ALL", lambda: _sector_api_service.sector_themes(market="ALL", limit=72, members=12)),
        ("sectors/themes:US", lambda: _sector_api_service.sector_themes(market="US", limit=72, members=12)),
        ("sectors/themes:KR", lambda: _sector_api_service.sector_themes(market="KR", limit=72, members=12)),
    ]
    for market in ("ALL", "US", "KR"):
        for label in _cache_warm_sector_detail_labels(market):
            jobs.append((
                f"sectors/themes/detail:{market}:{label}",
                lambda market=market, label=label: _sector_api_service.sector_theme_detail(
                    label=label,
                    market=market,
                    members=40,
                ),
            ))
    return jobs


def _cache_warm_stock_tickers() -> list[str]:
    tickers: list[str] = []

    def add(ticker: object) -> None:
        clean = str(ticker or "").strip().upper()
        if clean and clean not in tickers:
            tickers.append(clean)

    for market in ("us", "kr"):
        try:
            for row in (_portfolio_api_service.portfolio(market).get("stocks") or [])[:30]:
                add(row.get("Ticker") or row.get("ticker"))
        except Exception:
            pass
    for market in ("US", "KR"):
        try:
            for row in (smallcap(market).get("stocks") or [])[:20]:
                add(row.get("Ticker") or row.get("ticker"))
        except Exception:
            pass
    try:
        for theme in (_sector_api_service.sector_themes(market="ALL", limit=24, members=12).get("items") or []):
            leader = theme.get("leader") if isinstance(theme, dict) else None
            if isinstance(leader, dict):
                add(leader.get("Ticker"))
            for member in (theme.get("members") or [])[:3]:
                if isinstance(member, dict):
                    add(member.get("Ticker"))
    except Exception:
        pass
    return tickers[:_DETAIL_WARM_STOCK_LIMIT]


def _cache_warm_etf_tickers() -> list[str]:
    tickers: list[str] = []
    try:
        items = _etf_api_service.etfs(limit=80).get("items") or []
    except Exception:
        return tickers
    for item in items:
        if not isinstance(item, dict):
            continue
        ticker = str(item.get("Ticker") or item.get("ticker") or item.get("symbol") or "").strip().upper()
        if ticker and ticker not in tickers:
            tickers.append(ticker)
        if len(tickers) >= _DETAIL_WARM_ETF_LIMIT:
            break
    return tickers


_api_cache_warmer = ApiCacheWarmer(
    primary_jobs=_cache_warm_jobs,
    stock_tickers=_cache_warm_stock_tickers,
    etf_tickers=_cache_warm_etf_tickers,
    stock_detail=lambda ticker: _stock_detail_service.detail(ticker=ticker, period="1y", profile=False),
    etf_detail=lambda ticker: _etf_api_service.etf_detail(ticker),
)

portfolio = _portfolio_api_service.portfolio
portfolio_prices = _portfolio_api_service.portfolio_prices
sector_themes = _sector_api_service.sector_themes
etfs = _etf_api_service.etfs
etf_detail = _etf_api_service.etf_detail
news_issues = _news_api_service.news_issues
market_indices = _market_api_service.market_indices
market_indicators = _market_api_service.market_indicators
market_indicator_history = _market_api_service.market_indicator_history
search_universe = _search_api_service.search_universe
calendar_earnings = _calendar_api_service.calendar_earnings
earnings_calendar = _calendar_api_service.earnings_calendar
earnings = _calendar_api_service.earnings
macro = _calendar_api_service.macro
signal_events = _risk_api_service.signal_events
comparison_recommendations = _risk_api_service.comparison_recommendations
backtest = _risk_api_service.backtest
smallcap_backtest = _risk_api_service.smallcap_backtest
risk_drift = _risk_api_service.risk_drift
portfolio_risk = _risk_api_service.portfolio_risk
rebalance_report = _risk_api_service.rebalance_report
shadow_attribution = _risk_api_service.shadow_attribution
risk_industry = _risk_api_service.risk_industry
risk_order_flow = _risk_api_service.risk_order_flow
ops_pipeline_runs = _ops_api_service.pipeline_runs
ops_research_health = _ops_api_service.research_health
ops_data_quality = _ops_api_service.data_quality
ops_data_sources = _ops_api_service.data_sources
ops_performance = _ops_api_service.performance
ops_health = _ops_api_service.health
cache_clear = _ops_api_service.cache_clear

app.include_router(create_portfolio_router(_portfolio_api_service))
app.include_router(create_sector_router(_sector_api_service))
app.include_router(create_etf_router(_etf_api_service))
app.include_router(create_news_router(_news_api_service))
app.include_router(create_market_router(_market_api_service))
app.include_router(create_search_router(_search_api_service))
app.include_router(create_calendar_router(_calendar_api_service))
app.include_router(create_risk_router(_risk_api_service))
app.include_router(create_ops_router(_ops_api_service))
app.include_router(create_stock_router(_stock_detail_service))


@app.on_event("startup")
def _warm_mobile_cache_on_startup() -> None:
    enabled = str(os.environ.get("QUANT_API_AUTO_WARM", "1")).strip().lower()
    if enabled not in {"0", "false", "no", "off"}:
        _start_cache_warm("startup")
