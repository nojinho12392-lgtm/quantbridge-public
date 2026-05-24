import unittest

import pandas as pd

from pipeline.portfolio.risk_controls import (
    PortfolioRiskConfig,
    apply_weight_limits,
    estimate_portfolio_volatility,
    period_cash_return,
    risk_controlled_selection,
    risk_limit_summary,
    volatility_target_scalar,
)


class PortfolioRiskControlTests(unittest.TestCase):
    def test_selection_limits_sector_concentration(self):
        scored = pd.DataFrame(
            {
                "Total_Score": [100, 99, 98, 97, 96, 95, 94, 93],
                "Sector": ["Tech", "Tech", "Tech", "Tech", "Health", "Energy", "Finance", "Industrial"],
                "MarketCap": [10_000_000_000] * 8,
            },
            index=["T1", "T2", "T3", "T4", "H1", "E1", "F1", "I1"],
        )
        config = PortfolioRiskConfig(max_sector_weight=0.4, max_illiquid_weight=1.0)

        selected = risk_controlled_selection(scored, target_n=5, market="US", config=config)

        sectors = scored.loc[selected, "Sector"]
        self.assertLessEqual((sectors == "Tech").sum(), 2)
        self.assertEqual(len(selected), 5)

    def test_selection_keeps_existing_holding_inside_sell_band(self):
        scored = pd.DataFrame(
            {
                "Total_Score": [10, 9, 8, 7, 6, 5],
                "Sector": ["A", "B", "C", "D", "E", "F"],
                "MarketCap": [10_000_000_000] * 6,
            },
            index=["N1", "N2", "N3", "OLD", "N4", "N5"],
        )
        config = PortfolioRiskConfig(sell_rank_multiplier=1.5, max_turnover_fraction=0.34)

        selected = risk_controlled_selection(
            scored,
            target_n=3,
            market="US",
            previous_holdings={"OLD", "LEGACY"},
            config=config,
        )

        self.assertIn("OLD", selected)

    def test_weight_limits_cap_position_sector_and_illiquid_bucket(self):
        weights = pd.Series({"A": 0.30, "B": 0.25, "C": 0.20, "D": 0.15, "E": 0.10})
        metadata = pd.DataFrame(
            {
                "Sector": ["Tech", "Tech", "Tech", "Health", "Energy"],
                "MarketCap": [1_000_000_000, 1_500_000_000, 20_000_000_000, 30_000_000_000, 40_000_000_000],
            },
            index=weights.index,
        )
        config = PortfolioRiskConfig(
            max_position_weight=0.25,
            max_sector_weight=0.50,
            max_illiquid_weight=0.25,
        )

        limited = apply_weight_limits(weights, metadata, market="US", config=config)
        summary = risk_limit_summary(limited, metadata, market="US", config=config)

        self.assertAlmostEqual(limited.sum(), 1.0)
        self.assertLessEqual(summary["max_position"], 0.250001)
        self.assertLessEqual(summary["max_sector"], 0.500001)
        self.assertLessEqual(summary["illiquid_weight"], 0.250001)

    def test_volatility_target_scalar_reduces_exposure_when_vol_is_high(self):
        returns = pd.DataFrame(
            {
                "AAA": [0.01, -0.01] * 20,
                "BBB": [0.008, -0.008] * 20,
            }
        )
        weights = pd.Series({"AAA": 0.5, "BBB": 0.5})

        vol = estimate_portfolio_volatility(returns, weights, min_observations=20)
        scalar = volatility_target_scalar(vol, target_vol=0.05)

        self.assertIsNotNone(vol)
        self.assertLess(scalar, 1.0)
        self.assertAlmostEqual(period_cash_return(0.05, "2026-01-01", "2027-01-01"), 0.05, places=3)


if __name__ == "__main__":
    unittest.main()
