from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass


@dataclass(frozen=True)
class NewsPublicFormatter:
    change_display_label: Callable[[str], str]

    @staticmethod
    def public_market_label(value: str, fallback: str = "ALL") -> str:
        market = str(value or fallback or "ALL").strip().upper()
        if market == "US":
            return "미국"
        if market == "KR":
            return "국내"
        if market == "GLOBAL":
            return "글로벌"
        return "시장"

    @staticmethod
    def public_impact_label(item: dict) -> str:
        label_ko = str(item.get("impact_label_ko") or "").strip()
        if label_ko:
            return label_ko
        label = str(item.get("impact_label") or "").strip().lower()
        if label == "positive":
            return "긍정"
        if label == "negative":
            return "부정"
        return "중립"

    def public_subject(self, item: dict, safe_market: str) -> str:
        related_tickers = [
            str(ticker or "").strip().upper()
            for ticker in (item.get("related_tickers") or [])
            if str(ticker or "").strip()
        ]
        ticker = str(item.get("ticker") or "").strip().upper()
        if ticker:
            related_tickers.insert(0, ticker)
        if related_tickers:
            return self.change_display_label(related_tickers[0])

        for keyword in item.get("related_keywords") or []:
            keyword_text = str(keyword or "").strip()
            if keyword_text:
                return keyword_text

        return self.public_market_label(str(item.get("market") or ""), safe_market)

    def public_title(self, item: dict, safe_market: str) -> str:
        impact = self.public_impact_label(item)
        scope = str(item.get("impact_scope") or "").strip().lower()
        subject = self.public_subject(item, safe_market)
        market = self.public_market_label(str(item.get("market") or ""), safe_market)
        if scope == "stock":
            return f"{subject} {impact} 뉴스"
        if scope == "sector":
            return f"{subject} 관련 섹터 {impact} 뉴스"
        if scope == "market":
            return f"{market} 시장 {impact} 뉴스"
        return f"{market} 주요 {impact} 뉴스"

    def public_item(self, item: dict, safe_market: str) -> dict:
        """Return only link metadata and Qubit-owned analysis, not publisher body assets."""
        output = dict(item)
        output["title"] = self.public_title(item, safe_market)
        output["summary"] = ""
        output["image_url"] = ""
        output["urlToImage"] = ""
        output["image"] = ""
        output["thumbnail"] = ""
        output["thumbnail_url"] = ""
        output["content_mode"] = "link_analysis"
        output.pop("naver_link", None)
        return output

    def public_items(self, items: list[dict], safe_market: str) -> list[dict]:
        return [self.public_item(item, safe_market) for item in items]
