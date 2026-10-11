//  FriendsBoard.swift
//  NOOP · Friends — the tab's list: the wearer and their friends, one card each, as the Fitness app's
//  Sharing tab lists Activity Rings. A person's name, the day's figure in its ring's hue with the amount
//  behind it on a second line (as Fitness sets "923/850KCAL" under "108%"), and the three rings on the
//  trailing side. The menu over the list picks the order and, with it, what the cards show, as
//  Fitness's sort menu does: a score, or the day's workouts.
//
//  The order is decided by `FriendsBoard.rows`, which is pure. A figure from an earlier day is still
//  shown, said to be that day's, and listed after every figure from today.

import SwiftUI
import StrandAnalytics
import StrandDesign

/// What a card shows: one of the three scores, or the day's workouts.
enum FriendsMetric: String, CaseIterable, Identifiable {
    case recovery, strain, sleep, workouts
    var id: String { rawValue }

    var title: String {
        switch self {
        case .recovery: return String(localized: "Charge")
        case .strain: return String(localized: "Effort")
        case .sleep: return String(localized: "Rest")
        case .workouts: return String(localized: "Workouts")
        }
    }

    /// The hue of the score's ring on the Summary, in its text weight. Workouts are set in the first
    /// ring's hue, as Fitness sets its own Workouts order in Move's.
    var tint: Color {
        switch self {
        case .recovery, .workouts: return StrandPalette.activityMoveText
        case .strain: return StrandPalette.activityExerciseText
        case .sleep: return StrandPalette.activityStandText
        }
    }

    /// The day's figure. For workouts it is the energy they burned, added up: zero on a day without
    /// one, and nil when the day has workouts but none of them carries its energy.
    func value(_ day: FriendsDay) -> Double? {
        switch self {
        case .recovery: return day.recovery.map(Double.init)
        case .strain: return day.strain
        case .sleep: return day.sleepScore.map(Double.init)
        case .workouts:
            guard let list = day.workouts else { return nil }
            let known = list.compactMap(\.kcal)
            return known.isEmpty && !list.isEmpty ? nil : Double(known.reduce(0, +))
        }
    }

    func text(_ value: Double, scale: EffortScale) -> String {
        switch self {
        case .recovery, .sleep: return FriendsFormat.percent(Int(value.rounded()))
        case .strain: return FriendsFormat.strain(value, scale: scale)
        case .workouts: return FriendsFormat.whole(value)
        }
    }

    /// Whether a card for this figure carries a second line. Sleep has the time asleep behind it, Strain
    /// the minutes of workouts (as Fitness's Exercise ring has its minutes) and the workouts their
    /// name; nothing a friend uploads stands behind Recovery, so its cards are one line.
    var hasDetail: Bool { self != .recovery }
}

/// The list's order. Each order puts one figure on the cards; by name it is Recovery, the first ring, as
/// Fitness shows Move.
enum FriendsSort: String, CaseIterable, Identifiable {
    case name, recovery, strain, sleep, workouts
    var id: String { rawValue }

    var metric: FriendsMetric {
        switch self {
        case .name, .recovery: return .recovery
        case .strain: return .strain
        case .sleep: return .sleep
        case .workouts: return .workouts
        }
    }

    var title: String {
        switch self {
        case .name: return String(localized: "Name")
        case .recovery, .strain, .sleep, .workouts: return metric.title
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
        /// How many workouts the day had and, when it was one, which sport.
        case sessions(count: Int, sport: String?)
    }

    let person: FriendProfile
    let isMe: Bool
    let value: Double?
    let standing: Standing
    var detail: Detail? = nil

    var id: String { person.id }
}

enum FriendsBoard {
    /// Everyone on the list in `sort`'s order, each with their figure for `sort.metric`.
    ///
    /// By name the wearer stands among their friends under `meName`, the word their own card carries, as
    /// Fitness files "Me" between Jane and Paul. By a figure, those with one from `todayKey` (or a later
    /// day, for a friend whose calendar is already ahead) come first, highest first, the wearer among
    /// them; then everyone else by name. Pure: `todayKey` is the wearer's own day, "yyyy-MM-dd".
    static func rows(me: FriendProfile, friends: [FriendProfile], sort: FriendsSort,
                     todayKey: String, meName: String) -> [FriendsBoardRow] {
        let own = row(me, isMe: true, metric: sort.metric, todayKey: todayKey)
        let all = [own] + friends.map { row($0, isMe: false, metric: sort.metric, todayKey: todayKey) }
        let byListedName: (FriendsBoardRow, FriendsBoardRow) -> Bool = { a, b in
            let order = (a.isMe ? meName : a.person.name)
                .localizedCaseInsensitiveCompare(b.isMe ? meName : b.person.name)
            return order != .orderedSame ? order == .orderedAscending : a.person.id < b.person.id
        }
        if sort == .name { return all.sorted(by: byListedName) }
        let fresh = all.filter { $0.standing == .today }.sorted { a, b in
            a.value != b.value ? (a.value ?? 0) > (b.value ?? 0) : byListedName(a, b)
        }
        return fresh + all.filter { $0.standing != .today }.sorted(by: byListedName)
    }

    private static func row(_ person: FriendProfile, isMe: Bool, metric: FriendsMetric,
                            todayKey: String) -> FriendsBoardRow {
        // The scores and the workouts each have their own sharing switch.
        let shared = metric == .workouts ? person.share?.workouts != false : person.share?.scores != false
        guard shared else {
            return FriendsBoardRow(person: person, isMe: isMe, value: nil, standing: .notShared)
        }
        guard let day = person.latestDay else {
            return FriendsBoardRow(person: person, isMe: isMe, value: nil, standing: .noData)
        }
        let value = metric.value(day.summary)
        // A day whose workouts were not read says nothing about workouts; one that has some without
        // their energy is still that day's, with no figure to show.
        if value == nil, metric != .workouts || day.summary.workouts == nil {
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
        case .workouts:
            let list = day.workouts ?? []
            return .sessions(count: list.count, sport: list.count == 1 ? list[0].sport : nil)
        }
    }
}

// MARK: - Views

/// The heading over the list, as Fitness heads Activity Rings: the title with the sort menu across from
/// it, and the day the figures belong to in capitals under.
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
        VStack(alignment: .leading, spacing: FriendsStyle.dayLineGap) {
            let layout = dts.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
                : AnyLayout(HStackLayout(alignment: .firstTextBaseline))
            layout {
                FriendsSectionTitle(title: "Rings")
                if !dts.isAccessibilitySize { Spacer(minLength: 8) }
                sortMenu
            }
            Text(verbatim: dayLine)
                .font(StrandFont.pro(17))
                .foregroundStyle(StrandPalette.textSecondary)
        }
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
        // Fitness sets the menu in from the cards' edge, a little above the title's baseline.
        .padding(.trailing, dts.isAccessibilitySize ? 0 : FriendsStyle.sortInset)
        .offset(y: dts.isAccessibilitySize ? 0 : -2)
        .accessibilityLabel(Text("Sort"))
        .accessibilityValue(Text(verbatim: sort.title))
    }
}

/// One person's card, to Fitness's measure: the picture and the name, the figure for the list's order in
/// its ring's hue with the amount behind it under, and the rings on the trailing side.
struct FriendScoreCard: View {
    let row: FriendsBoardRow
    let metric: FriendsMetric
    let effortScale: EffortScale
    /// The wearer's own photo, for their own card.
    var ownImageData: Data?

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .title) private var figureSize: CGFloat = FriendsStyle.figureSize

    static var avatarSize: CGFloat { 32 }

    /// Fitness names the wearer's own card "Me".
    private var name: String { row.isMe ? String(localized: "Me") : row.person.name }

    private var note: String? {
        switch row.standing {
        case .today: return nil
        // "Yesterday" counted from the same logical day the list is headed with.
        case let .stale(dayKey): return FriendsFormat.dayLabel(dayKey, now: Repository.logicalDay(Date()))
        case .notShared:
            return metric == .workouts ? String(localized: "Doesn't share workouts")
                                       : String(localized: "Doesn't share scores")
        case .noData: return String(localized: "No data yet")
        }
    }

    private var figure: String {
        row.value.map { metric.text($0, scale: effortScale) } ?? SummaryMetricReading.noValue
    }

    /// The workouts order's first line: the sport when the day had one workout, how many otherwise.
    private var sessions: String? {
        guard case let .sessions(count, sport) = row.detail else { return nil }
        if let sport { return WorkoutSource.localizedSport(sport) }
        return String(localized: "\(count) workouts")
    }

    /// A figure that is not this day's is set back in grey.
    private var tint: Color { row.standing == .today ? metric.tint : StrandPalette.textSecondary }

    /// The rings are the person's newest day, the same day the figure is from.
    private var rings: some View {
        let day = row.person.share?.scores == false ? nil : row.person.latestDay?.summary
        // Fitness is dark-only and sets the rings straight on its card; on a white card they keep the
        // black disc Health gives its small rings, since the hues are made for black.
        return ActivityRingsView(rings: FriendsFormat.rings(day, glyphs: false),
                                 diameter: FriendsStyle.cardRings.diameter, fitness: true,
                                 disc: colorScheme == .light, stroke: FriendsStyle.cardRings.stroke,
                                 gap: FriendsStyle.cardRings.gap)
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
            }
        }
        .padding(EdgeInsets(top: 14, leading: 16, bottom: 11, trailing: 20))
        .friendsCard()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: spoken))
        .accessibilityAddTraits(.isButton)
    }

    private var text: some View {
        VStack(alignment: .leading, spacing: 1.67) {
            HStack(spacing: 11) {
                FriendAvatar(person: row.person, size: Self.avatarSize, own: row.isMe, imageData: ownImageData)
                HStack(spacing: 5) {
                    // Fitness marks the wearer's own card with a dot in the hue of its figure.
                    if row.isMe {
                        Circle().fill(tint).frame(width: 10, height: 10)
                    }
                    Text(verbatim: name)
                        .font(StrandFont.pro(17))
                        .foregroundStyle(StrandPalette.textPrimary)
                        .lineLimit(dts.isAccessibilitySize ? 2 : 1)
                }
            }
            // The lines sit closer than their own leading, as Fitness sets them.
            VStack(alignment: .leading, spacing: FriendsStyle.figureLineGap) {
                if let sessions {
                    Text(verbatim: sessions)
                        .font(StrandFont.pro(17))
                        .foregroundStyle(tint)
                        .lineLimit(1)
                }
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    firstLine
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
                if metric == .strain || metric == .sleep {
                    // Every card under this order keeps the line, so the cards stay one height.
                    secondLine
                        .foregroundStyle(tint)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
        }
    }

    /// The figure, and for the workouts order the energy with its unit.
    private var firstLine: Text {
        guard metric == .workouts, row.value != nil else {
            return FriendsFigure.text(figure, size: figureSize)
        }
        return FriendsFigure.text(figure, unit: String(localized: "kcal"), size: figureSize)
    }

    /// The amount behind a score: the minutes of workouts, or the time asleep.
    private var secondLine: Text {
        switch row.detail {
        case let .workouts(minutes):
            return FriendsFigure.text("\(minutes)", unit: String(localized: "sleep.unit.min", defaultValue: "min"),
                                      size: figureSize)
        case let .asleep(minutes):
            return FriendsFigure.duration(minutes: minutes, size: figureSize)
        case .sessions, nil:
            return FriendsFigure.text(" ", size: figureSize)
        }
    }

    private var spoken: String {
        var parts = [name]
        if let sessions { parts.append(sessions) }
        if row.value != nil {
            parts.append(metric == .workouts ? figure + " " + String(localized: "kcal")
                                             : metric.title + " " + figure)
        }
        switch row.detail {
        case let .asleep(minutes):
            parts.append(String(localized: "Time Asleep") + " " + FriendsFormat.duration(seconds: minutes * 60))
        case let .workouts(minutes):
            parts.append(String(localized: "Workouts") + " " + FriendsFormat.minutes(minutes))
        case .sessions, nil:
            break
        }
        if let note { parts.append(note) }
        return parts.joined(separator: ", ")
    }
}
