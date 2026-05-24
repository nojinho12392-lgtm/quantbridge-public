#!/usr/bin/env python3
"""Check the full QuantBridge operating health surface.

This is the phase-4 smoke check: it calls the FastAPI `/ops/health` endpoint,
prints a compact operator summary, optionally sends a webhook alert, and exits
non-zero when the service is not healthy unless `--warn-only` is used.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from datetime import datetime

import requests


def _float_env(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return float(raw.strip())
    except ValueError:
        return default


def detect_lan_ip() -> str:
    for iface in ("en0", "en1"):
        try:
            out = subprocess.check_output(["ipconfig", "getifaddr", iface], text=True).strip()
            if out:
                return out
        except Exception:
            pass
    return "127.0.0.1"


def get_ops_health(base_url: str, max_research_age_hours: int, timeout: float) -> dict:
    url = f"{base_url.rstrip('/')}/ops/health"
    response = requests.get(
        url,
        params={"max_research_age_hours": max_research_age_hours},
        timeout=timeout,
    )
    response.raise_for_status()
    payload = response.json()
    payload["_url"] = url
    return payload


def send_webhook(webhook_url: str, payload: dict, timeout: float) -> None:
    if not webhook_url:
        return
    status = payload.get("status", "UNKNOWN")
    text = {
        "timestamp": datetime.utcnow().isoformat(),
        "service": "QuantBridge",
        "status": status,
        "healthy": payload.get("healthy"),
        "status_counts": payload.get("status_counts", {}),
        "url": payload.get("_url"),
        "failed_checks": [
            check for check in payload.get("checks", [])
            if str(check.get("status")).upper() == "FAIL"
        ],
        "warning_checks": [
            check for check in payload.get("checks", [])
            if str(check.get("status")).upper() == "WARN"
        ],
    }
    response = requests.post(webhook_url, json=text, timeout=timeout)
    response.raise_for_status()


def print_human(payload: dict) -> None:
    status = payload.get("status", "UNKNOWN")
    marker = "OK" if payload.get("healthy") else "NOT OK"
    print(f"[ops-health] {marker} - {status}")
    print(f"  url           : {payload.get('_url', '-')}")
    print(f"  generated     : {payload.get('generated_at', '-')}")
    print(f"  status_counts : {payload.get('status_counts', {})}")
    for check in payload.get("checks", []):
        name = str(check.get("name") or "-")
        status_text = str(check.get("status") or "UNKNOWN")
        message = str(check.get("message") or "")
        print(f"  {status_text:<5} {name:<24} {message}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check QuantBridge phase-4 operating health")
    parser.add_argument("--url", default="", help="API base URL. Defaults to current LAN IP.")
    parser.add_argument("--timeout", type=float, default=_float_env("QUANT_OPS_TIMEOUT_SECONDS", 30.0))
    parser.add_argument("--max-research-age-hours", type=int, default=84)
    parser.add_argument("--json", action="store_true", help="Print JSON only")
    parser.add_argument("--warn-only", action="store_true", help="Always exit 0")
    parser.add_argument(
        "--webhook-url",
        default=os.getenv("QUANT_OPS_WEBHOOK_URL", ""),
        help="Optional webhook URL for JSON alerts. Defaults to QUANT_OPS_WEBHOOK_URL.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    base_url = args.url.strip().rstrip("/") or os.getenv("QUANT_API_BASE_URL", "").strip().rstrip("/")
    if not base_url:
        base_url = f"http://{detect_lan_ip()}:8000"

    try:
        payload = get_ops_health(base_url, args.max_research_age_hours, args.timeout)
    except Exception as exc:
        payload = {
            "healthy": False,
            "status": "FAIL",
            "generated_at": datetime.utcnow().isoformat(),
            "_url": f"{base_url.rstrip('/')}/ops/health",
            "status_counts": {"FAIL": 1},
            "checks": [{
                "name": "API /ops/health",
                "status": "FAIL",
                "message": f"{type(exc).__name__}: {exc}",
                "detail": {},
            }],
        }

    if args.webhook_url and not payload.get("healthy"):
        try:
            send_webhook(args.webhook_url, payload, args.timeout)
        except Exception as exc:
            payload.setdefault("checks", []).append({
                "name": "Webhook alert",
                "status": "WARN",
                "message": f"{type(exc).__name__}: {exc}",
                "detail": {},
            })

    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2, default=str))
    else:
        print_human(payload)

    if not payload.get("healthy") and not args.warn_only:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
