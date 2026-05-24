#!/usr/bin/env python3
"""Clean and mirror Earnings_Calendar from Sheets into service storage."""

from __future__ import annotations

import argparse
import re
import sys
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from io import StringIO
from pathlib import Path

import pandas as pd
import requests

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quantbridge.storage import QuantRepository
from sheets_client import get_spreadsheet


HEADERS = [
    "Ticker", "Next_Earnings_Date", "Market", "Name", "Sector", "MarketCap",
    "Last_Updated", "Visible_Reason",
]
KST = timezone(timedelta(hours=9))


def _kr_code(value: str) -> str:
    match = re.search(r"(\d{6})", str(value or "").strip().upper())
    return match.group(1) if match else ""


def _infer_market(ticker: str) -> str:
    normal = str(ticker or "").strip().upper()
    if normal.endswith((".KS", ".KQ")) or re.fullmatch(r"\d{6}", normal):
        return "KR"
    return "US"


def _normalize_us_ticker(ticker: str) -> str:
    return str(ticker or "").strip().upper().replace(".", "-")


def _sp500_tickers() -> set[str]:
    url = "https://en.wikipedia.org/wiki/List_of_S%26P_500_companies"
    resp = requests.get(url, headers={"User-Agent": "QuantBridge/1.0"}, timeout=12)
    resp.raise_for_status()
    table_match = re.search(
        r'<table[^>]*class="[^"]*wikitable[^"]*sortable[^"]*"[^>]*>(.*?)</table>',
        resp.text,
        flags=re.IGNORECASE | re.DOTALL,
    )
    if not table_match:
        raise ValueError("S&P 500 component table not found")
    symbols = re.findall(r'class="external text"[^>]*>\s*([A-Za-z.]+)\s*</a>', table_match.group(1))
    return {_normalize_us_ticker(symbol) for symbol in symbols if symbol}


def _portfolio_tickers(rows: list[dict]) -> set[str]:
    return {
        _normalize_us_ticker(row.get("Ticker") or "")
        for row in rows
        if str(row.get("Ticker") or "").strip()
    }


def _parse_date(value: str):
    text = str(value or "").strip()
    if not text or text.lower() in {"[]", "none", "nan", "nat"}:
        return None
    try:
        return datetime.fromisoformat(text[:10]).date()
    except ValueError:
        return None


def _to_float(value):
    try:
        text = str(value or "").replace(",", "").strip()
        return float(text) if text else None
    except (TypeError, ValueError):
        return None


def _market_cap_text_to_float(value):
    text = str(value or "").replace("$", "").replace(",", "").strip().upper()
    if not text or text == "-":
        return None
    match = re.match(r"^(-?\d+(?:\.\d+)?)([KMBT])?$", text)
    if not match:
        return _to_float(text)
    number = float(match.group(1))
    multiplier = {
        "K": 1e3,
        "M": 1e6,
        "B": 1e9,
        "T": 1e12,
        None: 1,
    }[match.group(2)]
    return number * multiplier


def _missing_name(name: str | None, ticker: str) -> bool:
    text = str(name or "").strip()
    normal = str(ticker or "").strip().upper()
    code = _kr_code(normal)
    if not text:
        return True
    if text.upper() == normal:
        return True
    if code and text == code:
        return True
    return bool(re.fullmatch(r"\d{6}(?:\.(?:KS|KQ))?", text.upper()))


def _read_sheet_records(spreadsheet, title: str) -> list[dict]:
    rows = spreadsheet.worksheet(title).get_all_values()
    if len(rows) < 2:
        return []
    header_idx = next(
        (
            idx for idx, row in enumerate(rows)
            if "Ticker" in {str(col).strip() for col in row}
        ),
        0,
    )
    headers = [str(col).strip() for col in rows[header_idx]]
    records = []
    for raw in rows[header_idx + 1:]:
        record = {headers[idx]: (raw[idx] if idx < len(raw) else "").strip() for idx in range(len(headers)) if headers[idx]}
        if record.get("Ticker"):
            records.append(record)
    return records


def _identity_lookup(company_rows: list[dict]) -> dict[str, dict]:
    lookup: dict[str, dict] = {}
    for row in company_rows:
        ticker = str(row.get("Ticker") or "").strip().upper()
        if not ticker:
            continue
        lookup[ticker] = row
        code = _kr_code(ticker)
        if code:
            lookup.setdefault(code, row)
    return lookup


def _naver_identity(code: str) -> dict:
    code = _kr_code(code)
    if not code:
        return {}
    url = f"https://polling.finance.naver.com/api/realtime/domestic/stock/{code}"
    try:
        resp = requests.get(url, headers={"User-Agent": "Mozilla/5.0"}, timeout=8)
        resp.raise_for_status()
        item = (resp.json().get("datas") or [{}])[0]
    except Exception:
        return {}
    exchange_info = item.get("stockExchangeType") or {}
    exchange = str(exchange_info.get("code") or item.get("stockExchangeName") or "").upper()
    suffix = ".KQ" if exchange in {"KQ", "KOSDAQ"} else ".KS"
    payload = {
        "Ticker": f"{code}{suffix}",
        "Name": str(item.get("stockName") or "").strip(),
        "MarketCap": _to_float(item.get("marketValueFullRaw")),
    }
    return {key: value for key, value in payload.items() if value not in (None, "")}


def _yahoo_day(day: str) -> list[dict]:
    url = f"https://finance.yahoo.com/calendar/earnings?day={day}&offset=0&size=100"
    try:
        resp = requests.get(url, timeout=10, headers={"User-Agent": "QuantBridge/1.0"})
        resp.raise_for_status()
        tables = pd.read_html(StringIO(resp.text))
    except Exception:
        return []

    records = []
    for table in tables:
        columns = {str(col).strip(): col for col in table.columns}
        if "Symbol" not in columns or "Company" not in columns:
            continue
        for _, row in table.iterrows():
            ticker = str(row.get(columns["Symbol"]) or "").strip().upper()
            if not ticker or ticker == "-":
                continue
            records.append({
                "Ticker": ticker,
                "Next_Earnings_Date": day,
                "Name": str(row.get(columns["Company"]) or "").strip(),
                "MarketCap": _market_cap_text_to_float(row.get(columns.get("Market Cap"))),
            })
    return records


def _yahoo_lookup(earnings_rows: list[dict], today, days: int, include_past: bool) -> dict[tuple[str, str], dict]:
    dates = set()
    end_date = today + timedelta(days=days)
    for row in earnings_rows:
        ticker = str(row.get("Ticker") or "").strip().upper()
        if _infer_market(ticker) != "US":
            continue
        event_date = _parse_date(row.get("Next_Earnings_Date"))
        if not event_date:
            continue
        if not include_past and event_date < today:
            continue
        if event_date > end_date:
            continue
        dates.add(event_date.isoformat())

    lookup: dict[tuple[str, str], dict] = {}
    with ThreadPoolExecutor(max_workers=4) as executor:
        futures = [executor.submit(_yahoo_day, day) for day in sorted(dates)]
        for future in as_completed(futures):
            for record in future.result():
                lookup[(record["Ticker"], record["Next_Earnings_Date"])] = record
    return lookup


def _yahoo_search(ticker: str) -> dict:
    try:
        resp = requests.get(
            "https://query1.finance.yahoo.com/v1/finance/search",
            params={"q": ticker, "quotesCount": 1, "newsCount": 0},
            headers={"User-Agent": "Mozilla/5.0"},
            timeout=8,
        )
        resp.raise_for_status()
        quotes = resp.json().get("quotes") or []
    except Exception:
        return {}
    for quote in quotes:
        if str(quote.get("symbol") or "").upper() != ticker.upper():
            continue
        name = quote.get("longname") or quote.get("shortname")
        return {
            "Name": str(name or "").strip(),
            "Sector": str(quote.get("sector") or quote.get("sectorDisp") or "").strip(),
            "Exchange": str(quote.get("exchDisp") or quote.get("exchange") or "").strip(),
        }
    return {}


def _yahoo_search_lookup(
    earnings_rows: list[dict],
    company_rows: list[dict],
    *,
    today,
    days: int,
    include_past: bool,
) -> dict[str, dict]:
    identities = _identity_lookup(company_rows)
    end_date = today + timedelta(days=days)
    tickers = set()
    for row in earnings_rows:
        ticker = str(row.get("Ticker") or "").strip().upper()
        event_date = _parse_date(row.get("Next_Earnings_Date"))
        if not ticker or _infer_market(ticker) != "US" or not event_date:
            continue
        if not include_past and event_date < today:
            continue
        if event_date > end_date:
            continue
        identity = identities.get(ticker) or {}
        name = str(row.get("Name") or identity.get("Name") or "").strip()
        sector = str(row.get("Sector") or identity.get("Sector") or "").strip()
        if _missing_name(name, ticker) or not sector:
            tickers.add(ticker)

    lookup: dict[str, dict] = {}
    with ThreadPoolExecutor(max_workers=6) as executor:
        futures = {executor.submit(_yahoo_search, ticker): ticker for ticker in sorted(tickers)}
        for future in as_completed(futures):
            ticker = futures[future]
            hit = future.result()
            if hit:
                lookup[ticker] = hit
    return lookup


def _build_records(
    earnings_rows: list[dict],
    company_rows: list[dict],
    *,
    today,
    days: int,
    include_past: bool,
    delay: float,
    yahoo: dict[tuple[str, str], dict],
    yahoo_search: dict[str, dict],
    allowed_us_tickers: set[str],
    portfolio_tickers: set[str],
    sp500_tickers: set[str],
) -> tuple[list[dict], dict]:
    identities = _identity_lookup(company_rows)
    end_date = today + timedelta(days=days)
    stats = Counter()
    naver_cache: dict[str, dict] = {}
    records: dict[tuple[str, str], dict] = {}

    for row in earnings_rows:
        ticker = str(row.get("Ticker") or "").strip().upper()
        event_date = _parse_date(row.get("Next_Earnings_Date"))
        if not ticker:
            continue
        if not event_date:
            stats["invalid_date"] += 1
            continue
        if not include_past and event_date < today:
            stats["past"] += 1
            continue
        if event_date > end_date:
            stats["outside_window"] += 1
            continue

        market = str(row.get("Market") or "").strip().upper() or _infer_market(ticker)
        visible_reason = ""
        if market == "US":
            normal_ticker = _normalize_us_ticker(ticker)
            if normal_ticker not in allowed_us_tickers:
                stats["filtered_us_not_portfolio_or_sp500"] += 1
                continue
            reasons = []
            if normal_ticker in portfolio_tickers:
                reasons.append("portfolio")
            if normal_ticker in sp500_tickers:
                reasons.append("sp500")
            visible_reason = "+".join(reasons) or "allowed"

        identity = identities.get(ticker) or identities.get(_kr_code(ticker) or "") or {}
        yahoo_identity = yahoo.get((ticker, event_date.isoformat())) or {}
        yahoo_search_identity = yahoo_search.get(ticker) or {}
        name = str(row.get("Name") or identity.get("Name") or "").strip()
        sector = str(row.get("Sector") or identity.get("Sector") or "").strip()
        market_cap = _to_float(row.get("MarketCap")) or _to_float(identity.get("MarketCap")) or _to_float(identity.get("MarketCap_Last"))

        if market == "US":
            if _missing_name(name, ticker):
                name = str(yahoo_identity.get("Name") or yahoo_search_identity.get("Name") or name or "").strip()
            if not sector:
                sector = str(yahoo_search_identity.get("Sector") or sector or "").strip()
            if market_cap is None:
                market_cap = _to_float(yahoo_identity.get("MarketCap"))

        if market == "KR" and (_missing_name(name, ticker) or market_cap is None or abs(market_cap) < 1_000_000):
            code = _kr_code(ticker)
            if code not in naver_cache:
                naver_cache[code] = _naver_identity(code)
                time.sleep(max(delay, 0))
            naver = naver_cache.get(code) or {}
            if _missing_name(name, ticker):
                name = str(naver.get("Name") or name or ticker).strip()
            market_cap = _to_float(naver.get("MarketCap")) or market_cap

        if not name:
            name = ticker

        records[(ticker, event_date.isoformat())] = {
            "Ticker": ticker,
            "Next_Earnings_Date": event_date.isoformat(),
            "Market": market,
            "Name": name,
            "Sector": sector,
            "MarketCap": market_cap,
            "Last_Updated": today.isoformat(),
            "Visible_Reason": visible_reason,
        }

    ordered = sorted(records.values(), key=lambda item: (item["Next_Earnings_Date"], item["Market"], item["Ticker"]))
    summary = dict(stats)
    summary["naver_lookups"] = len(naver_cache)
    summary["yahoo_rows"] = len(yahoo)
    summary["yahoo_search_rows"] = len(yahoo_search)
    return ordered, summary


def _update_sheet(spreadsheet, records: list[dict]) -> None:
    rows = [HEADERS]
    for record in records:
        rows.append([record.get(header, "") for header in HEADERS])
    ws = spreadsheet.worksheet("Earnings_Calendar")
    ws.clear()
    ws.update(range_name="A1", values=rows, value_input_option="USER_ENTERED")


def main() -> int:
    parser = argparse.ArgumentParser(description="Mirror clean earnings calendar rows into QuantRepository storage")
    parser.add_argument("--days", type=int, default=366, help="Forward date window from today")
    parser.add_argument("--include-past", action="store_true", help="Keep valid historical rows too")
    parser.add_argument("--update-sheet", action="store_true", help="Rewrite the Earnings_Calendar sheet with clean enriched rows")
    parser.add_argument("--skip-yahoo", action="store_true", help="Do not enrich US names/market caps from Yahoo's free earnings calendar pages")
    parser.add_argument("--skip-yahoo-search", action="store_true", help="Do not enrich US names/sectors from Yahoo's free search endpoint")
    parser.add_argument("--skip-sp500-filter", action="store_true", help="Keep all US rows instead of filtering to portfolio or S&P 500")
    parser.add_argument("--delay", type=float, default=0.04, help="Delay between Naver KR metadata calls")
    args = parser.parse_args()

    today = datetime.now(KST).date()
    spreadsheet = get_spreadsheet()
    earnings_rows = _read_sheet_records(spreadsheet, "Earnings_Calendar")
    company_rows = _read_sheet_records(spreadsheet, "Company_Master")
    portfolio_rows = _read_sheet_records(spreadsheet, "US_Final_Portfolio")
    portfolio_set = _portfolio_tickers(portfolio_rows)
    sp500_set = set() if args.skip_sp500_filter else _sp500_tickers()
    if args.skip_sp500_filter:
        allowed_us = {
            _normalize_us_ticker(row.get("Ticker") or "")
            for row in earnings_rows
            if _infer_market(str(row.get("Ticker") or "")) == "US"
        }
    else:
        allowed_us = portfolio_set | sp500_set
    yahoo = {} if args.skip_yahoo else _yahoo_lookup(earnings_rows, today, max(1, args.days), args.include_past)
    yahoo_search = {} if args.skip_yahoo_search else _yahoo_search_lookup(
        earnings_rows,
        company_rows,
        today=today,
        days=max(1, args.days),
        include_past=args.include_past,
    )
    records, skipped = _build_records(
        earnings_rows,
        company_rows,
        today=today,
        days=max(1, args.days),
        include_past=args.include_past,
        delay=args.delay,
        yahoo=yahoo,
        yahoo_search=yahoo_search,
        allowed_us_tickers=allowed_us,
        portfolio_tickers=portfolio_set,
        sp500_tickers=sp500_set,
    )

    repo = QuantRepository()
    result = repo.write_records("Earnings_Calendar", records, snapshot_date=today.isoformat())
    if args.update_sheet:
        _update_sheet(spreadsheet, records)

    by_market = Counter(record["Market"] for record in records)
    by_date = Counter(record["Next_Earnings_Date"] for record in records)
    print({
        "records": len(records),
        "by_market": dict(by_market),
        "date_range": [min(by_date) if by_date else None, max(by_date) if by_date else None],
        "skipped": skipped,
        "portfolio_tickers": len(portfolio_set),
        "sp500_tickers": len(sp500_set),
        "storage": result,
        "sheet_updated": bool(args.update_sheet),
    })
    return 0 if records else 1


if __name__ == "__main__":
    raise SystemExit(main())
