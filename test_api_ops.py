"""Behavior tests for lightweight API operations telemetry."""

from __future__ import annotations

import unittest

from api import runtime_state
import api.server as server


class DataSourceTelemetryTests(unittest.TestCase):
    def setUp(self):
        runtime_state.clear_runtime_state()

    def tearDown(self):
        runtime_state.clear_runtime_state()

    def test_data_source_payload_summarizes_latest_dataset_reads(self):
        runtime_state.record_data_source("US_Final_Portfolio", "storage", market="US", rows=30)
        runtime_state.record_data_source("KR_Final_Portfolio", "sheet", market="KR", rows=30)

        payload = runtime_state.data_source_payload()

        self.assertEqual(payload["count"], 2)
        self.assertEqual(payload["summary"]["storage"], 1)
        self.assertEqual(payload["summary"]["sheet"], 1)
        self.assertEqual(payload["items"][0]["dataset"], "KR_Final_Portfolio")
        self.assertIn("last_seen_at", payload["items"][0])

    def test_data_source_check_warns_on_fallback(self):
        runtime_state.record_data_source("US_Final_Portfolio", "sheet", market="US", rows=30)

        check = server._data_source_check()

        self.assertEqual(check["status"], "WARN")
        self.assertEqual(check["name"], "Data source fallback")


if __name__ == "__main__":
    unittest.main()
