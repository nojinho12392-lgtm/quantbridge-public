"""Small FRED client for free macro signals.

FRED requires a free API key. The helpers return ``None`` when the key is
missing or the request fails, so callers can keep their existing fallbacks.
"""

from __future__ import annotations

import os

import pandas as pd
import requests


FRED_OBSERVATIONS_URL = "https://api.stlouisfed.org/fred/series/observations"


def fred_api_key() -> str:
    return (
        os.environ.get("FRED_API_KEY", "").strip()
        or os.environ.get("QUANT_FRED_API_KEY", "").strip()
    )


def fetch_fred_series(
    series_id: str,
    *,
    api_key: str | None = None,
    observation_start: str | None = None,
    timeout: int = 15,
) -> pd.Series | None:
    key = (api_key or fred_api_key()).strip()
    if not key:
        return None

    params = {
        "series_id": series_id,
        "api_key": key,
        "file_type": "json",
        "sort_order": "asc",
    }
    if observation_start:
        params["observation_start"] = observation_start

    try:
        resp = requests.get(FRED_OBSERVATIONS_URL, params=params, timeout=timeout)
        resp.raise_for_status()
        observations = resp.json().get("observations", [])
        rows = []
        for obs in observations:
            value = obs.get("value")
            if value in (None, "."):
                continue
            rows.append((pd.Timestamp(obs["date"]), float(value)))
        if not rows:
            return None
        return pd.Series([v for _, v in rows], index=pd.DatetimeIndex([d for d, _ in rows])).sort_index()
    except Exception:
        return None


def latest_fred_value(series_id: str, **kwargs) -> float | None:
    series = fetch_fred_series(series_id, **kwargs)
    if series is None or series.empty:
        return None
    return float(series.dropna().iloc[-1])
