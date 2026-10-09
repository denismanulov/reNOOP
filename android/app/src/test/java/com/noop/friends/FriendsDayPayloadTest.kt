package com.noop.friends

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The day a phone uploads (friends-server/README.md, "A day"): every member, what is left out when the
 * app has no figure, each sharing switch, the heart-rate line's 300-point bound, the Strain axis, and the
 * rule that skips an unchanged day.
 */
class FriendsDayPayloadTest {
    private val now = 1_791_540_000L
    private val all = FriendShare(scores = true, sleep = true, workouts = true, hr = true)

    private fun full() = FriendsDayInputs(
        recovery = 80.6,
        strain = 38.604,
        sleepScore = 87.5,
        sleep = FriendsSleepInput(
            startTs = now - 30_000, endTs = now - 1_080, asleepMin = 474.4, awakeMin = 18.2,
            remMin = 104.0, lightMin = 252.49, deepMin = 99.6, needMin = 480.0,
        ),
        workouts = listOf(
            FriendsWorkoutInput(now - 9_600, "Running", 1_860.4, strain = 35.2, avgHr = 139, maxHr = 162, kcal = 310.3),
        ),
        hrSamples = listOf(FriendHrPoint(now - 120, 64), FriendHrPoint(now - 60, 62)),
        restingBpm = 51,
    )

    private fun build(inputs: FriendsDayInputs, share: FriendShare = all) = FriendsDayPayload.build(inputs, share, now)

    // MARK: Every member

    @Test
    fun everyMemberIsBuiltAndRoundedToTheWire() {
        val day = build(full())
        assertEquals(81, day.recovery)
        assertEquals(38.6, day.strain!!, 0.0)
        assertEquals(88, day.sleepScore)
        assertEquals(
            FriendSleep(now - 30_000, now - 1_080, asleepMin = 474, awakeMin = 18, remMin = 104, lightMin = 252, deepMin = 100, needMin = 480),
            day.sleep,
        )
        assertEquals(
            listOf(FriendWorkout(now - 9_600, "Running", 1_860, strain = 35.2, avgHr = 139, maxHr = 162, kcal = 310)),
            day.workouts,
        )
        assertEquals(
            FriendHr(lastBpm = 62, lastTs = now - 60, restingBpm = 51, series = listOf(FriendHrPoint(now - 120, 64), FriendHrPoint(now - 60, 62))),
            day.hr,
        )
    }

    @Test
    fun theWireTextIsTheReadmesObjectInItsOrder() {
        val json = FriendsDayPayload.toJson(build(full()))
        assertEquals(
            "{\"recovery\":81,\"strain\":38.6,\"sleepScore\":88," +
                "\"sleep\":{\"startTs\":${now - 30_000},\"endTs\":${now - 1_080},\"asleepMin\":474,\"awakeMin\":18," +
                "\"remMin\":104,\"lightMin\":252,\"deepMin\":100,\"needMin\":480}," +
                "\"workouts\":[{\"startTs\":${now - 9_600},\"sport\":\"Running\",\"durationS\":1860,\"strain\":35.2," +
                "\"avgHr\":139,\"maxHr\":162,\"kcal\":310}]," +
                "\"hr\":{\"lastBpm\":62,\"lastTs\":${now - 60},\"restingBpm\":51,\"series\":[[${now - 120},64],[${now - 60},62]]}}",
            json,
        )
        // It is JSON a strict parser reads back, with no member the server would refuse as unknown.
        val parsed = JSONObject(json)
        assertEquals(setOf("recovery", "strain", "sleepScore", "sleep", "workouts", "hr"), parsed.keySet())
    }

    // MARK: Omission

    @Test
    fun aDayWithNothingIsAnEmptyObjectNeverZeros() {
        val day = build(FriendsDayInputs())
        assertEquals(FriendsDayBody(), day)
        assertEquals("{}", FriendsDayPayload.toJson(day))
    }

    @Test
    fun aMissingFigureIsLeftOutWhileItsNeighboursStay() {
        val day = build(full().copy(recovery = null, restingBpm = null))
        assertNull(day.recovery)
        assertEquals(38.6, day.strain!!, 0.0)
        assertNull(day.hr!!.restingBpm)
        val json = FriendsDayPayload.toJson(day)
        assertFalse(json.contains("recovery"))
        assertFalse(json.contains("restingBpm"))
        assertTrue(json.contains("\"sleepScore\":88"))
    }

    @Test
    fun optionalSleepAndWorkoutMembersAreLeftOutNotNulled() {
        val day = build(
            FriendsDayInputs(
                sleep = FriendsSleepInput(now - 30_000, now - 1_000, asleepMin = 400.0),
                workouts = listOf(FriendsWorkoutInput(now - 900, "Strength", 600.0)),
            ),
        )
        assertEquals(
            "{\"sleep\":{\"startTs\":${now - 30_000},\"endTs\":${now - 1_000},\"asleepMin\":400}," +
                "\"workouts\":[{\"startTs\":${now - 900},\"sport\":\"Strength\",\"durationS\":600}]}",
            FriendsDayPayload.toJson(day),
        )
    }

    @Test
    fun aDayWithNoWorkoutSaysSoAndAnUnreadOneSaysNothing() {
        assertEquals("{\"workouts\":[]}", FriendsDayPayload.toJson(build(FriendsDayInputs(workouts = emptyList()))))
        assertEquals("{}", FriendsDayPayload.toJson(build(FriendsDayInputs(workouts = null))))
    }

    @Test
    fun noHeartRateSampleMeansNoHeartRateSectionEvenWithARestingFigure() {
        assertNull(build(FriendsDayInputs(restingBpm = 51)).hr)
    }

    @Test
    fun figuresTheServerWouldRefuseAreLeftOutSoTheDayStillGoesUp() {
        val day = build(
            full().copy(
                recovery = 140.0,
                strain = -3.0,
                sleepScore = Double.NaN,
                workouts = listOf(
                    FriendsWorkoutInput(now - 900, "Running", 600.0, strain = 250.0, avgHr = 300, maxHr = 10, kcal = 99_999.0),
                    FriendsWorkoutInput(now - 800, "   ", 600.0),
                    FriendsWorkoutInput(100L, "Cycling", 600.0),
                    FriendsWorkoutInput(now - 700, "Rowing", 200_000.0),
                ),
                hrSamples = listOf(FriendHrPoint(now - 60, 62), FriendHrPoint(now - 30, 400), FriendHrPoint(now + 10 * 86_400, 70)),
                restingBpm = 5,
            ),
        )
        assertNull(day.recovery)
        assertNull(day.strain)
        assertNull(day.sleepScore)
        assertEquals(listOf(FriendWorkout(now - 900, "Running", 600)), day.workouts)
        assertEquals(FriendHr(62, now - 60, null, listOf(FriendHrPoint(now - 60, 62))), day.hr)
    }

    @Test
    fun aSleepThatEndsBeforeItStartsIsNotSent() {
        assertNull(build(FriendsDayInputs(sleep = FriendsSleepInput(now - 100, now - 200, 60.0))).sleep)
        assertNull(build(FriendsDayInputs(sleep = FriendsSleepInput(now - 200, now - 100, Double.NaN))).sleep)
    }

    // MARK: Sharing switches

    @Test
    fun scoresOffDropsTheThreeScoresOnly() {
        val day = build(full(), all.copy(scores = false))
        assertNull(day.recovery)
        assertNull(day.strain)
        assertNull(day.sleepScore)
        assertEquals(build(full()).copy(recovery = null, strain = null, sleepScore = null), day)
    }

    @Test
    fun sleepOffDropsTheNightOnly() {
        assertEquals(build(full()).copy(sleep = null), build(full(), all.copy(sleep = false)))
    }

    @Test
    fun workoutsOffDropsTheWorkoutsOnly() {
        assertEquals(build(full()).copy(workouts = null), build(full(), all.copy(workouts = false)))
    }

    @Test
    fun heartRateOffDropsLatestLineAndRestingTogether() {
        val day = build(full(), all.copy(hr = false))
        assertEquals(build(full()).copy(hr = null), day)
        val json = FriendsDayPayload.toJson(day)
        assertFalse(json.contains("hr"))
        assertFalse(json.contains("restingBpm"))
    }

    @Test
    fun everySwitchOffSendsNothingAtAll() {
        assertEquals("{}", FriendsDayPayload.toJson(build(full(), FriendShare.NONE)))
    }

    // MARK: Strain axis

    @Test
    fun strainStaysOnTheStored0To100AxisForTheDayAndEachWorkout() {
        // 59.05 stored is 12.4 on the 21 scale; the wire carries the stored value whatever the display scale.
        val day = build(
            FriendsDayInputs(strain = 59.05, workouts = listOf(FriendsWorkoutInput(now - 900, "Running", 600.0, strain = 100.0))),
        )
        assertEquals(59.05, day.strain!!, 0.0)
        assertEquals(100.0, day.workouts!!.single().strain!!, 0.0)
        val json = FriendsDayPayload.toJson(day)
        assertTrue(json, json.startsWith("{\"strain\":59.05,"))
        assertTrue(json, json.contains("\"strain\":100}"))
    }

    @Test
    fun strainIsWrittenWithoutExponentOrTrailingZeros() {
        fun text(strain: Double) = FriendsDayPayload.toJson(build(FriendsDayInputs(strain = strain)))
        assertEquals("{\"strain\":0}", text(0.0))
        assertEquals("{\"strain\":0.01}", text(0.005))
        assertEquals("{\"strain\":7}", text(7.0))
        assertEquals("{\"strain\":38.61}", text(38.605))
        assertEquals("{\"strain\":100}", text(99.999))
        assertEquals("{}", text(100.01))
    }

    // MARK: The heart-rate line

    @Test
    fun aDayAtOneSampleASecondIsThinnedToAtMost300Points() {
        val start = now - 86_399
        val samples = (0 until 86_400).map { FriendHrPoint(start + it, 50 + (it / 600) % 90) }
        val hr = build(FriendsDayInputs(hrSamples = samples)).hr!!
        assertTrue("got ${hr.series.size}", hr.series.size <= FriendsDayPayload.MAX_HR_POINTS)
        assertTrue("got ${hr.series.size}", hr.series.size >= 290)
        // In order, inside the day, and ending on the newest sample itself.
        assertEquals(hr.series.sortedBy { it.ts }, hr.series)
        assertEquals(hr.series.map { it.ts }.distinct().size, hr.series.size)
        assertTrue(hr.series.first().ts >= start)
        assertEquals(samples.last(), hr.series.last())
        assertEquals(samples.last().bpm, hr.lastBpm)
        assertEquals(samples.last().ts, hr.lastTs)
        assertTrue(hr.series.all { it.bpm in 20..250 })
    }

    @Test
    fun theBoundHoldsForEverySizeAroundIt() {
        for (n in listOf(1, 2, 299, 300, 301, 302, 599, 600, 601, 5_000)) {
            val samples = (0 until n).map { FriendHrPoint(now - n + it, 60 + it % 40) }
            val out = FriendsDayPayload.downsample(samples)
            assertTrue("$n -> ${out.size}", out.size <= 300)
            assertEquals("$n", samples.last(), out.last())
            if (n <= 300) assertEquals("$n", samples, out)
        }
    }

    @Test
    fun aThinnedStretchIsTheMeanOfItsSamples() {
        // 600 samples in two flat halves, thinned to 3 points: two stretches and the newest sample.
        val samples = (0 until 300).map { FriendHrPoint(now - 600 + it, 60) } +
            (300 until 600).map { FriendHrPoint(now - 600 + it, 120) }
        val out = FriendsDayPayload.downsample(samples, maxPoints = 3)
        assertEquals(3, out.size)
        assertEquals(60, out[0].bpm)
        assertEquals(120, out[1].bpm)
        assertEquals(samples.last(), out[2])
    }

    @Test
    fun samplesArriveInAnyOrderAndOnePerSecondIsKept() {
        val hr = build(
            FriendsDayInputs(hrSamples = listOf(FriendHrPoint(now - 10, 70), FriendHrPoint(now - 30, 60), FriendHrPoint(now - 10, 99))),
        ).hr!!
        assertEquals(listOf(FriendHrPoint(now - 30, 60), FriendHrPoint(now - 10, 70)), hr.series)
        assertEquals(70, hr.lastBpm)
    }

    // MARK: Workouts

    @Test
    fun atMostTwentyWorkoutsGoUpAndTheNewestStay() {
        val many = (0 until 25).map { FriendsWorkoutInput(now - 50_000 + it * 1_000L, "Walking", 600.0) }
        val sent = build(FriendsDayInputs(workouts = many.shuffled(java.util.Random(7)))).workouts!!
        assertEquals(FriendsDayPayload.MAX_WORKOUTS, sent.size)
        assertEquals(many.drop(5).map { it.startTs }, sent.map { it.startTs })
    }

    @Test
    fun aSportLabelIsTrimmedCutTo40AndStrippedOfControlCharacters() {
        assertEquals("Open-water swim", FriendsDayPayload.sportLabel("  Open-water swim \n"))
        assertEquals("a".repeat(40), FriendsDayPayload.sportLabel("a".repeat(55)))
        assertEquals("Trail run", FriendsDayPayload.sportLabel("Trail\u0007 run​"))
        assertEquals("Бег", FriendsDayPayload.sportLabel("Бег"))
        assertNull(FriendsDayPayload.sportLabel(" \t\n"))
    }

    @Test
    fun aSportLabelIsQuotedAsJson() {
        val json = FriendsDayPayload.toJson(
            build(FriendsDayInputs(workouts = listOf(FriendsWorkoutInput(now - 900, "Push \"n\" pull\\", 60.0)))),
        )
        assertEquals("Push \"n\" pull\\", JSONObject(json).getJSONArray("workouts").getJSONObject(0).getString("sport"))
    }

    // MARK: Skipping an unchanged day

    @Test
    fun aDayIsUploadedTheFirstTimeAndSkippedWhileItsTextIsTheSame() {
        val json = FriendsDayPayload.toJson(build(full()))
        assertTrue(FriendsUploadPolicy.shouldUpload(lastAcceptedFingerprint = null, json = json))
        val accepted = FriendsDayPayload.fingerprint(json)
        assertFalse(FriendsUploadPolicy.shouldUpload(accepted, json))
        // Built again from the same figures, the text is byte for byte the same.
        assertFalse(FriendsUploadPolicy.shouldUpload(accepted, FriendsDayPayload.toJson(build(full()))))
    }

    @Test
    fun anyChangedFigureOrSwitchSendsTheDayAgain() {
        val accepted = FriendsDayPayload.fingerprint(FriendsDayPayload.toJson(build(full())))
        fun changed(inputs: FriendsDayInputs, share: FriendShare = all) =
            FriendsUploadPolicy.shouldUpload(accepted, FriendsDayPayload.toJson(build(inputs, share)))
        assertTrue(changed(full().copy(recovery = 82.0)))
        assertTrue(changed(full().copy(hrSamples = full().hrSamples + FriendHrPoint(now - 5, 61))))
        assertTrue(changed(full(), all.copy(hr = false)))
        // A change below the wire's resolution is no change: 80.6 and 81.2 both go up as 81.
        assertFalse(changed(full().copy(recovery = 81.2)))
    }

    @Test
    fun theFingerprintIsSha256Hex() {
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", FriendsDayPayload.fingerprint("{}"))
        assertNotEquals(FriendsDayPayload.fingerprint("{}"), FriendsDayPayload.fingerprint("{ }"))
    }
}
