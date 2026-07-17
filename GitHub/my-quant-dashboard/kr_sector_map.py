"""
Read-only KR sector lookup helpers for the Streamlit dashboard.

The main pipeline owns KR_Sector_Map creation/updating. The dashboard only needs
to read that sheet and fill blank KR sector labels in portfolio/search views.
"""

from __future__ import annotations

import re

SHEET_NAME = "KR_Sector_Map"
UNCLASSIFIED_SECTOR = "Unclassified"


def kr_code(value) -> str:
    value = str(value or "").strip().upper()
    if re.fullmatch(r"\d{1,6}", value):
        return value.zfill(6)
    match = re.search(r"(\d{6})", value)
    return match.group(1) if match else ""


def kr_ticker(code: str) -> str:
    code = kr_code(code)
    return f"{code}.KS" if code else ""


def _clean(value) -> str:
    return re.sub(r"\s+", " ", str(value or "").replace("\xa0", " ")).strip()


def normalize_sector(value) -> str:
    value = _clean(value)
    if not value or value in {"-", "None", "nan", "NaN"}:
        return UNCLASSIFIED_SECTOR
    return value


def _row_to_record(row: dict) -> dict:
    code = kr_code(row.get("Code") or row.get("Ticker"))
    ticker = str(row.get("Ticker") or kr_ticker(code)).strip()
    return {
        "Ticker": ticker,
        "Code": code,
        "Name": _clean(row.get("Name")),
        "Sector": normalize_sector(row.get("Sector")),
        "Industry": _clean(row.get("Industry")),
        "Source": _clean(row.get("Source")),
        "Confidence": _clean(row.get("Confidence")),
        "Last_Updated": _clean(row.get("Last_Updated")),
    }


def load_kr_sector_map(spreadsheet) -> dict[str, dict]:
    try:
        ws = spreadsheet.worksheet(SHEET_NAME)
        rows = ws.get_all_records()
    except Exception:
        return {}

    records = {}
    for raw in rows:
        rec = _row_to_record(raw)
        if not rec["Code"]:
            continue
        records[rec["Code"]] = rec
        records[rec["Ticker"]] = rec
    return records


def sector_for_ticker(ticker: str, sector_map: dict[str, dict], fallback: str = "") -> str:
    code = kr_code(ticker)
    rec = sector_map.get(str(ticker).strip()) or sector_map.get(code) or {}
    return normalize_sector(rec.get("Sector") or fallback)
