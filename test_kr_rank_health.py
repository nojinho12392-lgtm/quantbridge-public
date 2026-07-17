import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

from quantbridge.storage.parquet import ParquetLake
from tools.check_kr_rank_health import check_health


def _records(count: int, snapshot_date: str) -> list[dict]:
    return [
        {
            "Rank": idx + 1,
            "Ticker": f"{idx:06d}.KS",
            "Name": f"Stock {idx}",
            "Market": "KR",
            "Final_Score": 1.0 - idx / 100.0,
            "Business_Quality_Score": 0.6,
            "Investability_Score": 0.55,
            "Quality_Category": "QARP Candidate",
            "snapshot_date": snapshot_date,
        }
        for idx in range(count)
    ]


def _portfolio_records(snapshot_date: str) -> list[dict]:
    return [
        {
            "Rank": 1,
            "Ticker": "005930.KS",
            "Name": "Samsung",
            "Market": "KR",
            "Weight(%)": 1.0,
            "Total_Score": 0.8,
            "Last_Updated": snapshot_date,
            "snapshot_date": snapshot_date,
        }
    ]


def _smallcap_records(snapshot_date: str) -> list[dict]:
    return [
        {
            "Rank": 1,
            "Ticker": "123456.KQ",
            "Name": "Small",
            "Market": "KR",
            "Data_Confidence": 0.3,
            "Total_Score": 50,
            "Last_Updated": snapshot_date,
            "snapshot_date": snapshot_date,
        }
    ]


def _write_fresh_companions(root: Path, snapshot_date: str) -> None:
    lake = ParquetLake(root / "data_lake")
    lake.write_records("KR_Final_Portfolio", _portfolio_records(snapshot_date), market="KR", snapshot_date=snapshot_date)
    lake.write_records("KR_SmallCap_Gems", _smallcap_records(snapshot_date), market="KR", snapshot_date=snapshot_date)


class KrRankHealthTests(unittest.TestCase):
    def test_health_ok_for_fresh_rank_snapshot(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            logs = root / "logs"
            logs.mkdir()
            today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
            ParquetLake(root / "data_lake").write_records(
                "KR_Scored_Stocks",
                _records(25, today),
                market="KR",
                snapshot_date=today,
            )
            _write_fresh_companions(root, today)

            report = check_health(root / "data_lake", logs, max_age_days=2, min_rows=20, include_launchd=False)

        self.assertTrue(report["healthy"])
        self.assertEqual(report["status"], "OK")
        self.assertEqual(report["rows"], 25)

    def test_health_can_read_repository_frames_when_local_lake_is_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            logs = root / "logs"
            logs.mkdir()
            today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
            frames = {
                "KR_Scored_Stocks": pd.DataFrame(_records(25, today)),
                "KR_Final_Portfolio": pd.DataFrame(_portfolio_records(today)),
                "KR_SmallCap_Gems": pd.DataFrame(_smallcap_records(today)),
            }

            report = check_health(
                root / "data_lake",
                logs,
                max_age_days=2,
                min_rows=20,
                include_launchd=False,
                repository_reader=lambda dataset, market: frames.get(dataset, pd.DataFrame()),
            )

        self.assertTrue(report["healthy"])
        self.assertEqual(report["status"], "OK")
        self.assertEqual(report["rows"], 25)

    def test_health_fails_when_required_columns_are_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            logs = root / "logs"
            logs.mkdir()
            today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
            records = _records(25, today)
            for row in records:
                row.pop("Investability_Score")
            ParquetLake(root / "data_lake").write_records(
                "KR_Scored_Stocks",
                records,
                market="KR",
                snapshot_date=today,
            )
            _write_fresh_companions(root, today)

            report = check_health(root / "data_lake", logs, max_age_days=2, min_rows=20, include_launchd=False)

        self.assertFalse(report["healthy"])
        self.assertEqual(report["status"], "FAIL")
        column_check = next(check for check in report["checks"] if check["name"] == "required columns")
        self.assertIn("Investability_Score", column_check["message"])

    def test_health_warns_on_error_like_log_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            logs = root / "logs"
            logs.mkdir()
            (logs / "kr_rank_local.err.log").write_text("Traceback: sample failure\n", encoding="utf-8")
            today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
            ParquetLake(root / "data_lake").write_records(
                "KR_Scored_Stocks",
                _records(25, today),
                market="KR",
                snapshot_date=today,
            )
            _write_fresh_companions(root, today)

            report = check_health(root / "data_lake", logs, max_age_days=2, min_rows=20, include_launchd=False)

        self.assertTrue(report["healthy"])
        self.assertEqual(report["status"], "WARN")
        log_check = next(check for check in report["checks"] if check["name"] == "ranker logs")
        self.assertEqual(log_check["status"], "WARN")

    def test_health_fails_when_companion_outputs_are_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            logs = root / "logs"
            logs.mkdir()
            today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
            ParquetLake(root / "data_lake").write_records(
                "KR_Scored_Stocks",
                _records(25, today),
                market="KR",
                snapshot_date=today,
            )

            report = check_health(root / "data_lake", logs, max_age_days=2, min_rows=20, include_launchd=False)

        self.assertFalse(report["healthy"])
        self.assertEqual(report["status"], "FAIL")
        names = {check["name"] for check in report["checks"] if check["status"] == "FAIL"}
        self.assertIn("portfolio candidates", names)
        self.assertIn("smallcap candidates", names)


if __name__ == "__main__":
    unittest.main()
