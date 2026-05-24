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

# ── Google Sheets connection ──────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()

TOP_N        = 30   # stocks to consider for optimization
MIN_W        = 0.01 # 1 % floor per stock
MAX_W        = 0.15 # 15 % cap per stock
TARGET_VOL   = 0.14 # 14% annualized target volatility (KR market is more volatile than US)
ANALYZE_ONLY = os.environ.get('QUANT_ANALYZE_ONLY') == 'true'
RISK_CONFIG  = PortfolioRiskConfig(
    max_position_weight=float(os.environ.get("QUANT_KR_MAX_POSITION_WEIGHT", "0.10")),
    max_sector_weight=float(os.environ.get("QUANT_KR_MAX_SECTOR_WEIGHT", "0.30")),
    max_illiquid_weight=float(os.environ.get("QUANT_KR_MAX_ILLIQUID_WEIGHT", "0.20")),
    max_turnover_fraction=float(os.environ.get("QUANT_KR_MAX_TURNOVER_FRACTION", "0.50")),
)
REBALANCE_CONFIG = rebalance_config_from_env(
    "KR",
    default_portfolio_value=10_000_000.0,
    default_min_trade_value=50_000.0,
    default_fee_rate=0.00015,
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
    return {
        str(row[ticker_col]).strip()
        for row in rows[header_idx + 1 :]
        if len(row) > ticker_col and str(row[ticker_col]).strip()
    }

# ── Load macro regime ─────────────────────────────────────────────────────────
def _load_regime(ss) -> str:
    try:
        ws   = ss.worksheet('Macro_Regime')
        rows = ws.get_all_values()
        kv   = {r[0].strip(): r[1].strip() for r in rows if len(r) >= 2 and r[0].strip()}
        return kv.get('Regime', 'NEUTRAL')
    except Exception:
        return 'NEUTRAL'

# ── Risk-parity optimizer (identical to US) ───────────────────────────────────
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

# ── Hierarchical Risk Parity ─────────────────────────────────────────────────
def hrp_weights(cov: np.ndarray) -> np.ndarray:
    n   = len(cov)
    std = np.sqrt(np.diag(cov))
    std[std == 0] = 1e-9
    corr = cov / np.outer(std, std)
    corr = np.clip(corr, -1, 1)
    np.fill_diagonal(corr, 1.0)
    dist      = np.sqrt((1 - corr) / 2.0)
    condensed = squareform(dist, checks=False)
    link      = linkage(condensed, method='single')
    order     = list(leaves_list(link))

    def _cluster_var(items):
        sub = cov[np.ix_(items, items)]
        ivp = 1.0 / np.maximum(np.diag(sub), 1e-12)
        ivp /= ivp.sum()
        return float(ivp @ sub @ ivp)

    def _bisect(items):
        if len(items) == 1:
            return {items[0]: 1.0}
        mid  = len(items) // 2
        l, r = items[:mid], items[mid:]
        lv, rv = _cluster_var(l), _cluster_var(r)
        alpha  = 1 - lv / (lv + rv + 1e-12)
        wl = {k: alpha * v       for k, v in _bisect(l).items()}
        wr = {k: (1 - alpha) * v for k, v in _bisect(r).items()}
        return {**wl, **wr}

    raw = _bisect(order)
    w   = np.array([raw[i] for i in range(n)])
    w   = np.clip(w, MIN_W, MAX_W)
    return w / w.sum()

# ── Load KR_Scored_Stocks ────────────────────────────────────────────────────
print("[KR-OPT] Loading KR_Scored_Stocks...")
try:
    ss_sheet  = spreadsheet.worksheet("KR_Scored_Stocks")
    data      = ss_sheet.get_all_values()
    scored_df = pd.DataFrame(data[1:], columns=data[0])
    # Use Final_Score if present (written by 03_factor_scorer_kr.py after sector neutralization)
    # Fall back to Total_Score for backwards compatibility
    score_col = 'Final_Score' if 'Final_Score' in scored_df.columns and scored_df['Final_Score'].replace('', np.nan).notna().any() else 'Total_Score'
    scored_df[score_col] = pd.to_numeric(scored_df[score_col], errors='coerce')
    if 'MarketCap' in scored_df.columns:
        scored_df['MarketCap'] = pd.to_numeric(scored_df['MarketCap'], errors='coerce')
    scored_df = scored_df.sort_values(score_col, ascending=False, na_position='last')
    previous_holdings = _read_previous_holdings(spreadsheet, "KR_Final_Portfolio")
    selected_tickers = risk_controlled_selection(
        scored_df.set_index("Ticker", drop=False),
        target_n=TOP_N,
        market="KR",
        previous_holdings=previous_holdings,
        score_col=score_col,
        config=RISK_CONFIG,
    )
    ml_df     = scored_df.set_index("Ticker", drop=False).reindex(selected_tickers).dropna(subset=[score_col])
    tickers   = ml_df['Ticker'].tolist()
except Exception as e:
    print(f"[KR-OPT] KR_Scored_Stocks not found: {e}")
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

print(f"[KR-OPT] Optimizing {len(tickers)} stocks  (score column: {score_col})")

# ── Download 1 year of prices for covariance ──────────────────────────────────
if not ANALYZE_ONLY:
    print("[KR-OPT] Downloading KRX prices...")
    BATCH = 30   # smaller batch for KRX rate limits
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
        time.sleep(1.5)   # KRX rate limit buffer

    prices = pd.concat(price_frames, axis=1)
    prices = prices.loc[:, ~prices.columns.duplicated()]
    prices = prices.dropna(axis=1, thresh=int(len(prices) * 0.8))

    valid = [t for t in tickers if t in prices.columns]
    prices = prices[valid]
    print(f"[KR-OPT] Valid stocks for optimization: {len(valid)}")

    # ── Covariance matrix (Ledoit-Wolf shrinkage) ────────────────────────────
    # KRX data has fewer trading days than US (~245/yr) and KR stocks are more
    # correlated within sectors — shrinkage is especially beneficial here.
    returns = prices.pct_change().dropna()
    lw = LedoitWolf()
    lw.fit(returns)
    cov_matrix = lw.covariance_ * 252          # annualised shrunk covariance
    print(f"[KR-OPT] Ledoit-Wolf shrinkage coefficient: {lw.shrinkage_:.4f}  "
          f"(0=sample cov, 1=diagonal)")

    # ── Optimize: Risk-Parity + HRP blend ────────────────────────────────────
    print("[KR-OPT] Running risk-parity optimisation...")
    rp_w = risk_parity_weights(cov_matrix)

    print("[KR-OPT] Running Hierarchical Risk Parity...")
    hrp_w = hrp_weights(cov_matrix)

    weights = 0.6 * rp_w + 0.4 * hrp_w
    weights = np.clip(weights, MIN_W, MAX_W)
    weights /= weights.sum()
    meta_for_valid = scored_df.set_index("Ticker", drop=False).reindex(valid)
    weights = apply_weight_limits(
        pd.Series(weights, index=valid),
        meta_for_valid,
        market="KR",
        config=RISK_CONFIG,
    ).reindex(valid).to_numpy()
    print(f"[KR-OPT] Blended weights  (RP×0.6 + HRP×0.4)  "
          f"min={weights.min():.3f}  max={weights.max():.3f}")
else:
    # No price data available — fall back to equal weights
    valid   = tickers
    n       = len(valid)
    weights = np.array([1.0 / n] * n)
    cov_matrix = np.eye(n)
    returns    = pd.DataFrame(np.zeros((1, n)), columns=valid)
    meta_for_valid = scored_df.set_index("Ticker", drop=False).reindex(valid)
    weights = apply_weight_limits(
        pd.Series(weights, index=valid),
        meta_for_valid,
        market="KR",
        config=RISK_CONFIG,
    ).reindex(valid).to_numpy()
    print(f"[KR-OPT] ANALYZE-ONLY: equal weights applied ({n} stocks)")

# ── Standard PORTFOLIO_COLS schema ────────────────────────────────────────────
PORTFOLIO_COLS = [
    'Rank', 'Ticker', 'Name', 'Market', 'Sector', 'MarketCap',
    'Weight(%)', 'Current_Price', 'Return_1M', 'Total_Score', 'ROIC', 'RevGrowth',
    'GrossMargin', 'Expected_Return', 'Last_Updated',
]

from datetime import datetime

# ── Dynamic Position Sizing (KR) ─────────────────────────────────────────────
port_vol = np.sqrt(weights @ cov_matrix @ weights)

macro_regime = _load_regime(spreadsheet)
if ANALYZE_ONLY:
    vol_scalar = 1.0
    print("[KR-OPT] ANALYZE-ONLY: Dynamic Sizing skipped (no real covariance)")
else:
    vol_scalar = min(1.0, TARGET_VOL / max(port_vol, 1e-6))
    if macro_regime == 'RISK_OFF':
        vol_scalar = min(vol_scalar, 0.80)
        print(f"[KR-OPT] RISK_OFF regime: capping invested fraction at 80%")
cash_weight = round(1.0 - vol_scalar, 4)

weights_invested = weights * vol_scalar
limit_summary = risk_limit_summary(
    pd.Series(weights, index=valid),
    meta_for_valid,
    market="KR",
    config=RISK_CONFIG,
)
print(f"[KR-OPT] Dynamic Sizing: vol_scalar={vol_scalar:.2f}  cash={cash_weight:.1%}  "
      f"regime={macro_regime}")
print(f"[KR-OPT] Risk Limits: max_pos={limit_summary['max_position']:.1%}  "
      f"max_sector={limit_summary['max_sector']:.1%}  illiquid={limit_summary['illiquid_weight']:.1%}")

# ── Portfolio-level metrics ───────────────────────────────────────────────────
KR_RF_ANN       = 0.035   # KR 3M CD rate proxy; update if BOK rate changes materially
hist_ann        = returns[valid].mean() * 252
market_ret      = float(hist_ann.mean())
per_stock_er    = (0.7 * hist_ann + 0.3 * market_ret).round(4)
port_ret_equity = float(per_stock_er @ weights)
port_ret_avg    = port_ret_equity * vol_scalar + KR_RF_ANN * cash_weight
port_vol_total  = port_vol * vol_scalar
sharpe_est      = (port_ret_avg - KR_RF_ANN) / (port_vol_total + 1e-9)

result = pd.DataFrame({
    'Ticker':    valid,
    'Weight(%)': weights_invested.round(4),   # fraction of total capital (cash excluded)
    'Total_Score': [round(float(score_map.get(t, 0)), 4) for t in valid],
    'Current_Price': [round(float(prices[t].iloc[-1]), 2) if not ANALYZE_ONLY else '' for t in valid],
    'Return_1M': [
        round(float((prices[t].iloc[-1] / prices[t].dropna().iloc[-22]) - 1), 4)
        if not ANALYZE_ONLY and t in prices.columns and len(prices[t].dropna()) >= 22 else ''
        for t in valid
    ],
})
result = result.sort_values('Weight(%)', ascending=False).reset_index(drop=True)
result['Rank']            = result.index + 1
result['Market']          = 'KR'
result['Name']            = result['Ticker'].map(lambda t: meta_map.get(t, {}).get('Name', ''))
result['Sector']          = result['Ticker'].map(lambda t: meta_map.get(t, {}).get('Sector', ''))
result['MarketCap']       = result['Ticker'].map(lambda t: meta_map.get(t, {}).get('MarketCap', ''))
result['ROIC']            = result['Ticker'].map(lambda t: meta_map.get(t, {}).get('ROIC', ''))
result['RevGrowth']       = result['Ticker'].map(lambda t: meta_map.get(t, {}).get('RevGrowth', ''))
result['GrossMargin']     = result['Ticker'].map(lambda t: meta_map.get(t, {}).get('GrossMargin', ''))
result['Expected_Return'] = result['Ticker'].map(per_stock_er).round(4)  # 종목별 개별값
result['Last_Updated']    = datetime.now().strftime('%Y-%m-%d')

print(f"\n[KR-OPT] ── Portfolio Summary ───────────────────────")
print(f"  Stocks              : {len(valid)}")
print(f"  Ann. Volatility     : {port_vol_total:.2%}  (equity sleeve: {port_vol:.2%})")
print(f"  Est. Ann. Return    : {port_ret_avg:.2%}")
print(f"  Est. Sharpe         : {sharpe_est:.2f}")
print(f"  Invested Fraction   : {vol_scalar:.1%}  (cash={cash_weight:.1%})")
print(f"  Max Sector Weight   : {limit_summary['max_sector']:.1%}")
print(f"  Illiquid Weight     : {limit_summary['illiquid_weight']:.1%}")
print(f"  Macro Regime        : {macro_regime}")
print(f"\n  Top 10 holdings:")
for _, row in result.head(10).iterrows():
    price_str = f"  ₩{row['Current_Price']}" if not ANALYZE_ONLY else ''
    print(f"    {row['Ticker']:14s}  {float(row['Weight(%)']) * 100:5.1f}%  "
          f"score={row['Total_Score']:.4f}{price_str}")

# ── Save to KR_Final_Portfolio sheet ─────────────────────────────────────────
summary = [
    ["── KR Final Portfolio ──",    ""],
    ["Strategy",                    "RP×0.6+HRP×0.4 (Ledoit-Wolf) + Factor Scoring + risk caps + turnover buffer"],
    ["Number of Stocks",            str(len(valid))],
    ["Ann. Volatility (est.)",      f"{port_vol_total:.4f}"],
    ["Ann. Return (hist. est.)",    f"{port_ret_avg:.4f}"],
    ["Sharpe (est.)",               f"{sharpe_est:.4f}  (vs KR rf={KR_RF_ANN:.2%})"],
    ["Macro Regime",                macro_regime],
    ["Invested Fraction",           f"{vol_scalar:.4f}"],
    ["Cash Weight",                 f"{cash_weight:.4f}"],
    ["Max Weight",                  f"{weights_invested.max():.4f}"],
    ["Min Weight",                  f"{weights_invested.min():.4f}"],
    ["Max Sector Weight",           f"{limit_summary['max_sector'] * vol_scalar:.4f}"],
    ["Illiquid Weight",             f"{limit_summary['illiquid_weight'] * vol_scalar:.4f}"],
    ["Turnover Buffer",             f"keep until rank <= {RISK_CONFIG.sell_rank_multiplier:.1f}x TopN; max new names {RISK_CONFIG.max_turnover_fraction:.0%}"],
    ["Generated",                   pd.Timestamp.now().strftime('%Y-%m-%d %H:%M')],
    ["", ""],
]

# ── Apply standard schema ─────────────────────────────────────────────────────
for col in PORTFOLIO_COLS:
    if col not in result.columns:
        result[col] = ''
hold_df = result[PORTFOLIO_COLS].copy()
sheet_hold_df = hold_df.fillna('').astype(str)

risk_summary, risk_holdings, risk_sectors = build_risk_report(
    market="KR",
    tickers=valid,
    weights=pd.Series(weights_invested, index=valid),
    cov_matrix=cov_matrix,
    metadata=result.set_index("Ticker", drop=False),
    target_vol=TARGET_VOL,
    invested_fraction=vol_scalar,
    cash_weight=cash_weight,
    config=RiskReportConfig(max_position_warn=RISK_CONFIG.max_position_weight),
)

current_holdings_df = read_current_holdings_sheet(spreadsheet, "KR")
previous_portfolio_df = read_previous_portfolio_sheet(spreadsheet, "KR_Final_Portfolio")
rebalance_summary, rebalance_orders = build_rebalance_report(
    market="KR",
    target_portfolio=result,
    current_holdings=current_holdings_df,
    previous_portfolio=previous_portfolio_df,
    config=REBALANCE_CONFIG,
)

try:
    port_sheet = spreadsheet.worksheet("KR_Final_Portfolio")
except Exception:
    port_sheet = spreadsheet.add_worksheet(title="KR_Final_Portfolio", rows=200, cols=len(PORTFOLIO_COLS))

rows = (summary
        + [sheet_hold_df.columns.tolist()]
        + sheet_hold_df.values.tolist())

port_sheet.clear()
port_sheet.update(range_name='A1', values=rows, value_input_option='USER_ENTERED')
dual_write_dataframe("KR_Final_Portfolio", hold_df, market="KR")

write_risk_report_sheet(
    spreadsheet,
    "KR_Final_Portfolio_Risk",
    risk_summary,
    risk_holdings,
    risk_sectors,
)
dual_write_dataframe("KR_Final_Portfolio_Risk_Summary", risk_summary, market="KR")
dual_write_dataframe("KR_Final_Portfolio_Risk", risk_holdings, market="KR")
dual_write_dataframe("KR_Final_Portfolio_Risk_Sectors", risk_sectors, market="KR")

write_rebalance_report_sheet(
    spreadsheet,
    "KR_Rebalance_Execution",
    rebalance_summary,
    rebalance_orders,
)
dual_write_dataframe("KR_Rebalance_Execution_Summary", rebalance_summary, market="KR")
dual_write_dataframe("KR_Rebalance_Execution", rebalance_orders, market="KR")

print(f"\n✅ [KR-OPT] Final portfolio saved to KR_Final_Portfolio sheet")
print("✅ [KR-OPT] Risk report saved to KR_Final_Portfolio_Risk sheet")
print("✅ [KR-OPT] Rebalance execution report saved to KR_Rebalance_Execution sheet")
