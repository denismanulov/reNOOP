import XCTest
@testable import WhoopProtocol

/// The WHOOP 4.0 strap-computed blood-oxygen byte at absolute offset 86 of a 104-byte v24 record.
///
/// Every frame below is a real record from the overnight strap logs two contributors attached to
/// #1617 (the Test Centre `spo2re` dump, which prints the full frame). They are the same captures that
/// issue read `red`/`ir` from and concluded a 4.0 banks no percentage; the result byte sits 16 bytes
/// further down the record and was not examined there. Type-47 records carry no name, serial or
/// session token, so they are safe committed fixtures.
///
/// What these pin is the decode and the storage rule, not accuracy: no capture has been compared with a
/// reference oximeter or with the WHOOP app's nightly figure. Same fixtures and expectations as Kotlin
/// `Whoop4AuxByte86Test`.
final class Whoop4AuxByte86Tests: XCTestCase {

    // Strap A, 2026-08-26: inside a measurement window (channel words 0x0b01 / 0x0562), byte = 94.
    private let percentA =
        "aa6400a12f1805b88f9c036f5a8e6a68158054400145015f030000000000000000ae43ff00a5e43be10a86bee1ca193e" +
        "ec9f813f0000803fe10a86bee1ca193eec9f813f10027402a60375024d019006010b620520005e0000000001ddea0000" +
        "00000000d974b36e"

    // Strap A, same night, earlier window: byte = 168 = 128 + 32 + 8, a status code and not a percentage.
    private let codeA =
        "aa6400a12f1805fe7c9c036e488e6a7847805440013d0000000000000000000061344048709adf3d663eb3be1f65233e" +
        "141a783f0098efc6663eb3be1f65233e141a783f10027402aa03760252016007010b62052100a80000000001c4ea0000" +
        "000000000d52b206"

    // Strap A, same night, outside any window (channel words 0x0c01 / 0x0c02 = disabled): byte = 0.
    private let idleA =
        "aa6400a12f1805ef359c031e048e6ac83c805440013d01b70300000000000000003f0aff00bc3c3c71a95a3f0060bfbe" +
        "48a9093f0004444671a95a3f0060bfbe48a9093f04026802000368024d019007010c020c0c000000000d000141ea0000" +
        "000000003d5b599e"

    // Strap B, 2026-08-26: a second strap's window (channel words 0x0b51 / 0x0552), byte = 94.
    private let percentB =
        "aa6400a12f180591191602b16e8e6a781c80544a012e0000000000000000000000fd48ff403b673c1f75313e148a533f" +
        "a49c063f0000dec61f75313e148a533fa49c063fd50159022803570250016002510b520520005e000000000494ee0000" +
        "00000000bdffe833"

    /// The synthetic 94-byte v24 record from `HistoricalV24Tests`: it ends before the DSP tail.
    private let shortV24Hex =
        "aa5a008e2f18000000000000f153650000000000003f0152030000000000000000dc053075" +
        "000000cdcc4c3dcdcccc3d5a657e3f00000040cdcc4c3dcdcccc3d5a657e3f504668428403" +
        "200364006400b80bb80b000000000000c25c1a88"

    private func bytes(_ s: String) -> [UInt8] {
        var out = [UInt8](); out.reserveCapacity(s.count / 2); var i = s.startIndex
        while i < s.endIndex { let j = s.index(i, offsetBy: 2)
            out.append(UInt8(s[i..<j], radix: 16)!); i = j }
        return out
    }

    private func banked(_ streams: Streams) -> [WhoopEvent] {
        streams.events.filter { $0.kind == V24AuxByte86Mapping.eventKind }
    }

    func testFixturesAreIntact104ByteRecords() {
        for hex in [percentA, codeA, idleA, percentB] {
            let out = parseFrame(bytes(hex), family: .whoop4)
            XCTAssertEqual(bytes(hex).count, 104)
            XCTAssertEqual(out.crcOK, true)
        }
    }

    func testAPercentageIsDecodedAndOfferedAsACandidate() {
        for hex in [percentA, percentB] {
            let p = parseFrame(bytes(hex), family: .whoop4).parsed
            XCTAssertEqual(p["hist_version"]?.intValue, 24)
            XCTAssertEqual(p["aux_byte_86"]?.intValue, 94)
            XCTAssertEqual(p["spo2_candidate_86"]?.intValue, 94)
        }
    }

    /// A status code is carried raw and is NOT offered as a percentage.
    func testAStatusCodeIsCarriedRawButIsNotACandidate() {
        let p = parseFrame(bytes(codeA), family: .whoop4).parsed
        XCTAssertEqual(p["aux_byte_86"]?.intValue, 168)
        XCTAssertNil(p["spo2_candidate_86"])
    }

    /// Zero is "nothing computed this second", never 0%.
    func testAnIdleRecordReadsZeroAndIsNotACandidate() {
        let p = parseFrame(bytes(idleA), family: .whoop4).parsed
        XCTAssertEqual(p["aux_byte_86"]?.intValue, 0)
        XCTAssertNil(p["spo2_candidate_86"])
    }

    /// The field the #1617 analysis was about is untouched by this decode.
    func testTheRedAndIrFieldsStillDecodeBesideIt() {
        let p = parseFrame(bytes(percentA), family: .whoop4).parsed
        XCTAssertEqual(p["spo2_red"]?.intValue, 0x0210)
        XCTAssertEqual(p["spo2_ir"]?.intValue, 0x0274)
    }

    /// The candidate gate is inclusive at 70 and 100 and closed outside them. Decode is not CRC-gated,
    /// so an in-memory edit of the one byte is sufficient.
    func testTheCandidateBandIsInclusiveAt70And100() {
        for (raw, want) in [(69, nil), (70, 70), (100, 100), (101, nil)] as [(Int, Int?)] {
            var f = bytes(percentA)
            f[86] = UInt8(raw)
            let p = parseFrame(f, family: .whoop4).parsed
            XCTAssertEqual(p["aux_byte_86"]?.intValue, raw)
            XCTAssertEqual(p["spo2_candidate_86"]?.intValue, want, "raw \(raw)")
        }
    }

    /// Only nonzero bytes are banked, each as one event at the record's own second.
    func testOnlyNonzeroBytesAreBankedAsEvents() {
        let parsed = [idleA, codeA, percentA].map { parseFrame(bytes($0), family: .whoop4) }
        let streams = extractHistoricalStreams(parsed, deviceClockRef: 0, wallClockRef: 0, family: .whoop4)
        let events = banked(streams)
        XCTAssertEqual(events.map(\.ts), [1_787_709_550, 1_787_714_159])
        XCTAssertEqual(events.map(\.payload), [["byte": .int(168)], ["byte": .int(94)]])
        // The idle record still banked its ordinary streams; it just has no reading to record.
        XCTAssertTrue(streams.hr.contains { $0.ts == 1_787_692_062 })
        XCTAssertFalse(events.contains { $0.ts == 1_787_692_062 })
    }

    /// The payload is one integer under one key, so the stored JSON is the same string Android writes.
    /// `WhoopStore.encodePayload` uses this encoder configuration; its own test pins the stored row.
    func testTheEventPayloadEncodesToTheSharedJSON() throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        func json(_ byte: Int) throws -> String {
            String(decoding: try encoder.encode(V24AuxByte86Mapping.event(ts: 1, byte: byte).payload),
                   as: UTF8.self)
        }
        XCTAssertEqual(try json(168), #"{"byte":168}"#)
        XCTAssertEqual(try json(94), #"{"byte":94}"#)
        XCTAssertEqual(V24AuxByte86Mapping.eventKind, "V24_AUX_BYTE_86")
    }

    /// The same bytes read as another family's record must not bank a WHOOP 4.0 reading.
    func testAWhoop5DecodeOfTheSameBytesBanksNothing() {
        let asFive = [parseFrame(bytes(percentA), family: .whoop5)]
        XCTAssertTrue(banked(extractHistoricalStreams(asFive, deviceClockRef: 0, wallClockRef: 0,
                                                      family: .whoop5)).isEmpty)
        // A correctly decoded 4.0 record handed to the extractor under another family, or under none,
        // banks nothing either: the gate is the family, not the key being present.
        let asFour = [parseFrame(bytes(percentA), family: .whoop4)]
        XCTAssertTrue(banked(extractHistoricalStreams(asFour, deviceClockRef: 0, wallClockRef: 0,
                                                      family: .whoop5)).isEmpty)
        XCTAssertTrue(banked(extractHistoricalStreams(asFour, deviceClockRef: 0, wallClockRef: 0)).isEmpty)
    }

    /// The offset was established on the 104-byte record whose own version byte is 24. A shorter v24
    /// record and a version-12 record borrowing the v24 field map carry no byte at all.
    func testOtherLayoutsCarryNoByte() {
        let short = parseFrame(bytes(shortV24Hex), family: .whoop4)
        XCTAssertEqual(short.parsed["hist_version"]?.intValue, 24)
        XCTAssertNil(short.parsed["aux_byte_86"])
        XCTAssertNil(short.parsed["spo2_candidate_86"])

        var v12 = bytes(percentA)
        v12[5] = 12
        let p = parseFrame(v12, family: .whoop4).parsed
        XCTAssertEqual(p["hist_version"]?.intValue, 12)
        XCTAssertNil(p["aux_byte_86"])
        XCTAssertNil(p["spo2_candidate_86"])
    }

    /// GUARD (the rule in AGENTS.md for a signal derived from sensor data): the byte is a candidate, so
    /// decoding or banking it must not change anything a score reads. A record with a percentage and
    /// the same record with the byte zeroed must decode to the same fields apart from the two candidate
    /// keys, and must bank the same rows in every stream apart from the one event. In particular the
    /// byte never surfaces as `spo2_pct`, and never alters the `spo2_red` / `spo2_ir` row.
    func testTheByteChangesNothingButItsOwnKeysAndItsOwnEvent() {
        var zeroed = bytes(percentA)
        zeroed[86] = 0
        let with = parseFrame(bytes(percentA), family: .whoop4)
        let without = parseFrame(zeroed, family: .whoop4)

        var a = with.parsed, b = without.parsed
        XCTAssertNil(a["spo2_pct"])
        XCTAssertNil(a["spo2Pct"])
        for key in ["aux_byte_86", "spo2_candidate_86"] { a[key] = nil; b[key] = nil }
        XCTAssertEqual(a, b, "the byte must add its own two keys and touch no other decoded field")

        // The zeroed frame's CRC no longer matches, and the extractor skips a frame whose CRC failed,
        // so compare what the extractor reads: the parsed fields, under the intact frame's verdict.
        let rebuilt = ParsedFrame(ok: with.ok, typeName: with.typeName, seq: with.seq,
                                  cmdName: with.cmdName, crcOK: with.crcOK, lenBytes: with.lenBytes,
                                  rawHex: without.rawHex, fields: without.fields, parsed: without.parsed)
        let sWith = extractHistoricalStreams([with], deviceClockRef: 0, wallClockRef: 0, family: .whoop4)
        let sWithout = extractHistoricalStreams([rebuilt], deviceClockRef: 0, wallClockRef: 0,
                                                family: .whoop4)
        XCTAssertEqual(sWith.spo2, sWithout.spo2)
        XCTAssertEqual(sWith.spo2, [SpO2Sample(ts: 1_787_714_159, red: 0x0210, ir: 0x0274)])
        XCTAssertEqual(sWith.hr, sWithout.hr)
        XCTAssertEqual(sWith.rr, sWithout.rr)
        XCTAssertEqual(sWith.skinTemp, sWithout.skinTemp)
        XCTAssertEqual(sWith.resp, sWithout.resp)
        XCTAssertEqual(sWith.gravity, sWithout.gravity)
        XCTAssertEqual(sWith.steps, sWithout.steps)
        XCTAssertEqual(sWith.sleepState, sWithout.sleepState)
        XCTAssertEqual(sWith.v18Aux, sWithout.v18Aux)
        XCTAssertEqual(sWith.events, [V24AuxByte86Mapping.event(ts: 1_787_714_159, byte: 94)])
        XCTAssertTrue(sWithout.events.isEmpty)
    }
}
