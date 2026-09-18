package github.vega.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan

/**
 * DeepSeek-style formula typography for math and chemistry.
 *
 * Model output arrives as plain text (or LaTeX already lowered to Unicode by
 * [MarkdownRenderer.latexToUnicode], e.g. `CH_4` -> `CH₄`, `\frac{a}{b}` ->
 * `a⁄b`, `\alpha` -> `α`). Rendered in the UI font that reads as the "ugly"
 * font: Unicode sub/superscripts in a Persian-first sans look wrong, and
 * fractions sit inline as `a⁄b`.
 *
 * This pass runs over every rendered message span ([MarkdownRenderer.toSpanned]
 * is the single hook, so paragraphs, table cells, display-math blocks and the
 * streaming tail all go through it; code blocks never do — they bypass
 * `toSpanned`, and inline code is shielded by its monospace span) and:
 *
 * - sets formula runs in a real serif math face (Latin Modern Math, the
 *   classic TeX/Computer Modern look, GUST Font License, bundled in assets)
 *   — Latin letters, digits, Greek and operators all take the familiar
 *   DeepSeek-style typeset look;
 * - turns Unicode sub/superscripts into true lowered/raised spans, so the
 *   positioning never depends on the font happening to ship those glyphs;
 * - stacks `a⁄b` fractions as a real numerator-over-rule-over-denominator,
 *   drawn in the same serif face;
 * - recognises plain-text chemistry (`CH4(g) + 2O2(g) -> CO2(g) + 2H2O(g)`)
 *   that never went through LaTeX, and gives it the same treatment.
 *
 * Everything is best-effort and total: [beautify] never throws — a failure
 * returns the input untouched rather than breaking a message render.
 */
object FormulaTypography {

    private const val FONT_ASSET = "fonts/LatinModernMath-Regular.otf"

    /** Fraction slash, the only thing [MarkdownRenderer.renderFraction] emits. */
    private const val FRACTION_SLASH = '⁄'

    /** Object-replacement placeholder; a [StackedFractionSpan] draws over it. */
    private const val FRACTION_PLACEHOLDER = '￼'

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var mathTypeface: Typeface? = null

    @Volatile
    private var typefaceTried = false

    /** Call once from [App.onCreate]; the application context is retained. */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            // Warm the font off the main thread so the first message render
            // never waits on asset loading.
            val warm = Thread({ typeface() }, "FormulaTypography-init")
            warm.isDaemon = true
            warm.start()
        }
    }

    private fun typeface(): Typeface? {
        mathTypeface?.let { return it }
        if (typefaceTried) {
            return null
        }
        typefaceTried = true
        val ctx = appContext ?: return null
        mathTypeface = try {
            Typeface.createFromAsset(ctx.assets, FONT_ASSET)
        } catch (e: Exception) {
            null
        }
        return mathTypeface
    }

    /** Applies the serif math face. A no-op span when the font failed to load. */
    private class MathFontSpan(private val tf: Typeface?) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) {
            if (tf != null) {
                tp.typeface = tf
            }
        }

        override fun updateMeasureState(tp: TextPaint) {
            if (tf != null) {
                tp.typeface = tf
            }
        }
    }

    /**
     * A true stacked fraction: numerator centred over a horizontal rule over
     * the denominator, all in the serif math face. Sub/superscript Unicode
     * inside either half is honoured while drawing (a span cannot reach inside
     * custom-drawn text).
     */
    private class StackedFractionSpan(
        private val num: String,
        private val den: String,
        private val tf: Typeface?
    ) : ReplacementSpan() {

        private fun scriptPaint(base: Paint): TextPaint {
            // TextPaint (not Paint): the rich measuring/drawing below leans
            // on baselineShift, which only exists on TextPaint.
            val p = TextPaint()
            p.setFlags(base.flags)
            p.color = base.color
            p.textSize = base.textSize * SCRIPT_SCALE
            p.typeface = if (tf != null) tf else base.typeface
            return p
        }

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val p = scriptPaint(paint)
            val w = maxOf(measureRich(p, num), measureRich(p, den))
            if (fm != null) {
                // Numerator sits ~1.55 text sizes above the baseline, the
                // denominator ~0.95 below; the line grows to fit, exactly like
                // a display fraction in a typeset document.
                val ts = paint.textSize
                fm.ascent = -(ts * 1.55f).toInt()
                fm.top = fm.ascent
                fm.descent = (ts * 0.95f).toInt()
                fm.bottom = fm.descent
            }
            return (w + paint.textSize * 0.5f).toInt()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val p = scriptPaint(paint)
            val ts = paint.textSize
            val w = maxOf(measureRich(p, num), measureRich(p, den))
            val cx = x + w / 2.0f + ts * 0.25f
            val numBaseline = y - ts * 0.92f
            val ruleY = y - ts * 0.30f
            val denBaseline = y + ts * 0.68f
            drawRich(canvas, p, num, cx, numBaseline)
            val ruleH = maxOf(1.5f, ts * 0.045f)
            val half = w / 2.0f + ts * 0.12f
            canvas.drawRect(cx - half, ruleY - ruleH / 2.0f, cx + half, ruleY + ruleH / 2.0f, p)
            drawRich(canvas, p, den, cx, denBaseline)
        }

        companion object {
            private const val SCRIPT_SCALE = 0.78f

            /** Width of [s], honouring sub/superscript runs drawn at 0.72x. */
            private fun measureRich(p: TextPaint, s: String): Float {
                var w = 0.0f
                var i = 0
                val saved = p.textSize
                while (i < s.length) {
                    val c = s[i]
                    if (isSubChar(c) || isSupChar(c)) {
                        p.textSize = saved * 0.72f
                        w += p.measureText(String(charArrayOf(baseChar(c))))
                        p.textSize = saved
                    } else {
                        w += p.measureText(String(charArrayOf(c)))
                    }
                    i++
                }
                p.textSize = saved
                return w
            }

            /** Draws [s] centred on [cx], honouring sub/superscript runs. */
            private fun drawRich(canvas: Canvas, p: TextPaint, s: String, cx: Float, baseline: Float) {
                val w = measureRich(p, s)
                var x = cx - w / 2.0f
                val saved = p.textSize
                val savedBaseline = p.baselineShift
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    val base = baseChar(c)
                    if (isSubChar(c) || isSupChar(c)) {
                        p.textSize = saved * 0.72f
                        val glyph = String(charArrayOf(base))
                        val dy = saved * (if (isSubChar(c)) 0.22f else -0.42f)
                        canvas.drawText(glyph, x, baseline + dy, p)
                        x += p.measureText(glyph)
                        p.textSize = saved
                    } else {
                        val glyph = String(charArrayOf(c))
                        canvas.drawText(glyph, x, baseline, p)
                        x += p.measureText(glyph)
                    }
                    i++
                }
                p.textSize = saved
                p.baselineShift = savedBaseline
            }
        }
    }

    // ------------------------------------------------------------------
    // Character tables
    // ------------------------------------------------------------------

    private val SUB_BASE: Map<Char, Char> = mapOf(
        '₀' to '0', '₁' to '1', '₂' to '2', '₃' to '3', '₄' to '4',
        '₅' to '5', '₆' to '6', '₇' to '7', '₈' to '8', '₉' to '9',
        '₊' to '+', '₋' to '-', '₌' to '=', '₍' to '(', '₎' to ')',
        'ₐ' to 'a', 'ₑ' to 'e', 'ₒ' to 'o', 'ₓ' to 'x',
        'ₕ' to 'h', 'ₖ' to 'k', 'ₗ' to 'l', 'ₘ' to 'm', 'ₙ' to 'n',
        'ₚ' to 'p', 'ₛ' to 's', 'ₜ' to 't',
        'ᵢ' to 'i', 'ⱼ' to 'j', 'ᵣ' to 'r', 'ᵤ' to 'u', 'ᵥ' to 'v'
    )

    private val SUP_BASE: Map<Char, Char> = mapOf(
        '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4',
        '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9',
        '⁺' to '+', '⁻' to '-', '⁼' to '=', '⁽' to '(', '⁾' to ')',
        'ⁿ' to 'n', 'ⁱ' to 'i',
        'ᵃ' to 'a', 'ᵇ' to 'b', 'ᶜ' to 'c', 'ᵈ' to 'd', 'ᵉ' to 'e',
        'ᶠ' to 'f', 'ᵍ' to 'g', 'ʰ' to 'h', 'ʲ' to 'j', 'ᵏ' to 'k',
        'ˡ' to 'l', 'ᵐ' to 'm', 'ᵒ' to 'o', 'ᵖ' to 'p', 'ʳ' to 'r',
        'ˢ' to 's', 'ᵗ' to 't', 'ᵘ' to 'u', 'ᵛ' to 'v', 'ʷ' to 'w',
        'ˣ' to 'x', 'ʸ' to 'y', 'ᶻ' to 'z'
    )

    internal fun isSubChar(c: Char): Boolean = SUB_BASE.containsKey(c)

    internal fun isSupChar(c: Char): Boolean = SUP_BASE.containsKey(c)

    internal fun baseChar(c: Char): Char = SUB_BASE[c] ?: SUP_BASE[c] ?: c

    /**
     * A character that proves a run is mathematical: sub/superscripts, Greek,
     * arrows, operators, the fraction slash and vulgar fractions.
     */
    private fun isMathIndicator(c: Char): Boolean {
        if (isSubChar(c) || isSupChar(c)) {
            return true
        }
        if (c == FRACTION_SLASH) {
            return true
        }
        return when (c) {
            in 'Ͱ'..'Ͽ' -> true // Greek and Coptic
            in '℀'..'⅏' -> true // Letterlike symbols
            in '←'..'⇿' -> true // Arrows
            in '∀'..'⋿' -> true // Mathematical operators
            in '⟀'..'⟯' -> true // Misc mathematical symbols-A
            '×', '÷', '±', '·', '°', '′', '″', '½', '¼', '¾', '⅓', '⅔',
            '⅕', '⅖', '⅗', '⅘', '⅙', '⅚', '⅐', '⅛', '⅜', '⅝', '⅞', '⅑', '⅒' -> true
            else -> false
        }
    }

    /**
     * Characters a formula region may extend over: Latin letters and digits,
     * the indicators themselves, and the punctuation formulas are made of.
     * Anything else (Persian/Arabic script included) ends the region.
     */
    private fun isFormulaChar(c: Char): Boolean {
        if (isMathIndicator(c)) {
            return true
        }
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9') {
            return true
        }
        return when (c) {
            ' ', '\t', '(', ')', '[', ']', '{', '}', '.', ',', ';', ':',
            '!', '?', '+', '-', '=', '–', '—', '*', '/', '^', '_', '\'',
            '"', '~', '|', '@', '#', '$', '%', '&', '…', '·', '×', '÷' -> true
            else -> false
        }
    }

    /** A chemical token with at least one digit: CH4, H2O, O2, CaCO3, 2H2O. */
    private val CHEM_TOKEN =
        Regex("(?:\\d+\\s*)?[A-Z][a-z]?\\d+(?:[A-Z][a-z]?\\d*)*")

    /**
     * One element symbol glued to its count: the H2 in H2O, the O2 in CO2,
     * the C6/H12/O6 in C6H12O6. Fires outside chemistry lines too, so a lone
     * "H2O" in prose still subscripts — while test123, v1.1.0 and A/B-test
     * style labels (no uppercase-led element) stay untouched.
     */
    internal val CHEM_FORMULA = Regex("([A-Z][a-z]?)([0-9]+)")

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    /**
     * Returns [source] with formula typography applied. Never throws: on any
     * failure the input is returned as an untouched copy.
     */
    fun beautify(source: Spanned): SpannableStringBuilder {
        val text = source.toString()
        val n = text.length
        if (n == 0) {
            return SpannableStringBuilder(source)
        }
        return try {
            beautifyOrThrow(source, text, n)
        } catch (e: Exception) {
            SpannableStringBuilder(source)
        }
    }

    private class SpanReq(
        val span: Any,
        var start: Int,
        var end: Int
    )

    private fun beautifyOrThrow(source: Spanned, text: String, n: Int): SpannableStringBuilder {
        // Code is verbatim: shield everything under a monospace span (inline
        // code) — block code never reaches here (it bypasses toSpanned).
        val protected = BooleanArray(n)
        val monoSpans = source.getSpans(0, n, TypefaceSpan::class.java)
        for (span in monoSpans) {
            if (span.family == "monospace") {
                val s = maxOf(0, source.getSpanStart(span))
                val e = minOf(n, source.getSpanEnd(span))
                for (i in s until e) {
                    protected[i] = true
                }
            }
        }

        // Chemistry lines: whole-line formula regions, recognised before the
        // main pass so the pass can transform arrows and digit runs inline.
        val chemLine = BooleanArray(n)
        // Formula regions in ORIGINAL coordinates; mapped after the pass.
        val regions = ArrayList<IntRange>()
        markChemLines(text, protected, chemLine, n, regions)
        // Lone formulas ("H2O", "C6H12O6") outside chemistry lines: their
        // digit runs subscript, the whole token takes the serif face.
        val tokenDigits = BooleanArray(n)
        findChemTokens(text, protected, chemLine, n, regions, tokenDigits)

        val out = SpannableStringBuilder()
        // newIndex[i] = output offset for original offset i.
        val newIndex = IntArray(n + 1)
        val spanReqs = ArrayList<SpanReq>()
        var lastFracEnd = -1

        var i = 0
        while (i < n) {
            newIndex[i] = out.length
            val c = text[i]
            if (protected[i]) {
                out.append(c)
                i++
                continue
            }
            // Plain-text chemistry: 2O2, not 2O₂.
            if (chemLine[i] && c == '<' && text.startsWith("<->", i)) {
                out.append('↔')
                i += 3
                continue
            }
            if (chemLine[i] && c == '-' && i + 1 < n && text[i + 1] == '>') {
                out.append('→')
                i += 2
                continue
            }
            if (chemLine[i] && c == '<' && i + 1 < n && text[i + 1] == '-') {
                out.append('←')
                i += 2
                continue
            }
            if (chemLine[i] && c == '*' && i > 0 && i + 1 < n &&
                text[i - 1] == ' ' && text[i + 1] == ' '
            ) {
                out.append('×')
                i++
                continue
            }
            // Chemistry digit runs: the 4 in CH4, never the 2 in 2H2O and
            // never a version fragment like the 1 in 1.1.0 (a dot follows those).
            // Outside chemistry lines the element-token pass (findChemTokens)
            // marks the same digits via tokenDigits.
            if ((chemLine[i] || tokenDigits[i]) && c in '0'..'9' && i > 0 &&
                (text[i - 1] in 'A'..'Z' || text[i - 1] in 'a'..'z' ||
                        text[i - 1] == ')' || text[i - 1] == ']') &&
                (i + 1 >= n || (text[i + 1] != '.' && text[i + 1] != ':'))
            ) {
                val base = c
                val pos = out.length
                out.append(base)
                spanReqs.add(SpanReq(SubscriptSpan(), pos, pos + 1))
                spanReqs.add(SpanReq(RelativeSizeSpan(0.72f), pos, pos + 1))
                i++
                continue
            }
            // Raw ^x / _x in chemistry lines.
            if (chemLine[i] && (c == '^' || c == '_') && i + 1 < n &&
                isFormulaChar(text[i + 1]) && text[i + 1] !in '0'..'9'
            ) {
                val isSuper = c == '^'
                var j = i + 1
                while (j < n && !protected[j] && text[j] in 'A'..'z' &&
                    text[j] != '^' && text[j] != '_'
                ) {
                    j++
                }
                val pos = out.length
                out.append(text, i + 1, j)
                val span: Any = if (isSuper) SuperscriptSpan() else SubscriptSpan()
                spanReqs.add(SpanReq(span, pos, out.length))
                spanReqs.add(SpanReq(RelativeSizeSpan(0.72f), pos, out.length))
                i = j
                continue
            }
            if (isSubChar(c) || isSupChar(c)) {
                val pos = out.length
                out.append(baseChar(c))
                spanReqs.add(
                    SpanReq(
                        if (isSubChar(c)) SubscriptSpan() else SuperscriptSpan(),
                        pos, pos + 1
                    )
                )
                spanReqs.add(SpanReq(RelativeSizeSpan(0.72f), pos, pos + 1))
                i++
                continue
            }
            if (c == FRACTION_SLASH) {
                val frac = parseFraction(text, protected, n, i, lastFracEnd)
                if (frac != null) {
                    // The numerator was already emitted as plain text while
                    // scanning up to the slash — retract it (and any spans it
                    // earned) before laying the stacked fraction down.
                    val numOutStart = newIndex[frac.third]
                    out.delete(numOutStart, out.length)
                    spanReqs.removeAll { it.end > numOutStart }
                    // The retracted original offsets (numerator interior and
                    // the slash) now all land on the placeholder.
                    for (k in frac.third..i) {
                        newIndex[k] = numOutStart
                    }
                    val pos = out.length
                    out.append(FRACTION_PLACEHOLDER)
                    spanReqs.add(
                        SpanReq(
                            StackedFractionSpan(frac.first, frac.second, typeface()),
                            pos, pos + 1
                        )
                    )
                    regions.add(IntRange(frac.third, frac.fourth - 1))
                    lastFracEnd = frac.fourth
                    i = frac.fourth
                    continue
                }
                out.append(c)
                i++
                continue
            }
            out.append(c)
            i++
        }
        newIndex[n] = out.length
        // Consumed runs ("->" -> "→") never wrote their interior offsets;
        // fill them forward so region mapping stays total.
        for (j in 1..n) {
            if (newIndex[j] == 0) {
                newIndex[j] = newIndex[j - 1]
            }
        }

        // Formula regions: expand indicator runs over formula characters.
        findRegions(text, protected, n, regions)
        val tf = typeface()
        val outLen = out.length
        for (region in regions) {
            val s = newIndex[region.first.coerceIn(0, n)]
            val e = newIndex[(region.last + 1).coerceIn(0, n)]
            if (s < e && s < outLen && e <= outLen) {
                spanReqs.add(SpanReq(MathFontSpan(tf), s, e))
            }
        }
        // The converted sub/superscript glyphs take the serif face too
        // (regions already cover most; this closes the gaps).
        val scriptReqs = ArrayList<SpanReq>()
        for (req in spanReqs) {
            if (req.span is SubscriptSpan || req.span is SuperscriptSpan) {
                scriptReqs.add(SpanReq(MathFontSpan(tf), req.start, req.end))
            }
        }
        spanReqs.addAll(scriptReqs)

        // Carry the renderer's own spans (bold, links, …) across the edit.
        val existing = source.getSpans(0, n, Any::class.java)
        for (span in existing) {
            val s0 = newIndex[source.getSpanStart(span).coerceIn(0, n)]
            val s1 = newIndex[source.getSpanEnd(span).coerceIn(0, n)]
            val s = minOf(s0, s1)
            val e = maxOf(s0, s1)
            if (s <= e && e <= outLen) {
                out.setSpan(span, s, e, source.getSpanFlags(span))
            }
        }
        for (req in spanReqs) {
            val s = req.start.coerceIn(0, outLen)
            val e = req.end.coerceIn(0, outLen)
            if (s < e) {
                out.setSpan(req.span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    /**
     * Marks whole lines that read as chemistry: a reaction arrow, or at least
     * two chemical tokens joined by `+`. Only unprotected characters count, so
     * `a -> b` inside code never triggers. Matching lines are added to
     * [regions] as whole-line formula regions (IntRange, inclusive).
     */
    private fun markChemLines(
        text: String,
        protected: BooleanArray,
        chemLine: BooleanArray,
        n: Int,
        regions: ArrayList<IntRange>
    ) {
        var lineStart = 0
        var i = 0
        while (i <= n) {
            if (i == n || text[i] == '\n') {
                if (i > lineStart) {
                    val sb = StringBuilder()
                    for (k in lineStart until i) {
                        sb.append(if (protected[k]) ' ' else text[k])
                    }
                    if (isChemLine(sb.toString())) {
                        for (k in lineStart until i) {
                            chemLine[k] = true
                        }
                        regions.add(IntRange(lineStart, i - 1))
                    }
                }
                lineStart = i + 1
            }
            i++
        }
    }

    internal fun isChemLine(line: String): Boolean {
        if (line.contains("->") || line.contains("→") ||
            line.contains("<-") || line.contains("←") || line.contains("<->")
        ) {
            return true
        }
        if (!line.contains("+")) {
            return false
        }
        var count = 0
        for (m in CHEM_TOKEN.findAll(line)) {
            count++
            if (count >= 2) {
                return true
            }
        }
        return false
    }

    /**
     * Finds lone chemical formulas outside chemistry lines — the H2 in
     * "Water is H2O", the C6/H12/O6 in "glucose is C6H12O6". Their digit
     * runs are marked in [tokenDigits] for subscripting by the main pass,
     * and the whole token joins [regions] for the serif face. Code spans
     * ([protected]), chemistry lines (already handled) and version-like
     * fragments ("H2.0", "1.1.0") are skipped.
     */
    private fun findChemTokens(
        text: String,
        protected: BooleanArray,
        chemLine: BooleanArray,
        n: Int,
        regions: ArrayList<IntRange>,
        tokenDigits: BooleanArray
    ) {
        for (m in CHEM_FORMULA.findAll(text)) {
            val digits = m.groups[2] ?: continue
            val ds = digits.range.first
            val de = digits.range.last + 1
            if (de < n && (text[de] == '.' || text[de] == ':')) {
                continue
            }
            var ok = true
            for (k in m.range) {
                if (protected[k] || chemLine[k]) {
                    ok = false
                    break
                }
            }
            if (!ok) {
                continue
            }
            // The serif region extends back over the element letters, so
            // "CH4" matches at "H4" but the whole token takes the face.
            var ts = m.range.first
            while (ts > 0 && !protected[ts - 1] && !chemLine[ts - 1] &&
                text[ts - 1] in 'A'..'z'
            ) {
                ts--
            }
            regions.add(IntRange(ts, de - 1))
            for (k in ds until de) {
                tokenDigits[k] = true
            }
        }
    }

    /**
     * Parses `numerator⁄denominator` around [slashAt]. Returns
     * (num, den, numStart, denEnd) or null when either side is empty.
     */
    internal fun parseFraction(
        text: String,
        protected: BooleanArray,
        n: Int,
        slashAt: Int,
        lastFracEnd: Int
    ): Quad<String, String, Int, Int>? {
        var s = slashAt - 1
        while (s >= 0 && s > lastFracEnd && !protected[s] && isFractionChar(text[s])) {
            s--
        }
        s++
        var e = slashAt + 1
        while (e < n && !protected[e] && isFractionChar(text[e])) {
            e++
        }
        var numStart = s
        var denEnd = e
        while (numStart < slashAt && text[numStart] == ' ') {
            numStart++
        }
        while (denEnd > slashAt + 1 && text[denEnd - 1] == ' ') {
            denEnd--
        }
        if (numStart >= slashAt || slashAt + 1 >= denEnd) {
            return null
        }
        if (slashAt - numStart + denEnd - slashAt > 80) {
            return null
        }
        val num = text.substring(numStart, slashAt).trimJava()
        val den = text.substring(slashAt + 1, denEnd).trimJava()
        if (num.isBlankJava() || den.isBlankJava()) {
            return null
        }
        return Quad(num, den, numStart, denEnd)
    }

    private fun isFractionChar(c: Char): Boolean {
        if (isSubChar(c) || isSupChar(c)) {
            return true
        }
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9') {
            return true
        }
        return c == ' ' || c == '.' || c == '(' || c == ')'
    }

    /**
     * Finds formula regions in ORIGINAL coordinates and appends them to
     * [regions]: maximal runs of formula characters containing at least one
     * math indicator.
     */
    private fun findRegions(
        text: String,
        protected: BooleanArray,
        n: Int,
        regions: ArrayList<IntRange>
    ) {
        var i = 0
        while (i < n) {
            if (protected[i] || !isMathIndicator(text[i])) {
                i++
                continue
            }
            var s = i
            while (s > 0 && !protected[s - 1] && isFormulaChar(text[s - 1])) {
                s--
            }
            var e = i
            while (e + 1 < n && !protected[e + 1] && isFormulaChar(text[e + 1])) {
                e++
            }
            // Do not swallow a whole sentence through its spaces: trim the
            // region to the indicator cluster plus adjacent formula tokens.
            regions.add(IntRange(s, e))
            i = e + 1
        }
        // Merge overlaps (adjacent indicators share one region).
        if (regions.size > 1) {
            regions.sortBy { it.first }
            val merged = ArrayList<IntRange>()
            var cur = regions[0]
            for (k in 1 until regions.size) {
                val r = regions[k]
                if (r.first <= cur.last + 1) {
                    cur = IntRange(cur.first, maxOf(cur.last, r.last))
                } else {
                    merged.add(cur)
                    cur = r
                }
            }
            merged.add(cur)
            regions.clear()
            regions.addAll(merged)
        }
    }

    internal class Quad<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )
}
