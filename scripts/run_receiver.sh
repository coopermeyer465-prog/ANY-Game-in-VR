#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="${TMPDIR:-/tmp}/games-in-vr-run"
LOG_FILE="$RUN_DIR/quest_headpose_receiver.log"
CONFIG_FILE="$ROOT_DIR/config/quest_headpose.env"
RECEIVER_BIN="$ROOT_DIR/mac_receiver_py/quest_headpose_receiver.py"

mkdir -p "$RUN_DIR"
touch "$LOG_FILE"

detect_mac_ip() {
  local target_ip
  target_ip="$(awk -F= '/^QUEST_IP=/{print $2; exit}' "$CONFIG_FILE")"
  local iface
  iface="$(route -n get "${target_ip:-192.168.0.63}" 2>/dev/null | awk '/interface:/{print $2; exit}')"
  if [[ -n "$iface" ]] && ifconfig "$iface" >/dev/null 2>&1; then
    local routed
    routed="$(ifconfig "$iface" | awk '/inet / && $2 != "127.0.0.1" {print $2; exit}')"
    if [[ -n "$routed" ]]; then
      echo "$routed"
      return
    fi
  fi
  for candidate in en0 en1; do
    if ifconfig "$candidate" >/dev/null 2>&1; then
      local detected
      detected="$(ifconfig "$candidate" | awk '/inet / && $2 != "127.0.0.1" {print $2; exit}')"
      if [[ -n "$detected" ]]; then
        echo "$detected"
        return
      fi
    fi
  done
}

if MAC_IP="$(detect_mac_ip)"; [[ -n "$MAC_IP" ]]; then
  perl -0pi -e "s/^MAC_IP=.*$/MAC_IP=$MAC_IP/m" "$CONFIG_FILE"
fi

python3 -u "$RECEIVER_BIN" run "$CONFIG_FILE" 2>&1 | tee -a "$LOG_FILE"
