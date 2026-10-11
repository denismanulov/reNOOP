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
    let personID: String
    let name: String
    /// The revision of the friend's picture; 0 is an account without one.
    var avatarRev = 0
    let workout: FriendsDay.Workout

    var endTs: Int { workout.startTs + workout.durationS }
    var id: String { "\(personID)#\(workout.startTs)" }
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
                    let highlight = FriendHighlight(personID: person.id, name: person.name, avatarRev: person.avatarRev, workout: workout)
                    let age = now - highlight.endTs
                    guard age >= -clockSlackSeconds, age <= windowSeconds,
                          seen.insert(highlight.id).inserted else { continue }
                    found.append(highlight)
                }
            }
        }
        found.sort { a, b in a.endTs != b.endTs ? a.endTs > b.endTs : a.personID < b.personID }
        return Array(found.prefix(limit))
    }
}

// MARK: - Views

/// The highlights in a row that pages card by card, as Fitness's does: the card in view is centred and a
/// strip of its neighbours shows at either edge. At the accessibility text sizes the cards stack
/// instead, each as tall as its text needs.
struct FriendsHighlightsCarousel: View {
    let highlights: [FriendHighlight]
    /// The page's own side margin, which the row breaks out of to run edge to edge.
    let gutter: CGFloat

    @Environment(\.dynamicTypeSize) private var dts

    /// Fitness's own: 10 pt between cards and 30⅓ pt of the next one in sight, which leaves a card the
    /// screen less 80⅔ pt (321⅓ pt on a 402 pt screen). A card is as tall as it is wide.
    static var spacing: CGFloat { 10 }
    static var peek: CGFloat { 30.33 }

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
                        ForEach(highlights) { card($0).frame(width: width, height: width) }
                    }
                    .friendsScrollTargetLayout(fallbackSide: side)
                }
                .scrollIndicators(.hidden)
                .friendsCardPaging(side: side)
            }
            .aspectRatio(FriendHighlightCard.screenToCard, contentMode: .fit)
            .padding(.horizontal, -gutter)
        }
    }

    private func card(_ highlight: FriendHighlight) -> some View {
        NavigationLink(value: TabRoute.friend(highlight.personID)) {
            FriendHighlightCard(highlight: highlight)
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

/// One highlight, laid out as Fitness's: who and when across the top, a closed ring around the
/// activity's glyph, how long the workout ran in the ring's hue and the activity under it. Fitness draws
/// a workout measured by its time in yellow, and every workout here carries its length, so the ring and
/// the figure are that yellow. The hue is made for black, so on a white card the ring sits on a black
/// disc as the Summary's rings do.
struct FriendHighlightCard: View {
    let highlight: FriendHighlight

    /// The screen's width over the card's, which is also the row's width over its height.
    static var screenToCard: CGFloat { 402 / 321.33 }
    static var radius: CGFloat { 28 }

    @Environment(\.dynamicTypeSize) private var dts
    @Environment(\.colorScheme) private var colorScheme
    @ScaledMetric(relativeTo: .title2) private var figureSize: CGFloat = 24

    private var workout: FriendsDay.Workout { highlight.workout }
    // The sport travels as the sender's own label; show it in this phone's language when the catalogue
    // knows it, and as sent otherwise.
    private var sport: String { WorkoutSource.localizedSport(workout.sport) }
    private var length: String { FriendsFormat.clockDuration(seconds: workout.durationS) }
    /// Fitness heads a highlight with the person's short name.
    private var who: String { FriendsFormat.shortName(highlight.name) }

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 10.67) {
                FriendAvatar(name: highlight.name, size: 32, id: highlight.personID, rev: highlight.avatarRev)
                    .padding(.top, 3.17)
                VStack(alignment: .leading, spacing: 2.17) {
                    Text("\(who) completed a workout")
                        .font(StrandFont.pro(17))
                        .foregroundStyle(StrandPalette.textPrimary)
                        .lineLimit(dts.isAccessibilitySize ? 4 : 2)
                        .multilineTextAlignment(.leading)
                    Text(verbatim: FriendsFormat.ago(highlight.endTs))
                        .font(StrandFont.pro(11))
                        .foregroundStyle(StrandPalette.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.top, 14.17)
            // The ring keeps its place whatever the title's length: Fitness sets it 68⅚ pt down.
            .frame(height: dts.isAccessibilitySize ? nil : 68.83, alignment: .top)
            .padding(.bottom, dts.isAccessibilitySize ? 12 : 0)
            ZStack {
                ActivityRingsView(rings: [ActivityRing(id: "time", fraction: 1,
                                                       start: StrandPalette.activityTimeStart,
                                                       end: StrandPalette.activityTimeEnd)],
                                  diameter: FriendsStyle.highlightRing.diameter, fitness: true,
                                  disc: colorScheme == .light, stroke: FriendsStyle.highlightRing.stroke)
                WorkoutTypeIcon(workoutType: workout.sport, size: 64, weight: .semibold,
                                color: StrandPalette.activityTimeEnd)
            }
            .accessibilityHidden(true)
            FriendsFigure.text(length, size: figureSize)
                .foregroundStyle(StrandPalette.activityTimeText)
                .lineLimit(1)
                .padding(.top, 17.3)
            Text(verbatim: sport)
                .font(StrandFont.pro(17))
                .foregroundStyle(StrandPalette.textPrimary)
                .lineLimit(dts.isAccessibilitySize ? 2 : 1)
                .multilineTextAlignment(.center)
                .minimumScaleFactor(0.8)
                .padding(.top, -1.3)
            Spacer(minLength: 16)
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .friendsFixedMeasure(dts)
        .friendsCard(radius: Self.radius)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: spoken))
        .accessibilityAddTraits(.isButton)
    }

    private var spoken: String {
        [String(localized: "\(highlight.name) completed a workout"), FriendsFormat.ago(highlight.endTs),
         sport, FriendsFormat.duration(seconds: workout.durationS)].joined(separator: ", ")
    }
}
