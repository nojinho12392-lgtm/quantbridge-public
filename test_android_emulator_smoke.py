from __future__ import annotations

import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from tools.qa_android_emulator_smoke import require_absent_text, write_report


class AndroidEmulatorSmokeHelpersTests(unittest.TestCase):
    def test_require_absent_text_allows_ready_home_labels(self) -> None:
        root = ET.fromstring(
            """<hierarchy><node text="위험선호" content-desc="" /></hierarchy>"""
        )

        require_absent_text(root, "시장 상태 대기")

    def test_require_absent_text_rejects_pending_home_labels(self) -> None:
        root = ET.fromstring(
            """<hierarchy><node text="시장 상태 대기" content-desc="" /></hierarchy>"""
        )

        with self.assertRaisesRegex(RuntimeError, "Unexpected pending UI text"):
            require_absent_text(root, "시장 상태 대기")

    def test_write_report_records_run_context_and_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "home-ready.png").write_bytes(b"png")

            write_report(
                root,
                status="OK",
                base_url="https://api.test",
                profile="quick",
                serial="emulator-5554",
            )

            payload = json.loads((root / "report.json").read_text(encoding="utf-8"))

        self.assertEqual("OK", payload["status"])
        self.assertEqual("https://api.test", payload["base_url"])
        self.assertEqual("quick", payload["profile"])
        self.assertIn("/portfolio/us", payload["required_endpoints"])
        self.assertIn("home-ready.png", payload["artifacts"])


if __name__ == "__main__":
    unittest.main()
