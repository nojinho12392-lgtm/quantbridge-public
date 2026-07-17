#!/usr/bin/env python3
"""Build and score a local US stock universe without Google Sheets."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from pipeline.data.sec_companyfacts_lake import (
    DEFAULT_OUTPUT_DIR,
    SecCompanyFactsLakeConfig,
    build_quality_history_features,
    build_sec_pit_frame,
    fetch_companyfacts_for_lake,
    write_sec_companyfacts_lake,
)
from pipeline.data.us_local_universe import DEFAULT_US_TICKERS, LocalUsUniverseConfig, build_local_us_universe
from pipeline.scoring.us_local_scorer import (
    build_us_portfolio_candidates,
    build_us_smallcap_candidates,
    score_local_us_stocks,
)
from quantbridge.storage import QuantRepository


def _parse_tickers(text: str) -> list[str]:
    return [item.strip().upper() for item in str(text or "").split(",") if item.strip()]


def _read_tickers_file(path: str) -> list[str]:
    if not path:
        return []
    source = Path(path).expanduser()
    return [
        line.strip().split(",")[0].upper()
        for line in source.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run local US universe + scoring without Sheets")
    parser.add_argument("--tickers", default="", help="Comma-separated US tickers")
    parser.add_argument("--tickers-file", default="", help="Optional newline-delimited ticker file")
    parser.add_argument("--limit", type=int, default=80, help="Trim selected/default ticker list")
    parser.add_argument("--delay", type=float, default=0.05, help="Delay between Yahoo info requests")
    parser.add_argument("--no-prices", action="store_true", help="Skip yfinance momentum download")
    parser.add_argument("--refresh-sec-lake", action="store_true", help="Fetch/cache SEC CompanyFacts for selected tickers before scoring")
    parser.add_argument("--sec-max-requests", type=int, default=100, help="SEC request budget when --refresh-sec-lake is used")
    parser.add_argument("--output-dir", default=str(DEFAULT_OUTPUT_DIR), help="SEC CompanyFacts lake root")
    parser.add_argument("--output-csv", default="artifacts/local_US_Scored_Stocks.csv")
    parser.add_argument("--universe-csv", default="artifacts/local_US_Universe.csv")
    parser.add_argument("--portfolio-csv", default="artifacts/local_US_Final_Portfolio.csv")
    parser.add_argument("--portfolio-size", type=int, default=10)
    parser.add_argument("--smallcap-csv", default="artifacts/local_US_SmallCap_Gems.csv")
    parser.add_argument("--smallcap-size", type=int, default=20)
    parser.add_argument("--no-write-repository", action="store_true", help="Do not write Parquet snapshots")
    parser.add_argument("--snapshot-date", default="", help="Optional snapshot date for repository writes")
    return parser.parse_args()


def _selected_tickers(args: argparse.Namespace) -> list[str]:
    tickers = _parse_tickers(args.tickers) + _read_tickers_file(args.tickers_file)
    if not tickers:
        tickers = list(DEFAULT_US_TICKERS)
    if args.limit:
        tickers = tickers[: args.limit]
    return list(dict.fromkeys(tickers))


def _refresh_sec_lake(tickers: list[str], output_dir: Path, max_requests: int, snapshot_date: str | None) -> None:
    raw, cik_by_ticker = fetch_companyfacts_for_lake(tickers, max_requests=max_requests)
    pit = build_sec_pit_frame(raw, cik_by_ticker=cik_by_ticker, config=SecCompanyFactsLakeConfig(output_dir=output_dir))
    history = build_quality_history_features(pit)
    outputs = write_sec_companyfacts_lake(pit, history, output_dir=output_dir, snapshot_date=snapshot_date)
    print(f"[local-us] SEC lake refreshed: payloads={len(raw)} pit_rows={len(pit)} history_rows={len(history)}")
    for name, path in outputs.items():
        print(f"[local-us] wrote SEC {name}: {path}")


def _write_repository(dataset: str, df: pd.DataFrame, snapshot_date: str | None) -> None:
    clean = df.copy().replace({"": None})
    result = QuantRepository().write_records(
        dataset=dataset,
        records=clean.astype(object).where(pd.notna(clean), None).to_dict(orient="records"),
        market="US",
        snapshot_date=snapshot_date or None,
    )
    target = result.get("parquet_path") or "(repository write skipped by settings)"
    print(f"[local-us] wrote {dataset}: rows={len(df)} | {target}")


def main() -> int:
    args = parse_args()
    tickers = _selected_tickers(args)
    output_dir = Path(args.output_dir).expanduser()
    snapshot_date = args.snapshot_date.strip() or None

    if args.refresh_sec_lake:
        _refresh_sec_lake(tickers, output_dir, args.sec_max_requests, snapshot_date)

    universe = build_local_us_universe(
        tickers,
        config=LocalUsUniverseConfig(limit=args.limit, delay=args.delay, output_dir=output_dir),
    )
    scored = score_local_us_stocks(
        universe,
        output_dir=output_dir,
        download_prices=not args.no_prices,
    )
    portfolio = build_us_portfolio_candidates(scored, size=args.portfolio_size)
    smallcap = build_us_smallcap_candidates(scored, size=args.smallcap_size)

    universe_path = Path(args.universe_csv).expanduser()
    scored_path = Path(args.output_csv).expanduser()
    portfolio_path = Path(args.portfolio_csv).expanduser()
    smallcap_path = Path(args.smallcap_csv).expanduser()
    for path in [universe_path, scored_path, portfolio_path, smallcap_path]:
        path.parent.mkdir(parents=True, exist_ok=True)
    universe.to_csv(universe_path, index=False)
    scored.to_csv(scored_path, index=False)
    portfolio.to_csv(portfolio_path, index=False)
    smallcap.to_csv(smallcap_path, index=False)

    print(f"[local-us] universe rows={len(universe)} | csv={universe_path}")
    print(f"[local-us] scored rows={len(scored)} | csv={scored_path}")
    print(f"[local-us] portfolio rows={len(portfolio)} | csv={portfolio_path}")
    print(f"[local-us] smallcap rows={len(smallcap)} | csv={smallcap_path}")

    if not args.no_write_repository:
        _write_repository("US_Universe", universe, snapshot_date)
        _write_repository("US_Scored_Stocks", scored, snapshot_date)
        if not portfolio.empty:
            _write_repository("US_Final_Portfolio", portfolio, snapshot_date)
        if not smallcap.empty:
            _write_repository("US_SmallCap_Gems", smallcap, snapshot_date)

    if not scored.empty:
        view_cols = [
            "Rank", "Ticker", "Name", "Sector", "Final_Score",
            "Business_Quality_Score", "Investability_Score",
            "Quality_Category", "Quality_Red_Flags",
        ]
        print("\n[local-us] top ranked")
        print(scored[view_cols].head(20).to_string(index=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
