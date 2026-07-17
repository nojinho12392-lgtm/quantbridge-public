# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
import gspread
import yfinance as yf
from sheets_client import get_spreadsheet
import pandas as pd
import numpy as np
import time
from datetime import datetime

# ── Cache integration ─────────────────────────────────────────────────────────
from cache_manager import CacheManager
from pipeline.data.sec_companyfacts_lake import read_latest_quality_history_features
from pipeline.data.sec_edgar import latest_sec_metrics_for_tickers
from pipeline.factor_policy_runtime import apply_factor_policy_weights
from pipeline.scoring.company_quality import (
    QUALITY_REVIEW_COLS,
    QUALITY_SCORE_COLS,
    add_company_quality_review_columns,
    compute_company_quality_scores,
    quality_adjusted_final_score,
)
from pipeline.scoring.common_factor_scorer import compute_us_factor_scores
from quantbridge.ticker_policy import banned_tickers_label, drop_banned_ticker_rows
from quantbridge.writers.dual_write import dual_write_dataframe

# ── Google Sheets connection ──────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()
raw_sheet = spreadsheet.worksheet('US_Universe')

# Initialise cache (bulk-loads Company_Master into memory — one API call)
cache = CacheManager(spreadsheet, verbose=True)


# ── Macro regime factor weights ───────────────────────────────────────────────
def _load_macro_weights(ss, market: str, defaults: tuple) -> tuple:
    """
    Read V/Q/M factor weights from the Macro_Regime sheet.

    Returns (w_value, w_quality, w_momentum). Falls back to defaults if:
    - Sheet does not exist
    - Sheet was generated more than 48 hours ago (stale)
    - Any parsing error
    """
    try:
        ws   = ss.worksheet('Macro_Regime')
        rows = ws.get_all_values()
        kv   = {r[0].strip(): r[1].strip()
                for r in rows if len(r) >= 2 and r[0].strip()}
        gen = kv.get('Generated', '')
        if gen:
            age_h = (datetime.now() -
                     datetime.strptime(gen[:16], '%Y-%m-%d %H:%M')).total_seconds() / 3600
            if age_h > 48:
                print(f"  ⚠️  Macro_Regime is stale ({age_h:.0f}h old) — using default weights")
                return defaults
        v = float(kv.get(f'{market}_V_Weight', defaults[0]))
        q = float(kv.get(f'{market}_Q_Weight', defaults[1]))
        m = float(kv.get(f'{market}_M_Weight', defaults[2]))
        regime = kv.get('Regime', 'NEUTRAL')
        print(f"\n📊 Macro Regime : {regime}  |  {market} weights → "
              f"Value:{v:.2f}  Quality:{q:.2f}  Momentum:{m:.2f}")
        return (v, q, m)
    except gspread.exceptions.WorksheetNotFound:
        print(f"\n📊 Macro_Regime sheet not found — using default {market} weights")
        return defaults
    except Exception as e:
        print(f"\n⚠️  Macro weight load error ({e}) — using default {market} weights")
        return defaults


# US defaults: Value=0.40, Quality=0.30, Momentum=0.30
W_V, W_Q, W_M = _load_macro_weights(spreadsheet, 'US', (0.40, 0.30, 0.30))
_policy_weights = apply_factor_policy_weights(
    spreadsheet,
    'US',
    {'Value_Score': W_V, 'Quality_Score': W_Q, 'Momentum_Score': W_M},
)
W_V = _policy_weights['Value_Score']
W_Q = _policy_weights['Quality_Score']
W_M = _policy_weights['Momentum_Score']

# ── Test mode ───────────────────────────────────────────────────────────────────
TEST_MODE    = os.environ.get('QUANT_TEST_MODE')    == 'true'
ANALYZE_ONLY = os.environ.get('QUANT_ANALYZE_ONLY') == 'true'
ENABLE_SEC_LIVE = os.environ.get('QUANT_ENABLE_SEC_LIVE', 'true').lower() not in {'0', 'false', 'no', 'off'}
SEC_LIVE_MAX_TICKERS = int(os.environ.get('QUANT_SEC_LIVE_MAX_TICKERS', '120'))
TEST_LIMIT = 50
if TEST_MODE:
    print("\n⚠️  TEST MODE : scoring limited to 50 stocks")
if ANALYZE_ONLY:
    print("\n⚡ ANALYZE-ONLY : using cached fundamentals, skipping momentum download")

# ── Standard column schema ────────────────────────────────────────────────────
SCORED_COLS = [
    'Rank', 'Ticker', 'Name', 'Market', 'Sector', 'MarketCap',
    'Value_Score', 'Quality_Score', 'Momentum_Score', 'Total_Score',
    'Final_Score', 'Score_Neutral',
    'Profitability_Quality', 'Cash_Quality', 'Growth_Quality',
    'BalanceSheet_Strength', 'Valuation_Discipline', 'Timing_Overlay',
    'Persistence_Quality', 'Business_Quality_Score', 'Investability_Score', 'Quality_Data_Confidence',
    'Quality_Red_Flags',
    'Investability_Rank', 'Business_Quality_Rank', 'Quality_Rank_Delta',
    'Quality_Category',
    'ROIC', 'RevGrowth', 'GrossMargin', 'FCF_Margin', 'Debt_EBITDA', 'PEG',
    'Last_Updated',
]

# ── Load raw universe data from US_Universe ────────────────────────────────────────
data = raw_sheet.get_all_values()
if not data or len(data) < 2:
    print("US_Universe is empty. Run 01_universe_expander.py first.")
    exit()

df = pd.DataFrame(data[1:], columns=data[0])

# Guard: keep only US rows (Market column written by 01_universe_expander.py).
# Prevents KR tickers from leaking in if the sheet ever contains mixed data.
if 'Market' in df.columns:
    df = df[df['Market'] == 'US'].copy()

before_policy = len(df)
df = drop_banned_ticker_rows(df)
if len(df) != before_policy:
    print(f"[Scorer] Banned US tickers excluded: {banned_tickers_label()}")

# Coerce numeric columns from the standardised US_Universe schema
numeric_cols = ['MarketCap', 'PER', 'PBR', 'ROE', 'Revenue',
                'RevenueGrowth', 'OperatingMargin', 'GrossMargin', 'DebtToEquity']
for col in numeric_cols:
    if col in df.columns:
        df[col] = pd.to_numeric(df[col], errors='coerce')
    else:
        df[col] = np.nan

# ── 1차 필터 (sector-aware quality gate) ─────────────────────────────────────
# Rationale:
#   - PER cap: Growth / Tech / Healthcare often trade at 40–60x legitimately.
#              A flat 30x cap eliminates most high-quality growth stocks.
#   - ROE min: Capital-intensive sectors (Utilities, REITs, Financials) run
#              structurally lower ROE; 10% floor is too high for them.
#   - D/E cap: Financials and Utilities use leverage by design (banks, bonds).
#              100% D/E would exclude virtually all banks and utilities.
#   - OperatingMargin: Financials report net interest margin, not op-margin;
#              applying a 5% op-margin floor to banks is meaningless.
#
# Sector groupings are derived from the 'Sector' column written by 01_universe_expander.py.
# Unknown/blank sectors fall back to the DEFAULT tier.

DEFAULT_THRESHOLDS = dict(per_max=30,  roe_min=0.10, op_margin_min=0.05, de_max=100)

SECTOR_THRESHOLDS = {
    # High-multiple growth sectors — PER up to 60
    'Technology':             dict(per_max=60,  roe_min=0.10, op_margin_min=0.05, de_max=100),
    'Healthcare':             dict(per_max=60,  roe_min=0.08, op_margin_min=0.03, de_max=100),
    'Communication Services': dict(per_max=45,  roe_min=0.10, op_margin_min=0.05, de_max=100),
    'Consumer Discretionary': dict(per_max=45,  roe_min=0.10, op_margin_min=0.05, de_max=100),
    # Capital-intensive / regulated — structural leverage & lower ROE
    'Financials':             dict(per_max=20,  roe_min=0.07, op_margin_min=0.00, de_max=1500),
    'Real Estate':            dict(per_max=40,  roe_min=0.05, op_margin_min=0.05, de_max=300),
    'Utilities':              dict(per_max=25,  roe_min=0.05, op_margin_min=0.05, de_max=300),
    # Standard industrial / materials sectors
    'Industrials':            dict(per_max=35,  roe_min=0.10, op_margin_min=0.05, de_max=150),
    'Materials':              dict(per_max=30,  roe_min=0.10, op_margin_min=0.05, de_max=100),
    'Energy':                 dict(per_max=30,  roe_min=0.08, op_margin_min=0.05, de_max=150),
    'Consumer Staples':       dict(per_max=30,  roe_min=0.10, op_margin_min=0.05, de_max=150),
}

def _sector_thresholds(sector: str) -> dict:
    """Return filter thresholds for a given sector string."""
    if not isinstance(sector, str):
        return DEFAULT_THRESHOLDS
    # Exact match first
    if sector in SECTOR_THRESHOLDS:
        return SECTOR_THRESHOLDS[sector]
    # Partial match (e.g. 'Financial Services' → 'Financials')
    for key in SECTOR_THRESHOLDS:
        if key.lower() in sector.lower() or sector.lower() in key.lower():
            return SECTOR_THRESHOLDS[key]
    return DEFAULT_THRESHOLDS

def _to_float(v):
    try:
        return float(v) if v not in ('', None) else None
    except (ValueError, TypeError):
        return None

# Apply MarketCap floor first (sector-agnostic)
df_cap = df[df['MarketCap'] > 5e8].copy()  # $500M+ liquidity floor

# Apply sector-aware row-level filter
def _passes_filter(row) -> bool:
    t = _sector_thresholds(row.get('Sector', ''))
    roe = row.get('ROE')
    op  = row.get('OperatingMargin')
    de  = row.get('DebtToEquity')
    per = row.get('PER')
    try:
        if pd.isna(roe) or float(roe) <= t['roe_min']:           return False
        if t['op_margin_min'] > 0:
            if pd.isna(op) or float(op) <= t['op_margin_min']:   return False
        if pd.isna(de)  or float(de)  >= t['de_max']:            return False
        if pd.isna(per) or not (0 < float(per) < t['per_max']):  return False
    except (ValueError, TypeError):
        return False
    return True

pre_filtered = df_cap[df_cap.apply(_passes_filter, axis=1)].copy()

# Print sector breakdown for transparency
sector_counts = pre_filtered['Sector'].value_counts()
print("\n📊 Sector-aware filter thresholds applied:")
for sec, thresholds in SECTOR_THRESHOLDS.items():
    n = sector_counts.get(sec, 0)
    print(f"  {sec:<28} PER<{thresholds['per_max']:>3}  ROE>{thresholds['roe_min']:.0%}  D/E<{thresholds['de_max']:>5}%  → {n} stocks")
print(f"  {'(default)' :<28} PER<{DEFAULT_THRESHOLDS['per_max']:>3}  ROE>{DEFAULT_THRESHOLDS['roe_min']:.0%}  D/E<{DEFAULT_THRESHOLDS['de_max']:>5}%")
print(f"  Total pre_filtered: {len(pre_filtered)} / {len(df_cap)} (cap-filtered)  / {len(df)} (raw)")


if TEST_MODE:
    pre_filtered = pre_filtered.head(TEST_LIMIT)
    print(f"⚠️  TEST MODE: trimmed to {len(pre_filtered)} stocks")

# Optional: additional volume check via cache
pre_filtered['_volume'] = pre_filtered['Ticker'].apply(
    lambda t: _to_float(cache.get_row(t).get('Volume_Last')))
filtered = pre_filtered[
    pre_filtered['_volume'].isna() | (pre_filtered['_volume'] > 1_000_000)
].copy()
filtered.drop(columns=['_volume'], inplace=True)

print(f"📊 1차 필터 통과: {len(filtered)}개")

# ── Fetch deep fundamentals from cache ────────────────────────────────────────
tickers_filtered = filtered['Ticker'].tolist()
if not ANALYZE_ONLY:
    print(f"\n[Scorer] Pre-warming cache for {len(filtered)} tickers...")
    cache.prefetch(tickers_filtered, delay=0.3)
else:
    print(f"\n[Scorer] ANALYZE-ONLY: reading {len(tickers_filtered)} tickers from existing cache")

deep_rows = {}
for i, ticker in enumerate(tickers_filtered, 1):
    rec = dict(PEG=None, EV_EBITDA=None, EPS_Growth=None,
               ROIC=None, FCF_NI=None, FCF_Margin=None, Debt_EBITDA=None,
               InterestCoverage=None, RevGrowth=None, GrossMargin=None,
               DivYield=None,
               Name=None, Sector=None,
               TotalAssets=None, CurrentAssets=None, CurrentLiabilities=None,
               RetainedEarnings=None, TotalLiabilities=None)
    try:
        fin_data = cache.load_or_fetch_financials(ticker)
        rec['PEG']              = fin_data.get('PEG')
        rec['EV_EBITDA']        = fin_data.get('EV_EBITDA')
        rec['EPS_Growth']       = fin_data.get('EPS_Growth')
        rec['ROIC']             = fin_data.get('ROIC')
        rec['FCF_NI']           = fin_data.get('FCF_NI')
        rec['FCF_Margin']       = fin_data.get('FCF_Margin')
        rec['Debt_EBITDA']      = fin_data.get('Debt_EBITDA')
        rec['InterestCoverage'] = fin_data.get('InterestCoverage')
        rec['RevGrowth']        = fin_data.get('RevGrowth')
        rec['GrossMargin']      = fin_data.get('GrossMargin')
        rec['DivYield']         = fin_data.get('DivYield')
        rec['Name']             = fin_data.get('Name')
        rec['Sector']           = fin_data.get('Sector')
        rec['TotalAssets']        = fin_data.get('TotalAssets')
        rec['CurrentAssets']      = fin_data.get('CurrentAssets')
        rec['CurrentLiabilities'] = fin_data.get('CurrentLiabilities')
        rec['RetainedEarnings']   = fin_data.get('RetainedEarnings')
        rec['TotalLiabilities']   = fin_data.get('TotalLiabilities')
        print(f"  [{i}/{len(tickers_filtered)}] {ticker} OK")
    except Exception as e:
        print(f"  [{i}/{len(tickers_filtered)}] {ticker} ERR ({e})")
    deep_rows[ticker] = rec

deep_df = pd.DataFrame(deep_rows).T.reset_index().rename(columns={'index': 'Ticker'})
filtered = filtered.merge(deep_df, on='Ticker', how='left', suffixes=('_old', ''))

for col in ['PEG', 'EV_EBITDA', 'EPS_Growth', 'ROIC', 'FCF_NI', 'FCF_Margin',
            'Debt_EBITDA', 'InterestCoverage', 'RevGrowth', 'GrossMargin',
            'DivYield',
            'TotalAssets', 'CurrentAssets', 'CurrentLiabilities',
            'RetainedEarnings', 'TotalLiabilities']:
    old_col = col + '_old'
    if old_col in filtered.columns:
        filtered[col] = filtered[col].combine_first(filtered[old_col])
        filtered.drop(columns=[old_col], inplace=True)
    filtered[col] = pd.to_numeric(filtered[col], errors='coerce')

# Handle Name/Sector merges
for col in ['Name', 'Sector']:
    old_col = col + '_old'
    if old_col in filtered.columns:
        filtered[col] = filtered[col].fillna(filtered[old_col])
        filtered.drop(columns=[old_col], inplace=True)

# ── SEC EDGAR live overlay (free, filing-date-safe fundamentals) ──────────────
if ENABLE_SEC_LIVE and not ANALYZE_ONLY and len(filtered):
    sec_candidates = (
        filtered[['Ticker', 'MarketCap']]
        .assign(MarketCap=lambda d: pd.to_numeric(d['MarketCap'], errors='coerce'))
        .sort_values('MarketCap', ascending=False)['Ticker']
        .dropna()
        .astype(str)
        .head(SEC_LIVE_MAX_TICKERS)
        .tolist()
    )
    print(f"\n[Scorer] Loading SEC EDGAR fundamentals for top {len(sec_candidates)} US tickers...")
    try:
        sec_df = latest_sec_metrics_for_tickers(
            sec_candidates,
            max_tickers=SEC_LIVE_MAX_TICKERS,
        )
    except Exception as exc:
        sec_df = pd.DataFrame()
        print(f"  SEC EDGAR overlay skipped: {type(exc).__name__}: {exc}")

    if not sec_df.empty:
        sec_df = sec_df.reset_index().rename(columns={'index': 'Ticker'})
        sec_cols = [
            'Revenue', 'TotalAssets', 'CurrentAssets', 'CurrentLiabilities',
            'RetainedEarnings', 'TotalLiabilities', 'OperatingMargin',
            'ROIC', 'ROE', 'FCF_NI', 'FCF_Margin', 'GrossMargin',
            'InterestCoverage', 'RevGrowth', 'EPS_Growth',
            'DebtToEquity', 'Debt_EBITDA',
        ]
        overlay = sec_df[['Ticker'] + [c for c in sec_cols if c in sec_df.columns]].copy()
        filtered = filtered.merge(overlay, on='Ticker', how='left', suffixes=('', '_sec'))
        updated = 0
        for col in sec_cols:
            sec_col = col + '_sec'
            if sec_col not in filtered.columns:
                continue
            sec_values = pd.to_numeric(filtered[sec_col], errors='coerce')
            if col not in filtered.columns:
                filtered[col] = np.nan
            before_missing = filtered[col].isna().sum()
            filtered[col] = sec_values.combine_first(pd.to_numeric(filtered[col], errors='coerce'))
            updated += int(before_missing - filtered[col].isna().sum())
            filtered.drop(columns=[sec_col], inplace=True)
        print(f"  SEC EDGAR overlay applied: {len(sec_df)} tickers, {updated} missing fields filled")
    else:
        print("  SEC EDGAR overlay returned no usable fundamentals")
elif ANALYZE_ONLY:
    print("\n[Scorer] ANALYZE-ONLY: SEC EDGAR live overlay skipped")
else:
    print("\n[Scorer] SEC EDGAR live overlay disabled")

# ── SEC CompanyFacts local history features (free data lake) ─────────────────
try:
    sec_history = read_latest_quality_history_features()
except Exception as exc:
    sec_history = pd.DataFrame()
    print(f"\n[Scorer] SEC CompanyFacts history features skipped: {type(exc).__name__}: {exc}")

if not sec_history.empty and 'Ticker' in sec_history.columns:
    history_cols = [
        'Ticker',
        'History_Years',
        'ROIC_5Y_Median',
        'ROIC_5Y_Stability',
        'Revenue_CAGR_5Y',
        'FCF_Positive_Years_5Y',
        'Margin_Stability_5Y',
        'Debt_Reduction_Trend_5Y',
        'Quality_Persistence_Score',
    ]
    available_history_cols = [col for col in history_cols if col in sec_history.columns]
    sec_history = sec_history[available_history_cols].drop_duplicates('Ticker', keep='last')
    before_cols = set(filtered.columns)
    filtered = filtered.merge(sec_history, on='Ticker', how='left')
    added_cols = [col for col in available_history_cols if col != 'Ticker' and col not in before_cols]
    matched = int(pd.to_numeric(
        filtered.get('Quality_Persistence_Score', pd.Series(index=filtered.index)),
        errors='coerce',
    ).notna().sum())
    print(
        "\n[Scorer] SEC CompanyFacts history features merged "
        f"for {matched} tickers | added: {added_cols}"
    )
else:
    print("\n[Scorer] SEC CompanyFacts history features not found; run tools/sec_companyfacts_lake.py")

# ── Download momentum data for filtered tickers ───────────────────────────────
BATCH = 100
price_frames = []
if not ANALYZE_ONLY:
    print(f"\n[Scorer] Downloading 1yr price history for {len(tickers_filtered)} tickers...")
    for i in range(0, len(tickers_filtered), BATCH):
        batch = tickers_filtered[i:i + BATCH]
        try:
            raw = yf.download(batch, period='1y', auto_adjust=True, progress=False)
            closes = raw['Close'] if isinstance(raw.columns, pd.MultiIndex) else raw
            if isinstance(closes, pd.Series):
                closes = closes.to_frame(name=batch[0])
            price_frames.append(closes)
        except Exception as e:
            print(f"  batch error: {e}")
        time.sleep(0.5)
else:
    print("\n[Scorer] ANALYZE-ONLY: skipping price download — Momentum_Score = 0")

if price_frames:
    prices = pd.concat(price_frames, axis=1)
    prices = prices.loc[:, ~prices.columns.duplicated()]

    def _mom(ticker):
        if ticker not in prices.columns:
            return pd.Series({'Mom_1M': None, 'Mom_3M': None, 'Mom_12M': None})
        s = prices[ticker].dropna()
        if len(s) < 200:
            return pd.Series({'Mom_1M': None, 'Mom_3M': None, 'Mom_12M': None})
        curr = s.iloc[-1]
        return pd.Series({
            'Mom_1M':  round((curr / s.iloc[-21]) - 1, 4) if len(s) >= 21 else None,
            'Mom_3M':  round((curr / s.iloc[-63]) - 1, 4) if len(s) >= 63 else None,
            'Mom_12M': round((curr / s.iloc[0])  - 1, 4),
        })

    mom_df = pd.DataFrame([_mom(t) for t in tickers_filtered], index=tickers_filtered)
    mom_df.index.name = 'Ticker'
    mom_df = mom_df.reset_index()
    filtered = filtered.merge(mom_df, on='Ticker', how='left')
else:
    filtered['Mom_1M'] = filtered['Mom_3M'] = filtered['Mom_12M'] = np.nan

for col in ['Mom_1M', 'Mom_3M', 'Mom_12M']:
    filtered[col] = pd.to_numeric(filtered[col], errors='coerce')

# ── Sector neutralization ────────────────────────────────────────────────────
def sector_neutralize(df, score_col='Total_Score'):
    """
    Z-score normalize Total_Score within each sector group.
    Sectors with fewer than 5 stocks keep their original score.
    """
    df = df.copy()
    df['Score_Neutral'] = df[score_col]
    for sector, group in df.groupby('Sector'):
        if len(group) < 5:
            continue
        mu  = group[score_col].mean()
        std = group[score_col].std()
        if std > 0:
            df.loc[group.index, 'Score_Neutral'] = (group[score_col] - mu) / std
    return df

# ── Value Score ───────────────────────────────────────────────────────────────
# Growth-adjusted value: PEG already incorporates EPS growth; RevGrowth adds a
# top-line growth premium so high-multiple/high-growth names (AI, cloud) are not
# purely penalised by absolute multiples alone.
#   PEG 0.35 | EV/EBITDA 0.25 | RevGrowth 0.25 | DivYield 0.15  (sum = 1.00)
def value_rank(series):
    inv = (1 / series).where(series > 0)
    return inv.rank(pct=True)

filtered['r_peg'] = value_rank(filtered['PEG'])
filtered['r_ev']  = value_rank(filtered['EV_EBITDA'])

# Revenue growth: higher growth = growth premium within value factor
# Neutral (0.5) for stocks with missing RevGrowth — no penalty for non-reporters
filtered['r_revgrowth'] = filtered['RevGrowth'].rank(pct=True)

# Dividend yield: higher yield = better value; 0-div stocks treated as neutral (0.5), not penalised
_div_pos = filtered['DivYield'].where(filtered['DivYield'] > 0)
filtered['r_divyield'] = _div_pos.rank(pct=True)   # NaN for 0-div stocks → fillna(0.5) below

all_missing = (filtered['r_peg'].isna() & filtered['r_ev'].isna() &
               filtered['r_revgrowth'].isna() & filtered['r_divyield'].isna())
filtered['Value_Score'] = np.where(
    all_missing, np.nan,
    W_V * (
        0.35 * filtered['r_peg'].fillna(0.5) +
        0.25 * filtered['r_ev'].fillna(0.5) +
        0.25 * filtered['r_revgrowth'].fillna(0.5) +
        0.15 * filtered['r_divyield'].fillna(0.5)
    )
)

peg_cov = filtered['r_peg'].notna().sum()
ev_cov  = filtered['r_ev'].notna().sum()
rg_cov  = filtered['r_revgrowth'].notna().sum()
div_cov = filtered['r_divyield'].notna().sum()
print(f"  Value coverage → PEG: {peg_cov}, EV/EBITDA: {ev_cov}, RevGrowth: {rg_cov}, DivYield: {div_cov} / {len(filtered)}")

# ── Altman Z-Score (US public company version) ───────────────────────────────
def compute_altman_z(row):
    """
    Altman Z-Score (US public company version only — not applicable to KR).
    Z = 1.2*X1 + 1.4*X2 + 3.3*X3 + 0.6*X4 + 1.0*X5

    X1 = (CurrentAssets - CurrentLiabilities) / TotalAssets
    X2 = RetainedEarnings / TotalAssets
    X3 = EBIT / TotalAssets  (proxied by OperatingMargin * Revenue = operating income)
    X4 = MarketCap / TotalLiabilities
    X5 = Revenue / TotalAssets

    Zones:
      Z > 2.99  → Safe
      1.81~2.99 → Grey
      Z < 1.81  → Distress
    """
    try:
        ta  = float(row.get('TotalAssets') or 0)
        if ta == 0:
            return np.nan
        ca  = float(row.get('CurrentAssets') or 0)
        cl  = float(row.get('CurrentLiabilities') or 0)
        re  = float(row.get('RetainedEarnings') or 0)
        tl  = float(row.get('TotalLiabilities') or 1e-9)
        mc  = float(row.get('MarketCap') or 0)
        rev = float(row.get('Revenue') or 0)
        # EBIT proxy: OperatingMargin (decimal) × Revenue gives operating income in dollars.
        # Both columns come from US_Universe and are always present in filtered.
        # Avoids the old ROIC*TotalAssets error (which reduced to just ROIC after /ta).
        op_margin = float(row.get('OperatingMargin') or 0)
        revenue   = float(row.get('Revenue') or 0)
        ebit = op_margin * revenue

        x1 = (ca - cl) / ta
        x2 = re / ta
        x3 = ebit / ta
        x4 = mc / tl
        x5 = rev / ta
        return round(1.2*x1 + 1.4*x2 + 3.3*x3 + 0.6*x4 + 1.0*x5, 4)
    except Exception:
        return np.nan

# ── Quality Score ─────────────────────────────────────────────────────────────
def rpct(s):
    return s.rank(pct=True)

filtered['r_roic']  = rpct(filtered['ROIC'])
filtered['r_fcfni'] = rpct(filtered['FCF_NI'])
filtered['r_roe']   = rpct(filtered['ROE'])
filtered['r_ic']    = rpct(filtered['InterestCoverage'])

# ── Altman Z-Score (US only) ──────────────────────────────────────────────────
filtered['AltmanZ']  = filtered.apply(compute_altman_z, axis=1)
filtered['r_altman'] = rpct(filtered['AltmanZ'])

altman_coverage = filtered['AltmanZ'].notna().sum()
safe_n    = (filtered['AltmanZ'] > 2.99).sum()
grey_n    = ((filtered['AltmanZ'] >= 1.81) & (filtered['AltmanZ'] <= 2.99)).sum()
distress_n = (filtered['AltmanZ'] < 1.81).sum()
print(f"\n📊 Altman Z-Score: {altman_coverage}/{len(filtered)} computed  "
      f"| Safe(>2.99): {safe_n}  Grey(1.81-2.99): {grey_n}  Distress(<1.81): {distress_n}")

# ── Accruals Quality Signal (Sloan 1996 framework) ────────────────────────────
# Proxy: FCF_Margin / OperatingMargin — cash conversion efficiency of operating earnings
# High ratio → company converts operating profit into real cash → low accruals → good
# Additive to FCF_Margin and ROIC individually (captures their relationship)
_accruals_num = filtered['FCF_Margin']
_accruals_den = filtered['OperatingMargin'].replace(0, np.nan)
filtered['Accruals_EQ'] = _accruals_num / _accruals_den
filtered['r_accruals']  = rpct(filtered['Accruals_EQ'])
accruals_cov = filtered['r_accruals'].notna().sum()
print(f"  Accruals (EQ) coverage: {accruals_cov} / {len(filtered)}")

# Quality_Score 6-factor (outer weight = W_Q from macro regime)
# Weights: ROIC 25% + FCF_NI 25% + ROE 20% + IC 10% + AltmanZ 10% + Accruals 10%
filtered['Quality_Score'] = W_Q * (
    0.25 * filtered['r_roic'].fillna(0.5) +
    0.25 * filtered['r_fcfni'].fillna(0.5) +
    0.20 * filtered['r_roe'].fillna(0.5) +
    0.10 * filtered['r_ic'].fillna(0.5) +
    0.10 * filtered['r_altman'].fillna(0.5) +
    0.10 * filtered['r_accruals'].fillna(0.5)
)

# ── Analyst Revision Momentum ─────────────────────────────────────────────────
# recommendationMean: 1=Strong Buy ... 5=Strong Sell
# Fetched per-ticker from yfinance info; requires ≥3 analyst opinions to be valid.
# Skipped in ANALYZE_ONLY mode to avoid additional API calls.
analyst_ratings = {}
if not ANALYZE_ONLY:
    # Limit to top 100 by MarketCap — analyst consensus is most reliable for large-caps
    # and fetching 200+ tickers risks Yahoo Finance IP throttling.
    _analyst_candidates = (
        filtered[['Ticker', 'MarketCap']]
        .assign(MarketCap=lambda d: pd.to_numeric(d['MarketCap'], errors='coerce'))
        .sort_values('MarketCap', ascending=False)
        .head(100)['Ticker'].tolist()
    )
    print(f"\n[Scorer] Fetching analyst ratings for top {len(_analyst_candidates)} tickers by MarketCap...")
    _yf_session = None
    try:
        import requests as _req
        from requests.adapters import HTTPAdapter
        from urllib3.util.retry import Retry
        _s = _req.Session()
        _s.mount('https://', HTTPAdapter(max_retries=Retry(total=2, backoff_factor=0.5,
                                                            status_forcelist=[429, 500, 503])))
        _yf_session = _s
    except Exception:
        pass

    for _t in _analyst_candidates:
        try:
            _info = yf.Ticker(_t, session=_yf_session).info
            _rating  = _info.get('recommendationMean')
            _n_analy = _info.get('numberOfAnalystOpinions') or 0
            if _rating is not None and _n_analy >= 3:
                analyst_ratings[_t] = float(_rating)
        except Exception:
            pass
        time.sleep(0.25)

    print(f"  Analyst ratings fetched: {len(analyst_ratings)} / {len(_analyst_candidates)}")

if analyst_ratings:
    _rat_series = pd.Series(analyst_ratings)
    filtered['AnalystRating'] = filtered['Ticker'].map(_rat_series)
    # Lower score (1=Strong Buy) → higher quality → invert before ranking
    filtered['r_analyst'] = (1.0 / filtered['AnalystRating'].replace(0, np.nan)).rank(pct=True)
else:
    filtered['r_analyst'] = np.nan

analyst_cov = filtered['r_analyst'].notna().sum()
print(f"  Analyst Revision coverage: {analyst_cov} / {len(filtered)}")

# ── Momentum Score ────────────────────────────────────────────────────────────
filtered['Mom_12M_1M'] = filtered['Mom_12M'] - filtered['Mom_1M']

filtered['r_mom12m1m'] = rpct(filtered['Mom_12M_1M'])
filtered['r_mom3m']    = rpct(filtered['Mom_3M'])

eps_coverage    = filtered['EPS_Growth'].notna().mean()
analyst_usable  = filtered['r_analyst'].notna().mean() > 0.2   # ≥20% coverage

if eps_coverage > 0.3 and analyst_usable:
    filtered['r_eps'] = rpct(filtered['EPS_Growth'])
    filtered['Momentum_Score'] = W_M * (
        0.40 * filtered['r_mom12m1m'].fillna(0.5) +
        0.25 * filtered['r_mom3m'].fillna(0.5) +
        0.20 * filtered['r_eps'].fillna(0.5) +
        0.15 * filtered['r_analyst'].fillna(0.5)
    )
    print(f"✓ EPS Growth + Analyst Revision 사용 (EPS {eps_coverage:.0%}, Analyst {analyst_cov}개)")
elif eps_coverage > 0.3:
    filtered['r_eps'] = rpct(filtered['EPS_Growth'])
    filtered['Momentum_Score'] = W_M * (
        0.5 * filtered['r_mom12m1m'].fillna(0.5) +
        0.3 * filtered['r_mom3m'].fillna(0.5) +
        0.2 * filtered['r_eps'].fillna(0.5)
    )
    print(f"✓ EPS Growth 사용 (커버리지 {eps_coverage:.0%})")
elif analyst_usable:
    filtered['Momentum_Score'] = W_M * (
        0.45 * filtered['r_mom12m1m'].fillna(0.5) +
        0.40 * filtered['r_mom3m'].fillna(0.5) +
        0.15 * filtered['r_analyst'].fillna(0.5)
    )
    print(f"✓ Analyst Revision 사용 (EPS 없음, Analyst {analyst_cov}개)")
else:
    filtered['Momentum_Score'] = W_M * (
        0.5 * filtered['r_mom12m1m'].fillna(0.5) +
        0.5 * filtered['r_mom3m'].fillna(0.5)
    )
    print(f"✓ EPS/Analyst 없음 → Mom 시그널만 사용")

# ── Shared scoring engine ───────────────────────────────────────────────────
# Keep the diagnostics above for transparency, but make the persisted live
# scores come from the same function used by the point-in-time backtest.
filtered, score_diag = compute_us_factor_scores(
    filtered,
    weights=(W_V, W_Q, W_M),
    include_diagnostics=True,
)
print("\n[Scorer] Shared US scoring engine applied")
print(f"  Value coverage   : {score_diag.value_coverage}")
print(f"  Quality coverage : {score_diag.quality_coverage}")
print(f"  Momentum coverage: {score_diag.momentum_coverage}")

# ── Company Quality Core v2 ─────────────────────────────────────────────────
# Separates "good business" from "good candidate to review now". These columns
# are additive diagnostics; existing Total/Final/ML score behavior remains intact.
filtered = compute_company_quality_scores(filtered)
print("\n[Scorer] Company Quality Core v2 applied")
print(
    "  Business quality range : "
    f"{filtered['Business_Quality_Score'].min():.4f} → {filtered['Business_Quality_Score'].max():.4f}"
)
print(
    "  Investability range    : "
    f"{filtered['Investability_Score'].min():.4f} → {filtered['Investability_Score'].max():.4f}"
)

# ── Total Score ───────────────────────────────────────────────────────────────
filtered['Total_Score'] = (
    filtered['Value_Score'] +
    filtered['Quality_Score'] +
    filtered['Momentum_Score']
).round(4)

filtered = filtered.sort_values('Total_Score', ascending=False).reset_index(drop=True)
scored = filtered.dropna(subset=['Total_Score'])

scored = sector_neutralize(scored, score_col='Total_Score')
scored['Final_Score'] = quality_adjusted_final_score(scored)
scored = scored.sort_values('Final_Score', ascending=False).reset_index(drop=True)
scored['Rank'] = range(1, len(scored) + 1)
scored = add_company_quality_review_columns(scored, rank_col='Rank')

print(f"\n📊 점수 산출 완료: {len(scored)}개 / 필터 통과: {len(filtered)}개")
print("\n📊 Sector Neutralization applied:")
for sector, group in scored.groupby('Sector'):
    print(f"  {sector:<30} {len(group):>3} stocks  Final_Score mean: {group['Final_Score'].mean():.3f}")
if len(scored):
    print(f"📊 Top score : {scored['Total_Score'].iloc[0]:.4f}")
    print(f"📊 Bottom score: {scored['Total_Score'].iloc[-1]:.4f}")

# ── Build US_Scored_Stocks with standard schema ──────────────────────────────────
scored = scored.copy()
scored['Rank']         = range(1, len(scored) + 1)
scored['Market']       = 'US'
scored['Last_Updated'] = datetime.now().strftime('%Y-%m-%d')

# Ensure Name/Sector are populated (fallback to Ticker)
if 'Name' not in scored.columns or scored['Name'].isna().all():
    scored['Name'] = scored['Ticker']
scored['Name']   = scored['Name'].fillna(scored['Ticker'])
if 'Sector' not in scored.columns:
    scored['Sector'] = ''
scored['Sector'] = scored['Sector'].fillna('')

# Round all numeric output to 4 decimal places
num_cols = ['Value_Score', 'Quality_Score', 'Momentum_Score', 'Total_Score',
            'Final_Score', 'Score_Neutral',
            *[c for c in QUALITY_SCORE_COLS if c != 'Quality_Red_Flags'],
            *[c for c in QUALITY_REVIEW_COLS if c != 'Quality_Category'],
            'ROIC', 'RevGrowth', 'GrossMargin', 'FCF_Margin', 'Debt_EBITDA', 'PEG']
for col in num_cols:
    if col in scored.columns:
        scored[col] = pd.to_numeric(scored[col], errors='coerce').round(4)

# Select standard columns (fill any missing with "")
for col in SCORED_COLS:
    if col not in scored.columns:
        scored[col] = ''
result_all = drop_banned_ticker_rows(scored[SCORED_COLS].copy())
result_all['Rank'] = range(1, len(result_all) + 1)
sheet_out = result_all.fillna('').astype(str)

# ── Save US_Scored_Stocks ────────────────────────────────────────────────────────
try:
    ss = spreadsheet.worksheet('US_Scored_Stocks')
except Exception:
    ss = spreadsheet.add_worksheet('US_Scored_Stocks', rows=1000, cols=len(SCORED_COLS) + 2)
cache.flush(label='US factor scorer')   # batch-write any buffered cache updates
ss.clear()
ss.update([sheet_out.columns.tolist()] + sheet_out.values.tolist())
print(f"\n✅ US_Scored_Stocks saved: {len(result_all)} stocks  |  columns: {SCORED_COLS}")
dual_write_dataframe("US_Scored_Stocks", result_all, market="US")

cache.print_stats()
print("✅ US Factor Scorer complete!")
