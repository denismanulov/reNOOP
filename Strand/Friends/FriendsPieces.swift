//  FriendsPieces.swift
//  NOOP · Friends — what the tab, a person's page and the sheets all draw with: Fitness's measures and
//  the tab's key colour, a figure with its unit, a person's picture, the hero that opens a page, the
//  tab's capsule button, and the formatting they share.

import SwiftUI
import StrandAnalytics
import StrandDesign

// MARK: - The tab's look

/// What the tab's own surfaces share. It is laid out as the Fitness app's Sharing tab, so the figures
/// are Fitness's, measured from the app itself (iOS 26.5): its side margin, the gap between its cards
/// and their corners, its type sizes and ring sizes, and its key colour for the links and buttons in the
/// content (lime on black, the Exercise green on a light page), the same one the Workouts tab uses.
enum FriendsStyle {
    static var gutter: CGFloat { 16 }
    static var cardSpacing: CGFloat { 10 }
    static var cardRadius: CGFloat { 18 }
    static var key: Color { StrandPalette.activityExerciseText }
    /// A label on a button filled with `key`.
    static var onKey: Color { StrandPalette.fitnessOnAccent }

    /// A figure on a card or a page ("108%"), and how much closer than its own leading the line under
    /// it sits.
    static var figureSize: CGFloat { 30 }
    static var figureLineGap: CGFloat { -5 }
    /// A figure beside its label on a person's page ("630/400KCAL").
    static var rowFigureSize: CGFloat { 22 }

    /// From a section's title down to its first card.
    static var titleToCard: CGFloat { 10.2 }
    /// From the bar, which carries the tab's large title, down to the first section's title.
    static var barToTitle: CGFloat { 9.8 }
    /// The row of highlights keeps a little more air under it than a card does.
    static var carouselToTitle: CGFloat { 23.9 }
    /// Under the list's title: down to the day line, and from the day line to the first card.
    static var dayLineGap: CGFloat { 9.33 }
    static var dayLineToCard: CGFloat { 10.2 }
    /// How far in from the cards' edge the sort menu ends.
    static var sortInset: CGFloat { 12 }

    /// Fitness draws each size of its rings to its own measure.
    struct Rings {
        let diameter: CGFloat
        let stroke: CGFloat
        let gap: CGFloat
    }
    /// On a person's card in the list.
    static var cardRings: Rings { Rings(diameter: 69.33, stroke: 8.33, gap: 1.67) }
    /// On a person's page, beside the day's three figures.
    static var pageRings: Rings { Rings(diameter: 137.33, stroke: 15.33, gap: 1.67) }
    /// One day of the last seven on a person's page.
    static var dayRings: Rings { Rings(diameter: 40.67, stroke: 4, gap: 1.5) }
    /// The one ring of a highlight, and the one round a workout's glyph in a list.
    static var highlightRing: Rings { Rings(diameter: 163.67, stroke: 20, gap: 0) }
    static var workoutRing: Rings { Rings(diameter: 47.33, stroke: 6, gap: 0) }
}

/// A rule one device pixel thick, as Fitness draws the ones on a friend's page.
struct FriendsRule: View {
    var color = StrandPalette.fitnessFigureRule

    @Environment(\.displayScale) private var displayScale

    var body: some View {
        Rectangle().fill(color).frame(height: 1 / max(displayScale, 1))
    }
}

/// A section's title on the tab ("Highlights"), as Fitness sets its own: bold, at the cards' edge.
struct FriendsSectionTitle: View {
    let title: LocalizedStringKey

    var body: some View {
        Text(title)
            .font(StrandFont.pro(22, weight: .bold))
            .foregroundStyle(StrandPalette.textPrimary)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A figure as Fitness sets one: the number in SF Compact Rounded and its unit straight after it in
/// small capitals ("923/850KCAL").
enum FriendsFigure {
    static func text(_ number: String, unit: String? = nil, size: CGFloat,
                     weight: Font.Weight = .medium) -> Text {
        let figure = Text(verbatim: number).font(StrandFont.compactRounded(size, weight: weight))
        guard let unit, !unit.isEmpty else { return figure }
        return figure + unitText(unit, size: size, weight: weight)
    }

    /// "7H 42MIN": hours are left out under an hour.
    static func duration(minutes: Int, size: CGFloat, weight: Font.Weight = .medium) -> Text {
        let hours = minutes / 60
        let rest = text("\(minutes % 60)", unit: String(localized: "sleep.unit.min", defaultValue: "min"),
                        size: size, weight: weight)
        guard hours > 0 else { return rest }
        return text("\(hours)", unit: String(localized: "sleep.unit.hr", defaultValue: "hr"), size: size, weight: weight)
            + text(" ", size: size, weight: weight) + rest
    }

    /// The unit in the face's own small capitals, which it carries for Latin and Cyrillic alike.
    private static func unitText(_ unit: String, size: CGFloat, weight: Font.Weight) -> Text {
        Text(verbatim: unit.lowercased(with: AppLanguage.activeLocale))
            .font(StrandFont.compactRounded(size, weight: weight).lowercaseSmallCaps())
    }
}

extension View {
    /// For a view drawn to Fitness's fixed measure: its text stops growing one step above the default
    /// size, where it would leave the measure. The accessibility sizes are not held back; a view that
    /// takes this has a layout of its own for them.
    @ViewBuilder func friendsFixedMeasure(_ size: DynamicTypeSize) -> some View {
        if size.isAccessibilitySize { self } else { self.dynamicTypeSize(...DynamicTypeSize.xLarge) }
    }

    /// The plate behind one of the tab's cards.
    func friendsCard(radius: CGFloat = FriendsStyle.cardRadius) -> some View {
        self
            .background(StrandPalette.summaryCard, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

// MARK: - A person

/// A person's picture. A friend is the picture they chose to show, fetched once per revision, and
/// until it arrives (or when they have none) their initials on Contacts' grey, as Fitness and Messages
/// draw a contact with no photo. The wearer is drawn with their own photo straight from this phone.
struct FriendAvatar: View {
    let name: String
    let size: CGFloat
    var own = false
    /// The wearer's own photo; read only when `own`.
    var imageData: Data?
    /// Whose picture to fetch and at which revision; revision 0 is an account without one.
    var id = ""
    var rev = 0

    @State private var fetched: Data?

    init(name: String, size: CGFloat, own: Bool = false, imageData: Data? = nil, id: String = "", rev: Int = 0) {
        self.name = name; self.size = size; self.own = own; self.imageData = imageData
        self.id = id; self.rev = rev
    }

    init(person: FriendProfile, size: CGFloat, own: Bool = false, imageData: Data? = nil) {
        self.init(name: person.name, size: size, own: own, imageData: imageData, id: person.id, rev: person.avatarRev)
    }

    private var picture: Data? {
        own ? imageData : (fetched ?? FriendsAvatars.cached(id: id, rev: rev))
    }

    var body: some View {
        SummaryAvatar(imageData: picture, initials: FriendsFormat.initials(name), size: size)
            .accessibilityHidden(true)
            .task(id: "\(id):\(rev):\(own)") {
                fetched = own ? nil : await FriendsAvatars.load(id: id, rev: rev)
            }
    }
}

/// A section's title in the friends sheet, as Fitness heads "Sharing With": semibold, in grey, in the
/// case it is written in.
struct FriendsSheetHeader: View {
    let title: LocalizedStringKey

    var body: some View {
        Text(title)
            .font(StrandFont.pro(17, weight: .semibold))
            .foregroundStyle(StrandPalette.textSecondary)
            .textCase(nil)
            // Fitness sets the title closer to its card than a list sets a header.
            .padding(.bottom, -4)
    }
}

/// The top of a person's page and of the sharing page: the picture and the name, centred, as Contacts
/// opens a card and Health its profile. `large` is Fitness's own measure for a friend's page.
struct FriendsHero: View {
    let name: String
    /// Whose picture to fetch; empty for the wearer, who is drawn from this phone.
    var id = ""
    /// The wearer's own account: drawn with their photo.
    var own = false
    var imageData: Data?
    /// The revision of a friend's picture; 0 is an account without one.
    var avatarRev = 0
    var large = false

    var body: some View {
        VStack(spacing: large ? 7.9 : 6) {
            FriendAvatar(name: name, size: large ? 120 : 96, own: own, imageData: imageData, id: id, rev: avatarRev)
                .padding(.bottom, large ? 0 : 4)
            if !name.isEmpty {
                Text(verbatim: name)
                    .font(StrandFont.pro(large ? 34 : 28, weight: .bold))
                    .foregroundStyle(StrandPalette.textPrimary)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

// MARK: - Buttons

extension View {
    /// The capsule a row's action sits in on a sheet ("Confirm", "Add"), filled when it is the answer the
    /// row asks for. The sheets are the tab's own, so their buttons take its key colour as Fitness's do.
    func friendsCapsuleButton(prominent: Bool) -> some View {
        buttonStyle(FriendsKeyButtonStyle(prominent: prominent))
    }
}

/// A capsule button in the tab's own content, in the tab's key colour as Fitness fills its buttons:
/// filled when it is the answer asked for, tinted otherwise. `large` is the one button a page ends on.
struct FriendsKeyButtonStyle: ButtonStyle {
    var prominent = true
    var large = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(StrandFont.pro(large ? 17 : 15, weight: .semibold))
            .lineLimit(1)
            .foregroundStyle(prominent ? FriendsStyle.onKey : FriendsStyle.key)
            .padding(.horizontal, large ? 20 : 14)
            // The capsule grows with the text size; the heights are those at the default one.
            .padding(.vertical, 6)
            .frame(maxWidth: large ? .infinity : nil, minHeight: large ? 50 : 32)
            // The tint is light enough that the label keeps 4.5:1 on it in either appearance.
            .background(prominent ? FriendsStyle.key : FriendsStyle.key.opacity(0.1), in: Capsule())
            // A 44 pt target around the small capsule.
            .contentShape(Capsule().inset(by: large ? 0 : -6))
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

// MARK: - Formatting

enum FriendsFormat {
    static func initials(_ name: String) -> String {
        let words = name.split(whereSeparator: { $0 == " " || $0 == "_" }).prefix(2)
        return words.compactMap { $0.first }.map { String($0).uppercased() }.joined()
    }

    /// The name a person goes by in a sentence about them: their given name where the name parses as a
    /// person's, the whole name otherwise.
    static func shortName(_ name: String) -> String {
        let given = PersonNameComponentsFormatter().personNameComponents(from: name)?.givenName ?? ""
        if !given.isEmpty { return given }
        // A name the formatter does not take apart goes by its first word.
        return name.split(separator: " ").first.map(String.init) ?? name
    }

    static func percent(_ value: Int?) -> String {
        value.map { "\($0)%" } ?? SummaryMetricReading.noValue
    }

    /// A whole amount ("540"), grouped as the active language groups thousands.
    static func whole(_ value: Double) -> String {
        value.rounded().formatted(.number.precision(.fractionLength(0)).locale(AppLanguage.activeLocale))
    }

    /// A workout's length as Fitness writes it on a card: hours and minutes, "0:30".
    static func clockDuration(seconds: Int) -> String {
        let minutes = Int((Double(max(0, seconds)) / 60).rounded())
        return "\(minutes / 60):" + String(format: "%02d", minutes % 60)
    }

    /// Strain travels on the stored 0-100 axis; each phone draws it on its wearer's own scale.
    static func strain(_ value: Double?, scale: EffortScale) -> String {
        guard let value else { return SummaryMetricReading.noValue }
        return SummaryMetricReading.decimal(UnitFormatter.effortValue(value, scale: scale))
    }

    static func rings(_ day: FriendsDay?, glyphs: Bool) -> [ActivityRing] {
        [ActivityRing(id: "recovery", fraction: RingFraction.of(day?.recovery.map(Double.init), max: 100),
                      start: StrandPalette.activityMoveStart, end: StrandPalette.activityMoveEnd,
                      glyph: glyphs ? .move : nil),
         ActivityRing(id: "strain", fraction: RingFraction.of(day?.strain, max: 100),
                      start: StrandPalette.activityExerciseStart, end: StrandPalette.activityExerciseEnd,
                      glyph: glyphs ? .exercise : nil),
         ActivityRing(id: "sleep", fraction: RingFraction.of(day?.sleepScore.map(Double.init), max: 100),
                      start: StrandPalette.activityStandStart, end: StrandPalette.activityStandEnd,
                      glyph: glyphs ? .stand : nil)]
    }

    private static let keyFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    static func date(_ dayKey: String) -> Date? { keyFormatter.date(from: dayKey) }

    /// The key of the calendar day `date` falls on, as `date(_:)` reads it back.
    static func key(_ date: Date) -> String { keyFormatter.string(from: date) }

    /// The calendar day keys are counted in: the one the key formatter itself uses.
    static var calendar: Calendar { keyFormatter.calendar }

    /// The one letter a weekday goes by over a column ("M"), in the active language.
    static func weekdayLetter(_ dayKey: String, locale: Locale = AppLanguage.activeLocale) -> String {
        guard let date = date(dayKey) else { return "" }
        var named = Calendar(identifier: .gregorian)
        named.locale = locale
        let symbols = named.veryShortStandaloneWeekdaySymbols
        let index = calendar.component(.weekday, from: date) - 1
        return symbols.indices.contains(index) ? symbols[index].uppercased(with: locale) : ""
    }

    /// "Today", "Yesterday", or the weekday by its name for a day of the past week, as Fitness dates a
    /// friend's workout; the date for anything older.
    static func weekdayLabel(_ dayKey: String, now: Date = Date(), locale: Locale = AppLanguage.activeLocale) -> String {
        guard let date = date(dayKey) else { return dayKey }
        let today = calendar.startOfDay(for: now)
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: today).day ?? 0
        guard (2...6).contains(days) else { return dayLabel(dayKey, now: now) }
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "cccc"
        let name = formatter.string(from: date)
        return name.prefix(1).uppercased(with: locale) + name.dropFirst()
    }

    /// The wearer's own day as the days are keyed on the server: the logical day, which rolls at 04:00
    /// as the Summary's does, so a late night still counts toward the day it belongs to.
    @MainActor static func todayKey(_ now: Date = Date()) -> String { Repository.logicalDayKey(now) }

    /// "Today", "Yesterday" or the date, for a day key.
    static func dayLabel(_ dayKey: String, now: Date = Date()) -> String {
        guard let date = date(dayKey) else { return dayKey }
        let cal = Calendar.current
        if cal.isDate(date, inSameDayAs: now) { return String(localized: "Today") }
        if let y = cal.date(byAdding: .day, value: -1, to: now), cal.isDate(date, inSameDayAs: y) {
            return String(localized: "Yesterday")
        }
        return date.formatted(.dateTime.day().month(.wide).locale(AppLanguage.activeLocale))
    }

    /// "2 hr. ago", in the active language. A moment still ahead of this phone's clock reads as now.
    static func ago(_ ts: Int, now: Date = Date()) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = AppLanguage.activeLocale
        formatter.unitsStyle = .short
        let then = min(Date(timeIntervalSince1970: TimeInterval(ts)), now.addingTimeInterval(-1))
        return formatter.localizedString(for: then, relativeTo: now)
    }

    /// "69 min", in the active language: minutes are not folded into hours, as Fitness counts Exercise.
    static func minutes(_ minutes: Int) -> String {
        Measurement(value: Double(minutes), unit: UnitDuration.minutes)
            .formatted(.measurement(width: .abbreviated, usage: .asProvided,
                                    numberFormatStyle: .number.precision(.fractionLength(0)))
                .locale(AppLanguage.activeLocale))
    }

    /// "1 h 12 min" / "45 min", in the active language.
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
