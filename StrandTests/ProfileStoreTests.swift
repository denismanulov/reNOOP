import XCTest
@testable import Strand

/// What `ProfileStore` writes back when it is made: a date of birth that came from storage, never the
/// age-30 default. A launch before the phone's first unlock reads every default as absent, so a default
/// written there replaces the wearer's real date.
final class ProfileStoreTests: XCTestCase {

    @MainActor
    func testWithNothingStoredTheDefaultAgeIsShownAndNothingIsWritten() {
        let defaults = FriendsTestDefaults()
        let profile = ProfileStore(defaults: defaults)
        XCTAssertEqual(profile.age, 30)
        XCTAssertNil(defaults.object(forKey: "profile.dateOfBirth"), "the default is not written")
        XCTAssertNil(defaults.object(forKey: "profile.age"))
    }

    @MainActor
    func testAStoredDateOfBirthIsKeptAndItsAgeMirrored() throws {
        let defaults = FriendsTestDefaults()
        let born = ProfileStore.dateOfBirth(forAge: 17)
        defaults.set(born, forKey: "profile.dateOfBirth")
        let profile = ProfileStore(defaults: defaults)
        XCTAssertEqual(profile.dateOfBirth, born)
        XCTAssertEqual(defaults.object(forKey: "profile.dateOfBirth") as? Date, born)
        XCTAssertEqual(defaults.object(forKey: "profile.age") as? Int, 17)
    }

    /// A pre-#146 install, or a `.noopbak` restore, has only the whole-number age.
    @MainActor
    func testAStoredAgeAloneBecomesAStoredDateOfBirth() {
        let defaults = FriendsTestDefaults()
        defaults.set(41, forKey: "profile.age")
        let profile = ProfileStore(defaults: defaults)
        XCTAssertEqual(profile.age, 41)
        XCTAssertNotNil(defaults.object(forKey: "profile.dateOfBirth") as? Date)
        XCTAssertEqual(defaults.object(forKey: "profile.age") as? Int, 41)
    }

    /// Once the wearer answers, the date is stored like any other edit.
    @MainActor
    func testSettingTheDateOfBirthStoresIt() {
        let defaults = FriendsTestDefaults()
        let profile = ProfileStore(defaults: defaults)
        let born = ProfileStore.dateOfBirth(forAge: 25)
        profile.dateOfBirth = born
        XCTAssertEqual(defaults.object(forKey: "profile.dateOfBirth") as? Date, born)
        XCTAssertEqual(defaults.object(forKey: "profile.age") as? Int, 25)
    }
}
