//  FriendsSheets.swift
//  NOOP · Friends — the tab's two sheets: creating the account, and the one behind the bar's button,
//  where Fitness keeps friends too: requests waiting for an answer, adding a friend by name, and the
//  page of what the wearer shares. An account is a name and a password; the server has no reset, so
//  the sheets say so where the password is chosen.
//
//  Each closes with the bar's ✕ and ✓ like every other sheet in the app.

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

// MARK: - Account

/// Sign-up and sign-in, one sheet. A name, a password, and the server the account lives on, which is
/// the fork's own unless the wearer names another. When creating, the name is checked as it is typed, so
/// "taken" is said before anything is made; that check, and the question of whether the server wants an
/// invite code asked beside it, are the only requests made before an account exists. How a wearer signs
/// in is still under discussion (2026-10-09), so this sheet stays as thin as the server's contract.
struct FriendsSetupSheet: View {
    enum Mode { case create, signIn }

    let suggestedName: String

    @ObservedObject private var store = FriendsStore.shared
    @EnvironmentObject private var profile: ProfileStore
    @Environment(\.dismiss) private var dismiss

    @State private var mode = Mode.create
    @State private var nick = ""
    @State private var password = ""
    @State private var address = FriendsStore.shared.serverAddress
    @State private var invite = ""
    @State private var inviteRequired = false
    /// The address `inviteRequired` was last asked of, so one server is asked once.
    @State private var inviteKnownFor: String?
    @State private var nickFree: Bool?
    @State private var working = false

    private var nickValid: Bool { FriendsNick.isValid(nick) }
    private var addressValid: Bool { FriendsServerAddress.baseURL(address) != nil }
    private var canSubmit: Bool {
        guard nickValid, addressValid, !working else { return false }
        switch mode {
        case .create: return FriendsPassword.isValid(password) && nickFree != false && (!inviteRequired || !invite.isEmpty)
        case .signIn: return !password.isEmpty
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Account", selection: $mode) {
                        Text("New Account").tag(Mode.create)
                        Text("Sign In").tag(Mode.signIn)
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }
                Section {
                    // The account as friends will see it: the name it starts with and the nickname typed so far.
                    FriendsHero(name: suggestedName, nick: nickValid ? FriendsNick.normalized(nick) : "",
                                own: true, imageData: profile.avatarImageData)
                        .friendsHeroRow()
                }
                Section {
                    HStack(spacing: 2) {
                        Text(verbatim: "@").foregroundStyle(StrandPalette.textSecondary)
                        TextField("Name", text: $nick, prompt: Text(verbatim: "denis"))
                            .disableAutocorrection(true)
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            .keyboardType(.asciiCapable)
                            #endif
                    }
                } header: {
                    Text("Your Name")
                } footer: {
                    nickFooter
                }
                Section {
                    SecureField("Password", text: $password)
                        #if os(iOS)
                        .textContentType(mode == .create ? .newPassword : .password)
                        #endif
                } header: {
                    Text("Password")
                } footer: {
                    if mode == .create {
                        Text("8 to 128 characters. There is no reset: if you forget it, the account cannot be recovered.")
                    }
                }
                Section {
                    TextField("Server", text: $address, prompt: Text(verbatim: "https://"))
                        .disableAutocorrection(true)
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        #endif
                    if inviteRequired && mode == .create {
                        TextField("Invite Code", text: $invite)
                            .disableAutocorrection(true)
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            #endif
                    }
                } header: {
                    Text("Server")
                } footer: {
                    VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                        Text("Your account lives on this server. Change it only if you or a friend run your own.")
                        if let error = store.errorText {
                            Text(error).foregroundStyle(StrandPalette.settingsRed)
                        }
                    }
                }
            }
            .settingsForm()
            .navigationTitle(Text(mode == .create ? "New Account" : "Sign In"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { SheetCloseButton { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if working {
                        ProgressView()
                    } else {
                        SheetConfirmButton(tint: StrandPalette.accent, action: submit)
                            .disabled(!canSubmit)
                            .accessibilityLabel(Text(mode == .create ? "Create" : "Sign In"))
                    }
                }
            }
            .task(id: "\(nick)|\(address)|\(mode == .create)") { await checkNick() }
            .onChangeCompat(of: mode) { _ in store.errorText = nil }
            .onAppear { store.errorText = nil }
        }
    }

    @ViewBuilder private var nickFooter: some View {
        if mode == .signIn {
            EmptyView()
        } else if nick.isEmpty || !nickValid {
            Text("3 to 20 Latin letters, digits or underscores. Friends find you by this name.")
        } else if nickFree == false {
            Text("This name is taken.").foregroundStyle(StrandPalette.settingsRed)
        } else if nickFree == true {
            Text("This name is free.")
        } else {
            Text("Friends find you by this name.")
        }
    }

    /// Asks whether the typed name is free, a moment after the typing stops, and with the first such
    /// question what the server asks of a sign-up. Nothing is asked while signing in, or before there is
    /// a whole name to ask about: opening the sheet sends nothing.
    private func checkNick() async {
        nickFree = nil
        if inviteKnownFor != FriendsServerAddress.normalized(address) { inviteRequired = false }
        guard mode == .create, nickValid, let server = FriendsServerAddress.normalized(address) else { return }
        try? await Task.sleep(nanoseconds: 400_000_000)
        guard !Task.isCancelled else { return }
        async let free = store.isNickFree(nick, address: server)
        if inviteKnownFor != server, case let .success(info) = await store.serverInfo(address: server) {
            guard !Task.isCancelled else { return }
            inviteRequired = info.inviteRequired
            inviteKnownFor = server
        }
        let answer = await free
        guard !Task.isCancelled else { return }
        nickFree = answer
    }

    private func submit() {
        working = true
        Task {
            let entered: Bool
            switch mode {
            case .create:
                entered = await store.signUp(address: address, nick: nick, password: password,
                                             name: suggestedName, invite: invite)
            case .signIn:
                entered = await store.signIn(address: address, nick: nick, password: password)
            }
            working = false
            if entered { dismiss() }
        }
    }
}

// MARK: - Friends

/// The sheet behind the tab's bar button. Fitness answers invitations and invites from the same place,
/// so this one holds the requests that wait for an answer, the field that adds a friend, and the way to
/// the wearer's own sharing.
///
/// A friend is added by their exact name. The server has no directory and no search by part of a name,
/// so the sheet looks the name up once it is a whole one and shows the person it belongs to.
struct FriendsManageSheet: View {
    @ObservedObject private var store = FriendsStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var nick = ""
    @State private var found: FriendProfile?
    @State private var message: String?
    @State private var working = false
    @FocusState private var focused: Bool

    private var incoming: [FriendProfile] { store.requests?.incoming ?? [] }

    var body: some View {
        NavigationStack {
            Form {
                if !incoming.isEmpty {
                    Section {
                        ForEach(incoming) { person in
                            FriendPersonRow(person: person) { answer(person) }
                                .padding(.vertical, -4)
                        }
                    } header: {
                        Text("Requests")
                    }
                }
                Section {
                    HStack(spacing: 2) {
                        Text(verbatim: "@").foregroundStyle(StrandPalette.textSecondary)
                        TextField("Friend's Name", text: $nick)
                            .disableAutocorrection(true)
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            .keyboardType(.asciiCapable)
                            .submitLabel(.search)
                            #endif
                            .focused($focused)
                        if working { ProgressView() }
                    }
                } header: {
                    Text("Add Friend")
                } footer: {
                    if let message {
                        Text(message)
                    } else if found == nil {
                        Text("Type the whole name, exactly as your friend chose it.")
                    }
                }
                if let found {
                    Section {
                        FriendPersonRow(person: found) { action(found) }
                            .padding(.vertical, -4)
                    }
                }
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
            .task(id: nick) { await find() }
            // With requests to answer the keyboard would cover them, so the field waits to be tapped.
            .onAppear { focused = incoming.isEmpty }
        }
    }

    /// The two answers to a request: the one the row asks for, and ✕ to turn it down.
    private func answer(_ person: FriendProfile) -> some View {
        HStack(spacing: 8) {
            Button { Task { await store.accept(person.nick) } } label: {
                Text("Accept").font(StrandFont.pro(15, weight: .semibold)).lineLimit(1)
            }
            .friendsCapsuleButton(prominent: true)
            Button { Task { await store.dropRequest(person.nick) } } label: {
                Image(systemName: "xmark")
                    .font(StrandFont.pro(11, weight: .bold))
                    .foregroundStyle(StrandPalette.textSecondary)
                    .frame(width: 32, height: 32)
                    .background(StrandPalette.hairline, in: Circle())
                    // A 44 pt target around the 32 pt circle.
                    .contentShape(Circle().inset(by: -6))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Decline"))
        }
        .fixedSize()
    }

    @ViewBuilder private func action(_ person: FriendProfile) -> some View {
        switch person.relation ?? FriendRelation.none {
        case .me: status("You")
        case .friend: status("Friends")
        case .outgoing: status("Requested")
        case .incoming:
            Button { request(person) } label: { Text("Accept").font(StrandFont.pro(15, weight: .semibold)) }
                .friendsCapsuleButton(prominent: true)
        case .none:
            Button { request(person) } label: { Text("Add").font(StrandFont.pro(15, weight: .semibold)) }
                .friendsCapsuleButton(prominent: true)
        }
    }

    /// How the person already stands to the account, said on one line where the button would be.
    private func status(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(StrandFont.pro(15))
            .foregroundStyle(StrandPalette.textSecondary)
            .lineLimit(1)
            .fixedSize()
    }

    /// Looks the typed name up a moment after the typing stops. A name that is not yet a whole one asks
    /// nothing.
    private func find() async {
        found = nil
        message = nil
        working = false
        guard FriendsNick.isValid(nick) else { return }
        try? await Task.sleep(nanoseconds: 450_000_000)
        guard !Task.isCancelled else { return }
        working = true
        let answer = await store.lookup(nick)
        guard !Task.isCancelled else { return }
        working = false
        switch answer {
        case let .success(person): found = person
        case let .failure(error): message = FriendsStore.message(for: error)
        }
    }

    /// Sending a request to someone who already asked is an acceptance, so one call serves both.
    private func request(_ person: FriendProfile) {
        Task {
            if let updated = await store.sendRequest(to: person.nick) { found = updated }
            else { message = store.errorText }
        }
    }
}

// MARK: - What the wearer shares

/// The wearer's own account, pushed from the friends sheet: the name friends see, what they can see,
/// and deleting the account. The name is saved on the way out.
struct FriendsSharingPage: View {
    /// The account is gone from this phone (signed out or deleted): the sheet that showed it has
    /// nothing left to show.
    let onAccountLeft: () -> Void

    @ObservedObject private var store = FriendsStore.shared
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var profile: ProfileStore

    @State private var name = ""
    @State private var share = FriendsShare()
    @State private var confirmDelete = false
    @State private var deletePassword = ""
    @State private var left = false
    @State private var pictureWorking = false

    var body: some View {
        Form {
            Section {
                FriendsHero(name: name, nick: store.nick, own: true, imageData: profile.avatarImageData)
                    .friendsHeroRow()
            }
            Section {
                HStack {
                    Text("Name").foregroundStyle(StrandPalette.textPrimary)
                    TextField("Name", text: $name)
                        .multilineTextAlignment(.trailing)
                        .foregroundStyle(StrandPalette.textSecondary)
                        .onSubmit { Task { await store.rename(name) } }
                }
            }
            Section {
                // The picture is the profile photo the app already has; the tab has no picker of its own.
                Button("Use My Profile Photo") {
                    pictureWorking = true
                    Task {
                        _ = await store.uploadProfilePhoto(profile.avatarImageData)
                        pictureWorking = false
                    }
                }
                .disabled(profile.avatarImageData == nil || pictureWorking)
                if (store.me?.avatarRev ?? 0) > 0 {
                    Button("Remove Picture", role: .destructive) {
                        pictureWorking = true
                        Task {
                            _ = await store.removePicture()
                            pictureWorking = false
                        }
                    }
                    .disabled(pictureWorking)
                }
            } header: {
                Text("Picture")
            } footer: {
                if profile.avatarImageData == nil {
                    Text("To have a picture here, first set a profile photo in Settings.")
                } else if (store.me?.avatarRev ?? 0) > 0 {
                    Text("Friends see the picture you sent. Send it again after changing your profile photo.")
                } else {
                    Text("Your photo stays on this device until you send it. Friends see your initials.")
                }
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
            } header: {
                Text("Friends Can See")
            } footer: {
                Text("Only what is switched on goes to the server, and only accepted friends see it. Switching something off also erases it from the server.")
            }
            Section {
                LabeledContent {
                    Text(verbatim: FriendsServerAddress.baseURL(store.serverAddress)?.host ?? store.serverAddress)
                } label: {
                    Text("Server")
                }
                NavigationLink {
                    FriendsPasswordPage()
                } label: {
                    SettingsRowLabel(title: "Change Password", icon: "key.fill", color: StrandPalette.settingsBlue)
                }
                Button("Sign Out") {
                    Task {
                        await store.signOut()
                        left = true
                        onAccountLeft()
                    }
                }
            } footer: {
                VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                    if let sent = store.lastUploadAt {
                        Text(verbatim: String(localized: "Last sent") + " " + FriendsFormat.ago(sent))
                    } else {
                        Text("Nothing sent yet.")
                    }
                    Text("Signing out keeps the account, and data stops going to the server from this device. Your name and password sign you back in.")
                }
            }
            Section {
                // The alert hangs off the button that asks for it, where iOS 26 points it.
                Button("Delete Account", role: .destructive) {
                    deletePassword = ""
                    store.errorText = nil
                    confirmDelete = true
                }
                .alert("Delete Account", isPresented: $confirmDelete) {
                    SecureField("Password", text: $deletePassword)
                    Button("Delete Account", role: .destructive) {
                        let password = deletePassword
                        deletePassword = ""
                        Task {
                            guard await store.deleteAccount(password: password) else { return }
                            left = true
                            onAccountLeft()
                        }
                    }
                    .disabled(deletePassword.isEmpty)
                    Button("Cancel", role: .cancel) { deletePassword = "" }
                } message: {
                    Text("Removes your name, every day you uploaded and every friendship from the server. Enter your password to confirm.")
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
            name = store.me?.name ?? ""
            share = store.share
        }
        .onChangeCompat(of: share) { changed in
            Task { await store.setShare(changed, repo: repo, profile: profile) }
        }
        // `rename` sends nothing for a name that is empty or unchanged; an account that is gone has none to save.
        .onDisappear {
            guard !left else { return }
            Task { await store.rename(name) }
        }
    }
}

// MARK: - Password

/// Pushed from the sharing page. The server wants the current password again and ends every other
/// session of the account when it takes the new one; this phone is handed a fresh token.
struct FriendsPasswordPage: View {
    @ObservedObject private var store = FriendsStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var old = ""
    @State private var new = ""
    @State private var working = false

    private var canSave: Bool { !old.isEmpty && FriendsPassword.isValid(new) && new != old && !working }

    var body: some View {
        Form {
            Section {
                SecureField("Current Password", text: $old)
                    #if os(iOS)
                    .textContentType(.password)
                    #endif
                SecureField("New Password", text: $new)
                    #if os(iOS)
                    .textContentType(.newPassword)
                    #endif
            } footer: {
                VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                    Text("8 to 128 characters. Saving signs the account out on every other phone.")
                    if let error = store.errorText {
                        Text(error).foregroundStyle(StrandPalette.settingsRed)
                    }
                }
            }
        }
        .settingsForm()
        .navigationTitle(Text("Change Password"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if working {
                    ProgressView()
                } else {
                    SheetConfirmButton(tint: StrandPalette.accent) {
                        working = true
                        Task {
                            let changed = await store.changePassword(old: old, new: new)
                            working = false
                            if changed { dismiss() }
                        }
                    }
                    .disabled(!canSave)
                    .accessibilityLabel(Text("Save"))
                }
            }
        }
        .onAppear { store.errorText = nil }
    }
}
