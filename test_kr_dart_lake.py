import tempfile
import unittest
from pathlib import Path

import pandas as pd

from pipeline.data.kr_dart_lake import (
    build_kr_quality_history_features,
    normalize_kr_tickers,
    read_latest_quality_history_features,
    write_kr_dart_lake,
)


def _sample_pit():
    return pd.DataFrame(
        {
            "Ticker": ["005930.KS"] * 5,
            "Fiscal_Year": [2020, 2021, 2022, 2023, 2024],
            "Available_Date": ["2021-04-01", "2022-04-01", "2023-04-01", "2024-04-01", "2025-03-18"],
            "Revenue": [100, 115, 130, 150, 175],
            "ROIC": [0.12, 0.14, 0.13, 0.16, 0.18],
            "ROE": [0.10, 0.12, 0.11, 0.15, 0.17],
            "OperatingMargin": [0.10, 0.11, 0.10, 0.12, 0.13],
            "GrossMargin": [0.35, 0.36, 0.35, 0.37, 0.38],
            "FCF_Margin": [0.08, 0.09, 0.07, 0.10, 0.11],
            "DebtToEquity": [80, 70, 65, 55, 50],
            "Debt_EBITDA": [2.4, 2.1, 1.8, 1.5, 1.2],
        }
    )


class KrDartLakeTests(unittest.TestCase):
    def test_normalize_kr_tickers(self):
        self.assertEqual(
            normalize_kr_tickers(["5930", "005930.KS", "000660.KQ"]),
            ["005930.KS", "000660.KQ"],
        )

    def test_build_kr_quality_history_features(self):
        features = build_kr_quality_history_features(_sample_pit(), as_of="2025-05-01")

        self.assertEqual(len(features), 1)
        row = features.iloc[0]
        self.assertEqual(row["Ticker"], "005930.KS")
        self.assertEqual(row["History_Years"], 5)
        self.assertGreater(row["Revenue_CAGR_5Y"], 0)
        self.assertEqual(row["FCF_Positive_Years_5Y"], 1.0)
        self.assertTrue(0 <= row["Quality_Persistence_Score"] <= 1)
        self.assertEqual(row["Source"], "OPENDART")

    def test_write_and_read_latest_features(self):
        pit = _sample_pit()
        features = build_kr_quality_history_features(pit)
        with tempfile.TemporaryDirectory() as tmp:
            write_kr_dart_lake(pit, features, output_dir=tmp, snapshot_date="2026-01-01")
            loaded = read_latest_quality_history_features(output_dir=tmp)

        self.assertEqual(len(loaded), 1)
        self.assertEqual(loaded.iloc[0]["Ticker"], "005930.KS")


if __name__ == "__main__":
    unittest.main()
