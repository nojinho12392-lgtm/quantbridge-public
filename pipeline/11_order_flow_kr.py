# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
"""
11_kr_order_flow.py — Consecutive Dual Net Buying Scanner (KR)
==============================================================

Architecture — Naver Finance Scraper (v3)
-----------------------------------------
pykrx investor data functions all return 403 / 'LOGOUT' from KRX API
(KRX now requires authenticated browser sessions for investor data).

This version uses Naver Finance's per-stock investor trend page:
  https://finance.naver.com/item/frgn.naver?code=XXXXXX

That page returns a table with per-day institutional (기관) and
foreign (외국인) net buying amounts — exactly what we need.

One HTTP request per ticker, ~0.4s each → 663 tickers ≈ 5 minutes.

Run standalone:
  python pipeline/11_kr_order_flow.py
  python pipeline/11_kr_order_flow.py --all   # use KR_Universe (~500 stocks)
"""

import gspread
import argparse
import time
import warnings
from datetime import datetime

import pandas as pd
import requests
from bs4 import BeautifulSoup
from sheets_client import get_spreadsheet
from quantbridge.writers.dual_write import dual_write_dataframe

warnings.filterwarnings('ignore')

# ── CLI arguments ─────────────────────────────────────────────────────────────
_parser = argparse.ArgumentParser(add_help=False)
_parser.add_argument('--all', action='store_true',
                     help='Read KR_Universe instead of KR_Scored_Stocks')
_args, _ = _parser.parse_known_args()

TEST_MODE  = os.environ.get('QUANT_TEST_MODE') == 'true'
USE_ALL    = _args.all
TEST_LIMIT = 30

if TEST_MODE:
    print("\n⚠️  TEST MODE: trimmed to first 30 KR tickers")

# ── Constants ─────────────────────────────────────────────────────────────────
LOOKBACK_DAYS    = 5    # trading days in the sliding window
CONSECUTIVE_DAYS = 3    # days BOTH foreign + inst must simultaneously be net buyers
CALL_DELAY       = 0.4  # seconds between Naver Finance requests

NAVER_FRGN_URL   = 'https://finance.naver.com/item/frgn.naver'
NAVER_HEADERS    = {
    'User-Agent': (
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) '
        'AppleWebKit/537.36 (KHTML, like Gecko) '
        'Chrome/122.0.0.0 Safari/537.36'
    ),
    'Referer':    'https://finance.naver.com/',
    'Accept-Language': 'ko-KR,ko;q=0.9,en-US;q=0.8',
}

# ── Sheet names ───────────────────────────────────────────────────────────────
SHEET_SCORED   = 'KR_Scored_Stocks'
SHEET_UNIVERSE = 'KR_Universe'
SHEET_OUTPUT   = 'KR_Dual_Net_Buyers'

# ── Output schema ─────────────────────────────────────────────────────────────
OUTPUT_COLS = [
    'Rank', 'Ticker', 'Name', 'Market',
    'Consecutive_Days',
    'Foreign_Net_Buy',   # total net shares bought by foreign investors over lookback
    'Inst_Net_Buy',      # total net shares bought by institutional investors
    'Last_Updated',
]

# ── Google Sheets connection ──────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()


# ═════════════════════════════════════════════════════════════════════════════
# SECTION 1 — TICKER UTILITIES
# ═════════════════════════════════════════════════════════════════════════════

def _to_krx_code(raw: str) -> str:
    """
    Convert ANY KR ticker format to a strict 6-digit KRX code.

    '005930.KS' → '005930'   yfinance KOSPI
    '293490.KQ' → '293490'   yfinance KOSDAQ
    '5930.KS'   → '005930'   Sheets stripped leading zero
    '5930'      → '005930'   Sheets stored as integer (no suffix)
    '005930'    → '005930'   Already correct
    """
    s = str(raw).strip().split('.')[0].strip()
    return s.zfill(6)


def _detect_market(raw: str) -> str:
    return 'KOSDAQ' if str(raw).strip().upper().endswith('.KQ') else 'KOSPI'


# ═════════════════════════════════════════════════════════════════════════════
# SECTION 2 — NAVER FINANCE INVESTOR DATA FETCHER
# ═════════════════════════════════════════════════════════════════════════════

def _parse_int(raw: str) -> int | None:
    """Parse '+1,550,021' / '-3,993,971' / '1234' → int.  None if non-numeric."""
    s = raw.replace(',', '').replace('+', '').strip()
    if not s:
        return None
    try:
        return int(s)
    except ValueError:
        return None


def fetch_investor_data(krx_code: str) -> list[dict] | None:
    """
    Scrape investor net-buying data for one KR stock from Naver Finance.

    URL: https://finance.naver.com/item/frgn.naver?code=XXXXXX

    The page contains a table with columns:
      날짜 | 종가 | 전일비 | 등락률 | 거래량 | 기관 순매매량 | 외국인 순매매량 | 보유주수 | 보유율

    Returns
    -------
    list of dicts (newest trading day first), each containing:
        date        (str)  : '2026.03.30'
        inst_net    (int)  : institutional net buying in shares (positive = net buyer)
        foreign_net (int)  : foreign net buying in shares (positive = net buyer)

    Returns None on fetch/parse failure or when the stock has no data.
    """
    try:
        resp = requests.get(
            NAVER_FRGN_URL,
            headers=NAVER_HEADERS,
            params={'code': krx_code},
            timeout=10,
        )
        if resp.status_code != 200:
            return None

        soup = BeautifulSoup(resp.text, 'html.parser')
        tables = soup.find_all('table', {'class': 'type2'})
        if len(tables) < 2:
            return None

        # Table index 1 is the investor trend table
        rows = tables[1].find_all('tr')

        records = []
        for row in rows[3:]:   # skip 2 header rows + 1 empty row
            cells = [c.get_text(strip=True) for c in row.find_all(['th', 'td'])]
            # A valid data row has ≥7 cells and starts with a date
            if len(cells) < 7 or not cells[0] or '.' not in cells[0]:
                continue

            inst_net    = _parse_int(cells[5])
            foreign_net = _parse_int(cells[6])

            if inst_net is None or foreign_net is None:
                continue

            records.append({
                'date':        cells[0],
                'inst_net':    inst_net,
                'foreign_net': foreign_net,
            })

        return records if records else None

    except Exception:
        return None


def check_consecutive(
    records:       list[dict],
    n_consecutive: int = CONSECUTIVE_DAYS,
    n_lookback:    int = LOOKBACK_DAYS,
) -> dict | None:
    """
    Check if a stock qualifies as a Consecutive Dual Net Buyer.

    Algorithm
    ---------
    records come in newest-first order.

    1. Take the first n_consecutive records (most recent days).
    2. ALL of them must have inst_net > 0 AND foreign_net > 0.
    3. Count the actual streak length (going back as far as it holds).
    4. Compute totals over the first n_lookback records.
    5. Return metrics dict, or None if condition not met.

    Returns
    -------
    dict or None:
        consecutive_days  (int)
        foreign_net_buy   (int)  total over lookback
        inst_net_buy      (int)  total over lookback
    """
    if not records or len(records) < n_consecutive:
        return None

    # ── Check the most-recent n_consecutive days ──────────────────────────────
    tail = records[:n_consecutive]
    if not all(r['inst_net'] > 0 and r['foreign_net'] > 0 for r in tail):
        return None

    # ── Count actual streak ───────────────────────────────────────────────────
    streak = 0
    for r in records:        # still newest-first
        if r['inst_net'] > 0 and r['foreign_net'] > 0:
            streak += 1
        else:
            break

    # ── Totals over the lookback window ───────────────────────────────────────
    lookback = records[:n_lookback]
    total_foreign = sum(r['foreign_net'] for r in lookback)
    total_inst    = sum(r['inst_net']    for r in lookback)

    return {
        'consecutive_days': streak,
        'foreign_net_buy':  int(total_foreign),
        'inst_net_buy':     int(total_inst),
    }


def probe_naver(sample_code: str = '005930') -> bool:
    """
    Quick sanity-check: fetch Samsung's investor data and print the result.
    Returns True if data was successfully retrieved.
    """
    print("[ORDER FLOW] ── Naver Finance probe ──────────────────────────────")
    records = fetch_investor_data(sample_code)
    if not records:
        print(f"  ✗ No data returned for {sample_code} (Samsung).")
        print("    Check your internet connection.")
        return False

    print(f"  ✅ Naver Finance OK — {len(records)} rows for Samsung ({sample_code})")
    print(f"     Most recent 3 rows:")
    for r in records[:3]:
        sign_i = '+' if r['inst_net']    >= 0 else ''
        sign_f = '+' if r['foreign_net'] >= 0 else ''
        print(f"       {r['date']}   기관 {sign_i}{r['inst_net']:>12,}   "
              f"외국인 {sign_f}{r['foreign_net']:>12,}")
    return True


# ═════════════════════════════════════════════════════════════════════════════
# SECTION 3 — GOOGLE SHEETS I/O
# ═════════════════════════════════════════════════════════════════════════════

def load_kr_tickers(use_all: bool = False) -> list[dict]:
    """
    Load KR tickers from Google Sheets and normalise to 6-digit KRX codes.

    Accepted formats: '005930.KS', '5930.KS', '5930', '005930'
    US tickers (contain letters after split) are silently skipped.
    """
    source_order = (
        [SHEET_UNIVERSE, SHEET_SCORED] if use_all
        else [SHEET_SCORED, SHEET_UNIVERSE]
    )

    for sheet_name in source_order:
        try:
            ws   = spreadsheet.worksheet(sheet_name)
            data = ws.get_all_values()
            if len(data) < 2:
                continue
            df = pd.DataFrame(data[1:], columns=data[0])
            if 'Ticker' not in df.columns:
                print(f"  [ORDER FLOW] '{sheet_name}' has no 'Ticker' column — skipping")
                continue

            result        = []
            n_skipped_us  = 0
            n_skipped_bad = 0

            for _, row in df.iterrows():
                raw = str(row.get('Ticker', '')).strip()
                if not raw or raw == 'nan':
                    continue

                numeric_part = raw.split('.')[0].strip()
                if not numeric_part.isdigit():
                    n_skipped_us += 1
                    continue

                krx_code = numeric_part.zfill(6)
                if len(krx_code) != 6 or not krx_code.isdigit():
                    n_skipped_bad += 1
                    continue

                result.append({
                    'yf_ticker': raw,
                    'krx_code':  krx_code,
                    'market':    _detect_market(raw),
                    'name':      str(row.get('Name', '')).strip(),
                })

            if result:
                print(f"[ORDER FLOW] Ticker source : {sheet_name}")
                print(f"             Loaded        : {len(result)} KR tickers")
                print(f"             Skipped (US)  : {n_skipped_us}")
                print(f"             Skipped (bad) : {n_skipped_bad}")
                print(f"             Sample codes  : "
                      + "  |  ".join(
                          f"{e['yf_ticker']} → {e['krx_code']}"
                          for e in result[:5]
                      ))
                return result

        except Exception as e:
            print(f"  [ORDER FLOW] Could not read '{sheet_name}': {e}")

    return []


def _get_or_create_sheet(name: str, rows: int = 500,
                         cols: int = len(OUTPUT_COLS) + 2) -> gspread.Worksheet:
    try:
        return spreadsheet.worksheet(name)
    except gspread.exceptions.WorksheetNotFound:
        print(f"  [Sheets] Creating new worksheet: '{name}'")
        return spreadsheet.add_worksheet(title=name, rows=rows, cols=cols)


def export_to_sheets(result_df: pd.DataFrame, date_range: str):
    """Write the dual-net-buyer ranking to KR_Dual_Net_Buyers."""
    ws       = _get_or_create_sheet(SHEET_OUTPUT)
    n_stocks = len(result_df)
    source   = SHEET_UNIVERSE if USE_ALL else SHEET_SCORED

    summary = [
        ["── KR Dual Net Buyers ──", ""],
        ["Strategy",     "Consecutive Dual Net Buying — 기관 + 외국인"],
        ["Data source",  "Naver Finance (finance.naver.com)"],
        ["Universe",     source],
        ["Date range",   date_range],
        ["Lookback days",          str(LOOKBACK_DAYS)],
        ["Consecutive days req'd", str(CONSECUTIVE_DAYS)],
        ["Qualifying stocks",      str(n_stocks)],
        ["Sort order",  "Consecutive_Days DESC → Foreign_Net_Buy DESC"],
        ["Generated",   pd.Timestamp.now().strftime('%Y-%m-%d %H:%M')],
        ["", ""],
    ]

    for col in OUTPUT_COLS:
        if col not in result_df.columns:
            result_df[col] = ''

    out_df = result_df[OUTPUT_COLS].fillna('').astype(str)
    rows   = summary + [out_df.columns.tolist()] + out_df.values.tolist()

    ws.clear()
    ws.update(range_name='A1', values=rows, value_input_option='USER_ENTERED')
    dual_write_dataframe(SHEET_OUTPUT, result_df[OUTPUT_COLS], market="KR")
    print(f"✅ [ORDER FLOW] {n_stocks} dual-net-buyer stocks → '{SHEET_OUTPUT}'")


# ═════════════════════════════════════════════════════════════════════════════
# SECTION 4 — MAIN ORCHESTRATOR
# ═════════════════════════════════════════════════════════════════════════════

def main():
    print("\n" + "═" * 65)
    print("  11 — KR Consecutive Dual Net Buying Scanner  (v3 Naver)")
    print("  Institutional 기관 + Foreign 외국인 — Naver Finance")
    print("═" * 65)

    # ── 1. Probe Naver Finance ────────────────────────────────────────────────
    if not probe_naver():
        print("\n[ORDER FLOW] ❌ Naver Finance probe failed. Check network and re-run.")
        return

    # ── 2. Load KR ticker universe ────────────────────────────────────────────
    ticker_list = load_kr_tickers(use_all=USE_ALL)
    if not ticker_list:
        print("[ORDER FLOW] ❌ No KR tickers. Run pipeline/01_universe_expander.py first.")
        return

    if TEST_MODE:
        ticker_list = ticker_list[:TEST_LIMIT]
        print(f"⚠️  TEST MODE: trimmed to {len(ticker_list)} tickers")

    # ── 3. Scan each ticker ───────────────────────────────────────────────────
    total      = len(ticker_list)
    est_min    = total * CALL_DELAY / 60
    print(f"\n[ORDER FLOW] Scanning {total} tickers  "
          f"(~{est_min:.1f} min at {CALL_DELAY}s/ticker)")
    print(f"             Condition: ≥{CONSECUTIVE_DAYS} consecutive days, "
          f"BOTH 기관 AND 외국인 net buyers")
    print()

    results    = []
    n_qualify  = 0
    n_no_data  = 0
    n_rejected = 0
    date_range_set: set[str] = set()

    for idx, entry in enumerate(ticker_list, 1):
        krx_code = entry['krx_code']

        # Progress counter every 50 tickers
        if idx % 50 == 0 or idx == total:
            print(f"  [{idx:>4}/{total}]  ✅ {n_qualify} qualifying  "
                  f"❌ {n_no_data} no data  ✗ {n_rejected} rejected")

        records = fetch_investor_data(krx_code)
        time.sleep(CALL_DELAY)

        if records is None:
            n_no_data += 1
            continue

        # Collect date range info from first few results
        if records and len(date_range_set) < 10:
            date_range_set.add(records[0]['date'])

        metrics = check_consecutive(records, CONSECUTIVE_DAYS, LOOKBACK_DAYS)
        if metrics is None:
            n_rejected += 1
            continue

        n_qualify += 1
        results.append({
            'Ticker':           krx_code,
            'Name':             entry['name'],
            'Market':           entry['market'],
            'Consecutive_Days': metrics['consecutive_days'],
            'Foreign_Net_Buy':  metrics['foreign_net_buy'],
            'Inst_Net_Buy':     metrics['inst_net_buy'],
        })

    # ── 4. Summary ───────────────────────────────────────────────────────────
    print(f"\n[ORDER FLOW] ── Final Scan Summary ─────────────────────────────")
    print(f"  Total scanned        : {total}")
    print(f"  No data (Naver 404)  : {n_no_data}")
    print(f"  In Naver, no streak  : {n_rejected}")
    print(f"  ✅ Qualifying        : {n_qualify}")

    # ── 5. Build output DataFrame ─────────────────────────────────────────────
    if not results:
        print(f"\n[ORDER FLOW] ⚠️  No stocks met ≥{CONSECUTIVE_DAYS} consecutive days.")
        print(f"             Try reducing CONSECUTIVE_DAYS or use --all flag.")
        result_df = pd.DataFrame(columns=OUTPUT_COLS)
        date_range = "N/A"
    else:
        result_df = (
            pd.DataFrame(results)
            .sort_values(['Consecutive_Days', 'Foreign_Net_Buy'],
                         ascending=[False, False])
            .reset_index(drop=True)
        )
        result_df.insert(0, 'Rank', result_df.index + 1)
        result_df['Last_Updated'] = datetime.now().strftime('%Y-%m-%d')

        # Date range string for summary sheet
        sorted_dates = sorted(date_range_set, reverse=True)
        date_range = (f"{sorted_dates[-1]} → {sorted_dates[0]}"
                      if len(sorted_dates) > 1 else sorted_dates[0] if sorted_dates else "N/A")

        # ── Console summary ───────────────────────────────────────────────────
        print(f"\n[ORDER FLOW] ── Top {min(15, n_qualify)} Dual Net Buyers ─────────")
        print(f"  {'Rk':>3}  {'Code':>6}  {'Name':<22}  {'Mkt':>6}  "
              f"{'Days':>4}  {'FgnNet':>14}  {'InstNet':>14}")
        print(f"  {'─'*3}  {'─'*6}  {'─'*22}  {'─'*6}  "
              f"{'─'*4}  {'─'*14}  {'─'*14}")
        for _, row in result_df.head(15).iterrows():
            print(
                f"  {int(row['Rank']):>3}  "
                f"{row['Ticker']:>6}  "
                f"{str(row['Name'])[:22]:<22}  "
                f"{row['Market']:>6}  "
                f"{int(row['Consecutive_Days']):>4}  "
                f"{int(row['Foreign_Net_Buy']):>14,}  "
                f"{int(row['Inst_Net_Buy']):>14,}"
            )

    # ── 6. Export to Google Sheets ────────────────────────────────────────────
    export_to_sheets(result_df, date_range=date_range)
    print("\n✅ [ORDER FLOW] KR Dual Net Buying scan complete.")


if __name__ == '__main__':
    main()
