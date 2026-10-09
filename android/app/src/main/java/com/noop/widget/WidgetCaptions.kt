package com.noop.widget

import com.noop.ui.EffortScale
import com.noop.ui.UnitFormatter

/**
 * What the widgets print and speak, kept pure so it is pinned by a JVM test.
 *
 * Every figure is honest-blank: a score NOOP has not computed, a heart rate too old to stand for the
 * wearer and a battery the strap has not reported are a dash, never a zero. The words around a figure
 * (the ring captions, the units) are resources resolved by the caller, so nothing here is language.
 */
internal object WidgetCaptions {

    /** The dash every widget shows for a value it does not have. */
    const val DASH = "—"

    /** A ring's arc, 0..100. An unscored ring draws no arc at all, only its track. */
    fun ringProgress(pct: Int?): Int = pct?.coerceIn(0, 100) ?: 0

    /** The figure inside a ring: the whole number, without a sign (the ring is the percent). */
    fun score(pct: Int?): String = pct?.toString() ?: DASH

    /**
     * The strain figure, on the scale the wearer chose in Settings.
     *
     * On the app's own 0 to 100 axis it is the whole number, as the other two rings print theirs. On
     * WHOOP's 0 to 21 axis it is one decimal through [UnitFormatter.effortDisplay], the helper every
     * strain read-out in the app goes through, so the widget cannot print a different number from the
     * Summary. The ring's arc does not depend on the scale: 19 of 100 and 4.0 of 21 are the same arc.
     */
    fun effort(effortPct: Int?, effort: Double?, scale: EffortScale): String {
        if (scale != EffortScale.WHOOP) return score(effortPct)
        val stored = effort ?: effortPct?.toDouble() ?: return DASH
        return UnitFormatter.effortDisplay(stored, scale)
    }

    /** The heart-rate figure. */
    fun heartRate(bpm: Int?): String = bpm?.toString() ?: DASH

    /**
     * One spoken element for a labelled figure: "Charge, 68%", or "Charge, No data" when there is none.
     * [value] arrives already formatted with its unit, because a bare number tells TalkBack nothing.
     */
    fun spoken(label: String, value: String?, noData: String): String = "$label, ${value ?: noData}"

    /**
     * The part of a Coach brief a widget has room for: its first [maxLines] lines that say something.
     * Blank lines are dropped rather than counted, so a brief with paragraph gaps still fills the card.
     */
    fun briefExcerpt(text: String, maxLines: Int = 4): String =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(maxLines).joinToString("\n")
}
