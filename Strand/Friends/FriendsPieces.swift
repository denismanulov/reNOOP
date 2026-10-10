//  FriendsPieces.swift
//  NOOP · Friends — what the tab, a person's page and the sheets all draw with: the tab's margins and
//  key colour, a person's picture, a person's row (as Find My lists People), a card of such rows, the
//  hero that opens a page or a sheet, the tab's capsule button, and the formatting they share.

import SwiftUI
import StrandAnalytics
import StrandDesign

// MARK: - The tab's look

/// What the tab's own surfaces share. It is laid out as the Fitness app's Sharing tab, so the figures
/// are Fitness's: its side margin, the gap between its cards and their corners, and its key colour for
/// the links and buttons in the content (lime on black, the Exercise green on a light page), the same
/// one the Workouts tab uses.
enum FriendsStyle {
    static var gutter: CGFloat { 16 }
    static var cardSpacing: CGFloat { 12 }
    static var cardRadius: CGFloat { 18 }
    static var key: Color { StrandPalette.activityExerciseText }
    /// A label on a button filled with `key`.
    static var onKey: Color { StrandPalette.fitnessOnAccent }
}

extension View {
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
    var nick = ""
    var rev = 0

    @State private var fetched: Data?

    init(name: String, size: CGFloat, own: Bool = false, imageData: Data? = nil, nick: String = "", rev: Int = 0) {
        self.name = name; self.size = size; self.own = own; self.imageData = imageData
        self.nick = nick; self.rev = rev
    }

    init(person: FriendProfile, size: CGFloat, own: Bool = false, imageData: Data? = nil) {
        self.init(name: person.name, size: size, own: own, imageData: imageData, nick: person.nick, rev: person.avatarRev)
    }

    private var picture: Data? {
        own ? imageData : (fetched ?? FriendsAvatars.cached(nick: nick, rev: rev))
    }

    var body: some View {
        SummaryAvatar(imageData: picture, initials: FriendsFormat.initials(name), size: size)
            .accessibilityHidden(true)
            .task(id: "\(nick):\(rev):\(own)") {
                fetched = own ? nil : await FriendsAvatars.load(nick: nick, rev: rev)
            }
    }
}

struct FriendNameStack: View {
    let person: FriendProfile

    @Environment(\.dynamicTypeSize) private var dts

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(verbatim: person.name)
                .font(StrandFont.headline)
                .foregroundStyle(StrandPalette.textPrimary)
                .lineLimit(dts.isAccessibilitySize ? 2 : 1)
                .minimumScaleFactor(0.85)
            Text(verbatim: "@" + person.nick)
                .font(StrandFont.pro(15))
                .foregroundStyle(StrandPalette.textSecondary)
                .lineLimit(1)
        }
    }
}

/// One person as Find My lists People: the monogram, the name over the nickname, and what can be done
/// about them on the trailing edge. At the accessibility text sizes the actions move under the name, so
/// neither squeezes the other.
struct FriendPersonRow<Trailing: View>: View {
    let person: FriendProfile
    @ViewBuilder var trailing: Trailing

    @Environment(\.dynamicTypeSize) private var dts

    static var avatarSize: CGFloat { 44 }
    static var spacing: CGFloat { 12 }

    var body: some View {
        let layout = dts.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
            : AnyLayout(HStackLayout(spacing: Self.spacing))
        layout {
            HStack(spacing: Self.spacing) {
                FriendAvatar(person: person, size: Self.avatarSize)
                FriendNameStack(person: person)
            }
            if !dts.isAccessibilitySize { Spacer(minLength: 8) }
            trailing
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 10)
    }
}

/// People in one card, a hairline between them that starts under the names, as a grouped list draws it.
struct FriendRowsCard<Row: View>: View {
    let people: [FriendProfile]
    @ViewBuilder var row: (FriendProfile) -> Row

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(people.enumerated()), id: \.element.id) { index, person in
                if index > 0 {
                    Rectangle()
                        .fill(StrandPalette.hairline)
                        .frame(height: NoopMetrics.hairlineWidth)
                        .padding(.leading, FriendPersonRow<EmptyView>.avatarSize + FriendPersonRow<EmptyView>.spacing)
                }
                row(person)
            }
        }
        .padding(.horizontal, 16)
        .friendsCard()
    }
}

/// The top of a person's page and of the account sheets: the picture, the name and the nickname,
/// centred, as Contacts opens a card and Health its profile.
struct FriendsHero: View {
    let name: String
    let nick: String
    /// The wearer's own account: drawn with their photo.
    var own = false
    var imageData: Data?
    /// The revision of a friend's picture; 0 is an account without one.
    var avatarRev = 0

    var body: some View {
        VStack(spacing: 6) {
            FriendAvatar(name: name, size: 96, own: own, imageData: imageData, nick: nick, rev: avatarRev)
                .padding(.bottom, 4)
            if !name.isEmpty {
                Text(verbatim: name)
                    .font(StrandFont.pro(28, weight: .bold))
                    .foregroundStyle(StrandPalette.textPrimary)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)
            }
            if !nick.isEmpty {
                Text(verbatim: "@" + nick)
                    .font(StrandFont.pro(17))
                    .foregroundStyle(StrandPalette.textSecondary)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

// MARK: - Buttons

extension View {
    /// The capsule a row's action sits in on a sheet ("Accept", "Add"), filled when it is the answer the
    /// row asks for. A sheet is system chrome, so it takes the app's accent.
    @ViewBuilder
    func friendsCapsuleButton(prominent: Bool) -> some View {
        if #available(iOS 17.0, macOS 14.0, *) {
            if prominent {
                self.buttonStyle(.borderedProminent).buttonBorderShape(.capsule)
            } else {
                self.buttonStyle(.bordered).buttonBorderShape(.capsule)
            }
        } else if prominent {
            self.buttonStyle(.borderedProminent)
        } else {
            self.buttonStyle(.bordered)
        }
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

    static func hasScores(_ day: FriendsDay) -> Bool {
        day.recovery != nil || day.strain != nil || day.sleepScore != nil
    }

    static func percent(_ value: Int?) -> String {
        value.map { "\($0)%" } ?? SummaryMetricReading.noValue
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

    static func clock(_ ts: Int) -> String {
        Date(timeIntervalSince1970: TimeInterval(ts))
            .formatted(.dateTime.hour().minute().locale(AppLanguage.activeLocale))
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
