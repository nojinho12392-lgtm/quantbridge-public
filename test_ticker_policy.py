from __future__ import annotations

import unittest

import pandas as pd

from quantbridge.ticker_policy import (
    drop_banned_ticker_rows,
    filter_banned_tickers,
    is_banned_ticker,
)


class TickerPolicyTests(unittest.TestCase):
    def test_foxa_is_banned_but_fox_is_allowed(self):
        self.assertTrue(is_banned_ticker("FOXA"))
        self.assertTrue(is_banned_ticker(" foxa "))
        self.assertFalse(is_banned_ticker("FOX"))

    def test_filter_banned_tickers_preserves_allowed_tickers(self):
        self.assertEqual(filter_banned_tickers(["AAPL", "FOXA", "FOX"]), ["AAPL", "FOX"])

    def test_drop_banned_ticker_rows(self):
        df = pd.DataFrame({"Ticker": ["AAPL", "FOXA", "FOX"], "Name": ["Apple", "Fox A", "Fox"]})
        out = drop_banned_ticker_rows(df)

        self.assertEqual(out["Ticker"].tolist(), ["AAPL", "FOX"])


if __name__ == "__main__":
    unittest.main()
