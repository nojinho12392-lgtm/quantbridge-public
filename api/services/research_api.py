from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from fastapi import HTTPException


@dataclass(frozen=True)
class ResearchPayloadBuilder:
    load_simple: Callable[[str, list[str]], list[dict]]
    safe_float: Callable[[Any], float | None]
    utc_now: Callable[[], datetime]
    load_storage_df: Callable[[str, str | None], Any]
    coerce: Callable[[Any, list[str]], Any]
    df_to_records: Callable[[Any], list[dict]]

    def factor_quality_payload(self) -> dict:
        num_cols = [
            "Mean_IC", "Positive_IC_Rate", "Mean_Top_Bottom_Spread",
            "Mean_Hit_Rate", "Snapshots", "Total_Observations",
            "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
        ]

        items = self.load_simple("Signal_Quality_Gates", num_cols)
        status_counts: dict[str, int] = {}
        for item in items:
            status_text = str(item.get("Status") or "UNKNOWN").upper()
            status_counts[status_text] = status_counts.get(status_text, 0) + 1
        production_ready = [
            item for item in items
            if str(item.get("Production_Ready") or "").upper() in {"TRUE", "1", "YES"}
        ]
        proxy_items = [
            item for item in items
            if str(item.get("Evidence_Source") or "").upper() in {"PROXY_ONLY", "MIXED"}
        ]
        status_rank = {"FAIL": 0, "WATCH": 1, "INSUFFICIENT": 2, "PASS": 3}
        overall_status = "UNKNOWN"
        if items:
            overall_status = min(
                (str(item.get("Status") or "UNKNOWN").upper() for item in items),
                key=lambda value: status_rank.get(value, 9),
            )

        ranked = [
            item for item in items
            if self.safe_float(item.get("Mean_IC")) is not None
        ]
        best_factors = sorted(
            ranked,
            key=lambda item: self.safe_float(item.get("Mean_IC")) or -999,
            reverse=True,
        )[:5]
        weak_factors = sorted(
            ranked,
            key=lambda item: self.safe_float(item.get("Mean_IC")) or 999,
        )[:5]
        return {
            "items": items,
            "overall_status": overall_status,
            "status_counts": status_counts,
            "warning_count": status_counts.get("WATCH", 0) + status_counts.get("FAIL", 0),
            "production_ready_count": len(production_ready),
            "proxy_evidence_count": len(proxy_items),
            "best_factors": best_factors,
            "weak_factors": weak_factors,
            "source": "Signal_Quality_Gates",
        }

    def factor_policy_payload(self) -> dict:
        num_cols = [
            "Adjustment_Bias", "Suggested_Multiplier", "Mean_IC",
            "Positive_IC_Rate", "Snapshots", "Total_Observations",
            "Live_Snapshots", "Proxy_Snapshots", "Proxy_Ratio",
        ]

        items = self.load_simple("Factor_Weight_Policy", num_cols)
        status_counts: dict[str, int] = {}
        for item in items:
            status_text = str(item.get("Policy_Status") or "UNKNOWN").upper()
            status_counts[status_text] = status_counts.get(status_text, 0) + 1
        review_items = [
            item for item in items
            if str(item.get("Policy_Status") or "").upper() in {"REVIEW", "WATCH"}
        ]
        hold_items = [
            item for item in items
            if str(item.get("Policy_Status") or "").upper() == "HOLD"
        ]
        production_ready = [
            item for item in items
            if str(item.get("Production_Ready") or "").upper() in {"TRUE", "1", "YES"}
        ]
        proxy_items = [
            item for item in items
            if str(item.get("Evidence_Source") or "").upper() in {"PROXY_ONLY", "MIXED"}
        ]
        return {
            "items": items,
            "status_counts": status_counts,
            "review_count": len(review_items),
            "hold_count": len(hold_items),
            "production_ready_count": len(production_ready),
            "proxy_evidence_count": len(proxy_items),
            "review_items": review_items[:10],
            "source": "Factor_Weight_Policy",
            "mode": "observation_only",
        }

    def ml_blend_status(self, item: dict | None) -> str:
        if not item:
            return "UNAVAILABLE"

        reason = str(item.get("ML_Weight_Reason") or "").strip().lower()
        ml_weight = self.safe_float(item.get("ML_Weight"))
        rank_ic = self.safe_float(item.get("Rank_IC"))

        if ml_weight is None:
            return "UNAVAILABLE"
        if ml_weight <= 0 or reason == "rank_ic_non_positive":
            return "ML_OFF"
        if reason == "rank_ic_unavailable":
            return "REVIEW"
        if rank_ic is not None and rank_ic >= 0.05:
            return "ML_STRONG"
        if rank_ic is not None and rank_ic < 0.02:
            return "ML_WEAK"
        return "ML_BASE"

    def ml_blend_payload(self) -> dict:
        num_cols = [
            "Rank_IC", "ML_Weight", "Factor_Weight",
            "ML_Factor_Spearman", "ML_Factor_Pearson", "Predicted_Stocks",
        ]

        try:
            items = self.load_simple("ML_Blend_Report", num_cols)
        except Exception as exc:
            return {
                "status": "UNAVAILABLE",
                "generated_at": self.utc_now().isoformat(),
                "source": "ML_Blend_Report",
                "latest": None,
                "items": [],
                "error": f"{type(exc).__name__}: {exc}",
            }

        items = sorted(items, key=lambda item: str(item.get("Generated") or ""), reverse=True)
        for item in items:
            item["Status"] = self.ml_blend_status(item)
        latest = items[0] if items else None
        return {
            "status": self.ml_blend_status(latest),
            "generated_at": self.utc_now().isoformat(),
            "source": "ML_Blend_Report",
            "latest": latest,
            "items": items,
        }

    def policy_backtest_payload(self) -> dict:
        num_cols = [
            "Snapshots", "Total_Observations", "Base_Weighted_IC",
            "Policy_Weighted_IC", "IC_Delta", "Base_Top_Bottom_Spread",
            "Policy_Top_Bottom_Spread", "Spread_Delta", "Base_Hit_Rate",
            "Policy_Hit_Rate", "Turnover_Estimate", "Live_Snapshots",
            "Proxy_Snapshots", "Proxy_Ratio",
        ]

        items = self.load_simple("Factor_Policy_Backtest", num_cols)
        status_counts: dict[str, int] = {}
        for item in items:
            status_text = str(item.get("Status") or "UNKNOWN").upper()
            status_counts[status_text] = status_counts.get(status_text, 0) + 1
        improved = [item for item in items if str(item.get("Status") or "").upper() == "IMPROVED"]
        worse = [item for item in items if str(item.get("Status") or "").upper() == "WORSE"]
        production_ready = [
            item for item in items
            if str(item.get("Production_Ready") or "").upper() in {"TRUE", "1", "YES"}
        ]
        proxy_items = [
            item for item in items
            if str(item.get("Evidence_Source") or "").upper() in {"PROXY_ONLY", "MIXED"}
        ]
        ranked = [item for item in items if self.safe_float(item.get("IC_Delta")) is not None]
        best = sorted(ranked, key=lambda item: self.safe_float(item.get("IC_Delta")) or -999, reverse=True)[:5]
        weak = sorted(ranked, key=lambda item: self.safe_float(item.get("IC_Delta")) or 999)[:5]
        return {
            "items": items,
            "status_counts": status_counts,
            "improved_count": len(improved),
            "worse_count": len(worse),
            "production_ready_count": len(production_ready),
            "proxy_evidence_count": len(proxy_items),
            "best_windows": best,
            "weak_windows": weak,
            "source": "Factor_Policy_Backtest",
            "mode": "observation_only",
        }

    def policy_adjusted_ranking_payload(self, safe_market: str, safe_limit: int) -> dict:
        num_cols = [
            "Policy_Rank", "Base_Rank", "Rank_Change", "MarketCap",
            "Policy_Final_Score", "Base_Final_Score", "Score_Change",
            "Policy_Total_Score", "Base_Total_Score", "Policy_Score_Neutral",
            "Base_Score_Neutral", "Value_Multiplier", "Quality_Multiplier",
            "Momentum_Multiplier", "Investability_Score", "Business_Quality_Score",
            "Quality_Data_Confidence",
        ]
        summary_num_cols = [
            "Rows", "Positive_Movers", "Negative_Movers", "Unchanged",
            "Mean_Abs_Rank_Change", "Top_Up_Rank_Change", "Top_Down_Rank_Change",
        ]

        items = self.load_simple(f"{safe_market}_Policy_Adjusted_Ranking", num_cols)
        items = sorted(
            items,
            key=lambda item: self.safe_float(item.get("Policy_Rank")) or 999999,
        )
        summary_items = self.load_simple("Policy_Adjusted_Ranking_Summary", summary_num_cols)
        if not summary_items:
            summary_df = self.load_storage_df("Policy_Adjusted_Ranking_Summary", None)
            if not summary_df.empty:
                summary_df = self.coerce(summary_df, summary_num_cols)
                summary_items = self.df_to_records(summary_df)
        summary_rows = [
            row for row in summary_items
            if str(row.get("Market") or "").upper() == safe_market
        ]
        top_up = sorted(
            items,
            key=lambda item: self.safe_float(item.get("Rank_Change")) or -999999,
            reverse=True,
        )[:10]
        top_down = sorted(
            items,
            key=lambda item: self.safe_float(item.get("Rank_Change")) or 999999,
        )[:10]
        mode = str(items[0].get("Policy_Mode") or "") if items else None
        return {
            "market": safe_market,
            "items": items[:safe_limit],
            "count": len(items),
            "summary": summary_rows[0] if summary_rows else None,
            "top_up": top_up,
            "top_down": top_down,
            "source": f"{safe_market}_Policy_Adjusted_Ranking",
            "mode": mode,
        }

    def remediation_plan_payload(self) -> dict:
        num_cols = [
            "Priority", "Mean_IC", "Positive_IC_Rate", "Top_Bottom_Spread",
            "IC_Delta",
        ]

        items = self.load_simple("Factor_Remediation_Plan", num_cols)
        severity_counts: dict[str, int] = {}
        for item in items:
            severity = str(item.get("Severity") or "UNKNOWN").upper()
            severity_counts[severity] = severity_counts.get(severity, 0) + 1
        urgent_items = [
            item for item in items
            if str(item.get("Severity") or "").upper() in {"HIGH", "OBSERVE_HIGH"}
        ]
        production_ready = [
            item for item in items
            if str(item.get("Production_Ready") or "").upper() in {"TRUE", "1", "YES"}
        ]
        top_actions = sorted(
            items,
            key=lambda item: self.safe_float(item.get("Priority")) or 999,
        )[:10]
        return {
            "items": items,
            "severity_counts": severity_counts,
            "urgent_count": len(urgent_items),
            "production_ready_count": len(production_ready),
            "top_actions": top_actions,
            "source": "Factor_Remediation_Plan",
            "mode": "observation_only",
        }


@dataclass(frozen=True)
class ResearchApiService:
    cached: Callable
    factor_quality_payload: Callable[[], dict]
    factor_policy_payload: Callable[[], dict]
    ml_blend_payload: Callable[[], dict]
    policy_backtest_payload: Callable[[], dict]
    policy_adjusted_ranking_payload: Callable[[str, int], dict]
    remediation_plan_payload: Callable[[], dict]

    def factor_quality(self) -> dict:
        return self.cached("research_factor_quality", self.factor_quality_payload)

    def factor_policy(self) -> dict:
        return self.cached("research_factor_policy", self.factor_policy_payload)

    def ml_blend(self) -> dict:
        return self.cached("research_ml_blend", self.ml_blend_payload)

    def policy_backtest(self) -> dict:
        return self.cached("research_policy_backtest", self.policy_backtest_payload)

    def policy_adjusted_ranking(self, market: str = "US", limit: int = 30) -> dict:
        safe_market = str(market or "US").strip().upper()
        if safe_market not in {"US", "KR"}:
            raise HTTPException(status_code=400, detail="market must be US or KR")
        safe_limit = max(1, min(int(limit or 30), 200))
        return self.cached(
            f"research_policy_adjusted_ranking_{safe_market}_{safe_limit}",
            lambda: self.policy_adjusted_ranking_payload(safe_market, safe_limit),
        )

    def remediation_plan(self) -> dict:
        return self.cached("research_remediation_plan", self.remediation_plan_payload)
