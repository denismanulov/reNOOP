//  MetricDetailView.swift
//  NOOP · Metric page — the one page every metric opens on, laid out like a data type's page in the
//  iOS 26 Health app (Browse → Heart → Heart Rate Variability, Cardio Fitness, Steps…).
//
//  One grouped canvas, cards on it as on the Summary: the W / M / 6M / Y picker, then a card with
//  "AVERAGE", the period's figure and dates (ⓘ explains the metric), the metric's own chart (press and
//  drag to read one mark) and its latest reading; a Highlights card (the latest reading against the
//  two-week average); and Options (all data, pin to Summary, data sources). Every figure comes from
//  `MetricHealthSeries`.

import SwiftUI
import StrandDesign
import StrandAnalytics
import WhoopStore

struct MetricDetailView: View {
    let metric: MetricDescriptor

    @EnvironmentObject private var repo: Repository
    @EnvironmentObject private var profile: ProfileStore
    @EnvironmentObject private var intelligence: IntelligenceEngine
    @AppStorage(UnitPrefs.systemKey) private var unitSystemRaw = UnitSystem.metric.rawValue
    @AppStorage(UnitPrefs.temperatureKey) private var temperatureRaw = ""
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue
    /// #1846/#1848: lead the skin-temp page with a temperature (default) or with the ±baseline move.
    @AppStorage(UnitPrefs.skinTempDisplayKey) private var skinTempDisplayRaw = ""
    @AppStorage(KeyMetricPrefs.layoutKey) private var keyMetricsRaw = ""

    @State private var range: MetricHealthRange = Self.initialRange
    @State private var selection: MetricHealthPoint?
    /// Full ascending series for this metric — all history.
    @State private var series: [(day: String, value: Double)] = []
    /// day → the raw source id that supplied that day's value, for "Show All Data" and the VO₂max breaks.
    @State private var sourceByDay: [String: String] = [:]
    @State private var loaded = false
    /// #1848: why the skin-temp series leads with what it does, when that needs saying.
    @State private var skinTempNote: String?
    @State private var refreshing = false
    /// What an empty Blood Oxygen page adds to "No Data"; resolved with the series, from the registry.
    @State private var spo2Empty: Spo2EmptyState = .standard

    @Environment(\.dynamicTypeSize) private var dts
    /// Dynamic Type multipliers for a figure's numbers and units: the hero follows the large title, a row the body.
    @ScaledMetric(relativeTo: .largeTitle) private var heroScale: CGFloat = 1
    @ScaledMetric(relativeTo: .body) private var rowScale: CGFloat = 1
    @ScaledMetric(relativeTo: .title) private var highlightValueSize: CGFloat = 30

    // MARK: Derived

    private var units: MetricHealthStyle.Units {
        let system = UnitSystem(rawValue: unitSystemRaw) ?? .metric
        return .init(system: system,
                     temperature: UnitPrefs.resolveTemperature(system: system, override: temperatureRaw),
                     effortScale: UnitPrefs.resolveEffortScale(effortScaleRaw))
    }
    private var tint: Color { MetricHealthStyle.tint(metric) }
    private var isSteps: Bool { MetricHealthStyle.isSteps(metric) }
    private var skinTempPreferred: SkinTempDisplay.Kind { SkinTempDisplay.Kind(rawValue: skinTempDisplayRaw) ?? .absolute }
    private var calendar: Calendar { .current }
    private var locale: Locale { AppLanguage.activeLocale }

    /// A daily sum rather than a level: its picked day reads "TOTAL", not "AVERAGE".
    private var isTotal: Bool {
        metric.unit == "min" || metric.unit == "kcal" || metric.unit == "g" || isSteps
    }

    private var loadTaskID: String {
        "\(metric.id)|\(repo.refreshSeq)|\(skinTempDisplayRaw)|\(isSteps && range == .year)"
    }

    private var todayKey: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date())
    }

    // MARK: Body

    var body: some View {
        let window = MetricHealthSeries.window(series: series, range: range, today: Date(), calendar: calendar)
        return ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SpokenSegmentedPicker(selection: $range, options: MetricHealthRange.allCases, label: \.label, spoken: \.spokenName)
                .padding(.bottom, 4)
                chartCard(window)
                if MetricStressDayCard.applies(to: metric) {
                    MetricStressDayCard(metric: metric, tint: tint, units: units)
                }
                canvas
            }
            .padding(.horizontal, NoopMetrics.screenHPadding)
            .padding(.top, NoopMetrics.space2)
            .padding(.bottom, NoopMetrics.space8)
            #if os(macOS)
            .frame(maxWidth: 680)
            .frame(maxWidth: .infinity)
            #endif
        }
        #if os(iOS)
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
        #endif
        // The cards grow once the series lands; without a top anchor the page could open scrolled down.
        .modifier(TopScrollAnchor())
        .background(StrandPalette.summaryCanvas.ignoresSafeArea())
        .navigationTitle(Text(metric.title))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task(id: loadTaskID) { await load() }
    }

    // MARK: - Chart card

    /// The first card: the figure and its dates, the chart, and the latest reading under a hairline.
    private func chartCard(_ window: MetricHealthWindow) -> some View {
        SummaryCard {
            VStack(alignment: .leading, spacing: 0) {
                header(window)
                Group {
                    if showsImportState {
                        // Nothing recorded: one way to fill it, in place of an empty chart.
                        EmptyStateView(title: Text("No Data"), systemImage: AllMetricsCatalog.category(metric).icon) {
                            NavigationLink { DataSourcesView() } label: { Text("Import History") }
                        }
                    } else if loaded {
                        MetricHealthChart(window: window, spec: MetricHealthStyle.chart(metric, series: series),
                                          tint: tint, segments: segments(window),
                                          axisLabel: { v in
                                              MetricHealthStyle.axisLabel(metric, v, units: units,
                                                                          span: window.points.map(\.value).max() ?? 0)
                                          },
                                          axTitle: metric.title,
                                          axUnit: MetricHealthStyle.tokens(metric, window.average ?? 0, units: units)
                                              .last(where: \.isUnit)?.text ?? "",
                                          valueText: { MetricHealthStyle.text(metric, $0, units: units) },
                                          selection: $selection)
                    } else {
                        ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .frame(height: 240)
                .padding(.top, NoopMetrics.space4)

                ForEach(notes(window), id: \.self) { note in
                    Text(note)
                        .font(StrandFont.pro(13))
                        .foregroundStyle(StrandPalette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, NoopMetrics.space2)
                }

                if let latest = series.last {
                    Rectangle().fill(StrandPalette.hairline).frame(height: NoopMetrics.hairlineWidth)
                        .padding(.top, NoopMetrics.space4)
                    latestRow(latest)
                        .padding(.top, NoopMetrics.space3)
                }
            }
            .padding(.top, 4)
        }
        .animation(StrandMotion.interactive, value: range)
    }

    /// "AVERAGE" · figure · dates — or, while a mark is held, that mark's figure and date.
    private func header(_ window: MetricHealthWindow) -> some View {
        let picked = selection
        // A picked Charge mark also names its state, which its bar's hue alone would not say.
        let state = picked.flatMap { p in MetricHealthStyle.chart(metric, series: series).stateWord?(p.value) }
        let aggregate = picked != nil && range.bucket == .day
            ? (isTotal ? String(localized: "TOTAL") : nil)
            : String(localized: "AVERAGE")
        // A picked day of a level has no aggregate to name; a blank keeps the header from jumping.
        let value = picked?.value ?? window.average
        // A skin-temperature deviation carries a plain "°C": the header says it is one.
        let deviation = metric.unit == "°C" && value.map { SkinTempDisplay.kind(of: $0) == .deviation } == true
            ? String(localized: "Deviation").uppercased(with: locale) : nil
        let words = [aggregate, deviation, state].compactMap { $0 }
        // With nothing recorded, the empty state under the header names it; the header stays blank.
        let caption = words.isEmpty || showsImportState ? " " : words.joined(separator: " · ")
        let dates = picked.map { MetricHealthSeries.pointLabel($0, range: range, calendar: calendar, locale: locale) }
            ?? MetricHealthSeries.spanLabel(window, calendar: calendar, locale: locale)
        return VStack(alignment: .leading, spacing: 2) {
            Text(caption)
                .font(StrandFont.pro(13, weight: .semibold))
                .foregroundStyle(StrandPalette.textSecondary)
            HStack(alignment: .firstTextBaseline) {
                if let value {
                    figure(MetricHealthStyle.tokens(metric, value, units: units), size: 34)
                } else {
                    Text(loaded && !showsImportState ? String(localized: "No Data") : " ")
                        .font(StrandFont.pro(34, weight: .bold))
                        .foregroundStyle(StrandPalette.textPrimary)
                }
                Spacer(minLength: 8)
                if let about = MetricHealthStyle.about(metric) {
                    InfoButton(style: .circled, label: "About \(metric.title)") { Text(about) }
                }
            }
            Text(dates)
                .font(StrandFont.pro(17, weight: .semibold))
                .foregroundStyle(StrandPalette.textSecondary)
                .lineLimit(dts.isAccessibilitySize ? 2 : 1)
                .minimumScaleFactor(0.8)
        }
    }

    /// Numbers large and primary, units smaller and secondary, on one baseline.
    private func figure(_ tokens: [MetricHealthStyle.Token], size base: CGFloat) -> some View {
        let size = base * (base >= 30 ? heroScale : rowScale)
        return HStack(alignment: .firstTextBaseline, spacing: 3) {
            ForEach(Array(tokens.enumerated()), id: \.offset) { _, t in
                if t.isUnit {
                    Text(verbatim: t.text)
                        .font(StrandFont.pro(size * 0.58, weight: .semibold))
                        .foregroundStyle(StrandPalette.textSecondary)
                } else {
                    Text(verbatim: t.text)
                        .font(StrandFont.pro(size, weight: .bold))
                        .foregroundStyle(StrandPalette.textPrimary)
                }
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.6)
    }

    /// "Latest: Yesterday ······ 52 bpm" — the last line of the chart card.
    private func latestRow(_ latest: (day: String, value: Double)) -> some View {
        let stamp = SummaryStamp.text(dayKey: latest.day, todayKey: todayKey) ?? latest.day
        let stacked = dts.isAccessibilitySize
        let layout = stacked
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline))
        return layout {
            Text("Latest: \(stamp)")
                .font(StrandFont.pro(15))
                .foregroundStyle(StrandPalette.textSecondary)
                .lineLimit(stacked ? nil : 1)
            if !stacked { Spacer(minLength: 8) }
            figure(MetricHealthStyle.tokens(metric, latest.value, units: units), size: 17)
        }
        .accessibilityElement(children: .combine)
    }

    /// The explanations a chart can need: why the skin-temp series leads as it does (#1848), and why a
    /// VO₂max line breaks where the estimator changed (#1662).
    private func notes(_ window: MetricHealthWindow) -> [String] {
        var out: [String] = []
        if let skinTempNote { out.append(skinTempNote) }
        if metric.key == "vo2max_est",
           vo2MaxTrendHasBreak(days: window.days.map(\.day), sourceByDay: sourceByDay) {
            out.append(String(localized: "The line breaks where the estimation method changed or was not recorded."))
        }
        return out
    }

    /// Each VO₂max mark's estimator run, so the line never joins two methods; one run for anything else.
    private func segments(_ window: MetricHealthWindow) -> [Date: String] {
        guard metric.key == "vo2max_est" else { return [:] }
        let ids = vo2MaxTrendSegmentIds(days: window.days.map(\.day), sourceByDay: sourceByDay)
        let byDay = Dictionary(zip(window.days.map(\.day), ids), uniquingKeysWith: { a, _ in a })
        var out: [Date: String] = [:]
        for p in window.points { out[p.start] = byDay[p.lastDay] }
        return out
    }

    // MARK: - Canvas

    @ViewBuilder
    private var canvas: some View {
        VStack(alignment: .leading, spacing: 12) {
            if loaded && series.isEmpty {
                emptyState
            }
            if let highlight = MetricHealthSeries.highlight(series: series, calendar: calendar) {
                SectionHeader(title: "Highlights")
                highlightCard(highlight)
            }
            SectionHeader(title: "Options")
            optionsCard
        }
    }

    /// No reading on record for a metric that comes from an import or a strap (not Fitness Age, which is
    /// computed here and explains itself).
    private var showsImportState: Bool { loaded && series.isEmpty && metric.key != "fitness_age" }

    @ViewBuilder
    private var emptyState: some View {
        if metric.key == "fitness_age" {
            // Fitness Age is computed on-device from resting HR + activity, not imported: say how many more
            // nights it needs, and offer to recompute now from what is stored.
            SummaryCard {
                VStack(alignment: .leading, spacing: NoopMetrics.space3) {
                    Text(verbatim: fitnessReadyLeadCopy(rhrDays: repo.days.suffix(7).compactMap { $0.restingHr }.count,
                                                        hasAge: profile.age > 0, hasSex: !profile.sex.isEmpty))
                        .font(StrandFont.pro(17))
                        .foregroundStyle(StrandPalette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    if refreshing {
                        ProgressView().controlSize(.small)
                    } else {
                        Button {
                            refreshing = true
                            Task {
                                _ = await intelligence.recomputeFitnessAgeOnly()
                                await load()
                                refreshing = false
                            }
                        } label: {
                            Label("Refresh Fitness Age", systemImage: "arrow.clockwise")
                                .font(StrandFont.pro(17))
                                .foregroundStyle(StrandPalette.accent)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        } else if spo2Empty == .estimateOff {
            // The strap has an estimate and its switch is off: waiting adds nothing, so name the switch.
            SummaryCard {
                VStack(alignment: .leading, spacing: NoopMetrics.space2) {
                    Text("The blood oxygen estimate is turned off")
                        .font(StrandFont.pro(17, weight: .semibold))
                        .foregroundStyle(StrandPalette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("Your strap reports a blood oxygen estimate, but it is unverified and off by default. Turn on the strap estimate in Settings to see it, or import a WHOOP export in Data Sources for the calibrated value.")
                        .font(StrandFont.pro(15))
                        .foregroundStyle(StrandPalette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
    }

    /// Health's highlight on a data type's page: the category line, one sentence, the average and the latest
    /// figure, then the fortnight's readings as grey bars with the latest in the metric's hue and the
    /// average drawn across them.
    private func highlightCard(_ h: MetricHealthHighlight) -> some View {
        let sentence: String
        switch h.direction {
        case .above: sentence = String(localized: "Your latest reading was above your two-week average.")
        case .below: sentence = String(localized: "Your latest reading was below your two-week average.")
        case .close: sentence = String(localized: "Your latest reading was close to your two-week average.")
        }
        // As Health's highlights: the latest reading in the metric's hue, the average it is read against in grey.
        let latestTint = tint
        // Side by side; one under the other at accessibility sizes.
        let figuresLayout = dts.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
            : AnyLayout(HStackLayout(alignment: .top))
        return HighlightCard(icon: AllMetricsCatalog.category(metric).icon, title: metric.title, tint: tint, sentence: sentence, spacing: 10) {
            figuresLayout {
                highlightFigure(String(localized: "Two-Week Average"), h.average, color: StrandPalette.textSecondary, trailing: false)
                if !dts.isAccessibilitySize { Spacer(minLength: 8) }
                highlightFigure(String(localized: "Latest"), h.latest, color: StrandPalette.text(for: latestTint),
                                trailing: !dts.isAccessibilitySize)
            }
        } chart: {
            highlightBars(h, latestTint: latestTint)
                .frame(height: 96)
                .padding(.top, 6)
            HStack {
                Text(verbatim: shortDate(h.firstDay))
                Spacer()
                Text(verbatim: shortDate(h.lastDay))
            }
            .font(StrandFont.pro(13))
            .foregroundStyle(StrandPalette.textSecondary)
        }
    }

    private func highlightFigure(_ title: String, _ value: Double, color: Color, trailing: Bool) -> some View {
        VStack(alignment: trailing ? .trailing : .leading, spacing: 2) {
            Text(title)
                .font(StrandFont.pro(15, weight: .semibold))
                .foregroundStyle(StrandPalette.textSecondary)
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                ForEach(Array(MetricHealthStyle.tokens(metric, value, units: units).enumerated()), id: \.offset) { _, t in
                    Text(verbatim: t.text)
                        .font(t.isUnit ? StrandFont.pro(17, weight: .semibold) : StrandFont.pro(highlightValueSize, weight: .semibold))
                        .foregroundStyle(t.isUnit ? StrandPalette.textSecondary : color)
                }
            }
            .lineLimit(1)
            .minimumScaleFactor(0.7)
        }
    }

    /// Grey bars for the fortnight, the latest in the metric's hue, the average as a grey line across. The scale
    /// starts below the lowest reading so day-to-day movement shows, as on Health's highlight charts.
    private func highlightBars(_ h: MetricHealthHighlight, latestTint: Color) -> some View {
        let lo0 = h.values.min() ?? 0, hi = max(h.values.max() ?? 1, h.average)
        let lo = hi > lo0 ? max(0, lo0 - (hi - lo0) * 0.6) : hi * 0.5
        let span = max(hi - lo, 1e-9)
        return GeometryReader { geo in
            let slot = geo.size.width / CGFloat(max(h.values.count, 1))
            let barWidth = min(18, slot * 0.55)
            ZStack(alignment: .bottomLeading) {
                ForEach(Array(h.values.enumerated()), id: \.offset) { i, v in
                    RoundedRectangle(cornerRadius: 2, style: .continuous)
                        .fill(i == h.values.count - 1 ? latestTint : StrandPalette.textTertiary.opacity(0.35))
                        .frame(width: barWidth, height: max(3, geo.size.height * CGFloat((v - lo) / span)))
                        .offset(x: slot * CGFloat(i) + (slot - barWidth) / 2)
                }
                Capsule()
                    .fill(StrandPalette.textSecondary)
                    .frame(height: 3)
                    .offset(y: -geo.size.height * CGFloat((h.average - lo) / span) + 1.5)
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .bottomLeading)
        }
        .accessibilityHidden(true)
    }

    private func shortDate(_ key: String) -> String {
        MetricHealthSeries.date(key, calendar: calendar)
            .map { $0.formatted(.dateTime.day().month(.abbreviated).locale(locale)) } ?? key
    }

    // MARK: Options

    private var pinnable: KeyMetric? {
        guard let card = MetricHealthStyle.keyMetric(for: metric.key), ![.charge, .effort, .rest].contains(card) else {
            return nil
        }
        return card
    }

    private var optionsCard: some View {
        SummaryCard(insets: .summaryCardList) {
            VStack(spacing: 0) {
                if !series.isEmpty {
                    NavigationLink {
                        MetricAllDataView(metric: metric, series: series, sourceByDay: sourceByDay, units: units)
                    } label: {
                        optionRow(String(localized: "Show All Data"), accent: false, chevron: true)
                    }
                    .buttonStyle(.plain)
                    optionDivider
                }
                if metric.key == "avg_hr" {
                    // The day's heart rate at full resolution (#575), one level below the daily averages.
                    NavigationLink {
                        FullDayChartView()
                    } label: {
                        optionRow(String(localized: "Full Day by the Second"), accent: false, chevron: true)
                    }
                    .buttonStyle(.plain)
                    optionDivider
                }
                if let card = pinnable {
                    let pinned = KeyMetricPrefs.decodeEnabled(keyMetricsRaw).contains(card)
                    Button {
                        var list = KeyMetricPrefs.decodeEnabled(keyMetricsRaw)
                        if pinned { list.removeAll { $0 == card } } else { list.append(card) }
                        keyMetricsRaw = KeyMetricPrefs.encode(list)
                    } label: {
                        optionRow(pinned ? String(localized: "Unpin from Summary") : String(localized: "Pin in Summary"),
                                  accent: true, chevron: false)
                    }
                    .buttonStyle(.plain)
                    optionDivider
                }
                NavigationLink {
                    DataSourcesView()
                } label: {
                    optionRow(String(localized: "Data Sources"), accent: false, chevron: true)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func optionRow(_ title: String, accent: Bool, chevron: Bool) -> some View {
        HStack {
            Text(title)
                .font(StrandFont.pro(17))
                .foregroundStyle(accent ? StrandPalette.accent : StrandPalette.textPrimary)
            Spacer()
            if chevron {
                Image(systemName: "chevron.right")
                    .font(StrandFont.pro(13, weight: .semibold))
                    .foregroundStyle(StrandPalette.textTertiary)
                    .accessibilityHidden(true)
            }
        }
        .padding(.vertical, 13)
        .contentShape(Rectangle())
    }

    private var optionDivider: some View {
        Rectangle().fill(StrandPalette.hairline).frame(height: NoopMetrics.hairlineWidth)
    }

    /// W, or the range named by the DEBUG launch argument `--metric-range week|month|sixMonths|year`
    /// (simulator screenshots).
    private static var initialRange: MetricHealthRange {
        #if DEBUG
        let args = CommandLine.arguments
        if let i = args.firstIndex(of: "--metric-range"), i + 1 < args.count,
           let range = MetricHealthRange(rawValue: args[i + 1]) {
            return range
        }
        #endif
        return .week
    }

    // MARK: - Load

    /// This metric's own series and per-day provenance, read through the loader the All Metrics card
    /// shares, so the card and this page show the same latest reading.
    private func load() async {
        guard let result = await MetricSeriesLoader.load(metric, repo: repo, skinTemp: skinTempPreferred,
                                                         fullStepsHistory: range == .year) else { return }
        series = result.series
        sourceByDay = result.sourceByDay
        skinTempNote = result.skinTempNote
        spo2Empty = metric.source == "my-whoop"
            ? Spo2EmptyState.resolve(key: metric.key, family: repo.activeStrapFamily(),
                                     candidateDisplayOn: PuffinExperiment.spo2CandidateDisplayEnabled)
            : .standard
        loaded = true
    }
}

/// Opens the page at its top whatever the content does while it loads (iOS 17 / macOS 14 and later).
private struct TopScrollAnchor: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 17.0, macOS 14.0, *) {
            content.defaultScrollAnchor(.top)
        } else {
            content
        }
    }
}

/// The Fitness Age not-ready lead: a concrete countdown of nights-of-wear still needed (from the shared
/// `nightsUntilReady`), noting the profile basics only when they're actually missing. File-scope so the
/// Fitness Age metric page's empty state reads it from one source. Says what appears and when, with no "we".
func fitnessReadyLeadCopy(rhrDays: Int, hasAge: Bool, hasSex: Bool) -> String {
    let remaining = FitnessAgeEngine.nightsUntilReady(rhrDays: rhrDays)
    let needsBasics = !hasAge || !hasSex
    switch (remaining, needsBasics) {
    case (0, false): return String(localized: "Fitness Age appears after a few more days of wear.")
    case (0, true):  return String(localized: "Add your age and sex below to see your Fitness Age.")
    case (1, false): return String(localized: "Fitness Age appears after 1 more night of wear.")
    case (1, true):  return String(localized: "Add your age and sex below; Fitness Age appears after 1 more night of wear.")
    case (let n, false): return String(localized: "Fitness Age appears after \(n) more nights of wear.")
    case (let n, true):  return String(localized: "Add your age and sex below; Fitness Age appears after \(n) more nights of wear.")
    }
}
