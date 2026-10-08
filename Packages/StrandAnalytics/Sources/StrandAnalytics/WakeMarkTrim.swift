import Foundation

// WakeMarkTrim — what a tapped "I'm awake" mark does to the night it lands in (#461, the step after
// Phase 1's pure logging).
//
// The detector ends a night when the wrist stops looking asleep, and it is usually right. Its misses
// have one shape: the wearer wakes and then lies still, and the stillness is read as more sleep. On one
// wearer's eight nights the detected end sat within 30 seconds of the rise seen in the record on three,
// and 3 and 19 minutes late on two. A tapped mark is the one input that knows the difference.
//
// So a mark may only ever move the end of a night EARLIER, to the instant it was tapped, and only when
// it lands inside that night and close to its end. It never extends a night, never starts one, and
// never touches a night it does not fall in: a mark tapped ten minutes after getting up changes
// nothing, because the night already ended before it.
//
// A mark usually arrives BEFORE the night it belongs to has been synced and scored, so it is kept
// pending and offered to every later scoring pass until it either trims a night or grows too old.
//
// Pure: no I/O, no clock reads. All times are unix seconds. Byte-parity twin of Kotlin `WakeMarkTrim`.
public enum WakeMarkTrim {

    /// The furthest before a night's detected end a mark may sit and still be that night's waking. A
    /// mark deeper inside the night than this is far more likely a stray tap than a wake the detector
    /// missed by over an hour, and trimming on it would delete real sleep.
    public static let maxTrimSeconds = 60 * 60

    /// How long a mark waits for its night to be synced and scored before it is dropped.
    public static let maxPendingSeconds = 12 * 3600

    public enum Decision: Equatable, Sendable {
        /// End the night at `index` (into the list passed to `decide`) at `newEndTs`.
        case trim(index: Int, newEndTs: Int)
        /// No night ends later than the mark yet; keep the mark and ask again after the next pass.
        case wait
        /// The mark is too old to belong to any night still being scored; forget it.
        case expired
    }

    /// The end a night spanning `[startTs, endTs]` should take for a mark at `wakeTs`, or nil to leave
    /// it as detected. Non-nil only when the mark is strictly inside the night and no further than
    /// `maxTrimSeconds` before its end.
    public static func trimmedEnd(startTs: Int, endTs: Int, wakeTs: Int) -> Int? {
        wakeTs > startTs && wakeTs < endTs && endTs - wakeTs <= maxTrimSeconds ? wakeTs : nil
    }

    /// What to do with a pending mark at `wakeTs`, given the recorded `nights` as `(start, end)` pairs
    /// and the current time. The first night the mark can trim wins; a mark that fits none waits, and
    /// one older than `maxPendingSeconds` expires.
    public static func decide(nights: [(start: Int, end: Int)], wakeTs: Int, nowTs: Int) -> Decision {
        if nowTs - wakeTs > maxPendingSeconds { return .expired }
        for (index, night) in nights.enumerated() {
            if let newEnd = trimmedEnd(startTs: night.start, endTs: night.end, wakeTs: wakeTs) {
                return .trim(index: index, newEndTs: newEnd)
            }
        }
        return .wait
    }
}
