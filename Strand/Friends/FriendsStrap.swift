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

    /// Under this prefix and a registry id: a serial-derived id seen once there and not trusted yet.
    static let sightedIdKeyPrefix = "friends.strapSightedId."

    /// A WHOOP 4.0's serial is trusted once the same value has come from two connections
    /// (`RepeatedSerialGate`, #1193). That gate counts within one run of the app, so a strap that stays
    /// connected, or an app relaunched between its connections, never gets there. This keeps the first
    /// sighting, so the second may come in a later run: the value is recorded for `deviceId` when it
    /// repeats, and the answer says whether it is recorded now. Call it once per hello; the two sightings
    /// are still two hellos, which is all the gate's rule asks for.
    @discardableResult
    static func sight(adoptedId: String, forDeviceId deviceId: String, defaults: UserDefaults = .standard) -> Bool {
        guard !adoptedId.isEmpty, !deviceId.isEmpty else { return false }
        let key = sightedIdKeyPrefix + deviceId
        if self.adoptedId(forDeviceId: deviceId, defaults: defaults) == adoptedId {
            defaults.removeObject(forKey: key)
            return true
        }
        guard defaults.string(forKey: key) == adoptedId else {
            defaults.set(adoptedId, forKey: key)
            return false
        }
        note(adoptedId: adoptedId, forDeviceId: deviceId, defaults: defaults)
        defaults.removeObject(forKey: key)
        return true
    }

    /// Whether a device of this kind has a serial to bind to: a WHOOP strap or an Oura ring.
    static func hasSerial(_ device: PairedDevice) -> Bool {
        SourceIdentity.isWhoop(device) || device.id.hasPrefix(ExperimentalBrand.oura.idPrefix + "-")
    }

    /// `deviceId` itself when it is already serial-derived (`whoop-<SERIAL>`, `oura-<SERIAL>`), else nil.
    /// A remainder that is a UUID is refused: the wizard mints `whoop-<UUID>` and `oura-<UUID>` from the
    /// peripheral's identifier before any serial is read, so that id names a pairing, not a strap. A WHOOP
    /// id must also be the one `WhoopSerialIdentity` would produce from its own remainder. The legacy
    /// `my-whoop` seed has neither prefix and relies on the value recorded where its serial is confirmed.
    static func serialDerivedId(_ deviceId: String) -> String? {
        let whoopPrefix = WhoopSerialIdentity.idPrefix + "-"
        let ouraPrefix = ExperimentalBrand.oura.idPrefix + "-"
        let remainder: String
        if deviceId.hasPrefix(whoopPrefix) {
            remainder = String(deviceId.dropFirst(whoopPrefix.count))
            guard WhoopSerialIdentity.adoptedId(serial: remainder) == deviceId else { return nil }
        } else if deviceId.hasPrefix(ouraPrefix) {
            remainder = String(deviceId.dropFirst(ouraPrefix.count))
        } else {
            return nil
        }
        return remainder.isEmpty || UUID(uuidString: remainder) != nil ? nil : deviceId
    }

    static func identity(hasSerial: Bool, adoptedId: String?) -> Identity {
        if let adoptedId { return .handle(handle(adoptedId: adoptedId)) }
        return hasSerial ? .pending : .none
    }

    /// The identity of the registry's active device: its own id when that is already serial-derived, else
    /// the id recorded for it where its serial was confirmed. An id that names its strap is never overruled
    /// by a recorded value, which may be left from another strap. Before the registry exists, or with nothing active,
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
        guard let registry, let active = registry.devices.first(where: { $0.id == registry.activeDeviceId && $0.status == .active }) else {
            return .pending
        }
        let adopted = serialDerivedId(active.id) ?? adoptedId(forDeviceId: active.id, defaults: defaults)
        return identity(hasSerial: hasSerial(active), adoptedId: adopted)
    }
}
