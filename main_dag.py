#!/usr/bin/env python3
"""QuantBridge DAG runner.

This is the new orchestration entrypoint for upgrade phase 1. It uses Prefect
when available and falls back to the same deterministic local execution when
Prefect is not installed.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from quantbridge.config import get_settings
from quantbridge.orchestration import PipelineStep, run_steps


PROJECT_DIR = Path(__file__).resolve().parent


CORE_STEPS = [
    PipelineStep("pipeline/01_universe_expander.py", "Universe expansion", "SHARED"),
    PipelineStep("pipeline/02_macro_regime.py", "Macro regime", "MACRO", optional=True),
    PipelineStep("pipeline/03a_factor_scorer_us.py", "US factor scoring", "US"),
    PipelineStep("pipeline/04_ml_model.py", "US ML scoring", "US"),
    PipelineStep("pipeline/05a_backtest_us.py", "US backtest", "US"),
    PipelineStep("pipeline/06a_portfolio_optimizer_us.py", "US portfolio optimizer", "US"),
    PipelineStep("pipeline/03b_factor_scorer_kr.py", "KR factor scoring", "KR"),
    PipelineStep("pipeline/05b_backtest_kr.py", "KR backtest", "KR"),
    PipelineStep("pipeline/06b_portfolio_optimizer_kr.py", "KR portfolio optimizer", "KR"),
    PipelineStep("pipeline/19_shadow_portfolio.py", "Shadow portfolio", "SHADOW", optional=True),
    PipelineStep("pipeline/12_portfolio_drift_monitor.py", "Portfolio drift", "DRIFT", optional=True),
    PipelineStep("pipeline/13_factor_attribution.py", "Factor attribution", "ATTRIB", optional=True),
    PipelineStep("pipeline/14_factor_ic_report.py", "Factor IC", "IC", optional=True),
    PipelineStep("pipeline/15_signal_quality_gate.py", "Signal quality gates", "QUALITY", optional=True),
    PipelineStep("pipeline/16_factor_weight_policy.py", "Factor weight policy", "POLICY", optional=True),
    PipelineStep("pipeline/17_factor_policy_backtest.py", "Factor policy backtest", "POLICY-BT", optional=True),
    PipelineStep("pipeline/18_factor_remediation_plan.py", "Factor remediation plan", "REMEDIATE", optional=True),
    PipelineStep("pipeline/10a_earnings_surprise_us.py", "US earnings momentum", "EARN", optional=True),
    PipelineStep("pipeline/10b_earnings_surprise_kr.py", "KR earnings momentum", "EARN", optional=True),
    PipelineStep("pipeline/09_industry_ranking.py", "Industry ranking", "INDUSTRY", optional=True),
    PipelineStep("pipeline/11_order_flow_kr.py", "KR order flow", "KR-FLOW", optional=True),
    PipelineStep("pipeline/07a_smallcap_scanner_us.py", "US smallcap scanner", "SMALL"),
    PipelineStep("pipeline/07b_smallcap_scanner_kr.py", "KR smallcap scanner", "SMALL"),
    PipelineStep("pipeline/08_smallcap_backtest.py", "Smallcap backtest", "SMALL"),
    PipelineStep("tools/warm_detail_cache.py", "Warm portfolio and sector detail cache", "CACHE", optional=True),
    PipelineStep("tools/sync_price_snapshots.py", "Refresh latest app price snapshots", "PRICE", optional=True),
]


def parse_args():
    parser = argparse.ArgumentParser(description="QuantBridge DAG runner")
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--analyze-only", action="store_true")
    parser.add_argument("--no-prefect", action="store_true", help="Force local sequential runner")
    parser.add_argument("--dry-run", action="store_true", help="Validate DAG/runtime without executing pipeline scripts")
    return parser.parse_args()


def main():
    args = parse_args()
    settings = get_settings()
    use_prefect = settings.pipeline_runner == "prefect" and not args.no_prefect
    if args.dry_run:
        print("DAG dry-run: no pipeline scripts will execute")
        for step in CORE_STEPS:
            marker = "optional" if step.optional else "required"
            print(f"- {step.label or 'STEP'} | {marker} | {step.script} | {step.description}")
        results = run_steps(
            [],
            cwd=PROJECT_DIR,
            test_mode=args.test,
            analyze_only=args.analyze_only,
            use_prefect=use_prefect,
        )
        print(f"Runtime validation complete: {results}")
        return
    results = run_steps(
        CORE_STEPS,
        cwd=PROJECT_DIR,
        test_mode=args.test,
        analyze_only=args.analyze_only,
        use_prefect=use_prefect,
    )
    print("\nDAG complete")
    for row in results:
        print(row)


if __name__ == "__main__":
    main()
