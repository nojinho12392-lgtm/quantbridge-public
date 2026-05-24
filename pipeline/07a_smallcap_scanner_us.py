# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
"""
07a_smallcap_scanner_us.py  ─  US Small-Cap 10-Bagger Hunter  [Cache-Enabled]
==============================================================================
Universe  : Russell 2000 / S&P 600   $100M ~ $1B  (MarketCap USD)
Output    : US_SmallCap_Gems  (Top 20)

Scoring (max ~161 pts) — 텐배거 기준:
  ROIC            25pts  (>30%=25, >20%=20, >10%=12, >0%=4; <-10%=-5 완화)
  RevGrowth       30pts  (>50%=30, >25%=24, >10%=15, >0%=6)
  Rule of 40      15pts  (RevGrowth% + FCF_Margin% ≥60=15, ≥40=10, ≥20=5)
  GrossMargin     15pts  (>50%=15, >35%=12, >20%=7, >0%=3)
  FCF_Margin      10pts  (>10%=10, >5%=7, >0%=5; 음수 페널티 제거)
  Debt_EBITDA     10pts  (<3x=10, <5x=6; >8x=-5 penalty; missing=5 neutral)
  Rev_Accel       10pts  (분기 YoY 가속도; +5%p이상=10, 소폭=6, 보합=3)
  Insider_Pct     10pts  (>15%=10, >5%=6, >1%=2  — 내부자 보유 = 경영진 신뢰)
  Net_Cash_Ratio   8pts  (순현금/시총: >20%=8, >0%=4; <-50%=-5 페널티)
  Volume_Surge     5pts  (>3x=5, >1.5x=3; 보조지표로 축소)
  SmallCap_Bonus  15pts  (시총이 $100M에 가까울수록 최대 15pts)
  Short_Interest   8pts  (>30%=8, >20%=5, >10%=2  — 숏스퀴즈 잠재력)

필터:
  금융/보험/리츠 업종 제외 (GrossMargin·FCF 지표 비교 불가)
  데이터 품질 게이트: ROIC·RevGrowth·GrossMargin 중 2개 이상 유효해야 통과

Run standalone : python pipeline/07a_smallcap_scanner_us.py
Run via engine : main_engine.py --ussmallcap
"""

# ── Imports ───────────────────────────────────────────────────────────────────
import gspread
import yfinance as yf
from sheets_client import get_spreadsheet
import pandas as pd
import requests
import time
import warnings
import math
import random
from bs4 import BeautifulSoup
from io import StringIO
from cache_manager import CacheManager
from quantbridge.writers.dual_write import dual_write_dataframe

warnings.filterwarnings('ignore')

# ── Google Sheets auth ────────────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds",
         "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) '
                  'AppleWebKit/537.36 (KHTML, like Gecko) '
                  'Chrome/120.0.0.0 Safari/537.36'
}

# ── Constants ─────────────────────────────────────────────────────────────────
MCAP_MIN = 1e8    # $100M
MCAP_MAX = 1e9    # $1B
TOP_N    = 20

# 금융/보험/리츠: GrossMargin·FCF_Margin 지표가 사업체와 비교 불가 → 제외
_EXCLUDED_SECTORS = {
    'financial services', 'insurance', 'banks', 'real estate',
    'asset management', 'capital markets', 'mortgage finance',
    'diversified financials', 'thrifts & mortgage finance',
}

SMALLCAP_COLS = [
    'Rank', 'Ticker', 'Name', 'Market', 'MarketCap',
    'ROIC', 'RevGrowth', 'Rev_Accel', 'GrossMargin', 'FCF_Margin',
    'Debt_EBITDA', 'Insider_Pct', 'Net_Cash_Ratio',
    'Volume_Surge', 'SmallCap_Bonus', 'Data_Confidence', 'Total_Score', 'Last_Updated',
]

# ── Utilities ─────────────────────────────────────────────────────────────────
def is_valid(v):
    return v is not None and not (isinstance(v, float) and math.isnan(v))

def data_confidence(roic, rev_growth, gross_margin, fcf_margin,
                    debt_ebitda, rev_accel, volume_surge,
                    insider_pct=None, net_cash_ratio=None):
    """
    Convert metric coverage into a conservative score multiplier.

    Small-cap data is sparse; missing inputs should not earn the same score as
    observed fundamentals. The gate still requires 2/3 core metrics, then this
    multiplier haircuts names whose remaining evidence is thin.
    """
    weights = [
        (roic, 0.18), (rev_growth, 0.18), (gross_margin, 0.18),
        (fcf_margin, 0.14), (debt_ebitda, 0.10), (rev_accel, 0.08),
        (net_cash_ratio, 0.06), (insider_pct, 0.04), (volume_surge, 0.04),
    ]
    coverage = sum(weight for value, weight in weights if is_valid(value))
    if coverage >= 0.80:
        return 1.00
    if coverage >= 0.65:
        return 0.92
    if coverage >= 0.50:
        return 0.85
    return 0.75

def smallcap_bonus(mcap):
    """Smaller market-cap → higher bonus (max 15 pts)."""
    try:
        if not mcap or mcap <= 0:
            return 0.0
        n = (mcap - MCAP_MIN) / (MCAP_MAX - MCAP_MIN)
        return round((1 - max(0.0, min(1.0, n))) * 15, 2)
    except Exception:
        return 0.0

def compute_rev_acceleration(yf_ticker):
    """
    Quarterly YoY revenue growth acceleration (분기 YoY 가속도).

    Returns delta YoY as a float (e.g. 0.08 = acceleration of +8 percentage points)
    or None if fewer than 6 quarterly data points are available.

    Logic:
      recent_yoy = (Q0 − Q4) / |Q4|    ← most recent quarter vs same qtr 1yr ago
      prior_yoy  = (Q1 − Q5) / |Q5|    ← prior quarter vs same qtr 1yr ago
      acceleration = recent_yoy − prior_yoy
    """
    try:
        qf = yf_ticker.quarterly_financials
        if qf is None or qf.empty:
            return None
        rev_row = None
        for label in ['Total Revenue', 'Revenue', 'Net Revenue', 'TotalRevenue']:
            if label in qf.index:
                rev_row = qf.loc[label].dropna().sort_index(ascending=False)
                break
        if rev_row is None or len(rev_row) < 6:
            return None
        q0, q1 = float(rev_row.iloc[0]), float(rev_row.iloc[1])
        q4, q5 = float(rev_row.iloc[4]), float(rev_row.iloc[5])
        if abs(q4) < 1 or abs(q5) < 1:
            return None
        recent_yoy = (q0 - q4) / abs(q4)
        prior_yoy  = (q1 - q5) / abs(q5)
        return round(recent_yoy - prior_yoy, 4)
    except Exception:
        return None


def score_stock(roic, rev_growth, gross_margin, fcf_margin,
                debt_ebitda, rev_accel, volume_surge, mcap,
                short_interest=None, insider_pct=None, net_cash_ratio=None):
    """Return (total_score, smallcap_bonus) tuple. Max ~161 pts."""
    s = 0

    # ROIC  25 pts max  /  -5 pts penalty (완화: 성장 재투자 기업 배려)
    if is_valid(roic):
        if roic > 0.30:    s += 25
        elif roic > 0.20:  s += 20
        elif roic > 0.10:  s += 12
        elif roic > 0:     s += 4
        elif roic > -0.10: s += 0
        else:              s -= 5

    # Revenue Growth  30 pts max (가장 강력한 텐배거 예측 변수)
    if is_valid(rev_growth):
        if rev_growth > 0.50:   s += 30
        elif rev_growth > 0.25: s += 24
        elif rev_growth > 0.10: s += 15
        elif rev_growth > 0:    s += 6

    # Rule of 40  15 pts max  (RevGrowth% + FCF_Margin% ≥ 40 = 성장-수익 균형)
    if is_valid(rev_growth) and is_valid(fcf_margin):
        r40 = (rev_growth * 100) + (fcf_margin * 100)
        if r40 >= 60:   s += 15
        elif r40 >= 40: s += 10
        elif r40 >= 20: s += 5

    # Gross Margin  15 pts  (소프트웨어/SaaS 고마진 구조 우대)
    if is_valid(gross_margin):
        if gross_margin > 0.50:   s += 15
        elif gross_margin > 0.35: s += 12
        elif gross_margin > 0.20: s += 7
        elif gross_margin > 0:    s += 3

    # FCF Margin  10 pts max  (음수 페널티 없음: 재투자 모드 기업 보호)
    if is_valid(fcf_margin):
        if fcf_margin > 0.10:   s += 10
        elif fcf_margin > 0.05: s += 7
        elif fcf_margin > 0:    s += 5

    # Debt/EBITDA  10 pts max  /  -5 pts penalty  (5 pts neutral if missing)
    if is_valid(debt_ebitda) and debt_ebitda > 0:
        if debt_ebitda < 3:   s += 10
        elif debt_ebitda < 5: s += 6
        elif debt_ebitda < 8: s += 0
        else:                 s -= 5
    else:
        s += 5

    # Revenue Acceleration  10 pts max (분기 YoY 가속도 — 가장 강력한 선행지표)
    # None = yfinance 분기 데이터 부족 (신규상장/소형주) → 중립 3점 (불이익 방지)
    if is_valid(rev_accel):
        if rev_accel > 0.05:    s += 10
        elif rev_accel > 0.01:  s += 6
        elif rev_accel > -0.05: s += 3
    else:
        s += 3

    # Insider Ownership  10 pts max  (피터 린치: 내부자 보유 = 최강 신뢰 시그널)
    if is_valid(insider_pct) and insider_pct > 0:
        if insider_pct > 0.15:   s += 10  # >15% — 창업자/경영진 강한 보유
        elif insider_pct > 0.05: s += 6   # >5%  — 유의미한 내부자 보유
        elif insider_pct > 0.01: s += 2   # >1%  — 소폭 보유

    # Net Cash Position  8 pts max / -5 pts penalty
    # 소형주 파산 리스크 방어 + 성장 투자 여력 평가
    if is_valid(net_cash_ratio):
        if net_cash_ratio > 0.20:    s += 8   # 순현금 > 시총의 20% — 강력한 안전마진
        elif net_cash_ratio > 0:     s += 4   # 순현금 양수
        elif net_cash_ratio < -0.50: s -= 5   # 순부채 > 시총의 50% — 위험 구간

    # Volume Surge  5 pts  (보조지표로 축소 — 후행 확인용)
    if is_valid(volume_surge):
        s += 5 if volume_surge > 3 else (3 if volume_surge > 1.5 else 0)

    # Short Interest  8 pts max  (역발상 + 숏스퀴즈 잠재력 — 우량 펀더멘털 전제)
    if is_valid(short_interest) and short_interest > 0:
        if short_interest > 0.30:   s += 8
        elif short_interest > 0.20: s += 5
        elif short_interest > 0.10: s += 2

    # SmallCap Bonus  15 pts
    bonus = smallcap_bonus(mcap)
    s += bonus

    return round(s, 2), bonus


# ── Universe fetch ────────────────────────────────────────────────────────────
def get_tickers():
    """iShares IWM (Russell 2000)  →  S&P 600 (Wikipedia) fallback."""

    # Method 1: iShares IWM ETF CSV
    try:
        print("  Fetching Russell 2000 (iShares IWM)...")
        url = ("https://www.ishares.com/us/products/239710/"
               "ishares-russell-2000-etf/1467271812596.ajax"
               "?fileType=csv&fileName=IWM_holdings&dataType=fund")
        resp = requests.get(url, headers=HEADERS, timeout=20)
        if resp.status_code == 200:
            lines = resp.text.split('\n')
            start = next((i for i, l in enumerate(lines)
                          if 'Ticker' in l and 'Name' in l), None)
            if start is not None:
                df = pd.read_csv(StringIO('\n'.join(lines[start:])),
                                 on_bad_lines='skip')
                if 'Ticker' in df.columns:
                    # IWM CSV is sorted by weight (largest first).
                    # Sort ascending by Market Value so smallest caps come first —
                    # TEST_MODE [:N] then naturally lands on actual $100M~$1B stocks.
                    mval_col = next((c for c in df.columns
                                     if 'market value' in c.lower()), None)
                    if mval_col:
                        df[mval_col] = pd.to_numeric(
                            df[mval_col].astype(str).str.replace(',', ''),
                            errors='coerce')
                        df = df.sort_values(mval_col, ascending=True)
                    raw = df['Ticker'].dropna().astype(str).tolist()
                    tickers = [t.strip() for t in raw
                               if t.strip() not in ('', '-', 'nan', 'CASH')]
                    if not mval_col:          # fallback: shuffle for random sample
                        random.shuffle(tickers)
                    if tickers:
                        print(f"  ✅ Russell 2000: {len(tickers)} tickers")
                        return tickers
    except Exception as e:
        print(f"  iShares failed ({e})")

    # Method 2: S&P 600 (Wikipedia)
    try:
        print("  Fetching S&P 600 Small-Cap (Wikipedia)...")
        resp = requests.get(
            'https://en.wikipedia.org/wiki/List_of_S%26P_600_companies',
            headers=HEADERS)
        tables = pd.read_html(StringIO(resp.text))
        for t in tables:
            for col in t.columns:
                if 'ticker' in str(col).lower() or 'symbol' in str(col).lower():
                    tickers = [s.replace('.', '-')
                               for s in t[col].dropna().astype(str).tolist()]
                    if len(tickers) > 100:
                        print(f"  ✅ S&P 600: {len(tickers)} tickers")
                        return tickers
    except Exception as e:
        print(f"  S&P 600 failed ({e})")

    print("  ⚠️  No US tickers retrieved")
    return []


# ── Per-ticker fetch ──────────────────────────────────────────────────────────
def fetch_ticker(ticker, cache, analyze_only=False):
    """
    Return scored metric dict, or None if filtered out.

    Filters:
      - MarketCap must be $100M ~ $1B
      - Financial Services / Insurance / REIT sectors excluded
      - Data quality gate: at least 2 of (ROIC, RevGrowth, GrossMargin) must be valid
    Data sources:
      - Fundamentals: CacheManager (yfinance backed)
      - Volume_Surge / Insider_Pct / Net_Cash_Ratio: live yf — always fresh
    """
    if analyze_only:
        fin = cache._row_to_fin_dict(cache.get_row(ticker))
    else:
        fin = cache.load_or_fetch_financials(ticker)

    mcap = fin.get('MarketCap')
    if not mcap or not (MCAP_MIN <= mcap <= MCAP_MAX):
        return None

    # ① 금융/보험/리츠 업종 제외
    sector = (fin.get('Sector') or '').strip().lower()
    if any(ex in sector for ex in _EXCLUDED_SECTORS):
        return None

    roic = fin.get('ROIC')
    rg   = fin.get('RevGrowth')
    gm   = fin.get('GrossMargin')
    fcf  = fin.get('FCF_Margin')
    de   = fin.get('Debt_EBITDA')
    name = fin.get('Name', ticker)

    # ② 데이터 품질 게이트: ROIC·RevGrowth·GrossMargin 중 2개 이상 유효해야 통과
    key_valid = sum(1 for m in (roic, rg, gm) if is_valid(m))
    if key_valid < 2:
        return None

    # ③ 라이브 데이터: Volume Surge / Rev_Accel / Short Interest / Insider / Net Cash
    vs             = None
    rev_accel      = None
    short_pct      = None
    insider_pct    = None
    net_cash_ratio = None
    if not analyze_only:
        try:
            _yf_obj = yf.Ticker(ticker)
            # Volume Surge
            hist = _yf_obj.history(period='3mo')
            if not hist.empty and 'Volume' in hist.columns and len(hist) >= 30:
                recent_avg   = hist['Volume'].iloc[-10:].mean()
                baseline_avg = hist['Volume'].iloc[-30:-10].mean()
                if baseline_avg > 0:
                    vs = recent_avg / baseline_avg
            # Revenue Acceleration
            rev_accel = compute_rev_acceleration(_yf_obj)
            # Short Interest / Insider Ownership / Net Cash — all from .info
            _info = _yf_obj.info
            _sp = _info.get('shortPercentOfFloat') or _info.get('shortPercent')
            if _sp is not None:
                short_pct = float(_sp)
            _ip = _info.get('heldPercentInsiders')
            if _ip is not None:
                insider_pct = float(_ip)
            _cash = _info.get('totalCash') or _info.get('cash')
            _debt = _info.get('totalDebt') or 0
            if _cash is not None and mcap > 0:
                net_cash_ratio = (float(_cash) - float(_debt)) / mcap
        except Exception:
            pass

    total, bonus = score_stock(
        roic, rg, gm, fcf, de, rev_accel, vs, mcap,
        short_interest=short_pct, insider_pct=insider_pct,
        net_cash_ratio=net_cash_ratio,
    )
    confidence = data_confidence(
        roic, rg, gm, fcf, de, rev_accel, vs,
        insider_pct=insider_pct, net_cash_ratio=net_cash_ratio,
    )
    total = round(total * confidence, 2)
    return dict(
        Ticker          = ticker,
        Name            = name,
        Market          = 'US',
        MarketCap       = mcap,
        ROIC            = round(roic,           4) if is_valid(roic)           else None,
        RevGrowth       = round(rg,             4) if is_valid(rg)             else None,
        Rev_Accel       = round(rev_accel,      4) if is_valid(rev_accel)      else None,
        GrossMargin     = round(gm,             4) if is_valid(gm)             else None,
        FCF_Margin      = round(fcf,            4) if is_valid(fcf)            else None,
        Debt_EBITDA     = round(de,             4) if is_valid(de)             else None,
        Insider_Pct     = round(insider_pct,    4) if is_valid(insider_pct)    else None,
        Net_Cash_Ratio  = round(net_cash_ratio, 4) if is_valid(net_cash_ratio) else None,
        Volume_Surge    = round(vs,             4) if is_valid(vs)             else None,
        SmallCap_Bonus  = bonus,
        Data_Confidence = confidence,
        Total_Score     = total,
    )


# ── Sheet writer ──────────────────────────────────────────────────────────────
def get_or_create(name, rows=300, cols=20):
    try:
        return spreadsheet.worksheet(name)
    except gspread.exceptions.WorksheetNotFound:
        return spreadsheet.add_worksheet(title=name, rows=rows, cols=cols)

def write_sheet(df, sheet_name, summary_rows):
    ws = get_or_create(sheet_name)
    ws.clear()
    if df.empty:
        print(f"⚠️  {sheet_name}: no results — writing summary only")
        ws.update(summary_rows)
        return
    cols   = [c for c in SMALLCAP_COLS if c in df.columns]
    df_out = df[cols].copy()
    for c in df_out.select_dtypes(include=[float]).columns:
        df_out[c] = df_out[c].round(4)
    sheet_out = df_out.fillna('').astype(str)
    ws.update([sheet_out.columns.tolist()] + sheet_out.values.tolist() + summary_rows)
    print(f"✅ {sheet_name} → {len(df_out)} rows saved")
    dual_write_dataframe(sheet_name, df_out, market='US')


# ── Main ──────────────────────────────────────────────────────────────────────
def main():
    TEST_MODE    = os.environ.get('QUANT_TEST_MODE')    == 'true'
    ANALYZE_ONLY = os.environ.get('QUANT_ANALYZE_ONLY') == 'true'
    TEST_LIMIT   = int(os.environ.get('QUANT_TEST_LIMIT', '300'))

    print("\n" + "=" * 65)
    print("  US Small-Cap Scanner  ($100M ~ $1B)")
    print("=" * 65)
    if TEST_MODE:
        print(f"⚠️  TEST MODE : limited to {TEST_LIMIT} tickers")
    if ANALYZE_ONLY:
        print("⚡ ANALYZE-ONLY : re-scoring from Company_Master cache")

    cache = CacheManager(spreadsheet, verbose=True)

    # Universe
    if ANALYZE_ONLY:
        tickers = [t for t in cache.get_all_tickers()
                   if not t.endswith(('.KS', '.KQ'))]
        print(f"  Loaded {len(tickers)} US tickers from cache")
    else:
        tickers = get_tickers()

    if TEST_MODE:
        tickers = tickers[:TEST_LIMIT]
    print(f"  Total candidates : {len(tickers)}")

    # Pre-warm cache
    if not ANALYZE_ONLY:
        print("\n  Pre-warming cache...")
        cache.prefetch(tickers, delay=0.3)

    # Score
    rows = []
    for i, t in enumerate(tickers, 1):
        try:
            print(f"  [{i}/{len(tickers)}] {t}...", end=" ")
            row = fetch_ticker(t, cache, analyze_only=ANALYZE_ONLY)
            if row:
                rows.append(row)
                print(f"✅ score={row['Total_Score']}  mcap=${row['MarketCap']/1e6:.0f}M")
            else:
                print("skip")
            if not ANALYZE_ONLY:
                time.sleep(0.15)
        except Exception as e:
            print(f"ERR ({e})")

    df = pd.DataFrame(rows) if rows else pd.DataFrame()
    top = pd.DataFrame()
    if not df.empty:
        df  = df.sort_values('Total_Score', ascending=False).reset_index(drop=True)
        df['Rank'] = df.index + 1
        top = df.head(TOP_N)
        print(f"\n✅ valid={len(df)}  top {TOP_N}: "
              f"{top['Ticker'].iloc[0]} (score {top['Total_Score'].iloc[0]:.1f})")
    else:
        print("⚠️  No results")

    # Save
    cache.flush(label='US smallcap')
    now   = pd.Timestamp.now().strftime('%Y-%m-%d %H:%M')
    today = pd.Timestamp.now().strftime('%Y-%m-%d')
    if not top.empty:
        top['Last_Updated'] = today

    summary = [
        [''],
        ['== US_SmallCap_Gems =='],
        ['Generated',        now],
        ['Universe',         'Russell 2000 / S&P 600'],
        ['MarketCap Filter', '$100M ~ $1B USD'],
        ['Valid Stocks',     str(len(df))],
        ['Top Stock',        (f"{top['Ticker'].iloc[0]}  "
                              f"(score {top['Total_Score'].iloc[0]:.1f})")
                             if not top.empty else 'N/A'],
        ['Expected CAGR',    '25~40%  (small-cap growth, historical)'],
        ['Scoring',          'ROIC+RevGrowth+RuleOf40+GrossMargin+FCF+Debt+RevAccel+Insider+NetCash+ShortInterest+VolSurge+SmallCapBonus (max ~161pts)'],
        ['Cache',            f'Company_Master: {len(cache.get_all_tickers())} tickers cached'],
    ]
    write_sheet(top, 'US_SmallCap_Gems', summary)

    # Console preview
    print("\n" + "=" * 65)
    print("  US SmallCap GEMS – Top 5")
    print("=" * 65)
    if not top.empty:
        cols = ['Rank', 'Ticker', 'MarketCap', 'ROIC', 'RevGrowth', 'Rev_Accel', 'Total_Score']
        print(top[[c for c in cols if c in top.columns]].head(5).to_string(index=False))

    cache.print_stats()


if __name__ == '__main__':
    main()
