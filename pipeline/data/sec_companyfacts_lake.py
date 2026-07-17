"""Build a free SEC CompanyFacts data lake for quality research.

This module is intentionally file-based. The raw SEC bulk file and generated
Parquet outputs are local research artifacts and are ignored by git.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping
import json
import re
import zipfile

import numpy as np
import pandas as pd
import requests

from pipeline.data.sec_edgar import (
    DEFAULT_CACHE_DIR,
    DEFAULT_DELAY,
    DEFAULT_MAX_REQUESTS,
    build_fundamental_timeseries,
    company_facts_cache_path,
    edgar_user_agent,
    fetch_company_facts_for_tickers,
    get_pit_metrics,
    load_cik_map,
)


ROOT_DIR = Path(__file__).resolve().parents[2]
SEC_COMPANYFACTS_BULK_URL = "https://data.sec.gov/archives/edgar/daily-index/xbrl/companyfacts.zip"
DEFAULT_BULK_ZIP = DEFAULT_CACHE_DIR / "bulk" / "companyfacts.zip"
DEFAULT_OUTPUT_DIR = ROOT_DIR / "data_lake" / "sec_companyfacts"

PIT_METRIC_COLUMNS = [
    "Ticker",
    "CIK",
    "Filing_Date",
    "Source",
    "Revenue",
    "TotalAssets",
    "CurrentAssets",
    "CurrentLiabilities",
    "RetainedEarnings",
    "TotalLiabilities",
    "OperatingMargin",
    "ROIC",
    "ROE",
    "FCF_NI",
    "FCF_Margin",
    "GrossMargin",
    "InterestCoverage",
    "RevGrowth",
    "EPS_Growth",
    "DebtToEquity",
    "Debt_EBITDA",
]


@dataclass(frozen=True)
class SecCompanyFactsLakeConfig:
    output_dir: Path = DEFAULT_OUTPUT_DIR
    min_filing_year: int = 2010
    max_filing_year: int | None = None
    windows: tuple[int, ...] = (3, 5, 10)


def normalize_tickers(tickers: Iterable[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for ticker in tickers:
        symbol = str(ticker or "").strip().upper()
        if not symbol or symbol in seen:
            continue
        seen.add(symbol)
        out.append(symbol)
    return out


def download_companyfacts_bulk_zip(
    *,
    output_path: str | Path = DEFAULT_BULK_ZIP,
    session: requests.Session | None = None,
    user_agent: str | None = None,
    chunk_size: int = 1024 * 1024,
) -> Path:
    """Download SEC's CompanyFacts bulk ZIP to a local ignored cache path."""
    out = Path(output_path).expanduser()
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(out.suffix + ".tmp")
    sess = session or requests.Session()
    response = sess.get(
        SEC_COMPANYFACTS_BULK_URL,
        headers={"User-Agent": user_agent or edgar_user_agent()},
        stream=True,
        timeout=(30, 300),
    )
    response.raise_for_status()
    with tmp.open("wb") as handle:
        for chunk in response.iter_content(chunk_size=chunk_size):
            if chunk:
                handle.write(chunk)
    tmp.replace(out)
    return out


def iter_companyfacts_zip(
    zip_path: str | Path,
    *,
    ticker_by_cik: Mapping[str, str] | None = None,
    limit: int | None = None,
) -> Iterable[tuple[str, str, dict]]:
    """Yield ``(ticker, cik, payload)`` rows from an SEC CompanyFacts ZIP."""
    count = 0
    ticker_map = {str(k).zfill(10): str(v).upper() for k, v in (ticker_by_cik or {}).items()}
    with zipfile.ZipFile(Path(zip_path).expanduser()) as archive:
        for name in sorted(archive.namelist()):
            match = re.search(r"CIK(\d{10})\.json$", name)
            if not match:
                continue
            cik = match.group(1)
            ticker = ticker_map.get(cik, cik)
            if ticker_map and cik not in ticker_map:
                continue
            with archive.open(name) as handle:
                payload = json.loads(handle.read().decode("utf-8"))
            yield ticker, cik, payload
            count += 1
            if limit is not None and count >= limit:
                break


def load_cached_companyfacts(
    tickers: Iterable[str],
    cik_map: Mapping[str, str],
    *,
    cache_dir: str | Path | None = None,
) -> dict[str, dict]:
    """Read already-cached per-company CompanyFacts JSON files without network."""
    out: dict[str, dict] = {}
    for ticker in normalize_tickers(tickers):
        cik = cik_map.get(ticker)
        if not cik:
            continue
        path = company_facts_cache_path(cik, cache_dir)
        if not path.exists():
            continue
        try:
            out[ticker] = json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            continue
    return out


def companyfacts_to_pit_rows(
    ticker: str,
    cik: str,
    facts: Mapping,
    *,
    min_filing_year: int = 2010,
    max_filing_year: int | None = None,
) -> list[dict]:
    """Convert one SEC CompanyFacts payload into filing-date-safe rows."""
    symbol = str(ticker).strip().upper()
    if not symbol:
        return []
    timeseries = build_fundamental_timeseries({symbol: facts}).get(symbol)
    if not timeseries:
        return []

    filing_dates = sorted(
        {
            pd.Timestamp(date).normalize()
            for series in timeseries.values()
            for date in getattr(series, "index", [])
            if pd.notna(date)
        }
    )
    rows: list[dict] = []
    for filed_at in filing_dates:
        if filed_at.year < int(min_filing_year):
            continue
        if max_filing_year is not None and filed_at.year > int(max_filing_year):
            continue
        metrics = get_pit_metrics({symbol: timeseries}, symbol, filed_at)
        if not metrics:
            continue
        row = {
            "Ticker": symbol,
            "CIK": str(cik).zfill(10),
            "Filing_Date": filed_at.strftime("%Y-%m-%d"),
            "Source": "SEC_COMPANYFACTS",
            **metrics,
        }
        rows.append(row)
    return rows


def build_sec_pit_frame(
    raw_facts: Mapping[str, Mapping],
    *,
    cik_by_ticker: Mapping[str, str] | None = None,
    config: SecCompanyFactsLakeConfig | None = None,
) -> pd.DataFrame:
    cfg = config or SecCompanyFactsLakeConfig()
    rows: list[dict] = []
    cik_map = {str(k).upper(): str(v).zfill(10) for k, v in (cik_by_ticker or {}).items()}
    for ticker, facts in raw_facts.items():
        symbol = str(ticker).strip().upper()
        rows.extend(
            companyfacts_to_pit_rows(
                symbol,
                cik_map.get(symbol, symbol),
                facts,
                min_filing_year=cfg.min_filing_year,
                max_filing_year=cfg.max_filing_year,
            )
        )
    if not rows:
        return pd.DataFrame(columns=PIT_METRIC_COLUMNS)
    frame = pd.DataFrame(rows)
    for col in PIT_METRIC_COLUMNS:
        if col not in frame.columns:
            frame[col] = np.nan
    frame["Filing_Date"] = pd.to_datetime(frame["Filing_Date"], errors="coerce").dt.strftime("%Y-%m-%d")
    return frame[PIT_METRIC_COLUMNS].sort_values(["Ticker", "Filing_Date"]).reset_index(drop=True)


def _stability_score(series: pd.Series) -> float | None:
    values = pd.to_numeric(series, errors="coerce").dropna()
    if len(values) < 2:
        return None
    median = float(values.median())
    std = float(values.std(ddof=0))
    return float(np.clip(1.0 - std / (abs(median) + 0.10), 0.0, 1.0))


def _cagr(values: pd.Series, years: pd.Series) -> float | None:
    clean = pd.DataFrame({"value": pd.to_numeric(values, errors="coerce"), "year": years}).dropna()
    clean = clean[clean["value"] > 0]
    if len(clean) < 2:
        return None
    first = clean.iloc[0]
    last = clean.iloc[-1]
    periods = max(1, int(last["year"] - first["year"]))
    return float((float(last["value"]) / float(first["value"])) ** (1.0 / periods) - 1.0)


def _debt_reduction_trend(frame: pd.DataFrame) -> float | None:
    for col in ("Debt_EBITDA", "DebtToEquity"):
        values = pd.to_numeric(frame.get(col), errors="coerce").dropna()
        if len(values) >= 2:
            first = float(values.iloc[0])
            last = float(values.iloc[-1])
            return float((first - last) / max(abs(first), 1.0))
    return None


def _quality_persistence_score(row: Mapping[str, object], window: int = 5) -> float | None:
    pieces: list[tuple[float, float]] = []

    def add(weight: float, value: object, low: float = 0.0, high: float = 1.0) -> None:
        try:
            val = float(value)
        except Exception:
            return
        if pd.isna(val):
            return
        pieces.append((weight, float(np.clip((val - low) / (high - low), 0.0, 1.0))))

    add(0.25, row.get(f"ROIC_{window}Y_Median"), low=-0.05, high=0.25)
    add(0.20, row.get(f"ROIC_{window}Y_Stability"))
    add(0.20, row.get(f"FCF_Positive_Years_{window}Y"))
    add(0.15, row.get(f"Margin_Stability_{window}Y"))
    add(0.15, row.get(f"Revenue_CAGR_{window}Y"), low=-0.05, high=0.20)
    add(0.05, row.get(f"Debt_Reduction_Trend_{window}Y"), low=-0.50, high=0.50)
    if not pieces:
        return None
    total_weight = sum(weight for weight, _ in pieces)
    return float(sum(weight * value for weight, value in pieces) / total_weight)


def build_quality_history_features(
    pit_df: pd.DataFrame,
    *,
    as_of: pd.Timestamp | str | None = None,
    windows: tuple[int, ...] = (3, 5, 10),
) -> pd.DataFrame:
    """Build multi-year quality persistence features from PIT SEC rows."""
    if pit_df.empty:
        return pd.DataFrame()
    df = pit_df.copy()
    df["Filing_Date"] = pd.to_datetime(df["Filing_Date"], errors="coerce")
    df = df.dropna(subset=["Ticker", "Filing_Date"])
    if as_of is not None:
        df = df[df["Filing_Date"] <= pd.Timestamp(as_of)]
    if df.empty:
        return pd.DataFrame()

    df["Filing_Year"] = df["Filing_Date"].dt.year
    latest_per_year = (
        df.sort_values(["Ticker", "Filing_Year", "Filing_Date"])
        .groupby(["Ticker", "Filing_Year"], as_index=False)
        .tail(1)
        .sort_values(["Ticker", "Filing_Year"])
    )

    rows: list[dict] = []
    for ticker, group in latest_per_year.groupby("Ticker"):
        group = group.sort_values("Filing_Year")
        latest = group.iloc[-1].to_dict()
        row = {
            "Ticker": ticker,
            "CIK": latest.get("CIK"),
            "As_Of": pd.Timestamp(as_of or latest.get("Filing_Date")).strftime("%Y-%m-%d"),
            "Latest_Filing_Date": pd.Timestamp(latest.get("Filing_Date")).strftime("%Y-%m-%d"),
            "History_Years": int(group["Filing_Year"].nunique()),
        }
        for window in windows:
            tail = group.tail(window)
            row[f"ROIC_{window}Y_Median"] = pd.to_numeric(tail["ROIC"], errors="coerce").median()
            row[f"ROIC_{window}Y_Stability"] = _stability_score(tail["ROIC"])
            row[f"Revenue_CAGR_{window}Y"] = _cagr(tail["Revenue"], tail["Filing_Year"])
            row[f"FCF_Positive_Years_{window}Y"] = (
                pd.to_numeric(tail["FCF_Margin"], errors="coerce").gt(0).mean()
            )
            row[f"Margin_Stability_{window}Y"] = _stability_score(tail["OperatingMargin"])
            row[f"Debt_Reduction_Trend_{window}Y"] = _debt_reduction_trend(tail)
        row["Quality_Persistence_Score"] = _quality_persistence_score(row, window=5 if 5 in windows else windows[0])
        rows.append(row)
    return pd.DataFrame(rows).sort_values("Ticker").reset_index(drop=True)


def write_sec_companyfacts_lake(
    pit_df: pd.DataFrame,
    features_df: pd.DataFrame,
    *,
    output_dir: str | Path = DEFAULT_OUTPUT_DIR,
    snapshot_date: str | None = None,
) -> dict[str, Path]:
    """Write PIT rows and quality-history features to local Parquet files."""
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
    """Read the latest locally generated SEC quality-history feature snapshot."""
    root = Path(output_dir).expanduser() / "quality_history_features"
    if not root.exists():
        return pd.DataFrame()
    snapshots = sorted(
        [path for path in root.glob("snapshot_date=*") if path.is_dir()],
        key=lambda path: path.name,
        reverse=True,
    )
    for snapshot in snapshots:
        files = sorted(snapshot.glob("*.parquet"))
        frames = []
        for path in files:
            try:
                frames.append(pd.read_parquet(path))
            except Exception:
                continue
        if frames:
            return pd.concat(frames, ignore_index=True)
    return pd.DataFrame()


def read_latest_pit_fundamentals(
    *,
    output_dir: str | Path = DEFAULT_OUTPUT_DIR,
) -> pd.DataFrame:
    """Read the latest locally generated SEC point-in-time fundamental snapshot."""
    root = Path(output_dir).expanduser() / "pit_fundamentals"
    if not root.exists():
        return pd.DataFrame()
    snapshots = sorted(
        [path for path in root.glob("snapshot_date=*") if path.is_dir()],
        key=lambda path: path.name,
        reverse=True,
    )
    for snapshot in snapshots:
        files = sorted(snapshot.glob("*.parquet"))
        frames = []
        for path in files:
            try:
                frames.append(pd.read_parquet(path))
            except Exception:
                continue
        if frames:
            return pd.concat(frames, ignore_index=True)
    return pd.DataFrame()


def fetch_companyfacts_for_lake(
    tickers: Iterable[str],
    *,
    session: requests.Session | None = None,
    user_agent: str | None = None,
    cache_dir: str | Path | None = None,
    delay: float = DEFAULT_DELAY,
    max_requests: int | None = None,
) -> tuple[dict[str, dict], dict[str, str]]:
    """Fetch/cache CompanyFacts for selected tickers and return raw facts + CIK map."""
    symbols = normalize_tickers(tickers)
    if not symbols:
        return {}, {}
    ua = user_agent or edgar_user_agent()
    cik_map = load_cik_map(session=session, user_agent=ua, cache_dir=cache_dir)
    raw = fetch_company_facts_for_tickers(
        [symbol for symbol in symbols if symbol in cik_map],
        cik_map,
        session=session,
        user_agent=ua,
        cache_dir=cache_dir,
        delay=delay,
        max_requests=DEFAULT_MAX_REQUESTS if max_requests is None else max_requests,
    )
    return raw, {symbol: cik_map[symbol] for symbol in raw if symbol in cik_map}
