#!/usr/bin/env python3
import ctypes
import json
import math
import signal
import socket
import sys
import threading
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
        self.sensitivity = float(values.get("SENSITIVITY", "240.0"))
        self.quest_ip = values.get("QUEST_IP", "")
        self.mac_ip = values.get("MAC_IP", "")
        self.visible_cursor_test = values.get("VISIBLE_CURSOR_TEST", "1").strip().lower() in {"1", "true", "yes", "on"}
        self.deadzone_pixels = int(values.get("DEADZONE_PIXELS", "0"))
        self.max_step_pixels = int(values.get("MAX_STEP_PIXELS", "160"))
        self.min_step_pixels = int(values.get("MIN_STEP_PIXELS", "2"))
        self.smoothing_alpha = float(values.get("SMOOTHING_ALPHA", "0.08"))
        self.yaw_scale = float(values.get("YAW_SCALE", "2.2"))
        self.pitch_scale = float(values.get("PITCH_SCALE", "2.2"))
        self.yaw_deadzone_deg = float(values.get("YAW_DEADZONE_DEG", "0.05"))
        self.pitch_deadzone_deg = float(values.get("PITCH_DEADZONE_DEG", "0.04"))
        self.response_exponent = float(values.get("RESPONSE_EXPONENT", "0.85"))


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

        if self.is_cursor_visible():
            self.inject_absolute(delta_x, delta_y, current_location)
        else:
            self.inject_relative(delta_x, delta_y, current_location)

    def inject_absolute(self, delta_x: int, delta_y: int, current_location: CGPoint) -> None:
        event = self.create_event(
            None,
            KCG_EVENT_MOUSE_MOVED,
            CGPoint(current_location.x + delta_x, current_location.y - delta_y),
            KCG_MOUSE_BUTTON_LEFT,
        )
        if not event:
            return

        self.set_integer_value_field(event, KCG_MOUSE_EVENT_DELTA_X, delta_x)
        self.set_integer_value_field(event, KCG_MOUSE_EVENT_DELTA_Y, -delta_y)
        self.post_event(KCG_HID_EVENT_TAP, event)
        self.release(event)

    def inject_relative(self, delta_x: int, delta_y: int, current_location: CGPoint) -> None:
        event = self.create_event(
            None,
            KCG_EVENT_MOUSE_MOVED,
            current_location,
            KCG_MOUSE_BUTTON_LEFT,
        )
        if not event:
            return

        self.set_integer_value_field(event, KCG_MOUSE_EVENT_DELTA_X, delta_x)
        self.set_integer_value_field(event, KCG_MOUSE_EVENT_DELTA_Y, -delta_y)
        self.post_event(KCG_HID_EVENT_TAP, event)
        self.release(event)


class Receiver:
    def __init__(self, config_path: Path) -> None:
        self.config = ReceiverConfig(config_path)
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.socket.bind(("0.0.0.0", self.config.listen_port))
        self.socket.settimeout(1.0)
        self.tcp_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.tcp_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.tcp_server.bind(("0.0.0.0", self.config.listen_port))
        self.tcp_server.listen(1)
        self.tcp_server.settimeout(1.0)
        self.injector = MouseInjector()
        self.quest_addr: tuple[str, int] | None = None
        self.reply_transport = "udp"
        self.tcp_conn: socket.socket | None = None
        self.tcp_lock = threading.Lock()
        self.receiver_start = time.time()
        self.reload_requested = False
        self.output_dx = 0.0
        self.output_dy = 0.0
        self.smoothed_yaw = 0.0
        self.smoothed_pitch = 0.0
        self.pose_initialized = False
        self.motion_initialized = False
        self.last_motion_yaw = 0.0
        self.last_motion_pitch = 0.0
        self.last_cursor_visible = True
        self.last_packet_at = 0.0
        self.last_heartbeat_at = 0.0
        self.last_motion_log_at = 0.0
        self.last_status_sent_at = 0.0
        self.last_status_message = ""
        self.target_dx = 0.0
        self.target_dy = 0.0
        self.current_mode = "window"
        self.latest_yaw = 0.0
        self.latest_pitch = 0.0
        self.motion_lock = threading.Lock()

        signal.signal(signal.SIGHUP, self._request_reload)
        signal.signal(signal.SIGINT, self._exit_now)
        signal.signal(signal.SIGTERM, self._exit_now)

    def _request_reload(self, _signum, _frame) -> None:
        self.reload_requested = True

    def _exit_now(self, _signum, _frame) -> None:
        raise SystemExit(0)

    def run(self) -> None:
        print(f"Receiver listening on UDP {self.config.listen_port}", flush=True)
        print(f"Receiver listening on TCP {self.config.listen_port}", flush=True)
        if self.config.mac_ip:
            print(f"Configured Mac IP: {self.config.mac_ip}", flush=True)
        if self.config.quest_ip:
            print(f"Configured Quest IP: {self.config.quest_ip}", flush=True)
        print(f"Sensitivity: {self.config.sensitivity}", flush=True)
        print(
            f"Filtering: yaw_deadzone={self.config.yaw_deadzone_deg}deg pitch_deadzone={self.config.pitch_deadzone_deg}deg min_step={self.config.min_step_pixels}px max_step={self.config.max_step_pixels}px alpha={self.config.smoothing_alpha} response={self.config.response_exponent} yaw_scale={self.config.yaw_scale} pitch_scale={self.config.pitch_scale}",
            flush=True,
        )
        if self.config.visible_cursor_test:
            print("Visible cursor test mode is enabled.", flush=True)
        else:
            print("Mouse events only inject while the macOS cursor is hidden.", flush=True)
        if self.injector.is_accessibility_trusted():
            print("Accessibility permission is granted.", flush=True)
        else:
            print("Accessibility permission is required for synthetic mouse movement.", flush=True)

        threading.Thread(target=self.run_tcp_server, daemon=True).start()
        threading.Thread(target=self.run_output_loop, daemon=True).start()

        while True:
            if self.reload_requested:
                self.config.reload()
                self.reload_requested = False
                print(f"Reloaded sensitivity: {self.config.sensitivity}", flush=True)
                print(
                    f"Reloaded filtering: yaw_deadzone={self.config.yaw_deadzone_deg}deg pitch_deadzone={self.config.pitch_deadzone_deg}deg min_step={self.config.min_step_pixels}px max_step={self.config.max_step_pixels}px alpha={self.config.smoothing_alpha} response={self.config.response_exponent} yaw_scale={self.config.yaw_scale} pitch_scale={self.config.pitch_scale}",
                    flush=True,
                )

            try:
                payload, peer = self.socket.recvfrom(4096)
            except socket.timeout:
                self.maybe_log_heartbeat()
                continue

            self.quest_addr = peer
            self.reply_transport = "udp"
            self.last_packet_at = time.time()
            self.handle_payload(payload)

    def run_tcp_server(self) -> None:
        while True:
            try:
                conn, peer = self.tcp_server.accept()
            except socket.timeout:
                continue
            except Exception:
                continue

            conn.settimeout(1.0)
            with self.tcp_lock:
                old_conn = self.tcp_conn
                self.tcp_conn = conn
            if old_conn is not None and old_conn is not conn:
                try:
                    old_conn.close()
                except Exception:
                    pass

            self.quest_addr = peer
            self.reply_transport = "tcp"
            print(f"Quest TCP connected from {peer[0]}:{peer[1]}", flush=True)

            buffer = b""
            try:
                while True:
                    try:
                        chunk = conn.recv(4096)
                    except socket.timeout:
                        continue
                    if not chunk:
                        break
                    buffer += chunk
                    while b"\n" in buffer:
                        line, buffer = buffer.split(b"\n", 1)
                        message = line.strip()
                        if not message:
                            continue
                        self.quest_addr = peer
                        self.reply_transport = "tcp"
                        self.last_packet_at = time.time()
                        self.handle_payload(message)
            except Exception:
                pass
            finally:
                with self.tcp_lock:
                    if self.tcp_conn is conn:
                        self.tcp_conn = None
                try:
                    conn.close()
                except Exception:
                    pass
                self.reset_motion_state()
                print(f"Quest TCP disconnected from {peer[0]}:{peer[1]}", flush=True)

    def run_output_loop(self) -> None:
        tick_hz = 180.0
        tick_interval = 1.0 / tick_hz
        while True:
            time.sleep(tick_interval)
            self.emit_motion_tick()

    def emit_motion_tick(self) -> None:
        with self.motion_lock:
            if self.last_packet_at == 0.0 or time.time() - self.last_packet_at > 0.25:
                return
            cursor_visible = self.injector.is_cursor_visible()
            if cursor_visible and not self.config.visible_cursor_test:
                self.reset_motion_state_locked()
                dx = 0
                dy = 0
                target_dx = 0.0
                target_dy = 0.0
                gate = "blocked(cursor visible)"
                status_message = "Cursor visible, injection paused"
            else:
                target_dx = self.target_dx * 0.72
                target_dy = self.target_dy * 0.72
                dx, dy = self.accumulate_motion(target_dx, target_dy)
                if dx != 0 or dy != 0:
                    self.injector.inject(dx, dy)
                if dx == 0 and dy == 0:
                    gate = "tracking(output below threshold)"
                    status_message = "Tracking headpose; motion below output threshold"
                elif cursor_visible:
                    gate = "injecting(visible test)"
                    status_message = "Injecting visible-cursor test motion"
                else:
                    gate = "injecting"
                    status_message = "Injecting hidden-cursor motion"

            yaw = self.latest_yaw
            pitch = self.latest_pitch
            mode = self.current_mode

        self.last_cursor_visible = cursor_visible
        self.maybe_log_motion(
            uptime=int(time.time() - self.receiver_start),
            quest_ip=self.quest_addr[0] if self.quest_addr else "unknown",
            mode=mode,
            yaw=yaw,
            pitch=pitch,
            target_dx=target_dx,
            target_dy=target_dy,
            dx=dx,
            dy=dy,
            gate=gate,
        )
        self.send_status(status_message, throttle=True)

    def handle_payload(self, payload: bytes) -> None:
        try:
            message = json.loads(payload.decode("utf-8"))
        except Exception:
            return

        message_type = message.get("type")

        if message_type == "hello":
            self.apply_requested_sensitivity(message)
            print(f"Quest hello from {message.get('questIp', 'unknown')}", flush=True)
            self.send_status("Receiver ready")
            return

        if message_type == "disconnect":
            print(f"Quest disconnected: {message.get('reason', 'unknown')}", flush=True)
            self.reset_motion_state()
            self.send_status("Receiver saw disconnect")
            return

        if message_type == "set_sensitivity":
            self.apply_requested_sensitivity(message)
            self.send_status(f"Sensitivity updated to {self.config.sensitivity:.0f}")
            return

        if message_type == "set_mouse_armed":
            self.reset_motion_state()
            self.send_status("Receiver ready")
            return

        if message_type == "recenter":
            self.reset_motion_state()
            self.send_status("Receiver recentered")
            return

        if message_type != "headpose":
            return

        self.apply_requested_sensitivity(message)
        yaw = float(message.get("yaw", 0.0))
        pitch = float(message.get("pitch", 0.0))
        smoothed_yaw, smoothed_pitch = self.smooth_pose(yaw, pitch)
        target_dx, target_dy = self.relative_motion(smoothed_yaw, smoothed_pitch)
        with self.motion_lock:
            self.target_dx = target_dx
            self.target_dy = target_dy
            self.latest_yaw = yaw
            self.latest_pitch = pitch
            self.current_mode = message.get("mode", "window")

    def maybe_log_motion(
        self,
        *,
        uptime: int,
        quest_ip: str,
        mode: str,
        yaw: float,
        pitch: float,
        target_dx: float,
        target_dy: float,
        dx: int,
        dy: int,
        gate: str,
    ) -> None:
        now = time.time()
        if now - self.last_motion_log_at < 0.20:
            return
        self.last_motion_log_at = now
        print(
            f"[{uptime}s] {quest_ip} mode={mode} yaw={yaw:.2f} pitch={pitch:.2f} rel=({target_dx:.2f},{target_dy:.2f}) step=({dx},{dy}) {gate}",
            flush=True,
        )

    def send_status(self, message: str, throttle: bool = False) -> None:
        now = time.time()
        if throttle:
            if message == self.last_status_message and now - self.last_status_sent_at < 0.25:
                return
            self.last_status_message = message
            self.last_status_sent_at = now

        payload = json.dumps(
            {
                "type": "status",
                "receiverRunning": True,
                "cursorVisible": self.injector.is_cursor_visible(),
                "sensitivity": self.config.sensitivity,
                "macIp": self.config.mac_ip,
                "message": message,
            }
        ).encode("utf-8") + b"\n"

        if self.reply_transport == "tcp":
            with self.tcp_lock:
                conn = self.tcp_conn
            if conn is not None:
                try:
                    conn.sendall(payload)
                    return
                except Exception:
                    pass

        if not self.quest_addr:
            return

        try:
            self.socket.sendto(payload.rstrip(b"\n"), self.quest_addr)
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

    def apply_requested_sensitivity(self, message: dict) -> None:
        requested = message.get("sensitivity")
        if requested is None:
            return
        try:
            value = float(requested)
        except (TypeError, ValueError):
            return
        if value <= 0:
            return
        self.config.sensitivity = value

    def shape_axis(self, delta_deg: float, deadzone_deg: float, axis_scale: float) -> float:
        magnitude = abs(delta_deg)
        if magnitude <= deadzone_deg:
            return 0.0
        adjusted = magnitude - deadzone_deg
        exponent = max(0.55, min(1.8, self.config.response_exponent))
        curved = adjusted ** exponent
        return math.copysign(curved * (self.config.sensitivity * 0.012) * axis_scale, delta_deg)

    def relative_motion(self, yaw: float, pitch: float) -> tuple[float, float]:
        if not self.motion_initialized:
            self.last_motion_yaw = yaw
            self.last_motion_pitch = pitch
            self.motion_initialized = True
            return 0.0, 0.0

        yaw_delta = self.normalize_angle(yaw - self.last_motion_yaw)
        pitch_delta = pitch - self.last_motion_pitch
        self.last_motion_yaw = yaw
        self.last_motion_pitch = pitch
        return (
            self.shape_axis(yaw_delta, self.config.yaw_deadzone_deg, self.config.yaw_scale),
            self.shape_axis(pitch_delta, self.config.pitch_deadzone_deg, self.config.pitch_scale),
        )

    def quantize_axis(self, value: float) -> int:
        if abs(value) < 0.5:
            return 0
        rounded = int(round(value))
        if rounded == 0:
            return 0
        if self.config.min_step_pixels > 0 and abs(rounded) < self.config.min_step_pixels:
            return int(math.copysign(self.config.min_step_pixels, rounded))
        return rounded

    def accumulate_motion(self, target_dx: float, target_dy: float) -> tuple[int, int]:
        clamped_dx = max(-self.config.max_step_pixels, min(self.config.max_step_pixels, target_dx))
        clamped_dy = max(-self.config.max_step_pixels, min(self.config.max_step_pixels, target_dy))

        self.output_dx += clamped_dx
        self.output_dy += clamped_dy

        dx = self.quantize_axis(self.output_dx)
        dy = self.quantize_axis(self.output_dy)

        self.output_dx -= dx
        self.output_dy -= dy
        return dx, dy

    def reset_motion_state(self) -> None:
        with self.motion_lock:
            self.reset_motion_state_locked()

    def reset_motion_state_locked(self) -> None:
        self.output_dx = 0.0
        self.output_dy = 0.0
        self.target_dx = 0.0
        self.target_dy = 0.0
        self.pose_initialized = False
        self.motion_initialized = False

    def smooth_pose(self, yaw: float, pitch: float) -> tuple[float, float]:
        if not self.pose_initialized:
            self.smoothed_yaw = yaw
            self.smoothed_pitch = pitch
            self.pose_initialized = True
            return self.smoothed_yaw, self.smoothed_pitch

        yaw_gap = abs(self.normalize_angle(yaw - self.smoothed_yaw))
        pitch_gap = abs(pitch - self.smoothed_pitch)
        motion_gap = max(yaw_gap, pitch_gap)
        base_alpha = max(0.03, min(0.14, self.config.smoothing_alpha))
        adaptive_alpha = min(0.24, base_alpha + min(0.10, motion_gap * 0.015))

        self.smoothed_yaw = self.blend_angle(self.smoothed_yaw, yaw, adaptive_alpha)
        self.smoothed_pitch = self.blend_linear(self.smoothed_pitch, pitch, adaptive_alpha)
        return self.smoothed_yaw, self.smoothed_pitch

    def blend_linear(self, current: float, target: float, alpha: float) -> float:
        return current + (target - current) * alpha

    def blend_angle(self, current: float, target: float, alpha: float) -> float:
        return self.normalize_angle(current + self.normalize_angle(target - current) * alpha)

    def normalize_angle(self, angle: float) -> float:
        while angle > 180.0:
            angle -= 360.0
        while angle <= -180.0:
            angle += 360.0
        return angle


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[1] != "run":
        print("Usage: quest_headpose_receiver.py run <config_path>", file=sys.stderr)
        return 2

    receiver = Receiver(Path(sys.argv[2]))
    receiver.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
