# ── Path bootstrap ─────────────────────────────────────────────────────────
import os
import sys

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)
# ───────────────────────────────────────────────────────────────────────────
"""
14_factor_ic_report.py
======================
Walk-forward factor IC monitor.

This script does two jobs:
  1. Store today's factor score snapshot for US and KR scored stocks.
  2. Evaluate old snapshots once enough forward returns are available.

Why snapshots?
  A true Information Coefficient needs "score known at time T" versus
  "return after T". If we only keep the latest score sheet, we cannot know what
  the score looked like in the past. The Factor_Score_Snapshots sheet creates
  that point-in-time audit trail.

Outputs:
  - Factor_Score_Snapshots: point-in-time factor scores, one row per
    market/ticker/snapshot date.
  - Factor_IC_Report: summary and per-snapshot IC diagnostics.

Run:
  python pipeline/14_factor_ic_report.py
"""

import time
import warnings
from datetime import datetime

import gspread
import numpy as np
import pandas as pd
import yfinance as yf
from sheets_client import get_spreadsheet
from quantbridge.storage import QuantRepository
from quantbridge.writers.dual_write import dual_write_dataframe

warnings.filterwarnings("ignore")

SNAPSHOT_SHEET = "Factor_Score_Snapshots"
REPORT_SHEET = "Factor_IC_Report"

SNAPSHOT_COLS = [
    "Snapshot_Date", "Market", "Ticker", "Name", "Sector",
    "Value_Score", "Quality_Score", "Momentum_Score",
    "Total_Score", "Final_Score", "Score_Neutral", "Combined_Score",
    "Business_Quality_Score", "Investability_Score", "Persistence_Quality",
    "Snapshot_Source",
]

SUMMARY_COLS = [
    "Market", "Factor", "Horizon", "Snapshots", "Mean_IC", "Median_IC",
    "Positive_IC_Rate", "Mean_Top_Bottom_Spread", "Mean_Top_Quintile_Return",
    "Mean_Bottom_Quintile_Return", "Mean_Hit_Rate", "Total_Observations",
    "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio", "Evidence_Source",
    "Production_Ready",
    "Generated",
]

DETAIL_COLS = [
    "Snapshot_Date", "Snapshot_Source", "Market", "Factor", "Horizon", "IC", "N",
    "Top_Quintile_Return", "Bottom_Quintile_Return", "Top_Bottom_Spread",
    "Hit_Rate", "Forward_Start", "Forward_End",
]

FACTOR_COLS = [
    "Value_Score", "Quality_Score", "Momentum_Score",
    "Total_Score", "Final_Score", "Score_Neutral", "Combined_Score",
    "Business_Quality_Score", "Investability_Score", "Persistence_Quality",
]

HORIZONS = {
    "1M": 21,
    "3M": 63,
    "6M": 126,
}

MIN_OBS_PER_IC = 20
PRICE_DELAY = 0.4
BATCH = 80
MIN_LIVE_SNAPSHOTS_FOR_PRODUCTION = 3
MAX_PROXY_RATIO_FOR_PRODUCTION = 0.50


def _repository() -> QuantRepository:
    return QuantRepository()


def _spreadsheet():
    return get_spreadsheet()


def _get_or_create_sheet(name: str, rows: int, cols: int):
    try:
        return _spreadsheet().worksheet(name)
    except gspread.exceptions.WorksheetNotFound:
        return _spreadsheet().add_worksheet(title=name, rows=rows, cols=cols)


def _to_num(df: pd.DataFrame, cols: list[str]) -> pd.DataFrame:
    for col in cols:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    return df


def _read_scored_sheet(sheet_name: str, market: str) -> pd.DataFrame:
    try:
        df = _repository().read_dataframe(sheet_name, market=market)
    except Exception as exc:
        print(f"[IC] Storage read skipped for {sheet_name}: {type(exc).__name__}: {exc}")
        df = pd.DataFrame()

    if df.empty:
        try:
            ws = _spreadsheet().worksheet(sheet_name)
            rows = ws.get_all_values()
        except gspread.exceptions.WorksheetNotFound:
            print(f"[IC] Missing scored sheet: {sheet_name}")
            return pd.DataFrame()
        except Exception as exc:
            print(f"[IC] Sheet read skipped for {sheet_name}: {type(exc).__name__}: {exc}")
            return pd.DataFrame()

        if len(rows) < 2:
            return pd.DataFrame()
        df = pd.DataFrame(rows[1:], columns=rows[0])

    if "Ticker" not in df.columns:
        return pd.DataFrame()

    df = df[df["Ticker"].astype(str).str.strip() != ""].copy()
    df["Snapshot_Date"] = datetime.now().strftime("%Y-%m-%d")
    df["Market"] = market
    df["Snapshot_Source"] = "LIVE_DAILY"

    for col in SNAPSHOT_COLS:
        if col not in df.columns:
            df[col] = ""

    df = df[SNAPSHOT_COLS].copy()
    return _to_num(df, FACTOR_COLS)


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


def _read_snapshots() -> pd.DataFrame:
    try:
        df = _repository().read_history(SNAPSHOT_SHEET, market=None)
    except Exception as exc:
        print(f"[IC] Storage read skipped for {SNAPSHOT_SHEET}: {type(exc).__name__}: {exc}")
        df = pd.DataFrame()
    if not df.empty:
        return _prepare_snapshot_frame(df)

    try:
        ws = _spreadsheet().worksheet(SNAPSHOT_SHEET)
        rows = ws.get_all_values()
    except gspread.exceptions.WorksheetNotFound:
        return pd.DataFrame(columns=SNAPSHOT_COLS)
    except Exception as exc:
        print(f"[IC] Sheet read skipped for {SNAPSHOT_SHEET}: {type(exc).__name__}: {exc}")
        return pd.DataFrame(columns=SNAPSHOT_COLS)

    if len(rows) < 2:
        return pd.DataFrame(columns=SNAPSHOT_COLS)

    df = pd.DataFrame(rows[1:], columns=rows[0])
    return _prepare_snapshot_frame(df)


def _write_snapshots(df: pd.DataFrame) -> None:
    out = df[SNAPSHOT_COLS].fillna("").astype(str)
    try:
        ws = _get_or_create_sheet(SNAPSHOT_SHEET, rows=max(1000, len(df) + 50), cols=len(SNAPSHOT_COLS) + 2)
        ws.resize(rows=max(1000, len(out) + 50), cols=len(SNAPSHOT_COLS) + 2)
        ws.clear()
        ws.update(range_name="A1", values=[SNAPSHOT_COLS] + out.values.tolist(), value_input_option="USER_ENTERED")
    except Exception:
        print(f"[IC] Sheet write skipped for {SNAPSHOT_SHEET}.")
    dual_write_dataframe(SNAPSHOT_SHEET, out, market="GLOBAL")


def update_score_snapshots() -> pd.DataFrame:
    existing = _read_snapshots()
    current = pd.concat(
        [
            _read_scored_sheet("US_Scored_Stocks", "US"),
            _read_scored_sheet("KR_Scored_Stocks", "KR"),
        ],
        ignore_index=True,
    )

    if current.empty:
        print("[IC] No current scored rows found. Snapshot sheet unchanged.")
        return existing

    today = datetime.now().strftime("%Y-%m-%d")
    if not existing.empty:
        existing = existing[
            ~(
                existing["Snapshot_Date"].astype(str).eq(today)
                & existing["Market"].astype(str).isin(current["Market"].unique())
            )
        ].copy()

    combined = pd.concat([existing, current], ignore_index=True)
    combined = combined.drop_duplicates(
        subset=["Snapshot_Date", "Market", "Ticker"],
        keep="last",
    ).sort_values(["Snapshot_Date", "Market", "Ticker"])

    _write_snapshots(combined)
    print(f"[IC] Snapshot updated: +{len(current)} current rows, total={len(combined)}")
    return combined


def _download_prices(tickers: list[str], start_date: str) -> pd.DataFrame:
    frames = []
    if not tickers:
        return pd.DataFrame()

    start = (pd.Timestamp(start_date) - pd.Timedelta(days=10)).strftime("%Y-%m-%d")
    end = (pd.Timestamp.today() + pd.Timedelta(days=2)).strftime("%Y-%m-%d")

    for i in range(0, len(tickers), BATCH):
        batch = tickers[i:i + BATCH]
        try:
            raw = yf.download(batch, start=start, end=end, auto_adjust=True, progress=False, ignore_tz=True)
            closes = raw["Close"] if isinstance(raw.columns, pd.MultiIndex) else raw
            if isinstance(closes, pd.Series):
                closes = closes.to_frame(name=batch[0])
            frames.append(closes)
        except Exception as exc:
            print(f"[IC] Price batch error: {exc}")
        time.sleep(PRICE_DELAY)

    if not frames:
        return pd.DataFrame()

    prices = pd.concat(frames, axis=1)
    prices = prices.loc[:, ~prices.columns.duplicated()]
    return prices.sort_index().ffill()


def _price_on_or_after(prices: pd.DataFrame, date_like) -> pd.Series:
    ts = pd.Timestamp(date_like).normalize()
    idx = prices.index[prices.index.normalize() >= ts]
    if idx.empty:
        return pd.Series(dtype=float)
    return prices.loc[idx[0]]


def _forward_returns(prices: pd.DataFrame, snapshot_date: str, horizon_days: int) -> tuple[pd.Series, str, str]:
    start_px = _price_on_or_after(prices, snapshot_date)
    end_date = pd.Timestamp(snapshot_date) + pd.Timedelta(days=horizon_days)
    end_px = _price_on_or_after(prices, end_date)

    if start_px.empty or end_px.empty:
        return pd.Series(dtype=float), "", ""

    returns = (end_px / start_px) - 1.0
    returns = returns.replace([np.inf, -np.inf], np.nan).dropna()
    return returns, start_px.name.strftime("%Y-%m-%d"), end_px.name.strftime("%Y-%m-%d")


def _spearman_ic(scores: pd.Series, returns: pd.Series) -> float:
    aligned = pd.concat([scores.rename("score"), returns.rename("return")], axis=1).dropna()
    if len(aligned) < MIN_OBS_PER_IC:
        return float("nan")
    if aligned["score"].nunique() < 2 or aligned["return"].nunique() < 2:
        return float("nan")
    return float(aligned["score"].corr(aligned["return"], method="spearman"))


def _quintile_stats(scores: pd.Series, returns: pd.Series) -> tuple[float, float, float]:
    aligned = pd.concat([scores.rename("score"), returns.rename("return")], axis=1).dropna()
    if len(aligned) < MIN_OBS_PER_IC or aligned["score"].nunique() < 5:
        return float("nan"), float("nan"), float("nan")

    top_cut = aligned["score"].quantile(0.80)
    bot_cut = aligned["score"].quantile(0.20)
    top = aligned[aligned["score"] >= top_cut]["return"]
    bottom = aligned[aligned["score"] <= bot_cut]["return"]
    if top.empty or bottom.empty:
        return float("nan"), float("nan"), float("nan")

    hit_rate = float((top > 0).mean())
    return float(top.mean()), float(bottom.mean()), hit_rate


def _source_label(values: pd.Series) -> str:
    sources = sorted({
        str(value).strip().upper() or "UNKNOWN"
        for value in values.dropna().tolist()
    })
    if not sources:
        return "UNKNOWN"
    if len(sources) == 1:
        return sources[0]
    return "MIXED"


def _evidence_metrics(group: pd.DataFrame) -> dict:
    if group.empty or "Snapshot_Date" not in group.columns:
        return {
            "Live_Snapshots": 0,
            "Proxy_Snapshots": 0,
            "Proxy_Ratio": 0.0,
            "Evidence_Source": "UNKNOWN",
            "Production_Ready": "FALSE",
        }

    sources = group.get("Snapshot_Source", pd.Series(["UNKNOWN"] * len(group), index=group.index))
    sources = sources.fillna("").astype(str).str.upper().replace("", "UNKNOWN")
    dates = group["Snapshot_Date"].astype(str)
    total_dates = dates.nunique()
    live_dates = dates[sources.eq("LIVE_DAILY")].nunique()
    proxy_dates = dates[sources.eq("PROXY_BACKFILL")].nunique()
    proxy_ratio = proxy_dates / total_dates if total_dates else 0.0

    if live_dates and proxy_dates:
        evidence_source = "MIXED"
    elif live_dates:
        evidence_source = "LIVE_ONLY"
    elif proxy_dates:
        evidence_source = "PROXY_ONLY"
    else:
        evidence_source = "UNKNOWN"

    production_ready = (
        live_dates >= MIN_LIVE_SNAPSHOTS_FOR_PRODUCTION
        and proxy_ratio <= MAX_PROXY_RATIO_FOR_PRODUCTION
    )
    return {
        "Live_Snapshots": int(live_dates),
        "Proxy_Snapshots": int(proxy_dates),
        "Proxy_Ratio": float(proxy_ratio),
        "Evidence_Source": evidence_source,
        "Production_Ready": "TRUE" if production_ready else "FALSE",
    }


def build_ic_report(snapshots: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    if snapshots.empty:
        return pd.DataFrame(), pd.DataFrame()

    snapshots = snapshots.copy()
    snapshots["Snapshot_Date"] = pd.to_datetime(snapshots["Snapshot_Date"], errors="coerce")
    snapshots = snapshots.dropna(subset=["Snapshot_Date"])
    if "Snapshot_Source" not in snapshots.columns:
        snapshots["Snapshot_Source"] = "UNKNOWN"
    snapshots["Snapshot_Source"] = (
        snapshots["Snapshot_Source"].fillna("").astype(str).str.upper().replace("", "UNKNOWN")
    )

    today = pd.Timestamp.today().normalize()
    min_horizon = min(HORIZONS.values())
    snapshots = snapshots[snapshots["Snapshot_Date"] + pd.Timedelta(days=min_horizon) <= today].copy()
    if snapshots.empty:
        return pd.DataFrame(), pd.DataFrame()

    detail_rows = []

    for market, df_market in snapshots.groupby("Market"):
        tickers = sorted(df_market["Ticker"].dropna().astype(str).str.strip().unique())
        oldest_needed = df_market["Snapshot_Date"].min().strftime("%Y-%m-%d")
        print(f"[IC] {market}: downloading prices for {len(tickers)} snapshot tickers")
        prices = _download_prices(tickers, oldest_needed)
        if prices.empty:
            print(f"[IC] {market}: no price data")
            continue

        for snap_date, snap_df in df_market.groupby("Snapshot_Date"):
            snap_str = snap_date.strftime("%Y-%m-%d")
            snapshot_source = _source_label(snap_df["Snapshot_Source"])
            for horizon_label, horizon_days in HORIZONS.items():
                if snap_date + pd.Timedelta(days=horizon_days) > today:
                    continue

                fwd, fwd_start, fwd_end = _forward_returns(prices, snap_str, horizon_days)
                if fwd.empty:
                    continue

                snap_scores = snap_df.set_index("Ticker")
                for factor in FACTOR_COLS:
                    if factor not in snap_scores.columns:
                        continue
                    scores = pd.to_numeric(snap_scores[factor], errors="coerce")
                    aligned = pd.concat([scores.rename("score"), fwd.rename("return")], axis=1).dropna()
                    if len(aligned) < MIN_OBS_PER_IC:
                        continue

                    ic = _spearman_ic(aligned["score"], aligned["return"])
                    top_ret, bottom_ret, hit_rate = _quintile_stats(aligned["score"], aligned["return"])
                    spread = top_ret - bottom_ret if pd.notna(top_ret) and pd.notna(bottom_ret) else float("nan")

                    detail_rows.append({
                        "Snapshot_Date": snap_str,
                        "Snapshot_Source": snapshot_source,
                        "Market": market,
                        "Factor": factor,
                        "Horizon": horizon_label,
                        "IC": ic,
                        "N": len(aligned),
                        "Top_Quintile_Return": top_ret,
                        "Bottom_Quintile_Return": bottom_ret,
                        "Top_Bottom_Spread": spread,
                        "Hit_Rate": hit_rate,
                        "Forward_Start": fwd_start,
                        "Forward_End": fwd_end,
                    })

    detail = pd.DataFrame(detail_rows)
    if detail.empty:
        return pd.DataFrame(), detail

    generated = datetime.now().strftime("%Y-%m-%d %H:%M")
    summary_rows = []
    for (market, factor, horizon), grp in detail.groupby(["Market", "Factor", "Horizon"]):
        valid_ic = grp["IC"].dropna()
        row = {
            "Market": market,
            "Factor": factor,
            "Horizon": horizon,
            "Snapshots": int(grp["Snapshot_Date"].nunique()),
            "Mean_IC": valid_ic.mean() if len(valid_ic) else np.nan,
            "Median_IC": valid_ic.median() if len(valid_ic) else np.nan,
            "Positive_IC_Rate": (valid_ic > 0).mean() if len(valid_ic) else np.nan,
            "Mean_Top_Bottom_Spread": grp["Top_Bottom_Spread"].mean(),
            "Mean_Top_Quintile_Return": grp["Top_Quintile_Return"].mean(),
            "Mean_Bottom_Quintile_Return": grp["Bottom_Quintile_Return"].mean(),
            "Mean_Hit_Rate": grp["Hit_Rate"].mean(),
            "Total_Observations": int(grp["N"].sum()),
            "Generated": generated,
        }
        row.update(_evidence_metrics(grp))
        summary_rows.append(row)

    summary = pd.DataFrame(summary_rows).sort_values(["Market", "Horizon", "Mean_IC"], ascending=[True, True, False])
    detail = detail.sort_values(["Market", "Snapshot_Date", "Horizon", "Factor"])
    return summary, detail


def _fmt_df(df: pd.DataFrame, cols: list[str]) -> pd.DataFrame:
    if df.empty:
        return pd.DataFrame(columns=cols)
    out = df.copy()
    for col in cols:
        if col not in out.columns:
            out[col] = ""
    for col in out.columns:
        if pd.api.types.is_numeric_dtype(out[col]):
            out[col] = out[col].map(lambda x: "" if pd.isna(x) else round(float(x), 4))
    return out[cols].fillna("").astype(str)


def write_report(summary: pd.DataFrame, detail: pd.DataFrame) -> None:
    generated = datetime.now().strftime("%Y-%m-%d %H:%M")

    if summary.empty:
        rows = [
            ["-- Factor IC Report --", ""],
            ["Generated", generated],
            ["Status", "Score snapshots updated. IC requires aged snapshots with forward returns."],
            ["Horizons", ", ".join(f"{k}={v} trading days" for k, v in HORIZONS.items())],
            ["Minimum observations per IC", str(MIN_OBS_PER_IC)],
        ]
    else:
        summary_out = _fmt_df(summary, SUMMARY_COLS)
        detail_out = _fmt_df(detail, DETAIL_COLS)
        rows = [
            ["-- Factor IC Report --", ""],
            ["Generated", generated],
            ["Method", "Spearman rank IC: factor score at snapshot date vs forward return"],
            ["Horizons", ", ".join(f"{k}={v} trading days" for k, v in HORIZONS.items())],
            ["Minimum observations per IC", str(MIN_OBS_PER_IC)],
            ["", ""],
            SUMMARY_COLS,
        ]
        rows += summary_out.values.tolist()
        rows += [["", ""], DETAIL_COLS]
        rows += detail_out.values.tolist()

    try:
        ws = _get_or_create_sheet(REPORT_SHEET, rows=800, cols=max(len(SUMMARY_COLS), len(DETAIL_COLS)) + 2)
        ws.clear()
        ws.update(range_name="A1", values=rows, value_input_option="USER_ENTERED")
    except Exception:
        print(f"[IC] Sheet write skipped for {REPORT_SHEET}.")
    if not summary.empty:
        dual_write_dataframe(REPORT_SHEET, summary, market="GLOBAL")
    if not detail.empty:
        dual_write_dataframe("Factor_IC_Detail", detail, market="GLOBAL")
    print(f"[IC] Report written: {REPORT_SHEET}")


def main() -> None:
    print("\n" + "=" * 65)
    print("  FACTOR IC REPORT  (walk-forward)")
    print("=" * 65)

    snapshots = update_score_snapshots()
    summary, detail = build_ic_report(snapshots)
    write_report(summary, detail)

    if summary.empty:
        print("[IC] No aged snapshots yet. Future runs will calculate 1M/3M/6M IC.")
    else:
        print(f"[IC] Summary rows: {len(summary)} | detail rows: {len(detail)}")


if __name__ == "__main__":
    main()
