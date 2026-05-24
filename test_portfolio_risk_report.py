import unittest

import numpy as np
import pandas as pd

from pipeline.portfolio.risk_report import build_risk_report


class PortfolioRiskReportTests(unittest.TestCase):
    def test_risk_contributions_sum_to_one_and_sector_rolls_up(self):
        tickers = ["AAA", "BBB", "CCC"]
        weights = pd.Series({"AAA": 0.4, "BBB": 0.35, "CCC": 0.25})
        cov = np.array(
            [
                [0.04, 0.01, 0.00],
                [0.01, 0.09, 0.02],
                [0.00, 0.02, 0.16],
            ]
        )
        meta = pd.DataFrame(
            {
                "Name": ["A Co", "B Co", "C Co"],
                "Sector": ["Tech", "Tech", "Health"],
                "Total_Score": [90, 80, 70],
                "MarketCap": [10, 20, 30],
            },
            index=tickers,
        )

        summary, holdings, sectors = build_risk_report(
            market="US",
            tickers=tickers,
            weights=weights,
            cov_matrix=cov,
            metadata=meta,
            target_vol=0.12,
            invested_fraction=1.0,
            cash_weight=0.0,
            generated_at=pd.Timestamp("2026-01-02 09:00:00"),
        )

        self.assertAlmostEqual(holdings["Risk_Contribution_Pct"].sum(), 1.0)
        tech = sectors.set_index("Sector").loc["Tech"]
        self.assertAlmostEqual(
            tech["Sector_Risk_Contribution_Pct"],
            holdings[holdings["Sector"].eq("Tech")]["Risk_Contribution_Pct"].sum(),
        )
        self.assertIn("Effective_Holdings", set(summary["Metric"]))

    def test_high_single_name_risk_is_flagged(self):
        tickers = ["AAA", "BBB"]
        weights = pd.Series({"AAA": 0.8, "BBB": 0.2})
        cov = np.diag([0.25, 0.01])
        meta = pd.DataFrame({"Sector": ["Tech", "Health"]}, index=tickers)

        _, holdings, _ = build_risk_report(
            market="US",
            tickers=tickers,
            weights=weights,
            cov_matrix=cov,
            metadata=meta,
            target_vol=0.12,
            invested_fraction=1.0,
            cash_weight=0.0,
            generated_at=pd.Timestamp("2026-01-02 09:00:00"),
        )

        warning = holdings.set_index("Ticker").loc["AAA", "Warnings"]
        self.assertIn("HIGH_RISK_CONTRIB", warning)


if __name__ == "__main__":
    unittest.main()
