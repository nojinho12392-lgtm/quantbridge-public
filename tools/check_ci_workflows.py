#!/usr/bin/env python3
"""Validate GitHub Actions workflow files without network access."""

from __future__ import annotations

import re
import shutil
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW_DIRS = [
    ROOT / ".github" / "workflows",
    ROOT / "GitHub" / "my-quant-dashboard" / ".github" / "workflows",
]


def workflow_files() -> list[Path]:
    files: list[Path] = []
    for directory in WORKFLOW_DIRS:
        if directory.exists():
            files.extend(sorted(directory.glob("*.yml")))
            files.extend(sorted(directory.glob("*.yaml")))
    return files


def _parse_with_python_yaml(paths: list[Path]) -> bool:
    try:
        import yaml  # type: ignore[import-not-found]
    except ImportError:
        return False
    for path in paths:
        with path.open(encoding="utf-8") as fh:
            yaml.safe_load(fh)
    return True


def _parse_with_ruby(paths: list[Path]) -> bool:
    ruby = shutil.which("ruby")
    if ruby is None:
        return False
    script = "require 'yaml'; ARGV.each { |path| YAML.load_file(path) }"
    subprocess.run([ruby, "-e", script, *map(str, paths)], check=True)
    return True


def validate_yaml_syntax(paths: list[Path]) -> None:
    if _parse_with_python_yaml(paths):
        return
    if _parse_with_ruby(paths):
        return
    raise SystemExit("Install PyYAML or Ruby to validate workflow YAML syntax")


def _contains(text: str, pattern: str) -> bool:
    return re.search(pattern, text, flags=re.MULTILINE) is not None


def validate_workflow_contracts(paths: list[Path]) -> list[str]:
    errors: list[str] = []
    for path in paths:
        rel = path.relative_to(ROOT)
        text = path.read_text(encoding="utf-8")
        if not _contains(text, r"^name:\s*\S"):
            errors.append(f"{rel}: missing workflow name")
        if not _contains(text, r"^on:"):
            errors.append(f"{rel}: missing event trigger block")
        if not _contains(text, r"^jobs:"):
            errors.append(f"{rel}: missing jobs block")
        if "uses: actions/checkout@" not in text:
            errors.append(f"{rel}: no checkout step found")

        if rel.as_posix() == ".github/workflows/ci.yml":
            for expected in [
                "tools/check_ci_workflows.py",
                "tools/check_data_quality.py",
                "tools/check_staging_status.py",
                "tools/check_no_local_artifacts.py",
                "python -m unittest test_contracts.py test_smallcap_scoring.py",
                "docker compose config --quiet",
                "android/gradlew",
                ":app:assembleDebug",
            ]:
                if expected not in text:
                    errors.append(f"{rel}: missing {expected!r}")

        if rel.as_posix() == ".github/workflows/deploy-api-staging.yml":
            for expected in [
                "workflow_dispatch",
                "push:",
                "paths:",
                "QUANT_CORS_ORIGINS_VALUE",
                "QUANT_AUTH_RATE_LIMIT_PER_MINUTE_VALUE",
                "tools/check_staging_status.py",
                "--wait-ready-seconds",
            ]:
                if expected not in text:
                    errors.append(f"{rel}: missing {expected!r}")
    return errors


def main() -> int:
    paths = workflow_files()
    if not paths:
        raise SystemExit("No GitHub Actions workflow files found")
    validate_yaml_syntax(paths)
    errors = validate_workflow_contracts(paths)
    if errors:
        print("Workflow validation FAILED:")
        for error in errors:
            print(f"- {error}")
        return 2
    print(f"Workflow validation OK ({len(paths)} files)")
    for path in paths:
        print(f"- {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
