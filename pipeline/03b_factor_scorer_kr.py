# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
"""
03_factor_scorer_kr.py  ─  Korean Factor Scorer
=================================================
Reads  : KR_Universe  (standardised KRW fundamentals from 01_universe_expander.py)
Writes : KR_Scored_Stocks   – all filter-passing KR stocks with V/Q/M scores

Portfolio construction (KR_Final_Portfolio) is handled by 06b_portfolio_optimizer_kr.py
using the same risk-parity approach as the US pipeline.

Column schemas match US counterparts exactly (Market='KR' differentiates).

Metrics used (Naver Finance + yfinance):
  Value   : PER ↓  PBR ↓  RevGrowth ↑  DivYield ↑  (growth-adjusted value)
  Quality : ROE ↑  OperatingMargin ↑  DebtToEquity ↓
  Momentum: (Mom_12M − Mom_1M) ↑  Mom_3M ↑

Weights:
  Total_Score = Value_Score(0.40) + Quality_Score(0.35) + Momentum_Score(0.25)
  Value breakdown: PER 0.40 | PBR 0.25 | RevGrowth 0.25 | DivYield 0.10
"""

import gspread
import yfinance as yf
from sheets_client import get_spreadsheet
import pandas as pd
import numpy as np
import time
from datetime import datetime

# ── Cache integration ─────────────────────────────────────────────────────────
from cache_manager import CacheManager
from kr_sector_map import UNCLASSIFIED_SECTOR, load_kr_sector_map, sector_for_ticker
from pipeline.factor_policy_runtime import apply_factor_policy_weights
from quantbridge.writers.dual_write import dual_write_dataframe

# ── Google Sheets connection ──────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()

# Initialise cache
cache = CacheManager(spreadsheet, verbose=True)


# ── Macro regime factor weights ───────────────────────────────────────────────
def _load_macro_weights(ss, market: str, defaults: tuple) -> tuple:
    """
    Read V/Q/M factor weights from the Macro_Regime sheet.
    Falls back to defaults if sheet is missing, stale (>48h), or unparseable.
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


# KR defaults: Value=0.40, Quality=0.35, Momentum=0.25
W_V, W_Q, W_M = _load_macro_weights(spreadsheet, 'KR', (0.40, 0.35, 0.25))
_policy_weights = apply_factor_policy_weights(
    spreadsheet,
    'KR',
    {'Value_Score': W_V, 'Quality_Score': W_Q, 'Momentum_Score': W_M},
)
W_V = _policy_weights['Value_Score']
W_Q = _policy_weights['Quality_Score']
W_M = _policy_weights['Momentum_Score']

# ── Test mode ───────────────────────────────────────────────────────────────────
TEST_MODE    = os.environ.get('QUANT_TEST_MODE')    == 'true'
ANALYZE_ONLY = os.environ.get('QUANT_ANALYZE_ONLY') == 'true'
TEST_LIMIT = 50
if TEST_MODE:
    print("\n⚠️  TEST MODE : KR scoring limited to 50 stocks")
if ANALYZE_ONLY:
    print("\n⚡ ANALYZE-ONLY : using cached fundamentals, skipping momentum download")

# ── Standard column schemas ───────────────────────────────────────────────────
SCORED_COLS = [
    'Rank', 'Ticker', 'Name', 'Market', 'Sector', 'MarketCap',
    'Value_Score', 'Quality_Score', 'Momentum_Score', 'Total_Score',
    'Final_Score', 'Score_Neutral',
    'ROIC', 'RevGrowth', 'GrossMargin', 'FCF_Margin', 'Debt_EBITDA', 'PEG',
    'Last_Updated',
]
# 19 columns — Final_Score and Score_Neutral inserted after Total_Score
# mirrors US_Scored_Stocks schema (02_factor_scorer_us.py)


def _to_float(v):
    try:
        return float(v) if v not in ('', None) else None
    except (ValueError, TypeError):
        return None


def sector_neutralize(df: pd.DataFrame, score_col: str = 'Total_Score') -> pd.DataFrame:
    """
    Z-score normalise Total_Score within each sector group.
    Sectors with fewer than 5 stocks keep their original score unchanged
    (not enough peers for meaningful cross-sectional normalisation).
    Returns df with 'Score_Neutral' column added.
    """
    df = df.copy()
    df['Score_Neutral'] = df[score_col]
    for sector, group in df.groupby('Sector'):
        if not str(sector).strip() or str(sector).strip() == UNCLASSIFIED_SECTOR:
            continue
        if len(group) < 5:
            continue
        mu  = group[score_col].mean()
        std = group[score_col].std()
        if std > 0:
            df.loc[group.index, 'Score_Neutral'] = (group[score_col] - mu) / std
    return df


# ── Load KR_Universe ──────────────────────────────────────────────────────
print("\n" + "=" * 65)
print("  KR SCORER  ─  Loading KR_Universe sheet")
print("=" * 65)

try:
    ku_sheet = spreadsheet.worksheet("KR_Universe")
except gspread.exceptions.WorksheetNotFound:
    print("❌  KR_Universe sheet not found. Run 01_universe_expander.py first.")
    exit(1)

raw = ku_sheet.get_all_values()
if not raw or len(raw) < 2:
    print("❌  KR_Universe is empty. Run 01_universe_expander.py first.")
    exit(1)

df = pd.DataFrame(raw[1:], columns=raw[0])
print(f"  Loaded {len(df)} rows, {len(df.columns)} columns")

try:
    kr_sector_map = load_kr_sector_map(spreadsheet)
except Exception:
    kr_sector_map = {}

if 'Sector' not in df.columns:
    df['Sector'] = ''
df['Sector'] = df.apply(
    lambda r: sector_for_ticker(r.get('Ticker'), kr_sector_map, r.get('Sector', '')),
    axis=1,
)

# ── Coerce numeric columns ────────────────────────────────────────────────────
NUMERIC = ['MarketCap', 'PER', 'PBR', 'ROE', 'Revenue', 'RevenueGrowth',
           'OperatingMargin', 'GrossMargin', 'DebtToEquity']
for col in NUMERIC:
    if col in df.columns:
        df[col] = pd.to_numeric(df[col], errors='coerce')
    else:
        df[col] = np.nan

# ── 1차 필터 (Korean-market adapted) ─────────────────────────────────────────
# Volume no longer in KR_Universe; use cache Volume_Last as proxy.
# MarketCap > 50B KRW ensures minimal liquidity.
pre = len(df)

pre_filtered = df[
    (df['MarketCap']      > 5e10) &           # 500억 KRW proxy for liquidity
    (df['ROE']             > 0.05) &
    (df['OperatingMargin'] > 0.03) &
    (df['DebtToEquity']    < 200) &
    (df['PER']             > 0) &
    (df['PER']             < 60)
].copy()

# Volume check from cache (KRX volume threshold lower than US)
pre_filtered['_volume'] = pre_filtered['Ticker'].apply(
    lambda t: _to_float(cache.get_row(t).get('Volume_Last')))
filtered = pre_filtered[
    pre_filtered['_volume'].isna() | (pre_filtered['_volume'] > 100_000)
].copy()
filtered.drop(columns=['_volume'], inplace=True)

if TEST_MODE:
    filtered = filtered.head(TEST_LIMIT)
    print(f"⚠️  TEST MODE: trimmed to {len(filtered)} KR stocks")
print(f"\n  1차 필터 통과: {len(filtered)} / {pre} stocks")

if len(filtered) < 5:
    print("⚠️  Too few stocks — relaxing thresholds.")
    filtered = df[
        (df['MarketCap']      > 1e10) &
        (df['ROE']             > 0.02) &
        (df['OperatingMargin'] > 0) &
        (df['DebtToEquity']    < 300) &
        (df['PER']             > 0) &
        (df['PER']             < 100)
    ].copy()
    print(f"  완화 필터 통과: {len(filtered)} stocks")

# ── Fetch deep fundamentals from cache ────────────────────────────────────────
tickers_filtered = filtered['Ticker'].tolist()
if not ANALYZE_ONLY:
    print(f"\n[KR Scorer] Pre-warming cache for {len(tickers_filtered)} tickers...")
    cache.prefetch(tickers_filtered, delay=0.3)
else:
    print(f"\n[KR Scorer] ANALYZE-ONLY: reading {len(tickers_filtered)} tickers from existing cache")

deep_rows = {}
for i, ticker in enumerate(tickers_filtered, 1):
    rec = dict(ROIC=None, RevGrowth=None, GrossMargin=None,
               FCF_Margin=None, Debt_EBITDA=None, PEG=None, Sector=None,
               DivYield=None,
               TotalAssets=None, CurrentAssets=None, CurrentLiabilities=None,
               RetainedEarnings=None, TotalLiabilities=None)
    try:
        fin_data = cache.load_or_fetch_financials(ticker)
        rec['ROIC']             = fin_data.get('ROIC')
        rec['RevGrowth']        = fin_data.get('RevGrowth')
        rec['GrossMargin']      = fin_data.get('GrossMargin')
        rec['FCF_Margin']       = fin_data.get('FCF_Margin')
        rec['Debt_EBITDA']      = fin_data.get('Debt_EBITDA')
        rec['PEG']              = fin_data.get('PEG')
        rec['Sector']           = fin_data.get('Sector', '')
        rec['DivYield']         = fin_data.get('DivYield')
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

for col in ['ROIC', 'RevGrowth', 'FCF_Margin', 'Debt_EBITDA', 'PEG',
            'DivYield',
            'TotalAssets', 'CurrentAssets', 'CurrentLiabilities',
            'RetainedEarnings', 'TotalLiabilities']:
    old_col = col + '_old'
    if old_col in filtered.columns:
        filtered[col] = filtered[col].combine_first(filtered[old_col])
        filtered.drop(columns=[old_col], inplace=True)
    filtered[col] = pd.to_numeric(filtered[col], errors='coerce')

# GrossMargin: prefer Naver value (already in KR_Universe) > cache
for col in ['GrossMargin']:
    old_col = col + '_old'
    if old_col in filtered.columns:
        # Keep KR_Universe value if present (it's from Naver — more accurate)
        filtered[col] = filtered[old_col].combine_first(filtered[col])
        filtered.drop(columns=[old_col], inplace=True)
    filtered[col] = pd.to_numeric(filtered[col], errors='coerce')

# Sector merge
for col in ['Sector']:
    old_col = col + '_old'
    if old_col in filtered.columns:
        filtered[col] = filtered[col].fillna(filtered[old_col])
        filtered.drop(columns=[old_col], inplace=True)
if 'Sector' not in filtered.columns:
    filtered['Sector'] = ''
filtered['Sector'] = filtered.apply(
    lambda r: sector_for_ticker(r.get('Ticker'), kr_sector_map, r.get('Sector', '')),
    axis=1,
)

# ── Download momentum data for filtered KR tickers ───────────────────────────
BATCH = 100
price_frames = []
if not ANALYZE_ONLY:
    print(f"\n[KR Scorer] Downloading 1yr price history for {len(tickers_filtered)} tickers...")
    for i in range(0, len(tickers_filtered), BATCH):
        batch = tickers_filtered[i:i + BATCH]
        try:
            raw_prices = yf.download(batch, period='1y', auto_adjust=True, progress=False)
            closes = raw_prices['Close'] if isinstance(raw_prices.columns, pd.MultiIndex) else raw_prices
            if isinstance(closes, pd.Series):
                closes = closes.to_frame(name=batch[0])
            price_frames.append(closes)
        except Exception as e:
            print(f"  batch error: {e}")
        time.sleep(0.5)
else:
    print("\n[KR Scorer] ANALYZE-ONLY: skipping price download — Momentum_Score = 0")

if price_frames:
    prices = pd.concat(price_frames, axis=1)
    prices = prices.loc[:, ~prices.columns.duplicated()]

    def _mom(ticker):
        if ticker not in prices.columns:
            return pd.Series({'Mom_1M': None, 'Mom_3M': None,
                              'Mom_12M': None, 'Volatility': None})
        s = prices[ticker].dropna()
        if len(s) < 100:
            return pd.Series({'Mom_1M': None, 'Mom_3M': None,
                              'Mom_12M': None, 'Volatility': None})
        curr = s.iloc[-1]
        vol  = s.pct_change().dropna().std() * np.sqrt(252)
        return pd.Series({
            'Mom_1M':    round((curr / s.iloc[-21]) - 1, 4) if len(s) >= 21 else None,
            'Mom_3M':    round((curr / s.iloc[-63]) - 1, 4) if len(s) >= 63 else None,
            'Mom_12M':   round((curr / s.iloc[0])  - 1, 4),
            'Volatility': round(float(vol), 4) if not np.isnan(vol) else None,
        })

    mom_df = pd.DataFrame([_mom(t) for t in tickers_filtered], index=tickers_filtered)
    mom_df.index.name = 'Ticker'
    mom_df = mom_df.reset_index()
    filtered = filtered.merge(mom_df, on='Ticker', how='left')
else:
    for col in ['Mom_1M', 'Mom_3M', 'Mom_12M', 'Volatility']:
        filtered[col] = np.nan

for col in ['Mom_1M', 'Mom_3M', 'Mom_12M', 'Volatility']:
    filtered[col] = pd.to_numeric(filtered[col], errors='coerce')

# ── Helper: percentile rank ───────────────────────────────────────────────────
def rpct(s):
    return s.rank(pct=True, na_option='keep')

def value_rank(series):
    inv = (1.0 / series).where(series > 0)
    return inv.rank(pct=True, na_option='keep')

# ── Value Score ───────────────────────────────────────────────────────────────
# Growth-adjusted value: RevGrowth added so high-growth KOSDAQ/tech names are not
# purely penalised by absolute PER/PBR multiples alone.
#   PER 0.40 | PBR 0.25 | RevGrowth 0.25 | DivYield 0.10  (sum = 1.00)
filtered['r_per'] = value_rank(filtered['PER'])
filtered['r_pbr'] = value_rank(filtered['PBR'])

# Revenue growth: higher growth = growth premium within value factor
filtered['r_revgrowth'] = filtered['RevGrowth'].rank(pct=True, na_option='keep')

# Dividend yield: higher yield = better value; 0-div stocks treated as neutral (0.5), not penalised
_div_pos_kr = filtered['DivYield'].where(filtered['DivYield'] > 0)
filtered['r_divyield'] = _div_pos_kr.rank(pct=True, na_option='keep')

per_cov = filtered['r_per'].notna().sum()
pbr_cov = filtered['r_pbr'].notna().sum()
rg_cov  = filtered['r_revgrowth'].notna().sum()
div_cov = filtered['r_divyield'].notna().sum()
print(f"\n  Value coverage → PER: {per_cov}, PBR: {pbr_cov}, RevGrowth: {rg_cov}, DivYield: {div_cov} / {len(filtered)}")

all_val_missing = (filtered['r_per'].isna() & filtered['r_pbr'].isna() &
                   filtered['r_revgrowth'].isna() & filtered['r_divyield'].isna())
filtered['Value_Score'] = np.where(
    all_val_missing, np.nan,
    W_V * (
        0.40 * filtered['r_per'].fillna(0.5) +
        0.25 * filtered['r_pbr'].fillna(0.5) +
        0.25 * filtered['r_revgrowth'].fillna(0.5) +
        0.10 * filtered['r_divyield'].fillna(0.5)
    )
)

# ── Altman Z-Score (KR) ───────────────────────────────────────────────────────
# Formula is identical to the US version (Altman 1968, originally calibrated for
# US public companies). For KR stocks, all values are in KRW — units cancel out
# in each ratio so the Z value is numerically comparable across markets.
# Used as a relative distress signal between KR peers, not as an absolute predictor.
#
# Zones (Altman 1968 thresholds):
#   Z > 2.99  → Safe      (low bankruptcy risk)
#   1.81–2.99 → Grey zone
#   Z < 1.81  → Distress
def compute_altman_z_kr(row) -> float:
    """
    Z = 1.2*X1 + 1.4*X2 + 3.3*X3 + 0.6*X4 + 1.0*X5

    X1 = (CurrentAssets - CurrentLiabilities) / TotalAssets   (working capital ratio)
    X2 = RetainedEarnings / TotalAssets                        (cumulative profitability)
    X3 = EBIT / TotalAssets  → proxied by ROIC (= OpIncome*(1-tax)/InvestedCapital ≈ OpIncome/TA)
    X4 = MarketCap / TotalLiabilities                          (market leverage)
    X5 = Revenue / TotalAssets                                 (asset turnover)

    All monetary values in KRW. Revenue from KR_Universe (Naver, reliable).
    MarketCap from KR_Universe (already in KRW).
    """
    try:
        ta = float(row.get('TotalAssets') or 0)
        if ta == 0:
            return np.nan
        ca  = float(row.get('CurrentAssets')      or 0)
        cl  = float(row.get('CurrentLiabilities') or 0)
        re  = float(row.get('RetainedEarnings')   or 0)
        tl  = float(row.get('TotalLiabilities')   or 1e-9)
        mc  = float(row.get('MarketCap')          or 0)   # KRW, from KR_Universe
        rev = float(row.get('Revenue')            or 0)   # KRW, from KR_Universe
        op_margin = float(row.get('OperatingMargin') or 0)
        revenue   = float(row.get('Revenue')        or 0)
        ebit = op_margin * revenue   # EBIT proxy: OpMargin × Revenue = Operating Income

        x1 = (ca - cl) / ta
        x2 = re / ta
        x3 = ebit / ta
        x4 = mc / tl
        x5 = rev / ta
        return round(1.2*x1 + 1.4*x2 + 3.3*x3 + 0.6*x4 + 1.0*x5, 4)
    except Exception:
        return np.nan

filtered['AltmanZ']  = filtered.apply(compute_altman_z_kr, axis=1)
filtered['r_altman'] = rpct(filtered['AltmanZ'])

altman_cov  = filtered['AltmanZ'].notna().sum()
safe_n      = (filtered['AltmanZ'] > 2.99).sum()
grey_n      = ((filtered['AltmanZ'] >= 1.81) & (filtered['AltmanZ'] <= 2.99)).sum()
distress_n  = (filtered['AltmanZ'] < 1.81).sum()
print(f"\n📊 KR Altman Z-Score: {altman_cov}/{len(filtered)} 계산  "
      f"| Safe(>2.99): {safe_n}  Grey(1.81-2.99): {grey_n}  Distress(<1.81): {distress_n}")

# ── Accruals Quality Signal (KR) ──────────────────────────────────────────────
# FCF_Margin / OperatingMargin: 영업이익 대비 실제 현금 전환 효율
# 높을수록 회계 이익이 현금으로 뒷받침됨 → 발생주의 이상 리스크 낮음
_kr_accruals = filtered['FCF_Margin'] / filtered['OperatingMargin'].replace(0, np.nan)
filtered['Accruals_EQ'] = _kr_accruals
filtered['r_accruals']  = rpct(_kr_accruals)
accruals_cov_kr = filtered['r_accruals'].notna().sum()
print(f"  Accruals (EQ) coverage: {accruals_cov_kr} / {len(filtered)}")

# ── Quality Score ─────────────────────────────────────────────────────────────
filtered['r_roe']    = rpct(filtered['ROE'])
filtered['r_opmgn']  = rpct(filtered['OperatingMargin'])
filtered['r_de_inv'] = value_rank(filtered['DebtToEquity'])

roe_cov   = filtered['r_roe'].notna().sum()
opmgn_cov = filtered['r_opmgn'].notna().sum()
de_cov    = filtered['r_de_inv'].notna().sum()
print(f"  Quality coverage → ROE: {roe_cov}, OpMgn: {opmgn_cov}, D/E: {de_cov}, "
      f"AltmanZ: {altman_cov}, Accruals: {accruals_cov_kr} / {len(filtered)}")

# 5-factor Quality Score (outer weight = W_Q from macro regime):
#   ROE 30% + OpMargin 25% + D/E inverse 20% + AltmanZ 15% + Accruals 10%
all_qual_missing = filtered['r_roe'].isna() & filtered['r_opmgn'].isna()
filtered['Quality_Score'] = np.where(
    all_qual_missing, np.nan,
    W_Q * (
        0.30 * filtered['r_roe'].fillna(0.5) +
        0.25 * filtered['r_opmgn'].fillna(0.5) +
        0.20 * filtered['r_de_inv'].fillna(0.5) +
        0.15 * filtered['r_altman'].fillna(0.5) +
        0.10 * filtered['r_accruals'].fillna(0.5)
    )
)

# ── Momentum Score ────────────────────────────────────────────────────────────
filtered['Mom_12M_1M'] = filtered['Mom_12M'] - filtered['Mom_1M']

filtered['r_mom12m1m'] = rpct(filtered['Mom_12M_1M'])
filtered['r_mom3m']    = rpct(filtered['Mom_3M'])

mom12_cov = filtered['r_mom12m1m'].notna().sum()
mom3_cov  = filtered['r_mom3m'].notna().sum()
print(f"  Momentum coverage → 12M-1M: {mom12_cov}, 3M: {mom3_cov} / {len(filtered)}")

all_mom_missing = filtered['r_mom12m1m'].isna() & filtered['r_mom3m'].isna()
filtered['Momentum_Score'] = np.where(
    all_mom_missing, np.nan,
    W_M * (
        0.60 * filtered['r_mom12m1m'].fillna(0.5) +
        0.40 * filtered['r_mom3m'].fillna(0.5)
    )
)

# ── Total Score ───────────────────────────────────────────────────────────────
filtered['Total_Score'] = (
    filtered['Value_Score'].fillna(0) +
    filtered['Quality_Score'].fillna(0) +
    filtered['Momentum_Score'].fillna(0)
).where(
    filtered['Value_Score'].notna() |
    filtered['Quality_Score'].notna() |
    filtered['Momentum_Score'].notna(),
    other=np.nan
).round(4)

filtered = filtered.sort_values('Total_Score', ascending=False).reset_index(drop=True)
scored    = filtered.dropna(subset=['Total_Score'])

# ── Sector Neutralization ─────────────────────────────────────────────────────
scored = sector_neutralize(scored, score_col='Total_Score')
scored['Final_Score'] = (
    0.6 * scored['Total_Score'].rank(pct=True) +
    0.4 * scored['Score_Neutral'].rank(pct=True)
).round(4)
scored = scored.sort_values('Final_Score', ascending=False).reset_index(drop=True)
scored['Rank'] = range(1, len(scored) + 1)

print(f"\n  점수 산출 완료 : {len(scored)} / 필터 통과 : {len(filtered)}")
print("\n📊 Sector Neutralization applied (KR):")
for sector, group in scored.groupby('Sector'):
    if sector == '':
        sector = '(없음)'
    print(f"  {sector:<30} {len(group):>3}종목  Final_Score 평균: {group['Final_Score'].mean():.3f}")
if len(scored):
    print(f"\n  Top Final_Score  : {scored['Final_Score'].iloc[0]:.4f}  ({scored['Ticker'].iloc[0]})")
    print(f"  Bottom Final_Score: {scored['Final_Score'].iloc[-1]:.4f}")

# ── Worksheet helper ──────────────────────────────────────────────────────────
def get_or_create(name, rows=600, cols=20):
    try:
        return spreadsheet.worksheet(name)
    except gspread.exceptions.WorksheetNotFound:
        return spreadsheet.add_worksheet(title=name, rows=rows, cols=cols)

# ── KR_Scored_Stocks (standard schema) ───────────────────────────────────────
scored_out = scored.copy()
scored_out['Rank']         = range(1, len(scored_out) + 1)
scored_out['Market']       = 'KR'
scored_out['Last_Updated'] = datetime.now().strftime('%Y-%m-%d')

# Ensure Name is populated
if 'Name' not in scored_out.columns:
    scored_out['Name'] = scored_out['Ticker']
scored_out['Name'] = scored_out['Name'].fillna(scored_out['Ticker'])

# Round numeric columns to 4 decimal places
num_cols = ['Value_Score', 'Quality_Score', 'Momentum_Score', 'Total_Score',
            'Final_Score', 'Score_Neutral',
            'ROIC', 'RevGrowth', 'GrossMargin', 'FCF_Margin', 'Debt_EBITDA', 'PEG']
for col in num_cols:
    if col in scored_out.columns:
        scored_out[col] = pd.to_numeric(scored_out[col], errors='coerce').round(4)

# Select standard columns
for col in SCORED_COLS:
    if col not in scored_out.columns:
        scored_out[col] = ''
df_scored = scored_out[SCORED_COLS].copy()
sheet_out = df_scored.fillna('').astype(str)

ws_scored = get_or_create("KR_Scored_Stocks", rows=4200, cols=len(SCORED_COLS) + 2)
cache.flush(label='KR factor scorer')   # batch-write any buffered cache updates
ws_scored.clear()
ws_scored.update([sheet_out.columns.tolist()] + sheet_out.values.tolist())
print(f"\n✅  KR_Scored_Stocks  → {len(df_scored)} rows  |  columns: {SCORED_COLS}")
dual_write_dataframe("KR_Scored_Stocks", df_scored, market="KR")

cache.print_stats()
print("\n✅ KR Factor Scorer complete!")
print("   KR_Final_Portfolio will be built by 06b_portfolio_optimizer_kr.py (risk-parity)")
