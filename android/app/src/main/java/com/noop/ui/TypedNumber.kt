package com.noop.ui

/**
 * A number typed by hand, read the same way in every field that takes one (CR-11): a manual workout's
 * distance and calories, a Lab Results value, a numeric Journal habit.
 *
 * A comma decimal ("5,2") reads as well as a point ("5.2") whatever the phone's language, since the decimal
 * pads of the two kinds of locale offer one or the other. Spaces are dropped, including the no-break spaces
 * French and Russian group thousands with. When BOTH separators appear the last one is the decimal mark and
 * the other groups thousands ("1,234.5", "1.234,5"). Anything else (letters, two decimal marks, an exponent)
 * is not a number: the field keeps what was typed and stores nothing, rather than a value the reader did
 * not mean.
 */
internal object TypedNumber {
    fun parse(text: String): Double? {
        val compact = text.filterNot { it.isWhitespace() || it == ' ' || it == ' ' }
        if (compact.isEmpty()) return null
        val lastComma = compact.lastIndexOf(',')
        val lastDot = compact.lastIndexOf('.')
        val normalized = when {
            lastComma >= 0 && lastDot >= 0 -> {
                val decimal = if (lastComma > lastDot) ',' else '.'
                val grouping = if (decimal == ',') '.' else ','
                compact.replace(grouping.toString(), "").replace(decimal, '.')
            }
            lastComma >= 0 -> compact.replace(',', '.')
            else -> compact
        }
        if (normalized.count { it == '.' } > 1) return null
        if (!normalized.all { it.isDigit() || it == '.' || it == '-' || it == '+' }) return null
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
