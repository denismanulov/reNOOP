import XCTest
@testable import Strand

/// The step-calibration stepper's grid. A WHOOP 4.0 firmware counter runs at about 1.26 ticks per step
/// (2026-10-03: 1,108 ticks against 879 phone-counted steps), which a 0.1 grid cannot express, so the
/// grid is 0.01 below 1.5.
@MainActor
final class StepCalibrationStepperTests: XCTestCase {

    func testIncrementBands() {
        XCTAssertEqual(ProfileStore.stepScaleIncrement(for: 1.0), 0.01, accuracy: 1e-9)
        XCTAssertEqual(ProfileStore.stepScaleIncrement(for: 1.49), 0.01, accuracy: 1e-9)
        XCTAssertEqual(ProfileStore.stepScaleIncrement(for: 1.5), 0.1, accuracy: 1e-9)
        XCTAssertEqual(ProfileStore.stepScaleIncrement(for: 1.9), 0.1, accuracy: 1e-9)
        XCTAssertEqual(ProfileStore.stepScaleIncrement(for: 2.0), 0.5, accuracy: 1e-9)
        XCTAssertEqual(ProfileStore.stepScaleIncrement(for: 5.0), 1.0, accuracy: 1e-9)
    }

    func testTheMeasuredRatioIsReachableFromTheDefault() {
        var value = 1.0
        for _ in 0..<26 { value = ProfileStore.steppedStepScale(value, up: true) }
        XCTAssertEqual(value, 1.26, accuracy: 1e-9)
    }

    func testBandBoundariesAreSymmetric() {
        XCTAssertEqual(ProfileStore.steppedStepScale(1.49, up: true), 1.5, accuracy: 1e-9)
        XCTAssertEqual(ProfileStore.steppedStepScale(1.5, up: false), 1.49, accuracy: 1e-9)
        XCTAssertEqual(ProfileStore.steppedStepScale(1.5, up: true), 1.6, accuracy: 1e-9)
        XCTAssertEqual(ProfileStore.steppedStepScale(2.0, up: false), 1.9, accuracy: 1e-9)
        XCTAssertEqual(ProfileStore.steppedStepScale(0.5, up: false), 0.5, accuracy: 1e-9)
        XCTAssertEqual(ProfileStore.steppedStepScale(30.0, up: true), 30.0, accuracy: 1e-9)
    }
}
