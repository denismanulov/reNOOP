# Task 08 — Heart Rate (Live), Mindfulness (Breathe), Journal, What Moves You, Lab Results, Hydration

Read `SCRATCH/HANDBOOK.md` first. Then `SCRATCH/ios-anatomy.md` §12, §13. iOS: `Strand/Screens/LiveView.swift`,
`BreathingView.swift`, `Strand/Journal/*`, `InsightsHubView.swift`, `LabBookView.swift` (+ marker page, entry
sheet), hydration if still present. Denis commits: 237f1ae7, d6d169bb, 719ad15a (LV-1…LV-4, BR-2…BR-4), b10fdcc0
(CR-8 Calm shows rhythm visually and needs breathing haptics on), d16fcb6a, 8650b159, b5cf02eb, 686fe2f9 (WM-1,
WM-2, LB-1, LB-2: translated questions, no causal stats wording), 8f840a3c.

## Android today
`ui/LiveScreen.kt` (+ `LiveConsoleReadout`, `LiveRrPackets`, `HrvSnapshotScreen.kt`), `BreatheScreen.kt`
(+ `BreathProtocolL10n`, `BiofeedbackPrefs`), `InsightsScreen.kt` (journal) + `JournalCatalog.kt`,
`JournalLog.kt`, `JournalReminder.kt`, `CaffeineLog.kt`, `CycleTrackerDialog.kt`, `InsightsHubScreen.kt`,
`LabBookScreen.kt`, `MarkerEditorScreen.kt`, `LabValueFormat.kt`, `HydrationScreen.kt`, `MindSection.kt`.

## Build (M3, Google-app idioms; all push inside the Browse tab)
1. **Heart Rate** (watchOS Heart Rate / Fitbit style): problem NoticeCard → Devices; animated heart; "Now" +
   big BPM; "Today" card (range, resting HR, hourly range bars); bottom "Start workout" filled button or the
   active-workout row; overflow "HRV reading" → HRV snapshot. Test Centre live console stays reachable only from
   Developer.
2. **Mindfulness**: mode cards (Breathe / Resonance with its Quick/Full/Breathe-at dialog / Calm with its
   requirement), running-session row, Options group (Pace menu + "About this pace", Duration, Audio cues); dark
   session screen (minimise, end, animated flower drawn in Canvas, phase word, HR/HRV) and summary. CR-8: Calm
   shows its rhythm visually (not vibration only) and says it needs breathing haptics on.
3. **Journal**: day title, 8-day strip with completion rings, "To Log" cards (Habits sheet, Mood menu, Caffeine
   menu), "Logged" card with swipe-to-delete + undo; Habits sheet (Yes/No, Number, New item). Translated habit
   questions (WM-1). Cycle tracker/hydration only if iOS kept them (check; hydration is a Settings feature
   switch on iOS).
4. **What Moves You** (Highlights style): ⓘ "Association, not cause"; Outcome segmented Charge/HRV/Rest/Resting
   HR; habit cards → effect detail; Alcohol & Caffeine; Metrics relationship cards; Mood; empty state with "Open
   journal". No causal wording (WM-2).
5. **Lab Results**: ⓘ About; + menu (Add reading, Import CSV); category sections; marker rows; marker page (latest,
   range on report, chart, history with delete confirm, Compare); add-reading sheet; empty state. LB-1/LB-2.
6. Delete superseded UI; tests.

## Done means
Screenshots light + dark of every screen above. Commits per screen.
