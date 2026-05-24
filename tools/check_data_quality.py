#!/usr/bin/env python3
"""Check QuantBridge app-facing dataset quality."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quantbridge.quality import build_data_quality_report
from quantbridge.storage import QuantRepository


def _repository_report(max_age_days: int | None) -> dict:
    repo = QuantRepository()
    return build_data_quality_report(
        lambda dataset, market: repo.read_dataframe(dataset, market=market),
        max_age_days=max_age_days,
    )


def _api_report(base_url: str, max_age_days: int | None, timeout: float) -> dict:
    params = {"max_age_days": max_age_days} if max_age_days else None
    response = requests.get(
        f"{base_url.rstrip('/')}/ops/data-quality",
        params=params,
        timeout=timeout,
    )
    response.raise_for_status()
    payload = response.json()
    payload["_url"] = f"{base_url.rstrip('/')}/ops/data-quality"
    return payload


def print_human(report: dict) -> None:
    print(f"[data-quality] {report.get('status')} healthy={report.get('healthy')}")
    print(f"  generated     : {report.get('generated_at', '-')}")
    if report.get("_url"):
        print(f"  url           : {report['_url']}")
    print(f"  status_counts : {report.get('status_counts', {})}")
    for dataset in report.get("datasets", []):
        print(
            f"  {dataset.get('status', 'UNKNOWN'):<8} "
            f"{dataset.get('dataset', '-'):<24} rows={dataset.get('rows', 0)}"
        )
        for check in dataset.get("checks", []):
            if str(check.get("status")).upper() == "OK":
                continue
            print(f"      {check.get('status'):<5} {check.get('name')}: {check.get('message')}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check QuantBridge data quality")
    parser.add_argument("--url", default=os.getenv("QUANT_API_BASE_URL", ""), help="Optional API base URL")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--max-age-days", type=int, default=0, help="Override freshness threshold; 0 uses per-dataset defaults")
    parser.add_argument("--json", action="store_true", help="Print JSON only")
    parser.add_argument("--strict", action="store_true", help="Exit non-zero on DEGRADED as well as FAIL")
    parser.add_argument("--warn-only", action="store_true", help="Always exit 0")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    max_age_days = args.max_age_days if args.max_age_days > 0 else None
    if args.url.strip():
        report = _api_report(args.url.strip(), max_age_days, args.timeout)
    else:
        report = _repository_report(max_age_days)

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2, default=str))
    else:
        print_human(report)

    status = str(report.get("status") or "UNKNOWN").upper()
    if args.warn_only:
        return 0
    if status == "FAIL":
        return 2
    if args.strict and status != "OK":
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
