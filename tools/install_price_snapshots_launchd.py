#!/usr/bin/env python3
"""Manage the macOS launchd schedule for QuantBridge price snapshots.

The job runs inside the local API Docker container so it uses the same
PostgreSQL connection and Python dependencies as the server. It refreshes the
latest app prices; historical returns and chart context come from stored OHLCV.
"""

from __future__ import annotations

import argparse
import os
import plistlib
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LABEL = "com.quantbridge.price-snapshots"
PLIST_PATH = Path.home() / "Library" / "LaunchAgents" / f"{LABEL}.plist"
DEFAULT_INTERVAL_MINUTES = 15
DEFAULT_CONTAINER = "quantbridge-api"
DEFAULT_MARKETS = "US,KR"


def _docker_path() -> Path:
    found = shutil.which("docker")
    return Path(found) if found else Path("/usr/local/bin/docker")


def _domain() -> str:
    return f"gui/{os.getuid()}"


def _service() -> str:
    return f"{_domain()}/{LABEL}"


def _run(cmd: list[str], check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, text=True, capture_output=True, check=check)


def build_plist(
    docker_path: Path,
    container: str,
    interval_minutes: int,
    markets: str,
    batch_size: int,
    delay: float,
    intraday_interval: str,
    market_indicator_interval: str,
) -> dict:
    log_dir = ROOT / "logs"
    return {
        "Label": LABEL,
        "ProgramArguments": [
            str(docker_path),
            "exec",
            container,
            "python",
            "-c",
            "from quantbridge.price_snapshots import main; raise SystemExit(main())",
            "--markets",
            markets,
            "--batch-size",
            str(batch_size),
            "--delay",
            str(delay),
            "--intraday-interval",
            str(intraday_interval),
            "--market-indicator-interval",
            str(market_indicator_interval),
            "--skip-recent-daily",
        ],
        "WorkingDirectory": str(ROOT),
        "StartInterval": interval_minutes * 60,
        "RunAtLoad": True,
        "StandardOutPath": str(log_dir / "price_snapshots.out.log"),
        "StandardErrorPath": str(log_dir / "price_snapshots.err.log"),
        "EnvironmentVariables": {
            "PYTHONUNBUFFERED": "1",
        },
    }


def write_plist(plist: dict) -> Path:
    (ROOT / "logs").mkdir(parents=True, exist_ok=True)
    PLIST_PATH.parent.mkdir(parents=True, exist_ok=True)
    PLIST_PATH.write_bytes(plistlib.dumps(plist, sort_keys=False))
    return PLIST_PATH


def install(plist: dict, run_now: bool = False) -> None:
    path = write_plist(plist)
    _run(["launchctl", "bootout", _domain(), str(path)], check=False)
    _run(["launchctl", "bootstrap", _domain(), str(path)])
    _run(["launchctl", "enable", _service()], check=False)
    if run_now:
        _run(["launchctl", "kickstart", "-k", _service()])
    print(f"Installed {LABEL}")
    print(f"Plist: {path}")
    print(f"Logs:  {ROOT / 'logs'}")


def uninstall() -> None:
    _run(["launchctl", "bootout", _domain(), str(PLIST_PATH)], check=False)
    if PLIST_PATH.exists():
        PLIST_PATH.unlink()
    print(f"Uninstalled {LABEL}")


def status() -> int:
    result = _run(["launchctl", "print", _service()], check=False)
    if result.returncode == 0:
        print(result.stdout.strip())
        return 0
    print(f"{LABEL} is not loaded.")
    if PLIST_PATH.exists():
        print(f"Plist exists: {PLIST_PATH}")
    else:
        print(f"Plist missing: {PLIST_PATH}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Manage QuantBridge price-snapshot launchd schedule")
    parser.add_argument("command", choices=["render", "install", "uninstall", "status"])
    parser.add_argument("--interval-minutes", type=int, default=DEFAULT_INTERVAL_MINUTES)
    parser.add_argument("--container", default=DEFAULT_CONTAINER)
    parser.add_argument("--markets", default=DEFAULT_MARKETS)
    parser.add_argument("--batch-size", type=int, default=60)
    parser.add_argument("--delay", type=float, default=0.05)
    parser.add_argument("--intraday-interval", default="5m")
    parser.add_argument("--market-indicator-interval", default="1m")
    parser.add_argument("--docker", default=str(_docker_path()))
    parser.add_argument("--run-now", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.command == "status":
        raise SystemExit(status())
    if args.command == "uninstall":
        uninstall()
        return
    if args.interval_minutes < 5:
        raise SystemExit("--interval-minutes must be at least 5")
    if not str(args.container or "").strip():
        raise SystemExit("--container is required")

    plist = build_plist(
        Path(args.docker),
        str(args.container).strip(),
        args.interval_minutes,
        str(args.markets or DEFAULT_MARKETS).strip(),
        args.batch_size,
        args.delay,
        str(args.intraday_interval or "5m").strip(),
        str(args.market_indicator_interval or "1m").strip(),
    )
    if args.command == "render":
        sys.stdout.buffer.write(plistlib.dumps(plist, sort_keys=False))
        return
    install(plist, run_now=args.run_now)


if __name__ == "__main__":
    main()
