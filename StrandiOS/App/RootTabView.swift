#if os(iOS)
import SwiftUI
import StrandDesign

/// iOS navigation shell. macOS uses a `NavigationSplitView` sidebar (`RootView`); on iPhone the
/// natural analogue is a `TabView` with the most-used screens as tabs and everything else under a
/// "More" list. Every screen is the same `StrandDesign`-built view the macOS app uses.
struct RootTabView: View {
    /// #1841: shared with Android by NAME and meaning, not by storage — the two platforms keep their own
    /// stores, exactly as the Clock format setting does.
    ///
    /// Default FALSE here while Android defaults true, and the divergence is deliberate. Apple's forums
    /// report `.tabBarMinimizeBehavior(.onScrollDown)` failing to trigger in tabs built on
    /// `NavigationStack(path:)` — which is every primary tab in this file, bound deliberately so a tab
    /// root can pop and re-scroll. So this may well be inert on our structure, and defaulting ON would
    /// advertise a behaviour that never happens. Off until someone confirms it on an iOS 26 device.
    /// The Coach master switch, under the same `noop.` key Android writes. Default ON, so every install
    /// that shipped with the tab is unchanged.
    ///
    /// Not tab chrome: with this off the AI is off. The tab goes, the Today launcher card goes, and the
    /// daily brief is cancelled, because the brief calls a provider from the BACKGROUND with no UI
    /// attached and would otherwise keep posting AI notifications for a feature the wearer switched off.
    @AppStorage("noop.coachEnabled") private var coachEnabled = true

    /// The live gym session, owned at the app root — see `LiftSessionController`.
    @EnvironmentObject private var liftSession: LiftSessionController
    /// Whatever is running (gym session, intervals, workout), for the mini-player under every tab.
    @EnvironmentObject private var nowRunning: NowRunning
    /// External entry points must wait until the mandatory first-run gates have completed. The root owns
    /// that state; keeping it explicit here keeps an external action from navigating underneath a gate.
    let homeScreenQuickActionsEnabled: Bool

    @EnvironmentObject private var repo: Repository
    /// Cross-screen navigation requests (e.g. Live → "Manage devices"). A request navigates the tabs the
    /// way a tap would: it selects the tab the screen lives in and pushes it there, never a sheet.
    @EnvironmentObject private var router: NavRouter
    /// The scene-local receiver for actions chosen from NOOP's Home Screen icon menu.
    @EnvironmentObject private var homeScreenQuickActions: HomeScreenQuickActionSceneDelegate

    /// Selected tab, bound so a re-tap can pop / scroll its root. Defaults to Today.
    @State private var selectedTab: Int = 0
    /// One `NavigationPath` per tab, indexed by tab tag. Re-tapping the already-active tab pops
    /// that tab's stack to its root (#135) by clearing its path — an animated pop that leaves the
    /// root view alive, so an at-root re-tap keeps scroll position and never re-runs `.task`
    /// (#198; the #197 resetID/`.id()` rebuild reset both). Requires the tab roots' first-hop
    /// links to push `TabRoute`/`MoreDestination` VALUES — closure-destination links bypass the path.
    @State private var tabPaths: [NavigationPath] = Array(repeating: NavigationPath(), count: 5)
    /// One scroll-to-top token per tab. Bumped when the user re-taps the active tab while it's ALREADY
    /// at its root — the other half of the iOS convention #197/#198 left unserved (an at-root re-tap was
    /// a no-op). Threaded into each tab's root via `\.scrollToTopSignal`; SummaryView / SleepHealthView
    /// scroll to their top anchor when their tab's token changes.
    @State private var scrollTop: [Int] = Array(repeating: 0, count: 5)

    /// The Today tab root: the Apple-Health-style Summary.
    private var todayTabRoot: some View { SummaryView() }

    /// Native tab selection binding. SwiftUI sends taps on the already-selected item through the
    /// setter, which lets the system tab bar retain the app's refresh / pop-to-root / scroll-to-top
    /// convention without placing a custom hit-testing layer over the platform bar.
    private var nativeTabSelection: Binding<Int> {
        Binding(
            get: { selectedTab },
            set: { tag in
                if tag == selectedTab {
                    reselectTab(tag)
                } else {
                    selectedTab = tag
                }
            }
        )
    }

    private func reselectTab(_ tag: Int) {
        if !tabPaths[tag].isEmpty {
            tabPaths[tag] = NavigationPath()
        } else {
            scrollTop[tag] += 1
        }
    }

    var body: some View {
        // Health-style tab bar (iOS 26): a compact capsule of the three main tabs, with Browse set apart
        // as the search-role glass circle. The bar itself stays fully native — iOS 26 supplies Liquid
        // Glass and its scroll interaction; older releases get the matching system material. Tags are
        // stable indices into `tabPaths` / `scrollTop` (3 was the retired Coach tab).
        tabView
            // The running workout / gym session / intervals as the Music mini-player, on every tab.
            .nowRunningAccessory(isActive: nowRunning.kind != nil)
            .tint(StrandPalette.accent)
            // #1841: the same "Hide bar when scrolling" preference Android drives its own bar with. Here
            // the system owns the behaviour — iOS 26's tab bar MINIMISES to a pill on scroll down.
        .task {
            await repo.refresh()
            // Backup & Sync: on-launch catch-up (see RootView). Detached + utility priority so a
            // 100MB+ whole-DB ZIP never blocks startup; gated on the auto toggle (default OFF). (Must-fix #4.)
            let backupRepo = repo
            Task.detached(priority: .utility) {
                await FolderBackup.catchUpIfDue(checkpoint: { await backupRepo.checkpointForBackup() })
            }
        }
        // Honour a router request by navigating the tabs, as a tap on the same row would: every screen
        // outside the three main tabs is a Browse row, so it is pushed there. Cleared so the same tap can
        // fire again later.
        .onChange(of: router.requestedDestination) { _, dest in
            switch dest {
            case .devices: openInBrowse(.devices)
            case .insightsHub: openInBrowse(.insightsHub)
            case .labBook: openInBrowse(.labBook)
            case .journal: openInBrowse(.journal)
            // The wake alarm and wind-down live in the Sleep tab's schedule page.
            case .alarms:
                selectedTab = 2
                tabPaths[2] = NavigationPath([TabRoute.sleepSchedule])
            case .coach:
                // Guarded on the master switch, because this route is reachable with Coach OFF: a brief
                // notification already sitting in Notification Centre still calls `openCoach()` when it is
                // tapped (StrandApp wires `onCoachBriefTapped` to it). Dropping the request leaves the
                // wearer where they were, which is the honest answer for a feature that is switched off.
                if coachEnabled { openInBrowse(.coach) }
            case .trends:
                // Trends lives under the Summary, as Health's "Show All Health Trends": a routed open pushes it there.
                selectedTab = 0
                tabPaths[0] = NavigationPath([TabRoute.trends])
            case .activeWorkout:
                // The running workout opens the same recording screen as the mini-player does.
                router.presentActiveWorkout = false
                nowRunning.expand(.workout)
            // The widgets' taps (`WidgetLink`): the Summary at its root, Live Heart Rate, and Day Stress, which
            // is pushed on the Summary as its card's tap-through is.
            case .today:
                selectedTab = 0
                tabPaths[0] = NavigationPath()
            case .heartRate: openInBrowse(.live)
            case .stress:
                selectedTab = 0
                tabPaths[0] = NavigationPath([TabRoute.metricSourced(key: "stress", source: "my-whoop")])
            case nil:
                break
            }
            if dest != nil { router.requestedDestination = nil }
        }
        // A cold-launch selection is already pending when this shell appears; a warm selection arrives
        // through the change callback. Both navigate the same way.
        .onAppear {
            presentPendingHomeScreenQuickActionIfPossible()
        }
        .onChange(of: homeScreenQuickActions.pendingAction) { _, _ in
            presentPendingHomeScreenQuickActionIfPossible()
        }
        .onChange(of: homeScreenQuickActionsEnabled) { _, _ in
            presentPendingHomeScreenQuickActionIfPossible()
        }
        // The recording screens the mini-player opens. The gym session keeps its own sheet below.
        .fullScreenCover(item: $nowRunning.expanded) { kind in
            switch kind {
            case .workout: LiveWorkoutView(onClose: { nowRunning.expanded = nil })
            case .intervals: IntervalRunHost { nowRunning.expanded = nil }
            case .lift: EmptyView()
            }
        }
        // A session left running by a previous launch is back before this view exists
        // (`LiftSessionController.resumeSaved`, from `StrandiOSApp.init`), as the BAR — not as a sheet
        // thrown in the user's face; they open it when they want it.
        .fullScreenCover(isPresented: $liftSession.isPresented) {
            LiftSessionView { }
        }
    }

    /// Mandatory launch gates defer an external action. Once the shell is available, a Home Screen choice
    /// navigates the tabs exactly as the matching in-app route does.
    private func presentPendingHomeScreenQuickActionIfPossible() {
        guard homeScreenQuickActionsEnabled,
              let action = homeScreenQuickActions.pendingAction else { return }
        homeScreenQuickActions.consume(action)
        switch action {
        case .liveHeartRate: openInBrowse(.live)
        case .logJournal: openInBrowse(.journal)
        case .breathe: openInBrowse(.breathe)
        case .startWorkout:
            // Workouts is a main tab: select it, at its root, where a workout starts.
            selectedTab = 1
            tabPaths[1] = NavigationPath()
        }
    }

    /// Selects Browse with `destination` pushed on its otherwise empty stack, so the screen has the same
    /// back button to Browse it has when reached from its row.
    private func openInBrowse(_ destination: MoreDestination) {
        selectedTab = 4
        tabPaths[4] = NavigationPath([destination])
    }

    /// One primary tab's root in its OWN NavigationStack, so in-content NavigationLinks both navigate and
    /// render opaque (an orphaned link draws its label disabled). The root hides the system nav bar —
    /// each screen draws its own header — while pushed detail screens get a bar + back button. The stack
    /// is bound to the tab's path so a re-tap pops to the root (#135/#198), and the roots' first-hop
    /// links push TabRoute values registered here ONCE per stack (a double registration double-pushes, #38).
    private func tabRoot<V: View>(_ view: V, path: Binding<NavigationPath>, scrollSignal: Int,
                                  showsNavigationBar: Bool = false) -> some View {
        NavigationStack(path: path) {
            view
                // Summary uses the native large title + glass toolbar (Health); the others draw their own.
                .toolbar(showsNavigationBar ? .automatic : .hidden, for: .navigationBar)
                .tabRouteDestinations()
                // Settings (pushed from the Summary's profile circle) pushes its pages as SettingsPage values.
                .settingsDestinations()
        }
        // Drive this tab's root scroll-to-top on an at-root re-tap (#198 follow-up).
        .environment(\.scrollToTopSignal, scrollSignal)
    }

    /// The Browse (search) tab: every screen outside the three main tabs, searchable, with its own
    /// large-title nav bar. Rows push `MoreDestination` values onto the bound path.
    private func browseTab(path: Binding<NavigationPath>, scrollSignal: Int) -> some View {
        NavigationStack(path: path) {
            BrowseView()
                // Trends pushes metric pages as TabRoute values, so this stack resolves them too.
                .tabRouteDestinations()
                // Devices (a Browse row) pushes its settings pages as SettingsPage values.
                .settingsDestinations()
                .navigationDestination(for: MoreDestination.self) { route in
                    route.destination
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbarBackground(.hidden, for: .navigationBar)
                }
        }
        .environment(\.scrollToTopSignal, scrollSignal)
    }

    private var summaryRoot: some View {
        tabRoot(todayTabRoot, path: $tabPaths[0], scrollSignal: scrollTop[0], showsNavigationBar: true)
    }
    /// Tag 1 was Trends; Workouts took its place in the bar (Trends now lives in Browse).
    private var workoutsRoot: some View {
        tabRoot(WorkoutsHomeView(), path: $tabPaths[1], scrollSignal: scrollTop[1], showsNavigationBar: true)
    }
    private var sleepRoot: some View {
        tabRoot(SleepHealthView(), path: $tabPaths[2], scrollSignal: scrollTop[2], showsNavigationBar: true)
    }
    /// Tag 3 was the retired Coach tab; Friends takes its place in the bar, as Fitness has Sharing.
    private var friendsRoot: some View {
        tabRoot(FriendsView(), path: $tabPaths[3], scrollSignal: scrollTop[3], showsNavigationBar: true)
    }
    private var browseRoot: some View { browseTab(path: $tabPaths[4], scrollSignal: scrollTop[4]) }

    /// iOS 18+ declares tabs with `Tab`, which is what lets Browse take the search role (its own glass
    /// circle on iOS 26). The availability check is fixed for the life of the process, so the branch
    /// never flips at runtime and never rebuilds the tab roots (#519).
    @ViewBuilder private var tabView: some View {
        if #available(iOS 18.0, *) {
            TabView(selection: nativeTabSelection) {
                Tab("Summary", systemImage: "heart.text.square", value: 0) { summaryRoot }
                Tab("Sleep", systemImage: "bed.double", value: 2) { sleepRoot }
                Tab("Workouts", systemImage: "figure.run", value: 1) { workoutsRoot }
                Tab("Friends", systemImage: "person.2.fill", value: 3) { friendsRoot }
                Tab("Browse", systemImage: "magnifyingglass", value: 4, role: .search) { browseRoot }
            }
        } else {
            TabView(selection: nativeTabSelection) {
                summaryRoot.tabItem { Label("Summary", systemImage: "heart.text.square") }.tag(0)
                sleepRoot.tabItem { Label("Sleep", systemImage: "bed.double") }.tag(2)
                workoutsRoot.tabItem { Label("Workouts", systemImage: "figure.run") }.tag(1)
                friendsRoot.tabItem { Label("Friends", systemImage: "person.2.fill") }.tag(3)
                browseRoot.tabItem { Label("Browse", systemImage: "magnifyingglass") }.tag(4)
            }
        }
    }
}


/// The interval recording screen, observing the app's one timer.
private struct IntervalRunHost: View {
    @EnvironmentObject private var runner: IntervalTimerRunner
    let onClose: () -> Void

    var body: some View { IntervalRunView(runner: runner, onClose: onClose) }
}

#endif
