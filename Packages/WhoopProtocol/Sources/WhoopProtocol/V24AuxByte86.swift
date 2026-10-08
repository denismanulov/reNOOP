import Foundation

/// The byte at absolute offset 86 of a WHOOP 4.0 104-byte v24 historical record.
///
/// The byte is the strap's own blood-oxygen result for that second: `0` means no computed reading,
/// `70...100` is a percentage, and every other nonzero value is a status or error code. The offset and
/// those semantics come from a third-party firmware analysis and are reimplemented here as a protocol
/// fact, see `ATTRIBUTION.md`.
///
/// What the captures show, on two straps from the #1617 overnight logs: the byte is `0` in every record
/// whose optical channel words at 80/82 read disabled, and nonzero only inside the ~29 s windows where
/// those words read enabled (28 of 28 and 8 of 8 records). Inside a window it reads codes first and then
/// 93...95. No capture has been compared against a reference oximeter or the WHOOP app's nightly figure,
/// so this is an UNVALIDATED CANDIDATE: it never writes `spo2Pct` and never feeds a score.
///
/// Only NONZERO bytes are recorded. A zero is "the strap computed nothing this second", which is the
/// state of almost every record, and a row per second to say so would be the #1617 red/IR table again.
/// Absence of an event is therefore absence of a reading, never a 0% reading.
///
/// Rides the `event` table under its own kind, as `StandardHRMapping` does, so it needs no migration.
/// The kind and the payload live in this package because `extractHistoricalStreams` builds the event;
/// the persisted-payload parse is in WhoopStore. Twin of Kotlin `V24AuxByte86Mapping`.
public enum V24AuxByte86Mapping {
    public static let eventKind = "V24_AUX_BYTE_86"

    /// The in-band window in which the byte is a percentage. Same bounds as the v18 `@82` candidate.
    public static let percentRange = 70...100

    /// The decoder's key for the raw byte, and for its gated `percentRange` view.
    public static let decoderKey = "aux_byte_86"
    public static let candidateDecoderKey = "spo2_candidate_86"

    /// The one payload key. A single integer, so the stored JSON is `{"byte":168}` on both platforms.
    public static let payloadKey = "byte"

    public static func event(ts: Int, byte: Int) -> WhoopEvent {
        WhoopEvent(ts: ts, kind: eventKind, payload: [payloadKey: .int(byte)])
    }
}

/// One nonzero `@86` byte: a percentage when in `V24AuxByte86Mapping.percentRange`, else a code.
public struct V24AuxByte86Sample: Equatable, Sendable {
    public let ts: Int
    public let byte: Int
    public init(ts: Int, byte: Int) {
        self.ts = ts
        self.byte = byte
    }
}
