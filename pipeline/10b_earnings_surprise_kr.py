# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
"""
pipeline/12_kr_earnings_surprise.py
=====================================
Post-Earnings Announcement Drift (PEAD) Scanner — KR universe

Korean equivalent of 10_earnings_surprise_scanner.py.

Two-tier data approach:
  [Tier 1] yfinance earnings_dates — works for KOSPI200 large-caps that have
            analyst consensus tracked (actual EPS vs estimated EPS).
            Uses identical logic to the US scanner.

  [Tier 2] Naver Finance quarterly fallback — for tickers with no yfinance
            earnings_dates data, scrapes the quarterly financial summary at:
              https://finance.naver.com/item/coinfo.naver?code={6d_code}&target=finsum_more
            Computes YoY net income growth as a "surprise proxy".
            Announcement date is estimated from the fiscal quarter label.
            Uses a higher threshold (20%) since it is a proxy, not a true
            analyst-consensus beat.

Signal_Strength (same formula as US):
    Signal_Strength = 0.5 × surprise_score
                    + 0.3 × recency_score
                    + 0.2 × momentum_score

    surprise_score  = min(surprise_pct / cap, 1.0)
      Tier-1 cap = 0.20 (20% beat)
      Tier-2 cap = 0.50 (50% YoY growth)
    recency_score   = 1 − (days_since / LOOKBACK_DAYS)
    momentum_score  = min(max(return_since / 0.10, 0), 1.0)

Output: KR_Earnings_Momentum sheet (top 30 by Signal_Strength)

Academic basis: PEAD — Bernard & Thomas (1989); cross-market evidence for
  Korea: Choi & Sias (2009), Kim & Kim (2003).

Run standalone:
  python pipeline/12_kr_earnings_surprise.py
"""

import time
import warnings
from datetime import datetime, timedelta

import numpy as np
import pandas as pd
import requests
import yfinance as yf
from bs4 import BeautifulSoup
from sheets_client import get_spreadsheet

from cache_manager import CacheManager
from quantbridge.writers.dual_write import dual_write_dataframe

warnings.filterwarnings('ignore')

# ── Google Sheets connection ──────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()

# ── Constants ─────────────────────────────────────────────────────────────────
SURPRISE_THRESHOLD_YF    = 0.05   # Tier-1: min analyst EPS beat (5%)
SURPRISE_THRESHOLD_NAVER = 0.20   # Tier-2: min YoY net income growth (20%)
LOOKBACK_DAYS            = 45     # wider window for KR (results spread over ~6 weeks per quarter)
TOP_N                    = 30
YF_DELAY                 = 0.4    # seconds between yfinance calls
NAVER_DELAY              = 0.5    # seconds between Naver scrape calls

NAVER_HEADERS = {
    'User-Agent': (
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) '
        'AppleWebKit/537.36 (KHTML, like Gecko) '
        'Chrome/122.0.0.0 Safari/537.36'
    ),
    'Referer':    'https://finance.naver.com/',
    'Accept-Language': 'ko-KR,ko;q=0.9,en-US;q=0.8',
}
NAVER_COINFO_URL = 'https://finance.naver.com/item/coinfo.naver'

TEST_MODE  = os.environ.get('QUANT_TEST_MODE') == 'true'
TEST_LIMIT = 30

# ── Output schema ─────────────────────────────────────────────────────────────
KR_EARNINGS_MOMENTUM_COLS = [
    'Rank', 'Ticker', 'Name', 'Sector', 'MarketCap',
    'Earnings_Date', 'Days_Since_Earnings',
    'Actual_EPS', 'Estimated_EPS', 'Surprise_Pct',
    'Price_Before', 'Price_Latest', 'Return_Since',
    'Volume_Surge', 'Signal_Strength', 'Data_Source', 'Last_Updated',
]

# ── Load universe from KR_Universe ────────────────────────────────────────────
print("[KR-EARN] Loading universe from KR_Universe...")
try:
    ws_uni  = spreadsheet.worksheet("KR_Universe")
    data    = ws_uni.get_all_values()
    df_uni  = pd.DataFrame(data[1:], columns=data[0])
    tickers = [t.strip() for t in df_uni['Ticker'].dropna().tolist() if t.strip()]
    print(f"[KR-EARN] {len(tickers)} tickers loaded")
except Exception as e:
    print(f"[KR-EARN] Fatal: could not load KR_Universe: {e}")
    sys.exit(1)

if TEST_MODE:
    tickers = tickers[:TEST_LIMIT]
    print(f"[KR-EARN] ⚠️  TEST MODE: trimmed to {len(tickers)} tickers")

# ── CacheManager for Name / Sector / MarketCap enrichment ────────────────────
cache = CacheManager(spreadsheet, verbose=False)


# ── Helpers ───────────────────────────────────────────────────────────────────

def _to_float(v):
    try:
        return float(v) if v not in ('', None) else None
    except (ValueError, TypeError):
        return None


def _code_from_ticker(sym: str) -> str:
    """Extract 6-digit KRX code from yfinance ticker (e.g. '005930.KS' → '005930')."""
    return sym.split('.')[0]


def _estimate_announce_date(quarter_label: str) -> datetime | None:
    """
    Convert a Naver Finance quarter label like '2024.4Q' to an approximate
    announcement date. Korean companies typically report:
      Q1 (1Q): ~May 15 of the same year
      Q2 (2Q): ~Aug 14 of the same year
      Q3 (3Q): ~Nov 14 of the same year
      Q4 (4Q): ~Feb 14 of the FOLLOWING year
    """
    try:
        parts = quarter_label.strip().split('.')
        year  = int(parts[0])
        q     = int(parts[1].replace('Q', '').strip())
        announce = {1: (year, 5, 15), 2: (year, 8, 14), 3: (year, 11, 14), 4: (year + 1, 2, 14)}
        y, m, d = announce[q]
        return datetime(y, m, d)
    except Exception:
        return None


def _fetch_naver_quarterly(code: str) -> pd.DataFrame | None:
    """
    Scrape Naver Finance quarterly financial summary for a KRX code.

    Returns a DataFrame with columns [period, revenue, op_income, net_income, eps]
    where period is a string like '2024.4Q'. Returns None on failure.

    The target URL:
      https://finance.naver.com/item/coinfo.naver?code={code}&target=finsum_more
    returns an HTML fragment with a quarterly table (연결 재무제표 기준).
    """
    url = f"{NAVER_COINFO_URL}?code={code}&target=finsum_more"
    try:
        resp = requests.get(url, headers=NAVER_HEADERS, timeout=10)
        if resp.status_code != 200:
            return None

        soup  = BeautifulSoup(resp.text, 'html.parser')
        table = soup.find('table')
        if table is None:
            return None

        rows   = table.find_all('tr')
        if len(rows) < 2:
            return None

        # Header row: first <th> in the first row contains '구분', rest are quarter labels
        header_cells = rows[0].find_all(['th', 'td'])
        periods = []
        for cell in header_cells[1:]:  # skip first '구분' cell
            text = cell.get_text(strip=True)
            if text:
                periods.append(text)

        if not periods:
            return None

        # Parse data rows — identify by row header text
        metrics = {}
        metric_map = {
            '매출액':    'revenue',
            '영업이익':  'op_income',
            '당기순이익': 'net_income',
            'EPS':       'eps',
            'EPS(원)':   'eps',
        }
        for row in rows[1:]:
            cells = row.find_all(['th', 'td'])
            if not cells:
                continue
            label = cells[0].get_text(strip=True)
            key   = metric_map.get(label)
            if key is None:
                continue
            values = []
            for cell in cells[1:len(periods) + 1]:
                raw = cell.get_text(strip=True).replace(',', '').replace('-', '')
                values.append(_to_float(raw) if raw else None)
            metrics[key] = values

        if not metrics:
            return None

        # Build DataFrame; pad shorter lists to len(periods)
        n = len(periods)
        records = []
        for i, period in enumerate(periods):
            records.append({
                'period':     period,
                'revenue':    metrics.get('revenue',    [None] * n)[i] if i < len(metrics.get('revenue', [])) else None,
                'op_income':  metrics.get('op_income',  [None] * n)[i] if i < len(metrics.get('op_income', [])) else None,
                'net_income': metrics.get('net_income', [None] * n)[i] if i < len(metrics.get('net_income', [])) else None,
                'eps':        metrics.get('eps',        [None] * n)[i] if i < len(metrics.get('eps', [])) else None,
            })
        return pd.DataFrame(records)

    except Exception:
        return None


# ── Tier-1: yfinance scanner (same logic as US 10_earnings_surprise_scanner) ─

def scan_ticker_yf(sym: str) -> dict | None:
    """
    Scan one KR ticker via yfinance.earnings_dates.
    Returns result dict if earnings within LOOKBACK_DAYS with surprise > threshold.
    Returns None if no data, no recent earnings, or insufficient beat.
    """
    try:
        t  = yf.Ticker(sym)
        ed = t.earnings_dates
        if ed is None or ed.empty:
            return None

        now_utc = pd.Timestamp.now(tz='UTC')
        past    = ed[ed['Reported EPS'].notna()].copy()
        if past.empty:
            return None

        earn_date  = past.index[0]
        latest_row = past.iloc[0]
        days_since = int((now_utc - earn_date).days)

        if days_since < 0 or days_since > LOOKBACK_DAYS:
            return None

        actual_eps    = _to_float(latest_row['Reported EPS'])
        estimated_eps = _to_float(latest_row.get('EPS Estimate'))
        if actual_eps is None or estimated_eps is None or estimated_eps == 0:
            return None

        surprise_pct = (actual_eps - estimated_eps) / abs(estimated_eps)
        if surprise_pct < SURPRISE_THRESHOLD_YF:
            return None

        # Price data
        hist = yf.download(sym, period='60d', auto_adjust=True, progress=False)
        if hist.empty:
            return None

        if isinstance(hist.columns, pd.MultiIndex):
            hist.columns = hist.columns.get_level_values(0)

        earn_date_naive = earn_date.tz_localize(None).normalize()
        hist_before     = hist[hist.index.normalize() < earn_date_naive]
        if hist_before.empty:
            return None

        price_before = float(hist_before['Close'].iloc[-1])
        price_latest = float(hist['Close'].iloc[-1])
        return_since = (price_latest / price_before) - 1

        vol_avg  = float(hist['Volume'].mean()) if hist['Volume'].mean() > 0 else 1.0
        hist_on  = hist[hist.index.normalize() == earn_date_naive]
        if not hist_on.empty:
            volume_surge = float(hist_on['Volume'].iloc[0]) / vol_avg
        else:
            hist_after = hist[hist.index.normalize() > earn_date_naive]
            volume_surge = float(hist_after['Volume'].iloc[0]) / vol_avg if not hist_after.empty else 1.0

        surprise_score  = min(surprise_pct / 0.20, 1.0)
        recency_score   = 1.0 - (days_since / LOOKBACK_DAYS)
        momentum_score  = min(max(return_since / 0.10, 0.0), 1.0)
        signal_strength = round(0.5 * surprise_score + 0.3 * recency_score + 0.2 * momentum_score, 4)

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
            'Data_Source':         'yfinance',
        }

    except Exception:
        return None


# ── Tier-2: Naver Finance quarterly fallback ──────────────────────────────────

def scan_ticker_naver(sym: str) -> dict | None:
    """
    Fallback scanner using Naver Finance quarterly net income data.
    Computes YoY net income growth as a surprise proxy.
    Returns result dict if:
      - Most recent quarter was announced within LOOKBACK_DAYS
      - YoY net income growth >= SURPRISE_THRESHOLD_NAVER (20%)
      - Both this quarter and same quarter last year have positive net income
    Returns None otherwise.
    """
    code = _code_from_ticker(sym)
    df   = _fetch_naver_quarterly(code)
    if df is None or df.empty:
        return None

    # Filter to quarters that have a parseable period label (e.g. '2024.4Q')
    df = df[df['period'].str.match(r'^\d{4}\.\dQ$', na=False)].copy()
    if len(df) < 5:  # need at least 5 quarters to compute YoY (4 quarters gap)
        return None

    # Most recent quarter with net_income data
    df_valid = df[df['net_income'].notna() & (df['net_income'] != 0)].copy()
    if df_valid.empty:
        return None

    # Naver lists quarters newest-first in the table → df is already newest-first
    latest_idx    = df_valid.index[0]
    latest_period = df_valid.loc[latest_idx, 'period']
    latest_ni     = df_valid.loc[latest_idx, 'net_income']

    announce_date = _estimate_announce_date(latest_period)
    if announce_date is None:
        return None

    now = datetime.now()
    days_since = (now - announce_date).days
    if days_since < 0 or days_since > LOOKBACK_DAYS:
        return None

    # Find same quarter 1 year ago (4 rows later in the table if quarterly)
    # Parse period to find the matching row
    try:
        year_q = latest_period.strip().split('.')
        prev_year  = str(int(year_q[0]) - 1)
        prev_period = f"{prev_year}.{year_q[1]}"
        prev_rows  = df[df['period'] == prev_period]
        if prev_rows.empty:
            return None
        prev_ni = prev_rows.iloc[0]['net_income']
        if prev_ni is None or prev_ni <= 0 or latest_ni <= 0:
            return None
    except Exception:
        return None

    yoy_growth = (latest_ni - prev_ni) / abs(prev_ni)
    if yoy_growth < SURPRISE_THRESHOLD_NAVER:
        return None

    # Price data: download 60 days to cover up to 45-day lookback
    try:
        hist = yf.download(sym, period='60d', auto_adjust=True, progress=False)
        if hist.empty:
            return None
        if isinstance(hist.columns, pd.MultiIndex):
            hist.columns = hist.columns.get_level_values(0)

        announce_naive  = pd.Timestamp(announce_date).normalize()
        hist_before     = hist[hist.index.normalize() < announce_naive]
        if hist_before.empty:
            return None

        price_before = float(hist_before['Close'].iloc[-1])
        price_latest = float(hist['Close'].iloc[-1])
        return_since = (price_latest / price_before) - 1

        vol_avg  = float(hist['Volume'].mean()) if hist['Volume'].mean() > 0 else 1.0
        hist_on  = hist[hist.index.normalize() == announce_naive]
        if not hist_on.empty:
            volume_surge = float(hist_on['Volume'].iloc[0]) / vol_avg
        else:
            hist_after = hist[hist.index.normalize() > announce_naive]
            volume_surge = float(hist_after['Volume'].iloc[0]) / vol_avg if not hist_after.empty else 1.0

    except Exception:
        return None

    # Signal — use YoY growth capped at 50% for surprise_score
    surprise_score  = min(yoy_growth / 0.50, 1.0)
    recency_score   = 1.0 - (days_since / LOOKBACK_DAYS)
    momentum_score  = min(max(return_since / 0.10, 0.0), 1.0)
    signal_strength = round(0.5 * surprise_score + 0.3 * recency_score + 0.2 * momentum_score, 4)

    latest_eps = _to_float(df_valid.loc[latest_idx, 'eps']) or ''
    prev_eps   = _to_float(df[df['period'] == prev_period].iloc[0]['eps']) if not df[df['period'] == prev_period].empty else ''

    return {
        'Earnings_Date':       announce_date.strftime('%Y-%m-%d'),
        'Days_Since_Earnings': days_since,
        'Actual_EPS':          round(latest_eps, 4) if isinstance(latest_eps, float) else '',
        'Estimated_EPS':       round(prev_eps, 4)   if isinstance(prev_eps,   float) else '',
        'Surprise_Pct':        round(yoy_growth, 4),
        'Price_Before':        round(price_before, 4),
        'Price_Latest':        round(price_latest, 4),
        'Return_Since':        round(return_since, 4),
        'Volume_Surge':        round(volume_surge, 4),
        'Signal_Strength':     signal_strength,
        'Data_Source':         'naver_yoy',
    }


# ── Main scan loop ────────────────────────────────────────────────────────────

print(f"\n[KR-EARN] Scanning {len(tickers)} tickers "
      f"(yfinance beat >{SURPRISE_THRESHOLD_YF:.0%} | "
      f"Naver YoY >{SURPRISE_THRESHOLD_NAVER:.0%}, "
      f"last {LOOKBACK_DAYS} days)...")

results   = []
hits_yf   = 0
hits_nav  = 0
no_data   = 0

for i, sym in enumerate(tickers, 1):
    print(f"  [{i:>3}/{len(tickers)}] {sym:<14}", end='', flush=True)

    # Tier-1: yfinance
    r = scan_ticker_yf(sym)
    time.sleep(YF_DELAY)

    # Tier-2: Naver fallback
    if r is None:
        r = scan_ticker_naver(sym)
        time.sleep(NAVER_DELAY)

    if r is not None:
        row       = cache.get_row(sym) or {}
        r['Ticker']    = sym
        r['Name']      = row.get('Name', sym)
        r['Sector']    = row.get('Sector', '')
        r['MarketCap'] = _to_float(row.get('MarketCap_Last')) or ''

        if r['Data_Source'] == 'yfinance':
            hits_yf += 1
            src_tag  = 'yf'
        else:
            hits_nav += 1
            src_tag  = 'nav'

        results.append(r)
        print(f"  ✅ [{src_tag}]  surprise={r['Surprise_Pct']:+.1%}  "
              f"drift={r['Return_Since']:+.1%}  "
              f"signal={r['Signal_Strength']:.3f}")
    else:
        no_data += 1
        print()   # no hit — newline only

print(f"\n[KR-EARN] Scan complete")
print(f"          yfinance hits : {hits_yf}")
print(f"          Naver hits    : {hits_nav}")
print(f"          No qualifying : {no_data}")
print(f"          Total results : {len(results)} / {len(tickers)}")

# ── Build output DataFrame ────────────────────────────────────────────────────
today_str = datetime.now().strftime('%Y-%m-%d')

if results:
    df_out = pd.DataFrame(results)
    df_out = df_out.sort_values('Signal_Strength', ascending=False).reset_index(drop=True)
    df_out = df_out.head(TOP_N)
    df_out.insert(0, 'Rank', range(1, len(df_out) + 1))
    df_out['Last_Updated'] = today_str

    for col in KR_EARNINGS_MOMENTUM_COLS:
        if col not in df_out.columns:
            df_out[col] = ''
    df_out = df_out[KR_EARNINGS_MOMENTUM_COLS].fillna('').astype(str)
else:
    print("[KR-EARN] No qualifying stocks — sheet will be cleared with headers only.")
    df_out = pd.DataFrame(columns=KR_EARNINGS_MOMENTUM_COLS)

# ── Write to Google Sheets ────────────────────────────────────────────────────
print("\n[KR-EARN] Writing to KR_Earnings_Momentum sheet...")
try:
    earn_ws = spreadsheet.worksheet("KR_Earnings_Momentum")
except gspread.exceptions.WorksheetNotFound:
    earn_ws = spreadsheet.add_worksheet(
        title="KR_Earnings_Momentum",
        rows=200,
        cols=len(KR_EARNINGS_MOMENTUM_COLS),
    )

earn_ws.clear()
earn_ws.update([KR_EARNINGS_MOMENTUM_COLS] + df_out.values.tolist())
dual_write_dataframe("KR_Earnings_Momentum", df_out, market="KR")

print(f"✅ [KR-EARN] {len(df_out)} rows written to KR_Earnings_Momentum")
if not df_out.empty:
    top = df_out.iloc[0]
    print(f"   Top pick : {top['Ticker']} ({top['Name']})")
    print(f"             surprise={top['Surprise_Pct']}  "
          f"drift={top['Return_Since']}  "
          f"signal={top['Signal_Strength']}  "
          f"source={top['Data_Source']}")
