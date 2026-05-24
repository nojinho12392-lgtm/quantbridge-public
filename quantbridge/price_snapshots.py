"""Refresh lightweight latest-price snapshots for app list screens."""

from __future__ import annotations

import argparse
import re
import time
from datetime import datetime, time as dt_time, timedelta, timezone
from typing import Iterable
from zoneinfo import ZoneInfo

import pandas as pd
import requests

from api.services.company_names import localized_company_name
from api.services.etf_insights import default_etf_insights
from quantbridge.storage import QuantRepository


SNAPSHOT_DATASETS: tuple[tuple[str, str | None], ...] = (
    ("US_Final_Portfolio", "US"),
    ("KR_Final_Portfolio", "KR"),
    ("US_SmallCap_Gems", "US"),
    ("KR_SmallCap_Gems", "KR"),
    ("ETF_Insights", None),
)

SCORED_SNAPSHOT_DATASETS: tuple[tuple[str, str], ...] = (
    ("US_Scored_Stocks", "US"),
    ("KR_Scored_Stocks", "KR"),
)

UNIVERSE_SNAPSHOT_DATASETS: tuple[tuple[str, str], ...] = (
    ("US_Universe", "US"),
    ("KR_Universe", "KR"),
)

APP_PRICE_UNIVERSE_DATASET = "App_Price_Universe"
APP_PRICE_COVERAGE_DATASET = "App_Price_Coverage_Report"
APP_PRICE_STALE_HOURS = 72
US_MARKET_TZ = ZoneInfo("America/New_York")


def _normal_ticker(value) -> str:
    return str(value or "").strip().upper()


def _to_float(value) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if pd.notna(number) else None


def _clean_text(value) -> str | None:
    try:
        if pd.isna(value):
            return None
    except (TypeError, ValueError):
        pass
    text = str(value or "").strip()
    return text or None


def _iso_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _us_regular_session_phase(now: datetime | None = None) -> str:
    local = (now or datetime.now(timezone.utc)).astimezone(US_MARKET_TZ)
    if local.weekday() >= 5:
        return "closed_day"
    if local.time() < dt_time(9, 30):
        return "pre_open"
    if local.time() <= dt_time(16, 0):
        return "open"
    return "after_close"


def _should_use_regular_daily_snapshot(target: dict, now: datetime | None = None) -> bool:
    return (
        str(target.get("market") or "").strip().upper() == "US"
        and _asset_type_for_target(target) in {"EQUITY", "ETF"}
        and _us_regular_session_phase(now) != "open"
    )


def _daily_close_as_of(index_value: pd.Timestamp, market: str) -> pd.Timestamp:
    timestamp = pd.Timestamp(index_value)
    if str(market or "").strip().upper() == "US":
        local_close = datetime.combine(timestamp.date(), dt_time(16, 0), tzinfo=US_MARKET_TZ)
        return pd.Timestamp(local_close.astimezone(timezone.utc))
    if timestamp.tzinfo is not None:
        return pd.Timestamp(timestamp.tz_convert("UTC"))
    return timestamp.tz_localize("UTC")


def _chunked(values: list[str], size: int) -> Iterable[list[str]]:
    clean_size = max(1, int(size or 1))
    for index in range(0, len(values), clean_size):
        yield values[index:index + clean_size]


def _kr_code(value) -> str:
    match = re.search(r"(\d{6})", str(value or "").strip().upper())
    return match.group(1) if match else ""


def _kr_ticker_suffix(code: str) -> str:
    headers = {
        "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X)",
        "Referer": "https://m.stock.naver.com/",
    }
    try:
        response = requests.get(
            f"https://m.stock.naver.com/api/stock/{code}/basic",
            headers=headers,
            timeout=4,
        )
        if response.status_code == 200:
            data = response.json()
            exchange = (
                (data.get("stockExchangeType") or {}).get("code")
                or data.get("stockExchangeName")
                or ""
            )
            if str(exchange).upper() in {"KQ", "KOSDAQ"}:
                return ".KQ"
    except Exception:
        pass
    return ".KS"


_NAVER_QUOTE_HEADERS = {
    "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X)",
    "Referer": "https://m.stock.naver.com/",
}


def _naver_quote_float(value) -> float | None:
    text = str(value or "").strip()
    if not text:
        return None
    text = text.replace(",", "").replace("%", "")
    try:
        return float(text)
    except ValueError:
        return None


def _fetch_naver_kr_stock_quote_rows(codes: list[str], *, delay: float = 0.05) -> list[dict]:
    clean_codes = sorted({code for code in (_kr_code(value) for value in codes) if code})
    rows: list[dict] = []
    for chunk in _chunked(clean_codes, 50):
        try:
            response = requests.get(
                f"https://polling.finance.naver.com/api/realtime/domestic/stock/{','.join(chunk)}",
                headers=_NAVER_QUOTE_HEADERS,
                timeout=6,
            )
            if response.status_code == 200:
                rows.extend(row for row in response.json().get("datas") or [] if isinstance(row, dict))
        except Exception as exc:
            print(f"[price-snapshots] naver quote chunk skipped: {type(exc).__name__}: {exc}")
        if delay:
            time.sleep(delay)
    return rows


def _normalize_for_yfinance(ticker: str, market: str) -> str:
    normal = _normal_ticker(ticker)
    if market == "KR":
        code = _kr_code(normal)
        if code and not normal.endswith((".KS", ".KQ")):
            return f"{code}{_kr_ticker_suffix(code)}"
    return normal


def _target_source_list(target: dict) -> list[str]:
    raw = target.get("source_datasets")
    if isinstance(raw, str):
        values = [item.strip() for item in raw.split(",")]
    elif isinstance(raw, (list, tuple, set)):
        values = [str(item or "").strip() for item in raw]
    else:
        values = []
    source = str(target.get("source_dataset") or "").strip()
    if source:
        values.append(source)
    return list(dict.fromkeys(value for value in values if value))


def _merge_target(targets: dict[tuple[str, str], dict], key: tuple[str, str], candidate: dict) -> None:
    source_values = _target_source_list(candidate)
    candidate = dict(candidate)
    candidate["source_datasets"] = source_values
    if not candidate.get("source_dataset") and source_values:
        candidate["source_dataset"] = source_values[0]

    existing = targets.get(key)
    if existing is None:
        targets[key] = candidate
        return

    merged_sources = _target_source_list(existing) + source_values
    existing["source_datasets"] = list(dict.fromkeys(source for source in merged_sources if source))
    for field in (
        "raw_ticker",
        "name",
        "ko_name",
        "sector",
        "theme",
        "rank",
        "stored_current_price",
        "stored_return_1m",
        "total_score",
        "expected_return",
        "revenue_growth",
    ):
        if existing.get(field) in (None, "") and candidate.get(field) not in (None, ""):
            existing[field] = candidate[field]


def _load_dataset_targets(
    repo: QuantRepository,
    datasets: tuple[tuple[str, str | None], ...],
    markets: set[str],
    targets: dict[tuple[str, str], dict],
    limit: int | None = None,
) -> bool:
    """Load snapshot targets into ``targets``.

    Returns True when the global limit has been reached.
    """

    for dataset, fallback_market in datasets:
        if fallback_market and fallback_market not in markets:
            continue
        df = repo.read_dataframe(dataset, market=fallback_market)
        if dataset == "ETF_Insights" and df.empty:
            df = pd.DataFrame(default_etf_insights())
        ticker_col = next((col for col in ("Ticker", "ticker", "Symbol", "symbol") if col in df.columns), None)
        if df.empty or not ticker_col:
            continue
        for _, row in df.iterrows():
            market = str(
                row.get("Market")
                or row.get("market")
                or row.get("region")
                or row.get("Region")
                or fallback_market
                or "US"
            ).strip().upper()
            if market not in markets:
                continue
            raw_ticker = _normal_ticker(row.get(ticker_col))
            ticker = _normalize_for_yfinance(raw_ticker, market)
            if not ticker:
                continue
            key = (market, ticker)
            _merge_target(targets, key, {
                "market": market,
                "ticker": ticker,
                "raw_ticker": raw_ticker,
                "name": _clean_text(row.get("Name")) or _clean_text(row.get("name")),
                "ko_name": localized_company_name(
                    ticker,
                    _clean_text(row.get("Name")) or _clean_text(row.get("name")) or raw_ticker,
                    market,
                ),
                "source_dataset": dataset,
                "sector": _clean_text(row.get("Sector")) or _clean_text(row.get("category")) or _clean_text(row.get("Category")),
                "rank": _to_float(row.get("Rank")),
                "stored_current_price": _to_float(row.get("Current_Price")) or _to_float(row.get("currentPrice")),
                "stored_return_1m": _to_float(row.get("Return_1M")) or _to_float(row.get("return1M")) or _to_float(row.get("Mom_1M")),
                "total_score": _to_float(row.get("Total_Score")),
                "expected_return": _to_float(row.get("Expected_Return")),
                "revenue_growth": _to_float(row.get("RevGrowth")),
            })
            if limit and len(targets) >= limit:
                return True
    return False


def _load_sector_seed_targets(
    markets: set[str],
    targets: dict[tuple[str, str], dict],
    limit: int | None = None,
) -> bool:
    try:
        from api.server import _SECTOR_THEME_RULES, _SECTOR_THEME_SEED_GROUPS
    except Exception as exc:
        print(f"[price-snapshots] sector seed import skipped: {type(exc).__name__}: {exc}")
        return False

    def _append_target(
        *,
        raw_ticker: str,
        name: str | None,
        market: str,
        sector: str | None,
        theme: str,
        source_dataset: str,
    ) -> bool:
        clean_market = str(market or "").strip().upper()
        if clean_market not in markets:
            return False
        clean_raw = _normal_ticker(raw_ticker)
        ticker = _normalize_for_yfinance(clean_raw, clean_market)
        if not ticker:
            return False
        _merge_target(targets, (clean_market, ticker), {
            "market": clean_market,
            "ticker": ticker,
            "raw_ticker": clean_raw,
            "name": _clean_text(name) or clean_raw,
            "ko_name": localized_company_name(clean_raw, _clean_text(name) or clean_raw, clean_market),
            "source_dataset": source_dataset,
            "sector": _clean_text(sector) or theme,
            "theme": theme,
            "rank": None,
            "stored_current_price": None,
            "stored_return_1m": None,
            "total_score": None,
            "expected_return": None,
            "revenue_growth": None,
        })
        return bool(limit and len(targets) >= limit)

    for theme, members in _SECTOR_THEME_SEED_GROUPS.items():
        for raw_ticker, name, market, sector in members:
            if _append_target(
                raw_ticker=raw_ticker,
                name=name,
                market=market,
                sector=sector,
                theme=theme,
                source_dataset="Sector_Theme_Seeds",
            ):
                return True

    for rule in _SECTOR_THEME_RULES:
        theme = _clean_text(rule.get("label"))
        for raw_ticker in sorted(rule.get("tickers") or []):
            market = "KR" if _kr_code(raw_ticker) else "US"
            if _append_target(
                raw_ticker=raw_ticker,
                name=raw_ticker,
                market=market,
                sector=theme,
                theme=theme,
                source_dataset="Sector_Theme_Rules",
            ):
                return True
    return False


def _load_targets(
    repo: QuantRepository,
    markets: set[str],
    limit: int | None = None,
    *,
    include_scored: bool = True,
    include_universe: bool = True,
    include_sector_seeds: bool = True,
) -> list[dict]:
    targets: dict[tuple[str, str], dict] = {}
    if _load_dataset_targets(repo, SNAPSHOT_DATASETS, markets, targets, limit=limit):
        return list(targets.values())
    if include_scored and _load_dataset_targets(repo, SCORED_SNAPSHOT_DATASETS, markets, targets, limit=limit):
        return list(targets.values())
    if include_universe and _load_dataset_targets(repo, UNIVERSE_SNAPSHOT_DATASETS, markets, targets, limit=limit):
        return list(targets.values())
    if include_sector_seeds:
        _load_sector_seed_targets(markets, targets, limit=limit)
    return list(targets.values())


def _close_frame(raw: pd.DataFrame, batch: list[str]) -> pd.DataFrame:
    if raw.empty:
        return pd.DataFrame()
    if isinstance(raw.columns, pd.MultiIndex):
        levels = [set(map(str, raw.columns.get_level_values(level))) for level in range(raw.columns.nlevels)]
        if "Close" in levels[0]:
            frame = raw["Close"].copy()
        elif raw.columns.nlevels > 1 and "Close" in levels[1]:
            frame = raw.xs("Close", axis=1, level=1).copy()
        else:
            return pd.DataFrame()
    else:
        if "Close" not in raw.columns:
            return pd.DataFrame()
        close = raw["Close"].copy()
        frame = close.to_frame(name=batch[0]) if isinstance(close, pd.Series) else close

    if isinstance(frame, pd.Series):
        frame = frame.to_frame(name=batch[0])
    frame.columns = [_normal_ticker(col) for col in frame.columns]
    index = pd.to_datetime(frame.index, errors="coerce")
    try:
        if index.tz is not None:
            index = index.tz_convert(None)
    except AttributeError:
        pass
    frame.index = index
    frame = frame[frame.index.notna()]
    return frame.sort_index()


def _download_close_frames(
    yf,
    tickers: list[str],
    *,
    period: str,
    interval: str,
    batch_size: int,
    delay: float,
) -> pd.DataFrame:
    frames: list[pd.DataFrame] = []
    for batch in _chunked(tickers, batch_size):
        try:
            raw = yf.download(
                batch,
                period=period,
                interval=interval,
                auto_adjust=False,
                progress=False,
                ignore_tz=False,
                threads=True,
            )
            frame = _close_frame(raw, batch)
            if not frame.empty:
                frames.append(frame)
        except Exception as exc:
            print(f"[price-snapshots] batch failed interval={interval} tickers={len(batch)} error={type(exc).__name__}: {exc}")
        time.sleep(max(delay, 0))

    if not frames:
        return pd.DataFrame()
    merged = pd.concat(frames, axis=1)
    merged = merged.loc[:, ~merged.columns.duplicated()]
    return merged.sort_index().ffill()


def _field_value(row: pd.Series, *names: str):
    for name in names:
        if name in row:
            return row.get(name)
    return None


def _records_from_ohlcv_frame(frame: pd.DataFrame) -> list[dict]:
    if frame.empty:
        return []
    local = frame.copy()
    local.columns = [str(col) for col in local.columns]
    local.index = pd.to_datetime(local.index, errors="coerce")
    local = local[local.index.notna()].sort_index()
    records: list[dict] = []
    for index, row in local.iterrows():
        if pd.Timestamp(index).weekday() >= 5:
            continue
        open_price = _to_float(_field_value(row, "Open", "open"))
        high = _to_float(_field_value(row, "High", "high"))
        low = _to_float(_field_value(row, "Low", "low"))
        close = _to_float(_field_value(row, "Close", "close"))
        if open_price is None or high is None or low is None or close is None:
            continue
        records.append({
            "date": str(pd.Timestamp(index).date()),
            "open": open_price,
            "high": max(high, open_price, close),
            "low": min(low, open_price, close),
            "close": close,
            "volume": _to_float(_field_value(row, "Volume", "volume")),
        })
    return records


def _ticker_frame_from_multiindex(raw: pd.DataFrame, ticker: str) -> pd.DataFrame:
    normal = _normal_ticker(ticker)
    levels = [list(raw.columns.get_level_values(level)) for level in range(raw.columns.nlevels)]
    level_sets = [set(map(str, level_values)) for level_values in levels]

    if "Open" in level_sets[0] and raw.columns.nlevels > 1:
        for column_ticker in dict.fromkeys(str(value) for value in levels[1]):
            if _normal_ticker(column_ticker) == normal:
                return raw.xs(column_ticker, axis=1, level=1).copy()
    if raw.columns.nlevels > 1 and "Open" in level_sets[1]:
        for column_ticker in dict.fromkeys(str(value) for value in levels[0]):
            if _normal_ticker(column_ticker) == normal:
                return raw.xs(column_ticker, axis=1, level=0).copy()
    return pd.DataFrame()


def _download_daily_ohlcv_frames(
    yf,
    tickers: list[str],
    *,
    period: str,
    batch_size: int,
    delay: float,
) -> tuple[pd.DataFrame, dict[str, list[dict]]]:
    close_frames: list[pd.DataFrame] = []
    records_by_ticker: dict[str, list[dict]] = {}

    for batch in _chunked(tickers, batch_size):
        try:
            raw = yf.download(
                batch,
                period=period,
                interval="1d",
                auto_adjust=False,
                progress=False,
                ignore_tz=False,
                threads=True,
            )
        except Exception as exc:
            print(f"[price-snapshots] daily ohlcv batch failed tickers={len(batch)} error={type(exc).__name__}: {exc}")
            time.sleep(max(delay, 0))
            continue

        if raw.empty:
            time.sleep(max(delay, 0))
            continue

        for ticker in batch:
            normal = _normal_ticker(ticker)
            if isinstance(raw.columns, pd.MultiIndex):
                frame = _ticker_frame_from_multiindex(raw, ticker)
            else:
                frame = raw.copy() if len(batch) == 1 else pd.DataFrame()
            records = _records_from_ohlcv_frame(frame)
            if not records:
                continue
            records_by_ticker[normal] = records
            close_series = pd.Series(
                {pd.Timestamp(record["date"]): record["close"] for record in records},
                name=normal,
                dtype="float64",
            )
            close_frames.append(close_series.to_frame())

        time.sleep(max(delay, 0))

    if close_frames:
        close_frame = pd.concat(close_frames, axis=1)
        close_frame = close_frame.loc[:, ~close_frame.columns.duplicated()]
        close_frame = close_frame.sort_index().ffill()
    else:
        close_frame = pd.DataFrame()
    return close_frame, records_by_ticker


def _target_price_aliases(target: dict, *, include_code_variants: bool = True) -> list[str]:
    aliases: list[str] = []
    for value in (target.get("ticker"), target.get("raw_ticker")):
        normal = _normal_ticker(value)
        if normal and normal not in aliases:
            aliases.append(normal)
        if include_code_variants:
            code = _kr_code(normal)
            for candidate in (code, f"{code}.KS" if code else "", f"{code}.KQ" if code else ""):
                if candidate and candidate not in aliases:
                    aliases.append(candidate)
    return aliases


def _latest_close_for_target(frame: pd.DataFrame, target: dict) -> tuple[float | None, pd.Timestamp | None]:
    if frame.empty:
        return None, None
    for alias in _target_price_aliases(target):
        if alias not in frame.columns:
            continue
        series = frame[alias].dropna().sort_index()
        if series.empty:
            continue
        return _to_float(series.iloc[-1]), pd.Timestamp(series.index[-1])
    return None, None


def _stored_price_history(repo: QuantRepository, targets: list[dict]) -> dict[str, pd.DataFrame]:
    tickers_by_market: dict[str, set[str]] = {}
    for target in targets:
        market = str(target.get("market") or "").strip().upper()
        if not market:
            continue
        tickers_by_market.setdefault(market, set()).update(_target_price_aliases(target))

    history: dict[str, pd.DataFrame] = {}
    for market, tickers in tickers_by_market.items():
        if not tickers:
            continue
        try:
            history[market] = repo.read_prices_batch(sorted(tickers), period="3mo", market=market)
        except Exception:
            history[market] = pd.DataFrame()
    return history


def _return_1m_from_history(
    history_by_market: dict[str, pd.DataFrame],
    target: dict,
    current_price: float | None,
    anchor_time: pd.Timestamp,
) -> float | None:
    if current_price is None or current_price <= 0:
        return _to_float(target.get("stored_return_1m"))

    market = str(target.get("market") or "").strip().upper()
    history = history_by_market.get(market, pd.DataFrame())
    if history.empty or "ticker" not in history.columns or "date" not in history.columns or "close" not in history.columns:
        return _to_float(target.get("stored_return_1m"))

    aliases = set(_target_price_aliases(target))
    frame = history[history["ticker"].astype(str).str.upper().isin(aliases)].copy()
    if frame.empty:
        return _to_float(target.get("stored_return_1m"))

    frame["date"] = pd.to_datetime(frame["date"], errors="coerce")
    frame["close"] = pd.to_numeric(frame["close"], errors="coerce")
    frame = frame.dropna(subset=["date", "close"]).sort_values("date")
    if frame.empty:
        return _to_float(target.get("stored_return_1m"))

    anchor = pd.Timestamp(anchor_time)
    if anchor.tzinfo is not None:
        anchor = anchor.tz_convert(None)
    target_date = anchor.normalize() - pd.Timedelta(days=30)
    base_rows = frame[frame["date"] <= target_date]
    if base_rows.empty:
        return _to_float(target.get("stored_return_1m"))

    base_price = _to_float(base_rows["close"].iloc[-1])
    if base_price is None or base_price <= 0:
        return _to_float(target.get("stored_return_1m"))
    return (current_price / base_price) - 1.0


def _upsert_recent_daily_ohlcv(
    repo: QuantRepository,
    daily_records: dict[str, list[dict]],
    targets: list[dict],
    *,
    source: str,
) -> int:
    if not daily_records:
        return 0

    stored = 0
    for target in targets:
        records = None
        for alias in _target_price_aliases(target):
            records = daily_records.get(_normal_ticker(alias))
            if records:
                break
        if not records:
            continue

        market = str(target.get("market") or "").strip().upper()
        for alias in _target_price_aliases(target, include_code_variants=False):
            try:
                stored += repo.upsert_prices(alias, market, records, source=source)
            except Exception:
                pass
    return stored


def _snapshot_metrics(
    recent_daily: pd.DataFrame,
    intraday: pd.DataFrame,
    history_by_market: dict[str, pd.DataFrame],
    targets: list[dict],
    source: str,
) -> list[dict]:
    metrics: list[dict] = []
    now = datetime.now(timezone.utc).replace(microsecond=0)
    for target in targets:
        ticker = target["ticker"]
        market = str(target.get("market") or "").strip().upper()
        use_daily_close = _should_use_regular_daily_snapshot(target, now)
        current_price, current_time = (
            _latest_close_for_target(recent_daily, target)
            if use_daily_close
            else _latest_close_for_target(intraday, target)
        )
        if current_price is None and not use_daily_close:
            current_price, current_time = _latest_close_for_target(recent_daily, target)
        elif current_price is None:
            current_price, current_time = _latest_close_for_target(intraday, target)
        if current_price is None:
            continue

        anchor_time = pd.Timestamp(current_time) if current_time is not None else pd.Timestamp.utcnow()
        if use_daily_close and current_time is not None:
            anchor_time = _daily_close_as_of(pd.Timestamp(current_time), market)
        return_1m = _return_1m_from_history(history_by_market, target, current_price, anchor_time)

        metric = {
            "market": target["market"],
            "ticker": ticker,
            "name": target.get("name"),
            "current_price": current_price,
            "return_1m": return_1m,
            "as_of": anchor_time.tz_localize("UTC").isoformat() if anchor_time.tzinfo is None else anchor_time.isoformat(),
            "updated_at": now.isoformat(),
            "source": source,
            "source_dataset": target.get("source_dataset"),
        }
        metrics.append(metric)
        raw_ticker = _normal_ticker(target.get("raw_ticker"))
        if raw_ticker and raw_ticker != ticker:
            metrics.append({**metric, "ticker": raw_ticker})
    return metrics


def _naver_kr_metric_overrides(
    repo: QuantRepository,
    targets: list[dict],
    history_by_market: dict[str, pd.DataFrame],
    *,
    source: str = "naver_realtime",
) -> tuple[list[dict], int]:
    targets_by_code: dict[str, list[dict]] = {}
    for target in targets:
        if str(target.get("market") or "").strip().upper() != "KR":
            continue
        if _asset_type_for_target(target) != "EQUITY":
            continue
        code = _kr_code(target.get("ticker")) or _kr_code(target.get("raw_ticker"))
        if code:
            targets_by_code.setdefault(code, []).append(target)
    if not targets_by_code:
        return [], 0

    metrics: list[dict] = []
    daily_rows_by_ticker: dict[str, dict] = {}
    now = datetime.now(timezone.utc).replace(microsecond=0)
    for row in _fetch_naver_kr_stock_quote_rows(list(targets_by_code)):
        code = _kr_code(row.get("itemCode") or row.get("symbolCode"))
        if not code:
            continue
        current_price = _naver_quote_float(row.get("closePriceRaw") or row.get("closePrice"))
        if current_price is None or current_price <= 0:
            continue
        change_pct_percent = _naver_quote_float(row.get("fluctuationsRatioRaw") or row.get("fluctuationsRatio"))
        change_amount = _naver_quote_float(
            row.get("compareToPreviousClosePriceRaw") or row.get("compareToPreviousClosePrice")
        )
        previous_close = None
        if change_amount is not None:
            previous_close = current_price - change_amount
        elif change_pct_percent is not None and abs(change_pct_percent) > 1e-9:
            previous_close = current_price / (1.0 + change_pct_percent / 100.0)
        daily_change_pct = None
        if change_pct_percent is not None:
            daily_change_pct = change_pct_percent / 100.0
        elif previous_close is not None and previous_close > 0:
            daily_change_pct = (current_price / previous_close) - 1.0
        traded_at_raw = str(row.get("localTradedAt") or "").strip()
        traded_at = pd.to_datetime(traded_at_raw, errors="coerce")
        if pd.isna(traded_at):
            traded_at = pd.Timestamp(now)
        if traded_at.tzinfo is not None:
            anchor_time = traded_at.tz_convert("UTC")
        else:
            anchor_time = traded_at.tz_localize("Asia/Seoul").tz_convert("UTC")

        for target in targets_by_code.get(code, []):
            return_1m = _return_1m_from_history(history_by_market, target, current_price, anchor_time)
            metric = {
                "market": "KR",
                "ticker": target["ticker"],
                "name": target.get("name"),
                "current_price": current_price,
                "return_1m": return_1m,
                "as_of": anchor_time.isoformat(),
                "updated_at": now.isoformat(),
                "source": source,
                "source_dataset": target.get("source_dataset"),
                "daily_change_pct": daily_change_pct,
                "daily_change_horizon": "오늘",
                "previous_close": previous_close,
            }
            aliases = [
                _normal_ticker(target.get("ticker")),
                _normal_ticker(target.get("raw_ticker")),
                code,
            ]
            open_price = _naver_quote_float(row.get("openPriceRaw") or row.get("openPrice"))
            high = _naver_quote_float(row.get("highPriceRaw") or row.get("highPrice"))
            low = _naver_quote_float(row.get("lowPriceRaw") or row.get("lowPrice"))
            volume = _naver_quote_float(row.get("accumulatedTradingVolumeRaw") or row.get("accumulatedTradingVolume"))
            for alias in dict.fromkeys(alias for alias in aliases if alias):
                metrics.append({**metric, "ticker": alias})
                if open_price is not None and high is not None and low is not None:
                    daily_rows_by_ticker[alias] = {
                        "date": traded_at.date().isoformat(),
                        "open": open_price,
                        "high": max(high, open_price, current_price),
                        "low": min(low, open_price, current_price),
                        "close": current_price,
                        "volume": volume,
                    }

    stored_daily_rows = 0
    for ticker, record in daily_rows_by_ticker.items():
        try:
            stored_daily_rows += repo.upsert_prices(ticker, "KR", [record], source=source)
        except Exception as exc:
            print(f"[price-snapshots] naver daily close upsert skipped for {ticker}: {type(exc).__name__}: {exc}")
    return metrics, stored_daily_rows


def _asset_type_for_target(target: dict) -> str:
    sources = set(_target_source_list(target))
    ticker = _normal_ticker(target.get("ticker"))
    if "ETF_Insights" in sources:
        return "ETF"
    if ticker.startswith("^") or "=" in ticker:
        return "INDEX"
    return "EQUITY"


def _app_price_universe_rows(targets: list[dict], generated_at: str) -> list[dict]:
    rows: list[dict] = []
    for target in sorted(targets, key=lambda item: (str(item.get("market") or ""), str(item.get("ticker") or ""))):
        sources = _target_source_list(target)
        aliases = _target_price_aliases(target)
        rows.append({
            "Market": str(target.get("market") or "").strip().upper(),
            "Ticker": _normal_ticker(target.get("ticker")),
            "Raw_Ticker": _normal_ticker(target.get("raw_ticker")),
            "Name": _clean_text(target.get("name")),
            "ko_name": _clean_text(target.get("ko_name")) or localized_company_name(
                target.get("ticker") or target.get("raw_ticker"),
                _clean_text(target.get("name")) or target.get("raw_ticker") or target.get("ticker"),
                str(target.get("market") or "").strip().upper(),
            ),
            "Asset_Type": _asset_type_for_target(target),
            "Sector": _clean_text(target.get("sector")),
            "Theme": _clean_text(target.get("theme")),
            "Source_Dataset": _clean_text(target.get("source_dataset")),
            "Source_Datasets": ",".join(sources),
            "Aliases": ",".join(aliases),
            "Rank": _to_float(target.get("rank")),
            "Stored_Current_Price": _to_float(target.get("stored_current_price")),
            "Stored_Return_1M": _to_float(target.get("stored_return_1m")),
            "Total_Score": _to_float(target.get("total_score")),
            "Expected_Return": _to_float(target.get("expected_return")),
            "Revenue_Growth": _to_float(target.get("revenue_growth")),
            "Generated_At": generated_at,
        })
    return rows


def _metric_map(metrics: Iterable[dict]) -> dict[tuple[str, str], dict]:
    mapped: dict[tuple[str, str], dict] = {}
    for metric in metrics:
        market = str(metric.get("market") or metric.get("Market") or "").strip().upper()
        ticker = _normal_ticker(metric.get("ticker") or metric.get("Ticker"))
        if market and ticker:
            mapped[(market, ticker)] = metric
    return mapped


def _metric_for_target(mapped: dict[tuple[str, str], dict], target: dict) -> dict | None:
    market = str(target.get("market") or "").strip().upper()
    for alias in _target_price_aliases(target):
        metric = mapped.get((market, alias))
        if metric:
            return metric
    return None


def _metric_as_of(metric: dict | None) -> pd.Timestamp | None:
    if not metric:
        return None
    raw = metric.get("as_of") or metric.get("updated_at") or metric.get("Price_Updated_At")
    if raw is None:
        return None
    parsed = pd.to_datetime(raw, utc=True, errors="coerce")
    if pd.isna(parsed):
        return None
    return pd.Timestamp(parsed)


def _latest_metric_rows(repo: QuantRepository, targets: list[dict]) -> list[dict]:
    aliases = sorted({
        alias
        for target in targets
        for alias in _target_price_aliases(target)
    })
    if not aliases:
        return []
    try:
        frame = repo.read_price_metrics(aliases)
    except Exception:
        return []
    if frame.empty:
        return []
    return frame.astype(object).where(pd.notna(frame), None).to_dict(orient="records")


def _coverage_status(
    *,
    current_metric: dict | None,
    latest_metric: dict | None,
    stale_hours: int,
    now: pd.Timestamp,
) -> tuple[str, float | None]:
    if latest_metric is None or _to_float(latest_metric.get("current_price") or latest_metric.get("Current_Price")) is None:
        return "STORAGE_MISSING", None
    as_of = _metric_as_of(latest_metric)
    age_hours = None
    if as_of is not None:
        age_hours = round((now - as_of).total_seconds() / 3600.0, 2)
    if age_hours is not None and age_hours > stale_hours:
        return "STALE", age_hours
    if current_metric is None or _to_float(current_metric.get("current_price") or current_metric.get("Current_Price")) is None:
        return "REFRESH_MISSING", age_hours
    return "OK", age_hours


def _app_price_coverage_rows(
    repo: QuantRepository,
    targets: list[dict],
    metrics: list[dict],
    *,
    generated_at: str,
    stale_hours: int = APP_PRICE_STALE_HOURS,
) -> tuple[list[dict], dict]:
    current_by_key = _metric_map(metrics)
    latest_by_key = _metric_map(_latest_metric_rows(repo, targets))
    now = pd.Timestamp.now(tz=timezone.utc)
    rows: list[dict] = []
    status_counts: dict[str, int] = {}

    for target in sorted(targets, key=lambda item: (str(item.get("market") or ""), str(item.get("ticker") or ""))):
        current_metric = _metric_for_target(current_by_key, target)
        latest_metric = _metric_for_target(latest_by_key, target)
        status, age_hours = _coverage_status(
            current_metric=current_metric,
            latest_metric=latest_metric,
            stale_hours=stale_hours,
            now=now,
        )
        status_counts[status] = status_counts.get(status, 0) + 1
        latest_as_of = _metric_as_of(latest_metric)
        current_as_of = _metric_as_of(current_metric)
        rows.append({
            "Market": str(target.get("market") or "").strip().upper(),
            "Ticker": _normal_ticker(target.get("ticker")),
            "Raw_Ticker": _normal_ticker(target.get("raw_ticker")),
            "Name": _clean_text(target.get("name")),
            "Asset_Type": _asset_type_for_target(target),
            "Status": status,
            "Has_Current_Run_Price": current_metric is not None and _to_float(current_metric.get("current_price")) is not None,
            "Has_Storage_Price": latest_metric is not None and _to_float(latest_metric.get("current_price")) is not None,
            "Current_Run_Price": _to_float((current_metric or {}).get("current_price")),
            "Storage_Price": _to_float((latest_metric or {}).get("current_price")),
            "Return_1M": _to_float((latest_metric or {}).get("return_1m")),
            "Current_Run_As_Of": current_as_of.isoformat() if current_as_of is not None else None,
            "Storage_As_Of": latest_as_of.isoformat() if latest_as_of is not None else None,
            "Storage_Age_Hours": age_hours,
            "Stale_Threshold_Hours": stale_hours,
            "Source_Datasets": ",".join(_target_source_list(target)),
            "Generated_At": generated_at,
        })

    total = len(rows)
    ok_count = status_counts.get("OK", 0)
    summary = {
        "total": total,
        "ok": ok_count,
        "refresh_missing": status_counts.get("REFRESH_MISSING", 0),
        "storage_missing": status_counts.get("STORAGE_MISSING", 0),
        "stale": status_counts.get("STALE", 0),
        "coverage_ratio": round(ok_count / total, 4) if total else 0.0,
        "status_counts": status_counts,
    }
    return rows, summary


def _pct_text(value: float | None) -> str:
    if value is None:
        return "-"
    return f"{value * 100:+.2f}%"


def _price_text(value: float | None, market: str) -> str:
    if value is None:
        return "-"
    if market == "KR":
        return f"{value:,.0f}원"
    return f"${value:,.2f}"


def _previous_metric_map(previous: pd.DataFrame) -> dict[tuple[str, str], dict]:
    out: dict[tuple[str, str], dict] = {}
    if previous.empty:
        return out
    for _, row in previous.iterrows():
        market = str(row.get("market") or "").strip().upper()
        ticker = _normal_ticker(row.get("ticker"))
        if market and ticker:
            out[(market, ticker)] = row.to_dict()
    return out


def _previous_rank_map(repo: QuantRepository, dataset: str, market: str) -> dict[str, int]:
    previous = repo.read_previous_ranks(dataset, market=market)
    if previous.empty:
        return {}
    ranks: dict[str, int] = {}
    for _, row in previous.iterrows():
        rank = _to_float(row.get("Previous_Rank"))
        ticker = _normal_ticker(row.get("Ticker"))
        if rank is not None and ticker:
            ranks[ticker] = int(rank)
            code = _kr_code(ticker)
            if code:
                ranks[code] = int(rank)
                ranks[f"{code}.KS"] = int(rank)
                ranks[f"{code}.KQ"] = int(rank)
    return ranks


def _event_id(source: str, market: str, ticker: str, kind: str) -> str:
    return f"{source}:{market}:{ticker}:{kind}"


def _event(
    *,
    source: str,
    market: str,
    ticker: str,
    name: str | None,
    kind: str,
    severity: int,
    title: str,
    detail: str,
    metric_label: str,
    metric_value: str,
    event_time: datetime,
    payload: dict,
) -> dict:
    return {
        "event_id": _event_id(source, market, ticker, kind),
        "market": market,
        "ticker": ticker,
        "name": name or ticker,
        "kind": kind,
        "severity": max(1, min(int(severity), 5)),
        "title": title,
        "detail": detail,
        "metric_label": metric_label,
        "metric_value": metric_value,
        "event_time": event_time.isoformat(),
        "source": source,
        "payload": payload,
    }


def _build_price_and_rank_events(
    repo: QuantRepository,
    metrics: list[dict],
    targets: list[dict],
    previous_metrics: pd.DataFrame,
    source: str,
) -> list[dict]:
    now = datetime.now(timezone.utc).replace(microsecond=0)
    target_by_key: dict[tuple[str, str], dict] = {}
    for target in targets:
        market = str(target.get("market") or "").strip().upper()
        ticker = _normal_ticker(target.get("ticker"))
        raw_ticker = _normal_ticker(target.get("raw_ticker"))
        if market and ticker:
            target_by_key[(market, ticker)] = target
        if market and raw_ticker:
            target_by_key[(market, raw_ticker)] = target

    previous_by_key = _previous_metric_map(previous_metrics)
    rank_maps: dict[tuple[str, str], dict[str, int]] = {}
    events: list[dict] = []
    seen: set[tuple[str, str]] = set()

    for metric in metrics:
        market = str(metric.get("market") or "").strip().upper()
        ticker = _normal_ticker(metric.get("ticker"))
        target = target_by_key.get((market, ticker))
        if not market or not ticker or target is None:
            continue
        if (market, ticker) in seen:
            continue
        seen.add((market, ticker))

        name = target.get("name") or ticker
        current_price = _to_float(metric.get("current_price"))
        return_1m = _to_float(metric.get("return_1m"))
        previous = previous_by_key.get((market, ticker), {})
        previous_price = _to_float(previous.get("current_price"))
        since_last = (current_price / previous_price - 1.0) if current_price and previous_price and previous_price > 0 else None

        if return_1m is not None and abs(return_1m) >= 0.08:
            kind = "price_momentum" if return_1m > 0 else "price_pressure"
            title = "1개월 강세" if return_1m > 0 else "1개월 약세"
            severity = 5 if abs(return_1m) >= 0.18 else 4 if abs(return_1m) >= 0.12 else 3
            events.append(_event(
                source=source,
                market=market,
                ticker=ticker,
                name=name,
                kind=kind,
                severity=severity,
                title=title,
                detail=f"{name} 1개월 수익률 {_pct_text(return_1m)} · 현재가 {_price_text(current_price, market)}",
                metric_label="1개월",
                metric_value=_pct_text(return_1m),
                event_time=now,
                payload={**metric, "source_dataset": target.get("source_dataset")},
            ))

        if since_last is not None and abs(since_last) >= 0.015:
            kind = "price_spike" if since_last > 0 else "price_drop"
            title = "단기 가격 급등" if since_last > 0 else "단기 가격 급락"
            severity = 5 if abs(since_last) >= 0.04 else 4 if abs(since_last) >= 0.025 else 3
            events.append(_event(
                source=source,
                market=market,
                ticker=ticker,
                name=name,
                kind=kind,
                severity=severity,
                title=title,
                detail=f"직전 스냅샷 대비 {_pct_text(since_last)} · 현재가 {_price_text(current_price, market)}",
                metric_label="15분 변화",
                metric_value=_pct_text(since_last),
                event_time=now,
                payload={**metric, "previous_price": previous_price},
            ))

        dataset = str(target.get("source_dataset") or "")
        rank = _to_float(target.get("rank"))
        if dataset and rank is not None:
            key = (dataset, market)
            rank_maps.setdefault(key, _previous_rank_map(repo, dataset, market))
            rank_lookup = rank_maps[key]
            previous_rank = rank_lookup.get(ticker)
            if previous_rank is None:
                code = _kr_code(ticker)
                previous_rank = rank_lookup.get(code) if code else None
            if previous_rank is not None:
                change = int(previous_rank - int(rank))
                if abs(change) >= 3:
                    kind = "rank_up" if change > 0 else "rank_down"
                    title = "순위 상승" if change > 0 else "순위 하락"
                    severity = 5 if abs(change) >= 10 else 4 if abs(change) >= 6 else 3
                    events.append(_event(
                        source=source,
                        market=market,
                        ticker=ticker,
                        name=name,
                        kind=kind,
                        severity=severity,
                        title=title,
                        detail=f"{name} 순위 {previous_rank}위 → {int(rank)}위 ({'+' if change > 0 else ''}{change})",
                        metric_label="순위 변화",
                        metric_value=f"{'▲' if change > 0 else '▼'}{abs(change)}",
                        event_time=now,
                        payload={
                            "current_rank": int(rank),
                            "previous_rank": previous_rank,
                            "rank_change": change,
                            "source_dataset": dataset,
                        },
                    ))

    return events


def _build_earnings_events(repo: QuantRepository, targets: list[dict], source: str) -> list[dict]:
    calendar = repo.read_dataframe("Earnings_Calendar")
    if calendar.empty:
        return []

    today = datetime.now(timezone(timedelta(hours=9))).date()
    target_by_key: dict[str, dict] = {}
    for target in targets:
        for ticker in (_normal_ticker(target.get("ticker")), _normal_ticker(target.get("raw_ticker"))):
            if ticker:
                target_by_key[ticker] = target
                code = _kr_code(ticker)
                if code:
                    target_by_key[code] = target

    ticker_col = next((col for col in ("Ticker", "ticker", "Symbol") if col in calendar.columns), None)
    date_col = next((col for col in ("Next_Earnings_Date", "Next_Earnings", "Earnings_Date", "earnings_date") if col in calendar.columns), None)
    if not ticker_col or not date_col:
        return []

    events: list[dict] = []
    now = datetime.now(timezone.utc).replace(microsecond=0)
    for _, row in calendar.iterrows():
        raw_ticker = _normal_ticker(row.get(ticker_col))
        target = target_by_key.get(raw_ticker) or target_by_key.get(_kr_code(raw_ticker))
        if not raw_ticker or target is None:
            continue
        date = pd.to_datetime(row.get(date_col), errors="coerce")
        if pd.isna(date):
            continue
        days = (date.date() - today).days
        if days < 0 or days > 7:
            continue
        market = str(target.get("market") or row.get("Market") or "US").strip().upper()
        ticker = _normal_ticker(target.get("ticker") or raw_ticker)
        name = target.get("name") or _clean_text(row.get("Name")) or ticker
        day_text = "D-Day" if days == 0 else f"D-{days}"
        events.append(_event(
            source=source,
            market=market,
            ticker=ticker,
            name=name,
            kind="earnings_due",
            severity=5 if days <= 1 else 4 if days <= 3 else 3,
            title="실적 임박",
            detail=f"{name} 실적 {day_text} · {date.date().isoformat()} 발표 예정",
            metric_label="실적",
            metric_value=day_text,
            event_time=now,
            payload={
                "next_earnings_date": date.date().isoformat(),
                "days_until": days,
            },
        ))
    return events


def refresh_price_snapshots(
    repo: QuantRepository | None = None,
    *,
    markets: set[str] | None = None,
    limit: int | None = None,
    batch_size: int = 40,
    delay: float = 0.25,
    intraday_period: str = "2d",
    intraday_interval: str = "5m",
    update_recent_daily: bool = False,
    include_scored: bool = True,
    include_universe: bool = True,
    include_sector_seeds: bool = True,
    source: str = "yfinance",
) -> dict:
    try:
        import yfinance as yf
    except ImportError as exc:
        raise RuntimeError("yfinance is not installed") from exc

    repo = repo or QuantRepository()
    clean_markets = markets or {"US", "KR"}
    targets = _load_targets(
        repo,
        clean_markets,
        limit=limit,
        include_scored=include_scored,
        include_universe=include_universe,
        include_sector_seeds=include_sector_seeds,
    )
    generated_at = _iso_now()
    snapshot_date = generated_at[:10]
    universe_rows = _app_price_universe_rows(targets, generated_at)
    universe_stored = 0
    try:
        universe_stored = repo.write_records(APP_PRICE_UNIVERSE_DATASET, universe_rows, snapshot_date=snapshot_date)
    except Exception as exc:
        print(f"[price-snapshots] app price universe write skipped: {type(exc).__name__}: {exc}")

    tickers = sorted({target["ticker"] for target in targets})
    if not tickers:
        return {
            "targets": 0,
            "metrics": 0,
            "stored": 0,
            "app_price_universe_rows": len(universe_rows),
            "app_price_universe_stored": universe_stored,
            "markets": sorted(clean_markets),
        }

    intraday = _download_close_frames(
        yf,
        tickers,
        period=intraday_period,
        interval=intraday_interval,
        batch_size=batch_size,
        delay=delay,
    )
    needs_regular_daily_snapshot = any(_should_use_regular_daily_snapshot(target) for target in targets)
    should_refresh_recent_daily = update_recent_daily or needs_regular_daily_snapshot
    recent_daily = pd.DataFrame()
    stored_daily_rows = 0
    if should_refresh_recent_daily:
        recent_daily, recent_daily_records = _download_daily_ohlcv_frames(
            yf,
            tickers,
            period="7d",
            batch_size=batch_size,
            delay=delay,
        )
        stored_daily_rows = _upsert_recent_daily_ohlcv(
            repo,
            recent_daily_records,
            targets,
            source=f"{source}_recent_daily",
        )
    history_by_market = _stored_price_history(repo, targets)
    metrics = _snapshot_metrics(recent_daily, intraday, history_by_market, targets, source=source)
    naver_metrics, naver_daily_rows = _naver_kr_metric_overrides(
        repo,
        targets,
        history_by_market,
    )
    if naver_metrics:
        metrics_by_key = {
            (str(metric.get("market") or "").upper(), _normal_ticker(metric.get("ticker"))): metric
            for metric in metrics
        }
        for metric in naver_metrics:
            metrics_by_key[(str(metric.get("market") or "").upper(), _normal_ticker(metric.get("ticker")))] = metric
        metrics = list(metrics_by_key.values())
        stored_daily_rows += naver_daily_rows
    metric_tickers = sorted({
        alias
        for target in targets
        for alias in _target_price_aliases(target, include_code_variants=False)
    })
    previous_metrics = repo.read_price_metrics(metric_tickers or tickers)
    stored = repo.upsert_price_metrics(metrics, source=source)
    coverage_rows, coverage_summary = _app_price_coverage_rows(
        repo,
        targets,
        metrics,
        generated_at=generated_at,
    )
    coverage_stored = 0
    try:
        coverage_stored = repo.write_records(
            APP_PRICE_COVERAGE_DATASET,
            coverage_rows,
            snapshot_date=snapshot_date,
        )
    except Exception as exc:
        print(f"[price-snapshots] app price coverage report write skipped: {type(exc).__name__}: {exc}")
    event_source = "price_snapshot_15m"
    events = _build_price_and_rank_events(repo, metrics, targets, previous_metrics, event_source)
    events.extend(_build_earnings_events(repo, targets, event_source))
    stored_events = repo.replace_signal_events(event_source, events)
    return {
        "targets": len(targets),
        "tickers": len(tickers),
        "metrics": len(metrics),
        "stored": stored,
        "stored_daily_rows": stored_daily_rows,
        "naver_kr_metrics": len(naver_metrics),
        "app_price_universe_rows": len(universe_rows),
        "app_price_universe_stored": universe_stored,
        "app_price_coverage_rows": len(coverage_rows),
        "app_price_coverage_stored": coverage_stored,
        "app_price_coverage": coverage_summary,
        "events": len(events),
        "stored_events": stored_events,
        "include_scored": include_scored,
        "include_universe": include_universe,
        "include_sector_seeds": include_sector_seeds,
        "markets": sorted(clean_markets),
        "updated_at": _iso_now(),
    }


def _warm_api_cache(api_url: str, timeout: float = 5.0) -> dict:
    url = f"{str(api_url or 'http://127.0.0.1:8000').rstrip('/')}/ops/cache/warm"
    response = requests.post(url, timeout=timeout)
    response.raise_for_status()
    data = response.json()
    if isinstance(data, dict):
        return data
    return {"running": None}


def _clear_api_cache(api_url: str, timeout: float = 5.0) -> dict:
    url = f"{str(api_url or 'http://127.0.0.1:8000').rstrip('/')}/cache/clear"
    response = requests.post(url, timeout=timeout)
    response.raise_for_status()
    data = response.json()
    return data if isinstance(data, dict) else {"cleared": None}


def refresh_market_indicators(period: str = "1d", interval: str = "1m") -> dict:
    """Refresh compact market indicator ticks used by app headers and charts."""
    from api import server

    specs = server._indicator_specs("all")
    symbols = [str(spec.get("symbol") or "").strip() for spec in specs if spec.get("symbol")]
    points = server._fetch_indicator_history(symbols, period, interval)
    stored = 0
    if points:
        stored = server._repository().upsert_market_indicators(
            points,
            source="yfinance_intraday_refresh",
        )
    return {
        "symbols": len(symbols),
        "points": len(points),
        "stored": stored,
        "period": period,
        "interval": interval,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Refresh QuantBridge latest price snapshots")
    parser.add_argument("--markets", default="US,KR", help="Comma-separated markets: US,KR")
    parser.add_argument("--limit", type=int, default=None, help="Limit tickers for smoke tests")
    parser.add_argument("--batch-size", type=int, default=40)
    parser.add_argument("--delay", type=float, default=0.25)
    parser.add_argument("--intraday-period", default="2d", help="Intraday quote lookback for latest prices")
    parser.add_argument("--intraday-interval", default="5m", help="Intraday quote interval for latest prices")
    parser.add_argument(
        "--update-recent-daily",
        action="store_true",
        default=False,
        help="Also refresh a compact 7-day daily close tail for chart history",
    )
    parser.add_argument(
        "--skip-recent-daily",
        action="store_false",
        dest="update_recent_daily",
        help="Skip the compact 7-day daily close refresh",
    )
    parser.add_argument("--skip-scored", action="store_true", help="Do not include full scored-stock lists")
    parser.add_argument("--skip-universe", action="store_true", help="Do not include broad stock universes")
    parser.add_argument("--skip-sector-seeds", action="store_true", help="Do not include manually curated sector theme seeds")
    parser.add_argument("--api-url", default="http://127.0.0.1:8000", help="API base URL for post-refresh cache warming")
    parser.add_argument("--skip-api-cache-warm", action="store_true", help="Do not trigger API cache warming after storing prices")
    parser.add_argument("--skip-market-indicators", action="store_true", help="Do not refresh market indicator ticks")
    parser.add_argument("--market-indicator-period", default="1d", help="Market indicator history period")
    parser.add_argument("--market-indicator-interval", default="15m", help="Market indicator history interval")
    args = parser.parse_args(argv)

    markets = {market.strip().upper() for market in args.markets.split(",") if market.strip()}
    result = refresh_price_snapshots(
        markets=markets or {"US", "KR"},
        limit=args.limit,
        batch_size=args.batch_size,
        delay=args.delay,
        intraday_period=args.intraday_period,
        intraday_interval=args.intraday_interval,
        update_recent_daily=args.update_recent_daily,
        include_scored=not args.skip_scored,
        include_universe=not args.skip_universe,
        include_sector_seeds=not args.skip_sector_seeds,
    )
    print(f"[price-snapshots] {result}")
    if not args.skip_market_indicators:
        try:
            indicators = refresh_market_indicators(
                period=args.market_indicator_period,
                interval=args.market_indicator_interval,
            )
            print(f"[price-snapshots] market indicators: {indicators}")
        except Exception as exc:
            print(f"[price-snapshots] market indicator refresh skipped: {type(exc).__name__}: {exc}")
    if result.get("stored", 0) and not args.skip_api_cache_warm:
        try:
            cleared = _clear_api_cache(args.api_url)
            print(f"[price-snapshots] api cache cleared: {cleared}")
            warm = _warm_api_cache(args.api_url)
            print(f"[price-snapshots] api cache warm requested: {warm}")
        except Exception as exc:
            print(f"[price-snapshots] api cache warm skipped: {type(exc).__name__}: {exc}")
    return 0 if result.get("stored", 0) else 1
