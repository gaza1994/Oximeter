#!/usr/bin/env python3
"""
Work out what bytes 8-37 (trace A) actually are, from captures alone.

    python analyse_trace.py run1.csv
    python analyse_trace.py baseline.csv cold.csv loose.csv moving.csv

For each capture it reports how trace A relates to the plethysmograph and to
the perfusion index, and prints both traces so you can eyeball the shape.

The experiment to run, one capture each, 60 seconds, named so you can tell them
apart afterwards:

  baseline.csv  probe on, hand still and warm, normal reading
  cold.csv      same finger after holding something cold, or hand raised above
                the head for a minute - drops perfusion without losing signal
  loose.csv     probe deliberately half on, so the reading is unreliable but
                not absent
  moving.csv    wiggle the finger continuously
  off.csv       probe off entirely

Then:
  - if A tracks perfusion index and collapses in cold.csv, it is a pulse
    amplitude bar
  - if A goes erratic in moving.csv and loose.csv but survives cold.csv, it is
    a signal quality indicator
  - if A holds its shape in every case and simply scales, it is the second
    wavelength of the sensor
"""

import csv
import math
import os
import sys


def load(path):
    rows = []
    with open(path, encoding="utf-8") as fh:
        for r in csv.DictReader(fh):
            valid = r["spo2"] not in ("", "127")
            rows.append({
                "valid": valid,
                "spo2": int(r["spo2"]) if valid else None,
                "pulse": int(r["pulse"]) if r["pulse"] not in ("", "127") else None,
                "pi": float(r["pi"]) if r["pi"] not in ("",) else None,
                "a": [int(r[f"a{i}"]) for i in range(30)],
                "p": [int(r[f"p{i}"]) for i in range(30)],
            })
    return rows


def corr(x, y):
    n = len(x)
    if n < 3:
        return 0.0
    mx, my = sum(x) / n, sum(y) / n
    sx = math.sqrt(sum((v - mx) ** 2 for v in x)) or 1e-9
    sy = math.sqrt(sum((v - my) ** 2 for v in y)) or 1e-9
    return sum((a - mx) * (b - my) for a, b in zip(x, y)) / (sx * sy)


BARS = " .:-=+*#%@"


def spark(samples, lo=None, hi=None):
    lo = min(samples) if lo is None else lo
    hi = max(samples) if hi is None else hi
    span = max(hi - lo, 1)
    return "".join(BARS[min(int((v - lo) / span * 9), 9)] for v in samples)


def report(path):
    if not os.path.exists(path):
        print(f"\n=== {path} ===")
        print("  Not found. Capture it first, then run this again:")
        print(f"    python oxi_probe.py listen <ADDR> --csv {path}")
        print("  Let it run about 60 seconds, then Ctrl-C.")
        return
    rows = load(path)
    good = [r for r in rows if r["valid"]]
    print(f"\n=== {path} ===")
    print(f"frames {len(rows)}, valid {len(good)}, "
          f"{len(rows) - len(good)} with the probe off")
    if not good:
        return

    A = [v for r in good for v in r["a"]]
    P = [v for r in good for v in r["p"]]

    best = max(range(-15, 16),
               key=lambda lag: abs(corr(A[lag:] if lag >= 0 else A[:len(A) + lag],
                                        P[:len(P) - lag] if lag >= 0 else P[-lag:])))
    bl = corr(A[best:] if best >= 0 else A[:len(A) + best],
              P[:len(P) - best] if best >= 0 else P[-best:])
    print(f"A vs pleth: best r={bl:+.3f} at lag {best:+d} samples")

    zero_floor = sum(1 for r in good if min(r["a"]) == 0) / len(good)
    print(f"A touches zero in {zero_floor * 100:.0f}% of frames "
          f"(a floored signal is a bar, not a waveform)")

    amps_a = [max(r["a"]) - min(r["a"]) for r in good]
    amps_p = [max(r["p"]) - min(r["p"]) for r in good]
    pis = [r["pi"] for r in good if r["pi"] is not None]
    print(f"A amplitude  mean {sum(amps_a) / len(amps_a):5.1f}  "
          f"range {min(amps_a)}-{max(amps_a)}")
    print(f"P amplitude  mean {sum(amps_p) / len(amps_p):5.1f}  "
          f"range {min(amps_p)}-{max(amps_p)}")
    if pis:
        print(f"perfusion    mean {sum(pis) / len(pis):5.2f}  "
              f"range {min(pis):.2f}-{max(pis):.2f}")
        if len(pis) == len(amps_a):
            print(f"A amplitude vs perfusion index: r={corr(amps_a, pis):+.3f}"
                  "   (high means A is an amplitude bar)")
        print(f"A peak vs perfusion index: "
              f"r={corr([max(r['a']) for r in good if r['pi'] is not None], pis):+.3f}")

    mid = len(good) // 2
    window = good[mid:mid + 4]
    print("\nfour consecutive frames, A over pleth:")
    for r in window:
        print(f"  A |{spark(r['a'], 0, 31)}|")
        print(f"  P |{spark(r['p'])}|")
        print()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    for path in sys.argv[1:]:
        try:
            report(path)
        except Exception as e:
            print(f"\n=== {path} ===")
            print(f"  Could not read this file: {e}")
            print("  It should be a CSV written by oxi_probe.py --csv")


if __name__ == "__main__":
    main()
