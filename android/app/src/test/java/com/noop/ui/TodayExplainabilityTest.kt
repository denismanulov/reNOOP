package com.noop.ui

import com.noop.R
import com.noop.analytics.FusionSource
import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for what is left of the Today explainability layer after the Summary replaced Today
 * (spec: 2026-06-20-sleep-guidance-explainability.md): [provenanceBadgeLabel] (the By-Day vocabulary)
 * and [provenanceDisplayLabel] (the PER-METRIC badge — the REAL field-by-field winner), plus the carry
 * and calibration helpers the Summary still reads.
 *
 * The honesty rule is pinned directly: the provenance label is the real winner (never a blanket
 * "on-device").
 */
class TodayExplainabilityTest {

    private fun res(id: Int) = DisplayText.Resource(id)

    private fun day(key: String, recovery: Double? = null, deviceId: String = "my-whoop") =
        DailyMetric(deviceId = deviceId, day = key, recovery = recovery)

    @Test
    fun provenanceLabel_isNeverBlanketOnDevice_forImports() {
        // Honesty: an imported source must NOT be relabelled "On-device".
        assertEquals(res(R.string.today_source_whoop), provenanceBadgeLabel(FusionSource.WHOOP_IMPORT))
        assertEquals(res(R.string.today_source_apple_health), provenanceBadgeLabel(FusionSource.APPLE_HEALTH))
    }

    // ── COMPONENT 4 — PER-METRIC provenance (provenanceDisplayLabel) ─────────────────────────────────────
    //
    // The Today rings each badge their OWN metric's real merge winner, resolved field-by-field per
    // WhoopRepository.mergeDaily (imported WHOOP > NOOP-computed > Apple Health). `provenanceDisplayLabel`
    // is the PURE raw-source-id → label mapper that the per-ring badge uses. It must mirror the Swift
    // `provenanceDisplayLabel(rawSource:deviceId:)` EXACTLY: the computed sibling reads "On-device", the
    // imported strap source reads "Whoop", Apple Health reads "Apple Health", and any OTHER source keeps
    // its FusionSource display name (never a blanket "on-device").

    @Test
    fun perMetric_computedSibling_readsOnDevice() {
        // The "$deviceId-noop" sibling is a score NOOP computed on THIS device from the raw strap stream.
        assertEquals(res(R.string.today_source_on_device), provenanceDisplayLabel("my-whoop-noop"))
    }

    @Test
    fun perMetric_importedStrap_readsWhoop() {
        // The imported strap source (the deviceId itself, normally "my-whoop") is a real WHOOP export.
        assertEquals(res(R.string.today_source_whoop), provenanceDisplayLabel("my-whoop"))
    }

    @Test
    fun perMetric_importedMetricOnComputedDay_labelledHonestly() {
        // THE CONTRACT (Component 4): an imported metric winning field-by-field on an otherwise-computed
        // day must read its REAL source, never a blanket "On-device". So when the resolver returns the
        // import source for, say, "recovery" while the day's other fields are computed, the Charge badge
        // reads "Whoop" — and an Apple-Health-won metric reads "Apple Health" — not the day's deviceId.
        assertEquals(res(R.string.today_source_whoop), provenanceDisplayLabel("my-whoop"))
        assertEquals(res(R.string.today_source_apple_health), provenanceDisplayLabel("apple-health"))
    }

    @Test
    fun perMetric_appleHealth_readsAppleHealth() {
        assertEquals(res(R.string.today_source_apple_health), provenanceDisplayLabel("apple-health"))
    }

    @Test
    fun perMetric_otherKnownSource_keepsFusionDisplayName() {
        // Any other real source keeps its FusionSource.displayName (the genuine winner), never blanketed.
        assertEquals(res(R.string.today_source_health_connect), provenanceDisplayLabel("health-connect"))
        assertEquals(res(R.string.today_source_mi_band), provenanceDisplayLabel("xiaomi-band"))
    }

    @Test
    fun perMetric_unknownSource_fallsBackToRawId() {
        // An unrecognised raw id falls through to itself verbatim rather than guessing a label.
        assertEquals(DisplayText.Dynamic("garmin-import"), provenanceDisplayLabel("garmin-import"))
    }

    @Test
    fun perMetric_honoursACustomStrapDeviceId() {
        // The deviceId is parameterised (mirrors Swift's repo.deviceId): a custom strap id and its "-noop"
        // sibling still resolve to "Whoop" / "On-device", and the FIXED "my-whoop" import still reads "Whoop".
        assertEquals(res(R.string.today_source_on_device), provenanceDisplayLabel("strap-42-noop", deviceId = "strap-42"))
        assertEquals(res(R.string.today_source_whoop), provenanceDisplayLabel("strap-42", deviceId = "strap-42"))
        assertEquals(res(R.string.today_source_whoop), provenanceDisplayLabel("my-whoop", deviceId = "strap-42"))
    }

    @Test
    fun perMetric_crossStrapComputedSibling_stillReadsOnDevice() {
        // A "-noop" sibling banked under a DIFFERENT strap id (the user re-paired straps) is still a
        // score NOOP computed on-device. The resolver matches the "-noop" suffix, not the exact
        // "$deviceId-noop" — otherwise these rows would fall through to the raw id verbatim.
        assertEquals(res(R.string.today_source_on_device), provenanceDisplayLabel("whoop5-C0FF-noop", deviceId = "my-whoop"))
        assertEquals(res(R.string.today_source_on_device), provenanceDisplayLabel("my-whoop-noop", deviceId = "strap-42"))
    }

    @Test
    fun pullToSync_onlyEnabledWhenConnectedBondedAndIdle() {
        assertTrue(todayPullToSyncEnabled(
            connected = true, bonded = true, backfilling = false, historyReady = true))

        assertFalse(todayPullToSyncEnabled(
            connected = false, bonded = true, backfilling = false, historyReady = true))
        assertFalse(todayPullToSyncEnabled(
            connected = true, bonded = false, backfilling = false, historyReady = true))
        assertFalse(todayPullToSyncEnabled(
            connected = true, bonded = true, backfilling = true, historyReady = true))
    }

    /**
     * THE case this argument exists for, from the field: a WHOOP 5/MG that has never completed a
     * handshake. `bonded` is true — the live-HR path sets it — so every other condition passes and the
     * gesture was offered, accepted, and then refused by `beginBackfill`'s own `connectHandshakeDone`
     * gate with nothing shown. Reported four times as "refresh doesn't work"; it worked, it was silent.
     */
    @Test
    fun `a strap that cannot hand over history does not offer the gesture`() {
        assertFalse(todayPullToSyncEnabled(
            connected = true, bonded = true, backfilling = false, historyReady = false))
    }

    /**
     * The no-regression contract, and the reason this is safe to add: the new argument mirrors a
     * precondition `beginBackfill` ALREADY enforces, so the gesture can only disappear where the sync
     * would have been declined regardless. It can never withhold a refresh that would have run — which
     * is exactly what a strap-family check could not promise.
     */
    @Test
    fun `it never withholds a sync that would have run`() {
        // Every combination the old three-argument gate allowed still passes, provided the client would
        // actually have accepted it. historyReady=true is that condition, not an extra hurdle.
        assertTrue(todayPullToSyncEnabled(
            connected = true, bonded = true, backfilling = false, historyReady = true))
        // And it catches a case a family check would miss entirely: a WHOOP 4.0 whose bond has not landed
        // is just as unable to sync as an unpaired 5/MG, and its gesture is just as dead.
        assertFalse(todayPullToSyncEnabled(
            connected = true, bonded = false, backfilling = false, historyReady = false))
    }
}
