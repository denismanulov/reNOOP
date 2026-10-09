package com.noop.ui

import com.noop.data.Vo2MaxEstimator

/** One windowed reading behind a vital's detail chart: its day ("YYYY-MM-DD"), the value, and the RAW
 *  source id it came from (a strap id, the "-noop" computed sibling, "apple-health", or "health-connect").
 *  The readings TABLE and the "N readings" header both derive from this ONE list, so they can never
 *  disagree; the raw source maps to a human label via [provenanceDisplayLabel] — the SAME resolver Today
 *  uses, so we never invent a source vocabulary (task #8). */
internal data class VitalReading(
    val day: String,
    val value: Double,
    val source: String,
)

internal const val VO2_MAX_ATTRIBUTION_PREFIX = "vo2max-estimator:"

/**
 * #103/queue-11a follow-up: display-source token for a `spo2` reading that came from the
 * `spo2_candidate` fallback (WHOOP `spo2_candidate_82` or Oura ceiling@100 `0x6F`, device-conditional)
 * rather than a calibrated `spo2Pct` day. Every OTHER surface with this fallback (the Key Metrics
 * tile) already labels it via `R.string.spo2_strap_estimate_caption` — this vital-detail
 * screen (`VitalDetailScreen`/"Your Cards" drill-in) had no candidate fallback at all until now (found
 * 2026-08-24: an Oura-only or WHOOP-4.0-only install with the toggle ON saw a real number on the tile
 * but nothing here past the last calibrated import). Same prefix-token idiom as
 * [vo2MaxAttributionSource] just above.
 */
internal const val SPO2_CANDIDATE_ATTRIBUTION_SOURCE = "spo2-candidate-estimate"

/** Display-source token for a VO₂max reading. A missing legacy tag stays unknown; it must never inherit
 *  the method implied by today's profile because the waist measurement may have changed after scoring. */
internal fun vo2MaxAttributionSource(estimator: Vo2MaxEstimator?): String =
    VO2_MAX_ATTRIBUTION_PREFIX + (estimator?.provenanceId ?: "unknown")

/** Sequential ids for a method-aware trend. Nes → Uth → Nes becomes three segments rather than joining
 *  the non-adjacent Nes runs across an incompatible estimator. */
internal fun vo2MaxTrendSegmentIds(readings: List<VitalReading>): List<String> {
    var previous: String? = null
    var group = -1
    return readings.map { reading ->
        if (reading.source != previous) {
            group++
            previous = reading.source
        }
        "$group:${reading.source}"
    }
}

/**
 * Will the chart show a visible break in this VO2max trend?
 *
 * Derived from [vo2MaxTrendSegmentIds] rather than recomputed, so the caption and the segmentation can
 * never disagree: if the ids collapse to one group the line is continuous and there is nothing to
 * explain. Two readings from the SAME estimator with a gap in days are one segment and correctly get no
 * caption - a break means the readings were not produced alike, not that the data paused.
 *
 * Named for the BREAK, not for a method change: an untagged legacy reading resolves to
 * "...estimator:unknown" (see [vo2MaxAttributionSource]), so an unknown -> Nes transition also splits the
 * line while the method itself may never have changed. The caption is worded for both causes.
 */
internal fun vo2MaxTrendHasBreak(readings: List<VitalReading>): Boolean =
    vo2MaxTrendSegmentIds(readings).distinct().size > 1
