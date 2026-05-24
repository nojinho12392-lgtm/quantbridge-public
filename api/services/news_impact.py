from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
import json
import re
from typing import Any

import requests

from quantbridge.config import get_settings


GEMINI_GENERATE_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
VALID_IMPACT_LABELS = {"positive", "neutral", "negative"}
VALID_IMPACT_SCOPES = {"stock", "sector", "market", "general"}
VALID_CONFIDENCE = {"low", "medium", "high"}
VALID_NEWS_MARKETS = {"US", "KR", "GLOBAL"}


@dataclass(frozen=True)
class NewsSignal:
    terms: tuple[str, ...]
    weight: float
    reason: str


@dataclass(frozen=True)
class NewsImpactLLMConfig:
    enabled: bool
    api_key: str
    model: str
    max_items: int
    timeout_seconds: float


POSITIVE_SIGNALS: tuple[NewsSignal, ...] = (
    NewsSignal(("호실적", "실적 호조", "어닝 서프라이즈", "surprise", "beat"), 0.65, "실적 개선 단서"),
    NewsSignal(("상향", "목표가 상향", "upgrade", "raised", "buy rating"), 0.55, "전망 상향 단서"),
    NewsSignal(("수주", "계약", "공급", "partnership", "contract"), 0.45, "수주/계약 단서"),
    NewsSignal(("순매수", "외국인 매수", "기관 매수", "inflow"), 0.40, "수급 개선 단서"),
    NewsSignal(("반등", "상승", "강세", "급등", "rally", "surge", "jump"), 0.35, "가격 흐름 개선 단서"),
    NewsSignal(("완화", "인하", "dovish", "rate cut", "cut rates"), 0.35, "매크로 부담 완화 단서"),
    NewsSignal(("성장", "증가", "개선", "흑자전환", "record high"), 0.30, "성장/개선 단서"),
)

NEGATIVE_SIGNALS: tuple[NewsSignal, ...] = (
    NewsSignal(("실적 부진", "어닝 쇼크", "miss", "missed", "loss"), 0.65, "실적 악화 단서"),
    NewsSignal(("하향", "목표가 하향", "downgrade", "lowered", "sell rating"), 0.55, "전망 하향 단서"),
    NewsSignal(("규제", "조사", "소송", "제재", "lawsuit", "probe", "sanction"), 0.50, "규제/법적 리스크 단서"),
    NewsSignal(("순매도", "외국인 매도", "기관 매도", "outflow", "selloff"), 0.45, "수급 악화 단서"),
    NewsSignal(("급락", "하락", "약세", "폭락", "plunge", "drop", "slump"), 0.40, "가격 흐름 악화 단서"),
    NewsSignal(("긴축", "금리 상승", "고금리", "달러 강세", "hawkish", "higher yields"), 0.40, "금리/달러 부담 단서"),
    NewsSignal(("둔화", "감소", "적자", "침체", "부담", "tariff", "recession"), 0.35, "성장 둔화/비용 부담 단서"),
)

ENTITY_TERMS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("NVDA", ("엔비디아", "nvidia", "nvda")),
    ("TSLA", ("테슬라", "tesla", "tsla")),
    ("AAPL", ("애플", "apple", "aapl")),
    ("MSFT", ("마이크로소프트", "microsoft", "msft")),
    ("GOOGL", ("알파벳", "구글", "alphabet", "google", "googl")),
    ("AMZN", ("아마존", "amazon", "amzn")),
    ("META", ("메타", "meta platforms", "meta")),
    ("005930.KS", ("삼성전자", "samsung electronics")),
    ("000660.KS", ("sk하이닉스", "하이닉스", "sk hynix")),
    ("005380.KS", ("현대차", "현대자동차", "hyundai motor")),
)

SECTOR_TERMS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("반도체", ("반도체", "semiconductor", "chip", "hbm", "메모리")),
    ("AI", ("인공지능", "ai", "데이터센터", "data center")),
    ("전기차", ("전기차", "ev", "배터리", "2차전지")),
    ("바이오", ("바이오", "제약", "임상", "fda", "clinical")),
    ("금융", ("은행", "금융", "bank", "insurance")),
    ("에너지", ("유가", "원유", "oil", "energy")),
)

MACRO_TERMS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("금리", ("금리", "연준", "fed", "yield", "fomc")),
    ("환율", ("환율", "원화", "달러", "dollar", "fx")),
    ("시장", ("나스닥", "s&p", "코스피", "코스닥", "뉴욕증시", "증시")),
)

US_MARKET_TERMS: tuple[str, ...] = (
    "미국", "뉴욕증시", "미 증시", "미증시", "나스닥", "nasdaq", "s&p", "sp500", "s&p500",
    "다우", "dow", "월가", "연준", "fed", "fomc", "달러", "dollar", "엔비디아", "nvidia",
    "테슬라", "tesla", "애플", "apple", "마이크로소프트", "microsoft", "알파벳", "google",
    "아마존", "amazon", "메타", "meta",
)

KR_MARKET_TERMS: tuple[str, ...] = (
    "한국", "국내", "국내증시", "국내 증시", "한국증시", "한국 증시", "코스피", "kospi",
    "코스닥", "kosdaq", "원화", "원달러", "한국거래소", "삼성전자", "sk하이닉스",
    "삼성", "이재용", "하이닉스", "현대차", "현대자동차", "기관", "외국인", "금감원", "금융위",
)

US_PRIMARY_ENTITY_TERMS: tuple[str, ...] = (
    "엔비디아", "nvidia", "테슬라", "tesla", "애플", "apple", "마이크로소프트",
    "microsoft", "알파벳", "google", "구글", "아마존", "amazon", "메타", "meta",
)

KR_PRIMARY_ENTITY_TERMS: tuple[str, ...] = (
    "삼성전자", "삼성", "이재용", "sk하이닉스", "하이닉스", "현대차", "현대자동차",
    "lg에너지솔루션", "lg화학", "네이버", "카카오", "셀트리온", "기아",
)

KIND_HINTS: dict[str, tuple[float, str]] = {
    "earnings": (0.45, "실적 모멘텀 후보"),
    "smallcap": (0.30, "퀀트 점수 상위 후보"),
    "order_flow": (0.35, "외국인/기관 수급 개선"),
    "drift": (0.0, "리밸런싱 점검 이슈"),
    "macro": (0.0, "시장 흐름 점검 이슈"),
    "setup": (0.0, "설정 안내"),
}

LLM_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "index": {"type": "integer"},
                    "impact_label": {"type": "string", "enum": ["positive", "neutral", "negative"]},
                    "impact_score": {"type": "number"},
                    "impact_reason": {"type": "string"},
                    "impact_scope": {"type": "string", "enum": ["stock", "sector", "market", "general"]},
                    "impact_horizon": {"type": "string"},
                    "impact_confidence": {"type": "string", "enum": ["low", "medium", "high"]},
                    "market": {"type": "string", "enum": ["US", "KR", "GLOBAL"]},
                    "market_confidence": {"type": "string", "enum": ["low", "medium", "high"]},
                    "related_tickers": {"type": "array", "items": {"type": "string"}},
                    "related_keywords": {"type": "array", "items": {"type": "string"}},
                },
                "required": [
                    "index",
                    "impact_label",
                    "impact_score",
                    "impact_reason",
                    "impact_scope",
                    "impact_confidence",
                ],
            },
        }
    },
    "required": ["items"],
}


def enrich_news_item(item: dict[str, Any], market: str = "ALL") -> dict[str, Any]:
    enriched = dict(item)
    enriched.update(classify_news_impact(item, market=market))
    detected_market, market_confidence = classify_news_market(item, requested_market=market)
    current_market = str(enriched.get("market") or "").strip().upper()
    if str(enriched.get("kind") or "").strip().lower() == "external_news" or current_market in {"", "ALL"}:
        enriched["market"] = detected_market
    elif current_market in VALID_NEWS_MARKETS:
        enriched["market"] = current_market
    else:
        enriched["market"] = detected_market
    enriched["market_confidence"] = market_confidence
    enriched["market_source"] = "rules"
    return enriched


def enrich_news_items(items: list[dict[str, Any]], market: str = "ALL") -> list[dict[str, Any]]:
    enriched = [enrich_news_item(item, market=market) for item in items]
    return _enrich_with_gemini(enriched, market=market)


def classify_news_impact(item: dict[str, Any], market: str = "ALL") -> dict[str, Any]:
    title = str(item.get("title") or "")
    summary = str(item.get("summary") or "")
    kind = str(item.get("kind") or "").strip().lower()
    ticker = str(item.get("ticker") or "").strip().upper()
    text = f"{title} {summary}".casefold()

    score = 0.0
    positive_reasons: list[str] = []
    negative_reasons: list[str] = []

    if kind in KIND_HINTS:
        hint_score, hint_reason = KIND_HINTS[kind]
        score += hint_score
        if hint_score > 0:
            positive_reasons.append(hint_reason)

    for signal in POSITIVE_SIGNALS:
        if _contains_any(text, signal.terms):
            score += signal.weight
            positive_reasons.append(signal.reason)

    for signal in NEGATIVE_SIGNALS:
        if _contains_any(text, signal.terms):
            score -= signal.weight
            negative_reasons.append(signal.reason)

    related_tickers = _related_tickers(text)
    if ticker and ticker not in related_tickers:
        related_tickers.insert(0, ticker)

    related_keywords = _related_keywords(text)
    scope = _impact_scope(related_tickers, related_keywords)
    score = max(-1.0, min(1.0, score))

    if score >= 0.20:
        label = "positive"
    elif score <= -0.20:
        label = "negative"
    else:
        label = "neutral"

    confidence = _confidence(label, score, related_tickers, related_keywords, positive_reasons, negative_reasons)
    return {
        "impact_label": label,
        "impact_label_ko": _label_ko(label),
        "impact_score": round(score, 2),
        "impact_reason": _impact_reason(label, positive_reasons, negative_reasons, related_keywords),
        "impact_scope": scope,
        "impact_horizon": "단기",
        "impact_confidence": confidence,
        "related_tickers": related_tickers[:5],
        "related_keywords": related_keywords[:5],
        "impact_source": "rules",
    }


def classify_news_market(item: dict[str, Any], requested_market: str = "ALL") -> tuple[str, str]:
    existing = str(item.get("market") or "").strip().upper()
    kind = str(item.get("kind") or "").strip().lower()
    if kind != "external_news" and existing in VALID_NEWS_MARKETS:
        return existing, "high"

    requested = str(requested_market or "ALL").strip().upper()
    title = str(item.get("title") or "")
    summary = str(item.get("summary") or "")
    ticker = str(item.get("ticker") or "").strip().upper()
    text = f"{title} {summary}".casefold()

    us_score = _term_score(text, US_MARKET_TERMS)
    kr_score = _term_score(text, KR_MARKET_TERMS)
    if _contains_any(text, US_PRIMARY_ENTITY_TERMS):
        us_score += 2.0
    if _contains_any(text, KR_PRIMARY_ENTITY_TERMS):
        kr_score += 4.0

    for related in _related_tickers(text):
        if related.endswith((".KS", ".KQ")):
            kr_score += 3.0
        else:
            us_score += 3.0

    if ticker.endswith((".KS", ".KQ")) or re.fullmatch(r"\d{6}", ticker):
        kr_score += 4.0
    elif ticker:
        us_score += 2.0

    if max(us_score, kr_score) <= 0:
        if requested in {"US", "KR"}:
            return requested, "low"
        return "GLOBAL", "low"

    if us_score >= kr_score + 1.5:
        return "US", "high" if us_score >= 3.0 else "medium"
    if kr_score >= us_score + 1.5:
        return "KR", "high" if kr_score >= 3.0 else "medium"

    if requested in {"US", "KR"} and max(us_score, kr_score) < 3.0:
        return requested, "low"
    return "GLOBAL", "medium"


def _enrich_with_gemini(items: list[dict[str, Any]], market: str = "ALL") -> list[dict[str, Any]]:
    config = _llm_config()
    if not config.enabled or not config.api_key or config.max_items <= 0:
        return items

    candidates = [
        (index, item)
        for index, item in enumerate(items)
        if str(item.get("title") or item.get("summary") or "").strip()
    ][: config.max_items]
    if not candidates:
        return items

    try:
        payload = _request_gemini_classification(candidates, market=market, config=config)
    except Exception:
        return items

    if not payload:
        return items

    merged = list(items)
    for result in payload:
        if not isinstance(result, dict):
            continue
        try:
            index = int(result.get("index"))
        except (TypeError, ValueError):
            continue
        if 0 <= index < len(merged):
            merged[index] = _merge_llm_result(merged[index], result, config.model)
    return merged


@lru_cache(maxsize=1)
def _llm_config() -> NewsImpactLLMConfig:
    settings = get_settings()
    return NewsImpactLLMConfig(
        enabled=bool(settings.news_impact_llm_enabled),
        api_key=settings.gemini_api_key.strip(),
        model=settings.news_impact_llm_model.strip() or "gemini-2.5-flash",
        max_items=max(0, int(settings.news_impact_llm_max_items)),
        timeout_seconds=float(settings.news_impact_llm_timeout_seconds),
    )


def _request_gemini_classification(
    candidates: list[tuple[int, dict[str, Any]]],
    market: str,
    config: NewsImpactLLMConfig,
) -> list[dict[str, Any]]:
    url = GEMINI_GENERATE_URL.format(model=config.model)
    body = {
        "contents": [{"parts": [{"text": _gemini_prompt(candidates, market=market)}]}],
        "generationConfig": {
            "temperature": 0.1,
            "maxOutputTokens": 2048,
            "thinkingConfig": {"thinkingBudget": 0},
            "responseMimeType": "application/json",
        },
    }
    response = requests.post(
        url,
        headers={
            "Content-Type": "application/json",
            "x-goog-api-key": config.api_key,
        },
        json=body,
        timeout=config.timeout_seconds,
    )
    response.raise_for_status()
    text = _gemini_text(response.json())
    parsed = _parse_json_object(text)
    items = parsed.get("items") if isinstance(parsed, dict) else None
    return items if isinstance(items, list) else []


def _gemini_prompt(candidates: list[tuple[int, dict[str, Any]]], market: str) -> str:
    news_payload = []
    for index, item in candidates:
        news_payload.append({
            "index": index,
            "market_filter": market,
            "title": _short_text(item.get("title"), 220),
            "summary": _short_text(item.get("summary"), 420),
            "source": _short_text(item.get("source"), 80),
            "published_at": _short_text(item.get("published_at"), 40),
            "ticker": _short_text(item.get("ticker"), 20),
            "kind": _short_text(item.get("kind"), 40),
            "rule_label": item.get("impact_label"),
            "rule_score": item.get("impact_score"),
            "rule_reason": item.get("impact_reason"),
            "rule_market": item.get("market"),
            "rule_market_confidence": item.get("market_confidence"),
            "rule_related_tickers": item.get("related_tickers") or [],
            "rule_related_keywords": item.get("related_keywords") or [],
        })

    return (
        "You classify financial news impact for a Korean stock insight app.\n"
        "Use the headline and summary only. Do not assume private portfolio data.\n"
        "Judge likely short-term market impact, not whether the article sounds exciting.\n"
        "If the article says a stock fell because of a bad issue, classify negative. "
        "If it says demand, guidance, earnings, order flow, or policy conditions improved, classify positive. "
        "If direction is unclear or mixed, classify neutral.\n"
        "Also classify each article's primary market as US, KR, or GLOBAL. "
        "Use US for U.S. stocks, U.S. indices, Wall Street, Fed/FOMC, dollar-driven U.S. market stories, or U.S. company focus. "
        "Use KR for Korean stocks, KOSPI/KOSDAQ, Korea policy/regulation, won/KRW stories, or Korea-local flow. "
        "Use GLOBAL only when the headline is broad and neither US nor KR is the primary focus. "
        "If both markets appear, choose the market that is the main subject of the headline.\n"
        "Return concise Korean reasons, 70 Korean characters or less when possible.\n"
        "Use impact_score from -1.0 to 1.0. Use related_tickers only when directly mentioned.\n"
        "Return JSON only with this shape: {\"items\":[...]}\n"
        f"News items:\n{json.dumps(news_payload, ensure_ascii=False)}"
    )


def _gemini_text(response: dict[str, Any]) -> str:
    candidates = response.get("candidates")
    if not isinstance(candidates, list) or not candidates:
        return ""
    content = candidates[0].get("content") if isinstance(candidates[0], dict) else {}
    parts = content.get("parts") if isinstance(content, dict) else []
    if not isinstance(parts, list):
        return ""
    return "".join(str(part.get("text") or "") for part in parts if isinstance(part, dict))


def _parse_json_object(text: str) -> dict[str, Any]:
    stripped = text.strip()
    if not stripped:
        return {}
    try:
        parsed = json.loads(stripped)
        return parsed if isinstance(parsed, dict) else {}
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", stripped, flags=re.DOTALL)
        if not match:
            return {}
        try:
            parsed = json.loads(match.group(0))
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            return {}


def _merge_llm_result(item: dict[str, Any], result: dict[str, Any], model: str) -> dict[str, Any]:
    merged = dict(item)

    label = _valid_string(result.get("impact_label"), VALID_IMPACT_LABELS, merged.get("impact_label", "neutral"))
    score = _clamp_float(result.get("impact_score"), merged.get("impact_score", 0.0), -1.0, 1.0)
    scope = _valid_string(result.get("impact_scope"), VALID_IMPACT_SCOPES, merged.get("impact_scope", "general"))
    confidence = _valid_string(
        result.get("impact_confidence"),
        VALID_CONFIDENCE,
        merged.get("impact_confidence", "low"),
    )
    reason = _short_text(result.get("impact_reason"), 140) or str(merged.get("impact_reason") or "")
    horizon = _short_text(result.get("impact_horizon"), 20) or str(merged.get("impact_horizon") or "단기")
    market = _valid_upper_string(result.get("market"), VALID_NEWS_MARKETS, merged.get("market", "GLOBAL"))
    market_confidence = _valid_string(
        result.get("market_confidence"),
        VALID_CONFIDENCE,
        merged.get("market_confidence", "low"),
    )

    update = {
        "impact_label": label,
        "impact_label_ko": _label_ko(label),
        "impact_score": round(score, 2),
        "impact_reason": reason,
        "impact_scope": scope,
        "impact_horizon": horizon,
        "impact_confidence": confidence,
        "related_tickers": _clean_string_list(result.get("related_tickers"), merged.get("related_tickers"))[:5],
        "related_keywords": _clean_string_list(result.get("related_keywords"), merged.get("related_keywords"))[:5],
        "impact_source": "gemini",
        "impact_model": model,
        "market_confidence": market_confidence,
    }
    if str(merged.get("kind") or "").strip().lower() == "external_news" or str(merged.get("market") or "").upper() in {"", "ALL"}:
        update["market"] = market
        update["market_source"] = "gemini"
    merged.update(update)
    return merged


def _short_text(value: Any, limit: int) -> str:
    text = str(value or "").strip()
    if len(text) <= limit:
        return text
    return text[:limit].rstrip()


def _valid_string(value: Any, allowed: set[str], default: Any) -> str:
    text = str(value or "").strip().lower()
    return text if text in allowed else str(default or "").strip().lower()


def _valid_upper_string(value: Any, allowed: set[str], default: Any) -> str:
    text = str(value or "").strip().upper()
    if text in allowed:
        return text
    default_text = str(default or "").strip().upper()
    return default_text if default_text in allowed else "GLOBAL"


def _clamp_float(value: Any, default: Any, lower: float, upper: float) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        try:
            number = float(default)
        except (TypeError, ValueError):
            number = 0.0
    return max(lower, min(upper, number))


def _clean_string_list(value: Any, default: Any = None) -> list[str]:
    source = value if isinstance(value, list) else default
    if not isinstance(source, list):
        return []
    cleaned: list[str] = []
    for item in source:
        text = str(item or "").strip()
        if text and text not in cleaned:
            cleaned.append(text)
    return cleaned


def _contains_any(text: str, terms: tuple[str, ...]) -> bool:
    return any(term.casefold() in text for term in terms)


def _term_score(text: str, terms: tuple[str, ...]) -> float:
    score = 0.0
    for term in terms:
        if term.casefold() in text:
            score += 1.0
    return score


def _related_tickers(text: str) -> list[str]:
    related: list[str] = []
    for ticker, terms in ENTITY_TERMS:
        if _contains_any(text, terms) and ticker not in related:
            related.append(ticker)
    return related


def _related_keywords(text: str) -> list[str]:
    related: list[str] = []
    for label, terms in (*SECTOR_TERMS, *MACRO_TERMS):
        if _contains_any(text, terms) and label not in related:
            related.append(label)
    return related


def _impact_scope(related_tickers: list[str], related_keywords: list[str]) -> str:
    if related_tickers:
        return "stock"
    if any(label in related_keywords for label, _ in SECTOR_TERMS):
        return "sector"
    if related_keywords:
        return "market"
    return "general"


def _confidence(
    label: str,
    score: float,
    related_tickers: list[str],
    related_keywords: list[str],
    positive_reasons: list[str],
    negative_reasons: list[str],
) -> str:
    evidence_count = len(set(positive_reasons + negative_reasons))
    if label != "neutral" and related_tickers and (abs(score) >= 0.45 or evidence_count >= 2):
        return "high"
    if label != "neutral" and (abs(score) >= 0.35 or related_keywords or evidence_count >= 2):
        return "medium"
    if related_tickers or related_keywords:
        return "medium"
    return "low"


def _impact_reason(
    label: str,
    positive_reasons: list[str],
    negative_reasons: list[str],
    related_keywords: list[str],
) -> str:
    if label == "positive":
        reason = positive_reasons[0] if positive_reasons else "긍정 단서"
        return f"{reason}가 확인돼 단기 긍정으로 분류했습니다."
    if label == "negative":
        reason = negative_reasons[0] if negative_reasons else "부정 단서"
        return f"{reason}가 확인돼 단기 부담으로 분류했습니다."
    if related_keywords:
        return "관련 이슈는 잡혔지만 방향성 단서가 약해 중립으로 분류했습니다."
    return "방향성 단서가 약해 중립으로 분류했습니다."


def _label_ko(label: str) -> str:
    if label == "positive":
        return "긍정"
    if label == "negative":
        return "부정"
    return "중립"
