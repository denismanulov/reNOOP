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

    /// `text` with the whitespace outside its strings taken out: what the indenter may add and nothing else.
    private func withoutLayout(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        var inString = false, escaped = false
        for scalar in text.unicodeScalars {
            if inString {
                out.append(scalar)
                if escaped { escaped = false } else if scalar == "\\" { escaped = true } else if scalar == "\"" { inString = false }
            } else if scalar == "\"" {
                inString = true
                out.append(scalar)
            } else if scalar != " " && scalar != "\n" && scalar != "\t" && scalar != "\r" {
                out.append(scalar)
            }
        }
        return String(out)
    }

    /// An answer is laid out to be read without being parsed: every literal is copied as it came, so a
    /// number keeps the digits the server wrote and the members keep the server's order.
    func testAnAnswerIsIndentedWithEveryLiteralAsItCame() {
        let wire = #"{"name":"a\"b{,}","strain":38.6,"e":1e-7,"days":[[1791539880,64]],"n":null}"#
        let laid = FriendsWire.indented(wire)
        XCTAssertEqual(laid, """
        {
          "name": "a\\"b{,}",
          "strain": 38.6,
          "e": 1e-7,
          "days": [
            [
              1791539880,
              64
            ]
          ],
          "n": null
        }
        """)
        XCTAssertEqual(withoutLayout(laid), wire, "byte for byte, layout aside")
        XCTAssertEqual(Array(withoutLayout(laid).utf8), Array(wire.utf8))
    }

    func testIndentingLeavesStringsAndEmptyContainersAlone() {
        XCTAssertEqual(FriendsWire.indented(#"{"a":[],"b":{},"c":[{}]}"#), """
        {
          "a": [],
          "b": {},
          "c": [
            {}
          ]
        }
        """)
        // Layout the text already had outside its strings is replaced; inside them nothing is touched.
        let spaced = "{ \"a\" :\t[ ] ,\r\n \"b\" : \" x : y,\\t[z] \\\\\" }"
        XCTAssertEqual(FriendsWire.indented(spaced), "{\n  \"a\": [],\n  \"b\": \" x : y,\\t[z] \\\\\"\n}")
        XCTAssertEqual(FriendsWire.indented(#"{"имя":"Анна 🙂","n":12.0,"big":100000000000000000000}"#),
                       "{\n  \"имя\": \"Анна 🙂\",\n  \"n\": 12.0,\n  \"big\": 100000000000000000000\n}")
        XCTAssertEqual(FriendsWire.indented("38.6"), "38.6")
        XCTAssertEqual(FriendsWire.indented(""), "")
    }

    /// Text that is not an answer of the server's still comes out, with nothing lost.
    func testIndentingWhatIsNotAnAnswerLosesNothing() {
        for text in ["]}", "<html>busy</html>", #"{"open":"#, #""unterminated"#, "}{"] {
            XCTAssertEqual(withoutLayout(FriendsWire.indented(text)), withoutLayout(text), text)
        }
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
