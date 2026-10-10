import XCTest
@testable import Strand

/// The words for what waits for an answer, and the invite's QR.
final class FriendsRequestsTests: XCTestCase {

    private func claim(_ kind: FriendsClaim.Kind, platform: String? = "android") -> FriendsClaim {
        FriendsClaim(id: 1, kind: kind, code: "481902", state: .pending, platform: platform,
                     createdAt: 1_791_540_000, maturesAt: 1_791_712_800)
    }

    func testAPhoneIsNamedByItsPlatformAndAnUnknownOneIsStillAPhone() {
        XCTAssertFalse(FriendsRequestText.device("ios").isEmpty)
        XCTAssertNotEqual(FriendsRequestText.device("ios"), FriendsRequestText.device("android"))
        XCTAssertNotEqual(FriendsRequestText.device("mac"), FriendsRequestText.device("android"))
        XCTAssertEqual(FriendsRequestText.device("watch"), FriendsRequestText.device(nil))
    }

    /// A request to join carries the code both phones show; a request for the strap has none to compare.
    func testARequestToJoinShowsItsCodeAndTheTwoKindsReadDifferently() {
        let join = claim(.join), take = claim(.take)
        XCTAssertTrue(FriendsRequestText.detail(join).contains("481 902"))
        XCTAssertTrue(FriendsRequestText.detail(join).contains(FriendsRequestText.device("android")))
        XCTAssertFalse(FriendsRequestText.detail(take).contains("481"))
        XCTAssertNotEqual(FriendsRequestText.title(join), FriendsRequestText.title(take))
    }

    func testACodeIsReadOutInTwoGroups() {
        XCTAssertEqual(FriendsWelcome.spaced("481902"), "481 902")
        XCTAssertEqual(FriendsWelcome.spaced("12"), "12", "anything that is not six digits is left alone")
    }

    /// A moment days away is named by its date. A weekday and a time alone would read as today for
    /// the end of a seven-day wait, which falls on the weekday it began on.
    func testAMomentDaysAwayIsNamedByItsDate() throws {
        let utc = try XCTUnwrap(TimeZone(identifier: "UTC"))
        // 15 October 2026, 22:53:20 UTC, a Thursday.
        let english = FriendsWelcome.moment(1_792_104_800, locale: Locale(identifier: "en_GB"), timeZone: utc)
        XCTAssertTrue(english.contains("15"), english)
        XCTAssertTrue(english.contains("October"), english)
        XCTAssertTrue(english.contains("22:53"), english)
        XCTAssertFalse(english.contains("Thursday"), english)
        let russian = FriendsWelcome.moment(1_792_104_800, locale: Locale(identifier: "ru_RU"), timeZone: utc)
        XCTAssertTrue(russian.contains("15 октября"), russian)
        XCTAssertTrue(russian.contains("22:53"), russian)
        XCTAssertFalse(russian.contains("четверг"), russian)
    }

    /// How long ago a phone joined or was seen counts from the clock handed in, which the pages take
    /// from the server: a phone whose own clock is wrong reads the same ages.
    func testAPhonesAgesCountFromTheClockHandedIn() {
        let phone = { (now: Int) in
            FriendsDevice(id: "k2", platform: "android", addedAt: now - 2 * 3_600, lastSeenAt: now - 300,
                          probationUntil: nil, current: false)
        }
        let device = Int(Date().timeIntervalSince1970)
        let wrong = 1_000_000_000
        XCTAssertEqual(FriendsRequestText.joined(phone(wrong), now: Date(timeIntervalSince1970: TimeInterval(wrong))),
                       FriendsRequestText.joined(phone(device), now: Date(timeIntervalSince1970: TimeInterval(device))))
        XCTAssertEqual(FriendsRequestText.seen(phone(wrong), now: Date(timeIntervalSince1970: TimeInterval(wrong))),
                       FriendsRequestText.seen(phone(device), now: Date(timeIntervalSince1970: TimeInterval(device))))
        XCTAssertNotEqual(FriendsRequestText.seen(phone(wrong), now: Date(timeIntervalSince1970: TimeInterval(wrong))),
                          FriendsRequestText.seen(phone(wrong), now: Date(timeIntervalSince1970: TimeInterval(device))),
                          "the same phone against the wrong clock reads differently")
    }

    func testAnInviteLinkBecomesASquareQRCode() throws {
        let image = try XCTUnwrap(FriendsQRCode.image("https://renoop.duckdns.org/i/K7QM2-XRD4P", side: 660))
        XCTAssertEqual(image.width, image.height)
        XCTAssertGreaterThan(image.width, 300)
        XCTAssertLessThanOrEqual(image.width, 660)
    }
}
