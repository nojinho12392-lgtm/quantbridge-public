from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass


@dataclass(frozen=True)
class InternalNewsBuilder:
    utc_now_iso: Callable[[], str]
    macro_payload: Callable[[], dict]
    load_simple: Callable[[str, list[str]], list[dict]]
    safe_float: Callable[[object], float | None]
    enrich_kr_company_identities: Callable[[list[dict]], list[dict]]
    risk_drift_payload: Callable[[], dict]
    order_flow_payload: Callable[[int], dict]

    def internal_issue_news(self, market: str = "ALL", limit: int = 20) -> list[dict]:
        market = str(market or "ALL").upper()
        now = self.utc_now_iso()
        items: list[dict] = []

        if market == "ALL":
            try:
                macro = self.macro_payload()
                regime = str(macro.get("Regime") or "NEUTRAL")
                items.append({
                    "id": "internal-macro-regime",
                    "title": f"시장 흐름은 {regime} 상태",
                    "summary": (
                        f"V {macro.get('US_V_Weight', '-')} · Q {macro.get('US_Q_Weight', '-')} · "
                        f"M {macro.get('US_M_Weight', '-')} 기준으로 팩터 비중을 점검합니다."
                    ),
                    "source": "",
                    "url": "",
                    "published_at": str(macro.get("Generated") or now),
                    "market": "GLOBAL",
                    "ticker": "",
                    "kind": "macro",
                })
            except Exception:
                pass

        markets = ["US", "KR"] if market == "ALL" else [market]
        for current_market in markets:
            try:
                earnings_rows = self.load_simple(
                    f"{current_market}_Earnings_Momentum",
                    ["Signal_Strength", "Surprise_Pct", "Return_Since", "Days_Since_Earnings", "Rank"],
                )
                for row in sorted(
                    earnings_rows,
                    key=lambda item: self.safe_float(item.get("Signal_Strength")) or -999,
                    reverse=True,
                )[:3]:
                    ticker = str(row.get("Ticker") or "")
                    name = str(row.get("Name") or ticker)
                    items.append({
                        "id": f"internal-earnings-{current_market}-{ticker}",
                        "title": f"{name}, 실적 모멘텀 상위 후보",
                        "summary": (
                            f"서프라이즈 {row.get('Surprise_Pct', '-')} · 발표 후 수익률 "
                            f"{row.get('Return_Since', '-')} · 시그널 {row.get('Signal_Strength', '-')}"
                        ),
                        "source": "",
                        "url": "",
                        "published_at": now,
                        "market": current_market,
                        "ticker": ticker,
                        "kind": "earnings",
                    })
            except Exception:
                pass

            try:
                smallcap_rows = self.load_simple(
                    f"{current_market}_SmallCap_Gems",
                    ["Rank", "Total_Score", "RevGrowth", "Volume_Surge", "MarketCap"],
                )
                if current_market == "KR":
                    smallcap_rows = self.enrich_kr_company_identities(smallcap_rows)
                for row in sorted(
                    smallcap_rows,
                    key=lambda item: self.safe_float(item.get("Total_Score")) or -999,
                    reverse=True,
                )[:3]:
                    ticker = str(row.get("Ticker") or "")
                    name = str(row.get("Name") or ticker)
                    items.append({
                        "id": f"internal-smallcap-{current_market}-{ticker}",
                        "title": f"{name}, SmallCap 점수 상위",
                        "summary": (
                            f"총점 {row.get('Total_Score', '-')} · 성장 {row.get('RevGrowth', '-')} · "
                            f"거래량 {row.get('Volume_Surge', '-')}"
                        ),
                        "source": "",
                        "url": "",
                        "published_at": now,
                        "market": current_market,
                        "ticker": ticker,
                        "kind": "smallcap",
                    })
            except Exception:
                pass

        try:
            drift = self.risk_drift_payload().get("items") or []
            for row in drift[:5]:
                row_market = str(row.get("Market") or "")
                if market != "ALL" and row_market.upper() != market:
                    continue
                ticker = str(row.get("Ticker") or "")
                name = str(row.get("Name") or ticker)
                items.append({
                    "id": f"internal-drift-{row_market}-{ticker}",
                    "title": f"{name}, 리밸런싱 드리프트 점검",
                    "summary": (
                        f"상태 {row.get('Status') or row.get('Recommendation') or '-'} · "
                        f"드리프트 {row.get('Drift_Abs', '-')}"
                    ),
                    "source": "",
                    "url": "",
                    "published_at": now,
                    "market": row_market or "GLOBAL",
                    "ticker": ticker,
                    "kind": "drift",
                })
        except Exception:
            pass

        try:
            if market in {"ALL", "KR"}:
                for row in self.order_flow_payload(limit=5).get("items") or []:
                    ticker = str(row.get("Ticker") or "")
                    name = str(row.get("Name") or ticker)
                    items.append({
                        "id": f"internal-order-flow-{ticker}",
                        "title": f"{name}, 외국인+기관 동시 순매수",
                        "summary": (
                            f"{row.get('Consecutive_Days', '-')}일 연속 · 외국인 "
                            f"{row.get('Foreign_Net_Buy', '-')} · 기관 {row.get('Inst_Net_Buy', '-')}"
                        ),
                        "source": "",
                        "url": "",
                        "published_at": now,
                        "market": "KR",
                        "ticker": ticker,
                        "kind": "order_flow",
                    })
        except Exception:
            pass

        if not items:
            items.append({
                "id": "internal-news-ready",
                "title": "뉴스 피드 준비 완료",
                "summary": "NAVER_CLIENT_ID와 NAVER_CLIENT_SECRET을 서버 환경변수에 넣으면 네이버 뉴스 검색 결과가 이 화면에 함께 표시됩니다.",
                "source": "",
                "url": "",
                "published_at": now,
                "market": "GLOBAL",
                "ticker": "",
                "kind": "setup",
            })

        return items[:max(1, min(limit, 100))]
