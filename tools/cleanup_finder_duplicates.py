#!/usr/bin/env python3
"""Remove Finder-style ' 2' / ' 2.*' duplicate artifacts from the workspace.

macOS Finder copies create names like ``file 2.kt`` or ``src 2/``. Those files
pollute source trees and inflate the workspace. This script deletes only
obvious duplicate-name patterns and never touches git history.

Safe by default: dry-run unless ``--apply`` is passed.
"""

from __future__ import annotations

import argparse
import re
import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

SKIP_DIR_NAMES = {
    ".git",
    ".gradle",
    ".kotlin",
    ".venv",
    "__pycache__",
    "node_modules",
    "DerivedData",
}

# Match Finder copy suffixes: "foo 2.kt", "bar 2", "baz (2).xml"
DUPLICATE_NAME_RE = re.compile(
    r"^(?P<stem>.+?)(?:\s+\d+|\s+\(\d+\))(?P<suffix>\.[^./]+)?$"
)


def is_duplicate_name(name: str) -> bool:
    if name in {"src 2", "generated 2", "reports 2", "test-results 2"}:
        return True
    if " 2." in name or name.endswith(" 2") or name.endswith(" 2/"):
        return True
    match = DUPLICATE_NAME_RE.match(name)
    if not match:
        return False
    # Require a numeric copy marker, not arbitrary "something 2" words alone
    # unless the pattern is clearly "name 2.ext" or "name 2".
    return bool(re.search(r"\s+\d+(\.[^./]+)?$", name) or re.search(r"\s+\(\d+\)(\.[^./]+)?$", name))


def iter_candidates(root: Path) -> list[Path]:
    found: list[Path] = []
    for path in root.rglob("*"):
        if any(part in SKIP_DIR_NAMES for part in path.parts):
            continue
        # Skip deep Android/Xcode build trees for speed but still allow source
        # duplicate cleanup outside build dirs.
        if "build" in path.parts and path.suffix.lower() in {
            ".class", ".dex", ".bin", ".jar", ".so", ".o", ".a"
        }:
            continue
        if is_duplicate_name(path.name):
            found.append(path)
    # Delete deepest paths first so parents can be removed cleanly.
    return sorted(found, key=lambda p: len(p.parts), reverse=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=ROOT,
        help="Workspace root to scan (default: quantbridge-fullstack)",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Actually delete matches. Default is dry-run.",
    )
    args = parser.parse_args()
    root = args.root.resolve()
    candidates = iter_candidates(root)
    if not candidates:
        print("No Finder-style duplicates found.")
        return 0

    mode = "DELETE" if args.apply else "DRY-RUN"
    print(f"{mode}: {len(candidates)} paths under {root}")
    bytes_total = 0
    for path in candidates:
        size = 0
        try:
            if path.is_file():
                size = path.stat().st_size
            elif path.is_dir():
                size = sum(f.stat().st_size for f in path.rglob("*") if f.is_file())
        except OSError:
            size = 0
        bytes_total += size
        rel = path.relative_to(root) if path.is_relative_to(root) else path
        print(f"- {rel} ({size} bytes)")
        if args.apply:
            try:
                if path.is_dir():
                    shutil.rmtree(path, ignore_errors=True)
                elif path.exists():
                    path.unlink(missing_ok=True)
            except OSError as exc:
                print(f"  ! failed: {exc}")

    print(f"Total ~{bytes_total / (1024 * 1024):.1f} MiB")
    if not args.apply:
        print("Re-run with --apply to delete.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
