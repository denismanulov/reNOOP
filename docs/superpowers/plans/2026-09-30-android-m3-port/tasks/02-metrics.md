# Task 02 — Metric page, All Metrics, Trends

Read `SCRATCH/HANDBOOK.md` first. Then `SCRATCH/ios-anatomy.md` §2 Metric page, §3 All Metrics, §4 Trends.
iOS sources: `Strand/MetricHealth/*` (MetricDetailView, MetricHealthChart, MetricHealthSeries, MetricHealthStyle,
MetricDataPages, MetricReadings, MetricSeriesLoader, MetricStressDay, AllMetricsView), `Strand/Trends/*`
(TrendsView, HealthTrendCard, HealthTrendDetector, TrendsReportView, TrainingLoadView). Denis commits:
dcf2e920, 04104ee0 (plan doc `docs/superpowers/plans/2026-09-26-explore-redesign.md`), cb91b323, e0255b7a,
0847977f, 309e5a6c, 4133912f (M-*), a645ef78. Mockup: `03-Metric.dc.html`.

## Android today
`ui/TrendsExploreScreen.kt` (Explore: metric catalogue + per-metric screen), `ui/TrendsScreen.kt`,
`ui/TrendsReport.kt` (PDF), `ui/TrainingLoadCard.kt`, `ui/StressScreen.kt`, `ui/FullDayChartScreen.kt`,
`ui/Charts.kt`, `ui/HealthVitalDetailLogic.kt` etc. Find the existing per-metric detail/series code and reuse its
data loading (series per range, source resolution, units/formatting). The Explore catalogue (descriptor list)
is the Android twin of iOS `MetricCatalog`.

## Build
1. **Metric detail page** (new `ui/metric/MetricDetailScreen.kt` or similar), exactly the iOS anatomy §2 in M3:
   small top app bar with back + metric name; `PeriodSegmented` W/M/6M/Y (localized, TalkBack reads
   "Week"/"Month"/"6 months"/"Year"); chart card (caption AVERAGE/TOTAL…, big figure, ⓘ opening the About
   text in a dialog or bottom sheet, date span, 240 dp chart drawn with Compose Canvas following the per-metric
   mark rules — bars/rings/line/avg dashed line/banded Charge/± skin temp/0–3 stress, Y labels right, press-
   and-drag selection that dims the rest and shows the held value, TalkBack description of the marks);
   "Latest: …" row; empty state with "Import History" → Data Sources; Stress page's "Today" hourly card
   (fold the useful part of `StressScreen` in here and delete `StressScreen` + its route if nothing else needs
   it — Denis folded Stress into the metric page); "Highlights" two-week-average card; "Options" group:
   Show All Data (→ new All Data page: value, source, time rows), Full Day by the Second (avg HR only → the
   existing `FullDayChartScreen`), Pin/Unpin in Summary (writes the Android KeyMetric prefs, only for pinnable
   non-ring metrics), Data Sources (→ existing data-source/provenance view for that metric, or the Data Sources
   screen). Metric colours from `Health.colors` by the iOS metric→hue mapping (put that mapping in one pure
   function with a unit test).
2. **All Metrics** (new screen): large title, always-visible search, one section per Health category with data
   (alphabetical by localized category name: Activity, Body Measurements, Heart, Mental Wellbeing, Nutrition,
   Respiratory, Sleep — add Charge/Effort/Rest where iOS puts them), cards like the Summary pinned card
   (category icon + metric name in hue, stamp Today/Yesterday/date, big latest value + unit, 7-day mini chart),
   ONE card per metric key with the source priority rule (freshest; tie: WHOOP > Health Connect > others) as a
   pure function + test; "Metrics Without Data (N)" expander; search "No Results". Load non-empty metrics first,
   in parallel, off the main thread. Replace the old Explore route: every link that opened Explore/`explore`
   (Browse "All Metrics", Summary "Show All Metrics", stale Health routes) now opens All Metrics; every link
   that opened a metric (Today tiles, Browse search results, Sleep vitals…) opens the new detail page. Delete
   the old Explore UI (`TrendsExploreScreen` UI parts) but keep/move any helpers other code uses.
3. **Trends** (rebuild `TrendsScreen`): large title "Trends", overflow/share menu "Trends Report (PDF)" with the
   five ranges (reuse `TrendsReport.kt`), pull to refresh, trend cards per iOS `HealthTrendCard`, detection per
   iOS `HealthTrendDetector` — port it as a pure Kotlin object with the SAME thresholds/windows and a unit test
   whose expected outputs you derive by compiling the Swift detector standalone (`swiftc` over a small
   `main.swift` feeding the same fixtures) — the parity oracle rule in AGENTS.md. Empty states "Not Enough Data
   Yet" / "No Trends". After the cards a "Training Load" card → a Training Load page (rebuild the existing
   `TrainingLoadCard` content as a page in M3). Expose a small API the Summary task will call to get "up to 3
   trends" (e.g. `HealthTrends.top(repo, limit = 3)`), since the Summary shows the first three.
4. Remove what Denis removed here: the old Trends/Explore screens' UI, dead helpers, their tests (rewrite tests
   that pinned behaviour still present).

## Done means
Screenshots (light + dark) of: metric page for HRV, Steps, Charge, Stress, Skin temp; All Metrics with and
without search; Trends; Training Load. Unit tests green (+ new tests). Commits per step.
