#!/usr/bin/env python3
"""Build a free local SEC CompanyFacts research lake.

Small start:
    python tools/sec_companyfacts_lake.py --tickers AAPL,MSFT,NVDA --max-requests 20

Bulk mode after downloading SEC's ZIP yourself or with --download-bulk:
    python tools/sec_companyfacts_lake.py --from-bulk-zip docs_cache/sec_edgar/bulk/companyfacts.zip --max-tickers 500
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from pipeline.data.sec_companyfacts_lake import (
    DEFAULT_BULK_ZIP,
    DEFAULT_OUTPUT_DIR,
    SecCompanyFactsLakeConfig,
    build_quality_history_features,
    build_sec_pit_frame,
    download_companyfacts_bulk_zip,
    fetch_companyfacts_for_lake,
    iter_companyfacts_zip,
    load_cik_map,
    normalize_tickers,
    write_sec_companyfacts_lake,
)
from pipeline.data.sec_edgar import edgar_user_agent


def _tickers_from_args(args: argparse.Namespace) -> list[str]:
    tickers: list[str] = []
    if args.tickers:
        tickers.extend(part.strip() for part in args.tickers.split(","))
    if args.tickers_file:
        path = Path(args.tickers_file).expanduser()
        tickers.extend(line.strip().split(",")[0] for line in path.read_text().splitlines())
    return normalize_tickers(tickers)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build SEC CompanyFacts local research lake")
    parser.add_argument("--tickers", default="", help="Comma-separated tickers for API/cache mode")
    parser.add_argument("--tickers-file", default="", help="Optional text/CSV file with tickers in first column")
    parser.add_argument("--from-bulk-zip", default="", help="Parse an existing SEC companyfacts.zip")
    parser.add_argument("--download-bulk", action="store_true", help="Download SEC companyfacts.zip before parsing")
    parser.add_argument("--bulk-zip", default=str(DEFAULT_BULK_ZIP), help="Bulk ZIP path")
    parser.add_argument("--output-dir", default=str(DEFAULT_OUTPUT_DIR), help="Local ignored data_lake output dir")
    parser.add_argument("--min-filing-year", type=int, default=2010)
    parser.add_argument("--max-filing-year", type=int, default=0)
    parser.add_argument("--as-of", default="", help="Optional YYYY-MM-DD cut-off for quality-history features")
    parser.add_argument("--max-tickers", type=int, default=0, help="Limit processed tickers")
    parser.add_argument("--max-requests", type=int, default=100, help="API request budget in ticker mode")
    parser.add_argument("--dry-run", action="store_true", help="Print summary without writing Parquet")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    user_agent = edgar_user_agent()
    bulk_zip = Path(args.bulk_zip).expanduser()
    cfg = SecCompanyFactsLakeConfig(
        output_dir=Path(args.output_dir).expanduser(),
        min_filing_year=args.min_filing_year,
        max_filing_year=args.max_filing_year or None,
    )

    if args.download_bulk:
        print(f"[SEC-LAKE] downloading bulk CompanyFacts ZIP to {bulk_zip}")
        download_companyfacts_bulk_zip(output_path=bulk_zip, user_agent=user_agent)

    raw: dict[str, dict] = {}
    cik_by_ticker: dict[str, str] = {}
    tickers = _tickers_from_args(args)
    if args.from_bulk_zip or args.download_bulk:
        zip_path = Path(args.from_bulk_zip or bulk_zip).expanduser()
        if not zip_path.exists():
            raise SystemExit(f"Bulk ZIP not found: {zip_path}")
        cik_map = load_cik_map(user_agent=user_agent)
        selected = set(tickers)
        ticker_by_cik = {
            cik: ticker
            for ticker, cik in cik_map.items()
            if not selected or ticker in selected
        }
        limit = args.max_tickers or None
        for ticker, cik, payload in iter_companyfacts_zip(
            zip_path,
            ticker_by_cik=ticker_by_cik,
            limit=limit,
        ):
            raw[ticker] = payload
            cik_by_ticker[ticker] = cik
        print(f"[SEC-LAKE] loaded {len(raw)} CompanyFacts payloads from bulk ZIP")
    else:
        if not tickers:
            raise SystemExit("Provide --tickers/--tickers-file or use --from-bulk-zip/--download-bulk.")
        if args.max_tickers:
            tickers = tickers[: args.max_tickers]
        raw, cik_by_ticker = fetch_companyfacts_for_lake(
            tickers,
            user_agent=user_agent,
            max_requests=args.max_requests,
        )
        print(f"[SEC-LAKE] fetched/loaded {len(raw)} CompanyFacts payloads from SEC cache/API")

    pit = build_sec_pit_frame(raw, cik_by_ticker=cik_by_ticker, config=cfg)
    features = build_quality_history_features(
        pit,
        as_of=pd.Timestamp(args.as_of) if args.as_of else None,
        windows=cfg.windows,
    )

    print(f"[SEC-LAKE] PIT rows: {len(pit)}")
    print(f"[SEC-LAKE] quality feature rows: {len(features)}")
    if not features.empty:
        preview_cols = [
            "Ticker",
            "History_Years",
            "ROIC_5Y_Median",
            "Revenue_CAGR_5Y",
            "FCF_Positive_Years_5Y",
            "Quality_Persistence_Score",
        ]
        cols = [col for col in preview_cols if col in features.columns]
        print(features[cols].head(10).to_string(index=False))

    if args.dry_run:
        print("[SEC-LAKE] dry run: no files written")
        return 0

    outputs = write_sec_companyfacts_lake(pit, features, output_dir=cfg.output_dir)
    for name, path in outputs.items():
        print(f"[SEC-LAKE] wrote {name}: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
