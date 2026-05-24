from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
import math
from typing import Iterable, Mapping

import numpy as np
import pandas as pd


@dataclass(frozen=True)
class PortfolioRiskConfig:
    max_position_weight: float = 0.10
    max_sector_weight: float = 0.30
    max_illiquid_weight: float = 0.20
    candidate_pool_multiplier: float = 2.0
    sell_rank_multiplier: float = 1.5
    max_turnover_fraction: float = 0.50
    us_illiquid_market_cap: float = 2_000_000_000
    kr_illiquid_market_cap: float = 300_000_000_000
    kr_illiquid_trading_value_20d: float = 1_000_000_000


def risk_controlled_selection(
    scored: pd.DataFrame,
    *,
    target_n: int,
    market: str,
    previous_holdings: Iterable[str] | None = None,
    score_col: str = "Total_Score",
    config: PortfolioRiskConfig | None = None,
) -> list[str]:
    config = config or PortfolioRiskConfig()
    if scored.empty or target_n <= 0 or score_col not in scored.columns:
        return []

    ranked = scored.copy()
    ranked[score_col] = pd.to_numeric(ranked[score_col], errors="coerce")
    ranked = ranked.dropna(subset=[score_col]).sort_values(score_col, ascending=False)
    if ranked.empty:
        return []

    pool_n = max(target_n, int(math.ceil(target_n * config.candidate_pool_multiplier)))
    pool = ranked.head(pool_n).copy()
    pool["_rank"] = np.arange(1, len(pool) + 1)

    previous = {str(ticker).strip() for ticker in (previous_holdings or []) if str(ticker).strip()}
    max_sector_count = max(1, int(math.floor(target_n * config.max_sector_weight)))
    max_illiquid_count = max(0, int(math.floor(target_n * config.max_illiquid_weight)))
    sell_rank = max(target_n, int(math.ceil(target_n * config.sell_rank_multiplier)))

    selected: list[str] = []
    sector_counts: Counter[str] = Counter()
    illiquid_count = 0

    def can_add(ticker: str) -> bool:
        nonlocal illiquid_count
        if ticker in selected or ticker not in pool.index:
            return False
        row = pool.loc[ticker]
        sector = _sector(row)
        if sector and sector_counts[sector] >= max_sector_count:
            return False
        if _is_illiquid(row, market=market, config=config) and illiquid_count >= max_illiquid_count:
            return False
        return True

    def add(ticker: str) -> bool:
        nonlocal illiquid_count
        if not can_add(ticker):
            return False
        row = pool.loc[ticker]
        sector = _sector(row)
        if sector:
            sector_counts[sector] += 1
        if _is_illiquid(row, market=market, config=config):
            illiquid_count += 1
        selected.append(ticker)
        return True

    keepers = [
        ticker
        for ticker in previous
        if ticker in pool.index and int(pool.loc[ticker, "_rank"]) <= sell_rank
    ]
    for ticker in sorted(keepers, key=lambda t: int(pool.loc[t, "_rank"])):
        if len(selected) >= target_n:
            break
        add(ticker)

    for ticker in pool.index:
        if len(selected) >= target_n:
            break
        add(str(ticker))

    selected = _limit_replacements(
        selected,
        pool,
        previous=previous,
        target_n=target_n,
        market=market,
        config=config,
    )
    if len(selected) < target_n:
        selected = _relaxed_fill(selected, pool, target_n)
    return selected[:target_n]


def apply_weight_limits(
    weights: pd.Series | Mapping[str, float],
    metadata: pd.DataFrame,
    *,
    market: str,
    config: PortfolioRiskConfig | None = None,
) -> pd.Series:
    config = config or PortfolioRiskConfig()
    w = pd.Series(weights, dtype=float).replace([np.inf, -np.inf], np.nan).dropna()
    w = w[w > 0]
    if w.empty:
        return w
    w = w / w.sum()
    meta = metadata.reindex(w.index) if metadata is not None and not metadata.empty else pd.DataFrame(index=w.index)

    for _ in range(4):
        w = _cap_positions(w, config.max_position_weight)
        sectors = meta.apply(_sector, axis=1) if not meta.empty else pd.Series(index=w.index, dtype=object)
        w = _cap_group(w, sectors, config.max_sector_weight, config.max_position_weight)
        illiquid = meta.apply(lambda row: _is_illiquid(row, market=market, config=config), axis=1) if not meta.empty else pd.Series(False, index=w.index)
        w = _cap_boolean_group(w, illiquid, config.max_illiquid_weight, config.max_position_weight)
    return w / w.sum()


def risk_limit_summary(weights: pd.Series, metadata: pd.DataFrame, *, market: str, config: PortfolioRiskConfig | None = None) -> dict[str, float]:
    config = config or PortfolioRiskConfig()
    if weights.empty:
        return {"max_position": 0.0, "max_sector": 0.0, "illiquid_weight": 0.0}
    w = weights / weights.sum()
    meta = metadata.reindex(w.index) if metadata is not None and not metadata.empty else pd.DataFrame(index=w.index)
    sectors = meta.apply(_sector, axis=1) if not meta.empty else pd.Series(index=w.index, dtype=object)
    sector_weights = w.groupby(sectors.fillna("__UNCAPPED__")).sum()
    illiquid = meta.apply(lambda row: _is_illiquid(row, market=market, config=config), axis=1) if not meta.empty else pd.Series(False, index=w.index)
    return {
        "max_position": float(w.max()),
        "max_sector": float(sector_weights.max()) if not sector_weights.empty else 0.0,
        "illiquid_weight": float(w[illiquid.reindex(w.index).fillna(False)].sum()),
    }


def estimate_portfolio_volatility(
    returns: pd.DataFrame,
    weights: pd.Series | Mapping[str, float],
    *,
    periods_per_year: int = 252,
    min_observations: int = 30,
) -> float | None:
    if returns is None or returns.empty:
        return None
    w = pd.Series(weights, dtype=float).replace([np.inf, -np.inf], np.nan).dropna()
    w = w[w > 0]
    cols = [ticker for ticker in w.index if ticker in returns.columns]
    if not cols:
        return None
    aligned = returns[cols].apply(pd.to_numeric, errors="coerce").dropna()
    if len(aligned) < min_observations:
        return None
    w = w.reindex(cols).fillna(0.0)
    if float(w.sum()) <= 0:
        return None
    w = w / w.sum()
    port = aligned @ w
    vol = float(port.std(ddof=1) * math.sqrt(periods_per_year))
    return vol if math.isfinite(vol) and vol > 0 else None


def volatility_target_scalar(
    portfolio_vol: float | None,
    *,
    target_vol: float,
    max_invested: float = 1.0,
    min_invested: float = 0.0,
) -> float:
    if target_vol <= 0:
        return float(max(0.0, min(max_invested, 1.0)))
    vol = _to_float(portfolio_vol)
    if vol is None or vol <= 0:
        return float(max(0.0, min(max_invested, 1.0)))
    scalar = min(max_invested, target_vol / vol)
    return float(max(min_invested, min(max_invested, scalar)))


def period_cash_return(annual_rate: float, start: pd.Timestamp | str, end: pd.Timestamp | str) -> float:
    try:
        days = max((pd.Timestamp(end) - pd.Timestamp(start)).days, 0)
        return float((1.0 + float(annual_rate)) ** (days / 365.25) - 1.0)
    except Exception:
        return 0.0


def _limit_replacements(
    selected: list[str],
    pool: pd.DataFrame,
    *,
    previous: set[str],
    target_n: int,
    market: str,
    config: PortfolioRiskConfig,
) -> list[str]:
    if not previous or not selected:
        return selected

    max_replacements = max(1, int(math.ceil(target_n * config.max_turnover_fraction)))
    replacements = len([ticker for ticker in selected if ticker not in previous])
    if replacements <= max_replacements:
        return selected

    out = selected[:]
    previous_candidates = [
        ticker
        for ticker in previous
        if ticker in pool.index and ticker not in out
    ]
    previous_candidates = sorted(previous_candidates, key=lambda t: int(pool.loc[t, "_rank"]))

    for old_ticker in previous_candidates:
        if replacements <= max_replacements:
            break
        new_buys = [ticker for ticker in out if ticker not in previous]
        if not new_buys:
            break
        worst_new = max(new_buys, key=lambda t: int(pool.loc[t, "_rank"]))
        trial = [ticker for ticker in out if ticker != worst_new] + [old_ticker]
        if _selection_feasible(trial, pool, market=market, config=config, target_n=target_n):
            out = sorted(trial, key=lambda t: int(pool.loc[t, "_rank"]))
            replacements -= 1
    return out


def _relaxed_fill(selected: list[str], pool: pd.DataFrame, target_n: int) -> list[str]:
    out = selected[:]
    for ticker in pool.index:
        ticker = str(ticker)
        if len(out) >= target_n:
            break
        if ticker not in out:
            out.append(ticker)
    return out


def _selection_feasible(
    tickers: list[str],
    pool: pd.DataFrame,
    *,
    market: str,
    config: PortfolioRiskConfig,
    target_n: int,
) -> bool:
    max_sector_count = max(1, int(math.floor(target_n * config.max_sector_weight)))
    max_illiquid_count = max(0, int(math.floor(target_n * config.max_illiquid_weight)))
    sector_counts: Counter[str] = Counter()
    illiquid_count = 0
    for ticker in tickers:
        row = pool.loc[ticker]
        sector = _sector(row)
        if sector:
            sector_counts[sector] += 1
            if sector_counts[sector] > max_sector_count:
                return False
        if _is_illiquid(row, market=market, config=config):
            illiquid_count += 1
            if illiquid_count > max_illiquid_count:
                return False
    return True


def _cap_positions(weights: pd.Series, max_weight: float) -> pd.Series:
    if max_weight <= 0 or weights.empty:
        return weights
    w = weights.copy()
    for _ in range(len(w) + 1):
        over = w > max_weight
        if not over.any():
            break
        excess = float((w[over] - max_weight).sum())
        w[over] = max_weight
        eligible = ~over
        capacity = (max_weight - w[eligible]).clip(lower=0)
        if capacity.sum() <= 0 or excess <= 0:
            break
        add = excess * capacity / capacity.sum()
        w.loc[capacity.index] += add
    return w / w.sum()


def _cap_group(weights: pd.Series, labels: pd.Series, max_weight: float, max_position: float) -> pd.Series:
    if max_weight <= 0 or weights.empty or labels.empty:
        return weights
    w = weights.copy()
    labels = labels.reindex(w.index)
    capped_members: set[str] = set()
    excess = 0.0
    for label, members in labels.dropna().groupby(labels.dropna()).groups.items():
        member_index = pd.Index(members).intersection(w.index)
        total = float(w.loc[member_index].sum())
        if total > max_weight:
            scale = max_weight / total
            excess += total - max_weight
            w.loc[member_index] *= scale
            capped_members.update(member_index.astype(str))
    return _redistribute_excess(w, excess, excluded=capped_members, max_position=max_position)


def _cap_boolean_group(weights: pd.Series, mask: pd.Series, max_weight: float, max_position: float) -> pd.Series:
    if max_weight <= 0 or weights.empty:
        return weights
    w = weights.copy()
    mask = mask.reindex(w.index).fillna(False).astype(bool)
    total = float(w[mask].sum())
    if total <= max_weight:
        return w / w.sum()
    scale = max_weight / total
    excess = total - max_weight
    w.loc[mask] *= scale
    return _redistribute_excess(w, excess, excluded=set(w[mask].index.astype(str)), max_position=max_position)


def _redistribute_excess(weights: pd.Series, excess: float, *, excluded: set[str], max_position: float) -> pd.Series:
    if excess <= 0:
        return weights / weights.sum()
    w = weights.copy()
    eligible = pd.Series([str(idx) not in excluded for idx in w.index], index=w.index)
    capacity = (max_position - w[eligible]).clip(lower=0)
    if capacity.sum() > 0:
        w.loc[capacity.index] += excess * capacity / capacity.sum()
    elif w.sum() > 0:
        w += excess * w / w.sum()
    return w / w.sum()


def _is_illiquid(row: pd.Series, *, market: str, config: PortfolioRiskConfig) -> bool:
    market_name = str(market).upper()
    market_cap = _to_float(row.get("MarketCap"))
    trading_value = _to_float(row.get("TradingValue_20D"))
    if market_name == "KR":
        if trading_value is not None:
            return trading_value < config.kr_illiquid_trading_value_20d
        return market_cap is not None and market_cap < config.kr_illiquid_market_cap
    return market_cap is not None and market_cap < config.us_illiquid_market_cap


def _sector(row: pd.Series) -> str | None:
    value = row.get("Sector") if hasattr(row, "get") else None
    text = str(value or "").strip()
    if not text or text.lower() in {"nan", "none", "unknown"}:
        return None
    return text


def _to_float(value: object) -> float | None:
    try:
        if value is None or pd.isna(value):
            return None
        parsed = float(str(value).replace(",", "").replace("%", ""))
        if not math.isfinite(parsed):
            return None
        return parsed
    except Exception:
        return None
