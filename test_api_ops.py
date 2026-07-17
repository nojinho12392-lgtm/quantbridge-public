"""Behavior tests for lightweight API operations telemetry."""

from __future__ import annotations

from pathlib import Path
import tempfile
import time as _time
import unittest

import pandas as pd

from api import runtime_state
from api.services.cache_warm import ApiCacheWarmJobFactory, ApiCacheWarmer
from api.services.ops_api import OpsCheckBuilder
import api.server as server
from quantbridge.config import Settings


class DataSourceTelemetryTests(unittest.TestCase):
    def setUp(self):
        runtime_state.clear_runtime_state()

    def tearDown(self):
        runtime_state.clear_runtime_state()

    def test_data_source_payload_summarizes_latest_dataset_reads(self):
        runtime_state.record_data_source("US_Final_Portfolio", "storage", market="US", rows=30)
        runtime_state.record_data_source("KR_Final_Portfolio", "sheet", market="KR", rows=30)

        payload = runtime_state.data_source_payload()

        self.assertEqual(payload["count"], 2)
        self.assertEqual(payload["summary"]["storage"], 1)
        self.assertEqual(payload["summary"]["sheet"], 1)
        self.assertEqual(payload["items"][0]["dataset"], "KR_Final_Portfolio")
        self.assertIn("last_seen_at", payload["items"][0])

    def test_data_source_check_warns_on_fallback(self):
        runtime_state.record_data_source("US_Final_Portfolio", "sheet", market="US", rows=30)

        check = server._data_source_check()

        self.assertEqual(check["status"], "WARN")
        self.assertEqual(check["name"], "Data source fallback")

    def test_sheet_loaders_record_sheet_error_without_raising(self):
        original_spreadsheet = server._spreadsheet
        original_storage = server._load_storage_df
        server._spreadsheet = lambda: (_ for _ in ()).throw(IsADirectoryError("key.json"))
        server._load_storage_df = lambda sheet_name, market=None: pd.DataFrame()
        try:
            self.assertEqual(server._load_simple("US_SmallCap_Gems", ["Rank"]), [])
            meta, rows = server._load_portfolio("US_Final_Portfolio")
            macro = server._macro_payload()
        finally:
            server._spreadsheet = original_spreadsheet
            server._load_storage_df = original_storage

        self.assertEqual(meta, {})
        self.assertEqual(rows, [])
        self.assertEqual(macro, {})
        payload = runtime_state.data_source_payload()
        sources = {
            (item["dataset"], item["market"]): item["last_source"]
            for item in payload["items"]
        }
        self.assertEqual(sources[("US_SmallCap_Gems", "US")], "sheet_error")
        self.assertEqual(sources[("US_Final_Portfolio", "US")], "sheet_error")
        self.assertEqual(sources[("Macro_Regime", "GLOBAL")], "sheet_error")

    def test_row_market_storage_datasets_use_unpartitioned_read(self):
        self.assertIsNone(server._infer_storage_market("Signal_Quality_Gates"))
        self.assertIsNone(server._infer_storage_market("Factor_Weight_Policy"))
        self.assertEqual(server._infer_storage_market("Macro_Regime"), "GLOBAL")
        self.assertEqual(server._infer_storage_market("US_Final_Portfolio"), "US")
        self.assertEqual(server._infer_storage_market("KR_Final_Portfolio"), "KR")

    def test_signal_quality_proxy_gates_do_not_degrade_ops_health(self):
        original_load_simple = server._load_simple
        server._load_simple = lambda *_args, **_kwargs: [
            {"Status": "FAIL", "Evidence_Source": "PROXY_ONLY", "Production_Ready": "FALSE"},
            {"Status": "WATCH", "Evidence_Source": "PROXY_ONLY", "Production_Ready": "FALSE"},
        ]
        try:
            check = server._factor_quality_check()
        finally:
            server._load_simple = original_load_simple

        self.assertEqual(check["status"], "OK")
        self.assertEqual(check["detail"]["production_ready_rows"], 0)

    def test_signal_quality_production_fail_warns_ops_health(self):
        original_load_simple = server._load_simple
        server._load_simple = lambda *_args, **_kwargs: [
            {"Status": "FAIL", "Evidence_Source": "LIVE_ONLY", "Production_Ready": "TRUE"},
        ]
        try:
            check = server._factor_quality_check()
        finally:
            server._load_simple = original_load_simple

        self.assertEqual(check["status"], "WARN")

    def test_data_quality_check_names_problem_datasets(self):
        def check(name, status, message, detail=None):
            return {"name": name, "status": status, "message": message, "detail": detail or {}}

        def build_report(_reader, max_age_days=None):
            return {
                "status": "FAIL",
                "status_counts": {"OK": 1, "WARN": 1, "FAIL": 1},
                "datasets": [
                    {"dataset": "US_Universe", "market": "US", "status": "OK", "rows": 80},
                    {"dataset": "KR_Universe", "market": "KR", "status": "WARN", "rows": 60},
                    {"dataset": "KR_Scored_Stocks", "market": "KR", "status": "FAIL", "rows": 0},
                ],
            }

        builder = OpsCheckBuilder(
            check=check,
            load_simple=lambda *_args, **_kwargs: [],
            build_data_quality_report=build_report,
            repository=lambda: object(),
            data_source_payload=lambda: {},
            check_kr_rank_health=lambda *_args, **_kwargs: {},
            settings=object(),
            logs_dir=Path("."),
        )

        result = builder.data_quality_check()

        self.assertEqual("FAIL", result["status"])
        self.assertIn("issues=KR_Universe:WARN, KR_Scored_Stocks:FAIL", result["message"])

    def test_local_storage_mode_does_not_warn_on_optional_services(self):
        original_settings = server._SETTINGS
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            settings = Settings(
                _env_file=None,
                google_key_path=root / "missing-google-key.json",
                data_lake_dir=root / "data_lake",
                enable_parquet=True,
                enable_postgres=False,
                api_env="local",
            )
            settings.data_lake_dir.mkdir(parents=True, exist_ok=True)
            server._SETTINGS = settings
            try:
                checks = server._storage_config_checks()
            finally:
                server._SETTINGS = original_settings

        by_name = {check["name"]: check for check in checks}
        self.assertEqual(by_name["Google key"]["status"], "OK")
        self.assertEqual(by_name["PostgreSQL mode"]["status"], "OK")
        self.assertTrue(by_name["Google key"]["detail"]["local_parquet_available"])
        self.assertTrue(by_name["PostgreSQL mode"]["detail"]["local_storage_mode"])

    def test_policy_adjusted_ranking_endpoint_summarizes_movers(self):
        original_load_simple = server._load_simple

        def fake_load_simple(sheet_name, _num_cols=None):
            if sheet_name == "US_Policy_Adjusted_Ranking":
                return [
                    {"Ticker": "AAA", "Policy_Rank": 1, "Base_Rank": 3, "Rank_Change": 2, "Policy_Mode": "proxy_observation"},
                    {"Ticker": "BBB", "Policy_Rank": 2, "Base_Rank": 1, "Rank_Change": -1, "Policy_Mode": "proxy_observation"},
                ]
            if sheet_name == "Policy_Adjusted_Ranking_Summary":
                return [{"Market": "US", "Rows": 2, "Top_Up_Ticker": "AAA", "Top_Down_Ticker": "BBB"}]
            return []

        server._load_simple = fake_load_simple
        try:
            payload = server.research_policy_adjusted_ranking(market="US", limit=1)
        finally:
            server._load_simple = original_load_simple

        self.assertEqual(payload["market"], "US")
        self.assertEqual(len(payload["items"]), 1)
        self.assertEqual(payload["top_up"][0]["Ticker"], "AAA")
        self.assertEqual(payload["top_down"][0]["Ticker"], "BBB")
        self.assertEqual(payload["summary"]["Top_Up_Ticker"], "AAA")

    def test_policy_adjusted_summary_falls_back_to_unpartitioned_storage_read(self):
        original_load_simple = server._load_simple
        original_load_storage = server._load_storage_df

        def fake_load_simple(sheet_name, _num_cols=None):
            if sheet_name == "US_Policy_Adjusted_Ranking":
                return [{"Ticker": "AAA", "Policy_Rank": 1, "Base_Rank": 3, "Rank_Change": 2}]
            return []

        def fake_load_storage(sheet_name, market=None):
            if sheet_name == "Policy_Adjusted_Ranking_Summary" and market is None:
                return pd.DataFrame([
                    {"Market": "US", "Rows": 1, "Top_Up_Ticker": "AAA", "Top_Down_Ticker": "BBB"},
                ])
            return pd.DataFrame()

        server._load_simple = fake_load_simple
        server._load_storage_df = fake_load_storage
        try:
            payload = server.research_policy_adjusted_ranking(market="US", limit=1)
        finally:
            server._load_simple = original_load_simple
            server._load_storage_df = original_load_storage

        self.assertEqual(payload["summary"]["Rows"], 1)
        self.assertEqual(payload["summary"]["Top_Up_Ticker"], "AAA")

    def test_kr_rank_health_check_maps_payload_to_ops_check(self):
        payload = {
            "healthy": True,
            "status": "OK",
            "rows": 30,
            "snapshot_date": "2026-05-25",
            "checks": [],
        }

        original = server._kr_rank_health_payload
        server._kr_rank_health_payload = lambda max_age_days=2: payload
        try:
            check = server._kr_rank_health_check()
        finally:
            server._kr_rank_health_payload = original

        self.assertEqual(check["name"], "KR rank refresh")
        self.assertEqual(check["status"], "OK")
        self.assertIn("rows=30", check["message"])

    def test_ops_health_includes_kr_rank_refresh_check(self):
        def ok_check(name):
            return {"name": name, "status": "OK", "message": "ok", "detail": {}}

        patches = {
            "_ready_payload": lambda: {"status": "ready"},
            "_storage_config_checks": lambda: [ok_check("storage")],
            "_macro_check": lambda: ok_check("macro"),
            "_core_dataset_checks": lambda: [ok_check("core")],
            "_data_source_check": lambda: ok_check("data source"),
            "_data_quality_check": lambda: ok_check("data quality"),
            "_kr_rank_health_check": lambda: ok_check("KR rank refresh"),
            "_factor_quality_check": lambda: ok_check("factor quality"),
            "_research_health_payload": lambda max_age_hours=84: {"healthy": True, "reason": "ok"},
            "_cache_warm_state": lambda: {
                "running": False,
                "profile": "startup",
                "results": [
                    {"name": "portfolio/us", "status": "OK"},
                    {"name": "portfolio/kr", "status": "OK"},
                    {"name": "smallcap/us", "status": "OK"},
                    {"name": "smallcap/kr", "status": "OK"},
                    {"name": "macro", "status": "OK"},
                ],
            },
        }
        originals = {name: getattr(server, name) for name in patches}
        for name, replacement in patches.items():
            setattr(server, name, replacement)
        try:
            payload = server._ops_health_payload()
        finally:
            for name, original in originals.items():
                setattr(server, name, original)

        self.assertTrue(payload["healthy"])
        self.assertIn("KR rank refresh", [check["name"] for check in payload["checks"]])
        self.assertIn("Cache warm", [check["name"] for check in payload["checks"]])

    def test_ops_health_warns_when_cache_warm_is_missing_mobile_job(self):
        builder = server._ops_health_builder
        original = server._cache_warm_state
        server._cache_warm_state = lambda: {
            "running": False,
            "profile": "startup",
            "results": [
                {"name": "portfolio/us", "status": "OK"},
                {"name": "portfolio/kr", "status": "OK"},
                {"name": "smallcap/us", "status": "OK"},
                {"name": "macro", "status": "OK"},
            ],
        }
        try:
            check = builder.cache_warm_check()
        finally:
            server._cache_warm_state = original

        self.assertEqual("Cache warm", check["name"])
        self.assertEqual("WARN", check["status"])
        self.assertEqual(["smallcap/kr"], check["detail"]["missing_mobile"])

    def test_startup_cache_warm_uses_mobile_critical_jobs(self):
        calls: list[str] = []
        factory = ApiCacheWarmJobFactory(
            portfolio_service=_FakePortfolio(calls),
            ranking_service=_FakeRanking(calls),
            calendar_service=_FakeCalendar(calls),
            risk_service=_FakeRisk(calls),
            market_service=_FakeMarket(calls),
            news_service=_FakeNews(calls),
            etf_service=_FakeEtf(calls),
            sector_service=_FakeSector(calls),
            sector_theme_order=[],
        )

        names = [name for name, _job in factory.startup_jobs()]
        for _name, job in factory.startup_jobs():
            job()

        self.assertEqual(
            [
                "portfolio/us",
                "portfolio/kr",
                "smallcap/us",
                "smallcap/kr",
                "macro",
                "earnings/us",
                "earnings/kr",
                "calendar/earnings",
                "signals/events",
                "market/indices",
                "market/indicators",
                "news/issues",
            ],
            names,
        )
        self.assertIn("macro", calls)
        self.assertNotIn("etfs", calls)
        self.assertNotIn("sectors/themes", calls)

    def test_startup_cache_warmer_skips_detail_jobs(self):
        detail_calls: list[str] = []
        warmer = ApiCacheWarmer(
            primary_jobs=lambda: [("full", lambda: {"items": [1]})],
            startup_jobs=lambda: [("startup", lambda: {"items": [1]})],
            stock_tickers=lambda: ["AAPL"],
            etf_tickers=lambda: ["SPY"],
            stock_detail=lambda ticker: detail_calls.append(f"stock:{ticker}"),
            etf_detail=lambda ticker: detail_calls.append(f"etf:{ticker}"),
        )

        state = warmer.start("startup")
        deadline = _time.time() + 2
        while warmer.state()["running"] and _time.time() < deadline:
            _time.sleep(0.01)
        final = warmer.state()

        self.assertEqual("startup", state["profile"])
        self.assertEqual("startup", final["profile"])
        self.assertEqual(["startup"], [item["name"] for item in final["results"]])
        self.assertEqual([], detail_calls)


class _FakePortfolio:
    def __init__(self, calls: list[str]) -> None:
        self.calls = calls

    def portfolio(self, market: str) -> dict:
        self.calls.append(f"portfolio/{market}")
        return {"stocks": []}


class _FakeRanking:
    def __init__(self, calls: list[str]) -> None:
        self.calls = calls

    def smallcap(self, market: str) -> dict:
        self.calls.append(f"smallcap/{market}")
        return {"stocks": []}


class _FakeCalendar:
    def __init__(self, calls: list[str]) -> None:
        self.calls = calls

    def macro(self) -> dict:
        self.calls.append("macro")
        return {}

    def earnings(self, market: str) -> dict:
        self.calls.append(f"earnings/{market}")
        return {"stocks": []}

    def calendar_earnings(self, **_kwargs) -> dict:
        self.calls.append("calendar/earnings")
        return {"items": []}


class _FakeRisk:
    def __init__(self, calls: list[str]) -> None:
        self.calls = calls

    def signal_events(self, **_kwargs) -> dict:
        self.calls.append("signals/events")
        return {"items": []}


class _FakeMarket:
    def __init__(self, calls: list[str]) -> None:
        self.calls = calls

    def market_indices(self) -> dict:
        self.calls.append("market/indices")
        return {"indices": []}

    def market_indicators(self, **_kwargs) -> dict:
        self.calls.append("market/indicators")
        return {"items": []}


class _FakeNews:
    def __init__(self, calls: list[str]) -> None:
        self.calls = calls

    def news_issues(self, **_kwargs) -> dict:
        self.calls.append("news/issues")
        return {"items": []}


class _FakeEtf:
    def __init__(self, calls: list[str]) -> None:
        self.calls = calls

    def etfs(self, **_kwargs) -> dict:
        self.calls.append("etfs")
        return {"items": []}


class _FakeSector:
    def __init__(self, calls: list[str]) -> None:
        self.calls = calls

    def sector_theme_summary(self, **_kwargs) -> dict:
        self.calls.append("sectors/themes/summary")
        return {"items": []}

    def sector_themes(self, **_kwargs) -> dict:
        self.calls.append("sectors/themes")
        return {"items": []}

    def sector_theme_detail(self, **_kwargs) -> dict:
        self.calls.append("sectors/themes/detail")
        return {"item": {}}


if __name__ == "__main__":
    unittest.main()
