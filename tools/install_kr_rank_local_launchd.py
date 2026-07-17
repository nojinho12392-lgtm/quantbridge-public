#!/usr/bin/env python3
"""Manage the macOS launchd schedule for local Korean stock ranking refreshes."""

from __future__ import annotations

import argparse
import os
import plistlib
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LABEL = "com.quantbridge.kr-rank-local"
PLIST_PATH = Path.home() / "Library" / "LaunchAgents" / f"{LABEL}.plist"
DEFAULT_WEEKDAYS = "1,2,3,4,5"  # Mon-Fri in launchd; Sunday is 0 or 7.
DEFAULT_HOUR = 18
DEFAULT_MINUTE = 30


def _default_python() -> Path:
    local = ROOT / ".venv" / "bin" / "python"
    return local if local.exists() else Path(sys.executable)


def _parse_weekdays(raw: str) -> list[int]:
    weekdays = []
    for part in str(raw or "").split(","):
        part = part.strip()
        if not part:
            continue
        value = int(part)
        if value < 0 or value > 7:
            raise argparse.ArgumentTypeError("launchd weekdays must be 0-7")
        weekdays.append(value)
    if not weekdays:
        raise argparse.ArgumentTypeError("at least one weekday is required")
    return weekdays


def _domain() -> str:
    return f"gui/{os.getuid()}"


def _service() -> str:
    return f"{_domain()}/{LABEL}"


def _run(cmd: list[str], check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, text=True, capture_output=True, check=check)


def build_plist(
    python_path: Path,
    hour: int,
    minute: int,
    weekdays: list[int],
    limit: int,
    kospi_limit: int,
    kosdaq_limit: int,
    delay: float,
    portfolio_size: int,
    smallcap_size: int,
    no_prices: bool = False,
) -> dict:
    log_dir = ROOT / "logs"
    program_args = [
        str(python_path),
        str(ROOT / "tools" / "local_kr_ranker.py"),
        "--limit",
        str(limit),
        "--kospi-limit",
        str(kospi_limit),
        "--kosdaq-limit",
        str(kosdaq_limit),
        "--delay",
        str(delay),
        "--portfolio-size",
        str(portfolio_size),
        "--smallcap-size",
        str(smallcap_size),
    ]
    if no_prices:
        program_args.append("--no-prices")

    return {
        "Label": LABEL,
        "ProgramArguments": program_args,
        "WorkingDirectory": str(ROOT),
        "StartCalendarInterval": [
            {"Weekday": weekday, "Hour": hour, "Minute": minute}
            for weekday in weekdays
        ],
        "RunAtLoad": False,
        "StandardOutPath": str(log_dir / "kr_rank_local.out.log"),
        "StandardErrorPath": str(log_dir / "kr_rank_local.err.log"),
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
    parser = argparse.ArgumentParser(description="Manage QuantBridge local KR ranking launchd schedule")
    parser.add_argument("command", choices=["render", "install", "uninstall", "status"])
    parser.add_argument("--hour", type=int, default=DEFAULT_HOUR)
    parser.add_argument("--minute", type=int, default=DEFAULT_MINUTE)
    parser.add_argument("--weekdays", default=DEFAULT_WEEKDAYS, help="Comma-separated launchd weekdays, 0-7")
    parser.add_argument("--limit", type=int, default=60)
    parser.add_argument("--kospi-limit", type=int, default=30)
    parser.add_argument("--kosdaq-limit", type=int, default=30)
    parser.add_argument("--delay", type=float, default=0.08)
    parser.add_argument("--portfolio-size", type=int, default=10)
    parser.add_argument("--smallcap-size", type=int, default=20)
    parser.add_argument("--no-prices", action="store_true")
    parser.add_argument("--python", default=str(_default_python()), help="Python executable path")
    parser.add_argument("--run-now", action="store_true", help="Kickstart the job immediately after install")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.command == "status":
        raise SystemExit(status())
    if args.command == "uninstall":
        uninstall()
        return

    if args.hour < 0 or args.hour > 23:
        raise SystemExit("--hour must be 0-23")
    if args.minute < 0 or args.minute > 59:
        raise SystemExit("--minute must be 0-59")
    if args.limit < 1:
        raise SystemExit("--limit must be at least 1")
    if args.kospi_limit < 0 or args.kosdaq_limit < 0:
        raise SystemExit("--kospi-limit and --kosdaq-limit must be non-negative")
    if args.delay < 0:
        raise SystemExit("--delay must be non-negative")
    if args.portfolio_size < 1 or args.smallcap_size < 1:
        raise SystemExit("--portfolio-size and --smallcap-size must be at least 1")

    plist = build_plist(
        Path(args.python),
        args.hour,
        args.minute,
        _parse_weekdays(args.weekdays),
        args.limit,
        args.kospi_limit,
        args.kosdaq_limit,
        args.delay,
        args.portfolio_size,
        args.smallcap_size,
        no_prices=args.no_prices,
    )
    if args.command == "render":
        sys.stdout.buffer.write(plistlib.dumps(plist, sort_keys=False))
        return
    install(plist, run_now=args.run_now)


if __name__ == "__main__":
    main()
