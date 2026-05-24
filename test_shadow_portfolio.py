import unittest

import pandas as pd

from pipeline.backtest.shadow_portfolio import (
    EVALUATION_KEY,
    SNAPSHOT_KEY,
    attribute_snapshots,
    evaluate_snapshots,
    merge_by_key,
    normalize_portfolio_snapshot,
    summarize_evaluations,
)


class ShadowPortfolioTests(unittest.TestCase):
    def test_snapshot_normalizes_weights_and_dedupes_by_date_market_ticker(self):
        portfolio = pd.DataFrame(
            {
                "Ticker": ["AAA", "BBB"],
                "Name": ["A Co", "B Co"],
                "Weight(%)": ["60%", "40%"],
                "Total_Score": ["90", "70"],
            }
        )

        snapshot = normalize_portfolio_snapshot(
            portfolio,
            market="US",
            source_sheet="US_Final_Portfolio",
            benchmark_ticker="SPY",
            snapshot_date="2026-01-02",
            price_map={"AAA": 10.0, "BBB": 20.0, "SPY": 100.0},
        )
        merged = merge_by_key(pd.concat([snapshot, snapshot], ignore_index=True), snapshot, SNAPSHOT_KEY)

        self.assertEqual(len(snapshot), 2)
        self.assertAlmostEqual(snapshot["Weight"].sum(), 1.0)
        self.assertAlmostEqual(snapshot["Sleeve_Weight"].sum(), 1.0)
        self.assertEqual(len(merged), 2)
        self.assertEqual(snapshot["Benchmark_Price"].iloc[0], 100.0)

    def test_evaluate_snapshots_computes_forward_returns_and_alpha(self):
        portfolio = pd.DataFrame(
            {
                "Ticker": ["AAA", "BBB", "CCC"],
                "Weight(%)": [0.5, 0.3, 0.2],
                "Total_Score": [3.0, 2.0, 1.0],
            }
        )
        snapshots = normalize_portfolio_snapshot(
            portfolio,
            market="US",
            source_sheet="US_Final_Portfolio",
            benchmark_ticker="SPY",
            snapshot_date="2026-01-02",
        )
        prices = pd.DataFrame(
            {
                "AAA": [100.0, 110.0],
                "BBB": [100.0, 90.0],
                "CCC": [100.0, 120.0],
                "SPY": [100.0, 105.0],
            },
            index=pd.to_datetime(["2026-01-02", "2026-02-02"]),
        )

        evaluated = evaluate_snapshots(
            snapshots,
            prices,
            as_of_date="2026-02-03",
            horizons={"1M": 21},
            generated_at=pd.Timestamp("2026-02-03 09:00:00"),
        )

        self.assertEqual(len(evaluated), 1)
        row = evaluated.iloc[0]
        self.assertEqual(row["Horizon"], "1M")
        self.assertAlmostEqual(row["Benchmark_Return"], 0.05)
        self.assertAlmostEqual(row["Equal_Weight_Return"], round((0.10 - 0.10 + 0.20) / 3, 6))
        self.assertAlmostEqual(row["Actual_Weight_Return"], 0.06)
        self.assertAlmostEqual(row["Alpha_Actual"], 0.01)
        self.assertGreater(row["Hit_Rate"], 0.0)

    def test_summary_and_evaluation_deduplication(self):
        evaluations = pd.DataFrame(
            [
                {
                    "Snapshot_Date": "2026-01-02",
                    "Market": "US",
                    "Horizon": "1M",
                    "Evaluation_Date": "2026-02-02",
                    "Coverage": 1.0,
                    "Equal_Weight_Return": 0.10,
                    "Sleeve_Weight_Return": 0.08,
                    "Actual_Weight_Return": 0.06,
                    "Benchmark_Return": 0.04,
                    "Alpha_Equal": 0.06,
                    "Alpha_Sleeve": 0.04,
                    "Alpha_Actual": 0.02,
                    "Hit_Rate": 0.7,
                    "Positive_Rate": 0.8,
                    "Score_IC": 0.2,
                },
                {
                    "Snapshot_Date": "2026-01-03",
                    "Market": "US",
                    "Horizon": "1M",
                    "Evaluation_Date": "2026-02-03",
                    "Coverage": 0.8,
                    "Equal_Weight_Return": 0.00,
                    "Sleeve_Weight_Return": 0.01,
                    "Actual_Weight_Return": 0.01,
                    "Benchmark_Return": 0.02,
                    "Alpha_Equal": -0.02,
                    "Alpha_Sleeve": -0.01,
                    "Alpha_Actual": -0.01,
                    "Hit_Rate": 0.4,
                    "Positive_Rate": 0.5,
                    "Score_IC": -0.1,
                },
            ]
        )
        merged = merge_by_key(evaluations.iloc[[0]], evaluations, EVALUATION_KEY)
        summary = summarize_evaluations(merged, generated_at=pd.Timestamp("2026-02-01 09:00:00"))

        self.assertEqual(len(merged), 2)
        self.assertEqual(len(summary), 1)
        self.assertEqual(summary["Evaluations"].iloc[0], 2)
        self.assertAlmostEqual(summary["Mean_Alpha_Actual"].iloc[0], 0.005)
        self.assertAlmostEqual(summary["Median_Alpha_Actual"].iloc[0], 0.005)
        self.assertAlmostEqual(summary["Alpha_Actual_Win_Rate"].iloc[0], 0.5)
        self.assertAlmostEqual(summary["Mean_Coverage"].iloc[0], 0.9)
        self.assertEqual(summary["Latest_Evaluation_Date"].iloc[0], "2026-02-03")

    def test_shadow_attribution_breaks_down_stock_and_sector_contribution(self):
        portfolio = pd.DataFrame(
            {
                "Ticker": ["AAA", "BBB", "CCC"],
                "Name": ["A Co", "B Co", "C Co"],
                "Sector": ["Tech", "Tech", "Health"],
                "Weight(%)": [0.5, 0.3, 0.2],
                "Total_Score": [3.0, 2.0, 1.0],
            }
        )
        snapshots = normalize_portfolio_snapshot(
            portfolio,
            market="US",
            source_sheet="US_Final_Portfolio",
            benchmark_ticker="SPY",
            snapshot_date="2026-01-02",
        )
        prices = pd.DataFrame(
            {
                "AAA": [100.0, 110.0],
                "BBB": [100.0, 90.0],
                "CCC": [100.0, 120.0],
                "SPY": [100.0, 105.0],
            },
            index=pd.to_datetime(["2026-01-02", "2026-02-02"]),
        )

        detail, sectors, summary = attribute_snapshots(
            snapshots,
            prices,
            as_of_date="2026-02-03",
            horizons={"1M": 21},
            generated_at=pd.Timestamp("2026-02-03 09:00:00"),
        )

        self.assertEqual(len(detail), 3)
        self.assertEqual(len(sectors), 2)
        self.assertEqual(len(summary), 1)
        self.assertAlmostEqual(summary["Actual_Return"].iloc[0], 0.06)
        self.assertAlmostEqual(summary["Benchmark_Return"].iloc[0], 0.05)
        self.assertAlmostEqual(summary["Alpha_Actual"].iloc[0], 0.01)
        self.assertEqual(summary["Top_Contributor"].iloc[0], "AAA")
        self.assertEqual(summary["Worst_Contributor"].iloc[0], "BBB")

        sector_map = sectors.set_index("Sector")
        self.assertAlmostEqual(sector_map.loc["Tech", "Actual_Contribution"], 0.02)
        self.assertAlmostEqual(sector_map.loc["Health", "Actual_Contribution"], 0.04)


if __name__ == "__main__":
    unittest.main()
