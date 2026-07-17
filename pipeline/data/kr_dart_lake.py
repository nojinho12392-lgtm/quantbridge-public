"""Build a free OpenDART data lake for Korean quality research."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import pandas as pd

from pipeline.backtest.kr_pit_financials import (
    DEFAULT_CACHE,
    fetch_kr_pit_financials,
)
from pipeline.data.sec_companyfacts_lake import build_quality_history_features


ROOT_DIR = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_DIR = ROOT_DIR / "data_lake" / "kr_dart"


@dataclass(frozen=True)
class KrDartLakeConfig:
    output_dir: Path = DEFAULT_OUTPUT_DIR
    windows: tuple[int, ...] = (3, 5, 10)


def normalize_kr_tickers(tickers: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for ticker in tickers:
        raw = str(ticker or "").strip().upper()
        if not raw:
            continue
        symbol = raw if raw.endswith((".KS", ".KQ")) else f"{raw.split('.')[0].zfill(6)}.KS"
        if symbol in seen:
            continue
        seen.add(symbol)
        out.append(symbol)
    return out


def fetch_kr_dart_pit_frame(
    tickers: Iterable[str],
    years: Iterable[int],
    *,
    cache_path: str | Path = DEFAULT_CACHE,
    max_api_calls: int | None = None,
    delay: float = 0.35,
) -> pd.DataFrame:
    """Fetch/cache OpenDART PIT annual financials for selected tickers."""
    symbols = normalize_kr_tickers(tickers)
    fiscal_years = sorted({int(year) for year in years})
    if not symbols or not fiscal_years:
        return pd.DataFrame()
    return fetch_kr_pit_financials(
        symbols,
        fiscal_years,
        delay=delay,
        cache_path=Path(cache_path).expanduser(),
        max_api_calls=max_api_calls,
    )


def _standardize_pit_for_quality(pit_df: pd.DataFrame) -> pd.DataFrame:
    if pit_df.empty:
        return pd.DataFrame()
    df = pit_df.copy()
    if "Available_Date" in df.columns:
        df["Filing_Date"] = df["Available_Date"]
    elif "Filing_Date" not in df.columns:
        df["Filing_Date"] = pd.NaT
    df["Source"] = "OPENDART"
    if "CIK" not in df.columns:
        df["CIK"] = ""
    return df


def build_kr_quality_history_features(
    pit_df: pd.DataFrame,
    *,
    as_of: pd.Timestamp | str | None = None,
    windows: tuple[int, ...] = (3, 5, 10),
) -> pd.DataFrame:
    """Build multi-year quality persistence features from OpenDART PIT rows."""
    standardized = _standardize_pit_for_quality(pit_df)
    features = build_quality_history_features(standardized, as_of=as_of, windows=windows)
    if not features.empty:
        features["Source"] = "OPENDART"
    return features


def write_kr_dart_lake(
    pit_df: pd.DataFrame,
    features_df: pd.DataFrame,
    *,
    output_dir: str | Path = DEFAULT_OUTPUT_DIR,
    snapshot_date: str | None = None,
) -> dict[str, Path]:
    """Write OpenDART PIT rows and quality-history features to local Parquet."""
    root = Path(output_dir).expanduser()
    snap = snapshot_date or pd.Timestamp.utcnow().strftime("%Y-%m-%d")
    outputs: dict[str, Path] = {}
    for name, frame in (
        ("pit_fundamentals", pit_df),
        ("quality_history_features", features_df),
    ):
        out_dir = root / name / f"snapshot_date={snap}"
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / "part-000.parquet"
        frame.to_parquet(out_path, index=False)
        outputs[name] = out_path
    return outputs


def read_latest_quality_history_features(
    *,
    output_dir: str | Path = DEFAULT_OUTPUT_DIR,
) -> pd.DataFrame:
    """Read the latest locally generated OpenDART quality-history snapshot."""
    root = Path(output_dir).expanduser() / "quality_history_features"
    if not root.exists():
        return pd.DataFrame()
    snapshots = sorted(
        [path for path in root.glob("snapshot_date=*") if path.is_dir()],
        key=lambda path: path.name,
        reverse=True,
    )
    for snapshot in snapshots:
        frames = []
        for path in sorted(snapshot.glob("*.parquet")):
            try:
                frames.append(pd.read_parquet(path))
            except Exception:
                continue
        if frames:
            return pd.concat(frames, ignore_index=True)
    return pd.DataFrame()
