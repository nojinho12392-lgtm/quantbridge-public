#!/usr/bin/env python3
"""Validate quality signals from local snapshots and prices CSV files."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from pipeline.research.quality_signal_validation import (
    QUALITY_SIGNAL_COLS,
    QualityValidationConfig,
    validate_quality_signals,
)


def _parse_horizons(text: str) -> dict[str, int] | None:
    if not text.strip():
        return None
    out: dict[str, int] = {}
    for part in text.split(","):
        label, _, days = part.partition("=")
        if label.strip() and days.strip():
            out[label.strip()] = int(days)
    return out or None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate quality signal IC from local CSV inputs")
    parser.add_argument("--snapshots-csv", required=True, help="CSV with Snapshot_Date,Ticker and signal columns")
    parser.add_argument("--prices-csv", required=True, help="Wide close-price CSV with Date column or date index")
    parser.add_argument("--signals", default=",".join(QUALITY_SIGNAL_COLS), help="Comma-separated signal columns")
    parser.add_argument("--horizons", default="1M=21,3M=63,6M=126")
    parser.add_argument("--min-obs", type=int, default=20)
    parser.add_argument("--output-summary", default="")
    parser.add_argument("--output-detail", default="")
    return parser.parse_args()


def _read_prices(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path)
    date_col = "Date" if "Date" in df.columns else df.columns[0]
    df[date_col] = pd.to_datetime(df[date_col], errors="coerce")
    df = df.dropna(subset=[date_col]).set_index(date_col)
    return df


def main() -> int:
    args = parse_args()
    snapshots = pd.read_csv(Path(args.snapshots_csv).expanduser())
    prices = _read_prices(Path(args.prices_csv).expanduser())
    signals = [part.strip() for part in args.signals.split(",") if part.strip()]
    summary, detail = validate_quality_signals(
        snapshots,
        prices,
        signal_cols=signals,
        config=QualityValidationConfig(horizons=_parse_horizons(args.horizons), min_obs=args.min_obs),
    )

    print(f"[quality-validation] summary rows={len(summary)} detail rows={len(detail)}")
    if not summary.empty:
        printable = summary.copy()
        for col in ["Mean_IC", "Median_IC", "Positive_IC_Rate", "Mean_Top_Bottom_Spread", "Mean_Hit_Rate"]:
            printable[col] = pd.to_numeric(printable[col], errors="coerce").round(4)
        print(printable.to_string(index=False))

    if args.output_summary:
        out = Path(args.output_summary).expanduser()
        out.parent.mkdir(parents=True, exist_ok=True)
        summary.to_csv(out, index=False)
        print(f"[quality-validation] wrote summary: {out}")
    if args.output_detail:
        out = Path(args.output_detail).expanduser()
        out.parent.mkdir(parents=True, exist_ok=True)
        detail.to_csv(out, index=False)
        print(f"[quality-validation] wrote detail: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
