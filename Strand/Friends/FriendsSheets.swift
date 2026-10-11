//  FriendsSheets.swift
//  NOOP · Friends — the sheet behind the bar's button, where Fitness keeps friends too, the page of
//  what the wearer shares, and the sheet that names the server before Friends is on. There is no account
//  form: Friends is turned on from the tab itself, the name and photo are the profile's, and nothing
//  here asks for a password.
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

/// The sheet behind the tab's bar button, laid out as Fitness's Sharing sheet: who the wearer shares
/// with in one card, led by the row that invites another, each person opening their page; and at the
/// foot the link to how the wearer's own data is handled. What waits for an answer leads the sheet, and
/// the field for a code sits under the people, since Fitness has neither.
struct FriendsManageSheet: View {
    @ObservedObject private var store = FriendsStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var shown: FriendsShownInvite?
    @State private var showSharing = false

    var body: some View {
        NavigationStack {
            Form {
                FriendsRequestsSection()
                FriendsAddSections(shown: $shown)
                Section {
                    Button { showSharing = true } label: {
                        Text("See how your data is managed…")
                            .font(StrandFont.pro(17))
                            .foregroundStyle(FriendsStyle.key)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                }
            }
            .settingsForm()
            .navigationTitle(Text("Friends"))
            #if os(iOS)
            // Fitness sets the sheet's cards 20 pt in from its edges.
            .contentMargins(.horizontal, 20, for: .scrollContent)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                // Fitness closes its sheet from the trailing side.
                #if os(iOS)
                ToolbarItem(placement: .topBarTrailing) { SheetCloseButton { dismiss() } }
                #else
                ToolbarItem(placement: .cancellationAction) { SheetCloseButton { dismiss() } }
                #endif
            }
            .navigationDestination(isPresented: Binding(get: { shown != nil }, set: { if !$0 { shown = nil } })) {
                if let shown { FriendsInvitePage(shown: shown) }
            }
            .navigationDestination(isPresented: $showSharing) {
                FriendsSharingPage(onAccountLeft: { dismiss() })
            }
            .task { await store.loadInvites() }
        }
        // Friends went off behind the sheet (this phone dropped off the account): nothing here is
        // about an account this phone is on any more, and the tab says why.
        .onChangeCompat(of: store.isOn) { on in
            if !on { dismiss() }
        }
    }
}

// MARK: - The server

/// The sheet behind the welcome page's bar button: which server Friends talks to. Empty is the fork's
/// own server. It is offered only while Friends is off, since each server keeps its own account.
struct FriendsServerSheet: View {
    @ObservedObject private var store = FriendsStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var address = ""
    /// Why the address typed was not taken, until it is edited.
    @State private var refusal: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Server Address", text: $address, prompt: Text(verbatim: FriendsServerAddress.standard))
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        .textInputAutocapitalization(.never)
                        #endif
                        .submitLabel(.done)
                        .onSubmit(save)
                } footer: {
                    if let refusal {
                        Text(verbatim: refusal).foregroundStyle(StrandPalette.settingsRed)
                    }
                }
            }
            .settingsForm()
            .navigationTitle(Text("Server"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { SheetCloseButton { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { SheetConfirmButton(action: save) }
            }
        }
        .onAppear {
            address = store.serverAddress == FriendsServerAddress.standard ? "" : store.serverAddress
        }
        .onChangeCompat(of: address) { _ in refusal = nil }
    }

    private func save() {
        switch store.setServerAddress(address) {
        case .done, .notOff:
            // Not off any more means the page behind has moved on, and says why itself.
            dismiss()
        case let .refused(problem):
            refusal = FriendsStore.message(forAddress: problem)
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
                FriendsHero(name: store.nameFriendsSee(fallback: profile.displayName), own: true,
                            imageData: sharePhoto ? profile.avatarImageData : nil)
                    .friendsHeroRow()
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
            }
            .disabled(onProbation)
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
            }
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
