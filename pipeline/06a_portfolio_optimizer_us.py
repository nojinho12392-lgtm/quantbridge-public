# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
import yfinance as yf
from sheets_client import get_spreadsheet
import pandas as pd
import numpy as np
from scipy.optimize import minimize
from scipy.cluster.hierarchy import linkage, leaves_list
from scipy.spatial.distance import squareform
from sklearn.covariance import LedoitWolf
import time
import warnings
warnings.filterwarnings('ignore')
from quantbridge.writers.dual_write import dual_write_dataframe
from pipeline.portfolio.risk_controls import (
    PortfolioRiskConfig,
    apply_weight_limits,
    risk_controlled_selection,
    risk_limit_summary,
)
from pipeline.portfolio.rebalance_report import (
    build_rebalance_report,
    read_current_holdings_sheet,
    read_previous_portfolio_sheet,
    rebalance_config_from_env,
    write_rebalance_report_sheet,
)
from pipeline.portfolio.risk_report import RiskReportConfig, build_risk_report, write_risk_report_sheet
from quantbridge.ticker_policy import (
    banned_tickers_label,
    drop_banned_ticker_rows,
    filter_banned_tickers,
)

# ── Google Sheets connection ──────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()

TOP_N        = 30   # stocks to consider for optimization
MIN_W        = 0.01 # 1 % floor per stock
MAX_W        = 0.15 # 15 % cap per stock
TARGET_VOL   = 0.12 # 12% annualized target volatility for dynamic sizing
CVAR_LIMIT   = -0.20 # CVaR(95%) floor: -20% annualized
ANALYZE_ONLY = os.environ.get('QUANT_ANALYZE_ONLY') == 'true'
RISK_CONFIG  = PortfolioRiskConfig(
    max_position_weight=float(os.environ.get("QUANT_US_MAX_POSITION_WEIGHT", "0.10")),
    max_sector_weight=float(os.environ.get("QUANT_US_MAX_SECTOR_WEIGHT", "0.30")),
    max_illiquid_weight=float(os.environ.get("QUANT_US_MAX_ILLIQUID_WEIGHT", "0.20")),
    max_turnover_fraction=float(os.environ.get("QUANT_US_MAX_TURNOVER_FRACTION", "0.50")),
)
REBALANCE_CONFIG = rebalance_config_from_env(
    "US",
    default_portfolio_value=100_000.0,
    default_min_trade_value=50.0,
    default_fee_rate=0.001,
    default_fractional_shares=False,
)
if ANALYZE_ONLY:
    print("\n⚡ ANALYZE-ONLY : skipping price download — equal weights will be used")


def _read_previous_holdings(ss, sheet_name: str) -> set[str]:
    try:
        rows = ss.worksheet(sheet_name).get_all_values()
    except Exception:
        return set()
    header_idx = None
    for idx, row in enumerate(rows):
        if "Ticker" in row:
            header_idx = idx
            break
    if header_idx is None:
        return set()
    ticker_col = rows[header_idx].index("Ticker")
    holdings = {
        str(row[ticker_col]).strip()
        for row in rows[header_idx + 1 :]
        if len(row) > ticker_col and str(row[ticker_col]).strip()
    }
    return set(filter_banned_tickers(holdings))

# ── Load macro regime ─────────────────────────────────────────────────────────
def _load_regime(ss) -> str:
    try:
        ws   = ss.worksheet('Macro_Regime')
        rows = ws.get_all_values()
        kv   = {r[0].strip(): r[1].strip() for r in rows if len(r) >= 2 and r[0].strip()}
        return kv.get('Regime', 'NEUTRAL')
    except Exception:
        return 'NEUTRAL'

# ── Risk-parity optimizer ─────────────────────────────────────────────────────
def risk_parity_weights(cov: np.ndarray) -> np.ndarray:
    n = len(cov)
    target_rc = 1.0 / n

    def objective(w):
        pv = float(w @ cov @ w)
        if pv <= 0:
            return 1e9
        rc = w * (cov @ w) / pv   # relative risk contribution
        return float(np.sum((rc - target_rc) ** 2))

    res = minimize(
        objective,
        x0      = np.full(n, 1.0 / n),
        method  = 'SLSQP',
        bounds  = [(MIN_W, MAX_W)] * n,
        constraints = [{'type': 'eq', 'fun': lambda w: np.sum(w) - 1}],
        options = {'maxiter': 2000, 'ftol': 1e-10},
    )
    w = res.x if res.success else np.full(n, 1.0 / n)
    return w / w.sum()   # re-normalise

# ── Hierarchical Risk Parity (López de Prado, 2016) ──────────────────────────
def hrp_weights(cov: np.ndarray) -> np.ndarray:
    """
    HRP: cluster assets by return correlation, allocate inversely proportional
    to intra-cluster variance via recursive bisection.
    More robust to covariance estimation error than risk-parity (no matrix inversion).
    """
    n = len(cov)
    std = np.sqrt(np.diag(cov))
    std[std == 0] = 1e-9
    corr = cov / np.outer(std, std)
    corr = np.clip(corr, -1, 1)
    np.fill_diagonal(corr, 1.0)

    dist = np.sqrt((1 - corr) / 2.0)
    condensed = squareform(dist, checks=False)
    link = linkage(condensed, method='single')
    order = list(leaves_list(link))

    def _cluster_var(items):
        sub = cov[np.ix_(items, items)]
        ivp = 1.0 / np.maximum(np.diag(sub), 1e-12)
        ivp /= ivp.sum()
        return float(ivp @ sub @ ivp)

    def _bisect(items):
        if len(items) == 1:
            return {items[0]: 1.0}
        mid   = len(items) // 2
        left, right = items[:mid], items[mid:]
        lv, rv = _cluster_var(left), _cluster_var(right)
        alpha  = 1 - lv / (lv + rv + 1e-12)
        wl = {k: alpha * v       for k, v in _bisect(left).items()}
        wr = {k: (1 - alpha) * v for k, v in _bisect(right).items()}
        return {**wl, **wr}

    raw = _bisect(order)
    w   = np.array([raw[i] for i in range(n)])
    w   = np.clip(w, MIN_W, MAX_W)
    return w / w.sum()

# ── CVaR (Historical Simulation, 95% confidence) ─────────────────────────────
def compute_cvar(daily_returns: pd.DataFrame, weights: np.ndarray, alpha: float = 0.05) -> float:
    port_rets = daily_returns @ weights
    cutoff    = np.percentile(port_rets, alpha * 100)
    tail      = port_rets[port_rets <= cutoff]
    return float(tail.mean() * 252) if len(tail) > 0 else float('nan')   # annualised

# ── Load US_Scored_Stocks (contains factor scores + ML_Score + Combined_Score) ──
print("[OPT] Loading US_Scored_Stocks...")
try:
    ss_sheet   = spreadsheet.worksheet("US_Scored_Stocks")
    data       = ss_sheet.get_all_values()
    scored_df  = pd.DataFrame(data[1:], columns=data[0])
    before_policy = len(scored_df)
    scored_df = drop_banned_ticker_rows(scored_df)
    if len(scored_df) != before_policy:
        print(f"[OPT] Banned US tickers excluded: {banned_tickers_label()}")
    # Use Combined_Score if present (added by 04_ml_model.py), else Total_Score
    score_col  = 'Combined_Score' if 'Combined_Score' in scored_df.columns else 'Total_Score'
    for col in [score_col, 'Total_Score']:
        if col in scored_df.columns:
            scored_df[col] = pd.to_numeric(scored_df[col], errors='coerce')
    if 'MarketCap' in scored_df.columns:
        scored_df['MarketCap'] = pd.to_numeric(scored_df['MarketCap'], errors='coerce')
    scored_df  = scored_df.sort_values(score_col, ascending=False, na_position='last')
    previous_holdings = _read_previous_holdings(spreadsheet, "US_Final_Portfolio")
    selected_tickers = risk_controlled_selection(
        scored_df.set_index("Ticker", drop=False),
        target_n=TOP_N,
        market="US",
        previous_holdings=previous_holdings,
        score_col=score_col,
        config=RISK_CONFIG,
    )
    ml_df      = scored_df.set_index("Ticker", drop=False).reindex(selected_tickers).dropna(subset=[score_col])
    tickers    = ml_df['Ticker'].tolist()
except Exception as e:
    print(f"[OPT] US_Scored_Stocks not found: {e}")
    exit(1)

score_map = ml_df.set_index('Ticker')[score_col].to_dict()
meta_map  = {
    row['Ticker']: {
        'Name':        row.get('Name', ''),
        'Sector':      row.get('Sector', ''),
        'MarketCap':   row.get('MarketCap', ''),
        'ROIC':        row.get('ROIC', ''),
        'RevGrowth':   row.get('RevGrowth', ''),
        'GrossMargin': row.get('GrossMargin', ''),
        'Total_Score': row.get('Total_Score', ''),
    }
    for _, row in scored_df.iterrows()
}

print(f"[OPT] Optimizing {len(tickers)} stocks  (score column: {score_col})")

# ── Download 1 year of prices for covariance ─────────────────────────────────
if not ANALYZE_ONLY:
    print("[OPT] Downloading prices...")
    BATCH = 50
    price_frames = []
    for i in range(0, len(tickers), BATCH):
        batch = tickers[i:i + BATCH]
        try:
            raw = yf.download(batch, period="1y", auto_adjust=True, progress=False)
            closes = raw['Close'] if isinstance(raw.columns, pd.MultiIndex) else raw
            if isinstance(closes, pd.Series):
                closes = closes.to_frame(name=batch[0])
            price_frames.append(closes)
        except Exception as e:
            print(f"  batch error: {e}")
        time.sleep(1)

    prices = pd.concat(price_frames, axis=1)
    prices = prices.loc[:, ~prices.columns.duplicated()]
    prices = prices.dropna(axis=1, thresh=int(len(prices) * 0.8))

    valid = [t for t in tickers if t in prices.columns]
    prices = prices[valid]
    print(f"[OPT] Valid stocks for optimization: {len(valid)}")

    # ── Covariance matrix (Ledoit-Wolf shrinkage) ────────────────────────────
    # Raw sample covariance with only 252 observations and 30 stocks suffers
    # from estimation error — extreme off-diagonal entries distort risk-parity
    # weights.  Ledoit-Wolf shrinks the sample covariance toward a scaled
    # identity matrix, dramatically reducing that error with zero free parameters.
    returns = prices.pct_change().dropna()
    lw = LedoitWolf()
    lw.fit(returns)
    cov_matrix = lw.covariance_ * 252          # annualised shrunk covariance
    print(f"[OPT] Ledoit-Wolf shrinkage coefficient: {lw.shrinkage_:.4f}  "
          f"(0=sample cov, 1=diagonal)")

    # ── Optimize: Risk-Parity + HRP blend ────────────────────────────────────
    print("[OPT] Running risk-parity optimisation...")
    rp_w = risk_parity_weights(cov_matrix)

    print("[OPT] Running Hierarchical Risk Parity...")
    hrp_w = hrp_weights(cov_matrix)

    # Blend 60% Risk-Parity + 40% HRP → more robust than either alone
    weights = 0.6 * rp_w + 0.4 * hrp_w
    weights = np.clip(weights, MIN_W, MAX_W)
    weights /= weights.sum()
    meta_for_valid = scored_df.set_index("Ticker", drop=False).reindex(valid)
    weights = apply_weight_limits(
        pd.Series(weights, index=valid),
        meta_for_valid,
        market="US",
        config=RISK_CONFIG,
    ).reindex(valid).to_numpy()
    print(f"[OPT] Blended weights  (RP×0.6 + HRP×0.4)  "
          f"min={weights.min():.3f}  max={weights.max():.3f}")
else:
    # No price data available — fall back to equal weights
    valid      = tickers
    n          = len(valid)
    weights    = np.array([1.0 / n] * n)
    cov_matrix = np.eye(n)
    returns    = pd.DataFrame(np.zeros((1, n)), columns=valid)
    meta_for_valid = scored_df.set_index("Ticker", drop=False).reindex(valid)
    weights = apply_weight_limits(
        pd.Series(weights, index=valid),
        meta_for_valid,
        market="US",
        config=RISK_CONFIG,
    ).reindex(valid).to_numpy()
    print(f"[OPT] ANALYZE-ONLY: equal weights applied ({n} stocks)")

# ── Standard PORTFOLIO_COLS schema ────────────────────────────────────────────
PORTFOLIO_COLS = [
    'Rank', 'Ticker', 'Name', 'Market', 'Sector', 'MarketCap',
    'Weight(%)', 'Current_Price', 'Return_1M', 'Total_Score', 'ROIC', 'RevGrowth',
    'GrossMargin', 'Expected_Return', 'Last_Updated',
]

from datetime import datetime

# ── CVaR + Dynamic Position Sizing ────────────────────────────────────────────
port_vol = np.sqrt(weights @ cov_matrix @ weights)

# CVaR(95%) — historical simulation on daily returns (annualised)
cvar_ann   = compute_cvar(returns[valid], weights) if not ANALYZE_ONLY else float('nan')
cvar_label = f"{cvar_ann:.2%}" if not np.isnan(cvar_ann) else "N/A"

# Dynamic Position Sizing: scale invested fraction so realized vol ≈ TARGET_VOL
# ANALYZE_ONLY uses identity covariance → vol is meaningless → skip sizing
macro_regime = _load_regime(spreadsheet)
if ANALYZE_ONLY:
    vol_scalar = 1.0
    print("[OPT] ANALYZE-ONLY: Dynamic Sizing skipped (no real covariance)")
else:
    vol_scalar = min(1.0, TARGET_VOL / max(port_vol, 1e-6))
    if macro_regime == 'RISK_OFF':
        vol_scalar = min(vol_scalar, 0.80)
        print(f"[OPT] RISK_OFF regime: capping invested fraction at 80%")
cash_weight = round(1.0 - vol_scalar, 4)

# Apply scalar: weights now represent fraction of total capital (rest = cash)
weights_invested = weights * vol_scalar
limit_summary = risk_limit_summary(
    pd.Series(weights, index=valid),
    meta_for_valid,
    market="US",
    config=RISK_CONFIG,
)

print(f"[OPT] CVaR(95%) ann.    : {cvar_label}")
print(f"[OPT] Dynamic Sizing    : vol_scalar={vol_scalar:.2f}  cash={cash_weight:.1%}  "
      f"regime={macro_regime}")
print(f"[OPT] Risk Limits       : max_pos={limit_summary['max_position']:.1%}  "
      f"max_sector={limit_summary['max_sector']:.1%}  illiquid={limit_summary['illiquid_weight']:.1%}")

# Warn if CVaR breaches the limit (informational only — no hard override)
if not np.isnan(cvar_ann) and cvar_ann < CVAR_LIMIT:
    print(f"[OPT] ⚠️  CVaR {cvar_ann:.2%} breaches limit ({CVAR_LIMIT:.2%}) — "
          f"consider reducing position sizes or rebalancing")

# ── Portfolio-level metrics (must be computed before result DataFrame) ────────
US_RF_ANN    = 0.04   # US 3M T-bill proxy for Sharpe; update if ^IRX changes materially
hist_ann     = returns[valid].mean() * 252
market_ret   = float(hist_ann.mean())   # cross-sectional mean as market proxy
per_stock_er = (0.7 * hist_ann + 0.3 * market_ret).round(4)

# Equity sleeve return & vol (30-stock portfolio, weights sum to 1)
port_ret_equity = float(per_stock_er @ weights)

# Full portfolio return: equity sleeve * vol_scalar + cash * rf_rate
port_ret_avg = port_ret_equity * vol_scalar + US_RF_ANN * cash_weight
# Full portfolio vol: equity vol scaled by invested fraction
port_vol_total = port_vol * vol_scalar
# Sharpe on full portfolio (cash earns rf) — equivalent to equity sleeve Sharpe by one-fund theorem
sharpe_est   = (port_ret_avg - US_RF_ANN) / (port_vol_total + 1e-9)

result = pd.DataFrame({
    'Ticker':        valid,
    'Weight(%)':     weights_invested.round(4),   # fraction of total capital (cash excluded)
    'Combined_Score':[round(float(score_map.get(t, 0)), 4) for t in valid],
    'Current_Price': [round(float(prices[t].iloc[-1]), 2) if not ANALYZE_ONLY else '' for t in valid],
    'Return_1M': [
        round(float((prices[t].iloc[-1] / prices[t].dropna().iloc[-22]) - 1), 4)
        if not ANALYZE_ONLY and t in prices.columns and len(prices[t].dropna()) >= 22 else ''
        for t in valid
    ],
})
result = drop_banned_ticker_rows(result)
result = result.sort_values('Weight(%)', ascending=False).reset_index(drop=True)
result['Rank']            = result.index + 1
result['Market']          = 'US'
result['Name']            = result['Ticker'].map(lambda t: meta_map.get(t, {}).get('Name', ''))
result['Sector']          = result['Ticker'].map(lambda t: meta_map.get(t, {}).get('Sector', ''))
result['MarketCap']       = result['Ticker'].map(lambda t: meta_map.get(t, {}).get('MarketCap', ''))
result['ROIC']            = result['Ticker'].map(lambda t: meta_map.get(t, {}).get('ROIC', ''))
result['RevGrowth']       = result['Ticker'].map(lambda t: meta_map.get(t, {}).get('RevGrowth', ''))
result['GrossMargin']     = result['Ticker'].map(lambda t: meta_map.get(t, {}).get('GrossMargin', ''))
result['Total_Score']     = result['Ticker'].map(lambda t: meta_map.get(t, {}).get('Total_Score', str(score_map.get(t, ''))))
result['Expected_Return'] = result['Ticker'].map(per_stock_er).round(4)  # 종목별 개별값
result['Last_Updated']    = datetime.now().strftime('%Y-%m-%d')

print(f"\n[OPT] ── Portfolio Summary ───────────────────────")
print(f"  Stocks              : {len(valid)}")
print(f"  Ann. Volatility     : {port_vol:.2%}")
print(f"  CVaR(95%) ann.      : {cvar_label}")
print(f"  Est. Ann. Return    : {port_ret_avg:.2%}")
print(f"  Est. Sharpe         : {sharpe_est:.2f}")
print(f"  Invested Fraction   : {vol_scalar:.1%}  (cash={cash_weight:.1%})")
print(f"  Max Sector Weight   : {limit_summary['max_sector']:.1%}")
print(f"  Illiquid Weight     : {limit_summary['illiquid_weight']:.1%}")
print(f"  Macro Regime        : {macro_regime}")
print(f"\n  Top 10 holdings:")
for _, row in result.head(10).iterrows():
    print(f"    {row['Ticker']:8s}  {row['Weight(%)'] * 100:5.1f}%  "
          f"score={row['Combined_Score']:.4f}  ${row['Current_Price']}")

# ── Save to US_Final_Portfolio sheet ─────────────────────────────────────────────
summary = [
    ["── Final Portfolio ──",      ""],
    ["Strategy",                   "RP×0.6+HRP×0.4 (Ledoit-Wolf) + Factor/ML Scoring + risk caps + turnover buffer"],
    ["Number of Stocks",           str(len(valid))],
    ["Ann. Volatility (est.)",     f"{port_vol:.4f}"],
    ["CVaR(95%) ann.",             cvar_label],
    ["Ann. Return (hist. est.)",   f"{port_ret_avg:.4f}"],
    ["Sharpe (est.)",              f"{sharpe_est:.4f}"],
    ["Macro Regime",               macro_regime],
    ["Invested Fraction",          f"{vol_scalar:.4f}"],
    ["Cash Weight",                f"{cash_weight:.4f}"],
    ["Max Weight",                 f"{weights_invested.max():.4f}"],
    ["Min Weight",                 f"{weights_invested.min():.4f}"],
    ["Max Sector Weight",          f"{limit_summary['max_sector'] * vol_scalar:.4f}"],
    ["Illiquid Weight",            f"{limit_summary['illiquid_weight'] * vol_scalar:.4f}"],
    ["Turnover Buffer",            f"keep until rank <= {RISK_CONFIG.sell_rank_multiplier:.1f}x TopN; max new names {RISK_CONFIG.max_turnover_fraction:.0%}"],
    ["Generated",                  pd.Timestamp.now().strftime('%Y-%m-%d %H:%M')],
    ["", ""],
]

# ── Apply standard schema ────────────────────────────────────────────────────
for col in PORTFOLIO_COLS:
    if col not in result.columns:
        result[col] = ''
hold_df = drop_banned_ticker_rows(result[PORTFOLIO_COLS].copy())
sheet_hold_df = hold_df.fillna('').astype(str)

risk_summary, risk_holdings, risk_sectors = build_risk_report(
    market="US",
    tickers=valid,
    weights=pd.Series(weights_invested, index=valid),
    cov_matrix=cov_matrix,
    metadata=result.set_index("Ticker", drop=False),
    target_vol=TARGET_VOL,
    invested_fraction=vol_scalar,
    cash_weight=cash_weight,
    config=RiskReportConfig(max_position_warn=RISK_CONFIG.max_position_weight),
)

current_holdings_df = read_current_holdings_sheet(spreadsheet, "US")
previous_portfolio_df = read_previous_portfolio_sheet(spreadsheet, "US_Final_Portfolio")
rebalance_summary, rebalance_orders = build_rebalance_report(
    market="US",
    target_portfolio=result,
    current_holdings=current_holdings_df,
    previous_portfolio=previous_portfolio_df,
    config=REBALANCE_CONFIG,
)

try:
    port_sheet = spreadsheet.worksheet("US_Final_Portfolio")
except Exception:
    port_sheet = spreadsheet.add_worksheet(title="US_Final_Portfolio", rows=200, cols=len(PORTFOLIO_COLS))

rows = (summary
        + [sheet_hold_df.columns.tolist()]
        + sheet_hold_df.values.tolist())

port_sheet.clear()
port_sheet.update(range_name='A1', values=rows, value_input_option='USER_ENTERED')
dual_write_dataframe("US_Final_Portfolio", hold_df, market="US")

write_risk_report_sheet(
    spreadsheet,
    "US_Final_Portfolio_Risk",
    risk_summary,
    risk_holdings,
    risk_sectors,
)
dual_write_dataframe("US_Final_Portfolio_Risk_Summary", risk_summary, market="US")
dual_write_dataframe("US_Final_Portfolio_Risk", risk_holdings, market="US")
dual_write_dataframe("US_Final_Portfolio_Risk_Sectors", risk_sectors, market="US")

write_rebalance_report_sheet(
    spreadsheet,
    "US_Rebalance_Execution",
    rebalance_summary,
    rebalance_orders,
)
dual_write_dataframe("US_Rebalance_Execution_Summary", rebalance_summary, market="US")
dual_write_dataframe("US_Rebalance_Execution", rebalance_orders, market="US")

print(f"\n✅ [OPT] Final portfolio saved to US_Final_Portfolio sheet")
print("✅ [OPT] Risk report saved to US_Final_Portfolio_Risk sheet")
print("✅ [OPT] Rebalance execution report saved to US_Rebalance_Execution sheet")
