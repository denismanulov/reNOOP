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
