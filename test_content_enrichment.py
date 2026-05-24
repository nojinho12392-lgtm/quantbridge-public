import unittest

from tools.sync_content_enrichment import (
    better_identity,
    etf_identities,
    identity_from_row,
    merge_identities,
    quality_records,
)
from tools.etf_holdings_refresh import (
    append_data_source,
    holdings_from_table,
    ketf_holdings_from_rows,
    kis_holdings_from_rows,
    pykrx_holdings_from_table,
)


class FakeHoldingsTable:
    columns = ["Name", "Holding Percent"]
    empty = False

    def iterrows(self):
        yield "AAPL", {"Name": "Apple Inc", "Holding Percent": "7.5%"}
        yield "MSFT", {"Name": "Microsoft Corp", "Holding Percent": 0.061}


class FakePykrxTable:
    columns = ["종목명", "비중"]
    empty = False

    def iterrows(self):
        yield "005930", {"종목명": "삼성전자", "비중": 31.77}
        yield "000660", {"종목명": "SK하이닉스", "비중": "5.69"}


class ContentEnrichmentTests(unittest.TestCase):
    def test_holdings_table_parses_top_weights(self):
        rows = holdings_from_table(FakeHoldingsTable(), max_holdings=10)

        self.assertEqual(rows[0]["ticker"], "AAPL")
        self.assertAlmostEqual(rows[0]["weight"], 0.075)
        self.assertEqual(rows[1]["ticker"], "MSFT")
        self.assertAlmostEqual(rows[1]["weight"], 0.061)

    def test_append_data_source_is_idempotent(self):
        self.assertEqual(
            append_data_source("curated_universe;holdings:yfinance", "holdings:yfinance"),
            "curated_universe;holdings:yfinance",
        )

    def test_kis_rows_parse_domestic_etf_weights(self):
        rows = kis_holdings_from_rows([
            {"stck_shrn_iscd": "005930", "hts_kor_isnm": "삼성전자", "etf_cnfg_issu_rlim": "31.77"},
            {"stck_shrn_iscd": "000660", "hts_kor_isnm": "SK하이닉스", "etf_cnfg_issu_rlim": "5.69"},
        ])

        self.assertEqual(rows[0]["ticker"], "005930.KS")
        self.assertEqual(rows[0]["name"], "삼성전자")
        self.assertAlmostEqual(rows[0]["weight"], 0.3177)

    def test_pykrx_table_parse_domestic_etf_weights(self):
        rows = pykrx_holdings_from_table(FakePykrxTable(), max_holdings=10)

        self.assertEqual(rows[0]["ticker"], "005930.KS")
        self.assertEqual(rows[0]["name"], "삼성전자")
        self.assertAlmostEqual(rows[1]["weight"], 0.0569)

    def test_ketf_rows_parse_public_domestic_holdings(self):
        rows = ketf_holdings_from_rows([
            {"name": "삼성전자", "ratio": 32.8088},
            {"name": "SK하이닉스", "ratio": 25.6594},
        ])

        self.assertEqual(rows[0]["ticker"], "삼성전자")
        self.assertEqual(rows[0]["name"], "삼성전자")
        self.assertAlmostEqual(rows[0]["weight"], 0.328088)

    def test_kr_identity_gets_currency_and_logo(self):
        identity = identity_from_row(
            {"Ticker": "005930", "Name": "Samsung Electronics", "Sector": "Technology"},
            "005930",
            "KR",
            "KR_Final_Portfolio",
        )

        self.assertEqual(identity["currency"], "KRW")
        self.assertEqual(identity["logo_source"], "toss")
        self.assertIn("005930", identity["logo_url"])

    def test_better_identity_replaces_missing_ticker_name(self):
        merged = better_identity(
            {"ticker": "AAPL", "name": "AAPL", "market": "US"},
            {"ticker": "AAPL", "name": "Apple", "sector": "Technology"},
        )

        self.assertEqual(merged["name"], "Apple")
        self.assertEqual(merged["sector"], "Technology")

    def test_kr_etf_identity_uses_price_ticker(self):
        identities = etf_identities(
            [{
                "Ticker": "069500",
                "Name": "KODEX 200",
                "Market": "KR",
                "Category": "Index",
                "Theme": "KOSPI200",
                "Summary": "KOSPI 200 ETF",
            }],
            {"KR"},
        )

        self.assertEqual(identities[0]["ticker"], "069500.KS")
        self.assertEqual(identities[0]["asset_type"], "ETF")

    def test_quality_report_contains_summary(self):
        identities = merge_identities([
            {"market": "US", "ticker": "AAPL", "name": "Apple", "sector": "Technology", "logo_url": "https://example.com/aapl.png", "market_cap": 1},
            {"market": "US", "ticker": "MSFT", "name": "MSFT"},
        ])
        report = quality_records(identities, [{"Ticker": "QQQ"}])

        self.assertEqual(report[0]["Ticker"], "SUMMARY")
        self.assertEqual(report[0]["Identity_Count"], 2)
        self.assertEqual(report[0]["ETF_Row_Count"], 1)
        self.assertGreaterEqual(report[0]["Missing_Sector_Count"], 1)


if __name__ == "__main__":
    unittest.main()
