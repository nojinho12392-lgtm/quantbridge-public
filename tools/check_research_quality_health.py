#!/usr/bin/env python3
"""Check whether the research-quality job is healthy.

Healthy means:
  - at least one research-quality run exists,
  - the latest run succeeded,
  - the latest successful run is not older than the threshold.

Default threshold is 84 hours to tolerate the Fri/Sat to Tue KST weekend gap.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quantbridge.storage import QuantRepository


RESEARCH_SCRIPTS = {
    "pipeline/14_factor_ic_report.py",
    "pipeline/15_signal_quality_gate.py",
    "pipeline/16_factor_weight_policy.py",
    "pipeline/17_factor_policy_backtest.py",
}


def _run_scripts(payload: dict) -> list[str]:
    scripts = []
    for step in payload.get("steps") or []:
        if isinstance(step, str):
            scripts.append(step)
    for result in payload.get("results") or []:
        if isinstance(result, dict) and result.get("script"):
            scripts.append(str(result["script"]))
    return list(dict.fromkeys(scripts))


def _is_research_quality(payload: dict) -> bool:
    return RESEARCH_SCRIPTS.issubset(set(_run_scripts(payload)))


def _parse_dt(value) -> datetime | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except Exception:
        return None


def _age_hours(value) -> float | None:
    dt = _parse_dt(value)
    if dt is None:
        return None
    now = datetime.now(dt.tzinfo) if dt.tzinfo else datetime.utcnow()
    return round((now - dt).total_seconds() / 3600.0, 3)


def check_health(max_age_hours: int = 84) -> dict:
    runs = QuantRepository().read_pipeline_runs(limit=100)
    if runs.empty:
        return {
            "healthy": False,
            "status": "MISSING",
            "reason": "No pipeline_runs rows found.",
            "max_age_hours": max_age_hours,
        }

    for row in runs.to_dict("records"):
        payload = row.get("payload") if isinstance(row.get("payload"), dict) else {}
        if not _is_research_quality(payload):
            continue
        age = _age_hours(row.get("finished_at"))
        latest = {
            "run_id": row.get("run_id"),
            "runner": row.get("runner"),
            "status": row.get("status"),
            "started_at": str(row.get("started_at") or ""),
            "finished_at": str(row.get("finished_at") or ""),
            "scripts": _run_scripts(payload),
        }
        status_text = str(row.get("status") or "unknown").lower()
        if status_text != "success":
            return {
                "healthy": False,
                "status": "FAILED",
                "reason": f"Latest research-quality status is {status_text}.",
                "max_age_hours": max_age_hours,
                "age_hours": age,
                "latest_research_quality": latest,
            }
        if age is None:
            return {
                "healthy": False,
                "status": "UNKNOWN_AGE",
                "reason": "Latest research-quality run has no finished_at timestamp.",
                "max_age_hours": max_age_hours,
                "age_hours": None,
                "latest_research_quality": latest,
            }
        if age > max_age_hours:
            return {
                "healthy": False,
                "status": "STALE",
                "reason": f"Latest successful research-quality run is {age:.1f}h old.",
                "max_age_hours": max_age_hours,
                "age_hours": age,
                "latest_research_quality": latest,
            }
        return {
            "healthy": True,
            "status": "OK",
            "reason": f"Latest research-quality run succeeded {age:.1f}h ago.",
            "max_age_hours": max_age_hours,
            "age_hours": age,
            "latest_research_quality": latest,
        }

    return {
        "healthy": False,
        "status": "MISSING",
        "reason": "No research-quality run found in recent pipeline_runs.",
        "max_age_hours": max_age_hours,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check research-quality job health")
    parser.add_argument("--max-age-hours", type=int, default=84)
    parser.add_argument("--json", action="store_true", help="Print JSON only")
    parser.add_argument("--warn-only", action="store_true", help="Always exit 0")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    result = check_health(max_age_hours=args.max_age_hours)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        status = result.get("status")
        marker = "OK" if result.get("healthy") else "NOT OK"
        print(f"[research-health] {marker} - {status}: {result.get('reason')}")
    if not result.get("healthy") and not args.warn_only:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
