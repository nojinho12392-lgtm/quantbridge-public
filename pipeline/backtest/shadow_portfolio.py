from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Mapping

import numpy as np
import pandas as pd


DEFAULT_HORIZONS: dict[str, int] = {
    "1M": 21,
    "3M": 63,
    "6M": 126,
}

SNAPSHOT_KEY = ["Snapshot_Date", "Market", "Ticker"]
EVALUATION_KEY = ["Snapshot_Date", "Market", "Horizon"]
ATTRIBUTION_KEY = ["Snapshot_Date", "Market", "Horizon", "Ticker"]
SECTOR_ATTRIBUTION_KEY = ["Snapshot_Date", "Market", "Horizon", "Sector"]
ATTRIBUTION_SUMMARY_KEY = ["Snapshot_Date", "Market", "Horizon"]


@dataclass(frozen=True)
class MarketConfig:
    market: str
    source_sheet: str
    benchmark_ticker: str


MARKET_CONFIGS = (
    MarketConfig("US", "US_Final_Portfolio", "SPY"),
    MarketConfig("KR", "KR_Final_Portfolio", "^KS11"),
)


def _clean_ticker(value: object) -> str:
    return str(value or "").strip()


def _to_numeric(series: pd.Series) -> pd.Series:
    text = series.astype(str).str.replace(",", "", regex=False).str.replace("%", "", regex=False)
    return pd.to_numeric(text, errors="coerce")


def _float_or_zero(value: object) -> float:
    parsed = pd.to_numeric(pd.Series([value]), errors="coerce").iloc[0]
    return 0.0 if pd.isna(parsed) else float(parsed)


def parse_weight_column(df: pd.DataFrame, column: str = "Weight(%)") -> pd.Series:
    if column not in df.columns:
        return pd.Series(np.nan, index=df.index, dtype=float)

    weights = _to_numeric(df[column])
    finite = weights.dropna()
    if finite.empty:
        return weights

    if finite.max() > 1.0 or finite.sum() > 2.0:
        weights = weights / 100.0
    return weights.clip(lower=0.0)


def normalize_portfolio_snapshot(
    portfolio: pd.DataFrame,
    *,
    market: str,
    source_sheet: str,
    benchmark_ticker: str,
    snapshot_date: date | str,
    price_map: Mapping[str, float] | None = None,
    generated_at: pd.Timestamp | None = None,
) -> pd.DataFrame:
    price_map = price_map or {}
    generated_at = generated_at or pd.Timestamp.now()
    snapshot_date_str = pd.Timestamp(snapshot_date).strftime("%Y-%m-%d")

    if portfolio.empty or "Ticker" not in portfolio.columns:
        return empty_snapshots()

    df = portfolio.copy()
    df["Ticker"] = df["Ticker"].map(_clean_ticker)
    df = df[df["Ticker"].ne("")].copy()
    if df.empty:
        return empty_snapshots()

    weights = parse_weight_column(df)
    if weights.notna().sum() == 0 or float(weights.fillna(0).sum()) <= 0:
        weights = pd.Series(1.0 / len(df), index=df.index, dtype=float)
    else:
        weights = weights.fillna(0.0)

    sleeve_sum = float(weights.sum())
    sleeve_weights = weights / sleeve_sum if sleeve_sum > 0 else pd.Series(1.0 / len(df), index=df.index)
    equal_weight = 1.0 / len(df)

    out = pd.DataFrame(
        {
            "Snapshot_Date": snapshot_date_str,
            "Market": market,
            "Source_Sheet": source_sheet,
            "Ticker": df["Ticker"],
            "Name": df.get("Name", ""),
            "Sector": df.get("Sector", ""),
            "Rank": df.get("Rank", ""),
            "Weight": weights.astype(float),
            "Sleeve_Weight": sleeve_weights.astype(float),
            "Equal_Weight": equal_weight,
            "Total_Score": _to_numeric(df.get("Total_Score", pd.Series(np.nan, index=df.index))),
            "ROIC": _to_numeric(df.get("ROIC", pd.Series(np.nan, index=df.index))),
            "RevGrowth": _to_numeric(df.get("RevGrowth", pd.Series(np.nan, index=df.index))),
            "GrossMargin": _to_numeric(df.get("GrossMargin", pd.Series(np.nan, index=df.index))),
            "Expected_Return": _to_numeric(df.get("Expected_Return", pd.Series(np.nan, index=df.index))),
            "Price_At_Snapshot": df["Ticker"].map(lambda ticker: price_map.get(ticker, np.nan)),
            "Benchmark_Ticker": benchmark_ticker,
            "Benchmark_Price": price_map.get(benchmark_ticker, np.nan),
            "Last_Updated": df.get("Last_Updated", ""),
            "Generated_At": generated_at.strftime("%Y-%m-%d %H:%M:%S"),
        }
    )
    return out.reset_index(drop=True)


def empty_snapshots() -> pd.DataFrame:
    return pd.DataFrame(
        columns=[
            "Snapshot_Date",
            "Market",
            "Source_Sheet",
            "Ticker",
            "Name",
            "Sector",
            "Rank",
            "Weight",
            "Sleeve_Weight",
            "Equal_Weight",
            "Total_Score",
            "ROIC",
            "RevGrowth",
            "GrossMargin",
            "Expected_Return",
            "Price_At_Snapshot",
            "Benchmark_Ticker",
            "Benchmark_Price",
            "Last_Updated",
            "Generated_At",
        ]
    )


def empty_evaluations() -> pd.DataFrame:
    return pd.DataFrame(
        columns=[
            "Snapshot_Date",
            "Market",
            "Horizon",
            "Horizon_Trading_Days",
            "Target_Date",
            "Evaluation_Date",
            "Benchmark_Ticker",
            "N",
            "Coverage",
            "Equal_Weight_Return",
            "Sleeve_Weight_Return",
            "Actual_Weight_Return",
            "Benchmark_Return",
            "Alpha_Equal",
            "Alpha_Sleeve",
            "Alpha_Actual",
            "Hit_Rate",
            "Positive_Rate",
            "Score_IC",
            "Generated_At",
        ]
    )


def empty_attribution() -> pd.DataFrame:
    return pd.DataFrame(
        columns=[
            "Snapshot_Date",
            "Market",
            "Horizon",
            "Horizon_Trading_Days",
            "Target_Date",
            "Evaluation_Date",
            "Benchmark_Ticker",
            "Ticker",
            "Name",
            "Sector",
            "Rank",
            "Weight",
            "Sleeve_Weight",
            "Equal_Weight",
            "Total_Score",
            "Start_Price",
            "End_Price",
            "Stock_Return",
            "Benchmark_Return",
            "Actual_Contribution",
            "Sleeve_Contribution",
            "Equal_Contribution",
            "Benchmark_Contribution",
            "Excess_Contribution",
            "Hit_Benchmark",
            "Generated_At",
        ]
    )


def empty_sector_attribution() -> pd.DataFrame:
    return pd.DataFrame(
        columns=[
            "Snapshot_Date",
            "Market",
            "Horizon",
            "Horizon_Trading_Days",
            "Target_Date",
            "Evaluation_Date",
            "Sector",
            "Holdings",
            "Coverage",
            "Sector_Weight",
            "Sector_Sleeve_Weight",
            "Mean_Return",
            "Weighted_Return",
            "Benchmark_Return",
            "Actual_Contribution",
            "Sleeve_Contribution",
            "Benchmark_Contribution",
            "Excess_Contribution",
            "Hit_Rate",
            "Generated_At",
        ]
    )


def empty_attribution_summary() -> pd.DataFrame:
    return pd.DataFrame(
        columns=[
            "Snapshot_Date",
            "Market",
            "Horizon",
            "Horizon_Trading_Days",
            "Target_Date",
            "Evaluation_Date",
            "Benchmark_Ticker",
            "Holdings",
            "Coverage",
            "Invested_Weight",
            "Cash_Weight",
            "Actual_Return",
            "Benchmark_Return",
            "Alpha_Actual",
            "Stock_Excess_Contribution",
            "Cash_Opportunity_Cost",
            "Explained_Alpha",
            "Top_Contributor",
            "Top_Contribution",
            "Worst_Contributor",
            "Worst_Contribution",
            "Top_Sector",
            "Top_Sector_Contribution",
            "Worst_Sector",
            "Worst_Sector_Contribution",
            "Hit_Rate",
            "Positive_Rate",
            "Score_Return_IC",
            "Generated_At",
        ]
    )


def merge_by_key(existing: pd.DataFrame, new: pd.DataFrame, key_cols: list[str]) -> pd.DataFrame:
    frames = [frame for frame in (existing, new) if frame is not None and not frame.empty]
    if not frames:
        return pd.DataFrame()
    merged = pd.concat(frames, ignore_index=True)
    for col in key_cols:
        if col not in merged.columns:
            merged[col] = ""
    merged = merged.drop_duplicates(subset=key_cols, keep="last")
    sort_cols = [col for col in key_cols if col in merged.columns]
    return merged.sort_values(sort_cols).reset_index(drop=True)


def price_on_or_after(
    prices: pd.DataFrame,
    ticker: str,
    target_date: pd.Timestamp | str,
    *,
    max_date: pd.Timestamp | str | None = None,
) -> tuple[pd.Timestamp | None, float | None]:
    if prices.empty or ticker not in prices.columns:
        return None, None

    target = pd.Timestamp(target_date).normalize()
    latest = pd.Timestamp(max_date).normalize() if max_date is not None else None
    series = pd.to_numeric(prices[ticker], errors="coerce").dropna()
    if series.empty:
        return None, None

    index = pd.to_datetime(series.index).normalize()
    series.index = index
    mask = index >= target
    if latest is not None:
        mask &= index <= latest
    candidates = series.loc[mask]
    if candidates.empty:
        return None, None

    used_date = pd.Timestamp(candidates.index[0]).normalize()
    return used_date, float(candidates.iloc[0])


def evaluate_snapshots(
    snapshots: pd.DataFrame,
    prices: pd.DataFrame,
    *,
    as_of_date: date | str,
    horizons: Mapping[str, int] | None = None,
    min_coverage: float = 0.8,
    generated_at: pd.Timestamp | None = None,
) -> pd.DataFrame:
    if snapshots.empty or prices.empty:
        return empty_evaluations()

    horizons = dict(horizons or DEFAULT_HORIZONS)
    generated_at = generated_at or pd.Timestamp.now()
    as_of = pd.Timestamp(as_of_date).normalize()
    rows: list[dict[str, object]] = []

    clean = snapshots.copy()
    clean["Snapshot_Date"] = pd.to_datetime(clean["Snapshot_Date"], errors="coerce").dt.normalize()
    clean = clean.dropna(subset=["Snapshot_Date", "Market", "Ticker"])
    if clean.empty:
        return empty_evaluations()

    for (snapshot_date, market), group in clean.groupby(["Snapshot_Date", "Market"], dropna=True):
        benchmark = _clean_ticker(group["Benchmark_Ticker"].dropna().iloc[0] if "Benchmark_Ticker" in group else "")
        if not benchmark:
            continue

        for horizon_label, trading_days in horizons.items():
            target_date = snapshot_date + pd.tseries.offsets.BDay(int(trading_days))
            if target_date.normalize() > as_of:
                continue

            bench_start_date, bench_start = price_on_or_after(prices, benchmark, snapshot_date, max_date=as_of)
            bench_end_date, bench_end = price_on_or_after(prices, benchmark, target_date, max_date=as_of)
            if not bench_start or not bench_end:
                continue
            benchmark_return = bench_end / bench_start - 1.0

            stock_rows = []
            for _, snap in group.iterrows():
                ticker = _clean_ticker(snap.get("Ticker", ""))
                start_date, start_price = price_on_or_after(prices, ticker, snapshot_date, max_date=as_of)
                end_date, end_price = price_on_or_after(prices, ticker, target_date, max_date=as_of)
                if not start_price or not end_price:
                    continue
                stock_rows.append(
                    {
                        "Ticker": ticker,
                        "Return": end_price / start_price - 1.0,
                        "Weight": _float_or_zero(snap.get("Weight")),
                        "Sleeve_Weight": _float_or_zero(snap.get("Sleeve_Weight")),
                        "Total_Score": pd.to_numeric(pd.Series([snap.get("Total_Score")]), errors="coerce").iloc[0],
                        "End_Date": end_date,
                    }
                )

            valid = pd.DataFrame(stock_rows)
            coverage = len(valid) / max(len(group), 1)
            if valid.empty or coverage < min_coverage:
                continue

            sleeve_weights = valid["Sleeve_Weight"].clip(lower=0.0)
            if float(sleeve_weights.sum()) <= 0:
                sleeve_weights = pd.Series(1.0 / len(valid), index=valid.index)
            else:
                sleeve_weights = sleeve_weights / sleeve_weights.sum()

            actual_weights = valid["Weight"].clip(lower=0.0)
            equal_return = float(valid["Return"].mean())
            sleeve_return = float((valid["Return"] * sleeve_weights).sum())
            actual_return = float((valid["Return"] * actual_weights).sum())
            score_ic = np.nan
            if len(valid) >= 3 and valid["Total_Score"].nunique(dropna=True) > 1:
                score_ic = float(valid["Total_Score"].corr(valid["Return"], method="spearman"))

            evaluation_date = valid["End_Date"].max()
            if pd.isna(evaluation_date):
                evaluation_date = bench_end_date

            rows.append(
                {
                    "Snapshot_Date": snapshot_date.strftime("%Y-%m-%d"),
                    "Market": market,
                    "Horizon": horizon_label,
                    "Horizon_Trading_Days": int(trading_days),
                    "Target_Date": target_date.strftime("%Y-%m-%d"),
                    "Evaluation_Date": pd.Timestamp(evaluation_date).strftime("%Y-%m-%d"),
                    "Benchmark_Ticker": benchmark,
                    "N": int(len(valid)),
                    "Coverage": round(float(coverage), 4),
                    "Equal_Weight_Return": round(equal_return, 6),
                    "Sleeve_Weight_Return": round(sleeve_return, 6),
                    "Actual_Weight_Return": round(actual_return, 6),
                    "Benchmark_Return": round(float(benchmark_return), 6),
                    "Alpha_Equal": round(equal_return - benchmark_return, 6),
                    "Alpha_Sleeve": round(sleeve_return - benchmark_return, 6),
                    "Alpha_Actual": round(actual_return - benchmark_return, 6),
                    "Hit_Rate": round(float((valid["Return"] > benchmark_return).mean()), 6),
                    "Positive_Rate": round(float((valid["Return"] > 0).mean()), 6),
                    "Score_IC": round(score_ic, 6) if pd.notna(score_ic) else np.nan,
                    "Generated_At": generated_at.strftime("%Y-%m-%d %H:%M:%S"),
                }
            )

    if not rows:
        return empty_evaluations()
    return pd.DataFrame(rows, columns=empty_evaluations().columns)


def attribute_snapshots(
    snapshots: pd.DataFrame,
    prices: pd.DataFrame,
    *,
    as_of_date: date | str,
    horizons: Mapping[str, int] | None = None,
    min_coverage: float = 0.8,
    generated_at: pd.Timestamp | None = None,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    if snapshots.empty or prices.empty:
        return empty_attribution(), empty_sector_attribution(), empty_attribution_summary()

    horizons = dict(horizons or DEFAULT_HORIZONS)
    generated_at = generated_at or pd.Timestamp.now()
    generated = generated_at.strftime("%Y-%m-%d %H:%M:%S")
    as_of = pd.Timestamp(as_of_date).normalize()
    detail_rows: list[dict[str, object]] = []
    sector_rows: list[dict[str, object]] = []
    summary_rows: list[dict[str, object]] = []

    clean = snapshots.copy()
    clean["Snapshot_Date"] = pd.to_datetime(clean["Snapshot_Date"], errors="coerce").dt.normalize()
    clean = clean.dropna(subset=["Snapshot_Date", "Market", "Ticker"])
    if clean.empty:
        return empty_attribution(), empty_sector_attribution(), empty_attribution_summary()

    for (snapshot_date, market), group in clean.groupby(["Snapshot_Date", "Market"], dropna=True):
        benchmark = _clean_ticker(group["Benchmark_Ticker"].dropna().iloc[0] if "Benchmark_Ticker" in group else "")
        if not benchmark:
            continue

        for horizon_label, trading_days in horizons.items():
            target_date = snapshot_date + pd.tseries.offsets.BDay(int(trading_days))
            if target_date.normalize() > as_of:
                continue

            bench_start_date, bench_start = price_on_or_after(prices, benchmark, snapshot_date, max_date=as_of)
            bench_end_date, bench_end = price_on_or_after(prices, benchmark, target_date, max_date=as_of)
            if not bench_start or not bench_end:
                continue
            benchmark_return = bench_end / bench_start - 1.0

            stock_rows = []
            for _, snap in group.iterrows():
                ticker = _clean_ticker(snap.get("Ticker", ""))
                start_date, start_price = price_on_or_after(prices, ticker, snapshot_date, max_date=as_of)
                end_date, end_price = price_on_or_after(prices, ticker, target_date, max_date=as_of)
                if not start_price or not end_price:
                    continue
                stock_return = end_price / start_price - 1.0
                weight = _float_or_zero(snap.get("Weight"))
                sleeve_weight = _float_or_zero(snap.get("Sleeve_Weight"))
                equal_weight = _float_or_zero(snap.get("Equal_Weight"))
                stock_rows.append(
                    {
                        "Snapshot_Date": snapshot_date.strftime("%Y-%m-%d"),
                        "Market": market,
                        "Horizon": horizon_label,
                        "Horizon_Trading_Days": int(trading_days),
                        "Target_Date": target_date.strftime("%Y-%m-%d"),
                        "Evaluation_Date": pd.Timestamp(end_date).strftime("%Y-%m-%d"),
                        "Benchmark_Ticker": benchmark,
                        "Ticker": ticker,
                        "Name": snap.get("Name", ""),
                        "Sector": _clean_sector(snap.get("Sector", "")),
                        "Rank": snap.get("Rank", ""),
                        "Weight": weight,
                        "Sleeve_Weight": sleeve_weight,
                        "Equal_Weight": equal_weight,
                        "Total_Score": pd.to_numeric(pd.Series([snap.get("Total_Score")]), errors="coerce").iloc[0],
                        "Start_Price": float(start_price),
                        "End_Price": float(end_price),
                        "Stock_Return": float(stock_return),
                        "Benchmark_Return": float(benchmark_return),
                        "Actual_Contribution": float(weight * stock_return),
                        "Sleeve_Contribution": float(sleeve_weight * stock_return),
                        "Equal_Contribution": float(equal_weight * stock_return),
                        "Benchmark_Contribution": float(weight * benchmark_return),
                        "Excess_Contribution": float(weight * (stock_return - benchmark_return)),
                        "Hit_Benchmark": bool(stock_return > benchmark_return),
                        "Generated_At": generated,
                    }
                )

            valid = pd.DataFrame(stock_rows)
            coverage = len(valid) / max(len(group), 1)
            if valid.empty or coverage < min_coverage:
                continue

            detail_rows.extend(_round_detail_rows(valid))
            sector = _sector_attribution(valid, coverage=coverage, generated=generated)
            sector_rows.extend(sector.to_dict(orient="records"))
            summary_rows.append(_attribution_summary(valid, sector, coverage=coverage, generated=generated))

    detail = pd.DataFrame(detail_rows, columns=empty_attribution().columns) if detail_rows else empty_attribution()
    sectors = pd.DataFrame(sector_rows, columns=empty_sector_attribution().columns) if sector_rows else empty_sector_attribution()
    summary = pd.DataFrame(summary_rows, columns=empty_attribution_summary().columns) if summary_rows else empty_attribution_summary()
    return detail, sectors, summary


def summarize_evaluations(evaluations: pd.DataFrame, *, generated_at: pd.Timestamp | None = None) -> pd.DataFrame:
    generated_at = generated_at or pd.Timestamp.now()
    if evaluations.empty:
        return pd.DataFrame(
            columns=[
                "Market",
                "Horizon",
                "Evaluations",
                "Mean_Equal_Return",
                "Mean_Sleeve_Return",
                "Mean_Actual_Return",
                "Mean_Benchmark_Return",
                "Mean_Coverage",
                "Mean_Alpha_Equal",
                "Mean_Alpha_Sleeve",
                "Mean_Alpha_Actual",
                "Median_Alpha_Actual",
                "Alpha_Actual_Win_Rate",
                "Mean_Hit_Rate",
                "Mean_Positive_Rate",
                "Mean_Score_IC",
                "Latest_Evaluation_Date",
                "Generated_At",
            ]
        )

    numeric_cols = [
        "Coverage",
        "Equal_Weight_Return",
        "Sleeve_Weight_Return",
        "Actual_Weight_Return",
        "Benchmark_Return",
        "Alpha_Equal",
        "Alpha_Sleeve",
        "Alpha_Actual",
        "Hit_Rate",
        "Positive_Rate",
        "Score_IC",
    ]
    clean = evaluations.copy()
    for col in numeric_cols:
        if col not in clean.columns:
            clean[col] = np.nan
        if col in clean.columns:
            clean[col] = pd.to_numeric(clean[col], errors="coerce")
    if "Evaluation_Date" not in clean.columns:
        clean["Evaluation_Date"] = pd.NaT
    clean["Evaluation_Date"] = pd.to_datetime(clean["Evaluation_Date"], errors="coerce")

    summary = (
        clean.groupby(["Market", "Horizon"], dropna=False)
        .agg(
            Evaluations=("Snapshot_Date", "count"),
            Mean_Equal_Return=("Equal_Weight_Return", "mean"),
            Mean_Sleeve_Return=("Sleeve_Weight_Return", "mean"),
            Mean_Actual_Return=("Actual_Weight_Return", "mean"),
            Mean_Benchmark_Return=("Benchmark_Return", "mean"),
            Mean_Coverage=("Coverage", "mean"),
            Mean_Alpha_Equal=("Alpha_Equal", "mean"),
            Mean_Alpha_Sleeve=("Alpha_Sleeve", "mean"),
            Mean_Alpha_Actual=("Alpha_Actual", "mean"),
            Median_Alpha_Actual=("Alpha_Actual", "median"),
            Alpha_Actual_Win_Rate=("Alpha_Actual", lambda s: float((s > 0).mean())),
            Mean_Hit_Rate=("Hit_Rate", "mean"),
            Mean_Positive_Rate=("Positive_Rate", "mean"),
            Mean_Score_IC=("Score_IC", "mean"),
            Latest_Evaluation_Date=("Evaluation_Date", "max"),
        )
        .reset_index()
    )
    for col in summary.columns:
        if col.startswith("Mean_") or col in ("Median_Alpha_Actual", "Alpha_Actual_Win_Rate"):
            summary[col] = summary[col].round(6)
    summary["Latest_Evaluation_Date"] = summary["Latest_Evaluation_Date"].dt.strftime("%Y-%m-%d")
    summary["Generated_At"] = generated_at.strftime("%Y-%m-%d %H:%M:%S")
    return summary


def _clean_sector(value: object) -> str:
    text = str(value or "").strip()
    if not text or text.lower() in {"nan", "none", "unknown"}:
        return "Unknown"
    return text


def _round_detail_rows(valid: pd.DataFrame) -> list[dict[str, object]]:
    out = valid.copy()
    for col in [
        "Weight",
        "Sleeve_Weight",
        "Equal_Weight",
        "Total_Score",
        "Start_Price",
        "End_Price",
        "Stock_Return",
        "Benchmark_Return",
        "Actual_Contribution",
        "Sleeve_Contribution",
        "Equal_Contribution",
        "Benchmark_Contribution",
        "Excess_Contribution",
    ]:
        if col in out.columns:
            out[col] = pd.to_numeric(out[col], errors="coerce").round(6)
    out["Hit_Benchmark"] = out["Hit_Benchmark"].map(lambda value: "Y" if bool(value) else "N")
    return out[empty_attribution().columns].to_dict(orient="records")


def _sector_attribution(valid: pd.DataFrame, *, coverage: float, generated: str) -> pd.DataFrame:
    grouped = valid.groupby("Sector", dropna=False)
    rows = []
    for sector, group in grouped:
        sector_weight = float(group["Weight"].sum())
        sector_sleeve = float(group["Sleeve_Weight"].sum())
        actual_contribution = float(group["Actual_Contribution"].sum())
        sleeve_contribution = float(group["Sleeve_Contribution"].sum())
        benchmark_contribution = float(group["Benchmark_Contribution"].sum())
        excess_contribution = float(group["Excess_Contribution"].sum())
        weighted_return = actual_contribution / sector_weight if sector_weight > 0 else np.nan
        rows.append(
            {
                "Snapshot_Date": group["Snapshot_Date"].iloc[0],
                "Market": group["Market"].iloc[0],
                "Horizon": group["Horizon"].iloc[0],
                "Horizon_Trading_Days": int(group["Horizon_Trading_Days"].iloc[0]),
                "Target_Date": group["Target_Date"].iloc[0],
                "Evaluation_Date": group["Evaluation_Date"].max(),
                "Sector": sector,
                "Holdings": int(len(group)),
                "Coverage": round(float(coverage), 6),
                "Sector_Weight": round(sector_weight, 6),
                "Sector_Sleeve_Weight": round(sector_sleeve, 6),
                "Mean_Return": round(float(group["Stock_Return"].mean()), 6),
                "Weighted_Return": round(float(weighted_return), 6) if pd.notna(weighted_return) else np.nan,
                "Benchmark_Return": round(float(group["Benchmark_Return"].iloc[0]), 6),
                "Actual_Contribution": round(actual_contribution, 6),
                "Sleeve_Contribution": round(sleeve_contribution, 6),
                "Benchmark_Contribution": round(benchmark_contribution, 6),
                "Excess_Contribution": round(excess_contribution, 6),
                "Hit_Rate": round(float(group["Hit_Benchmark"].mean()), 6),
                "Generated_At": generated,
            }
        )
    if not rows:
        return empty_sector_attribution()
    return pd.DataFrame(rows, columns=empty_sector_attribution().columns).sort_values(
        "Actual_Contribution", ascending=False
    )


def _attribution_summary(valid: pd.DataFrame, sectors: pd.DataFrame, *, coverage: float, generated: str) -> dict[str, object]:
    actual_return = float(valid["Actual_Contribution"].sum())
    benchmark_return = float(valid["Benchmark_Return"].iloc[0])
    invested_weight = float(valid["Weight"].sum())
    cash_weight = max(0.0, 1.0 - invested_weight)
    stock_excess = float(valid["Excess_Contribution"].sum())
    cash_opportunity_cost = -cash_weight * benchmark_return
    explained_alpha = stock_excess + cash_opportunity_cost
    alpha = actual_return - benchmark_return

    top = valid.sort_values("Actual_Contribution", ascending=False).iloc[0]
    worst = valid.sort_values("Actual_Contribution", ascending=True).iloc[0]
    top_sector = sectors.sort_values("Actual_Contribution", ascending=False).iloc[0] if not sectors.empty else None
    worst_sector = sectors.sort_values("Actual_Contribution", ascending=True).iloc[0] if not sectors.empty else None
    score_ic = np.nan
    score = pd.to_numeric(valid["Total_Score"], errors="coerce")
    stock_return = pd.to_numeric(valid["Stock_Return"], errors="coerce")
    if len(valid) >= 3 and score.nunique(dropna=True) > 1 and stock_return.nunique(dropna=True) > 1:
        score_ic = float(score.corr(stock_return, method="spearman"))

    return {
        "Snapshot_Date": valid["Snapshot_Date"].iloc[0],
        "Market": valid["Market"].iloc[0],
        "Horizon": valid["Horizon"].iloc[0],
        "Horizon_Trading_Days": int(valid["Horizon_Trading_Days"].iloc[0]),
        "Target_Date": valid["Target_Date"].iloc[0],
        "Evaluation_Date": valid["Evaluation_Date"].max(),
        "Benchmark_Ticker": valid["Benchmark_Ticker"].iloc[0],
        "Holdings": int(len(valid)),
        "Coverage": round(float(coverage), 6),
        "Invested_Weight": round(invested_weight, 6),
        "Cash_Weight": round(cash_weight, 6),
        "Actual_Return": round(actual_return, 6),
        "Benchmark_Return": round(benchmark_return, 6),
        "Alpha_Actual": round(alpha, 6),
        "Stock_Excess_Contribution": round(stock_excess, 6),
        "Cash_Opportunity_Cost": round(cash_opportunity_cost, 6),
        "Explained_Alpha": round(explained_alpha, 6),
        "Top_Contributor": top["Ticker"],
        "Top_Contribution": round(float(top["Actual_Contribution"]), 6),
        "Worst_Contributor": worst["Ticker"],
        "Worst_Contribution": round(float(worst["Actual_Contribution"]), 6),
        "Top_Sector": "" if top_sector is None else top_sector["Sector"],
        "Top_Sector_Contribution": np.nan if top_sector is None else round(float(top_sector["Actual_Contribution"]), 6),
        "Worst_Sector": "" if worst_sector is None else worst_sector["Sector"],
        "Worst_Sector_Contribution": np.nan if worst_sector is None else round(float(worst_sector["Actual_Contribution"]), 6),
        "Hit_Rate": round(float(valid["Hit_Benchmark"].mean()), 6),
        "Positive_Rate": round(float((valid["Stock_Return"] > 0).mean()), 6),
        "Score_Return_IC": round(score_ic, 6) if pd.notna(score_ic) else np.nan,
        "Generated_At": generated,
    }
