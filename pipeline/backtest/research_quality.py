"""Research-quality diagnostics for backtests."""

from __future__ import annotations

import math

import numpy as np
import pandas as pd


def performance_stats(returns: pd.Series, *, periods_per_year: float, rf_per_period: float = 0.0) -> dict:
    returns = pd.Series(returns, dtype="float64").dropna()
    if returns.empty:
        return {
            "Total_Return": np.nan,
            "CAGR": np.nan,
            "Ann_Volatility": np.nan,
            "Sharpe": np.nan,
            "Max_Drawdown": np.nan,
            "Win_Rate": np.nan,
            "Avg_Period_Return": np.nan,
            "Periods": 0,
        }

    cumret = (1.0 + returns).cumprod()
    dd = (cumret / cumret.cummax()) - 1.0
    n = len(returns)
    total = float(cumret.iloc[-1] - 1.0)
    vol = float(returns.std() * math.sqrt(periods_per_year))
    sharpe = float((returns.mean() - rf_per_period) / (returns.std() + 1e-9) * math.sqrt(periods_per_year))
    cagr = float((1.0 + returns).prod() ** (periods_per_year / n) - 1.0)
    return {
        "Total_Return": total,
        "CAGR": cagr,
        "Ann_Volatility": vol,
        "Sharpe": sharpe,
        "Max_Drawdown": float(dd.min()),
        "Win_Rate": float((returns > 0).mean()),
        "Avg_Period_Return": float(returns.mean()),
        "Periods": int(n),
    }


def split_in_out_sample(returns: pd.Series, *, train_fraction: float = 0.70) -> tuple[pd.Series, pd.Series]:
    returns = pd.Series(returns, dtype="float64").dropna()
    if len(returns) < 4:
        return returns.copy(), returns.iloc[0:0].copy()
    split_idx = max(1, min(len(returns) - 1, int(len(returns) * train_fraction)))
    return returns.iloc[:split_idx].copy(), returns.iloc[split_idx:].copy()


def bootstrap_sharpe_ci(
    returns: pd.Series,
    *,
    periods_per_year: float,
    rf_per_period: float = 0.0,
    samples: int = 1000,
    seed: int = 42,
) -> dict:
    returns = pd.Series(returns, dtype="float64").dropna()
    if len(returns) < 4:
        return {"Sharpe_CI_Low": np.nan, "Sharpe_CI_High": np.nan, "Prob_Sharpe_GT_0": np.nan}

    rng = np.random.default_rng(seed)
    vals = np.empty(samples)
    arr = returns.to_numpy()
    for i in range(samples):
        draw = pd.Series(rng.choice(arr, size=len(arr), replace=True))
        vals[i] = performance_stats(draw, periods_per_year=periods_per_year, rf_per_period=rf_per_period)["Sharpe"]

    return {
        "Sharpe_CI_Low": float(np.nanpercentile(vals, 5)),
        "Sharpe_CI_High": float(np.nanpercentile(vals, 95)),
        "Prob_Sharpe_GT_0": float(np.nanmean(vals > 0)),
    }


def offset_sensitivity_table(rows: list[dict]) -> pd.DataFrame:
    if not rows:
        return pd.DataFrame(columns=["Offset_Days", "CAGR", "Sharpe", "Max_Drawdown", "Periods", "Robust"])
    df = pd.DataFrame(rows).sort_values("Offset_Days").reset_index(drop=True)
    valid = df["Sharpe"].dropna()
    if valid.empty:
        df["Robust"] = "FALSE"
        return df
    median_sharpe = valid.median()
    df["Robust"] = np.where(df["Sharpe"] >= median_sharpe * 0.75, "TRUE", "FALSE")
    return df
