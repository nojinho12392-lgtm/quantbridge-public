# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
"""
05c_backtest_grid_us.py — Parameter Grid Search for US Fundamental Backtest

Loads EDGAR fundamentals + prices ONCE, then sweeps:
  ① Rebalance frequency : 7, 14, 21 days
  ② Portfolio size (N)  : 20, 30, 40
  ③ Weighting scheme    : risk_parity, equal_weight, score_weighted
  ④ Factor weights(V/Q/M):
       Base         40 / 35 / 25
       Val-Heavy    50 / 30 / 20
       Qual-Heavy   30 / 45 / 25
       Mom-Heavy    30 / 25 / 45
       Balanced     33 / 33 / 34

Total configs: 3 × 3 × 3 × 5 = 135

Key optimisation: per-date fund_df is precomputed ONCE (7-day grid),
then sub-sampled for coarser frequencies → no duplicate get_metrics calls.

Output: US_Backtest_Grid sheet — ranked comparison table
"""

import yfinance as yf
from sheets_client import get_spreadsheet
import pandas as pd
import numpy as np
import time, itertools, warnings
from datetime import datetime
from scipy.optimize import minimize
from sklearn.covariance import LedoitWolf
from pipeline.backtest.costs import weighted_turnover_costs
from pipeline.data.sec_edgar import (
    build_fundamental_timeseries,
    edgar_user_agent,
    fetch_company_facts_for_tickers,
    get_pit_metrics,
    load_cik_map,
)
from pipeline.portfolio.risk_controls import (
    PortfolioRiskConfig,
    apply_weight_limits,
    estimate_portfolio_volatility,
    period_cash_return,
    risk_controlled_selection,
    volatility_target_scalar,
)
warnings.filterwarnings('ignore')

# ═══════════════════════════════════════════════════════════════════════════
# GRID DIMENSIONS
# ═══════════════════════════════════════════════════════════════════════════
BACKTEST_QUARTERS = 10        # common for all configs
FEE               = 0.001     # 0.1 % one-way (Toss)
BASE_REBAL_DAYS   = 7         # finest grid  — all fund_dfs precomputed here
EDGAR_DELAY       = 0.12
EDGAR_UA          = edgar_user_agent()
MIN_W, MAX_W      = 0.01, 0.15   # weight bounds for risk-parity / score-weighted
TARGET_VOL        = float(os.environ.get("QUANT_US_TARGET_VOL", "0.12"))
US_RF_ANN         = float(os.environ.get("QUANT_US_RF_ANN", "0.04"))
RISK_CONFIG       = PortfolioRiskConfig(
    max_position_weight=float(os.environ.get("QUANT_US_MAX_POSITION_WEIGHT", "0.10")),
    max_sector_weight=float(os.environ.get("QUANT_US_MAX_SECTOR_WEIGHT", "0.30")),
    max_illiquid_weight=float(os.environ.get("QUANT_US_MAX_ILLIQUID_WEIGHT", "0.20")),
    max_turnover_fraction=float(os.environ.get("QUANT_US_MAX_TURNOVER_FRACTION", "0.50")),
)

REBAL_OPTIONS = [7, 14, 21]   # calendar days between rebalances

TOP_N_OPTIONS = [20, 30, 40]

WEIGHT_OPTIONS = ['risk_parity', 'equal_weight', 'score_weighted']

FACTOR_CONFIGS = [
    # name                  v_w    q_w    m_w
    ('Base(40/35/25)',       0.40,  0.35,  0.25),
    ('Val-Heavy(50/30/20)',  0.50,  0.30,  0.20),
    ('Qual-Heavy(30/45/25)', 0.30,  0.45,  0.25),
    ('Mom-Heavy(30/25/45)',  0.30,  0.25,  0.45),
    ('Balanced(33/33/34)',   0.33,  0.33,  0.34),
]

END_DATE    = pd.Timestamp.now().normalize()
START_DATE  = END_DATE - pd.DateOffset(months=BACKTEST_QUARTERS * 3)
PRICE_START = START_DATE - pd.DateOffset(years=1)

# ═══════════════════════════════════════════════════════════════════════════
# GOOGLE SHEETS
# ═══════════════════════════════════════════════════════════════════════════
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()

# ═══════════════════════════════════════════════════════════════════════════
# LOAD UNIVERSE
# ═══════════════════════════════════════════════════════════════════════════
print("[GRID] Loading US_Universe...")
ws       = spreadsheet.worksheet("US_Universe")
data     = ws.get_all_values()
univ_df  = pd.DataFrame(data[1:], columns=data[0])
all_tickers = [t for t in univ_df['Ticker'].dropna().tolist()
               if t and not t.endswith(('.KS', '.KQ'))]
if 'MarketCap' in univ_df.columns:
    univ_df['MarketCap'] = pd.to_numeric(univ_df['MarketCap'], errors='coerce')
market_cap_map = univ_df.set_index('Ticker')['MarketCap'].to_dict() if 'MarketCap' in univ_df.columns else {}
sector_map = univ_df.set_index('Ticker')['Sector'].to_dict() if 'Sector' in univ_df.columns else {}
print(f"[GRID] Universe: {len(all_tickers)} tickers")

# ═══════════════════════════════════════════════════════════════════════════
# DOWNLOAD PRICES
# ═══════════════════════════════════════════════════════════════════════════
print(f"[GRID] Downloading prices {PRICE_START.date()} → {END_DATE.date()}...")
BATCH = 50
price_frames = []
for i in range(0, len(all_tickers), BATCH):
    batch = all_tickers[i:i + BATCH]
    try:
        raw    = yf.download(batch,
                             start=PRICE_START.strftime('%Y-%m-%d'),
                             end=(END_DATE + pd.Timedelta(days=2)).strftime('%Y-%m-%d'),
                             auto_adjust=True, progress=False)
        closes = raw['Close'] if isinstance(raw.columns, pd.MultiIndex) else raw
        if isinstance(closes, pd.Series):
            closes = closes.to_frame(name=batch[0])
        price_frames.append(closes)
        print(f"  batch {i//BATCH+1}/{(len(all_tickers)-1)//BATCH+1}: "
              f"{closes.shape[1]} stocks")
    except Exception as e:
        print(f"  batch error: {e}")
    time.sleep(1)

prices = pd.concat(price_frames, axis=1)
prices = prices.loc[:, ~prices.columns.duplicated()]
prices = prices.ffill()
prices = prices.dropna(axis=1, thresh=int(len(prices) * 0.4))
print(f"[GRID] Prices: {prices.shape[0]} days × {prices.shape[1]} stocks")

universe = [t for t in all_tickers if t in prices.columns]

# ═══════════════════════════════════════════════════════════════════════════
# SEC EDGAR — CIK MAP + COMPANY FACTS
# ═══════════════════════════════════════════════════════════════════════════
print("\n[GRID] Loading SEC EDGAR point-in-time fundamentals...")
try:
    cik_map = load_cik_map(user_agent=EDGAR_UA)
    print(f"[GRID] CIK map: {len(cik_map)} entries")
except Exception as e:
    cik_map = {}
    print(f"[GRID] WARNING — CIK map failed: {e}")

edgar_universe = [t for t in universe if t.upper() in cik_map]
print(f"[GRID] Fetching EDGAR facts for {len(edgar_universe)} tickers "
      f"(~{len(edgar_universe)*EDGAR_DELAY/60:.0f} min before cache hits)...")
edgar_raw = fetch_company_facts_for_tickers(
    edgar_universe,
    cik_map,
    user_agent=EDGAR_UA,
    delay=EDGAR_DELAY,
) if edgar_universe else {}
print(f"[GRID] EDGAR facts cached for {len(edgar_raw)} tickers")

fund_ts = build_fundamental_timeseries(edgar_raw)
print(f"[GRID] Time series ready for {len(fund_ts)} tickers")

def get_metrics(ticker, date):
    return get_pit_metrics(fund_ts, ticker, date)

# ═══════════════════════════════════════════════════════════════════════════
# PRECOMPUTE PER-DATE FUND_DF  (most expensive step — done ONCE)
# ═══════════════════════════════════════════════════════════════════════════
base_rebal_dates = pd.date_range(start=START_DATE, end=END_DATE,
                                 freq=f'{BASE_REBAL_DAYS}D')
print(f"\n[GRID] Precomputing fundamental snapshots for "
      f"{len(base_rebal_dates)} dates (7-day grid)...")

fund_df_by_date: dict = {}   # date → pd.DataFrame (tickers × metrics)
for i, rd in enumerate(base_rebal_dates):
    px_slice = prices.loc[:rd]
    if len(px_slice) < 63:
        continue
    rows: dict = {}
    for ticker in fund_ts:
        if ticker not in prices.columns:
            continue
        if px_slice[ticker].dropna().shape[0] < 10:
            continue
        m = get_metrics(ticker, rd)
        if m:
            rows[ticker] = m
    if len(rows) >= 10:
        snap = pd.DataFrame(rows).T
        snap["MarketCap"] = pd.Series(market_cap_map).reindex(snap.index)
        snap["Sector"] = pd.Series(sector_map).reindex(snap.index)
        fund_df_by_date[rd] = snap
    if (i + 1) % 20 == 0:
        print(f"  [{i+1}/{len(base_rebal_dates)}] dates computed "
              f"({len(fund_df_by_date)} with data)")

print(f"[GRID] Precomputed {len(fund_df_by_date)} fundamental snapshots ✓")

# ═══════════════════════════════════════════════════════════════════════════
# PRECOMPUTE MOMENTUM MATRIX
# ═══════════════════════════════════════════════════════════════════════════
print("[GRID] Precomputing 12M-1M momentum matrix...")
mom_matrix = (prices / prices.shift(252) - 1) - (prices / prices.shift(21) - 1)

# ═══════════════════════════════════════════════════════════════════════════
# SCORING FUNCTION
# ═══════════════════════════════════════════════════════════════════════════
def compute_scores(fund_df, mom_series, v_w, q_w, m_w):
    """Score all stocks in fund_df with given factor weights."""
    df = fund_df.copy()

    def rank(col, ascending=True, fallback=0.5):
        if col not in df.columns:
            return pd.Series(fallback, index=df.index)
        s = pd.to_numeric(df[col], errors='coerce')
        r = s.rank(pct=True, na_option='keep')
        return (r if ascending else (1 - r)).fillna(fallback)

    v = rank('op_margin') * 0.50 + rank('de_ratio', ascending=False) * 0.50
    q = (rank('roic')       * 0.35
       + rank('roe')        * 0.25
       + rank('fcf_margin') * 0.25
       + rank('int_cov')    * 0.15)

    if mom_series is not None and not mom_series.empty:
        aligned   = mom_series.reindex(df.index)
        m_price   = aligned.rank(pct=True, na_option='keep').fillna(0.5)
    else:
        m_price   = pd.Series(0.5, index=df.index)
    m = m_price * 0.70 + rank('rev_growth') * 0.30

    df['Value_Score']    = v.clip(0, 1)
    df['Quality_Score']  = q.clip(0, 1)
    df['Momentum_Score'] = m.clip(0, 1)
    df['Total_Score']    = (v * v_w + q * q_w + m * m_w).clip(0, 1)
    return df

# ═══════════════════════════════════════════════════════════════════════════
# WEIGHT OPTIMISERS
# ═══════════════════════════════════════════════════════════════════════════
def risk_parity_weights(cov):
    n = len(cov)
    def obj(w):
        pv = float(w @ cov @ w)
        if pv <= 0:
            return 1e9
        rc = w * (cov @ w) / pv
        return float(np.sum((rc - 1.0 / n) ** 2))
    res = minimize(obj, x0=np.full(n, 1.0 / n), method='SLSQP',
                   bounds=[(MIN_W, MAX_W)] * n,
                   constraints=[{'type': 'eq', 'fun': lambda w: np.sum(w) - 1}],
                   options={'maxiter': 1000, 'ftol': 1e-9})
    w = res.x if res.success else np.full(n, 1.0 / n)
    return w / w.sum()

def score_weights(scores, n):
    """Weight proportional to Total_Score, clipped to [MIN_W, MAX_W]."""
    w = np.array(scores, dtype=float)
    w = np.clip(w, 0, None)
    w /= w.sum() if w.sum() > 0 else 1
    # Iterative clipping to enforce bounds
    for _ in range(20):
        mask_hi = w > MAX_W
        mask_lo = w < MIN_W
        if not mask_hi.any() and not mask_lo.any():
            break
        w[mask_hi] = MAX_W
        w[mask_lo] = MIN_W
        remainder = 1.0 - w[mask_hi].sum() - w[mask_lo].sum()
        free = (~mask_hi) & (~mask_lo)
        if free.any() and w[free].sum() > 0:
            w[free] *= remainder / w[free].sum()
        else:
            break
    return w / w.sum()

# ═══════════════════════════════════════════════════════════════════════════
# SINGLE-CONFIG BACKTEST (uses precomputed fund_df_by_date)
# ═══════════════════════════════════════════════════════════════════════════
def run_config(rebal_days, top_n, weighting, v_w, q_w, m_w):
    rebal_dates_cfg = pd.date_range(start=START_DATE, end=END_DATE,
                                    freq=f'{rebal_days}D')

    # Sub-sample from precomputed snapshots
    avail_dates = sorted(fund_df_by_date.keys())

    portfolio_rets: list  = []
    portfolio_dates: list = []
    period_log: list      = []
    prev_holdings: set    = set()
    prev_weights: dict    = {}
    invested_fractions: list = []
    vol_estimates: list = []

    for i, rd in enumerate(rebal_dates_cfg[:-1]):
        next_rd = rebal_dates_cfg[i + 1]

        # Find closest precomputed snapshot ≤ rd
        candidates_d = [d for d in avail_dates if d <= rd]
        if not candidates_d:
            continue
        snap_date = max(candidates_d)
        fund_df   = fund_df_by_date[snap_date]

        if len(fund_df) < top_n:
            continue

        # Momentum at rebalance date
        mom_at = mom_matrix.reindex([rd], method='ffill')
        mom_series = mom_at.iloc[0] if not mom_at.empty else None

        # Score
        scored = compute_scores(fund_df, mom_series, v_w, q_w, m_w)
        scored = scored.sort_values('Total_Score', ascending=False)

        # Select top N with valid prices
        px_slice   = prices.loc[:rd]
        candidates = scored.head(top_n * 3).index.tolist()
        px_check   = px_slice.iloc[-63:][
            [c for c in candidates if c in px_slice.columns]
        ].dropna(axis=1, thresh=40)
        price_valid_scored = scored.loc[[t for t in candidates if t in px_check.columns]]
        top_valid = risk_controlled_selection(
            price_valid_scored,
            target_n=top_n,
            market="US",
            previous_holdings=prev_holdings,
            score_col="Total_Score",
            config=RISK_CONFIG,
        )

        if len(top_valid) < 5:
            continue

        # Weights
        if weighting == 'equal_weight':
            w_arr = np.full(len(top_valid), 1.0 / len(top_valid))

        elif weighting == 'score_weighted':
            sel_scores = scored.loc[
                [t for t in top_valid if t in scored.index], 'Total_Score'
            ].reindex(top_valid).fillna(0).values
            w_arr = score_weights(sel_scores, len(top_valid))

        else:   # risk_parity (default)
            ret_hist = prices[top_valid].loc[:rd].pct_change().dropna().tail(252)
            if ret_hist.shape[0] >= 30:
                try:
                    lw = LedoitWolf()
                    lw.fit(ret_hist)
                    cov_m = lw.covariance_ * 252
                    w_arr = risk_parity_weights(cov_m)
                except Exception:
                    w_arr = np.full(len(top_valid), 1.0 / len(top_valid))
            else:
                w_arr = np.full(len(top_valid), 1.0 / len(top_valid))

        w_arr /= w_arr.sum()
        w_arr = apply_weight_limits(
            pd.Series(w_arr, index=top_valid),
            scored.reindex(top_valid),
            market="US",
            config=RISK_CONFIG,
        ).reindex(top_valid).to_numpy()

        # Period return
        stock_rets = []
        for ticker, w in zip(top_valid, w_arr):
            p0 = prices[ticker].asof(rd)
            p1 = prices[ticker].asof(next_rd)
            if np.isnan(p0) or np.isnan(p1) or p0 == 0:
                continue
            stock_rets.append((ticker, w, p1 / p0 - 1))

        if not stock_rets:
            continue

        tkrs, ws, rs = zip(*stock_rets)
        ws_arr = np.array(ws, dtype=float)
        ws_arr /= ws_arr.sum()
        equity_ret = float(np.dot(ws_arr, rs))
        weight_series = pd.Series(ws_arr, index=tkrs)
        vol_est = estimate_portfolio_volatility(
            prices[list(tkrs)].loc[:rd].pct_change().dropna().tail(252),
            weight_series,
        )
        invested_fraction = volatility_target_scalar(vol_est, target_vol=TARGET_VOL)
        cash_weight = 1.0 - invested_fraction
        cash_ret = period_cash_return(US_RF_ANN, rd, next_rd)
        gross  = equity_ret * invested_fraction + cash_ret * cash_weight

        curr_set = set(tkrs)
        curr_weights = {
            ticker: float(weight * invested_fraction)
            for ticker, weight in zip(tkrs, ws_arr)
        }
        costs = weighted_turnover_costs(
            previous_weights=prev_weights,
            current_weights=curr_weights,
            fee_rate=FEE,
            slippage_rates={},
        )
        fee_cost = costs["fee"]
        net_ret  = gross - costs["total"]

        portfolio_rets.append(net_ret)
        portfolio_dates.append(next_rd)
        invested_fractions.append(invested_fraction)
        vol_estimates.append(vol_est if vol_est is not None else np.nan)
        period_log.append({
            'Period':       f"{rd.date()} → {next_rd.date()}",
            'Holdings':     len(curr_set),
            'Gross_Return': round(gross, 4),
            'Equity_Return': round(equity_ret, 4),
            'Fee':          round(fee_cost, 4),
            'Net_Return':   round(net_ret, 4),
            'Turnover_Pct': round(costs["turnover"], 4),
            'Invested_Fraction': round(invested_fraction, 4),
            'Cash_Weight': round(cash_weight, 4),
            'Portfolio_Vol_Est': round(vol_est, 4) if vol_est is not None else '',
        })
        prev_holdings = curr_set
        prev_weights = curr_weights

    if not portfolio_rets:
        return None

    ret_s   = pd.Series(portfolio_rets, index=pd.DatetimeIndex(portfolio_dates))
    cumret  = (1 + ret_s).cumprod()
    dd      = (cumret / cumret.cummax()) - 1
    n_per   = len(ret_s)
    ppy     = 365.0 / rebal_days

    cagr      = (1 + ret_s).prod() ** (ppy / n_per) - 1
    vol_ann   = ret_s.std() * np.sqrt(ppy)
    sharpe    = ret_s.mean() / (ret_s.std() + 1e-9) * np.sqrt(ppy)
    max_dd    = dd.min()
    total_ret = cumret.iloc[-1] - 1
    win_rate  = (ret_s > 0).mean()
    avg_ret   = ret_s.mean()
    calmar    = cagr / abs(max_dd) if max_dd != 0 else np.nan

    return {
        'cagr':      cagr,
        'total_ret': total_ret,
        'sharpe':    sharpe,
        'calmar':    calmar,
        'max_dd':    max_dd,
        'vol_ann':   vol_ann,
        'win_rate':  win_rate,
        'avg_ret':   avg_ret,
        'periods':   n_per,
        'avg_invested': pd.Series(invested_fractions, dtype=float).mean(),
        'avg_vol_est': pd.Series(vol_estimates, dtype=float).dropna().mean(),
        'period_log': period_log,
        'ret_s':     ret_s,
        'cumret':    cumret,
        'dd':        dd,
    }

# ═══════════════════════════════════════════════════════════════════════════
# GRID SWEEP
# ═══════════════════════════════════════════════════════════════════════════
grid_results: list = []

total_configs = len(REBAL_OPTIONS) * len(TOP_N_OPTIONS) * len(WEIGHT_OPTIONS) * len(FACTOR_CONFIGS)
print(f"\n[GRID] ── Starting Grid Sweep: {total_configs} configs ──────────────────────")

cfg_idx = 0
for rebal_days, top_n, weighting, (fc_name, v_w, q_w, m_w) in itertools.product(
        REBAL_OPTIONS, TOP_N_OPTIONS, WEIGHT_OPTIONS, FACTOR_CONFIGS):

    cfg_idx += 1
    cfg_label = (f"REBAL{rebal_days}d / Top{top_n} / {weighting} / {fc_name}")
    print(f"  [{cfg_idx:>3}/{total_configs}] {cfg_label} ...", end=' ', flush=True)

    t0 = time.time()
    result = run_config(rebal_days, top_n, weighting, v_w, q_w, m_w)
    elapsed = time.time() - t0

    if result is None:
        print("⚠  no data")
        continue

    row = {
        'Config':       cfg_label,
        'Rebal_Days':   rebal_days,
        'Top_N':        top_n,
        'Weighting':    weighting,
        'Factor_Config':fc_name,
        'V_W':          v_w,
        'Q_W':          q_w,
        'M_W':          m_w,
        'CAGR':         round(result['cagr'], 4),
        'Total_Return': round(result['total_ret'], 4),
        'Sharpe':       round(result['sharpe'], 4),
        'Calmar':       round(result['calmar'], 4) if not np.isnan(result['calmar']) else '',
        'Max_DD':       round(result['max_dd'], 4),
        'Ann_Vol':      round(result['vol_ann'], 4),
        'Win_Rate':     round(result['win_rate'], 4),
        'Avg_Period_Ret': round(result['avg_ret'], 4),
        'Avg_Invested': round(result['avg_invested'], 4),
        'Avg_Port_Vol_Est': round(result['avg_vol_est'], 4) if pd.notna(result['avg_vol_est']) else '',
        'Periods':      result['periods'],
    }
    grid_results.append(row)

    print(f"CAGR={result['cagr']:+.2%} Sharpe={result['sharpe']:.2f} "
          f"MaxDD={result['max_dd']:.2%} ({elapsed:.0f}s)")

print(f"\n[GRID] Grid complete: {len(grid_results)} configs with results")

# ═══════════════════════════════════════════════════════════════════════════
# RANK + SUMMARISE
# ═══════════════════════════════════════════════════════════════════════════
grid_df = pd.DataFrame(grid_results)

# Multi-metric rank: Sharpe (40%) + Calmar (30%) + CAGR (30%)
grid_df['Calmar_num'] = pd.to_numeric(grid_df['Calmar'], errors='coerce').fillna(0)
grid_df['Composite']  = (
    grid_df['Sharpe'].rank(pct=True) * 0.40
  + grid_df['Calmar_num'].rank(pct=True) * 0.30
  + grid_df['CAGR'].rank(pct=True) * 0.30
)
grid_df = grid_df.sort_values('Composite', ascending=False).reset_index(drop=True)
grid_df.insert(0, 'Rank', range(1, len(grid_df) + 1))

print("\n[GRID] ── Top 10 Configs by Composite Score ─────────────────────────────")
for _, row in grid_df.head(10).iterrows():
    print(f"  #{int(row['Rank']):>3} {row['Config']}")
    print(f"       CAGR={row['CAGR']:+.2%}  Sharpe={row['Sharpe']:.2f}  "
          f"MaxDD={row['Max_DD']:.2%}  Calmar={row['Calmar']}")

# ═══════════════════════════════════════════════════════════════════════════
# DIMENSION ANALYSIS — average Sharpe / CAGR by each dimension
# ═══════════════════════════════════════════════════════════════════════════
def dim_summary(col):
    g = grid_df.groupby(col)[['CAGR','Sharpe','Max_DD','Win_Rate']].mean().round(4)
    g = g.sort_values('Sharpe', ascending=False)
    return g

print("\n[GRID] ── Average Sharpe by Rebalance Frequency ─────────────────────────")
print(dim_summary('Rebal_Days').to_string())
print("\n[GRID] ── Average Sharpe by Portfolio Size ───────────────────────────────")
print(dim_summary('Top_N').to_string())
print("\n[GRID] ── Average Sharpe by Weighting Scheme ─────────────────────────────")
print(dim_summary('Weighting').to_string())
print("\n[GRID] ── Average Sharpe by Factor Config ───────────────────────────────")
print(dim_summary('Factor_Config').to_string())

# ═══════════════════════════════════════════════════════════════════════════
# SAVE TO GOOGLE SHEETS
# ═══════════════════════════════════════════════════════════════════════════
print("\n[GRID] Saving results to US_Backtest_Grid...")

# ── Summary header block ──────────────────────────────────────────────────
best = grid_df.iloc[0]
meta_block = [
    ["── US Backtest Parameter Grid Search ──", ""],
    ["Generated",       pd.Timestamp.now().strftime('%Y-%m-%d %H:%M')],
    ["Period",          f"{START_DATE.date()} → {END_DATE.date()} ({BACKTEST_QUARTERS}Q)"],
    ["Universe",        "S&P 500 + NASDAQ 100 (SEC EDGAR XBRL)"],
    ["Fee",             f"{FEE:.3%} one-way per turnover"],
    ["Vol Target",      f"Target {TARGET_VOL:.2%}; cash earns {US_RF_ANN:.2%} annual rf proxy"],
    ["Risk Controls",   f"Max position {RISK_CONFIG.max_position_weight:.0%}, sector {RISK_CONFIG.max_sector_weight:.0%}, "
                        f"illiquid {RISK_CONFIG.max_illiquid_weight:.0%}, max new names {RISK_CONFIG.max_turnover_fraction:.0%}"],
    ["Configs Tested",  str(len(grid_results))],
    ["", ""],
    ["── Best Config ──", ""],
    ["Rank #1",         str(best['Config'])],
    ["CAGR",            f"{best['CAGR']:.4f}"],
    ["Sharpe",          f"{best['Sharpe']:.4f}"],
    ["Calmar",          f"{best['Calmar']}"],
    ["Max Drawdown",    f"{best['Max_DD']:.4f}"],
    ["Win Rate",        f"{best['Win_Rate']:.4f}"],
    ["", ""],
    ["── Dimension Averages (Sharpe) ──", "Rebal / TopN / Weighting / Factor"],
]

for col, label in [('Rebal_Days','Rebalance'),('Top_N','Portfolio Size'),
                   ('Weighting','Weighting'),('Factor_Config','Factor Config')]:
    g = grid_df.groupby(col)['Sharpe'].mean().sort_values(ascending=False)
    meta_block.append([f"  Best {label}", f"{g.index[0]} (Sharpe {g.iloc[0]:.3f})"])

meta_block.append(["", ""])
meta_block.append(["── Full Grid Rankings ──", ""])

# ── Grid table ────────────────────────────────────────────────────────────
out_cols = ['Rank','Config','Rebal_Days','Top_N','Weighting','Factor_Config',
            'V_W','Q_W','M_W','CAGR','Total_Return','Sharpe','Calmar',
            'Max_DD','Ann_Vol','Win_Rate','Avg_Period_Ret','Avg_Invested',
            'Avg_Port_Vol_Est','Periods','Composite']
out_df = grid_df[[c for c in out_cols if c in grid_df.columns]].fillna('')

rows_out = (
    meta_block
    + [out_df.columns.tolist()]
    + [[str(v) for v in r] for r in out_df.values.tolist()]
)

try:
    grid_sheet = spreadsheet.worksheet("US_Backtest_Grid")
except Exception:
    grid_sheet = spreadsheet.add_worksheet(
        title="US_Backtest_Grid", rows=300, cols=25)

grid_sheet.clear()
grid_sheet.update(range_name='A1', values=rows_out, value_input_option='USER_ENTERED')

print(f"✅ [GRID] Saved {len(rows_out)} rows to US_Backtest_Grid")
print(f"\n[GRID] ── Best Config ────────────────────────────────────────────────────")
print(f"  {best['Config']}")
print(f"  CAGR={best['CAGR']:+.2%}  Sharpe={best['Sharpe']:.2f}  "
      f"MaxDD={best['Max_DD']:.2%}  WinRate={best['Win_Rate']:.1%}")
