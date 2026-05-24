"""Dual-write repository: PostgreSQL for service data, Parquet for research."""

from __future__ import annotations

from datetime import datetime
from typing import Iterable

import pandas as pd

from quantbridge.config import Settings, get_settings

from .parquet import ParquetLake
from .postgres import PostgresStore


class QuantRepository:
    def __init__(self, settings: Settings | None = None):
        self.settings = settings or get_settings()
        self.parquet = ParquetLake(self.settings.data_lake_dir)
        self.postgres = PostgresStore(self.settings.database_url)

    def write_records(
        self,
        dataset: str,
        records: Iterable[dict],
        market: str | None = None,
        snapshot_date: str | None = None,
    ) -> dict:
        rows = list(records)
        result = {"dataset": dataset, "rows": len(rows), "postgres_rows": 0, "parquet_path": None}

        if self.settings.enable_postgres:
            result["postgres_rows"] = self.postgres.write_records(dataset, rows, market, snapshot_date)

        if self.settings.enable_parquet:
            path = self.parquet.write_records(dataset, rows, market, snapshot_date)
            result["parquet_path"] = str(path) if path else None

        return result

    def record_run(self, run_id: str, runner: str, status: str, payload: dict | None = None) -> None:
        if self.settings.enable_postgres:
            self.postgres.record_run(run_id, runner, status, payload)

    def read_pipeline_runs(self, limit: int = 50) -> pd.DataFrame:
        if not self.settings.enable_postgres:
            return pd.DataFrame()
        try:
            return self.postgres.read_runs(limit=limit)
        except Exception as exc:
            print(f"  ⚠️  postgres run read skipped: {type(exc).__name__}: {exc}")
            return pd.DataFrame()

    def upsert_app_api_snapshot(
        self,
        cache_key: str,
        payload: object,
        source: str = "api_precompute",
    ) -> None:
        if self.settings.enable_postgres:
            self.postgres.upsert_app_api_snapshot(cache_key, payload, source=source)

    def read_app_api_snapshot(self, cache_key: str, max_age_seconds: int | None = None) -> object | None:
        if not self.settings.enable_postgres:
            return None
        try:
            return self.postgres.read_app_api_snapshot(cache_key, max_age_seconds=max_age_seconds)
        except Exception as exc:
            print(f"  ⚠️  postgres app snapshot read skipped for {cache_key}: {type(exc).__name__}: {exc}")
            return None

    def read_dataframe(self, dataset: str, market: str | None = None) -> pd.DataFrame:
        """Read the latest snapshot, preferring PostgreSQL and falling back to Parquet."""

        if self.settings.enable_postgres:
            try:
                df = self.postgres.read_latest(dataset, market=market)
                if not df.empty:
                    return self._order_frame(df)
            except Exception as exc:
                print(f"  ⚠️  postgres read skipped for {dataset}: {type(exc).__name__}: {exc}")

        if self.settings.enable_parquet:
            return self._order_frame(self.parquet.read_latest(dataset, market=market))

        return pd.DataFrame()

    def read_records(self, dataset: str, market: str | None = None) -> list[dict]:
        df = self.read_dataframe(dataset, market=market)
        if df.empty:
            return []
        clean = df.astype(object).where(pd.notna(df), None)
        return clean.to_dict(orient="records")

    def read_previous_ranks(self, dataset: str, market: str | None = None) -> pd.DataFrame:
        if not self.settings.enable_postgres:
            return pd.DataFrame()
        try:
            return self.postgres.read_previous_ranks(dataset, market=market)
        except Exception as exc:
            print(f"  ⚠️  postgres previous-rank read skipped for {dataset}: {type(exc).__name__}: {exc}")
            return pd.DataFrame()

    def upsert_identity(self, identity: dict) -> None:
        if self.settings.enable_postgres:
            self.postgres.upsert_identity(identity)

    def read_identity(self, ticker: str, market: str | None = None) -> dict | None:
        if not self.settings.enable_postgres:
            return None
        try:
            return self.postgres.read_identity(ticker, market=market)
        except Exception as exc:
            print(f"  ⚠️  postgres identity read skipped for {ticker}: {type(exc).__name__}: {exc}")
            return None

    def upsert_prices(self, ticker: str, market: str, prices: Iterable[dict], source: str = "yfinance") -> int:
        if not self.settings.enable_postgres:
            return 0
        return self.postgres.upsert_prices(ticker, market, prices, source=source)

    def read_prices(self, ticker: str, period: str = "6mo", market: str | None = None) -> pd.DataFrame:
        if not self.settings.enable_postgres:
            return pd.DataFrame()
        try:
            return self.postgres.read_prices(ticker, period=period, market=market)
        except Exception as exc:
            print(f"  ⚠️  postgres price read skipped for {ticker}: {type(exc).__name__}: {exc}")
            return pd.DataFrame()

    def read_prices_batch(
        self,
        tickers: Iterable[str],
        period: str = "6mo",
        market: str | None = None,
    ) -> pd.DataFrame:
        if not self.settings.enable_postgres:
            return pd.DataFrame()
        try:
            return self.postgres.read_prices_batch(tickers, period=period, market=market)
        except Exception as exc:
            print(f"  ⚠️  postgres batch price read skipped: {type(exc).__name__}: {exc}")
            return pd.DataFrame()

    def upsert_price_metrics(self, metrics: Iterable[dict], source: str = "yfinance") -> int:
        if not self.settings.enable_postgres:
            return 0
        return self.postgres.upsert_price_metrics(metrics, source=source)

    def read_price_metrics(self, tickers: Iterable[str], market: str | None = None) -> pd.DataFrame:
        if not self.settings.enable_postgres:
            return pd.DataFrame()
        try:
            return self.postgres.read_price_metrics(tickers, market=market)
        except Exception as exc:
            print(f"  ⚠️  postgres latest price metric read skipped: {type(exc).__name__}: {exc}")
            return pd.DataFrame()

    def replace_signal_events(self, source: str, events: Iterable[dict]) -> int:
        if not self.settings.enable_postgres:
            return 0
        return self.postgres.replace_signal_events(source, events)

    def read_signal_events(
        self,
        market: str | None = None,
        tickers: Iterable[str] | None = None,
        kinds: Iterable[str] | None = None,
        limit: int = 100,
    ) -> pd.DataFrame:
        if not self.settings.enable_postgres:
            return pd.DataFrame()
        try:
            return self.postgres.read_signal_events(market=market, tickers=tickers, kinds=kinds, limit=limit)
        except Exception as exc:
            print(f"  ⚠️  postgres signal event read skipped: {type(exc).__name__}: {exc}")
            return pd.DataFrame()

    def upsert_market_indicators(self, points: Iterable[dict], source: str = "yfinance") -> int:
        if not self.settings.enable_postgres:
            return 0
        return self.postgres.upsert_market_indicators(points, source=source)

    def read_market_indicator_latest(self, symbols: Iterable[str] | None = None) -> pd.DataFrame:
        if not self.settings.enable_postgres:
            return pd.DataFrame()
        try:
            return self.postgres.read_market_indicator_latest(symbols=symbols)
        except Exception as exc:
            print(f"  ⚠️  postgres market indicator latest read skipped: {type(exc).__name__}: {exc}")
            return pd.DataFrame()

    def read_market_indicator_history(self, symbols: Iterable[str], start_at: datetime) -> pd.DataFrame:
        if not self.settings.enable_postgres:
            return pd.DataFrame()
        try:
            return self.postgres.read_market_indicator_history(symbols=symbols, start_at=start_at)
        except Exception as exc:
            print(f"  ⚠️  postgres market indicator history read skipped: {type(exc).__name__}: {exc}")
            return pd.DataFrame()

    @staticmethod
    def _order_frame(df: pd.DataFrame) -> pd.DataFrame:
        if df.empty or "Rank" not in df.columns:
            return df
        out = df.copy()
        rank = pd.to_numeric(out["Rank"], errors="coerce")
        out["_rank_order"] = rank
        out = out.sort_values("_rank_order", na_position="last").drop(columns=["_rank_order"])
        return out.reset_index(drop=True)
