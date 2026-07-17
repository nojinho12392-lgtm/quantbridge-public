"""Shared Korean factor scoring logic for live scoring and PIT backtests."""

from __future__ import annotations

import numpy as np
import pandas as pd


DEFAULT_WEIGHTS = (0.40, 0.35, 0.25)


def _num(df: pd.DataFrame, name: str) -> pd.Series:
    if name not in df.columns:
        return pd.Series(np.nan, index=df.index, dtype="float64")
    return pd.to_numeric(df[name], errors="coerce")


def _rank(
    series: pd.Series,
    *,
    inverse: bool = False,
    fallback: float = 0.5,
    positive_only: bool = False,
) -> pd.Series:
    raw = pd.to_numeric(series, errors="coerce")
    observed = raw.notna()
    s = raw.where(raw > 0) if positive_only else raw
    if inverse:
        s = (1.0 / s).where(s > 0)
    if s.notna().sum() == 0:
        ranked = pd.Series(fallback, index=series.index, dtype="float64")
    else:
        ranked = s.rank(pct=True, na_option="keep").fillna(fallback).clip(0.0, 1.0)
    if positive_only:
        ranked = ranked.where(raw.gt(0) | ~observed, 0.0)
    return ranked.clip(0.0, 1.0)


def compute_kr_altman_z(df: pd.DataFrame) -> pd.Series:
    assets = _num(df, "TotalAssets")
    current_assets = _num(df, "CurrentAssets")
    current_liabilities = _num(df, "CurrentLiabilities")
    retained_earnings = _num(df, "RetainedEarnings")
    total_liabilities = _num(df, "TotalLiabilities")
    market_cap = _num(df, "MarketCap")
    revenue = _num(df, "Revenue")
    op_margin = _num(df, "OperatingMargin")
    ebit = op_margin * revenue

    z = (
        1.2 * ((current_assets - current_liabilities) / assets.replace(0, np.nan))
        + 1.4 * (retained_earnings / assets.replace(0, np.nan))
        + 3.3 * (ebit / assets.replace(0, np.nan))
        + 0.6 * (market_cap / total_liabilities.replace(0, np.nan))
        + 1.0 * (revenue / assets.replace(0, np.nan))
    )
    return z.replace([np.inf, -np.inf], np.nan)


def compute_kr_factor_scores(
    features: pd.DataFrame,
    weights: tuple[float, float, float] = DEFAULT_WEIGHTS,
    *,
    mom_series: pd.Series | None = None,
) -> pd.DataFrame:
    """Compute the KR value/quality/momentum score from PIT-safe features."""
    df = features.copy()
    w_v, w_q, w_m = weights

    r_per = _rank(_num(df, "PER"), inverse=True, positive_only=True)
    r_pbr = _rank(_num(df, "PBR"), inverse=True, positive_only=True)
    r_revgrowth = _rank(_num(df, "RevGrowth"))
    r_divyield = _rank(_num(df, "DivYield").where(_num(df, "DivYield") > 0))
    value_raw = 0.40 * r_per + 0.25 * r_pbr + 0.25 * r_revgrowth + 0.10 * r_divyield

    altman = _num(df, "AltmanZ")
    if altman.notna().sum() == 0:
        altman = compute_kr_altman_z(df)

    accruals = _num(df, "Accruals_EQ")
    if accruals.notna().sum() == 0:
        accruals = _num(df, "FCF_Margin") / _num(df, "OperatingMargin").replace(0, np.nan)
    accruals = accruals.replace([np.inf, -np.inf], np.nan)

    quality_raw = (
        0.30 * _rank(_num(df, "ROE"))
        + 0.25 * _rank(_num(df, "OperatingMargin"))
        + 0.20 * _rank(_num(df, "DebtToEquity"), inverse=True)
        + 0.15 * _rank(altman)
        + 0.10 * _rank(accruals)
    )

    if mom_series is not None and not mom_series.empty:
        df["_pit_mom_12m_1m"] = mom_series.reindex(df.index)
    mom_12m_1m = _num(df, "_pit_mom_12m_1m")
    if mom_12m_1m.notna().sum() == 0:
        mom_12m_1m = _num(df, "Mom_12M_1M")
    mom_3m = _num(df, "Mom_3M")
    dist_52w = _num(df, "Dist_52W_High")
    if dist_52w.notna().sum() > 0:
        momentum_raw = 0.50 * _rank(mom_12m_1m) + 0.30 * _rank(mom_3m) + 0.20 * _rank(dist_52w)
    else:
        momentum_raw = 0.60 * _rank(mom_12m_1m) + 0.40 * _rank(mom_3m)

    vol_63d = _num(df, "Volatility_63D")
    if vol_63d.notna().sum() > 0:
        risk_penalty = 0.05 * (1.0 - _rank(vol_63d, inverse=True))
    else:
        risk_penalty = pd.Series(0.0, index=df.index)

    df["AltmanZ"] = altman
    df["Accruals_EQ"] = accruals
    df["Value_Score"] = (w_v * value_raw).clip(0.0, 1.0)
    df["Quality_Score"] = (w_q * quality_raw).clip(0.0, 1.0)
    df["Momentum_Score"] = (w_m * momentum_raw).clip(0.0, 1.0)
    df["Risk_Penalty"] = risk_penalty
    df["Total_Score"] = (
        df["Value_Score"] + df["Quality_Score"] + df["Momentum_Score"] - df["Risk_Penalty"]
    ).clip(0.0, 1.0).round(4)
    return df
