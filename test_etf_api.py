import unittest

from api.services.etf_api import EtfPriceEnricher


def _first_float(*values):
    for value in values:
        try:
            if value is None or value == "":
                continue
            return float(value)
        except (TypeError, ValueError):
            continue
    return None


class EtfPriceEnricherTests(unittest.TestCase):
    def test_enrich_price_fields_uses_kr_price_ticker_and_daily_change(self):
        items = [{"region": "KR", "ticker": "069500"}]
        enricher = EtfPriceEnricher(
            kr_code=lambda value: str(value).strip().zfill(6),
            portfolio_price_snapshot_batch=lambda tickers, market: {
                "069500.KS": {
                    "current_price": 100.0,
                    "return_1m": 0.1,
                    "updated_at": "2026-05-27T00:00:00Z",
                    "source": "snapshot",
                }
            },
            portfolio_daily_change_batch=lambda tickers, market, snapshots: {"069500.KS": (0.02, "regular")},
            portfolio_price_metrics_batch=lambda tickers, market: {},
            first_float=_first_float,
        )

        enriched = enricher.enrich_price_fields(items)

        self.assertIs(enriched, items)
        self.assertEqual(items[0]["currentPrice"], 100.0)
        self.assertEqual(items[0]["return1M"], 0.1)
        self.assertEqual(items[0]["priceSource"], "snapshot")
        self.assertEqual(items[0]["dailyChangePct"], 0.02)
        self.assertEqual(items[0]["Daily_Change_Horizon"], "regular")
        self.assertAlmostEqual(items[0]["priceChange"], 100.0 - 100.0 / 1.1)
        self.assertAlmostEqual(items[0]["dailyPriceChange"], 100.0 - 100.0 / 1.02)


if __name__ == "__main__":
    unittest.main()
