//  FriendsView.swift
//  NOOP · Friends — the tab, laid out as the Fitness app's Sharing tab in iOS 26: the large title with
//  one button in the bar, Highlights (what friends did lately) paging side by side, then everyone's
//  rings under a heading that carries the sort menu and the day. Inviting a friend and whatever waits
//  for an answer are behind the bar's button, badged with their count, where Fitness keeps its
//  invitations.
//
//  The metrics are Fitness's, measured from the Sharing screenshot in Apple's iPhone User Guide
//  ("Share your activity in Fitness", iOS 26). Until Friends is turned on the tab is Fitness's
//  "Share Activity" page, and nothing is sent anywhere.

import SwiftUI
import StrandAnalytics
import StrandDesign

struct FriendsView: View {
    @ObservedObject private var store = FriendsStore.shared
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var profile: ProfileStore
    @Environment(\.scrollToTopSignal) private var scrollToTopSignal
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue
    @AppStorage("friends.sort") private var sortRaw = FriendsSort.name.rawValue

    @State private var showFriends = false
    /// An invite code that arrived by a link and is being asked about.
    @State private var promptCode: String?
    @State private var showServer = false

    private var sort: Binding<FriendsSort> {
        Binding(get: { FriendsSort(rawValue: sortRaw) ?? .name }, set: { sortRaw = $0.rawValue })
    }
    private var effortScale: EffortScale { UnitPrefs.resolveEffortScale(effortScaleRaw) }

    private static let topAnchorID = "friends.top"

    var body: some View {
        Group {
            if store.isOn { board } else { FriendsWelcome() }
        }
        .background(StrandPalette.summaryCanvas.ignoresSafeArea())
        .navigationTitle(Text("Friends"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(store.isOn ? .large : .inline)
        #endif
        .toolbar { toolbar }
        .sheet(isPresented: $showFriends) { FriendsManageSheet() }
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
        .sheet(isPresented: $showServer) { FriendsServerSheet() }
        .task(id: store.phase.stored) {
            // Opening the tab sends the wearer's own day first, so their card is never the stale one.
            // Coming back within a minute of a good answer shows that answer and asks nothing. Before
            // Friends is on, the same call moves the turning-on along.
            await store.sync(repo: repo, profile: profile, force: false)
        }
    }

    /// Fitness's one bar button: it opens the friends sheet, and counts what waits there for an answer.
    /// The page before Friends is on has no title, as Fitness's has none, and while Friends is off one
    /// button: which server it will talk to, which is chosen before turning on and not after.
    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        if store.isOn {
            ToolbarItem(placement: .primaryAction) { friendsButton }
        } else {
            ToolbarItem(placement: .principal) {
                Color.clear.frame(width: 1, height: 1).accessibilityHidden(true)
            }
            if store.phase == .off {
                ToolbarItem(placement: .primaryAction) { serverButton }
            }
        }
    }

    private var serverButton: some View {
        Button { showServer = true } label: { Image(systemName: "server.rack") }
            .barGlyph()
            .disabled(store.loading)
            .accessibilityLabel(Text("Server"))
    }

    private var waiting: Int { store.claims.count + store.unconfirmed.count }

    /// iOS 26 draws a bar button's badge itself; before it the glyph carries a dot instead.
    private var badgesBarButtons: Bool {
        if #available(iOS 26.0, macOS 26.0, *) { return true }
        return false
    }

    private var friendsButton: some View {
        Button { showFriends = true } label: {
            Image(systemName: "person.fill.badge.plus")
                .overlay(alignment: .topTrailing) {
                    if waiting > 0, !badgesBarButtons {
                        Circle().fill(StrandPalette.settingsRed).frame(width: 8, height: 8).offset(x: 4, y: -4)
                    }
                }
        }
        .badge(waiting)
        .barGlyph()
        .accessibilityLabel(Text("Add Friend"))
        .accessibilityValue(waiting > 0
                            ? Text(verbatim: String(localized: "Requests") + ": \(waiting)")
                            : Text(verbatim: ""))
    }

    // MARK: - On

    private var board: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: FriendsStyle.cardSpacing) {
                    if let error = store.errorText {
                        NoticeCard(title: Text(verbatim: error), systemImage: "exclamationmark.triangle.fill",
                                   tone: .warning, onDismiss: { store.errorText = nil })
                    }
                    FriendsNotices(onReview: { showFriends = true })
                    if let feed = store.feed {
                        let highlights = FriendsHighlights.recent(friends: feed.friends, now: store.serverNow())
                        if !highlights.isEmpty {
                            SectionHeader(title: "friends.highlights")
                            FriendsHighlightsCarousel(highlights: highlights, effortScale: effortScale,
                                                      gutter: FriendsStyle.gutter)
                        }
                        FriendsBoardHeader(sort: sort, day: Repository.logicalDay(Date()))
                            .padding(.top, NoopMetrics.space4)
                        ForEach(FriendsBoard.rows(me: feed.me, friends: feed.friends, sort: sort.wrappedValue,
                                                  todayKey: FriendsFormat.todayKey())) { row in
                            NavigationLink(value: TabRoute.friend(row.person.id)) {
                                FriendScoreCard(row: row, metric: sort.wrappedValue.metric, effortScale: effortScale,
                                                ownImageData: profile.avatarImageData)
                            }
                            .buttonStyle(.plain)
                        }
                        if feed.friends.isEmpty { noFriends }
                    } else if store.loading {
                        ProgressView().frame(maxWidth: .infinity).padding(.top, NoopMetrics.space8)
                    }
                }
                .padding(.horizontal, FriendsStyle.gutter)
                .padding(.bottom, NoopMetrics.space6)
                .id(Self.topAnchorID)
                #if os(macOS)
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
                #endif
            }
            .onChangeCompat(of: scrollToTopSignal) { _ in
                withAnimation(.easeOut(duration: 0.35)) { proxy.scrollTo(Self.topAnchorID, anchor: .top) }
            }
        }
        .refreshable { await store.sync(repo: repo, profile: profile, force: true) }
    }

    private var noFriends: some View {
        VStack(alignment: .leading, spacing: NoopMetrics.space3) {
            VStack(alignment: .leading, spacing: 2) {
                Text("No friends yet")
                    .font(StrandFont.headline)
                    .foregroundStyle(StrandPalette.textPrimary)
                Text("Invite a friend with a code. You see each other's days as soon as they use it.")
                    .font(StrandFont.pro(15))
                    .foregroundStyle(StrandPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button { showFriends = true } label: { Text("Add Friend") }
                .buttonStyle(FriendsKeyButtonStyle())
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .friendsCard()
    }
}

// MARK: - Before Friends is on

/// The tab before Friends is on, after the Fitness app's "Share Activity" page: the wearer's own picture
/// with a ring and an activity beside it and their name under it, what the tab is for in a sentence,
/// then what leaves the phone and the one button at the foot of the page. There is no form. The name
/// and photo are the profile's and the account is tied to the strap, so turning on is one tap. The
/// same page carries the three places turning on can pause: a strap not read yet, a strap that already
/// has an account, and a request to join that account waiting for an answer.
struct FriendsWelcome: View {
    @ObservedObject private var store = FriendsStore.shared
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var profile: ProfileStore

    /// The profile had no name when the page appeared, so the page asks for one. Decided once: the
    /// field must not go away at the first letter typed into it.
    @State private var asksForName = false

    var body: some View {
        GeometryReader { geo in
            ScrollView {
                VStack(spacing: 0) {
                    if let error = store.errorText {
                        NoticeCard(title: Text(verbatim: error), systemImage: "exclamationmark.triangle.fill",
                                   tone: .warning, onDismiss: { store.errorText = nil })
                            .padding(.bottom, NoopMetrics.space4)
                    }
                    FriendsWelcomeHero(imageData: profile.avatarImageData, initials: profile.initials)
                        .padding(.top, NoopMetrics.space5)
                    if !asksForName, !nameToShare.isEmpty {
                        Text(verbatim: nameToShare)
                            .font(StrandFont.pro(20))
                            .foregroundStyle(StrandPalette.textSecondary)
                            .lineLimit(1)
                            .padding(.top, NoopMetrics.space2)
                    }
                    Text(title)
                        .font(StrandFont.pro(34))
                        .foregroundStyle(StrandPalette.textPrimary)
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.7)
                        .padding(.top, NoopMetrics.space5)
                        .accessibilityAddTraits(.isHeader)
                    Text(sentence)
                        .font(StrandFont.pro(20))
                        .foregroundStyle(StrandPalette.textPrimary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, NoopMetrics.space2)
                    if case let .waiting(claim) = store.phase {
                        Text(verbatim: Self.spaced(claim.code))
                            .font(StrandFont.pro(44, weight: .semibold))
                            .monospacedDigit()
                            .foregroundStyle(FriendsStyle.key)
                            .padding(.top, NoopMetrics.space5)
                            .accessibilityLabel(Text(verbatim: claim.code.map(String.init).joined(separator: " ")))
                    }
                    Spacer(minLength: NoopMetrics.space8)
                    footer
                }
                .padding(.horizontal, FriendsStyle.gutter)
                .padding(.bottom, NoopMetrics.space4)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity, minHeight: geo.size.height)
            }
        }
        .onAppear { asksForName = profile.displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        .task(id: store.phase.stored) { await watch() }
    }

    /// The profile's name as it is sent, shown under the picture so the wearer sees who friends will
    /// see before anything leaves the phone. While the page is asking for a name the field shows it.
    private var nameToShare: String { profile.displayName.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var title: LocalizedStringKey {
        switch store.phase {
        case .off, .on: return "Share with Friends"
        case .waitingForStrap: return "Connect Your Strap"
        case .strapBound: return "This Strap Has an Account"
        case .waiting: return "Confirm on Your Other Phone"
        }
    }

    private var sentence: LocalizedStringKey {
        switch store.phase {
        case .off, .on:
            return "See how your friends recovered, trained and slept, and let them see your day."
        case .waitingForStrap:
            return "Your account is tied to your strap, so the same strap finds it again on a new phone. Connect the strap and this finishes by itself."
        case .strapBound:
            return "If it is yours from another phone, join it. If the strap came from someone else, start your own."
        case .waiting:
            return "Open Friends on the phone you used before and confirm this code."
        }
    }

    @ViewBuilder private var footer: some View {
        switch store.phase {
        case .off, .on:
            if asksForName {
                TextField("Your Name", text: $profile.displayName)
                    .font(StrandFont.pro(17))
                    .submitLabel(.done)
                    #if os(iOS)
                    .textContentType(.name)
                    #endif
                    .padding(.horizontal, 16)
                    .frame(minHeight: 50)
                    .friendsCard(radius: 14)
                    .padding(.bottom, NoopMetrics.space4)
            }
            note("lock.fill", "Nothing leaves this device until you turn Friends on, and you choose what is shared. Your name and photo are the ones in your profile.")
            Button { Task { await store.turnOn(profile: profile) } } label: { Text("Turn On") }
                .buttonStyle(FriendsKeyButtonStyle(large: true))
                .disabled(store.loading)
                .padding(.top, NoopMetrics.space5)
        case .waitingForStrap:
            note("dot.radiowaves.left.and.right", "Without a strap the account cannot be found again from another phone.")
            Button { Task { await store.turnOnWithoutStrap(profile: profile) } } label: { Text("Continue Without a Strap") }
                .buttonStyle(FriendsKeyButtonStyle(prominent: false, large: true))
                .disabled(store.loading)
                .padding(.top, NoopMetrics.space5)
            notNowButton
        case .strapBound:
            note("person.2.fill", "Starting your own asks the strap's previous owner to let it go. Friends works meanwhile.")
            Button { Task { await store.claimAccount(profile: profile) } } label: { Text("This Is My Account") }
                .buttonStyle(FriendsKeyButtonStyle(large: true))
                .disabled(store.loading)
                .padding(.top, NoopMetrics.space5)
            Button { Task { await store.turnOnWithoutStrap(profile: profile) } } label: { Text("Start a New Account") }
                .buttonStyle(FriendsKeyButtonStyle(prominent: false, large: true))
                .disabled(store.loading)
                .padding(.top, NoopMetrics.space3)
            notNowButton
        case let .waiting(claim):
            if let matures = claim.maturesAt {
                note("clock.fill", "If that phone is gone, you are let in by yourself on \(Self.moment(matures)).")
            }
            Button { Task { await store.cancelClaim() } } label: { Text("Cancel") }
                .buttonStyle(FriendsKeyButtonStyle(prominent: false, large: true))
                .padding(.top, NoopMetrics.space5)
        }
    }

    /// The way back from a page that waits on the strap: plain text under the page's buttons, in their
    /// type and key colour. Nothing has been sent by then, and nothing is.
    private var notNowButton: some View {
        Button { store.notNow() } label: {
            Text("Not Now")
                .font(StrandFont.pro(17, weight: .semibold))
                .foregroundStyle(FriendsStyle.key)
                .frame(maxWidth: .infinity)
                .padding(.vertical, NoopMetrics.space3)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(store.loading)
        .padding(.top, NoopMetrics.space2)
    }

    /// The glyph and footnote above the page's button, as Fitness sets its own.
    private func note(_ symbol: String, _ text: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Image(systemName: symbol)
                .font(StrandFont.pro(22, weight: .semibold))
                .foregroundStyle(FriendsStyle.key)
                .accessibilityHidden(true)
            Text(text)
                .font(StrandFont.pro(13))
                .foregroundStyle(StrandPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// While the page waits for something outside it, it asks again by itself: every minute how the
    /// request to join stands, every few seconds whether the strap has been read (which costs no request
    /// until it has).
    private func watch() async {
        let pause: UInt64
        switch store.phase {
        case .waiting: pause = 60_000_000_000
        case .waitingForStrap: pause = 5_000_000_000
        case .off, .strapBound, .on: return
        }
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: pause)
            guard !Task.isCancelled else { return }
            await store.sync(repo: repo, profile: profile, force: false)
        }
    }

    /// "481 902": six digits in two groups, as codes are read out.
    static func spaced(_ code: String) -> String {
        code.count == 6 ? code.prefix(3) + " " + code.suffix(3) : code
    }

    /// "Friday at 14:30", in the active language.
    static func moment(_ ts: Int) -> String {
        Date(timeIntervalSince1970: TimeInterval(ts))
            .formatted(.dateTime.weekday(.wide).hour().minute().locale(AppLanguage.activeLocale))
    }
}

/// The picture over the welcome page: the wearer, with a ring and an activity floating by their
/// shoulder as Fitness sets its own around the account's picture.
private struct FriendsWelcomeHero: View {
    var imageData: Data?
    var initials: String

    private static let side: CGFloat = 176

    var body: some View {
        ZStack {
            SummaryAvatar(imageData: imageData, initials: initials, size: Self.side)
            ActivityRingsView(rings: [ActivityRing(id: "recovery", fraction: 0.72,
                                                   start: StrandPalette.activityMoveStart,
                                                   end: StrandPalette.activityMoveEnd)],
                              diameter: 52, fitness: true, disc: false)
                .offset(x: -96, y: -78)
            Circle()
                .fill(StrandPalette.fitnessCard)
                .frame(width: 88, height: 88)
                .overlay {
                    Image(systemName: "figure.run")
                        .font(StrandFont.pro(40, weight: .semibold))
                        .foregroundStyle(FriendsStyle.key)
                }
                .offset(x: 98, y: -72)
        }
        .frame(maxWidth: .infinity)
        .frame(height: Self.side + 56, alignment: .bottom)
        .accessibilityHidden(true)
    }
}
