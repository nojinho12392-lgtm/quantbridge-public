"""Offline validation helpers for quality signals.

The production IC pipeline reads Google Sheets snapshots. These helpers are
pure pandas so local CSV/parquet exports can be validated without credentials.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

import numpy as np
import pandas as pd


QUALITY_SIGNAL_COLS = [
    "Business_Quality_Score",
    "Investability_Score",
    "Persistence_Quality",
    "Quality_Persistence_Score",
]

SUMMARY_COLS = [
    "Market", "Signal", "Horizon", "Snapshots", "Mean_IC", "Median_IC",
    "Positive_IC_Rate", "Mean_Top_Bottom_Spread", "Mean_Hit_Rate",
    "Total_Observations", "Verdict",
]

DETAIL_COLS = [
    "Snapshot_Date", "Market", "Signal", "Horizon", "IC", "N",
    "Top_Return", "Bottom_Return", "Top_Bottom_Spread", "Hit_Rate",
    "Forward_Start", "Forward_End",
]


@dataclass(frozen=True)
class QualityValidationConfig:
    horizons: dict[str, int] | None = None
    min_obs: int = 20
    top_quantile: float = 0.80
    bottom_quantile: float = 0.20

    def horizon_map(self) -> dict[str, int]:
        return self.horizons or {"1M": 21, "3M": 63, "6M": 126}


def _price_on_or_after(prices: pd.DataFrame, date_like) -> tuple[pd.Series, str]:
    if prices.empty:
        return pd.Series(dtype=float), ""
    ts = pd.Timestamp(date_like).normalize()
    idx = prices.index[pd.to_datetime(prices.index).normalize() >= ts]
    if idx.empty:
        return pd.Series(dtype=float), ""
    row = prices.loc[idx[0]]
    return pd.to_numeric(row, errors="coerce"), pd.Timestamp(idx[0]).strftime("%Y-%m-%d")


def forward_returns(
    prices: pd.DataFrame,
    snapshot_date,
    horizon_days: int,
) -> tuple[pd.Series, str, str]:
    start_px, start_date = _price_on_or_after(prices, snapshot_date)
    end_px, end_date = _price_on_or_after(
        prices,
        pd.Timestamp(snapshot_date) + pd.Timedelta(days=horizon_days),
    )
    if start_px.empty or end_px.empty:
        return pd.Series(dtype=float), "", ""
    returns = (end_px / start_px) - 1.0
    returns = returns.replace([np.inf, -np.inf], np.nan).dropna()
    return returns, start_date, end_date


def spearman_ic(scores: pd.Series, returns: pd.Series, *, min_obs: int = 20) -> tuple[float, int]:
    aligned = pd.concat([scores.rename("score"), returns.rename("return")], axis=1).dropna()
    if len(aligned) < min_obs or aligned["score"].nunique() < 2 or aligned["return"].nunique() < 2:
        return float("nan"), len(aligned)
    return float(aligned["score"].corr(aligned["return"], method="spearman")), len(aligned)


def top_bottom_stats(
    scores: pd.Series,
    returns: pd.Series,
    *,
    min_obs: int = 20,
    top_quantile: float = 0.80,
    bottom_quantile: float = 0.20,
) -> tuple[float, float, float, float]:
    aligned = pd.concat([scores.rename("score"), returns.rename("return")], axis=1).dropna()
    if len(aligned) < min_obs or aligned["score"].nunique() < 5:
        return float("nan"), float("nan"), float("nan"), float("nan")
    top_cut = aligned["score"].quantile(top_quantile)
    bottom_cut = aligned["score"].quantile(bottom_quantile)
    top = aligned[aligned["score"] >= top_cut]["return"]
    bottom = aligned[aligned["score"] <= bottom_cut]["return"]
    if top.empty or bottom.empty:
        return float("nan"), float("nan"), float("nan"), float("nan")
    top_ret = float(top.mean())
    bottom_ret = float(bottom.mean())
    return top_ret, bottom_ret, float(top_ret - bottom_ret), float((top > 0).mean())


def _verdict(mean_ic: float, positive_rate: float, spread: float) -> str:
    if pd.isna(mean_ic):
        return "INSUFFICIENT"
    if mean_ic >= 0.05 and positive_rate >= 0.60 and spread > 0:
        return "STRONG"
    if mean_ic >= 0.02 and positive_rate >= 0.50:
        return "PROMISING"
    if mean_ic > 0:
        return "WEAK"
    return "NEGATIVE"


def validate_quality_signals(
    snapshots: pd.DataFrame,
    prices: pd.DataFrame,
    *,
    signal_cols: Iterable[str] = QUALITY_SIGNAL_COLS,
    config: QualityValidationConfig | None = None,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Evaluate quality signal rank IC and top-bottom spread."""
    cfg = config or QualityValidationConfig()
    if snapshots.empty or prices.empty:
        return pd.DataFrame(columns=SUMMARY_COLS), pd.DataFrame(columns=DETAIL_COLS)

    snaps = snapshots.copy()
    if "Market" not in snaps.columns:
        snaps["Market"] = "US"
    snaps["Snapshot_Date"] = pd.to_datetime(snaps["Snapshot_Date"], errors="coerce")
    snaps["Ticker"] = snaps["Ticker"].fillna("").astype(str).str.strip()
    snaps = snaps.dropna(subset=["Snapshot_Date"])
    snaps = snaps[snaps["Ticker"] != ""].copy()

    price_frame = prices.copy()
    price_frame.index = pd.to_datetime(price_frame.index, errors="coerce")
    price_frame = price_frame[~price_frame.index.isna()].sort_index()

    detail_rows: list[dict] = []
    for (market, snap_date), group in snaps.groupby(["Market", "Snapshot_Date"]):
        score_frame = group.set_index("Ticker")
        for horizon_label, horizon_days in cfg.horizon_map().items():
            fwd, fwd_start, fwd_end = forward_returns(price_frame, snap_date, horizon_days)
            if fwd.empty:
                continue
            for signal in signal_cols:
                if signal not in score_frame.columns:
                    continue
                scores = pd.to_numeric(score_frame[signal], errors="coerce")
                ic, n = spearman_ic(scores, fwd, min_obs=cfg.min_obs)
                if n < cfg.min_obs:
                    continue
                top_ret, bottom_ret, spread, hit_rate = top_bottom_stats(
                    scores,
                    fwd,
                    min_obs=cfg.min_obs,
                    top_quantile=cfg.top_quantile,
                    bottom_quantile=cfg.bottom_quantile,
                )
                detail_rows.append({
                    "Snapshot_Date": snap_date.strftime("%Y-%m-%d"),
                    "Market": market,
                    "Signal": signal,
                    "Horizon": horizon_label,
                    "IC": ic,
                    "N": n,
                    "Top_Return": top_ret,
                    "Bottom_Return": bottom_ret,
                    "Top_Bottom_Spread": spread,
                    "Hit_Rate": hit_rate,
                    "Forward_Start": fwd_start,
                    "Forward_End": fwd_end,
                })

    detail = pd.DataFrame(detail_rows)
    if detail.empty:
        return pd.DataFrame(columns=SUMMARY_COLS), pd.DataFrame(columns=DETAIL_COLS)

    summary_rows: list[dict] = []
    for (market, signal, horizon), group in detail.groupby(["Market", "Signal", "Horizon"]):
        valid_ic = pd.to_numeric(group["IC"], errors="coerce").dropna()
        mean_ic = float(valid_ic.mean()) if len(valid_ic) else float("nan")
        positive_rate = float((valid_ic > 0).mean()) if len(valid_ic) else float("nan")
        spread = float(pd.to_numeric(group["Top_Bottom_Spread"], errors="coerce").mean())
        summary_rows.append({
            "Market": market,
            "Signal": signal,
            "Horizon": horizon,
            "Snapshots": int(group["Snapshot_Date"].nunique()),
            "Mean_IC": mean_ic,
            "Median_IC": float(valid_ic.median()) if len(valid_ic) else float("nan"),
            "Positive_IC_Rate": positive_rate,
            "Mean_Top_Bottom_Spread": spread,
            "Mean_Hit_Rate": float(pd.to_numeric(group["Hit_Rate"], errors="coerce").mean()),
            "Total_Observations": int(group["N"].sum()),
            "Verdict": _verdict(mean_ic, positive_rate, spread),
        })
    summary = pd.DataFrame(summary_rows).sort_values(
        ["Market", "Horizon", "Mean_IC"],
        ascending=[True, True, False],
    )
    return summary[SUMMARY_COLS].reset_index(drop=True), detail[DETAIL_COLS].reset_index(drop=True)
