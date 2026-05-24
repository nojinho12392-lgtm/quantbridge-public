#!/usr/bin/env python3
"""
Merge KR small-cap shard CSVs and publish KR_SmallCap_Gems once.
"""

from __future__ import annotations

import argparse
import glob
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import gspread
import pandas as pd

from quantbridge.writers.dual_write import dual_write_dataframe
from sheets_client import get_spreadsheet


TOP_N = 20
SHEET_NAME = "KR_SmallCap_Gems"
SMALLCAP_COLS = [
    "Rank", "Ticker", "Name", "Market", "MarketCap",
    "ROIC", "RevGrowth", "Rev_Accel", "GrossMargin", "FCF_Margin",
    "Debt_EBITDA", "Insider_Pct", "Net_Cash_Ratio",
    "Volume_Surge", "SmallCap_Bonus", "Data_Confidence", "Total_Score", "Last_Updated",
]


def expand_inputs(patterns: list[str]) -> list[str]:
    files: list[str] = []
    for pattern in patterns:
        matches = sorted(glob.glob(pattern))
        files.extend(matches if matches else [pattern])
    return [path for path in files if os.path.exists(path)]


def load_shards(paths: list[str]) -> pd.DataFrame:
    frames = []
    for path in paths:
        try:
            df = pd.read_csv(path)
        except pd.errors.EmptyDataError:
            continue
        if df.empty:
            continue
        df["Source_File"] = os.path.basename(path)
        frames.append(df)
    if not frames:
        return pd.DataFrame(columns=SMALLCAP_COLS)
    merged = pd.concat(frames, ignore_index=True)
    if "Ticker" in merged.columns:
        merged = merged[merged["Ticker"].astype(str).str.strip() != ""]
    return merged


def rank_results(df: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    if df.empty:
        empty = pd.DataFrame(columns=SMALLCAP_COLS)
        return empty, empty

    if "Total_Score" not in df.columns:
        df["Total_Score"] = float("-inf")
    for col in ("Total_Score", "MarketCap"):
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    df["Total_Score"] = df["Total_Score"].fillna(float("-inf"))
    df = df.sort_values("Total_Score", ascending=False)
    if "Ticker" in df.columns:
        df = df.drop_duplicates(subset=["Ticker"], keep="first")
    df = df.reset_index(drop=True)
    df["Rank"] = df.index + 1
    today = pd.Timestamp.now().strftime("%Y-%m-%d")
    df["Last_Updated"] = today

    for col in SMALLCAP_COLS:
        if col not in df.columns:
            df[col] = ""
    ranked = df[SMALLCAP_COLS].copy()
    top = ranked.head(TOP_N).copy()
    return ranked, top


def get_or_create(spreadsheet, name: str, rows: int = 300, cols: int = 20):
    try:
        return spreadsheet.worksheet(name)
    except gspread.exceptions.WorksheetNotFound:
        return spreadsheet.add_worksheet(title=name, rows=rows, cols=cols)


def write_sheet(top: pd.DataFrame, valid_count: int, shard_count: int) -> None:
    spreadsheet = get_spreadsheet()
    ws = get_or_create(spreadsheet, SHEET_NAME)
    ws.clear()

    now = pd.Timestamp.now().strftime("%Y-%m-%d %H:%M")
    summary = [
        [""],
        ["== KR_SmallCap_Gems =="],
        ["Generated", now],
        ["Mode", "Merged from KR smallcap shards"],
        ["Shard Files", str(shard_count)],
        ["Universe", "KOSDAQ Full + KOSPI Bottom-30%"],
        ["MarketCap Filter", "1000억 ~ 10조 KRW"],
        ["ETF/채권 필터", "ETF 브랜드·상품 키워드 및 재무지표 전무 종목 제외"],
        ["Valid Stocks", str(valid_count)],
        [
            "Top Stock",
            (
                f"{top['Ticker'].iloc[0]} (score {top['Total_Score'].iloc[0]:.1f})"
                if not top.empty else "N/A"
            ),
        ],
        ["Expected CAGR", "25~40%  (KR small-cap growth, historical)"],
        ["Scoring", "ROIC+RevGrowth+RuleOf40+GrossMargin+FCF+Debt+RevAccel+Insider+NetCash+ShortInterest+VolSurge+SmallCapBonus"],
    ]

    if top.empty:
        ws.update(summary)
        print(f"WARNING: {SHEET_NAME}: no merged results; summary written only")
        return

    df_out = top[SMALLCAP_COLS].copy()
    for col in df_out.select_dtypes(include=[float]).columns:
        df_out[col] = df_out[col].round(4)
    sheet_out = df_out.fillna("").astype(str)
    ws.update([sheet_out.columns.tolist()] + sheet_out.values.tolist() + summary)
    dual_write_dataframe(SHEET_NAME, df_out, market="KR")
    print(f"Published {SHEET_NAME}: {len(df_out)} rows")


def main() -> int:
    parser = argparse.ArgumentParser(description="Merge KR smallcap shard CSV files")
    parser.add_argument("inputs", nargs="+", help="CSV files or glob patterns")
    parser.add_argument(
        "--output",
        default="artifacts/KR_SmallCap_Gems_merged.csv",
        help="Merged ranked CSV output path",
    )
    args = parser.parse_args()

    paths = expand_inputs(args.inputs)
    if not paths:
        print("No shard CSV files found", file=sys.stderr)
        return 2

    df = load_shards(paths)
    ranked, top = rank_results(df)
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    ranked.to_csv(args.output, index=False)
    print(f"Merged {len(paths)} shard files; valid rows={len(ranked)}")
    print(f"Merged CSV: {args.output}")

    write_sheet(top, valid_count=len(ranked), shard_count=len(paths))
    return 0


if __name__ == "__main__":
    sys.exit(main())
