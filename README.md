# Oxi Meter

Welcome! Oxi Meter is a secondary Android display for Bluetooth LE pulse oximeters, designed so you can comfortably monitor readings from across the room.

If you recently purchased an Oximeter from [Healthtree](https://www.healthtreeltd.com/support.html) (specifically models M70, M70A, or M70C), you might have noticed their official app hasn't been updated since 2015 and doesn't work on modern phones. I faithfully recreated the app for my own use, and I'm sharing it here in hopes it helps you out too!

## What it does

*   **Auto-connects:** Automatically finds your sensor using its advertised `FFE0` service and connects on its own.
*   **Clear display:** Shows oxygen saturation, pulse, and perfusion index in large numerals that are easy to read across a dark room.
*   **Familiar graphs:** Features a sweeping plethysmograph, drawn exactly like a traditional bedside monitor, and mirrors the sensor's own pulse-amplitude bar.
*   **Audio cues:** Plays a pulse tone on every beat. The pitch gently falls if the saturation drops.
*   **Smart alarms:** Includes configurable alarms modeled after IEC 60601-1-8 standards, plus an alarm history that survives an app restart.
*   **Early warnings:** Provides a signal-quality warning when a reading is becoming unreliable—*before* it completely fails.
*   **Trending:** Shows a 10-minute trend graph for both saturation and pulse.
*   **Presets & Themes:** Includes handy limit presets for adults, children, and infants, along with four display themes (including a dim mode for nighttime).

## What it is not

**Important: This is not a medical device, and nothing here constitutes medical advice.** 

This app simply mirrors a consumer sensor that is already doing the monitoring. It does not replace professional equipment and should not be relied upon to detect or diagnose anything. From the app's perspective, a dead battery, a dropped Bluetooth connection, and a perfectly fine sleeping child all look the same—which is why the "disconnected" alarm is deliberately loud.

*   **Foreground only:** The app only runs while it is on your screen and in the foreground. By design, there is no background service. If you lock your phone, the app stops.
*   **Presets are just starting points:** The alarm limit presets are taken from typical ward settings, not strict medical recommendations. Please adjust them to match the monitor you already trust.

## Hardware Compatibility

This app was built and tested against a device advertising itself as `OXIMETER`, reporting device type `0x01` (WT1), using the HM-10 style serial service `FFE0` / `FFE1`. 

*Note:* The original 2015 vendor app targeted a sibling device that advertises as `BLT_M70C` and uses a different frame format. That specific variant is **not** supported, though both formats are documented in [PROTOCOL.md](PROTOCOL.md).

Because the app matches based on the advertised service UUID rather than the specific device name, other oximeter models might work perfectly! If yours does, a data capture would be incredibly welcome.

## Building the App

For full details, check out [BUILDING.md](BUILDING.md). 

**The short version:** Open the folder in Android Studio, or run `./gradlew assembleDebug`. You will need JDK 21 or newer.

Don't want to build it yourself? Prebuilt APKs are happily provided in the [Releases](../../releases) tab so you don't have to!

## The Protocol

Curious about how it works under the hood? [PROTOCOL.md](PROTOCOL.md) documents the frame format, the checksum, the 7-bit payload constraint, how the perfusion index is packed across two bytes, and the identity of the mysterious second trace. It also lists a few things that are still unknown.

## Tools

I've included two handy Python scripts if you want to tinker without rebuilding the app every time. You'll just need to run `pip install bleak` first.

**`oxi_probe.py`** finds the sensor, connects, decodes frames live, and can log everything to a CSV:
```bash
python tools/oxi_probe.py scan
python tools/oxi_probe.py listen <ADDR> --csv run1.csv
