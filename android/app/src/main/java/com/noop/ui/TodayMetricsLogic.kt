package com.noop.ui

import com.noop.data.AppleDaily

/**
 * Steps for [dayKey] from the imported Apple Health / Health Connect daily aggregates, or null when
 * neither source carries a step total for that day. Backs the Today Steps-tile fallback for straps
 * NOOP can't read steps off over Bluetooth, notably the WHOOP 4.0, which DOES count steps (in the
 * official WHOOP app) but doesn't expose them to NOOP, so on a 4.0 the tile shows imported steps
 * rather than "No Data". On-device WHOOP 5/MG steps (DailyMetric.steps) still take precedence at the
 * call site. When both sources report the same day, the larger (most-complete) total wins so we never
 * sum and double-count. Mirrors the macOS TodayView, which already falls back to imported steps. (#150)
 */
internal fun stepsForDay(apple: List<AppleDaily>, healthConnect: List<AppleDaily>, dayKey: String): Int? =
    (apple + healthConnect)
        .filter { it.day == dayKey }
        .mapNotNull { it.steps }
        .maxOrNull()

