#!/usr/bin/env python3
"""Build a free local OpenDART research lake for Korean quality history.

Requires a free OpenDART API key in DART_API_KEY, OPENDART_API_KEY, or
QUANT_DART_API_KEY. If the key is missing, the tool still builds features from
the existing local cache when available.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from pipeline.backtest.kr_pit_financials import DEFAULT_CACHE
from pipeline.data.kr_dart_lake import (
    DEFAULT_OUTPUT_DIR,
    KrDartLakeConfig,
    build_kr_quality_history_features,
    fetch_kr_dart_pit_frame,
    normalize_kr_tickers,
    write_kr_dart_lake,
)


def _tickers_from_args(args: argparse.Namespace) -> list[str]:
    tickers: list[str] = []
    if args.tickers:
        tickers.extend(part.strip() for part in args.tickers.split(","))
    if args.tickers_file:
        path = Path(args.tickers_file).expanduser()
        tickers.extend(line.strip().split(",")[0] for line in path.read_text().splitlines())
    return normalize_kr_tickers(tickers)


def _years_from_args(args: argparse.Namespace) -> list[int]:
    if args.years:
        return sorted({int(part.strip()) for part in args.years.split(",") if part.strip()})
    end = args.end_year or (pd.Timestamp.today().year - 1)
    start = args.start_year or (end - 9)
    return list(range(int(start), int(end) + 1))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build OpenDART local research lake")
    parser.add_argument("--tickers", default="", help="Comma-separated KR tickers, e.g. 005930.KS,000660.KS")
    parser.add_argument("--tickers-file", default="", help="Optional text/CSV file with tickers in first column")
    parser.add_argument("--years", default="", help="Comma-separated fiscal years. Overrides --start-year/--end-year")
    parser.add_argument("--start-year", type=int, default=0)
    parser.add_argument("--end-year", type=int, default=0)
    parser.add_argument("--cache-path", default=str(DEFAULT_CACHE), help="CSV cache path for PIT DART rows")
    parser.add_argument("--output-dir", default=str(DEFAULT_OUTPUT_DIR), help="Local ignored data_lake output dir")
    parser.add_argument("--as-of", default="", help="Optional YYYY-MM-DD cut-off for quality-history features")
    parser.add_argument("--max-api-calls", type=int, default=200)
    parser.add_argument("--delay", type=float, default=0.35)
    parser.add_argument("--dry-run", action="store_true", help="Print summary without writing Parquet")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    tickers = _tickers_from_args(args)
    years = _years_from_args(args)
    if not tickers:
        raise SystemExit("Provide --tickers or --tickers-file.")

    cfg = KrDartLakeConfig(output_dir=Path(args.output_dir).expanduser())
    pit = fetch_kr_dart_pit_frame(
        tickers,
        years,
        cache_path=Path(args.cache_path).expanduser(),
        max_api_calls=args.max_api_calls,
        delay=args.delay,
    )
    features = build_kr_quality_history_features(
        pit,
        as_of=pd.Timestamp(args.as_of) if args.as_of else None,
        windows=cfg.windows,
    )

    print(f"[KR-DART-LAKE] tickers: {len(tickers)} | years: {years[0]}-{years[-1]}")
    print(f"[KR-DART-LAKE] PIT rows: {len(pit)}")
    print(f"[KR-DART-LAKE] quality feature rows: {len(features)}")
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
        print("[KR-DART-LAKE] dry run: no files written")
        return 0

    outputs = write_kr_dart_lake(pit, features, output_dir=cfg.output_dir)
    for name, path in outputs.items():
        print(f"[KR-DART-LAKE] wrote {name}: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
