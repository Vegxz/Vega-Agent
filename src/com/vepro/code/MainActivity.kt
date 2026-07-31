package com.vepro.code

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * The chat screen — the Vega monochrome UI.
 *
 * The file splits cleanly into three layers, and they are maintained as three
 * layers:
 *
 *  * **View construction** — `buildUi`, `buildHeader`, `buildInputArea`,
 *    `buildDrawer`, `buildWelcome`, `permRow` and the row/card factories.
 *  * **View updating** — `refreshAppearance`, `renderAll`, `setRunning`,
 *    `refreshTitle`, `refreshChatList`, `refreshAttachStrip`,
 *    `updatePermBanner`, `showRunningIndicator`.
 *  * **Business logic** — the run / stream / approval / tool orchestration, the
 *    prefs, the permission flows, the intents and the lifecycle. This layer is
 *    load-bearing and is unchanged from the version that shipped: every
 *    hard-won guard in it (the Xiaomi/MIUI process-kill recovery, the approval
 *    dedup, the watchdog on a service that never starts, the stream flusher's
 *    ordering) is there because something broke without it.
 *
 * The visual layer is flat and strictly monochrome: a ground-coloured 56dp
 * header with no divider, a floating two-row composer, full-width transcript
 * turns with no avatars, and a near-zero motion budget — the copy-panel reveal,
 * the drawer slide and the 2dp run hairline are the whole animation inventory.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var store: ChatStore

    private var chat: Chat? = null

    // --- shell views ---
    private var attachScrollWrap: HorizontalScrollView? = null
    private var attachStrip: LinearLayout? = null
    private var chatListContainer: LinearLayout? = null
    private var composerBox: LinearLayout? = null
    /**
     * The one open tap-to-copy panel, or null. Exactly one may be open at a
     * time; it is cleared when the transcript is rebuilt, when its row detaches,
     * and on destroy — so no code path can touch a detached view.
     */
    private var openCopyPanel: LinearLayout? = null

    /**
     * The approval currently on screen, so redelivery cannot stack a second
     * modal for the same request. Cleared when the sheet is dismissed.
     */
    private var shownApproval: AgentBus.PendingApproval? = null

    /**
     * Transcript length at which the user dismissed the "continue the run" card,
     * or -1. Keeps a dismissal sticky until a new message arrives.
     */
    private var dismissedContinueAt: Int = -1

    /** The tool currently executing, so its pill survives a UI rebuild. */
    private var runningTool: String? = null
    private var runningDetail: String? = null
    private var drawerPanel: LinearLayout? = null
    private var drawerScrim: FrameLayout? = null
    private var input: EditText? = null
    private var messagesContainer: LinearLayout? = null
    private var messagesScroll: ScrollView? = null

    /**
     * The composer's model-selector chip. It is a [Ui.selectorChip], whose child
     * order is part of that factory's contract: glyph, label, chevron.
     */
    private var modePill: LinearLayout? = null

    /**
     * The label cap most recently pushed into [modePillText], in px.
     *
     * Tracked in a field because the cap is recomputed from inside a layout-change
     * listener and `TextView.setMaxWidth` has no getter to compare against —
     * writing it unconditionally would call `requestLayout` from within a layout
     * pass on every pass, forever.
     */
    private var modePillCap: Int = -1

    /** The chip's label, resolved once from `modePill.getChildAt(1)`. */
    private var modePillText: TextView? = null

    /**
     * The run mode the pill is currently painted with. Lets refreshModePill()
     * animate only on a real change — it is called on every tool step to catch
     * the engine's PLAN → ACCEPT escalation.
     */
    private var lastPillMode: String? = null

    /**
     * The storage-permission row hosted in the DRAWER. It is built once per
     * [buildUi] and lives for the tree's lifetime, so [updatePermBanner] can
     * reveal or hide it at any time.
     */
    private var permBanner: View? = null

    /**
     * The second host for the same row: the empty state. It only exists while
     * the transcript is empty AND the permission is still missing, so it is
     * nullable and re-derived by [renderAll] / [buildWelcome].
     */

    /** The storage prompt pinned under the header, or null when not owed. */
    private var headerPermRow: View? = null

    /**
     * The faint brand star behind an empty transcript.
     *
     * It is a state indicator, not decoration: full size on an empty chat,
     * smaller and higher when the keyboard takes the lower half, and gone
     * entirely once the conversation has begun — the same behaviour Grok's
     * empty state has.
     */
    private var chatWatermark: View? = null

    /** The root frame, so the watermark can be positioned against real geometry. */
    private var rootFrame: FrameLayout? = null

    /**
     * The open conversation overflow menu, so a second tap or a rebuild closes it.
     *
     * Tracked exactly like [openCopyPanel] and for the same reason: an overlay that
     * outlives the tree it was anchored to is a view floating over unrelated content.
     */
    private var chatMenu: FrameLayout? = null

    /** The empty state's suggestion block, which the watermark must stay clear of. */
    private var welcomeBlock: LinearLayout? = null

    /**
     * The soft keyboard's height in px, or 0 when it is down.
     *
     * Tracked as PIXELS rather than as a boolean on purpose. The watermark's
     * keyboard-up position is measured against the height still visible above
     * the IME, so the actual inset is the input to that sum — and tracking the
     * number means the mark also re-targets when the keyboard CHANGES height
     * (switching to an emoji panel, a taller third-party IME, a floating
     * keyboard) rather than only when it appears and disappears.
     */
    private var imeInsetPx: Int = 0

    /** True while the soft keyboard is showing; drives the watermark's size. */
    private var keyboardUp: Boolean = false

    /**
     * The mark's resting centre in px, fixed once in [buildUi].
     *
     * The mark is TOP-anchored, so this is `topMargin + markSize / 2` and it
     * never changes. Every pixel of the mark's movement is [View.setTranslationY]
     * measured from here, which is what makes the motion jump-free — see
     * [updateWatermark].
     */
    private var watermarkRestCentre: Float = 0.0f

    /** The mark's laid-out side in px, kept in step by [syncWatermarkGeometry]. */
    private var watermarkSize: Int = 0

    /** The status-bar inset in px, as last reported to the inset watcher. */
    private var statusInsetPx: Int = 0

    /**
     * The 2dp hairline under the header bar. A run is the only thing in this app
     * that takes an unknown amount of time, so it gets the one piece of
     * always-visible chrome: a sweep pinned to the bottom edge of the header,
     * instead of a spinner buried in the transcript.
     */
    private var runHairline: View? = null
    private var headerRunAnim: ValueAnimator? = null

    /**
     * Floating "jump to latest" affordance. Scrolling up through a long
     * transcript used to be a one-way trip — the only way back was to drag.
     */
    private var jumpButton: View? = null

    /**
     * Paints the status-bar strip in the header's own colour.
     *
     * `statusBarColor` alone is not enough: an app targeting SDK 35 is forced
     * edge-to-edge and the platform ignores that property, so on Android 15 the
     * strip shows whatever the root view paints there — which was [Theme.BG]
     * while the header below it is [Theme.BG_ELEV]. That is the seam. This
     * overlay lives in the root FrameLayout, is sized to the top system-window
     * inset, and therefore covers exactly the bar region on every API level.
     * When the root does *not* extend under the bar the overlay lands on the
     * header instead — same colour, so it is invisible and costs nothing.
     */
    private var statusScrim: View? = null
    private var runningIndicator: View? = null

    /** The label inside [runningIndicator], kept so its text can be handed over. */
    private var runningLabel: TextView? = null

    /** The live activity strip, the trail it shows, and the message that owns it. */
    private var trailView: TrailView? = null
    private var trailModel: Trail? = null
    private var trailOwner: Message? = null

    /** The Dynamic Workflow board for the run in flight, and the model it shows. */
    private var workflowView: WorkflowView? = null
    private var workflowBoard: Workflow? = null
    private var continueCard: View? = null
    private var sendBtn: ImageView? = null
    private var titleView: TextView? = null

    // --- live stream state ---
    private var currentContentBox: LinearLayout? = null
    private var currentStream: MarkdownRenderer.Streaming? = null
    private var lastCall: AgentEngine.ToolCall? = null

    @Volatile
    private var flushScheduled = false

    @Volatile
    private var streamPending: String? = null

    @Volatile
    private var thinkPending: String? = null

    private var uiListener: AgentBus.UiListener? = null

    private val diskExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val pending = ArrayList<Message.Attachment>()

    private var nearBottom = true
    private var prevInputLen = 0
    private var lastNight = -1

    /**
     * The palette generation this Activity's view tree was actually painted
     * with. [Theme] is a process-global singleton, so another Activity (or a
     * system night-mode flip) can swap the palette out from under a tree that
     * is still on the back stack. Comparing the live palette against
     * `Theme.DARK` cannot detect that — the global has *already* changed — so
     * every screen records what it drew with and rebuilds when it no longer
     * matches. This is what keeps chat and settings from ending up in
     * opposite themes.
     */
    private var appliedRevision = -1

    /**
     * The interface language this view tree was BUILT in.
     *
     * Compared on resume, exactly as [appliedRevision] is for the palette. Both
     * exist for the same reason: Settings mutates a process-wide global and then
     * recreates only itself, so this screen has to notice on its own that what it
     * is painted in no longer matches what is stored.
     */
    private var lastLanguage: String? = null

    /** True while a UI event arrived with no listener attached (screen off / backgrounded). */
    private var missedUiEvents = false
    private var lastVisible = ""

    /** Coalesces streamed tokens into at most one UI update every 60 ms. */
    private val flusher = Runnable {
        flushScheduled = false
        val body = streamPending
        val reasoning = thinkPending
        if (body != null && currentStream != null) {
            val parts = Think.split(body)
            val visible = AgentEngine.stripToolCalls(parts.visible)
            lastVisible = visible
            currentStream?.update(visible)
            if (Think.merge(reasoning, parts.thinking).isNotEmpty()) {
                showLiveReasoning()
            }
        } else if (!reasoning.isNullOrEmpty()) {
            showLiveReasoning()
        }
        // A turn can become a step mid-stream (the engine folds it as soon as its
        // tool call opens), so the sweep runs on the flush tick rather than waiting
        // for the message to finish.
        foldFinishedSteps()
        streamScroll()
    }

    /**
     * Brings the review row on screen as soon as reasoning starts arriving.
     *
     * Reasoning has exactly one home now: the review section. The engine writes it
     * there as a THINK row that rewrites itself in place, so the tokens themselves
     * need no help from the flusher — but the ROW only appears once the trail is
     * republished, and on a turn that reasons for several seconds before doing
     * anything else that left the screen looking idle. Binding here closes that
     * gap, so the lamp lights the moment the model starts thinking.
     *
     * There used to be a second destination: a collapsible "Model reasoning" card
     * built into the transcript whenever no strip existed yet. It is gone. Two
     * homes for one thing meant the reasoning appeared above or inside the review
     * box depending on timing and on whether the turn happened to call a tool.
     */
    private fun showLiveReasoning() {
        val owner = trailOwner ?: return
        val model = trailModel ?: return
        if (model.running && model.hasThoughts()) {
            refreshTrail(owner)
        }
    }

    private fun interface ApprovalResult {
        fun decided(approved: Boolean)
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        prefs = Prefs(this)
        NetworkPolicy.applyPrefs(prefs)
        Fa.apply(this)
        store = ChatStore(this)
        Theme.init(this)
        Theme.applyFromPrefs(this, prefs)
        appliedRevision = Theme.revision
        lastLanguage = prefs.language()
        // Everything the PLATFORM draws — text cursor, selection handles and
        // highlight, the ActionMode bar, overscroll glow, Toast and Dialog
        // chrome — comes from the activity theme, not from our palette. Pick
        // the matching one before any view exists.
        setTheme(if (Theme.DARK) R.style.AppTheme else R.style.AppThemeLight)
        lastNight = currentNight()
        applyWindowChrome()
        buildUi()
        buildListener()

        val liveId = if (AgentBus.isBusy()) AgentBus.activeChatId else null
        val live = AgentBus.liveChat
        var resolved: Chat? = if (liveId != null && live != null && liveId == live.id) {
            live
        } else {
            // Prefer the id the framework handed back on a save/restore, then the
            // persisted pointer. (Both survive process death; the bundle also
            // survives a same-process Activity recreate.)
            val savedId = bundle?.getString(STATE_CHAT_ID)?.takeIf { it.isNotEmpty() }
                ?: prefs.lastChatId()
            if (savedId.isEmpty()) null else store.load(savedId)
        }
        if (resolved == null) {
            // Xiaomi/MIUI hardening. If the saved conversation could not be
            // loaded — a transient filesystem read right after the OEM killed
            // our process — do NOT fabricate a fresh empty chat here. That path
            // used to run through setChat(), overwrite lastChatId with the new
            // empty chat, and make the app "forget" every past conversation.
            // Instead fall back to the most-recently-updated chat on disk, and
            // only create a brand-new one when there genuinely are none.
            val existing = store.list()
            resolved = if (existing.isNotEmpty()) existing[0] else store.create()
        }
        setChat(resolved)
        // First launch asks which language to read the app in, before anything
        // else has a chance to say something the user may not understand. The
        // permission prompts that follow are deferred until it is answered.
        if (!prefs.languageChosen()) {
            ui.postDelayed({ showLanguagePicker() }, 300L)
            return
        }
        ensureStorageAccess(false)
        requestNotifPermission()
        ui.postDelayed({ maybeBatteryPrompt() }, 1400L)
    }

    /**
     * The one-time language chooser.
     *
     * Shown before any other prompt, because every other prompt is a sentence in
     * a language we have not established the user reads. Each option is written
     * in its OWN script rather than translated — "English" and "فارسی" — since
     * the entire job of this screen is to be recognisable to someone who reads
     * either one, and a Persian speaker should not have to parse the word
     * "Persian" to find their language.
     *
     * Not cancellable. A picker dismissed by accident would leave the question
     * unanswered and ask again on the next launch, which is a worse first
     * impression than the question itself.
     */
    private fun showLanguagePicker() {
        if (isFinishing || isDestroyed) {
            return
        }
        val sheet = Sheet(this)
        sheet.setCancelable(false)
        // Bilingual header — the only text in the app that must land for a reader
        // of either language, so it says it twice rather than choosing.
        sheet.header(
            "globe",
            "Choose your language\nزبان خود را انتخاب کنید",
            "You can change this later in Settings.\n" +
                "بعداً می‌توانید از تنظیمات تغییرش دهید."
        )
        addLanguageChoice(sheet, "en", "English", "Full English interface")
        addLanguageChoice(
            sheet, "fa", "فارسی",
            "رابط کامل فارسی و راست‌چین"
        )
        sheet.show()
    }

    /**
     * One row of the chooser, laid out in the direction of the language it offers.
     *
     * Each row sets its OWN `layoutDirection` — the English row reads left to
     * right and the Persian row right to left, in the same sheet. That is not
     * decoration: it is a preview. The choice being made is which way the whole
     * interface will read, and showing it in the row is the most honest way to
     * ask.
     */
    private fun addLanguageChoice(
        sheet: Sheet,
        value: String,
        name: String,
        note: String
    ) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutDirection = if (value == "fa") {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
        val base = Theme.sheetRow(Theme.R_MD, this)
        row.background = Theme.rippleColorOver(base, Theme.ACCENT, Theme.R_MD, this)
        val pad = Theme.dp(this, 14.0f)
        row.setPaddingRelative(pad, pad, pad, pad)

        // A two-letter script tag rather than a flag or a glyph: a flag names a
        // country and these are languages, and no icon in the set says "Persian".
        val badge = LinearLayout(this)
        badge.gravity = Gravity.CENTER
        badge.background = Theme.iconChip(Theme.ACCENT, 11.0f, this)
        badge.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val tag = TextView(this)
        tag.text = if (value == "fa") "فا" else "EN"
        tag.setTextColor(Theme.ACCENT_TEXT)
        tag.textSize = Ui.Type.META
        tag.typeface = Theme.uiBold()
        badge.addView(tag)
        val badgeBox = Theme.dp(this, 38.0f)
        val badgeLp = LinearLayout.LayoutParams(badgeBox, badgeBox)
        badgeLp.marginEnd = Theme.dp(this, 13.0f)
        row.addView(badge, badgeLp)

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = name
        title.setTextColor(Theme.TEXT)
        title.textSize = Ui.Type.BODY
        title.typeface = Theme.uiSemi()
        title.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        Ui.rowLabel(title)
        texts.addView(title, Ui.matchWrap())
        val hint = TextView(this)
        hint.text = note
        hint.setTextColor(Theme.TEXT_FAINT)
        hint.textSize = Ui.Type.MICRO
        hint.typeface = Theme.ui()
        hint.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        Ui.rowLabel(hint)
        texts.addView(hint, Ui.matchWrap())
        row.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        // Points the way the row reads, so it reinforces the direction rather
        // than fighting it.
        val arrow = ImageView(this)
        arrow.setImageDrawable(
            Icons.of(
                if (value == "fa") "chevron-left" else "chevron-right",
                Theme.TEXT_FAINT,
                Ui.STROKE
            )
        )
        arrow.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val arrowSize = Theme.dp(this, 17.0f)
        row.addView(arrow, LinearLayout.LayoutParams(arrowSize, arrowSize))

        row.contentDescription = name
        Ui.pressScale(row)
        row.setOnClickListener {
            sheet.dismiss()
            applyChosenLanguage(value)
        }

        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowLp.bottomMargin = Theme.dp(this, 10.0f)
        sheet.body.addView(row, rowLp)
    }

    /**
     * Persists the choice and rebuilds the interface in it.
     *
     * In place through [refreshAppearance], NOT `recreate()`. MainActivity
     * deliberately never recreates itself — an Activity restart on MIUI while a
     * run is streaming can tear down callbacks even though the service survives,
     * and a source contract enforces the ban. Settings may call `recreate()`
     * because nothing is streaming there.
     *
     * The chosen flag is written FIRST and with `commit()`, so even a process
     * killed mid-rebuild never asks twice.
     */
    private fun applyChosenLanguage(value: String) {
        try {
            prefs.setLanguage(value)
            prefs.setLanguageChosen()
            Fa.apply(this)
        } catch (ignored: Throwable) {
        }
        if (isFinishing || isDestroyed) {
            return
        }
        try {
            refreshAppearance()
        } catch (ignored: Throwable) {
        }
        // Deferred on first launch so they arrive in the language just chosen.
        try {
            ensureStorageAccess(false)
            requestNotifPermission()
            ui.postDelayed({ maybeBatteryPrompt() }, 1400L)
        } catch (ignored: Throwable) {
        }
    }

    // =====================================================================
    // Shell
    // =====================================================================

    private fun buildUi() {
        val rootFrame = FrameLayout(this)
        this.rootFrame = rootFrame
        rootFrame.layoutDirection = Lang.direction(this)
        rootFrame.setBackgroundColor(Theme.BG)

        // A faint Vega star behind the conversation.
        //
        // Added FIRST so it sits at the bottom of the z-order: every message,
        // card and control draws over it, and it is not clickable, so it can
        // never intercept a tap. Sized to a fraction of the screen width and
        // held at a very low alpha — present enough to brand an empty screen,
        // quiet enough that body text over it stays perfectly legible.
        val watermark = ImageView(this)
        watermark.setImageDrawable(BrandMark(Theme.TEXT))
        watermark.alpha = if (Theme.DARK) WATERMARK_ALPHA_DARK else WATERMARK_ALPHA_LIGHT
        watermark.isClickable = false
        watermark.isFocusable = false
        watermark.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // Same short-edge basis as syncWatermarkGeometry, which re-derives this on
        // every resize — the two must agree or the first frame jumps.
        val markSize = (Math.min(
            resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels
        ) * 0.52f).toInt()
        val markLp = FrameLayout.LayoutParams(markSize, markSize)
        // TOP-anchored, NOT centred — and this is the whole reason the keyboard
        // animation is smooth.
        //
        // `adjustResize` shrinks this frame when the keyboard opens. A CENTRED
        // child is re-centred inside the smaller frame on that same layout pass,
        // which moved the mark up by half the keyboard's height INSTANTLY, with
        // no animation, and the translationY tween then animated a second, much
        // smaller distance on top of it. Most of the travel was an un-animated
        // jump, and worse, it only happened on the API levels that still resize
        // (Android 15 forces edge-to-edge, so there the frame does not shrink and
        // the mark simply stayed behind the keyboard instead).
        //
        // A TOP-anchored child cannot be moved by a height change. So the resting
        // position is pinned here once, every pixel of motion is translationY,
        // and the result is identical on API 24 and on API 35.
        markLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        rootFrame.addView(watermark, markLp)
        chatWatermark = watermark
        watermark.translationY = 0.0f
        syncWatermarkGeometry()

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.fitsSystemWindows = true
        rootFrame.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        column.addView(buildHeader())

        // The storage prompt sits at the VERY TOP, directly under the header.
        //
        // It was previously only in the drawer and the empty state, so a user who
        // never scrolled to the bottom or opened the drawer could not see why the
        // agent had no file access. It is the first thing that needs answering,
        // so it is the first thing on the screen — and it is only built when the
        // permission is actually missing, so a granted install pays nothing.
        if (!hasStorageAccess()) {
            val topPerm = permRow()
            headerPermRow = topPerm
            val topPermLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            topPermLp.topMargin = Theme.dp(this, Ui.Space.S)
            // Inset from both edges. The card was added with a top margin only, so
            // its rounded corners and its rail ran flush into the sides of the
            // screen — the one element in the app that looked like it had escaped
            // the layout. Matches the transcript's own horizontal padding.
            topPermLp.marginStart = Theme.dp(this, Ui.Space.L)
            topPermLp.marginEnd = Theme.dp(this, Ui.Space.L)
            column.addView(topPerm, topPermLp)
        }

        val scroll = ScrollView(this)
        messagesScroll = scroll
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false

        val messages = LinearLayout(this)
        messagesContainer = messages
        messages.orientation = LinearLayout.VERTICAL
        // Relative, because the top/bottom insets differ and the jump-to-latest
        // button floats over the generous bottom pad.
        // Side padding grows on a wide screen, which is how the conversation column
        // gets a readable measure without needing a nested container.
        //
        // On a phone this is exactly the 16dp it has always been: `gutter` only
        // exceeds it once the screen is wider than the column plus two gutters,
        // which no phone is. On a 10-inch tablet in landscape it becomes a couple
        // of hundred dp a side and the transcript reads as a centred column instead
        // of running the full width of the glass — which is what it did, at 16sp,
        // because this was the one surface in the app with no width cap at all.
        val gutter = transcriptGutter()
        messages.setPaddingRelative(
            gutter, Theme.dp(this, Ui.Space.S),
            gutter, Theme.dp(this, 24.0f)
        )
        scroll.addView(messages)
        // The transcript's own geometry is what the watermark is centred in, so any
        // change to it — the keyboard opening or closing, the composer growing to
        // several lines, the storage prompt appearing, a rotation, a fold — has to
        // re-place the mark. Listening to the layout covers all of them at once,
        // including the cases no window inset ever reports.
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateWatermark()
        }
        // Touching the conversation releases the composer.
        //
        // The focus ring is drawn from the field's focus state, and nothing ever
        // took that focus away: tapping the transcript, a message, or anywhere else
        // outside the composer left the field focused, so the ring — a full white
        // outline in dark mode, black in light — stayed lit around a box the user
        // had visibly stopped using. Releasing focus on touch is also what dismisses
        // the keyboard, which is the same gesture users already expect here.
        //
        // Not a click listener: the transcript scrolls, and a scroll must release
        // the focus too. ACTION_DOWN, and the event is never consumed, so selection
        // and scrolling behave exactly as before.
        scroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                releaseComposerFocus()
            }
            false
        }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val child = scroll.getChildAt(0)
            if (child != null) {
                nearBottom =
                    child.bottom - (scroll.height + scrollY) < Theme.dp(this, 150.0f)
            }
            updateJumpButton()
        }
        // The transcript and its floating affordance share a host, so the
        // jump button is positioned against the SCROLL area rather than the
        // raw window. Pinning it to the root would have put it a fixed 96dp
        // above the true window edge — i.e. on top of the composer on any
        // device whose bottom inset is not the one that number was picked for.
        val scrollHost = FrameLayout(this)
        scrollHost.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val jump = buildJumpButton()
        jumpButton = jump
        val jumpLp = FrameLayout.LayoutParams(
            Theme.dp(this, 38.0f), Theme.dp(this, 38.0f)
        )
        jumpLp.gravity = Gravity.BOTTOM or Gravity.END
        jumpLp.bottomMargin = Theme.dp(this, 14.0f)
        jumpLp.marginEnd = Theme.dp(this, 14.0f)
        jump.visibility = View.GONE
        jump.alpha = 0.0f
        scrollHost.addView(jump, jumpLp)

        column.addView(
            scrollHost,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
        )
        column.addView(buildInputArea())

        // Status-bar strip, painted the same colour as the header bar it sits on.
        //
        // Added HERE — above the chat, but BELOW the drawer scrim that follows.
        // It used to be the last child of the frame, i.e. on top of everything,
        // and because it paints an opaque fill that meant opening the drawer left
        // a bright undimmed band across the top of the screen while the entire
        // rest of the window dimmed behind the scrim.
        //
        // Nothing is lost by moving it: the drawer keeps its own content clear of
        // the status bar through the top padding `installInsetWatcher` applies to
        // it, so the strip never needed to cover the drawer in the first place.
        val barStrip = View(this)
        statusScrim = barStrip
        barStrip.setBackgroundColor(Theme.BG_ELEV)
        val stripLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        stripLp.gravity = Gravity.TOP
        rootFrame.addView(barStrip, stripLp)

        val scrim = FrameLayout(this)
        drawerScrim = scrim
        scrim.setBackgroundColor(Theme.SCRIM)
        scrim.visibility = View.GONE
        scrim.isClickable = true
        scrim.setOnClickListener { closeDrawer() }
        rootFrame.addView(
            scrim,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val drawer = buildDrawer()
        drawerPanel = drawer
        val drawerLp = FrameLayout.LayoutParams(
            Math.min(
                Theme.dp(this, 330.0f),
                (resources.displayMetrics.widthPixels * 0.86f).toInt()
            ),
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        drawerLp.gravity = Gravity.START
        drawer.visibility = View.GONE
        rootFrame.addView(drawer, drawerLp)

        installInsetWatcher(rootFrame, barStrip)

        setContentView(rootFrame)
    }

    /**
     * Sizes [scrim] to the top system-window inset.
     *
     * `column.fitsSystemWindows` already keeps the chat itself clear of the
     * bars; all this adds is the *paint* for the strip the root would otherwise
     * fill with [Theme.BG]. The insets are only read — never consumed — so the
     * normal `fitsSystemWindows` pass downstream is untouched.
     */
    private fun installInsetWatcher(root: FrameLayout, scrim: View) {
        root.setOnApplyWindowInsetsListener { _, insets ->
            var top = 0
            try {
                top = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.statusBars()).top
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop
                }
            } catch (e: Exception) {
                top = 0
            }
            // The navigation bar matters too. targetSdk 35 forces edge-to-edge on
            // Android 15, so with 3-button navigation the drawer's bottom rows
            // (Settings, Telegram) sat UNDER the bar and were hard to tap.
            var bottom = 0
            try {
                bottom = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
            } catch (e: Exception) {
                bottom = 0
            }
            // How tall is the soft keyboard? The watermark shrinks and lifts to
            // sit in whatever height is left above it.
            //
            // API 30+ reports the IME as its own inset, which is exactly the
            // number wanted. Below that there is no IME inset — but there are two
            // bottom insets, and the DIFFERENCE between them is the keyboard:
            // `systemWindowInsetBottom` grows when the IME opens (that is what
            // adjustResize does), while `stableInsetBottom` is the navigation bar
            // alone and does not move. Subtracting one from the other is the only
            // way to get a keyboard HEIGHT rather than a yes/no on these levels.
            //
            // The old code compared the raw bottom inset against a 120dp
            // threshold, which answered a different question (is something big
            // down there) and could not tell a tall keyboard from a short one.
            var ime = 0
            try {
                ime = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.ime()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    val gap = insets.systemWindowInsetBottom - insets.stableInsetBottom
                    // A few px of slack: rounding must not read as a 3px keyboard.
                    if (gap > Theme.dp(this, 48.0f)) gap else 0
                }
            } catch (e: Exception) {
                ime = 0
            }
            if (ime < 0) {
                ime = 0
            }
            imeInsetPx = ime
            keyboardUp = ime > 0
            // Unconditionally, every pass.
            //
            // This used to be gated on `ime != imeInsetPx`, which looked like a
            // sensible way to avoid redundant work and was in fact the bug: the
            // field outlives buildUi(), so after any rebuild while the keyboard was
            // up (a theme flip, a language change) the fresh watermark started at
            // rest, the next inset pass reported the SAME ime height, the gate
            // rejected it — and the mark stayed full-size behind the keyboard for
            // as long as the keyboard was open.
            //
            // updateWatermark is idempotent and skips its own animation when
            // nothing moved, so the guard bought nothing it did not also break.
            updateWatermark()
            statusInsetPx = top
            if (scrim.layoutParams.height != top) {
                scrim.layoutParams.height = top
                scrim.requestLayout()
            }
            // Keep the drawer's own content out from under the bar too. It is a
            // sibling of `column`, and ViewGroup hands the insets to children in
            // order — `column` (index 0) consumes them, so by the time the
            // dispatch reached the drawer there was nothing left and its
            // fitsSystemWindows was silently a no-op. Pad it here instead.
            drawerPanel?.let { panel ->
                if (panel.paddingTop != top || panel.paddingBottom != bottom) {
                    panel.setPadding(panel.paddingLeft, top, panel.paddingRight, bottom)
                }
            }
            insets
        }
        root.requestApplyInsets()
    }

    /**
     * The header: flat, ground-coloured, 56dp, and nothing else.
     *
     * There is no elevated bar, no divider and no second storey. The wordmark
     * lockup, the mode pill, the theme toggle and the gear button that used to
     * live here are all gone — the pill moved into the composer, the rest into
     * the drawer and Settings. What is left is the ChatGPT/Grok arrangement: a
     * menu button, the conversation's own title, and a new-chat button.
     *
     * The 2dp run hairline is the shell's second child rather than an overlay in
     * the root frame, so it is pinned to the bar's own bottom edge for free and
     * cannot drift when the header height changes.
     */
    private fun buildHeader(): LinearLayout {
        val shell = LinearLayout(this)
        shell.orientation = LinearLayout.VERTICAL
        shell.setBackgroundColor(Theme.BG)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.minimumHeight = Theme.dp(this, 56.0f)
        // Relative, not physical: the two buttons are symmetric here, but the
        // bar hosts a weighted centre child and the app's rule is that every
        // horizontal inset mirrors.
        bar.setPaddingRelative(Theme.dp(this, 6.0f), 0, Theme.dp(this, 6.0f), 0)

        // circleButton returns a view that already carries LinearLayout params,
        // which is exactly what this parent wants.
        bar.addView(
            Ui.circleButton(this, "menu", 40.0f, 20.0f, Theme.TEXT, 0) { openDrawer() }
        )

        // The real header title at last. It used to be a detached, never-added
        // TextView that existed only so the `titleView?.text = …` writes
        // scattered through the run logic would be safe no-ops; now those writes
        // actually paint something and refreshTitle() does what its name says.
        val title = TextView(this)
        titleView = title
        title.textSize = Ui.Type.HEAD
        title.typeface = Theme.uiSemi()
        title.setTextColor(Theme.TEXT)
        title.gravity = Gravity.CENTER
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.END
        title.typeface = Theme.uiSemi()
        // A brand name, not user content: forced left-to-right so it reads the same
        // in both languages, and centred in the bar it sits in. The two circle
        // buttons flanking it are the same width, so the weighted centre column is
        // the true centre of the header.
        title.textDirection = View.TEXT_DIRECTION_LTR
        title.textAlignment = View.TEXT_ALIGNMENT_CENTER
        bar.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        // Ui.circleButton derives its contentDescription from the glyph, and the
        // "plus" glyph is shared with the composer's attach button — so it reads
        // "Add". Correct there, wrong for the one control in the app that starts
        // a conversation, which TalkBack must announce as "New chat".
        val newChatButton =
            Ui.circleButton(this, "plus", 40.0f, 20.0f, Theme.TEXT, 0) { startNewChat() }
        newChatButton.contentDescription = Fa.NEW_CHAT
        bar.addView(newChatButton)

        shell.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // The run hairline. Hidden until a run starts; the gradient inside it
        // fades to transparent at both ends, so translating the whole strip
        // across the bar reads as a sweep of light rather than a bar sliding.
        val hairline = View(this)
        runHairline = hairline
        hairline.visibility = View.GONE
        hairline.background = Theme.runBar(this)
        hairline.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}

            override fun onViewDetachedFromWindow(view: View) {
                headerRunAnim?.cancel()
                headerRunAnim = null
            }
        })
        shell.addView(
            hairline,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 2.0f)
            )
        )
        return shell
    }

    /** Shows or hides the header run sweep. */
    private fun setHeaderBusy(busy: Boolean) {
        val hairline = runHairline ?: return
        headerRunAnim?.cancel()
        headerRunAnim = null
        if (!busy) {
            if (hairline.visibility != View.VISIBLE) {
                return
            }
            // D_BASE and the leaving curve, like every other exit in the app.
            // This was the only raw duration in the file and the only animation
            // with no interpolator at all.
            hairline.animate().alpha(0.0f)
                .setDuration(Ui.D_BASE)
                .setInterpolator(Ui.easeOut())
                .withEndAction {
                    hairline.visibility = View.GONE
                    // Park it back at rest, or the next run would start its
                    // sweep from wherever the previous one was cancelled.
                    hairline.translationX = 0.0f
                }.start()
            return
        }
        hairline.animate().cancel()
        hairline.visibility = View.VISIBLE
        hairline.alpha = 1.0f
        hairline.translationX = 0.0f
        val animator = ValueAnimator.ofFloat(0.0f, 1.0f)
        animator.duration = 1150L
        animator.repeatCount = ValueAnimator.INFINITE
        animator.interpolator = android.view.animation.LinearInterpolator()
        // Travels in the READING direction: left to right in English, right to left
        // in Persian. The offset itself is physical — translationX does not resolve
        // against the layout direction — so the fraction is inverted rather than the
        // geometry. The parent clips whatever hangs over either end.
        val rtl = Lang.mirrored(this)
        animator.addUpdateListener { value ->
            val width = hairline.width
            if (width > 0) {
                val fraction = value.animatedValue as Float
                val progress = if (rtl) 1.0f - fraction else fraction
                hairline.translationX = -width.toFloat() + 2.0f * width * progress
            }
        }
        animator.start()
        headerRunAnim = animator
    }

    /**
     * The jump-to-latest button: a quiet outlined disc that fades in whenever
     * the newest output is off-screen.
     *
     * It used to be a gradient accent chip on 8dp of elevation. On a transcript
     * that is otherwise flat and monochrome that read as the loudest control on
     * the page — for an affordance that only says "scroll down". A hairline
     * disc on the card surface, with no shadow at all, is enough.
     */
    private fun buildJumpButton(): View {
        val button = ImageView(this)
        button.setImageDrawable(Icons.of("arrow-down", Theme.TEXT, Ui.STROKE))
        button.scaleType = ImageView.ScaleType.FIT_CENTER
        button.contentDescription = "Jump to latest"
        val pad = Theme.dp(this, 11.0f)
        button.setPadding(pad, pad, pad, pad)
        // BORDER_HI and a 2dp lift, because this is the one control in the app
        // that genuinely floats.
        //
        // It was SURFACE + BORDER at elevation 0, which on the light palette is a
        // 1.05:1 disc behind a 1.11:1 hairline, on a page it barely differs from —
        // so nothing but the arrow glyph was visible and the affordance read as a
        // stray arrow hovering over the transcript. It also overlaps scrolling
        // content, which is precisely the case elevation exists for: the shadow is
        // what says "above", and no other surface here competes with it.
        button.background = Theme.rippleOver(
            Theme.roundStroke(Theme.SURFACE, Theme.BORDER_HI, Theme.R_PILL, 1, this),
            Theme.R_PILL, this
        )
        Theme.elevate(button, 2.0f, Theme.R_PILL, false)
        button.setOnClickListener {
            nearBottom = true
            scrollToBottom()
            updateJumpButton()
        }
        Ui.pressScale(button)
        return button
    }

    /** Fades the jump button in when the user has scrolled away from the tail. */
    private fun updateJumpButton() {
        val button = jumpButton ?: return
        val wanted = !nearBottom && (messagesContainer?.childCount ?: 0) > 0
        val showing = button.visibility == View.VISIBLE
        if (wanted == showing) {
            return
        }
        button.animate().cancel()
        if (wanted) {
            button.visibility = View.VISIBLE
            button.alpha = 0.0f
            button.scaleX = 0.7f
            button.scaleY = 0.7f
            button.animate().alpha(1.0f).scaleX(1.0f).scaleY(1.0f)
                .setDuration(Ui.D_BASE).setInterpolator(Ui.spring()).start()
        } else {
            button.animate().alpha(0.0f).scaleX(0.7f).scaleY(0.7f)
                .setDuration(Ui.D_FAST).setInterpolator(Ui.easeOut())
                .withEndAction { button.visibility = View.GONE }.start()
        }
    }

    /**
     * The storage-permission prompt, as ONE factory with TWO hosts.
     *
     * It used to be a banner welded to the top of the chat column, which is the
     * worst place for it: it pushed the transcript down on every launch until
     * the permission was granted, and it was the only route to the grant flow.
     * Now the drawer always hosts one (so it is reachable from anywhere) and the
     * empty state conditionally hosts a second (so a user who never opens the
     * drawer still sees it). Both are plain rows on a [Ui.railPanel], with the
     * `"alert"`-family treatment the monochrome system uses for anything that
     * needs attention: a 2dp start rail, an outline glyph, and weight.
     *
     * The initial visibility is the state the permission is ACTUALLY in, not a
     * hard-coded GONE. buildUi() runs again on every theme and language change,
     * and a hard-coded GONE meant switching the theme while access was still
     * ungranted made the prompt vanish until the app was restarted — with no
     * other way to reach it.
     */
    private fun permRow(): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        // A real card, not a rail on the page ground.
        //
        // The 2dp start rail was the only thing marking this out, which is the
        // treatment a quiet inline note gets — and this is a request for the most
        // consequential permission the app asks for. A filled SURFACE_2 panel with a
        // hairline is the same shape every other object in this interface uses, so it
        // reads as a deliberate piece of the screen rather than as text that has
        // escaped the layout. Insetting it fixed where it sat; this fixes what it is.
        card.background = Theme.roundStroke(
            Theme.SURFACE_2, Theme.BORDER_HI, Theme.R_CARD, 1, this
        )
        Ui.roundClip(card, Theme.R_CARD)
        card.setPaddingRelative(
            Theme.dp(this, Ui.Space.L), Theme.dp(this, Ui.Space.L),
            Theme.dp(this, Ui.Space.L), Theme.dp(this, Ui.Space.L)
        )

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.TOP

        // The glyph gets its own chip, so the card opens on an object rather than on
        // a loose mark beside a paragraph.
        val badge = Ui.iconBadge(this, "lock", Theme.TEXT_MUTED, 34.0f, 18.0f, Theme.R_SM)
        val badgeLp = LinearLayout.LayoutParams(
            Theme.dp(this, 34.0f), Theme.dp(this, 34.0f)
        )
        badgeLp.marginEnd = Theme.dp(this, Ui.Space.M)
        head.addView(badge, badgeLp)

        val stack = Ui.column(this)

        val title = TextView(this)
        title.text = Fa.PERM_TITLE
        title.textSize = Ui.Type.LABEL
        title.typeface = Theme.uiSemi()
        title.setTextColor(Theme.TEXT)
        Ui.rowLabel(title)
        stack.addView(title, Ui.matchWrap())

        val text = TextView(this)
        text.text = Fa.PERM_MSG
        text.textSize = Ui.Type.META
        // The explanation is prose, so it is set as prose: regular weight, muted, and
        // with the leading a two-line paragraph needs. It used to be SEMIBOLD full-ink
        // body copy, which is why the card shouted.
        text.typeface = Theme.ui()
        text.setTextColor(Theme.TEXT_MUTED)
        text.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        Ui.rowLabel(text)
        val textLp = Ui.matchWrap()
        textLp.topMargin = Theme.dp(this, 2.0f)
        stack.addView(text, textLp)

        head.addView(
            stack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        card.addView(
            head,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // Full width rather than beside the message: this row also lives in a
        // 330dp drawer, where a trailing pill squeezed the text to two words.
        val grant = Ui.primaryPill(this, Fa.PERM_GRANT, "lock") {
            ensureStorageAccess(true)
        }
        val grantLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        grantLp.topMargin = Theme.dp(this, Ui.Space.L)
        card.addView(grant, grantLp)

        card.visibility = if (hasStorageAccess()) View.GONE else View.VISIBLE
        return card
    }

    /**
     * The composer: one floating card holding two rows, Grok-style.
     *
     * Row 1 is the EditText. Row 2 is the tool strip: `+`, the model-selector
     * chip, a spacer, `@`, and the solid send button. Splitting them is what
     * lets the field grow to six lines without the buttons drifting up with it,
     * and it is why the mode pill could leave the header — it belongs next to
     * the thing it modifies.
     *
     * The EditText is a DIRECT child of the card, at exactly one level of depth.
     * That is load-bearing: the focus listener resolves the view to repaint from
     * `view.parent`, not from the [composerBox] field, because buildUi()
     * reassigns that field before setContentView detaches the old tree — and the
     * detach fires the OLD field's onFocusChange(false), which then stamped the
     * unfocused background onto the brand-new composer while it actually had
     * focus and the keyboard was up. Nesting the field one level deeper would
     * silently re-break that.
     */
    private fun buildInputArea(): LinearLayout {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.setBackgroundColor(Theme.BG)
        val padH = Theme.dp(this, 16.0f)
        wrap.setPadding(padH, 0, padH, Theme.dp(this, 10.0f))

        val attachScroll = HorizontalScrollView(this)
        attachScrollWrap = attachScroll
        attachScroll.isHorizontalScrollBarEnabled = false
        val strip = LinearLayout(this)
        attachStrip = strip
        strip.orientation = LinearLayout.HORIZONTAL
        attachScroll.addView(strip)
        attachScroll.visibility = View.GONE
        val attachLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        attachLp.bottomMargin = Theme.dp(this, Ui.Space.S)
        wrap.addView(attachScroll, attachLp)

        val composer = LinearLayout(this)
        composerBox = composer
        composer.orientation = LinearLayout.VERTICAL
        composer.background = composerBg(false)
        if (Build.VERSION.SDK_INT >= 21) {
            // 3dp rather than 2. The composer is the one control that floats over
            // the transcript, and at 2dp its shadow was thin enough to read as a
            // slightly dirty edge rather than as lift — the shape looked stuck to
            // the page instead of resting on it. 3dp with the same neutral inks
            // below is the smallest step that reads as a floating surface, and it
            // stays well under the elevation the sheets use, so nothing about the
            // depth order changes.
            composer.elevation = Theme.dpf(this, 3.0f)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            // Neutral black washes. The old inks were an accent tint, which on a
            // near-white monochrome page painted a coloured rim around the one
            // control the user looks at most.
            composer.outlineAmbientShadowColor = if (Theme.DARK) 0x40000000 else 0x14000000
            composer.outlineSpotShadowColor = if (Theme.DARK) 0x66000000 else 0x24000000
        }
        // Space.M all round, on the scale. The four off-scale numbers here were the
        // reason the composer's controls sat closer to its bottom edge than to its
        // sides — a 2dp asymmetry that reads as the row having slipped.
        composer.setPadding(
            Theme.dp(this, Ui.Space.M), Theme.dp(this, Ui.Space.M),
            Theme.dp(this, Ui.Space.M), Theme.dp(this, Ui.Space.M)
        )

        val field = EditText(this)
        field.typeface = Theme.ui()
        input = field
        field.hint = Fa.INPUT_HINT
        field.setHintTextColor(Theme.TEXT_FAINT)
        field.setTextColor(Theme.TEXT)
        field.textSize = Ui.Type.BODY
        field.background = null
        field.maxLines = 6
        field.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        field.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        field.setPadding(
            Theme.dp(this, 2.0f), Theme.dp(this, Ui.Space.XS),
            Theme.dp(this, 2.0f), Theme.dp(this, Ui.Space.S)
        )
        field.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        // Resolve the box from the callback's OWN view, not the `composerBox`
        // field — see the KDoc above. The field is exactly one level up.
        field.setOnFocusChangeListener { view, hasFocus ->
            (view.parent as? LinearLayout)?.background = composerBg(hasFocus)
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(editable: Editable?) {
                val text = editable?.toString() ?: ""
                // A freshly typed "@" at a word boundary opens the file browser.
                if (text.length == prevInputLen + 1) {
                    val caret = field.selectionStart
                    if (caret > 0 && caret <= text.length && text[caret - 1] == '@') {
                        var atBoundary = true
                        if (caret != 1) {
                            val before = text[caret - 2]
                            if (before != '\n' && before != ' ') {
                                atBoundary = false
                            }
                        }
                        if (atBoundary) {
                            openFileBrowser()
                        }
                    }
                }
                prevInputLen = text.length
                updateSendAvailability()
            }
        })
        composer.addView(
            field,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        tools.gravity = Gravity.CENTER_VERTICAL

        // circleButton hands back a view that already carries LinearLayout
        // params sized to its own box, so these two go in as-is.
        tools.addView(
            Ui.circleButton(this, "plus", 36.0f, 19.0f, Theme.TEXT_MUTED, Theme.SURFACE_2) {
                pickAttachment(false)
            }
        )

        val pill = Ui.selectorChip(this, "zap", modeLabel(), 34.0f) { showModeSheet() }
        modePill = pill
        // Child 1 by the selectorChip contract: the glyph is always present (it
        // is merely GONE when there is no icon), so the label never moves.
        modePillText = pill.getChildAt(1) as? TextView
        val pillLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        pillLp.marginStart = Theme.dp(this, Ui.Space.S)
        tools.addView(pill, pillLp)
        refreshModePill()

        val spacer = View(this)
        tools.addView(spacer, LinearLayout.LayoutParams(0, 1, 1.0f))

        // The pill's label is the only elastic thing in this row, so it is the only
        // thing that can keep the send button on screen — and its cap is therefore
        // derived from the row's OWN measured width, on every layout pass.
        //
        // It used to be capped at a build-time 28% of the screen, while the single
        // weighted child was the spacer. Two failures followed. On a narrow window
        // the fixed boxes (36dp plus, 36dp at, 40dp send, plus margins — ~128dp
        // before the pill is measured at all) plus a 28% label could exceed the
        // row: the spacer collapsed to zero, the overflow had nowhere left to go,
        // and the send button was pushed off the end edge. And because
        // `onConfigurationChanged` deliberately does not rebuild on a resize, the
        // cap kept a full-screen value after a split-screen drag that invalidated
        // it — so the clip could persist for the rest of the session.
        //
        // Measuring against the row cannot be stale and cannot exceed the space
        // actually left over.
        tools.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val label = modePillText
            if (label != null) {
                val fixed = Theme.dp(this, 36.0f + 36.0f + 40.0f + Ui.Space.S * 3.0f)
                val room = (right - left) - fixed - Theme.dp(this, Ui.Space.M)
                // A floor, so a very narrow window ellipsizes the label instead of
                // collapsing the chip into an unreadable sliver.
                val cap = Math.max(Theme.dp(this, 52.0f), room)
                if (cap != modePillCap) {
                    modePillCap = cap
                    label.maxWidth = cap
                }
            }
        }

        tools.addView(
            Ui.circleButton(this, "at", 36.0f, 18.0f, Theme.TEXT_MUTED, Theme.SURFACE_2) {
                openFileBrowser()
            }
        )

        val send = ImageView(this)
        sendBtn = send
        send.setImageDrawable(Icons.of("arrow-up", Theme.ON_ACCENT, Ui.STROKE))
        send.scaleType = ImageView.ScaleType.FIT_CENTER
        send.contentDescription = Fa.SEND
        send.isClickable = true
        send.isFocusable = true
        val sendBox = Theme.dp(this, 40.0f)
        val sendPad = Theme.dp(this, 10.0f)
        send.setPadding(sendPad, sendPad, sendPad, sendPad)
        // R_PILL, not a literal 20 — the drawable clamps the radius to half the
        // height, so this says "a circle" instead of silently restating half of
        // sendBox. The literal meant changing the button's size quietly turned the
        // disc into a squircle, and nothing pointed at the line that had to change.
        send.background = Theme.actionButton(Theme.R_PILL, this)
        if (Build.VERSION.SDK_INT >= 21) {
            // A solid black (or solid white) disc needs no shadow to read as a
            // button, and a shadow under it was the one place the flat system
            // leaked depth into the composer.
            send.elevation = 0.0f
        }
        val sendLp = LinearLayout.LayoutParams(sendBox, sendBox)
        sendLp.marginStart = Theme.dp(this, Ui.Space.S)
        send.setOnClickListener { onSendOrStop() }
        Ui.pressScale(send)
        tools.addView(send, sendLp)

        composer.addView(
            tools,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        wrap.addView(composer)
        updateSendAvailability()
        return wrap
    }

    /** Composer pill background; the border ignites on focus. */
    private fun composerBg(focused: Boolean): GradientDrawable =
        Theme.composerBg(focused, Theme.R_LG, this)

    // =====================================================================
    // Drawer
    // =====================================================================

    /**
     * The drawer: ground-coloured, full height, plain rows.
     *
     * It stays a [LinearLayout]. Retyping it to a FrameLayout would churn six
     * test-asserted literals (the gravity, the hidden translation, the direction,
     * the inset padding) for no layout gain — the panel is a vertical stack with
     * exactly one floating child, and that child gets its own FrameLayout host.
     *
     * Gone from here: the brand tile, the gradient heading, the two Telegram rows
     * (they belong in Settings, which is where a different screen now shows
     * them), the "v1" version chip, and the brand tile that marked the active
     * conversation. What is left is a title, a "Recents" label, the conversation
     * list, a floating New-chat pill, and Settings.
     */
    private fun buildDrawer(): LinearLayout {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.layoutDirection = Lang.direction(this)

        // Ground-coloured, with the 24dp corners on the OUTER edge only — the
        // edge that faces the page. The radii array is direction-aware because
        // GradientDrawable.cornerRadii is PHYSICAL (TL, TR, BR, BL) and does not
        // mirror itself.
        val panelBg = GradientDrawable()
        panelBg.setColor(Theme.BG)
        val radius = Theme.dpf(this, 24.0f)
        panelBg.cornerRadii = if (!Lang.mirrored(this)) {
            floatArrayOf(0.0f, 0.0f, radius, radius, radius, radius, 0.0f, 0.0f)
        } else {
            floatArrayOf(radius, radius, 0.0f, 0.0f, 0.0f, 0.0f, radius, radius)
        }
        panelBg.setStroke(Theme.hairline(this), Theme.BORDER)
        panel.background = panelBg
        // NOT fitsSystemWindows: `column` is dispatched the insets first and
        // consumes them, so this never fired. installInsetWatcher() pads the
        // panel explicitly instead.
        panel.fitsSystemWindows = false

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPaddingRelative(
            Theme.dp(this, Ui.Space.XL), Theme.dp(this, 18.0f),
            Theme.dp(this, Ui.Space.M), Theme.dp(this, 10.0f)
        )

        // Plain bold text. No gradient shader, no logo tile beside it: the app
        // name is a label here, not a badge.
        val heading = TextView(this)
        heading.text = Fa.APP_NAME
        heading.textSize = Ui.Type.TITLE
        heading.typeface = Theme.uiBold()
        heading.setTextColor(Theme.TEXT)
        heading.setSingleLine(true)
        heading.ellipsize = TextUtils.TruncateAt.END
        // "Vega Agent" is Latin in BOTH languages, so a first-strong resolve
        // would flip the view LTR and strand it against the drawer's far edge in
        // Persian. Pinning the alignment to the layout start keeps it put.
        Ui.rowLabel(heading)
        head.addView(
            heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        panel.addView(
            head,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // The grey "Recents" header. Its own start inset lives on the label, so
        // the row params only need to line it up with the list below it.
        val chatsLabel = Ui.sectionLabel(this, Fa.CHATS)
        val chatsLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        chatsLp.marginStart = Theme.dp(this, Ui.Space.L)
        chatsLp.topMargin = Theme.dp(this, Ui.Space.S)
        chatsLp.bottomMargin = Theme.dp(this, Ui.Space.XS)
        panel.addView(chatsLabel, chatsLp)

        // The list and its one floating child share a host, so the New-chat
        // pill's visibility follows the DRAWER for free. Parked in the root
        // FrameLayout it would have had to be shown and hidden by hand on every
        // open, close, theme change and back press.
        val listHost = FrameLayout(this)

        val listScroll = ScrollView(this)
        listScroll.isVerticalScrollBarEnabled = false
        listScroll.clipToPadding = false
        val list = LinearLayout(this)
        chatListContainer = list
        list.orientation = LinearLayout.VERTICAL
        // The 80dp tail is what the floating pill sits over, so the last
        // conversation is still reachable.
        list.setPaddingRelative(
            Theme.dp(this, Ui.Space.S), 0, Theme.dp(this, Ui.Space.S), Theme.dp(this, 80.0f)
        )
        listScroll.addView(list)
        listHost.addView(
            listScroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val newChat = Ui.primaryPill(this, Fa.NEW_CHAT, "plus") {
            startNewChat()
            closeDrawer()
        }
        val newChatLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        newChatLp.gravity = Gravity.BOTTOM or Gravity.END
        newChatLp.bottomMargin = Theme.dp(this, Ui.Space.L)
        newChatLp.marginEnd = Theme.dp(this, Ui.Space.L)
        listHost.addView(newChat, newChatLp)

        panel.addView(
            listHost,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
        )

        // One of the storage prompt's two hosts. Added unconditionally, and
        // GONE when the permission is already held: buildUi() only runs on a
        // theme or language change, so a row added only when access is missing
        // could never appear if it was revoked while the app was alive.
        val perm = permRow()
        permBanner = perm
        val permLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        permLp.marginStart = Theme.dp(this, Ui.Space.M)
        permLp.marginEnd = Theme.dp(this, Ui.Space.M)
        permLp.bottomMargin = Theme.dp(this, Ui.Space.S)
        panel.addView(perm, permLp)

        // Points along the reading direction: right in LTR, left in RTL.
        val chevron = ImageView(this)
        chevron.setImageDrawable(
            Icons.of(
                Lang.chevronForward(this),
                Theme.TEXT_FAINT,
                Ui.STROKE
            )
        )
        chevron.scaleType = ImageView.ScaleType.FIT_CENTER
        chevron.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val chevronSize = Theme.dp(this, 18.0f)
        chevron.layoutParams = LinearLayout.LayoutParams(chevronSize, chevronSize)

        panel.addView(
            Ui.cardRow(this, "settings", Fa.SETTINGS, null, chevron) {
                closeDrawer()
                openSettings()
            }
        )
        return panel
    }

    /**
     * Opens Settings with a smooth push transition instead of the abrupt
     * default swap. The platform's own fade constants are used rather than
     * hand-rolled slide animations: they are RTL-correct on every OEM and cost
     * nothing to maintain.
     */
    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
        try {
            @Suppress("DEPRECATION")
            // A presented-sheet transition instead of a flat cross-fade: the
            // settings screen lifts and fades in while the chat eases back a
            // hair, which is what makes the move feel deliberate rather than
            // like a hard cut.
            overridePendingTransition(R.anim.settings_enter, R.anim.settings_exit)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * Opens an external link. A `t.me` URL resolves to the Telegram app when
     * it is installed and to the browser otherwise. Wrapped in try/catch so a
     * device with no handler — or an OEM that throws from the resolver — shows
     * the link in a toast instead of crashing the app.
     */
    private fun openExternalLink(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Rebuilds the conversation list.
     *
     * Plain text rows: no leading glyph, no card, no per-row entrance stagger.
     * The active conversation is marked by WEIGHT plus a quiet SURFACE_2 fill —
     * it used to be marked by an accent colour on the label and a gradient brand
     * tile beside it, which made one row in the list shout.
     *
     * Titles-only, always: [ChatStore.listSummaries] reads headers, while
     * `store.list()` parses every message of every conversation. Drawing the
     * drawer through the latter was an OOM/ANR once a user had many chats.
     */
    private fun refreshChatList() {
        val list = chatListContainer ?: return
        list.removeAllViews()
        // Titles-only: never parse whole conversations just to draw the drawer.
        val chats = store.listSummaries()
        val currentId = chat?.id

        for (entry in chats) {
            val active = currentId != null && entry.id == currentId
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            // The active row gets a fill UNDER the ripple; every row gets the
            // same neutral ripple over it, so pressing them feels identical.
            row.background = if (active) {
                Theme.rippleOver(
                    Theme.roundRect(Theme.SURFACE_2, Theme.R_SM, this), Theme.R_SM, this
                )
            } else {
                Theme.rippleTransparent(Theme.R_SM, this)
            }
            // Relative, not physical: setPadding's left/right do NOT mirror, so
            // in Persian the roomy inset landed on the overflow-button side and
            // the tight one against the drawer edge — the mirror of the intent.
            row.setPaddingRelative(
                Theme.dp(this, Ui.Space.M), Theme.dp(this, 10.0f),
                Theme.dp(this, 6.0f), Theme.dp(this, 10.0f)
            )

            val label = TextView(this)
            label.text = if (Fa.isPlaceholderTitle(entry.title)) Fa.NEW_CHAT else entry.title
            label.setTextColor(Theme.TEXT)
            label.textSize = Ui.Type.BODY
            // Weight is the whole "this is the open one" signal now.
            label.typeface = if (active) Theme.uiSemi() else Theme.ui()
            label.setSingleLine(true)
            label.ellipsize = TextUtils.TruncateAt.END
            // Chat titles are user content and can be Latin even in Persian —
            // keep them pinned to the row's start edge instead of letting a
            // first-strong resolve fling them to the opposite side.
            Ui.rowLabel(label)
            row.addView(
                label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            )

            val entryId = entry.id
            val entryTitle = entry.title
            val entryPinned = entry.pinned
            // The overflow opens a MENU, not a delete confirmation.
            //
            // It used to fire the delete sheet directly, which made ⋮ a disguised
            // trash can — the one action on the row that cannot be undone, one tap
            // from the row you press to open a chat. Now it offers the three things
            // a conversation row can do, with delete marked as the destructive one.
            // The menu is anchored to THIS button, so the handler needs the view
            // itself — hence the two-step build rather than a trailing lambda.
            val overflow = Ui.circleButton(
                this, "more-vertical", 34.0f, 16.0f, Theme.TEXT_FAINT, 0, null
            )
            overflow.setOnClickListener {
                Ui.tick(overflow)
                showChatMenu(overflow, entryId, entryTitle, entryPinned)
            }
            row.addView(overflow)
            if (entryPinned) {
                // A pinned row says so, quietly, on the side its title starts from.
                val pin = ImageView(this)
                pin.setImageDrawable(Icons.of("pin", Theme.TEXT_FAINT, Ui.STROKE))
                val pinSize = Theme.dp(this, 14.0f)
                val pinLp = LinearLayout.LayoutParams(pinSize, pinSize)
                pinLp.marginEnd = Theme.dp(this, Ui.Space.XS)
                row.addView(pin, 0, pinLp)
            }

            // Whole row opens the chat.
            row.setOnClickListener {
                // Don't switch to a DIFFERENT chat while a run is live — the
                // streamed rows/tool cards would render into the wrong chat.
                if (AgentBus.isBusy() && entryId != AgentBus.activeChatId) {
                    Toast.makeText(this, Fa.WORKING, Toast.LENGTH_SHORT).show()
                    closeDrawer()
                } else {
                    val loaded = store.load(entryId)
                    if (loaded != null) {
                        setChat(loaded)
                        closeDrawer()
                    }
                }
            }
            Ui.pressScale(row)

            val rowLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rowLp.bottomMargin = Theme.dp(this, 2.0f)
            list.addView(row, rowLp)
        }

        if (chats.isEmpty()) {
            val empty = TextView(this)
            empty.text = Fa.NO_CHATS
            empty.setTextColor(Theme.TEXT_FAINT)
            empty.textSize = Ui.Type.META
            empty.typeface = Theme.ui()
            empty.gravity = Gravity.CENTER
            val emptyPad = Theme.dp(this, 18.0f)
            empty.setPadding(emptyPad, emptyPad, emptyPad, emptyPad)
            val emptyLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            emptyLp.topMargin = Theme.dp(this, Ui.Space.S)
            list.addView(empty, emptyLp)
        }
    }

    /**
     * Creates a new chat without letting a disk failure crash the app. Saving a
     * fresh chat can throw (e.g. storage full); callers that must recover — the
     * delete flow, the new-chat button — use this instead of [ChatStore.create].
     */
    private fun safeCreateChat(): Chat? = try {
        store.create()
    } catch (t: Throwable) {
        Toast.makeText(this, Fa.ERR_UNKNOWN, Toast.LENGTH_SHORT).show()
        null
    }

    /**
     * The conversation menu: a small card anchored to the \u22ee that opened it.
     *
     * It used to be a full-width bottom sheet, which is the wrong instrument twice
     * over. A bottom sheet is a modal surface for a decision — it dims the app, takes
     * the whole width, and slides up from the far end of the screen — and this is an
     * overflow menu for one row in a list, three lines long, that should appear
     * beside the thing it belongs to. It also cost two sheets to delete anything,
     * because the confirmation is a sheet too.
     *
     * Built as an overlay in the root frame rather than a [android.widget.PopupWindow]:
     * a popup needs a live window token and is a well-known source of
     * BadTokenException and leaked windows when the Activity is going away — the same
     * reason the tap-to-copy panel is an inline view, and the project's own contracts
     * hold both to it.
     */
    private fun showChatMenu(
        anchor: View,
        targetId: String,
        targetTitle: String,
        pinned: Boolean
    ) {
        val root = rootFrame ?: return
        dismissChatMenu()

        // A transparent catcher over everything, so a tap anywhere closes the menu.
        // Without it a menu opened over a scrolling list stays put while the list
        // moves underneath, anchored to nothing.
        val scrim = View(this)
        scrim.isClickable = true
        scrim.setOnClickListener { dismissChatMenu() }

        val card = Ui.column(this)
        card.background = Theme.roundStroke(
            Theme.SURFACE, Theme.BORDER_HI, Theme.R_MD, 1, this
        )
        Ui.roundClip(card, Theme.R_MD)
        card.elevation = Theme.dpf(this, 12.0f)
        val padV = Theme.dp(this, Ui.Space.XS)
        card.setPadding(0, padV, 0, padV)

        card.addView(
            // Theme.DIFF_DEL, not Theme.RED: RED is a near-black grey in this
            // palette (it equals TEXT), so tinting the destructive row with it drew
            // it identically to the other two — a delete action marked as dangerous
            // in the code and not on the screen. DIFF_DEL is the palette's one real
            // red, and "this removes something" is exactly what it already means.
            menuRow("trash", Fa.CHAT_MENU_DELETE, Theme.DIFF_DEL) {
                dismissChatMenu()
                confirmDeleteChat(targetId, targetTitle)
            }
        )
        card.addView(
            menuRow("edit", Fa.CHAT_MENU_RENAME, Theme.TEXT) {
                dismissChatMenu()
                showRenameChat(targetId, targetTitle)
            }
        )
        card.addView(
            menuRow(
                "pin",
                if (pinned) Fa.CHAT_MENU_UNPIN else Fa.CHAT_MENU_PIN,
                Theme.TEXT
            ) {
                dismissChatMenu()
                togglePinned(targetId, !pinned)
            }
        )

        val holder = FrameLayout(this)
        // The POSITIONING frame is direction-neutral; only the card's content
        // mirrors.
        //
        // The card is placed with `translationX`, which is a physical offset from
        // wherever the view was laid out — and a FrameLayout child with no gravity
        // is laid out at its parent's START edge, which is the RIGHT under RTL. So
        // in Persian the card started at the right edge and the offset pushed it
        // further right, off the screen: the delete/rename/pin row was clipped
        // against the glass with its icons half missing.
        //
        // Pinning this frame to LTR makes "translationX = x" mean the same thing in
        // both languages, which is what absolute screen maths needs. The card's own
        // rows take the interface direction below, so the menu still reads
        // right-to-left in Persian — it is only the arithmetic that stops flipping.
        holder.layoutDirection = View.LAYOUT_DIRECTION_LTR
        holder.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // Named for this menu, not `cardLp`: the static cross-referencer resolves a
        // params variable by name across the whole file, and `cardLp` is already a
        // LinearLayout.LayoutParams in the reasoning row.
        val menuLp = FrameLayout.LayoutParams(
            Theme.dp(this, CHAT_MENU_DP), FrameLayout.LayoutParams.WRAP_CONTENT
        )
        // Explicit, because the default depends on the resolved direction and the
        // whole point of the LTR frame above is that nothing here should.
        menuLp.gravity = Gravity.TOP or Gravity.START
        // The card itself reads in the interface's direction.
        card.layoutDirection = Lang.direction(this)
        holder.addView(card, menuLp)
        root.addView(
            holder,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        chatMenu = holder

        // Positioned after a layout pass, because the card's height is only known
        // once its three rows have been measured — and the height is what decides
        // whether it opens downwards or flips up to stay on screen.
        card.post {
            val rootAt = IntArray(2)
            val anchorAt = IntArray(2)
            root.getLocationInWindow(rootAt)
            anchor.getLocationInWindow(anchorAt)
            val margin = Theme.dp(this, Ui.Space.S)
            val width = Theme.dp(this, CHAT_MENU_DP)
            var x = anchorAt[0] - rootAt[0] + anchor.width - width
            x = Math.max(margin, Math.min(x, root.width - width - margin))
            var y = anchorAt[1] - rootAt[1] + anchor.height + Theme.dp(this, 2.0f)
            if (y + card.height > root.height - margin) {
                y = anchorAt[1] - rootAt[1] - card.height - Theme.dp(this, 2.0f)
            }
            y = Math.max(margin, y)
            card.translationX = x.toFloat()
            card.translationY = y.toFloat()
            card.pivotX = width.toFloat()
            card.pivotY = 0.0f
            card.alpha = 0.0f
            card.scaleX = 0.92f
            card.scaleY = 0.92f
            card.visibility = View.VISIBLE
            card.animate().alpha(1.0f).scaleX(1.0f).scaleY(1.0f)
                .setDuration(Ui.D_FAST).setInterpolator(Ui.ease()).start()
        }
        card.visibility = View.INVISIBLE
    }

    /** Closes the conversation menu, if one is open. */
    private fun dismissChatMenu() {
        val open = chatMenu ?: return
        chatMenu = null
        (open.parent as? ViewGroup)?.removeView(open)
    }

    /** One menu row: a glyph, a label, and a ripple across the whole width. */
    private fun menuRow(icon: String, label: String, tint: Int, action: () -> Unit): View {
        val row = Ui.row(this)
        row.background = Theme.rippleTransparent(Theme.R_MD, this)
        val padH = Theme.dp(this, Ui.Space.S)
        val padV = Theme.dp(this, 14.0f)
        row.setPadding(padH, padV, padH, padV)
        row.minimumHeight = Theme.dp(this, 52.0f)

        val glyphSize = Theme.dp(this, 20.0f)
        val glyph = ImageView(this)
        glyph.setImageDrawable(Icons.of(icon, tint, Ui.STROKE))
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        val glyphLp = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphLp.marginEnd = Theme.dp(this, Ui.Space.L)
        row.addView(glyph, glyphLp)

        val text = TextView(this)
        text.text = label
        text.textSize = Ui.Type.BODY
        text.typeface = Theme.uiMedium()
        text.setTextColor(tint)
        Ui.rowLabel(text)
        row.addView(text, Ui.grow())

        row.setOnClickListener {
            Ui.tick(row)
            action()
        }
        Ui.pressScale(row)
        return row
    }

    /** Renames a conversation, defaulting to whatever it is called now. */
    private fun showRenameChat(targetId: String, targetTitle: String) {
        val sheet = Sheet(this)
        sheet.header("edit", Fa.CHAT_MENU_RENAME, Fa.CHAT_RENAME_TITLE)

        val field = EditText(this)
        field.setText(if (Fa.isPlaceholderTitle(targetTitle)) "" else targetTitle)
        field.setSelection(field.text.length)
        field.textSize = Ui.Type.BODY
        field.typeface = Theme.ui()
        field.setTextColor(Theme.TEXT)
        field.setHintTextColor(Theme.TEXT_FAINT)
        field.hint = Fa.CHAT_RENAME_TITLE
        field.background = Theme.inputBg(false, Theme.R_MD, this)
        field.setSingleLine(true)
        field.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        val fieldPad = Theme.dp(this, 14.0f)
        field.setPadding(fieldPad, fieldPad, fieldPad, fieldPad)
        val fieldLp = Ui.matchWrap()
        fieldLp.bottomMargin = Theme.dp(this, 18.0f)
        sheet.body.addView(field, fieldLp)

        val buttons = Ui.row(this)
        val cancel = Ui.pillButton(this, Fa.CANCEL, null, Ui.SECONDARY) { sheet.dismiss() }
        val cancelLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        cancelLp.marginEnd = Theme.dp(this, Ui.Space.S)
        buttons.addView(cancel, cancelLp)
        buttons.addView(
            Ui.pillButton(this, Fa.SAVE, "check", Ui.PRIMARY) {
                val name = field.text.toString().trimJava()
                sheet.dismiss()
                if (name.isNotEmpty()) {
                    renameChat(targetId, name)
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        sheet.body.addView(buttons, Ui.matchWrap())
        sheet.show()
    }

    /**
     * Writes a new title, through the OPEN chat when it is the one being renamed.
     *
     * Renaming the copy on disk while a different copy is open in memory would let
     * the next save put the old title straight back.
     */
    private fun renameChat(targetId: String, name: String) {
        val open = chat
        try {
            if (open != null && open.id == targetId) {
                open.title = name
                store.saveNow(open)
            } else {
                val loaded = store.load(targetId) ?: return
                loaded.title = name
                store.saveNow(loaded)
            }
        } catch (ignored: Throwable) {
            Toast.makeText(this, Fa.ERR_SAVE, Toast.LENGTH_SHORT).show()
            return
        }
        refreshChatList()
        refreshTitle()
    }

    /** Pins or unpins a conversation, same ownership rule as renaming. */
    private fun togglePinned(targetId: String, value: Boolean) {
        val open = chat
        try {
            if (open != null && open.id == targetId) {
                open.pinned = value
                store.saveNow(open)
            } else {
                val loaded = store.load(targetId) ?: return
                loaded.pinned = value
                store.saveNow(loaded)
            }
        } catch (ignored: Throwable) {
            Toast.makeText(this, Fa.ERR_SAVE, Toast.LENGTH_SHORT).show()
            return
        }
        refreshChatList()
    }

    private fun confirmDeleteChat(targetId: String, targetTitle: String) {
        val sheet = Sheet(this)
        sheet.header(
            "trash", Fa.DELETE_CHAT_TITLE,
            if (Fa.isPlaceholderTitle(targetTitle)) Fa.NEW_CHAT else targetTitle
        )

        val msg = TextView(this)
        msg.typeface = Theme.ui()
        msg.text = Fa.DELETE_CHAT_MSG
        msg.setTextColor(Theme.TEXT_MUTED)
        msg.textSize = Ui.Type.LABEL
        msg.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        val msgLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        msgLp.bottomMargin = Theme.dp(this, 18.0f)
        sheet.body.addView(msg, msgLp)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val cancel = Ui.pillButton(this, Fa.CANCEL, null, Ui.SECONDARY) { sheet.dismiss() }
        val cancelLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        cancelLp.marginEnd = Theme.dp(this, 8.0f)
        buttons.addView(cancel, cancelLp)
        buttons.addView(
            Ui.pillButton(this, Fa.DELETE, "trash", Ui.DANGER) {
                // Don't allow deleting a chat that is actively running (would orphan
                // the service + leave a dangling AgentBus.liveChat).
                if (AgentBus.isRunningFor(targetId)) {
                    Toast.makeText(this, Fa.WORKING, Toast.LENGTH_SHORT).show()
                    sheet.dismiss()
                } else {
                    sheet.dismiss()
                    // Everything below is cheap now (no full-conversation parsing),
                    // and wrapped so a disk hiccup can never crash the app mid-delete.
                    try {
                        store.delete(targetId)
                        // Drop any stale global reference to the just-deleted chat so
                        // nothing re-saves it back to disk later.
                        val live = AgentBus.liveChat
                        if (live != null && targetId == live.id) {
                            AgentBus.liveChat = null
                            AgentBus.activeChatId = null
                        }
                        val current = chat
                        if (current != null && current.id == targetId) {
                            // Switch to the most recent remaining chat (load just
                            // that one — never the whole list), or start a fresh one.
                            val remaining = store.listSummaries()
                            val next = if (remaining.isEmpty()) {
                                safeCreateChat()
                            } else {
                                store.load(remaining[0].id) ?: safeCreateChat()
                            }
                            if (next != null) {
                                setChat(next)
                            }
                        }
                        refreshChatList()
                    } catch (t: Throwable) {
                        Toast.makeText(this, Fa.ERR_UNKNOWN, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        sheet.body.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sheet.show()
    }

    // =====================================================================
    // Mode sheet + pill
    // =====================================================================

    private fun showModeSheet() {
        val sheet = Sheet(this)
        sheet.header("sliders", Fa.MODE_TITLE, Fa.MODE_SUBTITLE)
        val mode = prefs.mode()
        addModeOption(
            sheet, "zap", Prefs.MODE_AUTO, Fa.MODE_AUTO, Fa.MODE_AUTO_DESC, mode
        )
        addModeOption(
            sheet, "eye", Prefs.MODE_PLAN, Fa.MODE_PLAN, Fa.MODE_PLAN_DESC, mode
        )
        addModeOption(
            sheet, "shield", Prefs.MODE_ACCEPT, Fa.MODE_ACCEPT, Fa.MODE_ACCEPT_DESC, mode
        )
        sheet.show()
    }

    /**
     * One row of the mode sheet.
     *
     * There is deliberately no `tone` parameter. The three modes used to be
     * keyed to ACCENT / BLUE / GREEN, which in a monochrome palette resolve to
     * three unrelated greys — so the rows rendered at three brightness levels
     * that encoded nothing, and "selected" looked emphatic for one mode and
     * washed out for another. Selection is now carried by an ACCENT ring, a
     * heavier title and a filled check disc; the mode is carried by its glyph
     * and its name. Same decision as the deleted mode-pill dot.
     */
    private fun addModeOption(
        sheet: Sheet,
        icon: String,
        value: String,
        title: String,
        description: String,
        activeMode: String
    ) {
        val selected = value == activeMode
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        // Selection is a fill plus a tick, not an outline. A 2dp ACCENT ring
        // would be the single heaviest stroke in the app, and the reference
        // model-pickers mark the active row with a grey fill and a check —
        // which this row already has, so the ring only shouted.
        val bg = if (selected) {
            Theme.roundRect(Theme.SURFACE_2, Theme.R_MD, this)
        } else {
            Theme.sheetRow(Theme.R_MD, this)
        }
        row.background = Theme.rippleOver(bg, Theme.R_MD, this)
        val pad = Theme.dp(this, 13.0f)
        row.setPadding(pad, pad, pad, pad)

        val tile = LinearLayout(this)
        tile.gravity = Gravity.CENTER
        tile.background = Theme.iconChip(Theme.TEXT_MUTED, 13.0f, this)
        val tileSize = Theme.dp(this, 42.0f)
        val glyph = ImageView(this)
        glyph.setImageDrawable(Icons.of(icon, Theme.TEXT_MUTED, Ui.STROKE))
        // Decorative: the row's own label already says which mode this is. Left
        // focusable, this and the check disc below were the only two ornamental
        // glyphs in the app that TalkBack stopped on, so every mode row announced
        // two unlabelled images before its actual text.
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(this, 21.0f)
        tile.addView(glyph, LinearLayout.LayoutParams(glyphSize, glyphSize))
        val tileLp = LinearLayout.LayoutParams(tileSize, tileSize)
        tileLp.marginEnd = Theme.dp(this, 13.0f)
        row.addView(tile, tileLp)

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val titleView = TextView(this)
        titleView.text = title
        titleView.setTextColor(Theme.TEXT)
        titleView.textSize = Ui.Type.LABEL
        titleView.typeface = if (selected) Theme.uiXBold() else Theme.uiSemi()
        texts.addView(titleView)
        val descView = TextView(this)
        descView.typeface = Theme.ui()
        descView.text = description
        descView.setTextColor(Theme.TEXT_MUTED)
        descView.textSize = Ui.Type.META
        descView.setLineSpacing(Theme.dpf(this, 2.0f), 1.0f)
        texts.addView(descView)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))

        if (selected) {
            // A filled check disc, not a bare tick: at 22dp a stroked glyph in
            // a tinted row is easy to miss, and this is the row's only state.
            val check = LinearLayout(this)
            check.gravity = Gravity.CENTER
            check.background = Theme.circle(Theme.ACCENT)
            val glyphCheck = ImageView(this)
            glyphCheck.setImageDrawable(
                Icons.of("check", Theme.ON_ACCENT, Ui.STROKE)
            )
            glyphCheck.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val innerCheck = Theme.dp(this, 13.0f)
            check.addView(glyphCheck, LinearLayout.LayoutParams(innerCheck, innerCheck))
            val checkSize = Theme.dp(this, 24.0f)
            val checkLp = LinearLayout.LayoutParams(checkSize, checkSize)
            checkLp.marginStart = Theme.dp(this, 10.0f)
            row.addView(check, checkLp)
        }

        row.setOnClickListener {
            // Switching modes is an explicit statement about how much autonomy
            // the agent gets, so it revokes every standing "always allow".
            if (value != prefs.mode()) {
                AgentEngine.clearSessionAllowances()
            }
            prefs.setMode(value)
            refreshModePill()
            sheet.dismiss()
        }
        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowLp.bottomMargin = Theme.dp(this, 10.0f)
        sheet.body.addView(row, rowLp)
    }

    /**
     * The run mode's own name — which, with the coloured dot gone, is now the
     * ONLY thing that distinguishes the three modes. That is deliberate: "Auto"
     * / "Plan" / "Accept" is legible, a violet-vs-blue-vs-green pip was not.
     */
    private fun modeLabel(): String = when (prefs.mode()) {
        Prefs.MODE_PLAN -> Fa.MODE_PLAN
        Prefs.MODE_ACCEPT -> Fa.MODE_ACCEPT
        else -> Fa.MODE_AUTO
    }

    /**
     * Repaints the composer's mode chip in place — the label, and nothing else.
     *
     * The chip's ground, glyph and chevron are all mode-independent now, so this
     * is a single text write against the [Ui.selectorChip] child contract.
     */
    private fun refreshModePill() {
        val pill = modePill ?: return
        val mode = prefs.mode()
        val label = modeLabel()

        val apply = Runnable {
            modePillText?.text = label
            pill.requestLayout()
        }

        // This runs on every tool step, so only animate when the mode ACTUALLY
        // changed — otherwise the pill would pulse continuously through a run.
        //
        // The pill can be trusted now. It used to read `prefs.mode()` while the
        // engine silently escalated PLAN → ACCEPT in a run-local variable that was
        // deliberately never persisted, so it said "Planning" for the whole of a run
        // that was editing files. PLAN refuses instead of escalating, and the plan
        // sheet's approval writes ACCEPT to prefs for real, so what this reads and
        // what the engine does are the same thing again.
        if (lastPillMode != null && lastPillMode != mode) {
            Ui.swapContent(pill, apply)
        } else {
            apply.run()
        }
        lastPillMode = mode
    }

    // =====================================================================
    // Drawer open/close + window chrome
    // =====================================================================

    private fun drawerHiddenTranslation(): Float {
        val width = drawerPanel?.layoutParams?.width ?: 0
        return if (Lang.mirrored(this)) width.toFloat() else -width.toFloat()
    }

    /**
     * The panel edge the drawer is anchored to, in panel-local px — its scale
     * pivot. 0 while the layout is left-to-right (anchored left), the far edge if
     * it is ever mirrored, matching the `Gravity.START` the panel is added with.
     */
    private fun drawerPivotX(): Float {
        val width = drawerPanel?.layoutParams?.width ?: 0
        return if (Lang.mirrored(this)) width.toFloat() else 0.0f
    }

    private fun openDrawer() {
        refreshChatList()
        val scrim = drawerScrim ?: return
        val panel = drawerPanel ?: return
        scrim.animate().cancel()
        panel.animate().cancel()
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0.0f
        scrim.animate().alpha(1.0f).setDuration(Ui.D_BASE)
            .setInterpolator(Ui.ease()).start()
        panel.visibility = View.VISIBLE
        panel.translationX = drawerHiddenTranslation()
        // A hair of scale alongside the slide makes the panel feel like it comes
        // forward rather than merely sideways — but on ONE axis, pivoted on the
        // edge the panel is anchored to.
        //
        // It used to scale both axes from the default centre pivot, on a panel
        // whose height is MATCH_PARENT. 2% of a full screen height is ~24px pulled
        // off the top and another ~24px off the bottom, so for the entire 300ms
        // you could see the scrim and the transcript through a band along both
        // edges of the drawer. Scaling X about the anchored edge keeps that edge
        // glued to the screen and moves only the inner one, which is the "comes
        // forward" read the scale was there for to begin with.
        panel.pivotX = drawerPivotX()
        panel.pivotY = 0.0f
        panel.scaleX = 0.98f
        panel.scaleY = 1.0f
        panel.animate().translationX(0.0f).scaleX(1.0f)
            .setDuration(Ui.D_SLOW).setInterpolator(Ui.ease()).start()
    }

    private fun closeDrawer() {
        // The menu is anchored to a row inside the drawer, so it goes with it.
        dismissChatMenu()
        val scrim = drawerScrim
        val panel = drawerPanel
        scrim?.animate()?.cancel()
        panel?.animate()?.cancel()
        scrim?.animate()?.alpha(0.0f)?.setDuration(Ui.D_BASE)
            ?.setInterpolator(Ui.easeOut())
            ?.withEndAction { scrim.visibility = View.GONE }?.start()
        panel?.pivotX = drawerPivotX()
        panel?.pivotY = 0.0f
        panel?.animate()?.translationX(drawerHiddenTranslation())
            ?.scaleX(0.98f)
            ?.setDuration(Ui.D_BASE)?.setInterpolator(Ui.easeOut())
            ?.withEndAction {
                panel.visibility = View.GONE
                panel.scaleX = 1.0f
                panel.scaleY = 1.0f
            }?.start()
    }

    @Deprecated("Kept to preserve the drawer's back behaviour")
    override fun onBackPressed() {
        if (drawerPanel?.visibility != View.VISIBLE) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            closeDrawer()
        }
    }

    private fun currentNight(): Int =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

    private fun applyWindowChrome() {
        val w = window
        // The status bar sits directly on top of the header bar, which is
        // painted BG_ELEV. Painting the bar BG — as this used to — left a
        // visible seam: a grey strip above a white bar in light mode, and a
        // faint step on OLED in dark. The two surfaces have to be the same
        // colour. The navigation bar keeps BG, because the input area directly
        // above *it* is painted BG, so that edge already matched.
        w.statusBarColor = Theme.BG_ELEV
        w.navigationBarColor = Theme.BG
        // Paint the window itself too: on API 35+ edge-to-edge the bar colors
        // above are ignored, and this keeps the bar areas on-brand.
        w.setBackgroundDrawable(Theme.windowBg())
        statusScrim?.setBackgroundColor(Theme.BG_ELEV)
        val decor = w.decorView
        // Stop the OS / MIUI / One UI from auto-inverting our hand-drawn colors
        // (the cause of grey/washed-out text when app theme != system theme).
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                decor.isForceDarkAllowed = false
            } catch (e: Exception) {
            }
        }
        @Suppress("DEPRECATION")
        var vis = decor.systemUiVisibility
        if (Theme.DARK) {
            vis = vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= 26) {
                vis = vis and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        } else {
            vis = vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) {
                vis = vis or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        @Suppress("DEPRECATION")
        decor.systemUiVisibility = vis
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        lastNight = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        // Rebuild ONLY when the resolved palette actually changes. The manifest
        // routes orientation / density / screenSize / multi-window resizes here
        // too; rebuilding on those tore down the live stream, re-decoded every
        // attachment bitmap on the UI thread and reset the scroll — once per
        // frame while a split-screen divider is dragged.
        Theme.applyFromPrefs(this, prefs)
        if (Theme.revision != appliedRevision) {
            refreshAppearance()
            return
        }
        // No rebuild, so the watermark's cached resting position is now stale for
        // the new screen size. Re-pin it (and settle it, in case the rotation also
        // dismissed the keyboard) without disturbing anything else.
        syncWatermarkGeometry()
        updateWatermark()
    }

    private fun toggleTheme() {
        val nowDark = Theme.DARK
        prefs.setThemeMode(if (nowDark) Prefs.THEME_LIGHT else Prefs.THEME_DARK)
        refreshAppearance()
    }

    /**
     * Digits for the interface.
     *
     * Kept as a named call rather than inlining `toString()`: plan steps, edit
     * counters and the activity strip all number things and have to agree with
     * each other. One function is the cheapest way to keep them agreeing.
     */
    private fun num(value: Int): String = Lang.num(this, value)

    /**
     * A readable measure for a run of prose, in pixels.
     *
     * Percentage-only widths were fine on the phones this was built on and wrong
     * the moment it met a tablet: `0.8 × widthPixels` on a 10-inch screen in
     * landscape is a 640dp line of 16sp text, roughly twice the length the eye
     * tracks comfortably, and the transcript column itself had no cap at all —
     * assistant prose simply ran edge to edge.
     *
     * The fraction stays as the basis, because on a phone it is the right answer
     * and produces the layout that already exists. This only bites when the
     * fraction would exceed a sane line length. [Sheet] has capped itself at 560dp
     * since the beginning; this is the same idea applied to the surface that needed
     * it most.
     */
    /**
     * The transcript's side inset, widening on screens bigger than a column.
     *
     * Padding rather than a fixed-width child, so nothing about the existing
     * layout changes on a phone and the scroll view keeps filling the window —
     * a centred fixed-width child would have needed its own container and would
     * have moved the scrollbar off the screen edge.
     */
    private fun transcriptGutter(): Int {
        val base = Theme.dp(this, Ui.Space.L)
        val screen = resources.displayMetrics.widthPixels
        val column = Theme.dpf(this, MAX_PROSE_DP).toInt()
        if (screen <= column + base * 2) {
            return base
        }
        return (screen - column) / 2
    }

    private fun proseWidth(fraction: Float): Int {
        val available = resources.displayMetrics.widthPixels * fraction
        return Math.min(available, Theme.dpf(this, MAX_PROSE_DP)).toInt()
    }

    private fun refreshAppearance() {
        val draft = input?.text?.toString() ?: ""
        val selection = input?.selectionStart ?: 0
        // Where the user was reading. renderAll() jumps to the bottom, so without
        // this a system dark/light flip teleported them out of the middle of a
        // long answer.
        val keptScroll = messagesScroll?.scrollY ?: 0
        val wasNearBottom = nearBottom
        // Any sheet still open was built with the OLD palette, so it would float
        // over the rebuilt tree looking foreign. A pending approval is re-shown
        // by reconcileRunningState() below.
        Sheet.dismissAll()
        dismissChatMenu()
        shownApproval = null
        // Re-reads the language flag and drops Lang's cached direction, so the tree
        // built below picks up both. Cheap — Fa is computed getters, so there is no
        // table to rebuild; this is one boolean and one cache invalidation.
        Fa.apply(this)
        Theme.applyFromPrefs(this, prefs)
        appliedRevision = Theme.revision
        lastLanguage = prefs.language()
        lastNight = currentNight()
        applyWindowChrome()
        buildUi()
        chat?.let { setChat(it) }
        refreshAttachStrip()
        if (draft.isNotEmpty()) {
            input?.setText(draft)
            input?.setSelection(Math.max(0, Math.min(selection, draft.length)))
        }
        AgentBus.listener = uiListener
        reconcileRunningState()
        // Put the reading position back. Only when the user was NOT already at
        // the bottom — otherwise renderAll's scroll-to-bottom is what they want.
        if (!wasNearBottom && keptScroll > 0) {
            messagesScroll?.let { sv -> sv.post { sv.scrollTo(0, keptScroll) } }
        }
        // The whole view tree was just rebuilt in the other palette. Fading the
        // new tree up turns a jarring instant repaint into a deliberate
        // transition — the difference between "the app glitched" and "the app
        // changed theme".
        window.decorView.let { decor ->
            decor.alpha = 0.0f
            decor.animate().alpha(1.0f)
                .setDuration(Ui.D_SLOW).setInterpolator(Ui.ease()).start()
        }
    }

    // =====================================================================
    // Chat state + rendering
    // =====================================================================

    private fun setChat(target: Chat) {
        chat = target
        prefs.setLastChatId(target.id)
        refreshTitle()
        renderAll()
        setRunning(AgentBus.isRunningFor(target.id))
    }

    private fun startNewChat() {
        if (AgentBus.isBusy()) {
            Toast.makeText(this, Fa.WORKING, Toast.LENGTH_SHORT).show()
        } else {
            // Consent granted with "always allow" belongs to the conversation it
            // was granted in. Without this it is a static that outlives every
            // chat for as long as Android keeps the process alive, with no way
            // to take it back.
            AgentEngine.clearSessionAllowances()
            safeCreateChat()?.let { setChat(it) }
        }
    }

    /**
     * The Thoughts panel: everything the run did, in one scrollable sheet.
     *
     * This is what tapping the strip opens, before and after the answer. It holds
     * the phase, every activity row in order — including the model's own reasoning,
     * which is why the separate "reasoning" card is gone — and the sources pill,
     * which used to sit loose in the transcript underneath the strip.
     */
    private fun showThoughtsSheet(trail: Trail) {
        val sheet = Sheet(this)
        sheet.plainTitle(Fa.TRAIL_PANEL_TITLE)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        // The model's own description of what it is doing, as SUPPORTING text.
        //
        // It used to be set bold at HEAD size, directly under the panel's title —
        // two headings stacked, the lower one written by the model and changing
        // every step. The panel is titled by the app; this line says what the run
        // is currently about, and it should look like the second of those, not the
        // first. It is deliberately still here, though: the strip's own heading is
        // now fixed, so this is the one place the model's framing of the task can
        // still be read.
        val phase = trail.phase.trimJavaOrEmpty()
        if (phase.isNotEmpty()) {
            val heading = TextView(this)
            heading.text = phase
            heading.textSize = Ui.Type.META
            heading.typeface = Theme.uiMedium()
            heading.setTextColor(Theme.TEXT_MUTED)
            heading.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            heading.setLineSpacing(Theme.dpf(this, 2.5f), 1.0f)
            val headingLp = Ui.matchWrap()
            headingLp.bottomMargin = Theme.dp(this, Ui.Space.S)
            column.addView(heading, headingLp)
        }

        // What the run cost, on one line under the phase: how long, how many steps,
        // how many thoughts, what changed. The panel used to open on a bare list of
        // rows, which answers "what happened" and not "how much" — and "how much" is
        // most of why anyone opens it.
        statsLine(trail)?.let { line ->
            val lp = Ui.matchWrap()
            lp.bottomMargin = Theme.dp(this, Ui.Space.M)
            column.addView(line, lp)
        }

        // The pages pill, in the panel where it belongs.
        val hosts = trail.pages()
        if (hosts.size >= 2) {
            column.addView(sourcesPill(hosts))
        }

        val steps = trail.steps()
        if (steps.isEmpty()) {
            val empty = TextView(this)
            empty.text = Fa.TRAIL_EMPTY
            empty.textSize = Ui.Type.LABEL
            empty.typeface = Theme.ui()
            empty.setTextColor(Theme.TEXT_FAINT)
            column.addView(empty, Ui.matchWrap())
        }
        for ((index, step) in steps.withIndex()) {
            if (index > 0) {
                // A hairline between rows, inset past the glyph rail so the run reads
                // as a sequence of steps rather than one continuous block of text.
                val rule = Ui.divider(this)
                val ruleLp = Ui.matchWrap()
                ruleLp.marginStart = Theme.dp(this, 20.0f + Ui.Space.M)
                rule.layoutParams = ruleLp
                column.addView(rule)
            }
            column.addView(thoughtRow(step, sheet))
        }

        // Copy the whole run as text. A trail is the most useful thing in the app to
        // paste into a bug report, and there was no way to get it out.
        if (steps.isNotEmpty()) {
            val copy = Ui.pillButton(this, Fa.TRAIL_COPY, "copy", Ui.SECONDARY) {
                MarkdownRenderer.copyText(this, Fa.TRAIL_PANEL_TITLE, trailAsText(trail))
                Toast.makeText(this, Fa.TRAIL_COPIED, Toast.LENGTH_SHORT).show()
            }
            val copyLp = Ui.matchWrap()
            copyLp.topMargin = Theme.dp(this, Ui.Space.L)
            column.addView(copy, copyLp)
        }

        val columnLp = Ui.matchWrap()
        columnLp.bottomMargin = Theme.dp(this, Ui.Space.M)
        sheet.body.addView(column, columnLp)
        sheet.show()
    }

    /**
     * The run's numbers, as a row of quiet chips.
     *
     * Every one of these was already recorded and none of them was ever displayed:
     * per-step timings from the first version, `workCount()` written with the comment
     * "for the panel's subtitle" and never called, the change counts added with the
     * diff work. Returns null when there is genuinely nothing to say.
     */
    private fun statsLine(trail: Trail): View? {
        val parts = ArrayList<String>(5)
        val elapsed = trail.elapsedMs(System.currentTimeMillis())
        if (elapsed > 0L) {
            parts.add(TrailView.duration(this, elapsed))
        }
        val steps = trail.workCount()
        if (steps > 0) {
            parts.add(Fa.TRAIL_STEPS.format(Lang.num(this, steps)))
        }
        val thoughts = trail.thoughtCount()
        if (thoughts > 0) {
            parts.add(Fa.TRAIL_THOUGHTS.format(Lang.num(this, thoughts)))
        }
        val files = trail.editedFiles().size
        if (files > 0) {
            parts.add(Fa.TRAIL_EDITED.format(Lang.num(this, files)))
        }
        val failed = trail.failedCount()
        if (failed > 0) {
            parts.add(Lang.num(this, failed) + " " + Fa.TRAIL_FAILED)
        }
        if (parts.isEmpty()) {
            return null
        }
        val row = Ui.row(this)
        val label = TextView(this)
        label.text = parts.joinToString("  \u00b7  ")
        label.textSize = Ui.Type.LABEL
        label.typeface = Theme.ui()
        label.setTextColor(Theme.TEXT_MUTED)
        label.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        row.addView(label, Ui.grow())

        val totals = trail.changeTotals()
        if (totals[0] > 0 || totals[1] > 0) {
            row.addView(changeCounts(totals[0], totals[1]))
        }
        return row
    }

    /** `+N \u2212N` in the palette's two real colours, for a panel row or a header. */
    private fun changeCounts(added: Int, removed: Int): View {
        val box = Ui.row(this)
        // The pair reads left to right in both languages: `+12 \u22123` mirrored is
        // nonsense, and these are numbers, not prose.
        box.layoutDirection = View.LAYOUT_DIRECTION_LTR
        if (added > 0) {
            val view = TextView(this)
            view.text = "+" + Lang.num(this, added)
            view.textSize = Ui.Type.LABEL
            view.typeface = Theme.uiMedium()
            view.setTextColor(Theme.DIFF_ADD)
            box.addView(view, Ui.wrapWrap())
        }
        if (removed > 0) {
            val view = TextView(this)
            view.text = "\u2212" + Lang.num(this, removed)
            view.textSize = Ui.Type.LABEL
            view.typeface = Theme.uiMedium()
            view.setTextColor(Theme.DIFF_DEL)
            val lp = Ui.wrapWrap()
            lp.marginStart = Theme.dp(this, Ui.Space.XS + 2.0f)
            box.addView(view, lp)
        }
        val boxLp = Ui.wrapWrap()
        boxLp.marginStart = Theme.dp(this, Ui.Space.S)
        box.layoutParams = boxLp
        return box
    }

    /** The whole run as plain text, for the clipboard. */
    private fun trailAsText(trail: Trail): String {
        val sb = StringBuilder()
        val phase = trail.phase.trimJavaOrEmpty()
        if (phase.isNotEmpty()) {
            sb.append(phase).append("\n\n")
        }
        for (step in trail.steps()) {
            if (step.kind == TrailStep.THINK) {
                sb.append("\u2022 ").append(Fa.TRAIL_REASONING).append(": ")
                    .append(step.detail).append("\n")
                continue
            }
            if (step.kind == TrailStep.NOTE) {
                sb.append("\u2022 ").append(step.detail).append("\n")
                continue
            }
            sb.append("\u2022 ").append(step.label)
            if (step.detail.isNotBlankJava()) {
                sb.append(" \u2014 ").append(step.detail)
            }
            // The whole point of copying a run is the part that went wrong. This
            // omitted the reason, and the status, so a pasted trail read as a list
            // of things that had happened with no indication that any of them
            // had failed.
            if (step.status == TrailStep.FAILED || step.status == TrailStep.REJECTED) {
                sb.append("  [")
                    .append(if (step.status == TrailStep.REJECTED) Fa.TRAIL_REJECTED else Fa.TRAIL_FAILED)
                    .append("]")
                if (step.hasReason()) {
                    sb.append("\n    ").append(step.reason)
                }
            }
            if (step.hasChangeCounts()) {
                sb.append("  (+").append(step.added).append(" \u2212")
                    .append(step.removed).append(")")
            }
            if (step.endedAt > 0L) {
                sb.append("  [").append(TrailView.duration(this, step.durationMs(step.endedAt)))
                    .append("]")
            }
            sb.append("\n")
        }
        val hosts = trail.pages()
        if (hosts.isNotEmpty()) {
            sb.append("\n").append(Fa.TRAIL_PAGES).append(": ").append(hosts.joinToString(", "))
        }
        return sb.toString()
    }

    /**
     * What the model said it was about to do, in its own words.
     *
     * The counterpart to [reasoningRow], and deliberately styled differently:
     * reasoning is private working-out and is set muted and quiet, whereas this is
     * a sentence addressed to the user and reads at full weight. It is here rather
     * than in the conversation because the conversation should hold the answer, not
     * the commentary — a long job used to interleave a dozen "now let me…" bubbles
     * with the result, and the review section is exactly the place where a running
     * account belongs.
     */
    private fun narrationRow(step: TrailStep): View {
        val row = Ui.row(this)
        row.gravity = Gravity.TOP
        val vertical = Theme.dp(this, Ui.Space.S)
        row.setPaddingRelative(0, vertical, 0, vertical)

        val glyph = ImageView(this)
        glyph.setImageDrawable(Icons.of("message", Theme.TEXT_FAINT, Ui.STROKE))
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(this, 16.0f)
        val glyphLp = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphLp.marginEnd = Theme.dp(this, Ui.Space.M)
        glyphLp.topMargin = Theme.dp(this, 2.0f)
        row.addView(glyph, glyphLp)

        val prose = TextView(this)
        prose.text = step.detail
        prose.textSize = Ui.Type.LABEL
        prose.typeface = Theme.ui()
        prose.setTextColor(Theme.TEXT)
        prose.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        // Model-written, so it decides its own direction — the interface may be in
        // one language while the answer is in the other.
        prose.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        prose.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        prose.setTextIsSelectable(true)
        MarkdownRenderer.installSelectionActions(this, prose)
        row.addView(prose, Ui.grow())
        return row
    }

    /** One row of the Thoughts panel: an action, or a sentence the model thought. */
    private fun thoughtRow(step: TrailStep, sheet: Sheet): View {
        if (step.kind == TrailStep.THINK) {
            return reasoningRow(step)
        }
        if (step.kind == TrailStep.NOTE) {
            return narrationRow(step)
        }

        val row = Ui.row(this)
        row.gravity = Gravity.TOP
        val vertical = Theme.dp(this, Ui.Space.M)
        row.setPadding(0, vertical, 0, vertical)

        val iconSize = Theme.dp(this, 20.0f)
        val icon = ImageView(this)
        icon.setImageDrawable(
            Icons.of(
                TrailView.iconOf(step),
                if (step.status == TrailStep.DONE || step.status == TrailStep.RUNNING) {
                    Theme.TEXT_MUTED
                } else {
                    Theme.TEXT_FAINT
                },
                Ui.STROKE
            )
        )
        val iconLp = LinearLayout.LayoutParams(iconSize, iconSize)
        iconLp.topMargin = Theme.dp(this, 2.0f)
        row.addView(icon, iconLp)

        val texts = Ui.column(this)
        val textsLp = Ui.grow()
        textsLp.marginStart = Theme.dp(this, Ui.Space.M)
        row.addView(texts, textsLp)

        // Label and outcome on one line: the label says what was attempted, the
        // duration and the state say how it went. The panel showed neither, so a
        // failed step was indistinguishable from a successful one and a step that
        // took a minute looked exactly like one that took 40ms.
        val head = Ui.row(this)
        val label = TextView(this)
        label.text = step.label
        label.textSize = Ui.Type.BODY
        label.typeface = Theme.ui()
        label.setTextColor(if (step.status == TrailStep.RUNNING) Theme.TEXT else Theme.TEXT)
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        Ui.rowLabel(label)
        head.addView(label, Ui.grow())

        outcomeMark(step)?.let { head.addView(it) }
        texts.addView(head, Ui.matchWrap())

        if (step.detail.isNotBlankJava()) {
            val detail = TextView(this)
            detail.text = step.detail
            detail.textSize = Ui.Type.LABEL
            detail.setTypeface(Theme.ui(), Typeface.ITALIC)
            detail.setTextColor(Theme.TEXT_FAINT)
            detail.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            detail.setLineSpacing(Theme.dpf(this, 2.0f), 1.0f)
            val detailLp = Ui.matchWrap()
            detailLp.topMargin = Theme.dp(this, 2.0f)
            texts.addView(detail, detailLp)
        }

        val hosts = step.domains()
        if (step.resultCount > 0 || hosts.isNotEmpty() || step.hasChangeCounts()) {
            val trailing = Ui.row(this)
            if (step.hasChangeCounts()) {
                trailing.addView(changeCounts(step.added, step.removed))
            }
            if (hosts.isNotEmpty()) {
                trailing.addView(
                    SourceCluster(this, hosts, TRAIL_CLUSTER),
                    LinearLayout.LayoutParams(
                        SourceCluster.widthFor(this, hosts.size, TRAIL_CLUSTER),
                        Theme.dp(this, SourceCluster.SIZE_DP)
                    )
                )
            }
            if (step.resultCount > 0) {
                val count = TextView(this)
                count.text = Lang.num(this, step.resultCount) + " " + Fa.TRAIL_RESULTS
                count.textSize = Ui.Type.LABEL
                count.typeface = Theme.ui()
                count.setTextColor(Theme.TEXT_MUTED)
                val countLp = Ui.wrapWrap()
                countLp.marginStart = Theme.dp(this, Ui.Space.S)
                trailing.addView(count, countLp)
            }
            val trailingLp = Ui.wrapWrap()
            trailingLp.marginStart = Theme.dp(this, Ui.Space.S)
            trailingLp.topMargin = Theme.dp(this, 1.0f)
            row.addView(trailing, trailingLp)
        }

        // Three things a row can open, and never more than one: the reason it
        // failed, the results behind a search, or the change behind an edit.
        //
        // The reason comes FIRST, and that ordering is the point. A failed step is
        // the row a user has actually come here to understand, and until now it was
        // the one row in the panel that could not be opened at all — a search had
        // its results and an edit had its diff, while a failure had a red word and
        // nothing behind it.
        if (step.hasReason()) {
            row.isClickable = true
            row.background = Theme.rippleTransparent(Theme.R_SM, this)
            row.setOnClickListener {
                Ui.tick(row)
                sheet.dismiss()
                showFailureSheet(step)
            }
        } else if (step.hasResults()) {
            row.isClickable = true
            row.background = Theme.rippleTransparent(Theme.R_SM, this)
            row.setOnClickListener {
                Ui.tick(row)
                sheet.dismiss()
                showWebResultsSheet(step)
            }
        } else if (step.hasDiff()) {
            row.isClickable = true
            row.background = Theme.rippleTransparent(Theme.R_SM, this)
            row.setOnClickListener {
                Ui.tick(row)
                sheet.dismiss()
                showDiffSheet(step)
            }
        }
        return row
    }

    /**
     * A reasoning row: the model's own words, marked as a quotation.
     *
     * It used to be bare prose with no chrome at all, on the theory that reasoning
     * should read as the model talking. In a list that now carries timings, outcomes
     * and change counts, bare prose reads as a row that failed to render — so it gets
     * the one mark that says "quoted, not narrated": a hairline rail down its start
     * edge, which is the same device the app already uses for the permission card.
     */
    private fun reasoningRow(step: TrailStep): View {
        val box = Ui.column(this)
        box.background = Ui.railPanel(this, Theme.R_SM, Theme.BORDER_HI)
        box.setPaddingRelative(
            Theme.dp(this, Ui.Space.M), Theme.dp(this, Ui.Space.S),
            Theme.dp(this, Ui.Space.S), Theme.dp(this, Ui.Space.S)
        )

        val head = Ui.row(this)
        // The reasoning mark, on the row as well as the label.
        //
        // This is the only home the model's thinking has now — the separate
        // "Model reasoning" card in the transcript is gone — so the row has to
        // announce itself rather than relying on a card title elsewhere to have
        // done it. The glyph is "neuron", the same one the collapsed strip draws
        // for a THINK step, so the two surfaces name the same thing identically.
        val mark = ImageView(this)
        mark.setImageDrawable(Icons.of("neuron", Theme.TEXT_FAINT, Ui.STROKE))
        mark.scaleType = ImageView.ScaleType.FIT_CENTER
        mark.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // Named distinctly from the watermark's own `markLp` a few hundred lines
        // up: that one is a FrameLayout.LayoutParams, and tools/check_layoutparams
        // resolves these bindings by name across the file, so reusing the name
        // makes the checker report the OTHER site as a mismatch.
        val reasonMarkSize = Theme.dp(this, 14.0f)
        val reasonMarkLp = LinearLayout.LayoutParams(reasonMarkSize, reasonMarkSize)
        reasonMarkLp.marginEnd = Theme.dp(this, Ui.Space.S)
        head.addView(mark, reasonMarkLp)

        val tag = TextView(this)
        tag.text = Fa.THINKING_LABEL
        tag.textSize = Ui.Type.MICRO
        tag.typeface = Theme.uiSemi()
        tag.setTextColor(Theme.TEXT_FAINT)
        // Latin only: Persian letters are drawn joined, and tracking pushes those
        // joins apart. Same reasoning as Ui.sectionLabel.
        tag.letterSpacing = if (Lang.farsi(this)) 0.0f else 0.02f
        Ui.rowLabel(tag)
        head.addView(tag, Ui.grow())
        if (step.status == TrailStep.RUNNING) {
            head.addView(Ui.pulseDots(this, Theme.TEXT_FAINT, 3.5f), Ui.wrapWrap())
        }
        box.addView(head, Ui.matchWrap())

        val prose = TextView(this)
        prose.text = step.detail
        prose.textSize = Ui.Type.LABEL
        prose.typeface = Theme.ui()
        prose.setTextColor(Theme.TEXT_MUTED)
        prose.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        prose.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        prose.setTextIsSelectable(true)
        MarkdownRenderer.installSelectionActions(this, prose)
        val proseLp = Ui.matchWrap()
        proseLp.topMargin = Theme.dp(this, 2.0f)
        box.addView(prose, proseLp)

        val boxLp = Ui.matchWrap()
        boxLp.topMargin = Theme.dp(this, Ui.Space.M)
        boxLp.bottomMargin = Theme.dp(this, Ui.Space.XS)
        box.layoutParams = boxLp
        return box
    }

    /** How a step ended: its duration, and a word when that word is not "fine". */
    private fun outcomeMark(step: TrailStep): View? {
        val box = Ui.row(this)
        val state = when (step.status) {
            TrailStep.FAILED -> Fa.TRAIL_FAILED
            // Declining an action is a decision, not a fault. It used to be drawn as
            // "Failed" in the same red as a tool that broke, which told the user
            // their own answer was an error.
            TrailStep.REJECTED -> Fa.TRAIL_REJECTED
            TrailStep.STOPPED -> Fa.TRAIL_STOPPED
            TrailStep.RUNNING -> Fa.TRAIL_LIVE
            else -> ""
        }
        if (state.isNotEmpty()) {
            val mark = TextView(this)
            mark.text = state
            mark.textSize = Ui.Type.MICRO
            mark.typeface = Theme.uiSemi()
            mark.setTextColor(
                if (step.status == TrailStep.FAILED) Theme.DIFF_DEL else Theme.TEXT_FAINT
            )
            mark.maxLines = 1
            box.addView(mark, Ui.wrapWrap())
        }
        if (step.endedAt > 0L && step.startedAt > 0L) {
            val time = TextView(this)
            time.text = TrailView.duration(this, step.durationMs(step.endedAt))
            time.textSize = Ui.Type.MICRO
            time.typeface = Theme.ui()
            time.setTextColor(Theme.TEXT_FAINT)
            time.maxLines = 1
            val lp = Ui.wrapWrap()
            lp.marginStart = Theme.dp(this, Ui.Space.S)
            box.addView(time, lp)
        }
        if (box.childCount == 0) {
            return null
        }
        val boxLp = Ui.wrapWrap()
        boxLp.marginStart = Theme.dp(this, Ui.Space.S)
        box.layoutParams = boxLp
        return box
    }

    /**
     * The change one step made to one file, in red and green.
     *
     * This is the answer to "editing shows nothing and looks like it has hung". The
     * strip says which file and how far through a multi-edit it is while the work
     * happens; this is where the actual lines are, afterwards, from the trail —
     * so it survives a reopened conversation, which the old tool card never did
     * because no diff was ever persisted.
     */
    private fun showDiffSheet(step: TrailStep) {
        if (!step.hasDiff()) {
            return
        }
        val sheet = Sheet(this)
        sheet.plainTitle(Fa.TRAIL_CHANGES)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        val path = step.filePath.ifEmpty { step.label }
        val head = TextView(this)
        head.text = path
        head.textSize = Ui.Type.LABEL
        head.typeface = Theme.mono()
        head.setTextColor(Theme.TEXT)
        head.textDirection = View.TEXT_DIRECTION_LTR
        head.maxLines = 2
        head.ellipsize = TextUtils.TruncateAt.MIDDLE
        val headLp = Ui.matchWrap()
        headLp.bottomMargin = Theme.dp(this, Ui.Space.S)
        column.addView(head, headLp)

        column.addView(
            MarkdownRenderer.buildDiffCard(
                this, step.diffBefore, step.diffAfter, step.diffBefore.isEmpty()
            ),
            Ui.matchWrap()
        )

        if (step.diffClipped) {
            val note = TextView(this)
            note.text = Fa.TRAIL_CLIPPED
            note.textSize = Ui.Type.MICRO
            note.typeface = Theme.ui()
            note.setTextColor(Theme.TEXT_FAINT)
            val noteLp = Ui.matchWrap()
            noteLp.topMargin = Theme.dp(this, Ui.Space.S)
            column.addView(note, noteLp)
        }

        val columnLp = Ui.matchWrap()
        columnLp.bottomMargin = Theme.dp(this, Ui.Space.M)
        sheet.body.addView(column, columnLp)
        sheet.show()
    }

    /**
     * The Web Results panel: the actual results one search returned, each opening
     * its source in the phone's browser.
     *
     * The results were always there — the search tool builds titles and urls and
     * then flattens them into the string it hands the model. Nothing kept the
     * structure, so a row could say "10 results" and offer no way to see one.
     */
    /**
     * Why a step failed, in full.
     *
     * The end of the chain this release exists to build. A tool's failure reason
     * was handed to the model in complete detail and rendered on no screen at all,
     * so the app's worst experience was watching the same red row four times over
     * with nothing to read and nothing to tap. The engine records it now, the
     * activity row shows the first line of it, and this is where the rest lives.
     *
     * Selectable, because the useful thing to do with a diagnosis is often to send
     * it to somebody. Monospaced, because it is tool output — paths, quoted source,
     * counts — and proportional type makes that harder to read, not easier.
     */
    private fun showFailureSheet(step: TrailStep) {
        val headline = step.reason.trimJavaOrEmpty()
        val body = step.output.trimJavaOrEmpty()
        if (headline.isEmpty() && body.isEmpty()) {
            return
        }
        val sheet = Sheet(this)
        val rejected = step.status == TrailStep.REJECTED
        sheet.header(
            if (rejected) "minus" else "alert",
            if (rejected) Fa.TRAIL_REJECTED else Fa.TRAIL_REASON,
            step.label,
            if (rejected) Theme.TEXT_MUTED else Theme.DIFF_DEL
        )

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.layoutDirection = Lang.direction(this)

        if (headline.isNotEmpty()) {
            val lead = TextView(this)
            lead.text = headline
            lead.textSize = Ui.Type.LABEL
            lead.typeface = Theme.uiSemi()
            lead.setTextColor(Theme.TEXT)
            lead.setLineSpacing(Theme.dpf(this, 2.5f), 1.0f)
            // A tool's reason is frequently raw English even on a Persian screen,
            // and frequently starts with a path — let the line decide its own
            // direction from its own first strong character.
            lead.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            Ui.rowLabel(lead)
            val leadLp = Ui.matchWrap()
            leadLp.bottomMargin = Theme.dp(this, Ui.Space.M)
            column.addView(lead, leadLp)
        }

        // The step's own path, when it has one — the row shows it, and the sheet
        // that explains the row should not make the user go back for it.
        val path = step.filePath.trimJavaOrEmpty().ifEmpty { step.detail.trimJavaOrEmpty() }
        if (path.isNotEmpty() && path != headline) {
            val where = TextView(this)
            where.text = path
            where.textSize = Ui.Type.MICRO
            where.typeface = Theme.mono()
            where.setTextColor(Theme.TEXT_MUTED)
            // A path is an identifier: LTR whatever the interface reads as, or its
            // separators and extension land on the wrong end of the line.
            where.layoutDirection = View.LAYOUT_DIRECTION_LTR
            where.textDirection = View.TEXT_DIRECTION_LTR
            where.background = Theme.sunkenCard(Theme.R_SM, this)
            val wherePad = Theme.dp(this, 9.0f)
            where.setPadding(wherePad, wherePad, wherePad, wherePad)
            // Selectable, and therefore hardened. Some MIUI builds crash the instant
            // smart-selection runs entity detection; installSelectionActions is the
            // ActionMode that prevents it, and a contract enforces the pairing
            // because the crash is OEM-specific and invisible from here.
            where.setTextIsSelectable(true)
            MarkdownRenderer.installSelectionActions(this, where)
            val whereLp = Ui.matchWrap()
            whereLp.bottomMargin = Theme.dp(this, Ui.Space.M)
            column.addView(where, whereLp)
        }

        if (body.isNotEmpty() && body != headline) {
            val out = TextView(this)
            out.text = body
            out.textSize = Ui.Type.META
            out.typeface = Theme.mono()
            out.setTextColor(Theme.TEXT_MUTED)
            out.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
            out.layoutDirection = View.LAYOUT_DIRECTION_LTR
            out.textDirection = View.TEXT_DIRECTION_LTR
            out.background = Theme.sunkenCard(Theme.R_SM, this)
            val outPad = Theme.dp(this, Ui.Space.M)
            out.setPadding(outPad, outPad, outPad, outPad)
            out.setTextIsSelectable(true)
            MarkdownRenderer.installSelectionActions(this, out)
            column.addView(out, Ui.matchWrap())
        }

        val columnLp = Ui.matchWrap()
        columnLp.bottomMargin = Theme.dp(this, Ui.Space.M)
        sheet.body.addView(column, columnLp)

        val copy = Ui.pillButton(this, Fa.COPY, "copy", Ui.SECONDARY) {
            val text = (if (headline.isEmpty()) "" else headline + "\n\n") + body
            MarkdownRenderer.copyText(this, Fa.TRAIL_REASON, text)
            Toast.makeText(this, Fa.COPIED, Toast.LENGTH_SHORT).show()
        }
        sheet.body.addView(copy, Ui.matchWrap())
        sheet.show()
    }

    private fun showWebResultsSheet(step: TrailStep) {
        val results = step.results()
        if (results.isEmpty()) {
            return
        }
        val sheet = Sheet(this)
        sheet.plainTitle(Fa.TRAIL_RESULTS_TITLE)

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        for (result in results) {
            column.addView(webResultCard(result))
        }
        val columnLp = Ui.matchWrap()
        columnLp.bottomMargin = Theme.dp(this, Ui.Space.M)
        sheet.body.addView(column, columnLp)
        sheet.show()
    }

    /** One result: its title, its host, and a tap that leaves the app. */
    private fun webResultCard(result: Web.SearchResult): View {
        val card = Ui.column(this)
        card.background = Theme.rippleOver(
            Theme.roundRect(Theme.SURFACE_2, Theme.R_MD, this), Theme.R_MD, this
        )
        val pad = Theme.dp(this, 14.0f)
        card.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = result.title
        title.textSize = Ui.Type.LABEL
        title.typeface = Theme.uiMedium()
        title.setTextColor(Theme.TEXT)
        title.maxLines = 2
        title.ellipsize = TextUtils.TruncateAt.END
        title.setLineSpacing(Theme.dpf(this, 2.0f), 1.0f)
        title.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        card.addView(title)

        val source = Ui.row(this)
        val sourceLp = Ui.matchWrap()
        sourceLp.topMargin = Theme.dp(this, Ui.Space.S)
        card.addView(source, sourceLp)

        val host = result.host()
        if (host.isNotEmpty()) {
            source.addView(
                SourceCluster(this, listOf(host), 1),
                LinearLayout.LayoutParams(
                    SourceCluster.widthFor(this, 1, 1),
                    Theme.dp(this, SourceCluster.SIZE_DP)
                )
            )
        }
        val domain = TextView(this)
        domain.text = if (host.isEmpty()) result.url else host
        domain.textSize = Ui.Type.META
        domain.typeface = Theme.ui()
        domain.setTextColor(Theme.TEXT_MUTED)
        domain.maxLines = 1
        domain.ellipsize = TextUtils.TruncateAt.MIDDLE
        domain.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val domainLp = Ui.grow()
        domainLp.marginStart = Theme.dp(this, Ui.Space.S)
        source.addView(domain, domainLp)

        val lp = Ui.matchWrap()
        lp.bottomMargin = Theme.dp(this, Ui.Space.S)
        card.layoutParams = lp
        card.setOnClickListener {
            Ui.tick(card)
            openLink(result.url)
        }
        Ui.pressScale(card)
        return card
    }

    /** The sources pill: every host the run touched, in one object. */
    private fun sourcesPill(hosts: List<String>): View {
        val pill = Ui.row(this)
        pill.background = Theme.roundRect(Theme.SURFACE_2, Theme.R_PILL, this)
        val padH = Theme.dp(this, Ui.Space.M)
        val padV = Theme.dp(this, Ui.Space.S)
        pill.setPadding(padH, padV, padH, padV)
        pill.addView(
            SourceCluster(this, hosts, TRAIL_CLUSTER),
            LinearLayout.LayoutParams(
                SourceCluster.widthFor(this, hosts.size, TRAIL_CLUSTER),
                Theme.dp(this, SourceCluster.SIZE_DP)
            )
        )
        val count = TextView(this)
        count.text = Lang.num(this, hosts.size) + " " + Fa.TRAIL_PAGES
        count.textSize = Ui.Type.LABEL
        count.typeface = Theme.uiMedium()
        count.setTextColor(Theme.TEXT_MUTED)
        val countLp = Ui.wrapWrap()
        countLp.marginStart = Theme.dp(this, Ui.Space.S)
        pill.addView(count, countLp)
        val lp = Ui.wrapWrap()
        lp.bottomMargin = Theme.dp(this, Ui.Space.S)
        pill.layoutParams = lp
        return pill
    }

    /**
     * True when [message] belongs to a run whose reasoning the Thoughts panel owns.
     *
     * Any message of a run that has a trail qualifies — the trail lives on the
     * run's FIRST message, so a later step has to look for the run rather than at
     * itself. A chat saved by an older build has no trail anywhere and keeps its
     * reasoning cards exactly as before.
     */
    private fun ownedByTrail(message: Message): Boolean {
        if (owningTrail(message.trail)) {
            return true
        }
        val current = chat ?: return false
        val history: List<Message> = synchronized(current.messages) { current.messages.toList() }
        val at = history.indexOfFirst { it === message }
        if (at < 0) {
            return false
        }
        // Walk back to the run's start: the first assistant message after the last
        // user turn is the one that carries the trail.
        for (i in at downTo 0) {
            val candidate = history[i]
            if (candidate.role == "user") {
                return false
            }
            if (owningTrail(candidate.trail)) {
                return true
            }
        }
        return false
    }

    /**
     * True when [trail] really is where this run's reasoning can be read.
     *
     * Suppressing the reasoning card is a trade: the text moves into the Thoughts
     * panel. It is only a trade if the panel is REACHABLE and POPULATED — so both
     * are required. A turn that called no tool has a hidden strip and thus no way
     * in; a chat saved by an earlier build has a trail but no reasoning rows in it.
     * In either case the card stays, which is what the old behaviour was anyway.
     */
    private fun owningTrail(trail: Trail?): Boolean {
        val value = trail ?: return false
        return value.didWork() && value.hasThoughts()
    }

    /** Opens [url] outside the app, quietly saying so if nothing can. */
    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (ignored: Throwable) {
            Toast.makeText(this, Fa.ERR_NO_BROWSER, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Draws (or re-draws) the activity strip that belongs to [owner].
     *
     * The view is remembered against the message it narrates, so an update while
     * the run is live re-binds the SAME view instead of adding a second strip —
     * the engine emits many trail changes per step, and every one of them arrives
     * here.
     */
    private fun addTrailRow(owner: Message, trail: Trail) {
        val container = messagesContainer ?: return
        // A FINISHED turn that used no tools has no work to narrate. Showing
        // "reviewed for 2 seconds" over a one-line reply is noise dressed as
        // progress — every turn thinks, so thinking alone is not something to
        // report afterwards.
        //
        // While the turn is still LIVE the calculus is the exact opposite, and this
        // is the fix for "I send a message and nothing happens". A run that is
        // connecting, waiting, or retrying has no tools and no reasoning yet, so the
        // old test hid the strip — and since the running indicator only appears once
        // a TOOL starts, the transcript was completely blank for the whole of a
        // failing request: about half a minute of backoff with the stop button lit
        // and not one word about what was going on. A live trail always has a phase
        // (the engine sets one before the first request), so `!isEmpty()` is true
        // from the moment of send and the strip carries the phase, the timer, and any
        // failure rows.
        //
        // A finished turn that only REASONED keeps its row, because the review
        // section is now the one and only home for reasoning. There used to be a
        // separate collapsible "Model reasoning" card in the transcript for
        // exactly this case, which meant the model's thinking appeared in one of
        // two completely different places depending on whether the turn happened
        // to call a tool. It is always here now, so `hasThoughts()` has to keep
        // the row alive or the thinking would have nowhere to live.
        //
        // A turn with no tools AND no reasoning still shows nothing: `didWork()`
        // and `hasThoughts()` are both false, the branch below removes the row,
        // and a one-line reply is left clean.
        val worthShowing = trail.didWork() || trail.hasThoughts() ||
            (trail.running && !trail.isEmpty())
        if (!worthShowing) {
            if (trailModel === trail) {
                trailView?.let { existing ->
                    (existing.parent as? ViewGroup)?.removeView(existing)
                    existing.detach()
                }
                trailView = null
                trailModel = null
                trailOwner = null
            }
            return
        }
        // Keyed on the TRAIL, not on the message that happens to own it.
        //
        // A run re-homes its trail whenever a step is dropped (an empty stall, a
        // malformed call), so the same run legitimately changes owning message
        // mid-flight. Keying on the message added a SECOND strip for the same run
        // each time that happened — a frozen one above a live one, up to seven of
        // them in a stall streak — because the old view was only detached, never
        // removed. Keying on the trail makes re-homing a no-op on screen.
        val existing = trailView
        if (trailModel === trail && existing != null && existing.parent === container) {
            trailOwner = owner
            existing.bind(trail)
            return
        }
        val view = TrailView(this)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = Theme.dp(this, Ui.Space.XS)
        lp.bottomMargin = Theme.dp(this, Ui.Space.XS)
        view.onOpenPanel = { showThoughtsSheet(trail) }
        view.onOpenStep = { step -> showWebResultsSheet(step) }
        view.onOpenFailure = { step -> showFailureSheet(step) }
        container.addView(view, lp)
        trailView?.detach()
        trailView = view
        trailModel = trail
        trailOwner = owner
        view.bind(trail)
    }

    /** Re-binds the live strip when the engine reports a change. */
    private fun refreshTrail(owner: Message) {
        // `owner.trail` is null for a re-homed run.
        //
        // The engine clears it whenever a step is dropped (an empty stall, a
        // malformed call) and re-attaches the trail to the next message. Between
        // those two moments every publish arrived here and returned having drawn
        // nothing — which silently swallowed exactly the states that most need to be
        // seen: the "Retrying" phase, and `settleAll`, whose whole job is to stop the
        // strip animating. A run that re-homed on its last iteration left a strip
        // spinning for ever. The strip on screen already holds the right model, so
        // fall back to it rather than to nothing.
        val trail = owner.trail ?: trailModel?.takeIf { trailView?.parent != null }
        if (trail == null) {
            // Still worth binding the board: the workflow lives on its own field and
            // was skipped entirely by the old early return.
            owner.workflow?.let { board -> bindWorkflow(owner, board) }
            return
        }
        // Identity, both ways. The strip on screen may belong to an EARLIER run in
        // this same chat, and re-binding that one to this run's trail would show
        // the new work in the old run's position, above the wrong answer. So a
        // different owner gets its own strip, appended in order — which is also
        // the path taken when a run's first event arrives before its message has
        // been rendered.
        if (trailModel !== trail || trailView == null) {
            addTrailRow(owner, trail)
        } else {
            trailOwner = owner
            trailView?.bind(trail)
        }
        owner.workflow?.let { board -> bindWorkflow(owner, board) }
        if (nearBottom) {
            scrollToBottom()
        }
    }

    /** Draws or re-binds the board that belongs to [owner]. */
    private fun bindWorkflow(owner: Message, board: Workflow) {
        if (workflowBoard !== board || workflowView == null ||
            workflowView?.parent == null
        ) {
            addWorkflowRow(board)
        } else {
            workflowView?.bind(board)
        }
    }

    /** Draws the Dynamic Workflow board. */
    private fun addWorkflowRow(board: Workflow) {
        val container = messagesContainer ?: return
        // Keyed on the BOARD, exactly as the strip is keyed on its trail. Without
        // this, a re-bind whose view had been detached added a second board, and a
        // transcript with two runs that both used Dynamic Workflow overwrote the
        // tracker with the last one — so only the final board could ever be updated
        // and the earlier one froze wherever it happened to be.
        val existing = workflowView
        if (workflowBoard === board && existing != null && existing.parent === container) {
            existing.bind(board)
            return
        }
        val view = WorkflowView(this)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = Theme.dp(this, Ui.Space.S)
        lp.bottomMargin = Theme.dp(this, Ui.Space.S)
        container.addView(view, lp)
        workflowView = view
        workflowBoard = board
        view.bind(board)
    }

    /**
     * Draws a board that belongs to history, without claiming the live tracker.
     *
     * A transcript rebuild walks every message, and an old run's board must be
     * redrawn without becoming the one the engine's next publish binds to — which is
     * what happened when the rebuild used the same entry point as the live path.
     */
    private fun addHistoricWorkflowRow(board: Workflow) {
        val container = messagesContainer ?: return
        val view = WorkflowView(this)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = Theme.dp(this, Ui.Space.S)
        lp.bottomMargin = Theme.dp(this, Ui.Space.S)
        container.addView(view, lp)
        view.bind(board)
        // The LAST board rendered is the newest, and the only one a live run could
        // still be writing to, so it is the one worth tracking.
        workflowView = view
        workflowBoard = board
    }

    private fun renderAll() {
        val container = messagesContainer ?: return
        val current = chat ?: return
        // The watermark tracks whether this chat is still empty, so it is
        // re-evaluated on every render: sending the first message makes it fade
        // out, and starting a new chat brings it back.
        updateWatermark()
        container.removeAllViews()
        continueCard = null
        // The copy panel lived inside the tree that was just torn down.
        openCopyPanel = null
        // So did the empty state's copy of the storage-permission row.
        currentContentBox = null
        currentStream?.detach()
        currentStream = null
        // BUGFIX: these two also point into the torn-down view tree; a stale
        // reference here let a late think-flush write into a historical card.
        runningIndicator = null
        runningLabel = null
        // Both point into the tree that was just torn down; the loop below
        // rebuilds them from the messages that own them.
        trailView?.detach()
        trailView = null
        trailModel = null
        trailOwner = null
        workflowView = null
        workflowBoard = null

        val history: List<Message> = synchronized(current.messages) { current.messages.toList() }
        if (history.isEmpty()) {
            container.addView(buildWelcome())
            return
        }
        val running = AgentBus.isRunningFor(current.id)
        for (i in history.indices) {
            val message = history[i]
            // The activity strip belongs to the first assistant message of its
            // run, and is drawn ABOVE that message, so it narrates the work that
            // produced everything below it.
            message.trail?.let { addTrailRow(message, it) }
            message.workflow?.let { addHistoricWorkflowRow(it) }
            // A folded step (the prose that introduced a tool call, and the tool
            // result itself) is represented inside the strip. Rendering it again
            // as a bubble is what used to put a raw ```json card in the middle of
            // the conversation.
            if (message.isStep) {
                continue
            }
            when (message.role) {
                "user" -> addUserRow(message)

                "assistant" -> {
                    // Only treat the last row as live when a run is actually active —
                    // a stale `streaming` flag (app killed mid-run) must not leave a
                    // frozen caret behind.
                    val live = running && i == history.size - 1
                    addAssistantRow(message, live)
                    if (live) {
                        streamPending = message.content
                        thinkPending = message.thinking
                        startCaret()
                        scheduleFlush()
                    }
                }

                "tool" -> addToolRow(message, null)
            }
        }
        // Put the "running <tool>…" pill back if a tool is still executing. The
        // rebuild dropped the view, and only the NEXT tool would have recreated
        // it — so a long download lost its progress row for minutes.
        if (running) {
            runningTool?.let { tool -> showRunningIndicator(tool, runningDetail) }
        } else {
            clearRunningTool()
        }
        scrollToBottom()
    }

    /**
     * The empty state: three suggestions, anchored to the BOTTOM of the
     * transcript so they sit just above the composer and read as things you
     * could type next.
     *
     * Everything that used to be here — an 88dp hero tile, a 30sp gradient
     * wordmark, a tagline, a status pill and three elevated cards, each with its
     * own staggered entrance — is gone. There is no logo and no watermark on the
     * chat screen; the app announces itself in the drawer and in Settings, and a
     * blank canvas is the point of a blank canvas.
     *
     * The status pill used to be the only route to [showSetupSheet] on an
     * unconfigured install. It no longer needs to be: [onSendOrStop] already
     * checks `prefs.isConfigured()` before it will start a run and opens the
     * setup sheet itself, so the first send is what surfaces it.
     */
    /**
     * Puts the watermark into the state the screen is actually in.
     *
     * Three states, all driven from here so they can never disagree:
     *  - transcript has messages -> gone (the conversation owns the screen)
     *  - keyboard up             -> smaller and higher (it must not sit behind
     *                               the composer or fight the suggestions)
     *  - otherwise               -> full size at its resting lift
     *
     * Animated rather than snapped, and idempotent, so the inset listener may
     * call it on every frame of the keyboard opening without stuttering.
     */
    /**
     * Pins the mark's resting position to the CURRENT screen size.
     *
     * Called from [buildUi] and again from [onConfigurationChanged], because the
     * manifest routes rotation and multi-window resizes there and that handler
     * deliberately does NOT rebuild the tree (a rebuild would tear down a live
     * stream). Without this the mark would keep a portrait resting spot in
     * landscape and read as mis-centred.
     *
     * Cheap enough to call on a resize: two layout-param writes and no measure of
     * anything else.
     */
    private fun syncWatermarkGeometry() {
        val mark = chatWatermark ?: return
        val params = mark.layoutParams
        if (params !is FrameLayout.LayoutParams) {
            return
        }
        val metrics = resources.displayMetrics
        // Sized off the SHORTER edge, not off the width.
        //
        // It was `widthPixels * 0.52`, while its resting centre is `heightPixels *
        // 0.40` — a box measured on one axis and positioned on the other. Whenever
        // the aspect ratio passed about 1.54, which is every landscape phone and
        // most landscape tablets, `top` came out NEGATIVE and the mark's layout box
        // hung off the top of the frame. `updateWatermark` largely rescued the
        // visible result by re-fitting against the measured band, so this showed up
        // as a mark that was mysteriously too big and too high rather than as an
        // obviously broken one. Measuring the box on the axis that constrains it
        // removes the contradiction instead of compensating for it.
        val shortEdge = Math.min(metrics.widthPixels, metrics.heightPixels)
        val size = (shortEdge * 0.52f).toInt()
        watermarkSize = size
        watermarkRestCentre = metrics.heightPixels * WATERMARK_LIFT
        // Never above the frame, whatever the aspect ratio.
        val top = Math.max(0.0f, watermarkRestCentre - size / 2.0f).toInt()
        if (params.width != size || params.height != size || params.topMargin != top) {
            params.width = size
            params.height = size
            params.topMargin = top
            mark.layoutParams = params
        }
    }

    /**
     * The band of screen the mark is allowed to occupy: the transcript's own
     * viewport, minus whatever the empty state has parked at the bottom of it.
     *
     * MEASURED, not calculated. Every previous version of this derived the mark's
     * home from `displayMetrics` and the IME inset, and that is the reason it
     * misbehaved: screen metrics do not know where the header ends, the IME inset
     * is unreliable below API 30 (it has to be inferred by subtracting two other
     * insets, which yields 0 on any OEM whose stable inset tracks the keyboard),
     * and neither of them knows that Android 15 stops resizing the window at all.
     *
     * The transcript's ScrollView already knows all of it. `fitsSystemWindows` on
     * the column pads it by the keyboard's height, so the view's own height IS the
     * free space above the keyboard, on every API level, with no arithmetic and no
     * per-version branch. Reading it also means the mark reacts to things no inset
     * ever reported — the composer growing to three lines, the storage prompt
     * appearing under the header, a foldable unfolding.
     *
     * @return `{ top, bottom }` in root-frame pixels, or null while unmeasured.
     */
    private fun watermarkBand(): FloatArray? {
        val root = rootFrame ?: return null
        val scroll = messagesScroll ?: return null
        if (root.height <= 0 || scroll.height <= 0 || !scroll.isLaidOut) {
            return null
        }
        val rootAt = IntArray(2)
        val scrollAt = IntArray(2)
        root.getLocationInWindow(rootAt)
        scroll.getLocationInWindow(scrollAt)
        val top = (scrollAt[1] - rootAt[1]).toFloat()
        var bottom = top + scroll.height.toFloat()
        // The suggestion rows are pinned to the bottom of this same viewport, so
        // the mark has to stop above them — this is the overlap the report
        // describes as the logo "mixing into the text".
        welcomeBlock?.let { block ->
            if (block.isLaidOut) {
                var used = 0
                for (i in 0 until block.childCount) {
                    used += block.getChildAt(i).height
                }
                if (used > 0) {
                    bottom -= used.toFloat() + Theme.dpf(this, Ui.Space.M)
                }
            }
        }
        if (bottom <= top) {
            return null
        }
        return floatArrayOf(top, bottom)
    }

    private fun updateWatermark() {
        val mark = chatWatermark ?: return
        val empty = chat?.let { c ->
            synchronized(c.messages) { c.messages.isEmpty() }
        } ?: true
        if (!empty) {
            if (mark.visibility != View.GONE) {
                mark.animate().cancel()
                mark.animate().alpha(0.0f)
                    .setDuration(Ui.D_FAST)
                    // The hide branch used to be the one animation in this file
                    // with no interpolator at all, three lines from a show branch
                    // that had one. A linear fade next to an eased one is visible.
                    .setInterpolator(Ui.easeOut())
                    .withEndAction { mark.visibility = View.GONE }
                    .start()
            }
            return
        }
        val rest = if (Theme.DARK) WATERMARK_ALPHA_DARK else WATERMARK_ALPHA_LIGHT
        // Centred in whatever room is actually left, and scaled to fit it.
        //
        // This is the behaviour the reference shows and the report asks for: as the
        // keyboard opens, the space above it shrinks, so the mark shrinks with it
        // and rises to stay in the middle of what remains — then returns to full
        // size and its resting spot when the keyboard closes. Both halves fall out
        // of one measurement, which is why they can no longer disagree.
        val band = watermarkBand()
        val target: Float
        val scale: Float
        if (band != null) {
            val available = band[1] - band[0]
            target = band[0] + available / 2.0f
            // FILL, not the whole band: a mark that touches the header and the
            // composer at once reads as a background texture rather than a logo.
            val desired = Math.min(watermarkSize.toFloat(), available * WATERMARK_FILL)
            scale = Math.max(
                WATERMARK_MIN_SCALE,
                if (watermarkSize > 0) desired / watermarkSize.toFloat() else 1.0f
            )
        } else {
            // Not laid out yet (first frame). The measured band is unavailable, so
            // fall back to the fixed resting origin and the IME height — the same
            // arithmetic as before, kept only for this one frame. The layout
            // listener re-runs with real geometry as soon as there is any.
            val screen = resources.displayMetrics.heightPixels.toFloat()
            target = if (imeInsetPx > 0) {
                Math.max(
                    (screen - imeInsetPx.toFloat()) / 2.0f,
                    statusInsetPx + Theme.dpf(this, 56.0f + Ui.Space.S) +
                        watermarkSize * WATERMARK_MIN_SCALE / 2.0f
                )
            } else {
                watermarkRestCentre
            }
            scale = if (imeInsetPx > 0) WATERMARK_FILL else 1.0f
        }
        val wasHidden = mark.visibility != View.VISIBLE
        mark.visibility = View.VISIBLE
        if (wasHidden) {
            mark.alpha = 0.0f
        }
        // One animator, cancelled first. ViewPropertyAnimator picks up from
        // wherever the previous run had reached, so closing the keyboard halfway
        // through opening it reverses smoothly from that point instead of
        // snapping to the end of the outgoing animation and starting over.
        // Nothing moved: leave the running animation alone. Without this the
        // layout listener — which fires for every frame of the keyboard's own
        // animation — would cancel and restart the tween on each pass, and the
        // mark would crawl instead of gliding.
        val wantedY = target - watermarkRestCentre
        if (!wasHidden &&
            Math.abs(mark.translationY - wantedY) < WATERMARK_EPSILON_PX &&
            Math.abs(mark.scaleX - scale) < WATERMARK_EPSILON_SCALE &&
            Math.abs(mark.alpha - rest) < WATERMARK_EPSILON_SCALE
        ) {
            return
        }
        mark.animate().cancel()
        mark.animate()
            .alpha(rest)
            // The mark scales about its own centre, so moving the CENTRE is the
            // whole job — the shrink does not shift it and the two compose.
            .translationY(wantedY)
            .scaleX(scale)
            .scaleY(scale)
            // D_SLOW while the keyboard is what moved it: this is the largest
            // travel in the app and the token exists for exactly that, and it lands
            // the mark on roughly the same beat as the system's own keyboard
            // animation, which is what makes the two read as one movement. A
            // smaller adjustment (the composer growing a line) gets D_BASE, because
            // matching the keyboard's pace for a 20px move looks sluggish.
            .setDuration(
                when {
                    wasHidden -> Ui.D_BASE
                    keyboardUp || Math.abs(mark.translationY - wantedY) >
                        Theme.dpf(this, 48.0f) -> Ui.D_SLOW
                    else -> Ui.D_BASE
                }
            )
            .setInterpolator(Ui.ease())
            .start()
    }

    private fun buildWelcome(): View {
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        // BOTTOM, and MATCH_PARENT height against the fill-viewport ScrollView:
        // that combination is what pins the block to the bottom of an otherwise
        // empty transcript instead of stranding it under the header.
        column.gravity = Gravity.BOTTOM
        column.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        column.setPaddingRelative(
            Theme.dp(this, Ui.Space.XS), Theme.dp(this, 24.0f),
            Theme.dp(this, Ui.Space.XS), Theme.dp(this, Ui.Space.S)
        )

        // No storage prompt here any more: it lives pinned under the header, so a
        // second copy in the empty state would ask the same question twice on the
        // same screen.

        column.addView(suggestionRow("image", Fa.SUG_1))
        column.addView(suggestionRow("search", Fa.SUG_3))
        welcomeBlock = column
        // Built after the watermark's first placement, so tell it the free band
        // just changed shape.
        column.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateWatermark()
        }
        return column
    }

    /**
     * One suggestion: an outline glyph, a line of plain body text, and a ripple.
     * No card, no badge, no chevron and no entrance animation — a suggestion is
     * a sentence you can tap, not an object.
     */
    private fun suggestionRow(icon: String, label: String): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = Theme.dp(this, 52.0f)
        row.background = Theme.rippleTransparent(Theme.R_SM, this)
        // One chassis, left to right, like every other row in the app. The
        // suggestions themselves are English, so the glyph reads as the first
        // character of the sentence without any mirroring.
        row.layoutDirection = Lang.direction(this)
        row.setPaddingRelative(Theme.dp(this, Ui.Space.XS), 0, Theme.dp(this, Ui.Space.XS), 0)

        val glyph = ImageView(this)
        glyph.setImageDrawable(Icons.of(icon, Theme.TEXT_MUTED, Ui.STROKE))
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(this, Ui.Space.XL)
        val glyphLp = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphLp.marginEnd = Theme.dp(this, Ui.Space.L)
        row.addView(glyph, glyphLp)

        val text = TextView(this)
        text.text = label
        text.textSize = Ui.Type.BODY
        text.typeface = Theme.ui()
        text.setTextColor(Theme.TEXT)
        text.setLineSpacing(Theme.dpf(this, 2.0f), 1.0f)
        // The suggestions are localized prose, but a user on the English UI in an
        // RTL locale still needs them pinned beside their glyph.
        Ui.rowLabel(text)
        row.addView(
            text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        row.contentDescription = label
        row.setOnClickListener {
            input?.setText(label)
            input?.setSelection(label.length)
        }
        Ui.pressScale(row)
        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        row.layoutParams = rowLp
        return row
    }

    // =====================================================================
    // Message rows
    // =====================================================================

    private fun addUserRow(message: Message) {
        val container = messagesContainer ?: return
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        // Gravity.END, which is the RIGHT edge in both languages.
        //
        // The layout direction is pinned LTR for the whole app (see Lang.direction),
        // so END resolves to the right whatever the language — which is where the
        // outgoing turn belongs, and where ChatGPT and Grok both put it in Persian
        // too. This comment used to claim the opposite ("the left edge in Persian …
        // it mirrors for free"), which was true of the mirrored chassis and was
        // exactly the bug: under RTL, END *is* the left, so the user's own message
        // sat on the wrong side of the conversation.
        column.gravity = Gravity.END
        val columnLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // One gap, above. The turn rhythm is carried entirely by the space
        // BEFORE each turn now, so a question and its answer are 20dp apart and
        // two turns are 20dp apart — no accumulating top+bottom pairs.
        columnLp.topMargin = Theme.dp(this, Ui.Space.XL)
        column.layoutParams = columnLp

        if (message.attachments.isNotEmpty()) {
            val previews = LinearLayout(this)
            previews.orientation = LinearLayout.VERTICAL
            previews.gravity = Gravity.END
            for (attachment in message.attachments) {
                previews.addView(buildAttachmentPreview(attachment))
            }
            column.addView(previews)
        }

        if (message.content.isNotBlankJava()) {
            val bubble = TextView(this)
            bubble.text = message.content
            bubble.setTextColor(Theme.ON_BUBBLE_USER)
            bubble.textSize = Ui.Type.BODY
            bubble.setTextIsSelectable(true)
            MarkdownRenderer.installSelectionActions(this, bubble)
            bubble.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            // Persian script has tall ascenders and deep descenders, so it needs
            // more leading than Latin before it stops looking crowded.
            bubble.setLineSpacing(Theme.dpf(this, Ui.Space.XS), 1.0f)
            bubble.typeface = Theme.ui()
            // An evenly-rounded neutral chip. No stroke, no asymmetric "tail"
            // corner: the fill alone separates it, and a tail read as a
            // messaging-app speech bubble that inverted awkwardly between
            // languages. There is no avatar beside it either — position and fill
            // already say whose turn this is.
            // R_CARD (20dp) from the token set rather than a bare 20.0f literal, and
            // symmetric padding on the scale: 14/10/14/11 was four off-scale numbers
            // and one of them was asymmetric by a single dp, which is not a decision
            // anyone can reproduce.
            bubble.background = Theme.roundRect(Theme.BUBBLE_USER, Theme.R_CARD, this)
            bubble.setPaddingRelative(
                Theme.dp(this, Ui.Space.L), Theme.dp(this, Ui.Space.M),
                Theme.dp(this, Ui.Space.L), Theme.dp(this, Ui.Space.M)
            )
            // The question is a compact aside and the ANSWER owns the full
            // column, which is what makes the two turns instantly
            // distinguishable at a glance.
            bubble.maxWidth = proseWidth(0.8f)
            column.addView(
                bubble,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            // Tap the question once to reveal a Copy button under it.
            val original = message.content
            attachCopyPanel(column, bubble) { original }
        }

        container.addView(column)
    }

    /**
     * Removes the chat bubble of any turn that turned out to be a STEP.
     *
     * The invariant this enforces: only the model's FINAL message of a run stands
     * in the conversation. Everything it said on the way — "now let me improve the
     * avatar with a better gradient" — belongs in the review section, in order,
     * beside the work it introduced.
     *
     * `finalizeStep` already removes a folded step's row, and on a full rebuild
     * `renderAll` skips step messages outright, so most of the time this finds
     * nothing. It exists for the ordering it cannot rule out: a message is only
     * known to be a step at the moment it is finalized, that removal is posted to
     * the UI thread, and anything that posts ahead of it — a tool row, the next
     * message's own row — can leave the bubble on screen with `currentContentBox`
     * already pointing somewhere else. One sweep is cheaper than reasoning about
     * every interleaving, and the conversation is the surface where being wrong
     * shows most.
     */
    private fun foldFinishedSteps() {
        val container = messagesContainer ?: return
        var i = container.childCount - 1
        while (i >= 0) {
            val child = container.getChildAt(i)
            val owner = child?.tag as? Message
            // `streaming` is deliberately NOT tested. The engine marks a turn a step
            // the moment its tool call opens, which is while the message is still
            // streaming — and folding it right then is the whole point: the prose is
            // already in the review section, so leaving the bubble up would show it
            // twice and then delete one copy.
            if (owner != null && owner.isStep) {
                container.removeViewAt(i)
                if (currentContentBox?.parent === child) {
                    currentContentBox = null
                    currentStream?.detach()
                    currentStream = null
                }
            }
            i--
        }
    }

    private fun addAssistantRow(message: Message, live: Boolean) {
        val container = messagesContainer ?: return
        if (!live) {
            val peek = computeParts(message)
            if (!message.isError && peek.thinking.isEmpty() && peek.visible.isEmpty()) {
                // Tool-call-only step: the tool row below already tells the story;
                // don't add an empty assistant bubble.
                return
            }
        }
        // The assistant's turn is a full-width VERTICAL block with no avatar at
        // all. The 26dp brand tile that used to lead it is gone (there is no
        // logo on the chat screen), and with it the 39dp start indent every
        // assistant row, tool card and running pill was hanging off — the answer
        // now owns the whole column, which is what makes it read as the reply
        // rather than as a second, narrower kind of message.
        //
        // The row is deliberately still a VERTICAL wrapper around a separate
        // `content` box: finalizeStep() removes a tool-call-only step by walking
        // `box.parent` and checking it is a direct child of messagesContainer, so
        // collapsing the two levels into one would break that.
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        // Tagged with its message so [foldFinishedSteps] can find it again.
        row.tag = message
        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        // One gap, above, matching the user turn's rhythm exactly.
        rowLp.topMargin = Theme.dp(this, Ui.Space.XL)
        rowLp.marginStart = 0
        row.layoutParams = rowLp

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        row.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        if (live) {
            val streamBox = LinearLayout(this)
            streamBox.orientation = LinearLayout.VERTICAL
            content.addView(
                streamBox,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            currentContentBox = content
            // Live markdown: rendered incrementally while tokens stream in.
            val streaming = MarkdownRenderer.Streaming(this, streamBox)
            currentStream = streaming
            val visible = AgentEngine.stripToolCalls(Think.visible(message.content))
            lastVisible = visible
            streaming.update(visible)
        } else {
            val parts = computeParts(message)
            if (Fa.isStalledMessage(parts.visible)) {
                // App-authored, not model output: it is a statement ABOUT the run,
                // so it gets the run's own card rather than being rendered as
                // markdown in the model's voice.
                content.addView(buildUnfinishedCard(parts.visible))
            } else if (message.isError) {
                content.addView(buildErrorCard(message.content))
            } else if (parts.visible.isNotEmpty()) {
                val body = LinearLayout(this)
                body.orientation = LinearLayout.VERTICAL
                content.addView(
                    body,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                MarkdownRenderer.render(this, body, parts.visible)

                // Tap the answer once to reveal a Copy button under it. The
                // rendered answer is a tree of TextViews/cards, so the listener
                // goes on each leaf the user can actually tap. Copies the visible
                // answer only — no reasoning, no tool-call JSON.
                val answer = parts.visible
                attachCopyPanelDeep(content, body) { answer }
            }
        }

        container.addView(row)
    }

    /** Splits a finished turn into (merged reasoning, visible answer). */
    private fun computeParts(message: Message): Think.Parts {
        // Same order as the live flusher: reasoning out first, THEN tool calls
        // out of what is left. Running them the other way round here meant a
        // tool call written inside a <think> block was stripped during
        // streaming but preserved on finalize (or vice versa), so a card could
        // appear or vanish the instant a step finished.
        val split = Think.split(message.content)
        // The app's own "nothing was produced" marker is app-authored, not model
        // output, so it is re-localized to the CURRENT language instead of being
        // frozen in whichever one was active when the run stalled.
        val visible = AgentEngine.stripToolCalls(split.visible)
        if (Fa.isStalledMessage(visible)) {
            return Think.Parts(Think.merge(message.thinking, split.thinking), Fa.RUN_STALLED)
        }
        return Think.Parts(Think.merge(message.thinking, split.thinking), visible)
    }

    /**
     * True when the latest turn has no real final answer — i.e. the run ended
     * on a tool result or an empty assistant step (interrupted / stalled), so
     * the user is owed either an answer or a way to resume.
     */
    private fun lastTurnUnfinished(): Boolean {
        val current = chat ?: return false
        synchronized(current.messages) {
            for (i in current.messages.indices.reversed()) {
                val m = current.messages[i]
                when (m.role) {
                    "assistant" -> {
                        if (m.isError) {
                            return false // an error card was already shown
                        }
                        val visible =
                            AgentEngine.stripToolCalls(Think.visible(m.content)).trimJava()
                        // Language-independent: the marker was persisted in
                        // whatever language was active when the run stalled.
                        if (Fa.isStalledMessage(m.content)) {
                            return true // our own "nothing produced" marker
                        }
                        // real answer ⇒ finished; empty ⇒ unfinished
                        return visible.isEmpty()
                    }

                    "tool" -> return true // ended on a tool with no answer after it
                    "user" -> return true // user asked, never got answered
                }
            }
        }
        return false
    }

    /** Shows a non-blocking "run was interrupted — continue?" card, once. */
    private fun maybeShowContinueCard() {
        val container = messagesContainer ?: return
        if (isFinishing || isDestroyed || AgentBus.isBusy()) {
            return
        }
        if (!lastTurnUnfinished()) {
            return
        }
        // Respect a dismissal until the transcript actually changes.
        val size = chat?.let { c -> synchronized(c.messages) { c.messages.size } } ?: -1
        if (dismissedContinueAt >= 0 && dismissedContinueAt == size) {
            return
        }
        val existing = continueCard
        if (existing != null && existing.parent != null) {
            return // already showing
        }

        // The same card every other "this did not go to plan" message uses.
        //
        // It was a 2dp rail with the whole message in semibold 13sp — the
        // treatment for a one-line aside, applied to a card that has a statement,
        // an explanation and two actions in it. It also sat one glyph away from
        // being the error card, which it is not: nothing failed here, the run was
        // simply cut short. Sharing the builder settles both — the silhouette is
        // the one the user already recognises, and the muted "refresh" badge says
        // resume where the error card's red "alert" says something broke.
        val card = buildNoticeCard(
            "refresh",
            Theme.TEXT_MUTED,
            Fa.RUN_INTERRUPTED,
            Fa.RUN_CONTINUE,
            "play",
            {
                removeContinueCard()
                if (!prefs.isConfigured()) {
                    showSetupSheet()
                } else {
                    sendProgrammatic(Fa.RUN_CONTINUE_MSG)
                }
            },
            {
                // Remember WHICH transcript the user dismissed this for. Without
                // it the card came straight back on the next
                // onStart/onResume/theme change, because the run is still
                // genuinely unfinished.
                dismissedContinueAt = chat?.let { c ->
                    synchronized(c.messages) { c.messages.size }
                } ?: -1
                removeContinueCard()
            }
        )

        val cardLp = Ui.matchWrap()
        cardLp.topMargin = Theme.dp(this, Ui.Space.XL)
        container.addView(card, cardLp)
        continueCard = card
        scrollToBottom()
    }

    private fun removeContinueCard() {
        val card = continueCard
        if (card != null) {
            messagesContainer?.removeView(card)
            continueCard = null
        }
    }

    /**
     * One-time nudge to exempt the app from battery optimization, so the OS
     * doesn't kill the foreground run in the background. Shown once.
     */
    private fun maybeBatteryPrompt() {
        if (Build.VERSION.SDK_INT < 23 || prefs.batteryPromptShown() || !hasStorageAccess()) {
            return
        }
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            return
        }
        prefs.setBatteryPromptShown(true)

        val sheet = Sheet(this)
        sheet.header("zap", Fa.BATT_TITLE, null)
        val msg = TextView(this)
        msg.typeface = Theme.ui()
        msg.text = Fa.BATT_MSG
        msg.setTextColor(Theme.TEXT_MUTED)
        msg.textSize = Ui.Type.LABEL
        msg.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        val msgLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        msgLp.bottomMargin = Theme.dp(this, 16.0f)
        sheet.body.addView(msg, msgLp)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val later = Ui.pillButton(this, Fa.BATT_LATER, null, Ui.SECONDARY) { sheet.dismiss() }
        val laterLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        laterLp.marginEnd = Theme.dp(this, 8.0f)
        row.addView(later, laterLp)
        row.addView(
            Ui.pillButton(this, Fa.BATT_ALLOW, "check", Ui.PRIMARY) {
                sheet.dismiss()
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    } catch (ignored: Exception) {
                    }
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        sheet.body.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sheet.show()
    }

    /** Renders one stacked diff card per edit in a multi-edit (edits[]) call. */
    private fun addMultiEditDiffs(container: LinearLayout, edits: JSONArray) {
        for (i in 0 until edits.length()) {
            val edit = edits.optJSONObject(i) ?: continue
            val label = Ui.metaChip(
                this,
                "Edit " + num(i + 1) +
                    " of " + num(edits.length()),
                0,
                false
            )
            val labelLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            labelLp.topMargin = Theme.dp(this, if (i == 0) 0.0f else 8.0f)
            labelLp.bottomMargin = Theme.dp(this, 4.0f)
            container.addView(label, labelLp)
            container.addView(
                MarkdownRenderer.buildDiffCard(
                    this, edit.optStr("old_string", ""),
                    edit.optStr("new_string", ""), false
                )
            )
        }
    }

    /**
     * The error card: a 2dp [Theme.RED] rail on the quiet surface, the `"alert"`
     * glyph, and a semibold label in the NORMAL text colour.
     *
     * No red fill and no red text. In this palette `Theme.RED` is simply the
     * ink colour, so a "red" message would be indistinguishable from any other —
     * the rail, the glyph and the weight are what say "this went wrong", and
     * they say it identically in both themes.
     */
    /**
     * The one card the app uses to say "something did not go to plan".
     *
     * There used to be three treatments for this and only one of them was any
     * good. A provider rejection got this card — a filled panel with a hairline,
     * an icon chip and a headline over muted detail. A run the system cut short
     * got a bare 2dp rail. And "the response was left unfinished" — the single
     * message most likely to need explaining — got NOTHING: it was written into
     * the transcript as an ordinary assistant turn with no error flag, so it
     * rendered as plain markdown prose, indistinguishable from the model talking.
     *
     * They are now one builder, because they are one kind of object: a short
     * statement of what happened, optional detail, and the one action that
     * responds to it. What varies between them is the glyph, its tint, and where
     * the button goes — not the silhouette.
     *
     * [text] is split at the first newline: the first line is the headline and
     * the rest is detail. Providers put the useful sentence first and the
     * machine-readable trail after it, and the app's own messages are written the
     * same way, so the split lands correctly for both.
     */
    private fun buildNoticeCard(
        icon: String,
        tint: Int,
        text: String?,
        actionLabel: String,
        actionIcon: String,
        onAction: Runnable,
        onDismiss: Runnable?
    ): LinearLayout {
        val card = Ui.column(this)
        card.background = Theme.roundStroke(
            Theme.SURFACE_2, Theme.BORDER_HI, Theme.R_CARD, 1, this
        )
        Ui.roundClip(card, Theme.R_CARD)
        val pad = Theme.dp(this, Ui.Space.L)
        card.setPaddingRelative(pad, pad, pad, pad)

        val head = Ui.row(this)
        head.gravity = Gravity.TOP

        val badge = Ui.iconBadge(this, icon, tint, 34.0f, 18.0f, Theme.R_SM)
        val badgeLp = LinearLayout.LayoutParams(
            Theme.dp(this, 34.0f), Theme.dp(this, 34.0f)
        )
        badgeLp.marginEnd = Theme.dp(this, Ui.Space.M)
        head.addView(badge, badgeLp)

        val body = (text ?: Fa.ERR_UNKNOWN).replace("\u26a0", "").trimJava()
        val cut = body.indexOf('\n')
        val headline = if (cut > 0) body.substring(0, cut).trimJava() else body
        val detail = if (cut > 0) body.substring(cut + 1).trimJava() else ""

        val title = TextView(this)
        title.text = headline
        title.textSize = Ui.Type.LABEL
        title.typeface = Theme.uiSemi()
        title.setTextColor(Theme.TEXT)
        title.setLineSpacing(Theme.dpf(this, 2.0f), 1.0f)
        // A provider's error detail is frequently raw English, and the model may
        // have been answering in Persian, so FIRST_STRONG alone resolves the
        // paragraph in whichever direction it opens and the default START
        // alignment then aligns against THAT direction — stranding the text at the
        // far side of its weighted slot, a column away from the glyph at the row's
        // start edge. Ui.rowLabel adds the missing pin, which every other
        // glyph-plus-label row in this file uses.
        Ui.rowLabel(title)
        head.addView(
            title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        // Dismiss is a bare circular "x" rather than a second pill: there is one
        // action here worth a button, and giving the two equal visual weight made
        // the card look like a decision instead of an offer.
        if (onDismiss != null) {
            val close = Ui.circleButton(this, "x", 32.0f, 15.0f, Theme.TEXT_FAINT, 0) {
                onDismiss.run()
            }
            val closeLp = LinearLayout.LayoutParams(
                Theme.dp(this, 32.0f), Theme.dp(this, 32.0f)
            )
            closeLp.marginStart = Theme.dp(this, Ui.Space.S)
            head.addView(close, closeLp)
        }
        card.addView(head, Ui.matchWrap())

        if (detail.isNotEmpty()) {
            val more = TextView(this)
            more.text = detail
            more.textSize = Ui.Type.META
            more.typeface = Theme.ui()
            more.setTextColor(Theme.TEXT_MUTED)
            more.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
            more.setTextIsSelectable(true)
            MarkdownRenderer.installSelectionActions(this, more)
            Ui.rowLabel(more)
            val moreLp = Ui.matchWrap()
            moreLp.topMargin = Theme.dp(this, Ui.Space.S)
            // Indented to the headline's column, so the detail reads as belonging
            // to it rather than restarting under the glyph.
            moreLp.marginStart = Theme.dp(this, 34.0f + Ui.Space.M)
            card.addView(more, moreLp)
        }

        val fix = Ui.pillButton(this, actionLabel, actionIcon, Ui.SECONDARY) {
            onAction.run()
        }
        val fixLp = Ui.matchWrap()
        fixLp.topMargin = Theme.dp(this, Ui.Space.L)
        fixLp.marginStart = Theme.dp(this, 34.0f + Ui.Space.M)
        card.addView(fix, fixLp)

        val cardLp = Ui.matchWrap()
        cardLp.topMargin = Theme.dp(this, Ui.Space.XS)
        card.layoutParams = cardLp
        return card
    }

    /**
     * A failed request.
     *
     * Straight to the one screen that can fix it: every failure this card carries
     * is either a credential, an endpoint or a model, all three of which live in
     * Settings — and the alternative was the user hunting for them after reading
     * a 401.
     */
    private fun buildErrorCard(text: String?): View = buildNoticeCard(
        "alert",
        Theme.DIFF_DEL,
        text,
        Fa.PRE_OPEN_SETTINGS,
        "settings",
        { openSettings() },
        null
    )

    /**
     * A turn that produced nothing, or stopped before the work was done.
     *
     * Not an error, so not the red alert badge — the run did not fail, it ran out
     * of road, and the only thing worth offering is the way back onto it.
     */
    private fun buildUnfinishedCard(text: String?): View = buildNoticeCard(
        "refresh",
        Theme.TEXT_MUTED,
        text,
        Fa.RUN_CONTINUE,
        "play",
        {
            if (!prefs.isConfigured()) {
                showSetupSheet()
            } else {
                sendProgrammatic(Fa.RUN_CONTINUE_MSG)
            }
        },
        null
    )

    /**
     * One tool step: a collapsible card whose head says what happened in words.
     *
     * The whole `tone` scheme is gone — a per-tool-type colour, a raw mono
     * sub-label under the action, and a tinted wash behind the card. In a
     * monochrome system those were all the same grey anyway, so the distinction
     * moves entirely to [Tools.actionIcon] (twenty-plus distinct glyphs) and
     * [Tools.actionLabel]. A failed call is the one exception, and it is marked
     * the way everything else in this design marks a problem: a 2dp
     * [Theme.RED] start rail, the `"alert"` glyph, and a heavier label.
     */
    private fun addToolRow(message: Message, toolCall: AgentEngine.ToolCall?) {
        val container = messagesContainer ?: return
        val name = if (message.toolLog.isEmpty()) "tool" else message.toolLog[0]
        val result = message.content
        val problem = result.startsWith("ERROR") || result.startsWith("BLOCKED") ||
            result.startsWith("REJECTED")

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = if (problem) {
            Ui.railPanel(this, Theme.R_MD, Theme.RED)
        } else {
            Theme.roundRect(Theme.SURFACE_2, Theme.R_MD, this)
        }
        val cardLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        cardLp.topMargin = Theme.dp(this, Ui.Space.S)
        // Zero: the 39dp indent existed to clear the assistant avatar, and the
        // avatar is gone.
        cardLp.marginStart = 0

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.background = Theme.rippleTransparent(Theme.R_MD, this)
        head.minimumHeight = Theme.dp(this, 46.0f)
        head.setPaddingRelative(
            Theme.dp(this, 14.0f), Theme.dp(this, Ui.Space.S),
            Theme.dp(this, Ui.Space.M), Theme.dp(this, Ui.Space.S)
        )

        val glyph = ImageView(this)
        glyph.setImageDrawable(
            Icons.of(
                if (problem) "alert" else Tools.actionIcon(name), Theme.TEXT_MUTED, Ui.STROKE
            )
        )
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(this, 18.0f)
        val glyphLp = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphLp.marginEnd = Theme.dp(this, Ui.Space.M)
        head.addView(glyph, glyphLp)

        // Plain-language action ONLY. The raw tool name used to sit under it in
        // mono, which doubled the card's height for a string the user cannot act
        // on; the exact name is still one tap away, at the top of the output.
        val label = TextView(this)
        label.text = Tools.actionLabel(name)
        label.setTextColor(Theme.TEXT)
        label.textSize = Ui.Type.META
        label.typeface = if (problem) Theme.uiSemi() else Theme.uiMedium()
        label.setSingleLine(true)
        label.ellipsize = TextUtils.TruncateAt.END
        Ui.rowLabel(label)
        head.addView(
            label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        val chevron = ImageView(this)
        chevron.setImageDrawable(Icons.of("chevron-down", Theme.TEXT_FAINT, Ui.STROKE))
        chevron.scaleType = ImageView.ScaleType.FIT_CENTER
        chevron.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val chevronSize = Theme.dp(this, Ui.Space.L)
        val chevronLp = LinearLayout.LayoutParams(chevronSize, chevronSize)
        chevronLp.marginStart = Theme.dp(this, 6.0f)
        head.addView(chevron, chevronLp)
        card.addView(head)

        val details = LinearLayout(this)
        details.orientation = LinearLayout.VERTICAL
        details.visibility = View.GONE
        details.setPaddingRelative(
            Theme.dp(this, 14.0f), 0, Theme.dp(this, 14.0f), Theme.dp(this, 14.0f)
        )

        if (toolCall != null &&
            (Tools.ToolNames.EDIT_FILE == toolCall.name ||
                Tools.ToolNames.WRITE_FILE == toolCall.name)
        ) {
            val isWrite = Tools.ToolNames.WRITE_FILE == toolCall.name
            val edits = toolCall.args.optJSONArray("edits")
            if (!isWrite && edits != null && edits.length() > 0) {
                addMultiEditDiffs(details, edits)
            } else {
                details.addView(
                    MarkdownRenderer.buildDiffCard(
                        this,
                        toolCall.args.optStr("old_string", ""),
                        toolCall.args.optStr(if (isWrite) "content" else "new_string"),
                        isWrite
                    )
                )
            }
        }

        val output = TextView(this)
        output.text = Util.truncate(result, 4000)
        output.setTextColor(Theme.TEXT_MUTED)
        output.textSize = Ui.Type.MICRO
        output.typeface = Theme.mono()
        output.setTextIsSelectable(true)
        MarkdownRenderer.installSelectionActions(this, output)
        output.textDirection = View.TEXT_DIRECTION_LTR
        // Raw output is content, so it sits one step INTO the page — the same
        // sunken surface every code block in the app uses. Greyscale now: it was
        // already TEXT_MUTED, and the surface it sits on carries no tint.
        output.background = Theme.roundRect(Theme.SURFACE, Theme.R_SM, this)
        val outputPad = Theme.dp(this, 10.0f)
        output.setPadding(outputPad, outputPad, outputPad, outputPad)
        output.setLineSpacing(Theme.dpf(this, 2.0f), 1.0f)
        val outputLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        outputLp.topMargin = Theme.dp(this, 6.0f)
        details.addView(output, outputLp)
        card.addView(details)

        var open = false
        // Same per-card animation state as the reasoning card, for the same
        // reason: a double tap must not strand the details box GONE while the
        // toggle thinks it is open.
        val panel = Collapsible()
        head.setOnClickListener {
            open = !open
            chevron.animate().rotation(if (open) 180.0f else 0.0f)
                .setDuration(Ui.D_BASE).setInterpolator(Ui.ease()).start()
            if (open) {
                expandView(details, panel)
            } else {
                collapseView(details, panel)
            }
        }

        container.addView(card, cardLp)
    }

    private fun showRunningIndicator(tool: String, detail: String?) {
        val container = messagesContainer ?: return
        // Remembered so renderAll() (a theme rebuild) can put the row back
        // mid-step instead of leaving the user with no idea what is running.
        runningTool = tool
        runningDetail = detail

        val text = runningText(tool, detail)
        // Reuse the row when there already is one, and HAND THE TEXT OVER rather
        // than replacing the view.
        //
        // Every update used to destroy this row and build a fresh one, so a run
        // that touched six tools flashed six times in the same spot with no
        // relationship between them — and the pulse dots restarted their animation
        // from zero each time, which is the tell that gives it away as a rebuild.
        // Reusing the row keeps the dots turning through the whole run and lets the
        // one thing that actually changed, the sentence, change visibly.
        val existing = runningIndicator
        val live = runningLabel
        if (existing != null && existing.parent === container && live != null) {
            Ui.swapText(live, text)
            return
        }
        removeRunningIndicator()

        // A bare row on the ground: three pulse dots and a line of grey text.
        // The cyan pill this used to sit in was the loudest thing in the
        // transcript, for the one element that is guaranteed to disappear again.
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowLp.topMargin = Theme.dp(this, Ui.Space.M)
        rowLp.bottomMargin = Theme.dp(this, Ui.Space.XS)
        // Zero: the 39dp indent existed to clear the assistant avatar, and the
        // avatar is gone.
        rowLp.marginStart = 0

        // The platform's indeterminate ProgressBar cannot be themed reliably
        // across OEMs (Samsung and Xiaomi both override it), so it always
        // looked borrowed from another app. The pulse dots are ours, they match
        // the header hairline, and they cost one animator.
        val dotsLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dotsLp.marginEnd = Theme.dp(this, 10.0f)
        row.addView(Ui.pulseDots(this, Theme.TEXT_MUTED, 5.0f), dotsLp)

        val label = TextView(this)
        label.text = text
        label.setTextColor(Theme.TEXT_MUTED)
        label.textSize = Ui.Type.META
        label.typeface = Theme.ui()
        // FIRST_STRONG rather than forced LTR. The label is "Running" plus a
        // tool name and, often, a DETAIL taken from the tool's arguments — a
        // search query or a file name the user typed, which may well be Persian.
        // FIRST_STRONG lets whichever script opens the line decide its direction
        // and keeps the tool name an intact LTR run inside it, so the ellipsis
        // also lands on the correct visual side.
        label.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        label.setSingleLine(true)
        label.ellipsize = TextUtils.TruncateAt.END
        label.maxWidth = proseWidth(0.6f)
        row.addView(label)

        container.addView(row, rowLp)
        runningIndicator = row
        runningLabel = label
    }

    /** The one sentence the running row shows. */
    private fun runningText(tool: String, detail: String?): String {
        val suffix = if (detail.isNullOrEmpty()) "" else "  " + Util.truncate(detail, 46)
        return "Running " + tool + suffix + " \u2026"
    }

    private fun removeRunningIndicator() {
        val view = runningIndicator
        if (view != null) {
            messagesContainer?.removeView(view)
            runningIndicator = null
        }
        runningLabel = null
    }

    /** Forgets the running tool, so a later rebuild does not resurrect its pill. */
    private fun clearRunningTool() {
        runningTool = null
        runningDetail = null
    }

    // =====================================================================
    // Attachments
    // =====================================================================

    private fun openFileBrowser() {
        if (!hasStorageAccess()) {
            ensureStorageAccess(true)
            return
        }
        FileBrowser(this) { file ->
            val name = file.name
            val isDirectory = file.isDirectory
            val attachment: Message.Attachment
            if (isDirectory) {
                attachment = Message.Attachment(
                    name, file.absolutePath, "inode/directory", 0L, "folder"
                )
            } else {
                val mime = Util.mimeOf(name)
                attachment = Message.Attachment(
                    name, file.absolutePath, mime, file.length(), Util.kindOf(mime)
                )
                if (attachment.kind == "image") {
                    try {
                        attachment.dataUri = Util.base64DataUri(file, attachment.mime)
                    } catch (e: Exception) {
                    }
                }
            }
            pending.add(attachment)
            refreshAttachStrip()

            val field = input
            if (field != null) {
                var caret = Math.max(0, field.selectionStart)
                val text = field.text.toString()
                if (caret > 0 && caret <= text.length && text[caret - 1] == '@') {
                    field.text.delete(caret - 1, caret)
                    caret--
                }
                field.text.insert(
                    Math.min(caret, field.text.length),
                    "@" + name + (if (isDirectory) "/ " else " ")
                )
            }
        }.show()
    }

    private fun pickAttachment(imagesOnly: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = if (imagesOnly) "image/*" else "*/*"
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(
                Intent.createChooser(
                    intent, if (imagesOnly) Fa.ATTACH_IMAGE else Fa.ATTACH_FILE
                ),
                REQ_ATTACH
            )
        } catch (e: Exception) {
            Toast.makeText(this, Fa.ERR_NO_PICKER, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("startActivityForResult is still the simplest path for this app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, intent)
        if (requestCode == REQ_MANAGE) {
            updatePermBanner()
            return
        }
        if (requestCode == REQ_ATTACH && resultCode == RESULT_OK && intent != null) {
            val uris = ArrayList<Uri>()
            val clip = intent.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    uris.add(clip.getItemAt(i).uri)
                }
            } else {
                intent.data?.let { uris.add(it) }
            }
            for (uri in uris) {
                addAttachmentFromUri(uri)
            }
        }
    }

    /**
     * Ingests a picked file on the disk executor, not the main thread.
     *
     * This copies the whole file into the cache and, for images, reads it again
     * to build a base64 data URI — tens of megabytes of I/O plus encoding.
     * Doing that inline on the UI thread stalled the app for seconds and ANR'd
     * on a multi-select of large photos. (`diskExec` already existed for
     * exactly this and was never wired up to anything.)
     */
    private fun addAttachmentFromUri(uri: Uri) {
        val name = queryName(uri)
        val mime = contentResolver.getType(uri) ?: Util.mimeOf(name)
        val kind = Util.kindOf(mime)
        diskExec.execute {
            val cached = try {
                Util.cacheFromUri(this, uri, name)
            } catch (error: Exception) {
                null
            }
            if (cached == null) {
                ui.post {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, Fa.ERR_READ_FILE, Toast.LENGTH_SHORT).show()
                    }
                }
                return@execute
            }
            val attachment = Message.Attachment(
                name, cached.absolutePath, mime, cached.length(), kind
            )
            if (kind == "image") {
                try {
                    attachment.dataUri = Util.base64DataUri(cached, mime)
                } catch (e: Exception) {
                }
            }
            ui.post {
                if (isFinishing || isDestroyed) {
                    return@post
                }
                pending.add(attachment)
                refreshAttachStrip()
            }
        }
    }

    private fun queryName(uri: Uri): String {
        var name = "file"
        try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex("_display_name")
                        if (column >= 0) {
                            cursor.getString(column)?.let { name = it }
                        }
                    }
                } finally {
                    // close in finally so a throwing getString() can't leak the cursor
                    cursor.close()
                }
            }
        } catch (e: Exception) {
        }
        return name
    }

    private fun refreshAttachStrip() {
        val strip = attachStrip ?: return
        val wrap = attachScrollWrap ?: return
        if (pending.isEmpty()) {
            // Fades out instead of vanishing — this strip sits directly above
            // the composer, so a hard disappearance visibly jolts the input.
            //
            // The chips are dropped AFTER that fade, not before it. Clearing them
            // first (which is what this did) collapsed the wrap to zero height on
            // the very same frame, because its height comes from the strip — so
            // the 120ms fade animated an already-empty box and the jolt this
            // branch exists to prevent happened anyway.
            Ui.reveal(wrap, false)
            strip.postDelayed({
                if (pending.isEmpty()) {
                    strip.removeAllViews()
                }
            }, Ui.D_FAST + 40L)
            updateSendAvailability()
            return
        }
        strip.removeAllViews()
        Ui.reveal(wrap, true)

        for (attachment in pending) {
            val chip = LinearLayout(this)
            chip.orientation = LinearLayout.HORIZONTAL
            chip.gravity = Gravity.CENTER_VERTICAL
            // A flat SURFACE_2 chip with no border. The strip sits directly on
            // the ground beside the composer card, so a stroked chip read as a
            // second, competing outline next to the card's own.
            chip.background = Theme.roundRect(Theme.SURFACE_2, Theme.R_SM, this)
            chip.setPadding(
                Theme.dp(this, 7.0f), Theme.dp(this, 6.0f),
                Theme.dp(this, 7.0f), Theme.dp(this, 6.0f)
            )

            val path = attachment.path
            if (attachment.kind == "image" && path != null) {
                val thumb = ImageView(this)
                try {
                    decodeSampled(path, Theme.dp(this, 40.0f))?.let { thumb.setImageBitmap(it) }
                } catch (e: Exception) {
                }
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                val thumbSize = Theme.dp(this, 34.0f)
                val thumbLp = LinearLayout.LayoutParams(thumbSize, thumbSize)
                thumbLp.marginEnd = Theme.dp(this, 7.0f)
                Ui.roundClip(thumb, 8.0f)
                chip.addView(thumb, thumbLp)
            } else {
                // A bare outline glyph, not a tinted badge: the file's KIND is
                // carried by the glyph shape now, not by a colour.
                val icon = ImageView(this)
                icon.setImageDrawable(
                    Icons.of(kindIcon(attachment.kind), Theme.TEXT_MUTED, Ui.STROKE)
                )
                icon.scaleType = ImageView.ScaleType.FIT_CENTER
                icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                val iconSize = Theme.dp(this, 17.0f)
                val iconLp = LinearLayout.LayoutParams(iconSize, iconSize)
                iconLp.marginEnd = Theme.dp(this, Ui.Space.S)
                chip.addView(icon, iconLp)
            }

            val fullName = attachment.name ?: ""
            val label = TextView(this)
            label.text = if (fullName.length > 18) fullName.substring(0, 16) + "…" else fullName
            label.setTextColor(Theme.TEXT)
            label.textSize = Ui.Type.META
            label.typeface = Theme.uiMedium()
            label.textDirection = View.TEXT_DIRECTION_LTR
            chip.addView(label)

            val close = ImageView(this)
            close.setImageDrawable(Icons.of("x", Theme.TEXT_MUTED, Ui.STROKE))
            close.contentDescription = Fa.CLOSE
            // 32dp, up from 28 — the smallest target in the app, on the control
            // whose whole job is destructive. The padding grows with the box so the
            // glyph inside renders at exactly the size it did before.
            val closeBox = Theme.dp(this, 32.0f)
            val closePad = Theme.dp(this, 8.0f)
            close.setPadding(closePad, closePad, closePad, closePad)
            close.background = Theme.rippleTransparent(Theme.R_PILL, this)
            val closeLp = LinearLayout.LayoutParams(closeBox, closeBox)
            closeLp.marginStart = Theme.dp(this, 4.0f)
            close.setOnClickListener {
                pending.remove(attachment)
                refreshAttachStrip()
            }
            Ui.pressScale(close)
            chip.addView(close, closeLp)

            val chipLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            chipLp.marginEnd = Theme.dp(this, 6.0f)
            strip.addView(chip, chipLp)
        }
        updateSendAvailability()
    }

    private fun kindIcon(kind: String?): String = when (kind) {
        "image" -> "image"
        "video" -> "video"
        "audio" -> "music"
        "text" -> "file"
        "folder" -> "folder"
        else -> "paperclip"
    }

    private fun buildAttachmentPreview(attachment: Message.Attachment): View {
        val path = attachment.path

        if (attachment.kind == "image" && path != null) {
            val frame = FrameLayout(this)
            val image = ImageView(this)
            try {
                decodeSampled(path, Theme.dp(this, 200.0f))?.let { image.setImageBitmap(it) }
            } catch (e: Throwable) {
                // Throwable, not Exception: a malformed/huge image can raise
                // OutOfMemoryError, which must never take the app down while
                // simply rendering a chat.
            }
            image.adjustViewBounds = true
            image.maxHeight = Theme.dp(this, 190.0f)
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            frame.addView(
                image,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            Ui.roundClip(frame, Theme.R_MD)
            frame.foreground = Theme.roundStroke(0, Theme.BORDER_HI, Theme.R_MD, 1, this)
            val frameLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            frameLp.bottomMargin = Theme.dp(this, 5.0f)
            // marginSTART, unconditionally: the preview column is Gravity.END in
            // both languages now, so the breathing room always belongs on the
            // start side and mirrors itself. The old English/Persian branch put
            // it on the SAME side as the alignment under RTL, which pushed the
            // preview 40dp in from the edge it was already hugging.
            frameLp.marginStart = Theme.dp(this, 40.0f)
            frame.layoutParams = frameLp
            return frame
        }

        if (attachment.kind == "video" && path != null) {
            val frame = FrameLayout(this)
            val image = ImageView(this)
            var frameBitmap: Bitmap? = null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                // getFrameAtTime returns a FULL-resolution frame — a 4K video
                // yields a ~33 MB bitmap, decoded on the UI thread, which can OOM
                // or ANR. Ask for a thumbnail-sized frame where the API allows it.
                val thumbW = Theme.dp(this, 200.0f)
                val thumbH = Theme.dp(this, 130.0f)
                frameBitmap = if (Build.VERSION.SDK_INT >= 27) {
                    retriever.getScaledFrameAtTime(
                        1000000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        thumbW,
                        thumbH
                    )
                } else {
                    retriever.getFrameAtTime(1000000L)
                }
            } catch (e: Throwable) {
                // Throwable covers OutOfMemoryError from an oversized frame.
            } finally {
                try {
                    retriever.release()
                } catch (ignored: Throwable) {
                }
            }
            if (frameBitmap != null) {
                image.setImageBitmap(frameBitmap)
            } else {
                image.setBackgroundColor(Theme.SURFACE_2)
            }
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            val previewW = Theme.dp(this, 200.0f)
            val previewH = Theme.dp(this, 130.0f)
            frame.addView(image, FrameLayout.LayoutParams(previewW, previewH))

            val playWrap = LinearLayout(this)
            playWrap.gravity = Gravity.CENTER
            playWrap.background = Theme.roundRect(Theme.alpha(Theme.SCRIM, 136), 30.0f, this)
            val playBox = Theme.dp(this, 44.0f)
            val play = ImageView(this)
            play.setImageDrawable(Icons.filled("play", Theme.ON_SCRIM))
            val playSize = Theme.dp(this, 22.0f)
            // playWrap is a LinearLayout, so its child needs LinearLayout.LayoutParams.
            // LinearLayout.measureHorizontal casts unconditionally — a FrameLayout.LayoutParams
            // here is a ClassCastException the first time a video attachment is measured.
            playWrap.addView(play, LinearLayout.LayoutParams(playSize, playSize))
            val playWrapLp = FrameLayout.LayoutParams(playBox, playBox)
            playWrapLp.gravity = Gravity.CENTER
            frame.addView(playWrap, playWrapLp)

            Ui.roundClip(frame, Theme.R_MD)
            val frameLp = LinearLayout.LayoutParams(previewW, previewH)
            frameLp.bottomMargin = Theme.dp(this, 5.0f)
            // marginSTART, unconditionally: the preview column is Gravity.END in
            // both languages now, so the breathing room always belongs on the
            // start side and mirrors itself. The old English/Persian branch put
            // it on the SAME side as the alignment under RTL, which pushed the
            // preview 40dp in from the edge it was already hugging.
            frameLp.marginStart = Theme.dp(this, 40.0f)
            frame.layoutParams = frameLp
            return frame
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Theme.roundRect(Theme.SURFACE_2, Theme.R_MD, this)
        row.setPaddingRelative(
            Theme.dp(this, 10.0f), Theme.dp(this, 8.0f),
            Theme.dp(this, 12.0f), Theme.dp(this, 8.0f)
        )
        val icon = ImageView(this)
        icon.setImageDrawable(Icons.of(kindIcon(attachment.kind), Theme.TEXT_MUTED, Ui.STROKE))
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val iconSize = Theme.dp(this, 17.0f)
        val iconLp = LinearLayout.LayoutParams(iconSize, iconSize)
        iconLp.marginEnd = Theme.dp(this, 9.0f)
        row.addView(icon, iconLp)
        val label = TextView(this)
        label.typeface = Theme.ui()
        label.text = attachment.name + "  (" + Util.humanSize(attachment.size) + ")"
        label.setTextColor(Theme.TEXT)
        label.textSize = Ui.Type.META
        // A WRAP_CONTENT label on a row with a 40dp start margin: a long filename
        // ran straight off the end of the transcript column with nothing to stop it.
        label.setSingleLine(true)
        label.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        label.maxWidth = proseWidth(0.62f)
        row.addView(label)
        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowLp.bottomMargin = Theme.dp(this, 5.0f)
        // Same reasoning as the image/video previews above: START in both.
        rowLp.marginStart = Theme.dp(this, 40.0f)
        row.layoutParams = rowLp
        return row
    }

    private fun decodeSampled(path: String, target: Int): Bitmap? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, options)
        var sample = 1
        while (Math.max(options.outWidth, options.outHeight) / sample > target * 2) {
            sample *= 2
        }
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        return BitmapFactory.decodeFile(path, options)
    }

    // =====================================================================
    // Send / run
    // =====================================================================

    private fun onSendOrStop() {
        val send = sendBtn
        if (AgentBus.isBusy()) {
            send?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (AgentBus.requestCancel(true)) { // user pressed stop
                setStopping()
            }
            return
        }
        val current = chat ?: return
        val field = input ?: return
        val text = field.text.toString().trimJava()
        if (text.isEmpty() && pending.isEmpty()) {
            return
        }
        if (!prefs.isConfigured()) {
            showSetupSheet()
            return
        }
        if (current.messages.isEmpty()) {
            messagesContainer?.removeAllViews()
        }
        removeContinueCard()

        val message = Message("user", text)
        message.attachments.addAll(pending)
        synchronized(current.messages) {
            current.messages.add(message)
        }
        addUserRow(message)
        // The conversation has begun: retire the watermark immediately.
        updateWatermark()
        pending.clear()
        refreshAttachStrip()
        field.setText("")
        prevInputLen = 0
        hideKeyboard()

        if (Fa.isPlaceholderTitle(current.title)) {
            // Auto-title the conversation for the DRAWER; the header keeps the
            // product's name. Painting the fresh title here is what made the brand
            // flicker to a chat name on the first send of every conversation — and
            // a Persian title in a header now forced left-to-right reads wrong too.
            current.autoTitle()
            refreshChatList()
        }
        store.save(current)
        scrollToBottom()
        beginRun()
    }

    private fun sendProgrammatic(text: String) {
        if (AgentBus.isBusy()) {
            return
        }
        val current = chat ?: return
        val message = Message("user", text)
        synchronized(current.messages) {
            current.messages.add(message)
        }
        addUserRow(message)
        // The conversation has begun: retire the watermark immediately.
        updateWatermark()
        store.save(current)
        scrollToBottom()
        beginRun()
    }

    private fun beginRun() {
        val current = chat ?: return
        val runId = AgentBus.beginStarting(current.id, current)
        if (runId == 0L) {
            Toast.makeText(this, Fa.WORKING, Toast.LENGTH_SHORT).show()
            return
        }
        AgentBus.listener = uiListener
        setRunning(true)
        try {
            AgentService.start(this, current.id, runId)
            // Posted on a PROCESS-scoped handler, not this Activity's: onDestroy
            // clears every callback on `ui`, and losing this one left the global
            // run slot claimed forever — send stayed a stop button and starting
            // or switching a chat was permanently refused as "busy".
            AgentBus.watchdog.postDelayed({
                if (AgentBus.runId() == runId &&
                    AgentBus.state() == AgentBus.RunState.STARTING
                ) {
                    AgentBus.requestCancel()
                    AgentBus.finish(runId)
                    setRunning(false)
                    if (!isFinishing && !isDestroyed) {
                        val card = buildErrorCard(
                            "⚠ The service did not start in time; the run was released — please try again."
                        )
                        messagesContainer?.addView(card)
                        scrollToBottom()
                    }
                }
            }, 12000L)
        } catch (error: Exception) {
            AgentBus.finish(runId)
            setRunning(false)
            val card = buildErrorCard(
                "⚠ Could not start the service: " +
                    error.message
            )
            messagesContainer?.addView(card)
            scrollToBottom()
        }
    }

    private fun showSetupSheet() {
        val sheet = Sheet(this)
        sheet.header("settings", Fa.SETUP_TITLE, null)
        val msg = TextView(this)
        msg.typeface = Theme.ui()
        msg.text = Fa.SETUP_MSG
        msg.setTextColor(Theme.TEXT_MUTED)
        msg.textSize = Ui.Type.LABEL
        msg.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        val msgLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        msgLp.bottomMargin = Theme.dp(this, 18.0f)
        sheet.body.addView(msg, msgLp)
        sheet.body.addView(
            Ui.pillButton(this, Fa.SETUP_OPEN, "settings", Ui.PRIMARY) {
                sheet.dismiss()
                openSettings()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sheet.show()
    }

    // =====================================================================
    // AgentBus listener (unchanged wiring)
    // =====================================================================

    private inner class BusListener : AgentBus.UiListener {

        override fun onNewAssistantMessage(message: Message) {
            ui.post {
                removeRunningIndicator()
                clearRunningTool()
                // Anything the model said EARLIER in this run is, by the fact that
                // it is still talking, not the final answer. Fold it before the new
                // row goes in, so the conversation never accumulates the commentary.
                foldFinishedSteps()
                // The run's strip is drawn once, above the first message of the
                // run, before that message's own row.
                message.trail?.let { trail -> addTrailRow(message, trail) }
                addAssistantRow(message, true)
                startCaret()
                scrollToBottom()
            }
        }

        override fun onDelta(message: Message) {
            streamPending = message.content
            scheduleFlush()
        }

        override fun onThinking(message: Message) {
            thinkPending = message.thinking
            scheduleFlush()
        }

        override fun onStepFinalized(message: Message) {
            ui.post { finalizeStep(message) }
        }

        override fun onToolRunning(tool: String, detail: String) {
            ui.post {
                // The mode can change mid-conversation — approving a plan switches
                // the app to Accepting — so keep the pill honest without waiting for
                // a resume. It only animates when the mode ACTUALLY changed.
                refreshModePill()
                showRunningIndicator(tool, detail)
                scrollToBottom()
            }
        }

        override fun onToolMessage(message: Message) {
            // BUGFIX: `lastCall` is written by finalizeStep on the UI thread.
            // Reading it HERE (worker thread) could observe the previous step's
            // call when a tool finished faster than the UI queue drained,
            // attaching the wrong diff to the new tool row. Reading it inside
            // the posted runnable is ordered after finalizeStep's own post.
            ui.post {
                removeRunningIndicator()
                clearRunningTool()
                foldFinishedSteps()
                // A folded step lives in the activity strip, not in a row of its
                // own — adding a card for it too would show the same work twice.
                if (!message.isStep) {
                    addToolRow(message, lastCall)
                }
                scrollToBottom()
            }
        }

        override fun onTrailChanged(owner: Message) {
            ui.post { refreshTrail(owner) }
        }

        override fun onComplete() {
            // Read the bus state HERE, on the callback thread. Reading it inside
            // the posted runnable raced AgentService.finishRun, which saves the
            // chat and then flips the run to IDLE: by the time the UI ran, the
            // "stopping" flag could already be cleared, so the STOPPED toast
            // went missing and the interrupted-run continue card was swallowed
            // by an isBusy() guard that had not yet turned false.
            val wasStopping = AgentBus.isStopping()
            val byUser = AgentBus.userStopped
            ui.post {
                removeRunningIndicator()
                clearRunningTool()
                stopCaret()
                setRunning(false)
                if (wasStopping && byUser) {
                    Toast.makeText(this@MainActivity, Fa.STOPPED, Toast.LENGTH_SHORT).show()
                }
                // A run can end in a different mode than it began (PLAN auto-
                // escalates to ACCEPT the first time a change is needed).
                refreshModePill()
                refreshTitle()
                maybeShowPlan()
                // If the run ended without the user asking (system interruption,
                // stall) and the last turn has no final answer, offer to continue
                // instead of leaving the composer silent.
                if (!byUser) {
                    // Deferred one beat so the service's own finishRun (a full
                    // save plus fd.sync) has landed and isBusy() reflects it.
                    ui.postDelayed({ maybeShowContinueCard() }, 120L)
                }
            }
        }

        override fun onError(error: String) {
            ui.post {
                removeRunningIndicator()
                clearRunningTool()
                stopCaret()
                setRunning(false)
                // Persist the error as a real turn instead of a loose view. A bare
                // card lived only in the view tree, so switching theme (or
                // reopening the chat) erased it and the run looked as though it had
                // stopped for no reason. isError keeps it out of the model's replay
                // and renders it as the same red card.
                val current = chat
                if (current != null) {
                    val message = Message("assistant", error)
                    message.isError = true
                    synchronized(current.messages) {
                        current.messages.add(message)
                    }
                    try {
                        store.save(current)
                    } catch (ignored: Throwable) {
                    }
                    addAssistantRow(message, false)
                } else {
                    val card = buildErrorCard(error)
                    val cardLp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    cardLp.topMargin = Theme.dp(this@MainActivity, 4.0f)
                    cardLp.bottomMargin = Theme.dp(this@MainActivity, 4.0f)
                    messagesContainer?.addView(card, cardLp)
                }
                scrollToBottom()
            }
        }

        override fun onApprovalRequested(approval: AgentBus.PendingApproval) {
            ui.post {
                if (isFinishing || isDestroyed) {
                    return@post
                }
                // Exactly one sheet per approval. Redelivery fires on every
                // onStart/onResume/appearance rebuild, and without this guard
                // backgrounding the app during an approval stacked a second and
                // third identical modal on top of the first — each of which had
                // to be dismissed separately.
                if (shownApproval === approval) {
                    return@post
                }
                shownApproval = approval
                showApprovalSheet(approval, approval.tool, approval.args) { approved ->
                    approval.decide(approved)
                }
            }
        }
    }

    private fun buildListener() {
        uiListener = BusListener()
    }

    private fun finalizeStep(message: Message) {
        ui.removeCallbacks(flusher)
        stopCaret()
        flushScheduled = false
        streamPending = null
        thinkPending = null
        lastVisible = ""
        lastCall = AgentEngine.parseToolCall(Think.stripForModel(message.content))

        val box = currentContentBox
        // A folded step's prose has already been promoted to the activity strip's
        // phase line, so its bubble is removed rather than finished. This is the
        // moment the ```json card used to appear; now the whole row simply lifts
        // into the strip above it.
        if (message.isStep && box != null) {
            val row = box.parent as? View
            if (row != null && row.parent === messagesContainer) {
                messagesContainer?.removeView(row)
            }
            currentContentBox = null
            currentStream?.detach()
            currentStream = null
            message.trail?.let { refreshTrail(message) }
            scrollToBottom()
            return
        }
        if (box != null) {
            box.removeAllViews()
            val parts = computeParts(message)
            if (Fa.isStalledMessage(parts.visible)) {
                box.addView(buildUnfinishedCard(parts.visible))
            } else if (message.isError) {
                box.addView(buildErrorCard(message.content))
            } else if (parts.visible.isNotEmpty()) {
                val body = LinearLayout(this)
                body.orientation = LinearLayout.VERTICAL
                box.addView(
                    body,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                MarkdownRenderer.render(this, body, parts.visible)
                // The answer that just finished streaming is the one the user is
                // most likely to copy, so it needs the tap-to-copy panel too —
                // without this it only appeared after the transcript was rebuilt.
                val answer = parts.visible
                attachCopyPanelDeep(box, body) { answer }
            } else if (parts.thinking.isEmpty()) {
                // Tool-call-only step: remove the now-empty assistant row instead
                // of leaving a "working…" placeholder behind.
                val row = box.parent as? View
                if (row != null && row.parent === messagesContainer) {
                    messagesContainer?.removeView(row)
                }
            }
        }
        currentContentBox = null
        currentStream?.detach()
        currentStream = null
        scrollToBottom()
    }

    // =====================================================================
    // Approval sheet
    // =====================================================================

    private fun showApprovalSheet(
        /**
         * The approval this sheet is for, so dismissal can clear only its own
         * tracking.
         *
         * Needed because approvals are a QUEUE now. Several sub-agents can be
         * waiting at once, and answering one immediately surfaces the next — while
         * the answered sheet is still running its exit animation. An unconditional
         * `shownApproval = null` in that animation's end action therefore lands on
         * the NEW sheet's tracking, and the next redelivery stacks a duplicate
         * modal on top of it. Which is precisely what the one-sheet-at-a-time
         * guard exists to prevent.
         */
        owner: AgentBus.PendingApproval?,
        tool: String,
        args: JSONObject?,
        result: ApprovalResult
    ) {
        val sheet = Sheet(this)
        sheet.setCancelable(false)
        var decided = false
        val payload = args ?: JSONObject()
        val asker = owner?.agent.orEmpty()
        val waiting = AgentBus.outstandingApprovals() - 1
        val subtitle = StringBuilder(
            if (asker.isNotEmpty()) asker else Fa.APPROVE_SUBTITLE
        )
        // With up to three sub-agents in flight, "the assistant wants to do this"
        // does not say enough to answer — and a queue behind it changes what
        // answering means, so both are on the sheet.
        if (waiting > 0) {
            subtitle.append(" \u00b7 ").append(Fa.APPROVE_QUEUED_N.format(Lang.num(this, waiting)))
        }
        sheet.header("shield", Fa.APPROVE_TITLE, subtitle.toString())

        val toolRow = LinearLayout(this)
        toolRow.orientation = LinearLayout.HORIZONTAL
        toolRow.gravity = Gravity.CENTER_VERTICAL
        // No tone follows the blast radius any more. GREEN-for-mutating and
        // CYAN-for-read both resolve to grey in this palette, so the only thing
        // that survived was a brightness difference — which made a read-only
        // approval's glyph look like a disabled control beside an identical
        // mutating one. The blast radius is stated in words by
        // Tools.actionLabel, drawn by Tools.actionIcon, and where it is
        // destructive it gets the RED start rail and the WILL_DELETE warning
        // further down this sheet.
        toolRow.background = Theme.roundRect(Theme.SURFACE_2, Theme.R_MD, this)
        val toolPad = Theme.dp(this, 11.0f)
        toolRow.setPadding(toolPad, toolPad, toolPad, toolPad)
        val toolIcon = Ui.iconBadge(
            this, Tools.actionIcon(tool), Theme.TEXT_MUTED, 34.0f, 17.0f, 11.0f
        )
        val toolIconLp = LinearLayout.LayoutParams(
            Theme.dp(this, 34.0f), Theme.dp(this, 34.0f)
        )
        toolIconLp.marginEnd = Theme.dp(this, 11.0f)
        toolRow.addView(toolIcon, toolIconLp)

        // What is actually about to happen, in words, above the raw tool name.
        // Being asked to approve `extract_archive_entry` is not consent — it is
        // a quiz.
        val toolTexts = LinearLayout(this)
        toolTexts.orientation = LinearLayout.VERTICAL
        val actionLabel = TextView(this)
        actionLabel.text = Tools.actionLabel(tool)
        actionLabel.setTextColor(Theme.TEXT)
        actionLabel.textSize = Ui.Type.LABEL
        actionLabel.typeface = Theme.uiBold()
        toolTexts.addView(actionLabel)
        val toolLabel = TextView(this)
        toolLabel.text = tool
        toolLabel.setTextColor(Theme.TEXT_FAINT)
        toolLabel.typeface = Theme.mono()
        toolLabel.textSize = Ui.Type.MICRO
        toolLabel.textDirection = View.TEXT_DIRECTION_LTR
        // Same stranding fix as the tool card: keep the raw name under its label.
        toolLabel.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        toolTexts.addView(toolLabel)
        toolRow.addView(
            toolTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        val toolRowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        toolRowLp.bottomMargin = Theme.dp(this, 10.0f)
        sheet.body.addView(toolRow, toolRowLp)

        if (Tools.ToolNames.EDIT_FILE == tool || Tools.ToolNames.WRITE_FILE == tool) {
            val isWrite = Tools.ToolNames.WRITE_FILE == tool
            val pathLabel = TextView(this)
            pathLabel.text =
                (if (isWrite) Fa.NEW_FILE + " · " else "") + payload.optStr("path", "")
            pathLabel.setTextColor(Theme.TEXT_MUTED)
            pathLabel.textSize = Ui.Type.MICRO
            pathLabel.typeface = Theme.mono()
            // FIRST_STRONG: the path segments can be user-named files in any
            // script, so the run that opens the label decides its direction while
            // the rest stays intact beside it.
            pathLabel.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            pathLabel.background = Theme.sunkenCard(Theme.R_SM, this)
            val pathPad = Theme.dp(this, 9.0f)
            pathLabel.setPadding(pathPad, pathPad, pathPad, pathPad)
            val pathLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            pathLp.bottomMargin = Theme.dp(this, 8.0f)
            sheet.body.addView(pathLabel, pathLp)

            val scroll = ScrollView(this)
            scroll.isVerticalScrollBarEnabled = false
            val edits = payload.optJSONArray("edits")
            if (!isWrite && edits != null && edits.length() > 0) {
                val column = LinearLayout(this)
                column.orientation = LinearLayout.VERTICAL
                addMultiEditDiffs(column, edits)
                scroll.addView(column)
            } else {
                scroll.addView(
                    MarkdownRenderer.buildDiffCard(
                        this,
                        payload.optStr("old_string", ""),
                        payload.optStr(if (isWrite) "content" else "new_string"),
                        isWrite
                    )
                )
            }
            val scrollLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 240.0f)
            )
            scrollLp.bottomMargin = Theme.dp(this, 14.0f)
            sheet.body.addView(scroll, scrollLp)
        } else if (Tools.ToolNames.DELETE == tool) {
            val warn = LinearLayout(this)
            warn.orientation = LinearLayout.HORIZONTAL
            warn.gravity = Gravity.CENTER_VERTICAL
            warn.background = Theme.tonePanel(Theme.RED, Theme.R_MD, this)
            val warnPad = Theme.dp(this, 12.0f)
            warn.setPadding(warnPad, warnPad, warnPad, warnPad)
            val warnIcon = Ui.iconBadge(this, "alert", Theme.RED, 32.0f, 17.0f, 10.0f)
            val warnIconLp = LinearLayout.LayoutParams(
                Theme.dp(this, 32.0f), Theme.dp(this, 32.0f)
            )
            warnIconLp.marginEnd = Theme.dp(this, 10.0f)
            warn.addView(warnIcon, warnIconLp)
            val warnText = TextView(this)
            warnText.text = Fa.WILL_DELETE + "\n" + payload.optStr("path", "")
            warnText.setTextColor(Theme.RED)
            warnText.textSize = Ui.Type.META
            warnText.typeface = Theme.uiMedium()
            warnText.setLineSpacing(Theme.dpf(this, 2.5f), 1.0f)
            // Two lines: a Persian warning, then a path. FIRST_STRONG resolves
            // PER PARAGRAPH, so the sentence goes RTL next to the red badge and
            // the path line stays LTR — forced LTR left-aligned both.
            warnText.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            warn.addView(
                warnText,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            )
            val warnLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            warnLp.bottomMargin = Theme.dp(this, 14.0f)
            sheet.body.addView(warn, warnLp)
        } else {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.background = Theme.sunkenCard(Theme.R_MD, this)
            val boxPad = Theme.dp(this, 11.0f)
            box.setPadding(boxPad, boxPad, boxPad, boxPad)
            val keys = payload.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val line = TextView(this)
                line.text = key + ": " + Util.truncate(payload.optStr(key), 300)
                line.setTextColor(Theme.TEXT_MUTED)
                line.textSize = Ui.Type.META
                line.typeface = Theme.mono()
                line.setLineSpacing(Theme.dpf(this, 2.0f), 1.0f)
                line.textDirection = View.TEXT_DIRECTION_LTR
                box.addView(line)
            }
            val boxLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            boxLp.bottomMargin = Theme.dp(this, 14.0f)
            sheet.body.addView(box, boxLp)
        }

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val reject = Ui.pillButton(this, Fa.APPROVE_REJECT, "x", Ui.DANGER) {
            decided = true
            result.decided(false)
            sheet.dismiss()
        }
        val rejectLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        rejectLp.marginEnd = Theme.dp(this, 8.0f)
        buttons.addView(reject, rejectLp)
        buttons.addView(
            Ui.pillButton(this, Fa.APPROVE_RUN, "check", Ui.PRIMARY) {
                decided = true
                result.decided(true)
                sheet.dismiss()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        sheet.body.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // "Always allow", for the read-only half of the toolset. ACCEPT mode now
        // asks before reads and searches too, which is what the mode is for —
        // but without this the same question would come back twenty times in one
        // task. Destructive tools are deliberately excluded: nobody should be
        // able to pre-approve every future delete with one tap.
        if (!Tools.isMutating(tool)) {
            val always = Ui.pillButton(this, Fa.APPROVE_ALWAYS, "check", Ui.GHOST) {
                decided = true
                AgentEngine.allowForSession(tool)
                result.decided(true)
                sheet.dismiss()
            }
            val alwaysLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            alwaysLp.topMargin = Theme.dp(this, 8.0f)
            sheet.body.addView(always, alwaysLp)

            val alwaysNote = TextView(this)
            alwaysNote.typeface = Theme.ui()
            alwaysNote.text = Fa.APPROVE_ALWAYS_NOTE
            alwaysNote.setTextColor(Theme.TEXT_FAINT)
            alwaysNote.textSize = Ui.Type.MICRO
            alwaysNote.gravity = Gravity.CENTER_HORIZONTAL
            val noteLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            noteLp.topMargin = Theme.dp(this, 6.0f)
            sheet.body.addView(alwaysNote, noteLp)
        }
        sheet.setOnDismiss {
            // Let a later redelivery re-show a sheet for a NEW approval (and for
            // this one if it somehow closed undecided) — but only clear the
            // tracking if it still points at THIS approval. See [owner].
            if (owner == null || shownApproval === owner) {
                shownApproval = null
            }
            if (!decided) {
                result.decided(false)
            }
        }
        sheet.show()
    }

    // =====================================================================
    // Plan sheet
    // =====================================================================

    private fun maybeShowPlan() {
        if (isFinishing || isDestroyed) {
            return
        }
        if (Prefs.MODE_PLAN != prefs.mode()) {
            return
        }
        val current = chat ?: return
        synchronized(current.messages) {
            var found: Message? = null
            var index = current.messages.size - 1
            while (index >= 0) {
                val candidate = current.messages[index]
                if (candidate.role == "assistant") {
                    found = candidate
                    break
                }
                if (candidate.role == "tool") {
                    return
                }
                index--
            }
            val message = found ?: return
            if (message.isError) {
                return
            }
            val visible = AgentEngine.stripToolCalls(Think.visible(message.content))
            if (visible.trimJava().length < 12) {
                return
            }
            // The sheet appears when there is something in it, and not otherwise.
            //
            // It used to open on `hasDecision || steps >= 1 || length >= 60`, and
            // that last clause is why it opened on almost everything: sixty
            // characters is two sentences, so any plan-mode answer longer than a
            // greeting produced a modal panel titled "Proposed plan" containing no
            // plan and no question. Asking the user to dismiss a sheet that had
            // nothing to say is worse than not showing one, and it trained them to
            // dismiss it without reading — which is exactly when it mattered.
            //
            // The two things the sheet exists to present are now each required to
            // actually exist. A question needs the model to have asked one. A plan
            // needs at least two steps, because a single step is not a plan, it is
            // a sentence — and the prose is already in the transcript behind this
            // sheet, rendered better. When neither is present, nothing opens and
            // the answer simply stands on its own.
            // A run that ASKED to change something is the strongest reason there is
            // to open this sheet: the model has work it wants permission for, and
            // this panel is where that permission is given.
            //
            // The same signal used to mean the opposite. It recorded that a PLAN run
            // had silently escalated to ACCEPT and was already executing, and the
            // sheet stayed shut so it would not pop over its own finished work.
            // PLAN refuses now instead of escalating, so nothing executes unapproved
            // and the bit means what its name says.
            val wantedChanges = AgentEngine.lastRunWantedChanges
            val hasDecision = visible.contains("[QUESTION]")
            // Counted with the SAME parser the sheet itself uses, so the sheet can
            // never open on a plan whose every "step" turns out to be a markdown
            // rule — which is precisely how it came to show four rows of "--".
            val steps = AgentEngine.planLines(visible).size
            if (hasDecision || wantedChanges || steps >= MIN_PLAN_STEPS) {
                showPlanSheet(visible)
            }
        }
    }

    private fun showPlanSheet(plan: String) {
        val sheet = Sheet(this)

        val options = ArrayList<String>()
        val steps = ArrayList<String>()
        val prose = StringBuilder()
        var question: String? = null
        var best = 0

        for (raw in plan.split("\n")) {
            val line = raw.trimJava()
            when {
                line.startsWith("[QUESTION]") -> question = line.substring(10).trimJava()
                line.startsWith("[OPTION]") -> options.add(line.substring(8).trimJava())
                line.startsWith("[BEST]") -> best = parseBest(line.substring(6))
                // AgentEngine.isPlanStep, not a local regex.
                //
                // The old test accepted any line starting with a bullet or a
                // number, so a markdown rule (`---`) and a table separator
                // (`|---|---|`) both qualified — and stripping one leading `-` off
                // `---` left the literal text `--`, which is exactly the blank
                // "step" the plan sheet was showing. A bare `1.` reduced to an
                // empty string the same way. The shared parser rejects all three
                // and requires real content to survive the strip, so the sheet can
                // only ever show steps that say something.
                AgentEngine.isPlanStep(line) -> steps.add(AgentEngine.stripPlanBullet(line))

                line.isNotEmpty() -> prose.append(line).append("\n")
            }
        }
        // Defence in depth: nothing empty reaches a row, whatever the parser did.
        steps.removeAll { it.isBlankJava() }
        // Exactly three model-written options plus the app's own free-text row.
        // A model that ignores the count and sends six would otherwise bury the
        // custom row off the bottom of the sheet.
        while (options.size > 3) {
            options.removeAt(options.size - 1)
        }
        if (question == null && options.isNotEmpty()) {
            question = Fa.APPROVE_ASK
        }

        // The header is chosen AFTER parsing, because the sheet is not always the
        // same object. Two things can open it and they are not the same thing: a
        // multi-step plan to approve, and a question to answer. Titling a bare
        // question "Proposed plan" over an empty step list was how the sheet came
        // to look like it had lost its contents.
        if (steps.isEmpty() && question != null) {
            sheet.header("help", Fa.Q_TITLE, Fa.APPROVE_SUBTITLE)
        } else {
            sheet.header("eye", Fa.PLAN_TITLE, Fa.PLAN_SUBTITLE)
        }

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        if (steps.isNotEmpty()) {
            column.addView(planSectionLabel(Fa.PLAN_STEPS))
            var number = 1
            for (step in steps) {
                column.addView(planStepRow(number, step))
                number++
            }
        } else if (prose.isNotEmpty()) {
            MarkdownRenderer.render(this, column, prose.toString().trimJava())
        }
        val columnLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        columnLp.bottomMargin = Theme.dp(this, 12.0f)
        sheet.body.addView(column, columnLp)

        if (question != null) {
            val qBox = LinearLayout(this)
            qBox.orientation = LinearLayout.VERTICAL
            qBox.background = Theme.roundRect(Theme.SURFACE_2, Theme.R_CARD, this)
            val qPad = Theme.dp(this, 13.0f)
            qBox.setPadding(qPad, qPad, qPad, qPad)

            val qHead = LinearLayout(this)
            qHead.orientation = LinearLayout.HORIZONTAL
            qHead.gravity = Gravity.CENTER_VERTICAL
            val qIcon = Ui.iconBadge(this, "help", Theme.TEXT_MUTED, 32.0f, 17.0f, 10.0f)
            val qIconLp = LinearLayout.LayoutParams(
                Theme.dp(this, 32.0f), Theme.dp(this, 32.0f)
            )
            qIconLp.marginEnd = Theme.dp(this, 10.0f)
            qHead.addView(qIcon, qIconLp)
            val qText = TextView(this)
            qText.text = MarkdownRenderer.inline(question)
            qText.setTextColor(Theme.TEXT)
            qText.textSize = Ui.Type.LABEL
            qText.typeface = Theme.uiBold()
            qText.setLineSpacing(Theme.dpf(this, 2.0f), 1.0f)
            // Same stranding fix as optionRow/planStepRow: the question is
            // model-authored (a "[QUESTION]" line, or Fa.APPROVE_ASK) and is
            // commonly Latin even on the Persian UI. Left alone, the resolved
            // direction falls back to FIRST_STRONG -> LTR and the default
            // START alignment pushes the bold question to the far left of its
            // weighted slot, detaching it from the 32dp "help" badge at the
            // row's start (right) edge. rowLabel keeps FIRST_STRONG shaping but
            // pins the alignment to the LAYOUT start.
            Ui.rowLabel(qText)
            qHead.addView(
                qText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            )
            qBox.addView(qHead)

            val note = TextView(this)
            note.typeface = Theme.ui()
            note.text = Fa.Q_APPLY_NOTE
            note.setTextColor(Theme.TEXT_FAINT)
            note.textSize = Ui.Type.MICRO
            val noteLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            noteLp.topMargin = Theme.dp(this, 4.0f)
            noteLp.bottomMargin = Theme.dp(this, 10.0f)
            qBox.addView(note, noteLp)

            var number = 1
            for (option in options) {
                qBox.addView(
                    optionRow(num(number), option, number == best) {
                        sheet.dismiss()
                        answerPlan(option)
                    }
                )
                number++
            }
            qBox.addView(customOptionRow(num(number), sheet))
            sheet.body.addView(
                qBox,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            // Picking an option refines the plan; running it is a separate,
            // explicit act — and the only thing that hands the agent write
            // access.
            val tail = LinearLayout(this)
            tail.orientation = LinearLayout.HORIZONTAL
            val keep = Ui.pillButton(this, Fa.PLAN_KEEP, null, Ui.SECONDARY) { sheet.dismiss() }
            val keepLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            keepLp.marginEnd = Theme.dp(this, 8.0f)
            tail.addView(keep, keepLp)
            tail.addView(
                Ui.pillButton(this, Fa.PLAN_RUN, "zap", Ui.PRIMARY) {
                    sheet.dismiss()
                    // Handing over write access is a mode change like any
                    // other, so it revokes standing "always allow" grants too.
                    AgentEngine.clearSessionAllowances()
                    prefs.setMode(Prefs.MODE_ACCEPT)
                    refreshModePill()
                    sendProgrammatic(Fa.PLAN_RUN_MSG)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            )
            val tailLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            tailLp.topMargin = Theme.dp(this, 10.0f)
            sheet.body.addView(tail, tailLp)
        } else {
            val buttons = LinearLayout(this)
            buttons.orientation = LinearLayout.HORIZONTAL
            val keep = Ui.pillButton(this, Fa.PLAN_KEEP, null, Ui.SECONDARY) { sheet.dismiss() }
            val keepLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            keepLp.marginEnd = Theme.dp(this, 8.0f)
            buttons.addView(keep, keepLp)
            buttons.addView(
                Ui.pillButton(this, Fa.PLAN_RUN, "zap", Ui.PRIMARY) {
                    sheet.dismiss()
                    // Handing over write access is a mode change like any
                    // other, so it revokes standing "always allow" grants too.
                    AgentEngine.clearSessionAllowances()
                    prefs.setMode(Prefs.MODE_ACCEPT)
                    refreshModePill()
                    sendProgrammatic(Fa.PLAN_RUN_MSG)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            )
            sheet.body.addView(
                buttons,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        sheet.show()
    }

    /**
     * Sends the user's chosen option back to the agent.
     *
     * Deliberately does NOT change the run mode. Answering a planning question
     * used to flip the global preference to ACCEPT and leave it there for every
     * future chat — the user picked an option and silently lost the mode they
     * had chosen. Refining a plan is still planning; only the explicit
     * "run plan" button hands over control.
     */
    private fun answerPlan(answer: String) {
        sendProgrammatic(answer)
    }

    private fun customOptionRow(number: String, sheet: Sheet): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Theme.sunkenCard(Theme.R_MD, this)
        val pad = Theme.dp(this, 8.0f)
        // Relative: the 12dp belongs on the number-chip side in both languages.
        row.setPaddingRelative(Theme.dp(this, 12.0f), pad, pad, pad)

        val numBox = LinearLayout(this)
        numBox.gravity = Gravity.CENTER
        numBox.background = Theme.iconChip(Theme.TEXT_MUTED, 9.0f, this)
        val numSize = Theme.dp(this, 24.0f)
        val numText = TextView(this)
        numText.text = number
        numText.setTextColor(Theme.TEXT_MUTED)
        numText.textSize = Ui.Type.META
        numText.typeface = Theme.uiBold()
        numBox.addView(numText)
        val numLp = LinearLayout.LayoutParams(numSize, numSize)
        numLp.marginEnd = Theme.dp(this, 10.0f)
        row.addView(numBox, numLp)

        val field = EditText(this)
        field.typeface = Theme.ui()
        field.hint = Fa.Q_CUSTOM_HINT
        field.setHintTextColor(Theme.TEXT_FAINT)
        field.setTextColor(Theme.TEXT)
        field.textSize = Ui.Type.LABEL
        field.background = null
        field.maxLines = 3
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        field.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        val fieldPadV = Theme.dp(this, 4.0f)
        field.setPadding(0, fieldPadV, 0, fieldPadV)
        row.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))

        val send = ImageView(this)
        send.setImageDrawable(Icons.of("send", Theme.ON_ACCENT, Ui.STROKE))
        val sendBox = Theme.dp(this, 36.0f)
        val sendPad = Theme.dp(this, 9.0f)
        send.setPadding(sendPad, sendPad, sendPad, sendPad)
        send.background = Theme.actionButton(Theme.R_PILL, this)
        // Every other icon-only button in the app carries a label, via Ui.iconLabel
        // or explicitly. This one did not, so TalkBack announced it as an unlabelled
        // image — on the control that submits the answer.
        send.contentDescription = Fa.SEND
        Ui.pressScale(send)
        send.setOnClickListener {
            val answer = field.text.toString().trimJava()
            if (answer.isEmpty()) {
                field.requestFocus()
            } else {
                sheet.dismiss()
                answerPlan(answer)
            }
        }
        val sendLp = LinearLayout.LayoutParams(sendBox, sendBox)
        sendLp.marginStart = Theme.dp(this, 6.0f)
        row.addView(send, sendLp)

        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowLp.bottomMargin = Theme.dp(this, 4.0f)
        row.layoutParams = rowLp
        return row
    }

    /**
     * Reads the `[BEST]` marker. The model is asked for a bare 1/2/3, so take
     * the first digit in the line and ignore whatever it wrapped around it
     * ("option 2", "۲", "2.").
     */
    private fun parseBest(raw: String): Int {
        for (c in raw) {
            if (c in '1'..'3') {
                return c - '0'
            }
            // Persian-Indic digits ۱ ۲ ۳
            if (c in '۱'..'۳') {
                return c - '۰'
            }
        }
        return 0
    }

    private fun optionRow(
        number: String,
        option: String,
        recommended: Boolean,
        action: Runnable
    ): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        // The recommendation is carried by the Fa.PLAN_BEST chip and a heavier
        // label, not by a fill and a ring: GREEN is near-black ink here, so the
        // old 2dp green ring drew a hard outline around one row and the number
        // chips differed in brightness for no reason a user could name.
        val background = if (recommended) {
            Theme.roundStroke(Theme.SURFACE_2, Theme.BORDER_HI, Theme.R_MD, 1, this)
        } else {
            Theme.sheetRow(Theme.R_MD, this)
        }
        row.background = Theme.rippleOver(background, Theme.R_MD, this)
        Ui.pressScale(row)
        val pad = Theme.dp(this, 12.0f)
        row.setPadding(pad, pad, pad, pad)

        val numBox = LinearLayout(this)
        numBox.gravity = Gravity.CENTER
        numBox.background = Theme.iconChip(Theme.TEXT_MUTED, 9.0f, this)
        val numSize = Theme.dp(this, 24.0f)
        val numText = TextView(this)
        numText.text = number
        numText.setTextColor(Theme.TEXT_MUTED)
        numText.textSize = Ui.Type.META
        numText.typeface = Theme.uiBold()
        numBox.addView(numText)
        val numLp = LinearLayout.LayoutParams(numSize, numSize)
        numLp.marginEnd = Theme.dp(this, 10.0f)
        row.addView(numBox, numLp)

        val label = TextView(this)
        label.text = MarkdownRenderer.inline(option)
        label.setTextColor(Theme.TEXT)
        label.textSize = Ui.Type.LABEL
        // Weight is the second half of the recommendation signal, paired with the
        // BEST chip — it replaces the green fill and ring this row used to have.
        label.typeface = if (recommended) Theme.uiSemi() else Theme.ui()
        label.setLineSpacing(Theme.dpf(this, 2.0f), 1.0f)
        // Options are model-authored and commonly Latin even on the Persian UI.
        // FIRST_STRONG alone would resolve the paragraph LTR and let the default
        // START alignment push the text to the far left of its weighted slot,
        // detaching it from the numbered chip (and the "BEST" chip) at the row's
        // start edge. rowLabel pins the alignment to the LAYOUT start.
        Ui.rowLabel(label)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))

        if (recommended) {
            // ACCENT, not GREEN: this chip is now the ONLY thing marking the
            // recommendation, so it has to be the emphatic ink rather than one
            // more mid-grey. The label beside it also steps to uiSemi() below.
            val badge = Ui.metaChip(this, Fa.PLAN_BEST, Theme.ACCENT, false)
            val badgeLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            badgeLp.marginStart = Theme.dp(this, 8.0f)
            row.addView(badge, badgeLp)
        }

        row.setOnClickListener { action.run() }
        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowLp.bottomMargin = Theme.dp(this, 7.0f)
        row.layoutParams = rowLp
        return row
    }

    /**
     * The plan sheet's own section heading. Uses the shared
     * [Ui.sectionLabel] treatment so it matches the settings screen — this used
     * to be a lone accent-coloured line that appeared nowhere else in the app.
     */
    private fun planSectionLabel(text: String): View {
        val label = Ui.sectionLabel(this, text)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = 0
        lp.bottomMargin = Theme.dp(this, 9.0f)
        label.layoutParams = lp
        return label
    }

    private fun planStepRow(number: Int, text: String): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.background = Theme.sheetRow(Theme.R_MD, this)
        val pad = Theme.dp(this, 12.0f)
        row.setPadding(pad, pad, pad, pad)

        val numBox = LinearLayout(this)
        numBox.gravity = Gravity.CENTER
        // A solid ACCENT chip with ON_ACCENT digits — the same primary
        // treatment as every other filled control, and it inverts per palette.
        numBox.background = Theme.roundRect(Theme.ACCENT, 9.0f, this)
        val numSize = Theme.dp(this, 25.0f)
        val numText = TextView(this)
        // Step numbers follow the UI language, like every other count in the
        // app: this was the one place that always emitted Latin digits.
        numText.text = num(number)
        numText.setTextColor(Theme.ON_ACCENT)
        numText.textSize = Ui.Type.META
        numText.typeface = Theme.uiBold()
        numBox.addView(numText)
        val numLp = LinearLayout.LayoutParams(numSize, numSize)
        numLp.marginEnd = Theme.dp(this, 11.0f)
        numLp.topMargin = Theme.dp(this, 1.0f)
        row.addView(numBox, numLp)

        val label = TextView(this)
        label.typeface = Theme.ui()
        label.text = MarkdownRenderer.inline(text)
        label.setTextColor(Theme.TEXT)
        label.textSize = Ui.Type.LABEL
        label.setLineSpacing(Theme.dpf(this, 2.5f), 1.0f)
        // Same stranding fix as optionRow: a plan step is model-authored text
        // that can resolve LTR on the Persian UI, and without the VIEW_START pin
        // it drifts to the far left of its weighted slot, away from the ACCENT
        // step-number chip sitting at the row's start (right) edge.
        Ui.rowLabel(label)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))

        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowLp.bottomMargin = Theme.dp(this, 7.0f)
        row.layoutParams = rowLp
        return row
    }

    // =====================================================================
    // Stream caret + flushing + animation
    // =====================================================================

    /**
     * The live tail is now revealed word-by-word by [StreamReveal]; there is no
     * blinking block caret to start or stop. These remain as the single place
     * the stream's "is writing" state is hooked, so the call sites read the
     * same as before.
     */
    private fun startCaret() {
    }

    private fun stopCaret() {
        currentStream?.detach()
    }

    private fun scheduleFlush() {
        if (!flushScheduled) {
            flushScheduled = true
            ui.postDelayed(flusher, FLUSH_MS)
        }
    }

    // =====================================================================
    // Tap-to-copy panel
    // =====================================================================
    //
    // A single tap on a message (the user's own question or the assistant's
    // answer) reveals a small panel with a Copy button, right under that
    // message.
    //
    // Deliberately an INLINE view, not a PopupWindow: a popup needs a live
    // window token and is the classic source of BadTokenException / leaked
    // windows when the Activity is finishing, recreating, or being killed by an
    // OEM. Living inside the row means the panel cannot outlive its window, and
    // is removed automatically whenever the transcript is rebuilt.

    /**
     * Attaches "tap once to reveal a copy panel" to a message view.
     *
     * [host] is the vertical container the panel is inserted into (the message
     * column), [anchor] is the view that receives the tap, and [textProvider]
     * supplies the text at copy time.
     *
     * Text selection is preserved: the message TextViews are selectable, so a
     * long press still starts selection and a drag still scrolls. Only a clean,
     * short, non-moving tap toggles the panel, which is decided here rather than
     * with setOnClickListener because a selectable TextView consumes clicks.
     */
    private fun attachCopyPanel(host: LinearLayout, anchor: View, textProvider: () -> String) {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val tapTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        anchor.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0.0f
            private var downY = 0.0f
            private var downAt = 0L
            private var moved = false

            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        downAt = System.currentTimeMillis()
                        moved = false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(event.rawX - downX) > slop ||
                            Math.abs(event.rawY - downY) > slop
                        ) {
                            moved = true
                        }
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val quick = System.currentTimeMillis() - downAt < tapTimeout
                        // A tap that lands on a link belongs to the link: the
                        // TextView's movement method will open it, and toggling a
                        // copy panel as well would leave one waiting behind when
                        // the user returns from the browser.
                        if (!moved && quick && !isOnLink(v, event)) {
                            toggleCopyPanel(host, textProvider)
                        }
                    }
                }
                // Never consume: selection, scrolling and link taps keep working
                // exactly as before.
                return false
            }
        })
    }

    /** True when [event] fell on a clickable span (a link) inside [view]. */
    private fun isOnLink(view: View, event: android.view.MotionEvent): Boolean {
        try {
            val tv = view as? TextView ?: return false
            val spanned = tv.text as? android.text.Spanned ?: return false
            val layout = tv.layout ?: return false
            val x = event.x - tv.totalPaddingLeft + tv.scrollX
            val y = event.y - tv.totalPaddingTop + tv.scrollY
            val line = layout.getLineForVertical(y.toInt())
            // getOffsetForHorizontal snaps to the nearest character even when the
            // touch is past the end of the line, so verify the x is really inside.
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) {
                return false
            }
            val offset = layout.getOffsetForHorizontal(line, x)
            return spanned.getSpans(
                offset, offset, android.text.style.ClickableSpan::class.java
            ).isNotEmpty()
        } catch (ignored: Throwable) {
            return false
        }
    }

    /**
     * Same as [attachCopyPanel] but for a rendered markdown answer, which is a
     * tree of views rather than one TextView.
     *
     * The listener is attached to the container AND to each descendant TextView,
     * because a child that handles its own touches (selectable text) would
     * otherwise swallow the tap before the parent ever sees it. Code blocks are
     * skipped: they already carry their own copy button, and hijacking them
     * would be confusing.
     */
    private fun attachCopyPanelDeep(
        host: LinearLayout,
        root: ViewGroup,
        textProvider: () -> String
    ) {
        attachCopyPanel(host, root, textProvider)
        walkForCopyTargets(host, root, textProvider, 0)
    }

    private fun walkForCopyTargets(
        host: LinearLayout,
        group: ViewGroup,
        textProvider: () -> String,
        depth: Int
    ) {
        // Bounded: a pathological answer must not recurse without limit.
        if (depth > 6) {
            return
        }
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is TextView) {
                attachCopyPanel(host, child, textProvider)
            } else if (child is ViewGroup) {
                // A code card owns its own copy affordance — leave it alone.
                if (child.tag == MarkdownRenderer.TAG_CODE_CARD) {
                    continue
                }
                walkForCopyTargets(host, child, textProvider, depth + 1)
            }
        }
    }

    /** Opens the copy panel under [host], or closes it if it is already open. */
    private fun toggleCopyPanel(host: LinearLayout, textProvider: () -> String) {
        if (isFinishing || isDestroyed) {
            return
        }
        val existing = openCopyPanel
        // Tapping the same message again closes its panel.
        if (existing != null && existing.parent === host) {
            hideCopyPanel()
            return
        }
        hideCopyPanel()
        val panel = buildCopyPanel(textProvider)
        host.addView(panel)
        openCopyPanel = panel

        // Fade + rise + a whisper of scale, on the app's shared easing curve so
        // it feels like the same material as the sheets and rows.
        panel.alpha = 0.0f
        panel.translationY = Theme.dpf(this, -6.0f)
        panel.scaleX = 0.92f
        panel.scaleY = 0.92f
        // Grow out of the corner nearest the message. The layout is left-to-right
        // in both languages now, so that is the LEFT edge and x=0 is right; the
        // mirrored branch is kept for the case where it is not.
        panel.pivotY = 0.0f
        if (!Lang.mirrored(this)) {
            panel.pivotX = 0.0f
        } else {
            panel.addOnLayoutChangeListener(
                object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View, l: Int, t: Int, r: Int, b: Int,
                        ol: Int, ot: Int, or_: Int, ob: Int
                    ) {
                        v.pivotX = v.width.toFloat()
                        v.removeOnLayoutChangeListener(this)
                    }
                }
            )
        }
        panel.animate()
            .alpha(1.0f).translationY(0.0f).scaleX(1.0f).scaleY(1.0f)
            .setDuration(Ui.D_SLOW).setInterpolator(Ui.ease()).start()

        // A panel whose row leaves the tree (chat switch, re-render, trimming)
        // must not stay referenced or keep animating.
        panel.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    v.animate().cancel()
                    if (openCopyPanel === v) {
                        openCopyPanel = null
                    }
                }
            }
        )
    }

    /** Closes the open copy panel, if any. Safe to call at any time. */
    private fun hideCopyPanel() {
        val panel = openCopyPanel ?: return
        openCopyPanel = null
        // Make the panel inert the moment it starts leaving. Without this, a tap
        // on the fading panel would both re-trigger copy and — because a touch
        // cancels the running ViewPropertyAnimator, and a cancelled animator
        // never runs its end action — strand the view in the transcript forever.
        try {
            panel.setOnClickListener(null)
            panel.setOnTouchListener(null)
            panel.isClickable = false
            panel.isFocusable = false
        } catch (ignored: Throwable) {
        }
        val detach = Runnable {
            try {
                (panel.parent as? ViewGroup)?.removeView(panel)
            } catch (ignored: Throwable) {
            }
        }
        try {
            panel.animate().cancel()
            if (panel.parent == null) {
                return
            }
            panel.animate()
                .alpha(0.0f).translationY(Theme.dpf(this, -4.0f))
                .scaleX(0.94f).scaleY(0.94f)
                .setDuration(Ui.D_FAST).setInterpolator(Ui.easeOut())
                .withEndAction(detach)
                .start()
            // Belt and braces: if that animation is ever cancelled, its end
            // action is dropped. removeView on an already-removed child is a
            // no-op, so running both is safe.
            ui.postDelayed(detach, Ui.D_FAST + 80L)
        } catch (ignored: Throwable) {
            detach.run()
        }
    }

    /** The panel itself: one Copy button on a quiet raised chip. */
    private fun buildCopyPanel(textProvider: () -> String): LinearLayout {
        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.HORIZONTAL
        panel.gravity = Gravity.CENTER_VERTICAL
        val padH = Theme.dp(this, 12.0f)
        val padV = Theme.dp(this, 7.0f)
        panel.setPadding(padH, padV, padH, padV)

        val glyph = ImageView(this)
        glyph.setImageDrawable(Icons.of("copy", Theme.ACCENT, Ui.STROKE))
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(this, 15.0f)
        val glyphLp = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphLp.marginEnd = Theme.dp(this, 7.0f)
        panel.addView(glyph, glyphLp)

        val label = TextView(this)
        label.text = Fa.COPY
        label.setTextColor(Theme.TEXT)
        label.textSize = Ui.Type.META
        label.typeface = Theme.uiMedium()
        label.setSingleLine(true)
        panel.addView(label)

        panel.contentDescription = Fa.COPY
        // strokeDp is in DP: every other card in the app passes 1. (Theme.hairline
        // returns PIXELS, so passing it here drew a ~3dp border on xxhdpi.)
        panel.background = Theme.rippleOver(
            Theme.roundStroke(Theme.SURFACE_2, Theme.BORDER, 14.0f, 1, this), 14.0f, this
        )
        // Deliberately NO Ui.pressScale here: it cancels the running
        // ViewPropertyAnimator on touch, which would freeze this panel's
        // entrance mid-fade and drop its exit animation's end action. The ripple
        // is enough feedback.
        panel.setOnClickListener {
            val text = try {
                textProvider()
            } catch (ignored: Throwable) {
                ""
            }
            // Reuses the app's hardened clipboard path, which already retries
            // through the application context because some MIUI builds reject
            // the transient clipboard service.
            val copied = MarkdownRenderer.copyText(this, Fa.APP_NAME, text)
            if (copied) {
                glyph.setImageDrawable(Icons.of("check", Theme.TEXT, Ui.STROKE))
                label.text = Fa.COPIED
                label.setTextColor(Theme.TEXT)
                ui.postDelayed({
                    if (!isFinishing && !isDestroyed && openCopyPanel === panel) {
                        hideCopyPanel()
                    }
                }, 900L)
            } else {
                Toast.makeText(this, Fa.ERR_UNKNOWN, Toast.LENGTH_SHORT).show()
            }
        }

        val panelLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        panelLp.topMargin = Theme.dp(this, 6.0f)
        panel.layoutParams = panelLp
        return panel
    }

    /**
     * The height-animation state of ONE collapsible panel.
     *
     * [expandView] and [collapseView] used to build a bare ValueAnimator that
     * was stored nowhere and cancelled nowhere, so a quick close-then-open ran
     * the two against each other: the collapse kept its own 210 ms deadline and
     * its end action stamped `GONE` over the panel the expand had just opened.
     * The card ended up invisible while its toggle still believed it was open,
     * so the next tap "collapsed" a zero-height view and did nothing — three
     * taps to reopen a card that was double-tapped. Every collapsible owns one
     * of these instead: the running animator is cancelled before a new one
     * starts, and [generation] invalidates the end action of a superseded run,
     * because a cancelled Animator still reports `onAnimationEnd`.
     */
    private class Collapsible {
        var anim: ValueAnimator? = null
        var generation: Int = 0
    }

    private fun expandView(view: View, state: Collapsible) {
        // Bump the generation FIRST: cancel() runs the outgoing animator's end
        // action synchronously, and it must see that it has been superseded.
        val generation = ++state.generation
        state.anim?.cancel()
        state.anim = null
        view.animate().cancel()
        view.visibility = View.VISIBLE
        val parentWidth = (view.parent as? View)?.width ?: 0
        view.measure(
            View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        // From wherever the box actually is — which is 0 for a closed panel and
        // part-way for one whose collapse was just interrupted.
        val animator = ValueAnimator.ofInt(Math.max(0, view.height), view.measuredHeight)
        animator.duration = Ui.D_BASE
        // The shared easing curve, so a panel opening matches every other
        // motion in the app instead of sliding at a constant speed.
        animator.interpolator = Ui.ease()
        animator.addUpdateListener { value ->
            view.layoutParams.height = value.animatedValue as Int
            view.requestLayout()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (state.generation != generation) {
                    return
                }
                state.anim = null
                view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                view.requestLayout()
            }
        })
        // Content fades up as the box opens; without this the text is fully
        // opaque at 1px tall and looks clipped rather than revealed. An
        // interrupted collapse leaves it part-way, so only reset to invisible
        // when the panel really is fully opaque and closed.
        if (view.alpha >= 1.0f) {
            view.alpha = 0.0f
        }
        view.animate().alpha(1.0f).setDuration(Ui.D_BASE)
            .setInterpolator(Ui.ease()).start()
        state.anim = animator
        animator.start()
    }

    private fun collapseView(view: View, state: Collapsible) {
        val generation = ++state.generation
        state.anim?.cancel()
        state.anim = null
        view.animate().cancel()
        val animator = ValueAnimator.ofInt(Math.max(0, view.height), 0)
        animator.duration = Ui.D_BASE
        // Accelerating out: closing should feel quicker than opening.
        animator.interpolator = Ui.easeOut()
        animator.addUpdateListener { value ->
            view.layoutParams.height = value.animatedValue as Int
            view.requestLayout()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // A collapse that was cancelled by a later expand must NOT hide
                // the panel that expand just opened.
                if (state.generation != generation) {
                    return
                }
                state.anim = null
                view.visibility = View.GONE
                view.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                // Reset for the next expand, which starts from alpha 0.
                view.alpha = 1.0f
                view.requestLayout()
            }
        })
        view.animate().alpha(0.0f).setDuration(Ui.D_FAST)
            .setInterpolator(Ui.easeOut()).start()
        state.anim = animator
        animator.start()
    }

    // =====================================================================
    // Send button states
    // =====================================================================

    private fun setRunning(running: Boolean) {
        setHeaderBusy(running)
        val send = sendBtn ?: return
        send.animate().cancel()
        send.alpha = 1.0f
        if (running) {
            if (AgentBus.isStopping()) {
                setStopping()
                return
            }
            send.contentDescription = Fa.STOP
            // The SAME solid disc as send, with a filled square inside it. The
            // stop state used to be a tinted red outline, which is exactly the
            // hue-carried meaning this design removes — and because none of the
            // three grounds carries a stroke, swapping between them never
            // shifts the glyph by a hairline.
            send.setImageDrawable(Icons.filled("stop", Theme.ON_ACCENT))
            send.background = Theme.actionButton(Theme.R_PILL, this)
        } else {
            send.contentDescription = Fa.SEND
            send.setImageDrawable(Icons.of("arrow-up", Theme.ON_ACCENT, Ui.STROKE))
            send.background = Theme.actionButton(Theme.R_PILL, this)
            updateSendAvailability(true)
        }
        if (Build.VERSION.SDK_INT >= 21) {
            send.elevation = 0.0f
        }
        // Send ⇄ stop is the app's most-watched state change: spring it in so
        // the swap is legible rather than an instant icon substitution.
        send.scaleX = 0.86f
        send.scaleY = 0.86f
        send.animate().scaleX(1.0f).scaleY(1.0f)
            .setDuration(Ui.D_BASE).setInterpolator(Ui.spring()).start()
    }

    private fun setStopping() {
        val send = sendBtn ?: return
        send.animate().cancel()
        send.contentDescription = Fa.STOPPING
        send.setImageDrawable(Icons.filled("stop", Theme.TEXT_FAINT))
        send.background = Theme.stoppingButton(20.0f, this)
        send.alpha = 0.78f
        if (Build.VERSION.SDK_INT >= 21) {
            send.elevation = 0.0f
        }
    }

    private fun updateSendAvailability() {
        updateSendAvailability(false)
    }

    private fun updateSendAvailability(force: Boolean) {
        val send = sendBtn ?: return
        val field = input ?: return
        if (!force && AgentBus.isBusy()) {
            return
        }
        val available = field.text.toString().trimJava().isNotEmpty() || pending.isNotEmpty()
        send.alpha = if (available) 1.0f else 0.58f
    }

    /**
     * The header carries the PRODUCT's name, never the conversation's.
     *
     * A chat title in the header is a label for something the user is already
     * looking at, and it moved on every turn as the auto-title was rewritten — so
     * the one fixed point in the chrome kept changing while the content below it
     * stayed put. The conversation is identified where it is chosen, in the drawer.
     */
    private fun refreshTitle() {
        titleView?.text = Fa.APP_NAME
    }

    /** Called after any change to the transcript's contents. */
    private fun onTranscriptChanged() {
        updateJumpButton()
    }

    // =====================================================================
    // Scrolling
    // =====================================================================

    private fun scrollToBottom() {
        val sv = messagesScroll ?: return
        val scroll = Runnable { sv.fullScroll(View.FOCUS_DOWN) }
        // Post several times: after recreation (app switch / theme change) the
        // ScrollView content is not yet measured on the first frame, so a single
        // post lands at the top (showing the old input instead of the latest output).
        sv.post(scroll)
        sv.postDelayed(scroll, 80L)
        sv.postDelayed(scroll, 240L)
        sv.postDelayed({ onTranscriptChanged() }, 300L)
    }

    /**
     * Lightweight scroll used while streaming. Instead of fullScroll() (which
     * grabs focus and forces a jump every frame), it nudges by exactly the delta
     * needed to keep the newest text in view — and only when the user is already
     * near the bottom, so scrolling up to read stays undisturbed. A single posted
     * runnable per flush prevents the stacked-repost jitter that made the screen
     * jump during streaming.
     */
    private fun streamScroll() {
        if (!nearBottom) {
            return
        }
        val sv = messagesScroll ?: return
        sv.post {
            val child = sv.getChildAt(0) ?: return@post
            val dy = (child.bottom - sv.height) - sv.scrollY
            if (dy > 0) {
                sv.scrollBy(0, dy)
            }
        }
    }

    /**
     * Drops the composer's focus, and with it the focus ring and the keyboard.
     *
     * Focus is moved to the transcript rather than merely cleared: a
     * `clearFocus()` on its own leaves the window with no focused view, and the
     * platform then hands focus straight back to the first focusable child — which
     * is the composer — the next time anything requests layout.
     */
    private fun releaseComposerFocus() {
        val field = input ?: return
        if (!field.hasFocus()) {
            return
        }
        val host = messagesScroll
        if (host != null) {
            host.isFocusableInTouchMode = true
            host.requestFocus()
        } else {
            field.clearFocus()
        }
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val manager =
            getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val focus = currentFocus ?: return
        manager.hideSoftInputFromWindow(focus.windowToken, 0)
    }

    // =====================================================================
    // Permissions + lifecycle
    // =====================================================================

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") !=
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_NOTIF)
            } catch (e: Exception) {
            }
        }
    }

    private fun ensureStorageAccess(showPicker: Boolean) {
        if (hasStorageAccess()) {
            updatePermBanner()
            if (showPicker) {
                pickAttachment(false)
            }
            return
        }
        if (!showPicker) {
            updatePermBanner()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQ_MANAGE)
            } else {
                requestPermissions(
                    arrayOf(
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE"
                    ),
                    REQ_PERMS
                )
            }
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (ignored: Exception) {
            }
        }
        updatePermBanner()
    }

    private fun hasStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            return android.os.Environment.isExternalStorageManager()
        }
        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun updatePermBanner() {
        // Slides/fades rather than snapping: this row appears and disappears in
        // response to a permission grant made in ANOTHER screen, so an abrupt
        // jump reads as a glitch when returning to the app.
        //
        // Both hosts are updated. The drawer's row always exists; the empty
        // state's only exists while the transcript is empty and the permission
        // is still owed, and it is dropped by renderAll() when the tree it lives
        // in is torn down.
        val granted = hasStorageAccess()
        permBanner?.let { Ui.reveal(it, !granted) }
        headerPermRow?.let { Ui.reveal(it, !granted) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updatePermBanner()
    }

    override fun onStart() {
        super.onStart()
        AgentBus.listener = uiListener
        reconcileRunningState()
    }

    override fun onStop() {
        super.onStop()
        if (AgentBus.listener === uiListener) {
            AgentBus.listener = null
        }
        // Xiaomi/MIUI hardening: flush the open conversation to disk before the
        // OEM can kill us in the background. User turns are already saved on
        // send and assistant turns by the service, so this is a belt-and-braces
        // flush of any in-memory edit (e.g. an auto-title). Skipped while a run
        // is live — the service owns the on-disk copy then and our snapshot
        // could race it. Saves are atomic, so a background kill mid-write can
        // never truncate a conversation.
        if (!AgentBus.isBusy()) {
            chat?.let { current ->
                val hasMessages = synchronized(current.messages) { current.messages.isNotEmpty() }
                if (hasMessages) {
                    try {
                        // saveNow, not save: writes are asynchronous now so the UI
                        // thread never waits on an fsync, and this is the one call
                        // where waiting is the entire point. An OEM that kills the
                        // process the moment it backgrounds — which is the case this
                        // whole block exists for — would otherwise kill the queued
                        // write with it.
                        store.saveNow(current)
                    } catch (ignored: Exception) {
                    }
                }
            }
        }
        // From here until the next attach, every callback the service fires is
        // dropped on the floor. Remember that so the next reconcile re-renders
        // instead of trusting a view tree that missed N messages.
        if (AgentBus.isBusy()) {
            missedUiEvents = true
        }
    }

    // Belt-and-suspenders for a same-process Activity recreate: stash the open
    // chat id so onCreate can restore it even before consulting prefs. The
    // persisted lastChatId already covers full process death; this covers the
    // window before it is written.
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        chat?.let { outState.putString(STATE_CHAT_ID, it.id) }
    }

    private fun reconcileRunningState() {
        val live = AgentBus.liveChat
        val liveId = AgentBus.activeChatId
        if (AgentBus.isBusy() && live != null && liveId != null) {
            val current = chat
            // `missedUiEvents` matters even when the chat identity is unchanged:
            // messages and tool cards produced while we were stopped never
            // reached the list, and the stale `currentStream` would otherwise
            // render the next turn's tokens into the previous turn's row.
            if (current == null || current.id != liveId || current !== live || missedUiEvents) {
                chat = live
                prefs.setLastChatId(live.id)
                refreshTitle()
                renderAll()
            }
            missedUiEvents = false
            setRunning(true)
            AgentBus.redeliverPendingApproval()
            return
        }
        setRunning(false)
        // The run also may have *finished* while we were stopped, in which case
        // its last messages never reached the list. Re-read the conversation
        // from disk and repaint before deciding what to show.
        if (missedUiEvents) {
            missedUiEvents = false
            val current = chat
            if (current != null) {
                val fresh = store.load(current.id)
                if (fresh != null) {
                    chat = fresh
                    refreshTitle()
                }
                renderAll()
            }
        }
        // Returned to the app and nothing is running: if the last run died
        // half-way (e.g. the system killed the service while we were away),
        // surface a continue affordance rather than a silent, frozen chat.
        maybeShowContinueCard()
    }

    override fun onResume() {
        super.onResume()
        // Re-attach immediately after returning from another app. OEMs can pause
        // and recreate the Activity while the foreground service keeps running.
        AgentBus.listener = uiListener
        reconcileRunningState()
        // Apply appearance/language changes in place. Recreating the Activity while
        // MIUI is streaming can tear down callbacks even though the service lives.
        //
        // The palette is applied FIRST (Settings may have changed the pref while
        // we were paused), then compared against the generation this view tree
        // was painted with. Comparing against `Theme.DARK` was the old bug: the
        // Settings screen mutates that global before it recreates itself, so by
        // the time we resume it already matches the new preference and the chat
        // screen stayed painted in the *old* palette — settings light, chat
        // dark, and every newly-built drawable (the composer's focus ring, tool
        // cards, sheets) rendering in whichever palette was live at that moment.
        Theme.applyFromPrefs(this, prefs)
        if (Theme.revision != appliedRevision) {
            refreshAppearance()
            return
        }
        // A LANGUAGE change has to be caught the same way, and was not being caught
        // at all — this check went missing when the app was briefly English-only and
        // was never restored when Persian came back.
        //
        // Settings applies the new language process-wide (`Fa` is computed getters
        // over one flag) and then recreates ITSELF. The chat screen was never told,
        // so it came back with a view tree built in the old direction while every
        // string it re-read was in the new language: a half-translated screen with
        // the drawer still hinged on the wrong side, and no way out but killing the
        // app and reopening it. Exactly what was reported, in both directions.
        if (prefs.language() != lastLanguage) {
            lastLanguage = prefs.language()
            Fa.apply(this)
            refreshAppearance()
            return
        }
        lastNight = currentNight()
        updatePermBanner()
        if (hasStorageAccess()) {
            requestNotifPermission()
            ui.postDelayed({ maybeBatteryPrompt() }, 500L)
        }
        // Re-check once more after a short delay: some OEMs (Xiaomi/MIUI) report
        // the freshly-granted "all files" permission a beat late, which used to
        // leave the access banner stuck on screen after returning from Settings.
        ui.postDelayed({
            updatePermBanner()
            if (hasStorageAccess()) {
                requestNotifPermission()
            }
        }, 500L)
        refreshModePill()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Plain dialogs outlive their Activity; dismiss them or leak the window.
        Sheet.dismissAll()
        dismissChatMenu()
        openCopyPanel = null
        // The run hairline's sweep is an INFINITE animator. Its own detach
        // listener normally stops it, but a destroy that never detaches the
        // window (or one that races the detach) would leave it running against
        // a dead view tree.
        headerRunAnim?.cancel()
        headerRunAnim = null
        currentStream?.detach()
        // Both drive Choreographer frame callbacks; a destroyed Activity must not
        // leave one advancing against a dead view tree.
        trailView?.detach()
        trailView = null
        if (AgentBus.listener === uiListener) {
            AgentBus.listener = null
        }
        // Cancel any self-reposting UI callbacks (caret blink, stream flusher,
        // delayed banners) so a destroyed Activity can't keep re-posting forever
        // — e.g. after a recreate() while a run is streaming.
        ui.removeCallbacksAndMessages(null)
        diskExec.shutdownNow()
    }

    companion object {
        /**
         * Where the watermark's CENTRE rests, as a fraction of screen height.
         *
         * 0.40 is a tenth of the screen above true centre — the same resting spot
         * the old `translationY = -(height * 0.10f)` produced against a centred
         * view. It is expressed as an absolute target now because the mark is
         * top-anchored, which gives the keyboard maths a fixed origin that a
         * window resize cannot move.
         */
        private const val WATERMARK_LIFT = 0.40f

        /**
         * How much of the free band the mark fills, edge to edge.
         *
         * There is no separate keyboard scale any more. The mark is fitted to the
         * space that actually exists, so the keyboard being up is not a special
         * case — it is simply a smaller band, and the same arithmetic handles a
         * split-screen window, a three-line composer and a tall OEM keyboard
         * without knowing anything about any of them.
         */
        /**
         * How present the mark is behind an empty conversation.
         *
         * Lowered from 0.05/0.04. The mark is a large solid fill, so a percentage
         * that is barely visible on a small glyph is a considerable amount of ink
         * across half the screen width — it was reading as a grey shape on the page
         * rather than as a watermark under it.
         */
        /** Source circles shown before the count takes over. */
        private const val TRAIL_CLUSTER = 3

        /**
         * Width of the conversation overflow menu.
         *
         * Wide enough for the longest of the three Persian labels at BODY size and no
         * wider — the point of an anchored menu is that it is visibly a fragment of
         * the interface rather than a panel that has taken it over.
         */
        private const val CHAT_MENU_DP = 196.0f

        private const val WATERMARK_ALPHA_DARK = 0.035f
        private const val WATERMARK_ALPHA_LIGHT = 0.028f

        private const val WATERMARK_FILL = 0.72f

        /** Never smaller than this fraction of full size, however tight the band. */
        private const val WATERMARK_MIN_SCALE = 0.34f

        /** Movement below this is not worth restarting an animation for. */
        private const val WATERMARK_EPSILON_PX = 1.5f
        private const val WATERMARK_EPSILON_SCALE = 0.004f

        private const val REQ_ATTACH = 1001
        private const val REQ_MANAGE = 1002
        private const val REQ_PERMS = 1003
        private const val REQ_NOTIF = 1004

        /** Bundle key for the open chat id across an Activity save/restore. */
        private const val STATE_CHAT_ID = "vepro_chat_id"

        /**
         * Steps a plan needs before it is worth a sheet of its own.
         *
         * Two, not one. One step is a sentence, and the transcript behind the
         * sheet already renders sentences better than a modal list can.
         */
        private const val MIN_PLAN_STEPS = 2

        /**
         * The widest a run of prose is allowed to get, in dp.
         *
         * 560 to match [Sheet]'s own cap, so a message bubble and a sheet holding
         * the same sentence measure the same on a tablet instead of disagreeing by
         * a factor of two.
         */
        private const val MAX_PROSE_DP = 560.0f

        /**
         * How long streamed tokens are coalesced before the text is rebuilt.
         *
         * 40ms — about two frames — down from 60. The reveal can only be as
         * continuous as the input it is given: at 60ms a fast provider's tokens were
         * batched into ~16 updates a second, and every batch started its whole group
         * of words on the same tick. Halving the quantum halves the size of each
         * group, so the cascade is fed a steadier trickle and the leading edge stops
         * arriving in visible steps.
         *
         * It costs one markdown re-render per tick on the streaming tail only, which
         * is a single TextView — the app was already doing this work, just less
         * often and in bigger lumps. 33ms was briefly used and is slightly worse:
         * every flush rebuilds the tail's markdown, and a paragraph that reflows
         * thirty times a second makes words move sideways WHILE they are fading,
         * which is its own kind of unsettled. Two frames is a better trade — the
         * cascade is still fed a steady trickle, with a third less reflow.
         */
        private const val FLUSH_MS = 40L

    }
}
