#!/usr/bin/env python3
"""Print a read-only Company Quality Core review report.

Examples:
    python tools/company_quality_report.py --market US
    python tools/company_quality_report.py --input-csv my_scored_stocks.csv
    python tools/company_quality_report.py --market KR --category "Risk Review"
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from pipeline.scoring.company_quality_report import (
    QualityReportConfig,
    build_company_quality_report,
)
from quantbridge.config import get_settings
from quantbridge.storage import QuantRepository


def _read_sheet(dataset: str) -> pd.DataFrame:
    from sheets_client import get_spreadsheet

    rows = get_spreadsheet().worksheet(dataset).get_all_values()
    if not rows or len(rows) < 2:
        return pd.DataFrame()
    return pd.DataFrame(rows[1:], columns=rows[0])


def _read_dataset(dataset: str, market: str | None, source: str) -> pd.DataFrame:
    if source in {"auto", "repository"}:
        df = QuantRepository().read_dataframe(dataset, market=market)
        if not df.empty or source == "repository":
            return df

    if source in {"auto", "sheets"}:
        settings = get_settings()
        if not settings.has_google_credentials:
            return pd.DataFrame()
        return _read_sheet(dataset)

    return pd.DataFrame()


def _print_human(report: pd.DataFrame, label: str) -> None:
    print(f"\n[company-quality] {label} rows={len(report)}")
    if report.empty:
        print("  No rows found. Provide --input-csv, enable repository data, or configure Google Sheets credentials.")
        return

    view_cols = [
        "Investability_Rank",
        "Ticker",
        "Name",
        "Sector",
        "Quality_Category",
        "Business_Quality_Score",
        "Investability_Score",
        "Persistence_Quality",
        "Quality_Data_Confidence",
        "Quality_Red_Flags",
        "Quality_Rank_Delta",
    ]
    printable = report[view_cols].copy()
    for col in [
        "Business_Quality_Score",
        "Investability_Score",
        "Persistence_Quality",
        "Quality_Data_Confidence",
    ]:
        printable[col] = pd.to_numeric(printable[col], errors="coerce").round(4)
    print(printable.to_string(index=False))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build a read-only Company Quality Core report")
    parser.add_argument("--market", choices=["US", "KR", "ALL"], default="ALL")
    parser.add_argument("--source", choices=["auto", "repository", "sheets"], default="auto")
    parser.add_argument("--input-csv", default="", help="Optional scored-stocks CSV path")
    parser.add_argument("--limit", type=int, default=25)
    parser.add_argument("--min-confidence", type=float, default=0.45)
    parser.add_argument("--category", default="", help="Filter by Quality_Category")
    parser.add_argument("--output-csv", default="", help="Optional path to write the report CSV")
    parser.add_argument("--json", action="store_true", help="Print JSON instead of a table")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    cfg = QualityReportConfig(
        limit=args.limit,
        min_confidence=args.min_confidence,
        category=args.category.strip() or None,
    )

    reports: list[pd.DataFrame] = []
    labels: list[str] = []

    if args.input_csv:
        source = Path(args.input_csv).expanduser()
        df = pd.read_csv(source)
        report = build_company_quality_report(
            df,
            market=None if args.market == "ALL" else args.market,
            config=cfg,
        )
        reports.append(report)
        labels.append(str(source))
    else:
        markets = ["US", "KR"] if args.market == "ALL" else [args.market]
        for market in markets:
            dataset = f"{market}_Scored_Stocks"
            df = _read_dataset(dataset, market, args.source)
            report = build_company_quality_report(df, market=market, config=cfg)
            reports.append(report)
            labels.append(dataset)

    combined = pd.concat(reports, ignore_index=True) if reports else pd.DataFrame()

    if args.output_csv:
        out = Path(args.output_csv).expanduser()
        out.parent.mkdir(parents=True, exist_ok=True)
        combined.to_csv(out, index=False)
        print(f"[company-quality] wrote {len(combined)} rows to {out}")

    if args.json:
        clean = combined.astype(object).where(pd.notna(combined), None)
        print(json.dumps(clean.to_dict(orient="records"), ensure_ascii=False, indent=2))
    else:
        if args.input_csv:
            _print_human(combined, labels[0] if labels else "input")
        else:
            for label, report in zip(labels, reports):
                _print_human(report, label)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
