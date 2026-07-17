from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
import math

from fastapi import HTTPException


@dataclass(frozen=True)
class ComparisonRecommendationBuilder:
    kr_code: Callable[[object], str]
    infer_market_from_ticker: Callable[[str], str]
    localized_company_name: Callable[[str, object, object], str]
    safe_float: Callable[[object], float | None]
    first_float: Callable[..., float | None]
    load_portfolio: Callable[[str], tuple[dict, list[dict]]]
    load_simple: Callable[[str, list[str]], list[dict]]
    utc_now_iso: Callable[[], str]

    def currency(self, ticker: str, market: str | None) -> str:
        return "KRW" if str(market or "").upper() == "KR" or self.kr_code(ticker) else "USD"

    def item(self, row: dict, source: str, market: str | None) -> dict:
        ticker = str(row.get("Ticker") or row.get("ticker") or "").strip().upper()
        item_market = str(row.get("Market") or market or self.infer_market_from_ticker(ticker)).strip().upper()
        score = self.first_float(row.get("Total_Score"), row.get("Final_Score"), row.get("Combined_Score"))
        return {
            "Ticker": ticker,
            "Name": self.localized_company_name(ticker, row.get("Name") or row.get("name") or ticker, item_market),
            "Market": item_market,
            "Sector": row.get("Sector") or ("SmallCap" if source == "SmallCap" else None),
            "Currency": self.currency(ticker, item_market),
            "Source": source,
            "Score_Value": score,
            "Score_Text": f"{score:.0f}점" if source == "SmallCap" and score is not None else f"{score:.3f}" if score is not None else "-",
            "Expected_Return": self.safe_float(row.get("Expected_Return")),
            "RevGrowth": self.safe_float(row.get("RevGrowth")),
            "ROIC": self.safe_float(row.get("ROIC")),
            "GrossMargin": self.safe_float(row.get("GrossMargin")),
            "MarketCap": self.safe_float(row.get("MarketCap")),
            "Weight(%)": self.safe_float(row.get("Weight(%)")),
            "FCF_Margin": self.safe_float(row.get("FCF_Margin")),
            "Volume_Surge": self.safe_float(row.get("Volume_Surge")),
            "Last_Updated": row.get("Last_Updated") or row.get("Price_Updated_At"),
        }

    def match_keys(self, ticker: str) -> set[str]:
        text = str(ticker or "").strip().upper()
        if not text:
            return set()
        keys = {text}
        code = self.kr_code(text)
        if code:
            keys.update({code, f"{code}.KS", f"{code}.KQ"})
        return keys

    def payload(self, ticker: str, market: str = "ALL", limit: int = 8) -> dict:
        safe_market = str(market or "ALL").upper()
        if safe_market not in {"ALL", "US", "KR"}:
            safe_market = "ALL"
        safe_limit = max(1, min(int(limit or 8), 20))
        anchor_keys = self.match_keys(ticker)
        markets = ["US", "KR"] if safe_market == "ALL" else [safe_market]

        items: list[dict] = []
        for current_market in markets:
            _, portfolio_rows = self.load_portfolio(f"{current_market}_Final_Portfolio")
            items.extend(self.item(row, "Portfolio", current_market) for row in portfolio_rows)
            small_rows = self.load_simple(
                f"{current_market}_SmallCap_Gems",
                ["Rank", "MarketCap", "Total_Score", "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin", "Volume_Surge"],
            )
            items.extend(self.item(row, "SmallCap", current_market) for row in small_rows)
            scored_rows = self.load_simple(
                f"{current_market}_Scored_Stocks",
                ["Rank", "MarketCap", "Total_Score", "Final_Score", "Combined_Score", "ROIC", "RevGrowth", "GrossMargin", "FCF_Margin"],
            )
            items.extend(self.item(row, "Score", current_market) for row in scored_rows[:120])

        distinct: dict[str, dict] = {}
        for item in items:
            key = next(iter(self.match_keys(item.get("Ticker") or "")), item.get("Ticker"))
            if key and key not in distinct:
                distinct[key] = item

        anchor = next((item for item in distinct.values() if self.match_keys(item.get("Ticker") or "") & anchor_keys), None)
        if anchor is None:
            anchor = {
                "Ticker": str(ticker or "").strip().upper(),
                "Name": str(ticker or "").strip().upper(),
                "Market": None if safe_market == "ALL" else safe_market,
                "Sector": None,
                "Source": "Anchor",
            }

        def match_score(item: dict) -> tuple[float, str]:
            score = 0.0
            reasons = []
            if item.get("Market") and item.get("Market") == anchor.get("Market"):
                score += 2
                reasons.append("같은 시장")
            if item.get("Sector") and item.get("Sector") == anchor.get("Sector"):
                score += 5
                reasons.append("같은 섹터")
            if item.get("Source") == "Portfolio":
                score += 3
                reasons.append("핵심 후보")
            if item.get("Source") == "SmallCap":
                score += 2
                reasons.append("스몰캡 대조")
            item_score = self.safe_float(item.get("Score_Value"))
            if item_score is not None:
                score += min(max(item_score, 0), 100) / 50
            anchor_cap = self.safe_float(anchor.get("MarketCap"))
            item_cap = self.safe_float(item.get("MarketCap"))
            if anchor_cap and item_cap and anchor_cap > 0 and item_cap > 0:
                cap_gap = abs(math.log(anchor_cap) - math.log(item_cap))
                if cap_gap < 1.0:
                    score += 2
                    reasons.append("비슷한 규모")
            if self.safe_float(item.get("RevGrowth")) is not None and self.safe_float(anchor.get("RevGrowth")) is not None:
                score += 1
                reasons.append("성장성 비교")
            return score, " · ".join(reasons[:3]) or "지표 비교"

        recommendations = []
        for item in distinct.values():
            if self.match_keys(item.get("Ticker") or "") & anchor_keys:
                continue
            score, reason = match_score(item)
            enriched = dict(item)
            enriched["Match_Score"] = score
            enriched["Recommendation_Reason"] = reason
            recommendations.append(enriched)
        recommendations.sort(key=lambda row: (-row.get("Match_Score", 0), str(row.get("Name") or "")))
        return {
            "anchor": anchor,
            "count": min(len(recommendations), safe_limit),
            "items": recommendations[:safe_limit],
            "generated_at": self.utc_now_iso(),
        }


@dataclass(frozen=True)
class SignalEventsPayloadBuilder:
    repository: Callable[[], object]
    df_to_records: Callable[[object], list[dict]]
    utc_now_iso: Callable[[], str]

    def payload(
        self,
        market: str = "ALL",
        tickers: str = "",
        kinds: str = "",
        limit: int = 100,
    ) -> dict:
        safe_market = str(market or "ALL").upper()
        if safe_market not in {"ALL", "US", "KR", "GLOBAL"}:
            safe_market = "ALL"
        requested_tickers = [
            ticker.strip().upper()
            for ticker in str(tickers or "").split(",")
            if ticker.strip()
        ]
        requested_kinds = [
            kind.strip()
            for kind in str(kinds or "").split(",")
            if kind.strip()
        ]
        safe_limit = max(1, min(int(limit or 100), 500))
        df = self.repository().read_signal_events(
            market=None if safe_market == "ALL" else safe_market,
            tickers=requested_tickers,
            kinds=requested_kinds,
            limit=safe_limit,
        )
        return {
            "market": safe_market,
            "count": int(len(df)),
            "generated_at": self.utc_now_iso(),
            "items": self.df_to_records(df) if not df.empty else [],
        }


@dataclass(frozen=True)
class RiskApiService:
    cached: Callable
    signal_events_payload: Callable[[str, str, str, int], dict]
    comparison_recommendation_payload: Callable[[str, str, int], dict]
    backtest_payload: Callable[[str, str], dict]
    risk_drift_payload: Callable[[], dict]
    portfolio_risk_payload: Callable[[str, int], dict]
    rebalance_payload: Callable[[str, int], dict]
    shadow_attribution_payload: Callable[[str, int], dict]
    industry_payload: Callable[[int], dict]
    order_flow_payload: Callable[[int], dict]

    def signal_events(self, market: str = "ALL", tickers: str = "", kinds: str = "", limit: int = 100) -> dict:
        cache_key = f"signal_events_{market}_{tickers}_{kinds}_{limit}"
        return self.cached(cache_key, lambda: self.signal_events_payload(market, tickers, kinds, limit), ttl=60)

    def comparison_recommendations(self, ticker: str, market: str = "ALL", limit: int = 8) -> dict:
        safe_ticker = str(ticker or "").strip().upper()
        if not safe_ticker:
            raise HTTPException(400, "ticker is required")
        cache_key = f"comparison_recommendations_{safe_ticker}_{market}_{limit}"
        return self.cached(cache_key, lambda: self.comparison_recommendation_payload(safe_ticker, market, limit), ttl=300)

    def backtest(self, market: str) -> dict:
        safe_market = self._market(market)
        return self.cached(
            f"backtest_{safe_market}",
            lambda: self.backtest_payload(f"{safe_market}_Backtest_Results", safe_market),
        )

    def smallcap_backtest(self, market: str) -> dict:
        safe_market = self._market(market)
        return self.cached(
            f"smallcap_backtest_{safe_market}",
            lambda: self.backtest_payload(f"{safe_market}_SmallCap_Backtest", safe_market),
        )

    def risk_drift(self) -> dict:
        return self.cached("risk_drift", self.risk_drift_payload)

    def portfolio_risk(self, market: str, limit: int = 30) -> dict:
        safe_market = self._market(market)
        safe_limit = max(1, min(int(limit or 30), 100))
        return self.cached(
            f"portfolio_risk_{safe_market}_{safe_limit}",
            lambda: self.portfolio_risk_payload(safe_market, safe_limit),
        )

    def rebalance_report(self, market: str, limit: int = 50) -> dict:
        safe_market = self._market(market)
        safe_limit = max(1, min(int(limit or 50), 200))
        return self.cached(
            f"rebalance_{safe_market}_{safe_limit}",
            lambda: self.rebalance_payload(safe_market, safe_limit),
        )

    def shadow_attribution(self, market: str = "ALL", limit: int = 50) -> dict:
        safe_market = str(market or "ALL").upper()
        if safe_market not in ("ALL", "US", "KR"):
            raise HTTPException(400, "market must be ALL, US, or KR")
        safe_limit = max(1, min(int(limit or 50), 200))
        return self.cached(
            f"shadow_attribution_{safe_market}_{safe_limit}",
            lambda: self.shadow_attribution_payload(safe_market, safe_limit),
        )

    def risk_industry(self, limit: int = 30) -> dict:
        safe_limit = max(1, min(int(limit or 30), 100))
        return self.cached(f"risk_industry_{safe_limit}", lambda: self.industry_payload(safe_limit))

    def risk_order_flow(self, limit: int = 30) -> dict:
        safe_limit = max(1, min(int(limit or 30), 100))
        return self.cached(f"risk_order_flow_{safe_limit}", lambda: self.order_flow_payload(safe_limit))

    @staticmethod
    def _market(market: str) -> str:
        safe_market = str(market or "").upper()
        if safe_market not in ("US", "KR"):
            raise HTTPException(400, "market must be US or KR")
        return safe_market
