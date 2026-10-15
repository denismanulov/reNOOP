//  HighlightsPages.swift
//  NOOP · Highlights — the two pages behind "Show All": one subject's highlights filed by how often each
//  is renewed, and every subject that has any, as the Health app sets them (iOS 26).

import SwiftUI
import StrandDesign

/// The row at the foot of a list that opens the next one: a title and a chevron in a card of its own.
struct HighlightsLinkRow: View {
    let title: LocalizedStringKey

    var body: some View {
        SummaryCard(insets: .summaryCardRow) {
            HStack {
                Text(title)
                    .font(StrandFont.body)
                    .foregroundStyle(StrandPalette.textPrimary)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(StrandFont.pro(12, weight: .semibold))
                    .foregroundStyle(StrandPalette.textTertiary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 6)
        }
    }
}

/// A subject's highlights under "Daily", "Weekly", "Monthly" and "Yearly", each section only when it has one.
struct HighlightSections: View {
    let group: HighlightGroup

    var body: some View {
        ForEach(HighlightCadence.allCases, id: \.self) { cadence in
            let items = group.items(cadence)
            if !items.isEmpty {
                // Health sets these titles over the cards' text, not over their edge.
                SectionHeader(title: cadence.sectionTitle)
                    .padding(.leading, 12)
                ForEach(items) { HighlightItemCard(item: $0) }
            }
        }
    }
}

// MARK: - One subject

struct SubjectHighlightsView: View {
    let subject: HighlightSubject.Kind

    @EnvironmentObject private var repo: Repository
    @AppStorage(UnitPrefs.systemKey) private var unitSystemRaw = UnitSystem.metric.rawValue
    @AppStorage(UnitPrefs.temperatureKey) private var temperatureRaw = ""
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue
    @State private var group: HighlightGroup?
    @State private var loaded = false

    private var name: String {
        switch subject {
        case .metric(let key, let source): return MetricCatalog.metric(key: key, source: source)?.title ?? ""
        case .workouts: return String(localized: "Workouts")
        case .heartRate: return String(localized: "Heart Rate")
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if let group {
                    HighlightSections(group: group)
                } else if loaded {
                    EmptyStateView(title: Text("No Highlights"), systemImage: "sparkles")
                }
                if loaded {
                    NavigationLink(value: TabRoute.allHighlights) {
                        HighlightsLinkRow(title: "Show All Highlights")
                    }
                    .buttonStyle(.plain)
                    .padding(.top, NoopMetrics.space3)
                }
            }
            .padding(.horizontal, HighlightChartStyle.pageGutter)
            .padding(.bottom, NoopMetrics.space4)
        }
        .background(StrandPalette.summaryCanvas.ignoresSafeArea())
        .navigationTitle(Text(verbatim: HighlightCopy.title(for: name)))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task(id: repo.refreshSeq) {
            let units = HealthTrendsUnits.resolve(system: unitSystemRaw, temperature: temperatureRaw,
                                                  effortScale: effortScaleRaw)
            group = await HealthHighlightLoader.group(subject, repo: repo, units: units)
            loaded = true
        }
    }
}

// MARK: - Every subject

struct AllHighlightsView: View {
    @EnvironmentObject private var repo: Repository
    @AppStorage(UnitPrefs.systemKey) private var unitSystemRaw = UnitSystem.metric.rawValue
    @AppStorage(UnitPrefs.temperatureKey) private var temperatureRaw = ""
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue
    @State private var groups: [HighlightGroup] = []
    @State private var loaded = false

    /// The categories that have a subject, in Health's order (by name), each with its subjects.
    private var sections: [(category: HealthCategory, subjects: [HighlightSubject])] {
        let byCategory = Dictionary(grouping: groups.map(\.subject), by: \.category)
        return byCategory.keys
            .sorted { $0.title.localizedCompare($1.title) == .orderedAscending }
            .map { (category: $0, subjects: byCategory[$0] ?? []) }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                hero
                if loaded && groups.isEmpty {
                    EmptyStateView(title: Text("No Highlights"), systemImage: "sparkles")
                }
                ForEach(sections, id: \.category) { section in
                    categoryHeader(section.category)
                    SummaryCard(insets: .summaryCardList) {
                        VStack(spacing: 0) {
                            ForEach(Array(section.subjects.enumerated()), id: \.element.id) { index, subject in
                                if index > 0 {
                                    Rectangle().fill(StrandPalette.hairline).frame(height: NoopMetrics.hairlineWidth)
                                }
                                NavigationLink(value: TabRoute.highlights(subject.kind)) { row(subject.title) }
                                    .buttonStyle(.plain)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, HighlightChartStyle.pageGutter)
            .padding(.bottom, NoopMetrics.space4)
        }
        .background(StrandPalette.summaryCanvas.ignoresSafeArea())
        .navigationTitle(Text("All Highlights"))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task(id: repo.refreshSeq) {
            let units = HealthTrendsUnits.resolve(system: unitSystemRaw, temperature: temperatureRaw,
                                                  effortScale: effortScaleRaw)
            groups = await HealthHighlightLoader.all(repo: repo, units: units)
            loaded = true
        }
    }

    private var hero: some View {
        VStack(spacing: 22) {
            Image(systemName: "list.clipboard")
                .font(StrandFont.pro(34, weight: .medium))
                .foregroundStyle(StrandPalette.accent)
                .frame(width: 80, height: 80)
                .background(StrandPalette.summaryCard, in: Circle())
                .accessibilityHidden(true)
            Text("Health Highlights")
                .font(StrandFont.pro(28, weight: .bold))
                .foregroundStyle(StrandPalette.textPrimary)
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 19)
        .padding(.bottom, 10)
    }

    private func categoryHeader(_ category: HealthCategory) -> some View {
        HStack(spacing: 10) {
            Image(systemName: category.icon)
                .font(StrandFont.pro(26, weight: .semibold))
                .foregroundStyle(tint(category))
                .frame(width: 34)
                .accessibilityHidden(true)
            Text(verbatim: category.title)
                .font(StrandFont.pro(22, weight: .bold))
                .foregroundStyle(StrandPalette.textPrimary)
            Spacer()
        }
        .padding(.leading, 6)
        .padding(.top, 22)
        .padding(.bottom, 8)
        .accessibilityAddTraits(.isHeader)
    }

    private func row(_ title: String) -> some View {
        HStack {
            Text(verbatim: title)
                .font(StrandFont.body)
                .foregroundStyle(StrandPalette.textPrimary)
            Spacer()
            Image(systemName: "chevron.right")
                .font(StrandFont.pro(12, weight: .semibold))
                .foregroundStyle(StrandPalette.textTertiary)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, 6)
        .frame(minHeight: 51)
        .contentShape(Rectangle())
    }

    private func tint(_ category: HealthCategory) -> Color {
        switch category {
        case .activity: return StrandPalette.activityTitle
        case .heart: return StrandPalette.healthHeart
        case .sleep: return StrandPalette.sleepSchedule
        case .respiratory: return StrandPalette.healthRespiratory
        case .bodyMeasurements: return StrandPalette.healthBody
        case .mentalWellbeing: return StrandPalette.healthMind
        case .nutrition: return StrandPalette.healthNutrition
        }
    }
}
