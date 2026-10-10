# Friends Client Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the iOS and macOS client from nickname-and-password accounts to API version 2: a per-phone P-256 key that signs every request, a strap handle the account is bound to, and a store that carries turning Friends on through its states.

**Architecture:** Three additive units first, each with its own tests and no effect on the running app: the text rules shared with Android (`FriendsWire`, in the `StrandAnalytics` package), the phone key (`FriendsKey`), and the strap handle (`FriendsStrap`, with one writer in each of the two places a serial is confirmed). Then one task flips the wire and the store to version 2 and adapts the screens that named the removed calls, because those four cannot compile apart.

**Tech Stack:** Swift 5.9+, CryptoKit (P-256, Secure Enclave), Security (Keychain), SwiftUI, XCTest. `xcodegen` for the project.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-10-10-friends-keys-and-straps-design.md`, sections 3, 5 and 9. Contract: `friends-server/README.md` as rewritten by plan 1.
- The code in this plan was compiled and run against plan 1's server before it was written down (key creation in the Secure Enclave, signing, the clock-skew retry, claims, strap moves, invites). Type it as given; where the app differs from what a step says, stop and say so rather than adapting silently.
- `Strand/Friends/` compiles into both `Strand` (macOS 13) and `NOOPiOS` (iOS 17). Both must build after every task that touches it. `StrandTests` runs on the macOS leg only.
- No `import UIKit`, `AppKit` or `CoreBluetooth` under `Packages/`. `FriendsWire` uses Foundation only and must stay buildable on Linux.
- The device key is never written to UserDefaults, never logged, and never enters a `.noopbak` backup. One key per server address.
- The 54-character device key in a WHOOP 4.0 hello response is not read, hashed or used.
- The BLE connection path does not change. Task 3 adds two writes to UserDefaults at points where a serial is already confirmed, and nothing else.
- UI uses `StrandPalette`, `StrandFont`, `NoopMetrics` and `FriendsStyle` only.
- New user-facing strings are added to `Strand/Resources/Localizable.xcstrings` as one-line entries at the end of `"strings"`, English key and a `ru` translation, in the form the fork's other Friends strings use.
- Commit subjects `feature:` / `fix:` / `docs:`; no `Co-Authored-By`; stage named files only.
- `Strand.xcodeproj` and `StrandNoWatch.xcodeproj` are generated and never committed.

## Before Task 1

The working tree holds the owner's uncommitted Friends redesign, in the very files Task 4 rewrites. It must be committed first, with the owner's say-so. Run `git status --short Strand/Friends StrandTests Packages/StrandAnalytics` and stop if anything is listed.

## Build and test commands

Used by every task below. Run from the repository root.

```bash
# Regenerate the projects after adding or removing a Swift file.
xcodegen generate >/dev/null && xcodegen generate --spec project-nowatch.yml >/dev/null

# macOS app (the Strand scheme).
xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO build 2>&1 | grep -E 'error:|BUILD' | head -20

# iOS app, for the simulator. `project-nowatch.yml` is a local, untracked copy of `project.yml` without
# the watch target (the watchOS platform is not installed on this machine). Signed ad hoc: an unsigned
# simulator build cannot reach the Keychain, and the phone key lives there.
xcodebuild -project StrandNoWatch.xcodeproj -scheme NOOPiOS -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/dd-ios CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual \
  DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build 2>&1 | grep -E 'error:|BUILD' | head -20

# One test class of the app target (macOS).
xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO \
  test -only-testing:StrandTests/<ClassName> 2>&1 | grep -E 'error:|Test Case.*(failed|passed)|Executed|TEST' | tail -30
```

`Expected` for a build is `** BUILD SUCCEEDED **` with no `error:` line.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `Packages/StrandAnalytics/Sources/StrandAnalytics/FriendsWire.swift` (new) | The signing string, the strap-handle input, invite-code rules. Pure text. | 1 |
| `Packages/StrandAnalytics/Tests/StrandAnalyticsTests/FriendsWireTests.swift` (new) | Pins the shared vectors. | 1 |
| `Strand/Friends/FriendsKey.swift` (new) | The phone key, where it is kept, `FriendsSigner`. | 2 |
| `StrandTests/FriendsKeyTests.swift` (new) | | 2 |
| `Strand/Friends/FriendsStrap.swift` (new) | The strap handle of the active device. | 3 |
| `Strand/BLE/BLEManager.swift`, `Strand/BLE/SourceCoordinator.swift` | One writer each: the confirmed serial id. | 3 |
| `StrandTests/FriendsStrapTests.swift` (new) | | 3 |
| `Strand/Friends/FriendsAPI.swift` | The v2 wire. | 4 |
| `Strand/Friends/FriendsStore.swift` | The state machine, the upload run, profile push, strap binding. | 4 |
| `Strand/Friends/FriendsView.swift`, `FriendsSheets.swift`, `FriendsPieces.swift`, `FriendsAvatars.swift`, `FriendDetailView.swift`, `FriendsBoard.swift`, `FriendsHighlights.swift` | Adapted to ids and to the new store. The welcome page gains the turning-on states. | 4 |
| `Strand/App/TabRoute.swift`, `Strand/App/AppModel.swift`, `StrandiOS/App/StrandiOSApp.swift` | One line each. | 4 |
| `StrandTests/FriendsClientTests.swift`, `FriendsBoardTests.swift`, `FriendsHighlightsTests.swift` | | 4 |
| `Strand/Resources/Localizable.xcstrings` | New strings. | 4 |

After this plan Friends can be turned on, joined from a second phone, and read; a strap change rebinds by itself. Inviting a friend, the phones list, answering claims and the export page have their store calls here and their screens in plan 3.

---

### Task 1: `FriendsWire`, the text rules shared with Android

**Files:**
- Create: `Packages/StrandAnalytics/Sources/StrandAnalytics/FriendsWire.swift`
- Test: `Packages/StrandAnalytics/Tests/StrandAnalyticsTests/FriendsWireTests.swift`

**Interfaces:**
- Produces:
  - `FriendsWire.signingString(method: String, target: String, time: Int, nonce: String, bodyHashHex: String) -> String`
  - `FriendsWire.strapHandleInput(adoptedId: String) -> String`
  - `FriendsInviteCode.normalized(_ raw: String) -> String?`, `.display(_ code: String) -> String`, `.extract(_ text: String) -> String?`, `.pageLink(server: String, code: String) -> String`, `.alphabet`, `.length`

- [ ] **Step 1: Write the failing test**

Create `Packages/StrandAnalytics/Tests/StrandAnalyticsTests/FriendsWireTests.swift`:

```swift
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
```

- [ ] **Step 2: Run it to see it fail**

```bash
cd Packages/StrandAnalytics && swift test --filter FriendsWireTests 2>&1 | tail -5
```

Expected: a compile error, `cannot find 'FriendsWire' in scope`.

- [ ] **Step 3: Write the implementation**

Create `Packages/StrandAnalytics/Sources/StrandAnalytics/FriendsWire.swift`:

```swift
import Foundation

/// The text rules of the friends service that every client applies identically (API version 2,
/// `friends-server/README.md`). No hashing and no I/O, so the package still builds on Linux: the app
/// target does the hashing and the signing. Kotlin twin: `FriendsWire`.
public enum FriendsWire {
    public static let signingPrefix = "renoop-friends-v2"
    public static let strapHandlePrefix = "renoop-friends-strap-v1\n"

    /// What a phone signs for one request: six lines joined by "\n", with no trailing newline.
    /// `target` is the path and query exactly as sent, beginning "/v2/".
    public static func signingString(method: String, target: String, time: Int, nonce: String,
                                     bodyHashHex: String) -> String {
        [signingPrefix, method.uppercased(), target, String(time), nonce, bodyHashHex].joined(separator: "\n")
    }

    /// The text whose SHA-256 is a strap's handle. `adoptedId` is the registry's serial-derived id,
    /// such as "whoop-4A0123456".
    public static func strapHandleInput(adoptedId: String) -> String { strapHandlePrefix + adoptedId }
}

/// An invite code: ten characters of a 32-letter alphabet that leaves out the letters read as digits.
public enum FriendsInviteCode {
    public static let alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    public static let length = 10

    /// The stored form of typed input, or nil when it is not a code. Forgives case, the dash, spaces,
    /// and O / I / L typed for 0 / 1 / 1, exactly as the server does. Walks code points, not
    /// characters: Swift reads "\r\n" as one character, the server as two.
    public static func normalized(_ raw: String) -> String? {
        var out = String.UnicodeScalarView()
        for scalar in raw.uppercased().unicodeScalars {
            switch scalar {
            case "-", " ", "\t", "\r", "\n": continue
            case "O": out.append("0")
            case "I", "L": out.append("1")
            default: out.append(scalar)
            }
        }
        guard out.count == length, out.allSatisfy({ alphabet.unicodeScalars.contains($0) }) else { return nil }
        return String(out)
    }

    /// A stored code as it is shown and shared: "XXXXX-XXXXX".
    public static func display(_ code: String) -> String {
        guard code.count == length else { return code }
        return code.prefix(5) + "-" + code.suffix(5)
    }

    /// The code in whatever was typed, pasted or opened: a bare code, an invite page's address
    /// (".../i/<code>") or the app's own link ("renoop://friends/add?c=<code>"). Nil when there is none.
    public static func extract(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let code = normalized(trimmed) { return code }
        guard let parts = URLComponents(string: trimmed) else { return nil }
        if parts.scheme?.lowercased() == "renoop" {
            guard parts.host?.lowercased() == "friends", parts.path == "/add" else { return nil }
            return parts.queryItems?.first(where: { $0.name == "c" })?.value.flatMap(normalized)
        }
        let segments = parts.path.split(separator: "/", omittingEmptySubsequences: true)
        guard segments.count >= 2, segments[segments.count - 2] == "i" else { return nil }
        return normalized(String(segments[segments.count - 1]))
    }

    /// The invite page's address on `server` (a normalised server address, no trailing slash).
    public static func pageLink(server: String, code: String) -> String { server + "/i/" + display(code) }
}
```

- [ ] **Step 4: Run the test to see it pass**

```bash
cd Packages/StrandAnalytics && swift test --filter FriendsWireTests 2>&1 | tail -3
```

Expected: `Executed 5 tests, with 0 failures`.

- [ ] **Step 5: Commit**

```bash
git add Packages/StrandAnalytics/Sources/StrandAnalytics/FriendsWire.swift Packages/StrandAnalytics/Tests/StrandAnalyticsTests/FriendsWireTests.swift
git commit -m "feature: friends wire rules shared with Android (signing string, invite codes)"
```

---

### Task 2: `FriendsKey`, the phone's key

**Files:**
- Create: `Strand/Friends/FriendsKey.swift`
- Test: `StrandTests/FriendsKeyTests.swift`

**Interfaces:**
- Produces:
  - `protocol FriendsSigner: Sendable { var keyID: String { get }; var publicKeySPKI: Data { get }; func sign(_ message: Data) throws -> Data }`
  - `protocol FriendsKeyStorage: Sendable { func read(account: String) -> Data?; func write(_ data: Data, account: String) -> Bool; func delete(account: String) }`
  - `struct FriendsKey: FriendsSigner` with `static func load(server: String, storage: FriendsKeyStorage) -> FriendsKey?`, `static func create(server: String, storage: FriendsKeyStorage, enclave: Bool = SecureEnclave.isAvailable) throws -> FriendsKey`, `static func delete(server: String, storage: FriendsKeyStorage)`, `static func account(forServer: String) -> String`, `static func hex(_ bytes:) -> String`, `var isHardwareBacked: Bool`
  - `struct FriendsKeychainStorage: FriendsKeyStorage`, `final class FriendsMemoryKeyStorage: FriendsKeyStorage` (with `var refusesWrites: Bool`), `enum FriendsKeyError { case notKept }`

- [ ] **Step 1: Write the failing test**

Create `StrandTests/FriendsKeyTests.swift`:

```swift
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

    /// Whatever kind of key this machine makes by default (the Secure Enclave's where there is one and
    /// the test host may use it, a software key otherwise), it loads back and signs.
    func testTheDefaultKeyOfThisMachineLoadsBackAndSigns() throws {
        let storage = FriendsMemoryKeyStorage()
        let key = try FriendsKey.create(server: "https://hw.example", storage: storage)
        let again = try XCTUnwrap(FriendsKey.load(server: "https://hw.example", storage: storage))
        XCTAssertEqual(again.keyID, key.keyID)
        XCTAssertEqual(again.isHardwareBacked, key.isHardwareBacked)
        let published = try P256.Signing.PublicKey(derRepresentation: again.publicKeySPKI)
        XCTAssertTrue(published.isValidSignature(
            try P256.Signing.ECDSASignature(derRepresentation: try again.sign(message)), for: message))
    }
}
```

- [ ] **Step 2: Run it to see it fail**

```bash
xcodegen generate >/dev/null && xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO test -only-testing:StrandTests/FriendsKeyTests 2>&1 | grep -E 'error:' | head -3
```

Expected: `error: cannot find 'FriendsKey' in scope` (and `FriendsMemoryKeyStorage`).

- [ ] **Step 3: Write the implementation**

Create `Strand/Friends/FriendsKey.swift`:

```swift
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
```

- [ ] **Step 4: Run the test to see it pass**

```bash
xcodegen generate >/dev/null && xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO test -only-testing:StrandTests/FriendsKeyTests 2>&1 | grep -E 'error:|Executed|TEST' | tail -4
```

Expected: `Executed 6 tests, with 0 failures` and `** TEST SUCCEEDED **`.

- [ ] **Step 5: Build the iOS target**

Run the iOS build command from "Build and test commands". Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 6: Commit**

```bash
git add Strand/Friends/FriendsKey.swift StrandTests/FriendsKeyTests.swift
git commit -m "feature: a per-phone P-256 key for Friends, in the Secure Enclave where there is one"
```

---

### Task 3: `FriendsStrap`, and the serial recorded where it is confirmed

**Files:**
- Create: `Strand/Friends/FriendsStrap.swift`
- Modify: `Strand/BLE/BLEManager.swift` (`adoptWhoopSerialIdentity()` and one new method after it, near line 5340)
- Modify: `Strand/BLE/SourceCoordinator.swift` (`adoptOuraSerial(currentId:serial:)`, near line 466)
- Test: `StrandTests/FriendsStrapTests.swift`

**Interfaces:**
- Consumes: `FriendsWire.strapHandleInput(adoptedId:)` (Task 1), `FriendsKey.hex(_:)` (Task 2), `WhoopSerialIdentity.adoptedId(serial:)`, `SourceIdentity.isWhoop(_:)`, `ExperimentalBrand.oura.idPrefix`, `DeviceRegistry.devices` / `.activeDeviceId`.
- Produces:
  - `FriendsStrap.Identity` with cases `.handle(String)`, `.pending`, `.none`
  - `FriendsStrap.handle(adoptedId: String) -> String`
  - `FriendsStrap.note(adoptedId: String, forDeviceId: String, defaults: UserDefaults = .standard)`
  - `FriendsStrap.adoptedId(forDeviceId: String, defaults: UserDefaults = .standard) -> String?`
  - `FriendsStrap.hasSerial(_ device: PairedDevice) -> Bool`
  - `FriendsStrap.identity(hasSerial: Bool, adoptedId: String?) -> Identity`
  - `@MainActor FriendsStrap.identity(registry: DeviceRegistry?, defaults: UserDefaults = .standard) -> Identity`
  - In DEBUG builds, the launch argument `--friends-strap <adopted id>` stands in for a strap, for the simulator.

- [ ] **Step 1: Write the failing test**

Create `StrandTests/FriendsStrapTests.swift`:

```swift
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
```

- [ ] **Step 2: Run it to see it fail**

```bash
xcodegen generate >/dev/null && xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO test -only-testing:StrandTests/FriendsStrapTests 2>&1 | grep -E 'error:' | head -3
```

Expected: `error: cannot find 'FriendsStrap' in scope`.

- [ ] **Step 3: Write `FriendsStrap`**

Create `Strand/Friends/FriendsStrap.swift`:

```swift
//  FriendsStrap.swift
//  NOOP · Friends — the strap an account is bound to (reNOOP fork feature).
//
//  The friends account is tied to the strap, so the same strap on a new phone leads back to it and a
//  new strap on this phone takes it over. What stands for the strap on the server is its handle: the
//  SHA-256 of the registry's serial-derived id (`whoop-<SERIAL>`, `oura-<SERIAL>`). The serial itself
//  never leaves the phone.
//
//  A legacy single-WHOOP install keeps the registry id `my-whoop` and is never re-pointed onto its
//  serial, so the id alone does not say which strap it is. The two places a serial is confirmed
//  (`BLEManager` for a WHOOP, `SourceCoordinator` for an Oura ring) therefore record the serial-derived
//  id against the registry id, and this file reads it back. The WHOOP 4.0's device key, which arrives
//  beside its serial, is not involved: it is never read.

import CryptoKit
import Foundation
import StrandAnalytics
import WhoopStore

enum FriendsStrap {
    /// What the active device gives Friends to bind the account to.
    enum Identity: Equatable {
        /// A strap whose serial is known: its handle.
        case handle(String)
        /// A strap that has a serial, not read on this phone yet.
        case pending
        /// A device with no serial to bind to (a watch, a chest strap, an import).
        case none
    }

    /// Under this prefix and a registry id: the serial-derived id of the strap registered there.
    static let adoptedIdKeyPrefix = "friends.strapAdoptedId."

    /// The handle of a serial-derived id. Lower-case hex, 64 characters. Kotlin twin: `FriendsStrap.handle`.
    static func handle(adoptedId: String) -> String {
        FriendsKey.hex(SHA256.hash(data: Data(FriendsWire.strapHandleInput(adoptedId: adoptedId).utf8)))
    }

    /// Records that the strap registered as `deviceId` has the serial-derived id `adoptedId`. Called
    /// where a serial is confirmed; writes only when the value changed.
    static func note(adoptedId: String, forDeviceId deviceId: String, defaults: UserDefaults = .standard) {
        guard !adoptedId.isEmpty, !deviceId.isEmpty else { return }
        let key = adoptedIdKeyPrefix + deviceId
        if defaults.string(forKey: key) != adoptedId { defaults.set(adoptedId, forKey: key) }
    }

    static func adoptedId(forDeviceId deviceId: String, defaults: UserDefaults = .standard) -> String? {
        defaults.string(forKey: adoptedIdKeyPrefix + deviceId).flatMap { $0.isEmpty ? nil : $0 }
    }

    /// Whether a device of this kind has a serial to bind to: a WHOOP strap or an Oura ring.
    static func hasSerial(_ device: PairedDevice) -> Bool {
        SourceIdentity.isWhoop(device) || device.id.hasPrefix(ExperimentalBrand.oura.idPrefix + "-")
    }

    static func identity(hasSerial: Bool, adoptedId: String?) -> Identity {
        if let adoptedId { return .handle(handle(adoptedId: adoptedId)) }
        return hasSerial ? .pending : .none
    }

    /// The identity of the registry's active device. Before the registry exists, or with nothing active,
    /// nothing is known yet: that reads as a strap not read, never as no strap, so an account is not
    /// made unbound by a race with launch.
    @MainActor
    static func identity(registry: DeviceRegistry?, defaults: UserDefaults = .standard) -> Identity {
        #if DEBUG
        // `--friends-strap <adopted id>`: a stand-in strap for the simulator, which has none to read.
        if let flag = CommandLine.arguments.firstIndex(of: "--friends-strap"), flag + 1 < CommandLine.arguments.count {
            return .handle(handle(adoptedId: CommandLine.arguments[flag + 1]))
        }
        #endif
        guard let registry, let active = registry.devices.first(where: { $0.id == registry.activeDeviceId }) else {
            return .pending
        }
        return identity(hasSerial: hasSerial(active), adoptedId: adoptedId(forDeviceId: active.id, defaults: defaults))
    }
}
```

- [ ] **Step 4: Record a WHOOP's serial where it is confirmed**

In `Strand/BLE/BLEManager.swift`, `adoptWhoopSerialIdentity()` is reached from both places a WHOOP serial is confirmed (the 5/MG's DIS read and a 4.0's twice-seen hello serial). Add one call as its first statement, so a legacy `my-whoop` row, which the guard below turns away, is recorded too:

```swift
    private func adoptWhoopSerialIdentity() {
        noteStrapSerialForFriends()
        guard let rs = registryStore,
```

Then add this method directly after the closing brace of `adoptWhoopSerialIdentity()`, before the doc comment that begins `/// The strap's own DIS attestation is ground truth`. Placed there it does not come between that doc comment and the declaration it documents, which is what `Tools/doc_comment_lint.py` would otherwise report:

```swift
    /// Friends (fork feature): keeps the confirmed serial's id against the active registry row, so the
    /// friends account can be bound to this strap. A legacy `my-whoop` row is never re-pointed onto its
    /// serial, so without this nothing outside this class would know which strap it is. Written under
    /// the id the row has now and the id it will have once adopted. Touches no connection state.
    private func noteStrapSerialForFriends() {
        guard let rs = registryStore,
              let adopted = WhoopSerialIdentity.adoptedId(serial: adoptableSerial),
              let active = try? rs.all().first(where: { $0.status == .active }),
              SourceIdentity.isWhoop(active)
        else { return }
        FriendsStrap.note(adoptedId: adopted, forDeviceId: active.id)
        FriendsStrap.note(adoptedId: adopted, forDeviceId: adopted)
    }
```

Nothing else in `BLEManager` changes. The adopted id embeds the full serial: it goes to UserDefaults only, never to `log(...)`.

- [ ] **Step 5: Record an Oura ring's serial where it is confirmed**

In `Strand/BLE/SourceCoordinator.swift`, in `adoptOuraSerial(currentId:serial:)`, add three lines between the `let serialId` line and the `guard`:

```swift
        let serialId = "\(ExperimentalBrand.oura.idPrefix)-\(serial)"
        // Friends (fork feature): the ring's serial id, kept so the friends account can be bound to it.
        FriendsStrap.note(adoptedId: serialId, forDeviceId: currentId)
        FriendsStrap.note(adoptedId: serialId, forDeviceId: serialId)
        guard currentId != serialId, registry.activeDeviceId == currentId else { return }
```

- [ ] **Step 6: Run the test, the doc-comment lint, and both builds**

```bash
xcodegen generate >/dev/null && xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO test -only-testing:StrandTests/FriendsStrapTests 2>&1 | grep -E 'error:|Executed|TEST' | tail -4
python3 Tools/doc_comment_lint.py 2>&1 | tail -3
```

Expected: `Executed 5 tests, with 0 failures`; the lint prints what it printed before this task (run it on a clean checkout first if unsure: it reports per-file counts, so a new finding shows up against lines that were not touched). Then the iOS build command: `** BUILD SUCCEEDED **`.

- [ ] **Step 7: Commit**

```bash
git add Strand/Friends/FriendsStrap.swift Strand/BLE/BLEManager.swift Strand/BLE/SourceCoordinator.swift StrandTests/FriendsStrapTests.swift
git commit -m "feature: the strap handle a friends account is bound to"
```

- [ ] **Step 8: Hand the hardware check to the owner**

CI and the simulator cannot exercise this path. Report to the owner that the following is theirs to do on a real strap, and that the commit is unverified on hardware until then:

1. Install the build, connect the strap, wait for the first sync.
2. In the debugger or a debug export, read UserDefaults for keys beginning `friends.strapAdoptedId.`. Expected: one for the active registry id (`my-whoop` on a legacy install, `whoop-<SERIAL>` otherwise) whose value is `whoop-<SERIAL>` with the strap's real serial.
3. A WHOOP 4.0 writes it on the second connect (its serial is trusted only once seen twice); a 5/MG on the first.
4. The strap connects, syncs and reconnects exactly as before.

---

### Task 4: The flip to version 2 (wire, store, and the screens that named the removed calls)

The wire, the store and the screens that call them cannot compile apart, so they change together. The task ends with both targets building, the client tests passing, and Friends turning on against a local server.

**Files:**
- Rewrite: `Strand/Friends/FriendsAPI.swift`, `Strand/Friends/FriendsStore.swift`, `Strand/Friends/FriendsView.swift`, `Strand/Friends/FriendsSheets.swift`, `StrandTests/FriendsClientTests.swift`
- Modify: `Strand/Friends/FriendsPieces.swift`, `Strand/Friends/FriendsAvatars.swift`, `Strand/Friends/FriendDetailView.swift`, `Strand/Friends/FriendsBoard.swift`, `Strand/Friends/FriendsHighlights.swift`, `Strand/App/TabRoute.swift`, `Strand/App/AppModel.swift`, `StrandiOS/App/StrandiOSApp.swift`, `StrandTests/FriendsBoardTests.swift`, `StrandTests/FriendsHighlightsTests.swift`, `Strand/Resources/Localizable.xcstrings`

**Interfaces:**
- Consumes: Tasks 1 to 3.
- Produces, for plan 3:
  - `FriendsStore`: `phase: FriendsPhase`, `isOn`, `feed`, `claims: [FriendsClaim]`, `unconfirmed: [FriendsDevice]`, `probationUntil: Int?`, `deviceID: String?`, `myID`, `devices: [FriendsDevice]?`, `invites: [FriendsInvite]?`, `pendingInviteCode: String?`, `sharePhoto`, `strapIdentity`
  - `turnOn(profile:)`, `turnOnWithoutStrap(profile:)`, `claimAccount(profile:)`, `cancelClaim()`, `pollClaim()`, `sync(repo:profile:force:)`, `refresh()`
  - `approve(_ claim: FriendsClaim)`, `decline(_:)`, `loadDevices()`, `removeDevice(_ id: String)`, `trustDevice(_:)`, `removeThisPhone() -> Bool`
  - `createInvite() -> FriendsInvite?`, `inviteCode(_ id: String) -> String?`, `loadInvites()`, `revokeInvite(_:)`, `redeem(_ text: String) -> FriendProfile?`
  - `exportText() -> String?`, `deleteAccount() -> Bool`, `setShare(_:repo:profile:)`, `setSharePhoto(_:repo:profile:)`
  - `FriendProfile.id`, `FriendsClaim`, `FriendsDevice`, `FriendsInvite`, `FriendsHero(name:id:own:imageData:avatarRev:)`, `FriendAvatar(name:size:own:imageData:id:rev:)`

- [ ] **Step 1: Rewrite the client tests**

Replace the body of `StrandTests/FriendsClientTests.swift` with the following. The last comment marks the one part of the current file that stays: everything from `// MARK: - Names and addresses` to the end of the class, minus the two tests named there.

```swift
import CryptoKit
import XCTest
import Foundation
import StrandAnalytics
import WhoopStore
@testable import Strand

/// The Friends client against the contract in `friends-server/README.md` (API version 2): what it
/// signs and sends, what it reads, and how Friends on this phone moves between its states.
final class FriendsClientTests: XCTestCase {

    /// Answers every request from a canned table and records what was asked.
    private final class Stub: URLProtocol {
        struct Seen {
            let method: String
            let path: String
            let headers: [String: String]
            let body: Data
        }

        /// Answers by "METHOD path". Each request takes the next answer of its list; the last one repeats.
        nonisolated(unsafe) static var answers: [String: [(status: Int, body: String)]] = [:]
        /// Requests answered with a redirect to the address given, keyed like `answers`.
        nonisolated(unsafe) static var redirects: [String: String] = [:]
        nonisolated(unsafe) static var seen: [Seen] = []

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func stopLoading() {}

        override func startLoading() {
            var body = request.httpBody ?? Data()
            if let stream = request.httpBodyStream {
                stream.open()
                var buffer = [UInt8](repeating: 0, count: 4096)
                while stream.hasBytesAvailable {
                    let n = stream.read(&buffer, maxLength: buffer.count)
                    if n <= 0 { break }
                    body.append(buffer, count: n)
                }
                stream.close()
            }
            let url = request.url!
            let path = url.path + (url.query.map { "?" + $0 } ?? "")
            let method = request.httpMethod ?? "GET"
            let key = method + " " + path
            Self.seen.append(Seen(method: method, path: path, headers: request.allHTTPHeaderFields ?? [:], body: body))
            if let target = Self.redirects[key], let location = URL(string: target) {
                let hop = HTTPURLResponse(url: url, statusCode: 302, httpVersion: "HTTP/1.1",
                                          headerFields: ["Location": target])!
                // A client that follows it asks `location` next, with the signature still on the request;
                // one that refuses is left with the 302 as its answer.
                client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: location), redirectResponse: hop)
                client?.urlProtocol(self, didReceive: hop, cacheStoragePolicy: .notAllowed)
                client?.urlProtocolDidFinishLoading(self)
                return
            }
            var list = Self.answers[key] ?? [(404, #"{"error":"not_found","message":""}"#)]
            let answer = list.count > 1 ? list.removeFirst() : list[0]
            if Self.answers[key] != nil { Self.answers[key] = list }
            let response = HTTPURLResponse(url: url, statusCode: answer.status, httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(answer.body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    /// A fixed software key, so no test touches the Keychain or the Secure Enclave.
    private struct TestSigner: FriendsSigner, @unchecked Sendable {
        let key = P256.Signing.PrivateKey()
        var publicKeySPKI: Data { key.publicKey.derRepresentation }
        var keyID: String { FriendsKey.hex(SHA256.hash(data: publicKeySPKI)) }
        func sign(_ message: Data) throws -> Data { try key.signature(for: message).derRepresentation }
    }

    private static let base = "https://friends.example"
    private static let fixedNow = 1_791_540_000
    private static let fixedNonce = "AAAAAAAAAAAAAAAAAAAAAA"
    private static let annaID = "00000000000000aa"
    private static let me = #"{"id":"00000000000000aa","name":"Anna","avatarRev":0,"share":{"scores":true,"sleep":true,"workouts":true,"hr":false},"strapBound":true,"device":{"id":"k","probationUntil":null}}"#
    private static let emptyFeed = #"{"serverTime":1791540000,"me":\#(me),"friends":[],"claims":[],"strapClaim":null}"#
    private static let handle = "98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707"

    private func stubbedSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [Stub.self]
        return URLSession(configuration: config)
    }

    private func client(_ signer: (any FriendsSigner)? = TestSigner(), offset: FriendsClockOffset = FriendsClockOffset()) -> FriendsClient {
        var c = FriendsClient(baseURL: URL(string: Self.base)!, signer: signer, session: stubbedSession())
        c.offset = offset
        c.now = { Self.fixedNow }
        c.nonce = { Self.fixedNonce }
        return c
    }

    /// Whether `seen` carries a signature by `signer` over exactly this method, target, time and body.
    private func isSigned(_ seen: Stub.Seen, by signer: TestSigner, target: String, time: Int = FriendsClientTests.fixedNow) throws -> Bool {
        guard seen.headers["X-Friends-Key"] == signer.keyID, seen.headers["X-Friends-Time"] == String(time),
              seen.headers["X-Friends-Nonce"] == Self.fixedNonce,
              let signature = seen.headers["X-Friends-Signature"].flatMap({ Data(base64Encoded: $0) }) else { return false }
        let text = FriendsWire.signingString(method: seen.method, target: target, time: time, nonce: Self.fixedNonce,
                                             bodyHashHex: FriendsKey.hex(SHA256.hash(data: seen.body)))
        return try P256.Signing.PublicKey(derRepresentation: signer.publicKeySPKI)
            .isValidSignature(P256.Signing.ECDSASignature(derRepresentation: signature), for: Data(text.utf8))
    }

    override func setUp() {
        super.setUp()
        Stub.answers = [:]
        Stub.redirects = [:]
        Stub.seen = []
    }

    // MARK: - Signing

    /// The README's vector, as this client produces it: the same method, target, time, nonce and body.
    func testARequestIsSignedOverItsMethodTargetTimeNonceAndBody() async throws {
        let signer = TestSigner()
        Stub.answers["PUT /v2/me/days/2026-10-10"] = [(204, "")]
        try await client(signer).upload(json: #"{"recovery":81}"#, on: "2026-10-10")
        let sent = try XCTUnwrap(Stub.seen.first)
        XCTAssertEqual(String(decoding: sent.body, as: UTF8.self), #"{"recovery":81}"#)
        XCTAssertEqual(FriendsKey.hex(SHA256.hash(data: sent.body)),
                       "a59ed6f5a3416c9b116d6d17ca709ffab4e839735f21ea3c8d301a045f567445")
        XCTAssertTrue(try isSigned(sent, by: signer, target: "/v2/me/days/2026-10-10"))
        XCTAssertNil(sent.headers["Authorization"], "there is no bearer token any more")
    }

    func testTheQueryIsPartOfWhatIsSigned() async throws {
        let signer = TestSigner()
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        _ = try await client(signer).feed()
        let sent = try XCTUnwrap(Stub.seen.first)
        XCTAssertTrue(try isSigned(sent, by: signer, target: "/v2/feed?days=7"))
        XCTAssertFalse(try isSigned(sent, by: signer, target: "/v2/feed"))
    }

    func testInfoIsTheOneCallThatIsNotSigned() async throws {
        Stub.answers["GET /v2/info"] = [(200, #"{"name":"renoop-friends","api":2,"time":1791540000}"#)]
        let info = try await client(nil).info()
        XCTAssertEqual(info, FriendsServerInfo(name: "renoop-friends", api: 2, time: 1_791_540_000))
        XCTAssertNil(Stub.seen.first?.headers["X-Friends-Signature"])
    }

    func testWithoutAKeyNothingElseIsSent() async {
        do {
            _ = try await client(nil).me()
            XCTFail("a call that needs the key was made without one")
        } catch {
            XCTAssertEqual(error as? FriendsAPIError, .notEnrolled)
        }
        XCTAssertTrue(Stub.seen.isEmpty)
    }

    /// A phone whose clock is wrong is told the server's, signs once more by it, and remembers the gap.
    func testAWrongClockSignsOnceMoreByTheServers() async throws {
        let signer = TestSigner()
        let offset = FriendsClockOffset()
        Stub.answers["GET /v2/me"] = [(401, #"{"error":"clock_skew","message":"","serverTime":1791545000}"#), (200, Self.me)]
        let me = try await client(signer, offset: offset).me()
        XCTAssertEqual(me.id, Self.annaID)
        XCTAssertEqual(Stub.seen.count, 2)
        XCTAssertTrue(try isSigned(Stub.seen[1], by: signer, target: "/v2/me", time: 1_791_545_000))
        XCTAssertEqual(offset.seconds, 5_000)
    }

    func testAClockTheServerRefusesTwiceIsTheAnswer() async {
        Stub.answers["GET /v2/me"] = [(401, #"{"error":"clock_skew","message":"","serverTime":1791545000}"#)]
        do {
            _ = try await client().me()
            XCTFail("a second refusal was retried")
        } catch {
            XCTAssertEqual(error as? FriendsAPIError, .clockSkew(serverTime: 1_791_545_000))
        }
        XCTAssertEqual(Stub.seen.count, 2, "once, and once more")
    }

    // MARK: - Calls

    func testEnrollingSendsTheKeyTheNameThePlatformAndTheStrap() async throws {
        let signer = TestSigner()
        Stub.answers["POST /v2/enroll"] = [(201, #"{"me":\#(Self.me)}"#)]
        let me = try await client(signer).enroll(name: "Anna", strap: Self.handle)
        XCTAssertEqual(me.strapBound, true)
        XCTAssertNil(me.device?.probationUntil)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: Stub.seen[0].body) as? [String: String])
        XCTAssertEqual(body, ["key": signer.publicKeySPKI.base64EncodedString(), "name": "Anna",
                              "platform": FriendsPlatform.current, "strap": Self.handle])
        XCTAssertTrue(try isSigned(Stub.seen[0], by: signer, target: "/v2/enroll"))

        _ = try await client(signer).enroll(name: "Anna", strap: nil)
        let unbound = try XCTUnwrap(JSONSerialization.jsonObject(with: Stub.seen[1].body) as? [String: String])
        XCTAssertNil(unbound["strap"], "no strap is no member, not a null")
    }

    func testARefusalCarriesTheServersCode() async {
        for (status, code, check) in [
            (401, "unknown_key", \FriendsAPIError.isUnknownKey), (409, "strap_bound", \.isStrapBound),
            (403, "probation", \.isProbation), (404, "no_claim", \.isNoClaim), (404, "strap_free", \.isStrapFree),
            (409, "already_enrolled", \.isAlreadyEnrolled),
        ] as [(Int, String, KeyPath<FriendsAPIError, Bool>)] {
            Stub.answers["GET /v2/me"] = [(status, #"{"error":"\#(code)","message":""}"#)]
            do {
                _ = try await client().me()
                XCTFail(code)
            } catch {
                XCTAssertEqual(error as? FriendsAPIError, .server(code: code, message: "", status: status))
                XCTAssertEqual((error as? FriendsAPIError)?[keyPath: check], true, code)
            }
        }
        XCTAssertFalse(FriendsAPIError.server(code: "unknown_key", message: "", status: 500).isUnknownKey)
    }

    func testBindingAStrapIsDoneOrAskedOfItsOwner() async throws {
        Stub.answers["PUT /v2/me/strap"] = [
            (200, #"{"bound":true}"#),
            (202, #"{"claim":{"id":7,"kind":"take","code":"481902","state":"pending","platform":"ios","createdAt":1791540000,"maturesAt":1791712800}}"#),
        ]
        let first = try await client().putStrap(Self.handle)
        XCTAssertEqual(first, .bound)
        let second = try await client().putStrap(Self.handle)
        XCTAssertEqual(second, .claimed(FriendsClaim(id: 7, kind: .take, code: "481902", state: .pending, platform: "ios",
                                                     createdAt: 1_791_540_000, maturesAt: 1_791_712_800)))
        XCTAssertEqual(String(decoding: Stub.seen[0].body, as: UTF8.self), #"{"strap":"\#(Self.handle)"}"#)
    }

    func testAnInviteIsMadeListedAndUsed() async throws {
        Stub.answers["POST /v2/invites"] = [(201, #"{"id":"0123456789abcdef","code":"K7QM2-XRD4P","expiresAt":1792144800}"#)]
        Stub.answers["GET /v2/invites"] = [(200, #"{"invites":[{"id":"0123456789abcdef","createdAt":1791540000,"expiresAt":1792144800}]}"#)]
        Stub.answers["POST /v2/invites/redeem"] = [(200, #"{"friend":{"id":"00000000000000aa","name":"Anna","avatarRev":2}}"#)]
        Stub.answers["DELETE /v2/invites/0123456789abcdef"] = [(204, "")]
        let made = try await client().createInvite()
        XCTAssertEqual(made.code, "K7QM2-XRD4P")
        let listed = try await client().invites()
        XCTAssertEqual(listed.map(\.id), ["0123456789abcdef"])
        XCTAssertNil(listed[0].code, "a listing never repeats a code")
        let friend = try await client().redeem("K7QM2XRD4P")
        XCTAssertEqual(friend, FriendProfile(id: Self.annaID, name: "Anna", avatarRev: 2))
        XCTAssertEqual(String(decoding: Stub.seen[2].body, as: UTF8.self), #"{"code":"K7QM2XRD4P"}"#)
        try await client().revokeInvite("0123456789abcdef")
        XCTAssertEqual(Stub.seen[3].method, "DELETE")
    }

    /// An id goes into a path only when it has the shape of one.
    func testWhatIsNotAnIdNeverReachesAPath() async {
        for id in ["../me", "anna", "00000000000000AA", ""] {
            do {
                _ = try await client().person(id)
                XCTFail(id)
            } catch {
                XCTAssertEqual(error as? FriendsAPIError, .server(code: "no_such_user", message: "", status: 404))
            }
        }
        XCTAssertTrue(Stub.seen.isEmpty)
        XCTAssertTrue(FriendsID.isValid(Self.annaID))
    }

    // MARK: - Days

    /// A day goes up unless its text is the one the server last accepted; the phone keeps the text's
    /// SHA-256. The literal is Android's (`FriendsDayPayloadTest.theFingerprintIsSha256Hex`).
    func testAnUnchangedDayIsSkippedByItsFingerprint() {
        XCTAssertEqual(FriendsUploadPolicy.fingerprint("{}"),
                       "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a")
        XCTAssertEqual(FriendsUploadPolicy.fingerprint(Data("{}".utf8)), FriendsUploadPolicy.fingerprint("{}"))
        XCTAssertNotEqual(FriendsUploadPolicy.fingerprint("{}"), FriendsUploadPolicy.fingerprint("{ }"))
        let json = FriendsDayBuilder.json(FriendsDay(recovery: 81))
        XCTAssertTrue(FriendsUploadPolicy.shouldUpload(lastAcceptedFingerprint: nil, json: json))
        let accepted = FriendsUploadPolicy.fingerprint(json)
        XCTAssertFalse(FriendsUploadPolicy.shouldUpload(lastAcceptedFingerprint: accepted, json: json))
        XCTAssertTrue(FriendsUploadPolicy.shouldUpload(lastAcceptedFingerprint: accepted,
                                                       json: FriendsDayBuilder.json(FriendsDay(recovery: 82))))
    }

    /// A workout goes to a friend under its English label, whatever language this phone is in, with its
    /// active time and its stored Strain.
    func testAWorkoutRowIsSentUnderItsEnglishLabel() {
        func row(_ sport: String, duration: Double?) -> WorkoutRow {
            WorkoutRow(startTs: 1_791_530_400, endTs: 1_791_532_260, sport: sport, source: "my-whoop",
                       durationS: duration, energyKcal: 310.3, avgHr: 139, maxHr: 162, strain: 35.2,
                       distanceM: nil, zonesJSON: nil, notes: nil, steps: nil)
        }
        let strength = FriendsUploader.workout(row("TraditionalStrengthTraining", duration: 1_800))
        XCTAssertEqual(strength.sport, "Traditional Strength Training")
        XCTAssertEqual(strength.durationS, 1_800)
        XCTAssertEqual(strength.strain, 35.2)
        XCTAssertEqual(strength.kcal, 310.3)
        let detected = FriendsUploader.workout(row("detected", duration: nil))
        XCTAssertEqual(detected.sport, "Activity")
        XCTAssertEqual(detected.durationS, 1_860, "a missing duration falls back to the span")
    }

    /// The feed as the server writes it: a friend with a day, the requests that wait for an answer, and
    /// around what cannot be read (a friend without an id, a claim of an unknown kind) instead of failing.
    func testTheFeedDecodesAroundWhatCannotBeRead() async throws {
        let claim = #"{"id":3,"kind":"join","code":"481902","state":"pending","platform":"android","createdAt":1791540000,"maturesAt":1791712800}"#
        Stub.answers["GET /v2/feed?days=7"] = [(200, """
        {"serverTime":1791540000,"me":\(Self.me),
         "friends":[{"id":"00000000000000bb","name":"Max","avatarRev":3,
                     "share":{"scores":true,"sleep":false,"workouts":true,"hr":false},
                     "days":[{"day":"2026-10-09","updatedAt":1791500000,"recovery":64,"strain":null},
                             {"day":"2026-10-10","updatedAt":1791539000,"recovery":81,"strain":38.6,"sleepScore":88},
                             {"day":"yesterday","recovery":1}]},
                    {"name":"No id"},{"id":"not-an-id","name":"Bad id"}],
         "claims":[\(claim),{"id":4,"kind":"sideways","code":"1","state":"pending","createdAt":1}],
         "strapClaim":null,
         "unconfirmed":[{"id":"k2","platform":"android","addedAt":1791500000,"lastSeenAt":1791539000,"probationUntil":1792104800,"current":false}]}
        """)]
        let (feed, raw) = try await client().feed()
        XCTAssertFalse(raw.isEmpty)
        XCTAssertEqual(feed.serverTime, 1_791_540_000)
        XCTAssertEqual(feed.me.id, Self.annaID)
        XCTAssertEqual(feed.friends.map(\.id), ["00000000000000bb"])
        XCTAssertEqual(feed.friends[0].days?.map(\.day), ["2026-10-10", "2026-10-09"], "newest first, unreadable dropped")
        XCTAssertEqual(feed.friends[0].latestDay?.summary.recovery, 81)
        XCTAssertEqual(feed.friends[0].share?.sleep, false)
        XCTAssertEqual(feed.claims.map(\.id), [3])
        XCTAssertEqual(feed.claims[0].kind, .join)
        XCTAssertNil(feed.strapClaim)
        XCTAssertEqual(feed.unconfirmed.map(\.platform), ["android"])
        let bare: FriendsFeed = try FriendsClient.decode(Data(Self.emptyFeed.utf8))
        XCTAssertEqual(bare.unconfirmed, [], "a feed without the member reads as none")
    }

    func testAPictureGoesUpAsAJpegAndOneTooLargeIsNotSent() async throws {
        Stub.answers["PUT /v2/me/avatar"] = [(200, Self.me)]
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0]) + Data(repeating: 0x30, count: 64)
        _ = try await client().putAvatar(jpeg)
        XCTAssertEqual(Stub.seen[0].headers["Content-Type"], "image/jpeg")
        XCTAssertEqual(Stub.seen[0].body, jpeg)
        do {
            _ = try await client().putAvatar(Data(repeating: 0xFF, count: FriendsLimits.maxAvatarBytes + 1))
            XCTFail("a picture over the limit was sent")
        } catch {
            XCTAssertEqual(error as? FriendsAPIError, .server(code: "too_large", message: "", status: 413))
        }
        XCTAssertEqual(Stub.seen.count, 1)
    }

    /// A redirect is the answer, not a hop: a signed request goes to the host the wearer named only.
    func testARedirectIsAFailureNotAHop() async {
        Stub.redirects["GET /v2/me"] = "https://elsewhere.example/v2/me"
        do {
            _ = try await client().me()
            XCTFail("the redirect was followed")
        } catch {
            XCTAssertEqual((error as? FriendsAPIError)?.isRedirect, true)
        }
        XCTAssertEqual(Stub.seen.count, 1)
    }

    // MARK: - The store

    @MainActor
    private func store(_ identity: FriendsStrap.Identity, signer: (any FriendsSigner)? = TestSigner(),
                       defaults: UserDefaults? = nil,
                       keys: FriendsMemoryKeyStorage = FriendsMemoryKeyStorage()) throws -> (FriendsStore, UserDefaults) {
        let defaults = try defaults ?? XCTUnwrap(UserDefaults(suiteName: "friends-store-\(UUID().uuidString)"))
        defaults.set(Self.base, forKey: FriendsStore.addressKey)
        let store = FriendsStore(defaults: defaults, cache: nil, keys: keys, session: stubbedSession(), signer: signer)
        store.strapIdentity = { identity }
        return (store, defaults)
    }

    @MainActor
    func testOffNothingIsSentAndNoKeyIsMade() async throws {
        let keys = FriendsMemoryKeyStorage()
        let (store, _) = try store(.handle(Self.handle), signer: nil, keys: keys)
        XCTAssertEqual(store.phase, .off)
        await store.refresh()
        let repo = Repository(deviceId: "test-friends")
        let profile = ProfileStore()
        await store.sync(repo: repo, profile: profile, force: true)
        store.daysChanged(repo: repo, profile: profile)
        _ = await store.person(Self.annaID)
        XCTAssertTrue(Stub.seen.isEmpty)
        XCTAssertNil(store.deviceID)
        XCTAssertNil(keys.read(account: FriendsKey.account(forServer: Self.base)))
    }

    @MainActor
    func testTurningOnEnrolsWithTheStrapAndGoesOn() async throws {
        let (store, defaults) = try store(.handle(Self.handle))
        Stub.answers["POST /v2/enroll"] = [(201, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        await store.turnOn(name: "  Anna ")
        XCTAssertEqual(store.phase, .on)
        XCTAssertEqual(store.myID, Self.annaID)
        XCTAssertNil(store.errorText)
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: Stub.seen[0].body) as? [String: String])
        XCTAssertEqual(body["name"], "Anna")
        XCTAssertEqual(body["strap"], Self.handle)
        XCTAssertEqual(defaults.string(forKey: FriendsStore.phaseKey), "on")
        XCTAssertEqual(defaults.string(forKey: FriendsStore.boundHandleKey), Self.handle)
        XCTAssertEqual(defaults.string(forKey: FriendsStore.pushedNameKey), "Anna", "the name just sent is not sent again")
    }

    @MainActor
    func testWithoutANameNothingIsSent() async throws {
        let (store, _) = try store(.handle(Self.handle))
        await store.turnOn(name: "   ")
        XCTAssertEqual(store.phase, .off)
        XCTAssertNotNil(store.errorText)
        XCTAssertTrue(Stub.seen.isEmpty)
    }

    @MainActor
    func testAStrapNotReadYetWaitsAndSendsNothing() async throws {
        let (store, defaults) = try store(.pending)
        await store.turnOn(name: "Anna")
        XCTAssertEqual(store.phase, .waitingForStrap)
        XCTAssertTrue(Stub.seen.isEmpty)
        XCTAssertEqual(defaults.string(forKey: FriendsStore.phaseKey), "waitingForStrap")
        // The strap is read: the same tap finishes by itself.
        store.strapIdentity = { .handle(Self.handle) }
        Stub.answers["POST /v2/enroll"] = [(201, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        await store.turnOn(name: "Anna")
        XCTAssertEqual(store.phase, .on)
    }

    @MainActor
    func testAStrapWithAnAccountAsksWhoseItIsAndAClaimSurvivesARelaunch() async throws {
        let signer = TestSigner()
        let (store, defaults) = try store(.handle(Self.handle), signer: signer)
        Stub.answers["POST /v2/enroll"] = [(409, #"{"error":"strap_bound","message":""}"#)]
        await store.turnOn(name: "Anna")
        XCTAssertEqual(store.phase, .strapBound)
        XCTAssertNil(store.errorText, "a strap with an account is a question, not a failure")

        let claim = #"{"id":3,"kind":"join","code":"481902","state":"pending","platform":"ios","createdAt":1791540000,"maturesAt":1791712800}"#
        Stub.answers["POST /v2/claims"] = [(201, #"{"claim":\#(claim)}"#)]
        await store.claimAccount(name: "Anna")
        guard case let .waiting(waiting) = store.phase else { return XCTFail("not waiting") }
        XCTAssertEqual(waiting.code, "481902")
        XCTAssertEqual(waiting.maturesAt, 1_791_712_800)

        let (relaunched, _) = try self.store(.handle(Self.handle), signer: signer, defaults: defaults)
        XCTAssertEqual(relaunched.phase, .waiting(waiting))

        Stub.answers["DELETE /v2/claims/mine"] = [(204, "")]
        await relaunched.cancelClaim()
        XCTAssertEqual(relaunched.phase, .strapBound)
    }

    /// A phone that joins an account does not send its own profile: what the account shows stands.
    @MainActor
    func testAGrantedClaimJoinsWithoutPushingTheProfile() async throws {
        let signer = TestSigner()
        let (store, defaults) = try store(.handle(Self.handle), signer: signer)
        let claim = { (state: String) in
            #"{"claim":{"id":3,"kind":"join","code":"481902","state":"\#(state)","platform":"ios","createdAt":1791540000,"maturesAt":null}}"#
        }
        Stub.answers["POST /v2/enroll"] = [(409, #"{"error":"strap_bound","message":""}"#)]
        Stub.answers["POST /v2/claims"] = [(201, claim("pending"))]
        await store.turnOn(name: "Other")
        await store.claimAccount(name: "Other")
        Stub.answers["GET /v2/claims/mine"] = [(200, claim("pending")), (200, claim("approved"))]
        Stub.answers["GET /v2/me"] = [(200, Self.me)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        await store.pollClaim()
        guard case .waiting = store.phase else { return XCTFail("left waiting on a pending claim") }
        await store.pollClaim()
        XCTAssertEqual(store.phase, .on)
        XCTAssertTrue(defaults.bool(forKey: FriendsStore.adoptProfileKey))
        XCTAssertNil(defaults.string(forKey: FriendsStore.pushedNameKey))
        XCTAssertFalse(Stub.seen.contains { $0.method == "PATCH" })
    }

    @MainActor
    func testADeclinedClaimGoesBackToTheChoice() async throws {
        let (store, _) = try store(.handle(Self.handle))
        Stub.answers["POST /v2/enroll"] = [(409, #"{"error":"strap_bound","message":""}"#)]
        Stub.answers["POST /v2/claims"] = [(201, #"{"claim":{"id":3,"kind":"join","code":"481902","state":"pending","createdAt":1}}"#)]
        Stub.answers["GET /v2/claims/mine"] = [(200, #"{"claim":{"id":3,"kind":"join","code":"481902","state":"declined","createdAt":1}}"#)]
        await store.turnOn(name: "Anna")
        await store.claimAccount(name: "Anna")
        await store.pollClaim()
        XCTAssertEqual(store.phase, .strapBound)
        XCTAssertNotNil(store.errorText)
    }

    /// A phone removed from the account by another one goes off, and keeps its key so that turning
    /// Friends on again asks to rejoin as the same phone.
    @MainActor
    func testAPhoneTheServerNoLongerKnowsGoesOffAndKeepsItsKey() async throws {
        let (store, defaults) = try store(.handle(Self.handle))
        Stub.answers["POST /v2/enroll"] = [(201, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed), (401, #"{"error":"unknown_key","message":""}"#)]
        await store.turnOn(name: "Anna")
        XCTAssertEqual(store.phase, .on)
        let epoch = store.epoch
        await store.refresh()
        XCTAssertEqual(store.phase, .off)
        XCTAssertNotNil(store.errorText)
        XCTAssertNotNil(store.deviceID)
        XCTAssertNil(store.feed)
        XCTAssertGreaterThan(store.epoch, epoch)
        XCTAssertNil(defaults.string(forKey: FriendsStore.boundHandleKey))
    }

    @MainActor
    func testDeletingTheAccountForgetsTheKey() async throws {
        let keys = FriendsMemoryKeyStorage()
        let (store, _) = try store(.none, signer: nil, keys: keys)
        Stub.answers["POST /v2/enroll"] = [(201, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        Stub.answers["POST /v2/me/delete"] = [(204, "")]
        await store.turnOn(name: "Anna")
        XCTAssertEqual(store.phase, .on)
        XCTAssertNotNil(keys.read(account: FriendsKey.account(forServer: Self.base)), "the key is made by turning on")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: Stub.seen[0].body) as? [String: String])
        XCTAssertNil(body["strap"], "a device with no serial enrols unbound")
        let deleted = await store.deleteAccount()
        XCTAssertTrue(deleted)
        XCTAssertEqual(store.phase, .off)
        XCTAssertNil(store.deviceID)
        XCTAssertNil(keys.read(account: FriendsKey.account(forServer: Self.base)))
    }

    @MainActor
    func testAnInviteIsReadOutOfALinkAndWhatIsNotACodeIsNotSent() async throws {
        let (store, _) = try store(.none)
        Stub.answers["POST /v2/enroll"] = [(201, #"{"me":\#(Self.me)}"#)]
        Stub.answers["GET /v2/feed?days=7"] = [(200, Self.emptyFeed)]
        Stub.answers["POST /v2/invites/redeem"] = [(200, #"{"friend":{"id":"00000000000000bb","name":"Max","avatarRev":0}}"#)]
        await store.turnOn(name: "Anna")
        let sent = Stub.seen.count
        let nobody = await store.redeem("hello")
        XCTAssertNil(nobody)
        XCTAssertNotNil(store.errorText)
        XCTAssertEqual(Stub.seen.count, sent)
        let friend = await store.redeem("https://friends.example/i/k7qm2-xrd4p")
        XCTAssertEqual(friend?.name, "Max")
        XCTAssertEqual(String(decoding: Stub.seen[sent].body, as: UTF8.self), #"{"code":"K7QM2XRD4P"}"#)
    }

    // MARK: - Clock

    /// Every "N min ago" counts from the server's clock, carried forward by the time since the feed
    /// arrived, so a phone whose own clock is wrong still reads the right age.
    func testTheServersClockIsCarriedForwardFromTheFeed() {
        XCTAssertEqual(FriendsClock.serverNow(serverTime: 1_000, fetchedAtDevice: 5_000, nowDevice: 5_090), 1_090)
        XCTAssertEqual(FriendsClock.serverNow(serverTime: 1_000, fetchedAtDevice: 5_000, nowDevice: 4_000), 1_000,
                       "a device clock set back counts as no time")
    }

    // >>> kept unchanged from here to the end of the class: `// MARK: - Names and addresses` and its
    // >>> tests, EXCEPT `testTheNameRuleIsTheServers` and `testThePasswordRuleIsTheServers`, which are
    // >>> deleted with the rules they tested.
}
```

- [ ] **Step 2: Rewrite `FriendsAPI.swift`**

Replace `Strand/Friends/FriendsAPI.swift` with the following. At each `// >>> kept` marker, the named declarations of the current file stay exactly as they are. `FriendRelation`, `FriendRequests`, `FriendsNick` and `FriendsPassword` are deleted.

```swift
//  FriendsAPI.swift
//  NOOP · Friends — the client of the friends service (reNOOP fork feature).
//
//  The contract is `friends-server/README.md`, API version 2. There is no name to type and no password:
//  the phone holds a P-256 key (`FriendsKey`) and signs every request with it, and people are known by
//  an opaque account id. This file is the wire only; what the app does with it lives in `FriendsStore`.

import CryptoKit
import Foundation
import StrandAnalytics

/// A person as others see them. `share` and `days` come only where the call returns them; `strapBound`
/// and `device` only for the wearer's own account.
struct FriendProfile: Codable, Equatable, Identifiable, Sendable {
    /// The account's id on the server: opaque, never shown, never typed.
    var id: String
    var name: String
    /// 0 when the account has no picture; otherwise it changes whenever the picture does.
    var avatarRev: Int = 0
    var share: FriendsShare?
    /// Newest day first.
    var days: [FriendFeedDay]?
    var strapBound: Bool?
    var device: FriendsDeviceState?

    /// The newest uploaded day, which leads the feed.
    var latestDay: FriendFeedDay? { days?.first }
}

extension FriendProfile {
    /// Only the id is required. Every other member reads as absent when it is missing, null or of the
    /// wrong kind, so a section a friend does not share and one a newer server spells differently both
    /// read as not there rather than failing the whole answer.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let id = try c.decode(String.self, forKey: .id)
        guard FriendsID.isValid(id) else {
            throw DecodingError.dataCorruptedError(forKey: .id, in: c, debugDescription: "not an account id")
        }
        self.id = id
        name = ((try? c.decode(String.self, forKey: .name)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        avatarRev = max(0, (try? c.decode(Int.self, forKey: .avatarRev)) ?? 0)
        share = try? c.decode(FriendsShare.self, forKey: .share)
        days = (try? c.decode([Lossy<FriendFeedDay>].self, forKey: .days))
            .map { $0.compactMap(\.value).sorted { $0.day > $1.day } }
        strapBound = try? c.decode(Bool.self, forKey: .strapBound)
        device = try? c.decode(FriendsDeviceState.self, forKey: .device)
    }
}

/// An account id as the server mints it: 16 lower-case hex characters. Checked wherever an id goes
/// into a path or a file name.
enum FriendsID {
    static func isValid(_ id: String) -> Bool {
        id.unicodeScalars.count == 16 && id.unicodeScalars.allSatisfy {
            ("0"..."9").contains($0) || ("a"..."f").contains($0)
        }
    }
}

// >>> kept unchanged from the current file: `struct Lossy` and `struct FriendFeedDay`, with their doc comments

/// How this phone stands on the wearer's own account.
struct FriendsDeviceState: Codable, Equatable, Sendable {
    /// This phone's key id.
    var id: String
    /// Unix seconds until which this phone may only read and upload; nil for a confirmed phone.
    var probationUntil: Int?
}

/// A request waiting on a strap: a new phone asking to join the account (`join`), or another account
/// asking for the strap (`take`).
struct FriendsClaim: Codable, Equatable, Identifiable, Sendable {
    enum Kind: String, Codable, Sendable { case join, take }
    enum State: String, Codable, Sendable { case pending, approved, declined, expired }

    var id: Int
    var kind: Kind
    /// Six digits shown on both phones, so two claims can be told apart.
    var code: String
    var state: State
    var platform: String?
    var createdAt: Int
    /// When silence alone will grant it (unix seconds); nil once settled.
    var maturesAt: Int?
}

/// One phone of the wearer's account.
struct FriendsDevice: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var platform: String
    var addedAt: Int
    var lastSeenAt: Int
    var probationUntil: Int?
    /// This phone.
    var current: Bool
}

struct FriendsInvite: Codable, Equatable, Identifiable, Sendable {
    var id: String
    /// Said once, in the answer that made the invite.
    var code: String?
    var createdAt: Int?
    var expiresAt: Int
}

struct FriendsFeed: Codable, Equatable, Sendable {
    /// The server's clock when it answered (unix seconds): what every "N min ago" on the tab counts from.
    var serverTime: Int
    var me: FriendProfile
    var friends: [FriendProfile]
    /// Claims waiting for this account's answer.
    var claims: [FriendsClaim]
    /// This account's own request for a strap bound to someone else.
    var strapClaim: FriendsClaim?
    /// The account's other phones that joined without confirmation and are still on probation.
    var unconfirmed: [FriendsDevice] = []
}

extension FriendsFeed {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        serverTime = try c.decode(Int.self, forKey: .serverTime)
        me = try c.decode(FriendProfile.self, forKey: .me)
        friends = ((try? c.decode([Lossy<FriendProfile>].self, forKey: .friends)) ?? []).compactMap(\.value)
        claims = ((try? c.decode([Lossy<FriendsClaim>].self, forKey: .claims)) ?? []).compactMap(\.value)
        strapClaim = try? c.decode(FriendsClaim.self, forKey: .strapClaim)
        unconfirmed = ((try? c.decode([Lossy<FriendsDevice>].self, forKey: .unconfirmed)) ?? []).compactMap(\.value)
    }
}

struct FriendsServerInfo: Codable, Equatable, Sendable {
    var name: String
    var api: Int
    var time: Int
}

/// What the server said to "bind this strap": done, or asked of the account that holds it.
enum FriendsStrapAnswer: Equatable, Sendable {
    case bound
    case claimed(FriendsClaim)
}

enum FriendsAPIError: Error, Equatable {
    /// The server's own refusal: its code, its sentence and the HTTP status.
    case server(code: String, message: String, status: Int)
    /// No usable server address is set.
    case notConfigured
    /// A call that needs this phone's key was made with none. Nothing was sent.
    case notEnrolled
    /// The request never got an answer, or the answer was not the contract's.
    case transport(String)
    /// This phone's clock is too far from the server's, which answered with its own.
    case clockSkew(serverTime: Int)

    private func refused(_ wanted: String, _ wantedStatus: Int) -> Bool {
        if case let .server(code, _, status) = self { return code == wanted && status == wantedStatus }
        return false
    }

    /// The server does not know this phone's key: it was removed from the account, or the account is gone.
    var isUnknownKey: Bool { refused("unknown_key", 401) }
    /// The strap already belongs to an account.
    var isStrapBound: Bool { refused("strap_bound", 409) }
    /// This phone joined without confirmation and may only read and upload for now.
    var isProbation: Bool { refused("probation", 403) }
    var isNoClaim: Bool { refused("no_claim", 404) }
    var isStrapFree: Bool { refused("strap_free", 404) }
    var isAlreadyEnrolled: Bool { refused("already_enrolled", 409) }

    /// The server answered with a redirect. It is never followed, so a signed request goes to the host
    /// the wearer named and nowhere else.
    var isRedirect: Bool {
        if case let .server(_, _, status) = self { return (300..<400).contains(status) }
        return false
    }

    /// Nothing reached the server or nothing came back: worth another try later, unlike a refusal.
    var isOffline: Bool {
        if case .transport = self { return true }
        return false
    }
}

// >>> kept unchanged from the current file: `enum FriendsLimits`, `enum FriendsServerAddress` and
// >>> `private final class FriendsNoRedirects`, with their doc comments (one sentence changes: Step 3)

/// The name this build gives the server for a phone. It is all the server learns about the device.
enum FriendsPlatform {
    static var current: String {
        #if os(macOS)
        return "mac"
        #else
        return "ios"
        #endif
    }
}

/// The gap between the server's clock and this phone's, learned from a `clock_skew` refusal and applied
/// to every later signature, so a phone whose clock is wrong still signs a time the server accepts.
final class FriendsClockOffset: @unchecked Sendable {
    static let shared = FriendsClockOffset()

    private let lock = NSLock()
    private var value = 0

    var seconds: Int {
        lock.lock(); defer { lock.unlock() }
        return value
    }

    func set(_ seconds: Int) {
        lock.lock(); defer { lock.unlock() }
        value = seconds
    }
}

/// One server, one key. Every call is a signed request and a decoded answer. Three things hold for
/// each: the address was checked by `FriendsServerAddress` before a client exists; a redirect is never
/// followed; and nothing is logged, not a URL, a body or a header.
struct FriendsClient: Sendable {
    let baseURL: URL
    /// This phone's key. Nil only for `info()`, the one call that is not signed.
    var signer: (any FriendsSigner)?
    var session: URLSession = FriendsClient.plainSession
    var offset: FriendsClockOffset = .shared
    /// The device clock and the nonce source, replaceable so a test can pin what is signed.
    var now: @Sendable () -> Int = { Int(Date().timeIntervalSince1970) }
    var nonce: @Sendable () -> String = { FriendsClient.randomNonce() }

    /// One session for the process: no cookies, no cache and no stored credentials, so nothing of an
    /// answer outlives the request that carried it.
    static let plainSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.urlCache = nil
        config.urlCredentialStorage = nil
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 30
        return URLSession(configuration: config)
    }()

    private static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return e
    }()

    /// 16 random bytes as base64url without padding: new for every request.
    static func randomNonce() -> String {
        Data((0..<16).map { _ in UInt8.random(in: .min ... .max) }).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    // MARK: Entering

    func info() async throws -> FriendsServerInfo {
        try decode(try await perform("GET", "/v2/info", body: nil, signed: false))
    }

    /// Makes an account for this phone's key, bound to `strap` when one is given. Answers the account
    /// the key already has when it has one. A strap that belongs to another account is `strap_bound`.
    func enroll(name: String, strap: String?) async throws -> FriendProfile {
        struct Body: Encodable { let key: String; let name: String; let platform: String; let strap: String? }
        struct Answer: Decodable { let me: FriendProfile }
        let answer: Answer = try await send("POST", "/v2/enroll", body: Body(
            key: try key().publicKeySPKI.base64EncodedString(), name: name,
            platform: FriendsPlatform.current, strap: strap))
        return answer.me
    }

    /// Asks to join the account `strap` is bound to. A phone of that account confirms it, or two days
    /// of its silence do.
    func fileClaim(strap: String) async throws -> FriendsClaim {
        struct Body: Encodable { let key: String; let strap: String; let platform: String }
        let answer: ClaimAnswer = try await send("POST", "/v2/claims", body: Body(
            key: try key().publicKeySPKI.base64EncodedString(), strap: strap, platform: FriendsPlatform.current))
        return answer.claim
    }

    func myClaim() async throws -> FriendsClaim {
        let answer: ClaimAnswer = try await get("/v2/claims/mine")
        return answer.claim
    }

    func withdrawClaim() async throws { try await sendNoAnswer("DELETE", "/v2/claims/mine") }

    // MARK: Account

    func me() async throws -> FriendProfile { try await get("/v2/me") }

    func update(name: String? = nil, share: FriendsShare? = nil) async throws -> FriendProfile {
        struct Body: Encodable { let name: String?; let share: FriendsShare? }
        return try await send("PATCH", "/v2/me", body: Body(name: name, share: share))
    }

    /// Uploads a JPEG picture. One over `FriendsLimits.maxAvatarBytes` is refused here, without a
    /// request: the server would refuse it before reading it and close the connection mid-upload, which
    /// reads as a lost connection rather than as the picture being too large.
    func putAvatar(_ jpeg: Data) async throws -> FriendProfile {
        guard jpeg.count <= FriendsLimits.maxAvatarBytes else {
            throw FriendsAPIError.server(code: "too_large", message: "", status: 413)
        }
        return try decode(try await perform("PUT", "/v2/me/avatar", body: jpeg, contentType: "image/jpeg"))
    }

    func deleteAvatar() async throws { try await sendNoAnswer("DELETE", "/v2/me/avatar") }

    /// The picture's bytes as the server holds them (JPEG, PNG or WebP).
    func avatar(_ id: String) async throws -> Data {
        try await perform("GET", "/v2/users/\(try checked(id))/avatar", body: nil, maxBytes: FriendsLimits.maxAvatarBytes)
    }

    /// Binds the account to the strap now worn. A free strap is bound at once; one that belongs to
    /// another account is asked for.
    func putStrap(_ handle: String) async throws -> FriendsStrapAnswer {
        struct Body: Encodable { let strap: String }
        struct Answer: Decodable { let bound: Bool?; let claim: FriendsClaim? }
        let answer: Answer = try await send("PUT", "/v2/me/strap", body: Body(strap: handle))
        if let claim = answer.claim { return .claimed(claim) }
        guard answer.bound == true else { throw FriendsAPIError.transport("unexpected answer") }
        return .bound
    }

    func approve(_ claimID: Int) async throws { try await sendNoAnswer("POST", "/v2/claims/\(claimID)/approve") }

    func decline(_ claimID: Int) async throws { try await sendNoAnswer("POST", "/v2/claims/\(claimID)/decline") }

    func devices() async throws -> [FriendsDevice] {
        struct Answer: Decodable { let devices: [FriendsDevice] }
        let answer: Answer = try await get("/v2/me/devices")
        return answer.devices
    }

    func removeDevice(_ keyID: String) async throws { try await sendNoAnswer("DELETE", "/v2/me/devices/\(try checkedKey(keyID))") }

    /// Ends the probation of a phone that joined without confirmation.
    func trustDevice(_ keyID: String) async throws {
        try await sendNoAnswer("POST", "/v2/me/devices/\(try checkedKey(keyID))/trust")
    }

    /// Everything the server holds for the account, as it sent it.
    func export() async throws -> Data { try await perform("GET", "/v2/me/export", body: nil) }

    func deleteAccount() async throws { try await sendNoAnswer("POST", "/v2/me/delete") }

    // MARK: Days

    /// Replaces the day `dayKey` ("yyyy-MM-dd") with `json`, a `FriendsDayBuilder.json` text.
    func upload(json: String, on dayKey: String) async throws {
        _ = try await perform("PUT", "/v2/me/days/" + dayKey, body: Data(json.utf8))
    }

    /// The feed and the bytes it was read from, which is what the tab keeps for an instant or offline start.
    func feed(days: Int = FriendsLimits.feedDays) async throws -> (feed: FriendsFeed, raw: Data) {
        let raw = try await perform("GET", "/v2/feed?days=\(days)", body: nil)
        return (try decode(raw), raw)
    }

    /// One person in full, the heart-rate line included: what their own page draws.
    func person(_ id: String, days: Int = FriendsLimits.feedDays) async throws -> FriendProfile {
        try await get("/v2/users/\(try checked(id))/days?days=\(days)")
    }

    // MARK: Friends

    /// Makes a one-time invite. Its code is in this answer and nowhere else.
    func createInvite() async throws -> FriendsInvite { try await send("POST", "/v2/invites", body: Optional<String>.none) }

    func invites() async throws -> [FriendsInvite] {
        struct Answer: Decodable { let invites: [FriendsInvite] }
        let answer: Answer = try await get("/v2/invites")
        return answer.invites
    }

    func revokeInvite(_ id: String) async throws { try await sendNoAnswer("DELETE", "/v2/invites/\(try checked(id))") }

    /// Uses an invite code: the two accounts are friends at once.
    func redeem(_ code: String) async throws -> FriendProfile {
        struct Body: Encodable { let code: String }
        struct Answer: Decodable { let friend: FriendProfile }
        let answer: Answer = try await send("POST", "/v2/invites/redeem", body: Body(code: code))
        return answer.friend
    }

    func unfriend(_ id: String) async throws { try await sendNoAnswer("DELETE", "/v2/friends/\(try checked(id))") }

    // MARK: Wire

    private struct ClaimAnswer: Decodable { let claim: FriendsClaim }

    private func key() throws -> any FriendsSigner {
        guard let signer else { throw FriendsAPIError.notEnrolled }
        return signer
    }

    /// An id that is safe to put in a path. Anything else is not an account the server could have.
    private func checked(_ id: String) throws -> String {
        guard FriendsID.isValid(id) else { throw FriendsAPIError.server(code: "no_such_user", message: "", status: 404) }
        return id
    }

    private func checkedKey(_ keyID: String) throws -> String {
        guard keyID.unicodeScalars.count == 64, keyID.unicodeScalars.allSatisfy({
            ("0"..."9").contains($0) || ("a"..."f").contains($0)
        }) else { throw FriendsAPIError.server(code: "no_such_device", message: "", status: 404) }
        return keyID
    }

    private func get<T: Decodable>(_ path: String) async throws -> T {
        try decode(try await perform("GET", path, body: nil))
    }

    private func send<T: Decodable, B: Encodable>(_ method: String, _ path: String, body: B?) async throws -> T {
        try decode(try await perform(method, path, body: try body.map { try Self.encoder.encode($0) }))
    }

    private func sendNoAnswer(_ method: String, _ path: String) async throws {
        _ = try await perform(method, path, body: nil)
    }

    static func decode<T: Decodable>(_ data: Data) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: data) }
        catch { throw FriendsAPIError.transport("unexpected answer") }
    }

    private func decode<T: Decodable>(_ data: Data) throws -> T { try Self.decode(data) }

    private func perform(_ method: String, _ path: String, body: Data?, contentType: String = "application/json",
                         maxBytes: Int = FriendsLimits.maxAnswerBytes, signed: Bool = true) async throws -> Data {
        do {
            return try await once(method, path, body: body, contentType: contentType, maxBytes: maxBytes, signed: signed)
        } catch let FriendsAPIError.clockSkew(serverTime) {
            // This phone's clock is off. Sign once more by the server's; a second refusal is the answer.
            offset.set(serverTime - now())
            return try await once(method, path, body: body, contentType: contentType, maxBytes: maxBytes, signed: signed)
        }
    }

    private func once(_ method: String, _ path: String, body: Data?, contentType: String, maxBytes: Int,
                      signed: Bool) async throws -> Data {
        guard let url = URL(string: baseURL.absoluteString + path) else { throw FriendsAPIError.notConfigured }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.httpMethod = method
        request.httpShouldHandleCookies = false
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = body
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        if signed {
            let signer = try key()
            let time = now() + offset.seconds
            let nonce = self.nonce()
            // `path` is what the server sees after its proxy: the signature covers it, the query included.
            let text = FriendsWire.signingString(method: method, target: path, time: time, nonce: nonce,
                                                 bodyHashHex: FriendsKey.hex(SHA256.hash(data: body ?? Data())))
            let signature: Data
            do { signature = try signer.sign(Data(text.utf8)) }
            catch { throw FriendsAPIError.transport("could not sign") }
            request.setValue(signer.keyID, forHTTPHeaderField: "X-Friends-Key")
            request.setValue(String(time), forHTTPHeaderField: "X-Friends-Time")
            request.setValue(nonce, forHTTPHeaderField: "X-Friends-Nonce")
            request.setValue(signature.base64EncodedString(), forHTTPHeaderField: "X-Friends-Signature")
        }
        var data = Data()
        let response: URLResponse
        do {
            let (bytes, answered) = try await session.bytes(for: request, delegate: FriendsNoRedirects.shared)
            response = answered
            // Read up to the limit and one byte past it, which is how an over-long answer is told apart.
            for try await byte in bytes {
                data.append(byte)
                if data.count > maxBytes { break }
            }
        } catch {
            throw FriendsAPIError.transport(error.localizedDescription)
        }
        guard let http = response as? HTTPURLResponse else { throw FriendsAPIError.transport("no answer") }
        guard data.count <= maxBytes else { throw FriendsAPIError.transport("unexpected answer") }
        guard (200..<300).contains(http.statusCode) else {
            struct Refusal: Decodable { let error: String; let message: String?; let serverTime: Int? }
            if let refusal = try? JSONDecoder().decode(Refusal.self, from: data) {
                if refusal.error == "clock_skew", let serverTime = refusal.serverTime {
                    throw FriendsAPIError.clockSkew(serverTime: serverTime)
                }
                throw FriendsAPIError.server(code: refusal.error, message: refusal.message ?? "", status: http.statusCode)
            }
            throw FriendsAPIError.server(code: "http_\(http.statusCode)", message: "", status: http.statusCode)
        }
        return data
    }
}
```

- [ ] **Step 3: One sentence in the kept `FriendsServerAddress`**

Its doc comment begins:

```swift
/// Which server address the app will talk to. Passwords and the session token travel in requests, so
/// the address must be HTTPS. The one exception is for development: plain HTTP to this device's own
```

Replace those two lines with:

```swift
/// Which server address the app will talk to. Requests carry the wearer's days and a signature, so the
/// address must be HTTPS. The one exception is for development: plain HTTP to this device's own
```

- [ ] **Step 4: Rewrite `FriendsStore.swift`**

Replace `Strand/Friends/FriendsStore.swift` with the following, keeping the declarations the markers name. In the kept `FriendsUploadPolicy`, add one function after `fingerprint(_ json: String)`:

```swift
    /// The same fingerprint for bytes: what tells one picture from another.
    static func fingerprint(_ data: Data) -> String { FriendsKey.hex(SHA256.hash(data: data)) }
```

The file:

```swift
//  FriendsStore.swift
//  NOOP · Friends — the account on this phone, what the tab shows, and the daily upload.
//
//  A reNOOP fork feature, and off until the wearer turns it on: until then no key exists, nothing is
//  sent anywhere and the rest of the app behaves exactly as it did. The server address is
//  configuration: the fork's own server until the wearer names another.
//
//  There is no name to type and no password. Turning Friends on makes this phone's key (`FriendsKey`,
//  in the Keychain, never in UserDefaults and never in a `.noopbak` backup) and an account bound to
//  the strap worn (`FriendsStrap`). The same strap on another phone asks to join that account; a new
//  strap on this phone takes the account over by itself.

import CryptoKit
import Foundation
import StrandAnalytics
import WhoopStore

// >>> kept unchanged from the current file: `enum FriendsUploadPolicy` (one function is added: Step 4),
// >>> `enum FriendsClock` and `struct FriendsFeedCache`, with their doc comments.
// >>> `enum FriendsKeychain` is deleted.

/// Where Friends stands on this phone.
enum FriendsPhase: Equatable {
    /// Never turned on, or turned off: no request is made.
    case off
    /// Turn On was tapped, and the strap worn has a serial this phone has not read yet.
    case waitingForStrap
    /// The strap already has an account. The wearer says whether it is theirs.
    case strapBound
    /// This phone asked to join the strap's account and waits for an answer.
    case waiting(FriendsClaim)
    case on

    /// What is kept between launches; a claim in flight is kept beside it.
    var stored: String {
        switch self {
        case .off: return "off"
        case .waitingForStrap: return "waitingForStrap"
        case .strapBound: return "strapBound"
        case .waiting: return "waiting"
        case .on: return "on"
        }
    }
}

@MainActor
final class FriendsStore: ObservableObject {
    static let shared = FriendsStore()

    /// The server address when the wearer named their own. Plain configuration; the key is NOT here.
    static let addressKey = "friends.serverAddress"
    static let phaseKey = "friends.phase"
    static let claimKey = "friends.claim"
    /// When the kept feed arrived, and when a day last went up (device clock, unix seconds).
    static let feedAtKey = "friends.feedAt"
    static let uploadedAtKey = "friends.uploadedAt"
    /// One fingerprint per uploaded day, under this prefix and the day's key.
    static let markPrefix = "friends.uploaded."
    /// What this phone last sent of the wearer's profile, so it sends again only after a change made here.
    static let pushedNameKey = "friends.pushedName"
    static let pushedPhotoKey = "friends.pushedPhoto"
    /// Set when this phone joined an account that already had a profile: the next run records this
    /// phone's profile as sent without sending it.
    static let adoptProfileKey = "friends.adoptProfile"
    static let sharePhotoKey = "friends.sharePhoto"
    /// The strap handle the server last accepted for the account.
    static let boundHandleKey = "friends.boundHandle"
    /// The codes of the invites made on this phone, by invite id, so a waiting invite can be shown again.
    static let inviteCodesKey = "friends.inviteCodes"
    /// Coming back to the tab inside this long of a good answer shows that answer.
    static let autoRefreshEverySeconds = 60

    @Published private(set) var phase: FriendsPhase
    @Published private(set) var feed: FriendsFeed?
    @Published private(set) var loading = false
    /// The last failure, already worded for the wearer; nil once a call succeeds.
    @Published var errorText: String?
    /// When a day last went up from this phone (device clock, unix seconds).
    @Published private(set) var lastUploadAt: Int?
    /// The account's phones and its waiting invites, once a screen has asked for them.
    @Published private(set) var devices: [FriendsDevice]?
    @Published private(set) var invites: [FriendsInvite]?
    /// An invite code that arrived by a link and waits for the wearer's yes.
    @Published var pendingInviteCode: String?

    /// Which strap is worn. The app sets it once its device registry exists; until then nothing is known.
    var strapIdentity: @MainActor () -> FriendsStrap.Identity = { .pending }

    private let defaults: UserDefaults
    private let cache: FriendsFeedCache?
    private let keys: FriendsKeyStorage
    private let session: URLSession
    private var key: (any FriendsSigner)?
    /// Counts the accounts this process has seen: it moves whenever Friends goes on or off. Work that
    /// began under one carries the value it started with, and what it brings back is kept only while
    /// that value still stands. Without it, leaving and joining as someone else while a request was in
    /// flight could file the old account's answer, or the mark of a day uploaded to it, under the new one.
    private(set) var epoch = 0
    /// Device clock (unix seconds) when `feed` arrived from the server; 0 before any did.
    private var feedFetchedAt = 0
    /// Whether the last refresh failed, in which case coming back to the tab tries again at once.
    private var lastRefreshFailed = false
    private var uploadTask: Task<Void, Never>?
    private var uploadRun: Task<Void, Never>?

    /// `signer` nil reads the kept key; tests pass their own and keep the Keychain out of it.
    init(defaults: UserDefaults = .standard, cache: FriendsFeedCache? = .standard,
         keys: FriendsKeyStorage = FriendsKeychainStorage(), session: URLSession = FriendsClient.plainSession,
         signer: (any FriendsSigner)? = nil) {
        self.defaults = defaults
        self.cache = cache
        self.keys = keys
        self.session = session
        let address = defaults.string(forKey: Self.addressKey).flatMap(FriendsServerAddress.normalized)
            ?? FriendsServerAddress.standard
        let held = signer ?? FriendsKey.load(server: address, storage: keys)
        self.key = held
        var phase = FriendsPhase.off
        switch defaults.string(forKey: Self.phaseKey) {
        case "on" where held != nil:
            phase = .on
        case "waiting" where held != nil:
            let claim = defaults.data(forKey: Self.claimKey).flatMap { try? JSONDecoder().decode(FriendsClaim.self, from: $0) }
            phase = claim.map { .waiting($0) } ?? .strapBound
        case "strapBound":
            phase = .strapBound
        case "waitingForStrap":
            phase = .waitingForStrap
        default:
            break
        }
        self.phase = phase
        if phase == .on {
            feed = cache?.read()
            feedFetchedAt = defaults.integer(forKey: Self.feedAtKey)
            lastUploadAt = defaults.object(forKey: Self.uploadedAtKey) as? Int
        }
    }

    var isOn: Bool { phase == .on }
    /// The server address in use; always one `FriendsServerAddress` accepts.
    var serverAddress: String {
        defaults.string(forKey: Self.addressKey).flatMap(FriendsServerAddress.normalized) ?? FriendsServerAddress.standard
    }
    var me: FriendProfile? { feed?.me }
    /// The wearer's own account id; empty before the first answer.
    var myID: String { feed?.me.id ?? "" }
    var share: FriendsShare { feed?.me.share ?? FriendsShare() }
    /// Requests waiting for this account's answer.
    var claims: [FriendsClaim] { feed?.claims ?? [] }
    /// The account's other phones that joined without confirmation: kept or removed from this one.
    var unconfirmed: [FriendsDevice] { feed?.unconfirmed ?? [] }
    /// Unix seconds until which this phone may only read and upload; nil for a confirmed phone.
    var probationUntil: Int? { feed?.me.device?.probationUntil }
    /// This phone's key id, which is its id among the account's phones.
    var deviceID: String? { key?.keyID }
    /// Whether the profile photo is sent for friends to see.
    var sharePhoto: Bool { defaults.object(forKey: Self.sharePhotoKey) as? Bool ?? true }

    /// The server's "now" (unix seconds): what every "N min ago" on the tab counts from.
    func serverNow(_ now: Date = Date()) -> Int {
        let device = Int(now.timeIntervalSince1970)
        guard let feed else { return device }
        return FriendsClock.serverNow(serverTime: feed.serverTime, fetchedAtDevice: feedFetchedAt, nowDevice: device)
    }

    /// A client signed with this phone's key. Without a key there is no client: nothing is sent.
    private func client() throws -> FriendsClient {
        guard let key else { throw FriendsAPIError.notEnrolled }
        guard let url = FriendsServerAddress.baseURL(serverAddress) else { throw FriendsAPIError.notConfigured }
        return FriendsClient(baseURL: url, signer: key, session: session)
    }

    /// The same, making the key first when there is none. The key exists from the first Turn On and
    /// never before it.
    private func clientMakingKey() throws -> FriendsClient {
        if key == nil { key = try FriendsKey.create(server: serverAddress, storage: keys) }
        return try client()
    }

    private func setPhase(_ next: FriendsPhase) {
        phase = next
        defaults.set(next.stored, forKey: Self.phaseKey)
        if case let .waiting(claim) = next, let data = try? JSONEncoder().encode(claim) {
            defaults.set(data, forKey: Self.claimKey)
        } else {
            defaults.removeObject(forKey: Self.claimKey)
        }
    }

    // MARK: - Turning on

    /// Turn Friends on: the one step that makes a key and sends anything. With a strap whose serial is
    /// known the account is bound to it; with a strap not read yet this waits for it.
    func turnOn(profile: ProfileStore) async { await turnOn(name: profile.displayName) }

    /// The same with the name given outright. The name is the profile's; this form exists for tests.
    func turnOn(name: String) async {
        await Task { await self.enter(name: name, bind: true) }.value
    }

    /// Turn Friends on with no strap to bind to, or beside a strap that belongs to someone else's
    /// account. The strap, once known, is bound or asked for by the next upload.
    func turnOnWithoutStrap(profile: ProfileStore) async { await turnOnWithoutStrap(name: profile.displayName) }

    func turnOnWithoutStrap(name: String) async {
        await Task { await self.enter(name: name, bind: false) }.value
    }

    /// Runs in a task of its own, so a view that goes away mid-request does not cut it short: an
    /// enrolment cut short would make the account and never show it.
    private func enter(name: String, bind: Bool) async {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            errorText = String(localized: "Enter your name first.")
            return
        }
        var strap: String?
        if bind {
            switch strapIdentity() {
            case .pending:
                errorText = nil
                setPhase(.waitingForStrap)
                return
            case let .handle(found): strap = found
            case .none: break
            }
        }
        loading = true
        defer { loading = false }
        do {
            let me = try await clientMakingKey().enroll(name: name, strap: strap)
            becomeOn(me: me, pushedName: name, boundHandle: strap)
            await refresh()
        } catch let error as FriendsAPIError where error.isStrapBound {
            errorText = nil
            setPhase(.strapBound)
        } catch {
            errorText = Self.message(for: error)
        }
    }

    /// "This is my account": ask to join the account the strap is bound to.
    func claimAccount(profile: ProfileStore) async { await claimAccount(name: profile.displayName) }

    func claimAccount(name: String) async {
        await Task { await self.claimNow(name: name) }.value
    }

    private func claimNow(name: String) async {
        guard case let .handle(strap) = strapIdentity() else {
            setPhase(.waitingForStrap)
            return
        }
        loading = true
        defer { loading = false }
        do {
            let claim = try await clientMakingKey().fileClaim(strap: strap)
            errorText = nil
            setPhase(.waiting(claim))
        } catch let error as FriendsAPIError where error.isStrapFree {
            // Its owner let the strap go meanwhile: there is no account to join, so make one.
            loading = false
            await enter(name: name, bind: true)
        } catch let error as FriendsAPIError where error.isAlreadyEnrolled {
            await adopt()
        } catch {
            errorText = Self.message(for: error)
        }
    }

    /// Withdraws the request to join and goes back to the choice.
    func cancelClaim() async {
        await Task {
            try? await self.client().withdrawClaim()
            if case .waiting = self.phase { self.setPhase(.strapBound) }
        }.value
    }

    /// Asks how the request to join stands. Called when the tab appears and while it stays open.
    func pollClaim() async {
        guard case .waiting = phase else { return }
        do {
            let claim = try await client().myClaim()
            guard case .waiting = phase else { return }
            switch claim.state {
            case .pending:
                setPhase(.waiting(claim))
            case .approved:
                await adopt()
            case .declined:
                errorText = String(localized: "The request was declined on your other phone.")
                setPhase(.strapBound)
            case .expired:
                errorText = String(localized: "The request expired. Send it again.")
                setPhase(.strapBound)
            }
        } catch let error as FriendsAPIError where error.isNoClaim || error.isUnknownKey {
            // The claim is gone from the server: granted long ago and cleared away, or withdrawn.
            await adopt()
        } catch let error as FriendsAPIError where error.isOffline {
            // No answer is not an answer: the screen keeps showing the code.
        } catch {
            errorText = Self.message(for: error)
        }
    }

    /// The request was granted: this phone is one of the account's. It joins without sending its own
    /// profile, since what the account already shows stands.
    private func adopt() async {
        do {
            let me = try await client().me()
            becomeOn(me: me, pushedName: nil, boundHandle: nil)
            defaults.set(true, forKey: Self.adoptProfileKey)
            await refresh()
        } catch let error as FriendsAPIError where error.isUnknownKey {
            setPhase(.strapBound)
        } catch {
            errorText = Self.message(for: error)
        }
    }

    private func becomeOn(me: FriendProfile, pushedName: String?, boundHandle: String?) {
        // Nothing of an earlier account on this phone may stay under the new one.
        forgetAccount()
        if let pushedName { defaults.set(pushedName, forKey: Self.pushedNameKey) }
        if let boundHandle { defaults.set(boundHandle, forKey: Self.boundHandleKey) }
        epoch += 1
        errorText = nil
        setPhase(.on)
    }

    // MARK: - Leaving

    /// Delete the account on the server and forget it here. The strap is free again.
    func deleteAccount() async -> Bool {
        await Task {
            let session = self.epoch
            do {
                try await self.client().deleteAccount()
                if session == self.epoch { self.leave(deleteKey: true) }
                return true
            } catch {
                self.handle(error, session: session)
                // An account that is already gone is a deletion that worked.
                return !self.isOn
            }
        }.value
    }

    /// Takes this phone off the account, which goes on living on the wearer's other phones. The server
    /// refuses it for the account's only confirmed phone.
    func removeThisPhone() async -> Bool {
        guard let id = deviceID else { return false }
        return await Task {
            let session = self.epoch
            do {
                try await self.client().removeDevice(id)
                if session == self.epoch { self.leave(deleteKey: true) }
                return true
            } catch {
                self.handle(error, session: session)
                return !self.isOn
            }
        }.value
    }

    /// Friends goes off on this phone: the one way the kept feed, the pictures and the upload marks go
    /// away together, and queued uploads with them. The key goes too when the phone left by its own
    /// doing; when the server stopped knowing it the key stays, so turning Friends on again asks to
    /// rejoin as the same phone.
    private func leave(deleteKey: Bool) {
        uploadTask?.cancel()
        uploadTask = nil
        if deleteKey {
            FriendsKey.delete(server: serverAddress, storage: keys)
            key = nil
        }
        forgetAccount()
        epoch += 1
        setPhase(.off)
    }

    /// Everything kept about the account except the server address and the key.
    private func forgetAccount() {
        for name in [Self.feedAtKey, Self.uploadedAtKey, Self.pushedNameKey, Self.pushedPhotoKey,
                     Self.adoptProfileKey, Self.sharePhotoKey, Self.boundHandleKey, Self.inviteCodesKey] {
            defaults.removeObject(forKey: name)
        }
        clearUploadMarks()
        cache?.clear()
        FriendsAvatars.clear()
        feed = nil
        feedFetchedAt = 0
        lastRefreshFailed = false
        devices = nil
        invites = nil
        lastUploadAt = nil
    }

    // MARK: - Reading

    /// What opening the tab and a pull both do. Before Friends is on it moves the turning-on along: a
    /// strap that has since been read is enrolled with, a request to join is asked about. Once on it
    /// sends today and yesterday if they changed and reads the feed; an automatic call inside
    /// `autoRefreshEverySeconds` of the last good answer does nothing, so switching tabs back and forth
    /// costs no request, and `force` (a pull, a change just made) always goes.
    func sync(repo: Repository, profile: ProfileStore, force: Bool) async {
        switch phase {
        case .off, .strapBound:
            return
        case .waitingForStrap:
            if strapIdentity() != .pending { await turnOn(profile: profile) }
        case .waiting:
            await pollClaim()
        case .on:
            let age = Int(Date().timeIntervalSince1970) - feedFetchedAt
            if !force, feed != nil, !lastRefreshFailed, (0..<Self.autoRefreshEverySeconds).contains(age) { return }
            // The upload first, so the wearer's own card is what the server holds as of now.
            await uploadRecentDays(repo: repo, profile: profile)
            await refresh()
        }
    }

    func refresh() async {
        guard isOn else { return }
        let session = epoch
        loading = feed == nil
        defer { loading = false }
        do {
            let answered = try await client().feed()
            // The account this was asked under is gone meanwhile: its answer belongs to nobody on screen.
            guard session == epoch else { return }
            feed = answered.feed
            feedFetchedAt = Int(Date().timeIntervalSince1970)
            defaults.set(feedFetchedAt, forKey: Self.feedAtKey)
            cache?.write(answered.raw)
            lastRefreshFailed = false
            errorText = nil
        } catch {
            if session == epoch { lastRefreshFailed = true }
            handle(error, session: session)
        }
    }

    /// One person in full for their own page, or nil (with `errorText` set) when it cannot be read.
    func person(_ id: String) async -> FriendProfile? {
        let session = epoch
        do { return try await client().person(id) }
        catch { handle(error, session: session); return nil }
    }

    /// A person's picture as the server holds it, or nil when there is none to show. Quiet: a picture
    /// that did not arrive is drawn as initials, and is no reason for a notice.
    func avatarBytes(of id: String) async -> Data? {
        let session = epoch
        guard isOn, let bytes = try? await client().avatar(id), session == epoch else { return nil }
        return bytes
    }

    // MARK: - Friends

    func unfriend(_ id: String) async { await actNoAnswer { try await self.client().unfriend(id) } }

    /// Makes a one-time invite and remembers its code, which the server says only now.
    func createInvite() async -> FriendsInvite? {
        guard let invite = await act({ try await self.client().createInvite() }, refreshing: false) else { return nil }
        if let code = invite.code {
            var kept = defaults.dictionary(forKey: Self.inviteCodesKey) as? [String: String] ?? [:]
            kept[invite.id] = code
            defaults.set(kept, forKey: Self.inviteCodesKey)
        }
        await loadInvites()
        return invite
    }

    /// The code of an invite made on this phone, or nil for one made elsewhere.
    func inviteCode(_ id: String) -> String? {
        (defaults.dictionary(forKey: Self.inviteCodesKey) as? [String: String])?[id]
    }

    func loadInvites() async {
        let session = epoch
        do {
            let found = try await client().invites()
            guard session == epoch else { return }
            invites = found
            // A code whose invite is used, revoked or expired is of no further use.
            let live = Set(found.map(\.id))
            let kept = (defaults.dictionary(forKey: Self.inviteCodesKey) as? [String: String] ?? [:]).filter { live.contains($0.key) }
            defaults.set(kept, forKey: Self.inviteCodesKey)
        } catch {
            handle(error, session: session)
        }
    }

    func revokeInvite(_ id: String) async {
        await actNoAnswer({ try await self.client().revokeInvite(id) }, refreshing: false)
        await loadInvites()
    }

    /// Uses an invite: whatever was typed, pasted or opened is read for its code. Answers the new friend,
    /// or nil with `errorText` set.
    func redeem(_ text: String) async -> FriendProfile? {
        guard let code = FriendsInviteCode.extract(text) else {
            errorText = String(localized: "That is not an invite code.")
            return nil
        }
        return await act { try await self.client().redeem(code) }
    }

    // MARK: - Requests on the strap

    /// Confirms a waiting request: a new phone joins the account, or the strap goes to who asked for it.
    func approve(_ claim: FriendsClaim) async { await actNoAnswer { try await self.client().approve(claim.id) } }

    func decline(_ claim: FriendsClaim) async { await actNoAnswer { try await self.client().decline(claim.id) } }

    // MARK: - Phones

    func loadDevices() async {
        let session = epoch
        do {
            let found = try await client().devices()
            if session == epoch { devices = found }
        } catch {
            handle(error, session: session)
        }
    }

    func removeDevice(_ id: String) async {
        await actNoAnswer { try await self.client().removeDevice(id) }
        await loadDevices()
    }

    /// Keeps a phone that joined without confirmation: its probation ends now.
    func trustDevice(_ id: String) async {
        await actNoAnswer { try await self.client().trustDevice(id) }
        await loadDevices()
    }

    // MARK: - Profile and sharing

    /// Change the sharing switches. Turning one off erases that section on the server from every day
    /// already uploaded, so what this phone remembers having sent no longer describes the server: the
    /// marks are dropped and today and yesterday go up again.
    func setShare(_ share: FriendsShare, repo: Repository, profile: ProfileStore) async {
        guard share != self.share else { return }
        await Task {
            guard await self.act({ try await self.client().update(share: share) }, refreshing: false) != nil else { return }
            self.clearUploadMarks()
            await self.uploadRecentDays(repo: repo, profile: profile)
            await self.refresh()
        }.value
    }

    /// Whether the profile photo is sent. Off removes the server's copy on the next run, which is now.
    func setSharePhoto(_ on: Bool, repo: Repository, profile: ProfileStore) async {
        guard on != sharePhoto else { return }
        defaults.set(on, forKey: Self.sharePhotoKey)
        objectWillChange.send()
        await uploadRecentDays(repo: repo, profile: profile)
        await refresh()
    }

    /// Everything the server holds for the account, laid out to be read; nil when it cannot be fetched.
    func exportText() async -> String? {
        let session = epoch
        do {
            let raw = try await client().export()
            guard let object = try? JSONSerialization.jsonObject(with: raw),
                  let pretty = try? JSONSerialization.data(
                    withJSONObject: object, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]) else {
                return String(decoding: raw, as: UTF8.self)
            }
            return String(decoding: pretty, as: UTF8.self)
        } catch {
            handle(error, session: session)
            return nil
        }
    }

    // MARK: - Uploading

    /// A scoring pass has finished (the cached days changed). Upload shortly after, once: a burst of
    /// refreshes becomes one upload, and an unchanged day is not sent at all. Does nothing while Friends
    /// is off, and the strap sync that led here never waits on it or learns how it went.
    func daysChanged(repo: Repository, profile: ProfileStore) {
        guard isOn else { return }
        uploadTask?.cancel()
        uploadTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled else { return }
            await self?.uploadRecentDays(repo: repo, profile: profile)
        }
    }

    /// Build and send today and yesterday, one run at a time. Failure is silent by design: a day that
    /// did not go up goes up on the next trigger.
    func uploadRecentDays(repo: Repository, profile: ProfileStore) async {
        guard isOn else { return }
        let previous = uploadRun
        let run = Task { [weak self] in
            await previous?.value
            await self?.uploadNow(repo: repo, profile: profile)
        }
        uploadRun = run
        await run.value
    }

    private func uploadNow(repo: Repository, profile: ProfileStore) async {
        let session = epoch
        guard isOn, let client = try? client() else { return }
        // The account is read from the server first, so a switch turned off on another phone is honoured
        // here before anything is built.
        let account: FriendProfile
        do {
            account = try await client.me()
            guard session == epoch else { return }
        } catch {
            handle(error, quiet: true, session: session)
            return
        }
        guard let share = account.share else { return }
        // A phone that joined without confirmation reads and uploads its days; the strap and the profile
        // wait until it is confirmed.
        if account.device?.probationUntil == nil {
            await bindStrap(client, session: session)
            await pushProfile(client, profile: profile, session: session)
        }
        let days = await FriendsUploader.recentDays(repo: repo, profile: profile, share: share)
        let keep = Set(days.map(\.key))
        for day in days {
            // The account this run started under is gone: nothing more is sent with its key.
            guard session == epoch else { return }
            let now = Int(Date().timeIntervalSince1970)
            let json = FriendsDayBuilder.json(FriendsDayBuilder.day(day.input, share: share, nowTs: now))
            guard FriendsUploadPolicy.shouldUpload(lastAcceptedFingerprint: uploadMark(day.key), json: json) else { continue }
            do {
                try await client.upload(json: json, on: day.key)
                guard session == epoch else { return }
                recordUpload(day.key, fingerprint: FriendsUploadPolicy.fingerprint(json), keep: keep, at: now)
            } catch {
                handle(error, quiet: true, session: session)
                // A refused day is not worth stopping for; a phone the server no longer knows, or no network, is.
                guard let api = error as? FriendsAPIError, !api.isUnknownKey, !api.isOffline else { return }
            }
        }
    }

    /// Tells the server which strap is worn, when it is not the one the server last accepted. A new
    /// strap on the same phone takes the account over this way, with nothing for the wearer to do. A
    /// strap that belongs to another account is asked for: the feed carries that request, and every run
    /// asks again until it is settled.
    private func bindStrap(_ client: FriendsClient, session: Int) async {
        guard case let .handle(strap) = strapIdentity(), strap != defaults.string(forKey: Self.boundHandleKey) else { return }
        do {
            let answer = try await client.putStrap(strap)
            guard session == epoch else { return }
            if answer == .bound { defaults.set(strap, forKey: Self.boundHandleKey) }
        } catch {
            handle(error, quiet: true, session: session)
        }
    }

    /// Sends the profile's name and photo when the wearer changed them on this phone since they were
    /// last sent. Never because the server's differ: two phones of one account with different profiles
    /// would otherwise overwrite each other forever.
    private func pushProfile(_ client: FriendsClient, profile: ProfileStore, session: Int) async {
        let name = profile.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let photo = sharePhoto ? profile.avatarImageData.flatMap { FriendsAvatars.fitForUpload($0) } : nil
        let photoMark = photo.map { FriendsUploadPolicy.fingerprint($0) } ?? (sharePhoto ? "none" : "off")
        if defaults.bool(forKey: Self.adoptProfileKey) {
            defaults.set(name, forKey: Self.pushedNameKey)
            defaults.set(photoMark, forKey: Self.pushedPhotoKey)
            defaults.removeObject(forKey: Self.adoptProfileKey)
            return
        }
        do {
            if !name.isEmpty, name != defaults.string(forKey: Self.pushedNameKey) {
                _ = try await client.update(name: name)
                guard session == epoch else { return }
                defaults.set(name, forKey: Self.pushedNameKey)
            }
            let sent = defaults.string(forKey: Self.pushedPhotoKey)
            guard photoMark != sent else { return }
            if let photo {
                _ = try await client.putAvatar(photo)
            } else if let sent, sent != "none", sent != "off" {
                try await client.deleteAvatar()
            }
            guard session == epoch else { return }
            defaults.set(photoMark, forKey: Self.pushedPhotoKey)
        } catch {
            handle(error, quiet: true, session: session)
        }
    }

    /// The fingerprint of the text the server last accepted for `day`, or nil when none is on record.
    func uploadMark(_ day: String) -> String? { defaults.string(forKey: Self.markPrefix + day) }

    /// Records an accepted upload of `day` and forgets the marks of every day not in `keep`.
    private func recordUpload(_ day: String, fingerprint: String, keep: Set<String>, at now: Int) {
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(Self.markPrefix) {
            let marked = String(key.dropFirst(Self.markPrefix.count))
            if marked != day, !keep.contains(marked) { defaults.removeObject(forKey: key) }
        }
        defaults.set(fingerprint, forKey: Self.markPrefix + day)
        defaults.set(now, forKey: Self.uploadedAtKey)
        lastUploadAt = now
    }

    /// Forgets every fingerprint, so the next upload sends each day again. Needed whenever the server
    /// may no longer hold what was sent.
    private func clearUploadMarks() {
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(Self.markPrefix) {
            defaults.removeObject(forKey: key)
        }
    }

    // MARK: - Plumbing

    private func act<T>(_ call: @escaping () async throws -> T, refreshing: Bool = true) async -> T? {
        let session = epoch
        do {
            let value = try await call()
            guard session == epoch else { return nil }
            errorText = nil
            if refreshing { await refresh() }
            return value
        } catch {
            handle(error, session: session)
            return nil
        }
    }

    private func actNoAnswer(_ call: @escaping () async throws -> Void, refreshing: Bool = true) async {
        _ = await act({ try await call(); return true }, refreshing: refreshing)
    }

    /// A phone the server no longer knows ends here, once, for every screen. An answer for an earlier
    /// account (Friends has since gone off, or on as someone else) says nothing about the one on screen.
    private func handle(_ error: Error, quiet: Bool = false, session: Int) {
        guard session == epoch else { return }
        // Nothing was sent and nothing ended: Friends is off, which the tab already shows.
        if case .notEnrolled? = error as? FriendsAPIError { return }
        if let api = error as? FriendsAPIError, api.isUnknownKey {
            // Removed from the account by another phone, or the account is gone.
            leave(deleteKey: false)
            errorText = String(localized: "This phone is no longer part of the account. Turn Friends on to join again.")
            return
        }
        if !quiet { errorText = Self.message(for: error) }
    }

    /// The server's codes in the wearer's language; its own English sentence only as a last resort.
    static func message(for error: Error) -> String {
        if error is FriendsKeyError {
            return String(localized: "The key for Friends could not be saved on this device.")
        }
        guard let api = error as? FriendsAPIError else { return error.localizedDescription }
        switch api {
        case .notEnrolled:
            return String(localized: "Friends is off on this phone.")
        case .notConfigured:
            return String(localized: "The server address is not valid. It must start with https://.")
        case .transport:
            return String(localized: "The friends server did not answer. Check the address and your connection.")
        case .clockSkew:
            return String(localized: "This phone's clock is too far off. Set the date and time automatically.")
        case let .server(code, message, status):
            switch code {
            case "bad_name": return String(localized: "A name is 1 to 40 characters.")
            case "server_full": return String(localized: "This server is not taking new accounts.")
            case "strap_bound": return String(localized: "This strap already has an account.")
            case "probation": return String(localized: "This phone joined without confirmation. It can change things once it is confirmed.")
            case "claim_declined": return String(localized: "That request was declined. Try again in a week.")
            case "too_many_claims": return String(localized: "Too many requests are waiting on this strap. Try again later.")
            case "too_many_devices": return String(localized: "The account already has five phones. Remove one first.")
            case "last_device": return String(localized: "This is the account's only confirmed phone. Delete the account instead.")
            case "claim_settled", "no_claim": return String(localized: "That request is no longer waiting.")
            case "no_such_invite": return String(localized: "This code is not valid any more.")
            case "own_invite": return String(localized: "That is your own invite.")
            case "too_many_invites": return String(localized: "Too many invites are waiting. Revoke one first.")
            case "too_many_friends": return String(localized: "The friend limit is reached.")
            case "no_such_user": return String(localized: "This person is no longer here.")
            case "bad_image", "too_large": return String(localized: "This picture cannot be used.")
            default:
                if status == 429 { return String(localized: "Too many attempts. Try again later.") }
                if api.isRedirect { return String(localized: "This address did not answer like a friends server.") }
                if status >= 500 { return String(localized: "The server had a problem. Try again later.") }
                return message.isEmpty ? String(localized: "The friends server refused the request.") : message
            }
        }
    }
}

// >>> kept unchanged from the current file: `enum FriendsUploader`, with its doc comment
```

- [ ] **Step 5: Give the store the registry**

In `Strand/App/AppModel.swift`, directly after the line `self.deviceRegistry = registry` (near line 686), add:

```swift
        // Friends (fork feature): which strap is worn, so the friends account can be bound to it.
        FriendsStore.shared.strapIdentity = { [weak registry] in FriendsStrap.identity(registry: registry) }
```

- [ ] **Step 6: Rewrite `FriendsView.swift`**

Replace the file with the following; `FriendsWelcomeHero` at its end stays as it is.

```swift
//  FriendsView.swift
//  NOOP · Friends — the tab, laid out as the Fitness app's Sharing tab in iOS 26: the large title with
//  one button in the bar, Highlights (what friends did lately) paging side by side, then everyone's
//  rings under a heading that carries the sort menu and the day. Inviting a friend and whatever waits
//  for an answer are behind the bar's button, badged with their count, where Fitness keeps its
//  invitations.
//
//  The metrics are Fitness's, measured from the Sharing screenshot in Apple's iPhone User Guide
//  ("Share your activity in Fitness", iOS 26). Until Friends is turned on the tab is Fitness's
//  "Share Activity" page, and nothing is sent anywhere.

import SwiftUI
import StrandAnalytics
import StrandDesign

struct FriendsView: View {
    @ObservedObject private var store = FriendsStore.shared
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var profile: ProfileStore
    @Environment(\.scrollToTopSignal) private var scrollToTopSignal
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue
    @AppStorage("friends.sort") private var sortRaw = FriendsSort.name.rawValue

    @State private var showFriends = false

    private var sort: Binding<FriendsSort> {
        Binding(get: { FriendsSort(rawValue: sortRaw) ?? .name }, set: { sortRaw = $0.rawValue })
    }
    private var effortScale: EffortScale { UnitPrefs.resolveEffortScale(effortScaleRaw) }

    private static let topAnchorID = "friends.top"

    var body: some View {
        Group {
            if store.isOn { board } else { FriendsWelcome() }
        }
        .background(StrandPalette.summaryCanvas.ignoresSafeArea())
        .navigationTitle(Text("Friends"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(store.isOn ? .large : .inline)
        #endif
        .toolbar { toolbar }
        .sheet(isPresented: $showFriends) { FriendsManageSheet() }
        .task(id: store.phase.stored) {
            // Opening the tab sends the wearer's own day first, so their card is never the stale one.
            // Coming back within a minute of a good answer shows that answer and asks nothing. Before
            // Friends is on, the same call moves the turning-on along.
            await store.sync(repo: repo, profile: profile, force: false)
        }
    }

    /// Fitness's one bar button: it opens the friends sheet, and counts what waits there for an answer.
    /// The page before Friends is on has no button and no title, as Fitness's has none.
    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        if store.isOn {
            ToolbarItem(placement: .primaryAction) { friendsButton }
        } else {
            ToolbarItem(placement: .principal) {
                Color.clear.frame(width: 1, height: 1).accessibilityHidden(true)
            }
        }
    }

    private var waiting: Int { store.claims.count }

    /// iOS 26 draws a bar button's badge itself; before it the glyph carries a dot instead.
    private var badgesBarButtons: Bool {
        if #available(iOS 26.0, macOS 26.0, *) { return true }
        return false
    }

    private var friendsButton: some View {
        Button { showFriends = true } label: {
            Image(systemName: "person.fill.badge.plus")
                .overlay(alignment: .topTrailing) {
                    if waiting > 0, !badgesBarButtons {
                        Circle().fill(StrandPalette.settingsRed).frame(width: 8, height: 8).offset(x: 4, y: -4)
                    }
                }
        }
        .badge(waiting)
        .barGlyph()
        .accessibilityLabel(Text("Add Friend"))
        .accessibilityValue(waiting > 0
                            ? Text(verbatim: String(localized: "Requests") + ": \(waiting)")
                            : Text(verbatim: ""))
    }

    // MARK: - On

    private var board: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: FriendsStyle.cardSpacing) {
                    if let error = store.errorText {
                        NoticeCard(title: Text(verbatim: error), systemImage: "exclamationmark.triangle.fill",
                                   tone: .warning, onDismiss: { store.errorText = nil })
                    }
                    if let feed = store.feed {
                        let highlights = FriendsHighlights.recent(friends: feed.friends, now: store.serverNow())
                        if !highlights.isEmpty {
                            SectionHeader(title: "friends.highlights")
                            FriendsHighlightsCarousel(highlights: highlights, effortScale: effortScale,
                                                      gutter: FriendsStyle.gutter)
                        }
                        FriendsBoardHeader(sort: sort, day: Repository.logicalDay(Date()))
                            .padding(.top, NoopMetrics.space4)
                        ForEach(FriendsBoard.rows(me: feed.me, friends: feed.friends, sort: sort.wrappedValue,
                                                  todayKey: FriendsFormat.todayKey())) { row in
                            NavigationLink(value: TabRoute.friend(row.person.id)) {
                                FriendScoreCard(row: row, metric: sort.wrappedValue.metric, effortScale: effortScale,
                                                ownImageData: profile.avatarImageData)
                            }
                            .buttonStyle(.plain)
                        }
                        if feed.friends.isEmpty { noFriends }
                    } else if store.loading {
                        ProgressView().frame(maxWidth: .infinity).padding(.top, NoopMetrics.space8)
                    }
                }
                .padding(.horizontal, FriendsStyle.gutter)
                .padding(.bottom, NoopMetrics.space6)
                .id(Self.topAnchorID)
                #if os(macOS)
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
                #endif
            }
            .onChangeCompat(of: scrollToTopSignal) { _ in
                withAnimation(.easeOut(duration: 0.35)) { proxy.scrollTo(Self.topAnchorID, anchor: .top) }
            }
        }
        .refreshable { await store.sync(repo: repo, profile: profile, force: true) }
    }

    private var noFriends: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space3) {
            VStack(alignment: .leading, spacing: 2) {
                Text("No friends yet")
                    .font(StrandFont.headline)
                    .foregroundStyle(StrandPalette.textPrimary)
                Text("Invite a friend with a code. You see each other's days as soon as they use it.")
                    .font(StrandFont.pro(15))
                    .foregroundStyle(StrandPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button { showFriends = true } label: { Text("Add Friend") }
                .buttonStyle(FriendsKeyButtonStyle())
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .friendsCard()
    }
}

// MARK: - Before Friends is on

/// The tab before Friends is on, after the Fitness app's "Share Activity" page: the wearer's own picture
/// with a ring and an activity beside it, what the tab is for in a sentence, then what leaves the phone
/// and the one button at the foot of the page. There is no form. The name and photo are the profile's
/// and the account is tied to the strap, so turning on is one tap. The same page carries the three
/// places turning on can pause: a strap not read yet, a strap that already has an account, and a
/// request to join that account waiting for an answer.
struct FriendsWelcome: View {
    @ObservedObject private var store = FriendsStore.shared
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var profile: ProfileStore

    /// The profile had no name when the page appeared, so the page asks for one. Decided once: the
    /// field must not go away at the first letter typed into it.
    @State private var asksForName = false

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(spacing: 0) {
                    if let error = store.errorText {
                        NoticeCard(title: Text(verbatim: error), systemImage: "exclamationmark.triangle.fill",
                                   tone: .warning, onDismiss: { store.errorText = nil })
                            .padding(.bottom, NoopMetrics.space4)
                    }
                    FriendsWelcomeHero(imageData: profile.avatarImageData, initials: profile.initials)
                        .padding(.top, NoopMetrics.space5)
                    Text(title)
                        .font(StrandFont.pro(34))
                        .foregroundStyle(StrandPalette.textPrimary)
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.7)
                        .padding(.top, NoopMetrics.space5)
                        .accessibilityAddTraits(.isHeader)
                    Text(sentence)
                        .font(StrandFont.pro(20))
                        .foregroundStyle(StrandPalette.textPrimary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, NoopMetrics.space2)
                    if case let .waiting(claim) = store.phase {
                        Text(verbatim: Self.spaced(claim.code))
                            .font(StrandFont.pro(44, weight: .semibold))
                            .monospacedDigit()
                            .foregroundStyle(FriendsStyle.key)
                            .padding(.top, NoopMetrics.space5)
                            .accessibilityLabel(Text(verbatim: claim.code.map(String.init).joined(separator: " ")))
                    }
                    Spacer(minLength: NoopMetrics.space8)
                    footer
                }
                .padding(.horizontal, FriendsStyle.gutter)
                .padding(.bottom, NoopMetrics.space4)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity, minHeight: geo.size.height)
            }
        }
        .onAppear { asksForName = profile.displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        .task(id: store.phase.stored) { await watch() }
    }

    private var title: LocalizedStringKey {
        switch store.phase {
        case .off, .on: return "Share with Friends"
        case .waitingForStrap: return "Connect Your Strap"
        case .strapBound: return "This Strap Has an Account"
        case .waiting: return "Confirm on Your Other Phone"
        }
    }

    private var sentence: LocalizedStringKey {
        switch store.phase {
        case .off, .on:
            return "See how your friends recovered, trained and slept, and let them see your day."
        case .waitingForStrap:
            return "Your account is tied to your strap, so the same strap finds it again on a new phone. Connect the strap and this finishes by itself."
        case .strapBound:
            return "If it is yours from another phone, join it. If the strap came from someone else, start your own."
        case .waiting:
            return "Open Friends on the phone you used before and confirm this code."
        }
    }

    @ViewBuilder private var footer: some View {
        switch store.phase {
        case .off, .on:
            if asksForName {
                TextField("Your Name", text: $profile.displayName)
                    .font(StrandFont.pro(17))
                    .submitLabel(.done)
                    #if os(iOS)
                    .textContentType(.name)
                    #endif
                    .padding(.horizontal, 16)
                    .frame(minHeight: 50)
                    .friendsCard(radius: 14)
                    .padding(.bottom, NoopMetrics.space4)
            }
            note("lock.fill", "Nothing leaves this device until you turn Friends on, and you choose what is shared. Your name and photo are the ones in your profile.")
            Button { Task { await store.turnOn(profile: profile) } } label: { Text("Turn On") }
                .buttonStyle(FriendsKeyButtonStyle(large: true))
                .disabled(store.loading)
                .padding(.top, NoopMetrics.space5)
        case .waitingForStrap:
            note("dot.radiowaves.left.and.right", "Without a strap the account cannot be found again from another phone.")
            Button { Task { await store.turnOnWithoutStrap(profile: profile) } } label: { Text("Continue Without a Strap") }
                .buttonStyle(FriendsKeyButtonStyle(prominent: false, large: true))
                .disabled(store.loading)
                .padding(.top, NoopMetrics.space5)
        case .strapBound:
            note("person.2.fill", "Starting your own asks the strap's previous owner to let it go. Friends works meanwhile.")
            Button { Task { await store.claimAccount(profile: profile) } } label: { Text("This Is My Account") }
                .buttonStyle(FriendsKeyButtonStyle(large: true))
                .disabled(store.loading)
                .padding(.top, NoopMetrics.space5)
            Button { Task { await store.turnOnWithoutStrap(profile: profile) } } label: { Text("Start a New Account") }
                .buttonStyle(FriendsKeyButtonStyle(prominent: false, large: true))
                .disabled(store.loading)
                .padding(.top, NoopMetrics.space3)
        case let .waiting(claim):
            if let matures = claim.maturesAt {
                note("clock.fill", "If that phone is gone, you are let in by yourself on \(Self.moment(matures)).")
            }
            Button { Task { await store.cancelClaim() } } label: { Text("Cancel") }
                .buttonStyle(FriendsKeyButtonStyle(prominent: false, large: true))
                .padding(.top, NoopMetrics.space5)
        }
    }

    /// The glyph and footnote above the page's button, as Fitness sets its own.
    private func note(_ symbol: String, _ text: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: symbol)
                .font(StrandFont.pro(22, weight: .semibold))
                .foregroundStyle(FriendsStyle.key)
                .accessibilityHidden(true)
            Text(text)
                .font(StrandFont.pro(13))
                .foregroundStyle(StrandPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// While the page waits for something outside it, it asks again by itself: every minute how the
    /// request to join stands, every few seconds whether the strap has been read (which costs no request
    /// until it has).
    private func watch() async {
        let pause: UInt64
        switch store.phase {
        case .waiting: pause = 60_000_000_000
        case .waitingForStrap: pause = 5_000_000_000
        case .off, .strapBound, .on: return
        }
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: pause)
            guard !Task.isCancelled else { return }
            await store.sync(repo: repo, profile: profile, force: false)
        }
    }

    /// "481 902": six digits in two groups, as codes are read out.
    static func spaced(_ code: String) -> String {
        code.count == 6 ? code.prefix(3) + " " + code.suffix(3) : code
    }

    /// "Friday at 14:30", in the active language.
    static func moment(_ ts: Int) -> String {
        Date(timeIntervalSince1970: TimeInterval(ts))
            .formatted(.dateTime.weekday(.wide).hour().minute().locale(AppLanguage.activeLocale))
    }
}

// >>> kept unchanged: `private struct FriendsWelcomeHero`, with its doc comment
```

- [ ] **Step 7: Rewrite `FriendsSheets.swift`**

Replace the file with the following. `FriendsSetupSheet` and `FriendsPasswordPage` are gone; the sheet is left with the way to My Sharing, and plan 3 gives it invites and requests.

```swift
//  FriendsSheets.swift
//  NOOP · Friends — the sheet behind the bar's button, where Fitness keeps friends too, and the page of
//  what the wearer shares. There is no account form: Friends is turned on from the tab itself, the name
//  and photo are the profile's, and nothing here asks for a password.
//
//  The sheet closes with the bar's ✕ like every other sheet in the app.

import SwiftUI
import StrandAnalytics
import StrandDesign

private extension View {
    /// The hero's row in a form: no plate behind it and no inset, so it sits on the sheet itself.
    func friendsHeroRow() -> some View {
        self
            .frame(maxWidth: .infinity)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
    }
}

// MARK: - Friends

/// The sheet behind the tab's bar button. Fitness invites and answers invitations from one place, so
/// this is where a friend is invited, where a code is entered, and the way to the wearer's own sharing.
struct FriendsManageSheet: View {
    @ObservedObject private var store = FriendsStore.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NavigationLink {
                        FriendsSharingPage(onAccountLeft: { dismiss() })
                    } label: {
                        SettingsRowLabel(title: "My Sharing", icon: "person.crop.circle.fill",
                                         color: StrandPalette.settingsBlue)
                    }
                }
            }
            .settingsForm()
            .navigationTitle(Text("Friends"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { SheetCloseButton { dismiss() } }
            }
        }
    }
}

// MARK: - What the wearer shares

/// The wearer's own account, pushed from the friends sheet: how friends see them, what friends can
/// see, and deleting the account.
struct FriendsSharingPage: View {
    /// The account is gone from this phone: the sheet that showed it has nothing left to show.
    let onAccountLeft: () -> Void

    @ObservedObject private var store = FriendsStore.shared
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var profile: ProfileStore

    @State private var share = FriendsShare()
    @State private var sharePhoto = true
    @State private var confirmDelete = false

    /// A phone that joined without confirmation changes nothing until it is confirmed.
    private var onProbation: Bool { store.probationUntil != nil }

    var body: some View {
        Form {
            Section {
                FriendsHero(name: profile.displayName, own: true,
                            imageData: sharePhoto ? profile.avatarImageData : nil)
                    .friendsHeroRow()
            } footer: {
                Text("Your name and photo are the ones in your profile. Change them in Settings.")
            }
            Section {
                Toggle(isOn: $share.scores) {
                    SettingsRowLabel(title: "Scores", icon: "target", color: StrandPalette.settingsPink)
                }
                Toggle(isOn: $share.sleep) {
                    SettingsRowLabel(title: "Sleep", icon: "bed.double.fill", color: StrandPalette.settingsIndigo)
                }
                Toggle(isOn: $share.workouts) {
                    SettingsRowLabel(title: "Workouts", icon: "figure.run", color: StrandPalette.settingsGreen)
                }
                Toggle(isOn: $share.hr) {
                    SettingsRowLabel(title: "Heart Rate", icon: "heart.fill", color: StrandPalette.settingsRed)
                }
                Toggle(isOn: $sharePhoto) {
                    SettingsRowLabel(title: "Photo", icon: "person.crop.square.fill", color: StrandPalette.settingsOrange)
                }
            } header: {
                Text("Friends Can See")
            } footer: {
                Text("Only what is switched on goes to the server, and only friends see it. Switching something off also erases it from the server.")
            }
            .disabled(onProbation)
            Section {
                LabeledContent {
                    Text(verbatim: FriendsServerAddress.baseURL(store.serverAddress)?.host ?? store.serverAddress)
                } label: {
                    Text("Server")
                }
            } footer: {
                if let sent = store.lastUploadAt {
                    Text(verbatim: String(localized: "Last sent") + " " + FriendsFormat.ago(sent))
                } else {
                    Text("Nothing sent yet.")
                }
            }
            Section {
                // The dialog hangs off the button that asks for it, where iOS 26 points it.
                Button("Delete Account", role: .destructive) {
                    store.errorText = nil
                    confirmDelete = true
                }
                .disabled(onProbation)
                .confirmationDialog("Delete Account", isPresented: $confirmDelete, titleVisibility: .visible) {
                    Button("Delete Account", role: .destructive) {
                        Task {
                            guard await store.deleteAccount() else { return }
                            onAccountLeft()
                        }
                    }
                } message: {
                    Text("Removes your name, your picture, every day you uploaded and every friendship from the server, and turns Friends off on this phone. This cannot be undone.")
                }
            } footer: {
                if let error = store.errorText {
                    Text(error).foregroundStyle(StrandPalette.settingsRed)
                }
            }
        }
        .settingsForm()
        .navigationTitle(Text("My Sharing"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear {
            share = store.share
            sharePhoto = store.sharePhoto
        }
        .onChangeCompat(of: share) { changed in
            Task { await store.setShare(changed, repo: repo, profile: profile) }
        }
        .onChangeCompat(of: sharePhoto) { on in
            Task { await store.setSharePhoto(on, repo: repo, profile: profile) }
        }
    }
}
```

- [ ] **Step 8: People are known by id, not by nickname**

`Strand/Friends/FriendsPieces.swift`:

Replace `struct FriendAvatar` with:

```swift
/// A person's picture. A friend is the picture they chose to show, fetched once per revision, and
/// until it arrives (or when they have none) their initials on Contacts' grey, as Fitness and Messages
/// draw a contact with no photo. The wearer is drawn with their own photo straight from this phone.
struct FriendAvatar: View {
    let name: String
    let size: CGFloat
    var own = false
    /// The wearer's own photo; read only when `own`.
    var imageData: Data?
    /// Whose picture to fetch and at which revision; revision 0 is an account without one.
    var id = ""
    var rev = 0

    @State private var fetched: Data?

    init(name: String, size: CGFloat, own: Bool = false, imageData: Data? = nil, id: String = "", rev: Int = 0) {
        self.name = name; self.size = size; self.own = own; self.imageData = imageData
        self.id = id; self.rev = rev
    }

    init(person: FriendProfile, size: CGFloat, own: Bool = false, imageData: Data? = nil) {
        self.init(name: person.name, size: size, own: own, imageData: imageData, id: person.id, rev: person.avatarRev)
    }

    private var picture: Data? {
        own ? imageData : (fetched ?? FriendsAvatars.cached(id: id, rev: rev))
    }

    var body: some View {
        SummaryAvatar(imageData: picture, initials: FriendsFormat.initials(name), size: size)
            .accessibilityHidden(true)
            .task(id: "\(id):\(rev):\(own)") {
                fetched = own ? nil : await FriendsAvatars.load(id: id, rev: rev)
            }
    }
}
```

Replace `struct FriendNameStack` with:

```swift
struct FriendNameStack: View {
    let person: FriendProfile

    @Environment(\.dynamicTypeSize) private var dts

    var body: some View {
        Text(verbatim: person.name)
            .font(StrandFont.headline)
            .foregroundStyle(StrandPalette.textPrimary)
            .lineLimit(dts.isAccessibilitySize ? 2 : 1)
            .minimumScaleFactor(0.85)
    }
}
```

In the doc comment of `FriendPersonRow`, replace `the monogram, the name over the nickname, and what can be done` with `the monogram, the name, and what can be done`.

Replace `struct FriendsHero` with:

```swift
/// The top of a person's page and of the sharing page: the picture and the name, centred, as Contacts
/// opens a card and Health its profile.
struct FriendsHero: View {
    let name: String
    /// Whose picture to fetch; empty for the wearer, who is drawn from this phone.
    var id = ""
    /// The wearer's own account: drawn with their photo.
    var own = false
    var imageData: Data?
    /// The revision of a friend's picture; 0 is an account without one.
    var avatarRev = 0

    var body: some View {
        VStack(spacing: 6) {
            FriendAvatar(name: name, size: 96, own: own, imageData: imageData, id: id, rev: avatarRev)
                .padding(.bottom, 4)
            if !name.isEmpty {
                Text(verbatim: name)
                    .font(StrandFont.pro(28, weight: .bold))
                    .foregroundStyle(StrandPalette.textPrimary)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}
```

`Strand/Friends/FriendsAvatars.swift`: replace the header comment's first paragraph and the three functions that take a nickname.

Header, first paragraph:

```swift
//  A picture is fetched with a signed request (the server shows one only to its owner and friends) and
//  kept in the caches directory under the account id and the picture's revision. The revision changes
//  whenever the owner changes the picture, so a kept file is never stale and a picture is fetched once
//  per revision. Turning Friends off deletes the lot.
//
//  The wearer's own picture is the profile photo the app already has: the tab has no picker of its own.
```

`cached` and `load`:

```swift
    /// Pictures already read this session, by "id:rev". A few dozen small JPEGs at most.
    private static let memory: NSCache<NSString, NSData> = {
        let cache = NSCache<NSString, NSData>()
        cache.countLimit = 64
        return cache
    }()

    /// The picture already read this session, without touching the disk.
    static func cached(id: String, rev: Int) -> Data? {
        rev > 0 ? memory.object(forKey: "\(id):\(rev)" as NSString) as Data? : nil
    }

    /// The picture of `id` at revision `rev`: from memory, else the disk, else the server. Nil when the
    /// account has none (`rev` is 0), Friends is off, offline, or the bytes are not an image. The id
    /// becomes a file name, so one that is not an id is refused.
    @MainActor
    static func load(id: String, rev: Int, store: FriendsStore? = nil) async -> Data? {
        guard rev > 0, FriendsID.isValid(id) else { return nil }
        if let held = cached(id: id, rev: rev) { return held }
        let file = folder?.appendingPathComponent("\(id)_\(rev)")
        if let file, let kept = try? Data(contentsOf: file) {
            memory.setObject(kept as NSData, forKey: "\(id):\(rev)" as NSString)
            return kept
        }
        guard let fetched = await (store ?? .shared).avatarBytes(of: id),
              let picture = AvatarImage.downscaledJPEG(from: fetched, maxDimension: CGFloat(keptSide)) else { return nil }
        if let folder, let file {
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            // One picture per person: an older revision is of no further use.
            for old in (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
            where old.hasPrefix("\(id)_") {
                try? FileManager.default.removeItem(at: folder.appendingPathComponent(old))
            }
            try? picture.write(to: file, options: [.atomic])
        }
        memory.setObject(picture as NSData, forKey: "\(id):\(rev)" as NSString)
        return picture
    }

    /// Deletes every kept picture (Friends turned off, account deleted).
    static func clear() {
        memory.removeAllObjects()
        if let folder { try? FileManager.default.removeItem(at: folder) }
    }
```

`Strand/Friends/FriendDetailView.swift`, six edits:

| Find | Replace with |
|---|---|
| `let nick: String` | `let personID: String` |
| `private var isMe: Bool { nick == store.nick }` | `private var isMe: Bool { personID == store.myID }` |
| `FriendsHero(name: person.name, nick: person.nick, own: isMe,` | `FriendsHero(name: person.name, id: person.id, own: isMe,` |
| `Task { await store.unfriend(nick); dismiss() }` | `Task { await store.unfriend(personID); dismiss() }` |
| `Text("You stop seeing each other's days. Either of you can send a new request later.")` | `Text("You stop seeing each other's days. Either of you can invite the other again later.")` |
| `.task(id: nick) {` and, on the next line, `person = await store.person(nick)` | `.task(id: personID) {` and `person = await store.person(personID)` |

`Strand/Friends/FriendsBoard.swift`: `var id: String { person.nick }` becomes `var id: String { person.id }`, and in `byName`, `a.nick < b.nick` becomes `a.id < b.id`.

`Strand/Friends/FriendsHighlights.swift`: the field `let nick: String` of `FriendHighlight` becomes `let personID: String`, and every use follows it: `"\(nick)#\(workout.startTs)"` to `"\(personID)#\(workout.startTs)"`; `FriendHighlight(nick: person.nick, name:` to `FriendHighlight(personID: person.id, name:`; `a.nick < b.nick` to `a.personID < b.personID`; `TabRoute.friend(highlight.nick)` to `TabRoute.friend(highlight.personID)`; `FriendAvatar(name: highlight.name, size: 32, nick: highlight.nick, rev:` to `FriendAvatar(name: highlight.name, size: 32, id: highlight.personID, rev:`.

`Strand/App/TabRoute.swift`: the comment `/// One person's page on the Friends tab, by nickname (the account's own page included).` becomes `/// One person's page on the Friends tab, by account id (the account's own page included).`, and `case .friend(let nick): FriendDetailView(nick: nick)` becomes `case .friend(let id): FriendDetailView(personID: id)`.

`StrandiOS/App/StrandiOSApp.swift`, the demo harness: `FriendsWelcome(onStart: {})` becomes `FriendsWelcome()`.

`StrandTests/FriendsBoardTests.swift` and `StrandTests/FriendsHighlightsTests.swift`: in every `FriendProfile(nick:` the label becomes `id:`; `rows.map(\.person.nick)` becomes `rows.map(\.person.id)`; `found.map(\.nick)` becomes `found.map(\.personID)`. The helper parameters named `nick` may keep their name.

- [ ] **Step 9: Check that nothing still names a removed thing**

```bash
grep -rn -E '\.nick\b|nick:|FriendsNick|FriendsPassword|FriendsKeychain|FriendRelation|FriendRequests|signedIn|signUp|signIn\(|signOut|changePassword|store\.requests|pendingIncoming|uploadProfilePhoto|removePicture|/v1/' Strand/Friends Strand/App/TabRoute.swift StrandTests/Friends*.swift StrandiOS/App/StrandiOSApp.swift
```

Expected: no output, apart from helper parameters named `nick` in the two test files of Step 8.

- [ ] **Step 10: Add the strings**

Run this from the repository root. It appends each new key, with its Russian, as a one-line entry at the end of `"strings"`, skips a key the catalogue already has, and checks the file is still valid JSON.

```bash
python3 - <<'PY'
import json
path = "Strand/Resources/Localizable.xcstrings"
new = [
    ("Turn On", "Включить"),
    ("Connect Your Strap", "Подключите браслет"),
    ("This Strap Has an Account", "У этого браслета уже есть аккаунт"),
    ("Confirm on Your Other Phone", "Подтвердите на другом телефоне"),
    ("Your account is tied to your strap, so the same strap finds it again on a new phone. Connect the strap and this finishes by itself.", "Аккаунт привязан к браслету, поэтому с тем же браслетом он найдётся и на новом телефоне. Подключите браслет, и всё завершится само."),
    ("If it is yours from another phone, join it. If the strap came from someone else, start your own.", "Если он ваш с другого телефона, войдите в него. Если браслет достался от другого человека, создайте свой."),
    ("Open Friends on the phone you used before and confirm this code.", "Откройте «Друзей» на прежнем телефоне и подтвердите этот код."),
    ("Your Name", "Ваше имя"),
    ("Nothing leaves this device until you turn Friends on, and you choose what is shared. Your name and photo are the ones in your profile.", "Ничто не покидает устройство, пока вы не включите «Друзей», и вы сами выбираете, чем делиться. Имя и фото берутся из вашего профиля."),
    ("Without a strap the account cannot be found again from another phone.", "Без браслета аккаунт нельзя будет найти с другого телефона."),
    ("Continue Without a Strap", "Продолжить без браслета"),
    ("Starting your own asks the strap's previous owner to let it go. Friends works meanwhile.", "Если создать свой, прежнего владельца браслета попросят его отпустить. «Друзья» работают и до этого."),
    ("This Is My Account", "Это мой аккаунт"),
    ("Start a New Account", "Создать новый аккаунт"),
    ("If that phone is gone, you are let in by yourself on %@.", "Если того телефона больше нет, вход откроется сам: %@."),
    ("Invite a friend with a code. You see each other's days as soon as they use it.", "Пригласите друга кодом. Вы увидите дни друг друга, как только он его введёт."),
    ("Your name and photo are the ones in your profile. Change them in Settings.", "Имя и фото берутся из вашего профиля. Изменить их можно в Настройках."),
    ("Photo", "Фото"),
    ("Only what is switched on goes to the server, and only friends see it. Switching something off also erases it from the server.", "На сервер уходит только включённое, и видят это только друзья. Выключенное также стирается с сервера."),
    ("Removes your name, your picture, every day you uploaded and every friendship from the server, and turns Friends off on this phone. This cannot be undone.", "Удаляет с сервера ваше имя, фото, все отправленные дни и все дружбы и выключает «Друзей» на этом телефоне. Отменить нельзя."),
    ("You stop seeing each other's days. Either of you can invite the other again later.", "Вы перестанете видеть дни друг друга. Позже любой из вас сможет пригласить другого снова."),
    ("Enter your name first.", "Сначала укажите имя."),
    ("The request was declined on your other phone.", "Запрос отклонён на другом телефоне."),
    ("The request expired. Send it again.", "Срок запроса истёк. Отправьте его снова."),
    ("That is not an invite code.", "Это не код приглашения."),
    ("This phone is no longer part of the account. Turn Friends on to join again.", "Этот телефон больше не входит в аккаунт. Включите «Друзей», чтобы войти снова."),
    ("The key for Friends could not be saved on this device.", "Не удалось сохранить ключ «Друзей» на этом устройстве."),
    ("Friends is off on this phone.", "«Друзья» на этом телефоне выключены."),
    ("This phone's clock is too far off. Set the date and time automatically.", "Часы телефона сильно расходятся. Включите автоматическую установку даты и времени."),
    ("This strap already has an account.", "У этого браслета уже есть аккаунт."),
    ("This phone joined without confirmation. It can change things once it is confirmed.", "Этот телефон вошёл без подтверждения. Менять что-либо можно будет после подтверждения."),
    ("That request was declined. Try again in a week.", "Этот запрос отклонён. Повторите через неделю."),
    ("Too many requests are waiting on this strap. Try again later.", "На этот браслет ждёт слишком много запросов. Повторите позже."),
    ("The account already has five phones. Remove one first.", "В аккаунте уже пять телефонов. Сначала уберите один."),
    ("This is the account's only confirmed phone. Delete the account instead.", "Это единственный подтверждённый телефон аккаунта. Вместо этого удалите аккаунт."),
    ("That request is no longer waiting.", "Этот запрос уже не ждёт ответа."),
    ("This code is not valid any more.", "Этот код больше не действует."),
    ("That is your own invite.", "Это ваше собственное приглашение."),
    ("Too many invites are waiting. Revoke one first.", "Слишком много приглашений ждёт. Сначала отзовите одно."),
    ("This person is no longer here.", "Этого человека здесь больше нет."),
]
text = open(path, encoding="utf-8").read()
have = json.loads(text)["strings"]
tail = '\n  },\n  "version"'
assert text.count(tail) == 1, "the catalogue does not end the way this script expects"
lines = []
for key, ru in new:
    if key in have:
        print("already there:", key[:60])
        continue
    entry = {"localizations": {"ru": {"stringUnit": {"state": "translated", "value": ru}}}}
    lines.append("    %s: %s" % (json.dumps(key, ensure_ascii=False), json.dumps(entry, ensure_ascii=False)))
if lines:
    text = text.replace(tail, ",\n" + ",\n".join(lines) + tail)
    open(path, "w", encoding="utf-8").write(text)
json.loads(open(path, encoding="utf-8").read())
print("added", len(lines))
PY
git diff --stat -- Strand/Resources/Localizable.xcstrings | tail -1
```

Expected: `added N` for some N up to 40, and a diff of N insertions and one changed line (the comma after the previous last entry). If the diff touches anything else, restore the file with `git checkout -- Strand/Resources/Localizable.xcstrings` and report it: the catalogue is hand-formatted and must not be re-serialised.

- [ ] **Step 11: Build both targets and run the Friends tests**

```bash
xcodegen generate >/dev/null && xcodegen generate --spec project-nowatch.yml >/dev/null
xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO build 2>&1 | grep -E 'error:|BUILD' | head -20
```

Expected: `** BUILD SUCCEEDED **`. Then the iOS build command: `** BUILD SUCCEEDED **`. Then:

```bash
xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO test \
  -only-testing:StrandTests/FriendsClientTests -only-testing:StrandTests/FriendsKeyTests \
  -only-testing:StrandTests/FriendsStrapTests -only-testing:StrandTests/FriendsBoardTests \
  -only-testing:StrandTests/FriendsHighlightsTests 2>&1 | grep -E 'error:|failed|Executed|TEST' | tail -12
cd Packages/StrandAnalytics && swift test --filter Friends 2>&1 | tail -3
```

Expected: `** TEST SUCCEEDED **` with no `failed` line, and the package's Friends tests passing.

- [ ] **Step 12: Turn Friends on against a local server, in the simulator**

Start plan 1's server on this Mac:

```bash
cd friends-server && FRIENDS_DB=/tmp/friends-dev.db FRIENDS_STRAP_PEPPER=0123456789abcdef0123456789abcdef .venv/bin/python server.py
```

In a second shell, install the simulator build and launch it pointed at that server. A launch argument `-friends.serverAddress <url>` sets the UserDefaults key the store reads, and plain HTTP is accepted for this device's own loopback only:

```bash
xcrun simctl boot "iPhone 17 Pro" 2>/dev/null; APP=$(find build/dd-ios/Build/Products -name 'NOOP*.app' -maxdepth 2 | head -1)
xcrun simctl install booted "$APP"
BUNDLE=$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$APP/Info.plist")
xcrun simctl launch booted "$BUNDLE" --demo-seed --demo-screen friends --friends-strap whoop-SIM0001 -friends.serverAddress http://127.0.0.1:8787
xcrun simctl io booted screenshot /tmp/friends-welcome.png
```

`--friends-strap <id>` is the DEBUG stand-in for a strap that Task 3 added: the simulator has no strap to read a serial from. Look at the screenshot, then tap through in the simulator. If no simulator of that name exists, `xcrun simctl list devices available` names the ones that do. Expected, in order:

1. The welcome page with **Turn On** and, with the demo profile's name empty, a **Your Name** field.
2. After Turn On: the board with the wearer's own card.
3. The server's log shows `POST /v2/enroll 201`, then `GET /v2/feed 200`, and no `401`.
4. Relaunching with the same arguments opens straight on the board: the key came back from the Keychain.
5. Relaunching without `--friends-strap` and tapping nothing changes nothing: Friends is already on. Deleting the app's data (`xcrun simctl uninstall booted "$BUNDLE"`, install again) and launching without the flag shows **Connect Your Strap** after Turn On, and **Continue Without a Strap** leads to the board.

Stop the server with Ctrl-C and delete `/tmp/friends-dev.db*`.

- [ ] **Step 13: Commit**

```bash
git add Strand/Friends/FriendsAPI.swift Strand/Friends/FriendsStore.swift Strand/Friends/FriendsView.swift \
  Strand/Friends/FriendsSheets.swift Strand/Friends/FriendsPieces.swift Strand/Friends/FriendsAvatars.swift \
  Strand/Friends/FriendDetailView.swift Strand/Friends/FriendsBoard.swift Strand/Friends/FriendsHighlights.swift \
  Strand/App/TabRoute.swift Strand/App/AppModel.swift StrandiOS/App/StrandiOSApp.swift \
  StrandTests/FriendsClientTests.swift StrandTests/FriendsBoardTests.swift StrandTests/FriendsHighlightsTests.swift \
  Strand/Resources/Localizable.xcstrings
git commit -m "feature: Friends on API v2 (signed requests, turning on by strap, no nickname or password)"
```

---

## Done when

- Both app targets build; the five Friends test classes and the package's Friends tests pass.
- `grep` of Step 9 prints nothing it should not.
- Friends turns on in the simulator against a local v2 server and stays on across a relaunch.
- The owner has been told that Task 3's writers are unverified on a real strap until they check them.
