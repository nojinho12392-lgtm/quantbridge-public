# Phase 4 Operations

Phase 4 turns QuantBridge from a working research/app stack into something that can be checked and operated every day.

## Daily Operator Checks

Start the API stack:

```bash
make server
```

Run the full operating health check:

```bash
make ops-health
```

The check calls `GET /ops/health` and verifies:

- FastAPI readiness and local SQLite auth store
- PostgreSQL connectivity/cache row counts when enabled
- Google key path and data lake path availability
- Core app datasets: US/KR portfolios, US/KR small-cap lists, macro regime, signal quality gates
- App-facing data quality across universe, scored-stock, portfolio, and small-cap datasets
- Latest research-quality job status and freshness
- Signal quality gate status counts

For JSON output:

```bash
.venv/bin/python tools/check_ops_health.py --json
```

To tolerate failures in a scheduled notification job:

```bash
.venv/bin/python tools/check_ops_health.py --warn-only
```

## Alerts

Set `QUANT_OPS_WEBHOOK_URL` to any JSON webhook endpoint and the CLI will send the failing/warning checks when the overall health is not OK.

```bash
QUANT_OPS_WEBHOOK_URL=https://example.com/webhook make ops-health
```

The payload includes `status`, `status_counts`, failed checks, warning checks, and the checked URL.

## Scheduled Ops Monitor

macOS launchd tooling is available for recurring local checks:

```bash
make print-ops-schedule
make ops-schedule-status
make install-ops-schedule
make uninstall-ops-schedule
```

Default cadence is every 30 minutes with `RunAtLoad=true`. The scheduled job calls:

```bash
tools/check_ops_health.py --url http://127.0.0.1:8000 --warn-only
```

Logs are written to:

```text
logs/ops_health.out.log
logs/ops_health.err.log
```

Use `QUANT_OPS_WEBHOOK_URL` before installation when alert delivery is needed:

```bash
QUANT_OPS_WEBHOOK_URL=https://example.com/webhook make install-ops-schedule
```

## API Surface

```bash
curl http://localhost:8000/ops/health
curl http://localhost:8000/ops/cache/warm
curl http://localhost:8000/ops/data-quality
curl http://localhost:8000/ops/research-health
curl http://localhost:8000/ops/pipeline-runs?limit=20
```

`/ops/health` returns:

- `healthy`: true only when all checks are OK
- `status`: `OK`, `DEGRADED`, or `FAIL`
- `status_counts`: count of `OK`, `WARN`, `FAIL`
- `checks`: detailed per-check records
- `config`: non-secret runtime configuration

## Staging Readiness

Use the combined staging readiness check before mobile/dashboard QA or after a
deploy:

```bash
make staging-readiness
make staging-smoke
```

The command resolves the staging URL from `QUANT_STAGING_API_URL`,
`QUANT_API_BASE_URL`, or `deploy/azure/staging.env`, then checks `/ready`,
startup cache warm coverage, `/ops/health`, and `/ops/data-quality`.
By default it keeps retrying `/ready` for up to 120 seconds so cold Azure
Container Apps revisions can wake before the rest of the checks run.
`staging-smoke` also runs app-facing endpoint smoke tests.

Android emulator QA writes screenshots, UI XML, logcat, and a compact
`report.json` under the selected artifacts directory. The report records the
staging URL, smoke profile, required 200 endpoints, and the captured artifact
list so a passed run can be reviewed without reading the full console log.

## Dashboard

The Streamlit dashboard has an `Ops` tab for both US and KR market views. It reads `QUANT_API_BASE_URL` or Streamlit secret `quant_api_base_url`, then displays `/ops/health` plus `/ops/data-quality` with refresh and raw JSON views.

## Recommended Routine

For a one-command local refresh before using the app:

```bash
make refresh-app-data-local
```

That starts local PostgreSQL if needed, writes the KR rank refresh into the
same service store used by the local API, refreshes latest price snapshots,
precomputes app snapshots, warms stock-detail cache, and runs data-quality plus
KR rank health gates. Use `KR_LOCAL_EXTRA="--no-prices"` when you want a faster
ranking-only refresh while iterating locally.

For staging data freshness after deploy or when `/ops/health` reports stale KR
rank data:

```bash
make refresh-app-data-staging
```

That bootstraps staging PostgreSQL from current snapshots, refreshes the
research-quality tables, then re-runs the combined staging status check.

1. Run the main pipeline after data refresh.
2. Run `make warm-cache` after portfolio/small-cap outputs change.
3. Run `make research-quality` once per trading day after market close. This refreshes factor quality, policy backtests, remediation rows, and the policy-adjusted shadow rankings used by the mobile quality tab.
4. Run `make ops-health` before using the mobile app or sharing results.
5. Keep `make ops-schedule-status` loaded once a webhook has been configured.
6. If `Ops` is `DEGRADED`, read the warning rows. If it is `FAIL`, fix the failed check before trusting the app.
