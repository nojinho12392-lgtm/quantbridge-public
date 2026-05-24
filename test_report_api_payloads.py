import unittest
from unittest.mock import patch

import pandas as pd

import api.server as server
from api.services.sector_api import SectorApiService
from api.services.stock_detail import StockDetailService


class ReportApiPayloadTests(unittest.TestCase):
    def test_stock_detail_uses_storage_only_when_api_external_fetch_is_disabled(self):
        class Repo:
            def read_identity(self, ticker, market=None):
                return {"ticker": ticker, "market": market, "name": "Apple"}

            def upsert_identity(self, identity):
                return None

            def read_prices(self, ticker, period="6mo", market=None):
                return pd.DataFrame()

            def read_price_metrics(self, tickers, market=None):
                return pd.DataFrame(
                    [
                        {
                            "ticker": "AAPL",
                            "market": "US",
                            "current_price": 171.2,
                            "return_1m": 0.04,
                            "as_of": "2026-05-20T00:00:00Z",
                            "updated_at": "2026-05-20T00:00:00Z",
                        }
                    ]
                )

        service = StockDetailService(
            repository=lambda: Repo(),
            cached=lambda key, loader, ttl=None: loader(),
            infer_market=lambda ticker: "US",
            utc_now_iso=lambda: "2026-05-20T00:00:00Z",
            identity_has_valuation=lambda identity: True,
            identity_from_storage=lambda ticker, market: None,
            merge_identity_payload=lambda identity, storage, ticker: identity or {"ticker": ticker},
            info_from_cached=lambda identity, prices: {"name": (identity or {}).get("name")},
            df_to_records=lambda df: df.to_dict(orient="records"),
            price_records_from_yfinance=lambda raw: self.fail("API stock detail should not fetch yfinance"),
            first_float=server._first_float,
            merge_company_profile=lambda info, full: info,
            allow_external_fetch=lambda: False,
        )

        payload = service.detail(ticker="AAPL", period="6mo", refresh=True, profile=True)

        self.assertEqual(payload["source"], "storage_snapshot")
        self.assertEqual(payload["prices"], [])
        self.assertAlmostEqual(payload["info"]["current_price"], 171.2)
        self.assertFalse(payload["storage_complete"])

    def test_stock_detail_sanitizes_null_ohlc_from_storage(self):
        class Repo:
            def read_identity(self, ticker, market=None):
                return {"ticker": ticker, "market": market, "name": "Sandisk"}

            def upsert_identity(self, identity):
                return None

            def read_prices(self, ticker, period="6mo", market=None):
                return pd.DataFrame(
                    [
                        {
                            "date": "2026-05-10",
                            "open": 100.0,
                            "high": 110.0,
                            "low": 98.0,
                            "close": 105.0,
                            "volume": 1,
                        },
                        {
                            "date": "2026-05-11",
                            "open": None,
                            "high": None,
                            "low": None,
                            "close": 108.0,
                            "volume": None,
                        },
                    ]
                )

            def read_price_metrics(self, tickers, market=None):
                return pd.DataFrame()

        service = StockDetailService(
            repository=lambda: Repo(),
            cached=lambda key, loader, ttl=None: loader(),
            infer_market=lambda ticker: "US",
            utc_now_iso=lambda: "2026-05-20T00:00:00Z",
            identity_has_valuation=lambda identity: True,
            identity_from_storage=lambda ticker, market: None,
            merge_identity_payload=lambda identity, storage, ticker: identity or {"ticker": ticker},
            info_from_cached=lambda identity, prices: {"name": (identity or {}).get("name")},
            df_to_records=lambda df: df.to_dict(orient="records"),
            price_records_from_yfinance=lambda raw: self.fail("API stock detail should not fetch yfinance"),
            first_float=server._first_float,
            merge_company_profile=lambda info, full: info,
            allow_external_fetch=lambda: False,
        )

        for refresh in (False, True):
            payload = service.detail(ticker="SNDK", period="1mo", refresh=refresh)
            latest = payload["prices"][-1]
            self.assertEqual(latest["date"], "2026-05-11")
            self.assertAlmostEqual(latest["open"], 108.0)
            self.assertAlmostEqual(latest["high"], 108.0)
            self.assertAlmostEqual(latest["low"], 108.0)
            self.assertAlmostEqual(latest["close"], 108.0)

    def test_market_indicator_quote_staleness_guard(self):
        newer_storage = {"symbol": "^IXIC", "observed_at": "2026-05-20T20:00:00+00:00"}
        older_daily_close = {"symbol": "^IXIC", "observed_at": "2026-05-19T20:00:00+00:00"}
        same_day_daily_close = {"symbol": "^IXIC", "observed_at": "2026-05-20T20:00:00+00:00"}

        self.assertTrue(server._indicator_quote_is_older(older_daily_close, newer_storage))
        self.assertFalse(server._indicator_quote_is_older(same_day_daily_close, newer_storage))

    def test_stored_intraday_indicator_change_uses_previous_regular_close(self):
        item = {
            "symbol": "^IXIC",
            "value": 110.0,
            "close": 110.0,
            "change_abs": 1.0,
            "change_pct": 0.01,
            "observed_at": "2026-05-20T16:00:00+00:00",
        }

        with patch.object(server, "_previous_regular_close_from_storage", return_value=98.0):
            normalized = server._with_regular_previous_close_change_from_storage(item)

        self.assertAlmostEqual(normalized["change_abs"], 12.0)
        self.assertAlmostEqual(normalized["change_pct"], 12.0 / 98.0)

    def test_sector_detail_resolves_combined_label_alias_from_cached_theme(self):
        cache: dict[str, dict] = {}

        def cached(key, loader, ttl=0):
            if key not in cache:
                cache[key] = loader()
            return cache[key]

        service = SectorApiService(
            cached=cached,
            invalidate=lambda key: cache.pop(key, None),
            payload=lambda market, limit, members, focus_label=None: {
                "market": market,
                "generated_at": "now",
                "items": [
                    {"label": "원자력", "members": [{"Ticker": "CEG"}]},
                    {"label": "SMR", "members": [{"Ticker": "SMR"}]},
                ],
            },
        )

        payload = service.sector_theme_detail("원자력/SMR", market="ALL", members=80)

        self.assertEqual(payload["item"]["label"], "원자력")

    def test_us_daily_change_uses_regular_close_when_market_is_closed(self):
        prices = pd.DataFrame(
            [
                {"ticker": "INTC", "date": "2026-05-18", "close": 100.0},
                {"ticker": "INTC", "date": "2026-05-19", "close": 110.0},
            ]
        )
        snapshot = {
            "current_price": 130.0,
            "as_of": "2026-05-20T14:45:00+00:00",
        }

        with patch.object(server, "_is_us_equity_regular_session_open", return_value=False):
            change, horizon = server._daily_change_from_price_frame(prices, snapshot, market="US")

        self.assertEqual(horizon, "전장")
        self.assertAlmostEqual(change, 0.10)

    def test_us_daily_change_uses_latest_snapshot_when_market_is_open(self):
        prices = pd.DataFrame(
            [
                {"ticker": "INTC", "date": "2026-05-18", "close": 100.0},
                {"ticker": "INTC", "date": "2026-05-19", "close": 110.0},
            ]
        )
        snapshot = {
            "current_price": 121.0,
            "as_of": "2026-05-20T14:45:00+00:00",
        }

        with patch.object(server, "_is_us_equity_regular_session_open", return_value=True):
            change, horizon = server._daily_change_from_price_frame(prices, snapshot, market="US")

        self.assertEqual(horizon, "오늘")
        self.assertAlmostEqual(change, 0.10)

    def test_sector_detail_reconcile_uses_stock_detail_price_fields(self):
        class FakeStockDetailService:
            def detail(self, *, ticker, period="6mo", refresh=False, profile=False):
                self.last_call = {
                    "ticker": ticker,
                    "period": period,
                    "refresh": refresh,
                    "profile": profile,
                }
                return {
                    "info": {
                        "current_price": 55.0,
                        "daily_change_pct": -0.049,
                        "daily_change_horizon": "전장",
                        "market_cap": 1_000_000_000,
                        "price_updated_at": "2026-05-20T14:45:00+00:00",
                    }
                }

        fake_service = FakeStockDetailService()
        members = [
            {
                "Ticker": "INTC",
                "Current_Price": 120.0,
                "Daily_Change_Pct": 0.14,
                "Daily_Change_Horizon": "오늘",
                "MarketCap": 900_000_000,
            }
        ]

        with patch.object(server, "_stock_detail_service", fake_service):
            reconciled = server._sector_detail_reconcile_members(members)

        self.assertEqual(fake_service.last_call["ticker"], "INTC")
        self.assertFalse(fake_service.last_call["refresh"])
        self.assertFalse(fake_service.last_call["profile"])
        self.assertEqual(reconciled[0]["Current_Price"], 120.0)
        self.assertEqual(reconciled[0]["Daily_Change_Pct"], 0.14)
        self.assertEqual(reconciled[0]["Daily_Change_Horizon"], "오늘")
        self.assertEqual(reconciled[0]["MarketCap"], 1_000_000_000)
        self.assertEqual(reconciled[0]["Price_Updated_At"], "2026-05-20T14:45:00+00:00")

    def test_portfolio_prices_uses_order_independent_unique_batch(self):
        with patch.object(server, "_portfolio_price_snapshot_batch", return_value={}) as snapshots, \
             patch.object(server, "_portfolio_price_metrics_batch", return_value={"AAPL": (170.0, 0.04), "MSFT": (420.0, 0.03)}), \
             patch.object(server, "_portfolio_daily_change_batch", return_value={"AAPL": (1.2, "regular"), "MSFT": (-0.5, "regular")}), \
             patch.object(server, "_naver_kr_stock_price_batch", return_value={}):
            payload = server.portfolio_prices("us", tickers="MSFT,AAPL,MSFT", refresh=True)

        self.assertEqual([row["Ticker"] for row in payload["metrics"]], ["AAPL", "MSFT"])
        snapshots.assert_called_once_with(["AAPL", "MSFT"], "US")

    def test_storage_portfolio_meta_includes_cash_generated_and_expected_return(self):
        frames = {
            "US_Final_Portfolio": pd.DataFrame(
                [
                    {"Market": "US", "Ticker": "AAA", "Weight(%)": 0.50, "Expected_Return": 0.10},
                    {"Market": "US", "Ticker": "BBB", "Weight(%)": 0.25, "Expected_Return": 0.08},
                ]
            ),
            "US_Final_Portfolio_Risk_Summary": pd.DataFrame(
                [
                    {
                        "Market": "US",
                        "Metric": "Cash_Weight",
                        "Value": 0.25,
                        "Generated_At": "2026-05-13 08:30:00",
                    },
                    {
                        "Market": "US",
                        "Metric": "Invested_Fraction",
                        "Value": 0.75,
                        "Generated_At": "2026-05-13 08:30:00",
                    },
                ]
            ),
        }

        with patch.object(server, "_load_storage_df", side_effect=lambda dataset, market=None: frames.get(dataset, pd.DataFrame())):
            meta, stocks = server._load_portfolio("US_Final_Portfolio")

        self.assertEqual(meta["Source"], "storage")
        self.assertEqual(meta["Cash_Weight"], "0.25")
        self.assertEqual(meta["Cash Weight"], "0.25")
        self.assertEqual(meta["Generated"], "2026-05-13 08:30:00")
        self.assertEqual(meta["Generated_At"], "2026-05-13 08:30:00")
        self.assertEqual(meta["Expected_Return"], "0.0700")
        self.assertEqual(len(stocks), 2)

    def test_portfolio_risk_payload_sorts_holdings_and_sectors(self):
        frames = {
            "US_Final_Portfolio_Risk_Summary": pd.DataFrame(
                [{"Market": "US", "Metric": "Portfolio_Vol", "Value": 0.18}]
            ),
            "US_Final_Portfolio_Risk": pd.DataFrame(
                [
                    {"Market": "US", "Ticker": "BBB", "Name": "Beta", "Risk_Contribution_Pct": 0.10},
                    {"Market": "US", "Ticker": "AAA", "Name": "Alpha", "Risk_Contribution_Pct": 0.30},
                ]
            ),
            "US_Final_Portfolio_Risk_Sectors": pd.DataFrame(
                [
                    {"Market": "US", "Sector": "Utilities", "Sector_Risk_Contribution_Pct": 0.05},
                    {"Market": "US", "Sector": "Technology", "Sector_Risk_Contribution_Pct": 0.45},
                ]
            ),
        }

        with patch.object(server, "_load_storage_df", side_effect=lambda dataset, market=None: frames.get(dataset, pd.DataFrame())):
            payload = server._portfolio_risk_payload("US", limit=10)

        self.assertEqual(payload["market"], "US")
        self.assertEqual(payload["summary"][0]["Metric"], "Portfolio_Vol")
        self.assertEqual([row["Ticker"] for row in payload["holdings"]], ["AAA", "BBB"])
        self.assertEqual([row["Sector"] for row in payload["sectors"]], ["Technology", "Utilities"])

    def test_rebalance_payload_sorts_by_executable_trade_size(self):
        frames = {
            "US_Rebalance_Execution_Summary": pd.DataFrame(
                [{"Market": "US", "Metric": "Trade_Count", "Value": 2}]
            ),
            "US_Rebalance_Execution": pd.DataFrame(
                [
                    {"Market": "US", "Ticker": "LOW", "Name": "Low", "Action": "BUY", "Executable_Trade_Value": 100.0},
                    {"Market": "US", "Ticker": "HIGH", "Name": "High", "Action": "SELL", "Executable_Trade_Value": -500.0},
                ]
            ),
        }

        with patch.object(server, "_load_storage_df", side_effect=lambda dataset, market=None: frames.get(dataset, pd.DataFrame())):
            payload = server._rebalance_payload("US", limit=10)

        self.assertEqual(payload["market"], "US")
        self.assertEqual(payload["summary"][0]["Metric"], "Trade_Count")
        self.assertEqual([row["Ticker"] for row in payload["orders"]], ["HIGH", "LOW"])

    def test_shadow_attribution_payload_filters_market(self):
        frames = {
            "Shadow_Portfolio_Attribution_Summary": pd.DataFrame(
                [
                    {"Market": "US", "Horizon_Trading_Days": 20, "Alpha_Actual": 0.02},
                    {"Market": "KR", "Horizon_Trading_Days": 20, "Alpha_Actual": -0.01},
                ]
            ),
            "Shadow_Portfolio_Attribution": pd.DataFrame(
                [
                    {"Market": "KR", "Ticker": "005930.KS", "Name": "Samsung", "Actual_Contribution": 0.03},
                    {"Market": "US", "Ticker": "AAPL", "Name": "Apple", "Actual_Contribution": -0.04},
                ]
            ),
            "Shadow_Portfolio_Sector_Attribution": pd.DataFrame(
                [
                    {"Market": "US", "Sector": "Technology", "Actual_Contribution": -0.02},
                    {"Market": "KR", "Sector": "Technology", "Actual_Contribution": 0.01},
                ]
            ),
        }

        with patch.object(server, "_load_storage_df", side_effect=lambda dataset, market=None: frames.get(dataset, pd.DataFrame())):
            payload = server._shadow_attribution_payload("US", limit=10)

        self.assertEqual(payload["market"], "US")
        self.assertEqual([row["Market"] for row in payload["summary"]], ["US"])
        self.assertEqual([row["Ticker"] for row in payload["items"]], ["AAPL"])
        self.assertEqual([row["Market"] for row in payload["sectors"]], ["US"])

    def test_stock_identity_merges_universe_valuation_fields(self):
        frames = {
            "KR_Final_Portfolio": pd.DataFrame(
                [{"Ticker": "005930.KS", "Name": "삼성전자", "MarketCap": 400_000_000_000_000}]
            ),
            "KR_Universe": pd.DataFrame(
                [
                    {
                        "Ticker": "005930.KS",
                        "PER": 13.2,
                        "PBR": 1.1,
                        "ROE": 0.12,
                        "Revenue": 300_000_000_000_000,
                        "RevenueGrowth": 0.08,
                        "OperatingMargin": 0.16,
                        "GrossMargin": 0.35,
                        "DebtToEquity": 22.5,
                    }
                ]
            ),
        }

        with patch.object(server, "_load_storage_df", side_effect=lambda dataset, market=None: frames.get(dataset, pd.DataFrame())):
            identity = server._identity_from_storage("005930.KS", "KR")
            info = server._info_from_cached(
                identity,
                [
                    {"close": 70000, "high": 70500, "low": 69000},
                    {"close": 71000, "high": 71200, "low": 69800},
                ],
            )

        self.assertEqual(identity["name"], "삼성전자")
        self.assertAlmostEqual(info["pe_ratio"], 13.2)
        self.assertAlmostEqual(info["price_to_book"], 1.1)
        self.assertAlmostEqual(info["return_on_equity"], 0.12)
        self.assertAlmostEqual(info["operating_margin"], 0.16)

    def test_company_profile_preserves_cached_per_when_yfinance_omits_it(self):
        info = {"pe_ratio": 13.2, "price_to_book": 1.1, "return_on_equity": 0.12}
        full = {
            "longName": "Samsung Electronics",
            "sector": "Technology",
            "industry": "Consumer Electronics",
            "country": "South Korea",
            "city": "Suwon",
            "exchange": "KSC",
        }

        merged = server._merge_company_profile(info, full)

        self.assertAlmostEqual(merged["pe_ratio"], 13.2)
        self.assertAlmostEqual(merged["price_to_book"], 1.1)
        self.assertAlmostEqual(merged["return_on_equity"], 0.12)


if __name__ == "__main__":
    unittest.main()
