import unittest

import pandas as pd

from pipeline.backtest.research_quality import (
    bootstrap_sharpe_ci,
    offset_sensitivity_table,
    performance_stats,
    split_in_out_sample,
)


class ResearchQualityTests(unittest.TestCase):
    def test_performance_stats_and_split(self):
        returns = pd.Series([0.01, -0.02, 0.03, 0.01, 0.02])

        stats = performance_stats(returns, periods_per_year=52, rf_per_period=0.0)
        ins, oos = split_in_out_sample(returns, train_fraction=0.6)

        self.assertEqual(stats["Periods"], 5)
        self.assertEqual(len(ins), 3)
        self.assertEqual(len(oos), 2)
        self.assertIn("Sharpe", stats)

    def test_bootstrap_sharpe_ci_is_bounded_probability(self):
        result = bootstrap_sharpe_ci(
            pd.Series([0.01, 0.02, -0.01, 0.03, 0.00, 0.01]),
            periods_per_year=52,
            samples=50,
            seed=7,
        )

        self.assertLessEqual(result["Prob_Sharpe_GT_0"], 1.0)
        self.assertGreaterEqual(result["Prob_Sharpe_GT_0"], 0.0)
        self.assertLessEqual(result["Sharpe_CI_Low"], result["Sharpe_CI_High"])

    def test_offset_sensitivity_table_marks_rows(self):
        df = offset_sensitivity_table(
            [
                {"Offset_Days": 0, "CAGR": 0.10, "Sharpe": 1.0, "Max_Drawdown": -0.1, "Periods": 10},
                {"Offset_Days": 1, "CAGR": 0.08, "Sharpe": 0.8, "Max_Drawdown": -0.1, "Periods": 10},
            ]
        )

        self.assertEqual(df["Offset_Days"].tolist(), [0, 1])
        self.assertIn("Robust", df.columns)


if __name__ == "__main__":
    unittest.main()
