package com.noop.ui

import com.noop.ui.m3.NavBarBackdropStrip
import com.noop.ui.m3.CappedFontScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.noop.R
import com.noop.push.SelfHostedPushScreen
import com.noop.ui.metric.ALL_METRICS_ROUTE
import com.noop.ui.metric.AllMetricsScreen
import com.noop.ui.metric.MetricAllDataScreen
import com.noop.ui.metric.MetricDescriptor
import com.noop.ui.metric.MetricDetailScreen
import com.noop.ui.metric.metricRoute
import com.noop.ui.settings.settingsGraph
import com.noop.ui.sleep.SleepActions
import com.noop.ui.sleep.SleepHistoryScreen
import com.noop.ui.sleep.SleepScheduleScreen
import com.noop.ui.sleep.SleepSessionScreen
import com.noop.ui.sleep.SleepTabScreen
import com.noop.ui.sleep.sleepSessionRoute
import com.noop.ui.summary.SummaryActions
import com.noop.ui.summary.SummaryScreen
import com.noop.ui.trends.TrainingLoadScreen
import com.noop.ui.trends.TrendsScreen
import com.noop.ui.m3.AlwaysDark
import com.noop.ui.workouts.IntervalRunScreen
import com.noop.ui.workouts.IntervalTimerRunner
import com.noop.ui.workouts.IntervalsSetupScreen
import com.noop.ui.workouts.LiveWorkoutRecording
import com.noop.ui.workouts.NowRunning
import com.noop.ui.workouts.NowRunningBar
import com.noop.ui.workouts.WorkoutDetailScreen
import com.noop.ui.workouts.WorkoutHistoryScreen
import com.noop.ui.workouts.WorkoutsHomeScreen
import com.noop.ui.workouts.WorkoutsNav
import com.noop.ui.workouts.workoutKey

// MARK: - Navigation model
//
// Twin of the iOS RootTabView: a Material 3 NavigationBar with the four tabs of [MainTab] (Summary, Sleep,
// Workouts, Browse) over ONE NavHost. Each tab keeps its own back stack: selecting a tab pops the whole
// visible stack with its state saved and restores the selected tab's saved stack, so a tab comes back
// exactly as it was left. Re-selecting the active tab pops it to its root, or scrolls a root that is
// already showing back to the top. Screens outside the three main tabs are Browse rows and push inside
// Browse; Settings pushes on the Summary from its avatar. A request from elsewhere (a Today card opening
// Coach, a Summary card opening the Sleep tab) selects the tab the screen lives in and pushes it there,
// as a tap on its row would.

/** A NavHost destination, by the stable route string it is registered under. */
internal enum class Destination(val route: String) {
    // The four tab roots.
    Today("today"),
    Sleep("sleep"),
    Workouts("workouts"),
    Browse("browse"),

    // Browse rows.
    AllMetrics(ALL_METRICS_ROUTE),
    Coach("coach"),
    Insights("insights"),
    LabBook("lab_book"),
    Trends("trends"),
    InsightsHub("insights_hub"),
    Devices("devices"),
    Live("live"),
    Breathe("breathe"),

    // Pushed from a screen.
    // Coach settings (#2243), reached only from the strip on the Coach page; it shares Coach's view model.
    CoachSettings("coach_settings"),
    Intervals("intervals"),
    // The Workouts tab's pages: All Workouts and one workout (its natural key, URI-encoded).
    WorkoutHistory("workout_history"),
    Workout("workout/{key}"),
    // A metric's page (key, optional source: null opens the freshest source), its All Data list, the Trends
    // page's Training Load, and the day by the second (a heart-rate page's option).
    Metric("metric/{key}?source={source}"),
    MetricData("metric_data/{key}?source={source}"),
    TrainingLoad("training_load"),
    FullDay("full_day"),
    Automations("automations"),
    // The Sleep tab's pages: one sleep's own page (the day it ended on, and the nap's start or 0 for the
    // day's main night), Sleep History, and the Sleep Schedule (the one alarm surface, which replaced the
    // Alarms screen).
    SleepSession("sleep_session/{day}/{nap}"),
    SleepHistory("sleep_history"),
    SleepSchedule("sleep_schedule"),
    // Settings > Import and Settings > Backup (also opened from Devices and a metric page).
    DataSources("data_sources"),
    BackupSync("backup_sync"),
    // Settings > Notifications > Wrist alerts.
    Notifications("notifications"),
    // The Settings root; its own pages register in ui/settings/SettingsGraph.kt.
    Settings("settings"),
    // Experimental: reachable only through Settings > Developer.
    SelfHostedPush("self_hosted_push"),
    // Shared by the Settings row and a blank WHOOP 4.0 Steps tile (#1515).
    StepsCalibration("steps_calibration"),
    TestCentre("test_centre"),
    GroundTruthCollector("ground_truth_collector"),
}

/** The All Data route of [metric]. */
private fun metricDataRoute(metric: MetricDescriptor): String =
    "metric_data/${android.net.Uri.encode(metric.key)}?source=${android.net.Uri.encode(metric.source)}"

/** The key + optional source arguments of the metric routes. */
private val metricArguments = listOf(
    navArgument("key") { type = NavType.StringType },
    navArgument("source") { type = NavType.StringType; nullable = true; defaultValue = null },
)

/**
 * App shell: a [Scaffold] whose bottom bar is the Material 3 [NavigationBar] of [MainTab], over one
 * [NavHost]. Every screen draws its own title; there is no global toolbar. A single [AppViewModel] is
 * created here and shared with every screen, so the BLE connection and cached metrics stay app-wide.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentTab = remember(backStackEntry) { nav.currentTab() }
    val atTabRoot = backStackEntry?.destination?.route == currentTab.route
    // One re-tap counter per tab, read by that tab's root through [LocalScrollToTopSignal].
    val scrollTop = remember { mutableStateListOf(*Array(MainTab.entries.size) { 0 }) }

    val context = LocalContext.current
    // The recording screen (a workout or the interval timer), drawn full screen over every tab and put away
    // to the mini-player above the navigation bar (iOS NowRunning). Guarded on a running workout so it never
    // opens empty.
    val expanded by NowRunning.expanded.collectAsStateWithLifecycle()
    val activeWorkout by viewModel.activeWorkout.collectAsStateWithLifecycle()
    // The interval timer buzzes the strap on every transition, only while one is bonded.
    LaunchedEffect(viewModel) {
        IntervalTimerRunner.shared.buzz = { loops ->
            if (viewModel.live.value.bonded) viewModel.buzz(loops, HapticPrefs.INTERVALS)
        }
    }

    // A widget or notification tap (AppLink) lands where it promised: the tab is selected and the screen
    // pushed as a tap on its row would, with the recording screen put away so the destination is in view.
    val pendingLink by AppLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingLink) {
        val link = pendingLink ?: return@LaunchedEffect
        AppLinks.consume()
        val target = AppLinkTarget.of(
            link,
            workoutActive = viewModel.activeWorkout.value != null,
            coachEnabled = CoachEnabledStore.enabled,
        )
        when (target) {
            is AppLinkTarget.TabRoot -> {
                NowRunning.collapse()
                nav.showTabRoot(target.tab)
            }
            is AppLinkTarget.Screen -> {
                NowRunning.collapse()
                nav.openInTab(target.tab, target.route)
            }
            is AppLinkTarget.Metric -> {
                NowRunning.collapse()
                nav.showTabRoot(target.tab)
                nav.push(metricRoute(target.key, target.source))
            }
            is AppLinkTarget.Recording -> {
                NowRunning.expand(NowRunning.Kind.Workout)
                if (target.confirmFinish) NowRunning.requestFinish()
            }
            null -> Unit
        }
    }

    /** Selects [tab] as a tap on it would: re-selecting pops to the root, then scrolls the root to the top. */
    fun onTabTapped(tab: MainTab) {
        when {
            tab != currentTab -> nav.selectTab(tab)
            !atTabRoot -> nav.popBackStack(tab.route, inclusive = false)
            else -> scrollTop[tab.ordinal] = scrollTop[tab.ordinal] + 1
        }
    }

    // Android Back on a tab root other than the Summary returns to the Summary, as Google's tabbed apps do;
    // Back on the Summary's root leaves the app. Pushed screens pop through the NavHost as usual.
    BackHandler(enabled = atTabRoot && currentTab != MainTab.Summary) {
        nav.selectTab(MainTab.Summary)
    }

    // The recording screen (a workout or the interval timer) is drawn over everything. While it is up it is
    // modal for TalkBack and switch access too: the tabs and the page under it leave the accessibility tree,
    // as they have left the screen (CR-7; they were still reachable, and operable, underneath).
    val recordingShown = when (expanded) {
        NowRunning.Kind.Workout -> if (activeWorkout != null) NowRunning.Kind.Workout else null
        NowRunning.Kind.Intervals -> NowRunning.Kind.Intervals
        null -> null
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        modifier = if (recordingShown != null) Modifier.clearAndSetSemantics {} else Modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            // Shown on tab roots and on pushed screens alike, as iOS keeps its tab bar. No recording is a
            // route to hide it for: a running workout or interval timer is drawn over everything, and put
            // away it docks as the mini-player above the bar.
            Column {
            NowRunningBar(viewModel)
            // Up to five labels share the bar's width, so they stop growing at 1.3x (at 2x "Summary" and
            // "Workouts" lost their last letter); TalkBack still reads each name in full (CR-1).
            CappedFontScale(max = 1.3f) {
                NavigationBar {
                    MainTab.shown(CoachEnabledStore.enabled).forEach { tab ->
                        val selected = tab == currentTab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { onTabTapped(tab) },
                            icon = {
                                Icon(if (selected) tab.selectedIcon else tab.unselectedIcon, contentDescription = null)
                            },
                            label = { Text(stringResource(tab.labelRes), maxLines = 1) },
                        )
                    }
                }
            }
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Destination.Today.route,
            modifier = Modifier.padding(inner),
            // Destinations crossfade (~240 ms) on the calm decelerating easing, forward and back alike.
            enterTransition = { fadeIn(navFadeSpec) },
            exitTransition = { fadeOut(navFadeSpec) },
            popEnterTransition = { fadeIn(navFadeSpec) },
            popExitTransition = { fadeOut(navFadeSpec) },
        ) {
            // --- Tab roots ---
            tabRoot(MainTab.Summary, scrollTop) {
                SummaryScreen(
                    vm = viewModel,
                    actions = SummaryActions(
                        // The profile avatar pushes Settings on the Summary (iOS: Summary avatar -> Settings).
                        openSettings = { nav.push(Destination.Settings.route) },
                        // Every metric card, ring figure, highlight and trend opens that metric's page here.
                        openMetric = { key, source -> nav.push(metricRoute(key, source)) },
                        openAllMetrics = { nav.push(Destination.AllMetrics.route) },
                        openTrends = { nav.push(Destination.Trends.route) },
                        // Rest and the Sleep card open the Sleep tab on the picked day's night.
                        openSleepNight = { wakeDay ->
                            SleepNightRequest.wakeDay = wakeDay
                            nav.showTabRoot(MainTab.Sleep)
                        },
                        // A nap's row opens that nap's page on the Sleep tab, over the day it belongs to.
                        openSleepNap = { wakeDay, napStartTs ->
                            SleepNightRequest.wakeDay = wakeDay
                            nav.openInTab(MainTab.Sleep, sleepSessionRoute(wakeDay, napStartTs))
                        },
                    ),
                )
            }
            tabRoot(MainTab.Sleep, scrollTop) {
                SleepTabScreen(
                    vm = viewModel,
                    actions = SleepActions(
                        openMetric = { key -> nav.push(metricRoute(key, null)) },
                        openSession = { day, nap -> nav.push(sleepSessionRoute(day, nap)) },
                        openHistory = { nav.push(Destination.SleepHistory.route) },
                        openSchedule = { nav.push(Destination.SleepSchedule.route) },
                    ),
                )
            }
            tabRoot(MainTab.Workouts, scrollTop) {
                WorkoutsHomeScreen(
                    vm = viewModel,
                    nav = WorkoutsNav(
                        openHistory = { nav.push(Destination.WorkoutHistory.route) },
                        openWorkout = { nav.push(workoutRoute(it)) },
                        openIntervals = { nav.push(Destination.Intervals.route) },
                    ),
                )
            }
            tabRoot(MainTab.Browse, scrollTop) {
                BrowseScreen(onOpen = { route -> nav.push(route) })
            }

            // --- Browse rows ---
            composable(Destination.AllMetrics.route) {
                AllMetricsScreen(
                    viewModel,
                    onBack = { nav.popBackStack() },
                    onOpenMetric = { nav.push(metricRoute(it.key, it.source)) },
                )
            }
            tabRoot(MainTab.Coach, scrollTop) {
                // The settings page is a normal push, so Back returns to the conversation (#2243).
                CoachScreen(onOpenSettings = { nav.push(Destination.CoachSettings.route) })
            }
            composable(Destination.CoachSettings.route) {
                // The SAME CoachViewModel the conversation is using, not a fresh one: `viewModel()` resolves
                // against the NavBackStackEntry, and CoachViewModel keeps consent in memory, so a revoke made
                // against a second instance would leave the conversation sending on the old one. Coach is
                // always below this entry: it is the root of the tab this page is pushed in.
                val coachEntry = remember(it) { nav.getBackStackEntry(Destination.Coach.route) }
                CoachSettingsScreen(vm = viewModel(coachEntry), onBack = { nav.popBackStack() })
            }
            composable(Destination.Insights.route) {
                com.noop.ui.journal.JournalScreen(vm = viewModel, onBack = { nav.popBackStack() })
            }
            composable(Destination.LabBook.route) {
                com.noop.ui.lab.LabResultsScreen(vm = viewModel, onBack = { nav.popBackStack() })
            }
            composable(Destination.Trends.route) {
                TrendsScreen(
                    viewModel,
                    onBack = { nav.popBackStack() },
                    onOpenMetric = { nav.push(metricRoute(it.key, it.source)) },
                    onOpenTrainingLoad = { nav.push(Destination.TrainingLoad.route) },
                )
            }
            composable(Destination.InsightsHub.route) {
                com.noop.ui.insights.WhatMovesYouScreen(
                    vm = viewModel,
                    onBack = { nav.popBackStack() },
                    onOpenJournal = { nav.push(Destination.Insights.route) },
                )
            }
            composable(Destination.Devices.route) {
                DevicesScreen(
                    viewModel,
                    onBack = { nav.popBackStack() },
                    onUseFileImport = { nav.push(Destination.DataSources.route) },
                )
            }
            composable(Destination.Live.route) {
                com.noop.ui.live.HeartRateScreen(
                    vm = viewModel,
                    onBack = { nav.popBackStack() },
                    onOpenDevices = { nav.openInTab(MainTab.Browse, Destination.Devices.route) },
                    onOpenActiveWorkout = { NowRunning.expand(NowRunning.Kind.Workout) },
                )
            }
            composable(Destination.Breathe.route) {
                com.noop.ui.mind.MindfulnessScreen(
                    vm = viewModel,
                    onBack = { nav.popBackStack() },
                    onOpenDevices = { nav.openInTab(MainTab.Browse, Destination.Devices.route) },
                )
            }

            // --- Pushed from a screen ---
            composable(Destination.Intervals.route) { IntervalsSetupScreen(onBack = { nav.popBackStack() }) }
            composable(Destination.WorkoutHistory.route) {
                WorkoutHistoryScreen(viewModel, onBack = { nav.popBackStack() }, openWorkout = { nav.push(workoutRoute(it)) })
            }
            composable(Destination.Workout.route, arguments = listOf(navArgument("key") { type = NavType.StringType })) { entry ->
                WorkoutDetailScreen(viewModel, key = entry.arguments?.getString("key").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable(Destination.Metric.route, arguments = metricArguments) { entry ->
                MetricDetailScreen(
                    vm = viewModel,
                    key = entry.arguments?.getString("key").orEmpty(),
                    source = entry.arguments?.getString("source"),
                    onBack = { nav.popBackStack() },
                    onOpenAllData = { nav.push(metricDataRoute(it)) },
                    onOpenFullDay = { nav.push(Destination.FullDay.route) },
                    onOpenDataSources = { nav.push(Destination.DataSources.route) },
                )
            }
            composable(Destination.MetricData.route, arguments = metricArguments) { entry ->
                MetricAllDataScreen(
                    vm = viewModel,
                    key = entry.arguments?.getString("key").orEmpty(),
                    source = entry.arguments?.getString("source"),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Destination.TrainingLoad.route) { TrainingLoadScreen(viewModel, onBack = { nav.popBackStack() }) }
            composable(Destination.FullDay.route) { FullDayChartScreen(vm = viewModel, onBack = { nav.popBackStack() }) }
            composable(Destination.Automations.route) { AutomationsScreen(viewModel, onBack = { nav.popBackStack() }) }
            composable(
                Destination.SleepSession.route,
                arguments = listOf(
                    navArgument("day") { type = NavType.StringType },
                    navArgument("nap") { type = NavType.LongType },
                ),
            ) { entry ->
                SleepSessionScreen(
                    viewModel,
                    day = entry.arguments?.getString("day").orEmpty(),
                    napStartTs = entry.arguments?.getLong("nap") ?: 0L,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Destination.SleepHistory.route) { SleepHistoryScreen(viewModel, onBack = { nav.popBackStack() }) }
            composable(Destination.SleepSchedule.route) { SleepScheduleScreen(viewModel, onBack = { nav.popBackStack() }) }
            composable(Destination.Notifications.route) { NotificationsSettingsScreen(viewModel, onBack = { nav.popBackStack() }) }
            // Settings and its pages, with Import (DataSources) and Backup (BackupSync).
            settingsGraph(viewModel, open = { nav.push(it) }, back = { nav.popBackStack() })
            composable(Destination.StepsCalibration.route) {
                val profile = remember(context) { ProfileStore.from(context) }
                var revision by remember { mutableStateOf(0) }
                // ProfileStore wraps SharedPreferences rather than snapshot state. Reading this counter
                // makes manual coefficient changes repaint the canonical screen immediately.
                @Suppress("UNUSED_VARIABLE") val tick = revision
                StepsCalibrationScreen(
                    vm = viewModel,
                    profile = profile,
                    onProfileChanged = { revision++ },
                    onClose = { nav.popBackStack() },
                )
            }
            composable(Destination.SelfHostedPush.route) { SelfHostedPushScreen() }
            composable(Destination.TestCentre.route) {
                TestCentreScreen(viewModel, onOpenGroundTruthCollector = {
                    nav.push(Destination.GroundTruthCollector.route)
                })
            }
            composable(Destination.GroundTruthCollector.route) { GroundTruthCollectorScreen(viewModel) }
        }
    }


    // Under a full-screen dialog or a bottom sheet the strip below the gesture bar is this activity (their
    // windows stop at the bar): their own surface colour there, not the navigation bar's.
    NavBarBackdropStrip()

    // The recording screen, always dark, sliding up over the whole app (bars included).
    val shown = recordingShown
    var lastShown by remember { mutableStateOf<NowRunning.Kind?>(null) }
    if (shown != null) lastShown = shown
    AnimatedVisibility(
        visible = shown != null,
        enter = slideInVertically(navSlideSpec) { it },
        exit = slideOutVertically(navSlideSpec) { it },
    ) {
        AlwaysDark {
            when (lastShown) {
                NowRunning.Kind.Workout -> LiveWorkoutRecording(viewModel, onMinimize = NowRunning::collapse)
                NowRunning.Kind.Intervals -> IntervalRunScreen(viewModel, onMinimize = NowRunning::collapse)
                null -> Unit
            }
        }
    }
    }
}

/** The page of one workout on the Workouts tab. */
private fun workoutRoute(row: com.noop.data.WorkoutRow): String = "workout/${android.net.Uri.encode(workoutKey(row))}"

/**
 * Registers [tab]'s root screen. The root reads its own tab's re-tap counter, so the value it sees never
 * changes because another tab was selected (only a re-tap of this tab moves it).
 */
private fun androidx.navigation.NavGraphBuilder.tabRoot(
    tab: MainTab,
    scrollTop: List<Int>,
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(tab.route) { entry ->
        CompositionLocalProvider(LocalScrollToTopSignal provides scrollTop[tab.ordinal]) {
            content(entry)
        }
    }
}

/**
 * Selects [tab]: everything above the graph is popped with its state saved (the tab being left, root
 * included), then [tab]'s saved stack is restored, or its root pushed the first time. Popping to the
 * graph rather than to the start destination treats all the tabs alike, so the Summary's pushed screens
 * survive a trip to another tab as reliably as any other tab's do.
 */
private fun NavHostController.selectTab(tab: MainTab) {
    navigate(tab.route) {
        popUpTo(graph.id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** True when an entry for [route] is on the back stack. */
private fun NavHostController.hasBackStackEntry(route: String): Boolean =
    runCatching { getBackStackEntry(route) }.isSuccess

/**
 * The tab whose root is on the back stack. Exactly one is: a tab switch pops everything down to the graph
 * before the next tab's stack goes on, and no screen pushes a tab root.
 */
private fun NavHostController.currentTab(): MainTab =
    MainTab.entries.firstOrNull { hasBackStackEntry(it.route) } ?: MainTab.Summary

/** Shows [tab] at its root (the Summary's Sleep card and Rest ring, a running workout). */
private fun NavHostController.showTabRoot(tab: MainTab) {
    if (tab != currentTab()) selectTab(tab)
    popBackStack(tab.route, inclusive = false)
}

/**
 * Opens [route] inside [tab], as a tap on its row would. From another tab the target tab starts from its
 * root, so Back from the opened screen lands on that tab's root (iOS `openInBrowse`); inside the tab it
 * pushes on top, so Back returns to the screen that asked.
 */
private fun NavHostController.openInTab(tab: MainTab, route: String) {
    if (tab != currentTab()) {
        selectTab(tab)
        popBackStack(tab.route, inclusive = false)
    }
    push(route)
}

/** Pushes [route] on the current tab (never twice on top of itself). */
private fun NavHostController.push(route: String) {
    navigate(route) { launchSingleTop = true }
}

/** Shows the Coach tab; dropped while the AI Coach switch is off (a stale brief tap still asks). */
private fun NavHostController.openCoach() {
    if (CoachEnabledStore.enabled) showTabRoot(MainTab.Coach)
}

// MARK: - Navigation motion
//
// The calm, decelerating cubic-bezier(0.22, 1, 0.36, 1): nothing bounces or overshoots. Destinations
// crossfade over ~240 ms, and back navigation uses the same spec.

/** The calm global easing curve (cubic-bezier 0.22, 1, 0.36, 1). */
private val NavEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** ~240 ms crossfade on the calm easing. */
private val navFadeSpec = tween<Float>(durationMillis = 240, easing = NavEasing)

/** The recording screen's slide, on the same calm easing. */
private val navSlideSpec = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = 320, easing = NavEasing)
