#!/usr/bin/env python3
"""Fail when local secrets or generated artifacts are tracked by Git."""

from __future__ import annotations

import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

BLOCKED_EXACT = {
    ".env",
    "key.json",
    "kiwoom_credentials.json",
    "deploy/azure/staging.env",
    "api/quantbridge.sqlite3",
    "cache.db",
    "cache.db-wal",
    "cache.db-shm",
    "delete_icon.py",
}

BLOCKED_PARTS = {
    ".venv",
    "__pycache__",
    "data_lake",
    "docs_cache",
    "logs",
    "artifacts",
    ".claude",
    ".codex",
}

BLOCKED_SUFFIXES = {
    ".pyc",
    ".sqlite3",
    ".db",
    ".parquet",
    ".pt",
    ".log",
}

SECRET_MARKERS = (
    "-----BEGIN " + "PRIVATE KEY-----",
    '"private_' + 'key"',
    "AZURE_CREDENTIALS" + "_JSON=",
)

ALLOW_MARKER_PATHS = {
    ".env.example",
    "docs/CODESPACES.md",
    "docs/STAGING_DEPLOY.md",
    "docs/CLOUD_MAIN_ENGINE.md",
    "deploy/azure/staging.env.example",
}


def tracked_files() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files"],
        cwd=str(ROOT),
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    )
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def path_violations(paths: list[str]) -> list[str]:
    violations: list[str] = []
    for rel in paths:
        path = Path(rel)
        parts = set(path.parts)
        if rel in BLOCKED_EXACT:
            violations.append(f"{rel} matches blocked local file name")
        elif parts & BLOCKED_PARTS:
            violations.append(f"{rel} is under blocked local/generated directory")
        elif path.suffix in BLOCKED_SUFFIXES:
            violations.append(f"{rel} has blocked generated suffix {path.suffix}")
    return violations


def content_violations(paths: list[str]) -> list[str]:
    violations: list[str] = []
    for rel in paths:
        if rel in ALLOW_MARKER_PATHS:
            continue
        path = ROOT / rel
        if not path.is_file() or path.stat().st_size > 1_000_000:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for marker in SECRET_MARKERS:
            if marker in text:
                violations.append(f"{rel} contains secret marker {marker!r}")
                break
    return violations


def main() -> int:
    paths = tracked_files()
    violations = path_violations(paths) + content_violations(paths)
    if violations:
        print("Tracked local secret/generated artifact check FAILED:")
        for item in violations:
            print(f"- {item}")
        return 2
    print("Tracked local secret/generated artifact check OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
