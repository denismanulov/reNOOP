package com.noop.data

import org.json.JSONObject

/**
 * Durable mapping for the byte at absolute offset 86 of a WHOOP 4.0 v24 historical record.
 *
 * The byte is the strap's own blood-oxygen result for that second: `0` means no computed reading,
 * `70..100` is a percentage, and every other nonzero value is a status or error code (bits 4, 8, 16,
 * 32, 128 and their sums have been observed). The offset and those semantics come from a third-party
 * firmware analysis and are reimplemented here as a protocol fact, see `ATTRIBUTION.md`.
 *
 * What the captures show, on two straps from the #1617 overnight logs: the byte is `0` in every record
 * whose optical channel words at 80/82 read disabled, and nonzero only inside the ~29 s windows where
 * those words read enabled (28 of 28 and 8 of 8 records). Inside a window it reads codes first and then
 * 93..95. No capture has been compared against a reference oximeter or the WHOOP app's nightly figure,
 * so this is an UNVALIDATED CANDIDATE: it never writes `spo2Pct` and never feeds a score.
 *
 * Only NONZERO bytes are recorded. A zero is "the strap computed nothing this second", which is the
 * state of ~99% of a day, and a row per second to say so would be the #1617 red/IR table again.
 * Absence of an event is therefore absence of a reading, never a 0% reading.
 *
 * Rides the `event` table under its own kind, as [StandardHrMapping] does, so it needs no migration.
 * Android only for now: there is no Swift twin yet.
 */
object V24AuxByte86Mapping {
    const val EVENT_KIND = "V24_AUX_BYTE_86"

    /** The in-band window in which the byte is a percentage. Same bounds as the v18 `@82` candidate. */
    val PERCENT_RANGE = 70..100

    fun event(ts: Long, byte: Int): EventEntry = EventEntry(
        ts = ts,
        kind = EVENT_KIND,
        payloadJSON = "{\"byte\":$byte}",
    )

    /**
     * Parse a persisted [EVENT_KIND] [EventRow.payloadJSON]. Throws [org.json.JSONException] on a
     * malformed payload so a parse failure is not the same as "no reading", as [StandardHrMapping] does.
     */
    fun sample(row: EventRow): V24AuxByte86Sample =
        V24AuxByte86Sample(row.ts, JSONObject(row.payloadJSON).getInt("byte"))
}

/** One nonzero `@86` byte: a percentage when in [V24AuxByte86Mapping.PERCENT_RANGE], else a code. */
data class V24AuxByte86Sample(val ts: Long, val byte: Int)
