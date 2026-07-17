#!/usr/bin/env bash
set -euo pipefail

LABEL="${LABEL:-com.quantbridge.runner-awake-window}"
START_HOUR="${START_HOUR:-4}"
START_MINUTE="${START_MINUTE:-45}"
DURATION_SECONDS="${DURATION_SECONDS:-36000}"
WAKE_DAYS="${WAKE_DAYS:-TWRFS}"
PLIST_PATH="$HOME/Library/LaunchAgents/${LABEL}.plist"
SCRIPT_PATH="$HOME/actions-runners/quantbridge-fullstack/keep_awake_window.sh"
LOG_DIR="$HOME/Library/Logs/QuantBridge"

start_now=false
schedule_wake=false

for arg in "$@"; do
  case "$arg" in
    --start-now) start_now=true ;;
    --schedule-wake) schedule_wake=true ;;
    *)
      echo "Unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

mkdir -p "$(dirname "$SCRIPT_PATH")" "$HOME/Library/LaunchAgents" "$LOG_DIR"

cat > "$SCRIPT_PATH" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

DURATION_SECONDS="${DURATION_SECONDS:-36000}"
LOG_DIR="$HOME/Library/Logs/QuantBridge"
mkdir -p "$LOG_DIR"
log="$LOG_DIR/runner-awake-window.log"

{
  echo "[$(date -Is)] keeping QuantBridge runners awake for ${DURATION_SECONDS}s"
  echo "[$(date -Is)] caffeinate flags: -ims"
} >> "$log"

/usr/bin/caffeinate -ims -t "$DURATION_SECONDS" >> "$log" 2>&1

echo "[$(date -Is)] awake window ended" >> "$log"
SCRIPT
chmod +x "$SCRIPT_PATH"

cat > "$PLIST_PATH" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${SCRIPT_PATH}</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>DURATION_SECONDS</key>
    <string>${DURATION_SECONDS}</string>
  </dict>
  <key>StartCalendarInterval</key>
  <array>
    <dict><key>Weekday</key><integer>2</integer><key>Hour</key><integer>${START_HOUR}</integer><key>Minute</key><integer>${START_MINUTE}</integer></dict>
    <dict><key>Weekday</key><integer>3</integer><key>Hour</key><integer>${START_HOUR}</integer><key>Minute</key><integer>${START_MINUTE}</integer></dict>
    <dict><key>Weekday</key><integer>4</integer><key>Hour</key><integer>${START_HOUR}</integer><key>Minute</key><integer>${START_MINUTE}</integer></dict>
    <dict><key>Weekday</key><integer>5</integer><key>Hour</key><integer>${START_HOUR}</integer><key>Minute</key><integer>${START_MINUTE}</integer></dict>
    <dict><key>Weekday</key><integer>6</integer><key>Hour</key><integer>${START_HOUR}</integer><key>Minute</key><integer>${START_MINUTE}</integer></dict>
  </array>
  <key>StandardOutPath</key>
  <string>${LOG_DIR}/runner-awake-window.out.log</string>
  <key>StandardErrorPath</key>
  <string>${LOG_DIR}/runner-awake-window.err.log</string>
</dict>
</plist>
PLIST

plutil -lint "$PLIST_PATH" >/dev/null
launchctl bootout "gui/$(id -u)" "$PLIST_PATH" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$PLIST_PATH"
launchctl enable "gui/$(id -u)/${LABEL}"

if [[ "$start_now" == "true" ]]; then
  launchctl kickstart -k "gui/$(id -u)/${LABEL}"
fi

if [[ "$schedule_wake" == "true" ]]; then
  echo "Scheduling system wake requires administrator privileges."
  sudo pmset repeat wakeorpoweron "$WAKE_DAYS" "$(printf '%02d:%02d:00' "$START_HOUR" "$START_MINUTE")"
fi

cat <<EOF
Installed ${LABEL}
  plist:  ${PLIST_PATH}
  script: ${SCRIPT_PATH}
  time:   Tue-Sat $(printf '%02d:%02d' "$START_HOUR" "$START_MINUTE") for ${DURATION_SECONDS}s

To wake a sleeping Mac before the window, run:
  sudo pmset repeat wakeorpoweron ${WAKE_DAYS} $(printf '%02d:%02d:00' "$START_HOUR" "$START_MINUTE")
EOF
