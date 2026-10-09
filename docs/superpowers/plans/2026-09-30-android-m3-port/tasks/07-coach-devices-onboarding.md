# Task 07 — AI Coach (Google Messages style), Devices + Add Device, Onboarding

Read `SCRATCH/HANDBOOK.md` first. Then `SCRATCH/ios-anatomy.md` §9 Coach, §10 Devices, §11 Onboarding.
iOS: `Strand/Screens/CoachView.swift`, `CoachMessageParts.swift`, `CoachSettingsView.swift`, `Strand/Devices/*`,
`Strand/Onboarding/*`, `Strand/App/TermsGateView.swift`. Denis commits: 46ac8e93, d2ca0517, 59827c70, 9b55077f
(CO-1, CO-2, CO-4, K-3), ba8f53d7 (CR-10 send health data only when asked), e40aa956 (CR-12 dictation), 719ad15a
(CO-5), a372fb21 (CO-3); 5eac9256, b4763aed, 61a8a162, 05d4df50 (DV-1…DV-5), 93fd546e; ea78da7f, 2412685d,
5ea7409f (ON-2…ON-5), 5b06c5dd (CR-7 gates modal to assistive tech). Mockups: `10-Coach.dc.html`,
`11-Devices.dc.html`, `12-Onboarding.dc.html`.

## Android today
Coach: `ui/CoachScreen.kt`, `CoachViewModel.kt`, `CoachSettingsScreen.kt`, `CoachMarkdown.kt`, `CoachPrompts.kt`,
`CoachVoiceInput.kt`, `CoachLauncherSheet.kt`, `CoachBriefScheduler.kt`, `com.noop.ai.*`.
Devices: `ui/DevicesScreen.kt`, `AddDeviceWizard.kt`, `ConnectionHelp.kt`, `HeaderBatteryDisplay.kt`, BLE in
`com.noop.ble` (do NOT change BLE behaviour or commands — UI only; every existing safety gate/confirmation stays).
Onboarding: `ui/OnboardingScreen.kt` (12 steps), `TermsGate.kt`, `MainActivity.kt` gate logic.

## Build
1. **Coach** as a Google Messages conversation: top app bar (back, gradient avatar with sparkle, "AI Coach" +
   subtitle "{provider} · AI can make mistakes", settings action → Coach settings as a full-screen page or
   sheet); bubbles (outgoing primary, incoming surfaceContainerHigh with markdown, grouped corners), day stamps,
   "Today's Brief" label, "Stopped", typing indicator, failure "Not delivered" + retry/update key dialog, long-
   press menu Copy/Share/Save to Journal/Try again; input: pill text field "Ask Coach", mic (dictation KEEPS the
   typed text, stoppable), send FAB-ish button, stop while streaming; suggestion chips row (Today's Brief +
   data prompts; after a reply the four follow-ups). Not configured → EmptyState + "Set up". Settings page
   sections per iOS. **CR-10: health data is sent only when the user asks** — check `CoachViewModel`/`AiCoach`:
   no automatic context upload without an explicit user action matching the "Use My Data" toggle footer text;
   fix if Android sends more than iOS now does, and say so in your report.
2. **Devices** (mockup 11 + iOS §10): active device card (icon, battery ring, status, Buzz/Sync tonal buttons);
   "My Devices" group (✓ active, tap row = make active, ⓘ → detail), "Add Device"; strap settings group (Sync,
   Power saving, Double-tap, Haptics, Heart-rate broadcast — the existing controls); "Removed" dimmed; device
   detail page per iOS; Add Device wizard as M3 full-screen steps (type list with WHOOP / Other / Beta groups,
   pairing card title + instruction + icon + "Find"→"Connect", pick list with signal bars, "Search again",
   Bluetooth-off state with "Open settings", confirm name + Connect). Keep every existing BLE flow & guard.
3. **Onboarding** = terms gate then 4 steps (Welcome / Find your strap / About you / Bring your history) as
   Pixel setup-wizard pages (mockup 12): step progress, big icon, headline, one line, content, bottom bar
   (text button left, filled button right). Reuse the existing strap-pairing and profile logic; import step
   rows WHOOP export / Health Connect. Gates are modal for TalkBack (no focus behind them). Delete the removed
   steps' UI.
4. Delete old UI no longer used; tests.

## Done means
Screenshots light + dark of each screen (Coach: configured conversation + empty state + settings; Devices list +
detail + wizard type list; onboarding each step). Commits per area.
