package com.vepro.code

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Vega bottom sheet: a flat [Theme.SURFACE] panel with a 28dp shoulder, a
 * grabber, a square header tile and a short translate + fade entrance.
 *
 * The panel used to be a vertical sheen gradient with a 32dp shoulder and an
 * elevated, gradient-filled header tile. All three are gone: the monochrome
 * system separates surfaces by FILL and a hairline [Theme.BORDER] only, and the
 * *title* — not the tile — carries the weight, so the question being asked is
 * the first thing read.
 *
 * The public surface is deliberately unchanged: twenty call sites build their
 * sheets through [body], [header], [rule] and [show].
 */
class Sheet(private val c: Context) {

    /** Content container — callers add their own rows here. */
    val body: LinearLayout

    private val dialog = Dialog(c)

    /** Set once the exit animation starts, so a double tap cannot re-run it. */
    private var dismissing = false

    /**
     * Mirrors what was last passed to [setCancelable].
     *
     * The dialog knows this, but will not say — there is no getter — and the
     * drag gesture has to ask, so that a sheet the app is BLOCKED on (an approval
     * that must be answered) cannot be flicked away without answering it.
     */
    private var cancelable = true
    private val panel: LinearLayout

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        panel = LinearLayout(c)
        panel.orientation = LinearLayout.VERTICAL
        panel.layoutDirection = Lang.direction(c)

        // Flat [Theme.SURFACE] with a hairline border and a 28dp shoulder on the
        // TOP corners only — the bottom two sit off-screen against the window
        // edge, so rounding them just clips the fill against the navigation bar.
        val background = GradientDrawable()
        background.setColor(Theme.SURFACE)
        background.setStroke(Theme.hairline(c), Theme.BORDER)
        val corner = Theme.dpf(c, Theme.R_LG)
        background.cornerRadii = floatArrayOf(
            corner, corner, corner, corner, 0.0f, 0.0f, 0.0f, 0.0f
        )
        panel.background = background

        val padH = Theme.dp(c, Ui.Space.XL)
        panel.setPadding(padH, Theme.dp(c, Ui.Space.M), padH, Theme.dp(c, Ui.Space.XXL))

        // grabber — 36 x 4dp, the one mark that says "this panel drags"
        val grabber = View(c)
        grabber.background = Theme.roundRect(Theme.BORDER_HI, 2.0f, c)
        grabber.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val grabberParams = LinearLayout.LayoutParams(
            Theme.dp(c, 36.0f), Theme.dp(c, 4.0f)
        )
        grabberParams.gravity = Gravity.CENTER_HORIZONTAL
        grabberParams.bottomMargin = Theme.dp(c, Ui.Space.L)
        panel.addView(grabber, grabberParams)

        body = LinearLayout(c)
        body.orientation = LinearLayout.VERTICAL
        body.layoutDirection = Lang.direction(c)
        panel.addView(
            body,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val scroll = DragScroll(c)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(panel)
        dialog.setContentView(scroll)

        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(0))
            window.setLayout(
                Math.min(c.resources.displayMetrics.widthPixels, Theme.dp(c, 560.0f)),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            val attributes = window.attributes
            attributes.dimAmount = if (Theme.DARK) 0.66f else 0.44f
            window.attributes = attributes
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // The dialog gets its own window, so it needs its own system-bar
            // chrome: without this a light-mode sheet sat over a dark system
            // navigation bar with light icons on a light background.
            try {
                window.navigationBarColor = Theme.SURFACE
                @Suppress("DEPRECATION")
                var vis = window.decorView.systemUiVisibility
                vis = if (Theme.DARK) {
                    vis and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                } else {
                    vis or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = vis
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * A bare centred title, with no icon tile and no subtitle.
     *
     * The icon header is right for a sheet that asks something — an approval, a
     * plan, a setting. It is wrong for a sheet that simply IS a body of content:
     * the Thoughts panel and the results list are the whole point of themselves,
     * and a decorated tile over them reads as a dialog wrapped round a list.
     * Centred and bold, exactly as the reference does it.
     */
    fun plainTitle(title: String): Sheet {
        val label = TextView(c)
        label.text = title
        label.textSize = Ui.Type.SUBTITLE
        label.typeface = Theme.uiBold()
        label.setTextColor(Theme.TEXT)
        label.gravity = Gravity.CENTER
        label.textAlignment = View.TEXT_ALIGNMENT_CENTER
        val lp = Ui.matchWrap()
        lp.bottomMargin = Theme.dp(c, Ui.Space.L)
        body.addView(label, lp)
        return this
    }

    /** Header row: square icon tile + title + optional subtitle. */
    fun header(icon: String, title: String, subtitle: String?): Sheet =
        header(icon, title, subtitle, Theme.ACCENT)

    /**
     * Header row in a specific [tone].
     *
     * [Theme.ACCENT] — the default — gets a SOLID accent tile with an
     * [Theme.ON_ACCENT] glyph, which inverts with the palette. Any other tone
     * gets a flat [Theme.iconChip] square with the glyph in [Theme.TEXT]: every
     * tone token is a grey now, so tinting the glyph would only make it quieter,
     * not different. The tile is never a gradient and never elevated.
     */
    fun header(icon: String, title: String, subtitle: String?, tone: Int): Sheet {
        val row = Ui.row(c)
        row.setPadding(0, 0, 0, Theme.dp(c, Ui.Space.L))

        val tile = LinearLayout(c)
        tile.gravity = Gravity.CENTER
        val tileRadius = Theme.R_SM
        val accentTile = tone == Theme.ACCENT
        tile.background = if (accentTile) {
            Theme.roundRect(Theme.ACCENT, tileRadius, c)
        } else {
            Theme.iconChip(tone, tileRadius, c)
        }
        val tileSize = Theme.dp(c, 40.0f)

        val glyph = ImageView(c)
        glyph.setImageDrawable(
            Icons.of(icon, if (accentTile) Theme.ON_ACCENT else Theme.TEXT, Ui.STROKE)
        )
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(c, Ui.Space.XL)
        tile.addView(glyph, LinearLayout.LayoutParams(glyphSize, glyphSize))

        val tileParams = LinearLayout.LayoutParams(tileSize, tileSize)
        tileParams.marginEnd = Theme.dp(c, Ui.Space.M)
        row.addView(tile, tileParams)

        val texts = Ui.column(c)

        val titleView = Ui.text(c, title, Ui.Type.SUBTITLE, Theme.TEXT, Theme.uiBold())
        titleView.setLineSpacing(Theme.dpf(c, 1.0f), 1.0f)
        Ui.rowLabel(titleView)
        texts.addView(titleView, Ui.matchWrap())

        if (!subtitle.isNullOrEmpty()) {
            val subtitleView = Ui.text(c, subtitle, Ui.Type.META, Theme.TEXT_MUTED, Theme.ui())
            subtitleView.setLineSpacing(Theme.dpf(c, 2.0f), 1.0f)
            Ui.rowLabel(subtitleView)
            val subtitleParams = Ui.matchWrap()
            subtitleParams.topMargin = Theme.dp(c, 2.0f)
            texts.addView(subtitleView, subtitleParams)
        }

        row.addView(texts, Ui.grow())
        body.addView(row)
        return this
    }

    /** A hairline separator inside the sheet body. */
    fun rule(): Sheet {
        val line = Ui.divider(c)
        val params = Ui.matchWrap()
        params.topMargin = Theme.dp(c, 4.0f)
        params.bottomMargin = Theme.dp(c, 14.0f)
        line.layoutParams = params
        body.addView(line)
        return this
    }

    fun setCancelable(cancelable: Boolean) {
        this.cancelable = cancelable
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
    }

    // =====================================================================
    // Drag to dismiss
    // =====================================================================

    /**
     * The scroller that owns the sheet, taught to hand a downward pull to the
     * panel instead of to itself.
     *
     * The grabber at the top of every sheet has always said "this panel drags",
     * and until now it was purely decorative — the only ways out were the back
     * gesture and a tap on the scrim, neither of which is what a thumb already
     * resting on a bottom sheet reaches for. Pulling it down and watching it
     * stay put is the kind of small lie that makes an interface feel cheap.
     *
     * The gesture has to be arbitrated rather than simply attached, because the
     * content scrolls: a Thoughts panel with thirty rows must scroll normally,
     * and a downward drag is ambiguous between "scroll up through the list" and
     * "throw the sheet away". The rule is the one every good bottom sheet uses —
     * a downward drag becomes a dismiss only when the list is already at its
     * top, so scrolling always wins while there is anything above to reveal.
     *
     * Implemented by overriding the ScrollView rather than with a touch listener
     * on the panel: a listener attached to a child never sees the gesture,
     * because the ScrollView claims it first. Interception is the only place the
     * decision can actually be made.
     */
    private inner class DragScroll(context: Context) : ScrollView(context) {

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
        private var downY = 0.0f
        private var dragging = false
        private var tracker: VelocityTracker? = null

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (shouldTakeOver(ev)) {
                return true
            }
            return try {
                super.onInterceptTouchEvent(ev)
            } catch (ignored: Throwable) {
                false
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!dragging) {
                if (shouldTakeOver(ev)) {
                    return true
                }
                return try {
                    super.onTouchEvent(ev)
                } catch (ignored: Throwable) {
                    false
                }
            }
            tracker?.addMovement(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> follow(ev.y - downY)
                MotionEvent.ACTION_UP -> release()
                MotionEvent.ACTION_CANCEL -> settle()
                else -> {
                }
            }
            return true
        }

        /**
         * Decides whether this event starts a dismissal drag.
         *
         * Records the finger on every DOWN so a MOVE has something to measure
         * against, then converts to a drag on the first MOVE that is downward,
         * past the touch slop, and taken while the content is already scrolled to
         * the top. A sheet that cannot be cancelled — an approval waiting on an
         * answer — is never draggable, or the gesture would become a way to skip
         * a question the app is blocked on.
         */
        private fun shouldTakeOver(ev: MotionEvent): Boolean {
            if (!cancelable || dismissing) {
                return false
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && ev.y - downY > slop && scrollY == 0) {
                        begin(ev)
                        return true
                    }
                }
                else -> {
                }
            }
            return false
        }

        private fun begin(ev: MotionEvent) {
            dragging = true
            // Re-base on the point the drag was RECOGNISED at, not where the
            // finger landed. Without this the panel jumps by one slop's worth the
            // instant it starts following, which is the exact amount of hitch the
            // eye notices.
            downY = ev.y
            panel.animate().cancel()
            tracker = try {
                VelocityTracker.obtain().also { it.addMovement(ev) }
            } catch (ignored: Throwable) {
                null
            }
        }

        /**
         * Moves the panel with the finger.
         *
         * Upward travel is clamped to zero — a sheet already at its resting place
         * has nowhere further up to go, and letting it follow would peel it off
         * the bottom of the window. The fade is partial and reaches only
         * [DRAG_FADE]: the panel should look like it is being let go of, not like
         * it is evaporating, and it still has to be legible if the drag is
         * abandoned halfway.
         */
        private fun follow(rawDelta: Float) {
            val delta = Math.max(0.0f, rawDelta)
            panel.translationY = delta
            val reach = dismissDistance()
            val progress = if (reach <= 0.0f) 0.0f else Math.min(1.0f, delta / reach)
            panel.alpha = 1.0f - DRAG_FADE * progress
        }

        /**
         * Decides, on lift, whether the sheet goes or comes back.
         *
         * Either a flick or a commitment counts: a fast downward fling dismisses
         * however far it travelled, and a slow drag dismisses once it has passed
         * [DISMISS_FRACTION] of the way. Requiring both would punish the quick
         * flick that is the whole point of the gesture; requiring distance alone
         * would make a decisive throw feel ignored.
         */
        private fun release() {
            var velocity = 0.0f
            tracker?.let { t ->
                try {
                    t.computeCurrentVelocity(1000)
                    velocity = t.getYVelocity()
                } catch (ignored: Throwable) {
                }
            }
            recycle()
            dragging = false
            val travelled = panel.translationY
            val flung = velocity > minFling
            val far = travelled > dismissDistance() * DISMISS_FRACTION
            if (flung || far) {
                dismissByDrag(velocity)
            } else {
                settle()
            }
        }

        /** Returns the panel to rest after an abandoned drag. */
        private fun settle() {
            recycle()
            dragging = false
            panel.animate().cancel()
            panel.animate()
                .translationY(0.0f)
                .alpha(1.0f)
                .setDuration(Ui.D_BASE)
                .setInterpolator(Ui.ease())
                .start()
        }

        private fun recycle() {
            try {
                tracker?.recycle()
            } catch (ignored: Throwable) {
            }
            tracker = null
        }

        /** How far the panel must travel to count as thrown away. */
        private fun dismissDistance(): Float {
            val height = panel.height.toFloat()
            return if (height > 0.0f) height else Theme.dpf(c, 240.0f)
        }
    }

    /**
     * Completes a dismissal the user's own thumb started.
     *
     * Distinct from [dismiss] because the panel is already part-way down and
     * part-way faded: the standard exit animates TO a fixed 56dp offset, which
     * from a 300dp drag would pull the sheet back UP before letting it go. This
     * carries on from wherever the finger left it, and shortens the animation the
     * harder it was thrown so a decisive flick is not made to wait for a
     * leisurely fade.
     */
    private fun dismissByDrag(velocity: Float) {
        if (dismissing) {
            return
        }
        dismissing = true
        OPEN.remove(this)
        val remaining = Math.max(0.0f, panel.height - panel.translationY)
        val duration = if (velocity > 0.0f) {
            Math.max(90L, Math.min(Ui.D_BASE, (remaining / velocity * 1000.0f).toLong()))
        } else {
            Ui.D_FAST
        }
        try {
            panel.animate().cancel()
            panel.animate()
                .translationY(panel.height.toFloat())
                .alpha(0.0f)
                .setDuration(duration)
                .setInterpolator(Ui.easeOut())
                .withEndAction { hardDismiss() }
                .start()
            panel.postDelayed({ hardDismiss() }, duration + 120L)
        } catch (e: Exception) {
            hardDismiss()
        }
    }

    fun setOnDismiss(action: Runnable?) {
        if (action != null) {
            dialog.setOnDismissListener { action.run() }
        }
    }

    fun show() {
        try {
            dialog.show()
            OPEN.add(this)
            panel.translationY = Theme.dpf(c, 72.0f)
            panel.alpha = 0.0f
            // Rises on the app's shared easing curve — decisive out of the
            // gate, settling softly — so a sheet feels like the same material
            // as the drawer and the buttons.
            panel.animate()
                .translationY(0.0f)
                .alpha(1.0f)
                .setDuration(Ui.D_SLOW)
                .setInterpolator(Ui.ease())
                .start()
        } catch (e: Exception) {
        }
    }

    /**
     * Slides the sheet back down, then dismisses it.
     *
     * The panel used to animate IN but leave with a hard cut, which is the
     * single most noticeable piece of roughness in the app's motion: a surface
     * that glides on and then vanishes reads as a glitch. The exit is faster
     * than the entrance (dismissal should feel lighter than presentation) and
     * the real `dialog.dismiss()` still happens in the end action, so every
     * existing onDismiss caller fires exactly as before.
     *
     * [immediate] skips the animation for teardown paths — [dismissAll] from a
     * dying Activity must not wait on a frame callback that may never come.
     */
    fun dismiss() {
        dismiss(false)
    }

    fun dismiss(immediate: Boolean) {
        OPEN.remove(this)
        if (dismissing) {
            return
        }
        dismissing = true
        if (immediate || !dialog.isShowing) {
            hardDismiss()
            return
        }
        try {
            panel.animate().cancel()
            panel.animate()
                .translationY(Theme.dpf(c, 56.0f))
                .alpha(0.0f)
                .setDuration(Ui.D_FAST)
                .setInterpolator(Ui.easeOut())
                .withEndAction { hardDismiss() }
                .start()
            // Belt and braces: a cancelled animator drops its end action, so the
            // window would stay up forever. Never let that happen.
            panel.postDelayed({ hardDismiss() }, Ui.D_FAST + 120L)
        } catch (e: Exception) {
            hardDismiss()
        }
    }

    private fun hardDismiss() {
        try {
            dialog.dismiss()
        } catch (e: Exception) {
        }
    }

    fun isShowing(): Boolean = dialog.isShowing

    companion object {
        /**
         * Every sheet currently on screen.
         *
         * A plain [Dialog] is NOT torn down when its host Activity is
         * destroyed, and nothing here held a reference to dismiss one — so a
         * back press or a low-memory kill while the approval sheet was up (it
         * is `setCancelable(false)` and can sit there for minutes) leaked a
         * WindowManager window, logged `android.view.WindowLeaked`, and pinned
         * the dead Activity in memory for the life of the process. The
         * redelivered approval then opened a *second* sheet over the orphan.
         */
        private val OPEN = java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<Sheet, Boolean>()
        )

            /** How far down a slow drag must go before releasing dismisses. */
        private const val DISMISS_FRACTION = 0.28f

        /** How much opacity a full-length drag takes off the panel. */
        private const val DRAG_FADE = 0.4f

        /** Dismisses every open sheet — call from the host Activity's onDestroy. */
        fun dismissAll() {
            for (sheet in ArrayList(OPEN)) {
                try {
                    // Immediate: the host Activity is going away, so there may be
                    // no more frames in which to run an exit animation.
                    sheet.dismiss(true)
                } catch (ignored: Exception) {
                }
            }
            OPEN.clear()
        }
    }
}
