//  FriendsSheets.swift
//  NOOP · Friends — the three sheets of the tab: creating the account, adding a friend, and what the
//  wearer shares. An account is a name and nothing else: there is no password to set, type or forget.

import SwiftUI
import StrandAnalytics
import StrandDesign

// MARK: - Account

/// Sign-up. One name, and the address of the server the wearer was given. The name is checked as it is
/// typed, so "taken" is said before anything is created.
struct FriendsSetupSheet: View {
    let suggestedName: String

    @ObservedObject private var store = FriendsStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var nick = ""
    @State private var address = FriendsStore.shared.serverAddress
    @State private var invite = ""
    @State private var inviteRequired = false
    @State private var nickFree: Bool?
    @State private var working = false

    private var nickValid: Bool { FriendsNick.isValid(nick) }
    private var addressValid: Bool { FriendsServerAddress.baseURL(address) != nil }
    private var canCreate: Bool { nickValid && addressValid && nickFree != false && !working && (!inviteRequired || !invite.isEmpty) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $nick, prompt: Text(verbatim: "denis"))
                        .disableAutocorrection(true)
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.asciiCapable)
                        #endif
                } header: {
                    Text("Your Name")
                } footer: {
                    nickFooter
                }
                Section {
                    TextField("Server", text: $address, prompt: Text(verbatim: "https://"))
                        .disableAutocorrection(true)
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        #endif
                    if inviteRequired {
                        TextField("Invite Code", text: $invite)
                            .disableAutocorrection(true)
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            #endif
                    }
                } header: {
                    Text("Server")
                } footer: {
                    Text("The address of the friends server you were given. reNOOP has no server of its own.")
                }
                Section {
                } footer: {
                    VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                        Text("There is no password. The account lives on this device: if you delete reNOOP's data or move to a new phone without it, the name cannot be recovered.")
                        if let error = store.errorText {
                            Text(error).foregroundStyle(StrandPalette.settingsRed)
                        }
                    }
                }
            }
            .navigationTitle(Text("New Account"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if working { ProgressView() } else { Button("Create", action: create).disabled(!canCreate) }
                }
            }
            .task(id: address) { await checkServer() }
            .task(id: "\(nick)|\(address)") { await checkNick() }
            .onAppear { store.errorText = nil }
        }
    }

    @ViewBuilder private var nickFooter: some View {
        if nick.isEmpty || !nickValid {
            Text("3 to 20 Latin letters, digits or underscores. Friends find you by this name.")
        } else if nickFree == false {
            Text("This name is taken.").foregroundStyle(StrandPalette.settingsRed)
        } else if nickFree == true {
            Text("This name is free.")
        } else {
            Text("Friends find you by this name.")
        }
    }

    private func checkServer() async {
        inviteRequired = false
        guard addressValid else { return }
        try? await Task.sleep(nanoseconds: 500_000_000)
        guard !Task.isCancelled else { return }
        if case let .success(info) = await store.serverInfo(address: address) { inviteRequired = info.inviteRequired }
    }

    private func checkNick() async {
        nickFree = nil
        guard nickValid, addressValid else { return }
        try? await Task.sleep(nanoseconds: 400_000_000)
        guard !Task.isCancelled else { return }
        let free = await store.isNickFree(nick, address: address)
        guard !Task.isCancelled else { return }
        nickFree = free
    }

    private func create() {
        working = true
        Task {
            let created = await store.signUp(address: address, nick: nick, name: suggestedName, invite: invite)
            working = false
            if created { dismiss() }
        }
    }
}

// MARK: - Adding a friend

/// Add a friend by their exact name. The server has no directory and no search by part of a name.
struct AddFriendSheet: View {
    @ObservedObject private var store = FriendsStore.shared
    @Environment(\.dismiss) private var dismiss

    @State private var nick = ""
    @State private var found: FriendProfile?
    @State private var message: String?
    @State private var working = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Friend's Name", text: $nick)
                        .disableAutocorrection(true)
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.asciiCapable)
                        #endif
                        .onSubmit(find)
                    Button("Find", action: find).disabled(!FriendsNick.isValid(nick) || working)
                } footer: {
                    if let message {
                        Text(message)
                    } else {
                        Text("Type the whole name, exactly as your friend chose it.")
                    }
                }
                if let found {
                    Section {
                        HStack(spacing: 12) {
                            FriendAvatar(name: found.name, size: 40)
                            FriendNameStack(person: found)
                            Spacer(minLength: 8)
                            action(found)
                        }
                    }
                }
            }
            .navigationTitle(Text("Add Friend"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }

    @ViewBuilder private func action(_ person: FriendProfile) -> some View {
        switch person.relation ?? FriendRelation.none {
        case .me: Text("You").foregroundStyle(StrandPalette.textSecondary)
        case .friend: Text("Friends").foregroundStyle(StrandPalette.textSecondary)
        case .outgoing: Text("Requested").foregroundStyle(StrandPalette.textSecondary)
        case .incoming:
            Button("Accept") { request(person) }.buttonStyle(.borderedProminent)
        case .none:
            Button("Add") { request(person) }.buttonStyle(.borderedProminent)
        }
    }

    private func find() {
        guard FriendsNick.isValid(nick), !working else { return }
        working = true
        message = nil
        found = nil
        Task {
            switch await store.lookup(nick) {
            case let .success(person): found = person
            case let .failure(error): message = FriendsStore.message(for: error)
            }
            working = false
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

struct FriendsSharingSheet: View {
    @ObservedObject private var store = FriendsStore.shared
    @EnvironmentObject private var repo: Repository
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var share = FriendsShare()
    @State private var confirmDelete = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $name)
                        .onSubmit { Task { await store.rename(name) } }
                } header: {
                    Text("Shown to Friends")
                } footer: {
                    Text(verbatim: "@" + store.nick)
                }
                Section {
                    Toggle("Scores", isOn: $share.scores)
                    Toggle("Sleep", isOn: $share.sleep)
                    Toggle("Workouts", isOn: $share.workouts)
                    Toggle("Heart Rate", isOn: $share.hr)
                } header: {
                    Text("Friends Can See")
                } footer: {
                    Text("What is switched off is not sent. Switching something off also erases it from the days already on the server.")
                }
                Section {
                    Button("Delete Account", role: .destructive) { confirmDelete = true }
                } footer: {
                    Text("Removes your name, every day you uploaded and every friendship from the server. There is no password, so an account that is deleted or lost cannot be signed in to again.")
                }
            }
            .navigationTitle(Text("My Sharing"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done", action: done) }
            }
            .confirmationDialog("Delete Account", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete Account", role: .destructive) {
                    Task { if await store.deleteAccount() { dismiss() } }
                }
            } message: {
                Text("This cannot be undone.")
            }
            .onAppear {
                name = store.me?.name ?? ""
                share = store.share
            }
            .onChangeCompat(of: share) { changed in
                Task { await store.setShare(changed, repo: repo) }
            }
        }
    }

    private func done() {
        Task { await store.rename(name) }
        dismiss()
    }
}
