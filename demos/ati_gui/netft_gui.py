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
import queue
import socket
import struct
import threading
import time
import tkinter as tk
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
        self.geometry("900x560")

        self.msg_queue: queue.Queue[tuple[str, object]] = queue.Queue()
        self.stop_event = threading.Event()
        self.reader_thread: threading.Thread | None = None
        self.client: NetFTRdtClient | None = None

        self.ip_var = tk.StringVar(value="192.168.1.1")
        self.local_port_var = tk.StringVar(value="49152")
        self.counts_force_var = tk.StringVar(value="1000000")
        self.counts_torque_var = tk.StringVar(value="1000000")
        self.status_var = tk.StringVar(value="Disconnected")
        self.seq_var = tk.StringVar(value="RDT seq: - | FT seq: - | Status: -")

        self.ft_vars = {
            "Fx": tk.StringVar(value="0.000"),
            "Fy": tk.StringVar(value="0.000"),
            "Fz": tk.StringVar(value="0.000"),
            "Tx": tk.StringVar(value="0.000"),
            "Ty": tk.StringVar(value="0.000"),
            "Tz": tk.StringVar(value="0.000"),
        }

        self._build_ui()
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

        messages = ttk.LabelFrame(root, text="Messages")
        messages.pack(fill=tk.BOTH, expand=False, pady=(10, 0))

        self.msg_text = tk.Text(messages, height=8, wrap=tk.WORD)
        self.msg_text.pack(fill=tk.BOTH, expand=True, padx=8, pady=8)

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

        self.ft_vars["Fx"].set(f"{fx / c_force:.6f}")
        self.ft_vars["Fy"].set(f"{fy / c_force:.6f}")
        self.ft_vars["Fz"].set(f"{fz / c_force:.6f}")
        self.ft_vars["Tx"].set(f"{tx / c_torque:.6f}")
        self.ft_vars["Ty"].set(f"{ty / c_torque:.6f}")
        self.ft_vars["Tz"].set(f"{tz / c_torque:.6f}")

        self.seq_var.set(f"RDT seq: {rdt_seq} | FT seq: {ft_seq} | Status: 0x{status:08x}")

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
            elif kind == "error":
                self.status_var.set("Error")
                self.log(str(payload))

        self.after(100, self._drain_queue)


def main() -> None:
    app = NetFTGui()
    app.mainloop()


if __name__ == "__main__":
    main()
