//  FriendsHighlights.swift
//  NOOP · Friends — "Highlights", as the Fitness app's Sharing tab opens: what friends did lately, one
//  card each, side by side. Here a highlight is a finished workout.
//
//  Nothing new travels for this: the cards are read out of the days the feed already carries.

import SwiftUI
import StrandAnalytics
import StrandDesign

/// One finished workout of one friend.
struct FriendHighlight: Identifiable, Equatable {
    let nick: String
    let name: String
    /// The revision of the friend's picture; 0 is an account without one.
    var avatarRev = 0
    let workout: FriendsDay.Workout

    var endTs: Int { workout.startTs + workout.durationS }
    var id: String { "\(nick)#\(workout.startTs)" }
}

enum FriendsHighlights {
    /// How far back a workout still counts as news.
    static let windowSeconds = 48 * 3600
    /// A friend's phone may run a little ahead of this one; a workout that ended this far in the
    /// "future" is still shown.
    static let clockSlackSeconds = 120
    /// The tab shows the latest few; a friend's own page has the rest.
    static let limit = 5

    /// Friends' workouts that ended within the window, newest first. Pure: `now` is unix seconds.
    static func recent(friends: [FriendProfile], now: Int) -> [FriendHighlight] {
        var seen = Set<String>()
        var found: [FriendHighlight] = []
        for person in friends {
            for day in person.days ?? [] {
                for workout in day.summary.workouts ?? [] {
                    let highlight = FriendHighlight(nick: person.nick, name: person.name, avatarRev: person.avatarRev, workout: workout)
                    let age = now - highlight.endTs
                    guard age >= -clockSlackSeconds, age <= windowSeconds,
                          seen.insert(highlight.id).inserted else { continue }
                    found.append(highlight)
                }
            }
        }
        found.sort { a, b in a.endTs != b.endTs ? a.endTs > b.endTs : a.nick < b.nick }
        return Array(found.prefix(limit))
    }
}

// MARK: - Views

/// The highlights in a row that pages card by card, as Fitness's does: the card in view is centred and a
/// strip of its neighbours shows at either edge. At the accessibility text sizes the cards stack
/// instead, each as tall as its text needs.
struct FriendsHighlightsCarousel: View {
    let highlights: [FriendHighlight]
    let effortScale: EffortScale
    /// The page's own side margin, which the row breaks out of to run edge to edge.
    let gutter: CGFloat

    @Environment(\.dynamicTypeSize) private var dts
    /// The card's height apart from its ring: the lines over and under it, which grow with the text size.
    @ScaledMetric(relativeTo: .subheadline) private var textHeight: CGFloat = FriendHighlightCard.height
        - FriendHighlightCard.ringDiameter

    /// Fitness's own: 16 pt between cards and 25 pt of the next one in sight, so a card is the screen
    /// less 82 pt (320 pt on a 402 pt screen).
    static var spacing: CGFloat { 16 }
    static var peek: CGFloat { 25 }

    var body: some View {
        if dts.isAccessibilitySize {
            VStack(spacing: FriendsStyle.cardSpacing) {
                ForEach(highlights) { card($0) }
            }
        } else {
            GeometryReader { geo in
                let side = Self.spacing + Self.peek
                let width = max(0, geo.size.width - side * 2)
                ScrollView(.horizontal) {
                    LazyHStack(spacing: Self.spacing) {
                        ForEach(highlights) { card($0).frame(width: width) }
                    }
                    .friendsScrollTargetLayout(fallbackSide: side)
                }
                .scrollIndicators(.hidden)
                .friendsCardPaging(side: side)
            }
            .frame(height: FriendHighlightCard.ringDiameter + textHeight)
            .padding(.horizontal, -gutter)
        }
    }

    private func card(_ highlight: FriendHighlight) -> some View {
        NavigationLink(value: TabRoute.friend(highlight.nick)) {
            FriendHighlightCard(highlight: highlight, effortScale: effortScale)
        }
        .buttonStyle(.plain)
    }
}

private extension View {
    /// Marks the cards as what the row snaps to. Before the snapping API the side margin is plain padding.
    @ViewBuilder func friendsScrollTargetLayout(fallbackSide: CGFloat) -> some View {
        if #available(iOS 17.0, macOS 14.0, *) {
            self.scrollTargetLayout()
        } else {
            self.padding(.horizontal, fallbackSide)
        }
    }

    /// Snaps the row card by card, each coming to rest `side` in from the edge, which centres it.
    @ViewBuilder func friendsCardPaging(side: CGFloat) -> some View {
        if #available(iOS 17.0, macOS 14.0, *) {
            self.contentMargins(.horizontal, side, for: .scrollContent)
                .scrollTargetBehavior(.viewAligned)
        } else {
            self
        }
    }
}

/// One highlight, laid out as Fitness's: who and when across the top, a ring around the activity's
/// glyph, the figure in the ring's hue and the activity under it. The ring is the workout's Strain. Ring
/// and glyph are one flat hue, as Fitness draws a highlight in one: its lime, which is made for black,
/// so on a white card the pair sits on a black disc as the Summary's rings do.
struct FriendHighlightCard: View {
    let highlight: FriendHighlight
    let effortScale: EffortScale

    /// The card's height at the default text size.
    static var height: CGFloat { 322 }
    static var radius: CGFloat { 28 }
    static var ringDiameter: CGFloat { 168 }

    @Environment(\.dynamicTypeSize) private var dts
    @Environment(\.colorScheme) private var colorScheme
    @ScaledMetric(relativeTo: .title2) private var figureSize: CGFloat = 22

    /// Fitness's lime, the bright end of the Exercise ring.
    private static var lime: Color { StrandPalette.activityExerciseEnd }
    /// The band the picture and the two lines beside it are centred in.
    private static var headerHeight: CGFloat { 66 }

    private var workout: FriendsDay.Workout { highlight.workout }
    // The sport travels as the sender's own label; show it in this phone's language when the catalogue
    // knows it, and as sent otherwise.
    private var sport: String { WorkoutSource.localizedSport(workout.sport) }
    private var duration: String { FriendsFormat.duration(seconds: workout.durationS) }
    /// Strain where the workout has one, else how long it ran.
    private var figure: String {
        workout.strain.map { FriendsFormat.strain($0, scale: effortScale) } ?? duration
    }
    private var caption: String {
        workout.strain == nil ? sport : sport + " · " + duration
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: 12) {
                FriendAvatar(name: highlight.name, size: 32, nick: highlight.nick, rev: highlight.avatarRev)
                VStack(alignment: .leading, spacing: 0) {
                    Text("\(highlight.name) completed a workout")
                        .font(StrandFont.pro(15))
                        .foregroundStyle(StrandPalette.textPrimary)
                        .lineLimit(dts.isAccessibilitySize ? 4 : 2)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(verbatim: FriendsFormat.ago(highlight.endTs))
                        .font(StrandFont.pro(12))
                        .foregroundStyle(StrandPalette.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .frame(minHeight: Self.headerHeight)
            .padding(.vertical, dts.isAccessibilitySize ? 12 : 0)
            ZStack {
                ActivityRingsView(rings: [ActivityRing(id: "strain",
                                                       fraction: RingFraction.of(workout.strain ?? 100, max: 100),
                                                       start: Self.lime, end: Self.lime)],
                                  diameter: Self.ringDiameter, fitness: true, disc: colorScheme == .light)
                WorkoutTypeIcon(workoutType: workout.sport, size: 60, weight: .semibold, color: Self.lime)
            }
            .accessibilityHidden(true)
            Text(verbatim: figure)
                .font(.system(size: figureSize, weight: .medium, design: .rounded))
                .foregroundStyle(FriendsStyle.key)
                .lineLimit(1)
                .padding(.top, 14)
            Text(verbatim: caption)
                .font(StrandFont.pro(15))
                .foregroundStyle(StrandPalette.textPrimary)
                .lineLimit(dts.isAccessibilitySize ? 2 : 1)
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.8)
            Spacer(minLength: 16)
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .friendsCard(radius: Self.radius)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: spoken))
        .accessibilityAddTraits(.isButton)
    }

    private var spoken: String {
        var parts = [String(localized: "\(highlight.name) completed a workout"), FriendsFormat.ago(highlight.endTs),
                     sport, duration]
        if let strain = workout.strain {
            parts.append(String(localized: "Effort") + " " + FriendsFormat.strain(strain, scale: effortScale))
        }
        return parts.joined(separator: ", ")
    }
}
