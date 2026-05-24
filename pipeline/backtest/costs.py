from __future__ import annotations

import math
from typing import Mapping

import pandas as pd


def liquidity_slippage_rate(
    *,
    market: str,
    market_cap: object = None,
    trading_value_20d: object = None,
) -> float:
    """One-way slippage estimate from liquidity proxies."""
    market_name = str(market).upper()
    mcap = _to_float(market_cap)
    trading_value = _to_float(trading_value_20d)

    if market_name == "KR":
        if trading_value is not None:
            if trading_value >= 5_000_000_000:
                return 0.0005
            if trading_value >= 1_000_000_000:
                return 0.0010
            if trading_value >= 200_000_000:
                return 0.0025
            return 0.0060
        if mcap is not None:
            if mcap >= 10_000_000_000_000:
                return 0.0005
            if mcap >= 1_000_000_000_000:
                return 0.0010
            if mcap >= 300_000_000_000:
                return 0.0025
            return 0.0060
        return 0.0030

    if mcap is not None:
        if mcap >= 50_000_000_000:
            return 0.0005
        if mcap >= 10_000_000_000:
            return 0.0010
        if mcap >= 2_000_000_000:
            return 0.0020
        return 0.0050
    return 0.0030


def equal_weight_turnover_costs(
    *,
    previous: set[str],
    current: set[str],
    fee_rate: float,
    slippage_rates: Mapping[str, float] | pd.Series | None = None,
) -> dict[str, float]:
    prev_weight = 1.0 / len(previous) if previous else 0.0
    curr_weight = 1.0 / len(current) if current else 0.0
    previous_weights = {ticker: prev_weight for ticker in previous}
    current_weights = {ticker: curr_weight for ticker in current}
    return weighted_turnover_costs(
        previous_weights=previous_weights,
        current_weights=current_weights,
        fee_rate=fee_rate,
        slippage_rates=slippage_rates,
    )


def weighted_turnover_costs(
    *,
    previous_weights: Mapping[str, float],
    current_weights: Mapping[str, float],
    fee_rate: float,
    slippage_rates: Mapping[str, float] | pd.Series | None = None,
) -> dict[str, float]:
    names = set(previous_weights) | set(current_weights)
    turnover_by_name = {
        ticker: abs(
            (_to_float(current_weights.get(ticker)) or 0.0)
            - (_to_float(previous_weights.get(ticker)) or 0.0)
        )
        for ticker in names
    }
    turnover = float(sum(turnover_by_name.values()))
    fee_cost = turnover * float(fee_rate)
    slippage = slippage_rates if slippage_rates is not None else {}
    slippage_cost = 0.0
    for ticker, trade_weight in turnover_by_name.items():
        rate = _to_float(slippage.get(ticker) if hasattr(slippage, "get") else None)
        slippage_cost += trade_weight * (rate if rate is not None else 0.0030)
    return {
        "turnover": turnover,
        "fee": fee_cost,
        "slippage": float(slippage_cost),
        "total": float(fee_cost + slippage_cost),
    }


def _to_float(value: object) -> float | None:
    try:
        if value is None or pd.isna(value):
            return None
        value_f = float(value)
        if not math.isfinite(value_f):
            return None
        return value_f
    except Exception:
        return None
