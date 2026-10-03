# Session brief: a sleep-stage model trained with Create ML, shipped as Core ML

Paste everything below the line into a new session opened in this repository.

---

Reply to me in Russian, briefly. This is my personal fork (reNOOP): work on `main`, no branches or PRs,
commit only when I ask, never push without asking. The Android twin is out of scope (no JDK here).

## Goal

Train a small on-device sleep-stage classifier (wake / light / deep / REM per 30 s epoch) on open
datasets with Apple's tooling, **Create ML producing a Core ML `.mlmodel`**, and run it in the iOS app
behind a default-off experimental toggle, in shadow beside the current stager, until it is shown to be
better. It must not become the default in this session.

## Why: what is wrong today, measured on my own 50 nights

The current stager is hand-set, nothing is fit to data. Detection of the night window is
`SleepStager.detectSleep` (V1); staging inside it is `SleepStagerV2.stageSession`
(`Packages/StrandAnalytics/Sources/StrandAnalytics/SleepStagerV2.swift`), chosen at
`Strand/Data/Repository.swift:1732`. Against human-scored PSG (`Tools/SleepPSG`, sleep-accel, n = 31)
it scores 4-class kappa about 0.37. On my nights (copy of the phone database, see Data):

- deep starts a median 6 min after the first sleep epoch, within 10 min on 34 of 50 nights. I am
  moving with HR 60-90 until about 5 min before the window, so I was not asleep earlier. Cause: no
  notion of falling asleep; a still wrist with a low flat pulse is scored deep, and my resting pulse
  lying down (46-48) is already the night's floor. REM has a 60 min latency guard, deep has none.
- the first epoch of the window is already sleep on 33 of 51 nights (zero sleep latency).
- about 13 awakenings a night, median 1.5 min each.
- 30 s "light" slivers, REM -> wake -> one light epoch -> REM, about 3 a night: the transition matrix
  forbids wake -> REM.
- stage shares over all nights: wake 6.9 %, light 43.6 %, deep 19.9 %, REM 29.6 %. Latest night:
  light 32 %, deep 28 %, REM 34 %.
- more than 10 min of deep in the last third of the night on 12 of 50 nights.

These are the baseline figures the model's output on my nights must be compared with. There is no
ground truth for my nights; they are a sanity check, never a training target.

An option I have not decided on: patch two of these in the recipe itself (a deep-latency guard, and
allowing wake -> REM). It was left open because `SleepStagerV2.swift` is upstream's file and edits
there conflict on every upstream sync. Do not do it unless I say so.

## Data (all outside the repository, never commit any of it)

Everything is under `~/datasets`. The two training datasets, Wearanize+ OA and sleep-accel, are on
disk, complete and checksum-verified (`Tools/SleepML/fetch_open.sh wearanize|sleepaccel` re-verifies
them). Together they are about 130 healthy people, all with human-scored PSG.

1. **BIDSleep is not used.** It was the planned third dataset (47 healthy adults, 253 nights, Apple
   Watch HR + accelerometer, EEG-headband labels), but PhysioNet serves it at about 100 kB/s to this
   network and it is not on the Amazon mirror, so sleep-accel was downloaded in its place.
   `~/datasets/bidsleep/` holds only a fragment of an abandoned download: ignore it, and do not
   start that download again unless I ask.
2. **Wearanize+ OA v1.1**, `~/datasets/wearanize-oa/`, wristband part only. 130 healthy adults aged
   18-39, one night at home, Empatica E4 wristband (BVP 64 Hz, inter-beat intervals, HR 1 Hz, ACC 32 Hz,
   skin temperature 4 Hz) beside full PSG scored by two experts. Downloaded:
   `Wearanize+_OA_raw_v1.1/1.Raw_data/SubNNNs1/3.Empatica/*.zip` (121 participants),
   `2.Sleep_scores/{1.PSG_manual_scores_scorer1 (103), 2.PSG_manual_scores_scorer2 (96),
   3.PSG_autoscores_U-Sleep_v2.0}` (30 s epochs; -1 artifact, 0 wake, 1 N1, 2 N2, 3 N3, 4 REM; second
   column is an arousal flag), `3.Manual_synchronization/Manual_sync_zmax_psg_emp_actpal.xlsx`,
   `4.Demographic_info/`, and the READMEs (read them, they define the sync columns: 0/0 means the
   device is missing, -999 means it could not be synchronised, seconds count from 1). Also three of
   the authors' synchronised files in `Wearanize+_OA_PlugNPlay_Parquet_v1.1/` (about 350 MB each,
   channels named `[device]_[channel]`, scores as `PSG_Manual_scor1` at 1/30 Hz): use them to verify
   that the alignment derived from the sync sheet matches the authors' before trusting it for
   everyone. Licence CC BY 4.0; the authors additionally ask for research use only and no
   re-identification, and for the acknowledgement quoted in `Tools/SleepML/fetch_open.sh`.
3. **sleep-accel 1.0.0**, `~/datasets/sleep-accel` (2.4 GB unpacked: `labels/`, `heart_rate/`,
   `motion/`, `steps/`, 31 files each), complete and checksum-verified. 31 healthy people, Apple
   Watch HR + accelerometer, human-scored PSG, no beat intervals. `Tools/SleepPSG` already has its
   loader and the shipped-stager benchmark, and it ran on this copy on 2026-10-03
   (`swift run -c release sleeppsg --dataset ~/datasets/sleep-accel --section baseline`): 26 773
   epochs, accuracy 61.14 %, kappa 0.371 (per-subject mean 0.357); predicted against true share of
   the night, pooled: wake 4.15 / 9.07 %, light 54.33 / 55.19 %, deep 15.09 / 13.76 %, REM
   26.44 / 21.98 %; wake recall 0.308. Those are the baseline numbers to beat here. It came from
   PhysioNet's Amazon mirror (`physionet-open.s3.amazonaws.com`), which is fast; BIDSleep is not on
   that mirror. Licence ODC-By 1.0.
4. **DREAMT 2.2.0** (100 sleep-clinic patients, same E4 wristband). Restricted access; I have an
   account. Optional, as a hard-case test only. `Tools/SleepML/fetch_dreamt.sh probe` exists and has
   not been run; I type the password myself. Print aggregates only, never participant rows, and do
   not ship weights trained on it without reading its LICENSE.txt.
5. **My own nights**, `~/datasets/renoop-db/whoop-2026-10-03.sqlite`, a copy of the phone database.
   Open it read-only. 53 sessions, 2026-08-16 to 2026-10-03. `sleepSession` rows use deviceId
   `my-whoop-noop` and `stagesJSON` is an array of `{start,end,stage}`; the streams use deviceId
   `my-whoop`: `hrSample(ts,bpm)` at 1 Hz, `gravitySample(ts,x,y,z)` at 1 Hz, `rrInterval(ts,rrMs,
   srcChannel)`. Some nights store two R-R channels (8 = strap history, 10 = live BLE): score channel
   8 only, as the app's read path does, or the beat train is doubled. Replaying
   `SleepStagerV2.stageSession` over these streams reproduces the stored hypnogram exactly, which is
   the check that a replay harness is wired correctly.

Machine: Apple M1, 8 GB RAM, about 11 GB of free disk. Xcode 26.5, Swift
6.3. Python 3.12 has numpy, scipy and scikit-learn only: no pandas, pyarrow, openpyxl, coremltools
or torch. Ask me before installing anything.

## Rules that come from this project's history

1. **Features are computed by Swift, by the code that ships.** Dump per-epoch features to CSV from a
   Swift tool and train on that file; do not reimplement features in Python. `SleepStagerV2.features`
   is internal: reach it with `@testable import` from a debug build, or put the model's feature
   extractor in a new file in `Packages/StrandAnalytics`. Do not edit `SleepStagerV2.swift`.
2. **One input shape for every source**: per-second heart rate, per-second mean acceleration in g
   (what `GravitySample` is on the strap), beat intervals where they exist. `Tools/SleepPSG` already
   collapses an Apple Watch accelerometer this way. Heart-rate cadence differs (about 5 s on Apple
   Watch, 1 s on the E4 and on my strap), so compute heart-rate features on one common cadence for
   every source, including my strap at inference, and normalise within the night as V2 does.
3. **Beat-interval features are optional inputs.** Only Wearanize+ OA (and DREAMT) and my strap have
   them. Stop the model learning "intervals present means Wearanize": blank them at random on part of
   those rows in training, or train a with-intervals and a without-intervals model. Check how Create
   ML's trees treat a missing value before relying on it.
4. **Split by person, never by epoch or by night.** Grouped folds by subject, plus leave-one-dataset-out.
   Create ML's automatic validation split is by row: always pass an explicit validation table.
5. **Kappa is not enough.** Report per-stage F1 and the stage-fraction bias as well, pooled and as the
   mean of per-subject shares (the two conventions in `Tools/SleepPSG/README.md`), plus deep latency
   and the number of wake bouts. Upstream PR #348 fitted the stager to DREAMT, raised kappa on three
   benchmarks, and was reverted within 48 hours because a healthy night went from 6 % to 23 % awake.
6. **The baseline is the shipped `SleepStagerV2`, scored on the same folds.**
7. **Labels**: wake; light = N1 + N2; deep = N3; REM. Drop unknown and artifact epochs. sleep-accel's
   own PSG labels as `Tools/SleepPSG` already maps them, Wearanize scorer 1 (scorer 2 gives an
   inter-scorer ceiling).
8. **Temporal context.** A tabular classifier sees one row per epoch, so context goes into the
   features (rolling windows, neighbouring epochs, minutes since the window started), and the model's
   class probabilities are then smoothed by Viterbi as V2 does today. The model replaces the hand-set
   emission, not the smoothing.

## Tooling, verified on this Mac

A command-line Swift program can train and export with the CreateML framework; this compiled, ran and
produced a model that `xcrun coremlcompiler compile` accepted:

```swift
import CreateML
import TabularData
var p = MLBoostedTreeClassifier.ModelParameters(validation: .dataFrame(valid), maxDepth: 4, maxIterations: 20)
p.randomSeed = 7
let m = try MLBoostedTreeClassifier(trainingData: train, targetColumn: "stage",
                                    featureColumns: ["a", "b"], parameters: p)
try m.write(to: url, metadata: MLModelMetadata(author: "...", shortDescription: "...", version: "0"))
```

Prefer that scripted route so training is reproducible; the Create ML app (Xcode, Open Developer
Tool, Create ML, "Tabular Classification") takes the same CSV if I want to look at it by hand.

Inference is Core ML. `Packages/` must stay free of Apple-only frameworks unless guarded with
`#if canImport(CoreML)`, and `project.yml` is the XcodeGen source of truth (`xcodegen generate` after
adding files, never commit `Strand.xcodeproj`). Use `MLModelConfiguration.computeUnits = .cpuOnly`.
The drop-in seam is the stager signature
`stageSession(start:end:grav:hr:rr:resp:) -> [StageSegment]`. Package tests do not compile the app
targets: build the `NOOPiOS` and `Strand` schemes yourself before saying it works. I install builds
through Sideloadly; you cannot sign.

## Order of work, stopping for me at each checkpoint

1. Confirm Wearanize+ OA and sleep-accel are intact (`Tools/SleepML/fetch_open.sh wearanize` and
   `sleepaccel` re-verify).
2. Reducers: each dataset to the common per-second streams plus 30 s labels, one night at a time.
   For Wearanize, prove the alignment on the three synchronised check files. Checkpoint: counts of
   usable people and nights per dataset, and the stage shares of the labels.
3. Swift feature dump, and the shipped-stager baseline on the same nights. Checkpoint: baseline table.
4. Create ML training with grouped folds. Checkpoint: model against baseline on every metric in rule
   5, per dataset and leave-one-dataset-out.
5. My 50 nights: the model beside the stored hypnograms, on the figures in "Why". Checkpoint.
6. Only then: Core ML integration behind a default-off experimental toggle, running in shadow.

Uncommitted files from the previous session, all in `Tools/SleepML/`: `fetch_open.sh`,
`ranged_fetch.py`, `fetch_dreamt.sh`, `dreamt_probe.py`, and this brief.
