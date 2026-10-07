import Foundation
import WhoopProtocol

/// Wrap-aware step derivation from the strap's cumulative `step_motion_counter@57`, shared by the daily
/// total (`AnalyticsEngine.analyzeDay`) and any windowed total (a manual workout's `[start, end]`, #398).
///
/// `step_motion_counter@57` is a CUMULATIVE u16 motion counter: it climbs for both locomotion and some
/// non-step wrist motion, and wraps at 65536. On classed WHOOP 5/MG records, the increment ending at each
/// sample is counted only when the strap labels that sample walk (1) or run (2); still (0) and unknown are
/// rejected. A wholly unclassed legacy window retains the old counter-only estimate so pre-migration history
/// remains readable. The caller applies its per-user `stepTicksPerStep` calibration afterwards. The result is
/// still an estimate, not cloud/clinical parity.
///
/// The Kotlin twin `StepsCounter.stepsInWindow` matches except for the rate gate: it has not adopted the
/// last-moved time base or the eight-per-second limit, so it drops a buffered release and a doubled
/// record that this side keeps.
public enum StepsCounter {
    private static let locomotionActivityClasses: Set<Int> = [1, 2]

    static func hasActivityClasses(_ samples: [StepSample]) -> Bool {
        samples.contains { $0.activityClass != nil }
    }

    /// Kotlin twin: `StepsCounter.shouldCountDelta`.
    static func shouldCountDelta(activityClass: Int?, hasActivityClasses: Bool) -> Bool {
        !hasActivityClasses || activityClass.map(locomotionActivityClasses.contains) == true
    }

    /// Absolute reboot/wrap guard, independent from the per-second plausibility gate below.
    public static let maxStepDelta = 512

    /// Ticks one increment may add for each second since the previous sample. Eight, not the four a
    /// cadence alone would suggest, because a 1 Hz record can carry two seconds' worth of ticks: on a
    /// WHOOP 4.0 walk of 2026-10-03 a steady 2-3 per second was broken by four records of 5 or 6, and
    /// that counter runs at about 1.26 ticks per step, so a run sits near four per second before any
    /// such doubling.
    public static let maxTicksPerSecond = 8

    /// Seconds a pedometer may hold steps back before publishing them. The WHOOP 4.0 counter stays flat
    /// while it confirms a walk, then releases the buffered steps in one record (11 to 14 on the
    /// 2026-10-03 captures). At a slow 1.5 steps per second that is eight seconds.
    public static let confirmationWindowSeconds = 8

    /// Ticks credited toward a buffered release for each second the counter stayed flat. This is a
    /// sustained cadence, not a single-record burst, so it stays below `maxTicksPerSecond`.
    public static let releaseTicksPerSecond = 4

    /// Rate plausibility for one counter increment. Two allowances apply and the larger one wins:
    /// `maxTicksPerSecond` for each second since the previous sample, and `releaseTicksPerSecond` for
    /// each second since the counter last moved, capped at `confirmationWindowSeconds`. A buffered
    /// release after a flat run is therefore judged against the seconds it covers, while an increment
    /// right after another one gets the per-second limit alone.
    /// `lastMovedTs` is the timestamp of the latest sample whose counter differed from its predecessor,
    /// or of the window's first sample when none has yet.
    ///
    /// Kotlin twin: `StepsCounter.isPlausibleDelta`, which has not adopted `lastMovedTs`, still measures
    /// over the consecutive-sample gap alone, and still allows four ticks per second.
    static func isPlausibleDelta(previousTs: Int, currentTs: Int, lastMovedTs: Int, delta: Int) -> Bool {
        guard delta >= 1, delta < maxStepDelta else { return false }
        let elapsed = currentTs - previousTs
        guard elapsed > 0 else { return false }
        let rateAllowance = elapsed >= maxStepDelta / maxTicksPerSecond
            ? maxStepDelta - 1
            : elapsed * maxTicksPerSecond
        let releaseAllowance = min(currentTs - lastMovedTs, confirmationWindowSeconds) * releaseTicksPerSecond
        return delta <= max(rateAllowance, releaseAllowance)
    }

    /// Raw wrap-aware locomotion-tick total across `samples`. When any sample carries `activityClass`, each
    /// positive increment is attributed to the later sample and retained only for walk/run. When the whole
    /// window is legacy-unclassed, all valid increments retain the historical counter-only fallback. Sorts
    /// by `ts` internally and returns `nil` for fewer than two samples or no retained movement.
    public static func stepsInWindow(_ samples: [StepSample]) -> Int? {
        let sorted = samples.sorted { $0.ts < $1.ts }
        if sorted.count < 2 { return nil }
        let hasActivityClasses = hasActivityClasses(sorted)
        var total = 0
        var lastMovedTs = sorted[0].ts
        for i in 1..<sorted.count {
            let delta = (sorted[i].counter - sorted[i - 1].counter) & 0xFFFF  // wrap-aware u16 increment
            let isLocomotion = shouldCountDelta(
                activityClass: sorted[i].activityClass,
                hasActivityClasses: hasActivityClasses)
            if isLocomotion && isPlausibleDelta(
                previousTs: sorted[i - 1].ts, currentTs: sorted[i].ts,
                lastMovedTs: lastMovedTs, delta: delta) {
                total += delta
            }
            if delta != 0 { lastMovedTs = sorted[i].ts }
        }
        return total > 0 ? total : nil
    }
}
