//  FriendsView.swift
//  NOOP · Friends — the tab, laid out as the Fitness app's Sharing tab in iOS 26: the large title with
//  one button in the bar, Highlights (what friends did lately) paging side by side, then everyone's
//  rings under a heading that carries the sort menu and the day, then the invitations sent. Requests to
//  answer are behind the bar's button, badged with their count, where Fitness keeps its invitations.
//
//  The metrics are Fitness's, measured from the Sharing screenshot in Apple's iPhone User Guide
//  ("Share your activity in Fitness", iOS 26). Before an account exists the tab is Fitness's
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

    @State private var showSetup = false
    @State private var showFriends = false

    private var sort: Binding<FriendsSort> {
        Binding(get: { FriendsSort(rawValue: sortRaw) ?? .name }, set: { sortRaw = $0.rawValue })
    }
    private var effortScale: EffortScale { UnitPrefs.resolveEffortScale(effortScaleRaw) }

    private static let topAnchorID = "friends.top"

    var body: some View {
        Group {
            if store.signedIn { signedIn } else { FriendsWelcome { showSetup = true } }
        }
        .background(StrandPalette.summaryCanvas.ignoresSafeArea())
        .navigationTitle(Text("Friends"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(store.signedIn ? .large : .inline)
        #endif
        .toolbar { toolbar }
        .sheet(isPresented: $showSetup) { FriendsSetupSheet(suggestedName: profile.displayName) }
        .sheet(isPresented: $showFriends) { FriendsManageSheet() }
        .task(id: store.signedIn) {
            // Opening the tab sends the wearer's own day first, so their card is never the stale one.
            // Coming back within a minute of a good answer shows that answer and asks nothing.
            await store.sync(repo: repo, profile: profile, force: false)
        }
    }

    /// Fitness's one bar button: it opens the friends sheet, and counts the requests waiting there. The
    /// page before an account exists has no button and no title, as Fitness's has none.
    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        if store.signedIn {
            ToolbarItem(placement: .primaryAction) { friendsButton }
        } else {
            ToolbarItem(placement: .principal) {
                Color.clear.frame(width: 1, height: 1).accessibilityHidden(true)
            }
        }
    }

    private var pendingRequests: Int { store.requests?.incoming.count ?? 0 }

    /// iOS 26 draws a bar button's badge itself; before it the glyph carries a dot instead.
    private var badgesBarButtons: Bool {
        if #available(iOS 26.0, macOS 26.0, *) { return true }
        return false
    }

    private var friendsButton: some View {
        Button { showFriends = true } label: {
            Image(systemName: "person.fill.badge.plus")
                .overlay(alignment: .topTrailing) {
                    if pendingRequests > 0, !badgesBarButtons {
                        Circle().fill(StrandPalette.settingsRed).frame(width: 8, height: 8).offset(x: 4, y: -4)
                    }
                }
        }
        .badge(pendingRequests)
        .barGlyph()
        .accessibilityLabel(Text("Add Friend"))
        .accessibilityValue(pendingRequests > 0
                            ? Text(verbatim: String(localized: "Requests") + ": \(pendingRequests)")
                            : Text(verbatim: ""))
    }

    // MARK: - Signed in

    private var signedIn: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: FriendsStyle.cardSpacing) {
                    if let error = store.errorText {
                        NoticeCard(title: Text(verbatim: error), systemImage: "exclamationmark.triangle.fill",
                                   tone: .warning, onDismiss: { store.errorText = nil })
                    }
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
                            NavigationLink(value: TabRoute.friend(row.person.nick)) {
                                FriendScoreCard(row: row, metric: sort.wrappedValue.metric, effortScale: effortScale,
                                                ownImageData: profile.avatarImageData)
                            }
                            .buttonStyle(.plain)
                        }
                        if feed.friends.isEmpty { noFriends }
                        if let outgoing = store.requests?.outgoing, !outgoing.isEmpty {
                            SectionHeader(title: "Invited")
                            FriendRowsCard(people: outgoing) { invitedRow($0) }
                        }
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
                Text("Add a friend by their name. They see your day once they accept, and you see theirs.")
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

    private func invitedRow(_ person: FriendProfile) -> some View {
        FriendPersonRow(person: person) {
            Button { Task { await store.dropRequest(person.nick) } } label: { Text("Withdraw") }
                .buttonStyle(FriendsKeyButtonStyle(prominent: false))
                .fixedSize()
        }
    }
}

// MARK: - Before an account exists

/// The tab with no account, after the Fitness app's "Share Activity" page: the wearer's own picture with
/// a ring and an activity beside it, what the tab is for in a sentence, then what leaves the phone and
/// the one button at the foot of the page.
struct FriendsWelcome: View {
    let onStart: () -> Void

    @ObservedObject private var store = FriendsStore.shared
    @EnvironmentObject private var profile: ProfileStore

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
                    Text("Share with Friends")
                        .font(StrandFont.pro(34))
                        .foregroundStyle(StrandPalette.textPrimary)
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.7)
                        .padding(.top, NoopMetrics.space5)
                        .accessibilityAddTraits(.isHeader)
                    Text("See how your friends recovered, trained and slept, and let them see your day.")
                        .font(StrandFont.pro(20))
                        .foregroundStyle(StrandPalette.textPrimary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, NoopMetrics.space2)
                    Spacer(minLength: NoopMetrics.space8)
                    VStack(alignment: .leading, spacing: 10) {
                        Image(systemName: "lock.fill")
                            .font(StrandFont.pro(22, weight: .semibold))
                            .foregroundStyle(FriendsStyle.key)
                            .accessibilityHidden(true)
                        Text("Nothing leaves this device until you create an account, and you choose what is shared.")
                            .font(StrandFont.pro(13))
                            .foregroundStyle(StrandPalette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Button(action: onStart) { Text("Get Started") }
                        .buttonStyle(FriendsKeyButtonStyle(large: true))
                        .padding(.top, NoopMetrics.space5)
                }
                .padding(.horizontal, FriendsStyle.gutter)
                .padding(.bottom, NoopMetrics.space4)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity, minHeight: geo.size.height)
            }
        }
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
