"""
cache_manager.py  —  QuantBridge Centralized Caching System
============================================================
Reduces redundant yfinance / Naver API calls by 80%+ by storing
fundamental data in Company_Master (Google Sheet) and only re-fetching
when data is stale (>90 days) or earnings have passed since last update.

Two new worksheets are automatically created inside QuantBridge_Demo_Workbook:

  Company_Master     — per-ticker fundamental + market snapshot
  Earnings_Calendar  — next earnings dates for the universe

Typical usage in any script:
    from cache_manager import CacheManager
    cache = CacheManager(spreadsheet)
    info  = cache.load_or_fetch_financials(ticker)   # dict of metrics
    price = cache.load_market(ticker)                # {'Price', 'Volume', 'MarketCap'}

One-time sheet bootstrap (run once after deployment):
    python cache_manager.py
"""

import os
import logging
import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed

import gspread
import yfinance as yf
import pandas as pd
import numpy as np
import math
import re
import time
import warnings
import requests
import contextlib
import io
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
from bs4 import BeautifulSoup
from datetime import datetime, timedelta

warnings.filterwarnings('ignore')

# Suppress noisy internal logging from yfinance, curl_cffi, and urllib3.
# These libraries emit curl (28) timeout / rate-limit errors as WARNING logs
# even when the exception is handled by our retry logic — very spammy.
for _lg in ('yfinance', 'urllib3', 'peewee', 'curl_cffi', 'curl_cffi.requests'):
    logging.getLogger(_lg).setLevel(logging.CRITICAL)

# SQLite local cache — sits next to cache_manager.py in the project root.
# Never committed (listed in .gitignore). Acts as the fast read layer.
_DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'cache.db')
_ROOT_DIR = os.path.dirname(os.path.abspath(__file__))
_DART_CACHE_DIR = os.path.join(_ROOT_DIR, 'docs_cache')


def _env_bool(name: str, default: bool = False) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in {'1', 'true', 'yes', 'on'}


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, str(default)))
    except (TypeError, ValueError):
        return default


def _normalize_earnings_date(value) -> str:
    """Return YYYY-MM-DD for real calendar dates; reject placeholders like []."""
    text = str(value or '').strip()
    if not text or text.lower() in {'[]', 'none', 'nan', 'nat'}:
        return ''
    match = re.search(r'\d{4}-\d{2}-\d{2}', text)
    if not match:
        return ''
    candidate = match.group(0)
    try:
        datetime.strptime(candidate, '%Y-%m-%d')
    except ValueError:
        return ''
    return candidate

# ══════════════════════════════════════════════════════════════════════════════
# Sheet Schema
# ══════════════════════════════════════════════════════════════════════════════

# Company_Master is universe-agnostic: any ticker from any market or market-cap
# range is stored identically here — large-cap (S&P 500, KOSPI 300), mid-cap,
# and small-cap (Russell 2000 $100M~$1B, full KOSDAQ 1000억~10조) alike.
# MarketCap range filtering is the caller's responsibility (e.g. pipeline/07_smallcap_scanner.py).
# All fields stored per ticker in Company_Master
MASTER_HEADERS = [
    # Identity
    'Ticker', 'Name', 'Sector', 'Market',
    # Core fundamentals (used by 02, 08_gem)
    'ROIC_Last', 'RevGrowth_Last', 'PEG_Last',
    'GrossMargin_Last', 'FCF_Margin_Last', 'Debt_EBITDA_Last',
    # Extended fundamentals (used by 02_factor_scorer, 04_ml)
    'EV_EBITDA_Last', 'EPS_Growth_Last', 'FCF_NI_Last', 'InterestCoverage_Last',
    'DivYield_Last',
    # Derived metrics cached to avoid repeated quarterly_financials fetches
    'RevAccel_Last',
    # Balance sheet fields (used by Altman Z-Score in 02_factor_scorer_us — US only)
    'TotalAssets_Last', 'CurrentAssets_Last', 'CurrentLiabilities_Last',
    'RetainedEarnings_Last', 'TotalLiabilities_Last',
    # Universe fields — stored during 01_universe_expander so the main loop
    # can read them from cache instead of re-calling yf.Ticker().get_info()
    'PER_Last', 'PBR_Last', 'ROE_Last', 'Revenue_Last',
    'OperatingMargin_Last', 'DebtToEquity_Last',
    # Market data (refreshed always)
    'MarketCap_Last', 'Price_Last', 'Volume_Last',
    # Timestamps
    'Last_Fin_Update', 'Last_Mkt_Update',
]

EARNINGS_HEADERS = [
    'Ticker', 'Next_Earnings_Date', 'Market', 'Name', 'Sector', 'MarketCap', 'Last_Updated',
]

# Refresh fundamentals if older than this many days OR if earnings passed
FIN_STALE_DAYS = 90


# ══════════════════════════════════════════════════════════════════════════════
# Private Helpers
# ══════════════════════════════════════════════════════════════════════════════

def _make_yf_session(timeout: int = 10) -> requests.Session:
    """
    Create a requests.Session with per-request timeout and built-in retry.

    Yahoo Finance doesn't expose a timeout parameter directly; patching the
    session is the standard workaround.  Timeout prevents indefinite hangs
    when Yahoo is throttling (curl error 28 / OPERATION_TIMEDOUT).

    Retry logic (status 429/5xx) is handled by urllib3 Retry so we don't
    need to replicate it in _fetch_with_retry for transient HTTP errors.
    """
    session = requests.Session()
    adapter = HTTPAdapter(
        max_retries=Retry(
            total=2,
            backoff_factor=1.0,
            status_forcelist=[429, 500, 502, 503, 504],
            allowed_methods=['GET'],
            raise_on_status=False,
        )
    )
    session.mount('https://', adapter)
    session.mount('http://', adapter)

    # Monkey-patch timeout into every GET call made through this session.
    _orig_request = session.request
    def _request_with_timeout(method, url, **kw):
        kw.setdefault('timeout', timeout)
        return _orig_request(method, url, **kw)
    session.request = _request_with_timeout
    return session


def _quiet_call(fn, *args, **kwargs):
    """Run noisy vendor calls quietly; callers decide fallback behavior."""
    try:
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return fn(*args, **kwargs)
    except Exception:
        return None


def _safe_row(df, *keys):
    """Extract first numeric value from a financial DataFrame by keyword match."""
    if df is None or df.empty:
        return None
    for key in keys:
        for idx in df.index:
            if key.lower() in str(idx).lower():
                try:
                    s = df.loc[idx].dropna()
                    if len(s):
                        return float(s.iloc[0])
                except Exception:
                    pass
    return None


def _is_valid(v):
    """True if v is a usable numeric value (not None, not NaN)."""
    return v is not None and not (isinstance(v, float) and math.isnan(v))


def _to_float(v):
    """Convert string/number to float; return None on failure."""
    try:
        return float(v) if v not in ('', None) else None
    except (ValueError, TypeError):
        return None


def _normalise_cache_dt(dt):
    """Return a naive datetime in a sane cache-date range, else None."""
    if dt is None:
        return None
    if getattr(dt, 'tzinfo', None) is not None:
        dt = dt.astimezone().replace(tzinfo=None)
    if dt < datetime(2000, 1, 1):
        return None
    if dt > datetime.now() + timedelta(days=366):
        return None
    return dt


def _parse_dt(s: str):
    """Parse cache datetimes from ISO, Sheets date serials, or locale formats."""
    if not s:
        return None
    if isinstance(s, datetime):
        return _normalise_cache_dt(s)
    text = str(s).strip()
    if not text:
        return None

    # Google Sheets can round-trip USER_ENTERED datetimes as serial day values.
    try:
        numeric = float(text)
        if 25000 <= numeric <= 90000:
            return _normalise_cache_dt(datetime(1899, 12, 30) + timedelta(days=numeric))
    except (TypeError, ValueError):
        pass

    try:
        return _normalise_cache_dt(datetime.fromisoformat(text.replace('Z', '+00:00')))
    except (ValueError, AttributeError):
        pass

    parsed = pd.to_datetime(text, errors='coerce')
    if pd.isna(parsed):
        return None
    return _normalise_cache_dt(parsed.to_pydatetime())


def _ensure_dart_cache_dir():
    """Create OpenDartReader's cache directory before worker threads race on it."""
    os.makedirs(_DART_CACHE_DIR, exist_ok=True)


# ══════════════════════════════════════════════════════════════════════════════
# CacheManager
# ══════════════════════════════════════════════════════════════════════════════

class CacheManager:
    """
    Centralized Google-Sheet-backed cache for yfinance fundamentals.

    Parameters
    ----------
    spreadsheet : gspread.Spreadsheet
        Already-authorized spreadsheet object (QuantBridge_Demo_Workbook).
    verbose : bool
        Print cache hit/miss messages. Default True.
    stale_days : int
        How many days before fundamentals are considered stale. Default 90.
    """

    def __init__(self, spreadsheet, verbose: bool = True,
                 stale_days: int = FIN_STALE_DAYS):
        self.ss         = spreadsheet
        self.verbose    = verbose
        self.stale_days = stale_days

        # In-memory mirror: { ticker: { col: value_str, ... } }
        self._mem: dict = {}

        # SQLite dual-write layer (primary read source, ~0ms)
        self._db_path = _DB_PATH
        self._db_lock = threading.Lock()   # serialises concurrent SQLite writes
        self._init_sqlite()

        # DART metadata is process-wide expensive. Cache the reader and stock-code
        # map per CacheManager instance, with a lock around first load.
        self._dart_lock = threading.Lock()
        self._dart_client = None
        self._dart_stock_to_corp: dict[str, str] | None = None
        self._dart_api_key_value = ''
        self._dart_missing_key_warned = False
        self._dart_disabled_for_run = (
            _env_bool('QUANT_DISABLE_DART')
            or _env_bool('QUANT_DISABLE_DART_FOR_RUN')
        )
        self._dart_disable_reason = os.environ.get(
            'QUANT_DART_DISABLE_REASON',
            'DART disabled by run configuration',
        ).strip()
        self._dart_disable_warned = False
        self._dart_corp_cache_failures = 0
        self._dart_corp_cache_failure_limit = max(
            1,
            _env_int('QUANT_DART_CORP_CACHE_FAILURE_LIMIT', 2),
        )

        # Bootstrap both worksheets (creates them if absent)
        self._master_ws   = self.get_or_create_worksheet(
            'Company_Master', MASTER_HEADERS)
        self._earnings_ws = self.get_or_create_worksheet(
            'Earnings_Calendar', EARNINGS_HEADERS)

        # Dirty-write buffer: tickers with in-memory changes not yet flushed to Sheets
        self._dirty: set = set()

        # Bulk-load: SQLite first (fast), then merge Sheets (authoritative row-index)
        self._load_master_into_memory()

    # ──────────────────────────────────────────────────────────────────────────
    # Sheet Utilities
    # ──────────────────────────────────────────────────────────────────────────

    def get_or_create_worksheet(self, sheet_name: str, headers: list):
        """Return existing worksheet or create it with the given header row.

        Only catches WorksheetNotFound — all other exceptions (network errors,
        rate limits, API errors) propagate so they are not silently converted
        into a destructive add_worksheet() call on an already-existing sheet.
        """
        try:
            ws = self.ss.worksheet(sheet_name)
            if self.verbose:
                print(f"  📋 Found worksheet: {sheet_name}")
            return ws
        except gspread.exceptions.WorksheetNotFound:
            ws = self.ss.add_worksheet(
                title=sheet_name,
                rows=15000,
                cols=max(len(headers), 20),
            )
            ws.update(range_name='A1', values=[headers], value_input_option='USER_ENTERED')
            print(f"  ✅ Created worksheet: {sheet_name}  ({len(headers)} cols)")
            return ws

    def _load_master_into_memory(self):
        """
        Two-phase bulk load of Company_Master into self._mem.

        Phase 1 — SQLite (~0 ms, local):
            Populates self._mem from the local SQLite DB.  Cold start (first
            ever run, or DB deleted) returns 0 rows and falls through to Phase 2.

        Phase 2 — Google Sheets (~200 ms, network):
            Authoritative source for sheet row numbers (needed by flush()).
            For each ticker already in _mem (from SQLite), Sheets data wins
            only when its Last_Fin_Update is strictly newer — prevents
            accidentally downgrading data written by this session.

        After both phases, _snap is set to the merged state so flush() can
        detect which rows actually changed.
        """
        self._row_index: dict = {}
        self._row_count: int  = 1   # header row occupies row 1
        self._snap:      dict = {}  # frozen at load time; never mutated after this

        # ── Phase 1: SQLite (fast) ────────────────────────────────────────────
        n_sqlite = self._load_sqlite_into_memory()

        # ── Phase 2: Google Sheets (authoritative for row numbers) ────────────
        n_sheets_only   = 0
        n_sheets_newer  = 0

        # Skip Sheets load when SQLite is well-populated (≥500 rows) — avoids
        # hanging on get_all_values() which has no total-response timeout.
        # Row indices are reconstructed from SQLite order; flush() appends new
        # rows and updates existing ones by row number from this index.
        _SHEETS_SKIP_THRESHOLD = 500
        if n_sqlite >= _SHEETS_SKIP_THRESHOLD:
            print(f"  🗄️  SQLite-only mode ({n_sqlite} rows ≥ {_SHEETS_SKIP_THRESHOLD}) — skipping Sheets bulk load", flush=True)
            for i, ticker in enumerate(self._mem, 2):
                self._row_index[ticker] = i
                self._snap[ticker] = dict(self._mem[ticker])
            self._row_count = 1 + len(self._mem)
            return

        print(f"  📡 Company_Master 로딩 중 (Sheets)... SQLite {n_sqlite}행 확보됨", flush=True)
        try:
            result_holder = [None]
            error_holder  = [None]
            def _fetch_rows():
                try:
                    result_holder[0] = self._master_ws.get_all_values()
                except Exception as exc:
                    error_holder[0] = exc
            fetch_thread = threading.Thread(target=_fetch_rows, daemon=True)
            fetch_thread.start()
            fetch_thread.join(timeout=90)  # hard 90s total timeout
            if fetch_thread.is_alive():
                raise TimeoutError("get_all_values() exceeded 90s — using SQLite-only mode")
            if error_holder[0]:
                raise error_holder[0]
            rows = result_holder[0]
        except Exception as e:
            print(f"  ⚠️  Could not load Company_Master from Sheets: {e}")
            # Sheets unreachable — operate in SQLite-only mode
            for i, ticker in enumerate(self._mem, 2):
                self._row_index[ticker] = i
                self._snap[ticker] = dict(self._mem[ticker])
            self._row_count = 1 + len(self._mem)
            print(f"  🗄️  SQLite-only mode: {n_sqlite} tickers")
            return

        if len(rows) >= 2:
            header = rows[0]
            self._row_count = len(rows)

            for sheet_row_idx, row in enumerate(rows[1:], 2):
                padded = row + [''] * len(header)
                d      = dict(zip(header, padded))
                ticker = d.get('Ticker', '').strip()
                if not ticker:
                    continue

                # Always record row number — needed for flush() to update in-place
                self._row_index[ticker] = sheet_row_idx

                if ticker not in self._mem:
                    # Sheets has it, SQLite doesn't → take Sheets value
                    self._mem[ticker] = d
                    n_sheets_only += 1
                else:
                    # Both have it — keep whichever is newer
                    sqlite_dt = _parse_dt(self._mem[ticker].get('Last_Fin_Update', ''))
                    sheets_dt = _parse_dt(d.get('Last_Fin_Update', ''))
                    if sheets_dt and (sqlite_dt is None or sheets_dt > sqlite_dt):
                        self._mem[ticker] = d
                        n_sheets_newer += 1
                    # else: SQLite is newer or equal — already in _mem, keep it

            # Tickers in SQLite but not yet in Sheets (fetched but not flushed yet)
            for ticker in self._mem:
                if ticker not in self._row_index:
                    self._row_count += 1
                    self._row_index[ticker] = self._row_count

        # Build snap from final merged state
        for ticker, d in self._mem.items():
            self._snap[ticker] = dict(d)

        total = len(self._mem)
        if n_sqlite:
            print(
                f"  🗄️  SQLite: {n_sqlite} | Sheets-only: {n_sheets_only} | "
                f"Sheets-newer: {n_sheets_newer} | Total: {total} tickers"
            )
        else:
            print(f"  📂 Loaded {total} tickers from Company_Master (Sheets cold-start)")

    # ──────────────────────────────────────────────────────────────────────────
    # SQLite Layer  (primary read source, dual-write target)
    # ──────────────────────────────────────────────────────────────────────────

    def _init_sqlite(self):
        """
        Create the SQLite DB file and tables if they don't already exist.
        Enables WAL journal mode so reads never block writes and vice-versa.
        Automatically migrates existing tables when MASTER_HEADERS gains new columns.
        """
        cols_ddl = ', '.join(f'"{h}" TEXT' for h in MASTER_HEADERS)
        try:
            with sqlite3.connect(self._db_path, timeout=10) as conn:
                conn.execute('PRAGMA journal_mode=WAL')
                conn.execute(f"""
                    CREATE TABLE IF NOT EXISTS company_master (
                        {cols_ddl},
                        PRIMARY KEY ("Ticker")
                    )
                """)
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS earnings_calendar (
                        "Ticker"              TEXT PRIMARY KEY,
                        "Next_Earnings_Date"  TEXT
                    )
                """)
                # Schema migration: add any columns missing from existing table
                existing_cols = {row[1] for row in
                                 conn.execute('PRAGMA table_info(company_master)').fetchall()}
                added = []
                for h in MASTER_HEADERS:
                    if h not in existing_cols:
                        conn.execute(f'ALTER TABLE company_master ADD COLUMN "{h}" TEXT DEFAULT ""')
                        added.append(h)
                if added:
                    print(f"  🗄️  SQLite 스키마 마이그레이션: {added} 컬럼 추가")
                conn.commit()
            if self.verbose:
                db_mb = os.path.getsize(self._db_path) / 1024 / 1024
                print(f"  🗄️  SQLite cache: {self._db_path}  ({db_mb:.2f} MB)")
        except Exception as e:
            print(f"  ⚠️  SQLite init error: {e}")

    def _load_sqlite_into_memory(self) -> int:
        """
        Bulk-load all rows from SQLite company_master → self._mem.
        Returns the number of rows loaded (0 on error or empty DB).
        """
        try:
            with sqlite3.connect(self._db_path, timeout=10) as conn:
                conn.row_factory = sqlite3.Row
                db_rows = conn.execute('SELECT * FROM company_master').fetchall()
            count = 0
            for row in db_rows:
                d = dict(row)
                ticker = (d.get('Ticker') or '').strip()
                if ticker:
                    self._mem[ticker] = d
                    count += 1
            return count
        except Exception as e:
            print(f"  ⚠️  SQLite load error: {e}")
            return 0

    def _sqlite_upsert(self, ticker: str):
        """
        Write one ticker's row from self._mem → SQLite (INSERT OR REPLACE).
        Thread-safe via self._db_lock.  Called synchronously — takes ~0 ms.
        """
        row = self._mem.get(ticker)
        if row is None:
            return
        cols   = ', '.join(f'"{h}"' for h in MASTER_HEADERS)
        placeh = ', '.join('?' for _ in MASTER_HEADERS)
        values = [str(row.get(h, '')) for h in MASTER_HEADERS]
        try:
            with self._db_lock:
                with sqlite3.connect(self._db_path, timeout=10) as conn:
                    conn.execute(
                        f'INSERT OR REPLACE INTO company_master ({cols}) VALUES ({placeh})',
                        values,
                    )
                    conn.commit()
        except Exception as e:
            print(f"  ⚠️  SQLite upsert error [{ticker}]: {e}")

    def _sqlite_upsert_batch(self, tickers: list):
        """
        Batch INSERT OR REPLACE for a list of tickers from self._mem.
        Used by flush() to sync dirty rows at end of pipeline.
        """
        if not tickers:
            return
        cols   = ', '.join(f'"{h}"' for h in MASTER_HEADERS)
        placeh = ', '.join('?' for _ in MASTER_HEADERS)
        rows   = []
        for ticker in tickers:
            row = self._mem.get(ticker)
            if row:
                rows.append([str(row.get(h, '')) for h in MASTER_HEADERS])
        if not rows:
            return
        try:
            with self._db_lock:
                with sqlite3.connect(self._db_path, timeout=10) as conn:
                    conn.executemany(
                        f'INSERT OR REPLACE INTO company_master ({cols}) VALUES ({placeh})',
                        rows,
                    )
                    conn.commit()
            if self.verbose:
                print(f"  🗄️  SQLite batch upsert: {len(rows)} rows")
        except Exception as e:
            print(f"  ⚠️  SQLite batch upsert error: {e}")

    def _sqlite_upsert_earnings(self, earnings_dict: dict):
        """
        Overwrite the entire earnings_calendar SQLite table with earnings_dict
        {ticker: date_str}.  Called after update_earnings_calendar().
        """
        rows = [(t, d) for t, d in earnings_dict.items() if t]
        if not rows:
            return
        try:
            with self._db_lock:
                with sqlite3.connect(self._db_path, timeout=10) as conn:
                    conn.execute('DELETE FROM earnings_calendar')
                    conn.executemany(
                        'INSERT INTO earnings_calendar '
                        '("Ticker", "Next_Earnings_Date") VALUES (?, ?)',
                        rows,
                    )
                    conn.commit()
            if self.verbose:
                print(f"  🗄️  SQLite earnings saved: {len(rows)} rows")
        except Exception as e:
            print(f"  ⚠️  SQLite earnings upsert error: {e}")

    # ──────────────────────────────────────────────────────────────────────────
    # Row-Level Access
    # ──────────────────────────────────────────────────────────────────────────

    def get_row(self, ticker: str) -> dict:
        """Return the raw (string-valued) cache dict for ticker, or {}."""
        return self._mem.get(ticker, {})

    def get_all_tickers(self) -> list:
        """Return all tickers currently held in the in-memory cache."""
        return list(self._mem.keys())

    def update_row(self, ticker: str, data_dict: dict):
        """
        Merge data into the in-memory cache and persist immediately to SQLite.
        The Sheets write is deferred until flush() is called.

        Write priority:
          SQLite  — synchronous, ~0 ms, survives process restarts
          Sheets  — async (batched at flush()), survives machine changes

        Partial updates are safe — only provided keys are overwritten.
        """
        existing = self._mem.get(ticker, {})
        existing['Ticker'] = ticker
        existing.update({k: str(v) if v is not None else '' for k, v in data_dict.items()})
        if not existing.get('Market'):
            existing['Market'] = self._market_from_ticker(ticker)
        self._mem[ticker] = existing
        self._dirty.add(ticker)
        self._sqlite_upsert(ticker)   # immediate local persist (~0 ms)

    def flush(self, label: str = '') -> int:
        """
        UPSERT all dirty rows to Company_Master without ever clearing the sheet.

        - Existing tickers (_row_index hit): batch_update to their known row.
          Rows whose values are identical to the loaded snapshot are skipped.
        - New tickers (_row_index miss): append_rows to the end of the sheet.

        Both paths are chunked at 1,000 rows per API call.
        Returns the number of rows written.
        """
        if not self._dirty:
            return 0

        tag     = f' [{label}]' if label else ''
        if _env_bool('QUANT_DISABLE_CACHE_FLUSH', False):
            print(
                f"  ⏭  Skipping Company_Master flush{tag} "
                "(QUANT_DISABLE_CACHE_FLUSH=true)"
            )
            return 0

        CHUNK   = 1000
        updates = []   # {'range': 'A{n}', 'values': [[...]]}
        appends = []   # [[...]]

        for ticker in self._dirty:
            row_values = [self._mem[ticker].get(h, '') for h in MASTER_HEADERS]

            if ticker in self._row_index:
                # Existing row — skip if nothing actually changed since load
                snap_values = [self._snap.get(ticker, {}).get(h, '') for h in MASTER_HEADERS]
                if row_values == snap_values:
                    continue
                updates.append({
                    'range':  f'A{self._row_index[ticker]}',
                    'values': [row_values],
                })
            else:
                appends.append(row_values)

        written = 0

        # ── Auto-resize sheet if needed ───────────────────────────────────────
        try:
            current_cap = self._master_ws.row_count
            max_update_row = max(
                (int(u['range'].lstrip('A')) for u in updates), default=0
            )
            needed = max(max_update_row, self._row_count + len(appends)) + 200
            if needed > current_cap:
                new_cap = max(needed, current_cap * 2)
                self._master_ws.resize(rows=new_cap)
                print(f"  📐 Resized Company_Master: {current_cap} → {new_cap} rows")
        except Exception as e:
            print(f"  ⚠️  resize check failed (non-fatal): {e}")

        # ── Update existing rows in-place ─────────────────────────────────────
        if updates:
            print(f"  💾 Updating {len(updates)} existing rows → Company_Master{tag}...")
            for i in range(0, len(updates), CHUNK):
                chunk = updates[i:i + CHUNK]
                try:
                    self._master_ws.batch_update(chunk, value_input_option='USER_ENTERED')
                    written += len(chunk)
                except Exception as e:
                    print(f"  ⚠️  batch_update error (chunk {i // CHUNK + 1}): {e}")
                if i + CHUNK < len(updates):
                    time.sleep(2)

        # ── Append new rows ───────────────────────────────────────────────────
        if appends:
            print(f"  ➕ Appending {len(appends)} new rows → Company_Master{tag}...")
            for i in range(0, len(appends), CHUNK):
                chunk = appends[i:i + CHUNK]
                try:
                    self._master_ws.append_rows(chunk, value_input_option='USER_ENTERED')
                    written += len(chunk)
                    # Register appended tickers so a second flush() this session
                    # treats them as existing rows (avoid duplicate appends)
                    for row_values in chunk:
                        t = row_values[0]   # Ticker is MASTER_HEADERS[0]
                        if t and t not in self._row_index:
                            self._row_count += 1
                            self._row_index[t] = self._row_count
                            self._snap[t] = dict(zip(MASTER_HEADERS, row_values))
                except Exception as e:
                    print(f"  ⚠️  append_rows error (chunk {i // CHUNK + 1}): {e}")
                if i + CHUNK < len(appends):
                    time.sleep(2)

        skipped = len(self._dirty) - len(updates) - len(appends)
        self._dirty.clear()
        print(f"  ✅ Flush done{tag}: {written} written, {skipped} unchanged (skipped)")
        return written

    # ──────────────────────────────────────────────────────────────────────────
    # Staleness Logic
    # ──────────────────────────────────────────────────────────────────────────

    def needs_fin_update(self, ticker: str) -> bool:
        """
        Return True when fundamentals must be re-fetched:

        1. No cache row exists yet.
        2. Last_Fin_Update is missing or unparseable.
        3. Last_Fin_Update is older than stale_days.
        4. An earnings date has passed since the last update
           (new financials likely reported).

        Always returns False in ANALYZE_ONLY mode — cache is read-only.
        """
        if os.environ.get('QUANT_ANALYZE_ONLY') == 'true':
            return False

        row = self.get_row(ticker)
        if not row:
            return True

        last_str = row.get('Last_Fin_Update', '').strip()
        if not last_str:
            return True

        last_dt = _parse_dt(last_str)
        if last_dt is None:
            return True

        # Age-based staleness
        if (datetime.now() - last_dt).days > self.stale_days:
            return True

        # Earnings-triggered staleness
        earn_date = self._cached_earnings_date(ticker)
        if (earn_date
                and earn_date <= datetime.now().date()
                and earn_date > last_dt.date()):
            if self.verbose:
                print(f"  📅 Earnings passed for {ticker} ({earn_date}) → refreshing")
            return True

        # Schema-migration staleness: PER_Last was added 2026-05-05.
        # Only trigger for rows cached BEFORE the migration date — prevents an
        # infinite re-fetch loop for tickers where yfinance simply doesn't
        # provide trailingPE (common for many KR stocks).
        _MIGRATION_DATE = datetime(2026, 5, 5)
        if row.get('PER_Last', '') == '' and last_dt < _MIGRATION_DATE:
            return True

        return False

    def _cached_earnings_date(self, ticker: str):
        """
        Look up Next_Earnings_Date.
        Priority: SQLite earnings_calendar (fast) → Sheets fallback (network).
        Uses a lazy-loaded in-memory dict to avoid repeated reads.
        Returns a datetime.date object or None.
        """
        if not hasattr(self, '_earnings_mem'):
            self._earnings_mem = {}

            # ── Try SQLite first (~0 ms) ──────────────────────────────────────
            try:
                with sqlite3.connect(self._db_path, timeout=10) as conn:
                    db_rows = conn.execute(
                        'SELECT "Ticker", "Next_Earnings_Date" FROM earnings_calendar'
                    ).fetchall()
                for t, d in db_rows:
                    if t:
                        self._earnings_mem[t] = d or ''
            except Exception:
                pass

            # ── Fall back to Sheets if SQLite had no data ─────────────────────
            if not self._earnings_mem:
                try:
                    rows = self._earnings_ws.get_all_values()
                    if len(rows) >= 2:
                        hdr = rows[0]
                        for r in rows[1:]:
                            d = dict(zip(hdr, r + [''] * len(hdr)))
                            t = d.get('Ticker', '').strip()
                            if t:
                                self._earnings_mem[t] = d.get('Next_Earnings_Date', '').strip()
                except Exception:
                    pass

        raw = self._earnings_mem.get(ticker, '')
        if not raw:
            return None
        try:
            return datetime.strptime(raw[:10], '%Y-%m-%d').date()
        except ValueError:
            return None

    # ──────────────────────────────────────────────────────────────────────────
    # Fundamental Data  (cache-first)
    # ──────────────────────────────────────────────────────────────────────────

    def load_or_fetch_financials(self, ticker: str) -> dict:
        """
        Primary entry point for fundamental data.

        Returns a typed dict with these keys (None if unavailable):
            Name, Sector, MarketCap,
            ROIC, RevGrowth, PEG, GrossMargin, FCF_Margin, Debt_EBITDA,
            EV_EBITDA, EPS_Growth, FCF_NI, InterestCoverage

        Cache behaviour:
            FRESH  → returns cached values, prints "Using cache"
            STALE  → fetches from yfinance, updates cache, prints "Updating cache"
        """
        if not self.needs_fin_update(ticker):
            if self.verbose:
                print(f"  ✅ Using cache for {ticker}")
            return self._row_to_fin_dict(self.get_row(ticker))

        if self.verbose:
            print(f"  🔄 Updating cache for {ticker}")

        if ticker.endswith(('.KS', '.KQ')):
            raw = self._fetch_kr_financials(ticker)
        else:
            raw = self._fetch_yf_financials(ticker)
        raw['Last_Fin_Update'] = datetime.now().isoformat()
        self.update_row(ticker, raw)
        return self._row_to_fin_dict(raw)

    def _fetch_yf_financials(self, ticker: str) -> dict:
        """
        Pull raw fundamentals from yfinance and compute derived metrics.
        Returns a flat dict of string/number values ready for update_row().
        """
        result = {
            'Name': ticker, 'Sector': '',
            'ROIC_Last': '', 'RevGrowth_Last': '', 'PEG_Last': '',
            'GrossMargin_Last': '', 'FCF_Margin_Last': '', 'Debt_EBITDA_Last': '',
            'EV_EBITDA_Last': '', 'EPS_Growth_Last': '',
            'FCF_NI_Last': '', 'InterestCoverage_Last': '',
            'DivYield_Last': '',
            'RevAccel_Last': '',
            'TotalAssets_Last': '', 'CurrentAssets_Last': '', 'CurrentLiabilities_Last': '',
            'RetainedEarnings_Last': '', 'TotalLiabilities_Last': '',
            'PER_Last': '', 'PBR_Last': '', 'ROE_Last': '', 'Revenue_Last': '',
            'OperatingMargin_Last': '', 'DebtToEquity_Last': '',
            'MarketCap_Last': '',
        }
        try:
            stock = yf.Ticker(ticker)
            info  = stock.info or {}

            # ── Info fields (no financial statement needed) ──────────────────
            result['Name']              = info.get('shortName', ticker)
            result['Sector']            = info.get('sector', '')
            result['MarketCap_Last']    = info.get('marketCap', '')
            result['PEG_Last']          = info.get('pegRatio', '')
            result['RevGrowth_Last']    = info.get('revenueGrowth', '')
            result['EV_EBITDA_Last']    = info.get('enterpriseToEbitda', '')
            result['EPS_Growth_Last']   = info.get('earningsGrowth', '')
            # Universe fields — so 01_universe_expander can skip re-calling get_info()
            result['PER_Last']          = info.get('trailingPE', '')
            result['PBR_Last']          = info.get('priceToBook', '')
            result['ROE_Last']          = info.get('returnOnEquity', '')
            result['Revenue_Last']      = info.get('totalRevenue', '')
            result['OperatingMargin_Last'] = info.get('operatingMargins', '')
            result['DebtToEquity_Last'] = info.get('debtToEquity', '')
            div_yield = _to_float(info.get('dividendYield', ''))
            if _is_valid(div_yield) and div_yield >= 0:
                result['DivYield_Last'] = round(div_yield, 4)

            # ── Financial statements ─────────────────────────────────────────
            try:
                fin = stock.financials
                bs  = stock.balance_sheet
                cf  = stock.cashflow
            except Exception:
                fin = bs = cf = pd.DataFrame()

            ebit    = _safe_row(fin, 'EBIT', 'Operating Income', 'Ebit')
            ta      = _safe_row(bs,  'Total Assets')
            cl      = _safe_row(bs,  'Current Liabilities', 'Total Current Liabilities')
            net_inc = _safe_row(fin, 'Net Income')
            revenue = _safe_row(fin, 'Total Revenue', 'Revenue')
            op_cf   = _safe_row(cf,  'Operating Cash Flow',
                                'Total Cash From Operating Activities')
            capex   = _safe_row(cf,  'Capital Expenditure', 'Capital Expenditures',
                                'Purchase Of Property Plant And Equipment')
            int_exp = _safe_row(fin, 'Interest Expense')
            gp      = _safe_row(fin, 'Gross Profit')
            td      = _safe_row(bs,  'Total Debt', 'Long Term Debt')
            ebitda  = _to_float(info.get('ebitda', ''))

            # ROIC = NOPAT / Invested Capital
            if _is_valid(ebit) and _is_valid(ta) and _is_valid(cl):
                ic = ta - cl
                if ic > 0:
                    result['ROIC_Last'] = round((ebit * 0.79) / ic, 4)

            # Gross Margin
            if _is_valid(gp) and _is_valid(revenue) and revenue > 0:
                result['GrossMargin_Last'] = round(gp / revenue, 4)

            # FCF
            fcf = None
            if _is_valid(op_cf):
                fcf = op_cf - (abs(capex) if _is_valid(capex) else 0)

            # FCF Margin
            if fcf is not None and _is_valid(revenue) and revenue > 0:
                result['FCF_Margin_Last'] = round(fcf / revenue, 4)

            # FCF / Net Income
            if fcf is not None and _is_valid(net_inc) and net_inc != 0:
                result['FCF_NI_Last'] = round(fcf / net_inc, 4)

            # Debt / EBITDA
            if _is_valid(td) and _is_valid(ebitda) and ebitda > 0:
                result['Debt_EBITDA_Last'] = round(td / ebitda, 4)

            # Interest Coverage
            if _is_valid(ebit) and _is_valid(int_exp) and int_exp != 0:
                result['InterestCoverage_Last'] = round(ebit / abs(int_exp), 4)

            # ── Altman Z-Score balance sheet fields (US only) ────────────────
            total_assets        = info.get('totalAssets')
            current_assets      = info.get('totalCurrentAssets')
            current_liabilities = info.get('totalCurrentLiabilities')
            retained_earnings   = info.get('retainedEarnings')
            total_liabilities   = info.get('totalDebt')  # best proxy in yf.info

            # Fallback: quarterly balance sheet
            try:
                qbs = stock.quarterly_balance_sheet
            except Exception:
                qbs = pd.DataFrame()

            if total_assets is None:
                total_assets = _safe_row(qbs, 'Total Assets')
            if current_assets is None:
                current_assets = _safe_row(qbs, 'Current Assets', 'Total Current Assets')
            if current_liabilities is None:
                current_liabilities = _safe_row(qbs, 'Current Liabilities',
                                                'Total Current Liabilities')
            if retained_earnings is None:
                retained_earnings = _safe_row(qbs, 'Retained Earnings',
                                              'Retained Earnings Accumulated Deficit')
            if total_liabilities is None:
                total_liabilities = _safe_row(qbs, 'Total Liabilities Net Minority Interest',
                                              'Total Liabilities')

            if _is_valid(total_assets):
                result['TotalAssets_Last'] = total_assets
            if _is_valid(current_assets):
                result['CurrentAssets_Last'] = current_assets
            if _is_valid(current_liabilities):
                result['CurrentLiabilities_Last'] = current_liabilities
            if _is_valid(retained_earnings):
                result['RetainedEarnings_Last'] = retained_earnings
            if _is_valid(total_liabilities):
                result['TotalLiabilities_Last'] = total_liabilities

        except Exception as e:
            print(f"  ⚠️  yfinance error for {ticker}: {e}")

        return result

    def _dart_api_key(self) -> str:
        """Load DART API key from env first, then key.json for local runs."""
        if hasattr(self, '_dart_api_key_cached'):
            return self._dart_api_key_cached
        for env_name in ('DART_API_KEY', 'OPENDART_API_KEY', 'QUANT_DART_API_KEY'):
            api_key = os.environ.get(env_name, '').strip()
            if api_key:
                self._dart_api_key_cached = api_key
                return self._dart_api_key_cached
        import json
        key_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'key.json')
        try:
            with open(key_path) as f:
                data = json.load(f)
                self._dart_api_key_cached = (
                    data.get('dart_api_key')
                    or data.get('DART_API_KEY')
                    or data.get('opendart_api_key')
                    or ''
                )
        except Exception:
            self._dart_api_key_cached = ''
        return self._dart_api_key_cached

    def _get_dart_reader_and_corp_code(self, code: str):
        """
        Return a cached OpenDartReader and DART corp_code for a KRX stock code.

        OpenDartReader lazily creates and reads docs_cache/corp_codes. Loading it
        once behind a lock prevents FileExistsError races and avoids re-reading
        the full company-code table for every KR ticker.
        """
        if self._dart_disabled_for_run:
            if not self._dart_disable_warned:
                print(f"  ↩️  DART fallback mode enabled: {self._dart_disable_reason}")
                self._dart_disable_warned = True
            return None, None

        api_key = self._dart_api_key()
        if not api_key:
            if not self._dart_missing_key_warned:
                print("  ⚠️  DART API key missing — skipping DART financial statements")
                self._dart_missing_key_warned = True
            return None, None

        code = str(code).strip().zfill(6)
        with self._dart_lock:
            try:
                import OpenDartReader

                if self._dart_client is None or self._dart_api_key_value != api_key:
                    _ensure_dart_cache_dir()
                    self._dart_client = OpenDartReader(api_key)
                    self._dart_api_key_value = api_key
                    self._dart_stock_to_corp = None

                if self._dart_stock_to_corp is None:
                    _ensure_dart_cache_dir()
                    corp_df = self._dart_client.corp_codes
                    mapping: dict[str, str] = {}
                    if corp_df is not None and not corp_df.empty:
                        for _, row in corp_df.iterrows():
                            stock_code = str(row.get('stock_code', '')).strip()
                            corp_code = str(row.get('corp_code', '')).strip()
                            if not stock_code or stock_code.lower() == 'nan':
                                continue
                            if not corp_code or corp_code.lower() == 'nan':
                                continue
                            if stock_code.endswith('.0'):
                                stock_code = stock_code[:-2]
                            if corp_code.endswith('.0'):
                                corp_code = corp_code[:-2]
                            mapping[stock_code.zfill(6)] = corp_code.zfill(8)
                    self._dart_stock_to_corp = mapping
                    self._dart_corp_cache_failures = 0

                return self._dart_client, self._dart_stock_to_corp.get(code)
            except ImportError:
                return None, None
            except Exception as e:
                self._dart_corp_cache_failures += 1
                if self._dart_corp_cache_failures >= self._dart_corp_cache_failure_limit:
                    self._dart_disabled_for_run = True
                    self._dart_disable_reason = (
                        "corp_code cache failed "
                        f"{self._dart_corp_cache_failures} times; "
                        "using fallback data for the rest of this run"
                    )
                    self._dart_disable_warned = True
                    print(f"  ↩️  DART fallback mode enabled: {self._dart_disable_reason}")
                else:
                    print(
                        "  ⚠️  DART corp_code cache failed for "
                        f"{code} ({self._dart_corp_cache_failures}/"
                        f"{self._dart_corp_cache_failure_limit}): {e}"
                    )
                return None, None

    def _fetch_dart_financials(self, code: str) -> dict:
        """
        Fetch Korean financial statement metrics via OpenDartReader (DART API).

        code: 6-digit KRX stock code (e.g. '005930')

        Computes from annual financial statements:
            ROIC, GrossMargin, FCF_Margin, FCF_NI, InterestCoverage,
            Debt_EBITDA, RevGrowth, EPS_Growth (net-income proxy)

        Also returns internal key '_ebitda' and '_per' so the caller can
        compute EV_EBITDA and PEG after market-cap data is available.

        Falls back to empty values on ImportError or any DART failure.
        """
        result = {
            'ROIC_Last': '', 'GrossMargin_Last': '', 'FCF_Margin_Last': '',
            'FCF_NI_Last': '', 'InterestCoverage_Last': '', 'Debt_EBITDA_Last': '',
            'EV_EBITDA_Last': '', 'RevGrowth_Last': '', 'EPS_Growth_Last': '',
            # Balance sheet fields for Altman Z-Score (KR)
            'RevAccel_Last': '',
            'TotalAssets_Last': '', 'CurrentAssets_Last': '', 'CurrentLiabilities_Last': '',
            'RetainedEarnings_Last': '', 'TotalLiabilities_Last': '',
            '_ebitda': None,   # internal — used by caller to compute EV_EBITDA
        }
        try:
            dart, corp_code = self._get_dart_reader_and_corp_code(code)
            if dart is None or not corp_code:
                return result

            # ── Fetch most recent annual statements ────────────────────────
            # reprt_code '11011' = annual report
            fs = None
            current_year = datetime.now().year
            for year in [current_year - 1, current_year - 2]:
                try:
                    time.sleep(0.5)
                    fs = dart.finstate_all(corp_code, year)
                    if fs is not None and not fs.empty:
                        break
                except Exception:
                    pass

            if fs is None or fs.empty:
                return result

            # ── Bug fix 1: prefer consolidated (CFS) over separate (OFS) ──
            # finstate_all() returns both CFS and OFS rows for companies that
            # file both. Without this filter, get_amount's iloc[0] may pick
            # an OFS row when a CFS row exists for the same account_nm.
            if 'fs_div' in fs.columns:
                fs_cfs = fs[fs['fs_div'] == 'CFS']
                if not fs_cfs.empty:
                    fs = fs_cfs

            # ── Bug fix 2: statement-type filtered views ───────────────────
            # Searching the full fs for e.g. '감가상각비' can match a value
            # from the IS, BS, or CF section — depending on which row comes
            # first. We now resolve each field against the correct statement.
            if 'sj_div' in fs.columns:
                fs_is = fs[fs['sj_div'].isin(['IS', 'CIS'])]   # income stmt
                fs_bs = fs[fs['sj_div'] == 'BS']                # balance sheet
                fs_cf = fs[fs['sj_div'] == 'CF']                # cash flow
            else:
                fs_is = fs_bs = fs_cf = fs   # fallback: search entire FS

            # ── Amount extractors ─────────────────────────────────────────
            def get_amount(account_names, stmt_df, use_prior=False):
                """Return first matching account amount from stmt_df by Korean name."""
                col = 'frmtrm_amount' if use_prior else 'thstrm_amount'
                for name in account_names:
                    mask = stmt_df['account_nm'].str.strip() == name
                    rows = stmt_df[mask]
                    if not rows.empty:
                        val = rows.iloc[0].get(col, '')
                        if val and str(val).strip() not in ('', '-'):
                            try:
                                return float(str(val).replace(',', '').replace(' ', ''))
                            except (ValueError, TypeError):
                                pass
                return None

            def get_by_id(account_id_substr, stmt_df, use_prior=False):
                """
                Fallback: look up by IFRS account_id substring (more stable
                across companies than Korean account names).
                For accounts that appear multiple times (e.g. ifrs-full_ProfitLoss
                shows total + controlling + non-controlling), returns the first
                non-zero row — callers should pass a more specific ID when possible.
                """
                if 'account_id' not in stmt_df.columns:
                    return None
                col = 'frmtrm_amount' if use_prior else 'thstrm_amount'
                mask = stmt_df['account_id'].str.contains(account_id_substr, na=False)
                rows = stmt_df[mask]
                if not rows.empty:
                    val = rows.iloc[0].get(col, '')
                    if val and str(val).strip() not in ('', '-'):
                        try:
                            return float(str(val).replace(',', '').replace(' ', ''))
                        except (ValueError, TypeError):
                            pass
                return None

            # ── Income statement ───────────────────────────────────────────
            revenue      = get_amount(['매출액', '수익(매출액)', '영업수익'], fs_is)
            if not _is_valid(revenue):
                revenue  = get_by_id('ifrs-full_Revenue', fs_is)
            revenue_py   = get_amount(['매출액', '수익(매출액)', '영업수익'], fs_is, use_prior=True)
            if not _is_valid(revenue_py):
                revenue_py = get_by_id('ifrs-full_Revenue', fs_is, use_prior=True)

            gross_profit = get_amount(['매출총이익', '매출총손익'], fs_is)
            if not _is_valid(gross_profit):
                gross_profit = get_by_id('ifrs-full_GrossProfit', fs_is)

            # '영업손익' is the standard name for large-cap consolidated DART filings
            # (e.g. Samsung SDI, SK Hynix); smaller companies often use '영업이익(손실)'
            op_income    = get_amount(['영업이익', '영업이익(손실)', '영업손익'], fs_is)
            if not _is_valid(op_income):
                op_income = get_by_id('dart_OperatingIncomeLoss', fs_is)

            # '당기순손익' is the CIS top-level label used in many large-cap filings;
            # for consolidated reports, prefer controlling-interest attribution
            net_income   = get_amount(
                ['당기순이익', '당기순이익(손실)', '당기순손익',
                 '지배기업 소유주 지분 당기순이익',
                 '지배기업주주지분 당기순이익'], fs_is)
            if not _is_valid(net_income):
                # Use controlling-interest net income for EPS accuracy
                net_income = get_by_id(
                    'ifrs-full_ProfitLossAttributableToOwnersOfParent', fs_is)
            if not _is_valid(net_income):
                net_income = get_by_id('ifrs-full_ProfitLoss', fs_is)

            net_income_py = get_amount(
                ['당기순이익', '당기순이익(손실)', '당기순손익',
                 '지배기업 소유주 지분 당기순이익',
                 '지배기업주주지분 당기순이익'], fs_is, use_prior=True)
            if not _is_valid(net_income_py):
                net_income_py = get_by_id(
                    'ifrs-full_ProfitLossAttributableToOwnersOfParent', fs_is, use_prior=True)
            if not _is_valid(net_income_py):
                net_income_py = get_by_id('ifrs-full_ProfitLoss', fs_is, use_prior=True)

            int_expense  = get_amount(['이자비용', '금융비용'], fs_is)
            if not _is_valid(int_expense):
                int_expense = get_by_id('ifrs-full_FinanceCosts', fs_is)

            # ── Balance sheet ──────────────────────────────────────────────
            # '자산 합계' (with space) is used by large-cap consolidated filers;
            # '자산총계' is the standard name for smaller company filings
            total_assets = get_amount(['자산총계', '자산 합계'], fs_bs)
            if not _is_valid(total_assets):
                total_assets = get_by_id('ifrs-full_Assets', fs_bs)

            current_assets = get_amount(['유동자산', '유동자산합계', '유동자산 합계'], fs_bs)
            if not _is_valid(current_assets):
                current_assets = get_by_id('ifrs-full_CurrentAssets', fs_bs)

            current_liab = get_amount(['유동부채', '유동부채합계', '유동부채 합계'], fs_bs)

            retained_earnings = get_amount(
                ['이익잉여금', '미처분이익잉여금', '이익잉여금(결손금)', '이익(결손금)'], fs_bs)
            if not _is_valid(retained_earnings):
                retained_earnings = get_by_id('ifrs-full_RetainedEarnings', fs_bs)

            total_liabilities = get_amount(['부채총계', '부채 합계'], fs_bs)
            if not _is_valid(total_liabilities):
                total_liabilities = get_by_id('ifrs-full_Liabilities', fs_bs)

            # Bug fix 3: expanded debt account name list.
            # Korean IFRS companies report short/long-term debt under varied
            # names; also include '유동성장기부채' (current portion of LTD)
            # and '사채' (bonds payable).
            short_debt = get_amount(
                ['단기차입금',
                 '단기차입금 및 유동성장기부채',
                 '유동성장기부채',
                 '유동성장기차입금'], fs_bs)
            long_debt = get_amount(
                ['장기차입금',
                 '장기차입금(순액)',
                 '비유동차입금',
                 '사채 및 장기차입금',
                 '사채'], fs_bs)
            # Some companies report a single aggregated debt line
            direct_total_debt = get_amount(
                ['차입금합계', '총차입금', '차입금 및 사채'], fs_bs)
            # Last-resort: non-current liabilities as debt proxy.
            # Many summary DART filings (e.g. Samsung) only expose top-level
            # 유동부채 / 비유동부채 / 부채총계 without individual debt lines.
            # 비유동부채 ≈ financial debt for most industrials; it excludes
            # short-term trade payables that inflate 부채총계.
            noncurrent_liab = get_amount(['비유동부채', '비유동부채합계'], fs_bs)

            # ── Cash flow ──────────────────────────────────────────────────
            op_cf = get_amount(
                ['영업활동현금흐름',
                 '영업활동으로 인한 현금흐름',
                 '영업활동으로인한현금흐름'], fs_cf)
            capex = get_amount(
                ['유형자산의 취득',
                 '유형자산취득',
                 '유형자산의취득',
                 '유형자산 취득'], fs_cf)
            # Depreciation: IFRS companies report this as a CF operating
            # adjustment, not always as a standalone IS line item.
            depreciation = get_amount(
                ['감가상각비',
                 '유형자산감가상각비',
                 '감가상각비 및 상각비',
                 '사용권자산상각비',
                 '감가상각 및 상각비'], fs_cf)
            if not _is_valid(depreciation):
                # secondary fallback: some companies put it in IS
                depreciation = get_amount(
                    ['감가상각비', '유형자산감가상각비'], fs_is)

            # ── Derived values ─────────────────────────────────────────────
            # Priority: aggregated debt line > sum of components > non-current liab
            total_debt = direct_total_debt
            if not _is_valid(total_debt):
                if _is_valid(short_debt) or _is_valid(long_debt):
                    total_debt = (short_debt or 0) + (long_debt or 0)
            if not _is_valid(total_debt) and _is_valid(noncurrent_liab):
                total_debt = noncurrent_liab   # proxy for summary-format filings

            ebitda = None
            if _is_valid(op_income) and _is_valid(depreciation):
                ebitda = op_income + abs(depreciation)
            elif _is_valid(op_income):
                ebitda = op_income   # conservative approximation

            fcf = None
            if _is_valid(op_cf):
                fcf = op_cf - (abs(capex) if _is_valid(capex) else 0)

            # ── Populate result ────────────────────────────────────────────
            if _is_valid(op_income) and _is_valid(total_assets) and _is_valid(current_liab):
                ic = total_assets - current_liab
                if ic > 0:
                    result['ROIC_Last'] = round((op_income * 0.79) / ic, 4)

            if _is_valid(gross_profit) and _is_valid(revenue) and revenue > 0:
                result['GrossMargin_Last'] = round(gross_profit / revenue, 4)

            if fcf is not None and _is_valid(revenue) and revenue > 0:
                result['FCF_Margin_Last'] = round(fcf / revenue, 4)

            if fcf is not None and _is_valid(net_income) and net_income != 0:
                result['FCF_NI_Last'] = round(fcf / net_income, 4)

            if _is_valid(op_income) and _is_valid(int_expense) and int_expense != 0:
                result['InterestCoverage_Last'] = round(op_income / abs(int_expense), 4)

            if _is_valid(total_debt) and _is_valid(ebitda) and ebitda > 0:
                result['Debt_EBITDA_Last'] = round(total_debt / ebitda, 4)

            if _is_valid(revenue) and _is_valid(revenue_py) and revenue_py != 0:
                result['RevGrowth_Last'] = round((revenue - revenue_py) / abs(revenue_py), 4)

            if _is_valid(net_income) and _is_valid(net_income_py) and net_income_py != 0:
                ni_growth = (net_income - net_income_py) / abs(net_income_py)
                result['EPS_Growth_Last'] = round(ni_growth, 4)

            result['_ebitda'] = ebitda   # passed back for EV_EBITDA in caller

            # ── Altman Z balance sheet fields ──────────────────────────────
            if _is_valid(total_assets):
                result['TotalAssets_Last']        = round(total_assets, 0)
            if _is_valid(current_assets):
                result['CurrentAssets_Last']      = round(current_assets, 0)
            if _is_valid(current_liab):
                result['CurrentLiabilities_Last'] = round(current_liab, 0)
            if _is_valid(retained_earnings):
                result['RetainedEarnings_Last']   = round(retained_earnings, 0)
            if _is_valid(total_liabilities):
                result['TotalLiabilities_Last']   = round(total_liabilities, 0)

        except ImportError:
            pass   # OpenDartReader package not installed — caller falls back to empty values
        except Exception as e:
            print(f"  ⚠️  DART financials error for {code}: {e}")

        return result

    @staticmethod
    def _naver_market_cap(code: str) -> int | None:
        """
        Naver Finance에서 시가총액(KRW 원 단위)을 스크래핑하여 반환한다.
        pykrx get_market_cap_by_date()가 빈 DataFrame을 반환할 때 fallback으로 사용.

        Naver 페이지 형식:
          "1,198조 7,267억원"  → 1_198조 + 7_267억 = 정수(KRW)
          "7,267억원"          → 7_267억
          "1,198조원"          → 1_198조
        """
        _HEADERS = {
            'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) '
                          'AppleWebKit/537.36 (KHTML, like Gecko) '
                          'Chrome/120.0.0.0 Safari/537.36'
        }
        try:
            resp = requests.get(
                'https://finance.naver.com/item/main.naver',
                params={'code': code},
                headers=_HEADERS,
                timeout=8,
            )
            soup = BeautifulSoup(resp.text, 'html.parser')
            for row in soup.find_all('tr'):
                cells = [td.get_text(separator=' ', strip=True) for td in row.find_all(['th', 'td'])]
                text = ' '.join(cells)
                if '시가총액' in text and '순위' not in text and '억' in text:
                    # 패턴: "X,XXX조 Y,YYY억원" or "Y,YYY억원" or "X,XXX조원"
                    jo  = re.search(r'([\d,]+)\s*조', text)
                    eok = re.search(r'([\d,]+)\s*억', text)
                    jo_val  = int(jo.group(1).replace(',', ''))  if jo  else 0
                    eok_val = int(eok.group(1).replace(',', '')) if eok else 0
                    total = jo_val * 10**12 + eok_val * 10**8
                    if total > 0:
                        return total
        except Exception:
            pass
        return None

    def _fetch_kr_financials(self, ticker: str) -> dict:
        """
        Fetch Korean fundamental data.

        Market / price data (MarketCap, Volume, Price, PER, Name):
            → pykrx (KRX official data)

        Financial statement metrics (ROIC, FCF_Margin, FCF_NI, InterestCoverage,
        Debt_EBITDA, EV_EBITDA, EPS_Growth, PEG, GrossMargin, RevGrowth):
            → OpenDartReader (DART API), 0.5 s delay per call

        Falls back gracefully: pykrx ImportError → yfinance .info only;
        DART failure → those 8 fields remain empty (no crash).
        """
        import datetime as dt

        code = ticker.split('.')[0]
        asof_date = dt.date.today()
        while asof_date.weekday() >= 5:
            asof_date -= dt.timedelta(days=1)
        today = asof_date.strftime('%Y%m%d')
        week_ago = (asof_date - dt.timedelta(days=10)).strftime('%Y%m%d')

        result = {
            'Name': ticker, 'Sector': '',
            'ROIC_Last': '', 'RevGrowth_Last': '', 'PEG_Last': '',
            'GrossMargin_Last': '', 'FCF_Margin_Last': '', 'Debt_EBITDA_Last': '',
            'EV_EBITDA_Last': '', 'EPS_Growth_Last': '',
            'FCF_NI_Last': '', 'InterestCoverage_Last': '',
            'DivYield_Last': '',
            'RevAccel_Last': '',
            'TotalAssets_Last': '', 'CurrentAssets_Last': '', 'CurrentLiabilities_Last': '',
            'RetainedEarnings_Last': '', 'TotalLiabilities_Last': '',
            'PER_Last': '', 'PBR_Last': '', 'ROE_Last': '', 'Revenue_Last': '',
            'OperatingMargin_Last': '', 'DebtToEquity_Last': '',
            'MarketCap_Last': '', 'Price_Last': '', 'Volume_Last': '',
        }

        # ── Step 1: market / price data via pykrx ─────────────────────────
        per_krx = None   # kept for PEG computation after DART fetch

        try:
            from pykrx import stock as krx

            # Market cap + volume
            cap = _quiet_call(krx.get_market_cap_by_date, today, today, code)
            if cap is None or cap.empty:
                cap = _quiet_call(krx.get_market_cap_by_date, week_ago, today, code)
            if cap is not None and not cap.empty:
                row = cap.iloc[-1]
                mc = _to_float(row.get('시가총액'))
                if _is_valid(mc) and mc > 0:
                    result['MarketCap_Last'] = int(mc)
                vol = _to_float(row.get('거래량'))
                if _is_valid(vol):
                    result['Volume_Last'] = int(vol)

            # Price
            ohlcv = _quiet_call(krx.get_market_ohlcv_by_date, week_ago, today, code)
            if ohlcv is not None and not ohlcv.empty:
                price = _to_float(ohlcv['종가'].iloc[-1])
                if _is_valid(price):
                    result['Price_Last'] = price

            # PER (used later for PEG = PER / (EPS_Growth * 100))
            fund = _quiet_call(krx.get_market_fundamental_by_date, today, today, code)
            if fund is None or fund.empty:
                fund = _quiet_call(krx.get_market_fundamental_by_date, week_ago, today, code)
            if fund is not None and not fund.empty:
                per_krx = _to_float(fund.iloc[-1].get('PER'))

            # Company name
            name = _quiet_call(krx.get_market_ticker_name, code)
            if name:
                result['Name'] = name

        except ImportError:
            # pykrx unavailable — fall back to yfinance .info for market data
            try:
                info = yf.Ticker(ticker).info or {}
                result['Name']           = info.get('shortName', ticker)
                result['Sector']         = info.get('sector', '')
                result['MarketCap_Last'] = info.get('marketCap', '')
            except Exception as e:
                print(f"  ⚠️  KR yfinance fallback error for {ticker}: {e}")

        except Exception as e:
            print(f"  ⚠️  pykrx error for {ticker}: {e}")

        # ── pykrx MarketCap 미확보 시 Naver → yfinance 순서로 fallback ────────
        # pykrx get_market_cap_by_date()가 빈 DataFrame을 반환하는 경우(API 호환성
        # 문제 등)에도 MarketCap을 확보하기 위해 2단계 fallback을 적용한다.
        if not result.get('MarketCap_Last'):
            # 1차 fallback: Naver Finance 스크래핑 (빠르고 rate-limit 없음)
            mc_naver = self._naver_market_cap(code)
            if mc_naver:
                result['MarketCap_Last'] = mc_naver
            else:
                # 2차 fallback: yfinance (느리고 rate-limit 있음 — 최후 수단)
                try:
                    info = yf.Ticker(ticker).info or {}
                    mc_yf = info.get('marketCap')
                    if mc_yf:
                        result['MarketCap_Last'] = mc_yf
                    if not result.get('Name') or result['Name'] == ticker:
                        result['Name'] = info.get('shortName', ticker)
                    if not result.get('Sector'):
                        result['Sector'] = info.get('sector', '')
                except Exception:
                    pass

        # ── Step 2: financial statement metrics via OpenDartReader ─────────
        dart_data = self._fetch_dart_financials(code)
        ebitda = dart_data.pop('_ebitda', None)

        # Merge DART results (only overwrite if DART returned a value)
        for key in ('ROIC_Last', 'GrossMargin_Last', 'FCF_Margin_Last', 'FCF_NI_Last',
                    'InterestCoverage_Last', 'Debt_EBITDA_Last',
                    'RevGrowth_Last', 'EPS_Growth_Last',
                    'TotalAssets_Last', 'CurrentAssets_Last', 'CurrentLiabilities_Last',
                    'RetainedEarnings_Last', 'TotalLiabilities_Last'):
            if dart_data.get(key) not in ('', None):
                result[key] = dart_data[key]

        # ── EV/EBITDA: market cap (pykrx) + EBITDA (DART) ─────────────────
        mc = _to_float(result.get('MarketCap_Last'))
        if _is_valid(mc) and _is_valid(ebitda) and ebitda > 0:
            result['EV_EBITDA_Last'] = round(mc / ebitda, 4)

        # ── PEG: PER (pykrx) / (EPS_Growth * 100) ─────────────────────────
        eps_growth = _to_float(result.get('EPS_Growth_Last'))
        if _is_valid(per_krx) and per_krx > 0 and _is_valid(eps_growth) and eps_growth > 0:
            result['PEG_Last'] = round(per_krx / (eps_growth * 100), 4)

        # ── yfinance fallback for any financial metrics still empty ──────────
        # Triggered when DART is unavailable (missing API key, package not
        # installed, or DART returned no data).  yfinance covers .KS/.KQ
        # tickers reasonably well for these derived metrics.
        _needs_yf = [k for k in ('GrossMargin_Last', 'FCF_Margin_Last',
                                  'Debt_EBITDA_Last', 'PEG_Last',
                                  'RevGrowth_Last', 'EPS_Growth_Last',
                                  'DivYield_Last',
                                  'PER_Last', 'PBR_Last', 'ROE_Last', 'Revenue_Last',
                                  'OperatingMargin_Last', 'DebtToEquity_Last')
                     if result.get(k) in ('', None)]
        if _needs_yf:
            try:
                stock    = yf.Ticker(ticker)
                info_yf  = stock.info or {}
                fin_yf   = stock.financials
                bs_yf    = stock.balance_sheet
                cf_yf    = stock.cashflow

                rev_yf   = _safe_row(fin_yf, 'Total Revenue', 'Revenue')
                gp_yf    = _safe_row(fin_yf, 'Gross Profit')
                op_cf_yf = _safe_row(cf_yf,  'Operating Cash Flow',
                                     'Total Cash From Operating Activities')
                capex_yf = _safe_row(cf_yf,  'Capital Expenditure',
                                     'Capital Expenditures',
                                     'Purchase Of Property Plant And Equipment')
                td_yf    = _safe_row(bs_yf,  'Total Debt', 'Long Term Debt')
                ebitda_yf = _to_float(info_yf.get('ebitda', ''))

                if 'GrossMargin_Last' in _needs_yf:
                    if _is_valid(gp_yf) and _is_valid(rev_yf) and rev_yf > 0:
                        result['GrossMargin_Last'] = round(gp_yf / rev_yf, 4)

                if 'FCF_Margin_Last' in _needs_yf:
                    if _is_valid(op_cf_yf) and _is_valid(rev_yf) and rev_yf > 0:
                        fcf_yf = op_cf_yf - (abs(capex_yf) if _is_valid(capex_yf) else 0)
                        result['FCF_Margin_Last'] = round(fcf_yf / rev_yf, 4)

                if 'Debt_EBITDA_Last' in _needs_yf:
                    if _is_valid(td_yf) and _is_valid(ebitda_yf) and ebitda_yf > 0:
                        result['Debt_EBITDA_Last'] = round(td_yf / ebitda_yf, 4)

                if 'PEG_Last' in _needs_yf:
                    peg_yf = (_to_float(info_yf.get('pegRatio', '')) or
                              _to_float(info_yf.get('trailingPegRatio', '')))
                    if _is_valid(peg_yf) and peg_yf > 0:
                        result['PEG_Last'] = round(peg_yf, 4)

                if 'RevGrowth_Last' in _needs_yf:
                    rg_yf = _to_float(info_yf.get('revenueGrowth', ''))
                    if _is_valid(rg_yf):
                        result['RevGrowth_Last'] = round(rg_yf, 4)

                if 'EPS_Growth_Last' in _needs_yf:
                    eg_yf = _to_float(info_yf.get('earningsGrowth', ''))
                    if _is_valid(eg_yf):
                        result['EPS_Growth_Last'] = round(eg_yf, 4)

                if 'DivYield_Last' in _needs_yf:
                    dy_yf = _to_float(info_yf.get('dividendYield', ''))
                    if _is_valid(dy_yf) and dy_yf >= 0:
                        result['DivYield_Last'] = round(dy_yf, 4)

                # Universe fields — filled from yfinance so 01_universe_expander
                # can skip re-calling get_info() for KR tickers
                if 'PER_Last' in _needs_yf:
                    result['PER_Last'] = info_yf.get('trailingPE', '')
                if 'PBR_Last' in _needs_yf:
                    result['PBR_Last'] = info_yf.get('priceToBook', '')
                if 'ROE_Last' in _needs_yf:
                    result['ROE_Last'] = info_yf.get('returnOnEquity', '')
                if 'Revenue_Last' in _needs_yf:
                    result['Revenue_Last'] = info_yf.get('totalRevenue', '')
                if 'OperatingMargin_Last' in _needs_yf:
                    result['OperatingMargin_Last'] = info_yf.get('operatingMargins', '')
                if 'DebtToEquity_Last' in _needs_yf:
                    result['DebtToEquity_Last'] = info_yf.get('debtToEquity', '')

                print(f"  ↩️  KR yfinance fallback used for {ticker} "
                      f"({', '.join(k.replace('_Last','') for k in _needs_yf)})")

            except Exception as e:
                print(f"  ⚠️  KR yfinance fallback error for {ticker}: {e}")

        return result

    def _fetch_with_retry(self, ticker: str, max_attempts: int = 3) -> dict:
        """
        Wrap _fetch_yf_financials() with exponential back-off on rate-limit AND
        connection-timeout errors (delays: 3 s, 6 s, 12 s).

        Catches:
          - HTTP 429 / "too many requests" / "rate limit"   ← Yahoo throttle
          - curl (28) / timeout / connection error           ← IP ban / slow response
        Other errors propagate immediately (non-retryable).
        """
        _RETRYABLE = (
            '429', 'too many requests', 'rate limit', 'rateerror',
            'timeout', 'timed out', 'connection', 'curl', 'remotedisconnected',
            'chunkedencodingerror',
        )
        for attempt in range(max_attempts):
            try:
                return self._fetch_yf_financials(ticker)
            except Exception as e:
                err = str(e).lower()
                if any(x in err for x in _RETRYABLE):
                    wait = 3 * (2 ** attempt)   # 3 s → 6 s → 12 s
                    print(f"  ⏳ [{ticker}] rate/timeout (attempt {attempt+1}/{max_attempts}), "
                          f"retry in {wait}s…", flush=True)
                    time.sleep(wait)
                    continue
                raise                           # non-retryable: fail fast
        return {}                               # exhausted retries

    @staticmethod
    def _market_from_ticker(ticker: str) -> str:
        return 'KR' if str(ticker).endswith(('.KS', '.KQ')) else 'US'

    def _row_to_fin_dict(self, row: dict) -> dict:
        """Convert a raw string-valued cache row into a typed fundamental dict."""
        ticker = row.get('Ticker', '')
        market = row.get('Market') or self._market_from_ticker(ticker)
        return {
            'Name':             row.get('Name',   ticker),
            'Sector':           row.get('Sector', ''),
            'Market':           market,
            'MarketCap':        _to_float(row.get('MarketCap_Last')),
            'ROIC':             _to_float(row.get('ROIC_Last')),
            'RevGrowth':        _to_float(row.get('RevGrowth_Last')),
            'PEG':              _to_float(row.get('PEG_Last')),
            'GrossMargin':      _to_float(row.get('GrossMargin_Last')),
            'FCF_Margin':       _to_float(row.get('FCF_Margin_Last')),
            'Debt_EBITDA':      _to_float(row.get('Debt_EBITDA_Last')),
            'EV_EBITDA':        _to_float(row.get('EV_EBITDA_Last')),
            'EPS_Growth':       _to_float(row.get('EPS_Growth_Last')),
            'FCF_NI':           _to_float(row.get('FCF_NI_Last')),
            'InterestCoverage': _to_float(row.get('InterestCoverage_Last')),
            'DivYield':         _to_float(row.get('DivYield_Last')),
            'RevAccel':         _to_float(row.get('RevAccel_Last')),
            'TotalAssets':        _to_float(row.get('TotalAssets_Last')),
            'CurrentAssets':      _to_float(row.get('CurrentAssets_Last')),
            'CurrentLiabilities': _to_float(row.get('CurrentLiabilities_Last')),
            'RetainedEarnings':   _to_float(row.get('RetainedEarnings_Last')),
            'TotalLiabilities':   _to_float(row.get('TotalLiabilities_Last')),
            # Universe fields (used by 01_universe_expander to skip re-calling get_info)
            'PER':              _to_float(row.get('PER_Last')),
            'PBR':              _to_float(row.get('PBR_Last')),
            'ROE':              _to_float(row.get('ROE_Last')),
            'Revenue':          _to_float(row.get('Revenue_Last')),
            'OperatingMargin':  _to_float(row.get('OperatingMargin_Last')),
            'DebtToEquity':     _to_float(row.get('DebtToEquity_Last')),
        }

    # ──────────────────────────────────────────────────────────────────────────
    # Parallel Batch Fetch  (replaces sequential prefetch inner loop)
    # ──────────────────────────────────────────────────────────────────────────

    def batch_fetch(self, tickers: list, max_workers: int = None) -> int:
        """
        Fetch stale tickers with rate-limited parallelism, then batch-write to
        Company_Master.

        Phase 1 — throttled parallel yfinance fetch (ThreadPoolExecutor):
          • Semaphore limits simultaneous live API calls to avoid IP throttling.
          • Per-request jitter sleep prevents burst patterns.
          • Each result is validated: if >5 of 8 key fields are empty the fetch
            is retried (up to 3×) with exponential back-off before acceptance.
          • Tickers that raise an exception fall through to a sequential fallback
            pass at the end of Phase 1.
        Phase 2 — single batch_update() per 1,000 rows (≈1-2 Sheets API calls).

        Returns count of tickers written to Company_Master.

        Concurrency rationale
        ─────────────────────
        Yahoo Finance starts silently returning empty dicts (not HTTP 429) once it
        sees >2 simultaneous connections from the same IP.  Each _fetch_yf_financials
        call internally makes 4-5 HTTP requests (.info + .financials + .balance_sheet
        + .cashflow + .quarterly_balance_sheet).  With _CONCURRENCY=2 that is at most
        10 parallel requests — within Yahoo's observed soft limit.

        When IP is already throttled (curl 28 errors), _fetch_with_retry backs off
        3 s → 6 s → 12 s automatically.  The jitter below prevents all threads from
        retrying at exactly the same instant (thundering-herd problem).
        """
        import random

        test_mode = os.environ.get('QUANT_TEST_MODE') == 'true'

        # ── Tuning constants ────────────────────────────────────────────────
        _CONCURRENCY  = 1   if test_mode else 2    # max simultaneous yfinance calls
        _WORKERS      = 2   if test_mode else 3    # thread-pool size (some sit waiting)
        _SLEEP_BASE   = 2.0 if test_mode else 1.5  # seconds between requests per slot
        _JITTER_LO    = 0.5                         # jitter lower bound (seconds)
        _JITTER_HI    = 1.5                         # jitter upper bound (seconds)
        _MAX_RETRIES  = 3                           # retries on empty-data detection
        _EMPTY_THRESH = 5                           # empty fields tolerated before retry
        _KEY_FIELDS   = [                           # fields checked for emptiness
            'MarketCap_Last', 'ROIC_Last', 'RevGrowth_Last', 'GrossMargin_Last',
            'FCF_Margin_Last', 'Debt_EBITDA_Last', 'EV_EBITDA_Last', 'EPS_Growth_Last',
        ]
        # ────────────────────────────────────────────────────────────────────

        if max_workers is None:
            max_workers = _WORKERS

        stale = [t for t in tickers if self.needs_fin_update(t)]
        fresh = len(tickers) - len(stale)
        total = len(stale)

        if not stale:
            print(f"\n🔄 batch_fetch: {fresh}/{len(tickers)} already fresh — 0 API calls")
            return 0

        print(f"\n🔄 batch_fetch: {fresh} fresh (skip) | {total} stale → fetching "
              f"(workers={max_workers}, concurrency={_CONCURRENCY})")

        _semaphore = threading.Semaphore(_CONCURRENCY)
        _mem_lock  = threading.Lock()
        _raw_store: dict = {}
        _done      = [0]
        _sequential_queue: list = []   # tickers to retry sequentially

        def _count_empty(raw: dict) -> int:
            return sum(1 for f in _KEY_FIELDS if not raw.get(f))

        def _fetch_validated(ticker: str) -> dict:
            """Fetch with up to _MAX_RETRIES attempts if data looks empty.
            KR tickers (.KS/.KQ) always have empty statement-derived fields
            (ROIC, FCF, Debt/EBITDA) — skip retry immediately for them.
            """
            is_kr = ticker.endswith(('.KS', '.KQ'))
            if is_kr:
                return self._fetch_kr_financials(ticker)
            for attempt in range(_MAX_RETRIES):
                raw = self._fetch_with_retry(ticker)
                if _count_empty(raw) <= _EMPTY_THRESH:
                    return raw
                if attempt < _MAX_RETRIES - 1:
                    wait = 2.0 * (attempt + 1)
                    print(f"  ⚠️  [{ticker}] {_count_empty(raw)} empty fields "
                          f"(attempt {attempt + 1}/{_MAX_RETRIES}), retry in {wait:.0f}s...",
                          flush=True)
                    time.sleep(wait)
            return raw   # return best-effort result after retries

        def _commit(ticker: str, raw: dict):
            """Merge raw dict into _mem and mark dirty (lock must be held by caller)."""
            existing = dict(self._mem.get(ticker, {}))
            existing['Ticker'] = ticker
            existing.update({k: str(v) if v is not None else '' for k, v in raw.items()})
            self._mem[ticker] = existing
            if ticker not in self._row_index:
                self._row_count += 1
                self._row_index[ticker] = self._row_count
            self._dirty.add(ticker)
            _raw_store[ticker] = raw

        def _worker(ticker: str):
            # Pre-semaphore jitter: spreads thread wake-up times
            time.sleep(random.uniform(_JITTER_LO, _JITTER_HI))
            with _semaphore:
                raw = _fetch_validated(ticker)
                # Post-request sleep inside semaphore: keeps slot utilisation low
                time.sleep(_SLEEP_BASE + random.uniform(0.0, 0.3))

            raw['Last_Fin_Update'] = datetime.now().isoformat()
            empty = _count_empty(raw)
            with _mem_lock:
                _commit(ticker, raw)
                _done[0] += 1
                i = _done[0]

            # SQLite write happens OUTSIDE _mem_lock — it has its own _db_lock
            # and is I/O-bound; holding _mem_lock during disk I/O would starve threads.
            self._sqlite_upsert(ticker)

            status = '✅' if empty <= _EMPTY_THRESH else f'⚠️  ({empty} empty fields)'
            print(f"  [{i}/{total}] {ticker} {status}", flush=True)

        # ── Phase 1: throttled parallel fetch ─────────────────────────────
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            futures = {executor.submit(_worker, t): t for t in stale}
            for fut in as_completed(futures):
                try:
                    fut.result()
                except Exception as e:
                    ticker = futures[fut]
                    with _mem_lock:
                        _done[0] += 1
                        i = _done[0]
                    print(f"  [{i}/{total}] {ticker} ⚠️  ({e}) → queued for sequential retry",
                          flush=True)
                    _sequential_queue.append(ticker)

        # ── Phase 1b: sequential fallback for exception tickers ────────────
        if _sequential_queue:
            print(f"\n  🔄 Sequential fallback: {len(_sequential_queue)} ticker(s)...")
            for ticker in _sequential_queue:
                print(f"  ⚠️  [{ticker}] rate limited — fetching sequentially", flush=True)
                try:
                    raw = _fetch_validated(ticker)
                    raw['Last_Fin_Update'] = datetime.now().isoformat()
                    with _mem_lock:
                        _commit(ticker, raw)
                except Exception as e:
                    print(f"  ⚠️  Sequential fetch also failed {ticker}: {e}")
                time.sleep(1.5 + random.uniform(0.0, 0.5))

        # ── Phase 2: batch Sheets write (one batch_update per 1,000 rows) ──
        print(f"\n  💾 Writing {len(_raw_store)} rows → Company_Master (batch)...")
        written = self.flush(label='batch_fetch')
        print(f"  ✅ batch_fetch done: {written}/{total} persisted to Company_Master")
        return written

    # ──────────────────────────────────────────────────────────────────────────
    # Market Data  (always live — price / volume change every day)
    # ──────────────────────────────────────────────────────────────────────────

    def load_market(self, ticker: str) -> dict:
        """
        Fetch today's price and volume from yfinance (last 5 trading days).
        Always makes a live API call — market data is not cached by design.
        Updates Price_Last, Volume_Last, MarketCap_Last, Last_Mkt_Update in cache.

        Returns dict: { 'Price': float|None, 'Volume': int|None, 'MarketCap': float|None }
        """
        result = {'Price': None, 'Volume': None, 'MarketCap': None}
        try:
            stock = yf.Ticker(ticker)
            hist  = stock.history(period='5d')
            if not hist.empty:
                result['Price']  = round(float(hist['Close'].iloc[-1]), 4)
                result['Volume'] = int(hist['Volume'].iloc[-1])

            info = stock.info or {}
            result['MarketCap'] = info.get('marketCap')

            # Write market snapshot back to cache
            self.update_row(ticker, {
                'Price_Last':      result['Price']    if result['Price']    is not None else '',
                'Volume_Last':     result['Volume']   if result['Volume']   is not None else '',
                'MarketCap_Last':  result['MarketCap'] if result['MarketCap'] is not None else '',
                'Last_Mkt_Update': datetime.now().isoformat(),
            })
        except Exception as e:
            print(f"  ⚠️  Market data error for {ticker}: {e}")

        return result

    # ──────────────────────────────────────────────────────────────────────────
    # Earnings Calendar  (weekly batch update)
    # ──────────────────────────────────────────────────────────────────────────

    def update_earnings_calendar(self, tickers: list, delay: float = 0.5):
        """
        Fetch next earnings dates for all tickers via yf.Ticker.calendar and
        upsert them into the Earnings_Calendar worksheet.

        Call this from main_engine.py once per week (or via a scheduled job).
        Automatically invalidates any cached fundamentals whose earnings have
        now passed (needs_fin_update will return True on next script run).

        Parameters
        ----------
        tickers : list of str
            Full universe of tickers to update.
        delay : float
            Sleep between API calls (seconds). Default 0.2.
        """
        import random

        test_mode = os.environ.get('QUANT_TEST_MODE') == 'true'

        # In test mode limit to 20 US tickers (KR calendar always empty anyway)
        if test_mode:
            us_only = [t for t in tickers if not t.endswith(('.KS', '.KQ'))]
            tickers = us_only[:20]

        print(f"\n📅 Updating Earnings_Calendar for {len(tickers)} tickers...")

        # Always pre-load the full existing sheet first — unconditionally — so
        # that a partial run (test mode, or a subset list) never destroys entries
        # for tickers not in the current call.
        self._earnings_mem = {}
        try:
            rows = self._earnings_ws.get_all_values()
            if len(rows) >= 2:
                hdr = rows[0]
                for r in rows[1:]:
                    d = dict(zip(hdr, r + [''] * len(hdr)))
                    t = d.get('Ticker', '').strip()
                    date_text = _normalize_earnings_date(d.get('Next_Earnings_Date', '').strip())
                    if t and date_text:
                        self._earnings_mem[t] = date_text
            if self._earnings_mem:
                print(f"  📂 Pre-loaded {len(self._earnings_mem)} existing earnings dates")
        except Exception as e:
            print(f"  ⚠️  Could not pre-load Earnings_Calendar: {e}")

        # Throttle: yfinance calendar endpoint suffers the same silent-empty
        # problem as fundamentals when hammered with concurrent requests.
        _CONCURRENCY = 1 if test_mode else 2
        _WORKERS     = 2 if test_mode else 3
        _semaphore   = threading.Semaphore(_CONCURRENCY)
        total        = len(tickers)
        _earn_lock   = threading.Lock()
        _updated     = [0]
        _failed      = [0]

        def _fetch_cal(ticker: str):
            """Fetch one earnings date with rate limiting; returns (ticker, date_str|None)."""
            time.sleep(random.uniform(0.4, 1.0))   # pre-semaphore jitter
            with _semaphore:
                try:
                    cal = yf.Ticker(ticker).calendar
                    earn_date = None
                    if isinstance(cal, dict):
                        raw = cal.get('Earnings Date')
                        if isinstance(raw, (list, pd.DatetimeIndex)) and len(raw):
                            earn_date = raw[0]
                        elif raw is not None:
                            earn_date = raw
                    elif isinstance(cal, pd.DataFrame) and not cal.empty:
                        if 'Earnings Date' in cal.index:
                            earn_date = cal.loc['Earnings Date'].iloc[0]

                    if earn_date is not None:
                        if hasattr(earn_date, 'date'):
                            earn_date = earn_date.date()
                        date_text = _normalize_earnings_date(earn_date)
                        if date_text:
                            return ticker, date_text
                except Exception:
                    pass
                finally:
                    time.sleep(random.uniform(0.3, 0.7))   # post-request cooldown
            return ticker, None

        with ThreadPoolExecutor(max_workers=_WORKERS) as executor:
            futures = {executor.submit(_fetch_cal, t): t for t in tickers}
            for i, fut in enumerate(as_completed(futures), 1):
                try:
                    t, date_str = fut.result()
                except Exception as e:
                    t = futures[fut]
                    date_str = None
                    print(f"  ⚠️  [{t}] calendar fetch error: {e}", flush=True)
                with _earn_lock:
                    if date_str:
                        self._earnings_mem[t] = date_str
                        _updated[0] += 1
                    else:
                        _failed[0] += 1
                if i % 50 == 0 or i == total:
                    print(f"  [{i}/{total}] earnings fetched: "
                          f"{_updated[0]} ✅  {_failed[0]} ⚠️")

        updated = _updated[0]
        failed  = _failed[0]

        # Write entire merged calendar back to sheet (with retry on network errors)
        valid_entries = [
            (t, _normalize_earnings_date(d))
            for t, d in sorted(self._earnings_mem.items())
            if t and _normalize_earnings_date(d)
        ]
        rows_out = [EARNINGS_HEADERS]
        for t, d in valid_entries:
            cached = self.get_row(t)
            rows_out.append([
                t,
                d,
                self._market_from_ticker(t),
                (cached.get('Name') if cached else '') or t,
                (cached.get('Sector') if cached else '') or '',
                (cached.get('MarketCap_Last') if cached else '') or '',
                datetime.now().strftime('%Y-%m-%d'),
            ])

        print(f"  [DEBUG] Writing {len(rows_out)} rows to Earnings_Calendar "
              f"({len(rows_out) - 1} tickers + header)")
        for attempt in range(4):
            try:
                self._earnings_ws.clear()
                self._earnings_ws.update(
                    range_name='A1',
                    values=rows_out,
                    value_input_option='USER_ENTERED',
                )
                # Read-back check
                rb = self._earnings_ws.get_all_values()
                print(f"  [DEBUG] Earnings_Calendar read-back: {len(rb)} rows")
                break
            except Exception as e:
                if attempt == 3:
                    print(f"  ⚠️  Earnings_Calendar sheet write failed after 4 attempts: {e}")
                else:
                    wait = 15 * (2 ** attempt)
                    print(f"  ⚠️  Sheet write error (attempt {attempt+1}/4): {e}  retrying in {wait}s...")
                    time.sleep(wait)

        print(f"  ✅ Earnings_Calendar saved: {updated} new/updated dates  "
              f"({len(valid_entries)} valid dated tickers)  {failed} no-date")

        # Mirror to SQLite so next run reads earnings from local DB (fast)
        self._sqlite_upsert_earnings(dict(valid_entries))

        # Mirror to API storage as well. The mobile API prefers the storage layer,
        # while Sheets remains the human-facing report surface.
        try:
            from quantbridge.writers.dual_write import dual_write_dataframe

            storage_rows = []
            for t, d in valid_entries:
                cached = self.get_row(t)
                record = {
                    'Ticker': t,
                    'Next_Earnings_Date': d,
                    'Last_Updated': datetime.now().strftime('%Y-%m-%d'),
                    'Market': self._market_from_ticker(t),
                }
                if cached:
                    record.update({
                        'Name': cached.get('Name') or t,
                        'Sector': cached.get('Sector') or '',
                        'MarketCap': cached.get('MarketCap_Last') or '',
                    })
                storage_rows.append(record)
            if storage_rows:
                dual_write_dataframe('Earnings_Calendar', pd.DataFrame(storage_rows), market='GLOBAL')
        except Exception as e:
            print(f"  ⚠️  Earnings_Calendar storage mirror skipped: {type(e).__name__}: {e}")

    # ──────────────────────────────────────────────────────────────────────────
    # Batch Pre-Warm
    # ──────────────────────────────────────────────────────────────────────────

    def prefetch(self, tickers: list, delay: float = 0.3):
        """
        Pre-warm the cache for a list of tickers before a pipeline run.
        Delegates to batch_fetch() for parallel yfinance fetching.

        delay parameter is kept for API backward-compatibility but is unused;
        rate limiting is handled internally via _fetch_with_retry() back-off.

        Example:
            cache.prefetch(universe_tickers)              # parallel warm
            for t in universe_tickers:
                info = cache.load_or_fetch_financials(t)  # all cache hits
        """
        stale_before = [t for t in tickers if self.needs_fin_update(t)]
        fresh = len(tickers) - len(stale_before)

        self.batch_fetch(tickers)

        print(f"  ✅ Prefetch done.  Cache holds {len(self._mem)} tickers  "
              f"(API reduction: {fresh / max(len(tickers), 1) * 100:.0f}%)")

    # ──────────────────────────────────────────────────────────────────────────
    # Cache Statistics
    # ──────────────────────────────────────────────────────────────────────────

    def stats(self) -> dict:
        """
        Return a summary dict of cache health.

        Keys: total, fresh, stale, missing_earnings, oldest_update
        """
        total = len(self._mem)
        stale = sum(1 for t in self._mem if self.needs_fin_update(t))
        fresh = total - stale

        oldest = None
        for row in self._mem.values():
            raw = row.get('Last_Fin_Update', '')
            if raw:
                try:
                    dt = _parse_dt(raw)
                    if dt is None:
                        continue
                    if oldest is None or dt < oldest:
                        oldest = dt
                except (ValueError, TypeError):
                    pass

        return {
            'total':           total,
            'fresh':           fresh,
            'stale':           stale,
            'oldest_update':   oldest.isoformat() if oldest else None,
        }

    def print_stats(self):
        """Pretty-print cache statistics to stdout."""
        s = self.stats()
        print(f"\n{'─' * 50}")
        print(f"  📊 CacheManager Stats")
        print(f"{'─' * 50}")
        print(f"  Total tickers cached : {s['total']}")
        print(f"  Fresh (≤{self.stale_days}d)       : {s['fresh']}")
        print(f"  Stale (>{self.stale_days}d)        : {s['stale']}")
        print(f"  Oldest update        : {s['oldest_update'] or 'N/A'}")
        try:
            db_rows = 0
            with sqlite3.connect(self._db_path, timeout=10) as conn:
                db_rows = conn.execute(
                    'SELECT COUNT(*) FROM company_master').fetchone()[0]
            db_mb = os.path.getsize(self._db_path) / 1024 / 1024
            print(f"  SQLite tickers       : {db_rows}  ({db_mb:.2f} MB)")
            print(f"  SQLite path          : {self._db_path}")
        except Exception:
            pass
        print(f"{'─' * 50}")


# ══════════════════════════════════════════════════════════════════════════════
# Bootstrap Script  (run once: python cache_manager.py)
# ══════════════════════════════════════════════════════════════════════════════

if __name__ == '__main__':
    """
    One-time setup:  creates Company_Master and Earnings_Calendar worksheets
    inside QuantBridge_Demo_Workbook if they don't already exist.

    Run:  python cache_manager.py
    """
    import gspread
    from sheets_client import get_spreadsheet

    print("=" * 60)
    print("  CacheManager Bootstrap")
    print("=" * 60)

    from sheets_client import get_spreadsheet
    spreadsheet = get_spreadsheet()

    print("\nConnected to: QuantBridge_Demo_Workbook")

    # This will create both worksheets (or confirm they exist)
    cache = CacheManager(spreadsheet, verbose=True)
    cache.print_stats()

    print("\n✅ Bootstrap complete.")
    print("   Company_Master and Earnings_Calendar are ready.")
    print("   All scripts can now import CacheManager and use the cache.")
