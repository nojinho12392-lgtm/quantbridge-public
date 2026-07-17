#!/usr/bin/env python3
"""Precompute mobile API payloads into PostgreSQL app_api_snapshots.

The app should read prepared server snapshots first and only fall back to
expensive request-time assembly when a snapshot is missing. This script runs
the same mobile cache warm jobs as the API, but disables snapshot reads so each
job refreshes and persists the latest payload.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import os
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def _clear_api_process_cache(api_url: str, timeout: float) -> None:
    if not api_url:
        return
    url = f"{api_url.rstrip('/')}/cache/clear"
    request = urllib.request.Request(url, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        response.read()


def _run_job(name: str, job) -> dict:
    started = time.perf_counter()
    try:
        payload = job()
        return {
            "name": name,
            "status": "OK",
            "count": _payload_count(payload),
            "elapsed_ms": round((time.perf_counter() - started) * 1000, 1),
        }
    except Exception as exc:
        return {
            "name": name,
            "status": "FAIL",
            "error": str(exc)[:240],
            "elapsed_ms": round((time.perf_counter() - started) * 1000, 1),
        }


def _payload_count(payload: object) -> int | None:
    if isinstance(payload, list):
        return len(payload)
    if not isinstance(payload, dict):
        return None
    for key in ("stocks", "items", "themes", "events", "indices", "metrics", "prices"):
        value = payload.get(key)
        if isinstance(value, list):
            return len(value)
    item = payload.get("item")
    if isinstance(item, dict):
        members = item.get("members")
        if isinstance(members, list):
            return len(members)
    return None


def _jobs(include_detail: bool) -> list[tuple[str, object]]:
    # Must be set before importing api.server so _cached does not serve an old
    # app_api_snapshots row while we are trying to refresh that same row.
    os.environ["QUANT_API_READ_APP_SNAPSHOTS"] = "0"
    os.environ.setdefault("QUANT_API_WRITE_APP_SNAPSHOTS", "1")

    from api import server

    server._clear_runtime_state()
    job_factory = getattr(server, "_api_cache_warm_jobs", None)
    if job_factory is not None:
        jobs = list(job_factory.primary_jobs())
        stock_tickers = list(job_factory.stock_tickers())
        etf_tickers = list(job_factory.etf_tickers())
    else:
        jobs = list(server._cache_warm_jobs())
        stock_tickers = list(server._cache_warm_stock_tickers())
        etf_tickers = list(server._cache_warm_etf_tickers())
    if not include_detail:
        return jobs

    detail_tickers = sorted(set(stock_tickers) | set(etf_tickers))
    jobs.extend(
        (f"stock/{ticker}", lambda ticker=ticker: server._stock_detail_service.detail(
            ticker=ticker,
            period="1y",
            profile=False,
        ))
        for ticker in detail_tickers
    )
    jobs.extend(
        (f"etf/{ticker}", lambda ticker=ticker: server._etf_api_service.etf_detail(ticker))
        for ticker in etf_tickers
    )
    return jobs


def main() -> int:
    parser = argparse.ArgumentParser(description="Precompute Qubit mobile API snapshots into PostgreSQL")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--skip-detail", action="store_true")
    parser.add_argument(
        "--clear-api-url",
        default=os.environ.get("QUBIT_CLEAR_API_URL", ""),
        help="Optional running API base URL to clear after DB snapshots are written.",
    )
    parser.add_argument("--clear-timeout", type=float, default=8.0)
    args = parser.parse_args()

    started = time.perf_counter()
    jobs = _jobs(include_detail=not args.skip_detail)
    results: list[dict] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = [pool.submit(_run_job, name, job) for name, job in jobs]
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            results.append(result)
            status = result["status"]
            count = "" if result.get("count") is None else f" count={result['count']}"
            error = "" if status == "OK" else f" error={result.get('error')}"
            print(f"  {status:<4} {result['elapsed_ms']:>8.1f}ms {result['name']}{count}{error}")

    ok_count = sum(1 for result in results if result.get("status") == "OK")
    fail_count = len(results) - ok_count
    if args.clear_api_url:
        try:
            _clear_api_process_cache(args.clear_api_url, args.clear_timeout)
            print(f"[app-snapshots] cleared API runtime cache: {args.clear_api_url}")
        except Exception as exc:
            print(f"[app-snapshots] API runtime cache clear skipped: {type(exc).__name__}: {exc}")
    elapsed = round(time.perf_counter() - started, 2)
    print(f"[app-snapshots] done jobs={len(results)} ok={ok_count} fail={fail_count} elapsed={elapsed}s")
    return 0 if ok_count else 1


if __name__ == "__main__":
    raise SystemExit(main())
