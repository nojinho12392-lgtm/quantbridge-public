#!/usr/bin/env python3
"""Run the lightweight daily research-quality job.

This job is intentionally much smaller than the full QuantBridge pipeline. It
only updates factor score snapshots, evaluates aged IC diagnostics when
available, refreshes the PASS/WATCH/FAIL gate table, updates policy
recommendations, simulates policy-adjusted factor weights when aged snapshots
exist, and writes an operator remediation plan for weak factors.

Run:
    python tools/run_research_quality.py
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from quantbridge.orchestration import PipelineStep, run_steps


RESEARCH_QUALITY_STEPS = [
    PipelineStep(
        "pipeline/14_factor_ic_report.py",
        "Update factor score snapshots and aged IC diagnostics",
        "IC",
    ),
    PipelineStep(
        "pipeline/15_signal_quality_gate.py",
        "Refresh signal quality gates",
        "QUALITY",
    ),
    PipelineStep(
        "pipeline/16_factor_weight_policy.py",
        "Refresh observation-only factor weight policy",
        "POLICY",
    ),
    PipelineStep(
        "pipeline/17_factor_policy_backtest.py",
        "Backtest observation-only factor policy",
        "POLICY-BT",
    ),
    PipelineStep(
        "pipeline/18_factor_remediation_plan.py",
        "Write factor remediation work queue",
        "REMEDIATE",
    ),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run QuantBridge research-quality job")
    parser.add_argument("--skip-ic", action="store_true", help="Skip Factor_IC_Report update")
    parser.add_argument("--skip-gates", action="store_true", help="Skip Signal_Quality_Gates update")
    parser.add_argument("--skip-policy", action="store_true", help="Skip Factor_Weight_Policy update")
    parser.add_argument("--skip-policy-backtest", action="store_true", help="Skip Factor_Policy_Backtest update")
    parser.add_argument("--skip-remediation", action="store_true", help="Skip Factor_Remediation_Plan update")
    parser.add_argument("--prefect", action="store_true", help="Use Prefect runner instead of local sequential runner")
    parser.add_argument("--test", action="store_true", help="Pass QUANT_TEST_MODE=true to child scripts")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    steps = []
    for step in RESEARCH_QUALITY_STEPS:
        if args.skip_ic and step.script.endswith("14_factor_ic_report.py"):
            continue
        if args.skip_gates and step.script.endswith("15_signal_quality_gate.py"):
            continue
        if args.skip_policy and step.script.endswith("16_factor_weight_policy.py"):
            continue
        if args.skip_policy_backtest and step.script.endswith("17_factor_policy_backtest.py"):
            continue
        if args.skip_remediation and step.script.endswith("18_factor_remediation_plan.py"):
            continue
        steps.append(step)

    if not steps:
        raise SystemExit("No research-quality steps selected.")

    print("\n" + "=" * 65)
    print("  RESEARCH QUALITY JOB")
    print("=" * 65)
    for step in steps:
        print(f"  - {step.label}: {step.script}")

    results = run_steps(
        steps,
        cwd=ROOT,
        test_mode=args.test,
        analyze_only=False,
        use_prefect=args.prefect,
    )

    print("\nResearch quality job complete")
    for row in results:
        print(row)


if __name__ == "__main__":
    main()
