#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="${TMPDIR:-/tmp}/games-in-vr-run"
LOG_FILE="$RUN_DIR/quest_headpose_receiver.log"
CONFIG_FILE="$ROOT_DIR/config/quest_headpose.env"
RECEIVER_BIN="$ROOT_DIR/mac_receiver_py/quest_headpose_receiver.py"

mkdir -p "$RUN_DIR"
touch "$LOG_FILE"

python3 -u "$RECEIVER_BIN" run "$CONFIG_FILE" 2>&1 | tee -a "$LOG_FILE"
