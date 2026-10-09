package com.noop.ui

import android.content.Context
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import com.noop.ingest.ActivityFileImporter
import com.noop.ingest.LiftingImporter

// MARK: - The workouts list, read once for every surface that lists workouts
//
// This is the read the Workouts tab has always done (it lived inside AppViewModel.loadWorkouts): every
// source, strap heart rate filled into imported sessions, dismissed detected bouts removed, cross-source
// duplicates collapsed, newest first. It stands on its own so the Friends upload, which runs in the
// background with no view model alive, sends a friend exactly the workouts this list shows.

internal object WorkoutListLoader {
    /**
     * Every workout the Workouts tab lists, newest first. [deviceId] is the process's active device id
     * (`NoopApplication.activeDeviceId`). [trace] receives the cross-source dedup's trace lines while the
     * Workouts test mode is on, and is not called otherwise.
     */
    suspend fun load(
        context: Context,
        repository: WhoopRepository,
        deviceId: String,
        profile: ProfileStore,
        trace: (String) -> Unit = {},
    ): List<WorkoutRow> {
        val now = System.currentTimeMillis() / 1000
        // #28: read across the strap-id + "my-whoop" union (like HR/sleep), so a re-added/newly-paired
        // strap whose workouts live under "my-whoop" isn't shown an empty Workouts screen.
        val whoop = repository.workoutsUnion(deviceId, 0L, now)
        val apple = repository.workouts("apple-health", 0L, now) +
            repository.workouts("health-connect", 0L, now)
        val detected = repository.detectedWorkoutsUnion(deviceId, 0L, now)
        // Imported lifting sessions (Hevy / Liftosaur) carry a volume-load note but no HR — they're
        // a strength-volume estimate, not cardio. Kept OUT of the strap HR-fill below so we never
        // fabricate a heart rate the lift never measured.
        val lifting = repository.workouts(LiftingImporter.SOURCE_ID, 0L, now)
        // #29: imported activity FILES (FIT / GPX / TCX) live under their own "activity-file" source, so
        // without reading it a successful file import never appears in the Workouts list (Data Sources
        // counts it, the load didn't). They're cardio (often GPS + HR), so they go through the strap
        // HR-fill below like the imported Apple sessions — a GPX with no HR borrows the strap's, while a
        // FIT that already carries HR is untouched (fill only fills nulls).
        val activityFiles = repository.workouts(ActivityFileImporter.SOURCE_ID, 0L, now)
        val markers = repository.dismissedDetectedUnion(deviceId)
        // Fill imported sessions' missing HR from strap samples (#77), same as before; detected /
        // manual rows already carry their own HR so they pass through unchanged. #961: also backfill a
        // strap-native row's Effort (strain) from the strap trace when it's null, so a live/manual
        // session that ended with sparse HR can't show a blank Effort while the day total counted it.
        // #1601: pass the ACTIVE strap id. Left to its "my-whoop" default, the fill resolved
        // `importedSourceIdsFor("my-whoop")` = the canonical id ALONE, while the detail sheet's chart,
        // zones and HR-recovery all resolve `importedSourceIdsFor(deviceId)` = active ∪ canonical. On
        // an install whose strap banks under a non-canonical id the chart therefore found HR and this
        // fill did not, and an imported session rendered "AVG –" beside a populated graph, a full zone
        // split and a peak — every one of them derived from the samples the average claimed not to
        // have. The fill's own doc promises "display == graph == zones == effort by construction";
        // this is the line that has to pass the same id for that to hold.
        val filled = repository.fillWorkoutHrFromStrap(
            (whoop + apple + detected + activityFiles),
            strapDeviceId = deviceId,
            strainMaxHR = profile.hrMax.toDouble(),
            strainSex = profile.sex,
            effortMethod = NoopPrefs.effortMethod(context),
        )
        // #687: collapse the SAME activity tracked live under the strap AND imported from Health
        // Connect / Apple Health into one richer entry — they sit under different sources so without
        // this they show as two sessions. Dedup runs on the dismissed-filtered set, before the sort.
        val filteredRows = WorkoutEditing.filterDismissed(filled + lifting, markers)
        // Workouts & GPS test mode: when on, run the dedup twin which returns the BYTE-IDENTICAL kept list
        // plus a trace line per collapsed cross-source pair, tagged .workouts. Zero-cost when off (the gate
        // is one SharedPreferences bool read), and the kept list equals dedupCrossSource exactly, so the
        // workout list the screen shows is unchanged. Mirrors the macOS Repository.workoutRows wiring.
        val deduped = if (com.noop.testcentre.TestCentre.from(context)
                .active(com.noop.testcentre.TestDomain.WORKOUTS)
        ) {
            val (kept, lines) = WorkoutEditing.dedupCrossSourceTrace(filteredRows)
            for (line in lines) trace(line)
            kept
        } else {
            WorkoutEditing.dedupCrossSource(filteredRows)
        }
        return deduped.sortedByDescending { it.startTs }
    }
}
