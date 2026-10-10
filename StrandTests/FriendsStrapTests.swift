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
        let defaults = FriendsTestDefaults()
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

    func testARegistryIdIsSerialDerivedOnlyWhenItsRemainderIsASerial() {
        let uuid = UUID().uuidString
        XCTAssertEqual(FriendsStrap.serialDerivedId("whoop-4A0123456"), "whoop-4A0123456")
        XCTAssertNil(FriendsStrap.serialDerivedId("whoop-\(uuid)"), "an upper-case UUID is a provisional pairing id")
        XCTAssertNil(FriendsStrap.serialDerivedId("whoop-\(uuid.lowercased())"), "so is a lower-case one")
        XCTAssertNil(FriendsStrap.serialDerivedId("my-whoop"), "the legacy seed relies on the stored value")
        XCTAssertEqual(FriendsStrap.serialDerivedId("oura-2H3B2405003655"), "oura-2H3B2405003655")
        XCTAssertNil(FriendsStrap.serialDerivedId("oura-\(uuid)"))
        XCTAssertNil(FriendsStrap.serialDerivedId("polar-h10-1A2B"))
        XCTAssertNil(FriendsStrap.serialDerivedId("whoop-"))
    }

    func testTheHandleOfASerialKeyedIdIsTheContractsVector() {
        XCTAssertEqual(FriendsStrap.identity(hasSerial: true, adoptedId: FriendsStrap.serialDerivedId("whoop-4A0123456")),
                       .handle("98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707"))
    }

    @MainActor
    private func registry(_ rows: [PairedDevice], active: String) async throws -> DeviceRegistry {
        let store = try await WhoopStore.inMemory()
        let registry = DeviceRegistry(store: DeviceRegistryStore(dbQueue: store.registryWriter))
        rows.forEach { registry.add($0) }
        registry.setActive(active)
        return registry
    }

    @MainActor
    func testASerialKeyedActiveRowIsAHandleWithNothingStored() async throws {
        let defaults = FriendsTestDefaults()
        let registry = try await registry([device("whoop-4A0123456", brand: "WHOOP")], active: "whoop-4A0123456")
        XCTAssertEqual(FriendsStrap.identity(registry: registry, defaults: defaults),
                       .handle("98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707"))
    }

    /// A row whose own id is serial-derived names its strap by itself. A value stored against that id
    /// (left by another strap confirmed earlier in the process) does not speak for it.
    @MainActor
    func testASerialKeyedActiveRowIsTheHandleOfItsOwnIdWhateverIsStoredAgainstIt() async throws {
        let defaults = FriendsTestDefaults()
        FriendsStrap.note(adoptedId: "whoop-4B7654321", forDeviceId: "whoop-4A0123456", defaults: defaults)
        let registry = try await registry([device("whoop-4A0123456", brand: "WHOOP")], active: "whoop-4A0123456")
        XCTAssertEqual(FriendsStrap.identity(registry: registry, defaults: defaults),
                       .handle("98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707"))
        // The legacy seed has no serial in its id, so the stored value is all there is.
        FriendsStrap.note(adoptedId: "whoop-4A0123456", forDeviceId: "my-whoop", defaults: defaults)
        let legacy = try await self.registry([device("my-whoop", brand: "WHOOP")], active: "my-whoop")
        XCTAssertEqual(FriendsStrap.identity(registry: legacy, defaults: defaults),
                       .handle("98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707"))
    }

    @MainActor
    func testAnArchivedRowThatIsStillTheActiveIdIsNotAStrap() async throws {
        let defaults = FriendsTestDefaults()
        let registry = try await registry([device("whoop-4A0123456", brand: "WHOOP")], active: "whoop-4A0123456")
        registry.archive("whoop-4A0123456")
        XCTAssertEqual(registry.activeDeviceId, "whoop-4A0123456", "archiving leaves the active id where it was")
        XCTAssertEqual(FriendsStrap.identity(registry: registry, defaults: defaults), .pending)
    }
}
