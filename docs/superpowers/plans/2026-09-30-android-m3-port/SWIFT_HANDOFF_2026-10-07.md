# Swift handoff, 2026-10-07: work that started on Android

The Android port normally follows the Swift app. On 2026-10-07 one change went the other way: it was
found and built on Android first, and the Swift app has no twin yet. This page is for whoever (person
or agent) brings it to `Packages/` and `Strand/`. It also records findings from the same day that
changed no code but concern logic both platforms share.

Read [`AGENTS.md`](../../../../AGENTS.md) first for the parity contract, and
[`docs/VALIDATION_PROTOCOL.md`](../../../VALIDATION_PROTOCOL.md) before claiming any accuracy.

## 1. The one code change to twin: WHOOP 4.0 blood oxygen (`aux_byte_86`)

Android commit `b7a287e0` on `android-m3`.

### The fact

A WHOOP 4.0 computes blood oxygen itself and writes the result to the byte at **absolute offset 86 of
the 104-byte v24 historical record**:

| Byte value | Meaning |
|---|---|
| `0` | nothing computed this second (almost every record) |
| `70..100` | a percentage |
| anything else nonzero | a status or error code; `8`, `16`, `40`, `128`, `168` seen on real straps |

Upstream issue 1617 concluded a 4.0 banks no percentage. It examined only `spo2_red@68` / `spo2_ir@70`,
and that conclusion still holds for those two fields. The result byte is 16 bytes further down the same
record and was not looked at.

The offset and the value vocabulary come from a third-party firmware analysis
(`alex-holovach/life`, `docs/RESPIRATION-OXYGEN.md`). No code from it is used; see the entry in
[`ATTRIBUTION.md`](../../../../ATTRIBUTION.md). Do not copy its implementation when writing the twin.

### The evidence, and how to re-derive it

Do not cite the counts below; reproduce them. The overnight strap logs attached to upstream issue 1617
contain Test Centre `spo2re` lines, each with a full frame in hex. Parse every line matching
`spo2re v=24 ... len=104 raw=<hex>`, dedupe by `unix`, and tabulate `frame[86]` against the two u16
words at 80 and 82.

| Capture | v24 records | byte 86 zero | byte 86 nonzero | words at 80 / 82 when nonzero |
|---|---|---|---|---|
| strap A, iOS overnight strap log | 312 | 284 | 28 | `0x0B01` / `0x0562` |
| strap B, Android overnight strap log | 210 | 202 | 8 | `0x0B51` / `0x0552` |

In all 486 zero records the words read `0x0C01` / `0x0C02`. In all 36 nonzero records they read the
values in the last column. Inside those runs the byte reads codes first and then `93`, `94` or `95`.

A third strap (the Android port author's, firmware not recorded) shows the schedule: the word at 80
leaves `0x0C01` for about 29 seconds roughly every 19 minutes, and over eight days never between 13:00
and 23:00 local. Android already stores that word as `respSample.raw`, so the query is
`SELECT ts FROM respSample WHERE raw <> 3073`.

### What Android does, piece by piece

| Piece | Android | Swift place to twin it |
|---|---|---|
| Decode | `decodeHistorical` in `android/.../protocol/HistoricalStreams.kt` emits `aux_byte_86` (raw) and `spo2_candidate_86` (only when `70..100`). Gate: record's own version byte is 24 **and** frame length is exactly 104. | `Packages/WhoopProtocol/Sources/WhoopProtocol/PostHooks.swift`, the `historical_data` v24 case, beside the `step_counter` read at offset 92 |
| Banking | `extractHistoricalStreams` adds one event per **nonzero** byte: kind `V24_AUX_BYTE_86`, at the record's own second. Gate: family is WHOOP 4 and `hist_version == 24`. A zero byte is never stored. | `Packages/WhoopProtocol/Sources/WhoopProtocol/HistoricalStreams.swift`, beside the `spo2_red` row near line 308 |
| Mapping | `android/.../data/V24AuxByte86Mapping.kt`: event kind, the `70..100` range, encode, parse | new file beside `Packages/WhoopStore/Sources/WhoopStore/StandardHRMapping.swift`, which is the pattern it follows |
| Read | `WhoopRepository.v24AuxByte86Samples(deviceId, from, to, limit)` reads events by kind | `StreamStore` |
| Nightly mean | `AnalyticsEngine.nightlyV24Spo2CandidateMean(sessions, samples)`: mean of in-band bytes inside a detected sleep session, rounded (not floored), returned with its count | `Packages/StrandAnalytics/Sources/StrandAnalytics/AnalyticsEngine.swift`, beside `nightlySpo2CandidateMean` (line 1418) |
| Caller | `IntelligenceEngine.spo2CandidateMean` uses the v18 aux stream when it has rows, else falls back to the v24 samples | `Strand/Data/IntelligenceEngine.swift` near line 1821 |
| Empty state | `android/.../ui/VitalGates.kt`: a 4.0 now follows the same rule as a 5/MG (estimate off: name the switch; on: needs nights). The two strings that said "stays empty however long you wear it" were deleted. | the Swift metric detail's Blood Oxygen empty copy, and the Test Centre toggle label, which still reads "(WHOOP 5/MG, Oura)" at `Strand/Screens/TestCentreView.swift:571` |

### The stored-data contract (must be byte-identical)

- Event kind: the literal `V24_AUX_BYTE_86`.
- Event payload JSON: exactly `{"byte":168}` for a byte of 168. One key, an integer value, no spaces.
  Swift's `WhoopEvent(ts:kind:payload:)` with `["byte": .int(168)]` must encode to that same string.
- Event `ts`: the record's `unix`, through the same clock correction every other type-47 field gets.
- The result is written only to the `spo2_candidate` metricSeries key. It must never write `spo2Pct`,
  `spo2_red` or `spo2_ir`, and never feed recovery or illness. Android enforces that only by where
  the value flows; no test asserts it for the 4.0 path yet, so add one on both sides.
- Display stays behind the existing default-off switch (`PuffinExperiment.spo2CandidateDisplayKey`).

### Fixtures and expected values

`android/app/src/test/java/com/noop/protocol/Whoop4AuxByte86Test.kt` holds four real frames from the
issue's logs with their expected decode:

| Fixture | `unix` | `aux_byte_86` | `spo2_candidate_86` | banked as an event |
|---|---|---|---|---|
| `percentA` | 1787714159 | 94 | 94 | yes, `{"byte":94}` |
| `codeA` | 1787709550 | 168 | absent | yes, `{"byte":168}` |
| `idleA` | 1787692062 | 0 | absent | no |
| `percentB` | 1787719345 | 94 | 94 | yes |

`android/app/src/test/java/com/noop/analytics/V24Spo2CandidateNightlyTest.kt` holds the mean's cases:
codes excluded, readings outside a session excluded, `95` and `96` round to `96`, the band is inclusive
at 70 and 100. Use the same inputs in the Swift suite. Per the parity rule, prove the two sides agree
with a compiled Swift oracle and paste its stdout as the Kotlin expectation; reading them side by side
is not enough.

### What is not established

- **One night has been seen on the author's own strap, and it shows the mean is a weak estimator.**
  See "First night on a third strap" below. Past nights cannot be recovered, because the raw records
  were never stored.
- **No reference comparison exists.** Not against a pulse oximeter, not against the WHOOP app's nightly
  figure. The only sanity reference is unpaired: one wearer's WHOOP export shows a nightly median of
  96.2 % (25th to 75th percentile 95.4 to 96.9), from months before the reNOOP data begins.
- **`98` may be ambiguous.** The same firmware analysis says a missing optical baseline also returns 98.
  Android keeps 98 in the mean for now. Check how often it appears next to codes before trusting a mean
  near 98.
- **Firmware coverage.** The analysis is of 41.17.4.0. The captures' firmware versions were not
  recorded here.
- It is a candidate under the rule in `AGENTS.md` for signals derived from sensor data: instrumentation
  behind a default-off switch, not a shipped metric.

### First night on a third strap (2026-10-08)

The path works end to end: the night of 2026-10-08 banked 608 nonzero bytes in 21 windows of 28 to 29
seconds, about 19 minutes apart, all inside the detected sleep session, and the Summary card shows
`94 %` under the "strap estimate (unverified)" caption.

| | Readings | Mean | Median |
|---|---|---|---|
| all in-band readings | 343 | 93.5 | 95 |
| in-band readings before 06:00 local | 230 | 95.2 | 95 |
| per-window medians, windows with at least 5 in-band readings | 15 windows | 93.3 | 95 |

What the night shows, and what it does not:

- **The mean is pulled down by a few late windows.** All 62 readings below 90 fall after 06:04, in
  the last two hours before waking, in runs such as 78 to 82 and 84 to 90. Whether those are motion
  or contact artifacts cannot be told from the byte alone, but a wearer whose WHOOP history never
  went below 92 as a nightly figure is unlikely to have spent minutes at 80.
- **Readings inside a window are not independent.** A window is typically one value repeated twenty
  times. Averaging readings weights a window by how long its value was held; the window is the unit
  the strap measures in.
- **One window was a flat 98** for 24 seconds straight after status codes, which fits the reported
  fallback. Three other windows moved between 97 and 99 and do not look like one.
- **The code vocabulary is wider than the first two straps showed:** `1`, `2`, `8`, `16`, `24`, `32`,
  `40`, `128`, `144`, `160`.

No estimator was changed on this evidence. One night from one wearer picks nothing, and the raw bytes
are stored, so any estimator can be recomputed over past nights later. If one is chosen, a median over
per-window medians is the candidate to test first, on grounds that do not depend on matching a target
value.

## 2. Findings that changed no code

These came out of the same investigation. Each is an observation with its sample size, not a decision.

### `red` / `ir` at 68 / 70 follow skin temperature

On one strap, over one night, per-minute `spo2_red` correlated with the skin-temperature ADC at
r = 0.99. Across eight days the `ir - red` offset took dozens of values and stepped during movement.
This agrees with issue 1617's "one channel plus a constant" and adds that the channel is not optical
oxygenation at all. `docs/PROTOCOL_SENSORS.md` now says so.

### The words at 78 / 80 / 82 look like optical channel configuration

The decoder names offset 80 `resp_rate_raw` and offset 82 a "signal-quality word". On three straps the
word at 80 is `0x0C01` outside a blood-oxygen window and `0x0B01` or `0x0B51` inside one, and nothing
else. That matches the firmware analysis's description of channel configuration bitfields and does not
look like respiration. No rename was made. `SleepStagerV2` already ignores this stream (it takes
respiration from R-R), so the default stager is unaffected; the V1 stager peak-detects it and on a 4.0
is reading a near-constant.

### REM share is set by constants in `SleepStagerV2`

One wearer, unpaired: the WHOOP export shows REM at 18 % of sleep (146 nights, 25th to 75th percentile
14 to 21), reNOOP shows 19 to 37 %, typically 29 %, over six nights. Deep sleep agrees (26 % against
21 to 29 %). A replay of the stager over those six nights, which reproduced the stored hypnograms
epoch for epoch, attributes the difference to the time-of-night ramp added to the REM emission:

| Variant | REM share of sleep, pooled |
|---|---|
| as shipped | 29.0 % |
| ramp halved | 16.9 % |
| ramp removed | 5.9 % |
| REM prior 0.18 instead of 0.22 | 20.8 % |
| RSA term dropped from REM | 20.2 % |

81 % of REM fell in the second half of the session, with single blocks of 46 to 79 minutes in the last
quarter on four of six nights. `Tools/SleepPSG/README.md` reports the same recipe predicting about
27 % REM on PSG data. The Swift twin has the same constants. **No change was made**, deliberately: the
WHOOP figure is a second estimate and not truth, and six unpaired nights from one person is not a basis
for retuning. This is recorded so the next person does not start from zero.

### The v25 record map may be wrong

Three archived v25 frames from one strap decode to `(15883, -31856, 0)` at 73 / 75 / 77 as i16, which is
not a 1 g vector under the documented `/16384` reading, so the existing decoder banks no gravity from
them. The firmware analysis cited above lays v25 out differently: an int32 first sample at 19, 24 int16
differences at 23 to 70, a float32 at 71, then a u16 and two bytes. Under that reading the same frames
give a float of about 0.136 at 71. Three frames settle nothing; the conflict is worth a look by whoever
next touches v25.

### Step auto-calibration mis-measured a second wearer (2026-10-08)

The Experimental step auto-calibration was on for one day on a second WHOOP 4.0 and wearer. It was
switched off again and the manual divisor set by hand. What that day showed:

| | Value |
|---|---|
| bursts taken in 24 h | 4 of 4 allowed |
| accepted | 1, giving 1.04 ticks per step (76 ticks over 73.2 steps in 40 s) |
| refused as "no clear gait" | 3 |
| of those, taken while the counter was advancing | 2 (77 and 60 ticks during the 40 s) |
| of those, taken during detected sleep | 1 (05:09 local, 14 ticks during the burst) |

Against that, a phone carried in a pocket counted 2,561 steps between 08:00 and 10:00 local while the
strap counter added 3,424: 1.34 ticks per step, inside the 1.14 to 1.49 already recorded for the first
strap. Of the 3,424, 405 came in 33 opening increments of 11 or 12, and the remaining 3,019 over 1,571
walking seconds (1.92 ticks per second). Without the opening increments the ratio is still 1.18.

What follows and what does not:

- The one accepted measurement disagrees with the phone by 29 %. The phone can undercount, so 1.34 is
  an upper bound, but 1.04 is outside anything measured on either strap by other means.
- `GaitCadence` was built on three recordings of slow walking (1.31 to 1.50 steps per second). This
  wearer's counter runs near 1.9 ticks per second, a brisker walk the detector's own notes call
  untested. Two refusals on real walking and one doubtful acceptance fit that, but the burst samples
  are not kept, so which test failed is not known.
- A burst fired during sleep because "walking" is decided from 10 ticks in 12 seconds of the offload.
- Whether an opening increment of 11 or 12 is real steps credited late or extra cannot be told from
  the record. Arm motion in the 8 seconds before each one is above baseline, which fits late credit.
  Three counted walks of different lengths on one strap would separate the two.

Nothing in the calibrator was changed. Before it is relied on, it needs brisk-walk recordings, and it
would help if a refused burst said which criterion refused it.

### Other levels, for orientation only

Same unpaired comparison, same wearer. HRV, sleep efficiency, deep-sleep share, daily Effort and daily
calories sit at the same level in both. reNOOP reads resting heart rate about 2 to 3 bpm lower and
respiratory rate about 1.3 breaths/min lower. Either could be the wearer changing over three months.

## 3. Things from that day that need nothing from Swift

- The author's max heart rate was set to 206 in his profile (a setting, not code).
- Health Connect writeback fails with `PERMISSION_DENIED` on his phone (Android only, not investigated).
- The live heart rate screen was hard to find from Summary (it is under Browse). A UX note, no change.
