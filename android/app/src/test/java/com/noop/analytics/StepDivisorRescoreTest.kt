package com.noop.analytics

import com.noop.data.DailyMetric
import com.noop.data.GravityWitness
import com.noop.data.HrSample
import com.noop.data.MemoryKeyValuePrefs
import com.noop.data.StepCalibrationStore
import com.noop.data.StepSample
import com.noop.data.WhoopDao
import com.noop.data.WhoopRepository
import com.noop.protocol.DeviceFamily
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.WildcardType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real scoring pass ([IntelligenceEngine.analyzeRecent]) over a three-day store of a WHOOP 4.0
 * that walked the same 600 seconds each day, with WHOOP 4.0 step auto-calibration off, on, measuring,
 * and off again. It reads what the pass PERSISTS and what its day cache reports, so it holds the whole
 * wiring: the resolver, the day cache key, the profile `analyzeDay` is handed and the stored step total.
 *
 * One test, in one order, because the day cache it exercises lives for the process.
 */
class StepDivisorRescoreTest {

    private val owner = "my-whoop"
    private val hr = ArrayList<HrSample>()
    private val steps = ArrayList<StepSample>()
    private val persisted = LinkedHashMap<Pair<String, String>, DailyMetric>()

    /** Room is replaced by lists. Only the reads this scenario feeds are answered from data; every other
     *  DAO call returns the empty value of its type (no rows, null, zero), as an empty table would. */
    private val dao = Proxy.newProxyInstance(WhoopDao::class.java.classLoader, arrayOf(WhoopDao::class.java)) { _, method, a ->
        val args = a ?: emptyArray()
        fun inWindow(deviceId: String, ts: Long) =
            deviceId == args[0] && ts >= args[1] as Long && ts <= args[2] as Long
        when (method.name) {
            "hrSamples", "rawHrSamples" -> hr.filter { inWindow(it.deviceId, it.ts) }.take(args[3] as Int)
            "hasHrInWindow" -> hr.any { inWindow(it.deviceId, it.ts) }
            "countHrInWindow" -> hr.count { inWindow(it.deviceId, it.ts) }
            "maxHrTsInWindow" -> hr.filter { inWindow(it.deviceId, it.ts) }.maxOfOrNull { it.ts } ?: 0L
            "stepSamples" -> steps.filter { inWindow(it.deviceId, it.ts) }.take(args[3] as Int)
            "dayStreamFingerprint" -> steps.filter { inWindow(it.deviceId, it.ts) }
                .let { "z${it.size}:${it.maxOfOrNull { row -> row.ts } ?: 0L}" }
            "gravityWitnessInWindow" -> GravityWitness(0, 0L)
            "days" -> persisted.values.filter { it.deviceId == args[0] }
            "dailyMetricsRange" -> persisted.values.filter {
                it.deviceId == args[0] && it.day >= args[1] as String && it.day <= args[2] as String
            }
            "replaceComputedScoreWindow" -> {
                val deviceId = args[0] as String
                val from = args[1] as String
                val to = args[2] as String
                val rows = (args[3] as List<*>).filterIsInstance<DailyMetric>()
                // The production DAO keeps the stored window when a pass produced no rows (#1196).
                if (rows.isNotEmpty()) {
                    persisted.keys.removeAll { it.first == deviceId && it.second >= from && it.second <= to }
                    rows.forEach { persisted[it.deviceId to it.day] = it }
                }
                Unit
            }
            else -> emptyValueOf(method)
        }
    } as WhoopDao

    /** The empty value a suspend DAO method of this return type would give for an empty table. */
    private fun emptyValueOf(method: java.lang.reflect.Method): Any? {
        val continuation = method.genericParameterTypes.lastOrNull() as? ParameterizedType
        var type = continuation?.actualTypeArguments?.firstOrNull() ?: method.genericReturnType
        if (type is WildcardType) type = type.lowerBounds.firstOrNull() ?: type.upperBounds.first()
        val raw = if (type is ParameterizedType) type.rawType else type
        return when (raw) {
            List::class.java, java.util.List::class.java -> emptyList<Any>()
            java.lang.Boolean::class.java, Boolean::class.javaPrimitiveType -> false
            java.lang.Integer::class.java, Int::class.javaPrimitiveType -> 0
            java.lang.Long::class.java, Long::class.javaPrimitiveType -> 0L
            String::class.java -> ""
            Unit::class.java, Void.TYPE -> Unit
            else -> null
        }
    }

    private val repo = WhoopRepository(dao)

    /** A registered WHOOP 4.0 owns every day, so the per-day reuse cache is in play. */
    private val whoop4 = object : IntelligenceEngine.DayOwnerSource {
        override suspend fun candidatePriorities() = listOf(owner to 0)
        override suspend fun lockedOwner(day: String): String? = null
        override suspend fun skinTempFamily(deviceId: String) = DeviceFamily.WHOOP4
        override suspend fun registeredWhoopFamily(deviceId: String): DeviceFamily? = DeviceFamily.WHOOP4
    }

    private val now = 1_780_272_000L + 15 * 3_600L
    private val tz = java.util.TimeZone.getDefault().getOffset(now * 1_000L) / 1_000L
    private val todayStart = now - Math.floorMod(now + tz, 86_400L)
    private val dayKeys = (0L..2L).map { AnalyticsEngine.dayString(todayStart - it * 86_400L, tz) }
    private val today get() = dayKeys[0]
    private val manual = 1.26f.toDouble()
    private val profile = UserProfile(stepTicksPerStep = manual)

    /** Each day: an hour of heart rate from 10:00 local, and a ten-minute walk at two ticks a second. */
    private fun seed() {
        for (back in 0L..2L) {
            val ten = todayStart - back * 86_400L + 10 * 3_600L
            for (s in 0 until 3_600) hr += HrSample(owner, ten + s, 70 + (s % 7))
            for (s in 0 until 600) steps += StepSample(owner, ten + 600 + s, 5_000 + 2 * s)
        }
    }

    private class Pass(val steps: Map<String, Int?>, val rows: Map<String, DailyMetric>, val cacheLine: String)

    private fun score(stepDivisors: ((Double, String) -> StepCalibrationStore.Snapshot)?): Pass = runBlocking {
        val diag = ArrayList<String>()
        IntelligenceEngine.analyzeRecent(
            repo = repo, profile = profile, maxDays = 3, importedDeviceId = owner, nowSeconds = now,
            ownerSource = whoop4, diag = { diag += it }, dayCycleMode = DayCycleMode.MIDNIGHT,
            stepDivisors = stepDivisors,
        )
        val rows = dayKeys.associateWith { persisted.getValue("$owner-noop" to it) }
        Pass(rows.mapValues { it.value.steps }, rows, diag.single { it.startsWith("analyzeRecent dayCache ") })
    }

    @Test
    fun theSwitchAndEachMeasurementMoveOnlyTheDaysTheyShould() {
        seed()
        val ticks = StepsCounter.stepsInWindow(steps.filter { it.ts >= todayStart }.toList())!!
        assertEquals("two ticks a second for 600 samples", 1_198, ticks)
        fun stepsAt(divisor: Double) = PhysiologicalStepCycleEngine.scaledCycleSteps(ticks, divisor)

        val prefs = MemoryKeyValuePrefs()
        val resolver: (Double, String) -> StepCalibrationStore.Snapshot =
            { m, t -> StepCalibrationStore.snapshot(m, t, prefs) }

        // 1. No resolver at all: the pass as it was before the feature existed.
        val plain = score(null)
        for (day in dayKeys) assertEquals(stepsAt(manual), plain.steps.getValue(day))

        // 2. The resolver wired, the opt-in OFF, and a learned state already in the store: every stored
        //    row is the same, every day is reused from the cache (so every key is the same too), and
        //    the store is neither read into the pass nor written.
        StepCalibrationStore.record(prefs, day = today, steps = 100.0, ticks = 130.0)
        val storedState = prefs.strings.getValue(StepCalibrationStore.STATE_KEY)
        val writes = prefs.writes
        val off = score(resolver)
        assertEquals(plain.rows, off.rows)
        assertTrue(off.cacheLine, off.cacheLine.startsWith("analyzeRecent dayCache reused=3/3 "))
        assertTrue(off.cacheLine, !off.cacheLine.contains("missBy="))
        assertEquals(writes, prefs.writes)
        assertEquals(storedState, prefs.strings.getValue(StepCalibrationStore.STATE_KEY))

        // 3. Switched ON: today has a learned factor (130 ticks for 100 steps), the earlier days none.
        //    Only today is re-scored, and for that reason.
        StepCalibrationStore.setEnabled(prefs, true)
        val on = score(resolver)
        assertTrue(on.cacheLine, on.cacheLine.startsWith("analyzeRecent dayCache reused=2/3 "))
        assertTrue(on.cacheLine, on.cacheLine.endsWith(" missBy=stepDiv:1"))
        assertEquals(stepsAt(1.3), on.steps.getValue(today))
        assertNotEquals(stepsAt(manual), on.steps.getValue(today))
        for (day in dayKeys.drop(1)) assertEquals(plain.rows.getValue(day), on.rows.getValue(day))
        // The divisor is all that moved in today's row.
        assertEquals(plain.rows.getValue(today), on.rows.getValue(today).copy(steps = plain.steps.getValue(today)))

        // 4. A pass with nothing new reuses every day.
        val again = score(resolver)
        assertTrue(again.cacheLine, again.cacheLine.startsWith("analyzeRecent dayCache reused=3/3 "))
        assertEquals(on.rows, again.rows)

        // 5. A second measurement today (66 ticks for 60 steps): today alone is re-scored again.
        val state = StepCalibrationStore.record(prefs, day = today, steps = 60.0, ticks = 66.0)
        val todayFactor = StepCalibration.factor(state, today, manual)
        assertEquals((130.0 + 66.0) / (100.0 + 60.0), todayFactor, 1e-12)
        val measured = score(resolver)
        assertTrue(measured.cacheLine, measured.cacheLine.startsWith("analyzeRecent dayCache reused=2/3 "))
        assertTrue(measured.cacheLine, measured.cacheLine.endsWith(" missBy=stepDiv:1"))
        assertEquals(stepsAt(todayFactor), measured.steps.getValue(today))
        for (day in dayKeys.drop(1)) assertEquals(plain.rows.getValue(day), measured.rows.getValue(day))

        // 6. Switched OFF again: today goes back to the manual divisor and every row is the plain one.
        StepCalibrationStore.setEnabled(prefs, false)
        val offAgain = score(resolver)
        assertTrue(offAgain.cacheLine, offAgain.cacheLine.endsWith(" missBy=stepDiv:1"))
        assertEquals(plain.rows, offAgain.rows)
    }
}
