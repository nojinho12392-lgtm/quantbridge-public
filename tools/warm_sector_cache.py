#!/usr/bin/env python3
"""Warm sector theme payloads so mobile sector pages open with priced members."""

from __future__ import annotations

import argparse
import subprocess
import time

import requests


def detect_lan_ip() -> str:
    for iface in ("en0", "en1"):
        try:
            out = subprocess.check_output(["ipconfig", "getifaddr", iface], text=True).strip()
            if out:
                return out
        except Exception:
            pass
    return "127.0.0.1"


def fetch(base_url: str, market: str, limit: int, members: int, timeout: float) -> dict:
    response = requests.get(
        f"{base_url.rstrip('/')}/sectors/themes",
        params={"market": market, "limit": limit, "members": members, "refresh": "true"},
        timeout=timeout,
    )
    response.raise_for_status()
    return response.json()


def main() -> int:
    parser = argparse.ArgumentParser(description="Warm sector theme cache")
    parser.add_argument("--url", default="", help="API base URL. Defaults to current LAN IP.")
    parser.add_argument("--limit", type=int, default=72)
    parser.add_argument("--members", type=int, default=200)
    parser.add_argument("--timeout", type=float, default=90.0)
    parser.add_argument("--markets", default="ALL,US,KR")
    args = parser.parse_args()

    base_url = args.url.strip().rstrip("/") or f"http://{detect_lan_ip()}:8000"
    markets = [item.strip().upper() for item in args.markets.split(",") if item.strip()]
    print(f"[sector-cache] base_url={base_url}")
    for market in markets:
        start = time.perf_counter()
        payload = fetch(base_url, market, args.limit, args.members, args.timeout)
        elapsed = time.perf_counter() - start
        items = payload.get("items") or []
        priced = sum(int(item.get("priced_count") or 0) for item in items if isinstance(item, dict))
        missing = sum(int(item.get("missing_price_count") or 0) for item in items if isinstance(item, dict))
        print(
            f"  {market:<3} {elapsed:>6.2f}s themes={len(items):>2} "
            f"priced_members={priced} missing_prices={missing}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
