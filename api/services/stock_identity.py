from __future__ import annotations

import math
import re
from typing import Callable

import pandas as pd

from api.services.company_names import (
    _is_missing_kr_name,
    kr_code,
    localized_company_name,
)


def safe_float(value) -> float | None:
    try:
        parsed = float(value)
        return None if math.isnan(parsed) else parsed
    except (TypeError, ValueError):
        return None


def clean_text(value) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def first_float(*values) -> float | None:
    for value in values:
        parsed = safe_float(value)
        if parsed is not None:
            return parsed
    return None


def first_text(*values) -> str | None:
    for value in values:
        text = clean_text(value)
        if text:
            return text
    return None


def has_value(value) -> bool:
    if value is None:
        return False
    if isinstance(value, float) and math.isnan(value):
        return False
    if isinstance(value, str) and not value.strip():
        return False
    return True


def infer_market(ticker: str) -> str:
    return "KR" if re.fullmatch(r"\d{6}(?:\.(?:KS|KQ))?", str(ticker or "").strip().upper()) else "US"


def default_logo_url(ticker: str, market: str) -> str:
    normal = str(ticker or "").strip().upper()
    if market == "KR":
        code = kr_code(normal)
        if not code:
            return ""
        overrides = {
            "064400": "https://www.lgcns.com/etc.clientlibs/lgcns/clientlibs/clientlib-site/resources/image/common/logo-og-0807.png",
            "267250": f"https://file.alphasquare.co.kr/media/images/stock_logo/kr/{code}.png",
        }
        return overrides.get(code) or f"https://static.toss.im/png-icons/securities/icn-sec-fill-{code}.png"
    return f"https://financialmodelingprep.com/image-stock/{normal}.png" if normal else ""


def identity_payload_from_row(row: dict, ticker: str, market: str, dataset: str) -> dict:
    normal = str(ticker or "").strip().upper()
    payload = {
        "market": market,
        "ticker": normal,
        "name": localized_company_name(normal, first_text(row.get("Name"), row.get("name")), market),
        "sector": first_text(row.get("Sector"), row.get("sector")),
        "market_cap": first_float(row.get("MarketCap"), row.get("MarketCap_Last"), row.get("market_cap")),
        "currency": "KRW" if market == "KR" else "USD",
        "exchange": first_text(row.get("Exchange"), row.get("exchange")),
        "logo_url": default_logo_url(normal, market),
        "logo_source": "kr_logo_fallback" if market == "KR" else "financialmodelingprep",
        "source_dataset": dataset,
        "current_price": first_float(row.get("Price_Last"), row.get("current_price")),
        "pe_ratio": first_float(row.get("PER"), row.get("PER_Last"), row.get("pe_ratio"), row.get("trailingPE")),
        "forward_pe": first_float(row.get("ForwardPER"), row.get("Forward_PE"), row.get("forward_pe")),
        "price_to_sales": first_float(row.get("PSR"), row.get("PriceToSales"), row.get("price_to_sales")),
        "price_to_book": first_float(row.get("PBR"), row.get("PBR_Last"), row.get("price_to_book")),
        "total_revenue": first_float(row.get("Revenue"), row.get("Revenue_Last"), row.get("total_revenue")),
        "revenue_growth": first_float(
            row.get("RevenueGrowth"), row.get("RevGrowth"), row.get("RevGrowth_Last"), row.get("revenue_growth")
        ),
        "gross_margin": first_float(row.get("GrossMargin"), row.get("GrossMargin_Last"), row.get("gross_margin")),
        "operating_margin": first_float(
            row.get("OperatingMargin"), row.get("OperatingMargin_Last"), row.get("operating_margin")
        ),
        "debt_to_equity": first_float(
            row.get("DebtToEquity"), row.get("DebtToEquity_Last"), row.get("debt_to_equity")
        ),
        "return_on_equity": first_float(row.get("ROE"), row.get("ROE_Last"), row.get("return_on_equity")),
    }
    return {key: value for key, value in payload.items() if has_value(value)}


def merge_identity_payload(primary: dict | None, fallback: dict | None, ticker: str = "") -> dict:
    merged: dict = {}
    for source in (primary or {}, fallback or {}):
        for key, value in source.items():
            if not has_value(value):
                continue
            current = merged.get(key)
            if key == "name" and has_value(current):
                if _is_missing_kr_name(current, ticker) and not _is_missing_kr_name(value, ticker):
                    merged[key] = value
                continue
            if not has_value(current):
                merged[key] = value
    return merged


def identity_has_valuation(identity: dict | None) -> bool:
    if not identity:
        return False
    return any(
        first_float(identity.get(key)) is not None
        for key in ("pe_ratio", "PER", "PER_Last", "price_to_book", "PBR", "PBR_Last")
    )


class StockIdentityLookup:
    def __init__(self, *, load_storage_df: Callable[[str, str | None], pd.DataFrame]) -> None:
        self._load_storage_df = load_storage_df

    def identity_from_storage(self, ticker: str, market: str) -> dict:
        normal = str(ticker or "").strip().upper()
        datasets = [
            (f"{market}_Final_Portfolio", market),
            (f"{market}_SmallCap_Gems", market),
            (f"{market}_Scored_Stocks", market),
            (f"{market}_Universe", market),
            ("Company_Master", None),
        ]
        identity = identity_payload_from_row({}, normal, market, "")
        found = False
        for dataset, dataset_market in datasets:
            df = self._load_storage_df(dataset, market=dataset_market)
            if df.empty or "Ticker" not in df.columns:
                continue
            hit = df[df["Ticker"].astype(str).str.upper() == normal]
            if hit.empty and market == "KR":
                code = kr_code(normal)
                hit = df[df["Ticker"].astype(str).map(kr_code) == code] if code else hit
            if hit.empty:
                continue
            row = hit.iloc[0].to_dict()
            identity = merge_identity_payload(identity, identity_payload_from_row(row, normal, market, dataset), normal)
            found = True
        return identity if found else identity_payload_from_row({}, normal, market, "")


def price_records_from_yfinance(raw: pd.DataFrame) -> list[dict]:
    if raw.empty:
        return []
    if isinstance(raw.columns, pd.MultiIndex):
        raw.columns = raw.columns.get_level_values(0)
    prices = []
    for date, row in raw.iterrows():
        try:
            prices.append({
                "date": str(date.date()),
                "open": float(row["Open"]),
                "high": float(row["High"]),
                "low": float(row["Low"]),
                "close": float(row["Close"]),
                "volume": safe_float(row.get("Volume")),
            })
        except (KeyError, ValueError, TypeError):
            pass
    return prices


def info_from_cached(identity: dict | None, prices: list[dict]) -> dict:
    identity = identity or {}
    closes = [price.get("close") for price in prices if safe_float(price.get("close")) is not None]
    highs = [price.get("high") for price in prices if safe_float(price.get("high")) is not None]
    lows = [price.get("low") for price in prices if safe_float(price.get("low")) is not None]
    info = {
        "ticker": identity.get("ticker") or identity.get("Ticker"),
        "market": identity.get("market") or identity.get("Market"),
        "name": localized_company_name(
            identity.get("ticker") or identity.get("Ticker"),
            identity.get("name") or identity.get("Name"),
            identity.get("market") or identity.get("Market"),
        ),
        "sector": identity.get("sector") or identity.get("Sector") or "",
        "currency": identity.get("currency"),
        "exchange": identity.get("exchange"),
        "logo_url": identity.get("logo_url"),
        "logo_source": identity.get("logo_source"),
        "market_cap": first_float(identity.get("market_cap"), identity.get("MarketCap"), identity.get("MarketCap_Last")),
        "current_price": first_float(closes[-1] if closes else None, identity.get("current_price"), identity.get("Price_Last")),
        "prev_close": safe_float(closes[-2]) if len(closes) >= 2 else None,
        "week52_high": max(highs) if highs else None,
        "week52_low": min(lows) if lows else None,
        "pe_ratio": first_float(identity.get("pe_ratio"), identity.get("PER"), identity.get("PER_Last")),
        "forward_pe": first_float(identity.get("forward_pe"), identity.get("ForwardPER"), identity.get("Forward_PE")),
        "price_to_sales": first_float(identity.get("price_to_sales"), identity.get("PSR"), identity.get("PriceToSales")),
        "price_to_book": first_float(identity.get("price_to_book"), identity.get("PBR"), identity.get("PBR_Last")),
        "total_revenue": first_float(identity.get("total_revenue"), identity.get("Revenue"), identity.get("Revenue_Last")),
        "revenue_growth": first_float(
            identity.get("revenue_growth"),
            identity.get("RevenueGrowth"),
            identity.get("RevGrowth"),
            identity.get("RevGrowth_Last"),
        ),
        "gross_margin": first_float(
            identity.get("gross_margin"),
            identity.get("GrossMargin"),
            identity.get("GrossMargin_Last"),
        ),
        "operating_margin": first_float(
            identity.get("operating_margin"),
            identity.get("OperatingMargin"),
            identity.get("OperatingMargin_Last"),
        ),
        "debt_to_equity": first_float(
            identity.get("debt_to_equity"),
            identity.get("DebtToEquity"),
            identity.get("DebtToEquity_Last"),
        ),
        "return_on_equity": first_float(identity.get("return_on_equity"), identity.get("ROE"), identity.get("ROE_Last")),
    }
    return {key: value for key, value in info.items() if value not in (None, "")}


def safe_int(value) -> int | None:
    try:
        if value is None:
            return None
        return int(float(value))
    except (TypeError, ValueError):
        return None


def merge_company_profile(info: dict, full: dict) -> dict:
    info.update({
        "name": localized_company_name(
            info.get("ticker"),
            clean_text(full.get("longName") or full.get("shortName") or info.get("name")),
            info.get("market"),
        ),
        "industry": clean_text(full.get("industry")),
        "sector": clean_text(full.get("sector") or info.get("sector")),
        "country": clean_text(full.get("country")),
        "city": clean_text(full.get("city")),
        "exchange": clean_text(full.get("exchange") or full.get("fullExchangeName") or info.get("exchange")),
        "website": clean_text(full.get("website")),
        "employees": safe_int(full.get("fullTimeEmployees")),
        "pe_ratio": first_float(full.get("trailingPE"), info.get("pe_ratio")),
        "forward_pe": first_float(full.get("forwardPE"), info.get("forward_pe")),
        "price_to_sales": first_float(full.get("priceToSalesTrailing12Months"), info.get("price_to_sales")),
        "price_to_book": first_float(full.get("priceToBook"), info.get("price_to_book")),
        "beta": first_float(full.get("beta"), info.get("beta")),
        "total_revenue": first_float(full.get("totalRevenue"), info.get("total_revenue")),
        "revenue_growth": first_float(full.get("revenueGrowth"), info.get("revenue_growth")),
        "gross_margin": first_float(full.get("grossMargins"), info.get("gross_margin")),
        "operating_margin": first_float(full.get("operatingMargins"), info.get("operating_margin")),
        "profit_margin": first_float(full.get("profitMargins"), info.get("profit_margin")),
        "ebitda_margin": first_float(full.get("ebitdaMargins"), info.get("ebitda_margin")),
        "ebitda": first_float(full.get("ebitda"), info.get("ebitda")),
        "free_cashflow": first_float(full.get("freeCashflow"), info.get("free_cashflow")),
        "total_debt": first_float(full.get("totalDebt"), info.get("total_debt")),
        "debt_to_equity": first_float(full.get("debtToEquity"), info.get("debt_to_equity")),
        "return_on_equity": first_float(full.get("returnOnEquity"), info.get("return_on_equity")),
        "target_mean_price": first_float(full.get("targetMeanPrice"), info.get("target_mean_price")),
        "recommendation": clean_text(full.get("recommendationKey")),
    })
    raw_desc = full.get("longBusinessSummary") or ""
    if raw_desc:
        info["description"] = str(raw_desc)[:900]
    return {key: value for key, value in info.items() if value not in (None, "")}
