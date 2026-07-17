from __future__ import annotations

import threading
import time as _time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from typing import Callable

CacheWarmJob = tuple[str, Callable[[], object]]


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(tzinfo=None).isoformat()


class ApiCacheWarmer:
    """Runs background API cache warmups and exposes a small status payload."""

    def __init__(
        self,
        *,
        primary_jobs: Callable[[], list[CacheWarmJob]],
        startup_jobs: Callable[[], list[CacheWarmJob]] | None = None,
        stock_tickers: Callable[[], list[str]],
        etf_tickers: Callable[[], list[str]],
        stock_detail: Callable[[str], object],
        etf_detail: Callable[[str], object],
    ) -> None:
        self._primary_jobs = primary_jobs
        self._startup_jobs = startup_jobs
        self._stock_tickers = stock_tickers
        self._etf_tickers = etf_tickers
        self._stock_detail = stock_detail
        self._etf_detail = etf_detail
        self._lock = threading.RLock()
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="api-cache-warmer")
        self._state: dict = {
            "running": False,
            "reason": "",
            "profile": "",
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
                "profile": self._state.get("profile") or "",
                "last_started_at": self._state.get("last_started_at"),
                "last_finished_at": self._state.get("last_finished_at"),
                "elapsed_ms": self._state.get("elapsed_ms"),
                "results": list(self._state.get("results") or []),
            }

    def start(self, reason: str = "manual") -> dict:
        profile = self._profile_for_reason(reason)
        with self._lock:
            if self._state.get("running"):
                return self.state()
            self._state.update(
                {
                    "running": True,
                    "reason": reason,
                    "profile": profile,
                    "last_started_at": _utc_now_iso(),
                    "last_finished_at": None,
                    "elapsed_ms": None,
                    "results": [],
                }
            )
        self._executor.submit(self._run, profile)
        return self.state()

    def _profile_for_reason(self, reason: str) -> str:
        if reason == "startup" and self._startup_jobs is not None:
            return "startup"
        return "full"

    def _run(self, profile: str) -> None:
        started = _time.perf_counter()
        results = self._run_primary_jobs(profile)
        if profile == "full":
            results.extend(self._run_detail_jobs())
        results.sort(key=lambda item: str(item.get("name") or ""))
        with self._lock:
            self._state.update(
                {
                    "running": False,
                    "last_finished_at": _utc_now_iso(),
                    "elapsed_ms": round((_time.perf_counter() - started) * 1000, 1),
                    "results": results,
                }
            )

    def _run_primary_jobs(self, profile: str) -> list[dict]:
        jobs = self._startup_jobs() if profile == "startup" and self._startup_jobs is not None else self._primary_jobs()
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


class ApiCacheWarmJobFactory:
    """Builds the default cache warm job lists from the composed API services."""

    def __init__(
        self,
        *,
        portfolio_service: object,
        ranking_service: object,
        calendar_service: object,
        risk_service: object,
        market_service: object,
        news_service: object,
        etf_service: object,
        sector_service: object,
        sector_theme_order: list[str] | tuple[str, ...],
        stock_limit: int = 80,
        etf_limit: int = 30,
    ) -> None:
        self._portfolio_service = portfolio_service
        self._ranking_service = ranking_service
        self._calendar_service = calendar_service
        self._risk_service = risk_service
        self._market_service = market_service
        self._news_service = news_service
        self._etf_service = etf_service
        self._sector_service = sector_service
        self._sector_theme_order = list(sector_theme_order)
        self._stock_limit = stock_limit
        self._etf_limit = etf_limit

    def primary_jobs(self) -> list[CacheWarmJob]:
        jobs: list[CacheWarmJob] = [
            ("portfolio/us", lambda: self._portfolio_service.portfolio("us")),
            ("portfolio/kr", lambda: self._portfolio_service.portfolio("kr")),
            ("smallcap/us", lambda: self._ranking_service.smallcap("US")),
            ("smallcap/kr", lambda: self._ranking_service.smallcap("KR")),
            ("macro", lambda: self._calendar_service.macro()),
            ("earnings/us", lambda: self._calendar_service.earnings("us")),
            ("earnings/kr", lambda: self._calendar_service.earnings("kr")),
            (
                "calendar/earnings",
                lambda: self._calendar_service.calendar_earnings(market="ALL", days=180, limit=2000),
            ),
            ("signals/events", lambda: self._risk_service.signal_events(market="ALL", limit=120)),
            ("market/indices", lambda: self._market_service.market_indices()),
            ("market/indicators", lambda: self._market_service.market_indicators(category="all")),
            ("news/issues", lambda: self._news_service.news_issues(q="", market="ALL", limit=40)),
            ("etfs", lambda: self._etf_service.etfs(limit=500)),
            (
                "sectors/themes/summary:ALL:36",
                lambda: self._sector_service.sector_theme_summary(market="ALL", limit=36),
            ),
            (
                "sectors/themes/summary:US:36",
                lambda: self._sector_service.sector_theme_summary(market="US", limit=36),
            ),
            (
                "sectors/themes/summary:KR:36",
                lambda: self._sector_service.sector_theme_summary(market="KR", limit=36),
            ),
            (
                "sectors/themes:ALL:36",
                lambda: self._sector_service.sector_themes(market="ALL", limit=36, members=12),
            ),
            (
                "sectors/themes:US:36",
                lambda: self._sector_service.sector_themes(market="US", limit=36, members=12),
            ),
            (
                "sectors/themes:KR:36",
                lambda: self._sector_service.sector_themes(market="KR", limit=36, members=12),
            ),
            (
                "sectors/themes/summary:ALL",
                lambda: self._sector_service.sector_theme_summary(market="ALL", limit=72),
            ),
            (
                "sectors/themes/summary:US",
                lambda: self._sector_service.sector_theme_summary(market="US", limit=72),
            ),
            (
                "sectors/themes/summary:KR",
                lambda: self._sector_service.sector_theme_summary(market="KR", limit=72),
            ),
            (
                "sectors/themes:ALL",
                lambda: self._sector_service.sector_themes(market="ALL", limit=72, members=12),
            ),
            (
                "sectors/themes:US",
                lambda: self._sector_service.sector_themes(market="US", limit=72, members=12),
            ),
            (
                "sectors/themes:KR",
                lambda: self._sector_service.sector_themes(market="KR", limit=72, members=12),
            ),
        ]
        for market in ("ALL", "US", "KR"):
            for label in self._sector_detail_labels(market):
                jobs.append((
                    f"sectors/themes/detail:{market}:{label}",
                    lambda market=market, label=label: self._sector_service.sector_theme_detail(
                        label=label,
                        market=market,
                        members=40,
                    ),
                ))
        return jobs

    def startup_jobs(self) -> list[CacheWarmJob]:
        return [
            ("portfolio/us", lambda: self._portfolio_service.portfolio("us")),
            ("portfolio/kr", lambda: self._portfolio_service.portfolio("kr")),
            ("smallcap/us", lambda: self._ranking_service.smallcap("US")),
            ("smallcap/kr", lambda: self._ranking_service.smallcap("KR")),
            ("macro", lambda: self._calendar_service.macro()),
            ("earnings/us", lambda: self._calendar_service.earnings("us")),
            ("earnings/kr", lambda: self._calendar_service.earnings("kr")),
            (
                "calendar/earnings",
                lambda: self._calendar_service.calendar_earnings(market="ALL", days=180, limit=2000),
            ),
            ("signals/events", lambda: self._risk_service.signal_events(market="ALL", limit=120)),
            ("market/indices", lambda: self._market_service.market_indices()),
            ("market/indicators", lambda: self._market_service.market_indicators(category="all")),
            ("news/issues", lambda: self._news_service.news_issues(q="", market="ALL", limit=40)),
        ]

    def stock_tickers(self) -> list[str]:
        tickers: list[str] = []

        def add(ticker: object) -> None:
            clean = str(ticker or "").strip().upper()
            if clean and clean not in tickers:
                tickers.append(clean)

        for market in ("us", "kr"):
            try:
                for row in (self._portfolio_service.portfolio(market).get("stocks") or [])[:30]:
                    add(row.get("Ticker") or row.get("ticker"))
            except Exception:
                pass
        for market in ("US", "KR"):
            try:
                for row in (self._ranking_service.smallcap(market).get("stocks") or [])[:20]:
                    add(row.get("Ticker") or row.get("ticker"))
            except Exception:
                pass
        try:
            themes = self._sector_service.sector_themes(market="ALL", limit=24, members=12).get("items") or []
            for theme in themes:
                leader = theme.get("leader") if isinstance(theme, dict) else None
                if isinstance(leader, dict):
                    add(leader.get("Ticker"))
                for member in (theme.get("members") or [])[:3]:
                    if isinstance(member, dict):
                        add(member.get("Ticker"))
        except Exception:
            pass
        return tickers[:self._stock_limit]

    def etf_tickers(self) -> list[str]:
        tickers: list[str] = []
        try:
            items = self._etf_service.etfs(limit=80).get("items") or []
        except Exception:
            return tickers
        for item in items:
            if not isinstance(item, dict):
                continue
            ticker = str(item.get("Ticker") or item.get("ticker") or item.get("symbol") or "").strip().upper()
            if ticker and ticker not in tickers:
                tickers.append(ticker)
            if len(tickers) >= self._etf_limit:
                break
        return tickers

    def _sector_detail_labels(self, market: str, limit: int = 18) -> list[str]:
        try:
            payload = self._sector_service.sector_theme_summary(market=market, limit=72)
            labels = [
                str(item.get("label") or "").strip()
                for item in (payload.get("items") or [])
                if isinstance(item, dict) and str(item.get("label") or "").strip()
            ]
            return list(dict.fromkeys(labels))[:limit]
        except Exception:
            return [label for label in self._sector_theme_order[:limit] if label]
