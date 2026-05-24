#!/usr/bin/env python3
"""
Check whether OpenDART corp-code metadata can be loaded quickly enough for a run.

The script never fails the workflow for DART availability. It emits GitHub
Actions outputs that downstream jobs can use to either keep DART enabled or
switch to fallback mode for this run.
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import sys
import time
from contextlib import contextmanager
from pathlib import Path


class HealthTimeout(TimeoutError):
    pass


@contextmanager
def time_limit(seconds: int):
    if seconds <= 0 or not hasattr(signal, "SIGALRM"):
        yield
        return

    def _raise_timeout(_signum, _frame):
        raise HealthTimeout(f"timed out after {seconds}s")

    previous_handler = signal.signal(signal.SIGALRM, _raise_timeout)
    signal.setitimer(signal.ITIMER_REAL, seconds)
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_handler)


def env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, str(default)))
    except (TypeError, ValueError):
        return default


def dart_api_key() -> str:
    for name in ("DART_API_KEY", "OPENDART_API_KEY", "QUANT_DART_API_KEY"):
        value = os.environ.get(name, "").strip()
        if value:
            return value
    return ""


def write_outputs(path: str | None, outputs: dict[str, str]) -> None:
    if not path:
        return
    with open(path, "a", encoding="utf-8") as fh:
        for key, value in outputs.items():
            safe_value = str(value).replace("\n", " ").replace("\r", " ")
            fh.write(f"{key}={safe_value}\n")


def choose_shard_total(dart_enabled: bool) -> int:
    explicit = os.environ.get("QUANT_KR_SMALLCAP_SHARDS", "").strip()
    if explicit:
        return max(1, env_int("QUANT_KR_SMALLCAP_SHARDS", 4))
    if dart_enabled:
        return max(1, env_int("QUANT_KR_SMALLCAP_DART_SHARDS", 2))
    return max(1, env_int("QUANT_KR_SMALLCAP_FALLBACK_SHARDS", 4))


def main() -> int:
    parser = argparse.ArgumentParser(description="OpenDART run health check")
    parser.add_argument(
        "--timeout",
        type=int,
        default=env_int("QUANT_DART_HEALTHCHECK_TIMEOUT_SECONDS", 30),
        help="Maximum seconds to wait for OpenDART corp_codes",
    )
    parser.add_argument(
        "--github-output",
        default=os.environ.get("GITHUB_OUTPUT", ""),
        help="GitHub Actions output file",
    )
    parser.add_argument(
        "--artifact",
        default="artifacts/dart-health.json",
        help="JSON summary path",
    )
    args = parser.parse_args()

    Path(args.artifact).parent.mkdir(parents=True, exist_ok=True)
    api_key = dart_api_key()
    start = time.time()
    dart_enabled = False
    reason = ""
    rows = 0

    if not api_key:
        reason = "DART API key missing"
    else:
        try:
            import OpenDartReader

            os.makedirs("docs_cache", exist_ok=True)
            with time_limit(args.timeout):
                reader = OpenDartReader(api_key)
                corp_codes = reader.corp_codes
            rows = 0 if corp_codes is None else len(corp_codes)
            if rows > 0:
                dart_enabled = True
                reason = f"corp_codes loaded ({rows} rows)"
            else:
                reason = "corp_codes returned no rows"
        except Exception as exc:
            reason = f"{exc.__class__.__name__}: {exc}"

    shard_total = choose_shard_total(dart_enabled)
    outputs = {
        "dart_mode": "enabled" if dart_enabled else "fallback",
        "disable_dart": "false" if dart_enabled else "true",
        "disable_reason": reason if not dart_enabled else "DART health check passed",
        "shard_total": str(shard_total),
        "shard_matrix": json.dumps(list(range(shard_total))),
    }
    summary = {
        **outputs,
        "corp_code_rows": rows,
        "elapsed_seconds": round(time.time() - start, 2),
        "timeout_seconds": args.timeout,
    }
    with open(args.artifact, "w", encoding="utf-8") as fh:
        json.dump(summary, fh, ensure_ascii=False, indent=2)

    write_outputs(args.github_output, outputs)

    print(f"DART mode: {outputs['dart_mode']} ({reason})")
    print(f"KR smallcap shard total: {shard_total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
