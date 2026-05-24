#!/usr/bin/env python3
"""Smoke-test app-facing QuantBridge API endpoints."""

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


def get_json(base_url: str, path: str, timeout: float = 10) -> tuple[float, dict]:
    start = time.perf_counter()
    response = requests.get(f"{base_url.rstrip('/')}{path}", timeout=timeout)
    elapsed = time.perf_counter() - start
    response.raise_for_status()
    return elapsed, response.json()


def main() -> int:
    parser = argparse.ArgumentParser(description="Smoke-test app-facing API endpoints")
    parser.add_argument("--url", default="", help="API base URL. Defaults to current LAN IP.")
    parser.add_argument("--timeout", type=float, default=30.0, help="Per-request timeout in seconds")
    args = parser.parse_args()

    base_url = args.url.strip().rstrip("/") or f"http://{detect_lan_ip()}:8000"
    checks = [
        ("ready", "/ready"),
        ("portfolio_us", "/portfolio/US"),
        ("smallcap_kr", "/smallcap/KR"),
        ("stock_us", "/stock/CF?period=5y"),
        ("stock_kr", "/stock/005930.KS?period=5y"),
    ]

    print(f"[smoke] base_url={base_url}")
    for name, path in checks:
        elapsed, data = get_json(base_url, path, timeout=args.timeout)
        if name == "ready":
            summary = f"status={data.get('status')} cache={data.get('cache')}"
        elif name.startswith("stock"):
            summary = (
                f"source={data.get('source')} prices={len(data.get('prices', []))} "
                f"name={data.get('info', {}).get('name')}"
            )
            if data.get("source") != "storage":
                raise RuntimeError(f"{name} expected storage source, got {data.get('source')}")
        else:
            summary = f"stocks={len(data.get('stocks', []))}"
        print(f"  {name:<12} {elapsed:>6.3f}s  {summary}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
