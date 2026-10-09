package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.TextStyle

/**
 * A small, dependency-free Markdown renderer for the AI Coach's replies (Android twin of the macOS/iOS
 * MarkdownUI Coach view, #149). The Coach is told to emit "simple Markdown, chat-sized": short
 * paragraphs, **bold** for key numbers, *italics*, `code`, `###` headings, bullet/numbered lists, and
 * GFM pipe tables — exactly the block + inline set handled here. Anything else it doesn't recognise falls
 * through as plain text rather than showing raw symbols, which is strictly better than the old verbatim
 * Text() that rendered `**bold**` literally. Styled from the Material type scale and colour scheme so it
 * matches the reply bubble it sits in.
 *
 * Chat-sized type: the body is [body] (bodyMedium, the size of the question bubbles), and a heading is
 * one step up from it at most. A reply that opens with `#` used to be set in titleLarge, which made the
 * coach read as shouting next to the wearer's own messages.
 *
 * [tail] reserves room at the end of the last line for whatever the bubble lays over its bottom corner
 * (the time). It is honoured only when [coachMarkdownTakesTail] says the reply ends in running text.
 *
 * The inline parser (parseInline) is pure and unit-tested in CoachMarkdownTest; block layout is above.
 */
@Composable
fun CoachMarkdown(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    body: TextStyle = MaterialTheme.typography.bodyMedium,
    tail: TextUnit = TextUnit.Unspecified,
) {
    Column {
        val lines = text.replace("\r\n", "\n").split("\n")
        val tailLine = if (tail != TextUnit.Unspecified && coachMarkdownTakesTail(text)) lines.indexOfLast { it.isNotBlank() } else -1
        val tailContent = remember(tail) {
            if (tail == TextUnit.Unspecified) emptyMap()
            else mapOf(TAIL_ID to InlineTextContent(Placeholder(tail, 1.sp, PlaceholderVerticalAlign.TextBottom)) {})
        }
        fun withTail(index: Int, content: AnnotatedString): AnnotatedString =
            if (index != tailLine) content else buildAnnotatedString { append(content); appendInlineContent(TAIL_ID, " ") }
        var i = 0
        var firstBlock = true
        while (i < lines.size) {
            // GFM pipe table — spans several lines (header, --- delimiter, body rows), so handle it
            // before the single-line block cases below and advance i past the whole table.
            val parsedTable = parseTable(lines, i)
            if (parsedTable != null) {
                if (!firstBlock) Spacer(Modifier.height(8.dp))
                MarkdownTable(parsedTable.first, color)
                firstBlock = false
                i = parsedTable.second
                continue
            }
            val raw = lines[i]
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                line.startsWith("### ") -> {
                    if (!firstBlock) Spacer(Modifier.height(6.dp))
                    HeadingText(line.removePrefix("### "), body, color)
                }
                line.startsWith("## ") -> {
                    if (!firstBlock) Spacer(Modifier.height(6.dp))
                    HeadingText(line.removePrefix("## "), MaterialTheme.typography.titleSmall, color)
                }
                line.startsWith("# ") -> {
                    if (!firstBlock) Spacer(Modifier.height(8.dp))
                    HeadingText(line.removePrefix("# "), MaterialTheme.typography.titleMedium, color)
                }
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") ->
                    BulletItem("•", withTail(i, parseInline(line.drop(2), color)), color, body, tailContent)
                NUMBERED.matchEntire(line) != null -> {
                    val m = NUMBERED.matchEntire(line)!!
                    BulletItem(m.groupValues[1] + ".", withTail(i, parseInline(m.groupValues[2], color)), color, body, tailContent)
                }
                else -> androidx.compose.material3.Text(
                    withTail(i, parseInline(line, color)), style = body, color = color, inlineContent = tailContent,
                )
            }
            firstBlock = firstBlock && line.isBlank()
            i++
        }
    }
}

private val NUMBERED = Regex("""^(\d+)\.\s+(.*)$""")

private const val TAIL_ID = "coachTail"

/**
 * A question bubble's text: verbatim, so a typed `*` or `#` never turns into formatting, with the same
 * room at the end of its last line as [CoachMarkdown]'s `tail`.
 */
@Composable
fun CoachPlainText(text: String, color: Color, style: TextStyle, tail: TextUnit = TextUnit.Unspecified) {
    if (tail == TextUnit.Unspecified) {
        androidx.compose.material3.Text(text, style = style, color = color)
        return
    }
    val content = remember(tail) {
        mapOf(TAIL_ID to InlineTextContent(Placeholder(tail, 1.sp, PlaceholderVerticalAlign.TextBottom)) {})
    }
    androidx.compose.material3.Text(
        buildAnnotatedString { append(text); appendInlineContent(TAIL_ID, " ") },
        style = style,
        color = color,
        inlineContent = content,
    )
}

/**
 * Whether a reply ends in running text (a paragraph or a list item), so the space [CoachMarkdown]'s
 * `tail` reserves lands on its last line. A reply that ends in a heading or a table row does not: the
 * bubble then sets the time on a line of its own. A table row always holds a `|`, so a last line with
 * none cannot belong to a table.
 */
fun coachMarkdownTakesTail(text: String): Boolean {
    val last = text.replace("\r\n", "\n").split("\n").lastOrNull { it.isNotBlank() }?.trimEnd() ?: return false
    return !last.contains("|") && !last.startsWith("# ") && !last.startsWith("## ") && !last.startsWith("### ")
}

/** A single * or _ opens emphasis only at a word boundary with non-space content after, so "3*4" and a
 *  stray "*" stay literal (a simplified CommonMark left-flanking rule). */
private fun emphasisOpensAt(s: String, i: Int): Boolean =
    (i == 0 || s[i - 1].isWhitespace()) && i + 1 < s.length && !s[i + 1].isWhitespace()

@Composable
private fun HeadingText(text: String, style: TextStyle, color: Color) {
    androidx.compose.material3.Text(
        parseInline(text, color),
        style = style.copy(fontWeight = FontWeight.SemiBold),
        color = color,
    )
}

@Composable
private fun BulletItem(
    marker: String,
    content: AnnotatedString,
    color: Color,
    body: TextStyle,
    inlineContent: Map<String, InlineTextContent>,
) {
    Row(modifier = Modifier.padding(start = 2.dp)) {
        androidx.compose.material3.Text(
            marker, style = body, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(if (marker.length > 2) 22.dp else 14.dp),
        )
        Spacer(Modifier.width(2.dp))
        androidx.compose.material3.Text(content, style = body, color = color, inlineContent = inlineContent)
    }
}

/**
 * Inline Markdown → [AnnotatedString]: **bold**, *italic* / _italic_, `code`. A single left-to-right
 * scan; an unterminated marker is emitted as literal text (so stray `*` never eats the rest of a line).
 */
@Suppress("UNUSED_PARAMETER") // `color` kept for call-site clarity + API symmetry with the block parser
fun parseInline(s: String, color: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = s.length
    fun emitUntil(marker: String, style: SpanStyle): Boolean {
        val close = s.indexOf(marker, startIndex = i + marker.length)
        if (close < 0) return false
        val inner = s.substring(i + marker.length, close)
        if (inner.isEmpty()) return false
        withStyle(style) { append(inner) }
        i = close + marker.length
        return true
    }
    while (i < n) {
        val c = s[i]
        val handled = when {
            c == '*' && i + 1 < n && s[i + 1] == '*' -> emitUntil("**", SpanStyle(fontWeight = FontWeight.Bold))
            c == '*' && emphasisOpensAt(s, i) -> emitUntil("*", SpanStyle(fontStyle = FontStyle.Italic))
            c == '_' && (i + 1 >= n || s[i + 1] != '_') && emphasisOpensAt(s, i) ->
                emitUntil("_", SpanStyle(fontStyle = FontStyle.Italic))
            c == '`' -> emitUntil("`", SpanStyle(fontFamily = FontFamily.Monospace))
            else -> false
        }
        if (!handled) { append(c); i++ }
    }
}

// MARK: - GFM pipe tables (Android twin of the iOS/macOS MarkdownUI table; "Markdown tables on Android"
// from the #132 roadmap). The Coach sometimes answers with a small comparison table ("metric | you |
// typical"); this is the dependency-free Android equivalent. parseTable is pure and unit-tested in
// CoachMarkdownTest; MarkdownTable does the Compose layout (a bordered grid, header in SemiBold over a
// subtle inset, hairline row separators), reusing parseInline so **bold** / `code` inside a cell styles.

/** A parsed GFM pipe table: a header row and zero-or-more body rows. Cell text is RAW Markdown — the
 *  renderer applies [parseInline] per cell so inline styling inside a cell still works. */
data class MdTable(val header: List<String>, val rows: List<List<String>>)

/** A GFM delimiter cell: dashes with an optional leading/trailing colon for alignment (`---`, `:--`, `:-:`). */
private val TABLE_DELIM_CELL = Regex("""^:?-+:?$""")

/** Split a table row into trimmed cells, dropping the empty cells the optional outer pipes create. */
private fun splitTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.dropLast(1)
    return s.split("|").map { it.trim() }
}

private fun isTableDelimiterRow(line: String): Boolean {
    val cells = splitTableRow(line)
    return cells.isNotEmpty() && cells.all { TABLE_DELIM_CELL.matches(it) }
}

/**
 * Parse a GFM pipe table starting at lines[start] — a header row, a `---` delimiter row, then body rows
 * until a blank/non-table line — returning the table plus the index of the first line after it, or null
 * if lines[start] doesn't begin a table. The delimiter row is required, so a prose line that merely
 * contains a `|` (or a setext `---` heading underline) is never mistaken for a table.
 */
fun parseTable(lines: List<String>, start: Int): Pair<MdTable, Int>? {
    if (start + 1 >= lines.size) return null
    val headerLine = lines[start]
    val delimLine = lines[start + 1]
    if (!headerLine.contains("|") || !delimLine.contains("|")) return null
    if (!isTableDelimiterRow(delimLine)) return null
    val header = splitTableRow(headerLine)
    val rows = mutableListOf<List<String>>()
    var i = start + 2
    while (i < lines.size && lines[i].isNotBlank() && lines[i].contains("|")) {
        rows.add(splitTableRow(lines[i]))
        i++
    }
    return MdTable(header, rows) to i
}

@Composable
private fun MarkdownTable(table: MdTable, color: Color) {
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    Column(
        modifier = Modifier
            .padding(vertical = 2.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
        MarkdownTableRow(table.header, columns, color, header = true)
        for (row in table.rows) {
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            MarkdownTableRow(row, columns, color, header = false)
        }
    }
}

@Composable
private fun MarkdownTableRow(cells: List<String>, columns: Int, color: Color, header: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (header) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier),
    ) {
        for (c in 0 until columns) {
            androidx.compose.material3.Text(
                parseInline(cells.getOrElse(c) { "" }, color),
                style = if (header) MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
