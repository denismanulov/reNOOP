import XCTest
import GRDB
import WhoopProtocol
@testable import WhoopStore

/// The stored side of the WHOOP 4.0 strap-computed blood-oxygen byte (`aux_byte_86`): the event row it
/// is banked as, and the read back. The row is a cross-platform contract, so the payload is asserted as
/// the exact string, not as a decoded value.
final class V24AuxByte86MappingTests: XCTestCase {
    private let device = "whoop-four"

    func testTheStoredRowIsTheSharedKindAndPayloadString() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.upsertDevice(id: device, mac: nil, name: nil)
        _ = try await store.insert(Streams(events: [
            V24AuxByte86Mapping.event(ts: 1_787_709_550, byte: 168),
            V24AuxByte86Mapping.event(ts: 1_787_714_159, byte: 94),
        ]), deviceId: device)

        let rows = try await store.registryWriter.read { db in
            try Row.fetchAll(db, sql: "SELECT ts, kind, payloadJSON FROM event ORDER BY ts")
                .map { row -> String in
                    let ts: Int = row["ts"], kind: String = row["kind"], json: String = row["payloadJSON"]
                    return "\(ts) \(kind) \(json)"
                }
        }
        XCTAssertEqual(rows, [
            #"1787709550 V24_AUX_BYTE_86 {"byte":168}"#,
            #"1787714159 V24_AUX_BYTE_86 {"byte":94}"#,
        ])
    }

    func testReadingsSurviveInsertAndReadCodesIncluded() async throws {
        let store = try await WhoopStore.inMemory()
        try await store.upsertDevice(id: device, mac: nil, name: nil)
        try await store.upsertDevice(id: "other", mac: nil, name: nil)
        _ = try await store.insert(Streams(events: [
            V24AuxByte86Mapping.event(ts: 100, byte: 168),
            V24AuxByte86Mapping.event(ts: 110, byte: 94),
            V24AuxByte86Mapping.event(ts: 500, byte: 95),
            // Another kind at the same second must not be read as a byte.
            WhoopEvent(ts: 110, kind: "BATTERY_LEVEL(3)", payload: ["byte": .int(7)]),
        ]), deviceId: device)
        _ = try await store.insert(Streams(events: [V24AuxByte86Mapping.event(ts: 120, byte: 99)]),
                                   deviceId: "other")

        let inWindow = try await store.v24AuxByte86Samples(deviceId: device, from: 0, to: 200)
        XCTAssertEqual(inWindow, [V24AuxByte86Sample(ts: 100, byte: 168),
                                  V24AuxByte86Sample(ts: 110, byte: 94)])
        let limited = try await store.v24AuxByte86Samples(deviceId: device, from: 0, to: 1_000, limit: 1)
        XCTAssertEqual(limited, [V24AuxByte86Sample(ts: 100, byte: 168)])
        let none = try await store.v24AuxByte86Samples(deviceId: "nobody", from: 0, to: 1_000)
        XCTAssertTrue(none.isEmpty)
    }

    func testSampleParsesTheSharedPayloadAndDoesNotTreatParseFailureAsAbsence() throws {
        XCTAssertEqual(try V24AuxByte86Mapping.sample(ts: 7, payloadJSON: #"{"byte":168}"#),
                       V24AuxByte86Sample(ts: 7, byte: 168))
        XCTAssertThrowsError(try V24AuxByte86Mapping.sample(ts: 8, payloadJSON: "not-json"))
        XCTAssertThrowsError(try V24AuxByte86Mapping.sample(ts: 9, payloadJSON: "{}"))
        XCTAssertThrowsError(try V24AuxByte86Mapping.sample(ts: 10, payloadJSON: #"{"byte":"94"}"#))
    }
}
