from __future__ import annotations

import unittest
from unittest.mock import Mock, patch

from api import server


class NewsChangeFieldTests(unittest.TestCase):
    def test_public_news_item_strips_publisher_body_and_images(self):
        item = {
            "title": "Publisher original title that should not be redistributed",
            "summary": "Publisher summary body that should not be redistributed",
            "source": "Example News",
            "url": "https://example.com/article",
            "image_url": "https://example.com/image.jpg",
            "thumbnail": "https://example.com/thumb.jpg",
            "naver_link": "https://news.naver.com/article",
            "impact_label_ko": "긍정",
            "impact_scope": "stock",
            "ticker": "NVDA",
            "related_tickers": ["NVDA"],
        }

        public = server._news_public_item(item, "US")

        self.assertEqual(public["title"], "엔비디아 긍정 뉴스")
        self.assertEqual(public["summary"], "")
        self.assertEqual(public["image_url"], "")
        self.assertEqual(public["thumbnail"], "")
        self.assertEqual(public["content_mode"], "link_analysis")
        self.assertNotIn("naver_link", public)

    @patch("api.server._portfolio_daily_change_batch")
    @patch("api.server._portfolio_price_snapshot_batch")
    @patch("api.server._naver_kr_stock_change_batch")
    @patch("api.server._allow_api_external_fetch", return_value=True)
    def test_kr_news_uses_naver_daily_move_before_stored_snapshot(
        self,
        _allow_external,
        naver_changes,
        price_snapshots,
        stored_changes,
    ):
        naver_changes.return_value = {"000660.KS": (-0.0766, "최근장")}
        price_snapshots.return_value = {}
        stored_changes.return_value = {"000660.KS": (0.067, "오늘")}

        item = {
            "title": "SK하이닉스, 메모리 가격 부담에 하락",
            "summary": "하이닉스 주가가 장중 약세를 보였다.",
            "market": "KR",
            "impact_scope": "stock",
            "related_tickers": ["000660.KS"],
        }

        enriched = server._enrich_news_change_fields([item], "KR")[0]

        self.assertEqual(enriched["related_change_label"], "SK하이닉스")
        self.assertEqual(enriched["related_change_pct"], -0.0766)
        self.assertEqual(enriched["related_change_horizon"], "최근장")
        price_snapshots.assert_not_called()
        stored_changes.assert_not_called()

    @patch("api.server._news_default_market_change", return_value=("코스피", 0.012))
    @patch("api.server._portfolio_daily_change_batch", return_value={"000660.KS": (None, "")})
    @patch("api.server._portfolio_price_snapshot_batch", return_value={})
    @patch("api.server._naver_kr_stock_change_batch", return_value={})
    def test_stock_specific_news_does_not_fallback_to_market_index(
        self,
        _naver_changes,
        _price_snapshots,
        _stored_changes,
        market_change,
    ):
        item = {
            "title": "SK하이닉스, 실적 전망 하향",
            "summary": "하이닉스 목표가가 낮아졌다.",
            "market": "KR",
            "impact_scope": "stock",
            "related_tickers": ["000660.KS"],
        }

        enriched = server._enrich_news_change_fields([item], "KR")[0]

        self.assertNotIn("related_change_pct", enriched)
        market_change.assert_not_called()

    @patch("api.server._cached", side_effect=lambda _key, loader, ttl=None: loader())
    @patch("api.server.requests.get")
    def test_naver_kr_stock_change_batch_parses_realtime_change(self, get, _cached):
        response = Mock()
        response.status_code = 200
        response.json.return_value = {
            "datas": [{
                "itemCode": "123456",
                "stockExchangeType": {"code": "KQ"},
                "closePrice": "10,500",
                "compareToPreviousClosePrice": "-500",
                "fluctuationsRatio": "-4.55",
                "localTradedAt": "2026-05-15T15:30:00+09:00",
            }]
        }
        get.return_value = response

        changes = server._naver_kr_stock_change_batch(["123456.KS"])

        self.assertEqual(changes["123456.KS"][0], -0.0455)
        self.assertEqual(changes["123456.KQ"][0], -0.0455)
        self.assertIn(changes["123456.KS"][1], {"오늘", "최근장"})


if __name__ == "__main__":
    unittest.main()
