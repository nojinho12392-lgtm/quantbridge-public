from __future__ import annotations

import threading
import time as _time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from typing import Callable

CacheWarmJob = tuple[str, Callable[[], object]]


class ApiCacheWarmer:
    """Runs background API cache warmups and exposes a small status payload."""

    def __init__(
        self,
        *,
        primary_jobs: Callable[[], list[CacheWarmJob]],
        stock_tickers: Callable[[], list[str]],
        etf_tickers: Callable[[], list[str]],
        stock_detail: Callable[[str], object],
        etf_detail: Callable[[str], object],
    ) -> None:
        self._primary_jobs = primary_jobs
        self._stock_tickers = stock_tickers
        self._etf_tickers = etf_tickers
        self._stock_detail = stock_detail
        self._etf_detail = etf_detail
        self._lock = threading.RLock()
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="api-cache-warmer")
        self._state: dict = {
            "running": False,
            "reason": "",
            "last_started_at": None,
            "last_finished_at": None,
            "elapsed_ms": None,
            "results": [],
        }

    def state(self) -> dict:
        with self._lock:
            return {
                "running": bool(self._state.get("running")),
                "reason": self._state.get("reason") or "",
                "last_started_at": self._state.get("last_started_at"),
                "last_finished_at": self._state.get("last_finished_at"),
                "elapsed_ms": self._state.get("elapsed_ms"),
                "results": list(self._state.get("results") or []),
            }

    def start(self, reason: str = "manual") -> dict:
        with self._lock:
            if self._state.get("running"):
                return self.state()
            self._state.update(
                {
                    "running": True,
                    "reason": reason,
                    "last_started_at": datetime.utcnow().isoformat(),
                    "last_finished_at": None,
                    "elapsed_ms": None,
                    "results": [],
                }
            )
        self._executor.submit(self._run)
        return self.state()

    def _run(self) -> None:
        started = _time.perf_counter()
        results = self._run_primary_jobs()
        results.extend(self._run_detail_jobs())
        results.sort(key=lambda item: str(item.get("name") or ""))
        with self._lock:
            self._state.update(
                {
                    "running": False,
                    "last_finished_at": datetime.utcnow().isoformat(),
                    "elapsed_ms": round((_time.perf_counter() - started) * 1000, 1),
                    "results": results,
                }
            )

    def _run_primary_jobs(self) -> list[dict]:
        jobs = self._primary_jobs()
        if not jobs:
            return []
        with ThreadPoolExecutor(max_workers=4, thread_name_prefix="api-cache-warm-job") as pool:
            futures = [pool.submit(_run_one_cache_warm_job, name, job) for name, job in jobs]
            return [future.result() for future in as_completed(futures)]

    def _run_detail_jobs(self) -> list[dict]:
        jobs: list[CacheWarmJob] = [
            (f"stock/{ticker}", lambda ticker=ticker: self._stock_detail(ticker))
            for ticker in self._stock_tickers()
        ]
        jobs.extend(
            (f"etf/{ticker}", lambda ticker=ticker: self._etf_detail(ticker))
            for ticker in self._etf_tickers()
        )
        if not jobs:
            return []
        with ThreadPoolExecutor(max_workers=6, thread_name_prefix="api-detail-warm-job") as pool:
            futures = [pool.submit(_run_one_cache_warm_job, name, job) for name, job in jobs]
            return [future.result() for future in as_completed(futures)]


def _run_one_cache_warm_job(name: str, job: Callable[[], object]) -> dict:
    started = _time.perf_counter()
    try:
        payload = job()
        return {
            "name": name,
            "status": "OK",
            "count": _payload_count(payload),
            "elapsed_ms": round((_time.perf_counter() - started) * 1000, 1),
        }
    except Exception as exc:
        return {
            "name": name,
            "status": "FAIL",
            "error": str(exc)[:240],
            "elapsed_ms": round((_time.perf_counter() - started) * 1000, 1),
        }


def _payload_count(payload: object) -> int | None:
    if isinstance(payload, list):
        return len(payload)
    if not isinstance(payload, dict):
        return None
    for key in ("stocks", "items", "themes", "events", "indices"):
        value = payload.get(key)
        if isinstance(value, list):
            return len(value)
    return None
