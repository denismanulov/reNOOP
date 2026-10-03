#!/usr/bin/env python3
"""check_wearanize_sync.py — is the wristband really placed on PSG time the way `reduce.py` places it?

The authors' manual synchronisation sheet maps a wristband second e to PSG second
e + (PSG_start_sec - Emp_start_sec). `files` and `beats` are the measurement of how far that can be trusted
(it cannot: tens of seconds, plus about 5 s more every hour), which is why `reduce.py` places the wristband
by `wearanize_ecg_sync.py` and `wearanize_scorer1_grid.py` instead; `transitions` is the test of the nights
it then writes. Each prints what it measured rather than a verdict:

  files    The authors publish synchronised recordings (PlugNPlay). For each one present, find where its
           wristband heart rate and accelerometer sit in the RAW wristband files by exhaustive search, and
           compare that with the sheet; then find which raw score rows its score vector is.
  beats    Inside a synchronised file, the PSG's own ECG against the wristband's beat intervals, hour by
           hour: the lag between two measurements of the same heart, which no spreadsheet is involved in.
  transitions
           Over every reduced night: when the wrist moves, relative to the first wake epoch of each
           sleep->wake transition, by hour of night. `sleepaccel` runs through the same code as a control,
           and Wearanize is read twice, once by each scorer's rows.

The PlugNPlay files are Parquet, read with `parquet_min.py`. `wearanize_ecg_sync.py` is what turns the
`beats` finding into a per-night correction.

USAGE   check_wearanize_sync.py files|beats|transitions [--src ~/datasets/wearanize-oa] [--out ~/datasets/reduced]
"""
import argparse
import csv
import math
import os
import sys
import zipfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reduce as R  # noqa: E402
from parquet_min import Parquet  # noqa: E402


# ── files ────────────────────────────────────────────────────────────────────────────────────────────

def check_files(src):
    raw = os.path.join(src, "Wearanize+_OA_raw_v1.1")
    pdir = os.path.join(src, "Wearanize+_OA_PlugNPlay_Parquet_v1.1")
    sync = R.wearanize_sync(raw)
    for name in sorted(os.listdir(pdir)):
        if not name.endswith(".parquet"):
            continue
        sid = name[:-len(".parquet")]
        s = sync[sid]
        pq = Parquet(os.path.join(pdir, name))
        devices = pq.device_rows()
        # The authors start their common clock on the Zmax 30 s grid at or before lights-out.
        snap = (s["Zmax_start_sec"] - 1) % 30
        print("%s  devices %s" % (sid, devices))
        print("  sheet: Zmax %d  PSG %d  Emp %d   (seconds, counted from 1); Zmax grid snap %d s"
              % (s["Zmax_start_sec"], s["PSG_start_sec"], s["Emp_start_sec"], snap))

        if "Empatica E4" in devices and R.usable_sync(s) is None:
            row = devices.index("Empatica E4")
            z = zipfile.ZipFile(os.path.join(raw, "1.Raw_data", sid, "3.Empatica", sid + "_Empatica_data.zip"))
            expect = s["Emp_start_sec"] - 1 - snap          # raw second where their clock should start
            # heart rate: 1 Hz, exhaustive search +-300 s around the sheet
            mine = pq.rows("SignalData.HR.list.element")[row].astype(np.float64)
            _, _, theirs = R.e4_member(z, "HR.csv")
            best = min(((np.mean(np.abs(theirs[k:k + len(mine)] - mine[:len(theirs) - k]) > 0.006), k)
                        for k in range(max(0, expect - 300), expect + 300)), key=lambda x: x[0])
            print("  heart rate   : their sample 0 is raw sample %d (mismatching samples %.4f %% of %d);"
                  " sheet predicts %d -> off by %d s" % (best[1], 100 * best[0], len(mine), expect,
                                                          best[1] - expect))
            # accelerometer: 32 Hz, search on the liveliest 200 s, then confirm over the whole night
            mine = pq.rows("SignalData.ACCX.list.element")[row].astype(np.float64)
            _, rate, v = R.e4_member(z, "ACC.csv")
            theirs = v.reshape(-1, 3)[:, 0]
            win = int(200 * rate)
            c = int(np.argmax([mine[i:i + win].std() for i in range(0, len(mine) - win, win)])) * win
            base = int(expect * rate) + c
            lags = range(int(-300 * rate), int(300 * rate))
            best = min(((np.mean(np.abs(theirs[base + g:base + g + win] - mine[c:c + win]) > 1e-3), g)
                        for g in lags if base + g >= 0 and base + g + win <= len(theirs)), key=lambda x: x[0])
            a = int(expect * rate) + best[1]
            n = min(len(mine), len(theirs) - a)
            whole = np.mean(np.abs(theirs[a:a + n] - mine[:n]) > 1e-3)
            print("  accelerometer: their sample 0 is raw sample %d (mismatching samples %.4f %% of %d);"
                  " sheet predicts %d -> off by %+.3f s" % (a, 100 * whole, n, int(expect * rate),
                                                            best[1] / rate))
        else:
            print("  no usable wristband row: %s" % (R.usable_sync(s) or "device absent from the file"))

        theirs = [x for x in pq.rows("SleepScores.ManualScores1.list.element") if len(x)][0].astype(int)
        mine = np.array([c for c, _ in R.wearanize_scores(
            os.path.join(raw, "2.Sleep_scores", "1.PSG_manual_scores_scorer1", sid + ".txt"))])
        exact = [k for k in range(0, 60) if np.array_equal(mine[k:k + len(theirs)], theirs[:len(mine) - k])]
        clock0 = s["PSG_start_sec"] - 1 - snap              # PSG second where their signals start
        print("  scores       : their %d epochs are raw rows starting at %s; ceil(PSG_start/30) = %d"
              % (len(theirs), exact, math.ceil(s["PSG_start_sec"] / 30)))
        if exact:
            print("                 their signals start at PSG second %d, their first label is PSG seconds"
                  " [%d, %d): labels sit %d s late in their file" % (clock0, 30 * exact[0], 30 * exact[0] + 30,
                                                                     30 * exact[0] - clock0))


# ── beats ────────────────────────────────────────────────────────────────────────────────────────────

def r_peaks(ecg, rate):
    """Beat times in seconds, from an ECG: band-pass, square, integrate, pick peaks."""
    from scipy.signal import butter, sosfiltfilt, find_peaks
    sos = butter(2, [5.0, 25.0], btype="band", fs=rate, output="sos")
    y = sosfiltfilt(sos, ecg.astype(np.float64)) ** 2
    w = int(0.12 * rate)
    y = np.convolve(y, np.ones(w) / w, mode="same")
    out = []
    block = int(30 * rate)
    for a in range(0, len(y), block):
        seg = y[a:a + block]
        pk, _ = find_peaks(seg, height=0.25 * np.percentile(seg, 99), distance=int(0.3 * rate))
        out.append((pk + a) / rate)
    return np.concatenate(out)


def second_hr(t, rr, dur, win=8):
    """Heart rate per second: the median of the beats within `win` seconds, slow trend removed."""
    ok = (rr > 0.35) & (rr < 1.8)
    t, h = t[ok], 60.0 / rr[ok]
    out = np.full(dur, np.nan)
    lo = np.searchsorted(t, np.arange(dur) - win / 2)
    hi = np.searchsorted(t, np.arange(dur) + win / 2)
    for i in range(dur):
        if hi[i] - lo[i] >= 4:
            out[i] = np.median(h[lo[i]:hi[i]])
    w = 90
    trend = np.convolve(np.nan_to_num(out), np.ones(w), "same") / np.maximum(
        np.convolve(~np.isnan(out), np.ones(w), "same"), 1)
    return out - trend


def best_lag(a, b, lo, hi, reach=200):
    """(correlation, seconds by which b trails a) over [lo, hi)."""
    a, b, res = a[lo:hi], b[lo:hi], []
    for lag in range(-reach, reach + 1):
        x, y = (a[lag:], b[:len(b) - lag]) if lag >= 0 else (a[:lag], b[-lag:])
        k = ~np.isnan(x) & ~np.isnan(y)
        if k.sum() > 300:
            res.append((np.corrcoef(x[k], y[k])[0, 1], -lag))
    return max(res) if res else (float("nan"), 0)


def check_beats(src):
    raw = os.path.join(src, "Wearanize+_OA_raw_v1.1")
    pdir = os.path.join(src, "Wearanize+_OA_PlugNPlay_Parquet_v1.1")
    sync = R.wearanize_sync(raw)
    for name in sorted(os.listdir(pdir)):
        sid = name[:-len(".parquet")]
        if not name.endswith(".parquet") or R.usable_sync(sync[sid]) is not None:
            continue
        pq = Parquet(os.path.join(pdir, name))
        devices = pq.device_rows()
        if "Empatica E4" not in devices:
            continue
        s = sync[sid]
        ecg = None
        for leaf in ("ECG 2", "ECG1", "ECG2", "ECG"):
            key = "SignalData.%s.list.element" % leaf
            got = [(i, x) for i, x in enumerate(pq.rows(key)) if len(x)] if key in pq.leaves else []
            if got:
                row, ecg = got[0]
                rate = float(pq.rows("SamplingRate." + leaf)[row][0])
                break
        if ecg is None:
            print("%s: no ECG channel" % sid)
            continue
        dur = int(len(ecg) / rate)
        bt = r_peaks(ecg, rate)
        a = second_hr(bt[1:], np.diff(bt), dur)
        # the wristband's own beat intervals, placed on the authors' clock exactly as their file places
        # its other wristband channels (`files` shows that placement is the sheet's)
        z = zipfile.ZipFile(os.path.join(raw, "1.Raw_data", sid, "3.Empatica", sid + "_Empatica_data.zip"))
        _, _, ibi = R.e4_member(z, "IBI.csv")
        clock0 = s["Emp_start_sec"] - 1 - (s["Zmax_start_sec"] - 1) % 30
        b = second_hr(ibi[:, 0] - clock0, ibi[:, 1], dur)
        r, lag = best_lag(a, b, 0, dur)
        print("%s  %s ECG against wristband beats, whole night: wristband trails by %+d s (r %.3f)"
              % (sid, devices[row], lag, r))
        for h in range(dur // 3600 + 1):
            r, lag = best_lag(a, b, h * 3600, min(dur, (h + 1) * 3600))
            print("    hour %d: trails by %+4d s (r %.3f)" % (h, lag, r))


# ── transitions ──────────────────────────────────────────────────────────────────────────────────────

def bursts(out, dataset, lights=None, labels=".labels.csv"):
    """(night, hours into the night, seconds from the first wake epoch's start to the wrist's largest
    movement within 150 s of it), for every wake epoch that follows three minutes of scored sleep.
    `labels` picks whose scores, by the suffix of the file they are in."""
    d = os.path.join(out, dataset)
    rows = []
    for n in csv.DictReader(open(os.path.join(d, "nights.csv"))):
        g = np.loadtxt(os.path.join(d, n["night"] + ".grav.csv"), delimiter=",", skiprows=1)
        path = os.path.join(d, n["night"] + labels)
        lab = list(csv.DictReader(open(path))) if os.path.exists(path) else []
        if not lab:
            continue
        t0 = int(g[0, 0])
        xyz = np.full((int(g[-1, 0]) - t0 + 1, 3), np.nan)
        xyz[g[:, 0].astype(int) - t0] = g[:, 1:4]
        jerk = np.sqrt((np.diff(xyz, axis=0) ** 2).sum(axis=1))
        act = np.convolve(np.nan_to_num(np.log1p(jerk / np.nanmedian(jerk))), np.ones(5) / 5, "same")
        ts = np.array([int(r["ts"]) for r in lab])
        wake = np.array([r["stage"] == "wake" for r in lab])
        origin = lights(n["night"]) if lights else int(n["start"])
        for i in range(6, len(lab)):
            if wake[i] and not wake[i - 6:i].any() and ts[i] - ts[i - 6] == 180:
                t = ts[i] - t0
                if t - 150 < 0 or t + 150 >= len(act):
                    continue
                seg = act[t - 150:t + 151]
                if seg.max() >= 1.0:                     # a transition with no real movement says nothing
                    rows.append((n["night"], (ts[i] - origin) / 3600.0, int(seg.argmax()) - 150))
    return rows


def check_transitions(out, src):
    fits = R.wearanize_ecg_fits(os.path.join(out, "wearanize"))
    grid = R.wearanize_grid(os.path.join(out, "wearanize"))

    def lights(night):                                      # the authors' time 0, as reduce.py places it
        return R.TIME_BASE + 30 * fits[night][1] + grid[night]
    sets = [("sleepaccel", None, ".labels.csv", "sleepaccel"),
            ("wearanize", lights, ".labels.csv", "wearanize, scorer 1"),
            ("wearanize", lights, ".scorer2.csv", "wearanize, scorer 2")]
    print("Seconds from the start of a sleep->wake transition's first wake epoch to the wrist's movement burst.")
    print("An awakening begins within 15 s either side of that boundary, so aligned data centres near it.")
    for dataset, origin, labels, name in sets:
        if not os.path.exists(os.path.join(out, dataset, "nights.csv")):
            continue
        rows = bursts(out, dataset, origin, labels)
        h, b = np.array([r[1] for r in rows]), np.array([r[2] for r in rows])
        print("%s: %d transitions from %d nights; median %+.0f s, quartiles %+.0f .. %+.0f" % (
            name, len(b), len({r[0] for r in rows}), np.median(b), np.percentile(b, 25), np.percentile(b, 75)))
        for lo, hi in ((0, 1.5), (1.5, 3), (3, 4.5), (4.5, 6), (6, 7.5), (7.5, 12)):
            k = (h >= lo) & (h < hi)
            if k.sum() >= 10:
                print("    hours %.1f-%.1f: n %4d  median %+4.0f s" % (lo, hi, k.sum(), np.median(b[k])))
        keep = np.abs(b - np.median(b)) < 90
        slope, icpt = np.linalg.lstsq(np.vstack([h, np.ones_like(h)]).T[keep], b[keep], rcond=None)[0]
        print("    line through them: %+.1f s at the start of the night, %+.2f s per hour" % (icpt, slope))
        per = {}
        for night, hh, bb in rows:
            per.setdefault(night, []).append(bb - slope * hh)
        med = np.array([np.median(v) for v in per.values() if len(v) >= 5])
        print("    per-night median with that slope removed (%d nights with 5+ transitions): sd %.0f s, range"
              " %+.0f .. %+.0f s" % (len(med), med.std(), med.min(), med.max()))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", choices=["files", "beats", "transitions"])
    ap.add_argument("--src", default="~/datasets/wearanize-oa")
    ap.add_argument("--out", default="~/datasets/reduced")
    a = ap.parse_args()
    src, out = os.path.expanduser(a.src), os.path.expanduser(a.out)
    if a.mode == "files":
        check_files(src)
    elif a.mode == "beats":
        check_beats(src)
    else:
        check_transitions(out, src)


if __name__ == "__main__":
    sys.exit(main())
