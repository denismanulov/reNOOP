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
