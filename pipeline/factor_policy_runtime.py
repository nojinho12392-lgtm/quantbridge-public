"""
Runtime helpers for applying observation-derived factor policy.

The research-quality pipeline writes Factor_Weight_Policy as an audit table.
Scorers use this helper to apply only production-ready recommendations, keeping
proxy/immature evidence in observation-only mode.
"""

from __future__ import annotations

import os
from typing import Mapping

import pandas as pd

from quantbridge.storage import QuantRepository


POLICY_SHEET = "Factor_Weight_Policy"
FACTOR_COLUMNS = ("Value_Score", "Quality_Score", "Momentum_Score")


def _is_true(value) -> bool:
    return str(value or "").strip().upper() in {"TRUE", "1", "YES", "Y"}


def _load_policy_from_storage() -> pd.DataFrame:
    try:
        return QuantRepository().read_dataframe(POLICY_SHEET, market="GLOBAL")
    except Exception:
        return pd.DataFrame()


def _load_policy_from_sheet(spreadsheet) -> pd.DataFrame:
    try:
        rows = spreadsheet.worksheet(POLICY_SHEET).get_all_values()
    except Exception:
        return pd.DataFrame()
    if not rows:
        return pd.DataFrame()
    header = rows[0]
    data = [row + [""] * (len(header) - len(row)) for row in rows[1:] if any(str(c).strip() for c in row)]
    return pd.DataFrame(data, columns=header)


def load_factor_policy(spreadsheet) -> pd.DataFrame:
    df = _load_policy_from_storage()
    if df.empty:
        df = _load_policy_from_sheet(spreadsheet)
    return df


def apply_factor_policy_weights(
    spreadsheet,
    market: str,
    weights: Mapping[str, float],
) -> dict[str, float]:
    """
    Apply production-ready policy multipliers to V/Q/M weights and renormalize.

    By default, rows whose Production_Ready flag is false are logged but not
    applied. Set QUANT_APPLY_PROXY_FACTOR_POLICY=true to experiment with proxy
    evidence in a non-production run.
    """
    base = {key: float(value) for key, value in weights.items()}
    total = sum(base.values())
    if total <= 0:
        return base

    df = load_factor_policy(spreadsheet)
    if df.empty:
        print(f"  Factor policy: no {POLICY_SHEET} rows — using macro weights")
        return base

    for col in ("Market", "Factor", "Adjustment_Bias", "Production_Ready", "Policy_Status", "Evidence_Status"):
        if col not in df.columns:
            df[col] = ""

    market = str(market).upper()
    include_proxy = _is_true(os.environ.get("QUANT_APPLY_PROXY_FACTOR_POLICY"))
    usable = df[df["Market"].astype(str).str.upper().isin([market, "GLOBAL"])].copy()
    if usable.empty:
        print(f"  Factor policy: no rows for {market} — using macro weights")
        return base

    adjusted = dict(base)
    applied = []
    observed = []
    for factor in FACTOR_COLUMNS:
        rows = usable[usable["Factor"].astype(str) == factor]
        if rows.empty:
            continue
        row = rows.iloc[0]
        try:
            multiplier = float(row.get("Adjustment_Bias") or 1.0)
        except (TypeError, ValueError):
            multiplier = 1.0
        production_ready = _is_true(row.get("Production_Ready"))
        label = f"{factor}={multiplier:.2f}({row.get('Evidence_Status') or row.get('Policy_Status')})"
        if production_ready or include_proxy:
            adjusted[factor] = adjusted.get(factor, 0.0) * multiplier
            applied.append(label)
        else:
            observed.append(label)

    adjusted_total = sum(adjusted.values())
    if adjusted_total > 0:
        adjusted = {key: value * total / adjusted_total for key, value in adjusted.items()}

    if applied:
        print(
            "  Factor policy applied: "
            + ", ".join(applied)
            + f" → V:{adjusted.get('Value_Score', 0):.3f} "
              f"Q:{adjusted.get('Quality_Score', 0):.3f} "
              f"M:{adjusted.get('Momentum_Score', 0):.3f}"
        )
    if observed:
        print("  Factor policy observed only: " + ", ".join(observed))
    if not applied and not observed:
        print(f"  Factor policy: no V/Q/M adjustments for {market}")
    return adjusted
