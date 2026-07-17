"""
16_factor_weight_policy.py
==========================
Create observation-only factor weight policy recommendations.

This script reads Signal_Quality_Gates and writes Factor_Weight_Policy. It does
not change scorer weights. It only produces a review table that says whether a
factor should be kept, watched, reduced, or left unchanged because evidence is
insufficient.

Output:
  - Factor_Weight_Policy

Run:
  python pipeline/16_factor_weight_policy.py
"""

from __future__ import annotations

import os
import sys
from datetime import datetime

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

import gspread
import pandas as pd

from sheets_client import get_spreadsheet
from quantbridge.storage import QuantRepository
from quantbridge.writers.dual_write import dual_write_dataframe


INPUT_SHEET = "Signal_Quality_Gates"
OUTPUT_SHEET = "Factor_Weight_Policy"

INPUT_COLS = [
    "Market", "Factor", "Horizon", "Status", "Mean_IC", "Positive_IC_Rate",
    "Mean_Top_Bottom_Spread", "Mean_Hit_Rate", "Snapshots",
    "Total_Observations", "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    "Evidence_Source", "Production_Ready", "Reason", "Recommended_Action",
    "Generated",
]

OUTPUT_COLS = [
    "Market", "Factor", "Policy_Status", "Current_Action", "Adjustment_Bias",
    "Suggested_Multiplier", "Evidence_Status", "Primary_Horizon", "Mean_IC",
    "Positive_IC_Rate", "Snapshots", "Total_Observations", "Live_Snapshots",
    "Proxy_Snapshots", "Proxy_Ratio", "Evidence_Source", "Production_Ready",
    "Reason", "Review_Note", "Generated",
]

FACTOR_BASE_WEIGHTS = {
    "Value_Score": 0.40,
    "Quality_Score": 0.30,
    "Momentum_Score": 0.30,
    "Total_Score": 1.00,
    "Final_Score": 1.00,
    "Score_Neutral": 1.00,
    "Combined_Score": 1.00,
    "Business_Quality_Score": 1.00,
    "Investability_Score": 1.00,
    "Persistence_Quality": 1.00,
}

STATUS_ORDER = {
    "FAIL": 0,
    "WATCH": 1,
    "INSUFFICIENT": 2,
    "PASS": 3,
}

HORIZON_ORDER = {
    "3M": 0,
    "6M": 1,
    "1M": 2,
    "ALL": 3,
}


def _repository() -> QuantRepository:
    return QuantRepository()


def _spreadsheet():
    return get_spreadsheet()


def _to_num(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    for col in [
        "Mean_IC", "Positive_IC_Rate", "Mean_Top_Bottom_Spread",
        "Mean_Hit_Rate", "Snapshots", "Total_Observations",
        "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    ]:
        if col in out.columns:
            out[col] = pd.to_numeric(out[col], errors="coerce")
    return out


def _load_from_storage() -> pd.DataFrame:
    try:
        df = _repository().read_dataframe(INPUT_SHEET, market=None)
    except Exception as exc:
        print(f"[POLICY] Storage read skipped: {type(exc).__name__}: {exc}")
        return pd.DataFrame(columns=INPUT_COLS)
    if df.empty:
        return pd.DataFrame(columns=INPUT_COLS)
    return _to_num(df)


def _load_from_sheet() -> pd.DataFrame:
    try:
        rows = _spreadsheet().worksheet(INPUT_SHEET).get_all_values()
    except gspread.exceptions.WorksheetNotFound:
        return pd.DataFrame(columns=INPUT_COLS)
    except Exception as exc:
        print(f"[POLICY] Sheet read skipped: {type(exc).__name__}: {exc}")
        return pd.DataFrame(columns=INPUT_COLS)
    if len(rows) < 2:
        return pd.DataFrame(columns=INPUT_COLS)
    return _to_num(pd.DataFrame(rows[1:], columns=rows[0]))


def load_quality_gates() -> pd.DataFrame:
    df = _load_from_storage()
    if df.empty:
        df = _load_from_sheet()
    for col in INPUT_COLS:
        if col not in df.columns:
            df[col] = ""
    df = df[INPUT_COLS].copy()
    df["Status"] = df["Status"].fillna("").astype(str).str.upper()
    df["Market"] = df["Market"].fillna("").astype(str).str.upper()
    df["Factor"] = df["Factor"].fillna("").astype(str)
    df["Horizon"] = df["Horizon"].fillna("").astype(str)
    df["Evidence_Source"] = df["Evidence_Source"].fillna("").astype(str).str.upper().replace("", "UNKNOWN")
    df["Production_Ready"] = df["Production_Ready"].fillna("").astype(str).str.upper().replace("", "FALSE")
    return _to_num(df)


def _policy_for_status(status: str) -> tuple[str, str, float, str]:
    status = str(status or "").upper()
    if status == "PASS":
        return (
            "KEEP",
            "maintain",
            1.00,
            "Evidence is positive; keep this factor active and monitor for decay.",
        )
    if status == "WATCH":
        return (
            "WATCH",
            "slight_downweight_candidate",
            0.90,
            "Evidence is usable but weak; consider a small reduction only after repeated WATCH readings.",
        )
    if status == "FAIL":
        return (
            "REVIEW",
            "downweight_candidate",
            0.70,
            "Evidence is negative or unstable; review factor definition before reducing production weights.",
        )
    return (
        "HOLD",
        "no_auto_change",
        1.00,
        "Evidence is insufficient; do not change production weights.",
    )


def _is_true(value) -> bool:
    return str(value or "").strip().upper() in {"TRUE", "1", "YES", "Y"}


def _readiness_note(primary: pd.Series) -> str:
    source = str(primary.get("Evidence_Source") or "UNKNOWN").upper()
    live = primary.get("Live_Snapshots", 0)
    proxy = primary.get("Proxy_Snapshots", 0)
    proxy_ratio = pd.to_numeric(primary.get("Proxy_Ratio"), errors="coerce")
    proxy_text = f"{float(proxy_ratio):.0%}" if pd.notna(proxy_ratio) else "unknown"
    return (
        f"Evidence is {source} with live snapshots={live}, proxy snapshots={proxy}, "
        f"proxy ratio={proxy_text}; keep this as observation-only until live evidence confirms it."
    )


def _select_primary_row(group: pd.DataFrame) -> pd.Series:
    group = group.copy()
    group["_status_rank"] = group["Status"].map(STATUS_ORDER).fillna(9)
    group["_horizon_rank"] = group["Horizon"].map(HORIZON_ORDER).fillna(9)
    group = group.sort_values(["_status_rank", "_horizon_rank", "Mean_IC"], ascending=[True, True, True])
    return group.iloc[0]


def build_weight_policy(gates: pd.DataFrame) -> pd.DataFrame:
    generated = datetime.now().strftime("%Y-%m-%d %H:%M")
    if gates.empty:
        return pd.DataFrame([{
            "Market": "GLOBAL",
            "Factor": "ALL",
            "Policy_Status": "HOLD",
            "Current_Action": "no_auto_change",
            "Adjustment_Bias": 1.00,
            "Suggested_Multiplier": 1.00,
            "Evidence_Status": "INSUFFICIENT",
            "Primary_Horizon": "ALL",
            "Mean_IC": "",
            "Positive_IC_Rate": "",
            "Snapshots": 0,
            "Total_Observations": 0,
            "Live_Snapshots": 0,
            "Proxy_Snapshots": 0,
            "Proxy_Ratio": 0,
            "Evidence_Source": "UNKNOWN",
            "Production_Ready": "FALSE",
            "Reason": "Signal_Quality_Gates is empty.",
            "Review_Note": "Collect quality gate data before considering factor weight changes.",
            "Generated": generated,
        }], columns=OUTPUT_COLS)

    rows = []
    usable = gates[gates["Factor"].astype(str).str.strip() != ""].copy()
    if usable.empty:
        return build_weight_policy(pd.DataFrame())

    for (market, factor), group in usable.groupby(["Market", "Factor"], dropna=False):
        primary = _select_primary_row(group)
        status = str(primary.get("Status") or "INSUFFICIENT").upper()
        policy_status, action, multiplier, note = _policy_for_status(status)
        production_ready = _is_true(primary.get("Production_Ready"))
        if not production_ready:
            action = "observe_only_proxy"
            note = _readiness_note(primary)
        base_weight = FACTOR_BASE_WEIGHTS.get(str(factor), 1.00)
        rows.append({
            "Market": market or "GLOBAL",
            "Factor": factor or "ALL",
            "Policy_Status": policy_status,
            "Current_Action": action,
            "Adjustment_Bias": round(multiplier, 4),
            "Suggested_Multiplier": round(base_weight * multiplier, 4),
            "Evidence_Status": status,
            "Primary_Horizon": primary.get("Horizon", ""),
            "Mean_IC": primary.get("Mean_IC", ""),
            "Positive_IC_Rate": primary.get("Positive_IC_Rate", ""),
            "Snapshots": primary.get("Snapshots", ""),
            "Total_Observations": primary.get("Total_Observations", ""),
            "Live_Snapshots": primary.get("Live_Snapshots", ""),
            "Proxy_Snapshots": primary.get("Proxy_Snapshots", ""),
            "Proxy_Ratio": primary.get("Proxy_Ratio", ""),
            "Evidence_Source": primary.get("Evidence_Source", "UNKNOWN"),
            "Production_Ready": "TRUE" if production_ready else "FALSE",
            "Reason": primary.get("Reason", ""),
            "Review_Note": note,
            "Generated": generated,
        })

    out = pd.DataFrame(rows, columns=OUTPUT_COLS)
    status_rank = {"REVIEW": 0, "WATCH": 1, "HOLD": 2, "KEEP": 3}
    out["_rank"] = out["Policy_Status"].map(status_rank).fillna(9)
    out = out.sort_values(["Market", "_rank", "Factor"]).drop(columns=["_rank"])
    return out.reset_index(drop=True)


def _fmt_df(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    for col in OUTPUT_COLS:
        if col not in out.columns:
            out[col] = ""
    for col in ["Adjustment_Bias", "Suggested_Multiplier", "Mean_IC", "Positive_IC_Rate", "Proxy_Ratio"]:
        if col in out.columns:
            out[col] = pd.to_numeric(out[col], errors="coerce").map(lambda x: "" if pd.isna(x) else round(float(x), 4))
    for col in ["Snapshots", "Total_Observations", "Live_Snapshots", "Proxy_Snapshots"]:
        if col in out.columns:
            out[col] = pd.to_numeric(out[col], errors="coerce").map(lambda x: "" if pd.isna(x) else int(x))
    return out[OUTPUT_COLS].fillna("").astype(str)


def write_weight_policy(df: pd.DataFrame) -> None:
    out = _fmt_df(df)
    dual_write_dataframe(OUTPUT_SHEET, out, market="GLOBAL")
    try:
        ws = _spreadsheet().worksheet(OUTPUT_SHEET)
    except gspread.exceptions.WorksheetNotFound:
        ws = _spreadsheet().add_worksheet(title=OUTPUT_SHEET, rows=max(100, len(out) + 10), cols=len(OUTPUT_COLS) + 2)
    except Exception as exc:
        print(f"[POLICY] Sheet write skipped: {type(exc).__name__}: {exc}")
        print(f"[POLICY] Wrote {len(out)} rows to local storage {OUTPUT_SHEET}")
        return
    try:
        ws.clear()
        ws.update(range_name="A1", values=[OUTPUT_COLS] + out.values.tolist(), value_input_option="USER_ENTERED")
        print(f"[POLICY] Wrote {len(out)} rows to {OUTPUT_SHEET}")
    except Exception as exc:
        print(f"[POLICY] Sheet write skipped: {type(exc).__name__}: {exc}")
        print(f"[POLICY] Wrote {len(out)} rows to local storage {OUTPUT_SHEET}")


def main() -> None:
    print("\n" + "=" * 65)
    print("  FACTOR WEIGHT POLICY")
    print("=" * 65)
    gates = load_quality_gates()
    policy = build_weight_policy(gates)
    write_weight_policy(policy)
    counts = policy["Policy_Status"].value_counts().to_dict()
    print(f"[POLICY] Status counts: {counts}")
    print("[POLICY] Observation-only: scorer weights were not changed.")


if __name__ == "__main__":
    main()
