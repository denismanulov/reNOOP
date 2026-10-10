import SwiftUI
import WhoopStore

// MARK: - TabRoute
//
// Value-based routes for every push that leaves a primary tab's ROOT (#198, Path A). The iOS tab
// shell binds each tab's `NavigationStack` to a `NavigationPath`, and a path only tracks pushes
// made through it — a closure-destination `NavigationLink` bypasses the path entirely. So the
// root-level links in the tab roots must push a VALUE for "re-tap the active tab" to pop back to
// the root (#135) without the #197 rebuild. Deeper links stay closure-based on purpose: popping a
// route off the path also pops everything pushed above it, so only the first hop needs a value.
//
// Shared with macOS because the tab roots (SummaryView, SleepHealthView…) are the SAME views the sidebar
// shell hosts — every `NavigationStack` that hosts one must register
// `tabRouteDestinations()`, and must register it exactly ONCE: the same value type resolving
// against two registrations in one stack double-pushes (#38).

/// One first-hop destination reachable from a tab root. `Hashable` so it can ride a `NavigationPath`.
enum TabRoute: Hashable {
    /// One metric's detail page by `MetricCatalog` key — the tap-through the Summary's cards share.
    /// Each card opens ITS metric (2026-07-02: not the shared Health screen).
    case metric(String)
    /// One metric's detail by BOTH key and source. `steps` exists under several sources (my-whoop,
    /// apple-health, xiaomi-band); routing by bare key alone resolves whichever catalog entry is
    /// declared first, so a card's tap-through would silently depend on declaration order. This pins
    /// the exact source, so the catalog's ordering can never decide where a card taps through.
    case metricSourced(key: String, source: String)
    /// Every metric, as Health's "Show All Health Data".
    case allMetrics
    /// The Workouts tab's full history and its toolbar menu entries.
    case workoutHistory
    case workout(WorkoutRow)
    case liftLog
    case intervalTimer
    /// The Sleep page opened on the night that ended on this day ("yyyy-MM-dd") — the Summary's Sleep card
    /// and Rest ring on a past day.
    case sleepNight(String)
    /// The sleep schedule (Health's Full Schedule): the strap alarm and the bedtime reminder.
    case sleepSchedule
    /// Health's Vitals page for the night that ended on `day` ("yyyy-MM-dd") at `wakeTs`. A value route,
    /// not a closure link, so the metric pages it opens push onto the tab's path.
    case sleepVitals(day: String, wakeTs: Int)
    /// Health's "Show All Health Trends": every metric whose recent readings have clearly moved.
    case trends
    /// The long-horizon training load (CTL / ATL / form), reached from Trends.
    case trainingLoad
    /// Settings, pushed from the Summary's profile circle. Its own pages push `SettingsPage` values, so
    /// a stack that hosts this route registers `.settingsDestinations()` too.
    case settings
    /// One person's page on the Friends tab, by account id (the account's own page included).
    case friend(String)
}

extension View {
    /// Maps every `TabRoute` push to its screen. Apply once to the ROOT content of each
    /// `NavigationStack` that hosts a tab-root view (the iOS tab shell's stacks; the macOS
    /// Summary detail pane and the sidebar's other stacks).
    func tabRouteDestinations() -> some View {
        navigationDestination(for: TabRoute.self) { route in
            switch route {
            case .metric(let key):
                // Every caller passes a catalog key; an unknown (stale) key lands on All Metrics.
                if let m = MetricCatalog.all.first(where: { $0.key == key }) {
                    MetricDetailView(metric: m)
                } else {
                    AllMetricsView()
                }
            case .metricSourced(let key, let source):
                // Exact (key, source) resolution, order-independent. Fall back to the bare-key entry,
                // then All Metrics, so a stale route can never dead-end.
                if let m = MetricCatalog.metric(key: key, source: source)
                    ?? MetricCatalog.all.first(where: { $0.key == key }) {
                    MetricDetailView(metric: m)
                } else {
                    AllMetricsView()
                }
            case .allMetrics: AllMetricsView()
            case .workoutHistory: WorkoutHistoryView()
            case .workout(let row): WorkoutDetailView(row: row)
            case .liftLog: LiftLogView()
            case .intervalTimer: IntervalTimerView()
            case .sleepNight(let day): SleepHealthView(initialWakeDay: day)
            case .sleepSchedule: SleepScheduleView()
            case .sleepVitals(let day, let wakeTs):
                SleepVitalsView(day: day, date: Date(timeIntervalSince1970: TimeInterval(wakeTs)))
            case .trends: TrendsView()
            case .trainingLoad: TrainingLoadView()
            case .settings: SettingsView()
            case .friend(let id): FriendDetailView(personID: id)
            }
        }
    }
}

/// The metric keys the Summary's score rings route to, named rather than repeated as literals.
///
/// Every ring opens its metric's detail page through `TabRoute.metric`, which falls back to All
/// Metrics on a key it does not recognise — the reason this is worth pinning: a rename leaves the ring
/// tappable, animating, and landing on the wrong screen with nothing logged and nothing to notice
/// (`HeroRingDetailRouteTests` pins these against `MetricCatalog`).
///
/// Twin of Android's `HERO_CHARGE_METRIC_KEY` and friends, with ONE deliberate difference: Rest routes on
/// `sleep_performance` here and on `rest` there, because the two platforms' detail screens resolve
/// different key spaces.
///
/// SCOPE, since the same three strings appear elsewhere meaning something else. These are CATALOG keys,
/// resolved through `MetricCatalog`. What does NOT belong here is the series key space:
/// `exploreSeries(key:)` and `resolvedSeries(key:)` happen to spell two of these the same way while
/// asking a different question.
enum HeroRingMetric {
    static let charge = "recovery"
    static let effort = "strain"
    static let rest = "sleep_performance"

    /// Charge, Effort, Rest, in the order the hero row renders them.
    static let all = [charge, effort, rest]
}
