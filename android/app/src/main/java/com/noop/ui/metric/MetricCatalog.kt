package com.noop.ui.metric

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.ui.graphics.vector.ImageVector
import com.noop.R
import com.noop.data.MoodStore
import com.noop.data.WhoopRepository
import com.noop.ingest.NutritionCsvImporter
import com.noop.ui.uiString
import java.text.Normalizer
import java.util.Locale

// MARK: - Metric catalogue (Android twin of iOS MetricCatalog + AllMetricsCatalog)
//
// Every metric a page can open on: how it is fetched (key + source), how it is named and formatted, and
// whether higher is better. The key/category/unit/decimals of each entry match the iOS catalogue entry of
// the same key, so a metric reads the same on both platforms. Two deliberate Android differences:
//
//  • The phone's own health store is Health Connect here, so the entries iOS files under "apple-health"
//    carry [MetricCatalog.PHONE] ("health-connect"). Their loader reads a Health Connect sync and an Apple
//    Health export alike (MetricSeriesLoader), and each day keeps the source that actually supplied it.
//  • Keys no Android importer or engine writes (the WHOOP export's HR-zone minutes, strength time, time in
//    bed, hours vs needed, restorative sleep) are left out rather than listed forever under "Metrics Without
//    Data": importing a WHOOP export on Android would never fill them.

/** One metric: its identity (key + source), how it is labelled and formatted, and its better direction. */
data class MetricDescriptor(
    val key: String,
    @StringRes val titleRes: Int,
    /** The iOS score family: "Heart", "Charge", "Rest", "Effort", "Health", "Nutrition" or "Mind". */
    val category: String,
    /** The stored unit ("ms", "bpm", "%", "min", "kcal", "/3"…), empty for a unitless score. */
    val unit: String,
    val source: String,
    val decimals: Int,
    val higherIsBetter: Boolean?,
    @StringRes val descriptionRes: Int? = null,
) {
    /** Unique per catalogue entry: the same key can come from several sources. */
    val id: String get() = "$source:$key"

    /** The localized name. */
    val title: String get() = uiString(titleRes)
}

object MetricCatalog {
    const val WHOOP = WhoopRepository.WHOOP_SOURCE
    /** The phone's health store: Health Connect, or an imported Apple Health export (iOS "apple-health"). */
    const val PHONE = WhoopRepository.HEALTH_CONNECT_SOURCE
    const val XIAOMI = "xiaomi-band"
    const val NUTRITION = NutritionCsvImporter.SOURCE_ID
    const val MOOD = MoodStore.MOOD_DEVICE_ID

    private fun d(
        key: String, @StringRes title: Int, category: String, unit: String, source: String, decimals: Int,
        higherIsBetter: Boolean?, @StringRes description: Int? = null,
    ) = MetricDescriptor(key, title, category, unit, source, decimals, higherIsBetter, description)

    /** The catalogue in the iOS order (Heart, Charge, Rest, Effort, Health, Nutrition, Mind, Mi Band). */
    val all: List<MetricDescriptor> = listOf(
        // Heart
        d("avg_hr", R.string.metric_title_avg_hr, "Heart", "bpm", WHOOP, 0, null),
        d("max_hr", R.string.metric_title_max_hr, "Heart", "bpm", WHOOP, 0, null),
        d("energy_kcal", R.string.metric_title_calories, "Heart", "kcal", WHOOP, 0, null),
        d("vo2max", R.string.metric_title_vo2max, "Heart", "", PHONE, 1, true),
        d("fitness_age", R.string.metric_title_fitness_age, "Heart", "yrs", WHOOP, 0, false),
        d("vo2max_est", R.string.metric_title_vo2max_est, "Heart", "", WHOOP, 1, true),
        d("vitality", R.string.metric_title_vitality, "Heart", "", WHOOP, 0, true),
        d("body_age", R.string.metric_title_body_age, "Heart", "yrs", WHOOP, 0, false),
        // Charge
        d("recovery", R.string.metric_title_charge, "Charge", "%", WHOOP, 0, true, R.string.explore_description_charge),
        d("hrv", R.string.metric_title_hrv, "Charge", "ms", WHOOP, 0, true),
        d("rhr", R.string.metric_title_rhr, "Charge", "bpm", WHOOP, 0, false),
        d("resp_rate", R.string.metric_title_resp_rate, "Charge", "rpm", WHOOP, 1, null),
        d("spo2", R.string.metric_title_spo2, "Charge", "%", WHOOP, 0, true),
        d("skin_temp", R.string.metric_title_skin_temp, "Charge", "°C", WHOOP, 1, null),
        // Rest
        d("sleep_performance", R.string.metric_title_rest, "Rest", "%", WHOOP, 0, true, R.string.explore_description_rest),
        d("sleep_total_min", R.string.metric_title_asleep, "Rest", "min", WHOOP, 0, true),
        d("sleep_consistency", R.string.metric_title_sleep_consistency, "Rest", "%", WHOOP, 0, true),
        d("sleep_efficiency", R.string.metric_title_sleep_efficiency, "Rest", "%", WHOOP, 0, true),
        d("sleep_deep_min", R.string.metric_title_deep, "Rest", "min", WHOOP, 0, true),
        d("sleep_rem_min", R.string.metric_title_rem, "Rest", "min", WHOOP, 0, true),
        d("sleep_light_min", R.string.metric_title_light, "Rest", "min", WHOOP, 0, null),
        d("sleep_need_min", R.string.metric_title_sleep_need, "Rest", "min", WHOOP, 0, null),
        d("sleep_debt_min", R.string.metric_title_sleep_debt, "Rest", "min", WHOOP, 0, false),
        // Effort
        d("strain", R.string.metric_title_effort, "Effort", "/100", WHOOP, 1, null, R.string.explore_description_effort),
        d("steps", R.string.metric_title_steps, "Effort", "", PHONE, 0, true),
        d("steps", R.string.metric_title_steps, "Effort", "steps", WHOOP, 0, true),
        d("steps_est", R.string.metric_title_steps_est, "Effort", "steps", WHOOP, 0, true, R.string.metric_desc_steps_est),
        d("active_kcal", R.string.metric_title_active_energy, "Effort", "kcal", PHONE, 0, null),
        // Health / body
        d("weight", R.string.metric_title_weight, "Health", "kg", PHONE, 1, null),
        d("body_fat", R.string.metric_title_body_fat, "Health", "%", PHONE, 1, false),
        d("lean_mass", R.string.metric_title_lean_mass, "Health", "kg", PHONE, 1, true),
        d("bmi", R.string.metric_title_bmi, "Health", "", PHONE, 1, null),
        d("stress", R.string.metric_title_day_stress, "Health", "/3", WHOOP, 1, false),
        // Nutrition (a food-tracker CSV)
        d("calories_in", R.string.metric_title_calories_in, "Nutrition", "kcal", NUTRITION, 0, null),
        d("protein_g", R.string.metric_title_protein, "Nutrition", "g", NUTRITION, 0, null),
        d("carbs_g", R.string.metric_title_carbs, "Nutrition", "g", NUTRITION, 0, null),
        d("fat_g", R.string.metric_title_fat, "Nutrition", "g", NUTRITION, 0, null),
        // Mind (the daily mood check-in, 1–5)
        d("mood", R.string.metric_title_mood, "Mind", "/5", MOOD, 0, true),
        // Mi Band (Mi Fitness import): the same keys under their own source, so they stay comparable.
        d("avg_hr", R.string.metric_title_avg_hr, "Heart", "bpm", XIAOMI, 0, null),
        d("max_hr", R.string.metric_title_max_hr, "Heart", "bpm", XIAOMI, 0, null),
        d("energy_kcal", R.string.metric_title_calories, "Heart", "kcal", XIAOMI, 0, null),
        d("vitality", R.string.metric_title_vitality, "Heart", "", XIAOMI, 0, true),
        d("rhr", R.string.metric_title_rhr, "Charge", "bpm", XIAOMI, 0, false),
        d("spo2", R.string.metric_title_spo2, "Charge", "%", XIAOMI, 0, true),
        d("sleep_total_min", R.string.metric_title_asleep, "Rest", "min", XIAOMI, 0, true),
        d("sleep_deep_min", R.string.metric_title_deep, "Rest", "min", XIAOMI, 0, true),
        d("sleep_rem_min", R.string.metric_title_rem, "Rest", "min", XIAOMI, 0, true),
        d("sleep_light_min", R.string.metric_title_light, "Rest", "min", XIAOMI, 0, null),
        d("sleep_score", R.string.metric_title_sleep_score, "Rest", "", XIAOMI, 0, true),
        d("steps", R.string.metric_title_steps, "Effort", "", XIAOMI, 0, true),
        d("intensity_min", R.string.metric_title_intensity_min, "Effort", "min", XIAOMI, 0, true),
        d("stress", R.string.metric_title_stress, "Health", "/100", XIAOMI, 0, false),
    )

    /** The entry for [key] from [source], or null. */
    fun metric(key: String, source: String): MetricDescriptor? = all.firstOrNull { it.key == key && it.source == source }

    /** Every entry recording [key], in catalogue order. */
    fun byKey(key: String): List<MetricDescriptor> = all.filter { it.key == key }

    /**
     * The key a link falls back to when nothing records [key]: the Summary's Steps and Calories cards show
     * a measured or imported figure first and an on-device estimate otherwise (iOS `todayStepsMetric` /
     * `todayCaloriesMetric`), so their page does the same.
     */
    fun fallbackKey(key: String): String? = when (key) {
        "steps" -> "steps_est"
        "active_kcal" -> "energy_kcal"
        else -> null
    }

    /**
     * The catalogue key behind a key the legacy Today cards still pass (`vital_detail/<key>`): "resp",
     * "skin", "rest" and the Steps / Calories cards spelled their metrics differently. Any other key is
     * already a catalogue key.
     */
    fun keyForLegacy(key: String): String = when (key) {
        "resp" -> "resp_rate"
        "skin" -> "skin_temp"
        "rest" -> "sleep_performance"
        "steps_est" -> "steps"
        "sleep" -> "sleep_total_min"
        "efficiency" -> "sleep_efficiency"
        else -> key
    }
}

// MARK: - Health categories (All Metrics sections)

/** The Health app's categories the catalogue falls into, as All Metrics lists them. */
enum class HealthCategory(@StringRes val titleRes: Int, val icon: ImageVector) {
    ACTIVITY(R.string.metric_category_activity, Icons.Filled.LocalFireDepartment),
    BODY_MEASUREMENTS(R.string.metric_category_body, Icons.Filled.Accessibility),
    HEART(R.string.metric_category_heart, Icons.Filled.Favorite),
    MENTAL_WELLBEING(R.string.metric_category_mind, Icons.Filled.Psychology),
    NUTRITION(R.string.metric_category_nutrition, Icons.Filled.Restaurant),
    RESPIRATORY(R.string.metric_category_respiratory, Icons.Filled.Air),
    SLEEP(R.string.metric_category_sleep, Icons.Filled.Bedtime),
}

/** The pure half of All Metrics: categories, one source per key, order and search. Twin of iOS. */
object AllMetricsCatalog {

    /** The Health category a metric is listed under. */
    fun category(key: String, category: String): HealthCategory = when (key) {
        "resp_rate", "spo2" -> HealthCategory.RESPIRATORY
        // Health files wrist temperature under Body Measurements.
        "weight", "body_fat", "lean_mass", "bmi", "skin_temp" -> HealthCategory.BODY_MEASUREMENTS
        "stress", "mood" -> HealthCategory.MENTAL_WELLBEING
        "energy_kcal" -> HealthCategory.ACTIVITY
        else -> when (category) {
            "Heart", "Charge" -> HealthCategory.HEART
            "Rest" -> HealthCategory.SLEEP
            "Effort" -> HealthCategory.ACTIVITY
            "Nutrition" -> HealthCategory.NUTRITION
            "Mind" -> HealthCategory.MENTAL_WELLBEING
            else -> HealthCategory.BODY_MEASUREMENTS
        }
    }

    fun category(metric: MetricDescriptor): HealthCategory = category(metric.key, metric.category)

    /** Which source wins a key recorded by several when their newest readings fall on the same day. */
    val sourcePriority = listOf(MetricCatalog.WHOOP, MetricCatalog.PHONE, MetricCatalog.XIAOMI)

    /**
     * One entry per key, in the order the keys first appear: the source with the newest reading
     * ([latestDay], by metric id), and on a tie — or when none has one — the higher [sourcePriority].
     */
    fun oneSourcePerKey(metrics: List<MetricDescriptor>, latestDay: Map<String, String>): List<MetricDescriptor> {
        fun rank(m: MetricDescriptor): Int = sourcePriority.indexOf(m.source).let { if (it < 0) sourcePriority.size else it }
        fun beats(a: MetricDescriptor, b: MetricDescriptor): Boolean {
            val da = latestDay[a.id].orEmpty()
            val db = latestDay[b.id].orEmpty()
            return if (da != db) da > db else rank(a) < rank(b)
        }
        val order = ArrayList<String>()
        val best = HashMap<String, MetricDescriptor>()
        for (m in metrics) {
            val current = best[m.key]
            if (current == null) {
                order += m.key
                best[m.key] = m
            } else if (beats(m, current)) {
                best[m.key] = m
            }
        }
        return order.mapNotNull { best[it] }
    }

    /** One All Metrics section. */
    data class Section(val category: HealthCategory, val metrics: List<MetricDescriptor>)

    /**
     * Categories alphabetically by their name in the reader's language (as Health lists them), each with
     * its metrics alphabetically by name. Empty categories are left out.
     */
    fun sections(
        metrics: List<MetricDescriptor>,
        locale: Locale,
        categoryTitle: (HealthCategory) -> String,
        metricTitle: (MetricDescriptor) -> String,
    ): List<Section> {
        val collator = java.text.Collator.getInstance(locale).apply { strength = java.text.Collator.SECONDARY }
        return metrics.groupBy { category(it) }
            .map { (cat, list) -> Section(cat, list.sortedWith { a, b -> collator.compare(metricTitle(a), metricTitle(b)) }) }
            .sortedWith { a, b -> collator.compare(categoryTitle(a.category), categoryTitle(b.category)) }
    }

    /** [metrics] alphabetically by [title] in [locale]'s collation. */
    fun sortedByTitle(metrics: List<MetricDescriptor>, locale: Locale, title: (MetricDescriptor) -> String): List<MetricDescriptor> {
        val collator = java.text.Collator.getInstance(locale).apply { strength = java.text.Collator.SECONDARY }
        return metrics.sortedWith { a, b -> collator.compare(title(a), title(b)) }
    }

    /**
     * The shorter name a pinned Summary card gives the metric ("HRV"), used when the catalogue name does
     * not fit a card's title row. Never for the step estimate and the two calorie series, which share one
     * Summary card and must stay told apart here.
     */
    fun shortTitle(metric: MetricDescriptor): String {
        if (metric.key in setOf("steps_est", "energy_kcal", "active_kcal")) return metric.title
        return MetricHealthStyle.keyMetric(metric.key)?.let { uiString(it.titleRes) } ?: metric.title
    }

    private val combiningMarks = Regex("\\p{Mn}+")

    /** Case- and accent-insensitive form of [text] for matching. */
    fun fold(text: String, locale: Locale): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "").lowercase(locale)

    /** The search field matches a metric's name, its short name or its category's name. */
    fun matches(query: String, locale: Locale, vararg names: String): Boolean {
        val q = fold(query.trim(), locale)
        if (q.isEmpty()) return true
        return names.any { fold(it, locale).contains(q) }
    }
}

// MARK: - Links

/** The metric page route for [key], from [source] (null: the freshest source, as All Metrics picks it). */
fun metricRoute(key: String, source: String? = null): String {
    val k = android.net.Uri.encode(key)
    return if (source == null) "metric/$k" else "metric/$k?source=${android.net.Uri.encode(source)}"
}

/** The metric page route for a key the legacy Today cards pass ("resp", "skin", "rest", "steps_est"…). */
fun metricRouteForLegacyKey(key: String): String = metricRoute(MetricCatalog.keyForLegacy(key))

/** The All Metrics route. */
const val ALL_METRICS_ROUTE = "all_metrics"
