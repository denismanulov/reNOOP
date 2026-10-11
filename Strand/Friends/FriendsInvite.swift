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
                // The page's one action, as the tab's own pages end on theirs: a capsule in its key colour.
                ShareLink(item: shareText) { Text("Share Invite") }
                    .buttonStyle(FriendsKeyButtonStyle(large: true))
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }
        }
        .settingsForm()
        .navigationTitle(Text("Invite"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }
}

/// The friends sheet's own sections, in the order Fitness's Sharing sheet keeps: who the wearer shares
/// with, led by the row that invites one more, then (what Fitness has no need of) the field for a code
/// that was handed over and the invites still waiting to be used.
struct FriendsAddSections: View {
    /// Set to push an invite's page.
    @Binding var shown: FriendsShownInvite?

    @ObservedObject private var store = FriendsStore.shared
    @State private var code = ""
    @State private var working = false
    /// What the last Add came to: who was added, or why not.
    @State private var message: String?
    /// Why an invite could not be made.
    @State private var inviteFailure: String?
    /// Why the last Revoke did not go through.
    @State private var revokeFailure: String?

    /// A phone that joined without confirmation leaves the friend list alone.
    private var locked: Bool { store.probationUntil != nil }
    /// Add is offered for anything typed: what is not a code is answered with the reason under the field.
    private var nothingTyped: Bool { code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    private var friends: [FriendProfile] {
        (store.feed?.friends ?? []).sorted { a, b in
            let order = a.name.localizedCaseInsensitiveCompare(b.name)
            return order != .orderedSame ? order == .orderedAscending : a.id < b.id
        }
    }

    var body: some View {
        Section {
            Button(action: invite) {
                HStack {
                    Text("Invite a Friend")
                    Spacer(minLength: 8)
                    if working {
                        ProgressView()
                    } else {
                        Image(systemName: "plus.circle.fill")
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(FriendsStyle.onKey, FriendsStyle.key)
                            .font(StrandFont.pro(23))
                            .padding(.trailing, 3)
                            .accessibilityHidden(true)
                    }
                }
                .foregroundStyle(FriendsStyle.key)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(working || locked)
            ForEach(friends) { friend in
                NavigationLink {
                    FriendDetailView(personID: friend.id)
                } label: {
                    HStack(spacing: 15) {
                        FriendAvatar(person: friend, size: 32)
                        Text(verbatim: friend.name)
                            .font(StrandFont.pro(17))
                            .foregroundStyle(StrandPalette.textPrimary)
                            .lineLimit(1)
                    }
                }
                // A person's row is as tall as the row above it, and the rule under it starts at their
                // picture, as Fitness draws both.
                .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }
            }
        } header: {
            FriendsSheetHeader(title: "Sharing With")
        } footer: {
            if let inviteFailure {
                Text(verbatim: inviteFailure).foregroundStyle(StrandPalette.settingsRed)
            }
        }
        Section {
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
                    Button(action: redeem) { Text("Add") }
                        .friendsCapsuleButton(prominent: true)
                        .disabled(nothingTyped || locked)
                }
            }
        } footer: {
            if let message {
                Text(verbatim: message)
            }
        }
        if let invites = store.invites, !invites.isEmpty {
            Section {
                ForEach(invites) { invite in waiting(invite) }
            } header: {
                FriendsSheetHeader(title: "Invites Waiting")
            } footer: {
                if let revokeFailure {
                    Text(verbatim: revokeFailure).foregroundStyle(StrandPalette.settingsRed)
                }
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
            Button { revoke(invite) } label: { Text("Revoke") }
                .friendsCapsuleButton(prominent: false)
            .disabled(working || locked)
        }
    }

    private static func day(_ ts: Int) -> String {
        Date(timeIntervalSince1970: TimeInterval(ts))
            .formatted(.dateTime.day().month(.wide).locale(AppLanguage.activeLocale))
    }

    private func invite() {
        working = true
        inviteFailure = nil
        Task {
            if let made = await store.createInvite(), let code = made.code {
                shown = FriendsShownInvite(invite: made, code: code)
            } else {
                inviteFailure = store.errorText
            }
            working = false
        }
    }

    /// A revoke that failed leaves the store's text as the reason; the invite stays in the list.
    private func revoke(_ invite: FriendsInvite) {
        guard !working else { return }
        working = true
        revokeFailure = nil
        store.errorText = nil
        Task {
            let went = await store.revokeInvite(invite.id)
            revokeFailure = went ? nil : store.errorText
            working = false
        }
    }

    private func redeem() {
        guard !working, !nothingTyped else { return }
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
