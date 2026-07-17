# Codespaces / Dev Container

QuantBridge has two devcontainer profiles:

- Root full-stack profile: `.devcontainer/`
- Streamlit dashboard-only profile: `GitHub/my-quant-dashboard/.devcontainer/`

## Root Full Stack

Open the project root in Codespaces or VS Code Dev Containers.

The root profile starts:

- Python 3.11 development container
- PostgreSQL 16 service
- FastAPI on port `8000`
- Streamlit dashboard on port `8501`

On first create, it installs:

```bash
requirements.txt
api/requirements_api.txt
GitHub/my-quant-dashboard/requirements.txt
tools/requirements_research.txt
```

It also runs:

```bash
python -m unittest test_contracts.py
```

## Google Credentials

Live Google Sheets calls need `key.json`. Do not commit this file.

For Codespaces, add a repository or user secret named:

```text
QUANT_GOOGLE_KEY_JSON
```

Use the entire service-account JSON as the value. During `postCreateCommand`,
the devcontainer writes it to:

```text
key.json
```

If that secret is missing, the container still works for offline tests, CI work,
Docker work, and code editing. Live Sheets-backed pages will fail until the key
is provided.

## Useful Commands

```bash
make test
make dag-dry-run
make api
make ops-health
cd GitHub/my-quant-dashboard && streamlit run app.py
```

Devcontainer auto-start logs:

```text
logs/devcontainer_api.log
logs/devcontainer_streamlit.log
```

## Dashboard-Only Repository

The nested `GitHub/my-quant-dashboard` repository has its own smaller
devcontainer. Use it when working only on Streamlit UI and dashboard loaders.

It installs `requirements.txt`, compiles the dashboard modules, and opens
Streamlit on port `8501`.
