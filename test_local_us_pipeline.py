import unittest

import pandas as pd

from pipeline.data.us_local_universe import LocalUsUniverseConfig, build_local_us_universe
from pipeline.scoring.us_local_scorer import (
    build_us_portfolio_candidates,
    build_us_smallcap_candidates,
    score_local_us_stocks,
)
from quantbridge.schemas import PORTFOLIO_COLS, SCORED_COLS_ML, SMALLCAP_COLS, UNIVERSE_COLS


def _pit():
    return pd.DataFrame(
        {
            "Ticker": ["AAPL", "AAPL", "SMAL", "SMAL"],
            "Filing_Date": ["2024-02-01", "2025-02-01", "2024-03-01", "2025-03-01"],
            "Revenue": [100, 120, 10, 14],
            "ROE": [0.4, 0.5, 0.1, 0.18],
            "ROIC": [0.3, 0.35, 0.08, 0.14],
            "OperatingMargin": [0.3, 0.32, 0.08, 0.13],
            "GrossMargin": [0.45, 0.46, 0.35, 0.40],
            "FCF_Margin": [0.2, 0.22, 0.04, 0.08],
            "FCF_NI": [1.0, 1.1, 0.5, 0.8],
            "InterestCoverage": [20, 25, 5, 8],
            "DebtToEquity": [1, 1, 1, 1],
            "Debt_EBITDA": [0.5, 0.4, 1.5, 1.2],
            "RevGrowth": [0.1, 0.2, 0.15, 0.4],
            "EPS_Growth": [0.1, 0.2, 0.1, 0.25],
            "TotalAssets": [200, 220, 30, 40],
            "CurrentAssets": [80, 90, 12, 16],
            "CurrentLiabilities": [40, 42, 8, 9],
            "RetainedEarnings": [70, 80, 4, 6],
            "TotalLiabilities": [90, 95, 15, 16],
        }
    )


def _history():
    return pd.DataFrame(
        {
            "Ticker": ["AAPL", "SMAL"],
            "History_Years": [5, 5],
            "ROIC_5Y_Median": [0.32, 0.12],
            "ROIC_5Y_Stability": [0.9, 0.7],
            "Revenue_CAGR_5Y": [0.12, 0.25],
            "FCF_Positive_Years_5Y": [1.0, 0.8],
            "Margin_Stability_5Y": [0.9, 0.7],
            "Quality_Persistence_Score": [0.9, 0.7],
        }
    )


def _info(ticker):
    return {
        "AAPL": {
            "Name": "Apple",
            "Sector": "Technology",
            "MarketCap": 3_000_000_000_000,
            "PER": 25,
            "PBR": 10,
            "PEG": 2,
            "EV_EBITDA": 20,
            "DivYield": 0.005,
        },
        "SMAL": {
            "Name": "Small Quality",
            "Sector": "Technology",
            "MarketCap": 2_000_000_000,
            "PER": 18,
            "PBR": 3,
            "PEG": 1.2,
            "EV_EBITDA": 12,
            "DivYield": 0,
        },
    }.get(ticker, {})


class LocalUsPipelineTests(unittest.TestCase):
    def test_build_local_us_universe_from_sec_and_info(self):
        universe = build_local_us_universe(
            ["AAPL", "SMAL"],
            config=LocalUsUniverseConfig(limit=10, delay=0),
            pit_df=_pit(),
            info_fetcher=_info,
        )

        self.assertEqual(list(universe.columns[: len(UNIVERSE_COLS)]), UNIVERSE_COLS)
        self.assertEqual(len(universe), 2)
        self.assertEqual(universe.iloc[0]["Name"], "Apple")
        self.assertEqual(universe.iloc[1]["Revenue"], 14)

    def test_score_local_us_stocks_without_prices(self):
        universe = build_local_us_universe(
            ["AAPL", "SMAL"],
            config=LocalUsUniverseConfig(limit=10, delay=0),
            pit_df=_pit(),
            info_fetcher=_info,
        )
        scored = score_local_us_stocks(
            universe,
            pit_df=_pit(),
            history_df=_history(),
            download_prices=False,
        )

        self.assertEqual(list(scored.columns), SCORED_COLS_ML)
        self.assertEqual(len(scored), 2)
        self.assertTrue(scored["Final_Score"].notna().all())
        self.assertIn("Quality_Category", scored.columns)

    def test_build_us_portfolio_and_smallcap_candidates(self):
        universe = build_local_us_universe(
            ["AAPL", "SMAL"],
            config=LocalUsUniverseConfig(limit=10, delay=0),
            pit_df=_pit(),
            info_fetcher=_info,
        )
        scored = score_local_us_stocks(
            universe,
            pit_df=_pit(),
            history_df=_history(),
            download_prices=False,
        )

        portfolio = build_us_portfolio_candidates(scored, size=2, min_confidence=0.2)
        smallcap = build_us_smallcap_candidates(scored, size=5, min_confidence=0.2)

        self.assertEqual(list(portfolio.columns), PORTFOLIO_COLS)
        self.assertGreaterEqual(len(portfolio), 1)
        self.assertEqual(list(smallcap.columns), SMALLCAP_COLS)
        self.assertEqual(len(smallcap), 1)
        self.assertEqual(smallcap.iloc[0]["Ticker"], "SMAL")


if __name__ == "__main__":
    unittest.main()
