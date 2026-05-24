# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
"""
pipeline/10_earnings_surprise_scanner.py
=========================================
Post-Earnings Announcement Drift (PEAD) Scanner — US universe

Scans S&P500 + NASDAQ-100 tickers for stocks that:
  1. Reported earnings within the last LOOKBACK_DAYS calendar days
  2. Beat EPS estimates by at least SURPRISE_THRESHOLD (5%)

Then ranks them by Signal_Strength — a composite of surprise magnitude,
recency, and post-announcement price drift:

    Signal_Strength = 0.5 × surprise_score
                    + 0.3 × recency_score
                    + 0.2 × momentum_score

    surprise_score  = min(surprise_pct / 0.20, 1.0)         # capped at 20%
    recency_score   = 1 − (days_since / LOOKBACK_DAYS)       # fresher = higher
    momentum_score  = min(max(return_since / 0.10, 0), 1.0)  # 10% drift → 1.0

Academic basis: Post-Earnings Announcement Drift (PEAD)
  Bernard & Thomas (1989) — drift persists 60+ days after announcement.

Data source: yfinance ticker.earnings_dates (free, no API key required)
Output:      US_Earnings_Momentum sheet (top 30 by Signal_Strength)
"""

import gspread
import yfinance as yf
from sheets_client import get_spreadsheet
import pandas as pd
import numpy as np
import time
import warnings
from datetime import datetime
from cache_manager import CacheManager
from quantbridge.ticker_policy import banned_tickers_label, filter_banned_tickers
from quantbridge.writers.dual_write import dual_write_dataframe

warnings.filterwarnings('ignore')

# ── Google Sheets connection ──────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()

# ── Constants ─────────────────────────────────────────────────────────────────
SURPRISE_THRESHOLD = 0.05   # minimum positive EPS surprise (5%)
LOOKBACK_DAYS      = 21     # only consider earnings announced within last 21 calendar days
TOP_N              = 30     # max rows written to output sheet
API_DELAY          = 0.3    # seconds between yfinance calls (rate limit safety)

TEST_MODE  = os.environ.get('QUANT_TEST_MODE') == 'true'
TEST_LIMIT = 30

# ── Output schema ─────────────────────────────────────────────────────────────
EARNINGS_MOMENTUM_COLS = [
    'Rank', 'Ticker', 'Name', 'Sector', 'MarketCap',
    'Earnings_Date', 'Days_Since_Earnings',
    'Actual_EPS', 'Estimated_EPS', 'Surprise_Pct',
    'Price_Before', 'Price_Latest', 'Return_Since',
    'Volume_Surge', 'Signal_Strength', 'Last_Updated',
]

# ── Load universe from US_Universe ────────────────────────────────────────────
print("[EARN] Loading universe from US_Universe...")
try:
    ws_uni = spreadsheet.worksheet("US_Universe")
    data   = ws_uni.get_all_values()
    df_uni = pd.DataFrame(data[1:], columns=data[0])
    tickers = [t.strip() for t in df_uni['Ticker'].dropna().tolist() if t.strip()]
    before_policy = len(tickers)
    tickers = filter_banned_tickers(tickers)
    if len(tickers) != before_policy:
        print(f"[EARN] Banned US tickers excluded: {banned_tickers_label()}")
    print(f"[EARN] {len(tickers)} tickers loaded")
except Exception as e:
    print(f"[EARN] Fatal: could not load US_Universe: {e}")
    exit(1)

if TEST_MODE:
    tickers = tickers[:TEST_LIMIT]
    print(f"[EARN] ⚠️  TEST MODE: trimmed to {len(tickers)} tickers")

# ── CacheManager — enriches results with Name / Sector / MarketCap ────────────
cache = CacheManager(spreadsheet, verbose=False)


# ── Helpers ───────────────────────────────────────────────────────────────────
def _to_float(v):
    try:
        return float(v) if v not in ('', None) else None
    except (ValueError, TypeError):
        return None


def scan_ticker(sym: str) -> dict | None:
    """
    Fetch earnings surprise data for one ticker via yfinance.earnings_dates.

    Returns a result dict if the ticker qualifies:
      - Most recent past earnings within LOOKBACK_DAYS calendar days
      - Positive EPS surprise > SURPRISE_THRESHOLD
    Returns None otherwise (no data, no recent earnings, miss/inline).
    """
    try:
        t  = yf.Ticker(sym)
        ed = t.earnings_dates          # DataFrame: index=UTC datetime, cols=[EPS Estimate, Reported EPS, Surprise(%)]
        if ed is None or ed.empty:
            return None

        now_utc = pd.Timestamp.now(tz='UTC')

        # Keep only past earnings (Reported EPS is not NaN)
        past = ed[ed['Reported EPS'].notna()].copy()
        if past.empty:
            return None

        # Most recent past earnings — DataFrame is sorted newest-first
        earn_date    = past.index[0]          # tz-aware UTC timestamp
        latest_row   = past.iloc[0]

        days_since = int((now_utc - earn_date).days)
        if days_since < 0 or days_since > LOOKBACK_DAYS:
            return None

        actual_eps    = _to_float(latest_row['Reported EPS'])
        estimated_eps = _to_float(latest_row.get('EPS Estimate'))
        if actual_eps is None or estimated_eps is None or estimated_eps == 0:
            return None

        surprise_pct = (actual_eps - estimated_eps) / abs(estimated_eps)
        if surprise_pct < SURPRISE_THRESHOLD:
            return None

        # ── Price data: last 30 days ──────────────────────────────────────────
        hist = yf.download(sym, period='30d', auto_adjust=True, progress=False)
        if hist.empty:
            return None

        # Flatten MultiIndex columns when single ticker is downloaded
        if isinstance(hist.columns, pd.MultiIndex):
            hist.columns = hist.columns.get_level_values(0)

        earn_date_naive = earn_date.tz_localize(None).normalize()

        # Price on last close BEFORE the earnings announcement date
        hist_before = hist[hist.index.normalize() < earn_date_naive]
        if hist_before.empty:
            # Earnings happened before the 30-day window — not enough history
            return None
        price_before = float(hist_before['Close'].iloc[-1])
        price_latest = float(hist['Close'].iloc[-1])
        return_since = (price_latest / price_before) - 1

        # Volume surge: earnings-day volume vs 30-day average
        vol_avg = float(hist['Volume'].mean()) if hist['Volume'].mean() > 0 else 1.0
        hist_on = hist[hist.index.normalize() == earn_date_naive]
        if not hist_on.empty:
            volume_surge = float(hist_on['Volume'].iloc[0]) / vol_avg
        else:
            # Earnings announced after hours — use next trading day volume
            hist_after = hist[hist.index.normalize() > earn_date_naive]
            if not hist_after.empty:
                volume_surge = float(hist_after['Volume'].iloc[0]) / vol_avg
            else:
                volume_surge = 1.0

        # ── Signal_Strength composite (0–1 scale) ────────────────────────────
        surprise_score  = min(surprise_pct / 0.20, 1.0)              # 20% surprise → 1.0
        recency_score   = 1.0 - (days_since / LOOKBACK_DAYS)         # day 0 → 1.0, day 21 → 0
        momentum_score  = min(max(return_since / 0.10, 0.0), 1.0)    # 10% drift → 1.0
        signal_strength = round(
            0.5 * surprise_score + 0.3 * recency_score + 0.2 * momentum_score, 4
        )

        return {
            'Earnings_Date':       earn_date.strftime('%Y-%m-%d'),
            'Days_Since_Earnings': days_since,
            'Actual_EPS':          round(actual_eps, 4),
            'Estimated_EPS':       round(estimated_eps, 4),
            'Surprise_Pct':        round(surprise_pct, 4),
            'Price_Before':        round(price_before, 4),
            'Price_Latest':        round(price_latest, 4),
            'Return_Since':        round(return_since, 4),
            'Volume_Surge':        round(volume_surge, 4),
            'Signal_Strength':     signal_strength,
        }

    except Exception:
        return None


# ── Main scan loop ────────────────────────────────────────────────────────────
print(f"\n[EARN] Scanning {len(tickers)} tickers "
      f"(surprise >{SURPRISE_THRESHOLD:.0%}, last {LOOKBACK_DAYS} days)...")

results = []
hits    = 0

for i, sym in enumerate(tickers, 1):
    print(f"  [{i:>3}/{len(tickers)}] {sym:<12}", end='', flush=True)
    r = scan_ticker(sym)

    if r is not None:
        # Enrich with cached Name / Sector / MarketCap (no extra API call)
        row       = cache.get_row(sym) or {}
        r['Ticker']    = sym
        r['Name']      = row.get('Name', sym)
        r['Sector']    = row.get('Sector', '')
        r['MarketCap'] = _to_float(row.get('MarketCap_Last')) or ''
        results.append(r)
        hits += 1
        print(f"  ✅  surprise={r['Surprise_Pct']:+.1%}  "
              f"drift={r['Return_Since']:+.1%}  "
              f"signal={r['Signal_Strength']:.3f}")
    else:
        print()   # newline only — no hit, keep output clean

    time.sleep(API_DELAY)

print(f"\n[EARN] Scan complete — {hits} qualifying stocks out of {len(tickers)}")

# ── Build output DataFrame ────────────────────────────────────────────────────
today_str = datetime.now().strftime('%Y-%m-%d')

if results:
    df_out = pd.DataFrame(results)
    df_out = df_out.sort_values('Signal_Strength', ascending=False).reset_index(drop=True)
    df_out = df_out.head(TOP_N)
    df_out.insert(0, 'Rank', range(1, len(df_out) + 1))
    df_out['Last_Updated'] = today_str

    for col in EARNINGS_MOMENTUM_COLS:
        if col not in df_out.columns:
            df_out[col] = ''
    df_out = df_out[EARNINGS_MOMENTUM_COLS].fillna('').astype(str)
else:
    print("[EARN] No qualifying stocks — sheet will be cleared with headers only.")
    df_out = pd.DataFrame(columns=EARNINGS_MOMENTUM_COLS)

# ── Write to Google Sheets ─────────────────────────────────────────────────────
print("\n[EARN] Writing to US_Earnings_Momentum sheet...")
try:
    earn_ws = spreadsheet.worksheet("US_Earnings_Momentum")
except gspread.exceptions.WorksheetNotFound:
    earn_ws = spreadsheet.add_worksheet(
        title="US_Earnings_Momentum",
        rows=200,
        cols=len(EARNINGS_MOMENTUM_COLS),
    )

earn_ws.clear()
earn_ws.update([EARNINGS_MOMENTUM_COLS] + df_out.values.tolist())
dual_write_dataframe("US_Earnings_Momentum", df_out, market="US")

print(f"✅ [EARN] {len(df_out)} rows written to US_Earnings_Momentum")
if not df_out.empty:
    top = df_out.iloc[0]
    print(f"   Top pick : {top['Ticker']}  "
          f"surprise={top['Surprise_Pct']}  "
          f"drift={top['Return_Since']}  "
          f"signal={top['Signal_Strength']}")
