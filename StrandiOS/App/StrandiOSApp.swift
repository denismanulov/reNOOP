#if os(iOS)
import SwiftUI
import ActivityKit
import StrandDesign
import UserNotifications

/// iOS entry point. Unlike the macOS app (which adds a `MenuBarExtra` scene), iOS uses a single
/// `WindowGroup`; the glanceable menu-bar role is filled by the Home/Lock-Screen widget instead.
///
/// The iOS shell is `RootTabView` (a `TabView`), NOT the macOS `ContentView`. `ContentView` embeds
/// `RootView()` — the `NavigationSplitView` sidebar shell — and `RootView.swift` is excluded from the
/// iOS target in `project.yml` (the sidebar has no iPhone analogue), so `ContentView` cannot compile
/// on iOS. The first-run onboarding/pairing wizard, the Terms acknowledgment gate, and the post-update
/// "What's New" sheet that `ContentView` layers on are reproduced here as `iOSRootView`, wrapped around
/// `RootTabView` so the iOS app keeps the same gating without depending on the macOS-only shell.
@main
struct StrandiOSApp: App {
    /// UIKit bridge for Home Screen quick actions. SwiftUI keeps ownership of the scene and window.
    @UIApplicationDelegateAdaptor(HomeScreenQuickActionAppDelegate.self) private var appDelegate
    @StateObject private var model: AppModel
    @StateObject private var health: HealthKitBridge
    /// The phone→watch link. Built + activated here so the watch app actually receives snapshots on a
    /// real device; without an owner that pushes it, the watch only ever shows placeholder data.
    @StateObject private var watch = WatchSessionBridge()
    /// Shared cross-screen navigation hook (e.g. Live → Devices). The iOS shell (`RootTabView`)
    /// observes it and presents the Devices manager.
    @StateObject private var router: NavRouter
    /// NOOP's live heart rate banner. Built in `init` and fed from there (`LiveActivityController.follow`), not from
    /// a view: a process iOS starts in the background need not build one.
    @State private var liveActivity: LiveActivityController
    /// The Lift Log session's own Live Activity. Separate from the live-HR one above: while a gym
    /// session is open this is the banner that matters (it carries the heart rate too), so the HR
    /// activity is suppressed rather than stacked beside it. Built in `init`, where the strap log it
    /// writes to exists.
    @State private var liftActivity: LiftLiveActivityController
    /// The interval timer's banner, laid out as the Clock app's timer. Follows the app's one timer.
    @State private var intervalActivity: IntervalLiveActivityController
    /// The live gym session. Owned HERE, at the app root, rather than by the screen that shows it:
    /// swiping the workout sheet away must not stop the clock, silence the strap or drop the
    /// double-tap handler. See `LiftSessionController`.
    @StateObject private var liftSession: LiftSessionController
    /// The interval timer, owned here for the same reason as the gym session: leaving its page must not stop it.
    @StateObject private var intervals: IntervalTimerRunner
    /// Which of the three is running, for the one mini-player under the tab bar (`NowRunningAccessory`).
    @StateObject private var nowRunning: NowRunning
    @Environment(\.scenePhase) private var scenePhase
    /// Chart data-colour style (Titanium / Classic throwback). Re-colours gauges + charts.
    @AppStorage(ChartStyle.storageKey) private var chartStyleRaw = ChartStyle.titanium.rawValue
    /// Chrome accent colour (mint / WHOOP blue / custom). Chrome only — never the data colour worlds.
    @AppStorage(AccentColor.storageKey) private var accentRaw = AccentColor.mint.rawValue
    @AppStorage(AccentColor.customHexKey) private var accentCustomHex = AccentColor.defaultCustomHex
    /// Effort's display scale is also embedded in the shared widget snapshot. Observe it here so a
    /// Settings change gets one accurate full rebuild instead of waiting for an unrelated repo refresh.
    @AppStorage(UnitPrefs.effortScaleKey) private var effortScaleRaw = EffortScale.hundred.rawValue
    /// kg vs lb for the Lift Log Live Activity's "8 x 30 kg" line — the app formats it, because the
    /// unit preference lives here and not in the widget extension.
    @AppStorage(UnitPrefs.systemKey) private var unitSystemRaw = UnitSystem.metric.rawValue

    init() {
        #if DEBUG
        AccessibilityDump.scheduleIfRequested()
        #endif
        // #1008: pin the pre-change Overnight-only default for existing installs before
        // anything reads it. Idempotent; a no-op on fresh installs and after the first launch.
        PuffinExperiment.migrateContinuousHrvOvernightDefault()
        // One fixed look that follows the system: pin the retired theme knobs before any view reads them.
        AppearanceLock.apply()
        RetiredSettings.purge()
        // Debug-only canary: trips if the App Group entitlement is missing on this target before any
        // silent no-op (PendingIntents, WidgetSnapshot.publish, Live Activity) can mask the issue as
        // "the widget doesn't show anything yet." No-op in Release.
        WidgetSnapshot.assertGroupProvisioned()
        // #510: register the scheduled debug auto-export's BGTask handler BEFORE launch finishes — iOS
        // only delivers a background task whose identifier was registered at launch AND listed in the
        // target's BGTaskSchedulerPermittedIdentifiers (project.yml). Without this the overnight drop
        // never fires; the macOS timer, foreground catch-up, and "Run now" already work without it.
        ScheduledDebugExport.register()
        // Foreground presentation: without a delegate, iOS suppresses a notification's banner while the app
        // is open, so a user testing the wind-down reminder with NOOP foregrounded sees nothing. Register
        // before the first scene so any early-fired notification is presented.
        UNUserNotificationCenter.current().delegate = NotificationPresenter.shared
        // K5: tapping a scheduled morning-brief notification routes to Coach via the shared NavRouter.
        let router = NavRouter()
        _router = StateObject(wrappedValue: router)
        NotificationPresenter.shared.onCoachBriefTapped = { [weak router] in router?.openCoach() }
        // The brief widget shows only while the Coach is on.
        CoachBriefScheduler.publishMasterSwitch(CoachBriefScheduler.coachMasterEnabled)
        let model = AppModel()
        _model = StateObject(wrappedValue: model)
        CoachBriefScheduler.register(generateBrief: { [weak coach = model.coach] in
            await coach?.generateBrief()
        }, log: { [weak model] line in
            model?.live.append(log: AppModel.stamped(line))
        })
        // Settings → "Keep screen on while syncing". Wired once here, not as another modifier on `body`.
        SyncKeepAwake.shared.attach(to: model.live)
        // The strap-sync Live Activity (Lock Screen + Dynamic Island). Same placement, same reason — and
        // it must also run in a process the Sync Strap shortcut launched with no scene.
        SyncLiveActivityController.shared.attach(to: model.live)
        // iOS's own daily report of NOOP's CPU, memory, disk writes, hangs and exits, and its crash/hang reports,
        // one strap-log line each. Registering is the whole cost; iOS gathers and delivers them (MetricKitLog).
        MetricKitLog.shared.attach(to: model.live)
        // The buzz and the strap-gesture claim are injected, so the controller itself knows nothing
        // about BLE and stays testable.
        let liftSession = LiftSessionController(
            buzz: { [weak model] loops in
                model?.buzz(loops: loops, gate: HapticPrefs.liftRest)
            },
            setStrapHandler: { [weak model] handler in
                model?.strapDoubleTapOverride = handler
            },
            log: { [weak model] line in
                model?.live.append(log: AppModel.stamped(line))
            })
        _liftSession = StateObject(wrappedValue: liftSession)
        let liftActivity = LiftLiveActivityController(log: { [weak model] line in
            model?.live.append(log: AppModel.stamped(line))
        })
        _liftActivity = State(initialValue: liftActivity)
        // The live heart rate banner makes room only for the Lift Log banner actually on screen, which carries the
        // heart rate itself — not for a sync (`LiveHRBannerLifecycle`).
        let liveActivity = LiveActivityController()
        let intervalActivity = IntervalLiveActivityController()
        _intervalActivity = State(initialValue: intervalActivity)
        liveActivity.follow(model, standsAside: { [weak liftActivity, weak intervalActivity] in
            liftActivity?.isShowing == true || intervalActivity?.isShowing == true
        })
        _liveActivity = State(initialValue: liveActivity)
        // A gym session keeps ONE banner on the Lock Screen, its own — as the live-HR banner already
        // stands aside for it. A sync started in the foreground mid-session starts no sync banner.
        // Held back only for a gym banner that will actually show: with its switch off, a session leaves the
        // Lock Screen to the sync, rather than to nothing.
        SyncLiveActivityController.shared.holdsBackNewBanner = { [weak liftSession] in
            liftSession?.isActive == true && UnitPrefs.liftLiveActivityEnabled()
        }
        // Before any view or publisher exists: the first push to the Lock Screen banner must find the
        // session already running, or it ends the banner iOS kept alive across the restart.
        liftSession.resumeSaved()
        let intervals = IntervalTimerRunner()
        _intervals = StateObject(wrappedValue: intervals)
        intervalActivity.follow(intervals)
        // The Lock Screen banners' buttons run here, in the app (`LiveActivityIntents`).
        LiveActivityActions.handler = { [weak liftSession, weak intervals] action in
            switch action {
            case .liftSetDone: liftSession?.advance()
            case .intervalsToggle: intervals?.toggleRunning()
            }
        }
        _nowRunning = StateObject(wrappedValue: NowRunning(model: model, lift: liftSession, intervals: intervals))
        #if DEBUG
        DemoRunning.start(model: model, lift: liftSession, intervals: intervals)
        #endif
        // #1538: a strap offload completes while the app is BACKGROUNDED — it stays alive as a
        // bluetooth-central to receive it — and the re-score it triggers took nearly eight minutes on the
        // reporter's install, far longer than that wake survives. The pass is all-or-nothing, so being
        // suspended lost every scored night AND left the watermark unadvanced, which made the next offload
        // start the same doomed pass again. This processing task is where that work is escalated to; it is
        // the long, deferrable kind rather than the metered refresh kind the two schedulers above use.
        // Registered before launch finishes and permitted in project.yml, or iOS never delivers it.
        RescoreBackgroundScheduler.register(perform: { [weak model] in
            await model?.runDeferredRescoreIfOwed()
        }, onExpire: { [weak model] in
            model?.live.append(log: "re-score: background processing time expired before the pass finished (#1538)")
        })
        // #2556: its own wake, because every existing one is conditional on something the missing strap
        // makes false. Registered unconditionally and re-armed from inside its own handler.
        StaleBatteryBackgroundScheduler.register(perform: { [weak model] in
            await model?.checkStrapNotSeen()
        })
        StaleBatteryBackgroundScheduler.schedule()
        let bridge = HealthKitBridge(
            repo: model.repo,
            appleDeviceId: model.appleDeviceId,
            noopDeviceId: model.deviceId
        )
        _health = StateObject(wrappedValue: bridge)
        // Register a separate, always-on-while-authorized refresh task for Apple Health write-back.
        // The operation is write-only and bounded to the bridge's recent window; fresh BLE offloads still
        // use the immediate hook below. BGTaskScheduler chooses the actual wake time.
        HealthWritebackBackgroundScheduler.register { [weak bridge] in
            guard let bridge else { return false }
            let succeeded = await bridge.writeBackAfterNewData()
            // A person can revoke every write type in Settings while NOOP is closed. Stop requesting
            // wakes once the cold-launched bridge can no longer resume a prior share grant.
            if bridge.auth != .authorized {
                HealthWritebackBackgroundScheduler.cancel()
            }
            return succeeded
        }
        // #1021: publish to Apple Health when an offload lands, not only on foreground entry - the
        // scenePhase pass below starts the offload and wrote to Health in parallel with it, so a night
        // synced on open only reached Health at the next launch. Weak so the scene owns the bridge's
        // lifetime; the bridge no-ops unless Health was authorized.
        model.healthWriteBack = { [weak bridge] in
            _ = await bridge?.writeBackAfterNewData()
        }
    }

    /// The Shortcut-import alert's presentation binding, hoisted OUT of the `.alert` chain.
    ///
    /// An inline `Binding(get:set:)` is two untyped closures the solver must infer in place, on a
    /// modifier chain that had already blown the type-check budget. Declaring it as a `Binding<Bool>`
    /// property replaces all of that with one known type. Hoisting the message alone was not enough —
    /// the build failed again at the same modifier, which is why this one is here too.
    private var healthImportAlertPresented: Binding<Bool> {
        Binding(
            get: { model.pendingShortcutHealthImport != nil },
            set: { showing in
                if !showing { model.cancelPendingHealthImport() }
            }
        )
    }

    /// The Shortcut-import alert's buttons, hoisted for the same reason as the binding above.
    @ViewBuilder
    private var healthImportAlertButtons: some View {
        Button("Import") { model.confirmPendingHealthImport() }
        Button("Cancel", role: .cancel) { model.cancelPendingHealthImport() }
    }

    /// The Shortcut-import alert's message, hoisted OUT of the `.alert` chain.
    ///
    /// Not a style preference. This closure — an `if let` around two interpolated `Text`s — sits on a
    /// modifier chain that grew past the Swift type-checker's budget, and the build failed with
    /// "unable to type-check this expression in reasonable time" pointing at `} message: {`. The
    /// expression did not change; the chain around it did. Hoisting a sub-expression into its own
    /// declaration gives the solver a fixed type to work from instead of one more unknown in a chain
    /// it is already struggling with.
    @ViewBuilder
    private var healthImportAlertMessage: some View {
        if let pending = model.pendingShortcutHealthImport {
            Text("A Shortcut wants to add \(pending.daysCount) days and \(pending.workoutsCount) workouts to the Apple Health import source.")
        } else {
            Text("A Shortcut wants to add data to the Apple Health import source.")
        }
    }

    /// Opens NOOP where a widget or Live Activity tap promised (HIG: a widget interaction opens the app at the right
    /// location). Screens go through `NavRouter`, which the tab shell turns into a tab selection and a push, as a
    /// tap on the same row would; the running session opens exactly as tapping the mini-player does
    /// (`NowRunning.expand`). A session that has already finished opens nothing, leaving NOOP where it was.
    private func openWidgetLink(_ link: WidgetLink) {
        switch link {
        case .today: router.requestedDestination = .today
        case .heartRate: router.requestedDestination = .heartRate
        case .stress: router.requestedDestination = .stress
        case .coach: router.openCoach()
        case .devices: router.openDevices()
        case .workout:
            if nowRunning.kind == .lift {
                nowRunning.expand(.lift)
            } else if nowRunning.workout != nil {
                nowRunning.expand(.workout)
            }
        case .intervals:
            if intervals.inProgress { nowRunning.expand(.intervals) }
        }
    }

    var body: some Scene {
        WindowGroup {
            iOSRootView()
                .environmentObject(model)
                .environmentObject(model.ble)   // #334: Today pull-to-sync reads BLEManager (no HR churn)
                .environmentObject(model.live)
                .environmentObject(model.repo)
                .environmentObject(model.profile)
                .environmentObject(model.behavior)
                .environmentObject(model.intelligence)
                .environmentObject(model.coach)
                .environmentObject(health)
                .environmentObject(router)
                .environmentObject(UpdateStore.shared)
                .environmentObject(liftSession)
                .environmentObject(intervals)
                .environmentObject(nowRunning)
                #if DEBUG
                .task { await DemoActivity.start() }
                #endif
                // v5 L3: the shared stress check-in nudge surface, so the Breathe screen's passive
                // card observes the SAME instance the central detector (AppModel.evaluateStress) posts to.
                .environment(\.stressNudgeCenter, model.stressNudgeCenter)
                .preferredColorScheme(AppearanceLock.colorScheme)
                // Match SwiftUI format styles to the localization selected by the app's bundles. Language
                // changes are process-wide on Apple and are applied after the documented reopen.
                .environment(\.locale, AppLanguage.activeLocale)
                .chartStyle(chartStyleRaw)
                .noopAccent(accentRaw, customHex: accentCustomHex)
                // One loading indicator everywhere: the system spinner in grey (`SystemProgressStyle`).
                .systemProgressStyle()
                // `hr` is the value being written: this runs in willSet, when `live.heartRate` still holds the old one.
                .onReceive(model.live.$heartRate) { hr in
                    // The gym banner's own cheap path: no presentation is built here, and a heart rate moves
                    // the banner only when `LiftBannerPushPolicy` says it is worth a push. Everything else
                    // about the session pushes through `pushLiftActivity` below, carrying the current number.
                    liftActivity.updateHeartRate(model.live.connected ? (model.bpm ?? hr) : nil)
                }
                // The gym session's own banner follows each change to the session once it has landed —
                // a stage, typed numbers, a rest's end — and the heart rate above; the controller decides
                // what is worth pushing, and the banner's clocks tick on their own. A strap step is pushed
                // at once, with its light-up alert, below.
                .onReceive(liftSession.changesSettled) { _ in pushLiftActivity() }
                // A strap double-tap lights the Lock Screen on the step it took.
                .onReceive(liftSession.strapStepTaken) { _ in pushLiftActivity(alert: true) }
                // #911/#759: republish the Home/Lock-Screen widget whenever the dashboard caches actually
                // change mid-session. The only other publish site is the scenePhase .active handler, so
                // during a long foreground session the widget froze at the last-foreground snapshot while
                // Today and the Live Activity kept updating. `refreshSeq` is diff-guarded (Repository.refresh
                // skips the bump when the merged caches are byte-identical) and refresh() assigns every cache
                // BEFORE bumping the seq, so this publish always reads fresh data. `dropFirst()` skips the
                // publisher's attach-time replay of the current value; the .active publish already covers
                // launch. BUDGET: this app runs with bluetooth-central, so the process is NOT suspended in
                // the background, and the 15-minute analyze tick + backfill-completion refreshes bump the
                // seq back there too, where WidgetKit reloads DO count against the daily budget. Hence the
                // foreground gate: publish only while .active (foreground-initiated reloads are budget
                // exempt); a background bump is covered by the widget's own 15-minute timeline policy and
                // by the .active republish on return.
                .onReceive(model.repo.$refreshSeq.dropFirst()) { _ in
                    guard scenePhase == .active else { return }
                    Task { await WidgetSnapshot.publish(from: model) }
                    // The watch rides the same active-only hook because the bridge now SELF-THROTTLES
                    // (30-minute spacing + headline-change dedup, both must pass, see WatchSessionBridge),
                    // so a refresh storm can't burn the ~50/day complication transfer budget.
                    Task { await watch.pushLatest(from: model) }
                }
                // #114: strap battery % and connection are LIVE (model.live), not repo-cache, so they never
                // bump refreshSeq — the widget's battery would otherwise never move while the app is open
                // (the "battery not updating" report). Republish on those too, foreground-gated. Both are
                // low-frequency (battery ~every 8 min; connection flips are rare), so no throttle is needed
                // and foreground-initiated reloads are budget-exempt. dropFirst() skips the attach replay.
                .onReceive(model.live.$batteryPct.dropFirst()) { _ in
                    guard scenePhase == .active else { return }
                    Task { await WidgetSnapshot.publishLive(from: model) }
                }
                .onReceive(model.live.$connected.dropFirst()) { _ in
                    guard scenePhase == .active else { return }
                    Task { await WidgetSnapshot.publishLive(from: model) }
                }
                // #114 (follow-up): `WidgetSnapshot.bpm` reads `model.bpm` (WidgetPublish.swift), the
                // smoothed live HR — same LIVE-not-repo-cache category as battery/connected above, so it
                // has the same gap: nothing bumped `refreshSeq` while a heart-rate stream was live, so the
                // widget's HR froze at the last foreground snapshot for the rest of the session. UNLIKE
                // battery/connection, HR is HIGH-frequency (the smoothed median moves every few seconds
                // under activity), so — unlike the ungated hooks above — this one is throttled through
                // `HRPublishThrottle` (60 s, mirroring Android's PushGate HR cadence). `publishLive` then
                // updates the saved live fields without re-reading the full Rest series, while the throttle
                // still bounds the App-Group writes + WidgetKit timeline reloads.
                .onReceive(model.$bpm.dropFirst()) { _ in
                    guard scenePhase == .active else { return }
                    guard WidgetSnapshot.HRPublishThrottle.admit() else { return }
                    Task { await WidgetSnapshot.publishLive(from: model) }
                }
                .onChange(of: effortScaleRaw) { _, _ in
                    guard scenePhase == .active else { return }
                    Task { await WidgetSnapshot.publish(from: model) }
                }
                // Apple Health is explicitly opt-in. Once any write type is authorized, keep one
                // best-effort BGAppRefresh request armed; revoking all write access cancels it.
                .onChange(of: health.auth) { _, auth in
                    HealthWritebackBackgroundScheduler.updateSchedule(isAuthorized: auth == .authorized)
                }
                // #581: the `noop://import-health` deep link the iOS Shortcut opens after building the
                // HealthKit-free payload. Filter on the host so other future schemes don't trip the
                // importer; macOS never registers the scheme so this stays iOS-only.
                //
                // Widgets and Live Activities open `noop://<route>` (`WidgetLink`), which lands where the tap
                // promised (`openWidgetLink`). Any other host (the Oura OAuth `noop://oura/callback`, which its
                // web-auth session consumes itself) falls through untouched.
                .onOpenURL { url in
                    if url.host == "import-health" {
                        model.handleHealthImportURL(url)
                    } else if let link = WidgetLink(url: url) {
                        openWidgetLink(link)
                    }
                }
                .alert("Import Apple Health data?", isPresented: healthImportAlertPresented) {
                    healthImportAlertButtons
                } message: {
                    healthImportAlertMessage
                }
                // Bring the watch link up once at launch (WCSession ignores a redundant activate), then
                // push the first snapshot so a watch that's already on-wrist gets current scores without
                // waiting for the next foreground. activate() is idempotent + a no-op where WC isn't
                // supported, so this is safe on every device/simulator combination.
                .task {
                    watch.activate()
                    await watch.pushLatest(from: model)
                }
        }
        // HealthKit authorization is intentionally NOT requested on launch. The system permission
        // dialog without prior in-app rationale violates Apple HIG / App Review guidance — the user
        // sees the prompt before any context. It is requested from an explicit user action instead:
        // the "Enable Apple Health" affordance in AppleHealthView (More → Data → Apple Health).
        // Below, `refreshAuthIfPreviouslyGranted` re-primes `auth` for users who already granted
        // access (it only reads write/share status, never prompts) so background syncs resume; and
        // HealthKitBridge.sync guards on `auth == .authorized`, so the scenePhase trigger stays a
        // safe no-op until the user opts in.
        .onChange(of: scenePhase, initial: true) { _, phase in
            if phase == .active {
                CoachBriefScheduler.activateIfEnabled { await model.coach.generateBrief() }
                model.drainPendingIntents(router: router)
                // iOS starts a Lift Log banner only for an app on screen, so a banner lost while NOOP was in
                // the background comes back now, whether or not the strap is sending anything.
                pushLiftActivity()
                // Only the foreground may start the live heart rate banner: offer it now.
                liveActivity.appBecameActive()
                // End a "Connecting…" sync island whose sync never came, rather than leave it greyed.
                SyncLiveActivityController.shared.reconcile(live: model.live)
                // Re-arm the strap's smart alarm on foreground: the firmware alarm is a single instant
                // and iOS can't re-arm it while suspended, so it would otherwise fire once and stop.
                model.applySmartAlarm()
                // #267: pull a reasonably fresh sync on open rather than waiting for the 900s periodic
                // timer or an incidental reconnect. Floored at 90s and never clock/empty-streak-suppressed
                // (BackfillPolicy.shouldRun's .foreground case), so this is a safe no-op on rapid re-opens.
                model.ble.requestSync(.foreground)
                // #1538: settle a re-score an earlier background attempt could not finish, rather than
                // waiting on the 15-minute idle tick now that there is a foreground with no suspension
                // deadline. A no-op unless one is genuinely outstanding.
                //
                // Its OWN task, deliberately. This pass is minutes long on the installs that need it —
                // that is the whole reason it was deferred — and the sequential block below owns Health
                // sync, the widget snapshot and the watch push. Awaiting it there would leave the widget
                // and the watch showing stale numbers for the entire re-score every time the app is
                // opened, which is a worse regression than the bug being fixed. `analyzeRecent`
                // serialises itself, so overlapping with the sync this foreground also kicks off is safe.
                Task { await model.runDeferredRescoreIfOwed() }
                Task {
                    health.refreshAuthIfPreviouslyGranted()
                    HealthWritebackBackgroundScheduler.updateSchedule(
                        isAuthorized: health.auth == .authorized)
                    await HealthSyncRefreshCoordinator.run(
                        sync: { await health.sync() },
                        refresh: {
                            await model.refreshAfterAppleHealthSync(
                                authorized: health.auth == .authorized)
                        }
                    )
                    await WidgetSnapshot.publish(from: model)
                    // Push the wrist on the SAME refresh as the Home-screen widget so the watch, the
                    // widget and Today never disagree about which day they describe. Without this the
                    // watch only ever holds placeholder data on a real device.
                    await watch.pushLatest(from: model)
                }
            } else if phase == .background {
                // Re-submit on every transition because iOS may discard an old best-effort request.
                HealthWritebackBackgroundScheduler.updateSchedule(
                    isAuthorized: health.auth == .authorized)
                // #1538: same reasoning for the re-score continuation, plus one case of its own. A pass
                // can be left owed with NOTHING scheduled — a foreground pass killed by a force-quit
                // never runs the deferral path that submits the request, and iOS can discard a request
                // that was submitted. Without this the work would wait for the next offload to defer it
                // or the next launch to drain it. Re-submitting on the way out costs nothing when
                // nothing is owed, because it is skipped entirely.
                if RescoreBackgroundScheduler.isRescoreOwed { RescoreBackgroundScheduler.schedule() }
                // #114: capture the LAST in-app live state on the way out so the Home widget matches what
                // the user just saw — its battery/HR/score otherwise lag to the last FOREGROUND refreshSeq
                // bump. One reload per app-exit is low-frequency and well within WidgetKit's daily budget.
                Task { await WidgetSnapshot.publish(from: model) }
                // #155: refresh the Documents/noop_sync.txt drop file the user's Siri Shortcut logs
                // into Apple Health. Gated inside writeIfEnabled on the opt-in default (OFF) — a
                // no-op until the user turns on Shortcuts Export.
                Task { await ShortcutHealthExport.writeIfEnabled(repo: model.repo) }
            }
        }
    }

    /// Map the running session onto the Lock Screen banner.
    ///
    /// The wording and the numbers come from `LiftSessionController.presentation`, the same
    /// resolution the in-app minimised bar renders, so the two surfaces cannot disagree. The heart
    /// rate is the app's smoothed value, and only while the strap is actually connected — a frozen
    /// last-known bpm on a Lock Screen reads as live and is not. `alert` lights the Lock Screen for this
    /// push — see `LiftLiveActivityController.update`.
    @MainActor
    private func pushLiftActivity(alert: Bool = false) {
        let system = UnitSystem(rawValue: unitSystemRaw) ?? .metric
        guard let p = liftSession.presentation(system: system) else {
            liftActivity.update(state: nil)
            return
        }
        let lightUp = liftActivity.update(
            state: LiftActivityAttributes.ContentState(
                isResting: p.isResting,
                exercise: p.exercise,
                status: p.status,
                detail: p.detail,
                bpm: model.live.connected ? (model.bpm ?? model.live.heartRate) : nil,
                next: p.next,
                stageStartedAt: p.stageStartedAt,
                restEndsAt: p.restEndsAt),
            alert: alert)
        // One line per strap step into NOOP's strap log: whether the Lock Screen was asked to light.
        if let lightUp { model.live.append(log: AppModel.stamped(lightUp.logLine)) }
    }
}

/// iOS root — the `RootTabView` shell with the first-run onboarding/pairing wizard overlaid until
/// complete, the Terms acknowledgment gate over everything until the current version is accepted, and
/// a "What's New" changelog sheet shown automatically after an update.
///
/// This mirrors the macOS `ContentView` (same `@AppStorage` keys, same gate ordering) but swaps the
/// excluded `RootView()` sidebar for `RootTabView()`. The shared `OnboardingWizard`, `TermsGateView`,
/// `WhatsNewView`, `AppChangelog`, and `Terms` symbols all compile into the iOS target unchanged.
private struct iOSRootView: View {
    @AppStorage("noop.onboarded") private var onboarded = false
    @AppStorage("noop.lastSeenChangelogVersion") private var lastSeenChangelog = ""
    @AppStorage("noop.acceptedTermsVersion") private var acceptedTerms = ""
    @State private var showWhatsNew = false
    /// Starts false so a cold-launch external action can't race this view's onAppear decision about the
    /// automatic What's New sheet. It becomes true only when no sheet is due or its dismissal completes.
    @State private var automaticLaunchSheetResolved = false
    /// A restored backup is only usable from a fresh process; see `LiveStoreReplacement`.
    @ObservedObject private var storeRestart = StoreRestartPrompt.shared
    /// A second app pulling this strap's history; see `ForeignOffloadDetector`.
    @ObservedObject private var otherAppWarning = OtherStrapAppWarning.shared

    var body: some View {
        #if DEBUG
        // DEBUG-only: `--demo-screen <name>` renders one screen full-bleed (gates bypassed) so a
        // seeded simulator build can be screenshotted deterministically for verification + marketing.
        // No-op in Release (whole branch is #if DEBUG) and when the arg is absent.
        // First-run setup brings its own navigation stack, as the real launch shows it.
        if DemoScreens.isOnboarding, let demo = DemoScreens.requested { return demo }
        if let demo = DemoScreens.requested {
            // Inherit the root's scheme (`-theme.appearance light|dark` in the launch arguments, see
            // `AppearanceLock.colorScheme`) so demo/marketing shots can be taken in either scheme.
            return AnyView(
                NavigationStack {
                    demo
                        .background(StrandPalette.surfaceBase.ignoresSafeArea())
                        .navigationBarTitleDisplayMode(.inline)
                }
            )
        }
        #endif
        return AnyView(shell)
    }

    private var shell: some View {
        let termsGate = acceptedTerms != Terms.currentVersion && !demoBypass
        let onboardingGate = !onboarded && !demoBypass
        return ZStack {
            RootTabView(homeScreenQuickActionsEnabled:
                demoBypass || (onboarded && acceptedTerms == Terms.currentVersion
                    && automaticLaunchSheetResolved))
                // CR-7: the gates are modal — VoiceOver, Switch Control and Full Keyboard Access must not
                // reach the tabs underneath before the terms are accepted.
                .accessibilityHidden(termsGate || onboardingGate)
            if onboardingGate {
                OnboardingWizard(onFinished: {
                    onboarded = true
                    // A brand-new user just saw the expectations in onboarding — don't also pop the
                    // changelog at them; mark them current.
                    lastSeenChangelog = AppChangelog.currentVersion
                })
                .accessibilityHidden(termsGate)
                .accessibilityAddTraits(.isModal)
                .transition(.opacity)
                .zIndex(1)
            }
            // Terms acknowledgment gate — over EVERYTHING (before onboarding/pairing/Bluetooth) until
            // the current terms version is accepted; re-appears if the terms materially change.
            if termsGate {
                TermsGateView(onAccept: {
                    // Keep any external action behind the gate while the accepted-terms change decides
                    // whether What's New must present next. This write must precede acceptedTerms.
                    automaticLaunchSheetResolved = false
                    acceptedTerms = Terms.currentVersion
                })
                    .accessibilityAddTraits(.isModal)
                    .transition(.opacity)
                    .zIndex(2)
            }
        }
        .animation(.easeInOut(duration: 0.35), value: onboarded)
        .animation(.easeInOut(duration: 0.35), value: acceptedTerms)
        .sheet(isPresented: $showWhatsNew, onDismiss: { automaticLaunchSheetResolved = true }) {
            WhatsNewView(onClose: {
                lastSeenChangelog = AppChangelog.currentVersion
                showWhatsNew = false
            })
        }
        // The Terms gate must stay "over everything" — don't pop What's New on top of it after a
        // combined terms+version update. Gate on terms being current, and re-check when they're
        // accepted (onAppear already fired before acceptance), so What's New shows right after.
        .onAppear {
            showWhatsNewIfDue()
            // Seed the current What's New into the Updates inbox (idempotent per version) so the bell
            // collects it even if the user dismisses the auto sheet.
            UpdateStore.shared.seedWhatsNewIfNeeded()
            // #1659: iOS cannot auto-update a sideloaded build - no API lets an app install or re-sign an
            // .ipa - so the most NOOP can do is NOTICE a release and say so.
            //
            // Gated on the SAME condition as showWhatsNewIfDue above, and the Android hook. This matters
            // now that the check is on by default: without it a brand-new install would reach the network
            // during first run, before the Terms gate the user has not accepted yet. While the default was
            // off, nothing made that visible.
            if onboarded && acceptedTerms == Terms.currentVersion {
                UpdateWatch.runIfDue(currentVersion: UpdateWatch.installedVersion, sideloadHint: true)
            }
        }
        .onChange(of: acceptedTerms) { _, _ in showWhatsNewIfDue() }
        // One button on purpose: every other answer leaves the app running on a store it cannot write,
        // and backgrounding it instead brings the question back on the next return.
        .alert(String(localized: "Reopen reNOOP"), isPresented: $storeRestart.isPresented) {
            Button(String(localized: "Close reNOOP")) { storeRestart.closeApp() }
        } message: {
            Text("The backup is restored. reNOOP has to start again to use it — until then nothing from your strap is saved.")
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            storeRestart.present()
        }
        .alert(String(localized: "Another App Is Syncing Your Strap"), isPresented: $otherAppWarning.isPresented) {
            Button(String(localized: "OK"), role: .cancel) {}
            Button(String(localized: "Don't Show Again")) { otherAppWarning.mute() }
        } message: {
            Text(otherAppWarningMessage)
        }
    }

    private var otherAppWarningMessage: String {
        if let apps = OtherStrapApps.phrase(otherAppWarning.installedNames) {
            return String(localized: "\(apps) also pulls your strap's history. Each hour goes to whichever app syncs first, so the other misses it. Keep one app: turn off Bluetooth for the other in Settings or delete it.")
        }
        return String(localized: "Another app also pulls your strap's history. Each hour goes to whichever app syncs first, so the other misses it. Keep one app: turn off Bluetooth for the other in Settings or delete it.")
    }

    /// DEBUG: launched with --demo-seed, skip the first-run gates (onboarding / terms / What's New) so the
    /// FULL shell with the tab bar renders populated for verification + screenshots. No-op in Release.
    private var demoBypass: Bool {
        #if DEBUG
        return CommandLine.arguments.contains("--demo-seed")
        #else
        return false
        #endif
    }

    private func showWhatsNewIfDue() {
        if demoBypass {
            automaticLaunchSheetResolved = true
            return
        }
        // Existing users who updated: their last-seen version is behind the current one.
        if onboarded && acceptedTerms == Terms.currentVersion
            && lastSeenChangelog != AppChangelog.currentVersion {
            automaticLaunchSheetResolved = false
            showWhatsNew = true
        } else {
            automaticLaunchSheetResolved = true
        }
    }
}

#if DEBUG
/// DEBUG-only: `--demo-running workout|lift|intervals` starts one so the tab bar's mini-player and the Lock Screen
/// banners can be captured; `--demo-running none` ends whatever a previous demo left running.
enum DemoRunning {
    @MainActor
    static func start(model: AppModel, lift: LiftSessionController, intervals: IntervalTimerRunner) {
        let args = CommandLine.arguments
        // `--demo-sync synced|syncing`: the strap-sync status line's states, with no strap.
        if let j = args.firstIndex(of: "--demo-sync"), j + 1 < args.count {
            switch args[j + 1] {
            case "syncing": model.live.backfilling = true
            default: model.live.lastSyncedAt = Date().timeIntervalSince1970 - 20
            }
        }
        guard let i = args.firstIndex(of: "--demo-running"), i + 1 < args.count else { return }
        switch args[i + 1] {
        case "workout":
            if model.activeWorkout == nil { model.startWorkout(sport: "Strength") }
        case "lift":
            if !lift.isActive {
                lift.start(plan: [LiftPlanItem(exercise: "Bench Press", targetSets: 4, restSec: 90,
                                               targetRepsLow: 8, targetRepsHigh: 8, targetWeightKg: 60),
                                  LiftPlanItem(exercise: "Overhead Press", targetSets: 3, restSec: 90,
                                               targetRepsLow: 10, targetRepsHigh: 10, targetWeightKg: 35)],
                           programId: nil, programName: "Push")
                lift.isPresented = false
            }
        case "intervals":
            intervals.start()
        default:
            if model.activeWorkout != nil { model.discardWorkout() }
            if lift.isActive { lift.discard() }
        }
    }
}

/// DEBUG-only: `--demo-activity hr|sync` puts that banner on the Lock Screen with sample content, since the
/// simulator has no strap to start either.
enum DemoActivity {
    @MainActor
    static func start() async {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "--demo-activity"), i + 1 < args.count else { return }
        // iOS starts a banner only for the app on screen; give the launch a moment to become active.
        try? await Task.sleep(for: .seconds(2))
        switch args[i + 1] {
        case "hr":
            _ = try? Activity.request(
                attributes: NOOPActivityAttributes(title: String(localized: "Live HR")),
                content: ActivityContent(state: .init(bpm: 72, recovery: 67, bonded: true, effort: 12),
                                         staleDate: Date().addingTimeInterval(3600)))
        case "sync":
            _ = try? Activity.request(
                attributes: SyncActivityAttributes(title: String(localized: "Strap sync")),
                content: ActivityContent(state: .init(phase: .syncing, chunks: 12,
                                                      startedAt: Date().addingTimeInterval(-42),
                                                      status: String(localized: "Syncing…"),
                                                      detail: nil), staleDate: nil))
        default: break
        }
    }
}

/// DEBUG-only screenshot harness. Maps `--demo-screen <name>` to a single screen so a seeded
/// simulator build can be captured deterministically (verification + marketing). Stripped from Release.
enum DemoScreens {
    static var isOnboarding: Bool {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "--demo-screen"), i + 1 < args.count else { return false }
        return args[i + 1].lowercased() == "onboarding"
    }

    /// The screen named by `--demo-screen <name>`, or nil if the arg is absent/unknown.
    static var requested: AnyView? {
        let args = CommandLine.arguments
        guard let i = args.firstIndex(of: "--demo-screen"), i + 1 < args.count else { return nil }
        switch args[i + 1].lowercased() {
        case "trends":   return AnyView(NavigationStack { TrendsView().tabRouteDestinations() })
        case "trainingload": return AnyView(NavigationStack { TrainingLoadView() })
        // The Sleep tab root (Health-style page).
        case "sleep":    return AnyView(SleepHealthView())
        // The sleep schedule; `--schedule-edit [new]` opens its editor sheet.
        case "schedule": return AnyView(SleepScheduleView())
        case "summary":  return AnyView(SummaryView())
        case "live":     return AnyView(LiveView())
        // Breathe; `--breathe-demo session|summary|stress` opens a session, its summary, or the check-in.
        case "breathe":  return AnyView(BreathingView())
        case "workouts": return AnyView(NavigationStack { WorkoutsHomeView().tabRouteDestinations() })
        case "intervals": return AnyView(NavigationStack { IntervalTimerView() })
        case "liftlog":  return AnyView(NavigationStack { LiftLogView().tabRouteDestinations() })
        // The running gym session (start one from "liftlog" first; it persists across launches).
        case "liftsession": return AnyView(LiftSessionView { })
        case "journal":  return AnyView(NavigationStack { JournalView() })
        case "insights": return AnyView(NavigationStack { InsightsHubView() })
        // Every notice state side by side (the shared NoticeCard, with each screen's real copy).
        case "notices": return AnyView(NoticeGalleryDemo())
        // The sheets and pages reworked in the legacy purge: `--demo-sheet hrv|fullday|scoring|whatsnew|howworks|watch|steps`.
        case "sheet":
            switch args.firstIndex(of: "--demo-sheet").flatMap({ $0 + 1 < args.count ? args[$0 + 1] : nil }) {
            case "hrv":      return AnyView(HRVSnapshotView(onClose: {}))
            case "fullday":  return AnyView(NavigationStack { FullDayChartView() })
            case "scoring":  return AnyView(ScoringGuideView(onClose: {}))
            case "whatsnew": return AnyView(WhatsNewView(onClose: {}))
            case "howworks": return AnyView(HowNoopWorksView(onClose: {}))
            case "watch":    return AnyView(AppleWatchSetupView(onClose: {}))
            default:         return nil
            }
        // First-run setup, optionally on one step: `--onboarding-step 0…4` (1 = the other-strap-apps step).
        case "onboarding":
            let n = args.firstIndex(of: "--onboarding-step").flatMap { $0 + 1 < args.count ? Int(args[$0 + 1]) : nil } ?? 0
            return AnyView(OnboardingWizard(onFinished: {}, startAt: n))
        // Lab Book; `--labbook-add` opens the Add Reading sheet over it.
        case "labbook":
            return AnyView(NavigationStack { LabBookView() }
                .sheet(isPresented: .constant(args.contains("--labbook-add"))) { MarkerEditorView { _ in } })
        case "explore":  return AnyView(NavigationStack { AllMetricsView().tabRouteDestinations() })
        // One metric's page: `--demo-screen metric --demo-metric hrv` (a catalog key; defaults to HRV).
        case "metric":
            let key = args.firstIndex(of: "--demo-metric").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil } ?? "hrv"
            return MetricCatalog.all.first { $0.key == key }.map { AnyView(MetricDetailView(metric: $0)) }
        // Settings, optionally opened on one page: `--demo-screen settings --settings-page general`.
        case "settings":
            let page = args.firstIndex(of: "--settings-page").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil }
            return AnyView(SettingsDemoHost(pageName: page))
        case "profile": return AnyView(ProfileSheet(onClose: {}))
        case "devices":  return AnyView(NavigationStack { DevicesView().settingsDestinations() })
        case "devicescatalog": return AnyView(NavigationStack { DeviceCardCatalog() })
        case "addwizard": return AnyView(AddWizardDemoHost())
        // Coach: `--coach-demo chat|empty|typing|error|draft|settings|setup` (see `CoachDemoHost`).
        case "coach":
            let mode = args.firstIndex(of: "--coach-demo").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil } ?? "chat"
            return AnyView(CoachDemoHost(mode: mode))
        default:         return nil
        }
    }
}
#endif
#endif

#if DEBUG
/// DEBUG-only: Settings in its own stack, with one page already pushed so its back button shows.
private struct SettingsDemoHost: View {
    @State private var path: [SettingsPage]

    init(pageName: String?) {
        let pages: [String: SettingsPage] = [
            "profile": .profile, "zones": .heartRateZones, "general": .general, "units": .units,
            "notifications": .notifications, "shortcuts": .shortcuts,
            "workouts": .workouts, "scores": .scores, "devices": .devices, "applehealth": .appleHealth,
            "import": .dataSources, "backup": .backup, "about": .about, "developer": .developer,
        ]
        _path = State(initialValue: pageName.flatMap { pages[$0] }.map { [$0] } ?? [])
    }

    var body: some View {
        NavigationStack(path: $path) {
            SettingsView().settingsDestinations()
        }
    }
}

/// DEBUG-only: Coach pushed onto a stack (so its back button shows), on a local-server connection (no
/// key, no network). `--coach-demo chat|empty|typing|error|draft|settings|setup`: a seeded conversation;
/// an empty one; the reply on its way; an undelivered question with a rejected key; text in the field;
/// the settings sheet over the chat; or no provider.
private struct CoachDemoHost: View {
    @EnvironmentObject var coach: AICoachEngine
    @EnvironmentObject var repo: Repository
    let mode: String
    @State private var ready = false

    var body: some View {
        NavigationStack {
            Color.clear
                .navigationDestination(isPresented: .constant(ready)) { CoachView() }
        }
        .sheet(isPresented: .constant(ready && mode == "settings")) {
            CoachSettingsView().environmentObject(coach).environmentObject(repo)
        }
        .onAppear {
            UserDefaults.standard.set(mode == "draft" ? "А если сегодня бегать?" : "", forKey: "coach.composerDraft")
            if mode == "setup" {
                coach.customConnected = false
                coach.provider = .openAI
                ready = true
                return
            }
            coach.provider = .custom
            coach.customBaseURL = "http://localhost:11434/v1"
            coach.customConnected = true
            coach.model = "llama3.1"
            var messages = [
                ChatMessage(role: .user, text: "Как я восстановился после вчерашней тренировки?"),
                ChatMessage(role: .assistant, text: "Заряд сегодня **74 %** — выше вашей нормы. ВСР 68 мс, пульс покоя 52 уд/мин.\n\n- Можно тренироваться в полную силу\n- Лягте до 23:30, чтобы закрепить результат"),
                ChatMessage(role: .user, text: "А какую тренировку выбрать?"),
                ChatMessage(role: .assistant, text: "Интервалы на 40–45 минут в зоне 3–4 и 10 минут заминки."),
            ]
            switch mode {
            case "empty":
                messages = []
                coach.dataConsent = false
            case "typing", "error":
                messages.append(ChatMessage(role: .user, text: "Сколько мне сегодня спать?"))
            default:
                break
            }
            // Arrival times for the stamps: the conversation began at 09:41 today.
            let start = Calendar.current.date(bySettingHour: 9, minute: 41, second: 0, of: Date()) ?? Date()
            var times: [UUID: Date] = [:]
            for (i, m) in messages.enumerated() { times[m.id] = start.addingTimeInterval(Double(i) * 50) }
            CoachMessageTimes.save(times)
            coach.messages = messages
            if mode == "typing" { coach.sending = true }
            if mode == "error" {
                coach.errorText = AICoachError.badKey.errorDescription
                coach.keyRejected = true
            }
            ready = true
        }
    }
}

/// DEBUG-only: the notices the app shows, drawn through the shared `NoticeCard` with their real copy.
private struct NoticeGalleryDemo: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                NoticeCard(title: Text("Syncing history from the strap…"), message: Text("\(12) chunks so far"), tone: .progress)
                NoticeCard(title: Text("Sleep hasn't synced"), message: Text("Keep the strap nearby and sync again."),
                           systemImage: "exclamationmark.arrow.triangle.2.circlepath", tone: .error)
                // The signals as AppModel renders them for HealthAlertCopy: localized names, each with its sign.
                NoticeCard(title: Text("Signs of strain"),
                           message: Text(verbatim: [String(localized: "Resting HR +\(6)"),
                                                    String(localized: "HRV −\(18)%")].joined(separator: ", ")),
                           systemImage: "exclamationmark.triangle.fill", tone: .warning)
                NoticeCard(title: Text("Strap not connected"), systemImage: "antenna.radiowaves.left.and.right.slash",
                           tone: .info, actionTitle: "Open Devices", action: {})
                NoticeCard(title: Text("Strap pairing was reset"), message: Text("Re-pair it to reconnect."),
                           systemImage: "exclamationmark.triangle.fill", tone: .warning, actionTitle: "How to Fix", action: {})
                NoticeCard(title: Text(verbatim: "Сон удалён"), systemImage: "trash.fill", tone: .info,
                           actionTitle: "Undo", action: {}, onDismiss: {})
            }
            .padding(16)
        }
        .background(StrandPalette.summaryCanvas.ignoresSafeArea())
        .navigationTitle(Text(verbatim: "Notices"))
    }
}

/// DEBUG-only host so `--demo-screen addwizard` shows the Add Device sheet over the Devices list, as it
/// opens for real. `--wizard-type whoop4|whoop5|strap|gym|oura` and `--wizard-step prep|pick|confirm`
/// deep-link one step so it can be screenshotted without hardware.
private struct AddWizardDemoHost: View {
    @EnvironmentObject var live: LiveState

    private var startAt: (type: AddDeviceWizard.DeviceType, step: AddDeviceWizard.Step)? {
        let args = ProcessInfo.processInfo.arguments
        func arg(_ name: String) -> String? {
            args.firstIndex(of: name).flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil }
        }
        let types: [String: AddDeviceWizard.DeviceType] = [
            "whoop4": .whoop4, "whoop5": .whoop5mg, "strap": .hrStrap, "gym": .gymEquipment, "oura": .oura,
        ]
        let steps: [String: AddDeviceWizard.Step] = ["prep": .prep, "pick": .pick, "confirm": .confirm]
        guard let t = arg("--wizard-type").flatMap({ types[$0] }) else { return nil }
        return (t, arg("--wizard-step").flatMap { steps[$0] } ?? .prep)
    }

    var body: some View {
        NavigationStack { DevicesView().settingsDestinations() }
            .sheet(isPresented: .constant(true)) {
                AddDeviceWizard(live: live, onClose: {}, startAt: startAt)
            }
    }
}
#endif
