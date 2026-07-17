"""DuckDB/Parquet research lake writer."""

from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import Iterable

import pandas as pd


class ParquetLake:
    """Append immutable snapshots under data_lake/<dataset>/snapshot_date=.../."""

    def __init__(self, root: Path):
        self.root = Path(root)

    def write_records(
        self,
        dataset: str,
        records: Iterable[dict],
        market: str | None = None,
        snapshot_date: str | None = None,
    ) -> Path | None:
        rows = list(records)
        if not rows:
            return None

        snap = snapshot_date or datetime.utcnow().strftime("%Y-%m-%d")
        df = pd.DataFrame(rows)
        df["snapshot_date"] = snap
        if market and "Market" not in df.columns:
            df["Market"] = market

        parts = [self.root, dataset, f"snapshot_date={snap}"]
        if market:
            parts.append(f"market={market.upper()}")
        out_dir = Path(*parts)
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / "part-000.parquet"
        df.to_parquet(out_path, index=False)
        return out_path

    def query(self, sql: str) -> pd.DataFrame:
        import duckdb

        return duckdb.connect(":memory:").execute(sql).df()

    def read_latest(self, dataset: str, market: str | None = None) -> pd.DataFrame:
        dataset_dir = self.root / dataset
        if not dataset_dir.exists():
            return pd.DataFrame()

        snapshots = sorted(
            [p for p in dataset_dir.glob("snapshot_date=*") if p.is_dir()],
            key=lambda p: p.name,
            reverse=True,
        )
        for snapshot_dir in snapshots:
            read_dir = snapshot_dir
            if market:
                read_dir = snapshot_dir / f"market={market.upper()}"
            if not read_dir.exists():
                continue
            try:
                if read_dir.is_dir():
                    files = sorted(read_dir.rglob("*.parquet") if not market else read_dir.glob("*.parquet"))
                else:
                    files = [read_dir]
                frames = [pd.read_parquet(path) for path in files if path.is_file()]
                df = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()
            except Exception:
                continue
            if not df.empty:
                return df
        return pd.DataFrame()

    def read_history(self, dataset: str, market: str | None = None) -> pd.DataFrame:
        dataset_dir = self.root / dataset
        if not dataset_dir.exists():
            return pd.DataFrame()

        frames = []
        snapshots = sorted(
            [p for p in dataset_dir.glob("snapshot_date=*") if p.is_dir()],
            key=lambda p: p.name,
        )
        for snapshot_dir in snapshots:
            storage_snapshot_date = snapshot_dir.name.split("=", 1)[1]
            read_dir = snapshot_dir / f"market={market.upper()}" if market else snapshot_dir
            if not read_dir.exists():
                continue
            try:
                files = sorted(read_dir.glob("*.parquet") if market else read_dir.rglob("*.parquet"))
                snapshot_frames = [pd.read_parquet(path) for path in files if path.is_file()]
            except Exception:
                continue
            for frame in snapshot_frames:
                if not frame.empty:
                    out = frame.copy()
                    out["_storage_snapshot_date"] = storage_snapshot_date
                    frames.append(out)
        return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()
