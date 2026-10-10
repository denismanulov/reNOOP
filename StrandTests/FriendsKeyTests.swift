import CryptoKit
import XCTest
@testable import Strand

/// The phone's key: what it signs verifies with what it publishes, it is kept per server, and the
/// README's vector verifies with CryptoKit as it does on the server.
final class FriendsKeyTests: XCTestCase {

    private let message = Data(("renoop-friends-v2\nPUT\n/v2/me/days/2026-10-10\n1791540000\nAAAAAAAAAAAAAAAAAAAAAA\n"
                                + "a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445").utf8)

    func testTheContractsSignatureVerifies() throws {
        let spki = try XCTUnwrap(Data(base64Encoded:
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZ/iGnmqlOeIlaOq9cf3gYSDRWcoyKOiHtx1m7TC7Wnr6CDeXUMf1QOVArjNKQgNPyKXVXw0/9N1Iyj8xadf1YA=="))
        let signature = try XCTUnwrap(Data(base64Encoded:
            "MEYCIQCbv6DGgAsKyGcPSjPiv6/b8i3IJZkUCmkxZt2/mg+nBgIhAPIfodgQQUa5TD0KWsCpCIKzIgtAwstLtonCzIHZLJMQ"))
        XCTAssertEqual(FriendsKey.hex(SHA256.hash(data: spki)),
                       "b9310888608f332f9d712a19e65fd9a088948406f04240f82a4e50843774784a")
        let key = try P256.Signing.PublicKey(derRepresentation: spki)
        XCTAssertTrue(key.isValidSignature(try P256.Signing.ECDSASignature(derRepresentation: signature), for: message))
        XCTAssertFalse(key.isValidSignature(try P256.Signing.ECDSASignature(derRepresentation: signature),
                                            for: message + Data(" ".utf8)))
    }

    func testWhatASoftwareKeySignsVerifiesWithWhatItPublishes() throws {
        let storage = FriendsMemoryKeyStorage()
        let key = try FriendsKey.create(server: "https://a.example", storage: storage, enclave: false)
        XCTAssertFalse(key.isHardwareBacked)
        XCTAssertEqual(key.publicKeySPKI.count, 91, "an uncompressed P-256 point in SubjectPublicKeyInfo")
        XCTAssertEqual(key.keyID, FriendsKey.hex(SHA256.hash(data: key.publicKeySPKI)))
        XCTAssertEqual(key.keyID.count, 64)
        let published = try P256.Signing.PublicKey(derRepresentation: key.publicKeySPKI)
        let signature = try P256.Signing.ECDSASignature(derRepresentation: try key.sign(message))
        XCTAssertTrue(published.isValidSignature(signature, for: message))
    }

    func testAKeyIsKeptPerServerAndComesBackTheSame() throws {
        let storage = FriendsMemoryKeyStorage()
        let key = try FriendsKey.create(server: "https://a.example", storage: storage, enclave: false)
        XCTAssertEqual(FriendsKey.load(server: "https://a.example", storage: storage)?.keyID, key.keyID)
        XCTAssertNil(FriendsKey.load(server: "https://b.example", storage: storage), "another server has no key")
        let other = try FriendsKey.create(server: "https://b.example", storage: storage, enclave: false)
        XCTAssertNotEqual(other.keyID, key.keyID)
        XCTAssertNotEqual(FriendsKey.account(forServer: "https://a.example"), FriendsKey.account(forServer: "https://b.example"))
        FriendsKey.delete(server: "https://a.example", storage: storage)
        XCTAssertNil(FriendsKey.load(server: "https://a.example", storage: storage))
        XCTAssertNotNil(FriendsKey.load(server: "https://b.example", storage: storage))
    }

    func testAKeyThatCannotBeKeptIsNotHandedOut() {
        let storage = FriendsMemoryKeyStorage()
        storage.refusesWrites = true
        XCTAssertThrowsError(try FriendsKey.create(server: "https://a.example", storage: storage, enclave: false)) {
            XCTAssertEqual($0 as? FriendsKeyError, .notKept)
        }
    }

    func testWhatIsKeptThatIsNotAKeyReadsAsNone() {
        let storage = FriendsMemoryKeyStorage()
        let account = FriendsKey.account(forServer: "https://a.example")
        for junk in [Data(), Data([9, 1, 2, 3]), Data([2, 1, 2, 3]), Data([1, 1, 2, 3])] {
            _ = storage.write(junk, account: account)
            XCTAssertNil(FriendsKey.load(server: "https://a.example", storage: storage))
        }
    }

    /// The storage tells "nothing is kept" from "what is kept could not be read", and the key follows
    /// it: only the first may ever be answered by making a new key.
    func testTheStorageTellsNoKeyFromAKeyItCouldNotRead() throws {
        let storage = FriendsMemoryKeyStorage()
        let server = "https://a.example"
        let account = FriendsKey.account(forServer: server)
        XCTAssertEqual(storage.item(account: account), .missing)
        guard case .absent = FriendsKey.find(server: server, storage: storage) else { return XCTFail("no item is no key") }

        let key = try FriendsKey.create(server: server, storage: storage, enclave: false)
        guard case let .data(blob) = storage.item(account: account) else { return XCTFail("the kept item was not read") }
        XCTAssertEqual(storage.read(account: account), blob)
        guard case let .key(found) = FriendsKey.find(server: server, storage: storage) else { return XCTFail("the kept key was not found") }
        XCTAssertEqual(found.keyID, key.keyID)

        storage.failsReads = true
        XCTAssertEqual(storage.item(account: account), .unreadable)
        XCTAssertNil(storage.read(account: account))
        guard case .unreadable = FriendsKey.find(server: server, storage: storage) else { return XCTFail("a failed read is not an answer") }
        XCTAssertNil(FriendsKey.load(server: server, storage: storage))

        storage.failsReads = false
        XCTAssertEqual(FriendsKey.load(server: server, storage: storage)?.keyID, key.keyID)
        XCTAssertEqual(storage.writeCount, 1)

        // What is kept but is not a key is "no key", as before: it can be replaced.
        XCTAssertTrue(storage.write(Data([9, 1, 2, 3]), account: account))
        guard case .absent = FriendsKey.find(server: server, storage: storage) else { return XCTFail("junk is no key") }
    }

    /// Whatever kind of key this machine makes by default (the Secure Enclave's where there is one and
    /// the test host may use it, a software key otherwise), it loads back and signs.
    func testTheDefaultKeyOfThisMachineLoadsBackAndSigns() throws {
        let storage = FriendsMemoryKeyStorage()
        let key = try FriendsKey.create(server: "https://hw.example", storage: storage)
        XCTAssertEqual(key.isHardwareBacked, SecureEnclave.isAvailable,
                       "where there is a Secure Enclave the key is made in it, not beside it")
        let again = try XCTUnwrap(FriendsKey.load(server: "https://hw.example", storage: storage))
        XCTAssertEqual(again.keyID, key.keyID)
        XCTAssertEqual(again.isHardwareBacked, key.isHardwareBacked)
        let published = try P256.Signing.PublicKey(derRepresentation: again.publicKeySPKI)
        XCTAssertTrue(published.isValidSignature(
            try P256.Signing.ECDSASignature(derRepresentation: try again.sign(message)), for: message))
    }
}
