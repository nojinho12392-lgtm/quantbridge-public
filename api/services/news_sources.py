from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
import hashlib
from urllib.parse import urlparse, urlunparse
import xml.etree.ElementTree as ET

import requests


@dataclass(frozen=True)
class NewsSourceFetcher:
    RSS_NEWS_SOURCES = [
        ("Nasdaq", "https://www.nasdaq.com/feed/rssoutbound?category=Markets"),
    ]
    RSS_NEWS_MAX_AGE = timedelta(days=7)
    RSS_MARKET_KEYWORDS = (
        "stock", "stocks", "share", "shares", "market", "markets", "nasdaq", "s&p", "dow",
        "wall street", "futures", "treasury", "yield", "bond", "fed", "rate", "inflation",
        "earnings", "revenue", "guidance", "analyst", "sec filing", "etf", "oil", "gold",
        "dollar", "chip", "semiconductor", "ai", "openai", "nvidia", "tesla", "apple",
    )

    naver_client_id: str
    naver_client_secret: str
    strip_news_html: Callable[[str | None], str]
    news_market_matches: Callable[[str, str, str], bool]
    news_query_plan: Callable[[str, str], list[str]]
    news_query_type: Callable[[str, str], str]
    round_robin_news_buckets: Callable[[list[list[dict]], int], list[dict]]

    def naver_news_items_for_query(
        self,
        query: str,
        market: str,
        limit: int,
        query_index: int = 0,
        query_type: str = "core",
    ) -> list[dict]:
        if not self.naver_client_id or not self.naver_client_secret:
            return []
        display = max(1, min(limit * 3, 100))
        response = requests.get(
            "https://openapi.naver.com/v1/search/news.json",
            params={"query": query, "display": display, "sort": "date"},
            headers={
                "X-Naver-Client-Id": self.naver_client_id,
                "X-Naver-Client-Secret": self.naver_client_secret,
            },
            timeout=8,
        )
        response.raise_for_status()
        items = []
        for index, row in enumerate(response.json().get("items") or []):
            title = self.strip_news_html(row.get("title"))
            summary = self.strip_news_html(row.get("description"))
            if not self.news_market_matches(title, summary, market):
                continue
            published = str(row.get("pubDate") or "")
            try:
                published = parsedate_to_datetime(published).isoformat()
            except Exception:
                pass
            items.append({
                "id": f"naver-{query_index}-{index}-{row.get('link', '')}",
                "title": title,
                "summary": summary,
                "source": "Naver News",
                "url": row.get("originallink") or row.get("link") or "",
                "naver_link": row.get("link") or "",
                "published_at": published,
                "market": market,
                "ticker": "",
                "kind": "external_news",
                "query": query,
                "query_type": query_type,
            })
            if len(items) >= limit:
                break
        return items

    def naver_news_items(self, query: str, market: str, limit: int) -> list[dict]:
        queries = self.news_query_plan(query, market)
        if len(queries) <= 1:
            return self.naver_news_items_for_query(queries[0], market, limit, query_type="custom")
        per_query_limit = max(4, min(12, (limit // len(queries)) + 3))
        buckets = [
            self.naver_news_items_for_query(
                current_query,
                market,
                per_query_limit,
                query_index=index,
                query_type=self.news_query_type(current_query, market),
            )
            for index, current_query in enumerate(queries)
        ]
        return self.round_robin_news_buckets(buckets, limit)

    @staticmethod
    def xml_child_text(node: ET.Element, name: str) -> str:
        for child in list(node):
            if child.tag.split("}")[-1].lower() == name.lower():
                return str(child.text or "").strip()
        return ""

    @staticmethod
    def xml_child_attr(node: ET.Element, name: str, attr: str) -> str:
        for child in list(node):
            if child.tag.split("}")[-1].lower() == name.lower():
                value = child.attrib.get(attr)
                if value:
                    return str(value).strip()
        return ""

    def rss_datetime(self, value: str) -> str:
        parsed = self.rss_published_datetime(value)
        if parsed is not None:
            return parsed.astimezone(timezone.utc).replace(microsecond=0).isoformat()
        return datetime.now(timezone.utc).replace(microsecond=0).isoformat()

    @staticmethod
    def rss_published_datetime(value: str) -> datetime | None:
        text = str(value or "").strip()
        if not text:
            return None
        try:
            if "T" in text:
                parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
                if parsed.tzinfo is None:
                    parsed = parsed.replace(tzinfo=timezone.utc)
                return parsed.astimezone(timezone.utc)
        except Exception:
            pass
        try:
            parsed = parsedate_to_datetime(text)
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            return parsed.astimezone(timezone.utc)
        except Exception:
            return None

    def rss_news_is_fresh(self, published: datetime | None) -> bool:
        if published is None:
            return True
        now = datetime.now(timezone.utc)
        if published > now + timedelta(hours=6):
            return False
        return now - published <= self.RSS_NEWS_MAX_AGE

    def rss_news_is_market_relevant(self, title: str, summary: str, link: str) -> bool:
        combined = " ".join([title, summary, link]).lower()
        return any(keyword in combined for keyword in self.RSS_MARKET_KEYWORDS)

    @staticmethod
    def normalize_url_key(value: str) -> str:
        text = str(value or "").strip()
        if not text:
            return ""
        try:
            parsed = urlparse(text)
        except Exception:
            return text.lower()
        return urlunparse((
            parsed.scheme.lower(),
            parsed.netloc.lower(),
            parsed.path.rstrip("/"),
            "",
            "",
            "",
        )).lower()

    def rss_news_items_for_source(self, source: str, url: str, market: str, limit: int) -> list[dict]:
        response = requests.get(
            url,
            timeout=8,
            headers={"User-Agent": "Mozilla/5.0 (compatible; QubitNews/1.0)"},
        )
        response.raise_for_status()
        root = ET.fromstring(response.content)
        items: list[dict] = []
        for node in root.iter():
            if node.tag.split("}")[-1].lower() != "item":
                continue
            title = self.strip_news_html(self.xml_child_text(node, "title"))
            summary = self.strip_news_html(self.xml_child_text(node, "description"))
            link = self.xml_child_text(node, "link")
            if not title or not link:
                continue
            published_raw = self.xml_child_text(node, "pubDate")
            published = self.rss_published_datetime(published_raw)
            if not self.rss_news_is_fresh(published):
                continue
            if not self.rss_news_is_market_relevant(title, summary, link):
                continue
            if not self.news_market_matches(title, summary, market):
                continue
            digest = hashlib.sha1(f"{source}|{link}|{title}".encode("utf-8")).hexdigest()[:16]
            items.append({
                "id": f"rss-{digest}",
                "title": title,
                "summary": summary,
                "source": source,
                "url": link,
                "image_url": "",
                "published_at": self.rss_datetime(published_raw),
                "market": "US" if market == "ALL" else market,
                "ticker": "",
                "kind": "external_news",
                "query_type": "rss_fallback",
            })
            if len(items) >= limit:
                break
        return items

    def rss_news_items(self, market: str, limit: int) -> tuple[list[dict], str]:
        buckets: list[list[dict]] = []
        errors: list[str] = []
        per_source_limit = max(4, min(limit, 16))
        for source, url in self.RSS_NEWS_SOURCES:
            try:
                rows = self.rss_news_items_for_source(source, url, market, per_source_limit)
                if rows:
                    buckets.append(rows)
            except Exception as exc:
                errors.append(f"{source}: {type(exc).__name__}: {exc}")
        rounded = self.round_robin_news_buckets(buckets, limit * 2)
        unique: list[dict] = []
        seen: set[str] = set()
        for item in rounded:
            key = self.normalize_url_key(str(item.get("url") or "")) or str(item.get("title") or "").strip().lower()
            if not key or key in seen:
                continue
            seen.add(key)
            unique.append(item)
        unique.sort(
            key=lambda item: self.rss_published_datetime(str(item.get("published_at") or ""))
            or datetime.min.replace(tzinfo=timezone.utc),
            reverse=True,
        )
        return unique[:limit], "; ".join(errors)
