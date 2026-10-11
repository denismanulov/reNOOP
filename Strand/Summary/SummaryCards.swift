//  SummaryCards.swift
//  NOOP · Summary home — the card building blocks (Apple Health Summary idiom).
//
//  Solid rounded cards on the grouped canvas: a small tinted icon + title row with a chevron, then one
//  bold value and at most a caption and a mini chart. Glass is reserved for floating controls.

import SwiftUI
import StrandDesign
import StrandAnalytics

// MARK: - Container + section header

/// The ONE card on the grouped canvas: white, Health's corner radius, full width. Draw a card with this,
/// never with a hand-made `.background(RoundedRectangle)`, so every card shares one radius and fill (Craft-3).
struct SummaryCard<Content: View>: View {
    /// Summary's margins by default; `.summaryCardList` for a card of rows that carry their own height,
    /// `.summaryCardRow` for a card that is one list row.
    var insets: EdgeInsets = .summaryCard
    @ViewBuilder var content: Content
    @Environment(\.summaryCardFill) private var fill

    var body: some View {
        content
            .padding(insets)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(fill, in: RoundedRectangle(cornerRadius: Self.radius, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: Self.radius, style: .continuous))
    }

    static var radius: CGFloat { 22 }
}

/// The card colour for a page whose cards sit on a different canvas than the Summary's (Health's Sleep
/// Score page is a step lighter in dark mode). Set once on the page; every `SummaryCard` under it follows.
private struct SummaryCardFillKey: EnvironmentKey {
    static let defaultValue = StrandPalette.summaryCard
}

extension EnvironmentValues {
    var summaryCardFill: Color {
        get { self[SummaryCardFillKey.self] }
        set { self[SummaryCardFillKey.self] = newValue }
    }
}

extension EdgeInsets {
    /// A Summary card's own margins: 16 at the sides, 12 over the title row, 14 under the last line.
    static var summaryCard: EdgeInsets { EdgeInsets(top: 12, leading: 16, bottom: 14, trailing: 16) }
    /// A card of rows set edge to edge, each carrying its own vertical padding and hairline (Health's
    /// grouped list inside a card).
    static var summaryCardList: EdgeInsets { EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16) }
    /// A card that is a single list row: even margins above and below.
    static var summaryCardRow: EdgeInsets { EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16) }
}

/// Health's section title on the grouped canvas (SF Pro bold 22, flush with the cards' inset), with an
/// optional trailing action ("Edit"). The ONE section header: every grouped-canvas screen uses it, so a
/// title reads the same on Summary, Sleep, a metric page and the schedule (Craft-3).
struct SectionHeader: View {
    let title: LocalizedStringKey
    var actionTitle: LocalizedStringKey? = nil
    var action: (() -> Void)? = nil

    @Environment(\.dynamicTypeSize) private var dts

    var body: some View {
        let layout = dts.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline))
        layout {
            Text(title)
                .font(StrandFont.pro(22, weight: .bold))
                .foregroundStyle(StrandPalette.textPrimary)
            if !dts.isAccessibilitySize { Spacer() }
            if let actionTitle, let action {
                Button(action: action) {
                    Text(actionTitle)
                        .font(StrandFont.body)
                        .foregroundStyle(StrandPalette.accent)
                        // A 44 pt target around the text without moving the header (CR-6).
                        .contentShape(Rectangle().inset(by: -12))
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
        .padding(.top, NoopMetrics.space4)
        .accessibilityAddTraits(.isHeader)
    }
}

/// Icon + tinted title on the left, chevron on the right — the header row every Summary card shares.
struct SummaryCardTitleRow: View {
    let icon: String
    let title: String
    let tint: Color
    var trailing: String? = nil
    /// Off for a card that opens nothing.
    var chevron = true

    @Environment(\.dynamicTypeSize) private var dts

    var body: some View {
        if dts.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    glyph
                    titleText
                    Spacer(minLength: 8)
                    chevronGlyph
                }
                if let trailing { trailingText(trailing) }
            }
        } else {
            HStack(spacing: 6) {
                glyph
                titleText
                Spacer(minLength: 8)
                if let trailing { trailingText(trailing) }
                chevronGlyph
            }
        }
    }

    private var glyph: some View {
        Image(systemName: icon)
            .font(StrandFont.pro(13, weight: .semibold))
            .foregroundStyle(tint)
            .accessibilityHidden(true)
    }

    private var titleText: some View {
        Text(title)
            .font(StrandFont.headline)
            .foregroundStyle(StrandPalette.text(for: tint))
            .lineLimit(dts.isAccessibilitySize ? nil : 1)
    }

    private func trailingText(_ text: String) -> some View {
        Text(text)
            .font(StrandFont.footnote)
            .foregroundStyle(StrandPalette.textSecondary)
            .lineLimit(dts.isAccessibilitySize ? nil : 1)
    }

    @ViewBuilder private var chevronGlyph: some View {
        if chevron {
            Image(systemName: "chevron.right")
                .font(StrandFont.pro(12, weight: .semibold))
                .foregroundStyle(StrandPalette.textTertiary)
                .accessibilityHidden(true)
        }
    }
}

// MARK: - Rings card

struct SummaryRingRow: Identifiable {
    let id: String
    let title: String
    let value: String
    let unit: String
    let caption: String?
    let color: Color
    let route: TabRoute
}

// MARK: - Pinned metric card

struct SummaryMetricCard: View {
    let metric: KeyMetric
    let reading: SummaryMetricReading
    let series: [Double]
    /// "Today" / "Yesterday" / a date: when the value was measured (`SummaryStamp`).
    var stamp: String? = nil

    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .title2) private var valueSize: CGFloat = 24

    var body: some View {
        NavigationLink(value: reading.route) {
            SummaryCard {
                VStack(alignment: .leading, spacing: 10) {
                    SummaryCardTitleRow(icon: metric.healthIcon, title: metric.title,
                                        tint: metric.healthTint, trailing: stamp)
                    HStack(alignment: .bottom, spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(alignment: .firstTextBaseline, spacing: 3) {
                                Text(verbatim: reading.value)
                                    .font(StrandFont.number(valueSize, weight: .bold))
                                    .foregroundStyle(StrandPalette.textPrimary)
                                if !reading.unit.isEmpty {
                                    Text(verbatim: reading.unit)
                                        .font(StrandFont.subhead.weight(.semibold))
                                        .foregroundStyle(StrandPalette.textSecondary)
                                }
                            }
                            if let caption = reading.caption {
                                Text(caption)
                                    .font(StrandFont.footnote)
                                    .foregroundStyle(StrandPalette.textSecondary)
                                    .lineLimit(dts.isAccessibilitySize ? 3 : 1)
                            }
                        }
                        Spacer(minLength: 8)
                        SummaryMiniChart(values: series, style: reading.chart, tint: metric.healthTint)
                            .frame(width: 72, height: 30)
                    }
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

/// A week at a glance: a line for continuous vitals, bars for daily totals. Draws nothing under three
/// points: two bars read as a pair of capsules (a toggle), not as a week.
struct SummaryMiniChart: View {
    let values: [Double]
    let style: SummaryMetricReading.ChartStyle
    let tint: Color

    var body: some View {
        if values.count >= 3 {
            switch style {
            case .line:
                Sparkline(values: values, gradient: Gradient(colors: [tint.opacity(0.55), tint]),
                          lineWidth: 2, showsArea: false, showsHead: true, showsHover: false)
            case .bars:
                bars
            }
        } else {
            Color.clear
        }
    }

    private var bars: some View {
        let top = values.max() ?? 0
        return GeometryReader { geo in
            HStack(alignment: .bottom, spacing: 3) {
                ForEach(Array(values.enumerated()), id: \.offset) { index, v in
                    Capsule()
                        .fill(tint.opacity(index == values.count - 1 ? 1 : 0.45))
                        .frame(height: max(3, geo.size.height * (top > 0 ? v / top : 0)))
                }
            }
            .frame(maxHeight: .infinity, alignment: .bottom)
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Highlight card

/// The ONE Highlights card, as Health sets it on Summary, Sleep and a data type's page (Craft-3): the
/// category row, one sentence, then — under a hairline — the evidence: two figures and a chart. The row,
/// the sentence and the hairline are fixed; what legitimately differs between screens is a slot or a
/// parameter: the figures (each page formats its own readings) and the chart (a week of Effort, the nights
/// behind a sleep claim, a fortnight of readings), each of which brings its own height.
struct HighlightCard<Figures: View, Chart: View>: View {
    let icon: String
    let title: String
    let tint: Color
    let sentence: String
    /// On for a card that opens a page (Summary's), off for one that opens nothing.
    var chevron = false
    /// The gap between the card's rows.
    var spacing: CGFloat = 8
    /// Off when the highlight has no evidence to set under its sentence (then no hairline either).
    var showsEvidence = true
    @ViewBuilder var figures: Figures
    @ViewBuilder var chart: Chart

    var body: some View {
        SummaryCard {
            VStack(alignment: .leading, spacing: spacing) {
                SummaryCardTitleRow(icon: icon, title: title, tint: tint, chevron: chevron)
                Text(verbatim: sentence)
                    .font(StrandFont.headline)
                    .foregroundStyle(StrandPalette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                if showsEvidence {
                    Rectangle().fill(StrandPalette.hairline).frame(height: NoopMetrics.hairlineWidth)
                    figures
                    chart
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

extension HighlightCard where Chart == EmptyView {
    /// A highlight whose figures carry their own bars (Summary's figure pair).
    init(icon: String, title: String, tint: Color, sentence: String, chevron: Bool = false,
         spacing: CGFloat = 8, showsEvidence: Bool = true, @ViewBuilder figures: () -> Figures) {
        self.init(icon: icon, title: title, tint: tint, sentence: sentence, chevron: chevron,
                  spacing: spacing, showsEvidence: showsEvidence, figures: figures, chart: { EmptyView() })
    }
}

struct SummaryHighlightCard: View {
    let highlight: SummaryHighlight
    /// The last seven days of Effort, drawn under a training-variety highlight (which has no figure pair).
    var effortWeek: [Double] = []

    var body: some View {
        let pair = figures
        // The claim is "every day looks alike": seven near-equal bars show it at a glance, where the
        // monotony index itself is a number nobody reads.
        let showsWeek = pair == nil && highlight.key == "monotony" && effortWeek.count >= 2
        NavigationLink(value: TabRoute.metric(highlight.routeKey)) {
            HighlightCard(icon: icon, title: highlight.title, tint: tint, sentence: highlight.sentence,
                          chevron: true, showsEvidence: pair != nil || showsWeek) {
                if let pair { pair }
            } chart: {
                if showsWeek {
                    weekBars(effortWeek)
                    Text("Effort, last 7 days")
                        .font(StrandFont.footnote)
                        .foregroundStyle(StrandPalette.textSecondary)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }

    /// The two figures a Health highlight sets under its sentence: the latest reading and the baseline it
    /// was read against (or the 7-day against the 28-day load). Monotony has no pair to show.
    private var figures: HighlightFigures? {
        switch highlight.evidenceData {
        case .metric(let value, let baseline, let unit, let decimals):
            // The engine's unit is a locale-free key ("ms", "bpm", "br/min"); the catalogue translates it.
            return HighlightFigures(leftTitle: String(localized: "Latest"), left: ReadinessCopy.number(value, decimals: decimals),
                                    rightTitle: String(localized: "Your Normal"), right: ReadinessCopy.number(baseline, decimals: decimals),
                                    unit: String(localized: String.LocalizationValue(unit)),
                                    leftValue: value, rightValue: baseline, tint: tint)
        case .trainingLoad(let acute, let chronic):
            return HighlightFigures(leftTitle: String(localized: "Last 7 Days"), left: ReadinessCopy.number(acute, decimals: 1),
                                    rightTitle: String(localized: "Last 28 Days"), right: ReadinessCopy.number(chronic, decimals: 1),
                                    unit: "", leftValue: acute, rightValue: chronic, tint: tint)
        case .monotony, .none:
            return nil
        }
    }

    /// A week of daily bars across the card, today's solid and the rest faded — Health's highlight chart.
    private func weekBars(_ values: [Double]) -> some View {
        let top = max(values.max() ?? 0, 1e-6)
        return HStack(alignment: .bottom, spacing: 0) {
            ForEach(Array(values.enumerated()), id: \.offset) { index, v in
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(tint.opacity(index == values.count - 1 ? 1 : 0.4))
                    .frame(width: 22, height: max(4, 64 * CGFloat(v / top)))
                    .frame(maxWidth: .infinity)
            }
        }
        .frame(height: 64, alignment: .bottom)
        .accessibilityHidden(true)
    }

    /// The pinned metric a highlight is about; its glyph and hue are that metric's.
    private var metric: KeyMetric {
        switch highlight.key {
        case "hrv": return .hrv
        case "rhr": return .restingHr
        case "respRate": return .respiratory
        default: return .effort
        }
    }

    private var icon: String { metric.healthIcon }

    private var tint: Color { metric.healthTint }
}

/// A Health highlight's figure pair: the tinted reading on the left, the grey one it is read against on the
/// right, and a bar for each under them.
struct HighlightFigures: View {
    let leftTitle: String
    let left: String
    let rightTitle: String
    let right: String
    let unit: String
    let leftValue: Double
    let rightValue: Double
    let tint: Color

    var body: some View {
        let top = max(leftValue, rightValue, 1e-6)
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                figure(leftTitle, left, tint: tint)
                Spacer()
                figure(rightTitle, right, tint: StrandPalette.textSecondary, trailing: true)
            }
            VStack(alignment: .leading, spacing: 5) {
                bar(leftValue / top, tint: tint)
                bar(rightValue / top, tint: StrandPalette.textTertiary.opacity(0.45))
            }
            .accessibilityHidden(true)
        }
    }

    private func figure(_ title: String, _ value: String, tint: Color, trailing: Bool = false) -> some View {
        VStack(alignment: trailing ? .trailing : .leading, spacing: 2) {
            Text(title)
                .font(StrandFont.footnote.weight(.semibold))
                .foregroundStyle(StrandPalette.textSecondary)
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(verbatim: value)
                    .font(StrandFont.number(22, weight: .bold))
                    .foregroundStyle(StrandPalette.text(for: tint))
                if !unit.isEmpty {
                    Text(verbatim: unit)
                        .font(StrandFont.subhead.weight(.semibold))
                        .foregroundStyle(StrandPalette.textSecondary)
                }
            }
        }
    }

    private func bar(_ fraction: Double, tint: Color) -> some View {
        GeometryReader { geo in
            Capsule().fill(tint)
                .frame(width: max(6, geo.size.width * CGFloat(min(1, max(0, fraction)))))
        }
        .frame(height: 8)
    }
}

// MARK: - Strap status

/// The active device's link state as quiet text in the Summary's bar, the way a Health card shows
/// its time: battery glyph + percent, "Syncing", or "Not connected". Tap → Devices. Resolves through the
/// `StrapBatteryDisplay`, the one place that decides what the link state may honestly claim.
struct SummaryStrapStatus: View {
    /// A bar item: glyphs and the battery figure only, the words left to VoiceOver.
    var compact = false

    @EnvironmentObject private var live: LiveState
    @EnvironmentObject private var router: NavRouter
    @State private var syncing = false

    private var display: StrapBatteryDisplay {
        .resolve(activeIsWhoop: live.activeIsWhoop, connected: live.connected,
                 batteryPct: live.batteryPct, charging: live.charging,
                 ringPct: live.ouraBatteryPct, ringCharging: live.ouraWearState == .charging)
    }

    var body: some View {
        Group {
            if case .notActiveDevice = display {
                EmptyView()
            } else {
                Button { router.openDevices() } label: { label }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text(accessibility))
            }
        }
        .debouncedSyncSignal(live.backfilling, into: $syncing)
    }

    @ViewBuilder private var label: some View {
        HStack(spacing: 5) {
            if syncing {
                ProgressView().controlSize(.small)
                if !compact { Text("Syncing") }
            } else {
                switch display {
                case .offline, .notActiveDevice:
                    Image(systemName: "antenna.radiowaves.left.and.right.slash")
                    if !compact { Text("Not connected") }
                case .pending(let charging):
                    Image(systemName: charging ? "battery.100percent.bolt" : "battery.50percent")
                case .charge(let pct, let charging, _):
                    Image(systemName: charging ? "battery.100percent.bolt" : Self.batterySymbol(pct))
                    Text(verbatim: "\(Int(pct.rounded()))%")
                        .monospacedDigit()
                }
            }
        }
        .font(StrandFont.footnote)
        .foregroundStyle(StrandPalette.textSecondary)
        .lineLimit(1)
    }

    private var accessibility: String {
        if syncing { return String(localized: "Syncing strap history") }
        switch display {
        case .offline, .notActiveDevice: return String(localized: "Strap not connected")
        case .pending: return String(localized: "Strap")
        case .charge(let pct, _, let isRing):
            let n = Int(pct.rounded())
            return isRing ? String(localized: "Ring battery \(n) percent") : String(localized: "Strap battery \(n) percent")
        }
    }

    static func batterySymbol(_ pct: Double) -> String {
        switch pct {
        case ..<13: return "battery.0percent"
        case ..<38: return "battery.25percent"
        case ..<63: return "battery.50percent"
        case ..<88: return "battery.75percent"
        default: return "battery.100percent"
        }
    }
}

// MARK: - Day pager

/// ‹ day › — the one place a screen names the day (or night) it shows. The arrows step through the days
/// on record; the title opens a calendar. Shared by the Summary and the Sleep page so both move the same way.
struct DayPager<Picker: View>: View {
    let title: String
    let canGoBack: Bool
    let canGoForward: Bool
    let onBack: () -> Void
    let onForward: () -> Void
    var backLabel: LocalizedStringKey = "Previous day"
    var forwardLabel: LocalizedStringKey = "Next day"
    @Binding var showPicker: Bool
    @ViewBuilder var picker: Picker

    var body: some View {
        HStack(spacing: 4) {
            arrow("chevron.left", enabled: canGoBack, action: onBack)
                .accessibilityLabel(Text(backLabel))
            Spacer(minLength: 0)
            Button { showPicker = true } label: {
                Text(title)
                    .font(StrandFont.headline)
                    .foregroundStyle(StrandPalette.textPrimary)
            }
            .buttonStyle(.plain)
            // A popover where there is room for one; on iPhone the system turns it into a sheet, as Health
            // shows its calendars, so the calendar is never squeezed into a compact popover.
            .popover(isPresented: $showPicker) {
                picker.presentationDetents([.medium, .large])
            }
            Spacer(minLength: 0)
            arrow("chevron.right", enabled: canGoForward, action: onForward)
                .accessibilityLabel(Text(forwardLabel))
        }
    }

    private func arrow(_ symbol: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button {
            withAnimation(StrandMotion.interactive) { action() }
        } label: {
            Image(systemName: symbol)
                .font(StrandFont.pro(15, weight: .semibold))
                .foregroundStyle(enabled ? StrandPalette.accent : StrandPalette.textTertiary)
                .frame(minWidth: 44, minHeight: 36)
                .contentShape(Rectangle().inset(by: -4))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
