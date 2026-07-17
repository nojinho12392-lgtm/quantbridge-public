import unittest

from api.services.training_quiz_api import TrainingQuizApiService


class TrainingQuizApiTests(unittest.TestCase):
    def test_blind_financial_quiz_response_shape(self):
        service = TrainingQuizApiService(
            stock_detail=lambda ticker: {
                "prices": [
                    {"date": "2023-01-01", "close": 100.0},
                    {"date": "2024-01-01", "close": 140.0},
                    {"date": "2025-01-01", "close": 170.0},
                    {"date": "2026-01-01", "close": 220.0 if ticker == "NVDA" else 95.0},
                ]
            },
            logo_url=lambda ticker, market: f"https://example.com/{market}/{ticker}.png",
            now_iso=lambda: "2026-05-28T00:00:00Z",
        )

        payload = service.blind_financial_quiz(quiz_id="us-ai-infra-001", market="US")

        self.assertEqual(payload["id"], "us-ai-infra-001")
        self.assertEqual(payload["correct_option_id"], "x")
        self.assertEqual(len(payload["options"]), 2)
        self.assertEqual(payload["options"][0]["blind_label"], "Company X")
        self.assertEqual(payload["options"][0]["company"]["ticker"], "NVDA")
        self.assertGreaterEqual(len(payload["options"][0]["company"]["price_points"]), 2)
        self.assertIn("logo_url", payload["options"][0]["company"])


if __name__ == "__main__":
    unittest.main()
