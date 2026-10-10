import XCTest
@testable import StrandAnalytics

/// The friends service's text rules, pinned to the vectors in `friends-server/README.md`. The server's
/// own tests and the Kotlin twin assert the same literals.
final class FriendsWireTests: XCTestCase {

    func testTheSigningStringIsTheContracts() {
        let text = FriendsWire.signingString(
            method: "put", target: "/v2/me/days/2026-10-10", time: 1_791_540_000, nonce: "AAAAAAAAAAAAAAAAAAAAAA",
            bodyHashHex: "a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445")
        XCTAssertEqual(text, "renoop-friends-v2\nPUT\n/v2/me/days/2026-10-10\n1791540000\nAAAAAAAAAAAAAAAAAAAAAA\n"
                       + "a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445")
        XCTAssertFalse(text.hasSuffix("\n"))
    }

    func testTheStrapHandleInputCarriesItsVersionAndTheAdoptedId() {
        XCTAssertEqual(FriendsWire.strapHandleInput(adoptedId: "whoop-4A0123456"),
                       "renoop-friends-strap-v1\nwhoop-4A0123456")
    }

    /// The same cases the server's `normalize_code` is tested with.
    func testACodeIsReadAsItIsTyped() {
        XCTAssertEqual(FriendsInviteCode.normalized(" O12-3456 789 "), "0123456789")
        XCTAssertEqual(FriendsInviteCode.normalized("ilooabcdef"), "1100ABCDEF")
        XCTAssertEqual(FriendsInviteCode.normalized("K7QM2\r\nXRD4P"), "K7QM2XRD4P")
        XCTAssertNil(FriendsInviteCode.normalized("ABCDE-1234"), "nine characters")
        XCTAssertNil(FriendsInviteCode.normalized("ABCDE-1234U"), "U is not in the alphabet")
        XCTAssertNil(FriendsInviteCode.normalized(""))
        XCTAssertEqual(FriendsInviteCode.alphabet.count, 32)
    }

    func testACodeIsShownInTwoHalves() {
        XCTAssertEqual(FriendsInviteCode.display("K7QM2XRD4P"), "K7QM2-XRD4P")
        XCTAssertEqual(FriendsInviteCode.pageLink(server: "https://renoop.duckdns.org", code: "K7QM2XRD4P"),
                       "https://renoop.duckdns.org/i/K7QM2-XRD4P")
    }

    func testTheCodeIsFoundInWhateverWasPastedOrOpened() {
        XCTAssertEqual(FriendsInviteCode.extract(" k7qm2 xrd4p "), "K7QM2XRD4P")
        XCTAssertEqual(FriendsInviteCode.extract("https://renoop.duckdns.org/i/K7QM2-XRD4P"), "K7QM2XRD4P")
        XCTAssertEqual(FriendsInviteCode.extract("https://example.org/friends/i/k7qm2-xrd4p"), "K7QM2XRD4P",
                       "a server mounted under a prefix")
        XCTAssertEqual(FriendsInviteCode.extract("renoop://friends/add?c=k7qm2-xrd4p"), "K7QM2XRD4P")
        XCTAssertNil(FriendsInviteCode.extract("https://example.org/other/K7QM2-XRD4P"), "not an invite page")
        XCTAssertNil(FriendsInviteCode.extract("renoop://today"), "another link of the app's")
        XCTAssertNil(FriendsInviteCode.extract("renoop://friends/add?c=nope"))
        XCTAssertNil(FriendsInviteCode.extract("hello"))
    }
}
