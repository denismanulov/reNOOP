package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The primary tabs (iOS RootTabView's Summary, Sleep and Workouts; Android adds Coach, shown while the
 * AI Coach switch is on, and the fork's Friends; Browse is a row in Settings). AppRoot derives the selected
 * tab from which tab ROOT is on the back stack, so the roots must be distinct routes that no pushed
 * screen shares; this pins that and the bar's order.
 */
class MainTabsTest {

    @Test
    fun theBarCarriesSummarySleepWorkoutsCoachFriends() {
        assertEquals(
            listOf(MainTab.Summary, MainTab.Sleep, MainTab.Workouts, MainTab.Coach, MainTab.Friends),
            MainTab.entries.toList(),
        )
        assertEquals(MainTab.entries.toList(), MainTab.shown(coachEnabled = true))
    }

    @Test
    fun theCoachTabFollowsTheMasterSwitchAndNothingElseDoes() {
        assertEquals(
            listOf(MainTab.Summary, MainTab.Sleep, MainTab.Workouts, MainTab.Friends),
            MainTab.shown(coachEnabled = false),
        )
    }

    @Test
    fun eachTabRootsOnItsOwnDestination() {
        assertEquals(Destination.Today.route, MainTab.Summary.route)
        assertEquals(Destination.Sleep.route, MainTab.Sleep.route)
        assertEquals(Destination.Workouts.route, MainTab.Workouts.route)
        assertEquals(Destination.Coach.route, MainTab.Coach.route)
        assertEquals(Destination.Friends.route, MainTab.Friends.route)
        assertEquals(MainTab.entries.size, MainTab.entries.map { it.route }.distinct().size)
    }

    @Test
    fun settingsIsNotATab() {
        assertTrue(MainTab.entries.none { it.route == Destination.Settings.route })
    }

    /** Browse is a row in Settings on this fork: five tabs is the most the bar carries. */
    @Test
    fun browseIsNotATabAndTheBarNeverCarriesMoreThanFive() {
        assertNull(MainTab.forRootRoute(Destination.Browse.route))
        assertTrue(MainTab.shown(coachEnabled = true).size <= 5)
    }

    @Test
    fun rootRouteResolvesItsTabAndPushedScreensResolveNone() {
        MainTab.entries.forEach { assertEquals(it, MainTab.forRootRoute(it.route)) }
        assertNull(MainTab.forRootRoute(Destination.Settings.route))
        assertNull(MainTab.forRootRoute(Destination.CoachSettings.route))
        assertNull(MainTab.forRootRoute(null))
    }

    /** No Browse row may push a tab root, or two roots would sit on one stack and the selected tab would be ambiguous. */
    @Test
    fun noBrowseRowPushesATabRoot() {
        val roots = MainTab.entries.map { it.route }.toSet()
        assertTrue(BrowseDestination.entries.none { it.route in roots })
    }

    @Test
    fun everyTabHasALabel() {
        assertTrue(MainTab.entries.all { it.labelRes != 0 })
    }
}
