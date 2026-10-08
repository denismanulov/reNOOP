import XCTest
import WhoopProtocol
@testable import Strand

/// #1617: the Blood Oxygen empty state must not promise a reading that is not coming.
///
/// Both WHOOP generations bank a strap-computed percentage (the 5/MG at `@82` of a v18 record, the 4.0
/// at `@86` of a 104-byte v24 record) and both ship it as an unverified estimate that is off by default.
/// So an empty page on either strap means the switch is off, or it is on and there are no nights yet.
/// Same cases as Kotlin `Spo2EmptyStateTest`.
final class Spo2EmptyStateTests: XCTestCase {

    /// A 4.0 with the estimate off: actionable, so name the switch, exactly as on a 5/MG.
    func testWhoop4WithTheEstimateOffPointsAtTheToggle() {
        XCTAssertEqual(Spo2EmptyState.resolve(key: "spo2", family: .whoop4, candidateDisplayOn: false),
                       .estimateOff)
    }

    /// A 4.0 with it on just needs nights.
    func testWhoop4WithTheEstimateOnKeepsTheDefaultCopy() {
        XCTAssertEqual(Spo2EmptyState.resolve(key: "spo2", family: .whoop4, candidateDisplayOn: true),
                       .standard)
    }

    /// A nil family is a positively non-WHOOP brand or a row not in the registry. Either way it has no
    /// strap-estimate switch to be pointed at, whatever the stored toggle says.
    func testANonWhoopOrUnresolvedDeviceIsNeverPointedAtTheStrapEstimateSwitch() {
        for on in [true, false] {
            XCTAssertEqual(Spo2EmptyState.resolve(key: "spo2", family: nil, candidateDisplayOn: on),
                           .standard)
        }
    }

    func testWhoop5WithTheEstimateOffPointsAtTheToggle() {
        XCTAssertEqual(Spo2EmptyState.resolve(key: "spo2", family: .whoop5, candidateDisplayOn: false),
                       .estimateOff)
    }

    func testWhoop5WithTheEstimateOnKeepsTheDefaultCopy() {
        XCTAssertEqual(Spo2EmptyState.resolve(key: "spo2", family: .whoop5, candidateDisplayOn: true),
                       .standard)
    }

    /// Every other vital is untouched on both strap generations: this is a Blood Oxygen carve-out.
    func testOtherVitalsKeepTheDefaultOnEitherStrap() {
        for key in ["hrv", "resting_hr", "skin_temp", "resp_rate", "fitness_age"] {
            for family in [DeviceFamily.whoop5, .whoop4] {
                for on in [true, false] {
                    XCTAssertEqual(
                        Spo2EmptyState.resolve(key: key, family: family, candidateDisplayOn: on),
                        .standard, "\(key) must keep the default empty state")
                }
            }
        }
    }
}
