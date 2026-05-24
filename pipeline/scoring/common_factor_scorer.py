"""Shared factor scoring logic for live scoring and point-in-time backtests.

The scorer accepts a best-effort feature frame. Live runs usually have richer
valuation inputs, while EDGAR backtests may only have accounting ratios. Missing
inputs are treated as neutral ranks so both paths can call the same function
without inventing future data.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

import numpy as np
import pandas as pd


DEFAULT_WEIGHTS = (0.40, 0.30, 0.30)


@dataclass(frozen=True)
class ScoreDiagnostics:
    value_coverage: dict[str, int]
    quality_coverage: dict[str, int]
    momentum_coverage: dict[str, int]
    eps_coverage: float
    analyst_coverage: float


def _first_existing(df: pd.DataFrame, names: Iterable[str]) -> str | None:
    for name in names:
        if name in df.columns:
            return name
    return None


def _num(df: pd.DataFrame, names: Iterable[str], default=np.nan) -> pd.Series:
    col = _first_existing(df, names)
    if col is None:
        return pd.Series(default, index=df.index, dtype="float64")
    return pd.to_numeric(df[col], errors="coerce")


def _rank(series: pd.Series, *, higher_is_better: bool = True, fallback: float = 0.5) -> pd.Series:
    s = pd.to_numeric(series, errors="coerce")
    if not higher_is_better:
        s = (1.0 / s).where(s > 0)
    if s.notna().sum() == 0:
        return pd.Series(fallback, index=series.index, dtype="float64")
    return s.rank(pct=True, na_option="keep").fillna(fallback).clip(0.0, 1.0)


def _rank_first_available(
    df: pd.DataFrame,
    candidates: list[tuple[list[str], bool]],
    *,
    fallback: float = 0.5,
) -> tuple[pd.Series, str | None, int]:
    """Rank the first candidate with at least one non-null value."""
    for names, higher_is_better in candidates:
        s = _num(df, names)
        coverage = int(s.notna().sum())
        if coverage > 0:
            label = _first_existing(df, names)
            return _rank(s, higher_is_better=higher_is_better, fallback=fallback), label, coverage
    return pd.Series(fallback, index=df.index, dtype="float64"), None, 0


def compute_altman_z(df: pd.DataFrame) -> pd.Series:
    """Compute Altman Z when the required balance-sheet fields exist."""
    total_assets = _num(df, ["TotalAssets", "total_assets", "assets"])
    current_assets = _num(df, ["CurrentAssets", "current_assets"])
    current_liabilities = _num(df, ["CurrentLiabilities", "current_liabilities"])
    retained_earnings = _num(df, ["RetainedEarnings", "retained_earnings"])
    total_liabilities = _num(df, ["TotalLiabilities", "total_liabilities", "liabilities"])
    market_cap = _num(df, ["MarketCap", "market_cap"])
    revenue = _num(df, ["Revenue", "revenue"])
    op_margin = _num(df, ["OperatingMargin", "op_margin"])

    ebit = op_margin * revenue
    z = (
        1.2 * ((current_assets - current_liabilities) / total_assets.replace(0, np.nan))
        + 1.4 * (retained_earnings / total_assets.replace(0, np.nan))
        + 3.3 * (ebit / total_assets.replace(0, np.nan))
        + 0.6 * (market_cap / total_liabilities.replace(0, np.nan))
        + 1.0 * (revenue / total_assets.replace(0, np.nan))
    )
    return z.replace([np.inf, -np.inf], np.nan)


def sector_neutralize(df: pd.DataFrame, score_col: str = "Total_Score") -> pd.DataFrame:
    """Z-score normalize a score within sectors, keeping tiny sectors unchanged."""
    out = df.copy()
    out["Score_Neutral"] = out[score_col]
    if "Sector" not in out.columns:
        return out

    for _, group in out.groupby("Sector"):
        if len(group) < 5:
            continue
        std = group[score_col].std()
        if std and std > 0:
            out.loc[group.index, "Score_Neutral"] = (group[score_col] - group[score_col].mean()) / std
    return out


def compute_us_factor_scores(
    features: pd.DataFrame,
    weights: tuple[float, float, float] = DEFAULT_WEIGHTS,
    *,
    mom_series: pd.Series | None = None,
    include_diagnostics: bool = False,
) -> pd.DataFrame | tuple[pd.DataFrame, ScoreDiagnostics]:
    """Compute shared US value/quality/momentum scores.

    Component score columns are weighted contributions. Their sum is
    ``Total_Score``. This preserves the live scorer's historical output shape
    while letting the backtest call the exact same function.
    """
    df = features.copy()
    w_v, w_q, w_m = weights

    # Value: prefer live valuation fields; fall back to PIT accounting proxies.
    r_peg, peg_src, peg_cov = _rank_first_available(
        df,
        [
            (["PEG"], False),
            (["PER", "pe_ratio"], False),
            (["DebtToEquity", "de_ratio"], False),
        ],
    )
    r_ev, ev_src, ev_cov = _rank_first_available(
        df,
        [
            (["EV_EBITDA", "ev_ebitda"], False),
            (["Debt_EBITDA", "debt_ebitda"], False),
            (["OperatingMargin", "op_margin"], True),
        ],
    )
    r_revgrowth, rg_src, rg_cov = _rank_first_available(
        df,
        [
            (["RevGrowth", "rev_growth", "RevenueGrowth"], True),
        ],
    )
    r_divyield, div_src, div_cov = _rank_first_available(
        df,
        [
            (["DivYield", "div_yield", "dividend_yield"], True),
        ],
    )

    value_raw = 0.35 * r_peg + 0.25 * r_ev + 0.25 * r_revgrowth + 0.15 * r_divyield

    # Quality: use the live 6-signal formula, with EDGAR-friendly aliases.
    r_roic, roic_src, roic_cov = _rank_first_available(df, [(["ROIC", "roic"], True)])
    r_fcfni, fcfni_src, fcfni_cov = _rank_first_available(
        df,
        [
            (["FCF_NI", "fcf_ni"], True),
            (["FCF_Margin", "fcf_margin"], True),
        ],
    )
    r_roe, roe_src, roe_cov = _rank_first_available(df, [(["ROE", "roe"], True)])
    r_ic, ic_src, ic_cov = _rank_first_available(
        df,
        [
            (["InterestCoverage", "int_cov", "interest_coverage"], True),
        ],
    )

    altman = _num(df, ["AltmanZ", "altman_z"])
    if altman.notna().sum() == 0:
        altman = compute_altman_z(df)
    r_altman = _rank(altman)
    altman_cov = int(altman.notna().sum())

    fcf_margin = _num(df, ["FCF_Margin", "fcf_margin"])
    op_margin = _num(df, ["OperatingMargin", "op_margin"])
    accruals = _num(df, ["Accruals_EQ", "accruals_eq"])
    if accruals.notna().sum() == 0:
        accruals = fcf_margin / op_margin.replace(0, np.nan)
    accruals = accruals.replace([np.inf, -np.inf], np.nan)
    r_accruals = _rank(accruals)
    accruals_cov = int(accruals.notna().sum())

    quality_raw = (
        0.25 * r_roic
        + 0.25 * r_fcfni
        + 0.20 * r_roe
        + 0.10 * r_ic
        + 0.10 * r_altman
        + 0.10 * r_accruals
    )

    # Momentum: 12M-1M and 3M price momentum, with EPS and analyst revision when
    # their coverage is broad enough for a cross-sectional rank.
    if mom_series is not None and not mom_series.empty:
        df["_common_mom_12m_1m"] = mom_series.reindex(df.index)

    mom_12m_1m = _num(df, ["Mom_12M_1M", "mom_12m_1m", "_common_mom_12m_1m"])
    if mom_12m_1m.notna().sum() == 0:
        mom_12m = _num(df, ["Mom_12M", "mom_12m"])
        mom_1m = _num(df, ["Mom_1M", "mom_1m"])
        mom_12m_1m = mom_12m - mom_1m
    r_mom12 = _rank(mom_12m_1m)

    mom_3m = _num(df, ["Mom_3M", "mom_3m"])
    r_mom3 = _rank(mom_3m)

    eps_growth = _num(df, ["EPS_Growth", "eps_growth"])
    eps_coverage = float(eps_growth.notna().mean()) if len(df) else 0.0
    r_eps = _rank(eps_growth)

    analyst_rank = _num(df, ["r_analyst", "AnalystRank", "analyst_rank"])
    if analyst_rank.notna().sum() == 0:
        analyst_rating = _num(df, ["AnalystRating", "analyst_rating"])
        analyst_rank = _rank(analyst_rating.replace(0, np.nan), higher_is_better=False)
        analyst_cov_n = int(analyst_rating.notna().sum())
    else:
        analyst_cov_n = int(analyst_rank.notna().sum())
        analyst_rank = analyst_rank.fillna(0.5).clip(0.0, 1.0)
    analyst_coverage = analyst_cov_n / len(df) if len(df) else 0.0

    if eps_coverage > 0.3 and analyst_coverage > 0.2:
        momentum_raw = 0.40 * r_mom12 + 0.25 * r_mom3 + 0.20 * r_eps + 0.15 * analyst_rank
    elif eps_coverage > 0.3:
        momentum_raw = 0.50 * r_mom12 + 0.30 * r_mom3 + 0.20 * r_eps
    elif analyst_coverage > 0.2:
        momentum_raw = 0.45 * r_mom12 + 0.40 * r_mom3 + 0.15 * analyst_rank
    else:
        momentum_raw = 0.50 * r_mom12 + 0.50 * r_mom3

    df["Value_Score"] = (w_v * value_raw).clip(0.0, 1.0)
    df["Quality_Score"] = (w_q * quality_raw).clip(0.0, 1.0)
    df["Momentum_Score"] = (w_m * momentum_raw).clip(0.0, 1.0)
    df["Total_Score"] = (df["Value_Score"] + df["Quality_Score"] + df["Momentum_Score"]).round(4)
    df["AltmanZ"] = altman
    df["Accruals_EQ"] = accruals
    df["Mom_12M_1M"] = mom_12m_1m

    if not include_diagnostics:
        return df

    diagnostics = ScoreDiagnostics(
        value_coverage={
            peg_src or "PEG/PER/DebtToEquity": peg_cov,
            ev_src or "EV_EBITDA/Debt_EBITDA/OperatingMargin": ev_cov,
            rg_src or "RevGrowth": rg_cov,
            div_src or "DivYield": div_cov,
        },
        quality_coverage={
            roic_src or "ROIC": roic_cov,
            fcfni_src or "FCF_NI/FCF_Margin": fcfni_cov,
            roe_src or "ROE": roe_cov,
            ic_src or "InterestCoverage": ic_cov,
            "AltmanZ": altman_cov,
            "Accruals_EQ": accruals_cov,
        },
        momentum_coverage={
            "Mom_12M_1M": int(mom_12m_1m.notna().sum()),
            "Mom_3M": int(mom_3m.notna().sum()),
            "EPS_Growth": int(eps_growth.notna().sum()),
            "Analyst": analyst_cov_n,
        },
        eps_coverage=eps_coverage,
        analyst_coverage=analyst_coverage,
    )
    return df, diagnostics
