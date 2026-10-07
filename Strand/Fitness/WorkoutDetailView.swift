//  WorkoutDetailView.swift
//  NOOP · one workout, laid out as the iOS 26 Fitness app's workout page: the date as the title, a large
//  activity glyph with the time range and source, "Workout Details" as a two-column grid of coloured
//  figures, Effort, the heart-rate curve with time in each zone, recovery after the session, and the map.

import SwiftUI
import Charts
import StrandDesign
import StrandAnalytics
import StrandImport
import WhoopStore

struct WorkoutDetailView: View {
    let row: WorkoutRow

    @EnvironmentObject private var repo: Repository
    @StateObject private var profile = ProfileStore()
    @Environment(\.dismiss) private var dismiss
    /// The workout's own actions, as its row's context menu offers them (`WorkoutRowMenu`).
    @State private var editing: WorkoutEditTarget?
    /// The route full size, opened from the preview.
    @State private var showMap = false

    @AppStorage(UnitPrefs.systemKey) private var unitSystemRaw = UnitSystem.metric.rawValue
    @AppStorage(UnitPrefs.distanceSystemKey) private var distanceSystemRaw = ""
    private var distanceUnitSystem: UnitSystem {
        UnitPrefs.resolveDistance(system: UnitSystem(rawValue: unitSystemRaw) ?? .metric, override: distanceSystemRaw)
    }
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue
    private var effortScale: EffortScale { UnitPrefs.resolveEffortScale(effortScaleRaw) }

    /// HR curve over the session window (5-min-ish bucket means). Empty until loaded.
    @State private var hrPoints: [TrendPoint] = []
    /// Minutes per zone: the imported split when the row carries one, else the window's raw HR binned into
    /// the profile's zones. nil = no split to show.
    @State private var zoneMinutes: [Double]?
    /// True when the split came from imported WHOOP percentages rather than the strap's raw HR.
    @State private var zonesFromImport = false
    /// #516: nil when the session was not intense enough or post-workout coverage was too thin.
    @State private var heartRateRecovery: HeartRateRecovery.Result?
    /// The GPS route recorded for this session on-device (#524); empty when none was captured.
    @State private var route: [RouteMath.LatLng] = []
    @State private var showRouteExport = false
    /// Steps for an on-foot sport (#398): the count and whether the strap (vs the phone) counted them.
    private struct StepReadout { let count: Int; let fromStrap: Bool }
    @State private var steps: StepReadout?

    @Environment(\.dynamicTypeSize) private var dts
    @ScaledMetric(relativeTo: .largeTitle) private var headerIconSize: CGFloat = 42
    @ScaledMetric(relativeTo: .largeTitle) private var headerCircle: CGFloat = 88
    @ScaledMetric(relativeTo: .subheadline) private var effortBadgeSize: CGFloat = 32
    @ScaledMetric(relativeTo: .subheadline) private var zoneLabelWidth: CGFloat = 64
    @ScaledMetric(relativeTo: .subheadline) private var zoneTimeWidth: CGFloat = 58
    @ScaledMetric(relativeTo: .footnote) private var zoneRangeWidth: CGFloat = 88

    /// Side by side, or stacked at accessibility sizes where the columns would not fit.
    private func columns<Content: View>(spacing: CGFloat = 16, alignment: VerticalAlignment = .top,
                                        @ViewBuilder _ content: () -> Content) -> some View {
        let layout = dts.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(alignment: alignment, spacing: spacing))
        return layout(content)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                header
                detailsSection
                heartRateSection
                recoverySection
                mapSection
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 32)
        }
        .background(StrandPalette.summaryCanvas.ignoresSafeArea())
        .navigationTitle(Text(start, format: .dateTime.weekday(.abbreviated).day().month(.abbreviated)))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            if route.count >= 2 {
                ToolbarItem(placement: .primaryAction) {
                    Button { showRouteExport = true } label: { Image(systemName: "square.and.arrow.up") }
                        .barGlyph()
                        .accessibilityLabel(Text("Export route"))
                }
            }
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    WorkoutRowMenu(row: row, onEdit: { editing = WorkoutEditTarget(row: row, isCopy: $0) },
                                   onDeleted: { dismiss() })
                } label: {
                    Image(systemName: "ellipsis")
                }
                .barGlyph()
                .accessibilityLabel(Text("More"))
            }
        }
        // Saving replaces this row, so the page steps back to the list that shows the new one.
        .workoutEditor($editing) { dismiss() }
        .sheet(isPresented: $showMap) {
            WorkoutRouteMapSheet(points: route, title: WorkoutSource.localizedSport(row.sport))
        }
        .confirmationDialog("Export route", isPresented: $showRouteExport, titleVisibility: .visible) {
            Button("GPX — Strava, Garmin, most apps") { exportRoute(.gpx) }
            Button("FIT — Garmin Connect") { exportRoute(.fit) }
            Button("Cancel", role: .cancel) {}
        }
        .task { await load() }
    }

    private var start: Date { Date(timeIntervalSince1970: TimeInterval(row.startTs)) }
    private var end: Date { Date(timeIntervalSince1970: TimeInterval(row.endTs)) }

    // MARK: - Header

    private var header: some View {
        columns(alignment: .center) {
            WorkoutTypeIcon(workoutType: row.sport, size: headerIconSize, weight: .semibold,
                            color: StrandPalette.activityExerciseText)
                .frame(width: headerCircle, height: headerCircle)
                .background(Circle().fill(StrandPalette.fitnessCard))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(WorkoutSource.localizedSport(row.sport))
                    .font(StrandFont.pro(20))
                    .foregroundStyle(StrandPalette.textPrimary)
                Text(timeRange)
                    .font(StrandFont.pro(17))
                    .foregroundStyle(StrandPalette.textSecondary)
                Text(sourceLabel)
                    .font(StrandFont.pro(17))
                    .foregroundStyle(StrandPalette.textSecondary)
            }
            if !dts.isAccessibilitySize { Spacer(minLength: 0) }
        }
        .accessibilityElement(children: .combine)
    }

    private var timeRange: String {
        let f = AppClock.hourMinuteFormatter()
        return row.endTs > row.startTs ? "\(f.string(from: start))–\(f.string(from: end))" : f.string(from: start)
    }

    private var sourceLabel: String {
        switch WorkoutSource.classify(row.source) {
        case .whoop: return "WHOOP"
        case .apple: return String(localized: "Apple Health")
        case .lifting, .activityFile: return String(localized: "Imported")
        case .manual, .detected: return String(localized: "Recorded on device")
        }
    }

    // MARK: - Workout Details

    private struct Figure {
        let label: LocalizedStringKey
        let value: String
        let unit: String
        let color: Color
    }

    private var figures: [Figure] {
        var out: [Figure] = []
        let active = row.durationS ?? Double(row.endTs - row.startTs)
        let elapsed = Double(row.endTs - row.startTs)
        out.append(Figure(label: "Workout Time", value: Self.clock(active), unit: "", color: StrandPalette.fitnessTime))
        if elapsed - active >= 60 {
            out.append(Figure(label: "Elapsed Time", value: Self.clock(elapsed), unit: "", color: StrandPalette.fitnessTime))
        }
        if let m = row.distanceM, m > 0 {
            let (v, u) = Self.split(Self.distance(m, system: distanceUnitSystem))
            out.append(Figure(label: "Distance", value: v, unit: u, color: StrandPalette.activityStandText))
            if active > 0 {
                let (pv, pu) = Self.split(UnitFormatter.paceFromSecPerKm(active / (m / 1000), system: distanceUnitSystem))
                out.append(Figure(label: "Avg. Pace", value: pv, unit: pu, color: StrandPalette.activityStandText))
            }
        }
        if let kcal = row.energyKcal, kcal > 0 {
            out.append(Figure(label: "Active Calories", value: Self.grouped(kcal), unit: String(localized: "kcal"),
                              color: StrandPalette.activityMoveText))
        }
        if let hr = row.avgHr {
            out.append(Figure(label: "Avg. Heart Rate", value: "\(hr)", unit: String(localized: "bpm"),
                              color: StrandPalette.healthHeart))
        }
        if let hr = row.maxHr {
            out.append(Figure(label: "Max. Heart Rate", value: "\(hr)", unit: String(localized: "bpm"),
                              color: StrandPalette.healthHeart))
        }
        if let steps {
            out.append(Figure(label: "Steps", value: Self.grouped(Double(steps.count)), unit: "",
                              color: StrandPalette.activityStandText))
        }
        return out
    }

    private var detailsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("Workout Details")
            let rows = stride(from: 0, to: figures.count, by: 2).map { Array(figures[$0..<min($0 + 2, figures.count)]) }
            VStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.offset) { index, pair in
                    columns {
                        ForEach(Array(pair.enumerated()), id: \.offset) { figureCell($0.element) }
                        if pair.count == 1, !dts.isAccessibilitySize { Spacer().frame(maxWidth: .infinity) }
                    }
                    .padding(.vertical, 12)
                    if index < rows.count - 1 { Divider() }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
            .background(StrandPalette.summaryCard, in: RoundedRectangle(cornerRadius: 26, style: .continuous))

            if let strain = row.strain { effortCard(strain) }
        }
    }

    private func figureCell(_ f: Figure) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(f.label)
                .font(StrandFont.pro(17))
                .foregroundStyle(StrandPalette.textPrimary)
            (Text(f.value).font(StrandFont.pro(28, weight: .semibold))
             + Text(f.unit.isEmpty ? "" : f.unit.uppercased()).font(StrandFont.pro(20, weight: .semibold)))
                .foregroundStyle(f.color)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private func effortCard(_ strain: Double) -> some View {
        let shown = UnitFormatter.effortValue(strain, scale: effortScale)
        let fraction = min(max(shown / (effortScale == .whoop ? 21 : 100), 0), 1)
        return VStack(alignment: .leading, spacing: 6) {
            Text("Effort")
                .font(StrandFont.pro(17))
                .foregroundStyle(StrandPalette.textPrimary)
            columns(spacing: 10, alignment: .center) {
                Text(effortScale == .whoop ? String(format: "%.1f", shown) : "\(Int(shown.rounded()))")
                    .font(StrandFont.pro(15, weight: .bold))
                    .foregroundStyle(StrandPalette.fitnessEffort)
                    .padding(.horizontal, 8)
                    .frame(minWidth: effortBadgeSize, minHeight: effortBadgeSize)
                    .background(Capsule().fill(StrandPalette.fitnessEffort.opacity(0.2)))
                Text(StrainLoadLabel.forFraction(fraction).lowercased().localizedCapitalized)
                    .font(StrandFont.pro(28, weight: .semibold))
                    .foregroundStyle(StrandPalette.fitnessEffort)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(StrandPalette.summaryCard, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    // MARK: - Heart Rate

    @ViewBuilder private var heartRateSection: some View {
        if hrPoints.count > 1 || zoneMinutes != nil {
            VStack(alignment: .leading, spacing: 10) {
                sectionTitle("Heart Rate")
                VStack(alignment: .leading, spacing: 14) {
                    if hrPoints.count > 1 { hrChart }
                    if hrPoints.count > 1, zoneMinutes != nil { Divider() }
                    if let zoneMinutes { zoneRows(zoneMinutes) }
                }
                .padding(16)
                .background(StrandPalette.summaryCard, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
            }
        }
    }

    private var hrChart: some View {
        let values = hrPoints.map(\.value)
        let avg = row.avgHr.map(Double.init) ?? values.reduce(0, +) / Double(values.count)
        return VStack(alignment: .leading, spacing: 8) {
            VStack(alignment: .leading, spacing: 0) {
                Text("Average Heart Rate")
                    .font(StrandFont.pro(15))
                    .foregroundStyle(StrandPalette.textSecondary)
                (Text("\(Int(avg.rounded()))").font(StrandFont.pro(28, weight: .semibold))
                 + Text(String(localized: "bpm").uppercased()).font(StrandFont.pro(20, weight: .semibold)))
                    .foregroundStyle(StrandPalette.healthHeart)
            }
            Chart(hrPoints, id: \.date) { p in
                LineMark(x: .value("Time", p.date), y: .value("bpm", p.value))
                    .interpolationMethod(.monotone)
                    .foregroundStyle(StrandPalette.healthHeart)
                    .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round))
            }
            .chartYScale(domain: max(0, (values.min() ?? 60) - 10)...((values.max() ?? 160) + 10))
            .chartXScale(domain: start...max(end, start.addingTimeInterval(60)))
            .chartYAxis {
                AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) {
                    AxisGridLine().foregroundStyle(StrandPalette.hairline)
                    AxisValueLabel()
                }
            }
            .chartXAxis {
                AxisMarks(values: [start, end]) {
                    AxisValueLabel(format: .dateTime.hour().minute())
                }
            }
            .frame(height: 150)
        }
    }

    private func zoneRows(_ minutes: [Double]) -> some View {
        let longest = max(minutes.max() ?? 1, 1)
        let bands = profile.hrZoneSet.zones
        return VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(minutes.prefix(5).enumerated()), id: \.offset) { index, m in
                let zone = index + 1
                let color = StrandPalette.fitnessZone(zone)
                let stacked = dts.isAccessibilitySize
                columns(spacing: 10, alignment: .center) {
                    Text("Zone \(zone)")
                        .font(StrandFont.pro(15, weight: .semibold))
                        .foregroundStyle(StrandPalette.fitnessZoneText(zone))
                        .frame(width: stacked ? nil : zoneLabelWidth, alignment: .leading)
                    GeometryReader { geo in
                        Capsule().fill(color)
                            .frame(width: max(6, geo.size.width * m / longest), height: 6)
                            .frame(maxHeight: .infinity, alignment: .center)
                    }
                    .frame(height: 20)
                    HStack(spacing: 10) {
                        Text(Self.clock(m * 60))
                            .font(StrandFont.pro(15, weight: .semibold).monospacedDigit())
                            .foregroundStyle(StrandPalette.textPrimary)
                            .frame(width: stacked ? nil : zoneTimeWidth, alignment: .trailing)
                        if !zonesFromImport, index < bands.count {
                            Text(zoneRange(bands, index))
                                .font(StrandFont.pro(13))
                                .foregroundStyle(StrandPalette.textSecondary)
                                .frame(width: stacked ? nil : zoneRangeWidth, alignment: .trailing)
                        }
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
    }

    private func zoneRange(_ bands: [HRZone], _ index: Int) -> String {
        let unit = String(localized: "bpm")
        let band = bands[index]
        if index == 0 { return "<\(Int(band.upper)) \(unit)" }
        if index == bands.count - 1 { return "\(Int(band.lower))+ \(unit)" }
        return "\(Int(band.lower))–\(Int(band.upper))"
    }

    // MARK: - Recovery

    @ViewBuilder private var recoverySection: some View {
        if let r = heartRateRecovery, r.hasMeasurement {
            VStack(alignment: .leading, spacing: 10) {
                sectionTitle("Heart Rate Recovery")
                columns {
                    recoveryCell("1 min", r.after1Minute)
                    recoveryCell("2 min", r.after2Minutes)
                    recoveryCell("5 min", r.after5Minutes)
                }
                .padding(16)
                .background(StrandPalette.summaryCard, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
            }
        }
    }

    private func recoveryCell(_ label: LocalizedStringKey, _ drop: Int?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(StrandFont.pro(15))
                .foregroundStyle(StrandPalette.textSecondary)
            (Text(drop.map { $0 >= 0 ? "−\($0)" : "+\(-$0)" } ?? "—").font(StrandFont.pro(28, weight: .semibold))
             + Text(drop == nil ? "" : String(localized: "bpm").uppercased()).font(StrandFont.pro(17, weight: .semibold)))
                .foregroundStyle(drop == nil ? StrandPalette.textTertiary : StrandPalette.healthHeart)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Map

    @ViewBuilder private var mapSection: some View {
        if route.count >= 2 {
            VStack(alignment: .leading, spacing: 10) {
                sectionTitle("Map")
                // A still preview; the tap opens the map full size.
                WorkoutRouteMap(points: route, interactive: false)
                    .frame(height: 240)
                    .allowsHitTesting(false)
                    .overlay {
                        Button { showMap = true } label: { Color.clear.contentShape(Rectangle()) }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("Map of your \(WorkoutSource.localizedSport(row.sport)) route"))
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
            }
        }
    }

    private func sectionTitle(_ title: LocalizedStringKey) -> some View {
        Text(title)
            .font(StrandFont.pro(22, weight: .bold))
            .foregroundStyle(StrandPalette.textPrimary)
            .padding(.horizontal, 4)
    }

    // MARK: - Load

    private func load() async {
        // #524: a cheap UserDefaults read keyed by the row's natural key; ≥2 points or no map at all.
        let routePoints: [RouteMath.LatLng] = {
            guard let r = RouteStore.load(startTs: row.startTs, sport: row.sport) else { return [] }
            let pts = RouteMath.decode(r.polyline)
            return pts.count >= 2 ? pts : []
        }()

        let buckets = await repo.workoutHrBuckets(from: row.startTs, to: row.endTs, source: row.source)
        let points = buckets.map { TrendPoint(date: Date(timeIntervalSince1970: TimeInterval($0.ts)), value: $0.bpm) }

        // Prefer the imported per-workout split; derive from the strap's raw HR only when there is none, so a
        // real imported split is never replaced by an on-device approximation.
        var minutes: [Double]?
        var fromImport = false
        if let pct = WorkoutZones.percents(row.zonesJSON) {
            let durMin = (row.durationS ?? Double(row.endTs - row.startTs)) / 60.0
            if durMin > 0 {
                minutes = pct.map { durMin * $0 / 100.0 }
                fromImport = true
            }
        }
        if minutes == nil {
            minutes = await repo.workoutZoneMinutes(from: row.startTs, to: row.endTs, zoneSet: profile.hrZoneSet,
                                                    source: row.source)
        }

        let hrr = await repo.workoutHeartRateRecovery(
            from: row.startTs, to: row.endTs, maxHR: Double(profile.hrMax), source: row.source)

        // Steps for an on-foot session (#398): the strap's own counter once it has offloaded the window, else
        // the phone pedometer. Both return nil for "no data", so an empty window shows nothing, not 0.
        var stepReadout: StepReadout?
        if WorkoutCatalog.isOnFoot(row.sport) {
            if let ticks = await repo.strapStepTicks(from: row.startTs, to: row.endTs) {
                // The workout's own day's divisor, so a session and its day never disagree.
                let tz = TimeZone.current.secondsFromGMT()
                let divisor = StepCalibrationStore.snapshot(
                    manual: profile.stepTicksPerStep,
                    today: AnalyticsEngine.dayString(Int(Date().timeIntervalSince1970), offsetSec: tz))
                    .factor(day: AnalyticsEngine.dayString(row.startTs, offsetSec: tz))
                let scaled = Int((Double(ticks) / max(divisor, 0.5)).rounded())
                if scaled > 0 { stepReadout = StepReadout(count: scaled, fromStrap: true) }
            }
            if stepReadout == nil,
               let ped = await WorkoutPedometer.steps(fromSec: row.startTs, toSec: row.endTs), ped > 0 {
                stepReadout = StepReadout(count: ped, fromStrap: false)
            }
        }

        route = routePoints
        hrPoints = points
        zoneMinutes = minutes
        zonesFromImport = fromImport
        heartRateRecovery = hrr
        steps = stepReadout
    }

    @MainActor private func exportRoute(_ format: RouteExporter.Format) {
        guard route.count >= 2 else { return }
        let points = route.map { RoutePoint(lat: $0.lat, lon: $0.lon) }
        // Named by the workout's start (not export time) so it's stable and matches the Android twin.
        let name = "noop-route-\(row.startTs).\(format.ext)"
        let startTs = row.startTs, endTs = row.endTs, sport = row.sport
        let distanceM = row.distanceM, energyKcal = row.energyKcal, avgHr = row.avgHr, maxHr = row.maxHr
        Task.detached(priority: .userInitiated) {
            let data = RouteExporter.render(
                format, route: points, startTs: startTs, endTs: endTs, sport: sport,
                distanceM: distanceM, energyKcal: energyKcal, avgHr: avgHr, maxHr: maxHr)
            let url = NoopScratch.file(name)
            do { try data.write(to: url) } catch { return }
            await MainActor.run { FileExport.exportFile(at: url, suggestedName: name) }
        }
    }

    // MARK: - Formatting

    /// "0:55:42" / "12:04", the stopwatch form Fitness uses for every duration on this page.
    static func clock(_ seconds: Double) -> String {
        let s = max(0, Int(seconds.rounded()))
        return s >= 3600
            ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            : String(format: "%d:%02d", s / 60, s % 60)
    }

    /// "5.74 km" → ("5.74", "km"): the figure and its unit, so the unit can be set smaller. Splits on any
    /// space, including the no-break spaces the measurement formatter emits.
    static func split(_ formatted: String) -> (String, String) {
        guard let space = formatted.firstIndex(where: \.isWhitespace) else { return (formatted, "") }
        return (String(formatted[..<space]), String(formatted[formatted.index(after: space)...]))
    }

    /// Distance in the locale's number format with two decimals ("4,88 км"), in km or miles.
    static func distance(_ meters: Double, system: UnitSystem) -> String {
        let unit: UnitLength = system == .imperial ? .miles : .kilometers
        return Measurement(value: meters, unit: UnitLength.meters).converted(to: unit)
            .formatted(.measurement(width: .abbreviated, usage: .asProvided,
                                    numberFormatStyle: .number.precision(.fractionLength(2))))
    }

    private static func grouped(_ v: Double) -> String {
        Int(v.rounded()).formatted(.number.grouping(.automatic))
    }
}
