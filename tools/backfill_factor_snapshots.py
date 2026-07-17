#!/usr/bin/env python3
"""Bootstrap historical factor score snapshots for Phase 3 research.

The backfill is intentionally marked as PROXY_BACKFILL. It uses the latest
value/quality scores as static anchors and reconstructs historical momentum
from prices at each snapshot date. This is useful for warming IC dashboards and
policy simulations, but true production evidence should still come from
LIVE_DAILY snapshots accumulated over time.

Run:
    python tools/backfill_factor_snapshots.py --months 9
"""

from __future__ import annotations

import argparse
import sys
import time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import gspread
import numpy as np
import pandas as pd
import yfinance as yf

from sheets_client import get_spreadsheet
from quantbridge.storage import QuantRepository
from quantbridge.writers.dual_write import dual_write_dataframe


SNAPSHOT_SHEET = "Factor_Score_Snapshots"
BACKFILL_LOG_SHEET = "Factor_Snapshot_Backfill_Log"

SNAPSHOT_COLS = [
    "Snapshot_Date", "Market", "Ticker", "Name", "Sector",
    "Value_Score", "Quality_Score", "Momentum_Score",
    "Total_Score", "Final_Score", "Score_Neutral", "Combined_Score",
    "Business_Quality_Score", "Investability_Score", "Persistence_Quality",
    "Snapshot_Source",
]

FACTOR_COLS = [
    "Value_Score", "Quality_Score", "Momentum_Score",
    "Total_Score", "Final_Score", "Score_Neutral", "Combined_Score",
    "Business_Quality_Score", "Investability_Score", "Persistence_Quality",
]
STATIC_FACTOR_COLS = [
    "Value_Score", "Quality_Score",
    "Business_Quality_Score", "Investability_Score", "Persistence_Quality",
]

LOG_COLS = [
    "Generated", "Mode", "Months", "Dates", "Rows_Added", "Rows_Total",
    "US_Tickers", "KR_Tickers", "Note",
]

BASE_WEIGHTS = {
    "US": {"Value_Score": 0.40, "Quality_Score": 0.30, "Momentum_Score": 0.30},
    "KR": {"Value_Score": 0.40, "Quality_Score": 0.35, "Momentum_Score": 0.25},
}

BATCH = 80
PRICE_DELAY = 0.4


def _spreadsheet():
    return get_spreadsheet()


def _repository() -> QuantRepository:
    return QuantRepository()


def _get_or_create_sheet(name: str, rows: int, cols: int):
    try:
        return _spreadsheet().worksheet(name)
    except gspread.exceptions.WorksheetNotFound:
        return _spreadsheet().add_worksheet(title=name, rows=rows, cols=cols)


def _to_num(df: pd.DataFrame, cols: list[str]) -> pd.DataFrame:
    out = df.copy()
    for col in cols:
        if col in out.columns:
            out[col] = pd.to_numeric(out[col], errors="coerce")
    return out


def _read_simple_sheet(name: str) -> pd.DataFrame:
    try:
        rows = _spreadsheet().worksheet(name).get_all_values()
    except gspread.exceptions.WorksheetNotFound:
        return pd.DataFrame()
    except Exception as exc:
        print(f"[BACKFILL] Sheet read skipped for {name}: {type(exc).__name__}: {exc}")
        return pd.DataFrame()
    if len(rows) < 2:
        return pd.DataFrame()
    return pd.DataFrame(rows[1:], columns=rows[0])


def _load_scored_sheet(name: str, market: str, limit: int = 0) -> pd.DataFrame:
    try:
        df = _repository().read_dataframe(name, market=market)
    except Exception as exc:
        print(f"[BACKFILL] Storage read skipped for {name}: {type(exc).__name__}: {exc}")
        df = pd.DataFrame()
    if df.empty:
        df = _read_simple_sheet(name)
    if df.empty or "Ticker" not in df.columns:
        return pd.DataFrame()

    df = df[df["Ticker"].astype(str).str.strip() != ""].copy()
    df["Market"] = market
    for col in [
        "Name", "Sector", "Value_Score", "Quality_Score", "Momentum_Score",
        "Total_Score", "Final_Score", "Business_Quality_Score",
        "Investability_Score", "Persistence_Quality",
    ]:
        if col not in df.columns:
            df[col] = ""
    score_sort = "Final_Score" if "Final_Score" in df.columns else "Total_Score"
    df = _to_num(df, FACTOR_COLS)
    if score_sort in df.columns:
        df = df.sort_values(score_sort, ascending=False, na_position="last")
    if limit and limit > 0:
        df = df.head(limit)
    return df.reset_index(drop=True)


def _prepare_snapshot_frame(df: pd.DataFrame) -> pd.DataFrame:
    if df.empty:
        return pd.DataFrame(columns=SNAPSHOT_COLS)

    out = df.copy()
    for col in SNAPSHOT_COLS:
        if col not in out.columns:
            out[col] = ""

    out = out[out["Ticker"].astype(str).str.strip() != ""].copy()
    out["Market"] = out["Market"].fillna("").astype(str).str.upper()
    out = out[out["Market"].isin(["US", "KR"])].copy()

    logical_dates = pd.to_datetime(out["Snapshot_Date"], errors="coerce")
    out = out[logical_dates.notna()].copy()
    logical_dates = logical_dates[logical_dates.notna()]
    out["Snapshot_Date"] = logical_dates.dt.strftime("%Y-%m-%d")

    if "_storage_snapshot_date" in out.columns:
        out["_storage_snapshot_order"] = pd.to_datetime(out["_storage_snapshot_date"], errors="coerce")
    else:
        out["_storage_snapshot_order"] = logical_dates

    out = (
        out.sort_values(["Snapshot_Date", "Market", "Ticker", "_storage_snapshot_order"], na_position="first")
        .drop_duplicates(subset=["Snapshot_Date", "Market", "Ticker"], keep="last")
    )
    out = out[SNAPSHOT_COLS].copy()
    return _to_num(out, FACTOR_COLS)


def _load_existing_snapshots() -> pd.DataFrame:
    try:
        df = _repository().read_history(SNAPSHOT_SHEET, market=None)
    except Exception as exc:
        print(f"[BACKFILL] Storage read skipped for {SNAPSHOT_SHEET}: {type(exc).__name__}: {exc}")
        df = pd.DataFrame()
    if df.empty:
        df = _read_simple_sheet(SNAPSHOT_SHEET)
    return _prepare_snapshot_frame(df)


def _snapshot_dates(months: int, min_age_days: int) -> list[pd.Timestamp]:
    today = pd.Timestamp.today().normalize()
    cutoff = today - pd.Timedelta(days=min_age_days)
    start = today - pd.DateOffset(months=months)
    dates = pd.date_range(start=start, end=cutoff, freq="ME")
    if len(dates) == 0 and start <= cutoff:
        dates = pd.DatetimeIndex([start])
    return [pd.Timestamp(d).normalize() for d in dates if pd.Timestamp(d).normalize() < today]


def _download_prices(tickers: list[str], earliest_snapshot: pd.Timestamp) -> pd.DataFrame:
    if not tickers:
        return pd.DataFrame()
    start = (earliest_snapshot - pd.Timedelta(days=420)).strftime("%Y-%m-%d")
    end = (pd.Timestamp.today() + pd.Timedelta(days=2)).strftime("%Y-%m-%d")
    frames = []
    for i in range(0, len(tickers), BATCH):
        batch = tickers[i:i + BATCH]
        try:
            raw = yf.download(batch, start=start, end=end, auto_adjust=True, progress=False, ignore_tz=True)
            closes = raw["Close"] if isinstance(raw.columns, pd.MultiIndex) else raw
            if isinstance(closes, pd.Series):
                closes = closes.to_frame(name=batch[0])
            frames.append(closes)
        except Exception as exc:
            print(f"[BACKFILL] Price batch error: {exc}")
        time.sleep(PRICE_DELAY)
    if not frames:
        return pd.DataFrame()
    prices = pd.concat(frames, axis=1)
    prices = prices.loc[:, ~prices.columns.duplicated()]
    return prices.sort_index().ffill()


def _row_on_or_before(prices: pd.DataFrame, date_like, offset: int = 0) -> pd.Series:
    idx = prices.index[prices.index.normalize() <= pd.Timestamp(date_like).normalize()]
    if idx.empty:
        return pd.Series(dtype=float)
    pos = len(idx) - 1 - offset
    if pos < 0:
        return pd.Series(dtype=float)
    return prices.loc[idx[pos]]


def _momentum_scores(prices: pd.DataFrame, snapshot_date: pd.Timestamp, market: str) -> pd.Series:
    end_px = _row_on_or_before(prices, snapshot_date)
    px_1m = _row_on_or_before(prices, snapshot_date, offset=21)
    px_3m = _row_on_or_before(prices, snapshot_date, offset=63)
    px_12m = _row_on_or_before(prices, snapshot_date, offset=252)
    if end_px.empty:
        return pd.Series(dtype=float)

    mom_12_1 = (px_1m / px_12m) - 1.0 if not px_1m.empty and not px_12m.empty else pd.Series(dtype=float)
    mom_3 = (end_px / px_3m) - 1.0 if not px_3m.empty else pd.Series(dtype=float)
    mom_12_1 = mom_12_1.replace([np.inf, -np.inf], np.nan)
    mom_3 = mom_3.replace([np.inf, -np.inf], np.nan)
    combined = 0.7 * mom_12_1.rank(pct=True) + 0.3 * mom_3.rank(pct=True)
    combined = combined.reindex(prices.columns).fillna(0.5)
    return combined * BASE_WEIGHTS.get(market, BASE_WEIGHTS["US"])["Momentum_Score"]


def _sector_neutralize(df: pd.DataFrame) -> pd.Series:
    total = pd.to_numeric(df["Total_Score"], errors="coerce")
    out = total.copy()
    sectors = df.get("Sector", pd.Series([""] * len(df), index=df.index)).fillna("").astype(str)
    for sector, idx in sectors.groupby(sectors).groups.items():
        values = total.loc[idx]
        if len(values) < 5 or values.std(ddof=0) == 0:
            out.loc[idx] = values
        else:
            out.loc[idx] = (values - values.mean()) / values.std(ddof=0)
    return out


def _build_market_snapshots(scored: pd.DataFrame, prices: pd.DataFrame, dates: list[pd.Timestamp], market: str) -> pd.DataFrame:
    if scored.empty or prices.empty or not dates:
        return pd.DataFrame(columns=SNAPSHOT_COLS)

    base = scored.copy()
    base = base[base["Ticker"].isin(prices.columns)].copy()
    if base.empty:
        return pd.DataFrame(columns=SNAPSHOT_COLS)

    for col in STATIC_FACTOR_COLS:
        base[col] = pd.to_numeric(base[col], errors="coerce")
        fallback = base[col].median()
        base[col] = base[col].fillna(fallback if pd.notna(fallback) else 0.0)

    rows = []
    for snapshot_date in dates:
        frame = base[["Ticker", "Name", "Sector", *STATIC_FACTOR_COLS]].copy()
        momentum = _momentum_scores(prices, snapshot_date, market)
        frame["Momentum_Score"] = frame["Ticker"].map(momentum).fillna(BASE_WEIGHTS[market]["Momentum_Score"] * 0.5)
        frame["Total_Score"] = frame["Value_Score"] + frame["Quality_Score"] + frame["Momentum_Score"]
        frame["Score_Neutral"] = _sector_neutralize(frame)
        frame["Final_Score"] = 0.6 * frame["Total_Score"].rank(pct=True) + 0.4 * frame["Score_Neutral"].rank(pct=True)
        frame["Combined_Score"] = frame["Final_Score"]
        frame["Snapshot_Date"] = snapshot_date.strftime("%Y-%m-%d")
        frame["Market"] = market
        frame["Snapshot_Source"] = "PROXY_BACKFILL"
        rows.append(frame[SNAPSHOT_COLS])

    out = pd.concat(rows, ignore_index=True)
    for col in FACTOR_COLS:
        out[col] = pd.to_numeric(out[col], errors="coerce").round(4)
    return out


def _write_snapshots(df: pd.DataFrame) -> None:
    out = df[SNAPSHOT_COLS].fillna("").astype(str)
    dual_write_dataframe(SNAPSHOT_SHEET, out, market="GLOBAL")
    try:
        ws = _get_or_create_sheet(SNAPSHOT_SHEET, rows=max(1000, len(out) + 50), cols=len(SNAPSHOT_COLS) + 2)
        ws.resize(rows=max(1000, len(out) + 50), cols=len(SNAPSHOT_COLS) + 2)
        ws.clear()
        ws.update(range_name="A1", values=[SNAPSHOT_COLS] + out.values.tolist(), value_input_option="USER_ENTERED")
    except Exception:
        print(f"[BACKFILL] Sheet write skipped for {SNAPSHOT_SHEET}.")


def _write_log(row: dict) -> None:
    dual_write_dataframe(BACKFILL_LOG_SHEET, pd.DataFrame([row], columns=LOG_COLS), market="GLOBAL")
    try:
        ws = _get_or_create_sheet(BACKFILL_LOG_SHEET, rows=100, cols=len(LOG_COLS) + 2)
        existing = ws.get_all_values()
    except Exception:
        print(f"[BACKFILL] Sheet write skipped for {BACKFILL_LOG_SHEET}.")
        return
    rows = [LOG_COLS]
    if len(existing) >= 2 and existing[0] == LOG_COLS:
        rows += existing[1:]
    rows.append([str(row.get(col, "")) for col in LOG_COLS])
    ws.clear()
    ws.update(range_name="A1", values=rows, value_input_option="USER_ENTERED")


def build_backfill(months: int, min_age_days: int, limit_per_market: int, force: bool) -> tuple[pd.DataFrame, dict]:
    existing = _load_existing_snapshots()
    existing_pairs = set()
    if not existing.empty:
        existing_pairs = set(zip(existing["Snapshot_Date"].astype(str), existing["Market"].astype(str).str.upper()))

    dates = _snapshot_dates(months, min_age_days)
    if not dates:
        return existing, {"rows_added": 0, "dates": [], "us_tickers": 0, "kr_tickers": 0}

    generated_frames = []
    ticker_counts = {}
    for market, sheet in [("US", "US_Scored_Stocks"), ("KR", "KR_Scored_Stocks")]:
        market_dates = [
            date for date in dates
            if force or (date.strftime("%Y-%m-%d"), market) not in existing_pairs
        ]
        if not market_dates:
            ticker_counts[market] = 0
            continue
        scored = _load_scored_sheet(sheet, market, limit=limit_per_market)
        ticker_counts[market] = len(scored)
        tickers = scored["Ticker"].dropna().astype(str).str.strip().tolist()
        if not tickers:
            continue
        print(f"[BACKFILL] {market}: {len(tickers)} tickers, {len(market_dates)} snapshot dates")
        prices = _download_prices(tickers, min(market_dates))
        generated = _build_market_snapshots(scored, prices, market_dates, market)
        if not generated.empty:
            generated_frames.append(generated)

    generated_all = pd.concat(generated_frames, ignore_index=True) if generated_frames else pd.DataFrame(columns=SNAPSHOT_COLS)
    if generated_all.empty:
        return existing, {
            "rows_added": 0,
            "dates": [d.strftime("%Y-%m-%d") for d in dates],
            "us_tickers": ticker_counts.get("US", 0),
            "kr_tickers": ticker_counts.get("KR", 0),
        }

    if force and not existing.empty:
        forced_pairs = set(zip(generated_all["Snapshot_Date"].astype(str), generated_all["Market"].astype(str).str.upper()))
        mask = [
            (str(row["Snapshot_Date"]), str(row["Market"]).upper()) not in forced_pairs
            for _, row in existing.iterrows()
        ]
        existing = existing[mask].copy()

    combined = pd.concat([existing, generated_all], ignore_index=True)
    combined = combined.drop_duplicates(
        subset=["Snapshot_Date", "Market", "Ticker"],
        keep="last",
    ).sort_values(["Snapshot_Date", "Market", "Ticker"])
    return combined.reset_index(drop=True), {
        "rows_added": len(generated_all),
        "dates": sorted(generated_all["Snapshot_Date"].unique().tolist()),
        "us_tickers": ticker_counts.get("US", 0),
        "kr_tickers": ticker_counts.get("KR", 0),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Backfill proxy factor score snapshots")
    parser.add_argument("--months", type=int, default=9, help="Months of monthly proxy snapshots to generate")
    parser.add_argument("--min-age-days", type=int, default=35, help="Skip dates newer than this many days")
    parser.add_argument("--limit-per-market", type=int, default=0, help="Optional top-N scored tickers per market")
    parser.add_argument("--force", action="store_true", help="Regenerate existing backfill dates")
    parser.add_argument("--dry-run", action="store_true", help="Build but do not write")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    print("\n" + "=" * 65)
    print("  FACTOR SNAPSHOT BACKFILL  (proxy bootstrap)")
    print("=" * 65)
    print("[BACKFILL] Static value/quality + historical price momentum; source=PROXY_BACKFILL")

    combined, stats = build_backfill(
        months=args.months,
        min_age_days=args.min_age_days,
        limit_per_market=args.limit_per_market,
        force=args.force,
    )
    rows_added = int(stats.get("rows_added") or 0)
    print(f"[BACKFILL] Rows added: {rows_added}, total rows after merge: {len(combined)}")
    if args.dry_run:
        print("[BACKFILL] Dry run only; no sheets/storage writes.")
        return

    if rows_added <= 0:
        print("[BACKFILL] No new dates to write. Use --force to regenerate existing dates.")
        return

    _write_snapshots(combined)
    log_row = {
        "Generated": datetime.now().strftime("%Y-%m-%d %H:%M"),
        "Mode": "PROXY_BACKFILL",
        "Months": args.months,
        "Dates": ", ".join(stats.get("dates") or []),
        "Rows_Added": rows_added,
        "Rows_Total": len(combined),
        "US_Tickers": stats.get("us_tickers", 0),
        "KR_Tickers": stats.get("kr_tickers", 0),
        "Note": "Static latest value/quality scores plus historical price momentum. Use for warm-up, not final production evidence.",
    }
    _write_log(log_row)
    print("[BACKFILL] Complete. Run make research-quality to rebuild IC/gates/policy/backtest.")


if __name__ == "__main__":
    main()
