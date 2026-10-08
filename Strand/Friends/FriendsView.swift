//  FriendsView.swift
//  NOOP · Friends — the tab: everyone's day at a glance, laid out as the Fitness app's Sharing tab
//  (iOS 26): a small centred title, an add-friend button, a headed list with a sort menu, and one card
//  per person with their name, their figures and their rings.
//
//  Before an account exists the tab is Fitness's "Get Started" page and nothing is sent anywhere.

import SwiftUI
import StrandAnalytics
import StrandDesign

struct FriendsView: View {
    @ObservedObject private var store = FriendsStore.shared
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var profile: ProfileStore
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue
    @AppStorage("friends.sort") private var sortRaw = FriendsSort.name.rawValue

    @State private var showSetup = false
    @State private var showAdd = false
    @State private var showSharing = false

    private var sort: FriendsSort { FriendsSort(rawValue: sortRaw) ?? .name }
    private var effortScale: EffortScale { UnitPrefs.resolveEffortScale(effortScaleRaw) }

    var body: some View {
        Group {
            if store.signedIn { signedIn } else { welcome }
        }
        .summaryBackdrop()
        .navigationTitle(Text("Friends"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar { toolbar }
        .sheet(isPresented: $showSetup) { FriendsSetupSheet(suggestedName: profile.displayName) }
        .sheet(isPresented: $showAdd) { AddFriendSheet() }
        .sheet(isPresented: $showSharing) { FriendsSharingSheet() }
        .task(id: store.signedIn) {
            // Opening the tab sends the wearer's own day first, so their card is never the stale one.
            await store.uploadRecentDays(repo: repo)
            await store.refresh()
        }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        if store.signedIn {
            ToolbarItem(placement: .primaryAction) {
                Button { showAdd = true } label: { Image(systemName: "person.badge.plus") }
                    .accessibilityLabel(Text("Add Friend"))
            }
            ToolbarItem(placement: .cancellationAction) {
                Button { showSharing = true } label: { Image(systemName: "person.crop.circle") }
                    .accessibilityLabel(Text("My Sharing"))
            }
        }
    }

    // MARK: - Signed out

    private var welcome: some View {
        ScrollView {
            VStack(spacing: NoopMetrics.space4) {
                Image(systemName: "person.2.fill")
                    .font(.system(size: 56))
                    .foregroundStyle(StrandPalette.accent)
                    .padding(.top, NoopMetrics.space8)
                    .accessibilityHidden(true)
                Text("Share with Friends")
                    .font(StrandFont.pro(28, weight: .bold))
                    .foregroundStyle(StrandPalette.textPrimary)
                    .multilineTextAlignment(.center)
                Text("See how your friends recovered, trained and slept, and let them see your day. You choose what is shared, and nothing leaves this device until you create an account.")
                    .font(StrandFont.pro(17))
                    .foregroundStyle(StrandPalette.textSecondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                Button { showSetup = true } label: {
                    Text("Get Started")
                        .font(StrandFont.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.top, NoopMetrics.space2)
            }
            .padding(.horizontal, 28)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Signed in

    private var signedIn: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if let error = store.errorText {
                    Text(error)
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.settingsRed)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let incoming = store.requests?.incoming, !incoming.isEmpty {
                    FriendsSectionTitle(title: "Requests")
                    ForEach(incoming) { person in requestCard(person) }
                }
                if let feed = store.feed {
                    FriendsSectionTitle(title: "Scores", subtitle: FriendsFormat.today()) { sortMenu }
                    ForEach(people(feed)) { person in
                        NavigationLink(value: TabRoute.friend(person.nick)) {
                            FriendCard(person: person, isMe: person.nick == feed.me.nick, effortScale: effortScale)
                        }
                        .buttonStyle(.plain)
                    }
                    if feed.friends.isEmpty { noFriends }
                    if let outgoing = store.requests?.outgoing, !outgoing.isEmpty {
                        FriendsSectionTitle(title: "Invited")
                        ForEach(outgoing) { person in invitedCard(person) }
                    }
                } else if store.loading {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, NoopMetrics.space8)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, NoopMetrics.space3)
            #if os(macOS)
            .frame(maxWidth: 680)
            .frame(maxWidth: .infinity)
            #endif
        }
        .refreshable {
            await store.uploadRecentDays(repo: repo)
            await store.refresh()
        }
    }

    /// The account first, then friends in the chosen order. A person with no figure sorts last.
    private func people(_ feed: FriendsFeed) -> [FriendProfile] {
        [feed.me] + feed.friends.sorted { a, b in
            switch sort {
            case .name: return a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
            case .recovery: return FriendsSort.descending(a.latestDay?.summary.recovery.map(Double.init),
                                                          b.latestDay?.summary.recovery.map(Double.init), a, b)
            case .strain: return FriendsSort.descending(a.latestDay?.summary.strain, b.latestDay?.summary.strain, a, b)
            case .sleep: return FriendsSort.descending(a.latestDay?.summary.sleepScore.map(Double.init),
                                                       b.latestDay?.summary.sleepScore.map(Double.init), a, b)
            }
        }
    }

    private var sortMenu: some View {
        Menu {
            Picker("Sort", selection: $sortRaw) {
                ForEach(FriendsSort.allCases) { Text($0.title).tag($0.rawValue) }
            }
        } label: {
            HStack(spacing: 3) {
                Text(sort.title)
                Image(systemName: "chevron.up.chevron.down").imageScale(.small)
            }
            .font(StrandFont.pro(15))
            .foregroundStyle(StrandPalette.accent)
        }
        .accessibilityLabel(Text("Sort"))
    }

    private var noFriends: some View {
        SummaryCard {
            VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                Text("No friends yet")
                    .font(StrandFont.headline)
                    .foregroundStyle(StrandPalette.textPrimary)
                Text("Add a friend by their name. They see your day once they accept, and you see theirs.")
                    .font(StrandFont.pro(15))
                    .foregroundStyle(StrandPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Button { showAdd = true } label: { Label("Add Friend", systemImage: "person.badge.plus") }
                    .font(StrandFont.pro(17))
                    .foregroundStyle(StrandPalette.accent)
                    .buttonStyle(.plain)
                    .padding(.top, 2)
            }
        }
    }

    /// A request reads as a notification does: who, then the two answers side by side under the name,
    /// so neither a long name nor a long word ever squeezes the other.
    private func requestCard(_ person: FriendProfile) -> some View {
        SummaryCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    FriendAvatar(name: person.name, size: 40)
                    FriendNameStack(person: person)
                    Spacer(minLength: 0)
                }
                HStack(spacing: 10) {
                    Button { Task { await store.dropRequest(person.nick) } } label: {
                        Text("Decline").lineLimit(1).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    Button { Task { await store.accept(person.nick) } } label: {
                        Text("Accept").lineLimit(1).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
        }
    }

    private func invitedCard(_ person: FriendProfile) -> some View {
        SummaryCard {
            HStack(spacing: 12) {
                FriendAvatar(name: person.name, size: 40)
                FriendNameStack(person: person)
                Spacer(minLength: 8)
                Button { Task { await store.dropRequest(person.nick) } } label: { Text("Withdraw").lineLimit(1) }
                    .buttonStyle(.bordered)
                    .fixedSize()
            }
        }
    }
}

enum FriendsSort: String, CaseIterable, Identifiable {
    case name, recovery, strain, sleep
    var id: String { rawValue }

    var title: String {
        switch self {
        case .name: return String(localized: "Name")
        case .recovery: return String(localized: "Charge")
        case .strain: return String(localized: "Effort")
        case .sleep: return String(localized: "Rest")
        }
    }

    /// Higher first; a missing figure after every present one; ties by name.
    static func descending(_ a: Double?, _ b: Double?, _ pa: FriendProfile, _ pb: FriendProfile) -> Bool {
        switch (a, b) {
        case let (x?, y?) where x != y: return x > y
        case (_?, nil): return true
        case (nil, _?): return false
        default: return pa.name.localizedCaseInsensitiveCompare(pb.name) == .orderedAscending
        }
    }
}

// MARK: - Pieces

/// Fitness's section heading on the Sharing tab: a bold title, a grey line under it, a control beside.
struct FriendsSectionTitle<Trailing: View>: View {
    let title: LocalizedStringKey
    var subtitle: String?
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(alignment: .lastTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(StrandFont.pro(22, weight: .bold))
                    .foregroundStyle(StrandPalette.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                if let subtitle {
                    Text(verbatim: subtitle)
                        .font(StrandFont.pro(15))
                        .foregroundStyle(StrandPalette.textSecondary)
                }
            }
            Spacer(minLength: 8)
            trailing
        }
        .padding(.top, NoopMetrics.space3)
        .padding(.horizontal, 4)
    }
}

extension FriendsSectionTitle where Trailing == EmptyView {
    init(title: LocalizedStringKey, subtitle: String? = nil) {
        self.init(title: title, subtitle: subtitle) { EmptyView() }
    }
}

/// A person's monogram on Contacts' grey, as the Summary draws the wearer's own.
struct FriendAvatar: View {
    let name: String
    let size: CGFloat

    var body: some View {
        SummaryAvatar(imageData: nil, initials: FriendsFormat.initials(name), size: size)
            .accessibilityHidden(true)
    }
}

struct FriendNameStack: View {
    let person: FriendProfile

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(verbatim: person.name)
                .font(StrandFont.headline)
                .foregroundStyle(StrandPalette.textPrimary)
                .lineLimit(1)
            Text(verbatim: "@" + person.nick)
                .font(StrandFont.footnote)
                .foregroundStyle(StrandPalette.textSecondary)
                .lineLimit(1)
        }
    }
}

/// One person on the list: who, their three figures in the rings' own hues, and the rings.
struct FriendCard: View {
    let person: FriendProfile
    let isMe: Bool
    let effortScale: EffortScale

    var body: some View {
        let day = person.latestDay
        SummaryCard(insets: EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16)) {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 8) {
                        FriendAvatar(name: person.name, size: 26)
                        Text(verbatim: person.name)
                            .font(StrandFont.pro(17))
                            .foregroundStyle(StrandPalette.textPrimary)
                            .lineLimit(1)
                        if isMe {
                            Text("You")
                                .font(StrandFont.footnote)
                                .foregroundStyle(StrandPalette.textSecondary)
                        }
                    }
                    if person.share?.scores == false {
                        note(String(localized: "Doesn't share scores"))
                    } else if let day, !FriendsFormat.hasScores(day.summary) {
                        note(String(localized: "No scores yet"))
                    } else if let day {
                        FriendScoresRow(day: day.summary, effortScale: effortScale)
                        if let stamp = FriendsFormat.staleStamp(day.day) { note(stamp) }
                    } else {
                        note(String(localized: "No data yet"))
                    }
                }
                Spacer(minLength: 8)
                ActivityRingsView(rings: FriendsFormat.rings(day?.summary, glyphs: false), diameter: 62)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func note(_ text: String) -> some View {
        Text(verbatim: text)
            .font(StrandFont.pro(15))
            .foregroundStyle(StrandPalette.textSecondary)
    }
}

/// Recovery, Strain and Sleep side by side, each in its ring's colour over its name.
struct FriendScoresRow: View {
    let day: FriendsDay
    let effortScale: EffortScale
    var valueSize: CGFloat = 22

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            figure(FriendsFormat.percent(day.recovery), String(localized: "Charge"), StrandPalette.activityMoveText)
            figure(FriendsFormat.strain(day.strain, scale: effortScale), String(localized: "Effort"),
                   StrandPalette.activityExerciseText)
            figure(FriendsFormat.percent(day.sleepScore), String(localized: "Rest"), StrandPalette.activityStandText)
        }
    }

    private func figure(_ value: String, _ label: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: value)
                .font(StrandFont.rounded(valueSize, weight: .semibold))
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(verbatim: label)
                .font(StrandFont.pro(11))
                .foregroundStyle(StrandPalette.textSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(verbatim: "\(label) \(value)"))
    }
}

// MARK: - Formatting

enum FriendsFormat {
    static func initials(_ name: String) -> String {
        let words = name.split(whereSeparator: { $0 == " " || $0 == "_" }).prefix(2)
        return words.compactMap { $0.first }.map { String($0).uppercased() }.joined()
    }

    static func hasScores(_ day: FriendsDay) -> Bool {
        day.recovery != nil || day.strain != nil || day.sleepScore != nil
    }

    static func percent(_ value: Int?) -> String {
        value.map { "\($0)%" } ?? SummaryMetricReading.noValue
    }

    /// Strain travels on the stored 0-100 axis; each phone draws it on its wearer's own scale.
    static func strain(_ value: Double?, scale: EffortScale) -> String {
        guard let value else { return SummaryMetricReading.noValue }
        return SummaryMetricReading.decimal(UnitFormatter.effortValue(value, scale: scale))
    }

    static func rings(_ day: FriendsDay?, glyphs: Bool) -> [ActivityRing] {
        [ActivityRing(id: "recovery", fraction: RingFraction.of(day?.recovery.map(Double.init), max: 100),
                      start: StrandPalette.activityMoveStart, end: StrandPalette.activityMoveEnd,
                      glyph: glyphs ? .move : nil),
         ActivityRing(id: "strain", fraction: RingFraction.of(day?.strain, max: 100),
                      start: StrandPalette.activityExerciseStart, end: StrandPalette.activityExerciseEnd,
                      glyph: glyphs ? .exercise : nil),
         ActivityRing(id: "sleep", fraction: RingFraction.of(day?.sleepScore.map(Double.init), max: 100),
                      start: StrandPalette.activityStandStart, end: StrandPalette.activityStandEnd,
                      glyph: glyphs ? .stand : nil)]
    }

    private static let keyFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static func date(_ dayKey: String) -> Date? { keyFormatter.date(from: dayKey) }

    /// "Today, 8 October", as Fitness dates its Sharing list.
    static func today(_ now: Date = Date()) -> String {
        String(localized: "Today") + ", "
            + now.formatted(.dateTime.day().month(.wide).locale(AppLanguage.activeLocale))
    }

    /// "Today", "Yesterday" or the date, for a day key.
    static func dayLabel(_ dayKey: String, now: Date = Date()) -> String {
        guard let date = date(dayKey) else { return dayKey }
        let cal = Calendar.current
        if cal.isDate(date, inSameDayAs: now) { return String(localized: "Today") }
        if let y = cal.date(byAdding: .day, value: -1, to: now), cal.isDate(date, inSameDayAs: y) {
            return String(localized: "Yesterday")
        }
        return date.formatted(.dateTime.day().month(.wide).locale(AppLanguage.activeLocale))
    }

    /// Nil for today's figures; otherwise which day the card's figures are from.
    static func staleStamp(_ dayKey: String, now: Date = Date()) -> String? {
        guard let date = date(dayKey), !Calendar.current.isDate(date, inSameDayAs: now) else { return nil }
        return dayLabel(dayKey, now: now)
    }

    static func clock(_ ts: Int) -> String {
        Date(timeIntervalSince1970: TimeInterval(ts))
            .formatted(.dateTime.hour().minute().locale(AppLanguage.activeLocale))
    }
}
