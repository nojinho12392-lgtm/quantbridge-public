#!/usr/bin/env python3
"""Export a public-safe snapshot from quantbridge-fullstack.

Copies source, docs, tests, and tooling while excluding secrets, local data
lakes, build outputs, staging deploy credentials, and runtime caches.

Usage:
  python tools/export_public_tree.py --dest ../quantbridge-public-clean --apply
  python tools/export_public_tree.py --dest /tmp/quantbridge-public --dry-run
"""

from __future__ import annotations

import argparse
import fnmatch
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

# Top-level entries that may be exported (allow-list).
ALLOW_TOP = {
    "api",
    "android",
    "pipeline",
    "quantbridge",
    "tools",
    "scripts",
    "docs",
    "examples",
    "data",
    "Stock Analysis",
    "GitHub",
    "requirements.txt",
    "Makefile",
    "docker-compose.yml",
    "README.md",
    "LICENSE",
    "main_engine.py",
    "main_dag.py",
    "cache_manager.py",
    "sheets_client.py",
    "kr_sector_map.py",
    ".env.example",
    ".gitignore",
}

# Always skip these path fragments anywhere under the tree.
SKIP_PARTS = {
    ".git",
    ".gradle",
    ".kotlin",
    ".venv",
    ".idea",
    ".DS_Store",
    "__pycache__",
    "build",
    "data_lake",
    "docs_cache",
    "logs",
    "artifacts",
    "DerivedData",
    "xcuserdata",
    "node_modules",
    "key.json",
    "kiwoom_credentials.json",
}

SKIP_SUFFIXES = {
    ".pyc",
    ".pyo",
    ".sqlite3",
    ".db",
    ".parquet",
    ".pt",
    ".log",
    ".apk",
    ".aab",
    ".ipa",
}

SKIP_NAMES = {
    ".env",
    "local.properties",
    "staging.env",
    "key.json",
    "kiwoom_credentials.json",
    "quantbridge.sqlite3",
    "cache.db",
}

SKIP_GLOBS = [
    "deploy/azure/staging.env",
    "**/Icon\r",
    "**/* 2.*",
    "**/* 2",
    "**/src 2/**",
]


def should_skip(rel: Path) -> bool:
    parts = set(rel.parts)
    if parts & SKIP_PARTS:
        return True
    if rel.name in SKIP_NAMES:
        return True
    if rel.suffix in SKIP_SUFFIXES:
        return True
    # Finder junk / AppleDouble
    if rel.name.startswith("._") or rel.name in {"Icon\r", "Icon"}:
        return True
    text = rel.as_posix()
    for pattern in SKIP_GLOBS:
        if fnmatch.fnmatch(text, pattern):
            return True
    # Keep staging deploy scripts out of public by default
    if text.startswith("deploy/"):
        return True
    if text.startswith(".github/workflows/") and "staging" in text:
        return True
    return False


def collect_files(src_root: Path) -> list[Path]:
    files: list[Path] = []
    for top in sorted(src_root.iterdir(), key=lambda p: p.name.lower()):
        if top.name not in ALLOW_TOP and not (
            top.is_file() and top.name.startswith("test_") and top.suffix == ".py"
        ):
            # allow root unit tests
            if not (top.is_file() and top.name.startswith("test_") and top.suffix == ".py"):
                continue
        if top.is_file():
            rel = top.relative_to(src_root)
            if not should_skip(rel):
                files.append(top)
            continue
        for path in top.rglob("*"):
            if not path.is_file():
                continue
            rel = path.relative_to(src_root)
            if should_skip(rel):
                continue
            files.append(path)
    # Root tests always considered
    for path in src_root.glob("test_*.py"):
        if path not in files:
            files.append(path)
    return sorted(set(files), key=lambda p: p.as_posix())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dest", type=Path, required=True, help="Destination directory")
    parser.add_argument("--apply", action="store_true", help="Copy files (default dry-run)")
    parser.add_argument(
        "--prune-dest",
        action="store_true",
        help="When applying, delete destination paths not present in export (dangerous).",
    )
    args = parser.parse_args()

    dest = args.dest.resolve()
    files = collect_files(ROOT)
    print(f"{'COPY' if args.apply else 'DRY-RUN'}: {len(files)} files -> {dest}")

    for src in files:
        rel = src.relative_to(ROOT)
        target = dest / rel
        print(f"- {rel}")
        if args.apply:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, target)

    if args.apply:
        # Ensure public package id if android was exported
        gradle = dest / "android/app/build.gradle.kts"
        if gradle.exists():
            text = gradle.read_text(encoding="utf-8")
            if "com.example.myapplication" in text:
                text = text.replace("com.example.myapplication", "com.qubit.quantbridge")
                gradle.write_text(text, encoding="utf-8")
                print("Normalized Android applicationId/namespace in export")
        print(f"Export complete: {dest}")
    else:
        print("Re-run with --apply to write files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
