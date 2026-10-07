import Foundation
#if os(iOS)
import UIKit
#endif

/// Other apps on this iPhone that can connect to the same WHOOP.
///
/// Two apps on one strap split its history: the strap drops a chunk as soon as ANY app acks it, so the
/// hours one app syncs first never reach the other (`ForeignOffloadDetector` is the runtime half of this).
/// iOS lets an app ask only whether a URL scheme it declared in `LSApplicationQueriesSchemes` has a
/// handler, so this can see an app only through its scheme:
/// - upstream NOOP registers `noop://`, which reNOOP deliberately leaves free (it answers to `renoop://`);
/// - `whoop://` is assumed for the WHOOP app and NOT verified — a miss there is silence, never a false alarm.
enum OtherStrapApps {
    struct Known: Equatable {
        let name: String
        let scheme: String
    }

    static let known: [Known] = [
        Known(name: "NOOP", scheme: "noop"),
        Known(name: "WHOOP", scheme: "whoop"),
    ]

    /// Names of the known strap apps installed beside this one, in `known` order.
    @MainActor
    static func installed() -> [String] {
        #if os(iOS)
        return known.compactMap { app in
            guard let url = URL(string: "\(app.scheme)://") else { return nil }
            return UIApplication.shared.canOpenURL(url) ? app.name : nil
        }
        #else
        return []
        #endif
    }

    /// "NOOP", "NOOP and WHOOP" — the installed names as one phrase, or nil when none is known.
    static func phrase(_ names: [String]) -> String? {
        guard !names.isEmpty else { return nil }
        return names.formatted(.list(type: .and))
    }
}

/// The in-app warning for a second app pulling this strap's history, raised by `BLEManager` when
/// `ForeignOffloadDetector` has the evidence. Shown at most once per process, and never again once the
/// user says so: someone who deliberately runs two apps should not be nagged every launch.
@MainActor
final class OtherStrapAppWarning: ObservableObject {
    static let shared = OtherStrapAppWarning()
    static let mutedKey = "noop.otherAppWarningMuted"

    @Published var isPresented = false
    /// The installed apps named in the message; empty when the syncing app is not one we can see.
    @Published private(set) var installedNames: [String] = []
    private var shownThisProcess = false

    func reportForeignOffload() {
        guard !shownThisProcess, !UserDefaults.standard.bool(forKey: Self.mutedKey) else { return }
        shownThisProcess = true
        installedNames = OtherStrapApps.installed()
        isPresented = true
    }

    func mute() {
        UserDefaults.standard.set(true, forKey: Self.mutedKey)
    }
}
