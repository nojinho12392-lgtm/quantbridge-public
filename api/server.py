#!/usr/bin/env python3
"""
QuantBridge FastAPI Server
Reads Google Sheets (Jino_Quant_Database) and serves JSON to the iOS app.

Run:
    uvicorn api.server:app --host 0.0.0.0 --port 8000 --reload
"""

import sys
import math
import os
import time as _time
from contextlib import asynccontextmanager
from pathlib import Path
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo
from functools import lru_cache

_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT))

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
import pandas as pd
from api.auth import db as _auth_db
from api.auth import init_db as _init_auth_db
from api.auth import router as auth_router
from api.routers.etfs import create_etf_router
from api.routers.calendar import create_calendar_router
from api.routers.market import create_market_router
from api.routers.news import create_news_router
from api.routers.ops import create_ops_router
from api.routers.portfolio import create_portfolio_router
from api.routers.ranking import create_ranking_router
from api.routers.research import create_research_router
from api.routers.risk import create_risk_router
from api.routers.search import create_search_router
from api.routers.sectors import create_sector_router
from api.routers.stock import create_stock_router
from api.routers.system import create_system_router
from api.routers.training import create_training_router
from api.services.app_snapshots import ApiSnapshotCache
from api.services.cache_warm import ApiCacheWarmJobFactory, ApiCacheWarmer
from api.services.company_names import (
    apply_localized_names as _apply_localized_names,
    enrich_kr_company_identities as _enrich_kr_company_identities,
    _is_missing_kr_name,
    _naver_kr_identity,
    kr_code as _kr_code,
    localize_company_name_fields as _localize_company_name_fields,
    localized_company_name as _localized_company_name,
)
from api.services.calendar_api import CalendarApiService, EarningsCalendarDataProvider, EarningsCalendarPayloadBuilder
from api.services.etf_api import EtfApiService, EtfPayloadBuilder, EtfPriceEnricher
from api.services.market_api import (
    MarketApiService,
    MarketIndicatorHistoryBuilder,
    MarketIndicatorNaverBuilder,
    MarketIndicatorPayloadBuilder,
    MarketIndicatorQuoteBuilder,
    MarketIndicatorStorageBuilder,
)
from api.services.news_api import NewsApiService, NewsPayloadBuilder
from api.services.news_changes import NewsChangeEnricher
from api.services.news_internal import InternalNewsBuilder
from api.services.news_public import NewsPublicFormatter
from api.services.news_queries import NewsQueryPlanner
from api.services.news_sources import NewsSourceFetcher
from api.services.ops_api import OpsApiService, OpsCheckBuilder, OpsHealthPayloadBuilder, OpsRunPayloadBuilder
from api.services.portfolio_api import PortfolioApiService, PortfolioPayloadBuilder, PortfolioPriceEnricher
from api.services.ranking_api import RankingApiService, RankingPayloadBuilder
from api.services.research_api import ResearchApiService, ResearchPayloadBuilder
from api.services.risk_reports import RiskReportBuilder
from api.services.risk_api import (
    ComparisonRecommendationBuilder,
    RiskApiService,
    SignalEventsPayloadBuilder,
)
from api.services.search_api import SearchApiService
from api.services.sector_api import (
    SectorApiService,
    SectorDetailReconciler,
    SectorPriceMetricBuilder,
    SectorThemeClassifier,
    SectorThemePayloadBuilder,
)
from api.services.system_api import SystemApiService, SystemPayloadBuilder
from api.services.training_quiz_api import TrainingQuizApiService
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
from api.services.stock_quotes import YFinanceQuoteBatcher, YFinanceQuoteFrameBuilder
from api.services.stock_quotes import fast_info_get as _fast_info_get
from api.services.stock_identity import (
    StockIdentityLookup,
    clean_text as _clean_text,
    default_logo_url as _default_logo_url,
    first_float as _first_float,
    first_text as _first_text,
    identity_has_valuation as _identity_has_valuation,
    identity_payload_from_row as _identity_payload_from_row,
    infer_market as _infer_market,
    info_from_cached as _info_from_cached,
    merge_company_profile as _merge_company_profile,
    merge_identity_payload as _merge_identity_payload,
    price_records_from_yfinance as _price_records_from_yfinance,
    safe_float as _safe_float,
)
from api.services.storage_frames import (
    PORTFOLIO_NUMERIC_COLS,
    clean_dataframe_columns as _clean_dataframe_columns,
    clean_json_value as _clean,
    clean_meta_value as _clean_meta_value,
    coerce_numeric as _coerce,
    df_to_records as _df_to_records_core,
    infer_storage_market as _infer_storage_market,
    load_storage_df as _load_storage_df_core,
    normalize_portfolio_price_columns as _normalize_portfolio_price_columns,
    sheet_values_to_df as _sheet_values_to_df,
)
from sheets_client import get_spreadsheet
from quantbridge.config import get_settings
from quantbridge.quality import build_data_quality_report
from quantbridge.storage import QuantRepository
from quantbridge.ticker_policy import is_banned_ticker
from tools.check_kr_rank_health import check_health as _check_kr_rank_health

# ── Config ────────────────────────────────────────────────────────────────────

_SETTINGS    = get_settings()
_SPREADSHEET = _SETTINGS.spreadsheet_name
_US_MARKET_TZ = ZoneInfo("America/New_York")


def _allow_api_external_fetch() -> bool:
    return bool(getattr(_SETTINGS, "api_allow_external_fetch", False))


@asynccontextmanager
async def _api_lifespan(_app: FastAPI):
    _init_auth_db()
    _warm_mobile_cache_on_startup()
    yield


app = FastAPI(title="QuantBridge API", version="1.0.0", lifespan=_api_lifespan)
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


# ── Google Sheets client ──────────────────────────────────────────────────────

@lru_cache(maxsize=1)
def _spreadsheet():
    return get_spreadsheet(_SPREADSHEET)


@lru_cache(maxsize=1)
def _repository():
    return QuantRepository(_SETTINGS)


_api_snapshot_cache = ApiSnapshotCache(
    settings=_SETTINGS,
    repository=_repository,
    runtime_cached=_runtime_cached,
    runtime_invalidate=_runtime_invalidate,
)


def _cached(key: str, loader, ttl: int | None = None, stale_ttl: int | None = None):
    return _api_snapshot_cache.cached(key, loader, ttl=ttl, stale_ttl=stale_ttl)


def _invalidate(key: str) -> None:
    _api_snapshot_cache.invalidate(key)


def _write_app_snapshot(cache_key: str, payload: object) -> None:
    _api_snapshot_cache.write_snapshot(cache_key, payload)


# ── Helpers (frame normalization lives in api.services.storage_frames) ─────────

def _df_to_records(df: pd.DataFrame) -> list[dict]:
    return _df_to_records_core(df, localize=_localize_company_name_fields)


def _load_storage_df(sheet_name: str, market: str | None = None) -> pd.DataFrame:
    """Return latest storage snapshot, or an empty frame when not bootstrapped yet."""
    return _load_storage_df_core(
        _repository(),
        sheet_name,
        market=market,
        record_data_source=_record_data_source,
    )


_portfolio_price_enricher = PortfolioPriceEnricher(
    repository=lambda: _repository(),
    safe_float=lambda value: _safe_float(value),
    first_float=lambda *values: _first_float(*values),
    us_market_tz=_US_MARKET_TZ,
    is_us_equity_regular_session_open_loader=lambda: _is_us_equity_regular_session_open(),
    price_snapshot_batch_loader=lambda tickers, market: _portfolio_price_snapshot_batch(tickers, market),
    daily_change_batch_loader=lambda tickers, market, snapshots: _portfolio_daily_change_batch(
        tickers,
        market,
        snapshots,
    ),
)
_us_equity_regular_session_phase = _portfolio_price_enricher.us_equity_regular_session_phase
_us_equity_local_date = _portfolio_price_enricher.us_equity_local_date
_drop_unsettled_us_daily_rows = _portfolio_price_enricher.drop_unsettled_us_daily_rows
_drop_unsettled_us_daily_series = _portfolio_price_enricher.drop_unsettled_us_daily_series
_portfolio_price_metrics = _portfolio_price_enricher.portfolio_price_metrics
_portfolio_price_metrics_from_frame = _portfolio_price_enricher.portfolio_price_metrics_from_frame
_is_us_equity_regular_session_open = _portfolio_price_enricher.is_us_equity_regular_session_open
_should_use_last_regular_close_change = _portfolio_price_enricher.should_use_last_regular_close_change
_portfolio_price_metrics_batch = _portfolio_price_enricher.portfolio_price_metrics_batch
_portfolio_price_snapshot_batch = _portfolio_price_enricher.price_snapshot_batch
_daily_change_from_price_frame = _portfolio_price_enricher.daily_change_from_price_frame
_portfolio_daily_change_batch = _portfolio_price_enricher.daily_change_batch
_enrich_portfolio_price_fields = _portfolio_price_enricher.enrich_price_fields


def _portfolio_display_overrides(records: list[dict], market: str | None) -> list[dict]:
    if str(market or "").upper() != "US":
        return records
    return [row for row in records if not is_banned_ticker(row.get("Ticker"))]


_portfolio_payload_builder = PortfolioPayloadBuilder(
    infer_storage_market=lambda sheet_name: _infer_storage_market(sheet_name),
    load_storage_df=lambda sheet_name, market: _load_storage_df(sheet_name, market=market),
    normalize_portfolio_price_columns=lambda frame: _normalize_portfolio_price_columns(frame),
    coerce=lambda frame, cols: _coerce(frame, cols),
    df_to_records=lambda frame: _df_to_records(frame),
    enrich_price_fields=lambda records, market, max_fetch: _enrich_portfolio_price_fields(
        records,
        market,
        max_fetch=max_fetch,
    ),
    enrich_rank_change_fields=lambda records, dataset, market: _enrich_rank_change_fields(records, dataset, market),
    portfolio_display_overrides=lambda records, market: _portfolio_display_overrides(records, market),
    spreadsheet=lambda: _spreadsheet(),
    sheet_values_to_df=lambda rows, header: _sheet_values_to_df(rows, header),
    record_data_source=lambda dataset, source, **kwargs: _record_data_source(dataset, source, **kwargs),
    clean_meta_value=lambda value: _clean_meta_value(value),
    portfolio_numeric_cols=PORTFOLIO_NUMERIC_COLS,
)
_meta_key = _portfolio_payload_builder.meta_key
_first_frame_value = _portfolio_payload_builder.first_frame_value
_summary_metric_value = _portfolio_payload_builder.summary_metric_value
_weighted_expected_return = _portfolio_payload_builder.weighted_expected_return
_portfolio_meta_aliases = _portfolio_payload_builder.portfolio_meta_aliases
_portfolio_meta_from_storage = _portfolio_payload_builder.portfolio_meta_from_storage
_load_portfolio = _portfolio_payload_builder.load_portfolio


# ── Sheet parsers ─────────────────────────────────────────────────────────────


def _load_simple(sheet_name: str, num_cols: list[str]) -> list[dict]:
    """Simple sheets: row 0 = header, rest = data (no summary block)."""
    market = _infer_storage_market(sheet_name)
    storage_df = _load_storage_df(sheet_name, market=market)
    if not storage_df.empty:
        storage_df = _coerce(storage_df, num_cols)
        return _df_to_records(storage_df)

    try:
        ws = _spreadsheet().worksheet(sheet_name)
        data = ws.get_all_values()
    except Exception as exc:
        _record_data_source(sheet_name, "sheet_error", market=market, detail=f"{type(exc).__name__}: {exc}")
        return []
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
    market = _infer_storage_market(sheet_name)
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


_ranking_payload_builder = RankingPayloadBuilder(
    repository=lambda: _repository(),
    safe_float=lambda value: _safe_float(value),
    kr_code=lambda value: _kr_code(value),
    load_simple=lambda sheet, cols: _load_simple(sheet, cols),
    enrich_kr_company_identities=lambda rows: _enrich_kr_company_identities(rows),
    enrich_portfolio_price_fields=lambda records, market, max_fetch: _enrich_portfolio_price_fields(
        records,
        market,
        max_fetch=max_fetch,
    ),
    apply_localized_names=lambda payload: _apply_localized_names(payload),
    data_source_events=lambda: _data_source_events,
    utc_now_iso=lambda: _utc_now_iso(),
)
_rank_match_keys = _ranking_payload_builder.rank_match_keys
_enrich_rank_change_fields = _ranking_payload_builder.enrich_rank_change_fields
_smallcap_payload = _ranking_payload_builder.smallcap_payload
_scored_payload = _ranking_payload_builder.scored_payload


_risk_report_builder = RiskReportBuilder(
    load_storage_df=lambda dataset, market=None: _load_storage_df(dataset, market=market),
    spreadsheet=lambda: _spreadsheet(),
    sheet_values_to_df=lambda rows, header: _sheet_values_to_df(rows, header),
    coerce=lambda frame, cols: _coerce(frame, cols),
    record_data_source=lambda dataset, source, **kwargs: _record_data_source(dataset, source, **kwargs),
    df_to_records=lambda frame: _df_to_records(frame),
    safe_float=lambda value: _safe_float(value),
    load_table=lambda sheet, cols=None: _load_table(sheet, cols),
)
_load_backtest_df = _risk_report_builder.load_backtest_df
_backtest_payload = _risk_report_builder.backtest_payload
_risk_drift_payload = _risk_report_builder.risk_drift_payload
_load_sectioned_sheet_table = _risk_report_builder.load_sectioned_sheet_table
_load_report_dataset = _risk_report_builder.load_report_dataset
_portfolio_risk_payload = _risk_report_builder.portfolio_risk_payload
_rebalance_payload = _risk_report_builder.rebalance_payload
_shadow_attribution_payload = _risk_report_builder.shadow_attribution_payload
_industry_payload = _risk_report_builder.industry_payload
_order_flow_payload = _risk_report_builder.order_flow_payload


_news_query_planner = NewsQueryPlanner(
    market_indicators_payload=lambda category="all", refresh=False: _market_indicators_payload(
        category=category,
        refresh=refresh,
    ),
    safe_float=lambda value: _safe_float(value),
    cached=lambda key, loader, ttl=None: _cached(key, loader, ttl=ttl),
)
_strip_news_html = _news_query_planner.strip_news_html
_default_news_query = _news_query_planner.default_news_query
_default_news_queries = _news_query_planner.default_news_queries
_discovery_news_queries = _news_query_planner.discovery_news_queries
_market_news_query_direction = _news_query_planner.market_news_query_direction
_market_news_query_threshold = _news_query_planner.market_news_query_threshold
_market_news_query_allowed = _news_query_planner.market_news_query_allowed
_dynamic_market_news_queries = _news_query_planner.dynamic_market_news_queries
_news_query_plan = _news_query_planner.news_query_plan
_news_cache_query_key = _news_query_planner.news_cache_query_key
_news_market_matches = _news_query_planner.news_market_matches
_news_dedupe_key = _news_query_planner.news_dedupe_key
_html_attr = _news_query_planner.html_attr
_normalize_news_image_url = _news_query_planner.normalize_news_image_url
_extract_news_image_url = _news_query_planner.extract_news_image_url
_news_image_url_for_url = _news_query_planner.news_image_url_for_url
_enrich_news_image_fields = _news_query_planner.enrich_news_image_fields
_round_robin_news_buckets = _news_query_planner.round_robin_news_buckets
_news_published_datetime = _news_query_planner.news_published_datetime
_news_recency_score = _news_query_planner.news_recency_score
_news_breaking_score = _news_query_planner.news_breaking_score
_news_query_type_score = _news_query_planner.news_query_type_score
_news_pre_importance_score = _news_query_planner.news_pre_importance_score
_news_importance_score = _news_query_planner.news_importance_score
_diversify_news_items = _news_query_planner.diversify_news_items
_news_query_type = _news_query_planner.news_query_type


_internal_news_builder = InternalNewsBuilder(
    utc_now_iso=lambda: _utc_now_iso(),
    macro_payload=lambda: _macro_payload(),
    load_simple=lambda sheet, cols: _load_simple(sheet, cols),
    safe_float=lambda value: _safe_float(value),
    enrich_kr_company_identities=lambda rows: _enrich_kr_company_identities(rows),
    risk_drift_payload=lambda: _risk_drift_payload(),
    order_flow_payload=lambda limit: _order_flow_payload(limit=limit),
)
_internal_issue_news = _internal_news_builder.internal_issue_news


_news_source_fetcher = NewsSourceFetcher(
    naver_client_id=_SETTINGS.naver_client_id,
    naver_client_secret=_SETTINGS.naver_client_secret,
    strip_news_html=lambda value: _strip_news_html(value),
    news_market_matches=lambda title, summary, market: _news_market_matches(title, summary, market),
    news_query_plan=lambda query, market: _news_query_plan(query, market),
    news_query_type=lambda query, market: _news_query_type(query, market),
    round_robin_news_buckets=lambda buckets, limit: _round_robin_news_buckets(buckets, limit),
)
_naver_news_items_for_query = _news_source_fetcher.naver_news_items_for_query
_naver_news_items = _news_source_fetcher.naver_news_items
_xml_child_text = _news_source_fetcher.xml_child_text
_xml_child_attr = _news_source_fetcher.xml_child_attr
_rss_datetime = _news_source_fetcher.rss_datetime
_rss_published_datetime = _news_source_fetcher.rss_published_datetime
_rss_news_is_fresh = _news_source_fetcher.rss_news_is_fresh
_rss_news_is_market_relevant = _news_source_fetcher.rss_news_is_market_relevant
_normalize_url_key = _news_source_fetcher.normalize_url_key
_rss_news_items_for_source = _news_source_fetcher.rss_news_items_for_source
_rss_news_items = _news_source_fetcher.rss_news_items


_news_change_enricher = NewsChangeEnricher(
    kr_code=lambda value: _kr_code(value),
    infer_market_from_ticker=lambda ticker: _infer_market_from_ticker(ticker),
    safe_float=lambda value: _safe_float(value),
    naver_indicator_float=lambda value: _naver_indicator_float(value),
    cached=lambda key, loader, ttl=None: _cached(key, loader, ttl=ttl),
    portfolio_price_snapshot_batch=lambda tickers, market: _portfolio_price_snapshot_batch(tickers, market),
    portfolio_daily_change_batch=lambda tickers, market, snapshots: _portfolio_daily_change_batch(
        tickers,
        market,
        snapshots,
    ),
    market_indicators_payload=lambda **kwargs: _market_indicators_payload(**kwargs),
    allow_external_fetch=lambda: _allow_api_external_fetch(),
    naver_kr_change_batch=lambda tickers: _naver_kr_stock_change_batch(tickers),
    default_market_change=lambda item, safe_market: _news_default_market_change(item, safe_market),
)
_news_price_ticker = _news_change_enricher.news_price_ticker
_news_ticker_terms = _news_change_enricher.news_ticker_terms
_news_ticker_mention_index = _news_change_enricher.news_ticker_mention_index
_news_change_display_label = _news_change_enricher.news_change_display_label
_news_kr_quote_horizon = _news_change_enricher.news_kr_quote_horizon
_naver_kr_stock_quote_rows = _news_change_enricher.naver_kr_stock_quote_rows
_naver_kr_stock_change_batch = _news_change_enricher.naver_kr_stock_change_batch
_naver_kr_stock_price_batch = _news_change_enricher.naver_kr_stock_price_batch
_news_change_candidates = _news_change_enricher.news_change_candidates
_news_market_change_symbol = _news_change_enricher.news_market_change_symbol
_news_default_market_change = _news_change_enricher.news_default_market_change
_news_should_use_market_change = _news_change_enricher.news_should_use_market_change
_enrich_news_change_fields = _news_change_enricher.enrich_news_change_fields


_news_public_formatter = NewsPublicFormatter(
    change_display_label=lambda ticker: _news_change_display_label(ticker),
)
_news_public_market_label = _news_public_formatter.public_market_label
_news_public_impact_label = _news_public_formatter.public_impact_label
_news_public_subject = _news_public_formatter.public_subject
_news_public_title = _news_public_formatter.public_title
_news_public_item = _news_public_formatter.public_item
_news_public_items = _news_public_formatter.public_items


_news_payload_builder = NewsPayloadBuilder(
    naver_configured=bool(_SETTINGS.naver_client_id and _SETTINGS.naver_client_secret),
    default_news_query=lambda market: _default_news_query(market),
    naver_news_items=lambda query, market, limit: _naver_news_items(query, market, limit),
    rss_news_items=lambda market, limit: _rss_news_items(market, limit),
    diversify_news_items=lambda items, limit: _diversify_news_items(items, limit),
    news_pre_importance_score=lambda item: _news_pre_importance_score(item),
    news_importance_score=lambda item: _news_importance_score(item),
    enrich_news_items=lambda items, market: _enrich_news_items(items, market),
    enrich_news_change_fields=lambda items, market: _enrich_news_change_fields(items, market),
    news_public_items=lambda items, market: _news_public_items(items, market),
    news_query_plan=lambda query, market: _news_query_plan(query, market),
)
_news_payload = _news_payload_builder.payload


_signal_events_payload_builder = SignalEventsPayloadBuilder(
    repository=lambda: _repository(),
    df_to_records=lambda df: _df_to_records(df),
    utc_now_iso=lambda: _utc_now_iso(),
)
_signal_events_payload = _signal_events_payload_builder.payload


_comparison_recommendation_builder = ComparisonRecommendationBuilder(
    kr_code=lambda value: _kr_code(value),
    infer_market_from_ticker=lambda ticker: _infer_market_from_ticker(ticker),
    localized_company_name=lambda ticker, name, market: _localized_company_name(ticker, name, market),
    safe_float=lambda value: _safe_float(value),
    first_float=lambda *values: _first_float(*values),
    load_portfolio=lambda sheet_name: _load_portfolio(sheet_name),
    load_simple=lambda sheet_name, num_cols: _load_simple(sheet_name, num_cols),
    utc_now_iso=lambda: _utc_now_iso(),
)
_comparison_currency = _comparison_recommendation_builder.currency
_comparison_item = _comparison_recommendation_builder.item
_comparison_match_keys = _comparison_recommendation_builder.match_keys
_comparison_recommendation_payload = _comparison_recommendation_builder.payload


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


_sector_theme_classifier = SectorThemeClassifier(
    theme_overrides=_SECTOR_THEME_OVERRIDES,
    theme_rules=_SECTOR_THEME_RULES,
    theme_seed_groups=_SECTOR_THEME_SEED_GROUPS,
    kr_code=lambda ticker: _kr_code(ticker),
    localized_company_name=lambda ticker, name, market: _localized_company_name(ticker, name, market),
    load_table=lambda sheet, cols=None: _load_table(sheet, cols),
    df_to_records=lambda frame: _df_to_records(frame),
    load_portfolio=lambda sheet: _load_portfolio(sheet),
    load_simple=lambda sheet, cols: _load_simple(sheet, cols),
)
_sector_compact = _sector_theme_classifier.compact
_sector_term_matches = _sector_theme_classifier.term_matches
_sector_theme_label = _sector_theme_classifier.theme_label
_sector_theme_labels = _sector_theme_classifier.theme_labels
_sector_fallback_theme_label = _sector_theme_classifier.fallback_theme_label
_sector_stock_key = _sector_theme_classifier.stock_key
_sector_source_priority = _sector_theme_classifier.source_priority
_is_sector_equity_row = _sector_theme_classifier.is_equity_row
_sector_theme_seed_rows = _sector_theme_classifier.theme_seed_rows
_sector_market_rows = _sector_theme_classifier.market_rows


# ── Endpoints ─────────────────────────────────────────────────────────────────

_system_payload_builder = SystemPayloadBuilder(
    utc_now=lambda: _utc_now(),
    auth_db=lambda: _auth_db(),
    repository=lambda: _repository(),
    enable_postgres=lambda: _SETTINGS.enable_postgres,
)
_health_payload = _system_payload_builder.health_payload
_ready_payload = _system_payload_builder.ready_payload


_yfinance_quote_frame_builder = YFinanceQuoteFrameBuilder(
    kr_code=lambda value: _kr_code(value),
)
_yf_stock_symbol = _yfinance_quote_frame_builder.stock_symbol
_yf_close_frame = _yfinance_quote_frame_builder.close_frame
_yf_download_close_frame = _yfinance_quote_frame_builder.download_close_frame


_yfinance_quote_batcher = YFinanceQuoteBatcher(
    yf_stock_symbol=lambda ticker, market: _yf_stock_symbol(ticker, market),
    should_use_last_regular_close_change=lambda market: _should_use_last_regular_close_change(market),
    safe_float=lambda value: _safe_float(value),
    yf_download_close_frame=lambda yf, symbols, period, interval: _yf_download_close_frame(
        yf,
        symbols,
        period=period,
        interval=interval,
    ),
    drop_unsettled_us_daily_series=lambda series, market: _drop_unsettled_us_daily_series(series, market),
    comparison_match_keys=lambda ticker: _comparison_match_keys(ticker),
    utc_now_iso=lambda: _utc_now_iso(),
)
_yfinance_stock_quote_batch = _yfinance_quote_batcher.yfinance_stock_quote_batch


_sector_price_metric_builder = SectorPriceMetricBuilder(
    stored_indicator_latest=lambda symbols: _stored_indicator_latest(symbols),
    safe_float=lambda value: _safe_float(value),
    comparison_match_keys=lambda ticker: _comparison_match_keys(ticker),
    portfolio_price_snapshot_batch=lambda tickers, market: _portfolio_price_snapshot_batch(tickers, market),
    portfolio_price_metrics_batch=lambda tickers, market: _portfolio_price_metrics_batch(tickers, market),
    portfolio_daily_change_batch=lambda tickers, market, snapshots: _portfolio_daily_change_batch(
        tickers,
        market,
        snapshots,
    ),
    naver_kr_stock_price_batch=lambda tickers: _naver_kr_stock_price_batch(tickers),
    yfinance_stock_quote_batch=lambda tickers, market: _yfinance_stock_quote_batch(tickers, market),
    allow_external_fetch=lambda: _allow_api_external_fetch(),
)
_SECTOR_PRICE_REQUEST_LIMIT = _sector_price_metric_builder.PRICE_REQUEST_LIMIT
_SECTOR_US_LIVE_QUOTE_LIMIT = _sector_price_metric_builder.US_LIVE_QUOTE_LIMIT
_SECTOR_DETAIL_RECONCILE_LIMIT = 40
_SECTOR_FALLBACK_USDKRW = _sector_price_metric_builder.FALLBACK_USDKRW
_sector_usdkrw_rate = _sector_price_metric_builder.usdkrw_rate
_sector_market_cap_usd_sort_value = _sector_price_metric_builder.market_cap_usd_sort_value
_sector_market_cap_weighted_value = _sector_price_metric_builder.market_cap_weighted_value
_sector_price_metric_map = _sector_price_metric_builder.price_metric_map


_sector_theme_payload_builder = SectorThemePayloadBuilder(
    theme_order=_SECTOR_THEME_ORDER,
    price_request_limit=_SECTOR_PRICE_REQUEST_LIMIT,
    detail_reconcile_limit=_SECTOR_DETAIL_RECONCILE_LIMIT,
    sector_market_rows=lambda market: _sector_market_rows(market),
    infer_market_from_ticker=lambda ticker: _infer_market_from_ticker(ticker),
    localized_company_name=lambda ticker, name, market: _localized_company_name(ticker, name, market),
    first_float=lambda *values: _first_float(*values),
    sector_theme_labels=lambda ticker, name, sector=None, explicit_theme=None: _sector_theme_labels(
        ticker,
        name,
        sector,
        explicit_theme=explicit_theme,
    ),
    sector_stock_key=lambda ticker: _sector_stock_key(ticker),
    sector_source_priority=lambda source: _sector_source_priority(source),
    safe_float=lambda value: _safe_float(value),
    sector_compact=lambda value: _sector_compact(value),
    sector_price_metric_map=lambda market, tickers, live_tickers=None: _sector_price_metric_map(
        market,
        tickers,
        live_tickers=live_tickers,
    ),
    comparison_match_keys=lambda ticker: _comparison_match_keys(ticker),
    comparison_currency=lambda ticker, market: _comparison_currency(ticker, market),
    sector_usdkrw_rate=lambda: _sector_usdkrw_rate(),
    sector_market_cap_weighted_value=lambda items, value_key, usdkrw_rate: _sector_market_cap_weighted_value(
        items,
        value_key,
        usdkrw_rate,
    ),
    sector_market_cap_usd_sort_value=lambda item, usdkrw_rate: _sector_market_cap_usd_sort_value(
        item,
        usdkrw_rate,
    ),
    sector_detail_reconcile_members=lambda members: _sector_detail_reconcile_members(members),
    utc_now_iso=lambda: _utc_now_iso(),
)
_sector_themes_payload = _sector_theme_payload_builder.payload


_earnings_calendar_data_provider = EarningsCalendarDataProvider(
    api_sqlite_path=_SETTINGS.api_sqlite_path,
    spreadsheet=lambda: _spreadsheet(),
    load_storage_df=lambda sheet_name, market=None: _load_storage_df(sheet_name, market=market),
    load_table=lambda sheet_name: _load_table(sheet_name),
    sheet_values_to_df=lambda rows, header: _sheet_values_to_df(rows, header),
    clean_meta_value=lambda value: _clean_meta_value(value),
    safe_float=lambda value: _safe_float(value),
    first_float=lambda *values: _first_float(*values),
    kr_code=lambda value: _kr_code(value),
    record_data_source=lambda dataset, source, **kwargs: _record_data_source(dataset, source, **kwargs),
)
_parse_calendar_date = _earnings_calendar_data_provider.parse_calendar_date
_infer_market_from_ticker = _earnings_calendar_data_provider.infer_market_from_ticker
_normalize_us_calendar_ticker = _earnings_calendar_data_provider.normalize_us_calendar_ticker
_extract_sp500_symbols = _earnings_calendar_data_provider.extract_sp500_symbols
_sp500_ticker_set = _earnings_calendar_data_provider.sp500_ticker_set
_us_calendar_allowed_tickers = _earnings_calendar_data_provider.us_calendar_allowed_tickers
_earnings_calendar_sqlite_df = _earnings_calendar_data_provider.earnings_calendar_sqlite_df
_company_identity_lookup = _earnings_calendar_data_provider.company_identity_lookup
_load_earnings_calendar_frame = _earnings_calendar_data_provider.load_earnings_calendar_frame
_market_cap_text_to_float = _earnings_calendar_data_provider.market_cap_text_to_float
_calendar_market_cap = _earnings_calendar_data_provider.calendar_market_cap
_extract_yfinance_earnings_date = _earnings_calendar_data_provider.extract_yfinance_earnings_date
_yahoo_earnings_calendar_seed_rows = _earnings_calendar_data_provider.yahoo_earnings_calendar_seed_rows
_verified_earnings_calendar_record = _earnings_calendar_data_provider.verified_earnings_calendar_record
_fetch_verified_earnings_calendar_df = _earnings_calendar_data_provider.fetch_verified_earnings_calendar_df


_earnings_calendar_builder = EarningsCalendarPayloadBuilder(
    load_earnings_calendar_frame=lambda: _load_earnings_calendar_frame(),
    fetch_verified_earnings_calendar_df=lambda today, days, limit: _fetch_verified_earnings_calendar_df(
        today,
        days,
        limit,
    ),
    clean_dataframe_columns=lambda frame: _clean_dataframe_columns(frame),
    company_identity_lookup=lambda: _company_identity_lookup(),
    us_calendar_allowed_tickers=lambda: _us_calendar_allowed_tickers(),
    parse_calendar_date=lambda value: _parse_calendar_date(value),
    infer_market_from_ticker=lambda ticker: _infer_market_from_ticker(ticker),
    normalize_us_calendar_ticker=lambda ticker: _normalize_us_calendar_ticker(ticker),
    kr_code=lambda value: _kr_code(value),
    identity_payload_from_row=lambda row, ticker, market, source: _identity_payload_from_row(
        row,
        ticker,
        market,
        source,
    ),
    first_text=lambda *values: _first_text(*values),
    first_float=lambda *values: _first_float(*values),
    calendar_market_cap=lambda market, *values: _calendar_market_cap(market, *values),
    is_missing_kr_name=lambda name, ticker: _is_missing_kr_name(name, ticker),
    naver_kr_identity=lambda ticker: _naver_kr_identity(ticker),
    safe_float=lambda value: _safe_float(value),
)
_build_earnings_calendar_rows = _earnings_calendar_builder.build_rows
_earnings_calendar_payload = _earnings_calendar_builder.payload
_earnings_calendar_sort_key = _earnings_calendar_builder.sort_key


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
_NAVER_DOMESTIC_INDEX_CODES = {"^KS11": "KOSPI", "^KQ11": "KOSDAQ"}
_NAVER_DOMESTIC_INTEREST_CODES = {
    "IRR_GOVT03Y": "IRR_GOVT03Y",
    "IRR_CORP03Y": "IRR_CORP03Y",
}
_MARKET_HISTORY_PERIODS = {"1d": 1, "5d": 5, "1mo": 31, "3mo": 93}
_MARKET_HISTORY_INTERVALS = {"1m", "2m", "5m", "15m", "30m", "60m", "1h", "1d"}
_KST = timezone(timedelta(hours=9))


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


_etf_price_enricher = EtfPriceEnricher(
    kr_code=lambda value: _kr_code(value),
    portfolio_price_snapshot_batch=lambda tickers, market: _portfolio_price_snapshot_batch(tickers, market),
    portfolio_daily_change_batch=lambda tickers, market, snapshots: _portfolio_daily_change_batch(
        tickers,
        market,
        snapshots,
    ),
    portfolio_price_metrics_batch=lambda tickers, market: _portfolio_price_metrics_batch(tickers, market),
    first_float=lambda *values: _first_float(*values),
)
_etf_price_lookup_ticker = _etf_price_enricher.price_lookup_ticker
_price_change_from_return = _etf_price_enricher.price_change_from_return
_enrich_etf_price_fields = _etf_price_enricher.enrich_price_fields


_etf_payload_builder = EtfPayloadBuilder(
    payload_from_records=lambda records, **kwargs: _etf_payload_from_records(records, **kwargs),
    storage_records=lambda: _etf_storage_records(),
    yahoo_search_records=lambda q, limit: _search_api_service.yahoo_etf_search_records(q, limit=limit),
    enrich_price_fields=lambda items: _enrich_etf_price_fields(items),
)
_etf_payload_with_prices = _etf_payload_builder.payload_with_prices


_market_indicator_storage_builder = MarketIndicatorStorageBuilder(
    market_indicator_specs=_MARKET_INDICATOR_SPECS,
    market_indicator_symbols=_MARKET_INDICATOR_SYMBOLS,
    us_regular_index_symbols=_US_REGULAR_INDEX_SYMBOLS,
    kr_regular_index_symbols=_KR_REGULAR_INDEX_SYMBOLS,
    us_market_tz=_US_MARKET_TZ,
    kst=_KST,
    repository=lambda: _repository(),
    df_to_records=lambda frame: _df_to_records(frame),
    safe_float=lambda value: _safe_float(value),
    first_float=lambda *values: _first_float(*values),
    utc_now_iso=lambda: _utc_now_iso(),
    load_naver_market_indicator_quotes=lambda specs: _load_naver_market_indicator_quotes(specs),
    indicator_daily_close_quote_from_yfinance=lambda ticker, spec, regular_session=False: _indicator_daily_close_quote_from_yfinance(
        ticker,
        spec,
        regular_session=regular_session,
    ),
    previous_regular_close_loader=lambda symbol, observed_at: _previous_regular_close_from_storage(symbol, observed_at),
)
_stored_indicator_items = _market_indicator_storage_builder.stored_indicator_items
_with_regular_previous_close_change_from_storage = (
    _market_indicator_storage_builder.with_regular_previous_close_change_from_storage
)
_previous_regular_close_from_storage = _market_indicator_storage_builder.previous_regular_close_from_storage
_merge_naver_domestic_indicator_quotes = _market_indicator_storage_builder.merge_naver_domestic_indicator_quotes
_merge_closed_regular_index_quotes = _market_indicator_storage_builder.merge_closed_regular_index_quotes
_indicator_specs = _market_indicator_storage_builder.indicator_specs
_spec_public_fields = _market_indicator_storage_builder.spec_public_fields
_should_use_latest_regular_close = _market_indicator_storage_builder.should_use_latest_regular_close
_is_regular_index_session_open = _market_indicator_storage_builder.is_regular_index_session_open
_regular_index_zone = _market_indicator_storage_builder.regular_index_zone
_regular_index_session_times = _market_indicator_storage_builder.regular_index_session_times
_daily_index_observed_at = _market_indicator_storage_builder.daily_index_observed_at
_indicator_quote_is_older = _market_indicator_storage_builder.indicator_quote_is_older
_indicator_observed_datetime = _market_indicator_storage_builder.indicator_observed_datetime
_stored_indicator_latest = _market_indicator_storage_builder.stored_indicator_latest
_selected_indicator_symbols = _market_indicator_storage_builder.selected_indicator_symbols
_history_frame_for_symbol = _market_indicator_storage_builder.history_frame_for_symbol


_market_indicator_payload_builder = MarketIndicatorPayloadBuilder(
    repository=lambda: _repository(),
    indicator_specs=lambda category: _indicator_specs(category),
    stored_indicator_latest=lambda symbols: _stored_indicator_latest(symbols),
    stored_indicator_items=lambda specs, stored: _stored_indicator_items(specs, stored),
    merge_naver_domestic_indicator_quotes=lambda items, specs: _merge_naver_domestic_indicator_quotes(items, specs),
    allow_api_external_fetch=lambda: _allow_api_external_fetch(),
    utc_now_iso=lambda: _utc_now_iso(),
    merge_closed_regular_index_quotes=lambda items, specs: _merge_closed_regular_index_quotes(items, specs),
    indicator_quote_from_yfinance=lambda yf, spec: _indicator_quote_from_yfinance(yf, spec),
    is_naver_domestic_interest_symbol=lambda symbol: _is_naver_domestic_interest_symbol(symbol),
    load_naver_market_indicator_quotes=lambda specs: _load_naver_market_indicator_quotes(specs),
    market_history_periods=_MARKET_HISTORY_PERIODS,
    empty_frame=lambda: pd.DataFrame(),
    stored_indicator_history_is_sparse=lambda stored, symbols, interval: _stored_indicator_history_is_sparse(
        stored,
        symbols,
        interval,
    ),
    fetch_indicator_history=lambda symbols, period, interval: _fetch_indicator_history(symbols, period, interval),
    df_to_records=lambda frame: _df_to_records(frame),
    latest_naver_domestic_indicator_history_points=lambda symbols: _latest_naver_domestic_indicator_history_points(
        symbols,
    ),
    merge_indicator_history_records=lambda records, latest_points: _merge_indicator_history_records(
        records,
        latest_points,
    ),
    market_indicator_specs=_MARKET_INDICATOR_SPECS,
    safe_float=lambda value: _safe_float(value),
    filter_indicator_history_points_for_period=lambda symbol, points, period: _filter_indicator_history_points_for_period(
        symbol,
        points,
        period,
    ),
    spec_public_fields=lambda spec: _spec_public_fields(spec),
)
_load_market_indicators = _market_indicator_payload_builder.load_market_indicators
_market_indicator_history_payload = _market_indicator_payload_builder.market_indicator_history_payload


_market_indicator_naver_builder = MarketIndicatorNaverBuilder(
    naver_domestic_index_codes=_NAVER_DOMESTIC_INDEX_CODES,
    naver_domestic_interest_codes=_NAVER_DOMESTIC_INTEREST_CODES,
    kst=_KST,
    safe_float=lambda value: _safe_float(value),
    utc_now_iso=lambda: _utc_now_iso(),
    indicator_observed_datetime=lambda item: _indicator_observed_datetime(item),
    regular_index_zone=lambda symbol: _regular_index_zone(symbol),
    regular_index_session_times=lambda symbol: _regular_index_session_times(symbol),
    spec_public_fields=lambda spec: _spec_public_fields(spec),
)
_load_naver_market_indicator_quotes = _market_indicator_naver_builder.load_naver_market_indicator_quotes
_naver_domestic_index_observed_at = _market_indicator_naver_builder.naver_domestic_index_observed_at
_load_naver_domestic_indicator_quotes = _market_indicator_naver_builder.load_naver_domestic_indicator_quotes
_load_naver_domestic_interest_quotes = _market_indicator_naver_builder.load_naver_domestic_interest_quotes
_is_naver_domestic_interest_symbol = _market_indicator_naver_builder.is_naver_domestic_interest_symbol
_fetch_naver_interest_rows = _market_indicator_naver_builder.fetch_naver_interest_rows
_parse_naver_interest_rows = _market_indicator_naver_builder.parse_naver_interest_rows
_clean_html_cell = _market_indicator_naver_builder.clean_html_cell
_naver_interest_observed_at = _market_indicator_naver_builder.naver_interest_observed_at
_naver_indicator_float = _market_indicator_naver_builder.naver_indicator_float


_market_indicator_quote_builder = MarketIndicatorQuoteBuilder(
    safe_float=lambda value: _safe_float(value),
    fast_info_get=lambda fast_info, key: _fast_info_get(fast_info, key),
    should_use_latest_regular_close=lambda symbol: _should_use_latest_regular_close(symbol),
    daily_close_quote_from_yfinance=lambda ticker, spec, regular_session=False: _indicator_daily_close_quote_from_yfinance(
        ticker,
        spec,
        regular_session=regular_session,
    ),
    is_regular_index_session_open=lambda symbol: _is_regular_index_session_open(symbol),
    intraday_quote_from_yfinance=lambda ticker, spec: _indicator_intraday_quote_from_yfinance(ticker, spec),
    regular_index_zone=lambda symbol: _regular_index_zone(symbol),
    spec_public_fields=lambda spec: _spec_public_fields(spec),
    utc_now_iso=lambda: _utc_now_iso(),
    previous_regular_close_loader=lambda ticker, symbol: _previous_regular_close_from_yfinance(ticker, symbol),
    daily_index_observed_at=lambda index_value, symbol: _daily_index_observed_at(index_value, symbol),
)
_indicator_quote_from_yfinance = _market_indicator_quote_builder.indicator_quote_from_yfinance
_indicator_intraday_quote_from_yfinance = _market_indicator_quote_builder.indicator_intraday_quote_from_yfinance
_previous_regular_close_from_yfinance = _market_indicator_quote_builder.previous_regular_close_from_yfinance
_indicator_daily_close_quote_from_yfinance = _market_indicator_quote_builder.indicator_daily_close_quote_from_yfinance


_market_indicator_history_builder = MarketIndicatorHistoryBuilder(
    market_indicator_specs=_MARKET_INDICATOR_SPECS,
    market_history_periods=_MARKET_HISTORY_PERIODS,
    naver_domestic_index_codes=_NAVER_DOMESTIC_INDEX_CODES,
    kst=_KST,
    load_naver_domestic_indicator_quotes=lambda specs: _load_naver_domestic_indicator_quotes(specs),
    fetch_naver_interest_rows=lambda symbol, pages=1: _fetch_naver_interest_rows(symbol, pages=pages),
    naver_interest_observed_at=lambda date_text: _naver_interest_observed_at(date_text),
    history_frame_for_symbol=lambda raw, symbol, single_symbol: _history_frame_for_symbol(raw, symbol, single_symbol),
    regular_index_zone=lambda symbol: _regular_index_zone(symbol),
    regular_index_session_times=lambda symbol: _regular_index_session_times(symbol),
    indicator_observed_datetime=lambda item: _indicator_observed_datetime(item),
    is_naver_domestic_interest_symbol=lambda symbol: _is_naver_domestic_interest_symbol(symbol),
    spec_public_fields=lambda spec: _spec_public_fields(spec),
    safe_float=lambda value: _safe_float(value),
)
_latest_naver_domestic_indicator_history_points = (
    _market_indicator_history_builder.latest_naver_domestic_indicator_history_points
)
_merge_indicator_history_records = _market_indicator_history_builder.merge_indicator_history_records
_filter_indicator_history_points_for_period = (
    _market_indicator_history_builder.filter_indicator_history_points_for_period
)
_stored_indicator_history_is_sparse = _market_indicator_history_builder.stored_indicator_history_is_sparse
_fetch_indicator_history = _market_indicator_history_builder.fetch_indicator_history
_append_indicator_history_points = _market_indicator_history_builder.append_indicator_history_points
_fetch_naver_domestic_interest_history = _market_indicator_history_builder.fetch_naver_domestic_interest_history


def _utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def _utc_now_iso() -> str:
    return _utc_now().replace(microsecond=0).isoformat()


def _macro_payload() -> dict:
    df = _load_storage_df("Macro_Regime", market="GLOBAL")
    if not df.empty and {"Key", "Value"}.issubset(df.columns):
        return {
            str(row["Key"]).strip(): str(row["Value"]).strip()
            for _, row in df.iterrows()
            if str(row["Key"]).strip()
        }
    try:
        ws = _spreadsheet().worksheet("Macro_Regime")
    except Exception as exc:
        _record_data_source("Macro_Regime", "sheet_error", market="GLOBAL", detail=f"{type(exc).__name__}: {exc}")
        return {}
    return {
        r[0].strip(): r[1].strip()
        for r in ws.get_all_values()
        if len(r) >= 2 and r[0].strip()
    }


_research_payload_builder = ResearchPayloadBuilder(
    load_simple=lambda sheet, cols: _load_simple(sheet, cols),
    safe_float=lambda value: _safe_float(value),
    utc_now=lambda: _utc_now(),
    load_storage_df=lambda sheet, market: _load_storage_df(sheet, market=market),
    coerce=lambda frame, cols: _coerce(frame, cols),
    df_to_records=lambda frame: _df_to_records(frame),
)
_research_factor_quality_payload = _research_payload_builder.factor_quality_payload
_research_factor_policy_payload = _research_payload_builder.factor_policy_payload
_ml_blend_status = _research_payload_builder.ml_blend_status
_research_ml_blend_payload = _research_payload_builder.ml_blend_payload
_research_policy_backtest_payload = _research_payload_builder.policy_backtest_payload
_research_policy_adjusted_ranking_payload = _research_payload_builder.policy_adjusted_ranking_payload
_research_remediation_plan_payload = _research_payload_builder.remediation_plan_payload


_ops_run_payload_builder = OpsRunPayloadBuilder(
    repository=lambda: _repository(),
    safe_float=lambda value: _safe_float(value),
    utc_now=lambda: _utc_now(),
)
_run_scripts = _ops_run_payload_builder.run_scripts
_run_kind = _ops_run_payload_builder.run_kind
_run_elapsed = _ops_run_payload_builder.run_elapsed
_pipeline_runs_payload = _ops_run_payload_builder.pipeline_runs_payload
_parse_iso_datetime = _ops_run_payload_builder.parse_iso_datetime
_age_hours = _ops_run_payload_builder.age_hours
_research_health_payload = _ops_run_payload_builder.research_health_payload


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


_ops_check_builder = OpsCheckBuilder(
    check=lambda name, status, message, detail=None: _check(name, status, message, detail),
    load_simple=lambda sheet, cols: _load_simple(sheet, cols),
    build_data_quality_report=lambda reader, max_age_days=None: build_data_quality_report(
        reader,
        max_age_days=max_age_days,
    ),
    repository=lambda: _repository(),
    data_source_payload=lambda: _data_source_payload(),
    check_kr_rank_health=_check_kr_rank_health,
    settings=_SETTINGS,
    logs_dir=_ROOT / "logs",
    kr_rank_health_payload_loader=lambda max_age_days: _kr_rank_health_payload(max_age_days=max_age_days),
)
_factor_quality_check = _ops_check_builder.factor_quality_check
_data_quality_payload = _ops_check_builder.data_quality_payload
_data_quality_check = _ops_check_builder.data_quality_check
_kr_rank_health_payload = _ops_check_builder.kr_rank_health_payload
_kr_rank_health_check = _ops_check_builder.kr_rank_health_check
_data_source_check = _ops_check_builder.data_source_check


_ops_health_builder = OpsHealthPayloadBuilder(
    settings=_SETTINGS,
    check=lambda name, status, message, detail=None: _check(name, status, message, detail),
    utc_now=lambda: _utc_now(),
    ready_payload=lambda: _ready_payload(),
    storage_config_checks_loader=lambda: _storage_config_checks(),
    macro_check=lambda: _macro_check(),
    core_dataset_checks=lambda: _core_dataset_checks(),
    data_source_check=lambda: _data_source_check(),
    data_quality_check=lambda: _data_quality_check(),
    kr_rank_health_check=lambda: _kr_rank_health_check(),
    factor_quality_check=lambda: _factor_quality_check(),
    research_health_payload=lambda **kwargs: _research_health_payload(**kwargs),
    cache_warm_state_payload=lambda: _cache_warm_state(),
)
_storage_config_checks = _ops_health_builder.storage_config_checks
_ops_health_payload = _ops_health_builder.ops_health_payload


# ── Stock detail (price history + company info) ───────────────────────────────

def _identity_from_storage(ticker: str, market: str) -> dict:
    return StockIdentityLookup(load_storage_df=_load_storage_df).identity_from_storage(ticker, market)


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


_training_quiz_api_service = TrainingQuizApiService(
    cached=_cached,
    stock_detail=lambda ticker: _stock_detail_service.detail(ticker=ticker, period="3y", profile=False),
    logo_url=lambda ticker, market: _default_logo_url(ticker, market),
    now_iso=_utc_now_iso,
)


_sector_detail_reconciler = SectorDetailReconciler(
    stock_detail_service=lambda: _stock_detail_service,
    first_float=lambda *values: _first_float(*values),
)
_sector_detail_reconcile_members = _sector_detail_reconciler.reconcile_members


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
    load_table=lambda sheet, cols=None: _load_table(sheet, cols),
    load_portfolio=lambda sheet: _load_portfolio(sheet),
    load_simple=lambda sheet, cols: _load_simple(sheet, cols),
    coerce=lambda frame, cols: _coerce(frame, cols),
    df_to_records=lambda frame: _df_to_records(frame),
    etf_storage_records=lambda: _etf_storage_records(),
    etf_payload_from_records=lambda records, **kwargs: _etf_payload_from_records(records, **kwargs),
    enrich_kr_company_identities=lambda rows: _enrich_kr_company_identities(rows),
    default_logo_url=lambda ticker, market: _default_logo_url(ticker, market),
    kr_code=lambda value: _kr_code(value),
    utc_now_iso=lambda: _utc_now_iso(),
    record_data_source=lambda dataset, source, **kwargs: _record_data_source(dataset, source, **kwargs),
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
_ranking_api_service = RankingApiService(
    cached=_cached,
    smallcap_payload=lambda market: _smallcap_payload(market),
    scored_payload=lambda market, limit: _scored_payload(market, limit),
)
_system_api_service = SystemApiService(
    health_payload=lambda: _health_payload(),
    ready_payload=lambda: _ready_payload(),
)
_research_api_service = ResearchApiService(
    cached=_cached,
    factor_quality_payload=lambda: _research_factor_quality_payload(),
    factor_policy_payload=lambda: _research_factor_policy_payload(),
    ml_blend_payload=lambda: _research_ml_blend_payload(),
    policy_backtest_payload=lambda: _research_policy_backtest_payload(),
    policy_adjusted_ranking_payload=lambda market, limit: _research_policy_adjusted_ranking_payload(market, limit),
    remediation_plan_payload=lambda: _research_remediation_plan_payload(),
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

def _cache_warm_state() -> dict:
    return _api_cache_warmer.state()


def _start_cache_warm(reason: str = "manual") -> dict:
    return _api_cache_warmer.start(reason)


_api_cache_warm_jobs = ApiCacheWarmJobFactory(
    portfolio_service=_portfolio_api_service,
    ranking_service=_ranking_api_service,
    calendar_service=_calendar_api_service,
    risk_service=_risk_api_service,
    market_service=_market_api_service,
    news_service=_news_api_service,
    etf_service=_etf_api_service,
    sector_service=_sector_api_service,
    sector_theme_order=_SECTOR_THEME_ORDER,
)
_api_cache_warmer = ApiCacheWarmer(
    primary_jobs=_api_cache_warm_jobs.primary_jobs,
    startup_jobs=_api_cache_warm_jobs.startup_jobs,
    stock_tickers=_api_cache_warm_jobs.stock_tickers,
    etf_tickers=_api_cache_warm_jobs.etf_tickers,
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
health = _system_api_service.health
ready = _system_api_service.ready
search_universe = _search_api_service.search_universe
calendar_earnings = _calendar_api_service.calendar_earnings
earnings_calendar = _calendar_api_service.earnings_calendar
earnings = _calendar_api_service.earnings
macro = _calendar_api_service.macro
smallcap = _ranking_api_service.smallcap
scored = _ranking_api_service.scored
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
research_factor_quality = _research_api_service.factor_quality
research_factor_policy = _research_api_service.factor_policy
research_ml_blend = _research_api_service.ml_blend
research_policy_backtest = _research_api_service.policy_backtest
research_policy_adjusted_ranking = _research_api_service.policy_adjusted_ranking
research_remediation_plan = _research_api_service.remediation_plan
ops_pipeline_runs = _ops_api_service.pipeline_runs
ops_research_health = _ops_api_service.research_health
ops_data_quality = _ops_api_service.data_quality
ops_data_sources = _ops_api_service.data_sources
ops_performance = _ops_api_service.performance
ops_health = _ops_api_service.health
cache_clear = _ops_api_service.cache_clear
blind_financial_quiz = _training_quiz_api_service.blind_financial_quiz

app.include_router(create_portfolio_router(_portfolio_api_service))
app.include_router(create_sector_router(_sector_api_service))
app.include_router(create_etf_router(_etf_api_service))
app.include_router(create_news_router(_news_api_service))
app.include_router(create_market_router(_market_api_service))
app.include_router(create_search_router(_search_api_service))
app.include_router(create_calendar_router(_calendar_api_service))
app.include_router(create_ranking_router(_ranking_api_service))
app.include_router(create_risk_router(_risk_api_service))
app.include_router(create_research_router(_research_api_service))
app.include_router(create_ops_router(_ops_api_service))
app.include_router(create_stock_router(_stock_detail_service))
app.include_router(create_system_router(_system_api_service))
app.include_router(create_training_router(_training_quiz_api_service))


def _warm_mobile_cache_on_startup() -> None:
    enabled = str(os.environ.get("QUANT_API_AUTO_WARM", "1")).strip().lower()
    if enabled not in {"0", "false", "no", "off"}:
        _start_cache_warm("startup")
