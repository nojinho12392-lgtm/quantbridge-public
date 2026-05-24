import unittest

import pandas as pd

from pipeline.backtest.costs import equal_weight_turnover_costs, liquidity_slippage_rate, weighted_turnover_costs
from pipeline.backtest.kr_market_data import asof_krx_liquidity, load_krx_liquidity_history
from pipeline.data.fred import fetch_fred_series


class FakeKrxProvider:
    @staticmethod
    def get_market_cap_by_date(start, end, code):
        idx = pd.to_datetime(["2024-01-02", "2024-01-03"])
        return pd.DataFrame(
            {
                "시가총액": [1_000_000_000_000, 1_100_000_000_000],
                "거래대금": [300_000_000, 500_000_000],
                "거래량": [100_000, 120_000],
            },
            index=idx,
        )


class FreeDataEnhancementTests(unittest.TestCase):
    def test_fred_missing_key_returns_none(self):
        self.assertIsNone(fetch_fred_series("DGS10", api_key=""))

    def test_krx_liquidity_history_and_asof(self):
        hist = load_krx_liquidity_history(
            ["005930.KS"],
            pd.Timestamp("2024-01-01"),
            pd.Timestamp("2024-01-05"),
            provider=FakeKrxProvider,
            delay=0,
        )
        asof = asof_krx_liquidity(hist, pd.Timestamp("2024-01-03"))

        self.assertIn("005930.KS", asof.index)
        self.assertEqual(asof.loc["005930.KS", "MarketCap_PIT"], 1_100_000_000_000)
        self.assertEqual(asof.loc["005930.KS", "TradingValue_20D"], 400_000_000)

    def test_kr_slippage_uses_trading_value_when_available(self):
        liquid = liquidity_slippage_rate(market="KR", market_cap=100_000_000_000, trading_value_20d=6_000_000_000)
        illiquid = liquidity_slippage_rate(market="KR", market_cap=20_000_000_000_000, trading_value_20d=100_000_000)

        self.assertLess(liquid, illiquid)
        self.assertAlmostEqual(liquid, 0.0005)
        self.assertAlmostEqual(illiquid, 0.0060)

    def test_equal_weight_turnover_costs_include_slippage(self):
        costs = equal_weight_turnover_costs(
            previous={"AAA", "BBB"},
            current={"BBB", "CCC"},
            fee_rate=0.001,
            slippage_rates={"AAA": 0.002, "CCC": 0.004},
        )

        self.assertAlmostEqual(costs["turnover"], 1.0)
        self.assertAlmostEqual(costs["fee"], 0.001)
        self.assertAlmostEqual(costs["slippage"], 0.003)
        self.assertAlmostEqual(costs["total"], 0.004)

    def test_weighted_turnover_costs_captures_resizing_trades(self):
        costs = weighted_turnover_costs(
            previous_weights={"AAA": 0.30, "BBB": 0.30},
            current_weights={"AAA": 0.20, "CCC": 0.40},
            fee_rate=0.001,
            slippage_rates={"AAA": 0.002, "BBB": 0.003, "CCC": 0.004},
        )

        self.assertAlmostEqual(costs["turnover"], 0.80)
        self.assertAlmostEqual(costs["fee"], 0.0008)
        self.assertAlmostEqual(costs["slippage"], 0.0027)


if __name__ == "__main__":
    unittest.main()
