"""Tests for local pipeline run tracking."""

from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from quantbridge.config import Settings
from quantbridge.storage import QuantRepository


RESEARCH_STEPS = [
    "pipeline/14_factor_ic_report.py",
    "pipeline/15_signal_quality_gate.py",
    "pipeline/16_factor_weight_policy.py",
    "pipeline/17_factor_policy_backtest.py",
]


class RepositoryPipelineRunTests(unittest.TestCase):
    def _repo(self, root: Path) -> QuantRepository:
        settings = Settings(
            data_lake_dir=root,
            enable_postgres=False,
            enable_parquet=True,
        )
        return QuantRepository(settings=settings)

    def test_local_pipeline_runs_merge_steps_and_results(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = self._repo(Path(tmp))

            repo.record_run("run-1", "local", "running", {"steps": RESEARCH_STEPS})
            repo.record_run(
                "run-1",
                "local",
                "success",
                {"results": [{"script": RESEARCH_STEPS[0], "elapsed_sec": 1.25}]},
            )

            runs = repo.read_pipeline_runs(limit=10)

        self.assertEqual(len(runs), 1)
        row = runs.iloc[0].to_dict()
        self.assertEqual(row["run_id"], "run-1")
        self.assertEqual(row["runner"], "local")
        self.assertEqual(row["status"], "success")
        self.assertTrue(row["started_at"])
        self.assertTrue(row["finished_at"])
        self.assertEqual(row["payload"]["steps"], RESEARCH_STEPS)
        self.assertEqual(row["payload"]["results"][0]["script"], RESEARCH_STEPS[0])

    def test_local_pipeline_runs_respect_limit(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = self._repo(Path(tmp))
            repo.record_run("run-1", "local", "success", {"steps": ["a"]})
            repo.record_run("run-2", "local", "success", {"steps": ["b"]})

            runs = repo.read_pipeline_runs(limit=1)

        self.assertEqual(len(runs), 1)

    def test_local_repository_reads_partitioned_latest_and_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = self._repo(Path(tmp))
            repo.write_records(
                "Factor_Score_Snapshots",
                [{"Snapshot_Date": "2026-01-01", "Market": "US", "Ticker": "AAA", "Rank": 1}],
                market="GLOBAL",
                snapshot_date="2026-01-02",
            )
            repo.write_records(
                "Factor_Score_Snapshots",
                [{"Snapshot_Date": "2026-01-03", "Market": "KR", "Ticker": "BBB", "Rank": 1}],
                market="GLOBAL",
                snapshot_date="2026-01-04",
            )

            latest = repo.read_dataframe("Factor_Score_Snapshots", market=None)
            history = repo.read_history("Factor_Score_Snapshots", market=None)

        self.assertEqual(latest["Ticker"].tolist(), ["BBB"])
        self.assertEqual(set(history["Ticker"]), {"AAA", "BBB"})
        self.assertEqual(set(history["_storage_snapshot_date"]), {"2026-01-02", "2026-01-04"})


if __name__ == "__main__":
    unittest.main()
