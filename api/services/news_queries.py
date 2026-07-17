from __future__ import annotations

from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timezone
import html
import math
import re
from urllib.parse import urljoin

import requests


@dataclass(frozen=True)
class NewsQueryPlanner:
    NEWS_BREAKING_TERMS = (
        "속보", "급락", "급등", "폭락", "폭등", "쇼크", "위기", "리스크",
        "파산", "부도", "거래정지", "하한가", "상한가", "전쟁", "중동",
        "관세", "규제", "인하", "동결", "긴급", "surge", "plunge", "crash",
        "bankruptcy", "halt", "tariff", "risk",
    )

    market_indicators_payload: Callable[..., dict]
    safe_float: Callable[[object], float | None]
    cached: Callable[..., object]

    @staticmethod
    def strip_news_html(value: str | None) -> str:
        text = re.sub(r"<[^>]+>", "", str(value or ""))
        return html.unescape(text).strip()

    @staticmethod
    def default_news_query(market: str) -> str:
        market = str(market or "").upper()
        if market == "KR":
            return "국내증시 코스피 코스닥 삼성전자 SK하이닉스"
        if market == "US":
            return "뉴욕증시 나스닥 S&P500 미국 주식 엔비디아 테슬라 애플"
        return "증시 주식 실적 반도체 환율"

    @staticmethod
    def default_news_queries(market: str) -> list[str]:
        market = str(market or "ALL").upper()
        if market == "KR":
            return [
                "국내증시 코스피 코스닥 외국인 기관",
                "삼성전자 SK하이닉스 반도체 AI",
                "현대차 배터리 바이오 조선 방산",
                "코스닥 성장주 실적 수급",
                "원달러 환율 한국은행 금리 채권",
            ]
        if market == "US":
            return [
                "뉴욕증시 나스닥 S&P500 연준 금리",
                "엔비디아 테슬라 애플 마이크로소프트 실적",
                "미국 반도체 AI 클라우드 빅테크",
                "미국 채권 금리 달러 유가",
                "미국 ETF 성장주 배당주",
            ]
        return [
            "뉴욕증시 나스닥 S&P500 연준 금리",
            "엔비디아 테슬라 애플 마이크로소프트 실적",
            "국내증시 코스피 코스닥 외국인 기관",
            "삼성전자 SK하이닉스 반도체 AI",
            "환율 원달러 유가 금 채권",
            "ETF 배당 성장주 가치주",
        ]

    @staticmethod
    def discovery_news_queries(market: str) -> list[str]:
        market = str(market or "ALL").upper()
        if market == "KR":
            return [
                "국내증시 속보 급락 급등",
                "한국 증시 시장 충격 리스크",
                "국내 주요 기업 실적 급등락",
            ]
        if market == "US":
            return [
                "뉴욕증시 속보 급락 급등",
                "월가 시장 충격 연준 리스크",
                "미국 주요 기업 실적 급등락",
            ]
        return [
            "증시 속보 금융시장 급락 급등",
            "시장 충격 리스크 위기 금융",
            "주요 기업 실적 전망 급등락",
        ]

    @staticmethod
    def market_news_query_direction(label: str, change_pct: float) -> str:
        if any(term in label for term in ("VIX", "환율", "달러", "국채", "채권", "유가", "원유", "금", "비트코인")):
            return "상승" if change_pct > 0 else "하락"
        return "급등" if change_pct > 0 else "급락"

    @staticmethod
    def market_news_query_threshold(item: dict) -> float:
        label = str(item.get("label") or "")
        category = str(item.get("category") or "").lower()
        symbol = str(item.get("symbol") or "").upper()
        if "VIX" in label or symbol == "^VIX":
            return 0.04
        if category in {"crypto", "commodity"}:
            return 0.025
        if category == "bond" or "국채" in label or "채권" in label:
            return 0.012
        if "환율" in label or "달러" in label:
            return 0.008
        return 0.010

    @staticmethod
    def market_news_query_allowed(item: dict, market: str) -> bool:
        market = str(market or "ALL").upper()
        if market == "ALL":
            return True
        label = str(item.get("label") or "")
        region = str(item.get("region") or "").lower()
        symbol = str(item.get("symbol") or "").upper()
        domestic = (
            region == "domestic"
            or symbol in {"^KS11", "^KQ11"}
            or any(term in label for term in ("KOSPI", "KOSDAQ", "국고채", "회사채", "환율"))
        )
        if market == "KR":
            return domestic or "달러" in label or "원유" in label or "유가" in label
        if market == "US":
            return not domestic or symbol in {"KRW=X"}
        return True

    def dynamic_market_news_queries(self, market: str, limit: int = 4) -> list[str]:
        try:
            payload = self.market_indicators_payload(category="all", refresh=False)
        except Exception:
            return []
        rows = payload.get("items") if isinstance(payload, dict) else []
        if not isinstance(rows, list):
            return []

        candidates: list[tuple[float, str]] = []
        for item in rows:
            if not isinstance(item, dict) or not self.market_news_query_allowed(item, market):
                continue
            label = str(item.get("label") or "").strip()
            change_pct = self.safe_float(item.get("change_pct"))
            if change_pct is None or not math.isfinite(change_pct):
                continue
            if not label:
                continue
            threshold = self.market_news_query_threshold(item)
            if abs(change_pct) < threshold:
                continue
            direction = self.market_news_query_direction(label, change_pct)
            query = f"{label} {direction} 이유"
            candidates.append((abs(change_pct), query))

        output: list[str] = []
        seen: set[str] = set()
        for _, query in sorted(candidates, reverse=True):
            key = query.lower()
            if key in seen:
                continue
            seen.add(key)
            output.append(query)
            if len(output) >= limit:
                break
        return output

    def news_query_plan(self, query: str, market: str) -> list[str]:
        value = str(query or "").strip()
        market = str(market or "ALL").upper()
        default_values = {self.default_news_query(scope).strip().lower() for scope in ("ALL", "US", "KR")}
        if value and value.lower() not in default_values:
            return [value]
        queries = [
            *self.default_news_queries(market),
            *self.dynamic_market_news_queries(market, limit=3),
            *self.discovery_news_queries(market)[:2],
        ]
        output: list[str] = []
        seen: set[str] = set()
        for current in queries:
            key = current.strip().lower()
            if not key or key in seen:
                continue
            seen.add(key)
            output.append(current)
        return output[:10]

    def news_cache_query_key(self, query: str, market: str) -> str:
        value = str(query or "").strip().lower()
        default_values = {self.default_news_query(scope).strip().lower() for scope in ("ALL", "US", "KR")}
        if not value or value in default_values:
            return "default-link-analysis-v1"
        return f"link-analysis-v1:{value}"

    @staticmethod
    def news_market_matches(title: str, summary: str, market: str) -> bool:
        market = str(market or "ALL").upper()
        if market == "ALL":
            return True
        text = f"{title or ''} {summary or ''}".lower()
        if market == "US":
            excluded = [
                "삼성전자", "sk하이닉스", "코스피", "코스닥",
                "국내 증시", "한국 증시", "현대차",
            ]
            if any(term.lower() in text for term in excluded):
                return False
            included = [
                "미국", "뉴욕증시", "미 증시", "미증시", "나스닥", "s&p",
                "다우", "월가", "엔비디아", "nvidia", "테슬라", "애플",
                "apple", "마이크로소프트", "microsoft", "알파벳",
                "amazon", "아마존", "연준", "fed", "빅테크", "클라우드",
                "미국채", "미 국채", "달러", "유가", "ai",
            ]
            return any(term.lower() in text for term in included)
        if market == "KR":
            included = [
                "국내 증시", "한국 증시", "코스피", "코스닥", "삼성전자",
                "sk하이닉스", "현대차", "외국인", "기관", "한국거래소",
                "2차전지", "배터리", "바이오", "조선", "방산", "네이버",
                "카카오", "lg에너지솔루션", "한국은행", "원달러",
            ]
            return any(term.lower() in text for term in included)
        return True

    @staticmethod
    def news_dedupe_key(item: dict) -> str:
        url = str(item.get("url") or "").strip().lower()
        if url:
            return url
        return str(item.get("title") or "").strip().lower()

    @staticmethod
    def html_attr(tag: str, attr: str) -> str:
        match = re.search(
            rf"\b{re.escape(attr)}\s*=\s*(['\"])(.*?)\1",
            str(tag or ""),
            flags=re.IGNORECASE | re.DOTALL,
        )
        if not match:
            return ""
        return html.unescape(match.group(2).strip())

    @staticmethod
    def normalize_news_image_url(value: str, page_url: str) -> str:
        url = html.unescape(str(value or "").strip())
        if not url or url.startswith("data:"):
            return ""
        return urljoin(page_url, url)

    def extract_news_image_url(self, body: str, page_url: str) -> str:
        for tag in re.findall(r"<meta\b[^>]*>", body or "", flags=re.IGNORECASE | re.DOTALL):
            key = (self.html_attr(tag, "property") or self.html_attr(tag, "name")).lower()
            if key in {"og:image", "og:image:url", "twitter:image", "twitter:image:src", "thumbnail"}:
                image_url = self.normalize_news_image_url(self.html_attr(tag, "content"), page_url)
                if image_url:
                    return image_url

        for tag in re.findall(r"<link\b[^>]*>", body or "", flags=re.IGNORECASE | re.DOTALL):
            rel = self.html_attr(tag, "rel").lower()
            if "image_src" in rel:
                image_url = self.normalize_news_image_url(self.html_attr(tag, "href"), page_url)
                if image_url:
                    return image_url
        return ""

    def news_image_url_for_url(self, url: str) -> str:
        clean_url = str(url or "").strip()
        if not clean_url:
            return ""

        def load() -> str:
            try:
                response = requests.get(
                    clean_url,
                    headers={
                        "User-Agent": (
                            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
                        ),
                        "Accept": "text/html,application/xhtml+xml",
                    },
                    timeout=4,
                    allow_redirects=True,
                )
                if response.status_code >= 400:
                    return ""
                return self.extract_news_image_url(response.text[:220_000], response.url or clean_url)
            except Exception:
                return ""

        return str(self.cached(f"news_image_url_{abs(hash(clean_url))}", load, ttl=21_600))

    def enrich_news_image_fields(self, items: list[dict]) -> list[dict]:
        targets: list[tuple[int, list[str]]] = []
        for index, item in enumerate(items):
            if str(item.get("image_url") or "").strip():
                continue
            urls = [
                str(item.get("url") or "").strip(),
                str(item.get("naver_link") or "").strip(),
            ]
            urls = [url for url in dict.fromkeys(urls) if url]
            if urls:
                targets.append((index, urls))

        if not targets:
            return items

        def first_image_url(candidate_urls: list[str]) -> str:
            return next((image for image in (self.news_image_url_for_url(url) for url in candidate_urls) if image), "")

        enriched = [dict(item) for item in items]
        with ThreadPoolExecutor(max_workers=min(6, len(targets))) as executor:
            futures = {executor.submit(first_image_url, urls): index for index, urls in targets}
            for future in as_completed(futures):
                image_url = future.result()
                if image_url:
                    enriched[futures[future]]["image_url"] = image_url
        return enriched

    def round_robin_news_buckets(self, buckets: list[list[dict]], limit: int) -> list[dict]:
        output: list[dict] = []
        seen: set[str] = set()
        max_len = max((len(bucket) for bucket in buckets), default=0)
        for index in range(max_len):
            for bucket in buckets:
                if index >= len(bucket):
                    continue
                item = bucket[index]
                key = self.news_dedupe_key(item)
                if key in seen:
                    continue
                seen.add(key)
                output.append(item)
                if len(output) >= limit:
                    return output
        return output

    @staticmethod
    def news_published_datetime(item: dict) -> datetime | None:
        value = str(item.get("published_at") or "").strip()
        if not value:
            return None
        try:
            dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except Exception:
            return None
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc)

    def news_recency_score(self, item: dict) -> float:
        published = self.news_published_datetime(item)
        if published is None:
            return 0.0
        age_hours = max(0.0, (datetime.now(timezone.utc) - published).total_seconds() / 3600.0)
        if age_hours <= 2:
            return 0.7
        if age_hours <= 6:
            return 0.55
        if age_hours <= 24:
            return 0.35
        if age_hours <= 72:
            return 0.15
        return 0.0

    def news_breaking_score(self, item: dict) -> float:
        text = f"{item.get('title') or ''} {item.get('summary') or ''}".casefold()
        hits = sum(1 for term in self.NEWS_BREAKING_TERMS if term.casefold() in text)
        return min(0.9, hits * 0.28)

    @staticmethod
    def news_query_type_score(item: dict) -> float:
        query_type = str(item.get("query_type") or "").lower()
        if query_type == "dynamic":
            return 0.65
        if query_type == "discovery":
            return 0.42
        if query_type == "custom":
            return 0.25
        return 0.0

    def news_pre_importance_score(self, item: dict) -> float:
        kind = str(item.get("kind") or "").lower()
        internal_boost = {
            "earnings": 0.45,
            "order_flow": 0.38,
            "smallcap": 0.28,
            "drift": 0.20,
            "macro": 0.18,
        }.get(kind, 0.0)
        return self.news_query_type_score(item) + self.news_breaking_score(item) + self.news_recency_score(item) + internal_boost

    def news_importance_score(self, item: dict) -> float:
        impact = self.safe_float(item.get("impact_score")) or 0.0
        change = abs(self.safe_float(item.get("related_change_pct")) or 0.0)
        confidence = str(item.get("impact_confidence") or "").lower()
        scope = str(item.get("impact_scope") or "").lower()
        confidence_boost = {"high": 0.35, "medium": 0.18, "low": 0.05}.get(confidence, 0.0)
        scope_boost = {"market": 0.25, "stock": 0.22, "sector": 0.18, "general": 0.0}.get(scope, 0.0)
        return (
            abs(impact) * 2.6
            + min(change * 18.0, 0.9)
            + self.news_query_type_score(item)
            + self.news_breaking_score(item)
            + self.news_recency_score(item)
            + confidence_boost
            + scope_boost
        )

    def diversify_news_items(self, items: list[dict], limit: int) -> list[dict]:
        if limit <= 0:
            return []
        per_bucket_limit = max(2, math.ceil(limit / 10))
        selected: list[dict] = []
        seen: set[str] = set()
        bucket_counts: dict[str, int] = {}

        def bucket_key(item: dict) -> str:
            query = str(item.get("query") or "").strip().lower()
            if query:
                return f"query:{query}"
            kind = str(item.get("kind") or "").strip().lower()
            return f"kind:{kind}" if kind else "misc"

        for item in items:
            dedupe_key = self.news_dedupe_key(item)
            if dedupe_key in seen:
                continue
            bucket = bucket_key(item)
            if bucket_counts.get(bucket, 0) >= per_bucket_limit:
                continue
            selected.append(item)
            seen.add(dedupe_key)
            bucket_counts[bucket] = bucket_counts.get(bucket, 0) + 1
            if len(selected) >= limit:
                return selected

        for item in items:
            dedupe_key = self.news_dedupe_key(item)
            if dedupe_key in seen:
                continue
            selected.append(item)
            seen.add(dedupe_key)
            if len(selected) >= limit:
                break
        return selected

    def news_query_type(self, query: str, market: str) -> str:
        if query in self.dynamic_market_news_queries(market, limit=10):
            return "dynamic"
        if query in self.discovery_news_queries(market):
            return "discovery"
        return "core"
