#!/usr/bin/env bash
set -euo pipefail

export PATH="$HOME/.local/bin:$PATH"

python_bin=""
for candidate in \
  /opt/anaconda3/bin/python3.11 \
  /opt/homebrew/bin/python3.11 \
  /usr/local/bin/python3.11 \
  "$HOME/.local/bin/python3.11" \
  python3.11
do
  if [[ -x "$candidate" ]]; then
    python_bin="$candidate"
    break
  fi
  if command -v "$candidate" >/dev/null 2>&1; then
    python_bin="$(command -v "$candidate")"
    break
  fi
done

if [[ -z "$python_bin" ]]; then
  if ! command -v uv >/dev/null 2>&1; then
    if ! command -v curl >/dev/null 2>&1; then
      echo "Python 3.11 was not found and curl is unavailable to install uv." >&2
      exit 1
    fi
    curl -LsSf https://astral.sh/uv/install.sh | sh
    export PATH="$HOME/.local/bin:$PATH"
  fi

  if command -v uv >/dev/null 2>&1; then
    uv python install 3.11
    python_bin="$(uv python find 3.11)"
  fi
fi

if [[ -z "$python_bin" ]]; then
  echo "Python 3.11 was not found on this self-hosted runner." >&2
  exit 1
fi

version="$("$python_bin" - <<'PY'
import sys
print(f"{sys.version_info.major}.{sys.version_info.minor}")
PY
)"

if [[ "$version" != "3.11" ]]; then
  echo "Expected Python 3.11, got $version from $python_bin" >&2
  exit 1
fi

venv_root="${RUNNER_TEMP:-$PWD/.runner-temp}/quantbridge-python"
rm -rf "$venv_root"
"$python_bin" -m venv "$venv_root"
"$venv_root/bin/python" -m pip install --upgrade pip

{
  echo "$venv_root/bin"
} >> "$GITHUB_PATH"

{
  echo "VIRTUAL_ENV=$venv_root"
  echo "PYTHON_BIN=$venv_root/bin/python"
} >> "$GITHUB_ENV"

"$venv_root/bin/python" --version
"$venv_root/bin/python" -m pip --version
