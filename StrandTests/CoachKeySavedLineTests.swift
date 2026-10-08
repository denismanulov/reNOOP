import XCTest
@testable import Strand

/// The coach's API key field empties on save and the stored key is never shown again, so a saved key
/// and no key looked the same. The field carries a line saying a key is saved while one is stored and
/// nothing is typed.
final class CoachKeySavedLineTests: XCTestCase {

    func testShownWhileAKeyIsStoredAndNothingIsTyped() {
        XCTAssertTrue(CoachSettingsView.showsKeySavedLine(hasKey: true, keyDraft: ""))
    }

    func testNotShownWithoutAStoredKey() {
        XCTAssertFalse(CoachSettingsView.showsKeySavedLine(hasKey: false, keyDraft: ""))
        XCTAssertFalse(CoachSettingsView.showsKeySavedLine(hasKey: false, keyDraft: "sk-new"))
    }

    /// Once a replacement is being typed the Update Key row takes the line's place.
    func testNotShownWhileAReplacementIsBeingTyped() {
        XCTAssertFalse(CoachSettingsView.showsKeySavedLine(hasKey: true, keyDraft: "sk-new"))
    }

    /// Blank input is not a key: the save row treats it as empty, and so does the line.
    func testWhitespaceCountsAsNothingTyped() {
        XCTAssertTrue(CoachSettingsView.showsKeySavedLine(hasKey: true, keyDraft: "  \n"))
    }
}
