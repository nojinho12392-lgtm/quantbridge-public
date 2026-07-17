#!/usr/bin/env python3
"""Manage the macOS launchd schedule for QuantBridge ops-health checks.

Default schedule: every 30 minutes. The job calls the local FastAPI
`/ops/health` endpoint through `tools/check_ops_health.py`, writes logs, and can
send webhook alerts when `QUANT_OPS_WEBHOOK_URL` or `--webhook-url` is set.

Examples:
    python tools/install_ops_health_launchd.py render
    python tools/install_ops_health_launchd.py status
    python tools/install_ops_health_launchd.py install
    python tools/install_ops_health_launchd.py uninstall
"""

from __future__ import annotations

import argparse
import os
import plistlib
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LABEL = "com.quantbridge.ops-health"
PLIST_PATH = Path.home() / "Library" / "LaunchAgents" / f"{LABEL}.plist"
DEFAULT_INTERVAL_MINUTES = 30
DEFAULT_URL = os.getenv("QUANT_API_BASE_URL", "http://127.0.0.1:8000")
DEFAULT_TIMEOUT_SECONDS = float(os.getenv("QUANT_OPS_TIMEOUT_SECONDS", "30"))


def _default_python() -> Path:
    local = ROOT / ".venv" / "bin" / "python"
    return local if local.exists() else Path(sys.executable)


def _domain() -> str:
    return f"gui/{os.getuid()}"


def _service() -> str:
    return f"{_domain()}/{LABEL}"


def _run(cmd: list[str], check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, text=True, capture_output=True, check=check)


def build_plist(
    python_path: Path,
    interval_minutes: int,
    url: str,
    timeout_seconds: float,
    webhook_url: str = "",
    strict_exit: bool = False,
) -> dict:
    log_dir = ROOT / "logs"
    program_args = [
        str(python_path),
        str(ROOT / "tools" / "check_ops_health.py"),
        "--url",
        url,
        "--timeout",
        str(timeout_seconds),
    ]
    if not strict_exit:
        program_args.append("--warn-only")
    if webhook_url:
        program_args.extend(["--webhook-url", webhook_url])

    return {
        "Label": LABEL,
        "ProgramArguments": program_args,
        "WorkingDirectory": str(ROOT),
        "StartInterval": interval_minutes * 60,
        "RunAtLoad": True,
        "StandardOutPath": str(log_dir / "ops_health.out.log"),
        "StandardErrorPath": str(log_dir / "ops_health.err.log"),
        "EnvironmentVariables": {
            "PYTHONUNBUFFERED": "1",
            "QUANT_API_BASE_URL": url,
            "QUANT_OPS_TIMEOUT_SECONDS": str(timeout_seconds),
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
    parser = argparse.ArgumentParser(description="Manage QuantBridge ops-health launchd schedule")
    parser.add_argument(
        "command",
        choices=["render", "install", "uninstall", "status"],
        help="Action to perform",
    )
    parser.add_argument("--interval-minutes", type=int, default=DEFAULT_INTERVAL_MINUTES)
    parser.add_argument("--url", default=DEFAULT_URL, help="FastAPI base URL")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS, help="HTTP timeout in seconds")
    parser.add_argument(
        "--webhook-url",
        default=os.getenv("QUANT_OPS_WEBHOOK_URL", ""),
        help="Optional JSON webhook URL for alerts",
    )
    parser.add_argument("--python", default=str(_default_python()), help="Python executable path")
    parser.add_argument("--strict-exit", action="store_true", help="Let non-OK health produce a non-zero job exit")
    parser.add_argument("--run-now", action="store_true", help="Kickstart the job immediately after install")
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
    url = str(args.url or "").strip().rstrip("/")
    if not url:
        raise SystemExit("--url is required")

    plist = build_plist(
        Path(args.python),
        args.interval_minutes,
        url,
        args.timeout,
        webhook_url=str(args.webhook_url or "").strip(),
        strict_exit=args.strict_exit,
    )

    if args.command == "render":
        sys.stdout.buffer.write(plistlib.dumps(plist, sort_keys=False))
        return
    install(plist, run_now=args.run_now)


if __name__ == "__main__":
    main()
