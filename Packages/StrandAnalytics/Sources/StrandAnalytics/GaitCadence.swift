import Foundation

/// Step frequency from a window of raw wrist accelerometer samples (the WHOOP 4.0 realtime stream:
/// three axes at 100 Hz). Used to measure how many firmware step-counter ticks one real step costs, so
/// the counted total can be scaled; it never produces a step total by itself.
///
/// HOW. Gait is periodic with the stride (a left and a right step), and the arm follows the stride. So
/// a walking wrist shows two lines an exact octave apart: the stride line in at least one axis, and the
/// step line at twice that in the acceleration magnitude. The detector looks for that pair and reads the
/// step frequency off the upper line. It counts nothing in the time domain: on a wrist held in a pocket
/// the step's second harmonic is strong enough that a peak counter reads 10-19% high, while the spectral
/// line stays where the wearer's own count puts it.
///
/// WHAT IT CANNOT TELL APART. A gait with no stride line at all (a rigidly held arm, perfectly even
/// steps) leaves only the step line and its harmonic, which is the same picture as stride and step an
/// octave higher. The detector then answers an octave high. It does not guess from intensity; the caller
/// is expected to hold the answer against an independent bound, which `StepCalibration` does.
///
/// EVIDENCE. One strap, one wearer, 2026-10-03, three recordings of slow walking (1.31 to 1.50 steps per
/// second), two of them over stretches the wearer counted. Brisk walking and running are untested.
public enum GaitCadence {

    public struct Estimate: Equatable, Sendable {
        /// Steps per second.
        public let stepHz: Double
        /// Height of the step line in the magnitude spectrum, relative to that spectrum's maximum (0...1).
        public let stepStrength: Double
        /// Height of the stride line in its strongest axis, relative to that axis's maximum (0...1).
        public let strideStrength: Double

        public init(stepHz: Double, stepStrength: Double, strideStrength: Double) {
            self.stepHz = stepHz
            self.stepStrength = stepStrength
            self.strideStrength = strideStrength
        }
    }

    /// Step frequencies searched, in Hz: 60 to 216 steps per minute.
    public static let stepBand: ClosedRange<Double> = 1.0...3.6
    /// Shortest window analysed. Below this the two lines are not separable from their neighbours.
    public static let minimumSeconds = 20.0
    /// A line counts only when it reaches this share of its spectrum's maximum.
    static let minimumLineHeight = 0.4
    /// A line also has to stand this far above its spectrum's median. Gait lines measured 8.7 to 13.7
    /// times the median on the three recordings; the tallest bin of white noise over the same window
    /// stays under 4.2.
    static let minimumLineToMedian = 6.0
    /// How far the stride line may sit from exactly half the step line, in Hz.
    static let octaveTolerance = 0.05
    /// Standard deviation of the acceleration magnitude, in g, below which the wrist is not moving
    /// enough to carry a gait.
    static let minimumMotionG = 0.02

    private static let gridStart = 0.30
    private static let gridStep = 0.01
    private static let gridCount = 371            // 0.30 ... 4.00 Hz
    private static let peakHalfWidth = 5           // grid bins: a peak tops everything within 0.05 Hz

    /// `x`, `y`, `z` are acceleration in g, equally long, sampled at `sampleRate` Hz.
    public static func estimate(x: [Double], y: [Double], z: [Double], sampleRate: Double) -> Estimate? {
        let count = min(x.count, y.count, z.count)
        guard sampleRate > 2 * (gridStart + Double(gridCount) * gridStep),
              Double(count) / sampleRate >= minimumSeconds else { return nil }

        var magnitude = [Double](repeating: 0, count: count)
        for i in 0..<count { magnitude[i] = (x[i] * x[i] + y[i] * y[i] + z[i] * z[i]).squareRoot() }
        guard standardDeviation(magnitude) >= minimumMotionG else { return nil }

        let magnitudeSpectrum = spectrum(magnitude, sampleRate: sampleRate)
        let axisSpectra = [x, y, z].map { spectrum(Array($0.prefix(count)), sampleRate: sampleRate) }
        let magnitudePeaks = peaks(magnitudeSpectrum)
        let axisPeaks = axisSpectra.map(peaks)

        // Every (step line, stride line) pair an octave apart, scored by the product of the two heights.
        var pairs: [(bin: Int, step: Double, stride: Double)] = []
        for peak in magnitudePeaks where stepBand.contains(frequency(peak.bin)) {
            let half = frequency(peak.bin) / 2
            let stride = axisPeaks.joined()
                .filter { abs(frequency($0.bin) - half) <= octaveTolerance }
                .map(\.height).max()
            if let stride { pairs.append((peak.bin, peak.height, stride)) }
        }
        guard var best = pairs.max(by: { $0.step * $0.stride < $1.step * $1.stride }) else { return nil }
        // The gait's own fundamental is the lowest line. When a second pair sits an octave below the
        // best one and is not much weaker, the best one was the harmonic.
        if let lower = pairs.first(where: {
            abs(frequency($0.bin) - frequency(best.bin) / 2) <= octaveTolerance
                && $0.step * $0.stride >= 0.5 * best.step * best.stride
        }) {
            best = lower
        }
        return Estimate(stepHz: refinedFrequency(magnitudeSpectrum, bin: best.bin),
                        stepStrength: best.step, strideStrength: best.stride)
    }

    private static func frequency(_ bin: Int) -> Double { gridStart + Double(bin) * gridStep }

    private static func standardDeviation(_ values: [Double]) -> Double {
        let mean = values.reduce(0, +) / Double(values.count)
        return (values.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Double(values.count)).squareRoot()
    }

    /// Amplitude spectrum of the mean-removed, Hann-windowed signal on the fixed frequency grid, by one
    /// Goertzel pass per grid frequency. No FFT dependency, so the package stays buildable on Linux.
    private static func spectrum(_ signal: [Double], sampleRate: Double) -> [Double] {
        let count = signal.count
        let mean = signal.reduce(0, +) / Double(count)
        var windowed = [Double](repeating: 0, count: count)
        for i in 0..<count {
            let hann = 0.5 - 0.5 * cos(2 * Double.pi * Double(i) / Double(count - 1))
            windowed[i] = (signal[i] - mean) * hann
        }
        var out = [Double](repeating: 0, count: gridCount)
        for bin in 0..<gridCount {
            let omega = 2 * Double.pi * frequency(bin) / sampleRate
            let coefficient = 2 * cos(omega)
            var previous = 0.0, beforePrevious = 0.0
            for sample in windowed {
                let current = sample + coefficient * previous - beforePrevious
                beforePrevious = previous
                previous = current
            }
            let power = previous * previous + beforePrevious * beforePrevious
                - coefficient * previous * beforePrevious
            out[bin] = max(power, 0).squareRoot()
        }
        return out
    }

    /// Local maxima that reach `minimumLineHeight` of the spectrum's maximum and `minimumLineToMedian`
    /// times its median. Heights are relative to the maximum.
    private static func peaks(_ spectrum: [Double]) -> [(bin: Int, height: Double)] {
        guard let top = spectrum.max(), top > 0 else { return [] }
        let floor = spectrum.sorted()[spectrum.count / 2] * minimumLineToMedian
        var out: [(bin: Int, height: Double)] = []
        for bin in spectrum.indices {
            let height = spectrum[bin] / top
            guard height >= minimumLineHeight, spectrum[bin] >= floor else { continue }
            let lower = max(bin - peakHalfWidth, 0), upper = min(bin + peakHalfWidth, spectrum.count - 1)
            if spectrum[lower...upper].allSatisfy({ $0 <= spectrum[bin] }) { out.append((bin, height)) }
        }
        return out
    }

    /// Parabolic interpolation of the peak position across its two neighbours.
    private static func refinedFrequency(_ spectrum: [Double], bin: Int) -> Double {
        guard bin > 0, bin < spectrum.count - 1 else { return frequency(bin) }
        let left = spectrum[bin - 1], centre = spectrum[bin], right = spectrum[bin + 1]
        let curvature = left - 2 * centre + right
        guard curvature < 0 else { return frequency(bin) }
        return frequency(bin) + 0.5 * (left - right) / curvature * gridStep
    }
}
