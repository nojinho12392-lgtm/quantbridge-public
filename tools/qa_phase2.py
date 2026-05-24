#!/usr/bin/env python3
"""Run the phase-2 operational QA suite."""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
QUANT_DIR = ROOT.parent


def _sibling_or_root_dir(*names: str) -> Path:
    for name in names:
        in_root = ROOT / name
        if in_root.exists():
            return in_root
    for name in names:
        sibling = QUANT_DIR / name
        if sibling.exists():
            return sibling
    return ROOT / names[0]


ANDROID_DIR = _sibling_or_root_dir("android", "andriod")
IOS_DIR = _sibling_or_root_dir("Stock Analysis")


def run_step(name: str, cmd: list[str], cwd: Path = ROOT) -> None:
    print(f"\n[qa] {name}")
    print("     " + " ".join(cmd))
    start = time.perf_counter()
    result = subprocess.run(cmd, cwd=str(cwd), text=True)
    elapsed = time.perf_counter() - start
    if result.returncode != 0:
        raise SystemExit(f"[qa] {name} failed after {elapsed:.1f}s with exit {result.returncode}")
    print(f"[qa] {name} ok ({elapsed:.1f}s)")


def main() -> int:
    parser = argparse.ArgumentParser(description="Run QuantBridge phase-2 QA checks")
    parser.add_argument("--url", default="", help="Explicit API base URL for smoke tests")
    parser.add_argument("--skip-builds", action="store_true", help="Skip Android/iOS build checks")
    args = parser.parse_args()

    python = sys.executable
    smoke_url_args = ["--url", args.url] if args.url else []

    run_step("docker compose status", ["docker", "compose", "ps"])
    run_step("configure app API", [python, "tools/configure_app_api.py", *smoke_url_args])
    run_step("app endpoint smoke", [python, "tools/smoke_app_api.py", *smoke_url_args])
    run_step("user flow smoke", [python, "tools/smoke_user_flow.py", *smoke_url_args])
    run_step("contract tests", [python, "-m", "unittest", "test_contracts.py"])

    if not args.skip_builds:
        run_step("Android debug build", ["./gradlew", "assembleDebug"], cwd=ANDROID_DIR)
        run_step(
            "iOS simulator build",
            [
                "xcodebuild",
                "-project",
                "Stock Analysis.xcodeproj",
                "-scheme",
                "Stock Analysis",
                "-configuration",
                "Debug",
                "-destination",
                "generic/platform=iOS Simulator",
                "build",
            ],
            cwd=IOS_DIR,
        )

    print("\n[qa] phase-2 QA complete")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
