package com.noop.ui.metric

import android.icu.text.DateFormat
import android.icu.text.DateIntervalFormat
import android.icu.util.DateInterval
import com.noop.R
import com.noop.ui.AppToday
import com.noop.ui.DayRelation
import com.noop.ui.uiString
import java.text.FieldPosition
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

// MARK: - Metric page date labels
//
// The dates a metric page, its cards and its trends print, in the reader's language: the period under the
// header ("24–30 Sep 2026"), a picked mark ("Thu, 24 Sep 2026", a week, a month), the axis ticks (weekday,
// day, month, one-letter month) and the Summary-style stamp ("Today", "Yesterday", "24 Sep"). ICU
// skeletons, so every locale orders and abbreviates its own way; ranges use the en dash as Health does.

internal object MetricDateLabels {

    private fun millis(d: LocalDate): Long = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun skeleton(pattern: String, d: LocalDate, locale: Locale): String =
        DateFormat.getInstanceForSkeleton(pattern, locale).format(java.util.Date(millis(d)))

    /** A date range joined by the en dash, as Health prints one; some locales join with an em dash. */
    fun rangeDash(text: String): String = text.replace('—', '–')

    private fun interval(from: LocalDate, to: LocalDate, pattern: String, locale: Locale): String {
        val f = DateIntervalFormat.getInstance(pattern, locale)
        val out = f.format(DateInterval(millis(from), millis(to)), StringBuffer(), FieldPosition(0))
        return rangeDash(out.toString())
    }

    /** The first day of a week where [locale] is read. */
    fun firstDayOfWeek(locale: Locale): DayOfWeek = WeekFields.of(locale).firstDayOfWeek

    /** "20–26 Sep 2026" / "Nov 2025 – Oct 2026": the span under the header figure. */
    fun span(window: MetricWindow, locale: Locale): String {
        val last = minOf(window.end.minusDays(1), window.anchor)
        return interval(window.start, last, if (window.range == MetricRange.YEAR) "yMMM" else "yMMMd", locale)
    }

    /** The date a picked mark stands for: its day, its week, or its month. */
    fun point(p: MetricPoint, range: MetricRange, locale: Locale): String = when (range.bucket) {
        MetricBucket.DAY -> skeleton("yMMMdEEE", p.start, locale)
        MetricBucket.WEEK -> interval(p.start, p.end.minusDays(1), "yMMMd", locale)
        MetricBucket.MONTH -> skeleton("yMMMM", p.start, locale)
    }

    /** A mark as TalkBack names it: the weekday, day and month spelled out; a week; or a month and year. */
    fun spoken(p: MetricPoint, range: MetricRange, locale: Locale): String = when (range.bucket) {
        MetricBucket.DAY -> skeleton("MMMMdEEEE", p.start, locale)
        MetricBucket.WEEK -> interval(p.start, p.end.minusDays(1), "yMMMMd", locale)
        MetricBucket.MONTH -> point(p, range, locale)
    }

    /** An x-axis tick: the weekday for W, the day for M, the month for 6M, the month's letter for Y. */
    fun xTick(d: LocalDate, range: MetricRange, locale: Locale): String = when (range) {
        MetricRange.WEEK -> skeleton("EEE", d, locale)
        MetricRange.MONTH -> skeleton("d", d, locale)
        MetricRange.SIX_MONTHS -> skeleton("LLL", d, locale)
        MetricRange.YEAR -> skeleton("LLLLL", d, locale)
    }

    /** "17 Sep": a short day. */
    fun shortDate(day: String, locale: Locale): String =
        MetricHealthSeries.date(day)?.let { skeleton("MMMd", it, locale) } ?: day

    /**
     * Health's stamp for a reading's day: "Today", "Yesterday", else "24 Sep". [today] is the app's one
     * today ([AppToday]), the same the Summary stamps its cards with, so a value that reads "Today" there
     * reads "Latest: Today" on its own page at any hour.
     */
    fun stamp(day: String, today: AppToday, locale: Locale): String = when (val r = today.relation(day)) {
        null -> day
        DayRelation.Today -> uiString(R.string.metric_today)
        DayRelation.Yesterday -> uiString(R.string.metric_yesterday)
        is DayRelation.OnDate -> skeleton("MMMd", r.date, locale)
    }

    /** "24 Sep – 30 Sep 2026" between two "yyyy-MM-dd" days (the Training Load header). */
    fun between(from: LocalDate, to: LocalDate, year: Boolean, locale: Locale): String =
        interval(from, to, if (year) "yMMM" else "yMMMd", locale)
}
