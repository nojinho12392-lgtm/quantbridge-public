# Repository Cleanup Plan

This workspace is currently a mixed desktop folder, not a single clean Git
checkout. Cleanup should be staged and non-destructive until the duplicate trees
are reconciled.

## Current State

- `quantbridge-fullstack/` is the canonical repository candidate because it has
  the Git remote `github.com/nojinho12392-lgtm/quantbridge-fullstack`.
- The previous top-level duplicate trees have been moved out of the active
  workspace into a dated desktop archive:
  - `Quant project/`
  - `Stock Analysis/`
  - `andriod/`
- Source changes should now land in `quantbridge-fullstack/` first.

## Safe Workflow

1. Run `make workspace-audit` from `quantbridge-fullstack/`.
2. Run `make secret-audit` to confirm no local secrets or generated artifacts
   are tracked by Git.
3. Review dirty Git roots and duplicate risks.
4. Reconcile newer `Quant project/` changes into `quantbridge-fullstack/`.
5. Commit or migrate the top-level iOS changes from `Stock Analysis/`.
6. Remove or archive the top-level `andriod/` duplicate after a final clean diff.
7. Remove duplicate folders only after a clean diff and a backup/tag.

Steps 4-6 have been completed for the active workspace; the old folders are
preserved outside `Quant/` instead of being deleted.

## iOS Status

`Stock Analysis/` and `quantbridge-fullstack/Stock Analysis/` have no source
differences after ignoring local Git/Xcode state. The canonical iOS project
also builds for a generic iOS Simulator with code signing disabled.

## Android Status

The canonical Android app has been renamed to `android/`. Tooling prefers the
corrected path and falls back to the old `andriod/` spelling only for local
workspace compatibility while the duplicate sibling folder still exists.
`make android-build` succeeds from the canonical repo.

Package identity is now branded:

- `applicationId` / `namespace`: `com.qubit.quantbridge`
- Source root: `android/app/src/main/java/com/qubit/quantbridge/`
- Theme: `Theme.Qubit`

Use `make cleanup-duplicates` to dry-run Finder-style `* 2*` junk removal, and
`make cleanup-duplicates-apply` to delete matches. Use `make export-public-dry`
or `make export-public` to refresh the public-safe tree.

## Files That Must Stay Local

Never commit these files or their contents:

- `key.json`
- `kiwoom_credentials.json`
- `.env`
- `deploy/azure/staging.env`
- `cache.db`
- `api/quantbridge.sqlite3`
- `data_lake/`
- `docs_cache/`
- `logs/`
- generated Android/iOS build outputs
- model weights such as `*.pt`

## Recommended End State

Use one repository root:

```text
quantbridge-fullstack/
  api/
  quantbridge/
  pipeline/
  tools/
  docs/
  Stock Analysis/
  android/
  GitHub/my-quant-dashboard/
```

Keep runtime state outside the repo or under ignored local paths. At that point,
CI, deployment, app configuration, and documentation can all point at one source
of truth.
