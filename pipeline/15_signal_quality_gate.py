"""
15_signal_quality_gate.py
=========================
Turn factor IC diagnostics into operational quality gates.

The IC report is a research artifact: useful, but still too raw for app/API
surfaces and production decisions. This script converts it into a compact
status table:

  - PASS: enough history and positive signal quality
  - WATCH: usable, but weak or unstable
  - FAIL: enough history and negative signal quality
  - INSUFFICIENT: not enough aged snapshots yet

Output:
  - Signal_Quality_Gates

Run:
  python pipeline/15_signal_quality_gate.py
"""

from __future__ import annotations

import os
import sys
from datetime import datetime
from typing import Any

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

import gspread
import numpy as np
import pandas as pd

from sheets_client import get_spreadsheet
from quantbridge.storage import QuantRepository
from quantbridge.writers.dual_write import dual_write_dataframe


REPORT_SHEET = "Factor_IC_Report"
SNAPSHOT_SHEET = "Factor_Score_Snapshots"
OUTPUT_SHEET = "Signal_Quality_Gates"

SUMMARY_COLS = [
    "Market", "Factor", "Horizon", "Snapshots", "Mean_IC", "Median_IC",
    "Positive_IC_Rate", "Mean_Top_Bottom_Spread", "Mean_Top_Quintile_Return",
    "Mean_Bottom_Quintile_Return", "Mean_Hit_Rate", "Total_Observations",
    "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio", "Evidence_Source",
    "Production_Ready",
    "Generated",
]

OUTPUT_COLS = [
    "Market", "Factor", "Horizon", "Status", "Mean_IC", "Positive_IC_Rate",
    "Mean_Top_Bottom_Spread", "Mean_Hit_Rate", "Snapshots",
    "Total_Observations", "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    "Evidence_Source", "Production_Ready", "Reason", "Recommended_Action",
    "Generated",
]

CORE_FACTORS = [
    "Value_Score",
    "Quality_Score",
    "Momentum_Score",
    "Total_Score",
    "Final_Score",
    "Score_Neutral",
    "Combined_Score",
    "Business_Quality_Score",
    "Investability_Score",
    "Persistence_Quality",
]

HORIZONS = ["1M", "3M", "6M"]
MARKETS = ["US", "KR"]

MIN_SNAPSHOTS = 3
MIN_OBSERVATIONS = 60
PASS_IC = 0.03
WATCH_IC = 0.00
PASS_POSITIVE_RATE = 0.55
WATCH_POSITIVE_RATE = 0.45


def _repository() -> QuantRepository:
    return QuantRepository()


def _spreadsheet():
    return get_spreadsheet()


def _to_num(df: pd.DataFrame, cols: list[str]) -> pd.DataFrame:
    out = df.copy()
    for col in cols:
        if col in out.columns:
            out[col] = pd.to_numeric(out[col], errors="coerce")
    return out


def _read_storage_summary() -> pd.DataFrame:
    try:
        df = _repository().read_dataframe(REPORT_SHEET, market=None)
    except Exception as exc:
        print(f"[QUALITY] Storage read skipped: {type(exc).__name__}: {exc}")
        return pd.DataFrame(columns=SUMMARY_COLS)

    if df.empty or not set(["Market", "Factor", "Horizon"]).issubset(df.columns):
        return pd.DataFrame(columns=SUMMARY_COLS)
    for col in SUMMARY_COLS:
        if col not in df.columns:
            df[col] = ""
    return _to_num(df[SUMMARY_COLS].copy(), [
        "Snapshots", "Mean_IC", "Median_IC", "Positive_IC_Rate",
        "Mean_Top_Bottom_Spread", "Mean_Top_Quintile_Return",
        "Mean_Bottom_Quintile_Return", "Mean_Hit_Rate", "Total_Observations",
        "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    ])


def _read_sheet_summary() -> pd.DataFrame:
    try:
        rows = _spreadsheet().worksheet(REPORT_SHEET).get_all_values()
    except gspread.exceptions.WorksheetNotFound:
        return pd.DataFrame(columns=SUMMARY_COLS)
    except Exception as exc:
        print(f"[QUALITY] Sheet read skipped: {type(exc).__name__}: {exc}")
        return pd.DataFrame(columns=SUMMARY_COLS)

    if not rows:
        return pd.DataFrame(columns=SUMMARY_COLS)

    header_idx = next((idx for idx, row in enumerate(rows) if row[:len(SUMMARY_COLS)] == SUMMARY_COLS), None)
    if header_idx is None:
        return pd.DataFrame(columns=SUMMARY_COLS)

    data_rows = []
    for row in rows[header_idx + 1:]:
        if not any(str(cell).strip() for cell in row):
            break
        if len(row) < len(SUMMARY_COLS):
            row = row + [""] * (len(SUMMARY_COLS) - len(row))
        data_rows.append(row[:len(SUMMARY_COLS)])

    if not data_rows:
        return pd.DataFrame(columns=SUMMARY_COLS)
    df = pd.DataFrame(data_rows, columns=SUMMARY_COLS)
    return _to_num(df, [
        "Snapshots", "Mean_IC", "Median_IC", "Positive_IC_Rate",
        "Mean_Top_Bottom_Spread", "Mean_Top_Quintile_Return",
        "Mean_Bottom_Quintile_Return", "Mean_Hit_Rate", "Total_Observations",
        "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    ])


def load_ic_summary() -> pd.DataFrame:
    storage = _read_storage_summary()
    if not storage.empty:
        return storage
    return _read_sheet_summary()


def _snapshot_count() -> int:
    try:
        df = _repository().read_history(SNAPSHOT_SHEET, market=None)
        if not df.empty:
            if {"Snapshot_Date", "Market", "Ticker"}.issubset(df.columns):
                return len(df.drop_duplicates(["Snapshot_Date", "Market", "Ticker"]))
            return len(df)
    except Exception:
        pass

    try:
        rows = _spreadsheet().worksheet(SNAPSHOT_SHEET).get_all_values()
        return max(len(rows) - 1, 0)
    except Exception:
        return 0


def _clean_number(value: Any) -> float | None:
    try:
        number = float(value)
        return None if np.isnan(number) else number
    except (TypeError, ValueError):
        return None


def _is_true(value: Any) -> bool:
    return str(value or "").strip().upper() in {"TRUE", "1", "YES", "Y"}


def _readiness_context(row: pd.Series) -> str:
    evidence = str(row.get("Evidence_Source") or "UNKNOWN").upper()
    live = int(_clean_number(row.get("Live_Snapshots")) or 0)
    proxy = int(_clean_number(row.get("Proxy_Snapshots")) or 0)
    proxy_ratio = _clean_number(row.get("Proxy_Ratio"))
    proxy_text = f"{proxy_ratio:.0%}" if proxy_ratio is not None else "unknown"
    return f"Evidence source={evidence}; live snapshots={live}, proxy snapshots={proxy}, proxy ratio={proxy_text}."


def _classify(row: pd.Series) -> tuple[str, str, str]:
    snapshots = _clean_number(row.get("Snapshots")) or 0
    observations = _clean_number(row.get("Total_Observations")) or 0
    mean_ic = _clean_number(row.get("Mean_IC"))
    positive_rate = _clean_number(row.get("Positive_IC_Rate"))
    spread = _clean_number(row.get("Mean_Top_Bottom_Spread"))

    if snapshots < MIN_SNAPSHOTS:
        return (
            "INSUFFICIENT",
            f"Only {int(snapshots)} aged snapshots; need at least {MIN_SNAPSHOTS}.",
            "Keep collecting daily score snapshots before changing factor weights.",
        )

    if observations < MIN_OBSERVATIONS:
        return (
            "INSUFFICIENT",
            f"Only {int(observations)} forward-return observations; need at least {MIN_OBSERVATIONS}.",
            "Wait for more observations or broaden the evaluated universe.",
        )

    if mean_ic is None:
        return (
            "INSUFFICIENT",
            "Mean IC is unavailable.",
            "Check Factor_IC_Report data coverage and price availability.",
        )

    if mean_ic >= PASS_IC and (positive_rate is None or positive_rate >= PASS_POSITIVE_RATE) and (spread is None or spread >= 0):
        return (
            "PASS",
            "Signal shows positive rank IC with acceptable consistency.",
            "Keep the factor active and monitor for decay.",
        )

    if mean_ic >= WATCH_IC and (positive_rate is None or positive_rate >= WATCH_POSITIVE_RATE):
        return (
            "WATCH",
            "Signal is positive but not strong enough for a confident pass.",
            "Keep the factor active with reduced confidence; revisit after more snapshots.",
        )

    return (
        "FAIL",
        "Signal has negative or unstable IC after enough observations.",
        "Review factor definition and consider down-weighting until it recovers.",
    )


def build_quality_gates(summary: pd.DataFrame) -> pd.DataFrame:
    generated = datetime.now().strftime("%Y-%m-%d %H:%M")
    if summary.empty:
        count = _snapshot_count()
        return pd.DataFrame([{
            "Market": "GLOBAL",
            "Factor": "ALL",
            "Horizon": "ALL",
            "Status": "INSUFFICIENT",
            "Mean_IC": "",
            "Positive_IC_Rate": "",
            "Mean_Top_Bottom_Spread": "",
            "Mean_Hit_Rate": "",
            "Snapshots": count,
            "Total_Observations": 0,
            "Live_Snapshots": 0,
            "Proxy_Snapshots": 0,
            "Proxy_Ratio": 0,
            "Evidence_Source": "UNKNOWN",
            "Production_Ready": "FALSE",
            "Reason": "No aged Factor_IC_Report summary rows are available yet.",
            "Recommended_Action": "Run the pipeline daily; IC gates become meaningful after aged 1M/3M/6M snapshots exist.",
            "Generated": generated,
        }], columns=OUTPUT_COLS)

    rows = []
    summary = summary.copy()
    summary["Market"] = summary["Market"].astype(str).str.upper()
    summary["Factor"] = summary["Factor"].astype(str)
    summary["Horizon"] = summary["Horizon"].astype(str)
    if "Production_Ready" in summary.columns:
        summary["Production_Ready"] = summary["Production_Ready"].fillna("").astype(str).str.upper()
    if "Evidence_Source" in summary.columns:
        summary["Evidence_Source"] = summary["Evidence_Source"].fillna("").astype(str).str.upper()

    for _, row in summary.iterrows():
        if row["Market"] not in MARKETS or row["Horizon"] not in HORIZONS:
            continue
        status, reason, action = _classify(row)
        production_ready = _is_true(row.get("Production_Ready"))
        if not production_ready:
            reason = f"{reason} {_readiness_context(row)}"
            action = "Treat as research warm-up only; wait for LIVE_DAILY confirmation before production weight changes."
        rows.append({
            "Market": row.get("Market", ""),
            "Factor": row.get("Factor", ""),
            "Horizon": row.get("Horizon", ""),
            "Status": status,
            "Mean_IC": row.get("Mean_IC", ""),
            "Positive_IC_Rate": row.get("Positive_IC_Rate", ""),
            "Mean_Top_Bottom_Spread": row.get("Mean_Top_Bottom_Spread", ""),
            "Mean_Hit_Rate": row.get("Mean_Hit_Rate", ""),
            "Snapshots": row.get("Snapshots", ""),
            "Total_Observations": row.get("Total_Observations", ""),
            "Live_Snapshots": row.get("Live_Snapshots", ""),
            "Proxy_Snapshots": row.get("Proxy_Snapshots", ""),
            "Proxy_Ratio": row.get("Proxy_Ratio", ""),
            "Evidence_Source": row.get("Evidence_Source", "UNKNOWN"),
            "Production_Ready": "TRUE" if production_ready else "FALSE",
            "Reason": reason,
            "Recommended_Action": action,
            "Generated": generated,
        })

    if not rows:
        return build_quality_gates(pd.DataFrame())

    out = pd.DataFrame(rows, columns=OUTPUT_COLS)
    status_order = {"FAIL": 0, "WATCH": 1, "INSUFFICIENT": 2, "PASS": 3}
    out["_status_order"] = out["Status"].map(status_order).fillna(9)
    out = out.sort_values(["Market", "Horizon", "_status_order", "Factor"]).drop(columns=["_status_order"])
    return out.reset_index(drop=True)


def _fmt_df(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    for col in OUTPUT_COLS:
        if col not in out.columns:
            out[col] = ""
    for col in ["Mean_IC", "Positive_IC_Rate", "Mean_Top_Bottom_Spread", "Mean_Hit_Rate", "Proxy_Ratio"]:
        if col in out.columns:
            out[col] = pd.to_numeric(out[col], errors="coerce").map(lambda x: "" if pd.isna(x) else round(float(x), 4))
    for col in ["Snapshots", "Total_Observations", "Live_Snapshots", "Proxy_Snapshots"]:
        if col in out.columns:
            out[col] = pd.to_numeric(out[col], errors="coerce").map(lambda x: "" if pd.isna(x) else int(x))
    return out[OUTPUT_COLS].fillna("").astype(str)


def write_quality_gates(df: pd.DataFrame) -> None:
    out = _fmt_df(df)
    try:
        try:
            ws = _spreadsheet().worksheet(OUTPUT_SHEET)
        except gspread.exceptions.WorksheetNotFound:
            ws = _spreadsheet().add_worksheet(title=OUTPUT_SHEET, rows=max(100, len(out) + 10), cols=len(OUTPUT_COLS) + 2)
        ws.clear()
        ws.update(range_name="A1", values=[OUTPUT_COLS] + out.values.tolist(), value_input_option="USER_ENTERED")
    except Exception as exc:
        print(f"[QUALITY] Sheet write skipped: {type(exc).__name__}: {exc}")
    dual_write_dataframe(OUTPUT_SHEET, out, market="GLOBAL")
    print(f"[QUALITY] Wrote {len(out)} rows to {OUTPUT_SHEET}")


def main() -> None:
    print("\n" + "=" * 65)
    print("  SIGNAL QUALITY GATES")
    print("=" * 65)

    summary = load_ic_summary()
    gates = build_quality_gates(summary)
    write_quality_gates(gates)

    counts = gates["Status"].value_counts().to_dict()
    print(f"[QUALITY] Status counts: {counts}")


if __name__ == "__main__":
    main()
