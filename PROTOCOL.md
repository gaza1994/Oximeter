# Oximeter BLE protocol

Confirmed against a real device (advertising as `OXIMETER`,
`DC:04:5A:25:CF:74`) over two captures totalling ~180 seconds. Checksum
verified on every frame.

This is **not** the protocol in the 2015 `com.blt.oximeter` APK. That app used
0xAA framing with byte stuffing and a different field layout. Everything below
was recovered from live captures instead.

## Link

| Item | UUID |
|---|---|
| Service | `0000ffe0-0000-1000-8000-00805f9b34fb` |
| Characteristic (notify) | `0000ffe1-0000-1000-8000-00805f9b34fb` |
| Characteristic (write) | `0000ffe2-0000-1000-8000-00805f9b34fb` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

Subscribe to FFE1 and the device streams unprompted. Nothing is ever written to
it. FFE2 accepts writes but nothing is known about what it expects.

Standard GATT services are also present: Generic Access (1800), Generic
Attribute (1801) and Device Information (180A).

## Discovery

The device advertises the FFE0 service UUID, which is the only reliable way to
find it - the name varies between units. This one calls itself `OXIMETER`; the
2015 app expected `BLT_M70C`.

Manufacturer data: company ID `0x0012`, payload `01 30 30 30 30 30 30 31 00`.
The leading `0x01` is the device type (matching byte 2 of the data frames) and
the rest is an ASCII serial number, here `0000001`.

## Frames

Fixed 69 bytes, no byte stuffing. Two frames per second.

```
[0]      0xFF     sync
[1]      0x44     length of everything after this byte (68)
[2]      type     0x01 = WT1, 0x30 = M70C
[3]      0x00     constant
[4]      SpO2 %                          127 = invalid
[5]      pulse, bpm                      127 = invalid
[6]      perfusion index, low 7 bits
[7]      perfusion index, high 6 bits
[8..37]  30 samples, range 0-31, purpose not established
[38..67] 30 plethysmograph samples, range 0-127
[68]     checksum
```

**Checksum:** `sum(bytes 1..67) & 0xFF`. Verified on 103/103 frames.

**No payload byte exceeds 0x7F.** Zero violations across every frame captured.
That is what makes 0xFF safe as a sync marker, and it is why the perfusion index
needs two bytes for a value that would otherwise fit in one. The checksum byte
is the only one that can exceed 0x7F, so a reader should validate the checksum
rather than trusting the sync byte alone.

**Perfusion index** is a 13-bit value in hundredths of a percent:

```
pi = (((b7 & 0x3F) << 7) | (b6 & 0x7F)) / 100.0      0x1FFF = invalid
```

Confirmed: the device displayed 5.99 while this decoded to 5.99.

**Invalid values** are all-ones within each field's width. With the probe off,
a frame reads SpO2 127, pulse 127, PI 0x1FFF, and both traces go to zero.

## Rates

Two frames a second, 30 samples per trace per frame, so both traces run at
60 Hz. Notifications arrive in 20/20/20/9-byte chunks, four per frame.

## Bytes 8..37 - the pulse amplitude bar

Confirmed by capture and by the device's own display, which shows a bar that
jumps with each beat.

This is the same pulse waveform as bytes 38..67, but DC-stripped, gained and
clipped into 5 bits. Measured against the pleth over the same window:

| Condition | correlation with pleth | mean amplitude |
|---|---|---|
| probe seated, hand still | 0.907 | 21.3 |
| finger moving | 0.578 | 18.8 |
| probe deliberately loose | 0.454 | 10.3 |

It sits at zero in roughly 70% of frames and clips at 31, which is what a
32-segment bar display looks like as data.

The two failure modes are distinguishable, which makes this usable as a signal
quality measure: poor coupling collapses the amplitude, while movement artefact
leaves the amplitude intact but destroys the agreement between the two traces.
Amplitude alone would miss movement; correlation alone would miss a loose probe.

Still open: whether the bar is driven by perfusion or purely by coupling. A
capture at low perfusion with good coupling - a cold hand, or one held above
the head - would separate those. It does not affect how the app uses it.

## Not established

(Bytes 8..37 are now identified - see below.)
- What FFE2 accepts.
- Whether byte 3 is ever non-zero.
- There is no battery or firmware-version frame in this protocol. The 2015
  app's 0x43 status frame does not exist here.
