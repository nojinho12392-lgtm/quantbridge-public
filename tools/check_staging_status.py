#!/usr/bin/env python3
"""Check QuantBridge staging readiness from one operator command."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests


ROOT = Path(__file__).resolve().parents[1]
MOBILE_CACHE_WARM_ENDPOINTS = {
    "portfolio/us",
    "portfolio/kr",
    "smallcap/us",
    "smallcap/kr",
    "macro",
}


def _float_env(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None:
        return default
    try:
        return float(raw.strip())
    except ValueError:
        return default


def _resolve_from_helper(timeout: float) -> str:
    helper = ROOT / "deploy" / "azure" / "staging-url.sh"
    env_file = ROOT / "deploy" / "azure" / "staging.env"
    if not helper.exists() or not env_file.exists():
        return ""
    try:
        result = subprocess.run(
            [str(helper)],
            cwd=str(ROOT),
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout,
        )
    except Exception:
        return ""
    if result.returncode != 0:
        return ""
    return result.stdout.strip().splitlines()[-1].strip()


def resolve_base_url(explicit_url: str, timeout: float) -> str:
    candidates = [
        explicit_url,
        os.getenv("QUANT_STAGING_API_URL", ""),
        os.getenv("QUANT_API_BASE_URL", ""),
        _resolve_from_helper(timeout),
    ]
    for candidate in candidates:
        value = candidate.strip().rstrip("/")
        if value:
            return value
    raise SystemExit(
        "Could not resolve staging URL. Pass --url, set QUANT_STAGING_API_URL, "
        "or create deploy/azure/staging.env for deploy/azure/staging-url.sh."
    )


def request_json(base_url: str, path: str, timeout: float) -> tuple[float, dict]:
    start = time.perf_counter()
    response = requests.get(f"{base_url.rstrip('/')}{path}", timeout=timeout)
    elapsed = time.perf_counter() - start
    response.raise_for_status()
    return elapsed, response.json()


def ready_check(base_url: str, timeout: float, wait_seconds: float, interval: float) -> dict:
    deadline = time.monotonic() + max(wait_seconds, 0.0)
    attempt = 0
    last_error = ""
    while True:
        attempt += 1
        try:
            elapsed, payload = request_json(base_url, "/ready", timeout)
            ready = payload.get("status") == "ready"
            return {
                "name": "API /ready",
                "status": "OK" if ready else "FAIL",
                "message": f"status={payload.get('status')} cache={payload.get('cache')}",
                "elapsed_seconds": round(elapsed, 3),
                "attempts": attempt,
                "detail": payload,
            }
        except Exception as exc:
            last_error = f"{type(exc).__name__}: {exc}"
            if time.monotonic() >= deadline:
                return {
                    "name": "API /ready",
                    "status": "FAIL",
                    "message": last_error,
                    "elapsed_seconds": None,
                    "attempts": attempt,
                    "detail": {},
                }
            time.sleep(interval)


def ops_health_check(base_url: str, timeout: float, max_research_age_hours: int) -> dict:
    try:
        elapsed, payload = request_json(
            base_url,
            f"/ops/health?max_research_age_hours={max_research_age_hours}",
            timeout,
        )
    except Exception as exc:
        return {
            "name": "API /ops/health",
            "status": "FAIL",
            "message": f"{type(exc).__name__}: {exc}",
            "elapsed_seconds": None,
            "detail": {},
        }

    status = str(payload.get("status", "UNKNOWN")).upper()
    if payload.get("healthy") is True or status == "OK":
        check_status = "OK"
    elif status == "DEGRADED":
        check_status = "WARN"
    else:
        check_status = "FAIL"
    checks = payload.get("checks") if isinstance(payload.get("checks"), list) else []
    problem_checks = [
        f"{item.get('name')}:{item.get('status')}"
        for item in checks
        if isinstance(item, dict) and str(item.get("status") or "").upper() not in {"OK", ""}
    ]
    issue_suffix = f" issues={', '.join(problem_checks[:5])}" if problem_checks else ""
    return {
        "name": "API /ops/health",
        "status": check_status,
        "message": f"status={status} counts={payload.get('status_counts', {})}{issue_suffix}",
        "elapsed_seconds": round(elapsed, 3),
        "detail": {
            "healthy": payload.get("healthy"),
            "status": payload.get("status"),
            "status_counts": payload.get("status_counts", {}),
            "checks": checks,
        },
    }


def data_quality_check(base_url: str, timeout: float) -> dict:
    try:
        elapsed, payload = request_json(base_url, "/ops/data-quality", timeout)
    except Exception as exc:
        if isinstance(exc, requests.HTTPError) and exc.response is not None and exc.response.status_code == 404:
            return {
                "name": "API /ops/data-quality",
                "status": "WARN",
                "message": "Endpoint is not available on the currently deployed revision.",
                "elapsed_seconds": None,
                "detail": {"hint": "Deploy the API revision that includes /ops/data-quality."},
            }
        return {
            "name": "API /ops/data-quality",
            "status": "FAIL",
            "message": f"{type(exc).__name__}: {exc}",
            "elapsed_seconds": None,
            "detail": {},
        }

    status = str(payload.get("status", "UNKNOWN")).upper()
    if status == "OK":
        check_status = "OK"
    elif status == "DEGRADED":
        check_status = "WARN"
    else:
        check_status = "FAIL"
    return {
        "name": "API /ops/data-quality",
        "status": check_status,
        "message": f"status={status} counts={payload.get('status_counts', {})}",
        "elapsed_seconds": round(elapsed, 3),
        "detail": {
            "healthy": payload.get("healthy"),
            "status": payload.get("status"),
            "status_counts": payload.get("status_counts", {}),
        },
    }


def cache_warm_check(base_url: str, timeout: float) -> dict:
    try:
        elapsed, payload = request_json(base_url, "/ops/cache/warm", timeout)
    except Exception as exc:
        if isinstance(exc, requests.HTTPError) and exc.response is not None and exc.response.status_code == 404:
            return {
                "name": "API cache warm",
                "status": "WARN",
                "message": "Endpoint is not available on the currently deployed revision.",
                "elapsed_seconds": None,
                "detail": {"hint": "Deploy the API revision that includes /ops/cache/warm."},
            }
        return {
            "name": "API cache warm",
            "status": "FAIL",
            "message": f"{type(exc).__name__}: {exc}",
            "elapsed_seconds": None,
            "detail": {},
        }

    results = payload.get("results") if isinstance(payload.get("results"), list) else []
    result_names = {str(item.get("name") or "") for item in results if isinstance(item, dict)}
    failed = [item for item in results if isinstance(item, dict) and str(item.get("status") or "").upper() == "FAIL"]
    missing_mobile = sorted(MOBILE_CACHE_WARM_ENDPOINTS - result_names)
    running = bool(payload.get("running"))
    profile = str(payload.get("profile") or payload.get("reason") or "unknown")

    if running:
        check_status = "WARN"
        message = f"profile={profile} running"
    elif failed:
        check_status = "WARN"
        message = f"profile={profile} failed={len(failed)}"
    elif missing_mobile:
        check_status = "WARN"
        message = f"profile={profile} missing_mobile={','.join(missing_mobile)}"
    else:
        check_status = "OK"
        message = f"profile={profile} warmed={len(results)} elapsed_ms={payload.get('elapsed_ms')}"

    return {
        "name": "API cache warm",
        "status": check_status,
        "message": message,
        "elapsed_seconds": round(elapsed, 3),
        "detail": {
            "running": running,
            "profile": payload.get("profile"),
            "reason": payload.get("reason"),
            "last_started_at": payload.get("last_started_at"),
            "last_finished_at": payload.get("last_finished_at"),
            "elapsed_ms": payload.get("elapsed_ms"),
            "missing_mobile": missing_mobile,
            "failed": failed[:5],
            "result_count": len(results),
        },
    }


def smoke_check(base_url: str, timeout: float) -> dict:
    cmd = [
        sys.executable,
        "tools/smoke_app_api.py",
        "--url",
        base_url,
        "--timeout",
        str(timeout),
    ]
    start = time.perf_counter()
    result = subprocess.run(
        cmd,
        cwd=str(ROOT),
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    elapsed = time.perf_counter() - start
    return {
        "name": "App endpoint smoke",
        "status": "OK" if result.returncode == 0 else "FAIL",
        "message": f"exit={result.returncode}",
        "elapsed_seconds": round(elapsed, 3),
        "detail": {"output": result.stdout.strip()},
    }


def android_emulator_smoke_check(
    base_url: str,
    avd: str,
    serial: str,
    artifacts_dir: str,
    profile: str,
    api_wait_timeout: float,
    skip_tests: bool,
) -> dict:
    cmd = [
        sys.executable,
        "tools/qa_android_emulator_smoke.py",
        "--url",
        base_url,
        "--avd",
        avd,
        "--profile",
        profile,
        "--api-wait-timeout",
        str(api_wait_timeout),
    ]
    if serial:
        cmd.extend(["--serial", serial])
    if artifacts_dir:
        cmd.extend(["--artifacts-dir", artifacts_dir])
    if skip_tests:
        cmd.append("--skip-tests")

    start = time.perf_counter()
    result = subprocess.run(
        cmd,
        cwd=str(ROOT),
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    elapsed = time.perf_counter() - start
    return {
        "name": "Android emulator smoke",
        "status": "OK" if result.returncode == 0 else "FAIL",
        "message": f"exit={result.returncode}",
        "elapsed_seconds": round(elapsed, 3),
        "detail": {"output": result.stdout.strip()},
    }


def overall_status(checks: list[dict]) -> str:
    statuses = {str(check.get("status", "UNKNOWN")).upper() for check in checks}
    if "FAIL" in statuses:
        return "FAIL"
    if "WARN" in statuses:
        return "DEGRADED"
    return "OK"


def print_human(report: dict) -> None:
    print(f"[staging] {report['status']} - {report['url']}")
    print(f"  generated : {report['generated_at']}")
    for check in report["checks"]:
        elapsed = check.get("elapsed_seconds")
        timing = "-" if elapsed is None else f"{elapsed:.3f}s"
        print(f"  {check['status']:<5} {check['name']:<20} {timing:>8}  {check['message']}")
        output = check.get("detail", {}).get("output")
        if output:
            for line in output.splitlines():
                print(f"        {line}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check staging API readiness and operating health")
    parser.add_argument("--url", default="", help="Staging API base URL")
    parser.add_argument("--timeout", type=float, default=_float_env("QUANT_OPS_TIMEOUT_SECONDS", 30.0))
    parser.add_argument("--max-research-age-hours", type=int, default=84)
    parser.add_argument(
        "--wait-ready-seconds",
        type=float,
        default=_float_env("QUANT_STAGING_WAIT_READY_SECONDS", 120.0),
        help="Seconds to keep retrying /ready, useful for cold Azure Container Apps revisions",
    )
    parser.add_argument("--ready-interval", type=float, default=5.0)
    parser.add_argument("--smoke", action="store_true", help="Also run app endpoint smoke tests")
    parser.add_argument("--android-emulator-smoke", action="store_true", help="Also run Android emulator staging smoke QA")
    parser.add_argument("--android-avd", default="QuantBridge_Pixel_8_API_36", help="AVD to use for Android emulator QA")
    parser.add_argument("--android-serial", default="", help="Use an already-running emulator serial for Android QA")
    parser.add_argument("--android-artifacts-dir", default="", help="Directory for Android smoke screenshots, UI XML, and logs")
    parser.add_argument(
        "--android-profile",
        choices=["quick", "full"],
        default="full",
        help="Android emulator smoke depth. full also verifies account create, session restore, and delete.",
    )
    parser.add_argument("--android-api-wait-timeout", type=float, default=90.0, help="Seconds to wait for required Android API 200 logs")
    parser.add_argument("--android-skip-tests", action="store_true", help="Skip Android unit tests before emulator install")
    parser.add_argument("--strict", action="store_true", help="Exit non-zero when staging is DEGRADED")
    parser.add_argument("--warn-only", action="store_true", help="Always exit 0")
    parser.add_argument("--json", action="store_true", help="Print JSON only")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    base_url = resolve_base_url(args.url, args.timeout)
    checks = [
        ready_check(base_url, args.timeout, args.wait_ready_seconds, args.ready_interval),
        cache_warm_check(base_url, args.timeout),
        ops_health_check(base_url, args.timeout, args.max_research_age_hours),
        data_quality_check(base_url, args.timeout),
    ]
    if args.smoke:
        checks.append(smoke_check(base_url, args.timeout))
    if args.android_emulator_smoke:
        checks.append(
            android_emulator_smoke_check(
                base_url,
                args.android_avd,
                args.android_serial,
                args.android_artifacts_dir,
                args.android_profile,
                args.android_api_wait_timeout,
                args.android_skip_tests,
            )
        )
    report = {
        "url": base_url,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "status": overall_status(checks),
        "checks": checks,
    }
    report["healthy"] = report["status"] == "OK"

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2, default=str))
    else:
        print_human(report)

    if args.warn_only:
        return 0
    if report["status"] == "FAIL":
        return 2
    if args.strict and report["status"] != "OK":
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
