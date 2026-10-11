//  HighlightBodies.swift
//  NOOP · Highlights — what sits under a highlight's sentence, one view per kind of card the Health app
//  draws. Sizes are read off the Health app (iOS 26) in points.
//
//  Presentation only: every figure arrives resolved in the body it draws.

import SwiftUI
import StrandDesign

// MARK: - Shared pieces

/// A figure as Health sets one inside a highlight: the number large, its unit small beside it.
struct HighlightFigureText: View {
    let tokens: [MetricHealthStyle.Token]
    var numberColor: Color = StrandPalette.textPrimary
    var unitColor: Color = StrandPalette.textSecondary

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            ForEach(Array(tokens.enumerated()), id: \.offset) { _, token in
                Text(verbatim: token.text)
                    .font(token.isUnit ? StrandFont.pro(15, weight: .semibold) : StrandFont.pro(28, weight: .medium))
                    .foregroundStyle(token.isUnit ? unitColor : numberColor)
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.6)
    }
}

enum HighlightChartStyle {
    /// The grey of a bar, a rule or a usual-day line.
    static let quiet = StrandPalette.fitnessTileRule
    /// Health's teal for an average a latest reading is set against.
    static let average = StrandPalette.sleepScoreRegularity
    /// The grey of a period's bar that is not the current one.
    static let quietFill = StrandPalette.fitnessTileRule.opacity(0.4)
    static let axisLabel = StrandFont.pro(12, weight: .semibold)
    /// The margin beside the cards on the Highlights pages, as on the Summary and in Health.
    static let pageGutter: CGFloat = 20

    static var locale: Locale { AppLanguage.activeLocale }

    static var calendar: Calendar {
        var calendar = Calendar.current
        calendar.locale = locale
        return calendar
    }

    static func time(_ date: Date) -> String {
        date.formatted(.dateTime.hour().minute().locale(locale))
    }
}

/// The body under a highlight's sentence.
struct HighlightBodyView: View {
    let item: HighlightItem

    var body: some View {
        switch item.body {
        case .progress(let progress):
            HighlightProgressBody(progress: progress, tint: item.tint, figure: item.figure)
        case .sevenDay(let week):
            HighlightSevenDayBody(week: week, label: item.weekLabel, tint: item.tint, figure: item.figure)
        case .comparison(let comparison):
            HighlightComparisonBody(comparison: comparison, tint: item.tint, figure: item.figure)
        case .range(let range):
            HighlightRangeBody(range: range, tint: item.tint, marksEnds: item.id.hasSuffix("sleep"))
        case .recovery(let recovery):
            HighlightRecoveryBody(recovery: recovery, tint: item.tint)
        case .consistency(let consistency):
            HighlightConsistencyBody(consistency: consistency, tint: item.tint)
        }
    }
}

/// A highlight as a card: the category row, the sentence, the hairline, then its body.
struct HighlightItemCard: View {
    let item: HighlightItem
    /// On for a card that opens a page.
    var chevron = false

    var body: some View {
        HighlightCard(icon: item.icon, title: item.title, tint: item.tint, sentence: item.sentence,
                      chevron: chevron, spacing: 10) {
            EmptyView()
        } chart: {
            HighlightBodyView(item: item)
        }
    }
}

// MARK: - Today against a usual day

struct HighlightProgressBody: View {
    let progress: HighlightProgress
    let tint: Color
    let figure: (Double) -> [MetricHealthStyle.Token]

    @Environment(\.summaryCardFill) private var cardFill

    private let plotHeight: CGFloat = 163
    private let edge: CGFloat = 5.5

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 0) {
                legend(HighlightCopy.text("hl.label.today"), progress.todayTotal,
                       dot: tint, color: StrandPalette.text(for: tint))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.trailing, 10)
                legend(HighlightCopy.text("hl.label.average"), progress.typicalTotal,
                       dot: StrandPalette.textTertiary, color: StrandPalette.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.top, 5)
            Canvas { context, size in draw(&context, size) }
                .frame(height: plotHeight + 21)
                .padding(.top, 1)
                .accessibilityHidden(true)
            axis
                .padding(.bottom, 2)
        }
    }

    private func legend(_ title: String, _ value: Double, dot: Color, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 3) {
                Circle().fill(dot).frame(width: 9, height: 9)
                Text(verbatim: title)
                    .font(StrandFont.pro(13, weight: .semibold))
                    .foregroundStyle(color)
                    .lineLimit(1)
            }
            HighlightFigureText(tokens: figure(value), numberColor: color, unitColor: color)
        }
        .accessibilityElement(children: .combine)
    }

    private func draw(_ context: inout GraphicsContext, _ size: CGSize) {
        let left = edge, right = size.width - edge
        let top: CGFloat = 8, bottom = top + plotHeight
        let peak = max(progress.todayTotal, progress.typical.last?.total ?? 0, 1e-9)
        func point(_ p: HighlightDayPoint) -> CGPoint {
            CGPoint(x: left + (right - left) * p.fraction, y: bottom - (bottom - top) * p.total / peak)
        }
        let now = progress.nowFraction
        let typicalNow = HighlightDayPoint(fraction: now, total: progress.typicalTotal)
        let before = progress.typical.filter { $0.fraction < now } + [typicalNow]
        let after = [typicalNow] + progress.typical.filter { $0.fraction > now }
        let stroke = StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round)

        context.stroke(line(after.map(point)), with: .color(HighlightChartStyle.quiet.opacity(0.3)), style: stroke)
        context.stroke(line(before.map(point)), with: .color(HighlightChartStyle.quiet.opacity(0.85)), style: stroke)

        let todayEnd = point(progress.today.last ?? HighlightDayPoint(fraction: now, total: 0))
        var rule = Path()
        rule.move(to: CGPoint(x: todayEnd.x, y: min(todayEnd.y, point(typicalNow).y)))
        rule.addLine(to: CGPoint(x: todayEnd.x, y: bottom))
        context.stroke(rule, with: .color(HighlightChartStyle.quiet.opacity(0.7)), lineWidth: 1.3)

        context.stroke(line(progress.today.map(point)), with: .color(tint), style: stroke)
        dot(&context, point(typicalNow), fill: HighlightChartStyle.quiet)
        dot(&context, todayEnd, fill: tint)

        // The day's hours along the foot: a tick at each end and at now, a dot at every hour between.
        let tickTop = bottom + 5
        for hour in 0...24 {
            let x = left + (right - left) * CGFloat(hour) / 24
            let tall = hour == 0 || hour == 24
            tick(&context, x: x, top: tickTop, tall: tall)
        }
        tick(&context, x: todayEnd.x, top: tickTop, tall: true)
    }

    private func line(_ points: [CGPoint]) -> Path {
        var path = Path()
        guard let first = points.first else { return path }
        path.move(to: first)
        for p in points.dropFirst() { path.addLine(to: p) }
        return path
    }

    private func dot(_ context: inout GraphicsContext, _ center: CGPoint, fill: Color) {
        let ring = CGRect(x: center.x - 5.5, y: center.y - 5.5, width: 11, height: 11)
        context.fill(Path(ellipseIn: ring), with: .color(cardFill))
        context.fill(Path(ellipseIn: ring.insetBy(dx: 2, dy: 2)), with: .color(fill))
    }

    private func tick(_ context: inout GraphicsContext, x: CGFloat, top: CGFloat, tall: Bool) {
        let rect = tall ? CGRect(x: x - 1, y: top, width: 2, height: 5)
                        : CGRect(x: x - 0.65, y: top + 1.8, width: 1.3, height: 1.3)
        context.fill(Path(roundedRect: rect, cornerRadius: 1), with: .color(HighlightChartStyle.quiet))
    }

    /// Midnight, now and midnight under their ticks. The label for now gives way to neither end: an end
    /// it would run into is left out.
    private var axis: some View {
        let calendar = HighlightChartStyle.calendar
        let midnight = calendar.startOfDay(for: Date())
        let now = midnight.addingTimeInterval(progress.nowFraction * 86_400)
        return GeometryReader { geo in
            let x = edge + (geo.size.width - 2 * edge) * progress.nowFraction
            ZStack(alignment: .topLeading) {
                if x > 96 {
                    Text(verbatim: HighlightChartStyle.time(midnight))
                }
                if x < geo.size.width - 96 {
                    Text(verbatim: HighlightChartStyle.time(midnight))
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                Text(verbatim: HighlightChartStyle.time(now))
                    .fixedSize()
                    .position(x: min(max(x, 24), geo.size.width - 24), y: 8)
            }
        }
        .frame(height: 16)
        .font(HighlightChartStyle.axisLabel)
        .foregroundStyle(StrandPalette.textTertiary)
    }
}

// MARK: - Seven days

struct HighlightSevenDayBody: View {
    let week: HighlightSevenDay
    let label: String
    let tint: Color
    let figure: (Double) -> [MetricHealthStyle.Token]

    @Environment(\.colorScheme) private var scheme

    private let plotHeight: CGFloat = 155
    private let headroom: CGFloat = 20
    private let barWidth: CGFloat = 14.7
    private let lineWidth: CGFloat = 4.7

    /// The top of the scale: the tallest day, and never so near the average that its label has no room.
    private var peak: Double { max(week.values.compactMap { $0 }.max() ?? 0, week.average * 1.16, 1e-9) }

    private var lineY: CGFloat { headroom + plotHeight * CGFloat(1 - week.average / peak) }

    var body: some View {
        ZStack(alignment: .topLeading) {
            Canvas { context, size in draw(&context, size) }
                .accessibilityHidden(true)
            // Two lines at most, standing on the line: a long label grows upward, never under it.
            Text(verbatim: label)
                .font(StrandFont.pro(15, weight: .semibold).leading(.tight))
                .foregroundStyle(StrandPalette.textSecondary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
                .frame(width: 128, height: max(0, lineY - lineWidth / 2 - 4), alignment: .bottomLeading)
            HighlightFigureText(tokens: figure(week.average))
                .frame(width: 128, alignment: .leading)
                .padding(.top, lineY + lineWidth / 2 + 10)
        }
        .frame(height: headroom + plotHeight + 18, alignment: .top)
        .padding(.bottom, 4)
        .accessibilityElement(children: .combine)
    }

    private func draw(_ context: inout GraphicsContext, _ size: CGSize) {
        let bottom = headroom + plotHeight
        // Seven bars in the trailing three fifths; the leading two hold the label and the figure.
        let last = size.width - 13 - barWidth
        let pitch = min(30.9, (last - 132) / 6)
        var line = Path()
        line.addRoundedRect(in: CGRect(x: 0, y: lineY - lineWidth / 2, width: size.width, height: lineWidth),
                            cornerSize: CGSize(width: lineWidth / 2, height: lineWidth / 2))
        context.fill(line, with: .color(tint))
        let letters = HighlightChartStyle.calendar.veryShortStandaloneWeekdaySymbols
        for (index, value) in week.values.enumerated() {
            let x = last - pitch * CGFloat(week.values.count - 1 - index)
            if let value, value > 0 {
                let height = max(3, plotHeight * CGFloat(value / peak))
                let bar = Path(roundedRect: CGRect(x: x, y: bottom - height, width: barWidth, height: height),
                               cornerRadii: RectangleCornerRadii(topLeading: 3, topTrailing: 3))
                // Over the line a bar darkens it, as ink over ink; on the dark card it is drawn plain.
                var layer = context
                if scheme == .light { layer.blendMode = .multiply }
                layer.fill(bar, with: .color(HighlightChartStyle.quiet.opacity(scheme == .light ? 0.78 : 0.9)))
            }
            let weekday = HighlightChartStyle.calendar.component(.weekday, from: week.days[index])
            let text = Text(verbatim: letters[weekday - 1].uppercased())
                .font(StrandFont.pro(12, weight: .semibold))
                .foregroundColor(StrandPalette.textTertiary)
            context.draw(text, at: CGPoint(x: x + barWidth / 2, y: bottom + 9))
        }
    }
}

// MARK: - This period against the last

/// A figure over a bar that carries its name, as long as its share of the longer one: Health's way of
/// setting two figures side by side. The bar in `tint` is the one the card is about; the other is grey.
struct HighlightBarRow: View {
    let tokens: [MetricHealthStyle.Token]
    let name: String
    let share: Double
    let tint: Color?

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HighlightFigureText(tokens: tokens)
            GeometryReader { geo in
                Text(verbatim: name)
                    .font(StrandFont.pro(15, weight: .semibold))
                    .foregroundStyle(tint == nil ? StrandPalette.textPrimary : StrandPalette.fitnessOnAccent)
                    .lineLimit(1)
                    .fixedSize()
                    .padding(.horizontal, 7)
                    .frame(minWidth: max(24, geo.size.width * CGFloat(min(max(share, 0), 1))), minHeight: 24,
                           alignment: .leading)
                    .background(tint ?? HighlightChartStyle.quietFill,
                                in: RoundedRectangle(cornerRadius: 4, style: .continuous))
            }
            .frame(height: 24)
        }
        .accessibilityElement(children: .combine)
    }
}

struct HighlightComparisonBody: View {
    let comparison: HighlightComparison
    let tint: Color
    let figure: (Double) -> [MetricHealthStyle.Token]

    var body: some View {
        let peak = max(comparison.current, comparison.previous, 1e-9)
        VStack(alignment: .leading, spacing: 15) {
            HighlightBarRow(tokens: figure(comparison.current), name: name(comparison.currentStart),
                            share: comparison.current / peak, tint: tint)
            HighlightBarRow(tokens: figure(comparison.previous), name: name(comparison.previousStart),
                            share: comparison.previous / peak, tint: nil)
        }
        .padding(.top, 2)
        .padding(.bottom, 6)
    }

    /// "October" or "2026": the period by its own name.
    private func name(_ start: Date) -> String {
        let calendar = HighlightChartStyle.calendar
        switch comparison.period {
        case .year:
            return "\(calendar.component(.year, from: start))"
        case .month:
            let month = calendar.standaloneMonthSymbols[calendar.component(.month, from: start) - 1]
            return month.prefix(1).uppercased() + month.dropFirst()
        }
    }
}

// MARK: - A range over time

struct HighlightRangeBody: View {
    let range: HighlightRange
    let tint: Color
    /// A white bead at the ends of the lowest and highest slice, as Health marks a night's range.
    var marksEnds = false

    private let plotTop: CGFloat = 49
    private let plotHeight: CGFloat = 117
    private let inset: CGFloat = 26
    private let capsule: CGFloat = 9.7

    var body: some View {
        VStack(spacing: 4) {
            Canvas { context, size in draw(&context, size) }
                .frame(height: plotTop + plotHeight + 54)
                .accessibilityHidden(true)
            HStack {
                Text(verbatim: HighlightChartStyle.time(range.start))
                Spacer()
                Text(verbatim: HighlightChartStyle.time(range.end))
            }
            .font(StrandFont.pro(13, weight: .semibold))
            .foregroundStyle(StrandPalette.textTertiary)
            .padding(.horizontal, 6)
        }
        .padding(.bottom, 5)
    }

    private func draw(_ context: inout GraphicsContext, _ size: CGSize) {
        let count = range.slices.count
        let step = count > 1 ? (size.width - 2 * inset) / CGFloat(count - 1) : 0
        let span = max(range.maximum - range.minimum, 1e-9)
        func x(_ index: Int) -> CGFloat { inset + step * CGFloat(index) }
        func y(_ value: Double) -> CGFloat { plotTop + plotHeight * CGFloat((range.maximum - value) / span) }

        for (index, slice) in range.slices.enumerated() {
            guard let slice else { continue }
            // A slice with one reading is still a bead as tall as it is wide.
            let mid = (y(slice.high) + y(slice.low)) / 2
            let height = max(capsule, y(slice.low) - y(slice.high))
            let top = min(max(mid - height / 2, plotTop), plotTop + plotHeight - height)
            let rect = CGRect(x: x(index) - capsule / 2, y: top, width: capsule, height: height)
            context.fill(Path(roundedRect: rect, cornerRadius: capsule / 2), with: .color(tint))
            if marksEnds {
                if index == range.maximumIndex { bead(&context, CGPoint(x: x(index), y: top + capsule / 2)) }
                if index == range.minimumIndex { bead(&context, CGPoint(x: x(index), y: top + height - capsule / 2)) }
            }
        }

        mark(&context, HighlightCopy.text("hl.label.max"), range.maximum, x: x(range.maximumIndex),
             y: plotTop - 8, above: true, width: size.width)
        mark(&context, HighlightCopy.text("hl.label.min"), range.minimum, x: x(range.minimumIndex),
             y: plotTop + plotHeight + 8, above: false, width: size.width)

        let tickTop = plotTop + plotHeight + 47
        for index in 0..<count {
            let end = index == 0 || index == count - 1
            guard end || index % 2 == 1 else { continue }
            let rect = end ? CGRect(x: x(index) - 1, y: tickTop, width: 2, height: 6.7)
                           : CGRect(x: x(index) - 0.65, y: tickTop + 2.5, width: 1.3, height: 1.3)
            context.fill(Path(roundedRect: rect, cornerRadius: 1), with: .color(HighlightChartStyle.quiet))
        }
    }

    private func bead(_ context: inout GraphicsContext, _ center: CGPoint) {
        let rect = CGRect(x: center.x - 2.4, y: center.y - 2.4, width: 4.8, height: 4.8)
        context.stroke(Path(ellipseIn: rect), with: .color(StrandPalette.fitnessOnAccent), lineWidth: 1.6)
    }

    /// "MAX" over its figure, set above the highest slice or below the lowest, kept inside the card.
    private func mark(_ context: inout GraphicsContext, _ label: String, _ value: Double, x: CGFloat, y: CGFloat,
                      above: Bool, width: CGFloat) {
        let color = StrandPalette.text(for: tint)
        let name = context.resolve(Text(verbatim: label.uppercased(with: HighlightChartStyle.locale))
            .font(StrandFont.pro(12, weight: .semibold)).foregroundColor(color))
        let figure = context.resolve(Text(verbatim: MetricHealthStyle.number(value, decimals: 0, locale: HighlightChartStyle.locale))
            .font(StrandFont.pro(22, weight: .medium)).foregroundColor(color))
        let nameSize = name.measure(in: CGSize(width: width, height: 40))
        let figureSize = figure.measure(in: CGSize(width: width, height: 40))
        let half = max(nameSize.width, figureSize.width) / 2
        let cx = min(max(x, half), width - half)
        let top = above ? y - nameSize.height - figureSize.height + 4 : y
        context.draw(name, at: CGPoint(x: cx, y: top + nameSize.height / 2))
        context.draw(figure, at: CGPoint(x: cx, y: top + nameSize.height - 3 + figureSize.height / 2))
    }
}

// MARK: - After a workout

struct HighlightRecoveryBody: View {
    let recovery: HighlightRecovery
    let tint: Color

    private let height: CGFloat = 226.7
    private let trailingRoom: CGFloat = 74

    var body: some View {
        GeometryReader { geo in
            let pitch = (geo.size.width - 4 - trailingRoom) / 3
            ZStack(alignment: .topLeading) {
                Canvas { context, size in draw(&context, size, pitch: pitch) }
                    .accessibilityHidden(true)
                column(recovery.atEnd, caption: HighlightChartStyle.time(recovery.end), index: 0, pitch: pitch)
                column(recovery.afterOneMinute, caption: HighlightCopy.text("hl.label.plus1"), index: 1, pitch: pitch)
                column(recovery.afterTwoMinutes, caption: HighlightCopy.text("hl.label.plus2"), index: 2, pitch: pitch)
                column(nil, caption: HighlightCopy.text("hl.label.plus3"), index: 3, pitch: pitch)
            }
        }
        .frame(height: height)
        .padding(.top, 2)
        .padding(.bottom, 4)
    }

    /// A minute's figure and what minute it is, at the foot of its rule.
    private func column(_ value: Int?, caption: String, index: Int, pitch: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: -2) {
            if let value {
                Text(verbatim: "\(value)")
                    .font(StrandFont.pro(28, weight: .medium))
                    .foregroundStyle(StrandPalette.text(for: tint))
            }
            Text(verbatim: caption)
                .font(StrandFont.pro(13, weight: .medium))
                .foregroundStyle(StrandPalette.textTertiary)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.7)
        .frame(width: (index == 3 ? trailingRoom : pitch) - 10, height: height, alignment: .bottomLeading)
        .offset(x: 4 + pitch * CGFloat(index) + 7.5)
        .accessibilityElement(children: .combine)
    }

    private func draw(_ context: inout GraphicsContext, _ size: CGSize, pitch: CGFloat) {
        for index in 0...3 {
            let x = 4 + pitch * CGFloat(index)
            context.fill(Path(CGRect(x: x - 0.5, y: 0, width: 1, height: height)),
                         with: .color(HighlightChartStyle.quiet.opacity(0.6)))
        }
        let high = max(recovery.readings.map(\.bpm).max() ?? 0, Double(recovery.atEnd))
        let low = min(recovery.readings.map(\.bpm).min() ?? 0, Double(recovery.afterThreeMinutes))
        let span = max(high - low, 1e-9)
        func y(_ bpm: Double) -> CGFloat { 14 + 159 * CGFloat((high - bpm) / span) }
        for reading in recovery.readings {
            let center = CGPoint(x: 4 + pitch * CGFloat(reading.seconds / 60), y: y(reading.bpm))
            context.fill(Path(ellipseIn: CGRect(x: center.x - 3.35, y: center.y - 3.35, width: 6.7, height: 6.7)),
                         with: .color(tint))
        }
        let unit = String(localized: "bpm").uppercased(with: HighlightChartStyle.locale)
        for (bpm, at) in [(high, CGFloat(7)), (low, y(low))] {
            let text = Text(verbatim: "\(Int(bpm.rounded())) \(unit)")
                .font(StrandFont.pro(11, weight: .medium))
                .foregroundColor(StrandPalette.textTertiary.opacity(0.7))
            context.draw(text, at: CGPoint(x: 4 + pitch * 3 + 6, y: at), anchor: .leading)
        }
    }
}

// MARK: - Days that had one

struct HighlightConsistencyBody: View {
    let consistency: HighlightConsistency
    let tint: Color

    private let dot: CGFloat = 31.3

    private var weeks: [[HighlightConsistency.Day]] {
        stride(from: 0, to: consistency.days.count, by: 7).map { Array(consistency.days[$0..<min($0 + 7, consistency.days.count)]) }
    }

    var body: some View {
        let calendar = HighlightChartStyle.calendar
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                ForEach(Array((weeks.first ?? []).enumerated()), id: \.offset) { offset, day in
                    if offset > 0 { Spacer(minLength: 0) }
                    Text(verbatim: weekday(day.date, calendar: calendar))
                        .font(StrandFont.pro(15))
                        .foregroundStyle(StrandPalette.textTertiary)
                        .fixedSize()
                        .frame(width: dot)
                }
            }
            .frame(height: 30)
            .accessibilityHidden(true)
            Rectangle().fill(StrandPalette.hairline).frame(height: NoopMetrics.hairlineWidth)
            VStack(spacing: 0) {
                ForEach(Array(weeks.enumerated()), id: \.offset) { index, week in
                    HStack(spacing: 0) {
                        ForEach(Array(week.enumerated()), id: \.offset) { offset, day in
                            if offset > 0 { Spacer(minLength: 0) }
                            cell(day, names: index == 0 && offset == 0, calendar: calendar)
                        }
                    }
                }
            }
            .padding(.top, 9)
        }
        .padding(.bottom, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: "\(consistency.count)"))
    }

    /// A day's dot, with its month named over it when the month starts there (or the grid does).
    private func cell(_ day: HighlightConsistency.Day, names: Bool, calendar: Calendar) -> some View {
        let number = calendar.component(.day, from: day.date)
        let isToday = day.date == consistency.today
        return VStack(spacing: 3) {
            Text(verbatim: names || number == 1 ? month(day.date, calendar: calendar) : " ")
                .font(StrandFont.pro(11, weight: .medium))
                .foregroundStyle(StrandPalette.textTertiary)
                .fixedSize()
                .frame(width: dot, height: 21, alignment: .bottom)
            Text(verbatim: "\(number)")
                .font(StrandFont.pro(15, weight: .semibold))
                .foregroundStyle(day.mark == .ahead ? StrandPalette.textTertiary : StrandPalette.fitnessOnAccent)
                .frame(width: dot, height: dot)
                .background {
                    switch day.mark {
                    case .done: Circle().fill(tint)
                    case .missed: Circle().fill(HighlightChartStyle.quiet)
                    case .ahead: EmptyView()
                    }
                }
                .overlay {
                    if isToday {
                        Circle().strokeBorder(StrandPalette.text(for: tint), lineWidth: 2.5)
                    }
                }
        }
    }

    private func weekday(_ date: Date, calendar: Calendar) -> String {
        let symbol = calendar.shortStandaloneWeekdaySymbols[calendar.component(.weekday, from: date) - 1]
        return symbol.prefix(1).uppercased() + symbol.dropFirst()
    }

    private func month(_ date: Date, calendar: Calendar) -> String {
        calendar.shortStandaloneMonthSymbols[calendar.component(.month, from: date) - 1]
            .uppercased(with: HighlightChartStyle.locale)
    }
}

// MARK: - The latest against the usual

/// Health's "latest against average" highlight: the average over the latest, one under the other, then
/// the readings behind them as grey bars with the latest in the card's hue and the average drawn across
/// in Health's teal, the first and last day under the ends.
struct HighlightLatestFigures: View {
    let averageTitle: String
    let average: [MetricHealthStyle.Token]
    let latestTitle: String
    let latest: [MetricHealthStyle.Token]
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            figure(averageTitle, average, tint: HighlightChartStyle.average)
            figure(latestTitle, latest, tint: tint)
        }
    }

    private func figure(_ title: String, _ tokens: [MetricHealthStyle.Token], tint: Color) -> some View {
        let color = StrandPalette.text(for: tint)
        return VStack(alignment: .leading, spacing: -1) {
            Text(verbatim: title)
                .font(StrandFont.pro(13, weight: .semibold))
                .foregroundStyle(StrandPalette.textSecondary)
            HighlightFigureText(tokens: tokens, numberColor: color, unitColor: color)
        }
        .accessibilityElement(children: .combine)
    }
}

struct HighlightLatestBars: View {
    /// Oldest first; the last is the latest.
    let values: [Double]
    let average: Double
    let first: String
    let last: String
    let tint: Color
    /// How far below the lowest reading the scale starts, at the least; an hour for a clock time.
    var minimumFloor: Double = 0

    @Environment(\.colorScheme) private var scheme

    private let barWidth: CGFloat = 11.7
    private let lineWidth: CGFloat = 4.7
    /// The line runs this far past the first and last bar.
    private let overhang: CGFloat = 2.3

    var body: some View {
        Canvas { context, size in draw(&context, size) }
            .frame(height: 124)
            .padding(.horizontal, -overhang)
            .padding(.top, 6)
            .accessibilityHidden(true)
        HStack {
            Text(verbatim: first)
            Spacer()
            Text(verbatim: last)
        }
        .font(StrandFont.pro(13))
        .foregroundStyle(StrandPalette.textTertiary)
        .padding(.bottom, 2)
    }

    /// The readings on their own scale, which starts below the lowest so a small move shows.
    private func draw(_ context: inout GraphicsContext, _ size: CGSize) {
        let low = min(values.min() ?? 0, average), high = max(values.max() ?? 1, average)
        let floor = low - max(minimumFloor, (high - low) * 1.6, abs(high) * 0.02)
        func height(_ value: Double) -> CGFloat { size.height * CGFloat((value - floor) / max(1e-9, high - floor)) }
        let lineY = size.height - height(average)
        context.fill(Path(roundedRect: CGRect(x: 0, y: lineY - lineWidth / 2, width: size.width, height: lineWidth),
                          cornerRadius: lineWidth / 2), with: .color(HighlightChartStyle.average))
        // A fortnight's bars run edge to edge; fewer keep a fortnight's spacing from the trailing edge.
        let span = size.width - 2 * overhang - barWidth
        let pitch = span / 13
        for (index, value) in values.enumerated() {
            let x = overhang + span - pitch * CGFloat(values.count - 1 - index)
            let h = max(4, height(value))
            let bar = Path(roundedRect: CGRect(x: x, y: size.height - h, width: barWidth, height: h),
                           cornerRadii: RectangleCornerRadii(topLeading: 3, topTrailing: 3))
            if index == values.count - 1 {
                context.fill(bar, with: .color(tint))
            } else {
                var layer = context
                if scheme == .light { layer.blendMode = .multiply }
                layer.fill(bar, with: .color(HighlightChartStyle.quiet.opacity(scheme == .light ? 0.78 : 0.9)))
            }
        }
    }
}
