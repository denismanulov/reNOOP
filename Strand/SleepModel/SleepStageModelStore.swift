import CoreML
import Foundation
import StrandAnalytics

/// The learned sleep-stage model as the app ships it: two boosted-tree classifiers compiled into the
/// bundle (`SleepStageFirst`, `SleepStageSecond`), installed into `SleepStageLearned` so every place a
/// night is staged asks them first. How they were fit and what they score is in `Tools/SleepML`; what
/// they are given and how their answer becomes a hypnogram is in `StrandAnalytics`. This file only loads
/// them and runs them.
///
/// Trained on Wearanize+ OA (Radboud University, CC BY 4.0) and sleep-accel (Walch et al., PhysioNet,
/// ODC-By 1.0); the weights carry both attributions.
enum SleepStageModelStore {

    /// Install the bundled models, once per process. Called before a night is scored or re-staged; a
    /// second call does nothing. A bundle without the models (or models that do not load) installs
    /// nothing, and every night is then staged by the recipe as before.
    ///
    /// Once per process and before the first pass is also why the model is not a field of the engine's
    /// day-cache signature (`IntelligenceEngine.dayCacheConfigFields`, a list shared with the Kotlin
    /// twin): that cache lives in memory, so it never holds a scan another model staged.
    static func ensureInstalled() { _ = installed }

    private static let installed: Bool = {
        guard let model = load(from: .main) else {
            NSLog("SleepStageModelStore: the stage models are not in the bundle or did not load; staging with the recipe")
            return false
        }
        SleepStageLearned.model = model
        return true
    }()

    /// The pair in `bundle`, with the decoder settings stored in the second model's metadata.
    static func load(from bundle: Bundle) -> SleepStageModel? {
        guard let firstURL = bundle.url(forResource: "SleepStageFirst", withExtension: "mlmodelc"),
              let secondURL = bundle.url(forResource: "SleepStageSecond", withExtension: "mlmodelc") else { return nil }
        // Trees gain nothing from a GPU or the neural engine, and the CPU path answers the same on every device.
        let config = MLModelConfiguration()
        config.computeUnits = .cpuOnly
        guard let first = try? MLModel(contentsOf: firstURL, configuration: config),
              let second = try? MLModel(contentsOf: secondURL, configuration: config) else { return nil }
        let meta = second.modelDescription.metadata[.creatorDefinedKey] as? [String: String] ?? [:]
        let prior = meta["classPrior"].map { $0.split(separator: ",").compactMap { Double($0) } }
        guard let weight = meta["priorWeight"].flatMap({ Double($0) }),
              let smoothing = meta["smoothing"].flatMap({ Double($0) }),
              let prior, prior.count == SleepStageDecoder.stages.count else { return nil }
        let one = Runner(first, names: SleepStageContext.firstNames)
        let two = Runner(second, names: SleepStageContext.secondNames)
        guard one.takesItsColumns, two.takesItsColumns else { return nil }
        return SleepStageModel(
            version: fingerprint([firstURL, secondURL]),
            prior: prior, weight: weight, smoothing: smoothing,
            first: { one.probabilities($0) }, second: { two.probabilities($0) })
    }

    /// Names a pair by its bytes, so a rebuilt app with refit models stages every stored night again
    /// and one with the same models does not. FNV-1a over each compiled model's files in name order.
    static func fingerprint(_ compiled: [URL]) -> String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        for url in compiled {
            let files = (FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.isRegularFileKey])?
                .compactMap { $0 as? URL } ?? [])
                .filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
                .sorted { $0.path < $1.path }
            for file in files {
                guard let data = try? Data(contentsOf: file) else { continue }
                for byte in data { hash = (hash ^ UInt64(byte)) &* 0x0000_0100_0000_01b3 }
            }
        }
        return String(hash, radix: 16)
    }

    /// One loaded model as a function from rows to probabilities. Predictions are serialised: the
    /// scoring loop and an edit's re-stage can ask at the same moment.
    private final class Runner: @unchecked Sendable {
        private let model: MLModel
        private let names: [String]
        private let output: String
        private let lock = NSLock()

        init(_ model: MLModel, names: [String]) {
            self.model = model
            self.names = names
            output = model.modelDescription.predictedProbabilitiesName ?? "stageProbability"
        }

        /// The model was fit on exactly the columns this build computes: a model from another feature
        /// set would otherwise be handed the wrong numbers under the right names, or none at all.
        var takesItsColumns: Bool {
            Set(model.modelDescription.inputDescriptionsByName.keys) == Set(names)
        }

        func probabilities(_ rows: [[Double]]) -> [[Double]]? {
            lock.lock()
            defer { lock.unlock() }
            guard rows.allSatisfy({ $0.count == names.count }) else { return nil }
            let providers: [MLFeatureProvider]? = try? rows.map { row in
                try MLDictionaryFeatureProvider(
                    dictionary: Dictionary(uniqueKeysWithValues: zip(names, row).map { ($0, $1) }))
            }
            guard let providers,
                  let out = try? model.predictions(fromBatch: MLArrayBatchProvider(array: providers)),
                  out.count == rows.count else { return nil }
            var result: [[Double]] = []
            result.reserveCapacity(rows.count)
            for i in 0..<out.count {
                guard let d = out.features(at: i).featureValue(for: output)?.dictionaryValue else { return nil }
                result.append(SleepStageDecoder.stages.map { d[AnyHashable($0)]?.doubleValue ?? 0 })
            }
            return result
        }
    }
}
