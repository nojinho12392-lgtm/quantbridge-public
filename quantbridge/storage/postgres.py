"""PostgreSQL service-store adapter.

The first stable table is intentionally generic. It lets existing pipeline
outputs move off Google Sheets without forcing every sheet into a bespoke table
on day one. Typed analytical tables can be layered in after the migration.
"""

from __future__ import annotations

import math
from datetime import date, datetime, timedelta
from typing import Iterable

import pandas as pd


DDL = """
CREATE TABLE IF NOT EXISTS quant_records (
    dataset TEXT NOT NULL,
    market TEXT,
    ticker TEXT NOT NULL,
    snapshot_date DATE NOT NULL,
    payload JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (dataset, market, ticker, snapshot_date)
);

CREATE TABLE IF NOT EXISTS pipeline_runs (
    run_id TEXT PRIMARY KEY,
    runner TEXT NOT NULL,
    status TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS latest_portfolios (
    dataset TEXT NOT NULL,
    market TEXT NOT NULL,
    ticker TEXT NOT NULL,
    rank INTEGER,
    name TEXT,
    sector TEXT,
    market_cap DOUBLE PRECISION,
    weight_pct DOUBLE PRECISION,
    total_score DOUBLE PRECISION,
    snapshot_date DATE NOT NULL,
    payload JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (dataset, market, ticker)
);

CREATE TABLE IF NOT EXISTS latest_smallcaps (
    dataset TEXT NOT NULL,
    market TEXT NOT NULL,
    ticker TEXT NOT NULL,
    rank INTEGER,
    name TEXT,
    market_cap DOUBLE PRECISION,
    total_score DOUBLE PRECISION,
    snapshot_date DATE NOT NULL,
    payload JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (dataset, market, ticker)
);

CREATE TABLE IF NOT EXISTS latest_scored_stocks (
    dataset TEXT NOT NULL,
    market TEXT NOT NULL,
    ticker TEXT NOT NULL,
    rank INTEGER,
    name TEXT,
    sector TEXT,
    market_cap DOUBLE PRECISION,
    total_score DOUBLE PRECISION,
    final_score DOUBLE PRECISION,
    score_neutral DOUBLE PRECISION,
    combined_score DOUBLE PRECISION,
    snapshot_date DATE NOT NULL,
    payload JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (dataset, market, ticker)
);

CREATE TABLE IF NOT EXISTS company_identity (
    market TEXT NOT NULL,
    ticker TEXT NOT NULL,
    name TEXT,
    sector TEXT,
    logo_url TEXT,
    logo_source TEXT,
    currency TEXT,
    exchange TEXT,
    market_cap DOUBLE PRECISION,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (market, ticker)
);

CREATE TABLE IF NOT EXISTS price_ohlcv (
    market TEXT NOT NULL,
    ticker TEXT NOT NULL,
    price_date DATE NOT NULL,
    open DOUBLE PRECISION,
    high DOUBLE PRECISION,
    low DOUBLE PRECISION,
    close DOUBLE PRECISION,
    volume DOUBLE PRECISION,
    source TEXT NOT NULL DEFAULT 'yfinance',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (market, ticker, price_date)
);

CREATE TABLE IF NOT EXISTS latest_price_metrics (
    market TEXT NOT NULL,
    ticker TEXT NOT NULL,
    current_price DOUBLE PRECISION,
    return_1m DOUBLE PRECISION,
    as_of TIMESTAMPTZ,
    source TEXT NOT NULL DEFAULT 'yfinance',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (market, ticker)
);

CREATE TABLE IF NOT EXISTS latest_signal_events (
    event_id TEXT PRIMARY KEY,
    market TEXT NOT NULL,
    ticker TEXT NOT NULL,
    name TEXT,
    kind TEXT NOT NULL,
    severity INTEGER NOT NULL DEFAULT 1,
    title TEXT NOT NULL,
    detail TEXT NOT NULL,
    metric_label TEXT,
    metric_value TEXT,
    event_time TIMESTAMPTZ NOT NULL,
    source TEXT NOT NULL DEFAULT 'system',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS market_indicator_ticks (
    symbol TEXT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    label TEXT NOT NULL,
    category TEXT NOT NULL,
    region TEXT NOT NULL,
    value DOUBLE PRECISION NOT NULL,
    change_abs DOUBLE PRECISION,
    change_pct DOUBLE PRECISION,
    open DOUBLE PRECISION,
    high DOUBLE PRECISION,
    low DOUBLE PRECISION,
    close DOUBLE PRECISION,
    volume DOUBLE PRECISION,
    source TEXT NOT NULL DEFAULT 'yfinance',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, observed_at)
);

CREATE TABLE IF NOT EXISTS app_api_snapshots (
    cache_key TEXT PRIMARY KEY,
    payload JSONB NOT NULL,
    source TEXT NOT NULL DEFAULT 'precomputed',
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_price_ohlcv_lookup
    ON price_ohlcv (market, ticker, price_date DESC);

CREATE INDEX IF NOT EXISTS idx_price_ohlcv_ticker_date
    ON price_ohlcv (ticker, price_date DESC);

CREATE INDEX IF NOT EXISTS idx_latest_price_metrics_market_updated
    ON latest_price_metrics (market, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_latest_signal_events_market_severity
    ON latest_signal_events (market, severity DESC, event_time DESC);

CREATE INDEX IF NOT EXISTS idx_latest_signal_events_ticker
    ON latest_signal_events (ticker, event_time DESC);

CREATE INDEX IF NOT EXISTS idx_market_indicator_ticks_symbol_time
    ON market_indicator_ticks (symbol, observed_at DESC);

CREATE INDEX IF NOT EXISTS idx_market_indicator_ticks_category_time
    ON market_indicator_ticks (category, observed_at DESC);

CREATE INDEX IF NOT EXISTS idx_app_api_snapshots_updated
    ON app_api_snapshots (updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_quant_records_latest
    ON quant_records (dataset, market, snapshot_date DESC);

CREATE INDEX IF NOT EXISTS idx_pipeline_runs_started_at
    ON pipeline_runs (started_at DESC);

CREATE INDEX IF NOT EXISTS idx_latest_portfolios_rank
    ON latest_portfolios (market, dataset, rank);

CREATE INDEX IF NOT EXISTS idx_latest_smallcaps_rank
    ON latest_smallcaps (market, dataset, rank);

CREATE INDEX IF NOT EXISTS idx_latest_scored_stocks_rank
    ON latest_scored_stocks (market, dataset, rank);

CREATE TABLE IF NOT EXISTS schema_migrations (
    version TEXT PRIMARY KEY,
    description TEXT NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
"""


MIGRATIONS: tuple[tuple[str, str, str], ...] = (
    (
        "20260509_service_indexes",
        "Add service-store indexes for API detail, latest snapshots, and pipeline run reads.",
        """
        CREATE INDEX IF NOT EXISTS idx_price_ohlcv_lookup
            ON price_ohlcv (market, ticker, price_date DESC);
        CREATE INDEX IF NOT EXISTS idx_price_ohlcv_ticker_date
            ON price_ohlcv (ticker, price_date DESC);
        CREATE INDEX IF NOT EXISTS idx_quant_records_latest
            ON quant_records (dataset, market, snapshot_date DESC);
        CREATE INDEX IF NOT EXISTS idx_pipeline_runs_started_at
            ON pipeline_runs (started_at DESC);
        CREATE INDEX IF NOT EXISTS idx_latest_portfolios_rank
            ON latest_portfolios (market, dataset, rank);
        CREATE INDEX IF NOT EXISTS idx_latest_smallcaps_rank
            ON latest_smallcaps (market, dataset, rank);
        CREATE INDEX IF NOT EXISTS idx_latest_scored_stocks_rank
            ON latest_scored_stocks (market, dataset, rank);
        """,
    ),
    (
        "20260511_market_indicator_ticks",
        "Store current and intraday market indicator observations for mobile charts.",
        """
        CREATE TABLE IF NOT EXISTS market_indicator_ticks (
            symbol TEXT NOT NULL,
            observed_at TIMESTAMPTZ NOT NULL,
            label TEXT NOT NULL,
            category TEXT NOT NULL,
            region TEXT NOT NULL,
            value DOUBLE PRECISION NOT NULL,
            change_abs DOUBLE PRECISION,
            change_pct DOUBLE PRECISION,
            open DOUBLE PRECISION,
            high DOUBLE PRECISION,
            low DOUBLE PRECISION,
            close DOUBLE PRECISION,
            volume DOUBLE PRECISION,
            source TEXT NOT NULL DEFAULT 'yfinance',
            payload JSONB NOT NULL DEFAULT '{}'::jsonb,
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            PRIMARY KEY (symbol, observed_at)
        );
        CREATE INDEX IF NOT EXISTS idx_market_indicator_ticks_symbol_time
            ON market_indicator_ticks (symbol, observed_at DESC);
        CREATE INDEX IF NOT EXISTS idx_market_indicator_ticks_category_time
            ON market_indicator_ticks (category, observed_at DESC);
        """,
    ),
    (
        "20260515_latest_price_metrics",
        "Store latest portfolio and smallcap price/one-month-return snapshots.",
        """
        CREATE TABLE IF NOT EXISTS latest_price_metrics (
            market TEXT NOT NULL,
            ticker TEXT NOT NULL,
            current_price DOUBLE PRECISION,
            return_1m DOUBLE PRECISION,
            as_of TIMESTAMPTZ,
            source TEXT NOT NULL DEFAULT 'yfinance',
            payload JSONB NOT NULL DEFAULT '{}'::jsonb,
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            PRIMARY KEY (market, ticker)
        );
        CREATE INDEX IF NOT EXISTS idx_latest_price_metrics_market_updated
            ON latest_price_metrics (market, updated_at DESC);
        """,
    ),
    (
        "20260515_latest_signal_events",
        "Store latest server-generated events for app briefings and comparison workflows.",
        """
        CREATE TABLE IF NOT EXISTS latest_signal_events (
            event_id TEXT PRIMARY KEY,
            market TEXT NOT NULL,
            ticker TEXT NOT NULL,
            name TEXT,
            kind TEXT NOT NULL,
            severity INTEGER NOT NULL DEFAULT 1,
            title TEXT NOT NULL,
            detail TEXT NOT NULL,
            metric_label TEXT,
            metric_value TEXT,
            event_time TIMESTAMPTZ NOT NULL,
            source TEXT NOT NULL DEFAULT 'system',
            payload JSONB NOT NULL DEFAULT '{}'::jsonb,
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        CREATE INDEX IF NOT EXISTS idx_latest_signal_events_market_severity
            ON latest_signal_events (market, severity DESC, event_time DESC);
        CREATE INDEX IF NOT EXISTS idx_latest_signal_events_ticker
            ON latest_signal_events (ticker, event_time DESC);
        """,
    ),
    (
        "20260522_app_api_snapshots",
        "Store precomputed mobile API payloads for fast app startup and list navigation.",
        """
        CREATE TABLE IF NOT EXISTS app_api_snapshots (
            cache_key TEXT PRIMARY KEY,
            payload JSONB NOT NULL,
            source TEXT NOT NULL DEFAULT 'precomputed',
            generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        CREATE INDEX IF NOT EXISTS idx_app_api_snapshots_updated
            ON app_api_snapshots (updated_at DESC);
        """,
    ),
)

PERIOD_DAYS = {
    "1mo": 31,
    "3mo": 93,
    "6mo": 186,
    "1y": 370,
    "2y": 740,
    "3y": 1110,
    "5y": 1850,
}


def _record_key(row: dict, idx: int) -> str:
    explicit = str(row.get("Record_Key") or row.get("Row_Key") or row.get("record_key") or "").strip()
    if explicit:
        return explicit

    composite_cols = ("Snapshot_Date", "Date", "Market", "Ticker", "Factor", "Horizon", "Metric", "Industry", "Key")
    composite_parts = [str(row.get(key) or "").strip() for key in composite_cols if str(row.get(key) or "").strip()]
    if len(composite_parts) >= 2 and any(str(row.get(key) or "").strip() for key in ("Snapshot_Date", "Date", "Factor", "Horizon")):
        return "|".join(composite_parts)

    for key in ("Ticker", "ticker", "Industry", "Key", "Metric", "Snapshot_Date", "Date", "Market"):
        value = str(row.get(key) or "").strip()
        if value:
            return value
    return f"ROW_{idx:06d}"


def _record_market(row: dict, fallback: str | None) -> str:
    raw = str(row.get("Market") or row.get("market") or "").strip().upper()
    if raw in {"US", "KR", "GLOBAL"}:
        return raw
    return str(fallback or "GLOBAL").strip().upper() or "GLOBAL"


def _to_float(value) -> float | None:
    try:
        text = str(value).replace(",", "").strip()
        return float(text) if text else None
    except (TypeError, ValueError):
        return None


def _to_int(value) -> int | None:
    number = _to_float(value)
    return int(number) if number is not None else None


def _normal_ticker(ticker: str) -> str:
    return str(ticker or "").strip().upper()


def _period_start(period: str) -> date:
    days = PERIOD_DAYS.get(period, PERIOD_DAYS["6mo"])
    return date.today() - timedelta(days=days)


def _to_date(value) -> date | None:
    if value is None or value == "":
        return None
    if isinstance(value, date) and not isinstance(value, datetime):
        return value
    if isinstance(value, datetime):
        return value.date()
    try:
        return pd.to_datetime(value).date()
    except Exception:
        return None


def _to_datetime(value) -> datetime | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value
    try:
        return pd.to_datetime(value, utc=True).to_pydatetime()
    except Exception:
        return None


def _json_safe(value):
    if value is None:
        return None
    if isinstance(value, (str, bool, int)):
        return value
    if isinstance(value, float):
        return value if math.isfinite(value) else None
    try:
        if pd.isna(value):
            return None
    except (TypeError, ValueError):
        pass
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [_json_safe(item) for item in value]
    if isinstance(value, (date, datetime, pd.Timestamp)):
        return value.isoformat()
    if hasattr(value, "item"):
        try:
            return _json_safe(value.item())
        except Exception:
            pass
    return str(value)


class PostgresStore:
    def __init__(self, database_url: str):
        self.database_url = database_url

    def _connect(self):
        import psycopg

        return psycopg.connect(self.database_url)

    def ensure_schema(self) -> None:
        with self._connect() as conn:
            conn.execute(DDL)
            self._apply_migrations(conn)

    def _apply_migrations(self, conn) -> None:
        for version, description, sql in MIGRATIONS:
            exists = conn.execute(
                "SELECT 1 FROM schema_migrations WHERE version = %s",
                (version,),
            ).fetchone()
            if exists:
                continue
            conn.execute(sql)
            conn.execute(
                """
                INSERT INTO schema_migrations (version, description)
                VALUES (%s, %s)
                ON CONFLICT (version) DO NOTHING
                """,
                (version, description),
            )

    def write_records(
        self,
        dataset: str,
        records: Iterable[dict],
        market: str | None = None,
        snapshot_date: str | None = None,
    ) -> int:
        from psycopg.types.json import Jsonb

        rows = list(records)
        if not rows:
            return 0

        self.ensure_schema()
        snap = snapshot_date or datetime.utcnow().strftime("%Y-%m-%d")
        payloads = []
        typed_rows = []
        for idx, row in enumerate(rows, 1):
            ticker = _record_key(row, idx)
            row_market = _record_market(row, market)
            payloads.append((dataset, row_market, ticker, snap, Jsonb(row)))
            typed_rows.append((row_market, ticker, row))

        if not payloads:
            return 0

        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "DELETE FROM quant_records WHERE dataset = %s AND snapshot_date = %s",
                    (dataset, snap),
                )
                cur.executemany(
                    """
                    INSERT INTO quant_records (dataset, market, ticker, snapshot_date, payload)
                    VALUES (%s, %s, %s, %s, %s)
                    ON CONFLICT (dataset, market, ticker, snapshot_date)
                    DO UPDATE SET payload = excluded.payload, updated_at = now()
                    """,
                    payloads,
                )
                self._write_typed_latest(cur, dataset, snap, typed_rows)
        return len(payloads)

    def _write_typed_latest(self, cur, dataset: str, snapshot_date: str, typed_rows: list[tuple]) -> None:
        from psycopg.types.json import Jsonb
        markets = sorted({market for market, _, row in typed_rows if row.get("Ticker") or row.get("ticker")})

        if dataset.endswith("_Final_Portfolio"):
            for market in markets:
                cur.execute("DELETE FROM latest_portfolios WHERE dataset = %s AND market = %s", (dataset, market))
            cur.executemany(
                """
                INSERT INTO latest_portfolios
                (dataset, market, ticker, rank, name, sector, market_cap, weight_pct,
                 total_score, snapshot_date, payload)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (dataset, market, ticker)
                DO UPDATE SET rank = excluded.rank,
                              name = excluded.name,
                              sector = excluded.sector,
                              market_cap = excluded.market_cap,
                              weight_pct = excluded.weight_pct,
                              total_score = excluded.total_score,
                              snapshot_date = excluded.snapshot_date,
                              payload = excluded.payload,
                              updated_at = now()
                """,
                [
                    (
                        dataset, market, ticker, _to_int(row.get("Rank")), row.get("Name"),
                        row.get("Sector"), _to_float(row.get("MarketCap")),
                        _to_float(row.get("Weight(%)")), _to_float(row.get("Total_Score")),
                        snapshot_date, Jsonb(row),
                    )
                    for market, ticker, row in typed_rows
                    if row.get("Ticker") or row.get("ticker")
                ],
            )
        elif dataset.endswith("_SmallCap_Gems"):
            for market in markets:
                cur.execute("DELETE FROM latest_smallcaps WHERE dataset = %s AND market = %s", (dataset, market))
            cur.executemany(
                """
                INSERT INTO latest_smallcaps
                (dataset, market, ticker, rank, name, market_cap, total_score, snapshot_date, payload)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (dataset, market, ticker)
                DO UPDATE SET rank = excluded.rank,
                              name = excluded.name,
                              market_cap = excluded.market_cap,
                              total_score = excluded.total_score,
                              snapshot_date = excluded.snapshot_date,
                              payload = excluded.payload,
                              updated_at = now()
                """,
                [
                    (
                        dataset, market, ticker, _to_int(row.get("Rank")), row.get("Name"),
                        _to_float(row.get("MarketCap")), _to_float(row.get("Total_Score")),
                        snapshot_date, Jsonb(row),
                    )
                    for market, ticker, row in typed_rows
                    if row.get("Ticker") or row.get("ticker")
                ],
            )
        elif dataset.endswith("_Scored_Stocks"):
            for market in markets:
                cur.execute("DELETE FROM latest_scored_stocks WHERE dataset = %s AND market = %s", (dataset, market))
            cur.executemany(
                """
                INSERT INTO latest_scored_stocks
                (dataset, market, ticker, rank, name, sector, market_cap, total_score,
                 final_score, score_neutral, combined_score, snapshot_date, payload)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (dataset, market, ticker)
                DO UPDATE SET rank = excluded.rank,
                              name = excluded.name,
                              sector = excluded.sector,
                              market_cap = excluded.market_cap,
                              total_score = excluded.total_score,
                              final_score = excluded.final_score,
                              score_neutral = excluded.score_neutral,
                              combined_score = excluded.combined_score,
                              snapshot_date = excluded.snapshot_date,
                              payload = excluded.payload,
                              updated_at = now()
                """,
                [
                    (
                        dataset, market, ticker, _to_int(row.get("Rank")), row.get("Name"),
                        row.get("Sector"), _to_float(row.get("MarketCap")),
                        _to_float(row.get("Total_Score")), _to_float(row.get("Final_Score")),
                        _to_float(row.get("Score_Neutral")), _to_float(row.get("Combined_Score")),
                        snapshot_date, Jsonb(row),
                    )
                    for market, ticker, row in typed_rows
                    if row.get("Ticker") or row.get("ticker")
                ],
            )

    def record_run(self, run_id: str, runner: str, status: str, payload: dict | None = None) -> None:
        from psycopg.types.json import Jsonb

        self.ensure_schema()
        now = datetime.utcnow()
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO pipeline_runs (run_id, runner, status, started_at, finished_at, payload)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (run_id)
                DO UPDATE SET status = excluded.status,
                              finished_at = excluded.finished_at,
                              payload = excluded.payload
                """,
                (run_id, runner, status, now, now if status in {"success", "failed"} else None, Jsonb(payload or {})),
            )

    def read_runs(self, limit: int = 50) -> pd.DataFrame:
        self.ensure_schema()
        safe_limit = max(1, min(int(limit or 50), 200))
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT run_id, runner, status, started_at, finished_at, payload
                FROM pipeline_runs
                ORDER BY started_at DESC
                LIMIT %s
                """,
                (safe_limit,),
            ).fetchall()

        records = []
        for row in rows:
            records.append({
                "run_id": row[0],
                "runner": row[1],
                "status": row[2],
                "started_at": row[3].isoformat() if row[3] else None,
                "finished_at": row[4].isoformat() if row[4] else None,
                "payload": dict(row[5] or {}),
            })
        return pd.DataFrame(records)

    def upsert_app_api_snapshot(
        self,
        cache_key: str,
        payload: object,
        source: str = "api_precompute",
    ) -> None:
        from psycopg.types.json import Jsonb

        clean_key = str(cache_key or "").strip()
        if not clean_key:
            return
        self.ensure_schema()
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO app_api_snapshots (cache_key, payload, source, generated_at, updated_at)
                VALUES (%s, %s, %s, now(), now())
                ON CONFLICT (cache_key)
                DO UPDATE SET payload = excluded.payload,
                              source = excluded.source,
                              generated_at = excluded.generated_at,
                              updated_at = now()
                """,
                (clean_key, Jsonb(_json_safe(payload)), str(source or "api_precompute")),
            )

    def read_app_api_snapshot(self, cache_key: str, max_age_seconds: int | None = None) -> object | None:
        clean_key = str(cache_key or "").strip()
        if not clean_key:
            return None
        self.ensure_schema()
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT payload, updated_at
                FROM app_api_snapshots
                WHERE cache_key = %s
                """,
                (clean_key,),
            ).fetchone()

        if not row:
            return None
        updated_at = row[1]
        if max_age_seconds is not None and updated_at is not None:
            age = datetime.now(updated_at.tzinfo) - updated_at
            if age.total_seconds() > max_age_seconds:
                return None
        return _json_safe(row[0])

    def read_latest(self, dataset: str, market: str | None = None) -> pd.DataFrame:
        self.ensure_schema()
        params = [dataset]
        market_filter = ""
        if market:
            market_filter = "AND market = %s"
            params.append(market.upper())

        with self._connect() as conn:
            rows = conn.execute(
                f"""
                WITH latest AS (
                    SELECT max(snapshot_date) AS snapshot_date
                    FROM quant_records
                    WHERE dataset = %s {market_filter}
                )
                SELECT payload
                FROM quant_records, latest
                WHERE dataset = %s
                  {market_filter}
                  AND quant_records.snapshot_date = latest.snapshot_date
                ORDER BY quant_records.snapshot_date DESC, ticker
                """,
                params + params,
            ).fetchall()

        records = [dict(row[0]) for row in rows]
        return pd.DataFrame(records)

    def read_history(self, dataset: str, market: str | None = None) -> pd.DataFrame:
        """Return all stored snapshots for a generic dataset.

        The generic ``quant_records.snapshot_date`` is the storage snapshot
        date. Some research datasets also carry a logical date inside the JSON
        payload, so expose the storage date separately for callers that need to
        de-duplicate point-in-time rows across multiple storage writes.
        """

        self.ensure_schema()
        params = [dataset]
        market_filter = ""
        if market:
            market_filter = "AND market = %s"
            params.append(market.upper())

        with self._connect() as conn:
            rows = conn.execute(
                f"""
                SELECT payload, snapshot_date
                FROM quant_records
                WHERE dataset = %s {market_filter}
                ORDER BY snapshot_date ASC, market, ticker
                """,
                params,
            ).fetchall()

        records = []
        for payload, storage_snapshot_date in rows:
            row = dict(payload)
            row["_storage_snapshot_date"] = storage_snapshot_date.isoformat() if storage_snapshot_date else None
            records.append(row)
        return pd.DataFrame(records)

    def read_previous_ranks(self, dataset: str, market: str | None = None) -> pd.DataFrame:
        """Return ticker ranks from the snapshot immediately before the current latest snapshot."""

        self.ensure_schema()
        params = [dataset]
        market_filter = ""
        if market:
            market_filter = "AND market = %s"
            params.append(market.upper())

        with self._connect() as conn:
            rows = conn.execute(
                f"""
                WITH latest AS (
                    SELECT max(snapshot_date) AS snapshot_date
                    FROM quant_records
                    WHERE dataset = %s {market_filter}
                ),
                previous AS (
                    SELECT max(snapshot_date) AS snapshot_date
                    FROM quant_records
                    WHERE dataset = %s
                      {market_filter}
                      AND snapshot_date < (SELECT snapshot_date FROM latest)
                )
                SELECT ticker, payload->>'Ticker' AS payload_ticker, payload->>'Rank' AS rank, snapshot_date
                FROM quant_records
                WHERE dataset = %s
                  {market_filter}
                  AND snapshot_date = (SELECT snapshot_date FROM previous)
                """,
                params + params + params,
            ).fetchall()

        records = []
        for row in rows:
            records.append({
                "Ticker": row[1] or row[0],
                "Previous_Rank": _to_int(row[2]),
                "Rank_Snapshot_Date": row[3].isoformat() if row[3] else None,
            })
        return pd.DataFrame(records)

    def upsert_identity(self, identity: dict) -> None:
        from psycopg.types.json import Jsonb

        market = str(identity.get("market") or identity.get("Market") or "").strip().upper()
        ticker = _normal_ticker(identity.get("ticker") or identity.get("Ticker"))
        if not market or not ticker:
            return

        self.ensure_schema()
        payload = dict(identity)
        with self._connect() as conn:
            conn.execute(
                """
                INSERT INTO company_identity
                    (market, ticker, name, sector, logo_url, logo_source,
                     currency, exchange, market_cap, payload)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (market, ticker)
                DO UPDATE SET name = excluded.name,
                              sector = excluded.sector,
                              logo_url = excluded.logo_url,
                              logo_source = excluded.logo_source,
                              currency = excluded.currency,
                              exchange = excluded.exchange,
                              market_cap = excluded.market_cap,
                              payload = excluded.payload,
                              updated_at = now()
                """,
                (
                    market, ticker, identity.get("name") or identity.get("Name"),
                    identity.get("sector") or identity.get("Sector"),
                    identity.get("logo_url"), identity.get("logo_source"),
                    identity.get("currency"), identity.get("exchange"),
                    _to_float(identity.get("market_cap") or identity.get("MarketCap")),
                    Jsonb(payload),
                ),
            )

    def read_identity(self, ticker: str, market: str | None = None) -> dict | None:
        self.ensure_schema()
        normal = _normal_ticker(ticker)
        if not normal:
            return None

        params = [normal]
        market_filter = ""
        if market:
            market_filter = "AND market = %s"
            params.append(market.upper())

        with self._connect() as conn:
            row = conn.execute(
                f"""
                SELECT market, ticker, name, sector, logo_url, logo_source,
                       currency, exchange, market_cap, payload, updated_at
                FROM company_identity
                WHERE ticker = %s {market_filter}
                ORDER BY updated_at DESC
                LIMIT 1
                """,
                params,
            ).fetchone()

        if not row:
            return None
        payload = dict(row[9] or {})
        payload.update({
            "market": row[0],
            "ticker": row[1],
            "name": row[2],
            "sector": row[3],
            "logo_url": row[4],
            "logo_source": row[5],
            "currency": row[6],
            "exchange": row[7],
            "market_cap": row[8],
            "updated_at": row[10].isoformat() if row[10] else None,
        })
        return payload

    def upsert_prices(
        self,
        ticker: str,
        market: str,
        prices: Iterable[dict],
        source: str = "yfinance",
    ) -> int:
        rows = []
        normal = _normal_ticker(ticker)
        row_market = str(market or "").strip().upper()
        if not normal or not row_market:
            return 0

        for price in prices:
            price_date = _to_date(price.get("date") or price.get("Date"))
            if price_date is None:
                continue
            rows.append((
                row_market,
                normal,
                price_date,
                _to_float(price.get("open") or price.get("Open")),
                _to_float(price.get("high") or price.get("High")),
                _to_float(price.get("low") or price.get("Low")),
                _to_float(price.get("close") or price.get("Close")),
                _to_float(price.get("volume") or price.get("Volume")),
                source,
            ))

        if not rows:
            return 0

        self.ensure_schema()
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.executemany(
                    """
                    INSERT INTO price_ohlcv
                        (market, ticker, price_date, open, high, low, close, volume, source)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (market, ticker, price_date)
                    DO UPDATE SET open = COALESCE(excluded.open, price_ohlcv.open),
                                  high = COALESCE(excluded.high, price_ohlcv.high),
                                  low = COALESCE(excluded.low, price_ohlcv.low),
                                  close = COALESCE(excluded.close, price_ohlcv.close),
                                  volume = COALESCE(excluded.volume, price_ohlcv.volume),
                                  source = excluded.source,
                                  updated_at = now()
                    """,
                    rows,
                )
        return len(rows)

    def read_prices(self, ticker: str, period: str = "6mo", market: str | None = None) -> pd.DataFrame:
        self.ensure_schema()
        normal = _normal_ticker(ticker)
        if not normal:
            return pd.DataFrame()

        params = [normal, _period_start(period)]
        market_filter = ""
        if market:
            market_filter = "AND market = %s"
            params.append(market.upper())

        with self._connect() as conn:
            rows = conn.execute(
                f"""
                SELECT price_date, open, high, low, close, volume, source, updated_at, market
                FROM price_ohlcv
                WHERE ticker = %s
                  AND price_date >= %s
                  {market_filter}
                ORDER BY price_date ASC
                """,
                params,
            ).fetchall()

        if not rows:
            return pd.DataFrame()

        return pd.DataFrame(
            [
                {
                    "date": str(row[0]),
                    "open": row[1],
                    "high": row[2],
                    "low": row[3],
                    "close": row[4],
                    "volume": row[5],
                    "source": row[6],
                    "updated_at": row[7],
                    "market": row[8],
                }
                for row in rows
            ]
        )

    def read_prices_batch(
        self,
        tickers: Iterable[str],
        period: str = "6mo",
        market: str | None = None,
    ) -> pd.DataFrame:
        self.ensure_schema()
        normals = sorted({_normal_ticker(ticker) for ticker in tickers if _normal_ticker(ticker)})
        if not normals:
            return pd.DataFrame()

        params = [normals, _period_start(period)]
        market_filter = ""
        if market:
            market_filter = "AND market = %s"
            params.append(market.upper())

        with self._connect() as conn:
            rows = conn.execute(
                f"""
                SELECT ticker, price_date, open, high, low, close, volume, source, updated_at, market
                FROM price_ohlcv
                WHERE ticker = ANY(%s)
                  AND price_date >= %s
                  {market_filter}
                ORDER BY ticker ASC, price_date ASC
                """,
                params,
            ).fetchall()

        if not rows:
            return pd.DataFrame()

        return pd.DataFrame(
            [
                {
                    "ticker": row[0],
                    "date": str(row[1]),
                    "open": row[2],
                    "high": row[3],
                    "low": row[4],
                    "close": row[5],
                    "volume": row[6],
                    "source": row[7],
                    "updated_at": row[8],
                    "market": row[9],
                }
                for row in rows
            ]
        )

    def upsert_price_metrics(self, metrics: Iterable[dict], source: str = "yfinance") -> int:
        from psycopg.types.json import Jsonb

        def pick(row: dict, *keys: str):
            for key in keys:
                value = row.get(key)
                if value is not None and str(value).strip() != "":
                    return value
            return None

        def clean_payload_value(value):
            if value is None:
                return None
            try:
                if pd.isna(value):
                    return None
            except (TypeError, ValueError):
                pass
            if hasattr(value, "isoformat"):
                return value.isoformat()
            return value

        rows = []
        for metric in metrics:
            market = str(pick(metric, "market", "Market") or "").strip().upper()
            ticker = _normal_ticker(pick(metric, "ticker", "Ticker"))
            current_price = _to_float(pick(metric, "current_price", "Current_Price", "price", "Price"))
            return_1m = _to_float(pick(metric, "return_1m", "Return_1M", "1M_Return", "Mom_1M"))
            as_of = _to_datetime(pick(metric, "as_of", "As_Of", "observed_at", "updated_at", "Updated_At"))
            if not market or not ticker or (current_price is None and return_1m is None):
                continue
            payload = {key: clean_payload_value(value) for key, value in metric.items()}
            metric_source = str(pick(metric, "source") or source or "yfinance").strip() or "yfinance"
            rows.append((
                market,
                ticker,
                current_price,
                return_1m,
                as_of or datetime.utcnow(),
                metric_source,
                Jsonb(payload),
            ))

        if not rows:
            return 0

        self.ensure_schema()
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.executemany(
                    """
                    INSERT INTO latest_price_metrics
                        (market, ticker, current_price, return_1m, as_of, source, payload)
                    VALUES (%s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (market, ticker)
                    DO UPDATE SET current_price = excluded.current_price,
                                  return_1m = excluded.return_1m,
                                  as_of = excluded.as_of,
                                  source = excluded.source,
                                  payload = excluded.payload,
                                  updated_at = now()
                    """,
                    rows,
                )
        return len(rows)

    def read_price_metrics(self, tickers: Iterable[str], market: str | None = None) -> pd.DataFrame:
        self.ensure_schema()
        normals = sorted({_normal_ticker(ticker) for ticker in tickers if _normal_ticker(ticker)})
        if not normals:
            return pd.DataFrame()

        params = [normals]
        market_filter = ""
        if market:
            market_filter = "AND market = %s"
            params.append(market.upper())

        with self._connect() as conn:
            rows = conn.execute(
                f"""
                SELECT ticker, market, current_price, return_1m, as_of, source, updated_at, payload
                FROM latest_price_metrics
                WHERE ticker = ANY(%s)
                  {market_filter}
                ORDER BY market ASC, ticker ASC
                """,
                params,
            ).fetchall()

        if not rows:
            return pd.DataFrame()

        return pd.DataFrame(
            [
                {
                    "ticker": row[0],
                    "market": row[1],
                    "current_price": row[2],
                    "return_1m": row[3],
                    "as_of": row[4],
                    "source": row[5],
                    "updated_at": row[6],
                    "payload": row[7],
                }
                for row in rows
            ]
        )

    def replace_signal_events(self, source: str, events: Iterable[dict]) -> int:
        from psycopg.types.json import Jsonb

        def pick(row: dict, *keys: str):
            for key in keys:
                value = row.get(key)
                if value is not None and str(value).strip() != "":
                    return value
            return None

        def payload_value(value):
            if value is None:
                return None
            try:
                if pd.isna(value):
                    return None
            except (TypeError, ValueError):
                pass
            if hasattr(value, "isoformat"):
                return value.isoformat()
            return value

        clean_source = str(source or "system").strip() or "system"
        rows = []
        for event in events:
            market = str(pick(event, "market", "Market") or "GLOBAL").strip().upper()
            ticker = _normal_ticker(pick(event, "ticker", "Ticker"))
            kind = str(pick(event, "kind", "Kind") or "").strip()
            title = str(pick(event, "title", "Title") or "").strip()
            detail = str(pick(event, "detail", "Detail") or "").strip()
            if not market or not ticker or not kind or not title or not detail:
                continue
            event_id = str(pick(event, "event_id", "id") or f"{clean_source}:{market}:{ticker}:{kind}").strip()
            severity = _to_int(pick(event, "severity", "Severity")) or 1
            event_time = _to_datetime(pick(event, "event_time", "Event_Time", "updated_at", "Updated_At")) or datetime.utcnow()
            payload = {key: payload_value(value) for key, value in event.items()}
            rows.append((
                event_id,
                market,
                ticker,
                pick(event, "name", "Name"),
                kind,
                max(1, min(int(severity), 5)),
                title,
                detail,
                pick(event, "metric_label", "Metric_Label"),
                pick(event, "metric_value", "Metric_Value"),
                event_time,
                clean_source,
                Jsonb(payload),
            ))

        self.ensure_schema()
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM latest_signal_events WHERE source = %s", (clean_source,))
                if rows:
                    cur.executemany(
                        """
                        INSERT INTO latest_signal_events
                            (event_id, market, ticker, name, kind, severity, title, detail,
                             metric_label, metric_value, event_time, source, payload)
                        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                        ON CONFLICT (event_id)
                        DO UPDATE SET market = excluded.market,
                                      ticker = excluded.ticker,
                                      name = excluded.name,
                                      kind = excluded.kind,
                                      severity = excluded.severity,
                                      title = excluded.title,
                                      detail = excluded.detail,
                                      metric_label = excluded.metric_label,
                                      metric_value = excluded.metric_value,
                                      event_time = excluded.event_time,
                                      source = excluded.source,
                                      payload = excluded.payload,
                                      updated_at = now()
                        """,
                        rows,
                    )
        return len(rows)

    def read_signal_events(
        self,
        market: str | None = None,
        tickers: Iterable[str] | None = None,
        kinds: Iterable[str] | None = None,
        limit: int = 100,
    ) -> pd.DataFrame:
        self.ensure_schema()
        filters = []
        params: list = []

        safe_market = str(market or "").strip().upper()
        if safe_market and safe_market != "ALL":
            filters.append("market = %s")
            params.append(safe_market)

        normal_tickers = sorted({_normal_ticker(ticker) for ticker in (tickers or []) if _normal_ticker(ticker)})
        if normal_tickers:
            filters.append("ticker = ANY(%s)")
            params.append(normal_tickers)

        clean_kinds = sorted({str(kind or "").strip() for kind in (kinds or []) if str(kind or "").strip()})
        if clean_kinds:
            filters.append("kind = ANY(%s)")
            params.append(clean_kinds)

        where = f"WHERE {' AND '.join(filters)}" if filters else ""
        safe_limit = max(1, min(int(limit or 100), 500))
        params.append(safe_limit)

        with self._connect() as conn:
            rows = conn.execute(
                f"""
                SELECT event_id, market, ticker, name, kind, severity, title, detail,
                       metric_label, metric_value, event_time, source, payload, updated_at
                FROM latest_signal_events
                {where}
                ORDER BY severity DESC, event_time DESC, ticker ASC
                LIMIT %s
                """,
                params,
            ).fetchall()

        return pd.DataFrame(
            [
                {
                    "Event_ID": row[0],
                    "Market": row[1],
                    "Ticker": row[2],
                    "Name": row[3],
                    "Kind": row[4],
                    "Severity": row[5],
                    "Title": row[6],
                    "Detail": row[7],
                    "Metric_Label": row[8],
                    "Metric_Value": row[9],
                    "Event_Time": row[10].isoformat() if row[10] else None,
                    "Source": row[11],
                    "Payload": dict(row[12] or {}),
                    "Updated_At": row[13].isoformat() if row[13] else None,
                }
                for row in rows
            ]
        )

    def latest_price_date(self, ticker: str, market: str | None = None) -> date | None:
        self.ensure_schema()
        normal = _normal_ticker(ticker)
        if not normal:
            return None
        params = [normal]
        market_filter = ""
        if market:
            market_filter = "AND market = %s"
            params.append(market.upper())

        with self._connect() as conn:
            row = conn.execute(
                f"""
                SELECT max(price_date)
                FROM price_ohlcv
                WHERE ticker = %s {market_filter}
                """,
                params,
            ).fetchone()
        return row[0] if row and row[0] else None

    def upsert_market_indicators(self, points: Iterable[dict], source: str = "yfinance") -> int:
        from psycopg.types.json import Jsonb

        rows = []
        for point in points:
            symbol = str(point.get("symbol") or "").strip().upper()
            observed_at = _to_datetime(point.get("observed_at") or point.get("timestamp") or point.get("updated_at"))
            value = _to_float(point.get("value") or point.get("close"))
            if not symbol or observed_at is None or value is None:
                continue
            rows.append((
                symbol,
                observed_at,
                str(point.get("label") or symbol).strip(),
                str(point.get("category") or "index_fx").strip(),
                str(point.get("region") or "global").strip(),
                value,
                _to_float(point.get("change_abs")),
                _to_float(point.get("change_pct")),
                _to_float(point.get("open")),
                _to_float(point.get("high")),
                _to_float(point.get("low")),
                _to_float(point.get("close")),
                _to_float(point.get("volume")),
                str(point.get("source") or source).strip() or source,
                Jsonb(point),
            ))

        if not rows:
            return 0

        self.ensure_schema()
        with self._connect() as conn:
            with conn.cursor() as cur:
                cur.executemany(
                    """
                    INSERT INTO market_indicator_ticks
                        (symbol, observed_at, label, category, region, value,
                         change_abs, change_pct, open, high, low, close, volume, source, payload)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT (symbol, observed_at)
                    DO UPDATE SET label = excluded.label,
                                  category = excluded.category,
                                  region = excluded.region,
                                  value = excluded.value,
                                  change_abs = excluded.change_abs,
                                  change_pct = excluded.change_pct,
                                  open = excluded.open,
                                  high = excluded.high,
                                  low = excluded.low,
                                  close = excluded.close,
                                  volume = excluded.volume,
                                  source = excluded.source,
                                  payload = excluded.payload,
                                  updated_at = now()
                    """,
                    rows,
                )
        return len(rows)

    def read_market_indicator_latest(self, symbols: Iterable[str] | None = None) -> pd.DataFrame:
        self.ensure_schema()
        wanted = [str(symbol).strip().upper() for symbol in (symbols or []) if str(symbol).strip()]
        params: list = []
        symbol_filter = ""
        if wanted:
            symbol_filter = "WHERE symbol = ANY(%s)"
            params.append(wanted)

        with self._connect() as conn:
            rows = conn.execute(
                f"""
                SELECT DISTINCT ON (symbol)
                    symbol, observed_at, label, category, region, value,
                    change_abs, change_pct, open, high, low, close, volume, source
                FROM market_indicator_ticks
                {symbol_filter}
                ORDER BY symbol, observed_at DESC
                """,
                params,
            ).fetchall()

        return _market_indicator_rows_to_frame(rows)

    def read_market_indicator_history(self, symbols: Iterable[str], start_at: datetime) -> pd.DataFrame:
        self.ensure_schema()
        wanted = [str(symbol).strip().upper() for symbol in symbols if str(symbol).strip()]
        if not wanted:
            return pd.DataFrame()

        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT symbol, observed_at, label, category, region, value,
                       change_abs, change_pct, open, high, low, close, volume, source
                FROM market_indicator_ticks
                WHERE symbol = ANY(%s)
                  AND observed_at >= %s
                ORDER BY symbol ASC, observed_at ASC
                """,
                (wanted, start_at),
            ).fetchall()

        return _market_indicator_rows_to_frame(rows)


def _market_indicator_rows_to_frame(rows) -> pd.DataFrame:
    if not rows:
        return pd.DataFrame()
    return pd.DataFrame(
        [
            {
                "symbol": row[0],
                "observed_at": row[1].isoformat() if hasattr(row[1], "isoformat") else row[1],
                "label": row[2],
                "category": row[3],
                "region": row[4],
                "value": row[5],
                "change_abs": row[6],
                "change_pct": row[7],
                "open": row[8],
                "high": row[9],
                "low": row[10],
                "close": row[11],
                "volume": row[12],
                "source": row[13],
            }
            for row in rows
        ]
    )
