#!/usr/bin/env python3
"""Backfill quality-review columns on scored stock datasets.

Some staging snapshots were produced before the company-quality layer became
part of the scored stock contract. This repair keeps the existing rank and
score outputs intact, then fills the missing quality diagnostics from currently
available fundamentals.
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from pipeline.scoring.company_quality import (
    QUALITY_REVIEW_COLS,
    QUALITY_SCORE_COLS,
    add_company_quality_review_columns,
    compute_company_quality_scores,
)
from quantbridge.schemas import SHEET_SCHEMAS
from quantbridge.storage import QuantRepository
from quantbridge.writers.dual_write import dual_write_dataframe


MARKET_DATASETS = {
    "US": "US_Scored_Stocks",
    "KR": "KR_Scored_Stocks",
}


def _blank_mask(series: pd.Series) -> pd.Series:
    return series.isna() | series.astype(str).str.strip().eq("")


def _fill_column(target: pd.DataFrame, source: pd.DataFrame, column: str, *, force: bool = False) -> None:
    if column not in source.columns:
        return
    if column not in target.columns:
        target[column] = source[column]
        return
    mask = pd.Series(True, index=target.index) if force else _blank_mask(target[column])
    target.loc[mask, column] = source.loc[mask, column]


def _round_numeric(df: pd.DataFrame, columns: list[str]) -> None:
    for column in columns:
        if column in df.columns:
            df[column] = pd.to_numeric(df[column], errors="coerce").round(4)


def repair_scored_frame(scored: pd.DataFrame, market: str, *, force: bool = False) -> pd.DataFrame:
    """Return scored rows with the canonical quality columns populated."""

    market = str(market or "").strip().upper()
    dataset = MARKET_DATASETS.get(market)
    if not dataset:
        raise ValueError("market must be US or KR")

    schema = SHEET_SCHEMAS[dataset]
    if scored.empty:
        return pd.DataFrame(columns=schema)

    out = scored.copy().reset_index(drop=True)
    out["Market"] = market
    if "Last_Updated" not in out.columns or _blank_mask(out["Last_Updated"]).all():
        out["Last_Updated"] = datetime.utcnow().strftime("%Y-%m-%d")
    if market == "US":
        if "ML_Score" not in out.columns:
            out["ML_Score"] = ""
        if "Combined_Score" not in out.columns:
            out["Combined_Score"] = out.get("Final_Score", out.get("Total_Score", ""))

    computed = compute_company_quality_scores(out)
    computed = add_company_quality_review_columns(computed, rank_col="Rank")
    for column in [*QUALITY_SCORE_COLS, *QUALITY_REVIEW_COLS]:
        _fill_column(out, computed, column, force=force)

    if "Quality_Red_Flags" in out.columns:
        out["Quality_Red_Flags"] = out["Quality_Red_Flags"].fillna("").astype(str)
    if "Quality_Category" in out.columns:
        out["Quality_Category"] = out["Quality_Category"].fillna("").astype(str)

    numeric_columns = [
        "Rank",
        "MarketCap",
        "Value_Score",
        "Quality_Score",
        "Momentum_Score",
        "Total_Score",
        "Final_Score",
        "Score_Neutral",
        "ML_Score",
        "Combined_Score",
        "Profitability_Quality",
        "Cash_Quality",
        "Growth_Quality",
        "BalanceSheet_Strength",
        "Valuation_Discipline",
        "Timing_Overlay",
        "Persistence_Quality",
        "Business_Quality_Score",
        "Investability_Score",
        "Quality_Data_Confidence",
        "Investability_Rank",
        "Business_Quality_Rank",
        "Quality_Rank_Delta",
        "ROIC",
        "RevGrowth",
        "GrossMargin",
        "FCF_Margin",
        "Debt_EBITDA",
        "PEG",
    ]
    _round_numeric(out, numeric_columns)

    for column in schema:
        if column not in out.columns:
            out[column] = ""
    return out[schema].copy()


def _missing_columns(df: pd.DataFrame, dataset: str) -> list[str]:
    return [column for column in SHEET_SCHEMAS[dataset] if column not in df.columns]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Repair scored stock quality schema")
    parser.add_argument("--markets", default="US,KR", help="Comma-separated markets to repair")
    parser.add_argument("--snapshot-date", default="", help="Optional repository snapshot date")
    parser.add_argument("--force", action="store_true", help="Recompute quality columns even when values already exist")
    parser.add_argument("--no-write-repository", action="store_true", help="Do not write repaired rows")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    markets = [item.strip().upper() for item in str(args.markets).split(",") if item.strip()]
    repo = QuantRepository()

    print("\n" + "=" * 65)
    print("  SCORED QUALITY SCHEMA REPAIR")
    print("=" * 65)

    repaired_any = False
    for market in markets:
        dataset = MARKET_DATASETS.get(market)
        if not dataset:
            raise SystemExit(f"Unsupported market: {market}")
        scored = repo.read_dataframe(dataset, market=market)
        before_missing = _missing_columns(scored, dataset)
        repaired = repair_scored_frame(scored, market, force=args.force)
        after_missing = _missing_columns(repaired, dataset)
        repaired_any = repaired_any or bool(before_missing)
        print(
            f"[repair-scored] {dataset}: rows={len(repaired)} "
            f"missing_before={len(before_missing)} missing_after={len(after_missing)}"
        )
        if before_missing:
            print(f"[repair-scored] filled: {', '.join(before_missing)}")
        if not args.no_write_repository and not repaired.empty:
            dual_write_dataframe(
                dataset,
                repaired,
                market=market,
                snapshot_date=args.snapshot_date.strip() or None,
            )

    if not repaired_any:
        print("[repair-scored] scored schemas already complete")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
