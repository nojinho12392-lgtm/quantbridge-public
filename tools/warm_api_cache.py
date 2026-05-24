#!/usr/bin/env python3
"""Warm the mobile API cache so app screens open from local cached payloads."""

from __future__ import annotations

import argparse
import concurrent.futures
import subprocess
import time
import urllib.error
import urllib.request
from urllib.parse import urlencode


def detect_lan_ip() -> str:
    for iface in ("en0", "en1"):
        try:
            out = subprocess.check_output(["ipconfig", "getifaddr", iface], text=True).strip()
            if out:
                return out
        except Exception:
            pass
    return "127.0.0.1"


def request_endpoint(base_url: str, path: str, params: dict[str, str | int] | None, timeout: float) -> tuple[str, float, int, str]:
    url = f"{base_url.rstrip('/')}/{path.lstrip('/')}"
    if params:
        url = f"{url}?{urlencode(params)}"
    started = time.perf_counter()
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            content = response.read()
            elapsed = time.perf_counter() - started
            status = getattr(response, "status", 200)
            if status >= 400:
                raise RuntimeError(f"HTTP {status}")
            return path, elapsed, len(content), response.headers.get("x-process-time-ms", "")
    except urllib.error.HTTPError as exc:
        detail = exc.read(240).decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code} {detail}") from exc


def endpoints() -> list[tuple[str, dict[str, str | int] | None]]:
    return [
        ("portfolio/us", None),
        ("portfolio/kr", None),
        ("smallcap/us", None),
        ("smallcap/kr", None),
        ("earnings/us", None),
        ("earnings/kr", None),
        ("calendar/earnings", {"market": "ALL", "days": 180, "limit": 2000}),
        ("signals/events", {"market": "ALL", "limit": 120}),
        ("market/indices", None),
        ("market/indicators", {"category": "all"}),
        ("news/issues", {"q": "", "market": "ALL", "limit": 40}),
        ("etfs", {"limit": 500}),
        ("sectors/themes/summary", {"market": "ALL", "limit": 72}),
        ("sectors/themes/summary", {"market": "US", "limit": 72}),
        ("sectors/themes/summary", {"market": "KR", "limit": 72}),
        ("sectors/themes", {"market": "ALL", "limit": 72, "members": 200}),
        ("sectors/themes", {"market": "US", "limit": 72, "members": 200}),
        ("sectors/themes", {"market": "KR", "limit": 72, "members": 200}),
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description="Warm Qubit mobile API caches")
    parser.add_argument("--url", default="", help="API base URL. Defaults to current LAN IP.")
    parser.add_argument("--timeout", type=float, default=90.0)
    parser.add_argument("--workers", type=int, default=4)
    args = parser.parse_args()

    base_url = args.url.strip().rstrip("/") or f"http://{detect_lan_ip()}:8000"
    jobs = endpoints()
    print(f"[api-cache] base_url={base_url} endpoints={len(jobs)}")
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = [pool.submit(request_endpoint, base_url, path, params, args.timeout) for path, params in jobs]
        for future in concurrent.futures.as_completed(futures):
            try:
                path, elapsed, size, server_ms = future.result()
                suffix = f" server={server_ms}ms" if server_ms else ""
                print(f"  OK   {elapsed:>6.2f}s {size:>8}B {path}{suffix}")
            except Exception as exc:
                print(f"  FAIL {type(exc).__name__}: {exc}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
