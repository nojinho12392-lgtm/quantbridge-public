import unittest

import pandas as pd

from pipeline.research.quality_signal_validation import (
    QualityValidationConfig,
    forward_returns,
    validate_quality_signals,
)


class QualitySignalValidationTests(unittest.TestCase):
    def test_forward_returns_uses_next_available_prices(self):
        prices = pd.DataFrame(
            {
                "AAA": [100, 110],
                "BBB": [100, 90],
            },
            index=pd.to_datetime(["2024-01-02", "2024-02-05"]),
        )

        returns, start, end = forward_returns(prices, "2024-01-01", 21)

        self.assertEqual(start, "2024-01-02")
        self.assertEqual(end, "2024-02-05")
        self.assertAlmostEqual(returns["AAA"], 0.10)
        self.assertAlmostEqual(returns["BBB"], -0.10)

    def test_validate_quality_signals_detects_positive_rank_ic(self):
        tickers = [f"T{i:02d}" for i in range(30)]
        snapshots = pd.DataFrame(
            {
                "Snapshot_Date": ["2024-01-02"] * len(tickers),
                "Market": ["US"] * len(tickers),
                "Ticker": tickers,
                "Persistence_Quality": [i / 29 for i in range(30)],
                "Business_Quality_Score": [i / 29 for i in range(30)],
            }
        )
        start_prices = pd.Series(100.0, index=tickers)
        end_prices = pd.Series({ticker: 100.0 * (1.0 + i / 100.0) for i, ticker in enumerate(tickers)})
        prices = pd.DataFrame(
            [start_prices, end_prices],
            index=pd.to_datetime(["2024-01-02", "2024-02-05"]),
        )

        summary, detail = validate_quality_signals(
            snapshots,
            prices,
            signal_cols=["Persistence_Quality", "Business_Quality_Score"],
            config=QualityValidationConfig(horizons={"1M": 21}, min_obs=20),
        )

        self.assertEqual(len(detail), 2)
        self.assertTrue((summary["Mean_IC"] > 0.99).all())
        self.assertTrue((summary["Mean_Top_Bottom_Spread"] > 0).all())
        self.assertTrue(set(summary["Verdict"]).issubset({"STRONG"}))


if __name__ == "__main__":
    unittest.main()
