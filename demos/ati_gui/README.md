# ATI Tkinter GUI Demo

This folder contains a Python/Tkinter translation of ATI's Java Net F/T demo workflow.

## Features

- Device discovery broadcast (RTA discovery protocol)
- Select discovered device IP and connect
- Live low-speed RDT streaming (Fx, Fy, Fz, Tx, Ty, Tz)
- Status and sequence display (RDT seq, FT seq, status)
- Tare command support
- Adjustable counts-per-force and counts-per-torque scaling

## Run

```bash
python3 demos/ati_gui/netft_gui.py
```

## Notes

- Make sure the Net F/T `RDT interface` is enabled in `comm.htm`.
- If the Net F/T uses static IP (for example `192.168.2.200`), your NIC must have a matching subnet address.
- The display scaling defaults to `1e6` counts/unit to match this repository's C++ scaling behavior.
