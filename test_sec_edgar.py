import tempfile
import unittest

import pandas as pd

from pipeline.data.sec_edgar import (
    build_fundamental_timeseries,
    concept_series,
    concept_ttm_series,
    fetch_company_facts_for_tickers,
    get_pit_metrics,
    load_cik_map,
)


class FakeResponse:
    status_code = 200

    def __init__(self, payload):
        self._payload = payload

    def json(self):
        return self._payload

    def raise_for_status(self):
        return None


class FakeSession:
    def __init__(self, payload):
        self.payload = payload
        self.calls = 0

    def get(self, *args, **kwargs):
        self.calls += 1
        return FakeResponse(self.payload)


def _fact(concept, unit, rows):
    return {concept: {"units": {unit: rows}}}


def _annual_row(filed, value):
    fiscal_year = int(str(filed)[:4]) - 1
    return {
        "form": "10-K",
        "filed": filed,
        "start": f"{fiscal_year}-01-01",
        "end": f"{fiscal_year}-12-31",
        "val": value,
    }


def _sample_company_facts():
    gaap = {}
    concepts = [
        _fact(
            "Revenues",
            "USD",
            [
                _annual_row("2024-02-15", 100.0),
                _annual_row("2025-02-15", 150.0),
            ],
        ),
        _fact(
            "GrossProfit",
            "USD",
            [
                _annual_row("2024-02-15", 60.0),
                _annual_row("2025-02-15", 90.0),
            ],
        ),
        _fact(
            "OperatingIncomeLoss",
            "USD",
            [
                _annual_row("2024-02-15", 20.0),
                _annual_row("2025-02-15", 30.0),
            ],
        ),
        _fact("NetIncomeLoss", "USD", [_annual_row("2025-02-15", 24.0)]),
        _fact("StockholdersEquity", "USD", [{"form": "10-K", "filed": "2025-02-15", "val": 120.0}]),
        _fact("Assets", "USD", [{"form": "10-K", "filed": "2025-02-15", "val": 300.0}]),
        _fact("AssetsCurrent", "USD", [{"form": "10-K", "filed": "2025-02-15", "val": 120.0}]),
        _fact("LiabilitiesCurrent", "USD", [{"form": "10-K", "filed": "2025-02-15", "val": 80.0}]),
        _fact("RetainedEarningsAccumulatedDeficit", "USD", [{"form": "10-K", "filed": "2025-02-15", "val": 50.0}]),
        _fact("Liabilities", "USD", [{"form": "10-K", "filed": "2025-02-15", "val": 180.0}]),
        _fact("NetCashProvidedByUsedInOperatingActivities", "USD", [_annual_row("2025-02-15", 35.0)]),
        _fact("PaymentsToAcquirePropertyPlantAndEquipment", "USD", [_annual_row("2025-02-15", 10.0)]),
        _fact("LongTermDebt", "USD", [{"form": "10-K", "filed": "2025-02-15", "val": 50.0}]),
        _fact("InterestExpense", "USD", [_annual_row("2025-02-15", 3.0)]),
        _fact(
            "EarningsPerShareDiluted",
            "USD/shares",
            [
                _annual_row("2024-02-15", 1.0),
                _annual_row("2025-02-15", 1.5),
            ],
        ),
    ]
    for concept in concepts:
        gaap.update(concept)
    return {"facts": {"us-gaap": gaap}}


class SecEdgarTests(unittest.TestCase):
    def test_cik_map_uses_cache_after_first_request(self):
        payload = {"0": {"ticker": "AAPL", "cik_str": 320193}}
        session = FakeSession(payload)
        with tempfile.TemporaryDirectory() as tmp:
            first = load_cik_map(session=session, cache_dir=tmp)
            second = load_cik_map(session=session, cache_dir=tmp)

        self.assertEqual(first["AAPL"], "0000320193")
        self.assertEqual(second["AAPL"], "0000320193")
        self.assertEqual(session.calls, 1)

    def test_company_facts_respects_request_budget(self):
        session = FakeSession(_sample_company_facts())
        cik_map = {"AAA": "1", "BBB": "2"}
        with tempfile.TemporaryDirectory() as tmp:
            facts = fetch_company_facts_for_tickers(
                ["AAA", "BBB"],
                cik_map,
                session=session,
                cache_dir=tmp,
                delay=0,
                max_requests=1,
            )

        self.assertEqual(set(facts), {"AAA"})
        self.assertEqual(session.calls, 1)

    def test_pit_metrics_do_not_use_future_filings(self):
        ts = build_fundamental_timeseries({"ABC": _sample_company_facts()})

        before_filing = get_pit_metrics(ts, "ABC", pd.Timestamp("2025-01-31"))
        after_filing = get_pit_metrics(ts, "ABC", pd.Timestamp("2025-03-01"))

        self.assertAlmostEqual(before_filing["Revenue"], 100.0)
        self.assertAlmostEqual(after_filing["Revenue"], 150.0)
        self.assertAlmostEqual(after_filing["RevGrowth"], 0.5)
        self.assertAlmostEqual(after_filing["GrossMargin"], 0.6)
        self.assertAlmostEqual(after_filing["FCF_Margin"], 25.0 / 150.0)
        self.assertAlmostEqual(after_filing["ROIC"], (30.0 * 0.79) / (120.0 + 50.0))
        self.assertAlmostEqual(after_filing["EPS_Growth"], 0.5)

    def test_flow_concepts_prefer_quarter_duration_for_10q(self):
        gaap = {
            "Revenues": {
                "units": {
                    "USD": [
                        {
                            "form": "10-Q",
                            "filed": "2025-05-01",
                            "start": "2024-10-01",
                            "end": "2025-03-31",
                            "val": 300.0,
                        },
                        {
                            "form": "10-Q",
                            "filed": "2025-05-01",
                            "start": "2025-01-01",
                            "end": "2025-03-31",
                            "val": 120.0,
                        },
                    ]
                }
            }
        }

        series = concept_series(gaap, "Revenues", flow=True)

        self.assertAlmostEqual(series.loc[pd.Timestamp("2025-05-01")], 120.0)

    def test_ttm_flow_uses_annual_plus_ytd_delta(self):
        gaap = {
            "Revenues": {
                "units": {
                    "USD": [
                        _annual_row("2025-02-15", 400.0),
                        {
                            "form": "10-Q",
                            "filed": "2024-11-01",
                            "start": "2024-01-01",
                            "end": "2024-09-30",
                            "val": 280.0,
                        },
                        {
                            "form": "10-Q",
                            "filed": "2025-11-01",
                            "start": "2025-01-01",
                            "end": "2025-09-30",
                            "val": 330.0,
                        },
                    ]
                }
            }
        }

        series = concept_ttm_series(gaap, "Revenues")

        self.assertAlmostEqual(series.asof(pd.Timestamp("2025-03-01")), 400.0)
        self.assertAlmostEqual(series.asof(pd.Timestamp("2025-11-02")), 450.0)


if __name__ == "__main__":
    unittest.main()
