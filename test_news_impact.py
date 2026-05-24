from __future__ import annotations

import os
import unittest
from unittest.mock import Mock, patch

from api.services.news_impact import _llm_config, classify_news_impact, enrich_news_items


class NewsImpactTests(unittest.TestCase):
    def tearDown(self):
        _llm_config.cache_clear()

    def test_positive_stock_news_detects_related_ticker(self):
        result = classify_news_impact({
            "title": "삼성전자, AI 반도체 수요 증가에 실적 전망 상향",
            "summary": "HBM 공급 확대와 목표가 상향이 이어졌다.",
            "kind": "external_news",
        })

        self.assertEqual(result["impact_label"], "positive")
        self.assertIn("005930.KS", result["related_tickers"])
        self.assertEqual(result["impact_scope"], "stock")

    def test_negative_macro_news(self):
        result = classify_news_impact({
            "title": "미국 금리 상승과 달러 강세에 나스닥 하락",
            "summary": "연준 긴축 우려가 성장주에 부담으로 작용했다.",
            "kind": "external_news",
        })

        self.assertEqual(result["impact_label"], "negative")
        self.assertIn("금리", result["related_keywords"])
        self.assertEqual(result["impact_scope"], "market")

    def test_neutral_news_when_direction_is_weak(self):
        result = classify_news_impact({
            "title": "증시 주요 일정 공개",
            "summary": "이번 주 주요 경제지표 발표 일정이 공개됐다.",
            "kind": "external_news",
        })

        self.assertEqual(result["impact_label"], "neutral")
        self.assertEqual(result["impact_confidence"], "medium")

    @patch.dict(
        os.environ,
        {
            "GEMINI_API_KEY": "test-key",
            "NEWS_IMPACT_LLM_ENABLED": "true",
            "NEWS_IMPACT_LLM_MODEL": "gemini-2.5-flash",
            "NEWS_IMPACT_LLM_MAX_ITEMS": "1",
        },
        clear=True,
    )
    @patch("api.services.news_impact.requests.post")
    def test_gemini_enrichment_overrides_rule_result(self, post):
        _llm_config.cache_clear()
        response = Mock()
        response.raise_for_status.return_value = None
        response.json.return_value = {
            "candidates": [{
                "content": {
                    "parts": [{
                        "text": (
                            '{"items":[{"index":0,"impact_label":"negative",'
                            '"impact_score":-0.74,"impact_reason":"규제 리스크가 커져 단기 부담입니다.",'
                            '"impact_scope":"stock","impact_horizon":"단기",'
                            '"impact_confidence":"high","related_tickers":["TSLA"],'
                            '"related_keywords":["전기차"]}]}'
                        )
                    }]
                }
            }]
        }
        post.return_value = response

        items = enrich_news_items([{
            "title": "테슬라, 새 규제 조사 확대",
            "summary": "전기차 안전 조사 범위가 넓어졌다.",
            "kind": "external_news",
        }])

        self.assertEqual(items[0]["impact_label"], "negative")
        self.assertEqual(items[0]["impact_source"], "gemini")
        self.assertEqual(items[0]["impact_model"], "gemini-2.5-flash")
        self.assertIn("TSLA", items[0]["related_tickers"])
        post.assert_called_once()
        payload = post.call_args.kwargs["json"]
        self.assertEqual(payload["generationConfig"]["thinkingConfig"]["thinkingBudget"], 0)
        self.assertEqual(payload["generationConfig"]["responseMimeType"], "application/json")

    @patch.dict(
        os.environ,
        {
            "GEMINI_API_KEY": "test-key",
            "NEWS_IMPACT_LLM_ENABLED": "true",
        },
        clear=True,
    )
    @patch("api.services.news_impact.requests.post", side_effect=RuntimeError("quota"))
    def test_gemini_failure_falls_back_to_rules(self, _post):
        _llm_config.cache_clear()
        items = enrich_news_items([{
            "title": "삼성전자, 목표가 상향",
            "summary": "AI 반도체 수요 증가가 반영됐다.",
            "kind": "external_news",
        }])

        self.assertEqual(items[0]["impact_label"], "positive")
        self.assertEqual(items[0]["impact_source"], "rules")


if __name__ == "__main__":
    unittest.main()
