# Task 05 — Workouts tab, history, detail, live recording, mini-player, gym session, intervals, manual add

Read `SCRATCH/HANDBOOK.md` first. Then `SCRATCH/ios-anatomy.md` §6 Workouts, iOS `Strand/Fitness/*`. Denis
commits: 1e8fcf66, e2d82d46, c08dc4a2, 2f1436ff, 0118b14c, d0016d98, 960d6356, 54dbb5a5, 16c2915e (mini-player),
7a9ae81e (K-4, W-4…W-14), 141afac2 (locale-aware numbers, CR-11), a372fb21 (undo/confirm destructive).
Mockups: `06-Workouts.dc.html`, `07-Recording.dc.html`.

## Android today
`ui/WorkoutsScreen.kt`, `WorkoutStart.kt`, `WorkoutEditing.kt`, `LiveWorkoutScreen.kt`, `ActiveWorkoutStore.kt`,
`ActiveWorkoutClock.kt`, `EndWorkoutConfirmation.kt`, `IntervalsScreen.kt`, `AutoWorkoutNudge.kt`,
`RecentSportsPrefs.kt`, `RouteCanvas.kt`, `RouteExportShare.kt`, lift/gym code if any (grep `Lift`). Reuse all
recording/HR/GPS/route/export logic; this is UI + navigation.

## Build
1. **Workouts tab root** (large title, + → manual add, overflow: Lift Log (if Android has a lift log; else omit and
   report), Intervals): start cards 2-column grid (up to 5: recent sports, most frequent, defaults Walking/
   Running/Strength/Cycling) with fitness container colour + play button → start workout and open the live
   screen; "Other Workout" → sport picker; "Recent" + "Show All" → history; long-press row menu; pull to refresh.
2. **All Workouts** history: FilterChips (All + sports), month sections, rows (sport icon circle, name, headline
   figure distance/kcal/duration in fitness colour, date), swipe-to-delete WITH undo snackbar, row menu
   (Label as…/Not a Workout for detected, Edit for manual, Duplicate as Manual for imported, Delete), empty
   state.
3. **Workout detail** per §6 (header, "Workout Details" grid, Effort card, "Heart Rate" with chart + 5 zone rows,
   "Heart Rate Recovery", "Map" → full map; top bar export (GPX/FIT) + overflow with the row menu).
4. **Live recording** (always dark, full screen, per mockup 07): collapse ⌄ minimises to the mini-player; 2 pages
   (metrics / zones) with page dots; bottom panel with stopwatch (fitness colour; paused colour + "PAUSED"),
   Finish (dialog Finish/Discard/Cancel), big pause/resume, zones toggle. Keep screen-on per setting.
5. **Mini-player**: a compact bar docked above the NavigationBar on every tab while a workout / gym session /
   intervals run is active (icon, title, "clock · status", one control); tap re-opens the recording screen.
   Implement in the shell (AppRoot) with the existing active-workout store.
6. **Intervals** setup + run per §6 (M3), gym session if Android has it (else report).
7. **Manual add/edit** as an M3 full-screen dialog or bottom sheet: sport picker (searchable, Recent/All, "Use
   “query”"), start/end pickers, duration, optional distance/kcal/avg HR with LOCALE-AWARE number parsing
   (comma decimal accepted — CR-11) and validation copy; discard confirmation.
8. Delete old Workouts UI no longer used; tests.

## Done means
Screenshots light + dark: tab root, history, detail, live recording both pages + paused, mini-player on
another tab, intervals, manual add. Tests: locale number parsing, start-card ordering, headline figure
choice. Commits per step.

## Notes from earlier tasks
- The Summary shows a temporary "Workout in progress" NoticeCard (`WorkoutRunningNotice` in ui/summary) until the
  mini-player exists — remove it when your mini-player lands. The live workout is currently a full-screen
  dialog opened over the Workouts tab (Task 01 moved the "workout in progress" request there).
- Android has no Lift Log / gym session code: say so, don't invent one (the Workouts overflow has Intervals only).
