package com.vepro.code

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The separator between the facts on one metadata line, used at every level. */
private const val SEP = "  \u00b7  "

/**
 * Assigns text only when it would change something.
 *
 * [TextView.setText] measures and re-lays out unconditionally, and this board
 * republishes on EVERY sub-agent tool step. With three agents in flight most of
 * those publishes advance one row's step count and leave every other string on
 * the card identical, so comparing first trades a string compare for a layout
 * pass over the whole card. That is a real trade on the 2016-era hardware the
 * app supports now that the floor is API 23.
 */
private fun TextView.setIfChanged(value: String) {
    if (text != value) {
        text = value
    }
}

/**
 * The Dynamic Workflow board: how many sub-agents are working, on what, and how
 * many have finished.
 *
 * ### Why this exists
 *
 * Dynamic Workflow was always real — the `task` tool spawns genuine sub-agents,
 * each with its own clean context, its own tools and its own step budget — but
 * none of it was visible. The user switched on a mode whose entire promise is
 * "the job gets split up and worked through", and then watched the same single
 * opaque line as before.
 *
 * ### What this card used to claim, and why it was wrong
 *
 * The first board was drawn from the PLAN rather than from the agents, and it
 * said so out loud: the subheading was `WF_SUBTITLE` — "Split across %s
 * sub-agents" — formatted with the number of bulleted lines the model happened
 * to type in its prose. It therefore read "Split across 7 sub-agents" after a
 * single delegation, and it said it before any agent had started. Nothing on the
 * card counted agents, because nothing in the model recorded them.
 *
 * The model records them now, so the card reports them:
 *
 *  - the subheading comes from [Workflow.launched], [Workflow.liveCount] and
 *    [Workflow.doneCount] — agents dispatched, agents working, agents finished —
 *    and never from [Workflow.size], which is a count of plan lines;
 *  - before anything has been delegated it says [Fa.WF_AGENTS_NONE] rather than
 *    inventing a split;
 *  - the panel under the heading names what the live agents are actually working
 *    on, from [Workflow.liveTopics], which with three concurrent agents is the
 *    single thing on this card worth reading;
 *  - a row with a real agent on it ([WorkPhase.agentId] non-zero) is labelled
 *    with that agent; a row that is still only a line from the lead's plan is
 *    labelled as planned, so the card never implies a worker where there is none.
 */
class WorkflowView(context: Context) : LinearLayout(context) {

    private val heading = TextView(context)
    private val subheading = TextView(context)

    /**
     * "Working on ..." — the answer to "what is it doing right now".
     *
     * Hidden the moment nothing is running, because a stale list of topics is
     * worse than no list: it reads as work still in flight.
     */
    private val livePanel = LinearLayout(context)
    private val liveHeading = TextView(context)
    private val liveLines = LinearLayout(context)
    private val topicLines = ArrayList<TopicLine>()

    private val list = LinearLayout(context)

    /**
     * The rows, in board order, reused across binds.
     *
     * The board republishes on EVERY sub-agent tool step, and with three agents
     * in flight that is several times a second. Rebuilding the whole list each
     * time inflated a dozen views per publish on a device whose floor is now API
     * 23, and it threw away the state the rows are supposed to carry. Phases are
     * only ever appended to, so the common case is "same rows, new numbers" and
     * costs nothing but the text.
     */
    private val rows = ArrayList<PhaseRow>()

    /**
     * One arc per running agent, keyed by [WorkPhase.index].
     *
     * There used to be a single [PhaseSpinner] passed from row to row, because
     * execution was strictly sequential and at most one phase could be RUNNING.
     * With up to three agents at once that spinner animated whichever row got it
     * first and left the other two frozen.
     *
     * The keep-it-alive dance that surrounded that single instance is still here,
     * generalised, and it exists for one reason: an arc's state IS elapsed time,
     * so a spinner rebuilt on every publish restarts its sweep from zero and the
     * arc visibly jumps backwards — which reads as the work restarting. Keying on
     * the phase index (stable for the life of a row, and what [Workflow.launch]
     * binds an agent to) is what carries a turning arc across a rebuild, including
     * a full one: the row is new, the spinner inside it is not.
     */
    private val spinners = HashMap<Int, PhaseSpinner>()

    /**
     * Builds the mark for a row whose status has changed.
     *
     * Held as a field rather than made per call so the update loop allocates
     * nothing, and so a row can ask for its mark without knowing that a running
     * one has to come out of the spinner table instead of being constructed.
     */
    private val markFactory: (WorkPhase) -> View = { phase -> buildMark(phase) }

    init {
        orientation = VERTICAL
        // Persian is fully mirrored, and this card is ordinary chassis: heading,
        // panel and rows all flip together because every inset below is relative.
        layoutDirection = Lang.direction(context)
        background = Theme.flatCard(Theme.R_CARD, context)
        val pad = Theme.dp(context, Ui.Space.L)
        setPaddingRelative(pad, pad, pad, pad)

        val top = LinearLayout(context)
        top.orientation = HORIZONTAL
        top.layoutDirection = Lang.direction(context)
        top.gravity = Gravity.CENTER_VERTICAL

        val badgeSize = Theme.dp(context, BADGE_DP)
        val badge = ImageView(context)
        badge.setImageDrawable(Icons.of("layers", Theme.TEXT, Ui.STROKE))
        top.addView(badge, LayoutParams(badgeSize, badgeSize))

        val titles = LinearLayout(context)
        titles.orientation = VERTICAL
        titles.layoutDirection = Lang.direction(context)
        val titlesLp = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        titlesLp.marginStart = Theme.dp(context, Ui.Space.M)
        top.addView(titles, titlesLp)

        heading.text = Fa.WF_TITLE
        heading.textSize = Ui.Type.HEAD
        heading.typeface = Theme.uiBold()
        heading.setTextColor(Theme.TEXT)
        // Interface chrome, so it follows the interface rather than its content.
        heading.textDirection = Lang.textDirection(context)
        titles.addView(heading)

        subheading.textSize = Ui.Type.META
        subheading.typeface = Theme.ui()
        subheading.setTextColor(Theme.TEXT_FAINT)
        subheading.textDirection = Lang.textDirection(context)
        // Two lines: with six facts and Persian's longer words, one line loses the
        // live/finished marker at the end, which is the part that says whether any
        // of this is still happening.
        subheading.maxLines = 2
        subheading.ellipsize = TextUtils.TruncateAt.END
        val subLp = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        subLp.topMargin = Theme.dp(context, 2.0f)
        titles.addView(subheading, subLp)

        addView(
            top,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Recessed rather than raised: this block holds content the agents are
        // producing, it does not present a control.
        livePanel.orientation = VERTICAL
        livePanel.layoutDirection = Lang.direction(context)
        livePanel.background = Theme.sunkenCard(Theme.R_SM, context)
        val panelH = Theme.dp(context, Ui.Space.M)
        val panelV = Theme.dp(context, Ui.Space.S)
        livePanel.setPaddingRelative(panelH, panelV, panelH, panelV)

        liveHeading.text = Fa.WF_TOPIC
        liveHeading.textSize = Ui.Type.MICRO
        liveHeading.typeface = Theme.uiSemi()
        liveHeading.setTextColor(Theme.TEXT_MUTED)
        liveHeading.textDirection = Lang.textDirection(context)
        liveHeading.maxLines = 1
        livePanel.addView(liveHeading)

        liveLines.orientation = VERTICAL
        liveLines.layoutDirection = Lang.direction(context)
        val linesLp = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        linesLp.topMargin = Theme.dp(context, 3.0f)
        livePanel.addView(liveLines, linesLp)

        val panelLp = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        panelLp.topMargin = Theme.dp(context, Ui.Space.M)
        addView(livePanel, panelLp)

        list.orientation = VERTICAL
        list.layoutDirection = Lang.direction(context)
        val listLp = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        listLp.topMargin = Theme.dp(context, Ui.Space.M)
        addView(list, listLp)
    }

    fun bind(value: Workflow?) {
        val phases = value?.phases() ?: emptyList()
        if (value == null || phases.isEmpty()) {
            visibility = GONE
            // A board that empties — a different chat, a cleared transcript —
            // must not leave arcs reposting themselves on the Choreographer.
            release()
            return
        }
        visibility = VISIBLE

        val now = System.currentTimeMillis()
        subheading.setIfChanged(summary(value))
        paintLive(value.liveTopics())
        syncRows(phases, now)
        sweepSpinners(phases)
    }

    /**
     * The subheading: agents, not plan lines.
     *
     * Zeroes are left out on purpose — a run that has finished nothing yet should
     * not print "0 finished", because a zero is still a fact the eye has to read.
     * That leaves the line saying only what is true right now: "3 working" at the
     * start, "2 working · 1 finished" in the middle, "5 finished · 34 steps"
     * afterwards.
     */
    private fun summary(value: Workflow): String {
        val live = value.liveCount()
        val done = value.doneCount()
        val queued = value.pendingCount()
        val failed = value.failedCount()
        val parts = ArrayList<String>(6)

        if (value.launched <= 0) {
            // Nothing has been delegated. The plan may already have seven rows on
            // it, and every one of them is an intention: saying anything about
            // sub-agents here is the exact claim this card used to get wrong.
            parts.add(Fa.WF_AGENTS_NONE)
        } else {
            if (live > 0) {
                parts.add(Fa.WF_AGENTS_LIVE.format(Lang.num(context, live)))
            }
            if (done > 0) {
                parts.add(Fa.WF_AGENTS_DONE.format(Lang.num(context, done)))
            }
        }
        if (queued > 0) {
            parts.add(Fa.WF_QUEUED.format(Lang.num(context, queued)))
        }
        if (failed > 0) {
            parts.add(Lang.num(context, failed) + " " + Fa.WF_FAILED)
        }
        // Only while it explains something. "3 at a time" is the answer to "why
        // are four of these queued when three are running"; on a settled board it
        // is trivia.
        if (value.running && value.parallel > 1 && live + queued > 1) {
            parts.add(Fa.WF_PARALLEL.format(Lang.num(context, value.parallel)))
        }
        // The cost of the whole board, once there is nothing left to watch.
        if (!value.running) {
            val total = value.stepTotal()
            if (total > 0) {
                parts.add(Fa.WF_STEPS.format(Lang.num(context, total)))
            }
        }
        parts.add(if (value.running) Fa.WF_LIVE else Fa.WF_HISTORY)
        return parts.joinToString(SEP)
    }

    /**
     * Names what every live agent is working on.
     *
     * How this reads at each width, which is the whole design of the block:
     *
     *  - **one agent** — no bullet, and the topic may run to two lines. A bullet
     *    in front of a single item claims a list that is not there, and with one
     *    agent there is room to show the topic whole.
     *  - **two or three** — a bullet each and one line each. Bounded height
     *    matters more than a complete sentence here: the block sits above the
     *    rows, and a topic that wraps to three lines pushes the work itself off
     *    the screen. Nothing is lost — the same topic is spelled out in full in
     *    that agent's own row below.
     *  - **more than [TOPIC_LINES]** — the engine may bind six briefs to rows in
     *    one wave even though only [Workflow.parallel] of them execute at a time,
     *    so the tail is summarised as a count rather than allowed to grow.
     *
     * The label stays on its own line at every width, so the block does not
     * reflow around it as agents start and finish.
     */
    private fun paintLive(topics: List<String>) {
        if (topics.isEmpty()) {
            livePanel.visibility = GONE
            return
        }
        livePanel.visibility = VISIBLE

        val shown = Math.min(topics.size, TOPIC_LINES)
        val extra = topics.size - shown
        val needed = if (extra > 0) shown + 1 else shown
        while (topicLines.size < needed) {
            val line = TopicLine(context)
            topicLines.add(line)
            val lineLp = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            liveLines.addView(line, lineLp)
        }

        val bulleted = shown > 1
        for (i in topicLines.indices) {
            val line = topicLines[i]
            when {
                i < shown -> line.show(topics[i], bulleted, if (bulleted) 1 else 2, false)
                i == shown && extra > 0 -> line.show(
                    Lang.text(context, "+%s more", "%s مورد دیگر")
                        .format(Lang.num(context, extra)),
                    false, 1, true
                )

                else -> line.visibility = GONE
            }
        }
    }

    /**
     * Brings the rows into line with the phases without rebuilding them.
     *
     * A board only ever grows: [Workflow.seed], [Workflow.launch] and
     * [Workflow.claim] all append, and no path removes or reorders. So the
     * ordinary publish appends nothing and updates text, and the full rebuild is
     * reserved for the case where the view has been pointed at a DIFFERENT board
     * — a history redraw, a switched chat — which the index check catches.
     */
    private fun syncRows(phases: List<WorkPhase>, now: Long) {
        var reusable = rows.size <= phases.size
        if (reusable) {
            for (i in rows.indices) {
                if (rows[i].index != phases[i].index) {
                    reusable = false
                    break
                }
            }
        }
        if (!reusable) {
            list.removeAllViews()
            rows.clear()
        }
        while (rows.size < phases.size) {
            val row = PhaseRow(context, phases[rows.size].index)
            rows.add(row)
            val rowLp = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            list.addView(row, rowLp)
        }
        for (i in phases.indices) {
            rows[i].update(phases[i], now, markFactory)
        }
    }

    /** The mark at the head of a row: an arc, an outcome, or a plan number. */
    private fun buildMark(phase: WorkPhase): View = when (phase.status) {
        WorkPhase.RUNNING -> spinnerFor(phase.index)
        WorkPhase.DONE -> glyphMark("check", Theme.TEXT)
        WorkPhase.FAILED -> glyphMark("x", Theme.TEXT_MUTED)
        WorkPhase.STOPPED -> glyphMark("minus", Theme.TEXT_MUTED)
        // No agent has ever been on this row, so it gets no agent's mark — just
        // its number in the plan, in the quietest weight the palette has.
        else -> numberMark(phase.index)
    }

    /** The arc belonging to one phase, created once and kept turning. */
    private fun spinnerFor(index: Int): PhaseSpinner {
        val existing = spinners[index]
        if (existing != null) {
            return existing
        }
        val arc = PhaseSpinner(context)
        spinners[index] = arc
        arc.start()
        return arc
    }

    private fun glyphMark(icon: String, tint: Int): View {
        val view = ImageView(context)
        view.setImageDrawable(Icons.of(icon, tint, Ui.STROKE))
        view.background = Theme.circle(Theme.SURFACE_2)
        val inset = Theme.dp(context, 3.0f)
        view.setPaddingRelative(inset, inset, inset, inset)
        return view
    }

    private fun numberMark(index: Int): View {
        val view = TextView(context)
        view.text = Lang.num(context, index)
        view.textSize = Ui.Type.MICRO
        view.typeface = Theme.uiSemi()
        view.setTextColor(Theme.TEXT_FAINT)
        view.gravity = Gravity.CENTER
        view.background = Theme.circle(Theme.SURFACE_2)
        return view
    }

    /**
     * Stops the arcs whose agents have finished, and makes sure every arc that
     * still has an agent behind it is turning.
     *
     * [PhaseSpinner.start] returns immediately when the arc is already running,
     * so the second half costs nothing on an ordinary publish and is what brings
     * the board back to life after [onDetachedFromWindow] has stopped everything.
     */
    private fun sweepSpinners(phases: List<WorkPhase>) {
        val live = HashSet<Int>()
        for (phase in phases) {
            if (phase.status == WorkPhase.RUNNING) {
                live.add(phase.index)
            }
        }
        val finished = ArrayList<Int>(spinners.size)
        for (key in spinners.keys) {
            if (!live.contains(key)) {
                finished.add(key)
            }
        }
        for (key in finished) {
            spinners.remove(key)?.stop()
        }
        for (spinner in spinners.values) {
            spinner.start()
        }
    }

    /** Drops every arc and every row. Only for a board that has gone away. */
    private fun release() {
        for (spinner in spinners.values) {
            spinner.stop()
        }
        spinners.clear()
        list.removeAllViews()
        rows.clear()
        topicLines.clear()
        liveLines.removeAllViews()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // EVERY arc, not one. Each spinner already stops itself on its own detach,
        // but the arcs deliberately outlive a rebuild, so one can be sitting in
        // this table with no parent at all — and a Choreographer callback that
        // reposts itself with nothing to draw is a frame-rate and battery leak
        // that nothing else would ever clear.
        //
        // The table itself is KEPT. The spinner views are still inside their rows,
        // and a row only asks for a new mark when its status changes, so clearing
        // the table here would strand a stopped arc in a running row for ever.
        // sweepSpinners() restarts them on the next bind.
        for (spinner in spinners.values) {
            spinner.stop()
        }
    }

    /**
     * One line of the live panel: an optional bullet, then a topic.
     *
     * A [View] with a circle background rather than a bullet CHARACTER, because a
     * literal "•" in front of Persian is a neutral glyph whose side depends
     * on the surrounding run; a dot in its own slot with a `marginEnd` sits on the
     * reading edge in both languages by construction.
     */
    private class TopicLine(context: Context) : LinearLayout(context) {

        private val dot = View(context)
        private val label = TextView(context)

        /** The style this line is currently wearing; -1 = none applied yet. */
        private var styleKey = -1

        init {
            orientation = HORIZONTAL
            layoutDirection = Lang.direction(context)
            gravity = Gravity.CENTER_VERTICAL
            val gap = Theme.dp(context, 2.0f)
            setPaddingRelative(0, gap, 0, gap)

            dot.background = Theme.circle(Theme.TEXT_FAINT)
            val dotSize = Theme.dp(context, DOT_DP)
            val dotLp = LayoutParams(dotSize, dotSize)
            dotLp.marginEnd = Theme.dp(context, Ui.Space.S)
            addView(dot, dotLp)

            label.textSize = Ui.Type.LABEL
            // A topic is written by the model, from a brief written by the model,
            // about whatever the user asked for — so it decides its own direction
            // from its own first strong character rather than inheriting the
            // interface's.
            label.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            label.ellipsize = TextUtils.TruncateAt.END
            val labelLp = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            addView(label, labelLp)
        }

        fun show(text: String, bulleted: Boolean, lines: Int, muted: Boolean) {
            visibility = VISIBLE
            // Style changes only when the SHAPE of the list changes — an agent
            // starting or finishing — which is rare next to the publish rate.
            // setMaxLines and setTypeface both force a measure pass.
            val key = (if (bulleted) 1 else 0) or (if (muted) 2 else 0) or (lines shl 2)
            if (styleKey != key) {
                styleKey = key
                dot.visibility = if (bulleted) VISIBLE else GONE
                label.maxLines = lines
                label.setTextColor(if (muted) Theme.TEXT_FAINT else Theme.TEXT)
                label.typeface = if (muted) Theme.ui() else Theme.uiMedium()
            }
            label.setIfChanged(text)
        }

        private companion object {
            const val DOT_DP = 4.0f
        }
    }

    /**
     * One row of the board.
     *
     * Two kinds of thing land here and they are not the same: a row with an agent
     * on it, and a line from the plan that no agent has been dispatched onto. The
     * difference is [WorkPhase.agentId], not the status — a board reloaded from
     * an older transcript can be DONE with no agent recorded — and it is drawn
     * twice over: in the mark (an outcome glyph against a plain plan number) and
     * in the first token of the metadata line (the agent, against "Planned").
     *
     * The row updates in place. Only the mark is ever swapped, and only when the
     * status it was built for changes, which is why the arc of a running agent
     * survives a publish.
     */
    private class PhaseRow(context: Context, val index: Int) : LinearLayout(context) {

        /** A fixed-size slot, so exchanging the mark never re-lays the row. */
        private val mark = LinearLayout(context)
        private val title = TextView(context)
        private val meta = TextView(context)

        /** What the agent reported back — model-written, so it gets its own line. */
        private val note = TextView(context)

        /** The status the mark in [mark] was built for; -1 = nothing built yet. */
        private var markStatus = -1

        init {
            orientation = HORIZONTAL
            layoutDirection = Lang.direction(context)
            gravity = Gravity.TOP
            val vertical = Theme.dp(context, Ui.Space.S)
            setPaddingRelative(0, vertical, 0, vertical)

            mark.orientation = HORIZONTAL
            mark.layoutDirection = Lang.direction(context)
            mark.gravity = Gravity.CENTER
            val markSize = Theme.dp(context, MARK_DP)
            addView(mark, LayoutParams(markSize, markSize))

            val column = LinearLayout(context)
            column.orientation = VERTICAL
            column.layoutDirection = Lang.direction(context)
            val columnLp = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            columnLp.marginStart = Theme.dp(context, Ui.Space.M)
            addView(column, columnLp)

            // Model-written: its own direction, from its own first character.
            title.textSize = Ui.Type.LABEL
            title.maxLines = 2
            title.ellipsize = TextUtils.TruncateAt.END
            title.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            column.addView(title)

            // Interface chrome only — agent, state, steps, elapsed. The note is
            // NOT folded in here: it is the one part of this line the model wrote,
            // and a Persian sentence appended to an English run lays itself out
            // backwards inside it.
            meta.textSize = Ui.Type.META
            meta.typeface = Theme.ui()
            meta.setTextColor(Theme.TEXT_FAINT)
            meta.maxLines = 1
            meta.ellipsize = TextUtils.TruncateAt.END
            meta.textDirection = Lang.textDirection(context)
            val metaLp = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            metaLp.topMargin = Theme.dp(context, 2.0f)
            column.addView(meta, metaLp)

            note.textSize = Ui.Type.META
            note.typeface = Theme.ui()
            note.setTextColor(Theme.TEXT_MUTED)
            note.maxLines = 2
            note.ellipsize = TextUtils.TruncateAt.END
            note.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            val noteLp = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            noteLp.topMargin = Theme.dp(context, 2.0f)
            column.addView(note, noteLp)
        }

        fun update(phase: WorkPhase, now: Long, markFactory: (WorkPhase) -> View) {
            // Everything that depends only on the STATUS is touched only when the
            // status actually moves. Exchanging the mark and setting a typeface
            // both force a measure pass, and the overwhelming majority of
            // publishes on a live board advance a step count and change nothing
            // else — so on those this whole block is skipped.
            if (markStatus != phase.status) {
                markStatus = phase.status
                swapMark(phase, markFactory)
                paintTone(phase)
            }

            // The topic where there is one: the title is the plan line the user
            // read, the topic is the subject of the brief the agent was actually
            // handed, and where they differ the topic is the truthful one. Same
            // rule as Workflow.liveTopics(), so the panel above and the row below
            // cannot disagree about what an agent is doing.
            title.setIfChanged(
                if (phase.topic.isNotBlankJava()) phase.topic else phase.title
            )
            meta.setIfChanged(metaLine(phase, now))

            val reported = phase.note
            if (reported.isNotBlankJava()) {
                note.setIfChanged(reported)
                note.visibility = VISIBLE
            } else {
                note.visibility = GONE
            }
        }

        private fun swapMark(phase: WorkPhase, markFactory: (WorkPhase) -> View) {
            mark.removeAllViews()
            val glyph = markFactory(phase)
            // An arc outlives the row it was in, so it can arrive here still
            // parented to the row a full rebuild has just discarded.
            (glyph.parent as? ViewGroup)?.removeView(glyph)
            val glyphLp = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            mark.addView(glyph, glyphLp)
        }

        /**
         * Weight and colour for the row's state.
         *
         * [WorkPhase.agentId] and the status are written together by
         * [Workflow.launch], so a status change is the only moment either of the
         * two facts this reads can have moved.
         */
        private fun paintTone(phase: WorkPhase) {
            val running = phase.status == WorkPhase.RUNNING
            title.typeface = if (running) Theme.uiSemi() else Theme.ui()
            title.setTextColor(
                when {
                    running -> Theme.TEXT
                    phase.agentId == 0 && phase.status == WorkPhase.PENDING -> Theme.TEXT_FAINT
                    else -> Theme.TEXT_MUTED
                }
            )
        }

        /** Agent, state, steps, elapsed — only the parts that are true. */
        private fun metaLine(phase: WorkPhase, now: Long): String {
            val parts = ArrayList<String>(4)
            val agent = phase.agentId
            if (agent != 0) {
                parts.add(Fa.WF_AGENT.format(Lang.num(context, agent)))
            } else {
                parts.add(Lang.text(context, "Planned", "برنامه‌ریزی‌شده"))
            }
            // "Planned" has already said PENDING. Printing both is the kind of
            // doubled statement that makes a card look automatically generated.
            if (agent != 0 || phase.status != WorkPhase.PENDING) {
                parts.add(stateWord(phase.status))
            }
            if (phase.steps > 0) {
                parts.add(Fa.WF_STEPS.format(Lang.num(context, phase.steps)))
            }
            // One duration vocabulary in the app: milliseconds under a second,
            // seconds above, in the interface's own numerals.
            val elapsed = phase.durationMs(now)
            if (elapsed > 0L) {
                parts.add(TrailView.duration(context, elapsed))
            }
            return parts.joinToString(SEP)
        }

        private fun stateWord(status: Int): String = when (status) {
            WorkPhase.RUNNING -> Fa.WF_RUNNING
            WorkPhase.DONE -> Fa.WF_DONE
            WorkPhase.FAILED -> Fa.WF_FAILED
            WorkPhase.STOPPED -> Fa.WF_STOPPED
            else -> Fa.WF_PENDING
        }

        private companion object {
            const val MARK_DP = 22.0f
        }
    }

    private companion object {
        const val BADGE_DP = 20.0f

        /**
         * Topics named individually before the rest become a count.
         *
         * Matches AgentEngine's parallel ceiling, which is how many agents can be
         * executing at once. The engine binds a whole wave of briefs to rows
         * before dispatching them, so more than this can be RUNNING at the same
         * instant, and the overflow line is what keeps that honest instead of
         * unbounded.
         */
        const val TOPIC_LINES = 3
    }
}

/**
 * The arc that turns while a sub-agent works.
 *
 * A single stroked arc sweeping around its own circle, driven by
 * [Choreographer] so it advances on vsync and stops the instant it leaves the
 * window. Deliberately not an indeterminate ProgressBar: that would drag in the
 * platform's own styling and colour, and this interface is monochrome by design.
 *
 * There is one of these per RUNNING agent now, not one per board. See
 * [WorkflowView.spinners] for why they are keyed and carried across rebuilds.
 */
class PhaseSpinner(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var running = false
    private var startedAt = 0L

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
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
    }

    fun start() {
        if (running) {
            return
        }
        running = true
        startedAt = android.os.SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onDraw(canvas: Canvas) {
        val span = Math.min(width, height).toFloat()
        if (span <= 0.0f) {
            return
        }
        val stroke = Math.max(Theme.dpf(context, 2.0f), span * 0.11f)
        paint.strokeWidth = stroke
        val inset = stroke / 2.0f + Theme.dpf(context, 1.0f)
        val left = (width - span) / 2.0f + inset
        val top = (height - span) / 2.0f + inset
        val right = left + span - inset * 2.0f
        val bottom = top + span - inset * 2.0f

        paint.color = Theme.alpha(Theme.TEXT, 40)
        canvas.drawArc(left, top, right, bottom, 0.0f, 360.0f, false, paint)

        // The sweep does NOT mirror in Persian, and that is a decision rather than
        // an oversight.
        //
        // Everything else on this card flips, because everything else on this card
        // has a reading order: text, insets, the bullet before a topic, the mark
        // before a row. This arc has none. It is INDETERMINATE — the angle encodes
        // no quantity and indexes no scale, it only says "an agent is working" —
        // and the shape it borrows for that is a clock, which runs clockwise in
        // Persian exactly as it does in English. Mirroring it would not translate
        // anything; it would just make the one moving object on a Persian screen
        // turn the opposite way from the one on an English screen for no reason a
        // reader could name.
        //
        // A DETERMINATE arc would be the opposite case: a progress arc maps a value
        // onto an angle, so it inherits the direction of the scale it is drawing
        // and should be mirrored with everything else. If this spinner ever gains
        // a completion fraction, that is the moment to start multiplying the sweep
        // by Lang.mirrored(context).
        val elapsed = (android.os.SystemClock.uptimeMillis() - startedAt).toFloat()
        val turn = (elapsed / PERIOD_MS) * 360.0f % 360.0f
        paint.color = Theme.TEXT
        canvas.drawArc(left, top, right, bottom, turn - 90.0f, SWEEP, false, paint)
    }

    private companion object {
        const val PERIOD_MS = 1000.0f
        const val SWEEP = 90.0f
    }
}
