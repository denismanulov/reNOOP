//  SummaryView.swift
//  NOOP · Summary — the home screen, modelled on the iOS 26 Apple Health Summary.
//
//  Large title (with the profile photo on its row, as in Health) over a warm → cool wash, a ‹ day › pager
//  (the same one the Sleep page uses), then the top of Apple Fitness's Summary — the Rings card for Charge /
//  Effort / Rest and two tiles the user picks — and under them Health's Summary: "Pinned" (the Sleep card,
//  the other pinned metrics, Show All), "Trends" and "Highlights". Every card stamps when its value is from
//  ("Today", "Yesterday", a date). All data arrives in one `SummarySnapshot` from `SummaryLoader`; this
//  view only lays it out and routes taps.

import SwiftUI
import StrandDesign
import StrandAnalytics
import WhoopStore

struct SummaryView: View {
    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var router: NavRouter
    @EnvironmentObject private var profile: ProfileStore
    @EnvironmentObject private var ble: BLEManager
    @EnvironmentObject private var app: AppModel
    @Environment(\.scrollToTopSignal) private var scrollToTopSignal
    @ScaledMetric(relativeTo: .body) private var trendsGlyphSize: CGFloat = 20
    @Environment(\.dynamicTypeSize) private var dts

    /// 0 = today's logical day (rolls at 04:00), 1 = yesterday, …
    @State private var dayOffset = 0
    @State private var showDayPicker = false
    @State private var snapshot = SummarySnapshot()
    /// The night that ended on the picked day, for the Sleep card.
    @State private var sleepLoad = SummarySleepLoad()
    /// Non-nil while today's night has not been seen to end: the card that says so and its figure.
    @State private var sleepInProgress: SummarySleepInProgress?
    /// Health's Trends, as of now (not the picked day): the first few lead the section at the bottom.
    @State private var trends: HealthTrendsSnapshot?

    #if os(macOS)
    @State private var showSettings = false
    #endif
    /// The large title has scrolled under the bar: the bar shows the small one.
    @State private var titleScrolledAway = false
    @State private var customization: TodayCustomizationDestination?

    // The pinned list is the shared Key Metrics selection; the editor sheet needs every Today binding.
    @AppStorage(KeyMetricPrefs.layoutKey) private var keyMetricsRaw = ""
    @AppStorage(SummaryTilePrefs.storageKey) private var tilesRaw = ""
    @AppStorage("today.keyMetricsDetailed") private var keyMetricsDetailed = false
    @AppStorage("today.keyMetricsWindowDays") private var keyMetricsWindowDays = 14
    @AppStorage(TodayLayoutPrefs.orderKey) private var sectionOrderRaw = ""
    @AppStorage(TodayLayoutPrefs.hiddenKey) private var hiddenSectionsRaw = ""
    @AppStorage(DashboardCardPrefs.selectionKey) private var dashboardCardsRaw = ""
    @AppStorage(HostedCardPrefs.selectionKey) private var hostedCardsRaw = ""

    @AppStorage(DayCycleMode.storageKey) private var dayCycleModeRaw = DayCycleMode.sleepOnset.rawValue
    @AppStorage(UnitPrefs.systemKey) private var unitSystemRaw = UnitSystem.metric.rawValue
    @AppStorage(UnitPrefs.temperatureKey) private var temperatureRaw = ""
    @AppStorage(UnitPrefs.skinTempDisplayKey) private var skinTempDisplayRaw = ""
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue

    private static let topAnchorID = "summary.top"
    private static let cardGutter: CGFloat = 20
    /// How far the page scrolls before the large title counts as gone (about its own height).
    private static let titleFoldOffset: CGFloat = 36

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    Color.clear.frame(height: 0).id(Self.topAnchorID)
                    #if os(macOS)
                    header
                    #else
                    // Health's row sits 7 pt higher than the bar's bottom edge puts it.
                    titleRow
                        .padding(.top, -7)
                    #endif
                    // Raised health alerts stay pinned above everything else. A running workout is the tab
                    // bar's mini-player on iPhone (`NowRunningAccessory`); the Mac has no tab bar, so it keeps
                    // the card here.
                    HealthAlertBanner()
                    #if os(macOS)
                    ActiveWorkoutIndicatorSection()
                    #endif
                    DayPager(title: dayTitle,
                             canGoBack: dayOffset < SummaryDay.maxOffset(repo: repo), canGoForward: dayOffset > 0,
                             onBack: { dayOffset += 1 }, onForward: { dayOffset -= 1 },
                             showPicker: $showDayPicker) { dayPicker }
                        .padding(.horizontal, -8)
                    ringsCard
                    if let sleepInProgress {
                        SleepInProgressCard(state: sleepInProgress, onAwake: markAwake)
                    }
                    tilesRow
                    pinnedSection
                    trendsSection
                    highlightsSection
                    #if os(iOS)
                    // The strap's sync as Mail and Photos say theirs: one quiet line under everything.
                    StrapSyncStatusText()
                        .frame(maxWidth: .infinity)
                        .padding(.top, 12)
                    #endif
                }
                // Fitness and Health both set their Summary cards 20 pt in from the screen's edges.
                .padding(.horizontal, Self.cardGutter)
                .padding(.bottom, NoopMetrics.space8)
                #if os(macOS)
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
                #endif
            }
            .summaryBackdrop()
            #if os(iOS)
            .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
            .softTopEdge()
            .onScrolledPast(Self.titleFoldOffset) { away in
                withAnimation(.easeInOut(duration: 0.2)) { titleScrolledAway = away }
            }
            .onChange(of: scrollToTopSignal) { _, _ in
                withAnimation(.easeOut(duration: 0.35)) { proxy.scrollTo(Self.topAnchorID, anchor: .top) }
            }
            #endif
        }
        #if os(iOS)
        // Health's chrome: the large title row scrolls with the page and hands over to the bar's title.
        .navigationTitle(Text("Summary"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { inlineTitle }
        #endif
        .refreshable { await refresh() }
        .task(id: "sleep-\(repo.refreshSeq)-\(dayOffset)") {
            // The night still on screen may belong to the day just left; do not let its card linger.
            resolveSleepInProgress()
            let loaded = await SummaryLoader.sleep(repo: repo, wakeDayKey: selectedKey)
            guard !Task.isCancelled else { return }
            sleepLoad = loaded
            resolveSleepInProgress()
            // Fresh data goes stale with no reload to say so; ask again each minute while this shows.
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                guard !Task.isCancelled else { return }
                resolveSleepInProgress()
            }
        }
        .task(id: "trends-\(repo.refreshSeq)-\(skinTempDisplayRaw)") {
            let prefer = SkinTempDisplay.Kind(rawValue: skinTempDisplayRaw) ?? .absolute
            if let loaded = await HealthTrendLoader.load(repo: repo, skinTemp: prefer) { trends = loaded }
        }
        .sensoryFeedbackCompat(trigger: dayOffset)
        .task(id: "\(repo.refreshSeq)-\(dayOffset)-\(dayCycleModeRaw)-\(unitSystemRaw)-\(temperatureRaw)-\(skinTempDisplayRaw)") {
            await reload()
        }
        .sheet(item: $customization) { destination in
            TodayCustomizationSheet(
                initialDestination: destination,
                sectionOrderRaw: $sectionOrderRaw,
                hiddenSectionsRaw: $hiddenSectionsRaw,
                keyMetricsRaw: $keyMetricsRaw,
                keyMetricsDetailed: $keyMetricsDetailed,
                keyMetricsWindowDays: $keyMetricsWindowDays,
                dashboardCardsRaw: $dashboardCardsRaw,
                hostedCardsRaw: $hostedCardsRaw
            )
        }
        #if os(macOS)
        // On the Mac the avatar opens Settings as Health's profile sheet: photo and name on top.
        .sheet(isPresented: $showSettings) {
            ProfileSheet(onClose: { showSettings = false })
        }
        .toolbarBackground(.hidden, for: .windowToolbar)
        #endif
    }

    // MARK: - Header

    #if os(macOS)
    private var header: some View {
        HStack(alignment: .center, spacing: 12) {
            Text("Summary")
                .font(StrandFont.rounded(34, weight: .heavy))
                .foregroundStyle(StrandPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            SummaryStrapStatus()
            avatarButton
        }
        .padding(.top, NoopMetrics.space4)
        .padding(.bottom, NoopMetrics.space2)
    }
    #endif

    #if os(iOS)
    /// Health's large title row, drawn in the content: "Summary" with the profile photo at its trailing
    /// edge. (A `.largeTitle` toolbar item would draw the same row, but on iOS 26.5 its content takes no
    /// touches, so the photo could not open Settings.) Scrolling it away brings the title into the bar.
    private var titleRow: some View {
        HStack(alignment: .center) {
            Text("Summary")
                .font(.largeTitle.bold())
                .foregroundStyle(StrandPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            avatarButton
        }
    }

    /// The bar's small title, shown once the large one has scrolled under the bar.
    @ToolbarContentBuilder private var inlineTitle: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            Text("Summary")
                .font(.headline)
                .foregroundStyle(StrandPalette.textPrimary)
                .opacity(titleScrolledAway ? 1 : 0)
                .accessibilityHidden(!titleScrolledAway)
        }
    }
    #endif

    /// The profile circle opens Settings: pushed onto the Summary's stack on iPhone, a sheet on the Mac
    /// (whose Summary pane has no Settings pages registered).
    @ViewBuilder private var avatarButton: some View {
        #if os(iOS)
        NavigationLink(value: TabRoute.settings) { avatarLabel }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Profile and settings"))
        #else
        Button { showSettings = true } label: { avatarLabel }
            .buttonStyle(.plain)
            .summaryGlassCircle()
            .accessibilityLabel(Text("Profile and settings"))
        #endif
    }

    private var avatarLabel: some View {
        SummaryAvatar(imageData: profile.avatarImageData, initials: profile.initials,
                      size: NoopMetrics.compactControlSize)
            .frame(width: NoopMetrics.compactControlSize, height: NoopMetrics.compactControlSize)
            .contentShape(Circle())
    }

    // MARK: - Rings

    private var effortScale: EffortScale { UnitPrefs.resolveEffortScale(effortScaleRaw) }

    private var ringsCard: some View {
        let charge = snapshot.charge
        let chargeCaption: String? = {
            switch charge {
            case .carried(_, let caption): return caption
            case .calibrating: return charge.stateLabel
            case .scored, .noData: return nil
            }
        }()
        // Apple Watch's three hues and glyphs, outermost first: Move red for Charge, Exercise green for
        // Effort, Stand cyan for Rest.
        let rings = [
            ActivityRing(id: "charge", fraction: RingFraction.of(charge.pct, max: 100),
                         start: StrandPalette.activityMoveStart, end: StrandPalette.activityMoveEnd,
                         glyph: .move),
            ActivityRing(id: "effort", fraction: RingFraction.of(snapshot.effort, max: 100),
                         start: StrandPalette.activityExerciseStart, end: StrandPalette.activityExerciseEnd,
                         glyph: .exercise),
            ActivityRing(id: "rest", fraction: RingFraction.of(snapshot.rest, max: 100),
                         start: StrandPalette.activityStandStart, end: StrandPalette.activityStandEnd,
                         glyph: .stand),
        ]
        let rows = [
            SummaryRingRow(id: "charge", title: String(localized: "Charge"),
                           value: SummaryMetricReading.int(charge.pct), unit: charge.pct == nil ? "" : "%",
                           caption: chargeCaption, color: StrandPalette.activityMoveText,
                           route: .metric(HeroRingMetric.charge)),
            SummaryRingRow(id: "effort", title: String(localized: "Effort"),
                           // "12,4/21" (or "/100") as Fitness writes a ring against its goal.
                           value: snapshot.effort == nil ? SummaryMetricReading.noValue
                               : SummaryMetricReading.decimal(
                                   snapshot.effort.map { UnitFormatter.effortValue($0, scale: effortScale) })
                                 + "/" + UnitFormatter.effortScaleMax(effortScale),
                           unit: "",
                           caption: nil, color: StrandPalette.activityExerciseText,
                           route: .metric(HeroRingMetric.effort)),
            SummaryRingRow(id: "rest", title: String(localized: "Rest"),
                           value: SummaryMetricReading.int(snapshot.rest), unit: snapshot.rest == nil ? "" : "%",
                           caption: nil, color: StrandPalette.activityStandText,
                           // Rest is last night's sleep: it opens the Sleep page on that night.
                           route: .sleepNight(selectedKey)),
        ]
        return SummaryFitnessRingsCard(rings: rings, rows: rows)
    }

    // MARK: - Tiles

    private var tiles: [KeyMetric] { SummaryTilePrefs.decode(tilesRaw) }

    /// Fitness's pair of square tiles under the rings, each a metric the user picks (long-press).
    @ViewBuilder private var tilesRow: some View {
        if let inputs = snapshot.metrics {
            let layout = dts.isAccessibilitySize
                ? AnyLayout(VStackLayout(spacing: 10)) : AnyLayout(HStackLayout(alignment: .top, spacing: 10))
            layout {
                ForEach(Array(tiles.enumerated()), id: \.element) { slot, metric in
                    if let reading = SummaryMetricReading.resolve(metric, inputs) {
                        SummaryFitnessTile(
                            metric: metric, reading: reading,
                            week: snapshot.dailySeries[reading.seriesKey] ?? [],
                            weekKeys: snapshot.weekKeys,
                            stamp: SummaryStamp.text(dayKey: reading.stampDay ?? selectedKey, todayKey: todayKey),
                            onChange: { tilesRaw = SummaryTilePrefs.replacing(tilesRaw, slot: slot, with: $0) })
                    }
                }
            }
        }
    }


    // MARK: - Pinned

    /// The pinned cards: every enabled Key Metric the rings and the tiles don't already show.
    private var pinnedMetrics: [KeyMetric] {
        KeyMetricPrefs.decodeEnabled(keyMetricsRaw).filter { ![.charge, .effort, .rest].contains($0) && !tiles.contains($0) }
    }

    /// Today's logical day key (rolls at 04:00), following the live `repo.today` row.
    private var todayKey: String { SummaryDay.key(offset: 0, repo: repo) }
    /// The picked day's key.
    private var selectedKey: String { SummaryDay.key(offset: dayOffset, repo: repo) }

    private var selectedDay: Binding<Date> {
        Binding(
            get: { SummaryDay.logicalDay(offset: dayOffset) },
            set: { picked in
                dayOffset = SummaryDay.pickedDayOffset(pickedDate: picked,
                                                       anchorLogicalDay: SummaryDay.logicalDay(offset: 0))
                showDayPicker = false
            }
        )
    }

    /// The pager's title: "Today", "Yesterday", else "Thursday, 24 September".
    private var dayTitle: String {
        switch dayOffset {
        case 0: return String(localized: "Today")
        case 1: return String(localized: "Yesterday")
        default:
            let text = SummaryDay.logicalDay(offset: dayOffset)
                .formatted(.dateTime.weekday(.wide).day().month(.wide).locale(AppLanguage.activeLocale))
            return text.prefix(1).uppercased(with: AppLanguage.activeLocale) + text.dropFirst()
        }
    }

    /// Tapping the pager's title: a calendar for jumping further than a few days.
    private var dayPicker: some View {
        DatePicker("", selection: selectedDay,
                   in: SummaryDay.logicalDay(offset: SummaryDay.maxOffset(repo: repo))...SummaryDay.logicalDay(offset: 0),
                   displayedComponents: [.date])
            .datePickerStyle(.graphical)
            .labelsHidden()
            .padding(12)
    }


    private func stamp(_ reading: SummaryMetricReading) -> String? { stamp(dayKey: reading.stampDay) }

    /// A card's stamp. On a past day the subtitle already names the day, so a value from that very day
    /// says nothing more; only a value carried from another day is stamped. Today keeps Health's "Today".
    private func stamp(dayKey: String?) -> String? {
        guard dayOffset == 0 || dayKey != selectedKey else { return nil }
        return SummaryStamp.text(dayKey: dayKey, todayKey: todayKey)
    }

    private var sleepNight: Night? { sleepLoad.night }

    /// Is today's night one the app has not seen end? Asked after every load, each minute, and on the
    /// card's button. One resolver, one clock, one loaded night for both the answer and the figure.
    private func resolveSleepInProgress() {
        #if DEBUG
        // `--demo-sleep-in-progress`: show the card over the demo seed, whose night has no fresh data.
        if CommandLine.arguments.contains("--demo-sleep-in-progress"), dayOffset == 0 {
            sleepInProgress = SummarySleepInProgress(asleepMinutes: sleepNight?.stages.asleep ?? 337)
            return
        }
        #endif
        sleepInProgress = SummarySleepInProgress.resolve(
            sleepLoad, isToday: dayOffset == 0, selectedDayKey: selectedKey,
            nowTs: Int(Date().timeIntervalSince1970), pendingWakeTs: WakeMarkStore.pending())
    }

    /// The wearer says they are awake: mark it, which starts a sync, and let the detector find when
    /// they woke. The card goes away on the tap, because the pending mark now postdates the night's start.
    private func markAwake() {
        let mark = app.logWakeNow()
        resolveSleepInProgress()
        Confirmation.shared.show(mark.wakeRequestConfirmation, systemImage: "sun.max.fill")
    }

    private var pinnedSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Pinned", actionTitle: "Edit") { customization = .keyMetrics }
            if let sleepNight, sleepNight.stages.asleep > 0 {
                SummarySleepCard(dayKey: selectedKey, night: sleepNight)
            }
            if pinnedMetrics.isEmpty {
                Button { customization = .keyMetrics } label: {
                    SummaryCard {
                        Label("Pin metrics you care about", systemImage: "pin.fill")
                            .font(StrandFont.headline)
                            .foregroundStyle(StrandPalette.accent)
                    }
                }
                .buttonStyle(.plain)
            } else if let inputs = snapshot.metrics {
                ForEach(pinnedMetrics) { metric in
                    if let reading = SummaryMetricReading.resolve(metric, inputs) {
                        SummaryMetricCard(metric: metric, reading: reading,
                                          series: snapshot.series[reading.seriesKey] ?? [],
                                          stamp: stamp(reading))
                    }
                }
            }
            // Health's "Show All Health Data": the whole metric catalog, one tap away.
            NavigationLink(value: TabRoute.allMetrics) {
                SummaryCard(insets: .summaryCardRow) {
                    HStack(spacing: 0) {
                        HealthDataGlyph()
                            .frame(width: 38, alignment: .leading)
                            .padding(.leading, 6)
                        Text("Show All Metrics")
                            .font(StrandFont.body)
                            .foregroundStyle(StrandPalette.textPrimary)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(StrandFont.pro(12, weight: .semibold))
                            .foregroundStyle(StrandPalette.textTertiary)
                            .accessibilityHidden(true)
                    }
                }
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: - Highlights

    @ViewBuilder private var highlightsSection: some View {
        if !snapshot.highlights.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "Highlights")
                ForEach(snapshot.highlights) {
                    SummaryHighlightCard(highlight: $0, effortWeek: snapshot.series["effort"] ?? [])
                }
            }
        }
    }

    // MARK: - Trends

    /// Health's Trends at the foot of the Summary: the leading few cards, then the row that opens them all.
    @ViewBuilder private var trendsSection: some View {
        if let trends {
            VStack(alignment: .leading, spacing: 10) {
                SectionHeader(title: "Trends")
                ForEach(trends.items.prefix(Self.trendCards)) { item in
                    NavigationLink(value: TabRoute.metricSourced(key: item.metric.key, source: item.metric.source)) {
                        HealthTrendCard(metric: item.metric, trend: item.trend,
                                        units: HealthTrendsUnits.resolve(system: unitSystemRaw, temperature: temperatureRaw,
                                                                         effortScale: effortScaleRaw))
                    }
                    .buttonStyle(.plain)
                }
                NavigationLink(value: TabRoute.trends) {
                    SummaryCard(insets: .summaryCardRow) {
                        HStack(spacing: 0) {
                            // Health's rows: the glyph 22 pt in, the title at 60.
                            HealthTrendsGlyph(size: trendsGlyphSize)
                                .foregroundStyle(StrandPalette.accent)
                                .frame(width: 38, alignment: .leading)
                                .padding(.leading, 6)
                            Text("Show All Trends")
                                .font(StrandFont.body)
                                .foregroundStyle(StrandPalette.textPrimary)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(StrandFont.pro(12, weight: .semibold))
                                .foregroundStyle(StrandPalette.textTertiary)
                                .accessibilityHidden(true)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    /// Health shows a few trends on the Summary and the rest behind "Show All Health Trends".
    private static let trendCards = 3

    // MARK: - Loading

    private func reload() async {
        let unitSystem = UnitSystem(rawValue: unitSystemRaw) ?? .metric
        let prefs = SummaryLoader.Prefs(
            dayCycleMode: DayCycleMode.persisted(dayCycleModeRaw),
            unitSystem: unitSystem,
            fahrenheit: UnitPrefs.resolveTemperature(system: unitSystem, override: temperatureRaw) == .fahrenheit,
            skinTempKind: SkinTempDisplay.Kind(rawValue: skinTempDisplayRaw) ?? .absolute
        )
        let loaded = await SummaryLoader.load(repo: repo, profile: profile, offset: dayOffset, prefs: prefs)
        guard !Task.isCancelled else { return }
        snapshot = loaded
    }

    /// A pull asks the strap for its history (when the link can serve one), then re-reads the store.
    private func refresh() async {
        if ble.state.historyReady { ble.syncNow() }
        await repo.refresh()
        await reload()
    }
}


private extension View {
    /// iOS 26's soft scroll edge under the bar, so the page blurs away beneath the small title as Health's
    /// does. A no-op before iOS 26.
    @ViewBuilder
    func softTopEdge() -> some View {
        #if compiler(>=6.2) && os(iOS)
        if #available(iOS 26.0, *) {
            self.scrollEdgeEffectStyle(.soft, for: .top)
        } else {
            self
        }
        #else
        self
        #endif
    }

    /// Reports whether the scroll view has moved more than `offset` points from its top. iOS 18 API; on
    /// iOS 17 nothing is reported, so the bar keeps no title (the page's own title row still shows).
    @ViewBuilder
    func onScrolledPast(_ offset: CGFloat, action: @escaping (Bool) -> Void) -> some View {
        if #available(iOS 18.0, macOS 15.0, *) {
            self.onScrollGeometryChange(for: Bool.self) { geo in
                geo.contentOffset.y + geo.contentInsets.top > offset
            } action: { _, away in
                action(away)
            }
        } else {
            self
        }
    }

    /// A light tick when the picked day changes; iOS 17 / macOS 14 API, a no-op before that.
    @ViewBuilder
    func sensoryFeedbackCompat(trigger: Int) -> some View {
        if #available(iOS 17.0, macOS 14.0, *) {
            self.sensoryFeedback(.selection, trigger: trigger)
        } else {
            self
        }
    }
}

/// Health's profile circle: the user's photo, else their initials on Contacts' grey monogram gradient,
/// else — with no name either — a white silhouette on the same grey.
struct SummaryAvatar: View {
    let imageData: Data?
    var initials: String = ""
    let size: CGFloat

    var body: some View {
        if imageData != nil {
            ProfileAvatarView(imageData: imageData, size: size)
        } else {
            Circle()
                .fill(LinearGradient(colors: [StrandPalette.summaryAvatarTop, StrandPalette.summaryAvatarBottom],
                                     startPoint: .top, endPoint: .bottom))
                .overlay {
                    if initials.isEmpty {
                        Image(systemName: "person.fill")
                            .font(.system(size: size * 0.52, weight: .medium))
                            .foregroundStyle(StrandPalette.onDarkPrimary)
                            .offset(y: size * 0.06)
                    } else {
                        Text(verbatim: initials)
                            .font(.system(size: size * 0.4, weight: .semibold, design: .rounded))
                            .foregroundStyle(StrandPalette.onDarkPrimary)
                            .minimumScaleFactor(0.6)
                            .lineLimit(1)
                            .padding(size * 0.12)
                    }
                }
                .clipShape(Circle())
                .frame(width: size, height: size)
        }
    }
}
