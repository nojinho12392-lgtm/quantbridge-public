import unittest

import pandas as pd

from pipeline.data.kr_local_universe import LocalKrUniverseConfig, build_local_kr_universe, parse_naver_market_cap
from pipeline.scoring.kr_local_scorer import (
    build_kr_portfolio_candidates,
    build_kr_smallcap_candidates,
    score_local_kr_stocks,
)
from quantbridge.schemas import PORTFOLIO_COLS, SCORED_COLS, SMALLCAP_COLS, UNIVERSE_COLS


def _pit():
    return pd.DataFrame(
        {
            "Ticker": ["005930.KS", "005930.KS", "000270.KS", "000270.KS"],
            "Fiscal_Year": [2023, 2024, 2023, 2024],
            "Revenue": [100, 120, 80, 110],
            "ROE": [0.12, 0.16, 0.18, 0.24],
            "ROIC": [0.13, 0.17, 0.18, 0.25],
            "OperatingMargin": [0.11, 0.14, 0.12, 0.16],
            "GrossMargin": [0.35, 0.38, 0.31, 0.34],
            "FCF_Margin": [0.06, 0.08, 0.09, 0.12],
            "FCF_NI": [0.7, 0.8, 0.9, 1.0],
            "InterestCoverage": [8, 12, 9, 14],
            "DebtToEquity": [40, 35, 50, 38],
            "Debt_EBITDA": [1.5, 1.2, 1.7, 1.1],
            "RevGrowth": [0.05, 0.20, 0.10, 0.375],
            "EPS_Growth": [0.04, 0.22, 0.08, 0.35],
            "TotalAssets": [200, 220, 150, 180],
            "CurrentAssets": [90, 110, 70, 95],
            "CurrentLiabilities": [40, 45, 35, 40],
            "RetainedEarnings": [80, 95, 60, 80],
            "TotalLiabilities": [70, 75, 55, 65],
        }
    )


def _history():
    return pd.DataFrame(
        {
            "Ticker": ["005930.KS", "000270.KS"],
            "History_Years": [5, 5],
            "ROIC_5Y_Median": [0.15, 0.22],
            "ROIC_5Y_Stability": [0.8, 0.9],
            "Revenue_CAGR_5Y": [0.10, 0.20],
            "FCF_Positive_Years_5Y": [0.8, 1.0],
            "Margin_Stability_5Y": [0.7, 0.8],
            "Quality_Persistence_Score": [0.65, 0.85],
        }
    )


def _naver(code):
    return {
        "005930": {
            "Name": "삼성전자",
            "MarketCap": 500_000_000_000_000,
            "PER": 20,
            "PBR": 1.5,
            "ROE": 0.15,
            "OperatingMargin": 0.13,
            "RevenueGrowth": 0.18,
            "DebtToEquity": 30,
        },
        "000270": {
            "Name": "기아",
            "MarketCap": 70_000_000_000_000,
            "PER": 8,
            "PBR": 0.9,
            "ROE": 0.23,
            "OperatingMargin": 0.15,
            "RevenueGrowth": 0.30,
            "DebtToEquity": 35,
        },
    }.get(code, {})


class LocalKrPipelineTests(unittest.TestCase):
    def test_parse_naver_market_cap(self):
        self.assertEqual(parse_naver_market_cap("1,198조 7,267억원"), 1_198_726_700_000_000)
        self.assertEqual(parse_naver_market_cap("7,267억원"), 726_700_000_000)

    def test_build_local_universe_from_pit_and_naver(self):
        universe = build_local_kr_universe(
            ["005930.KS", "000270.KS"],
            pit_df=_pit(),
            naver_fetcher=_naver,
        )

        self.assertEqual(list(universe.columns), UNIVERSE_COLS)
        self.assertEqual(len(universe), 2)
        samsung = universe[universe["Ticker"].eq("005930.KS")].iloc[0]
        self.assertEqual(samsung["Name"], "삼성전자")
        self.assertEqual(samsung["PER"], 20)
        self.assertEqual(samsung["Revenue"], 120)

    def test_build_local_universe_combines_dart_seed_and_naver_top_tickers(self):
        universe = build_local_kr_universe(
            None,
            config=LocalKrUniverseConfig(kospi_limit=1, kosdaq_limit=1, delay=0),
            pit_df=_pit(),
            naver_fetcher=_naver,
            top_tickers_fetcher=lambda kospi, kosdaq: ["005930.KS", "123456.KQ"],
        )

        self.assertIn("005930.KS", set(universe["Ticker"]))
        self.assertIn("123456.KQ", set(universe["Ticker"]))
        self.assertEqual(len(universe["Ticker"]), len(set(universe["Ticker"])))

    def test_score_local_kr_stocks_without_prices(self):
        universe = build_local_kr_universe(
            ["005930.KS", "000270.KS"],
            pit_df=_pit(),
            naver_fetcher=_naver,
        )
        scored = score_local_kr_stocks(
            universe,
            pit_df=_pit(),
            history_df=_history(),
            download_prices=False,
        )

        self.assertEqual(list(scored.columns), SCORED_COLS)
        self.assertEqual(len(scored), 2)
        self.assertTrue(scored["Final_Score"].notna().all())
        self.assertIn("Quality_Category", scored.columns)
        self.assertEqual(scored.iloc[0]["Ticker"], "000270.KS")

    def test_build_kr_portfolio_candidates_from_scores(self):
        universe = build_local_kr_universe(
            ["005930.KS", "000270.KS"],
            pit_df=_pit(),
            naver_fetcher=_naver,
        )
        scored = score_local_kr_stocks(
            universe,
            pit_df=_pit(),
            history_df=_history(),
            download_prices=False,
        )

        portfolio = build_kr_portfolio_candidates(scored, size=2)

        self.assertEqual(list(portfolio.columns), PORTFOLIO_COLS)
        self.assertEqual(len(portfolio), 2)
        self.assertEqual(portfolio.iloc[0]["Ticker"], scored.iloc[0]["Ticker"])
        self.assertAlmostEqual(float(portfolio["Weight(%)"].sum()), 1.0, places=6)
        self.assertEqual(set(portfolio["Market"]), {"KR"})

    def test_build_kr_portfolio_candidates_excludes_data_gap_rows(self):
        scored = pd.DataFrame([
            {
                "Rank": 1,
                "Ticker": "123456.KQ",
                "Name": "정보부족",
                "Market": "KR",
                "MarketCap": 1,
                "Total_Score": 1.0,
                "Final_Score": 1.0,
                "Quality_Category": "Data Gap",
                "Quality_Data_Confidence": 0.25,
                "Last_Updated": "2026-05-25",
            },
            {
                "Rank": 2,
                "Ticker": "000270.KS",
                "Name": "기아",
                "Market": "KR",
                "MarketCap": 1,
                "Total_Score": 0.8,
                "Final_Score": 0.8,
                "Quality_Category": "QARP Candidate",
                "Quality_Data_Confidence": 0.8,
                "Last_Updated": "2026-05-25",
            },
        ])

        portfolio = build_kr_portfolio_candidates(scored, size=2)

        self.assertEqual(len(portfolio), 1)
        self.assertEqual(portfolio.iloc[0]["Ticker"], "000270.KS")

    def test_build_kr_smallcap_candidates_filters_by_size_and_confidence(self):
        scored = pd.DataFrame([
            {
                "Ticker": "123456.KQ",
                "Name": "성장소형주",
                "Market": "KR",
                "MarketCap": 500_000_000_000,
                "Total_Score": 0.7,
                "Final_Score": 0.9,
                "Business_Quality_Score": 0.65,
                "Investability_Score": 0.62,
                "Quality_Data_Confidence": 0.35,
                "ROIC": 0.12,
                "RevGrowth": 0.2,
                "GrossMargin": 0.4,
                "FCF_Margin": 0.08,
                "Debt_EBITDA": 1.2,
                "Last_Updated": "2026-05-25",
            },
            {
                "Ticker": "005930.KS",
                "Name": "대형주",
                "Market": "KR",
                "MarketCap": 500_000_000_000_000,
                "Total_Score": 0.95,
                "Final_Score": 1.0,
                "Business_Quality_Score": 0.9,
                "Investability_Score": 0.9,
                "Quality_Data_Confidence": 1.0,
            },
        ])

        smallcap = build_kr_smallcap_candidates(scored, size=5)

        self.assertEqual(list(smallcap.columns), SMALLCAP_COLS)
        self.assertEqual(len(smallcap), 1)
        self.assertEqual(smallcap.iloc[0]["Ticker"], "123456.KQ")
        self.assertGreater(float(smallcap.iloc[0]["SmallCap_Bonus"]), 0)
        self.assertGreater(float(smallcap.iloc[0]["Data_Confidence"]), 0)


if __name__ == "__main__":
    unittest.main()
