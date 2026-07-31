package com.vepro.code

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The live activity strip: what the agent is doing, while it does it.
 *
 * ### Shape
 *
 * ```
 *  ⠿  Checking the current dollar rate · 9s   ›
 *  ⌕  Searching                    ◍◍◍  5 results
 *     usd to irr exchange rate
 *  ⊕  Opened page
 *     tgju.org
 * ```
 *
 * and once the answer starts, the whole thing folds to one line plus a summary of
 * what the run actually cost:
 *
 * ```
 *  ◌  Thought for 14s  ›
 *     4 steps · 2 files · +31 −6 · 32 pages
 * ```
 *
 * ### Why it is built this way
 *
 * No card, no border, no fill — the strip is *transparent*, sitting directly on
 * the conversation background, because it is a narration of the run rather than
 * an object in the transcript.
 *
 * Everything is DERIVED from the [Trail] on [bind]: every row is a pure function
 * of its step, and nothing about a row is ever patched in place from an event.
 * That is a deliberate choice over hand-written diffing, because a view that is a
 * pure function of its model can never drift out of step with it — which is
 * exactly what a half-updated progress display does when a run is cancelled at
 * the wrong moment.
 *
 * What has changed is that deriving a row no longer means BUILDING one. Each row
 * carries a signature of everything that was read to make it, and a repaint
 * rebuilds only the rows whose signature moved; see [syncRows]. Three concurrent
 * sub-agents publish several trail changes a second between them and almost all
 * of them touch one step, so tearing the list down each time was throwing away
 * three inflated rows to replace one — noticeable on the 2016 hardware minSdk 23
 * puts back in scope.
 *
 * The only stateful parts are the elapsed-seconds counter, which ticks on its own
 * so a live run's timer advances without the engine having to emit an event per
 * second, and the caches that keep a repaint from redoing work that has not
 * changed.
 */
class TrailView(context: Context) : LinearLayout(context) {

    private val header = LinearLayout(context)
    private val glyph = PulseGlyph(context)
    private val title = TextView(context)
    private val timer = TextView(context)
    private val chevron = ImageView(context)
    private val rows = LinearLayout(context)

    private val summary = TextView(context)

    private var trail: Trail? = null
    private var ticking = false

    /**
     * Whether the rows are showing.
     *
     * Read from and written back to [Trail.collapsed], which was already persisted
     * with the conversation and already set by the engine when the answer starts —
     * and which no view ever read. Expansion was therefore view-local state that
     * reset on every rebuild: rotate the phone, or let one more trail event arrive,
     * and a strip you had just opened closed itself again.
     */
    private var expanded: Boolean
        get() = trail?.collapsed == false
        set(value) {
            trail?.collapsed = !value
        }

    /**
     * Opens the full Thoughts panel. Set by the Activity, because a sheet needs an
     * Activity context and a view must not assume it has one.
     *
     * The strip used to expand in place, which put a growing list of rows in the
     * middle of the transcript and pushed the answer around while it was being
     * read. A panel is the right shape for "show me everything": it is as tall as
     * it needs to be, it scrolls, and closing it puts the conversation back exactly
     * as it was.
     */
    var onOpenPanel: (() -> Unit)? = null

    /** Opens the results behind one activity row. */
    var onOpenStep: ((TrailStep) -> Unit)? = null

    /**
     * Opens the tool's own words behind a step that did not succeed.
     *
     * Deliberately a SECOND callback rather than a wider [onOpenStep]. The two are
     * different sheets over different data — one is a list of web results, the
     * other is a wall of diagnostic text — and folding them into one hook would put
     * the choice of which to show inside the Activity, where it would have to
     * re-derive from the step what this view already knows.
     *
     * A row offers at most one of them; see [buildRow].
     */
    var onOpenFailure: ((TrailStep) -> Unit)? = null

    /**
     * The layout direction the chrome was last built for, or -1 before the first
     * pass. Compared in [applyDirection] so a language flip re-flips the strip and
     * an ordinary repaint does not touch the direction at all.
     */
    private var chromeDirection = -1

    /** What [paintTimer] last wrote, and the palette it wrote it in. */
    private var timerText = ""
    private var timerRevision = -1

    /** What the chevron last drew, and the palette it drew it in. */
    private var chevronName = ""
    private var chevronRevision = -1

    /** Re-reads the timer every ~250ms while the run is live. */
    private val tick = object : Runnable {
        override fun run() {
            ticking = false
            val live = trail?.running == true
            if (!live) {
                return
            }
            paintTimer()
            schedule()
        }
    }

    init {
        orientation = VERTICAL
        val pad = Theme.dp(context, Ui.Space.XS)
        setPadding(0, pad, 0, pad)

        header.orientation = HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.isClickable = true
        header.background = Theme.rippleTransparent(Theme.R_MD, context)
        val headerPad = Theme.dp(context, Ui.Space.XS)
        header.setPadding(headerPad, headerPad, headerPad, headerPad)

        val glyphSize = Theme.dp(context, GLYPH_DP)
        header.addView(glyph, LayoutParams(glyphSize, glyphSize))

        title.textSize = Ui.Type.BODY
        title.typeface = Theme.uiMedium()
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        val titleLp = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        titleLp.marginStart = Theme.dp(context, Ui.Space.M)
        header.addView(title, titleLp)

        timer.textSize = Ui.Type.LABEL
        timer.typeface = Theme.ui()
        timer.maxLines = 1
        val timerLp = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        timerLp.marginStart = Theme.dp(context, Ui.Space.S)
        header.addView(timer, timerLp)

        val chevronSize = Theme.dp(context, CHEVRON_DP)
        val chevronLp = LayoutParams(chevronSize, chevronSize)
        chevronLp.marginStart = Theme.dp(context, Ui.Space.XS)
        header.addView(chevron, chevronLp)

        addView(
            header,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // The one-line cost of the run, shown when the rows are folded away. It is
        // what makes a collapsed strip still worth having on screen: "Thought for
        // 14s" alone says nothing about what happened, and opening a panel to find
        // out is a tap the answer does not deserve to cost.
        summary.textSize = Ui.Type.LABEL
        summary.typeface = Theme.ui()
        summary.setTextColor(Theme.TEXT_FAINT)
        summary.maxLines = 1
        summary.ellipsize = TextUtils.TruncateAt.END
        // FIRST_STRONG, not the interface direction. The digest is assembled from
        // counts and units, and a run that only changed lines produces `+31 −6` —
        // no strong character anywhere in it. Letting the paragraph resolve from
        // its own content is the honest rule for a line whose content varies.
        summary.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        summary.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        val summaryLp = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // marginStart, so it mirrors: the digest hangs under the TITLE, which is
        // inset by the glyph, the title's own gap and the header's padding. In
        // Persian all three of those move to the right edge and so does this.
        summaryLp.marginStart = Theme.dp(context, GLYPH_DP + Ui.Space.M + Ui.Space.XS)
        summaryLp.topMargin = Theme.dp(context, 1.0f)
        addView(summary, summaryLp)

        rows.orientation = VERTICAL
        val rowsLp = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowsLp.topMargin = Theme.dp(context, Ui.Space.XS)
        addView(rows, rowsLp)

        header.setOnClickListener {
            Ui.tick(this)
            val opener = onOpenPanel
            if (opener != null) {
                opener()
            } else {
                // No panel wired (a preview, a test): fall back to expanding.
                expanded = !expanded
                paint()
            }
        }
        // The chevron is its own target, and its only job is the inline rows — so a
        // live run really can be made quiet without losing the way into the panel.
        //
        // It could not, before: `paint` computed `live || expanded`, which pins the
        // rows open for the whole of a live run and reduces the chevron to switching
        // between the last three rows and all of them. The glyph then always drew
        // downwards while live, so it also lied about which way it would go.
        chevron.isClickable = true
        chevron.background = Theme.rippleTransparent(Theme.R_PILL, context)
        chevron.setOnClickListener {
            expanded = !expanded
            Ui.tick(this)
            paint()
        }

        applyDirection()
    }

    /**
     * Points the strip at the interface's own reading direction.
     *
     * Set on the strip AND on its two containers rather than left to inherit: a
     * strip is added to whatever row of the transcript owns the run, and a
     * container that has not been flipped yet would resolve its children LTR for
     * one frame — which in Persian is a visible jump of the whole row.
     *
     * Guarded on [chromeDirection] because this is reached from [paint], which
     * runs on every trail publish, and because the language really can change
     * under a live strip: `Fa.apply` drops `Lang`'s cache and the next publish
     * repaints. Everything below is a no-op when nothing flipped.
     *
     * Only CHROME is set here. The digest and the step rows hold text somebody
     * else wrote and decide their own direction from it — see [buildRow].
     */
    private fun applyDirection() {
        val direction = Lang.direction(context)
        if (direction == chromeDirection) {
            return
        }
        chromeDirection = direction
        layoutDirection = direction
        header.layoutDirection = direction
        rows.layoutDirection = direction
        // The heading and the timer are always in the interface's language, so
        // they take the interface's direction rather than guessing from content.
        val text = Lang.textDirection(context)
        title.textDirection = text
        timer.textDirection = text
        // The already-built rows are stale too — each one pins its own direction —
        // but they are not torn down here: the direction is part of a row's
        // signature, so [syncRows] rebuilds exactly the ones that need it and one
        // mechanism decides row freshness rather than two.
    }

    /**
     * Points this view at [value] and repaints.
     *
     * Safe to call as often as the engine likes: it is idempotent, it never
     * animates the rows (a list that reflows while you read it is worse than one
     * that appears), and it starts or stops the timer to match the run.
     */
    fun bind(value: Trail?) {
        trail = value
        if (value == null) {
            visibility = GONE
            stop()
            return
        }
        visibility = if (value.isEmpty()) GONE else VISIBLE
        paint()
        if (value.running) {
            glyph.start()
            schedule()
        } else {
            glyph.stop()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Belt and braces. Every teardown path already calls detach(), but a view
        // that leaves the window with a 250ms self-reposting timer still queued is
        // one refactor away from being a leak, and the cost of being sure is two
        // lines.
        detach()
    }

    /** Releases the timer and the glyph animation. */
    fun detach() {
        stop()
        glyph.stop()
    }

    private fun stop() {
        removeCallbacks(tick)
        ticking = false
    }

    private fun schedule() {
        val live = trail?.running == true
        if (ticking || !live) {
            return
        }
        ticking = true
        postDelayed(tick, TIMER_MS)
    }

    // ---- painting ----------------------------------------------------------

    private fun paint() {
        val value = trail ?: return
        val live = value.running
        // One rule, both states: the rows show when the strip is expanded. The
        // engine expands it at the start of a run and folds it when the answer
        // begins, so the default behaviour is unchanged — but the chevron now
        // actually works mid-run, which is what it looked like it did all along.
        val showRows = expanded

        applyDirection()

        glyph.visibility = VISIBLE
        glyph.setSummary(!live && !showRows)

        // The heading is FIXED. It says what this row is — "Reviewing request"
        // while the model works, and how long it took once it is done — and
        // nothing else can put words here.
        //
        // It used to render `value.phase`, which the engine filled from the first
        // line of the model's own preamble. So the heading of the review section
        // became whatever the model happened to open with, changing sentence to
        // sentence and turn to turn: the one label on the screen that should be a
        // fixed landmark was the least stable text in the app. The model's prose
        // is still kept — it goes to the step rows inside the panel, where a
        // sentence about what is being done belongs — but it no longer reaches
        // this line.
        Ui.swapText(
            title,
            if (live) Fa.TRAIL_THINKING else Fa.TRAIL_THOUGHT_FOR.format(seconds(value))
        )
        title.setTextColor(if (live) Theme.TEXT else Theme.TEXT_MUTED)
        paintTimer()

        // "Open" points DOWN in both languages; "go on to the panel" points at the
        // end edge, which is left in Persian — so only the second one is asked for
        // by direction. Rebuilt only when the glyph or the palette actually
        // changed: parsing and tinting a path on every publish is pure waste when
        // the answer is the same drawable four times a second.
        val chevronWanted = if (showRows) "chevron-down" else Lang.chevronForward(context)
        if (chevronWanted != chevronName || chevronRevision != Theme.revision) {
            chevronName = chevronWanted
            chevronRevision = Theme.revision
            chevron.setImageDrawable(Icons.of(chevronWanted, Theme.TEXT_FAINT))
        }

        // Built only when it is going to be read. The digest walks every step in
        // the trail four times over — steps, files, totals, pages — and while the
        // rows are open nothing is done with the answer.
        if (showRows) {
            summary.visibility = GONE
        } else {
            val digest = digest(value)
            if (digest.isEmpty()) {
                summary.visibility = GONE
            } else {
                Ui.swapText(summary, digest)
                summary.visibility = VISIBLE
            }
        }

        if (showRows) {
            // A live strip is a window on the newest work, not a log: the tail is
            // what tells you where the run has got to, and an ever-growing list
            // would push the answer off the screen. Once the run is over the whole
            // thing is available, because then nothing is moving.
            val all = value.steps()
            val from = if (live) Math.max(0, all.size - LIVE_ROWS) else 0
            syncRows(all, from)
        } else if (rows.childCount > 0) {
            rows.removeAllViews()
        }
        rows.visibility = if (rows.childCount == 0) GONE else VISIBLE
    }

    /**
     * Brings [rows] into line with `all[from ..]`, keeping every child that is
     * already correct.
     *
     * This used to be `removeAllViews()` and a fresh [buildRow] per step, on the
     * reasoning that a trail is small and a view that is a pure function of its
     * model can never drift. The model half of that is still true and is why the
     * rows are still derived, not diffed by hand — but the cost half stopped being
     * true twice over. Three concurrent sub-agents publish a trail change several
     * times a second between them, and almost every one of those is a progress
     * tick that rewrites ONE step's detail; and minSdk 23 puts 2016 hardware back
     * in scope, where tearing down and re-inflating three nested rows with six
     * text views and three tinted drawables per publish is real jank.
     *
     * So each row carries a [signature] of everything [buildRow] read out of its
     * step, plus the palette and the direction it was built in. A row whose
     * signature still matches is left completely alone; anything else is replaced
     * in place. In the common case — a detail tick on the newest step — that is
     * two rows untouched and one rebuilt instead of three rebuilt.
     *
     * The windowing above is untouched: this is handed exactly the slice
     * [LIVE_ROWS] chose, and it renders exactly that slice.
     */
    private fun syncRows(all: List<TrailStep>, from: Int) {
        val want = all.size - from
        // Drop from the tail first, so the indices below stay meaningful.
        while (rows.childCount > want) {
            rows.removeView(rows.getChildAt(rows.childCount - 1))
        }
        val mirrored = Lang.mirrored(context)
        val revision = Theme.revision
        for (i in 0 until want) {
            val step = all[from + i]
            val key = signature(step, mirrored, revision)
            val current = if (i < rows.childCount) rows.getChildAt(i) else null
            if (current != null && key == current.tag) {
                continue
            }
            val fresh = buildRow(step)
            fresh.tag = key
            if (current == null) {
                rows.addView(fresh)
            } else {
                rows.removeView(current)
                rows.addView(fresh, i)
            }
        }
    }

    /**
     * Everything [buildRow] reads, in one comparable string.
     *
     * The step's IDENTITY leads it, because the window SLIDES: index 1 becomes
     * index 0 as soon as a step is appended, and two steps are perfectly capable
     * of being indistinguishable by content — two reasoning rows, two reads of the
     * same file a millisecond apart. Keying on content alone would let a row be
     * reused for a different step and keep a tap handler closed over the one that
     * used to be at that index, which is a wrong sheet opening on a row whose
     * whole purpose is to open the right one.
     *
     * The palette and the direction are in here for a different reason: neither is
     * a property of the step, and both change what was drawn.
     */
    private fun signature(step: TrailStep, mirrored: Boolean, revision: Int): String {
        val sb = StringBuilder(96)
        sb.append(System.identityHashCode(step)).append(SEP)
        sb.append(step.startedAt).append(SEP)
        sb.append(step.endedAt).append(SEP)
        sb.append(step.status).append(SEP)
        sb.append(step.kind).append(SEP)
        sb.append(step.label).append(SEP)
        sb.append(step.detail).append(SEP)
        sb.append(step.reason).append(SEP)
        sb.append(step.resultCount).append(SEP)
        sb.append(step.added).append(SEP)
        sb.append(step.removed).append(SEP)
        sb.append(step.hasDiff()).append(SEP)
        sb.append(step.hasResults()).append(SEP)
        // Presence, not content: the text itself only ever reaches the sheet.
        sb.append(step.output.isNotBlankJava()).append(SEP)
        for (host in step.domains()) {
            sb.append(host).append(',')
        }
        sb.append(SEP).append(mirrored).append(SEP).append(revision)
        return sb.toString()
    }

    /**
     * The collapsed one-liner: steps, files touched, lines changed, pages read.
     *
     * Only the parts that happened. A run that read three web pages and changed
     * nothing should not display "+0 −0" — a zero is a fact the eye still has to
     * process, and there are enough of them in a summary to make it useless.
     */
    private fun digest(value: Trail): String {
        val parts = ArrayList<String>(4)
        val steps = value.workCount()
        if (steps > 0) {
            parts.add(Fa.TRAIL_STEPS.format(Lang.num(context, steps)))
        }
        val files = value.editedFiles().size
        if (files > 0) {
            parts.add(Fa.TRAIL_EDITED.format(Lang.num(context, files)))
        }
        val totals = value.changeTotals()
        if (totals[0] > 0 || totals[1] > 0) {
            parts.add(
                "+" + Lang.num(context, totals[0]) + " \u2212" + Lang.num(context, totals[1])
            )
        }
        val pages = value.pages().size
        if (pages > 0) {
            parts.add(Lang.num(context, pages) + " " + Fa.TRAIL_PAGES)
        }
        val failed = value.failedCount()
        if (failed > 0) {
            parts.add(Lang.num(context, failed) + " " + Fa.TRAIL_FAILED)
        }
        return parts.joinToString("  \u00b7  ")
    }

    private fun paintTimer() {
        val value = trail ?: return
        if (!value.running) {
            timer.visibility = GONE
            return
        }
        timer.visibility = VISIBLE
        // Four times a second, for a number that changes once. Writing the same
        // text back costs a measure pass and a redraw of the header on every tick,
        // which is exactly the sort of idle cost that shows up on a 2016 phone as a
        // strip that will not settle. The seconds are already through Lang.num.
        val next = "· " + Fa.TRAIL_SECONDS.format(seconds(value))
        if (next == timerText && timerRevision == Theme.revision) {
            return
        }
        timerText = next
        timerRevision = Theme.revision
        timer.text = next
        timer.setTextColor(Theme.TEXT_FAINT)
    }

    private fun seconds(value: Trail): String {
        val total = value.elapsedMs(System.currentTimeMillis()) / 1000L
        return Lang.num(context, total)
    }

    /**
     * One activity row: glyph, label, the detail under it, why it went wrong, and
     * its outcome.
     */
    private fun buildRow(step: TrailStep): View {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.gravity = Gravity.TOP
        // Pinned rather than inherited: a row is built before it is added, and the
        // strip it is added to may be flipping in the same pass.
        row.layoutDirection = Lang.direction(context)
        val vertical = Theme.dp(context, Ui.Space.S)
        // RELATIVE. The leading inset is 4dp and the trailing one is 0, and
        // setPadding's first argument is the physical LEFT — so in Persian this row
        // was inset on the wrong side and sat flush against the edge the eye starts
        // from. Symmetric padding elsewhere in this file is left alone; it cannot
        // be wrong either way.
        row.setPaddingRelative(Theme.dp(context, Ui.Space.XS), vertical, 0, vertical)

        // What a tap is FOR. A search opens its results; a step that broke or was
        // cut short opens the tool's own words. Never both, and a row that has
        // neither stays inert rather than offering an empty sheet.
        val opensResults = step.hasResults()
        val opensFailure = !opensResults && unexplained(step) && step.output.isNotBlankJava()
        if (opensResults || opensFailure) {
            row.isClickable = true
            row.background = Theme.rippleTransparent(Theme.R_SM, context)
            row.setOnClickListener {
                Ui.tick(row)
                if (opensResults) {
                    onOpenStep?.invoke(step)
                } else {
                    onOpenFailure?.invoke(step)
                }
            }
        }

        val iconSize = Theme.dp(context, ROW_ICON_DP)
        val icon = ImageView(context)
        icon.setImageDrawable(
            Icons.of(
                iconFor(step),
                if (step.status == TrailStep.DONE || step.status == TrailStep.RUNNING) {
                    Theme.TEXT_MUTED
                } else {
                    Theme.TEXT_FAINT
                },
                Ui.STROKE
            )
        )
        val iconLp = LayoutParams(iconSize, iconSize)
        // Optically centres the glyph against the label's cap height instead of
        // its line box, which is what stops it reading as floating high.
        iconLp.topMargin = Theme.dp(context, 1.0f)
        row.addView(icon, iconLp)

        val column = LinearLayout(context)
        column.orientation = VERTICAL
        val columnLp = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        columnLp.marginStart = Theme.dp(context, Ui.Space.M)
        row.addView(column, columnLp)

        if (step.kind != TrailStep.THINK && step.kind != TrailStep.NOTE) {
            val label = TextView(context)
            label.text = step.label
            label.textSize = Ui.Type.BODY
            label.typeface = Theme.ui()
            label.setTextColor(Theme.TEXT_MUTED)
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            // A label is always one of the app's own strings, so it takes the
            // app's own direction rather than sniffing its first character.
            label.textDirection = Lang.textDirection(context)
            column.addView(label)
        }

        if (step.detail.isNotBlankJava()) {
            val detail = TextView(context)
            detail.text = step.detail
            detail.textSize = Ui.Type.LABEL
            // Italic, exactly as in the reference: it marks the text as a QUOTED
            // query rather than as more of the app's own voice.
            detail.setTypeface(Theme.ui(), Typeface.ITALIC)
            detail.setTextColor(Theme.TEXT_FAINT)
            detail.maxLines = 2
            detail.ellipsize = TextUtils.TruncateAt.END
            proseDirection(detail)
            val detailLp = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            detailLp.topMargin = Theme.dp(context, 2.0f)
            column.addView(detail, detailLp)
        }

        // WHY it failed, under WHAT it was doing.
        //
        // This is the fix for the app's most-reported bug, and where it sits is the
        // whole of the fix. It is a THIRD line rather than a replacement for the
        // detail, because the detail is the path and the path is still the answer
        // to "which file" — overwriting it would trade one missing fact for
        // another. It comes after the detail because the row then reads in the
        // order the run happened: what was tried, on what, and how it went; the
        // outcome mark at the end of the row is part of that same last beat.
        //
        // It is set UPRIGHT against the detail's italic and one step brighter than
        // it. The italic above marks a quoted value — a query the model wrote, a
        // path it chose — and this is not quoted from anywhere: it is the app
        // reporting a fact, so it is set in the app's own voice and given the
        // precedence it earns. There is no red on it. The outcome mark already
        // says the step failed, and a reason in alarm colours would say it a
        // second time and make one failure look like two.
        if (unexplained(step) && step.hasReason()) {
            val reason = TextView(context)
            reason.text = step.reason
            reason.textSize = Ui.Type.LABEL
            reason.typeface = Theme.ui()
            reason.setTextColor(Theme.TEXT_MUTED)
            reason.maxLines = 2
            reason.ellipsize = TextUtils.TruncateAt.END
            proseDirection(reason)
            val reasonLp = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            reasonLp.topMargin = Theme.dp(context, 2.0f)
            column.addView(reason, reasonLp)
        }

        // ...and that the rest of it is one tap away. A row with no affordance is
        // a row nobody taps, which would leave the full output as unreachable as
        // the reason used to be. Smallest type in the scale and the faintest ink:
        // it is an instruction, not information, and it must not compete with the
        // reason directly above it.
        if (opensFailure) {
            val hint = TextView(context)
            hint.text = Fa.TRAIL_TAP_REASON
            hint.textSize = Ui.Type.MICRO
            hint.typeface = Theme.ui()
            hint.setTextColor(Theme.TEXT_FAINT)
            hint.maxLines = 1
            hint.ellipsize = TextUtils.TruncateAt.END
            hint.textDirection = Lang.textDirection(context)
            val hintLp = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hintLp.topMargin = Theme.dp(context, 3.0f)
            column.addView(hint, hintLp)
        }

        val hosts = step.domains()
        val marked = outcomeWord(step).isNotEmpty()
        if (step.resultCount > 0 || hosts.isNotEmpty() || step.hasChangeCounts() || marked) {
            val trailing = LinearLayout(context)
            trailing.orientation = HORIZONTAL
            trailing.gravity = Gravity.CENTER_VERTICAL
            // The one place this design permits colour, and the reason it does: added
            // and removed are opposites, not two amounts of one thing, so lightness
            // alone cannot say which is which.
            if (step.hasChangeCounts()) {
                trailing.addView(changeCounts(step))
            }
            if (marked) {
                trailing.addView(outcomeMark(step))
            }
            if (hosts.isNotEmpty()) {
                trailing.addView(
                    SourceCluster(context, hosts, CLUSTER_MAX),
                    LayoutParams(
                        SourceCluster.widthFor(context, hosts.size, CLUSTER_MAX),
                        Theme.dp(context, SourceCluster.SIZE_DP)
                    )
                )
            }
            if (step.resultCount > 0) {
                val count = TextView(context)
                count.text = Lang.num(context, step.resultCount) + " " + Fa.TRAIL_RESULTS
                count.textSize = Ui.Type.LABEL
                count.typeface = Theme.ui()
                count.setTextColor(Theme.TEXT_FAINT)
                count.maxLines = 1
                // A count and the app's own word for it: interface direction, and
                // the number is already in the interface's numerals.
                count.textDirection = Lang.textDirection(context)
                val countLp = LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                countLp.marginStart = Theme.dp(context, Ui.Space.S)
                trailing.addView(count, countLp)
            }
            val trailingLp = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            trailingLp.marginStart = Theme.dp(context, Ui.Space.S)
            trailingLp.topMargin = Theme.dp(context, 1.0f)
            row.addView(trailing, trailingLp)
        }
        return row
    }

    /**
     * Lays out a view holding text SOMEBODY ELSE wrote \u2014 the model's query, the
     * tool's diagnosis, a path the agent picked.
     *
     * Two settings, and they are not the same one twice. FIRST_STRONG lets the
     * paragraph resolve from its own first strong character, so a Persian query
     * reads right-to-left on an English screen and an English one reads
     * left-to-right on a Persian screen.
     *
     * TEXT_ALIGNMENT_VIEW_START is the answer to the file-path wrinkle. Nearly
     * every detail and a good share of every reason begins with a path, a
     * `snake_case` argument name or a quoted identifier \u2014 `src/com/...`,
     * `old_string` \u2014 whose first strong character is Latin. FIRST_STRONG therefore
     * (correctly) runs the line left-to-right, but without this it would also
     * ALIGN it left, stranding it against the far edge of a mirrored row with a
     * gap where the eye starts. Pinning the alignment to the view's start edge
     * keeps the box beside the glyph in both languages while the characters inside
     * it still run the only way they can be read.
     */
    private fun proseDirection(view: TextView) {
        view.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        view.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    /**
     * The word a row ends with when "finished" is not the whole story, or empty
     * when it is.
     */
    private fun outcomeWord(step: TrailStep): String = when (step.status) {
        TrailStep.FAILED -> Fa.TRAIL_FAILED
        TrailStep.STOPPED -> Fa.TRAIL_STOPPED
        TrailStep.REJECTED -> Fa.TRAIL_REJECTED
        else -> ""
    }

    /**
     * How the row ends: a glyph for a declined action, then the word.
     *
     * ### Why a declined action does not look like a broken one
     *
     * Rejecting an approval used to produce a row identical to a tool that threw \u2014
     * the same "Failed", in the same ink, next to the same duration. That told the
     * user their own answer was an error and sent them hunting for a fault that
     * was never there, which is the worst thing an interface can do to somebody
     * who has just made a decision correctly.
     *
     * So a declined row is given a mark of its own, built the same way STOPPED's
     * is \u2014 a word at the end of the row, in the trailing cluster, nothing moved
     * and no new colour introduced \u2014 and differing from it in exactly two ways:
     *
     *  - A `minus` leads it. A bar, not a cross: a cross is the glyph this app
     *    uses for dismissing and for errors, and it would put the alarm straight
     *    back. A bar reads as "nothing happened here", which is precisely what a
     *    declined action is.
     *  - It is set in [Theme.TEXT_MUTED] at regular weight, where a failure is
     *    [Theme.TEXT_FAINT] at medium. Brighter, because this is a settled fact
     *    worth stating plainly rather than a problem being flagged; lighter in
     *    weight, because it is not urgent. The two land at about the same optical
     *    weight by different routes, which is what stops the declined row reading
     *    as either an alarm or an afterthought.
     *
     * A declined row is also given no reason line, no "tap to see why" and no tap:
     * see [unexplained].
     */
    private fun outcomeMark(step: TrailStep): View {
        val declined = step.status == TrailStep.REJECTED
        val box = LinearLayout(context)
        box.orientation = HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        // The glyph leads the word, so the pair mirrors together.
        box.layoutDirection = Lang.direction(context)
        if (declined) {
            val glyph = ImageView(context)
            glyph.setImageDrawable(Icons.of("minus", Theme.TEXT_MUTED, Ui.STROKE))
            val size = Theme.dp(context, DECLINE_DP)
            val glyphLp = LayoutParams(size, size)
            glyphLp.marginEnd = Theme.dp(context, Ui.Space.XS)
            box.addView(glyph, glyphLp)
        }
        val mark = TextView(context)
        mark.text = outcomeWord(step)
        mark.textSize = Ui.Type.LABEL
        mark.typeface = if (declined) Theme.ui() else Theme.uiMedium()
        mark.setTextColor(if (declined) Theme.TEXT_MUTED else Theme.TEXT_FAINT)
        mark.maxLines = 1
        mark.textDirection = Lang.textDirection(context)
        box.addView(
            mark,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val boxLp = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        boxLp.marginStart = Theme.dp(context, Ui.Space.S)
        box.layoutParams = boxLp
        return box
    }

    /**
     * The `+N \u2212N` pair, in the palette's two real colours.
     *
     * Shared with the panel's own rows by being a plain factory rather than a piece
     * of either layout, because the numbers have to read identically in both — the
     * strip is where they are first seen and the panel is where they are checked.
     */
    private fun changeCounts(step: TrailStep): View {
        val box = LinearLayout(context)
        box.orientation = HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        // Latin digits and the sign glyphs read left to right whatever the language,
        // and `+12 \u22123` reversed by an RTL paragraph direction is nonsense.
        box.layoutDirection = View.LAYOUT_DIRECTION_LTR
        if (step.added > 0) {
            val added = TextView(context)
            added.text = "+" + Lang.num(context, step.added)
            added.textSize = Ui.Type.LABEL
            added.typeface = Theme.uiMedium()
            added.setTextColor(Theme.DIFF_ADD)
            added.maxLines = 1
            box.addView(added)
        }
        if (step.removed > 0) {
            val removed = TextView(context)
            removed.text = "\u2212" + Lang.num(context, step.removed)
            removed.textSize = Ui.Type.LABEL
            removed.typeface = Theme.uiMedium()
            removed.setTextColor(Theme.DIFF_DEL)
            removed.maxLines = 1
            val lp = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = Theme.dp(context, Ui.Space.XS + 2.0f)
            box.addView(removed, lp)
        }
        val boxLp = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        boxLp.marginStart = Theme.dp(context, Ui.Space.S)
        box.layoutParams = boxLp
        return box
    }

    private fun iconFor(step: TrailStep): String = iconOf(step)

    companion object {
        /**
         * The glyph for one activity row.
         *
         * On the companion because the panel draws the same rows from the same model
         * and had its own private copy of this mapping, which had already drifted:
         * the panel's version had no THINK case at all, so a reasoning row there fell
         * through to the generic tool glyph.
         */
        fun iconOf(step: TrailStep): String = when {
            step.kind == TrailStep.SEARCH -> "search"
            step.kind == TrailStep.FETCH -> "globe"
            step.kind == TrailStep.TASK -> "layers"
            step.kind == TrailStep.THINK -> "neuron"
            // The model addressing the user, not thinking. A speech mark, because
            // this is the one row on the strip that is a sentence somebody said.
            step.kind == TrailStep.NOTE -> "message"
            step.hasDiff() -> "edit"
            else -> "tool"
        }

        /**
         * True when a step ended in a way the user did NOT ask for and may need to
         * look into — the only two states worth spending a reason line, a tap and
         * an affordance on.
         *
         * [TrailStep.REJECTED] is deliberately not one of them, and that is the
         * point of the status existing. Declining an approval is a decision, not
         * an incident: there is nothing to diagnose, and putting "Tap to see why"
         * under a choice the user just made invites them to go looking for a fault
         * in their own answer — the same mistake, one layer up, that showing it as
         * "Failed" made in the first place. What the engine records against a
         * rejected step is in any case addressed to the MODEL ("do not retry it,
         * ask how they'd like to proceed"), which is not a sentence to put in
         * front of the person who said no.
         *
         * On the companion because the panel renders the same steps from the same
         * model and must reach the same verdict; the icon mapping below is here
         * for exactly that reason, having already drifted once when it was not.
         */
        fun unexplained(step: TrailStep): Boolean =
            step.status == TrailStep.FAILED || step.status == TrailStep.STOPPED

        /**
         * A duration a person can read: milliseconds below a second, seconds above.
         *
         * Per-step timings were recorded from the very first version and never shown,
         * which is a strange thing for a panel whose whole subject is where the time
         * went.
         */
        fun duration(context: Context, ms: Long): String = if (ms < 950L) {
            Fa.TRAIL_MS.format(Lang.num(context, Math.max(0L, ms)))
        } else {
            Fa.TRAIL_SECONDS.format(Lang.num(context, (ms + 500L) / 1000L))
        }

        /** Rows kept visible while the run is live. */
        const val LIVE_ROWS = 3

        /** Favicon-cluster circles shown before the count takes over. */
        const val CLUSTER_MAX = 3

        const val GLYPH_DP = 20.0f
        const val CHEVRON_DP = 18.0f
        const val ROW_ICON_DP = 19.0f

        /** The bar in front of "Declined". Sized to the word, not to the row icon. */
        const val DECLINE_DP = 14.0f

        /** Timer refresh. Four times a second: smooth, and nearly free. */
        const val TIMER_MS = 250L

        /**
         * Field separator inside a row [signature]. A C0 control, so no label,
         * detail or reason can contain one and forge a match across two fields.
         */
        private const val SEP = '\u0001'
    }

}

/**
 * The leading glyph of the strip: a lamp.
 *
 * It used to be a 3x3 grid of dots with a wave running through it, collapsing to
 * a thin open ring once the run finished. Both said "something is happening"
 * without saying what, and the ring in particular was just a circle sitting in
 * front of a sentence — the least specific mark available for the one row on the
 * screen whose whole subject is a request being thought about.
 *
 * A lamp says it directly. While the model is working the bulb is lit, rays out,
 * and breathes; the moment the run ends the rays drop away and the glass goes
 * quiet and unlit. The state IS the drawing, so nothing else on the row has to
 * carry it.
 *
 * The glyphs come from [Icons], so the lamp is stroked at the same width as every
 * other icon in the app and lands on the same pixel grid. What is drawn by hand
 * here is only the part [Icons] cannot express: a soft halo behind the glass
 * whose radius and opacity follow the same slow sine as the bulb's own
 * brightness, so the glow and the filament read as one breath rather than two
 * effects that happen to overlap.
 *
 * Driven by [Choreographer], so it advances on the display's own vsync and costs
 * nothing between frames — and it stops dead the moment the run ends, because an
 * idle 60fps animation on a phone is a battery bug, not a flourish.
 *
 * ### It has no direction
 *
 * Checked rather than assumed, because everything around it mirrors. The lamp is
 * symmetric about its own vertical axis and so are its rays — the `bulb-on` path
 * places them in matching pairs either side of x=12 — the halo is a circle
 * centred on the glyph, and both are drawn into a box this class squares and
 * centres itself. There is nothing here that reads as pointing anywhere, so
 * nothing here flips: a mirrored lamp is the same lamp. The one directional glyph
 * in the strip is the chevron, and that is handled where it is built.
 */
class PulseGlyph(context: Context) : View(context) {

    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private var running = false
    private var summary = false
    private var startedAt = 0L

    /** Repainted on a palette change; see [refreshTint]. */
    private var litLamp = Icons.of("bulb-on", Theme.TEXT, Ui.STROKE)
    private var idleLamp = Icons.of("bulb", Theme.TEXT_MUTED, Ui.STROKE)
    private var tintRevision = Theme.revision

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) {
                return
            }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        halo.style = Paint.Style.FILL
    }

    fun start() {
        if (running) {
            return
        }
        running = true
        summary = false
        startedAt = android.os.SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(frame)
        invalidate()
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        invalidate()
    }

    /** True to draw the finished-run mark instead of the working lamp. */
    fun setSummary(value: Boolean) {
        if (summary != value) {
            summary = value
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    /**
     * Rebuilds both drawables after a light/dark flip.
     *
     * The strip is not torn down when the palette changes — it is re-bound — so a
     * lamp built in the old theme would otherwise stay in the old theme's ink for
     * the rest of the run. Comparing [Theme.revision] is how the rest of the app
     * detects the same staleness.
     */
    private fun refreshTint() {
        if (tintRevision == Theme.revision) {
            return
        }
        tintRevision = Theme.revision
        litLamp = Icons.of("bulb-on", Theme.TEXT, Ui.STROKE)
        idleLamp = Icons.of("bulb", Theme.TEXT_MUTED, Ui.STROKE)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0.0f || h <= 0.0f) {
            return
        }
        refreshTint()
        val span = Math.min(w, h)
        val left = Math.round((w - span) / 2.0f)
        val top = Math.round((h - span) / 2.0f)
        val size = Math.round(span)

        if (summary || !running) {
            // Finished, or never started: the quiet unlit lamp, no halo.
            val lamp = idleLamp
            lamp.setBounds(left, top, left + size, top + size)
            lamp.alpha = if (summary) 255 else 200
            lamp.draw(canvas)
            return
        }

        // One sine drives everything, so the glow and the glass brighten together.
        val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt).toFloat()
        val wave = (Math.sin((elapsed / PERIOD_MS) * 2.0 * Math.PI).toFloat() + 1.0f) / 2.0f

        // The halo sits behind the glass, not behind the whole icon: the bulb's
        // centre of mass is high in a 24x24 viewport, so a halo centred on the
        // view would look like it was leaking out of the base.
        val glowRadius = span * (GLOW_MIN + (GLOW_MAX - GLOW_MIN) * wave)
        halo.color = Theme.alpha(Theme.TEXT, Math.round(GLOW_ALPHA * wave))
        canvas.drawCircle(w / 2.0f, top + span * GLASS_CENTRE, glowRadius, halo)

        val lamp = litLamp
        lamp.setBounds(left, top, left + size, top + size)
        lamp.alpha = Math.round(255.0f * (DIM + (BRIGHT - DIM) * wave))
        lamp.draw(canvas)
    }

    private companion object {
        /** How bright the lamp gets at the top and bottom of a breath. */
        const val DIM = 0.55f
        const val BRIGHT = 1.0f

        /** Halo size as a fraction of the glyph box, dimmest to brightest. */
        const val GLOW_MIN = 0.26f
        const val GLOW_MAX = 0.40f

        /** Peak halo opacity. Low: this is a suggestion of light, not a flare. */
        const val GLOW_ALPHA = 46.0f

        /** Where the glass sits down the 24x24 viewport — see [onDraw]. */
        const val GLASS_CENTRE = 0.46f

        /** One full breath, in milliseconds. Slower than a pulse, like thought. */
        const val PERIOD_MS = 1700.0f
    }
}

/**
 * The overlapping source cluster from the reference: a short row of circles,
 * each standing for one host, each biting into the one before it.
 *
 * The circles carry the host's initial rather than its real favicon. That is a
 * deliberate choice, not a shortcut: real favicons are full-colour, and dropping
 * a handful of them into a strictly monochrome interface is the one thing
 * guaranteed to make it look cheap. A monogram keeps the cluster's shape — which
 * is the part that reads as "sources" — in the app's own palette, and it needs no
 * network request, no cache, no decoder and no fallback for the many hosts whose
 * icon is a 404.
 */
class SourceCluster(
    context: Context,
    hosts: List<String>,
    private val limit: Int
) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shown: List<String> = hosts.take(limit)

    init {
        fill.style = Paint.Style.FILL
        ring.style = Paint.Style.STROKE
        text.textAlign = Paint.Align.CENTER
        text.typeface = Theme.uiSemi()
    }

    override fun onDraw(canvas: Canvas) {
        if (shown.isEmpty()) {
            return
        }
        val size = Math.min(width, height).toFloat()
        if (size <= 0.0f) {
            return
        }
        val radius = size / 2.0f
        val step = size * (1.0f - OVERLAP)
        val ringWidth = Theme.dpf(context, 1.5f)
        ring.strokeWidth = ringWidth
        text.textSize = size * 0.5f
        // A stack has a leading edge, and in Persian that edge is the right one.
        //
        // This is hand-rolled drawing, so nothing mirrors it: the row around it
        // flips, the circles did not, and the cluster ended up biting the wrong
        // way — first host at the far edge and buried under the last one, which is
        // the opposite of what the overlap is there to say. `Lang.mirrored` rather
        // than the resolved layout direction because a Canvas has none of its own
        // and the answer is the same for every cluster on the screen.
        val mirrored = Lang.mirrored(context)
        val span = width.toFloat()
        // Drawn last-to-first so each circle sits UNDER the one before it, which
        // is what makes the row read as a stack rather than a fence.
        for (i in shown.indices.reversed()) {
            val offset = i * step
            val cx = if (mirrored) span - radius - offset else radius + offset
            val host = shown[i]
            fill.color = shadeFor(host)
            canvas.drawCircle(cx, radius, radius - ringWidth / 2.0f, fill)
            ring.color = Theme.BG
            canvas.drawCircle(cx, radius, radius - ringWidth / 2.0f, ring)
            text.color = Theme.BG
            val initial = initialOf(host)
            val baseline = radius - (text.descent() + text.ascent()) / 2.0f
            canvas.drawText(initial, cx, baseline, text)
        }
    }

    private fun initialOf(host: String): String {
        for (c in host) {
            if (c.isLetterOrDigit()) {
                return c.uppercase()
            }
        }
        return "•"
    }

    /**
     * A stable grey per host, so the same site keeps the same shade for the whole
     * run and two different sites next to each other are told apart.
     */
    private fun shadeFor(host: String): Int {
        var hash = 0
        for (c in host) {
            hash = hash * 31 + c.code
        }
        val bucket = Math.abs(hash) % SHADES
        val level = 0.42f + bucket * (0.34f / (SHADES - 1).toFloat())
        return Theme.alpha(Theme.TEXT, Math.round(255.0f * level))
    }

    companion object {
        /** Circle diameter. */
        const val SIZE_DP = 20.0f

        /** How much of a circle the next one covers. */
        private const val OVERLAP = 0.42f

        private const val SHADES = 5

        /** Width needed for [count] hosts, capped at [limit]. */
        fun widthFor(context: Context, count: Int, limit: Int): Int {
            val shown = Math.min(count, limit)
            if (shown <= 0) {
                return 0
            }
            val size = Theme.dpf(context, SIZE_DP)
            return Math.ceil((size + (shown - 1) * size * (1.0f - OVERLAP)).toDouble()).toInt()
        }
    }
}
