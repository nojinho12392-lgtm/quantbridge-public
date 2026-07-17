# QuantBridge self-hosted runners

The daily parallel main engine uses runners with these labels:

```text
self-hosted, quantbridge-server
```

GitHub-hosted runners charge one Actions minute per runner minute. The QuantBridge
pipeline is a long-running data job, so it should run on self-hosted runners and
use GitHub Actions only for scheduling, logs, artifacts, and orchestration.

## One-time setup on this machine

The original MacBook runners can stay registered for ad-hoc work, but scheduled
QuantBridge jobs should use the dedicated server label so a laptop sleep or
network change cannot steal a production run.

Runner count controls true GitHub Actions parallelism. One registered runner can
execute one job at a time. Four runners lets the KR smallcap shard matrix run up
to the workflow's current `max-parallel: 4`.

## Daily operation

Keep the machine awake and online during the scheduled run window. On macOS, the
setup script installs each runner through `svc.sh`, so the runners start through
launchd for the current user.

The machine cannot execute jobs while it is fully asleep. To make it behave like
"sleep" while still running jobs, let the display turn off but keep the system
awake during the run window:

```bash
cd /Users/nohjinho/Desktop/Quant/quantbridge-fullstack
tools/install_runner_awake_window.sh
```

That installs a user LaunchAgent that runs `caffeinate -ims` from 04:45 KST on
Tuesday through Saturday for 10 hours. If the Mac may already be asleep before
04:45, also schedule a system wake:

```bash
sudo pmset repeat wakeorpoweron TWRFS 04:45:00
```

For a MacBook, keep the lid open, or use supported clamshell mode with power
connected. A closed, unplugged MacBook can still force sleep.

Check registered runners:

```bash
gh api /repos/nojinho12392-lgtm/quantbridge-fullstack/actions/runners \
  --jq '.runners[] | {name,status,busy,labels:[.labels[].name]}'
```

If a run queues forever, the usual causes are:

- no online runner has the `quantbridge-server` label
- the machine is asleep or offline
- all registered runners are already busy
- GitHub Actions is disabled for the repository
