"""Local US universe builder backed by free SEC and Yahoo Finance data."""

from __future__ import annotations

import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

import pandas as pd
import yfinance as yf

from pipeline.data.sec_companyfacts_lake import DEFAULT_OUTPUT_DIR, read_latest_pit_fundamentals
from quantbridge.schemas import UNIVERSE_COLS


DEFAULT_US_TICKERS = [
    "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "AVGO", "TSLA", "JPM", "V",
    "MA", "WMT", "LLY", "ORCL", "XOM", "COST", "HD", "PG", "NFLX", "ABBV",
    "CRM", "KO", "BAC", "CVX", "AMD", "GE", "CSCO", "IBM", "LIN", "MRK",
    "MCD", "ADBE", "PEP", "TMO", "DIS", "INTU", "QCOM", "TXN", "AMAT", "NOW",
    "ISRG", "UBER", "CAT", "GS", "HON", "RTX", "BKNG", "LOW", "SPGI", "UNP",
    "NEE", "AMGN", "DHR", "MU", "LRCX", "PANW", "ADI", "GILD", "DE", "PLTR",
    "CELH", "ELF", "DUOL", "TMDX", "CRDO", "IOT", "FROG", "BILL", "DOCN", "INSP",
    "ALGM", "GTLS", "FOUR", "RXRX", "RUN", "ENPH", "ROKU", "UPST", "HIMS", "MDB",
]


@dataclass(frozen=True)
class LocalUsUniverseConfig:
    limit: int = 80
    delay: float = 0.05
    output_dir: Path = DEFAULT_OUTPUT_DIR


def normalize_us_ticker(value: object) -> str:
    return str(value or "").strip().upper()


def _num(value) -> float | None:
    try:
        if value in ("", None):
            return None
        out = float(value)
        return out if pd.notna(out) else None
    except Exception:
        return None


def _latest_sec_by_ticker(pit_df: pd.DataFrame) -> pd.DataFrame:
    if pit_df.empty or "Ticker" not in pit_df.columns:
        return pd.DataFrame()
    df = pit_df.copy()
    df["Ticker"] = df["Ticker"].map(normalize_us_ticker)
    df["Filing_Date"] = pd.to_datetime(df.get("Filing_Date"), errors="coerce")
    df = df[df["Ticker"].ne("")]
    if df.empty:
        return pd.DataFrame()
    return df.sort_values(["Ticker", "Filing_Date"]).drop_duplicates("Ticker", keep="last").set_index("Ticker")


def fetch_yfinance_snapshot(ticker: str) -> dict:
    """Fetch a compact current US company snapshot from Yahoo Finance."""
    symbol = normalize_us_ticker(ticker)
    if not symbol:
        return {}
    info = yf.Ticker(symbol).get_info() or {}
    return {
        "Ticker": symbol,
        "Name": info.get("longName") or info.get("shortName") or symbol,
        "Sector": info.get("sector") or "",
        "MarketCap": info.get("marketCap"),
        "PER": info.get("trailingPE"),
        "PBR": info.get("priceToBook"),
        "ROE": info.get("returnOnEquity"),
        "Revenue": info.get("totalRevenue"),
        "RevenueGrowth": info.get("revenueGrowth"),
        "OperatingMargin": info.get("operatingMargins"),
        "GrossMargin": info.get("grossMargins"),
        "DebtToEquity": info.get("debtToEquity"),
        "PEG": info.get("trailingPegRatio") or info.get("pegRatio"),
        "EV_EBITDA": info.get("enterpriseToEbitda"),
        "DivYield": info.get("dividendYield"),
    }


def build_local_us_universe(
    tickers: Iterable[str] | None = None,
    *,
    config: LocalUsUniverseConfig | None = None,
    pit_df: pd.DataFrame | None = None,
    info_fetcher: Callable[[str], dict] | None = fetch_yfinance_snapshot,
) -> pd.DataFrame:
    """Build a ``US_Universe`` frame without Google Sheets."""
    cfg = config or LocalUsUniverseConfig()
    symbols = [normalize_us_ticker(t) for t in (tickers or DEFAULT_US_TICKERS)]
    symbols = [ticker for ticker in dict.fromkeys(symbols) if ticker]
    if cfg.limit:
        symbols = symbols[: cfg.limit]

    pit = read_latest_pit_fundamentals(output_dir=cfg.output_dir) if pit_df is None else pit_df
    latest = _latest_sec_by_ticker(pit)

    rows = []
    for ticker in symbols:
        current = {}
        if info_fetcher is not None:
            try:
                current = info_fetcher(ticker) or {}
            except Exception:
                current = {}
            if cfg.delay > 0:
                time.sleep(cfg.delay)
        sec_row = latest.loc[ticker].to_dict() if ticker in latest.index else {}

        def first(*values):
            for value in values:
                num = _num(value)
                if num is not None:
                    return num
            return None

        rows.append({
            "Ticker": ticker,
            "Name": current.get("Name") or ticker,
            "Market": "US",
            "Sector": current.get("Sector") or "",
            "MarketCap": first(current.get("MarketCap")),
            "PER": first(current.get("PER")),
            "PBR": first(current.get("PBR")),
            "ROE": first(current.get("ROE"), sec_row.get("ROE")),
            "Revenue": first(current.get("Revenue"), sec_row.get("Revenue")),
            "RevenueGrowth": first(current.get("RevenueGrowth"), sec_row.get("RevGrowth")),
            "OperatingMargin": first(current.get("OperatingMargin"), sec_row.get("OperatingMargin")),
            "GrossMargin": first(current.get("GrossMargin"), sec_row.get("GrossMargin")),
            "DebtToEquity": first(current.get("DebtToEquity"), sec_row.get("DebtToEquity")),
            "PEG": first(current.get("PEG")),
            "EV_EBITDA": first(current.get("EV_EBITDA")),
            "DivYield": first(current.get("DivYield")),
            "Last_Updated": pd.Timestamp.now().strftime("%Y-%m-%d"),
        })

    df = pd.DataFrame(rows)
    for col in UNIVERSE_COLS:
        if col not in df.columns:
            df[col] = ""
    extras = [col for col in ["PEG", "EV_EBITDA", "DivYield"] if col in df.columns]
    return df[UNIVERSE_COLS + extras].astype(object).where(pd.notna(df[UNIVERSE_COLS + extras]), "")
