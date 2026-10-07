package com.noop.protocol

// Whoop4RawImu.kt — the accelerometer block of a WHOOP 4.0 realtime raw IMU packet (REALTIME_RAW_DATA,
// the 1917-byte payload variant): one packet per second, 100 samples per axis. Kotlin twin of
// WhoopProtocol/Whoop4RawImu.swift — same gating, same bytes read, same values out.
//
// WHERE THE OFFSETS COME FROM. The Swift decoder reads every offset from the bundled schema
// (`whoop_protocol.json`: the packet's `timestamp` and `subseconds` fields and the variant's accel axes)
// at run time. Android bundles no schema, so the constants below restate those schema entries, and
// `Whoop4RawImuTest.layoutConstantsMatchTheSharedSchema` reads the schema file and holds them to it.
// This file adds no layout fact of its own.
//
// Samples stay raw; [Whoop4RawImu.gPerLSB] is the scale the schema records for them. The packet's
// gyroscope block and tail are not read here.

object Whoop4RawImu {

    /**
     * One packet's accelerometer block. A value type like the Swift struct: two buffers holding the same
     * numbers are equal.
     */
    class AccelBuffer(
        /** Strap clock, whole seconds: the same clock the history records are stamped with. A full u32. */
        val timestamp: Long,
        /** Strap clock subseconds, in 1/32768 s. */
        val subseconds: Int,
        val x: ShortArray,
        val y: ShortArray,
        val z: ShortArray,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (
                other is AccelBuffer && timestamp == other.timestamp && subseconds == other.subseconds &&
                    x.contentEquals(other.x) && y.contentEquals(other.y) && z.contentEquals(other.z)
                )

        override fun hashCode(): Int {
            var h = timestamp.hashCode()
            h = 31 * h + subseconds
            h = 31 * h + x.contentHashCode()
            h = 31 * h + y.contentHashCode()
            h = 31 * h + z.contentHashCode()
            return h
        }

        override fun toString(): String =
            "AccelBuffer(timestamp=$timestamp, subseconds=$subseconds, samples=${x.size}/${y.size}/${z.size})"
    }

    /** Accelerometer scale: 1/4096 g per count (sphere-fit against gravity, see the schema's variant note). */
    const val gPerLSB = 1.0 / 4096.0

    /** Samples the strap puts in one packet per axis, at about 100 Hz. */
    const val samplesPerPacket = 100

    // The schema entries this decoder reads (packets.REALTIME_RAW_DATA), FRAME-absolute like the schema.
    // Internal so the lockstep test can compare each one with the schema file.

    /** The variant key: the payload length (declared length minus the 7 envelope bytes) of the IMU layout. */
    internal const val imuPayloadLength = 1917

    /** `fields[name == "timestamp"]`: u32 little-endian. */
    internal const val timestampOffset = 11
    internal const val timestampLength = 4

    /** `fields[name == "subseconds"]`: u16 little-endian. */
    internal const val subsecondsOffset = 15
    internal const val subsecondsLength = 2

    /** `variants["1917"].axes`, the three with category `accel`: i16 little-endian, columnar. */
    internal const val accelXOffset = 89
    internal const val accelYOffset = 289
    internal const val accelZOffset = 489

    /**
     * Null unless [frame] is an intact WHOOP 4.0 raw IMU packet carrying all three accel axes in full.
     * Swift twin: `Whoop4RawImu.accel(_:)`.
     */
    fun accel(frame: ByteArray): AccelBuffer? {
        if (frame.size <= 4) return null
        if ((frame[4].toInt() and 0xFF) != PacketType.REALTIME_RAW_DATA.rawValue) return null
        if (!Framing.verifyFrame(frame, DeviceFamily.WHOOP4).ok) return null
        val declaredLength = (frame[1].toInt() and 0xFF) or ((frame[2].toInt() and 0xFF) shl 8)
        // Only the IMU variant: the optical one (payload 1921) and any other length have no accel axes.
        if (declaredLength - 7 != imuPayloadLength) return null
        val limit = declaredLength   // the CRC32 trailer starts here; no sample may reach into it

        val timestamp = field(frame, timestampOffset, timestampLength, limit) ?: return null
        val subseconds = field(frame, subsecondsOffset, subsecondsLength, limit) ?: return null
        val x = axis(frame, accelXOffset, limit) ?: return null
        val y = axis(frame, accelYOffset, limit) ?: return null
        val z = axis(frame, accelZOffset, limit) ?: return null
        return AccelBuffer(timestamp = timestamp, subseconds = subseconds.toInt(), x = x, y = y, z = z)
    }

    /** A little-endian unsigned field of [len] bytes, or null when it would reach past [limit]. */
    private fun field(frame: ByteArray, off: Int, len: Int, limit: Int): Long? {
        if (off + len > limit) return null
        var value = 0L
        for (i in 0 until len) value = value or ((frame[off + i].toLong() and 0xFFL) shl (8 * i))
        return value
    }

    /** One axis: [samplesPerPacket] signed 16-bit little-endian samples, or null when it would reach past [limit]. */
    private fun axis(frame: ByteArray, off: Int, limit: Int): ShortArray? {
        if (off + samplesPerPacket * 2 > limit) return null
        return ShortArray(samplesPerPacket) { i ->
            ((frame[off + 2 * i].toInt() and 0xFF) or ((frame[off + 2 * i + 1].toInt() and 0xFF) shl 8)).toShort()
        }
    }
}
