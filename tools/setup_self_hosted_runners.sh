#!/usr/bin/env bash
set -euo pipefail

REPO="${REPO:-nojinho12392-lgtm/quantbridge-fullstack}"
RUNNER_COUNT="${RUNNER_COUNT:-4}"
RUNNER_ROOT="${RUNNER_ROOT:-$HOME/actions-runners/quantbridge-fullstack}"
RUNNER_LABELS="${RUNNER_LABELS:-quantbridge}"

case "$(uname -s)" in
  Darwin) runner_os="osx" ;;
  Linux) runner_os="linux" ;;
  *) echo "Unsupported OS: $(uname -s)" >&2; exit 2 ;;
esac

case "$(uname -m)" in
  arm64|aarch64) runner_arch="arm64" ;;
  x86_64|amd64) runner_arch="x64" ;;
  *) echo "Unsupported architecture: $(uname -m)" >&2; exit 2 ;;
esac

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI (gh) is required." >&2
  exit 2
fi

gh auth status -h github.com >/dev/null

latest_json="$(gh api /repos/actions/runner/releases/latest)"
asset_name="$(jq -r --arg suffix "actions-runner-${runner_os}-${runner_arch}-" '
  .assets[]
  | select(.name | startswith($suffix) and endswith(".tar.gz"))
  | .name
' <<<"$latest_json" | head -n 1)"
asset_url="$(jq -r --arg name "$asset_name" '
  .assets[]
  | select(.name == $name)
  | .browser_download_url
' <<<"$latest_json")"

if [[ -z "$asset_name" || "$asset_name" == "null" || -z "$asset_url" || "$asset_url" == "null" ]]; then
  echo "Could not find a runner asset for ${runner_os}-${runner_arch}." >&2
  exit 2
fi

repo_url="https://github.com/${REPO}"
mkdir -p "$RUNNER_ROOT"
archive="$RUNNER_ROOT/$asset_name"

if [[ ! -f "$archive" ]]; then
  echo "Downloading $asset_name"
  curl -fsSL "$asset_url" -o "$archive"
fi

token="$(gh api -X POST "/repos/${REPO}/actions/runners/registration-token" --jq .token)"

for index in $(seq 1 "$RUNNER_COUNT"); do
  runner_dir="$RUNNER_ROOT/runner-$index"
  runner_name="quantbridge-$(hostname -s)-$index"
  mkdir -p "$runner_dir"

  if [[ ! -x "$runner_dir/config.sh" ]]; then
    tar -xzf "$archive" -C "$runner_dir"
  fi

  pushd "$runner_dir" >/dev/null
  if [[ ! -f .runner ]]; then
    ./config.sh \
      --unattended \
      --url "$repo_url" \
      --token "$token" \
      --name "$runner_name" \
      --labels "$RUNNER_LABELS" \
      --work "_work" \
      --replace
  fi

  started_by_service=false
  if [[ "$(uname -s)" == "Darwin" ]]; then
    ./svc.sh install >/tmp/quantbridge-runner-svc.log 2>&1 || true
    ./svc.sh start >>/tmp/quantbridge-runner-svc.log 2>&1 || true
    if ./svc.sh status 2>&1 | grep -Eqi "Started|running"; then
      started_by_service=true
      rm -f .runner.pid
    fi
  fi

  if [[ "$started_by_service" != "true" ]]; then
    if [[ -f ".runner.pid" ]] && kill -0 "$(cat .runner.pid)" >/dev/null 2>&1; then
      echo "$runner_name is already running with PID $(cat .runner.pid)"
    else
      nohup ./run.sh > runner.log 2>&1 &
      echo "$!" > .runner.pid
      echo "$runner_name started in the background with PID $(cat .runner.pid)"
    fi
  fi
  popd >/dev/null
done

echo "Registered ${RUNNER_COUNT} runner(s) for ${REPO} with label(s): ${RUNNER_LABELS}"
