#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUN_DIR="${TMPDIR:-/tmp}/games-in-vr-run"
LOG_FILE="$RUN_DIR/quest_headpose_desktop_streamer.log"
STREAMER_BIN="$ROOT_DIR/mac_streamer/desktop_streamer.swift"
BUILD_DIR="$ROOT_DIR/.build/mac"
STREAMER_EXEC="$BUILD_DIR/desktop_streamer"
CONFIG_FILE="$ROOT_DIR/config/quest_headpose.env"

mkdir -p "$RUN_DIR"
: >"$LOG_FILE"

if [[ -f "$CONFIG_FILE" ]]; then
  set -a
  source "$CONFIG_FILE"
  set +a
fi

mkdir -p "$BUILD_DIR"
swiftc "$STREAMER_BIN" -o "$STREAMER_EXEC"
"$STREAMER_EXEC" 2>&1 | tee -a "$LOG_FILE"
