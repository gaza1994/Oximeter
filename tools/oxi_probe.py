#!/usr/bin/env python3
"""
Oximeter probe - for a device advertising as OXIMETER with service FFE0.

    pip install bleak

    python oxi_probe.py scan                 # find it
    python oxi_probe.py listen <ADDR>        # live decode
    python oxi_probe.py listen <ADDR> --raw  # also dump raw notification hex
    python oxi_probe.py listen <ADDR> --csv run1.csv

Protocol, recovered from a 51-second capture (103 frames, checksum verified on
every one). Fixed 69-byte frames, no byte stuffing:

    [0]     0xFF    sync
    [1]     0x44    length of everything after this byte (68)
    [2]     0x01    device type (0x01 = WT1, matches the advertisement)
    [3]     0x00    unknown, constant
    [4]     SpO2 %                       127 = invalid
    [5]     pulse, bpm                   127 = invalid
    [6]     perfusion index, low 7 bits
    [7]     perfusion index, high 6 bits
    [8:38]  30 samples, range 0-31   - secondary trace, purpose unknown
    [38:68] 30 samples, range 0-127  - plethysmograph
    [68]    checksum: sum(bytes 1..67) & 0xFF

Perfusion index is a 13-bit value in hundredths of a percent, split across two
bytes because no payload byte may exceed 0x7F (that is what keeps 0xFF unique
as a sync marker):

    pi = (((b7 & 0x3F) << 7) | (b6 & 0x7F)) / 100      0x1FFF = invalid

Confirmed against the device display at 5.99. Two frames per second, so both
traces run at 60 Hz.
"""

import asyncio
import sys
import time
from datetime import datetime

try:
    from bleak import BleakScanner, BleakClient
except ImportError:
    sys.exit("bleak is not installed. Run:  pip install bleak")

SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

SYNC = 0xFF
FRAME_LEN = 69          # including the sync byte
PAYLOAD_LEN = 0x44      # value of byte 1


# --------------------------------------------------------------------------
# scan
# --------------------------------------------------------------------------

async def do_scan(seconds):
    seen = {}

    def on_found(device, adv):
        has_ffe0 = any(u.lower() == SERVICE_UUID for u in (adv.service_uuids or []))
        if not has_ffe0 or device.address in seen:
            return
        seen[device.address] = True
        name = adv.local_name or device.name or "(no name)"
        mfr = "  ".join(f"mfr[0x{c:04X}]={d.hex()}"
                        for c, d in (adv.manufacturer_data or {}).items())
        print(f"[{datetime.now():%H:%M:%S}] {name}  {device.address}  rssi={adv.rssi}  {mfr}")

    print(f"Scanning {seconds}s for devices with the FFE0 service.\n")
    scanner = BleakScanner(detection_callback=on_found)
    await scanner.start()
    await asyncio.sleep(seconds)
    await scanner.stop()
    if not seen:
        print("Nothing with FFE0 found.")
    else:
        print(f"\n{len(seen)} candidate(s). Use:  python oxi_probe.py listen <ADDR>")


# --------------------------------------------------------------------------
# framing
# --------------------------------------------------------------------------

class Frames:
    """
    Resynchronising reader. Looks for 0xFF followed by the expected length byte,
    then validates the checksum before emitting - so joining mid-stream or
    losing a notification recovers on the next frame rather than corrupting.
    """

    def __init__(self, on_frame):
        self.on_frame = on_frame
        self.buf = bytearray()
        self.bad_checksums = 0
        self.resyncs = 0

    def feed(self, chunk):
        self.buf.extend(chunk)
        while True:
            start = self.buf.find(bytes([SYNC, PAYLOAD_LEN]))
            if start < 0:
                if len(self.buf) > 4 * FRAME_LEN:
                    del self.buf[:-FRAME_LEN]
                return
            if start > 0:
                self.resyncs += 1
                del self.buf[:start]
            if len(self.buf) < FRAME_LEN:
                return
            frame = bytes(self.buf[:FRAME_LEN])
            if (sum(frame[1:68]) & 0xFF) == frame[68]:
                del self.buf[:FRAME_LEN]
                self.on_frame(frame)
            else:
                self.bad_checksums += 1
                del self.buf[:2]        # false sync, step past it and retry


def decode(frame):
    pi_raw = ((frame[7] & 0x3F) << 7) | (frame[6] & 0x7F)
    return {
        "device_type": frame[2],
        "spo2": None if frame[4] == 127 else frame[4],
        "pulse": None if frame[5] == 127 else frame[5],
        "pi": None if pi_raw == 0x1FFF else pi_raw / 100.0,
        "pi_raw": pi_raw,
        "trace_a": list(frame[8:38]),
        "pleth": list(frame[38:68]),
    }


BARS = " .:-=+*#%@"


def sparkline(samples, lo, hi):
    span = max(hi - lo, 1)
    return "".join(
        BARS[min(int((v - lo) / span * (len(BARS) - 1)), len(BARS) - 1)]
        for v in samples
    )


# --------------------------------------------------------------------------
# listen
# --------------------------------------------------------------------------

async def do_listen(address, raw, csv_path):
    # Timed from the first frame, not from connect: counting the connection and
    # subscription handshake dilutes the rate and makes 60 Hz read as 59.
    state = {"frames": 0, "notifications": 0, "first": None, "last": None,
             "gaps": []}
    csv = open(csv_path, "w", encoding="utf-8") if csv_path else None
    if csv:
        csv.write("time,spo2,pulse,pi,pi_raw," +
                  ",".join(f"a{i}" for i in range(30)) + "," +
                  ",".join(f"p{i}" for i in range(30)) + "\n")

    def on_frame(frame):
        now = time.time()
        if state["first"] is None:
            state["first"] = now
        else:
            state["gaps"].append(now - state["last"])
        state["last"] = now
        state["frames"] += 1
        d = decode(frame)
        pleth = d["pleth"]
        spo2 = f"{d['spo2']:3d}" if d["spo2"] is not None else " --"
        pulse = f"{d['pulse']:3d}" if d["pulse"] is not None else " --"
        pi = f"{d['pi']:5.2f}" if d["pi"] is not None else "  -.--"
        print(
            f"SpO2 {spo2}%   pulse {pulse}   PI {pi}   "
            f"|{sparkline(pleth, min(pleth), max(pleth))}|",
            flush=True,
        )
        if csv:
            csv.write(
                f"{time.time():.3f},"
                f"{'' if d['spo2'] is None else d['spo2']},"
                f"{'' if d['pulse'] is None else d['pulse']},"
                f"{'' if d['pi'] is None else d['pi']},"
                f"{d['pi_raw']},"
                + ",".join(map(str, d["trace_a"])) + ","
                + ",".join(map(str, pleth)) + "\n"
            )

    frames = Frames(on_frame)

    def on_notify(_, data):
        state["notifications"] += 1
        if raw:
            print(f"raw {data.hex()}", flush=True)
        frames.feed(data)

    print(f"Connecting to {address} ...")
    async with BleakClient(address) as client:
        target = None
        for service in client.services:
            for ch in service.characteristics:
                if ch.uuid.lower() == CHAR_UUID:
                    target = ch
        if target is None:
            print("No FFE1 characteristic on this device.")
            return

        print("Connected. Probe on a finger. Ctrl-C to stop.\n")
        await client.start_notify(target, on_notify)
        try:
            while True:
                await asyncio.sleep(10)
                gaps = state["gaps"]
                if len(gaps) < 2:
                    print("-- waiting for frames", flush=True)
                    continue
                span = state["last"] - state["first"]
                rate = (state["frames"] - 1) / span
                ordered = sorted(gaps)
                median_gap = ordered[len(ordered) // 2]
                print(
                    f"-- {state['frames']} frames, {rate:.3f}/s "
                    f"({rate * 30:.1f} Hz trace), "
                    f"median gap {median_gap * 1000:.0f} ms, "
                    f"{frames.bad_checksums} bad checksums, "
                    f"{frames.resyncs} resyncs",
                    flush=True,
                )
        except asyncio.CancelledError:
            pass
        finally:
            await client.stop_notify(target)
            if csv:
                csv.close()
                print(f"\nWrote {csv_path}")


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return
    if args[0] == "scan":
        asyncio.run(do_scan(int(args[1]) if len(args) > 1 else 20))
    elif args[0] == "listen" and len(args) > 1:
        csv_path = args[args.index("--csv") + 1] if "--csv" in args else None
        asyncio.run(do_listen(args[1], "--raw" in args, csv_path))
    else:
        print(__doc__)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\nStopped.")
