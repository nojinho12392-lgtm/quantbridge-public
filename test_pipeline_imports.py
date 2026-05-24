"""Import-safety tests for pipeline modules with historically eager side effects."""

from __future__ import annotations

import importlib.util
from pathlib import Path
from unittest import mock
import unittest

import pandas as pd


ROOT = Path(__file__).resolve().parent


def load_module(rel_path: str, name: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / rel_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class PipelineImportTests(unittest.TestCase):
    def test_macro_regime_import_does_not_touch_network_or_sheets(self):
        with mock.patch("sheets_client.get_spreadsheet", side_effect=AssertionError("Sheets touched")):
            module = load_module("pipeline/02_macro_regime.py", "macro_regime_import_test")

        self.assertTrue(callable(module.run))
        self.assertTrue(callable(module.compute_macro_regime))

    def test_macro_regime_compute_is_testable_with_injected_prices(self):
        module = load_module("pipeline/02_macro_regime.py", "macro_regime_compute_test")
        uptrend = pd.Series([100 + i for i in range(260)])
        flat = pd.Series([100.0 for _ in range(40)])
        hyg = pd.Series([100.0 for _ in range(20)] + [103.0])
        ief = pd.Series([100.0 for _ in range(20)] + [100.0])

        def fake_download(ticker: str, period: str):
            return {
                "^VIX": pd.Series([18.0]),
                "^TNX": pd.Series([4.5]),
                "^IRX": pd.Series([2.0]),
                "^GSPC": uptrend,
                "HYG": hyg,
                "IEF": flat if ticker == "IEF" else ief,
            }[ticker]

        result = module.compute_macro_regime(download=fake_download, sleep=lambda _: None, verbose=False)

        self.assertEqual(result["Regime"], "RISK_ON")
        self.assertGreaterEqual(result["Regime_Score"], 2)
        rows = module.macro_rows(result)
        self.assertEqual(rows[0], ["Metric", "Value"])
        self.assertIn(["Regime", "RISK_ON"], rows)


if __name__ == "__main__":
    unittest.main()
