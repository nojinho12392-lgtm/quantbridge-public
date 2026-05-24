#!/usr/bin/env python3
"""Seed ETF insights into QuantBridge storage for the Azure API.

This keeps the mobile apps pointed at the backend while still allowing the ETF
universe to be edited and synced without shipping new app binaries.
"""

from __future__ import annotations

import sys
import argparse
import json
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from api.services.etf_insights import ETF_UNIVERSE_CSV, default_etf_insights, normalize_etf_item

try:
    from .etf_holdings_refresh import refresh_etf_holdings
except ImportError:
    from etf_holdings_refresh import refresh_etf_holdings


def build_rows(
    source: Path,
    limit: int | None = None,
    *,
    refresh_holdings: bool = False,
    refresh_holdings_limit: int | None = None,
) -> list[dict]:
    rows = []
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


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Sync curated ETF insights into QuantBridge storage.")
    parser.add_argument("--source", type=Path, default=ETF_UNIVERSE_CSV, help="ETF universe CSV path.")
    parser.add_argument("--limit", type=int, default=None, help="Optional maximum rows to sync.")
    parser.add_argument("--refresh-holdings", action="store_true", help="Refresh supported ETF holdings from live providers before writing storage.")
    parser.add_argument("--refresh-holdings-limit", type=int, default=None, help="Optional maximum number of ETFs to attempt for live holdings.")
    parser.add_argument("--dry-run", action="store_true", help="Validate and print a summary without writing storage.")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    rows = build_rows(
        args.source,
        args.limit,
        refresh_holdings=args.refresh_holdings,
        refresh_holdings_limit=args.refresh_holdings_limit,
    )
    if args.dry_run:
        refresh_stats = rows[0].get("HoldingsRefresh", {}) if rows else {}
        preview = {
            "source": str(args.source),
            "rows": len(rows),
            "firstTickers": [row["Ticker"] for row in rows[:10]],
            "markets": sorted({row["Market"] for row in rows}),
            "categories": sorted({row["Category"] for row in rows}),
            "holdingsRefresh": refresh_stats,
        }
        print(json.dumps(preview, ensure_ascii=False, indent=2))
        return 0

    from quantbridge.config import get_settings
    from quantbridge.storage import QuantRepository

    repo = QuantRepository(get_settings())
    result = repo.write_records("ETF_Insights", rows, market=None, snapshot_date=date.today())
    print(f"Synced {len(rows)} ETF insight rows: {result}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
