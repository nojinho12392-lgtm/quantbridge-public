import unittest

import pandas as pd

from pipeline.portfolio.rebalance_report import RebalanceConfig, build_rebalance_report


class PortfolioRebalanceReportTests(unittest.TestCase):
    def test_builds_buy_sell_orders_from_actual_holdings(self):
        target = pd.DataFrame(
            {
                "Ticker": ["AAA", "BBB", "CCC"],
                "Name": ["A Co", "B Co", "C Co"],
                "Sector": ["Tech", "Health", "Energy"],
                "Weight(%)": [0.40, 0.30, 0.20],
                "Current_Price": [100.0, 50.0, 25.0],
                "MarketCap": [60_000_000_000, 8_000_000_000, 1_000_000_000],
            }
        )
        current = pd.DataFrame(
            {
                "Ticker": ["AAA", "BBB", "CASH"],
                "Shares": [50, 90, ""],
                "Price": [100.0, 50.0, ""],
                "Cash": ["", "", 500.0],
            }
        )

        summary, orders = build_rebalance_report(
            market="US",
            target_portfolio=target,
            current_holdings=current,
            generated_at=pd.Timestamp("2026-01-02 09:00:00"),
            config=RebalanceConfig(
                rebalance_band=0.005,
                min_trade_value=50.0,
                fee_rate=0.001,
                fractional_shares=False,
            ),
        )

        by_ticker = orders.set_index("Ticker")
        self.assertEqual(by_ticker.loc["AAA", "Action"], "SELL")
        self.assertEqual(by_ticker.loc["BBB", "Action"], "SELL")
        self.assertEqual(by_ticker.loc["CCC", "Action"], "BUY")
        self.assertAlmostEqual(by_ticker.loc["CCC", "Executable_Trade_Value"], 2000.0)
        self.assertEqual(summary["Trade_Count"].iloc[0], 3)
        self.assertGreater(summary["Estimated_Total_Cost"].iloc[0], 0)

    def test_band_and_min_trade_suppress_small_orders(self):
        target = pd.DataFrame(
            {
                "Ticker": ["AAA", "BBB"],
                "Weight(%)": [0.501, 0.499],
                "Current_Price": [100.0, 100.0],
            }
        )
        current = pd.DataFrame(
            {
                "Ticker": ["AAA", "BBB"],
                "Weight(%)": [0.50, 0.50],
            }
        )

        _, orders = build_rebalance_report(
            market="US",
            target_portfolio=target,
            current_holdings=current,
            generated_at=pd.Timestamp("2026-01-02 09:00:00"),
            config=RebalanceConfig(
                portfolio_value=10_000.0,
                rebalance_band=0.005,
                min_trade_value=100.0,
            ),
        )

        self.assertTrue((orders["Action"] == "HOLD").all())
        self.assertEqual(set(orders["Reason"]), {"within_band"})

    def test_previous_portfolio_is_used_when_actual_holdings_are_missing(self):
        target = pd.DataFrame(
            {
                "Ticker": ["AAA", "BBB", "CCC"],
                "Weight(%)": [0.50, 0.20, 0.10],
                "Current_Price": [100.0, 50.0, 20.0],
            }
        )
        previous = pd.DataFrame(
            {
                "Ticker": ["AAA", "BBB"],
                "Weight(%)": [0.40, 0.40],
            }
        )

        summary, orders = build_rebalance_report(
            market="US",
            target_portfolio=target,
            previous_portfolio=previous,
            generated_at=pd.Timestamp("2026-01-02 09:00:00"),
            config=RebalanceConfig(
                portfolio_value=10_000.0,
                rebalance_band=0.001,
                min_trade_value=10.0,
            ),
        )

        by_ticker = orders.set_index("Ticker")
        self.assertEqual(by_ticker.loc["AAA", "Action"], "BUY")
        self.assertEqual(by_ticker.loc["BBB", "Action"], "SELL")
        self.assertEqual(by_ticker.loc["CCC", "Action"], "BUY")
        self.assertAlmostEqual(summary["Current_Cash_Value"].iloc[0], 2000.0)


if __name__ == "__main__":
    unittest.main()
