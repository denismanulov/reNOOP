package com.noop.ui

/**
 * Emit the Import & Data Ingest test-mode trace for a finished import, tagged TestDomain.IMPORT, iff the
 * mode is on. Shared by the Data Sources + Onboarding import flows (both call runImport). Gated zero-cost
 * when off: one SharedPreferences bool read before any line is built. The lines are byte-aligned with the
 * macOS ImportTrace shapes (parser / per-stage / reject / day-delta), built from the ImportSummary the
 * importer already returned. Never a file name, a path, or any health value.
 *
 * HONESTY (the whole point of this mode, tied to the #601/#749/#754 "didn't save" cluster): unlike the
 * Swift store, which returns the summed SQLite changes from each upsert, Room's @Upsert reports no
 * store-write count at this layer. So Android does NOT claim "(all written)" / "(all days persisted)" - it
 * emits rowsIn / daysMapped with rowsOut / daysPersisted marked UNVERIFIED. A line never asserts a save it
 * cannot confirm. REJECTED counts (e.g. skippedSpans - scrubbed/damaged spans, the OPPOSITE of written)
 * are routed through the reject line, never a stage line, matching AppleHealthImport.swift.
 */
internal fun emitImportTrace(
    context: android.content.Context,
    vm: AppViewModel,
    summary: com.noop.data.ImportSummary,
) {
    if (!com.noop.testcentre.TestCentre.from(context).active(com.noop.testcentre.TestDomain.IMPORT)) return
    if (summary.totalRows <= 0) return   // a failed/empty import already logged its reason above
    val kind = com.noop.analytics.ImportTrace.kindWire(summary.source)
    vm.ble.externalLog(
        com.noop.analytics.ImportTrace.parserVersionLine(kind, importerVersion = 1),
        com.noop.testcentre.TestDomain.IMPORT,
    )
    // Reject keys are NOT writes: they are rows/spans the import dropped (the opposite of "written"), so
    // they must never become a stage line. skippedSpans is the only one an Android importer emits today.
    val skippedSpans = summary.counts["skippedSpans"] ?: 0
    for ((rawKey, count) in summary.counts) {
        if (rawKey == "skippedSpans") continue   // routed through the reject line below, not as a stage
        val category = com.noop.analytics.ImportTrace.categoryWire(summary.source, rawKey)
        // rowsOut is UNVERIFIED on Android (Room reports no store-write count); never claim "(all written)".
        vm.ble.externalLog(
            com.noop.analytics.ImportTrace.stageLineUnverified(category, rowsIn = count),
            com.noop.testcentre.TestDomain.IMPORT,
        )
    }
    // #1617: which metric COLUMNS the file actually carried. rowsOut stays unverified here because Room
    // reports no write count, but this is known at PARSE time and so is exact on both platforms — and it
    // separates "the store never got it" from "the export never had it", which the stage lines alone
    // cannot. Emitted only when the importer produced daily rows.
    // Emitted generically here for any summary carrying coverage, while the Swift twin emits it inside
    // WhoopImporter — so a NEW importer that starts filling columnCoverage would produce this line on
    // Android and silently not on Swift. Give it the Swift twin at the same time.
    if (summary.columnCoverage.isNotEmpty()) {
        vm.ble.externalLog(
            com.noop.analytics.ImportTrace.columnCoverageLine(
                // The literal, not categoryWire: that function maps RAW TABLE KEYS to wire categories
                // ("dailyMetric" -> "cycles"), so handing it a category already in wire form only works
                // through its `else -> rawKey` fallthrough, and would break the day anyone adds a
                // "cycles" branch. The Swift twin passes the same literal.
                stage = "cycles",
                rows = summary.columnCoverageRows,
                counts = summary.columnCoverage,
            ),
            com.noop.testcentre.TestDomain.IMPORT,
        )
    }
    // The reject line mirrors AppleHealthImport.swift: the app map drops nothing further here, so
    // droppedRows = 0; skippedSpans carries the tolerant-import scrubbed-span count (0 on non-Apple).
    vm.ble.externalLog(
        com.noop.analytics.ImportTrace.rejectLine(droppedRows = 0, skippedSpans = skippedSpans),
        com.noop.testcentre.TestDomain.IMPORT,
    )
    // Day delta: pick the source's day-keyed table (Apple -> appleDaily, WHOOP/others -> dailyMetric) so a
    // real Apple import reports the right day count, and label the stage with the Swift category vocabulary.
    val dayKey = if (summary.counts.containsKey("appleDaily")) "appleDaily" else "dailyMetric"
    val days = summary.counts[dayKey] ?: summary.counts["days"] ?: 0
    val dayCategory = com.noop.analytics.ImportTrace.categoryWire(summary.source, dayKey)
    vm.ble.externalLog(
        com.noop.analytics.ImportTrace.dayDeltaLineUnverified(dayCategory, daysMapped = days),
        com.noop.testcentre.TestDomain.IMPORT,
    )
}
