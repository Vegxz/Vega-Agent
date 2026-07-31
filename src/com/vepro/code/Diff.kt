package com.vepro.code

/**
 * The pure, view-free part of "what changed in this file".
 *
 * ### Why this exists
 *
 * The line diff used to live as a private function inside [MarkdownRenderer],
 * which meant only a view could ask what an edit did. That was fine while the
 * only consumer was the approval sheet, and wrong the moment the *engine* needed
 * the same answer: the activity strip wants to say `+12 −3` on the row for an
 * edit while it is still running, and a trail is built on a worker thread with no
 * Context anywhere near it.
 *
 * So the algorithm lives here, with no Android import in the file at all, and
 * [MarkdownRenderer] renders what this returns. One implementation, two callers.
 *
 * The second job is [hunk]. A trail is persisted with the conversation, so
 * keeping two whole copies of every file the agent touched is not an option —
 * a 400 KB source file would be 800 KB of JSON per edit. [hunk] narrows both
 * sides to the region that actually changed, plus a few lines of context, and
 * caps what is left. That is also the *right* thing to show: nobody reads the
 * 900 unchanged lines above the edit.
 */
object Diff {

    /** One line of a diff: -1 removed, 0 unchanged, +1 added. */
    class Row(val op: Int, val oldIndex: Int, val newIndex: Int)

    /**
     * A narrowed before/after pair, small enough to persist and to lay out.
     *
     * [added] and [removed] are counted on the FULL text, not on the narrowed
     * copy, so the summary stays honest even when the body is clipped.
     */
    class Hunk(
        val before: String,
        val after: String,
        val added: Int,
        val removed: Int,
        val clipped: Boolean
    ) {
        fun isEmpty(): Boolean = added == 0 && removed == 0
    }

    /**
     * LCS line diff. Falls back to "delete everything, add everything" once the
     * table would get too big, so a huge file can never hang the caller.
     */
    fun rows(oldLines: List<String>, newLines: List<String>): List<Row> {
        val oldSize = oldLines.size
        val newSize = newLines.size
        val out = ArrayList<Row>()

        // toLong() matters: 50000 * 50000 overflows Int to a NEGATIVE value, the
        // guard passes, and the next line allocates a 50001 x 50001 IntArray —
        // roughly 10 GB — for an instant OutOfMemoryError.
        if (oldSize.toLong() * newSize.toLong() > MAX_TABLE) {
            for (i in 0 until oldSize) {
                out.add(Row(-1, i, -1))
            }
            for (j in 0 until newSize) {
                out.add(Row(1, -1, j))
            }
            return out
        }

        val lcs = Array(oldSize + 1) { IntArray(newSize + 1) }
        for (i in oldSize - 1 downTo 0) {
            for (j in newSize - 1 downTo 0) {
                lcs[i][j] = if (oldLines[i] == newLines[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    Math.max(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        var i = 0
        var j = 0
        while (i < oldSize && j < newSize) {
            if (oldLines[i] == newLines[j]) {
                out.add(Row(0, i, j))
                i++
                j++
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(Row(-1, i, -1))
                i++
            } else {
                out.add(Row(1, -1, j))
                j++
            }
        }
        while (i < oldSize) {
            out.add(Row(-1, i, -1))
            i++
        }
        while (j < newSize) {
            out.add(Row(1, -1, j))
            j++
        }
        return out
    }

    /**
     * Narrows [before] and [after] to the changed region and counts the change.
     *
     * The narrowing is a prefix/suffix trim, not a hunk splitter: everything from
     * the first differing line to the last, with [CONTEXT_LINES] either side. For
     * the single-region edits `edit_file` makes that IS the hunk, and for a
     * scattered edit it degrades into "the span that contains all of them", which
     * is still enormously smaller than the file and never misleading.
     *
     * Counting is a real LCS over the narrowed span, so `+12 −3` means what it
     * says. The span is bounded by [MAX_COUNT_LINES] first, because the point of
     * this function is that it is cheap enough to call from a tool's own thread
     * while the user waits.
     */
    fun hunk(before: String, after: String): Hunk {
        if (before == after) {
            return Hunk("", "", 0, 0, false)
        }
        val oldLines = before.split("\n")
        val newLines = after.split("\n")

        // Common prefix, then common suffix, never crossing each other.
        var head = 0
        val headMax = Math.min(oldLines.size, newLines.size)
        while (head < headMax && oldLines[head] == newLines[head]) {
            head++
        }
        var tail = 0
        val tailMax = headMax - head
        while (
            tail < tailMax &&
            oldLines[oldLines.size - 1 - tail] == newLines[newLines.size - 1 - tail]
        ) {
            tail++
        }

        val from = Math.max(0, head - CONTEXT_LINES)
        val oldTo = Math.min(oldLines.size, oldLines.size - tail + CONTEXT_LINES)
        val newTo = Math.min(newLines.size, newLines.size - tail + CONTEXT_LINES)

        var oldSpan = oldLines.subList(Math.min(from, oldLines.size), Math.max(from, oldTo))
        var newSpan = newLines.subList(Math.min(from, newLines.size), Math.max(from, newTo))
        var clipped = from > 0 || oldTo < oldLines.size || newTo < newLines.size

        if (oldSpan.size > MAX_COUNT_LINES) {
            oldSpan = oldSpan.subList(0, MAX_COUNT_LINES)
            clipped = true
        }
        if (newSpan.size > MAX_COUNT_LINES) {
            newSpan = newSpan.subList(0, MAX_COUNT_LINES)
            clipped = true
        }

        var added = 0
        var removed = 0
        for (row in rows(oldSpan, newSpan)) {
            if (row.op > 0) {
                added++
            } else if (row.op < 0) {
                removed++
            }
        }

        var beforeText = oldSpan.joinToString("\n")
        var afterText = newSpan.joinToString("\n")
        if (beforeText.length > MAX_SIDE_CHARS) {
            beforeText = beforeText.substring(0, MAX_SIDE_CHARS)
            clipped = true
        }
        if (afterText.length > MAX_SIDE_CHARS) {
            afterText = afterText.substring(0, MAX_SIDE_CHARS)
            clipped = true
        }
        return Hunk(beforeText, afterText, added, removed, clipped)
    }

    /** The same summary for a file created from nothing. */
    fun created(content: String): Hunk {
        var lines = content.split("\n")
        var clipped = false
        if (lines.size > MAX_COUNT_LINES) {
            lines = lines.subList(0, MAX_COUNT_LINES)
            clipped = true
        }
        var text = lines.joinToString("\n")
        if (text.length > MAX_SIDE_CHARS) {
            text = text.substring(0, MAX_SIDE_CHARS)
            clipped = true
        }
        return Hunk("", text, lines.size, 0, clipped)
    }

    /** Lines of context kept either side of the change. */
    const val CONTEXT_LINES = 3

    /** Longest span either side is narrowed to before counting. */
    const val MAX_COUNT_LINES = 400

    /** Hard cap on each stored side, so a trail stays a reasonable size on disk. */
    const val MAX_SIDE_CHARS = 4000

    /** Above this many cells the LCS table is skipped entirely. */
    private const val MAX_TABLE = 400000L
}
