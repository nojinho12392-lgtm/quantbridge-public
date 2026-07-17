"""
Contract tests for QuantBridge schemas and wiring.

These tests intentionally avoid importing pipeline modules because most pipeline
files connect to Google Sheets at import time. They parse source files directly
so the checks stay fast and offline.

Run:
    python -m unittest test_contracts.py
"""

import ast
from pathlib import Path
import sys
import tempfile
import unittest

import pandas as pd

from api.contracts.mobile_v1 import ENDPOINTS, FRESHNESS_FIELDS, MOBILE_API_VERSION


ROOT = Path(__file__).resolve().parent


def read(rel_path: str) -> str:
    return (ROOT / rel_path).read_text(encoding="utf-8")


def assigned_list(rel_path: str, name: str) -> list[str]:
    tree = ast.parse(read(rel_path), filename=rel_path)
    for node in tree.body:
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id == name:
                    return ast.literal_eval(node.value)
    raise AssertionError(f"{name} not found in {rel_path}")


class SchemaContractTests(unittest.TestCase):
    def test_ml_scored_schema_preserves_sector_neutral_scores(self):
        cols = assigned_list("pipeline/04_ml_model.py", "SCORED_COLS_ML")

        self.assertEqual(len(cols), 36)
        self.assertIn("Final_Score", cols)
        self.assertIn("Score_Neutral", cols)
        self.assertIn("Business_Quality_Score", cols)
        self.assertIn("Investability_Score", cols)
        self.assertLess(cols.index("Total_Score"), cols.index("Final_Score"))
        self.assertLess(cols.index("Score_Neutral"), cols.index("ML_Score"))
        self.assertLess(cols.index("ML_Score"), cols.index("Combined_Score"))
        self.assertLess(cols.index("Combined_Score"), cols.index("Business_Quality_Score"))

    def test_us_and_kr_base_scored_schemas_match(self):
        us_cols = assigned_list("pipeline/03a_factor_scorer_us.py", "SCORED_COLS")
        kr_cols = assigned_list("pipeline/03b_factor_scorer_kr.py", "SCORED_COLS")

        self.assertEqual(us_cols, kr_cols)
        self.assertEqual(len(us_cols), 34)
        self.assertEqual(us_cols[10:12], ["Final_Score", "Score_Neutral"])
        self.assertIn("Business_Quality_Score", us_cols)
        self.assertIn("Investability_Score", us_cols)
        self.assertIn("Quality_Category", us_cols)

    def test_kr_order_flow_sheet_name_is_canonical(self):
        cols = assigned_list("pipeline/11_order_flow_kr.py", "OUTPUT_COLS")
        source = read("pipeline/11_order_flow_kr.py")

        self.assertIn("Consecutive_Days", cols)
        self.assertIn("SHEET_OUTPUT   = 'KR_Dual_Net_Buyers'", source)
        self.assertNotIn("SHEET_OUTPUT   = 'KR_Order_Flow'", source)

    def test_macro_regime_pipeline_is_import_safe(self):
        source = read("pipeline/02_macro_regime.py")

        self.assertIn("def compute_macro_regime", source)
        self.assertIn("def write_macro_regime", source)
        self.assertIn("def run(", source)
        self.assertIn('if __name__ == "__main__":', source)
        self.assertNotIn("spreadsheet = get_spreadsheet()", source)


class WiringContractTests(unittest.TestCase):
    def test_fastapi_uses_shared_sheets_client(self):
        source = read("api/server.py")
        auth = read("api/auth.py")
        auth_store = read("api/auth_store.py")
        calendar_service = read("api/services/calendar_api.py")
        news_sources = read("api/services/news_sources.py")
        ops_service = read("api/services/ops_api.py")

        self.assertIn("from sheets_client import get_spreadsheet", source)
        self.assertIn("from api.auth import router as auth_router", source)
        self.assertIn("app.include_router(auth_router)", source)
        self.assertIn("from quantbridge.quality import build_data_quality_report", source)
        self.assertIn("def _spreadsheet()", source)
        self.assertIn("return get_spreadsheet(_SPREADSHEET)", source)
        self.assertIn("enrich_kr_company_identities as _enrich_kr_company_identities", source)
        self.assertIn('if safe_market == "KR":', calendar_service)
        self.assertIn("QUANT_CORS_ORIGINS", read("quantbridge/config.py"))
        self.assertIn("SettingsConfigDict", read("quantbridge/config.py"))
        self.assertIn("allow_cors_credentials", read("quantbridge/config.py"))
        self.assertIn("QUANT_AUTH_RATE_LIMIT_PER_MINUTE", read("quantbridge/config.py"))
        self.assertIn("def _check_auth_rate_limit", auth)
        self.assertIn("DuplicateUserError", auth)
        self.assertIn("SQLiteAuthStore", auth)
        self.assertIn("PostgresAuthStore", auth)
        self.assertIn("class SQLiteAuthStore", auth_store)
        self.assertIn("def create_user", auth_store)
        self.assertIn("def upsert_watchlist_item", auth_store)
        self.assertIn('@router.post("/auth/signup", response_model=AuthResponse)', auth)
        self.assertIn('@router.get("/me/watchlist", response_model=WatchlistResponse)', auth)
        self.assertNotIn("oauth2client", source)
        self.assertNotIn("ServiceAccountCredentials", source)
        self.assertIn('"Score_Neutral"', read("api/services/ranking_api.py"))
        self.assertIn("create_search_router", source)
        self.assertIn("def _search_universe_payload", read("api/services/search_api.py"))
        self.assertIn("create_risk_router", source)
        self.assertIn("create_news_router", source)
        self.assertIn("_news_payload =", source)
        self.assertIn("create_calendar_router", source)
        self.assertIn("create_ranking_router", source)
        self.assertIn("create_system_router", source)
        self.assertIn("create_ops_router", source)
        self.assertIn("def data_source_check", ops_service)
        self.assertIn("X-Naver-Client-Id", source + news_sources)
        self.assertIn("NAVER_CLIENT_ID", read("quantbridge/config.py"))

    def test_ios_matches_android_explore_and_news_features(self):
        content = read("Stock Analysis/Stock Analysis/ContentView.swift")
        home = "\n".join(
            [
                read("Stock Analysis/Stock Analysis/HomeDashboardView.swift"),
                read("Stock Analysis/Stock Analysis/Home/HomeNewsCards.swift"),
            ]
        )
        portfolio = read("Stock Analysis/Stock Analysis/PortfolioView.swift")
        explore = read("Stock Analysis/Stock Analysis/ExploreFeature.swift")
        news = read("Stock Analysis/Stock Analysis/NewsFeature.swift")
        android = "\n".join(
            [
                read("android/app/src/main/java/com/qubit/quantbridge/MainActivity.kt"),
                read("android/app/src/main/java/com/qubit/quantbridge/AppScreens.kt"),
                read("android/app/src/main/java/com/qubit/quantbridge/screens/PortfolioAnalysisScreen.kt"),
                read("android/app/src/main/java/com/qubit/quantbridge/screens/InsightPulseScreen.kt"),
                read("android/app/src/main/java/com/qubit/quantbridge/screens/AccountScreenUi.kt"),
                read("android/app/src/main/java/com/qubit/quantbridge/screens/AnalysisListComponents.kt"),
                read("android/app/src/main/java/com/qubit/quantbridge/screens/StockDetailScreenUi.kt"),
                read("android/app/src/main/java/com/qubit/quantbridge/screens/AppSharedUiComponents.kt"),
                read("android/app/src/main/java/com/qubit/quantbridge/screens/AppScreenHelpers.kt"),
                read("android/app/src/main/java/com/qubit/quantbridge/screens/NewsScreen.kt"),
                read("android/app/src/main/java/com/qubit/quantbridge/screens/SmallCapScreen.kt"),
                read("android/app/src/main/java/com/qubit/quantbridge/screens/ComparisonSheet.kt"),
            ]
        )
        android_models = read("android/app/src/main/java/com/qubit/quantbridge/Models.kt")

        self.assertIn("ExploreView(showsAdvancedModes: false)", content)
        self.assertNotIn("NewsTabView()", content)
        self.assertNotIn("SmallCapTabView()", content)
        self.assertIn("NewsVM()", home)
        self.assertIn("HomeNewsCard", home)
        self.assertIn("PortfolioMode", portfolio)
        self.assertIn('case smallCap = "스몰캡"', portfolio)
        self.assertIn("SmallCapRankingRow(", portfolio)
        self.assertIn("searchUniverse", explore)
        self.assertIn('["search", "universe"]', explore)
        self.assertIn("fetchScored", explore)
        self.assertIn("fetchResearchQuality", explore)
        self.assertIn("fetchOpsHealth", explore)
        self.assertIn("fetchBacktest", explore)
        self.assertIn("fetchDriftItems", explore)
        self.assertIn("fetchIndustryItems", explore)
        self.assertIn("fetchOrderFlowItems", explore)
        self.assertIn('case strategy = "전략"', explore)
        self.assertIn('case diagnostics = "품질"', explore)
        self.assertIn("fetchNews", news)
        self.assertIn('["news", "issues"]', news)
        self.assertIn("NewsItem", news)
        self.assertIn("it == AppTab.News", android)
        self.assertIn("it == AppTab.SmallCap", android)
        self.assertIn("NewsScreen(app)", android)
        self.assertIn("SmallCapScreen(app)", android)
        self.assertIn("CompactNewsCard", android)
        self.assertIn('listOf("일반", "스몰캡")', android)
        self.assertIn("data class PortfolioStock", android_models)
        self.assertIn("enum class AppTab", android_models)
        self.assertNotIn("data class PortfolioStock", android)

    def test_mobile_logo_fallbacks_cover_current_problem_cases(self):
        formatting = read("Stock Analysis/Stock Analysis/Formatting.swift")
        shared = read("Stock Analysis/Stock Analysis/SharedViews.swift")
        android = read("android/app/src/main/java/com/qubit/quantbridge/Formatting.kt")
        api = read("api/services/stock_identity.py")

        for source in (formatting, android, api):
            self.assertIn("static.toss.im/png-icons/securities/icn-sec-fill-", source)
            self.assertIn("financialmodelingprep.com/image-stock", source)
            self.assertIn("064400", source)
            self.assertIn("lgcns.com", source)

        self.assertIn("func logoURLs", formatting)
        self.assertIn("usLogoSymbols", formatting)
        self.assertIn("nextAvailableIndex", shared)
        self.assertIn("logoIndex = CompanyLogoMemoryCache.nextAvailableIndex", shared)
        self.assertIn('symbol.replace(".", "-")', android)

    def test_upgrade_phase_one_foundation_exists(self):
        for rel in [
            "quantbridge/config.py",
            "quantbridge/schemas.py",
            "quantbridge/storage/postgres.py",
            "quantbridge/storage/parquet.py",
            "quantbridge/storage/repository.py",
            "quantbridge/orchestration/dag.py",
            "main_dag.py",
            ".env.example",
            "docker-compose.yml",
            "api/Dockerfile",
            ".dockerignore",
        ]:
            self.assertTrue((ROOT / rel).exists(), rel)

        config = read("quantbridge/config.py")
        self.assertIn("QUANT_DATABASE_URL", config)
        self.assertIn("QUANT_DATA_LAKE_DIR", config)
        self.assertIn("QUANT_GOOGLE_KEY_PATH", config)

        schemas = read("quantbridge/schemas.py")
        self.assertIn("UNIVERSE_COLS", schemas)
        self.assertIn("SCORED_COLS_ML", schemas)
        self.assertIn("PORTFOLIO_COLS", schemas)
        self.assertIn("SMALLCAP_COLS", schemas)

        repository = read("quantbridge/storage/repository.py")
        self.assertIn("def read_dataframe", repository)
        self.assertIn("read_latest", repository)

        postgres = read("quantbridge/storage/postgres.py")
        self.assertIn("latest_portfolios", postgres)
        self.assertIn("latest_smallcaps", postgres)
        self.assertIn("latest_scored_stocks", postgres)
        self.assertIn("company_identity", postgres)
        self.assertIn("price_ohlcv", postgres)
        self.assertIn("schema_migrations", postgres)
        self.assertIn("idx_price_ohlcv_ticker_date", postgres)
        self.assertIn("idx_quant_records_latest", postgres)
        self.assertIn("idx_latest_portfolios_rank", postgres)

        schema_source = read("quantbridge/schemas.py")
        self.assertIn("STORAGE_SHEETS", schema_source)
        self.assertIn('"Macro_Regime"', schema_source)
        self.assertIn('"Factor_IC_Report"', schema_source)

        main = read("main_engine.py")
        self.assertIn("--runner", main)
        self.assertIn("run_dag_steps", main)
        self.assertIn("--skip-detail-cache", main)

        main_dag = read("main_dag.py")
        self.assertIn("--dry-run", main_dag)
        self.assertIn("tools/warm_detail_cache.py", main_dag)

    def test_phase_two_detail_cache_exists(self):
        postgres = read("quantbridge/storage/postgres.py")
        repository = read("quantbridge/storage/repository.py")
        api = read("api/server.py")
        stock_service = read("api/services/stock_detail.py")
        etf_service = read("api/services/etf_api.py")
        news_service = read("api/services/news_api.py")
        market_service = read("api/services/market_api.py")
        search_service = read("api/services/search_api.py")
        calendar_service = read("api/services/calendar_api.py")
        news_change_service = read("api/services/news_changes.py")
        news_internal_service = read("api/services/news_internal.py")
        news_public_service = read("api/services/news_public.py")
        news_query_service = read("api/services/news_queries.py")
        news_source_service = read("api/services/news_sources.py")
        risk_service = read("api/services/risk_api.py")
        risk_report_service = read("api/services/risk_reports.py")
        research_service = read("api/services/research_api.py")
        stock_quote_service = read("api/services/stock_quotes.py")
        ranking_service = read("api/services/ranking_api.py")
        system_service = read("api/services/system_api.py")
        app_snapshot_service = read("api/services/app_snapshots.py")
        cache_warm_service = read("api/services/cache_warm.py")
        ops_service = read("api/services/ops_api.py")
        stock_identity_service = read("api/services/stock_identity.py")
        stock_router = read("api/routers/stock.py")
        etf_router = read("api/routers/etfs.py")
        news_router = read("api/routers/news.py")
        market_router = read("api/routers/market.py")
        search_router = read("api/routers/search.py")
        calendar_router = read("api/routers/calendar.py")
        risk_router = read("api/routers/risk.py")
        ranking_router = read("api/routers/ranking.py")
        system_router = read("api/routers/system.py")
        ops_router = read("api/routers/ops.py")
        portfolio_service = read("api/services/portfolio_api.py")
        sector_service = read("api/services/sector_api.py")
        portfolio_router = read("api/routers/portfolio.py")
        sector_router = read("api/routers/sectors.py")
        dashboard = read("GitHub/my-quant-dashboard/data_loader.py")
        warm = read("tools/warm_detail_cache.py")
        configure = read("tools/configure_app_api.py")
        ios_api = read("Stock Analysis/Stock Analysis/APIClient.swift")
        smoke = read("tools/smoke_app_api.py")
        user_flow = read("tools/smoke_user_flow.py")
        qa = read("tools/qa_phase2.py")
        makefile = read("Makefile")

        self.assertIn("def upsert_prices", postgres)
        self.assertIn("def read_prices", postgres)
        self.assertIn("def upsert_identity", repository)
        self.assertIn("def read_identity", repository)
        self.assertIn("create_stock_router", api)
        self.assertIn("create_etf_router", api)
        self.assertIn("create_news_router", api)
        self.assertIn("create_market_router", api)
        self.assertIn("create_portfolio_router", api)
        self.assertIn("create_sector_router", api)
        self.assertIn("create_search_router", api)
        self.assertIn("create_calendar_router", api)
        self.assertIn("create_ranking_router", api)
        self.assertIn("create_system_router", api)
        self.assertIn("create_risk_router", api)
        self.assertIn("create_ops_router", api)
        self.assertIn('router.get("/stock/{ticker}", response_model=StockDetailResponse)', stock_router)
        self.assertIn('router.get("/etfs", response_model=EtfListResponse)', etf_router)
        self.assertIn('router.get("/etfs/{ticker}", response_model=EtfDetailResponse)', etf_router)
        self.assertIn('router.get("/news/issues", response_model=NewsIssuesResponse)', news_router)
        self.assertIn('router.get("/market/indices", response_model=MarketIndicesResponse)', market_router)
        self.assertIn('router.get("/market/indicators", response_model=MarketIndicatorsResponse)', market_router)
        self.assertIn(
            'router.get("/market/indicators/history", response_model=MarketIndicatorHistoryResponse)',
            market_router,
        )
        self.assertIn('router.get("/search/universe", response_model=SearchUniverseResponse)', search_router)
        self.assertIn('router.get("/calendar/earnings", response_model=EarningsCalendarResponse)', calendar_router)
        self.assertIn('router.get("/earnings/{market}", response_model=EarningsResponse)', calendar_router)
        self.assertIn('router.get("/smallcap/{market}", response_model=SmallCapResponse)', ranking_router)
        self.assertIn('router.get("/scored/{market}", response_model=ScoredResponse)', ranking_router)
        self.assertIn('router.get("/health", response_model=HealthResponse)', system_router)
        self.assertIn('router.get("/ready", response_model=ReadyResponse)', system_router)
        self.assertIn('router.get("/backtest/{market}", response_model=BacktestResponse)', risk_router)
        self.assertIn('router.get("/risk/drift", response_model=DriftAlertsResponse)', risk_router)
        self.assertIn('router.get("/risk/order-flow", response_model=OrderFlowResponse)', risk_router)
        self.assertIn('router.get("/ops/data-sources", response_model=OpsTableResponse)', ops_router)
        self.assertIn('router.post("/cache/clear", response_model=CacheClearResponse)', ops_router)
        self.assertIn('router.get("/portfolio/{market}", response_model=PortfolioResponse)', portfolio_router)
        self.assertIn('router.get("/portfolio/{market}/prices", response_model=PortfolioPricesResponse)', portfolio_router)
        self.assertIn('router.get("/sectors/themes", response_model=SectorThemesResponse)', sector_router)
        self.assertIn("class PortfolioApiService", portfolio_service)
        self.assertIn("class PortfolioPayloadBuilder", portfolio_service)
        self.assertIn("class PortfolioPriceEnricher", portfolio_service)
        self.assertIn("class SectorApiService", sector_service)
        self.assertIn("class SectorDetailReconciler", sector_service)
        self.assertIn("class SectorPriceMetricBuilder", sector_service)
        self.assertIn("class SectorThemeClassifier", sector_service)
        self.assertIn("class SectorThemePayloadBuilder", sector_service)
        self.assertIn("class EtfApiService", etf_service)
        self.assertIn("class EtfPriceEnricher", etf_service)
        self.assertIn("class NewsApiService", news_service)
        self.assertIn("class NewsPayloadBuilder", news_service)
        self.assertIn("class NewsChangeEnricher", news_change_service)
        self.assertIn("class InternalNewsBuilder", news_internal_service)
        self.assertIn("class NewsPublicFormatter", news_public_service)
        self.assertIn("class NewsQueryPlanner", news_query_service)
        self.assertIn("class NewsSourceFetcher", news_source_service)
        self.assertIn("class MarketApiService", market_service)
        self.assertIn("class MarketIndicatorHistoryBuilder", market_service)
        self.assertIn("class MarketIndicatorNaverBuilder", market_service)
        self.assertIn("class MarketIndicatorPayloadBuilder", market_service)
        self.assertIn("class MarketIndicatorQuoteBuilder", market_service)
        self.assertIn("class MarketIndicatorStorageBuilder", market_service)
        self.assertIn("class SearchApiService", search_service)
        self.assertIn("class CalendarApiService", calendar_service)
        self.assertIn("class EarningsCalendarDataProvider", calendar_service)
        self.assertIn("class EarningsCalendarPayloadBuilder", calendar_service)
        self.assertIn("class EtfPayloadBuilder", etf_service)
        self.assertIn("class RankingApiService", ranking_service)
        self.assertIn("class RankingPayloadBuilder", ranking_service)
        self.assertIn("class SystemApiService", system_service)
        self.assertIn("class SystemPayloadBuilder", system_service)
        self.assertIn("class ComparisonRecommendationBuilder", risk_service)
        self.assertIn("class SignalEventsPayloadBuilder", risk_service)
        self.assertIn("class RiskReportBuilder", risk_report_service)
        self.assertIn("class YFinanceQuoteBatcher", stock_quote_service)
        self.assertIn("class YFinanceQuoteFrameBuilder", stock_quote_service)
        self.assertIn("class ApiSnapshotCache", app_snapshot_service)
        self.assertIn("class ApiCacheWarmJobFactory", cache_warm_service)
        self.assertIn("class StockIdentityLookup", stock_identity_service)
        self.assertIn("from api.services.stock_identity import", api)
        self.assertIn("class RiskApiService", risk_service)
        self.assertIn("class ResearchPayloadBuilder", research_service)
        self.assertIn("_risk_report_builder", api)
        self.assertIn("class OpsApiService", ops_service)
        self.assertIn("class OpsCheckBuilder", ops_service)
        self.assertIn("class OpsHealthPayloadBuilder", ops_service)
        self.assertIn("class OpsRunPayloadBuilder", ops_service)
        self.assertNotIn('@app.get("/portfolio/{market}")', api)
        self.assertNotIn('@app.get("/sectors/themes")', api)
        self.assertNotIn('@app.get("/etfs")', api)
        self.assertNotIn('@app.get("/news/issues")', api)
        self.assertNotIn('@app.get("/market/indices")', api)
        self.assertNotIn('@app.get("/search/universe")', api)
        self.assertNotIn('@app.get("/calendar/earnings")', api)
        self.assertNotIn('@app.get("/smallcap/{market}")', api)
        self.assertNotIn('@app.get("/scored/{market}")', api)
        self.assertNotIn('@app.get("/health")', api)
        self.assertNotIn('@app.get("/ready")', api)
        self.assertNotIn('@app.get("/risk/drift")', api)
        self.assertNotIn('@app.get("/ops/data-sources")', api)
        self.assertNotIn("@app.on_event", api)
        self.assertIn("lifespan=_api_lifespan", api)
        self.assertIn("read_prices(normal_ticker", stock_service)
        self.assertIn('"source": "storage"', stock_service)
        self.assertIn("_repository().read_prices", dashboard)
        self.assertIn("BASE_DATASETS", warm)
        self.assertIn('default="5y"', warm)
        self.assertIn("DETAIL_CACHE_PIPELINE", read("main_engine.py"))
        self.assertIn("warm-cache", makefile)
        self.assertIn("kr-rank-local", makefile)
        self.assertIn("kr-rank-health", makefile)
        self.assertIn("refresh-app-data-local", makefile)
        self.assertIn("refresh-app-data-staging", makefile)
        self.assertIn("tools/local_kr_ranker.py", makefile)
        self.assertIn("tools/check_kr_rank_health.py", makefile)
        self.assertIn("install-kr-rank-schedule", makefile)
        self.assertIn("tools/install_kr_rank_local_launchd.py", makefile)
        self.assertIn("configure-app-api", makefile)
        self.assertIn("app-smoke", makefile)
        self.assertIn("user-flow", makefile)
        self.assertIn("qa", makefile)
        self.assertIn("APIBaseURL", configure)
        self.assertIn("quantApiBaseUrl", configure)
        self.assertIn("configuredBaseURL", ios_api)
        self.assertIn("targetEnvironment(simulator)", ios_api)
        self.assertIn("/stock/CF?period=5y", smoke)
        self.assertIn("/auth/signup", user_flow)
        self.assertIn("/me/watchlist", user_flow)
        self.assertIn("/auth/me", user_flow)
        self.assertIn("tools/smoke_app_api.py", qa)
        self.assertIn("tools/smoke_user_flow.py", qa)
        self.assertTrue((ROOT / "docs/PHASE2_QA.md").exists())

    def test_mobile_api_contract_is_versioned(self):
        contract_doc = read("docs/MOBILE_API_CONTRACT.md")

        self.assertEqual(MOBILE_API_VERSION, "mobile-v1")
        self.assertEqual(ENDPOINTS["portfolio_prices"]["path"], "/portfolio/{market}/prices")
        self.assertEqual(ENDPOINTS["sector_themes"]["path"], "/sectors/themes")
        self.assertEqual(ENDPOINTS["etfs"]["path"], "/etfs")
        self.assertEqual(ENDPOINTS["etf_detail"]["path"], "/etfs/{ticker}")
        self.assertEqual(ENDPOINTS["news_issues"]["path"], "/news/issues")
        self.assertEqual(ENDPOINTS["market_indicators"]["path"], "/market/indicators")
        self.assertEqual(ENDPOINTS["scored"]["path"], "/scored/{market}")
        self.assertEqual(ENDPOINTS["policy_adjusted_ranking"]["path"], "/research/policy-adjusted-ranking")
        self.assertIn("Daily_Change_Horizon", ENDPOINTS["portfolio_prices"]["response"]["metric"])
        self.assertIn("avg_change_pct", ENDPOINTS["sector_themes"]["response"]["theme"])
        self.assertIn("topHoldings", ENDPOINTS["etfs"]["response"]["item"])
        self.assertIn("relatedTickers", ENDPOINTS["news_issues"]["response"]["item"])
        self.assertIn("Quality_Category", ENDPOINTS["scored"]["response"]["stock"])
        self.assertIn("Investability_Score", ENDPOINTS["scored"]["response"]["stock"])
        self.assertIn("Policy_Rank", ENDPOINTS["policy_adjusted_ranking"]["response"]["item"])
        self.assertIn("Price_Updated_At", FRESHNESS_FIELDS)
        self.assertIn("시가총액 가중 평균", contract_doc)
        self.assertIn("/research/policy-adjusted-ranking", contract_doc)
        self.assertIn("세션 만료 또는 로그아웃은 로컬 관심목록을 지우지 않는다", contract_doc)

    def test_openapi_mobile_responses_are_typed(self):
        from api.server import app

        spec = app.openapi()
        missing = []

        def has_ref(value):
            if isinstance(value, dict):
                return "$ref" in value or any(has_ref(child) for child in value.values())
            if isinstance(value, list):
                return any(has_ref(child) for child in value)
            return False

        for path, methods in spec.get("paths", {}).items():
            for method, operation in methods.items():
                if method.lower() not in {"get", "post", "put", "patch", "delete"}:
                    continue
                content = operation.get("responses", {}).get("200", {}).get("content", {})
                schema = content.get("application/json", {}).get("schema")
                if not has_ref(schema):
                    missing.append(f"{method.upper()} {path}")

        self.assertEqual([], missing)
        self.assertGreaterEqual(len(spec.get("components", {}).get("schemas", {})), 60)

    def test_phase_three_signal_quality_layer_exists(self):
        signal = read("pipeline/15_signal_quality_gate.py")
        policy = read("pipeline/16_factor_weight_policy.py")
        policy_backtest = read("pipeline/17_factor_policy_backtest.py")
        remediation = read("pipeline/18_factor_remediation_plan.py")
        snapshot_backfill = read("tools/backfill_factor_snapshots.py")
        main_dag = read("main_dag.py")
        main = read("main_engine.py")
        schemas = read("quantbridge/schemas.py")
        api = read("api/server.py")
        research_service = read("api/services/research_api.py")
        ops_service = read("api/services/ops_api.py")
        makefile = read("Makefile")
        compose = read("docker-compose.yml")
        research_job = read("tools/run_research_quality.py")
        research_health = read("tools/check_research_quality_health.py")
        launchd = read("tools/install_research_quality_launchd.py")

        self.assertIn("OUTPUT_SHEET = \"Signal_Quality_Gates\"", signal)
        self.assertIn("def build_quality_gates", signal)
        self.assertIn("PASS", signal)
        self.assertIn("WATCH", signal)
        self.assertIn("FAIL", signal)
        self.assertIn("INSUFFICIENT", signal)
        self.assertIn("OUTPUT_SHEET = \"Factor_Weight_Policy\"", policy)
        self.assertIn("def build_weight_policy", policy)
        self.assertIn("Observation-only", policy)
        self.assertIn("OUTPUT_SHEET = \"Factor_Policy_Backtest\"", policy_backtest)
        self.assertIn("def build_policy_backtest", policy_backtest)
        self.assertIn("Observation-only", policy_backtest)
        self.assertIn("OUTPUT_SHEET = \"Factor_Remediation_Plan\"", remediation)
        self.assertIn("def build_remediation_plan", remediation)
        self.assertIn("Root_Cause", remediation)
        self.assertIn("Remediation_Action", remediation)
        self.assertIn("Success_Criteria", remediation)
        self.assertIn("PROXY_BACKFILL", snapshot_backfill)
        self.assertIn("Factor_Snapshot_Backfill_Log", snapshot_backfill)
        self.assertIn("Snapshot_Source", read("pipeline/14_factor_ic_report.py"))
        self.assertIn("Snapshot_Source", policy_backtest)
        self.assertIn("Evidence_Source", signal)
        self.assertIn("Production_Ready", signal)
        self.assertIn("Evidence_Source", policy)
        self.assertIn("Production_Ready", policy)
        self.assertIn("OBSERVE_PROXY", policy_backtest)
        self.assertIn("pipeline/15_signal_quality_gate.py", main_dag)
        self.assertIn("pipeline/16_factor_weight_policy.py", main_dag)
        self.assertIn("pipeline/17_factor_policy_backtest.py", main_dag)
        self.assertIn("pipeline/18_factor_remediation_plan.py", main_dag)
        self.assertIn("SIGNAL_QUALITY_PIPELINE", main)
        self.assertIn("FACTOR_POLICY_PIPELINE", main)
        self.assertIn("FACTOR_POLICY_BACKTEST_PIPELINE", main)
        self.assertIn("FACTOR_REMEDIATION_PIPELINE", main)
        self.assertIn("Signal_Quality_Gates", schemas)
        self.assertIn("Factor_Weight_Policy", schemas)
        self.assertIn("Factor_Policy_Backtest", schemas)
        self.assertIn("Factor_Remediation_Plan", schemas)
        self.assertIn("US_Policy_Adjusted_Ranking", schemas)
        self.assertIn("Policy_Adjusted_Ranking_Summary", schemas)
        self.assertIn("Factor_Snapshot_Backfill_Log", schemas)
        self.assertIn('"Factor_IC_Detail"', schemas)
        research_router = read("api/routers/research.py")
        self.assertIn('@router.get("/research/factor-quality", response_model=ResearchQualityResponse)', research_router)
        self.assertIn('@router.get("/research/factor-policy", response_model=ResearchPolicyResponse)', research_router)
        self.assertIn('@router.get("/research/policy-backtest", response_model=PolicyBacktestResponse)', research_router)
        self.assertIn('@router.get("/research/policy-adjusted-ranking", response_model=PolicyAdjustedRankingResponse)', research_router)
        self.assertIn('@router.get("/research/remediation-plan", response_model=RemediationPlanResponse)', research_router)
        self.assertIn("create_research_router", api)
        self.assertIn("ResearchApiService", api)
        self.assertIn("create_ops_router", api)
        self.assertIn("OpsApiService", api)
        self.assertIn("KR rank refresh", ops_service)
        self.assertIn("_kr_rank_health_check", api)
        self.assertIn("overall_status", research_service)
        self.assertIn("best_factors", research_service)
        self.assertIn("weak_factors", research_service)
        self.assertIn("production_ready_count", research_service)
        self.assertIn("proxy_evidence_count", research_service)
        self.assertIn("latest_research_quality", ops_service)
        self.assertIn("max_age_hours", ops_service)
        self.assertIn("read_pipeline_runs", read("quantbridge/storage/repository.py"))
        self.assertIn("def read_runs", read("quantbridge/storage/postgres.py"))
        self.assertIn("Record_Key", read("quantbridge/storage/postgres.py"))
        self.assertIn("quality-gates", makefile)
        self.assertIn("factor-policy", makefile)
        self.assertIn("policy-backtest", makefile)
        self.assertIn("remediation-plan", makefile)
        self.assertIn("backfill-snapshots", makefile)
        self.assertIn("research-quality", makefile)
        self.assertIn("research-quality-docker", makefile)
        self.assertIn("research-health", makefile)
        self.assertIn("data-quality", makefile)
        self.assertIn("install-research-schedule", makefile)
        self.assertIn("uninstall-research-schedule", makefile)
        self.assertIn("research-schedule-status", makefile)
        self.assertIn("RESEARCH_QUALITY_STEPS", research_job)
        self.assertIn("pipeline/14_factor_ic_report.py", research_job)
        self.assertIn("pipeline/15_signal_quality_gate.py", research_job)
        self.assertIn("pipeline/16_factor_weight_policy.py", research_job)
        self.assertIn("pipeline/17_factor_policy_backtest.py", research_job)
        self.assertIn("tools/build_policy_adjusted_rankings.py", research_job)
        self.assertIn("pipeline/18_factor_remediation_plan.py", research_job)
        self.assertIn("def check_health", research_health)
        self.assertIn("max_age_hours", research_health)
        self.assertIn("pipeline/17_factor_policy_backtest.py", research_health)
        self.assertTrue((ROOT / "tools/check_research_quality_health.py").exists())
        self.assertIn("com.quantbridge.research-quality", launchd)
        self.assertIn("StartCalendarInterval", launchd)
        self.assertIn("launchctl", launchd)
        self.assertIn("research-quality:", compose)
        self.assertIn("tools/Dockerfile.research", compose)
        self.assertTrue((ROOT / "tools/requirements_research.txt").exists())
        self.assertTrue((ROOT / "tools/install_research_quality_launchd.py").exists())
        self.assertIn("load_signal_quality_gates", read("GitHub/my-quant-dashboard/data_loader.py"))
        self.assertIn("load_factor_weight_policy", read("GitHub/my-quant-dashboard/data_loader.py"))
        self.assertIn("load_factor_policy_backtest", read("GitHub/my-quant-dashboard/data_loader.py"))
        self.assertIn("load_factor_remediation_plan", read("GitHub/my-quant-dashboard/data_loader.py"))
        self.assertIn("load_pipeline_runs", read("GitHub/my-quant-dashboard/data_loader.py"))
        self.assertIn("load_data_quality", read("GitHub/my-quant-dashboard/data_loader.py"))
        self.assertIn("def _render_research_job_monitor", read("GitHub/my-quant-dashboard/app.py"))
        self.assertIn("def _render_factor_policy_section", read("GitHub/my-quant-dashboard/app.py"))
        self.assertIn("def _render_factor_policy_backtest_section", read("GitHub/my-quant-dashboard/app.py"))
        self.assertIn("def _render_factor_remediation_section", read("GitHub/my-quant-dashboard/app.py"))
        self.assertIn("def _render_signal_quality_tab", read("GitHub/my-quant-dashboard/app.py"))
        self.assertIn("load_data_quality.clear", read("GitHub/my-quant-dashboard/app.py"))
        self.assertTrue((ROOT / "docs/PHASE3_RESEARCH.md").exists())

    def test_api_container_runtime_is_wired(self):
        compose = read("docker-compose.yml")
        dockerfile = read("api/Dockerfile")
        api_req = read("api/requirements_api.txt")
        api = read("api/server.py")
        ops_service = read("api/services/ops_api.py")
        system_router = read("api/routers/system.py")

        self.assertIn("container_name: quantbridge-api", compose)
        self.assertIn("QUANT_DATABASE_URL: postgresql://quantbridge:quantbridge@postgres:5432/quantbridge", compose)
        self.assertIn("QUANT_API_SQLITE_PATH: /var/lib/quantbridge/quantbridge.sqlite3", compose)
        self.assertIn("/ready", compose)
        self.assertIn("uvicorn", dockerfile)
        self.assertIn("sheets_client.py", dockerfile)
        self.assertIn("psycopg[binary]", api_req)
        self.assertIn('router.get("/ready", response_model=ReadyResponse)', system_router)
        self.assertIn("create_system_router", api)
        self.assertIn("GZipMiddleware", api)
        self.assertIn("minimum_size=1000", api)
        self.assertIn("ttl: int | None = None", api)
        self.assertIn("ttl=15", ops_service)

    def test_github_actions_ci_exists(self):
        ci = read(".github/workflows/ci.yml")
        codeql = read(".github/workflows/codeql.yml")
        dependabot = read(".github/dependabot.yml")
        dashboard_ci = read("GitHub/my-quant-dashboard/.github/workflows/dashboard-ci.yml")
        dashboard_dependabot = read("GitHub/my-quant-dashboard/.github/dependabot.yml")

        self.assertIn("QuantBridge CI", ci)
        self.assertIn("tools/check_ci_workflows.py", ci)
        self.assertIn("tools/check_data_quality.py", ci)
        self.assertIn("tools/check_staging_status.py", ci)
        self.assertIn("python -m unittest test_contracts.py test_smallcap_scoring.py test_data_quality.py test_api_auth.py test_api_ops.py test_config.py test_pipeline_imports.py", ci)
        self.assertIn("python main_dag.py --dry-run --no-prefect", ci)
        self.assertIn("docker compose build api", ci)
        self.assertIn("docker compose build research-quality", ci)
        self.assertIn(":app:assembleDebug", ci)
        android_detects_both_layouts = "andriod/gradlew" in ci and "android/gradlew" in ci
        android_uses_fullstack_dir = "working-directory: android" in ci or "working-directory: andriod" in ci
        self.assertTrue(android_detects_both_layouts or android_uses_fullstack_dir)

        self.assertIn("github/codeql-action/init", codeql)
        self.assertIn("languages: python", codeql)
        self.assertIn("continue-on-error: true", codeql)
        self.assertIn("Check code scanning availability", codeql)
        self.assertIn("steps.code-scanning.outputs.enabled == 'true'", codeql)

        self.assertIn("package-ecosystem: github-actions", dependabot)
        self.assertIn("directory: /GitHub/my-quant-dashboard", dependabot)
        self.assertIn("directory: /api", dependabot)
        self.assertIn("directory: /tools", dependabot)
        self.assertIn("Dashboard CI", dashboard_ci)
        self.assertIn("python -m py_compile", dashboard_ci)
        self.assertIn("data_loader import OK", dashboard_ci)
        self.assertIn("package-ecosystem: github-actions", dashboard_dependabot)
        self.assertIn("package-ecosystem: pip", dashboard_dependabot)

    def test_workspace_audit_ignores_local_android_state(self):
        from tools import audit_workspace

        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            left = tmp_path / "left"
            right = tmp_path / "right"
            left.mkdir()
            right.mkdir()
            (left / "settings.gradle.kts").write_text("pluginManagement {}\n", encoding="utf-8")
            (right / "settings.gradle.kts").write_text("pluginManagement {}\n", encoding="utf-8")

            (left / ".kotlin" / "errors").mkdir(parents=True)
            (left / ".kotlin" / "errors" / "local.log").write_text("left only\n", encoding="utf-8")
            (left / "build").mkdir()
            (left / "build" / "generated.txt").write_text("left generated\n", encoding="utf-8")
            (left / "local.properties").write_text("sdk.dir=/tmp/android-sdk\n", encoding="utf-8")

            self.assertTrue(audit_workspace._dirs_identical(left, right))

        makefile = read("Makefile")
        self.assertIn("workspace-audit", makefile)
        self.assertIn("secret-audit", makefile)
        self.assertIn("ci-local", makefile)
        self.assertIn("tools/check_ci_workflows.py", makefile)
        self.assertIn("tools/check_no_local_artifacts.py", makefile)

    def test_staging_deploy_wiring_exists(self):
        workflow = read(".github/workflows/deploy-api-staging.yml")
        create_script = read("deploy/azure/create-staging-resources.sh")
        deploy_local = read("deploy/azure/deploy-api-local.sh")
        bootstrap_staging = read("deploy/azure/bootstrap-staging-data.sh")
        sync_staging = read("deploy/azure/sync-staging-secrets.sh")
        staging_control = read("deploy/azure/staging-control.sh")
        staging_url = read("deploy/azure/staging-url.sh")
        staging_research = read("deploy/azure/run-staging-research-quality.sh")
        android_qa = read("tools/qa_android_device.py")
        staging_status = read("tools/check_staging_status.py")
        data_quality = read("tools/check_data_quality.py")
        quality = read("quantbridge/quality.py")
        env_example = read("deploy/azure/staging.env.example")
        docs = read("docs/STAGING_DEPLOY.md")
        makefile = read("Makefile")
        sheets = read("sheets_client.py")
        config = read("quantbridge/config.py")
        api = read("api/server.py")
        ops_service = read("api/services/ops_api.py")

        self.assertIn("Deploy API Staging", workflow)
        self.assertIn("workflow_dispatch", workflow)
        self.assertIn("Check required staging configuration", workflow)
        self.assertIn("azure/login", workflow)
        self.assertIn("az acr login", workflow)
        self.assertIn('docker --config "$DOCKER_CONFIG" buildx build', workflow)
        self.assertIn("-f api/Dockerfile", workflow)
        self.assertIn("--push", workflow)
        self.assertIn("az containerapp update", workflow)
        self.assertIn("--no-wait", workflow)
        self.assertIn("AZURE_KEYVAULT_NAME", workflow)
        self.assertIn("az keyvault secret set", workflow)
        self.assertIn("keyvaultref:", workflow)
        self.assertIn("QUANT_GOOGLE_KEY_JSON=secretref:google-key-json", workflow)
        self.assertIn("NAVER_CLIENT_ID=secretref:naver-client-id", workflow)
        self.assertIn("NAVER_CLIENT_SECRET=secretref:naver-client-secret", workflow)
        self.assertIn("bootstrap_data", workflow)
        self.assertIn("tools/bootstrap_storage.py", workflow)
        self.assertIn("tools/warm_detail_cache.py", workflow)
        self.assertIn("/ready", workflow)
        self.assertIn("tools/check_staging_status.py", workflow)
        self.assertIn("tools/check_data_quality.py", workflow)
        self.assertIn("--wait-ready-seconds 150", workflow)
        self.assertIn("--smoke", workflow)

        self.assertIn("az containerapp create", create_script)
        self.assertIn("Microsoft.KeyVault", create_script)
        self.assertIn("az keyvault create", create_script)
        self.assertIn("az keyvault secret set", create_script)
        self.assertIn("keyvaultref:", create_script)
        self.assertIn("az postgres flexible-server create", create_script)
        self.assertIn("az postgres flexible-server db create", create_script)
        self.assertIn("AcrPull", create_script)
        self.assertIn("staging-smoke", makefile)
        self.assertIn("staging-status", makefile)
        self.assertIn("idle-staging", makefile)
        self.assertIn("stop-staging", makefile)
        self.assertIn("start-staging", makefile)
        self.assertIn("delete-staging", makefile)
        self.assertIn("create-staging-resources", makefile)
        self.assertIn("sync-staging-secrets", makefile)
        self.assertIn("deploy-staging-local", makefile)
        self.assertIn("bootstrap-staging-data", makefile)
        self.assertIn("staging-url", makefile)
        self.assertIn("staging-readiness", makefile)
        self.assertIn("staging-ops-health", makefile)
        self.assertIn("staging-research-quality", makefile)
        self.assertIn("android-device-qa", makefile)
        self.assertIn("install-staging-ops-schedule", makefile)

        self.assertIn("docker build -f api/Dockerfile", deploy_local)
        self.assertIn("sync-staging-secrets.sh", deploy_local)
        self.assertIn("NAVER_CLIENT_ID=secretref:naver-client-id", deploy_local)
        self.assertIn("/ready", deploy_local)
        self.assertIn("ContainerAppOperationInProgress", deploy_local)
        self.assertIn("containerapp show", staging_url)
        self.assertIn("NAVER_CLIENT_ID=", env_example)
        self.assertIn("NAVER_CLIENT_SECRET=", env_example)
        self.assertIn("naver-client-id", sync_staging)
        self.assertIn("naver-client-secret", sync_staging)
        self.assertIn("NAVER_CLIENT_ID", docs)
        self.assertIn("properties.configuration.ingress.fqdn", staging_url)
        self.assertIn("az keyvault create", sync_staging)
        self.assertIn("az keyvault secret set", sync_staging)
        self.assertIn("google-key-json", sync_staging)
        self.assertIn("database-url=keyvaultref:", sync_staging)
        self.assertIn("QUANT_GOOGLE_KEY_JSON=secretref:google-key-json", sync_staging)
        self.assertIn("tools/bootstrap_storage.py", bootstrap_staging)
        self.assertIn("tools/warm_detail_cache.py", bootstrap_staging)
        self.assertIn("AllowLocalBootstrap", bootstrap_staging)
        self.assertIn("tools/run_research_quality.py", staging_research)
        self.assertIn("AllowLocalResearchQuality", staging_research)
        self.assertIn("QUANT_ENABLE_POSTGRES=true", staging_research)
        self.assertIn("QUANT_ENABLE_PARQUET=false", staging_research)
        self.assertIn("API URL to configure", android_qa)
        self.assertIn('"android", "andriod"', android_qa)
        self.assertIn("tools/smoke_app_api.py", android_qa)
        self.assertIn("tools/smoke_user_flow.py", android_qa)
        self.assertIn("./gradlew", android_qa)
        self.assertIn("adb", android_qa)
        self.assertIn("android-qa-screenshot.png", android_qa)
        self.assertIn("az postgres flexible-server stop", staging_control)
        self.assertIn("az postgres flexible-server start", staging_control)
        self.assertIn("idle     Keep staging usable", staging_control)
        self.assertIn("--min-replicas 0", staging_control)
        self.assertIn("CONFIRM_DELETE", staging_control)
        self.assertIn("az group delete", staging_control)

        self.assertIn("def resolve_base_url", staging_status)
        self.assertIn("def ready_check", staging_status)
        self.assertIn("def ops_health_check", staging_status)
        self.assertIn("def data_quality_check", staging_status)
        self.assertIn("def smoke_check", staging_status)
        self.assertIn("tools/smoke_app_api.py", staging_status)
        self.assertIn("build_data_quality_report", data_quality)
        self.assertIn("CORE_DATASETS", quality)
        self.assertIn("def evaluate_dataset", quality)
        self.assertIn("def build_data_quality_report", quality)

        self.assertIn("AZURE_CONTAINER_APP", env_example)
        self.assertIn("AZURE_KEYVAULT_NAME", env_example)
        self.assertIn("AZURE_POSTGRES_SERVER", env_example)
        self.assertIn("QUANT_DATABASE_URL", docs)
        self.assertIn("QUANT_GOOGLE_KEY_JSON", docs)
        self.assertIn("Azure Key Vault", docs)
        self.assertIn("make sync-staging-secrets", docs)
        self.assertIn("make staging-research-quality", docs)
        self.assertIn("make staging-ops-health", docs)
        self.assertIn("make staging-readiness", docs)
        self.assertIn("make android-device-qa", docs)
        self.assertIn("make install-staging-ops-schedule", docs)
        self.assertIn("make idle-staging", docs)
        self.assertIn("make stop-staging", docs)
        self.assertIn("manual-only", docs)

        self.assertIn("google_key_json", config)
        self.assertIn("from_service_account_info", sheets)
        self.assertIn("json_secret", ops_service)

    def test_cloud_main_engine_workflow_exists(self):
        workflow = read(".github/workflows/run-main-engine.yml")
        parallel = read(".github/workflows/run-main-engine-parallel.yml")
        docs = read("docs/CLOUD_MAIN_ENGINE.md")

        self.assertIn("Run Main Engine", workflow)
        self.assertIn("workflow_dispatch", workflow)
        self.assertNotIn("schedule:", workflow)
        self.assertIn("run_mode", workflow)
        self.assertIn("test", workflow)
        self.assertIn("full", workflow)
        self.assertIn("RUN_MODE", workflow)
        self.assertIn("env.RUN_MODE", workflow)
        self.assertIn("analyze-only", workflow)
        self.assertIn("smallcap", workflow)
        self.assertIn("--from-smallcap", workflow)
        self.assertIn("smallcap-after-us", workflow)
        self.assertIn("--smallcap-after-us", workflow)
        self.assertIn("smallcap-backtest-only", workflow)
        self.assertIn("--smallcap-backtest-only", workflow)
        self.assertIn("SKIP_DETAIL_CACHE", workflow)
        self.assertIn("start_staging_database", workflow)
        self.assertIn("stop_staging_database_after", workflow)
        self.assertIn("AZURE_CREDENTIALS", workflow)
        self.assertIn("QUANT_DATABASE_URL", workflow)
        self.assertIn("QUANT_GOOGLE_KEY_JSON", workflow)
        self.assertIn("python -m pip install -r requirements.txt", workflow)
        self.assertIn("main_engine.py --runner legacy", workflow)
        self.assertIn("tools/smoke_app_api.py", workflow)
        self.assertIn("tools/check_ops_health.py", workflow)
        self.assertIn("actions/upload-artifact", workflow)
        self.assertIn('default: "true"', workflow)

        self.assertIn("Run Main Engine Parallel", parallel)
        self.assertIn("workflow_dispatch", parallel)
        self.assertIn("schedule:", parallel)
        self.assertIn('cron: "10 20 * * 1-5"', parallel)
        self.assertIn('cron: "10 21 * * 1-5"', parallel)
        self.assertIn("Market close schedule gate", parallel)
        self.assertIn("America/New_York", parallel)
        self.assertIn("shared-prep", parallel)
        self.assertIn("us-core", parallel)
        self.assertIn("kr-core", parallel)
        self.assertIn("smallcap-us", parallel)
        self.assertIn("kr-smallcap-shards", parallel)
        self.assertIn("dart-health-check", parallel)
        self.assertIn("shard_matrix", parallel)
        self.assertIn("max-parallel: 4", parallel)
        self.assertIn("QUANT_DISABLE_DART_FOR_RUN", parallel)
        self.assertIn('QUANT_KR_SMALLCAP_WRITE_SHEET: "false"', parallel)
        self.assertIn("smallcap-backtest", parallel)
        self.assertIn("downstream-report", parallel)
        self.assertIn("parallel-finalize", parallel)
        self.assertIn("main_engine.py --runner legacy --shared-prep-only", parallel)
        self.assertIn("main_engine.py --runner legacy --us-core-only", parallel)
        self.assertIn("main_engine.py --runner legacy --kr-core-only", parallel)
        self.assertIn("main_engine.py --runner legacy --downstream-only", parallel)

        self.assertIn("GitHub -> Actions -> Run Main Engine Parallel", docs)
        self.assertIn("shortly after the Nasdaq", docs)
        self.assertIn("05:10 KST", docs)
        self.assertIn("06:10 KST", docs)
        self.assertIn("10 20 * * 1-5", docs)
        self.assertIn("10 21 * * 1-5", docs)
        self.assertIn("shared-prep", docs)
        self.assertIn("us-core", docs)
        self.assertIn("kr-core", docs)
        self.assertIn("kr-smallcap-shards", docs)
        self.assertIn("DART health check chooses the shard count", docs)
        self.assertIn("reduce provider", docs)
        self.assertIn("smallcap-backtest", docs)
        self.assertIn("downstream-report", docs)
        self.assertIn("| `skip_detail_cache` | `true` |", docs)
        self.assertIn("stop_staging_database_after", docs)

    def test_cache_manager_handles_kr_dart_cache_and_sheet_dates(self):
        source = read("cache_manager.py")

        self.assertIn("def _get_dart_reader_and_corp_code", source)
        self.assertIn("_dart_stock_to_corp", source)
        self.assertIn("os.makedirs(_DART_CACHE_DIR, exist_ok=True)", source)
        self.assertIn("Google Sheets can round-trip", source)
        self.assertIn("last_dt = _parse_dt(last_str)", source)

    def test_codespaces_devcontainers_exist(self):
        root_devcontainer = read(".devcontainer/devcontainer.json")
        root_compose = read(".devcontainer/docker-compose.yml")
        root_post_create = read(".devcontainer/post-create.sh")
        root_start = read(".devcontainer/start-services.sh")
        dashboard_devcontainer = read("GitHub/my-quant-dashboard/.devcontainer/devcontainer.json")
        docs = read("docs/CODESPACES.md")

        self.assertIn("QuantBridge Full Stack", root_devcontainer)
        self.assertIn("postCreateCommand", root_devcontainer)
        self.assertIn("postStartCommand", root_devcontainer)
        self.assertIn("8000", root_devcontainer)
        self.assertIn("8501", root_devcontainer)
        self.assertIn("postgres:16", root_compose)
        self.assertIn("QUANT_DATABASE_URL", root_compose)
        self.assertIn("QUANT_GOOGLE_KEY_PATH", root_compose)
        self.assertIn("QUANT_GOOGLE_KEY_JSON", root_post_create)
        self.assertIn("python -m unittest test_contracts.py", root_post_create)
        self.assertIn("uvicorn api.server:app", root_start)
        self.assertIn("streamlit run app.py", root_start)

        self.assertIn("QuantBridge Dashboard", dashboard_devcontainer)
        self.assertIn("python -m py_compile", dashboard_devcontainer)
        self.assertIn("streamlit run app.py", dashboard_devcontainer)

        self.assertIn("QUANT_GOOGLE_KEY_JSON", docs)
        self.assertIn("make dag-dry-run", docs)

    def test_apps_prefer_storage_before_sheets(self):
        api = read("api/server.py")
        dashboard = read("GitHub/my-quant-dashboard/data_loader.py")

        self.assertIn("from quantbridge.storage import QuantRepository", api)
        self.assertIn("def _load_storage_df", api)
        self.assertLess(api.index("_load_storage_df(sheet_name"), api.index("_spreadsheet().worksheet(sheet_name)"))

        self.assertIn("from quantbridge.storage import QuantRepository", dashboard)
        self.assertIn("def _load_storage_df", dashboard)
        self.assertIn("_SETTINGS.spreadsheet_id", dashboard)

    def test_requirements_are_split_by_runtime(self):
        pipeline_req = read("requirements.txt")
        api_req = read("api/requirements_api.txt")
        dashboard_req = read("GitHub/my-quant-dashboard/requirements.txt")

        for package in ["yfinance", "scikit-learn", "finance-datareader", "OpenDartReader"]:
            self.assertIn(package, pipeline_req)
        self.assertNotIn("streamlit", pipeline_req)
        for package in ["psycopg", "duckdb", "pyarrow", "prefect"]:
            self.assertIn(package, pipeline_req)

        self.assertIn("fastapi", api_req)
        self.assertIn("google-auth", api_req)
        self.assertIn("python-dotenv", api_req)
        self.assertIn("pydantic-settings", api_req)
        self.assertNotIn("oauth2client", api_req)

        self.assertIn("streamlit", dashboard_req)
        self.assertIn("plotly", dashboard_req)


class DashboardIdentityTests(unittest.TestCase):
    def test_kr_smallcap_identity_enrichment_matches_by_code_and_naver(self):
        sys.path.insert(0, str(ROOT / "GitHub/my-quant-dashboard"))
        import data_loader

        original_fetch = data_loader.fetch_kr_identities
        try:
            data_loader.fetch_kr_identities = lambda tickers: {
                "483650": {"Ticker": "483650.KS", "Name": "달바글로벌"},
                "483650.KS": {"Ticker": "483650.KS", "Name": "달바글로벌"},
                "052400.KQ": {"Ticker": "052400.KQ", "Name": "코나아이"},
                "052400": {"Ticker": "052400.KQ", "Name": "코나아이"},
            }

            df = pd.DataFrame([
                {"Ticker": "483650", "Name": "483650"},
                {"Ticker": "171090.KQ", "Name": "171090.KQ"},
                {"Ticker": "052400.KQ", "Name": ""},
            ])
            universe = pd.DataFrame([
                {"Ticker": "171090.KQ", "Name": "선익시스템"},
            ])

            out = data_loader.enrich_kr_company_identities(df, universe_df=universe)

            self.assertEqual(out.loc[0, "Ticker"], "483650.KS")
            self.assertEqual(out.loc[0, "Name"], "달바글로벌")
            self.assertEqual(out.loc[1, "Name"], "선익시스템")
            self.assertEqual(out.loc[2, "Name"], "코나아이")
        finally:
            data_loader.fetch_kr_identities = original_fetch


if __name__ == "__main__":
    unittest.main()
