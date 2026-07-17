"""In-process runtime state for the QuantBridge API.

This module keeps volatile cache and data-source telemetry out of the FastAPI
route file. It intentionally remains process-local; production persistence
belongs in PostgreSQL/SQLite storage layers.
"""

from __future__ import annotations

import json
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, TypeVar


T = TypeVar("T")
DEFAULT_CACHE_TTL_SECONDS = 600
DEFAULT_STALE_CACHE_TTL_SECONDS = 24 * 60 * 60
_ROOT = Path(__file__).resolve().parent.parent
_DISK_CACHE_DIR = Path(os.environ.get("QUANT_API_CACHE_DIR", _ROOT / ".cache" / "api_runtime"))

_cache: dict[str, object] = {}
_cache_ts: dict[str, float] = {}
_data_source_events: dict[str, dict] = {}
_locks: dict[str, threading.Lock] = {}
_locks_guard = threading.Lock()
_refreshing: set[str] = set()
_refresh_executor = ThreadPoolExecutor(max_workers=4, thread_name_prefix="api-cache-refresh")
_perf_events: dict[str, dict] = {}


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(tzinfo=None).isoformat()


def cached(
    key: str,
    loader: Callable[[], T],
    ttl: int | None = None,
    stale_ttl: int | None = None,
) -> T:
    """Return a cached value with stale-while-revalidate protection.

    The API should feel fast even when a slow upstream source is temporarily
    unavailable. Fresh values are returned immediately. Expired-but-usable
    values are returned immediately while one background refresh is scheduled.
    Cold misses still block, but concurrent requests for the same key are
    coalesced behind a per-key lock.
    """

    now = time.time()
    max_age = DEFAULT_CACHE_TTL_SECONDS if ttl is None else ttl
    max_stale_age = DEFAULT_STALE_CACHE_TTL_SECONDS if stale_ttl is None else stale_ttl
    if key in _cache and now - _cache_ts.get(key, 0) < max_age:
        return _cache[key]  # type: ignore[return-value]

    disk_value = _read_disk_cache(key)
    if disk_value is not None:
        loaded_at, value = disk_value
        _cache[key] = value
        _cache_ts[key] = loaded_at
        age = now - loaded_at
        if age < max_age:
            return value  # type: ignore[return-value]

    stale_value = _stale_value(key, now, max_stale_age)
    if stale_value is not None:
        _refresh_in_background(key, loader)
        return stale_value  # type: ignore[return-value]

    lock = _lock_for(key)
    with lock:
        now = time.time()
        if key in _cache and now - _cache_ts.get(key, 0) < max_age:
            return _cache[key]  # type: ignore[return-value]
        disk_value = _read_disk_cache(key)
        if disk_value is not None:
            loaded_at, value = disk_value
            _cache[key] = value
            _cache_ts[key] = loaded_at
            if now - loaded_at < max_age:
                return value  # type: ignore[return-value]
        stale_value = _stale_value(key, now, max_stale_age)
        try:
            result = loader()
        except Exception:
            if stale_value is not None:
                return stale_value  # type: ignore[return-value]
            raise
        _store_cache(key, result)
        return result


def _lock_for(key: str) -> threading.Lock:
    with _locks_guard:
        lock = _locks.get(key)
        if lock is None:
            lock = threading.Lock()
            _locks[key] = lock
        return lock


def _stale_value(key: str, now: float, max_stale_age: int) -> object | None:
    if key in _cache and now - _cache_ts.get(key, 0) < max_stale_age:
        return _cache[key]
    disk_value = _read_disk_cache(key)
    if disk_value is None:
        return None
    loaded_at, value = disk_value
    if now - loaded_at >= max_stale_age:
        return None
    _cache[key] = value
    _cache_ts[key] = loaded_at
    return value


def _refresh_in_background(key: str, loader: Callable[[], T]) -> None:
    with _locks_guard:
        if key in _refreshing:
            return
        _refreshing.add(key)

    def refresh() -> None:
        try:
            result = loader()
            _store_cache(key, result)
        except Exception:
            pass
        finally:
            with _locks_guard:
                _refreshing.discard(key)

    _refresh_executor.submit(refresh)


def _store_cache(key: str, result: object) -> None:
    now = time.time()
    _cache[key] = result
    _cache_ts[key] = now
    _write_disk_cache(key, now, result)


def _cache_file(key: str) -> Path:
    safe = "".join(ch if ch.isalnum() or ch in ("-", "_", ".") else "_" for ch in key)
    if len(safe) > 160:
        import hashlib

        safe = hashlib.sha256(key.encode("utf-8")).hexdigest()
    return _DISK_CACHE_DIR / f"{safe}.json"


def _read_disk_cache(key: str) -> tuple[float, object] | None:
    path = _cache_file(key)
    if not path.exists():
        return None
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        loaded_at = float(payload.get("loaded_at") or 0)
        value = payload.get("value")
    except Exception:
        return None
    if loaded_at <= 0:
        return None
    return loaded_at, value


def _write_disk_cache(key: str, loaded_at: float, value: object) -> None:
    try:
        _DISK_CACHE_DIR.mkdir(parents=True, exist_ok=True)
        _cache_file(key).write_text(
            json.dumps(
                {"loaded_at": loaded_at, "value": value},
                ensure_ascii=False,
                separators=(",", ":"),
            ),
            encoding="utf-8",
        )
    except Exception:
        pass


def invalidate(key: str) -> None:
    _cache.pop(key, None)
    _cache_ts.pop(key, None)
    try:
        _cache_file(key).unlink(missing_ok=True)
    except Exception:
        pass


def clear_runtime_state() -> None:
    _cache.clear()
    _cache_ts.clear()
    _data_source_events.clear()
    _perf_events.clear()
    try:
        for path in _DISK_CACHE_DIR.glob("*.json"):
            path.unlink(missing_ok=True)
    except Exception:
        pass


def record_data_source(
    dataset: str,
    source: str,
    market: str | None = None,
    rows: int = 0,
    detail: str = "",
) -> None:
    key = f"{market or 'GLOBAL'}:{dataset}"
    previous = _data_source_events.get(key, {})
    count_key = f"{source}_count"
    _data_source_events[key] = {
        **previous,
        "dataset": dataset,
        "market": market or "GLOBAL",
        "last_source": source,
        "last_rows": int(rows or 0),
        "last_detail": detail,
        "last_seen_at": _utc_now_iso(),
        count_key: int(previous.get(count_key, 0)) + 1,
    }


def data_source_payload() -> dict:
    items = sorted(
        _data_source_events.values(),
        key=lambda item: (str(item.get("market")), str(item.get("dataset"))),
    )
    summary: dict[str, int] = {}
    for item in items:
        source = str(item.get("last_source") or "unknown")
        summary[source] = summary.get(source, 0) + 1
    return {
        "count": len(items),
        "summary": summary,
        "items": items,
        "generated_at": _utc_now_iso(),
    }


def record_api_timing(method: str, path: str, status_code: int, elapsed_ms: float) -> None:
    key = f"{method.upper()} {path}"
    previous = _perf_events.get(key, {})
    count = int(previous.get("count", 0)) + 1
    avg = ((float(previous.get("avg_ms", 0.0)) * (count - 1)) + elapsed_ms) / count
    worst = max(float(previous.get("max_ms", 0.0)), elapsed_ms)
    _perf_events[key] = {
        "route": key,
        "count": count,
        "avg_ms": round(avg, 1),
        "max_ms": round(worst, 1),
        "last_ms": round(elapsed_ms, 1),
        "last_status": int(status_code or 0),
        "last_seen_at": _utc_now_iso(),
    }


def performance_payload(limit: int = 40) -> dict:
    items = sorted(
        _perf_events.values(),
        key=lambda item: (float(item.get("last_ms") or 0), float(item.get("avg_ms") or 0)),
        reverse=True,
    )[: max(1, min(int(limit or 40), 200))]
    return {
        "count": len(items),
        "items": items,
        "generated_at": _utc_now_iso(),
    }
