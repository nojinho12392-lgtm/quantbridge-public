import unittest

import pandas as pd

from pipeline.scoring.company_quality import (
    QUALITY_REVIEW_COLS,
    QUALITY_SCORE_COLS,
    CompanyQualityConfig,
    add_company_quality_review_columns,
    compute_company_quality_scores,
    quality_adjusted_final_score,
)


class CompanyQualityScorerTests(unittest.TestCase):
    def test_good_business_scores_above_flagged_business(self):
        df = pd.DataFrame(
            {
                "Ticker": ["GOOD", "MID", "BAD"],
                "Sector": ["Tech", "Tech", "Tech"],
                "ROIC": [0.35, 0.14, -0.08],
                "ROE": [0.40, 0.16, -0.20],
                "GrossMargin": [0.72, 0.42, 0.18],
                "OperatingMargin": [0.30, 0.12, -0.06],
                "FCF_Margin": [0.22, 0.08, -0.12],
                "FCF_NI": [1.20, 0.70, -0.40],
                "RevGrowth": [0.26, 0.08, -0.14],
                "EPS_Growth": [0.28, 0.05, -0.20],
                "RevAccel": [0.06, 0.01, -0.04],
                "Debt_EBITDA": [0.5, 2.2, 8.0],
                "DebtToEquity": [25.0, 110.0, 450.0],
                "InterestCoverage": [30.0, 7.0, 1.0],
                "AltmanZ": [6.0, 3.0, 1.2],
                "PEG": [1.1, 2.0, 4.2],
                "EV_EBITDA": [12.0, 18.0, 42.0],
                "PER": [24.0, 18.0, 55.0],
                "PBR": [6.0, 2.5, 12.0],
                "DivYield": [0.01, 0.02, 0.0],
                "Mom_12M_1M": [0.25, 0.08, -0.30],
                "Mom_3M": [0.06, 0.01, -0.12],
                "r_analyst": [0.9, 0.5, 0.1],
            }
        ).set_index("Ticker")

        scored = compute_company_quality_scores(
            df,
            config=CompanyQualityConfig(min_sector_size=2),
        )

        for col in QUALITY_SCORE_COLS:
            self.assertIn(col, scored.columns)
        for col in [c for c in QUALITY_SCORE_COLS if c != "Quality_Red_Flags"]:
            self.assertTrue(scored[col].between(0, 1).all(), col)

        self.assertGreater(
            scored.loc["GOOD", "Business_Quality_Score"],
            scored.loc["BAD", "Business_Quality_Score"],
        )
        self.assertGreater(
            scored.loc["GOOD", "Investability_Score"],
            scored.loc["BAD", "Investability_Score"],
        )
        self.assertIn("NEGATIVE_ROIC", scored.loc["BAD", "Quality_Red_Flags"])
        self.assertIn("HIGH_DEBT_EBITDA", scored.loc["BAD", "Quality_Red_Flags"])
        self.assertIn("DISTRESS_RISK", scored.loc["BAD", "Quality_Red_Flags"])

    def test_missing_inputs_are_neutral_but_lower_confidence(self):
        df = pd.DataFrame(
            {
                "Ticker": ["AAA", "BBB"],
                "Sector": ["Software", "Software"],
                "ROIC": [0.2, 0.1],
            }
        ).set_index("Ticker")

        scored = compute_company_quality_scores(df)

        numeric_cols = [c for c in QUALITY_SCORE_COLS if c != "Quality_Red_Flags"]
        self.assertFalse(scored[numeric_cols].isna().any().any())
        self.assertTrue(scored[numeric_cols].stack().between(0, 1).all())
        self.assertLess(scored["Quality_Data_Confidence"].max(), 0.5)
        self.assertTrue(
            scored["Quality_Red_Flags"].str.contains("LOW_DATA_CONFIDENCE").all()
        )

    def test_nonpositive_valuation_multiples_are_penalized_not_treated_neutral(self):
        df = pd.DataFrame(
            {
                "Ticker": ["CHEAP_PROFIT", "LOSSMAKER", "EXPENSIVE_PROFIT"],
                "Sector": ["Software", "Software", "Software"],
                "ROIC": [0.20, 0.18, 0.16],
                "ROE": [0.22, 0.19, 0.17],
                "GrossMargin": [0.70, 0.68, 0.66],
                "OperatingMargin": [0.26, 0.24, 0.22],
                "FCF_Margin": [0.18, 0.16, 0.14],
                "FCF_NI": [1.10, 1.05, 1.00],
                "RevGrowth": [0.16, 0.15, 0.14],
                "EPS_Growth": [0.14, 0.13, 0.12],
                "Debt_EBITDA": [1.0, 1.2, 1.4],
                "DebtToEquity": [20.0, 25.0, 30.0],
                "InterestCoverage": [18.0, 16.0, 14.0],
                "AltmanZ": [5.0, 4.8, 4.6],
                "PEG": [1.0, -2.0, 2.5],
                "EV_EBITDA": [10.0, -5.0, 24.0],
                "PER": [18.0, -12.0, 38.0],
                "PBR": [3.0, -1.0, 7.0],
                "DivYield": [0.02, 0.00, 0.01],
            }
        ).set_index("Ticker")

        scored = compute_company_quality_scores(df)

        self.assertLess(
            scored.loc["LOSSMAKER", "Valuation_Discipline"],
            scored.loc["EXPENSIVE_PROFIT", "Valuation_Discipline"],
        )
        flags = scored.loc["LOSSMAKER", "Quality_Red_Flags"]
        self.assertIn("NEGATIVE_EARNINGS", flags)
        self.assertIn("NEGATIVE_BOOK_EQUITY", flags)
        self.assertIn("NEGATIVE_EBITDA", flags)

    def test_review_columns_rank_and_categorize(self):
        scored = pd.DataFrame(
            {
                "Rank": [1, 2, 10],
                "Ticker": ["AAA", "BBB", "CCC"],
                "Business_Quality_Score": [0.80, 0.74, 0.68],
                "Investability_Score": [0.72, 0.62, 0.70],
                "Valuation_Discipline": [0.65, 0.40, 0.66],
                "Quality_Data_Confidence": [0.90, 0.90, 0.90],
                "Quality_Red_Flags": ["", "HIGH_DEBT_EBITDA", ""],
            }
        )

        reviewed = add_company_quality_review_columns(scored)

        for col in QUALITY_REVIEW_COLS:
            self.assertIn(col, reviewed.columns)
        self.assertEqual(reviewed.loc[0, "Quality_Category"], "Core Compounder")
        self.assertEqual(reviewed.loc[1, "Quality_Category"], "Risk Review")
        self.assertEqual(reviewed.loc[2, "Investability_Rank"], 2)
        self.assertEqual(reviewed.loc[2, "Quality_Rank_Delta"], 8)

    def test_quality_adjusted_final_score_rewards_investability_and_penalizes_severe_flags(self):
        scored = pd.DataFrame(
            {
                "Ticker": ["RAW_ONLY", "QUALITY"],
                "Total_Score": [0.90, 0.80],
                "Score_Neutral": [0.20, 0.90],
                "Investability_Score": [0.25, 0.90],
                "Quality_Data_Confidence": [0.95, 0.95],
                "Quality_Red_Flags": ["", ""],
            }
        ).set_index("Ticker")

        final = quality_adjusted_final_score(scored)

        self.assertGreater(final.loc["QUALITY"], final.loc["RAW_ONLY"])

        flagged = scored.copy()
        flagged.loc["QUALITY", "Quality_Red_Flags"] = "HIGH_DEBT_EBITDA|DISTRESS_RISK"
        flagged_final = quality_adjusted_final_score(flagged)

        self.assertLess(flagged_final.loc["QUALITY"], final.loc["QUALITY"])
        self.assertGreaterEqual(final.loc["QUALITY"] - flagged_final.loc["QUALITY"], 0.06)

    def test_quality_adjusted_final_score_caps_low_confidence_and_severe_flags(self):
        base = {
            "Total_Score": [0.95],
            "Score_Neutral": [0.95],
            "Investability_Score": [0.95],
        }
        clean = pd.DataFrame(
            {**base, "Quality_Data_Confidence": [0.95], "Quality_Red_Flags": [""]},
            index=["CLEAN"],
        )
        low_confidence = pd.DataFrame(
            {**base, "Quality_Data_Confidence": [0.20], "Quality_Red_Flags": [""]},
            index=["LOW_CONF"],
        )
        severe = pd.DataFrame(
            {
                **base,
                "Quality_Data_Confidence": [0.95],
                "Quality_Red_Flags": ["NEGATIVE_BOOK_EQUITY"],
            },
            index=["SEVERE"],
        )

        clean_final = quality_adjusted_final_score(clean)
        low_confidence_final = quality_adjusted_final_score(low_confidence)
        severe_final = quality_adjusted_final_score(severe)

        self.assertGreater(clean_final.loc["CLEAN"], 0.72)
        self.assertLessEqual(low_confidence_final.loc["LOW_CONF"], 0.52)
        self.assertLessEqual(severe_final.loc["SEVERE"], 0.72)
        self.assertLess(severe_final.loc["SEVERE"], clean_final.loc["CLEAN"])


if __name__ == "__main__":
    unittest.main()
