"""Company-quality scoring built from currently available QuantBridge fields.

The existing V/Q/M score is useful for ranking investable candidates, but it
mixes business quality with valuation and market timing. This module separates
those ideas so the pipeline can answer two different questions:

1. Is this a good business?
2. Is this a good candidate to review now?
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

import numpy as np
import pandas as pd


QUALITY_SCORE_COLS = [
    "Profitability_Quality",
    "Cash_Quality",
    "Growth_Quality",
    "BalanceSheet_Strength",
    "Valuation_Discipline",
    "Timing_Overlay",
    "Persistence_Quality",
    "Business_Quality_Score",
    "Investability_Score",
    "Quality_Data_Confidence",
    "Quality_Red_Flags",
]

QUALITY_REVIEW_COLS = [
    "Investability_Rank",
    "Business_Quality_Rank",
    "Quality_Rank_Delta",
    "Quality_Category",
]

SEVERE_QUALITY_FLAGS = {
    "NEGATIVE_ROIC",
    "CASH_BURN",
    "HIGH_DEBT_EBITDA",
    "HIGH_DEBT_TO_EQUITY",
    "LOW_INTEREST_COVERAGE",
    "DISTRESS_RISK",
    "NEGATIVE_BOOK_EQUITY",
}

LOW_CONFIDENCE_SCORE_CAP = 0.62
VERY_LOW_CONFIDENCE_SCORE_CAP = 0.52
SEVERE_FLAG_SCORE_CAP = 0.72


@dataclass(frozen=True)
class CompanyQualityConfig:
    sector_blend: float = 0.70
    min_sector_size: int = 5
    min_confidence_multiplier: float = 0.75


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


def _rank_global(series: pd.Series, *, higher_is_better: bool = True) -> pd.Series:
    s = pd.to_numeric(series, errors="coerce").replace([np.inf, -np.inf], np.nan)
    if not higher_is_better:
        s = (1.0 / s).where(s > 0)
    if s.notna().sum() == 0:
        return pd.Series(0.5, index=series.index, dtype="float64")
    return s.rank(pct=True, na_option="keep").fillna(0.5).clip(0.0, 1.0)


def _rank_sector_aware(
    df: pd.DataFrame,
    series: pd.Series,
    *,
    higher_is_better: bool = True,
    config: CompanyQualityConfig,
) -> pd.Series:
    global_rank = _rank_global(series, higher_is_better=higher_is_better)
    if "Sector" not in df.columns or config.sector_blend <= 0:
        return global_rank

    sector_rank = global_rank.copy()
    for _, idx in df.groupby("Sector").groups.items():
        idx_list = list(idx)
        if len(idx_list) < config.min_sector_size:
            continue
        sector_rank.loc[idx_list] = _rank_global(
            series.loc[idx_list],
            higher_is_better=higher_is_better,
        )

    blend = float(np.clip(config.sector_blend, 0.0, 1.0))
    return (blend * sector_rank + (1.0 - blend) * global_rank).clip(0.0, 1.0)


def _metric_rank(
    df: pd.DataFrame,
    names: list[str],
    *,
    higher_is_better: bool = True,
    config: CompanyQualityConfig,
) -> tuple[pd.Series, pd.Series]:
    values = _num(df, names)
    return (
        _rank_sector_aware(df, values, higher_is_better=higher_is_better, config=config),
        values.notna(),
    )


def _positive_metric_rank(
    df: pd.DataFrame,
    names: list[str],
    *,
    higher_is_better: bool = True,
    config: CompanyQualityConfig,
) -> tuple[pd.Series, pd.Series]:
    values = _num(df, names)
    observed = values.notna()
    positive_values = values.where(values > 0)
    ranked = _rank_sector_aware(
        df,
        positive_values,
        higher_is_better=higher_is_better,
        config=config,
    )
    return ranked.where(values.gt(0) | ~observed, 0.0).clip(0.0, 1.0), observed


def _weighted_average(parts: list[tuple[float, pd.Series]]) -> pd.Series:
    if not parts:
        raise ValueError("parts cannot be empty")
    out = pd.Series(0.0, index=parts[0][1].index, dtype="float64")
    total = 0.0
    for weight, values in parts:
        out = out.add(values.fillna(0.5).mul(weight), fill_value=0.0)
        total += weight
    return (out / total if total else out).clip(0.0, 1.0)


def _confidence(mask_parts: list[pd.Series]) -> pd.Series:
    if not mask_parts:
        return pd.Series(0.0, dtype="float64")
    frame = pd.concat([part.astype(float) for part in mask_parts], axis=1)
    return frame.mean(axis=1).fillna(0.0).clip(0.0, 1.0)


def _confidence_multiplier(confidence: pd.Series, config: CompanyQualityConfig) -> pd.Series:
    floor = float(np.clip(config.min_confidence_multiplier, 0.0, 1.0))
    return floor + (1.0 - floor) * confidence.clip(0.0, 1.0)


def _quality_red_flags(df: pd.DataFrame, data_confidence: pd.Series) -> pd.Series:
    roic = _num(df, ["ROIC", "roic"])
    fcf_margin = _num(df, ["FCF_Margin", "fcf_margin"])
    fcf_ni = _num(df, ["FCF_NI", "fcf_ni"])
    rev_growth = _num(df, ["RevGrowth", "RevenueGrowth", "rev_growth"])
    debt_ebitda = _num(df, ["Debt_EBITDA", "debt_ebitda"])
    debt_to_equity = _num(df, ["DebtToEquity", "de_ratio"])
    interest_coverage = _num(df, ["InterestCoverage", "int_cov", "interest_coverage"])
    altman = _num(df, ["AltmanZ", "altman_z"])
    peg = _num(df, ["PEG"])
    ev_ebitda = _num(df, ["EV_EBITDA", "ev_ebitda"])
    per = _num(df, ["PER", "pe_ratio"])
    pbr = _num(df, ["PBR", "price_to_book"])

    flags: list[str] = []
    for idx in df.index:
        row_flags: list[str] = []
        if pd.notna(roic.loc[idx]) and roic.loc[idx] < 0:
            row_flags.append("NEGATIVE_ROIC")
        if pd.notna(fcf_margin.loc[idx]) and fcf_margin.loc[idx] < -0.05:
            row_flags.append("CASH_BURN")
        if pd.notna(fcf_ni.loc[idx]) and fcf_ni.loc[idx] < 0:
            row_flags.append("EARNINGS_NOT_CASH_BACKED")
        if pd.notna(rev_growth.loc[idx]) and rev_growth.loc[idx] < -0.05:
            row_flags.append("REVENUE_DECLINE")
        if pd.notna(debt_ebitda.loc[idx]) and debt_ebitda.loc[idx] > 5:
            row_flags.append("HIGH_DEBT_EBITDA")
        if pd.notna(debt_to_equity.loc[idx]) and debt_to_equity.loc[idx] > 300:
            row_flags.append("HIGH_DEBT_TO_EQUITY")
        if pd.notna(interest_coverage.loc[idx]) and interest_coverage.loc[idx] < 2:
            row_flags.append("LOW_INTEREST_COVERAGE")
        if pd.notna(altman.loc[idx]) and altman.loc[idx] < 1.81:
            row_flags.append("DISTRESS_RISK")
        if pd.notna(per.loc[idx]) and per.loc[idx] <= 0:
            row_flags.append("NEGATIVE_EARNINGS")
        if pd.notna(pbr.loc[idx]) and pbr.loc[idx] <= 0:
            row_flags.append("NEGATIVE_BOOK_EQUITY")
        if pd.notna(ev_ebitda.loc[idx]) and ev_ebitda.loc[idx] <= 0:
            row_flags.append("NEGATIVE_EBITDA")
        if pd.notna(peg.loc[idx]) and peg.loc[idx] > 3:
            row_flags.append("EXPENSIVE_VS_GROWTH")
        if pd.notna(ev_ebitda.loc[idx]) and ev_ebitda.loc[idx] > 30:
            row_flags.append("HIGH_EV_EBITDA")
        if pd.notna(data_confidence.loc[idx]) and data_confidence.loc[idx] < 0.45:
            row_flags.append("LOW_DATA_CONFIDENCE")
        flags.append("|".join(row_flags))
    return pd.Series(flags, index=df.index, dtype="object")


def _flag_penalty(flags: pd.Series) -> pd.Series:
    counts = flags.fillna("").astype(str).map(lambda text: 0 if not text else len(text.split("|")))
    return counts.clip(0, 5).astype(float) * 0.025


def quality_flags_set(flags: object) -> set[str]:
    text = str(flags or "").strip()
    if not text:
        return set()
    return {part.strip() for part in text.split("|") if part.strip()}


def quality_flag_penalty(flags: pd.Series) -> pd.Series:
    """Return an expert-review penalty where severe flags count more than soft flags."""
    def penalty(value: object) -> float:
        flag_set = quality_flags_set(value)
        severe_count = len(flag_set & SEVERE_QUALITY_FLAGS)
        soft_count = len(flag_set - SEVERE_QUALITY_FLAGS)
        return min(0.18, severe_count * 0.035 + soft_count * 0.0125)

    return flags.fillna("").map(penalty).astype(float)


def quality_adjusted_final_score(
    df: pd.DataFrame,
    *,
    total_col: str = "Total_Score",
    neutral_col: str = "Score_Neutral",
    investability_col: str = "Investability_Score",
    confidence_col: str = "Quality_Data_Confidence",
    flags_col: str = "Quality_Red_Flags",
) -> pd.Series:
    """Blend factor rank, sector-neutral rank, and quality rank into a review score.

    The formula keeps the V/Q/M factor engine as the base signal, then behaves
    more like a senior analyst review: require sector-neutral strength, reward
    investability, haircut low-confidence rows, and penalize severe red flags.
    """
    def rank_component(column: str) -> pd.Series:
        if column not in df.columns:
            return pd.Series(0.5, index=df.index, dtype="float64")
        values = pd.to_numeric(df[column], errors="coerce")
        if values.notna().sum() == 0:
            return pd.Series(0.5, index=df.index, dtype="float64")
        return values.rank(pct=True, na_option="keep").fillna(0.5)

    base_rank = (
        0.45 * rank_component(total_col)
        + 0.25 * rank_component(neutral_col)
        + 0.30 * rank_component(investability_col)
    )
    if confidence_col in df.columns:
        confidence = pd.to_numeric(df[confidence_col], errors="coerce").fillna(0.0).clip(0.0, 1.0)
    else:
        confidence = pd.Series(0.0, index=df.index, dtype="float64")
    flags = df[flags_col] if flags_col in df.columns else pd.Series("", index=df.index, dtype="object")
    score = (
        base_rank * (0.50 + 0.50 * confidence)
        - quality_flag_penalty(flags)
    ).clip(0.0, 1.0)

    score_cap = pd.Series(1.0, index=df.index, dtype="float64")
    score_cap = score_cap.mask(confidence < 0.45, LOW_CONFIDENCE_SCORE_CAP)
    score_cap = score_cap.mask(confidence < 0.25, VERY_LOW_CONFIDENCE_SCORE_CAP)
    severe_flags = flags.fillna("").map(
        lambda value: bool(quality_flags_set(value) & SEVERE_QUALITY_FLAGS)
    )
    score_cap = score_cap.mask(
        severe_flags,
        np.minimum(score_cap.to_numpy(dtype="float64"), SEVERE_FLAG_SCORE_CAP),
    )
    return score.clip(upper=score_cap).clip(0.0, 1.0).round(4)


def quality_category(row: pd.Series, min_confidence: float = 0.45) -> str:
    business = float(row.get("Business_Quality_Score") or 0)
    investability = float(row.get("Investability_Score") or 0)
    valuation = float(row.get("Valuation_Discipline") or 0)
    confidence = float(row.get("Quality_Data_Confidence") or 0)
    flags = quality_flags_set(row.get("Quality_Red_Flags"))

    if flags & SEVERE_QUALITY_FLAGS:
        return "Risk Review"
    if confidence < min_confidence:
        return "Data Gap"
    if business >= 0.72 and investability >= 0.68:
        return "Core Compounder"
    if business >= 0.65 and investability >= 0.60:
        return "QARP Candidate"
    if business >= 0.70 and valuation < 0.45:
        return "Great Business, Pricey"
    if business >= 0.60:
        return "Watchlist"
    return "Low Priority"


def add_company_quality_review_columns(
    features: pd.DataFrame,
    *,
    rank_col: str = "Rank",
    min_confidence: float = 0.45,
) -> pd.DataFrame:
    """Add review-oriented rank/category columns from quality scores."""
    df = features.copy()
    if any(col not in df.columns for col in ["Business_Quality_Score", "Investability_Score"]):
        df = compute_company_quality_scores(df)

    investability = pd.to_numeric(df["Investability_Score"], errors="coerce")
    business = pd.to_numeric(df["Business_Quality_Score"], errors="coerce")
    df["Investability_Rank"] = investability.rank(
        method="first",
        ascending=False,
        na_option="bottom",
    ).astype("Int64")
    df["Business_Quality_Rank"] = business.rank(
        method="first",
        ascending=False,
        na_option="bottom",
    ).astype("Int64")

    if rank_col in df.columns:
        original_rank = pd.to_numeric(df[rank_col], errors="coerce")
        df["Quality_Rank_Delta"] = (original_rank - df["Investability_Rank"].astype(float)).round(0)
    else:
        df["Quality_Rank_Delta"] = pd.NA

    df["Quality_Category"] = df.apply(
        lambda row: quality_category(row, min_confidence=min_confidence),
        axis=1,
    )
    return df


def compute_company_quality_scores(
    features: pd.DataFrame,
    *,
    config: CompanyQualityConfig | None = None,
) -> pd.DataFrame:
    """Add business-quality and investability scores to a feature frame.

    All scores are normalized to 0..1. Missing metrics receive neutral ranks, but
    the final scores are lightly haircut by ``Quality_Data_Confidence`` so sparse
    data does not look as strong as observed evidence.
    """
    cfg = config or CompanyQualityConfig()
    df = features.copy()

    roic, roic_m = _metric_rank(df, ["ROIC", "roic"], config=cfg)
    roe, roe_m = _metric_rank(df, ["ROE", "roe"], config=cfg)
    gross_margin, gm_m = _metric_rank(df, ["GrossMargin", "gross_margin"], config=cfg)
    op_margin, opm_m = _metric_rank(df, ["OperatingMargin", "op_margin"], config=cfg)
    profitability = _weighted_average([
        (0.35, roic),
        (0.25, roe),
        (0.20, gross_margin),
        (0.20, op_margin),
    ])

    fcf_margin, fcfm_m = _metric_rank(df, ["FCF_Margin", "fcf_margin"], config=cfg)
    fcf_ni, fcfni_m = _metric_rank(df, ["FCF_NI", "fcf_ni"], config=cfg)
    accruals_values = _num(df, ["Accruals_EQ", "accruals_eq"])
    if accruals_values.notna().sum() == 0:
        accruals_values = _num(df, ["FCF_Margin", "fcf_margin"]) / _num(
            df, ["OperatingMargin", "op_margin"]
        ).replace(0, np.nan)
    accruals = _rank_sector_aware(df, accruals_values, config=cfg)
    accruals_m = accruals_values.replace([np.inf, -np.inf], np.nan).notna()
    cash = _weighted_average([
        (0.45, fcf_margin),
        (0.35, fcf_ni),
        (0.20, accruals),
    ])

    rev_growth, revg_m = _metric_rank(
        df, ["RevGrowth", "RevenueGrowth", "rev_growth"], config=cfg
    )
    eps_growth, epsg_m = _metric_rank(df, ["EPS_Growth", "eps_growth"], config=cfg)
    rev_accel, reva_m = _metric_rank(df, ["RevAccel", "Rev_Accel", "rev_accel"], config=cfg)
    growth = _weighted_average([
        (0.45, rev_growth),
        (0.30, eps_growth),
        (0.25, rev_accel),
    ])

    debt_ebitda, debte_m = _metric_rank(
        df, ["Debt_EBITDA", "debt_ebitda"], higher_is_better=False, config=cfg
    )
    debt_equity, de_m = _metric_rank(
        df, ["DebtToEquity", "de_ratio"], higher_is_better=False, config=cfg
    )
    interest_cov, ic_m = _metric_rank(
        df, ["InterestCoverage", "int_cov", "interest_coverage"], config=cfg
    )
    altman, altman_m = _metric_rank(df, ["AltmanZ", "altman_z"], config=cfg)
    balance = _weighted_average([
        (0.35, debt_ebitda),
        (0.25, debt_equity),
        (0.25, interest_cov),
        (0.15, altman),
    ])

    peg, peg_m = _positive_metric_rank(df, ["PEG"], higher_is_better=False, config=cfg)
    ev_ebitda, ev_m = _positive_metric_rank(
        df, ["EV_EBITDA", "ev_ebitda"], higher_is_better=False, config=cfg
    )
    per, per_m = _positive_metric_rank(df, ["PER", "pe_ratio"], higher_is_better=False, config=cfg)
    pbr, pbr_m = _positive_metric_rank(df, ["PBR", "price_to_book"], higher_is_better=False, config=cfg)
    div_yield, div_m = _metric_rank(df, ["DivYield", "div_yield"], config=cfg)
    valuation = _weighted_average([
        (0.35, peg),
        (0.30, ev_ebitda),
        (0.20, per),
        (0.10, pbr),
        (0.05, div_yield),
    ])

    mom12, mom12_m = _metric_rank(df, ["Mom_12M_1M", "mom_12m_1m"], config=cfg)
    mom3, mom3_m = _metric_rank(df, ["Mom_3M", "mom_3m"], config=cfg)
    analyst, analyst_m = _metric_rank(df, ["r_analyst", "AnalystRank", "analyst_rank"], config=cfg)
    timing = _weighted_average([
        (0.55, mom12),
        (0.35, mom3),
        (0.10, analyst),
    ])

    persistence_score, persist_m = _metric_rank(
        df, ["Quality_Persistence_Score", "quality_persistence_score"], config=cfg
    )
    roic_5y, roic5_m = _metric_rank(df, ["ROIC_5Y_Median", "roic_5y_median"], config=cfg)
    roic_stability, roicstab_m = _metric_rank(
        df, ["ROIC_5Y_Stability", "roic_5y_stability"], config=cfg
    )
    fcf_positive, fcfpos_m = _metric_rank(
        df, ["FCF_Positive_Years_5Y", "fcf_positive_years_5y"], config=cfg
    )
    margin_stability, marginstab_m = _metric_rank(
        df, ["Margin_Stability_5Y", "margin_stability_5y"], config=cfg
    )
    persistence = _weighted_average([
        (0.45, persistence_score),
        (0.20, roic_5y),
        (0.15, roic_stability),
        (0.10, fcf_positive),
        (0.10, margin_stability),
    ])

    business_confidence = _confidence([
        roic_m,
        roe_m,
        gm_m,
        opm_m,
        fcfm_m,
        fcfni_m,
        accruals_m,
        revg_m,
        epsg_m,
        reva_m,
        debte_m,
        de_m,
        ic_m,
        altman_m,
        persist_m,
        roic5_m,
        roicstab_m,
        fcfpos_m,
        marginstab_m,
    ])
    investability_confidence = _confidence([
        roic_m,
        roe_m,
        gm_m,
        opm_m,
        fcfm_m,
        fcfni_m,
        accruals_m,
        revg_m,
        epsg_m,
        reva_m,
        debte_m,
        de_m,
        ic_m,
        altman_m,
        peg_m,
        ev_m,
        per_m,
        pbr_m,
        div_m,
        mom12_m,
        mom3_m,
        analyst_m,
        persist_m,
        roic5_m,
        roicstab_m,
        fcfpos_m,
        marginstab_m,
    ])

    business_raw = _weighted_average([
        (0.30, profitability),
        (0.22, cash),
        (0.18, growth),
        (0.18, balance),
        (0.12, persistence),
    ])
    business = (business_raw * _confidence_multiplier(business_confidence, cfg)).clip(0.0, 1.0)

    flags = _quality_red_flags(df, investability_confidence)
    investability_raw = _weighted_average([
        (0.50, business),
        (0.25, valuation),
        (0.20, timing),
        (0.05, balance),
    ])
    investability = (
        investability_raw * _confidence_multiplier(investability_confidence, cfg)
        - _flag_penalty(flags)
    ).clip(0.0, 1.0)

    df["Profitability_Quality"] = profitability.round(4)
    df["Cash_Quality"] = cash.round(4)
    df["Growth_Quality"] = growth.round(4)
    df["BalanceSheet_Strength"] = balance.round(4)
    df["Valuation_Discipline"] = valuation.round(4)
    df["Timing_Overlay"] = timing.round(4)
    df["Persistence_Quality"] = persistence.round(4)
    df["Business_Quality_Score"] = business.round(4)
    df["Investability_Score"] = investability.round(4)
    df["Quality_Data_Confidence"] = investability_confidence.round(4)
    df["Quality_Red_Flags"] = flags
    return df
