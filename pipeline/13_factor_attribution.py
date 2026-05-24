# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
"""
pipeline/14_factor_attribution.py
===================================
Barra-Style Factor Attribution

Decomposes portfolio return (since last rebalance) into:

    Portfolio Return = Value Factor Return
                     + Quality Factor Return
                     + Momentum Factor Return
                     + Residual (stock-specific / unexplained)

Methodology:
  1. Read Final_Portfolio sheets → weights + rebalance date
  2. Read Scored_Stocks sheets → V/Q/M factor scores per stock
  3. Download prices from rebalance date → today → compute per-stock returns
  4. Z-score normalize factor scores across ALL scored stocks (not just portfolio)
     to get unbiased factor exposures
  5. OLS cross-sectional regression across all scored stocks:
         r_i = β_V·f_V(i) + β_Q·f_Q(i) + β_M·f_M(i) + ε_i
     → β_V, β_Q, β_M = "factor returns" (return per unit of exposure)
  6. Portfolio factor exposures:
         h_j = Σ w_i · f_j(i)   (weight-averaged exposure over portfolio holdings)
  7. Attribution:
         Contrib_V = h_V × β_V
         Contrib_Q = h_Q × β_Q
         Contrib_M = h_M × β_M
         Residual  = Portfolio_Return − (Contrib_V + Contrib_Q + Contrib_M)

Output: Factor_Attribution sheet (summary + per-stock detail for US and KR)

Run standalone:
    python pipeline/14_factor_attribution.py
"""

import time
import warnings
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

# ── Output schemas ────────────────────────────────────────────────────────────
SUMMARY_COLS = [
    'Market', 'Period_Start', 'Period_End', 'Days',
    'Portfolio_Return',
    'Value_Contrib', 'Quality_Contrib', 'Momentum_Contrib', 'Residual',
    'Beta_V', 'Beta_Q', 'Beta_M', 'R_Squared',
    'N_Portfolio', 'N_Universe', 'Generated',
]

DETAIL_COLS = [
    'Market', 'Ticker', 'Name', 'Sector',
    'Weight', 'Return',
    'V_Exposure', 'Q_Exposure', 'M_Exposure',
    'Predicted_Return', 'Stock_Residual',
]

PRICE_DELAY  = 0.4   # seconds between yfinance batch calls


# ── Helpers ───────────────────────────────────────────────────────────────────

def _to_float(v, default=None):
    try:
        return float(v) if v not in ('', None) else default
    except (ValueError, TypeError):
        return default


def _parse_portfolio_sheet(sheet_name: str) -> tuple[pd.DataFrame, str]:
    """
    Read a Final_Portfolio sheet.
    Returns (df, rebal_date_str  'YYYY-MM-DD').
    The sheet layout: rows 1-10 = key-value summary block, then header + data.
    """
    try:
        ws   = spreadsheet.worksheet(sheet_name)
        rows = ws.get_all_values()
    except gspread.exceptions.WorksheetNotFound:
        print(f"  ⚠️  Sheet not found: {sheet_name}")
        return pd.DataFrame(), ''

    if not rows:
        return pd.DataFrame(), ''

    rebal_date_str = ''
    for row in rows:
        if row and row[0].strip() == 'Generated' and len(row) > 1:
            rebal_date_str = row[1].strip()[:10]
            break

    header_idx = None
    for i, row in enumerate(rows):
        if row and row[0].strip() in ('Rank', 'Ticker'):
            header_idx = i
            break

    if header_idx is None:
        return pd.DataFrame(), rebal_date_str

    header = rows[header_idx]
    data   = [r for r in rows[header_idx + 1:] if any(c.strip() for c in r)]
    df     = pd.DataFrame(data, columns=header)
    df     = df[df['Ticker'].str.strip() != ''].copy()

    for col in ['Weight(%)', 'Total_Score']:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors='coerce')

    return df, rebal_date_str


def _parse_scored_sheet(sheet_name: str) -> pd.DataFrame:
    """Read Scored_Stocks sheet → DataFrame with Ticker, V/Q/M factor scores."""
    try:
        ws   = spreadsheet.worksheet(sheet_name)
        rows = ws.get_all_values()
    except gspread.exceptions.WorksheetNotFound:
        print(f"  ⚠️  Sheet not found: {sheet_name}")
        return pd.DataFrame()

    if len(rows) < 2:
        return pd.DataFrame()

    df = pd.DataFrame(rows[1:], columns=rows[0])
    df = df[df['Ticker'].str.strip() != ''].copy()

    for col in ['Value_Score', 'Quality_Score', 'Momentum_Score', 'Total_Score']:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors='coerce')

    return df


def _download_prices(tickers: list, start_date: str) -> pd.DataFrame:
    """
    Download daily closes from (start_date - 15 days) to today.
    Returns DataFrame indexed by date, columns = tickers.
    """
    try:
        start_dt = (pd.Timestamp(start_date) - timedelta(days=15)).strftime('%Y-%m-%d')
    except Exception:
        start_dt = (pd.Timestamp.today() - timedelta(days=90)).strftime('%Y-%m-%d')

    BATCH  = 50
    frames = []
    for i in range(0, len(tickers), BATCH):
        batch = tickers[i: i + BATCH]
        try:
            raw = yf.download(batch, start=start_dt, auto_adjust=True, progress=False)
            if isinstance(raw.columns, pd.MultiIndex):
                closes = raw['Close']
            else:
                closes = raw
            if isinstance(closes, pd.Series):
                closes = closes.to_frame(name=batch[0])
            frames.append(closes)
        except Exception as e:
            print(f"  ⚠️  Price batch error: {e}")
        time.sleep(PRICE_DELAY)

    if not frames:
        return pd.DataFrame()

    prices = pd.concat(frames, axis=1)
    prices = prices.loc[:, ~prices.columns.duplicated()]
    prices = prices.ffill()
    return prices


def _compute_returns(prices: pd.DataFrame, rebal_date: str) -> pd.Series:
    """
    For each ticker, compute return from just-before rebal_date to latest date.
    Returns pd.Series(return, index=ticker).
    """
    rebal_ts = pd.Timestamp(rebal_date) if rebal_date else None
    rets = {}
    for ticker in prices.columns:
        col = prices[ticker].dropna()
        if col.empty:
            continue
        if rebal_ts is not None:
            col_before = col[col.index.normalize() <= rebal_ts.normalize()]
            p_start = float(col_before.iloc[-1]) if not col_before.empty else float(col.iloc[0])
        else:
            p_start = float(col.iloc[0])
        p_end = float(col.iloc[-1])
        rets[ticker] = (p_end / p_start - 1) if p_start > 0 else 0.0

    return pd.Series(rets)


def _zscore(series: pd.Series) -> pd.Series:
    """Z-score normalize, return 0.0 for constant / single-element series."""
    s = series.dropna()
    if len(s) < 2 or s.std() == 0:
        return series.map(lambda _: 0.0)
    mu, sigma = s.mean(), s.std()
    return (series - mu) / sigma


def _ols_regression(y: pd.Series, X: pd.DataFrame) -> tuple:
    """
    Minimal OLS: y = X @ β  (no intercept — factor exposures are z-scored).
    Returns (β: array, r_squared: float).
    Both y and X must be aligned on the same index.
    """
    mask  = y.notna() & X.notna().all(axis=1)
    y_    = y[mask].values
    X_    = X[mask].values

    if len(y_) < X_.shape[1] + 1:
        return np.zeros(X_.shape[1]), 0.0

    try:
        betas, _, _, _ = np.linalg.lstsq(X_, y_, rcond=None)
    except np.linalg.LinAlgError:
        return np.zeros(X_.shape[1]), 0.0

    y_hat  = X_ @ betas
    ss_res = np.sum((y_ - y_hat) ** 2)
    ss_tot = np.sum((y_ - y_.mean()) ** 2)
    r2     = 1.0 - ss_res / ss_tot if ss_tot > 0 else 0.0
    return betas, float(np.clip(r2, 0.0, 1.0))


def run_attribution(market: str,
                    portfolio_sheet: str,
                    scored_sheet: str) -> tuple[dict, pd.DataFrame]:
    """
    Run Barra-lite attribution for one market.

    Returns:
        summary_dict  — dict matching SUMMARY_COLS
        detail_df     — DataFrame with DETAIL_COLS (per portfolio stock)
    """
    today_str = datetime.now().strftime('%Y-%m-%d')

    print(f"\n{'─'*60}")
    print(f"  [{market}] Factor Attribution")
    print(f"{'─'*60}")

    # ── 1. Read portfolio ─────────────────────────────────────────────────────
    df_port, rebal_date = _parse_portfolio_sheet(portfolio_sheet)
    if df_port.empty:
        print(f"  ⚠️  No portfolio data — skipping {market}")
        return {}, pd.DataFrame()

    print(f"  Portfolio : {portfolio_sheet}  ({len(df_port)} holdings)")
    print(f"  Rebal date: {rebal_date}")

    # ── 2. Read scored stocks (universe for factor return estimation) ──────────
    df_scored = _parse_scored_sheet(scored_sheet)
    if df_scored.empty or 'Value_Score' not in df_scored.columns:
        print(f"  ⚠️  No scored data — skipping {market}")
        return {}, pd.DataFrame()

    print(f"  Universe  : {scored_sheet}  ({len(df_scored)} stocks)")

    # ── 3. Download prices ────────────────────────────────────────────────────
    # Need prices for: (a) all scored stocks (regression), (b) portfolio (attribution)
    # Start with portfolio tickers + scored tickers (capped to avoid too many calls)
    port_tickers   = df_port['Ticker'].str.strip().tolist()
    scored_tickers = df_scored['Ticker'].str.strip().tolist()
    all_tickers    = list(dict.fromkeys(port_tickers + scored_tickers[:200]))

    print(f"  Downloading prices for {len(all_tickers)} tickers "
          f"(portfolio: {len(port_tickers)} + universe sample)...")
    prices = _download_prices(all_tickers, rebal_date)
    if prices.empty:
        print(f"  ⚠️  Price download failed — skipping {market}")
        return {}, pd.DataFrame()

    # Period boundaries for output
    period_end = prices.index[-1].strftime('%Y-%m-%d')

    if rebal_date:
        rebal_ts = pd.Timestamp(rebal_date)
        n_days   = (pd.Timestamp(period_end) - rebal_ts).days

        # Portfolio was just generated — no meaningful post-rebalance return yet.
        # Fall back to 30-day lookback so attribution always shows real data.
        if n_days < 2:
            fallback_start = (pd.Timestamp(period_end) - timedelta(days=30))
            # Find the nearest trading day at or after fallback_start
            available = prices.index[prices.index.normalize() >= fallback_start.normalize()]
            if available.empty:
                available = prices.index
            effective_start_ts = available[0]
            period_start = effective_start_ts.strftime('%Y-%m-%d')
            n_days       = (pd.Timestamp(period_end) - pd.Timestamp(period_start)).days
            print(f"  ℹ️  Portfolio just generated (rebal={rebal_date}). "
                  f"Using 30-day fallback window: {period_start} → {period_end}")
            # Treat the fallback start as the anchor for return computation
            effective_rebal = period_start
        else:
            period_start   = rebal_date
            effective_rebal = rebal_date
    else:
        period_start    = prices.index[0].strftime('%Y-%m-%d')
        effective_rebal = period_start
        n_days          = (pd.Timestamp(period_end) - pd.Timestamp(period_start)).days

    # ── 4. Compute returns ─────────────────────────────────────────────────────
    returns = _compute_returns(prices, effective_rebal)
    nonzero = (returns != 0).sum()
    print(f"  Returns computed: {len(returns)} tickers  |  non-zero: {nonzero}  |  "
          f"mean={returns.mean():+.4f}  std={returns.std():.4f}")
    if nonzero == 0:
        print(f"  ⚠️  ALL RETURNS ARE ZERO — price window: "
              f"{prices.index[0].date()} → {prices.index[-1].date()}  "
              f"effective_rebal={effective_rebal}")

    # ── 5. Build factor exposure matrix across scored-stock universe ──────────
    # Z-score the three factor columns across all scored stocks
    print(f"  Scored stocks columns : {list(df_scored.columns)}")
    for sc in ['Value_Score', 'Quality_Score', 'Momentum_Score']:
        nn = df_scored[sc].notna().sum() if sc in df_scored.columns else 0
        print(f"    {sc}: {nn} non-null out of {len(df_scored)}")

    df_scored['f_V'] = _zscore(df_scored['Value_Score'])
    df_scored['f_Q'] = _zscore(df_scored['Quality_Score'])
    df_scored['f_M'] = _zscore(df_scored['Momentum_Score'])

    # Merge returns into scored df (only stocks with prices)
    df_scored_ret = df_scored.merge(
        returns.rename('Return'), left_on='Ticker', right_index=True, how='inner'
    )
    n_universe = len(df_scored_ret)
    print(f"  Universe with returns : {n_universe} stocks  "
          f"(scored tickers: {len(df_scored)}, price tickers: {len(returns)})")

    # ── 6. OLS cross-sectional regression → factor returns ────────────────────
    # r_i = β_V·f_V + β_Q·f_Q + β_M·f_M  (no intercept; z-scored exposures)
    if n_universe >= 10:
        y_reg = df_scored_ret['Return']
        X_reg = df_scored_ret[['f_V', 'f_Q', 'f_M']]
        betas, r2 = _ols_regression(y_reg, X_reg)
        beta_v, beta_q, beta_m = betas
        print(f"  Factor returns → β_V={beta_v:+.4f}  β_Q={beta_q:+.4f}  β_M={beta_m:+.4f}  R²={r2:.3f}")
    else:
        beta_v = beta_q = beta_m = 0.0
        r2 = 0.0
        print(f"  ⚠️  Too few universe stocks ({n_universe}) — factor betas set to 0")

    # ── 7. Portfolio holdings: weight, return, factor exposure ────────────────
    port_meta = df_scored.set_index('Ticker')[
        ['f_V', 'f_Q', 'f_M', 'Value_Score', 'Quality_Score', 'Momentum_Score']
    ]

    # Build per-stock dict for portfolio
    name_map   = df_port.set_index('Ticker')['Name'].to_dict() if 'Name' in df_port.columns else {}
    sector_map = df_port.set_index('Ticker')['Sector'].to_dict() if 'Sector' in df_port.columns else {}

    detail_rows = []
    total_weight = 0.0
    port_return  = 0.0
    h_v = h_q = h_m = 0.0   # portfolio factor exposures (weight-averaged)

    for _, row in df_port.iterrows():
        ticker  = row['Ticker'].strip()
        weight  = _to_float(row.get('Weight(%)'), 0.0)
        ret_i   = _to_float(returns.get(ticker))

        if weight is None or weight <= 0:
            continue

        # Look up factor exposures from scored-stock universe
        if ticker in port_meta.index:
            f_v_i = _to_float(port_meta.loc[ticker, 'f_V'], 0.0)
            f_q_i = _to_float(port_meta.loc[ticker, 'f_Q'], 0.0)
            f_m_i = _to_float(port_meta.loc[ticker, 'f_M'], 0.0)
        else:
            f_v_i = f_q_i = f_m_i = 0.0

        total_weight += weight
        if ret_i is not None:
            port_return += weight * ret_i

        h_v += weight * f_v_i
        h_q += weight * f_q_i
        h_m += weight * f_m_i

        pred_ret_i   = beta_v * f_v_i + beta_q * f_q_i + beta_m * f_m_i
        stock_resid  = (ret_i - pred_ret_i) if ret_i is not None else None

        detail_rows.append({
            'Market':           market,
            'Ticker':           ticker,
            'Name':             name_map.get(ticker, ''),
            'Sector':           sector_map.get(ticker, ''),
            'Weight':           round(weight, 4),
            'Return':           round(ret_i, 4) if ret_i is not None else '',
            'V_Exposure':       round(f_v_i, 4),
            'Q_Exposure':       round(f_q_i, 4),
            'M_Exposure':       round(f_m_i, 4),
            'Predicted_Return': round(pred_ret_i, 4),
            'Stock_Residual':   round(stock_resid, 4) if stock_resid is not None else '',
        })

    # Normalise weights if they don't sum to 1 (shouldn't happen, but be safe)
    if total_weight > 0 and abs(total_weight - 1.0) > 0.01:
        port_return /= total_weight
        h_v /= total_weight
        h_q /= total_weight
        h_m /= total_weight

    # ── 8. Attribution decomposition ──────────────────────────────────────────
    contrib_v = h_v * beta_v
    contrib_q = h_q * beta_q
    contrib_m = h_m * beta_m
    residual  = port_return - (contrib_v + contrib_q + contrib_m)

    n_port = len(detail_rows)
    print(f"\n  {'─'*50}")
    print(f"  Portfolio Return         : {port_return:+.4f}  ({port_return:+.2%})")
    print(f"  Value  contribution      : {contrib_v:+.4f}  ({contrib_v:+.2%})")
    print(f"  Quality contribution     : {contrib_q:+.4f}  ({contrib_q:+.2%})")
    print(f"  Momentum contribution    : {contrib_m:+.4f}  ({contrib_m:+.2%})")
    print(f"  Residual (stock-specific): {residual:+.4f}  ({residual:+.2%})")
    print(f"  {'─'*50}")

    summary = {
        'Market':           market,
        'Period_Start':     period_start,
        'Period_End':       period_end,
        'Days':             str(n_days),
        'Portfolio_Return': round(port_return, 4),
        'Value_Contrib':    round(contrib_v,  4),
        'Quality_Contrib':  round(contrib_q,  4),
        'Momentum_Contrib': round(contrib_m,  4),
        'Residual':         round(residual,   4),
        'Beta_V':           round(beta_v,     4),
        'Beta_Q':           round(beta_q,     4),
        'Beta_M':           round(beta_m,     4),
        'R_Squared':        round(r2,          4),
        'N_Portfolio':      str(n_port),
        'N_Universe':       str(n_universe),
        'Generated':        today_str,
    }

    detail_df = pd.DataFrame(detail_rows)
    if not detail_df.empty:
        detail_df = detail_df.sort_values('Weight', ascending=False).reset_index(drop=True)

    return summary, detail_df


# ══════════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════════

print("\n" + "=" * 65)
print("  FACTOR ATTRIBUTION  (Barra-lite)")
print("=" * 65)

generated    = datetime.now().strftime('%Y-%m-%d %H:%M')
all_summaries = []
all_details   = []

for market, port_sheet, scored_sheet in [
    ("US", "US_Final_Portfolio", "US_Scored_Stocks"),
    ("KR", "KR_Final_Portfolio", "KR_Scored_Stocks"),
]:
    summary_dict, detail_df = run_attribution(market, port_sheet, scored_sheet)
    if summary_dict:
        all_summaries.append(summary_dict)
    if not detail_df.empty:
        all_details.append(detail_df)

if not all_summaries:
    print("\n⚠️  No attribution data produced. Ensure portfolios and scored stocks exist.")
    sys.exit(0)

# ── Build output rows ─────────────────────────────────────────────────────────
df_summary = pd.DataFrame(all_summaries)
for col in SUMMARY_COLS:
    if col not in df_summary.columns:
        df_summary[col] = ''
df_summary = df_summary[SUMMARY_COLS].fillna('').astype(str)

df_detail = pd.concat(all_details, ignore_index=True) if all_details else pd.DataFrame()
for col in DETAIL_COLS:
    if not df_detail.empty and col not in df_detail.columns:
        df_detail[col] = ''
if not df_detail.empty:
    df_detail = df_detail[DETAIL_COLS].fillna('').astype(str)

# ── Write to Google Sheets ────────────────────────────────────────────────────
print(f"\n[ATTRIB] Writing to Factor_Attribution sheet...")
try:
    attr_ws = spreadsheet.worksheet("Factor_Attribution")
except gspread.exceptions.WorksheetNotFound:
    attr_ws = spreadsheet.add_worksheet(
        title="Factor_Attribution",
        rows=300,
        cols=max(len(SUMMARY_COLS), len(DETAIL_COLS)) + 2,
    )

# Layout:
#   header block (generated timestamp)
#   blank row
#   summary table (header + rows for US and KR)
#   blank row
#   per-stock detail table (header + all rows)

header_block = [
    ["── Factor Attribution (Barra-lite) ──", ""],
    ["Generated", generated],
    ["Method", "OLS cross-sectional regression: r = β_V·f_V + β_Q·f_Q + β_M·f_M"],
    ["Factor exposures", "Z-scored Value/Quality/Momentum scores from Scored_Stocks"],
    ["", ""],
    SUMMARY_COLS,
]
summary_rows = df_summary.values.tolist()
separator    = [["", ""], DETAIL_COLS]

all_rows = header_block + summary_rows + separator
if not df_detail.empty:
    all_rows += df_detail.values.tolist()

attr_ws.clear()
attr_ws.update(range_name='A1', values=all_rows, value_input_option='USER_ENTERED')
dual_write_dataframe("Factor_Attribution", df_summary, market="GLOBAL")
if not df_detail.empty:
    dual_write_dataframe("Factor_Attribution_Detail", df_detail, market="GLOBAL")

print(f"✅ [ATTRIB] Factor_Attribution updated — "
      f"{len(all_summaries)} markets  |  "
      f"{len(df_detail) if not df_detail.empty else 0} stock rows  |  {generated}")
