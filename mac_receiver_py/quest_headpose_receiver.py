#!/usr/bin/env python3
import ctypes
import json
import signal
import socket
import sys
import time
from pathlib import Path


KCG_HID_EVENT_TAP = 0
KCG_EVENT_MOUSE_MOVED = 5
KCG_MOUSE_BUTTON_LEFT = 0
KCG_MOUSE_EVENT_DELTA_X = 4
KCG_MOUSE_EVENT_DELTA_Y = 5


class CGPoint(ctypes.Structure):
    _fields_ = [("x", ctypes.c_double), ("y", ctypes.c_double)]


class ReceiverConfig:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.reload()

    def reload(self) -> None:
        values: dict[str, str] = {}
        for raw_line in self.path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key] = value

        self.listen_port = int(values.get("LISTEN_PORT", "7007"))
        self.sensitivity = float(values.get("SENSITIVITY", "18.0"))
        self.quest_ip = values.get("QUEST_IP", "")
        self.mac_ip = values.get("MAC_IP", "")
        self.deadzone_pixels = int(values.get("DEADZONE_PIXELS", "1"))
        self.max_step_pixels = int(values.get("MAX_STEP_PIXELS", "35"))
        self.smoothing_alpha = float(values.get("SMOOTHING_ALPHA", "0.25"))
        self.yaw_scale = float(values.get("YAW_SCALE", "1.0"))
        self.pitch_scale = float(values.get("PITCH_SCALE", "0.0"))


class MouseInjector:
    def __init__(self) -> None:
        self.core_graphics = ctypes.cdll.LoadLibrary(
            "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics"
        )
        self.app_services = ctypes.cdll.LoadLibrary(
            "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices"
        )
        self.core_foundation = ctypes.cdll.LoadLibrary(
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
        )

        self.cursor_is_visible = self.core_graphics.CGCursorIsVisible
        self.cursor_is_visible.restype = ctypes.c_int32

        self.ax_is_process_trusted = self.app_services.AXIsProcessTrusted
        self.ax_is_process_trusted.argtypes = []
        self.ax_is_process_trusted.restype = ctypes.c_bool

        self.create_event = self.app_services.CGEventCreateMouseEvent
        self.create_event.argtypes = [
            ctypes.c_void_p,
            ctypes.c_uint32,
            CGPoint,
            ctypes.c_uint32,
        ]
        self.create_event.restype = ctypes.c_void_p

        self.copy_event = self.app_services.CGEventCreate
        self.copy_event.argtypes = [ctypes.c_void_p]
        self.copy_event.restype = ctypes.c_void_p

        self.get_location = self.app_services.CGEventGetLocation
        self.get_location.argtypes = [ctypes.c_void_p]
        self.get_location.restype = CGPoint

        self.set_integer_value_field = self.app_services.CGEventSetIntegerValueField
        self.set_integer_value_field.argtypes = [
            ctypes.c_void_p,
            ctypes.c_uint32,
            ctypes.c_int64,
        ]

        self.post_event = self.app_services.CGEventPost
        self.post_event.argtypes = [ctypes.c_uint32, ctypes.c_void_p]

        self.release = self.core_foundation.CFRelease
        self.release.argtypes = [ctypes.c_void_p]

    def is_cursor_visible(self) -> bool:
        return self.cursor_is_visible() != 0

    def is_accessibility_trusted(self) -> bool:
        return bool(self.ax_is_process_trusted())

    def inject(self, delta_x: int, delta_y: int) -> None:
        if delta_x == 0 and delta_y == 0:
            return

        current_event = self.copy_event(None)
        if not current_event:
            return
        current_location = self.get_location(current_event)
        self.release(current_event)

        event = self.create_event(
            None,
            KCG_EVENT_MOUSE_MOVED,
            CGPoint(current_location.x + delta_x, current_location.y + delta_y),
            KCG_MOUSE_BUTTON_LEFT,
        )
        if not event:
            return

        self.set_integer_value_field(event, KCG_MOUSE_EVENT_DELTA_X, delta_x)
        self.set_integer_value_field(event, KCG_MOUSE_EVENT_DELTA_Y, delta_y)
        self.post_event(KCG_HID_EVENT_TAP, event)
        self.release(event)


class Receiver:
    def __init__(self, config_path: Path) -> None:
        self.config = ReceiverConfig(config_path)
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.socket.bind(("0.0.0.0", self.config.listen_port))
        self.socket.settimeout(1.0)
        self.injector = MouseInjector()
        self.quest_addr: tuple[str, int] | None = None
        self.receiver_start = time.time()
        self.reload_requested = False
        self.filtered_dx = 0.0
        self.filtered_dy = 0.0
        self.residual_dx = 0.0
        self.residual_dy = 0.0
        self.last_cursor_visible = True
        self.last_packet_at = 0.0
        self.last_heartbeat_at = 0.0

        signal.signal(signal.SIGHUP, self._request_reload)
        signal.signal(signal.SIGINT, self._exit_now)
        signal.signal(signal.SIGTERM, self._exit_now)

    def _request_reload(self, _signum, _frame) -> None:
        self.reload_requested = True

    def _exit_now(self, _signum, _frame) -> None:
        raise SystemExit(0)

    def run(self) -> None:
        print(f"Receiver listening on UDP {self.config.listen_port}", flush=True)
        if self.config.mac_ip:
            print(f"Configured Mac IP: {self.config.mac_ip}", flush=True)
        if self.config.quest_ip:
            print(f"Configured Quest IP: {self.config.quest_ip}", flush=True)
        print(f"Sensitivity: {self.config.sensitivity}", flush=True)
        print(
            f"Filtering: deadzone={self.config.deadzone_pixels}px max_step={self.config.max_step_pixels}px alpha={self.config.smoothing_alpha} yaw_scale={self.config.yaw_scale} pitch_scale={self.config.pitch_scale}",
            flush=True,
        )
        print("Mouse events only inject while the macOS cursor is hidden.", flush=True)
        if self.injector.is_accessibility_trusted():
            print("Accessibility permission is granted.", flush=True)
        else:
            print("Accessibility permission is required for synthetic mouse movement.", flush=True)

        while True:
            if self.reload_requested:
                self.config.reload()
                self.reload_requested = False
                print(f"Reloaded sensitivity: {self.config.sensitivity}", flush=True)
                print(
                    f"Reloaded filtering: deadzone={self.config.deadzone_pixels}px max_step={self.config.max_step_pixels}px alpha={self.config.smoothing_alpha} yaw_scale={self.config.yaw_scale} pitch_scale={self.config.pitch_scale}",
                    flush=True,
                )

            try:
                payload, peer = self.socket.recvfrom(4096)
            except socket.timeout:
                self.maybe_log_heartbeat()
                continue

            self.quest_addr = peer
            self.last_packet_at = time.time()
            self.handle_payload(payload)

    def handle_payload(self, payload: bytes) -> None:
        try:
            message = json.loads(payload.decode("utf-8"))
        except Exception:
            return

        message_type = message.get("type")

        if message_type == "hello":
            print(f"Quest hello from {message.get('questIp', 'unknown')}", flush=True)
            self.send_status("Receiver ready")
            return

        if message_type == "disconnect":
            print(f"Quest disconnected: {message.get('reason', 'unknown')}", flush=True)
            self.send_status("Receiver saw disconnect")
            return

        if message_type != "headpose":
            return

        yaw_delta = float(message.get("yawDelta", 0.0))
        pitch_delta = float(message.get("pitchDelta", 0.0))
        cursor_visible = self.injector.is_cursor_visible()
        raw_dx = yaw_delta * self.config.sensitivity * self.config.yaw_scale
        raw_dy = -pitch_delta * self.config.sensitivity * self.config.pitch_scale
        if cursor_visible:
            self.reset_motion_filter()
            dx = 0
            dy = 0
        else:
            dx, dy = self.filter_motion(raw_dx, raw_dy)
            self.injector.inject(dx, dy)
        self.last_cursor_visible = cursor_visible

        quest_ip = message.get("questIp", "unknown")
        yaw = float(message.get("yaw", 0.0))
        pitch = float(message.get("pitch", 0.0))
        mode = message.get("mode", "window")
        uptime = int(time.time() - self.receiver_start)
        if cursor_visible:
            gate = "blocked(cursor visible)"
            status_message = "Cursor visible, injection paused"
        elif dx == 0 and dy == 0:
            gate = "tracking(hidden cursor, output below threshold)"
            status_message = "Tracking headpose; motion below output threshold"
        else:
            gate = "injecting"
            status_message = "Injecting hidden-cursor motion"
        print(
            f"[{uptime}s] {quest_ip} mode={mode} yaw={yaw:.2f} pitch={pitch:.2f} raw=({raw_dx:.2f},{raw_dy:.2f}) step=({dx},{dy}) {gate}",
            flush=True,
        )
        self.send_status(status_message)

    def send_status(self, message: str) -> None:
        if not self.quest_addr:
            return

        payload = json.dumps(
            {
                "type": "status",
                "receiverRunning": True,
                "cursorVisible": self.injector.is_cursor_visible(),
                "sensitivity": self.config.sensitivity,
                "macIp": self.config.mac_ip,
                "message": message,
            }
        ).encode("utf-8")

        try:
            self.socket.sendto(payload, self.quest_addr)
        except Exception:
            pass

    def maybe_log_heartbeat(self) -> None:
        now = time.time()
        if now - self.last_heartbeat_at < 5.0:
            return
        self.last_heartbeat_at = now
        if self.last_packet_at == 0.0:
            print("Receiver alive; waiting for Quest packets...", flush=True)
            return
        age = now - self.last_packet_at
        if self.quest_addr:
            print(
                f"Receiver alive; last Quest packet was {age:.1f}s ago from {self.quest_addr[0]}:{self.quest_addr[1]}",
                flush=True,
            )
        else:
            print(f"Receiver alive; last Quest packet was {age:.1f}s ago", flush=True)

    def filter_motion(self, raw_dx: float, raw_dy: float) -> tuple[int, int]:
        alpha = max(0.0, min(1.0, self.config.smoothing_alpha))
        effective_raw_dx = 0.0 if abs(raw_dx) < self.config.deadzone_pixels else raw_dx
        effective_raw_dy = 0.0 if abs(raw_dy) < self.config.deadzone_pixels else raw_dy
        self.filtered_dx = (1.0 - alpha) * self.filtered_dx + alpha * effective_raw_dx
        self.filtered_dy = (1.0 - alpha) * self.filtered_dy + alpha * effective_raw_dy
        if effective_raw_dx == 0.0 and abs(self.filtered_dx) < 0.05:
            self.filtered_dx = 0.0
        if effective_raw_dy == 0.0 and abs(self.filtered_dy) < 0.05:
            self.filtered_dy = 0.0

        clamped_dx = max(-self.config.max_step_pixels, min(self.config.max_step_pixels, self.filtered_dx))
        clamped_dy = max(-self.config.max_step_pixels, min(self.config.max_step_pixels, self.filtered_dy))

        self.residual_dx += clamped_dx
        self.residual_dy += clamped_dy

        step_dx = int(round(self.residual_dx))
        step_dy = int(round(self.residual_dy))

        self.residual_dx -= step_dx
        self.residual_dy -= step_dy

        return step_dx, step_dy

    def reset_motion_filter(self) -> None:
        self.filtered_dx = 0.0
        self.filtered_dy = 0.0
        self.residual_dx = 0.0
        self.residual_dy = 0.0


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] != "run":
        print("Usage: quest_headpose_receiver.py run <config_path>", file=sys.stderr)
        return 2

    receiver = Receiver(Path(sys.argv[2]))
    receiver.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
