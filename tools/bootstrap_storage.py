#!/usr/bin/env python3
"""Bootstrap PostgreSQL + Parquet from the current Google Sheets workbook.

This is the bridge for upgrade phase 1:
  - Google Sheets remains the visible report surface.
  - PostgreSQL becomes the service store when QUANT_ENABLE_POSTGRES=true.
  - Parquet becomes the research lake when QUANT_ENABLE_PARQUET=true.
"""

from __future__ import annotations

import sys
from pathlib import Path
from datetime import datetime

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quantbridge.schemas import STORAGE_SHEETS
from quantbridge.storage import QuantRepository
from sheets_client import get_spreadsheet


def _infer_market(ticker: str) -> str:
    normal = str(ticker or "").strip().upper()
    if normal.endswith((".KS", ".KQ")) or (len(normal) == 6 and normal.isdigit()):
        return "KR"
    return "US"


def _parse_date(value) -> str:
    text = str(value or "").strip()
    if not text or text.lower() in {"[]", "none", "nan", "nat"}:
        return ""
    try:
        return datetime.fromisoformat(text[:10]).date().isoformat()
    except ValueError:
        return ""


def _read_sheet_records(sheet_name: str, spreadsheet) -> list[dict]:
    ws = spreadsheet.worksheet(sheet_name)
    rows = ws.get_all_values()
    if not rows:
        return []

    if sheet_name == "Macro_Regime":
        records = []
        for row in rows:
            if len(row) < 2:
                continue
            key = str(row[0]).strip()
            if key:
                records.append({"Key": key, "Value": row[1], "Market": "GLOBAL"})
        return records

    if len(rows) < 2:
        return []

    header_idx = next(
        (
            i for i, row in enumerate(rows)
            if any(c.strip() in {"Ticker", "Rank", "Date", "Market", "Snapshot_Date", "Industry", "Factor"}
                   for c in row)
            and sum(1 for c in row if c.strip()) >= 2
        ),
        0,
    )
    headers = [str(c).strip() or f"Col_{idx}" for idx, c in enumerate(rows[header_idx])]
    width = len(headers)
    data_rows = [row[:width] + [""] * max(0, width - len(row)) for row in rows[header_idx + 1:]]
    df = pd.DataFrame(data_rows, columns=headers)
    if "Ticker" in df.columns:
        df = df[df["Ticker"].astype(str).str.strip() != ""]
    if sheet_name == "Earnings_Calendar":
        if "Next_Earnings_Date" not in df.columns:
            return []
        df["Ticker"] = df["Ticker"].astype(str).str.strip().str.upper()
        df["Next_Earnings_Date"] = df["Next_Earnings_Date"].map(_parse_date)
        df = df[df["Next_Earnings_Date"].astype(str).str.strip() != ""].copy()
        df["Market"] = df["Ticker"].map(_infer_market)
        df["Last_Updated"] = datetime.utcnow().date().isoformat()
    if "Rank" in df.columns:
        df = df[pd.to_numeric(df["Rank"], errors="coerce").notna()]
    if "Date" in df.columns:
        df = df[df["Date"].astype(str).str.strip() != ""]
    if "Industry" in df.columns:
        df = df[df["Industry"].astype(str).str.strip() != ""]
    return df.where(df.notna(), None).to_dict("records")


def main() -> None:
    repo = QuantRepository()
    spreadsheet = get_spreadsheet()
    for sheet_name in STORAGE_SHEETS:
        try:
            records = _read_sheet_records(sheet_name, spreadsheet)
        except Exception as exc:
            print(f"[BOOT] {sheet_name}: skipped ({exc})")
            continue
        market = "KR" if sheet_name.startswith("KR_") else "US" if sheet_name.startswith("US_") else None
        result = repo.write_records(sheet_name, records, market=market)
        print(f"[BOOT] {sheet_name}: {result}")


if __name__ == "__main__":
    main()
