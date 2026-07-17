"""Sheet-less Korean stock scoring for local research workflows."""

from __future__ import annotations

import time
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd
import yfinance as yf

from pipeline.data.kr_dart_lake import DEFAULT_OUTPUT_DIR, read_latest_quality_history_features
from pipeline.data.kr_local_universe import kr_code, read_latest_kr_pit_fundamentals
from pipeline.scoring.company_quality import (
    QUALITY_REVIEW_COLS,
    QUALITY_SCORE_COLS,
    add_company_quality_review_columns,
    compute_company_quality_scores,
    quality_adjusted_final_score,
)
from quantbridge.schemas import PORTFOLIO_COLS, SCORED_COLS, SMALLCAP_COLS


DEFAULT_WEIGHTS = {
    "Value_Score": 0.40,
    "Quality_Score": 0.35,
    "Momentum_Score": 0.25,
}


def _to_float(value):
    try:
        if value in ("", None):
            return np.nan
        return float(value)
    except Exception:
        return np.nan


def _rpct(series: pd.Series) -> pd.Series:
    return pd.to_numeric(series, errors="coerce").rank(pct=True, na_option="keep")


def _numeric_column(df: pd.DataFrame, column: str, default: float = np.nan) -> pd.Series:
    if column not in df.columns:
        return pd.Series(default, index=df.index, dtype="float64")
    return pd.to_numeric(df[column], errors="coerce")


def _value_rank(series: pd.Series) -> pd.Series:
    values = pd.to_numeric(series, errors="coerce")
    inverse = (1.0 / values).where(values > 0)
    return inverse.rank(pct=True, na_option="keep")


def _latest_pit_by_ticker(pit_df: pd.DataFrame) -> pd.DataFrame:
    if pit_df.empty or "Ticker" not in pit_df.columns:
        return pd.DataFrame()
    df = pit_df.copy()
    df["Code"] = df["Ticker"].map(kr_code)
    df["Fiscal_Year"] = pd.to_numeric(df.get("Fiscal_Year"), errors="coerce")
    df = df[df["Code"].ne("")]
    if df.empty:
        return pd.DataFrame()
    return df.sort_values(["Code", "Fiscal_Year"]).drop_duplicates("Code", keep="last")


def _merge_latest_pit(scored: pd.DataFrame, pit_df: pd.DataFrame) -> pd.DataFrame:
    if pit_df.empty:
        return scored.copy()
    latest = _latest_pit_by_ticker(pit_df)
    if latest.empty:
        return scored.copy()
    latest["Ticker_Code"] = latest["Code"]
    out = scored.copy()
    out["Ticker_Code"] = out["Ticker"].map(kr_code)
    extra_cols = [
        "Ticker_Code",
        "ROIC",
        "RevGrowth",
        "FCF_Margin",
        "FCF_NI",
        "InterestCoverage",
        "Debt_EBITDA",
        "EPS_Growth",
        "TotalAssets",
        "CurrentAssets",
        "CurrentLiabilities",
        "RetainedEarnings",
        "TotalLiabilities",
    ]
    available = [col for col in extra_cols if col in latest.columns]
    if len(available) <= 1:
        return out.drop(columns=["Ticker_Code"], errors="ignore")
    out = out.merge(latest[available], on="Ticker_Code", how="left", suffixes=("", "_pit"))
    out = out.drop(columns=["Ticker_Code"], errors="ignore")
    return out


def _merge_history_features(scored: pd.DataFrame, history_df: pd.DataFrame) -> pd.DataFrame:
    if history_df.empty or "Ticker" not in history_df.columns:
        return scored.copy()
    history = history_df.copy()
    history["Ticker_Code"] = history["Ticker"].map(kr_code)
    out = scored.copy()
    out["Ticker_Code"] = out["Ticker"].map(kr_code)
    history_cols = [
        "Ticker_Code",
        "History_Years",
        "ROIC_5Y_Median",
        "ROIC_5Y_Stability",
        "Revenue_CAGR_5Y",
        "FCF_Positive_Years_5Y",
        "Margin_Stability_5Y",
        "Debt_Reduction_Trend_5Y",
        "Quality_Persistence_Score",
    ]
    available = [col for col in history_cols if col in history.columns]
    history = history[available].drop_duplicates("Ticker_Code", keep="last")
    out = out.merge(history, on="Ticker_Code", how="left")
    return out.drop(columns=["Ticker_Code"], errors="ignore")


def _compute_altman_z(row: pd.Series) -> float:
    try:
        total_assets = _to_float(row.get("TotalAssets"))
        if not total_assets or np.isnan(total_assets):
            return np.nan
        current_assets = _to_float(row.get("CurrentAssets"))
        current_liabilities = _to_float(row.get("CurrentLiabilities"))
        retained_earnings = _to_float(row.get("RetainedEarnings"))
        total_liabilities = _to_float(row.get("TotalLiabilities")) or 1e-9
        market_cap = _to_float(row.get("MarketCap"))
        revenue = _to_float(row.get("Revenue"))
        operating_margin = _to_float(row.get("OperatingMargin"))
        ebit = operating_margin * revenue if pd.notna(operating_margin) and pd.notna(revenue) else np.nan
        x1 = (current_assets - current_liabilities) / total_assets
        x2 = retained_earnings / total_assets
        x3 = ebit / total_assets
        x4 = market_cap / total_liabilities
        x5 = revenue / total_assets
        return round(1.2 * x1 + 1.4 * x2 + 3.3 * x3 + 0.6 * x4 + 1.0 * x5, 4)
    except Exception:
        return np.nan


def _download_momentum(tickers: Iterable[str]) -> pd.DataFrame:
    symbols = [str(ticker) for ticker in tickers if str(ticker).strip()]
    if not symbols:
        return pd.DataFrame(columns=["Ticker", "Mom_1M", "Mom_3M", "Mom_12M", "Volatility"])
    frames = []
    for i in range(0, len(symbols), 80):
        batch = symbols[i:i + 80]
        try:
            raw = yf.download(batch, period="1y", auto_adjust=True, progress=False)
            closes = raw["Close"] if isinstance(raw.columns, pd.MultiIndex) else raw
            if isinstance(closes, pd.Series):
                closes = closes.to_frame(name=batch[0])
            frames.append(closes)
        except Exception:
            pass
        time.sleep(0.25)
    if not frames:
        return pd.DataFrame({"Ticker": symbols})
    prices = pd.concat(frames, axis=1)
    prices = prices.loc[:, ~prices.columns.duplicated()]

    rows = []
    for ticker in symbols:
        if ticker not in prices.columns:
            rows.append({"Ticker": ticker})
            continue
        series = prices[ticker].dropna()
        if len(series) < 100:
            rows.append({"Ticker": ticker})
            continue
        current = series.iloc[-1]
        volatility = series.pct_change().dropna().std() * np.sqrt(252)
        rows.append({
            "Ticker": ticker,
            "Mom_1M": round((current / series.iloc[-21]) - 1, 4) if len(series) >= 21 else np.nan,
            "Mom_3M": round((current / series.iloc[-63]) - 1, 4) if len(series) >= 63 else np.nan,
            "Mom_12M": round((current / series.iloc[0]) - 1, 4),
            "Volatility": round(float(volatility), 4) if pd.notna(volatility) else np.nan,
        })
    return pd.DataFrame(rows)


def sector_neutralize(df: pd.DataFrame, score_col: str = "Total_Score") -> pd.DataFrame:
    out = df.copy()
    out["Score_Neutral"] = out[score_col]
    if "Sector" not in out.columns:
        return out
    for sector, group in out.groupby("Sector"):
        if not str(sector).strip() or str(sector).strip() == "Unclassified" or len(group) < 5:
            continue
        std = group[score_col].std()
        if std and std > 0:
            out.loc[group.index, "Score_Neutral"] = (group[score_col] - group[score_col].mean()) / std
    return out


def build_kr_portfolio_candidates(
    scored_df: pd.DataFrame,
    *,
    size: int = 10,
    min_confidence: float = 0.45,
) -> pd.DataFrame:
    """Convert local KR scores into an app-facing equal-weight candidate sleeve."""
    if scored_df.empty:
        return pd.DataFrame(columns=PORTFOLIO_COLS)

    safe_size = max(1, int(size or 10))
    df = scored_df.copy()
    if "Quality_Category" in df.columns:
        blocked = {"DATA GAP", "RISK REVIEW"}
        category = df["Quality_Category"].fillna("").astype(str).str.strip().str.upper()
        df = df[~category.isin(blocked)].copy()
    if "Quality_Data_Confidence" in df.columns:
        confidence = _numeric_column(df, "Quality_Data_Confidence", default=0.0).fillna(0.0)
        df = df[confidence >= min_confidence].copy()
    if df.empty:
        return pd.DataFrame(columns=PORTFOLIO_COLS)

    if "Rank" in df.columns:
        df["_rank_sort"] = pd.to_numeric(df["Rank"], errors="coerce")
        df = df.sort_values(["_rank_sort", "Final_Score"], ascending=[True, False], na_position="last")
    elif "Final_Score" in df.columns:
        df = df.sort_values("Final_Score", ascending=False)
    top = df.head(safe_size).copy().reset_index(drop=True)
    if top.empty:
        return pd.DataFrame(columns=PORTFOLIO_COLS)

    rows = pd.DataFrame({
        "Rank": range(1, len(top) + 1),
        "Ticker": top.get("Ticker", ""),
        "Name": top.get("Name", ""),
        "Market": "KR",
        "Sector": top.get("Sector", ""),
        "MarketCap": top.get("MarketCap", ""),
        "Weight(%)": round(1.0 / len(top), 6),
        "Current_Price": "",
        "Return_1M": "",
        "Total_Score": top.get("Total_Score", ""),
        "ROIC": top.get("ROIC", ""),
        "RevGrowth": top.get("RevGrowth", ""),
        "GrossMargin": top.get("GrossMargin", ""),
        "Expected_Return": "",
        "Last_Updated": top.get("Last_Updated", pd.Timestamp.now().strftime("%Y-%m-%d")),
    })
    for col in PORTFOLIO_COLS:
        if col not in rows.columns:
            rows[col] = ""
    return rows[PORTFOLIO_COLS].copy()


def _smallcap_bonus(market_cap: float, min_cap: float, max_cap: float) -> float:
    if pd.isna(market_cap):
        return np.nan
    if market_cap <= min_cap:
        return 15.0
    if market_cap >= max_cap:
        return 0.0
    return float(15.0 * (max_cap - market_cap) / (max_cap - min_cap))


def build_kr_smallcap_candidates(
    scored_df: pd.DataFrame,
    *,
    size: int = 20,
    min_market_cap: float = 100_000_000_000,
    max_market_cap: float = 10_000_000_000_000,
    min_confidence: float = 0.20,
) -> pd.DataFrame:
    """Build local KR small-cap candidates from scored rows with observable market caps."""
    if scored_df.empty:
        return pd.DataFrame(columns=SMALLCAP_COLS)

    df = scored_df.copy()
    df["MarketCap"] = _numeric_column(df, "MarketCap")
    df = df[df["MarketCap"].between(min_market_cap, max_market_cap, inclusive="both")].copy()
    if df.empty:
        return pd.DataFrame(columns=SMALLCAP_COLS)

    confidence = _numeric_column(df, "Quality_Data_Confidence", default=0.0).fillna(0.0)
    df = df[confidence >= min_confidence].copy()
    if df.empty:
        return pd.DataFrame(columns=SMALLCAP_COLS)

    investability = _numeric_column(df, "Investability_Score")
    business = _numeric_column(df, "Business_Quality_Score")
    final = _numeric_column(df, "Final_Score")
    total = _numeric_column(df, "Total_Score")
    base = (
        0.55 * investability.fillna(total.fillna(0.0))
        + 0.25 * business.fillna(total.fillna(0.0))
        + 0.20 * final.fillna(total.fillna(0.0))
    ) * 100.0
    df["SmallCap_Bonus"] = df["MarketCap"].map(lambda value: _smallcap_bonus(value, min_market_cap, max_market_cap))
    df["_SmallCap_Total"] = (base + pd.to_numeric(df["SmallCap_Bonus"], errors="coerce").fillna(0.0)).round(4)
    df = df.sort_values("_SmallCap_Total", ascending=False).head(max(1, int(size or 20))).reset_index(drop=True)

    rows = pd.DataFrame({
        "Rank": range(1, len(df) + 1),
        "Ticker": df.get("Ticker", ""),
        "Name": df.get("Name", ""),
        "Market": "KR",
        "MarketCap": df.get("MarketCap", ""),
        "ROIC": df.get("ROIC", ""),
        "RevGrowth": df.get("RevGrowth", ""),
        "Rev_Accel": "",
        "GrossMargin": df.get("GrossMargin", ""),
        "FCF_Margin": df.get("FCF_Margin", ""),
        "Debt_EBITDA": df.get("Debt_EBITDA", ""),
        "Volume_Surge": "",
        "SmallCap_Bonus": df.get("SmallCap_Bonus", ""),
        "Data_Confidence": df.get("Quality_Data_Confidence", ""),
        "Total_Score": df.get("_SmallCap_Total", ""),
        "Last_Updated": df.get("Last_Updated", pd.Timestamp.now().strftime("%Y-%m-%d")),
    })
    for col in SMALLCAP_COLS:
        if col not in rows.columns:
            rows[col] = ""
    numeric_cols = [
        "MarketCap",
        "ROIC",
        "RevGrowth",
        "GrossMargin",
        "FCF_Margin",
        "Debt_EBITDA",
        "SmallCap_Bonus",
        "Data_Confidence",
        "Total_Score",
    ]
    for col in numeric_cols:
        if col in rows.columns:
            rows[col] = pd.to_numeric(rows[col], errors="coerce").round(4)
    return rows[SMALLCAP_COLS].copy()


def score_local_kr_stocks(
    universe_df: pd.DataFrame,
    *,
    pit_df: pd.DataFrame | None = None,
    history_df: pd.DataFrame | None = None,
    output_dir: str | Path = DEFAULT_OUTPUT_DIR,
    download_prices: bool = True,
    weights: dict[str, float] | None = None,
) -> pd.DataFrame:
    """Score a local ``KR_Universe`` DataFrame without Google Sheets."""
    if universe_df.empty:
        return pd.DataFrame(columns=SCORED_COLS)

    w = {**DEFAULT_WEIGHTS, **(weights or {})}
    df = universe_df.copy()
    pit = read_latest_kr_pit_fundamentals(output_dir=output_dir) if pit_df is None else pit_df
    history = read_latest_quality_history_features(output_dir=output_dir) if history_df is None else history_df
    df = _merge_latest_pit(df, pit)
    df = _merge_history_features(df, history)

    numeric_cols = [
        "MarketCap",
        "PER",
        "PBR",
        "ROE",
        "Revenue",
        "RevenueGrowth",
        "OperatingMargin",
        "GrossMargin",
        "DebtToEquity",
        "ROIC",
        "RevGrowth",
        "FCF_Margin",
        "FCF_NI",
        "InterestCoverage",
        "Debt_EBITDA",
        "EPS_Growth",
        "TotalAssets",
        "CurrentAssets",
        "CurrentLiabilities",
        "RetainedEarnings",
        "TotalLiabilities",
        "Quality_Persistence_Score",
    ]
    for col in numeric_cols:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    if "RevGrowth" not in df.columns:
        df["RevGrowth"] = np.nan
    df["RevGrowth"] = df["RevGrowth"].combine_first(pd.to_numeric(df.get("RevenueGrowth"), errors="coerce"))

    if "EPS_Growth" in df.columns and "PER" in df.columns:
        eps_growth = pd.to_numeric(df["EPS_Growth"], errors="coerce")
        df["PEG"] = (pd.to_numeric(df["PER"], errors="coerce") / (eps_growth * 100)).where(eps_growth > 0)
    else:
        df["PEG"] = np.nan

    df["AltmanZ"] = df.apply(_compute_altman_z, axis=1)
    df["Accruals_EQ"] = pd.to_numeric(df.get("FCF_Margin"), errors="coerce") / pd.to_numeric(
        df.get("OperatingMargin"), errors="coerce"
    ).replace(0, np.nan)

    if download_prices:
        momentum = _download_momentum(df["Ticker"].astype(str).tolist())
        df = df.merge(momentum, on="Ticker", how="left")
    else:
        for col in ["Mom_1M", "Mom_3M", "Mom_12M", "Volatility"]:
            df[col] = np.nan

    df["Mom_12M_1M"] = pd.to_numeric(df.get("Mom_12M"), errors="coerce") - pd.to_numeric(
        df.get("Mom_1M"), errors="coerce"
    )

    df["r_per"] = _value_rank(df["PER"])
    df["r_pbr"] = _value_rank(df["PBR"])
    df["r_revgrowth"] = _rpct(df["RevGrowth"])
    df["r_divyield"] = _rpct(df["DivYield"]) if "DivYield" in df.columns else np.nan
    all_value_missing = df[["r_per", "r_pbr", "r_revgrowth"]].isna().all(axis=1)
    df["Value_Score"] = np.where(
        all_value_missing,
        np.nan,
        w["Value_Score"] * (
            0.40 * df["r_per"].fillna(0.5)
            + 0.25 * df["r_pbr"].fillna(0.5)
            + 0.25 * df["r_revgrowth"].fillna(0.5)
            + 0.10 * pd.Series(df["r_divyield"], index=df.index).fillna(0.5)
        ),
    )

    df["r_roe"] = _rpct(df["ROE"])
    df["r_opmgn"] = _rpct(df["OperatingMargin"])
    df["r_de_inv"] = _value_rank(df["DebtToEquity"])
    df["r_altman"] = _rpct(df["AltmanZ"])
    df["r_accruals"] = _rpct(df["Accruals_EQ"].replace([np.inf, -np.inf], np.nan))
    all_quality_missing = df[["r_roe", "r_opmgn"]].isna().all(axis=1)
    df["Quality_Score"] = np.where(
        all_quality_missing,
        np.nan,
        w["Quality_Score"] * (
            0.30 * df["r_roe"].fillna(0.5)
            + 0.25 * df["r_opmgn"].fillna(0.5)
            + 0.20 * df["r_de_inv"].fillna(0.5)
            + 0.15 * df["r_altman"].fillna(0.5)
            + 0.10 * df["r_accruals"].fillna(0.5)
        ),
    )

    df["r_mom12m1m"] = _rpct(df["Mom_12M_1M"])
    df["r_mom3m"] = _rpct(df["Mom_3M"])
    all_momentum_missing = df[["r_mom12m1m", "r_mom3m"]].isna().all(axis=1)
    df["Momentum_Score"] = np.where(
        all_momentum_missing,
        np.nan,
        w["Momentum_Score"] * (
            0.60 * df["r_mom12m1m"].fillna(0.5)
            + 0.40 * df["r_mom3m"].fillna(0.5)
        ),
    )

    df["Total_Score"] = (
        df["Value_Score"].fillna(0)
        + df["Quality_Score"].fillna(0)
        + df["Momentum_Score"].fillna(0)
    ).where(
        df["Value_Score"].notna() | df["Quality_Score"].notna() | df["Momentum_Score"].notna(),
        np.nan,
    )

    df = compute_company_quality_scores(df)
    scored = df.dropna(subset=["Total_Score"]).copy()
    scored = sector_neutralize(scored, score_col="Total_Score")
    scored["Final_Score"] = quality_adjusted_final_score(scored)
    scored = scored.sort_values("Final_Score", ascending=False).reset_index(drop=True)
    scored["Rank"] = range(1, len(scored) + 1)
    scored = add_company_quality_review_columns(scored, rank_col="Rank")
    scored["Market"] = "KR"
    scored["Last_Updated"] = pd.Timestamp.now().strftime("%Y-%m-%d")

    round_cols = [
        "Value_Score",
        "Quality_Score",
        "Momentum_Score",
        "Total_Score",
        "Final_Score",
        "Score_Neutral",
        *[col for col in QUALITY_SCORE_COLS if col != "Quality_Red_Flags"],
        *[col for col in QUALITY_REVIEW_COLS if col != "Quality_Category"],
        "ROIC",
        "RevGrowth",
        "GrossMargin",
        "FCF_Margin",
        "Debt_EBITDA",
        "PEG",
    ]
    for col in round_cols:
        if col in scored.columns:
            scored[col] = pd.to_numeric(scored[col], errors="coerce").round(4)
    for col in SCORED_COLS:
        if col not in scored.columns:
            scored[col] = ""
    return scored[SCORED_COLS].copy()
