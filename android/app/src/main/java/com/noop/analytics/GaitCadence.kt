package com.noop.analytics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Step frequency from a window of raw wrist accelerometer samples (the WHOOP 4.0 realtime stream:
 * three axes at 100 Hz). Used to measure how many firmware step-counter ticks one real step costs, so
 * the counted total can be scaled; it never produces a step total by itself.
 *
 * Kotlin twin of the Swift `GaitCadence` (StrandAnalytics/GaitCadence.swift): the same operations in the
 * same order, pinned by `GaitCadenceParityOracleTest` against the Swift build's own output.
 *
 * HOW. Gait is periodic with the stride (a left and a right step), and the arm follows the stride. So
 * a walking wrist shows two lines an exact octave apart: the stride line in at least one axis, and the
 * step line at twice that in the acceleration magnitude. The detector looks for that pair and reads the
 * step frequency off the upper line. It counts nothing in the time domain: on a wrist held in a pocket
 * the step's second harmonic is strong enough that a peak counter reads 10-19% high, while the spectral
 * line stays where the wearer's own count puts it.
 *
 * WHAT IT CANNOT TELL APART. A gait with no stride line at all (a rigidly held arm, perfectly even
 * steps) leaves only the step line and its harmonic, which is the same picture as stride and step an
 * octave higher. The detector then answers an octave high. It does not guess from intensity; the caller
 * is expected to hold the answer against an independent bound, which [StepCalibration] does.
 *
 * EVIDENCE. One strap, one wearer, 2026-10-03, three recordings of slow walking (1.31 to 1.50 steps per
 * second), two of them over stretches the wearer counted. Brisk walking and running are untested.
 */
object GaitCadence {

    data class Estimate(
        /** Steps per second. */
        val stepHz: Double,
        /** Height of the step line in the magnitude spectrum, relative to that spectrum's maximum (0...1). */
        val stepStrength: Double,
        /** Height of the stride line in its strongest axis, relative to that axis's maximum (0...1). */
        val strideStrength: Double,
    )

    /** Step frequencies searched, in Hz: 60 to 216 steps per minute. */
    val STEP_BAND: ClosedFloatingPointRange<Double> = 1.0..3.6

    /** Shortest window analysed. Below this the two lines are not separable from their neighbours. */
    const val MINIMUM_SECONDS = 20.0

    /** A line counts only when it reaches this share of its spectrum's maximum. */
    internal const val MINIMUM_LINE_HEIGHT = 0.4

    /**
     * A line also has to stand this far above its spectrum's median. Gait lines measured 8.7 to 13.7
     * times the median on the three recordings; the tallest bin of white noise over the same window
     * stays under 4.2.
     */
    internal const val MINIMUM_LINE_TO_MEDIAN = 6.0

    /** How far the stride line may sit from exactly half the step line, in Hz. */
    internal const val OCTAVE_TOLERANCE = 0.05

    /**
     * Standard deviation of the acceleration magnitude, in g, below which the wrist is not moving
     * enough to carry a gait.
     */
    internal const val MINIMUM_MOTION_G = 0.02

    private const val GRID_START = 0.30
    private const val GRID_STEP = 0.01
    private const val GRID_COUNT = 371            // 0.30 ... 4.00 Hz
    private const val PEAK_HALF_WIDTH = 5         // grid bins: a peak tops everything within 0.05 Hz

    private class Peak(val bin: Int, val height: Double)

    private class LinePair(val bin: Int, val step: Double, val stride: Double)

    /**
     * [x], [y], [z] are acceleration in g, sampled at [sampleRate] Hz. When their lengths differ the
     * shortest decides the window, as in Swift.
     *
     * Swift twin: `GaitCadence.estimate(x:y:z:sampleRate:)`.
     */
    fun estimate(x: DoubleArray, y: DoubleArray, z: DoubleArray, sampleRate: Double): Estimate? {
        val count = minOf(x.size, y.size, z.size)
        if (!(sampleRate > 2 * (GRID_START + GRID_COUNT.toDouble() * GRID_STEP) &&
                count.toDouble() / sampleRate >= MINIMUM_SECONDS)
        ) return null

        val magnitude = DoubleArray(count)
        for (i in 0 until count) magnitude[i] = sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i])
        // Negated `>=`, not `<`: a NaN deviation (a NaN or infinite sample) must leave here, as Swift's guard does.
        if (!(standardDeviation(magnitude) >= MINIMUM_MOTION_G)) return null

        val magnitudeSpectrum = spectrum(magnitude, count, sampleRate)
        val magnitudePeaks = peaks(magnitudeSpectrum)
        val axisPeaks = listOf(x, y, z).map { peaks(spectrum(it, count, sampleRate)) }

        // Every (step line, stride line) pair an octave apart, scored by the product of the two heights.
        val pairs = ArrayList<LinePair>()
        for (peak in magnitudePeaks) {
            if (frequency(peak.bin) !in STEP_BAND) continue
            val half = frequency(peak.bin) / 2
            var stride: Double? = null
            for (axis in axisPeaks) {
                for (candidate in axis) {
                    if (abs(frequency(candidate.bin) - half) <= OCTAVE_TOLERANCE &&
                        (stride == null || stride < candidate.height)
                    ) stride = candidate.height
                }
            }
            if (stride != null) pairs.add(LinePair(peak.bin, peak.height, stride))
        }
        // The first of equal maxima, as Swift's `max(by:)` keeps it.
        var best = pairs.firstOrNull() ?: return null
        for (i in 1 until pairs.size) {
            if (best.step * best.stride < pairs[i].step * pairs[i].stride) best = pairs[i]
        }
        // The gait's own fundamental is the lowest line. When a second pair sits an octave below the
        // best one and is not much weaker, the best one was the harmonic.
        val top = best
        val lower = pairs.firstOrNull {
            abs(frequency(it.bin) - frequency(top.bin) / 2) <= OCTAVE_TOLERANCE &&
                it.step * it.stride >= 0.5 * top.step * top.stride
        }
        if (lower != null) best = lower
        return Estimate(
            stepHz = refinedFrequency(magnitudeSpectrum, best.bin),
            stepStrength = best.step,
            strideStrength = best.stride,
        )
    }

    /** Centre frequency of a grid bin, in Hz. Swift twin: `GaitCadence.frequency(_:)`. */
    private fun frequency(bin: Int): Double = GRID_START + bin.toDouble() * GRID_STEP

    /** Population standard deviation, summed in index order. Swift twin: `GaitCadence.standardDeviation(_:)`. */
    private fun standardDeviation(values: DoubleArray): Double {
        var sum = 0.0
        for (value in values) sum += value
        val mean = sum / values.size.toDouble()
        var squares = 0.0
        for (value in values) squares += (value - mean) * (value - mean)
        return sqrt(squares / values.size.toDouble())
    }

    /**
     * Amplitude spectrum of the mean-removed, Hann-windowed first [count] samples of [signal] on the fixed
     * frequency grid, by one Goertzel pass per grid frequency.
     *
     * `StrictMath.cos`, not `kotlin.math.cos`: its results are fixed by the Java specification (fdlibm),
     * so the unit-test JVM and an Android runtime are meant to agree, where the platform routine may
     * differ between them in the last bit. Neither reproduces Apple's `cos` bit for bit (measured, see
     * `GaitCadenceParityOracleTest`), so an estimate can differ from the Swift one in its last few bits.
     * Everything else in this file is the same arithmetic in the same order.
     *
     * Swift twin: `GaitCadence.spectrum(_:sampleRate:)`, which is handed the already-shortened array.
     */
    private fun spectrum(signal: DoubleArray, count: Int, sampleRate: Double): DoubleArray {
        var sum = 0.0
        for (i in 0 until count) sum += signal[i]
        val mean = sum / count.toDouble()
        val windowed = DoubleArray(count)
        for (i in 0 until count) {
            val hann = 0.5 - 0.5 * StrictMath.cos(2 * PI * i.toDouble() / (count - 1).toDouble())
            windowed[i] = (signal[i] - mean) * hann
        }
        val out = DoubleArray(GRID_COUNT)
        for (bin in 0 until GRID_COUNT) {
            val omega = 2 * PI * frequency(bin) / sampleRate
            val coefficient = 2 * StrictMath.cos(omega)
            var previous = 0.0
            var beforePrevious = 0.0
            for (sample in windowed) {
                val current = sample + coefficient * previous - beforePrevious
                beforePrevious = previous
                previous = current
            }
            val power = previous * previous + beforePrevious * beforePrevious -
                coefficient * previous * beforePrevious
            // Swift's `max(power, 0)`, spelled out: `0 >= power ? 0 : power`.
            out[bin] = sqrt(if (0.0 >= power) 0.0 else power)
        }
        return out
    }

    /**
     * Local maxima that reach [MINIMUM_LINE_HEIGHT] of the spectrum's maximum and [MINIMUM_LINE_TO_MEDIAN]
     * times its median. Heights are relative to the maximum.
     *
     * Swift twin: `GaitCadence.peaks(_:)`.
     */
    private fun peaks(spectrum: DoubleArray): List<Peak> {
        if (spectrum.isEmpty()) return emptyList()
        var top = spectrum[0]
        for (i in 1 until spectrum.size) if (top < spectrum[i]) top = spectrum[i]
        if (!(top > 0)) return emptyList()
        val floor = spectrum.sortedArray()[spectrum.size / 2] * MINIMUM_LINE_TO_MEDIAN
        val out = ArrayList<Peak>()
        for (bin in spectrum.indices) {
            val height = spectrum[bin] / top
            if (!(height >= MINIMUM_LINE_HEIGHT && spectrum[bin] >= floor)) continue
            val lower = maxOf(bin - PEAK_HALF_WIDTH, 0)
            val upper = minOf(bin + PEAK_HALF_WIDTH, spectrum.size - 1)
            var tops = true
            for (k in lower..upper) {
                if (!(spectrum[k] <= spectrum[bin])) { tops = false; break }
            }
            if (tops) out.add(Peak(bin, height))
        }
        return out
    }

    /**
     * Parabolic interpolation of the peak position across its two neighbours.
     * Swift twin: `GaitCadence.refinedFrequency(_:bin:)`.
     */
    private fun refinedFrequency(spectrum: DoubleArray, bin: Int): Double {
        if (!(bin > 0 && bin < spectrum.size - 1)) return frequency(bin)
        val left = spectrum[bin - 1]
        val centre = spectrum[bin]
        val right = spectrum[bin + 1]
        val curvature = left - 2 * centre + right
        if (!(curvature < 0)) return frequency(bin)
        return frequency(bin) + 0.5 * (left - right) / curvature * GRID_STEP
    }
}
