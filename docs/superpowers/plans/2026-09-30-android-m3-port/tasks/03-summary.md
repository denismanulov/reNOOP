# Task 03 — Summary tab (layout A = Denis, layout B = compact), replacing Today

Read `SCRATCH/HANDBOOK.md` first. Then `SCRATCH/ios-anatomy.md` §1 Summary, and iOS `Strand/Summary/*`
(SummaryView, SummaryLoader, SummaryCards, SummaryFitnessCards, SummarySleepCard, SummaryHighlights,
SummaryMetricReading, ReadinessCopy, DayScoreReadings, ActivityRingsView, StrapBatteryDisplay,
ActiveWorkoutIndicator), spec `docs/superpowers/specs/2026-09-26-summary-home-design.md`. Denis commits:
8e38c1b6, 63984147, d35fa5b8, 4d48f5f7, 51ea6e5e, 4133912f (S-*), a4695dd4 (fitness tiles), 10c307e7.
Mockups: `Main.dc.html` (layout A) and `02-Summary-B.dc.html` (layout B).

## The user's decision
Two layouts, user-selectable: **A "Detailed" (Denis's structure 1:1)** and **B "Compact" (Fitbit-like: three
separate score dials, a highlight banner, a 2-column grid of pinned tiles, trends as a horizontal carousel)**.
Default A. The choice lives in (1) the Pinned "Edit" sheet (a segmented control at its top) and (2) Settings >
General "Summary layout" (the Settings task will move/keep it; add a pref accessor it can call). Store it as a
String pref `noop.summaryLayout` = "detailed" | "compact" (SharedPreferences, same store the app uses).
Both layouts show the same data and the same destinations; only arrangement differs.

## Android today
`ui/TodayScreen.kt` (8 k lines) + `TodayScoring.kt`, `TodayMetricsLogic.kt`, `TodayProvenance.kt`,
`TodayDayNav.kt`, `TodayLayoutPrefs.kt`, `KeyMetricPrefs.kt`, `DashboardCards.kt`, `HostedCards.kt`,
`SleepHeroLogic.kt`, `WeeklyDigestCard.kt`… The data Denis's Summary needs (scores with the Charge carried /
calibrating caption, effort scale 21/100, pinned KeyMetric readings + 7-day series, sleep of the night,
readiness highlights, trends, strap battery, sync state, day navigation bounds at the 04:00 logical-day roll,
pull-to-sync) is already computed for Today somewhere — find it and reuse it (extract pure helpers where they
are private to TodayScreen). Readiness highlights: Android twin of iOS `ReadinessEngine` signals if it exists
(grep `Readiness`); copy per iOS `ReadinessCopy.swift` (strings via helper). Trends: Task 02 exposes a top-3 API.

## Build `ui/summary/SummaryScreen.kt` (+ small files)
Layout A exactly per iOS anatomy §1 in M3: large title "Summary" with the profile avatar (photo/initials/
person icon — reuse `ProfileAvatar.kt`) → Settings; health alert NoticeCard when raised; day pager ‹ Today ›
with the title opening an M3 DatePicker dialog within bounds; rings card (`ActivityRings` + three figures,
Charge caption; taps: Charge/Effort → metric page, Rest → Sleep tab on that night, rings → Charge); fitness
tiles (2, default Steps + Calories, each changeable via long-press menu "Change Card" listing Key Metrics
except rings; persist; `WeekColumnsChart`); "Pinned" + "Edit" (customisation sheet — rebuild the existing
Today customisation as an M3 ModalBottomSheet: reorder/toggle pinned metrics + the layout segmented control);
Sleep card (if slept) → Sleep tab night; pinned metric cards (`HealthCard` + `CardTitleRow` + `ValueWithUnit` +
Mini chart) → metric page; empty "Pin metrics you care about"; row "Show All Metrics" → All Metrics; "Trends"
(≤3 cards + "Show All Trends"); "Highlights" (≤3, bad→watch→good, two capsule-bar figures); `SyncFooter`
("Updated just now"/"Updated 5 minutes ago"/"Syncing…"); pull-to-refresh = strap sync + reload (existing
Today logic). Default pinned list per iOS (HRV, Resting HR, Blood Oxygen, Respiratory, Weight after rings and
tiles) — only change Android's default if its KeyMetric prefs have no stored value yet; never rewrite stored
user choices.
Layout B per mockup 02 with the same data: top row title "Today"/date + calendar + avatar; three `ProgressRing`
dials (Charge %, Effort on the user's scale, Rest %) with icon + value inside and label below; the first
highlight as a primaryContainer banner (if any); "Pinned" 2-column grid of square tiles (sleep, fitness tiles,
pinned metrics — value, mini chart or goal bar); "All Metrics" outlined button; Trends as a horizontal row of
cards; SyncFooter.
Make Summary the Summary tab root (replace TodayScreen in AppRoot), keep router/deep-link entry points
(widgets "today", stress → Stress metric page on the Summary stack, quick actions). Then delete the old Today
UI and everything only it used (Liquid*/sky/background/hosted cards/greeting/readiness pills/live HR card/
journal card/coach launcher/hydration card/weekly digest — check each: if another screen still uses it, keep
it), keeping pure helpers other code or tests use; delete/rewrite tests that pinned removed UI (including the
known-failing `TodayWorkoutTapTest`, `StressPersonalBaselineSurfaceTest` if they only pin removed Today code).
Denis removed the "+" button and the quick-actions sheet from the Summary.

## Done means
Screenshots (light + dark) of layout A top and scrolled, layout B, the Edit sheet, the date picker, a past day.
Unit tests for new pure logic (pinned list resolution, highlight ordering/cap, ring fractions, stamp text,
layout pref round-trip). Commits: (1) SummaryScreen A + wiring, (2) layout B + setting, (3) Today removal.

## Notes from Task 02 (commits 3f6e4e2b, 097122d1) — use these
- Open a metric page with `metricRoute(key, source)` (route `metric/{key}?source=`; no source = freshest).
  All Metrics route exists (`ui/metric/AllMetricsScreen.kt`); `metricHue(key, category)` gives the iOS hue.
  `MetricCatalog` (Kotlin twin of iOS) and `MetricSeriesLoader` load series; reuse them for pinned cards'
  7-day charts and stamps instead of re-deriving.
- Trends for the Summary: `HealthTrends.top(vm, context, limit = 3)` (ui/trends); trend card composable exists
  in `ui/trends/TrendsScreen.kt` — reuse it.
- `ui/m3` gained `PushedTopBar` (small top app bar with back) — use it on pushed pages.
- Legacy Today still uses `StressModel.kt` (StressModel, StressTodayCard), `TrendHostCards.kt` and the legacy
  metric keys (`metricRouteForLegacyKey`). When you retire Today, retire those too if nothing else uses them.
- The emulator swaps heavily: stop the Gradle daemon (`./gradlew --stop`) before a long emulator session, and
  prefer `adb shell input text` in short chunks when typing.
