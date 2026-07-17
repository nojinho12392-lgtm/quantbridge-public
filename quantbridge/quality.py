"""Data quality checks for QuantBridge app-facing datasets."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Callable

import pandas as pd

from quantbridge.schemas import SHEET_SCHEMAS


@dataclass(frozen=True)
class DatasetSpec:
    name: str
    market: str | None = None
    min_rows: int = 1
    max_age_days: int = 7
    optional_columns: tuple[str, ...] = ()


CORE_DATASETS = [
    DatasetSpec("US_Universe", market="US", min_rows=50, max_age_days=14),
    DatasetSpec("KR_Universe", market="KR", min_rows=50, max_age_days=14),
    DatasetSpec("US_Scored_Stocks", market="US", min_rows=20, max_age_days=7),
    DatasetSpec("KR_Scored_Stocks", market="KR", min_rows=20, max_age_days=7),
    DatasetSpec("US_Final_Portfolio", market="US", min_rows=1, max_age_days=7),
    DatasetSpec("KR_Final_Portfolio", market="KR", min_rows=1, max_age_days=7),
    DatasetSpec("US_SmallCap_Gems", market="US", min_rows=1, max_age_days=14, optional_columns=("Data_Confidence",)),
    DatasetSpec("KR_SmallCap_Gems", market="KR", min_rows=1, max_age_days=14, optional_columns=("Data_Confidence",)),
]

STATUS_ORDER = {"OK": 0, "WARN": 1, "FAIL": 2}


def _status_max(*statuses: str) -> str:
    if not statuses:
        return "OK"
    return max((status.upper() for status in statuses), key=lambda status: STATUS_ORDER.get(status, 3))


def _check(name: str, status: str, message: str, detail: dict | None = None) -> dict:
    return {
        "name": name,
        "status": status,
        "message": message,
        "detail": detail or {},
    }


def _clean_frame(df: pd.DataFrame) -> pd.DataFrame:
    if df is None or df.empty:
        return pd.DataFrame()
    return df.copy().astype(object).where(pd.notna(df), None)


def _number_series(df: pd.DataFrame, column: str) -> pd.Series:
    if column not in df.columns:
        return pd.Series(dtype="float64")
    return pd.to_numeric(df[column], errors="coerce")


def _latest_age_days(df: pd.DataFrame, column: str = "Last_Updated") -> tuple[float | None, str | None]:
    if column not in df.columns or df.empty:
        return None, None
    parsed = pd.to_datetime(df[column], errors="coerce", utc=True)
    latest = parsed.max()
    if pd.isna(latest):
        return None, None
    now = pd.Timestamp.now(tz=timezone.utc)
    age = round((now - latest).total_seconds() / 86400.0, 3)
    return age, latest.isoformat()


def _required_column_check(dataset: str, df: pd.DataFrame, optional_columns: tuple[str, ...] = ()) -> dict:
    expected = SHEET_SCHEMAS.get(dataset, [])
    optional = set(optional_columns)
    missing_required = [column for column in expected if column not in df.columns and column not in optional]
    missing_optional = [column for column in expected if column not in df.columns and column in optional]
    if missing_required:
        return _check(
            "Required columns",
            "FAIL",
            f"Missing {len(missing_required)} required columns.",
            {
                "missing": missing_required,
                "missing_optional": missing_optional,
                "expected": expected,
                "actual": list(df.columns),
            },
        )
    if missing_optional:
        return _check(
            "Required columns",
            "WARN",
            f"Missing {len(missing_optional)} optional columns.",
            {"missing_optional": missing_optional, "expected": expected, "actual": list(df.columns)},
        )
    return _check("Required columns", "OK", f"{len(expected)} required columns present.", {"expected": expected})


def _row_count_check(df: pd.DataFrame, min_rows: int) -> dict:
    rows = len(df)
    status = "OK" if rows >= min_rows else "FAIL"
    return _check(
        "Row count",
        status,
        f"{rows} rows available; minimum is {min_rows}.",
        {"rows": rows, "min_rows": min_rows},
    )


def _ticker_checks(df: pd.DataFrame) -> list[dict]:
    if "Ticker" not in df.columns:
        return []
    tickers = df["Ticker"].fillna("").astype(str).str.strip()
    blank_count = int((tickers == "").sum())
    duplicate_count = int(tickers[tickers != ""].duplicated().sum())
    checks = [
        _check(
            "Ticker completeness",
            "FAIL" if blank_count else "OK",
            f"{blank_count} blank tickers.",
            {"blank_count": blank_count},
        ),
        _check(
            "Ticker uniqueness",
            "FAIL" if duplicate_count else "OK",
            f"{duplicate_count} duplicate tickers.",
            {"duplicate_count": duplicate_count},
        ),
    ]
    return checks


def _market_check(df: pd.DataFrame, expected_market: str | None) -> dict | None:
    if not expected_market or "Market" not in df.columns or df.empty:
        return None
    values = {str(value).strip().upper() for value in df["Market"].dropna() if str(value).strip()}
    invalid = sorted(value for value in values if value != expected_market.upper())
    return _check(
        "Market consistency",
        "FAIL" if invalid else "OK",
        f"Expected {expected_market}; invalid markets={invalid}.",
        {"expected": expected_market, "values": sorted(values), "invalid": invalid},
    )


def _freshness_check(df: pd.DataFrame, max_age_days: int) -> dict:
    age, latest = _latest_age_days(df)
    if age is None:
        return _check(
            "Freshness",
            "WARN",
            "Last_Updated is missing or cannot be parsed.",
            {"max_age_days": max_age_days},
        )
    if age <= max_age_days:
        status = "OK"
    elif age <= max_age_days * 2:
        status = "WARN"
    else:
        status = "FAIL"
    return _check(
        "Freshness",
        status,
        f"Latest Last_Updated is {age:.1f} days old.",
        {"age_days": age, "latest": latest, "max_age_days": max_age_days},
    )


def _numeric_range_check(df: pd.DataFrame, column: str, low: float | None = None, high: float | None = None) -> dict | None:
    if column not in df.columns or df.empty:
        return None
    values = _number_series(df, column)
    present = values.dropna()
    if present.empty:
        return _check(column, "WARN", "No numeric values found.", {"column": column})
    invalid = pd.Series(False, index=present.index)
    if low is not None:
        invalid = invalid | (present < low)
    if high is not None:
        invalid = invalid | (present > high)
    invalid_count = int(invalid.sum())
    return _check(
        f"{column} range",
        "FAIL" if invalid_count else "OK",
        f"{invalid_count} values outside expected range.",
        {
            "column": column,
            "min": float(present.min()),
            "max": float(present.max()),
            "low": low,
            "high": high,
            "invalid_count": invalid_count,
        },
    )


def _portfolio_weight_check(df: pd.DataFrame) -> dict | None:
    if "Weight(%)" not in df.columns or df.empty:
        return None
    weights = _number_series(df, "Weight(%)").dropna()
    if weights.empty:
        return _check("Portfolio weight sum", "WARN", "No numeric weights found.", {})
    total = round(float(weights.sum()), 4)
    if 0.25 <= total <= 1.05:
        status = "OK"
    elif 0 < total <= 1.10:
        status = "WARN"
    else:
        status = "FAIL"
    return _check(
        "Portfolio weight sum",
        status,
        f"Invested weight sum is {total:.2%}.",
        {"sum_weight_decimal": total, "rows_with_weight": int(len(weights))},
    )


def evaluate_dataset(spec: DatasetSpec, df: pd.DataFrame, max_age_days: int | None = None) -> dict:
    frame = _clean_frame(df)
    checks: list[dict] = [
        _row_count_check(frame, spec.min_rows),
        _required_column_check(spec.name, frame, optional_columns=spec.optional_columns),
    ]
    checks.extend(_ticker_checks(frame))
    market_check = _market_check(frame, spec.market)
    if market_check:
        checks.append(market_check)
    checks.append(_freshness_check(frame, max_age_days or spec.max_age_days))

    for rule in [
        ("Rank", 1, None),
        ("MarketCap", 0, None),
        ("Total_Score", 0, None),
        ("Final_Score", 0, None),
        ("Score_Neutral", None, None),
        ("Combined_Score", 0, None),
        ("Profitability_Quality", 0, 1),
        ("Cash_Quality", 0, 1),
        ("Growth_Quality", 0, 1),
        ("BalanceSheet_Strength", 0, 1),
        ("Valuation_Discipline", 0, 1),
        ("Timing_Overlay", 0, 1),
        ("Persistence_Quality", 0, 1),
        ("Business_Quality_Score", 0, 1),
        ("Investability_Score", 0, 1),
        ("Quality_Data_Confidence", 0, 1),
        ("Investability_Rank", 1, None),
        ("Business_Quality_Rank", 1, None),
        ("Quality_Rank_Delta", None, None),
        ("Data_Confidence", 0, 1),
        ("SmallCap_Bonus", 0, 15),
        ("Weight(%)", 0, 1),
    ]:
        check = _numeric_range_check(frame, rule[0], rule[1], rule[2])
        if check:
            checks.append(check)

    weight_check = _portfolio_weight_check(frame)
    if weight_check:
        checks.append(weight_check)

    status = _status_max(*(check["status"] for check in checks))
    return {
        "dataset": spec.name,
        "market": spec.market,
        "status": status,
        "rows": len(frame),
        "columns": list(frame.columns),
        "checks": checks,
    }


def build_data_quality_report(
    load_dataset: Callable[[str, str | None], pd.DataFrame],
    specs: list[DatasetSpec] | None = None,
    max_age_days: int | None = None,
) -> dict:
    dataset_reports: list[dict] = []
    for spec in specs or CORE_DATASETS:
        try:
            df = load_dataset(spec.name, spec.market)
        except Exception as exc:
            dataset_reports.append({
                "dataset": spec.name,
                "market": spec.market,
                "status": "FAIL",
                "rows": 0,
                "columns": [],
                "checks": [_check("Dataset load", "FAIL", f"{type(exc).__name__}: {exc}")],
            })
            continue
        dataset_reports.append(evaluate_dataset(spec, df, max_age_days=max_age_days))

    counts: dict[str, int] = {}
    for report in dataset_reports:
        status = str(report.get("status") or "UNKNOWN").upper()
        counts[status] = counts.get(status, 0) + 1
    overall = _status_max(*(str(report.get("status") or "UNKNOWN") for report in dataset_reports))
    return {
        "healthy": overall == "OK",
        "status": "DEGRADED" if overall == "WARN" else overall,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "status_counts": counts,
        "datasets": dataset_reports,
    }
