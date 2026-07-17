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
    ".apk",
    ".aab",
    ".ipa",
}

# Placeholder Android application IDs that must not ship in source.
BLOCKED_PACKAGE_MARKERS = (
    'applicationId = "com.example.myapplication"',
    'namespace = "com.example.myapplication"',
    "package com.example.myapplication",
)

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
        # Only enforce package identity on Android app source / Gradle config.
        if rel.startswith("android/app/") and not "/build/" in rel:
            for marker in BLOCKED_PACKAGE_MARKERS:
                if marker in text:
                    violations.append(
                        f"{rel} still uses placeholder Android package marker {marker!r}"
                    )
                    break
    return violations


def package_identity_violations() -> list[str]:
    """Fail if the canonical Android app identity is not branded."""
    gradle = ROOT / "android/app/build.gradle.kts"
    main_activity = ROOT / "android/app/src/main/java/com/qubit/quantbridge/MainActivity.kt"
    violations: list[str] = []
    if not gradle.exists():
        return ["android/app/build.gradle.kts is missing"]
    text = gradle.read_text(encoding="utf-8")
    if 'applicationId = "com.qubit.quantbridge"' not in text:
        violations.append('android/app/build.gradle.kts must set applicationId = "com.qubit.quantbridge"')
    if 'namespace = "com.qubit.quantbridge"' not in text:
        violations.append('android/app/build.gradle.kts must set namespace = "com.qubit.quantbridge"')
    if not main_activity.exists():
        violations.append("android package path missing MainActivity under com/qubit/quantbridge")
    return violations


def main() -> int:
    paths = tracked_files()
    violations = path_violations(paths) + content_violations(paths) + package_identity_violations()
    if violations:
        print("Tracked local secret/generated artifact check FAILED:")
        for item in violations:
            print(f"- {item}")
        return 2
    print("Tracked local secret/generated artifact check OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
