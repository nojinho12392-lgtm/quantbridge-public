"""Unit tests for QuantBridge data quality checks."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
import unittest

import pandas as pd

from quantbridge.quality import DatasetSpec, build_data_quality_report, evaluate_dataset


def _fresh_date(days_ago: int = 0) -> str:
    return (datetime.now(timezone.utc) - timedelta(days=days_ago)).date().isoformat()


class DataQualityTests(unittest.TestCase):
    def test_good_portfolio_passes(self):
        df = pd.DataFrame([
            {
                "Rank": 1,
                "Ticker": "AAPL",
                "Name": "Apple",
                "Market": "US",
                "Sector": "Tech",
                "MarketCap": 1_000_000_000,
                "Weight(%)": 0.6,
                "Current_Price": 190.0,
                "Return_1M": 0.04,
                "Total_Score": 90.0,
                "ROIC": 0.3,
                "RevGrowth": 0.1,
                "GrossMargin": 0.5,
                "Expected_Return": 0.12,
                "Last_Updated": _fresh_date(),
            },
            {
                "Rank": 2,
                "Ticker": "MSFT",
                "Name": "Microsoft",
                "Market": "US",
                "Sector": "Tech",
                "MarketCap": 1_000_000_000,
                "Weight(%)": 0.4,
                "Current_Price": 420.0,
                "Return_1M": 0.03,
                "Total_Score": 85.0,
                "ROIC": 0.2,
                "RevGrowth": 0.1,
                "GrossMargin": 0.5,
                "Expected_Return": 0.1,
                "Last_Updated": _fresh_date(),
            },
        ])
        report = evaluate_dataset(DatasetSpec("US_Final_Portfolio", market="US"), df)
        self.assertEqual(report["status"], "OK")

    def test_duplicate_ticker_and_bad_weight_fail(self):
        df = pd.DataFrame([
            {
                "Rank": 1,
                "Ticker": "AAPL",
                "Name": "Apple",
                "Market": "US",
                "Sector": "Tech",
                "MarketCap": 1,
                "Weight(%)": 1.2,
                "Total_Score": 10,
                "ROIC": 0.1,
                "RevGrowth": 0.1,
                "GrossMargin": 0.1,
                "Expected_Return": 0.1,
                "Last_Updated": _fresh_date(40),
            },
            {
                "Rank": 2,
                "Ticker": "AAPL",
                "Name": "Duplicate",
                "Market": "US",
                "Sector": "Tech",
                "MarketCap": 1,
                "Weight(%)": 0.1,
                "Total_Score": 10,
                "ROIC": 0.1,
                "RevGrowth": 0.1,
                "GrossMargin": 0.1,
                "Expected_Return": 0.1,
                "Last_Updated": _fresh_date(40),
            },
        ])
        report = evaluate_dataset(DatasetSpec("US_Final_Portfolio", market="US", max_age_days=7), df)
        self.assertEqual(report["status"], "FAIL")
        failing = {check["name"] for check in report["checks"] if check["status"] == "FAIL"}
        self.assertIn("Ticker uniqueness", failing)
        self.assertIn("Weight(%) range", failing)

    def test_report_rolls_up_dataset_statuses(self):
        good = pd.DataFrame([
            {
                "Rank": 1,
                "Ticker": "AAPL",
                "Name": "Apple",
                "Market": "US",
                "Sector": "Tech",
                "MarketCap": 1,
                "Weight(%)": 1.0,
                "Current_Price": 190.0,
                "Return_1M": 0.04,
                "Total_Score": 10,
                "ROIC": 0.1,
                "RevGrowth": 0.1,
                "GrossMargin": 0.1,
                "Expected_Return": 0.1,
                "Last_Updated": _fresh_date(),
            },
        ])
        bad = pd.DataFrame()
        specs = [
            DatasetSpec("US_Final_Portfolio", market="US"),
            DatasetSpec("KR_Final_Portfolio", market="KR"),
        ]
        frames = {"US_Final_Portfolio": good, "KR_Final_Portfolio": bad}
        report = build_data_quality_report(lambda dataset, market: frames[dataset], specs=specs)
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["status_counts"].get("OK"), 1)
        self.assertEqual(report["status_counts"].get("FAIL"), 1)

    def test_staging_contract_allows_neutral_scores_cash_and_legacy_smallcap(self):
        scored = pd.DataFrame([
            {
                "Rank": 1,
                "Ticker": "AAPL",
                "Name": "Apple",
                "Market": "US",
                "Sector": "Tech",
                "MarketCap": 1,
                "Value_Score": 1,
                "Quality_Score": 1,
                "Momentum_Score": 1,
                "Total_Score": 10,
                "Final_Score": 8,
                "Score_Neutral": -1.25,
                "ML_Score": 0.4,
                "Combined_Score": 0.7,
                "ROIC": 0.1,
                "RevGrowth": 0.1,
                "GrossMargin": 0.1,
                "FCF_Margin": 0.1,
                "Debt_EBITDA": 1,
                "PEG": 1,
                "Last_Updated": _fresh_date(),
            },
        ])
        scored_report = evaluate_dataset(DatasetSpec("US_Scored_Stocks", market="US"), scored)
        self.assertEqual(scored_report["status"], "OK")
        failing = {check["name"] for check in scored_report["checks"] if check["status"] == "FAIL"}
        self.assertNotIn("Score_Neutral range", failing)

        portfolio = pd.DataFrame([
            {
                "Rank": 1,
                "Ticker": "AAPL",
                "Name": "Apple",
                "Market": "US",
                "Sector": "Tech",
                "MarketCap": 1,
                "Weight(%)": 0.6,
                "Current_Price": 190.0,
                "Return_1M": 0.04,
                "Total_Score": 10,
                "ROIC": 0.1,
                "RevGrowth": 0.1,
                "GrossMargin": 0.1,
                "Expected_Return": 0.1,
                "Last_Updated": _fresh_date(),
            },
        ])
        self.assertEqual(evaluate_dataset(DatasetSpec("US_Final_Portfolio", market="US"), portfolio)["status"], "OK")

        smallcap = pd.DataFrame([
            {
                "Rank": 1,
                "Ticker": "SMCI",
                "Name": "Super Micro",
                "Market": "US",
                "MarketCap": 500_000_000,
                "ROIC": 0.1,
                "RevGrowth": 0.2,
                "Rev_Accel": 0.1,
                "GrossMargin": 0.3,
                "FCF_Margin": 0.1,
                "Debt_EBITDA": 1,
                "Volume_Surge": 2,
                "SmallCap_Bonus": 10,
                "Total_Score": 80,
                "Last_Updated": _fresh_date(),
            },
        ])
        spec = DatasetSpec(
            "US_SmallCap_Gems",
            market="US",
            optional_columns=("Data_Confidence",),
        )
        self.assertEqual(evaluate_dataset(spec, smallcap)["status"], "WARN")


if __name__ == "__main__":
    unittest.main()
