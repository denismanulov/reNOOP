#!/usr/bin/env python3
"""dreamt_probe.py: describe one DREAMT `data_64Hz` file without printing any of its rows.

The reducer that turns a participant file into per-second streams has to know how each column is laid
out on the 64 Hz grid: whether IBI is held between beats or left empty, which labels Sleep_Stage
carries, how far the timestamps run. This prints that as counts and ranges only. DREAMT is
restricted-access, so nothing a participant recorded is echoed.

USAGE:  dreamt_probe.py <participant csv>
"""
import collections
import csv
import os
import sys

EMPTY = {"", "nan", "NaN", "NA"}
MAX_LABELS = 12


def main(path):
    with open(path, newline="") as fh:
        reader = csv.reader(fh)
        header = next(reader)
        n = len(header)
        filled = [0] * n
        changes = [0] * n
        prev = [None] * n
        lo = [None] * n
        hi = [None] * n
        labels = [collections.Counter() for _ in header]
        rows = 0
        for row in reader:
            rows += 1
            for i, v in enumerate(row[:n]):
                if v in EMPTY:
                    continue
                filled[i] += 1
                if v != prev[i]:
                    changes[i] += 1
                    prev[i] = v
                try:
                    x = float(v)
                except ValueError:
                    labels[i][v] += 1
                    continue
                if lo[i] is None or x < lo[i]:
                    lo[i] = x
                if hi[i] is None or x > hi[i]:
                    hi[i] = x

    print(f"{os.path.basename(path)}: {os.path.getsize(path) / 1e6:.0f} MB, {rows} rows, {n} columns")
    print(f"{'column':<22}{'filled %':>9}{'value runs':>12}  range or labels")
    for i, name in enumerate(header):
        pct = 100.0 * filled[i] / rows if rows else 0.0
        if labels[i]:
            if len(labels[i]) <= MAX_LABELS:
                detail = ", ".join(f"{k}={c}" for k, c in labels[i].most_common())
            else:
                detail = f"{len(labels[i])} distinct text values"
        elif lo[i] is not None:
            detail = f"{lo[i]:g} .. {hi[i]:g}"
        else:
            detail = "empty"
        print(f"{name:<22}{pct:>9.1f}{changes[i]:>12}  {detail}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
