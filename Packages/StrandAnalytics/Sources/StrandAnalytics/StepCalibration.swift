import Foundation

/// How many firmware step-counter ticks one real step costs, learned from short paired measurements and
/// kept per day.
///
/// WHY A LEARNED FACTOR. The WHOOP 4.0 firmware counter is not a 1:1 step count, and its excess is not
/// constant: on one strap and wearer (2026-10-03) it ran 1.14, 1.20, 1.23, 1.26 and 1.27 ticks per step
/// with the arm free and 1.49 with the strap hand in a pocket, at the same walking pace. Nothing in the
/// 1 Hz history separates those cases, so no fixed divisor is right. A measurement pairs the ticks the
/// counter added over some seconds with the steps `GaitCadence` read from the raw accelerometer over the
/// same seconds.
///
/// THE TWO LAYERS.
/// - A day's own measurements are summed (ticks and steps separately, so a long measurement weighs more
///   than a short one) and pulled toward the long-run factor with the weight of `priorSteps` steps. A day
///   spent with hands in pockets is therefore scaled mostly by that day's own evidence.
/// - The long-run factor is an exponential moving average over measured days, again of the two sums. It
///   starts from zero on both sums, so their ratio is an unbiased weighted mean from the first day on and
///   needs no seed value.
///
/// A finished day keeps the factor it ended with (`frozen`), so later learning never rewrites history.
/// Until anything has been measured, the caller's manual divisor applies unchanged.
///
/// THE RATIO BAND is what makes an unattended measurement safe to accept. `GaitCadence` fails in one way
/// that matters, by an octave, and an octave error moves the ratio by a factor of two: far outside a band
/// that is itself narrower than a factor of two. Such a measurement is refused, never averaged in.
public enum StepCalibration {

    /// Ticks per step a measurement must fall in to be accepted. Observed: 1.14 to 1.49.
    public static let ratioBand: ClosedRange<Double> = 1.0...1.7
    /// A measurement shorter than this many steps is too coarse: one tick either way is over 3%.
    public static let minimumSteps = 30.0
    /// Weight of one measured day in the long-run average.
    public static let dayAlpha = 0.2
    /// Weight of the long-run factor inside a day's own factor, in steps.
    public static let priorSteps = 120.0
    /// Finished days remembered. Older days fall back to the manual divisor if they are ever re-scored.
    public static let frozenDayLimit = 400

    public struct State: Equatable, Codable, Sendable {
        /// Exponential moving averages of measured ticks and steps per day; both zero until a measured
        /// day has finished.
        public var emaTicks = 0.0
        public var emaSteps = 0.0
        /// The day (`yyyy-MM-dd`) the running sums belong to.
        public var day: String?
        public var dayTicks = 0.0
        public var daySteps = 0.0
        /// Factor each finished day ended with.
        public var frozen: [String: Double] = [:]
        /// Measurements accepted so far, for diagnostics.
        public var accepted = 0

        public init() {}
    }

    public static func accepts(steps: Double, ticks: Double) -> Bool {
        guard steps >= minimumSteps, ticks > 0 else { return false }
        return ratioBand.contains(ticks / steps)
    }

    /// The long-run factor, or nil while no measured day has finished.
    public static func longRunFactor(_ state: State) -> Double? {
        state.emaSteps > 0 ? state.emaTicks / state.emaSteps : nil
    }

    /// Ticks per step to divide `day`'s counter total by.
    public static func factor(_ state: State, day: String, manual: Double) -> Double {
        if let frozen = state.frozen[day] { return frozen }
        guard day == state.day else { return longRunFactorIfLater(state, day: day) ?? manual }
        return runningDayFactor(state) ?? manual
    }

    /// Moves the running day forward to `day`, freezing the day it leaves and folding that day's sums
    /// into the long-run average. A `day` that is not later than the running one changes nothing.
    public static func advanced(_ state: State, to day: String) -> State {
        guard let current = state.day else {
            var next = state
            next.day = day
            return next
        }
        guard day > current else { return state }
        var next = state
        if let ended = runningDayFactor(state) { next.frozen[current] = ended }
        if state.daySteps > 0 {
            next.emaTicks = (1 - dayAlpha) * state.emaTicks + dayAlpha * state.dayTicks
            next.emaSteps = (1 - dayAlpha) * state.emaSteps + dayAlpha * state.daySteps
        }
        next.day = day
        next.dayTicks = 0
        next.daySteps = 0
        if next.frozen.count > frozenDayLimit {
            for stale in next.frozen.keys.sorted().prefix(next.frozen.count - frozenDayLimit) {
                next.frozen[stale] = nil
            }
        }
        return next
    }

    /// Adds one accepted measurement taken on `day`. A measurement for a day that has already been left
    /// (a burst just before midnight, paired after it) is dropped: its day is frozen.
    public static func recorded(_ state: State, day: String, steps: Double, ticks: Double) -> State {
        guard accepts(steps: steps, ticks: ticks) else { return state }
        var next = advanced(state, to: day)
        guard next.day == day else { return next }
        next.dayTicks += ticks
        next.daySteps += steps
        next.accepted += 1
        return next
    }

    /// The running day's factor: its own sums pulled toward the long-run factor. Nil when there is
    /// neither a measurement today nor a long-run factor.
    private static func runningDayFactor(_ state: State) -> Double? {
        let longRun = longRunFactor(state)
        guard state.daySteps > 0 else { return longRun }
        guard let longRun else { return state.dayTicks / state.daySteps }
        return (state.dayTicks + priorSteps * longRun) / (state.daySteps + priorSteps)
    }

    /// A day after the running one (the caller has not advanced yet) is scaled by the long-run factor
    /// as it would stand once the running day is folded in. An earlier unfrozen day has no factor here.
    private static func longRunFactorIfLater(_ state: State, day: String) -> Double? {
        guard let current = state.day, day > current else { return nil }
        return longRunFactor(advanced(state, to: day))
    }
}
