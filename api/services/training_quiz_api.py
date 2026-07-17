from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
from math import isfinite
from random import Random
from typing import Any

from fastapi import HTTPException


def _utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _clean_float(value: object) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if isfinite(number) else None


def _fallback_points(total_return: float) -> list[dict]:
    anchors = [0.0, 0.03, -0.01, 0.08, 0.14, 0.19, 0.24, 0.31, 0.39, 0.52, 0.68, 0.82, 1.0]
    start_year = datetime.now(timezone.utc).year - 3
    return [
        {
            "date": f"{start_year + (index * 3) // 12:04d}-{((index * 3) % 12) + 1:02d}-01",
            "return_pct": round(total_return * progress, 4),
        }
        for index, progress in enumerate(anchors)
    ]


def _return_points_from_prices(records: list[dict], fallback_total_return: float) -> list[dict]:
    clean: list[tuple[str, float]] = []
    for record in records:
        date = str(record.get("date") or record.get("Date") or "").strip()
        close = _clean_float(record.get("close") or record.get("Close"))
        if date and close is not None and close > 0:
            clean.append((date[:10], close))
    clean.sort(key=lambda item: item[0])
    if len(clean) < 2:
        return _fallback_points(fallback_total_return)

    first_close = clean[0][1]
    if first_close <= 0:
        return _fallback_points(fallback_total_return)

    target_count = min(37, len(clean))
    stride = max(1, round((len(clean) - 1) / max(target_count - 1, 1)))
    sampled = clean[::stride]
    if sampled[-1] != clean[-1]:
        sampled.append(clean[-1])

    points = [
        {
            "date": date,
            "return_pct": round(close / first_close - 1.0, 4),
        }
        for date, close in sampled
    ]
    return points if len(points) >= 2 else _fallback_points(fallback_total_return)


@dataclass(frozen=True)
class TrainingQuizApiService:
    cached: Callable | None = None
    stock_detail: Callable[[str], dict] | None = None
    logo_url: Callable[[str, str], str] | None = None
    now_iso: Callable[[], str] = _utc_now_iso

    def blind_financial_quiz(
        self,
        *,
        quiz_id: str = "",
        market: str = "US",
        refresh: bool = False,
    ) -> dict:
        safe_market = str(market or "US").strip().upper()
        if safe_market not in {"US", "KR", "ALL"}:
            raise HTTPException(status_code=400, detail="market must be US, KR, or ALL")

        fixtures = [quiz for quiz in _QUIZ_FIXTURES if safe_market in {"ALL", quiz["market"]}]
        if not fixtures:
            raise HTTPException(status_code=404, detail="no quiz fixtures for market")

        def load() -> dict:
            quiz = self._select_fixture(fixtures, quiz_id)
            options = [self._build_option(option) for option in quiz["options"]]
            winner = max(options, key=lambda item: item["company"]["three_year_return_pct"] or -999.0)
            return {
                "id": quiz["id"],
                "title": quiz["title"],
                "prompt": quiz["prompt"],
                "market": quiz["market"],
                "as_of": quiz["as_of"],
                "source": quiz["source"],
                "generated_at": self.now_iso(),
                "correct_option_id": winner["id"],
                "answer_rule": "3년 주가 상승률이 더 높았던 기업",
                "options": options,
            }

        if refresh or self.cached is None:
            return load()
        cache_key = f"training_blind_quiz_{safe_market}_{quiz_id or 'daily'}"
        return self.cached(cache_key, load, ttl=300, stale_ttl=3600)

    def _select_fixture(self, fixtures: list[dict], quiz_id: str) -> dict:
        requested = str(quiz_id or "").strip()
        if requested:
            for quiz in fixtures:
                if quiz["id"] == requested:
                    return quiz
            raise HTTPException(status_code=404, detail="quiz_id not found")
        today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        return Random(today).choice(fixtures)

    def _build_option(self, option: dict) -> dict:
        company = dict(option["company"])
        ticker = str(company["ticker"]).strip().upper()
        market = str(company.get("market") or "US").upper()
        fallback_return = float(company.get("fallback_three_year_return_pct") or 0.0)
        prices = self._price_records(ticker)
        points = _return_points_from_prices(prices, fallback_return)
        total_return = points[-1]["return_pct"] if points else fallback_return
        company["logo_url"] = company.get("logo_url") or self._logo_url(ticker, market)
        company["price_points"] = points
        company["three_year_return_pct"] = round(float(total_return), 4)
        company.pop("fallback_three_year_return_pct", None)
        return {
            "id": option["id"],
            "blind_label": option["blind_label"],
            "thesis": option["thesis"],
            "metrics": option["metrics"],
            "company": company,
        }

    def _price_records(self, ticker: str) -> list[dict]:
        if self.stock_detail is None:
            return []
        try:
            payload = self.stock_detail(ticker)
        except Exception:
            return []
        records = payload.get("prices") if isinstance(payload, dict) else None
        return records if isinstance(records, list) else []

    def _logo_url(self, ticker: str, market: str) -> str:
        if self.logo_url is None:
            return ""
        try:
            return self.logo_url(ticker, market) or ""
        except Exception:
            return ""


_QUIZ_FIXTURES: list[dict[str, Any]] = [
    {
        "id": "us-ai-infra-001",
        "title": "AI 인프라 수익성 대결",
        "prompt": "두 기업 중 지난 3년 주가 상승률이 더 높았던 기업을 고르세요.",
        "market": "US",
        "as_of": "2026-05-28",
        "source": "MVP curated fundamentals + cached 3y price history",
        "options": [
            {
                "id": "x",
                "blind_label": "Company X",
                "thesis": "고성장, 고마진이지만 밸류에이션 부담이 큰 반도체 플랫폼",
                "company": {
                    "ticker": "NVDA",
                    "name": "NVIDIA",
                    "market": "US",
                    "currency": "USD",
                    "sector": "Semiconductors",
                    "fallback_three_year_return_pct": 6.85,
                },
                "metrics": [
                    {"label": "PER", "value": "50.8x", "tone": "warning"},
                    {"label": "PBR", "value": "44.2x", "tone": "warning"},
                    {"label": "ROE", "value": "86.7%", "tone": "positive"},
                    {"label": "부채비율", "value": "17.2%", "tone": "positive"},
                    {"label": "매출성장률", "value": "114.2%", "tone": "positive"},
                ],
            },
            {
                "id": "y",
                "blind_label": "Company Y",
                "thesis": "저평가처럼 보이지만 턴어라운드 확인이 필요한 반도체 제조 기업",
                "company": {
                    "ticker": "INTC",
                    "name": "Intel",
                    "market": "US",
                    "currency": "USD",
                    "sector": "Semiconductors",
                    "fallback_three_year_return_pct": -0.38,
                },
                "metrics": [
                    {"label": "PER", "value": "N/A", "tone": "neutral"},
                    {"label": "PBR", "value": "0.9x", "tone": "positive"},
                    {"label": "ROE", "value": "-3.1%", "tone": "negative"},
                    {"label": "부채비율", "value": "51.6%", "tone": "warning"},
                    {"label": "매출성장률", "value": "-2.1%", "tone": "negative"},
                ],
            },
        ],
    },
    {
        "id": "us-platform-quality-001",
        "title": "플랫폼 퀄리티 대결",
        "prompt": "비슷하게 우량해 보이는 두 기업 중 지난 3년 수익률 승자를 찾으세요.",
        "market": "US",
        "as_of": "2026-05-28",
        "source": "MVP curated fundamentals + cached 3y price history",
        "options": [
            {
                "id": "x",
                "blind_label": "Company X",
                "thesis": "높은 현금창출력과 생태계 락인을 가진 소비자 플랫폼",
                "company": {
                    "ticker": "AAPL",
                    "name": "Apple",
                    "market": "US",
                    "currency": "USD",
                    "sector": "Consumer Electronics",
                    "fallback_three_year_return_pct": 0.62,
                },
                "metrics": [
                    {"label": "PER", "value": "29.4x", "tone": "neutral"},
                    {"label": "PBR", "value": "45.0x", "tone": "warning"},
                    {"label": "ROE", "value": "154.0%", "tone": "positive"},
                    {"label": "부채비율", "value": "145.0%", "tone": "warning"},
                    {"label": "매출성장률", "value": "2.0%", "tone": "neutral"},
                ],
            },
            {
                "id": "y",
                "blind_label": "Company Y",
                "thesis": "클라우드와 AI 소프트웨어 레버리지가 강한 엔터프라이즈 플랫폼",
                "company": {
                    "ticker": "MSFT",
                    "name": "Microsoft",
                    "market": "US",
                    "currency": "USD",
                    "sector": "Software",
                    "fallback_three_year_return_pct": 0.95,
                },
                "metrics": [
                    {"label": "PER", "value": "34.1x", "tone": "neutral"},
                    {"label": "PBR", "value": "11.8x", "tone": "warning"},
                    {"label": "ROE", "value": "34.0%", "tone": "positive"},
                    {"label": "부채비율", "value": "32.0%", "tone": "positive"},
                    {"label": "매출성장률", "value": "15.7%", "tone": "positive"},
                ],
            },
        ],
    },
]
