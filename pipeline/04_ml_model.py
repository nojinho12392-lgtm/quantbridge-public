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
from sklearn.ensemble import RandomForestRegressor
from scipy.stats import spearmanr
import time
import warnings
warnings.filterwarnings('ignore')
from quantbridge.config import get_settings
from quantbridge.ticker_policy import banned_tickers_label, drop_banned_ticker_rows
from quantbridge.writers.dual_write import dual_write_dataframe

try:
    from lightgbm import LGBMRegressor
    _LGBM_AVAILABLE = True
except ImportError:
    _LGBM_AVAILABLE = False
    print("[ML] LightGBM not installed — RF-only mode (pip install lightgbm)")

TEST_MODE    = os.environ.get('QUANT_TEST_MODE')    == 'true'
ANALYZE_ONLY = os.environ.get('QUANT_ANALYZE_ONLY') == 'true'
MIN_COMMON   = 2 if TEST_MODE else 5
TEST_LIMIT   = 10   # quarterly fetch cap in test mode
SETTINGS     = get_settings()

# ── Look-ahead bias fix ───────────────────────────────────────────────────────
# US large-caps typically publish earnings within 45 days of quarter end.
# For rebal_date = 2025-01-31:
#   cutoff = 2024-12-17
#   Q3-2024 (ends 2024-09-30) ✓  available
#   Q4-2024 (ends 2024-12-31) ✗  NOT available → excluded
EARNINGS_LAG_DAYS = 45

if TEST_MODE:
    print("\n⚠️  TEST MODE : ML min_common threshold lowered to 2")
if ANALYZE_ONLY:
    print("\n⚡ ANALYZE-ONLY : ML model requires 2yr price history — skipping.")
    print("   Portfolio optimizer (06) will use Combined_Score already in US_Scored_Stocks.")
    sys.exit(0)

# ── Google Sheets connection ──────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()

# ── Feature definitions ───────────────────────────────────────────────────────
PRICE_FEATS = ["mom_12m_1m", "vol_21d", "rsi_14", "high52w"]
FUND_FEATS  = ["roic", "fcf_margin", "eps_growth", "peg"]
ALL_FEATS   = PRICE_FEATS + FUND_FEATS

# ── Blend policy ─────────────────────────────────────────────────────────────
def select_ml_weight(rank_ic: float) -> tuple[float, str]:
    """Choose the ML overlay weight from holdout Rank IC."""
    base = float(SETTINGS.ml_blend_weight)
    if not SETTINGS.ml_auto_weight:
        return base, "configured"
    if not np.isfinite(rank_ic):
        return base, "rank_ic_unavailable"
    if rank_ic <= 0:
        return 0.0, "rank_ic_non_positive"
    if rank_ic < 0.02:
        return min(base, float(SETTINGS.ml_blend_weak_weight)), "rank_ic_weak"
    if rank_ic < 0.05:
        return base, "rank_ic_moderate"
    return max(base, float(SETTINGS.ml_blend_strong_weight)), "rank_ic_strong"

def _first_available_score_col(df: pd.DataFrame) -> str | None:
    """Prefer sector-neutral Final_Score for the factor leg, then fall back."""
    preferred = [
        SETTINGS.ml_factor_score_col,
        "Final_Score",
        "Total_Score",
    ]
    for col in dict.fromkeys(preferred):
        if col in df.columns and pd.to_numeric(df[col], errors="coerce").notna().any():
            return col
    return None

# ── Price feature helpers ─────────────────────────────────────────────────────
def compute_rsi(series, period=14):
    delta = series.diff()
    gain  = delta.clip(lower=0).rolling(period).mean()
    loss  = (-delta.clip(upper=0)).rolling(period).mean()
    rs    = gain / (loss + 1e-9)
    return 100 - 100 / (1 + rs)

def calc_price_features(prices, end_idx):
    """Cross-sectional rank_pct of price-based features at a given bar index."""
    raw = {}
    window = prices.iloc[:end_idx + 1]
    for ticker in window.columns:
        s = window[ticker].dropna()
        if len(s) < 252:
            continue
        curr    = s.iloc[-1]
        mom_12m = s.pct_change(252).iloc[-1]
        mom_1m  = s.pct_change(21).iloc[-1]
        raw[ticker] = {
            "mom_12m_1m": (mom_12m - mom_1m) if (pd.notna(mom_12m) and pd.notna(mom_1m)) else np.nan,
            "vol_21d":    s.pct_change().rolling(21).std().iloc[-1] * np.sqrt(252),
            "rsi_14":     compute_rsi(s).iloc[-1],
            "high52w":    curr / (s.rolling(252).max().iloc[-1] + 1e-9),
        }
    return pd.DataFrame(raw).T.rank(pct=True)

# ── Look-ahead-free fundamental helpers ──────────────────────────────────────
def _avail_cols(df, rebal_date, lag_days=EARNINGS_LAG_DAYS):
    """
    Return df columns (quarter-end Timestamps) that were publicly available
    at rebal_date after applying the earnings reporting lag.

    E.g. rebal_date=2025-01-31, lag=45 → cutoff=2024-12-17
         Q3-2024 (2024-09-30) ✓   Q4-2024 (2024-12-31) ✗
    """
    cutoff = pd.Timestamp(rebal_date) - pd.Timedelta(days=lag_days)
    cols = []
    for c in df.columns:
        try:
            if pd.Timestamp(c) <= cutoff:
                cols.append(c)
        except Exception:
            pass
    return sorted(cols, reverse=True)   # most recent first

def _ttm_sum(df, cols, *keys, n=4):
    """Sum a row across the n most recent available quarters (TTM = 4Q)."""
    if not cols:
        return np.nan
    for k in keys:
        if k in df.index:
            take = cols[:n]
            if not take:
                return np.nan
            vals = pd.to_numeric(df.loc[k, take], errors='coerce')
            return float(vals.sum()) if vals.notna().any() else np.nan
    return np.nan

def _latest(df, cols, *keys):
    """Get the most recent available quarter's value for a row."""
    for k in keys:
        if k in df.index and cols:
            v = pd.to_numeric(df.loc[k, cols[0]], errors='coerce')
            return float(v) if pd.notna(v) else np.nan
    return np.nan

def compute_ttm_fund(q_data, rebal_date, price_at_date=None):
    """
    Compute ROIC, FCF_Margin, EPS_Growth, PEG using ONLY quarterly financial
    data that was publicly available at rebal_date (EARNINGS_LAG_DAYS applied).

    This is the core fix for look-ahead bias:
    ✗ Old: use today's fundamentals as features for ALL historical rebal dates
    ✓ New: for each rebal_date, look back and find which quarter's data was
           actually public at that point in time

    Parameters
    ----------
    q_data        : dict {'income': DataFrame, 'balance': DataFrame, 'cashflow': DataFrame}
    rebal_date    : pd.Timestamp  — the rebalancing date being evaluated
    price_at_date : float | None  — closing price at rebal_date (required for PEG)

    Returns
    -------
    dict {roic, fcf_margin, eps_growth, peg} — np.nan where data unavailable
    """
    out = {f: np.nan for f in FUND_FEATS}

    inc = q_data.get('income',   pd.DataFrame())
    bal = q_data.get('balance',  pd.DataFrame())
    cf  = q_data.get('cashflow', pd.DataFrame())

    if inc.empty:
        return out

    # Filter each statement to quarters available at rebal_date
    inc_c = _avail_cols(inc, rebal_date)
    bal_c = _avail_cols(bal, rebal_date)
    cf_c  = _avail_cols(cf,  rebal_date)

    if not inc_c:
        return out   # no financial data public at this rebal_date

    ttm_rev = _ttm_sum(inc, inc_c, 'Total Revenue', 'Revenue')

    # ── FCF_Margin ────────────────────────────────────────────────────────────
    if cf_c:
        ocf   = _ttm_sum(cf, cf_c, 'Operating Cash Flow',
                         'Cash Flow From Continuing Operating Activities')
        capex = _ttm_sum(cf, cf_c, 'Capital Expenditure', 'Purchase Of PPE')
        if pd.notna(ocf) and pd.notna(ttm_rev) and ttm_rev != 0:
            # yfinance reports capex as negative → FCF = OCF + capex
            fcf = ocf + (capex if pd.notna(capex) else 0)
            out['fcf_margin'] = fcf / ttm_rev

    # ── ROIC ─────────────────────────────────────────────────────────────────
    if bal_c:
        ebit     = _ttm_sum(inc, inc_c, 'EBIT', 'Operating Income')
        tax_exp  = _ttm_sum(inc, inc_c, 'Tax Provision', 'Income Tax Expense')
        pretax   = _ttm_sum(inc, inc_c, 'Pretax Income', 'Pre Tax Income')
        t_assets = _latest(bal, bal_c, 'Total Assets')
        cl       = _latest(bal, bal_c, 'Current Liabilities', 'Total Current Liabilities')
        cash     = _latest(bal, bal_c,
                           'Cash And Cash Equivalents',
                           'Cash Cash Equivalents And Short Term Investments')

        if pd.notna(ebit) and pd.notna(t_assets):
            tx = 0.21   # default US effective tax rate
            if pd.notna(tax_exp) and pd.notna(pretax) and pretax != 0:
                tx = float(np.clip(tax_exp / pretax, 0.0, 0.45))
            nopat = ebit * (1 - tx)
            ic    = t_assets - (cl or 0) - (cash or 0)
            if ic > 0:
                out['roic'] = nopat / ic

    # ── EPS Growth (YoY TTM) ─────────────────────────────────────────────────
    if len(inc_c) >= 8:
        # 8 quarters available → full TTM vs prior-year TTM comparison
        eps_now   = _ttm_sum(inc, inc_c[:4],  'Diluted EPS', 'Basic EPS')
        eps_prior = _ttm_sum(inc, inc_c[4:8], 'Diluted EPS', 'Basic EPS')
        if pd.notna(eps_now) and pd.notna(eps_prior) and eps_prior != 0:
            out['eps_growth'] = (eps_now - eps_prior) / abs(eps_prior)
    elif len(inc_c) >= 5:
        # Fallback: single-quarter YoY (Q vs same Q one year ago)
        eps_now   = _ttm_sum(inc, inc_c[:1],  'Diluted EPS', 'Basic EPS', n=1)
        eps_prior = _ttm_sum(inc, inc_c[4:5], 'Diluted EPS', 'Basic EPS', n=1)
        if pd.notna(eps_now) and pd.notna(eps_prior) and eps_prior != 0:
            out['eps_growth'] = (eps_now - eps_prior) / abs(eps_prior)

    # ── PEG ───────────────────────────────────────────────────────────────────
    if (price_at_date is not None
            and pd.notna(out['eps_growth'])
            and out['eps_growth'] > 0):
        eps_ttm = _ttm_sum(inc, inc_c[:4], 'Diluted EPS', 'Basic EPS')
        if pd.notna(eps_ttm) and eps_ttm > 0:
            pe = price_at_date / eps_ttm
            # EPS growth expressed as integer % by PEG convention
            out['peg'] = pe / (out['eps_growth'] * 100)

    return out

def cross_sectional_rank(fund_raw_dict):
    """
    Given {ticker: {roic, fcf_margin, eps_growth, peg}},
    return {feat: pd.Series(rank_pct, index=tickers)}.
    PEG is inverted (lower PEG → higher rank).
    Tickers with NaN for a feature are excluded from that feature's ranking.
    """
    ranked = {}
    for feat in FUND_FEATS:
        s = pd.Series({t: v[feat] for t, v in fund_raw_dict.items()
                       if pd.notna(v.get(feat))})
        if s.empty:
            ranked[feat] = pd.Series(dtype=float)
            continue
        if feat == 'peg':
            s = 1 / s.where(s > 0)   # invert: low PEG → high rank
        ranked[feat] = s.dropna().rank(pct=True)
    return ranked

# ── Load scored universe from Google Sheets ───────────────────────────────────
print("[ML] Loading scored stocks...")
_MIN_SCORED = 5   # need at least this many tickers for a meaningful ML run
try:
    sc_sheet = spreadsheet.worksheet("US_Scored_Stocks")
    raw_data = sc_sheet.get_all_values()
    scored_df = pd.DataFrame(raw_data[1:], columns=raw_data[0])
    before_policy = len(scored_df)
    scored_df = drop_banned_ticker_rows(scored_df)
    if len(scored_df) != before_policy:
        print(f"[ML] Banned US tickers excluded: {banned_tickers_label()}")
    tickers = scored_df["Ticker"].tolist()
    if len(tickers) < _MIN_SCORED:
        raise ValueError(f"Only {len(tickers)} rows in US_Scored_Stocks — falling back to US_Universe")
except Exception as e:
    print(f"[ML] Fallback to US_Universe: {e}")
    raw_data = spreadsheet.worksheet('US_Universe').get_all_values()
    scored_df = pd.DataFrame(raw_data[1:], columns=raw_data[0])
    before_policy = len(scored_df)
    scored_df = drop_banned_ticker_rows(scored_df)
    if len(scored_df) != before_policy:
        print(f"[ML] Banned US tickers excluded: {banned_tickers_label()}")
    tickers = scored_df["Ticker"].tolist()[:150]

print(f"[ML] Universe: {len(tickers)} stocks")

# ── Download 2 years of daily prices ─────────────────────────────────────────
print("[ML] Downloading 2 years of prices...")
BATCH = 50
price_frames = []
dl_tickers = tickers[:TEST_LIMIT] if TEST_MODE else tickers
for i in range(0, len(dl_tickers), BATCH):
    batch = dl_tickers[i:i + BATCH]
    try:
        raw = yf.download(batch, period="2y", auto_adjust=True, progress=False)
        closes = raw["Close"] if isinstance(raw.columns, pd.MultiIndex) else raw
        if isinstance(closes, pd.Series):
            closes = closes.to_frame(name=batch[0])
        price_frames.append(closes)
    except Exception as e:
        print(f"  batch {i} err: {e}")
    time.sleep(1)

if not price_frames:
    print("[ML] No price data."); exit(1)

prices = pd.concat(price_frames, axis=1)
prices = prices.loc[:, ~prices.columns.duplicated()]
prices = prices.dropna(axis=1, thresh=int(len(prices) * 0.7))
print(f"[ML] Prices: {prices.shape}")

# ── Fetch quarterly financials (one-time, per ticker) ─────────────────────────
# This block downloads yfinance quarterly income statement, balance sheet, and
# cash flow for every ticker that survived the price filter.  The data is then
# used in the training loop to construct fundamentals at each historical
# rebal_date WITHOUT look-ahead bias.
print("[ML] Fetching quarterly financials (look-ahead-free feature construction)...")
quarterly_data = {}
q_tickers = list(prices.columns)
if TEST_MODE:
    q_tickers = q_tickers[:TEST_LIMIT]

for i, ticker in enumerate(q_tickers):
    try:
        t_obj = yf.Ticker(ticker)
        quarterly_data[ticker] = {
            'income':   t_obj.quarterly_income_stmt,
            'balance':  t_obj.quarterly_balance_sheet,
            'cashflow': t_obj.quarterly_cashflow,
        }
    except Exception:
        quarterly_data[ticker] = {}
    if (i + 1) % 25 == 0 or (i + 1) == len(q_tickers):
        print(f"  [{i+1}/{len(q_tickers)}] quarterly data fetched")
    time.sleep(0.3)

print(f"[ML] Quarterly financials ready for {len(quarterly_data)} tickers")

# ── Build training dataset (look-ahead-free) ──────────────────────────────────
# For each monthly rebal_date in the 2-year price window:
#   1. Compute price features using prices up to that date             (no bias)
#   2. Compute fundamental features using only data available at that date (no bias)
#   3. Target = 3-month forward return rank percentile
print("[ML] Building cross-sectional training panel (with holdout split)...")

rebal_dates = pd.date_range(
    start=prices.index[252],
    end=prices.index[-(63 + 1)],
    freq="ME"
)

# Last 25% of rebal dates reserved as out-of-sample holdout for Rank IC validation.
# This prevents inflated in-sample metrics and detects overfitting early.
n_holdout     = max(2, len(rebal_dates) // 4)
holdout_set   = set(rebal_dates[-n_holdout:])
print(f"[ML] Total months: {len(rebal_dates)}  |  Train: {len(rebal_dates)-n_holdout}  |  Holdout: {n_holdout}")

X_train_rows, y_train_rows = [], []
X_hold_rows,  y_hold_rows  = [], []

for rebal_date in rebal_dates:
    idx = prices.index.searchsorted(rebal_date)
    if idx >= len(prices) - 63:
        continue

    # ── Price features (no look-ahead — uses only prices up to idx) ──────────
    price_feat = calc_price_features(prices, idx).dropna()
    if price_feat.empty:
        continue

    fwd_rank = (prices.iloc[idx + 63] / prices.iloc[idx] - 1).rank(pct=True)
    common = price_feat.index.intersection(fwd_rank.dropna().index)
    if len(common) < MIN_COMMON:
        continue

    # ── Fundamental features (no look-ahead — respects EARNINGS_LAG_DAYS) ────
    # For rebal_date = 2025-01-31 → only use quarters ending ≤ 2024-12-17
    # i.e. Q3-2024 (Sep-30) is the most recent usable quarter
    price_row = prices.iloc[idx]
    fund_raw = {}
    for ticker in common:
        p = (float(price_row[ticker])
             if ticker in price_row.index and pd.notna(price_row.get(ticker))
             else None)
        fund_raw[ticker] = compute_ttm_fund(
            quarterly_data.get(ticker, {}),
            rebal_date,
            price_at_date=p,
        )

    fund_ranked_now = cross_sectional_rank(fund_raw)

    is_holdout = rebal_date in holdout_set
    for ticker in common:
        prow = price_feat.loc[ticker]
        if prow.isna().any():
            continue
        row_vals = list(prow[PRICE_FEATS].values)
        for feat in FUND_FEATS:
            row_vals.append(
                float(fund_ranked_now.get(feat, pd.Series(dtype=float)).get(ticker, 0.5))
            )
        if is_holdout:
            X_hold_rows.append(row_vals)
            y_hold_rows.append(float(fwd_rank[ticker]))
        else:
            X_train_rows.append(row_vals)
            y_train_rows.append(float(fwd_rank[ticker]))

if not X_train_rows:
    print("[ML] Insufficient training data."); exit(1)

X_train = np.array(X_train_rows)
y_train = np.array(y_train_rows)
print(f"[ML] Training samples: {len(X_train)}  |  Holdout samples: {len(X_hold_rows)}")

# ── Train RandomForestRegressor ───────────────────────────────────────────────
print("[ML] Training RandomForestRegressor...")
rf_model = RandomForestRegressor(
    n_estimators=200, max_depth=4, min_samples_leaf=5, random_state=42, n_jobs=-1
)
rf_model.fit(X_train, y_train)

importance = pd.Series(rf_model.feature_importances_, index=ALL_FEATS).sort_values(ascending=False)
print(f"[ML] RF Feature importances:\n{importance.round(3).to_string()}")

# ── Train LightGBM (if available) ─────────────────────────────────────────────
lgbm_model = None
if _LGBM_AVAILABLE:
    print("[ML] Training LGBMRegressor...")
    lgbm_model = LGBMRegressor(
        n_estimators=300, max_depth=4, learning_rate=0.05,
        num_leaves=31, min_child_samples=10,
        subsample=0.8, colsample_bytree=0.8,
        random_state=42, n_jobs=-1, verbose=-1,
    )
    lgbm_model.fit(X_train, y_train)
    lgbm_imp = pd.Series(lgbm_model.feature_importances_, index=ALL_FEATS).sort_values(ascending=False)
    print(f"[ML] LGBM Feature importances:\n{lgbm_imp.round(0).astype(int).to_string()}")

# ── Out-of-sample Rank IC (Spearman correlation) ──────────────────────────────
# Rank IC measures how well ML predictions rank-order actual future returns.
# IC > 0.02 is generally considered meaningful in factor research.
def _rank_ic(y_pred, y_true, label):
    if len(y_pred) < 10:
        print(f"[ML] Not enough holdout samples for {label} Rank IC")
        return float('nan')
    ic, p = spearmanr(y_pred, y_true)
    print(f"\n[ML] ── {label} Out-of-sample Rank IC ──────────────────────────────")
    print(f"  Holdout samples : {len(y_pred)}")
    print(f"  Rank IC         : {ic:.4f}  (p={p:.3f})")
    if   ic > 0.05: print("  Signal quality  : STRONG  (IC > 0.05)")
    elif ic > 0.02: print("  Signal quality  : MODERATE  (IC 0.02–0.05)")
    elif ic > 0.00: print("  Signal quality  : WEAK  (IC 0–0.02)")
    else:           print("  Signal quality  : ⚠️  NEGATIVE IC — model may be overfitting")
    return ic

rank_ic = float('nan')
if len(X_hold_rows) >= 10:
    X_hold = np.array(X_hold_rows)
    y_hold = np.array(y_hold_rows)

    rf_preds_hold   = rf_model.predict(X_hold)
    rank_ic_rf      = _rank_ic(rf_preds_hold, y_hold, "RandomForest")

    rank_ic_lgbm = float('nan')
    if lgbm_model is not None:
        lgbm_preds_hold = lgbm_model.predict(X_hold)
        rank_ic_lgbm    = _rank_ic(lgbm_preds_hold, y_hold, "LightGBM")

        # Ensemble holdout IC
        ens_preds_hold = 0.6 * rf_preds_hold + 0.4 * lgbm_preds_hold
        rank_ic        = _rank_ic(ens_preds_hold, y_hold, "Ensemble (RF×0.6+LGBM×0.4)")
    else:
        rank_ic = rank_ic_rf

# ── Predict on current snapshot (inference) ───────────────────────────────────
# Inference uses today's date — same compute_ttm_fund() call, but rebal_date=today.
# This is consistent with training: "what data is available right now?"
# No look-ahead concern here since we are predicting the future.
print("[ML] Computing current-snapshot features for inference...")
curr_price = calc_price_features(prices, len(prices) - 1).dropna()
if curr_price.empty:
    print("[ML] No current features."); exit(1)

today = pd.Timestamp.now().normalize()
curr_price_row = prices.iloc[-1]
curr_fund_raw = {}
for ticker in curr_price.index:
    p = (float(curr_price_row[ticker])
         if ticker in curr_price_row.index and pd.notna(curr_price_row.get(ticker))
         else None)
    curr_fund_raw[ticker] = compute_ttm_fund(
        quarterly_data.get(ticker, {}), today, price_at_date=p
    )
curr_fund_ranked = cross_sectional_rank(curr_fund_raw)

curr_tickers, curr_X = [], []
for ticker in curr_price.index:
    prow = curr_price.loc[ticker]
    if prow.isna().any():
        continue
    row_vals = list(prow[PRICE_FEATS].values)
    for feat in FUND_FEATS:
        row_vals.append(
            float(curr_fund_ranked.get(feat, pd.Series(dtype=float)).get(ticker, 0.5))
        )
    curr_tickers.append(ticker)
    curr_X.append(row_vals)

if not curr_tickers:
    print("[ML] No valid prediction rows."); exit(1)

curr_arr     = np.array(curr_X)
rf_preds     = rf_model.predict(curr_arr)

if lgbm_model is not None:
    lgbm_preds = lgbm_model.predict(curr_arr)
    preds      = 0.6 * rf_preds + 0.4 * lgbm_preds
    print(f"[ML] Inference: Ensemble (RF×0.6 + LGBM×0.4)  |  {len(curr_tickers)} stocks")
else:
    preds = rf_preds
    print(f"[ML] Inference: RandomForest only  |  {len(curr_tickers)} stocks")

ml_scores = pd.Series(preds, index=curr_tickers)

# ── Merge with factor scores ──────────────────────────────────────────────────
result = ml_scores.reset_index()
result.columns = ["Ticker", "ML_Score"]
result["ML_Score"] = pd.to_numeric(result["ML_Score"], errors="coerce").clip(0, 1)

factor_score_col = _first_available_score_col(scored_df)
ml_weight, ml_weight_reason = select_ml_weight(rank_ic)
factor_weight = 1.0 - ml_weight

if factor_score_col:
    scored_df[factor_score_col] = pd.to_numeric(scored_df[factor_score_col], errors="coerce")
    fs_map = scored_df.set_index("Ticker")[factor_score_col]
    result["Factor_Score"] = result["Ticker"].map(fs_map)
    result["Factor_Score_Norm"] = result["Factor_Score"].rank(pct=True)
    result["Factor_Score_Norm"] = result["Factor_Score_Norm"].fillna(0.5)
    result["Combined_Score"] = (
        ml_weight * result["ML_Score"] +
        factor_weight * result["Factor_Score_Norm"]
    ).round(4)
    print(
        "[ML] Blend policy: "
        f"factor={factor_score_col} weight={factor_weight:.2f}  "
        f"ml_weight={ml_weight:.2f} ({ml_weight_reason})"
    )
else:
    result["Factor_Score"] = None
    result["Factor_Score_Norm"] = None
    result["Combined_Score"] = result["ML_Score"].round(4)
    print("[ML] Blend policy: no factor score column found — using ML_Score only")

result["ML_Score"] = result["ML_Score"].round(4)
price_latest = prices.iloc[-1]
result["Price"] = result["Ticker"].map(
    lambda t: round(float(price_latest[t]), 2) if t in price_latest.index else None
)

result = drop_banned_ticker_rows(result)
result = result.sort_values("Combined_Score", ascending=False).reset_index(drop=True)
print(f"[ML] Top 5: {result['Ticker'].head(5).tolist()}")

# ── Write lightweight ML blend diagnostics ───────────────────────────────────
blend_report_cols = [
    "Generated", "Market", "Model", "Rank_IC", "ML_Weight", "Factor_Weight",
    "ML_Weight_Reason", "Factor_Score_Column", "ML_Factor_Spearman",
    "ML_Factor_Pearson", "Predicted_Stocks", "Top5", "Notes",
]
if factor_score_col:
    ml_factor_spearman = result["ML_Score"].corr(result["Factor_Score_Norm"], method="spearman")
    ml_factor_pearson = result["ML_Score"].corr(result["Factor_Score_Norm"], method="pearson")
else:
    ml_factor_spearman = np.nan
    ml_factor_pearson = np.nan

blend_report = pd.DataFrame([{
    "Generated": pd.Timestamp.now().strftime("%Y-%m-%d %H:%M:%S"),
    "Market": "US",
    "Model": "RF+LGBM" if lgbm_model is not None else "RandomForest",
    "Rank_IC": round(float(rank_ic), 4) if np.isfinite(rank_ic) else "",
    "ML_Weight": round(float(ml_weight), 4),
    "Factor_Weight": round(float(factor_weight), 4),
    "ML_Weight_Reason": ml_weight_reason,
    "Factor_Score_Column": factor_score_col or "",
    "ML_Factor_Spearman": round(float(ml_factor_spearman), 4) if pd.notna(ml_factor_spearman) else "",
    "ML_Factor_Pearson": round(float(ml_factor_pearson), 4) if pd.notna(ml_factor_pearson) else "",
    "Predicted_Stocks": len(result),
    "Top5": ", ".join(result["Ticker"].head(5).astype(str).tolist()),
    "Notes": "Current-snapshot correlation only; full ML residual IC needs historical prediction snapshots.",
}], columns=blend_report_cols)

try:
    report_ws = spreadsheet.worksheet("ML_Blend_Report")
except Exception:
    report_ws = spreadsheet.add_worksheet(
        title="ML_Blend_Report",
        rows=max(100, len(blend_report) + 10),
        cols=len(blend_report_cols) + 2,
    )
report_ws.clear()
report_ws.update([blend_report.columns.tolist()] + blend_report.fillna("").astype(str).values.tolist())
dual_write_dataframe("ML_Blend_Report", blend_report, market="US")

# ── Merge ML_Score + Combined_Score back into US_Scored_Stocks ───────────────
SCORED_COLS_ML = [
    'Rank', 'Ticker', 'Name', 'Market', 'Sector', 'MarketCap',
    'Value_Score', 'Quality_Score', 'Momentum_Score', 'Total_Score',
    'Final_Score', 'Score_Neutral',
    'ML_Score', 'Combined_Score',
    'ROIC', 'RevGrowth', 'GrossMargin', 'FCF_Margin', 'Debt_EBITDA', 'PEG',
    'Last_Updated',
]

ml_map       = result.set_index("Ticker")["ML_Score"].to_dict()
combined_map = result.set_index("Ticker")["Combined_Score"].to_dict()

scored_df["ML_Score"]       = scored_df["Ticker"].map(ml_map)
scored_df["Combined_Score"] = scored_df["Ticker"].map(combined_map)

# Tickers with no ML prediction (insufficient price history): fall back to the
# same factor score used for blending, then Total_Score for older sheets.
mask = scored_df["Combined_Score"].isna()
fallback_col = factor_score_col or ("Total_Score" if "Total_Score" in scored_df.columns else None)
if fallback_col:
    scored_df.loc[mask, "Combined_Score"] = pd.to_numeric(
        scored_df.loc[mask, fallback_col], errors="coerce")

# Re-sort by Combined_Score desc and re-rank
scored_df["ML_Score"]       = pd.to_numeric(scored_df["ML_Score"],       errors="coerce").round(4)
scored_df["Combined_Score"] = pd.to_numeric(scored_df["Combined_Score"], errors="coerce").round(4)
scored_df = scored_df.sort_values("Combined_Score", ascending=False, na_position="last")
scored_df = scored_df.reset_index(drop=True)
scored_df["Rank"] = scored_df.index + 1

for col in SCORED_COLS_ML:
    if col not in scored_df.columns:
        scored_df[col] = ''

save_df = drop_banned_ticker_rows(scored_df[SCORED_COLS_ML].copy())
save_df["Rank"] = range(1, len(save_df) + 1)
sheet_out = save_df.fillna("").astype(str)

sc_sh = spreadsheet.worksheet("US_Scored_Stocks")
sc_sh.clear()
sc_sh.update([sheet_out.columns.tolist()] + sheet_out.values.tolist())
print(f"✅ [ML] {len(save_df)} stocks written to US_Scored_Stocks "
      f"with ML_Score + Combined_Score  |  top: {result['Ticker'].head(5).tolist()}")
dual_write_dataframe("US_Scored_Stocks", save_df, market="US")
