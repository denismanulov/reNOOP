import Foundation
import WhoopProtocol

/// The persisted side of `V24AuxByte86Mapping` (the WHOOP 4.0 strap-computed blood-oxygen byte at
/// offset 86 of a 104-byte v24 record). The event kind, the `70...100` range and the event itself are
/// declared in WhoopProtocol, where `extractHistoricalStreams` builds them; this adds the read back
/// from the `event` table, following `StandardHRMapping`.
public extension V24AuxByte86Mapping {
    /// Parse a persisted `V24_AUX_BYTE_86` `payloadJSON`. Throws on malformed JSON or a missing /
    /// non-integer `byte` field so a parse failure is not the same as "no reading". Twin of Kotlin
    /// `V24AuxByte86Mapping.sample`.
    static func sample(ts: Int, payloadJSON: String) throws -> V24AuxByte86Sample {
        let payload = try JSONDecoder().decode([String: ParsedValue].self, from: Data(payloadJSON.utf8))
        guard case let .int(byte)? = payload[payloadKey] else {
            throw V24AuxByte86SampleError.missingByte
        }
        return V24AuxByte86Sample(ts: ts, byte: byte)
    }
}

public enum V24AuxByte86SampleError: Error, Equatable {
    case missingByte
}
