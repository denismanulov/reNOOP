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

    func testAnInviteLinkBecomesASquareQRCode() throws {
        let image = try XCTUnwrap(FriendsQRCode.image("https://renoop.duckdns.org/i/K7QM2-XRD4P", side: 660))
        XCTAssertEqual(image.width, image.height)
        XCTAssertGreaterThan(image.width, 300)
        XCTAssertLessThanOrEqual(image.width, 660)
    }
}
