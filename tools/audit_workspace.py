#!/usr/bin/env python3
"""Non-destructive workspace audit for the QuantBridge desktop folder.

The project currently lives in a mixed workspace with generated data, local
secrets, and multiple Git roots. This script reports the risky parts without
moving or deleting anything.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[1]
WORKSPACE_ROOT = PROJECT_ROOT.parent

SECRET_OR_GENERATED_NAMES = {
    ".env",
    "key.json",
    "kiwoom_credentials.json",
    "staging.env",
    "cache.db",
    "quantbridge.sqlite3",
}
SECRET_OR_GENERATED_SUFFIXES = {
    ".sqlite3",
    ".db",
    ".parquet",
    ".pyc",
    ".pt",
    ".log",
}
IGNORED_DIRS = {
    ".git",
    ".kotlin",
    ".mypy_cache",
    ".pytest_cache",
    ".ruff_cache",
    ".venv",
    ".gradle",
    "build",
    "__pycache__",
    "coverage",
    "data_lake",
    "DerivedData",
    "docs_cache",
    "logs",
}


@dataclass
class GitRoot:
    path: str
    branch: str
    remote: str
    dirty_entries: int


@dataclass
class DuplicateRisk:
    path: str
    reason: str
    identical: bool | None = None


@dataclass
class LocalArtifact:
    path: str
    bytes: int
    kind: str


def _run(cmd: list[str], cwd: Path) -> str:
    try:
        result = subprocess.run(
            cmd,
            cwd=str(cwd),
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
        )
    except OSError:
        return ""
    return result.stdout.strip()


def _git_roots() -> list[GitRoot]:
    roots = sorted(path.parent for path in WORKSPACE_ROOT.rglob(".git") if path.is_dir())
    out: list[GitRoot] = []
    for root in roots:
        branch = _run(["git", "branch", "--show-current"], root) or "(detached)"
        remote = _run(["git", "remote", "get-url", "origin"], root) or ""
        dirty = len([line for line in _run(["git", "status", "--short"], root).splitlines() if line.strip()])
        out.append(
            GitRoot(
                path=str(root.relative_to(WORKSPACE_ROOT)),
                branch=branch,
                remote=remote,
                dirty_entries=dirty,
            )
        )
    return out


def _duplicate_risks() -> list[DuplicateRisk]:
    risks: list[DuplicateRisk] = []
    pairs = [
        ("Quant project", "quantbridge-fullstack", "two backend/fullstack trees exist"),
        ("Stock Analysis", "quantbridge-fullstack/Stock Analysis", "two iOS app trees exist"),
        ("andriod", "quantbridge-fullstack/android", "top-level misspelled Android tree duplicates canonical Android tree"),
        ("android", "quantbridge-fullstack/android", "top-level Android tree duplicates canonical Android tree"),
        (
            "Quant project/GitHub/my-quant-dashboard",
            "quantbridge-fullstack/GitHub/my-quant-dashboard",
            "two Streamlit dashboard trees exist",
        ),
    ]
    for left, right, reason in pairs:
        left_path = WORKSPACE_ROOT / left
        right_path = WORKSPACE_ROOT / right
        if left_path.exists() and right_path.exists():
            risks.append(
                DuplicateRisk(
                    path=f"{left} <-> {right}",
                    reason=reason,
                    identical=_dirs_identical(left_path, right_path),
                )
            )
    return risks


def _dirs_identical(left: Path, right: Path) -> bool | None:
    if not left.is_dir() or not right.is_dir():
        return None
    cmd = [
        "diff",
        "-qr",
        "-x",
        ".git",
        "-x",
        "xcuserdata",
        "-x",
        "*.xcuserstate",
        "-x",
        ".DS_Store",
        "-x",
        ".venv",
        "-x",
        "__pycache__",
        "-x",
        "data_lake",
        "-x",
        "docs_cache",
        "-x",
        "logs",
        "-x",
        "build",
        "-x",
        ".gradle",
        "-x",
        ".kotlin",
        "-x",
        "local.properties",
        str(left),
        str(right),
    ]
    try:
        result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, check=False)
    except OSError:
        return None
    return result.returncode == 0 and not result.stdout.strip()


def _local_artifacts(limit: int = 200) -> list[LocalArtifact]:
    artifacts: list[LocalArtifact] = []
    for path in sorted(WORKSPACE_ROOT.rglob("*")):
        if not path.is_file():
            continue
        parts = set(path.relative_to(WORKSPACE_ROOT).parts)
        if parts & IGNORED_DIRS:
            continue
        name_match = path.name in SECRET_OR_GENERATED_NAMES
        suffix_match = path.suffix in SECRET_OR_GENERATED_SUFFIXES
        if not name_match and not suffix_match:
            continue
        rel = path.relative_to(WORKSPACE_ROOT)
        kind = "secret" if path.name in {".env", "key.json", "kiwoom_credentials.json", "staging.env"} else "generated"
        artifacts.append(LocalArtifact(path=str(rel), bytes=path.stat().st_size, kind=kind))
        if len(artifacts) >= limit:
            break
    return artifacts


def _recommendations(git_roots: list[GitRoot], duplicate_risks: list[DuplicateRisk]) -> list[str]:
    recommendations = [
        "Keep key.json, .env, SQLite, Parquet, logs, APKs, and model weights out of Git history.",
    ]
    fullstack = next((root for root in git_roots if root.path == "quantbridge-fullstack"), None)
    if fullstack and fullstack.remote:
        recommendations.insert(
            0,
            "Use quantbridge-fullstack as the canonical source tree; keep Quant project only for ignored local runtime state until cleanup.",
        )
    if duplicate_risks:
        recommendations.append("Pick one canonical Git root before deleting duplicate folders.")
        if any("Stock Analysis" in risk.path for risk in duplicate_risks):
            recommendations.append("Do not delete top-level Stock Analysis while it has uncommitted files.")
        recommendations.append("Run a file-level diff before any folder removal; this audit is intentionally read-only.")
    else:
        recommendations.append("No duplicate source trees are currently detected inside the workspace.")
    return recommendations


def build_report() -> dict:
    git_roots = _git_roots()
    duplicate_risks = _duplicate_risks()
    artifacts = _local_artifacts()
    return {
        "workspace_root": str(WORKSPACE_ROOT),
        "project_root": str(PROJECT_ROOT),
        "git_roots": [asdict(root) for root in git_roots],
        "duplicate_risks": [asdict(risk) for risk in duplicate_risks],
        "local_artifacts": [asdict(artifact) for artifact in artifacts],
        "recommendations": _recommendations(git_roots, duplicate_risks),
    }


def print_human(report: dict) -> None:
    print("QuantBridge workspace audit")
    print(f"workspace_root: {report['workspace_root']}")
    print(f"project_root  : {report['project_root']}")

    print("\nGit roots")
    for root in report["git_roots"]:
        remote = f" | {root['remote']}" if root["remote"] else ""
        print(f"- {root['path']} | branch={root['branch']} | dirty={root['dirty_entries']}{remote}")

    print("\nDuplicate risks")
    if report["duplicate_risks"]:
        for risk in report["duplicate_risks"]:
            suffix = ""
            if risk.get("identical") is True:
                suffix = " | identical after local ignores"
            elif risk.get("identical") is False:
                suffix = " | differs"
            print(f"- {risk['path']}: {risk['reason']}{suffix}")
    else:
        print("- none detected")

    print("\nLocal secrets/generated artifacts")
    if report["local_artifacts"]:
        for artifact in report["local_artifacts"]:
            print(f"- {artifact['kind']}: {artifact['path']} ({artifact['bytes']} bytes)")
    else:
        print("- none detected")

    print("\nRecommendations")
    for item in report["recommendations"]:
        print(f"- {item}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Audit QuantBridge workspace structure without changing files.")
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON.")
    args = parser.parse_args()
    report = build_report()
    if args.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    else:
        print_human(report)


if __name__ == "__main__":
    main()
