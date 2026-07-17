#!/usr/bin/env python3
"""Manage the macOS launchd schedule for the research-quality job.

Default schedule: Tuesday-Saturday 07:30 KST, after the US market close has
settled in Korea. This keeps the job lightweight and separate from the full
portfolio pipeline.

Examples:
    python tools/install_research_quality_launchd.py render
    python tools/install_research_quality_launchd.py status
    python tools/install_research_quality_launchd.py install
    python tools/install_research_quality_launchd.py uninstall
"""

from __future__ import annotations

import argparse
import os
import plistlib
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LABEL = "com.quantbridge.research-quality"
PLIST_PATH = Path.home() / "Library" / "LaunchAgents" / f"{LABEL}.plist"
DEFAULT_WEEKDAYS = "2,3,4,5,6"  # Tue-Sat in launchd; Sunday is 0 or 7.
DEFAULT_HOUR = 7
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


def build_plist(python_path: Path, hour: int, minute: int, weekdays: list[int]) -> dict:
    log_dir = ROOT / "logs"
    intervals = [
        {"Weekday": weekday, "Hour": hour, "Minute": minute}
        for weekday in weekdays
    ]
    return {
        "Label": LABEL,
        "ProgramArguments": [
            str(python_path),
            str(ROOT / "tools" / "run_research_quality.py"),
        ],
        "WorkingDirectory": str(ROOT),
        "StartCalendarInterval": intervals,
        "StandardOutPath": str(log_dir / "research_quality.out.log"),
        "StandardErrorPath": str(log_dir / "research_quality.err.log"),
        "EnvironmentVariables": {
            "PYTHONUNBUFFERED": "1",
        },
    }


def _domain() -> str:
    return f"gui/{os.getuid()}"


def _service() -> str:
    return f"{_domain()}/{LABEL}"


def _run(cmd: list[str], check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, text=True, capture_output=True, check=check)


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
    parser = argparse.ArgumentParser(description="Manage QuantBridge research-quality launchd schedule")
    parser.add_argument(
        "command",
        choices=["render", "install", "uninstall", "status"],
        help="Action to perform",
    )
    parser.add_argument("--hour", type=int, default=DEFAULT_HOUR)
    parser.add_argument("--minute", type=int, default=DEFAULT_MINUTE)
    parser.add_argument("--weekdays", default=DEFAULT_WEEKDAYS, help="Comma-separated launchd weekdays, 0-7")
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

    weekdays = _parse_weekdays(args.weekdays)
    plist = build_plist(Path(args.python), args.hour, args.minute, weekdays)

    if args.command == "render":
        sys.stdout.buffer.write(plistlib.dumps(plist, sort_keys=False))
        return
    install(plist, run_now=args.run_now)


if __name__ == "__main__":
    main()
