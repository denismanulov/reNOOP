# Swift work list: what `android-m3` has that the Swift app does not

State on 2026-10-08. The Android port normally follows the Swift app. Since 2026-10-07 several changes
went the other way: they were asked for by the Android wearer, built on Android first, and have no Swift
twin. This page is the ordered list of that work for whoever (person or agent) brings it to `Packages/`,
`Strand/` and `StrandiOS/`. One section is one commit.

## How to use this page

- Everything referenced lives on the branch `android-m3`. Read an Android change with
  `git show <hash>` after `git fetch origin android-m3`; the commit bodies carry the reasoning and the
  test counts, and are not repeated here.
- Read [`AGENTS.md`](../../../../AGENTS.md) first. Three of its rules decide how this work is done:
  stored data and analytics must be byte-identical on both platforms and proven by a compiled oracle,
  not by reading the two side by side; app-target Swift must be built with `xcodebuild` because no
  default CI compiles it; a signal derived from sensor data stays behind a default-off switch until it
  is shown to track a varying input.
- Each section says what was seen on a device. Most of this was built on one day and has **not** been
  exercised on a strap. Do not describe the Swift twin as verified on the strength of the Android one.
- Tasks 1 to 4 need no decision and can be done in order. Tasks 5 to 8 start with a decision that
  belongs to the Swift app's owner; the section says what the decision is.

| # | Task | Android commits | Decision needed first | Seen on a device (Android) |
|---|---|---|---|---|
| 1 | WHOOP 4.0 blood oxygen from the strap's own byte | `b7a287e0` | no | one night, one strap |
| 2 | A tapped "I'm awake" ends the night it lands in | `3dc7dd48` | no | installed, not used on a real night |
| 3 | Summary says when the night is still being counted | `6689bb10` | no | never: it only shows during a night |
| 4 | Coach settings say that a key is saved | `d52f0363` | no | compiled only |
| 5 | Score names: Recovery / Strain / Sleep | `48db24f7`, `bc67ceee` | yes: rename on iOS or not | English and Russian, on one phone |
| 6 | Friends tab client | `45dfa0db`, `990ef2e8` (server only) | yes: the design is not final | server only; no client exists on either platform |
| 7 | Sleep tab rethought around answers | `3545cfa9`, `a91adcda`, `4f6c2764` | yes: follow it on iOS or keep the Health layout | daily use, one phone |
| 8 | Coach conversation redrawn, and Coach as a tab | `f20adffb`, `12e1abbf` | yes: follow it on iOS or keep the Messages layout | the conversation on one phone; the tab compiled and unit-tested only |

Nothing to do on Swift: `36af70f2` (the short-nap review list). Nap detection and its review queue
(`NapDetector`, `NapStore`) exist only on Android.

## 1. WHOOP 4.0 blood oxygen from the strap's own byte

Suggested subject: `protocol: read the WHOOP 4.0 strap's own blood-oxygen byte as an unverified candidate`

The full specification is [`SWIFT_HANDOFF_2026-10-07.md`](SWIFT_HANDOFF_2026-10-07.md), section 1: the
offset and value vocabulary, the stored-data contract (event kind `V24_AUX_BYTE_86`, payload exactly
`{"byte":168}`), a table of each Android piece beside the Swift file it belongs in, four real frames
with their expected decode, and what is not established.

Two things to carry from the first night on a strap (same file, "First night on a third strap"):

- The path works end to end, and the nightly **mean** is a weak estimator: a few late windows of 78 to
  90 pulled it to 93.5 while the median was 95. Twin the mean as Android has it so the two platforms
  agree; do not pick another estimator in the same commit.
- No test on either side asserts that the 4.0 path writes only the `spo2_candidate` series and never
  `spo2Pct`, recovery or illness. Add it on Swift, and name the missing Kotlin one in the commit body.

## 2. A tapped "I'm awake" ends the night it lands in

Suggested subject: `sleep: let a tapped "I'm awake" mark end the night it lands in`

"Log waking up" has only written a log line and a series point (#461 Phase 1). On Android it now also
asks for the night to end at the tap. The reason is in the handoff's "End of sleep runs late when the
wearer lies still after waking": the detector's misses have one shape, and a tap is the one input that
knows it.

**The rule (pure, integers, unix seconds).** Android: `android/.../analytics/WakeMarkTrim.kt`.

- `MAX_TRIM_SECONDS = 3600`, `MAX_PENDING_SECONDS = 43200`.
- `trimmedEnd(startTs, endTs, wakeTs)` returns `wakeTs` when `startTs < wakeTs < endTs` and
  `endTs - wakeTs <= 3600`, otherwise nothing. A mark never extends a night and never touches a night
  it is not strictly inside.
- `decide(nights, wakeTs, nowTs)`: `expired` when `nowTs - wakeTs > 43200` (checked first, even if a
  night would fit); otherwise `trim(index, newEndTs)` for the first night `trimmedEnd` accepts;
  otherwise `wait`.

Put the Swift twin in `Packages/StrandAnalytics` so `swift test` covers it. Port every case of
`android/app/src/test/java/com/noop/analytics/WakeMarkTrimTest.kt`; the boundaries that matter are:
a mark exactly one hour before the end trims and one second more does not; a mark at the end or at the
start does nothing; a mark `43200` seconds old still trims and `43201` expires.

**The wiring.** Android: `logWakeNow` and `applyPendingWakeMark` in `android/.../ui/AppViewModel.kt`,
`android/.../data/WakeMarkStore.kt`.

| Step | Android | Swift place |
|---|---|---|
| The tap | `logWakeNow()`: logs as Phase 1 did, stores the pending mark, starts a sync | `logMark(.wake)` in `Strand/SleepHealth/SleepHealthView.swift` (line 380), `Strand/Data/SleepMark.swift` |
| The pending mark | one preference, `noop.pendingWakeMarkTs`, unix seconds; a newer tap replaces an older one | `UserDefaults`, the same key name |
| When it is applied | on the tap, and after every scoring pass while a mark is pending | wherever the Swift app learns a scoring pass finished |
| Which nights are offered | sessions whose start is in `[wakeTs - 86400, wakeTs]`, as `(effective start, end)` | the merged sleep sessions for the registry's **active** strap id |
| The trim | `updateSleepSessionTimes(night, sameStart, newEnd)`, the hand-edit path, then the mark is cleared | `Repository.editSleepTimes` (`Strand/Data/Repository.swift`, line 1486) |
| Evidence | strap log line `Sleep mark · wake moved the end of sleep <N> s earlier`, always on | the same line |

Contract points:

- The trim must go through the hand-edit path, so the night is marked `userEdited` and a later pass
  does not re-detect the longer span back over it. That is the only stored-data effect: the same mark
  on the same night must leave the same session end on both platforms.
- The pending mark is a request, not a measurement. It is not a database row and is **not** added to
  the `.noopbak` whitelist.
- "Log going to sleep" still only logs. So does the strap double-tap, which cannot say which boundary
  it means.

Not established: this has never been used on a real night on either platform.

## 3. Summary says when the night is still being counted

Suggested subject: `summary: say when the night is still being counted, and give it an "I'm awake" button`

Depends on task 2. While the wearer sleeps the app keeps syncing and re-scoring, and each pass stores
the night as it stands, so the Summary showed a growing total (289, 337 ... 473 minutes on one night)
exactly like a finished night.

**The rule (pure).** Android: `android/.../ui/summary/SleepInProgress.kt`.

`isInProgress(bedTs, lastAsleepTs, newestDataTs, nowTs, pendingWakeTs)` is true when all of these hold:

1. there is a newest sample (`newestDataTs` is present);
2. there is no pending wake mark later than `bedTs` (a mark from before this night is ignored);
3. `nowTs - newestDataTs <= 5400` (the data is under ninety minutes old);
4. `newestDataTs - lastAsleepTs <= 600` (the night ends within ten minutes of the newest sample).

Both limits are inclusive. Port every case of
`android/app/src/test/java/com/noop/ui/summary/SleepInProgressTest.kt`.

**The card.** Android: `SleepInProgressCard` in `android/.../ui/summary/SummaryScreen.kt`, used by both
Summary layouts, placed directly under the scores. Swift place: `Strand/Summary/SummaryView.swift`,
`SummaryLoader.swift`, `SummarySleepCard.swift`.

- Shown only for today, only when the night on screen is the one whose wake day is today, and only
  when it has time asleep.
- `newestDataTs` is the newest heart-rate sample on record for the active strap id.
- It shows the time asleep so far and one button. The button does **not** set the wake time: it calls
  the wake mark of task 2 (which starts a sync), and the detector finds the moment in the strap's data.
  The tap survives only as an upper bound. The card goes away on the tap, before any sync proves it.
- Resolve the in-progress state and the figure on the card from one loader result and one clock, per
  the "two readouts of one fact" rule in `AGENTS.md`.

| String | English | Russian |
|---|---|---|
| title | Sleep still in progress | Сон ещё идёт |
| body | Tap when you are up. reNOOP syncs and finds when you woke. | Нажмите, когда встанете. reNOOP синхронизируется и сам найдёт момент пробуждения. |
| button | I'm awake | Я проснулся |
| confirmation after a wake mark | Marked awake at 08:15. Syncing to find when you woke. | Отмечено в 08:15. Синхронизирую и уточняю момент пробуждения. |

The other seven Android locales are in `android/app/src/main/res/values-*/strings.xml` under
`summary_sleep_in_progress_*` and `sleep_wake_logged`.

Not established: the card has never been seen on a device, because it only appears during a night.

## 4. Coach settings say that a key is saved

Suggested subject: `coach: say that a key is saved when the key field is empty`

The API key field empties on save and the stored key is never shown again, so a saved key and no key
look the same. A wearer read the empty field as the key having been lost. Android now shows a
supporting line under the field while a key is stored and nothing is typed.

Swift has the same shape: `commit()` in `Strand/Screens/CoachSettingsView.swift` (line 184) clears
`keyDraft` after `coach.setKey`, and the `SecureField` at line 128 then shows only its placeholder.
Show the line when `coach.hasKey && keyDraftIsEmpty`.

| English | Russian |
|---|---|
| A key is saved. Enter a new one to replace it. | Ключ сохранён. Введите новый, чтобы заменить его. |

## 5. Score names: Recovery / Strain / Sleep

Decision first: the Android wearer asked for the three scores to carry WHOOP's names instead of
Charge / Effort / Rest (Russian: Восстановление / Нагрузка / Сон instead of Заряд / Усилие / Отдых).
Android shows them that way in English and Russian since `48db24f7` and `bc67ceee`. Whether the Swift
app follows is its owner's call. Until it does, the two apps name the same scores differently.

If the answer is yes, it is two commits:

1. **The catalogue.** `Strand/Resources/Localizable.xcstrings` and the other catalogues: the strings
   that name a score. `bc67ceee` lists what Android changed beyond the string table (the explainers in
   How reNOOP works, two Test Centre blurbs, the morning and post-workout notifications, one sentence in
   the coach's default system prompt) and what it deliberately left alone (battery charge wording, the
   interval-training Rest label, changelog bodies).
2. **The pinned sentences, on both platforms in one commit.** These are byte-identical twins and were
   left on the old names on Android for that reason: the `WeeklyDigest` and `ActivityCostEngine`
   sentences, and the Test Centre mode registry. Change Swift and Kotlin together and re-derive the
   Kotlin expectations from a compiled Swift oracle. The internal score-family keys in `MetricCatalog`
   are identifiers, not copy; leave them.

One finding to weigh with the rename: under the name "Strain" a wearer read `19.1` on the default
0 to 100 axis as 19 of WHOOP's 21. The display scale is an existing setting (Settings > Scores); the
default is 0 to 100 on both platforms and was not changed. Changing the default was suggested and not
decided.

## 6. Friends tab client

Decision first: the design is not final. Open on 2026-10-08: whether the tab's home shows the wearer
beside one chosen friend or a card per friend, and whether password recovery is needed (the server
has none, because an account has no e-mail).

What exists: the service only, in [`friends-server/`](../../../../friends-server/README.md). Its
`README.md` is the API contract for both clients: nickname accounts, mutual friendships that start
with a request, one whitelisted summary per day uploaded after a strap sync, four sharing switches.
No client exists yet on either platform. The Android client is planned on this branch; the iOS client
is the Swift side's.

Constraints a client must keep, on either platform:

- **Fork-only, and off until the wearer signs in.** Accounts and reading data back from a server are
  outside upstream NOOP's scope (`AGENTS.md`, hard limits). Nothing may be sent before sign-in, the app
  must work exactly as before without an account, and none of this is offered upstream.
- **The server address is configuration, not a constant.** The instance the two authors use is shared
  between them directly and is not written into this repository.
- **Strain travels on the stored 0 to 100 axis.** Each phone converts to its wearer's display scale
  when it draws.
- **A sharing switch that is off sends nothing.** The server also drops and erases that section, but
  the client must not rely on that. Heart rate is off by default.
- The feed omits the heart-rate line; a friend's own page asks for it with
  `GET /v1/users/{nick}/days`.
- The token belongs in the Keychain. It is not a setting and never enters `.noopbak`.
- What goes into an uploaded day must be computed the same way on both platforms, since two friends
  on different platforms read each other's figures side by side. When the Android uploader exists, its
  day builder is the reference; agree the field derivations before writing a second one.

## 7. Sleep tab rethought around answers

Decision first: the Android Sleep tab departs from the iOS layout on purpose. The Android wearer found
the ported Health-style page hard to read (points out of 50 / 20 / 20 / 10, unlabelled marks, three
levels of pages) and asked for WHOOP's flow: a list of the day's sleeps, each opening its own page,
stages against a typical range, and a meaning in words beside every figure. Whether iOS follows or
keeps the Health layout is its owner's call. Scoring, data and editing are unchanged, so nothing here
is a parity obligation.

What Android built is described in this folder's [`README.md`](README.md), "Sleep tab rework
(2026-10-07)": the tab, one sleep's page, Sleep History, and the hand-drawn Material 3 Expressive
pieces. The last of those is Android-only styling; the structure is what would carry over.

## 8. Coach conversation redrawn, and Coach as a tab

No suggested subject: whether iOS follows is the decision.

The wearer found the Messages-style conversation heavy: replies set in a larger type than his own
messages, with headings larger still, no motion, and a header line naming "Your server" and the
general caution about AI. Android now draws it as a messenger chat (`f20adffb`): both sides in one
chat-sized type, the clock time in each bubble's bottom corner and one chip per day in place of the
hourly stamp, a floating header and entry row drawn as glass over the transcript, and, while a reply
is on its way, the Coach's mark turning beside a verb that changes every few seconds in place of the
three dots. The header's status line reads "typing…" for that time and otherwise names the model.
The verbs describe thinking and claim no step the app cannot show is happening.

Coach is also a tab of its own on Android, between Workouts and Browse, shown while the AI Coach
switch is on (`12e1abbf`); its Browse row and search hit are gone and the Coach widget's link shows
the tab.

What would carry over if iOS follows: the chat-sized type for replies (the complaint was about size,
and `CoachView` sets replies the same way), the per-bubble time with a day chip (`dayChipBefore`
replaces `stampBefore` in `CoachConversationRules`), the changing verb, and the status line. Glass is
native on iOS 26. The tab is a placement choice for the iOS owner.

| English | Russian |
|---|---|
| typing… | печатает… |
| Thinking… | Думаю… |
| Pondering… | Размышляю… |
| Mulling it over… | Обдумываю… |
| Connecting the dots… | Сопоставляю… |
| Weighing it up… | Взвешиваю… |
| Finding the words… | Подбираю слова… |
| What would you like to know? | Что хотите узнать? |
| Jump to latest | К последним сообщениям |

## Findings that changed no code

Recorded in [`SWIFT_HANDOFF_2026-10-07.md`](SWIFT_HANDOFF_2026-10-07.md), section 2, each with its
sample size. They concern logic both platforms share, and none of them is a task yet:

- `red` / `ir` at 68 / 70 of the v24 record follow skin temperature, not oxygenation.
- The words at 78 / 80 / 82 look like optical channel configuration; offset 80 is still named
  `resp_rate_raw`.
- REM share is set by the time-of-night ramp in `SleepStagerV2` (29 % against WHOOP's 18 % on one
  wearer, unpaired).
- The v25 record map may be wrong.
- Step auto-calibration accepted one measurement 29 % away from a phone's count, refused real brisk
  walking twice, and fired a burst during sleep.
- The end of sleep runs late when the wearer lies still after waking (the reason for task 2).

## Owed on the Android side, for completeness

- The Friends tab client and its uploader.
- `Tools/parity_ledger.py` lists the Kotlin functions added on 2026-10-07 as unregistered; the stored
  authority was already failing before them and has not been re-derived.
- Two `RecoveryDriversTest` cases have failed on this branch since before this work.
- `origin/main` commits after 2026-10-03 (the sleep-stage model work) are not merged into
  `android-m3` yet.
