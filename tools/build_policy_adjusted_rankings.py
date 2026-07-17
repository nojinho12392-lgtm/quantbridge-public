#!/usr/bin/env python3
"""Build observation-only rankings with factor policy multipliers applied.

This does not overwrite production scored tables. It writes shadow datasets so
operators can inspect how proxy/production factor policy would move names.
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quantbridge.storage import QuantRepository
from quantbridge.writers.dual_write import dual_write_dataframe


FACTOR_COLS = ["Value_Score", "Quality_Score", "Momentum_Score"]
POLICY_SHEET = "Factor_Weight_Policy"
OUTPUT_COLS = [
    "Policy_Rank", "Base_Rank", "Rank_Change", "Ticker", "Name", "Market", "Sector", "MarketCap",
    "Policy_Final_Score", "Base_Final_Score", "Score_Change",
    "Policy_Total_Score", "Base_Total_Score",
    "Policy_Score_Neutral", "Base_Score_Neutral",
    "Policy_Value_Score", "Base_Value_Score", "Value_Multiplier",
    "Policy_Quality_Score", "Base_Quality_Score", "Quality_Multiplier",
    "Policy_Momentum_Score", "Base_Momentum_Score", "Momentum_Multiplier",
    "Policy_Mode", "Policy_Evidence_Source", "Policy_Production_Ready", "Policy_Actions",
    "Investability_Score", "Business_Quality_Score", "Quality_Data_Confidence",
    "Quality_Category", "Quality_Red_Flags", "Last_Updated", "Generated",
]
SUMMARY_COLS = [
    "Generated", "Market", "Policy_Mode", "Rows", "Positive_Movers", "Negative_Movers", "Unchanged",
    "Mean_Abs_Rank_Change", "Top_Up_Ticker", "Top_Up_Name", "Top_Up_Rank_Change",
    "Top_Down_Ticker", "Top_Down_Name", "Top_Down_Rank_Change",
    "Top_Base_Ticker", "Top_Policy_Ticker", "Multipliers",
    "Evidence_Source", "Production_Ready", "Note",
]


def _num(df: pd.DataFrame, column: str, default: float = np.nan) -> pd.Series:
    if column not in df.columns:
        return pd.Series(default, index=df.index, dtype="float64")
    return pd.to_numeric(df[column], errors="coerce")


def _is_true(value: Any) -> bool:
    return str(value or "").strip().upper() in {"TRUE", "1", "YES", "Y"}


def _clean_text(value: Any) -> str:
    if value is None:
        return ""
    try:
        if pd.isna(value):
            return ""
    except Exception:
        pass
    return str(value).strip()


def _rank_desc(series: pd.Series) -> pd.Series:
    values = pd.to_numeric(series, errors="coerce")
    return values.rank(method="first", ascending=False, na_option="bottom").astype(int)


def _sector_neutral_score(df: pd.DataFrame, score_col: str) -> pd.Series:
    values = pd.to_numeric(df.get(score_col), errors="coerce")
    out = values.copy()
    if "Sector" not in df.columns:
        return out
    sectors = df["Sector"].fillna("").astype(str)
    for sector, idx in sectors.groupby(sectors).groups.items():
        if not str(sector).strip() or str(sector).strip() == "Unclassified" or len(idx) < 5:
            continue
        group = values.loc[idx]
        std = group.std()
        if pd.notna(std) and std > 0:
            out.loc[idx] = (group - group.mean()) / std
    return out


def load_factor_policy(repo: QuantRepository | None = None) -> pd.DataFrame:
    repo = repo or QuantRepository()
    try:
        return repo.read_dataframe(POLICY_SHEET, market=None)
    except Exception:
        return pd.DataFrame()


def build_factor_multipliers(
    policy: pd.DataFrame,
    market: str,
    *,
    mode: str = "proxy",
) -> tuple[dict[str, float], dict[str, dict[str, Any]]]:
    """Return factor multipliers and row metadata for a market."""

    mode = str(mode or "proxy").lower()
    multipliers = {factor: 1.0 for factor in FACTOR_COLS}
    details = {
        factor: {
            "Adjustment_Bias": 1.0,
            "Evidence_Source": "UNAVAILABLE",
            "Production_Ready": False,
            "Current_Action": "no_policy_row",
            "Policy_Status": "UNAVAILABLE",
        }
        for factor in FACTOR_COLS
    }
    if policy.empty:
        return multipliers, details

    df = policy.copy()
    for col in ["Market", "Factor", "Adjustment_Bias", "Evidence_Source", "Production_Ready", "Current_Action", "Policy_Status"]:
        if col not in df.columns:
            df[col] = ""
    market = str(market).upper()
    scoped = df[df["Market"].astype(str).str.upper().isin([market, "GLOBAL"])].copy()
    if scoped.empty:
        return multipliers, details

    for factor in FACTOR_COLS:
        rows = scoped[scoped["Factor"].astype(str).eq(factor)]
        if rows.empty:
            continue
        row = rows.iloc[0].to_dict()
        ready = _is_true(row.get("Production_Ready"))
        raw_multiplier = pd.to_numeric(row.get("Adjustment_Bias"), errors="coerce")
        multiplier = float(raw_multiplier) if pd.notna(raw_multiplier) and raw_multiplier > 0 else 1.0
        should_apply = mode == "proxy" or (mode == "production" and ready)
        details[factor] = {
            "Adjustment_Bias": multiplier,
            "Evidence_Source": _clean_text(row.get("Evidence_Source")) or "UNKNOWN",
            "Production_Ready": ready,
            "Current_Action": _clean_text(row.get("Current_Action")) or "unknown",
            "Policy_Status": _clean_text(row.get("Policy_Status")) or "UNKNOWN",
        }
        if should_apply:
            multipliers[factor] = multiplier
    return multipliers, details


def build_policy_adjusted_ranking(
    scored: pd.DataFrame,
    policy: pd.DataFrame,
    market: str,
    *,
    mode: str = "proxy",
    generated: str | None = None,
) -> pd.DataFrame:
    if scored.empty:
        return pd.DataFrame(columns=OUTPUT_COLS)

    generated = generated or datetime.utcnow().replace(microsecond=0).isoformat()
    market = str(market).upper()
    mode = str(mode or "proxy").lower()
    multipliers, details = build_factor_multipliers(policy, market, mode=mode)

    df = scored.copy()
    for col in ["Ticker", "Name", "Market", "Sector", "MarketCap", "Last_Updated"]:
        if col not in df.columns:
            df[col] = ""
    df["Market"] = market
    df["Base_Rank"] = _num(df, "Rank")
    if df["Base_Rank"].isna().all():
        df["Base_Rank"] = _rank_desc(_num(df, "Final_Score"))

    for factor in FACTOR_COLS:
        df[f"Base_{factor}"] = _num(df, factor)
        df[f"Policy_{factor}"] = df[f"Base_{factor}"] * multipliers[factor]

    base_total = _num(df, "Total_Score")
    factor_total = sum(df[f"Policy_{factor}"].fillna(0.0) for factor in FACTOR_COLS)
    has_factor = pd.concat([df[f"Policy_{factor}"].notna() for factor in FACTOR_COLS], axis=1).any(axis=1)
    df["Base_Total_Score"] = base_total
    df["Policy_Total_Score"] = factor_total.where(has_factor, np.nan)
    df["Base_Final_Score"] = _num(df, "Final_Score")
    df["Base_Score_Neutral"] = _num(df, "Score_Neutral")
    df["Policy_Score_Neutral"] = _sector_neutral_score(df, "Policy_Total_Score")

    investability = _num(df, "Investability_Score")
    invest_rank = investability.rank(pct=True)
    if invest_rank.notna().sum() == 0:
        invest_rank = df["Policy_Total_Score"].rank(pct=True)
    policy_rank_mix = (
        0.45 * df["Policy_Total_Score"].rank(pct=True)
        + 0.25 * df["Policy_Score_Neutral"].rank(pct=True)
        + 0.30 * invest_rank
    )
    confidence = _num(df, "Quality_Data_Confidence", default=0.0).fillna(0.0).clip(0.0, 1.0)
    flag_count = df.get("Quality_Red_Flags", pd.Series("", index=df.index)).fillna("").astype(str).map(
        lambda text: 0 if not text else len([part for part in text.split("|") if part])
    )
    df["Policy_Final_Score"] = (
        policy_rank_mix.fillna(0.0) * (0.50 + 0.50 * confidence)
        - flag_count.clip(0, 5) * 0.015
    ).clip(0.0, 1.0)
    df["Score_Change"] = df["Policy_Final_Score"] - df["Base_Final_Score"]

    df = df.sort_values("Policy_Final_Score", ascending=False, na_position="last").reset_index(drop=True)
    df["Policy_Rank"] = range(1, len(df) + 1)
    df["Base_Rank"] = pd.to_numeric(df["Base_Rank"], errors="coerce").fillna(len(df) + 1).astype(int)
    df["Rank_Change"] = df["Base_Rank"] - df["Policy_Rank"]
    df["Policy_Mode"] = "proxy_observation" if mode == "proxy" else "production_ready_only"
    df["Policy_Evidence_Source"] = ", ".join(sorted({str(item["Evidence_Source"]) for item in details.values()}))
    df["Policy_Production_Ready"] = all(bool(item["Production_Ready"]) for item in details.values())
    df["Policy_Actions"] = "; ".join(
        f"{factor}={details[factor]['Current_Action']}:{multipliers[factor]:.2f}"
        for factor in FACTOR_COLS
    )
    df["Generated"] = generated
    df["Value_Multiplier"] = multipliers["Value_Score"]
    df["Quality_Multiplier"] = multipliers["Quality_Score"]
    df["Momentum_Multiplier"] = multipliers["Momentum_Score"]

    for col in OUTPUT_COLS:
        if col not in df.columns:
            df[col] = ""
    out = df[OUTPUT_COLS].copy()
    numeric_cols = [
        "Policy_Rank", "Base_Rank", "Rank_Change", "MarketCap",
        "Policy_Final_Score", "Base_Final_Score", "Score_Change",
        "Policy_Total_Score", "Base_Total_Score",
        "Policy_Score_Neutral", "Base_Score_Neutral",
        "Policy_Value_Score", "Base_Value_Score", "Value_Multiplier",
        "Policy_Quality_Score", "Base_Quality_Score", "Quality_Multiplier",
        "Policy_Momentum_Score", "Base_Momentum_Score", "Momentum_Multiplier",
        "Investability_Score", "Business_Quality_Score", "Quality_Data_Confidence",
    ]
    for col in numeric_cols:
        if col in out.columns:
            out[col] = pd.to_numeric(out[col], errors="coerce").round(6)
    return out


def summarize_ranking(ranking: pd.DataFrame, market: str, *, mode: str, generated: str) -> dict[str, Any]:
    if ranking.empty:
        return {
            "Generated": generated,
            "Market": market,
            "Policy_Mode": mode,
            "Rows": 0,
            "Note": "No scored rows available.",
        }
    rank_change = pd.to_numeric(ranking["Rank_Change"], errors="coerce").fillna(0)
    up = ranking.sort_values("Rank_Change", ascending=False).iloc[0]
    down = ranking.sort_values("Rank_Change", ascending=True).iloc[0]
    base_top = ranking.sort_values("Base_Rank", ascending=True).iloc[0]
    policy_top = ranking.sort_values("Policy_Rank", ascending=True).iloc[0]
    return {
        "Generated": generated,
        "Market": market,
        "Policy_Mode": ranking.iloc[0].get("Policy_Mode", mode),
        "Rows": len(ranking),
        "Positive_Movers": int((rank_change > 0).sum()),
        "Negative_Movers": int((rank_change < 0).sum()),
        "Unchanged": int((rank_change == 0).sum()),
        "Mean_Abs_Rank_Change": round(float(rank_change.abs().mean()), 4),
        "Top_Up_Ticker": up.get("Ticker", ""),
        "Top_Up_Name": up.get("Name", ""),
        "Top_Up_Rank_Change": int(up.get("Rank_Change") or 0),
        "Top_Down_Ticker": down.get("Ticker", ""),
        "Top_Down_Name": down.get("Name", ""),
        "Top_Down_Rank_Change": int(down.get("Rank_Change") or 0),
        "Top_Base_Ticker": base_top.get("Ticker", ""),
        "Top_Policy_Ticker": policy_top.get("Ticker", ""),
        "Multipliers": (
            f"V={float(ranking.iloc[0].get('Value_Multiplier') or 1):.2f}, "
            f"Q={float(ranking.iloc[0].get('Quality_Multiplier') or 1):.2f}, "
            f"M={float(ranking.iloc[0].get('Momentum_Multiplier') or 1):.2f}"
        ),
        "Evidence_Source": ranking.iloc[0].get("Policy_Evidence_Source", ""),
        "Production_Ready": ranking.iloc[0].get("Policy_Production_Ready", False),
        "Note": "Shadow ranking only; production scored tables are unchanged.",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build policy-adjusted shadow rankings")
    parser.add_argument("--markets", default="US,KR", help="Comma-separated markets to process")
    parser.add_argument(
        "--mode",
        choices=["proxy", "production"],
        default="proxy",
        help="proxy applies observation-only multipliers; production applies only Production_Ready rows",
    )
    parser.add_argument("--limit", type=int, default=0, help="Optional rows to print per market")
    parser.add_argument("--no-write-repository", action="store_true", help="Do not write Parquet snapshots")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    markets = [item.strip().upper() for item in str(args.markets).split(",") if item.strip()]
    generated = datetime.utcnow().replace(microsecond=0).isoformat()
    repo = QuantRepository()
    policy = load_factor_policy(repo)
    summaries = []

    print("\n" + "=" * 65)
    print("  POLICY-ADJUSTED SHADOW RANKINGS")
    print("=" * 65)
    print(f"[policy-ranking] mode={args.mode}; production scored tables are unchanged")

    for market in markets:
        scored = repo.read_dataframe(f"{market}_Scored_Stocks", market=market)
        ranking = build_policy_adjusted_ranking(scored, policy, market, mode=args.mode, generated=generated)
        summaries.append(summarize_ranking(ranking, market, mode=args.mode, generated=generated))
        if not args.no_write_repository and not ranking.empty:
            dual_write_dataframe(f"{market}_Policy_Adjusted_Ranking", ranking, market=market)

        print(f"\n[policy-ranking] {market}: rows={len(ranking)}")
        if not ranking.empty:
            view_cols = [
                "Policy_Rank", "Base_Rank", "Rank_Change", "Ticker", "Name",
                "Policy_Final_Score", "Base_Final_Score", "Policy_Actions",
            ]
            print(ranking[view_cols].head(max(1, int(args.limit or 12))).to_string(index=False))

    summary_df = pd.DataFrame(summaries, columns=SUMMARY_COLS)
    if not args.no_write_repository and not summary_df.empty:
        dual_write_dataframe("Policy_Adjusted_Ranking_Summary", summary_df, market="GLOBAL")

    print("\n[policy-ranking] summary")
    print(summary_df[["Market", "Rows", "Positive_Movers", "Negative_Movers", "Mean_Abs_Rank_Change", "Top_Up_Ticker", "Top_Down_Ticker"]].to_string(index=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
