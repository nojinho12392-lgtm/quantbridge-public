# Canonical Repository Reconcile

Date: 2026-05-10

`quantbridge-fullstack/` is now treated as the canonical repository candidate.
The older/mixed `Quant project/` tree is still present as a local working copy,
but new source changes should land in `quantbridge-fullstack/` first.

## Reconciled Into `quantbridge-fullstack`

- Added the non-destructive workspace audit command: `make workspace-audit`.
- Added `docs/REPO_CLEANUP.md` and this reconcile note.
- Hardened `.gitignore` and `.dockerignore` for local secrets, caches, logs,
  Parquet, SQLite, model weights, and local tool state.
- Added `make secret-audit` / `tools/check_no_local_artifacts.py` and wired it
  into CI so blocked local secrets or generated artifacts fail before merge.
- Made `.env.example` portable instead of tied to one absolute desktop path.
- Added API CORS and auth rate-limit configuration:
  - `QUANT_CORS_ORIGINS`
  - `QUANT_AUTH_RATE_LIMIT_PER_MINUTE`
  - `QUANT_API_ENV`
- Added a lightweight in-memory auth rate limiter to signup/login endpoints.
- Raised ops-health default timeout from 10s to 30s and wired it into launchd.
- Reconciled CI improvements from `Quant project` while preserving newer
  fullstack features:
  - CI concurrency
  - launchd plist validation
  - Android `andriod`/`android` auto-detection
  - staging deploy push trigger and deployment summary
  - staging API env/rate-limit/CORS configuration

## Intentionally Kept From `quantbridge-fullstack`

These were newer than `Quant project` and should not be overwritten:

- `pipeline/factor_policy_runtime.py`
- Runtime factor-policy weight application in US/KR factor scorers.
- Small-cap `Data_Confidence` score haircuts and schema/API support.
- DART/KRX optional secret checks in `run-main-engine.yml`.
- CodeQL v4 workflow action versions.

## Archived Out Of Active Workspace

The duplicate folders have not been deleted. They were moved out of the active
`Quant/` workspace into a dated desktop archive so their local Git state,
secrets, and runtime data remain recoverable.

Archived folders:

- `Quant project/`
- `Stock Analysis/`
- `andriod/`

After the archive move, `make workspace-audit` reports no duplicate source
trees and no local secret/generated artifacts inside the active workspace.

## iOS Reconcile

The iOS app source has been reconciled into the canonical repo.

Validation:

```bash
xcodebuild -project "Stock Analysis.xcodeproj" \
  -scheme "Stock Analysis" \
  -configuration Debug \
  -destination "generic/platform=iOS Simulator" \
  CODE_SIGNING_ALLOWED=NO build
```

Result: `BUILD SUCCEEDED` on 2026-05-10.

## Android Reconcile

The canonical Android project was renamed from `andriod/` to `android/`.
Tooling now prefers `android/` and keeps `andriod/` only as a fallback while the
top-level duplicate exists.

Affected tooling:

- GitHub Actions Android build detection
- `tools/configure_app_api.py`
- `tools/qa_phase2.py`
- `tools/qa_android_device.py`
- `tools/audit_workspace.py`
- `make android-build`

Validation:

```bash
make android-build
```

Result: `BUILD SUCCESSFUL` on 2026-05-10. Remaining Kotlin messages are
pre-existing deprecation warnings for mirrored Material icons.

## Current Diff Summary

The remaining source differences between `Quant project/` and
`quantbridge-fullstack/` are intentional canonical improvements, not newer
runtime-tree changes:

- API CORS/rate-limit hardening and `Data_Confidence` API parsing.
- Runtime factor-policy weight application.
- Small-cap `Data_Confidence` scoring and schema documentation.
- DART/KRX optional secret checks for cloud engine runs.
- CI path filters, concurrency, Android path detection, and secret audit.
- Workspace audit fixes for ignored Android/Xcode/generated local state.

## Next Cleanup Gate

Before removing any duplicate tree:

```bash
make workspace-audit
make secret-audit
git status --short
```

Then verify:

- `Stock Analysis/` dirty count is zero or its changes are migrated.
- `diff -qr "Stock Analysis" "quantbridge-fullstack/Stock Analysis"` has no
  source differences after ignoring `.git`, `xcuserdata`, `.DS_Store`, and build
  state.
- `Quant project` contains no source file that is newer than the canonical repo.
- top-level `andriod/` has no source differences from canonical `android/`
  after ignoring local Gradle/Kotlin/build state.
