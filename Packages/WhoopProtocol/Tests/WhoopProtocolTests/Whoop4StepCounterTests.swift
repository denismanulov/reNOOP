import XCTest
@testable import WhoopProtocol

/// The WHOOP 4.0 firmware step counter: a cumulative u32 at frame offset 92 of the 104-byte v24
/// history record.
///
/// `walkStart` holds real on-wrist records from one strap, captured 2026-10-03 across the start of a
/// counted walk: the counter sits flat, releases 12 buffered steps in one record, then climbs. Type-47
/// records carry no name, serial or session token, so they are safe committed fixtures.
final class Whoop4StepCounterTests: XCTestCase {

    private let walkStart: [(hex: String, unix: Int, counter: Int)] = [
        ("aa6400a12f18051ffd5301f7dfc06a883c805422014d0000000000000000000060a004cda06ab63e8f023a3f" +
         "9ad11b3fb8ee0cbe0020a2c68f023a3f9ad11b3fb8ee0cbef4014202fd0240023101a003010c020c00000000" +
         "006c00014ab2000000000000bfc134d4", 1_791_025_143, 45_642),
        ("aa6400a12f180520fd5301f8dfc06a9037805422014f0000000000000000000060b30bdcd0beeb3d4889643f" +
         "3d22073f856bf4bc00c0bdc64889643f3d22073f856bf4bcf4014202fc023f023201a003010c020c00000000" +
         "006c000156b20000000000002ef6ff39", 1_791_025_144, 45_654),
        ("aa6400a12f180521fd5301f9dfc06aa03280542201510000000000000000000060ea03e1f0278c3db8d66e3f" +
         "295cde3ec3b53ebd00e0f546b8d66e3f295cde3ec3b53ebdf4014202fd023f023101a003010c020c00000000" +
         "0024000157b200000000000072d8f47e", 1_791_025_145, 45_655),
        ("aa6400a12f180522fd5301fadfc06ab02d80542201530000000000000000000061d50ddd40c1b23dc3456b3f" +
         "140eb83e5278953d0040d546c3456b3f140eb83e5278953df4014202fe023f023101a003010c020c01000000" +
         "0024000159b2000000000000118b600f", 1_791_025_146, 45_657),
        ("aa6400a12f180523fd5301fbdfc06ab828805422015400000000000000000000615204d3e0e6d13d9a79703f" +
         "3dbacc3e0a572a3d006082c69a79703f3dbacc3e0a572a3df4014202ff0240023101a003010c020c01000000" +
         "001300015ab2000000000000110452c3", 1_791_025_147, 45_658),
    ]

    /// A different strap, captured 2026-06-08 (the frame `Whoop4HistoricalV24HardwareTests` uses).
    private let otherStrapHex =
        "aa6400a12f18054c1c0a023ed0266a5037805418016d022b0234020000000000006b07ff00" +
        "85593c1f65cebed7b3e63eb85a5f3f000080401f65cebed7b3e63eb85a5f3f500264025d03" +
        "640229014009010c020c00000000000f0001c4020000000000008fdeb278"

    /// The synthetic 94-byte v24 record from `HistoricalV24Tests`: it ends before offset 92.
    private let shortV24Hex =
        "aa5a008e2f18000000000000f153650000000000003f0152030000000000000000dc053075" +
        "000000cdcc4c3dcdcccc3d5a657e3f00000040cdcc4c3dcdcccc3d5a657e3f504668428403" +
        "200364006400b80bb80b000000000000c25c1a88"

    /// `walkStart[1]` with its version byte set to 12 and the CRC32 recomputed.
    private let version12Hex =
        "aa6400a12f0c0520fd5301f8dfc06a9037805422014f0000000000000000000060b30bdcd0beeb3d4889643f" +
        "3d22073f856bf4bc00c0bdc64889643f3d22073f856bf4bcf4014202fc023f023201a003010c020c00000000" +
        "006c000156b20000000000005af393ab"

    private func bytes(_ s: String) -> [UInt8] {
        var out = [UInt8](); out.reserveCapacity(s.count / 2); var i = s.startIndex
        while i < s.endIndex { let j = s.index(i, offsetBy: 2)
            out.append(UInt8(s[i..<j], radix: 16)!); i = j }
        return out
    }

    func testCounterDecodesFromRealRecords() {
        for record in walkStart {
            let out = parseFrame(bytes(record.hex))
            XCTAssertEqual(out.crcOK, true)
            XCTAssertEqual(out.parsed["hist_version"]?.intValue, 24)
            XCTAssertEqual(out.parsed["unix"]?.intValue, record.unix)
            XCTAssertEqual(out.parsed["step_counter"]?.intValue, record.counter)
        }
    }

    func testCounterDecodesOnASecondStrap() {
        XCTAssertEqual(parseFrame(bytes(otherStrapHex)).parsed["step_counter"]?.intValue, 708)
    }

    func testShortRecordCarriesNoCounter() {
        let out = parseFrame(bytes(shortV24Hex))
        XCTAssertEqual(out.crcOK, true)
        XCTAssertNil(out.parsed["step_counter"])
        XCTAssertTrue(extractHistoricalStreams([out], deviceClockRef: 0, wallClockRef: 0).steps.isEmpty)
    }

    func testVersion12RecordCarriesNoCounter() {
        let out = parseFrame(bytes(version12Hex))
        XCTAssertEqual(out.crcOK, true)
        XCTAssertEqual(out.parsed["hist_version"]?.intValue, 12)
        XCTAssertEqual(out.parsed["heart_rate"]?.intValue, 79)   // the borrowed v24 map still decodes
        XCTAssertNil(out.parsed["step_counter"])
    }

    func testStreamsCarryOneStepSamplePerRecord() {
        let parsed = walkStart.map { parseFrame(bytes($0.hex)) }
        let streams = extractHistoricalStreams(parsed, deviceClockRef: 0, wallClockRef: 0)
        XCTAssertEqual(streams.steps, walkStart.map { StepSample(ts: $0.unix, counter: $0.counter) })
    }
}
