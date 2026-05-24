# Public Release Checklist

Use this checklist before pushing the public repository to GitHub.

## Must Not Be Included

- Real API keys or service-account files.
- `.env`, `key.json`, `kiwoom_credentials.json`, or broker credentials.
- SQLite databases, cached market data, Parquet data lake files, logs, screenshots,
  APK/AAB/IPA files, or other generated artifacts.
- Production deployment scripts that depend on private cloud resources.
- Self-hosted runner setup files.
- Git history from the private repository.

## Must Be Placeholder Values

- `QUANT_SPREADSHEET_ID`
- `QUANT_SPREADSHEET_NAME`
- `QUANT_DATABASE_URL`
- `QUANT_API_BASE_URL`
- external API credentials

## Recommended Before Push

Run these checks from the public-clean folder:

```bash
find . -name key.json -o -name ".env" -o -name "*.sqlite3" -o -name "*.parquet"
rg -n "BEGIN PRIVATE KEY|private_key|client_email|ghp_|github_pat_|AIza|sk-"
rg -n "YOUR_GOOGLE_SHEET_ID|YOUR_API_KEY|example" .env.example README.md docs
```

If the first two commands print real files or secrets, do not publish yet.

## Messaging Guardrails

Use:

- decision support
- risk checklist
- investment journal
- personal research tool
- user-provided data

Avoid:

- buy now
- sell now
- target price
- recommended allocation
- guaranteed return
- AI stock pick
