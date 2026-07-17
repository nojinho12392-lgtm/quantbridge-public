from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from fastapi import HTTPException, status


@dataclass(frozen=True)
class SystemPayloadBuilder:
    utc_now: Callable[[], datetime]
    auth_db: Callable[[], Any]
    repository: Callable[[], Any]
    enable_postgres: Callable[[], bool]

    def health_payload(self) -> dict:
        return {"status": "ok", "ts": self.utc_now().isoformat()}

    def ready_payload(self) -> dict:
        postgres_enabled = self.enable_postgres()
        checks: dict = {
            "api": "ok",
            "auth_store": "unknown",
            "sqlite": "unknown",
            "postgres": "unknown" if postgres_enabled else "disabled",
            "cache": {},
            "ts": self.utc_now().isoformat(),
        }

        try:
            with self.auth_db() as conn:
                conn.execute("SELECT 1").fetchone()
            checks["auth_store"] = "ok"
            checks["sqlite"] = "ok"
        except Exception as exc:
            message = f"error: {type(exc).__name__}: {exc}"
            checks["auth_store"] = message
            checks["sqlite"] = message

        if postgres_enabled:
            try:
                pg = self.repository().postgres
                pg.ensure_schema()
                with pg._connect() as conn:
                    identity_count = conn.execute("SELECT count(*) FROM company_identity").fetchone()[0]
                    price_count = conn.execute("SELECT count(*) FROM price_ohlcv").fetchone()[0]
                    price_metric_count = conn.execute("SELECT count(*) FROM latest_price_metrics").fetchone()[0]
                checks["postgres"] = "ok"
                checks["cache"] = {
                    "company_identity": identity_count,
                    "price_ohlcv": price_count,
                    "latest_price_metrics": price_metric_count,
                }
            except Exception as exc:
                checks["postgres"] = f"error: {type(exc).__name__}: {exc}"

        ok = checks["auth_store"] == "ok" and checks["postgres"] in {"ok", "disabled"}
        checks["status"] = "ready" if ok else "not_ready"
        return checks


@dataclass(frozen=True)
class SystemApiService:
    health_payload: Callable[[], dict]
    ready_payload: Callable[[], dict]

    def health(self) -> dict:
        return self.health_payload()

    def ready(self) -> dict:
        checks = self.ready_payload()
        if checks.get("status") != "ready":
            raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=checks)
        return checks
