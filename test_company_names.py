from __future__ import annotations

import api.services.company_names as company_names_module
from api.services.company_names import (
    KR_COMPANY_NAMES,
    US_COMPANY_NAMES_KO,
    apply_localized_names,
    contains_hangul,
    enrich_kr_company_identities,
    localize_company_name_fields,
    localized_company_name,
)
from api.services.portfolio_api import PortfolioApiService
from api.services.search_api import SearchApiService


def test_company_name_maps_cover_merged_mobile_overrides():
    assert KR_COMPANY_NAMES["005930"] == "삼성전자"
    assert KR_COMPANY_NAMES["034020"] == "두산에너빌리티"
    assert US_COMPANY_NAMES_KO["AAPL"] == "애플"
    assert US_COMPANY_NAMES_KO["CPRT"] == "코파트"


def test_localized_company_name_prefers_existing_hangul_and_handles_generic_names():
    assert contains_hangul("삼성전자")
    assert localized_company_name("AAPL", "Apple Inc.", "US") == "애플"
    assert localized_company_name("005930.KS", "005930", "KR") == "삼성전자"
    assert localized_company_name("BOTZ", "BOTZ 기업", "US") == "BOTZ"
    assert localized_company_name("AAPL", "애플", "US") == "애플"


def test_localize_company_name_fields_preserves_non_company_instruments():
    assert localize_company_name_fields({"Ticker": "AAPL", "Name": "Apple Inc.", "Market": "US"})["Name"] == "애플"
    assert localize_company_name_fields({"Ticker": "XLY", "Name": "Consumer Discretionary Select Sector SPDR ETF", "Market": "US"})["Name"] == "Consumer Discretionary Select Sector SPDR ETF"


def test_apply_localized_names_recurses_through_api_payloads():
    payload = {
        "stocks": [
            {"Ticker": "AAPL", "Name": "Apple Inc.", "Market": "US"},
            {"ticker": "005930.KS", "name": "005930", "market": "KR"},
        ],
        "meta": {"Name": "not a stock"},
    }

    localized = apply_localized_names(payload)

    assert localized["stocks"][0]["Name"] == "애플"
    assert localized["stocks"][1]["name"] == "삼성전자"
    assert localized["meta"]["Name"] == "not a stock"


def test_enrich_kr_company_identities_normalizes_missing_kr_names(monkeypatch):
    monkeypatch.setattr(
        company_names_module,
        "_naver_kr_identity",
        lambda _code: {"Ticker": "005930.KS", "Name": "삼성전자", "Exchange": "KS"},
    )

    enriched = enrich_kr_company_identities([{"Ticker": "005930", "Name": "005930"}])

    assert enriched == [{"Ticker": "005930.KS", "Name": "삼성전자"}]


def test_portfolio_service_applies_localized_names_to_response():
    service = PortfolioApiService(
        cached=lambda _key, load, ttl=None: load(),
        invalidate=lambda _key: None,
        load_portfolio=lambda _sheet: ({}, [{"Ticker": "AAPL", "Name": "Apple Inc.", "Market": "US"}]),
        price_snapshot_batch=lambda _tickers, _market: {},
        price_metrics_batch=lambda _tickers, _market: {},
        daily_change_batch=lambda tickers, _market, _snapshots: {ticker: (None, "") for ticker in tickers},
        naver_kr_quote_batch=lambda _tickers: {},
        utc_now_iso=lambda: "2026-05-20T00:00:00+00:00",
    )

    assert service.portfolio("US")["stocks"][0]["Name"] == "애플"


def test_search_service_applies_localized_names_to_stocks_and_groups():
    service = SearchApiService(
        cached=lambda _key, load, ttl=None: load(),
        payload=lambda _query, _limit: {
            "stocks": [{"Ticker": "005930.KS", "Name": "005930", "Market": "KR"}],
            "groups": [{"label": "기업", "items": [{"Ticker": "AAPL", "Name": "Apple Inc.", "Market": "US"}]}],
        },
    )

    payload = service.search_universe(q="a", limit=10)

    assert payload["stocks"][0]["Name"] == "삼성전자"
    assert payload["groups"][0]["items"][0]["Name"] == "애플"
