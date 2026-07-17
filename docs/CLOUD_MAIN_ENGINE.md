# Cloud Main Engine Runbook

QuantBridge can run `main_engine.py` from GitHub Actions so the laptop does not
need to stay on.

## Run From GitHub

Open:

```text
GitHub -> Actions -> Run Main Engine Parallel -> Run workflow
```

Workflow:

```text
.github/workflows/run-main-engine-parallel.yml
```

The older `Run Main Engine` workflow remains available for manual, targeted
serial modes such as `test`, `us-only`, `kr-only`, or one-off debugging. Daily
automatic refreshes should use the parallel workflow.

## Automatic Schedule

The workflow also runs automatically Tuesday-Saturday shortly after the Nasdaq
regular close:

- 05:10 KST during US daylight time
- 06:10 KST during US standard time

GitHub Actions uses UTC cron and cannot express US daylight-saving changes
directly, so the configured schedules are:

```text
10 20 * * 1-5
10 21 * * 1-5
```

A gate job allows only the run that maps to 16:10 in `America/New_York`, so the
daily full run does not execute twice.

Scheduled runs use the daily parallel refresh profile:

```text
skip_detail_cache=true
start_staging_database=true
stop_staging_database_after=false
smoke_api_after=true
```

The workflow breaks the engine into independent GitHub Actions jobs so the
longest pieces do not wait on unrelated setup work, while sequencing the
highest-risk KR/DART stages:

```text
shared-prep -> dart-health-check
shared-prep -> us-core -> smallcap-us
shared-prep + dart-health-check -> kr-core
dart-health-check + kr-core + smallcap-us -> kr-smallcap-shards -> kr-smallcap-merge
smallcap-us + kr-smallcap-merge -> smallcap-backtest
us-core + kr-core + smallcap-backtest -> downstream-report -> parallel-finalize
```

`kr-smallcap-shards` still uses a matrix so a failed shard can be retried and
logs stay split by slice. The DART health check chooses the shard count
(`2` when DART is healthy, `4` in fallback mode by default), and KR smallcap
waits for the KR core and US smallcap jobs before it starts to reduce provider
contention. This still runs your existing `main_engine.py` and pipeline scripts;
it does not add a new paid API or new paid service. Detail-cache warming is
skipped to keep the daily job shorter; run the workflow manually with
`skip_detail_cache=false` when the app detail cache needs to be rewarmed.

## Inputs

| Input | Default | Meaning |
| --- | --- | --- |
| `skip_detail_cache` | `true` | Adds `--skip-detail-cache` when `true`; set `false` only when the app detail cache needs a full rewarm |
| `start_staging_database` | `true` | Starts Azure PostgreSQL before the run if it was stopped |
| `stop_staging_database_after` | `false` | Stops Azure PostgreSQL after the run to reduce cost |
| `smoke_api_after` | `true` | Runs staging API smoke checks after the pipeline |

## Required Secrets

The workflow reads the same repository secrets as staging deploy:

```text
AZURE_CREDENTIALS
AZURE_RESOURCE_GROUP
AZURE_CONTAINER_APP
QUANT_DATABASE_URL
QUANT_GOOGLE_KEY_JSON
QUANT_SPREADSHEET_ID
```

Optional repository variable:

```text
QUANT_SPREADSHEET_NAME=Jino_Quant_Database
```

## Runtime Behavior

- Runs on `ubuntu-latest` with Python 3.11.
- Installs root, API, and research requirements.
- Sets `QUANT_ENABLE_POSTGRES=true` and `QUANT_ENABLE_PARQUET=false`, so the
  pipeline writes to Azure PostgreSQL and Google Sheets without creating a
  GitHub-hosted Parquet lake.
- Forces `--runner legacy` for each `main_engine.py` segment to avoid a
  temporary Prefect server inside GitHub Actions.
- Uploads one artifact per parallel segment, such as `parallel-us-core-*`,
  `parallel-kr-core-*`, `parallel-kr-smallcap-shard-*`, and
  `parallel-downstream-report-*`.

## Cost Notes

Use `stop_staging_database_after=true` when running one-off cloud jobs and the
API does not need to remain live afterwards. PostgreSQL storage, ACR storage,
Key Vault, and Log Analytics can still have small residual costs.
