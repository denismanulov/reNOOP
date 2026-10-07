# WHOOP 4.0 firmware step counter — design

Date: 2026-10-03. Status: approved.

## Goal

Show counted steps on a WHOOP 4.0, read from the strap's own pedometer, instead of the
motion-volume estimate (`StepsEstimateEngine`). No model, no phone calibration.

## Evidence

One strap (WHOOP 4.0, Nordic 17.2.2.0), 1,203 consecutive v24 history records captured with
`enableRawCapture` on 2026-10-03, 13:43:59–14:04:03 local.

- Real v24 records are **104 bytes**. The schema maps fields only up to offset 84.
- Frame bytes **92..95, `u32` little-endian**, hold a cumulative counter (45630 → 45894).
- It never decreased and did not move during 14.5 minutes seated, despite arm motion.
- A walk counted by the wearer as 100 steps raised it by 127; a 50-step walk by 63. The wearer
  turned on the spot in both, so the surplus is plausibly real steps. The 2:1 ratio held.
- 25 seconds of hand-waving raised it by 62. The firmware counts vigorous rhythmic waving;
  1 Hz records carry nothing that could separate it.
- Every bout opens with a single **+11 or +12** record (the pedometer releases its
  confirmation buffer), then 1–2 per second, at most 4.

Limits of this evidence: one strap, one firmware, four bouts. Ticks-per-step is not pinned
tighter than "about 1". Rollover and reset behaviour are unobserved.

## Design

### 1. Decode

- The `historical_data` decode hook reads a `u32` at offset 92 as `step_counter`, the same way
  it already reads the v25 fields: in code, bounded by the payload limit. A shorter v24 record
  (the 94-byte fixture shape) ends before the field and yields no counter rather than garbage.
- Only for records whose own version byte is 24. Version 12 and the unmapped-version fallback
  reuse the v24 field map, and nothing shows that their bytes 92..95 are a counter. That gate is
  why the field is not added to the shared v24 list in `whoop_protocol.json`.
- `HistoricalStreams`: when a record carries `step_counter`, emit
  `StepSample(ts:, counter:, activityClass: nil)`.

### 2. Store

The existing `stepSample` table takes the rows as they are. No migration.

This step is urgent on its own: the strap trims acknowledged history, so every sync before
it lands loses the counter for that span. Days already synced cannot be recovered.

### 3. Count

`StepsCounter.isPlausibleDelta` allows 4 ticks per second **between consecutive samples**.
On the capture that drops all four bout-start releases: 217 counted of 264.

Change the time base, not the limit: measure the rate over the time since the counter
**last moved**, capped at an 8-second confirmation window (11–12 buffered steps at a slow
1.5 steps/s). On the capture this counts 264 of 264.

- Sync-gap and reboot guards (`maxStepDelta`, the ≥128 s branch) are unchanged.
- The rule is family-neutral. A 5/MG is affected in one case only: a 5–32 tick increment
  arriving after two or more flat seconds was rejected and is now accepted. Walk/run class
  gating on 5/MG is untouched.
- `SleepAwareStepCounter.Accumulator` shares the gate and must carry the last-moved
  timestamp across pages.
- The wrap-aware `& 0xFFFF` delta stays correct for a `u32` counter.
- `profile.stepTicksPerStep` (default 1.0) continues to scale the total.

Alternative considered: thread `DeviceFamily` into the counter and relax the gate for
WHOOP 4.0 only. Rejected because buffering is a property of pedometers, not of one strap,
and it adds a parameter to every caller.

### 4. Surface

- `resolvedSteps` and the Summary reading already rank a measured strap total above the phone
  import and the estimate, so a 4.0 day with counter rows becomes a counted day without new
  plumbing. Checked: the Summary reads `dailyMetric.steps` first and falls back to `steps_est`.
- The estimate stays, for days that have no counter rows.
- The device capability table lists Steps as fully read on a 4.0.
- The `noRawCounter … e.g. WHOOP 4.0` trace line is left as it is: it is pinned byte-for-byte
  against its Kotlin twin and still describes a 4.0 day without counter rows.
- Add the offset-92 row to the v24 table in `docs/PROTOCOL_SENSORS.md`, with the validation
  boundary above.

## Platforms

Swift packages and the Apple app are built and tested locally. This machine has no JDK, so
Kotlin cannot be compiled here.

## Testing

- `WhoopProtocol`: thirteen real 104-byte frames spanning a bout start decode to the
  expected counters; a 94-byte v24 record decodes to no `StepSample`.
- `StrandAnalytics`: the captured 1,203-point series totals 264. Gate cases: a release
  after a flat run is counted, a +300 jump within one second is not, wrap is handled.
  Existing 5/MG step tests stay green.
- App targets: `xcodebuild` for `Strand` (macOS) and `NOOPiOS`, since CI does not compile
  them.
- On hardware: one more walk with the phone in a pocket, comparing the counter's rise with
  the iPhone pedometer, to pin ticks-per-step.

## Out of scope

- A step detector on the live 100 Hz stream.
- Other fields noticed in the same capture: an `f32` at offset 36 that behaves like dynamic
  acceleration, and `skin_contact@55`, which looks like the top byte of an `f32` at 52..55.
  Both need their own investigation.

## Decisions (2026-10-03)

1. Apple platforms, with one forced exception. `decoder_oracle.json` is shared: the Swift and
   Android copies must be byte-identical and both platforms assert it, and it pins the step
   row count of two 104-byte v24 batches. The Swift decoder therefore cannot change without
   the Kotlin decoder. The Kotlin decoder twin (two short insertions) is part of this change,
   uncompiled until CI runs. The gate change has no shared fixture and stays Swift-only; until
   it is ported, Android counts a WHOOP 4.0 with the old gate and drops each bout's release.
2. The gate change is family-neutral, as proposed. Upstream's Kotlin comment names "delayed
   counter publication" among the things the old gate rejects on purpose, so this is a
   deliberate divergence and a likely merge conflict when syncing upstream.
