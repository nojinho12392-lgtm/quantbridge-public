import unittest

import pandas as pd

from pipeline.scoring.common_factor_scorer import compute_us_factor_scores


class CommonFactorScorerTests(unittest.TestCase):
    def test_live_and_backtest_frames_use_same_scoring_function(self):
        live = pd.DataFrame(
            {
                "Ticker": ["AAA", "BBB", "CCC"],
                "PEG": [0.8, 2.0, 1.2],
                "EV_EBITDA": [9.0, 16.0, 11.0],
                "RevGrowth": [0.30, 0.05, 0.15],
                "DivYield": [0.01, 0.00, 0.02],
                "ROIC": [0.25, 0.08, 0.16],
                "FCF_NI": [0.9, 0.4, 0.7],
                "ROE": [0.30, 0.09, 0.18],
                "InterestCoverage": [20.0, 3.0, 9.0],
                "FCF_Margin": [0.18, 0.03, 0.10],
                "OperatingMargin": [0.25, 0.06, 0.15],
                "Mom_12M": [0.40, 0.05, 0.20],
                "Mom_1M": [0.03, 0.01, 0.02],
                "Mom_3M": [0.12, 0.02, 0.08],
                "EPS_Growth": [0.25, -0.05, 0.10],
            }
        ).set_index("Ticker")

        backtest = pd.DataFrame(
            {
                "Ticker": ["AAA", "BBB", "CCC"],
                "ROIC": [0.25, 0.08, 0.16],
                "ROE": [0.30, 0.09, 0.18],
                "FCF_NI": [0.9, 0.4, 0.7],
                "FCF_Margin": [0.18, 0.03, 0.10],
                "OperatingMargin": [0.25, 0.06, 0.15],
                "InterestCoverage": [20.0, 3.0, 9.0],
                "RevGrowth": [0.30, 0.05, 0.15],
                "DebtToEquity": [0.3, 1.8, 0.7],
                "EPS_Growth": [0.25, -0.05, 0.10],
            }
        ).set_index("Ticker")

        live_scored = compute_us_factor_scores(live, weights=(0.4, 0.35, 0.25))
        bt_scored = compute_us_factor_scores(
            backtest,
            weights=(0.4, 0.35, 0.25),
            mom_series=pd.Series({"AAA": 0.37, "BBB": 0.04, "CCC": 0.18}),
        )

        for scored in (live_scored, bt_scored):
            self.assertIn("Total_Score", scored.columns)
            self.assertTrue(scored["Total_Score"].between(0, 1).all())
            self.assertGreater(scored.loc["AAA", "Total_Score"], scored.loc["BBB", "Total_Score"])

    def test_missing_inputs_are_neutral_not_nan(self):
        df = pd.DataFrame({"Ticker": ["AAA", "BBB"], "ROIC": [0.2, 0.1]}).set_index("Ticker")

        scored = compute_us_factor_scores(df)

        self.assertFalse(scored[["Value_Score", "Quality_Score", "Momentum_Score", "Total_Score"]].isna().any().any())


if __name__ == "__main__":
    unittest.main()
