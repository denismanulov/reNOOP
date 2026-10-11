# Android work list: what the Swift app gained on 2026-10-10 and 2026-10-11

State on 2026-10-11, `main` at `01b11d33`. Two days of work went into the Swift app (`Packages/`,
`Strand/`, `StrandiOS/`) and the friends server with no Kotlin twin. This page is the ordered list of
that work for whoever (person or agent) brings it to `android/`. It is the mirror of
[`SWIFT_WORKLIST.md`](../2026-09-30-android-m3-port/SWIFT_WORKLIST.md), which went the other way.

## How to use this page

- Everything referenced is on `main`. Start by bringing it into the Android branch:
  `git fetch origin && git merge origin/main`, then build with `--no-build-cache --rerun-tasks`
  (see `AGENTS.md`, "Fast local loops").
- Read a Swift change with `git show <hash>`. The commit bodies carry the reasoning and are not
  repeated here. The Swift tests named in each section are the behaviour to reproduce.
- Read [`AGENTS.md`](../../../../AGENTS.md) first. The rule that decides how most of this is done:
  analytics and stored data must be byte-identical on both platforms and proven by a compiled oracle
  (the Swift twin's stdout pasted as the expected literal in the Kotlin test), not by reading the two
  side by side. UI parity is feature-level: the behaviour and the data must match, the look is
  Material 3's.
- No Android toolchain was available where this was written, so nothing here was compiled or run on
  Android. Each section says what was seen on the Swift side. Do not describe a Kotlin twin as
  verified on the strength of the Swift one.
- One section is one or more commits, one concern each. Tasks 1, 3, 4 and 5 need no decision. Tasks
  2 and 6 are design: how far Android follows the iOS layout is the Android app's owner's to decide,
  alone. The Swift app's owner has said so and does not need to be asked. What is not open in either
  is the behaviour and the figures.

| # | Task | Swift commits | Whose call | Size |
|---|---|---|---|---|
| 0 | Friends server on version 2 | `e4f7a588`..`e0678360`, `f5f3ee8f`..`2554868b` | done: the public server runs it | nothing to build |
| 1 | Friends client on API version 2 | `86cb95bc`..`d16bb9ee`, `e383c945` | none | large |
| 2 | Friends screens as redrawn | `afe40318`, `bd09bd79` | layout: the Android owner's | medium |
| 3 | Learned sleep stage model | `be1b4e1f`, `01b11d33` | none | large |
| 4 | Step auto-calibration starts only in a real walk | `ae1f9884` | none | small |
| 5 | Small fixes to check against Kotlin | `9e0f7b81`, `fd027288` | none | small |
| 6 | Tab bar and glyphs | `59a27c36`, `a8cc7320` | the bar: the Android owner's | small |

Nothing to do on Android: `Tools/SleepML` (the training tool, macOS only), the iOS demo launch
arguments, the `docs:` commits. The gate that holds the iOS app before the phone's first unlock
(branch `claude/sad-dijkstra-88ad25`) is not on `main` and is not part of this list.

## 0. Friends server on version 2

The server in `friends-server/` was rewritten: version 1 (nickname and password, friend requests) is
gone, and its database is not migrated. Everyone enrols again. The contract is
[`friends-server/README.md`](../../../../friends-server/README.md); the design and the reasons are
[`2026-10-10-friends-keys-and-straps-design.md`](../../specs/2026-10-10-friends-keys-and-straps-design.md).

- The public server (`https://renoop.duckdns.org`) runs version 2: on 2026-10-11 `GET /v2/info`
  answered `{"name": "renoop-friends", "api": 2, "time": N}`. So the Android tab as it stands (the
  version 1 client) does not work against it, and task 1 is what brings it back.
- For a server of one's own: [`friends-server/deploy/README.md`](../../../../friends-server/deploy/README.md)
  (origin behind a TCP-only proxy over WireGuard, no Cloudflare). The files there carry placeholders,
  not keys. `FRIENDS_STRAP_PEPPER` must be set and kept: the README says what a lost pepper does.
- Server tests: `cd friends-server && python3 -m unittest test_server` (79 tests; needs the
  `cryptography` package).

## 1. Friends client on API version 2

What changed for the wearer: no nickname and no password. Turning Friends on makes a P-256 key on the
phone; every request is signed with it. The account is tied to the strap's serial, so the same strap
finds it again on a new phone, where the old phone confirms the new one (or, after 48 hours of
silence, the strap alone lets it in on a 7-day probation). Friends are made by a one-time invite (QR,
link or code); using one makes two people friends at once.

The Android pieces to replace are in `android/app/src/main/java/com/noop/friends/` and
`com/noop/ui/friends/`. The work list of section 10 of the design, with the Swift file each item is
the twin of:

| Android piece | Swift twin | Notes |
|---|---|---|
| `AndroidKeyStore` P-256 key, one per server address | `Strand/Friends/FriendsKey.swift` | Public key as SubjectPublicKeyInfo DER (`publicKey.encoded`), key id = lower-case hex SHA-256 of it, `SHA256withECDSA` (already DER). A kept key that could not be read is never replaced (`2d7418e0`). |
| OkHttp interceptor that signs every `/v2/` call | `FriendsAPI.swift` (`perform`) | Four headers and the six-line signing string of the README. One retry on `401 clock_skew` with the server's offset. Send bodies with a `Content-Length`: a chunked body is refused `411`. |
| Twin of the wire rules | `Packages/StrandAnalytics/Sources/StrandAnalytics/FriendsWire.swift` | Signing string, invite-code normalising and formatting, taking a code out of a link or a pasted message (`ffd07eef`). Pure text: port by oracle, with the literals of `FriendsWireTests.swift`. |
| Twin of the strap handle | `Strand/Friends/FriendsStrap.swift` | Lower-case hex SHA-256 of `"renoop-friends-strap-v1\n" + <adopted id>`, through the existing Kotlin `WhoopSerialIdentity`. Vector: `whoop-4A0123456` gives `98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707`. |
| Models keyed by account id, `FriendsApi` on version 2 | `FriendsAPI.swift` | Register, login and password calls are deleted. The day payload is unchanged (`FriendsDayPayload` is already the twin). |
| `FriendsStore` state machine | `FriendsStore.swift`, design section 9.2 | `off`, `waitingForStrap`, `strapBound`, `waiting(claim)`, `on`. State, claim code and `maturesAt` survive a relaunch. |
| Turn-on card in place of the sign-in form | `FriendsView.swift` (`FriendsWelcome`) | Shows the name friends will see (`16dc2511`); "Not Now" on the two waiting pages (`3464f8cf`); a server-address field while Friends is off (`286143e6`). |
| Requests: a new phone, the strap, an unconfirmed phone | `FriendsRequests.swift` (`93daca82`) | Banners on the tab and a section in the sheet, with Confirm / Decline / Release / Keep / Remove. |
| Invite by QR, link or code; enter a code | `FriendsInvite.swift` (`765762d9`) | Invite codes are kept in a file left out of backups (`1adf4cdf`). |
| Phones of the account, and what the server stores | `FriendsPhones.swift` (`7e84eebf`) | The export is shown as it came, not re-encoded (`a6a26364`). Removing a phone asks first (`1079aad1`). |
| Intent filter for `renoop://friends/add?c=…` | `8a6db351` | Opens the Friends tab and asks before adding. |
| `FriendsApiLiveServerTest` on version 2 | `StrandTests/FriendsClientTests.swift` | Against a local server only. |

Behaviour fixed after the first pass, each with a Swift test; carry the rule, not the code:

- Turning Friends on runs once, and a lost enrolment answer is not read as joining (`c72edc32`). An
  enrolment that finds an existing account records no strap and pushes no profile (`9024711c`).
- A phone with nothing to show does not blank a day another phone uploaded (`f0184d10`); an empty day
  still goes up when this phone may have sent that day before (`1a736b9c`).
- Switching Photo off removes the account's picture whoever sent it (`49dbeffe`).
- A request cancelled by leaving its page is not reported as a server failure (`58073889`); an answer
  that went through is not reported as failed when the refresh after it fails (`00cc5055`); a failed
  answer, a failed revoke and a failed phone removal say so where the wearer acted (`c77688ed`,
  `a7369909`, `1079aad1`). A pull to refresh is not dropped when its task is cancelled (`d16bb9ee`).
- Store loose ends in one commit (`31c914ae`): strap id order, cancel while offline, a claim without a
  strap, upload-run guards, version 1 leftovers.
- A WHOOP 4.0 serial counts once it has been seen in two hellos. The gate that counts them lives for
  one run of the app, so a strap that stays connected never reached two; the first sighting is now
  kept between runs, and a hello that arrives before the store is open is held (`e383c945`,
  `FriendsStrapTests`). Check the Kotlin gate for the same hole before relying on it.

Error codes the client tells apart are listed in the README under "Errors". `unknown_key` returns the
phone to the turn-on page and keeps the key.

How to try it: run the server locally on a scratch database
(`FRIENDS_DB=/tmp/friends-dev.db FRIENDS_STRAP_PEPPER=<32 hex characters> python3 server.py`) and
point the app at it through the server-address field. Never enrol against the public server from a
test. Enrolments are limited to five an hour per address; the limits are in memory, so a restart
resets them.

Seen on the Swift side (as of `d16bb9ee`): 129 Friends tests in `StrandTests`, 38 package tests, the
screens in the simulator against a local server. Not verified on a strap or a signed phone: the serial writers, the
real key store path, turning on from a second phone.

Open, and not decided: which phone of several uploads a day; whether a desktop build takes invite
links.

## 2. Friends screens as redrawn

**The layout is the Android owner's to decide.** On iOS the tab is now a measured copy of Apple
Fitness's Sharing tab. Android's tab was drawn from its own mockups
([`2026-10-08-friends-tab/mockups`](../2026-10-08-friends-tab/mockups)). Adopt the structure below,
keep the present one, or draw a third: none of that needs the Swift side's agreement. What must match
whichever is chosen is the behaviour and the figures.

Structure on iOS (`bd09bd79`; all in `Strand/Friends/`):

- **List** (`FriendsView.swift`, `FriendsBoard.swift`): Highlights (friends' recent workouts, paging
  side by side), then one card per person with the day's figure, a second line with the amount behind
  it (time asleep under Sleep, workout minutes under Strain, nothing under Recovery) and three rings.
  A menu sorts by Name, Recovery, Strain, Sleep or Workouts and, with it, picks what the cards show.
  The wearer is on the list as "Me", filed alphabetically under that word in the active language.
- **A friend's page** (`FriendDetailView.swift`): picture and name, the day's three figures with
  rings, time asleep and workout minutes (heart rate when shared), the last seven days as seven rings
  (tapping one shows that day), the workouts of those days. The menu holds only Remove Friend.
- **The sheet** behind the bar button (`FriendsSheets.swift`, `FriendsInvite.swift`): "Sharing With"
  (Invite a Friend, then each friend, opening their page), the field for a code, the invites waiting,
  and a link to the wearer's own sharing. Requests lead the sheet when there are any.
- Explanatory footers were removed from the sheet, the sharing page, the phones page and the invite
  page. The page that waits for the strap is titled "Waiting for Your Strap" and says it finishes by
  itself once the app has read the strap. No Competitions.

Rules that are logic, not layout. These are pure functions with tests; port them with their cases:

- `FriendsBoard.rows` (`StrandTests/FriendsBoardTests.swift`, 19 cases): the highest figure leads; equal
  figures fall back to the name; a figure from an earlier day is shown, said to be that day's, and
  listed after every figure from today; people with no figure come last by name; a section a person
  does not share is not guessed at; by Workouts the most energy leads, a day without a workout counts
  as zero, and workouts that carry no energy are listed without a figure.
- `FriendsHighlights.recent` (`FriendsHighlightsTests.swift`, 6 cases): which workouts make a
  highlight.
- The friend page's week (`FriendPageTests.swift`, 10 cases): seven days, oldest first, ending on the
  wearer's day unless the friend's calendar is ahead; a day uploaded twice is read once; a workout of
  the past week is dated by its weekday.
- Score names: the cards show Recovery / Strain / Sleep; a person goes by their given name in a
  sentence.

New and changed strings are the Friends entries of `Strand/Resources/Localizable.xcstrings` in
`bd09bd79` (English and Russian). Android carries every key in every locale, so the other locales need
their own translations.

Seen on the Swift side: the simulator, beside the Fitness app, in English and Russian, light and dark.

## 3. Learned sleep stage model

What changed for the wearer: the stages inside a detected night (awake, light, deep, REM) are now
labelled by a model fit to human-scored sleep studies instead of the hand-set recipe
(`SleepStagerV2`). When the wearer slept is still the detector's answer; only the hypnogram inside an
accepted window changes. There is no switch. Against scored PSG, with every person staged by a model
trained without them (122 people): kappa 0.562, against 0.356 for the recipe; stage shares within 1.1
points of the PSG, where the recipe put REM 5.4 points too high.

This is stored data (the hypnogram, and every daily figure derived from it), so the parity rule
applies in full: for the same streams, Kotlin must produce the same stage for every epoch as Swift.

### The pipeline

All of it is pure Swift in `Packages/StrandAnalytics/Sources/StrandAnalytics/`
(`SleepStageFeatures.swift`, `SleepStageLearned.swift`, `SleepStageTrees.swift`), with no model
runtime behind it, so every step has a Kotlin twin to write:

1. `SleepStageFeatures.rows(start:end:grav:hr:rr:)` — one row per 30 s epoch, 26 features
   (`SleepStageFeatures.names`), read from the window and 1800 s either side (`reach`). Motion is in
   absolute g, heart rate is night-relative on 10 s bins, the `rr_*` features are optional. A value
   that could not be measured is `-999` (`missing`), never NaN.
2. `SleepStageContext.firstInput` — each row plus the surroundings of 16 of its features: 186 columns
   (`firstNames`).
3. The first model: 186 columns in, four probabilities out.
4. `SleepStageContext.secondInput` — the first input plus the first model's probabilities around the
   epoch: 246 columns (`secondNames`).
5. The second model: 246 columns in, four probabilities out.
6. `SleepStageDecoder.decode(_:prior:weight:smoothing:)` — divides out the class prior and decodes
   the night. The settings come with the model: `classPrior` `0.048777,0.556483,0.190340,0.204401`,
   `priorWeight` `0.15`, `smoothing` `0.00`, in the order `wake,light,deep,rem`.
7. `SleepStageLearned.stageSession` — tiles the labels into segments from `start` to `end`, and
   returns nothing when the model should not stage the night: no model installed, motion on fewer
   than 80 % of the window's seconds or heart rate on fewer than 80 % of its 10 s bins
   (`minCoverage`), or an answer that is not one finite row of four per epoch. The caller then stages
   with the recipe exactly as before.

### The models

On Apple platforms the pair is `Strand/SleepModel/SleepStageFirst.mlmodel` and
`SleepStageSecond.mlmodel`, run through Core ML. Each is a feature vectorizer followed by a
boosted-tree classifier: 1200 trees of depth 4 (300 rounds, one tree per class per round).

Core ML does not run on Android, so the same trees are already written out as text:

- `android/app/src/main/assets/sleepstage/SleepStageFirst.trees` and `SleepStageSecond.trees`, about
  800 kB each. They are the model. Nothing in them is refit or rounded: every threshold and leaf
  value is the shortest decimal that reads back as the same double.
- `Tools/SleepML/export_trees.py` writes them from the `.mlmodel` files (standard library only, runs
  on any OS). Its header describes the format line by line.
- `Packages/StrandAnalytics/Sources/StrandAnalytics/SleepStageTrees.swift` is the Swift reader and
  evaluator of that format. **The Kotlin evaluator is its twin: port that file**, about 150 lines.

The arithmetic, which both platforms must do in the same order:

1. Each class's sum starts at its `base` value.
2. For every tree in file order, start at node 0 of the tree. At a branch `b <column> <threshold>
   <yes> <no>`, go to node `<yes>` when `row[column] < threshold`, compared as doubles, else to
   `<no>`. At a leaf `l <class> <value>`, add the value to that class's sum.
3. The probabilities are `exp(sum - largest sum)` for each class, divided by the total of those four
   added in class order.
4. The file lists its classes as `deep light rem wake`. Everything in `StrandAnalytics` uses
   `wake, light, deep, rem` (`SleepStageDecoder.stages`): reorder by name, as
   `SleepStageTrees.probabilities(_:order:)` does.

Other rules of the reader:

- The columns in the file are the input order. A file whose columns are not exactly `firstNames`
  (first model) or `secondNames` (second) is refused, and so is a pair whose second file does not
  carry `meta classPrior`, `meta priorWeight`, `meta smoothing` and `meta classes
  wake,light,deep,rem` (`SleepStageModel.init(first:second:version:)`). A refused pair installs no
  model, and every night is staged by the recipe.
- A malformed file is refused whole (`SleepStageTreesTests.testAnythingMalformedIsRefused` lists the
  cases): a model half-read would stage nights wrongly without saying so.
- A missing value is `-999`, an ordinary number below every threshold. There is no NaN handling.
- The version of the pair (the cache key and the re-stage marker) on Apple platforms is a hash of the
  compiled Core ML bytes. On Android use the two `source` lines of the files (each is the SHA-256 of
  the `.mlmodel` it was written from), joined. The version stays on the phone; no backup carries it.

Attribution travels with the weights, in each file's comment lines: Wearanize+ OA (Radboud University,
CC BY 4.0) and sleep-accel (Walch et al., PhysioNet, ODC-By 1.0). Keep those lines when the files are
copied or repackaged, and name both in the app's licence list.

When the models are refit, the `.mlmodel` files, the two `.trees` files and the oracle below change
together. `StrandTests/SleepStageModelStoreTests.swift` (`SleepStageTreesAgainstCoreMLTests`) fails
on the Swift side when a `.trees` file was not written from the bundled `.mlmodel`.

### Where the app calls it

Swift, in `01b11d33`; each has a Kotlin counterpart to find:

- `SleepStager.detectSleep` asks `SleepStageLearned.stageSession` first and falls back to
  `SleepStagerV2` or the first recipe. The detect cache key gained the model version.
- `Repository.restageFromRaw` (an edited night re-staged inside the wearer's bounds) does the same;
  its witness string gained `model=<version>`.
- The Sleep score's restorative target moved from 0.50 to 0.38
  (`AnalyticsEngine.RestScorer.restorativeTarget`). The recipe met 0.50 only because it over-calls
  REM; in scored PSG deep and REM together are about 41 % of sleep. Kotlin:
  `RestScorer.restorativeTargetShare` in `analytics/AnalyticsEngine.kt`, read also by
  `ui/sleep/SleepLogic.kt`. Move it in the same commit that turns the model on, not before: under the
  recipe 0.38 would hand out full marks too easily.
- Every stored night is staged again once per model version
  (`IntelligenceEngine.runSleepStageModelRestageIfNeeded`, key
  `intelligence.sleepStageModel.restagedVersion`). Without it a history reads as two stagers joined
  at the day the model arrived: REM near 30 % before it and near 20 % after. Edited and dismissed
  sleep stay protected; a day with no raw streams is left as it is; the version is recorded only once
  the pass has persisted.
- Two diagnostic lines say that the model stages dense nights and the recipe is its fallback
  (`AnalyticsEngine.sleepMotionLine` caller, `DebugDataDiagnostics`). They do not claim which of the
  two staged a given night, because that is not recorded.

### Proving parity

One oracle covers the whole path: `android/app/src/test/resources/sleep_stage_oracle.json` (its
`about` field describes every key). It is one invented two-hour night, not anyone's recording, with
a few seconds of motion, forty of pulse and ten minutes of beat intervals left out so that missing
data is exercised. It holds the night's streams and, for the same night, what each step must
produce:

| Key | Step it checks |
|---|---|
| `gravity`, `heartRate`, `beatIntervals`, `start`, `end` | the input |
| `features`, `epochStarts` (240 epochs, 26 columns, `null` where not measured) | `SleepStageFeatures.rows` |
| `firstInput` (the 186 columns, for `sampleEpochs`) | `SleepStageContext.firstInput` |
| `firstProbabilities` | the first model's trees |
| `neighbours` (the 60 columns the second model adds, for `sampleEpochs`) | `SleepStageContext.secondInput` |
| `secondProbabilities` | the second model's trees |
| `stages` (one per epoch; all four stages occur) | `SleepStageDecoder.decode` |
| `segments` | `SleepStageLearned.stageSession` |

The Kotlin test reads that file and asserts each step in order, so a mismatch names the step that
broke. Numbers that pass through `log10` or `exp` may differ in the last place between platforms:
compare them to 1e-9 (the Swift test's `close`). `epochStarts`, `stages` and `segments` must be
equal exactly.

How the three implementations are tied together:

- `SleepStageTreesTests` (Swift package) stages the night with `SleepStageTrees` and must reproduce
  the oracle. It is also what writes it (`SLEEP_STAGE_ORACLE_WRITE=1`).
- `SleepStageTreesAgainstCoreMLTests` (`StrandTests`, on a Mac) stages the same night with the Core
  ML pair the iPhone runs. On 2026-10-11 Core ML's probabilities stood 2e-17 from the trees' own,
  the last place of a double, and every epoch's stage was equal. So the text files are the iPhone's
  model, not an approximation of it.
- The Kotlin test against the same file is the third side, and the one this task adds.

For the pieces around the trees, the Swift tests carry more cases than the one night does:
`SleepStageFeaturesTests.swift`, `SleepStageContextTests.swift` (9), `SleepStageLearnedTests.swift`
(9: the coverage gate, the tiling, the refusals) and `SleepStageTreesTests.swift` (6). Port those too.

The requirement on speed: no visible lag on an iPhone 11-class phone. Measured on an M1, CPU only,
for a 9.6 hour night: about 0.6 s end to end through Core ML. Measure the Kotlin path on a mid-range
phone before the one-time re-stage ships.

Seen on the Swift side: through the app's own pass on a Mac against a copy of one wearer's database,
60 of 60 sessions kept their bounds and were re-staged (pooled wake 5.9 %, light 56.3 %, deep 17.4 %,
REM 20.4 %). Not measured on a phone: the speed, and the one-time pass.

## 4. Step auto-calibration starts only in a real walk

`ae1f9884`. Twin: `StepAutoCalibrator.isWalkingNow` in
`android/app/src/main/java/com/noop/ble/StepAutoCalibrator.kt` (its comment names the Swift twin).

The old rule called it walking when the WHOOP 4.0 counter had added 10 ticks in 12 seconds. The
counter holds a walk's first steps back and releases them in one record of 11 to 14, so a few steps
between two rooms passed. On one wearer all four daily bursts went to such moments, nothing was
measured, and the daily cap then blocked real walks.

The new rule: the newest history must be fresh and must end with 45 seconds of one unbroken walk
(`walkingWindowSeconds = 45`): no one-second increment above the steady rate
(`maxSteadyTicksPerSecond`), no flat run longer than 2 seconds (`walkingPauseSeconds = 2`), and at
least 1 tick a second overall (`walkingMinimumTicksPerSecond = 1`). `walkingMinimumTicks` is gone.

Port by oracle: compile the Swift function standalone over the cases of
`StrandTests/StepAutoCalibratorTests.swift` and paste its answers into the Kotlin test.

Seen on the Swift side: replayed over one wearer's four days of history (12,959 start seconds allowed,
3 of them on a day spent at home; the old rule allowed 36,857 and 2,899). Not yet seen to start a
burst in a real outdoor walk.

## 5. Small fixes to check against Kotlin

- **Default date of birth (`9e0f7b81`).** On iOS a start before the phone's first unlock reads every
  stored setting as absent while writes still land, and the profile wrote its age-30 default over the
  wearer's date of birth. Now only a date that came from storage is written back. Android: check
  whether the profile writes a default back on first read, and whether any component can start before
  the first unlock (direct boot). If neither holds there is nothing to do; say so in the commit or
  skip it.
- **Raw strap records (`fd027288`).** iOS gained a Test Centre switch, "Keep raw strap records", and
  the 50 MB cap on kept raw frames is now applied after each finished offload (nothing applied it
  before). Android already has a raw-capture switch in `TestCentreScreen.kt`; check that what it keeps
  is bounded while the switch stays on.

## 6. Tab bar and glyphs

**The bar is the Android owner's to decide.** On iPhone it is now three tabs and the search circle:
Summary, Workouts, Friends, with Browse behind the search role (`59a27c36`). Sleep is no longer a
tab: it is a row in Browse and the Summary's sleep card. The selected tab takes the Exercise ring's
green (lime on a dark bar); each tab's own content keeps the app's accent.

Android's bar is Summary, Sleep, Workouts, Coach, Friends (`ui/MainTabs.kt`), with Browse moved to the
top of Settings on 2026-10-09. Whether Android follows (Sleep out of the bar, three tabs) or keeps
its bar needs nobody's agreement; the two bars already differ on purpose.

Independent of that decision (`a8cc7320`, and the Browse rows of `59a27c36`): a metric now carries one
glyph everywhere, the glyph of the Health category it files under (`KeyMetric.healthCategory`), on its
Summary card, in a Summary highlight and on its own page. Check that Android does not show two
different icons for one metric between those places.
