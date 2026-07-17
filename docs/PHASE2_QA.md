# QuantBridge Phase 2 QA

This checklist closes the app/API operations pass.

## One-command QA

Run this from `Quant project/`:

```bash
make server
make qa
```

Fast mode without app builds:

```bash
.venv/bin/python tools/qa_phase2.py --skip-builds
```

## What `make qa` Checks

- Docker Compose status for `quantbridge-postgres` and `quantbridge-api`
- Current LAN API URL detection and app configuration update
- `/ready`, portfolio, smallcap, and cached stock-detail endpoint smoke test
- Signup, auth/me, watchlist save/list/delete, storage-backed stock detail, and test account cleanup
- Contract tests
- Android debug build
- iOS simulator build

## Manual Device Checklist

Before testing:

```bash
docker compose ps
make configure-app-api
make app-smoke
```

Phone setup:

- Connect the phone to the same Wi-Fi as this Mac.
- Confirm the app API base URL is reachable from the phone network: `http://<Mac LAN IP>:8000`.
- If Wi-Fi changes, rerun `make configure-app-api` and rebuild/reinstall the app.

In the app:

- Sign up or log in.
- Confirm Home, Portfolio, SmallCap, Pulse, and Watch tabs load.
- Add a heart/watch item from a list.
- Confirm the item appears in Watch.
- Remove the watch item and confirm it disappears.
- Open a US stock detail screen.
- Open a KR stock detail screen.
- Switch chart periods.
- Toggle candle/line chart mode.
- Select indicators and confirm overlays render.

## Useful Debug Commands

```bash
docker compose ps
docker logs -f quantbridge-api
curl http://127.0.0.1:8000/ready
curl http://$(ipconfig getifaddr en0):8000/ready
```

## Current Known Local URL

As of the latest phase-2 pass:

```text
http://10.248.62.42:8000
```
