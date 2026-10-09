# Task 04 — Sleep tab, Vitals, More Sleep Data, Sleep Schedule (alarms)

Read `SCRATCH/HANDBOOK.md` first. Then `SCRATCH/ios-anatomy.md` §5 Sleep, and iOS `Strand/SleepHealth/*`,
`Strand/SleepSchedule/*`. Denis commits: 8e38c1b6 (sleep part), 9522ee86 (stages chart 1:1), bdab8586 (Sleep
tab = Sleep Score page), 4b2fa10a (Vitals rows open their metric; outlier ring inside tile), 4dfc2b6f (Alarms →
Full Schedule opened from the Sleep tab), b9b96897 (phone backup = system alarm), 4133912f (SL-3…SL-6, AL-2,
AL-3), 6995ed84 (a11y dial). Mockups: `05-Sleep.dc.html`, `08-Schedule.dc.html`.

## Android today
`ui/SleepScreen.kt` + Sleep*Logic/Ui files (`SleepHeroLogic`, `SleepModels`, `SleepModelLogic`,
`SleepStageTimelineLogic`, `SleepStageBreakdownUi`, `SleepMetricCardsUi`, `SleepMetricDetailSheet`,
`SleepNightNavUi`, `SleepNightSelection`, `SleepTimeEditDraft`, `SleepTimeLabels`, `SleepArrangeSheet`,
`SleepLayoutPrefs`, `SleepSectionReorder`, `SleepFormatting`), `ui/SmartAlarmScreen.kt` (+ `alarm/`),
`BodyClockDialCard.kt`. Reuse the data/logic (night selection, stage segments, score parts if Android computes
them, time-edit/nap/delete/undo, strap alarm arm/cancel with its safety gating, wind-down reminder, phone
alarm). Do not change sleep analytics or the BLE alarm commands; if Android lacks a number iOS shows (e.g.
sleep-score parts), check `com.noop.analytics` for the twin of iOS `AnalyticsEngine` sleep score weights
(Duration 50 / Interruptions 20 / Deep & REM 20 / Regularity 10) before concluding it is missing.

## Build
1. **Sleep tab root** per iOS §5 in M3 + mockup 05: large title "Sleep" (the iOS inline title is "Sleep Score";
   on Android keep the tab name as the large title and put "Sleep Score" on the score card), overflow menu (Edit
   sleep times, Add a nap / Naps submenu, Log going to sleep, Log waking up), a VISIBLE night picker row
   (‹ date chip ›, chip opens a DatePicker; swipe still works) — user-approved deviation; undo NoticeCard after
   delete; freshness NoticeCard states (exact iOS copy); Sleep Score card (4-part segmented ring drawn in Canvas
   with `Health.colors.score*`, word Poor/Fair/Good/Optimal, legend "Duration: 42 of 50"…, one sentence naming
   the biggest loss; imported-WHOOP variant) → Rest metric page; two tiles Sleep (compact stages + duration) →
   More Sleep Data, Vitals (learning capsules / band chart + verdict) → Vitals page; empty "No sleep data";
   Highlights (Bedtime, Duration, Sleep: Stages cards) + "Show All" → Sleep Highlights page; Options "Sleep
   Schedule" → Sleep Schedule. Time editor as M3 bottom sheet/dialog with date+time pickers, the no-data warning,
   Delete with confirmation, Move confirmation; nap variant.
2. **Stages chart** component (reusable, `ui/m3` or `ui/sleep`): rows Awake/REM/Core/Deep with names top-left,
   hairline row rules, 4 equal whole-hour x sections labelled in the locale's clock, rounded blocks, stage-to-
   stage connectors, compact mode, per-stage highlight dimming, TalkBack summary (stage durations).
3. **Vitals page** per §5 (verdict + date, learning card, 5-column zone chart with rings, rows → metric pages).
4. **More Sleep Data** page per §5: D/W/M/6M, header figures, D = stages chart, W/M/6M floating bedtime→wake bars,
   Stages | Amounts | Comparisons with the Sleep Debt card and overlays.
5. **Sleep Schedule** (Google Clock Bedtime look, mockup 08) replacing the Alarms screen: strap alarm switch +
   footnote, warnings, schedule card(s) with the 24 h dial (drag ends, 5-min snap, 5–11 h span, TalkBack
   adjustable per end), bedtime/wake times opening M3 TimePicker, day circles Monday-first (locale), "Next wake
   up" card with countdown, Bedtime Reminder + Wind Down, "Check what the strap has stored" (not on 5/MG), phone
   backup alarm = a real system alarm (AlarmManager `setAlarmClock` or the existing phone-alarm path) scheduled
   when the strap alarm is on — keep whatever safety/guarantee logic the existing SmartAlarm code has; one
   gated funnel for "will this alarm actually fire" (AGENTS.md "Two readouts of one fact").
   Route: Sleep tab Options row; remove Alarms from Settings/More/automation lists if it was there.
6. Delete the old Sleep UI files that nothing uses anymore, keep logic helpers; update/delete tests accordingly.

## Done means
Screenshots light + dark: Sleep root (top + scrolled), time editor, Vitals, More Sleep Data D and W and M,
Sleep Schedule + editor. Unit tests for new pure logic (score word thresholds, biggest-loss sentence choice,
vitals verdict, schedule countdown text). Commits per step.

## Notes from earlier tasks
- The Summary (commit 052c956e) opens the Sleep tab on a given night through `SleepNightRequest.wakeDay`; the
  rebuilt Sleep tab must keep consuming it (Summary sleep card / Rest ring → that night).
- Use `metricRoute(key, source)` (ui/metric) for every vital/metric link and `PushedTopBar` (ui/m3) on pushed
  pages. Demo nights carry only stage TOTALS (no timestamped segments) — the stages chart must degrade
  gracefully (show totals/durations, no fake timeline).
