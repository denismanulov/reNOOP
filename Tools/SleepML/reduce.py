#!/usr/bin/env python3
"""reduce.py — bring each open sleep dataset to one shape: per-second streams plus 30 s stage labels.

Every source becomes what a WHOOP strap gives the stager, so one feature extractor reads them all:

    <night>.hr.csv      ts,bpm            heart rate at the source's own cadence
    <night>.grav.csv    ts,x,y,z          per-second MEAN acceleration in g (what `GravitySample` is)
    <night>.rr.csv      ts,rr_ms          beat intervals, only for sources that record them
    <night>.labels.csv  ts,stage,...      one row per scored 30 s epoch; unknown and artifact epochs are
                                          simply absent. stage is wake | light | deep | rem
                                          (light = N1 + N2, deep = N3 and the older N4).
    <night>.scorer2.csv ts,stage,code     Wearanize only: the second scorer's epochs, on their own grid
    nights.csv          one row per night: dataset, subject, night, window and row counts

`ts` is whole seconds on a per-night clock: TIME_BASE + seconds since the start of label epoch 0, so the
label grid lands on the 30 s wall-clock grid `SleepStagerV2` stages on (the same device `Tools/SleepPSG`
uses). Streams are kept from PAD_BEFORE seconds ahead of the first label to PAD_AFTER past the last.

Nothing here is committed and nothing is unpacked to disk: archives are read member by member, one night
at a time. Sources and the attribution their licences require are listed in `fetch_open.sh`.

USAGE
    reduce.py wearanize  [--src ~/datasets/wearanize-oa] [--out ~/datasets/reduced]
                         needs <out>/wearanize/ecg_sync.csv and scorer1_grid.csv, written by
                         wearanize_ecg_sync.py and wearanize_scorer1_grid.py
    reduce.py sleepaccel [--src ~/datasets/sleep-accel]  [--out ~/datasets/reduced]
    reduce.py summary    [--out ~/datasets/reduced]        counts and label stage shares per dataset
"""
import argparse
import csv
import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET

import numpy as np

TIME_BASE = 1_699_999_980          # a multiple of 30; `Tools/SleepPSG` uses the same value
PAD_BEFORE = 3600
PAD_AFTER = 1800
STAGES = ("wake", "light", "deep", "rem")


# ── shared ───────────────────────────────────────────────────────────────────────────────────────────

def numbers(blob, skip_lines=0):
    """Every number in a text table, flat, as float64. Separators are commas and any whitespace."""
    for _ in range(skip_lines):
        blob = blob[blob.index(b"\n") + 1:]
    return np.array(blob.replace(b",", b" ").split(), dtype=np.float64)


def per_second_mean(t, xyz):
    """Mean of every sample whose time floors to the same whole second. Returns (seconds, means)."""
    sec = np.floor(t).astype(np.int64)
    lo = int(sec.min())
    idx = sec - lo
    n = np.bincount(idx)
    keep = n > 0
    cols = [np.bincount(idx, weights=xyz[:, k])[keep] / n[keep] for k in range(3)]
    return np.nonzero(keep)[0] + lo, np.stack(cols, axis=1)


def write_night(out_dir, night, hr, grav, rr, labels, label_header):
    """hr (t, bpm), grav (sec, xyz), rr (t, ms) or None; labels: rows already carrying their ts."""
    os.makedirs(out_dir, exist_ok=True)
    base = os.path.join(out_dir, night)
    with open(base + ".hr.csv", "w") as f:
        f.write("ts,bpm\n")
        f.writelines("%d,%.2f\n" % (TIME_BASE + t, b) for t, b in zip(hr[0], hr[1]))
    with open(base + ".grav.csv", "w") as f:
        f.write("ts,x,y,z\n")
        f.writelines("%d,%.17g,%.17g,%.17g\n" % (TIME_BASE + t, x, y, z)
                     for t, (x, y, z) in zip(grav[0], grav[1]))
    if rr is not None and len(rr[0]):
        with open(base + ".rr.csv", "w") as f:
            f.write("ts,rr_ms\n")
            f.writelines("%d,%d\n" % (TIME_BASE + t, ms) for t, ms in zip(rr[0], rr[1]))
    elif os.path.exists(base + ".rr.csv"):
        os.remove(base + ".rr.csv")
    with open(base + ".labels.csv", "w") as f:
        f.write(label_header + "\n")
        f.writelines(",".join(str(v) for v in row) + "\n" for row in labels)


def clip(t, lo, hi):
    return (t >= lo) & (t < hi)


def write_index(out_dir, rows):
    cols = ["dataset", "subject", "night", "start", "end", "epochs", "scored", "hr_rows", "grav_rows",
            "rr_rows", "note"]
    with open(os.path.join(out_dir, "nights.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=cols, lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow(r)


# ── sleep-accel ──────────────────────────────────────────────────────────────────────────────────────
# Apple Watch: ~50 Hz accelerometer in g, heart rate about every 5 s, no beat intervals. Times are seconds
# from the start of PSG, and the files run for days either side of the lab night.

SLEEPACCEL_STAGE = {0: "wake", 1: "light", 2: "light", 3: "deep", 4: "deep", 5: "rem"}


def reduce_sleepaccel(src, out):
    out_dir = os.path.join(out, "sleepaccel")
    index = []
    ids = sorted(n[:-len("_labeled_sleep.txt")] for n in os.listdir(os.path.join(src, "labels"))
                 if n.endswith("_labeled_sleep.txt"))
    for sid in ids:
        lab = numbers(open(os.path.join(src, "labels", sid + "_labeled_sleep.txt"), "rb").read())
        lab = lab.reshape(-1, 2)
        rows = [(int(round(t / 30.0)) * 30, int(round(c))) for t, c in lab]
        scored = [(t, SLEEPACCEL_STAGE[c], c) for t, c in rows if c in SLEEPACCEL_STAGE]
        if not scored:
            continue
        first, last = scored[0][0], scored[-1][0]
        lo, hi = first - PAD_BEFORE, last + 30 + PAD_AFTER

        hr = numbers(open(os.path.join(src, "heart_rate", sid + "_heartrate.txt"), "rb").read())
        hr = hr.reshape(-1, 2)
        hsec = np.floor(hr[:, 0]).astype(np.int64)
        k = clip(hsec, lo, hi)
        order = np.argsort(hsec[k], kind="stable")
        hr = (hsec[k][order], hr[k, 1][order])

        acc = numbers(open(os.path.join(src, "motion", sid + "_acceleration.txt"), "rb").read())
        acc = acc.reshape(-1, 4)
        k = clip(np.floor(acc[:, 0]), lo, hi)
        gsec, gmean = per_second_mean(acc[k, 0], acc[k, 1:4])

        write_night(out_dir, sid, hr, (gsec, gmean), None,
                    [(TIME_BASE + t, s, c) for t, s, c in scored], "ts,stage,code")
        index.append(dict(dataset="sleepaccel", subject=sid, night=sid, start=TIME_BASE + first,
                          end=TIME_BASE + last + 30, epochs=(last - first) // 30 + 1, scored=len(scored),
                          hr_rows=len(hr[0]), grav_rows=len(gsec), rr_rows=0, note=""))
        print("sleepaccel %s: %d scored epochs, hr %d, grav %d" % (sid, len(scored), len(hr[0]), len(gsec)))
    write_index(out_dir, index)


# ── Wearanize+ OA ────────────────────────────────────────────────────────────────────────────────────
# Empatica E4 wristband beside full PSG, on separate clocks. The labels are scorer 1's rows, 30 s each,
# and they stay where they are: row k is label second 30k. It is the WRISTBAND that is moved onto them,
# in three measured steps, none of which the authors' manual synchronisation sheet can supply (taken at
# its word it leaves the wristband tens of seconds off and drifting 5 s more every hour):
#
#   1. wristband clock -> the authors' cut PSG recording, by the heart: the lag between the PSG ECG's
#      beats and the wristband's, a straight line over the night      (`wearanize_ecg_sync.py`)
#          tau = (e - e4_clock0) - (lag0 + drift * (e - e4_clock0) / 3600)
#   2. cut recording -> raw recording: the cut starts exactly on raw second 30 * scorer1_row0
#                                                                     (`wearanize_psg_cut.py`)
#   3. raw recording -> scorer 1's rows: its epochs are cut on the wall clock's half-minutes and its
#      file leads with a night-specific number of extra rows           (`wearanize_scorer1_grid.py`)
#          label second = tau + 30 * scorer1_row0 + wrist_shift_s
#
# Scorer 2 scored the cut recording itself, so its row j is tau in [30j, 30j + 30): it goes to its own
# file on its own grid, and beside scorer 1's rows only as the nearest row, for the inter-scorer ceiling.
# A night missing step 1 or 3 is not written.

XLSX_NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
WEARANIZE_STAGE = {0: "wake", 1: "light", 2: "light", 3: "deep", 4: "rem"}


def xlsx_rows(path):
    """First worksheet of an .xlsx as a list of dicts keyed by the header row (stdlib only)."""
    z = zipfile.ZipFile(path)
    shared = []
    if "xl/sharedStrings.xml" in z.namelist():
        for si in ET.fromstring(z.read("xl/sharedStrings.xml")).findall("m:si", XLSX_NS):
            shared.append("".join(t.text or "" for t in si.iter("{%s}t" % XLSX_NS["m"])))
    sheet = sorted(n for n in z.namelist() if re.match(r"xl/worksheets/sheet\d+\.xml", n))[0]
    rows = []
    for row in ET.fromstring(z.read(sheet)).iter("{%s}row" % XLSX_NS["m"]):
        cells = {}
        for c in row.findall("m:c", XLSX_NS):
            v = c.find("m:v", XLSX_NS)
            if v is None:
                continue
            col = re.match(r"[A-Z]+", c.get("r")).group(0)
            cells[col] = shared[int(v.text)] if c.get("t") == "s" else v.text
        rows.append(cells)
    header = rows[0]
    return [{header[k]: r.get(k) for k in header} for r in rows[1:]]


def wearanize_sync(raw_root):
    """SubjectID -> {column: int}. The sheet writes ids with a trailing apostrophe."""
    path = os.path.join(raw_root, "3.Manual_synchronization", "Manual_sync_zmax_psg_emp_actpal.xlsx")
    out = {}
    for r in xlsx_rows(path):
        sid = r["SubjectID"].strip().strip("'")
        out[sid] = {k: int(float(v)) for k, v in r.items() if k != "SubjectID" and v is not None}
    return out


def wearanize_scores(path):
    """Rows of a manual score file as (stage code, arousal flag)."""
    rows = []
    for line in open(path).read().splitlines()[1:]:
        p = line.split()
        if p:
            rows.append((int(p[0]), int(p[1]) if len(p) > 1 else 0))
    return rows


def e4_member(z, name):
    """An Empatica CSV: (start unix time, sample rate, values). IBI carries no rate row."""
    blob = z.read(name)
    first = blob[:blob.index(b"\n")]
    t0 = float(first.split(b",")[0])
    if name == "IBI.csv":
        v = numbers(blob, skip_lines=1)
        return t0, None, v.reshape(-1, 2)
    second = blob[len(first) + 1:]
    rate = float(second[:second.index(b"\n")].split(b",")[0])
    return t0, rate, numbers(blob, skip_lines=2)


def wearanize_ecg_fits(out_dir):
    """night -> (e4_clock0, scorer1_row0, lag0 seconds, drift seconds per hour), fitted nights only."""
    path = os.path.join(out_dir, "ecg_sync.csv")
    if not os.path.exists(path):
        sys.exit("no %s: run wearanize_ecg_sync.py first" % path)
    out = {}
    for r in csv.DictReader(open(path)):
        if r["lag0_s"] and r["scorer1_row0"] != "":
            out[r["night"]] = (int(r["e4_clock0"]), int(r["scorer1_row0"]), float(r["lag0_s"]),
                               float(r["drift_s_per_h"]))
    return out


def wearanize_grid(out_dir):
    """night -> seconds added to the wristband clock so that it lands on scorer 1's rows."""
    path = os.path.join(out_dir, "scorer1_grid.csv")
    if not os.path.exists(path):
        sys.exit("no %s: run wearanize_scorer1_grid.py first" % path)
    return {r["night"]: int(r["wrist_shift_s"]) for r in csv.DictReader(open(path)) if r["wrist_shift_s"] != ""}


def per_second_value(t, v):
    """One value per whole second: the mean of the samples that floor into it. A 1 Hz channel on a
    stretched clock lands two samples in one second every few minutes."""
    sec = np.floor(t).astype(np.int64)
    uniq, inverse = np.unique(sec, return_inverse=True)
    return uniq, np.bincount(inverse, weights=v) / np.bincount(inverse)


def usable_sync(s):
    """None when the row lets the wristband be placed on PSG time, else the reason it does not."""
    for dev in ("PSG", "Emp"):
        a, b = s.get(dev + "_start_sec"), s.get(dev + "_end_sec")
        if a is None or b is None:
            return dev + " missing from the sheet"
        if a == -999 or b == -999:
            return dev + " could not be synchronised (-999)"
        if a == 0 and b == 0:
            return dev + " recording unavailable (0/0)"
    return None


def reduce_wearanize(src, out):
    raw = os.path.join(src, "Wearanize+_OA_raw_v1.1")
    sync = wearanize_sync(raw)
    s1_dir = os.path.join(raw, "2.Sleep_scores", "1.PSG_manual_scores_scorer1")
    s2_dir = os.path.join(raw, "2.Sleep_scores", "2.PSG_manual_scores_scorer2")
    out_dir = os.path.join(out, "wearanize")
    fits = wearanize_ecg_fits(out_dir)
    grid = wearanize_grid(out_dir)
    index, skipped = [], {}

    def skip(sid, why):
        skipped[why] = skipped.get(why, 0) + 1
        print("wearanize %s: skipped, %s" % (sid, why))

    scored_ids = sorted(n[:-4] for n in os.listdir(s1_dir) if n.endswith(".txt"))
    for sid in scored_ids:
        zpath = os.path.join(raw, "1.Raw_data", sid, "3.Empatica", sid + "_Empatica_data.zip")
        if not os.path.exists(zpath):
            skip(sid, "no wristband archive"); continue
        if sid not in sync:
            skip(sid, "not in the synchronisation sheet"); continue
        why = usable_sync(sync[sid])
        if why:
            skip(sid, why); continue
        if sid not in fits:
            skip(sid, "no ECG fit for the wristband clock"); continue
        if sid not in grid:
            skip(sid, "scorer 1's rows could not be placed on the recording"); continue
        clock0, row0, lag0, drift = fits[sid]
        cut = 30 * row0 + grid[sid]                         # label second of the authors' time 0

        def psg_time(e):                                    # wristband seconds -> label seconds
            tau = e - clock0
            return tau - (lag0 + drift * tau / 3600.0) + cut

        s1 = wearanize_scores(os.path.join(s1_dir, sid + ".txt"))
        s2_path = os.path.join(s2_dir, sid + ".txt")
        s2 = wearanize_scores(s2_path) if os.path.exists(s2_path) else []
        s2_row0 = int(round(cut / 30.0))                    # scorer 1's row nearest to scorer 2's row 0
        labels = []
        for k, (code, arousal) in enumerate(s1):
            if code not in WEARANIZE_STAGE:
                continue
            other = WEARANIZE_STAGE.get(s2[k - s2_row0][0], "") if 0 <= k - s2_row0 < len(s2) else ""
            labels.append((k * 30, WEARANIZE_STAGE[code], code, arousal, other))
        if not labels:
            skip(sid, "no scored epoch"); continue
        first, last = labels[0][0], labels[-1][0]
        lo, hi = first - PAD_BEFORE, last + 30 + PAD_AFTER

        z = zipfile.ZipFile(zpath)
        names = set(z.namelist())
        if not {"ACC.csv", "HR.csv"} <= names:
            skip(sid, "wristband archive lacks ACC or HR"); continue
        t0, rate, v = e4_member(z, "ACC.csv")
        acc = v.reshape(-1, 3) / 64.0                       # the E4 reports 1/64 g
        t = psg_time(np.arange(len(acc)) / rate)
        k = clip(t, lo, hi)
        if not k.any():
            skip(sid, "wristband does not overlap the scored night"); continue
        gsec, gmean = per_second_mean(t[k], acc[k])

        h0, hrate, hv = e4_member(z, "HR.csv")
        ht = psg_time((h0 - t0) + np.arange(len(hv)) / hrate)
        k = clip(ht, lo, hi) & (hv > 0)
        hr = per_second_value(ht[k], hv[k])

        rr = None
        if "IBI.csv" in names and len(z.read("IBI.csv")) > 40:
            i0, _, iv = e4_member(z, "IBI.csv")
            it = np.floor(psg_time((i0 - t0) + iv[:, 0])).astype(np.int64)
            k = clip(it, lo, hi)
            rr = (it[k], np.rint(iv[k, 1] * 1000.0).astype(np.int64))

        # Epochs the wristband was not recording through cannot be staged from it; say how many.
        gset = np.zeros(last + 30 - first, dtype=bool)
        inside = gsec[(gsec >= first) & (gsec < last + 30)] - first
        gset[inside] = True
        covered = sum(1 for (ts, *_r) in labels if gset[ts - first:ts - first + 30].mean() >= 0.5)

        write_night(out_dir, sid, hr, (gsec, gmean), rr,
                    [(TIME_BASE + ts, st, code, ar, o) for ts, st, code, ar, o in labels],
                    "ts,stage,code,arousal,stage_scorer2")
        if s2:
            with open(os.path.join(out_dir, sid + ".scorer2.csv"), "w") as f:
                f.write("ts,stage,code\n")
                f.writelines("%d,%s,%d\n" % (TIME_BASE + cut + 30 * j, WEARANIZE_STAGE[c], c)
                             for j, (c, _) in enumerate(s2) if c in WEARANIZE_STAGE)
        s = sync[sid]
        note = "lights %d..%d; wristband covers %d of %d scored epochs; clock lag %.1f s, drift %.2f s/h," \
               " moved %+d s onto scorer 1" % (cut, cut + s["PSG_dur_sec"], covered, len(labels), lag0, drift,
                                               grid[sid])
        index.append(dict(dataset="wearanize", subject=sid, night=sid, start=TIME_BASE + first,
                          end=TIME_BASE + last + 30, epochs=(last - first) // 30 + 1, scored=len(labels),
                          hr_rows=len(hr[0]), grav_rows=len(gsec), rr_rows=0 if rr is None else len(rr[0]),
                          note=note))
        print("wearanize %s: %d scored epochs (%d with wristband), hr %d, grav %d, rr %d" % (
            sid, len(labels), covered, len(hr[0]), len(gsec), 0 if rr is None else len(rr[0])))
    write_index(out_dir, index)
    print("wearanize: %d nights written; skipped: %s" % (len(index), skipped or "none"))


# ── summary ──────────────────────────────────────────────────────────────────────────────────────────

def summary(out):
    """Aggregates only: usable people and nights, and the stage shares of the labels, both conventions."""
    for ds in sorted(d for d in os.listdir(out) if os.path.exists(os.path.join(out, d, "nights.csv"))):
        nights = list(csv.DictReader(open(os.path.join(out, ds, "nights.csv"))))
        pooled = dict.fromkeys(STAGES, 0)
        shares = []
        for n in nights:
            c = dict.fromkeys(STAGES, 0)
            for r in csv.DictReader(open(os.path.join(out, ds, n["night"] + ".labels.csv"))):
                c[r["stage"]] += 1
            tot = sum(c.values())
            shares.append([c[s] / tot for s in STAGES])
            for s in STAGES:
                pooled[s] += c[s]
        tot = sum(pooled.values())
        mean = np.mean(shares, axis=0)
        hours = np.array([int(n["scored"]) for n in nights]) / 120.0
        print("%-11s people %3d  nights %3d  scored epochs %6d  hours/night median %.2f (min %.2f, max %.2f)"
              "  with beat intervals %d" % (ds, len({n["subject"] for n in nights}), len(nights), tot,
                                           np.median(hours), hours.min(), hours.max(),
                                           sum(1 for n in nights if int(n["rr_rows"]) > 0)))
        print("            pooled over epochs       " + "  ".join(
            "%s %5.2f %%" % (s, 100.0 * pooled[s] / tot) for s in STAGES))
        print("            mean of per-night shares " + "  ".join(
            "%s %5.2f %%" % (s, 100.0 * m) for s, m in zip(STAGES, mean)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", choices=["wearanize", "sleepaccel", "summary"])
    ap.add_argument("--src")
    ap.add_argument("--out", default="~/datasets/reduced")
    a = ap.parse_args()
    out = os.path.expanduser(a.out)
    if a.mode == "sleepaccel":
        reduce_sleepaccel(os.path.expanduser(a.src or "~/datasets/sleep-accel"), out)
    elif a.mode == "wearanize":
        reduce_wearanize(os.path.expanduser(a.src or "~/datasets/wearanize-oa"), out)
    else:
        summary(out)


if __name__ == "__main__":
    sys.exit(main())
