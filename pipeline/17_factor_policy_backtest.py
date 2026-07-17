"""
17_factor_policy_backtest.py
============================
Backtest observation-only factor weight policy recommendations.

This script compares the current base V/Q/M composite against the
Factor_Weight_Policy-adjusted composite using point-in-time factor snapshots
and forward returns. It does not change production scorer weights.

Outputs:
  - Factor_Policy_Backtest

Run:
  python pipeline/17_factor_policy_backtest.py
"""

from __future__ import annotations

import os
import sys
import time
from datetime import datetime

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

import gspread
import numpy as np
import pandas as pd
import yfinance as yf

from sheets_client import get_spreadsheet
from quantbridge.storage import QuantRepository
from quantbridge.writers.dual_write import dual_write_dataframe


SNAPSHOT_SHEET = "Factor_Score_Snapshots"
POLICY_SHEET = "Factor_Weight_Policy"
OUTPUT_SHEET = "Factor_Policy_Backtest"

FACTOR_COLS = ["Value_Score", "Quality_Score", "Momentum_Score"]

SNAPSHOT_COLS = [
    "Snapshot_Date", "Market", "Ticker", "Name", "Sector",
    "Value_Score", "Quality_Score", "Momentum_Score",
    "Total_Score", "Final_Score", "Score_Neutral", "Combined_Score",
    "Snapshot_Source",
]

POLICY_COLS = [
    "Market", "Factor", "Policy_Status", "Current_Action", "Adjustment_Bias",
    "Suggested_Multiplier", "Evidence_Status", "Primary_Horizon", "Mean_IC",
    "Positive_IC_Rate", "Snapshots", "Total_Observations", "Live_Snapshots",
    "Proxy_Snapshots", "Proxy_Ratio", "Evidence_Source", "Production_Ready",
    "Reason", "Review_Note", "Generated",
]

OUTPUT_COLS = [
    "Market", "Horizon", "Status", "Snapshots", "Total_Observations",
    "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio", "Evidence_Source",
    "Production_Ready",
    "Base_Weighted_IC", "Policy_Weighted_IC", "IC_Delta",
    "Base_Top_Bottom_Spread", "Policy_Top_Bottom_Spread", "Spread_Delta",
    "Base_Hit_Rate", "Policy_Hit_Rate", "Turnover_Estimate",
    "Decision", "Reason", "Generated",
]

BASE_WEIGHTS = {
    "US": {"Value_Score": 0.40, "Quality_Score": 0.30, "Momentum_Score": 0.30},
    "KR": {"Value_Score": 0.40, "Quality_Score": 0.35, "Momentum_Score": 0.25},
    "GLOBAL": {"Value_Score": 0.40, "Quality_Score": 0.30, "Momentum_Score": 0.30},
}

HORIZONS = {
    "1M": 21,
    "3M": 63,
    "6M": 126,
}

MIN_SNAPSHOTS = 3
MIN_OBSERVATIONS = 60
MIN_OBS_PER_SNAPSHOT = 20
PRICE_DELAY = 0.4
BATCH = 80


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


def _read_sheet(name: str, cols: list[str]) -> pd.DataFrame:
    try:
        rows = _spreadsheet().worksheet(name).get_all_values()
    except gspread.exceptions.WorksheetNotFound:
        return pd.DataFrame(columns=cols)
    except Exception as exc:
        print(f"[POLICY-BT] Sheet read skipped for {name}: {type(exc).__name__}: {exc}")
        return pd.DataFrame(columns=cols)
    if len(rows) < 2:
        return pd.DataFrame(columns=cols)
    df = pd.DataFrame(rows[1:], columns=rows[0])
    for col in cols:
        if col not in df.columns:
            df[col] = ""
    return df[cols].copy()


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
    return out[SNAPSHOT_COLS].copy()


def _load_storage_or_sheet(name: str, cols: list[str], *, history: bool = False) -> pd.DataFrame:
    try:
        repo = _repository()
        df = repo.read_history(name, market=None) if history else repo.read_dataframe(name, market=None)
    except Exception as exc:
        print(f"[POLICY-BT] Storage read skipped for {name}: {type(exc).__name__}: {exc}")
        df = pd.DataFrame()
    if df.empty:
        df = _read_sheet(name, cols)
    if history:
        return _prepare_snapshot_frame(df)
    for col in cols:
        if col not in df.columns:
            df[col] = ""
    return df[cols].copy()


def load_snapshots() -> pd.DataFrame:
    df = _load_storage_or_sheet(SNAPSHOT_SHEET, SNAPSHOT_COLS, history=True)
    df = _to_num(df, FACTOR_COLS)
    df["Market"] = df["Market"].fillna("").astype(str).str.upper()
    df["Ticker"] = df["Ticker"].fillna("").astype(str).str.strip()
    df["Snapshot_Date"] = pd.to_datetime(df["Snapshot_Date"], errors="coerce")
    df = df.dropna(subset=["Snapshot_Date"])
    df = df[df["Ticker"] != ""].copy()
    df["Snapshot_Source"] = df["Snapshot_Source"].fillna("").astype(str).str.upper().replace("", "UNKNOWN")
    return df


def load_policy() -> pd.DataFrame:
    df = _load_storage_or_sheet(POLICY_SHEET, POLICY_COLS)
    df = _to_num(df, ["Adjustment_Bias", "Suggested_Multiplier"])
    df["Market"] = df["Market"].fillna("").astype(str).str.upper()
    df["Factor"] = df["Factor"].fillna("").astype(str)
    df["Policy_Status"] = df["Policy_Status"].fillna("").astype(str).str.upper()
    if "Evidence_Source" in df.columns:
        df["Evidence_Source"] = df["Evidence_Source"].fillna("").astype(str).str.upper()
    if "Production_Ready" in df.columns:
        df["Production_Ready"] = df["Production_Ready"].fillna("").astype(str).str.upper()
    return df


def _policy_adjusted_weights(market: str, policy: pd.DataFrame) -> tuple[dict[str, float], dict[str, float]]:
    base = BASE_WEIGHTS.get(market, BASE_WEIGHTS["GLOBAL"]).copy()
    multipliers = {factor: 1.0 for factor in FACTOR_COLS}
    if not policy.empty:
        scoped = policy[policy["Market"].isin([market, "GLOBAL"])].copy()
        for factor in FACTOR_COLS:
            rows = scoped[scoped["Factor"].eq(factor)]
            if rows.empty:
                continue
            value = pd.to_numeric(rows.iloc[-1].get("Adjustment_Bias"), errors="coerce")
            if pd.notna(value) and value > 0:
                multipliers[factor] = float(value)

    adjusted = {factor: base[factor] * multipliers[factor] for factor in FACTOR_COLS}
    total = sum(adjusted.values())
    if total <= 0:
        return base, base.copy()
    adjusted = {factor: value / total for factor, value in adjusted.items()}
    return base, adjusted


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
            print(f"[POLICY-BT] Price batch error: {exc}")
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


def _forward_returns(prices: pd.DataFrame, snapshot_date, horizon_days: int) -> pd.Series:
    start_px = _price_on_or_after(prices, snapshot_date)
    end_date = pd.Timestamp(snapshot_date) + pd.Timedelta(days=horizon_days)
    end_px = _price_on_or_after(prices, end_date)
    if start_px.empty or end_px.empty:
        return pd.Series(dtype=float)
    returns = (end_px / start_px) - 1.0
    return returns.replace([np.inf, -np.inf], np.nan).dropna()


def _composite_score(snap_df: pd.DataFrame, weights: dict[str, float]) -> pd.Series:
    parts = []
    for factor, weight in weights.items():
        values = pd.to_numeric(snap_df[factor], errors="coerce")
        ranks = values.rank(pct=True)
        parts.append(ranks.mul(weight))
    return pd.concat(parts, axis=1).sum(axis=1, min_count=1)


def _score_stats(scores: pd.Series, returns: pd.Series) -> tuple[float, float, float, int, set[str]]:
    aligned = pd.concat([scores.rename("score"), returns.rename("return")], axis=1).dropna()
    n = len(aligned)
    if n < MIN_OBS_PER_SNAPSHOT or aligned["score"].nunique() < 5:
        return float("nan"), float("nan"), float("nan"), n, set()

    ic = float(aligned["score"].corr(aligned["return"], method="spearman"))
    top_cut = aligned["score"].quantile(0.80)
    bot_cut = aligned["score"].quantile(0.20)
    top = aligned[aligned["score"] >= top_cut]
    bottom = aligned[aligned["score"] <= bot_cut]
    if top.empty or bottom.empty:
        return ic, float("nan"), float("nan"), n, set(top.index)

    spread = float(top["return"].mean() - bottom["return"].mean())
    hit_rate = float((top["return"] > 0).mean())
    return ic, spread, hit_rate, n, set(top.index)


def _turnover_estimate(base_top: set[str], policy_top: set[str]) -> float:
    if not base_top or not policy_top:
        return float("nan")
    return 1.0 - (len(base_top.intersection(policy_top)) / max(len(base_top), len(policy_top)))


def _is_true(value) -> bool:
    return str(value or "").strip().upper() in {"TRUE", "1", "YES", "Y"}


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

    production_ready = live_dates >= MIN_SNAPSHOTS and proxy_ratio <= 0.50
    return {
        "Live_Snapshots": int(live_dates),
        "Proxy_Snapshots": int(proxy_dates),
        "Proxy_Ratio": float(proxy_ratio),
        "Evidence_Source": evidence_source,
        "Production_Ready": "TRUE" if production_ready else "FALSE",
    }


def _status_for_summary(row: dict) -> tuple[str, str, str]:
    snapshots = int(row.get("Snapshots") or 0)
    observations = int(row.get("Total_Observations") or 0)
    ic_delta = row.get("IC_Delta")
    spread_delta = row.get("Spread_Delta")
    production_ready = _is_true(row.get("Production_Ready"))
    evidence_source = str(row.get("Evidence_Source") or "UNKNOWN").upper()
    if snapshots < MIN_SNAPSHOTS or observations < MIN_OBSERVATIONS:
        return (
            "INSUFFICIENT",
            "HOLD",
            "Need more aged snapshots before trusting the policy simulation.",
        )
    if pd.notna(ic_delta) and pd.notna(spread_delta) and ic_delta >= 0.01 and spread_delta >= 0:
        if not production_ready:
            return (
                "IMPROVED",
                "OBSERVE_PROXY",
                f"Policy-adjusted composite improved in simulation, but evidence is {evidence_source}; wait for LIVE_DAILY confirmation before adopting.",
            )
        return (
            "IMPROVED",
            "ADOPT_CANDIDATE",
            "Policy-adjusted composite improved IC without hurting spread.",
        )
    if pd.notna(ic_delta) and pd.notna(spread_delta) and ic_delta < -0.005 and spread_delta < 0:
        if not production_ready:
            return (
                "WORSE",
                "OBSERVE_PROXY",
                f"Policy-adjusted composite weakened in simulation, but evidence is {evidence_source}; treat as diagnostic until live confirmation.",
            )
        return (
            "WORSE",
            "REJECT",
            "Policy-adjusted composite weakened both IC and spread.",
        )
    if not production_ready:
        return (
            "NEUTRAL",
            "OBSERVE_PROXY",
            f"Policy-adjusted composite is not decisive and evidence is {evidence_source}; keep observing live snapshots.",
        )
    return (
        "NEUTRAL",
        "OBSERVE",
        "Policy-adjusted composite is not clearly better or worse yet.",
    )


def _insufficient_row(snapshots: pd.DataFrame, reason: str) -> pd.DataFrame:
    generated = datetime.now().strftime("%Y-%m-%d %H:%M")
    snapshot_count = len(snapshots.drop_duplicates(["Snapshot_Date", "Market"])) if not snapshots.empty else 0
    return pd.DataFrame([{
        "Market": "GLOBAL",
        "Horizon": "ALL",
        "Status": "INSUFFICIENT",
        "Snapshots": snapshot_count,
        "Total_Observations": 0,
        "Live_Snapshots": 0,
        "Proxy_Snapshots": 0,
        "Proxy_Ratio": 0,
        "Evidence_Source": "UNKNOWN",
        "Production_Ready": "FALSE",
        "Base_Weighted_IC": "",
        "Policy_Weighted_IC": "",
        "IC_Delta": "",
        "Base_Top_Bottom_Spread": "",
        "Policy_Top_Bottom_Spread": "",
        "Spread_Delta": "",
        "Base_Hit_Rate": "",
        "Policy_Hit_Rate": "",
        "Turnover_Estimate": "",
        "Decision": "HOLD",
        "Reason": reason,
        "Generated": generated,
    }], columns=OUTPUT_COLS)


def build_policy_backtest(snapshots: pd.DataFrame, policy: pd.DataFrame) -> pd.DataFrame:
    if snapshots.empty:
        return _insufficient_row(snapshots, "No Factor_Score_Snapshots rows are available.")

    snapshots = snapshots.copy()
    today = pd.Timestamp.today().normalize()
    min_horizon = min(HORIZONS.values())
    aged = snapshots[snapshots["Snapshot_Date"] + pd.Timedelta(days=min_horizon) <= today].copy()
    if aged.empty:
        return _insufficient_row(snapshots, "No aged snapshots with forward returns are available yet.")

    generated = datetime.now().strftime("%Y-%m-%d %H:%M")
    detail_rows = []

    for market, df_market in aged.groupby("Market"):
        if not market:
            continue
        tickers = sorted(df_market["Ticker"].dropna().astype(str).str.strip().unique())
        oldest = df_market["Snapshot_Date"].min().strftime("%Y-%m-%d")
        print(f"[POLICY-BT] {market}: downloading prices for {len(tickers)} snapshot tickers")
        prices = _download_prices(tickers, oldest)
        if prices.empty:
            continue

        base_weights, policy_weights = _policy_adjusted_weights(str(market), policy)
        for snap_date, snap_df in df_market.groupby("Snapshot_Date"):
            snapshot_source = _source_label(snap_df.get("Snapshot_Source", pd.Series(dtype=str)))
            snap_idx = snap_df.set_index("Ticker")
            base_score = _composite_score(snap_idx, base_weights)
            policy_score = _composite_score(snap_idx, policy_weights)

            for horizon_label, horizon_days in HORIZONS.items():
                if snap_date + pd.Timedelta(days=horizon_days) > today:
                    continue
                fwd = _forward_returns(prices, snap_date, horizon_days)
                if fwd.empty:
                    continue

                base_ic, base_spread, base_hit, base_n, base_top = _score_stats(base_score, fwd)
                policy_ic, policy_spread, policy_hit, policy_n, policy_top = _score_stats(policy_score, fwd)
                n = min(base_n, policy_n)
                if n < MIN_OBS_PER_SNAPSHOT:
                    continue

                detail_rows.append({
                    "Market": market,
                    "Horizon": horizon_label,
                    "Snapshot_Date": snap_date.strftime("%Y-%m-%d"),
                    "Snapshot_Source": snapshot_source,
                    "N": n,
                    "Base_IC": base_ic,
                    "Policy_IC": policy_ic,
                    "Base_Spread": base_spread,
                    "Policy_Spread": policy_spread,
                    "Base_Hit_Rate": base_hit,
                    "Policy_Hit_Rate": policy_hit,
                    "Turnover_Estimate": _turnover_estimate(base_top, policy_top),
                })

    detail = pd.DataFrame(detail_rows)
    if detail.empty:
        return _insufficient_row(snapshots, "No eligible policy backtest windows were available.")

    rows = []
    for (market, horizon), grp in detail.groupby(["Market", "Horizon"]):
        row = {
            "Market": market,
            "Horizon": horizon,
            "Status": "",
            "Snapshots": int(grp["Snapshot_Date"].nunique()),
            "Total_Observations": int(grp["N"].sum()),
            "Base_Weighted_IC": grp["Base_IC"].mean(),
            "Policy_Weighted_IC": grp["Policy_IC"].mean(),
            "IC_Delta": grp["Policy_IC"].mean() - grp["Base_IC"].mean(),
            "Base_Top_Bottom_Spread": grp["Base_Spread"].mean(),
            "Policy_Top_Bottom_Spread": grp["Policy_Spread"].mean(),
            "Spread_Delta": grp["Policy_Spread"].mean() - grp["Base_Spread"].mean(),
            "Base_Hit_Rate": grp["Base_Hit_Rate"].mean(),
            "Policy_Hit_Rate": grp["Policy_Hit_Rate"].mean(),
            "Turnover_Estimate": grp["Turnover_Estimate"].mean(),
            "Decision": "",
            "Reason": "",
            "Generated": generated,
        }
        row.update(_evidence_metrics(grp))
        status, decision, reason = _status_for_summary(row)
        row["Status"] = status
        row["Decision"] = decision
        row["Reason"] = reason
        rows.append(row)

    out = pd.DataFrame(rows, columns=OUTPUT_COLS)
    order = {"WORSE": 0, "INSUFFICIENT": 1, "NEUTRAL": 2, "IMPROVED": 3}
    out["_rank"] = out["Status"].map(order).fillna(9)
    out = out.sort_values(["Market", "_rank", "Horizon"]).drop(columns=["_rank"])
    return out.reset_index(drop=True)


def _fmt_df(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    for col in OUTPUT_COLS:
        if col not in out.columns:
            out[col] = ""
    numeric_cols = [
        "Base_Weighted_IC", "Policy_Weighted_IC", "IC_Delta",
        "Base_Top_Bottom_Spread", "Policy_Top_Bottom_Spread", "Spread_Delta",
        "Base_Hit_Rate", "Policy_Hit_Rate", "Turnover_Estimate",
        "Proxy_Ratio",
    ]
    for col in numeric_cols:
        out[col] = pd.to_numeric(out[col], errors="coerce").map(lambda x: "" if pd.isna(x) else round(float(x), 4))
    for col in ["Snapshots", "Total_Observations", "Live_Snapshots", "Proxy_Snapshots"]:
        out[col] = pd.to_numeric(out[col], errors="coerce").map(lambda x: "" if pd.isna(x) else int(x))
    return out[OUTPUT_COLS].fillna("").astype(str)


def write_policy_backtest(df: pd.DataFrame) -> None:
    out = _fmt_df(df)
    dual_write_dataframe(OUTPUT_SHEET, out, market="GLOBAL")
    try:
        ws = _spreadsheet().worksheet(OUTPUT_SHEET)
    except gspread.exceptions.WorksheetNotFound:
        ws = _spreadsheet().add_worksheet(title=OUTPUT_SHEET, rows=max(100, len(out) + 10), cols=len(OUTPUT_COLS) + 2)
    except Exception as exc:
        print(f"[POLICY-BT] Sheet write skipped: {type(exc).__name__}: {exc}")
        print(f"[POLICY-BT] Wrote {len(out)} rows to local storage {OUTPUT_SHEET}")
        return
    try:
        ws.clear()
        ws.update(range_name="A1", values=[OUTPUT_COLS] + out.values.tolist(), value_input_option="USER_ENTERED")
        print(f"[POLICY-BT] Wrote {len(out)} rows to {OUTPUT_SHEET}")
    except Exception as exc:
        print(f"[POLICY-BT] Sheet write skipped: {type(exc).__name__}: {exc}")
        print(f"[POLICY-BT] Wrote {len(out)} rows to local storage {OUTPUT_SHEET}")


def main() -> None:
    print("\n" + "=" * 65)
    print("  FACTOR POLICY BACKTEST")
    print("=" * 65)
    snapshots = load_snapshots()
    policy = load_policy()
    result = build_policy_backtest(snapshots, policy)
    write_policy_backtest(result)
    counts = result["Status"].value_counts().to_dict()
    print(f"[POLICY-BT] Status counts: {counts}")
    print("[POLICY-BT] Observation-only: scorer weights were not changed.")


if __name__ == "__main__":
    main()
