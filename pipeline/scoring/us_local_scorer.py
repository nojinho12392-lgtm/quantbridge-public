"""Sheet-less US stock scoring for local research workflows."""

from __future__ import annotations

import time
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd
import yfinance as yf

from pipeline.data.sec_companyfacts_lake import (
    DEFAULT_OUTPUT_DIR,
    read_latest_pit_fundamentals,
    read_latest_quality_history_features,
)
from pipeline.scoring.common_factor_scorer import compute_us_factor_scores, sector_neutralize
from pipeline.scoring.company_quality import (
    QUALITY_REVIEW_COLS,
    QUALITY_SCORE_COLS,
    add_company_quality_review_columns,
    compute_company_quality_scores,
    quality_adjusted_final_score,
)
from quantbridge.schemas import PORTFOLIO_COLS, SCORED_COLS_ML, SMALLCAP_COLS


def _numeric_column(df: pd.DataFrame, column: str, default: float = np.nan) -> pd.Series:
    if column not in df.columns:
        return pd.Series(default, index=df.index, dtype="float64")
    return pd.to_numeric(df[column], errors="coerce")


def _latest_pit_by_ticker(pit_df: pd.DataFrame) -> pd.DataFrame:
    if pit_df.empty or "Ticker" not in pit_df.columns:
        return pd.DataFrame()
    df = pit_df.copy()
    df["Ticker"] = df["Ticker"].astype(str).str.upper().str.strip()
    df["Filing_Date"] = pd.to_datetime(df.get("Filing_Date"), errors="coerce")
    df = df[df["Ticker"].ne("")]
    if df.empty:
        return pd.DataFrame()
    return df.sort_values(["Ticker", "Filing_Date"]).drop_duplicates("Ticker", keep="last")


def _merge_latest_pit(scored: pd.DataFrame, pit_df: pd.DataFrame) -> pd.DataFrame:
    latest = _latest_pit_by_ticker(pit_df)
    if latest.empty:
        return scored.copy()
    extra_cols = [
        "Ticker",
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
        "OperatingMargin",
        "GrossMargin",
        "ROE",
        "Revenue",
        "DebtToEquity",
    ]
    available = [col for col in extra_cols if col in latest.columns]
    out = scored.copy()
    out["Ticker"] = out["Ticker"].astype(str).str.upper().str.strip()
    out = out.merge(latest[available], on="Ticker", how="left", suffixes=("", "_sec"))
    for col in extra_cols:
        sec_col = f"{col}_sec"
        if col == "Ticker" or sec_col not in out.columns:
            continue
        if col not in out.columns:
            out[col] = np.nan
        out[col] = pd.to_numeric(out[col], errors="coerce").combine_first(
            pd.to_numeric(out[sec_col], errors="coerce")
        )
        out = out.drop(columns=[sec_col])
    return out


def _merge_history_features(scored: pd.DataFrame, history_df: pd.DataFrame) -> pd.DataFrame:
    if history_df.empty or "Ticker" not in history_df.columns:
        return scored.copy()
    history_cols = [
        "Ticker",
        "History_Years",
        "ROIC_5Y_Median",
        "ROIC_5Y_Stability",
        "Revenue_CAGR_5Y",
        "FCF_Positive_Years_5Y",
        "Margin_Stability_5Y",
        "Debt_Reduction_Trend_5Y",
        "Quality_Persistence_Score",
    ]
    available = [col for col in history_cols if col in history_df.columns]
    history = history_df[available].copy()
    history["Ticker"] = history["Ticker"].astype(str).str.upper().str.strip()
    history = history.drop_duplicates("Ticker", keep="last")
    out = scored.copy()
    out["Ticker"] = out["Ticker"].astype(str).str.upper().str.strip()
    return out.merge(history, on="Ticker", how="left")


def _download_momentum(tickers: Iterable[str]) -> pd.DataFrame:
    symbols = [str(ticker).strip().upper() for ticker in tickers if str(ticker).strip()]
    if not symbols:
        return pd.DataFrame(columns=["Ticker", "Mom_1M", "Mom_3M", "Mom_12M", "Volatility"])
    frames = []
    for i in range(0, len(symbols), 100):
        batch = symbols[i:i + 100]
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


def _smallcap_bonus(market_cap: float, min_cap: float, max_cap: float) -> float:
    if pd.isna(market_cap):
        return np.nan
    if market_cap <= min_cap:
        return 15.0
    if market_cap >= max_cap:
        return 0.0
    return float(15.0 * (max_cap - market_cap) / (max_cap - min_cap))


def score_local_us_stocks(
    universe_df: pd.DataFrame,
    *,
    pit_df: pd.DataFrame | None = None,
    history_df: pd.DataFrame | None = None,
    output_dir: str | Path = DEFAULT_OUTPUT_DIR,
    download_prices: bool = True,
) -> pd.DataFrame:
    """Score a local ``US_Universe`` DataFrame without Google Sheets."""
    if universe_df.empty:
        return pd.DataFrame(columns=SCORED_COLS_ML)

    df = universe_df.copy()
    df["Ticker"] = df["Ticker"].astype(str).str.upper().str.strip()
    pit = read_latest_pit_fundamentals(output_dir=output_dir) if pit_df is None else pit_df
    history = read_latest_quality_history_features(output_dir=output_dir) if history_df is None else history_df
    df = _merge_latest_pit(df, pit)
    df = _merge_history_features(df, history)

    if download_prices:
        momentum = _download_momentum(df["Ticker"].astype(str).tolist())
        df = df.merge(momentum, on="Ticker", how="left")
    else:
        for col in ["Mom_1M", "Mom_3M", "Mom_12M", "Volatility"]:
            df[col] = np.nan

    numeric_cols = [
        "MarketCap", "PER", "PBR", "ROE", "Revenue", "RevenueGrowth",
        "OperatingMargin", "GrossMargin", "DebtToEquity", "PEG", "EV_EBITDA",
        "DivYield", "ROIC", "RevGrowth", "FCF_Margin", "FCF_NI",
        "InterestCoverage", "Debt_EBITDA", "EPS_Growth", "TotalAssets",
        "CurrentAssets", "CurrentLiabilities", "RetainedEarnings",
        "TotalLiabilities", "Quality_Persistence_Score", "Mom_1M", "Mom_3M",
        "Mom_12M",
    ]
    for col in numeric_cols:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    if "RevGrowth" not in df.columns:
        df["RevGrowth"] = np.nan
    df["RevGrowth"] = df["RevGrowth"].combine_first(pd.to_numeric(df.get("RevenueGrowth"), errors="coerce"))

    df = compute_us_factor_scores(df)
    df = compute_company_quality_scores(df)
    scored = df.dropna(subset=["Total_Score"]).copy()
    scored = sector_neutralize(scored, score_col="Total_Score")
    scored["Final_Score"] = quality_adjusted_final_score(scored)
    scored = scored.sort_values("Final_Score", ascending=False).reset_index(drop=True)
    scored["Rank"] = range(1, len(scored) + 1)
    scored = add_company_quality_review_columns(scored, rank_col="Rank")
    scored["Market"] = "US"
    scored["Last_Updated"] = pd.Timestamp.now().strftime("%Y-%m-%d")
    scored["ML_Score"] = ""
    scored["Combined_Score"] = scored["Final_Score"]

    round_cols = [
        "Value_Score", "Quality_Score", "Momentum_Score", "Total_Score",
        "Final_Score", "Score_Neutral", "Combined_Score",
        *[col for col in QUALITY_SCORE_COLS if col != "Quality_Red_Flags"],
        *[col for col in QUALITY_REVIEW_COLS if col != "Quality_Category"],
        "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin", "Debt_EBITDA", "PEG",
    ]
    for col in round_cols:
        if col in scored.columns:
            scored[col] = pd.to_numeric(scored[col], errors="coerce").round(4)
    for col in SCORED_COLS_ML:
        if col not in scored.columns:
            scored[col] = ""
    return scored[SCORED_COLS_ML].copy()


def build_us_portfolio_candidates(
    scored_df: pd.DataFrame,
    *,
    size: int = 10,
    min_confidence: float = 0.45,
) -> pd.DataFrame:
    if scored_df.empty:
        return pd.DataFrame(columns=PORTFOLIO_COLS)
    df = scored_df.copy()
    if "Quality_Category" in df.columns:
        blocked = {"DATA GAP", "RISK REVIEW"}
        category = df["Quality_Category"].fillna("").astype(str).str.strip().str.upper()
        df = df[~category.isin(blocked)].copy()
    confidence = _numeric_column(df, "Quality_Data_Confidence", default=0.0).fillna(0.0)
    df = df[confidence >= min_confidence].copy()
    if df.empty:
        return pd.DataFrame(columns=PORTFOLIO_COLS)
    df = df.sort_values(["Rank", "Final_Score"], ascending=[True, False], na_position="last")
    top = df.head(max(1, int(size or 10))).copy().reset_index(drop=True)
    rows = pd.DataFrame({
        "Rank": range(1, len(top) + 1),
        "Ticker": top.get("Ticker", ""),
        "Name": top.get("Name", ""),
        "Market": "US",
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


def build_us_smallcap_candidates(
    scored_df: pd.DataFrame,
    *,
    size: int = 20,
    min_market_cap: float = 100_000_000,
    max_market_cap: float = 10_000_000_000,
    min_confidence: float = 0.20,
) -> pd.DataFrame:
    if scored_df.empty:
        return pd.DataFrame(columns=SMALLCAP_COLS)
    df = scored_df.copy()
    df["MarketCap"] = _numeric_column(df, "MarketCap")
    df = df[df["MarketCap"].between(min_market_cap, max_market_cap, inclusive="both")].copy()
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
        "Market": "US",
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
    for col in ["MarketCap", "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin", "Debt_EBITDA", "SmallCap_Bonus", "Data_Confidence", "Total_Score"]:
        if col in rows.columns:
            rows[col] = pd.to_numeric(rows[col], errors="coerce").round(4)
    return rows[SMALLCAP_COLS].copy()
