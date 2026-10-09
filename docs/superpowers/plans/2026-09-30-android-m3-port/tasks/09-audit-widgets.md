# Task 09 — Audit sweep (Critical items), widgets, notifications, i18n, dead code

Read `SCRATCH/HANDBOOK.md` first. Then Denis's audit `docs/ux-audit-2026-09.md` (Russian): the Critical items
CR-1…CR-12, sections 16–18, "Craft notes", and the fix plan at the end. For each item decide the Android twin
and fix it on Android where it applies (many were fixed by Tasks 01–08 already — verify, don't redo).
Denis commits: 3680748a, 1a85ade7 (Dynamic Type), f70a026f (contrast), a645ef78 (charts speak), 6995ed84,
0af70563, 6ab14bbb (44 pt targets, undo timeouts), 5b06c5dd, b10fdcc0, c375b167, ba8f53d7, 141afac2, e40aa956,
cbb3ec3c, fc4a478e, 431f4cf2, 38f0a2ba, 6211dd86, 4d48f5f7, e2c164fb (one term per concept), ab4f7b0a (every key
in all languages), 1845cda3, 40c113b0, 92eed399, 5448e31a, 4053fcda, 16c2915e.

## Android twins to check and fix
- CR-1 font scaling: every rebuilt screen uses `MaterialTheme.typography` (sp) and layouts that reflow at 200 %
  font scale (no fixed heights that clip text; test `adb shell settings put system font_scale 2.0` on the
  emulator, restore to 1.0 after). CR-2 contrast ≥ 4.5:1 for text (check data-hue text on surfaces; Health
  colours were chosen for it — verify the worst cases). CR-3 charts have TalkBack descriptions. CR-5 labels for
  icon-only buttons. CR-6 48 dp targets; snackbars with actions don't time out silently (use
  `SnackbarDuration.Indefinite` or long + explicit dismiss for undo). CR-7 gates modal. CR-8 Calm. CR-9 widget
  text ≥ 12 sp at 4.5:1. CR-10 Coach. CR-11 locale numbers everywhere a number is typed. CR-12 dictation.
- **Widgets** (`com.noop.widget`, Glance or RemoteViews): restyle to Material You (dynamic colour, system
  background, rounded corners), localized ring captions (CR-9, WG-5), deep links to Summary / Heart Rate /
  Stress metric page on the Summary stack.
- **Notifications** (`com.noop.notif`, alarm, coach brief, live workout ongoing notification): Material 3
  notification styles; the live workout's ongoing notification as the Android twin of Denis's Live Activity
  (sport, clock, HR, pause/resume + finish actions) — only if the existing one lacks it.
- **Terminology**: one Russian/English term per concept across the app (e.g. Charge/Заряд, Effort/Усилие,
  Rest/Отдых, Coach/ИИ-тренер) — align Android strings with Denis's catalogue (`addstr.py lookup`), fix
  inconsistent Russian especially.
- **i18n completeness**: every key in values/strings.xml present in all 8 locale files (write a quick check;
  fix gaps with real translations).
- **Dead code**: grep for screens/composables/prefs no longer referenced after Tasks 01–08 and delete them (like
  Denis's "chore: delete … nothing calls any more" commits); keep data/analytics/BLE code.
- Run the full unit test suite; fix what the port broke.

## Done means
A short table in your report: audit item → Android status (fixed here / already fixed by task N / n/a because
…). Screenshots at font scale 2.0 of Summary, Sleep, a metric page, Settings; widgets on the home screen.
Commits per theme (a11y, widgets, notifications, i18n, dead code).

## Notes from earlier tasks
- Two readouts disagree between 00:00 and 04:00: the Summary stamps a value "Today" (logical day, 04:00 roll)
  while the metric page says "Latest: Yesterday" (calendar day). Resolve both from one funnel (AGENTS.md).
- Unused `l10n_today_screen_*` / `today_*` strings remain; delete unused strings in all locales.
- Pure helpers kept only because tests pin them (TodayScoring/Provenance/MetricsLogic parts, TodayDayNav,
  TodayLayoutPrefs, DashboardCards, HeaderBatteryDisplay, RollingStepsAverage, AutoWorkoutPrefs, RhythmScreener,
  some Units helpers, WhoopRepository.allSleepSessionsUnion): delete helper + test when nothing else uses them.
- Dark-theme pass: Tasks 04+ capture light screenshots only; do the dark check for every rebuilt screen here.
