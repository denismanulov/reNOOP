//  SleepHealthView.swift
//  NOOP · Sleep — the tab root, laid out like the Sleep Score page of the iOS 26 Health app.
//
//  The score card (ring, word, the parts' points, a sentence; swipe for another night, tap for the score's
//  history), then the Sleep tile (opens `SleepMoreDataView`: the D / W / M / 6M chart with Stages ·
//  Amounts · Comparisons) beside the Vitals tile (opens `SleepVitalsView`), Highlights, and Options (the
//  sleep schedule). The ••• menu edits the night, adds a nap and logs sleep marks. The night data comes
//  from the same `SleepModel` pipeline every other sleep surface reads.

import SwiftUI
import StrandDesign
import StrandAnalytics
import WhoopStore

struct SleepHealthView: View {
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var live: LiveState
    @EnvironmentObject private var intelligence: IntelligenceEngine
    @EnvironmentObject private var app: AppModel
    @Environment(\.scrollToTopSignal) private var scrollToTopSignal
    @ScaledMetric(relativeTo: .body) private var chevronSize: CGFloat = 14

    @State private var range: SleepRange = Self.initialRange
    /// 0 = the newest night, 1 = the one before, … (days in `navDays`).
    @State private var nightOffset = 0
    @State private var showMoreData = Self.initialShowMore
    @State private var showAllHighlights = false
    @State private var wakeEdit: WakeEdit?
    @State private var addNap: AddNapSeed?
    @State private var sleepUndo: SleepUndo?

    // Loaded inputs, as the full Sleep screen loads them.
    @State private var allSessions: [CachedSleepSession] = []
    @State private var habitualMidsleepSec: Int?
    @State private var motionByStart: [Int: [Double]] = [:]
    @State private var navDays: [[CachedSleepSession]] = []
    @State private var model: SleepModel?
    @State private var night: Night?
    @State private var entries: [SleepNightEntry] = []

    private static let topAnchorID = "sleepHealth.top"

    /// The night to open on ("yyyy-MM-dd", the day it ended), when a link names one — the Summary's Sleep
    /// card on a past day. nil opens on the newest night.
    private let initialWakeDay: String?
    @State private var appliedInitialWakeDay = false

    init(initialWakeDay: String? = nil) {
        self.initialWakeDay = initialWakeDay
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 0) {
                    Color.clear.frame(height: 0).id(Self.topAnchorID)
                    pageContent
                        .padding(.horizontal, NoopMetrics.screenHPadding)
                        .padding(.top, NoopMetrics.space2)
                        .padding(.bottom, NoopMetrics.space8)
                }
                #if os(macOS)
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
                #endif
            }
            .background(StrandPalette.sleepScoreCanvas.ignoresSafeArea())
            .environment(\.summaryCardFill, StrandPalette.sleepScoreCard)
            #if os(iOS)
            .onChange(of: scrollToTopSignal) { _, _ in
                withAnimation(.easeOut(duration: 0.35)) { proxy.scrollTo(Self.topAnchorID, anchor: .top) }
            }
            #endif
        }
        .navigationTitle(Text("Sleep Score"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) { moreMenu.barGlyph() }
        }
        .navigationDestination(isPresented: $showMoreData) { moreData }
        .navigationDestination(isPresented: $showAllHighlights) { allHighlights }
        .sheet(item: $wakeEdit) { edit in editSheet(edit) }
        .sheet(item: $addNap) { seed in napSheet(seed) }
        .refreshable { await repo.refresh() }
        .task(id: repo.refreshSeq) { await load() }
        // The undo stays until ✕ or Undo, never on a timer; leaving the screen puts it away.
        .onDisappear { sleepUndo = nil }
        .onChangeCompat(of: nightOffset) { offset in
            night = offset == 0 ? model?.night
                : SleepModel.decodedNight(at: offset, navDays: navDays,
                                          habitualMidsleepSec: habitualMidsleepSec, motionByStart: motionByStart)
        }
    }

    // MARK: - Night navigation

    /// Right = an older night, left = a newer one; clamped to the nights on record.
    private var nightSwipe: some Gesture {
        DragGesture(minimumDistance: 24)
            .onEnded { value in
                let dx = value.translation.width
                guard abs(dx) > 50, abs(dx) > abs(value.translation.height) * 1.5 else { return }
                let next = min(max(0, nightOffset + (dx > 0 ? 1 : -1)), max(0, navDays.count - 1))
                if next != nightOffset { withAnimation(StrandMotion.interactive) { nightOffset = next } }
            }
    }

    // MARK: - Ranges

    /// "20 Sep – 26 Sep 2026": the span a range window covers.
    static func rangeLabel(_ window: SleepRangeWindow) -> String {
        guard let first = window.slotStarts.first else { return "" }
        let last = window.range == .sixMonths ? Date() : (window.slotStarts.last ?? first)
        let locale = AppLanguage.activeLocale
        return "\(first.formatted(.dateTime.day().month(.abbreviated).locale(locale))) – \(last.formatted(.dateTime.day().month(.abbreviated).year().locale(locale)))"
    }

    // MARK: - Page

    private var pageContent: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let sleepUndo { undoBanner(sleepUndo) }
            SleepFreshnessNote(latestWakeTs: model?.night.session.endTs)
            Group {
                if let night, night.stages.asleep > 0 {
                    if let score = score(for: night) {
                        NavigationLink(value: TabRoute.metric("sleep_performance")) {
                            SleepScoreCard(score: score)
                        }
                        .buttonStyle(.plain)
                    }
                    HStack(alignment: .top, spacing: 10) {
                        Button { showMoreData = true } label: {
                            SleepDurationTile(intervals: nightIntervals(night), onset: night.onsetDate,
                                              asleepMinutes: night.stages.asleep)
                        }
                        .buttonStyle(.plain)
                        NavigationLink(value: TabRoute.sleepVitals(day: wakeDayKey(night), wakeTs: night.session.endTs)) {
                            SleepVitalsTile(vitals: vitals(for: night))
                        }
                        .buttonStyle(.plain)
                    }
                    // Both tiles take the taller one's height, as Health's pair does.
                    .fixedSize(horizontal: false, vertical: true)
                } else {
                    SummaryCard {
                        Text("No sleep data")
                            .font(StrandFont.subhead)
                            .foregroundStyle(StrandPalette.textSecondary)
                            .frame(maxWidth: .infinity, minHeight: 120)
                    }
                }
            }
            .contentShape(Rectangle())
            .gesture(nightSwipe)
            highlightsSection
            optionsSection
        }
    }

    /// The newest night draws the model's own timeline; an older one its decoded blocks.
    private func nightIntervals(_ night: Night) -> [SleepInterval] {
        nightOffset == 0 ? (model?.intervals ?? night.intervals) : night.intervals
    }

    private func vitals(for night: Night) -> SleepVitals {
        SleepVitals.make(rows: repo.days, day: wakeDayKey(night))
    }

    /// The Sleep tile's page: the D / W / M / 6M chart with Stages · Amounts · Comparisons.
    private var moreData: some View {
        SleepMoreDataView(navDays: navDays, habitualMidsleepSec: habitualMidsleepSec,
                          motionByStart: motionByStart, typicalStageMin: typicalStageMin,
                          sleepDebtLedger: model?.sleepDebtLedger,
                          range: range, nightOffset: nightOffset, tab: Self.initialMoreTab)
    }

    /// The day's blocks outside the bridged main night: its naps (#508, #555).
    static func naps(_ night: Night) -> [CachedSleepSession] {
        let groupStarts = night.mainGroupStarts
        return night.sourceBlocks
            .filter { !groupStarts.contains($0.startTs) }
            .sorted { $0.effectiveStartTs < $1.effectiveStartTs }
    }

    static func napMinutes(_ night: Night) -> Double {
        naps(night).reduce(0) { $0 + Double($1.endTs - $1.effectiveStartTs) / 60 }
    }

    private func wakeDayKey(_ night: Night) -> String {
        Repository.localDayKey(Date(timeIntervalSince1970: TimeInterval(night.session.endTs)))
    }

    private func dailyRow(for night: Night) -> DailyMetric? {
        let key = wakeDayKey(night)
        return repo.days.last(where: { $0.day == key })
    }

    /// The night's score as every other surface reads it: the imported figure for its wake-day, else the
    /// Rest composite of that day's row (`SleepModel.performanceSeries`).
    private func score(for night: Night) -> SleepScore? {
        SleepScore.make(daily: dailyRow(for: night),
                        importedPct: repo.importedSleep[wakeDayKey(night)]?.performancePct)
    }

    private var typicalStageMin: [SleepStage: Double] {
        guard let model else { return [:] }
        var out: [SleepStage: Double] = [:]
        out[.deep] = model.typicalDeepMin
        out[.rem] = model.typicalRemMin
        out[.light] = model.typicalLightMin
        return out
    }

    // MARK: - Editing (sheets from the ••• menu)

    private func edit(_ block: CachedSleepSession, userEdited: Bool) {
        wakeEdit = WakeEdit(detectedStartTs: block.startTs, bedTs: block.effectiveStartTs, wakeTs: block.endTs,
                            stagesJSON: block.stagesJSON, userEdited: userEdited)
    }

    private func editSheet(_ edit: WakeEdit) -> some View {
        // The night's RECORDED coverage for the #940 guards: from the detected onset (or an earlier
        // hand-set one) through the current wake.
        let coverageLo = min(edit.detectedStartTs, edit.bedTs)
        return SleepTimeEditor(bedTs: edit.bedTs, wakeTs: edit.wakeTs,
                               coverage: coverageLo...max(edit.wakeTs, coverageLo + 1),
                               suppressesReDetection: !edit.userEdited,
                               onSave: { newBedTs, newWakeTs in
            await repo.editSleepTimes(detectedStartTs: edit.detectedStartTs, oldEndTs: edit.wakeTs,
                                      storedStagesJSON: edit.stagesJSON,
                                      newStartTs: newBedTs, newEndTs: newWakeTs)
            // Re-score so Rest / recovery honour the corrected window, then refresh the read cache.
            await intelligence.analyzeRecent()
            await repo.refresh()
        }, onDelete: {
            // Durably tombstoned so a re-detect does not bring it back (#68); undoable until put away (#65).
            let snapshot = await repo.deleteSleepSession(detectedStartTs: edit.detectedStartTs, endTs: edit.wakeTs)
            await intelligence.analyzeRecent()
            await repo.refresh()
            if let snapshot { presentUndo(snapshot, displayStart: edit.bedTs, windowEnd: edit.wakeTs) }
        })
    }

    /// A missed nap is staged from raw as its own session, never folded into the night (#508).
    private func napSheet(_ seed: AddNapSeed) -> some View {
        SleepTimeEditor(bedTs: seed.bedTs, wakeTs: seed.wakeTs,
                        title: "Add a nap",
                        blurb: "Pick when the nap started and ended. reNOOP stages it from your data as its own session, separate from the night's sleep.",
                        bedLabel: "Nap started", wakeLabel: "Nap ended") { startTs, endTs in
            await repo.addManualNap(startTs: startTs, endTs: endTs)
            await intelligence.analyzeRecent()
            await repo.refresh()
        }
    }

    // MARK: - Delete undo (#65)

    struct SleepUndo {
        let snapshot: SleepDeletionSnapshot
        let message: String
    }

    private func presentUndo(_ snapshot: SleepDeletionSnapshot, displayStart: Int, windowEnd: Int) {
        // A hand-edited / added night writes no tombstone, so only a detected one promises no re-detection.
        let message = snapshot.session.userEdited
            ? String(localized: "Sleep deleted.")
            : String(localized: "Sleep deleted. reNOOP won't detect sleep between \(Self.clock(displayStart)) and \(Self.clock(windowEnd)) again.")
        withAnimation(.easeOut(duration: 0.2)) { sleepUndo = SleepUndo(snapshot: snapshot, message: message) }
    }

    private func undoBanner(_ undo: SleepUndo) -> some View {
        NoticeCard(title: Text(verbatim: undo.message), systemImage: "trash.fill", tone: .info,
                   actionTitle: "Undo", action: {
                       Task {
                           await repo.undoDeleteSleepSession(undo.snapshot)
                           await intelligence.analyzeRecent()
                           await repo.refresh()
                           withAnimation(.easeOut(duration: 0.2)) { sleepUndo = nil }
                       }
                   },
                   onDismiss: {
                       withAnimation(.easeOut(duration: 0.2)) { sleepUndo = nil }
                   })
            .transition(.opacity)
    }

    // MARK: - Highlights and options (grouped canvas)

    /// Health's Sleep highlights: the bedtime and duration claims, then the night's stages.
    @ViewBuilder private var highlightCards: some View {
        ForEach(SleepHighlights.make(entries: entries, anchor: Date())) { SleepHighlightCard(highlight: $0) }
        if let night, night.stages.asleep > 0, !nightIntervals(night).isEmpty {
            SleepStagesHighlightCard(intervals: nightIntervals(night), onset: night.onsetDate,
                                     asleepMinutes: night.stages.asleep)
        }
    }

    private var highlightsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Highlights", actionTitle: "Show All") { showAllHighlights = true }
            highlightCards
        }
    }

    private var allHighlights: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) { highlightCards }
                .padding(.horizontal, NoopMetrics.screenHPadding)
                .padding(.vertical, NoopMetrics.space3)
        }
        .background(StrandPalette.sleepScoreCanvas.ignoresSafeArea())
        .environment(\.summaryCardFill, StrandPalette.sleepScoreCard)
        .navigationTitle(Text("Sleep Highlights"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    /// Health's Options card: here the way into the sleep schedule.
    private var optionsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Options")
            NavigationLink(value: TabRoute.sleepSchedule) {
                SummaryCard(insets: .summaryCardRow) {
                    HStack {
                        Text("Sleep Schedule")
                            .font(StrandFont.body)
                            .foregroundStyle(StrandPalette.textPrimary)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(StrandFont.pro(chevronSize, weight: .semibold))
                            .foregroundStyle(StrandPalette.healthChevron)
                            .accessibilityHidden(true)
                    }
                }
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - Menu

    private var moreMenu: some View {
        Menu {
            if let target = night?.editTarget {
                Button { edit(target, userEdited: target.userEdited) } label: {
                    Label("Edit sleep times", systemImage: "pencil")
                }
            }
            if let night {
                let naps = Self.naps(night)
                if naps.isEmpty {
                    Button { addNap = AddNapSeed(forNight: night) } label: { Label("Add a nap", systemImage: "powersleep") }
                } else {
                    // Each of the day's naps opens the same editor as the night: change its times or delete it.
                    Menu {
                        ForEach(naps, id: \.startTs) { nap in
                            Button { edit(nap, userEdited: true) } label: {
                                Text(verbatim: "\(Self.clock(nap.effectiveStartTs)) – \(Self.clock(nap.endTs))")
                            }
                        }
                        Divider()
                        Button { addNap = AddNapSeed(forNight: night) } label: { Label("Add a nap", systemImage: "plus") }
                    } label: {
                        Label("Naps", systemImage: "powersleep")
                    }
                }
            }
            Divider()
            Button { logMark(.bedtime) } label: { Label("Log going to sleep", systemImage: "moon.zzz.fill") }
            Button { logMark(.wake) } label: { Label("Log waking up", systemImage: "sun.max.fill") }
        } label: {
            Image(systemName: "ellipsis")
        }
        .accessibilityLabel(Text("More"))
    }

    private func logMark(_ type: SleepMarkType) {
        if type == .wake {
            // A wake mark is more than a log line: it asks for the night to end at this instant.
            let mark = app.logWakeNow()
            Confirmation.shared.show(mark.wakeRequestConfirmation, systemImage: "sun.max.fill")
            return
        }
        let mark = SleepMark(type: type)
        SleepMark.log(mark, repo: repo, live: live)
        Confirmation.shared.show(mark.confirmation, systemImage: type == .bedtime ? "moon.zzz.fill" : "sun.max.fill")
    }

    // MARK: - Loading

    private func load() async {
        allSessions = await repo.allSleepSessions()
        habitualMidsleepSec = await repo.habitualMidsleepSec()
        motionByStart = await repo.sessionMotions(sessions: allSessions)
        let sessions = allSessions.isEmpty ? repo.sleeps : allSessions
        navDays = SleepModel.navDays(navSessions: sessions)
        model = SleepModel.build(SleepModelInputs(
            days: repo.days, sleeps: repo.sleeps, allSessions: allSessions,
            importedSleep: repo.importedSleep, habitualMidsleepSec: habitualMidsleepSec,
            motionByStart: motionByStart))
        entries = SleepHistory.entries(navDays: navDays, habitualMidsleepSec: habitualMidsleepSec)
        nightOffset = 0
        night = model?.night
        if let key = initialWakeDay, !appliedInitialWakeDay {
            appliedInitialWakeDay = true
            if let i = SleepNightLoader.index(ofWakeDay: key, in: navDays), i != 0 { nightOffset = i }
        }
    }

    static func clock(_ ts: Int) -> String {
        Date(timeIntervalSince1970: TimeInterval(ts))
            .formatted(.dateTime.hour().minute().locale(AppLanguage.activeLocale))
    }

    /// DEBUG screenshot runs open "Show More Sleep Data" with `--sleep-more [stages|amounts|comparisons]`.
    private static var initialShowMore: Bool {
        #if DEBUG
        return CommandLine.arguments.contains("--sleep-more")
        #else
        return false
        #endif
    }

    private static var initialMoreTab: SleepMoreTab {
        #if DEBUG
        let args = CommandLine.arguments
        if let i = args.firstIndex(of: "--sleep-more"), i + 1 < args.count,
           let tab = SleepMoreTab(rawValue: args[i + 1]) {
            return tab
        }
        #endif
        return .stages
    }

    /// Day view, unless a DEBUG screenshot run asks for another with `--sleep-range week|month|sixMonths`.
    private static var initialRange: SleepRange {
        #if DEBUG
        let args = CommandLine.arguments
        if let i = args.firstIndex(of: "--sleep-range"), i + 1 < args.count,
           let range = SleepRange(rawValue: args[i + 1]) {
            return range
        }
        #endif
        return .day
    }
}

extension SleepMark {
    /// Record a mark: a timestamped log line and metric point, never a change to detected sleep. Shared
    /// by the Sleep marks card and the Sleep page's menu so both write the same thing.
    @MainActor
    static func log(_ mark: SleepMark, repo: Repository, live: LiveState) {
        live.append(log: mark.logLine)
        Task {
            guard let store = await repo.storeHandle() else { return }
            try? await store.upsertMetricSeries([mark.metricPoint], deviceId: repo.deviceId)
        }
    }
}
