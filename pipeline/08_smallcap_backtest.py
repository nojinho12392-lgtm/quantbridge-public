#!/usr/bin/env python3
# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
"""
08_smallcap_backtest.py
=======================
Integrates 07_smallcap_scanner.py (gem scoring) with walk-forward backtesting.

Workflow
--------
  1. Run 07_smallcap_scanner.py  →  US_SmallCap_Gems + KR_SmallCap_Gems sheets
  2. Load gems from those sheets
  3. Backtest each market independently (US and KR — no blending):
       Config A  – Top-10 by Total_Score  (fixed selection, equal-weight monthly)
       Config B  – Top-20 by Total_Score  (fixed selection, equal-weight monthly)
       Config C  – Top-10 by 3M momentum  (dynamic, walk-forward safe)
  4. Save  →  "US_SmallCap_Backtest" and "KR_SmallCap_Backtest" sheets
  5. Print sample performance for 2024-2026

Notes
-----
  • KR tickers from 07_smallcap_scanner.py already have .KS / .KQ suffix.
  • Delisted / missing stocks are silently dropped from each period.
  • Returns are NOT currency-adjusted (KR returns in KRW, US in USD).
  • Run with --rescan to force re-execution of the gem scanner.

Usage
-----
  python pipeline/08_smallcap_backtest.py          # skip gem scan if sheets exist
  python pipeline/08_smallcap_backtest.py --rescan # force re-scan first
"""

import subprocess
import sys
import os
import yfinance as yf
from sheets_client import get_spreadsheet
import pandas as pd
import numpy as np
import time
from quantbridge.writers.dual_write import dual_write_dataframe
import warnings
from datetime import datetime
warnings.filterwarnings('ignore')

# ── Google Sheets connection ──────────────────────────────────────────────────
scope  = ["https://spreadsheets.google.com/feeds",
          "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()

# ── Backtest hyper-parameters ─────────────────────────────────────────────────
BACKTEST_PERIOD  = "5y"       # yfinance period string; auto-trims to available data
REBAL_FREQ       = 'ME'       # Month-end rebalancing
LOOKBACK_DAYS    = 63         # 3-month momentum window (Config C)
TOP_N_OPTIONS    = [10, 20]   # Run both configurations

# ── Gem sheet staleness threshold ─────────────────────────────────────────────
# If US_SmallCap_Gems / KR_SmallCap_Gems were last updated more than this many
# days ago, 07_smallcap_scanner.py is re-run automatically (even without --rescan).
GEM_MAX_AGE_DAYS = 3

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
GEM_SCRIPT = os.path.join(SCRIPT_DIR, '07_smallcap_scanner.py')


# ══════════════════════════════════════════════════════════════════════════════
#  STEP 1 — Gem scanner
# ══════════════════════════════════════════════════════════════════════════════

def _sheets_populated(*names, min_data_rows=3, max_age_days=3):
    """
    Return True if all sheets exist, have enough data rows, AND are fresh.

    Freshness check: if a 'Last_Updated' column is present and the most recent
    date is older than max_age_days, the sheet is treated as stale (returns False)
    so that 07_smallcap_scanner.py is re-run automatically.
    """
    for name in names:
        try:
            vals = spreadsheet.worksheet(name).get_all_values()
            if len(vals) < min_data_rows:
                return False
            # Staleness check via Last_Updated column
            header = vals[0] if vals else []
            if 'Last_Updated' in header:
                lu_idx = header.index('Last_Updated')
                for row in vals[1:]:
                    if len(row) > lu_idx and row[lu_idx].strip():
                        raw_date = row[lu_idx].strip()[:10]   # "YYYY-MM-DD"
                        try:
                            updated_dt = datetime.strptime(raw_date, "%Y-%m-%d")
                            age = (datetime.now() - updated_dt).days
                            if age > max_age_days:
                                print(f"[GEM] '{name}' last updated {age} day(s) ago "
                                      f"(threshold: {max_age_days}d) — treating as stale")
                                return False
                        except ValueError:
                            pass   # unparseable date → assume fresh
                        break      # only need to check the first data row
        except Exception:
            return False
    return True


def run_gem_scanner(force=False, run_us=True, run_kr=True):
    """
    Run 07_smallcap_scanner.py as a subprocess to populate gem sheets.
    Skips if the relevant sheets already have data and force=False.
    Passes QUANT_SMALLCAP_MARKET env var so 07 only runs the needed market.
    Returns True on success or skipped.
    """
    # Determine which sheets we need to check
    sheets_needed = []
    if run_us:
        sheets_needed.append('US_SmallCap_Gems')
    if run_kr:
        sheets_needed.append('KR_SmallCap_Gems')

    if not force and sheets_needed and _sheets_populated(*sheets_needed,
                                                           max_age_days=GEM_MAX_AGE_DAYS):
        print("[GEM] Required gem sheets populated and fresh — skipping re-scan")
        print(f"      (last update within {GEM_MAX_AGE_DAYS} days; pass --rescan to force)")
        return True

    if not os.path.exists(GEM_SCRIPT):
        print(f"[GEM] ❌  {GEM_SCRIPT} not found — cannot run gem scanner")
        print("         Proceeding with existing sheet data if available.")
        return False

    print(f"[GEM] Running 07_smallcap_scanner.py (this may take 10-30 minutes)...")
    env = os.environ.copy()
    # Forward the market filter so 07 only processes the needed market
    if run_us and not run_kr:
        env['QUANT_SMALLCAP_MARKET'] = 'US'
    elif run_kr and not run_us:
        env['QUANT_SMALLCAP_MARKET'] = 'KR'
    result = subprocess.run(
        [sys.executable, GEM_SCRIPT],
        cwd=SCRIPT_DIR,
        env=env,
    )
    ok = result.returncode == 0
    if not ok:
        print(f"[GEM] ⚠️  Gem scanner exited with code {result.returncode}")
    return ok


# ══════════════════════════════════════════════════════════════════════════════
#  STEP 2 — Load gems from sheets + build US_Scored_Stocks
# ══════════════════════════════════════════════════════════════════════════════

def _try_float(val):
    try:
        return float(str(val).replace(',', '').strip())
    except (ValueError, TypeError):
        return 0.0


def load_gems(sheet_name):
    """
    Load gem list from a Google Sheet tab.
    Stops at summary rows (rows where Ticker is blank or starts with '=').
    Returns list of dicts: {Ticker, Name, Total_Score, Market, Exchange}.
    """
    try:
        ws   = spreadsheet.worksheet(sheet_name)
        data = ws.get_all_values()
    except Exception as e:
        print(f"  [LOAD] {sheet_name}: {e}")
        return []

    if len(data) < 2:
        print(f"  [LOAD] {sheet_name}: empty sheet")
        return []

    header = data[0]
    market = 'US' if 'US' in sheet_name else 'KR'
    gems   = []
    seen   = set()

    for row in data[1:]:
        if not row or len(row) < 2:
            break
        # Build dict first so we use column names, not positional index.
        # The sheet has Rank as col-0; Ticker is col-1 — using row[0] was wrong.
        d      = dict(zip(header, row + [''] * len(header)))
        ticker = d.get('Ticker', '').strip()
        # Stop at summary / separator rows (empty ticker or metadata lines)
        if not ticker or ticker.startswith('=') or ticker.startswith('['):
            break
        if ticker in seen:
            continue
        seen.add(ticker)
        gems.append({
            'Ticker':      ticker,
            'Name':        d.get('Name', ''),
            'Total_Score': _try_float(d.get('Total_Score', 0)),
            'Market':      market,
            'Exchange':    d.get('Exchange', ''),
        })

    return gems




# ══════════════════════════════════════════════════════════════════════════════
#  STEP 3 — Price download
# ══════════════════════════════════════════════════════════════════════════════

def download_prices(tickers, period="5y", batch=30):
    """
    Download adjusted close prices (batched to avoid rate limits).
    • Handles delisted stocks by dropping tickers with < 40% coverage.
    • Forward-fills up to 5 trading days for holiday gaps.
    Returns pd.DataFrame (rows=trading days, cols=tickers).
    """
    if not tickers:
        return pd.DataFrame()

    frames = []
    n_batches = (len(tickers) - 1) // batch + 1

    for i in range(0, len(tickers), batch):
        chunk = tickers[i:i + batch]
        try:
            raw    = yf.download(chunk, period=period,
                                 auto_adjust=True, progress=False,
                                 threads=True)
            closes = raw['Close'] if isinstance(raw.columns, pd.MultiIndex) else raw
            if isinstance(closes, pd.Series):
                closes = closes.to_frame(name=chunk[0])
            frames.append(closes)
            print(f"    batch {i // batch + 1}/{n_batches}: "
                  f"{closes.shape[1]} tickers OK")
        except Exception as e:
            print(f"    batch {i // batch + 1}/{n_batches} error: {e}")
        time.sleep(0.5)

    if not frames:
        return pd.DataFrame()

    prices = pd.concat(frames, axis=1)
    prices = prices.loc[:, ~prices.columns.duplicated()]

    # Drop tickers with < 40% data (delisted / unavailable)
    min_rows = int(len(prices) * 0.40)
    prices   = prices.dropna(axis=1, thresh=min_rows)

    # Forward-fill short gaps (weekends, holidays)
    prices = prices.ffill(limit=5)

    # Normalize index to timezone-naive date-only timestamps.
    # yfinance sometimes returns tz-aware index; resample('ME') labels rows by
    # calendar period-end (e.g. 2021-05-31) which may not exist in tz-aware index.
    # Converting to plain dates avoids KeyError on market holidays.
    prices.index = pd.DatetimeIndex(prices.index.date)

    return prices


# ══════════════════════════════════════════════════════════════════════════════
#  STEP 4 — Backtest engine  (adapted from 05_backtest_engine.py)
# ══════════════════════════════════════════════════════════════════════════════

def _momentum_score_at(price_df, as_of_date, lookback_days):
    """
    Walk-forward-safe 3M momentum z-score for all tickers.
    Only uses prices up to (and including) as_of_date.
    Mirrors compute_score() in 05_backtest_engine.py.
    """
    hist = price_df.loc[:as_of_date]
    if len(hist) < lookback_days + 1:
        return pd.Series(dtype=float)
    curr = hist.iloc[-1]
    past = hist.iloc[-(lookback_days + 1)]
    mom  = (curr / past - 1).dropna()
    std  = mom.std()
    if std == 0 or np.isnan(std):
        return pd.Series(dtype=float)
    return (mom - mom.mean()) / std


def run_fixed_backtest(prices, selected_tickers, freq='ME'):
    """
    Config A / B: Fixed portfolio.
    Holds `selected_tickers` in equal weight, rebalances monthly.
    Stocks that get delisted mid-backtest are dropped silently.

    Args:
        prices:            DataFrame of daily close prices
        selected_tickers:  Ordered list (by Total_Score); we take as-is
        freq:              Rebalance frequency ('ME' = month-end)
    Returns:
        Metrics dict, or None if < 4 return observations.
    """
    # Restrict to tickers that actually have price data
    alive = [t for t in selected_tickers if t in prices.columns]
    if not alive:
        return None

    # Use resample to get end-of-period price rows directly.
    # iloc-based access avoids the KeyError caused by resample labelling rows
    # with calendar period-ends (e.g. 2021-05-31) that may be market holidays.
    monthly = prices[alive].resample(freq).last()
    rets, dates = [], []

    for i in range(len(monthly) - 1):
        cp  = monthly.iloc[i].dropna()
        np_ = monthly.iloc[i + 1].dropna()

        # Only trade stocks alive in BOTH periods (handles delisting)
        common = cp.index.intersection(np_.index)
        common = common[cp[common] > 0]
        if common.empty:
            continue

        ret = float((np_[common] / cp[common] - 1).mean())
        rets.append(ret)
        dates.append(monthly.index[i + 1])

    return _compute_metrics(rets, dates, freq)


def run_momentum_backtest(prices, top_n, lookback_days=63, freq='ME'):
    """
    Config C: Dynamic momentum selection within gem universe.
    At each rebalance date, picks top-N by 3M momentum (walk-forward safe).
    Mirrors run_config() in 05_backtest_engine.py.

    Args:
        prices:       DataFrame of daily close prices
        top_n:        Number of stocks to hold each period
        lookback_days: Momentum lookback window in trading days
        freq:         Rebalance frequency
    Returns:
        Metrics dict, or None if < 4 return observations.
    """
    if prices.empty or len(prices) < lookback_days * 2:
        return None

    # Resample to get end-of-period rows; use iloc for price access (not loc[date])
    # to avoid KeyError when period-end labels fall on market holidays.
    monthly = prices.resample(freq).last()
    rets, dates = [], []

    for i in range(len(monthly) - 1):
        # Calendar period-end date used only for momentum score cutoff (loc[:date] is safe)
        cal_date = monthly.index[i]

        score = _momentum_score_at(prices, cal_date, lookback_days)
        n_sel = min(top_n, len(score))
        if n_sel < 2:
            continue

        top = score.nlargest(n_sel).index.tolist()
        cp  = monthly.iloc[i]
        np_ = monthly.iloc[i + 1]

        valid = [t for t in top
                 if t in cp.index and t in np_.index
                 and not np.isnan(float(cp[t])) and not np.isnan(float(np_[t]))
                 and float(cp[t]) > 0]
        if not valid:
            continue

        ret = float((np_[valid] / cp[valid] - 1).mean())
        rets.append(ret)
        dates.append(monthly.index[i + 1])

    return _compute_metrics(rets, dates, freq)


def _compute_metrics(rets, dates, freq='ME'):
    """Compute full performance metrics from period-return list."""
    if len(rets) < 4:
        return None

    ann    = 52 if freq == 'W' else 12
    ret_s  = pd.Series(rets, index=pd.DatetimeIndex(dates))
    cumret = (1 + ret_s).cumprod()
    dd     = (cumret / cumret.cummax()) - 1
    n      = len(ret_s)

    return {
        'n':         n,
        'start':     dates[0],
        'end':       dates[-1],
        'cagr':      (1 + ret_s).prod() ** (ann / n) - 1,
        'sharpe':    ret_s.mean() / (ret_s.std() + 1e-9) * np.sqrt(ann),
        'max_dd':    dd.min(),
        'total_ret': cumret.iloc[-1] - 1,
        'win_rate':  (ret_s > 0).mean(),
        'avg_ret':   ret_s.mean(),
        'best':      ret_s.max(),
        'worst':     ret_s.min(),
        'ret_series': ret_s,
        'cumret':     cumret,
        'drawdown':   dd,
    }


# ══════════════════════════════════════════════════════════════════════════════
#  STEP 4 — Save results + console output
# ══════════════════════════════════════════════════════════════════════════════

def _fmt_metrics_block(label, m):
    if m is None:
        return [[f"── {label} ──", "No data"]]
    return [
        [f"── {label} ──", ""],
        ["Period",      f"{m['start'].strftime('%Y-%m')} → {m['end'].strftime('%Y-%m')}"],
        ["Months",      str(m['n'])],
        ["CAGR",        f"{m['cagr']:.4f}"],
        ["Sharpe",      f"{m['sharpe']:.4f}"],
        ["MaxDrawdown", f"{m['max_dd']:.4f}"],
        ["TotalReturn", f"{m['total_ret']:.4f}"],
        ["WinRate",     f"{m['win_rate']:.4f}"],
        ["AvgMonthly",  f"{m['avg_ret']:.4f}"],
        ["BestMonth",   f"{m['best']:.4f}"],
        ["WorstMonth",  f"{m['worst']:.4f}"],
        ["", ""],
    ]


def print_metrics(label, m):
    if m is None:
        print(f"  {label:35s}: No data")
        return
    print(f"  {label:35s}: CAGR={m['cagr']:+.1%}  Sharpe={m['sharpe']:.2f}  "
          f"MaxDD={m['max_dd']:.1%}  WinRate={m['win_rate']:.0%}  "
          f"({m['start'].strftime('%Y-%m')}→{m['end'].strftime('%Y-%m')}, {m['n']}mo)")


def _save_market_results(results_map, tickers, sheet_name, market_label):
    """Write one market's backtest results to its own sheet."""
    now = datetime.now().strftime("%Y-%m-%d %H:%M")

    header_block = [
        [f"── {sheet_name} ──", ""],
        ["Generated",  now],
        ["Market",     market_label],
        ["Strategy",   "Small-Cap Gems · Monthly Rebalancing · Equal Weight"],
        ["Lookback",   f"{LOOKBACK_DAYS}d momentum (Momentum config only)"],
        ["Tickers",    ", ".join(tickers)],
        ["", ""],
    ]

    metrics_blocks = []
    for label, m in results_map.items():
        metrics_blocks += _fmt_metrics_block(label, m)

    detail_rows = [["── Monthly Returns Detail ──"]]
    series_parts = {
        label[:22]: m['ret_series']
        for label, m in results_map.items()
        if m is not None
    }
    if series_parts:
        aligned = pd.concat(series_parts, axis=1)
        detail_rows.append(['Date'] + list(aligned.columns))
        for dt, row in aligned.iterrows():
            detail_rows.append(
                [dt.strftime('%Y-%m-%d')] +
                [f"{v:.4f}" if not pd.isna(v) else "" for v in row.values])

    all_rows = header_block + metrics_blocks + detail_rows

    try:
        ws = spreadsheet.worksheet(sheet_name)
    except Exception:
        ws = spreadsheet.add_worksheet(title=sheet_name, rows=800, cols=12)
    ws.clear()
    ws.update(range_name='A1', values=all_rows, value_input_option='USER_ENTERED')
    market = 'KR' if sheet_name.startswith('KR_') else 'US' if sheet_name.startswith('US_') else None
    if series_parts:
        storage_df = aligned.reset_index().rename(columns={'index': 'Date'})
        storage_df['Date'] = storage_df['Date'].dt.strftime('%Y-%m-%d')
        dual_write_dataframe(sheet_name, storage_df, market=market)
    else:
        metric_records = [
            {'Metric': row[0], 'Value': row[1], 'Market': market or 'GLOBAL'}
            for row in metrics_blocks
            if len(row) >= 2 and str(row[0]).strip()
        ]
        dual_write_dataframe(sheet_name, pd.DataFrame(metric_records), market=market)
    print(f"  ✅ Saved {len(all_rows)} rows → '{sheet_name}'")


# ══════════════════════════════════════════════════════════════════════════════
#  MAIN
# ══════════════════════════════════════════════════════════════════════════════

def main(force_rescan=False):
    print("=" * 65)
    print("  08_smallcap_backtest.py")
    print(f"  {datetime.now().strftime('%Y-%m-%d %H:%M')}")
    print("=" * 65)

    # ── Market filter (set by --ussmallcap / --krsmallcap in main_engine.py) ─
    _sc_market = os.environ.get('QUANT_SMALLCAP_MARKET', '').upper()
    RUN_US = _sc_market in ('', 'BOTH', 'US')
    RUN_KR = _sc_market in ('', 'BOTH', 'KR')
    if _sc_market == 'US':
        print("\n🔬 SMALLCAP MARKET FILTER: US only")
    elif _sc_market == 'KR':
        print("\n🔬 SMALLCAP MARKET FILTER: KR only")

    # ── 1. Gem scanner ────────────────────────────────────────────────────────
    print("\n[1/4] Gem Scanner")
    run_gem_scanner(force=force_rescan, run_us=RUN_US, run_kr=RUN_KR)

    # ── 2. Load gems ──────────────────────────────────────────────────────────
    print("\n[2/4] Loading Gems from Sheets")
    us_gems = load_gems('US_SmallCap_Gems') if RUN_US else []
    kr_gems = load_gems('KR_SmallCap_Gems') if RUN_KR else []
    print(f"  US: {len(us_gems)} gems  |  KR: {len(kr_gems)} gems")

    if not us_gems and not kr_gems:
        print("\n  ❌ No gem data found.")
        print("     Run:  python pipeline/07_smallcap_scanner.py")
        print("     Then: python pipeline/08_smallcap_backtest.py")
        return

    # Tickers are yfinance-ready: US = "AAPL", KR = "005930.KS"
    us_tickers = [g['Ticker'] for g in us_gems]
    kr_tickers = [g['Ticker'] for g in kr_gems]

    # ── 3. Download prices + run backtests ────────────────────────────────────
    print("\n[3/4] Downloading Prices + Running Backtests")
    us_results = {}   # label → metrics dict (US only)
    kr_results = {}   # label → metrics dict (KR only)

    # ── US ────────────────────────────────────────────────────────────────────
    us_prices = pd.DataFrame()
    if RUN_US and us_tickers:
        print(f"\n  [US] Downloading {len(us_tickers)} tickers ({BACKTEST_PERIOD})...")
        us_prices = download_prices(us_tickers, period=BACKTEST_PERIOD)
        kept = us_prices.shape[1] if not us_prices.empty else 0
        dropped = len(us_tickers) - kept
        print(f"  [US] Price matrix: {us_prices.shape[0]} days × {kept} stocks"
              + (f"  ({dropped} delisted/unavailable dropped)" if dropped else ""))

        for top_n in TOP_N_OPTIONS:
            label = f"US Fixed Top-{top_n}"
            m     = run_fixed_backtest(us_prices, us_tickers[:top_n])
            us_results[label] = m
            print_metrics(label, m)

        label = "US Momentum Top-10 (gem universe)"
        m     = run_momentum_backtest(us_prices, top_n=10,
                                      lookback_days=LOOKBACK_DAYS)
        us_results[label] = m
        print_metrics(label, m)
    elif not RUN_US:
        print("\n  ⏭️  Skipping US backtest (QUANT_SMALLCAP_MARKET=KR)")

    # ── KR ────────────────────────────────────────────────────────────────────
    kr_prices = pd.DataFrame()
    if RUN_KR and kr_tickers:
        print(f"\n  [KR] Downloading {len(kr_tickers)} tickers ({BACKTEST_PERIOD})...")
        kr_prices = download_prices(kr_tickers, period=BACKTEST_PERIOD)
        kept = kr_prices.shape[1] if not kr_prices.empty else 0
        dropped = len(kr_tickers) - kept
        print(f"  [KR] Price matrix: {kr_prices.shape[0]} days × {kept} stocks"
              + (f"  ({dropped} delisted/unavailable dropped)" if dropped else ""))

        for top_n in TOP_N_OPTIONS:
            label = f"KR Fixed Top-{top_n}"
            m     = run_fixed_backtest(kr_prices, kr_tickers[:top_n])
            kr_results[label] = m
            print_metrics(label, m)

        label = "KR Momentum Top-10 (gem universe)"
        m     = run_momentum_backtest(kr_prices, top_n=10,
                                      lookback_days=LOOKBACK_DAYS)
        kr_results[label] = m
        print_metrics(label, m)
    elif not RUN_KR:
        print("\n  ⏭️  Skipping KR backtest (QUANT_SMALLCAP_MARKET=US)")

    # ── 4. Save to separate sheets ────────────────────────────────────────────
    print("\n[4/4] Saving Results to Google Sheets")
    if RUN_US:
        _save_market_results(us_results, us_tickers,
                             sheet_name="US_SmallCap_Backtest", market_label="US")
    if RUN_KR:
        _save_market_results(kr_results, kr_tickers,
                             sheet_name="KR_SmallCap_Backtest", market_label="KR")

    # ── Sample performance 2024–2026 ──────────────────────────────────────────
    print(f"\n{'='*65}")
    print("  SAMPLE PERFORMANCE  (2024–2026)")
    print(f"{'─'*65}")
    print(f"  {'Config':<36} {'CAGR':>7}  {'TotRet':>7}  {'Sharpe':>6}  {'n':>4}")
    print(f"  {'─'*36} {'─'*7}  {'─'*7}  {'─'*6}  {'─'*4}")

    combined = (list(us_results.items()) if RUN_US else []) + \
               (list(kr_results.items()) if RUN_KR else [])
    for label, m in combined:
        if m is None:
            continue
        rs = m['ret_series']

        recent = rs.loc['2024':'2026']
        if len(recent) < 3:
            recent = rs

        rc     = (1 + recent).cumprod()
        n_mo   = len(recent)
        cagr   = (1 + recent).prod() ** (12 / max(n_mo, 1)) - 1
        tret   = rc.iloc[-1] - 1 if len(rc) else 0.0
        sharpe = recent.mean() / (recent.std() + 1e-9) * np.sqrt(12)
        print(f"  {label:<36} {cagr:>+6.1%}  {tret:>+6.1%}  "
              f"{sharpe:>6.2f}  {n_mo:>4}")

    print(f"\n  ⚡ Expected CAGR 25–40% for small-cap growth portfolio")
    print(f"  ⚠️  Past performance does not guarantee future results.")
    print(f"{'='*65}")


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(
        description='08_backtest_integration — Small-Cap Gem Backtester')
    parser.add_argument('--rescan', action='store_true',
                        help='Force re-run of 07_smallcap_scanner.py before backtesting')
    args = parser.parse_args()
    main(force_rescan=args.rescan)
