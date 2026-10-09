package com.noop.ui

import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Devices link word's priority (#221), now the one decision the row, the active card and the
 * device page all read: "Connected · not paired" must beat "Connected" but yield to a reboot's
 * "Reconnecting…", and a device that is not the active one reads "Not connected". Mirrors the Swift
 * `DevicePillStateTests` (`DevicePillState.resolve`) exactly — a silent reorder on either platform would
 * otherwise only be caught by eyeballing a screenshot.
 */
class DevicePillStateTest {

    @Test
    fun bondRefused_beatsConnected_butYieldsToReconnecting() {
        assertEquals(
            DeviceLink.NOT_PAIRED,
            deviceLink(isArchived = false, isActive = true, isReconnecting = false, bondRefused = true, isLiveConnected = true),
        )
        assertEquals(
            DeviceLink.RECONNECTING,
            deviceLink(isArchived = false, isActive = true, isReconnecting = true, bondRefused = true, isLiveConnected = true),
        )
    }

    @Test
    fun normalConnect_isUnaffected() {
        assertEquals(
            DeviceLink.CONNECTED,
            deviceLink(isArchived = false, isActive = true, isReconnecting = false, bondRefused = false, isLiveConnected = true),
        )
        assertEquals(
            DeviceLink.NOT_CONNECTED,
            deviceLink(isArchived = false, isActive = true, isReconnecting = false, bondRefused = false, isLiveConnected = false),
        )
    }

    @Test
    fun nonActiveAndArchived() {
        assertEquals(
            DeviceLink.NOT_CONNECTED,
            deviceLink(isArchived = false, isActive = false, isReconnecting = false, bondRefused = false, isLiveConnected = false),
        )
        // Archived beats everything, even a stale live flag.
        assertEquals(
            DeviceLink.REMOVED,
            deviceLink(isArchived = true, isActive = true, isReconnecting = true, bondRefused = true, isLiveConnected = true),
        )
    }

    private fun row(kind: SourceKind, brand: String = "X", model: String = "Y", nickname: String? = null) = PairedDeviceRow(
        id = "id-${kind.name}", brand = brand, model = model, nickname = nickname, peripheralId = null,
        sourceKind = kind.name, capabilities = "", status = DeviceStatus.paired.name, addedAt = 0, lastSeenAt = 0,
    )

    /** I-1: an import partition is never an active-device candidate, so a tap opens its page instead. */
    @Test
    fun importPartitionsAreNotActivatable() {
        assertTrue(DeviceReadout.isImportSource(row(SourceKind.fileImport)))
        assertTrue(DeviceReadout.isImportSource(row(SourceKind.cloudImport)))
        assertTrue(DeviceReadout.isImportSource(row(SourceKind.activityFile)))
        assertFalse(DeviceReadout.isImportSource(row(SourceKind.liveBLE)))
        assertFalse(DeviceReadout.isImportSource(row(SourceKind.oura)))
    }

    @Test
    fun displayName_collapsesBrandIntoModel() {
        assertEquals("WHOOP", displayName(row(SourceKind.liveBLE, brand = "WHOOP", model = "WHOOP")))
        assertEquals("WHOOP 4.0", displayName(row(SourceKind.liveBLE, brand = "WHOOP", model = "4.0")))
        assertEquals("Mine", displayName(row(SourceKind.liveBLE, brand = "WHOOP", model = "4.0", nickname = "Mine")))
    }

    @Test
    fun signalBars_bucketsRssiCoarsely() {
        assertEquals(4, SignalBars.level(-50))
        assertEquals(3, SignalBars.level(-60))
        assertEquals(2, SignalBars.level(-75))
        assertEquals(1, SignalBars.level(-85))
        assertEquals(0, SignalBars.level(-95))
    }
}
