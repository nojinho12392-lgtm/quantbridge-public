#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from datetime import date, timedelta
from pathlib import Path

import pandas as pd
import yfinance as yf

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from pipeline.backtest.shadow_portfolio import (  # noqa: E402
    ATTRIBUTION_KEY,
    ATTRIBUTION_SUMMARY_KEY,
    DEFAULT_HORIZONS,
    EVALUATION_KEY,
    MARKET_CONFIGS,
    SNAPSHOT_KEY,
    SECTOR_ATTRIBUTION_KEY,
    attribute_snapshots,
    empty_attribution,
    empty_attribution_summary,
    empty_evaluations,
    empty_sector_attribution,
    empty_snapshots,
    evaluate_snapshots,
    merge_by_key,
    normalize_portfolio_snapshot,
    summarize_evaluations,
)
from quantbridge.config import get_settings  # noqa: E402
from quantbridge.writers.dual_write import dual_write_dataframe  # noqa: E402
from sheets_client import get_spreadsheet  # noqa: E402


SNAPSHOT_SHEET = "Shadow_Portfolio_Snapshots"
EVALUATION_SHEET = "Shadow_Portfolio_Evaluation"
SUMMARY_SHEET = "Shadow_Portfolio_Summary"
ATTRIBUTION_SHEET = "Shadow_Portfolio_Attribution"
SECTOR_ATTRIBUTION_SHEET = "Shadow_Portfolio_Sector_Attribution"
ATTRIBUTION_SUMMARY_SHEET = "Shadow_Portfolio_Attribution_Summary"


def _values_to_dataframe(values: list[list[str]], *, required_header: str | None = None) -> pd.DataFrame:
    if not values:
        return pd.DataFrame()

    header_idx = 0
    if required_header:
        for idx, row in enumerate(values):
            if required_header in row:
                header_idx = idx
                break

    header = [str(col).strip() for col in values[header_idx]]
    rows = values[header_idx + 1 :]
    if not header:
        return pd.DataFrame()

    width = len(header)
    padded = [row[:width] + [""] * max(width - len(row), 0) for row in rows if any(str(cell).strip() for cell in row)]
    return pd.DataFrame(padded, columns=header)


def _read_sheet_dataframe(spreadsheet, title: str, *, required_header: str | None = None) -> pd.DataFrame:
    try:
        values = spreadsheet.worksheet(title).get_all_values()
    except Exception:
        return pd.DataFrame()
    return _values_to_dataframe(values, required_header=required_header)


def _write_dataframe(spreadsheet, title: str, df: pd.DataFrame) -> None:
    sheet_df = df.copy()
    sheet_df = sheet_df.astype(object).where(pd.notna(sheet_df), "")
    values = [sheet_df.columns.tolist()] + sheet_df.astype(str).values.tolist()
    rows = max(len(values) + 10, 100)
    cols = max(len(sheet_df.columns) + 2, 10)

    try:
        worksheet = spreadsheet.worksheet(title)
    except Exception:
        worksheet = spreadsheet.add_worksheet(title=title, rows=rows, cols=cols)

    worksheet.clear()
    if values:
        worksheet.update(range_name="A1", values=values, value_input_option="USER_ENTERED")


def _download_prices(tickers: list[str], start: date, end: date) -> pd.DataFrame:
    unique = sorted({ticker for ticker in tickers if ticker})
    if not unique:
        return pd.DataFrame()

    try:
        raw = yf.download(
            unique,
            start=start.strftime("%Y-%m-%d"),
            end=(end + timedelta(days=1)).strftime("%Y-%m-%d"),
            auto_adjust=True,
            progress=False,
            threads=True,
        )
    except Exception as exc:
        print(f"[SHADOW] Price download skipped: {type(exc).__name__}: {exc}")
        return pd.DataFrame()

    if raw.empty:
        return pd.DataFrame()

    if isinstance(raw.columns, pd.MultiIndex):
        if "Close" not in raw.columns.get_level_values(0):
            return pd.DataFrame()
        close = raw["Close"]
    else:
        close = raw[["Close"]].rename(columns={"Close": unique[0]}) if "Close" in raw.columns else raw

    if isinstance(close, pd.Series):
        close = close.to_frame(name=unique[0])
    close.index = pd.to_datetime(close.index).normalize()
    close = close.loc[:, ~close.columns.duplicated()]
    return close


def _latest_price_map(prices: pd.DataFrame) -> dict[str, float]:
    out: dict[str, float] = {}
    for ticker in prices.columns:
        series = pd.to_numeric(prices[ticker], errors="coerce").dropna()
        if not series.empty:
            out[str(ticker)] = float(series.iloc[-1])
    return out


def _collect_tickers(*frames: pd.DataFrame) -> list[str]:
    tickers: set[str] = set()
    for frame in frames:
        if frame is None or frame.empty:
            continue
        for col in ("Ticker", "Benchmark_Ticker"):
            if col in frame.columns:
                tickers.update(str(value).strip() for value in frame[col].dropna() if str(value).strip())
    return sorted(tickers)


def main() -> None:
    print("[SHADOW] Recording and evaluating shadow portfolios")
    settings = get_settings()
    has_google_credentials = bool(getattr(settings, "google_key_json", "").strip()) or Path(
        settings.google_key_path
    ).expanduser().exists()
    if not has_google_credentials:
        print("[SHADOW] Google Sheets credentials not configured; skipping shadow portfolio step")
        return

    spreadsheet = get_spreadsheet()
    today = date.today()
    now = pd.Timestamp.now()

    portfolio_frames: dict[str, pd.DataFrame] = {}
    current_tickers: list[str] = []
    for config in MARKET_CONFIGS:
        frame = _read_sheet_dataframe(spreadsheet, config.source_sheet, required_header="Ticker")
        portfolio_frames[config.market] = frame
        if not frame.empty and "Ticker" in frame.columns:
            current_tickers.extend(str(value).strip() for value in frame["Ticker"].dropna() if str(value).strip())
        current_tickers.append(config.benchmark_ticker)

    recent_prices = _download_prices(current_tickers, today - timedelta(days=14), today)
    price_map = _latest_price_map(recent_prices)

    new_snapshots = []
    for config in MARKET_CONFIGS:
        snapshot = normalize_portfolio_snapshot(
            portfolio_frames.get(config.market, pd.DataFrame()),
            market=config.market,
            source_sheet=config.source_sheet,
            benchmark_ticker=config.benchmark_ticker,
            snapshot_date=today,
            price_map=price_map,
            generated_at=now,
        )
        if not snapshot.empty:
            new_snapshots.append(snapshot)
            print(f"[SHADOW] {config.market}: captured {len(snapshot)} holdings")
        else:
            print(f"[SHADOW] {config.market}: no holdings found")

    existing_snapshots = _read_sheet_dataframe(spreadsheet, SNAPSHOT_SHEET)
    if existing_snapshots.empty:
        existing_snapshots = empty_snapshots()
    snapshots = merge_by_key(existing_snapshots, pd.concat(new_snapshots, ignore_index=True) if new_snapshots else empty_snapshots(), SNAPSHOT_KEY)
    if snapshots.empty:
        print("[SHADOW] No snapshots available; exiting")
        return

    min_snapshot = pd.to_datetime(snapshots["Snapshot_Date"], errors="coerce").min()
    start = min_snapshot.date() - timedelta(days=10) if pd.notna(min_snapshot) else today - timedelta(days=10)
    all_tickers = _collect_tickers(snapshots)
    eval_prices = _download_prices(all_tickers, start, today)

    new_evaluations = evaluate_snapshots(
        snapshots,
        eval_prices,
        as_of_date=today,
        horizons=DEFAULT_HORIZONS,
        generated_at=now,
    )
    new_attribution, new_sector_attribution, new_attribution_summary = attribute_snapshots(
        snapshots,
        eval_prices,
        as_of_date=today,
        horizons=DEFAULT_HORIZONS,
        generated_at=now,
    )

    existing_evaluations = _read_sheet_dataframe(spreadsheet, EVALUATION_SHEET)
    if existing_evaluations.empty:
        existing_evaluations = empty_evaluations()
    evaluations = merge_by_key(existing_evaluations, new_evaluations, EVALUATION_KEY)
    summary = summarize_evaluations(evaluations, generated_at=now)

    existing_attribution = _read_sheet_dataframe(spreadsheet, ATTRIBUTION_SHEET)
    if existing_attribution.empty:
        existing_attribution = empty_attribution()
    attribution = merge_by_key(existing_attribution, new_attribution, ATTRIBUTION_KEY)

    existing_sector_attribution = _read_sheet_dataframe(spreadsheet, SECTOR_ATTRIBUTION_SHEET)
    if existing_sector_attribution.empty:
        existing_sector_attribution = empty_sector_attribution()
    sector_attribution = merge_by_key(
        existing_sector_attribution,
        new_sector_attribution,
        SECTOR_ATTRIBUTION_KEY,
    )

    existing_attribution_summary = _read_sheet_dataframe(spreadsheet, ATTRIBUTION_SUMMARY_SHEET)
    if existing_attribution_summary.empty:
        existing_attribution_summary = empty_attribution_summary()
    attribution_summary = merge_by_key(
        existing_attribution_summary,
        new_attribution_summary,
        ATTRIBUTION_SUMMARY_KEY,
    )

    _write_dataframe(spreadsheet, SNAPSHOT_SHEET, snapshots)
    _write_dataframe(spreadsheet, EVALUATION_SHEET, evaluations)
    _write_dataframe(spreadsheet, SUMMARY_SHEET, summary)
    _write_dataframe(spreadsheet, ATTRIBUTION_SHEET, attribution)
    _write_dataframe(spreadsheet, SECTOR_ATTRIBUTION_SHEET, sector_attribution)
    _write_dataframe(spreadsheet, ATTRIBUTION_SUMMARY_SHEET, attribution_summary)

    dual_write_dataframe("Shadow_Portfolio_Snapshots", snapshots)
    dual_write_dataframe("Shadow_Portfolio_Evaluation", evaluations)
    dual_write_dataframe("Shadow_Portfolio_Summary", summary)
    dual_write_dataframe("Shadow_Portfolio_Attribution", attribution)
    dual_write_dataframe("Shadow_Portfolio_Sector_Attribution", sector_attribution)
    dual_write_dataframe("Shadow_Portfolio_Attribution_Summary", attribution_summary)

    print(f"[SHADOW] Snapshots: {len(snapshots)} rows")
    print(f"[SHADOW] Evaluations: {len(evaluations)} rows")
    print(f"[SHADOW] Summary: {len(summary)} rows")
    print(f"[SHADOW] Attribution: {len(attribution)} rows")
    print(f"[SHADOW] Sector attribution: {len(sector_attribution)} rows")
    print(f"[SHADOW] Attribution summary: {len(attribution_summary)} rows")


if __name__ == "__main__":
    main()
