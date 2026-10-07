# WHOOP 4.0 Firmware Step Counter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show counted steps on a WHOOP 4.0 by reading the strap's own cumulative step counter, instead of the motion-volume estimate.

**Architecture:** The v24 history decoder reads a `u32` at frame offset 92 and the history stream extractor turns it into the existing `StepSample` rows, so the 5/MG step pipeline (store, daily total, resolver, UI) picks a 4.0 up unchanged. The shared plausibility gate changes its time base so the pedometer's buffered release at the start of a walk is kept.

**Tech Stack:** Swift packages `WhoopProtocol` and `StrandAnalytics` (XCTest, `swift test`), the `Strand`/`NOOPiOS` app targets (XcodeGen + `xcodebuild`), one Kotlin decoder twin in `android/`.

Spec: `docs/superpowers/specs/2026-10-03-whoop4-step-counter-design.md`

## Global Constraints

- All repository text is English: code comments, doc comments, docs. Neutral, third-person voice.
- Do not commit unless the user asks. The working tree on `main` carries unrelated uncommitted work, so when a commit is requested stage only the files a task lists, never `git add -A`.
- Never push. Pushing is the user's call.
- No JDK on this machine: the Kotlin edit in Task 1 cannot be compiled or tested locally. Keep it to the lines shown.
- New declarations go after the previous declaration's closing brace, with their doc comment attached directly above them (`Tools/doc_comment_lint.py` counts detached doc comments per file).
- The counter is read only from a record whose own version byte is 24.
- Evidence the code rests on: one strap, 1,203 records on 2026-10-03, counter 45630 to 45894.

## File Structure

| File | Change |
|---|---|
| `Packages/WhoopProtocol/Sources/WhoopProtocol/PostHooks.swift` | Decode `step_counter` in the `historical_data` hook |
| `Packages/WhoopProtocol/Sources/WhoopProtocol/HistoricalStreams.swift` | Emit `StepSample` for it |
| `Packages/WhoopProtocol/Sources/WhoopProtocol/Streams.swift` | `StepSample` doc comment |
| `Packages/WhoopProtocol/Tests/WhoopProtocolTests/Whoop4StepCounterTests.swift` | New: decode tests on real frames |
| `Packages/WhoopProtocol/Tests/WhoopProtocolTests/Resources/decoder_oracle.json` | Two batch expectations |
| `android/app/src/test/resources/decoder_oracle.json` | Byte-identical copy of the above |
| `android/app/src/main/java/com/noop/protocol/HistoricalStreams.kt` | Kotlin decoder twin |
| `Packages/StrandAnalytics/Sources/StrandAnalytics/StepsCounter.swift` | Gate time base |
| `Packages/StrandAnalytics/Sources/StrandAnalytics/SleepAwareStepCounter.swift` | Carry last-moved time |
| `Packages/StrandAnalytics/Tests/StrandAnalyticsTests/StepsCounterTests.swift` | Gate cases |
| `Packages/StrandAnalytics/Tests/StrandAnalyticsTests/SleepAwareStepCounterTests.swift` | Release and paging cases |
| `Packages/StrandAnalytics/Tests/StrandAnalyticsTests/Whoop4StepCaptureTests.swift` | New: the captured series |
| `Packages/StrandAnalytics/Sources/StrandAnalytics/StepsEstimateEngine.swift` | Header comment |
| `Strand/Devices/DeviceReadsView.swift` | Steps row for a 4.0 |
| `docs/PROTOCOL_SENSORS.md` | v24 table row |

Why the Kotlin decoder is in scope although the decision was Apple-only: `decoder_oracle.json` pins `steps: 0` for two 104-byte v24 batches, `DecoderOracleTests.testOracleCopiesAreIdentical` requires the Swift and Android copies to be byte-identical, and the Android test reads the same file. The Swift decoder cannot change without the oracle, and the oracle cannot change without the Kotlin decoder. The gate in Task 2 has no shared fixture and stays Swift-only.

---

### Task 1: Decode and store the counter

**Files:**
- Create: `Packages/WhoopProtocol/Tests/WhoopProtocolTests/Whoop4StepCounterTests.swift`
- Modify: `Packages/WhoopProtocol/Sources/WhoopProtocol/PostHooks.swift` (the `historical_data` hook, after the line `fb.parsed["rr_intervals"] = .intArray(rrVals)`)
- Modify: `Packages/WhoopProtocol/Sources/WhoopProtocol/HistoricalStreams.swift` (after the `step_motion_counter` block, near line 327)
- Modify: `Packages/WhoopProtocol/Sources/WhoopProtocol/Streams.swift` (`StepSample` doc comment, near line 345)
- Modify: `Packages/WhoopProtocol/Tests/WhoopProtocolTests/Resources/decoder_oracle.json`
- Modify: `android/app/src/test/resources/decoder_oracle.json`
- Modify: `android/app/src/main/java/com/noop/protocol/HistoricalStreams.kt` (near lines 318 and 1042)

**Interfaces:**
- Consumes: `parseFrame(_:)`, `extractHistoricalStreams(_:deviceClockRef:wallClockRef:)`, `StepSample(ts:counter:activityClass:)`, the hook-local `u32(frame, off, limit)` reader and `version` constant.
- Produces: parsed key `"step_counter"` (`.int`) on a v24 record of 104 bytes, and one `StepSample(ts:, counter:)` per such record in `Streams.steps`. Task 2 relies on those rows having `activityClass == nil`.

- [ ] **Step 1: Write the failing test**

Create `Packages/WhoopProtocol/Tests/WhoopProtocolTests/Whoop4StepCounterTests.swift`:

```swift
import XCTest
@testable import WhoopProtocol

/// The WHOOP 4.0 firmware step counter: a cumulative u32 at frame offset 92 of the 104-byte v24
/// history record.
///
/// `walkStart` holds real on-wrist records from one strap, captured 2026-10-03 across the start of a
/// counted walk: the counter sits flat, releases 12 buffered steps in one record, then climbs. Type-47
/// records carry no name, serial or session token, so they are safe committed fixtures.
final class Whoop4StepCounterTests: XCTestCase {

    private let walkStart: [(hex: String, unix: Int, counter: Int)] = [
        ("aa6400a12f18051ffd5301f7dfc06a883c805422014d0000000000000000000060a004cda06ab63e8f023a3f" +
         "9ad11b3fb8ee0cbe0020a2c68f023a3f9ad11b3fb8ee0cbef4014202fd0240023101a003010c020c00000000" +
         "006c00014ab2000000000000bfc134d4", 1_791_025_143, 45_642),
        ("aa6400a12f180520fd5301f8dfc06a9037805422014f0000000000000000000060b30bdcd0beeb3d4889643f" +
         "3d22073f856bf4bc00c0bdc64889643f3d22073f856bf4bcf4014202fc023f023201a003010c020c00000000" +
         "006c000156b20000000000002ef6ff39", 1_791_025_144, 45_654),
        ("aa6400a12f180521fd5301f9dfc06aa03280542201510000000000000000000060ea03e1f0278c3db8d66e3f" +
         "295cde3ec3b53ebd00e0f546b8d66e3f295cde3ec3b53ebdf4014202fd023f023101a003010c020c00000000" +
         "0024000157b200000000000072d8f47e", 1_791_025_145, 45_655),
        ("aa6400a12f180522fd5301fadfc06ab02d80542201530000000000000000000061d50ddd40c1b23dc3456b3f" +
         "140eb83e5278953d0040d546c3456b3f140eb83e5278953df4014202fe023f023101a003010c020c01000000" +
         "0024000159b2000000000000118b600f", 1_791_025_146, 45_657),
        ("aa6400a12f180523fd5301fbdfc06ab828805422015400000000000000000000615204d3e0e6d13d9a79703f" +
         "3dbacc3e0a572a3d006082c69a79703f3dbacc3e0a572a3df4014202ff0240023101a003010c020c01000000" +
         "001300015ab2000000000000110452c3", 1_791_025_147, 45_658),
    ]

    /// A different strap, captured 2026-06-08 (the frame `Whoop4HistoricalV24HardwareTests` uses).
    private let otherStrapHex =
        "aa6400a12f18054c1c0a023ed0266a5037805418016d022b0234020000000000006b07ff00" +
        "85593c1f65cebed7b3e63eb85a5f3f000080401f65cebed7b3e63eb85a5f3f500264025d03" +
        "640229014009010c020c00000000000f0001c4020000000000008fdeb278"

    /// The synthetic 94-byte v24 record from `HistoricalV24Tests`: it ends before offset 92.
    private let shortV24Hex =
        "aa5a008e2f18000000000000f153650000000000003f0152030000000000000000dc053075" +
        "000000cdcc4c3dcdcccc3d5a657e3f00000040cdcc4c3dcdcccc3d5a657e3f504668428403" +
        "200364006400b80bb80b000000000000c25c1a88"

    /// `walkStart[1]` with its version byte set to 12 and the CRC32 recomputed.
    private let version12Hex =
        "aa6400a12f0c0520fd5301f8dfc06a9037805422014f0000000000000000000060b30bdcd0beeb3d4889643f" +
        "3d22073f856bf4bc00c0bdc64889643f3d22073f856bf4bcf4014202fc023f023201a003010c020c00000000" +
        "006c000156b20000000000005af393ab"

    private func bytes(_ s: String) -> [UInt8] {
        var out = [UInt8](); out.reserveCapacity(s.count / 2); var i = s.startIndex
        while i < s.endIndex { let j = s.index(i, offsetBy: 2)
            out.append(UInt8(s[i..<j], radix: 16)!); i = j }
        return out
    }

    func testCounterDecodesFromRealRecords() {
        for record in walkStart {
            let out = parseFrame(bytes(record.hex))
            XCTAssertEqual(out.crcOK, true)
            XCTAssertEqual(out.parsed["hist_version"]?.intValue, 24)
            XCTAssertEqual(out.parsed["unix"]?.intValue, record.unix)
            XCTAssertEqual(out.parsed["step_counter"]?.intValue, record.counter)
        }
    }

    func testCounterDecodesOnASecondStrap() {
        XCTAssertEqual(parseFrame(bytes(otherStrapHex)).parsed["step_counter"]?.intValue, 708)
    }

    func testShortRecordCarriesNoCounter() {
        let out = parseFrame(bytes(shortV24Hex))
        XCTAssertEqual(out.crcOK, true)
        XCTAssertNil(out.parsed["step_counter"])
        XCTAssertTrue(extractHistoricalStreams([out], deviceClockRef: 0, wallClockRef: 0).steps.isEmpty)
    }

    func testVersion12RecordCarriesNoCounter() {
        let out = parseFrame(bytes(version12Hex))
        XCTAssertEqual(out.crcOK, true)
        XCTAssertEqual(out.parsed["hist_version"]?.intValue, 12)
        XCTAssertEqual(out.parsed["heart_rate"]?.intValue, 79)   // the borrowed v24 map still decodes
        XCTAssertNil(out.parsed["step_counter"])
    }

    func testStreamsCarryOneStepSamplePerRecord() {
        let parsed = walkStart.map { parseFrame(bytes($0.hex)) }
        let streams = extractHistoricalStreams(parsed, deviceClockRef: 0, wallClockRef: 0)
        XCTAssertEqual(streams.steps, walkStart.map { StepSample(ts: $0.unix, counter: $0.counter) })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd Packages/WhoopProtocol && swift test --filter Whoop4StepCounterTests`

Expected: `testCounterDecodesFromRealRecords`, `testCounterDecodesOnASecondStrap` and `testStreamsCarryOneStepSamplePerRecord` FAIL (`nil` is not equal to the counter; `steps` is empty). The two "carries no counter" tests pass already.

- [ ] **Step 3: Decode the field**

In `PostHooks.swift`, inside `postHooks["historical_data"]`, directly after the line `fb.parsed["rr_intervals"] = .intArray(rrVals)` and before the `// Validate the v24-layout guess` comment, insert:

```swift
        // The firmware pedometer total: a cumulative u32 at 92..95 of the 104-byte v24 record, past the
        // DSP block. Read only when the record's OWN version byte is 24. Version 12 and the
        // unmapped-version fallback borrow this field map, and nothing shows their bytes there are a
        // counter. A shorter v24 record ends before the field, so the bounded read returns nil.
        if version == 24, let count = u32(frame, 92, limit) {
            fb.add(92, 4, "step_counter", "activity", value: .int(Int(count)),
                   note: "cumulative firmware step count")
            fb.parsed["step_counter"] = .int(Int(count))
        }
```

- [ ] **Step 4: Emit the stream row**

In `HistoricalStreams.swift`, directly after the closing brace of the `if let c = p["step_motion_counter"]?.intValue { … }` block, insert:

```swift
            // step_counter@92 is the WHOOP 4.0 firmware pedometer total (cumulative u32). The decoder
            // emits the key only for a v24 record long enough to carry it, so no other layout reaches
            // this branch. A WHOOP 4.0 record has no activity class.
            if let c = p["step_counter"]?.intValue {
                out.steps.append(StepSample(ts: ts, counter: c))
            }
```

In `Streams.swift`, replace the first two lines of the `StepSample` doc comment:

```swift
/// WHOOP 5/MG cumulative u16 step / motion counter (step_motion_counter@57). APPROXIMATE — the @57
/// step semantics are unverified against the official WHOOP app (#78). Mirrors Android StepSample.
```

with:

```swift
/// A cumulative step counter reading. WHOOP 5/MG: the u16 step / motion counter at
/// step_motion_counter@57, whose step semantics are unverified against the official WHOOP app (#78).
/// WHOOP 4.0: the u32 firmware pedometer total at step_counter@92 of the 104-byte v24 record, with no
/// activity class. Consumers take wrap-aware differences, so both widths read the same way. Mirrors
/// Android StepSample.
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd Packages/WhoopProtocol && swift test --filter Whoop4StepCounterTests`

Expected: 5 tests, 0 failures.

- [ ] **Step 6: Update the shared decoder oracle**

Both 104-byte v24 batches now assemble one step row. Run from the repository root:

```bash
python3 - <<'EOF'
import re
swift = 'Packages/WhoopProtocol/Tests/WhoopProtocolTests/Resources/decoder_oracle.json'
android = 'android/app/src/test/resources/decoder_oracle.json'
text = open(swift).read()
for name in ('whoop4_v24_single', 'whoop4_v24_high_bit_unix_survives'):
    start = text.index('"name": "%s"' % name)
    end = text.index('"steps": 0', start) + len('"steps": 0')
    assert '"name":' not in text[start + 10:end], name   # stay inside this batch
    text = text[:end - 1] + '1' + text[end:]
open(swift, 'w').write(text)
open(android, 'w').write(text)
EOF
git diff --stat -- Packages/WhoopProtocol/Tests/WhoopProtocolTests/Resources/decoder_oracle.json android/app/src/test/resources/decoder_oracle.json
```

Expected: each file shows `2 insertions(+), 2 deletions(-)`.

- [ ] **Step 7: Mirror the decoder in Kotlin**

In `android/app/src/main/java/com/noop/protocol/HistoricalStreams.kt`, inside `decodeHistorical`, directly after the line that reads `gravity_z` (`layout.gravityZOff?.let { off -> frame.histF32(off, limit)?.let { out["gravity_z"] = it } }`), insert:

```kotlin

    // step_counter@92: the firmware pedometer total, a cumulative u32 past the DSP block of the 104-byte
    // v24 record. Only when the record's OWN version byte is 24: v12 and the unmapped-version fallback
    // borrow this layout, and nothing shows their bytes there are a counter. A shorter record ends before
    // the field and the bounded read returns null. Mirrors Swift PostHooks "historical_data".
    if (version == 24) frame.histU32(92, limit)?.let { out["step_counter"] = it }
```

In the same file, directly after the closing brace of the `p.intOrNull("step_motion_counter")?.let { c -> … }` block, insert:

```kotlin
                // step_counter@92 is the WHOOP 4.0 firmware pedometer total (cumulative u32). The decoder
                // emits the key only for a v24 record long enough to carry it. A WHOOP 4.0 record has no
                // activity class.
                p.intOrNull("step_counter")?.let { c -> steps.add(StepRow(ts, c)) }
```

This edit is not compiled locally. It uses only names already in scope at both sites (`version`, `frame.histU32`, `limit`, `out`, `p.intOrNull`, `steps`, `StepRow`, `ts`); confirm each by reading the surrounding ten lines before saving.

- [ ] **Step 8: Run the whole package**

Run: `cd Packages/WhoopProtocol && swift test`

Expected: 0 failures, including `DecoderOracleTests` (batch counts and `testOracleCopiesAreIdentical`).

- [ ] **Step 9: Commit, if the user has asked for commits**

```bash
git add Packages/WhoopProtocol/Sources/WhoopProtocol/PostHooks.swift \
        Packages/WhoopProtocol/Sources/WhoopProtocol/HistoricalStreams.swift \
        Packages/WhoopProtocol/Sources/WhoopProtocol/Streams.swift \
        Packages/WhoopProtocol/Tests/WhoopProtocolTests/Whoop4StepCounterTests.swift \
        Packages/WhoopProtocol/Tests/WhoopProtocolTests/Resources/decoder_oracle.json \
        android/app/src/test/resources/decoder_oracle.json \
        android/app/src/main/java/com/noop/protocol/HistoricalStreams.kt
git commit -m "feat(protocol): decode the WHOOP 4.0 firmware step counter at v24 offset 92"
```

---

### Task 2: Keep the buffered release in the step total

**Files:**
- Modify: `Packages/StrandAnalytics/Sources/StrandAnalytics/StepsCounter.swift`
- Modify: `Packages/StrandAnalytics/Sources/StrandAnalytics/SleepAwareStepCounter.swift` (`Accumulator`)
- Modify: `Packages/StrandAnalytics/Tests/StrandAnalyticsTests/StepsCounterTests.swift`
- Modify: `Packages/StrandAnalytics/Tests/StrandAnalyticsTests/SleepAwareStepCounterTests.swift`
- Create: `Packages/StrandAnalytics/Tests/StrandAnalyticsTests/Whoop4StepCaptureTests.swift`

**Interfaces:**
- Consumes: `StepSample(ts:counter:activityClass:)` rows with `activityClass == nil` from Task 1.
- Produces: `StepsCounter.confirmationWindowSeconds: Int` (8) and `StepsCounter.isPlausibleDelta(previousTs: Int, currentTs: Int, lastMovedTs: Int, delta: Int) -> Bool`. The old three-argument form is removed; its only callers are `StepsCounter.stepsInWindow` and `SleepAwareStepCounter.Accumulator.acceptPage`. Public entry points keep their signatures.

- [ ] **Step 1: Write the failing tests**

Append to `StepsCounterTests`, before the final closing brace:

```swift

    func testBufferedReleaseAfterFlatRunCounts() {
        // A pedometer holds the first steps of a walk back, then publishes them in one record. Ten flat
        // seconds, a 12-step release, then one more step: all 13 are real.
        let flat = (0...9).map { step($0, 100) }
        XCTAssertEqual(StepsCounter.stepsInWindow(flat + [step(10, 112), step(11, 113)]), 13)
    }

    func testReleaseIsBoundedByTheConfirmationWindow() {
        // The credit stops at 8 s x 4 ticks = 32, however long the counter was flat.
        let flat = (0...60).map { step($0, 100) }
        XCTAssertEqual(StepsCounter.stepsInWindow(flat + [step(61, 132)]), 32)
        XCTAssertNil(StepsCounter.stepsInWindow(flat + [step(61, 133)]))
    }

    func testSpikeRightAfterMovementIsStillRejected() {
        // The counter moved one second ago, so there is no flat run to credit: +8 in a second is dropped.
        XCTAssertEqual(StepsCounter.stepsInWindow([step(0, 100), step(1, 102), step(2, 110)]), 2)
    }
```

Append to `SleepAwareStepCounterTests`, before the final closing brace:

```swift

    func testBufferedReleaseAfterFlatRunCounts() {
        let flat = (0...9).map { sample($0, 500, nil) }
        let count = SleepAwareStepCounter.count(flat + [sample(10, 512, nil), sample(11, 514, nil)],
                                                sleepSessions: [])
        XCTAssertEqual(count.totalTicks, 14)
        XCTAssertEqual(count.rejectedImplausibleTicks, 0)
    }

    func testLastMovedTimeCarriesAcrossPages() {
        let accumulator = SleepAwareStepCounter.Accumulator(sleepSessions: [], hasActivityClasses: false)
        accumulator.acceptPage((0...9).map { sample($0, 500, nil) })
        accumulator.acceptPage([sample(10, 512, nil)])
        XCTAssertEqual(accumulator.finish().totalTicks, 12)
    }
```

Create `Packages/StrandAnalytics/Tests/StrandAnalyticsTests/Whoop4StepCaptureTests.swift`:

```swift
import XCTest
@testable import StrandAnalytics
import WhoopProtocol

/// The WHOOP 4.0 capture of 2026-10-03: 20 minutes of 1 Hz history across a seated stretch, two
/// counted walks and a bout of hand-waving. The firmware counter rose 45630 -> 45894, and every bout
/// opened with an 11 or 12 step release. The consecutive-sample rate gate kept 217 of those 264.
final class Whoop4StepCaptureTests: XCTestCase {

    private let firstTs = 1_791_024_239
    private let spanSeconds = 1_204

    /// (seconds since `firstTs`, counter) at every record where the counter changed. Index 0 is the
    /// first record. The series is rebuilt at 1 Hz, flat between entries.
    private let changes: [(Int, Int)] = [
        (0, 45630), (27, 45642), (905, 45654), (906, 45655), (907, 45657), (908, 45658), (909, 45660),
        (910, 45661), (911, 45663), (912, 45664), (913, 45666), (914, 45667), (915, 45669), (916, 45672),
        (917, 45673), (918, 45674), (919, 45676), (920, 45678), (921, 45679), (922, 45682), (924, 45684),
        (925, 45685), (926, 45687), (927, 45689), (928, 45691), (929, 45693), (930, 45695), (931, 45696),
        (932, 45699), (933, 45701), (934, 45702), (935, 45704), (936, 45705), (937, 45707), (939, 45709),
        (940, 45710), (941, 45714), (942, 45715), (943, 45716), (944, 45718), (945, 45719), (946, 45721),
        (947, 45722), (948, 45724), (949, 45726), (950, 45727), (951, 45729), (952, 45730), (953, 45732),
        (955, 45734), (956, 45736), (957, 45737), (958, 45738), (959, 45740), (960, 45743), (962, 45745),
        (964, 45747), (965, 45751), (966, 45752), (967, 45753), (969, 45756), (970, 45757), (971, 45759),
        (972, 45761), (973, 45763), (974, 45764), (975, 45766), (976, 45767), (977, 45768), (978, 45769),
        (1052, 45780), (1053, 45782), (1054, 45783), (1055, 45785), (1056, 45787), (1057, 45788), (1058, 45790),
        (1059, 45792), (1060, 45793), (1061, 45795), (1062, 45797), (1063, 45798), (1064, 45800), (1065, 45803),
        (1066, 45805), (1067, 45806), (1068, 45807), (1069, 45809), (1070, 45810), (1071, 45812), (1072, 45813),
        (1073, 45815), (1074, 45816), (1075, 45818), (1076, 45819), (1077, 45820), (1078, 45823), (1079, 45824),
        (1080, 45826), (1081, 45827), (1082, 45830), (1083, 45831), (1084, 45832), (1160, 45844), (1161, 45845),
        (1162, 45846), (1163, 45848), (1164, 45852), (1165, 45854), (1166, 45856), (1167, 45858), (1168, 45860),
        (1169, 45862), (1170, 45864), (1171, 45866), (1172, 45868), (1173, 45870), (1174, 45872), (1175, 45874),
        (1176, 45876), (1177, 45878), (1178, 45880), (1179, 45882), (1180, 45885), (1181, 45887), (1182, 45889),
        (1183, 45891), (1184, 45892), (1185, 45894),
    ]

    private var samples: [StepSample] {
        var counterAt: [Int: Int] = [:]
        for (offset, counter) in changes { counterAt[offset] = counter }
        var counter = changes[0].1
        return (0...spanSeconds).map { offset in
            if let changed = counterAt[offset] { counter = changed }
            return StepSample(ts: firstTs + offset, counter: counter)
        }
    }

    func testEveryCountedStepSurvivesTheGate() {
        XCTAssertEqual(StepsCounter.stepsInWindow(samples), 264)
    }

    func testSleepAwareCounterAgrees() {
        let count = SleepAwareStepCounter.count(samples, sleepSessions: [])
        XCTAssertEqual(count.totalTicks, 264)
        XCTAssertEqual(count.rejectedImplausibleTicks, 0)
    }

    func testDailyTotalIsTheCounterRise() {
        let daily = AnalyticsEngine.analyzeDay(day: "2026-10-03", steps: samples,
                                               profile: UserProfile()).daily
        XCTAssertEqual(daily.steps, 264)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd Packages/StrandAnalytics && swift test --filter 'StepsCounterTests|SleepAwareStepCounterTests|Whoop4StepCaptureTests'`

Expected FAIL: `testBufferedReleaseAfterFlatRunCounts` (1 instead of 13; 2 instead of 14), `testReleaseIsBoundedByTheConfirmationWindow` (nil instead of 32), `testLastMovedTimeCarriesAcrossPages` (0 instead of 12), and the three capture tests (217 instead of 264). `testSpikeRightAfterMovementIsStillRejected` passes already.

- [ ] **Step 3: Change the gate's time base**

In `StepsCounter.swift`, replace everything from the line `/// Absolute reboot/wrap guard, independent from the per-second plausibility gate below.` through the closing brace of `stepsInWindow` with:

```swift
    /// Absolute reboot/wrap guard, independent from the per-second plausibility gate below.
    public static let maxStepDelta = 512
    public static let maxTicksPerSecond = 4

    /// Seconds a pedometer may hold steps back before publishing them. The WHOOP 4.0 counter stays flat
    /// while it confirms a walk, then releases the buffered steps in one record (11 or 12 on the
    /// 2026-10-03 capture). At a slow 1.5 steps per second that is eight seconds.
    public static let confirmationWindowSeconds = 8

    /// Rate plausibility for one counter increment. The rate is measured over the time since the counter
    /// last moved, capped at `confirmationWindowSeconds`, and never over less than the gap to the previous
    /// sample. A buffered release after a flat run is therefore judged against the seconds it covers,
    /// while an increment right after another one still gets `maxTicksPerSecond` for each elapsed second.
    /// `lastMovedTs` is the timestamp of the latest sample whose counter differed from its predecessor,
    /// or of the window's first sample when none has yet.
    ///
    /// Kotlin twin: `StepsCounter.isPlausibleDelta`, which has not adopted `lastMovedTs` and still
    /// measures over the consecutive-sample gap alone.
    static func isPlausibleDelta(previousTs: Int, currentTs: Int, lastMovedTs: Int, delta: Int) -> Bool {
        guard delta >= 1, delta < maxStepDelta else { return false }
        let elapsed = currentTs - previousTs
        guard elapsed > 0 else { return false }
        let credit = max(elapsed, min(currentTs - lastMovedTs, confirmationWindowSeconds))
        let rateAllowance = credit >= maxStepDelta / maxTicksPerSecond
            ? maxStepDelta - 1
            : credit * maxTicksPerSecond
        return delta <= rateAllowance
    }

    /// Raw wrap-aware locomotion-tick total across `samples`. When any sample carries `activityClass`, each
    /// positive increment is attributed to the later sample and retained only for walk/run. When the whole
    /// window is legacy-unclassed, all valid increments retain the historical counter-only fallback. Sorts
    /// by `ts` internally and returns `nil` for fewer than two samples or no retained movement.
    public static func stepsInWindow(_ samples: [StepSample]) -> Int? {
        let sorted = samples.sorted { $0.ts < $1.ts }
        if sorted.count < 2 { return nil }
        let hasActivityClasses = hasActivityClasses(sorted)
        var total = 0
        var lastMovedTs = sorted[0].ts
        for i in 1..<sorted.count {
            let delta = (sorted[i].counter - sorted[i - 1].counter) & 0xFFFF  // wrap-aware u16 increment
            let isLocomotion = shouldCountDelta(
                activityClass: sorted[i].activityClass,
                hasActivityClasses: hasActivityClasses)
            if isLocomotion && isPlausibleDelta(
                previousTs: sorted[i - 1].ts, currentTs: sorted[i].ts,
                lastMovedTs: lastMovedTs, delta: delta) {
                total += delta
            }
            if delta != 0 { lastMovedTs = sorted[i].ts }
        }
        return total > 0 ? total : nil
    }
```

In the same file, replace the type's header comment line

```swift
/// Kept byte-for-byte in lockstep with the Kotlin twin `StepsCounter.stepsInWindow`.
```

with:

```swift
/// The Kotlin twin `StepsCounter.stepsInWindow` matches except for the rate gate: it has not adopted the
/// last-moved time base, so it drops a buffered release that this side keeps.
```

- [ ] **Step 4: Carry the last-moved time through the paged accumulator**

In `SleepAwareStepCounter.swift`, in `Accumulator`, replace

```swift
        private var previous: StepSample?
```

with:

```swift
        private var previous: StepSample?
        private var lastMovedTs: Int?
```

and replace the start of the loop in `acceptPage`

```swift
                guard let prior = previous else { previous = current; continue }
                guard current.ts > prior.ts else { continue }
                previous = current
                let delta = (current.counter - prior.counter) & 0xffff
                guard StepsCounter.shouldCountDelta(activityClass: current.activityClass,
                                                     hasActivityClasses: hasClasses) else {
                    rejectedClass += delta; continue
                }
                guard StepsCounter.isPlausibleDelta(previousTs: prior.ts, currentTs: current.ts,
                                                    delta: delta) else {
                    rejectedImplausible += delta; continue
                }
```

with:

```swift
                guard let prior = previous else { previous = current; lastMovedTs = current.ts; continue }
                guard current.ts > prior.ts else { continue }
                previous = current
                let delta = (current.counter - prior.counter) & 0xffff
                // Read the last-moved time BEFORE this sample updates it: the gate judges this increment
                // against the flat run that preceded it.
                let movedTs = lastMovedTs ?? prior.ts
                if delta != 0 { lastMovedTs = current.ts }
                guard StepsCounter.shouldCountDelta(activityClass: current.activityClass,
                                                     hasActivityClasses: hasClasses) else {
                    rejectedClass += delta; continue
                }
                guard StepsCounter.isPlausibleDelta(previousTs: prior.ts, currentTs: current.ts,
                                                    lastMovedTs: movedTs, delta: delta) else {
                    rejectedImplausible += delta; continue
                }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd Packages/StrandAnalytics && swift test --filter 'StepsCounterTests|SleepAwareStepCounterTests|Whoop4StepCaptureTests|StepsDailyTests'`

Expected: 0 failures. The pre-existing cases must pass unmodified, in particular `testRejectsPhysicallyImpossibleOneSecondSpikeButAllowsSameTicksAcrossTime` and `testDiagnosticRejectionReasonsAreSeparated`.

- [ ] **Step 6: Run the whole package**

Run: `cd Packages/StrandAnalytics && swift test`

Expected: 0 failures.

- [ ] **Step 7: Commit, if the user has asked for commits**

```bash
git add Packages/StrandAnalytics/Sources/StrandAnalytics/StepsCounter.swift \
        Packages/StrandAnalytics/Sources/StrandAnalytics/SleepAwareStepCounter.swift \
        Packages/StrandAnalytics/Tests/StrandAnalyticsTests/StepsCounterTests.swift \
        Packages/StrandAnalytics/Tests/StrandAnalyticsTests/SleepAwareStepCounterTests.swift \
        Packages/StrandAnalytics/Tests/StrandAnalyticsTests/Whoop4StepCaptureTests.swift
git commit -m "fix(analytics): judge a step increment over the time since the counter last moved"
```

---

### Task 3: Surface, document, build

**Files:**
- Modify: `Strand/Devices/DeviceReadsView.swift:74`
- Modify: `Packages/StrandAnalytics/Sources/StrandAnalytics/StepsEstimateEngine.swift:4-12`
- Modify: `docs/PROTOCOL_SENSORS.md` (the "WHOOP 4 historical v24" table and the last paragraph of "WHOOP 4 sensor and record controls")

**Interfaces:**
- Consumes: nothing new. `Repository.resolvedSteps` and `SummaryMetricReading` already rank a measured strap total (`dailyMetric.steps`) above the phone import and the estimate, so no resolver change is needed. Verified by reading `Strand/Data/ResolvedSteps.swift` and `Strand/Summary/SummaryMetricReading.swift` (`case .steps`).
- Produces: nothing other tasks use.

Not changed on purpose: the `noRawCounter … e.g. WHOOP 4.0` line in `StepsEstimateEngine+Trace.swift`. It is pinned byte-for-byte against the Kotlin twin and still describes a 4.0 day that has no counter rows.

- [ ] **Step 1: Update the device capability row**

In `Strand/Devices/DeviceReadsView.swift`, replace

```swift
        LimitRow(feature: "Steps", spokenFeature: String(localized: "Steps"), whoop4: .partial, whoop5: .full),
```

with:

```swift
        // `.full` on a 4.0 as well: its 104-byte v24 record carries the firmware's own cumulative step
        // counter (`step_counter@92`), counted the same way as the 5/MG counter. The motion-volume
        // estimate only fills days that have no counter rows.
        LimitRow(feature: "Steps", spokenFeature: String(localized: "Steps"), whoop4: .full, whoop5: .full),
```

- [ ] **Step 2: Correct the estimate engine's header comment**

In `StepsEstimateEngine.swift`, replace

```swift
/// Estimate daily steps for a WHOOP 4.0 from the strap's MOTION, calibrated per-user against a phone
/// step count (Apple Health / Health Connect).
///
/// WHY THIS IS A CALIBRATED ESTIMATE, NOT A PEDOMETER. A WHOOP 4.0 does not send a step count over BLE,
/// and the accelerometer/gravity data we DO get is sparse (~one vector per stored record, roughly minute
/// granularity) — far below the ~25–50 Hz a true step counter needs to see individual footfalls. So we
/// cannot count steps. What we CAN measure is movement VOLUME (how much the gravity vector moved over the
/// day), and we map that volume to steps with a coefficient learned from days where the phone ALSO counted
/// steps. The output is always framed as an estimate.
```

with:

```swift
/// Estimate daily steps for a WHOOP 4.0 from the strap's MOTION, calibrated per-user against a phone
/// step count (Apple Health / Health Connect). Used for days that have no firmware step counter rows:
/// history synced before `step_counter@92` was decoded, or a record layout that does not carry it. A day
/// with counter rows is counted by `StepsCounter` instead and outranks this estimate.
///
/// WHY THIS IS A CALIBRATED ESTIMATE, NOT A PEDOMETER. The gravity vector arrives once per stored record
/// (1 Hz), far below the ~25–50 Hz a step detector needs to see individual footfalls, so steps cannot be
/// counted from it. What it does give is movement VOLUME (how much the gravity vector moved over the
/// day), mapped to steps with a coefficient learned from days where the phone ALSO counted steps. The
/// output is always framed as an estimate.
```

- [ ] **Step 3: Document the wire field**

In `docs/PROTOCOL_SENSORS.md`, in the table under `### WHOOP 4 historical v24`, add after the row `| 82 | 2/u16 | signal-quality word | scale/polarity unresolved |`:

```markdown
| 92 | 4/u32 | cumulative firmware step count | capture-backed on one strap; present only in the 104-byte record; ticks-per-step, rollover and reset unvalidated |
```

Directly under that table's closing paragraph (the one ending `that association is not a fresh v12 device validation.`), add:

```markdown

**Observed in device captures (one strap, 2026-10-03, 1,203 consecutive records):** the value at
92 never decreased, stayed flat through 14.5 minutes seated despite arm motion, and rose only
during walking. Walks the wearer counted as 100 and 50 steps raised it by 127 and 63; the wearer
turned on the spot in both. Each bout opened with a single 11 or 12 step increment, then 1–2 per
second and at most 4. Twenty-five seconds of hand-waving raised it by 62, so the firmware does not
reject vigorous rhythmic arm motion. A record from a second strap carries 708 at the same offset.
The field is read only when the record's own version byte is 24.
```

In the same file, in the last paragraph of `### WHOOP 4 sensor and record controls`, replace

```markdown
remain unknown. This counter
must not be equated with app or cloud steps without a version-labelled wire field
and a validation set.
```

with:

```markdown
remain unknown. The count appears on the wire at offset 92 of the 104-byte v24
historical record (see [WHOOP 4 historical v24](#whoop-4-historical-v24)). It
must not be equated with app or cloud steps without a validation set.
```

- [ ] **Step 4: Build both app targets**

`swift test` does not compile the app targets. Run from the repository root:

```bash
xcodegen generate
xcodebuild -project Strand.xcodeproj -scheme Strand -destination 'platform=macOS' \
  -derivedDataPath build/ddmac CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -5
xcodebuild -project Strand.xcodeproj -scheme NOOPiOS -destination 'generic/platform=iOS' \
  -derivedDataPath build/dd CODE_SIGNING_ALLOWED=NO build 2>&1 | tail -5
```

Expected: `** BUILD SUCCEEDED **` twice. If the iOS build stops on the watch target rather than on this change, build the no-watch variant instead: copy `project.yml` to a scratch spec with `name: StrandNoWatch` and the line `      - target: NOOPWatch` removed, run `xcodegen generate --spec <that file>`, then the same `xcodebuild` with `-project StrandNoWatch.xcodeproj`.

- [ ] **Step 5: Check the parity scanner gained no errors**

Run: `python3 Tools/parity_ledger.py 2>&1 | tail -3`

Expected: `Baseline not evaluated: 18 scan errors.` The count was 18 before this plan; it must not grow, and none of the listed errors may name a file this plan touched.

- [ ] **Step 6: Commit, if the user has asked for commits**

```bash
git add Strand/Devices/DeviceReadsView.swift \
        Packages/StrandAnalytics/Sources/StrandAnalytics/StepsEstimateEngine.swift \
        docs/PROTOCOL_SENSORS.md \
        docs/superpowers/specs/2026-10-03-whoop4-step-counter-design.md \
        docs/superpowers/plans/2026-10-03-whoop4-step-counter.md
git commit -m "docs: WHOOP 4.0 counts steps from the firmware counter"
```

---

### Task 4: Verify on the strap (with the user)

BLE behaviour cannot be tested off-device. This task needs the user's phone and a walk.

- [ ] **Step 1: Install the build on the phone** using the user's usual sideload route.
- [ ] **Step 2: Sync, then read the day's step total** on the Summary screen. Expected: a number without the estimate marker, and it rises after a walk and the next sync.
- [ ] **Step 3: Pin ticks-per-step.** A 10-minute walk with the phone in a pocket; compare the rise in the app's step total with the Health app's step count for the same minutes. Expected: within a few percent. If it is consistently off, the existing "ticks per step" setting scales it.
- [ ] **Step 4: Record the result** in the validation paragraph added to `docs/PROTOCOL_SENSORS.md`.
