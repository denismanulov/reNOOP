#!/usr/bin/env python3
"""wearanize_ecg_sync.py — place each Wearanize+ OA wristband on PSG time by the heart itself.

The authors' manual synchronisation sheet is not enough (`check_wearanize_sync.py`): against the PSG's own
ECG the wristband trails by tens of seconds and gains about 5 s more every hour. The heart is recorded
twice, by the PSG's ECG and by the wristband's photoplethysmograph, so the lag between those two beat
series is the clock error, measured, night by night.

For every participant this reads ONE column of the authors' synchronised recording, the PSG ECG, by HTTP
byte range (about 14 MB of a 400 MB file), detects its beats, and slides the wristband's beat intervals
against them in half-hour windows. A straight line through the window lags gives that night's offset and
drift. Three small columns from the same file pin down the rest: which raw wristband second the authors'
clock starts on, and which raw score row their first epoch is.

Kept on disk: `ecg_sync.csv` (one row per night) and the ECG beat times (`ecg_beats/<night>.npy`, about
250 kB each) so the fit can be redone without fetching again. No ECG is stored.

Run again and it only refits, from the kept beat times, fetching just what is missing.

USAGE   wearanize_ecg_sync.py [--src ~/datasets/wearanize-oa] [--out ~/datasets/reduced]
                              [--only Sub005s1,Sub124s1] [--force]
"""
import argparse
import csv
import os
import sys
import zipfile

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reduce as R  # noqa: E402
import check_wearanize_sync as C  # noqa: E402
from parquet_min import Parquet, RangeFile  # noqa: E402

BASE = "https://webdav.data.ru.nl/dcmn/DSC_wrnzpoa_t0000925a_195_v1"
PLUGNPLAY = "Wearanize+_OA_PlugNPlay_Parquet_v1.1"
WINDOW, STEP, REACH = 1800, 900, 300       # seconds
MIN_R = 0.5                                # a window whose beat series do not correlate says nothing
MIN_WINDOWS = 4                            # fewer than this and the night cannot show its own drift
FEW = "no window where the two beat series agree"
HELD = "drift held at the cohort median"
COLUMNS = ["night", "psg_device", "ecg_channel", "e4_clock0", "scorer1_row0", "duration_s", "windows",
           "windows_used", "lag0_s", "drift_s_per_h", "resid_mad_s", "r_median", "fetched_mb", "note"]


def theil_sen(x, y):
    """Slope as the median of pairwise slopes, intercept as the median residual: a few wild windows
    (a restless hour where the wristband saw no clean beats) cannot bend the line."""
    i, j = np.triu_indices(len(x), 1)
    ok = x[j] != x[i]
    slope = float(np.median((y[j] - y[i])[ok] / (x[j] - x[i])[ok]))
    return slope, float(np.median(y - slope * x))


def ecg_channels(pq, devices):
    """Every PSG ECG channel as (leaf, channel, device row, rate), likeliest first: the two PSG systems
    in the collection name theirs "ECG 2" and "ECG1"."""
    names = [k[len("SignalData."):-len(".list.element")] for k in pq.leaves
             if k.startswith("SignalData.ECG") and k.endswith(".list.element")]
    out = []
    for ch in sorted(names, key=lambda c: (["ECG 2", "ECG1"].index(c) if c in ("ECG 2", "ECG1") else 9, c)):
        rate = pq.rows("SamplingRate." + ch)
        rows = [i for i, v in enumerate(rate) if len(v) and devices[i] not in ("Zmax Lite", "Empatica E4",
                                                                              "ActivPAL")]
        if rows:
            out.append(("SignalData.%s.list.element" % ch, ch, rows[0], float(rate[rows[0]][0])))
    return out


def plausible(beats):
    """A channel that is noise yields "beats" a third of a second apart; a heart does not."""
    rr = np.diff(beats)
    return len(rr) > 1000 and float(np.mean((rr > 0.4) & (rr < 1.8))) >= 0.8


def fetch(sid, src, out_dir, sync):
    """Everything that needs the synchronised file: the table facts and the ECG beat times."""
    raw = os.path.join(src, "Wearanize+_OA_raw_v1.1")
    local = os.path.join(src, PLUGNPLAY, sid + ".parquet")
    remote = None
    if os.path.exists(local):
        pq = Parquet(local)
    else:
        remote = RangeFile("%s/%s/%s.parquet" % (BASE, PLUGNPLAY.replace("+", "%2B"), sid))
        pq = Parquet(remote.url, opener=lambda: remote)
    row = dict.fromkeys(COLUMNS, "")
    row["night"] = sid
    devices = pq.device_rows()
    if "Empatica E4" not in devices:
        row["note"] = "no wristband in the synchronised file"
        return row
    e4 = devices.index("Empatica E4")

    # Which raw wristband second is their time 0? Their heart-rate channel is the raw one, cut.
    z = zipfile.ZipFile(os.path.join(raw, "1.Raw_data", sid, "3.Empatica", sid + "_Empatica_data.zip"))
    theirs = pq.rows("SignalData.HR.list.element")[e4].astype(np.float64)
    _, _, mine = R.e4_member(z, "HR.csv")
    s = sync[sid]
    expect = s["Emp_start_sec"] - 1 - (s["Zmax_start_sec"] - 1) % 30

    def mismatch(k):                                        # k < 0: the wristband started after their time 0
        a, b = max(0, -k), max(0, k)
        n = min(len(theirs) - a, len(mine) - b)
        return 1.0 if n < 600 else float(np.mean(np.abs(mine[b:b + n] - theirs[a:a + n]) > 0.006))
    clock0 = expect
    if mismatch(expect) > 0.001:
        clock0 = min(range(expect - 600, expect + 600), key=mismatch)
        if mismatch(clock0) > 0.001:
            row["note"] = "their wristband channel was not found in the raw file"
            return row
        row["note"] = "their wristband cut is %+d s from the sheet's; " % (clock0 - expect)
    row["e4_clock0"] = clock0

    # Which raw scorer-1 row is their first epoch?
    their_scores = [x for x in pq.rows("SleepScores.ManualScores1.list.element") if len(x)][0].astype(int)
    my_scores = np.array([c for c, _ in R.wearanize_scores(
        os.path.join(raw, "2.Sleep_scores", "1.PSG_manual_scores_scorer1", sid + ".txt"))])
    exact = [k for k in range(0, len(my_scores) - 100)
             if np.array_equal(my_scores[k:k + len(their_scores)], their_scores[:len(my_scores) - k])]
    if len(exact) != 1:
        row["note"] += "their scorer-1 vector matches raw rows at %s; " % exact
    row["scorer1_row0"] = exact[0] if exact else ""

    beats = None
    for leaf, channel, prow, rate in ecg_channels(pq, devices):
        ecg = pq.rows(leaf)[prow]
        got = C.r_peaks(ecg, rate)
        row["psg_device"], row["ecg_channel"], row["duration_s"] = devices[prow], channel, int(len(ecg) / rate)
        del ecg
        if plausible(got):
            beats = got
            break
        row["note"] += "%s is not a heartbeat; " % channel
    if remote is not None:
        row["fetched_mb"] = "%.1f" % (remote.fetched / 1e6)
    if beats is None:
        row["note"] += "no usable ECG channel"
        return row
    os.makedirs(os.path.join(out_dir, "ecg_beats"), exist_ok=True)
    np.save(os.path.join(out_dir, "ecg_beats", sid + ".npy"), beats.astype(np.float32))
    return row


def windows(sid, src, out_dir, row):
    """(window midpoints, lags, correlations): the wristband's beats slid against the ECG's."""
    z = zipfile.ZipFile(os.path.join(src, "Wearanize+_OA_raw_v1.1", "1.Raw_data", sid, "3.Empatica",
                                     sid + "_Empatica_data.zip"))
    _, _, ibi = R.e4_member(z, "IBI.csv")
    beats = np.load(os.path.join(out_dir, "ecg_beats", sid + ".npy")).astype(np.float64)
    dur = int(row["duration_s"])
    a = C.second_hr(beats[1:], np.diff(beats), dur)
    b = C.second_hr(ibi[:, 0] - int(row["e4_clock0"]), ibi[:, 1], dur)
    mids, lags, rs = [], [], []
    for lo in range(0, max(1, dur - WINDOW + 1), STEP):
        r, lag = C.best_lag(a, b, lo, lo + WINDOW, reach=REACH)
        if not np.isnan(r):
            mids.append(lo + WINDOW / 2)
            lags.append(lag)
            rs.append(r)
    return np.array(mids), np.array(lags, dtype=float), np.array(rs)


def fit(row, mids, lags, rs, held_drift=None):
    """Offset and drift from the window lags. With `held_drift` (seconds per second) only the offset is
    fitted: the fallback for a night whose wristband saw too few beats to show its own drift."""
    good = (rs >= MIN_R) & (np.abs(lags) < REACH - 2)
    row.update(windows=len(rs), windows_used=int(good.sum()), lag0_s="", drift_s_per_h="", resid_mad_s="",
               r_median="")
    need = MIN_WINDOWS if held_drift is None else 1
    if good.sum() < need:
        return False
    if held_drift is None:
        slope, icpt = theil_sen(mids[good], lags[good])
    else:
        slope, icpt = held_drift, float(np.median(lags[good] - held_drift * mids[good]))
    resid = lags[good] - (icpt + slope * mids[good])
    row.update(lag0_s="%.2f" % icpt, drift_s_per_h="%.3f" % (slope * 3600),
               resid_mad_s="%.2f" % float(np.median(np.abs(resid))), r_median="%.3f" % float(np.median(rs[good])))
    return True


def report(row):
    print("%s  %s %s  lag %s s at their time 0, drift %s s/h, %s of %s windows, spread %s s, r %s  %s" % (
        row["night"], row["psg_device"], row["ecg_channel"], row["lag0_s"], row["drift_s_per_h"],
        row["windows_used"], row["windows"], row["resid_mad_s"], row["r_median"], row["note"]), flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", default="~/datasets/wearanize-oa")
    ap.add_argument("--out", default="~/datasets/reduced")
    ap.add_argument("--only", help="comma-separated nights; the others keep their rows")
    ap.add_argument("--force", action="store_true", help="fetch again even where beats are already kept")
    a = ap.parse_args()
    src = os.path.expanduser(a.src)
    out_dir = os.path.join(os.path.expanduser(a.out), "wearanize")
    sync = R.wearanize_sync(os.path.join(src, "Wearanize+_OA_raw_v1.1"))
    nights = [n["night"] for n in csv.DictReader(open(os.path.join(out_dir, "nights.csv")))]
    if a.only:
        nights = [n for n in nights if n in a.only.split(",")]
    table = os.path.join(out_dir, "ecg_sync.csv")
    done = {r["night"]: r for r in csv.DictReader(open(table))} if os.path.exists(table) else {}

    def save():                                          # after every night, so an interrupted run resumes
        with open(table, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=COLUMNS)
            w.writeheader()
            for k in sorted(done):
                w.writerow(done[k])

    held = []
    for sid in nights:
        row = done.get(sid)
        kept = os.path.exists(os.path.join(out_dir, "ecg_beats", sid + ".npy"))
        if a.force or row is None or not kept or row["e4_clock0"] == "" or row["scorer1_row0"] == "":
            try:
                row = fetch(sid, src, out_dir, sync)
            except Exception as e:                       # one bad night must not cost the other ninety
                row = dict.fromkeys(COLUMNS, "")
                row.update(night=sid, note="failed: %s" % e)
            done[sid] = row
            save()
            kept = os.path.exists(os.path.join(out_dir, "ecg_beats", sid + ".npy"))
        if not kept or row["e4_clock0"] == "" or row["scorer1_row0"] == "" or "no usable ECG" in row["note"]:
            report(row)
            continue
        row["note"] = row["note"].replace(FEW, "").replace(HELD, "")
        w = windows(sid, src, out_dir, row)
        if not fit(row, *w):
            held.append((sid, w))
            continue
        report(row)
        save()

    # A wristband that saw too few clean beats cannot show its own drift, but the drift is a property of
    # the device, near-identical across the cohort: hold it at the cohort median and fit the offset alone.
    drifts = [float(r["drift_s_per_h"]) for r in done.values() if r["drift_s_per_h"] and HELD not in r["note"]]
    for sid, w in held:
        row = done[sid]
        if drifts and fit(row, *w, held_drift=float(np.median(drifts)) / 3600.0):
            row["note"] += HELD
        else:
            row["note"] += FEW
        report(row)
    save()


if __name__ == "__main__":
    sys.exit(main())
