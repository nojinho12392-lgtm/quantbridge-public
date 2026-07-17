from __future__ import annotations

import unittest
from unittest.mock import patch

import tools.check_staging_status as staging_status


class StagingStatusCacheWarmTests(unittest.TestCase):
    def test_ready_check_retries_cold_start_timeout(self):
        calls = 0

        def fake_request_json(_base_url, _path, _timeout):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise TimeoutError("cold start")
            return 0.1, {"status": "ready", "cache": "memory"}

        with patch.object(staging_status, "request_json", side_effect=fake_request_json), \
                patch.object(staging_status.time, "sleep", return_value=None):
            check = staging_status.ready_check(
                "https://api.test",
                timeout=3,
                wait_seconds=60,
                interval=0,
            )

        self.assertEqual("OK", check["status"])
        self.assertEqual(2, check["attempts"])

    def test_cache_warm_check_passes_when_mobile_endpoints_are_warm(self):
        payload = {
            "running": False,
            "profile": "startup",
            "reason": "startup",
            "elapsed_ms": 1234.5,
            "results": [
                {"name": "portfolio/us", "status": "OK"},
                {"name": "portfolio/kr", "status": "OK"},
                {"name": "smallcap/us", "status": "OK"},
                {"name": "smallcap/kr", "status": "OK"},
                {"name": "macro", "status": "OK"},
            ],
        }
        with patch.object(staging_status, "request_json", return_value=(0.2, payload)):
            check = staging_status.cache_warm_check("https://api.test", timeout=3)

        self.assertEqual("OK", check["status"])
        self.assertIn("profile=startup", check["message"])
        self.assertEqual([], check["detail"]["missing_mobile"])

    def test_ops_health_check_message_names_problem_checks(self):
        payload = {
            "healthy": False,
            "status": "FAIL",
            "status_counts": {"OK": 1, "FAIL": 1},
            "checks": [
                {"name": "API readiness", "status": "OK"},
                {"name": "Cache warm", "status": "FAIL"},
            ],
        }
        with patch.object(staging_status, "request_json", return_value=(0.2, payload)):
            check = staging_status.ops_health_check(
                "https://api.test",
                timeout=3,
                max_research_age_hours=84,
            )

        self.assertEqual("FAIL", check["status"])
        self.assertIn("issues=Cache warm:FAIL", check["message"])

    def test_cache_warm_check_warns_when_mobile_endpoint_is_missing(self):
        payload = {
            "running": False,
            "profile": "startup",
            "results": [
                {"name": "portfolio/us", "status": "OK"},
                {"name": "portfolio/kr", "status": "OK"},
                {"name": "smallcap/us", "status": "OK"},
                {"name": "macro", "status": "OK"},
            ],
        }
        with patch.object(staging_status, "request_json", return_value=(0.2, payload)):
            check = staging_status.cache_warm_check("https://api.test", timeout=3)

        self.assertEqual("WARN", check["status"])
        self.assertEqual(["smallcap/kr"], check["detail"]["missing_mobile"])

    def test_cache_warm_check_warns_while_warm_is_running(self):
        payload = {
            "running": True,
            "profile": "startup",
            "results": [],
        }
        with patch.object(staging_status, "request_json", return_value=(0.2, payload)):
            check = staging_status.cache_warm_check("https://api.test", timeout=3)

        self.assertEqual("WARN", check["status"])
        self.assertIn("running", check["message"])


if __name__ == "__main__":
    unittest.main()
