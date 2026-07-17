from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any

MOBILE_CACHE_WARM_ENDPOINTS = {
    "portfolio/us",
    "portfolio/kr",
    "smallcap/us",
    "smallcap/kr",
    "macro",
}


@dataclass(frozen=True)
class OpsRunPayloadBuilder:
    repository: Callable[[], Any]
    safe_float: Callable[[Any], float | None]
    utc_now: Callable[[], datetime]

    def run_scripts(self, payload: dict) -> list[str]:
        scripts: list[str] = []
        for step in payload.get("steps") or []:
            if isinstance(step, str):
                scripts.append(step)
        for result in payload.get("results") or []:
            if isinstance(result, dict) and result.get("script"):
                scripts.append(str(result["script"]))
        return list(dict.fromkeys(scripts))

    def run_kind(self, scripts: list[str]) -> str:
        script_set = set(scripts)
        if {
            "pipeline/14_factor_ic_report.py",
            "pipeline/15_signal_quality_gate.py",
            "pipeline/16_factor_weight_policy.py",
            "pipeline/17_factor_policy_backtest.py",
        }.issubset(script_set):
            return "research_quality"
        if "tools/warm_detail_cache.py" in script_set:
            return "full_pipeline"
        return "pipeline"

    def run_elapsed(self, payload: dict) -> float | None:
        total = 0.0
        seen = False
        for result in payload.get("results") or []:
            if not isinstance(result, dict):
                continue
            value = self.safe_float(result.get("elapsed_sec"))
            if value is None:
                continue
            total += value
            seen = True
        return round(total, 3) if seen else None

    def pipeline_runs_payload(self, limit: int = 20) -> dict:
        safe_limit = max(1, min(int(limit or 20), 100))
        df = self.repository().read_pipeline_runs(limit=safe_limit)
        items: list[dict] = []
        if not df.empty:
            for row in df.to_dict("records"):
                payload = row.get("payload") if isinstance(row.get("payload"), dict) else {}
                scripts = self.run_scripts(payload)
                items.append({
                    "run_id": row.get("run_id"),
                    "runner": row.get("runner"),
                    "status": row.get("status"),
                    "started_at": row.get("started_at"),
                    "finished_at": row.get("finished_at"),
                    "kind": self.run_kind(scripts),
                    "scripts": scripts,
                    "elapsed_sec": self.run_elapsed(payload),
                })

        status_counts: dict[str, int] = {}
        for item in items:
            status_text = str(item.get("status") or "unknown").lower()
            status_counts[status_text] = status_counts.get(status_text, 0) + 1

        latest_research = next(
            (item for item in items if item.get("kind") == "research_quality"),
            None,
        )
        return {
            "items": items,
            "latest_research_quality": latest_research,
            "status_counts": status_counts,
            "source": "pipeline_runs",
        }

    def parse_iso_datetime(self, value: str | None) -> datetime | None:
        if not value:
            return None
        try:
            return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        except Exception:
            return None

    def age_hours(self, value: str | None) -> float | None:
        dt = self.parse_iso_datetime(value)
        if dt is None:
            return None
        now = datetime.now(dt.tzinfo) if dt.tzinfo else self.utc_now()
        return round((now - dt).total_seconds() / 3600.0, 3)

    def research_health_payload(self, max_age_hours: int = 84) -> dict:
        safe_hours = max(1, min(int(max_age_hours or 84), 240))
        runs = self.pipeline_runs_payload(limit=100)
        latest = runs.get("latest_research_quality")

        if not latest:
            return {
                "healthy": False,
                "status": "MISSING",
                "reason": "No research-quality run has been recorded yet.",
                "max_age_hours": safe_hours,
                "age_hours": None,
                "latest_research_quality": None,
            }

        status_text = str(latest.get("status") or "unknown").lower()
        finished_at = latest.get("finished_at")
        age = self.age_hours(finished_at)
        if status_text != "success":
            return {
                "healthy": False,
                "status": "FAILED",
                "reason": f"Latest research-quality run status is {status_text}.",
                "max_age_hours": safe_hours,
                "age_hours": age,
                "latest_research_quality": latest,
            }
        if age is None:
            return {
                "healthy": False,
                "status": "UNKNOWN_AGE",
                "reason": "Latest research-quality run has no finished_at timestamp.",
                "max_age_hours": safe_hours,
                "age_hours": None,
                "latest_research_quality": latest,
            }
        if age > safe_hours:
            return {
                "healthy": False,
                "status": "STALE",
                "reason": f"Latest successful research-quality run is {age:.1f}h old.",
                "max_age_hours": safe_hours,
                "age_hours": age,
                "latest_research_quality": latest,
            }
        return {
            "healthy": True,
            "status": "OK",
            "reason": f"Latest research-quality run succeeded {age:.1f}h ago.",
            "max_age_hours": safe_hours,
            "age_hours": age,
            "latest_research_quality": latest,
        }


@dataclass(frozen=True)
class OpsCheckBuilder:
    check: Callable[..., dict]
    load_simple: Callable[[str, list[str]], list[dict]]
    build_data_quality_report: Callable[..., dict]
    repository: Callable[[], Any]
    data_source_payload: Callable[[], dict]
    check_kr_rank_health: Callable[..., dict]
    settings: Any
    logs_dir: Any
    kr_rank_health_payload_loader: Callable[[int], dict] | None = None

    def factor_quality_check(self) -> dict:
        try:
            items = self.load_simple("Signal_Quality_Gates", [
                "Mean_IC", "Positive_IC_Rate", "Mean_Top_Bottom_Spread",
                "Mean_Hit_Rate", "Snapshots", "Total_Observations",
                "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
            ])
        except Exception as exc:
            return self.check("Signal quality", "WARN", f"{type(exc).__name__}: {exc}")

        if not items:
            return self.check("Signal quality", "WARN", "No signal quality rows available yet.")

        counts: dict[str, int] = {}
        for item in items:
            value = str(item.get("Status") or "UNKNOWN").upper()
            counts[value] = counts.get(value, 0) + 1
        worst = min(counts, key=lambda value: {"FAIL": 0, "WATCH": 1, "INSUFFICIENT": 2, "PASS": 3}.get(value, 9))
        production_ready = [
            item for item in items
            if str(item.get("Production_Ready") or "").upper() in {"TRUE", "1", "YES"}
        ]
        if production_ready:
            production_counts: dict[str, int] = {}
            for item in production_ready:
                value = str(item.get("Status") or "UNKNOWN").upper()
                production_counts[value] = production_counts.get(value, 0) + 1
            production_worst = min(
                production_counts,
                key=lambda value: {"FAIL": 0, "WATCH": 1, "INSUFFICIENT": 2, "PASS": 3}.get(value, 9),
            )
            status_text = "WARN" if production_worst in {"FAIL", "WATCH", "INSUFFICIENT", "UNKNOWN"} else "OK"
            message = f"Worst production gate={production_worst}; {len(items)} factor/horizon rows."
        else:
            status_text = "WARN" if worst == "UNKNOWN" else "OK"
            message = f"Observation-only research gates available; worst gate={worst}; {len(items)} factor/horizon rows."
        return self.check(
            "Signal quality",
            status_text,
            message,
            {
                "rows": len(items),
                "status_counts": counts,
                "worst_gate": worst,
                "production_ready_rows": len(production_ready),
            },
        )

    def data_quality_payload(self, max_age_days: int | None = None) -> dict:
        return self.build_data_quality_report(
            lambda dataset, market: self.repository().read_dataframe(dataset, market=market),
            max_age_days=max_age_days,
        )

    def data_quality_check(self, max_age_days: int | None = None) -> dict:
        try:
            payload = self.data_quality_payload(max_age_days=max_age_days)
        except Exception as exc:
            return self.check("Data quality", "FAIL", f"{type(exc).__name__}: {exc}")
        status_text = str(payload.get("status") or "UNKNOWN").upper()
        if status_text == "OK":
            check_status = "OK"
        elif status_text == "DEGRADED":
            check_status = "WARN"
        else:
            check_status = "FAIL"
        problem_datasets = [
            f"{item.get('dataset')}:{item.get('status')}"
            for item in payload.get("datasets", [])
            if str(item.get("status") or "").upper() not in {"OK", ""}
        ]
        issue_suffix = f"; issues={', '.join(problem_datasets[:5])}" if problem_datasets else ""
        return self.check(
            "Data quality",
            check_status,
            f"status={status_text}; datasets={len(payload.get('datasets', []))}{issue_suffix}",
            {
                "status": payload.get("status"),
                "status_counts": payload.get("status_counts", {}),
                "datasets": [
                    {
                        "dataset": item.get("dataset"),
                        "market": item.get("market"),
                        "status": item.get("status"),
                        "rows": item.get("rows"),
                    }
                    for item in payload.get("datasets", [])
                ],
            },
        )

    def kr_rank_health_payload(self, max_age_days: int = 2) -> dict:
        return self.check_kr_rank_health(
            self.settings.data_lake_dir,
            self.logs_dir,
            max_age_days=max_age_days,
            min_rows=20,
            include_launchd=True,
        )

    def kr_rank_health_check(self, max_age_days: int = 2) -> dict:
        try:
            payload = self._kr_rank_health_payload(max_age_days=max_age_days)
        except Exception as exc:
            return self.check("KR rank refresh", "FAIL", f"{type(exc).__name__}: {exc}")
        status_text = str(payload.get("status") or "UNKNOWN").upper()
        if status_text == "OK":
            check_status = "OK"
        elif status_text == "FAIL":
            check_status = "FAIL"
        else:
            check_status = "WARN"
        return self.check(
            "KR rank refresh",
            check_status,
            f"status={status_text}; rows={payload.get('rows', 0)}; snapshot={payload.get('snapshot_date') or '-'}",
            payload,
        )

    def data_source_check(self) -> dict:
        payload = self.data_source_payload()
        summary = payload.get("summary", {})
        fallback_count = (
            int(summary.get("sheet", 0))
            + int(summary.get("sheet_empty", 0))
            + int(summary.get("sheet_error", 0))
        )
        storage_error_count = int(summary.get("storage_error", 0))
        if storage_error_count:
            return self.check(
                "Data source fallback",
                "WARN",
                f"{storage_error_count} dataset(s) hit storage errors before fallback.",
                payload,
            )
        if fallback_count:
            return self.check(
                "Data source fallback",
                "WARN",
                f"{fallback_count} dataset(s) used Sheets fallback in this API process.",
                payload,
            )
        if payload.get("count", 0):
            return self.check("Data source fallback", "OK", "All observed dataset reads used storage or empty storage.", payload)
        return self.check("Data source fallback", "WARN", "No dataset reads observed yet.", payload)

    def _kr_rank_health_payload(self, max_age_days: int) -> dict:
        if self.kr_rank_health_payload_loader is not None:
            return self.kr_rank_health_payload_loader(max_age_days)
        return self.kr_rank_health_payload(max_age_days=max_age_days)


@dataclass(frozen=True)
class OpsHealthPayloadBuilder:
    settings: Any
    check: Callable[..., dict]
    utc_now: Callable[[], datetime]
    ready_payload: Callable[[], dict]
    storage_config_checks_loader: Callable[[], list[dict]]
    macro_check: Callable[[], dict]
    core_dataset_checks: Callable[[], list[dict]]
    data_source_check: Callable[[], dict]
    data_quality_check: Callable[[], dict]
    kr_rank_health_check: Callable[[], dict]
    factor_quality_check: Callable[[], dict]
    research_health_payload: Callable[..., dict]
    cache_warm_state_payload: Callable[[], dict] | None = None

    def storage_config_checks(self) -> list[dict]:
        checks = []
        has_google_key_json = bool(str(self.settings.google_key_json or "").strip())
        has_google_key_path = self.settings.google_key_path.exists()
        has_google_key_file = self.settings.google_key_path.is_file()
        local_parquet_available = bool(self.settings.enable_parquet and self.settings.data_lake_dir.exists())
        if has_google_key_file:
            google_message = "Google key file exists."
        elif has_google_key_json:
            google_message = "Google key JSON secret is configured."
        elif local_parquet_available:
            google_message = "Google key is not configured as a file; local Parquet mode is active."
        elif has_google_key_path:
            google_message = "Google key path exists but is not a file."
        else:
            google_message = "Google key file/JSON secret not found."
        checks.append(self.check(
            "Google key",
            "OK" if (has_google_key_file or has_google_key_json or local_parquet_available) else "WARN",
            google_message,
            {
                "path": str(self.settings.google_key_path),
                "path_exists": has_google_key_path,
                "path_is_file": has_google_key_file,
                "json_secret": has_google_key_json,
                "local_parquet_available": local_parquet_available,
            },
        ))
        parquet_exists = self.settings.data_lake_dir.exists()
        if self.settings.enable_parquet:
            checks.append(self.check(
                "Parquet lake",
                "OK" if parquet_exists else "WARN",
                "Data lake directory exists." if parquet_exists else "Data lake directory not found yet.",
                {"enabled": True, "path": str(self.settings.data_lake_dir)},
            ))
        else:
            checks.append(self.check(
                "Parquet lake",
                "OK",
                "Parquet is disabled for this runtime.",
                {"enabled": False, "path": str(self.settings.data_lake_dir), "exists": parquet_exists},
            ))
        local_storage_mode = bool(
            self.settings.enable_parquet and str(self.settings.api_env).lower() not in {"prod", "production"}
        )
        postgres_ok = bool(self.settings.enable_postgres or local_storage_mode)
        postgres_message = (
            "PostgreSQL is enabled."
            if self.settings.enable_postgres
            else "PostgreSQL is disabled; local Parquet mode is active."
            if local_storage_mode
            else "PostgreSQL is disabled; using Sheets/Parquet fallback where available."
        )
        checks.append(self.check(
            "PostgreSQL mode",
            "OK" if postgres_ok else "WARN",
            postgres_message,
            {"enabled": self.settings.enable_postgres, "local_storage_mode": local_storage_mode},
        ))
        return checks

    def cache_warm_check(self) -> dict:
        if self.cache_warm_state_payload is None:
            return self.check("Cache warm", "WARN", "Cache warm state is not configured.", {})
        payload = self.cache_warm_state_payload()
        results = payload.get("results") if isinstance(payload.get("results"), list) else []
        result_names = {str(item.get("name") or "") for item in results if isinstance(item, dict)}
        failed = [item for item in results if isinstance(item, dict) and str(item.get("status") or "").upper() == "FAIL"]
        missing_mobile = sorted(MOBILE_CACHE_WARM_ENDPOINTS - result_names)
        running = bool(payload.get("running"))
        profile = str(payload.get("profile") or payload.get("reason") or "unknown")

        if running:
            return self.check("Cache warm", "WARN", f"profile={profile} is still running.", payload)
        if failed:
            return self.check("Cache warm", "WARN", f"profile={profile} has {len(failed)} failed job(s).", payload)
        if missing_mobile:
            detail = dict(payload)
            detail["missing_mobile"] = missing_mobile
            return self.check(
                "Cache warm",
                "WARN",
                f"profile={profile} missing mobile warm jobs: {', '.join(missing_mobile)}.",
                detail,
            )
        return self.check(
            "Cache warm",
            "OK",
            f"profile={profile} warmed {len(results)} job(s).",
            payload,
        )

    def ops_health_payload(self, max_research_age_hours: int = 84) -> dict:
        started = self.utc_now()
        checks: list[dict] = []

        ready_payload = self.ready_payload()
        ready_ok = ready_payload.get("status") == "ready"
        checks.append(self.check(
            "API readiness",
            "OK" if ready_ok else "FAIL",
            f"ready status={ready_payload.get('status')}",
            ready_payload,
        ))
        checks.append(self.cache_warm_check())
        checks.extend(self.storage_config_checks_loader())
        checks.append(self.macro_check())
        checks.extend(self.core_dataset_checks())
        checks.append(self.data_source_check())
        checks.append(self.data_quality_check())
        checks.append(self.kr_rank_health_check())
        checks.append(self.factor_quality_check())

        research = self.research_health_payload(max_age_hours=max_research_age_hours)
        research_status = "OK" if research.get("healthy") else ("FAIL" if research.get("status") == "FAILED" else "WARN")
        checks.append(self.check(
            "Research-quality job",
            research_status,
            str(research.get("reason") or research.get("status") or "unknown"),
            research,
        ))

        counts: dict[str, int] = {}
        for check in checks:
            status_text = str(check.get("status") or "UNKNOWN").upper()
            counts[status_text] = counts.get(status_text, 0) + 1

        if counts.get("FAIL", 0):
            overall = "FAIL"
        elif counts.get("WARN", 0):
            overall = "DEGRADED"
        else:
            overall = "OK"

        elapsed_ms = round((self.utc_now() - started).total_seconds() * 1000, 2)
        return {
            "healthy": overall == "OK",
            "status": overall,
            "generated_at": self.utc_now().isoformat(),
            "elapsed_ms": elapsed_ms,
            "status_counts": counts,
            "checks": checks,
            "config": {
                "postgres_enabled": self.settings.enable_postgres,
                "parquet_enabled": self.settings.enable_parquet,
                "pipeline_runner": self.settings.pipeline_runner,
                "spreadsheet_name": self.settings.spreadsheet_name,
            },
        }


@dataclass(frozen=True)
class OpsApiService:
    cached: Callable
    clear_runtime_state: Callable[[], None]
    pipeline_runs_payload: Callable[[int], dict]
    research_health_payload: Callable[[int], dict]
    data_quality_payload: Callable[[int | None], dict]
    data_source_payload: Callable[[], dict]
    performance_payload: Callable[[int], dict]
    cache_warm_state_payload: Callable[[], dict]
    cache_warm_payload: Callable[[], dict]
    ops_health_payload: Callable[[int], dict]

    def pipeline_runs(self, limit: int = 20) -> dict:
        safe_limit = max(1, min(int(limit or 20), 100))
        return self.cached(f"ops_pipeline_runs_{safe_limit}", lambda: self.pipeline_runs_payload(safe_limit), ttl=15)

    def research_health(self, max_age_hours: int = 84) -> dict:
        safe_hours = max(1, min(int(max_age_hours or 84), 240))
        return self.cached(f"ops_research_health_{safe_hours}", lambda: self.research_health_payload(safe_hours), ttl=15)

    def data_quality(self, max_age_days: int = 0) -> dict:
        safe_days = None if int(max_age_days or 0) <= 0 else max(1, min(int(max_age_days), 90))
        cache_key = safe_days if safe_days is not None else "default"
        return self.cached(f"ops_data_quality_{cache_key}", lambda: self.data_quality_payload(safe_days), ttl=15)

    def data_sources(self) -> dict:
        return self.data_source_payload()

    def performance(self, limit: int = 40) -> dict:
        safe_limit = max(1, min(int(limit or 40), 200))
        return self.performance_payload(safe_limit)

    def cache_warm(self) -> dict:
        return self.cache_warm_payload()

    def cache_warm_state(self) -> dict:
        return self.cache_warm_state_payload()

    def health(self, max_research_age_hours: int = 84) -> dict:
        safe_hours = max(1, min(int(max_research_age_hours or 84), 240))
        return self.cached(f"ops_health_{safe_hours}", lambda: self.ops_health_payload(safe_hours), ttl=15)

    def cache_clear(self) -> dict:
        self.clear_runtime_state()
        return {"cleared": True}
