# Android port of Denis's iOS 26 redesign (Material 3)

Plan and working notes for bringing the reNOOP iOS redesign (Denis, 2026-09-26..30) to the Android app in
Material 3 / Google-app style. Decisions (user, 2026-09-30): Material You colour from the wallpaper; reNOOP ring
palette (Charge green, Effort blue, Rest violet); the Summary offers two layouts (Denis's "Detailed" and a
"Compact" Fitbit-like grid, pref `noop.summaryLayout`); work is committed step by step to the `android-m3` branch (main is merged into it; see HANDBOOK "On the Windows PC" for the PC set-up).

- `HANDBOOK.md` — rules every implementing agent follows (design system, strings in 9 locales, build/test/emulator).
- `ios-anatomy.md` — screen-by-screen anatomy of Denis's iOS screens with exact strings.
- `tasks/01…09` — the port broken into tasks; `mockups/` — the approved Material 3 references
  (Design canvas: https://claude.ai/artifact/WrtJBAqCxTcr8rpBkEFL2E); `tools/addstr.py` — adds a string in all
  nine Android locales, reusing Denis's iOS translations.

## Status
| Task | State | Commits |
|---|---|---|
| Rebrand + Material You theme + `ui/m3` | done | 9e40ed9b, 4c5610b2 |
| 01 Shell, Browse, removals | done | 42c828cc, cec7f311 |
| 02 Metric page, All Metrics, Trends | done | 3f6e4e2b, 097122d1 |
| 03 Summary (A/B) replacing Today | done | 052c956e, 2021324b, 531311f8 |
| 04 Sleep, Vitals, More Sleep Data, Sleep Schedule | done (WIP 8246e461 finished on the PC, merged with main's sleep fixes) | 8246e461 + merge |
| 05 Workouts | done (no Lift Log / gym session on Android; the route map is the offline drawing, no tiles) | e3462f91, 9046e481 |
| 06 Settings | done | c6b5f47a, 9af446e5, 172fa05c, ab0e6c3c |
| 07 Coach, Devices, Onboarding | done (not run against a real strap; Gemini chart image not ported) | e9b2d356, 5c7a784e, 97a4c49f, 1eca0586 |
| 08 Heart Rate, Mindfulness, Journal, What Moves You, Lab Results | done (the unreachable Hydration screen is deleted, its Settings switch stays; HRV reading restyled) | 1e8c40a0, 8137052e, 3a8d2548, 27f0d705, 0da73668, a67cdb47, c859bd11 |
| 09 Audit, widgets, notifications, i18n, dead code, dark pass | done in three parts: 09a widgets and notifications, 09b audit and dark pass, 09c terminology, i18n and dead code (open: widget Rest vs Summary Rest, parity dispositions for deleted twins, import messages still English) | 2334bac1..9611b627, 92419bca..af6e5ecb, 5aa063d1..f7da2b92 |
| Sleep tab rework (2026-10-07, after the port) | done: see "Sleep tab rework" below | (this commit) |
| Kotlin twins of Denis's 2026-10-03 commits (0398679d..f73b7144) | done: see "Kotlin twins of 2026-10-03" below | 7dafc618..e21e5c2d |

## Sleep tab rework (2026-10-07)
The ported Sleep tab followed iOS (Health's Sleep Score page) and read as figures without meanings: a
four-arc ring with points out of 50 / 20 / 20 / 10 (the 10 for regularity is a neutral constant for one
night), a Vitals tile of unlabelled marks, highlight bars without a scale, and three levels of pages. The
user asked for the tab to be rethought, with WHOOP's flow as the reference (a list of the day's sleeps, each
opening its own page; stages against a typical range) and in Material 3 Expressive. Android's Sleep tab now
departs from the iOS layout on purpose; the data, the scoring and the editing are unchanged.

- **Sleep tab** (`ui/sleep/SleepTabScreen.kt`, `SleepCards.kt`): the score on a cookie with its word and one
  sentence; the day's sleeps as rows (night and naps); "Key factors" (time asleep against the 8 h target,
  unbroken sleep, deep and REM, each with a level badge and a bar); "Your body overnight" (each reading with
  its usual range and typical / higher / lower in words); "Past 7 nights" (average, a bar per night against
  the target, the week before, bedtime against the usual, sleep debt on the newest night). Each section's
  "What's this?" opens a sheet explaining its figures.
- **One sleep's page** (`SleepSessionScreen.kt`, route `sleep_session/{day}/{nap}`): time asleep and deep +
  REM beside the usual, the stages over the clock, each stage as a bar with its usual range marked, heart rate
  through the sleep. A nap gets the same page without the comparisons. The Summary lists the day's naps as
  rows under the Sleep card.
- **Sleep History** (`SleepHistoryScreen.kt`) replaces More Sleep Data: W / M / 6M, one list of averages, the
  debt card with what it measures. The Vitals and Sleep Highlights pages are gone (their content is on the
  tab).
- **Expressive pieces** (`ui/m3/M3Expressive.kt`): the module is on material3 1.2.1, which has no Expressive
  components, so the cookie and clover shapes, the thick indicator with gap and stop, and the filled level
  badge are drawn by hand from the existing tokens. Moving to the library's own Expressive components needs
  material3 1.4+ with Kotlin 2 and a newer Compose: a separate change.
- Not done here: the iOS Sleep tab is untouched; editing a night or a nap still lives in the tab's overflow
  menu, not on the sleep's own page.

## Kotlin twins of 2026-10-03
Denis's twelve commits of 2026-10-03 were Swift-only apart from one decoder line. Their Android twins, in the
order they landed (all on `android-m3`, 2026-10-07):

| Denis | Android | What |
|---|---|---|
| 707d0c58, c2271480, 21975064 | 7dafc618 | Step gate with the last-moved time base; 0.01 calibration grid; Steps read in full on a 4.0 |
| 34bd3f32 | 676db807 | `Whoop4RawImu.accel`: the raw IMU packet's accelerometer block |
| 5da7946a | 8e799d09 | `GaitCadence`, `StepCalibration` |
| 815eb650 | 3adae6f8 | `LiveStoreReplacement`: no history ack once a restore replaced the database |
| f73b7144 | 6a79de69 | `ForeignOffloadDetector`, the other-app warning, the first-run "One App per Strap" step |
| 8598ba4e | 62015fe4 | Per-day step divisor through scoring; the Auto step calibration switch (Experimental, default off) |
| ff4c0e32, 8598ba4e | 3b5201bb | `RawStreamProbe`, `StepAutoCalibrator` and their BLE wiring |
| b40998a7 | 6ee457a9, 2beee6e1, 68e123d4, e21e5c2d | Planner hint; offload cadence log; scoring held until the burst ends; edited nights re-staged only on change |

Not ported, with the reason: the Live-screen raw-stream opt-in (a launch argument on iOS), the `renoop://`
URL scheme (Android declares no URL scheme), the iOS relaunch alert (Android restarts itself after a
restore), the chunk-decode priority (Android sets none to raise).

Seen on a WHOOP 4.0 with a Pixel 8 Pro on 2026-10-07: a sync on the final build, the cadence line
(`chunks=13 … strap+radio … n=12`), the scoring hold (`waited 14s for the offload`), no crash, and a cold
scoring pass as long as before (about 57 s). Not exercised on a strap: a calibration burst (the switch is
off by default), the other-app warning, the restore latch. Known cost carried over from the Swift change:
the planner hint is slower when the probed id has no tagged beats and another strap in the store has many
(a re-added 5/MG's canonical alias); fixing every shape needs an index on `(deviceId, srcChannel)`.
`Tools/parity_ledger.py` lists the new Kotlin functions as unregistered; the stored authority was already
failing before these commits and was not re-derived.
