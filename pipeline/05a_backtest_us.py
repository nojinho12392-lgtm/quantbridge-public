# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
"""
05_backtest_us.py — SEC EDGAR Point-in-Time Fundamental Backtest

Strategy:
  - Universe  : S&P 500 + NASDAQ 100 (from US_Universe sheet)
  - Financials: SEC EDGAR XBRL API  →  point-in-time, zero look-ahead bias
  - Scoring   : V/Q/M factor model  (configurable weights)
  - Portfolio : Top N by Total_Score, configurable weighting scheme
  - Rebalance : Every N calendar days
  - Period    : 10 quarters (~2.5 years) ending today
  - Fee       : 0.1 % one-way (Toss Securities)
  - Output    : US_Backtest_Results (Google Sheets)

  ✨ Auto-config: if US_Backtest_Grid sheet exists (from 05c_backtest_grid_us.py),
     the Rank #1 config is automatically loaded and applied.
     To override, set USE_GRID_CONFIG = False below.
"""

import yfinance as yf
from sheets_client import get_spreadsheet
import pandas as pd
import numpy as np
import time
import warnings
from datetime import datetime
from scipy.optimize import minimize
from sklearn.covariance import LedoitWolf
from pipeline.data.sec_edgar import (
    build_fundamental_timeseries,
    edgar_user_agent,
    fetch_company_facts_for_tickers,
    get_pit_metrics,
    load_cik_map,
)
from pipeline.backtest.research_quality import (
    bootstrap_sharpe_ci,
    offset_sensitivity_table,
    performance_stats,
    split_in_out_sample,
)
from pipeline.backtest.costs import weighted_turnover_costs
from pipeline.scoring.common_factor_scorer import compute_us_factor_scores
from pipeline.portfolio.risk_controls import (
    PortfolioRiskConfig,
    apply_weight_limits,
    estimate_portfolio_volatility,
    period_cash_return,
    risk_controlled_selection,
    volatility_target_scalar,
)
from quantbridge.writers.dual_write import dual_write_dataframe
warnings.filterwarnings('ignore')

# ── Google Sheets ─────────────────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()

# ── Default Config (used when grid sheet is absent or USE_GRID_CONFIG=False) ──
# Research default: do not auto-adopt the best grid row. This prevents the main
# backtest from silently becoming an in-sample winner. Set
# QUANT_USE_GRID_CONFIG=true when intentionally replaying a chosen grid config.
USE_GRID_CONFIG   = os.environ.get('QUANT_USE_GRID_CONFIG', '').lower() == 'true'

BACKTEST_QUARTERS = 10
REBAL_DAYS        = 7         # rebalance every N calendar days
TOP_N             = 30        # top stocks by score
WEIGHTING         = 'risk_parity'   # 'risk_parity' | 'equal_weight' | 'score_weighted'
V_W, Q_W, M_W     = 0.40, 0.35, 0.25   # Value / Quality / Momentum weights
MIN_W, MAX_W      = 0.01, 0.15
FEE               = 0.001     # 0.1 % one-way
TARGET_VOL        = float(os.environ.get("QUANT_US_TARGET_VOL", "0.12"))
OOS_FRACTION      = 0.30
OFFSET_TEST_DAYS  = [0, 1, 2, 3, 4]
BOOTSTRAP_SAMPLES = 1000
EDGAR_DELAY       = 0.12
EDGAR_UA          = edgar_user_agent()
RISK_CONFIG       = PortfolioRiskConfig(
    max_position_weight=float(os.environ.get("QUANT_US_MAX_POSITION_WEIGHT", "0.10")),
    max_sector_weight=float(os.environ.get("QUANT_US_MAX_SECTOR_WEIGHT", "0.30")),
    max_illiquid_weight=float(os.environ.get("QUANT_US_MAX_ILLIQUID_WEIGHT", "0.20")),
    max_turnover_fraction=float(os.environ.get("QUANT_US_MAX_TURNOVER_FRACTION", "0.50")),
)

# ── Auto-load best config from US_Backtest_Grid (if it exists) ────────────────
def _load_best_grid_config() -> bool:
    """
    Read US_Backtest_Grid sheet → find Rank #1 row → override global config vars.
    Returns True if a config was successfully loaded, False otherwise.
    """
    global REBAL_DAYS, TOP_N, WEIGHTING, V_W, Q_W, M_W
    try:
        grid_ws   = spreadsheet.worksheet("US_Backtest_Grid")
        grid_data = grid_ws.get_all_values()
        if len(grid_data) < 3:
            return False

        # Find the header row (contains 'Rank' and 'Config')
        header_row = None
        for i, row in enumerate(grid_data):
            if 'Rank' in row and 'Config' in row:
                header_row = i
                break
        if header_row is None:
            return False

        headers = grid_data[header_row]
        def _col(name):
            return headers.index(name) if name in headers else None

        # Find Rank 1 data row
        rank_col = _col('Rank')
        for row in grid_data[header_row + 1:]:
            if not row or len(row) <= (rank_col or 0):
                continue
            if str(row[rank_col]).strip() == '1':
                def _get(name):
                    c = _col(name)
                    return row[c].strip() if c is not None and c < len(row) else None

                rebal  = _get('Rebal_Days')
                topn   = _get('Top_N')
                weight = _get('Weighting')
                vw     = _get('V_W')
                qw     = _get('Q_W')
                mw     = _get('M_W')
                cfg    = _get('Config')

                if all([rebal, topn, weight, vw, qw, mw]):
                    REBAL_DAYS = int(rebal)
                    TOP_N      = int(topn)
                    WEIGHTING  = str(weight)
                    V_W        = float(vw)
                    Q_W        = float(qw)
                    M_W        = float(mw)
                    print(f"[BT] ✨ Grid best config loaded: {cfg}")
                    print(f"[BT]    REBAL={REBAL_DAYS}d | Top{TOP_N} | "
                          f"{WEIGHTING} | V/Q/M={V_W}/{Q_W}/{M_W}")
                    return True
        return False

    except Exception as e:
        print(f"[BT] Grid config load skipped ({e})")
        return False

if USE_GRID_CONFIG:
    _loaded = _load_best_grid_config()
    if not _loaded:
        print("[BT] No grid config found — using defaults "
              f"(REBAL={REBAL_DAYS}d, Top{TOP_N}, {WEIGHTING}, "
              f"V/Q/M={V_W}/{Q_W}/{M_W})")
else:
    _loaded = False
    print(f"[BT] USE_GRID_CONFIG=False — using defaults "
          f"(REBAL={REBAL_DAYS}d, Top{TOP_N}, {WEIGHTING}, "
          f"V/Q/M={V_W}/{Q_W}/{M_W})")

END_DATE    = pd.Timestamp.now().normalize()
START_DATE  = END_DATE - pd.DateOffset(months=BACKTEST_QUARTERS * 3)
PRICE_START = START_DATE - pd.DateOffset(years=1)

# ── Load universe ─────────────────────────────────────────────────────────────
print("[BT] Loading US_Universe...")
ws      = spreadsheet.worksheet("US_Universe")
data    = ws.get_all_values()
univ_df = pd.DataFrame(data[1:], columns=data[0])
all_tickers = [t for t in univ_df['Ticker'].dropna().tolist()
               if t and not t.endswith(('.KS', '.KQ'))]
if 'MarketCap' in univ_df.columns:
    univ_df['MarketCap'] = pd.to_numeric(univ_df['MarketCap'], errors='coerce')
    market_cap_map = univ_df.set_index('Ticker')['MarketCap'].to_dict()
else:
    market_cap_map = {}
sector_map = univ_df.set_index('Ticker')['Sector'].to_dict() if 'Sector' in univ_df.columns else {}
print(f"[BT] Universe: {len(all_tickers)} tickers")

# ── Download prices for full backtest period + 1-yr covariance buffer ─────────
print(f"[BT] Downloading prices {PRICE_START.date()} → {END_DATE.date()}...")
BATCH = 50
price_frames = []
for i in range(0, len(all_tickers), BATCH):
    batch = all_tickers[i:i + BATCH]
    try:
        raw    = yf.download(batch,
                             start=(PRICE_START).strftime('%Y-%m-%d'),
                             end=(END_DATE + pd.Timedelta(days=2)).strftime('%Y-%m-%d'),
                             auto_adjust=True, progress=False)
        closes = raw['Close'] if isinstance(raw.columns, pd.MultiIndex) else raw
        if isinstance(closes, pd.Series):
            closes = closes.to_frame(name=batch[0])
        price_frames.append(closes)
        print(f"  batch {i // BATCH + 1}/{(len(all_tickers)-1)//BATCH+1}: {closes.shape[1]} stocks")
    except Exception as e:
        print(f"  batch error: {e}")
    time.sleep(1)

prices = pd.concat(price_frames, axis=1)
prices = prices.loc[:, ~prices.columns.duplicated()]
prices = prices.ffill()
prices = prices.dropna(axis=1, thresh=int(len(prices) * 0.4))
print(f"[BT] Prices: {prices.shape[0]} days × {prices.shape[1]} stocks")

universe = [t for t in all_tickers if t in prices.columns]
print(f"[BT] {len(universe)} tickers with price data")

# ── Risk-free rate (^IRX 3M T-bill) and SPY benchmark ────────────────────────
print("[BT] Fetching risk-free rate (^IRX) and SPY benchmark...")
try:
    irx_raw = yf.download('^IRX', period='5d', auto_adjust=True, progress=False)
    irx_col = irx_raw['Close'] if isinstance(irx_raw.columns, pd.MultiIndex) else irx_raw
    rf_ann  = float(irx_col.dropna().iloc[-1]) / 100  # e.g. 4.5 → 0.045
    print(f"  ^IRX (3M T-bill) = {rf_ann:.2%}")
except Exception:
    rf_ann = 0.04
    print("  ⚠️  ^IRX unavailable — using 4.0% rf fallback")

try:
    spy_raw = yf.download('SPY',
                          start=PRICE_START.strftime('%Y-%m-%d'),
                          end=(END_DATE + pd.Timedelta(days=2)).strftime('%Y-%m-%d'),
                          auto_adjust=True, progress=False)
    spy_col = spy_raw['Close'] if isinstance(spy_raw.columns, pd.MultiIndex) else spy_raw
    spy_prices_full = spy_col.squeeze().dropna()
    print(f"  SPY: {len(spy_prices_full)} days loaded")
except Exception as e:
    spy_prices_full = None
    print(f"  ⚠️  SPY unavailable: {e}")

# ── SEC EDGAR: CIK map, company facts, and filing-date time series ───────────
print("\n[BT] Loading SEC EDGAR point-in-time fundamentals...")
try:
    cik_map = load_cik_map(user_agent=EDGAR_UA)
    print(f"[BT] CIK map loaded: {len(cik_map)} tickers")
except Exception as e:
    cik_map = {}
    print(f"[BT] WARNING — CIK map failed: {e}. EDGAR step will be skipped.")

edgar_universe = [t for t in universe if t.upper() in cik_map]
print(f"[BT] Fetching EDGAR facts for {len(edgar_universe)} tickers "
      f"(~{len(edgar_universe)*EDGAR_DELAY/60:.0f} min before cache hits)...")
edgar_raw = fetch_company_facts_for_tickers(
    edgar_universe,
    cik_map,
    user_agent=EDGAR_UA,
    delay=EDGAR_DELAY,
) if edgar_universe else {}
print(f"[BT] EDGAR facts cached for {len(edgar_raw)} tickers")

fund_ts = build_fundamental_timeseries(edgar_raw)
print(f"[BT] Time series preprocessed for {len(fund_ts)} tickers")

def get_metrics(ticker: str, date: pd.Timestamp) -> 'dict | None':
    return get_pit_metrics(fund_ts, ticker, date)

# ── V/Q/M Scoring ─────────────────────────────────────────────────────────────
def compute_scores(fund_df: pd.DataFrame,
                   mom_series: 'pd.Series | None') -> pd.DataFrame:
    """
    fund_df    : index = ticker, columns = fundamental metrics
    mom_series : 12M-1M momentum per ticker (from precomputed matrix)

    Returns fund_df extended with:
        Value_Score | Quality_Score | Momentum_Score
        Total_Score
    """
    return compute_us_factor_scores(fund_df, weights=(V_W, Q_W, M_W), mom_series=mom_series)

# ── Risk-parity optimizer (Ledoit-Wolf + SLSQP) ───────────────────────────────
def risk_parity_weights(cov: np.ndarray) -> np.ndarray:
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

# ── Precompute 12M-1M momentum matrix (full price history) ────────────────────
print("[BT] Precomputing 12M-1M momentum matrix...")
mom_matrix = (prices / prices.shift(252) - 1) - (prices / prices.shift(21) - 1)

# ── Rebalance schedule ────────────────────────────────────────────────────────
rebal_dates = pd.date_range(start=START_DATE, end=END_DATE, freq=f'{REBAL_DAYS}D')
print(f"\n[BT] Backtest period : {START_DATE.date()} → {END_DATE.date()}")
print(f"[BT] Quarters        : {BACKTEST_QUARTERS}")
print(f"[BT] Rebalance dates : {len(rebal_dates)} (every {REBAL_DAYS} days)")
print(f"[BT] EDGAR universe  : {len(fund_ts)} tickers\n")

# ── Main backtest simulation ─────────────────────────────────────────────────
def _slippage_rate(ticker: str) -> float:
    """Market-cap based one-way slippage estimate."""
    mcap = market_cap_map.get(ticker)
    try:
        mcap = float(mcap)
    except Exception:
        return 0.0030
    if mcap >= 50e9:
        return 0.0005
    if mcap >= 10e9:
        return 0.0010
    if mcap >= 2e9:
        return 0.0020
    return 0.0050


def _portfolio_weights(top_valid: list[str], scored: pd.DataFrame, rebal_date: pd.Timestamp) -> np.ndarray:
    if WEIGHTING == 'equal_weight':
        w_arr = np.full(len(top_valid), 1.0 / len(top_valid))
    elif WEIGHTING == 'score_weighted':
        sel_scores = scored.loc[
            [t for t in top_valid if t in scored.index], 'Total_Score'
        ].reindex(top_valid).fillna(0).values
        w_arr = np.clip(sel_scores, 0, None)
        w_arr /= w_arr.sum() if w_arr.sum() > 0 else 1
        for _ in range(20):
            hi = w_arr > MAX_W; lo = w_arr < MIN_W
            if not hi.any() and not lo.any(): break
            w_arr[hi] = MAX_W; w_arr[lo] = MIN_W
            free = (~hi) & (~lo)
            rem  = 1.0 - w_arr[hi].sum() - w_arr[lo].sum()
            if free.any() and w_arr[free].sum() > 0:
                w_arr[free] *= rem / w_arr[free].sum()
        w_arr /= w_arr.sum()
    else:
        ret_hist = prices[top_valid].loc[:rebal_date].pct_change().dropna().tail(252)
        if ret_hist.shape[0] >= 30:
            try:
                lw = LedoitWolf()
                lw.fit(ret_hist)
                w_arr = risk_parity_weights(lw.covariance_ * 252)
            except Exception:
                w_arr = np.full(len(top_valid), 1.0 / len(top_valid))
        else:
            w_arr = np.full(len(top_valid), 1.0 / len(top_valid))
    return w_arr / w_arr.sum()


def _period_factor_ic(scored: pd.DataFrame, returns: pd.Series, rebal_date: pd.Timestamp, next_date: pd.Timestamp) -> list[dict]:
    rows = []
    for factor in ['Value_Score', 'Quality_Score', 'Momentum_Score', 'Total_Score']:
        if factor not in scored.columns:
            continue
        aligned = pd.concat([
            pd.to_numeric(scored[factor], errors='coerce').rename('score'),
            returns.rename('return'),
        ], axis=1).dropna()
        if len(aligned) < 20 or aligned['score'].nunique() < 2 or aligned['return'].nunique() < 2:
            continue
        rows.append({
            'Rebal_Date': rebal_date.strftime('%Y-%m-%d'),
            'Forward_End': next_date.strftime('%Y-%m-%d'),
            'Factor': factor,
            'IC': round(float(aligned['score'].corr(aligned['return'], method='spearman')), 4),
            'N': len(aligned),
        })
    return rows


def simulate_schedule(schedule: pd.DatetimeIndex, *, collect_detail: bool = True, label: str = "main") -> dict:
    portfolio_rets: list = []
    portfolio_dates: list = []
    period_log: list = []
    factor_ic_rows: list = []
    invested_fractions: list = []
    vol_estimates: list = []
    prev_holdings: set = set()
    prev_weights: dict = {}

    for i, rebal_date in enumerate(schedule[:-1]):
        next_date = schedule[i + 1]
        px_slice = prices.loc[:rebal_date]
        if len(px_slice) < 63:
            continue

        rows: dict = {}
        for ticker in fund_ts:
            if ticker not in prices.columns:
                continue
            if px_slice[ticker].dropna().shape[0] < 10:
                continue
            m = get_metrics(ticker, rebal_date)
            if m:
                rows[ticker] = m

        if len(rows) < TOP_N:
            if collect_detail:
                print(f"  {rebal_date.date()} — only {len(rows)} stocks with fundamentals, skipping")
            continue

        fund_df = pd.DataFrame(rows).T
        fund_df["MarketCap"] = pd.Series(market_cap_map).reindex(fund_df.index)
        fund_df["Sector"] = pd.Series(sector_map).reindex(fund_df.index)
        mom_at = mom_matrix.reindex([rebal_date], method='ffill')
        mom_series = mom_at.iloc[0] if not mom_at.empty else None
        scored = compute_scores(fund_df, mom_series).sort_values('Total_Score', ascending=False)

        candidates = scored.head(TOP_N * 3).index.tolist()
        px_check = px_slice.iloc[-63:][candidates].dropna(axis=1, thresh=40)
        price_valid_scored = scored.loc[[t for t in candidates if t in px_check.columns]]
        top_valid = risk_controlled_selection(
            price_valid_scored,
            target_n=TOP_N,
            market="US",
            previous_holdings=prev_holdings,
            score_col="Total_Score",
            config=RISK_CONFIG,
        )
        if len(top_valid) < 5:
            continue

        w_arr = _portfolio_weights(top_valid, scored, rebal_date)
        w_arr = apply_weight_limits(
            pd.Series(w_arr, index=top_valid),
            scored.reindex(top_valid),
            market="US",
            config=RISK_CONFIG,
        ).reindex(top_valid).to_numpy()

        stock_rets = []
        for ticker, w in zip(top_valid, w_arr):
            p0 = prices[ticker].asof(rebal_date)
            p1 = prices[ticker].asof(next_date)
            if np.isnan(p0) or np.isnan(p1) or p0 == 0:
                continue
            stock_rets.append((ticker, w, p1 / p0 - 1))
        if not stock_rets:
            continue

        tkrs, ws, rs = zip(*stock_rets)
        ws = np.array(ws, dtype=float)
        ws /= ws.sum()
        rs = np.array(rs, dtype=float)
        equity_ret = float(np.dot(ws, rs))
        weight_series = pd.Series(ws, index=tkrs)
        vol_est = estimate_portfolio_volatility(
            prices[list(tkrs)].loc[:rebal_date].pct_change().dropna().tail(252),
            weight_series,
        )
        invested_fraction = volatility_target_scalar(vol_est, target_vol=TARGET_VOL)
        cash_weight = 1.0 - invested_fraction
        cash_ret = period_cash_return(rf_ann, rebal_date, next_date)
        gross = equity_ret * invested_fraction + cash_ret * cash_weight

        curr_set = set(tkrs)
        curr_weights_dict = {
            ticker: float(weight * invested_fraction)
            for ticker, weight in zip(tkrs, ws)
        }
        traded_names = set(curr_weights_dict) | set(prev_weights)
        costs = weighted_turnover_costs(
            previous_weights=prev_weights,
            current_weights=curr_weights_dict,
            fee_rate=FEE,
            slippage_rates={ticker: _slippage_rate(ticker) for ticker in traded_names},
        )
        turnover = costs["turnover"]
        fee_cost = costs["fee"]
        slippage_cost = costs["slippage"]
        net_ret = gross - costs["total"]

        portfolio_rets.append(net_ret)
        portfolio_dates.append(next_date)
        invested_fractions.append(invested_fraction)
        vol_estimates.append(vol_est if vol_est is not None else np.nan)

        if collect_detail:
            all_period_returns = {}
            for ticker in scored.index:
                if ticker not in prices.columns:
                    continue
                p0 = prices[ticker].asof(rebal_date)
                p1 = prices[ticker].asof(next_date)
                if not np.isnan(p0) and not np.isnan(p1) and p0:
                    all_period_returns[ticker] = p1 / p0 - 1
            factor_ic_rows.extend(_period_factor_ic(scored, pd.Series(all_period_returns), rebal_date, next_date))

            top3 = ', '.join(list(tkrs)[:3])
            period_log.append({
                'Period':       f"{rebal_date.date()} → {next_date.date()}",
                'Holdings':     len(curr_set),
                'Gross_Return': round(gross, 4),
                'Equity_Return': round(equity_ret, 4),
                'Fee':          round(fee_cost, 4),
                'Slippage':     round(slippage_cost, 4),
                'Net_Return':   round(net_ret, 4),
                'Turnover_Pct': round(turnover, 4),
                'Invested_Fraction': round(invested_fraction, 4),
                'Cash_Weight': round(cash_weight, 4),
                'Portfolio_Vol_Est': round(vol_est, 4) if vol_est is not None else '',
                'Top3_Tickers': top3,
            })

            if (i + 1) % 13 == 0:
                cum = (1 + pd.Series(portfolio_rets)).cumprod().iloc[-1] - 1
                print(f"  [{i+1:>3}/{len(schedule)-1}] {rebal_date.date()} "
                      f"→ {len(curr_set)} stocks | net={net_ret:+.2%} | cum={cum:+.2%}")

        prev_holdings = curr_set
        prev_weights = curr_weights_dict

    return {
        'label': label,
        'returns': portfolio_rets,
        'dates': portfolio_dates,
        'period_log': period_log,
        'factor_ic_rows': factor_ic_rows,
        'invested_fractions': invested_fractions,
        'vol_estimates': vol_estimates,
    }


print("[BT] ── Running Fundamental Backtest ─────────────────────────────────────")
main_result = simulate_schedule(rebal_dates, collect_detail=True, label="offset_0")
portfolio_rets = main_result['returns']
portfolio_dates = main_result['dates']
period_log = main_result['period_log']
factor_ic_rows = main_result['factor_ic_rows']
avg_invested_fraction = pd.Series(main_result.get('invested_fractions', []), dtype=float).mean()
avg_vol_est = pd.Series(main_result.get('vol_estimates', []), dtype=float).dropna().mean()

print(f"\n[BT] Backtest complete: {len(portfolio_rets)} periods traded")

# ── Performance statistics ────────────────────────────────────────────────────
if not portfolio_rets:
    print("[BT] No returns computed. Exiting.")
    exit(1)

ret_s  = pd.Series(portfolio_rets, index=pd.DatetimeIndex(portfolio_dates))
cumret = (1 + ret_s).cumprod()
dd     = (cumret / cumret.cummax()) - 1
n      = len(ret_s)
ppy    = 365.0 / REBAL_DAYS   # periods per year

rf_per_period = rf_ann / ppy
main_stats = performance_stats(ret_s, periods_per_year=ppy, rf_per_period=rf_per_period)
cagr         = main_stats["CAGR"]
vol_ann      = main_stats["Ann_Volatility"]
sharpe       = main_stats["Sharpe"]
max_dd       = main_stats["Max_Drawdown"]
total_ret    = main_stats["Total_Return"]
win_rate     = main_stats["Win_Rate"]
avg_ret      = main_stats["Avg_Period_Return"]

is_ret, oos_ret = split_in_out_sample(ret_s, train_fraction=1.0 - OOS_FRACTION)
is_stats = performance_stats(is_ret, periods_per_year=ppy, rf_per_period=rf_per_period)
oos_stats = performance_stats(oos_ret, periods_per_year=ppy, rf_per_period=rf_per_period)
bootstrap_stats = bootstrap_sharpe_ci(
    ret_s,
    periods_per_year=ppy,
    rf_per_period=rf_per_period,
    samples=BOOTSTRAP_SAMPLES,
)

offset_rows = []
for offset in OFFSET_TEST_DAYS:
    offset_schedule = rebal_dates + pd.Timedelta(days=offset)
    if offset == 0:
        offset_ret_s = ret_s
    else:
        offset_result = simulate_schedule(offset_schedule, collect_detail=False, label=f"offset_{offset}")
        offset_ret_s = pd.Series(offset_result['returns'], index=pd.DatetimeIndex(offset_result['dates']))
    stats = performance_stats(offset_ret_s, periods_per_year=ppy, rf_per_period=rf_per_period)
    offset_rows.append({
        "Offset_Days": offset,
        "CAGR": stats["CAGR"],
        "Sharpe": stats["Sharpe"],
        "Max_Drawdown": stats["Max_Drawdown"],
        "Periods": stats["Periods"],
    })
offset_df = offset_sensitivity_table(offset_rows)
factor_ic_df = pd.DataFrame(factor_ic_rows)

# ── SPY benchmark comparison ─────────────────────────────────────────────────
spy_cagr_val = spy_sharpe_val = spy_vol_val = alpha_val = None
if spy_prices_full is not None and len(portfolio_dates) >= 2:
    spy_bt = spy_prices_full[
        (spy_prices_full.index >= portfolio_dates[0]) &
        (spy_prices_full.index <= portfolio_dates[-1])
    ]
    if len(spy_bt) >= 5:
        spy_years     = (spy_bt.index[-1] - spy_bt.index[0]).days / 365.25
        spy_total_ret = float(spy_bt.iloc[-1] / spy_bt.iloc[0]) - 1
        spy_cagr_val  = (1 + spy_total_ret) ** (1 / spy_years) - 1 if spy_years > 0 else 0
        spy_dr        = spy_bt.pct_change().dropna()
        spy_vol_val   = spy_dr.std() * np.sqrt(252)
        spy_sharpe_val = (spy_dr.mean() * 252 - rf_ann) / (spy_vol_val + 1e-9)
        alpha_val     = cagr - spy_cagr_val

print(f"\n[BT] ── Results ─────────────────────────────────────────────────────────")
print(f"  Period       : {START_DATE.date()} → {END_DATE.date()}")
print(f"  Risk-Free    : {rf_ann:.2%} (^IRX 3M T-bill)")
print(f"  CAGR         : {cagr:+.2%}")
print(f"  Total Return : {total_ret:+.2%}")
print(f"  Sharpe       : {sharpe:.2f}  (excess return over rf)")
print(f"  Max Drawdown : {max_dd:.2%}")
print(f"  Ann. Vol     : {vol_ann:.2%}")
print(f"  Target Vol   : {TARGET_VOL:.2%}")
print(f"  Avg Invested : {avg_invested_fraction:.1%}" if pd.notna(avg_invested_fraction) else "  Avg Invested : N/A")
print(f"  Win Rate     : {win_rate:.1%}")
print(f"  Periods      : {n}")
if spy_cagr_val is not None:
    print(f"\n[BT] ── vs SPY Benchmark ────────────────────────────────────────────────")
    print(f"  SPY CAGR     : {spy_cagr_val:+.2%}")
    print(f"  SPY Sharpe   : {spy_sharpe_val:.2f}")
    print(f"  Alpha        : {alpha_val:+.2%}")

# ── Build Google Sheets output ────────────────────────────────────────────────
summary = [
    ["── SEC EDGAR Fundamental Backtest ──", ""],
    ["Strategy",        f"{WEIGHTING.replace('_',' ').title()} Top {TOP_N} | "
                        f"V/Q/M Score | {REBAL_DAYS}-Day Rebalance"],
    ["Config Source",   "US_Backtest_Grid (auto)" if (USE_GRID_CONFIG and _loaded) else "Default (manual)"],
    ["Universe",        "S&P 500 + NASDAQ 100 (SEC EDGAR XBRL financials)"],
    ["Period",          f"{START_DATE.date()} → {END_DATE.date()} "
                        f"({BACKTEST_QUARTERS} Quarters)"],
    ["Scoring",         f"Shared live/backtest scorer | V/Q/M={V_W:.0%}/{Q_W:.0%}/{M_W:.0%}"],
    ["Portfolio",       f"Top {TOP_N} by Total_Score | "
                        f"{WEIGHTING} | {MIN_W:.0%}–{MAX_W:.0%} bounds | risk caps + turnover buffer"],
    ["Costs",           f"{FEE:.3%} fee + market-cap slippage per turnover"],
    ["Vol Target",      f"Target {TARGET_VOL:.2%}; avg invested {avg_invested_fraction:.4f}" if pd.notna(avg_invested_fraction) else f"Target {TARGET_VOL:.2%}"],
    ["Avg Port Vol Est", f"{avg_vol_est:.4f}" if pd.notna(avg_vol_est) else "N/A"],
    ["Risk Controls",   f"Max position {RISK_CONFIG.max_position_weight:.0%}, sector {RISK_CONFIG.max_sector_weight:.0%}, "
                        f"illiquid {RISK_CONFIG.max_illiquid_weight:.0%}, max new names {RISK_CONFIG.max_turnover_fraction:.0%}"],
    ["Research Split",  f"IS first {1-OOS_FRACTION:.0%} periods | OOS last {OOS_FRACTION:.0%} periods"],
    ["EDGAR tickers",   str(len(fund_ts))],
    ["", ""],
    ["── Performance ──", ""],
    ["CAGR",            f"{cagr:.4f}"],
    ["Total Return",    f"{total_ret:.4f}"],
    ["Ann. Volatility", f"{vol_ann:.4f}"],
    ["Sharpe Ratio",    f"{sharpe:.4f}  (vs rf={rf_ann:.2%})"],
    ["Max Drawdown",    f"{max_dd:.4f}"],
    ["Win Rate",        f"{win_rate:.4f}"],
    ["Avg Period Ret",  f"{avg_ret:.4f}"],
    ["Best Period",     f"{ret_s.max():.4f}"],
    ["Worst Period",    f"{ret_s.min():.4f}"],
    ["Periods",         str(n)],
    ["Risk-Free Rate",  f"{rf_ann:.4f}  (^IRX 3M T-bill)"],
    ["── IS / OOS ──", ""],
    ["IS Periods",      str(is_stats["Periods"])],
    ["IS CAGR",         f"{is_stats['CAGR']:.4f}"],
    ["IS Sharpe",       f"{is_stats['Sharpe']:.4f}"],
    ["OOS Periods",     str(oos_stats["Periods"])],
    ["OOS CAGR",        f"{oos_stats['CAGR']:.4f}"],
    ["OOS Sharpe",      f"{oos_stats['Sharpe']:.4f}"],
    ["OOS Max DD",      f"{oos_stats['Max_Drawdown']:.4f}"],
    ["── Bootstrap Robustness ──", ""],
    ["Sharpe 5%-95% CI", f"{bootstrap_stats['Sharpe_CI_Low']:.4f} → {bootstrap_stats['Sharpe_CI_High']:.4f}"],
    ["P(Sharpe > 0)",   f"{bootstrap_stats['Prob_Sharpe_GT_0']:.4f}"],
    ["── SPY Benchmark ──", ""],
    ["SPY CAGR",        f"{spy_cagr_val:.4f}"   if spy_cagr_val  is not None else "N/A"],
    ["SPY Sharpe",      f"{spy_sharpe_val:.4f}" if spy_sharpe_val is not None else "N/A"],
    ["Alpha vs SPY",    f"{alpha_val:.4f}"       if alpha_val      is not None else "N/A"],
    ["Generated",       pd.Timestamp.now().strftime('%Y-%m-%d %H:%M')],
    ["", ""],
]

# Period return table
ret_tbl = pd.DataFrame({
    "Date":           ret_s.index.strftime('%Y-%m-%d'),
    "Net_Return":     ret_s.values.round(4),
    "Cumulative_Ret": cumret.values.round(4),
    "Drawdown":       dd.values.round(4),
})

# Detailed per-period log
log_df = pd.DataFrame(period_log)
offset_out = offset_df.copy()
for col in ["CAGR", "Sharpe", "Max_Drawdown"]:
    if col in offset_out.columns:
        offset_out[col] = offset_out[col].map(lambda x: "" if pd.isna(x) else round(float(x), 4))

factor_ic_out = factor_ic_df.copy()
if not factor_ic_out.empty:
    factor_ic_out["IC"] = factor_ic_out["IC"].map(lambda x: "" if pd.isna(x) else round(float(x), 4))

rows = (
    summary
    + [ret_tbl.columns.tolist()]
    + [[str(v) for v in r] for r in ret_tbl.values.tolist()]
    + [["", ""]]
    + [["── Rebalance Offset Sensitivity ──", "", "", "", "", ""]]
    + [offset_out.columns.tolist()]
    + [[str(v) for v in r] for r in offset_out.values.tolist()]
    + [["", ""]]
    + [["── Period Detail ──", "", "", "", "", "", ""]]
    + [log_df.columns.tolist()]
    + [[str(v) for v in r] for r in log_df.values.tolist()]
    + [["", ""]]
    + [["── Backtest Factor IC ──", "", "", "", ""]]
    + ([factor_ic_out.columns.tolist()] if not factor_ic_out.empty else [["Status"]])
    + ([[str(v) for v in r] for r in factor_ic_out.values.tolist()] if not factor_ic_out.empty else [["No IC rows"]])
)

# ── Save to US_Backtest_Results ───────────────────────────────────────────────
print("\n[BT] Saving to US_Backtest_Results...")
try:
    bt_sheet = spreadsheet.worksheet("US_Backtest_Results")
except Exception:
    bt_sheet = spreadsheet.add_worksheet(
        title="US_Backtest_Results", rows=2000, cols=15)

bt_sheet.clear()
bt_sheet.update(range_name='A1', values=rows, value_input_option='USER_ENTERED')
dual_write_dataframe("US_Backtest_Results", ret_tbl, market="US")
if not log_df.empty:
    dual_write_dataframe("US_Backtest_Period_Detail", log_df, market="US")
if not offset_df.empty:
    dual_write_dataframe("US_Backtest_Offset_Sensitivity", offset_df, market="US")
if not factor_ic_df.empty:
    dual_write_dataframe("US_Backtest_Factor_IC", factor_ic_df, market="US")

print(f"✅ [BT] Saved {len(rows)} rows to US_Backtest_Results")
print(f"   CAGR={cagr:.2%} | Sharpe={sharpe:.2f} | "
      f"MaxDD={max_dd:.2%} | WinRate={win_rate:.1%}")
print(f"   OOS CAGR={oos_stats['CAGR']:.2%} | OOS Sharpe={oos_stats['Sharpe']:.2f} | "
      f"P(Sharpe>0)={bootstrap_stats['Prob_Sharpe_GT_0']:.1%}")
