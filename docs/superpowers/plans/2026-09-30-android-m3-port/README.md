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
