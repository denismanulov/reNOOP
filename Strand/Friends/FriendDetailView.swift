//  FriendDetailView.swift
//  NOOP · Friends — one person's page, as Fitness shows a friend: their rings for the last seven days,
//  the picked day's figures, then what they did that day. A section the person does not share is said
//  in a line, never drawn as an empty card.

import SwiftUI
import StrandAnalytics
import StrandDesign

struct FriendDetailView: View {
    let nick: String

    @ObservedObject private var store = FriendsStore.shared
    @Environment(\.dismiss) private var dismiss
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue

    @State private var person: FriendProfile?
    @State private var loaded = false
    @State private var selectedDay: String?
    @State private var confirmRemove = false

    private var effortScale: EffortScale { UnitPrefs.resolveEffortScale(effortScaleRaw) }
    private var isMe: Bool { nick == store.nick }
    private var day: FriendFeedDay? {
        guard let days = person?.days else { return nil }
        return days.first(where: { $0.day == selectedDay }) ?? days.first
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if let person {
                    header(person)
                    week(person)
                    scores(person)
                    sleep(person)
                    workouts(person)
                    heartRate(person)
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
            .padding(.horizontal, 20)
            .padding(.bottom, NoopMetrics.space8)
            #if os(macOS)
            .frame(maxWidth: 680)
            .frame(maxWidth: .infinity)
            #endif
        }
        .summaryBackdrop()
        .navigationTitle(Text(verbatim: person?.name ?? ""))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            if person != nil, !isMe {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button(role: .destructive) { confirmRemove = true } label: {
                            Label("Remove Friend", systemImage: "person.badge.minus")
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                    }
                    .accessibilityLabel(Text("More"))
                }
            }
        }
        .confirmationDialog("Remove Friend", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove Friend", role: .destructive) {
                Task { await store.unfriend(nick); dismiss() }
            }
        } message: {
            Text("You stop seeing each other's days. Either of you can send a new request later.")
        }
        .task(id: nick) {
            person = await store.person(nick)
            selectedDay = person?.days?.first?.day
            loaded = true
        }
    }

    // MARK: - Header and week

    private func header(_ person: FriendProfile) -> some View {
        VStack(spacing: 6) {
            FriendAvatar(name: person.name, size: 76)
            Text(verbatim: person.name)
                .font(StrandFont.pro(22, weight: .bold))
                .foregroundStyle(StrandPalette.textPrimary)
            Text(verbatim: "@" + person.nick)
                .font(StrandFont.pro(15))
                .foregroundStyle(StrandPalette.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, NoopMetrics.space3)
        .accessibilityElement(children: .combine)
    }

    /// The last seven days as small rings, oldest on the left; a tap picks the day the page shows.
    private func week(_ person: FriendProfile) -> some View {
        let days = FriendWeek.days(ending: Date())
        let byKey = Dictionary((person.days ?? []).map { ($0.day, $0) }, uniquingKeysWith: { first, _ in first })
        return SummaryCard(insets: EdgeInsets(top: 12, leading: 8, bottom: 12, trailing: 8)) {
            HStack(spacing: 0) {
                ForEach(days, id: \.key) { slot in
                    let picked = slot.key == day?.day
                    Button { selectedDay = slot.key } label: {
                        VStack(spacing: 6) {
                            Text(verbatim: slot.letter)
                                .font(StrandFont.pro(11, weight: .semibold))
                                .foregroundStyle(picked ? StrandPalette.textPrimary : StrandPalette.textSecondary)
                            ActivityRingsView(rings: FriendsFormat.rings(byKey[slot.key]?.summary, glyphs: false),
                                              diameter: 34)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                        .background(picked ? StrandPalette.hairline : Color.clear,
                                    in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .disabled(byKey[slot.key] == nil)
                    .accessibilityLabel(Text(verbatim: FriendsFormat.dayLabel(slot.key)))
                    .accessibilityAddTraits(picked ? .isSelected : [])
                }
            }
        }
    }

    // MARK: - Sections

    @ViewBuilder private func scores(_ person: FriendProfile) -> some View {
        FriendsSectionTitle(title: "Scores", subtitle: day.map { FriendsFormat.dayLabel($0.day) })
        if person.share?.scores == false {
            notShared(String(localized: "Doesn't share scores"))
        } else if let summary = day?.summary, FriendsFormat.hasScores(summary) {
            SummaryCard {
                HStack(alignment: .center, spacing: 16) {
                    ActivityRingsView(rings: FriendsFormat.rings(summary, glyphs: true), diameter: 112)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 8) {
                        scoreLine(String(localized: "Charge"), FriendsFormat.percent(summary.recovery),
                                  StrandPalette.activityMoveText)
                        scoreLine(String(localized: "Effort"),
                                  summary.strain == nil ? SummaryMetricReading.noValue
                                      : FriendsFormat.strain(summary.strain, scale: effortScale) + "/"
                                        + UnitFormatter.effortScaleMax(effortScale),
                                  StrandPalette.activityExerciseText)
                        scoreLine(String(localized: "Rest"), FriendsFormat.percent(summary.sleepScore),
                                  StrandPalette.activityStandText)
                    }
                    Spacer(minLength: 0)
                }
            }
        } else {
            notShared(String(localized: "No scores yet"))
        }
    }

    private func scoreLine(_ label: String, _ value: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: label)
                .font(StrandFont.pro(15))
                .foregroundStyle(StrandPalette.textPrimary)
            Text(verbatim: value)
                .font(StrandFont.rounded(24, weight: .semibold))
                .foregroundStyle(color)
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func sleep(_ person: FriendProfile) -> some View {
        FriendsSectionTitle(title: "Sleep")
        if person.share?.sleep == false {
            notShared(String(localized: "Doesn't share sleep"))
        } else if let night = day?.summary.sleep {
            SummaryCard {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Time Asleep")
                                .font(StrandFont.footnote.weight(.semibold))
                                .foregroundStyle(StrandPalette.textSecondary)
                            SleepCardValueText(value: .duration(Double(night.asleepMin)), size: 24)
                        }
                        Spacer(minLength: 8)
                        Text(verbatim: "\(FriendsFormat.clock(night.startTs)) – \(FriendsFormat.clock(night.endTs))")
                            .font(StrandFont.pro(15))
                            .foregroundStyle(StrandPalette.textSecondary)
                    }
                    FriendStageBar(night: night)
                }
            }
        } else {
            notShared(String(localized: "No sleep on this day"))
        }
    }

    @ViewBuilder private func workouts(_ person: FriendProfile) -> some View {
        FriendsSectionTitle(title: "Workouts")
        if person.share?.workouts == false {
            notShared(String(localized: "Doesn't share workouts"))
        } else if let list = day?.summary.workouts, !list.isEmpty {
            SummaryCard(insets: .summaryCardList) {
                VStack(spacing: 0) {
                    ForEach(Array(list.enumerated()), id: \.offset) { index, workout in
                        if index > 0 {
                            Rectangle().fill(StrandPalette.hairline).frame(height: NoopMetrics.hairlineWidth)
                        }
                        workoutRow(workout)
                    }
                }
            }
        } else {
            notShared(String(localized: "No workouts on this day"))
        }
    }

    private func workoutRow(_ workout: FriendsDay.Workout) -> some View {
        var facts: [String] = [SleepCardValueFormat.duration(seconds: workout.durationS)]
        if let strain = workout.strain {
            facts.append(String(localized: "Effort") + " " + FriendsFormat.strain(strain, scale: effortScale))
        }
        if let avg = workout.avgHr { facts.append("\(avg) " + String(localized: "bpm")) }
        if let kcal = workout.kcal { facts.append("\(kcal) " + String(localized: "kcal")) }
        return HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                // The sport travels as the sender's own label; show it in this phone's language when
                // the catalogue knows it, and as sent otherwise.
                Text(verbatim: String(localized: String.LocalizationValue(workout.sport)))
                    .font(StrandFont.pro(17))
                    .foregroundStyle(StrandPalette.textPrimary)
                Text(verbatim: facts.joined(separator: " · "))
                    .font(StrandFont.pro(15))
                    .foregroundStyle(StrandPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Text(verbatim: FriendsFormat.clock(workout.startTs))
                .font(StrandFont.pro(15))
                .foregroundStyle(StrandPalette.textSecondary)
        }
        .padding(.vertical, 11)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private func heartRate(_ person: FriendProfile) -> some View {
        FriendsSectionTitle(title: "Heart Rate")
        if person.share?.hr == false {
            notShared(String(localized: "Doesn't share heart rate"))
        } else if let hr = day?.summary.hr {
            SummaryCard {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(alignment: .firstTextBaseline, spacing: 16) {
                        hrFigure(String(localized: "Latest"), hr.lastBpm, stamp: FriendsFormat.clock(hr.lastTs))
                        if let resting = hr.restingBpm { hrFigure(String(localized: "Resting Heart Rate"), resting, stamp: nil) }
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
        } else {
            notShared(String(localized: "No heart rate on this day"))
        }
    }

    private func hrFigure(_ label: String, _ bpm: Int, stamp: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: stamp.map { "\(label) · \($0)" } ?? label)
                .font(StrandFont.footnote.weight(.semibold))
                .foregroundStyle(StrandPalette.textSecondary)
            SleepCardValueText(value: .number("\(bpm)", unit: String(localized: "bpm")), size: 24,
                               tint: StrandPalette.healthHeart)
        }
        .accessibilityElement(children: .combine)
    }

    private func notShared(_ text: String) -> some View {
        Text(verbatim: text)
            .font(StrandFont.pro(15))
            .foregroundStyle(StrandPalette.textSecondary)
            .padding(.horizontal, 4)
            .padding(.bottom, 2)
    }
}

/// A night's stages as one bar in Health's sleep hues, widths by minutes, with a legend under it.
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
        VStack(alignment: .leading, spacing: 8) {
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
            // Two columns: four names with their durations do not fit one line in every language.
            LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading), GridItem(.flexible(), alignment: .leading)],
                      alignment: .leading, spacing: 4) {
                ForEach(parts, id: \.name) { part in
                    HStack(spacing: 6) {
                        Circle().fill(part.color).frame(width: 8, height: 8)
                        Text(verbatim: part.name)
                            .font(StrandFont.pro(13))
                            .foregroundStyle(StrandPalette.textSecondary)
                        Text(verbatim: SleepCardValueFormat.duration(seconds: part.minutes * 60))
                            .font(StrandFont.pro(13, weight: .semibold))
                            .foregroundStyle(StrandPalette.textPrimary)
                    }
                    .lineLimit(1)
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }
}

/// The seven calendar days ending today, oldest first, each with its key and weekday letter.
enum FriendWeek {
    static func days(ending now: Date, calendar: Calendar = .current) -> [(key: String, letter: String)] {
        let symbols = calendar.veryShortStandaloneWeekdaySymbols
        var localized = calendar
        localized.locale = AppLanguage.activeLocale
        let letters = localized.veryShortStandaloneWeekdaySymbols
        return (0..<7).reversed().compactMap { back in
            guard let date = calendar.date(byAdding: .day, value: -back, to: now) else { return nil }
            let weekday = calendar.component(.weekday, from: date) - 1
            let letter = letters.indices.contains(weekday) ? letters[weekday] : symbols[weekday]
            return (Repository.localDayKey(date), letter)
        }
    }
}

/// "1 h 12 min" / "45 min", in the active language.
enum SleepCardValueFormat {
    static func duration(seconds: Int) -> String {
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = .abbreviated
        formatter.allowedUnits = seconds >= 3600 ? [.hour, .minute] : [.minute]
        var calendar = Calendar.current
        calendar.locale = AppLanguage.activeLocale
        formatter.calendar = calendar
        return formatter.string(from: TimeInterval(max(60, seconds))) ?? "\(seconds / 60)"
    }
}
