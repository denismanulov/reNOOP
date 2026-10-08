package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The pure half of Browse: which rows exist, how a query matches, and the order results come in. */
class BrowseScreenLogicTest {

    private val en = Locale.ENGLISH
    private val ru = Locale.forLanguageTag("ru")

    private val englishTitles = mapOf(
        BrowseDestination.AllMetrics to "All Metrics",
        BrowseDestination.Journal to "Journal",
        BrowseDestination.LabResults to "Lab Results",
        BrowseDestination.Trends to "Trends",
        BrowseDestination.WhatMovesYou to "What Moves You",
        BrowseDestination.Devices to "Devices",
        BrowseDestination.HeartRate to "Heart Rate",
        BrowseDestination.Mindfulness to "Mindfulness",
    )

    private val russianTitles = mapOf(
        BrowseDestination.AllMetrics to "Все показатели",
        BrowseDestination.Journal to "Журнал",
        BrowseDestination.LabResults to "Результаты анализов",
        BrowseDestination.Trends to "Тренды",
        BrowseDestination.WhatMovesYou to "Что вами движет",
        BrowseDestination.Devices to "Устройства",
        BrowseDestination.HeartRate to "Пульс",
        BrowseDestination.Mindfulness to "Осознанность",
    )

    private val catalogue = listOf(
        BrowseMetricEntry("recovery", "Charge", "Charge"),
        BrowseMetricEntry("hrv", "HRV", "Charge"),
        BrowseMetricEntry("rhr", "Resting HR", "Charge"),
        BrowseMetricEntry("resp_rate", "Respiratory Rate", "Charge"),
        BrowseMetricEntry("avg_hr", "Average Heart Rate", "Heart"),
        BrowseMetricEntry("max_hr", "Max Heart Rate", "Heart"),
    )

    @Test
    fun categoriesAndToolsMatchIosLessCoach() {
        assertEquals(
            setOf(
                BrowseDestination.AllMetrics, BrowseDestination.Journal,
                BrowseDestination.LabResults, BrowseDestination.Trends, BrowseDestination.WhatMovesYou,
            ),
            browseRows(BrowseGroup.Categories).toSet(),
        )
        assertEquals(
            setOf(BrowseDestination.Devices, BrowseDestination.HeartRate, BrowseDestination.Mindfulness),
            browseRows(BrowseGroup.Tools).toSet(),
        )
    }

    @Test
    fun coachIsATabAndNotABrowseRow() {
        assertTrue(BrowseDestination.entries.none { it.route == Destination.Coach.route })
        val result = browseSearch("coach", en, titleOf = { englishTitles.getValue(it) }, catalogue = catalogue)
        assertTrue(result.isEmpty)
    }

    @Test
    fun rowsSortByLocalizedTitle() {
        val sortedEn = sortedByTitle(browseRows(BrowseGroup.Tools), en) { englishTitles.getValue(it) }
        assertEquals(listOf(BrowseDestination.Devices, BrowseDestination.HeartRate, BrowseDestination.Mindfulness), sortedEn)
        val sortedRu = sortedByTitle(browseRows(BrowseGroup.Tools), ru) { russianTitles.getValue(it) }
        // Осознанность, Пульс, Устройства.
        assertEquals(listOf(BrowseDestination.Mindfulness, BrowseDestination.HeartRate, BrowseDestination.Devices), sortedRu)
        val categoriesRu = sortedByTitle(browseRows(BrowseGroup.Categories), ru) { russianTitles.getValue(it) }
        // Все показатели, Журнал, Результаты анализов, Тренды, Что вами движет.
        assertEquals(
            listOf(
                BrowseDestination.AllMetrics, BrowseDestination.Journal,
                BrowseDestination.LabResults, BrowseDestination.Trends, BrowseDestination.WhatMovesYou,
            ),
            categoriesRu,
        )
    }

    @Test
    fun matchingIgnoresCaseDiacriticsAndSurroundingSpace() {
        assertTrue(browseMatches("Heart Rate", "  heart ", en))
        assertTrue(browseMatches("Frequência cardíaca", "cardiaca", Locale.forLanguageTag("pt-PT")))
        assertTrue(browseMatches("Écran", "ecran", Locale.FRENCH))
        assertTrue(browseMatches("Ёжик", "еж", ru))
        assertTrue(browseMatches("Осознанность", "ОСОЗ", ru))
        assertFalse(browseMatches("Heart Rate", "   ", en))
        assertFalse(browseMatches("Heart Rate", "pulse", en))
    }

    @Test
    fun searchListsScreensFirstThenMetricsEachAlphabetical() {
        val result = browseSearch("rate", en, titleOf = { englishTitles.getValue(it) }, catalogue = catalogue)
        assertEquals(listOf(BrowseDestination.HeartRate), result.screens)
        assertEquals(listOf("avg_hr", "max_hr", "resp_rate"), result.metrics.map { it.key })
    }

    @Test
    fun searchKeepsOneRowPerMetricKey() {
        val doubled = catalogue + BrowseMetricEntry("hrv", "HRV", "Charge")
        val result = browseSearch("hrv", en, titleOf = { englishTitles.getValue(it) }, catalogue = doubled)
        assertEquals(listOf("hrv"), result.metrics.map { it.key })
    }
}
