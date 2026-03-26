#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_FILE="$ROOT_DIR/run/quest_headpose_receiver.log"
CONFIG_FILE="$ROOT_DIR/config/quest_headpose.env"
RECEIVER_BIN="$ROOT_DIR/mac_receiver/.build/debug/quest-headpose-receiver"

mkdir -p "$ROOT_DIR/run"
touch "$LOG_FILE"

exec "$RECEIVER_BIN" run "$CONFIG_FILE" 2>&1 | tee -a "$LOG_FILE"
