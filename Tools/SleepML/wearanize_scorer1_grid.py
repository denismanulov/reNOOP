#!/usr/bin/env python3
"""wearanize_scorer1_grid.py — where do scorer 1's rows sit on the PSG recording?

Scorer 1 is the label source, and its rows are NOT the recording's seconds [30k, 30k + 30). Two things
are true instead, and both were found by measurement, not read from any documentation:

  1. Scorer 1's epochs are cut on the wall clock, on the half-minutes hh:mm:00 and hh:mm:30. A recording
     that started at 22:05:42 therefore has its epoch boundaries 18 s, 48 s, ... into the recording.
  2. The file's first row is not always the first of those epochs: on about half the nights one extra
     row leads, on a few there are more or fewer.

So row k covers recording seconds [30 (k + n) - s, 30 (k + n) - s + 30), where s is the second of the
half-minute the recording started on (exact, from the recording's header) and n is a whole number per
night. n is measured here against a physiological anchor that needs no other scorer: an arousal makes
the heart rate jump, the PSG's own ECG shows the jump to the second, and scorer 1 flagged the epochs
holding arousals and awakenings. Sliding those epochs along the night's heart-rate surges gives the place
where they cover the surges best ("centre"). The centre lands within a few seconds of one candidate
30 (k + n) - s and 30 s from the next, so n is not a judgement call.

The method checks itself on scorer 2, whose rows are known to sit on the recording (scorer 2 scored the
authors' cut recording, and `wearanize_psg_cut.py` shows that cut begins exactly on a 30 s boundary of
the raw one): run on scorer 2's awakenings it must return 0, and the spread of what it returns is its
precision. Both are printed.

What `reduce.py` uses is `wrist_shift_s` = s - 30 n: the seconds added to the wristband's clock so that it
lands on scorer 1's rows. The labels themselves are not moved.

Needs `ecg_sync.csv` and the kept ECG beat times from `wearanize_ecg_sync.py`. Fetches only the header of
each raw PSG recording, for its start time (about 70 kB a night), and only for nights not yet in the table.

USAGE   wearanize_scorer1_grid.py [--src ~/datasets/wearanize-oa] [--out ~/datasets/reduced]
"""
import argparse
import csv
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reduce as R  # noqa: E402
from wearanize_ecg_sync import BASE  # noqa: E402
from wearanize_psg_cut import ARCHIVE, RAW, MemberHead  # noqa: E402

REACH = 150                 # seconds either side over which the epochs are slid
MIN_EVENTS = 8
COLUMNS = ["night", "record_start", "start_second", "events", "centre_s", "whole_rows", "residual_s",
           "wrist_shift_s", "events_scorer2", "centre_scorer2_s", "note"]


def record_start(sid, device):
    """hh.mm.ss the raw PSG recording started at, from its EDF header; "" for a format without one."""
    head = MemberHead("%s/%s/1.Raw_data/%s/%s" % (BASE, RAW.replace("+", "%2B"), sid, ARCHIVE[device] % sid))
    if not head.name.lower().endswith(".edf"):
        return ""
    head.rf.seek(head.next)
    first = head.inflater.decompress(head.rf.read(2048)) if head.inflater else head.rf.read(2048)
    return first[176:184].decode("ascii")


def surge(beats):
    """Per second: how far the heart rate stands above the half-minute before it, from ECG beat times."""
    dur = int(beats[-1]) + 1
    rr = np.diff(beats)
    ok = (rr > 0.35) & (rr < 1.8)
    t, h = beats[1:][ok], 60.0 / rr[ok]
    hr = np.full(dur, np.nan)
    lo = np.searchsorted(t, np.arange(dur) - 1.5)
    hi = np.searchsorted(t, np.arange(dur) + 1.5)
    for i in range(dur):
        if hi[i] - lo[i] >= 2:
            hr[i] = np.median(h[lo[i]:hi[i]])
    base = np.full(dur, np.nan)
    for i in range(40, dur):
        seg = hr[i - 40:i - 8]
        if np.isfinite(seg).sum() >= 10:
            base[i] = np.nanmedian(seg)
    return np.clip(np.nan_to_num(hr - base, nan=0.0), 0, 40)


def centre(sg, starts):
    """(d, events used): the shift d at which 30 s windows opened at `starts` + d hold the most surge,
    as the midpoint of the top third of that profile. 0 when the windows already sit on the surges."""
    total = np.concatenate([[0.0], np.cumsum(sg)])
    ds = np.arange(-REACH, REACH + 1)
    profile, used = np.zeros(len(ds)), 0
    for s in starts:
        a = s + ds
        if a[0] >= 0 and a[-1] + 30 < len(sg):
            profile += total[a + 30] - total[a]
            used += 1
    if used < MIN_EVENTS:
        return None, used
    top = profile >= profile.min() + (profile.max() - profile.min()) * (2.0 / 3.0)
    a = b = int(profile.argmax())
    while a > 0 and top[a - 1]:
        a -= 1
    while b < len(ds) - 1 and top[b + 1]:
        b += 1
    return (ds[a] + ds[b]) / 2.0, used


def onsets(scores, arousals=True):
    """Rows that open an awakening or (scorer 1 only) an arousal after undisturbed sleep."""
    code = [c for c, _ in scores]
    flag = [a for _, a in scores]
    rows = set()
    for i in range(2, len(code) - 1):
        if code[i] == 0 and code[i - 1] > 0 and code[i - 2] > 0:
            rows.add(i)
        if arousals and flag[i] and code[i] > 0 and not flag[i - 1] and code[i - 1] > 0:
            rows.add(i)
    return sorted(rows)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", default="~/datasets/wearanize-oa")
    ap.add_argument("--out", default="~/datasets/reduced")
    a = ap.parse_args()
    src = os.path.expanduser(a.src)
    out_dir = os.path.join(os.path.expanduser(a.out), "wearanize")
    scores = os.path.join(src, RAW, "2.Sleep_scores")
    fits = {r["night"]: r for r in csv.DictReader(open(os.path.join(out_dir, "ecg_sync.csv"))) if r["lag0_s"]}
    table = os.path.join(out_dir, "scorer1_grid.csv")
    done = {r["night"]: r for r in csv.DictReader(open(table))} if os.path.exists(table) else {}

    rows = {}
    for sid in sorted(fits):
        row = dict.fromkeys(COLUMNS, "")
        row["night"] = sid
        row["record_start"] = done.get(sid, {}).get("record_start", "")
        if not row["record_start"] and "no start time" not in done.get(sid, {}).get("note", ""):
            row["record_start"] = record_start(sid, fits[sid]["psg_device"])
        row0 = int(fits[sid]["scorer1_row0"])
        sg = surge(np.load(os.path.join(out_dir, "ecg_beats", sid + ".npy")).astype(np.float64))
        # the recording's second 30 * row0 is the authors' time 0, where the kept beat times start
        s1 = R.wearanize_scores(os.path.join(scores, "1.PSG_manual_scores_scorer1", sid + ".txt"))
        c, n = centre(sg, [30 * (k - row0) for k in onsets(s1)])
        row["events"] = n
        s2_path = os.path.join(scores, "2.PSG_manual_scores_scorer2", sid + ".txt")
        if os.path.exists(s2_path):
            c2, n2 = centre(sg, [30 * k for k in onsets(R.wearanize_scores(s2_path), arousals=False)])
            row["events_scorer2"] = n2
            row["centre_scorer2_s"] = "" if c2 is None else "%.1f" % c2
        if c is None:
            row["note"] = "too few arousals and awakenings to place the rows"
        else:
            row["centre_s"] = "%.1f" % c
        rows[sid] = row

    # Scorer 1's flagged epoch holds its arousal anywhere in its 30 s, an awakening's first epoch holds
    # it in the first half: the centre therefore stands a few seconds late of the epoch grid, by the same
    # amount every night. That constant is the median distance to the grid, taken over the cohort.
    def off_grid(row, bias=0.0):
        s = int(row["record_start"].split(".")[2]) % 30
        n = int(np.round((float(row["centre_s"]) - bias + s) / 30.0))
        return s, n, float(row["centre_s"]) + s - 30 * n
    timed = [r for r in rows.values() if r["centre_s"] and r["record_start"]]
    bias = float(np.median([off_grid(r)[2] for r in timed]))
    for row in rows.values():
        if not row["centre_s"]:
            continue
        if row["record_start"]:
            s, n, resid = off_grid(row, bias)
            row.update(start_second=s, whole_rows=-n, residual_s="%.1f" % (resid - bias), wrist_shift_s=s - 30 * n)
        else:
            row["wrist_shift_s"] = int(np.round(-(float(row["centre_s"]) - bias)))
            row["note"] = "no start time in this recording's format: placed by the measured centre alone"

    with open(table, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        for sid in sorted(rows):
            w.writerow(rows[sid])

    placed = [r for r in rows.values() if r["wrist_shift_s"] != ""]
    resid = np.array([float(r["residual_s"]) for r in placed if r["residual_s"]])
    lead = np.array([int(r["whole_rows"]) for r in placed if r["whole_rows"] != ""])
    shift = np.array([int(r["wrist_shift_s"]) for r in placed])
    ctrl = np.array([float(r["centre_scorer2_s"]) for r in rows.values() if r["centre_scorer2_s"]])
    print("nights placed: %d of %d" % (len(placed), len(rows)))
    for r in rows.values():
        if r["note"]:
            print("  %s: %s" % (r["night"], r["note"]))
    print("control, scorer 2 (its rows sit on the recording, so the answer is 0): median %+.1f s, sd %.1f s,"
          " range %+.1f .. %+.1f over %d nights" % (np.median(ctrl), ctrl.std(), ctrl.min(), ctrl.max(), len(ctrl)))
    print("scorer 1: centre stands %+.1f s late of the wall-clock grid on every night (arousals fall anywhere"
          " in their epoch); around that, sd %.1f s, largest %.1f s, against 15 s to the next candidate"
          % (bias, resid.std(), np.abs(resid).max()))
    print("extra leading rows in scorer 1's file:", dict(zip(*[x.tolist() for x in np.unique(lead, return_counts=True)])))
    print("seconds added to the wristband clock: median %+d, quartiles %+d .. %+d, range %+d .. %+d" % (
        np.median(shift), np.percentile(shift, 25), np.percentile(shift, 75), shift.min(), shift.max()))


if __name__ == "__main__":
    sys.exit(main())
