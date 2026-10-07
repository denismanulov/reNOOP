import XCTest
@testable import WhoopProtocol

/// `Whoop4RawImu.accel` against the real 1928-byte raw IMU packets in the parity corpus (`frames.json`).
final class Whoop4RawImuTests: XCTestCase {

    private struct Entry: Decodable { let hex: String }

    private func corpus() throws -> [[UInt8]] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "frames", withExtension: "json"))
        return try JSONDecoder().decode([Entry].self, from: Data(contentsOf: url)).map { entry in
            var out = [UInt8](); var i = entry.hex.startIndex
            while i < entry.hex.endIndex {
                let j = entry.hex.index(i, offsetBy: 2)
                out.append(UInt8(entry.hex[i..<j], radix: 16)!); i = j
            }
            return out
        }
    }

    func testDecodesTheImuPacketsOfTheCorpus() throws {
        let imu = try corpus().filter { $0.count == 1928 }
        XCTAssertFalse(imu.isEmpty)
        for frame in imu {
            let buffer = try XCTUnwrap(Whoop4RawImu.accel(frame))
            let parsed = parseFrame(frame, family: .whoop4)
            XCTAssertEqual(buffer.x.count, 100)
            XCTAssertEqual(buffer.y.count, 100)
            XCTAssertEqual(buffer.z.count, 100)
            XCTAssertEqual(buffer.timestamp, parsed.parsed["timestamp"]?.intValue)
            // The interpreter reports each axis as a mean rounded to one decimal; the raw block agrees.
            for (name, samples) in [("accelX", buffer.x), ("accelY", buffer.y), ("accelZ", buffer.z)] {
                let mean = Double(samples.reduce(0) { $0 + Int($1) }) / 100
                let reported = try XCTUnwrap(parsed.parsed["\(name)_mean"]?.doubleValue
                                             ?? parsed.parsed["\(name)_mean"]?.intValue.map(Double.init))
                XCTAssertEqual(mean, reported, accuracy: 0.051, name)
            }
            // A worn or resting strap reads about 1 g.
            let magnitudes = (0..<100).map { i -> Double in
                let x = Double(buffer.x[i]), y = Double(buffer.y[i]), z = Double(buffer.z[i])
                return (x * x + y * y + z * z).squareRoot() * Whoop4RawImu.gPerLSB
            }
            XCTAssertEqual(magnitudes.reduce(0, +) / 100, 1.0, accuracy: 0.25)
        }
    }

    func testEveryOtherFrameOfTheCorpusIsRefused() throws {
        for frame in try corpus() where frame.count != 1928 {
            XCTAssertNil(Whoop4RawImu.accel(frame))
        }
    }

    func testADamagedImuPacketIsRefused() throws {
        var frame = try XCTUnwrap(try corpus().first { $0.count == 1928 })
        frame[300] ^= 0x40
        XCTAssertNil(Whoop4RawImu.accel(frame))
    }
}
