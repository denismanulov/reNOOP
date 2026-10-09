package com.noop.ui.workouts

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.noop.R
import com.noop.ui.WorkoutEditing

// MARK: - Sport names in the reader's language
//
// The catalogue (WorkoutSport / ExerciseTypes) and every stored row keep the English, locale-stable sport
// name: it is what cross-source dedup and the Health Connect write-back key on. Only the DISPLAY is
// translated, through this table, exactly as iOS `WorkoutSource.localizedSport` looks the display name up
// in its string catalogue. A free-typed or imported name with no entry shows as stored.

/** Catalogue name (and "Activity", how a detected bout reads) -> its translated string. */
internal val sportNameRes: Map<String, Int> = mapOf(
    "Running" to R.string.sport_running,
    "Walking" to R.string.sport_walking,
    "Hiking" to R.string.sport_hiking,
    "Cycling" to R.string.sport_cycling,
    "Open-water swim" to R.string.sport_open_water_swim,
    "Rowing" to R.string.sport_rowing,
    "Treadmill run" to R.string.sport_treadmill_run,
    "Indoor cycle" to R.string.sport_indoor_cycle,
    "Pool swim" to R.string.sport_pool_swim,
    "Row machine" to R.string.sport_row_machine,
    "Elliptical" to R.string.sport_elliptical,
    "Strength" to R.string.sport_strength,
    "Weightlifting" to R.string.sport_weightlifting,
    "HIIT" to R.string.sport_hiit,
    "Yoga" to R.string.sport_yoga,
    "Pilates" to R.string.sport_pilates,
    "Boxing" to R.string.sport_boxing,
    "Basketball" to R.string.sport_basketball,
    "Soccer" to R.string.sport_soccer,
    "Baseball" to R.string.sport_baseball,
    "Ice Hockey" to R.string.sport_ice_hockey,
    "Badminton" to R.string.sport_badminton,
    "Tennis" to R.string.sport_tennis,
    "Squash" to R.string.sport_squash,
    "Racquetball" to R.string.sport_racquetball,
    "Table tennis" to R.string.sport_table_tennis,
    "Volleyball" to R.string.sport_volleyball,
    "Martial arts" to R.string.sport_martial_arts,
    "Dancing" to R.string.sport_dancing,
    "Golf" to R.string.sport_golf,
    "Climbing" to R.string.sport_climbing,
    "Stretching" to R.string.sport_stretching,
    "Skiing" to R.string.sport_skiing,
    "Snowboarding" to R.string.sport_snowboarding,
    "American football" to R.string.sport_american_football,
    "Australian football" to R.string.sport_australian_football,
    "Rugby" to R.string.sport_rugby,
    "Cricket" to R.string.sport_cricket,
    "Softball" to R.string.sport_softball,
    "Handball" to R.string.sport_handball,
    "Water polo" to R.string.sport_water_polo,
    "Frisbee" to R.string.sport_frisbee,
    "Surfing" to R.string.sport_surfing,
    "Kayaking" to R.string.sport_kayaking,
    "Sailing" to R.string.sport_sailing,
    "Scuba diving" to R.string.sport_scuba_diving,
    "Ice skating" to R.string.sport_ice_skating,
    "Inline skating" to R.string.sport_inline_skating,
    "Snowshoeing" to R.string.sport_snowshoeing,
    "Gymnastics" to R.string.sport_gymnastics,
    "Fencing" to R.string.sport_fencing,
    "Calisthenics" to R.string.sport_calisthenics,
    "Stair climber" to R.string.sport_stair_climber,
    "Boot camp" to R.string.sport_boot_camp,
    "Other" to R.string.sport_other,
    "Padel" to R.string.sport_padel,
    "Pickleball" to R.string.sport_pickleball,
    "Bowling" to R.string.sport_bowling,
    "Treadmill walk" to R.string.sport_treadmill_walk,
    "Bodybuilding" to R.string.sport_bodybuilding,
    "Lacrosse" to R.string.sport_lacrosse,
    "Field hockey" to R.string.sport_field_hockey,
    "CrossFit" to R.string.sport_crossfit,
    "Kickboxing" to R.string.sport_kickboxing,
    "Mountain biking" to R.string.sport_mountain_biking,
    "Skateboarding" to R.string.sport_skateboarding,
    "Stand-up paddleboard" to R.string.sport_stand_up_paddleboard,
    "Spinning" to R.string.sport_spinning,
    "Jump rope" to R.string.sport_jump_rope,
    "Powerlifting" to R.string.sport_powerlifting,
    "Rucking" to R.string.sport_rucking,
    "Sand volleyball" to R.string.sport_sand_volleyball,
    "Archery" to R.string.sport_archery,
    "Fishing" to R.string.sport_fishing,
    "Hunting" to R.string.sport_hunting,
    "Curling" to R.string.sport_curling,
    "Netball" to R.string.sport_netball,
    "Gaelic football" to R.string.sport_gaelic_football,
    "Spikeball" to R.string.sport_spikeball,
    "Meditation" to R.string.sport_meditation,
    "Horseback riding" to R.string.sport_horseback_riding,
    "Wheelchair" to R.string.sport_wheelchair,
    "Gaming" to R.string.sport_gaming,
    "Motor racing" to R.string.sport_motor_racing,
    "Nordic walking" to R.string.sport_nordic_walking,
    "Ballet" to R.string.sport_ballet,
    "Billiards" to R.string.sport_billiards,
    "Breakdancing" to R.string.sport_breakdancing,
    "Cheerleading" to R.string.sport_cheerleading,
    "Darts" to R.string.sport_darts,
    "Disc golf" to R.string.sport_disc_golf,
    "Hurling/Camogie" to R.string.sport_hurling_camogie,
    "Jiu jitsu" to R.string.sport_jiu_jitsu,
    "Judo" to R.string.sport_judo,
    "Kiteboarding" to R.string.sport_kiteboarding,
    "Motocross" to R.string.sport_motocross,
    "Muay Thai" to R.string.sport_muay_thai,
    "Paintball" to R.string.sport_paintball,
    "Parkour" to R.string.sport_parkour,
    "Polo" to R.string.sport_polo,
    "Skydiving" to R.string.sport_skydiving,
    "Activity" to R.string.sport_activity,
)

private val sportNameResLower: Map<String, Int> = sportNameRes.mapKeys { it.key.lowercase() }

/** The display name of a stored [sport] in the app's language. */
internal fun localizedSport(resources: Resources, sport: String): String {
    val display = WorkoutEditing.displaySport(sport)
    val res = sportNameResLower[display.trim().lowercase()] ?: return display
    return resources.getString(res)
}

/** [localizedSport] for the current composition (follows a language change). */
@Composable
@ReadOnlyComposable
internal fun sportLabel(sport: String): String {
    val display = WorkoutEditing.displaySport(sport)
    val res = sportNameResLower[display.trim().lowercase()] ?: return display
    return stringResource(res)
}
