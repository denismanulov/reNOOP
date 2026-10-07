import Foundation
import StrandAnalytics

/// Persistence for the learned WHOOP 4.0 ticks-per-step factor (`StepCalibration`) and the one place the
/// app resolves "which divisor does this day use".
///
/// The feature is an Experimental opt-in, default off. While it is off every day uses the manual Step
/// calibration divisor, exactly as before, whatever has been learned. State lives in user defaults: it is
/// small, re-learned within days if lost, and deliberately not part of the `.noopbak` contract.
enum StepCalibrationStore {
    static let enabledKey = "stepAutoCalibration.enabled"
    static let stateKey = "stepAutoCalibration.state"

    static func isEnabled(_ defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: enabledKey)
    }

    static func load(_ defaults: UserDefaults = .standard) -> StepCalibration.State {
        guard let data = defaults.data(forKey: stateKey),
              let state = try? JSONDecoder().decode(StepCalibration.State.self, from: data) else {
            return StepCalibration.State()
        }
        return state
    }

    static func save(_ state: StepCalibration.State, _ defaults: UserDefaults = .standard) {
        guard let data = try? JSONEncoder().encode(state) else { return }
        defaults.set(data, forKey: stateKey)
    }

    /// The divisors of one scoring pass, resolved once so every reader of the pass sees the same values.
    struct Snapshot: Sendable {
        let state: StepCalibration.State?
        let manual: Double

        /// Ticks per step for `day` (`yyyy-MM-dd`, local).
        func factor(day: String) -> Double {
            guard let state else { return manual }
            return StepCalibration.factor(state, day: day, manual: manual)
        }
    }

    /// `today` moves the running day forward first, so a day that ended without a measurement is frozen
    /// at the factor it ended with instead of following later learning.
    static func snapshot(manual: Double, today: String,
                         defaults: UserDefaults = .standard) -> Snapshot {
        guard isEnabled(defaults) else { return Snapshot(state: nil, manual: manual) }
        let stored = load(defaults)
        let advanced = StepCalibration.advanced(stored, to: today)
        if advanced != stored { save(advanced, defaults) }
        return Snapshot(state: advanced, manual: manual)
    }
}
