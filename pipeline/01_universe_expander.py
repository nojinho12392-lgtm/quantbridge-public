# ── Path bootstrap ─────────────────────────────────────────────────────────
import os, sys
_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
_KEY_JSON = os.path.join(_ROOT, 'key.json')
# ───────────────────────────────────────────────────────────────────────────
import os
import logging
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed

import yfinance as yf

# Suppress noisy internal warnings from yfinance / curl_cffi (curl timeout errors etc.)
for _lg in ('yfinance', 'urllib3', 'peewee', 'curl_cffi', 'curl_cffi.requests'):
    logging.getLogger(_lg).setLevel(logging.CRITICAL)
from sheets_client import get_spreadsheet
import pandas as pd
import numpy as np
import requests
import time
from io import StringIO
from datetime import datetime

# ── Cache integration ─────────────────────────────────────────────────────────
from cache_manager import CacheManager
from kr_sector_map import (
    UNCLASSIFIED_SECTOR,
    sector_for_ticker,
    update_kr_sector_map,
)
from quantbridge.ticker_policy import (
    banned_tickers_label,
    drop_banned_ticker_rows,
    is_banned_ticker,
)
from quantbridge.writers.dual_write import dual_write_dataframe

# ── Google Sheets connection ──────────────────────────────────────────────────
scope = ["https://spreadsheets.google.com/feeds", "https://www.googleapis.com/auth/drive"]
spreadsheet = get_spreadsheet()
sheet = spreadsheet.worksheet('US_Universe')

# Initialise cache (Company_Master bulk-loaded into memory once)
cache = CacheManager(spreadsheet, verbose=True)

# ── Test mode (set by main_engine.py --test flag) ────────────────────────────
TEST_MODE  = os.environ.get('QUANT_TEST_MODE') == 'true'
TEST_LIMIT = 50   # process only 10 tickers per universe
if TEST_MODE:
    print("\n⚠️  TEST MODE : US + KR universe limited to 10 tickers each")

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 '
                  '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
}

# ── Google Sheets retry helper ────────────────────────────────────────────────
def _sheets_write(ws, data, retries=5, base_delay=30):
    """Clear + update a worksheet with exponential-backoff retry on network/quota errors."""
    import gspread.exceptions
    for attempt in range(1, retries + 1):
        try:
            ws.clear()
            # Explicit range_name='A1' avoids gspread 6.x ambiguity when first arg is a list
            ws.update(range_name='A1', values=data, value_input_option='USER_ENTERED')
            return
        except Exception as e:
            is_network = isinstance(e, (
                requests.exceptions.ConnectionError,
                requests.exceptions.Timeout,
            )) or 'TransportError' in type(e).__name__ or 'NewConnectionError' in str(e)
            # Also retry on Google API quota / rate-limit errors (429)
            is_quota = isinstance(e, gspread.exceptions.APIError) and (
                '429' in str(e) or 'RESOURCE_EXHAUSTED' in str(e) or 'quota' in str(e).lower()
            )
            is_retryable = is_network or is_quota
            if attempt == retries or not is_retryable:
                raise
            wait = base_delay * (2 ** (attempt - 1))
            reason = "quota/rate-limit" if is_quota else "network"
            print(f"  ⚠️  {reason} error writing to Sheets (attempt {attempt}/{retries}): {e}")
            print(f"  Retrying in {wait}s...")
            time.sleep(wait)

# ── Standard column schemas ───────────────────────────────────────────────────
UNIVERSE_COLS = [
    'Ticker', 'Name', 'Market', 'Sector', 'MarketCap',
    'PER', 'PBR', 'ROE', 'Revenue', 'RevenueGrowth',
    'OperatingMargin', 'GrossMargin', 'DebtToEquity', 'Last_Updated',
]

# ── US Universe helpers ───────────────────────────────────────────────────────
def get_sp500():
    url = 'https://en.wikipedia.org/wiki/List_of_S%26P_500_companies'
    resp = requests.get(url, headers=HEADERS)
    df = pd.read_html(StringIO(resp.text))[0]
    tickers = [t.replace('.', '-') for t in df['Symbol'].tolist()]
    print(f"  S&P 500 : {len(tickers)} tickers")
    return tickers

def get_nasdaq100():
    url = 'https://en.wikipedia.org/wiki/Nasdaq-100'
    resp = requests.get(url, headers=HEADERS)
    tables = pd.read_html(StringIO(resp.text))
    for t in tables:
        if 'Ticker' in t.columns:
            tickers = [tk.replace('.', '-') for tk in t['Ticker'].tolist()]
            print(f"  NASDAQ-100 : {len(tickers)} tickers")
            return tickers
    print("  NASDAQ-100 : could not parse, skipping")
    return []

# ── Korean Universe helpers ───────────────────────────────────────────────────
def _fdr_top_codes(market, top_n):
    """Return top_n codes sorted by market cap (Marcap) via FinanceDataReader."""
    import FinanceDataReader as fdr
    df = fdr.StockListing(market)
    if 'Code' not in df.columns or len(df) == 0:
        raise ValueError(f"No Code column in fdr.StockListing('{market}')")
    if 'Market' in df.columns:
        before = len(df)
        df = df[~df['Market'].str.contains('ETF', case=False, na=False)]
        removed = before - len(df)
        if removed:
            print(f"    (ETF filter: {removed}개 제거)")
    if 'Marcap' in df.columns:
        df = df.sort_values('Marcap', ascending=False)
    return df['Code'].astype(str).str.zfill(6).head(top_n).tolist()

def _naver_top_codes(sosok, max_codes):
    """
    Scrape Naver sise_market_sum (sorted by market cap desc) up to max_codes.
    Handles per-page timeouts gracefully: retries once, then skips the page.
    Returns whatever codes were collected if Naver is slow or partially unreachable.
    """
    from bs4 import BeautifulSoup
    codes = []
    consecutive_failures = 0
    for page in range(1, 60):
        for attempt in range(2):   # one retry per page
            try:
                resp = requests.get(
                    'https://finance.naver.com/sise/sise_market_sum.nhn',
                    params={'sosok': str(sosok), 'page': str(page)},
                    headers=HEADERS, timeout=20,
                )
                resp.raise_for_status()
                break   # success
            except Exception as e:
                if attempt == 0:
                    time.sleep(2)   # brief pause before retry
                else:
                    print(f"    Naver 페이지 {page} 수집 실패 ({type(e).__name__}) — 건너뜀")
                    resp = None
        else:
            resp = None

        if resp is None:
            consecutive_failures += 1
            if consecutive_failures >= 3:
                print(f"    Naver 연속 3페이지 실패 — 수집 중단 ({len(codes)}개 확보)")
                break
            continue
        consecutive_failures = 0

        soup = BeautifulSoup(resp.text, 'html.parser')
        found = 0
        for a in soup.select('a[href*="code="]'):
            code = a['href'].split('code=')[-1][:6]
            if len(code) == 6 and code.isdigit() and code not in codes:
                codes.append(code)
                found += 1
        if found == 0 or len(codes) >= max_codes:
            break
        time.sleep(0.3)
    return codes[:max_codes]

def get_kospi300():
    try:
        codes   = _fdr_top_codes('KOSPI', 300)
        tickers = [c + '.KS' for c in codes]
        print(f"  KOSPI300 : {len(tickers)}개 종목 확보 (FDR 시총 상위)")
        return tickers
    except Exception:
        print(f"    KRX 직접 연결 불가 — Naver Finance로 대체 수집 중...")
        try:
            codes   = _naver_top_codes(sosok=0, max_codes=300)
            tickers = [c + '.KS' for c in codes]
            print(f"    Naver Finance 수집 완료 : {len(tickers)}개 종목")
            return tickers
        except Exception as e:
            print(f"    Naver Finance도 실패 ({e}) — KOSPI 건너뜀")
            return []

def get_kosdaq200():
    try:
        codes   = _fdr_top_codes('KOSDAQ', 200)
        tickers = [c + '.KQ' for c in codes]
        print(f"  KOSDAQ200 : {len(tickers)}개 종목 확보 (FDR 시총 상위)")
        return tickers
    except Exception:
        print(f"    KRX 직접 연결 불가 — Naver Finance로 대체 수집 중...")
        try:
            codes   = _naver_top_codes(sosok=1, max_codes=200)
            tickers = [c + '.KQ' for c in codes]
            print(f"    Naver Finance 수집 완료 : {len(tickers)}개 종목")
            return tickers
        except Exception as e:
            print(f"    Naver Finance도 실패 ({e}) — KOSDAQ 건너뜀")
            return []

# ── Naver Finance fundamentals (Korean stocks only) ───────────────────────────

# Module-level pre-fetch cache populated by _batch_prefetch_naver() before the
# KR main loop.  fetch_naver_fundamentals() checks this first so the main loop
# pays zero network cost per ticker once the parallel pre-fetch is done.
_NAVER_PREFETCH: dict = {}
_NAVER_LOCK = threading.Lock()


def _batch_prefetch_naver(codes: list, max_workers: int = 5, delay: float = 0.3):
    """
    Pre-fetch Naver fundamentals for all codes in parallel.
    Results stored in _NAVER_PREFETCH so subsequent fetch_naver_fundamentals()
    calls return instantly (no HTTP request).

    Constraints:
      - max_workers=5 to avoid Naver IP-ban (≤5 concurrent connections safe)
      - delay=0.3 s sleep inside each worker between requests
      - No gspread writes — pure HTTP scraping
    """
    total = len(codes)
    workers = min(max_workers, 3 if TEST_MODE else max_workers)
    print(f"\n  [Naver] Parallel prefetch: {total} codes  (workers={workers}, delay={delay}s)")
    _done = [0]

    def _worker(code):
        # Check if already pre-fetched (e.g. duplicate codes)
        if code in _NAVER_PREFETCH:
            return
        try:
            result = _do_fetch_naver(code)      # raw HTTP scrape (no cache check)
        except Exception:
            result = {}
        time.sleep(delay)
        with _NAVER_LOCK:
            _NAVER_PREFETCH[code] = result
            _done[0] += 1
            i = _done[0]
        if i % 100 == 0 or i == total:
            print(f"  [Naver {i}/{total}] prefetched...", flush=True)

    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [executor.submit(_worker, c) for c in codes]
        for fut in as_completed(futures):
            try:
                fut.result()
            except Exception:
                pass

    print(f"  ✅ Naver prefetch done: {len(_NAVER_PREFETCH)}/{total} codes cached")


def _do_fetch_naver(code):
    """Raw Naver scrape with no prefetch-cache check. Internal use only."""
    from bs4 import BeautifulSoup

    url  = 'https://finance.naver.com/item/main.naver'
    resp = requests.get(url, params={'code': code}, headers=HEADERS, timeout=10)
    soup = BeautifulSoup(resp.text, 'html.parser')

    result = {
        'PER_Trailing': None, 'PER_Forward': None, 'PBR': None, 'ROE': None,
        'OperatingMargin': None, 'GrossMargin': None,
        'RevenueGrowth': None, 'DebtToEquity': None,
    }

    per_table = soup.select_one('table.per_table')
    if per_table:
        for row in per_table.select('tr'):
            th = row.find('th')
            td = row.find('td')
            if not th or not td:
                continue
            label    = th.get_text(strip=True)
            val_cell = td.get_text(strip=True)
            raw = val_cell.split('배')[0].replace(',', '')
            try:
                val = float(raw)
            except ValueError:
                continue
            if label.startswith('PER') and '추정' not in label:
                result['PER_Trailing'] = val
            elif '추정PER' in label:
                result['PER_Forward'] = val
            elif label.startswith('PBR'):
                result['PBR'] = val

    # Note: '매출총이익률' (GrossMargin) is NOT present in tb_type1_ifrs on the
    # Naver main page — it's excluded from that summary table.
    # GrossMargin falls back to yfinance grossMargins in fetch_fundamentals().
    LABEL_MAP = {
        'ROE(지배주주)': ('ROE',            True),
        '영업이익률':    ('OperatingMargin', True),
        '매출총이익률':  ('GrossMargin',     True),
        '부채비율':      ('DebtToEquity',    False),
    }
    COL_CURR = 3
    COL_PREV = 2

    fin_table = soup.select_one('table.tb_type1_ifrs')
    if fin_table:
        for row in fin_table.select('tr'):
            cells = [td.get_text(strip=True) for td in row.select('td, th')]
            if len(cells) <= COL_CURR:
                continue
            label = cells[0]
            for key, (field, pct) in LABEL_MAP.items():
                if label == key:
                    raw = cells[COL_CURR].replace(',', '').replace('%', '')
                    try:
                        val = float(raw)
                        result[field] = val / 100 if pct else val
                    except ValueError:
                        pass
            if label == '매출액' and len(cells) > COL_CURR:
                try:
                    curr = float(cells[COL_CURR].replace(',', ''))
                    prev = float(cells[COL_PREV].replace(',', ''))
                    if prev > 0:
                        result['RevenueGrowth'] = round((curr - prev) / prev, 4)
                except (ValueError, IndexError):
                    pass

    return result


def fetch_naver_fundamentals(code):
    """Scrape PER, PBR, ROE, OperatingMargin, GrossMargin, RevenueGrowth,
    DebtToEquity from Naver Finance. Returns a dict; missing values are None.

    Checks _NAVER_PREFETCH first (populated by _batch_prefetch_naver before
    the KR main loop) so the main loop has zero HTTP overhead per ticker.
    """
    if code in _NAVER_PREFETCH:
        return _NAVER_PREFETCH[code]

    # Fallback: live fetch (not pre-fetched, e.g. codes added after prefetch)
    url  = 'https://finance.naver.com/item/main.naver'
    resp = requests.get(url, params={'code': code}, headers=HEADERS, timeout=10)
    soup = BeautifulSoup(resp.text, 'html.parser')

    result = {
        'PER_Trailing': None, 'PER_Forward': None, 'PBR': None, 'ROE': None,
        'OperatingMargin': None, 'GrossMargin': None,
        'RevenueGrowth': None, 'DebtToEquity': None,
    }

    # ── PER / PBR from table.per_table ──────────────────────────────────────
    per_table = soup.select_one('table.per_table')
    if per_table:
        for row in per_table.select('tr'):
            th = row.find('th')
            td = row.find('td')
            if not th or not td:
                continue
            label    = th.get_text(strip=True)
            val_cell = td.get_text(strip=True)
            raw = val_cell.split('배')[0].replace(',', '')
            try:
                val = float(raw)
            except ValueError:
                continue
            if label.startswith('PER') and '추정' not in label:
                result['PER_Trailing'] = val
            elif '추정PER' in label:
                result['PER_Forward'] = val
            elif label.startswith('PBR'):
                result['PBR'] = val

    # ── Annual financial table (IFRS consolidated, most recent year) ────────
    LABEL_MAP = {
        'ROE(지배주주)': ('ROE',            True),   # pct → ratio
        '영업이익률':    ('OperatingMargin', True),
        '매출총이익률':  ('GrossMargin',     True),
        '부채비율':      ('DebtToEquity',    False),  # keep as percentage (50 = 50%)
    }
    COL_CURR = 3   # most recent actual year
    COL_PREV = 2   # prior year (for RevenueGrowth)

    fin_table = soup.select_one('table.tb_type1_ifrs')
    if fin_table:
        for row in fin_table.select('tr'):
            cells = [td.get_text(strip=True) for td in row.select('td, th')]
            if len(cells) <= COL_CURR:
                continue
            label = cells[0]
            for key, (field, pct) in LABEL_MAP.items():
                if label == key:
                    raw = cells[COL_CURR].replace(',', '').replace('%', '')
                    try:
                        val = float(raw)
                        result[field] = val / 100 if pct else val
                    except ValueError:
                        pass
            # Revenue Growth from 매출액 row
            if label == '매출액' and len(cells) > COL_CURR:
                try:
                    curr = float(cells[COL_CURR].replace(',', ''))
                    prev = float(cells[COL_PREV].replace(',', ''))
                    if prev > 0:
                        result['RevenueGrowth'] = round((curr - prev) / prev, 4)
                except (ValueError, IndexError):
                    pass

    return result

# ── Shared fundamentals fetcher ───────────────────────────────────────────────
def _make_yf_session(timeout: int = 10):
    """requests.Session with per-request timeout — prevents indefinite hangs."""
    from requests.adapters import HTTPAdapter
    from urllib3.util.retry import Retry
    session = requests.Session()
    adapter = HTTPAdapter(max_retries=Retry(total=2, backoff_factor=1.0,
                                            status_forcelist=[429, 500, 502, 503, 504],
                                            allowed_methods=['GET'], raise_on_status=False))
    session.mount('https://', adapter)
    session.mount('http://', adapter)
    _orig = session.request
    def _req(method, url, **kw):
        kw.setdefault('timeout', timeout)
        return _orig(method, url, **kw)
    session.request = _req
    return session


def fetch_fundamentals(ticker, korean=False):
    """
    Assemble a UNIVERSE_COLS row from cache (pre-warmed by prefetch/batch_fetch).
    No yf.Ticker().get_info() call — zero HTTP overhead in the main loop.
    For KR tickers, Naver overlay is applied on top of cached yfinance fallback values.
    """
    # All fields populated during cache.prefetch() / cache.batch_fetch()
    fin = cache.load_or_fetch_financials(ticker)

    row = {
        "Ticker":          ticker,
        "Name":            fin.get("Name") or ticker,
        "Sector":          fin.get("Sector") or "",
        "MarketCap":       fin.get("MarketCap"),
        "PER":             fin.get("PER"),
        "PBR":             fin.get("PBR"),
        "ROE":             fin.get("ROE"),
        "Revenue":         fin.get("Revenue"),
        "RevenueGrowth":   fin.get("RevGrowth"),
        "OperatingMargin": fin.get("OperatingMargin"),
        "GrossMargin":     fin.get("GrossMargin"),
        "DebtToEquity":    fin.get("DebtToEquity"),
    }

    # ── For Korean stocks, overlay Naver Finance (more accurate for KR) ────
    if korean:
        code = ticker.split('.')[0]
        try:
            naver = fetch_naver_fundamentals(code)
            if naver.get('PER_Trailing') is not None:
                row['PER'] = naver['PER_Trailing']
            if naver.get('PBR') is not None:
                row['PBR'] = naver['PBR']
            if naver.get('ROE') is not None:
                row['ROE'] = naver['ROE']
            if naver.get('OperatingMargin') is not None:
                row['OperatingMargin'] = naver['OperatingMargin']
            if naver.get('GrossMargin') is not None:
                row['GrossMargin'] = naver['GrossMargin']
            if naver.get('RevenueGrowth') is not None:
                row['RevenueGrowth'] = naver['RevenueGrowth']
            if naver.get('DebtToEquity') is not None:
                row['DebtToEquity'] = naver['DebtToEquity']

            # Persist Naver results back to cache for downstream scripts
            cache_patch = {}
            if naver.get('PER_Trailing')  is not None:
                cache_patch['PER_Last']         = round(naver['PER_Trailing'], 2)
            if naver.get('PBR')           is not None:
                cache_patch['PBR_Last']         = round(naver['PBR'], 2)
            if naver.get('ROE')           is not None:
                cache_patch['ROE_Last']         = round(naver['ROE'], 4)
            if naver.get('OperatingMargin') is not None:
                cache_patch['OperatingMargin_Last'] = round(naver['OperatingMargin'], 4)
            if naver.get('GrossMargin')   is not None:
                cache_patch['GrossMargin_Last'] = round(naver['GrossMargin'], 4)
            if naver.get('RevenueGrowth') is not None:
                cache_patch['RevGrowth_Last']   = round(naver['RevenueGrowth'], 4)
            if naver.get('DebtToEquity')  is not None:
                cache_patch['DebtToEquity_Last'] = round(naver['DebtToEquity'], 2)
            if cache_patch:
                cache.update_row(ticker, cache_patch)
        except Exception:
            pass

    return row


def _round_row(row):
    """Round numeric fields to 4 decimal places; keep integers intact."""
    FOUR_DP = ['ROE', 'RevenueGrowth', 'OperatingMargin', 'GrossMargin']
    TWO_DP  = ['PER', 'PBR', 'DebtToEquity']
    for col in FOUR_DP:
        if row.get(col) is not None:
            try:
                row[col] = round(float(row[col]), 4)
            except (TypeError, ValueError):
                pass
    for col in TWO_DP:
        if row.get(col) is not None:
            try:
                row[col] = round(float(row[col]), 2)
            except (TypeError, ValueError):
                pass
    return row


def _to_universe_df(data, market):
    """Convert list of raw row dicts to standardised universe DataFrame."""
    df = pd.DataFrame(data)
    df['Market']       = market
    df['Last_Updated'] = datetime.now().strftime('%Y-%m-%d')
    # Select and order columns per schema (fill missing with "")
    for col in UNIVERSE_COLS:
        if col not in df.columns:
            df[col] = ""
    df = df[UNIVERSE_COLS]
    df = df.astype(object).where(pd.notnull(df), "")
    return df


# ═════════════════════════════════════════════════════════════════════════════
# PIPELINE 1 : US Universe (S&P 500 + NASDAQ-100)  →  US_Universe
# ═════════════════════════════════════════════════════════════════════════════
print("\n" + "=" * 65)
print("  PIPELINE 1 : US Universe (S&P 500 + NASDAQ-100)")
print("=" * 65)

sp500  = get_sp500()
nasdaq = get_nasdaq100()

# Deduplicate dual-class shares: keep one ticker per company.
# GOOGL (Class A, voting) preferred over GOOG (Class C, non-voting).
DUAL_CLASS_EXCLUDE = {'GOOG'}

seen, us_tickers, blocked = set(), [], []
for t in sp500 + nasdaq:
    if t in seen or t in DUAL_CLASS_EXCLUDE:
        continue
    seen.add(t)
    if is_banned_ticker(t):
        blocked.append(t)
        continue
    us_tickers.append(t)

print(f"Total unique US tickers : {len(us_tickers)}")
if blocked:
    print(f"  Banned US tickers excluded : {', '.join(dict.fromkeys(blocked))}")
elif banned_tickers_label():
    print(f"  Banned US ticker policy active : {banned_tickers_label()}")

if TEST_MODE:
    us_tickers = us_tickers[:TEST_LIMIT]
    print(f"⚠️  TEST MODE: trimmed to {len(us_tickers)} US tickers")

print("\n  Pre-warming cache for US universe...")
cache.prefetch(us_tickers, delay=0.4)

us_data = []
for i, ticker in enumerate(us_tickers, 1):
    if i % 50 == 0 or i == len(us_tickers):
        print(f"  [{i}/{len(us_tickers)}] 처리 중...", flush=True)
    try:
        row = fetch_fundamentals(ticker, korean=False)
        row = _round_row(row)
        us_data.append(row)
    except Exception as e:
        print(f"  ⚠️  [{ticker}] ERROR ({e})")

df_us = drop_banned_ticker_rows(_to_universe_df(us_data, market="US"))
cache.flush(label='US market data')   # batch-write all buffered market data updates

# ── Debug: verify DataFrame before write ──────────────────────────────────────
print(f"\n  [DEBUG] df_us shape: {df_us.shape}")
print(f"  [DEBUG] df_us first 3 rows:\n{df_us.head(3).to_string()}")
write_payload = [df_us.columns.values.tolist()] + df_us.values.tolist()
print(f"  [DEBUG] write payload rows: {len(write_payload)}  cols: {len(write_payload[0]) if write_payload else 0}")
print(f"  [DEBUG] calling: sheet.update(range_name='A1', values=<{len(write_payload)} rows>, value_input_option='USER_ENTERED')")
# ─────────────────────────────────────────────────────────────────────────────

_sheets_write(sheet, write_payload)

# ── Read-back verification ────────────────────────────────────────────────────
_rb = sheet.get_all_values()
print(f"  [DEBUG] read-back row count (incl. header): {len(_rb)}")
if len(_rb) <= 1:
    print("  ⚠️  WARNING: US_Universe appears empty after write — check quota/API errors above")
# ─────────────────────────────────────────────────────────────────────────────

print(f"\n✅ US universe saved : {len(df_us)} stocks → US_Universe")
print(f"   Columns: {list(df_us.columns)}")
dual_write_dataframe("US_Universe", df_us, market="US")

# ═════════════════════════════════════════════════════════════════════════════
# PIPELINE 2 : Korean Universe (ALL KOSPI + ALL KOSDAQ)  →  KR_Universe
# ═════════════════════════════════════════════════════════════════════════════
print("\n" + "=" * 65)
print("  PIPELINE 2 : Korean Universe (KOSPI300 + KOSDAQ200)")
print("=" * 65)

kospi_all  = get_kospi300()
kosdaq_all = get_kosdaq200()

seen_kr, kr_tickers = set(), []
for t in kospi_all + kosdaq_all:
    if t not in seen_kr:
        seen_kr.add(t)
        kr_tickers.append(t)

print(f"  KR 전체 유니버스 : {len(kr_tickers)}개 종목 (중복 제거)")

if TEST_MODE:
    kr_tickers = kr_tickers[:TEST_LIMIT]
    print(f"⚠️  TEST MODE: trimmed to {len(kr_tickers)} KR tickers")

# ── Build code→name map ───────────────────────────────────────────────────────
kr_name_map: dict = {}

from bs4 import BeautifulSoup as _BS
_max_pages = 2 if TEST_MODE else 60
for _sosok in (0, 1):
    for _pg in range(1, _max_pages + 1):
        try:
            _r = requests.get(
                'https://finance.naver.com/sise/sise_market_sum.nhn',
                params={'sosok': str(_sosok), 'page': str(_pg)},
                headers=HEADERS, timeout=20,
            )
            _soup = _BS(_r.text, 'html.parser')
            _found = 0
            for _a in _soup.select('a[href*="code="]'):
                _c = _a['href'].split('code=')[-1][:6]
                if len(_c) == 6 and _c.isdigit() and _c not in kr_name_map:
                    kr_name_map[_c] = _a.get_text(strip=True)
                    _found += 1
            if _found == 0:
                break
            time.sleep(0.2)
        except Exception:
            break
print(f"  종목명 로드 완료 : {len(kr_name_map)}개")

try:
    import FinanceDataReader as fdr
    for _mkt in ('KOSPI', 'KOSDAQ'):
        _lst = fdr.StockListing(_mkt)
        if 'Name' in _lst.columns and 'Code' in _lst.columns:
            _codes = _lst['Code'].astype(str).str.zfill(6)
            kr_name_map.update(dict(zip(_codes, _lst['Name'])))
    print(f"  Name map enriched via FDR : {len(kr_name_map)} entries total")
except Exception:
    pass

# ── Filter non-equity instruments from KR universe ────────────────────────────
# ETFs without a Market column hit (Naver fallback path): catch by name
# REITs    : '리츠' in name  (e.g. 맥쿼리인프라, ESR켄달스퀘어리츠)
# SPACs    : '스팩' in name  (e.g. 삼성머스트스팩8호)
# Preferred: name ends with '우' or digit+'우'+'B'?  (e.g. 삼성전자우, 현대차2우B)
# ETF names: 'ETF' in name  (catches Naver-path ETFs FDR filter missed)
import re as _re

_PREF_RE = _re.compile(r'\d*우B?$')   # matches '우', '2우', '2우B', '1우B' at end

def _is_excluded_kr(ticker: str) -> bool:
    code = ticker.split('.')[0]
    name = kr_name_map.get(code, '')
    if not name:
        return False   # unknown name → keep (better false negative than false positive)
    if '리츠' in name:
        return True    # REIT
    if '스팩' in name:
        return True    # SPAC
    if 'ETF' in name.upper():
        return True    # ETF missed by Market-column filter (Naver path)
    if _PREF_RE.search(name):
        return True    # preferred stock
    return False

_before = len(kr_tickers)
kr_tickers = [t for t in kr_tickers if not _is_excluded_kr(t)]
_removed  = _before - len(kr_tickers)
print(f"\n  KR universe filter: {_before} → {len(kr_tickers)} tickers "
      f"({_removed} ETF/REIT/SPAC/preferred removed)")

print("\n  Building/updating KR_Sector_Map...")
try:
    _sector_fetch_limit = TEST_LIMIT if TEST_MODE else None
    kr_sector_map = update_kr_sector_map(
        spreadsheet,
        kr_tickers,
        names=kr_name_map,
        fetch_missing=True,
        max_fetch=_sector_fetch_limit,
    )
    _classified = sum(
        1 for t in kr_tickers
        if sector_for_ticker(t, kr_sector_map) != UNCLASSIFIED_SECTOR
    )
    print(f"  KR sector map ready: {_classified}/{len(kr_tickers)} classified")
except Exception as e:
    kr_sector_map = {}
    print(f"  ⚠️  KR_Sector_Map update failed: {e}")

# ── Get or create KR_Universe worksheet ───────────────────────────────────
try:
    kr_sheet = spreadsheet.worksheet("KR_Universe")
    if not TEST_MODE:
        kr_sheet.resize(rows=4200, cols=len(UNIVERSE_COLS) + 2)
except gspread.exceptions.WorksheetNotFound:
    kr_sheet = spreadsheet.add_worksheet(title="KR_Universe", rows=4200, cols=len(UNIVERSE_COLS) + 2)

# ── Parallel pre-warm: Naver scraping + yfinance cache for KR universe ────────
if not kr_tickers:
    print("  ⚠️  KR 티커 목록이 비어 있습니다. FDR / Naver 모두 실패 — KR_Universe를 빈 상태로 저장합니다.")
    df_kr = _to_universe_df([], market="KR")
    _sheets_write(kr_sheet, [df_kr.columns.values.tolist()])
    dual_write_dataframe("KR_Universe", df_kr, market="KR")
    print(f"✅ KR_Universe 저장 완료 (데이터 없음)")
    import sys; sys.exit(0)

kr_codes = [t.split('.')[0] for t in kr_tickers]
print(f"\n  Pre-fetching Naver fundamentals for {len(kr_codes)} KR tickers in parallel...")
_batch_prefetch_naver(kr_codes)
print(f"  Pre-warming yfinance cache for {len(kr_tickers)} KR tickers in parallel...")
cache.batch_fetch(kr_tickers)

kr_data = []
for i, ticker in enumerate(kr_tickers, 1):
    if i % 50 == 0 or i == len(kr_tickers):
        print(f"  [{i}/{len(kr_tickers)}] 처리 중...", flush=True)
    try:
        row = fetch_fundamentals(ticker, korean=True)
        code = ticker.split('.')[0]
        if kr_name_map.get(code):
            row['Name'] = kr_name_map[code]
        row['Sector'] = sector_for_ticker(ticker, kr_sector_map, row.get('Sector', ''))
        row = _round_row(row)
        kr_data.append(row)
    except Exception as e:
        print(f"  ⚠️  [{ticker}] ERROR ({e})")

df_kr = _to_universe_df(kr_data, market="KR")
cache.flush(label='KR market data')   # batch-write all buffered market data updates
_sheets_write(kr_sheet, [df_kr.columns.values.tolist()] + df_kr.values.tolist())
print(f"\n✅ Korean universe saved : {len(df_kr)} stocks → KR_Universe  [KOSPI300 + KOSDAQ200]")
print(f"   Columns: {list(df_kr.columns)}")
dual_write_dataframe("KR_Universe", df_kr, market="KR")

# ═════════════════════════════════════════════════════════════════════════════
# SCHEMA SHEET  →  Documents entire database structure
# ═════════════════════════════════════════════════════════════════════════════
print("\n  Creating Schema documentation sheet...")

def get_or_create_ws(name, rows=200, cols=10):
    try:
        return spreadsheet.worksheet(name)
    except gspread.exceptions.WorksheetNotFound:
        return spreadsheet.add_worksheet(title=name, rows=rows, cols=cols)

if TEST_MODE:
    print("⚠️  TEST MODE: Schema sheet update skipped")
else:
    schema_ws = get_or_create_ws("Schema", rows=200, cols=8)
SCHEMA_DATA = [
    ["Jino_Quant_Database — Schema Reference",
     "", "", "", "", "", "", ""],
    [f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M')}",
     "", "", "", "", "", "", ""],
    ["", "", "", "", "", "", "", ""],

    # ── Universe Sheets ────────────────────────────────────────────────────
    ["Sheet", "Column", "Type", "Format", "Market", "Source", "Update Freq", "Notes"],
    ["US_Universe / KR_Universe", "Ticker",         "string",  "AAPL / 005930.KS", "US/KR", "Wikipedia/FDR",  "Weekly",  "Primary key"],
    ["US_Universe / KR_Universe", "Name",           "string",  "Apple Inc.",        "US/KR", "yfinance/Naver",  "Weekly",  "Company name"],
    ["US_Universe / KR_Universe", "Market",         "string",  "US or KR",          "US/KR", "pipeline",        "Static",  "Enables US+KR merge"],
    ["US_Universe / KR_Universe", "Sector",         "string",  "Technology",        "US/KR", "yfinance",        "Weekly",  "GICS sector"],
    ["US_Universe / KR_Universe", "MarketCap",      "number",  "raw USD/KRW",       "US/KR", "yfinance",        "Weekly",  "Raw number, no formatting"],
    ["US_Universe / KR_Universe", "PER",            "number",  "25.00",             "US/KR", "yfinance/Naver",  "Weekly",  "Trailing P/E ratio"],
    ["US_Universe / KR_Universe", "PBR",            "number",  "3.50",              "US/KR", "yfinance/Naver",  "Weekly",  "Price-to-Book ratio"],
    ["US_Universe / KR_Universe", "ROE",            "decimal", "0.1234",            "US/KR", "yfinance/Naver",  "Weekly",  "Return on Equity as decimal"],
    ["US_Universe / KR_Universe", "Revenue",        "number",  "raw USD/KRW",       "US/KR", "yfinance",        "Quarterly","Annual revenue, raw number"],
    ["US_Universe / KR_Universe", "RevenueGrowth",  "decimal", "0.1500",            "US/KR", "yfinance/Naver",  "Quarterly","YoY revenue growth as decimal"],
    ["US_Universe / KR_Universe", "OperatingMargin","decimal", "0.1234",            "US/KR", "yfinance/Naver",  "Quarterly","Operating margin as decimal"],
    ["US_Universe / KR_Universe", "GrossMargin",    "decimal", "0.1234",            "US/KR", "cache/Naver",     "Quarterly","Gross margin as decimal"],
    ["US_Universe / KR_Universe", "DebtToEquity",   "number",  "50.00",             "US/KR", "yfinance/Naver",  "Quarterly","D/E in percentage scale (50=50%)"],
    ["US_Universe / KR_Universe", "Last_Updated",   "date",    "YYYY-MM-DD",        "US/KR", "pipeline",        "Weekly",  "Date of last data refresh"],
    ["", "", "", "", "", "", "", ""],

    # ── Scored Sheets ──────────────────────────────────────────────────────
    ["US_Scored_Stocks / KR_Scored_Stocks", "Rank",           "integer", "1",      "US/KR", "pipeline",    "Weekly",  "Rank by Total_Score"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Ticker",         "string",  "AAPL",   "US/KR", "US_Universe/KU",   "Weekly",  ""],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Name",           "string",  "Apple",  "US/KR", "cache",       "Weekly",  ""],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Market",         "string",  "US/KR",  "US/KR", "pipeline",    "Static",  ""],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Sector",         "string",  "Tech",   "US/KR", "cache",       "Weekly",  ""],
    ["US_Scored_Stocks / KR_Scored_Stocks", "MarketCap",      "number",  "raw",    "US/KR", "yfinance",    "Weekly",  ""],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Value_Score",    "decimal", "0.1234", "US/KR", "pipeline",    "Weekly",  "0-0.40 range"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Quality_Score",  "decimal", "0.1234", "US/KR", "pipeline",    "Weekly",  "0-0.30 range"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Momentum_Score", "decimal", "0.1234", "US/KR", "pipeline",    "Weekly",  "0-0.30 range"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Total_Score",    "decimal", "0.1234", "US/KR", "pipeline",    "Weekly",  "Sum of V+Q+M scores"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Profitability_Quality", "decimal", "0.1234", "US/KR", "pipeline", "Weekly", "Profitability subscore"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Cash_Quality",   "decimal", "0.1234", "US/KR", "pipeline",    "Weekly",  "Cash conversion and FCF quality subscore"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Growth_Quality", "decimal", "0.1234", "US/KR", "pipeline",    "Weekly",  "Growth quality subscore"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "BalanceSheet_Strength", "decimal", "0.1234", "US/KR", "pipeline", "Weekly", "Leverage and solvency subscore"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Valuation_Discipline", "decimal", "0.1234", "US/KR", "pipeline", "Weekly", "Valuation discipline subscore"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Timing_Overlay", "decimal", "0.1234", "US/KR", "pipeline",    "Weekly",  "Momentum and analyst timing subscore"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Persistence_Quality", "decimal", "0.1234", "US/KR", "pipeline", "Weekly", "Multi-year quality persistence subscore"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Business_Quality_Score", "decimal", "0.1234", "US/KR", "pipeline", "Weekly", "Pure business quality score"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Investability_Score", "decimal", "0.1234", "US/KR", "pipeline", "Weekly", "Business quality plus valuation and timing"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Quality_Data_Confidence", "decimal", "0.1234", "US/KR", "pipeline", "Weekly", "Observed metric coverage"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Quality_Red_Flags", "string", "HIGH_DEBT_EBITDA", "US/KR", "pipeline", "Weekly", "Quality and balance-sheet warning flags"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Investability_Rank", "integer", "1", "US/KR", "pipeline", "Weekly", "Rank by Investability_Score"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Business_Quality_Rank", "integer", "1", "US/KR", "pipeline", "Weekly", "Rank by Business_Quality_Score"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Quality_Rank_Delta", "decimal", "12", "US/KR", "pipeline", "Weekly", "Original rank minus investability rank"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Quality_Category", "string", "Core Compounder", "US/KR", "pipeline", "Weekly", "Review category from quality scores"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "ROIC",           "decimal", "0.1234", "US/KR", "cache",       "Quarterly","Return on Invested Capital"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "RevGrowth",      "decimal", "0.1500", "US/KR", "cache",       "Quarterly","Revenue growth YoY"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "GrossMargin",    "decimal", "0.1234", "US/KR", "cache",       "Quarterly",""],
    ["US_Scored_Stocks / KR_Scored_Stocks", "FCF_Margin",     "decimal", "0.1234", "US/KR", "cache",       "Quarterly","Free cash flow margin"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "PEG",            "decimal", "1.2345", "US/KR", "cache",       "Weekly",  "PEG ratio"],
    ["US_Scored_Stocks / KR_Scored_Stocks", "Last_Updated",   "date",    "YYYY-MM-DD", "US/KR","pipeline", "Weekly",  ""],
    ["", "", "", "", "", "", "", ""],

    # ── Portfolio Sheets ───────────────────────────────────────────────────
    ["US_Final_Portfolio / KR_Final_Portfolio", "Rank",           "integer", "1",      "US/KR", "pipeline",    "Weekly",  "By weight desc"],
    ["US_Final_Portfolio / KR_Final_Portfolio", "Ticker",         "string",  "AAPL",   "US/KR", "Scored",      "Weekly",  ""],
    ["US_Final_Portfolio / KR_Final_Portfolio", "Name",           "string",  "Apple",  "US/KR", "cache",       "Weekly",  ""],
    ["US_Final_Portfolio / KR_Final_Portfolio", "Market",         "string",  "US/KR",  "US/KR", "pipeline",    "Static",  ""],
    ["US_Final_Portfolio / KR_Final_Portfolio", "Sector",         "string",  "Tech",   "US/KR", "cache",       "Weekly",  ""],
    ["US_Final_Portfolio / KR_Final_Portfolio", "MarketCap",      "number",  "raw",    "US/KR", "cache",       "Weekly",  ""],
    ["US_Final_Portfolio / KR_Final_Portfolio", "Weight(%)",      "decimal", "0.0500", "US/KR", "optimizer",   "Weekly",  "Portfolio weight as decimal"],
    ["US_Final_Portfolio / KR_Final_Portfolio", "Total_Score",    "decimal", "0.1234", "US/KR", "Scored",      "Weekly",  ""],
    ["US_Final_Portfolio / KR_Final_Portfolio", "ROIC",           "decimal", "0.1234", "US/KR", "cache",       "Quarterly",""],
    ["US_Final_Portfolio / KR_Final_Portfolio", "RevGrowth",      "decimal", "0.1500", "US/KR", "cache",       "Quarterly",""],
    ["US_Final_Portfolio / KR_Final_Portfolio", "GrossMargin",    "decimal", "0.1234", "US/KR", "cache",       "Quarterly",""],
    ["US_Final_Portfolio / KR_Final_Portfolio", "Expected_Return","decimal", "0.1234", "US/KR", "computed",    "Weekly",  "1yr historical return"],
    ["US_Final_Portfolio / KR_Final_Portfolio", "Last_Updated",   "date",    "YYYY-MM-DD","US/KR","pipeline",  "Weekly",  ""],
    ["", "", "", "", "", "", "", ""],

    # ── SmallCap Sheets ────────────────────────────────────────────────────
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "Rank",          "integer", "1",      "US/KR", "pipeline",    "Weekly",  "By Total_Score"],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "Ticker",        "string",  "AAPL",   "US/KR", "IWM/KOSDAQ",  "Weekly",  ""],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "Name",          "string",  "...",    "US/KR", "cache",       "Weekly",  ""],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "Market",        "string",  "US/KR",  "US/KR", "pipeline",    "Static",  ""],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "MarketCap",     "number",  "raw",    "US/KR", "cache",       "Weekly",  ""],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "ROIC",          "decimal", "0.1234", "US/KR", "cache",       "Quarterly",""],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "RevGrowth",     "decimal", "0.1500", "US/KR", "cache/Naver", "Quarterly",""],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "PEG",           "decimal", "1.2345", "US/KR", "cache",       "Weekly",  ""],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "GrossMargin",   "decimal", "0.1234", "US/KR", "cache/Naver", "Quarterly",""],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "FCF_Margin",    "decimal", "0.1234", "US/KR", "cache",       "Quarterly",""],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "Debt_EBITDA",   "decimal", "2.5000", "US/KR", "cache",       "Quarterly","Debt/EBITDA ratio"],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "Volume_Surge",  "decimal", "3.5000", "US/KR", "yfinance",    "Daily",   "3mo avg volume ratio"],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "SmallCap_Bonus","decimal", "10.00",  "US/KR", "pipeline",    "Daily",   "Size bonus (0-15pts)"],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "Data_Confidence","decimal", "0.92",  "US/KR", "pipeline",    "Weekly",  "Metric coverage haircut"],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "Total_Score",   "decimal", "75.00",  "US/KR", "pipeline",    "Weekly",  "Max ~115 pts"],
    ["US_SmallCap_Gems / KR_SmallCap_Gems", "Last_Updated",  "date",    "YYYY-MM-DD","US/KR","pipeline",  "Weekly",  ""],
    ["", "", "", "", "", "", "", ""],

    # ── Cache Sheet ────────────────────────────────────────────────────────
    ["Company_Master (cache)", "Ticker",         "string",  "AAPL",       "US/KR", "primary key", "On fetch", ""],
    ["Company_Master (cache)", "Name",           "string",  "Apple Inc",  "US/KR", "yfinance",    "On fetch", ""],
    ["Company_Master (cache)", "Sector",         "string",  "Technology", "US/KR", "yfinance",    "On fetch", ""],
    ["Company_Master (cache)", "ROIC",           "decimal", "0.1234",     "US/KR", "computed",    "90 days",  "Refreshed when stale or earnings passed"],
    ["Company_Master (cache)", "RevGrowth",      "decimal", "0.1500",     "US/KR", "yfinance",    "90 days",  ""],
    ["Company_Master (cache)", "PEG",            "decimal", "1.2345",     "US/KR", "yfinance",    "90 days",  ""],
    ["Company_Master (cache)", "GrossMargin",    "decimal", "0.1234",     "US/KR", "computed",    "90 days",  ""],
    ["Company_Master (cache)", "FCF_Margin",     "decimal", "0.1234",     "US/KR", "computed",    "90 days",  ""],
    ["Company_Master (cache)", "Debt_EBITDA",    "decimal", "2.5000",     "US/KR", "computed",    "90 days",  ""],
    ["Company_Master (cache)", "Next_Earnings",  "date",    "YYYY-MM-DD", "US/KR", "yfinance",    "Weekly",   "From Earnings_Calendar sheet"],
    ["Company_Master (cache)", "Last_Fin_Update","datetime","ISO format",  "US/KR", "pipeline",    "On fetch", ""],
    ["Company_Master (cache)", "Last_Mkt_Update","datetime","ISO format",  "US/KR", "pipeline",    "Daily",    ""],
]

if not TEST_MODE:
    _sheets_write(schema_ws, SCHEMA_DATA)
    print(f"✅ Schema sheet created : {len(SCHEMA_DATA)} rows")

cache.print_stats()
print("\n✅ Universe expansion complete!")
