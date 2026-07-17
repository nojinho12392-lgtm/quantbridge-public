import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import pandas as pd

from pipeline.data.sec_companyfacts_lake import (
    build_quality_history_features,
    build_sec_pit_frame,
    companyfacts_to_pit_rows,
    iter_companyfacts_zip,
)


def _annual_row(filed, value):
    fiscal_year = int(str(filed)[:4]) - 1
    return {
        "form": "10-K",
        "filed": filed,
        "start": f"{fiscal_year}-01-01",
        "end": f"{fiscal_year}-12-31",
        "val": value,
    }


def _fact(concept, unit, rows):
    return {concept: {"units": {unit: rows}}}


def _sample_company_facts():
    gaap = {}
    for concept in [
        _fact("Revenues", "USD", [_annual_row("2021-02-15", 100), _annual_row("2022-02-15", 120), _annual_row("2023-02-15", 150), _annual_row("2024-02-15", 180), _annual_row("2025-02-15", 220)]),
        _fact("GrossProfit", "USD", [_annual_row("2021-02-15", 60), _annual_row("2022-02-15", 72), _annual_row("2023-02-15", 90), _annual_row("2024-02-15", 108), _annual_row("2025-02-15", 132)]),
        _fact("OperatingIncomeLoss", "USD", [_annual_row("2021-02-15", 20), _annual_row("2022-02-15", 25), _annual_row("2023-02-15", 32), _annual_row("2024-02-15", 40), _annual_row("2025-02-15", 50)]),
        _fact("NetIncomeLoss", "USD", [_annual_row("2021-02-15", 15), _annual_row("2022-02-15", 19), _annual_row("2023-02-15", 25), _annual_row("2024-02-15", 30), _annual_row("2025-02-15", 38)]),
        _fact("StockholdersEquity", "USD", [{"form": "10-K", "filed": "2021-02-15", "val": 100}, {"form": "10-K", "filed": "2022-02-15", "val": 110}, {"form": "10-K", "filed": "2023-02-15", "val": 120}, {"form": "10-K", "filed": "2024-02-15", "val": 130}, {"form": "10-K", "filed": "2025-02-15", "val": 145}]),
        _fact("Assets", "USD", [{"form": "10-K", "filed": "2025-02-15", "val": 300}]),
        _fact("NetCashProvidedByUsedInOperatingActivities", "USD", [_annual_row("2021-02-15", 24), _annual_row("2022-02-15", 31), _annual_row("2023-02-15", 37), _annual_row("2024-02-15", 46), _annual_row("2025-02-15", 58)]),
        _fact("PaymentsToAcquirePropertyPlantAndEquipment", "USD", [_annual_row("2021-02-15", 8), _annual_row("2022-02-15", 9), _annual_row("2023-02-15", 10), _annual_row("2024-02-15", 12), _annual_row("2025-02-15", 14)]),
        _fact("LongTermDebt", "USD", [{"form": "10-K", "filed": "2021-02-15", "val": 50}, {"form": "10-K", "filed": "2025-02-15", "val": 30}]),
        _fact("InterestExpense", "USD", [_annual_row("2025-02-15", 3)]),
    ]:
        gaap.update(concept)
    return {"facts": {"us-gaap": gaap}}


class SecCompanyFactsLakeTests(unittest.TestCase):
    def test_companyfacts_to_pit_rows_uses_filing_dates(self):
        rows = companyfacts_to_pit_rows("AAA", "1", _sample_company_facts(), min_filing_year=2021)

        self.assertGreaterEqual(len(rows), 5)
        self.assertEqual(rows[-1]["Ticker"], "AAA")
        self.assertEqual(rows[-1]["CIK"], "0000000001")
        self.assertAlmostEqual(rows[-1]["Revenue"], 220)
        self.assertGreater(rows[-1]["ROIC"], 0)

    def test_build_quality_history_features(self):
        pit = build_sec_pit_frame(
            {"AAA": _sample_company_facts()},
            cik_by_ticker={"AAA": "1"},
        )

        features = build_quality_history_features(pit, as_of=pd.Timestamp("2025-03-01"))

        self.assertEqual(len(features), 1)
        row = features.iloc[0]
        self.assertEqual(row["Ticker"], "AAA")
        self.assertGreaterEqual(row["History_Years"], 5)
        self.assertGreater(row["Revenue_CAGR_5Y"], 0)
        self.assertEqual(row["FCF_Positive_Years_5Y"], 1.0)
        self.assertTrue(0 <= row["Quality_Persistence_Score"] <= 1)

    def test_iter_companyfacts_zip_filters_by_cik(self):
        payload = _sample_company_facts()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "companyfacts.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("CIK0000000001.json", json.dumps(payload))
                archive.writestr("CIK0000000002.json", json.dumps(payload))

            rows = list(iter_companyfacts_zip(path, ticker_by_cik={"0000000002": "BBB"}))

        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][0], "BBB")
        self.assertEqual(rows[0][1], "0000000002")


if __name__ == "__main__":
    unittest.main()
