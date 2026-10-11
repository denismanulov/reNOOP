//  FriendDetailView.swift
//  NOOP · Friends — one person's page, laid out as the Fitness app's page for a friend in iOS 26: the
//  picture and the name, the day's three figures with the rings beside them, two amounts under a rule,
//  the last seven days as seven small rings, then their recent workouts and a quiet line of what they
//  keep to themselves. The metrics are Fitness's, measured from the app (iOS 26.5).
//
//  Fitness shows today. Here a day of the last seven can be picked from its ring, since a friend's
//  newest day is not always today; the page opens on the newest.

import SwiftUI
import StrandAnalytics
import StrandDesign

/// The seven days a person's page covers, oldest first.
enum FriendWeek {
    struct Day: Identifiable, Equatable {
        /// "yyyy-MM-dd".
        let key: String
        /// What the person uploaded for the day, when they did.
        let summary: FriendsDay?
        var id: String { key }
    }

    /// The seven days ending on `anchorKey`, each with what was uploaded for it. Pure. A key that is
    /// not a date yields no days.
    static func days(ending anchorKey: String, uploaded: [FriendFeedDay]) -> [Day] {
        guard let anchor = FriendsFormat.date(anchorKey) else { return [] }
        let byKey = Dictionary(uploaded.map { ($0.day, $0.summary) }, uniquingKeysWith: { first, _ in first })
        return (0..<7).reversed().compactMap { back in
            guard let date = FriendsFormat.calendar.date(byAdding: .day, value: -back, to: anchor) else { return nil }
            let key = FriendsFormat.key(date)
            return Day(key: key, summary: byKey[key])
        }
    }

    /// The day the week ends on: the wearer's own day, or the person's newest when their calendar is
    /// already a day ahead.
    static func anchor(todayKey: String, newest: String?) -> String {
        guard let newest, newest > todayKey else { return todayKey }
        return newest
    }
}

struct FriendDetailView: View {
    let personID: String

    @ObservedObject private var store = FriendsStore.shared
    @EnvironmentObject private var profile: ProfileStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dts
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue
    @ScaledMetric(relativeTo: .title2) private var rowFigureSize: CGFloat = FriendsStyle.rowFigureSize

    @State private var person: FriendProfile?
    @State private var loaded = false
    @State private var selectedDay: String?
    @State private var confirmRemove = false
    /// The hero has scrolled under the bar: the bar names the person.
    @State private var heroScrolledAway = false

    private var effortScale: EffortScale { UnitPrefs.resolveEffortScale(effortScaleRaw) }
    private var isMe: Bool { personID == store.myID }
    private var day: FriendFeedDay? {
        guard let days = person?.days else { return nil }
        return days.first(where: { $0.day == selectedDay }) ?? days.first
    }

    /// The hero's own height, roughly: past it the name is out of sight.
    private static let heroFoldOffset: CGFloat = 190

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if let person {
                    FriendsHero(name: person.name, id: person.id, own: isMe,
                                imageData: profile.avatarImageData, avatarRev: person.avatarRev, large: true)
                        .padding(.top, 23)
                    today(person)
                    week(person)
                    workouts(person)
                    unshared(person)
                } else if loaded {
                    Text(store.errorText ?? String(localized: "This person is no longer here."))
                        .font(StrandFont.pro(15))
                        .foregroundStyle(StrandPalette.textSecondary)
                        .frame(maxWidth: .infinity)
                        .padding(.top, NoopMetrics.space8)
                } else {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, NoopMetrics.space8)
                }
            }
            .padding(.horizontal, FriendsStyle.gutter)
            .padding(.bottom, NoopMetrics.space8)
            #if os(macOS)
            .frame(maxWidth: 680)
            .frame(maxWidth: .infinity)
            #endif
        }
        .background(StrandPalette.summaryCanvas.ignoresSafeArea())
        #if os(iOS)
        .softTopEdge()
        .onScrolledPast(Self.heroFoldOffset) { away in
            withAnimation(.easeInOut(duration: 0.2)) { heroScrolledAway = away }
        }
        #endif
        .navigationTitle(Text(verbatim: person?.name ?? ""))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(verbatim: person?.name ?? "")
                    .font(.headline)
                    .foregroundStyle(StrandPalette.textPrimary)
                    .opacity(heroScrolledAway ? 1 : 0)
                    .accessibilityHidden(!heroScrolledAway)
            }
            if person != nil, !isMe {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button(role: .destructive) { confirmRemove = true } label: {
                            Label("Remove Friend", systemImage: "person.2.badge.minus")
                        }
                    } label: {
                        // The glyph in the label colour, as the bar's other buttons are; set here and
                        // not as the menu's tint, which would grey the destructive row's icon too.
                        Image(systemName: "ellipsis").foregroundStyle(StrandPalette.textPrimary)
                    }
                    .accessibilityLabel(Text("More"))
                    // The dialog hangs off the button that asks for it, where iOS 26 points it.
                    .confirmationDialog("Remove Friend", isPresented: $confirmRemove, titleVisibility: .visible) {
                        Button("Remove Friend", role: .destructive) {
                            Task { await store.unfriend(personID); dismiss() }
                        }
                    } message: {
                        Text("You stop seeing each other's days. Either of you can invite the other again later.")
                    }
                }
            }
        }
        .task(id: personID) {
            person = await store.person(personID)
            selectedDay = person?.days?.first?.day
            loaded = true
        }
    }

    // MARK: - The day

    private func dayTitle(_ key: String) -> String {
        FriendsFormat.dayLabel(key, now: Repository.logicalDay(Date()))
    }

    /// The shown day under its name: the three figures down the leading side with a rule under each and
    /// the rings beside them, then the amounts behind the day in two columns over a full rule.
    @ViewBuilder private func today(_ person: FriendProfile) -> some View {
        let sharesScores = person.share?.scores != false
        let summary = sharesScores ? day?.summary : nil
        Text(verbatim: day.map { dayTitle($0.day) } ?? String(localized: "Today"))
            .font(StrandFont.pro(17))
            .foregroundStyle(StrandPalette.textSecondary)
            .padding(.leading, 2)
            .padding(.top, 51.3)
            .accessibilityAddTraits(.isHeader)
        let layout = dts.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 16))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 20.67))
        layout {
            VStack(alignment: .leading, spacing: 0) {
                figureRow("Charge", FriendsFigure.text(FriendsFormat.percent(summary?.recovery), size: rowFigureSize),
                          StrandPalette.activityMoveText)
                figureRow("Effort", strainFigure(summary?.strain), StrandPalette.activityExerciseText)
                figureRow("Rest", FriendsFigure.text(FriendsFormat.percent(summary?.sleepScore), size: rowFigureSize),
                          StrandPalette.activityStandText)
            }
            // Fitness is dark-only and sets the rings straight on its page; on a light page they keep the
            // black disc Health gives its rings, since the hues are made for black.
            ActivityRingsView(rings: FriendsFormat.rings(summary, glyphs: false),
                              diameter: FriendsStyle.pageRings.diameter, fitness: true, disc: colorScheme == .light,
                              stroke: FriendsStyle.pageRings.stroke, gap: FriendsStyle.pageRings.gap)
                .padding(.top, 4.33)
                .padding(.trailing, 4.67)
        }
        .padding(.top, 11.9)
        amounts(person)
    }

    /// "71.0/100": Strain is not a percentage, so its scale stands behind it as a goal stands behind
    /// Fitness's amounts.
    private func strainFigure(_ strain: Double?) -> Text {
        guard let strain else { return FriendsFigure.text(SummaryMetricReading.noValue, size: rowFigureSize) }
        return FriendsFigure.text(FriendsFormat.strain(strain, scale: effortScale) + "/"
                                  + UnitFormatter.effortScaleMax(effortScale), size: rowFigureSize)
    }

    /// One of the day's three figures: its name over the figure in its ring's hue, a rule under.
    private func figureRow(_ label: LocalizedStringKey, _ figure: Text, _ tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            labelled(label, figure, tint)
                .padding(.leading, 4)
                .padding(.top, 1.8)
            Spacer(minLength: 3.3)
            FriendsRule()
        }
        .frame(minHeight: 47)
        .accessibilityElement(children: .combine)
    }

    /// A name over its figure, set as close as Fitness sets them.
    private func labelled(_ label: LocalizedStringKey, _ figure: Text, _ tint: Color) -> some View {
        VStack(alignment: .leading, spacing: -5) {
            Text(label)
                .font(StrandFont.pro(17))
                .foregroundStyle(StrandPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
            figure
                .foregroundStyle(tint)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }

    /// The amounts behind the day, in grey as Fitness sets Steps and Distance: the time asleep and the
    /// minutes of workouts, and under them the heart rate where the person shares it.
    @ViewBuilder private func amounts(_ person: FriendProfile) -> some View {
        let summary = day?.summary
        let night = person.share?.sleep != false ? summary?.sleep : nil
        let sessions = person.share?.workouts != false ? summary?.workouts : nil
        if night != nil || sessions != nil {
            amountPair(
                ("Time Asleep", night.map { FriendsFigure.duration(minutes: $0.asleepMin, size: rowFigureSize) }),
                ("Workouts", sessions.map { list in
                    let minutes = Int((Double(list.reduce(0) { $0 + $1.durationS }) / 60).rounded())
                    return FriendsFigure.text("\(minutes)", unit: String(localized: "sleep.unit.min", defaultValue: "min"),
                                              size: rowFigureSize)
                }))
        }
        if person.share?.hr != false, let hr = summary?.hr {
            let bpm = String(localized: "bpm")
            amountPair(("Heart Rate", FriendsFigure.text("\(hr.lastBpm)", unit: bpm, size: rowFigureSize)),
                       ("Resting Heart Rate", hr.restingBpm.map { FriendsFigure.text("\($0)", unit: bpm, size: rowFigureSize) }))
        }
    }

    /// Two amounts side by side over a rule that runs the page's width. The second starts where the
    /// rings above it start, as Fitness lines Distance up.
    private func amountPair(_ first: (LocalizedStringKey, Text?), _ second: (LocalizedStringKey, Text?)) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 20.67) {
                amount(first)
                amount(second)
                    .frame(width: dts.isAccessibilitySize ? nil : FriendsStyle.pageRings.diameter + 4.67,
                           alignment: .leading)
            }
            .padding(.leading, 4)
            .padding(.top, 16.1)
            Spacer(minLength: 2.6)
            FriendsRule()
        }
        .frame(minHeight: 61.33)
    }

    @ViewBuilder private func amount(_ item: (LocalizedStringKey, Text?)) -> some View {
        if let figure = item.1 {
            labelled(item.0, figure, StrandPalette.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
        } else {
            Color.clear.frame(maxWidth: .infinity, maxHeight: 1)
        }
    }

    // MARK: - The last seven days

    /// Seven small rings under the letters of their weekdays. The shown day's letter stands on a disc,
    /// as Fitness marks today's; a ring picks its day.
    @ViewBuilder private func week(_ person: FriendProfile) -> some View {
        let todayKey = FriendsFormat.todayKey()
        let days = FriendWeek.days(ending: FriendWeek.anchor(todayKey: todayKey, newest: person.latestDay?.day),
                                   uploaded: person.share?.scores == false ? [] : person.days ?? [])
        if !days.isEmpty {
            Text("friends.last7Days")
                .font(StrandFont.pro(17))
                .foregroundStyle(StrandPalette.textSecondary)
                .padding(.leading, 2)
                .padding(.top, 33.1)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 0) {
                ForEach(days) { item in
                    Button { selectedDay = item.key } label: {
                        weekDay(item, shown: item.key == day?.day)
                    }
                    .buttonStyle(.plain)
                    .disabled(item.summary == nil)
                    .frame(maxWidth: .infinity)
                }
            }
            // Fitness runs the seven columns wider than the page's margin.
            .padding(.horizontal, -11)
            .padding(.top, 9.7)
        }
    }

    private func weekDay(_ item: FriendWeek.Day, shown: Bool) -> some View {
        VStack(spacing: 3.83) {
            Text(verbatim: FriendsFormat.weekdayLetter(item.key))
                .font(StrandFont.pro(12, weight: .semibold))
                .foregroundStyle(shown ? Color.white : StrandPalette.textSecondary)
                .frame(width: 20, height: 20)
                .background { if shown { Circle().fill(StrandPalette.activityMoveEnd) } }
            ActivityRingsView(rings: FriendsFormat.rings(item.summary, glyphs: false),
                              diameter: FriendsStyle.dayRings.diameter, fitness: true, disc: colorScheme == .light,
                              stroke: FriendsStyle.dayRings.stroke, gap: FriendsStyle.dayRings.gap)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: weekDaySpoken(item)))
        .accessibilityAddTraits(shown ? .isSelected : [])
    }

    private func weekDaySpoken(_ item: FriendWeek.Day) -> String {
        var parts = [dayTitle(item.key)]
        if let summary = item.summary {
            if let recovery = summary.recovery { parts.append(String(localized: "Charge") + " " + FriendsFormat.percent(recovery)) }
            if let strain = summary.strain { parts.append(String(localized: "Effort") + " " + FriendsFormat.strain(strain, scale: effortScale)) }
            if let sleep = summary.sleepScore { parts.append(String(localized: "Rest") + " " + FriendsFormat.percent(sleep)) }
        } else {
            parts.append(String(localized: "No data yet"))
        }
        return parts.joined(separator: ", ")
    }

    // MARK: - Workouts

    /// The workouts of the days the page covers, newest first, each on the day it was uploaded for.
    private func recentWorkouts(_ person: FriendProfile) -> [(day: String, workout: FriendsDay.Workout)] {
        guard person.share?.workouts != false else { return [] }
        return (person.days ?? [])
            .flatMap { day in (day.summary.workouts ?? []).map { (day: day.day, workout: $0) } }
            .sorted { $0.workout.startTs > $1.workout.startTs }
    }

    @ViewBuilder private func workouts(_ person: FriendProfile) -> some View {
        let list = recentWorkouts(person)
        VStack(spacing: 0) {
            ForEach(Array(list.enumerated()), id: \.offset) { index, item in
                FriendWorkoutRow(workout: item.workout,
                                 dayLabel: FriendsFormat.weekdayLabel(item.day, now: Repository.logicalDay(Date())))
                    .padding(.top, index == 0 ? 60 : 26)
            }
        }
        .padding(.horizontal, 16)
    }

    /// What this person keeps to themselves, in one quiet line under the page. A day that simply has no
    /// night or no workout is not remarked on.
    @ViewBuilder private func unshared(_ person: FriendProfile) -> some View {
        let notes = [
            person.share?.scores == false ? String(localized: "Doesn't share scores") : nil,
            person.share?.sleep == false ? String(localized: "Doesn't share sleep") : nil,
            person.share?.workouts == false ? String(localized: "Doesn't share workouts") : nil,
            person.share?.hr == false ? String(localized: "Doesn't share heart rate") : nil
        ].compactMap { $0 }
        if !notes.isEmpty {
            Text(verbatim: notes.joined(separator: " · "))
                .font(StrandFont.pro(13))
                .foregroundStyle(StrandPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 5.3)
                .padding(.top, 13.1)
        }
    }
}

/// One workout as Fitness lists a friend's: a closed ring round the activity's glyph, the activity over
/// how long it ran in the ring's hue, the day on the trailing side, and a rule under.
struct FriendWorkoutRow: View {
    let workout: FriendsDay.Workout
    /// "Today", "Yesterday" or the weekday.
    let dayLabel: String

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .title) private var figureSize: CGFloat = FriendsStyle.figureSize

    // The sport travels as the sender's own label; show it in this phone's language when the catalogue
    // knows it, and as sent otherwise.
    private var sport: String { WorkoutSource.localizedSport(workout.sport) }
    private static var side: CGFloat { FriendsStyle.workoutRing.diameter }

    var body: some View {
        VStack(spacing: 16.33) {
            HStack(alignment: .top, spacing: 8.33) {
                ZStack {
                    ActivityRingsView(rings: [ActivityRing(id: "time", fraction: 1,
                                                           start: StrandPalette.activityTimeStart,
                                                           end: StrandPalette.activityTimeEnd)],
                                      diameter: Self.side, fitness: true, disc: colorScheme == .light,
                                      stroke: FriendsStyle.workoutRing.stroke)
                    WorkoutTypeIcon(workoutType: workout.sport, size: 20, weight: .semibold,
                                    color: StrandPalette.activityTimeEnd)
                }
                .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 0.4) {
                    Text(verbatim: sport)
                        .font(StrandFont.pro(17))
                        .foregroundStyle(StrandPalette.textPrimary)
                        .lineLimit(1)
                    FriendsFigure.text(FriendsFormat.clockDuration(seconds: workout.durationS), size: figureSize)
                        .foregroundStyle(StrandPalette.activityTimeText)
                        .lineLimit(1)
                }
                // The name's capitals start at the ring's top and the figure stands on its foot.
                .padding(.top, -2.4)
                .frame(height: dts.isAccessibilitySize ? nil : Self.side, alignment: .top)
                Spacer(minLength: 8)
                Text(verbatim: dayLabel)
                    .font(StrandFont.pro(13))
                    .foregroundStyle(StrandPalette.textSecondary)
                    .lineLimit(1)
                    .frame(height: dts.isAccessibilitySize ? nil : Self.side, alignment: .bottom)
                    .offset(y: 3.4)
            }
            .padding(.leading, 0.33)
            FriendsRule(color: StrandPalette.fitnessListRule)
        }
        .friendsFixedMeasure(dts)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: [sport, FriendsFormat.duration(seconds: workout.durationS), dayLabel]
            .joined(separator: ", ")))
    }
}
