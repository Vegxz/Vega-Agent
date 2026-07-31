package com.vepro.code

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
    const val TAG_CODE_CARD = "vepro_code_card"

    /** Language tag on a fence, e.g. ```kotlin */
    private val LANG_TAG = Regex("[a-zA-Z0-9_+-]{1,20}")
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val QUOTE = Regex("^\\s*&gt;\\s?(.*)$")
    private val BULLET = Regex("^\\s*[-*]\\s+(.*)$")

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
        val source = normalizeEscapes(markdown ?: "")
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

    /** A markdown pipe-table row, e.g. `| a | b |` or `|---|---|`. */
    private fun isTableLine(line: String): Boolean {
        val t = line.trimJava()
        return t.length >= 3 && t.startsWith("|") && t.lastIndexOf('|') > 0
    }

    /**
     * Splits a text block into runs of table lines and ordinary prose, so a
     * table can be rendered as one LTR monospace unit.
     *
     * Every line is otherwise its own bidi paragraph: in a mixed Persian/Latin
     * table the header row could resolve LTR while data rows resolved RTL, so
     * the columns visually reversed from row to row. Keeping the whole table in
     * one forced-LTR view makes the columns line up — the same treatment code
     * blocks already get.
     */
    private fun addTextBlock(context: Context, container: LinearLayout, text: String) {
        val lines = text.split("\n")
        if (lines.count { isTableLine(it) } >= 2) {
            val run = StringBuilder()
            var runIsTable = false
            fun flush() {
                val body = run.toString().trimJava()
                run.setLength(0)
                if (body.isEmpty()) {
                    return
                }
                if (runIsTable) {
                    addTableBlock(context, container, body)
                } else {
                    addProseBlock(context, container, body)
                }
            }
            for (line in lines) {
                val table = isTableLine(line)
                if (run.isNotEmpty() && table != runIsTable) {
                    flush()
                }
                runIsTable = table
                if (run.isNotEmpty()) {
                    run.append('\n')
                }
                run.append(line)
            }
            flush()
            return
        }
        addProseBlock(context, container, text)
    }

    /** A whole pipe table, held in one LTR monospace view so columns align. */
    private fun addTableBlock(context: Context, container: LinearLayout, text: String) {
        val scroll = HorizontalScrollView(context)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val view = TextView(context)
        view.setTextColor(Theme.TEXT)
        view.textSize = Ui.Type.META
        view.typeface = Theme.mono()
        view.setLineSpacing(Theme.dpf(context, 3.0f), 1.0f)
        view.setTextIsSelectable(true)
        installSelectionActions(context, view)
        view.textDirection = View.TEXT_DIRECTION_LTR
        view.text = text
        view.background = Theme.sunkenCard(Theme.R_SM, context)
        val pad = Theme.dp(context, 10.0f)
        view.setPadding(pad, pad, pad, pad)
        scroll.addView(view)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = Theme.dp(context, 6.0f)
        container.addView(scroll, params)
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
            toSpanned(inlineToHtml(normalizeEscapes(markdown.trimJava())))
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
            val md = normalizeEscapes(markdown ?: "")
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
        addedView.text = "+" + added
        addedView.setTextColor(Theme.DIFF_ADD)
        addedView.textSize = Ui.Type.MICRO
        addedView.typeface = Theme.mono()
        summary.addView(addedView)
        val removedView = TextView(context)
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
        // ***x*** before ** and *: bold-then-italic on the same run produced
        // the mis-nested <b><i>x</b></i>, which Html.fromHtml renders wrong.
        html = repl(html, "\\*\\*\\*(.+?)\\*\\*\\*", "<b><i>$1</i></b>")
        html = repl(html, "\\*\\*(.+?)\\*\\*", "<b>$1</b>")
        html = repl(html, "(?<![\\*])\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)", "<i>$1</i>")
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

    /**
     * ARGB -> "#RRGGBB". Pinned to Locale.US so a locale with non-ASCII digits
     * can never produce an unparsable colour for Html.fromHtml.
     */
    private fun hex(color: Int): String =
        String.format(Locale.US, "#%06X", color and 0xFFFFFF)

    @Suppress("DEPRECATION")
    private fun toSpanned(html: String): Spanned = android.text.Html.fromHtml(html, 0)
}
