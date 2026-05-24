"""KRX market-data helpers for Korean backtests."""

from __future__ import annotations

import time

import pandas as pd


def kr_code(ticker: str) -> str:
    return str(ticker).split(".")[0].strip().zfill(6)


def _pick_column(df: pd.DataFrame, candidates: list[str]) -> str | None:
    for col in candidates:
        if col in df.columns:
            return col
    return None


def load_krx_liquidity_history(
    tickers: list[str],
    start: pd.Timestamp,
    end: pd.Timestamp,
    *,
    delay: float = 0.08,
    provider=None,
) -> dict[str, pd.DataFrame]:
    """Load daily KRX market cap/trading value for tickers.

    Returns ``{ticker: DataFrame}`` with optional ``MarketCap_PIT`` and
    ``TradingValue`` columns. Empty dict on pykrx/provider failure.
    """
    if provider is None:
        try:
            from pykrx import stock as provider
        except Exception:
            return {}

    start_s = pd.Timestamp(start).strftime("%Y%m%d")
    end_s = pd.Timestamp(end).strftime("%Y%m%d")
    out: dict[str, pd.DataFrame] = {}

    for ticker in tickers:
        try:
            raw = provider.get_market_cap_by_date(start_s, end_s, kr_code(ticker))
            if raw is None or raw.empty:
                continue
            frame = raw.copy()
            frame.index = pd.to_datetime(frame.index)
            cap_col = _pick_column(frame, ["시가총액", "MarketCap", "market_cap"])
            value_col = _pick_column(frame, ["거래대금", "TradingValue", "trading_value"])
            volume_col = _pick_column(frame, ["거래량", "Volume", "volume"])
            cols = {}
            if cap_col:
                cols["MarketCap_PIT"] = pd.to_numeric(frame[cap_col], errors="coerce")
            if value_col:
                cols["TradingValue"] = pd.to_numeric(frame[value_col], errors="coerce")
            if volume_col:
                cols["Volume"] = pd.to_numeric(frame[volume_col], errors="coerce")
            if cols:
                out[ticker] = pd.DataFrame(cols).sort_index()
        except Exception:
            continue
        time.sleep(delay)
    return out


def asof_krx_liquidity(liquidity: dict[str, pd.DataFrame], date: pd.Timestamp, *, window: int = 20) -> pd.DataFrame:
    rows = []
    for ticker, frame in liquidity.items():
        if frame.empty:
            continue
        hist = frame.loc[:pd.Timestamp(date)].tail(window)
        if hist.empty:
            continue
        row = {"Ticker": ticker}
        if "MarketCap_PIT" in hist.columns:
            row["MarketCap_PIT"] = hist["MarketCap_PIT"].dropna().iloc[-1] if hist["MarketCap_PIT"].notna().any() else pd.NA
        if "TradingValue" in hist.columns:
            row["TradingValue_20D"] = hist["TradingValue"].mean()
        if "Volume" in hist.columns:
            row["Volume_20D"] = hist["Volume"].mean()
        rows.append(row)
    if not rows:
        return pd.DataFrame()
    return pd.DataFrame(rows).set_index("Ticker")
