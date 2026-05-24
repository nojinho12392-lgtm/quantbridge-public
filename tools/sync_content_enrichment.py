#!/usr/bin/env python3
"""Sync app-facing metadata and content quality rows into service storage."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from api.services.etf_insights import ETF_UNIVERSE_CSV, default_etf_insights, normalize_etf_item
from quantbridge.storage import QuantRepository

try:
    from .etf_holdings_refresh import refresh_etf_holdings
except ImportError:
    from etf_holdings_refresh import refresh_etf_holdings


BASE_DATASETS: tuple[tuple[str, str], ...] = (
    ("US_Final_Portfolio", "US"),
    ("KR_Final_Portfolio", "KR"),
    ("US_SmallCap_Gems", "US"),
    ("KR_SmallCap_Gems", "KR"),
)

SCORED_DATASETS: tuple[tuple[str, str], ...] = (
    ("US_Scored_Stocks", "US"),
    ("KR_Scored_Stocks", "KR"),
)

IDENTITY_NUMERIC_ALIASES: dict[str, tuple[str, ...]] = {
    "market_cap": ("MarketCap", "MarketCap_Last", "market_cap"),
    "current_price": ("Price_Last", "Current_Price", "current_price"),
    "pe_ratio": ("PER", "PER_Last", "pe_ratio", "trailingPE"),
    "forward_pe": ("ForwardPER", "Forward_PE", "forward_pe"),
    "price_to_sales": ("PSR", "PriceToSales", "price_to_sales"),
    "price_to_book": ("PBR", "PBR_Last", "price_to_book"),
    "total_revenue": ("Revenue", "Revenue_Last", "total_revenue"),
    "revenue_growth": ("RevenueGrowth", "RevGrowth", "RevGrowth_Last", "revenue_growth"),
    "gross_margin": ("GrossMargin", "GrossMargin_Last", "gross_margin"),
    "operating_margin": ("OperatingMargin", "OperatingMargin_Last", "operating_margin"),
    "debt_to_equity": ("DebtToEquity", "DebtToEquity_Last", "debt_to_equity"),
    "return_on_equity": ("ROE", "ROE_Last", "return_on_equity"),
}


def clean_text(value) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text or text.lower() in {"nan", "none", "null"}:
        return None
    return text


def normal_ticker(value) -> str:
    return str(value or "").strip().upper()


def to_float(value) -> float | None:
    try:
        text = str(value).replace(",", "").strip()
        if not text or text.lower() in {"nan", "none", "null"}:
            return None
        number = float(text)
    except (TypeError, ValueError):
        return None
    return number


def first_float(row: dict, aliases: Iterable[str]) -> float | None:
    for alias in aliases:
        parsed = to_float(row.get(alias))
        if parsed is not None:
            return parsed
    return None


def first_text(row: dict, aliases: Iterable[str]) -> str | None:
    for alias in aliases:
        text = clean_text(row.get(alias))
        if text:
            return text
    return None


def kr_code(value: str) -> str:
    match = re.search(r"(\d{6})", str(value or "").strip().upper())
    return match.group(1) if match else ""


def infer_market(ticker: str, fallback: str) -> str:
    return "KR" if re.fullmatch(r"\d{6}(?:\.(?:KS|KQ))?", normal_ticker(ticker)) else fallback


def logo_url(ticker: str, market: str) -> tuple[str, str]:
    normal = normal_ticker(ticker)
    if market == "KR":
        code = kr_code(normal)
        if not code:
            return "", ""
        overrides = {
            "064400": "https://www.lgcns.com/etc.clientlibs/lgcns/clientlibs/clientlib-site/resources/image/common/logo-og-0807.png",
            "267250": f"https://file.alphasquare.co.kr/media/images/stock_logo/kr/{code}.png",
        }
        if code in overrides:
            return overrides[code], "curated_override"
        return f"https://static.toss.im/png-icons/securities/icn-sec-fill-{code}.png", "toss"
    return f"https://financialmodelingprep.com/image-stock/{normal}.png" if normal else "", "financialmodelingprep"


def has_value(value) -> bool:
    return clean_text(value) is not None if isinstance(value, str) or value is None else value is not None


def missing_name(name: str | None, ticker: str) -> bool:
    text = clean_text(name)
    if not text:
        return True
    compact_name = re.sub(r"\W+", "", text).upper()
    compact_ticker = re.sub(r"\W+", "", normal_ticker(ticker))
    return compact_name == compact_ticker or compact_name == kr_code(ticker)


def identity_from_row(row: dict, ticker: str, market: str, dataset: str) -> dict:
    ticker = normal_ticker(ticker)
    logo, source = logo_url(ticker, market)
    identity = {
        "market": market,
        "ticker": ticker,
        "name": first_text(row, ("Name", "name", "Company", "company")),
        "sector": first_text(row, ("Sector", "sector", "Industry", "industry")),
        "currency": first_text(row, ("Currency", "currency")) or ("KRW" if market == "KR" else "USD"),
        "exchange": first_text(row, ("Exchange", "exchange")),
        "logo_url": logo,
        "logo_source": source,
        "source_dataset": dataset,
    }
    for target, aliases in IDENTITY_NUMERIC_ALIASES.items():
        parsed = first_float(row, aliases)
        if parsed is not None:
            identity[target] = parsed
    return {key: value for key, value in identity.items() if has_value(value)}


def better_identity(existing: dict, candidate: dict) -> dict:
    merged = dict(existing)
    ticker = clean_text(existing.get("ticker")) or clean_text(candidate.get("ticker")) or ""
    for key, value in candidate.items():
        if not has_value(value):
            continue
        current = merged.get(key)
        if key == "name" and not missing_name(current, ticker):
            continue
        if not has_value(current) or (key == "name" and missing_name(current, ticker)):
            merged[key] = value
    return merged


def load_dataset_identities(
    repo: QuantRepository,
    *,
    markets: set[str],
    include_scored: bool,
    limit: int | None = None,
) -> list[dict]:
    datasets = BASE_DATASETS + (SCORED_DATASETS if include_scored else ())
    identities: dict[tuple[str, str], dict] = {}
    for dataset, fallback_market in datasets:
        if fallback_market not in markets:
            continue
        frame = repo.read_dataframe(dataset, market=fallback_market)
        if frame.empty or "Ticker" not in frame.columns:
            continue
        for _, raw_row in frame.iterrows():
            row = raw_row.to_dict()
            ticker = normal_ticker(row.get("Ticker"))
            if not ticker:
                continue
            market = infer_market(ticker, fallback_market)
            if market not in markets:
                continue
            identity = identity_from_row(row, ticker, market, dataset)
            key = (market, identity["ticker"])
            identities[key] = better_identity(identities.get(key, {}), identity)
            if limit and len(identities) >= limit:
                return list(identities.values())
    return list(identities.values())


def build_etf_rows(
    source: Path,
    limit: int | None = None,
    *,
    refresh_holdings: bool = False,
    refresh_holdings_limit: int | None = None,
) -> list[dict]:
    rows: list[dict] = []
    items = [normalize_etf_item(raw) for raw in default_etf_insights(source)]
    if refresh_holdings:
        effective_refresh_limit = refresh_holdings_limit if refresh_holdings_limit is not None else limit
        items = refresh_etf_holdings(items, limit=effective_refresh_limit)
    for index, item in enumerate(items, start=1):
        if limit is not None and len(rows) >= limit:
            break
        rows.append({
            "Rank": item["rank"] or index,
            "Ticker": item["ticker"],
            "Name": item["name"],
            "Market": item["region"],
            "Category": item["category"],
            "Theme": item["theme"],
            "Summary": item["summary"],
            "ExpenseRatio": item["expenseRatio"],
            "AUM": item["aum"],
            "Distribution": item["distribution"],
            "Outlook": item["outlook"],
            "Risk": item["risk"],
            "AsOf": item.get("asOf") or "",
            "DataSource": item.get("dataSource") or "curated_universe",
            "holdings": item["holdings"],
            "exposures": item["exposures"],
            "HoldingsRefresh": item.get("_holdingsRefresh") or {},
        })
    return rows


def etf_price_ticker(ticker: str, market: str) -> str:
    normal = normal_ticker(ticker)
    if market == "KR":
        code = kr_code(normal)
        return f"{code}.KS" if code else normal
    return normal


def etf_identities(etf_rows: Iterable[dict], markets: set[str]) -> list[dict]:
    identities: dict[tuple[str, str], dict] = {}
    for row in etf_rows:
        market = normal_ticker(row.get("Market")) or "US"
        if market not in markets:
            continue
        ticker = etf_price_ticker(str(row.get("Ticker") or ""), market)
        if not ticker:
            continue
        identity = identity_from_row(
            {
                **row,
                "Sector": row.get("Category"),
                "Currency": "KRW" if market == "KR" else "USD",
                "Exchange": "KRX" if market == "KR" else "ETF",
            },
            ticker,
            market,
            "ETF_Insights",
        )
        identity["asset_type"] = "ETF"
        identity["theme"] = row.get("Theme")
        identity["summary"] = row.get("Summary")
        key = (market, ticker)
        identities[key] = better_identity(identities.get(key, {}), identity)
    return list(identities.values())


def merge_identities(*groups: Iterable[dict]) -> list[dict]:
    merged: dict[tuple[str, str], dict] = {}
    for group in groups:
        for identity in group:
            market = normal_ticker(identity.get("market"))
            ticker = normal_ticker(identity.get("ticker"))
            if not market or not ticker:
                continue
            key = (market, ticker)
            merged[key] = better_identity(merged.get(key, {}), identity)
    return list(merged.values())


def quality_records(identities: list[dict], etf_rows: list[dict]) -> list[dict]:
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    counts = Counter()
    issues: list[dict] = []
    for identity in identities:
        ticker = normal_ticker(identity.get("ticker"))
        market = normal_ticker(identity.get("market")) or "GLOBAL"
        source = clean_text(identity.get("source_dataset")) or "unknown"
        counts[f"identity.{market}"] += 1
        counts[f"source.{source}"] += 1

        checks = {
            "missing_name": missing_name(clean_text(identity.get("name")), ticker),
            "missing_sector": clean_text(identity.get("sector")) is None,
            "missing_logo": clean_text(identity.get("logo_url")) is None,
            "missing_market_cap": identity.get("asset_type") != "ETF" and to_float(identity.get("market_cap")) is None,
        }
        for issue, failed in checks.items():
            if not failed:
                continue
            counts[issue] += 1
            if len(issues) < 100:
                issues.append({
                    "Record_Key": f"{issue}:{market}:{ticker}",
                    "Ticker": ticker,
                    "Market": market,
                    "Issue": issue,
                    "Name": identity.get("name"),
                    "Source": source,
                    "Observed_At": now,
                    "Severity": "warning",
                })

    counts["etf.rows"] = len(etf_rows)
    summary = {
        "Record_Key": "summary",
        "Ticker": "SUMMARY",
        "Market": "GLOBAL",
        "Metric": "content_enrichment",
        "Identity_Count": len(identities),
        "ETF_Row_Count": len(etf_rows),
        "Missing_Name_Count": counts["missing_name"],
        "Missing_Sector_Count": counts["missing_sector"],
        "Missing_Logo_Count": counts["missing_logo"],
        "Missing_Market_Cap_Count": counts["missing_market_cap"],
        "Observed_At": now,
        "Status": "check" if issues else "ok",
        "Counts": dict(counts),
    }
    return [summary, *issues]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Sync app metadata, ETF content, and content quality rows.")
    parser.add_argument("--source", type=Path, default=ETF_UNIVERSE_CSV, help="ETF universe CSV path.")
    parser.add_argument("--markets", default="US,KR", help="Comma-separated markets to sync.")
    parser.add_argument("--include-scored", action="store_true", help="Also read scored-stock datasets.")
    parser.add_argument("--skip-etfs", action="store_true", help="Do not sync ETF_Insights rows.")
    parser.add_argument("--refresh-etf-holdings", action="store_true", help="Refresh supported ETF holdings from live providers before writing ETF_Insights.")
    parser.add_argument("--refresh-etf-holdings-limit", type=int, default=None, help="Optional maximum number of ETFs to attempt for live holdings.")
    parser.add_argument("--limit", type=int, default=None, help="Optional identity/ETF row limit for smoke tests.")
    parser.add_argument("--dry-run", action="store_true", help="Print a summary without writing storage.")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    markets = {normal_ticker(market) for market in args.markets.split(",") if clean_text(market)}
    repo = QuantRepository()

    company_identities = load_dataset_identities(
        repo,
        markets=markets,
        include_scored=args.include_scored,
        limit=args.limit,
    )
    etf_rows = build_etf_rows(
        args.source,
        args.limit,
        refresh_holdings=args.refresh_etf_holdings,
        refresh_holdings_limit=args.refresh_etf_holdings_limit,
    )
    all_identities = merge_identities(
        company_identities,
        [] if args.skip_etfs else etf_identities(etf_rows, markets),
    )
    report_rows = quality_records(all_identities, [] if args.skip_etfs else etf_rows)

    summary = {
        "markets": sorted(markets),
        "companyIdentities": len(company_identities),
        "totalIdentities": len(all_identities),
        "etfRows": 0 if args.skip_etfs else len(etf_rows),
        "etfHoldingsRefresh": etf_rows[0].get("HoldingsRefresh", {}) if etf_rows else {},
        "qualityRows": len(report_rows),
        "missingName": report_rows[0]["Missing_Name_Count"],
        "missingSector": report_rows[0]["Missing_Sector_Count"],
        "missingLogo": report_rows[0]["Missing_Logo_Count"],
        "missingMarketCap": report_rows[0]["Missing_Market_Cap_Count"],
    }

    if args.dry_run:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0

    for identity in all_identities:
        repo.upsert_identity(identity)

    today = date.today().isoformat()
    if not args.skip_etfs:
        repo.write_records("ETF_Insights", etf_rows, market=None, snapshot_date=today)
    repo.write_records("Content_Quality_Report", report_rows, market=None, snapshot_date=today)
    repo.record_run(
        run_id=f"content-enrichment-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}",
        runner="sync_content_enrichment",
        status="success",
        payload=summary,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
