#!/usr/bin/env python3
"""Best-effort ATI Net F/T discovery helper.

This script avoids privileged operations and probes likely Net F/T addresses using:
- HTTP checks on port 80 for ATI web pages
- UDP RDT probe on port 49152
"""

import argparse
import os
import socket
import struct
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from urllib.request import urlopen


DEFAULT_FACTORY_IP = "192.168.1.1"
COMMON_STATIC_HINTS = [
    "192.168.2.200",  # Common lab static choice for Net F/T
    "192.168.4.1",    # Legacy/static used by older software setups
]


@dataclass
class ProbeResult:
    ip: str
    http_ok: bool
    udp_ok: bool
    note: str


def probe_http(ip: str, timeout: float) -> tuple[bool, str]:
    urls = [
        f"http://{ip}/",
        f"http://{ip}/comm.htm",
    ]
    for url in urls:
        try:
            with urlopen(url, timeout=timeout) as resp:
                body = resp.read(4096).decode("latin-1", errors="ignore")
                hint = "ATI" if "ATI" in body or "Net F/T" in body else "HTTP reachable"
                return True, f"{hint} via {url}"
        except Exception:
            continue
    return False, "HTTP no response"


def probe_udp_rdt(ip: str, timeout: float, local_port: int) -> tuple[bool, str]:
    # RDT command packet: header=0x1234, command=0x0002 (start), sample_count=0
    start_packet = struct.pack("!HHI", 0x1234, 0x0002, 0)
    stop_packet = struct.pack("!HHI", 0x1234, 0x0000, 0)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.settimeout(timeout)
        sock.bind(("0.0.0.0", local_port))
        sock.sendto(start_packet, (ip, 49152))
        data, _ = sock.recvfrom(2048)
        # Expected payload has 9 x uint32/int32 fields => 36 bytes.
        if len(data) >= 36:
            return True, f"RDT reply length={len(data)}"
        return False, f"Unexpected UDP payload length={len(data)}"
    except OSError as exc:
        return False, f"UDP error: {exc}"
    finally:
        try:
            sock.sendto(stop_packet, (ip, 49152))
        except Exception:
            pass
        sock.close()


def probe_ip(ip: str, timeout: float, local_port: int) -> ProbeResult:
    http_ok, http_note = probe_http(ip, timeout)
    udp_ok, udp_note = probe_udp_rdt(ip, timeout, local_port)
    note = f"{http_note}; {udp_note}"
    return ProbeResult(ip=ip, http_ok=http_ok, udp_ok=udp_ok, note=note)


def build_scan_candidates(prefix: str, start: int, end: int) -> list[str]:
    return [f"{prefix}.{i}" for i in range(start, end + 1)]


def main() -> int:
    parser = argparse.ArgumentParser(description="Discover ATI Net F/T endpoint")
    parser.add_argument("--ip", action="append", default=[], help="specific IP to probe (repeatable)")
    parser.add_argument(
        "--static-ip",
        action="append",
        default=[],
        help="Net F/T static IP(s) to probe (repeatable), e.g. --static-ip 192.168.2.200",
    )
    parser.add_argument(
        "--include-common-static-hints",
        action="store_true",
        help="also probe common static Net F/T addresses (192.168.2.200, 192.168.4.1)",
    )
    parser.add_argument("--scan-prefix", default="", help="optional /24 prefix, e.g. 192.168.0")
    parser.add_argument("--scan-range", default="1-254", help="host range for --scan-prefix, default 1-254")
    parser.add_argument("--timeout", type=float, default=0.35, help="probe timeout in seconds")
    parser.add_argument("--workers", type=int, default=32, help="scan worker threads")
    parser.add_argument("--local-port", type=int, default=49152, help="local UDP port for RDT probe")
    args = parser.parse_args()

    env_static = os.getenv("ATI_NETFT_STATIC_IP", "").strip()
    env_static_ips = [ip.strip() for ip in env_static.split(",") if ip.strip()]

    candidates = [DEFAULT_FACTORY_IP]
    candidates.extend(args.static_ip)
    candidates.extend(env_static_ips)
    candidates.extend(args.ip)
    if args.include_common_static_hints:
        candidates.extend(COMMON_STATIC_HINTS)
    candidates = list(dict.fromkeys(candidates))

    if args.scan_prefix:
        try:
            start_str, end_str = args.scan_range.split("-", 1)
            start = int(start_str)
            end = int(end_str)
            if start < 1 or end > 254 or start > end:
                raise ValueError("invalid range")
            candidates.extend(build_scan_candidates(args.scan_prefix, start, end))
        except ValueError as exc:
            print(f"Invalid --scan-range: {exc}")
            return 2

    candidates = list(dict.fromkeys(candidates))

    print(f"Probing {len(candidates)} candidate IPs...")
    results: list[ProbeResult] = []

    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futs = [pool.submit(probe_ip, ip, args.timeout, args.local_port) for ip in candidates]
        for fut in as_completed(futs):
            results.append(fut.result())

    hits = [r for r in results if r.http_ok or r.udp_ok]
    hits.sort(key=lambda r: (r.udp_ok, r.http_ok), reverse=True)

    if not hits:
        print("No likely Net F/T endpoint found.")
        print("Tips:")
        print("- Put DIP switch 9 ON for default-IP mode and power-cycle the Net F/T box")
        print("- Probe your static IP too: --static-ip <your_netft_static_ip>")
        print("- Put your NIC on the matching subnet and retry")
        print("- If using DHCP mode, run with --scan-prefix on your active LAN")
        return 1

    print("Likely Net F/T endpoints:")
    for r in hits:
        print(f"- {r.ip}: HTTP={r.http_ok} UDP={r.udp_ok} ({r.note})")

    best = hits[0]
    print("\nRecommended connection settings:")
    print(f"ATI_NETFT_IP={best.ip}")
    print("ATI_NETFT_PORT=49152")
    print("ATI_LOCAL_PORT=49152")
    return 0


if __name__ == "__main__":
    sys.exit(main())
