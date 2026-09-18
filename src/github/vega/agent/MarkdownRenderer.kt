package github.vega.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Small hand-rolled markdown renderer.
 *
 * Blocks are split on ``` fences (odd segments are code), inline spans are
 * converted to a narrow HTML subset and handed to `Html.fromHtml`, and a
 * [Streaming] session renders live model output without rebuilding the whole
 * message on every token.
 */
object MarkdownRenderer {

    private const val MENU_COPY = 0x564350
    private const val MENU_SELECT_ALL = 0x564341

    /**
     * View tag marking a rendered code card. The message-level tap-to-copy panel
     * skips these subtrees because a code block already has its own copy button.
     */
    const val TAG_CODE_CARD = "vega_code_card"

    /**
     * Tag on the body [TextView] of a code card (or each added/removed line
     * view of a diff card). The whole-message Select collector gathers these;
     * the header row (language label, copy button) stays out of the selection.
     */
    const val TAG_CODE_BODY = "vega_code_body"

    /** Language tag on a fence, e.g. ```kotlin */
    private val LANG_TAG = Regex("[a-zA-Z0-9_+-]{1,20}")
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val QUOTE = Regex("^\\s*&gt;\\s?(.*)$")
    private val BULLET = Regex("^\\s*[-*]\\s+(.*)$")
    /** One GFM delimiter cell: optional colons around one or more dashes. */
    private val DELIM_CELL = Regex("^:?-{1,}:?$")
    /** `- foo`, `* foo`, `+ foo`, `1. foo`, `2) foo` with leading indent. */
    private val LIST_ITEM = Regex("^(\\s*)(\\d{1,3}[.)]|[-*+])\\s+(.*)$")
    /** `- [ ] foo` / `- [x] foo` task items. Checked before [LIST_ITEM]. */
    private val TASK_ITEM = Regex("^(\\s*)[-*]\\s+\\[([ xX])\\]\\s+(.*)$")
    /** `---`, `***`, `___`, with optional spaces between the marks. */
    private val HR = Regex("^(?:\\*[ \\t]*){3,}$|^(?:-[ \\t]*){3,}$|^(?:_[ \\t]*){3,}$")
    /** Autolink `<https://…>`, matched AFTER the escape pass (see inlineToHtml). */
    private val AUTOLINK = Regex("&lt;(https?://[^<>\\s]+?)&gt;")

    /**
     * Hard ceiling on rendered input. Chat messages stream and re-render, and a
     * pasted 5MB log must not turn one message into a multi-second layout pass.
     * Truncation prefers a paragraph boundary so the visible text never ends
     * mid-word-run; it degrades to plain truncation only when there is no
     * newline near the cap.
     */
    private const val MAX_INPUT_CHARS = 200_000

    private fun capInput(text: String): String {
        if (text.length <= MAX_INPUT_CHARS) {
            return text
        }
        val cut = text.lastIndexOf('\n', MAX_INPUT_CHARS)
        return if (cut > MAX_INPUT_CHARS - 4096) {
            text.substring(0, cut)
        } else {
            text.substring(0, MAX_INPUT_CHARS)
        }
    }

    /**
     * Splits on ``` fences that actually OPEN A LINE.
     *
     * "Code block" is decided purely by segment parity, so a stray ``` counted
     * in the wrong place does not mis-render one span — it swaps the role of
     * every span after it, for the rest of the message. `split("```")` counted
     * every occurrence, including one inside a code block that is *showing*
     * markdown, one inside a quoted web page, one in a shell heredoc. A fence
     * marker is only a fence when nothing but whitespace precedes it on its
     * line, which is also what the CommonMark spec says.
     *
     * The delimiter is dropped exactly as `split` dropped it, so callers that
     * reassemble with "```" still round-trip.
     */
    internal fun splitFences(source: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var scan = 0
        while (true) {
            val at = source.indexOf("```", scan)
            if (at < 0) {
                break
            }
            if (opensLine(source, at)) {
                out.add(source.substring(start, at))
                start = at + 3
                scan = start
            } else {
                scan = at + 3
            }
        }
        out.add(source.substring(start))
        return out
    }

    /** True when only whitespace separates [at] from the start of its line. */
    private fun opensLine(text: String, at: Int): Boolean {
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

    fun render(context: Context, container: LinearLayout, markdown: String?) {
        container.removeAllViews()
        // Normalise BEFORE splitting, exactly as Streaming.update does.
        //
        // This used to split the raw text and normalise each segment
        // afterwards. With `split("```")` that made no difference; with a
        // position-sensitive, line-anchored split it does: on a response whose
        // newlines arrived as literal "\n" — the whole reason normalizeEscapes
        // exists — the fences are not at line starts until the repair has run,
        // so streaming showed a code block and this showed raw text with visible
        // backticks. The card flipped the instant the step finalised, and again
        // on every reload.
        val source = normalizeEscapes(capInput(markdown ?: ""))
        val segments = splitFences(source)
        for (i in segments.indices) {
            val segment = segments[i]
            if (i % 2 == 1) {
                addCodeBlock(context, container, segment)
            } else if (segment.isNotBlankJava()) {
                addTextBlock(context, container, segment)
            }
        }
        if (container.childCount == 0) {
            addTextBlock(context, container, source)
        }
    }

    /**
     * Repairs double-escaped model output: some providers/models leak literal
     * "\n"/"\t" sequences instead of real line breaks, which used to render as
     * ugly one-line blobs. Only triggers when literal escapes clearly dominate
     * real newlines, so genuine code snippets containing "\n" stay untouched.
     */
    internal fun normalizeEscapes(text: String?): String {
        if (text.isNullOrEmpty()) {
            return text ?: ""
        }
        var literalNl = 0
        var realNl = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                realNl++
            } else if (c == '\\' && i + 1 < text.length && text[i + 1] == 'n') {
                literalNl++
                i++
            }
            i++
        }
        if (literalNl < 2 || literalNl <= realNl) {
            return text
        }
        val sb = StringBuilder(text.length)
        var escaped = false
        for (c in text) {
            if (escaped) {
                when (c) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> Unit // dropped, exactly as the Java did
                    '"' -> sb.append('"')
                    '\'' -> sb.append('\'')
                    '\\' -> sb.append('\\')
                    else -> sb.append('\\').append(c)
                }
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else {
                sb.append(c)
            }
        }
        if (escaped) {
            sb.append('\\')
        }
        return sb.toString()
    }

    // ---- block parser ------------------------------------------------------

    /**
     * Splits one table row on unescaped pipes. `\|` stays a literal pipe inside
     * the cell. Outer empty cells produced by a leading/trailing pipe are
     * stripped. Returns null when the line holds no pipe at all, and an empty
     * list for a bare `|` run (never a row).
     *
     * Single linear scan, no regex, no per-character allocation beyond the
     * cell builders.
     */
    private fun splitTableRow(line: String): List<String>? {
        if (line.indexOf('|') < 0) {
            return null
        }
        val cells = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            if (c == '\\' && i + 1 < n && line[i + 1] == '|') {
                cur.append('|')
                i += 2
            } else if (c == '|') {
                cells.add(cur.toString())
                cur.setLength(0)
                i++
            } else {
                cur.append(c)
                i++
            }
        }
        cells.add(cur.toString())
        var start = 0
        var end = cells.size
        val t = line.trimJava()
        if (t.startsWith("|") && start < end && cells[start].trimJava().isEmpty()) {
            start++
        }
        if (t.endsWith("|") && end > start && cells[end - 1].trimJava().isEmpty()) {
            end--
        }
        return cells.subList(start, end)
    }

    /** Alignment of one column from its delimiter cell. */
    private fun columnAlign(delim: String): Int {
        val t = delim.trimJava()
        val left = t.startsWith(":")
        val right = t.endsWith(":")
        return when {
            left && right -> Gravity.CENTER_HORIZONTAL
            right -> Gravity.END
            else -> Gravity.START
        }
    }

    /**
     * Returns the index one past the last body row of the GFM table starting
     * at [start], or -1 when lines[start..start+1] are not a header+delimiter
     * pair. A table NEEDS the delimiter row; without it the lines stay prose —
     * a run of pipe lines with no delimiter is not a table, it is text that
     * happens to contain pipes.
     *
     * Body rows are the consecutive non-blank lines containing a pipe; ragged
     * rows are padded or trimmed at render time. Capped so a pathological
     * pipe-everywhere paste cannot build a thousand-row view tree.
     */
    private fun tableBlockEnd(lines: List<String>, start: Int): Int {
        if (start + 1 >= lines.size) {
            return -1
        }
        val head = lines[start].trimJava()
        // Quoted or headed pipe lines keep their old meaning: the quote /
        // heading pass owns them (it runs later, inside inlineToHtml).
        if (head.isEmpty() || head[0] == '>' || head[0] == '#') {
            return -1
        }
        if (head.indexOf('|') < 0) {
            return -1
        }
        val header = splitTableRow(lines[start]) ?: return -1
        if (header.isEmpty() || header.size > 24) {
            return -1
        }
        val delim = splitTableRow(lines[start + 1]) ?: return -1
        if (delim.isEmpty() || delim.size > 24) {
            return -1
        }
        for (cell in delim) {
            if (!DELIM_CELL.matches(cell.trimJava())) {
                return -1
            }
        }
        var end = start + 2
        val cap = minOf(lines.size, start + 502)
        while (end < cap) {
            val lt = lines[end].trimJava()
            if (lt.isEmpty() || lt.indexOf('|') < 0) {
                break
            }
            end++
        }
        return end
    }

    /**
     * Renders one GFM table: header row bold on [Theme.TABLE_HEAD_BG], zebra
     * striping via [Theme.TABLE_STRIPE], hairline separators, 8dp-ish cell padding. The
     * whole table sits in a HorizontalScrollView forced LTR so wide tables
     * scroll instead of breaking the bubble; each CELL keeps
     * FIRST_STRONG direction so Persian cell text still resolves RTL.
     *
     * Inline markdown renders inside cells through the same inline pipeline
     * as prose (bold/italic/inline code/links), minus the escape repair —
     * a cell is one line, so a literal `\n` in it is content, not a newline.
     *
     * Returns the index of the first line after the table.
     */
    private fun addGfmTable(
        context: Context, container: LinearLayout, lines: List<String>, start: Int
    ): Int {
        val end = tableBlockEnd(lines, start)
        if (end < 0) {
            // The caller checked; this is pure paranoia. Never drop content.
            addProseBlock(context, container, lines[start])
            return start + 1
        }
        val headerCells = splitTableRow(lines[start]) ?: emptyList()
        val delimCells = splitTableRow(lines[start + 1]) ?: emptyList()
        val cols = maxOf(headerCells.size, delimCells.size, 1)
        val aligns = IntArray(cols) { c ->
            if (c < delimCells.size) columnAlign(delimCells[c]) else Gravity.START
        }

        val scroll = HorizontalScrollView(context)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.layoutDirection = View.LAYOUT_DIRECTION_LTR

        val table = TableLayout(context)
        table.layoutDirection = View.LAYOUT_DIRECTION_LTR
        // Sensible in the non-scrolling case (a narrow table in a wide bubble):
        // columns share the width instead of huddling left, and may shrink
        // instead of overflowing. Inside the scroll view they are no-ops, which
        // is exactly right — there the table sizes to its content.
        table.isStretchAllColumns = true
        table.isShrinkAllColumns = true
        val hair = Theme.hairline(context)
        table.dividerDrawable = object : ColorDrawable(Theme.BORDER) {
            override fun getIntrinsicHeight(): Int = hair
        }
        table.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        table.background = Theme.tableCard(Theme.R_SM, context)
        val pad = Theme.dp(context, 4.0f)
        table.setPadding(pad, pad, pad, pad)

        val cellPadH = Theme.dp(context, 10.0f)
        val cellPadV = Theme.dp(context, 7.0f)
        // Floor, not ceiling: an empty or one-glyph column must not collapse to
        // a sliver and throw the whole grid off.
        val minCell = Theme.dp(context, 44.0f)
        val zebra = Theme.TABLE_STRIPE

        for (r in -1 until end - start - 2) {
            val isHeader = r < 0
            val rawCells = if (isHeader) {
                headerCells
            } else {
                splitTableRow(lines[start + 2 + r]) ?: emptyList()
            }
            val row = TableRow(context)
            row.layoutDirection = View.LAYOUT_DIRECTION_LTR
            if (isHeader) {
                row.setBackgroundColor(Theme.TABLE_HEAD_BG)
            } else if (r % 2 == 1) {
                row.setBackgroundColor(zebra)
            }
            for (c in 0 until cols) {
                val cellText = if (c < rawCells.size) rawCells[c].trimJava() else ""
                val tv = TextView(context)
                tv.text = inlineCell(cellText)
                tv.setTextColor(Theme.TEXT)
                tv.textSize = Ui.Type.META
                tv.typeface = Theme.ui()
                if (isHeader) {
                    tv.setTypeface(tv.typeface, Typeface.BOLD)
                }
                tv.gravity = aligns[c]
                tv.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                tv.setPadding(cellPadH, cellPadV, cellPadH, cellPadV)
                tv.minWidth = minCell
                // Cells are deliberately not selectable: one toolbar per table
                // beats one per cell inside a scrolling container.
                row.addView(tv)
            }
            table.addView(row)
        }

        scroll.addView(table)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = Theme.dp(context, 6.0f)
        container.addView(scroll, params)
        return end
    }

    /**
     * Inline markdown for one table cell. The public [inline] runs the escape
     * repair first, which would turn a literal `\n` inside a cell into a real
     * line break; a cell is a single line, so cells skip that repair.
     */
    private fun inlineCell(text: String): CharSequence =
        try {
            toSpanned(inlineToHtml(text.trimJava()))
        } catch (e: Exception) {
            text
        }

    private fun isRule(line: String): Boolean = HR.matches(line.trimJava())

    private fun addRule(context: Context, container: LinearLayout) {
        val rule = View(context)
        rule.setBackgroundColor(Theme.BORDER)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Theme.hairline(context)
        )
        val v = Theme.dp(context, 8.0f)
        params.topMargin = v
        params.bottomMargin = v
        container.addView(rule, params)
    }

    private fun isListItem(line: String): Boolean =
        TASK_ITEM.matchEntire(line) != null || LIST_ITEM.matchEntire(line) != null

    private fun leadingSpaces(line: String): Int {
        var i = 0
        while (i < line.length && line[i] == ' ') {
            i++
        }
        return i
    }

    /**
     * Renders a run of list items: unordered (`-`/`*`/`+`), ordered (`1.`/`1)`),
     * nested up to 4 levels by 2-space indent, and task lists (`- [ ]`/`- [x]`).
     * An indented non-item line continues the previous item; anything else ends
     * the list. Returns the index of the first line after the list.
     */
    private fun addListBlock(
        context: Context, container: LinearLayout, lines: List<String>, start: Int
    ): Int {
        val texts = ArrayList<StringBuilder>()
        val levels = ArrayList<Int>()
        val markers = ArrayList<String>()
        val tasks = ArrayList<Int>() // -1 plain, 0 unchecked, 1 checked
        val indents = ArrayList<Int>()
        val counters = IntArray(4)

        var i = start
        while (i < lines.size) {
            val raw = lines[i]
            if (raw.trimJava().isEmpty()) {
                break
            }
            // Tabs count as 4 spaces for nesting, exactly as CommonMark.
            val line = if (raw.indexOf('\t') >= 0) raw.replace("\t", "    ") else raw
            val task = TASK_ITEM.matchEntire(line)
            val plain = if (task == null) LIST_ITEM.matchEntire(line) else null
            if (task == null && plain == null) {
                // Continuation: a line indented past its item's own indent
                // belongs to that item; anything else ends the list.
                val last = texts.size - 1
                if (last < 0 || leadingSpaces(line) <= indents[last]) {
                    break
                }
                texts[last].append(' ').append(line.trimJava())
                i++
                continue
            }
            val indent: Int
            val level: Int
            val body: String
            val marker: String
            val taskState: Int
            if (task != null) {
                indent = task.groupValues[1].length
                body = task.groupValues[3]
                taskState = if (task.groupValues[2] == " ") 0 else 1
                marker = if (taskState == 1) "☑" else "☐"
            } else if (plain != null) {
                indent = plain.groupValues[1].length
                body = plain.groupValues[3]
                taskState = -1
                val bullet = plain.groupValues[2]
                if (bullet[0] in '0'..'9') {
                    val lv = minOf(3, indent / 2)
                    counters[lv]++
                    for (k in lv + 1..3) {
                        counters[k] = 0
                    }
                    marker = counters[lv].toString() + "."
                } else {
                    marker = "•"
                }
            } else {
                // Unreachable: the continuation branch above already broke or
                // continued when both matches were null. Kept as a guard so a
                // future refactor of that control flow cannot turn this into
                // an NPE.
                break
            }
            level = minOf(3, indent / 2)
            levels.add(level)
            markers.add(marker)
            tasks.add(taskState)
            indents.add(indent)
            texts.add(StringBuilder(body.trimJava()))
            i++
            if (texts.size > 400) {
                break
            }
        }

        val markerMin = Theme.dp(context, 22.0f)
        for (k in texts.indices) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = Theme.dp(context, (levels[k] * 14).toFloat())
            lp.bottomMargin = Theme.dp(context, 2.0f)

            val markerView = TextView(context)
            markerView.text = markers[k]
            markerView.setTextColor(
                when (tasks[k]) {
                    0 -> Theme.TEXT_FAINT
                    1 -> Theme.TEXT_MUTED
                    else -> Theme.TEXT_MUTED
                }
            )
            markerView.textSize = Ui.Type.BODY
            markerView.typeface = Theme.ui()
            markerView.gravity = Gravity.END
            markerView.minWidth = markerMin
            val markerPadEnd = Theme.dp(context, 8.0f)
            markerView.setPadding(0, 0, markerPadEnd, 0)
            row.addView(
                markerView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val content = TextView(context)
            content.text = inline(texts[k].toString())
            content.setTextColor(Theme.TEXT)
            content.textSize = Ui.Type.BODY
            content.setLineSpacing(Theme.dpf(context, 6.0f), 1.0f)
            content.typeface = Theme.ui()
            content.setTextIsSelectable(true)
            installSelectionActions(context, content)
            content.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            row.addView(
                content,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            )

            container.addView(row, lp)
        }
        return i
    }

    /**
     * Block parser for a non-fence segment: GFM tables, horizontal rules and
     * lists become real views; everything else accumulates into prose TextViews
     * (whose per-line heading/quote/bullet styling still lives in
     * [inlineToHtml], so the streaming tail — which renders through
     * [inlineToHtml] directly — looks identical to the finalised message).
     */
    private fun addTextBlock(context: Context, container: LinearLayout, text: String) {
        val lines = text.split("\n")
        val prose = StringBuilder()
        var i = 0
        val n = lines.size
        while (i < n) {
            val line = lines[i]
            val t = line.trimJava()
            if (t.isEmpty()) {
                flushProse(context, container, prose)
                i++
                continue
            }
            // Display math: a line opening with $$ or \[ collects through its
            // closing $$ / \] and renders as one centred block. Unterminated
            // delimiters fall through and render as plain text.
            val mathOpen = when {
                t.startsWith("$$") -> "$$"
                t.startsWith("\\[") -> "\\["
                else -> null
            }
            if (mathOpen != null) {
                val mathClose = if (mathOpen == "$$") "$$" else "\\]"
                val mathEnd = mathBlockEnd(lines, i, mathOpen, mathClose)
                if (mathEnd >= 0) {
                    flushProse(context, container, prose)
                    addMathBlock(
                        context, container,
                        extractMath(lines, i, mathEnd, mathOpen, mathClose)
                    )
                    i = mathEnd + 1
                    continue
                }
            }
            if (tableBlockEnd(lines, i) >= 0) {
                flushProse(context, container, prose)
                i = addGfmTable(context, container, lines, i)
                continue
            }
            if (isRule(t)) {
                flushProse(context, container, prose)
                addRule(context, container)
                i++
                continue
            }
            if (isListItem(line)) {
                flushProse(context, container, prose)
                i = addListBlock(context, container, lines, i)
                continue
            }
            if (prose.isNotEmpty()) {
                prose.append('\n')
            }
            prose.append(line)
            i++
        }
        flushProse(context, container, prose)
    }

    private fun flushProse(context: Context, container: LinearLayout, prose: StringBuilder) {
        if (prose.toString().isNotBlankJava()) {
            addProseBlock(context, container, prose.toString())
        }
        prose.setLength(0)
    }

    /**
     * Index of the line closing a display-math block opened at [start], or -1
     * when the block never closes. A one-line `$$x$$` closes on its own line.
     */
    private fun mathBlockEnd(
        lines: List<String>,
        start: Int,
        open: String,
        close: String
    ): Int {
        val first = lines[start].trimJava()
        val afterOpen = first.substring(open.length)
        if (afterOpen.contains(close)) {
            return start
        }
        for (i in start + 1 until lines.size) {
            if (lines[i].contains(close)) {
                return i
            }
        }
        return -1
    }

    /** Joins lines [start]..[end] and strips the math delimiters. */
    private fun extractMath(
        lines: List<String>,
        start: Int,
        end: Int,
        open: String,
        close: String
    ): String {
        val sb = StringBuilder()
        for (i in start..end) {
            var line = lines[i]
            if (i == start) {
                val t = line.trimJava()
                val at = t.indexOf(open)
                line = if (at >= 0) t.substring(at + open.length) else t
            }
            if (i == end) {
                val at = line.indexOf(close)
                if (at >= 0) {
                    line = line.substring(0, at)
                }
            }
            if (sb.isNotEmpty()) {
                sb.append(' ')
            }
            sb.append(line.trimJava())
        }
        return sb.toString()
    }

    /**
     * A display-math block: the LaTeX translated to Unicode, centred on its
     * own line. Monochrome like everything else — the shape of the symbols
     * carries it, not colour.
     */
    private fun addMathBlock(context: Context, container: LinearLayout, latex: String) {
        val converted = try {
            latexToUnicode(latex)
        } catch (e: Exception) {
            latex
        }
        if (converted.isBlankJava()) {
            return
        }
        val view = TextView(context)
        // Display math goes through the same typography pass as everything
        // else: serif face, true sub/superscripts, stacked fractions.
        view.text = FormulaTypography.beautify(SpannableStringBuilder(converted))
        view.setTextColor(Theme.TEXT)
        view.textSize = Ui.Type.BODY
        view.typeface = Theme.ui()
        view.gravity = Gravity.CENTER
        view.setTextIsSelectable(true)
        installSelectionActions(context, view)
        view.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = Theme.dp(context, 6.0f)
        params.bottomMargin = Theme.dp(context, 6.0f)
        container.addView(view, params)
    }

    private fun addProseBlock(context: Context, container: LinearLayout, text: String) {
        val view = TextView(context)
        view.setTextColor(Theme.TEXT)
        view.textSize = Ui.Type.BODY
        // Generous leading: this is the app's primary reading surface, and it is
        // often Persian, which needs more room between lines than Latin.
        view.setLineSpacing(Theme.dpf(context, 6.0f), 1.0f)
        view.typeface = Theme.ui()
        view.setTextIsSelectable(true)
        installSelectionActions(context, view)
        // Links are underlined, not tinted, so the link ink is simply the body
        // ink — see the <u> wrap in inlineToHtml.
        view.setLinkTextColor(Theme.TEXT)
        view.movementMethod = LinkMovementMethod.getInstance()
        view.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        view.text = toSpanned(inlineToHtml(text.trimJava()))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = Theme.dp(context, 5.0f)
        container.addView(view, params)
    }

    /** Extracts the language tag from a fenced block's raw text ("" if none). */
    internal fun codeLang(fence: String?): String {
        val body = fence ?: ""
        val nl = body.indexOf('\n')
        if (nl in 1..23) {
            val first = body.substring(0, nl).trimJava()
            if (LANG_TAG.matches(first)) {
                return first
            }
        }
        return ""
    }

    /** Returns a fenced block's code body (language line and trailing \n removed). */
    internal fun codeBody(fence: String?): String {
        var body = fence ?: ""
        val nl = body.indexOf('\n')
        if (nl in 1..23) {
            val first = body.substring(0, nl).trimJava()
            if (LANG_TAG.matches(first)) {
                body = body.substring(nl + 1)
            }
        }
        if (body.endsWith("\n")) {
            body = body.substring(0, body.length - 1)
        }
        return body
    }

    /** Builds a code card and returns the body TextView (the streamer mutates it). */
    private fun addCodeBlock(
        context: Context,
        container: LinearLayout,
        fence: String
    ): TextView {
        val lang = codeLang(fence)
        val code = codeBody(fence)
        // Holds the body view so the copy button, which is built before it, can
        // read the CURRENT text at click time. See the listener below.
        val bodyRef = arrayOfNulls<TextView>(1)

        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        // Marks this subtree as a code card, so the message-level tap-to-copy
        // panel leaves it alone — it already has its own copy button.
        card.tag = TAG_CODE_CARD
        card.background = Theme.sunkenCard(Theme.R_MD, context)
        // Clip to the corner radius: the header's own surface and the code body
        // both run edge to edge, so without this they square off the card.
        Ui.roundClip(card, Theme.R_MD)
        card.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val cardParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        cardParams.topMargin = Theme.dp(context, 4.0f)
        cardParams.bottomMargin = Theme.dp(context, 6.0f)

        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        // No wash behind the header any more: the card is one flat [Theme.SURFACE_2]
        // fill and the hairline rule below is the only thing that separates the
        // language label from the code.
        val headerPadH = Theme.dp(context, 12.0f)
        header.setPadding(
            headerPadH, Theme.dp(context, 5.0f), Theme.dp(context, 5.0f),
            Theme.dp(context, 5.0f)
        )

        val langView = TextView(context)
        langView.text = if (lang.isEmpty()) "code" else lang.lowercase()
        // The faintest ink in the palette: the language is a caption on the card,
        // not a thing to read. The tinted status dot that used to lead it is gone.
        langView.setTextColor(Theme.TEXT_FAINT)
        langView.textSize = Ui.Type.MICRO
        langView.typeface = Theme.mono()
        langView.textDirection = View.TEXT_DIRECTION_LTR
        if (Build.VERSION.SDK_INT >= 21) {
            langView.letterSpacing = 0.08f
        }
        header.addView(
            langView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        val copy = Icons.view(context, "copy", 15.0f, Theme.TEXT_MUTED)
        copy.background = Theme.rippleTransparent(Theme.R_PILL, context)
        copy.contentDescription = "Copy code"
        // 32dp box: the copy affordance was a 15dp glyph with 8dp of padding,
        // well under a comfortable target on a dense code card.
        val copyPad = Theme.dp(context, 8.5f)
        copy.setPadding(copyPad, copyPad, copyPad, copyPad)
        copy.layoutParams = LinearLayout.LayoutParams(
            Theme.dp(context, 32.0f), Theme.dp(context, 32.0f)
        )
        // Reads the body view LIVE rather than the `code` string captured above.
        //
        // `Streaming.renderTail` builds this card exactly once and thereafter only
        // mutates `body.text` as more of the fence arrives, so the captured string
        // is whatever the FIRST flush happened to hold. Copying a code block while
        // it was still streaming therefore produced a silently truncated fragment —
        // and the longer the block, the less of it you actually got.
        copy.setOnClickListener {
            val live = bodyRef[0]
            val payload = if (live != null) live.text.toString() else code
            if (copyToClipboard(context, "code", payload)) {
                Toast.makeText(context, Fa.COPIED, Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(copy)
        card.addView(header)

        val separator = View(context)
        separator.setBackgroundColor(Theme.BORDER)
        card.addView(
            separator,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.hairline(context)
            )
        )

        val scroll = HorizontalScrollView(context)
        scroll.isHorizontalScrollBarEnabled = false

        val body = TextView(context)
        body.tag = TAG_CODE_BODY
        bodyRef[0] = body
        body.text = code
        body.setTextColor(Theme.TEXT)
        body.textSize = Ui.Type.META
        body.typeface = Theme.mono()
        body.setTextIsSelectable(true)
        installSelectionActions(context, body)
        body.textDirection = View.TEXT_DIRECTION_LTR
        body.setLineSpacing(Theme.dpf(context, 2.0f), 1.0f)
        val bodyPadH = Theme.dp(context, 13.0f)
        body.setPadding(
            bodyPadH, Theme.dp(context, 11.0f), bodyPadH, Theme.dp(context, 12.0f)
        )
        scroll.addView(body)
        card.addView(scroll)

        container.addView(card, cardParams)
        return body
    }

    /**
     * Makes a selectable TextView safe to select and copy on every OEM — the
     * v9 hardening for the Xiaomi/MIUI "the app crashes the instant you select
     * text" reports — and replaces the platform selection toolbar with a
     * minimal copy + select-all.
     */
    fun installSelectionActions(context: Context, textView: TextView) {
        // 1) Opt out of the smart-selection TextClassifier. On MIUI (and a few
        //    other forks) its background entity detection throws when text is
        //    selected, taking the whole app down. NO_OP keeps plain selection
        //    working with zero crash surface. API 28+.
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                textView.setTextClassifier(
                    android.view.textclassifier.TextClassifier.NO_OP
                )
            } catch (ignored: Throwable) {
            }
        }

        // 2) Neutralise the INSERTION action mode (the popup shown on a plain
        //    tap with no selection). It is separate from the selection toolbar
        //    below and is where a rogue PROCESS_TEXT item or a bad floating-
        //    toolbar token crashes on some OEMs. These views are read-only, so
        //    suppressing that popup loses nothing.
        textView.customInsertionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu): Boolean {
                menu.clear()
                return false
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu): Boolean {
                menu.clear()
                return false
            }

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem): Boolean =
                false

            override fun onDestroyActionMode(mode: ActionMode?) {}
        }

        // 3) The selection toolbar: just Copy + Select all — nothing that can
        //    launch an external activity.
        textView.customSelectionActionModeCallback = object : ActionMode.Callback {

            override fun onCreateActionMode(mode: ActionMode?, menu: Menu): Boolean {
                menu.clear()
                menu.add(Menu.NONE, MENU_COPY, Menu.NONE, Fa.COPY)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(
                    Menu.NONE, MENU_SELECT_ALL, Menu.NONE,
                    "Select all"
                ).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }

            // Returns true — "the menu was changed". Returning false told the
            // framework nothing had changed, so the enabled state set below was
            // discarded and Copy stayed in whatever state it was created with.
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu): Boolean {
                val start = textView.selectionStart
                val end = textView.selectionEnd
                menu.findItem(MENU_COPY)?.isEnabled = start >= 0 && end >= 0 && start != end
                return true
            }

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem): Boolean {
                if (item.itemId == MENU_COPY) {
                    val start = textView.selectionStart
                    val end = textView.selectionEnd
                    if (start >= 0 && end >= 0 && start != end) {
                        val from = Math.min(start, end)
                        val to = Math.max(start, end)
                        if (copyToClipboard(
                                context, "text",
                                textView.text.subSequence(from, to).toString()
                            )
                        ) {
                            Toast.makeText(context, Fa.COPIED, Toast.LENGTH_SHORT).show()
                        }
                    }
                    return true
                }
                if (item.itemId == MENU_SELECT_ALL) {
                    // Public-API select-all: a selectable TextView always holds its
                    // text as a Spannable, so move the selection span directly.
                    // (TextView.selectAll() is a hidden API and breaks the build.)
                    try {
                        val text = textView.text
                        if (text is Spannable) {
                            Selection.setSelection(text, 0, text.length)
                        }
                    } catch (ignored: Exception) {
                    }
                    return true
                }
                return false
            }

            override fun onDestroyActionMode(mode: ActionMode?) {
                // Keep TextView's selection intact after the contextual toolbar closes.
            }
        }
    }

    /**
     * Public entry point to the hardened clipboard path, so callers outside the
     * renderer (the tap-to-copy panel) get the same OEM-safe behaviour instead
     * of hand-rolling a second, less careful copy.
     */
    fun copyText(context: Context?, label: String?, text: String?): Boolean =
        copyToClipboard(context, label, text)

    private fun copyToClipboard(context: Context?, label: String?, text: String?): Boolean {
        if (context == null) {
            return false
        }
        val value = text ?: ""
        val name = label ?: "text"
        return try {
            val clipboard = if (Build.VERSION.SDK_INT >= 23) {
                context.getSystemService(ClipboardManager::class.java)
            } else {
                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            } ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText(name, value))
            true
        } catch (firstFailure: RuntimeException) {
            // Some MIUI builds reject a transient clipboard service; retry through the
            // application context before failing without crashing the renderer.
            try {
                val app = context.applicationContext ?: return false
                val clipboard =
                    app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        ?: return false
                clipboard.setPrimaryClip(ClipData.newPlainText(name, value))
                true
            } catch (ignored: RuntimeException) {
                false
            }
        }
    }

    /**
     * Renders inline markdown (bold/italic/`code`/links) into a CharSequence —
     * used for plan steps, question options and other single-view texts.
     */
    fun inline(markdown: String?): CharSequence {
        if (markdown.isNullOrBlankJava()) {
            return markdown ?: ""
        }
        return try {
            toSpanned(inlineToHtml(normalizeEscapes(capInput(markdown.trimJava()))))
        } catch (e: Exception) {
            markdown
        }
    }

    /**
     * Incremental renderer used while the model is streaming: markdown is applied
     * live, token by token. Fully-closed segments (paragraphs / fenced code blocks)
     * are rendered once and never rebuilt; only the small mutable tail is redrawn,
     * so long answers stay smooth. If the visible text shrinks (a completed tool
     * call or <think> block gets stripped) the session resets itself safely.
     */
    class Streaming(private val ctx: Context, box: LinearLayout) {

        private val done: LinearLayout
        private val tail: LinearLayout

        private var stableText = ""
        private var committedSegs = 0
        private var tailConsumed = 0
        private var lastTail = ""
        private var lastTailCode = false
        private var lastLang: String? = null
        private var tailCodeTv: TextView? = null
        private var tailTextTv: TextView? = null

        /** Word-by-word blur/fade reveal driving the live tail. */
        private val reveal = StreamReveal.session()

        init {
            box.removeAllViews()
            done = LinearLayout(ctx)
            done.orientation = LinearLayout.VERTICAL
            box.addView(
                done,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            tail = LinearLayout(ctx)
            tail.orientation = LinearLayout.VERTICAL
            box.addView(
                tail,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        private fun reset() {
            done.removeAllViews()
            clearTail()
            stableText = ""
            committedSegs = 0
            tailConsumed = 0
        }

        private fun clearTail() {
            tail.removeAllViews()
            tailCodeTv = null
            tailTextTv = null
            lastLang = null
            reveal.reset()
        }

        /** Stops the reveal ticker; call when the row leaves the screen. */
        fun detach() {
            reveal.detach()
        }

        fun update(markdown: String?) {
            val md = normalizeEscapes(capInput(markdown ?: ""))
            if (!md.startsWith(stableText)) {
                reset()
            }
            val segs = splitFences(md)
            val n = segs.size

            // 1) commit every fully closed segment
            while (committedSegs < n - 1) {
                val seg = segs[committedSegs]
                val part = if (tailConsumed > 0 && tailConsumed <= seg.length) {
                    seg.substring(tailConsumed)
                } else {
                    seg
                }
                val isCode = (committedSegs % 2) == 1
                if (isCode) {
                    addCodeBlock(ctx, done, seg)
                } else if (part.isNotBlankJava()) {
                    addTextBlock(ctx, done, part)
                }
                stableText = stableText + part + "```"
                tailConsumed = 0
                committedSegs++
                clearTail()
            }

            // 2) the still-growing tail segment
            val tailCode = ((n - 1) % 2) == 1
            val tailSeg = segs[n - 1]
            var remainder = if (tailCode || tailConsumed > tailSeg.length) {
                tailSeg
            } else {
                tailSeg.substring(tailConsumed)
            }
            if (!tailCode) {
                // commit whole paragraphs of long answers so redraws stay small
                val cut = remainder.lastIndexOf("\n\n")
                if (cut > 700) {
                    val head = remainder.substring(0, cut + 2)
                    if (head.isNotBlankJava()) {
                        addTextBlock(ctx, done, head)
                    }
                    stableText += head
                    tailConsumed += head.length
                    remainder = remainder.substring(cut + 2)
                    clearTail()
                }
            }
            renderTail(remainder, tailCode)
        }

        private fun renderTail(text: String?, isCode: Boolean) {
            lastTail = text ?: ""
            lastTailCode = isCode

            if (isCode) {
                val lang = codeLang(lastTail)
                var codeView = tailCodeTv
                if (codeView == null || lang != lastLang) {
                    tail.removeAllViews()
                    tailTextTv = null
                    codeView = addCodeBlock(ctx, tail, lastTail)
                    tailCodeTv = codeView
                    lastLang = lang
                    codeView.setTextIsSelectable(false)
                }
                codeView.text = codeBody(lastTail)
                return
            }

            var textView = tailTextTv
            if (textView == null) {
                tail.removeAllViews()
                tailCodeTv = null
                lastLang = null
                textView = TextView(ctx)
                textView.setTextColor(Theme.TEXT)
                textView.textSize = Ui.Type.BODY
                // MUST match addProseBlock's size and leading: this is the
                // streaming tail, and any difference makes the answer visibly
                // re-flow the instant the step finalises and the same text is
                // re-rendered. Both sites read the declared body step of the
                // type scale, which is also what the user bubble uses — the
                // answer must not be set smaller than the question above it.
                textView.setLineSpacing(Theme.dpf(ctx, 6.0f), 1.0f)
                textView.typeface = Theme.ui()
                textView.setTextIsSelectable(true)
                installSelectionActions(ctx, textView)
                textView.setLinkTextColor(Theme.TEXT)
                textView.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = Theme.dp(ctx, 5.0f)
                tail.addView(textView, params)
                tailTextTv = textView
            }
            try {
                val sb = SpannableStringBuilder(toSpanned(inlineToHtml(lastTail.trimJava())))
                // Stamp and span the words *before* handing the text to the
                // view, so the first frame already carries the reveal state and
                // a new word never flashes in at full opacity for one frame.
                reveal.apply(textView, sb)
                textView.text = sb
            } catch (e: Exception) {
                textView.text = lastTail
            }
        }
    }

    // ---- diff card ---------------------------------------------------------

    /**
     * Diff colours.
     *
     * This used to be greyscale on principle: both halves of a diff were washes of
     * the same ink at different strengths, with the `+` / `−` gutter carrying the
     * actual meaning. It was the one place the monochrome rule cost more than it
     * bought — added and removed are opposites, not two amounts of one thing, and a
     * reader scanning a hunk has to see which is which without decoding a glyph.
     *
     * So diffs, and only diffs, carry hue: [Theme.DIFF_ADD] / [Theme.DIFF_DEL] for
     * the ink and the sign, their `_BG` pair for the wash behind the line. Both
     * pairs are GitHub's own restrained values and both invert with the theme, so
     * they stay legible on the near-black card and the near-white one alike.
     *
     * Computed properties rather than constants, because a compile-time constant
     * cannot read the mutable [Theme] globals.
     */
    private val DIFF_ADD_BG get() = Theme.DIFF_ADD_BG
    private val DIFF_DEL_BG get() = Theme.DIFF_DEL_BG
    private val DIFF_ADD_GUTTER get() = Theme.DIFF_ADD
    private val DIFF_DEL_GUTTER get() = Theme.DIFF_DEL

    /** One diff row: op (-1 removed / 0 same / +1 added) plus both line indices. */
    // One line of a diff. Lives in [Diff] now — see the note on [diff] below.
    private fun addedRow(index: Int): Diff.Row = Diff.Row(1, -1, index)

    /**
     * Unified-style diff card. When [allNew] is true every line of [newText] is
     * rendered as an addition (used when a file is created from scratch).
     */
    fun buildDiffCard(
        context: Context,
        oldText: String?,
        newText: String?,
        allNew: Boolean
    ): View {
        val card = LinearLayout(context)
        card.orientation = LinearLayout.VERTICAL
        // Marks this subtree as a code card, so the message-level tap-to-copy
        // panel leaves it alone — it already has its own copy button.
        card.tag = TAG_CODE_CARD
        card.background = Theme.sunkenCard(Theme.R_MD, context)
        // Each changed line paints a full-width tint; without clipping, the top
        // and bottom rows square off the card's rounded corners.
        Ui.roundClip(card, Theme.R_MD)
        card.layoutDirection = View.LAYOUT_DIRECTION_LTR

        val oldLines = (oldText ?: "").split("\n")
        val newLines = (newText ?: "").split("\n")

        val rows: List<Diff.Row> = if (allNew) {
            newLines.indices.map { addedRow(it) }
        } else {
            diff(oldLines, newLines)
        }

        // A +N / −N summary above the hunk. Scrolling a 400-line diff to work
        // out how much actually changed is not a reasonable ask.
        var added = 0
        var removed = 0
        for (row in rows) {
            if (row.op > 0) {
                added++
            } else if (row.op < 0) {
                removed++
            }
        }
        val summary = LinearLayout(context)
        summary.orientation = LinearLayout.HORIZONTAL
        summary.gravity = Gravity.CENTER_VERTICAL
        summary.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val summaryPadH = Theme.dp(context, 12.0f)
        summary.setPadding(
            summaryPadH, Theme.dp(context, 6.0f), summaryPadH, Theme.dp(context, 6.0f)
        )
        val addedView = TextView(context)
        addedView.tag = TAG_CODE_BODY
        addedView.text = "+" + added
        addedView.setTextColor(Theme.DIFF_ADD)
        addedView.textSize = Ui.Type.MICRO
        addedView.typeface = Theme.mono()
        summary.addView(addedView)
        val removedView = TextView(context)
        removedView.tag = TAG_CODE_BODY
        removedView.text = "−" + removed
        removedView.setTextColor(Theme.DIFF_DEL)
        removedView.textSize = Ui.Type.MICRO
        removedView.typeface = Theme.mono()
        val removedParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        removedParams.marginStart = Theme.dp(context, 10.0f)
        summary.addView(removedView, removedParams)
        card.addView(
            summary,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val summaryRule = View(context)
        summaryRule.setBackgroundColor(Theme.BORDER)
        card.addView(
            summaryRule,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.hairline(context)
            )
        )

        var rendered = 0
        for (row in rows) {
            if (rendered > 400) {
                break
            }
            rendered++

            val lineText = if (row.op < 0) oldLines[row.oldIndex] else newLines[row.newIndex]

            val line = LinearLayout(context)
            line.orientation = LinearLayout.HORIZONTAL
            line.layoutDirection = View.LAYOUT_DIRECTION_LTR

            val background = when {
                row.op < 0 -> DIFF_DEL_BG
                row.op > 0 -> DIFF_ADD_BG
                else -> 0
            }
            if (background != 0) {
                line.setBackgroundColor(background)
            }

            val marker = TextView(context)
            marker.text = when {
                row.op < 0 -> "−"
                row.op > 0 -> "+"
                else -> " "
            }
            marker.setTextColor(
                when {
                    row.op < 0 -> DIFF_DEL_GUTTER
                    row.op > 0 -> DIFF_ADD_GUTTER
                    else -> Theme.TEXT_FAINT
                }
            )
            marker.typeface = Theme.mono()
            marker.textSize = Ui.Type.META
            marker.gravity = Gravity.CENTER_HORIZONTAL
            // The sign is INK now, not a second wash. A stronger wash behind the
            // gutter made sense while add and remove were the same hue and the
            // column had to be found by contrast; with the sign itself coloured,
            // stacking another wash under it only muddied both.
            marker.setPadding(Theme.dp(context, 8.0f), 0, Theme.dp(context, 6.0f), 0)
            line.addView(
                marker,
                LinearLayout.LayoutParams(
                    Theme.dp(context, 26.0f), ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            val content = TextView(context)
            content.text = if (lineText.isEmpty()) " " else lineText
            content.setTextColor(
                when {
                    row.op < 0 -> DIFF_DEL_GUTTER
                    row.op > 0 -> DIFF_ADD_GUTTER
                    else -> Theme.TEXT_FAINT
                }
            )
            content.typeface = Theme.mono()
            content.textSize = Ui.Type.META
            content.textDirection = View.TEXT_DIRECTION_LTR
            content.setPadding(
                Theme.dp(context, 4.0f), Theme.dp(context, 2.0f),
                Theme.dp(context, 10.0f), Theme.dp(context, 2.0f)
            )
            line.addView(
                content,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            )

            card.addView(
                line,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return card
    }

    /**
     * The line diff, which now lives in [Diff] because the engine needs it too.
     *
     * It used to be a private function here, and that made the answer to "what did
     * this edit change?" reachable only from a view. The activity strip wants to
     * put `+12 −3` on a row while the edit is still running, from a worker thread
     * with no Context in sight — so the algorithm moved out and this renders what
     * it returns. See [Diff].
     */
    private fun diff(oldLines: List<String>, newLines: List<String>): List<Diff.Row> = Diff.rows(
        oldLines, newLines
    )

    // ---- inline markdown -> HTML subset ------------------------------------

    private fun inlineToHtml(markdown: String): String {
        val escaped = markdown
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            // '"' MUST be escaped too: the link pass below interpolates a
            // model-supplied URL straight into href="…", so an unescaped quote
            // let arbitrary attributes and tags through to Html.fromHtml.
            .replace("\"", "&quot;")

        val sb = StringBuilder()
        for (line in escaped.split("\n")) {
            val heading = HEADING.matchEntire(line)
            val quote = QUOTE.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            when {
                // Headings are WEIGHT, not colour: full-strength ink and bold.
                heading != null ->
                    sb.append("<b><font color=\"").append(hex(Theme.TEXT)).append("\">")
                        .append(heading.groupValues[2]).append("</font></b><br>")

                quote != null ->
                    sb.append("<font color=\"").append(hex(Theme.TEXT_MUTED))
                        .append("\"><i>│ ").append(quote.groupValues[1])
                        .append("</i></font><br>")

                bullet != null ->
                    sb.append("<font color=\"").append(hex(Theme.TEXT_MUTED))
                        .append("\">•</font>&nbsp; ")
                        .append(bullet.groupValues[1]).append("<br>")

                else -> sb.append(line).append("<br>")
            }
        }

        var html = sb.toString()
        if (html.endsWith("<br>")) {
            html = html.substring(0, html.length - 4)
        }
        // Inline code is lifted out FIRST and restored last. Running the code
        // pass at the end meant `a*b*c` had already been italicised, so it
        // rendered as a<i>b</i>c inside a monospace run.
        val codeSpans = ArrayList<String>()
        html = repl(html, "`([^`]+?)`") { match ->
            codeSpans.add(match.groupValues[1])
            "\u0000CODE" + (codeSpans.size - 1) + "\u0000"
        }
        // Autolinks <https://…>: the escape pass turned the brackets into
        // &lt;/&gt;, so the escaped form is matched. Code spans are already
        // tokens, so a URL shown as code is never linkified by accident; and
        // the `"`-escape above keeps a quote inside the URL entity-encoded, so
        // the interpolated href cannot break out of its attribute.
        val autoLinks = ArrayList<String>()
        html = repl(html, AUTOLINK.pattern) { match ->
            autoLinks.add(match.groupValues[1])
            "\u0000LINK" + (autoLinks.size - 1) + "\u0000"
        }
        // Math is lifted right after autolinks and before emphasis: a `$` or
        // `\(` inside a URL or code span must never become math, and a `*` or
        // `_` inside math (a*b, x_i) must never become emphasis. The converted
        // text is pure Unicode (already HTML-safe: the escape pass ran first),
        // so the restore below inserts it verbatim with no extra markup.
        val mathSpans = ArrayList<String>()
        html = liftMathSpans(html, mathSpans)
        // ***x*** before ** and *: bold-then-italic on the same run produced
        // the mis-nested <b><i>x</b></i>, which Html.fromHtml renders wrong.
        html = repl(html, "\\*\\*\\*(.+?)\\*\\*\\*", "<b><i>$1</i></b>")
        html = repl(html, "__(.+?)__", "<b>$1</b>")
        html = repl(html, "\\*\\*(.+?)\\*\\*", "<b>$1</b>")
        html = repl(html, "(?<![\\*])\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", "<i>$1</i>")
        // Underscore emphasis never fires inside a word (foo_bar stays foo_bar):
        // both edges need a non-word character outside the marks.
        html = repl(html, "(?<!\\w)_(?!_)(.+?)(?<!_)_(?!\\w)", "<i>$1</i>")
        html = repl(html, "~~(.+?)~~", "<strike>$1</strike>")
        // A link is marked by an UNDERLINE, not by a colour — there is no blue in
        // this palette to spend, and an accent-coloured link on a monochrome page
        // is just bolder body text. `URLSpan` underlines by default; the explicit
        // <u> keeps the mark if a future span replaces it.
        html = repl(html, "\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)", "<a href=\"$2\"><u>$1</u></a>")
        for (i in codeSpans.indices) {
            html = html.replace(
                "\u0000CODE" + i + "\u0000",
                "<font color=\"" + hex(Theme.TEXT) + "\" face=\"monospace\">" +
                    codeSpans[i] + "</font>"
            )
        }
        for (i in autoLinks.indices) {
            val url = autoLinks[i]
            html = html.replace(
                "\u0000LINK" + i + "\u0000",
                "<a href=\"" + url + "\"><u>" + url + "</u></a>"
            )
        }
        for (i in mathSpans.indices) {
            html = html.replace("\u0000MATH" + i + "\u0000", mathSpans[i])
        }
        return html
    }

    private fun repl(text: String, pattern: String, replacement: String): String = try {
        Regex(pattern, RegexOption.DOT_MATCHES_ALL).replace(text, replacement)
    } catch (e: Exception) {
        text
    }

    private fun repl(
        text: String,
        pattern: String,
        transform: (MatchResult) -> CharSequence
    ): String = try {
        Regex(pattern, RegexOption.DOT_MATCHES_ALL).replace(text, transform)
    } catch (e: Exception) {
        text
    }

    // ------------------------------------------------------------------
    // Math notation: LaTeX -> Unicode.
    //
    // There is no TeX engine on the phone, so math is rendered by
    // translating the LaTeX the model wrote into the Unicode math it
    // means: \frac{1}{2} -> 1/2, \sqrt{x} -> √(x), x^2 -> x²,
    // \alpha -> α. Delimiters: $$…$$ or \[…\] for display math (its own
    // centred block), $…$ or \(…\) for inline math.
    // ------------------------------------------------------------------

    /**
     * Inline math delimiters, lifted into tokens like code spans. `$$…$$`
     * first (so it is never misread as two `$…$` pairs), then `\(…\)`,
     * then `$…$`. A `$` pair whose content starts or ends with whitespace
     * is left alone — that is how "$5 and $10" survives.
     */
    private fun liftMathSpans(html: String, out: ArrayList<String>): String {
        var s = html
        s = repl(s, "\\$\\$([^$<]+?)\\$\\$") { m -> mathToken(out, m.groupValues[1]) }
        s = repl(s, "\\\\\\(([^<]+?)\\\\\\)") { m -> mathToken(out, m.groupValues[1]) }
        s = repl(s, "\\$([^$<]+?)\\$") { m ->
            val body = m.groupValues[1]
            if (body.isEmpty() || isSpace(body[0]) || isSpace(body[body.length - 1])) {
                m.value
            } else {
                mathToken(out, body)
            }
        }
        return s
    }

    private fun mathToken(out: ArrayList<String>, latex: String): String {
        out.add(latexToUnicode(latex))
        return "\u0000MATH" + (out.size - 1) + "\u0000"
    }

    private fun isSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r'

    /**
     * Translates a LaTeX math fragment into Unicode. Unknown commands are
     * left as written rather than dropped — showing `\foo` is more honest
     * than showing nothing.
     */
    internal fun latexToUnicode(latex: String): String {
        var s = latex
        // Display/text style variants collapse to the plain fraction form.
        s = s.replace("\\dfrac", "\\frac").replace("\\tfrac", "\\frac")
        // \frac{a}{b} and \sqrt — innermost first (the character classes
        // refuse nested braces), looped so nesting resolves layer by layer.
        var guard = 0
        while (guard++ < 8) {
            var changed = false
            s = Regex("\\\\frac\\{([^{}]*)\\}\\{([^{}]*)\\}").replace(s) { m ->
                changed = true
                renderFraction(m.groupValues[1], m.groupValues[2])
            }
            s = Regex("\\\\sqrt\\[([^\\]]*)\\]\\{([^{}]*)\\}").replace(s) { m ->
                changed = true
                toSuper(m.groupValues[1]) + "√(" + m.groupValues[2] + ")"
            }
            s = Regex("\\\\sqrt\\{([^{}]*)\\}").replace(s) { m ->
                changed = true
                "√(" + m.groupValues[1] + ")"
            }
            if (!changed) {
                break
            }
        }
        // \text{…} and friends: keep the words, drop the wrapper.
        s = Regex("\\\\(?:text|mathrm|mathbf|mathit|mathsf|mathtt)\\{([^{}]*)\\}")
            .replace(s, "$1")
        // \left( and \right): the stretchiness is lost, the brackets stay.
        s = Regex("\\\\(?:left|right)\\s*").replace(s, "")
        // \\ is a row break in TeX; inline it is just a gap.
        s = s.replace("\\\\", " ")
        // Thin/medium/thick spaces and the escaped space.
        s = Regex("\\\\[,;:! ]").replace(s, " ")
        // Named commands: \alpha -> α, \times -> ×, …
        s = Regex("\\\\([a-zA-Z]+)").replace(s) { m ->
            LATEX_COMMANDS[m.groupValues[1]] ?: m.value
        }
        // Superscripts and subscripts: x^{12} -> x¹², x_2 -> x₂.
        s = Regex("\\^\\{([^{}]*)\\}").replace(s) { m -> toSuper(m.groupValues[1]) }
        s = Regex("\\^(\\S)").replace(s) { m -> toSuper(m.groupValues[1]) }
        s = Regex("_\\{([^{}]*)\\}").replace(s) { m -> toSub(m.groupValues[1]) }
        s = Regex("_(\\S)").replace(s) { m -> toSub(m.groupValues[1]) }
        // In math, ' is a prime and * is multiplication — never emphasis.
        s = s.replace("'", "′").replace("*", "∗")
        return s
    }

    /** \frac{a}{b}: a vulgar fraction when one exists, else a⁄b. */
    private fun renderFraction(num: String, den: String): String {
        val n = num.trimJava()
        val d = den.trimJava()
        val vulgar = VULGAR_FRACTIONS[n + "/" + d]
        if (vulgar != null) {
            return vulgar
        }
        if (n.isEmpty() || d.isEmpty()) {
            return n + d
        }
        return n + "⁄" + d
    }

    private fun toSuper(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            sb.append(SUPERSCRIPT[c] ?: c)
        }
        return sb.toString()
    }

    private fun toSub(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            sb.append(SUBSCRIPT[c] ?: c)
        }
        return sb.toString()
    }

    /**
     * LaTeX command -> Unicode. Covers the Greek alphabet, the operators and
     * relations models actually emit, and the arrows. Anything not listed is
     * left verbatim by [latexToUnicode].
     */
    private val LATEX_COMMANDS: Map<String, String> = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ",
        "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "sigma" to "σ",
        "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ",
        "Psi" to "Ψ", "Omega" to "Ω",
        "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
        "cdot" to "·", "ast" to "∗",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥",
        "neq" to "≠", "ne" to "≠", "approx" to "≈", "equiv" to "≡",
        "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝",
        "ll" to "≪", "gg" to "≫",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇",
        "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮",
        "sqrt" to "√",
        "forall" to "∀", "exists" to "∃", "in" to "∈", "notin" to "∉",
        "ni" to "∋", "subset" to "⊂", "subseteq" to "⊆",
        "supset" to "⊃", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩",
        "setminus" to "∖", "emptyset" to "∅",
        "vee" to "∨", "wedge" to "∧", "neg" to "¬", "lnot" to "¬",
        "land" to "∧", "lor" to "∨",
        "rightarrow" to "→", "to" to "→", "leftarrow" to "←",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "mapsto" to "↦",
        "uparrow" to "↑", "downarrow" to "↓",
        "langle" to "⟨", "rangle" to "⟩",
        "ldots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
        "dots" to "…",
        "circ" to "∘", "bullet" to "•", "oplus" to "⊕", "otimes" to "⊗",
        "perp" to "⊥", "parallel" to "∥", "angle" to "∠",
        "hbar" to "ħ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
        "aleph" to "ℵ", "prime" to "′", "degree" to "°"
    )

    private val SUPERSCRIPT: Map<Char, Char> = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
        'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'j' to 'ʲ', 'k' to 'ᵏ',
        'l' to 'ˡ', 'm' to 'ᵐ', 'o' to 'ᵒ', 'p' to 'ᵖ', 'r' to 'ʳ',
        's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ', 'v' to 'ᵛ', 'w' to 'ʷ',
        'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ'
    )

    private val SUBSCRIPT: Map<Char, Char> = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ',
        'h' to 'ₕ', 'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ',
        'p' to 'ₚ', 's' to 'ₛ', 't' to 'ₜ',
        'i' to 'ᵢ', 'j' to 'ⱼ', 'r' to 'ᵣ', 'u' to 'ᵤ', 'v' to 'ᵥ'
    )

    private val VULGAR_FRACTIONS: Map<String, String> = mapOf(
        "1/2" to "½", "1/3" to "⅓", "2/3" to "⅔",
        "1/4" to "¼", "3/4" to "¾",
        "1/5" to "⅕", "2/5" to "⅖", "3/5" to "⅗", "4/5" to "⅘",
        "1/6" to "⅙", "5/6" to "⅚", "1/7" to "⅐", "1/8" to "⅛",
        "3/8" to "⅜", "5/8" to "⅝", "7/8" to "⅞",
        "1/9" to "⅑", "1/10" to "⅒"
    )

    /**
     * ARGB -> "#RRGGBB". Pinned to Locale.US so a locale with non-ASCII digits
     * can never produce an unparsable colour for Html.fromHtml.
     */
    private fun hex(color: Int): String =
        String.format(Locale.US, "#%06X", color and 0xFFFFFF)

    @Suppress("DEPRECATION")
    private fun toSpanned(html: String): Spanned {
        // fromHtml(String, int) is API 24+; calling it on Android 6 throws
        // NoSuchMethodError (an Error, uncatchable by the surrounding
        // Exception handlers) and kills the whole chat render.
        val base = if (Build.VERSION.SDK_INT >= 24) {
            android.text.Html.fromHtml(html, 0)
        } else {
            android.text.Html.fromHtml(html)
        }
        // DeepSeek-style formula typography: serif math face, true
        // sub/superscripts, stacked fractions, plain-text chemistry. Code is
        // shielded inside; this never throws (it returns the input untouched
        // on failure).
        return FormulaTypography.beautify(base)
    }
}
