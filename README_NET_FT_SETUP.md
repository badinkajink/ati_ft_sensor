# ATI Mini45 + Net F/T Setup Log (2026-04-13)

This document records the network and sensor bring-up work performed on this PC, plus the exact commands to complete setup and read force/torque data.

## Hardware Topology

- ATI Mini45 sensor -> ATI Net F/T box
- Net F/T box -> PoE switch
- PoE switch -> same Ethernet network as this PC

## What The Driver Expects

From this repository's source:

- UDP RDT port is `49152`
- Sensor IP is now configurable from C++ and Python API
- Default API value is `192.168.1.1` (factory default-IP mode from ATI Quick Start)

Code location:

- `src/AtiFTSensor.cpp`: `initialize(sensor_ip, sensor_port, local_port)`
- `srcpy/ati_ft_sensor_cpp.cpp`: Python binding with `initialize(sensor_ip=..., sensor_port=49152, local_port=49152)`
- `demos/ati.py`: reads `ATI_NETFT_IP`, `ATI_NETFT_PORT`, `ATI_LOCAL_PORT`

## Important Factory-Default Detail

From ATI Quick Start (`doc/9610-05-1022 Quick Start.pdf`):

- For first-time setup, set DIP switch 9 to ON and power cycle.
- In that mode, the Net F/T web UI should be at `http://192.168.1.1`.
- ATI instructs temporary PC NIC address `192.168.1.100` for direct setup.
- If demo has IO exceptions while LEDs are green, ATI says to check `http://192.168.1.1/comm.htm` and enable RDT.

This explains why probing only `192.168.4.1` failed.

## PC Network State Observed

- Wired NIC: `enp130s0` on `192.168.0.10/24`
- Wi-Fi NIC: `wlp129s0f0` on `192.168.50.53/24`
- Active wired NetworkManager profile: `netplan-enp130s0`

Key checks performed:

```bash
ip -brief addr
ip route
nmcli -t -f DEVICE,TYPE,STATE,CONNECTION device status
nmcli connection show --active
```

## Discovery / Reachability Attempts Performed

### 1) Direct ping to default Net F/T IP

```bash
ping -c 2 -W 1 192.168.4.1
```

Result: `100% packet loss`

### 2) ARP neighbor check

```bash
ip neigh show dev enp130s0 | rg '192.168.4.1|FAILED|REACHABLE|STALE'
```

Result: `192.168.4.1 FAILED`

### 3) Add temporary ATI subnet address

```bash
ip addr add 192.168.4.2/24 dev enp130s0
```

Result: `RTNETLINK answers: Operation not permitted`

Conclusion: this shell/session does not have privileges to modify NIC addressing, so discovery/streaming could not be completed here.

### 4) Retest after privileged NIC update to default subnet

User applied:

```bash
sudo ip addr add 192.168.1.100/24 dev enp130s0
ping -I enp130s0 -c 3 -W 1 192.168.1.1
ip neigh show dev enp130s0 | rg 192.168.1.1
```

Observed:

- `enp130s0` now has `192.168.1.100/24`
- Ping to `192.168.1.1` still times out
- ARP for `192.168.1.1` is `FAILED`

Additional check:

```bash
nmap -sn 192.168.1.0/24
```

Observed only this host (`192.168.1.100`) is up.

Interpretation: host-side IP routing is correct, but Net F/T is not responding on layer 2 in current hardware/switch/DIP state.

## Commands To Complete Setup On Your PC (with privileges)

You can choose temporary setup (quick test) or persistent setup (NetworkManager).

### Option A: Temporary (fastest, factory-default mode)

```bash
sudo ip addr add 192.168.1.100/24 dev enp130s0
ping -I enp130s0 -c 3 -W 1 192.168.1.1
ip neigh show dev enp130s0 | rg 192.168.1.1
```

If successful, ARP should show a MAC for `192.168.1.1` (not `FAILED`).

Then open:

```bash
xdg-open http://192.168.1.1
```

To remove temporary address later:

```bash
sudo ip addr del 192.168.1.100/24 dev enp130s0
```

### Option B: Persistent via NetworkManager

```bash
sudo nmcli connection modify netplan-enp130s0 \
	+ipv4.addresses 192.168.1.100/24 \
	ipv4.method manual

sudo nmcli connection up netplan-enp130s0
```

Then verify:

```bash
ip -brief addr show dev enp130s0
ping -I enp130s0 -c 3 -W 1 192.168.1.1
```

Note: if this wired NIC is also used for office LAN/internet, keep Wi-Fi up as default route while testing the sensor subnet.

## Recommended "Golden" Configuration (Reliable On Any PC)

Use this profile unless your lab IT requires DHCP:

### Net F/T box settings (web UI)

1. Communications/network page (`comm.htm`):
	- Addressing mode: `Static`
	- IP address: `192.168.1.1`
	- Subnet mask: `255.255.255.0`
	- Gateway: `0.0.0.0` (or leave blank if allowed)
2. RDT interface: `Enabled`
3. Save/apply and power-cycle the box.
4. DIP switch 9:
	- During first setup/recovery: `ON` (default-IP mode)
	- For normal operation with stored comm settings: `OFF`

### Any computer settings

On the NIC connected to the Net F/T path, add one secondary address on the same subnet:

```bash
sudo ip addr add 192.168.1.100/24 dev <your_nic>
```

Use any free host ID in `192.168.1.x` except `.1`.

Verification:

```bash
ping -I <your_nic> -c 3 -W 1 192.168.1.1
ip neigh show dev <your_nic> | rg 192.168.1.1
```

If you want persistence on Linux/NetworkManager, create a dedicated connection profile for the sensor-facing NIC with `192.168.1.x/24` as secondary IPv4 address.

## Reading Sensor Data

After the Net F/T IP is reachable (typically `192.168.1.1` in default mode), run one of the repository demos/tests.

### Python demo

```bash
ATI_NETFT_IP=192.168.1.1 python3 demos/ati.py
```

Expected behavior: continuous 6D readings are printed.

### Discovery helper (new)

Probe common defaults first (`192.168.1.1`, `192.168.4.1`):

```bash
python3 demos/discover_netft.py
```

Probe factory default and a known static IP (example `192.168.2.200`):

```bash
python3 demos/discover_netft.py --static-ip 192.168.2.200
```

You can also set static targets by environment variable:

```bash
ATI_NETFT_STATIC_IP=192.168.2.200 python3 demos/discover_netft.py
```

If Net F/T is on DHCP/current LAN, scan a subnet:

```bash
python3 demos/discover_netft.py --scan-prefix 192.168.0 --scan-range 1-254
```

Use discovered IP with demo:

```bash
ATI_NETFT_IP=<discovered_ip> python3 demos/ati.py
```

### Tkinter GUI demo (translated from ATI Java demo)

```bash
python3 demos/ati_gui/netft_gui.py
```

Includes discovery, connect/disconnect, tare, and live RDT display.

### C++ test executable

If your build exports this target/binary, run:

```bash
ati_ft_sensor_test_sensor
```

or (ROS2 workspace install):

```bash
ros2 run ati_ft_sensor ati_ft_sensor_test_sensor
```

Expected output style:

```text
rdt_seq=..., ft_seq=..., status=0
Fx Fy Fz Tx Ty Tz
```

## Calibration / Units Troubleshooting (Mini45 and Mini40)

If one sensor looks reasonable and the other looks off by a large factor, this is usually calibration/profile mismatch, not a bad sensor.

Check these items in Net F/T Configuration page:

1. Calibration Select must match the attached transducer serial number.
2. Output mode should be Force/Torque (not raw gage) for normal operation.
3. Force units and torque units must match your software expectation.
4. If using force/torque units directly, ATI quick-start indicates:
	- Counts per Force = `1`
	- Counts per Torque = `1`

Important code note for this repository:

- `src/AtiFTSensor.cpp` currently divides raw values by `1e6` (`count_per_force_` and `count_per_torque_`).
- If your Net F/T is configured with Counts-per-unit = `1`, this software scaling will make values appear `1e6` too small.
- If your Net F/T is configured with Counts-per-unit = `1e6`, this software scaling may look correct.

So for consistent behavior across Mini40/Mini45, ensure Net F/T counts-per-unit and software scaling agree.

Practical recommendation:

1. Keep Net F/T units/counts consistent across both sensors.
2. Perform a quick known-load sanity check (e.g., hang a known mass, verify $F_z \approx m g$ in chosen units).
3. If scale is off by a near-constant factor across axes, correct counts-per-unit or software scale.
4. If only some axes are wrong/sign-flipped, verify calibration profile and tool transform settings.

## Extra Debug Checks (if still no data)

1. Confirm link LEDs are active on Net F/T box and PoE switch port.
2. Confirm no second process is already binding UDP port 49152.
3. Confirm sensor/box IP mode matches DIP switch state and network setup.
4. Confirm DIP switch 9 position:
	- ON: default-IP mode (`192.168.1.1`)
	- OFF: user-configured static/DHCP mode
   - For first bring-up, prefer only DIP 9 ON and all other DIP switches OFF unless your ATI manual explicitly requires otherwise.
5. Confirm RDT is enabled in `comm.htm` page.
6. Temporarily disable other wired VLAN/profiles on `enp130s0` and retest.
7. Keep only one active path to the box during initial bring-up to avoid L2 ambiguity.

## Build Attempt In This Session

A build was attempted using VS Code CMake Tools (`Build_CMakeTools`) but project configuration failed in this environment, so no live binary execution was completed in-session.
