# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
"""
07b_smallcap_scanner_kr.py  ─  KR Small-Cap 10-Bagger Hunter  [Cache-Enabled]
==============================================================================
Universe  : KOSDAQ Full + KOSPI Bottom-30%   1000억 ~ 10조 KRW
Output    : KR_SmallCap_Gems  (Top 20)

Data sources:
  - Ticker list   : FinanceDataReader (→ Naver fallback)
  - MarketCap     : pykrx → Naver Finance scraping → yfinance fallback
  - Fundamentals  : pykrx (price/PER) + OpenDartReader (DART financials)
                    → yfinance fallback when DART unavailable
  - GrossMargin / RevGrowth : Naver Finance scraping (more accurate for KR)
  - Volume_Surge  : yfinance history(3mo) — always live

ETF / 채권 필터:
  - ETF 브랜드명 (KODEX, TIGER, RISE, KIWOOM, HANARO …) 또는
    상품 키워드 (합성, 액티브, 채권, 금리, 레버리지 …) 포함 시 제외
  - ROIC·RevGrowth·GrossMargin 세 지표 중 2개 이상 None 이면 제외
  - 금융/보험/리츠 업종 제외 (GrossMargin·FCF 지표 비교 불가)

Scoring (max ~153 pts) — 텐배거 기준:
  ROIC            25pts  (>30%=25, >20%=20, >10%=12, >0%=4; <-10%=-5 완화)
  RevGrowth       30pts  (>50%=30, >25%=24, >10%=15, >0%=6)
  Rule of 40      15pts  (RevGrowth% + FCF_Margin% ≥60=15, ≥40=10, ≥20=5)
  GrossMargin     15pts  (>50%=15, >35%=12, >20%=7, >0%=3)
  FCF_Margin      10pts  (>10%=10, >5%=7, >0%=5; 음수 페널티 제거)
  Debt_EBITDA     10pts  (<3x=10, <5x=6; >8x=-5 penalty; missing=5 neutral)
  Rev_Accel       10pts  (분기 YoY 가속도; +5%p=10, 소폭=6, 보합=3)
  Insider_Pct     10pts  (>15%=10, >5%=6, >1%=2  — 내부자 보유 = 경영진 신뢰)
  Net_Cash_Ratio   8pts  (순현금/시총: >20%=8, >0%=4; <-50%=-5 페널티)
  Volume_Surge     5pts  (>3x=5, >1.5x=3; 보조지표로 축소)
  SmallCap_Bonus  15pts  (시총이 1000억에 가까울수록 최대 15pts)
  Short_Interest   8pts  (>5%=8, >3%=5, >1%=2  — KR 기준 숏스퀴즈 잠재력)

Run standalone : python pipeline/07b_smallcap_scanner_kr.py
Run via engine : main_engine.py --krsmallcap
"""

# ── Imports ───────────────────────────────────────────────────────────────────
import gspread
import yfinance as yf
from sheets_client import get_spreadsheet
import pandas as pd
import requests
import signal
import time
import warnings
import math
from contextlib import contextmanager
from bs4 import BeautifulSoup
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
MCAP_MIN = 1e11   # 1000억 KRW
MCAP_MAX = 1e13   # 10조  KRW
TOP_N    = 20
TICKER_TIMEOUT_SECONDS = int(os.environ.get('QUANT_KR_SMALLCAP_TICKER_TIMEOUT_SECONDS', '75'))


def env_bool(name: str, default: bool = False) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in {'1', 'true', 'yes', 'on'}


def env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, str(default)))
    except (TypeError, ValueError):
        return default

# 금융/보험/리츠: GrossMargin·FCF_Margin 지표가 사업체와 비교 불가 → 제외
_EXCLUDED_SECTORS = {
    'financial services', 'insurance', 'banks', 'real estate',
    'asset management', 'capital markets', 'mortgage finance',
    'diversified financials',
}

SMALLCAP_COLS = [
    'Rank', 'Ticker', 'Name', 'Market', 'MarketCap',
    'ROIC', 'RevGrowth', 'Rev_Accel', 'GrossMargin', 'FCF_Margin',
    'Debt_EBITDA', 'Insider_Pct', 'Net_Cash_Ratio',
    'Volume_Surge', 'SmallCap_Bonus', 'Data_Confidence', 'Total_Score', 'Last_Updated',
]


class TickerTimeoutError(TimeoutError):
    """Raised when a single KR small-cap ticker takes too long to score."""


@contextmanager
def ticker_timeout(seconds: int):
    if seconds <= 0 or not hasattr(signal, 'SIGALRM'):
        yield
        return

    def _raise_timeout(_signum, _frame):
        raise TickerTimeoutError(f"ticker exceeded {seconds}s")

    previous_handler = signal.signal(signal.SIGALRM, _raise_timeout)
    signal.setitimer(signal.ITIMER_REAL, seconds)
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_handler)

# ── ETF / 채권 필터 설정 ──────────────────────────────────────────────────────
_ETF_BRAND_KW = {
    'KODEX', 'TIGER', 'RISE', 'KIWOOM', 'HANARO', 'KOSEF', 'ARIRANG',
    'FOCUS', 'TIMEFOLIO', 'TREX', 'SOL', 'ACE', 'PLUS', 'BNK',
    'SMART', 'MAXI', 'WOORI', 'KB', 'NH',
}
_ETF_PRODUCT_KW = [
    '합성', '액티브', '채권', '국채', '통안채', '회사채', '금리',
    '레버리지', '인버스', '선물', 'ETF', 'ETN', '인덱스펀드',
    'Nifty', 'KOFR', '스트립', '커버드콜', '리츠', 'REIT',
]


# ── Utilities ─────────────────────────────────────────────────────────────────
def is_valid(v):
    return v is not None and not (isinstance(v, float) and math.isnan(v))

def data_confidence(roic, rev_growth, gross_margin, fcf_margin,
                    debt_ebitda, rev_accel, volume_surge,
                    insider_pct=None, net_cash_ratio=None):
    """Return a conservative score multiplier from small-cap metric coverage."""
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

def is_missing_kr_name(name, ticker='') -> bool:
    """True when a Korean company name is blank or just repeats its ticker/code."""
    text = str(name or '').strip()
    ticker_text = str(ticker or '').strip()
    code = ticker_text.split('.')[0]
    if not text:
        return True
    if ticker_text and text.upper() == ticker_text.upper():
        return True
    return text == code or text.upper() in {f'{code}.KS', f'{code}.KQ'}

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

    Returns delta YoY as a float (e.g. 0.08 = +8%p acceleration)
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
    """Return (total_score, smallcap_bonus) tuple. Max ~153 pts."""
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

    # Gross Margin  15 pts  (고마진 구조 = 경쟁 해자)
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
    # None = yfinance 분기 데이터 부족 → 중립 3점 (신규상장/소형주 불이익 방지)
    if is_valid(rev_accel):
        if rev_accel > 0.05:    s += 10
        elif rev_accel > 0.01:  s += 6
        elif rev_accel > -0.05: s += 3
    else:
        s += 3

    # Insider Ownership  10 pts max  (창업자/경영진 보유 = 피터 린치 핵심 시그널)
    if is_valid(insider_pct) and insider_pct > 0:
        if insider_pct > 0.15:   s += 10  # >15% — 창업자/대주주 강한 보유
        elif insider_pct > 0.05: s += 6   # >5%  — 유의미한 내부자 보유
        elif insider_pct > 0.01: s += 2   # >1%  — 소폭 보유

    # Net Cash Position  8 pts max / -5 pts penalty (KRW 단위로 동일 비율 적용)
    if is_valid(net_cash_ratio):
        if net_cash_ratio > 0.20:    s += 8   # 순현금 > 시총의 20%
        elif net_cash_ratio > 0:     s += 4   # 순현금 양수
        elif net_cash_ratio < -0.50: s -= 5   # 순부채 > 시총의 50% — 위험

    # Volume Surge  5 pts  (보조지표로 축소 — 후행 확인용)
    if is_valid(volume_surge):
        s += 5 if volume_surge > 3 else (3 if volume_surge > 1.5 else 0)

    # Short Interest  8 pts max  (KR 공매도 잔고 기준 — 일반 주보다 낮은 구조)
    if is_valid(short_interest) and short_interest > 0:
        if short_interest > 0.05:   s += 8
        elif short_interest > 0.03: s += 5
        elif short_interest > 0.01: s += 2

    # SmallCap Bonus  15 pts
    bonus = smallcap_bonus(mcap)
    s += bonus

    return round(s, 2), bonus

def is_etf_or_bond(name, roic, rev_growth, gross_margin) -> bool:
    """
    True  → ETF·채권·펀드 → 제외
    False → 일반 사업체 → 통과

    판단 기준:
      ① 이름 첫 단어가 ETF 브랜드명 (KODEX, TIGER 등)
      ② 이름에 상품 특성 키워드 포함 (합성, 채권, 금리 등)
      ③ ROIC·RevGrowth·GrossMargin 세 지표 중 2개 이상 None (데이터 품질 부족)
    """
    if not name:
        return False
    name_upper = name.upper()
    first_token = name_upper.split()[0] if name_upper.split() else ''
    if first_token in _ETF_BRAND_KW:
        return True
    for kw in _ETF_PRODUCT_KW:
        if kw.upper() in name_upper:
            return True
    key_valid = sum(1 for m in (roic, rev_growth, gross_margin) if is_valid(m))
    if key_valid < 2:
        return True
    return False


# ── Universe fetch ────────────────────────────────────────────────────────────
def get_tickers():
    """KOSDAQ full + KOSPI bottom-30% by MarketCap."""

    # Method 1: FinanceDataReader
    try:
        import FinanceDataReader as fdr
        kosdaq = fdr.StockListing('KOSDAQ')
        kospi  = fdr.StockListing('KOSPI')

        kq = kosdaq['Code'].astype(str).str.zfill(6).tolist()
        print(f"  KOSDAQ (FDR): {len(kq)} stocks")

        if 'Marcap' in kospi.columns:
            ks_df = kospi.sort_values('Marcap')
            ks = ks_df.head(int(len(ks_df) * 0.30))['Code']\
                      .astype(str).str.zfill(6).tolist()
        else:
            ks = kospi['Code'].astype(str).str.zfill(6).tolist()
        print(f"  KOSPI bottom-30% (FDR): {len(ks)} stocks")

        kq_set = set(kq)
        tickers = ([c + '.KQ' for c in kq] +
                   [c + '.KS' for c in ks if c not in kq_set])
        result = list(dict.fromkeys(tickers))
        print(f"  ✅ KR universe: {len(result)} tickers")
        return result

    except Exception as e:
        print(f"  FDR failed ({e})  →  Naver fallback")

    # Method 2: Naver sise_market_sum scraping
    market_map = {0: '.KS', 1: '.KQ'}
    all_tickers: list = []
    for sosok, suffix in market_map.items():
        seen: set = set()
        for page in range(1, 60):
            try:
                resp = requests.get(
                    'https://finance.naver.com/sise/sise_market_sum.nhn',
                    params={'sosok': str(sosok), 'page': str(page)},
                    headers=HEADERS, timeout=10)
                soup  = BeautifulSoup(resp.text, 'html.parser')
                found = 0
                for a in soup.select('a[href*="code="]'):
                    code = a['href'].split('code=')[-1][:6]
                    if len(code) == 6 and code.isdigit() and code not in seen:
                        seen.add(code)
                        found += 1
                if found == 0:
                    break
                time.sleep(0.2)
            except Exception:
                break
        mkt_name = 'KOSPI' if sosok == 0 else 'KOSDAQ'
        print(f"  Naver {mkt_name}: {len(seen)} codes (suffix {suffix})")
        all_tickers += [c + suffix for c in seen]

    result = list(dict.fromkeys(all_tickers))
    print(f"  ✅ Naver fallback: {len(result)} tickers")
    return result


# ── Naver fundamentals scraper ────────────────────────────────────────────────
def naver_fundamentals(code):
    """
    Scrape GrossMargin, RevenueGrowth, Korean company name from Naver Finance.
    More accurate than yfinance for Korean stocks.
    """
    result = {k: None for k in
              ['PER', 'PBR', 'ROE', 'OperatingMargin',
               'GrossMargin', 'DebtToEquity', 'RevenueGrowth', 'KoreanName']}
    try:
        resp = requests.get('https://finance.naver.com/item/main.naver',
                            params={'code': code}, headers=HEADERS, timeout=10)
        soup = BeautifulSoup(resp.text, 'html.parser')
    except Exception:
        return result

    # Korean company name
    title_tag = soup.find('title')
    if title_tag:
        kr_name = title_tag.get_text(strip=True).split(':')[0].strip()
        if kr_name:
            result['KoreanName'] = kr_name

    # PER / PBR
    per_table = soup.select_one('table.per_table')
    if per_table:
        for row in per_table.select('tr'):
            th = row.find('th'); td = row.find('td')
            if not th or not td:
                continue
            lbl = th.get_text(strip=True)
            raw = td.get_text(strip=True).split('배')[0].replace(',', '')
            try:
                v = float(raw)
                if lbl.startswith('PER') and '추정' not in lbl:
                    result['PER'] = v
                elif lbl.startswith('PBR'):
                    result['PBR'] = v
            except ValueError:
                pass

    # IFRS annual table
    LMAP = {
        'ROE(지배주주)': ('ROE',            True),
        '영업이익률':    ('OperatingMargin', True),
        '부채비율':      ('DebtToEquity',    False),
        '매출총이익률':  ('GrossMargin',     True),
    }
    C, P = 3, 2
    fin_table = soup.select_one('table.tb_type1_ifrs')
    if fin_table:
        for row in fin_table.select('tr'):
            cells = [td.get_text(strip=True) for td in row.select('td, th')]
            if len(cells) <= C:
                continue
            for key, (field, pct) in LMAP.items():
                if cells[0] == key:
                    raw = cells[C].replace(',', '').replace('%', '')
                    try:
                        v = float(raw)
                        result[field] = v / 100 if pct else v
                    except ValueError:
                        pass
            if cells[0] == '매출액' and len(cells) > C:
                try:
                    curr = float(cells[C].replace(',', ''))
                    prev = float(cells[P].replace(',', ''))
                    if prev > 0:
                        result['RevenueGrowth'] = (curr - prev) / prev
                except (ValueError, IndexError):
                    pass
    return result


# ── Per-ticker fetch ──────────────────────────────────────────────────────────
def fetch_ticker(ticker, cache, analyze_only=False):
    """
    Return scored metric dict, or None if filtered out.

    Filters:
      - MarketCap must be 1000억 ~ 10조 KRW
      - ETF / 채권 상품 제외 (is_etf_or_bond) — 데이터 품질 게이트 포함
      - Financial Services / Insurance / REIT sectors excluded
    Data sources:
      - Fundamentals : CacheManager (pykrx + DART backed)
      - GrossMargin / RevGrowth : Naver Finance scraping
      - Volume_Surge / Insider_Pct / Net_Cash_Ratio: live yf
    """
    code = ticker.split('.')[0]

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

    # GrossMargin / RevGrowth: use cache first (populated by 01 via Naver)
    gm      = fin.get('GrossMargin')
    rg      = fin.get('RevGrowth')
    kr_name = fin.get('Name')

    if not analyze_only and (not is_valid(gm) or not is_valid(rg) or is_missing_kr_name(kr_name, ticker)):
        try:
            nv = naver_fundamentals(code)
            time.sleep(0.2)
            nv_gm   = nv.get('GrossMargin')
            nv_rg   = nv.get('RevenueGrowth')
            nv_name = nv.get('KoreanName')
            if is_valid(nv_gm):
                gm = nv_gm
            if is_valid(nv_rg):
                rg = nv_rg
            if nv_name:
                kr_name = nv_name
            patch = {}
            if is_valid(nv_gm):
                patch['GrossMargin_Last'] = round(nv_gm, 4)
            if is_valid(nv_rg):
                patch['RevGrowth_Last']   = round(nv_rg, 4)
            if nv_name:
                patch['Name'] = nv_name
            if patch:
                cache.update_row(ticker, patch)
        except Exception:
            pass

    roic = fin.get('ROIC')
    fcf  = fin.get('FCF_Margin')
    de   = fin.get('Debt_EBITDA')
    name = kr_name if not is_missing_kr_name(kr_name, ticker) else code

    # ② ETF / 채권 필터 (데이터 품질 게이트 포함 — 3개 중 2개 이상 유효해야 통과)
    if is_etf_or_bond(name, roic, rg, gm):
        return None

    # ③ 라이브 데이터: Volume Surge / Rev_Accel / Short Interest / Insider / Net Cash
    vs             = None
    rev_accel      = fin.get('RevAccel')
    short_pct      = None
    insider_pct    = None
    net_cash_ratio = None
    if not analyze_only:
        try:
            _yf_obj = yf.Ticker(ticker)
            # Volume Surge
            hist = _yf_obj.history(period='3mo')
            if not hist.empty and 'Volume' in hist.columns and len(hist) >= 15:
                baseline_avg = hist['Volume'].iloc[:-10].mean()
                recent_avg   = hist['Volume'].tail(10).mean()
                if baseline_avg > 0:
                    vs = recent_avg / baseline_avg
            # Revenue Acceleration
            if not is_valid(rev_accel):
                rev_accel = compute_rev_acceleration(_yf_obj)
                if is_valid(rev_accel):
                    cache.update_row(ticker, {'RevAccel_Last': round(rev_accel, 4)})
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
        Market          = 'KR',
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
    dual_write_dataframe(sheet_name, df_out, market='KR')


# ── Main ──────────────────────────────────────────────────────────────────────
def main():
    TEST_MODE    = os.environ.get('QUANT_TEST_MODE')    == 'true'
    ANALYZE_ONLY = os.environ.get('QUANT_ANALYZE_ONLY') == 'true'
    TEST_LIMIT   = int(os.environ.get('QUANT_TEST_LIMIT', '300'))
    SHARD_TOTAL  = max(1, env_int('QUANT_KR_SMALLCAP_SHARD_TOTAL', 1))
    SHARD_INDEX  = env_int('QUANT_KR_SMALLCAP_SHARD_INDEX', -1)
    SHARD_MODE   = SHARD_TOTAL > 1 or SHARD_INDEX >= 0
    WRITE_SHEET  = env_bool('QUANT_KR_SMALLCAP_WRITE_SHEET', True)
    FLUSH_CACHE  = env_bool('QUANT_KR_SMALLCAP_FLUSH_CACHE', True)
    OUTPUT_PATH  = os.environ.get('QUANT_KR_SMALLCAP_OUTPUT_PATH', '').strip()

    print("\n" + "=" * 65)
    print("  KR Small-Cap Scanner  (1000억 ~ 10조 KRW)")
    print("=" * 65)
    if TEST_MODE:
        print(f"⚠️  TEST MODE : limited to {TEST_LIMIT} tickers")
    if ANALYZE_ONLY:
        print("⚡ ANALYZE-ONLY : re-scoring from Company_Master cache")
    if SHARD_MODE:
        if SHARD_INDEX < 0 or SHARD_INDEX >= SHARD_TOTAL:
            raise ValueError(
                f"Invalid shard index {SHARD_INDEX}; expected 0 <= index < {SHARD_TOTAL}"
            )
        print(f"🧩 SHARD MODE : shard {SHARD_INDEX + 1}/{SHARD_TOTAL}")
    if OUTPUT_PATH:
        print(f"📦 Shard artifact: {OUTPUT_PATH}")

    cache = CacheManager(spreadsheet, verbose=True)

    # Universe
    if ANALYZE_ONLY:
        tickers = [t for t in cache.get_all_tickers()
                   if t.endswith(('.KS', '.KQ'))]
        print(f"  Loaded {len(tickers)} KR tickers from cache")
    else:
        tickers = get_tickers()

    if TEST_MODE:
        tickers = tickers[:TEST_LIMIT]
    if SHARD_MODE:
        universe_count = len(tickers)
        tickers = [
            ticker for pos, ticker in enumerate(tickers)
            if pos % SHARD_TOTAL == SHARD_INDEX
        ]
        print(
            f"  Shard slice: {len(tickers)} / {universe_count} tickers "
            f"(index {SHARD_INDEX}, total {SHARD_TOTAL})"
        )
    print(f"  Total candidates : {len(tickers)}")

    # Pre-warm cache
    if not ANALYZE_ONLY:
        print(
            "\n  Skipping bulk pre-warm for KR smallcap; "
            f"per-ticker timeout is {TICKER_TIMEOUT_SECONDS}s"
        )

    # Score
    rows = []
    for i, t in enumerate(tickers, 1):
        try:
            print(f"  [{i}/{len(tickers)}] {t}...", end=" ", flush=True)
            with ticker_timeout(TICKER_TIMEOUT_SECONDS):
                row = fetch_ticker(t, cache, analyze_only=ANALYZE_ONLY)
            if row:
                rows.append(row)
                print(f"✅ score={row['Total_Score']}  mcap={row['MarketCap']/1e8:.0f}억")
            else:
                print("skip")
            if not ANALYZE_ONLY:
                time.sleep(0.2)
        except TickerTimeoutError:
            print(f"skip (timeout > {TICKER_TIMEOUT_SECONDS}s)")
        except Exception as e:
            print(f"ERR ({e})")

    df = pd.DataFrame(rows) if rows else pd.DataFrame()
    top = pd.DataFrame()
    if not df.empty:
        df  = df.sort_values('Total_Score', ascending=False).reset_index(drop=True)
        df['Rank'] = df.index + 1
        top = df.head(TOP_N).copy()
        print(f"\n✅ valid={len(df)}  top {TOP_N}: "
              f"{top['Ticker'].iloc[0]} (score {top['Total_Score'].iloc[0]:.1f})")
    else:
        print("⚠️  No KR results")

    # Save
    now   = pd.Timestamp.now().strftime('%Y-%m-%d %H:%M')
    today = pd.Timestamp.now().strftime('%Y-%m-%d')
    if not df.empty:
        df['Last_Updated'] = today
    if not top.empty:
        top['Last_Updated'] = today

    if OUTPUT_PATH:
        os.makedirs(os.path.dirname(os.path.abspath(OUTPUT_PATH)), exist_ok=True)
        if df.empty:
            export_df = pd.DataFrame(columns=SMALLCAP_COLS)
        else:
            export_cols = [c for c in SMALLCAP_COLS if c in df.columns]
            export_df = df[export_cols].copy()
        export_df.to_csv(OUTPUT_PATH, index=False)
        print(f"📦 KR smallcap shard artifact saved: {OUTPUT_PATH} ({len(export_df)} rows)")

    if FLUSH_CACHE:
        cache.flush(label='KR smallcap')
    else:
        print("  ⏭  Skipping Company_Master flush for KR smallcap shard job")

    summary = [
        [''],
        ['== KR_SmallCap_Gems =='],
        ['Generated',        now],
        ['Universe',         'KOSDAQ Full + KOSPI Bottom-30%'],
        ['Shard',            f'{SHARD_INDEX + 1}/{SHARD_TOTAL}' if SHARD_MODE else 'N/A'],
        ['MarketCap Filter', '1000억 ~ 10조 KRW'],
        ['ETF/채권 필터',    'ETF 브랜드·상품 키워드 및 재무지표 전무 종목 제외'],
        ['Valid Stocks',     str(len(df))],
        ['Top Stock',        (f"{top['Ticker'].iloc[0]}  "
                              f"(score {top['Total_Score'].iloc[0]:.1f})")
                             if not top.empty else 'N/A'],
        ['Expected CAGR',    '25~40%  (KR small-cap growth, historical)'],
        ['Scoring',          'ROIC+RevGrowth+RuleOf40+GrossMargin+FCF+Debt+RevAccel+Insider+NetCash+ShortInterest+VolSurge+SmallCapBonus (max ~153pts)'],
        ['Cache',            f'Company_Master: {len(cache.get_all_tickers())} tickers cached'],
    ]
    if WRITE_SHEET:
        write_sheet(top, 'KR_SmallCap_Gems', summary)
    else:
        print("  ⏭  Skipping KR_SmallCap_Gems sheet/DB write for shard job")

    # Console preview
    print("\n" + "=" * 65)
    print("  KR SmallCap GEMS – Top 5")
    print("=" * 65)
    if not top.empty:
        cols = ['Rank', 'Ticker', 'Name', 'MarketCap', 'ROIC', 'RevGrowth', 'Rev_Accel', 'Total_Score']
        print(top[[c for c in cols if c in top.columns]].head(5).to_string(index=False))

    cache.print_stats()


if __name__ == '__main__':
    main()
