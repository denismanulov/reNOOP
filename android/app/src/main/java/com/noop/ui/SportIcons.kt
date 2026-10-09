package com.noop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Accessible
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.IceSkating
import androidx.compose.material.icons.filled.Kayaking
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Rowing
import androidx.compose.material.icons.filled.Sailing
import androidx.compose.material.icons.filled.ScubaDiving
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Skateboarding
import androidx.compose.material.icons.filled.Snowboarding
import androidx.compose.material.icons.filled.Snowshoeing
import androidx.compose.material.icons.filled.SportsBaseball
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.SportsCricket
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material.icons.filled.SportsGolf
import androidx.compose.material.icons.filled.SportsGymnastics
import androidx.compose.material.icons.filled.SportsHandball
import androidx.compose.material.icons.filled.SportsHockey
import androidx.compose.material.icons.filled.SportsMartialArts
import androidx.compose.material.icons.filled.SportsMotorsports
import androidx.compose.material.icons.filled.SportsRugby
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SportsTennis
import androidx.compose.material.icons.filled.SportsVolleyball
import androidx.compose.material.icons.filled.Surfing
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.ui.graphics.vector.ImageVector

// MARK: - Sport icons (Material equivalents of the SF Symbols the iOS WorkoutTypeIcon draws)
//
// Moved here unchanged from the old Workouts screen when the Fitness-style tab replaced it: the new Workouts
// screens and the Insights activity-cost rows both glyph a sport with it.

/** The Material glyph for a stored sport name (catalogue names exactly, free text by substring). */
internal fun sportIcon(sport: String): ImageVector = when (sport.lowercase().trim()) {
    // Per-sport glyphs, parity with the iOS SportIcon catalogue. Exact catalogue names first (each gets
    // its closest Material sports glyph); free-typed / auto-detected labels fall through to the fuzzy
    // matcher below. Sports Material has no dedicated glyph for (Dancing, Frisbee, Archery, Fishing,
    // Hunting, Curling, Horseback riding, Elliptical, ...) resolve to the generic FitnessCenter there.
    "running", "treadmill run" -> Icons.AutoMirrored.Filled.DirectionsRun
    "walking", "treadmill walk" -> Icons.AutoMirrored.Filled.DirectionsWalk
    "hiking", "rucking" -> Icons.Filled.Hiking
    "cycling", "indoor cycle", "spinning", "mountain biking" -> Icons.AutoMirrored.Filled.DirectionsBike
    "open-water swim", "pool swim", "water polo" -> Icons.Filled.Pool
    "rowing", "row machine" -> Icons.Filled.Rowing
    "kayaking", "stand-up paddleboard" -> Icons.Filled.Kayaking
    "surfing" -> Icons.Filled.Surfing
    "sailing" -> Icons.Filled.Sailing
    "scuba diving" -> Icons.Filled.ScubaDiving
    "skiing" -> Icons.Filled.DownhillSkiing
    "snowboarding" -> Icons.Filled.Snowboarding
    "snowshoeing" -> Icons.Filled.Snowshoeing
    "ice skating" -> Icons.Filled.IceSkating
    "inline skating", "skateboarding" -> Icons.Filled.Skateboarding
    "hiit", "gymnastics" -> Icons.Filled.SportsGymnastics
    "yoga", "pilates", "stretching", "meditation" -> Icons.Filled.SelfImprovement
    "boxing", "martial arts", "kickboxing", "fencing" -> Icons.Filled.SportsMartialArts
    "basketball", "netball" -> Icons.Filled.SportsBasketball
    "soccer", "gaelic football" -> Icons.Filled.SportsSoccer
    "american football", "australian football" -> Icons.Filled.SportsFootball
    "rugby" -> Icons.Filled.SportsRugby
    "cricket" -> Icons.Filled.SportsCricket
    "baseball", "softball", "bowling" -> Icons.Filled.SportsBaseball
    "handball" -> Icons.Filled.SportsHandball
    "ice hockey", "field hockey", "lacrosse" -> Icons.Filled.SportsHockey
    "tennis", "badminton", "squash", "racquetball", "table tennis", "padel", "pickleball" ->
        Icons.Filled.SportsTennis
    "volleyball", "sand volleyball", "spikeball" -> Icons.Filled.SportsVolleyball
    "golf" -> Icons.Filled.SportsGolf
    "climbing" -> Icons.Filled.Terrain
    "wheelchair" -> Icons.AutoMirrored.Filled.Accessible
    "gaming" -> Icons.Filled.SportsEsports
    "motor racing" -> Icons.Filled.SportsMotorsports
    else -> sportIconFuzzy(sport)
}

/// Free-text / auto-detected label fallback (e.g. "Morning Run", "trail hike"): fuzzy substring match,
/// then the generic dumbbell. Catalogue names are handled exactly by [sportIcon] before this runs.
private fun sportIconFuzzy(sport: String): ImageVector {
    val s = sport.lowercase()
    return when {
        s.contains("run") -> Icons.AutoMirrored.Filled.DirectionsRun
        s.contains("walk") || s.contains("hike") -> Icons.AutoMirrored.Filled.DirectionsWalk
        s.contains("cycl") || s.contains("bike") || s.contains("ride") || s.contains("spin") -> Icons.AutoMirrored.Filled.DirectionsBike
        // "polo" alone used to land here, which gave horseback Polo a swimming-pool glyph. Water polo
        // is matched exactly above; this only needs to catch the free-typed run-together spelling.
        s.contains("swim") || s.contains("water polo") || s.contains("waterpolo") -> Icons.Filled.Pool
        s.contains("row") -> Icons.Filled.Rowing
        s.contains("kayak") || s.contains("paddle") -> Icons.Filled.Kayaking
        s.contains("surf") -> Icons.Filled.Surfing
        s.contains("sail") -> Icons.Filled.Sailing
        s.contains("scuba") || s.contains("dive") -> Icons.Filled.ScubaDiving
        s.contains("snowboard") -> Icons.Filled.Snowboarding
        s.contains("ski") -> Icons.Filled.DownhillSkiing
        s.contains("skat") || s.contains("board") -> Icons.Filled.Skateboarding
        s.contains("cricket") -> Icons.Filled.SportsCricket
        s.contains("rugby") -> Icons.Filled.SportsRugby
        s.contains("hockey") -> Icons.Filled.SportsHockey
        s.contains("handball") -> Icons.Filled.SportsHandball
        s.contains("softball") || s.contains("baseball") || s.contains("bowl") -> Icons.Filled.SportsBaseball
        s.contains("yoga") || s.contains("pilates") || s.contains("meditat") || s.contains("stretch") -> Icons.Filled.SelfImprovement
        s.contains("strength") || s.contains("weight") || s.contains("lift") -> Icons.Filled.FitnessCenter
        s.contains("box") || s.contains("martial") || s.contains("jiu") || s.contains("judo") || s.contains("karate") || s.contains("muay") || s.contains("wrestl") || s.contains("fenc") -> Icons.Filled.SportsMartialArts
        s.contains("hiit") || s.contains("functional") || s.contains("gymnast") -> Icons.Filled.SportsGymnastics
        s.contains("tennis") || s.contains("padel") || s.contains("pickle") || s.contains("squash") || s.contains("racquet") || s.contains("badminton") -> Icons.Filled.SportsTennis
        s.contains("volleyball") -> Icons.Filled.SportsVolleyball
        s.contains("golf") -> Icons.Filled.SportsGolf
        s.contains("climb") -> Icons.Filled.Terrain
        s.contains("soccer") || s.contains("football") -> Icons.Filled.SportsSoccer
        s.contains("basketball") || s.contains("netball") -> Icons.Filled.SportsBasketball
        s.contains("gaming") || s.contains("esport") -> Icons.Filled.SportsEsports
        s.contains("motor") || s.contains("racing") -> Icons.Filled.SportsMotorsports
        s.contains("wheelchair") -> Icons.AutoMirrored.Filled.Accessible
        else -> Icons.Filled.FitnessCenter
    }
}
