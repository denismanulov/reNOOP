# Task 06 — Settings (Pixel Settings style), Health Details, sub-pages

Read `SCRATCH/HANDBOOK.md` first. Then `SCRATCH/ios-anatomy.md` §8 Settings, iOS `Strand/Settings/*`,
plan `docs/superpowers/plans/2026-09-27-settings-consolidation.md`. Denis commits: 581adb98, eaf485a9, 3b9a1e8d,
14628b61, 41fcfe98, f67a8186 (no in-app appearance), 6f27c1c6 (no copies of system settings), 719ad15a (What's
New), a372fb21 (backup confirm), audit items ST-1…ST-10. Mockup: `09-Settings.dc.html`.

## Android today
`ui/SettingsScreen.kt` (4.4 k lines) + `SettingsComponents.kt`, `SettingsLogic.kt`, `NotificationsSettingsScreen.kt`,
`AutomationsScreen.kt`, `PowerSavingScreen.kt`, `BackupSyncScreen.kt`, `DataSourcesScreen.kt`,
`AppleHealthScreen.kt` (Health Connect), `StepsCalibrationScreen.kt`, `ScoringGuideScreen.kt`,
`HowNoopWorksScreen.kt`, `UpdatesInboxScreen.kt`, `WhatsNewSheet.kt`, `TestCentreScreen.kt`,
`NoopLimitationsScreen.kt`, `ProfileAvatar.kt`, profile prefs, `CoachSettingsScreen.kt` (Coach task).

## Build
Root "Settings" exactly in iOS order, drawn like Android 16 Settings (large title, segmented `ListGroup`s,
`TonalIcon`s with `LocalTonalIcons` pairs matching iOS tile colours): profile header (avatar + name, "Health
Details") → Health Details page; strap row (active strap name + status) → Devices; App: General,
Notifications, Automations (Android's twin of iOS Shortcuts — the existing Automations screen); Features:
Workouts, Scores, AI Coach (switch = `noop.coachEnabled` master switch semantics: off hides Coach everywhere and
cancels the brief), Hydration (switch); Data: Health Connect (the existing AppleHealthScreen/Health Connect
page), Import (Days stored + importers list from DataSourcesScreen), Backup (BackupSync: folder, restore,
daily, keep, last; file export/import/CSV, destructive actions confirmed); About reNOOP (Version, What's new,
How reNOOP works, Updates: check / check automatically, Built on credits — keep upstream credits intact);
Developer (Test Centre, Power saving/diagnostics, self-hosted push, ground truth…) hidden until the version row
is tapped 7 times (persist the unlock).
Sub-pages: General (Language → system per-app language settings intent on Android 13+, else the existing
in-app picker; Day starts; App icon; Units page; **Summary layout** Detailed/Compact using the pref from the
Summary task `noop.summaryLayout`), Notifications (Reminders / Alerts per iOS), Workouts (Auto-detect, Keep
screen on, live HR in an ongoing notification = Android twin of "Heart rate in Dynamic Island"), Scores (Effort
scale + exponential, Steps calibration + estimate, Charge HRV window + Reset baseline with confirmation, How
scores work). Health Details (avatar photo add/change/remove, name, date of birth with age, sex, height, weight,
waist with locale-aware wheels/fields, max HR auto/manual, zones auto/manual → zone editor).
Remove what Denis removed: in-app Appearance (theme mode + accent colour + chart style + background image +
bottom-bar style controls — the app follows the system and Material You now; delete the prefs' UI and make
`NoopTheme` ignore the stored mode; keep reading nothing else), duplicated controls, explanatory prose blocks,
settings that copy system settings. Anything else in the old SettingsScreen that has no place in the new tree:
list it in your report instead of silently dropping behaviour (BLE/strap controls belong to Devices).
Delete the old Settings UI once every control is reachable. Tests for the 7-tap unlock and any pure logic.

## Done means
Screenshots light + dark: root, General, Notifications, Scores, Backup, Health Details, About, Developer
unlocked. Commits per step.

## Notes from earlier tasks
- Task 01 added temporary "App" and "Data" link groups at the top of the legacy Settings (Notifications, Alarms,
  Automations, Power saving, Apple Health import, Data Sources, reNOOP Limitations) — fold them into the new tree.
  The AI Coach switch lives in `CoachEnabledStore` (key `noop.coachEnabled`).
- Task 03 put "Summary layout" under Settings > Appearance via `SummaryLayoutPrefs` — move it to General.
- Dead settings (only the removed Today read them) to delete: "Ring gauges on Today", "Gauge numbers",
  "Day-cycle background", "Sky behind cards", card transparency; plus the in-app appearance controls.
  "Auto-detect workouts" no longer has a Summary nudge; keep the toggle only if detection itself uses it.
