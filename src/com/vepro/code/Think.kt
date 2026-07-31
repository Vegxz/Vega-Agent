package com.vepro.code

/**
 * Separates model "reasoning" blocks from the answer the user should see.
 *
 * Handles both closed blocks (`<think>…</think>`) and the half-open block that
 * is still arriving while a response streams (`<think>…` with no close tag yet).
 */
object Think {

    /** Closed reasoning block. Case-insensitive, dot-matches-newline. */
    private val BLOCK = Regex(
        "<\\s*(think|thinking|reasoning|reflection)\\s*>(.*?)<\\s*/\\s*\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** Reasoning block that is still open at the end of the buffer. */
    private val OPEN_TAIL = Regex(
        "<\\s*(think|thinking|reasoning|reflection)\\s*>(.*)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** Result of [split]: the hidden reasoning and the user-visible answer. */
    data class Parts(val thinking: String, val visible: String)

    fun split(raw: String?): Parts {
        if (raw == null) {
            return Parts("", "")
        }
        val thoughts = StringBuilder()
        // Closed blocks, but only the ones the model actually MEANT as
        // reasoning. A `<think>…</think>` inside a fenced example or between
        // inline backticks is content the user asked to see — quietly moving it
        // to the reasoning panel left an empty code card where the example had
        // been, and mangled any JSON or XML that happened to carry one of these
        // tag names.
        val out = StringBuilder()
        var cursor = 0
        for (match in BLOCK.findAll(raw)) {
            if (insideCode(raw, match.range.first)) {
                continue
            }
            out.append(raw, cursor, match.range.first)
            if (thoughts.isNotEmpty()) {
                thoughts.append("\n\n")
            }
            thoughts.append(match.groupValues[2].trimJava())
            cursor = match.range.last + 1
        }
        out.append(raw, cursor, raw.length)
        var visible = out.toString()
        // An open `<think>` hides EVERYTHING after it, so it must not fire on a
        // tag the model was only quoting. Inside a code fence — an XML config, a
        // JSX snippet, a prompt-engineering example, which this app's own system
        // prompt makes a likely subject — the answer would simply stop dead at
        // that line and the rest would vanish into the reasoning panel.
        //
        // Scan for the first tag that is NOT fenced rather than testing only the
        // first match: a quoted tag early in the answer must not stop a genuine
        // open reasoning tail later in it from being hidden.
        var tail: MatchResult? = null
        var probe = OPEN_TAIL.find(visible)
        while (probe != null) {
            if (!insideCode(visible, probe.range.first)) {
                tail = probe
                break
            }
            probe = if (probe.range.first + 1 <= visible.length) {
                OPEN_TAIL.find(visible, probe.range.first + 1)
            } else {
                null
            }
        }
        if (tail != null) {
            val trailing = tail.groupValues[2].trimJava()
            if (trailing.isNotEmpty()) {
                if (thoughts.isNotEmpty()) {
                    thoughts.append("\n\n")
                }
                thoughts.append(trailing)
            }
            visible = visible.substring(0, tail.range.first)
        }
        return Parts(thoughts.toString().trimJava(), visible.trimJava())
    }

    /**
     * True when [at] falls inside a fenced block or an inline code span — i.e.
     * inside text the user is meant to READ rather than markup to act on.
     */
    internal fun insideCode(text: String, at: Int): Boolean =
        insideFence(text, at) || insideInlineCode(text, at)

    /**
     * True when [at] sits between an odd number of single backticks on its own
     * line. Inline spans do not cross lines in CommonMark, so the scan starts at
     * the line break — which also keeps it O(line), not O(document).
     */
    private fun insideInlineCode(text: String, at: Int): Boolean {
        var lineStart = text.lastIndexOf('\n', Math.max(0, at - 1))
        lineStart = if (lineStart < 0) 0 else lineStart + 1
        var ticks = 0
        var i = lineStart
        while (i < at) {
            if (text[i] == '`') {
                // A ``` run is a fence marker, not an inline span.
                var run = 0
                while (i < at && text[i] == '`') {
                    run++
                    i++
                }
                if (run < 3) {
                    ticks++
                }
                continue
            }
            i++
        }
        return ticks % 2 == 1
    }

    /** True when [at] falls inside an open ``` fence. */
    internal fun insideFence(text: String, at: Int): Boolean {
        var fences = 0
        var i = 0
        while (i < at) {
            val found = text.indexOf("```", i)
            if (found < 0 || found >= at) {
                break
            }
            if (opensLineAt(text, found)) {
                fences++
            }
            i = found + 3
        }
        return fences % 2 == 1
    }

    /** True when only whitespace separates [at] from the start of its line. */
    internal fun opensLineAt(text: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '\n') {
                return true
            }
            if (c != ' ' && c != '\t' && c != '\r') {
                return false
            }
            i--
        }
        return true
    }

    fun visible(raw: String?): String = split(raw).visible

    fun merge(first: String?, second: String?): String {
        val a = first?.trimJava().orEmpty()
        val b = second?.trimJava().orEmpty()
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        if (a.contains(b) || b.contains(a)) {
            return if (a.length >= b.length) a else b
        }
        return "$a\n\n$b"
    }

    /** Strips closed reasoning blocks before a turn is replayed to the model. */
    fun stripForModel(raw: String?): String =
        if (raw == null) "" else BLOCK.replace(raw, "").trimJava()
}
