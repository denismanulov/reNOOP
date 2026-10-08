// swift-tools-version:5.9
import PackageDescription

// sleepml — the Swift half of the learned sleep-stage model's training loop.
//
// It reads the nights `reduce.py` wrote (per-second streams plus PSG stage labels, outside this
// repository), and does the two things that must be done by the code that ships:
//
//   baseline   stage every night with `SleepStagerV2.stageSession` and score it against the labels:
//              the numbers a learned model has to beat, on the same nights and the same window.
//   features   write the model's per-epoch inputs, from `StrandAnalytics.SleepStageFeatures`, as the
//              training table. Training fits to that file and reimplements nothing.
//   train      fit a boosted-tree classifier with Create ML, folds by person and leave-one-dataset-out,
//              and score it beside the shipped stager on every metric; optionally write the .mlmodel.
//   speed      time features, model and smoothing for the longest night, CPU only.
//   own        the model beside the hypnograms stored in a COPY of a wearer's database (opened
//              immutable): no ground truth there, a check of the night's shape on the wearer's hardware.
//
// No dataset is committed and none is read except through `--reduced`.
let package = Package(
    name: "sleepml",
    platforms: [.macOS(.v13)],
    dependencies: [
        .package(path: "../../Packages/StrandAnalytics"),
        .package(path: "../../Packages/WhoopProtocol"),
    ],
    targets: [
        .executableTarget(name: "sleepml", dependencies: ["StrandAnalytics", "WhoopProtocol"]),
    ]
)
