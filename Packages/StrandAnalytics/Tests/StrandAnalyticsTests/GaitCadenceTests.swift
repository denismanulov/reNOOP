import XCTest
@testable import StrandAnalytics

final class GaitCadenceTests: XCTestCase {

    /// A fixed-seed generator, so the noise in these signals is the same on every run.
    private struct SeededGenerator: RandomNumberGenerator {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
    }

    /// A wrist during gait: the arm swings at the stride frequency (x, z), each step lands a vertical
    /// impact at twice that (y, on top of gravity), with an optional second harmonic of the step.
    private func gait(stepHz: Double, seconds: Double = 40, rate: Double = 100,
                      armG: Double = 0.25, stepG: Double = 0.2, harmonicG: Double = 0,
                      noiseG: Double = 0.02) -> (x: [Double], y: [Double], z: [Double]) {
        var generator = SeededGenerator(state: UInt64(stepHz * 1000) &+ 7)
        func noise() -> Double { Double.random(in: -noiseG...noiseG, using: &generator) }
        var x: [Double] = [], y: [Double] = [], z: [Double] = []
        for i in 0..<Int(seconds * rate) {
            let t = Double(i) / rate
            let stride = 2 * Double.pi * (stepHz / 2) * t
            let step = 2 * Double.pi * stepHz * t
            x.append(armG * sin(stride) + noise())
            y.append(1 + stepG * sin(step) + harmonicG * sin(2 * step + 0.7) + noise())
            z.append(0.6 * armG * sin(stride + 1.1) + noise())
        }
        return (x, y, z)
    }

    func testRecoversSeveralInjectedCadences() {
        // More than one value, so a peak the method manufactures by itself would not pass.
        for stepHz in [1.20, 1.55, 1.90, 2.35, 2.90] {
            let s = gait(stepHz: stepHz)
            let estimate = GaitCadence.estimate(x: s.x, y: s.y, z: s.z, sampleRate: 100)
            XCTAssertEqual(estimate?.stepHz ?? 0, stepHz, accuracy: 0.02, "stepHz \(stepHz)")
        }
    }

    func testAStrongStepHarmonicDoesNotMoveTheAnswerAnOctaveUp() {
        // The hand-in-pocket shape: the step's second harmonic nearly as tall as the step line.
        let s = gait(stepHz: 1.3, armG: 0.12, stepG: 0.2, harmonicG: 0.15)
        XCTAssertEqual(GaitCadence.estimate(x: s.x, y: s.y, z: s.z, sampleRate: 100)?.stepHz ?? 0,
                       1.3, accuracy: 0.02)
    }

    func testWithoutAStrideLineTheAnswerIsAnOctaveHigh() {
        // No arm swing at all: only the step line and its harmonic remain, which is the same picture as
        // stride and step an octave up. The detector does not guess; `StepCalibration`'s ratio band is
        // what rejects this reading (see `StepCalibrationTests`).
        let s = gait(stepHz: 1.3, armG: 0, stepG: 0.2, harmonicG: 0.15)
        var x = s.x
        for i in x.indices { x[i] += 0.15 * sin(2 * Double.pi * 1.3 * Double(i) / 100) }
        XCTAssertEqual(GaitCadence.estimate(x: x, y: s.y, z: s.z, sampleRate: 100)?.stepHz ?? 0,
                       2.6, accuracy: 0.03)
    }

    func testNoGaitIsNil() {
        let still = gait(stepHz: 1.5, armG: 0, stepG: 0, noiseG: 0.005)
        XCTAssertNil(GaitCadence.estimate(x: still.x, y: still.y, z: still.z, sampleRate: 100))

        var generator = SeededGenerator(state: 42)
        let fidget = (0..<4000).map { _ in Double.random(in: -0.3...0.3, using: &generator) }
        XCTAssertNil(GaitCadence.estimate(x: fidget, y: fidget.map { 1 + $0 }, z: fidget.reversed(),
                                          sampleRate: 100))
    }

    func testAShortWindowIsNil() {
        let s = gait(stepHz: 1.6, seconds: 12)
        XCTAssertNil(GaitCadence.estimate(x: s.x, y: s.y, z: s.z, sampleRate: 100))
    }

    // MARK: - Real recordings

    func testArmSwingingRecording() {
        // The wearer counted 100 steps over the whole stretch this window is cut from, which took
        // between 75 and 80 seconds: 1.25 to 1.33 steps per second.
        let s = GaitFixtures.axes(GaitFixtures.armSwinging)
        let estimate = GaitCadence.estimate(x: s.x, y: s.y, z: s.z, sampleRate: GaitFixtures.sampleRate)
        XCTAssertEqual(estimate?.stepHz ?? 0, 1.34, accuracy: 0.02)
    }

    func testHandInPocketRecording() {
        // Same wearer, same pace, strap hand in a pocket: again 100 counted steps in about 80 seconds.
        // A time-domain peak count over this window reads 69 to 75 steps where the line gives 63.
        let s = GaitFixtures.axes(GaitFixtures.handInPocket)
        let estimate = GaitCadence.estimate(x: s.x, y: s.y, z: s.z, sampleRate: GaitFixtures.sampleRate)
        XCTAssertEqual(estimate?.stepHz ?? 0, 1.31, accuracy: 0.02)
    }

    func testWalkRecording() {
        let s = GaitFixtures.axes(GaitFixtures.walk)
        let estimate = GaitCadence.estimate(x: s.x, y: s.y, z: s.z, sampleRate: GaitFixtures.sampleRate)
        XCTAssertEqual(estimate?.stepHz ?? 0, 1.50, accuracy: 0.02)
    }
}
