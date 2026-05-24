"""
kr_sector_map.py
================
Persistent Korean sector/industry lookup for QuantBridge.

The KR pipeline cannot rely on yfinance for sector labels. This module keeps a
Google Sheets-backed `KR_Sector_Map` table and fills it from KR-native sources
when possible:

  1. existing/manual KR_Sector_Map rows
  2. pykrx KRX sector classifications
  3. FinanceDataReader listing columns, when available
  4. FnGuide Company Guide page (FICS industry + KRX market sector)

Rows with no reliable sector become `Unclassified` instead of blank, which makes
downstream scoring and dashboards explicit about missing classification.
"""

from __future__ import annotations

import re
import time
from datetime import datetime, timedelta
from typing import Iterable

import gspread
import pandas as pd
import requests
from bs4 import BeautifulSoup

SHEET_NAME = "KR_Sector_Map"
UNCLASSIFIED_SECTOR = "Unclassified"

SECTOR_MAP_COLS = [
    "Ticker", "Code", "Name", "Sector", "Industry",
    "Source", "Confidence", "Last_Updated",
]

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
    )
}

SOURCE_CONFIDENCE = {
    "Manual": 1.00,
    "FnGuide_FICS": 0.92,
    "pykrx": 0.86,
    "FinanceDataReader": 0.78,
    "Existing": 0.70,
    "Unclassified": 0.00,
}


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
        "Source": _clean(row.get("Source")) or "Existing",
        "Confidence": _clean(row.get("Confidence")),
        "Last_Updated": _clean(row.get("Last_Updated")),
    }


def _confidence(source: str, fallback: float = 0.0) -> float:
    return SOURCE_CONFIDENCE.get(source, fallback)


def _record_confidence(rec: dict) -> float:
    try:
        return float(rec.get("Confidence") or _confidence(rec.get("Source", "")))
    except Exception:
        return _confidence(rec.get("Source", ""))


def _should_replace(old: dict | None, new: dict) -> bool:
    if not old:
        return True
    if old.get("Source") == "Manual" and old.get("Sector") != UNCLASSIFIED_SECTOR:
        return False
    old_unclassified = normalize_sector(old.get("Sector")) == UNCLASSIFIED_SECTOR
    new_unclassified = normalize_sector(new.get("Sector")) == UNCLASSIFIED_SECTOR
    if old_unclassified and not new_unclassified:
        return True
    if new_unclassified and not old_unclassified:
        return False
    return _record_confidence(new) > _record_confidence(old)


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
    sector = normalize_sector(rec.get("Sector") or fallback)
    return sector


def _upsert(records: dict[str, dict], rec: dict) -> None:
    rec = _row_to_record(rec)
    if not rec["Code"]:
        return
    rec["Confidence"] = str(rec.get("Confidence") or _confidence(rec.get("Source", "")))
    rec["Last_Updated"] = rec.get("Last_Updated") or datetime.now().strftime("%Y-%m-%d")
    old = records.get(rec["Code"])
    if _should_replace(old, rec):
        records[rec["Code"]] = rec
        records[rec["Ticker"]] = rec


def _source_pykrx() -> dict[str, dict]:
    out = {}
    try:
        from pykrx import stock
    except Exception:
        return out

    date_candidates = [
        (datetime.now() - timedelta(days=i)).strftime("%Y%m%d")
        for i in range(0, 14)
    ]
    for date in date_candidates:
        for market in ("KOSPI", "KOSDAQ"):
            try:
                df = stock.get_market_sector_classifications(date, market)
            except Exception:
                continue
            if df is None or df.empty:
                continue
            idx_values = [str(x).zfill(6) for x in df.index]
            cols = {str(c): c for c in df.columns}
            name_col = next((cols[c] for c in cols if c in {"종목명", "Name", "회사명"}), None)
            sector_col = next((cols[c] for c in cols if c in {"업종명", "Sector", "업종"}), None)
            industry_col = next((cols[c] for c in cols if c in {"산업명", "Industry", "지수업종대분류"}), None)
            if sector_col is None and industry_col is None:
                continue
            for code, (_, row) in zip(idx_values, df.iterrows()):
                sector = row.get(sector_col, "") if sector_col is not None else ""
                industry = row.get(industry_col, "") if industry_col is not None else ""
                name = row.get(name_col, "") if name_col is not None else ""
                if normalize_sector(sector or industry) == UNCLASSIFIED_SECTOR:
                    continue
                out[code] = {
                    "Ticker": kr_ticker(code), "Code": code, "Name": name,
                    "Sector": sector or industry, "Industry": industry,
                    "Source": "pykrx", "Confidence": SOURCE_CONFIDENCE["pykrx"],
                }
            if out:
                return out
    return out


def _source_fdr() -> dict[str, dict]:
    out = {}
    try:
        import FinanceDataReader as fdr
    except Exception:
        return out
    for market in ("KOSPI", "KOSDAQ"):
        try:
            df = fdr.StockListing(market)
        except Exception:
            continue
        if df is None or df.empty:
            continue
        cols = {str(c): c for c in df.columns}
        code_col = next((cols[c] for c in cols if c in {"Code", "Symbol", "종목코드"}), None)
        name_col = next((cols[c] for c in cols if c in {"Name", "종목명"}), None)
        sector_col = next((cols[c] for c in cols if c in {"Sector", "업종", "업종명"}), None)
        industry_col = next((cols[c] for c in cols if c in {"Industry", "산업", "산업명"}), None)
        if code_col is None or (sector_col is None and industry_col is None):
            continue
        for _, row in df.iterrows():
            code = kr_code(row.get(code_col))
            sector = row.get(sector_col, "") if sector_col is not None else ""
            industry = row.get(industry_col, "") if industry_col is not None else ""
            if not code or normalize_sector(sector or industry) == UNCLASSIFIED_SECTOR:
                continue
            out[code] = {
                "Ticker": kr_ticker(code), "Code": code,
                "Name": row.get(name_col, "") if name_col is not None else "",
                "Sector": sector or industry, "Industry": industry,
                "Source": "FinanceDataReader",
                "Confidence": SOURCE_CONFIDENCE["FinanceDataReader"],
            }
    return out


def fetch_fnguide_sector(code: str, timeout: int = 10) -> dict:
    code = kr_code(code)
    if not code:
        return {}
    url = (
        "https://comp.fnguide.com/SVO2/ASP/SVD_Main.asp"
        f"?pGB=1&gicode=A{code}&cID=&MenuYn=Y&ReportGB=&NewMenuID=101&stkGb=701"
    )
    try:
        resp = requests.get(url, headers=HEADERS, timeout=timeout)
        if resp.status_code != 200:
            return {}
        soup = BeautifulSoup(resp.text, "html.parser")
        text = soup.get_text("\n", strip=True).replace("\xa0", " ")
        title = soup.title.get_text(" ", strip=True) if soup.title else ""
    except Exception:
        return {}

    name = ""
    title_match = re.match(r"(.+?)\(A?\d{6}\)", title)
    if title_match:
        name = _clean(title_match.group(1))

    sector = ""
    industry = ""
    market_match = re.search(r"(?:KSE|KOSDAQ|KOSPI)\s+(?:코스피|코스닥)\s+([^\n|]+)", text)
    if market_match:
        sector = _clean(market_match.group(1))
    fics_match = re.search(r"FICS\s+([^\n|]+)", text)
    if fics_match:
        industry = _clean(fics_match.group(1))

    if normalize_sector(sector or industry) == UNCLASSIFIED_SECTOR:
        return {}

    return {
        "Ticker": kr_ticker(code),
        "Code": code,
        "Name": name,
        "Sector": sector or industry,
        "Industry": industry,
        "Source": "FnGuide_FICS",
        "Confidence": SOURCE_CONFIDENCE["FnGuide_FICS"],
    }


def _write_map(spreadsheet, records: dict[str, dict]) -> None:
    unique = {}
    for key, rec in records.items():
        code = rec.get("Code")
        if code:
            unique[code] = _row_to_record(rec)

    rows = []
    for code in sorted(unique):
        rec = unique[code]
        rows.append([rec.get(col, "") for col in SECTOR_MAP_COLS])

    try:
        ws = spreadsheet.worksheet(SHEET_NAME)
    except gspread.exceptions.WorksheetNotFound:
        ws = spreadsheet.add_worksheet(title=SHEET_NAME, rows=max(1000, len(rows) + 50), cols=len(SECTOR_MAP_COLS) + 2)
    ws.clear()
    ws.update(range_name="A1", values=[SECTOR_MAP_COLS] + rows, value_input_option="RAW")


def update_kr_sector_map(
    spreadsheet,
    tickers: Iterable[str],
    names: dict[str, str] | None = None,
    fetch_missing: bool = True,
    max_fetch: int | None = None,
) -> dict[str, dict]:
    names = names or {}
    records = load_kr_sector_map(spreadsheet)

    for src in (_source_pykrx(), _source_fdr()):
        for rec in src.values():
            _upsert(records, rec)

    today = datetime.now().strftime("%Y-%m-%d")
    missing = []
    for ticker in tickers:
        code = kr_code(ticker)
        if not code:
            continue
        name = names.get(code) or names.get(ticker) or ""
        rec = records.get(code)
        if rec and normalize_sector(rec.get("Sector")) != UNCLASSIFIED_SECTOR:
            if name and not rec.get("Name"):
                rec["Name"] = name
            continue
        missing.append(code)
        _upsert(records, {
            "Ticker": kr_ticker(code),
            "Code": code,
            "Name": name,
            "Sector": UNCLASSIFIED_SECTOR,
            "Industry": "",
            "Source": "Unclassified",
            "Confidence": 0.0,
            "Last_Updated": today,
        })

    if fetch_missing:
        todo = list(dict.fromkeys(missing))
        if max_fetch is not None:
            todo = todo[:max_fetch]
        for i, code in enumerate(todo, 1):
            rec = fetch_fnguide_sector(code)
            if rec:
                if not rec.get("Name"):
                    rec["Name"] = names.get(code, "")
                _upsert(records, rec)
            if i % 50 == 0:
                print(f"  [KR Sector] FnGuide fetched {i}/{len(todo)} missing codes")
            time.sleep(0.15)

    _write_map(spreadsheet, records)
    return load_kr_sector_map(spreadsheet)
