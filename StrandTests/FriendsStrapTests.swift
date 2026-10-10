import XCTest
import WhoopStore
@testable import Strand

/// Which strap a friends account is bound to: the handle's vector, what is recorded where a serial is
/// confirmed, and which devices have a serial at all.
final class FriendsStrapTests: XCTestCase {

    private func device(_ id: String, brand: String) -> PairedDevice {
        PairedDevice(id: id, brand: brand, model: brand, sourceKind: .liveBLE, capabilities: [],
                     status: .active, addedAt: 0, lastSeenAt: 0)
    }

    /// The literal is the contract's (`friends-server/README.md`), shared with the server and Android.
    func testTheHandleIsTheContractsVector() {
        XCTAssertEqual(FriendsStrap.handle(adoptedId: "whoop-4A0123456"),
                       "98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707")
        XCTAssertNotEqual(FriendsStrap.handle(adoptedId: "whoop-4A0123456"), FriendsStrap.handle(adoptedId: "oura-4A0123456"),
                          "the brand is part of the id, so two brands cannot share a handle")
    }

    func testAConfirmedSerialIsKeptAgainstTheRegistryId() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "friends-strap-\(UUID().uuidString)"))
        XCTAssertNil(FriendsStrap.adoptedId(forDeviceId: "my-whoop", defaults: defaults))
        FriendsStrap.note(adoptedId: "whoop-4A0123456", forDeviceId: "my-whoop", defaults: defaults)
        XCTAssertEqual(FriendsStrap.adoptedId(forDeviceId: "my-whoop", defaults: defaults), "whoop-4A0123456")
        XCTAssertNil(FriendsStrap.adoptedId(forDeviceId: "whoop-OTHER", defaults: defaults))
        FriendsStrap.note(adoptedId: "", forDeviceId: "my-whoop", defaults: defaults)
        XCTAssertEqual(FriendsStrap.adoptedId(forDeviceId: "my-whoop", defaults: defaults), "whoop-4A0123456",
                       "an empty id records nothing")
    }

    func testAStrapIsAHandleOnceItsSerialIsKnownAndPendingBefore() {
        XCTAssertEqual(FriendsStrap.identity(hasSerial: true, adoptedId: "whoop-4A0123456"),
                       .handle("98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707"))
        XCTAssertEqual(FriendsStrap.identity(hasSerial: true, adoptedId: nil), .pending)
        XCTAssertEqual(FriendsStrap.identity(hasSerial: false, adoptedId: nil), FriendsStrap.Identity.none)
    }

    func testOnlyAWhoopOrAnOuraHasASerialToBindTo() {
        XCTAssertTrue(FriendsStrap.hasSerial(device("my-whoop", brand: "WHOOP")))
        XCTAssertTrue(FriendsStrap.hasSerial(device("whoop-4A0123456", brand: "whoop")))
        XCTAssertTrue(FriendsStrap.hasSerial(device("oura-2H3B2405003655", brand: "Oura")))
        XCTAssertFalse(FriendsStrap.hasSerial(device("polar-h10-1A2B", brand: "Polar")))
        XCTAssertFalse(FriendsStrap.hasSerial(device("apple-watch", brand: "Apple")))
    }

    @MainActor
    func testWithNoRegistryNothingIsKnownYet() {
        XCTAssertEqual(FriendsStrap.identity(registry: nil), .pending)
    }
}
