from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Iterable, Mapping

import numpy as np
import pandas as pd
import requests


ROOT_DIR = Path(__file__).resolve().parents[2]
DEFAULT_CACHE_DIR = ROOT_DIR / "docs_cache" / "sec_edgar"
DEFAULT_USER_AGENT = "QuantBridge/1.0 jino-quant-bot@axion-quant-491114.iam.gserviceaccount.com"
DEFAULT_DELAY = float(os.environ.get("QUANT_SEC_DELAY", "0.12"))
DEFAULT_MAX_REQUESTS = int(os.environ.get("QUANT_SEC_MAX_REQUESTS", "2500"))

REVENUE_NAMES = [
    "RevenueFromContractWithCustomerExcludingAssessedTax",
    "RevenueFromContractWithCustomerIncludingAssessedTax",
    "SalesRevenueNet",
    "Revenues",
]

def edgar_user_agent() -> str:
    return (
        os.environ.get("QUANT_SEC_USER_AGENT")
        or os.environ.get("SEC_EDGAR_USER_AGENT")
        or DEFAULT_USER_AGENT
    ).strip()


def _cache_dir(cache_dir: str | Path | None = None) -> Path:
    path = Path(cache_dir or os.environ.get("QUANT_SEC_CACHE_DIR", DEFAULT_CACHE_DIR)).expanduser()
    path.mkdir(parents=True, exist_ok=True)
    return path


def _is_fresh(path: Path, max_age_days: int) -> bool:
    if not path.exists():
        return False
    age_seconds = time.time() - path.stat().st_mtime
    return age_seconds <= max_age_days * 24 * 60 * 60


def _read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


def _write_json(path: Path, payload: Mapping) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload), encoding="utf-8")
    tmp.replace(path)


def company_facts_cache_path(cik: str, cache_dir: str | Path | None = None) -> Path:
    return _cache_dir(cache_dir) / f"companyfacts_CIK{str(cik).zfill(10)}.json"


def load_cik_map(
    *,
    session: requests.Session | None = None,
    user_agent: str | None = None,
    cache_dir: str | Path | None = None,
    max_age_days: int = 7,
) -> dict[str, str]:
    cache_path = _cache_dir(cache_dir) / "company_tickers.json"
    payload = _read_json(cache_path) if _is_fresh(cache_path, max_age_days) else {}

    if not payload:
        sess = session or requests.Session()
        resp = sess.get(
            "https://www.sec.gov/files/company_tickers.json",
            headers={"User-Agent": user_agent or edgar_user_agent()},
            timeout=30,
        )
        resp.raise_for_status()
        payload = resp.json()
        _write_json(cache_path, payload)

    return {
        str(value.get("ticker", "")).upper(): str(value.get("cik_str", "")).zfill(10)
        for value in payload.values()
        if value.get("ticker") and value.get("cik_str") is not None
    }


class EdgarRateLimiter:
    def __init__(self, delay: float = DEFAULT_DELAY):
        self.delay = delay
        self._last_request = 0.0

    def wait(self) -> None:
        elapsed = time.time() - self._last_request
        if elapsed < self.delay:
            time.sleep(self.delay - elapsed)

    def mark(self) -> None:
        self._last_request = time.time()


def fetch_company_facts(
    cik: str,
    *,
    session: requests.Session | None = None,
    user_agent: str | None = None,
    cache_dir: str | Path | None = None,
    max_age_days: int = 30,
    limiter: EdgarRateLimiter | None = None,
) -> dict:
    cik10 = str(cik).zfill(10)
    cache_path = company_facts_cache_path(cik10, cache_dir)
    cached = _read_json(cache_path) if _is_fresh(cache_path, max_age_days) else {}
    if cached:
        return cached

    sess = session or requests.Session()
    if limiter is not None:
        limiter.wait()
    try:
        resp = sess.get(
            f"https://data.sec.gov/api/xbrl/companyfacts/CIK{cik10}.json",
            headers={"User-Agent": user_agent or edgar_user_agent()},
            timeout=30,
        )
    finally:
        if limiter is not None:
            limiter.mark()

    if resp.status_code != 200:
        return {}
    payload = resp.json()
    _write_json(cache_path, payload)
    return payload


def fetch_company_facts_for_tickers(
    tickers: Iterable[str],
    cik_map: Mapping[str, str],
    *,
    session: requests.Session | None = None,
    user_agent: str | None = None,
    cache_dir: str | Path | None = None,
    delay: float = DEFAULT_DELAY,
    max_age_days: int = 30,
    max_requests: int | None = None,
) -> dict[str, dict]:
    sess = session or requests.Session()
    limiter = EdgarRateLimiter(delay=delay)
    request_budget = DEFAULT_MAX_REQUESTS if max_requests is None else max_requests
    network_requests = 0
    out: dict[str, dict] = {}
    for ticker in tickers:
        symbol = str(ticker).strip().upper()
        cik = cik_map.get(symbol)
        if not cik:
            continue
        cache_path = company_facts_cache_path(cik, cache_dir)
        needs_network = not _is_fresh(cache_path, max_age_days)
        if needs_network and network_requests >= request_budget:
            print(f"[SEC] request budget reached ({request_budget}); remaining tickers skipped")
            break
        out[symbol] = fetch_company_facts(
            cik,
            session=sess,
            user_agent=user_agent,
            cache_dir=cache_dir,
            max_age_days=max_age_days,
            limiter=limiter,
        )
        if needs_network:
            network_requests += 1
    return out


def concept_series(
    gaap: Mapping,
    names: str | Iterable[str],
    *,
    forms: tuple[str, ...] = ("10-K", "10-Q"),
    units: str = "USD",
    flow: bool = False,
) -> pd.Series | None:
    for name in ([names] if isinstance(names, str) else names):
        entries = gaap.get(name, {}).get("units", {}).get(units, [])
        if not entries:
            continue
        seen: dict[pd.Timestamp, tuple[float, float]] = {}
        for entry in entries:
            if entry.get("form") not in forms:
                continue
            filed = entry.get("filed")
            value = entry.get("val")
            if not filed or value is None:
                continue
            try:
                filed_at = pd.Timestamp(filed)
            except Exception:
                continue
            score = _duration_score(entry) if flow else 0.0
            if filed_at not in seen or score < seen[filed_at][0]:
                seen[filed_at] = (score, float(value))
        if seen:
            items = sorted(seen.items())
            return pd.Series(
                [value for _, (_, value) in items],
                index=pd.DatetimeIndex([filed for filed, _ in items]),
                dtype="float64",
            ).sort_index()
    return None


def concept_ttm_series(
    gaap: Mapping,
    names: str | Iterable[str],
    *,
    units: str = "USD",
) -> pd.Series | None:
    entries = _period_entries(gaap, names, units=units)
    if not entries:
        return None

    annual = [entry for entry in entries if entry["form"] == "10-K" and 250 <= entry["days"] <= 450]
    quarterly = [entry for entry in entries if entry["form"] == "10-Q" and 60 <= entry["days"] <= 130]
    interim = [entry for entry in entries if entry["form"] == "10-Q" and 60 <= entry["days"] <= 310]
    filing_dates = sorted({entry["filed"] for entry in annual + interim})
    values: dict[pd.Timestamp, float] = {}

    for filed_at in filing_dates:
        available_quarters = [entry for entry in quarterly if entry["filed"] <= filed_at]
        available_quarters = sorted(available_quarters, key=lambda entry: (entry["end"], entry["filed"]))
        if len(available_quarters) >= 4:
            last_four = available_quarters[-4:]
            span_days = (last_four[-1]["end"] - last_four[0]["start"]).days
            if 300 <= span_days <= 460:
                values[filed_at] = float(sum(entry["value"] for entry in last_four))
                continue

        ytd_ttm = _ttm_from_annual_and_ytd(annual, interim, filed_at)
        if ytd_ttm is not None:
            values[filed_at] = ytd_ttm
            continue

        available_annual = [entry for entry in annual if entry["filed"] <= filed_at]
        if available_annual:
            latest = sorted(available_annual, key=lambda entry: (entry["end"], entry["filed"]))[-1]
            values[filed_at] = float(latest["value"])

    if not values:
        return None
    items = sorted(values.items())
    return pd.Series(
        [value for _, value in items],
        index=pd.DatetimeIndex([filed for filed, _ in items]),
        dtype="float64",
    ).sort_index()


def _period_entries(gaap: Mapping, names: str | Iterable[str], *, units: str) -> list[dict[str, object]]:
    for name in ([names] if isinstance(names, str) else names):
        out: list[dict[str, object]] = []
        seen: dict[tuple[object, object, object, object], dict[str, object]] = {}
        raw_entries = gaap.get(name, {}).get("units", {}).get(units, [])
        for entry in raw_entries:
            form = entry.get("form")
            if form not in {"10-K", "10-Q"}:
                continue
            start = entry.get("start")
            end = entry.get("end")
            filed = entry.get("filed")
            value = entry.get("val")
            if not start or not end or not filed or value is None:
                continue
            try:
                start_at = pd.Timestamp(start)
                end_at = pd.Timestamp(end)
                filed_at = pd.Timestamp(filed)
                numeric_value = float(value)
            except Exception:
                continue
            days = (end_at - start_at).days
            if days <= 0:
                continue
            normalized = {
                "form": form,
                "start": start_at,
                "end": end_at,
                "filed": filed_at,
                "days": days,
                "value": numeric_value,
            }
            seen[(form, start_at, end_at, filed_at)] = normalized
        out = list(seen.values())
        if out:
            return out
    return []


def _ttm_from_annual_and_ytd(
    annual: list[dict[str, object]],
    quarterly: list[dict[str, object]],
    filed_at: pd.Timestamp,
) -> float | None:
    available_annual = sorted(
        [entry for entry in annual if entry["filed"] <= filed_at],
        key=lambda entry: (entry["end"], entry["filed"]),
    )
    if not available_annual:
        return None

    ytd_candidates = sorted(
        [
            entry
            for entry in quarterly
            if entry["filed"] <= filed_at and 60 <= entry["days"] <= 310
        ],
        key=lambda entry: (entry["end"], entry["filed"], entry["days"]),
        reverse=True,
    )
    for current_ytd in ytd_candidates:
        annual_before = [
            entry
            for entry in available_annual
            if entry["end"] < current_ytd["end"]
        ]
        if not annual_before:
            continue
        latest_annual = annual_before[-1]
        if current_ytd["days"] <= 130 and (current_ytd["start"] - latest_annual["end"]).days > 45:
            continue
        prior_target = current_ytd["end"] - pd.DateOffset(years=1)
        prior_matches = [
            entry
            for entry in quarterly
            if entry["filed"] <= filed_at
            and 60 <= entry["days"] <= 310
            and abs((entry["end"] - prior_target).days) <= 35
            and abs(entry["days"] - current_ytd["days"]) <= 45
        ]
        if not prior_matches:
            continue
        prior_ytd = sorted(prior_matches, key=lambda entry: (entry["end"], entry["filed"], entry["days"]))[-1]
        return float(latest_annual["value"] + current_ytd["value"] - prior_ytd["value"])
    return None


def _duration_score(entry: Mapping) -> float:
    start = entry.get("start")
    end = entry.get("end")
    if not start or not end:
        return 999.0
    try:
        days = (pd.Timestamp(end) - pd.Timestamp(start)).days
    except Exception:
        return 999.0
    if days < 30 or days > 450:
        return 999.0
    target = 365 if entry.get("form") == "10-K" else 91
    return float(abs(days - target))


def facts_to_timeseries(facts: Mapping) -> dict[str, pd.Series]:
    gaap = facts.get("facts", {}).get("us-gaap", {}) if isinstance(facts, Mapping) else {}
    if not gaap:
        return {}

    out: dict[str, pd.Series] = {}

    def add(
        key: str,
        names: str | Iterable[str],
        *,
        units: str = "USD",
        flow: bool = False,
        forms: tuple[str, ...] = ("10-K",),
    ) -> None:
        series = concept_ttm_series(gaap, names, units=units) if flow else concept_series(
            gaap,
            names,
            units=units,
            flow=False,
            forms=forms,
        )
        if series is not None and not series.empty:
            out[key] = series

    add("revenue", REVENUE_NAMES, flow=True)
    add("net_income", "NetIncomeLoss", flow=True)
    add("op_income", "OperatingIncomeLoss", flow=True)
    add("assets", "Assets", forms=("10-K", "10-Q"))
    add("current_assets", "AssetsCurrent", forms=("10-K", "10-Q"))
    add("current_liabilities", "LiabilitiesCurrent", forms=("10-K", "10-Q"))
    add("retained_earnings", "RetainedEarningsAccumulatedDeficit", forms=("10-K", "10-Q"))
    add("liabilities", "Liabilities", forms=("10-K", "10-Q"))
    add("equity", ["StockholdersEquity", "StockholdersEquityAttributableToParent"])
    add("op_cf", "NetCashProvidedByUsedInOperatingActivities", flow=True)
    add("capex", "PaymentsToAcquirePropertyPlantAndEquipment", flow=True)
    add("da", ["DepreciationDepletionAndAmortization", "DepreciationAndAmortization"], flow=True)
    add("interest", "InterestExpense", flow=True)
    add("ltd", ["LongTermDebt", "LongTermDebtNoncurrent"], forms=("10-K", "10-Q"))
    add("std", ["ShortTermBorrowings", "CurrentPortionOfLongTermDebt"], forms=("10-K", "10-Q"))
    add("gross_profit", "GrossProfit", flow=True)
    add("eps", ["EarningsPerShareDiluted", "EarningsPerShareBasic"], units="USD/shares", flow=True)
    return out


def build_fundamental_timeseries(raw_facts: Mapping[str, Mapping]) -> dict[str, dict[str, pd.Series]]:
    out: dict[str, dict[str, pd.Series]] = {}
    for ticker, facts in raw_facts.items():
        series = facts_to_timeseries(facts)
        if series:
            out[str(ticker).strip().upper()] = series
    return out


def asof_metric(series_map: Mapping[str, pd.Series], key: str, as_of: pd.Timestamp | str) -> float | None:
    series = series_map.get(key)
    if series is None or series.empty:
        return None
    try:
        value = series.asof(pd.Timestamp(as_of))
        return float(value) if pd.notna(value) else None
    except Exception:
        return None


def _safe_div(numerator: object, denominator: object) -> float | None:
    try:
        if numerator is None or denominator is None:
            return None
        denominator_f = float(denominator)
        if denominator_f == 0:
            return None
        return float(numerator) / denominator_f
    except Exception:
        return None


def get_pit_metrics(
    fundamental_timeseries: Mapping[str, Mapping[str, pd.Series]],
    ticker: str,
    as_of: pd.Timestamp | str,
) -> dict[str, float | None] | None:
    symbol = str(ticker).strip().upper()
    ts = fundamental_timeseries.get(symbol)
    if ts is None:
        return None

    date = pd.Timestamp(as_of)
    g = lambda key: asof_metric(ts, key, date)

    revenue = g("revenue")
    net_income = g("net_income")
    op_income = g("op_income")
    equity = g("equity")
    assets = g("assets")
    current_assets = g("current_assets")
    current_liabilities = g("current_liabilities")
    retained_earnings = g("retained_earnings")
    liabilities = g("liabilities")
    op_cf = g("op_cf")
    capex = g("capex")
    da = g("da")
    interest = g("interest")
    ltd = g("ltd") or 0.0
    std_debt = g("std") or 0.0
    gross_profit = g("gross_profit")
    eps_current = g("eps")

    prior_date = date - pd.DateOffset(years=1)
    revenue_prior = asof_metric(ts, "revenue", prior_date)
    eps_prior = asof_metric(ts, "eps", prior_date)

    total_debt = ltd + std_debt
    invested_capital = (equity or 0.0) + total_debt
    fcf = (op_cf - capex) if op_cf is not None and capex is not None else None
    ebitda = (op_income or 0.0) + (da or 0.0)

    interest_coverage = _safe_div(op_income, interest)
    if interest_coverage is not None:
        interest_coverage = min(interest_coverage, 50.0)

    revenue_ratio = _safe_div(revenue, revenue_prior)
    eps_ratio = _safe_div(eps_current, abs(eps_prior) if eps_prior is not None else None)

    metrics = {
        "Revenue": revenue,
        "TotalAssets": assets,
        "CurrentAssets": current_assets,
        "CurrentLiabilities": current_liabilities,
        "RetainedEarnings": retained_earnings,
        "TotalLiabilities": liabilities,
        "OperatingMargin": _safe_div(op_income, revenue),
        "ROIC": _safe_div((op_income or 0.0) * 0.79, invested_capital),
        "ROE": _safe_div(net_income, equity),
        "FCF_NI": _safe_div(fcf, net_income),
        "FCF_Margin": _safe_div(fcf, revenue),
        "GrossMargin": _safe_div(gross_profit, revenue),
        "InterestCoverage": interest_coverage,
        "RevGrowth": revenue_ratio - 1.0 if revenue_ratio is not None else None,
        "EPS_Growth": eps_ratio - 1.0 if eps_ratio is not None and eps_prior else None,
        "DebtToEquity": _safe_div(total_debt, equity),
        "Debt_EBITDA": _safe_div(total_debt, ebitda),
        "roic": _safe_div((op_income or 0.0) * 0.79, invested_capital),
        "roe": _safe_div(net_income, equity),
        "fcf_ni": _safe_div(fcf, net_income),
        "fcf_margin": _safe_div(fcf, revenue),
        "op_margin": _safe_div(op_income, revenue),
        "gross_margin": _safe_div(gross_profit, revenue),
        "int_cov": interest_coverage,
        "rev_growth": revenue_ratio - 1.0 if revenue_ratio is not None else None,
        "eps_growth": eps_ratio - 1.0 if eps_ratio is not None and eps_prior else None,
        "de_ratio": _safe_div(total_debt, equity),
    }
    return metrics if any(value is not None and pd.notna(value) for value in metrics.values()) else None


def latest_sec_metrics_for_tickers(
    tickers: Iterable[str],
    *,
    as_of: pd.Timestamp | str | None = None,
    max_tickers: int | None = None,
    session: requests.Session | None = None,
    user_agent: str | None = None,
    cache_dir: str | Path | None = None,
    delay: float = DEFAULT_DELAY,
    max_requests: int | None = None,
) -> pd.DataFrame:
    symbols = []
    for ticker in tickers:
        symbol = str(ticker).strip().upper()
        if symbol and symbol not in symbols:
            symbols.append(symbol)
    if max_tickers is not None:
        symbols = symbols[:max_tickers]
    if not symbols:
        return pd.DataFrame()

    ua = user_agent or edgar_user_agent()
    cik_map = load_cik_map(session=session, user_agent=ua, cache_dir=cache_dir)
    raw = fetch_company_facts_for_tickers(
        [symbol for symbol in symbols if symbol in cik_map],
        cik_map,
        session=session,
        user_agent=ua,
        cache_dir=cache_dir,
        delay=delay,
        max_requests=max_requests,
    )
    timeseries = build_fundamental_timeseries(raw)
    effective_date = pd.Timestamp(as_of or pd.Timestamp.now()).normalize()

    rows = {}
    for symbol in symbols:
        metrics = get_pit_metrics(timeseries, symbol, effective_date)
        if metrics:
            rows[symbol] = {**metrics, "SEC_Data_Source": "SEC_EDGAR", "SEC_As_Of": effective_date.strftime("%Y-%m-%d")}
    return pd.DataFrame.from_dict(rows, orient="index")
