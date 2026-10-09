package com.noop.ble

/**
 * EXPERIMENTAL Garmin support — recognition only.
 *
 * Faithful Kotlin twin of Strand/BLE/GarminBroadcast.swift.
 *
 * HONEST, NON-PROPRIETARY BY DESIGN. Garmin watches do NOT expose a NOOP-readable proprietary live
 * stream. They DO broadcast the STANDARD Bluetooth Heart Rate profile (0x180D / 0x2A37) when the user
 * turns on "Broadcast Heart Rate" on the watch. So Garmin live HR is the EXISTING generic-HR path
 * ([StandardHrSource]) — there is nothing Garmin-proprietary to implement, and we don't pretend there is.
 *
 * A Garmin device is registered with sourceKind "liveBLE" so the SourceCoordinator already runs it
 * through [StandardHrSource] — no new BLE driver is needed, and the WHOOP/standard paths are untouched.
 */
object GarminBroadcast {

    /** True when the advertised name reads as a Garmin watch. */
    fun isGarmin(name: String): Boolean = ExperimentalBrand.recognise(name) == ExperimentalBrand.GARMIN
}
