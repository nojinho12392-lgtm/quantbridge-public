"""Helpers for writing pipeline outputs to the new storage layer.

Google Sheets remains the report/viewer surface during migration. Pipeline
steps should call this helper after their existing sheet write so PostgreSQL
and Parquet can be enabled independently from environment settings.
"""

from __future__ import annotations

from typing import Any

import pandas as pd

from quantbridge.storage import QuantRepository


def _records_from_frame(df: pd.DataFrame) -> list[dict[str, Any]]:
    clean = df.copy()
    clean = clean.astype(object).where(pd.notna(clean), None)
    return clean.to_dict(orient="records")


def dual_write_dataframe(
    dataset: str,
    df: pd.DataFrame,
    *,
    market: str | None = None,
    snapshot_date: str | None = None,
) -> dict | None:
    """Best-effort dual-write that never breaks the legacy sheet pipeline."""

    try:
        result = QuantRepository().write_records(
            dataset=dataset,
            records=_records_from_frame(df),
            market=market,
            snapshot_date=snapshot_date,
        )
    except Exception as exc:
        print(f"  ⚠️  storage dual-write skipped for {dataset}: {type(exc).__name__}: {exc}")
        return None

    targets = []
    if result.get("postgres_rows"):
        targets.append(f"postgres={result['postgres_rows']}")
    if result.get("parquet_path"):
        targets.append(f"parquet={result['parquet_path']}")
    suffix = " | " + ", ".join(targets) if targets else ""
    print(f"  🗄️  storage dual-write complete: {dataset} rows={result['rows']}{suffix}")
    return result


def dual_write_key_values(
    dataset: str,
    rows: list[list[Any]],
    *,
    market: str | None = None,
    key_col: str = "Key",
    value_col: str = "Value",
) -> dict | None:
    records = []
    for row in rows:
        if len(row) < 2:
            continue
        key = str(row[0] or "").strip()
        if not key:
            continue
        records.append({key_col: key, value_col: row[1], "Market": market or "GLOBAL"})
    return dual_write_dataframe(dataset, pd.DataFrame(records), market=market)
