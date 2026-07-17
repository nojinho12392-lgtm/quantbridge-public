# FastAPI Staging Deploy

This staging path uses GitHub Education cloud credits with Azure:

- Azure Container Registry for the Docker image
- Azure Container Apps for the FastAPI service
- Azure Database for PostgreSQL Flexible Server for the service store
- Azure Key Vault for runtime secrets
- GitHub Actions for build/deploy

The dashboard and Android app can then point at a stable HTTPS API URL instead
of a local `10.0.2.2` or LAN address.

## 1. Create Azure Resources

Install and sign in to Azure CLI locally:

```bash
az login
az account show
```

Create a local staging env file:

```bash
cp deploy/azure/staging.env.example deploy/azure/staging.env
```

Edit names/passwords in `deploy/azure/staging.env`, then run:

```bash
deploy/azure/create-staging-resources.sh
```

The script creates a low-cost staging shell and prints the Container App URL.

## 2. GitHub Secrets

In GitHub repository settings, add these Actions secrets:

```text
AZURE_CREDENTIALS
AZURE_RESOURCE_GROUP
AZURE_ACR_NAME
AZURE_CONTAINER_APP
AZURE_KEYVAULT_NAME
QUANT_DATABASE_URL
QUANT_GOOGLE_KEY_JSON
QUANT_SPREADSHEET_ID
```

Optional Actions secrets for the Android/API news feed:

```text
NAVER_CLIENT_ID
NAVER_CLIENT_SECRET
```

Optional repository variable:

```text
QUANT_SPREADSHEET_NAME=Jino_Quant_Database
```

`QUANT_GOOGLE_KEY_JSON` is the full service-account JSON string. Do not commit
`key.json`. A compact one-line JSON value is easiest to paste into GitHub
Secrets, but the app also accepts normal JSON content.

`QUANT_DATABASE_URL` should look like:

```text
postgresql://USER:PASSWORD@SERVER.postgres.database.azure.com:5432/quantbridge?sslmode=require
```

## 3. Azure Credentials Secret

Create a service principal scoped to the staging resource group:

```bash
SUBSCRIPTION_ID="$(az account show --query id -o tsv)"
az ad sp create-for-rbac \
  --name quantbridge-github-actions \
  --role contributor \
  --scopes "/subscriptions/${SUBSCRIPTION_ID}/resourceGroups/rg-quantbridge-staging" \
  --sdk-auth
```

Copy the JSON output into the `AZURE_CREDENTIALS` GitHub secret.

## 4. Deploy

### Option A — Local Deploy

After `make create-staging-resources`, deploy the current local API image:

```bash
PATH="/opt/homebrew/bin:$PATH" make deploy-staging-local
```

This command reads `deploy/azure/staging.env`, builds `api/Dockerfile`, pushes
the image to ACR, syncs `QUANT_DATABASE_URL` and `QUANT_GOOGLE_KEY_JSON` into
Azure Key Vault, syncs optional Naver news credentials when present, points
Container App secrets at Key Vault references, updates the Container App, and
waits for `/ready`.

### Option B — GitHub Actions Deploy

The workflow is intentionally manual-only until the full-stack repository has
its Azure and QuantBridge secrets configured:

```text
GitHub → Actions → Deploy API Staging → Run workflow
```

Manual runs include an optional `bootstrap_data=true` input. Use it when the
staging PostgreSQL store needs to be reloaded from Google Sheets and the
stock-detail cache should be warmed inside GitHub Actions. After bootstrap, the
workflow verifies `/ops/data-quality` and reruns staging readiness with app
endpoint smoke tests.

The workflow:

1. Logs into Azure
2. Verifies the required GitHub secrets are present
3. Builds `api/Dockerfile`
4. Pushes `quantbridge-api` to Azure Container Registry
5. Syncs runtime secrets into Azure Key Vault
6. Updates the Container App image, Key Vault secret references, and environment variables
7. Runs `/ready`, `/ops/health`, and `/ops/data-quality` smoke checks
8. Optionally bootstraps staging data on manual runs
9. Rechecks data quality and app endpoint smoke tests after bootstrap

## 5. Secret Rotation

Runtime secrets live in Key Vault and are exposed to Container Apps through
`keyvaultref:...,identityref:system` references. Rotate local staging secrets
without rebuilding the API image:

```bash
PATH="/opt/homebrew/bin:$PATH" make sync-staging-secrets
```

This command writes:

- `database-url`
- `google-key-json`
- `naver-client-id` and `naver-client-secret` when both optional Naver values are set

Then it refreshes Container App environment references so the next revision uses
the Key Vault-backed values.

## 6. Verify

Before app/detail smoke tests, bootstrap the staging service store:

```bash
PATH="/opt/homebrew/bin:$PATH" make bootstrap-staging-data
```

This loads current Google Sheets snapshots into Azure PostgreSQL and warms
stock-detail OHLCV cache.

When staging needs a full data-freshness pass, use the combined command:

```bash
PATH="/opt/homebrew/bin:$PATH" make refresh-app-data-staging
```

It runs staging data bootstrap, research-quality refresh, and the staging
status probe in sequence. This is the quickest local recovery path when
`/ops/health` reports stale KR rank or research-quality checks after a deploy.

Use the URL printed in the workflow summary:

```bash
PATH="/opt/homebrew/bin:$PATH" make staging-url
python tools/check_staging_status.py --url https://YOUR-CONTAINER-APP-FQDN --warn-only --timeout 30
python tools/check_staging_status.py --url https://YOUR-CONTAINER-APP-FQDN --smoke --warn-only --timeout 30
PATH="/opt/homebrew/bin:$PATH" make staging-ops-health
PATH="/opt/homebrew/bin:$PATH" make staging-readiness
```

`check_staging_status.py` retries `/ready` for up to 120 seconds by default,
which lets cold Azure Container Apps revisions wake before the heavier ops and
data-quality checks run. Override with `--wait-ready-seconds` or
`QUANT_STAGING_WAIT_READY_SECONDS` when you need a shorter fail-fast probe.

Expected staging status can be `DEGRADED` if `Signal quality` is warning. The
important infrastructure checks are API readiness, PostgreSQL, Google key,
portfolio rows, small-cap rows, and research-quality freshness.

To verify the deployed API through the Android app on an emulator, run:

```bash
PATH="/opt/homebrew/bin:$PATH" make staging-android-smoke
```

This runs the staging API smoke, builds and installs the Android debug app on
the `QuantBridge_Pixel_8_API_36` emulator, confirms the app calls staging for
the core portfolio/small-cap/macro endpoints, checks the main app screens,
creates a temporary account, verifies session restore after app restart, deletes
the temporary account, and writes screenshots, UI XML, and logcat under
`artifacts/`.

For a faster staging check while iterating, run the same command with the quick
Android profile:

```bash
STAGING_ANDROID_SMOKE_EXTRA="--android-skip-tests" make staging-android-smoke-quick
```

`make staging-android-smoke-full` is the explicit release-gate equivalent of
`make staging-android-smoke`.

Refresh the research-quality tables against staging PostgreSQL when
`/ops/health` reports stale or missing research runs:

```bash
PATH="/opt/homebrew/bin:$PATH" make staging-research-quality
```

This runs factor IC, signal quality gates, factor weight policy, policy
backtest, policy-adjusted shadow rankings, and remediation planning locally
while writing the results into the Azure PostgreSQL service store.

For continuous lightweight monitoring, install the staging health LaunchAgent:

```bash
PATH="/opt/homebrew/bin:$PATH" make install-staging-ops-schedule
```

It checks `/ops/health` every 30 minutes and writes logs under `logs/`.

## 7. Android / iOS / Dashboard

Once staging is healthy:

- Android should use the staging HTTPS URL as `APIBaseURL`
- iOS should set `APIBaseURL` in `Stock Analysis/Stock Analysis/Info.plist`
- Streamlit can set `QUANT_API_BASE_URL=https://YOUR-CONTAINER-APP-FQDN`
- GitHub Actions deployment summary keeps the current staging URL visible

Configure, smoke-test, build, and optionally launch the Android app against
staging with the quick emulator profile:

```bash
PATH="/opt/homebrew/bin:$PATH" make android-emulator-smoke
```

For a quicker emulator check while iterating, skip Android unit tests:

```bash
ANDROID_EMULATOR_SMOKE_EXTRA="--skip-tests" make android-emulator-smoke
```

The broader mobile smoke runner can pass the same Android emulator flags:

```bash
MOBILE_SMOKE_EXTRA="--platform android --skip-api --android-skip-tests" make mobile-smoke
```

If a cold emulator is slow to emit the first API logs, extend the Android API
wait:

```bash
MOBILE_SMOKE_EXTRA="--platform android --skip-api --android-skip-tests --android-api-wait-timeout 180" make mobile-smoke
```

The default emulator smoke profile is `quick`: it verifies the core screens,
stock detail tabs, small-cap filter, insights, watch add/remove, and the Account
login screen. Run the full account-session profile before release:

```bash
ANDROID_EMULATOR_SMOKE_EXTRA="--skip-tests" make android-emulator-smoke-full
```

For a real phone, enable USB debugging, accept the RSA prompt, then run the
legacy connected-device path:

```bash
PATH="/opt/homebrew/bin:$PATH" make android-device-qa
```

## Cost Guardrails

- Use small PostgreSQL SKU for staging.
- Stop/delete staging resources when not using them for a while:

```bash
PATH="/opt/homebrew/bin:$PATH" make staging-status
PATH="/opt/homebrew/bin:$PATH" make idle-staging
PATH="/opt/homebrew/bin:$PATH" make stop-staging
PATH="/opt/homebrew/bin:$PATH" make start-staging
PATH="/opt/homebrew/bin:$PATH" make restart-staging
```

`idle-staging` keeps the API usable but caps Container Apps to 0-1 replicas.
`stop-staging` sets Container Apps min replicas to 0 and stops PostgreSQL
compute. PostgreSQL storage and ACR storage can still cost a small amount.
Azure may also automatically restart a stopped PostgreSQL Flexible Server after
several days, so check `make staging-status` if you leave staging idle.

For a full teardown:

```bash
CONFIRM_DELETE=rg-quantbridge-staging PATH="/opt/homebrew/bin:$PATH" make delete-staging
```

The confirmation variable is required so the resource group cannot be deleted
by an accidental command.

- Set Azure budget alerts before leaving anything running continuously.

## Repository Caveat

This workflow must live in the GitHub repository that contains `api/Dockerfile`,
`api/`, `quantbridge/`, and the deployment scripts. The current dashboard-only
repository cannot deploy the FastAPI image unless the full project is pushed
there or a separate full-stack QuantBridge repository is created.
