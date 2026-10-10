//  FriendsSheets.swift
//  NOOP · Friends — the sheet behind the bar's button, where Fitness keeps friends too, and the page of
//  what the wearer shares. There is no account form: Friends is turned on from the tab itself, the name
//  and photo are the profile's, and nothing here asks for a password.
//
//  The sheet closes with the bar's ✕ like every other sheet in the app.

import SwiftUI
import StrandAnalytics
import StrandDesign

private extension View {
    /// The hero's row in a form: no plate behind it and no inset, so it sits on the sheet itself.
    func friendsHeroRow() -> some View {
        self
            .frame(maxWidth: .infinity)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
    }
}

// MARK: - Friends

/// The sheet behind the tab's bar button. Fitness invites and answers invitations from one place, so
/// this is where a friend is invited, where a code is entered, and the way to the wearer's own sharing.
struct FriendsManageSheet: View {
    @ObservedObject private var store = FriendsStore.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
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
        }
    }
}

// MARK: - What the wearer shares

/// The wearer's own account, pushed from the friends sheet: how friends see them, what friends can
/// see, and deleting the account.
struct FriendsSharingPage: View {
    /// The account is gone from this phone: the sheet that showed it has nothing left to show.
    let onAccountLeft: () -> Void

    @ObservedObject private var store = FriendsStore.shared
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var profile: ProfileStore

    @State private var share = FriendsShare()
    @State private var sharePhoto = true
    @State private var confirmDelete = false

    /// A phone that joined without confirmation changes nothing until it is confirmed.
    private var onProbation: Bool { store.probationUntil != nil }

    var body: some View {
        Form {
            Section {
                FriendsHero(name: profile.displayName, own: true,
                            imageData: sharePhoto ? profile.avatarImageData : nil)
                    .friendsHeroRow()
            } footer: {
                Text("Your name and photo are the ones in your profile. Change them in Settings.")
            }
            Section {
                Toggle(isOn: $share.scores) {
                    SettingsRowLabel(title: "Scores", icon: "target", color: StrandPalette.settingsPink)
                }
                Toggle(isOn: $share.sleep) {
                    SettingsRowLabel(title: "Sleep", icon: "bed.double.fill", color: StrandPalette.settingsIndigo)
                }
                Toggle(isOn: $share.workouts) {
                    SettingsRowLabel(title: "Workouts", icon: "figure.run", color: StrandPalette.settingsGreen)
                }
                Toggle(isOn: $share.hr) {
                    SettingsRowLabel(title: "Heart Rate", icon: "heart.fill", color: StrandPalette.settingsRed)
                }
                Toggle(isOn: $sharePhoto) {
                    SettingsRowLabel(title: "Photo", icon: "person.crop.square.fill", color: StrandPalette.settingsOrange)
                }
            } header: {
                Text("Friends Can See")
            } footer: {
                Text("Only what is switched on goes to the server, and only friends see it. Switching something off also erases it from the server.")
            }
            .disabled(onProbation)
            Section {
                LabeledContent {
                    Text(verbatim: FriendsServerAddress.baseURL(store.serverAddress)?.host ?? store.serverAddress)
                } label: {
                    Text("Server")
                }
            } footer: {
                if let sent = store.lastUploadAt {
                    Text(verbatim: String(localized: "Last sent") + " " + FriendsFormat.ago(sent))
                } else {
                    Text("Nothing sent yet.")
                }
            }
            Section {
                // The dialog hangs off the button that asks for it, where iOS 26 points it.
                Button("Delete Account", role: .destructive) {
                    store.errorText = nil
                    confirmDelete = true
                }
                .disabled(onProbation)
                .confirmationDialog("Delete Account", isPresented: $confirmDelete, titleVisibility: .visible) {
                    Button("Delete Account", role: .destructive) {
                        Task {
                            guard await store.deleteAccount() else { return }
                            onAccountLeft()
                        }
                    }
                } message: {
                    Text("Removes your name, your picture, every day you uploaded and every friendship from the server, and turns Friends off on this phone. This cannot be undone.")
                }
            } footer: {
                if let error = store.errorText {
                    Text(error).foregroundStyle(StrandPalette.settingsRed)
                }
            }
        }
        .settingsForm()
        .navigationTitle(Text("My Sharing"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear {
            share = store.share
            sharePhoto = store.sharePhoto
        }
        .onChangeCompat(of: share) { changed in
            Task { await store.setShare(changed, repo: repo, profile: profile) }
        }
        .onChangeCompat(of: sharePhoto) { on in
            Task { await store.setSharePhoto(on, repo: repo, profile: profile) }
        }
    }
}
