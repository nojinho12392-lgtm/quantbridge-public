"""Read-only review report for Company Quality Core scores."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

import pandas as pd

from pipeline.scoring.company_quality import (
    add_company_quality_review_columns,
    compute_company_quality_scores,
    quality_category,
)


REPORT_COLUMNS = [
    "Market", "Investability_Rank", "Business_Quality_Rank", "Original_Rank",
    "Quality_Rank_Delta", "Ticker", "Name", "Sector", "Quality_Category",
    "Business_Quality_Score", "Investability_Score", "Profitability_Quality",
    "Cash_Quality", "Growth_Quality", "BalanceSheet_Strength",
    "Valuation_Discipline", "Timing_Overlay", "Persistence_Quality",
    "Quality_Data_Confidence", "Quality_Red_Flags", "Total_Score",
    "Final_Score", "Combined_Score",
]


@dataclass(frozen=True)
class QualityReportConfig:
    limit: int = 25
    min_confidence: float = 0.45
    category: str | None = None


def _normalise_columns(df: pd.DataFrame) -> pd.DataFrame:
    rename = {
        "ticker": "Ticker",
        "symbol": "Ticker",
        "name": "Name",
        "company": "Name",
        "market": "Market",
        "sector": "Sector",
        "rank": "Rank",
        "score": "Total_Score",
        "total_score": "Total_Score",
        "final_score": "Final_Score",
        "combined_score": "Combined_Score",
    }
    out = df.copy()
    out = out.rename(columns={col: rename[col] for col in out.columns if col in rename})
    return out


def _coerce_numeric(df: pd.DataFrame, columns: Iterable[str]) -> pd.DataFrame:
    out = df.copy()
    for col in columns:
        if col in out.columns:
            out[col] = pd.to_numeric(out[col], errors="coerce")
    return out


def build_company_quality_report(
    df: pd.DataFrame,
    *,
    market: str | None = None,
    config: QualityReportConfig | None = None,
) -> pd.DataFrame:
    cfg = config or QualityReportConfig()
    frame = _normalise_columns(df)
    if frame.empty:
        return pd.DataFrame(columns=REPORT_COLUMNS)

    if market and "Market" not in frame.columns:
        frame["Market"] = market.upper()
    elif "Market" in frame.columns:
        frame["Market"] = frame["Market"].fillna(market or "").astype(str).str.upper()

    if "Ticker" not in frame.columns:
        frame["Ticker"] = ""
    if "Name" not in frame.columns:
        frame["Name"] = frame["Ticker"]
    if "Sector" not in frame.columns:
        frame["Sector"] = ""

    required_scores = [
        "Business_Quality_Score",
        "Investability_Score",
        "Quality_Data_Confidence",
    ]
    if any(col not in frame.columns for col in required_scores):
        frame = compute_company_quality_scores(frame)
    elif "Quality_Red_Flags" not in frame.columns:
        frame["Quality_Red_Flags"] = ""

    numeric_cols = [
        "Rank",
        "Investability_Rank",
        "Business_Quality_Rank",
        "Quality_Rank_Delta",
        "Business_Quality_Score",
        "Investability_Score",
        "Profitability_Quality",
        "Cash_Quality",
        "Growth_Quality",
        "BalanceSheet_Strength",
        "Valuation_Discipline",
        "Timing_Overlay",
        "Persistence_Quality",
        "Quality_Data_Confidence",
        "Total_Score",
        "Final_Score",
        "Combined_Score",
    ]
    frame = _coerce_numeric(frame, numeric_cols)

    frame["Original_Rank"] = frame["Rank"] if "Rank" in frame.columns else pd.NA
    frame = add_company_quality_review_columns(
        frame,
        rank_col="Original_Rank",
        min_confidence=cfg.min_confidence,
    )
    frame = frame.sort_values(
        ["Investability_Rank", "Business_Quality_Rank"],
        ascending=[True, True],
        na_position="last",
    ).reset_index(drop=True)

    if cfg.category:
        wanted = cfg.category.strip().lower()
        frame = frame[frame["Quality_Category"].str.lower() == wanted]

    for col in REPORT_COLUMNS:
        if col not in frame.columns:
            frame[col] = pd.NA
    out = frame[REPORT_COLUMNS].copy()
    if cfg.limit > 0:
        out = out.head(cfg.limit)
    return out.reset_index(drop=True)
