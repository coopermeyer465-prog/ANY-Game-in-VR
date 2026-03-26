#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DEV_SH="$ROOT_DIR/dev.sh"

ACTION="${1:-}"
if [[ -z "$ACTION" ]]; then
  ACTION="$(osascript "$ROOT_DIR/scripts/quest_shortcut_menu.applescript")"
fi

case "$ACTION" in
  "Connect to Quest")
    exec "$DEV_SH" connect
    ;;
  "Disconnect Quest")
    exec "$DEV_SH" disconnect
    ;;
  "Detect Quest IP")
    exec "$DEV_SH" detect_quest_ip
    ;;
  "Install Quest App")
    exec "$DEV_SH" install_quest_app
    ;;
  "Change Head Movement Sensitivity")
    VALUE="${2:-}"
    if [[ -z "$VALUE" ]]; then
      VALUE="$(osascript -e 'text returned of (display dialog "Sensitivity" default answer "18.0")')"
    fi
    exec "$DEV_SH" set_sensitivity "$VALUE"
    ;;
  *)
    echo "Unknown shortcut action: $ACTION" >&2
    exit 1
    ;;
esac
