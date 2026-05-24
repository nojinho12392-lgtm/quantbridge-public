#!/usr/bin/env python3
"""Run local mobile smoke QA for API account flow, Android, and iOS."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def run(cmd: list[str]) -> None:
    print("  $ " + " ".join(str(part) for part in cmd))
    subprocess.run(cmd, cwd=str(ROOT), check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Run QuantBridge local mobile smoke QA")
    parser.add_argument("--platform", choices=["all", "android", "ios"], default="all")
    parser.add_argument("--url", default="", help="API base URL for account/watchlist smoke")
    parser.add_argument("--skip-api", action="store_true", help="Skip API account/watchlist smoke")
    parser.add_argument("--allow-missing-device", action="store_true", help="Do not fail when Android/iOS devices are missing")
    parser.add_argument("--android-serial", default="", help="ADB serial to use for Android QA")
    parser.add_argument("--artifacts-dir", default=str(ROOT / "artifacts" / "mobile-smoke"))
    args = parser.parse_args()

    url_args = ["--url", args.url] if args.url else []
    if not args.skip_api:
        print("[mobile-smoke] Account/Watchlist API flow")
        run([sys.executable, "tools/smoke_user_flow.py", *url_args])

    if args.platform in ("all", "android"):
        print("[mobile-smoke] Android emulator/device flow")
        android_args = [
            sys.executable,
            "tools/qa_android_device.py",
            "--skip-api-smoke",
            "--skip-config",
            "--artifacts-dir",
            args.artifacts_dir,
        ]
        if args.allow_missing_device:
            android_args.append("--allow-missing-device")
        if args.android_serial:
            android_args.extend(["--serial", args.android_serial])
        run(android_args)

    if args.platform in ("all", "ios"):
        print("[mobile-smoke] iOS simulator flow")
        ios_args = [
            sys.executable,
            "tools/qa_ios_simulator.py",
            "--artifacts-dir",
            args.artifacts_dir,
        ]
        if args.allow_missing_device:
            ios_args.append("--allow-missing-simulator")
        run(ios_args)

    print("[mobile-smoke] ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
