#!/usr/bin/env python3
"""Warm the service cache used by app stock-detail screens.

This job reads the latest portfolio/small-cap rows from QuantRepository, stores
company identity metadata, and preloads OHLCV rows into PostgreSQL. The app/API
can then render detail charts without waiting on yfinance for every tap.
"""

from __future__ import annotations

import argparse
import re
import sys
import time
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quantbridge.price_snapshots import _kr_code, _normalize_for_yfinance
from quantbridge.storage import QuantRepository
from quantbridge.ticker_policy import is_banned_ticker


BASE_DATASETS = [
    ("US_Final_Portfolio", "US"),
    ("KR_Final_Portfolio", "KR"),
    ("US_SmallCap_Gems", "US"),
    ("KR_SmallCap_Gems", "KR"),
]

SCORED_DATASETS = [
    ("US_Scored_Stocks", "US"),
    ("KR_Scored_Stocks", "KR"),
]

UNIVERSE_DATASETS = [
    ("US_Universe", "US"),
    ("KR_Universe", "KR"),
]


def _infer_market(ticker: str, fallback: str) -> str:
    text = str(ticker or "").strip().upper()
    if re.fullmatch(r"\d{6}(?:\.(?:KS|KQ))?", text):
        return "KR"
    return fallback


def _normal_target_ticker(ticker: str, market: str) -> str:
    return _normalize_for_yfinance(str(ticker or "").strip().upper(), market)


def _logo_url(ticker: str, market: str) -> tuple[str, str]:
    normal = str(ticker or "").strip().upper()
    if market == "KR":
        code = re.sub(r"\D", "", normal)[:6]
        if code == "064400":
            return (
                "https://www.lgcns.com/etc.clientlibs/lgcns/clientlibs/clientlib-site/resources/image/common/logo-og-0807.png",
                "lgcns",
            )
        if code == "267250":
            return (
                f"https://file.alphasquare.co.kr/media/images/stock_logo/kr/{code}.png",
                "alphasquare",
            )
        return (
            f"https://static.toss.im/png-icons/securities/icn-sec-fill-{code}.png" if code else "",
            "toss",
        )
    return (f"https://financialmodelingprep.com/image-stock/{normal}.png", "financialmodelingprep")


def _to_float(value) -> float | None:
    try:
        text = str(value).replace(",", "").strip()
        return float(text) if text else None
    except (TypeError, ValueError):
        return None


def _price_records(raw: pd.DataFrame) -> list[dict]:
    if raw.empty:
        return []
    if isinstance(raw.columns, pd.MultiIndex):
        raw.columns = raw.columns.get_level_values(0)
    rows = []
    for d, row in raw.iterrows():
        try:
            rows.append({
                "date": str(d.date()),
                "open": float(row["Open"]),
                "high": float(row["High"]),
                "low": float(row["Low"]),
                "close": float(row["Close"]),
                "volume": _to_float(row.get("Volume")),
            })
        except Exception:
            pass
    return rows


def _add_target(
    targets: dict[tuple[str, str], dict],
    *,
    ticker: str,
    market: str,
    name,
    sector,
    market_cap,
    source_dataset: str,
) -> None:
    normal = _normal_target_ticker(ticker, market)
    if not normal or is_banned_ticker(normal):
        return
    logo, logo_source = _logo_url(normal, market)
    key = (market, normal)
    if key in targets:
        return
    targets[key] = {
        "market": market,
        "ticker": normal,
        "name": name,
        "sector": sector,
        "market_cap": _to_float(market_cap),
        "currency": "KRW" if market == "KR" else "USD",
        "logo_url": logo,
        "logo_source": logo_source,
        "source_dataset": source_dataset,
    }


def _load_sector_targets(targets: dict[tuple[str, str], dict], markets: set[str], limit: int | None) -> None:
    try:
        from api.server import _SECTOR_THEME_RULES, _SECTOR_THEME_SEED_GROUPS
    except Exception as exc:
        print(f"[warm-cache] sector target import skipped: {type(exc).__name__}: {exc}")
        return

    for theme, members in _SECTOR_THEME_SEED_GROUPS.items():
        for ticker, name, market, sector in members:
            market = str(market or "").strip().upper()
            if market not in markets:
                continue
            _add_target(
                targets,
                ticker=ticker,
                market=market,
                name=name,
                sector=sector or theme,
                market_cap=None,
                source_dataset="Sector_Theme_Seeds",
            )
            if limit and len(targets) >= limit:
                return

    for rule in _SECTOR_THEME_RULES:
        theme = str(rule.get("label") or "").strip()
        for ticker in sorted(rule.get("tickers") or []):
            market = "KR" if _kr_code(ticker) else "US"
            if market not in markets:
                continue
            _add_target(
                targets,
                ticker=ticker,
                market=market,
                name=ticker,
                sector=theme,
                market_cap=None,
                source_dataset="Sector_Theme_Rules",
            )
            if limit and len(targets) >= limit:
                return


def _load_targets(
    repo: QuantRepository,
    include_scored: bool,
    include_universe: bool,
    include_sector_seeds: bool,
    limit: int | None,
    markets: set[str],
) -> list[dict]:
    datasets = BASE_DATASETS
    if include_scored:
        datasets += SCORED_DATASETS
    if include_universe:
        datasets += UNIVERSE_DATASETS
    targets: dict[tuple[str, str], dict] = {}
    for dataset, fallback_market in datasets:
        if fallback_market not in markets:
            continue
        df = repo.read_dataframe(dataset, market=fallback_market)
        if df.empty or "Ticker" not in df.columns:
            continue
        for _, row in df.iterrows():
            ticker = str(row.get("Ticker") or "").strip().upper()
            if not ticker or is_banned_ticker(ticker):
                continue
            market = _infer_market(ticker, fallback_market)
            _add_target(
                targets,
                ticker=ticker,
                market=market,
                name=row.get("Name"),
                sector=row.get("Sector"),
                market_cap=row.get("MarketCap"),
                source_dataset=dataset,
            )
            if limit and len(targets) >= limit:
                return list(targets.values())
    if include_sector_seeds:
        _load_sector_targets(targets, markets, limit)
    return list(targets.values())


def _history_complete(repo: QuantRepository, ticker: str, market: str, period: str) -> bool:
    try:
        prices = repo.read_prices(ticker, period=period, market=market)
    except Exception:
        return False
    if prices.empty or "date" not in prices.columns:
        return False

    min_points_by_period = {
        "1mo": 15,
        "3mo": 45,
        "6mo": 90,
        "1y": 180,
        "2y": 360,
        "3y": 540,
        "5y": 900,
    }
    min_span_days_by_period = {
        "1mo": 20,
        "3mo": 60,
        "6mo": 120,
        "1y": 240,
        "2y": 480,
        "3y": 720,
        "5y": 1200,
    }
    dates = pd.to_datetime(prices["date"], errors="coerce").dropna().sort_values()
    if len(dates) >= min_points_by_period.get(period, 90):
        return True
    if len(dates) < 2:
        return False
    span_days = (dates.iloc[-1] - dates.iloc[0]).days
    return span_days >= min_span_days_by_period.get(period, 120)


def main() -> int:
    parser = argparse.ArgumentParser(description="Warm QuantBridge detail-screen Postgres cache")
    parser.add_argument("--period", default="5y", choices=["1mo", "3mo", "6mo", "1y", "2y", "3y", "5y"])
    parser.add_argument("--limit", type=int, default=None, help="Limit number of tickers for a smoke test")
    parser.add_argument("--markets", default="US,KR", help="Comma-separated markets: US,KR")
    parser.add_argument("--include-scored", action="store_true", default=True, help="Also warm full scored-stock lists")
    parser.add_argument("--skip-scored", action="store_false", dest="include_scored", help="Skip full scored-stock lists")
    parser.add_argument("--include-universe", action="store_true", default=True, help="Also warm broad stock universes used by sector themes")
    parser.add_argument("--skip-universe", action="store_false", dest="include_universe", help="Skip broad stock universes")
    parser.add_argument("--include-sector-seeds", action="store_true", default=True, help="Also warm manually curated sector theme members")
    parser.add_argument("--skip-sector-seeds", action="store_false", dest="include_sector_seeds", help="Skip manually curated sector theme members")
    parser.add_argument("--recent-period", default="10d", help="Daily tail to append when full history already exists")
    parser.add_argument("--full-refresh", action="store_true", help="Always download the full requested period")
    parser.add_argument("--delay", type=float, default=0.15, help="Delay between yfinance calls")
    args = parser.parse_args()

    try:
        import yfinance as yf
    except ImportError:
        print("❌ yfinance is not installed")
        return 1

    repo = QuantRepository()
    markets = {m.strip().upper() for m in args.markets.split(",") if m.strip()}
    targets = _load_targets(
        repo,
        args.include_scored,
        args.include_universe,
        args.include_sector_seeds,
        args.limit,
        markets,
    )
    print(f"[warm-cache] targets={len(targets)} period={args.period} markets={','.join(sorted(markets))}")

    ok = 0
    empty = 0
    for idx, identity in enumerate(targets, 1):
        ticker = identity["ticker"]
        market = identity["market"]
        repo.upsert_identity(identity)
        try:
            download_period = args.period
            if not args.full_refresh and _history_complete(repo, ticker, market, args.period):
                download_period = args.recent_period
            raw = yf.download(ticker, period=download_period, auto_adjust=True, progress=False, ignore_tz=True)
            prices = _price_records(raw)
            if prices:
                rows = repo.upsert_prices(ticker, market, prices, source="yfinance")
                ok += 1
                print(f"  {idx:>3}/{len(targets)} {ticker:<12} period={download_period:<4} prices={rows:>4}")
            else:
                empty += 1
                print(f"  {idx:>3}/{len(targets)} {ticker:<12} period={download_period:<4} prices=empty")
        except Exception as exc:
            empty += 1
            print(f"  {idx:>3}/{len(targets)} {ticker:<12} failed={type(exc).__name__}: {exc}")
        time.sleep(max(args.delay, 0))

    print(f"[warm-cache] done ok={ok} empty_or_failed={empty}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
