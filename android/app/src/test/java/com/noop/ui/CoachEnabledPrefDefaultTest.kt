package com.noop.ui

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The stored default of the AI Coach master switch, which is the invariant that actually matters on
 * upgrade.
 *
 * It asserts the PREF, not `CoachEnabledStore.enabled`: that is an in-memory singleton seeded to true, so
 * it would pass whatever the pref does and miss the one mistake worth catching here, a pref default of
 * false, which would silently remove Coach from Browse on every existing install. (Which Browse rows the
 * switch shows is pinned in [BrowseScreenLogicTest].)
 */
@RunWith(RobolectricTestRunner::class)
class CoachEnabledPrefDefaultTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `an install that never touched the toggle has coach enabled`() {
        NoopPrefs.of(context).edit().remove(NoopPrefs.KEY_COACH_ENABLED).commit()
        assertTrue("unset must read as ON, or upgrades lose Coach", NoopPrefs.coachEnabled(context))
    }

    @Test
    fun `the stored value round-trips in both directions`() {
        NoopPrefs.setCoachEnabled(context, false)
        assertFalse(NoopPrefs.coachEnabled(context))
        NoopPrefs.setCoachEnabled(context, true)
        assertTrue(NoopPrefs.coachEnabled(context))
    }
}
