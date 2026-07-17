"""Storage/DataFrame helpers shared by the FastAPI composition root.

Extracted from ``api/server.py`` so payload builders and tests can reuse pure
frame-normalization logic without importing the full app module.
"""

from __future__ import annotations

import math
from typing import Callable, Iterable

import pandas as pd

PORTFOLIO_NUMERIC_COLS = [
    "Weight(%)", "Current_Price", "Return_1M", "Mom_1M", "Total_Score", "ROIC",
    "RevGrowth", "GrossMargin", "MarketCap", "Expected_Return", "Rank",
    "Previous_Rank", "Rank_Change",
]

ROW_MARKET_STORAGE_DATASETS = {
    "Signal_Quality_Gates",
    "Factor_Weight_Policy",
    "Factor_Policy_Backtest",
    "Factor_Remediation_Plan",
    "Factor_IC_Report",
    "Factor_IC_Detail",
    "Factor_Score_Snapshots",
    "Policy_Adjusted_Ranking_Summary",
}

GLOBAL_STORAGE_DATASETS = {
    "Macro_Regime",
}


def clean_json_value(val):
    """Convert NaN/None to Python None for valid JSON serialization."""
    if val is None:
        return None
    if isinstance(val, float) and math.isnan(val):
        return None
    return val


def clean_dataframe_columns(df: pd.DataFrame) -> pd.DataFrame:
    """Drop blank/duplicate columns from oversized Sheets/storage ranges."""
    if df is None or df.empty:
        return df if isinstance(df, pd.DataFrame) else pd.DataFrame()
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


def sheet_values_to_df(rows, header) -> pd.DataFrame:
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
    data = [[row[idx] if idx < len(row) else "" for idx, _ in keep] for row in rows]
    return pd.DataFrame(data, columns=[col for _, col in keep])


def coerce_numeric(df: pd.DataFrame, cols: Iterable[str]) -> pd.DataFrame:
    for col in cols:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    return df


def infer_storage_market(sheet_name: str) -> str | None:
    if sheet_name.startswith("KR_"):
        return "KR"
    if sheet_name.startswith("US_"):
        return "US"
    if sheet_name in ROW_MARKET_STORAGE_DATASETS:
        return None
    if sheet_name in GLOBAL_STORAGE_DATASETS:
        return "GLOBAL"
    return None


def normalize_portfolio_price_columns(df: pd.DataFrame) -> pd.DataFrame:
    if df is None or df.empty:
        return df if isinstance(df, pd.DataFrame) else pd.DataFrame()

    out = df.copy()
    aliases = {
        "Current_Price": ["Current_Price", "Price", "Last_Price", "Close", "End_Price"],
        "Return_1M": ["Return_1M", "1M_Return", "Return_1m", "One_Month_Return", "Mom_1M"],
    }
    for target, candidates in aliases.items():
        merged = None
        for candidate in candidates:
            if candidate not in out.columns:
                continue
            series = out[candidate]
            merged = series.copy() if merged is None else merged.where(pd.notna(merged), series)
        out[target] = merged if merged is not None else None
    return out


def clean_meta_value(value) -> str | None:
    if value is None:
        return None
    if isinstance(value, float) and math.isnan(value):
        return None
    text = str(value).strip()
    if not text or text.lower() in {"nan", "nat", "none", "null"}:
        return None
    return text


def df_to_records(
    df: pd.DataFrame,
    *,
    localize: Callable[[dict], dict] | None = None,
) -> list[dict]:
    if df is None or df.empty:
        return []
    frame = df.where(df.notna(), None)
    records = []
    for row in frame.to_dict("records"):
        cleaned = {k: clean_json_value(v) for k, v in row.items()}
        records.append(localize(cleaned) if localize else cleaned)
    return records


def load_storage_df(
    repository,
    sheet_name: str,
    *,
    market: str | None = None,
    record_data_source: Callable[..., None] | None = None,
) -> pd.DataFrame:
    """Return latest storage snapshot, or an empty frame when not bootstrapped yet."""
    try:
        df = repository.read_dataframe(sheet_name, market=market)
    except Exception as exc:
        if record_data_source is not None:
            record_data_source(
                sheet_name,
                "storage_error",
                market=market,
                detail=f"{type(exc).__name__}: {exc}",
            )
        return pd.DataFrame()
    if df is None or df.empty:
        if record_data_source is not None:
            record_data_source(sheet_name, "storage_empty", market=market, rows=0)
        return pd.DataFrame() if df is None else df
    df = clean_dataframe_columns(df)
    if "Ticker" in df.columns:
        df = df[df["Ticker"].astype(str).str.strip() != ""]
    if "Rank" in df.columns:
        rank = pd.to_numeric(df["Rank"], errors="coerce")
        df = df[rank.notna()].copy()
        df["Rank"] = rank[rank.notna()]
    if record_data_source is not None:
        record_data_source(sheet_name, "storage", market=market, rows=len(df))
    return df.reset_index(drop=True)
