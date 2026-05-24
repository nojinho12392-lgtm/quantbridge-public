from __future__ import annotations

from collections.abc import Iterable
from typing import TypeVar

import pandas as pd


BANNED_TICKERS: frozenset[str] = frozenset({"FOXA"})

_T = TypeVar("_T")


def normalize_ticker(value: object) -> str:
    return str(value or "").strip().upper()


def is_banned_ticker(value: object) -> bool:
    return normalize_ticker(value) in BANNED_TICKERS


def filter_banned_tickers(tickers: Iterable[_T]) -> list[_T]:
    return [ticker for ticker in tickers if not is_banned_ticker(ticker)]


def banned_tickers_label() -> str:
    return ", ".join(sorted(BANNED_TICKERS))


def drop_banned_ticker_rows(df: pd.DataFrame, ticker_col: str = "Ticker") -> pd.DataFrame:
    if df is None or df.empty or ticker_col not in df.columns:
        return df
    banned = df[ticker_col].map(is_banned_ticker)
    if not banned.any():
        return df
    return df.loc[~banned].copy()
