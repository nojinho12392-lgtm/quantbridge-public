from __future__ import annotations

import streamlit as st
import pandas as pd
import gspread
import json
import requests
import re
import sys
import os
from dataclasses import dataclass
from pathlib import Path

try:
    import yfinance as yf
    _YFINANCE_IMPORT_ERROR = None
except ImportError as exc:
    yf = None
    _YFINANCE_IMPORT_ERROR = exc


# ── Google Sheets loader ───────────────────────────────────────────────────────
_ROOT = Path(__file__).resolve().parents[2]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from kr_sector_map import UNCLASSIFIED_SECTOR, load_kr_sector_map, sector_for_ticker

try:
    from quantbridge.config import get_settings
    from quantbridge.storage import QuantRepository
except ImportError:
    @dataclass(frozen=True)
    class _DashboardSettings:
        google_key_path: Path = Path(os.getenv("QUANT_GOOGLE_KEY_PATH", "key.json"))
        spreadsheet_name: str = os.getenv("QUANT_SPREADSHEET_NAME", "Jino_Quant_Database")
        spreadsheet_id: str = os.getenv(
            "QUANT_SPREADSHEET_ID",
            "1kn0Kp1QESSdwvmphCmaUFHNRh9qVUoN-2m_vMVDYHes",
        )

    class QuantRepository:
        """No-op storage fallback for Streamlit Cloud dashboard-only deploys."""

        def __init__(self, *_args, **_kwargs):
            pass

        def read_dataframe(self, *_args, **_kwargs) -> pd.DataFrame:
            return pd.DataFrame()

        def read_pipeline_runs(self, *_args, **_kwargs) -> pd.DataFrame:
            return pd.DataFrame()

    def get_settings() -> _DashboardSettings:
        return _DashboardSettings()

_SETTINGS = get_settings()
_LOCAL_KEY_JSON = _SETTINGS.google_key_path
_SPREADSHEET_NAME = _SETTINGS.spreadsheet_name
_SPREADSHEET_ID = _SETTINGS.spreadsheet_id


def _yfinance_unavailable_message() -> str:
    if _YFINANCE_IMPORT_ERROR is None:
        return ""
    return f"yfinance unavailable: {_YFINANCE_IMPORT_ERROR}"


def _has_streamlit_runtime() -> bool:
    try:
        from streamlit.runtime.scriptrunner import get_script_run_ctx

        try:
            return get_script_run_ctx(suppress_warning=True) is not None
        except TypeError:
            return get_script_run_ctx() is not None
    except Exception:
        return False


def _attach_noop_cache_clear(func):
    if not hasattr(func, "clear"):
        func.clear = lambda *_args, **_kwargs: None
    return func


def _cache_data(*decorator_args, **decorator_kwargs):
    if _has_streamlit_runtime():
        return st.cache_data(*decorator_args, **decorator_kwargs)
    if decorator_args and callable(decorator_args[0]) and len(decorator_args) == 1 and not decorator_kwargs:
        return _attach_noop_cache_clear(decorator_args[0])

    def decorator(func):
        return _attach_noop_cache_clear(func)

    return decorator


def _load_google_key_dict():
    """Return Streamlit secret credentials if present; otherwise use local key.json."""
    try:
        raw = st.secrets.get("google_key")
        if raw:
            return json.loads(raw) if isinstance(raw, str) else dict(raw)
    except Exception:
        pass
    if _LOCAL_KEY_JSON.exists():
        with open(_LOCAL_KEY_JSON, encoding="utf-8") as f:
            return json.load(f)
    raise RuntimeError(
        "Google credentials not found. Add st.secrets['google_key'] or "
        f"place key.json at {_LOCAL_KEY_JSON}."
    )


@st.cache_resource
def _gspread_client():
    return gspread.service_account_from_dict(_load_google_key_dict())


@st.cache_resource
def _spreadsheet():
    client = _gspread_client()
    try:
        return client.open_by_key(_SPREADSHEET_ID)
    except Exception:
        return client.open(_SPREADSHEET_NAME)


@st.cache_resource
def _repository():
    return QuantRepository(_SETTINGS)


def _clean_dataframe_columns(df: pd.DataFrame) -> pd.DataFrame:
    """Drop blank or duplicate columns that can appear from oversized Sheets ranges."""
    if df.empty:
        return df
    out = df.copy()
    columns = [str(col).strip() if col is not None else "" for col in out.columns]
    keep = []
    seen = set()
    for col in columns:
        if not col or col in seen:
            keep.append(False)
            continue
        seen.add(col)
        keep.append(True)
    out.columns = columns
    return out.loc[:, keep]


def _sheet_values_to_df(rows, header) -> pd.DataFrame:
    """Build a DataFrame from Sheets values while ignoring blank/duplicate headers."""
    keep = []
    seen = set()
    for idx, raw_col in enumerate(header or []):
        col = str(raw_col).strip()
        if not col or col in seen:
            continue
        seen.add(col)
        keep.append((idx, col))
    if not keep:
        return pd.DataFrame()
    data = [
        [row[idx] if idx < len(row) else "" for idx, _ in keep]
        for row in rows
    ]
    return pd.DataFrame(data, columns=[col for _, col in keep])


@_cache_data(ttl=600)
def _load_storage_df(sheet_name: str) -> pd.DataFrame:
    global_datasets = {
        "Signal_Quality_Gates",
        "Factor_Weight_Policy",
        "Factor_Policy_Backtest",
        "Factor_Remediation_Plan",
        "Factor_IC_Report",
        "Factor_IC_Detail",
        "Factor_Score_Snapshots",
        "Factor_Snapshot_Backfill_Log",
        "Policy_Adjusted_Ranking_Summary",
        "Macro_Regime",
    }
    market = 'KR' if sheet_name.startswith('KR_') else 'US' if sheet_name.startswith('US_') else None
    if sheet_name in global_datasets:
        market = "GLOBAL"
    try:
        df = _repository().read_dataframe(sheet_name, market=market)
    except Exception:
        return pd.DataFrame()
    if df.empty:
        return df
    df = _clean_dataframe_columns(df)
    if 'Ticker' in df.columns:
        df = df[df['Ticker'].astype(str).str.strip() != '']
    if 'Rank' in df.columns:
        rank = pd.to_numeric(df['Rank'], errors='coerce')
        df = df[rank.notna()].copy()
        df['Rank'] = rank[rank.notna()]
    return df.reset_index(drop=True)


@_cache_data(ttl=600)
def load_portfolio_sheet(sheet_name):
    storage_df = _load_storage_df(sheet_name)
    if not storage_df.empty:
        if sheet_name.startswith('KR_'):
            storage_df = _apply_kr_sector_fallback(storage_df, force_kr=True)
        return {"Source": "storage"}, storage_df.reset_index(drop=True)

    ws = _spreadsheet().worksheet(sheet_name)
    data = ws.get_all_values()
    if not data:
        return {}, pd.DataFrame()

    header_idx = next(
        (i for i, row in enumerate(data)
         if sum(1 for c in row if c.strip()) >= 3),
        None,
    )
    meta = {}
    if header_idx is None:
        header_idx = 0
    for row in data[:header_idx]:
        if len(row) >= 2 and row[0].strip():
            meta[row[0].strip()] = row[1].strip()

    if header_idx >= len(data) - 1:
        return meta, pd.DataFrame()

    headers = data[header_idx]
    rows    = data[header_idx + 1:]
    df = pd.DataFrame(rows, columns=headers)
    seen = {}
    new_cols = []
    for i, col in enumerate(df.columns):
        if not col or col in seen:
            new_cols.append(f"Col_{i}")
        else:
            new_cols.append(col)
            seen[col] = True
    df.columns = new_cols
    df = df[df.apply(lambda r: r.str.strip().ne('').any(), axis=1)]
    if 'Ticker' in df.columns:
        df = df[df['Ticker'].str.strip() != '']
    if sheet_name.startswith('KR_'):
        df = _apply_kr_sector_fallback(df, force_kr=True)
    return meta, df.reset_index(drop=True)


# ── Data helpers ───────────────────────────────────────────────────────────────
def _to_num(df, cols):
    for c in cols:
        if c in df.columns:
            df[c] = pd.to_numeric(df[c], errors='coerce')
    return df


def _tickers_from_sheet_values(data) -> set:
    """Extract tickers from either simple sheets or summary-block sheets."""
    if not data:
        return set()
    header_idx = next(
        (i for i, row in enumerate(data) if 'Ticker' in [c.strip() for c in row]),
        None,
    )
    if header_idx is None:
        return set()
    headers = [c.strip() for c in data[header_idx]]
    ticker_idx = headers.index('Ticker')
    tickers = set()
    for row in data[header_idx + 1:]:
        if len(row) <= ticker_idx:
            continue
        ticker = row[ticker_idx].strip()
        if ticker:
            tickers.add(ticker)
    return tickers


def _fmt_krw(x):
    if not pd.notna(x) or x <= 0:
        return "—"
    eok = round(x / 1e8)
    jo, rem = divmod(eok, 10000)
    if jo > 0 and rem > 0:
        return f"₩{jo}조 {rem:,}억"
    elif jo > 0:
        return f"₩{jo}조"
    return f"₩{rem:,}억"


def _logo_url(ticker: str, currency: str) -> str:
    if currency == 'KRW':
        code = str(ticker).split('.')[0]
        if code == '064400':
            return "https://www.lgcns.com/etc.clientlibs/lgcns/clientlibs/clientlib-site/resources/image/common/logo-og-0807.png"
        if code == '267250':
            return f"https://file.alphasquare.co.kr/media/images/stock_logo/kr/{code}.png"
        return f"https://static.toss.im/png-icons/securities/icn-sec-fill-{code}.png"
    return f"https://financialmodelingprep.com/image-stock/{str(ticker).upper()}.png"


def _kr_code(value) -> str:
    """Return the 6-digit Korean stock code from a ticker/name-like value."""
    text = str(value or '').strip().upper()
    match = re.search(r'(\d{6})', text)
    return match.group(1) if match else ''


def _is_missing_kr_name(name, ticker='') -> bool:
    """True when the displayed company name is blank or just repeats a ticker/code."""
    name_text = str(name or '').strip()
    ticker_text = str(ticker or '').strip()
    code = _kr_code(ticker_text)
    if not name_text:
        return True
    if ticker_text and name_text.upper() == ticker_text.upper():
        return True
    if code and name_text == code:
        return True
    return bool(re.fullmatch(r'\d{6}(?:\.(?:KS|KQ))?', name_text.upper()))


def _has_kr_suffix(ticker) -> bool:
    return bool(re.fullmatch(r'\d{6}\.(?:KS|KQ)', str(ticker or '').strip().upper()))


def _apply_kr_sector_fallback(df: pd.DataFrame, force_kr: bool = False) -> pd.DataFrame:
    """Fill blank KR sector labels from KR_Sector_Map; explicit fallback is Unclassified."""
    if df.empty or 'Ticker' not in df.columns:
        return df
    out = df.copy()
    if 'Sector' not in out.columns:
        out['Sector'] = ''

    try:
        sector_map = load_kr_sector_map(_spreadsheet())
    except Exception:
        sector_map = {}

    def _is_kr_row(row) -> bool:
        if force_kr:
            return True
        market = str(row.get('Market', '')).upper()
        ticker = str(row.get('Ticker', '')).upper()
        return market == 'KR' or bool(re.fullmatch(r'\d{6}\.(?:KS|KQ)', ticker))

    def _sector(row):
        current = str(row.get('Sector') or '').strip()
        if not _is_kr_row(row):
            return current
        if current and current != UNCLASSIFIED_SECTOR:
            return current
        return sector_for_ticker(row.get('Ticker'), sector_map, current)

    out['Sector'] = out.apply(_sector, axis=1)
    return out


def enrich_kr_company_identities(df: pd.DataFrame, universe_df: pd.DataFrame | None = None) -> pd.DataFrame:
    """
    Fill Korean small-cap names and normalize code-only tickers.

    The KR small-cap scanner covers the full KOSDAQ plus small KOSPI names, so
    some rows are outside KR_Universe and can reach the dashboard with Name equal
    to the ticker. Match by both full yfinance ticker and 6-digit code, then use
    Naver mobile stock metadata as the authoritative fallback.
    """
    if df.empty or 'Ticker' not in df.columns:
        return df

    out = df.copy()
    if 'Name' not in out.columns:
        out['Name'] = ''

    out['Ticker'] = out['Ticker'].fillna('').astype(str).str.strip()
    out['Name'] = out['Name'].fillna('').astype(str).str.strip()

    # If Ticker is blank but Name contains a ticker/code, recover the ticker first.
    blank_ticker = out['Ticker'].eq('')
    if blank_ticker.any():
        out.loc[blank_ticker, 'Ticker'] = out.loc[blank_ticker, 'Name'].apply(_kr_code)

    ticker_by_code = {}
    name_by_code = {}
    name_by_ticker = {}

    if universe_df is None:
        try:
            universe_df, _, _ = load_search_universe()
        except Exception:
            universe_df = pd.DataFrame()

    if universe_df is not None and not universe_df.empty and {'Ticker', 'Name'}.issubset(universe_df.columns):
        uni = universe_df[['Ticker', 'Name']].copy()
        uni['Ticker'] = uni['Ticker'].fillna('').astype(str).str.strip()
        uni['Name'] = uni['Name'].fillna('').astype(str).str.strip()
        uni['Code'] = uni['Ticker'].apply(_kr_code)
        uni = uni[(uni['Code'] != '') & (uni['Name'] != '')]
        name_by_ticker.update(dict(zip(uni['Ticker'], uni['Name'])))
        ticker_by_code.update(dict(zip(uni['Code'], uni['Ticker'])))
        name_by_code.update(dict(zip(uni['Code'], uni['Name'])))

    # Normalize code-only tickers when the universe tells us the market suffix.
    code_only = out['Ticker'].apply(lambda t: bool(re.fullmatch(r'\d{6}', str(t).strip())))
    if code_only.any() and ticker_by_code:
        out.loc[code_only, 'Ticker'] = out.loc[code_only, 'Ticker'].map(ticker_by_code).fillna(out.loc[code_only, 'Ticker'])

    bad_name = out.apply(lambda r: _is_missing_kr_name(r.get('Name'), r.get('Ticker')), axis=1)
    if bad_name.any():
        mapped = out.loc[bad_name, 'Ticker'].map(name_by_ticker)
        out.loc[bad_name, 'Name'] = mapped.fillna(out.loc[bad_name, 'Name'])

    bad_name = out.apply(lambda r: _is_missing_kr_name(r.get('Name'), r.get('Ticker')), axis=1)
    if bad_name.any() and name_by_code:
        codes = out.loc[bad_name, 'Ticker'].apply(_kr_code)
        mapped = codes.map(name_by_code)
        out.loc[bad_name, 'Name'] = mapped.fillna(out.loc[bad_name, 'Name'])

    needs_naver = out.apply(
        lambda r: _is_missing_kr_name(r.get('Name'), r.get('Ticker')) or not _has_kr_suffix(r.get('Ticker')),
        axis=1,
    )
    lookup_keys = tuple(
        dict.fromkeys(out.loc[needs_naver, 'Ticker'].dropna().astype(str).str.strip())
    )
    if lookup_keys:
        try:
            identities = fetch_kr_identities(lookup_keys)
        except Exception:
            identities = {}

        for idx, row in out.loc[needs_naver].iterrows():
            raw_ticker = row.get('Ticker', '')
            ident = identities.get(raw_ticker) or identities.get(_kr_code(raw_ticker)) or {}
            if ident.get('Ticker') and (not _has_kr_suffix(raw_ticker)):
                out.at[idx, 'Ticker'] = ident['Ticker']
            if ident.get('Name') and _is_missing_kr_name(out.at[idx, 'Name'], out.at[idx, 'Ticker']):
                out.at[idx, 'Name'] = ident['Name']

    final_bad = out.apply(lambda r: _is_missing_kr_name(r.get('Name'), r.get('Ticker')), axis=1)
    if final_bad.any():
        out.loc[final_bad, 'Name'] = out.loc[final_bad, 'Ticker'].apply(_kr_code)

    return out


_PORT_NUM = ['Weight(%)', 'Total_Score', 'ROIC', 'RevGrowth',
             'GrossMargin', 'MarketCap', 'Expected_Return']
_SC_NUM   = ['ROIC', 'RevGrowth', 'PEG', 'GrossMargin', 'FCF_Margin',
             'Debt_EBITDA', 'Volume_Surge', 'SmallCap_Bonus', 'Total_Score', 'MarketCap']


def format_portfolio_df(df):
    df = df.copy()
    df = df[df.apply(lambda r: r.str.strip().ne('').any(), axis=1)]
    if 'Ticker' in df.columns:
        df = df[df['Ticker'].str.strip() != '']
    return _to_num(df, _PORT_NUM).reset_index(drop=True)


def format_smallcap_df(df):
    df = df.copy()
    df = df[df.apply(lambda r: r.str.strip().ne('').any(), axis=1)]
    if 'Ticker' in df.columns:
        df = df[df['Ticker'].str.strip() != '']
    if 'Rank' in df.columns:
        df = df[pd.to_numeric(df['Rank'], errors='coerce').notna()]
        df['Rank'] = pd.to_numeric(df['Rank'], errors='coerce').astype(int)
        df = df.sort_values('Rank').head(20)
    return _to_num(df, _SC_NUM).reset_index(drop=True)


def _add_logo_col(df, currency):
    if 'Ticker' in df.columns:
        df.insert(0, 'Logo', df['Ticker'].apply(lambda t: _logo_url(t, currency)))
    return df


def render_portfolio_table(df, currency='USD'):
    d = df.copy()
    d = _add_logo_col(d, currency)
    for col in ['Weight(%)', 'ROIC', 'RevGrowth', 'GrossMargin', 'Expected_Return']:
        if col in d.columns:
            d[col] = d[col].apply(lambda x: f"{x:.2%}" if pd.notna(x) else "—")
    if 'Total_Score' in d.columns:
        d['Total_Score'] = d['Total_Score'].apply(lambda x: f"{x:.4f}" if pd.notna(x) else "—")
    if 'MarketCap' in d.columns:
        if currency == 'USD':
            d['MarketCap'] = d['MarketCap'].apply(
                lambda x: f"${x/1e9:.1f}B" if pd.notna(x) and x > 0 else "—")
        else:
            d['MarketCap'] = d['MarketCap'].apply(_fmt_krw)
    if 'Rank' in d.columns:
        d['Rank'] = d['Rank'].apply(lambda x: str(x) if str(x).strip() else "—")
    return d


def render_smallcap_table(df, currency='USD'):
    d = df.copy()
    d = _add_logo_col(d, currency)
    for col in ['ROIC', 'RevGrowth', 'GrossMargin', 'FCF_Margin']:
        if col in d.columns:
            d[col] = d[col].apply(lambda x: f"{x:.2%}" if pd.notna(x) else "—")
    for col in ['PEG', 'Debt_EBITDA', 'Volume_Surge']:
        if col in d.columns:
            d[col] = d[col].apply(lambda x: f"{x:.2f}" if pd.notna(x) else "—")
    for col in ['SmallCap_Bonus', 'Total_Score']:
        if col in d.columns:
            d[col] = d[col].apply(lambda x: f"{x:.1f}" if pd.notna(x) else "—")
    if 'MarketCap' in d.columns:
        if currency == 'USD':
            d['MarketCap'] = d['MarketCap'].apply(
                lambda x: f"${x/1e6:.0f}M" if pd.notna(x) and x > 0 else "—")
        else:
            d['MarketCap'] = d['MarketCap'].apply(_fmt_krw)
    if 'Rank' in d.columns:
        d['Rank'] = d['Rank'].apply(lambda x: str(x) if str(x).strip() else "—")
    return d


# ── Stock detail fetcher ───────────────────────────────────────────────────────
def _safe_attr(obj, attr):
    try:
        v = getattr(obj, attr, None)
        return float(v) if v is not None else None
    except Exception:
        return None


def _infer_market_from_ticker(ticker: str) -> str:
    text = str(ticker or '').strip().upper()
    return 'KR' if re.fullmatch(r'\d{6}(?:\.(?:KS|KQ))?', text) else 'US'


def _storage_identity_info(ticker: str, prices: pd.DataFrame) -> dict:
    market = _infer_market_from_ticker(ticker)
    identity = _repository().read_identity(str(ticker).upper(), market=market) or {}
    close = pd.to_numeric(prices.get('Close'), errors='coerce').dropna() if not prices.empty else pd.Series(dtype=float)
    high = pd.to_numeric(prices.get('High'), errors='coerce').dropna() if not prices.empty else pd.Series(dtype=float)
    low = pd.to_numeric(prices.get('Low'), errors='coerce').dropna() if not prices.empty else pd.Series(dtype=float)
    return {
        'shortName': identity.get('name') or identity.get('Name'),
        'sector': identity.get('sector') or identity.get('Sector'),
        'logo_url': identity.get('logo_url') or _logo_url(ticker, 'KRW' if market == 'KR' else 'USD'),
        'currentPrice': float(close.iloc[-1]) if len(close) else None,
        'previousClose': float(close.iloc[-2]) if len(close) >= 2 else None,
        'fiftyTwoWeekHigh': float(high.max()) if len(high) else None,
        'fiftyTwoWeekLow': float(low.min()) if len(low) else None,
        'marketCap': identity.get('market_cap') or identity.get('MarketCap'),
    }


def _storage_prices_to_hist(prices: pd.DataFrame) -> pd.DataFrame:
    if prices.empty:
        return pd.DataFrame()
    hist = prices.rename(columns={
        'open': 'Open',
        'high': 'High',
        'low': 'Low',
        'close': 'Close',
        'volume': 'Volume',
    })
    hist['date'] = pd.to_datetime(hist['date'], errors='coerce')
    hist = hist.dropna(subset=['date']).set_index('date')
    return hist[['Open', 'High', 'Low', 'Close', 'Volume']]


def _price_records_from_yfinance(raw: pd.DataFrame) -> list[dict]:
    if raw.empty:
        return []
    if isinstance(raw.columns, pd.MultiIndex):
        raw.columns = raw.columns.get_level_values(0)
    rows = []
    for d, row in raw.iterrows():
        try:
            rows.append({
                'date': str(d.date()),
                'open': float(row['Open']),
                'high': float(row['High']),
                'low': float(row['Low']),
                'close': float(row['Close']),
                'volume': float(row['Volume']) if pd.notna(row.get('Volume')) else None,
            })
        except Exception:
            pass
    return rows


@_cache_data(ttl=300, show_spinner=False)
def fetch_stock_detail(ticker: str):
    """
    Returns (hist: DataFrame, info: dict, err: str).
    Uses yf.download() for price (more reliable than .history()) and
    fast_info for key metrics with full info as optional enrichment.
    """
    hist = pd.DataFrame()
    info = {}
    err  = ""

    market = _infer_market_from_ticker(ticker)
    try:
        stored_prices = _repository().read_prices(str(ticker).upper(), period='2y', market=market)
        if not stored_prices.empty:
            hist = _storage_prices_to_hist(stored_prices)
            info = _storage_identity_info(ticker, hist)
            return hist, info, err
    except Exception:
        pass

    if yf is None:
        return hist, info, _yfinance_unavailable_message()

    # ── Price history — yf.download is more robust on hosted environments
    try:
        raw = yf.download(
            ticker, period="2y",
            auto_adjust=True, progress=False,
            ignore_tz=True,
        )
        # yfinance >=0.2 returns MultiIndex columns when downloading a single ticker
        if isinstance(raw.columns, pd.MultiIndex):
            raw.columns = raw.columns.get_level_values(0)
        if not raw.empty:
            hist = raw
            try:
                _repository().upsert_prices(str(ticker).upper(), market, _price_records_from_yfinance(raw), source='yfinance')
            except Exception:
                pass
    except Exception as e:
        err = f"주가 데이터 오류: {e}"

    # ── Company info — fast_info first, then try full info for description
    try:
        stk = yf.Ticker(ticker)
        fi  = stk.fast_info          # always fast and reliable
        info = {
            'currentPrice':     _safe_attr(fi, 'last_price'),
            'previousClose':    _safe_attr(fi, 'previous_close'),
            'fiftyTwoWeekHigh': _safe_attr(fi, 'year_high'),
            'fiftyTwoWeekLow':  _safe_attr(fi, 'year_low'),
            'volume':           _safe_attr(fi, 'three_month_average_volume'),
            'marketCap':        _safe_attr(fi, 'market_cap'),
        }
        # Enrich with full info (description, sector, PE...) — may be slow/fail
        try:
            full = stk.info
            if isinstance(full, dict) and len(full) > 5:
                # Merge but keep fast_info values for price fields
                for k, v in full.items():
                    if k not in info or info[k] is None:
                        info[k] = v
        except Exception:
            pass
    except Exception as e:
        if not err:
            err = f"기업 정보 오류: {e}"

    return hist, info, err


# ── Earnings + Analyst data loader ───────────────────────────────────────────
@_cache_data(ttl=3600)
def fetch_earnings_data(ticker: str):
    """
    Returns (annual_rev, quarterly_rev, eps_df).
    annual_rev:    Total Revenue indexed by fiscal year date (ascending, last 5yr).
    quarterly_rev: Total Revenue indexed by quarter date (ascending, last 8q).
    eps_df:        ['EPS Estimate', 'Reported EPS'] indexed by date (most recent first).
    """
    annual_rev    = pd.Series(dtype=float)
    quarterly_rev = pd.Series(dtype=float)
    eps_df        = pd.DataFrame()
    if yf is None:
        return annual_rev, quarterly_rev, eps_df
    try:
        stk = yf.Ticker(ticker)

        # Annual revenue from annual income statement
        try:
            ai = stk.income_stmt
            if ai is not None and not ai.empty:
                for label in ['Total Revenue', 'Revenue']:
                    if label in ai.index:
                        row = ai.loc[label].dropna()
                        annual_rev = row.sort_index().tail(5)
                        break
        except Exception:
            pass

        # Quarterly revenue from income statement
        try:
            qi = stk.quarterly_income_stmt
            if qi is not None and not qi.empty:
                for label in ['Total Revenue', 'Revenue']:
                    if label in qi.index:
                        row = qi.loc[label].dropna()
                        quarterly_rev = row.sort_index()
                        break
        except Exception:
            pass

        # EPS actual vs estimate
        try:
            ed = stk.earnings_dates
            if ed is not None and not ed.empty:
                cols = [c for c in ['EPS Estimate', 'Reported EPS'] if c in ed.columns]
                if cols:
                    eps_df = ed[cols].dropna(how='all').head(8)
        except Exception:
            pass

    except Exception:
        pass

    return annual_rev, quarterly_rev, eps_df


# ── Backtest sheet loader ─────────────────────────────────────────────────────
@_cache_data(ttl=600)
def load_backtest_sheet(sheet_name: str = "US_Backtest_Results"):
    """
    Parse US_Backtest_Results → (meta, ret_df, detail_df).

    Sheet layout written by 05_backtest_us.py:
      Block 1 — key/value summary rows
      Block 2 — return table  (Date | Net_Return | Cumulative_Ret | Drawdown)
      Block 3 — period detail (Period | Holdings | ... | Top3_Tickers)
    """
    ws     = _spreadsheet().worksheet(sheet_name)
    data   = ws.get_all_values()
    if not data:
        return {}, pd.DataFrame(), pd.DataFrame()

    meta: dict       = {}
    ret_df           = pd.DataFrame()
    detail_df        = pd.DataFrame()
    ret_hdr_idx      = None
    detail_hdr_idx   = None

    # Single pass: find both table headers and collect meta key-values
    for i, row in enumerate(data):
        first = row[0].strip() if row else ''

        # Return table header — US writes 'Net_Return', KR writes 'Return'
        if first == 'Date' and len(row) >= 3 and (
                'Net_Return' in row or 'Return' in row):
            ret_hdr_idx = i
            continue

        # Period detail section marker
        if first == '── Period Detail ──':
            if i + 1 < len(data):
                detail_hdr_idx = i + 1
            continue

        # Meta key-value (skip section banners and empty rows)
        if (ret_hdr_idx is None           # still in summary block
                and first
                and not first.startswith('──')
                and len(row) >= 2
                and row[1].strip()):
            meta[first] = row[1].strip()

    # Parse return table
    if ret_hdr_idx is not None:
        headers = [c.strip() for c in data[ret_hdr_idx]]
        rows    = []
        for row in data[ret_hdr_idx + 1:]:
            if not row or not row[0].strip():
                break
            rows.append(row[:len(headers)])
        if rows:
            ret_df = pd.DataFrame(rows, columns=headers)
            # KR backtest writes 'Return'; normalise to 'Net_Return' for the chart renderer
            if 'Return' in ret_df.columns and 'Net_Return' not in ret_df.columns:
                ret_df = ret_df.rename(columns={'Return': 'Net_Return'})
            for col in ['Net_Return', 'Cumulative_Ret', 'Drawdown']:
                if col in ret_df.columns:
                    ret_df[col] = pd.to_numeric(ret_df[col], errors='coerce')
            if 'Date' in ret_df.columns:
                ret_df['Date'] = pd.to_datetime(ret_df['Date'], errors='coerce')
                ret_df = ret_df.dropna(subset=['Date']).reset_index(drop=True)

    # Parse period detail table
    if detail_hdr_idx is not None and detail_hdr_idx < len(data):
        headers = [c.strip() for c in data[detail_hdr_idx]]
        rows    = []
        for row in data[detail_hdr_idx + 1:]:
            if not row or not row[0].strip():
                break
            rows.append(row[:len(headers)])
        if rows:
            detail_df = pd.DataFrame(rows, columns=headers)
            for col in ['Gross_Return', 'Fee', 'Net_Return', 'Turnover_Pct']:
                if col in detail_df.columns:
                    detail_df[col] = pd.to_numeric(detail_df[col], errors='coerce')
            if 'Holdings' in detail_df.columns:
                detail_df['Holdings'] = pd.to_numeric(
                    detail_df['Holdings'], errors='coerce').astype('Int64')

    return meta, ret_df, detail_df


# ── NASDAQ benchmark for backtest comparison ─────────────────────────────────
@_cache_data(ttl=3600, show_spinner=False)
def fetch_nasdaq_benchmark(start_date: str, end_date: str) -> 'pd.Series':
    """
    Fetch ^IXIC (NASDAQ Composite) daily closes for [start_date, end_date]
    and return a cumulative-return Series (%) starting at 0 on start_date.
    Cached 1 hr — no need to refresh on every page render.
    """
    if yf is None:
        return pd.Series(dtype=float)
    try:
        raw = yf.download(
            '^IXIC',
            start=start_date,
            end=end_date,
            auto_adjust=True,
            progress=False,
        )
        if isinstance(raw.columns, pd.MultiIndex):
            closes = raw['Close'].squeeze()
        else:
            closes = raw['Close']
        closes = closes.dropna()
        if closes.empty:
            return pd.Series(dtype=float)
        cum = (closes / closes.iloc[0] - 1) * 100   # % starting at 0
        cum.index = pd.to_datetime(cum.index)
        return cum
    except Exception:
        return pd.Series(dtype=float)


# ── KOSPI benchmark for KR backtest comparison ───────────────────────────────
@_cache_data(ttl=3600, show_spinner=False)
def fetch_kospi_benchmark(start_date: str, end_date: str) -> 'pd.Series':
    """
    Fetch ^KS11 (KOSPI) daily closes for [start_date, end_date]
    and return a cumulative-return Series (%) starting at 0 on start_date.
    """
    if yf is None:
        return pd.Series(dtype=float)
    try:
        raw = yf.download(
            '^KS11',
            start=start_date,
            end=end_date,
            auto_adjust=True,
            progress=False,
        )
        if isinstance(raw.columns, pd.MultiIndex):
            closes = raw['Close'].squeeze()
        else:
            closes = raw['Close']
        closes = closes.dropna()
        if closes.empty:
            return pd.Series(dtype=float)
        cum = (closes / closes.iloc[0] - 1) * 100
        cum.index = pd.to_datetime(cum.index)
        return cum
    except Exception:
        return pd.Series(dtype=float)


# ── Scored stocks loader ──────────────────────────────────────────────────────
@_cache_data(ttl=600)
def load_scored_stocks(market: str = 'US') -> pd.DataFrame:
    """
    Load US_Scored_Stocks or KR_Scored_Stocks from Google Sheets.
    Returns empty DataFrame on failure.
    """
    sheet_name = 'US_Scored_Stocks' if market == 'US' else 'KR_Scored_Stocks'
    try:
        df = _load_storage_df(sheet_name)
        if df.empty:
            ws   = _spreadsheet().worksheet(sheet_name)
            data = ws.get_all_values()
            if len(data) < 2:
                return pd.DataFrame()
            df = pd.DataFrame(data[1:], columns=data[0])
        if 'Ticker' in df.columns:
            df = df[df['Ticker'].str.strip() != '']
        for col in ['Value_Score', 'Quality_Score', 'Momentum_Score',
                    'Total_Score', 'Final_Score', 'Score_Neutral',
                    'ML_Score', 'Combined_Score',
                    'ROIC', 'GrossMargin', 'FCF_Margin',
                    'RevGrowth', 'Debt_EBITDA', 'PEG',
                    'MarketCap', 'Rank']:
            if col in df.columns:
                df[col] = pd.to_numeric(df[col], errors='coerce')
        if market == 'KR':
            df = _apply_kr_sector_fallback(df, force_kr=True)
        return df.reset_index(drop=True)
    except Exception:
        return pd.DataFrame()


# ── Search universe loader ────────────────────────────────────────────────────
@_cache_data(ttl=600)
def load_search_universe():
    """
    Aggregate all searchable companies from US_Universe and KR_Universe.

    Returns:
        universe_df  — DataFrame with columns:
                        Ticker, Name, Market, Sector, MarketCap
        portfolio_set — set of tickers present in either Final Portfolio
        gem_set       — set of tickers present in either SmallCap Gems list
    """
    frames = []
    ss = None
    for sheet_name in ['US_Universe', 'KR_Universe']:
        try:
            df = _load_storage_df(sheet_name)
            if df.empty:
                if ss is None:
                    ss = _spreadsheet()
                data = ss.worksheet(sheet_name).get_all_values()
                if len(data) < 2:
                    continue
                df = pd.DataFrame(data[1:], columns=data[0])
            if 'Ticker' in df.columns:
                df = df[df['Ticker'].str.strip() != '']
            keep = [c for c in ['Ticker', 'Name', 'Market', 'Sector', 'MarketCap'] if c in df.columns]
            frames.append(df[keep].copy())
        except Exception:
            pass

    if frames:
        universe_df = pd.concat(frames, ignore_index=True)
        universe_df = _to_num(universe_df, ['MarketCap'])
        universe_df = universe_df.drop_duplicates(subset=['Ticker']).reset_index(drop=True)
        universe_df = _apply_kr_sector_fallback(universe_df)
    else:
        universe_df = pd.DataFrame(columns=['Ticker', 'Name', 'Market', 'Sector', 'MarketCap'])

    # Portfolio and gem membership (for badge display)
    portfolio_set: set = set()
    gem_set: set = set()

    for sname in ['US_Final_Portfolio', 'KR_Final_Portfolio']:
        try:
            df = _load_storage_df(sname)
            if not df.empty and 'Ticker' in df.columns:
                portfolio_set.update(df['Ticker'].dropna().astype(str).str.strip())
            else:
                if ss is None:
                    ss = _spreadsheet()
                data = ss.worksheet(sname).get_all_values()
                portfolio_set.update(_tickers_from_sheet_values(data))
        except Exception:
            pass

    for sname in ['US_SmallCap_Gems', 'KR_SmallCap_Gems']:
        try:
            df = _load_storage_df(sname)
            if not df.empty and 'Ticker' in df.columns:
                gem_set.update(df['Ticker'].dropna().astype(str).str.strip())
            else:
                if ss is None:
                    ss = _spreadsheet()
                data = ss.worksheet(sname).get_all_values()
                gem_set.update(_tickers_from_sheet_values(data))
        except Exception:
            pass

    return universe_df, portfolio_set, gem_set


# ── Earnings Momentum loader ─────────────────────────────────────────────────
@_cache_data(ttl=600)
def load_earnings_momentum(market: str = 'US') -> pd.DataFrame:
    """Load US_Earnings_Momentum or KR_Earnings_Momentum. Returns empty DF on failure."""
    sheet_name = 'US_Earnings_Momentum' if market == 'US' else 'KR_Earnings_Momentum'
    try:
        df = _load_storage_df(sheet_name)
        if df.empty:
            ws = _spreadsheet().worksheet(sheet_name)
            data = ws.get_all_values()
            if len(data) < 2:
                return pd.DataFrame()
            df = pd.DataFrame(data[1:], columns=data[0])
        for col in ['Surprise_Pct', 'Signal_Strength', 'Return_Since',
                    'Volume_Surge', 'Days_Since_Earnings', 'MarketCap']:
            if col in df.columns:
                df[col] = pd.to_numeric(df[col], errors='coerce')
        return df
    except Exception:
        return pd.DataFrame()


# ── Factor Attribution loader ─────────────────────────────────────────────────
@_cache_data(ttl=600)
def load_attribution_sheet() -> tuple[list[dict], pd.DataFrame]:
    """
    Parse Factor_Attribution sheet.

    Sheet layout written by 14_factor_attribution.py:
      Block 1 — header key-values (Generated, Method, …)
      Row     — SUMMARY_COLS header
      Rows    — one row per market (US, KR)
      blank
      Row     — DETAIL_COLS header
      Rows    — per-stock attribution detail

    Returns:
        summaries  — list of dicts, one per market
        detail_df  — per-stock DataFrame
    """
    storage_summary = _load_storage_df("Factor_Attribution")
    storage_detail = _load_storage_df("Factor_Attribution_Detail")
    if not storage_summary.empty:
        for col in ['Portfolio_Return', 'Value_Contrib', 'Quality_Contrib',
                    'Momentum_Contrib', 'Residual', 'Beta_V', 'Beta_Q', 'Beta_M',
                    'R_Squared', 'Days']:
            if col in storage_summary.columns:
                storage_summary[col] = pd.to_numeric(storage_summary[col], errors='coerce')
        if not storage_detail.empty:
            for col in ['Weight', 'Return', 'V_Exposure', 'Q_Exposure', 'M_Exposure',
                        'Predicted_Return', 'Stock_Residual']:
                if col in storage_detail.columns:
                    storage_detail[col] = pd.to_numeric(storage_detail[col], errors='coerce')
        return storage_summary.to_dict('records'), storage_detail

    try:
        ws     = _spreadsheet().worksheet("Factor_Attribution")
        data   = ws.get_all_values()
    except Exception:
        return [], pd.DataFrame()

    if not data:
        return [], pd.DataFrame()

    summaries       = []
    detail_df       = pd.DataFrame()
    summary_hdr     = None
    summary_rows    = []
    detail_hdr      = None
    detail_rows_raw = []
    state           = 'pre_summary'   # states: pre_summary → in_summary → in_detail

    for row in data:
        first = row[0].strip() if row else ''

        if state == 'pre_summary':
            # Detect summary header row (contains 'Market' and 'Portfolio_Return')
            if 'Market' in row and 'Portfolio_Return' in row:
                summary_hdr = [c.strip() for c in row]
                state = 'in_summary'
            continue

        if state == 'in_summary':
            if not first:
                continue  # skip blank rows between tables
            # Detect transition to detail table
            if 'Ticker' in row and 'Weight' in row:
                detail_hdr = [c.strip() for c in row]
                state = 'in_detail'
                continue
            if summary_hdr and len(row) >= len(summary_hdr):
                summary_rows.append(row[:len(summary_hdr)])
            continue

        if state == 'in_detail':
            if first:
                detail_rows_raw.append(row)

    # Build summaries list
    if summary_hdr and summary_rows:
        for row in summary_rows:
            d = dict(zip(summary_hdr, row))
            # Coerce numerics
            for k in ['Portfolio_Return', 'Value_Contrib', 'Quality_Contrib',
                      'Momentum_Contrib', 'Residual', 'Beta_V', 'Beta_Q', 'Beta_M',
                      'R_Squared', 'Days']:
                try:
                    d[k] = float(d[k]) if d.get(k, '') != '' else None
                except (ValueError, TypeError):
                    d[k] = None
            summaries.append(d)

    # Build detail df
    if detail_hdr and detail_rows_raw:
        detail_df = pd.DataFrame(detail_rows_raw, columns=detail_hdr)
        for col in ['Weight', 'Return', 'V_Exposure', 'Q_Exposure', 'M_Exposure',
                    'Predicted_Return', 'Stock_Residual']:
            if col in detail_df.columns:
                detail_df[col] = pd.to_numeric(detail_df[col], errors='coerce')
        detail_df = detail_df[detail_df['Ticker'].str.strip() != ''].reset_index(drop=True)

    return summaries, detail_df


# ── Factor IC report loader ──────────────────────────────────────────────────
@_cache_data(ttl=600)
def load_factor_ic_report() -> tuple[pd.DataFrame, pd.DataFrame]:
    """
    Parse Factor_IC_Report sheet.

    Returns:
        summary_df — one row per Market/Factor/Horizon
        detail_df  — per-snapshot diagnostics
    """
    summary_df = _load_storage_df("Factor_IC_Report")
    detail_df = _load_storage_df("Factor_IC_Detail")
    if not summary_df.empty:
        summary_num = [
            "Snapshots", "Mean_IC", "Median_IC", "Positive_IC_Rate",
            "Mean_Top_Bottom_Spread", "Mean_Top_Quintile_Return",
            "Mean_Bottom_Quintile_Return", "Mean_Hit_Rate", "Total_Observations",
            "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
        ]
        detail_num = [
            "IC", "N", "Top_Quintile_Return", "Bottom_Quintile_Return",
            "Top_Bottom_Spread", "Hit_Rate",
        ]
        for col in summary_num:
            if col in summary_df.columns:
                summary_df[col] = pd.to_numeric(summary_df[col], errors="coerce")
        for col in detail_num:
            if col in detail_df.columns:
                detail_df[col] = pd.to_numeric(detail_df[col], errors="coerce")
        return summary_df, detail_df

    try:
        ws = _spreadsheet().worksheet("Factor_IC_Report")
        data = ws.get_all_values()
    except Exception:
        return pd.DataFrame(), pd.DataFrame()

    if not data:
        return pd.DataFrame(), pd.DataFrame()

    summary_hdr = None
    summary_rows = []
    detail_hdr = None
    detail_rows = []
    state = "pre_summary"

    for row in data:
        first = row[0].strip() if row else ""

        if state == "pre_summary":
            if "Market" in row and "Mean_IC" in row:
                summary_hdr = [c.strip() for c in row]
                state = "in_summary"
            continue

        if state == "in_summary":
            if not first:
                continue
            if "Snapshot_Date" in row and "IC" in row:
                detail_hdr = [c.strip() for c in row]
                state = "in_detail"
                continue
            if summary_hdr and len(row) >= len(summary_hdr):
                summary_rows.append(row[:len(summary_hdr)])
            continue

        if state == "in_detail" and first:
            detail_rows.append(row[:len(detail_hdr)])

    summary_df = _sheet_values_to_df(summary_rows, summary_hdr) if summary_hdr and summary_rows else pd.DataFrame()
    detail_df = _sheet_values_to_df(detail_rows, detail_hdr) if detail_hdr and detail_rows else pd.DataFrame()

    summary_num = [
        "Snapshots", "Mean_IC", "Median_IC", "Positive_IC_Rate",
        "Mean_Top_Bottom_Spread", "Mean_Top_Quintile_Return",
        "Mean_Bottom_Quintile_Return", "Mean_Hit_Rate", "Total_Observations",
        "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    ]
    detail_num = [
        "IC", "N", "Top_Quintile_Return", "Bottom_Quintile_Return",
        "Top_Bottom_Spread", "Hit_Rate",
    ]
    for col in summary_num:
        if col in summary_df.columns:
            summary_df[col] = pd.to_numeric(summary_df[col], errors="coerce")
    for col in detail_num:
        if col in detail_df.columns:
            detail_df[col] = pd.to_numeric(detail_df[col], errors="coerce")

    return summary_df, detail_df


@_cache_data(ttl=600)
def load_signal_quality_gates() -> pd.DataFrame:
    """Load PASS/WATCH/FAIL factor reliability gates."""
    df = _load_storage_df("Signal_Quality_Gates")
    if df.empty:
        try:
            ws = _spreadsheet().worksheet("Signal_Quality_Gates")
            data = ws.get_all_values()
            if len(data) >= 2:
                df = _sheet_values_to_df(data[1:], data[0])
        except Exception:
            return pd.DataFrame()

    numeric_cols = [
        "Mean_IC", "Positive_IC_Rate", "Mean_Top_Bottom_Spread",
        "Mean_Hit_Rate", "Snapshots", "Total_Observations",
        "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    ]
    for col in numeric_cols:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    if "Status" in df.columns:
        df["Status"] = df["Status"].fillna("").astype(str).str.upper()
    if "Market" in df.columns:
        df["Market"] = df["Market"].fillna("").astype(str).str.upper()
    for col in ["Evidence_Source", "Production_Ready"]:
        if col in df.columns:
            df[col] = df[col].fillna("").astype(str).str.upper()
    return df.reset_index(drop=True)


@_cache_data(ttl=600)
def load_factor_weight_policy() -> pd.DataFrame:
    """Load observation-only factor weight policy recommendations."""
    df = _load_storage_df("Factor_Weight_Policy")
    if df.empty:
        try:
            ws = _spreadsheet().worksheet("Factor_Weight_Policy")
            data = ws.get_all_values()
            if len(data) >= 2:
                df = _sheet_values_to_df(data[1:], data[0])
        except Exception:
            return pd.DataFrame()

    numeric_cols = [
        "Adjustment_Bias", "Suggested_Multiplier", "Mean_IC",
        "Positive_IC_Rate", "Snapshots", "Total_Observations",
        "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    ]
    for col in numeric_cols:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    for col in ["Policy_Status", "Evidence_Status", "Market", "Evidence_Source", "Production_Ready"]:
        if col in df.columns:
            df[col] = df[col].fillna("").astype(str).str.upper()
    return df.reset_index(drop=True)


@_cache_data(ttl=600)
def load_factor_policy_backtest() -> pd.DataFrame:
    """Load base-vs-policy factor composite backtest diagnostics."""
    df = _load_storage_df("Factor_Policy_Backtest")
    if df.empty:
        try:
            ws = _spreadsheet().worksheet("Factor_Policy_Backtest")
            data = ws.get_all_values()
            if len(data) >= 2:
                df = _sheet_values_to_df(data[1:], data[0])
        except Exception:
            return pd.DataFrame()

    numeric_cols = [
        "Snapshots", "Total_Observations", "Base_Weighted_IC",
        "Policy_Weighted_IC", "IC_Delta", "Base_Top_Bottom_Spread",
        "Policy_Top_Bottom_Spread", "Spread_Delta", "Base_Hit_Rate",
        "Policy_Hit_Rate", "Turnover_Estimate", "Live_Snapshots",
        "Proxy_Snapshots", "Proxy_Ratio",
    ]
    for col in numeric_cols:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    for col in ["Status", "Market", "Evidence_Source", "Production_Ready"]:
        if col in df.columns:
            df[col] = df[col].fillna("").astype(str).str.upper()
    return df.reset_index(drop=True)


@_cache_data(ttl=600)
def load_factor_remediation_plan() -> pd.DataFrame:
    """Load prioritized weak-factor remediation actions."""
    df = _load_storage_df("Factor_Remediation_Plan")
    if df.empty:
        try:
            ws = _spreadsheet().worksheet("Factor_Remediation_Plan")
            data = ws.get_all_values()
            if len(data) >= 2:
                df = _sheet_values_to_df(data[1:], data[0])
        except Exception:
            return pd.DataFrame()

    numeric_cols = [
        "Priority", "Mean_IC", "Positive_IC_Rate", "Top_Bottom_Spread",
        "IC_Delta",
    ]
    for col in numeric_cols:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    for col in [
        "Market", "Severity", "Worst_Status", "Policy_Status",
        "Policy_Backtest_Status", "Production_Ready", "Evidence_Source",
    ]:
        if col in df.columns:
            df[col] = df[col].fillna("").astype(str).str.upper()
    return df.reset_index(drop=True)


@_cache_data(ttl=600)
def load_policy_adjusted_rankings(market: str) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Load observation-only policy-adjusted ranking movers for a market."""
    market = str(market or "US").upper()
    if market not in {"US", "KR"}:
        return pd.DataFrame(), pd.DataFrame()

    ranking = _load_storage_df(f"{market}_Policy_Adjusted_Ranking")
    summary = _load_storage_df("Policy_Adjusted_Ranking_Summary")

    numeric_cols = [
        "Policy_Rank", "Base_Rank", "Rank_Change", "MarketCap",
        "Policy_Final_Score", "Base_Final_Score", "Score_Change",
        "Policy_Total_Score", "Base_Total_Score",
        "Policy_Score_Neutral", "Base_Score_Neutral",
        "Policy_Value_Score", "Base_Value_Score", "Value_Multiplier",
        "Policy_Quality_Score", "Base_Quality_Score", "Quality_Multiplier",
        "Policy_Momentum_Score", "Base_Momentum_Score", "Momentum_Multiplier",
        "Investability_Score", "Business_Quality_Score", "Quality_Data_Confidence",
    ]
    for col in numeric_cols:
        if col in ranking.columns:
            ranking[col] = pd.to_numeric(ranking[col], errors="coerce")

    summary_num_cols = [
        "Rows", "Positive_Movers", "Negative_Movers", "Unchanged",
        "Mean_Abs_Rank_Change", "Top_Up_Rank_Change", "Top_Down_Rank_Change",
    ]
    for col in summary_num_cols:
        if col in summary.columns:
            summary[col] = pd.to_numeric(summary[col], errors="coerce")
    if "Market" in summary.columns:
        summary["Market"] = summary["Market"].fillna("").astype(str).str.upper()
        summary = summary[summary["Market"] == market].copy()
    for col in ["Policy_Mode", "Evidence_Source", "Production_Ready"]:
        if col in ranking.columns:
            ranking[col] = ranking[col].fillna("").astype(str).str.upper()
        if col in summary.columns:
            summary[col] = summary[col].fillna("").astype(str).str.upper()

    if "Policy_Rank" in ranking.columns:
        ranking = ranking.sort_values("Policy_Rank", na_position="last")
    return ranking.reset_index(drop=True), summary.reset_index(drop=True)


@_cache_data(ttl=600)
def load_pipeline_runs(limit: int = 30) -> pd.DataFrame:
    """Load recent QuantBridge pipeline run records from PostgreSQL."""
    try:
        df = _repository().read_pipeline_runs(limit=limit)
    except Exception:
        return pd.DataFrame()
    if df.empty:
        return df
    for col in ["started_at", "finished_at"]:
        if col in df.columns:
            df[col] = pd.to_datetime(df[col], errors="coerce")
    if "status" in df.columns:
        df["status"] = df["status"].fillna("").astype(str).str.lower()
    return df.reset_index(drop=True)


def _api_base_url() -> str:
    try:
        secret_url = st.secrets.get("quant_api_base_url")
        if secret_url:
            return str(secret_url).rstrip("/")
    except Exception:
        pass
    return os.getenv("QUANT_API_BASE_URL", "http://127.0.0.1:8000").rstrip("/")


@_cache_data(ttl=60)
def load_ops_health(max_research_age_hours: int = 84) -> dict:
    """Load phase-4 operating health from the FastAPI service."""
    base_url = _api_base_url()
    try:
        response = requests.get(
            f"{base_url}/ops/health",
            params={"max_research_age_hours": max_research_age_hours},
            timeout=6,
        )
        response.raise_for_status()
        payload = response.json()
        payload["_base_url"] = base_url
        return payload
    except Exception as exc:
        return {
            "healthy": False,
            "status": "UNAVAILABLE",
            "_base_url": base_url,
            "generated_at": "",
            "status_counts": {"FAIL": 1},
            "checks": [{
                "name": "FastAPI /ops/health",
                "status": "FAIL",
                "message": f"{type(exc).__name__}: {exc}",
                "detail": {},
            }],
        }


@_cache_data(ttl=60)
def load_data_quality(max_age_days: int = 0) -> dict:
    """Load app-facing dataset quality from the FastAPI service."""
    base_url = _api_base_url()
    params = {"max_age_days": max_age_days} if max_age_days > 0 else None
    try:
        response = requests.get(
            f"{base_url}/ops/data-quality",
            params=params,
            timeout=8,
        )
        response.raise_for_status()
        payload = response.json()
        payload["_base_url"] = base_url
        return payload
    except Exception as exc:
        return {
            "healthy": False,
            "status": "UNAVAILABLE",
            "_base_url": base_url,
            "generated_at": "",
            "status_counts": {"FAIL": 1},
            "datasets": [{
                "dataset": "FastAPI /ops/data-quality",
                "market": "",
                "status": "FAIL",
                "rows": 0,
                "checks": [{
                    "name": "Data quality endpoint",
                    "status": "FAIL",
                    "message": f"{type(exc).__name__}: {exc}",
                    "detail": {},
                }],
            }],
        }


# ── Correlation data loader ──────────────────────────────────────────────────
@_cache_data(ttl=3600, show_spinner=False)
def load_correlation_data(sheet_name: str = "US_Final_Portfolio"):
    """
    Load portfolio tickers from sheet, download 1yr daily returns, return correlation matrix.
    Returns (corr_df, tickers_list, names_dict).
    """
    if yf is None:
        return pd.DataFrame(), [], {}
    try:
        ws   = _spreadsheet().worksheet(sheet_name)
        data = ws.get_all_values()
        if not data:
            return pd.DataFrame(), [], {}

        header_idx = next(
            (i for i, row in enumerate(data) if sum(1 for c in row if c.strip()) >= 3),
            0,
        )
        headers = data[header_idx]
        rows    = data[header_idx + 1:]
        df = pd.DataFrame(rows, columns=headers)
        df = df[df.apply(lambda r: r.str.strip().ne('').any(), axis=1)]
        if 'Ticker' not in df.columns:
            return pd.DataFrame(), [], {}

        tickers = [t.strip() for t in df['Ticker'] if t.strip()]
        names   = {}
        if 'Name' in df.columns:
            names = dict(zip(df['Ticker'].str.strip(), df['Name'].str.strip()))
        if not tickers:
            return pd.DataFrame(), [], {}

        raw = yf.download(tickers, period="1y", auto_adjust=True, progress=False, ignore_tz=True)
        if isinstance(raw.columns, pd.MultiIndex):
            closes = raw['Close']
        else:
            closes = raw[['Close']] if 'Close' in raw.columns else raw

        closes = closes.dropna(how='all')
        if closes.empty:
            return pd.DataFrame(), tickers, names

        # Drop tickers with <70% data coverage (delisted / newly listed)
        closes = closes.dropna(thresh=int(len(closes) * 0.7), axis=1)
        returns = closes.pct_change().dropna()
        corr    = returns.corr()
        return corr, list(corr.columns), names
    except Exception:
        return pd.DataFrame(), [], {}


# ── Industry ranking loader ───────────────────────────────────────────────────
@_cache_data(ttl=600)
def load_industry_ranking():
    """Load US_Industry_Ranking sheet. Returns DataFrame or empty on failure."""
    try:
        df = _load_storage_df("US_Industry_Ranking")
        if df.empty:
            ws   = _spreadsheet().worksheet("US_Industry_Ranking")
            data = ws.get_all_values()
            if not data:
                return pd.DataFrame()

            # Sheet has a 9-row summary block before the real column headers.
            # Find the header row by looking for the row that starts with 'Rank'.
            header_idx = next(
                (i for i, row in enumerate(data) if row and row[0].strip() == 'Rank'),
                None,
            )
            if header_idx is None or header_idx + 1 >= len(data):
                return pd.DataFrame()

            df = pd.DataFrame(data[header_idx + 1:], columns=data[header_idx])
        for col in ['Rank', 'Stock_Count', 'Mean_Return', 'Breadth',
                    'Mean_Return_Rank', 'Breadth_Rank', 'Combined_Rank', 'Lookback_Days']:
            if col in df.columns:
                df[col] = pd.to_numeric(df[col], errors='coerce')
        if 'Industry' in df.columns:
            df = df[df['Industry'].str.strip() != ''].reset_index(drop=True)
        return df
    except Exception:
        return pd.DataFrame()


# ── KR Order Flow loader ──────────────────────────────────────────────────────
@_cache_data(ttl=600)
def load_kr_order_flow():
    """Load KR_Dual_Net_Buyers sheet. Returns DataFrame or empty on failure."""
    try:
        df = _load_storage_df("KR_Dual_Net_Buyers")
        if df.empty:
            ws   = _spreadsheet().worksheet("KR_Dual_Net_Buyers")
            data = ws.get_all_values()
            if not data:
                return pd.DataFrame()

            # Sheet has a summary block before the real column headers.
            # Find the header row by looking for the row that starts with 'Rank'.
            header_idx = next(
                (i for i, row in enumerate(data) if row and row[0].strip() == 'Rank'),
                None,
            )
            if header_idx is None or header_idx + 1 >= len(data):
                return pd.DataFrame()

            df = pd.DataFrame(data[header_idx + 1:], columns=data[header_idx])
        for col in ['Rank', 'Consecutive_Days', 'Foreign_Net_Buy', 'Inst_Net_Buy']:
            if col in df.columns:
                df[col] = pd.to_numeric(df[col], errors='coerce')
        if 'Ticker' in df.columns:
            df = df[df['Ticker'].str.strip() != ''].reset_index(drop=True)
        return df
    except Exception:
        return pd.DataFrame()


# ── Portfolio Drift Alert loader ─────────────────────────────────────────────
@_cache_data(ttl=600)
def load_drift_alert() -> tuple[dict, pd.DataFrame]:
    """
    Parse Portfolio_Drift_Alert sheet.

    Sheet layout written by 12_portfolio_drift_monitor.py:
      Block 1 — key/value header rows (Generated, Method, …)
      Row     — SUMMARY_COLS header  (contains 'Total_Drift' and 'Market')
      Rows    — one row per market (US, KR)
      blank
      Row     — DRIFT_COLS header   (contains 'Drift_Abs' and 'Ticker')
      Rows    — per-stock detail rows

    Returns:
        summaries  — dict keyed by market ('US'/'KR'), each value is a row dict
        detail_df  — per-stock DataFrame
    """
    storage_detail = _load_storage_df("Portfolio_Drift_Alert")
    storage_summary = _load_storage_df("Portfolio_Drift_Summary")
    if not storage_detail.empty:
        for col in ['Target_Weight', 'Current_Weight', 'Drift_Abs', 'Drift_Pct',
                    'Price_Rebal', 'Price_Current', 'Return_Since_Rebal']:
            if col in storage_detail.columns:
                storage_detail[col] = pd.to_numeric(storage_detail[col], errors='coerce')
        summaries = {}
        if not storage_summary.empty:
            for col in ['Total_Drift', 'Stocks_Rebalance', 'Stocks_Watch',
                        'Stocks_OK', 'Days_Since_Rebal']:
                if col in storage_summary.columns:
                    storage_summary[col] = pd.to_numeric(storage_summary[col], errors='coerce')
            summaries = {
                str(row.get('Market', '')).strip(): row
                for row in storage_summary.to_dict('records')
                if str(row.get('Market', '')).strip()
            }
        return summaries, storage_detail

    try:
        ws     = _spreadsheet().worksheet("Portfolio_Drift_Alert")
        data   = ws.get_all_values()
    except Exception:
        return {}, pd.DataFrame()

    if not data:
        return {}, pd.DataFrame()

    summaries: dict     = {}
    detail_df           = pd.DataFrame()
    summary_hdr         = None
    summary_rows        = []
    detail_hdr          = None
    detail_rows_raw     = []
    state               = 'pre_summary'

    for row in data:
        first = row[0].strip() if row else ''

        if state == 'pre_summary':
            if 'Total_Drift' in row and 'Market' in row:
                summary_hdr = [c.strip() for c in row]
                state = 'in_summary'
            continue

        if state == 'in_summary':
            if not first:
                continue
            if 'Drift_Abs' in row and 'Ticker' in row:
                detail_hdr = [c.strip() for c in row]
                state = 'in_detail'
                continue
            if summary_hdr and len(row) >= len(summary_hdr):
                summary_rows.append(row[:len(summary_hdr)])
            continue

        if state == 'in_detail':
            if first:
                detail_rows_raw.append(row)

    # Build summaries dict keyed by market
    if summary_hdr and summary_rows:
        for row in summary_rows:
            d = dict(zip(summary_hdr, row))
            for k in ['Total_Drift', 'Stocks_Rebalance', 'Stocks_Watch',
                      'Stocks_OK', 'Days_Since_Rebal']:
                try:
                    d[k] = float(d[k]) if d.get(k, '') != '' else None
                except (ValueError, TypeError):
                    d[k] = None
            market = d.get('Market', '').strip()
            if market:
                summaries[market] = d

    # Build detail df
    if detail_hdr and detail_rows_raw:
        detail_df = pd.DataFrame(detail_rows_raw, columns=detail_hdr)
        for col in ['Target_Weight', 'Current_Weight', 'Drift_Abs', 'Drift_Pct',
                    'Price_Rebal', 'Price_Current', 'Return_Since_Rebal']:
            if col in detail_df.columns:
                detail_df[col] = pd.to_numeric(detail_df[col], errors='coerce')
        if 'Ticker' in detail_df.columns:
            detail_df = detail_df[detail_df['Ticker'].str.strip() != ''].reset_index(drop=True)

    return summaries, detail_df


# ── NASDAQ Top-20 marquee ──────────────────────────────────────────────────────
NASDAQ_TOP20 = [
    ('AAPL',  'Apple'),
    ('MSFT',  'Microsoft'),
    ('NVDA',  'NVIDIA'),
    ('AMZN',  'Amazon'),
    ('META',  'Meta'),
    ('GOOGL', 'Alphabet'),
    ('TSLA',  'Tesla'),
    ('AVGO',  'Broadcom'),
    ('COST',  'Costco'),
    ('NFLX',  'Netflix'),
    ('AMD',   'AMD'),
    ('ADBE',  'Adobe'),
    ('QCOM',  'Qualcomm'),
    ('INTU',  'Intuit'),
    ('CSCO',  'Cisco'),
    ('AMGN',  'Amgen'),
    ('TXN',   'Texas Instruments'),
    ('ISRG',  'Intuitive Surgical'),
    ('BKNG',  'Booking Holdings'),
    ('REGN',  'Regeneron'),
]


@_cache_data(ttl=300, show_spinner=False)
def fetch_marquee_prices():
    """
    Fetch 2-day OHLC for NASDAQ top-20 and return
    [(ticker, name, price, pct_change), ...].  TTL = 5 min.
    """
    if yf is None:
        return []
    tickers  = [t for t, _ in NASDAQ_TOP20]
    name_map = {t: n for t, n in NASDAQ_TOP20}
    result   = []
    try:
        raw = yf.download(
            tickers, period="2d",
            auto_adjust=True, progress=False,
            ignore_tz=True, group_by="ticker",
        )
        for ticker in tickers:
            try:
                if isinstance(raw.columns, pd.MultiIndex):
                    vals = raw[ticker]["Close"].dropna()
                else:
                    vals = raw["Close"].dropna()
                if len(vals) >= 2:
                    price = float(vals.iloc[-1])
                    pct   = float((vals.iloc[-1] - vals.iloc[-2]) / vals.iloc[-2])
                elif len(vals) == 1:
                    price, pct = float(vals.iloc[0]), 0.0
                else:
                    price, pct = 0.0, 0.0
            except Exception:
                price, pct = 0.0, 0.0
            result.append((ticker, name_map[ticker], price, pct))
    except Exception:
        result = [(t, n, 0.0, 0.0) for t, n in NASDAQ_TOP20]
    return result


@_cache_data(ttl=600)
def load_macro_regime() -> dict:
    """
    Load current macro regime and factor weights from Macro_Regime sheet.
    Returns dict with keys: Regime, US_V/Q/M_Weight, KR_V/Q/M_Weight, Generated.
    Returns empty dict on failure.
    """
    try:
        df = _load_storage_df("Macro_Regime")
        if not df.empty and {'Key', 'Value'}.issubset(df.columns):
            return {
                str(row['Key']).strip(): str(row['Value']).strip()
                for _, row in df.iterrows()
                if str(row['Key']).strip()
            }
        ws   = _spreadsheet().worksheet("Macro_Regime")
        rows = ws.get_all_values()
        kv   = {r[0].strip(): r[1].strip() for r in rows if len(r) >= 2 and r[0].strip()}
        return kv
    except Exception:
        return {}


@_cache_data(ttl=86400, show_spinner=False)
def fetch_kr_identities(tickers: tuple) -> dict:
    """
    Fetch Korean company names and exchange suffixes from Naver Finance.
    tickers: tuple of yfinance KR tickers (e.g. '005930.KS', '293490.KQ')
    Returns dict mapping the input ticker and 6-digit code to:
        {'Ticker': '005930.KS', 'Name': '삼성전자'}
    Cached 24h since names rarely change.
    """
    result = {}
    headers = {
        'User-Agent': 'Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X) AppleWebKit/605.1.15',
        'Referer': 'https://m.stock.naver.com/',
    }
    for ticker in tickers:
        raw = str(ticker or '').strip()
        code = _kr_code(raw)
        if not code:
            continue
        try:
            resp = requests.get(
                f"https://m.stock.naver.com/api/stock/{code}/basic",
                headers=headers,
                timeout=5,
            )
            if resp.status_code == 200:
                data = resp.json()
                name = data.get('stockName', '').strip()
                exchange = (
                    data.get('stockExchangeType', {}).get('code')
                    or data.get('stockExchangeName')
                    or ''
                )
                exchange = str(exchange).upper()
                suffix = '.KQ' if exchange in {'KQ', 'KOSDAQ'} else '.KS'
                if name:
                    ident = {'Ticker': f'{code}{suffix}', 'Name': name}
                    result[raw] = ident
                    result[code] = ident
        except Exception:
            pass
    return result


@_cache_data(ttl=86400, show_spinner=False)
def fetch_kr_names(tickers: tuple) -> dict:
    """
    Fetch Korean company names from Naver Finance mobile API.
    tickers: tuple of yfinance KR tickers (e.g. '005930.KS', '293490.KQ')
    Returns dict mapping yf_ticker -> Korean name.
    Cached 24h since names rarely change.
    """
    identities = fetch_kr_identities(tickers)
    result = {}
    for ticker in tickers:
        raw = str(ticker or '').strip()
        ident = identities.get(raw) or identities.get(_kr_code(raw))
        if ident and ident.get('Name'):
            result[raw] = ident['Name']
    return result
