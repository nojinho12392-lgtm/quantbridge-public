# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
"""
07_smallcap_scanner.py  ─  Small-Cap 10-Bagger Hunter  [Cache-Enabled]
===================================================================
Universe:
  US : Russell 2000 / S&P600  $100M ~ $1B (MarketCap)
  KR : KOSDAQ full + KOSPI bottom 30%  1000억 ~ 10조 KRW

Cache integration:
  - All .info / .financials / .balance_sheet / .cashflow calls
    are routed through CacheManager.load_or_fetch_financials().
  - Data is saved to Company_Master sheet on every new/stale fetch.
  - Data is refreshed only when >90 days stale OR earnings passed.
  - Volume_Surge requires 3-month history → kept as a live yf call
    (lightweight single endpoint, not worth caching at 3-min granularity).

Company_Master caching:
  - ALL small-cap tickers (Russell 2000, full KOSDAQ) are stored in
    Company_Master just like large-caps — no market-cap restrictions.
  - 80%+ API call reduction on subsequent runs once cache is warm.

Writes two Google Sheets:
  US_SmallCap_Gems  – Top 20 US small-cap growth stocks
  KR_SmallCap_Gems  – Top 20 KR small-cap growth stocks

Scoring (max ~120pts):
  ROIC          >30%  = 30pts  (>20%=25, >10%=15, >0%=5; <-10%=-10 penalty)
  RevenueGrowth >50%  = 25pts  (>25%=20, >10%=12, >0%=5)
  PEG           <1.0  = 20pts  (<1.5=12, <2.0=5; >3.0=-5 penalty)
  GrossMargin   >35%  = 15pts  (>20%=9, >0%=4)
  FCF_Margin    >10%  = 15pts  (>5%=10, >0%=8; <-10%=-5 penalty; missing=5 neutral)
  Debt_EBITDA   <3x   = 10pts  (<5x=6; >8x=-5 penalty; missing=5 neutral)
  Volume_Surge  >3x   = 10pts  (>1.5x=6; 10-day avg vs 3mo baseline)
  SmallCap_Bonus      = up to 15pts (smaller = higher)

[CHANGE] All pipeline execution moved into main() function.
         Script can be run standalone OR called as subprocess from
         main_engine.py's SMALLCAP_PIPELINE.
"""

# ── Imports ───────────────────────────────────────────────────────────────────
import yfinance as yf
from sheets_client import get_spreadsheet
import pandas as pd
import numpy as np
import requests
import time
import warnings
import math
from bs4 import BeautifulSoup
from io import StringIO

# ── Cache integration ─────────────────────────────────────────────────────────
# [CHANGE] CacheManager is imported but cache instance is created inside main()
#          (was previously created at module level, causing execution on import)
from cache_manager import CacheManager

warnings.filterwarnings('ignore')

# ── Google Sheets (module-level auth — needed by sheet writer helpers) ────────
scope = ["https://spreadsheets.google.com/feeds",
         "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) '
                  'AppleWebKit/537.36 (KHTML, like Gecko) '
                  'Chrome/120.0.0.0 Safari/537.36'
}

# ── Constants ─────────────────────────────────────────────────────────────────
US_MCAP_MIN = 1e8    # $100M
US_MCAP_MAX = 1e9    # $1B
KR_MCAP_MIN = 1e11   # 1000억 KRW
KR_MCAP_MAX = 1e13   # 10조  KRW
TOP_N = 20

# ── Utilities ─────────────────────────────────────────────────────────────────
def is_valid(v):
    return v is not None and not (isinstance(v, float) and math.isnan(v))

def smallcap_bonus(mcap, mcap_min, mcap_max):
    """Smaller market-cap → higher bonus (max 15 pts)."""
    try:
        if not mcap or mcap <= 0:
            return 0.0
        n = (mcap - mcap_min) / (mcap_max - mcap_min)
        return round((1 - max(0.0, min(1.0, n))) * 15, 2)
    except Exception:
        return 0.0

def score_stock(roic, rev_growth, peg, gross_margin, fcf_margin,
                debt_ebitda, volume_surge, mcap, mcap_min, mcap_max):
    """Return (total_score, bonus) tuple.

    Scoring breakdown (max ~120 pts):
      ROIC          max 30 pts  (penalty -10 if < -10%)
      RevenueGrowth max 25 pts  (exceptional >50% tier added)
      PEG           max 20 pts  (penalty -5 if > 3.0)
      GrossMargin   max 15 pts
      FCF_Margin    max 15 pts  (penalty -5 if < -10%; 5 neutral if missing)
      Debt/EBITDA   max 10 pts  (penalty -5 if > 8x; 5 neutral if missing)
      Volume_Surge  max 10 pts
      SmallCap_Bonus max 15 pts

    Improvement notes vs original:
    - ROIC < -10%: -10 pts penalty (capital destruction signal)
    - ROIC > 30%: 30 pts (exceptional tier above old 25-pt ceiling)
    - RevGrowth > 50%: 25 pts (hyper-growth tier; old cap was 20)
    - PEG > 3.0: -5 pts (overvalued small-cap penalty)
    - FCF_Margin < -10%: -5 pts (significant cash burn penalty)
    - FCF_Margin missing: 5 pts neutral (consistent with Debt/EBITDA treatment)
    - Debt/EBITDA > 8x: -5 pts (extreme leverage penalty)
    """
    s = 0

    # ROIC  30 pts max  /  -10 pts penalty
    if is_valid(roic):
        if roic > 0.30:
            s += 30
        elif roic > 0.20:
            s += 25
        elif roic > 0.10:
            s += 15
        elif roic > 0:
            s += 5
        elif roic > -0.10:
            s += 0   # breakeven zone — no reward, no penalty
        else:
            s -= 10  # severe capital destruction

    # Revenue Growth  25 pts max
    if is_valid(rev_growth):
        if rev_growth > 0.50:
            s += 25  # hyper-growth
        elif rev_growth > 0.25:
            s += 20
        elif rev_growth > 0.10:
            s += 12
        elif rev_growth > 0:
            s += 5

    # PEG  20 pts max  /  -5 pts penalty
    if is_valid(peg) and peg > 0:
        if peg < 1.0:
            s += 20
        elif peg < 1.5:
            s += 12
        elif peg < 2.0:
            s += 5
        elif peg > 3.0:
            s -= 5   # overvalued small-cap

    # Gross Margin  15 pts
    if is_valid(gross_margin):
        s += 15 if gross_margin > 0.35 else (9 if gross_margin > 0.20 else (4 if gross_margin > 0 else 0))

    # FCF Margin  15 pts max  /  -5 pts penalty  (5 neutral if missing)
    if is_valid(fcf_margin):
        if fcf_margin > 0.10:
            s += 15
        elif fcf_margin > 0.05:
            s += 10
        elif fcf_margin > 0:
            s += 8
        elif fcf_margin > -0.10:
            s += 0   # mild cash burn — no reward
        else:
            s -= 5   # significant cash burn
    else:
        s += 5       # neutral for missing (small caps often lack FCF data)

    # Debt/EBITDA  10 pts max  /  -5 pts penalty  (5 neutral if missing)
    if is_valid(debt_ebitda) and debt_ebitda > 0:
        if debt_ebitda < 3:
            s += 10
        elif debt_ebitda < 5:
            s += 6
        elif debt_ebitda < 8:
            s += 0   # high leverage — no reward
        else:
            s -= 5   # extreme leverage penalty
    else:
        s += 5       # neutral if EBITDA unavailable

    # Volume Surge  10 pts
    if is_valid(volume_surge):
        s += 10 if volume_surge > 3 else (6 if volume_surge > 1.5 else 0)

    # SmallCap Bonus  15 pts
    bonus = smallcap_bonus(mcap, mcap_min, mcap_max)
    s += bonus

    return round(s, 2), bonus


# ═══════════════════════════════════════════════════════════════════════════════
# PIPELINE 1 : US Small-Cap helpers
# ═══════════════════════════════════════════════════════════════════════════════

def get_us_tickers():
    """iShares IWM (Russell 2000)  →  S&P 600 (Wikipedia) fallback."""

    # ── Method 1: iShares IWM ETF CSV ────────────────────────────────────────
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
                    raw = df['Ticker'].dropna().astype(str).tolist()
                    tickers = [t.strip() for t in raw
                               if t.strip() not in ('', '-', 'nan', 'CASH')]
                    if tickers:
                        print(f"  ✅ Russell 2000: {len(tickers)} tickers")
                        return tickers
    except Exception as e:
        print(f"  iShares failed ({e})")

    # ── Method 2: S&P 600 (Wikipedia) ────────────────────────────────────────
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


# [CHANGE] cache is now an explicit parameter (was a module-level global).
#          This makes the function self-contained and testable.
def fetch_us(ticker, cache, analyze_only=False):
    """
    Return metric dict for ticker, or None if out of MarketCap range.

    CACHE INTEGRATION:
    - Fundamental metrics (ROIC, GrossMargin, FCF_Margin, Debt/EBITDA,
      RevGrowth, PEG, Name) → cache.load_or_fetch_financials()
      Avoids .info + .financials + .balance_sheet + .cashflow calls when fresh.
      Data is saved to Company_Master on every stale/new fetch.
    - MarketCap filter → uses cached MarketCap (or falls back to live if absent).
    - Volume_Surge → still uses live stock.history(period='3mo'):
      3-month volume ratio changes daily; caching it would give stale signals.

    analyze_only=True: reads from Company_Master only — no API calls, no
      Volume_Surge. Use after a full run has already warmed the cache.

    [CHANGE] cache parameter replaces implicit module-level cache global.
             Ensures Company_Master receives all small-cap fundamentals.
    """
    # ── Step 1: Load fundamental data (cache-only or live fetch) ─────────────
    if analyze_only:
        fin = cache._row_to_fin_dict(cache.get_row(ticker))
    else:
        # load_or_fetch_financials() writes to Company_Master when stale/new,
        # giving 80%+ API reduction on subsequent runs once cache is warm.
        fin = cache.load_or_fetch_financials(ticker)

    mcap = fin.get('MarketCap')

    # If cache has no MarketCap (first run for this ticker), fin fetch already
    # populated it above; if still None the ticker is likely delisted → skip.
    if not mcap or not (US_MCAP_MIN <= mcap <= US_MCAP_MAX):
        return None

    # ── Step 2: Volume Surge (live — skipped in analyze-only mode) ───────────
    # Use 10-day rolling avg vs 3mo baseline avg to avoid single-day spikes
    # (e.g. earnings day, index rebalance) inflating the signal.
    vs = None
    if not analyze_only:
        try:
            hist = yf.Ticker(ticker).history(period='3mo')
            if not hist.empty and 'Volume' in hist.columns and len(hist) >= 15:
                baseline_avg = hist['Volume'].iloc[:-10].mean()
                recent_avg   = hist['Volume'].tail(10).mean()
                if baseline_avg > 0:
                    vs = recent_avg / baseline_avg
        except Exception:
            pass

    # ── Step 3: Pull scored fields from cache dict ───────────────────────────
    roic = fin.get('ROIC')
    rg   = fin.get('RevGrowth')
    peg  = fin.get('PEG')
    gm   = fin.get('GrossMargin')
    fcf  = fin.get('FCF_Margin')
    de   = fin.get('Debt_EBITDA')
    name = fin.get('Name', ticker)

    # ── Step 4: Score ─────────────────────────────────────────────────────────
    total, bonus = score_stock(roic, rg, peg, gm, fcf, de, vs,
                               mcap, US_MCAP_MIN, US_MCAP_MAX)
    return dict(
        Ticker       = ticker,
        Name         = name,
        Market       = 'US',
        MarketCap    = mcap,
        ROIC         = round(roic, 4) if is_valid(roic) else None,
        RevGrowth    = round(rg,   4) if is_valid(rg)   else None,
        PEG          = round(peg,  4) if is_valid(peg)  else None,
        GrossMargin  = round(gm,   4) if is_valid(gm)   else None,
        FCF_Margin   = round(fcf,  4) if is_valid(fcf)  else None,
        Debt_EBITDA  = round(de,   4) if is_valid(de)   else None,
        Volume_Surge = round(vs,   4) if is_valid(vs)   else None,
        SmallCap_Bonus = bonus,
        Total_Score    = total,
    )


# ═══════════════════════════════════════════════════════════════════════════════
# PIPELINE 2 : KR Small-Cap helpers
# ═══════════════════════════════════════════════════════════════════════════════

def get_kr_tickers():
    """KOSDAQ full + KOSPI bottom-30% by MarketCap."""

    # ── Method 1: FinanceDataReader ───────────────────────────────────────────
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

    # ── Method 2: Naver sise_market_sum scraping ─────────────────────────────
    # sosok=0 → KOSPI (.KS),  sosok=1 → KOSDAQ (.KQ)
    # Each market is scraped separately so the correct yfinance suffix is applied.
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
                    break          # no new codes → last page reached
                time.sleep(0.2)
            except Exception:
                break
        mkt_name = 'KOSPI' if sosok == 0 else 'KOSDAQ'
        print(f"  Naver {mkt_name}: {len(seen)} codes (suffix {suffix})")
        all_tickers += [c + suffix for c in seen]

    # Deduplicate (KOSDAQ codes take priority if a code appears in both markets)
    result = list(dict.fromkeys(all_tickers))
    print(f"  ✅ Naver fallback: {len(result)} tickers (KOSPI .KS / KOSDAQ .KQ)")
    return result


def naver_fundamentals(code):
    """
    Scrape GrossMargin, RevenueGrowth, ROE, Debt/Equity from Naver Finance.
    Called only when the yfinance cache doesn't carry KR-specific fields
    (Naver provides more accurate GrossMargin and RevenueGrowth for KR stocks).
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

    # Korean company name from page title (e.g. "산일전기 : 네이버 금융")
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
        'ROE(지배주주)':  ('ROE',            True),
        '영업이익률':     ('OperatingMargin', True),
        '부채비율':       ('DebtToEquity',    False),
        '매출총이익률':   ('GrossMargin',     True),
    }
    C, P = 3, 2   # column indices (most-recent year, previous year)
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


# ── ETF / 채권 필터 ────────────────────────────────────────────────────────────
# KRX에 상장된 ETF·ETN·채권형 펀드는 재무지표(ROIC/RevGrowth 등)가 없어
# SmallCap_Bonus만으로 상위에 랭크되는 문제가 있음. 아래 두 가지 기준으로 제외:
#   1) 이름에 ETF 운용사 브랜드 또는 상품 특성 키워드 포함
#   2) 핵심 재무지표(ROIC, RevGrowth, GrossMargin) 가 모두 None
#      → 실제 사업체라면 적어도 하나는 존재해야 함

_ETF_BRAND_KW = {
    # 국내 ETF 브랜드
    'KODEX', 'TIGER', 'RISE', 'KIWOOM', 'HANARO', 'KOSEF', 'ARIRANG',
    'FOCUS', 'TIMEFOLIO', 'TREX', 'SOL', 'ACE', 'PLUS', 'BNK',
    'SMART', 'MAXI', 'WOORI', 'KB', 'NH',
}
_ETF_PRODUCT_KW = [
    # 상품 유형 키워드 (부분 포함 검사)
    '합성', '액티브', '채권', '국채', '통안채', '회사채', '금리',
    '레버리지', '인버스', '선물', 'ETF', 'ETN', '인덱스펀드',
    'Nifty', 'KOFR', '스트립', '커버드콜', '리츠', 'REIT',
]

def is_etf_or_bond(name: str, roic, rev_growth, gross_margin) -> bool:
    """
    True  → ETF·채권·펀드 상품으로 판단 → 스캐너에서 제외
    False → 일반 사업체 → 통과
    판단 기준:
      ① 이름 첫 단어가 ETF 브랜드명과 일치 (대소문자 무시)
      ② 이름에 상품 특성 키워드 포함
      ③ ROIC·RevGrowth·GrossMargin 세 지표 모두 None (재무공시 없음)
    """
    if not name:
        return False
    name_upper = name.upper()
    # ① 브랜드 체크: 이름 첫 토큰이 ETF 브랜드
    first_token = name_upper.split()[0] if name_upper.split() else ''
    if first_token in _ETF_BRAND_KW:
        return True
    # ② 키워드 체크
    for kw in _ETF_PRODUCT_KW:
        if kw.upper() in name_upper:
            return True
    # ③ 재무지표 전무 체크
    if not is_valid(roic) and not is_valid(rev_growth) and not is_valid(gross_margin):
        return True
    return False


# [CHANGE] cache is now an explicit parameter (was a module-level global).
def fetch_kr(ticker, cache, analyze_only=False):
    """
    Return metric dict for KR ticker, or None if out of MarketCap range.

    CACHE INTEGRATION:
    - Base fundamentals (ROIC, FCF_Margin, Debt_EBITDA, PEG, MarketCap)
      → cache.load_or_fetch_financials()  (saves to Company_Master on fetch)
    - GrossMargin and RevenueGrowth → prefer Naver Finance scrape for KR
      accuracy; use cached yf value as fallback.
    - Volume_Surge → live yf.history(period='3mo') (same as US pipeline).

    analyze_only=True: reads from Company_Master only — no API calls, no
      Naver scraping, no Volume_Surge. Use after a full run has warmed cache.

    Naver scraping is NOT cached (it's fast, ~200ms per stock, and
    GrossMargin/RevGrowth from Naver are more current than yfinance for KR).

    [CHANGE] cache parameter replaces implicit module-level cache global.
             Ensures Company_Master receives all KR small-cap fundamentals.
    """
    code = ticker.split('.')[0]

    # ── Step 1: Load fundamentals (cache-only or live fetch) ─────────────────
    if analyze_only:
        fin = cache._row_to_fin_dict(cache.get_row(ticker))
    else:
        # Saves to Company_Master on every stale/new fetch.
        fin = cache.load_or_fetch_financials(ticker)

    mcap = fin.get('MarketCap')
    if not mcap or not (KR_MCAP_MIN <= mcap <= KR_MCAP_MAX):
        return None

    # ── Step 2: Naver scrape for KR-specific accuracy (skipped in analyze-only)
    gm = rg = None
    if not analyze_only:
        nv = {}
        try:
            nv = naver_fundamentals(code)
            time.sleep(0.2)
        except Exception:
            pass
        gm = nv.get('GrossMargin')
        rg = nv.get('RevenueGrowth')
        kr_name = nv.get('KoreanName')
    else:
        kr_name = None

    # GrossMargin: Naver > cached yf
    if not is_valid(gm):
        gm = fin.get('GrossMargin')

    # RevGrowth: Naver > cached yf
    if not is_valid(rg):
        rg = fin.get('RevGrowth')

    # ── Step 3: Volume Surge (live — skipped in analyze-only mode) ───────────
    # Use 10-day rolling avg vs 3mo baseline avg to avoid single-day spikes.
    vs = None
    if not analyze_only:
        try:
            hist = yf.Ticker(ticker).history(period='3mo')
            if not hist.empty and 'Volume' in hist.columns and len(hist) >= 15:
                baseline_avg = hist['Volume'].iloc[:-10].mean()
                recent_avg   = hist['Volume'].tail(10).mean()
                if baseline_avg > 0:
                    vs = recent_avg / baseline_avg
        except Exception:
            pass

    # ── Step 4: Remaining fields from cache ─────────────────────────────────
    roic = fin.get('ROIC')
    peg  = fin.get('PEG')
    fcf  = fin.get('FCF_Margin')
    de   = fin.get('Debt_EBITDA')
    # Korean name from Naver > yfinance English name > code fallback
    name = kr_name if kr_name else fin.get('Name', code)

    # ── Step 4b: ETF / 채권 필터 ─────────────────────────────────────────────
    if is_etf_or_bond(name, roic, rg, gm):
        return None   # ETF·채권·펀드 → 제외

    # ── Step 5: Score ────────────────────────────────────────────────────────
    total, bonus = score_stock(roic, rg, peg, gm, fcf, de, vs,
                               mcap, KR_MCAP_MIN, KR_MCAP_MAX)
    return dict(
        Ticker       = ticker,
        Name         = name,
        Market       = 'KR',
        MarketCap    = mcap,
        ROIC         = round(roic, 4) if is_valid(roic) else None,
        RevGrowth    = round(rg,   4) if is_valid(rg)   else None,
        PEG          = round(peg,  4) if is_valid(peg)  else None,
        GrossMargin  = round(gm,   4) if is_valid(gm)   else None,
        FCF_Margin   = round(fcf,  4) if is_valid(fcf)  else None,
        Debt_EBITDA  = round(de,   4) if is_valid(de)   else None,
        Volume_Surge = round(vs,   4) if is_valid(vs)   else None,
        SmallCap_Bonus = bonus,
        Total_Score    = total,
    )


# ═══════════════════════════════════════════════════════════════════════════════
# Sheet Writers
# ═══════════════════════════════════════════════════════════════════════════════

def get_or_create(name, rows=300, cols=20):
    try:
        return spreadsheet.worksheet(name)
    except gspread.exceptions.WorksheetNotFound:
        return spreadsheet.add_worksheet(title=name, rows=rows, cols=cols)

def df_to_sheet(df, sheet_name, col_order, summary_rows):
    if df.empty:
        print(f"⚠️  {sheet_name}: no results — writing summary only")
        ws = get_or_create(sheet_name)
        ws.clear()
        ws.update(summary_rows)
        return
    cols   = [c for c in col_order if c in df.columns]
    df_out = df[cols].copy()
    for c in df_out.select_dtypes(include=[float]).columns:
        df_out[c] = df_out[c].round(4)
    df_out = df_out.fillna('').astype(str)
    ws = get_or_create(sheet_name)
    ws.clear()
    ws.update([df_out.columns.tolist()] +
              df_out.values.tolist() +
              summary_rows)
    print(f"✅ {sheet_name} → {len(df_out)} rows saved")


# ═══════════════════════════════════════════════════════════════════════════════
# [NEW] main() — all pipeline execution consolidated here
#       Previously ran at module level; now properly encapsulated.
#       Enables:  python pipeline/07_smallcap_scanner.py      (standalone)
#                 subprocess call from main_engine.py SMALLCAP_PIPELINE
# ═══════════════════════════════════════════════════════════════════════════════

def main():
    # ── [CHANGE] Cache initialised inside main() instead of at module level ──
    cache = CacheManager(spreadsheet, verbose=True)

    # ── Test mode ─────────────────────────────────────────────────────────────
    TEST_MODE    = os.environ.get('QUANT_TEST_MODE') == 'true'
    ANALYZE_ONLY = os.environ.get('QUANT_ANALYZE_ONLY') == 'true'
    TEST_LIMIT   = 50
    if TEST_MODE:
        print("\n⚠️  TEST MODE : SmallCap universe limited to 50 tickers each")
    if ANALYZE_ONLY:
        print("\n⚡ ANALYZE-ONLY MODE : re-scoring from Company_Master cache "
              "(no API calls, no Naver scraping, no Volume_Surge)")

    # ── Market filter (set by --ussmallcap / --krsmallcap in main_engine.py) ─
    _sc_market = os.environ.get('QUANT_SMALLCAP_MARKET', '').upper()
    RUN_US = _sc_market in ('', 'BOTH', 'US')
    RUN_KR = _sc_market in ('', 'BOTH', 'KR')
    if _sc_market == 'US':
        print("\n🔬 SMALLCAP MARKET FILTER: US only")
    elif _sc_market == 'KR':
        print("\n🔬 SMALLCAP MARKET FILTER: KR only")

    # ── PIPELINE 1 : US Small-Cap ────────────────────────────────────────────
    top_us = pd.DataFrame()
    df_us  = pd.DataFrame()

    if not RUN_US:
        print("\n⏭️  Skipping US SmallCap pipeline (QUANT_SMALLCAP_MARKET=KR)")
    else:
        print("\n" + "=" * 65)
        print("  PIPELINE 1 : US Small-Cap ($100M ~ $1B)")
        print("=" * 65)

        if ANALYZE_ONLY:
            # Read tickers from Company_Master cache (US = no .KS/.KQ suffix)
            us_raw = [t for t in cache._mem.keys()
                      if not t.endswith(('.KS', '.KQ'))]
            print(f"  Loaded {len(us_raw)} US tickers from Company_Master cache")
        else:
            us_raw = get_us_tickers()
        if TEST_MODE:
            us_raw = us_raw[:TEST_LIMIT]
            print(f"⚠️  TEST MODE: trimmed to {len(us_raw)} US tickers")
        print(f"  Total US candidates : {len(us_raw)}")

        # Pre-warm cache (skipped in analyze-only — cache is already warm)
        if not ANALYZE_ONLY:
            print("\n  Pre-warming cache for US universe...")
            cache.prefetch(us_raw, delay=0.3)

        us_rows = []
        for i, t in enumerate(us_raw, 1):
            try:
                print(f"  [{i}/{len(us_raw)}] {t}...", end=" ")
                row = fetch_us(t, cache, analyze_only=ANALYZE_ONLY)
                if row:
                    us_rows.append(row)
                    print(f"✅ score={row['Total_Score']}  "
                          f"mcap=${row['MarketCap']/1e6:.0f}M")
                else:
                    print("skip")
                if not ANALYZE_ONLY:
                    # Rate-limit only the live Volume_Surge history call
                    time.sleep(0.15)
            except Exception as e:
                print(f"ERR ({e})")

        df_us = pd.DataFrame(us_rows) if us_rows else pd.DataFrame()
        if not df_us.empty:
            df_us = df_us.sort_values('Total_Score', ascending=False).reset_index(drop=True)
            df_us['Rank'] = df_us.index + 1
            top_us = df_us.head(TOP_N)
            print(f"\n✅ US valid={len(df_us)}  top {TOP_N}: "
                  f"{top_us['Ticker'].iloc[0]} (score {top_us['Total_Score'].iloc[0]:.1f})")
        else:
            print("⚠️  No US results")

    # ── PIPELINE 2 : KR Small-Cap ────────────────────────────────────────────
    top_kr = pd.DataFrame()
    df_kr  = pd.DataFrame()

    if not RUN_KR:
        print("\n⏭️  Skipping KR SmallCap pipeline (QUANT_SMALLCAP_MARKET=US)")
    else:
        print("\n" + "=" * 65)
        print("  PIPELINE 2 : KR Small-Cap (1000억 ~ 10조 KRW)")
        print("=" * 65)

        if ANALYZE_ONLY:
            # Read tickers from Company_Master cache (KR = .KS or .KQ suffix)
            kr_tickers = [t for t in cache._mem.keys()
                          if t.endswith(('.KS', '.KQ'))]
            print(f"  Loaded {len(kr_tickers)} KR tickers from Company_Master cache")
        else:
            kr_tickers = get_kr_tickers()
        if TEST_MODE:
            kr_tickers = kr_tickers[:TEST_LIMIT]
            print(f"⚠️  TEST MODE: trimmed to {len(kr_tickers)} KR tickers")
        print(f"  Total KR candidates : {len(kr_tickers)}")

        # Pre-warm cache (skipped in analyze-only — cache is already warm)
        if not ANALYZE_ONLY:
            print("\n  Pre-warming cache for KR universe...")
            cache.prefetch(kr_tickers, delay=0.3)

        kr_rows = []
        for i, t in enumerate(kr_tickers, 1):
            try:
                print(f"  [{i}/{len(kr_tickers)}] {t}...", end=" ")
                row = fetch_kr(t, cache, analyze_only=ANALYZE_ONLY)
                if row:
                    kr_rows.append(row)
                    print(f"✅ score={row['Total_Score']}  "
                          f"mcap={row['MarketCap']/1e8:.0f}억")
                else:
                    print("skip")
                if not ANALYZE_ONLY:
                    time.sleep(0.2)  # only for Naver + Volume history calls
            except Exception as e:
                print(f"ERR ({e})")

        df_kr = pd.DataFrame(kr_rows) if kr_rows else pd.DataFrame()
        if not df_kr.empty:
            df_kr = df_kr.sort_values('Total_Score', ascending=False).reset_index(drop=True)
            df_kr['Rank'] = df_kr.index + 1
            top_kr = df_kr.head(TOP_N)
            print(f"\n✅ KR valid={len(df_kr)}  top {TOP_N}: "
                  f"{top_kr['Ticker'].iloc[0]} (score {top_kr['Total_Score'].iloc[0]:.1f})")
        else:
            print("⚠️  No KR results")

    # ── SAVE TO GOOGLE SHEETS ─────────────────────────────────────────────────
    cache.flush(label='smallcap scanner')   # batch-write any buffered cache updates
    now = pd.Timestamp.now().strftime('%Y-%m-%d %H:%M')
    today = pd.Timestamp.now().strftime('%Y-%m-%d')

    # Standard schema per CLAUDE.md §5 SMALLCAP_COLS
    SMALLCAP_COLS = [
        'Rank', 'Ticker', 'Name', 'Market', 'MarketCap',
        'ROIC', 'RevGrowth', 'PEG', 'GrossMargin', 'FCF_Margin',
        'Debt_EBITDA', 'Volume_Surge', 'SmallCap_Bonus', 'Total_Score', 'Last_Updated',
    ]

    # ── US ────────────────────────────────────────────────────────────────────
    if RUN_US:
        if not top_us.empty:
            top_us['Last_Updated'] = today

        us_summary = [
            [''],
            ['== US_SmallCap_Gems =='],
            ['Generated',        now],
            ['Universe',         'Russell 2000 / S&P 600'],
            ['MarketCap Filter', '$100M ~ $1B USD'],
            ['Valid Stocks',     str(len(df_us))],
            ['Top Stock',        (f"{top_us['Ticker'].iloc[0]}  "
                                  f"(score {top_us['Total_Score'].iloc[0]:.1f})")
                                 if not top_us.empty else 'N/A'],
            ['Expected CAGR',    '25~40%  (small-cap growth, historical)'],
            ['Scoring',          'ROIC+RevGrowth+PEG+GrossMargin+FCF+Debt+VolSurge+SmallCapBonus'],
            ['Cache',            f'Company_Master: {len(cache._mem)} tickers cached'],
        ]

        df_to_sheet(top_us, 'US_SmallCap_Gems', SMALLCAP_COLS, us_summary)

    # ── KR ────────────────────────────────────────────────────────────────────
    if RUN_KR:
        if not top_kr.empty:
            top_kr['Last_Updated'] = today

        kr_summary = [
            [''],
            ['== KR_SmallCap_Gems =='],
            ['Generated',        now],
            ['Universe',         'KOSDAQ Full + KOSPI Bottom-30%'],
            ['MarketCap Filter', '1000억 ~ 10조 KRW'],
            ['Valid Stocks',     str(len(df_kr))],
            ['Top Stock',        (f"{top_kr['Ticker'].iloc[0]}  "
                                  f"(score {top_kr['Total_Score'].iloc[0]:.1f})")
                                 if not top_kr.empty else 'N/A'],
            ['Expected CAGR',    '25~40%  (KR small-cap growth, historical)'],
            ['Scoring',          'ROIC+RevGrowth+GrossMargin(Naver)+FCF+Debt+VolSurge+SmallCapBonus'],
            ['Cache',            f'Company_Master: {len(cache._mem)} tickers cached'],
        ]

        df_to_sheet(top_kr, 'KR_SmallCap_Gems', SMALLCAP_COLS, kr_summary)

    # ── CONSOLE PREVIEW ───────────────────────────────────────────────────────
    if RUN_US:
        print("\n" + "=" * 65)
        print("  US SmallCap GEMS – Top 5")
        print("=" * 65)
        if not top_us.empty:
            cols = ['Rank','Ticker','MarketCap','ROIC','RevGrowth','PEG','Total_Score']
            print(top_us[[c for c in cols if c in top_us.columns]]
                  .head(5).to_string(index=False))

    if RUN_KR:
        print("\n" + "=" * 65)
        print("  KR SmallCap GEMS – Top 5")
        print("=" * 65)
        if not top_kr.empty:
            cols = ['Rank','Ticker','Name','MarketCap','ROIC','RevGrowth','Total_Score']
            print(top_kr[[c for c in cols if c in top_kr.columns]]
                  .head(5).to_string(index=False))

    cache.print_stats()

    print("\n" + "=" * 65)
    print("  Expected CAGR   : 25~40%  (top small-cap growth)")
    print("  Universe        : Russell2000 (US)  |  KOSDAQ+KOSPI-B30% (KR)")
    print("  MarketCap       : $100M~$1B (US)   |  1000억~10조 (KR)")
    print("  Scoring         : ROIC + RevGrowth + PEG + GrossMargin")
    print("                    + FCF + Debt + VolSurge + SmallCapBonus")
    print("  Cache           : Company_Master in Jino_Quant_Database")
    print("=" * 65)


# ── [NEW] Entry point guard ───────────────────────────────────────────────────
# Enables both:
#   python pipeline/07_smallcap_scanner.py                (standalone run)
#   subprocess call from main_engine.py      (SMALLCAP_PIPELINE)
if __name__ == '__main__':
    main()
