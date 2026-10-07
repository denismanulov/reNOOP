import Foundation

/// The accelerometer block of a WHOOP 4.0 realtime raw IMU packet (REALTIME_RAW_DATA, the 1917-byte
/// payload variant): one packet per second, 100 samples per axis.
///
/// Every offset comes from the bundled schema (`timestamp`, `subseconds`, and the variant's accel axes),
/// so this adds no layout fact of its own. Samples stay raw; `gPerLSB` is the scale the schema records
/// for them. The packet's gyroscope block and tail are not read here.
public enum Whoop4RawImu {

    public struct AccelBuffer: Equatable, Sendable {
        /// Strap clock, whole seconds: the same clock the history records are stamped with.
        public let timestamp: Int
        /// Strap clock subseconds, in 1/32768 s.
        public let subseconds: Int
        public let x: [Int16]
        public let y: [Int16]
        public let z: [Int16]

        public init(timestamp: Int, subseconds: Int, x: [Int16], y: [Int16], z: [Int16]) {
            self.timestamp = timestamp
            self.subseconds = subseconds
            self.x = x
            self.y = y
            self.z = z
        }
    }

    /// Accelerometer scale: 1/4096 g per count (sphere-fit against gravity, see the schema's variant note).
    public static let gPerLSB = 1.0 / 4096.0
    /// Samples the strap puts in one packet per axis, at about 100 Hz.
    public static let samplesPerPacket = 100

    /// Nil unless `frame` is an intact WHOOP 4.0 raw IMU packet carrying all three accel axes in full.
    public static func accel(_ frame: [UInt8]) -> AccelBuffer? {
        let schema = loadSchema()
        guard frame.count > 4, let spec = schema.packet(forType: Int(frame[4])),
              spec.name == "REALTIME_RAW_DATA",
              verifyFrame(frame, family: .whoop4).ok else { return nil }
        let declaredLength = Int(frame[1]) | Int(frame[2]) << 8
        guard let variant = spec.variants[String(declaredLength - 7)], variant.kind == "imu",
              let samples = variant.samples, samples == samplesPerPacket else { return nil }
        let limit = declaredLength   // the CRC32 trailer starts here; no sample may reach into it

        func field(_ name: String) -> Int? {
            guard let f = spec.fields.first(where: { $0.name == name }),
                  f.off + f.len <= limit else { return nil }
            return (0..<f.len).reduce(0) { $0 | Int(frame[f.off + $1]) << (8 * $1) }
        }
        func axis(_ name: String) -> [Int16]? {
            guard let a = variant.axes.first(where: { $0.name == name && $0.cat == "accel" }),
                  a.off + samples * 2 <= limit else { return nil }
            return (0..<samples).map { i in
                Int16(bitPattern: UInt16(frame[a.off + 2 * i]) | UInt16(frame[a.off + 2 * i + 1]) << 8)
            }
        }
        guard let timestamp = field("timestamp"), let subseconds = field("subseconds"),
              let x = axis("accelX"), let y = axis("accelY"), let z = axis("accelZ") else { return nil }
        return AccelBuffer(timestamp: timestamp, subseconds: subseconds, x: x, y: y, z: z)
    }
}
