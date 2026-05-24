import unittest
import os
from pathlib import Path
from tempfile import TemporaryDirectory

import pandas as pd

from pipeline.backtest.kr_pit_financials import (
    asof_pit_features,
    fetch_kr_pit_financials,
    parse_report_available_date,
    parse_dart_annual_financials,
    pit_available_date,
)
from pipeline.scoring.kr_factor_scorer import compute_kr_factor_scores


class KoreanPitBacktestTests(unittest.TestCase):
    def test_pit_available_date_uses_next_april(self):
        self.assertEqual(pit_available_date(2024), pd.Timestamp("2025-04-01"))

    def test_report_available_date_uses_first_matching_annual_filing(self):
        payload = {
            "list": [
                {"report_nm": "[기재정정]사업보고서 (2024.12)", "rcept_dt": "20250402"},
                {"report_nm": "사업보고서 (2024.12)", "rcept_dt": "20250318"},
                {"report_nm": "반기보고서 (2024.06)", "rcept_dt": "20240814"},
            ]
        }

        self.assertEqual(
            parse_report_available_date(payload, fiscal_year=2024),
            pd.Timestamp("2025-03-18"),
        )

    def test_asof_pit_features_uses_only_available_rows(self):
        pit = pd.DataFrame(
            {
                "Ticker": ["005930.KS", "005930.KS"],
                "Fiscal_Year": [2023, 2024],
                "Available_Date": ["2024-04-01", "2025-04-01"],
                "ROE": [0.10, 0.20],
            }
        )

        before = asof_pit_features(pit, pd.Timestamp("2025-03-31"))
        after = asof_pit_features(pit, pd.Timestamp("2025-04-02"))

        self.assertEqual(before.loc["005930.KS", "Fiscal_Year"], 2023)
        self.assertEqual(after.loc["005930.KS", "Fiscal_Year"], 2024)

    def test_parse_dart_annual_financials_minimal_frame(self):
        fs = pd.DataFrame(
            [
                ["CFS", "IS", "매출액", "ifrs-full_Revenue", "1,000", "900"],
                ["CFS", "IS", "영업이익", "dart_OperatingIncomeLoss", "100", "80"],
                ["CFS", "IS", "당기순이익", "ifrs-full_ProfitLoss", "70", "60"],
                ["CFS", "BS", "자산총계", "ifrs-full_Assets", "2,000", ""],
                ["CFS", "BS", "유동자산", "ifrs-full_CurrentAssets", "800", ""],
                ["CFS", "BS", "유동부채", "ifrs-full_CurrentLiabilities", "500", ""],
                ["CFS", "BS", "부채총계", "ifrs-full_Liabilities", "900", ""],
                ["CFS", "BS", "자본총계", "ifrs-full_Equity", "1,100", ""],
                ["CFS", "CF", "영업활동현금흐름", "", "120", ""],
                ["CFS", "CF", "유형자산의 취득", "", "40", ""],
            ],
            columns=["fs_div", "sj_div", "account_nm", "account_id", "thstrm_amount", "frmtrm_amount"],
        )

        row = parse_dart_annual_financials(fs, ticker="005930.KS", fiscal_year=2024)

        self.assertIsNotNone(row)
        self.assertAlmostEqual(row["OperatingMargin"], 0.10)
        self.assertAlmostEqual(row["RevGrowth"], (1000 - 900) / 900)
        self.assertEqual(row["Available_Date"], "2025-04-01")

    def test_kr_factor_scores_do_not_nan_with_sparse_pit_data(self):
        features = pd.DataFrame(
            {
                "ROE": [0.2, 0.05],
                "OperatingMargin": [0.15, 0.03],
                "DebtToEquity": [30, 180],
                "RevGrowth": [0.2, -0.1],
                "Mom_3M": [0.1, -0.05],
            },
            index=["AAA.KS", "BBB.KS"],
        )

        scored = compute_kr_factor_scores(features, mom_series=pd.Series({"AAA.KS": 0.2, "BBB.KS": -0.1}))

        self.assertFalse(scored[["Value_Score", "Quality_Score", "Momentum_Score", "Total_Score"]].isna().any().any())
        self.assertGreater(scored.loc["AAA.KS", "Total_Score"], scored.loc["BBB.KS", "Total_Score"])

    def test_fetch_kr_pit_financials_respects_api_budget(self):
        class FakeDart:
            def __init__(self, key):
                self.corp_codes = pd.DataFrame(
                    [
                        {"stock_code": "005930", "corp_code": "00126380"},
                        {"stock_code": "000660", "corp_code": "00164779"},
                    ]
                )
                self.calls = 0

            def finstate_all(self, corp, year, reprt_code="11011", fs_div="CFS"):
                self.calls += 1
                return pd.DataFrame(
                    [
                        ["CFS", "IS", "매출액", "ifrs-full_Revenue", "1,000", "900"],
                        ["CFS", "IS", "영업이익", "dart_OperatingIncomeLoss", "100", "80"],
                        ["CFS", "BS", "자본총계", "ifrs-full_Equity", "1,100", ""],
                    ],
                    columns=["fs_div", "sj_div", "account_nm", "account_id", "thstrm_amount", "frmtrm_amount"],
                )

        import sys

        original_module = sys.modules.get("OpenDartReader")
        original_key = os.environ.get("DART_API_KEY")
        sys.modules["OpenDartReader"] = FakeDart
        os.environ["DART_API_KEY"] = "test-key"
        try:
            with TemporaryDirectory() as tmp:
                result = fetch_kr_pit_financials(
                    ["005930.KS", "000660.KS"],
                    [2023, 2024],
                    delay=0,
                    cache_path=Path(tmp) / "kr_pit.csv",
                    max_api_calls=1,
                )
        finally:
            if original_module is None:
                sys.modules.pop("OpenDartReader", None)
            else:
                sys.modules["OpenDartReader"] = original_module
            if original_key is None:
                os.environ.pop("DART_API_KEY", None)
            else:
                os.environ["DART_API_KEY"] = original_key

        self.assertEqual(len(result), 1)


if __name__ == "__main__":
    unittest.main()
