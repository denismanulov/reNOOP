import Foundation
import StrandAnalytics
import WhoopStore

/// The one pending "I'm awake" mark: the instant the wearer last tapped it, kept until a scoring pass
/// has had the chance to end that night there (`WakeMarkTrim`).
///
/// A single `UserDefaults` value and deliberately not a database row: it is a request waiting to be
/// applied, not a measurement. For the same reason it is NOT in the `.noopbak` settings whitelist; a
/// restored backup must not replay a tap from another morning. The mark's own record stays where
/// Phase 1 put it (the `sleep_mark` series and the strap log). A newer tap replaces an older one.
/// Twin of Kotlin `WakeMarkStore`, under the same key.
enum WakeMarkStore {
    static let pendingKey = "noop.pendingWakeMarkTs"

    /// The pending mark in unix seconds, or nil when there is none.
    static func pending(_ defaults: UserDefaults = .standard) -> Int? {
        let ts = defaults.integer(forKey: pendingKey)
        return ts > 0 ? ts : nil
    }

    static func setPending(_ wakeTs: Int, _ defaults: UserDefaults = .standard) {
        defaults.set(wakeTs, forKey: pendingKey)
    }

    static func clear(_ defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: pendingKey)
    }
}

/// Applies the pending wake mark to the night it falls in, once that night is on record.
@MainActor
enum PendingWakeMark {
    /// The always-on strap-log line for a trim. Rare-event evidence, so it is not gated behind a Test
    /// Centre domain. Same text as Android.
    static func logLine(secondsEarlier: Int) -> String {
        "Sleep mark · wake moved the end of sleep \(secondsEarlier) s earlier"
    }

    /// End the night a pending wake mark falls in at that mark, if such a night is on record yet.
    /// Returns true when a night was trimmed, so the caller can re-score. Safe to call after every
    /// scoring pass: with no pending mark it reads one preference and returns.
    ///
    /// The nights offered are the merged sleep sessions of the active strap whose detected start lies
    /// in the day before the mark, as `(effective start, end)`. The trim goes through
    /// `Repository.editSleepTimes`, the hand-edit path, so the night is marked `userEdited` and a later
    /// pass cannot re-detect the longer night back over it. The mark is cleared before the edit, so the
    /// refresh the edit causes finds nothing pending.
    static func apply(repo: Repository, defaults: UserDefaults = .standard,
                      now: Int = Int(Date().timeIntervalSince1970),
                      log: (String) -> Void) async -> Bool {
        guard let wakeTs = WakeMarkStore.pending(defaults) else { return false }
        // Sessions are selected by START time, so reach back a day to see the night the mark ends.
        let nights = await repo.allSleepSessions(days: 2)
            .filter { $0.startTs >= wakeTs - 86_400 && $0.startTs <= wakeTs }
        // The tap may have been replaced while the sessions were being read.
        guard WakeMarkStore.pending(defaults) == wakeTs else { return false }
        let decision = WakeMarkTrim.decide(
            nights: nights.map { (start: $0.effectiveStartTs, end: $0.endTs) }, wakeTs: wakeTs, nowTs: now)
        switch decision {
        case let .trim(index, newEndTs):
            let night = nights[index]
            WakeMarkStore.clear(defaults)
            log(logLine(secondsEarlier: night.endTs - newEndTs))
            await repo.editSleepTimes(detectedStartTs: night.startTs, oldEndTs: night.endTs,
                                      storedStagesJSON: night.stagesJSON,
                                      newStartTs: night.effectiveStartTs, newEndTs: newEndTs)
            return true
        case .expired:
            WakeMarkStore.clear(defaults)
            return false
        case .wait:
            return false
        }
    }
}
