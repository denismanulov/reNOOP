#!/usr/bin/env python3
"""wearanize_psg_cut.py — at which second of the raw PSG recording does the authors' cut one start?

`wearanize_ecg_sync.py` puts the wristband on the clock of the authors' synchronised (cut) PSG recording.
The scores were made on the RAW recording, so the last link is where in the raw recording that cut begins.
The authors pair their first epoch with score row ceil(PSG_start_sec / 30), which implies the cut begins
on raw second 30 * that row. This is the check of that implication, and it holds: on every night tried,
including those where scorer 1's rows looked furthest out of place, the cut begins on that second to the
millisecond. (Scorer 1's rows are out of place for another reason: `wearanize_scorer1_grid.py`.)

The cut point is not a matter of estimation. The same ECG is in both recordings, so the rhythm of the
first beats of the cut recording (already kept by `wearanize_ecg_sync.py`) occurs exactly once near the
start of the raw one. This reads only the head of a raw PSG archive by HTTP byte range, up to the
expected cut plus a margin, detects its beats, and takes the cut second as the one shift at which the two
beat trains coincide. A heartbeat rhythm two minutes long does not repeat, so the match is unambiguous;
the count of coinciding beats and the best wrong shift are written beside it as the evidence.

`reduce.py` does not read this table: it uses 30 * scorer1_row0, which is what this confirms. Run it on
the nights you want confirmed (a few MB each); without --only it walks every night.

Kept on disk: `psg_cut.csv`, one row per night. No signal is stored.

USAGE   wearanize_psg_cut.py [--src ~/datasets/wearanize-oa] [--out ~/datasets/reduced]
                             [--only Sub005s1,Sub124s1] [--force]
"""
import argparse
import csv
import os
import struct
import sys
import zlib

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import reduce as R  # noqa: E402
import check_wearanize_sync as C  # noqa: E402
from parquet_min import Parquet, RangeFile  # noqa: E402
from wearanize_ecg_sync import BASE, PLUGNPLAY  # noqa: E402

RAW = "Wearanize+_OA_raw_v1.1"
ARCHIVE = {"SomnoScreen Plus": "1.Somno/%s_Somno_data.zip", "Mentalab Explore Pro": "1.Mentalab/%s_Mentalab_data.zip"}
MARGIN = 150                # seconds read past the sheet's cut point
MATCH_WINDOW = 120          # seconds of the cut recording whose beats are looked for
MIN_BEATS = 30              # coinciding beats below which the match is not believed
TOLERANCE = 0.03            # seconds: two detections of one beat
BUDGET_MB = 650
COLUMNS = ["night", "psg_cut_s", "beats_matched", "beats_sought", "best_wrong_shift", "raw_seconds_read",
           "fetched_mb", "note"]


class MemberHead:
    """The beginning of one member of a remote ZIP, inflated a piece at a time."""

    def __init__(self, url):
        self.rf = RangeFile(url, tail=65536)
        t = self.rf.tail
        i = t.rfind(b"PK\x05\x06")
        count, cd_size, cd_off = struct.unpack_from("<HII", t, i + 10)
        if cd_off >= self.rf.tail_start:
            cd = t[cd_off - self.rf.tail_start:cd_off - self.rf.tail_start + cd_size]
        else:
            self.rf.seek(cd_off)
            cd = self.rf.read(cd_size)
        members, p = [], 0
        for _ in range(count):
            method = struct.unpack_from("<H", cd, p + 10)[0]
            csize, usize, fl, el, cl = struct.unpack_from("<IIHHH", cd, p + 20)
            off = struct.unpack_from("<I", cd, p + 42)[0]
            members.append((cd[p + 46:p + 46 + fl].decode("utf-8", "replace"), method, csize, usize, off))
            p += 46 + fl + el + cl
        # the recording is the first and by far the largest member of each archive
        self.name, self.method, self.csize, _, off = min(members, key=lambda m: m[4])
        self.rf.seek(off)
        local = self.rf.read(30)
        fl, el = struct.unpack_from("<HH", local, 26)
        self.next = off + 30 + fl + el
        self.end = self.rf.size if self.csize == 0xFFFFFFFF else self.next + self.csize
        self.inflater = zlib.decompressobj(-15) if self.method == 8 else None
        self.data = bytearray()

    def grow(self, until, ratio=0.85):
        """Inflate until `until` bytes are in hand (or the member ends)."""
        while len(self.data) < until and self.next < self.end:
            n = min(self.end - self.next, max(262144, int((until - len(self.data)) * ratio)))
            self.rf.seek(self.next)
            raw = self.rf.read(n)
            self.next += n
            self.data += self.inflater.decompress(raw) if self.inflater else raw
        return len(self.data) >= until


def edf_ecg(head, seconds):
    """ECG samples of the first `seconds` of an EDF, and their rate."""
    head.grow(256)
    d = head.data
    header_bytes, record_s, ns = int(d[184:192]), float(d[244:252]), int(d[252:256])
    head.grow(header_bytes)
    h = bytes(head.data[256:256 + ns * 256])
    labels = [h[i * 16:(i + 1) * 16].decode("latin-1").strip() for i in range(ns)]
    per = [int(h[ns * 216 + i * 8:ns * 216 + (i + 1) * 8]) for i in range(ns)]
    ecg = next(i for i, name in enumerate(labels) if name.upper().startswith("ECG"))
    record_bytes = 2 * sum(per)
    want = int(np.ceil(seconds / record_s))
    head.grow(header_bytes + want * record_bytes)
    got = (len(head.data) - header_bytes) // record_bytes
    rec = np.frombuffer(bytes(head.data[header_bytes:header_bytes + got * record_bytes]), dtype="<i2")
    rec = rec.reshape(got, record_bytes // 2)
    a = sum(per[:ecg])
    return rec[:, a:a + per[ecg]].reshape(-1).astype(np.float64), per[ecg] / record_s, labels[ecg]


def csv_ecg(head, seconds, column, rate):
    """One column of the first `seconds` of a headerless-rate CSV export: row i is sample i."""
    lines = int(seconds * rate) + 2
    while True:
        text = bytes(head.data)
        if text.count(b"\n") > lines or not head.grow(len(head.data) + 4 * 1024 * 1024):
            break
    rows = bytes(head.data).split(b"\n")[1:lines]
    return np.array([float(r.split(b",")[column]) for r in rows if r.count(b",") >= column]), rate


def match(raw_beats, cut_beats):
    """(shift, coinciding beats, beats sought, coinciding beats at the best shift elsewhere)."""
    sought = cut_beats[(cut_beats >= 0) & (cut_beats < MATCH_WINDOW)]
    if len(sought) < MIN_BEATS:                           # a padded start: take the first beats there are
        sought = cut_beats[:120]
    d = (raw_beats[:, None] - sought[None, :]).ravel()
    bins = np.round(d / TOLERANCE).astype(np.int64)
    vals, counts = np.unique(bins, return_counts=True)
    top = vals[counts.argmax()] * TOLERANCE
    near = d[np.abs(d - top) <= TOLERANCE]
    shift = float(np.median(near))
    hits = int((np.abs(d - shift) <= TOLERANCE).sum())
    elsewhere = counts[np.abs(vals * TOLERANCE - shift) > 2.0]
    return shift, hits, len(sought), int(elsewhere.max()) if len(elsewhere) else 0


def one_night(sid, fit, sheet, src, out_dir):
    row = dict.fromkeys(COLUMNS, "")
    row["night"] = sid
    device = fit["psg_device"]
    head = MemberHead("%s/%s/1.Raw_data/%s/%s" % (BASE, RAW.replace("+", "%2B"), sid, ARCHIVE[device] % sid))
    cut_beats = np.load(os.path.join(out_dir, "ecg_beats", sid + ".npy")).astype(np.float64)
    seconds = max(sheet["PSG_start_sec"], 0) + MARGIN
    for attempt in range(3):
        if head.name.lower().endswith(".edf"):
            ecg, rate, _ = edf_ecg(head, seconds)
        else:
            # A CSV export has no channel names: the column is the channel's place in the device's own
            # list, which the synchronised file records.
            local = os.path.join(src, PLUGNPLAY, sid + ".parquet")
            if os.path.exists(local):
                pq = Parquet(local)
            else:
                remote = RangeFile("%s/%s/%s.parquet" % (BASE, PLUGNPLAY.replace("+", "%2B"), sid))
                pq = Parquet(remote.url, opener=lambda: remote)
            devices = pq.device_rows()
            names = list(pq.rows("SignalLabel.list.element")[devices.index(device)])
            rate = float(pq.rows("SamplingRate." + fit["ecg_channel"])[devices.index(device)][0])
            ecg, rate = csv_ecg(head, seconds, 1 + names.index(fit["ecg_channel"]), rate)
        raw_beats = C.r_peaks(ecg, rate)
        shift, hits, sought, wrong = match(raw_beats, cut_beats)
        row.update(psg_cut_s="%.3f" % shift, beats_matched=hits, beats_sought=sought, best_wrong_shift=wrong,
                   raw_seconds_read=int(len(ecg) / rate), fetched_mb="%.1f" % (head.rf.fetched / 1e6))
        if hits >= MIN_BEATS and hits >= 3 * wrong:
            return row
        seconds += 240                                    # the cut lies further in than the sheet says
    row["note"] = "the cut recording's first beats were not found in the raw recording's head"
    row["psg_cut_s"] = ""
    return row


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", default="~/datasets/wearanize-oa")
    ap.add_argument("--out", default="~/datasets/reduced")
    ap.add_argument("--only", help="comma-separated nights; the others keep their rows")
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    src = os.path.expanduser(a.src)
    out_dir = os.path.join(os.path.expanduser(a.out), "wearanize")
    sync = R.wearanize_sync(os.path.join(src, RAW))
    fits = {r["night"]: r for r in csv.DictReader(open(os.path.join(out_dir, "ecg_sync.csv"))) if r["lag0_s"]}
    table = os.path.join(out_dir, "psg_cut.csv")
    done = {r["night"]: r for r in csv.DictReader(open(table))} if os.path.exists(table) else {}
    spent = sum(float(r["fetched_mb"] or 0) for r in done.values())
    for sid in sorted(fits):
        if a.only and sid not in a.only.split(","):
            continue
        if sid in done and done[sid]["psg_cut_s"] and not a.force:
            continue
        if spent > BUDGET_MB:
            sys.exit("stopping: %.0f MB fetched, the agreed ceiling is %d MB" % (spent, BUDGET_MB))
        try:
            row = one_night(sid, fits[sid], sync[sid], src, out_dir)
        except Exception as e:                            # one bad night must not cost the other ninety
            row = dict.fromkeys(COLUMNS, "")
            row.update(night=sid, note="failed: %s" % e)
        done[sid] = row
        spent += float(row["fetched_mb"] or 0)
        row0 = int(fits[sid]["scorer1_row0"])
        print("%s  cut at raw second %s (the authors' pairing implies %d): %s of %s beats coincide, %s at the"
              " best other shift; read %s s, %s MB  %s" % (
                  sid, row["psg_cut_s"], 30 * row0, row["beats_matched"], row["beats_sought"],
                  row["best_wrong_shift"], row["raw_seconds_read"], row["fetched_mb"], row["note"]), flush=True)
        with open(table, "w", newline="") as f:           # after every night, so an interrupted run resumes
            w = csv.DictWriter(f, fieldnames=COLUMNS)
            w.writeheader()
            for k in sorted(done):
                w.writerow(done[k])
    print("fetched in all: %.0f MB" % spent)


if __name__ == "__main__":
    sys.exit(main())
