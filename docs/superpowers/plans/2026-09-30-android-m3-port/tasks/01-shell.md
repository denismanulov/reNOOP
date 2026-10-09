# Task 01 — Shell: four tabs, Browse, Settings from the avatar, removals

Read `SCRATCH/HANDBOOK.md` first and follow it exactly. Then `SCRATCH/ios-anatomy.md` §Shell, §7 Browse,
and the iOS files `StrandiOS/App/RootTabView.swift`, `StrandiOS/App/BrowseView.swift`, `Strand/App/TabRoute.swift`.
Mockups: `Main.dc.html` (navigation bar), `04-Browse.dc.html`.

## Goal
Replace the Android shell (`android/app/src/main/java/com/noop/ui/AppRoot.kt`: floating `GlassBottomBar`
Today · Trends · Sleep · Coach · More + More page) with Denis's structure in Material 3:

1. **Material 3 `NavigationBar`**, 4 destinations, in this order: Сводка/Summary (icon: Favorite filled when
   selected, outlined otherwise), Сон/Sleep (Bedtime), Тренировки/Workouts (DirectionsRun), Обзор/Browse (Search).
   Labels from string resources (add `nav_summary`, `nav_browse` etc. via the helper; iOS keys "Summary",
   "Sleep", "Workouts", "Browse"). Standard M3 bar colours (surfaceContainer, secondaryContainer indicator).
   - Each tab keeps its own back stack (Navigation Compose `saveState/restoreState`, `launchSingleTop`,
     `popUpTo(start)` — the existing `navigateTopLevel` pattern). Re-selecting the active tab pops it to its
     root; if already at root, it scrolls the root to top (expose a per-tab scroll-to-top signal, e.g. a
     `CompositionLocal` counter the tab roots can observe; wire it for the roots that have a LazyColumn/scroll
     state you can reach cheaply, leave a TODO-free no-op for the others).
   - The bar shows on tab roots AND pushed screens (as iOS keeps its tab bar), but hides for full-screen
     recording screens (live workout, intervals run, gym session, breathing session) if they are routes.
   - Remove the glass-bar machinery that only served the old bar (opacity/scale/overlay/auto-hide) IF nothing
     else needs it; its Settings controls go too (Denis: no copies of system settings). Keep
     `BottomBarStyleStore.coachEnabled` (or move that flag somewhere sensible) — it is the AI Coach master
     switch (`noop.coachEnabled`).
2. **Tab roots**: Summary = the existing `TodayScreen` for now (a later task rebuilds it) with its settings
   entry kept; Sleep = existing `SleepScreen`; Workouts = existing `WorkoutsScreen`; Browse = NEW `BrowseScreen`.
3. **Settings** is no longer a tab/More item: it opens (pushed, not a sheet) from the Summary's profile/avatar
   button. `TodayScreen` already has `onOpenSettings`; make sure it pushes within the Summary tab.
4. **BrowseScreen** (new file `ui/BrowseScreen.kt`), M3 per mockup 04:
   - M3 search bar at the top (docked, full width, placeholder "Search reNOOP"/"Поиск в reNOOP", leading search
     icon). No avatar in it unless trivial. Typing filters: first matching SCREENS (the rows below, by localized
     title, case/diacritic-insensitive), then matching METRICS from the Android metric catalogue (the
     descriptor list in `ui/TrendsExploreScreen.kt` — `Explore`'s catalogue) with their category tint; a
     metric result opens that metric's detail (whatever the Explore screen opens today for it). No hits →
     `EmptyState` "No Results for “…”".
   - Without a query: `SectionHeader("Categories")` + `ListGroup` rows (leading icon tinted with the category
     hue, trailing chevron), sorted alphabetically by the localized title: All Metrics (→ existing Explore route
     for now), AI Coach (only when the Coach switch is on; → Coach), Journal (→ existing Insights/journal
     route), Lab Results (→ LabBook), Trends (→ Trends), What Moves You (→ InsightsHub). Then a second
     `ListGroup` without header: Devices, Heart Rate (→ Live), Mindfulness (→ Breathe). Titles use Denis's
     terms (iOS keys "All Metrics", "Coach", "Journal", "Lab Results", "Trends", "What Moves You", "Devices",
     "Heart Rate", "Mindfulness").
   - Rows push inside the Browse tab. Router requests elsewhere in the app (widgets, notifications, quick
     actions, deep links in `MainActivity`/`AppRoot`) that used to open these screens must now select the
     right tab and push there (Coach/Journal/LabBook/InsightsHub/Devices/Live/Breathe → Browse tab; Trends →
     Summary tab; Workouts/active workout → Workouts tab; Today/stress → Summary tab).
5. **Removals** (Denis deleted these on iOS; delete the Android screens, their routes, `Destination` entries,
   More-page wiring, dead helpers and tests that only pin them — grep every reference; keep Room/DAO/migrations):
   More page (+ `MoreSectionPrefs`, drawer groups), Intelligence (`IntelligenceScreen`), Health screen
   (`HealthScreen` — stale routes land on All Metrics/Explore; KEEP any logic file other screens still use, e.g.
   `HealthVital*Logic.kt`, `HealthLiveHrLogic.kt` if referenced), Compare (`CompareScreen`), Rhythm
   (`RhythmScreen`, `RhythmRoute`), Your Data Fused (`FusedRecordScreen`, `FusedRecordRoute`,
   `FusionDayAdapter` only if unused), Coupled view (`CoupledScreen`), Live Sessions (`LiveSessionScreen`,
   `LiveSessionRunner` + its entry points; keep the DB tables). Check `VitalSigns`/`VitalSignsDetail`: if they
   are only reachable from the Health screen, remove them too; if Today/Sleep link to them, keep them for now
   and say so. The Coach bottom-bar tab goes (Coach now lives in Browse). Also check the Android-only extra
   screens that More exposed (Automations, SmartAlarm/Alarms, Notifications, DataSources, BackupSync,
   NoopLimitations, PowerSaving, AppleHealth/Health Connect, TestCentre): they must stay reachable — from
   Settings (as they are today) or from where Denis put them (Alarms → Sleep tab "Sleep Schedule" row, later
   task; for now keep whatever entry exists and list anything that became unreachable).
6. Quick actions / launcher shortcuts (`QuickAction` list in AppRoot, `res/xml/shortcuts*.xml` if any) keep
   working: Live HR → Browse/Heart Rate, Start workout → Workouts tab, Log journal → Browse/Journal, Breathe →
   Browse/Mindfulness.

## Done means
- Demo APK builds; screenshots (light + dark) of: each tab root, Browse with and without a search query, a
  pushed screen from Browse showing the nav bar still present, Settings opened from the Summary avatar.
- Unit tests pass except the known list (or you deleted/rewrote the ones pinning removed screens).
- Two or three commits: (1) shell + Browse, (2) removals, (3) any follow-up fixes.
