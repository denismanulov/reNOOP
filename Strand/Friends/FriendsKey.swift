//  FriendsKey.swift
//  NOOP · Friends — the phone's key for the friends service (reNOOP fork feature).
//
//  There is no password. A phone is known to the server by an ECDSA P-256 key it makes the first time
//  Friends is turned on, and every request is signed with it. On a device with a Secure Enclave the
//  private key lives there and cannot be read out; elsewhere (the simulator, a Mac without one) it is a
//  software key. Either way what is kept is one Keychain item, on this device only, readable after the
//  first unlock so an upload can follow a background strap sync. It is not a setting, is never written
//  to UserDefaults, and never enters a `.noopbak` backup. There is one key per server address, so a
//  server the wearer names themselves never sees a signature the fork's own server would accept.

import CryptoKit
import Foundation
import Security

/// What signs a request: `FriendsKey` in the app, a fixed software key in tests.
protocol FriendsSigner: Sendable {
    /// Lower-case hex SHA-256 of `publicKeySPKI`: the phone's id on the server.
    var keyID: String { get }
    /// The public key as SubjectPublicKeyInfo DER.
    var publicKeySPKI: Data { get }
    /// A DER ECDSA-SHA256 signature over `message`.
    func sign(_ message: Data) throws -> Data
}

/// Where a key's bytes are kept between launches: the Keychain in the app, memory in tests (an
/// unsigned test host cannot reach the Keychain).
protocol FriendsKeyStorage: Sendable {
    func read(account: String) -> Data?
    func write(_ data: Data, account: String) -> Bool
    func delete(account: String)
}

enum FriendsKeyError: Error, Equatable {
    /// The key was made but could not be kept, so it would not survive a relaunch.
    case notKept
}

struct FriendsKey: FriendsSigner, @unchecked Sendable {
    private enum Material {
        case enclave(SecureEnclave.P256.Signing.PrivateKey)
        case software(P256.Signing.PrivateKey)
    }

    private let material: Material
    let publicKeySPKI: Data
    let keyID: String

    /// True when the private key is held by the Secure Enclave.
    var isHardwareBacked: Bool {
        if case .enclave = material { return true }
        return false
    }

    private init(_ material: Material) {
        self.material = material
        switch material {
        case let .enclave(key): publicKeySPKI = key.publicKey.derRepresentation
        case let .software(key): publicKeySPKI = key.publicKey.derRepresentation
        }
        keyID = FriendsKey.hex(SHA256.hash(data: publicKeySPKI))
    }

    func sign(_ message: Data) throws -> Data {
        switch material {
        case let .enclave(key): return try key.signature(for: message).derRepresentation
        case let .software(key): return try key.signature(for: message).derRepresentation
        }
    }

    // MARK: Keeping

    /// The first byte of a kept key says which kind follows it.
    private static let enclaveTag: UInt8 = 1
    private static let softwareTag: UInt8 = 2

    /// The storage name of the key for `server` (a normalised server address).
    static func account(forServer server: String) -> String {
        "key." + hex(SHA256.hash(data: Data(server.utf8))).prefix(32)
    }

    /// The key kept for `server`, or nil when there is none or it can no longer be used on this device.
    static func load(server: String, storage: FriendsKeyStorage) -> FriendsKey? {
        guard let blob = storage.read(account: account(forServer: server)), let tag = blob.first else { return nil }
        let body = Data(blob.dropFirst())
        switch tag {
        case enclaveTag:
            return (try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: body)).map { FriendsKey(.enclave($0)) }
        case softwareTag:
            return (try? P256.Signing.PrivateKey(rawRepresentation: body)).map { FriendsKey(.software($0)) }
        default:
            return nil
        }
    }

    /// Makes and keeps a new key for `server`, replacing any kept before. In the Secure Enclave when
    /// `enclave` and the device can; a software key otherwise.
    static func create(server: String, storage: FriendsKeyStorage,
                       enclave: Bool = SecureEnclave.isAvailable) throws -> FriendsKey {
        let key: FriendsKey
        let blob: Data
        if enclave, let made = try? makeEnclaveKey() {
            key = FriendsKey(.enclave(made))
            blob = Data([enclaveTag]) + made.dataRepresentation
        } else {
            let made = P256.Signing.PrivateKey()
            key = FriendsKey(.software(made))
            blob = Data([softwareTag]) + made.rawRepresentation
        }
        guard storage.write(blob, account: account(forServer: server)) else { throw FriendsKeyError.notKept }
        return key
    }

    static func delete(server: String, storage: FriendsKeyStorage) {
        storage.delete(account: account(forServer: server))
    }

    private static func makeEnclaveKey() throws -> SecureEnclave.P256.Signing.PrivateKey {
        guard let access = SecAccessControlCreateWithFlags(
            nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, .privateKeyUsage, nil) else {
            throw FriendsKeyError.notKept
        }
        return try SecureEnclave.P256.Signing.PrivateKey(accessControl: access)
    }

    static func hex<Bytes: Sequence>(_ bytes: Bytes) -> String where Bytes.Element == UInt8 {
        bytes.map { byte in
            let text = String(byte, radix: 16)
            return text.count == 1 ? "0" + text : text
        }.joined()
    }
}

/// The Keychain: one generic-password item per server, this device only, readable after the first unlock.
struct FriendsKeychainStorage: FriendsKeyStorage {
    private static let service = "com.renoop.friends.key"

    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: Self.service,
         kSecAttrAccount as String: account]
    }

    func read(account: String) -> Data? {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    func write(_ data: Data, account: String) -> Bool {
        SecItemDelete(query(account) as CFDictionary)
        var q = query(account)
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    func delete(account: String) { SecItemDelete(query(account) as CFDictionary) }
}

/// Keys held in memory, for tests.
final class FriendsMemoryKeyStorage: FriendsKeyStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String: Data] = [:]
    /// Set to make every write fail, as a Keychain that refuses does.
    var refusesWrites = false

    func read(account: String) -> Data? {
        lock.lock(); defer { lock.unlock() }
        return items[account]
    }

    func write(_ data: Data, account: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard !refusesWrites else { return false }
        items[account] = data
        return true
    }

    func delete(account: String) {
        lock.lock(); defer { lock.unlock() }
        items[account] = nil
    }
}
