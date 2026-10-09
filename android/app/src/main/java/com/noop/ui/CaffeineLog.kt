package com.noop.ui

import android.content.Context
import com.noop.analytics.CaffeineIntake
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// MARK: - Caffeine window (#526) — pure persistence helpers (the Journal logs and lists intakes).
//
// Faithful Kotlin twin of Strand/Screens/CaffeineLogCard.swift + the CaffeineLogStore persistence.
// OPT-IN, manual-first: the user logs a caffeine intake (time + OPTIONAL mg) and NOOP shows a rough,
// on-device "still active" hint from a ~5–6 h half-life decay. Nothing leaves the device. The decay math
// + honesty rules live in com.noop.analytics.CaffeineDecay (cross-platform parity). Reuses the journal's
// SharedPreferences + showToast (Toast) patterns.

private const val CAFFEINE_PREFS = "noop_prefs"
private const val CAFFEINE_KEY = "noop.caffeineIntakes"
/** Drop intakes older than this many hours on load — well past the decay horizon, so the estimate is
 *  unchanged but the stored blob can't grow without bound. Matches Swift CaffeineLogStore.retentionHours. */
private const val CAFFEINE_RETENTION_HOURS = 48.0

/** Load the user's logged caffeine intakes, pruning anything past the retention horizon. */
internal fun loadCaffeineIntakes(context: Context, nowEpochSec: Long = System.currentTimeMillis() / 1000L): List<CaffeineIntake> {
    val raw = context.getSharedPreferences(CAFFEINE_PREFS, Context.MODE_PRIVATE).getString(CAFFEINE_KEY, "") ?: ""
    if (raw.isBlank()) return emptyList()
    val cutoff = nowEpochSec - (CAFFEINE_RETENTION_HOURS * 3600).toLong()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val at = o.optLong("at", Long.MIN_VALUE)
            if (at == Long.MIN_VALUE || at < cutoff) return@mapNotNull null
            CaffeineIntake(
                id = o.optString("id", UUID.randomUUID().toString()),
                atEpochSec = at,
                mg = if (o.has("mg") && !o.isNull("mg")) o.optDouble("mg") else null,
            )
        }.sortedByDescending { it.atEpochSec }
    }.getOrDefault(emptyList())
}

private fun saveCaffeineIntakes(context: Context, intakes: List<CaffeineIntake>) {
    val arr = JSONArray()
    for (i in intakes) {
        val o = JSONObject()
        o.put("id", i.id)
        o.put("at", i.atEpochSec)
        if (i.mg != null) o.put("mg", i.mg) else o.put("mg", JSONObject.NULL)
        arr.put(o)
    }
    context.getSharedPreferences(CAFFEINE_PREFS, Context.MODE_PRIVATE)
        .edit().putString(CAFFEINE_KEY, arr.toString()).apply()
}

/** Sanitise a user-entered mg into a stored value: blank/invalid/negative → null (unknown, not garbage);
 *  absurdly large → clamped. Honest: unknown amount is better than a wrong amount. Mirrors Swift. */
internal fun sanitiseCaffeineMg(input: String?): Double? {
    val v = input?.trim()?.toDoubleOrNull() ?: return null
    if (!v.isFinite() || v <= 0) return null
    return minOf(v, 2000.0)
}

/** Append a logged intake (newest-first) and persist. Returns the new list. */
internal fun addCaffeineIntake(context: Context, atEpochSec: Long, mgInput: String?): List<CaffeineIntake> {
    val intake = CaffeineIntake(UUID.randomUUID().toString(), atEpochSec, sanitiseCaffeineMg(mgInput))
    val next = (listOf(intake) + loadCaffeineIntakes(context)).sortedByDescending { it.atEpochSec }
    saveCaffeineIntakes(context, next)
    return next
}

internal fun removeCaffeineIntake(context: Context, id: String): List<CaffeineIntake> {
    val next = loadCaffeineIntakes(context).filterNot { it.id == id }
    saveCaffeineIntakes(context, next)
    return next
}

