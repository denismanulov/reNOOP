//  BrowseView.swift
//  NOOP · the Browse (search) tab — every screen outside the three main tabs, in one searchable list.
//
//  Modelled on the iOS 26 Health app's search tab: a large title, a bold "Categories" header over one
//  card of rows (a tinted glyph, the name, a chevron) in alphabetical order, and a second, headerless
//  card below it, as Health keeps Clinical Documents apart. The rows push `MoreDestination` values so
//  a re-tap of the tab pops them off its bound path (#135/#198). Settings is not here: it opens from the
//  Summary avatar (Health's profile sheet).

import SwiftUI
import StrandDesign

struct BrowseView: View {
    @AppStorage("noop.coachEnabled") private var coachEnabled = true
    @State private var query = ""
    @ScaledMetric(relativeTo: .body) private var glyphWidth: CGFloat = 28
    @ScaledMetric(relativeTo: .body) private var glyphHeight: CGFloat = 24

    struct Entry: Identifiable {
        let id: MoreDestination
        let title: String
        let icon: String
        let tint: Color
    }

    /// Health's categories card: the places to read and log data.
    private var categories: [Entry] {
        var rows = [
            Entry(id: .allMetrics, title: String(localized: "All Metrics"), icon: "chart.bar.fill", tint: StrandPalette.healthOxygen),
            Entry(id: .trends, title: String(localized: "Trends"), icon: HealthTrendsUnits.icon, tint: StrandPalette.healthNutrition),
            Entry(id: .journal, title: String(localized: "Journal"), icon: "text.book.closed.fill", tint: StrandPalette.healthMind),
            Entry(id: .insightsHub, title: String(localized: "What Moves You"), icon: "lightbulb.max.fill", tint: StrandPalette.healthTemperature),
            Entry(id: .labBook, title: String(localized: "Lab Results"), icon: "testtube.2", tint: StrandPalette.healthSleepCore),
            Entry(id: .sleep, title: String(localized: "Sleep"), icon: "bed.double.fill", tint: StrandPalette.sleepSchedule),
        ]
        if coachEnabled {
            rows.append(Entry(id: .coach, title: String(localized: "Coach"), icon: "bubble.left.and.text.bubble.right.fill", tint: StrandPalette.healthBody))
        }
        return rows.sorted(by: Self.alphabetical)
    }

    /// The second card: tools that act rather than show history.
    private var tools: [Entry] {
        [
            Entry(id: .live, title: String(localized: "Heart Rate"), icon: "heart.fill", tint: StrandPalette.healthHeart),
            Entry(id: .breathe, title: String(localized: "Mindfulness"), icon: "figure.mind.and.body", tint: StrandPalette.healthRespiratory),
            Entry(id: .devices, title: String(localized: "Devices"), icon: "applewatch", tint: StrandPalette.textSecondary),
        ].sorted(by: Self.alphabetical)
    }

    private static func alphabetical(_ a: Entry, _ b: Entry) -> Bool {
        a.title.localizedStandardCompare(b.title) == .orderedAscending
    }

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespaces) }

    private static func contains(_ text: String, _ q: String) -> Bool {
        text.range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil
    }

    /// Rows whose title contains the query (case- and diacritic-insensitive), as one flat list.
    private var hits: [Entry] {
        let q = trimmedQuery
        return (categories + tools)
            .filter { Self.contains($0.title, q) }
            .sorted(by: Self.alphabetical)
    }

    /// The metric pages whose name (or the short name its Summary card uses, "HRV") contains the query,
    /// one per metric, alphabetically — as Health's search lists data types under its categories.
    private var metricHits: [MetricDescriptor] {
        let q = trimmedQuery
        let matching = MetricCatalog.all.filter {
            Self.contains($0.title, q) || Self.contains(AllMetricsCatalog.shortTitle($0), q)
        }
        return AllMetricsCatalog.oneSourcePerKey(matching, latestDay: [:])
            .sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    private var searching: Bool { !trimmedQuery.isEmpty }

    var body: some View {
        List {
            if searching {
                let metrics = metricHits
                if !hits.isEmpty { Section { rows(hits) } }
                if !metrics.isEmpty { Section { metricRows(metrics) } }
            } else {
                Section {
                    rows(categories)
                } header: {
                    Text("Categories")
                        .font(StrandFont.pro(22, weight: .bold))
                        .foregroundStyle(StrandPalette.textPrimary)
                        .textCase(nil)
                        .padding(.bottom, 4)
                }
                Section { rows(tools) }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(StrandPalette.summaryCanvas.ignoresSafeArea())
        .navigationTitle("Browse")
        .navigationBarTitleDisplayMode(.large)
        .searchable(text: $query)
        .overlay {
            if searching && hits.isEmpty && metricHits.isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
    }

    private func rows(_ entries: [Entry]) -> some View {
        ForEach(entries) { entry in
            NavigationLink(value: entry.id) {
                rowLabel(entry.title, icon: entry.icon, tint: entry.tint)
            }
            // Health's rows are 51 pt tall; the default insets on top of the label ran taller.
            .frame(minHeight: 51)
            .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 20))
        }
    }

    /// A metric's page, under the glyph and tint its All Metrics card carries. The route pins the source,
    /// so the page opened is the one this row names.
    private func metricRows(_ metrics: [MetricDescriptor]) -> some View {
        ForEach(metrics) { metric in
            NavigationLink(value: TabRoute.metricSourced(key: metric.key, source: metric.source)) {
                rowLabel(metric.title, icon: AllMetricsCatalog.category(metric).icon,
                         tint: MetricHealthStyle.tint(metric))
            }
            .frame(minHeight: 51)
            .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 0, trailing: 20))
        }
    }

    private func rowLabel(_ title: String, icon: String, tint: Color) -> some View {
        Label {
            Text(title)
                .font(StrandFont.pro(17, weight: .semibold))
                .foregroundStyle(StrandPalette.textPrimary)
        } icon: {
            // Health draws every category glyph two-tone and inside one box, so a wide bed and a narrow
            // watch read as the same size and the titles start on one line.
            Image(systemName: icon)
                .resizable()
                .scaledToFit()
                .fontWeight(.medium)
                .symbolRenderingMode(.hierarchical)
                .foregroundStyle(tint)
                .frame(width: glyphWidth, height: glyphHeight)
        }
    }
}

/// Every screen the Browse list links to, as a `Hashable` value the tab's `NavigationPath` can carry
/// (#198): a closure-destination push would bypass the path and be un-poppable on tab re-tap. The
/// per-screen chrome lives at the single `navigationDestination(for:)` registration in
/// `RootTabView.browseTab`.
enum MoreDestination: Hashable {
    case allMetrics, trends, journal, insightsHub, labBook, coach, sleep
    case live, breathe, devices

    @ViewBuilder var destination: some View {
        switch self {
        case .allMetrics:  AllMetricsView()
        case .trends:      TrendsView()
        case .journal:     JournalView()
        case .insightsHub: InsightsHubView()
        case .labBook:     LabBookView()
        case .coach:       CoachView()
        case .sleep:       SleepHealthView()
        case .live:        LiveView()
        case .breathe:     BreathingView()
        case .devices:     DevicesView()
        }
    }
}
