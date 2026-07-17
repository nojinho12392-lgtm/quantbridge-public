import unittest

import pandas as pd

from pipeline.scoring.company_quality_report import (
    QualityReportConfig,
    build_company_quality_report,
    quality_category,
)


class CompanyQualityReportTests(unittest.TestCase):
    def test_report_ranks_and_categories_candidates(self):
        df = pd.DataFrame(
            [
                {
                    "Rank": 1,
                    "Ticker": "AAA",
                    "Name": "Alpha",
                    "Market": "US",
                    "Sector": "Tech",
                    "Business_Quality_Score": 0.82,
                    "Investability_Score": 0.76,
                    "Valuation_Discipline": 0.62,
                    "Quality_Data_Confidence": 0.91,
                    "Quality_Red_Flags": "",
                },
                {
                    "Rank": 2,
                    "Ticker": "BBB",
                    "Name": "Beta",
                    "Market": "US",
                    "Sector": "Energy",
                    "Business_Quality_Score": 0.74,
                    "Investability_Score": 0.68,
                    "Valuation_Discipline": 0.58,
                    "Quality_Data_Confidence": 0.88,
                    "Quality_Red_Flags": "HIGH_DEBT_EBITDA",
                },
                {
                    "Rank": 10,
                    "Ticker": "CCC",
                    "Name": "Gamma",
                    "Market": "US",
                    "Sector": "Industrials",
                    "Business_Quality_Score": 0.70,
                    "Investability_Score": 0.69,
                    "Valuation_Discipline": 0.60,
                    "Quality_Data_Confidence": 0.90,
                    "Quality_Red_Flags": "",
                },
            ]
        )

        report = build_company_quality_report(df, config=QualityReportConfig(limit=10))

        self.assertEqual(report.loc[0, "Ticker"], "AAA")
        self.assertEqual(report.loc[0, "Quality_Category"], "Core Compounder")
        self.assertEqual(
            report.loc[report["Ticker"] == "BBB", "Quality_Category"].iloc[0],
            "Risk Review",
        )
        self.assertEqual(
            report.loc[report["Ticker"] == "CCC", "Quality_Category"].iloc[0],
            "QARP Candidate",
        )
        self.assertEqual(
            int(report.loc[report["Ticker"] == "CCC", "Quality_Rank_Delta"].iloc[0]),
            8,
        )

    def test_missing_quality_columns_are_computed(self):
        df = pd.DataFrame(
            {
                "ticker": ["GOOD", "BAD"],
                "market": ["US", "US"],
                "sector": ["Tech", "Tech"],
                "ROIC": [0.25, -0.10],
                "FCF_Margin": [0.18, -0.15],
                "Debt_EBITDA": [1.0, 7.0],
            }
        )

        report = build_company_quality_report(df, config=QualityReportConfig(limit=2))

        self.assertFalse(report["Business_Quality_Score"].isna().any())
        self.assertIn("Risk Review", set(report["Quality_Category"]))
        self.assertIn("GOOD", set(report["Ticker"]))

    def test_quality_category_low_confidence_wins(self):
        row = pd.Series(
            {
                "Business_Quality_Score": 0.9,
                "Investability_Score": 0.9,
                "Valuation_Discipline": 0.9,
                "Quality_Data_Confidence": 0.1,
                "Quality_Red_Flags": "",
            }
        )

        self.assertEqual(quality_category(row), "Data Gap")


if __name__ == "__main__":
    unittest.main()
