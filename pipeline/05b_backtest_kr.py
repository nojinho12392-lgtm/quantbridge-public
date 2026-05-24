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
import time
import warnings
from itertools import product
from pipeline.backtest.kr_pit_financials import asof_pit_features, fetch_kr_pit_financials
from pipeline.backtest.kr_market_data import asof_krx_liquidity, load_krx_liquidity_history
from pipeline.backtest.costs import liquidity_slippage_rate, weighted_turnover_costs
from pipeline.portfolio.risk_controls import (
    PortfolioRiskConfig,
    estimate_portfolio_volatility,
    period_cash_return,
    risk_controlled_selection,
    volatility_target_scalar,
)
from pipeline.scoring.kr_factor_scorer import compute_kr_factor_scores
from quantbridge.writers.dual_write import dual_write_dataframe
warnings.filterwarnings('ignore')

# ── Google Sheets connection ──────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()

# ── Multi-Config Grid ─────────────────────────────────────────────────────────
REBALANCE_FREQS  = ['ME', 'W']       # Monthly-End, Weekly
LOOKBACK_OPTIONS = [21, 63, 126]     # 1M, 3M, 6M trading days
TOP_N_OPTIONS    = [10, 20, 30]

FORWARD_DAYS = 63                    # 3-month forward window for walk-forward R²
PIT_MAX_TICKERS = int(os.environ.get("KR_PIT_MAX_TICKERS", "120"))
MIN_TRADING_VALUE_20D = float(os.environ.get("KR_MIN_TRADING_VALUE_20D", "200000000"))
TARGET_VOL = float(os.environ.get("QUANT_KR_TARGET_VOL", "0.14"))
RISK_CONFIG = PortfolioRiskConfig(
    max_position_weight=float(os.environ.get("QUANT_KR_MAX_POSITION_WEIGHT", "0.10")),
    max_sector_weight=float(os.environ.get("QUANT_KR_MAX_SECTOR_WEIGHT", "0.30")),
    max_illiquid_weight=float(os.environ.get("QUANT_KR_MAX_ILLIQUID_WEIGHT", "0.20")),
    max_turnover_fraction=float(os.environ.get("QUANT_KR_MAX_TURNOVER_FRACTION", "0.50")),
)

# ── Transaction fee (Toss Securities, one-way) ────────────────────────────────
# KR stock: 0.015% per trade (buy or sell)
FEE = 0.00015

# ── KR Risk-free rate ────────────────────────────────────────────────────────
# Korean 3M CD rate / BOK base rate proxy — not directly on yfinance.
# Use a fixed conservative estimate; update manually if BOK rate changes.
KR_RF_ANN = 0.035   # 3.5% (approximate KR short-term rate as of 2026)

# ── KOSPI benchmark ───────────────────────────────────────────────────────────
print("[KR-BT] Fetching KOSPI benchmark (^KS11)...")
kospi_prices_full = None
try:
    kospi_raw = yf.download('^KS11', period='10y', auto_adjust=True, progress=False)
    kospi_col = kospi_raw['Close'] if 'Close' in kospi_raw.columns else kospi_raw.iloc[:, 0]
    kospi_prices_full = kospi_col.squeeze()
    print(f"  ^KS11 (KOSPI): {len(kospi_prices_full)} days loaded")
except Exception as e:
    print(f"  ⚠️  ^KS11 unavailable: {e}")

# ── Load universe from KR_Universe ───────────────────────────────────────────
print("[KR-BT] Loading PIT universe from KR_Universe...")
try:
    ws   = spreadsheet.worksheet("KR_Universe")
    data = ws.get_all_values()
    df   = pd.DataFrame(data[1:], columns=data[0])
    for col in ["MarketCap", "PER", "PBR", "ROE", "Revenue", "RevenueGrowth", "OperatingMargin", "DebtToEquity"]:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    df = df[df["Ticker"].astype(str).str.endswith((".KS", ".KQ"))].copy()
    df = df[df["MarketCap"].fillna(0) > 5e10].sort_values("MarketCap", ascending=False)
    tickers = df["Ticker"].dropna().astype(str).head(PIT_MAX_TICKERS).tolist()
    market_cap_map = df.set_index("Ticker")["MarketCap"].to_dict() if "MarketCap" in df.columns else {}
    sector_map = df.set_index("Ticker")["Sector"].to_dict() if "Sector" in df.columns else {}
    print(f"[KR-BT] Loaded {len(tickers)} PIT candidates from KR_Universe (max={PIT_MAX_TICKERS})")
except Exception as e:
    print(f"[KR-BT] Fatal: {e}")
    exit(1)

# ── Download 10 years of daily prices (batched) ───────────────────────────────
print("[KR-BT] Downloading 10 years of prices...")
BATCH = 30   # smaller batch for KR to reduce rate-limit risk
price_frames = []
for i in range(0, len(tickers), BATCH):
    batch = tickers[i:i + BATCH]
    try:
        raw = yf.download(batch, period="10y", auto_adjust=True, progress=False)
        closes = raw['Close'] if isinstance(raw.columns, pd.MultiIndex) else raw
        if isinstance(closes, pd.Series):
            closes = closes.to_frame(name=batch[0])
        price_frames.append(closes)
        print(f"  batch {i // BATCH + 1}/{(len(tickers) - 1) // BATCH + 1} done "
              f"({closes.shape[1]} stocks)")
    except Exception as e:
        print(f"  batch {i} error: {e}")
    time.sleep(1.5)   # slightly longer delay for KRX data

if not price_frames:
    print("[KR-BT] No price data. Exiting.")
    exit(1)

prices = pd.concat(price_frames, axis=1)
prices = prices.loc[:, ~prices.columns.duplicated()]
# Fill forward: carry last known price through trading halts / missing days.
# KR stocks are especially prone to short-term halts (관리종목, 거래정지).
prices = prices.ffill()
# Drop tickers that are still >40% empty after ffill (e.g. recently listed or delisted).
prices = prices.dropna(axis=1, thresh=int(len(prices) * 0.6))
print(f"[KR-BT] Price data: {prices.shape[0]} days × {prices.shape[1]} stocks  "
      f"({prices.index[0].date()} → {prices.index[-1].date()})")


# ── Vectorised momentum precomputation ───────────────────────────────────────
# Build z-scored momentum matrices for every lookback window — once, upfront.
# Each of the 18 configs indexes into this cache instead of re-slicing the
# full price DataFrame O(n_rebal_dates) times per config.
WF_LOOKBACK = 63
WF_TOP_N    = 20

print("[KR-BT] Precomputing momentum matrices...")
mom_cache: dict = {}
for lb in set(LOOKBACK_OPTIONS) | {WF_LOOKBACK}:
    raw  = prices / prices.shift(lb) - 1
    mean = raw.mean(axis=1)
    std  = raw.std(axis=1).replace(0, np.nan)
    mom_cache[lb] = raw.sub(mean, axis=0).div(std, axis=0)

# Precompute rebalanced price series for each frequency (shared across all configs)
rebal_cache: dict = {freq: prices.resample(freq).last() for freq in REBALANCE_FREQS}
print(f"[KR-BT] Cached {len(mom_cache)} momentum windows × {len(rebal_cache)} rebal frequencies")

# ── KRX point-in-time liquidity / market-cap data ────────────────────────────
print("\n[KR-BT] Loading pykrx historical liquidity/market-cap data...")
krx_liquidity = load_krx_liquidity_history(tickers, prices.index[0], prices.index[-1])
if krx_liquidity:
    print(f"[KR-BT] KRX liquidity loaded for {len(krx_liquidity)} tickers")
else:
    print("[KR-BT] ⚠️  pykrx liquidity unavailable — using KR_Universe market-cap fallback")

# ── DART PIT fundamentals ────────────────────────────────────────────────────
print("\n[KR-BT] Loading OpenDART PIT annual fundamentals...")
pit_years = list(range(prices.index[0].year - 1, prices.index[-1].year))
pit_financials = fetch_kr_pit_financials(tickers, pit_years)
if pit_financials.empty:
    print("[KR-BT] ⚠️  No PIT fundamentals available. Backtest will run with momentum-only ranks.")
else:
    print(f"[KR-BT] PIT fundamentals: {len(pit_financials)} ticker-year rows "
          f"({pit_financials['Ticker'].nunique()} tickers)")

mom_3m_matrix = prices / prices.shift(63) - 1
vol_63d_matrix = prices.pct_change().rolling(63).std() * np.sqrt(252)
dist_52w_matrix = prices / prices.rolling(252, min_periods=120).max() - 1

# ── Walk-Forward Validation (vectorised) ─────────────────────────────────────
print("\n[KR-BT] ── Walk-Forward Validation ─────────────────────────────────────")

monthly_dates = prices.resample('ME').last().index
wf_periods    = max(0, len(monthly_dates) - 3)

# Forward return matrix: actual return over the next FORWARD_DAYS trading days.
# shift(-FORWARD_DAYS) is intentional — we want real future outcomes to measure
# signal quality (validation only, not trading decisions).
fwd_ret_matrix = prices.shift(-FORWARD_DAYS) / prices - 1

# Align signal and outcome at monthly dates; drop last 3 months (no future data yet)
mom_monthly = mom_cache[WF_LOOKBACK].reindex(monthly_dates, method='ffill').iloc[:-3]
fwd_monthly = fwd_ret_matrix.reindex(monthly_dates, method='ffill').iloc[:-3]

pred_flat = mom_monthly.to_numpy().flatten()
act_flat  = fwd_monthly.to_numpy().flatten()
mask      = ~(np.isnan(pred_flat) | np.isnan(act_flat))
pred_arr  = pred_flat[mask]
act_arr   = act_flat[mask]

if len(pred_arr) >= 10:
    corr       = np.corrcoef(pred_arr, act_arr)[0, 1]
    wf_r2      = corr ** 2
    wf_avg_fwd = float(np.mean(act_arr))
    print(f"  Walk-forward months   : {wf_periods}")
    print(f"  Stock-period pairs    : {len(pred_arr)}")
    print(f"  Avg 3M forward return : {wf_avg_fwd:.2%}")
    print(f"  Momentum → Fwd Corr   : {corr:.4f}")
    print(f"  Prediction R²         : {wf_r2:.4f}")
else:
    wf_r2, wf_periods, wf_avg_fwd = float('nan'), 0, float('nan')
    print("  Not enough data for R² computation")


# ── Single-config backtest ────────────────────────────────────────────────────
def run_config(freq, lookback_days, top_n, fee=FEE):
    """
    Backtest one parameter combination using PIT DART fundamentals plus
    precomputed price momentum. Financial statement rows are selected with
    Available_Date <= rebalance date, so future fundamentals are not visible.
    Transaction fee (one-way) applied only on actual turnover.
    """
    rebal_prices = rebal_cache[freq]
    rebal_dates  = rebal_prices.index

    rets, dates        = [], []
    prev_holdings: set = set()
    prev_weights: dict = {}
    ic_rows: list = []
    invested_fractions: list = []
    vol_estimates: list = []

    for i in range(len(rebal_dates) - 1):
        rebal_date = rebal_dates[i]
        pit = asof_pit_features(pit_financials, rebal_date)
        if pit.empty:
            pit = pd.DataFrame(index=prices.columns)

        mom_12m_1m = mom_cache[lookback_days].reindex([rebal_date], method='ffill')
        mom_3m = mom_3m_matrix.reindex([rebal_date], method='ffill')
        vol_63d = vol_63d_matrix.reindex([rebal_date], method='ffill')
        dist_52w = dist_52w_matrix.reindex([rebal_date], method='ffill')
        mom_series = mom_12m_1m.iloc[0] if not mom_12m_1m.empty else pd.Series(dtype=float)

        features = pit.reindex(prices.columns).copy()
        features["MarketCap"] = pd.Series(market_cap_map).reindex(features.index)
        features["Sector"] = pd.Series(sector_map).reindex(features.index)
        liq = asof_krx_liquidity(krx_liquidity, rebal_date)
        if not liq.empty:
            features = features.join(liq, how="left")
            features["MarketCap"] = features["MarketCap_PIT"].combine_first(features["MarketCap"])
        if not mom_3m.empty:
            features["Mom_3M"] = mom_3m.iloc[0].reindex(features.index)
        if not vol_63d.empty:
            features["Volatility_63D"] = vol_63d.iloc[0].reindex(features.index)
        if not dist_52w.empty:
            features["Dist_52W_High"] = dist_52w.iloc[0].reindex(features.index)
        if "TradingValue_20D" in features.columns:
            tradable = features["TradingValue_20D"].isna() | (features["TradingValue_20D"] >= MIN_TRADING_VALUE_20D)
            features = features[tradable]

        scored = compute_kr_factor_scores(features, mom_series=mom_series).sort_values("Total_Score", ascending=False)
        scored = scored.dropna(subset=["Total_Score"])
        if len(scored) < top_n:
            continue

        top = risk_controlled_selection(
            scored,
            target_n=top_n,
            market="KR",
            previous_holdings=prev_holdings,
            score_col="Total_Score",
            config=RISK_CONFIG,
        )
        curr_px = rebal_prices.iloc[i]
        next_px = rebal_prices.iloc[i + 1]

        valid = [t for t in top
                 if not pd.isna(curr_px.get(t)) and not pd.isna(next_px.get(t))]
        if not valid:
            continue

        sleeve_weights = pd.Series(1.0 / len(valid), index=valid)
        equity_ret = float((next_px[valid] / curr_px[valid] - 1).mean())
        vol_est = estimate_portfolio_volatility(
            prices[valid].loc[:rebal_date].pct_change().dropna().tail(252),
            sleeve_weights,
        )
        invested_fraction = volatility_target_scalar(vol_est, target_vol=TARGET_VOL)
        cash_weight = 1.0 - invested_fraction
        cash_ret = period_cash_return(KR_RF_ANN, rebal_date, rebal_dates[i + 1])
        gross_ret = equity_ret * invested_fraction + cash_ret * cash_weight

        valid_set = set(valid)
        curr_weights = {ticker: float(weight * invested_fraction) for ticker, weight in sleeve_weights.items()}
        traded    = set(curr_weights) | set(prev_weights)
        slippage_rates = {}
        for ticker in traded:
            row = features.loc[ticker] if ticker in features.index else pd.Series(dtype=float)
            slippage_rates[ticker] = liquidity_slippage_rate(
                market="KR",
                market_cap=row.get("MarketCap", market_cap_map.get(ticker)),
                trading_value_20d=row.get("TradingValue_20D"),
            )
        costs = weighted_turnover_costs(
            previous_weights=prev_weights,
            current_weights=curr_weights,
            fee_rate=fee,
            slippage_rates=slippage_rates,
        )

        rets.append(gross_ret - costs["total"])
        dates.append(rebal_dates[i + 1])
        invested_fractions.append(invested_fraction)
        vol_estimates.append(vol_est if vol_est is not None else np.nan)

        fwd_returns = (next_px / curr_px - 1).replace([np.inf, -np.inf], np.nan)
        aligned = pd.concat([
            scored["Total_Score"].rename("score"),
            fwd_returns.rename("return"),
        ], axis=1).dropna()
        if len(aligned) >= 20 and aligned["score"].nunique() > 1 and aligned["return"].nunique() > 1:
            ic_rows.append({
                "Rebal_Date": rebal_date.strftime("%Y-%m-%d"),
                "Forward_End": rebal_dates[i + 1].strftime("%Y-%m-%d"),
                "Freq": freq,
                "Lookback": lookback_days,
                "TopN": top_n,
                "Factor": "Total_Score",
                "IC": float(aligned["score"].corr(aligned["return"], method="spearman")),
                "N": len(aligned),
            })
        prev_holdings = valid_set
        prev_weights = curr_weights

    if len(rets) < 4:
        return None

    periods_per_year = 52 if freq == 'W' else 12
    ret_s  = pd.Series(rets, index=pd.DatetimeIndex(dates))
    cumret = (1 + ret_s).cumprod()
    dd     = (cumret / cumret.cummax()) - 1
    n      = len(ret_s)
    rf_per_period = KR_RF_ANN / periods_per_year

    return {
        'freq': freq, 'lookback': lookback_days, 'top_n': top_n,
        'n_periods': n,
        'period_start': dates[0], 'period_end': dates[-1],
        'cagr':      (1 + ret_s).prod() ** (periods_per_year / n) - 1,
        'sharpe':    (ret_s.mean() - rf_per_period) / (ret_s.std() + 1e-9) * np.sqrt(periods_per_year),
        'max_dd':    dd.min(),
        'total_ret': cumret.iloc[-1] - 1,
        'win_rate':  (ret_s > 0).mean(),
        'avg_ret':   ret_s.mean(),
        'best':      ret_s.max(),
        'worst':     ret_s.min(),
        'avg_invested': pd.Series(invested_fractions, dtype=float).mean(),
        'avg_vol_est': pd.Series(vol_estimates, dtype=float).dropna().mean(),
        'ic_rows':    ic_rows,
        'ret_series': ret_s,
        'cumret':     cumret,
        'drawdown':   dd,
    }


# ── Run all configs ───────────────────────────────────────────────────────────
print("\n[KR-BT] ── Multi-Config Backtest ───────────────────────────────────────")
all_combos = list(product(REBALANCE_FREQS, LOOKBACK_OPTIONS, TOP_N_OPTIONS))
config_results = []

for ci, (freq, lookback, top_n) in enumerate(all_combos, 1):
    label = f"{freq} | LB={lookback:>3}d | Top{top_n}"
    print(f"  [{ci:>2}/{len(all_combos)}] {label} ...", end=' ', flush=True)
    result = run_config(freq, lookback, top_n)
    if result:
        config_results.append(result)
        print(f"CAGR={result['cagr']:+.1%}  Sharpe={result['sharpe']:.2f}  "
              f"MaxDD={result['max_dd']:.1%}  WinRate={result['win_rate']:.1%}")
    else:
        print("insufficient data")

if not config_results:
    print("[KR-BT] No valid configs. Exiting.")
    exit(1)

# ── Best config by Sharpe ratio ───────────────────────────────────────────────
best = max(config_results, key=lambda x: x['sharpe'])
print(f"\n[KR-BT] Best config (Sharpe={best['sharpe']:.2f}): "
      f"freq={best['freq']} | lookback={best['lookback']}d | top_n={best['top_n']}")

# ── KOSPI benchmark comparison ────────────────────────────────────────────────
kospi_cagr_val   = None
kospi_sharpe_val = None
alpha_val        = None

if kospi_prices_full is not None:
    try:
        period_start = best['period_start']
        period_end   = best['period_end']
        kospi_slice  = kospi_prices_full.loc[str(period_start):str(period_end)].dropna()
        if len(kospi_slice) >= 20:
            kospi_dr       = kospi_slice.pct_change().dropna()
            kospi_vol_val  = kospi_dr.std() * np.sqrt(252)
            n_years        = (kospi_slice.index[-1] - kospi_slice.index[0]).days / 365.25
            kospi_cagr_val = (kospi_slice.iloc[-1] / kospi_slice.iloc[0]) ** (1 / n_years) - 1
            kospi_sharpe_val = (kospi_dr.mean() * 252 - KR_RF_ANN) / (kospi_vol_val + 1e-9)
            alpha_val      = best['cagr'] - kospi_cagr_val
            print(f"\n[KR-BT] ── vs KOSPI Benchmark ───────────────────────────────────────────")
            print(f"  KOSPI CAGR   : {kospi_cagr_val:+.2%}")
            print(f"  KOSPI Sharpe : {kospi_sharpe_val:.2f}")
            print(f"  Alpha        : {alpha_val:+.2%}")
    except Exception as e:
        print(f"  ⚠️  KOSPI benchmark comparison failed: {e}")

# ── Build Google Sheets output ────────────────────────────────────────────────

# Section 1 — Walk-Forward Validation
wf_block = [
    ["── Walk-Forward Validation (KR) ──", ""],
    ["Lookback (days)",       str(WF_LOOKBACK)],
    ["Top-N",                 str(WF_TOP_N)],
    ["Forward Window (days)", str(FORWARD_DAYS)],
    ["WF Monthly Periods",    str(wf_periods)],
    ["Stock-Period Pairs",    str(len(pred_arr))],
    ["Avg 3M Forward Return", f"{wf_avg_fwd:.4f}" if not np.isnan(wf_avg_fwd) else "N/A"],
    ["Prediction R²",         f"{wf_r2:.4f}"      if not np.isnan(wf_r2)      else "N/A"],
    ["", ""],
]

# Section 2 — Config Comparison Table (sorted by Sharpe desc)
cmp_header = ["Rank", "Freq", "Lookback", "TopN", "CAGR", "Sharpe", "MaxDD", "WinRate",
              "TotalRet", "AvgPeriodRet", "AvgInvested", "Periods"]
cmp_rows = []
for rank, r in enumerate(sorted(config_results, key=lambda x: -x['sharpe']), 1):
    cmp_rows.append([
        str(rank), r['freq'], str(r['lookback']), str(r['top_n']),
        f"{r['cagr']:.4f}", f"{r['sharpe']:.4f}", f"{r['max_dd']:.4f}",
        f"{r['win_rate']:.4f}", f"{r['total_ret']:.4f}",
        f"{r['avg_ret']:.4f}", f"{r.get('avg_invested', float('nan')):.4f}", str(r['n_periods']),
    ])

# Section 3 — Best-config summary
bc = best
best_ic_df = pd.DataFrame(bc.get('ic_rows', []))
mean_ic = best_ic_df['IC'].mean() if not best_ic_df.empty else np.nan
summary_block = [
    ["── Best PIT Config Summary (KR) ──", ""],
    ["Period Start",    bc['period_start'].strftime('%Y-%m-%d')],
    ["Period End",      bc['period_end'].strftime('%Y-%m-%d')],
    ["Periods",         str(bc['n_periods'])],
    ["Rebal Freq",      bc['freq']],
    ["Lookback (days)", str(bc['lookback'])],
    ["Top-N",           str(bc['top_n'])],
    ["CAGR",            f"{bc['cagr']:.4f}"],
    ["Sharpe Ratio",    f"{bc['sharpe']:.4f}"],
    ["Max Drawdown",    f"{bc['max_dd']:.4f}"],
    ["Total Return",    f"{bc['total_ret']:.4f}"],
    ["Win Rate",        f"{bc['win_rate']:.4f}"],
    ["Avg Period Ret",  f"{bc['avg_ret']:.4f}"],
    ["Best Period",     f"{bc['best']:.4f}"],
    ["Worst Period",    f"{bc['worst']:.4f}"],
    ["Walk-Fwd R²",     f"{wf_r2:.4f}" if not np.isnan(wf_r2) else "N/A"],
    ["Mean PIT Factor IC", f"{mean_ic:.4f}" if not np.isnan(mean_ic) else "N/A"],
    ["PIT Fundamentals", f"{len(pit_financials)} DART ticker-year rows"],
    ["Risk-Free Rate",  f"{KR_RF_ANN:.2%}  (KR 3M CD proxy — update if BOK rate changes)"],
    ["Vol Target",      f"Target {TARGET_VOL:.2%}; avg invested {bc.get('avg_invested', float('nan')):.4f}"],
    ["Avg Port Vol Est", f"{bc.get('avg_vol_est', float('nan')):.4f}"],
    ["── KOSPI Benchmark ──", ""],
    ["KOSPI CAGR",      f"{kospi_cagr_val:.4f}"   if kospi_cagr_val   is not None else "N/A"],
    ["KOSPI Sharpe",    f"{kospi_sharpe_val:.4f}" if kospi_sharpe_val is not None else "N/A"],
    ["Alpha vs KOSPI",  f"{alpha_val:.4f}"         if alpha_val         is not None else "N/A"],
    ["Strategy",        f"Top {bc['top_n']} KR by PIT DART factor score + {bc['lookback']}d momentum · {bc['freq']} Rebal · Equal Weight · Fee={FEE:.4%}/trade + liquidity slippage"],
    ["Risk Controls",   f"Max position {RISK_CONFIG.max_position_weight:.0%}, sector {RISK_CONFIG.max_sector_weight:.0%}, "
                        f"illiquid {RISK_CONFIG.max_illiquid_weight:.0%}, max new names {RISK_CONFIG.max_turnover_fraction:.0%}"],
    ["", ""],
]

# Section 4 — Best-config period returns
ret_s = bc['ret_series']
cum_s = bc['cumret']
dd_s  = bc['drawdown']
period_tbl = pd.DataFrame({
    "Date":           ret_s.index.strftime('%Y-%m-%d'),
    "Return":         ret_s.values.round(4),
    "Cumulative_Ret": cum_s.values.round(4),
    "Drawdown":       dd_s.values.round(4),
})

rows = (
    wf_block
    + [cmp_header]
    + cmp_rows
    + [["", ""]]
    + summary_block
    + [period_tbl.columns.tolist()]
    + [[str(v) for v in r] for r in period_tbl.values.tolist()]
    + [["", ""]]
    + [["── PIT Factor IC Detail ──", "", "", "", "", "", "", ""]]
    + ([best_ic_df.columns.tolist()] if not best_ic_df.empty else [["Status"]])
    + ([[str(v) for v in r] for r in best_ic_df.round(4).values.tolist()] if not best_ic_df.empty else [["No IC rows"]])
)

# ── Save to KR_Backtest_Results sheet ─────────────────────────────────────────
print("\n[KR-BT] Saving to Google Sheets...")
try:
    bt_sheet = spreadsheet.worksheet("KR_Backtest_Results")
except Exception:
    bt_sheet = spreadsheet.add_worksheet(title="KR_Backtest_Results", rows=1200, cols=12)

bt_sheet.clear()
bt_sheet.update(rows)
dual_write_dataframe("KR_Backtest_Results", period_tbl, market="KR")
if cmp_rows:
    dual_write_dataframe(
        "KR_Backtest_Config_Comparison",
        pd.DataFrame(cmp_rows, columns=cmp_header),
        market="KR",
    )
if not best_ic_df.empty:
    dual_write_dataframe("KR_Backtest_Factor_IC", best_ic_df, market="KR")

print(f"✅ [KR-BT] Saved {len(rows)} rows to KR_Backtest_Results sheet")
print(f"   Best : {bc['freq']} | LB={bc['lookback']}d | Top{bc['top_n']}")
print(f"   CAGR={bc['cagr']:.2%}  Sharpe={bc['sharpe']:.2f}  "
      f"MaxDD={bc['max_dd']:.2%}  WinRate={bc['win_rate']:.2%}")
print(f"   Walk-Forward R² = {wf_r2:.4f}" if not np.isnan(wf_r2) else "   Walk-Forward R² = N/A")
