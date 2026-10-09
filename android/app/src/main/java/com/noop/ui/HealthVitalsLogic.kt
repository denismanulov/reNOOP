package com.noop.ui

import com.noop.analytics.SkinTempDisplay

/**
 * Whether the skin-temp screen must explain a SHORTENED series (#1847).
 *
 * Leading with the absolute means the chart and table must read the absolute column, because an absolute
 * plotted against a history of deviations is arithmetic on two scales. Nights that only ever recorded a
 * deviation therefore drop out — and after a sync refills the 21-night `analyzeRecent` window on an install
 * with older history, the reading count visibly falls with nothing on screen saying why.
 *
 * ONLY when leading with the absolute. The deviation-led branch also drops rows — calibrating nights that
 * have only an absolute, and the #622 bimodal partition — but they are the OPPOSITE kind, so this note's
 * sentence would be precisely backwards there. Gated inside the rule rather than at the call site, because
 * the function name promises the whole rule.
 *
 * True only when rows were actually dropped, so a complete series stays silent.
 */
internal fun shouldExplainShortenedSkinTempSeries(
    leadsAbsolute: Boolean,
    shownReadings: Int,
    rowsWithEitherNumber: Int,
): Boolean = leadsAbsolute && shownReadings < rowsWithEitherNumber

/**
 * Whether the skin-temp screen must EXPLAIN itself (#1847).
 *
 * `leadReading` deliberately falls back: asking for a temperature on a night that only ever recorded a
 * deviation shows the deviation rather than blanking. That is right, but silent — the setting then looks
 * broken, because both choices render the same Δ°C.
 *
 * Requires that NO night in the window carries a temperature. #1850 removed the other case entirely:
 * the preference now applies across the window, so a single stored temperature anywhere means the screen
 * leads with temperatures rather than falling back and needing to explain itself.
 *
 * Pure so the decision is testable without Compose.
 */
internal fun shouldExplainSkinTempFallback(
    prefer: SkinTempDisplay.Kind,
    leadsAbsolute: Boolean,
    anyAbsoluteInWindow: Boolean,
): Boolean = prefer == SkinTempDisplay.Kind.ABSOLUTE && !leadsAbsolute && !anyAbsoluteInWindow

