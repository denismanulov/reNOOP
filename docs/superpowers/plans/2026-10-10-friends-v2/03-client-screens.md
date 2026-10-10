# Friends Client Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Friends tab the screens version 2 needs: answering what waits on the strap, inviting a friend by QR, link or code, the account's phones, the page that shows what the server stores, and the invite link.

**Architecture:** Three new files, one per subject (`FriendsRequests`, `FriendsInvite`, `FriendsPhones`), each a set of small views over calls the store already has from plan 2. The tab and the two sheets gain a line or a section each. Nothing here adds a request the store does not already make.

**Tech Stack:** SwiftUI, CoreImage (the QR), XCTest.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-10-10-friends-keys-and-straps-design.md`, sections 4 and 9.3.
- Requires plan 2 complete: `FriendsStore` at version 2, `FriendsWelcome.spaced(_:)` and `.moment(_:)`, `FriendsHero(name:id:own:imageData:avatarRev:)`.
- The new views were type-checked against the plan 2 store before being written down. Type them as given.
- UI uses `StrandPalette`, `StrandFont`, `NoopMetrics` and `FriendsStyle` only. The one literal colour is the white plate behind the QR: a camera reads it, so it is not themed.
- A notice on the tab is a `NoticeCard`: a title, one sentence, at most one action. Answers with two choices live in the friends sheet.
- macOS 13 is a deployment target: no `navigationDestination(item:)`, no `onChange` with two parameters in shared files (`onChangeCompat` is the project's).
- Both app targets build after every task. Commands are in plan 2, "Build and test commands".
- Strings: English key and a `ru` translation, appended with the helper below.
- Commit subjects `feature:` / `fix:` / `docs:`; no `Co-Authored-By`; stage named files only.

## String helper

Every task adds strings the same way. Save this once, outside the repository:

```bash
cat > /tmp/friends_strings.py <<'PY'
"""Appends string-catalogue entries (English key, Russian value) as one-line entries at the end of
"strings". Reads a JSON list of [key, ru] pairs on stdin. Skips a key that exists. Never re-serialises
the catalogue, which is formatted by hand."""
import json, sys
path = "Strand/Resources/Localizable.xcstrings"
text = open(path, encoding="utf-8").read()
have = json.loads(text)["strings"]
tail = '\n  },\n  "version"'
assert text.count(tail) == 1, "the catalogue does not end the way this script expects"
lines = []
for key, ru in json.load(sys.stdin):
    if key in have:
        print("already there:", key[:60])
        continue
    entry = {"localizations": {"ru": {"stringUnit": {"state": "translated", "value": ru}}}}
    lines.append("    %s: %s" % (json.dumps(key, ensure_ascii=False), json.dumps(entry, ensure_ascii=False)))
if lines:
    open(path, "w", encoding="utf-8").write(text.replace(tail, ",\n" + ",\n".join(lines) + tail))
json.loads(open(path, encoding="utf-8").read())
print("added", len(lines))
PY
```

After each use, `git diff --stat -- Strand/Resources/Localizable.xcstrings` must show only insertions and one changed line (the comma after the previous last entry). Anything else: `git checkout -- Strand/Resources/Localizable.xcstrings` and report it.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `Strand/Friends/FriendsRequests.swift` (new) | The words for a request, the tab's notices, the sheet's Requests section. | 1 |
| `StrandTests/FriendsRequestsTests.swift` (new) | The words, and the QR. | 1, 2 |
| `Strand/Friends/FriendsInvite.swift` (new) | The QR, the invite page, the sheet's Add Friend sections. | 2 |
| `Strand/Friends/FriendsPhones.swift` (new) | The phones page and the export page. | 3 |
| `Strand/Friends/FriendsView.swift` | The notices; the invite-link question. | 1, 4 |
| `Strand/Friends/FriendsSheets.swift` | The sheet's sections; two links on the sharing page. | 1, 2, 3 |
| `StrandiOS/App/StrandiOSApp.swift`, `StrandiOS/App/RootTabView.swift` | The invite link. | 4 |

---

### Task 1: What waits for an answer

**Files:**
- Create: `Strand/Friends/FriendsRequests.swift`
- Create: `StrandTests/FriendsRequestsTests.swift`
- Modify: `Strand/Friends/FriendsView.swift` (the `board`)
- Modify: `Strand/Friends/FriendsSheets.swift` (`FriendsManageSheet`)

**Interfaces:**
- Consumes: `FriendsStore.claims`, `.unconfirmed`, `.probationUntil`, `.approve(_:)`, `.decline(_:)`, `.trustDevice(_:)`, `.removeDevice(_:)`; `FriendsWelcome.spaced(_:)`, `.moment(_:)`; `FriendsFormat.ago(_:now:)`; `NoticeCard`; `friendsCapsuleButton(prominent:)`.
- Produces: `FriendsRequestText.device(_ platform: String?) -> String`, `.title(_:)`, `.detail(_:)`, `.yes(_:) -> LocalizedStringKey`, `.joined(_:now:)`, `.seen(_:now:)`; `FriendsNotices(onReview:)`; `FriendsRequestsSection()`.

- [ ] **Step 1: Write the failing test**

Create `StrandTests/FriendsRequestsTests.swift`. Its last test is for Task 2's QR and is added there; leave it out for now.

```swift
import XCTest
@testable import Strand

/// The words for what waits for an answer, and the invite's QR.
final class FriendsRequestsTests: XCTestCase {

    private func claim(_ kind: FriendsClaim.Kind, platform: String? = "android") -> FriendsClaim {
        FriendsClaim(id: 1, kind: kind, code: "481902", state: .pending, platform: platform,
                     createdAt: 1_791_540_000, maturesAt: 1_791_712_800)
    }

    func testAPhoneIsNamedByItsPlatformAndAnUnknownOneIsStillAPhone() {
        XCTAssertFalse(FriendsRequestText.device("ios").isEmpty)
        XCTAssertNotEqual(FriendsRequestText.device("ios"), FriendsRequestText.device("android"))
        XCTAssertNotEqual(FriendsRequestText.device("mac"), FriendsRequestText.device("android"))
        XCTAssertEqual(FriendsRequestText.device("watch"), FriendsRequestText.device(nil))
    }

    /// A request to join carries the code both phones show; a request for the strap has none to compare.
    func testARequestToJoinShowsItsCodeAndTheTwoKindsReadDifferently() {
        let join = claim(.join), take = claim(.take)
        XCTAssertTrue(FriendsRequestText.detail(join).contains("481 902"))
        XCTAssertTrue(FriendsRequestText.detail(join).contains(FriendsRequestText.device("android")))
        XCTAssertFalse(FriendsRequestText.detail(take).contains("481"))
        XCTAssertNotEqual(FriendsRequestText.title(join), FriendsRequestText.title(take))
    }

    func testACodeIsReadOutInTwoGroups() {
        XCTAssertEqual(FriendsWelcome.spaced("481902"), "481 902")
        XCTAssertEqual(FriendsWelcome.spaced("12"), "12", "anything that is not six digits is left alone")
    }

    func testAnInviteLinkBecomesASquareQRCode() throws {
        let image = try XCTUnwrap(FriendsQRCode.image("https://renoop.duckdns.org/i/K7QM2-XRD4P", side: 660))
        XCTAssertEqual(image.width, image.height)
        XCTAssertGreaterThan(image.width, 300)
        XCTAssertLessThanOrEqual(image.width, 660)
    }
}
```

For this task, omit `testAnInviteLinkBecomesASquareQRCode`.

- [ ] **Step 2: Run it to see it fail**

```bash
xcodegen generate >/dev/null && xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO test -only-testing:StrandTests/FriendsRequestsTests 2>&1 | grep -E 'error:' | head -3
```

Expected: `error: cannot find 'FriendsRequestText' in scope`.

- [ ] **Step 3: Write `FriendsRequests.swift`**

```swift
//  FriendsRequests.swift
//  NOOP · Friends — what waits for the wearer's answer: a new phone asking to join the account, another
//  account asking for the strap, and a phone that got in while nobody answered. The tab says so in a
//  notice; the answers are given in the friends sheet, behind the bar's button and its badge.
//
//  A phone is told apart by a six-digit code that both phones show. A strap's serial can be read by
//  anyone near it, so the code on the wearer's own new phone is the one thing that says a request is
//  theirs.

import SwiftUI
import StrandDesign

/// The words for a request, kept apart from the views so they are tested without one.
enum FriendsRequestText {
    /// What a phone is called by its platform. One the app does not know reads as a phone.
    static func device(_ platform: String?) -> String {
        switch platform {
        case "ios": return String(localized: "iPhone")
        case "android": return String(localized: "Android phone")
        case "mac": return String(localized: "Mac")
        default: return String(localized: "Phone")
        }
    }

    static func title(_ claim: FriendsClaim) -> String {
        switch claim.kind {
        case .join: return String(localized: "A new phone wants to join your account")
        case .take: return String(localized: "Someone connected your strap")
        }
    }

    static func detail(_ claim: FriendsClaim) -> String {
        switch claim.kind {
        case .join:
            return String(localized: "\(device(claim.platform)), code \(FriendsWelcome.spaced(claim.code)). Confirm it only if the code is on your own phone.")
        case .take:
            return String(localized: "If the strap is no longer yours, release it. They get no access to your account.")
        }
    }

    /// The answer that grants a request: a phone is confirmed, a strap is released.
    static func yes(_ claim: FriendsClaim) -> LocalizedStringKey {
        claim.kind == .join ? "Confirm" : "Release"
    }

    /// "iPhone, joined 2 hr. ago. Keep it only if it is yours."
    static func joined(_ phone: FriendsDevice, now: Date = Date()) -> String {
        String(localized: "\(device(phone.platform)), joined \(FriendsFormat.ago(phone.addedAt, now: now)). Keep it only if it is yours.")
    }

    /// "Joined 3 days ago · last seen 5 min. ago"
    static func seen(_ phone: FriendsDevice, now: Date = Date()) -> String {
        String(localized: "Joined \(FriendsFormat.ago(phone.addedAt, now: now)) · last seen \(FriendsFormat.ago(phone.lastSeenAt, now: now))")
    }
}

/// The notices the tab shows above everything else while something waits.
struct FriendsNotices: View {
    @ObservedObject private var store = FriendsStore.shared
    /// Opens the friends sheet, where the answers are given.
    let onReview: () -> Void

    var body: some View {
        if let until = store.probationUntil {
            NoticeCard(title: Text("This phone is not confirmed yet"),
                       message: Text("It can read and upload your day. Changes wait until \(FriendsWelcome.moment(until)), or until another phone of yours confirms it."),
                       systemImage: "hourglass", tone: .warning)
        }
        ForEach(store.claims) { claim in
            NoticeCard(title: Text(verbatim: FriendsRequestText.title(claim)),
                       message: Text(verbatim: FriendsRequestText.detail(claim)),
                       systemImage: claim.kind == .join ? "iphone" : "dot.radiowaves.left.and.right",
                       tone: .info, actionTitle: "Review", action: onReview)
        }
        ForEach(store.unconfirmed) { phone in
            NoticeCard(title: Text("A phone joined without your confirmation"),
                       message: Text(verbatim: FriendsRequestText.joined(phone)),
                       systemImage: "exclamationmark.shield.fill", tone: .warning,
                       actionTitle: "Review", action: onReview)
        }
    }
}

/// The same things in the friends sheet, each with its two answers.
struct FriendsRequestsSection: View {
    @ObservedObject private var store = FriendsStore.shared
    @State private var working = false

    /// A phone that joined without confirmation answers nothing until it is confirmed itself.
    private var locked: Bool { working || store.probationUntil != nil }

    var body: some View {
        if !store.claims.isEmpty || !store.unconfirmed.isEmpty {
            Section {
                ForEach(store.claims) { claim in
                    row(title: FriendsRequestText.title(claim), detail: FriendsRequestText.detail(claim),
                        yes: FriendsRequestText.yes(claim), no: "Decline",
                        onYes: { await store.approve(claim) }, onNo: { await store.decline(claim) })
                }
                ForEach(store.unconfirmed) { phone in
                    row(title: String(localized: "A phone joined without your confirmation"),
                        detail: FriendsRequestText.joined(phone), yes: "Keep", no: "Remove",
                        onYes: { await store.trustDevice(phone.id) }, onNo: { await store.removeDevice(phone.id) })
                }
            } header: {
                Text("Requests")
            } footer: {
                if store.probationUntil != nil {
                    Text("This phone joined without confirmation, so it cannot answer these.")
                }
            }
        }
    }

    private func row(title: String, detail: String, yes: LocalizedStringKey, no: LocalizedStringKey,
                     onYes: @escaping () async -> Void, onNo: @escaping () async -> Void) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: title)
                    .font(StrandFont.headline)
                    .foregroundStyle(StrandPalette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(verbatim: detail)
                    .font(StrandFont.pro(15))
                    .foregroundStyle(StrandPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: 8) {
                Button { run(onYes) } label: {
                    Text(yes).font(StrandFont.pro(15, weight: .semibold)).lineLimit(1)
                }
                .friendsCapsuleButton(prominent: true)
                Button { run(onNo) } label: {
                    Text(no).font(StrandFont.pro(15, weight: .semibold)).lineLimit(1)
                }
                .friendsCapsuleButton(prominent: false)
            }
            .disabled(locked)
        }
        .padding(.vertical, 4)
    }

    private func run(_ answer: @escaping () async -> Void) {
        working = true
        Task {
            await answer()
            working = false
        }
    }
}
```

- [ ] **Step 4: Show the notices on the tab**

In `Strand/Friends/FriendsView.swift`, in `board`, directly after the block that shows `store.errorText` in a `NoticeCard` and before `if let feed = store.feed {`, add:

```swift
                    FriendsNotices(onReview: { showFriends = true })
```

The bar button's badge counts what waits in the sheet, so it now counts unconfirmed phones too. Replace

```swift
    private var waiting: Int { store.claims.count }
```

with

```swift
    private var waiting: Int { store.claims.count + store.unconfirmed.count }
```

- [ ] **Step 5: Give the answers in the sheet**

In `Strand/Friends/FriendsSheets.swift`, in `FriendsManageSheet`, add one line as the first thing inside `Form {`:

```swift
                FriendsRequestsSection()
```

- [ ] **Step 6: Add the strings**

```bash
python3 /tmp/friends_strings.py <<'JSON'
[["iPhone", "iPhone"],
 ["Android phone", "Телефон Android"],
 ["Mac", "Mac"],
 ["Phone", "Телефон"],
 ["A new phone wants to join your account", "Новый телефон хочет войти в ваш аккаунт"],
 ["Someone connected your strap", "Кто-то подключил ваш браслет"],
 ["%@, code %@. Confirm it only if the code is on your own phone.", "%@, код %@. Подтверждайте, только если этот код на вашем телефоне."],
 ["If the strap is no longer yours, release it. They get no access to your account.", "Если браслет больше не ваш, отпустите его. Доступа к вашему аккаунту это не даёт."],
 ["Confirm", "Подтвердить"],
 ["Release", "Отпустить"],
 ["Decline", "Отклонить"],
 ["Keep", "Оставить"],
 ["Remove", "Убрать"],
 ["Review", "Посмотреть"],
 ["%@, joined %@. Keep it only if it is yours.", "%@, вошёл %@. Оставляйте, только если он ваш."],
 ["Joined %@ · last seen %@", "Вошёл %@ · был в сети %@"],
 ["This phone is not confirmed yet", "Этот телефон ещё не подтверждён"],
 ["It can read and upload your day. Changes wait until %@, or until another phone of yours confirms it.", "Он может читать и отправлять ваш день. Изменения станут доступны %@ или когда его подтвердит другой ваш телефон."],
 ["A phone joined without your confirmation", "Телефон вошёл без вашего подтверждения"],
 ["This phone joined without confirmation, so it cannot answer these.", "Этот телефон вошёл без подтверждения и не может отвечать на запросы."]]
JSON
```

- [ ] **Step 7: Run the test and both builds**

```bash
xcodegen generate >/dev/null && xcodegen generate --spec project-nowatch.yml >/dev/null
xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO test -only-testing:StrandTests/FriendsRequestsTests 2>&1 | grep -E 'error:|failed|Executed|TEST' | tail -4
```

Expected: `Executed 3 tests, with 0 failures`. Then the iOS build command from plan 2: `** BUILD SUCCEEDED **`.

- [ ] **Step 8: Commit**

```bash
git add Strand/Friends/FriendsRequests.swift StrandTests/FriendsRequestsTests.swift Strand/Friends/FriendsView.swift Strand/Friends/FriendsSheets.swift Strand/Resources/Localizable.xcstrings
git commit -m "feature: Friends requests (a new phone, the strap, an unconfirmed phone) on the tab and in its sheet"
```

---

### Task 2: Inviting a friend

**Files:**
- Create: `Strand/Friends/FriendsInvite.swift`
- Modify: `Strand/Friends/FriendsSheets.swift` (`FriendsManageSheet`, replaced)
- Modify: `StrandTests/FriendsRequestsTests.swift` (one test)

**Interfaces:**
- Consumes: `FriendsStore.createInvite()`, `.inviteCode(_:)`, `.invites`, `.loadInvites()`, `.revokeInvite(_:)`, `.redeem(_:)`, `.serverAddress`, `.probationUntil`; `FriendsInviteCode.pageLink(server:code:)`, `.normalized(_:)`, `.extract(_:)`; `FriendsRequestsSection` (Task 1).
- Produces: `FriendsQRCode.image(_ text: String, side: CGFloat) -> CGImage?`; `FriendsShownInvite(invite:code:)`; `FriendsInvitePage(shown:)`; `FriendsAddSections(shown: Binding<FriendsShownInvite?>)`.

- [ ] **Step 1: Add the failing test**

Add `testAnInviteLinkBecomesASquareQRCode` (shown in Task 1, Step 1) to `StrandTests/FriendsRequestsTests.swift`, and run:

```bash
xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO test -only-testing:StrandTests/FriendsRequestsTests 2>&1 | grep -E 'error:' | head -3
```

Expected: `error: cannot find 'FriendsQRCode' in scope`.

- [ ] **Step 2: Write `FriendsInvite.swift`**

```swift
//  FriendsInvite.swift
//  NOOP · Friends — handing an invite over. A friend is added by a one-time code and by nothing else:
//  the server has no directory and no search. The code travels as a QR the friend's camera reads, as a
//  link in a message, or typed; all three are the same ten characters.

import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI
import StrandAnalytics
import StrandDesign

/// A QR code as an image, in whole pixels per module so it stays sharp.
enum FriendsQRCode {
    /// The code for `text`, about `side` pixels on a side; nil when the text cannot be encoded.
    static func image(_ text: String, side: CGFloat) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage, output.extent.width > 0 else { return nil }
        let scale = max(1, (side / output.extent.width).rounded(.down))
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        return CIContext().createCGImage(scaled, from: scaled.extent)
    }
}

/// An invite and its code together: the code is known only on the phone that made the invite.
struct FriendsShownInvite: Equatable {
    let invite: FriendsInvite
    /// As shown, "XXXXX-XXXXX".
    let code: String
}

/// One invite, laid out to be handed over: the QR, the code to type instead, and the system's Share.
struct FriendsInvitePage: View {
    let shown: FriendsShownInvite

    @ObservedObject private var store = FriendsStore.shared

    private static let qrSide: CGFloat = 220

    /// The invite page on the friends server: what the QR holds and what a message carries.
    private var link: String {
        FriendsInviteCode.pageLink(server: store.serverAddress,
                                   code: FriendsInviteCode.normalized(shown.code) ?? shown.code)
    }

    private var shareText: String {
        String(localized: "Add me on reNOOP Friends: \(link)\nOr enter the code \(shown.code) on the Friends tab.")
    }

    private var expiry: String {
        Date(timeIntervalSince1970: TimeInterval(shown.invite.expiresAt))
            .formatted(.dateTime.day().month(.wide).locale(AppLanguage.activeLocale))
    }

    var body: some View {
        Form {
            Section {
                VStack(spacing: NoopMetrics.space4) {
                    if let qr = FriendsQRCode.image(link, side: Self.qrSide * 3) {
                        // White behind black in either appearance: a camera reads this, a theme must not.
                        Image(qr, scale: 3, label: Text("Invite QR code"))
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .frame(width: Self.qrSide, height: Self.qrSide)
                            .padding(12)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    Text(verbatim: shown.code)
                        .font(StrandFont.pro(28, weight: .semibold).monospaced())
                        .foregroundStyle(StrandPalette.textPrimary)
                        .textSelection(.enabled)
                    Text("Valid until \(expiry). It works once.")
                        .font(StrandFont.pro(13))
                        .foregroundStyle(StrandPalette.textSecondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, NoopMetrics.space3)
                .listRowBackground(Color.clear)
            }
            Section {
                ShareLink(item: shareText) {
                    SettingsRowLabel(title: "Share Invite", icon: "square.and.arrow.up", color: StrandPalette.settingsBlue)
                }
            } footer: {
                Text("Whoever uses this code becomes your friend at once and sees what you share. Give it only to the person you mean.")
            }
        }
        .settingsForm()
        .navigationTitle(Text("Invite"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }
}

/// "Add Friend" in the friends sheet: making an invite to hand over, using one that was handed over,
/// and the invites still waiting to be used.
struct FriendsAddSections: View {
    /// Set to push an invite's page.
    @Binding var shown: FriendsShownInvite?

    @ObservedObject private var store = FriendsStore.shared
    @State private var code = ""
    @State private var working = false
    @State private var message: String?

    /// A phone that joined without confirmation leaves the friend list alone.
    private var locked: Bool { store.probationUntil != nil }

    var body: some View {
        Section {
            Button(action: invite) {
                SettingsRowLabel(title: "Invite a Friend", icon: "qrcode", color: StrandPalette.settingsGreen)
            }
            .disabled(working || locked)
            HStack(spacing: 8) {
                TextField("Enter a Code", text: $code)
                    .disableAutocorrection(true)
                    #if os(iOS)
                    .textInputAutocapitalization(.characters)
                    .submitLabel(.done)
                    #endif
                    .onSubmit(redeem)
                if working {
                    ProgressView()
                } else {
                    Button(action: redeem) { Text("Add").font(StrandFont.pro(15, weight: .semibold)) }
                        .friendsCapsuleButton(prominent: true)
                        .disabled(FriendsInviteCode.extract(code) == nil || locked)
                }
            }
        } header: {
            Text("Add Friend")
        } footer: {
            if let message {
                Text(verbatim: message)
            } else {
                Text("An invite works once and makes you friends at once. Paste a link or type its code.")
            }
        }
        if let invites = store.invites, !invites.isEmpty {
            Section {
                ForEach(invites) { invite in waiting(invite) }
            } header: {
                Text("Invites Waiting")
            }
        }
    }

    /// An invite not used yet. One made on this phone opens again; one made elsewhere can only be revoked.
    private func waiting(_ invite: FriendsInvite) -> some View {
        let code = store.inviteCode(invite.id)
        return HStack(spacing: 8) {
            Button {
                if let code { shown = FriendsShownInvite(invite: invite, code: code) }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: code ?? String(localized: "Invite"))
                        .font(StrandFont.pro(17).monospaced())
                        .foregroundStyle(StrandPalette.textPrimary)
                    Text("Valid until \(Self.day(invite.expiresAt))")
                        .font(StrandFont.pro(13))
                        .foregroundStyle(StrandPalette.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(code == nil)
            Button { Task { await store.revokeInvite(invite.id) } } label: {
                Text("Revoke").font(StrandFont.pro(15, weight: .semibold)).lineLimit(1)
            }
            .friendsCapsuleButton(prominent: false)
            .disabled(locked)
        }
    }

    private static func day(_ ts: Int) -> String {
        Date(timeIntervalSince1970: TimeInterval(ts))
            .formatted(.dateTime.day().month(.wide).locale(AppLanguage.activeLocale))
    }

    private func invite() {
        working = true
        message = nil
        Task {
            if let made = await store.createInvite(), let code = made.code {
                shown = FriendsShownInvite(invite: made, code: code)
            } else {
                message = store.errorText
            }
            working = false
        }
    }

    private func redeem() {
        guard !working, FriendsInviteCode.extract(code) != nil else { return }
        working = true
        Task {
            if let friend = await store.redeem(code) {
                message = String(localized: "You and \(friend.name) are friends now.")
                code = ""
            } else {
                message = store.errorText
            }
            working = false
        }
    }
}
```

- [ ] **Step 3: Replace the sheet**

In `Strand/Friends/FriendsSheets.swift`, replace `struct FriendsManageSheet` and its doc comment with:

```swift
/// The sheet behind the tab's bar button. Fitness invites and answers invitations from one place, so
/// this holds what waits for an answer, inviting a friend, using a friend's code, and the way to the
/// wearer's own sharing.
struct FriendsManageSheet: View {
    @ObservedObject private var store = FriendsStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var shown: FriendsShownInvite?

    var body: some View {
        NavigationStack {
            Form {
                FriendsRequestsSection()
                FriendsAddSections(shown: $shown)
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
            .navigationDestination(isPresented: Binding(get: { shown != nil }, set: { if !$0 { shown = nil } })) {
                if let shown { FriendsInvitePage(shown: shown) }
            }
            .task { await store.loadInvites() }
        }
    }
}
```

- [ ] **Step 4: Add the strings**

```bash
python3 /tmp/friends_strings.py <<'JSON'
[["Invite a Friend", "Пригласить друга"],
 ["Enter a Code", "Введите код"],
 ["Add", "Добавить"],
 ["An invite works once and makes you friends at once. Paste a link or type its code.", "Приглашение работает один раз и сразу делает вас друзьями. Вставьте ссылку или введите код."],
 ["You and %@ are friends now.", "Вы и %@ теперь друзья."],
 ["Invites Waiting", "Ожидающие приглашения"],
 ["Valid until %@", "Действует до %@"],
 ["Revoke", "Отозвать"],
 ["Invite", "Приглашение"],
 ["Invite QR code", "QR-код приглашения"],
 ["Valid until %@. It works once.", "Действует до %@. Работает один раз."],
 ["Share Invite", "Поделиться приглашением"],
 ["Whoever uses this code becomes your friend at once and sees what you share. Give it only to the person you mean.", "Тот, кто введёт этот код, сразу станет вашим другом и увидит то, чем вы делитесь. Давайте его только тому, кому хотите."],
 ["Add me on reNOOP Friends: %@\nOr enter the code %@ on the Friends tab.", "Добавь меня в друзья в reNOOP: %@\nИли введи код %@ на вкладке «Друзья»."]]
JSON
```

- [ ] **Step 5: Run the test and both builds**

```bash
xcodegen generate >/dev/null && xcodegen generate --spec project-nowatch.yml >/dev/null
xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO test -only-testing:StrandTests/FriendsRequestsTests 2>&1 | grep -E 'error:|failed|Executed|TEST' | tail -4
```

Expected: `Executed 4 tests, with 0 failures`. Then the iOS build: `** BUILD SUCCEEDED **`.

- [ ] **Step 6: Commit**

```bash
git add Strand/Friends/FriendsInvite.swift Strand/Friends/FriendsSheets.swift StrandTests/FriendsRequestsTests.swift Strand/Resources/Localizable.xcstrings
git commit -m "feature: invite a friend by QR, link or code"
```

---

### Task 3: The account's phones, and what the server stores

**Files:**
- Create: `Strand/Friends/FriendsPhones.swift`
- Modify: `Strand/Friends/FriendsSheets.swift` (`FriendsSharingPage`)

**Interfaces:**
- Consumes: `FriendsStore.devices`, `.loadDevices()`, `.removeDevice(_:)`, `.trustDevice(_:)`, `.removeThisPhone()`, `.exportText()`, `.probationUntil`; `FriendsRequestText.device(_:)`, `.seen(_:now:)` (Task 1).
- Produces: `FriendsPhonesPage(onAccountLeft:)`, `FriendsExportPage()`.

- [ ] **Step 1: Write `FriendsPhones.swift`**

```swift
//  FriendsPhones.swift
//  NOOP · Friends — two pages pushed from My Sharing: the phones the account lives on, and everything
//  the server holds about the wearer, shown as the server sends it.

import SwiftUI
import StrandDesign

/// The account's phones. Each holds its own key, so removing one ends what that phone can do at once.
struct FriendsPhonesPage: View {
    /// This phone left the account: the sheet that showed it has nothing left to show.
    let onAccountLeft: () -> Void

    @ObservedObject private var store = FriendsStore.shared
    @State private var confirmLeave = false

    private var phones: [FriendsDevice] { store.devices ?? [] }
    /// A phone that joined without confirmation removes nobody but itself.
    private var locked: Bool { store.probationUntil != nil }
    /// This phone may leave while the account stays on another confirmed phone, or when it is itself
    /// unconfirmed. The account's only confirmed phone cannot: there would be no way back in.
    private var canLeave: Bool { locked || phones.contains { !$0.current && $0.probationUntil == nil } }

    var body: some View {
        Form {
            Section {
                if store.devices == nil {
                    ProgressView().frame(maxWidth: .infinity)
                }
                ForEach(phones) { phone in row(phone) }
            } footer: {
                Text("Each phone holds its own key. Removing one stops it reading and uploading at once.")
            }
            if canLeave {
                Section {
                    // The dialog hangs off the button that asks for it, where iOS 26 points it.
                    Button("Remove This Phone", role: .destructive) { confirmLeave = true }
                        .confirmationDialog("Remove This Phone", isPresented: $confirmLeave, titleVisibility: .visible) {
                            Button("Remove This Phone", role: .destructive) {
                                Task {
                                    guard await store.removeThisPhone() else { return }
                                    onAccountLeft()
                                }
                            }
                        } message: {
                            Text("Friends turns off on this phone. The account stays on your other phone.")
                        }
                } footer: {
                    if let error = store.errorText {
                        Text(verbatim: error).foregroundStyle(StrandPalette.settingsRed)
                    }
                }
            }
        }
        .settingsForm()
        .navigationTitle(Text("Phones"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task { await store.loadDevices() }
    }

    private func row(_ phone: FriendsDevice) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(verbatim: FriendsRequestText.device(phone.platform))
                        .font(StrandFont.headline)
                        .foregroundStyle(StrandPalette.textPrimary)
                    if phone.current {
                        Text("This Phone")
                            .font(StrandFont.pro(13))
                            .foregroundStyle(StrandPalette.textSecondary)
                    }
                }
                Text(verbatim: FriendsRequestText.seen(phone))
                    .font(StrandFont.pro(13))
                    .foregroundStyle(StrandPalette.textSecondary)
                if phone.probationUntil != nil {
                    Text("Joined without confirmation")
                        .font(StrandFont.pro(13))
                        .foregroundStyle(StrandPalette.settingsOrange)
                }
            }
            Spacer(minLength: 8)
            if !phone.current, !locked {
                if phone.probationUntil != nil {
                    Button { Task { await store.trustDevice(phone.id) } } label: {
                        Text("Keep").font(StrandFont.pro(15, weight: .semibold)).lineLimit(1)
                    }
                    .friendsCapsuleButton(prominent: true)
                }
                Button { Task { await store.removeDevice(phone.id) } } label: {
                    Text("Remove").font(StrandFont.pro(15, weight: .semibold)).lineLimit(1)
                }
                .friendsCapsuleButton(prominent: false)
            }
        }
        .padding(.vertical, 2)
    }
}

/// Everything the server holds for the account, as the server sent it: the wearer reads what is kept
/// about them instead of being told.
struct FriendsExportPage: View {
    @ObservedObject private var store = FriendsStore.shared
    @State private var text: String?
    @State private var loaded = false

    var body: some View {
        ScrollView {
            Group {
                if let text {
                    Text(verbatim: text)
                        .font(StrandFont.pro(13).monospaced())
                        .foregroundStyle(StrandPalette.textPrimary)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else if loaded {
                    Text(verbatim: store.errorText ?? "")
                        .font(StrandFont.pro(15))
                        .foregroundStyle(StrandPalette.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
            .padding(16)
        }
        .background(StrandPalette.summaryCanvas.ignoresSafeArea())
        .navigationTitle(Text("What the Server Stores"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                if let text {
                    ShareLink(item: text) { Image(systemName: "square.and.arrow.up") }
                }
            }
        }
        .task {
            text = await store.exportText()
            loaded = true
        }
    }
}
```

- [ ] **Step 2: Link the two pages from My Sharing**

In `FriendsSharingPage`, between the "Friends Can See" section (the one that ends with `.disabled(onProbation)`) and the section that shows the server, add:

```swift
            Section {
                NavigationLink {
                    FriendsPhonesPage(onAccountLeft: onAccountLeft)
                } label: {
                    SettingsRowLabel(title: "Phones", icon: "iphone", color: StrandPalette.settingsBlue)
                }
                NavigationLink {
                    FriendsExportPage()
                } label: {
                    SettingsRowLabel(title: "What the Server Stores", icon: "doc.text.magnifyingglass",
                                     color: StrandPalette.settingsIndigo)
                }
            } footer: {
                Text("Everything the server holds about you, exactly as it holds it.")
            }
```

- [ ] **Step 3: Add the strings**

```bash
python3 /tmp/friends_strings.py <<'JSON'
[["Phones", "Телефоны"],
 ["This Phone", "Этот телефон"],
 ["Each phone holds its own key. Removing one stops it reading and uploading at once.", "У каждого телефона свой ключ. Убранный телефон сразу перестаёт читать и отправлять данные."],
 ["Remove This Phone", "Убрать этот телефон"],
 ["Friends turns off on this phone. The account stays on your other phone.", "«Друзья» выключатся на этом телефоне. Аккаунт останется на другом вашем телефоне."],
 ["Joined without confirmation", "Вошёл без подтверждения"],
 ["What the Server Stores", "Что хранит сервер"],
 ["Everything the server holds about you, exactly as it holds it.", "Всё, что сервер хранит о вас, в том виде, в каком хранит."]]
JSON
```

- [ ] **Step 4: Build both targets**

```bash
xcodegen generate >/dev/null && xcodegen generate --spec project-nowatch.yml >/dev/null
xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO build 2>&1 | grep -E 'error:|BUILD' | head -10
```

Expected: `** BUILD SUCCEEDED **`, and the same from the iOS build.

- [ ] **Step 5: Commit**

```bash
git add Strand/Friends/FriendsPhones.swift Strand/Friends/FriendsSheets.swift Strand/Resources/Localizable.xcstrings
git commit -m "feature: the Friends account's phones, and a page of what the server stores"
```

---

### Task 4: The invite link

**Files:**
- Modify: `StrandiOS/App/StrandiOSApp.swift` (`.onOpenURL`, near line 369)
- Modify: `StrandiOS/App/RootTabView.swift` (one modifier, near line 142)
- Modify: `Strand/Friends/FriendsView.swift`

**Interfaces:**
- Consumes: `FriendsStore.pendingInviteCode`, `.redeem(_:)`, `.isOn`; `FriendsInviteCode.extract(_:)`.
- Produces: `renoop://friends/add?c=<code>` opens the Friends tab and asks before adding.

- [ ] **Step 1: Take the code out of the link**

In `StrandiOS/App/StrandiOSApp.swift`, the `.onOpenURL` handler has two branches. Add one between them, and `import StrandAnalytics` at the top of the file if it is not there:

```swift
                .onOpenURL { url in
                    if url.host == "import-health" {
                        model.handleHealthImportURL(url)
                    } else if url.host == "friends", let code = FriendsInviteCode.extract(url.absoluteString) {
                        // An invite (`renoop://friends/add?c=…`). Nobody is added by opening a link: the
                        // Friends tab asks first.
                        FriendsStore.shared.pendingInviteCode = code
                    } else if let link = WidgetLink(url: url) {
                        openWidgetLink(link)
                    }
                }
```

Extend the comment above the handler with one sentence: `An invite to Friends (`renoop://friends/add`) is handed to the Friends tab.`

- [ ] **Step 2: Open the Friends tab**

In `StrandiOS/App/RootTabView.swift`, directly before the comment that begins `// A cold-launch selection is already pending when this shell appears`, add:

```swift
        // Friends (fork feature): an invite opened by a link is answered on the Friends tab.
        .onReceive(FriendsStore.shared.$pendingInviteCode) { code in
            if code != nil { selectedTab = 3 }
        }
```

If the compiler cannot find `onReceive`'s publisher type, add `import Combine` to the file.

- [ ] **Step 3: Ask before adding**

In `Strand/Friends/FriendsView.swift`, add a state property beside `showFriends`:

```swift
    /// An invite code that arrived by a link and is being asked about.
    @State private var promptCode: String?
```

and two modifiers directly after `.sheet(isPresented: $showFriends) { FriendsManageSheet() }`:

```swift
        .alert("Add Friend", isPresented: Binding(get: { promptCode != nil }, set: { if !$0 { promptCode = nil } }),
               presenting: promptCode) { code in
            Button("Add") { Task { _ = await store.redeem(code) } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("You opened an invite. Add this person as a friend? You will see each other's days.")
        }
        .task(id: "\(store.isOn)|\(store.pendingInviteCode ?? "")") {
            // An invite that arrives while Friends is off waits here until it is on.
            guard store.isOn, let code = store.pendingInviteCode else { return }
            store.pendingInviteCode = nil
            promptCode = code
        }
```

A failure (a used or expired code) shows in the tab's error notice, as every other failure does.

- [ ] **Step 4: Add the string**

```bash
python3 /tmp/friends_strings.py <<'JSON'
[["You opened an invite. Add this person as a friend? You will see each other's days.", "Вы открыли приглашение. Добавить этого человека в друзья? Вы будете видеть дни друг друга."]]
JSON
```

- [ ] **Step 5: Build both targets**

Both build commands. Expected: `** BUILD SUCCEEDED **` twice.

- [ ] **Step 6: Commit**

```bash
git add StrandiOS/App/StrandiOSApp.swift StrandiOS/App/RootTabView.swift Strand/Friends/FriendsView.swift Strand/Resources/Localizable.xcstrings
git commit -m "feature: an invite link opens the Friends tab and asks before adding"
```

---

### Task 5: Walk the whole thing through on two simulators

No code. Two simulators stand for two phones, a launch argument stands for the strap (`--friends-strap`, DEBUG only, from plan 2), and plan 1's server runs on this Mac.

- [ ] **Step 1: Start the server and two simulators**

```bash
cd friends-server && rm -f /tmp/friends-dev.db* && FRIENDS_DB=/tmp/friends-dev.db FRIENDS_STRAP_PEPPER=0123456789abcdef0123456789abcdef .venv/bin/python server.py
```

In a second shell, with the iOS build from plan 2's build command:

```bash
APP=$(find build/dd-ios/Build/Products -name 'NOOP*.app' -maxdepth 2 | head -1)
BUNDLE=$(/usr/libexec/PlistBuddy -c 'Print CFBundleIdentifier' "$APP/Info.plist")
xcrun simctl list devices available | grep -E 'iPhone' | head -4
```

Pick two of the listed devices as `A` and `B` (their UDIDs), then:

```bash
for D in "$A" "$B"; do xcrun simctl boot "$D" 2>/dev/null; xcrun simctl install "$D" "$APP"; done
launch() { xcrun simctl launch "$1" "$BUNDLE" --demo-seed --demo-screen friends --friends-strap "$2" -friends.serverAddress http://127.0.0.1:8787; }
launch "$A" whoop-SIM0001
```

- [ ] **Step 2: Walk through, checking each line**

Take a screenshot at each numbered point (`xcrun simctl io <udid> screenshot /tmp/fN.png`) and look at it.

1. **A, Turn On.** The board with A's own card. Server log: `POST /v2/enroll 201`.
2. **B, same strap:** `launch "$B" whoop-SIM0001`, Turn On. "This Strap Has an Account" with two buttons.
3. **B, This Is My Account.** "Confirm on Your Other Phone" with a six-digit code and the day the strap alone will be enough.
4. **A, pull to refresh.** A notice "A new phone wants to join your account" with the same code, and a badge on the bar button. Review opens the sheet; its Requests section has Confirm and Decline.
5. **A, Confirm.** The request is gone. On B within a minute, or after relaunching B, the board shows A's name: B joined without sending its own.
6. **A, My Sharing, Phones.** Two rows, one marked "This Phone".
7. **A, My Sharing, What the Server Stores.** The account as JSON: two devices, `strapBound` true.
8. **B, My Sharing, Phones, Remove This Phone.** B is back on the welcome page. A's Phones page shows one row after a refresh.
9. **B as someone else:** `launch "$B" whoop-SIM0002`, Turn On. A new account.
10. **A, bar button, Invite a Friend.** A page with a QR, a code, Share. Note the code.
11. **B, bar button, Enter a Code,** type A's code, Add. "You and … are friends now." Both boards show two cards after a refresh. A's Invites Waiting no longer lists it.
12. **The link.** `xcrun simctl openurl "$A" "renoop://friends/add?c=AAAAA-AAAAA"`. A switches to the Friends tab and asks "Add Friend"; Add shows "This code is not valid any more." in the error notice.
13. **The invite page in a browser:** `curl -s http://127.0.0.1:8787/i/K7QM2-XRD4P | grep -c renoop://friends/add` prints `1`.
14. **A new strap on A:** `launch "$A" whoop-SIM0003`, pull to refresh. Server log: `PUT /v2/me/strap 200`. Then `launch "$B" whoop-SIM0001` on a freshly installed B would enrol at once, because that strap is free again; checking the log line is enough.
15. **Dark and light.** One screenshot of the board, the sheet and the invite page in each appearance (`xcrun simctl ui <udid> appearance dark|light`). The QR is black on white in both.

- [ ] **Step 3: Report**

Stop the server. Report which of the fifteen points were seen as written and attach the screenshots of any that were not. A point that could not be reached (a simulator without the tab, a missing demo screen) is reported as not checked, not as passed.

---

## Done when

- Both targets build; `FriendsRequestsTests` and the plan 2 test classes pass.
- The walk-through's fifteen points were seen, or the ones that were not are named.
