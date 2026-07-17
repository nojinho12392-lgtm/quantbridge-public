# QuantBridge

QuantBridge is a full-stack quantitative investing workspace:

- Python factor/scoring/research pipeline
- FastAPI service backed by PostgreSQL and optional Parquet
- Streamlit dashboard
- Android app and iOS app clients
- Azure staging deployment scripts
- CI, research-quality, and operations health automation

## Common Commands

```bash
make ci-local
make test
python main_engine.py --test
make research-quality
make kr-rank-local
make kr-rank-health
make us-rank-local
make staging-ops-health
make android-device-qa
make cleanup-duplicates
make export-public-dry
```

## Product Identity

- Android `applicationId` / `namespace`: `com.qubit.quantbridge`
- App display name: 큐빗
- Public export helper: `make export-public` writes a secret-free tree to
  `../quantbridge-public-clean`

## Android Release Signing

Release builds are unsigned unless local signing credentials are provided.
Copy keys into `android/local.properties` using the template comments in
`android/signing.example.properties`, then:

```bash
cd android && ./gradlew :app:assembleRelease
```

Never commit the keystore or passwords.

## Quality Research Data Lakes

Build a free local SEC CompanyFacts lake for multi-year US quality features:

```bash
python tools/sec_companyfacts_lake.py --tickers AAPL,MSFT,NVDA --max-requests 20
```

Build a free local OpenDART lake for Korean quality features:

```bash
python tools/kr_dart_lake.py --tickers 005930.KS,000660.KS --start-year 2015 --end-year 2024 --max-api-calls 200
```

Refresh the local Korean ranking snapshots used by `/scored/KR`:

```bash
make kr-rank-local
make kr-rank-local KR_LOCAL_LIMIT=100 KR_LOCAL_KOSDAQ_LIMIT=50
```

The same command also publishes the top local KR scores as `KR_Final_Portfolio`
equal-weight candidates and eligible 100B-10T KRW names as `KR_SmallCap_Gems`.
It intentionally leaves price-derived fields blank when the local run cannot
verify them.

Install a local macOS schedule for the same refresh. By default it runs Monday
through Friday at 18:30 local time:

```bash
make install-kr-rank-schedule
make kr-rank-schedule-status
make kr-rank-health
make uninstall-kr-rank-schedule
```

`/ops/health` also includes this KR ranking refresh check, so the existing
ops-health schedule/webhook path will surface stale snapshots or failed runs.

Refresh local US ranking snapshots from free SEC CompanyFacts and Yahoo Finance
inputs:

```bash
make us-rank-local US_LOCAL_EXTRA=--refresh-sec-lake
```

This publishes `US_Universe`, `US_Scored_Stocks`, `US_Final_Portfolio`, and
eligible 100M-10B USD names as `US_SmallCap_Gems` to the local repository.

Validate quality signals offline from local snapshots and close prices:

```bash
python tools/validate_quality_signals.py --snapshots-csv quality_snapshots.csv --prices-csv closes.csv --signals Persistence_Quality,Investability_Score
```

## Secrets

Never commit local secrets or generated data. The repository ignores files such
as `key.json`, `.env`, `deploy/azure/staging.env`, Android `local.properties`,
SQLite databases, Parquet data, and build outputs.

For cloud/runtime secrets, use GitHub Actions secrets or Azure Key Vault as
documented in `docs/STAGING_DEPLOY.md`.
