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
