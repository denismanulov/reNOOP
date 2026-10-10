//  FriendDetailView.swift
//  NOOP · Friends — one person's page: their Summary, as far as they share it. It opens as Contacts
//  opens a card (the picture and the name in the content, no title in the bar until they scroll away),
//  then reads like the wearer's own Summary: the same ‹ day › pager, the same rings card, then the night,
//  the workouts and the heart rate of that day, each a card headed by its category as the Summary's are.
//  What the person does not share is said in one line under the cards, never drawn as an empty card.

import SwiftUI
import StrandAnalytics
import StrandDesign

struct FriendDetailView: View {
    let personID: String

    @ObservedObject private var store = FriendsStore.shared
    @EnvironmentObject private var profile: ProfileStore
    @Environment(\.dismiss) private var dismiss
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue

    @State private var person: FriendProfile?
    @State private var loaded = false
    @State private var selectedDay: String?
    @State private var confirmRemove = false
    @State private var showDayPicker = false
    @Environment(\.colorScheme) private var colorScheme
    /// The hero has scrolled under the bar: the bar names the person.
    @State private var heroScrolledAway = false

    private var effortScale: EffortScale { UnitPrefs.resolveEffortScale(effortScaleRaw) }
    private var isMe: Bool { personID == store.myID }
    private var day: FriendFeedDay? {
        guard let days = person?.days else { return nil }
        return days.first(where: { $0.day == selectedDay }) ?? days.first
    }

    /// The hero's own height, roughly: past it the name is out of sight.
    private static let heroFoldOffset: CGFloat = 150

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if let person {
                    FriendsHero(name: person.name, id: person.id, own: isMe,
                                imageData: profile.avatarImageData, avatarRev: person.avatarRev)
                        .padding(.bottom, NoopMetrics.space2)
                    pager(person)
                    scores(person)
                    sleep(person)
                    workouts(person)
                    heartRate(person)
                    unshared(person)
                } else if loaded {
                    Text(store.errorText ?? String(localized: "No one has this name."))
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
                            Label("Remove Friend", systemImage: "person.badge.minus")
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

    // MARK: - Day

    /// The days the person has uploaded, newest first, and where the shown one is among them.
    private func dayIndex(_ days: [FriendFeedDay]) -> Int {
        days.firstIndex(where: { $0.day == day?.day }) ?? 0
    }

    private func dayTitle(_ key: String) -> String {
        FriendsFormat.dayLabel(key, now: Repository.logicalDay(Date()))
    }

    /// ‹ day ›, the Summary's own pager, over the days this person has uploaded.
    @ViewBuilder private func pager(_ person: FriendProfile) -> some View {
        let days = person.days ?? []
        if let shown = day {
            let index = dayIndex(days)
            DayPager(title: dayTitle(shown.day),
                     canGoBack: index + 1 < days.count, canGoForward: index > 0,
                     onBack: { selectedDay = days[index + 1].day }, onForward: { selectedDay = days[index - 1].day },
                     showPicker: $showDayPicker) {
                NavigationStack {
                    List(days) { item in
                        Button {
                            selectedDay = item.day
                            showDayPicker = false
                        } label: {
                            HStack {
                                Text(verbatim: dayTitle(item.day)).foregroundStyle(StrandPalette.textPrimary)
                                Spacer()
                                if item.day == shown.day {
                                    Image(systemName: "checkmark")
                                        .font(StrandFont.pro(15, weight: .semibold))
                                        .foregroundStyle(StrandPalette.accent)
                                }
                            }
                        }
                    }
                    .navigationTitle(Text(verbatim: person.name))
                    #if os(iOS)
                    .navigationBarTitleDisplayMode(.inline)
                    #endif
                }
            }
            .padding(.horizontal, -8)
        }
    }

    // MARK: - Sections

    /// The Summary's rings card, with this person's figures: the rings beside each score's name over its
    /// figure in the ring's hue.
    @ViewBuilder private func scores(_ person: FriendProfile) -> some View {
        if person.share?.scores == false {
            notShared(String(localized: "Doesn't share scores"))
        } else if let summary = day?.summary, FriendsFormat.hasScores(summary) {
            SummaryCard(insets: EdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)) {
                HStack(alignment: .center, spacing: 24) {
                    // Fitness sets the rings straight on its dark card; on a white card they keep the disc.
                    ActivityRingsView(rings: FriendsFormat.rings(summary, glyphs: true), diameter: 140,
                                      fitness: true, disc: colorScheme == .light)
                        .padding(.leading, 4)
                    VStack(alignment: .leading, spacing: 3.5) {
                        scoreLine(String(localized: "Charge"), FriendsFormat.percent(summary.recovery), unit: "",
                                  StrandPalette.activityMoveText)
                        scoreLine(String(localized: "Effort"), FriendsFormat.strain(summary.strain, scale: effortScale),
                                  unit: summary.strain == nil ? "" : "/" + UnitFormatter.effortScaleMax(effortScale),
                                  StrandPalette.activityExerciseText)
                        scoreLine(String(localized: "Rest"), FriendsFormat.percent(summary.sleepScore), unit: "",
                                  StrandPalette.activityStandText)
                    }
                    Spacer(minLength: 0)
                }
            }
        } else {
            notShared(String(localized: "No scores yet"))
        }
    }

    private func scoreLine(_ label: String, _ value: String, unit: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: -2.5) {
            Text(verbatim: label)
                .font(StrandFont.pro(17))
                .foregroundStyle(StrandPalette.textPrimary)
                .lineLimit(1)
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(verbatim: value).font(StrandFont.rounded(24, weight: .semibold))
                if !unit.isEmpty {
                    Text(verbatim: unit).font(StrandFont.rounded(19, weight: .semibold))
                }
            }
            .foregroundStyle(color)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
        }
        .accessibilityElement(children: .combine)
    }

    /// The night, as the Summary's Sleep card: the category row stamped with the night's hours, the time
    /// asleep, and the stages as one bar.
    @ViewBuilder private func sleep(_ person: FriendProfile) -> some View {
        if person.share?.sleep != false, let night = day?.summary.sleep {
            SummaryCard {
                VStack(alignment: .leading, spacing: 10) {
                    SummaryCardTitleRow(icon: "bed.double.fill", title: String(localized: "Sleep"),
                                        tint: StrandPalette.healthSleepDeep,
                                        trailing: "\(FriendsFormat.clock(night.startTs)) – \(FriendsFormat.clock(night.endTs))",
                                        chevron: false)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Time Asleep")
                            .font(StrandFont.footnote.weight(.semibold))
                            .foregroundStyle(StrandPalette.textSecondary)
                        SleepCardValueText(value: .duration(Double(night.asleepMin)), size: 24)
                    }
                    FriendStageBar(night: night)
                        .padding(.top, 2)
                }
            }
        }
    }

    @ViewBuilder private func workouts(_ person: FriendProfile) -> some View {
        if person.share?.workouts != false, let list = day?.summary.workouts, !list.isEmpty {
            SummaryCard(insets: EdgeInsets(top: 12, leading: 16, bottom: 4, trailing: 16)) {
                VStack(alignment: .leading, spacing: 0) {
                    SummaryCardTitleRow(icon: "figure.run", title: String(localized: "Workouts"),
                                        tint: StrandPalette.activityTitle, chevron: false)
                        .padding(.bottom, 4)
                    ForEach(Array(list.enumerated()), id: \.offset) { index, workout in
                        if index > 0 {
                            Rectangle().fill(StrandPalette.hairline).frame(height: NoopMetrics.hairlineWidth)
                                .padding(.leading, FriendWorkoutRow.iconCircle + FriendWorkoutRow.spacing)
                        }
                        FriendWorkoutRow(workout: workout, effortScale: effortScale)
                    }
                }
            }
        }
    }

    @ViewBuilder private func heartRate(_ person: FriendProfile) -> some View {
        if person.share?.hr != false, let hr = day?.summary.hr {
            SummaryCard {
                VStack(alignment: .leading, spacing: 10) {
                    SummaryCardTitleRow(icon: "heart.fill", title: String(localized: "Heart Rate"),
                                        tint: StrandPalette.healthHeart,
                                        // Health stamps a card with when its value was recorded.
                                        trailing: FriendsFormat.clock(hr.lastTs), chevron: false)
                    HStack(alignment: .firstTextBaseline, spacing: 20) {
                        hrFigure(String(localized: "Latest"), hr.lastBpm)
                        if let resting = hr.restingBpm { hrFigure(String(localized: "Resting Heart Rate"), resting) }
                        Spacer(minLength: 0)
                    }
                    if let series = hr.series, series.count >= 2 {
                        Sparkline(values: series.map { Double($0.last ?? 0) },
                                  gradient: Gradient(colors: [StrandPalette.healthHeart, StrandPalette.healthHeart]),
                                  showsHead: false, showsHover: false)
                            .frame(height: 64)
                            .accessibilityHidden(true)
                    }
                }
            }
        }
    }

    private func hrFigure(_ label: String, _ bpm: Int) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: label)
                .font(StrandFont.footnote.weight(.semibold))
                .foregroundStyle(StrandPalette.textSecondary)
            SleepCardValueText(value: .number("\(bpm)", unit: String(localized: "bpm")), size: 24)
        }
        .accessibilityElement(children: .combine)
    }

    /// What this person keeps to themselves, in one quiet line under the cards. A day that simply has no
    /// night or no workout is not remarked on, as the Summary does not remark on its own.
    @ViewBuilder private func unshared(_ person: FriendProfile) -> some View {
        let notes = [
            person.share?.sleep == false ? String(localized: "Doesn't share sleep") : nil,
            person.share?.workouts == false ? String(localized: "Doesn't share workouts") : nil,
            person.share?.hr == false ? String(localized: "Doesn't share heart rate") : nil
        ].compactMap { $0 }
        if !notes.isEmpty {
            Text(verbatim: notes.joined(separator: " · "))
                .font(StrandFont.footnote)
                .foregroundStyle(StrandPalette.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.top, NoopMetrics.space2)
        }
    }

    private func notShared(_ text: String) -> some View {
        SummaryCard(insets: .summaryCardRow) {
            Text(verbatim: text)
                .font(StrandFont.pro(15))
                .foregroundStyle(StrandPalette.textSecondary)
        }
    }
}

/// One workout, as Mail lists a message: the activity's glyph in a tinted circle, the activity with the
/// time it began across from it, and the facts on the line under. Strain is named, since a bare figure
/// does not say what it is.
struct FriendWorkoutRow: View {
    let workout: FriendsDay.Workout
    let effortScale: EffortScale

    static var iconCircle: CGFloat { 40 }
    static var spacing: CGFloat { 12 }

    // The sport travels as the sender's own label; show it in this phone's language when the catalogue
    // knows it, and as sent otherwise.
    private var sport: String { WorkoutSource.localizedSport(workout.sport) }

    var body: some View {
        HStack(alignment: .center, spacing: Self.spacing) {
            WorkoutTypeIcon(workoutType: workout.sport, size: 20, weight: .semibold,
                            color: StrandPalette.activityExerciseText)
                .frame(width: Self.iconCircle, height: Self.iconCircle)
                .background(Circle().fill(StrandPalette.fitnessCard))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(verbatim: sport)
                        .font(StrandFont.headline)
                        .foregroundStyle(StrandPalette.textPrimary)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    Text(verbatim: FriendsFormat.clock(workout.startTs))
                        .font(StrandFont.pro(13))
                        .foregroundStyle(StrandPalette.textSecondary)
                        .lineLimit(1)
                        .layoutPriority(1)
                }
                facts
                    .font(StrandFont.pro(15))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    /// "25 min · Strain 43.5 · 128 bpm", the Strain in its ring's green.
    private var facts: Text {
        var line = Text(verbatim: FriendsFormat.duration(seconds: workout.durationS))
            .foregroundColor(StrandPalette.textSecondary)
        if let strain = workout.strain {
            line = line + Text(verbatim: " · ").foregroundColor(StrandPalette.textSecondary)
                + Text(verbatim: String(localized: "Effort") + " " + FriendsFormat.strain(strain, scale: effortScale))
                    .foregroundColor(StrandPalette.activityExerciseText)
        }
        if let avg = workout.avgHr {
            line = line + Text(verbatim: " · \(avg) " + String(localized: "bpm")).foregroundColor(StrandPalette.textSecondary)
        }
        return line
    }
}

/// A night's stages as one bar in Health's sleep hues, widths by minutes, with each stage's name over
/// its length under it.
struct FriendStageBar: View {
    let night: FriendsDay.Sleep

    private var parts: [(name: String, minutes: Int, color: Color)] {
        [(String(localized: "Awake"), night.awakeMin ?? 0, StrandPalette.healthSleepAwake),
         (String(localized: "REM"), night.remMin ?? 0, StrandPalette.healthSleepRem),
         (String(localized: "Core"), night.lightMin ?? 0, StrandPalette.healthSleepCore),
         (String(localized: "Deep"), night.deepMin ?? 0, StrandPalette.healthSleepDeep)]
            .filter { $0.minutes > 0 }
    }

    var body: some View {
        let total = max(1, parts.reduce(0) { $0 + $1.minutes })
        VStack(alignment: .leading, spacing: 12) {
            GeometryReader { geo in
                HStack(spacing: 2) {
                    ForEach(parts, id: \.name) { part in
                        Capsule().fill(part.color)
                            .frame(width: max(4, (geo.size.width - CGFloat(parts.count - 1) * 2)
                                              * CGFloat(part.minutes) / CGFloat(total)))
                    }
                }
            }
            .frame(height: 10)
            .accessibilityHidden(true)
            // Two columns, the name over the length: side by side the two do not fit a column in every
            // language.
            LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading), GridItem(.flexible(), alignment: .leading)],
                      alignment: .leading, spacing: 10) {
                ForEach(parts, id: \.name) { part in
                    VStack(alignment: .leading, spacing: 1) {
                        HStack(spacing: 5) {
                            Circle().fill(part.color).frame(width: 8, height: 8)
                            Text(verbatim: part.name)
                                .font(StrandFont.pro(13))
                                .foregroundStyle(StrandPalette.textSecondary)
                                .lineLimit(1)
                        }
                        Text(verbatim: FriendsFormat.duration(seconds: part.minutes * 60))
                            .font(StrandFont.pro(15, weight: .semibold))
                            .foregroundStyle(StrandPalette.textPrimary)
                            .lineLimit(1)
                            // Under the name, past the dot.
                            .padding(.leading, 13)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }
}
