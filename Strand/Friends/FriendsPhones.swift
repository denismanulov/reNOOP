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
