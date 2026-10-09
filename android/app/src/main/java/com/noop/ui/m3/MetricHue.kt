package com.noop.ui.m3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.noop.ui.KeyMetric

// MARK: - Metric hue (iOS MetricHealthStyle.tint / KeyMetric.healthTint)
//
// Which fixed data hue a metric is drawn in wherever it appears on its own: its page, its All Metrics card,
// its trend card, a Browse search result, a pinned Summary card. The rule is the iOS one: the three rings
// keep their ring hue on every screen, a few metrics carry their own Health category's hue, and the rest
// follow their score family. Keyed by the metric catalogue's key + category.

/** The hue family of a metric. Resolved to a colour at draw time so it follows light / dark. */
enum class MetricHue { Charge, Effort, Rest, Heart, Sleep, Oxygen, Respiratory, Temperature, Body, Mind, Nutrition, Activity }

/**
 * The hue of catalogue metric [key] in score family [category] ("Heart", "Charge", "Rest", "Effort",
 * "Health", "Nutrition", "Mind"). Unknown keys fall to their family, an unknown family to Body, as on iOS.
 */
fun metricHue(key: String, category: String): MetricHue = when (key) {
    "recovery" -> MetricHue.Charge
    "strain" -> MetricHue.Effort
    "sleep_performance" -> MetricHue.Rest
    "resp_rate" -> MetricHue.Respiratory
    "spo2" -> MetricHue.Oxygen
    "skin_temp" -> MetricHue.Temperature
    "weight", "body_fat", "lean_mass", "bmi" -> MetricHue.Body
    "stress", "mood" -> MetricHue.Mind
    else -> when (category) {
        "Heart", "Charge" -> MetricHue.Heart
        "Rest" -> MetricHue.Sleep
        "Effort" -> MetricHue.Activity
        "Nutrition" -> MetricHue.Nutrition
        "Mind" -> MetricHue.Mind
        else -> MetricHue.Body
    }
}

/** The hue a pinned Summary card shares with the metric page it opens. */
fun keyMetricHue(metric: KeyMetric): MetricHue = when (metric) {
    KeyMetric.CHARGE -> MetricHue.Charge
    KeyMetric.EFFORT -> MetricHue.Effort
    KeyMetric.REST -> MetricHue.Rest
    KeyMetric.HRV, KeyMetric.RESTING_HR -> MetricHue.Heart
    KeyMetric.BLOOD_OXYGEN -> MetricHue.Oxygen
    KeyMetric.RESPIRATORY -> MetricHue.Respiratory
    KeyMetric.STEPS, KeyMetric.CALORIES -> MetricHue.Activity
    KeyMetric.WEIGHT -> MetricHue.Body
    KeyMetric.SKIN_TEMP -> MetricHue.Temperature
}

/** The fixed data colour of this hue. */
val MetricHue.color: Color
    @Composable @ReadOnlyComposable get() {
        val c = Health.colors
        return when (this) {
            MetricHue.Charge -> c.charge
            MetricHue.Effort -> c.effort
            MetricHue.Rest -> c.rest
            MetricHue.Heart -> c.heart
            MetricHue.Sleep -> c.sleep
            MetricHue.Oxygen -> c.oxygen
            MetricHue.Respiratory -> c.respiratory
            MetricHue.Temperature -> c.temperature
            MetricHue.Body -> c.body
            MetricHue.Mind -> c.mind
            MetricHue.Nutrition -> c.nutrition
            MetricHue.Activity -> c.activity
        }
    }
