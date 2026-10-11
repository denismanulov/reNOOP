package com.noop.analytics

import kotlin.math.exp

// SleepStageTrees.kt — the learned stage model's trees, read from plain text and evaluated here.
//
// The Apple app runs the model pair through Core ML. Android has no Core ML, so
// `Tools/SleepML/export_trees.py` writes the same trees out as text
// (`app/src/main/assets/sleepstage/*.trees`) and this file evaluates those. Swift twin:
// `SleepStageTrees.swift`, the reader the committed oracle
// (`app/src/test/resources/sleep_stage_oracle.json`) is produced by.
//
// The arithmetic is spelled out because the two platforms must do the same sums in the same order:
// each class's sum starts at its base value and takes the leaves in the file's tree order; a branch
// goes to its first child when the row's value is LESS THAN the threshold, compared as doubles; the
// answer is exp(sum - largest sum) over the total of those four, added in class order.

/** One model's trees: a function from an input row to class probabilities. */
class SleepStageTrees private constructor(
    /** Input names, in the order of a row. */
    val columns: List<String>,
    /** The order of a row of probabilities as the file gives it. */
    val classes: List<String>,
    /** The model's own settings; the second model of a pair carries the decoder's. */
    val meta: Map<String, String>,
    /** Hex SHA-256 of the `.mlmodel` the file was written from. */
    val source: String,
    private val base: DoubleArray,
    // Every tree's nodes end to end. A node is a branch (`column >= 0`: go to `yes` when the row's
    // value is below `value`, else to `no`) or a leaf (`column < 0`: add `value` to class `yes`).
    private val column: IntArray,
    private val value: DoubleArray,
    private val yes: IntArray,
    private val no: IntArray,
    /** Where each tree's root sits in the arrays above. */
    private val roots: IntArray,
) {
    val treeCount: Int get() = roots.size

    /** One row's probabilities, in the file's class order. [row] holds one number per column. */
    fun probabilities(row: DoubleArray): DoubleArray {
        require(row.size == columns.size) { "a row carries one number per column" }
        val sums = base.copyOf()
        for (root in roots) {
            var node = root
            while (column[node] >= 0) {
                node = if (row[column[node]] < value[node]) yes[node] else no[node]
            }
            sums[yes[node]] += value[node]
        }
        var top = sums[0]
        for (i in 1 until sums.size) if (sums[i] > top) top = sums[i]
        val weights = DoubleArray(sums.size) { exp(sums[it] - top) }
        var total = 0.0
        for (w in weights) total += w
        return DoubleArray(weights.size) { weights[it] / total }
    }

    /** A night's rows as probabilities in [order] (the decoder's stage order, say). null when a row has
     *  the wrong width or [order] names a class this model does not have. */
    fun probabilities(rows: List<DoubleArray>, order: List<String>): List<DoubleArray>? {
        val index = IntArray(order.size) { classes.indexOf(order[it]) }
        if (index.any { it < 0 } || rows.any { it.size != columns.size }) return null
        return rows.map { row ->
            val p = probabilities(row)
            DoubleArray(index.size) { p[index[it]] }
        }
    }

    companion object {
        /** The first line of a file this reads. */
        const val FORMAT = "renoop-sleep-stage-trees 1"

        /** A decimal number as the exporter writes one. Java's own parser also takes type suffixes
         *  and surrounding whitespace, which the Swift reader refuses; this keeps to plain decimals. */
        private val number = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")

        private fun finite(text: String): Double? =
            if (number.matches(text)) text.toDouble().takeIf { it.isFinite() } else null

        private fun words(line: String): List<String> = line.split(' ').filter { it.isNotEmpty() }

        /** Reads a file's text. null for anything that is not a well-formed file of this format: a
         *  model half-read would stage nights wrongly without saying so. */
        fun parse(text: String): SleepStageTrees? {
            val all = text.split('\n')
            val lines = if (all.last() == "") all.subList(0, all.size - 1) else all
            var at = 0
            fun next(): String? = if (at < lines.size) lines[at++] else null
            if (next() != FORMAT) return null

            var classes: List<String> = emptyList()
            var base: List<Double> = emptyList()
            val meta = LinkedHashMap<String, String>()
            var source = ""
            var columns: List<String>? = null
            while (columns == null) {
                val line = next() ?: break
                if (line.startsWith("#")) continue
                val parts = line.split(" ", limit = 3)
                when (parts[0]) {
                    "source" -> {
                        val w = words(line)
                        if (w.size != 4 || w[2] != "sha256") return null
                        source = w[3]
                    }
                    "classes" -> classes = words(line).drop(1)
                    "transform" -> if (line != "transform softmax") return null
                    "base" -> {
                        val numbers = words(line).drop(1).map { finite(it) }
                        if (numbers.any { it == null }) return null
                        base = numbers.filterNotNull()
                    }
                    "meta" -> {
                        if (parts.size != 3) return null
                        meta[parts[1]] = parts[2]
                    }
                    "columns" -> {
                        val n = (if (parts.size == 2) parts[1].toIntOrNull() else null) ?: return null
                        if (n <= 0 || lines.size - at < n) return null
                        columns = lines.subList(at, at + n).toList()
                        at += n
                    }
                    else -> return null
                }
            }
            if (columns == null || classes.isEmpty() || base.size != classes.size ||
                columns.toSet().size != columns.size || classes.toSet().size != classes.size) return null
            val header = next()?.let(::words) ?: return null
            if (header.size != 2 || header[0] != "trees") return null
            val treeCount = header[1].toIntOrNull() ?: return null
            if (treeCount <= 0) return null

            var column = IntArray(1 shl 14); var value = DoubleArray(1 shl 14)
            var yes = IntArray(1 shl 14); var no = IntArray(1 shl 14)
            var count = 0
            val roots = IntArray(treeCount)
            for (tree in 0 until treeCount) {
                val head = next()?.let(::words) ?: return null
                if (head.size != 2 || head[0] != "tree") return null
                val nodes = head[1].toIntOrNull() ?: return null
                if (nodes <= 0 || lines.size - at < nodes) return null
                val root = count
                roots[tree] = root
                if (count + nodes > column.size) {
                    val size = maxOf(column.size * 2, count + nodes)
                    column = column.copyOf(size); value = value.copyOf(size)
                    yes = yes.copyOf(size); no = no.copyOf(size)
                }
                for (index in 0 until nodes) {
                    val f = words(lines[at++])
                    if (f.size == 3 && f[0] == "l") {
                        val c = f[1].toIntOrNull() ?: return null
                        val v = finite(f[2]) ?: return null
                        if (c !in classes.indices) return null
                        column[count] = -1; value[count] = v; yes[count] = c; no[count] = 0
                    } else if (f.size == 5 && f[0] == "b") {
                        val c = f[1].toIntOrNull() ?: return null
                        val v = finite(f[2]) ?: return null
                        val y = f[3].toIntOrNull() ?: return null
                        val n = f[4].toIntOrNull() ?: return null
                        // A branch points only forward, so no walk can loop.
                        if (c !in columns.indices || y <= index || n <= index || y >= nodes || n >= nodes) return null
                        column[count] = c; value[count] = v; yes[count] = root + y; no[count] = root + n
                    } else {
                        return null
                    }
                    count++
                }
            }
            if (at != lines.size) return null
            return SleepStageTrees(
                columns = columns, classes = classes, meta = meta, source = source,
                base = base.toDoubleArray(),
                column = column.copyOf(count), value = value.copyOf(count),
                yes = yes.copyOf(count), no = no.copyOf(count), roots = roots,
            )
        }
    }
}
