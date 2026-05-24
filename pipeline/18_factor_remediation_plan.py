"""
18_factor_remediation_plan.py
=============================
Create an operator-facing remediation plan for weak factor signals.

The quality gate and policy layers intentionally stop short of changing
production scorer weights. This script turns those diagnostics into a concise
work queue: which market/factor needs attention first, why it is weak, and what
action is safe to take next.

Output:
  - Factor_Remediation_Plan

Run:
  python pipeline/18_factor_remediation_plan.py
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
import pandas as pd

from sheets_client import get_spreadsheet
from quantbridge.storage import QuantRepository
from quantbridge.writers.dual_write import dual_write_dataframe


GATES_SHEET = "Signal_Quality_Gates"
POLICY_SHEET = "Factor_Weight_Policy"
BACKTEST_SHEET = "Factor_Policy_Backtest"
OUTPUT_SHEET = "Factor_Remediation_Plan"

GATE_COLS = [
    "Market", "Factor", "Horizon", "Status", "Mean_IC", "Positive_IC_Rate",
    "Mean_Top_Bottom_Spread", "Mean_Hit_Rate", "Snapshots",
    "Total_Observations", "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    "Evidence_Source", "Production_Ready", "Reason", "Recommended_Action",
    "Generated",
]

POLICY_COLS = [
    "Market", "Factor", "Policy_Status", "Current_Action", "Adjustment_Bias",
    "Suggested_Multiplier", "Evidence_Status", "Primary_Horizon", "Mean_IC",
    "Positive_IC_Rate", "Snapshots", "Total_Observations", "Live_Snapshots",
    "Proxy_Snapshots", "Proxy_Ratio", "Evidence_Source", "Production_Ready",
    "Reason", "Review_Note", "Generated",
]

BACKTEST_COLS = [
    "Market", "Horizon", "Status", "Base_Weighted_IC", "Policy_Weighted_IC",
    "IC_Delta", "Base_Top_Bottom_Spread", "Policy_Top_Bottom_Spread",
    "Spread_Delta", "Base_Hit_Rate", "Policy_Hit_Rate", "Turnover_Estimate",
    "Snapshots", "Total_Observations", "Live_Snapshots", "Proxy_Snapshots",
    "Proxy_Ratio", "Evidence_Source", "Production_Ready", "Decision",
    "Reason", "Generated",
]

OUTPUT_COLS = [
    "Priority", "Market", "Factor", "Severity", "Worst_Status",
    "Worst_Horizon", "Mean_IC", "Positive_IC_Rate", "Top_Bottom_Spread",
    "Policy_Status", "Current_Action", "Policy_Backtest_Status", "IC_Delta",
    "Production_Ready", "Evidence_Source", "Root_Cause",
    "Remediation_Action", "Success_Criteria", "Review_Cadence", "Generated",
]

STATUS_RANK = {
    "FAIL": 0,
    "WATCH": 1,
    "INSUFFICIENT": 2,
    "PASS": 3,
}

HORIZON_RANK = {
    "3M": 0,
    "6M": 1,
    "1M": 2,
    "ALL": 3,
}

AGGREGATE_FACTORS = {"Total_Score", "Final_Score", "Score_Neutral", "Combined_Score"}


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


def _load_storage(sheet_name: str, cols: list[str], num_cols: list[str]) -> pd.DataFrame:
    try:
        df = _repository().read_dataframe(sheet_name)
    except Exception as exc:
        print(f"[REMEDIATION] Storage read skipped for {sheet_name}: {type(exc).__name__}: {exc}")
        return pd.DataFrame(columns=cols)
    if df.empty:
        return pd.DataFrame(columns=cols)
    for col in cols:
        if col not in df.columns:
            df[col] = ""
    return _to_num(df[cols].copy(), num_cols)


def _load_sheet(sheet_name: str, cols: list[str], num_cols: list[str]) -> pd.DataFrame:
    try:
        rows = _spreadsheet().worksheet(sheet_name).get_all_values()
    except gspread.exceptions.WorksheetNotFound:
        return pd.DataFrame(columns=cols)
    except Exception as exc:
        print(f"[REMEDIATION] Sheet read skipped for {sheet_name}: {type(exc).__name__}: {exc}")
        return pd.DataFrame(columns=cols)
    if len(rows) < 2:
        return pd.DataFrame(columns=cols)
    header = [str(col).strip() for col in rows[0]]
    data = pd.DataFrame(rows[1:], columns=header)
    for col in cols:
        if col not in data.columns:
            data[col] = ""
    return _to_num(data[cols].copy(), num_cols)


def _load_dataset(sheet_name: str, cols: list[str], num_cols: list[str]) -> pd.DataFrame:
    df = _load_storage(sheet_name, cols, num_cols)
    if df.empty:
        df = _load_sheet(sheet_name, cols, num_cols)
    return df


def load_inputs() -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    gates = _load_dataset(GATES_SHEET, GATE_COLS, [
        "Mean_IC", "Positive_IC_Rate", "Mean_Top_Bottom_Spread",
        "Mean_Hit_Rate", "Snapshots", "Total_Observations",
        "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    ])
    policy = _load_dataset(POLICY_SHEET, POLICY_COLS, [
        "Adjustment_Bias", "Suggested_Multiplier", "Mean_IC",
        "Positive_IC_Rate", "Snapshots", "Total_Observations",
        "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    ])
    backtest = _load_dataset(BACKTEST_SHEET, BACKTEST_COLS, [
        "Base_Weighted_IC", "Policy_Weighted_IC", "IC_Delta",
        "Base_Top_Bottom_Spread", "Policy_Top_Bottom_Spread",
        "Spread_Delta", "Base_Hit_Rate", "Policy_Hit_Rate",
        "Turnover_Estimate", "Snapshots", "Total_Observations",
        "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
    ])
    for df in (gates, policy, backtest):
        for col in ["Market", "Status", "Policy_Status", "Evidence_Status", "Evidence_Source", "Production_Ready"]:
            if col in df.columns:
                df[col] = df[col].fillna("").astype(str).str.upper()
        if "Factor" in df.columns:
            df["Factor"] = df["Factor"].fillna("").astype(str)
        if "Horizon" in df.columns:
            df["Horizon"] = df["Horizon"].fillna("").astype(str)
    return gates, policy, backtest


def _is_true(value: Any) -> bool:
    return str(value or "").strip().upper() in {"TRUE", "1", "YES", "Y"}


def _num(value: Any) -> float | None:
    value = pd.to_numeric(value, errors="coerce")
    if pd.isna(value):
        return None
    return float(value)


def _primary_gate(group: pd.DataFrame) -> pd.Series:
    group = group.copy()
    group["_status_rank"] = group["Status"].map(STATUS_RANK).fillna(9)
    group["_horizon_rank"] = group["Horizon"].map(HORIZON_RANK).fillna(9)
    group["_mean_ic_sort"] = pd.to_numeric(group.get("Mean_IC"), errors="coerce").fillna(999)
    group = group.sort_values(["_status_rank", "_horizon_rank", "_mean_ic_sort"], ascending=[True, True, True])
    return group.iloc[0]


def _severity(row: pd.Series) -> str:
    status = str(row.get("Status") or "").upper()
    ready = _is_true(row.get("Production_Ready"))
    if status == "FAIL":
        return "HIGH" if ready else "OBSERVE_HIGH"
    if status == "WATCH":
        return "MEDIUM" if ready else "OBSERVE_MEDIUM"
    if status == "INSUFFICIENT":
        return "LOW_DATA"
    return "OK"


def _priority_score(row: pd.Series) -> int:
    status = str(row.get("Status") or "").upper()
    base = {"FAIL": 80, "WATCH": 50, "INSUFFICIENT": 25, "PASS": 5}.get(status, 10)
    mean_ic = _num(row.get("Mean_IC"))
    positive = _num(row.get("Positive_IC_Rate"))
    spread = _num(row.get("Mean_Top_Bottom_Spread"))
    score = base
    if mean_ic is not None and mean_ic < 0:
        score += min(25, int(abs(mean_ic) * 100))
    if positive is not None and positive < 0.45:
        score += int((0.45 - positive) * 20)
    if spread is not None and spread < 0:
        score += min(15, int(abs(spread) * 20))
    if not _is_true(row.get("Production_Ready")):
        score -= 5
    return max(score, 1)


def _root_cause(factor: str, row: pd.Series) -> str:
    status = str(row.get("Status") or "").upper()
    mean_ic = _num(row.get("Mean_IC"))
    positive = _num(row.get("Positive_IC_Rate"))
    spread = _num(row.get("Mean_Top_Bottom_Spread"))
    ready = _is_true(row.get("Production_Ready"))

    parts = []
    if not ready:
        parts.append("Evidence is still proxy-dominated, so this is a research warning rather than a production weight change.")
    if status == "PASS":
        parts.append("Factor is passing the current IC gate.")
    elif status == "INSUFFICIENT":
        parts.append("Not enough aged forward-return evidence is available.")
    else:
        if mean_ic is not None and mean_ic < 0:
            parts.append("Higher factor ranks have underperformed lower ranks on average.")
        if positive is not None and positive < 0.45:
            parts.append("The IC sign is inconsistent across snapshots.")
        if spread is not None and spread < 0:
            parts.append("Top-quintile returns are below bottom-quintile returns.")
        if not parts or (len(parts) == 1 and not ready):
            parts.append("Signal is weak and does not clear the current reliability threshold.")

    if factor in AGGREGATE_FACTORS:
        parts.append("This is an aggregate score, so the likely source is one or more child factors or the current blend.")
    elif factor == "Value_Score":
        parts.append("Value may be penalizing growth/outlier names or using stale valuation anchors.")
    elif factor == "Quality_Score":
        parts.append("Quality may depend on fundamentals with uneven coverage, especially for KR small caps.")
    elif factor == "Momentum_Score":
        parts.append("Momentum may be too short, too reversal-prone, or dominated by recent spikes.")
    return " ".join(parts)


def _action(factor: str, row: pd.Series, policy_row: pd.Series | None, backtest_row: pd.Series | None) -> str:
    status = str(row.get("Status") or "").upper()
    ready = _is_true(row.get("Production_Ready"))
    if status == "PASS":
        return "Keep active; continue daily IC monitoring."
    if status == "INSUFFICIENT":
        return "Collect more aged LIVE_DAILY snapshots before changing factor logic."

    prefix = "Keep observation-only for now; " if not ready else ""
    if factor in AGGREGATE_FACTORS:
        action = "diagnose child Value/Quality/Momentum legs before editing the aggregate blend."
    elif factor == "Value_Score":
        action = "audit valuation direction, winsorize extreme PER/PBR/PEG inputs, and compare sector-neutral value ranks."
    elif factor == "Quality_Score":
        action = "audit missing/zero ROIC and margin fields, especially KR Naver/yfinance mismatches, then retest quality ranks."
    elif factor == "Momentum_Score":
        action = "compare 1M reversal, 3M, and 12M-1M momentum variants; avoid overweighting single-spike moves."
    else:
        action = "review factor construction and rerun IC after the next scored snapshot."

    if policy_row is not None:
        policy_status = str(policy_row.get("Policy_Status") or "").upper()
        if policy_status == "REVIEW":
            action += " The policy layer already marks it as REVIEW."
        elif policy_status == "WATCH":
            action += " The policy layer marks it as WATCH, so use a small-change bias only."
    if backtest_row is not None:
        decision = str(backtest_row.get("Decision") or "").upper()
        if decision:
            action += f" Policy backtest decision is {decision}."
    return prefix + action


def _success_criteria(row: pd.Series) -> str:
    status = str(row.get("Status") or "").upper()
    if status == "PASS":
        return "Maintain PASS with Mean_IC >= 0.03 and Positive_IC_Rate >= 0.55."
    if status == "INSUFFICIENT":
        return "Reach at least 3 aged snapshots and 60 forward-return observations."
    return "Recover to WATCH or PASS for two consecutive research-quality runs without negative top-bottom spread."


def build_remediation_plan(gates: pd.DataFrame, policy: pd.DataFrame, backtest: pd.DataFrame) -> pd.DataFrame:
    generated = datetime.now().strftime("%Y-%m-%d %H:%M")
    if gates.empty:
        return pd.DataFrame([{
            "Priority": 1,
            "Market": "GLOBAL",
            "Factor": "ALL",
            "Severity": "LOW_DATA",
            "Worst_Status": "INSUFFICIENT",
            "Worst_Horizon": "ALL",
            "Mean_IC": "",
            "Positive_IC_Rate": "",
            "Top_Bottom_Spread": "",
            "Policy_Status": "HOLD",
            "Current_Action": "no_auto_change",
            "Policy_Backtest_Status": "INSUFFICIENT",
            "IC_Delta": "",
            "Production_Ready": "FALSE",
            "Evidence_Source": "UNKNOWN",
            "Root_Cause": "Signal_Quality_Gates is empty.",
            "Remediation_Action": "Run make research-quality after the next pipeline snapshot.",
            "Success_Criteria": "Generate Signal_Quality_Gates rows.",
            "Review_Cadence": "Daily after research-quality job.",
            "Generated": generated,
        }], columns=OUTPUT_COLS)

    policy_map = {
        (str(row.get("Market") or "").upper(), str(row.get("Factor") or "")): row
        for _, row in policy.iterrows()
    }
    backtest_map = {
        (str(row.get("Market") or "").upper(), str(row.get("Horizon") or "")): row
        for _, row in backtest.iterrows()
    }

    rows = []
    usable = gates[gates["Factor"].astype(str).str.strip() != ""].copy()
    for (market, factor), group in usable.groupby(["Market", "Factor"], dropna=False):
        primary = _primary_gate(group)
        policy_row = policy_map.get((str(market).upper(), str(factor)))
        backtest_row = backtest_map.get((str(market).upper(), str(primary.get("Horizon") or "")))
        severity = _severity(primary)
        rows.append({
            "Priority": _priority_score(primary),
            "Market": market,
            "Factor": factor,
            "Severity": severity,
            "Worst_Status": primary.get("Status", ""),
            "Worst_Horizon": primary.get("Horizon", ""),
            "Mean_IC": primary.get("Mean_IC", ""),
            "Positive_IC_Rate": primary.get("Positive_IC_Rate", ""),
            "Top_Bottom_Spread": primary.get("Mean_Top_Bottom_Spread", ""),
            "Policy_Status": "" if policy_row is None else policy_row.get("Policy_Status", ""),
            "Current_Action": "" if policy_row is None else policy_row.get("Current_Action", ""),
            "Policy_Backtest_Status": "" if backtest_row is None else backtest_row.get("Status", ""),
            "IC_Delta": "" if backtest_row is None else backtest_row.get("IC_Delta", ""),
            "Production_Ready": "TRUE" if _is_true(primary.get("Production_Ready")) else "FALSE",
            "Evidence_Source": primary.get("Evidence_Source", "UNKNOWN"),
            "Root_Cause": _root_cause(str(factor), primary),
            "Remediation_Action": _action(str(factor), primary, policy_row, backtest_row),
            "Success_Criteria": _success_criteria(primary),
            "Review_Cadence": "Daily after research-quality job; graduate only after LIVE_DAILY evidence dominates.",
            "Generated": generated,
        })

    out = pd.DataFrame(rows, columns=OUTPUT_COLS)
    out = out.sort_values(["Priority", "Market", "Factor"], ascending=[False, True, True])
    out["Priority"] = range(1, len(out) + 1)
    return out.reset_index(drop=True)


def _fmt_df(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    for col in OUTPUT_COLS:
        if col not in out.columns:
            out[col] = ""
    for col in ["Mean_IC", "Positive_IC_Rate", "Top_Bottom_Spread", "IC_Delta"]:
        out[col] = pd.to_numeric(out[col], errors="coerce").map(lambda x: "" if pd.isna(x) else round(float(x), 4))
    out["Priority"] = pd.to_numeric(out["Priority"], errors="coerce").map(lambda x: "" if pd.isna(x) else int(x))
    return out[OUTPUT_COLS].fillna("").astype(str)


def write_remediation_plan(df: pd.DataFrame) -> None:
    out = _fmt_df(df)
    try:
        ws = _spreadsheet().worksheet(OUTPUT_SHEET)
    except gspread.exceptions.WorksheetNotFound:
        ws = _spreadsheet().add_worksheet(title=OUTPUT_SHEET, rows=max(100, len(out) + 10), cols=len(OUTPUT_COLS) + 2)
    ws.clear()
    ws.update(range_name="A1", values=[OUTPUT_COLS] + out.values.tolist(), value_input_option="USER_ENTERED")
    dual_write_dataframe(OUTPUT_SHEET, out, market="GLOBAL")
    print(f"[REMEDIATION] Wrote {len(out)} rows to {OUTPUT_SHEET}")


def main() -> None:
    print("\n" + "=" * 65)
    print("  FACTOR REMEDIATION PLAN")
    print("=" * 65)
    gates, policy, backtest = load_inputs()
    plan = build_remediation_plan(gates, policy, backtest)
    write_remediation_plan(plan)
    counts = plan["Severity"].value_counts().to_dict()
    print(f"[REMEDIATION] Severity counts: {counts}")


if __name__ == "__main__":
    main()
