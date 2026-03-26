#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG_FILE="$ROOT_DIR/config/quest_headpose.env"
RUN_DIR="$ROOT_DIR/run"
GRADLE_USER_HOME_DIR="$ROOT_DIR/.gradle-home"
RECEIVER_PID_FILE="$RUN_DIR/quest_headpose_receiver.pid"
RECEIVER_LOG_FILE="$RUN_DIR/quest_headpose_receiver.log"
RECEIVER_LAUNCH_SCRIPT="$ROOT_DIR/scripts/run_receiver.sh"
JAVA_HOME_DEFAULT="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
ANDROID_HOME_DEFAULT="/opt/homebrew/share/android-commandlinetools"
ANDROID_PROJECT_DIR="$ROOT_DIR/quest_headpose_app"
RECEIVER_PROJECT_DIR="$ROOT_DIR/mac_receiver"

mkdir -p "$RUN_DIR"
mkdir -p "$GRADLE_USER_HOME_DIR"

if [[ -f "$CONFIG_FILE" ]]; then
  source "$CONFIG_FILE"
fi

QUEST_IP="${QUEST_IP:-192.168.0.63}"
ADB_PORT="${ADB_PORT:-5555}"
LISTEN_PORT="${LISTEN_PORT:-7007}"
QUEST_PORT="${QUEST_PORT:-7007}"
SENSITIVITY="${SENSITIVITY:-18.0}"
MAC_IP="${MAC_IP:-}"
ADB_WIFI_SERIAL="${QUEST_IP}:${ADB_PORT}"

write_config_value() {
  local key="$1"
  local value="$2"
  perl -0pi -e "s/^${key}=.*$/${key}=${value}/m" "$CONFIG_FILE"
}

list_adb_serials() {
  adb devices | awk 'NR>1 && $2=="device" {print $1}'
}

resolve_adb_target() {
  local serial

  if adb devices | awk 'NR>1 && $1=="'"$ADB_WIFI_SERIAL"'" && $2=="device" {found=1} END {exit(found ? 0 : 1)}'; then
    echo "$ADB_WIFI_SERIAL"
    return
  fi

  serial="$(adb devices | awk 'NR>1 && $2=="device" && $1 !~ /:5555$/ {print $1; exit}')"
  if [[ -n "$serial" ]]; then
    echo "$serial"
    return
  fi

  serial="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
  if [[ -n "$serial" ]]; then
    echo "$serial"
    return
  fi

  return 1
}

adb_on_target() {
  local target
  target="$(resolve_adb_target)" || {
    echo "No connected Quest device found in adb." >&2
    exit 1
  }
  adb -s "$target" "$@"
}

detect_mac_ip() {
  local target_ip="${1:-$QUEST_IP}"
  local iface
  iface="$(route -n get "$target_ip" 2>/dev/null | awk '/interface:/{print $2; exit}')"
  if [[ -n "$iface" ]]; then
    ipconfig getifaddr "$iface" 2>/dev/null || true
    return
  fi

  for candidate in en0 en1; do
    if ipconfig getifaddr "$candidate" >/dev/null 2>&1; then
      ipconfig getifaddr "$candidate"
      return
    fi
  done
}

ensure_java() {
  if [[ -z "${JAVA_HOME:-}" && -d "$JAVA_HOME_DEFAULT" ]]; then
    export JAVA_HOME="$JAVA_HOME_DEFAULT"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
  export GRADLE_USER_HOME="$GRADLE_USER_HOME_DIR"
}

ensure_android_sdk() {
  if [[ -d "$HOME/Library/Android/sdk" ]]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  elif [[ -d "$ANDROID_HOME_DEFAULT" ]]; then
    export ANDROID_HOME="$ANDROID_HOME_DEFAULT"
  else
    echo "Android SDK not found at $HOME/Library/Android/sdk or $ANDROID_HOME_DEFAULT" >&2
    exit 1
  fi

  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export PATH="$ANDROID_HOME/platform-tools:$PATH"
}

ensure_receiver_binary() {
  swift build --package-path "$RECEIVER_PROJECT_DIR"
}

receiver_pid() {
  ps -Ao pid=,command= | awk '
    index($0, "'"$RECEIVER_PROJECT_DIR"'/.build/debug/quest-headpose-receiver run '"$CONFIG_FILE"'") {
      print $1
      exit
    }
  '
}

start_receiver() {
  ensure_receiver_binary
  local pid
  pid="$(receiver_pid || true)"
  if [[ -n "$pid" ]]; then
    echo "$pid" >"$RECEIVER_PID_FILE"
    echo "Receiver already running (pid $pid)."
    return
  fi

  : >"$RECEIVER_LOG_FILE"
  osascript \
    -e 'tell application "Terminal" to activate' \
    -e "tell application \"Terminal\" to do script quoted form of \"$RECEIVER_LAUNCH_SCRIPT\""
  sleep 2
  pid="$(receiver_pid || true)"
  if [[ -n "$pid" ]]; then
    echo "$pid" >"$RECEIVER_PID_FILE"
    echo "Receiver started. Log: $RECEIVER_LOG_FILE"
  else
    echo "Receiver failed to stay running. Check: $RECEIVER_LOG_FILE" >&2
    exit 1
  fi
}

stop_receiver() {
  local pid
  pid="$(receiver_pid || true)"
  if [[ -n "$pid" ]]; then
    kill "$pid"
    echo "Receiver stopped."
  else
    echo "Receiver not running."
  fi
  rm -f "$RECEIVER_PID_FILE"
}

launch_quest_app() {
  adb_on_target shell am start \
    -n com.gamesinvr.questheadpose/.MainActivity \
    --es mac_ip "$MAC_IP" \
    --ei mac_port "$LISTEN_PORT" \
    --ez auto_connect true >/dev/null
}

case "${1:-}" in
  detect_quest_ip)
    QUEST_IP_DETECTED="$(adb_on_target shell ip -f inet addr show wlan0 | awk '/inet /{print $2}' | awk -F/ 'NR==1{print $1}')"
    if [[ -z "$QUEST_IP_DETECTED" ]]; then
      echo "Could not detect Quest IP over USB." >&2
      exit 1
    fi
    write_config_value "QUEST_IP" "$QUEST_IP_DETECTED"
    ADB_WIFI_SERIAL="${QUEST_IP_DETECTED}:${ADB_PORT}"
    echo "Quest IP updated to $QUEST_IP_DETECTED"
    ;;

  set_sensitivity)
    VALUE="${2:-}"
    if [[ -z "$VALUE" ]]; then
      echo "Usage: ./dev.sh set_sensitivity <float>" >&2
      exit 1
    fi
    write_config_value "SENSITIVITY" "$VALUE"
    if [[ -f "$RECEIVER_PID_FILE" ]] && kill -0 "$(cat "$RECEIVER_PID_FILE")" 2>/dev/null; then
      kill -HUP "$(cat "$RECEIVER_PID_FILE")"
    fi
    echo "Sensitivity updated to $VALUE"
    ;;

  build_quest_app)
    ensure_java
    ensure_android_sdk
    (
      cd "$ANDROID_PROJECT_DIR"
      ./gradlew --no-daemon assembleDebug
    )
    ;;

  install_quest_app)
    "$ROOT_DIR/dev.sh" build_quest_app
    adb_on_target install -r "$ANDROID_PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    ;;

  connect)
    MAC_IP="$(detect_mac_ip)"
    if [[ -z "$MAC_IP" ]]; then
      echo "Could not determine this Mac's IP." >&2
      exit 1
    fi
    write_config_value "MAC_IP" "$MAC_IP"
    adb connect "${QUEST_IP}:${ADB_PORT}" >/dev/null || true
    start_receiver
    launch_quest_app
    echo "Quest Headpose ready."
    echo "Mac IP: $MAC_IP"
    echo "Quest IP: $QUEST_IP"
    echo "Open the Quest app and press Connect if it is not already streaming."
    ;;

  disconnect)
    adb_on_target shell am start \
      -n com.gamesinvr.questheadpose/.MainActivity \
      --ez request_disconnect true >/dev/null || true
    adb disconnect "${QUEST_IP}:${ADB_PORT}" >/dev/null || true
    stop_receiver
    echo "Quest disconnected from ADB and receiver stopped."
    ;;

  receiver_status)
    if pid="$(receiver_pid || true)" && [[ -n "$pid" ]]; then
      echo "$pid" >"$RECEIVER_PID_FILE"
      echo "Receiver running (pid $pid)."
      echo "Log: $RECEIVER_LOG_FILE"
    else
      echo "Receiver not running."
    fi
    ;;

  shortcut)
    shift
    exec "$ROOT_DIR/scripts/quest_shortcut.sh" "$@"
    ;;

  *)
    cat <<'EOF'
Usage:
  ./dev.sh connect
  ./dev.sh disconnect
  ./dev.sh detect_quest_ip
  ./dev.sh set_sensitivity <float>
  ./dev.sh build_quest_app
  ./dev.sh install_quest_app
  ./dev.sh receiver_status
  ./dev.sh shortcut [action] [value]
EOF
    ;;
esac
