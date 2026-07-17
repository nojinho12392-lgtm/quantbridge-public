"""Tests for policy-adjusted shadow rankings."""

from __future__ import annotations

import unittest

import pandas as pd

from tools.build_policy_adjusted_rankings import (
    build_factor_multipliers,
    build_policy_adjusted_ranking,
    summarize_ranking,
)
from tools.run_research_quality import RESEARCH_QUALITY_STEPS


class PolicyAdjustedRankingTests(unittest.TestCase):
    def test_proxy_policy_can_move_momentum_leader_up(self):
        scored = pd.DataFrame([
            {
                "Rank": 1,
                "Ticker": "AAA",
                "Name": "Value Leader",
                "Market": "US",
                "Sector": "Tech",
                "Value_Score": 0.40,
                "Quality_Score": 0.10,
                "Momentum_Score": 0.05,
                "Total_Score": 0.55,
                "Final_Score": 0.90,
                "Score_Neutral": 0.55,
                "Investability_Score": 0.50,
                "Quality_Data_Confidence": 1.0,
                "Quality_Red_Flags": "",
            },
            {
                "Rank": 2,
                "Ticker": "BBB",
                "Name": "Momentum Leader",
                "Market": "US",
                "Sector": "Tech",
                "Value_Score": 0.05,
                "Quality_Score": 0.10,
                "Momentum_Score": 0.30,
                "Total_Score": 0.45,
                "Final_Score": 0.70,
                "Score_Neutral": 0.45,
                "Investability_Score": 0.50,
                "Quality_Data_Confidence": 1.0,
                "Quality_Red_Flags": "",
            },
        ])
        policy = pd.DataFrame([
            {"Market": "US", "Factor": "Value_Score", "Adjustment_Bias": 0.70, "Evidence_Source": "PROXY_ONLY", "Production_Ready": "FALSE", "Current_Action": "observe_only_proxy", "Policy_Status": "REVIEW"},
            {"Market": "US", "Factor": "Quality_Score", "Adjustment_Bias": 1.00, "Evidence_Source": "PROXY_ONLY", "Production_Ready": "FALSE", "Current_Action": "observe_only_proxy", "Policy_Status": "KEEP"},
            {"Market": "US", "Factor": "Momentum_Score", "Adjustment_Bias": 1.00, "Evidence_Source": "PROXY_ONLY", "Production_Ready": "FALSE", "Current_Action": "observe_only_proxy", "Policy_Status": "KEEP"},
        ])

        ranking = build_policy_adjusted_ranking(scored, policy, "US", mode="proxy", generated="2026-05-25T00:00:00")

        self.assertEqual(ranking.iloc[0]["Ticker"], "BBB")
        self.assertEqual(int(ranking.iloc[0]["Rank_Change"]), 1)
        self.assertEqual(float(ranking.iloc[0]["Value_Multiplier"]), 0.7)
        self.assertEqual(ranking.iloc[0]["Policy_Mode"], "proxy_observation")

    def test_production_mode_ignores_non_ready_proxy_rows(self):
        policy = pd.DataFrame([
            {"Market": "KR", "Factor": "Value_Score", "Adjustment_Bias": 0.70, "Production_Ready": "FALSE"},
            {"Market": "KR", "Factor": "Quality_Score", "Adjustment_Bias": 0.90, "Production_Ready": "TRUE"},
        ])

        multipliers, details = build_factor_multipliers(policy, "KR", mode="production")

        self.assertEqual(multipliers["Value_Score"], 1.0)
        self.assertEqual(multipliers["Quality_Score"], 0.9)
        self.assertFalse(details["Value_Score"]["Production_Ready"])
        self.assertTrue(details["Quality_Score"]["Production_Ready"])

    def test_summary_reports_top_movers(self):
        ranking = pd.DataFrame([
            {"Ticker": "AAA", "Name": "A", "Market": "US", "Policy_Rank": 1, "Base_Rank": 3, "Rank_Change": 2, "Value_Multiplier": 0.7, "Quality_Multiplier": 1.0, "Momentum_Multiplier": 1.0},
            {"Ticker": "BBB", "Name": "B", "Market": "US", "Policy_Rank": 2, "Base_Rank": 1, "Rank_Change": -1, "Value_Multiplier": 0.7, "Quality_Multiplier": 1.0, "Momentum_Multiplier": 1.0},
        ])

        summary = summarize_ranking(ranking, "US", mode="proxy", generated="2026-05-25T00:00:00")

        self.assertEqual(summary["Top_Up_Ticker"], "AAA")
        self.assertEqual(summary["Top_Down_Ticker"], "BBB")
        self.assertEqual(summary["Positive_Movers"], 1)
        self.assertEqual(summary["Negative_Movers"], 1)

    def test_research_quality_job_builds_shadow_rankings_before_remediation(self):
        scripts = [step.script for step in RESEARCH_QUALITY_STEPS]

        self.assertIn("tools/build_policy_adjusted_rankings.py", scripts)
        self.assertLess(
            scripts.index("pipeline/17_factor_policy_backtest.py"),
            scripts.index("tools/build_policy_adjusted_rankings.py"),
        )
        self.assertLess(
            scripts.index("tools/build_policy_adjusted_rankings.py"),
            scripts.index("pipeline/18_factor_remediation_plan.py"),
        )


if __name__ == "__main__":
    unittest.main()
