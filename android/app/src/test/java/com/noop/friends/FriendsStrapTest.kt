package com.noop.friends

import com.noop.data.PairedDeviceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Which strap a friends account is bound to: the handle's vector, what is recorded where a serial is
 * confirmed, the two sightings a WHOOP 4.0's serial needs, and which devices have a serial at all.
 * The cases of the Swift `FriendsStrapTests`.
 */
class FriendsStrapTest {
    private val handle = "98c15f4b6c7ad639bba026d0352acab84406af76243690aac8b169a1a902f707"

    private fun device(id: String, brand: String, status: String = "active") = PairedDeviceRow(
        id = id, brand = brand, model = brand, nickname = null, sourceKind = "liveBLE", capabilities = "",
        status = status, addedAt = 0, lastSeenAt = 0,
    )

    /** The literal is the contract's (`friends-server/README.md`), shared with the server and the Swift app. */
    @Test
    fun theHandleIsTheContractsVector() {
        assertEquals(handle, FriendsStrap.handle("whoop-4A0123456"))
        assertNotEquals(
            "the brand is part of the id, so two brands cannot share a handle",
            FriendsStrap.handle("whoop-4A0123456"), FriendsStrap.handle("oura-4A0123456"),
        )
    }

    /** The second line of each pair in [FriendsWireOracle]: the handle as the Swift side computes it. */
    @Test
    fun aHandleIsTheSwiftTwins() {
        val lines = FriendsWireOracle.section("strapHandle")
        val handles = lines.filterIndexed { index, _ -> index % 2 == 1 }
        assertTrue(handles.size >= 8)
        for (line in handles) {
            val id = FriendsWireOracle.input(line)
            assertEquals(line, FriendsWireOracle.line(id, FriendsStrap.handle(id)))
        }
    }

    @Test
    fun aConfirmedSerialIsKeptAgainstTheRegistryId() {
        val prefs = MemoryFriendsPrefs()
        assertNull(FriendsStrap.adoptedId("my-whoop", prefs))
        FriendsStrap.note("whoop-4A0123456", "my-whoop", prefs)
        assertEquals("whoop-4A0123456", FriendsStrap.adoptedId("my-whoop", prefs))
        assertNull(FriendsStrap.adoptedId("whoop-OTHER", prefs))
        FriendsStrap.note("", "my-whoop", prefs)
        assertEquals("an empty id records nothing", "whoop-4A0123456", FriendsStrap.adoptedId("my-whoop", prefs))
    }

    /**
     * A WHOOP 4.0's serial counts on its second sighting. The first is kept in the preferences, not in
     * memory, so the second may come after the app was relaunched.
     */
    @Test
    fun aSerialSeenOnceIsRecordedWhenItIsSeenAgain() {
        val prefs = MemoryFriendsPrefs()
        assertFalse(FriendsStrap.sight("whoop-4A0123456", "my-whoop", prefs))
        assertNull("one sighting records nothing", FriendsStrap.adoptedId("my-whoop", prefs))
        assertTrue(FriendsStrap.sight("whoop-4A0123456", "my-whoop", prefs))
        assertEquals("whoop-4A0123456", FriendsStrap.adoptedId("my-whoop", prefs))
        assertNull("the sighting is spent", prefs.string(FriendsStrap.SIGHTED_ID_PREFIX + "my-whoop"))
        assertTrue("a recorded serial stays recorded", FriendsStrap.sight("whoop-4A0123456", "my-whoop", prefs))
        assertNull(prefs.string(FriendsStrap.SIGHTED_ID_PREFIX + "my-whoop"))
    }

    /** Two different values are one sighting of each, which is what a per-connection token would give. */
    @Test
    fun aDifferentSerialStartsTheCountAgain() {
        val prefs = MemoryFriendsPrefs()
        assertFalse(FriendsStrap.sight("whoop-4A0123456", "my-whoop", prefs))
        assertFalse(FriendsStrap.sight("whoop-4B7654321", "my-whoop", prefs))
        assertFalse(FriendsStrap.sight("whoop-4A0123456", "my-whoop", prefs))
        assertNull(FriendsStrap.adoptedId("my-whoop", prefs))
        assertTrue(FriendsStrap.sight("whoop-4A0123456", "my-whoop", prefs))
    }

    @Test
    fun anotherStrapUnderARecordedIdReplacesItOnItsSecondSighting() {
        val prefs = MemoryFriendsPrefs()
        FriendsStrap.note("whoop-4A0123456", "my-whoop", prefs)
        assertFalse(FriendsStrap.sight("whoop-4B7654321", "my-whoop", prefs))
        assertEquals("whoop-4A0123456", FriendsStrap.adoptedId("my-whoop", prefs))
        assertTrue(FriendsStrap.sight("whoop-4B7654321", "my-whoop", prefs))
        assertEquals("whoop-4B7654321", FriendsStrap.adoptedId("my-whoop", prefs))
    }

    @Test
    fun sightingsAreCountedPerRegistryIdAndAnEmptyIdIsNoSighting() {
        val prefs = MemoryFriendsPrefs()
        assertFalse(FriendsStrap.sight("whoop-4A0123456", "my-whoop", prefs))
        assertFalse(
            "a sighting under one registry id does not count under another",
            FriendsStrap.sight("whoop-4A0123456", "whoop-OTHER", prefs),
        )
        assertFalse(FriendsStrap.sight("", "my-whoop", prefs))
        assertFalse(FriendsStrap.sight("whoop-4A0123456", "", prefs))
        assertTrue("nothing is recorded yet", prefs.keys().all { it.startsWith(FriendsStrap.SIGHTED_ID_PREFIX) })
    }

    @Test
    fun aStrapIsAHandleOnceItsSerialIsKnownAndPendingBefore() {
        assertEquals(FriendsStrap.Identity.Handle(handle), FriendsStrap.identity(hasSerial = true, adoptedId = "whoop-4A0123456"))
        assertEquals(FriendsStrap.Identity.Pending, FriendsStrap.identity(hasSerial = true, adoptedId = null))
        assertEquals(FriendsStrap.Identity.None, FriendsStrap.identity(hasSerial = false, adoptedId = null))
    }

    @Test
    fun onlyAWhoopOrAnOuraHasASerialToBindTo() {
        assertTrue(FriendsStrap.hasSerial(device("my-whoop", "WHOOP")))
        assertTrue(FriendsStrap.hasSerial(device("whoop-4A0123456", "whoop")))
        assertTrue(FriendsStrap.hasSerial(device("oura-2H3B2405003655", "Oura")))
        assertFalse(FriendsStrap.hasSerial(device("strap-1A2B", "Polar")))
        assertFalse(FriendsStrap.hasSerial(device("garmin-1A2B", "Garmin")))
    }

    @Test
    fun withNoRegistryNothingIsKnownYet() {
        assertEquals(FriendsStrap.Identity.Pending, FriendsStrap.identity(null, MemoryFriendsPrefs()))
        assertEquals(FriendsStrap.Identity.Pending, FriendsStrap.identity(emptyList(), MemoryFriendsPrefs()))
    }

    @Test
    fun aRegistryIdIsSerialDerivedOnlyWhenItsRemainderIsASerial() {
        val uuid = UUID.randomUUID().toString()
        assertEquals("whoop-4A0123456", FriendsStrap.serialDerivedId("whoop-4A0123456"))
        assertNull("an upper-case UUID is a provisional pairing id", FriendsStrap.serialDerivedId("whoop-${uuid.uppercase()}"))
        assertNull("so is a lower-case one", FriendsStrap.serialDerivedId("whoop-$uuid"))
        assertNull("the legacy seed relies on the stored value", FriendsStrap.serialDerivedId("my-whoop"))
        assertEquals("oura-2H3B2405003655", FriendsStrap.serialDerivedId("oura-2H3B2405003655"))
        assertNull(FriendsStrap.serialDerivedId("oura-$uuid"))
        assertNull(FriendsStrap.serialDerivedId("strap-1A2B"))
        assertNull(FriendsStrap.serialDerivedId("whoop-"))
        assertNull("a lower-case serial is not the id the registry would hold", FriendsStrap.serialDerivedId("whoop-4a0123456"))
    }

    /**
     * On Android the wizard mints a provisional id from the Bluetooth address, before any serial is
     * read. That id names a pairing, not a strap, exactly as the UUID does on iOS.
     */
    @Test
    fun anIdMintedFromABluetoothAddressIsNotSerialDerived() {
        assertNull(FriendsStrap.serialDerivedId("whoop-AA:BB:CC:DD:EE:FF"))
        assertNull(FriendsStrap.serialDerivedId("oura-AA:BB:CC:DD:EE:FF"))
        assertNull(FriendsStrap.serialDerivedId("oura-a0:b1:c2:d3:e4:f5"))
        assertNull(FriendsStrap.serialDerivedId("oura-AA-BB-CC-DD-EE-FF"))
        assertEquals(
            FriendsStrap.Identity.Pending,
            FriendsStrap.identity(listOf(device("whoop-AA:BB:CC:DD:EE:FF", "WHOOP")), MemoryFriendsPrefs()),
        )
        assertEquals(
            FriendsStrap.Identity.Pending,
            FriendsStrap.identity(listOf(device("oura-AA:BB:CC:DD:EE:FF", "Oura")), MemoryFriendsPrefs()),
        )
    }

    @Test
    fun aSerialKeyedActiveRowIsAHandleWithNothingStored() {
        assertEquals(
            FriendsStrap.Identity.Handle(handle),
            FriendsStrap.identity(listOf(device("whoop-4A0123456", "WHOOP")), MemoryFriendsPrefs()),
        )
    }

    /**
     * A row whose own id is serial-derived names its strap by itself. A value stored against that id
     * (left by another strap confirmed earlier) does not speak for it.
     */
    @Test
    fun aSerialKeyedActiveRowIsTheHandleOfItsOwnIdWhateverIsStoredAgainstIt() {
        val prefs = MemoryFriendsPrefs()
        FriendsStrap.note("whoop-4B7654321", "whoop-4A0123456", prefs)
        assertEquals(FriendsStrap.Identity.Handle(handle), FriendsStrap.identity(listOf(device("whoop-4A0123456", "WHOOP")), prefs))
        // The legacy seed has no serial in its id, so the stored value is all there is.
        FriendsStrap.note("whoop-4A0123456", "my-whoop", prefs)
        assertEquals(FriendsStrap.Identity.Handle(handle), FriendsStrap.identity(listOf(device("my-whoop", "WHOOP")), prefs))
    }

    @Test
    fun anArchivedRowIsNotAStrapAndADeviceWithoutASerialIsNone() {
        val prefs = MemoryFriendsPrefs()
        assertEquals(
            FriendsStrap.Identity.Pending,
            FriendsStrap.identity(listOf(device("whoop-4A0123456", "WHOOP", status = "archived")), prefs),
        )
        assertEquals(FriendsStrap.Identity.None, FriendsStrap.identity(listOf(device("strap-1A2B", "Polar")), prefs))
        assertEquals(
            "the active row decides, not an archived strap beside it",
            FriendsStrap.Identity.None,
            FriendsStrap.identity(listOf(device("whoop-4A0123456", "WHOOP", status = "archived"), device("strap-1A2B", "Polar")), prefs),
        )
    }

    // MARK: - A WHOOP 4.0 hello

    /**
     * A legacy `my-whoop` strap that stays connected sends one hello a run. The first is counted, the
     * second (a run later, the preferences being the same) records the serial, and Friends has its strap.
     */
    @Test
    fun twoHellosInTwoRunsOfTheAppMakeTheStrapKnown() {
        val prefs = MemoryFriendsPrefs()
        val registry = listOf(device("my-whoop", "WHOOP"))
        assertEquals(FriendsStrap.Identity.Pending, FriendsStrap.identity(registry, prefs))
        assertEquals(FriendsStrapWatch.Sighting.ONCE, FriendsStrapWatch.sight("4A0123456", registry, prefs))
        assertEquals("one hello is not enough", FriendsStrap.Identity.Pending, FriendsStrap.identity(registry, prefs))
        // The next run of the app: nothing held in memory carries over, the preferences do.
        assertEquals(FriendsStrapWatch.Sighting.RECORDED, FriendsStrapWatch.sight("4A0123456", registry, prefs))
        assertEquals(FriendsStrap.Identity.Handle(handle), FriendsStrap.identity(registry, prefs))
        assertEquals("recorded under the id the row would have once adopted, too", "whoop-4A0123456", FriendsStrap.adoptedId("whoop-4A0123456", prefs))
        assertEquals(FriendsStrapWatch.Sighting.KNOWN, FriendsStrapWatch.sight("4A0123456", registry, prefs))
    }

    @Test
    fun aHelloCountsOnlyForAnActiveWhoopAndOnlyWhenItCarriesASerial() {
        val prefs = MemoryFriendsPrefs()
        assertEquals(FriendsStrapWatch.Sighting.IGNORED, FriendsStrapWatch.sight("4A0123456", null, prefs))
        assertEquals(FriendsStrapWatch.Sighting.IGNORED, FriendsStrapWatch.sight("4A0123456", listOf(device("oura-X1", "Oura")), prefs))
        assertEquals(
            FriendsStrapWatch.Sighting.IGNORED,
            FriendsStrapWatch.sight("4A0123456", listOf(device("my-whoop", "WHOOP", status = "archived")), prefs),
        )
        assertEquals(FriendsStrapWatch.Sighting.IGNORED, FriendsStrapWatch.sight("  ", listOf(device("my-whoop", "WHOOP")), prefs))
        assertEquals(FriendsStrapWatch.Sighting.IGNORED, FriendsStrapWatch.sight("a b c d", listOf(device("my-whoop", "WHOOP")), prefs))
        assertTrue(prefs.keys().isEmpty())
    }

    /** No line of the strap log carries any of the serial. */
    @Test
    fun whatASightingLogsSaysNothingOfTheSerial() {
        for (sighting in FriendsStrapWatch.Sighting.entries) {
            val line = sighting.logLine ?: continue
            assertTrue(line.startsWith("Friends: "))
            assertFalse(line.contains("4A0"))
        }
    }

    /** A serial confirmed in one go (a 5/MG's, or a 4.0's second sighting within a run) is recorded at once. */
    @Test
    fun aConfirmedSerialIsRecordedForTheActiveWhoop() {
        val prefs = MemoryFriendsPrefs()
        FriendsStrapWatch.confirm("5am0123456789", listOf(device("my-whoop", "WHOOP")), prefs)
        assertEquals("whoop-5AM0123456789", FriendsStrap.adoptedId("my-whoop", prefs))
        assertEquals("whoop-5AM0123456789", FriendsStrap.adoptedId("whoop-5AM0123456789", prefs))
        val other = MemoryFriendsPrefs()
        FriendsStrapWatch.confirm("5AM0123456789", listOf(device("strap-1A2B", "Polar")), other)
        assertTrue("not for a device that is not a WHOOP", other.keys().isEmpty())
    }
}
