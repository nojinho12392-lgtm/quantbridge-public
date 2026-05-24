"""OpenDART helpers for Korean point-in-time backtests.

OpenDART annual statements are keyed by fiscal year, while exact filing dates
vary by company. For this free-data workflow we use a conservative availability
date of April 1 after fiscal-year end, roughly after annual reports are public.
That avoids using fiscal-year fundamentals during the fiscal year itself.
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

import numpy as np
import pandas as pd
import requests


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CACHE = ROOT / "docs_cache" / "kr_pit_financials.csv"
DEFAULT_MAX_DART_CALLS = int(os.environ.get("KR_DART_MAX_CALLS", "3000"))
OPENDART_LIST_URL = "https://opendart.fss.or.kr/api/list.json"
REPORT_NAME_BY_CODE = {
    "11011": "사업보고서",
}


def dart_api_key() -> str:
    for env_name in ("DART_API_KEY", "OPENDART_API_KEY", "QUANT_DART_API_KEY"):
        value = os.environ.get(env_name, "").strip()
        if value:
            return value
    try:
        with open(ROOT / "key.json") as f:
            data = json.load(f)
        return (
            data.get("dart_api_key")
            or data.get("DART_API_KEY")
            or data.get("opendart_api_key")
            or ""
        )
    except Exception:
        return ""


def stock_code(ticker: str) -> str:
    return str(ticker).split(".")[0].strip().zfill(6)


def pit_available_date(fiscal_year: int) -> pd.Timestamp:
    return pd.Timestamp(year=int(fiscal_year) + 1, month=4, day=1)


def parse_report_available_date(payload: dict, *, fiscal_year: int, reprt_code: str = "11011") -> pd.Timestamp | None:
    reports = payload.get("list") if isinstance(payload, dict) else None
    if not reports:
        return None
    target_name = REPORT_NAME_BY_CODE.get(str(reprt_code), "")
    target_year = str(int(fiscal_year))
    matched_dates = []
    fallback_dates = []
    for report in reports:
        name = str(report.get("report_nm", ""))
        received = str(report.get("rcept_dt", "")).strip()
        if target_name and target_name not in name:
            continue
        try:
            received_at = pd.Timestamp(received)
        except Exception:
            continue
        fallback_dates.append(received_at)
        if target_year in name:
            matched_dates.append(received_at)
    dates = matched_dates or fallback_dates
    return min(dates) if dates else None


def fetch_report_available_date(
    api_key: str,
    corp_code: str,
    fiscal_year: int,
    *,
    reprt_code: str = "11011",
    session: requests.Session | None = None,
) -> pd.Timestamp | None:
    sess = session or requests.Session()
    start = f"{int(fiscal_year) + 1}0101"
    end = f"{int(fiscal_year) + 1}0430"
    resp = sess.get(
        OPENDART_LIST_URL,
        params={
            "crtfc_key": api_key,
            "corp_code": corp_code,
            "bgn_de": start,
            "end_de": end,
            "pblntf_ty": "A",
            "page_count": 100,
        },
        timeout=20,
    )
    if resp.status_code != 200:
        return None
    return parse_report_available_date(resp.json(), fiscal_year=fiscal_year, reprt_code=reprt_code)


def _valid(value) -> bool:
    try:
        return value is not None and not pd.isna(value) and float(value) != 0
    except Exception:
        return False


def _clean_amount(value):
    if value is None:
        return None
    text = str(value).replace(",", "").replace(" ", "").strip()
    if text in ("", "-", "nan", "None"):
        return None
    try:
        return float(text)
    except Exception:
        return None


def _amount(df: pd.DataFrame, names: list[str], *, prior: bool = False):
    if df is None or df.empty or "account_nm" not in df.columns:
        return None
    col = "frmtrm_amount" if prior else "thstrm_amount"
    if col not in df.columns:
        return None
    for name in names:
        rows = df[df["account_nm"].astype(str).str.strip().eq(name)]
        if not rows.empty:
            val = _clean_amount(rows.iloc[0].get(col))
            if val is not None:
                return val
    return None


def _amount_by_id(df: pd.DataFrame, token: str, *, prior: bool = False):
    if df is None or df.empty or "account_id" not in df.columns:
        return None
    col = "frmtrm_amount" if prior else "thstrm_amount"
    if col not in df.columns:
        return None
    rows = df[df["account_id"].astype(str).str.contains(token, na=False)]
    if rows.empty:
        return None
    return _clean_amount(rows.iloc[0].get(col))


def parse_dart_annual_financials(
    fs: pd.DataFrame,
    *,
    ticker: str,
    fiscal_year: int,
    available_date: pd.Timestamp | str | None = None,
) -> dict | None:
    if fs is None or fs.empty:
        return None
    fs = fs.copy()
    if "fs_div" in fs.columns:
        cfs = fs[fs["fs_div"].eq("CFS")]
        if not cfs.empty:
            fs = cfs
    if "sj_div" in fs.columns:
        fs_is = fs[fs["sj_div"].isin(["IS", "CIS"])]
        fs_bs = fs[fs["sj_div"].eq("BS")]
        fs_cf = fs[fs["sj_div"].eq("CF")]
    else:
        fs_is = fs_bs = fs_cf = fs

    revenue = _amount(fs_is, ["매출액", "수익(매출액)", "영업수익"])
    revenue = revenue if _valid(revenue) else _amount_by_id(fs_is, "ifrs-full_Revenue")
    revenue_py = _amount(fs_is, ["매출액", "수익(매출액)", "영업수익"], prior=True)
    revenue_py = revenue_py if _valid(revenue_py) else _amount_by_id(fs_is, "ifrs-full_Revenue", prior=True)

    gross_profit = _amount(fs_is, ["매출총이익", "매출총손익"])
    gross_profit = gross_profit if _valid(gross_profit) else _amount_by_id(fs_is, "ifrs-full_GrossProfit")
    op_income = _amount(fs_is, ["영업이익", "영업이익(손실)", "영업손익"])
    op_income = op_income if _valid(op_income) else _amount_by_id(fs_is, "dart_OperatingIncomeLoss")
    net_income = _amount(
        fs_is,
        ["당기순이익", "당기순이익(손실)", "당기순손익", "지배기업 소유주 지분 당기순이익"],
    )
    net_income = net_income if _valid(net_income) else _amount_by_id(fs_is, "ifrs-full_ProfitLoss")
    net_income_py = _amount(
        fs_is,
        ["당기순이익", "당기순이익(손실)", "당기순손익", "지배기업 소유주 지분 당기순이익"],
        prior=True,
    )
    net_income_py = net_income_py if _valid(net_income_py) else _amount_by_id(fs_is, "ifrs-full_ProfitLoss", prior=True)
    interest = _amount(fs_is, ["이자비용", "금융비용"])
    interest = interest if _valid(interest) else _amount_by_id(fs_is, "ifrs-full_FinanceCosts")

    assets = _amount(fs_bs, ["자산총계", "자산 합계"])
    assets = assets if _valid(assets) else _amount_by_id(fs_bs, "ifrs-full_Assets")
    current_assets = _amount(fs_bs, ["유동자산", "유동자산합계", "유동자산 합계"])
    current_assets = current_assets if _valid(current_assets) else _amount_by_id(fs_bs, "ifrs-full_CurrentAssets")
    current_liabilities = _amount(fs_bs, ["유동부채", "유동부채합계", "유동부채 합계"])
    current_liabilities = current_liabilities if _valid(current_liabilities) else _amount_by_id(fs_bs, "ifrs-full_CurrentLiabilities")
    retained_earnings = _amount(fs_bs, ["이익잉여금", "미처분이익잉여금", "이익잉여금(결손금)", "이익(결손금)"])
    retained_earnings = retained_earnings if _valid(retained_earnings) else _amount_by_id(fs_bs, "ifrs-full_RetainedEarnings")
    total_liabilities = _amount(fs_bs, ["부채총계", "부채 합계"])
    total_liabilities = total_liabilities if _valid(total_liabilities) else _amount_by_id(fs_bs, "ifrs-full_Liabilities")
    equity = _amount(fs_bs, ["자본총계", "자본 합계"])
    equity = equity if _valid(equity) else _amount_by_id(fs_bs, "ifrs-full_Equity")

    short_debt = _amount(fs_bs, ["단기차입금", "유동성장기부채", "유동성장기차입금"])
    long_debt = _amount(fs_bs, ["장기차입금", "비유동차입금", "사채 및 장기차입금", "사채"])
    direct_debt = _amount(fs_bs, ["차입금합계", "총차입금", "차입금 및 사채"])
    if _valid(direct_debt):
        total_debt = direct_debt
    elif _valid(short_debt) or _valid(long_debt):
        total_debt = (short_debt or 0) + (long_debt or 0)
    else:
        total_debt = None

    op_cf = _amount(fs_cf, ["영업활동현금흐름", "영업활동으로 인한 현금흐름", "영업활동으로인한현금흐름"])
    capex = _amount(fs_cf, ["유형자산의 취득", "유형자산취득", "유형자산의취득", "유형자산 취득"])
    dep = _amount(fs_cf, ["감가상각비", "감가상각비 및 상각비", "감가상각 및 상각비"])
    ebitda = (op_income or 0) + abs(dep or 0) if _valid(op_income) else None
    fcf = (op_cf or 0) - abs(capex or 0) if op_cf is not None else None

    def div(a, b):
        try:
            return float(a) / float(b) if a is not None and b not in (None, 0) else np.nan
        except Exception:
            return np.nan

    row = {
        "Ticker": ticker,
        "Fiscal_Year": int(fiscal_year),
        "Available_Date": pd.Timestamp(available_date or pit_available_date(fiscal_year)).strftime("%Y-%m-%d"),
        "Revenue": revenue,
        "ROE": div(net_income, equity),
        "ROIC": div((op_income or 0) * 0.79, (equity or 0) + (total_debt or 0)),
        "OperatingMargin": div(op_income, revenue),
        "GrossMargin": div(gross_profit, revenue),
        "FCF_Margin": div(fcf, revenue),
        "FCF_NI": div(fcf, net_income),
        "InterestCoverage": div(op_income, abs(interest) if interest else np.nan),
        "DebtToEquity": div(total_debt, equity) * 100 if _valid(equity) else np.nan,
        "Debt_EBITDA": div(total_debt, ebitda),
        "RevGrowth": div(revenue - revenue_py, abs(revenue_py)) if _valid(revenue) and _valid(revenue_py) else np.nan,
        "EPS_Growth": div(net_income - net_income_py, abs(net_income_py)) if _valid(net_income) and _valid(net_income_py) else np.nan,
        "TotalAssets": assets,
        "CurrentAssets": current_assets,
        "CurrentLiabilities": current_liabilities,
        "RetainedEarnings": retained_earnings,
        "TotalLiabilities": total_liabilities,
    }
    return row if any(pd.notna(row.get(k)) for k in ("Revenue", "ROE", "ROIC", "OperatingMargin")) else None


def load_cached_pit_financials(cache_path: Path = DEFAULT_CACHE) -> pd.DataFrame:
    if not cache_path.exists():
        return pd.DataFrame()
    try:
        return pd.read_csv(cache_path)
    except Exception:
        return pd.DataFrame()


def save_cached_pit_financials(df: pd.DataFrame, cache_path: Path = DEFAULT_CACHE) -> None:
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(cache_path, index=False)


def fetch_kr_pit_financials(
    tickers: list[str],
    years: list[int],
    *,
    delay: float = 0.35,
    cache_path: Path = DEFAULT_CACHE,
    max_api_calls: int | None = None,
    report_session: requests.Session | None = None,
) -> pd.DataFrame:
    key = dart_api_key()
    if not key:
        print("[KR-PIT] DART API key missing. PIT fundamentals unavailable.")
        return load_cached_pit_financials(cache_path)

    import OpenDartReader

    cached = load_cached_pit_financials(cache_path)
    have = set()
    if not cached.empty and {"Ticker", "Fiscal_Year"}.issubset(cached.columns):
        have = set(zip(cached["Ticker"].astype(str), cached["Fiscal_Year"].astype(int)))

    dart = OpenDartReader(key)
    corp_df = dart.corp_codes
    corp_map = {}
    for _, row in corp_df.iterrows():
        code = str(row.get("stock_code", "")).strip()
        corp = str(row.get("corp_code", "")).strip()
        if code and code.lower() != "nan" and corp and corp.lower() != "nan":
            corp_map[code.split(".")[0].zfill(6)] = corp.split(".")[0].zfill(8)

    rows = []
    total = len(tickers) * len(years)
    done = 0
    api_budget = DEFAULT_MAX_DART_CALLS if max_api_calls is None else int(max_api_calls)
    api_calls = 0
    budget_exhausted = False
    for ticker in tickers:
        if budget_exhausted:
            break
        code = stock_code(ticker)
        corp = corp_map.get(code)
        if not corp:
            continue
        for year in years:
            done += 1
            if (ticker, int(year)) in have:
                continue
            if api_calls >= api_budget:
                print(f"[KR-PIT] DART API call budget reached ({api_budget}); remaining uncached rows skipped")
                budget_exhausted = True
                break
            try:
                api_calls += 1
                fs = dart.finstate_all(corp, int(year), reprt_code="11011", fs_div="CFS")
                available_date = None
                if api_calls < api_budget:
                    try:
                        api_calls += 1
                        available_date = fetch_report_available_date(
                            key,
                            corp,
                            int(year),
                            reprt_code="11011",
                            session=report_session,
                        )
                    except Exception:
                        available_date = None
                parsed = parse_dart_annual_financials(
                    fs,
                    ticker=ticker,
                    fiscal_year=int(year),
                    available_date=available_date,
                )
                if parsed:
                    rows.append(parsed)
            except Exception as exc:
                print(f"[KR-PIT] {ticker} {year} skipped: {exc}")
            if done % 50 == 0:
                print(f"[KR-PIT] fetched/checkpoint {done}/{total}")
            time.sleep(delay)

    if rows:
        fresh = pd.DataFrame(rows)
        combined = pd.concat([cached, fresh], ignore_index=True)
        combined = combined.drop_duplicates(["Ticker", "Fiscal_Year"], keep="last")
        save_cached_pit_financials(combined, cache_path)
        return combined
    return cached


def asof_pit_features(pit_df: pd.DataFrame, date: pd.Timestamp) -> pd.DataFrame:
    if pit_df.empty:
        return pd.DataFrame()
    df = pit_df.copy()
    df["Available_Date"] = pd.to_datetime(df["Available_Date"], errors="coerce")
    df = df.dropna(subset=["Available_Date"])
    df = df[df["Available_Date"] <= pd.Timestamp(date)].sort_values(["Ticker", "Available_Date"])
    if df.empty:
        return pd.DataFrame()
    return df.groupby("Ticker").tail(1).set_index("Ticker")
