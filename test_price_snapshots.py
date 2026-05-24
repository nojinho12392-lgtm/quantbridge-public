from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import patch

import pandas as pd

from api import server
from quantbridge import price_snapshots as ps


class FakeRepo:
    def __init__(self, rows: list[dict]):
        self.rows = rows

    def read_price_metrics(self, tickers, market=None):
        requested = {str(ticker or "").upper() for ticker in tickers}
        rows = [
            row
            for row in self.rows
            if str(row.get("ticker") or "").upper() in requested
            and (not market or str(row.get("market") or "").upper() == str(market).upper())
        ]
        return pd.DataFrame(rows)


def test_app_price_universe_merges_sources_and_aliases():
    targets: dict[tuple[str, str], dict] = {}
    ps._merge_target(
        targets,
        ("US", "AAPL"),
        {
            "market": "US",
            "ticker": "AAPL",
            "raw_ticker": "AAPL",
            "name": "Apple",
            "source_dataset": "US_Final_Portfolio",
        },
    )
    ps._merge_target(
        targets,
        ("US", "AAPL"),
        {
            "market": "US",
            "ticker": "AAPL",
            "raw_ticker": "AAPL",
            "sector": "Technology",
            "source_dataset": "US_Universe",
        },
    )

    rows = ps._app_price_universe_rows(list(targets.values()), "2026-05-20T00:00:00+00:00")

    assert len(rows) == 1
    assert rows[0]["Ticker"] == "AAPL"
    assert rows[0]["ko_name"] == "애플"
    assert rows[0]["Source_Datasets"] == "US_Final_Portfolio,US_Universe"
    assert rows[0]["Sector"] == "Technology"
    assert rows[0]["Aliases"] == "AAPL"


def test_app_price_coverage_report_distinguishes_refresh_and_storage_missing():
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    targets = [
        {"market": "US", "ticker": "AAPL", "raw_ticker": "AAPL", "name": "Apple", "source_dataset": "US_Universe"},
        {"market": "US", "ticker": "MSFT", "raw_ticker": "MSFT", "name": "Microsoft", "source_dataset": "US_Universe"},
        {"market": "US", "ticker": "ZZZ", "raw_ticker": "ZZZ", "name": "Missing", "source_dataset": "Sector_Theme_Seeds"},
    ]
    current_metrics = [
        {"market": "US", "ticker": "AAPL", "current_price": 190.0, "return_1m": 0.02, "as_of": now},
    ]
    repo = FakeRepo(
        [
            {"market": "US", "ticker": "AAPL", "current_price": 190.0, "return_1m": 0.02, "as_of": now},
            {"market": "US", "ticker": "MSFT", "current_price": 420.0, "return_1m": 0.01, "as_of": now},
        ]
    )

    rows, summary = ps._app_price_coverage_rows(repo, targets, current_metrics, generated_at=now)
    status_by_ticker = {row["Ticker"]: row["Status"] for row in rows}

    assert status_by_ticker == {
        "AAPL": "OK",
        "MSFT": "REFRESH_MISSING",
        "ZZZ": "STORAGE_MISSING",
    }
    assert summary["total"] == 3
    assert summary["ok"] == 1
    assert summary["refresh_missing"] == 1
    assert summary["storage_missing"] == 1


def test_download_close_frames_preserves_exchange_timezone_as_utc():
    class FakeYF:
        calls: list[dict] = []

        @classmethod
        def download(cls, batch, **kwargs):
            cls.calls.append(kwargs)
            index = pd.DatetimeIndex(
                ["2026-05-20 15:55:00"],
                tz="America/New_York",
                name="Datetime",
            )
            columns = pd.MultiIndex.from_product([["Close"], ["AAPL"]])
            return pd.DataFrame([[302.25]], index=index, columns=columns)

    frame = ps._download_close_frames(
        FakeYF,
        ["AAPL"],
        period="1d",
        interval="5m",
        batch_size=1,
        delay=0,
    )

    assert FakeYF.calls[0]["ignore_tz"] is False
    assert str(frame.index[-1]) == "2026-05-20 19:55:00"
    assert frame["AAPL"].iloc[-1] == 302.25


def test_us_daily_snapshot_is_preferred_outside_regular_session():
    target = {"market": "US", "ticker": "AAPL", "raw_ticker": "AAPL", "source_dataset": "US_Universe"}

    assert ps._should_use_regular_daily_snapshot(
        target,
        datetime(2026, 5, 20, 13, 0, tzinfo=timezone.utc),
    )
    assert not ps._should_use_regular_daily_snapshot(
        target,
        datetime(2026, 5, 20, 17, 0, tzinfo=timezone.utc),
    )
    assert ps._should_use_regular_daily_snapshot(
        target,
        datetime(2026, 5, 20, 22, 0, tzinfo=timezone.utc),
    )


def test_daily_close_as_of_uses_us_market_close_utc():
    observed = ps._daily_close_as_of(pd.Timestamp("2026-05-20 04:00:00+00:00"), "US")

    assert observed.isoformat() == "2026-05-20T20:00:00+00:00"


def test_closed_regular_index_quote_uses_previous_close_for_display_change():
    class FakeTicker:
        def history(self, **kwargs):
            return pd.DataFrame(
                {
                    "Open": [95.0, 100.0],
                    "High": [99.0, 112.0],
                    "Low": [94.0, 98.0],
                    "Close": [98.0, 110.0],
                    "Volume": [1_000, 2_000],
                },
                index=pd.DatetimeIndex(["2026-05-19", "2026-05-20"]),
            )

    quote = server._indicator_daily_close_quote_from_yfinance(
        FakeTicker(),
        {"symbol": "^IXIC", "label": "NASDAQ", "category": "index_fx", "region": "us", "sort_order": 1},
        regular_session=True,
    )

    assert quote is not None
    assert quote["value"] == 110.0
    assert quote["open"] == 100.0
    assert quote["change_abs"] == 12.0
    assert quote["change_pct"] == 12.0 / 98.0
    assert quote["session"] == "closed"


def test_previous_regular_close_from_storage_ignores_same_day_preopen_points():
    class Repo:
        def read_market_indicator_history(self, symbols, start_at):
            return pd.DataFrame(
                [
                    {
                        "symbol": "^IXIC",
                        "observed_at": "2026-05-19T20:00:00+00:00",
                        "close": 98.0,
                    },
                    {
                        "symbol": "^IXIC",
                        "observed_at": "2026-05-20T13:00:00+00:00",
                        "close": 101.0,
                    },
                ]
            )

    with patch.object(server, "_repository", return_value=Repo()):
        previous = server._previous_regular_close_from_storage(
            "^IXIC",
            "2026-05-20T14:00:00+00:00",
        )

    assert previous == 98.0


def test_indicator_history_points_use_session_open_for_regular_indices():
    frame = pd.DataFrame(
        {
            "Open": [100.0, 101.0],
            "High": [102.0, 104.0],
            "Low": [99.0, 100.0],
            "Close": [101.0, 103.0],
            "Volume": [100, 120],
        },
        index=pd.DatetimeIndex(
            ["2026-05-20 09:30:00", "2026-05-20 09:35:00"],
            tz="America/New_York",
        ),
    )
    points: list[dict] = []

    added = server._append_indicator_history_points(
        points,
        frame,
        "^IXIC",
        "5m",
        {"^IXIC": {"symbol": "^IXIC", "label": "NASDAQ", "category": "index_fx", "region": "us", "sort_order": 1}},
    )

    assert added
    assert points[0]["change_pct"] == 0.01
    assert points[1]["change_abs"] == 3.0
    assert points[1]["change_pct"] == 0.03


def test_kr_one_day_indicator_history_filters_to_current_kst_session(monkeypatch):
    class FixedDateTime(datetime):
        @classmethod
        def now(cls, tz=None):
            value = cls(2026, 5, 21, 0, 30, tzinfo=timezone.utc)
            return value if tz is None else value.astimezone(tz)

    monkeypatch.setattr(server, "datetime", FixedDateTime)
    points = [
        {"timestamp": "2026-05-20T00:00:00+00:00", "close": 100.0},
        {"timestamp": "2026-05-20T05:59:00+00:00", "close": 110.0},
        {"timestamp": "2026-05-21T00:00:00+00:00", "close": 120.0},
        {"timestamp": "2026-05-21T00:17:00+00:00", "close": 125.0},
    ]

    filtered = server._filter_indicator_history_points_for_period("^KS11", points, "1d")

    assert [point["timestamp"] for point in filtered] == [
        "2026-05-21T00:00:00+00:00",
        "2026-05-21T00:17:00+00:00",
    ]


def test_us_one_day_indicator_history_filters_to_latest_ny_session(monkeypatch):
    class FixedDateTime(datetime):
        @classmethod
        def now(cls, tz=None):
            value = cls(2026, 5, 21, 0, 30, tzinfo=timezone.utc)
            return value if tz is None else value.astimezone(tz)

    monkeypatch.setattr(server, "datetime", FixedDateTime)
    points = [
        {"timestamp": "2026-05-19T19:59:00+00:00", "close": 100.0},
        {"timestamp": "2026-05-20T13:30:00+00:00", "close": 110.0},
        {"timestamp": "2026-05-20T19:59:00+00:00", "close": 120.0},
        {"timestamp": "2026-05-20T21:00:00+00:00", "close": 130.0},
    ]

    filtered = server._filter_indicator_history_points_for_period("^IXIC", points, "1d")

    assert [point["timestamp"] for point in filtered] == [
        "2026-05-20T13:30:00+00:00",
        "2026-05-20T19:59:00+00:00",
    ]
