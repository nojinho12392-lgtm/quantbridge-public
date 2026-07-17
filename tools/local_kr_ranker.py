#!/usr/bin/env python3
"""Build and score a local Korean stock universe without Google Sheets."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from pipeline.data.kr_dart_lake import DEFAULT_OUTPUT_DIR
from pipeline.data.kr_local_universe import LocalKrUniverseConfig, build_local_kr_universe
from pipeline.scoring.kr_local_scorer import (
    build_kr_portfolio_candidates,
    build_kr_smallcap_candidates,
    score_local_kr_stocks,
)
from quantbridge.storage import QuantRepository


def _parse_tickers(text: str) -> list[str]:
    return [item.strip() for item in str(text or "").split(",") if item.strip()]


def _read_tickers_file(path: str) -> list[str]:
    if not path:
        return []
    source = Path(path).expanduser()
    return [
        line.strip()
        for line in source.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run local KR universe + scoring without Sheets")
    parser.add_argument("--tickers", default="", help="Comma-separated KR tickers, e.g. 005930.KS,000270.KS")
    parser.add_argument("--tickers-file", default="", help="Optional newline-delimited ticker file")
    parser.add_argument("--kospi-limit", type=int, default=30, help="Naver fallback KOSPI ticker count")
    parser.add_argument("--kosdaq-limit", type=int, default=0, help="Naver fallback KOSDAQ ticker count")
    parser.add_argument("--limit", type=int, default=0, help="Trim the selected ticker list before fetching")
    parser.add_argument("--delay", type=float, default=0.12, help="Delay between Naver item-page requests")
    parser.add_argument("--no-prices", action="store_true", help="Skip yfinance momentum download")
    parser.add_argument("--output-dir", default=str(DEFAULT_OUTPUT_DIR), help="OpenDART lake root")
    parser.add_argument("--output-csv", default="artifacts/local_KR_Scored_Stocks.csv")
    parser.add_argument("--universe-csv", default="artifacts/local_KR_Universe.csv")
    parser.add_argument("--portfolio-csv", default="artifacts/local_KR_Final_Portfolio.csv")
    parser.add_argument("--portfolio-size", type=int, default=10, help="Top-N scored stocks to publish as KR_Final_Portfolio")
    parser.add_argument("--no-portfolio", action="store_true", help="Skip KR_Final_Portfolio candidate publishing")
    parser.add_argument("--smallcap-csv", default="artifacts/local_KR_SmallCap_Gems.csv")
    parser.add_argument("--smallcap-size", type=int, default=20, help="Top-N small-cap candidates to publish as KR_SmallCap_Gems")
    parser.add_argument("--no-smallcap", action="store_true", help="Skip KR_SmallCap_Gems candidate publishing")
    parser.add_argument("--no-write-repository", action="store_true", help="Do not write Parquet snapshots")
    parser.add_argument("--snapshot-date", default="", help="Optional snapshot date for repository writes")
    return parser.parse_args()


def _write_repository(dataset: str, df: pd.DataFrame, snapshot_date: str | None) -> None:
    clean = df.copy().replace({"": None})
    result = QuantRepository().write_records(
        dataset=dataset,
        records=clean.astype(object).where(pd.notna(clean), None).to_dict(orient="records"),
        market="KR",
        snapshot_date=snapshot_date or None,
    )
    target = result.get("parquet_path") or "(repository write skipped by settings)"
    print(f"[local-kr] wrote {dataset}: rows={len(df)} | {target}")


def main() -> int:
    args = parse_args()
    tickers = _parse_tickers(args.tickers) + _read_tickers_file(args.tickers_file)
    if args.limit and tickers:
        tickers = tickers[: args.limit]

    output_dir = Path(args.output_dir).expanduser()
    cfg = LocalKrUniverseConfig(
        kospi_limit=args.kospi_limit,
        kosdaq_limit=args.kosdaq_limit,
        delay=args.delay,
        output_dir=output_dir,
    )

    universe = build_local_kr_universe(tickers or None, config=cfg)
    if args.limit and not tickers:
        universe = universe.head(args.limit).copy()
    scored = score_local_kr_stocks(
        universe,
        output_dir=output_dir,
        download_prices=not args.no_prices,
    )
    portfolio = (
        build_kr_portfolio_candidates(scored, size=args.portfolio_size)
        if not args.no_portfolio
        else pd.DataFrame()
    )
    smallcap = (
        build_kr_smallcap_candidates(scored, size=args.smallcap_size)
        if not args.no_smallcap
        else pd.DataFrame()
    )

    universe_path = Path(args.universe_csv).expanduser()
    scored_path = Path(args.output_csv).expanduser()
    portfolio_path = Path(args.portfolio_csv).expanduser()
    smallcap_path = Path(args.smallcap_csv).expanduser()
    universe_path.parent.mkdir(parents=True, exist_ok=True)
    scored_path.parent.mkdir(parents=True, exist_ok=True)
    portfolio_path.parent.mkdir(parents=True, exist_ok=True)
    smallcap_path.parent.mkdir(parents=True, exist_ok=True)
    universe.to_csv(universe_path, index=False)
    scored.to_csv(scored_path, index=False)
    if not args.no_portfolio:
        portfolio.to_csv(portfolio_path, index=False)
    if not args.no_smallcap:
        smallcap.to_csv(smallcap_path, index=False)

    print(f"[local-kr] universe rows={len(universe)} | csv={universe_path}")
    print(f"[local-kr] scored rows={len(scored)} | csv={scored_path}")
    if not args.no_portfolio:
        print(f"[local-kr] portfolio rows={len(portfolio)} | csv={portfolio_path}")
    if not args.no_smallcap:
        print(f"[local-kr] smallcap rows={len(smallcap)} | csv={smallcap_path}")

    if not args.no_write_repository:
        snapshot_date = args.snapshot_date.strip() or None
        _write_repository("KR_Universe", universe, snapshot_date)
        _write_repository("KR_Scored_Stocks", scored, snapshot_date)
        if not args.no_portfolio:
            _write_repository("KR_Final_Portfolio", portfolio, snapshot_date)
        if not args.no_smallcap and not smallcap.empty:
            _write_repository("KR_SmallCap_Gems", smallcap, snapshot_date)

    if not scored.empty:
        view_cols = [
            "Rank",
            "Ticker",
            "Name",
            "Sector",
            "Final_Score",
            "Business_Quality_Score",
            "Investability_Score",
            "Quality_Category",
            "Quality_Red_Flags",
        ]
        print("\n[local-kr] top ranked")
        print(scored[view_cols].head(20).to_string(index=False))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
