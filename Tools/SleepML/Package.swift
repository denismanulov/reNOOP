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
//              training table. The training script fits to that file and reimplements nothing.
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
