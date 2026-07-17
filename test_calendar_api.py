import unittest
from datetime import datetime

import pandas as pd

from api.services.calendar_api import EarningsCalendarPayloadBuilder


def _first_text(*values):
    for value in values:
        text = str(value or "").strip()
        if text:
            return text
    return None


def _first_float(*values):
    for value in values:
        try:
            if value is None or value == "":
                continue
            return float(value)
        except (TypeError, ValueError):
            continue
    return None


class EarningsCalendarPayloadBuilderTests(unittest.TestCase):
    def test_build_rows_filters_us_universe_and_enriches_kr_identity(self):
        today = datetime(2026, 5, 27)
        end_date = datetime(2026, 6, 30)
        frame = pd.DataFrame(
            [
                {
                    "Ticker": "AAPL",
                    "Name": "Apple",
                    "Market": "US",
                    "MarketCap": 3_000_000_000_000,
                    "Next_Earnings_Date": "2026-06-01",
                },
                {
                    "Ticker": "MSFT",
                    "Name": "Microsoft",
                    "Market": "US",
                    "MarketCap": 2_500_000_000_000,
                    "Next_Earnings_Date": "2026-06-02",
                },
                {
                    "Ticker": "005930",
                    "Name": "",
                    "Market": "KR",
                    "MarketCap": None,
                    "Next_Earnings_Date": "2026-06-03",
                },
            ]
        )
        builder = EarningsCalendarPayloadBuilder(
            load_earnings_calendar_frame=lambda: (pd.DataFrame(), "empty"),
            fetch_verified_earnings_calendar_df=lambda today, days, limit: pd.DataFrame(),
            clean_dataframe_columns=lambda df: df,
            company_identity_lookup=lambda: {},
            us_calendar_allowed_tickers=lambda: {"AAPL"},
            parse_calendar_date=lambda value: datetime.strptime(str(value), "%Y-%m-%d"),
            infer_market_from_ticker=lambda ticker: "KR" if ticker.isdigit() else "US",
            normalize_us_calendar_ticker=lambda ticker: str(ticker).upper().replace(".", "-"),
            kr_code=lambda ticker: str(ticker).strip(),
            identity_payload_from_row=lambda row, ticker, market, source: {},
            first_text=_first_text,
            first_float=_first_float,
            calendar_market_cap=lambda market, *values: _first_float(*values),
            is_missing_kr_name=lambda name, ticker: not str(name or "").strip(),
            naver_kr_identity=lambda ticker: {"Name": "Samsung Electronics", "MarketCap": 500_000_000_000_000},
            safe_float=_first_float,
        )

        rows = builder.build_rows(frame, "ALL", today, end_date)

        self.assertEqual([row["Ticker"] for row in rows], ["AAPL", "005930"])
        self.assertEqual(rows[1]["Name"], "Samsung Electronics")
        self.assertEqual(rows[1]["MarketCap"], 500_000_000_000_000.0)


if __name__ == "__main__":
    unittest.main()
