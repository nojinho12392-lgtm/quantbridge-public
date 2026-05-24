# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
"""
pipeline/13_portfolio_drift_monitor.py
=======================================
Portfolio Weight Drift Monitor

Reads the current target weights from US_Final_Portfolio and KR_Final_Portfolio,
then computes how much each holding's weight has drifted due to price movements
since the last rebalance date.

Drift formula (weight drift from price change alone):
    w_current(i) = w_target(i) × (1 + r_i) / Σ[ w_target(j) × (1 + r_j) ]
    drift_abs(i) = |w_current(i) - w_target(i)|
    drift_pct(i) = drift_abs(i) / w_target(i)          ← relative drift

Portfolio-level metric:
    Total_Drift = Σ drift_abs(i) / 2
    (= one-way turnover needed to fully rebalance back to target)

Status thresholds (configurable):
    REBALANCE  🔴 : drift_abs > THRESH_REBALANCE  (default 5%)
    WATCH      🟡 : drift_abs > THRESH_WATCH       (default 3%)
    OK         🟢 : drift_abs ≤ THRESH_WATCH

Output:
    - Portfolio_Drift_Alert sheet: per-stock drift table (US + KR combined)
    - Console: clear summary report with rebalance recommendations

Run standalone:
    python pipeline/13_portfolio_drift_monitor.py
"""

import warnings
import time
from datetime import datetime, timedelta

import gspread
import numpy as np
import pandas as pd
import yfinance as yf
from sheets_client import get_spreadsheet
from quantbridge.writers.dual_write import dual_write_dataframe

warnings.filterwarnings('ignore')

# ── Google Sheets connection ──────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()

# ── Thresholds ────────────────────────────────────────────────────────────────
THRESH_REBALANCE = 0.05   # 5% absolute drift → immediate rebalance signal
THRESH_WATCH     = 0.03   # 3% absolute drift → watch signal
PRICE_DELAY      = 0.3    # seconds between yfinance calls
PORTFOLIO_COLS   = [      # canonical schema written by 06/06b
    'Rank', 'Ticker', 'Name', 'Market', 'Sector', 'MarketCap',
    'Weight(%)', 'Total_Score', 'ROIC', 'RevGrowth',
    'GrossMargin', 'Expected_Return', 'Last_Updated',
]

# ── Output schema ─────────────────────────────────────────────────────────────
DRIFT_COLS = [
    'Market', 'Ticker', 'Name', 'Sector',
    'Target_Weight', 'Current_Weight', 'Drift_Abs', 'Drift_Pct',
    'Price_Rebal', 'Price_Current', 'Return_Since_Rebal',
    'Status', 'Last_Rebal', 'Last_Checked',
]

SUMMARY_COLS = [
    'Market', 'Total_Drift', 'Stocks_Rebalance', 'Stocks_Watch', 'Stocks_OK',
    'Days_Since_Rebal', 'Recommendation', 'Last_Checked',
]


# ── Helpers ───────────────────────────────────────────────────────────────────

def _to_float(v, default=None):
    try:
        return float(v) if v not in ('', None) else default
    except (ValueError, TypeError):
        return default


def _status(drift_abs: float) -> str:
    if drift_abs > THRESH_REBALANCE:
        return 'REBALANCE'
    if drift_abs > THRESH_WATCH:
        return 'WATCH'
    return 'OK'


def _parse_portfolio_sheet(sheet_name: str) -> tuple[pd.DataFrame, str]:
    """
    Read a Final_Portfolio sheet.

    The sheet layout (written by 06/06b) is:
        Rows 1–10 : key-value summary block (Strategy, Volatility, Generated, …)
        Row  11   : PORTFOLIO_COLS header
        Row  12+  : data rows

    Returns (df, rebal_date_str) where rebal_date_str is taken from the
    'Generated' row in the summary block.
    """
    try:
        ws   = spreadsheet.worksheet(sheet_name)
        rows = ws.get_all_values()
    except gspread.exceptions.WorksheetNotFound:
        print(f"  [DRIFT] ⚠️  Sheet not found: {sheet_name}")
        return pd.DataFrame(), ''

    if not rows:
        return pd.DataFrame(), ''

    # Extract rebal date from summary block ('Generated' key-value pair)
    rebal_date_str = ''
    for row in rows:
        if row and row[0].strip() == 'Generated' and len(row) > 1:
            # Format: '2026-04-09 14:32'
            rebal_date_str = row[1].strip()[:10]   # keep YYYY-MM-DD
            break

    # Find the header row (first row whose first cell is 'Rank' or 'Ticker')
    header_idx = None
    for i, row in enumerate(rows):
        if row and row[0].strip() in ('Rank', 'Ticker'):
            header_idx = i
            break

    if header_idx is None:
        print(f"  [DRIFT] ⚠️  Could not locate data header in {sheet_name}")
        return pd.DataFrame(), rebal_date_str

    header = rows[header_idx]
    data   = [r for r in rows[header_idx + 1:] if any(c.strip() for c in r)]
    df     = pd.DataFrame(data, columns=header)

    # Drop completely empty rows (sometimes sheets have trailing blank rows)
    df = df[df['Ticker'].str.strip() != ''].copy()

    for col in ['Weight(%)', 'Total_Score']:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors='coerce')

    return df, rebal_date_str


def _download_prices(tickers: list[str], rebal_date: str) -> pd.DataFrame:
    """
    Download daily close prices from just before rebal_date to today.
    Adds a buffer of 10 calendar days before rebal_date to ensure we
    have at least one trading day at or just before the rebalance.
    Returns a DataFrame indexed by date with tickers as columns.
    """
    try:
        start_dt = (pd.Timestamp(rebal_date) - timedelta(days=15)).strftime('%Y-%m-%d')
    except Exception:
        start_dt = (pd.Timestamp.today() - timedelta(days=90)).strftime('%Y-%m-%d')

    BATCH = 50
    frames = []
    for i in range(0, len(tickers), BATCH):
        batch = tickers[i: i + BATCH]
        try:
            raw = yf.download(batch, start=start_dt, auto_adjust=True, progress=False)
            closes = raw['Close'] if isinstance(raw.columns, pd.MultiIndex) else raw
            if isinstance(closes, pd.Series):
                closes = closes.to_frame(name=batch[0])
            frames.append(closes)
        except Exception as e:
            print(f"  [DRIFT] price download error: {e}")
        time.sleep(PRICE_DELAY)

    if not frames:
        return pd.DataFrame()

    prices = pd.concat(frames, axis=1)
    prices = prices.loc[:, ~prices.columns.duplicated()]
    return prices


def compute_drift(df_port: pd.DataFrame, prices: pd.DataFrame,
                  rebal_date: str) -> pd.DataFrame:
    """
    For each holding in df_port compute weight drift since rebal_date.

    Returns a DataFrame with DRIFT_COLS (excluding Market, Name, Sector —
    caller merges those in).
    """
    today_str  = datetime.now().strftime('%Y-%m-%d')
    rebal_ts   = pd.Timestamp(rebal_date) if rebal_date else None

    records = []
    for _, row in df_port.iterrows():
        ticker    = row['Ticker'].strip()
        w_target  = _to_float(row.get('Weight(%)'))
        if w_target is None or ticker not in prices.columns:
            continue

        col = prices[ticker].dropna()
        if col.empty:
            continue

        # Price just before or on the rebalance date
        if rebal_ts is not None:
            col_before = col[col.index.normalize() <= rebal_ts.normalize()]
            price_rebal = float(col_before.iloc[-1]) if not col_before.empty else float(col.iloc[0])
        else:
            price_rebal = float(col.iloc[0])

        price_current = float(col.iloc[-1])
        ret_since     = (price_current / price_rebal) - 1 if price_rebal > 0 else 0.0

        records.append({
            'Ticker':          ticker,
            'w_target':        w_target,
            'price_rebal':     round(price_rebal, 4),
            'price_current':   round(price_current, 4),
            'ret_since':       round(ret_since, 4),
            'adj_value':       w_target * (1 + ret_since),  # un-normalised adjusted weight
        })

    if not records:
        return pd.DataFrame()

    df_rec = pd.DataFrame(records)
    total_adj = df_rec['adj_value'].sum()
    if total_adj <= 0:
        return pd.DataFrame()

    df_rec['w_current'] = df_rec['adj_value'] / total_adj
    df_rec['drift_abs'] = (df_rec['w_current'] - df_rec['w_target']).abs()
    df_rec['drift_pct'] = df_rec['drift_abs'] / df_rec['w_target'].replace(0, np.nan)
    df_rec['status']    = df_rec['drift_abs'].apply(_status)

    return df_rec


# ── Main ──────────────────────────────────────────────────────────────────────

today_str = datetime.now().strftime('%Y-%m-%d')

print("\n" + "=" * 65)
print("  PORTFOLIO DRIFT MONITOR")
print(f"  Thresholds: REBALANCE > {THRESH_REBALANCE:.0%}  |  WATCH > {THRESH_WATCH:.0%}")
print("=" * 65)

all_drift_rows    = []   # combined US + KR per-stock rows
summary_rows_data = []   # one row per market for the summary block

for sheet_name, market in [("US_Final_Portfolio", "US"), ("KR_Final_Portfolio", "KR")]:
    currency = "$" if market == "US" else "₩"
    print(f"\n[DRIFT] ── {market} Portfolio ({sheet_name}) ──")

    df_port, rebal_date = _parse_portfolio_sheet(sheet_name)
    if df_port.empty:
        print(f"  [DRIFT] ⚠️  No data — skipping {market}")
        continue

    if not rebal_date:
        # Fall back to Last_Updated column if Generated row not found
        if 'Last_Updated' in df_port.columns:
            rebal_date = df_port['Last_Updated'].dropna().iloc[0] if not df_port['Last_Updated'].dropna().empty else ''

    days_since = ''
    if rebal_date:
        try:
            days_since = (pd.Timestamp.today() - pd.Timestamp(rebal_date)).days
            print(f"  Last rebalance : {rebal_date}  ({days_since} days ago)")
        except Exception:
            pass

    tickers = df_port['Ticker'].str.strip().tolist()
    print(f"  Holdings       : {len(tickers)} stocks")
    print(f"  Downloading prices since {rebal_date or 'max'}...")

    prices = _download_prices(tickers, rebal_date)
    if prices.empty:
        print(f"  [DRIFT] ⚠️  Price download failed — skipping {market}")
        continue

    df_drift = compute_drift(df_port, prices, rebal_date)
    if df_drift.empty:
        print(f"  [DRIFT] ⚠️  Could not compute drift — skipping {market}")
        continue

    # Merge metadata
    meta = df_port.set_index('Ticker')[['Name', 'Sector']].to_dict('index')

    n_rebal = (df_drift['status'] == 'REBALANCE').sum()
    n_watch = (df_drift['status'] == 'WATCH').sum()
    n_ok    = (df_drift['status'] == 'OK').sum()
    total_drift = df_drift['drift_abs'].sum() / 2   # one-way turnover

    rec = 'REBALANCE RECOMMENDED' if n_rebal >= 3 or total_drift > 0.10 else \
          'MONITOR CLOSELY'       if n_watch >= 5 or total_drift > 0.05 else \
          'HOLD — within tolerance'

    print(f"\n  {'Ticker':<14} {'Target':>7} {'Current':>8} {'Drift':>7} {'Return':>8}  Status")
    print(f"  {'-'*60}")
    for _, r in df_drift.sort_values('drift_abs', ascending=False).iterrows():
        status_icon = {'REBALANCE': '🔴', 'WATCH': '🟡', 'OK': '🟢'}.get(r['status'], '')
        print(f"  {r['Ticker']:<14} "
              f"{r['w_target']:>6.2%}  "
              f"{r['w_current']:>7.2%}  "
              f"{r['drift_abs']:>+6.2%}  "
              f"{r['ret_since']:>+7.2%}  "
              f"{status_icon} {r['status']}")

    print(f"\n  Portfolio Total Drift  : {total_drift:.2%}  (one-way turnover to rebalance)")
    print(f"  🔴 REBALANCE : {n_rebal} stocks")
    print(f"  🟡 WATCH     : {n_watch} stocks")
    print(f"  🟢 OK        : {n_ok} stocks")
    print(f"  → Recommendation: {rec}")

    # Build per-stock rows for output sheet
    for _, r in df_drift.iterrows():
        t = r['Ticker']
        all_drift_rows.append({
            'Market':             market,
            'Ticker':             t,
            'Name':               meta.get(t, {}).get('Name', ''),
            'Sector':             meta.get(t, {}).get('Sector', ''),
            'Target_Weight':      round(r['w_target'], 4),
            'Current_Weight':     round(r['w_current'], 4),
            'Drift_Abs':          round(r['drift_abs'], 4),
            'Drift_Pct':          round(r['drift_pct'], 4) if not pd.isna(r['drift_pct']) else '',
            'Price_Rebal':        r['price_rebal'],
            'Price_Current':      r['price_current'],
            'Return_Since_Rebal': r['ret_since'],
            'Status':             r['status'],
            'Last_Rebal':         rebal_date,
            'Last_Checked':       today_str,
        })

    summary_rows_data.append({
        'Market':           market,
        'Total_Drift':      round(total_drift, 4),
        'Stocks_Rebalance': n_rebal,
        'Stocks_Watch':     n_watch,
        'Stocks_OK':        n_ok,
        'Days_Since_Rebal': days_since if days_since != '' else '',
        'Recommendation':   rec,
        'Last_Checked':     today_str,
    })

# ── Build output DataFrames ───────────────────────────────────────────────────
if not all_drift_rows:
    print("\n[DRIFT] No drift data to write.")
    sys.exit(0)

df_out = pd.DataFrame(all_drift_rows)
df_out = df_out.sort_values(['Market', 'Drift_Abs'], ascending=[True, False]).reset_index(drop=True)
for col in DRIFT_COLS:
    if col not in df_out.columns:
        df_out[col] = ''
df_out = df_out[DRIFT_COLS].fillna('').astype(str)

df_sum = pd.DataFrame(summary_rows_data)
for col in SUMMARY_COLS:
    if col not in df_sum.columns:
        df_sum[col] = ''
df_sum = df_sum[SUMMARY_COLS].fillna('').astype(str)

# ── Write to Google Sheets ────────────────────────────────────────────────────
print("\n\n[DRIFT] Writing to Portfolio_Drift_Alert sheet...")
try:
    drift_ws = spreadsheet.worksheet("Portfolio_Drift_Alert")
except gspread.exceptions.WorksheetNotFound:
    drift_ws = spreadsheet.add_worksheet(
        title="Portfolio_Drift_Alert",
        rows=200,
        cols=max(len(DRIFT_COLS), len(SUMMARY_COLS)) + 2,
    )

# Layout: summary block → blank row → per-stock detail table
summary_block = [
    ["── Portfolio Drift Monitor ──", ""],
    [f"Checked", today_str],
    [f"REBALANCE threshold", f"{THRESH_REBALANCE:.0%} absolute drift"],
    [f"WATCH threshold",     f"{THRESH_WATCH:.0%} absolute drift"],
    ["", ""],
    SUMMARY_COLS,
] + df_sum.values.tolist() + [
    ["", ""],
    DRIFT_COLS,
] + df_out.values.tolist()

drift_ws.clear()
drift_ws.update(range_name='A1', values=summary_block, value_input_option='USER_ENTERED')
dual_write_dataframe("Portfolio_Drift_Alert", df_out, market="GLOBAL")
dual_write_dataframe("Portfolio_Drift_Summary", df_sum, market="GLOBAL")

print(f"✅ [DRIFT] Portfolio_Drift_Alert updated — "
      f"{len(df_out)} holdings  |  "
      f"{(df_out['Status'] == 'REBALANCE').sum()} need rebalancing")
