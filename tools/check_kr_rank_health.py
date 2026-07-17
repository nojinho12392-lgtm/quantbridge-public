#!/usr/bin/env python3
"""Check local Korean ranking refresh health."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quantbridge.config import get_settings
from quantbridge.storage import QuantRepository
from quantbridge.storage.parquet import ParquetLake


DATASET = "KR_Scored_Stocks"
LABEL = "com.quantbridge.kr-rank-local"
COMPANION_DATASETS = [
    (
        "KR_Final_Portfolio",
        "portfolio candidates",
        1,
        ["Rank", "Ticker", "Weight(%)", "Total_Score", "Last_Updated"],
    ),
    (
        "KR_SmallCap_Gems",
        "smallcap candidates",
        1,
        ["Rank", "Ticker", "Data_Confidence", "Total_Score", "Last_Updated"],
    ),
]
REQUIRED_COLUMNS = [
    "Rank",
    "Ticker",
    "Name",
    "Final_Score",
    "Business_Quality_Score",
    "Investability_Score",
    "Quality_Category",
]
ERROR_PATTERNS = re.compile(r"\b(traceback|error|exception|failed|modulenotfounderror)\b", re.IGNORECASE)


def _parse_date(value) -> datetime | None:
    if value is None or value == "":
        return None
    if isinstance(value, datetime):
        return value
    try:
        stamp = pd.to_datetime(value, utc=True, errors="coerce")
    except Exception:
        return None
    if pd.isna(stamp):
        return None
    return stamp.to_pydatetime()


def _age_days(value) -> float | None:
    dt = _parse_date(value)
    if dt is None:
        return None
    now = datetime.now(dt.tzinfo or timezone.utc)
    return round((now - dt).total_seconds() / 86400.0, 3)


def _latest_snapshot_date(data_lake_dir: Path, dataset: str) -> str:
    dataset_dir = data_lake_dir / dataset
    snapshots = sorted(dataset_dir.glob("snapshot_date=*"), reverse=True)
    for snapshot in snapshots:
        if snapshot.is_dir():
            return snapshot.name.split("=", 1)[-1]
    return ""


def _frame_snapshot_date(df: pd.DataFrame, data_lake_dir: Path, dataset: str) -> str:
    for column in ("snapshot_date", "Last_Updated", "Generated"):
        if column not in df.columns or df.empty:
            continue
        parsed = pd.to_datetime(df[column], utc=True, errors="coerce").dropna()
        if not parsed.empty:
            return parsed.max().strftime("%Y-%m-%d")
    return _latest_snapshot_date(data_lake_dir, dataset)


def _read_latest_frame(
    dataset: str,
    market: str,
    data_lake_dir: Path,
    repository_reader: Callable[[str, str], pd.DataFrame] | None = None,
) -> pd.DataFrame:
    if repository_reader is not None:
        try:
            df = repository_reader(dataset, market)
            if df is not None and not df.empty:
                return df
        except Exception:
            pass

    settings = get_settings()
    if settings.enable_postgres:
        try:
            df = QuantRepository(settings).read_dataframe(dataset, market=market)
            if not df.empty:
                return df
        except Exception:
            pass

    return ParquetLake(data_lake_dir).read_latest(dataset, market=market)


def _read_log_tail(path: Path, max_chars: int = 12000) -> str:
    if not path.exists():
        return ""
    text = path.read_text(encoding="utf-8", errors="replace")
    return text[-max_chars:]


def _launchd_status() -> dict:
    if platform.system() != "Darwin":
        return {
            "name": "launchd schedule",
            "status": "SKIP",
            "message": "launchd check is only available on macOS.",
        }
    service = f"gui/{os.getuid()}/{LABEL}"
    result = subprocess.run(
        ["launchctl", "print", service],
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        return {
            "name": "launchd schedule",
            "status": "FAIL",
            "message": f"{LABEL} is not loaded.",
            "detail": result.stderr.strip() or result.stdout.strip(),
        }
    output = result.stdout
    last_exit_ok = "last exit code = 0" in output or "last exit code = (never exited)" in output
    status = "OK" if last_exit_ok else "WARN"
    return {
        "name": "launchd schedule",
        "status": status,
        "message": "loaded; last exit OK" if last_exit_ok else "loaded; last exit is not 0",
    }


def check_health(
    data_lake_dir: Path,
    logs_dir: Path,
    max_age_days: int,
    min_rows: int,
    include_launchd: bool = True,
    repository_reader: Callable[[str, str], pd.DataFrame] | None = None,
) -> dict:
    checks: list[dict] = []
    df = _read_latest_frame(DATASET, "KR", data_lake_dir, repository_reader=repository_reader)
    rows = int(len(df))

    checks.append({
        "name": "dataset rows",
        "status": "OK" if rows >= min_rows else "FAIL",
        "message": f"{rows} rows in {DATASET}; expected at least {min_rows}.",
    })

    missing = [col for col in REQUIRED_COLUMNS if col not in df.columns]
    checks.append({
        "name": "required columns",
        "status": "OK" if not missing else "FAIL",
        "message": "all required quality columns present" if not missing else f"missing: {', '.join(missing)}",
    })

    snapshot_date = _frame_snapshot_date(df, data_lake_dir, DATASET)
    age = _age_days(snapshot_date)
    checks.append({
        "name": "snapshot freshness",
        "status": "OK" if age is not None and age <= max_age_days else "FAIL",
        "message": (
            f"latest snapshot {snapshot_date} is {age:.1f}d old; max {max_age_days}d."
            if age is not None
            else "latest snapshot date is unavailable."
        ),
        "detail": {"snapshot_date": snapshot_date, "age_days": age, "max_age_days": max_age_days},
    })

    if not df.empty and "Quality_Category" in df.columns:
        categorized = int(df["Quality_Category"].fillna("").astype(str).str.strip().ne("").sum())
        checks.append({
            "name": "quality categories",
            "status": "OK" if categorized >= min_rows else "WARN",
            "message": f"{categorized}/{rows} rows have a Quality_Category.",
        })

    for dataset, label, companion_min_rows, required_columns in COMPANION_DATASETS:
        companion = _read_latest_frame(dataset, "KR", data_lake_dir, repository_reader=repository_reader)
        companion_rows = int(len(companion))
        checks.append({
            "name": label,
            "status": "OK" if companion_rows >= companion_min_rows else "FAIL",
            "message": f"{companion_rows} rows in {dataset}; expected at least {companion_min_rows}.",
            "detail": {"dataset": dataset, "rows": companion_rows, "min_rows": companion_min_rows},
        })
        missing_companion = [col for col in required_columns if col not in companion.columns]
        checks.append({
            "name": f"{label} columns",
            "status": "OK" if not missing_companion else "FAIL",
            "message": (
                "required columns present"
                if not missing_companion
                else f"missing: {', '.join(missing_companion)}"
            ),
            "detail": {"dataset": dataset, "missing": missing_companion},
        })
        companion_snapshot = _frame_snapshot_date(companion, data_lake_dir, dataset)
        companion_age = _age_days(companion_snapshot)
        checks.append({
            "name": f"{label} freshness",
            "status": "OK" if companion_age is not None and companion_age <= max_age_days else "FAIL",
            "message": (
                f"latest snapshot {companion_snapshot} is {companion_age:.1f}d old; max {max_age_days}d."
                if companion_age is not None
                else "latest snapshot date is unavailable."
            ),
            "detail": {
                "dataset": dataset,
                "snapshot_date": companion_snapshot,
                "age_days": companion_age,
                "max_age_days": max_age_days,
            },
        })

    err_tail = _read_log_tail(logs_dir / "kr_rank_local.err.log")
    out_tail = _read_log_tail(logs_dir / "kr_rank_local.out.log")
    log_text = "\n".join([err_tail, out_tail])
    checks.append({
        "name": "ranker logs",
        "status": "WARN" if ERROR_PATTERNS.search(log_text or "") else "OK",
        "message": "error-like text found in recent logs" if ERROR_PATTERNS.search(log_text or "") else "no error-like text in recent logs",
    })

    if include_launchd:
        checks.append(_launchd_status())

    fail_count = sum(1 for check in checks if str(check.get("status")).upper() == "FAIL")
    warn_count = sum(1 for check in checks if str(check.get("status")).upper() == "WARN")
    status = "FAIL" if fail_count else "WARN" if warn_count else "OK"
    return {
        "healthy": fail_count == 0,
        "status": status,
        "generated_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "dataset": DATASET,
        "rows": rows,
        "snapshot_date": snapshot_date,
        "status_counts": {"OK": len(checks) - fail_count - warn_count, "WARN": warn_count, "FAIL": fail_count},
        "checks": checks,
    }


def print_human(report: dict) -> None:
    marker = "OK" if report.get("healthy") else "NOT OK"
    print(f"[kr-rank-health] {marker} - {report.get('status')}")
    print(f"  dataset       : {report.get('dataset')}")
    print(f"  rows          : {report.get('rows')}")
    print(f"  snapshot_date : {report.get('snapshot_date') or '-'}")
    print(f"  generated     : {report.get('generated_at')}")
    print(f"  status_counts : {report.get('status_counts')}")
    for check in report.get("checks", []):
        print(f"  {check.get('status', 'UNKNOWN'):<5} {check.get('name', '-'):<20} {check.get('message', '')}")


def parse_args() -> argparse.Namespace:
    settings = get_settings()
    parser = argparse.ArgumentParser(description="Check local KR rank refresh health")
    parser.add_argument("--data-lake-dir", default=str(settings.data_lake_dir))
    parser.add_argument("--logs-dir", default=str(ROOT / "logs"))
    parser.add_argument("--max-age-days", type=int, default=2)
    parser.add_argument("--min-rows", type=int, default=20)
    parser.add_argument("--skip-launchd", action="store_true")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--strict", action="store_true", help="Exit non-zero on WARN as well as FAIL")
    parser.add_argument("--warn-only", action="store_true", help="Always exit 0")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    report = check_health(
        Path(args.data_lake_dir).expanduser(),
        Path(args.logs_dir).expanduser(),
        max_age_days=args.max_age_days,
        min_rows=args.min_rows,
        include_launchd=not args.skip_launchd,
    )
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2, default=str))
    else:
        print_human(report)

    if args.warn_only:
        return 0
    if str(report.get("status")).upper() == "FAIL":
        return 2
    if args.strict and str(report.get("status")).upper() != "OK":
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
