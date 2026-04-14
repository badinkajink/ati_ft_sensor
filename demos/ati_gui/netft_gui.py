#!/usr/bin/env python3
"""Tkinter Net F/T GUI inspired by ATI's Java demo.

Features:
- Discover devices using ATI/RTA discovery protocol broadcast.
- Connect to a selected IP and stream low-speed RDT data.
- Display Fx/Fy/Fz/Tx/Ty/Tz, status, and packet sequence counters.
- Send tare command.
- Apply counts-per-force/torque scaling to match calibration setup.
"""

from __future__ import annotations

import ipaddress
import json
import os
import queue
import socket
import struct
import threading
import time
import tkinter as tk
from collections import deque
from dataclasses import dataclass
from tkinter import messagebox, ttk


RDT_PORT = 49152
DISCOVERY_SEND_PORT = 51000
DISCOVERY_RECEIVE_PORT = 28250
DISCOVERY_MULTICAST_IP = "224.0.5.128"
DISCOVERY_WAIT_SECONDS = 4.0

DISCOVERY_HEADER_REQ = b"RTA Device DiscoveryRTAD"
DISCOVERY_HEADER_RESP = b"RTAD"

TAG_MAC = 0x01
TAG_IP = 0x02
TAG_MASK = 0x03
TAG_APP = 0x0D
TAG_DELAY_MULT = 0xF2

RDT_CMD_STOP = 0x0000
RDT_CMD_SINGLE = 0x0002
RDT_CMD_TARE = 0x0042

SETTINGS_FILE = "ATINetFTDemoOptions.json"
AXES = ("Fx", "Fy", "Fz", "Tx", "Ty", "Tz")
AXIS_COLORS = {
    "Fx": "#0d47a1",
    "Fy": "#1b5e20",
    "Fz": "#bf360c",
    "Tx": "#4a148c",
    "Ty": "#006064",
    "Tz": "#5d4037",
}


@dataclass
class DeviceInfo:
    ip: str
    mac: str
    app: str


def make_rdt_command(command: int, count: int) -> bytes:
    return struct.pack("!HHI", 0x1234, command, count)


def parse_rdt_packet(data: bytes) -> tuple[int, int, int, int, int, int, int, int, int]:
    if len(data) < 36:
        raise ValueError(f"short RDT packet length={len(data)}")
    return struct.unpack("!IIIiiiiii", data[:36])


def is_valid_ipv4(value: str) -> bool:
    try:
        ipaddress.IPv4Address(value)
        return True
    except ValueError:
        return False


def parse_discovery_response(payload: bytes) -> DeviceInfo | None:
    if not payload.startswith(DISCOVERY_HEADER_RESP):
        return None
    pos = len(DISCOVERY_HEADER_RESP)
    ip = ""
    mac = ""
    app = ""

    while pos + 2 <= len(payload):
        tag = payload[pos]
        length = payload[pos + 1]
        start = pos + 2
        end = start + length
        if end > len(payload):
            break

        field = payload[start:end]
        if tag == TAG_IP and length == 4:
            ip = ".".join(str(b) for b in field)
        elif tag == TAG_MAC and length > 0:
            mac = "-".join(f"{b:02x}" for b in field)
        elif tag == TAG_APP and length > 0:
            app = field.decode("latin-1", errors="ignore")

        pos = end

    if not ip:
        return None
    return DeviceInfo(ip=ip, mac=mac, app=app)


def discover_devices() -> list[DeviceInfo]:
    req = bytearray(DISCOVERY_HEADER_REQ)
    req.extend([TAG_IP, 4])
    req.extend(int(octet) for octet in DISCOVERY_MULTICAST_IP.split("."))
    req.extend([TAG_DELAY_MULT, 1, 10])

    found: dict[str, DeviceInfo] = {}
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(0.2)
        sock.bind(("", DISCOVERY_RECEIVE_PORT))
        sock.sendto(bytes(req), ("255.255.255.255", DISCOVERY_SEND_PORT))

        end_time = time.monotonic() + DISCOVERY_WAIT_SECONDS
        while time.monotonic() < end_time:
            try:
                payload, _ = sock.recvfrom(1024)
            except socket.timeout:
                continue
            info = parse_discovery_response(payload)
            if info is not None:
                found[info.ip] = info
    finally:
        sock.close()

    return sorted(found.values(), key=lambda d: tuple(int(x) for x in d.ip.split(".")))


class NetFTRdtClient:
    def __init__(self, ip: str, local_port: int, timeout: float):
        self.ip = ip
        self.local_port = local_port
        self.timeout = timeout
        self.sock: socket.socket | None = None

    def open(self) -> None:
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.settimeout(self.timeout)
        if self.local_port > 0:
            self.sock.bind(("0.0.0.0", self.local_port))
        self.sock.connect((self.ip, RDT_PORT))

    def close(self) -> None:
        if self.sock is None:
            return
        try:
            self.sock.send(make_rdt_command(RDT_CMD_STOP, 0))
        except OSError:
            pass
        self.sock.close()
        self.sock = None

    def read_single(self) -> tuple[int, int, int, int, int, int, int, int, int]:
        if self.sock is None:
            raise RuntimeError("socket not open")
        self.sock.send(make_rdt_command(RDT_CMD_SINGLE, 1))
        data = self.sock.recv(2048)
        return parse_rdt_packet(data)

    def tare(self) -> None:
        if self.sock is None:
            raise RuntimeError("socket not open")
        self.sock.send(make_rdt_command(RDT_CMD_TARE, 1))


class NetFTGui(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("ATI Net F/T Demo (Tkinter)")
        self.geometry("980x680")
        self.minsize(900, 620)

        self.msg_queue: queue.Queue[tuple[str, object]] = queue.Queue()
        self.stop_event = threading.Event()
        self.reader_thread: threading.Thread | None = None
        self.client: NetFTRdtClient | None = None

        self._settings = self._load_settings()

        self.ip_var = tk.StringVar(value=self._settings.get("last_ip", "192.168.1.1"))
        self.local_port_var = tk.StringVar(value=str(self._settings.get("local_port", 49152)))
        self.counts_force_var = tk.StringVar(value=str(self._settings.get("counts_force", 1000000.0)))
        self.counts_torque_var = tk.StringVar(value=str(self._settings.get("counts_torque", 1000000.0)))
        self.status_var = tk.StringVar(value="Disconnected")
        self.seq_var = tk.StringVar(value="RDT seq: - | FT seq: - | Status: -")
        self.history_enabled_var = tk.BooleanVar(value=bool(self._settings.get("show_history", True)))
        self.auto_scale_var = tk.BooleanVar(value=bool(self._settings.get("history_auto_scale", True)))
        self.history_duration_var = tk.StringVar(value=str(self._settings.get("history_duration", 10)))
        self.last_mac_address = self._settings.get("last_mac", "")

        self.ft_vars = {
            "Fx": tk.StringVar(value="0.000"),
            "Fy": tk.StringVar(value="0.000"),
            "Fz": tk.StringVar(value="0.000"),
            "Tx": tk.StringVar(value="0.000"),
            "Ty": tk.StringVar(value="0.000"),
            "Tz": tk.StringVar(value="0.000"),
        }

        self.axis_visible_vars: dict[str, tk.BooleanVar] = {
            axis: tk.BooleanVar(value=True) for axis in AXES
        }
        if isinstance(self._settings.get("visible_axes"), dict):
            for axis, enabled in self._settings["visible_axes"].items():
                if axis in self.axis_visible_vars:
                    self.axis_visible_vars[axis].set(bool(enabled))

        self.history: dict[str, deque[tuple[float, float]]] = {
            axis: deque() for axis in AXES
        }

        self._build_ui()
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.after(250, self._redraw_history_plot)
        self.after(100, self._drain_queue)

    def _build_ui(self) -> None:
        root = ttk.Frame(self, padding=10)
        root.pack(fill=tk.BOTH, expand=True)

        top = ttk.Frame(root)
        top.pack(fill=tk.X)

        ttk.Label(top, text="IP Address:").grid(row=0, column=0, sticky="w")
        ttk.Entry(top, textvariable=self.ip_var, width=18).grid(row=0, column=1, padx=6)

        ttk.Button(top, text="Refresh Devices", command=self.on_refresh_devices).grid(row=0, column=2, padx=6)
        ttk.Button(top, text="Connect", command=self.on_connect).grid(row=0, column=3, padx=6)
        ttk.Button(top, text="Disconnect", command=self.on_disconnect).grid(row=0, column=4, padx=6)
        ttk.Button(top, text="Tare", command=self.on_tare).grid(row=0, column=5, padx=6)

        ttk.Label(top, text="Local Port:").grid(row=1, column=0, sticky="w", pady=(8, 0))
        ttk.Entry(top, textvariable=self.local_port_var, width=10).grid(row=1, column=1, sticky="w", padx=6, pady=(8, 0))

        ttk.Label(top, text="Counts/Force:").grid(row=1, column=2, sticky="e", pady=(8, 0))
        ttk.Entry(top, textvariable=self.counts_force_var, width=10).grid(row=1, column=3, sticky="w", pady=(8, 0))

        ttk.Label(top, text="Counts/Torque:").grid(row=1, column=4, sticky="e", pady=(8, 0))
        ttk.Entry(top, textvariable=self.counts_torque_var, width=10).grid(row=1, column=5, sticky="w", pady=(8, 0))

        ttk.Checkbutton(
            top,
            text="Show History",
            variable=self.history_enabled_var,
            command=self._toggle_history_view,
        ).grid(row=2, column=0, sticky="w", pady=(8, 0))

        ttk.Label(top, text="Duration (s):").grid(row=2, column=1, sticky="e", pady=(8, 0))
        ttk.Entry(top, textvariable=self.history_duration_var, width=8).grid(
            row=2, column=2, sticky="w", pady=(8, 0)
        )

        ttk.Checkbutton(
            top,
            text="Auto Scale",
            variable=self.auto_scale_var,
            command=self._save_settings,
        ).grid(row=2, column=3, sticky="w", pady=(8, 0))

        middle = ttk.Frame(root)
        middle.pack(fill=tk.BOTH, expand=True, pady=(10, 0))

        devices_frame = ttk.LabelFrame(middle, text="Discovered Devices")
        devices_frame.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(0, 8))

        self.devices_list = tk.Listbox(devices_frame, height=12)
        self.devices_list.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)
        self.devices_list.bind("<<ListboxSelect>>", self.on_device_select)

        readings = ttk.LabelFrame(middle, text="Live RDT Readings")
        readings.pack(side=tk.RIGHT, fill=tk.BOTH, expand=True)

        grid = ttk.Frame(readings, padding=8)
        grid.pack(fill=tk.BOTH, expand=True)

        for idx, axis in enumerate(["Fx", "Fy", "Fz", "Tx", "Ty", "Tz"]):
            ttk.Label(grid, text=f"{axis}:").grid(row=idx, column=0, sticky="w", pady=3)
            ttk.Label(grid, textvariable=self.ft_vars[axis], width=14).grid(row=idx, column=1, sticky="w", pady=3)

        ttk.Label(grid, text="Status:").grid(row=6, column=0, sticky="w", pady=5)
        ttk.Label(grid, textvariable=self.status_var).grid(row=6, column=1, sticky="w", pady=5)

        ttk.Label(grid, textvariable=self.seq_var).grid(row=7, column=0, columnspan=2, sticky="w", pady=5)

        history_frame = ttk.LabelFrame(root, text="History View")
        history_frame.pack(fill=tk.BOTH, expand=True, pady=(10, 0))
        self.history_frame = history_frame

        axis_controls = ttk.Frame(history_frame)
        axis_controls.pack(fill=tk.X, padx=8, pady=(8, 0))
        ttk.Label(axis_controls, text="Visible Axes:").pack(side=tk.LEFT)
        for axis in AXES:
            ttk.Checkbutton(
                axis_controls,
                text=axis,
                variable=self.axis_visible_vars[axis],
                command=self._save_settings,
            ).pack(side=tk.LEFT, padx=(8, 0))

        self.history_canvas = tk.Canvas(history_frame, bg="white", height=220)
        self.history_canvas.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)

        messages = ttk.LabelFrame(root, text="Messages")
        messages.pack(fill=tk.BOTH, expand=False, pady=(10, 0))

        self.msg_text = tk.Text(messages, height=8, wrap=tk.WORD)
        self.msg_text.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)

        self._toggle_history_view()

    def log(self, text: str) -> None:
        now = time.strftime("%H:%M:%S")
        self.msg_text.insert(tk.END, f"[{now}] {text}\n")
        self.msg_text.see(tk.END)

    def on_refresh_devices(self) -> None:
        self.log("Running discovery broadcast...")

        def worker() -> None:
            try:
                devices = discover_devices()
                self.msg_queue.put(("devices", devices))
            except OSError as exc:
                self.msg_queue.put(("error", f"Discovery failed: {exc}"))

        threading.Thread(target=worker, daemon=True).start()

    def on_device_select(self, _event: object) -> None:
        sel = self.devices_list.curselection()
        if not sel:
            return
        line = self.devices_list.get(sel[0])
        ip = line.split()[0].replace("IP=", "")
        self.ip_var.set(ip)
        if "MAC=" in line:
            mac_part = line.split("MAC=")[1].split()[0]
            self.last_mac_address = mac_part
        self._save_settings()

    def on_connect(self) -> None:
        if self.reader_thread is not None:
            messagebox.showinfo("Already connected", "Disconnect before connecting to another device.")
            return

        ip = self.ip_var.get().strip()
        if not is_valid_ipv4(ip):
            messagebox.showerror("Invalid IP", "Enter a valid IPv4 address.")
            return

        try:
            local_port = int(self.local_port_var.get().strip())
            if local_port < 0 or local_port > 65535:
                raise ValueError
        except ValueError:
            messagebox.showerror("Invalid local port", "Local port must be 0-65535.")
            return

        self.stop_event.clear()
        self._save_settings()

        def reader() -> None:
            self.client = NetFTRdtClient(ip=ip, local_port=local_port, timeout=0.75)
            try:
                self.client.open()
                self.msg_queue.put(("status", f"Connected to {ip}:{RDT_PORT}"))
                while not self.stop_event.is_set():
                    packet = self.client.read_single()
                    self.msg_queue.put(("rdt", packet))
                    time.sleep(0.08)
            except OSError as exc:
                self.msg_queue.put(("error", f"Connection/read error: {exc}"))
            finally:
                if self.client is not None:
                    self.client.close()
                self.client = None
                self.msg_queue.put(("status", "Disconnected"))

        self.reader_thread = threading.Thread(target=reader, daemon=True)
        self.reader_thread.start()

    def on_disconnect(self) -> None:
        self.stop_event.set()
        self.reader_thread = None
        self._save_settings()

    def on_tare(self) -> None:
        if self.client is None:
            messagebox.showinfo("Not connected", "Connect to a Net F/T first.")
            return
        try:
            self.client.tare()
            self.log("Tare command sent.")
        except OSError as exc:
            self.log(f"Failed to tare: {exc}")

    def _safe_float(self, value: str, fallback: float) -> float:
        try:
            parsed = float(value)
            if parsed == 0:
                return fallback
            return parsed
        except ValueError:
            return fallback

    def _update_rdt(self, packet: tuple[int, int, int, int, int, int, int, int, int]) -> None:
        rdt_seq, ft_seq, status, fx, fy, fz, tx, ty, tz = packet
        c_force = self._safe_float(self.counts_force_var.get(), 1_000_000.0)
        c_torque = self._safe_float(self.counts_torque_var.get(), 1_000_000.0)

        scaled = {
            "Fx": fx / c_force,
            "Fy": fy / c_force,
            "Fz": fz / c_force,
            "Tx": tx / c_torque,
            "Ty": ty / c_torque,
            "Tz": tz / c_torque,
        }

        for axis, value in scaled.items():
            self.ft_vars[axis].set(f"{value:.6f}")

        now = time.monotonic()
        for axis, value in scaled.items():
            self.history[axis].append((now, value))

        self._trim_history(now)

        self.seq_var.set(f"RDT seq: {rdt_seq} | FT seq: {ft_seq} | Status: 0x{status:08x}")

    def _history_duration_seconds(self) -> float:
        try:
            duration = float(self.history_duration_var.get())
            if duration <= 0:
                return 10.0
            return min(duration, 600.0)
        except ValueError:
            return 10.0

    def _trim_history(self, now: float | None = None) -> None:
        if now is None:
            now = time.monotonic()
        window = self._history_duration_seconds()
        cutoff = now - window
        for axis in AXES:
            data = self.history[axis]
            while data and data[0][0] < cutoff:
                data.popleft()

    def _toggle_history_view(self) -> None:
        if self.history_enabled_var.get():
            self.history_frame.pack(fill=tk.BOTH, expand=True, pady=(10, 0))
        else:
            self.history_frame.pack_forget()
        self._save_settings()

    def _settings_path(self) -> str:
        return os.path.join(os.path.dirname(__file__), SETTINGS_FILE)

    def _load_settings(self) -> dict[str, object]:
        try:
            with open(self._settings_path(), "r", encoding="utf-8") as handle:
                data = json.load(handle)
                if isinstance(data, dict):
                    return data
        except (OSError, json.JSONDecodeError):
            pass
        return {}

    def _save_settings(self) -> None:
        data = {
            "last_ip": self.ip_var.get().strip(),
            "last_mac": self.last_mac_address,
            "local_port": self.local_port_var.get().strip(),
            "counts_force": self.counts_force_var.get().strip(),
            "counts_torque": self.counts_torque_var.get().strip(),
            "show_history": self.history_enabled_var.get(),
            "history_auto_scale": self.auto_scale_var.get(),
            "history_duration": self.history_duration_var.get().strip(),
            "visible_axes": {axis: var.get() for axis, var in self.axis_visible_vars.items()},
        }
        try:
            with open(self._settings_path(), "w", encoding="utf-8") as handle:
                json.dump(data, handle, indent=2)
        except OSError:
            # Keep GUI responsive even when settings cannot be saved.
            pass

    def _redraw_history_plot(self) -> None:
        if not hasattr(self, "history_canvas"):
            return

        canvas = self.history_canvas
        width = max(canvas.winfo_width(), 200)
        height = max(canvas.winfo_height(), 120)
        canvas.delete("all")

        left_pad = 48
        right_pad = 16
        top_pad = 12
        bottom_pad = 24
        plot_w = max(width - left_pad - right_pad, 40)
        plot_h = max(height - top_pad - bottom_pad, 40)

        canvas.create_rectangle(
            left_pad,
            top_pad,
            left_pad + plot_w,
            top_pad + plot_h,
            outline="#b0bec5",
            width=1,
        )

        now = time.monotonic()
        self._trim_history(now)
        window = self._history_duration_seconds()
        x0 = now - window

        y_values: list[float] = []
        for axis in AXES:
            if self.axis_visible_vars[axis].get():
                y_values.extend(value for _, value in self.history[axis])

        if not y_values:
            canvas.create_text(width / 2, height / 2, text="No data yet", fill="#607d8b")
            self.after(250, self._redraw_history_plot)
            return

        y_min = min(y_values)
        y_max = max(y_values)
        if self.auto_scale_var.get():
            if y_min == y_max:
                span = max(abs(y_min), 1.0)
                y_min -= span * 0.2
                y_max += span * 0.2
        else:
            max_abs = max(abs(y_min), abs(y_max), 1.0)
            y_min, y_max = -max_abs, max_abs

        span_y = max(y_max - y_min, 1e-9)

        canvas.create_text(16, top_pad, text=f"{y_max:.3f}", anchor="w", fill="#455a64")
        canvas.create_text(16, top_pad + plot_h, text=f"{y_min:.3f}", anchor="w", fill="#455a64")
        canvas.create_text(left_pad + plot_w, top_pad + plot_h + 14, text="now", anchor="e", fill="#455a64")
        canvas.create_text(left_pad, top_pad + plot_h + 14, text=f"-{window:.1f}s", anchor="w", fill="#455a64")

        for axis in AXES:
            if not self.axis_visible_vars[axis].get():
                continue
            points = []
            for ts, value in self.history[axis]:
                x = left_pad + ((ts - x0) / window) * plot_w
                y = top_pad + (1.0 - ((value - y_min) / span_y)) * plot_h
                points.extend([x, y])
            if len(points) >= 4:
                canvas.create_line(*points, fill=AXIS_COLORS[axis], width=2)

        legend_x = left_pad + 6
        legend_y = top_pad + 6
        for axis in AXES:
            if not self.axis_visible_vars[axis].get():
                continue
            canvas.create_rectangle(
                legend_x,
                legend_y,
                legend_x + 10,
                legend_y + 10,
                fill=AXIS_COLORS[axis],
                outline="",
            )
            canvas.create_text(legend_x + 14, legend_y + 5, text=axis, anchor="w")
            legend_x += 54

        self.after(250, self._redraw_history_plot)

    def on_close(self) -> None:
        self.stop_event.set()
        self._save_settings()
        self.destroy()

    def _drain_queue(self) -> None:
        while True:
            try:
                kind, payload = self.msg_queue.get_nowait()
            except queue.Empty:
                break

            if kind == "devices":
                devices = payload
                self.devices_list.delete(0, tk.END)
                for dev in devices:
                    line = f"IP={dev.ip:<15} MAC={dev.mac:<17} INFO={dev.app}"
                    self.devices_list.insert(tk.END, line)
                self.log(f"Discovery complete: found {len(devices)} device(s).")
            elif kind == "rdt":
                self._update_rdt(payload)
            elif kind == "status":
                self.status_var.set(payload)
                self.log(str(payload))
                if str(payload) == "Disconnected":
                    self.reader_thread = None
            elif kind == "error":
                self.status_var.set("Error")
                self.log(str(payload))

        self.after(100, self._drain_queue)


def main() -> None:
    app = NetFTGui()
    app.mainloop()


if __name__ == "__main__":
    main()
