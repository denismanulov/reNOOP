package com.noop.analytics

import kotlin.math.PI

/**
 * The input signals of the gait tests, built the way the Swift suite builds them so both platforms
 * analyse the same samples. `GaitCadenceParityOracleTest` holds them to the Swift build: the noise stream
 * and the integer-built signals bit for bit, and the signals that pass through `sin` to the last place or
 * two, which is as close as two maths libraries come (`StrictMath.sin` here, so every JVM builds the same).
 */
internal object GaitTestSignals {

    /**
     * The Swift tests' fixed-seed generator (SplitMix64) together with the two standard-library routines
     * the Swift tests draw through: `RandomNumberGenerator.next(upperBound:)` and
     * `Double.random(in:using:)` over a closed range. Reimplemented here because the noise in the Swift
     * signals comes out of them; the oracle test pins the stream bit for bit.
     */
    class SeededGenerator(private var state: Long) {

        fun next(): Long {
            state += 0x9E37_79B9_7F4A_7C15uL.toLong()
            var z = state
            z = (z xor (z ushr 30)) * 0xBF58_476D_1CE4_E5B9uL.toLong()
            z = (z xor (z ushr 27)) * 0x94D0_49BB_1331_11EBuL.toLong()
            return z xor (z ushr 31)
        }

        /** Swift's `Double.random(in: lower...upper, using: &generator)`. */
        fun nextDouble(lower: Double, upper: Double): Double {
            val delta = upper - lower
            val maxSignificand = 1L shl 53
            val rand = nextBelow(maxSignificand + 1)
            if (rand == maxSignificand) return upper
            val unitRandom = rand.toDouble() * (Math.ulp(1.0) / 2)
            return delta * unitRandom + lower
        }

        /** Swift's `next(upperBound:)`: Lemire's method. [upperBound] is unsigned and below 2^63 here. */
        private fun nextBelow(upperBound: Long): Long {
            var random = next()
            var low = random * upperBound
            if (java.lang.Long.compareUnsigned(low, upperBound) < 0) {
                val threshold = java.lang.Long.remainderUnsigned(-upperBound, upperBound)
                while (java.lang.Long.compareUnsigned(low, threshold) < 0) {
                    random = next()
                    low = random * upperBound
                }
            }
            return unsignedMultiplyHigh(random, upperBound)
        }

        private fun unsignedMultiplyHigh(a: Long, b: Long): Long =
            Math.multiplyHigh(a, b) + ((a shr 63) and b) + ((b shr 63) and a)
    }

    /**
     * A wrist during gait: the arm swings at the stride frequency (x, z), each step lands a vertical
     * impact at twice that (y, on top of gravity), with an optional second harmonic of the step.
     */
    fun gait(
        stepHz: Double, seconds: Double = 40.0, rate: Double = 100.0,
        armG: Double = 0.25, stepG: Double = 0.2, harmonicG: Double = 0.0, noiseG: Double = 0.02,
    ): GaitFixtures.Axes {
        val generator = SeededGenerator((stepHz * 1000).toLong() + 7)
        fun noise(): Double = generator.nextDouble(-noiseG, noiseG)
        val count = (seconds * rate).toInt()
        val x = DoubleArray(count)
        val y = DoubleArray(count)
        val z = DoubleArray(count)
        for (i in 0 until count) {
            val t = i.toDouble() / rate
            val stride = 2 * PI * (stepHz / 2) * t
            val step = 2 * PI * stepHz * t
            x[i] = armG * StrictMath.sin(stride) + noise()
            y[i] = 1 + stepG * StrictMath.sin(step) + harmonicG * StrictMath.sin(2 * step + 0.7) + noise()
            z[i] = 0.6 * armG * StrictMath.sin(stride + 1.1) + noise()
        }
        return GaitFixtures.Axes(x, y, z)
    }

    /** The hand-in-pocket shape with no arm swing at all, plus a step-rate line in x (the Swift test's signal). */
    fun noStrideLine(): GaitFixtures.Axes {
        val s = gait(stepHz = 1.3, armG = 0.0, stepG = 0.2, harmonicG = 0.15)
        val x = s.x.copyOf()
        for (i in x.indices) x[i] += 0.15 * StrictMath.sin(2 * PI * 1.3 * i.toDouble() / 100)
        return GaitFixtures.Axes(x, s.y, s.z)
    }

    /** Uniform noise on every axis, no periodic motion. */
    fun fidget(): GaitFixtures.Axes {
        val generator = SeededGenerator(42)
        val fidget = DoubleArray(4000) { generator.nextDouble(-0.3, 0.3) }
        return GaitFixtures.Axes(fidget, DoubleArray(fidget.size) { 1 + fidget[it] }, fidget.reversedArray())
    }

    // Signals with no trigonometry in them: whole counts from integer arithmetic, divided by 4096. Both
    // languages build these bit for bit whatever their maths library does.

    class Lcg(private var s: Long) {
        fun next(): Int {
            s = s * 6364136223846793005L + 1442695040888963407L
            return (s ushr 33).toInt()
        }

        fun centred(amplitude: Int): Int = next() % (2 * amplitude + 1) - amplitude
    }

    /** Triangle wave in counts: period [p] samples, peak [amp], phase [ph] samples. */
    private fun tri(i: Int, p: Int, amp: Int, ph: Int): Int {
        val k = (i + ph) % p
        val half = p / 2
        val up = if (k < half) k else p - k
        return amp * (2 * up - half) / half
    }

    fun counts(n: Int, stepPeriod: Int, arm: Int, step: Int, harmonic: Int, noise: Int, seed: Long): GaitFixtures.Axes {
        val g = Lcg(seed)
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        val z = DoubleArray(n)
        for (i in 0 until n) {
            val nx = if (noise > 0) g.centred(noise) else 0
            val ny = if (noise > 0) g.centred(noise) else 0
            val nz = if (noise > 0) g.centred(noise) else 0
            val xi = tri(i, 2 * stepPeriod, arm, 0) + nx
            val yi = 4096 + tri(i, stepPeriod, step, 3) +
                (if (harmonic > 0) tri(i, maxOf(stepPeriod / 2, 2), harmonic, 1) else 0) + ny
            val zi = tri(i, 2 * stepPeriod, arm * 3 / 5, stepPeriod / 3) + nz
            x[i] = xi.toDouble() / 4096
            y[i] = yi.toDouble() / 4096
            z[i] = zi.toDouble() / 4096
        }
        return GaitFixtures.Axes(x, y, z)
    }
}
