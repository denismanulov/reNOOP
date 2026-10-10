//  FriendsBoard.swift
//  NOOP · Friends — the tab's list: the wearer and their friends, one card each, as the Fitness app's
//  Sharing tab lists Activity Rings. A person's name, the day's figure in its ring's hue with the amount
//  behind it on a second line (as Fitness sets "923/850 kcal" under "108 %"), and the three rings on the
//  trailing side. The menu over the list picks the order and, with it, the score the cards show, as
//  Fitness's sort menu does.
//
//  The order is decided by `FriendsBoard.rows`, which is pure. A figure from an earlier day is still
//  shown, said to be that day's, and listed after every figure from today.

import SwiftUI
import StrandAnalytics
import StrandDesign

/// One of the three scores.
enum FriendsMetric: String, CaseIterable, Identifiable {
    case recovery, strain, sleep
    var id: String { rawValue }

    var title: String {
        switch self {
        case .recovery: return String(localized: "Charge")
        case .strain: return String(localized: "Effort")
        case .sleep: return String(localized: "Rest")
        }
    }

    /// The hue of the score's ring on the Summary, in its text weight.
    var tint: Color {
        switch self {
        case .recovery: return StrandPalette.activityMoveText
        case .strain: return StrandPalette.activityExerciseText
        case .sleep: return StrandPalette.activityStandText
        }
    }

    func value(_ day: FriendsDay) -> Double? {
        switch self {
        case .recovery: return day.recovery.map(Double.init)
        case .strain: return day.strain
        case .sleep: return day.sleepScore.map(Double.init)
        }
    }

    func text(_ value: Double, scale: EffortScale) -> String {
        switch self {
        case .recovery, .sleep: return FriendsFormat.percent(Int(value.rounded()))
        case .strain: return FriendsFormat.strain(value, scale: scale)
        }
    }

    /// Whether a card for this score carries a second line. Sleep has the time asleep behind it and
    /// Strain the minutes of workouts, as Fitness's Exercise ring has its minutes; nothing a friend
    /// uploads stands behind Recovery, so its cards are one line.
    var hasDetail: Bool { self != .recovery }
}

/// The list's order. Each order puts one score on the cards; by name it is Recovery, the first ring, as
/// Fitness shows Move.
enum FriendsSort: String, CaseIterable, Identifiable {
    case name, recovery, strain, sleep
    var id: String { rawValue }

    var metric: FriendsMetric {
        switch self {
        case .name, .recovery: return .recovery
        case .strain: return .strain
        case .sleep: return .sleep
        }
    }

    var title: String {
        switch self {
        case .name: return String(localized: "Name")
        case .recovery, .strain, .sleep: return metric.title
        }
    }
}

/// One person on the list.
struct FriendsBoardRow: Identifiable, Equatable {
    /// How the shown figure stands to the wearer's own day.
    enum Standing: Equatable {
        /// A figure from today.
        case today
        /// The newest figure is from an earlier day, named by its key.
        case stale(String)
        /// The person has switched their scores off.
        case notShared
        /// Nothing uploaded yet, or nothing for this score.
        case noData
    }

    /// The amount behind the figure, from the same day.
    enum Detail: Equatable {
        /// Time asleep that night.
        case asleep(minutes: Int)
        /// The day's workouts, added up; zero on a day without one.
        case workouts(minutes: Int)
    }

    let person: FriendProfile
    let isMe: Bool
    let value: Double?
    let standing: Standing
    var detail: Detail? = nil

    var id: String { person.nick }
}

enum FriendsBoard {
    /// Everyone on the list in `sort`'s order, each with their figure for `sort.metric`.
    ///
    /// By name the wearer leads, as Fitness lists "Me", and friends follow alphabetically. By a score,
    /// those with a figure from `todayKey` (or a later day, for a friend whose calendar is already ahead)
    /// come first, highest first, the wearer among them; then everyone else by name. Pure: `todayKey` is
    /// the wearer's own day, "yyyy-MM-dd".
    static func rows(me: FriendProfile, friends: [FriendProfile], sort: FriendsSort,
                     todayKey: String) -> [FriendsBoardRow] {
        let own = row(me, isMe: true, metric: sort.metric, todayKey: todayKey)
        let others = friends.map { row($0, isMe: false, metric: sort.metric, todayKey: todayKey) }
        if sort == .name {
            return [own] + others.sorted { byName($0.person, $1.person) }
        }
        let all = [own] + others
        let fresh = all.filter { $0.standing == .today }.sorted { a, b in
            a.value != b.value ? (a.value ?? 0) > (b.value ?? 0) : byName(a.person, b.person)
        }
        let rest = all.filter { $0.standing != .today }.sorted { byName($0.person, $1.person) }
        return fresh + rest
    }

    private static func row(_ person: FriendProfile, isMe: Bool, metric: FriendsMetric,
                            todayKey: String) -> FriendsBoardRow {
        if person.share?.scores == false {
            return FriendsBoardRow(person: person, isMe: isMe, value: nil, standing: .notShared)
        }
        guard let day = person.latestDay, let value = metric.value(day.summary) else {
            return FriendsBoardRow(person: person, isMe: isMe, value: nil, standing: .noData)
        }
        return FriendsBoardRow(person: person, isMe: isMe, value: value,
                               standing: day.day >= todayKey ? .today : .stale(day.day),
                               detail: detail(person, day: day.summary, metric: metric))
    }

    /// What stands behind the figure, where the person shares it. A section a person has switched off
    /// is not sent, so it is not known and nothing is shown for it.
    private static func detail(_ person: FriendProfile, day: FriendsDay, metric: FriendsMetric) -> FriendsBoardRow.Detail? {
        switch metric {
        case .recovery:
            return nil
        case .sleep:
            guard person.share?.sleep != false, let night = day.sleep else { return nil }
            return .asleep(minutes: night.asleepMin)
        case .strain:
            guard person.share?.workouts != false else { return nil }
            let seconds = (day.workouts ?? []).reduce(0) { $0 + $1.durationS }
            return .workouts(minutes: Int((Double(seconds) / 60).rounded()))
        }
    }

    private static func byName(_ a: FriendProfile, _ b: FriendProfile) -> Bool {
        let order = a.name.localizedCaseInsensitiveCompare(b.name)
        return order != .orderedSame ? order == .orderedAscending : a.nick < b.nick
    }
}

// MARK: - Views

/// The heading over the list, as Fitness heads Activity Rings: the title with the sort menu across from
/// it, and the day the figures belong to in small capitals under.
struct FriendsBoardHeader: View {
    @Binding var sort: FriendsSort
    /// The wearer's own day, which a card's figure is from unless the card says otherwise.
    let day: Date

    @Environment(\.dynamicTypeSize) private var dts

    private var dayLine: String {
        let date = day.formatted(.dateTime.day().month(.wide).year().locale(AppLanguage.activeLocale))
        return (String(localized: "Today") + ", " + date).uppercased(with: AppLanguage.activeLocale)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            let layout = dts.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
                : AnyLayout(HStackLayout(alignment: .firstTextBaseline))
            layout {
                Text("Rings")
                    .font(StrandFont.pro(22, weight: .bold))
                    .foregroundStyle(StrandPalette.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                if !dts.isAccessibilitySize { Spacer(minLength: 8) }
                sortMenu
            }
            Text(verbatim: dayLine)
                .font(StrandFont.pro(15))
                .foregroundStyle(StrandPalette.textSecondary)
        }
        .padding(.horizontal, 4)
    }

    private var sortMenu: some View {
        Menu {
            Picker(selection: $sort) {
                ForEach(FriendsSort.allCases) { Text(verbatim: $0.title).tag($0) }
            } label: {
                Text("Sort")
            }
        } label: {
            HStack(spacing: 4) {
                Text(verbatim: sort.title)
                Image(systemName: "chevron.up.chevron.down").imageScale(.small)
            }
            .font(StrandFont.pro(17))
            .foregroundStyle(FriendsStyle.key)
            // A 44 pt target around the label without moving the heading.
            .contentShape(Rectangle().inset(by: -12))
        }
        .accessibilityLabel(Text("Sort"))
        .accessibilityValue(Text(verbatim: sort.title))
    }
}

/// One person's card, to Fitness's measure: the picture and the name, the figure for the list's score in
/// its ring's hue with the amount behind it under, and the rings on the trailing side.
struct FriendScoreCard: View {
    let row: FriendsBoardRow
    let metric: FriendsMetric
    let effortScale: EffortScale
    /// The wearer's own photo, for their own card.
    var ownImageData: Data?

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .title) private var figureSize: CGFloat = 28

    static var avatarSize: CGFloat { 32 }
    static var ringsDiameter: CGFloat { 72 }
    /// A one-line card is no shorter than this, so the rings keep their margin.
    static var minHeight: CGFloat { 104 }

    /// Fitness names the wearer's own card "Me".
    private var name: String { row.isMe ? String(localized: "Me") : row.person.name }

    private var note: String? {
        switch row.standing {
        case .today: return nil
        // "Yesterday" counted from the same logical day the list is headed with.
        case let .stale(dayKey): return FriendsFormat.dayLabel(dayKey, now: Repository.logicalDay(Date()))
        case .notShared: return String(localized: "Doesn't share scores")
        case .noData: return String(localized: "No data yet")
        }
    }

    private var figure: String {
        row.value.map { metric.text($0, scale: effortScale) } ?? SummaryMetricReading.noValue
    }

    private var detail: String? {
        switch row.detail {
        case let .asleep(minutes): return FriendsFormat.duration(seconds: minutes * 60)
        case let .workouts(minutes): return FriendsFormat.minutes(minutes)
        case nil: return nil
        }
    }

    /// A figure that is not this day's is set back in grey.
    private var tint: Color { row.standing == .today ? metric.tint : StrandPalette.textSecondary }
    private var figureFont: Font { .system(size: figureSize, weight: .medium, design: .rounded) }

    /// The rings are the person's newest day, the same day the figure is from.
    private var rings: some View {
        let day = row.standing == .notShared ? nil : row.person.latestDay?.summary
        // Fitness is dark-only and sets the rings straight on its card; on a white card they keep the
        // black disc Health gives its small rings, since the hues are made for black.
        return ActivityRingsView(rings: FriendsFormat.rings(day, glyphs: false), diameter: Self.ringsDiameter,
                                 fitness: true, disc: colorScheme == .light)
    }

    var body: some View {
        Group {
            if dts.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 12) {
                    text
                    rings
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(alignment: .center, spacing: 12) {
                    text
                    Spacer(minLength: 0)
                    rings
                }
                .frame(minHeight: Self.minHeight - 26)
            }
        }
        .padding(EdgeInsets(top: 14, leading: 16, bottom: 12, trailing: 20))
        .friendsCard()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: spoken))
        .accessibilityAddTraits(.isButton)
    }

    private var text: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 12) {
                FriendAvatar(person: row.person, size: Self.avatarSize, own: row.isMe, imageData: ownImageData)
                Text(verbatim: name)
                    .font(StrandFont.pro(17))
                    .foregroundStyle(StrandPalette.textPrimary)
                    .lineLimit(dts.isAccessibilitySize ? 2 : 1)
            }
            // The two lines sit closer than their own leading, as Fitness sets them.
            VStack(alignment: .leading, spacing: -2) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(verbatim: figure)
                        .font(figureFont)
                        .foregroundStyle(tint)
                        .lineLimit(1)
                        .layoutPriority(1)
                    if let note {
                        Text(verbatim: note)
                            .font(StrandFont.pro(15))
                            .foregroundStyle(StrandPalette.textSecondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                }
                if metric.hasDetail {
                    // Every card under this order keeps the line, so the cards stay one height.
                    Text(verbatim: detail ?? " ")
                        .font(figureFont)
                        .foregroundStyle(tint)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
        }
    }

    private var spoken: String {
        var parts = [name]
        if row.value != nil { parts.append(metric.title + " " + figure) }
        if let detail {
            switch row.detail {
            case .asleep: parts.append(String(localized: "Time Asleep") + " " + detail)
            case .workouts: parts.append(String(localized: "Workouts") + " " + detail)
            case nil: break
            }
        }
        if let note { parts.append(note) }
        return parts.joined(separator: ", ")
    }
}
