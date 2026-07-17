# Upgrade Phase 3 Research Layer

Phase 3 starts by turning raw factor diagnostics into operational model controls.

## Snapshot Backfill

`tools/backfill_factor_snapshots.py` bootstraps monthly proxy snapshots so the
IC and policy layers can be inspected before months of live daily snapshots have
accumulated.

The backfill is marked `PROXY_BACKFILL`: it uses the latest value/quality scores
as static anchors and reconstructs historical momentum from prices at each
snapshot date. Treat it as a warm-up diagnostic, not final production evidence.

Run:
```bash
make backfill-snapshots
make research-quality
```

The live daily process will continue adding `LIVE_DAILY` snapshots through
`pipeline/14_factor_ic_report.py`.

## Evidence Readiness Guard

Research outputs now carry provenance fields so proxy warm-up diagnostics are
not confused with production-ready evidence:

- `Live_Snapshots`: aged snapshots sourced from live daily runs
- `Proxy_Snapshots`: aged snapshots sourced from `PROXY_BACKFILL`
- `Proxy_Ratio`: proxy snapshots divided by total aged snapshots
- `Evidence_Source`: `LIVE_ONLY`, `PROXY_ONLY`, `MIXED`, or `UNKNOWN`
- `Production_Ready`: `TRUE` only after enough `LIVE_DAILY` evidence exists
  and proxy data no longer dominates the row

When `Production_Ready=FALSE`, quality gates still show the statistical
diagnostic, but the recommended action is observation-only. Policy backtests can
still report `IMPROVED` or `WORSE`, but their `Decision` becomes
`OBSERVE_PROXY` until live evidence confirms the result.

## Signal Quality Gates

`pipeline/15_signal_quality_gate.py` reads `Factor_IC_Report` and writes
`Signal_Quality_Gates`.

Statuses:
- `PASS`: enough aged snapshots and positive IC quality
- `WATCH`: usable but weak or unstable
- `FAIL`: enough data and negative/unstable IC
- `INSUFFICIENT`: not enough aged snapshots yet

Run:
```bash
make quality-gates
```

The full pipeline runs it after `pipeline/14_factor_ic_report.py`.

## Company Quality Review Layer

The production scorers compute `Business_Quality_Score`, `Investability_Score`,
quality ranks, and review categories before writing app-facing ranked tables.
The review layer treats non-positive valuation multiples as explicit risk
evidence rather than neutral missing data: negative PER, PBR, and EV/EBITDA now
lower valuation discipline and add `NEGATIVE_EARNINGS`,
`NEGATIVE_BOOK_EQUITY`, or `NEGATIVE_EBITDA` flags. `NEGATIVE_BOOK_EQUITY` is a
severe quality flag and moves the row into risk review. The shared US/KR value
factor scorers also rank non-positive valuation multiples at the bottom instead
of filling them with neutral ranks.

## Factor Weight Policy

`pipeline/16_factor_weight_policy.py` reads `Signal_Quality_Gates` and writes
`Factor_Weight_Policy`.

This is observation-only. It recommends whether each factor should be kept,
watched, reviewed, or held because evidence is insufficient, but it does not
mutate the production scorer weights.

Run:
```bash
make factor-policy
```

## Factor Policy Backtest

`pipeline/17_factor_policy_backtest.py` reads `Factor_Score_Snapshots` and
`Factor_Weight_Policy`, then compares the base V/Q/M composite against the
policy-adjusted V/Q/M composite using forward returns.

This is also observation-only. It reports whether policy-adjusted weights look
better, worse, neutral, or still insufficient. Production scorer weights are
not changed automatically.

Run:
```bash
make policy-backtest
```

## Factor Remediation Plan

`pipeline/18_factor_remediation_plan.py` reads `Signal_Quality_Gates`,
`Factor_Weight_Policy`, and `Factor_Policy_Backtest`, then writes
`Factor_Remediation_Plan`.

This is an operator work queue. It ranks weak factors by urgency, explains the
likely root cause, and gives a safe next action. It still does not mutate
production scorer weights.

Run:
```bash
make remediation-plan
```

## Daily Research-Quality Job

Use this lightweight job to accumulate IC snapshots and refresh quality gates
without running the full portfolio pipeline:

```bash
make research-quality
```

Docker version:

```bash
make research-quality-docker
```

Health check:

```bash
make research-health
```

Default health threshold is 84 hours. This tolerates the weekend gap between
Saturday morning KST and the next Tuesday morning KST run.

macOS launchd schedule tooling:

```bash
make print-research-schedule
make research-schedule-status
make install-research-schedule
make uninstall-research-schedule
```

Default schedule: Tuesday-Saturday 07:30 KST. This is designed to run after the
US market close has settled in Korea, while still keeping the job independent
from the full portfolio pipeline. Logs are written under `logs/`.

What it runs:
- `pipeline/14_factor_ic_report.py`
- `pipeline/15_signal_quality_gate.py`
- `pipeline/16_factor_weight_policy.py`
- `pipeline/17_factor_policy_backtest.py`

Recommended cadence: once per trading day after market close. The job records
its run status in `pipeline_runs` when PostgreSQL is enabled.

API:
```bash
curl http://localhost:8000/research/factor-quality
curl http://localhost:8000/research/factor-policy
curl http://localhost:8000/research/policy-backtest
curl http://localhost:8000/ops/pipeline-runs
curl http://localhost:8000/ops/research-health
```

Dashboard:
```bash
streamlit run GitHub/my-quant-dashboard/app.py
```

Open the `Research` tab, then `Signal Quality`.

## Current Rule Set

- Minimum aged snapshots: 3
- Minimum forward-return observations: 60
- PASS mean IC threshold: 0.03
- WATCH mean IC threshold: 0.00
- PASS positive IC rate threshold: 0.55
- WATCH positive IC rate threshold: 0.45
- Production-ready minimum live snapshots: 3
- Production-ready maximum proxy ratio: 0.50

These gates should not automatically change portfolio weights yet. They are a
research safety layer: first observe, then tune factor weights once enough
history accumulates.

`Factor_Weight_Policy` turns those gates into a review table:
- `KEEP`: evidence is positive; maintain current factor use
- `WATCH`: evidence is usable but weak; monitor for repeated weakness
- `REVIEW`: evidence is negative or unstable; review before reducing weights
- `HOLD`: evidence is insufficient; do not change weights

`Factor_Policy_Backtest` compares:
- base V/Q/M composite
- policy-adjusted V/Q/M composite

Statuses:
- `IMPROVED`: policy composite improved IC without hurting spread
- `NEUTRAL`: not clearly better or worse
- `WORSE`: policy composite weakened IC and spread
- `INSUFFICIENT`: not enough aged snapshots or forward-return observations

## API Response Shape

`GET /research/factor-quality` returns:
- `overall_status`
- `status_counts`
- `warning_count`
- `production_ready_count`
- `proxy_evidence_count`
- `best_factors`
- `weak_factors`
- `items`

`overall_status` uses the most conservative status in the current gate set:
`FAIL` before `WATCH`, then `INSUFFICIENT`, then `PASS`.

`GET /research/factor-policy` returns:
- `items`
- `status_counts`
- `review_count`
- `hold_count`
- `production_ready_count`
- `proxy_evidence_count`
- `review_items`
- `mode`

`GET /research/policy-backtest` returns:
- `items`
- `status_counts`
- `improved_count`
- `worse_count`
- `production_ready_count`
- `proxy_evidence_count`
- `best_windows`
- `weak_windows`
- `mode`

`GET /research/remediation-plan` returns:
- `items`
- `severity_counts`
- `urgent_count`
- `production_ready_count`
- `top_actions`
- `mode`

`GET /ops/pipeline-runs` returns:
- `items`
- `latest_research_quality`
- `status_counts`
- `source`

The dashboard uses this endpoint/storage data to show the latest
research-quality job status under `Research → Signal Quality → 자동 실행 상태`.

`GET /ops/research-health` returns:
- `healthy`
- `status`
- `reason`
- `max_age_hours`
- `age_hours`
- `latest_research_quality`
