# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
"""
09_industry_ranking.py — Bottom-up Industry Power Ranking
==========================================================

Philosophy
----------
Instead of top-down macro calls ("Tech is hot this month"), this module
builds industry rankings *bottom-up*: it looks at the actual price
behaviour of every individual stock in the universe, groups them by their
granular yfinance `industry` label, and aggregates two signals per group:

  Mean Return  — average % return of all stocks in the industry over the
                 lookback window.  Captures the central tendency of the
                 group's momentum.

  Breadth      — fraction of stocks in the industry with a positive return
                 (win rate).  Captures how *broad* the move is — a Mean
                 Return of +5 % driven by one outlier scores differently
                 from one driven by 8 out of 10 stocks.

The two signals are independently ranked (highest value → rank 1) and then
summed into a Combined_Rank.  A low Combined_Rank indicates an industry
that is both moving up AND doing so broadly — the strongest confluence.

Pipeline position
-----------------
Runs after 06_portfolio_optimizer.py (US scored stocks must exist).
Writes results to two Google Sheets worksheets:
  • Industry_Map        — cached ticker→industry mapping (refreshed every 30 days)
  • US_Industry_Ranking — final power ranking table

Run standalone:
  python pipeline/09_industry_ranking.py
  python pipeline/09_industry_ranking.py --force-refresh   # ignore cache age
"""

import gspread
import argparse
import time
import warnings
from datetime import datetime

import numpy as np
import pandas as pd
import yfinance as yf
from sheets_client import get_spreadsheet
from quantbridge.writers.dual_write import dual_write_dataframe

warnings.filterwarnings('ignore')

# ── CLI flags ─────────────────────────────────────────────────────────────────
_parser = argparse.ArgumentParser(add_help=False)
_parser.add_argument('--force-refresh', action='store_true',
                     help='Re-fetch all industry data from yfinance, ignoring cache age')
_args, _ = _parser.parse_known_args()

TEST_MODE     = os.environ.get('QUANT_TEST_MODE') == 'true'
FORCE_REFRESH = _args.force_refresh
TEST_LIMIT    = 30   # tickers kept in test mode (enough to get a few industries)

if TEST_MODE:
    print("\n⚠️  TEST MODE: trimmed to first 30 tickers")

# ── Tunable constants ─────────────────────────────────────────────────────────
LOOKBACK_DAYS  = 20   # trading days for return / breadth calculation (~1 month)
MIN_STOCKS     = 3    # minimum stocks per industry (fewer → statistically unreliable)
CACHE_MAX_DAYS = 30   # days before Industry_Map cache entry is considered stale
PRICE_PERIOD   = "3mo"  # yfinance period for price fetch (covers LOOKBACK_DAYS + buffer)
PRICE_BATCH    = 50   # tickers per yfinance download batch
FETCH_DELAY    = 0.4  # seconds between per-ticker yfinance.Ticker() calls

# ── Sheet names ───────────────────────────────────────────────────────────────
SHEET_INDUSTRY_MAP     = "Industry_Map"
SHEET_INDUSTRY_RANKING = "US_Industry_Ranking"

# ── Canonical column schemas ──────────────────────────────────────────────────
INDUSTRY_MAP_COLS = ['Ticker', 'Industry', 'Sector', 'Last_Updated']

INDUSTRY_RANKING_COLS = [
    'Rank',          # final power rank (1 = strongest industry)
    'Industry',      # granular yfinance industry label
    'Sector',        # broad yfinance sector label
    'Stock_Count',   # number of stocks in this industry with price data
    'Mean_Return',   # average return over LOOKBACK_DAYS (decimal, 4dp)
    'Breadth',       # win rate = fraction of stocks with return > 0 (decimal, 4dp)
    'Mean_Return_Rank',  # rank by Mean_Return alone (1 = highest mean return)
    'Breadth_Rank',      # rank by Breadth alone (1 = highest win rate)
    'Combined_Rank',     # Mean_Return_Rank + Breadth_Rank (lower = stronger)
    'Top_Tickers',   # top 3 performers within the industry (comma-separated)
    'Lookback_Days', # lookback window used for this run
    'Last_Updated',  # date this ranking was generated
]

# ── Google Sheets connection ──────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()


# ═════════════════════════════════════════════════════════════════════════════
# SECTION 1 — INDUSTRY MAP CACHE
# ═════════════════════════════════════════════════════════════════════════════

def _get_or_create_sheet(name: str, rows: int = 5000, cols: int = 15) -> gspread.Worksheet:
    """Return an existing worksheet by name, or create a new one."""
    try:
        return spreadsheet.worksheet(name)
    except gspread.exceptions.WorksheetNotFound:
        print(f"  [Sheets] Creating new worksheet: '{name}'")
        return spreadsheet.add_worksheet(title=name, rows=rows, cols=cols)


def _is_stale(date_str: str, max_days: int = CACHE_MAX_DAYS) -> bool:
    """Return True if date_str is older than max_days, or if it is missing/invalid."""
    if not date_str:
        return True
    try:
        last = datetime.strptime(date_str[:10], '%Y-%m-%d')
        return (datetime.now() - last).days > max_days
    except Exception:
        return True


def _load_industry_cache() -> dict:
    """
    Load the Industry_Map sheet into a dict.

    Returns
    -------
    {ticker: {'Industry': str, 'Sector': str, 'Last_Updated': str}}
    or {} if the sheet is empty or does not exist.
    """
    try:
        ws   = spreadsheet.worksheet(SHEET_INDUSTRY_MAP)
        data = ws.get_all_values()
        if len(data) < 2:
            return {}
        df = pd.DataFrame(data[1:], columns=data[0])
        df = df[df['Ticker'].str.strip() != '']
        return {
            row['Ticker']: {
                'Industry':    row.get('Industry',    ''),
                'Sector':      row.get('Sector',      ''),
                'Last_Updated': row.get('Last_Updated', ''),
            }
            for _, row in df.iterrows()
        }
    except gspread.exceptions.WorksheetNotFound:
        return {}


def fetch_and_cache_industry_map(tickers: list, force_refresh: bool = False) -> dict:
    """
    Build a ticker → {Industry, Sector} mapping.

    Strategy
    --------
    1. Load the existing Industry_Map cache from Google Sheets.
    2. Identify tickers that are either missing from the cache or whose
       'Last_Updated' date is older than CACHE_MAX_DAYS.
    3. Fetch only those tickers from yfinance (one API call per ticker).
    4. Merge the new results into the cache and persist the full cache back
       to the Industry_Map sheet.
    5. Return the clean {ticker: {Industry, Sector}} dict (no timestamps).

    Parameters
    ----------
    tickers       : list[str]  — full ticker universe to map
    force_refresh : bool       — skip cache age check; re-fetch every ticker

    Returns
    -------
    dict {ticker: {'Industry': str, 'Sector': str}}
    """
    print("[INDUSTRY] Loading Industry_Map cache from Google Sheets...")
    cache = {} if force_refresh else _load_industry_cache()

    today_str = datetime.now().strftime('%Y-%m-%d')
    to_fetch  = [
        t for t in tickers
        if t not in cache or _is_stale(cache.get(t, {}).get('Last_Updated', ''))
    ]

    if not to_fetch:
        print(f"[INDUSTRY] Cache is fully up-to-date ({len(cache)} tickers). "
              f"No yfinance calls needed.")
    else:
        print(f"[INDUSTRY] Fetching industry data for {len(to_fetch)} tickers "
              f"({len(tickers) - len(to_fetch)} already cached, "
              f"{len(to_fetch)} stale/missing)...")

        for i, ticker in enumerate(to_fetch):
            try:
                info = yf.Ticker(ticker).info
                industry = info.get('industry') or 'Unknown'
                sector   = info.get('sector')   or 'Unknown'
            except Exception:
                industry, sector = 'Unknown', 'Unknown'

            cache[ticker] = {
                'Industry':    industry,
                'Sector':      sector,
                'Last_Updated': today_str,
            }

            if (i + 1) % 25 == 0 or (i + 1) == len(to_fetch):
                print(f"  [{i+1}/{len(to_fetch)}] fetched  "
                      f"(last: {ticker} → {industry})")
            time.sleep(FETCH_DELAY)

        # ── Persist updated cache back to Google Sheets ───────────────────────
        print("[INDUSTRY] Saving updated Industry_Map to Google Sheets...")
        rows = [
            [t, cache[t]['Industry'], cache[t]['Sector'], cache[t]['Last_Updated']]
            for t in tickers
            if t in cache
        ]
        ws = _get_or_create_sheet(SHEET_INDUSTRY_MAP)
        ws.clear()
        ws.update([INDUSTRY_MAP_COLS] + rows)
        print(f"[INDUSTRY] Industry_Map saved: {len(rows)} tickers.")

    # Return clean mapping without internal cache timestamps
    return {
        t: {'Industry': cache[t]['Industry'], 'Sector': cache[t]['Sector']}
        for t in tickers
        if t in cache
    }


# ═════════════════════════════════════════════════════════════════════════════
# SECTION 2 — PRICE DOWNLOAD
# ═════════════════════════════════════════════════════════════════════════════

def download_prices(tickers: list, period: str = PRICE_PERIOD) -> pd.DataFrame:
    """
    Batch-download adjusted close prices for `tickers`.

    Uses the same batching pattern as the rest of the pipeline.
    Drops any column where more than 30 % of rows are NaN.

    Parameters
    ----------
    tickers : list[str]
    period  : str — yfinance period string (e.g. '3mo', '1y')

    Returns
    -------
    pd.DataFrame — index=DatetimeIndex, columns=ticker symbols
    """
    print(f"[INDUSTRY] Downloading prices: {len(tickers)} tickers, period={period}...")
    frames = []

    for i in range(0, len(tickers), PRICE_BATCH):
        batch = tickers[i:i + PRICE_BATCH]
        try:
            raw    = yf.download(batch, period=period, auto_adjust=True, progress=False)
            closes = raw['Close'] if isinstance(raw.columns, pd.MultiIndex) else raw
            if isinstance(closes, pd.Series):
                closes = closes.to_frame(name=batch[0])
            frames.append(closes)
        except Exception as e:
            print(f"  [price] batch {i}: {e}")
        time.sleep(1.0)

    if not frames:
        raise RuntimeError("[INDUSTRY] Price download returned no data.")

    prices = pd.concat(frames, axis=1)
    prices = prices.loc[:, ~prices.columns.duplicated()]
    prices = prices.dropna(axis=1, thresh=int(len(prices) * 0.70))
    print(f"[INDUSTRY] Prices ready: {prices.shape[1]} tickers × {prices.shape[0]} days")
    return prices


# ═════════════════════════════════════════════════════════════════════════════
# SECTION 3 — POWER RANKING LOGIC
# ═════════════════════════════════════════════════════════════════════════════

def compute_industry_power_ranking(
    prices: pd.DataFrame,
    industry_map: dict,
    lookback: int = LOOKBACK_DAYS,
) -> pd.DataFrame:
    """
    Bottom-up Industry Power Ranking.

    Algorithm
    ---------
    For each granular yfinance `industry` group:

      Step 1 — Collect members
        All tickers whose industry_map entry matches this industry AND whose
        price data exists in `prices`.

      Step 2 — Compute per-stock returns
        return_i = (close_today / close_{today - lookback_days}) − 1
        Uses the last `lookback` trading bars.

      Step 3 — Industry-level aggregation
        Mean Return  = mean(return_i  for all i in industry)
        Breadth      = count(return_i > 0) / total stocks in industry

      Step 4 — Filter
        Drop any industry with fewer than MIN_STOCKS stocks.
        Small samples produce unreliable aggregates.

      Step 5 — Dual-axis ranking
        Mean_Return_Rank : rank by Mean_Return  descending (1 = highest return)
        Breadth_Rank     : rank by Breadth      descending (1 = highest win rate)
        Combined_Rank    = Mean_Return_Rank + Breadth_Rank
        Final sort       : Combined_Rank ascending (lowest = strongest industry)

    Parameters
    ----------
    prices       : pd.DataFrame — daily close prices (rows=dates, cols=tickers)
    industry_map : dict         — {ticker: {'Industry': str, 'Sector': str}}
    lookback     : int          — number of trading days for the return window

    Returns
    -------
    pd.DataFrame with INDUSTRY_RANKING_COLS schema.
    """
    if len(prices) < lookback + 1:
        raise ValueError(
            f"Price history too short: {len(prices)} rows, need ≥ {lookback + 1}."
        )

    # ── Step 1 & 2: Per-stock returns ─────────────────────────────────────────
    price_end   = prices.iloc[-1]
    price_start = prices.iloc[-(lookback + 1)]
    stock_returns = (price_end / price_start - 1).dropna()

    # ── Step 3: Group by granular industry ───────────────────────────────────
    # Build: industry → list of tickers that (a) belong to it and (b) have prices
    industry_members: dict[str, list] = {}
    industry_sector:  dict[str, str]  = {}

    for ticker, meta in industry_map.items():
        industry = (meta.get('Industry') or '').strip()
        sector   = (meta.get('Sector')   or '').strip()

        if not industry or industry.lower() == 'unknown':
            continue
        if ticker not in stock_returns.index:
            continue   # no price data → cannot contribute

        industry_members.setdefault(industry, []).append(ticker)
        industry_sector[industry] = sector  # same industry → same sector

    # ── Step 4: Compute industry metrics & filter ─────────────────────────────
    records = []
    for industry, members in industry_members.items():
        if len(members) < MIN_STOCKS:
            continue   # too few stocks → skip

        rets        = stock_returns[members]
        mean_ret    = float(rets.mean())
        breadth     = float((rets > 0).sum() / len(rets))
        top_tickers = ', '.join(
            rets.sort_values(ascending=False).head(3).index.tolist()
        )

        records.append({
            'Industry':    industry,
            'Sector':      industry_sector.get(industry, ''),
            'Stock_Count': len(members),
            'Mean_Return': round(mean_ret, 4),
            'Breadth':     round(breadth,  4),
            'Top_Tickers': top_tickers,
        })

    if not records:
        print("[INDUSTRY] ⚠️  No industries had ≥ MIN_STOCKS stocks. "
              "Try lowering MIN_STOCKS or using a broader universe.")
        return pd.DataFrame(columns=INDUSTRY_RANKING_COLS)

    df = pd.DataFrame(records)

    # ── Step 5: Dual-axis ranking ─────────────────────────────────────────────
    df['Mean_Return_Rank'] = (
        df['Mean_Return'].rank(ascending=False, method='min').astype(int)
    )
    df['Breadth_Rank'] = (
        df['Breadth'].rank(ascending=False, method='min').astype(int)
    )
    df['Combined_Rank'] = df['Mean_Return_Rank'] + df['Breadth_Rank']

    # ── Sort: primary = Combined_Rank (asc), tiebreak = Mean_Return (desc) ───
    df = (
        df
        .sort_values(['Combined_Rank', 'Mean_Return'], ascending=[True, False])
        .reset_index(drop=True)
    )
    df['Rank']          = df.index + 1
    df['Lookback_Days'] = lookback
    df['Last_Updated']  = datetime.now().strftime('%Y-%m-%d')

    return df[INDUSTRY_RANKING_COLS]


# ═════════════════════════════════════════════════════════════════════════════
# SECTION 4 — GOOGLE SHEETS EXPORT
# ═════════════════════════════════════════════════════════════════════════════

def export_ranking_to_sheets(ranking_df: pd.DataFrame, lookback: int = LOOKBACK_DAYS):
    """
    Write the Industry Power Ranking to the US_Industry_Ranking worksheet.

    Sheet layout
    ────────────
    Rows 1–8  : Metadata summary block
    Row  9    : Blank separator
    Row  10   : Column headers  (INDUSTRY_RANKING_COLS)
    Row  11+  : Data rows

    Parameters
    ----------
    ranking_df : pd.DataFrame — output of compute_industry_power_ranking()
    lookback   : int          — lookback window used for this run (shown in summary)
    """
    ws = _get_or_create_sheet(SHEET_INDUSTRY_RANKING, rows=500, cols=len(INDUSTRY_RANKING_COLS) + 2)

    n_industries = len(ranking_df)
    n_stocks     = int(ranking_df['Stock_Count'].sum()) if not ranking_df.empty else 0

    summary = [
        ["── US Industry Power Ranking ──",  ""],
        ["Strategy",        "Bottom-up Industry Power Ranking"],
        ["Lookback (days)",  str(lookback)],
        ["Industries ranked", str(n_industries)],
        ["Total stocks covered", str(n_stocks)],
        ["Min stocks / industry", str(MIN_STOCKS)],
        ["Scoring",         "Combined_Rank = Mean_Return_Rank + Breadth_Rank (lower = stronger)"],
        ["Generated",       pd.Timestamp.now().strftime('%Y-%m-%d %H:%M')],
        ["", ""],
    ]

    out_df = ranking_df.fillna('').astype(str)
    rows   = summary + [out_df.columns.tolist()] + out_df.values.tolist()

    ws.clear()
    ws.update(range_name='A1', values=rows, value_input_option='USER_ENTERED')
    dual_write_dataframe(SHEET_INDUSTRY_RANKING, ranking_df, market="US")
    print(f"✅ [INDUSTRY] {n_industries} industries → '{SHEET_INDUSTRY_RANKING}'")


# ═════════════════════════════════════════════════════════════════════════════
# SECTION 5 — MAIN ORCHESTRATOR
# ═════════════════════════════════════════════════════════════════════════════

def _load_tickers_from_sheet(sheet_name: str, ticker_col: str = 'Ticker') -> list:
    """
    Safely load all non-empty ticker values from a Google Sheet column.
    Returns [] if the sheet doesn't exist or the column is missing.
    """
    try:
        data = spreadsheet.worksheet(sheet_name).get_all_values()
        if len(data) < 2:
            return []
        df = pd.DataFrame(data[1:], columns=data[0])
        if ticker_col not in df.columns:
            return []
        tickers = df[ticker_col].dropna().str.strip().tolist()
        return [t for t in tickers if t]
    except Exception as e:
        print(f"  [INDUSTRY] Could not load '{sheet_name}': {e}")
        return []


def main():
    print("\n" + "═" * 65)
    print("  09 — Bottom-up US Industry Power Ranking")
    print("═" * 65)

    # ── 1. Build the broadest possible US ticker universe ─────────────────────
    #
    # The wider the universe, the more stocks per industry group → more reliable
    # Mean Return and Breadth signals.  We layer three sources and deduplicate:
    #
    #  Layer 1 — US_Universe       : S&P 500 + NASDAQ-100 (~600 tickers)
    #                                Written by 01_universe_expander.py
    #  Layer 2 — Company_Master    : all previously cached tickers
    #                                (includes past smallcap scan results, etc.)
    #  Layer 3 — US_Scored_Stocks  : fallback if both above are unavailable
    #
    # KR tickers (.KS / .KQ) are excluded — this is a US-only module.
    # Result: typically 600–900 unique US tickers.

    print("[INDUSTRY] Building expanded US ticker universe...")

    raw_us_universe    = _load_tickers_from_sheet("US_Universe")
    raw_company_master = _load_tickers_from_sheet("Company_Master")
    raw_scored_stocks  = _load_tickers_from_sheet("US_Scored_Stocks")

    print(f"  US_Universe     : {len(raw_us_universe):>5} tickers")
    print(f"  Company_Master  : {len(raw_company_master):>5} tickers  "
          f"(includes NASDAQ + smallcap cache)")
    print(f"  US_Scored_Stocks: {len(raw_scored_stocks):>5} tickers  (quality-filtered subset)")

    # Merge in priority order; exclude KR tickers; deduplicate while preserving order
    seen, tickers = set(), []
    for t in raw_us_universe + raw_company_master + raw_scored_stocks:
        if t and t not in seen and not t.endswith(('.KS', '.KQ')):
            seen.add(t)
            tickers.append(t)

    print(f"  ─────────────────────────────────────")
    print(f"  Combined (US only, deduped): {len(tickers)} tickers")

    if not tickers:
        print("[INDUSTRY] ❌ No tickers found. Run pipeline step 01 first.")
        return

    if TEST_MODE:
        tickers = tickers[:TEST_LIMIT]
        print(f"⚠️  TEST MODE: trimmed to {len(tickers)} tickers")

    # ── 2. Fetch / load the ticker → industry map (cached) ───────────────────
    industry_map = fetch_and_cache_industry_map(tickers, force_refresh=FORCE_REFRESH)

    # Show industry coverage stats
    known    = sum(1 for v in industry_map.values() if v['Industry'] not in ('Unknown', '', None))
    n_unique = len({v['Industry'] for v in industry_map.values()
                    if v['Industry'] not in ('Unknown', '', None)})
    print(f"[INDUSTRY] Coverage: {known}/{len(tickers)} tickers mapped "
          f"→ {n_unique} unique industries")

    # ── 3. Download prices ────────────────────────────────────────────────────
    prices = download_prices(tickers, period=PRICE_PERIOD)

    if len(prices) < LOOKBACK_DAYS + 1:
        print(f"[INDUSTRY] ❌ Not enough price rows ({len(prices)}) for "
              f"lookback={LOOKBACK_DAYS}. Exiting.")
        return

    # ── 4. Compute industry power ranking ────────────────────────────────────
    print(f"[INDUSTRY] Computing power ranking  "
          f"(lookback={LOOKBACK_DAYS} days, min_stocks={MIN_STOCKS})...")
    ranking_df = compute_industry_power_ranking(prices, industry_map, lookback=LOOKBACK_DAYS)

    if ranking_df.empty:
        print("[INDUSTRY] No ranking produced. Exiting.")
        return

    # ── 5. Print top-15 summary to console ───────────────────────────────────
    print(f"\n[INDUSTRY] ── Top 15 Industries  "
          f"(lookback={LOOKBACK_DAYS}d) ─────────────────────")
    print(f"  {'Rk':>3}  {'Industry':<42}  {'#':>3}  "
          f"{'MeanRet':>8}  {'Breadth':>8}  {'CombRank':>9}")
    print(f"  {'─'*3}  {'─'*42}  {'─'*3}  {'─'*8}  {'─'*8}  {'─'*9}")
    for _, row in ranking_df.head(15).iterrows():
        print(
            f"  {int(row['Rank']):>3}  "
            f"{row['Industry']:<42}  "
            f"{int(row['Stock_Count']):>3}  "
            f"{float(row['Mean_Return']):>7.2%}  "
            f"{float(row['Breadth']):>7.2%}  "
            f"{int(row['Combined_Rank']):>8}"
        )

    # ── 6. Export to Google Sheets ────────────────────────────────────────────
    export_ranking_to_sheets(ranking_df, lookback=LOOKBACK_DAYS)


if __name__ == '__main__':
    main()
