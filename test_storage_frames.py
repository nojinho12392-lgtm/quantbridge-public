#!/usr/bin/env python3
"""Unit tests for extracted storage frame helpers."""

from __future__ import annotations

import math
import unittest

import pandas as pd

from api.services.storage_frames import (
    clean_dataframe_columns,
    clean_json_value,
    clean_meta_value,
    coerce_numeric,
    df_to_records,
    infer_storage_market,
    load_storage_df,
    normalize_portfolio_price_columns,
    sheet_values_to_df,
)


class StorageFramesTests(unittest.TestCase):
    def test_clean_json_value(self):
        self.assertIsNone(clean_json_value(None))
        self.assertIsNone(clean_json_value(float("nan")))
        self.assertEqual(clean_json_value(1.5), 1.5)

    def test_clean_dataframe_columns_drops_blanks_and_dupes(self):
        df = pd.DataFrame([[1, 2, 3, 4]], columns=["A", " ", "A", "B"])
        out = clean_dataframe_columns(df)
        self.assertEqual(list(out.columns), ["A", "B"])
        self.assertEqual(out.iloc[0].tolist(), [1, 4])

    def test_sheet_values_to_df(self):
        rows = [["x", "1"], ["y", "2"]]
        df = sheet_values_to_df(rows, ["Ticker", "Rank", "Ticker"])
        self.assertEqual(list(df.columns), ["Ticker", "Rank"])
        self.assertEqual(df.iloc[0]["Ticker"], "x")

    def test_coerce_and_infer_market(self):
        df = pd.DataFrame({"Rank": ["1", "x"], "Weight(%)": ["0.1", "0.2"]})
        out = coerce_numeric(df, ["Rank", "Weight(%)"])
        self.assertTrue(math.isnan(out.loc[1, "Rank"]))
        self.assertEqual(infer_storage_market("US_Final_Portfolio"), "US")
        self.assertEqual(infer_storage_market("KR_Scored_Stocks"), "KR")
        self.assertEqual(infer_storage_market("Macro_Regime"), "GLOBAL")
        self.assertIsNone(infer_storage_market("Factor_IC_Report"))

    def test_normalize_portfolio_price_columns(self):
        df = pd.DataFrame({"Price": [10.0], "Mom_1M": [0.05]})
        out = normalize_portfolio_price_columns(df)
        self.assertEqual(out.loc[0, "Current_Price"], 10.0)
        self.assertEqual(out.loc[0, "Return_1M"], 0.05)

    def test_df_to_records_and_meta(self):
        df = pd.DataFrame({"Ticker": ["AAPL"], "Score": [float("nan")]})
        records = df_to_records(df, localize=lambda row: {**row, "Name": "Apple"})
        self.assertEqual(records[0]["Ticker"], "AAPL")
        self.assertIsNone(records[0]["Score"])
        self.assertEqual(records[0]["Name"], "Apple")
        self.assertIsNone(clean_meta_value("nan"))
        self.assertEqual(clean_meta_value(" ok "), "ok")

    def test_load_storage_df_filters_rank_and_records(self):
        class FakeRepo:
            def read_dataframe(self, sheet_name, market=None):
                return pd.DataFrame(
                    {
                        "Ticker": ["AAA", "", "BBB"],
                        "Rank": ["1", "2", "x"],
                        "dup": [1, 2, 3],
                        "dup ": [4, 5, 6],
                    }
                )

        events = []

        def record(name, source, **kwargs):
            events.append((name, source, kwargs))

        df = load_storage_df(FakeRepo(), "US_Scored_Stocks", market="US", record_data_source=record)
        self.assertEqual(list(df["Ticker"]), ["AAA"])
        self.assertEqual(events[-1][1], "storage")
        self.assertEqual(events[-1][2]["rows"], 1)


if __name__ == "__main__":
    unittest.main()
