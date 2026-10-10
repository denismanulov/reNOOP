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
    /// The phone a Remove is being confirmed for.
    @State private var removing: FriendsDevice?
    /// Whether the list could not be loaded. Then there is no list to show, only the reason.
    @State private var loadFailed = false
    /// Why the load, a Remove or a Keep failed. Kept here, not read from the store, so what the tab left
    /// in the store's text is not mistaken for something that happened on this page.
    @State private var failure: String?
    /// Why this phone could not leave.
    @State private var leaveFailure: String?

    private var phones: [FriendsDevice] { loadFailed ? [] : store.devices ?? [] }
    /// The server's clock, which the list's timestamps are counted from.
    private var serverDate: Date { Date(timeIntervalSince1970: TimeInterval(store.serverNow())) }
    /// A phone that joined without confirmation removes nobody but itself.
    private var locked: Bool { store.probationUntil != nil }
    /// This phone may leave while the account stays on another confirmed phone, or when it is itself
    /// unconfirmed. The account's only confirmed phone cannot: there would be no way back in.
    private var canLeave: Bool { locked || phones.contains { !$0.current && $0.probationUntil == nil } }

    var body: some View {
        Form {
            Section {
                if store.devices == nil, !loadFailed {
                    ProgressView().frame(maxWidth: .infinity)
                }
                ForEach(phones) { phone in row(phone) }
            } footer: {
                Text("Each phone holds its own key. Removing one stops it reading and uploading at once.")
                if let failure {
                    Text(verbatim: failure).foregroundStyle(StrandPalette.settingsRed)
                }
            }
            if canLeave {
                Section {
                    // The dialog hangs off the button that asks for it, where iOS 26 points it.
                    Button("Remove This Phone", role: .destructive) { confirmLeave = true }
                        .confirmationDialog("Remove This Phone", isPresented: $confirmLeave, titleVisibility: .visible) {
                            Button("Remove This Phone", role: .destructive) { leave() }
                        } message: {
                            Text("Friends turns off on this phone. The account stays on your other phone.")
                        }
                } footer: {
                    if let leaveFailure {
                        Text(verbatim: leaveFailure).foregroundStyle(StrandPalette.settingsRed)
                    }
                }
            }
        }
        .settingsForm()
        .navigationTitle(Text("Phones"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task {
            store.errorText = nil
            failure = nil
            leaveFailure = nil
            loadFailed = false
            if await store.loadDevices() {
                // Whether this phone is still unconfirmed is the feed's to say: asked again with the
                // list, so the row's label and what the page offers come from the same moment.
                await store.refresh()
                return
            }
            // A page left before the answer came leaves no text: nothing failed.
            guard let text = store.errorText else { return }
            loadFailed = true
            failure = text
        }
    }

    /// An action that failed leaves the store's text as the reason. One that went through leaves none,
    /// even when the reload after it failed: the tab reports that, and the phone was dealt with.
    private func run(_ action: @escaping () async -> Bool) {
        failure = nil
        store.errorText = nil
        Task {
            let went = await action()
            failure = went ? nil : store.errorText
        }
    }

    private func leave() {
        leaveFailure = nil
        store.errorText = nil
        Task {
            guard await store.removeThisPhone() else {
                leaveFailure = store.errorText
                return
            }
            onAccountLeft()
        }
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
                Text(verbatim: FriendsRequestText.seen(phone, now: serverDate))
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
                    Button { run { await store.trustDevice(phone.id) } } label: {
                        Text("Keep").font(StrandFont.pro(15, weight: .semibold)).lineLimit(1)
                    }
                    .friendsCapsuleButton(prominent: true)
                }
                // The dialog hangs off the button that asks for it, where iOS 26 points it.
                Button { removing = phone } label: {
                    Text("Remove").font(StrandFont.pro(15, weight: .semibold)).lineLimit(1)
                }
                .friendsCapsuleButton(prominent: false)
                .confirmationDialog(
                    Text("Remove \(FriendsRequestText.device(phone.platform))"),
                    isPresented: Binding(get: { removing?.id == phone.id }, set: { if !$0 { removing = nil } }),
                    titleVisibility: .visible
                ) {
                    Button("Remove", role: .destructive) { run { await store.removeDevice(phone.id) } }
                } message: {
                    Text("It stops reading and uploading at once. It has to ask to join again.")
                }
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
    /// Why the export could not be fetched.
    @State private var failure: String?

    var body: some View {
        ScrollView {
            Group {
                if let text {
                    Text(verbatim: text)
                        .font(StrandFont.mono)
                        .foregroundStyle(StrandPalette.textPrimary)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else if loaded {
                    Text(verbatim: failure ?? "")
                        .font(StrandFont.pro(15))
                        .foregroundStyle(StrandPalette.settingsRed)
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
            store.errorText = nil
            failure = nil
            text = await store.exportText()
            if text == nil { failure = store.errorText }
            loaded = true
        }
    }
}
