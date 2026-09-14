# Oxi Meter

An Android second display for a Bluetooth LE pulse oximeter, for watching a
reading from another room.

The oximeter came with a QR code pointing at an APK built in 2015, which no
modern Android version will install. This is a replacement, written from
scratch, with the wire protocol recovered by decompiling that APK and then
corrected against live captures from the device — the two turned out to
disagree considerably.

Written for one specific device and one specific reason: a sensor clipped to a
sleeping child, and a phone on the other side of the house.

## What it does

- Finds the sensor by its advertised `FFE0` service and connects on its own
- Saturation, pulse and perfusion index, in large numerals meant to be read
  across a dark room
- Sweeping plethysmograph, drawn the way a bedside monitor draws one
- The sensor's own pulse-amplitude bar, mirrored
- A pulse tone on every beat, with the pitch falling as saturation falls
- Alarms on configurable limits, shaped after IEC 60601-1-8
- Signal-quality warning when a reading is becoming unreliable, before it fails
- Ten-minute trend for saturation and pulse
- Alarm history that survives a restart
- Adult, child and infant limit presets
- Four display themes, including a dim one for night

## What it is not

**This is not a medical device and nothing here is medical advice.** It mirrors
a consumer sensor that is already doing the monitoring; it does not replace it,
and it should not be relied on to detect anything. A flat battery, a dropped
Bluetooth link and a sleeping child are indistinguishable from the app's point
of view, which is why the disconnected state is deliberately loud.

It runs only while it is on screen and in the foreground. There is no
background service, by design — lock the phone and it stops.

The alarm limit presets are starting points taken from typical ward settings,
not recommendations. Set them to match whatever monitor you already trust.

## Hardware

Built against a device advertising as `OXIMETER`, reporting device type `0x01`
(WT1), with the HM-10 style serial service `FFE0` / `FFE1`. The 2015 vendor app
targeted a sibling that advertises as `BLT_M70C` and speaks a different frame
format; that variant is **not** supported, though [PROTOCOL.md](PROTOCOL.md)
documents both.

Matching is on the advertised service UUID rather than the device name, so
other units under other names may well work. If yours does, a capture would be
welcome.

## Building

See [BUILDING.md](BUILDING.md). Short version: open the folder in Android
Studio, or `./gradlew assembleDebug`. Needs JDK 21 or newer.

Prebuilt APKs are published under
[Releases](../../releases) rather than committed to the repository.

## The protocol

[PROTOCOL.md](PROTOCOL.md) documents the frame format, the checksum, the 7-bit
payload constraint, how the perfusion index is packed across two bytes, and
what the second trace turned out to be. It also lists what is still unknown.

## Tools

Two Python scripts, useful for working on this without rebuilding the app
each time. Both need `pip install bleak`.

`oxi_probe.py` finds the sensor, connects, decodes frames live and can log to
CSV:

```
python tools/oxi_probe.py scan
python tools/oxi_probe.py listen <ADDR> --csv run1.csv
```

`analyse_trace.py` takes those CSVs and reports how the two traces relate, which
is how the second one was identified:

```
python tools/analyse_trace.py baseline.csv loose.csv moving.csv
```

## Licence

[MIT](LICENSE).

No vendor code is included or redistributed here. The protocol was determined
by observing a device I own, for the purpose of interoperating with it.
