package github.vega.agent

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.PowerManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject

/**
 * Settings — a power user's control surface, not a preferences list.
 *
 * ### One silhouette
 *
 * Every group on this screen is the same object: a grey [Ui.sectionLabel]
 * introduces it, and the group itself is a flat [Ui.groupedCard] on the page
 * ground. There used to be TWO silhouettes — six filled cards and three bare
 * hairline tracks (protocol, theme, language) — so a third of the column read as
 * a different design from the rest. The segmented tracks now live INSIDE a card
 * like everything else, on a [Theme.SURFACE] ground so they are still visible
 * against the card's own [Theme.SURFACE_2].
 *
 * ### Separation inside a card
 *
 * A dense card is no longer one undifferentiated slab: rows are parted by an
 * INSET [Ui.divider] hairline that starts where the labels start, so the glyph
 * column reads as a continuous rail down the card and each row still reads as
 * its own line. [Ui.divider]'s own doc warns this is usually the wrong answer —
 * it is the right one here, because a settings card is a LIST, and a list of
 * eight identically-shaped rows with nothing between them is a wall.
 *
 * ### Explanations belong to their group
 *
 * A group's long explanation is the last block INSIDE its card, under a
 * hairline, rather than a paragraph floating on the page below it. Floating
 * footnotes made the vertical rhythm lumpy — a card, a gap, some prose, a
 * bigger gap, a heading — and left prose with no visible owner.
 *
 * ### One kind of meter
 *
 * The temperature dial and the reasoning dial are built from the same three
 * pieces ([blockHead], [styleSeek], [meterCaptions]), so they cannot drift into
 * two different-looking sliders again. Both carry a mono readout on the trailing
 * edge and two [Ui.Type.MICRO] captions naming the ends of the range.
 *
 * ### Appearance lives on its own page
 *
 * Theme, language and haptics sit on the "User Interface" page, not on the hub:
 * all three answer "how does the app look and feel" and none of them changes
 * what the agent does, so they get their own destination instead of stretching
 * the menu. Theme and language share one card there — the same question asked
 * twice — and the haptic switch gets its own iOS tile row.
 *
 * ### Persian mirrors, and these are the seams
 *
 * The root sets `layoutDirection = Lang.direction(this)` and the whole tree
 * flips, because every inset here is `setPaddingRelative` / `marginStart` /
 * `Gravity.START`. What that does NOT reach is anything positioned in PHYSICAL
 * pixels or anything whose meaning is Latin, so those are named individually:
 *
 *  * **Directional glyphs** come from [Lang.chevronBack] / [Lang.chevronForward]
 *    — the masthead's back arrow and every "opens something" chevron point at
 *    the edge they mean rather than at a fixed side.
 *  * **LTR islands** — the API key, the router keys, the base URL, the model
 *    name, the number fields and the connection row's `host · model · protocol`
 *    line — pin themselves to `LAYOUT_DIRECTION_LTR` / `TEXT_DIRECTION_LTR` and
 *    then pin their ALIGNMENT back to the view's start edge, so a Latin
 *    identifier still reads left-to-right while sitting on the reading edge of a
 *    mirrored row.
 *  * **The two meters** mirror with the interface (see [styleSeek]): a magnitude
 *    is laid along the reading axis, and in Persian that axis runs from the
 *    right.
 *  * **Numbers** are shown in the interface's own numerals via [Lang.num] and
 *    [localizeDigits]; numbers the user TYPES are normalised back to ASCII by
 *    [normalizeDigits].
 *  * **Tracking** ([blockCaption], the masthead title) is a Latin device and is
 *    zeroed in Persian, which is a joined script.
 *
 * ### Colour
 *
 * There is no accent colour here: every token is black, white or a grey between
 * them. The single exception is the connection row's state dot, which uses the
 * diff inks — the one sanctioned hue in the palette — because "reachable" and
 * "rejected" are opposites, not two amounts of one thing, and lightness alone
 * cannot say which is which.
 *
 * The Vega mark appears exactly ONCE in the whole app, in the About group at
 * the bottom of this screen. The chat screen shows no logo at all.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs

    private var selTheme: String = Prefs.THEME_SYSTEM
    private var selLevel: String = "medium"
    private var selLanguage: String = "en"

    private val themePills = ArrayList<LinearLayout>(3)
    private val langPills = ArrayList<LinearLayout>(2)
    private var presetBody: LinearLayout? = null
    private var presetRows: LinearLayout? = null
    private var presetChevron: ImageView? = null
    private var presetSelDot: View? = null
    private var presetSelName: TextView? = null
    private var presetSelSub: TextView? = null
    private var presetExpanded = false
    private var presetBoxAnimator: ValueAnimator? = null
    private var batteryRow: LinearLayout? = null

    private var etBase: EditText? = null
    private var etKey: EditText? = null
    private var etMaxTok: EditText? = null
    private var etContextTok: EditText? = null
    private var etTimeout: EditText? = null
    private var etModel: EditText? = null
    private var etNewKey: EditText? = null
    private var etSys: EditText? = null

    private var keysBox: LinearLayout? = null
    private var tvKeyCount: TextView? = null
    private var sbTemp: SeekBar? = null
    private var swWeb: Switch? = null
    private var swWorkflow: Switch? = null
    private var swLocalNet: Switch? = null
    private var swHaptic: Switch? = null
    private var tvTemp: TextView? = null
    private var tvTestStatus: TextView? = null
    private var tvConnSummary: TextView? = null
    private var tvConnProblem: TextView? = null
    /** The unprotected-key note AND its hairline, shown as one block or not at all. */
    private var tvKeyWarning: View? = null

    /** The connection row's state pip — neutral, testing, reachable, rejected. */
    private var connDot: View? = null
    private var sbThink: SeekBar? = null
    private var thinkReadout: TextView? = null
    private var thinkDesc: TextView? = null

    // Model picker — the expandable box under the Model name field.
    private var modelsBody: LinearLayout? = null
    private var modelsChevron: ImageView? = null
    private var modelsRows: LinearLayout? = null
    private var modelsStatus: TextView? = null
    private var modelsExpanded = false
    private var modelsGeneration = 0
    /**
     * `base + "\u0000" + key` the list was last fetched for. The key is part
     * of the identity: the list is fetched only AFTER a key exists, and a new
     * key means a new fetch even for the same base URL.
     */
    private var modelsFetchedFor: String? = null
    private var modelsCache: List<String> = emptyList()
    private val modelsFetchDebounced = Runnable {
        if (modelsExpanded && modelsFetchKey() != modelsFetchedFor) {
            fetchModels()
        }
    }
    /**
     * The key the auto-fetch already ran for. Typing the key fires a debounced
     * fetch exactly once per distinct key — not per keystroke, and not again
     * for the key the list already reflects.
     */
    private var lastKeyAutoFetch: String = ""
    private val keyAutoFetchDebounced = Runnable {
        val key = etKey?.text?.toString()?.trimJava() ?: ""
        if (key.isNotEmpty() && key != lastKeyAutoFetch) {
            lastKeyAutoFetch = key
            fetchModels(autoExpand = true)
        }
    }
    /** In-flight expand/collapse animator for the model picker box. */
    private var modelsBoxAnimator: ValueAnimator? = null

    private fun modelsFetchKey(): String {
        val base = etBase?.text?.toString()?.trimJava() ?: ""
        val key = etKey?.text?.toString()?.trimJava() ?: ""
        return base + "\u0000" + key
    }

    private val ui = Handler(Looper.getMainLooper())
    private val applyTextSettings = Runnable { applyTextSettingsNow() }

    /**
     * True from the moment a cell in the appearance card has asked for a
     * rebuild until the rebuilt instance replaces this one.
     *
     * It guards BOTH tracks in that card, because both end in `recreate()` and
     * neither survives being asked twice: a second tap while the first rebuild
     * is still pending would queue a second one, and [finish] reads the flag to
     * know that this teardown is a rebuild rather than a navigation and must
     * not be dressed with the exit transition.
     */
    private var applyingTheme = false
    private var lastSavedKey: String = ""
    private var connectionTestGeneration = 0

    /** Palette generation this screen was painted with — see MainActivity. */
    private var appliedRevision = -1

    /** Set while [resetAll] is clearing prefs, so onPause does not write them back. */
    private var resetting = false

    /**
     * Which settings page is on screen: the hub ([PAGE_HUB]) or one of the three
     * sub-pages ([PAGE_TOOLS], [PAGE_BRAIN], [PAGE_UI]). The whole panel is rebuilt by
     * [buildPage] whenever it changes — the screen is one Activity, and every
     * builder below already binds straight to [prefs], so a second Activity
     * would only duplicate two thousand lines of wiring.
     */
    private var page: Int = PAGE_HUB
    /** The scrollable column every page is built into. */
    private var pagePanel: LinearLayout? = null
    private var pageScroll: ScrollView? = null

    // Skills ("Add skill" flow in the Tools & access card).
    private lateinit var skillSheets: SkillSheets
    private var skillsBox: LinearLayout? = null
    private var skillsListBox: LinearLayout? = null
    private var skillsToggleChevron: ImageView? = null
    private var skillsExpanded: Boolean = false
    /** Bumps on every renderSkills(); stale background scans check it before posting. */
    private var skillsScanGen = 0

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        prefs = Prefs(this)
        // The skills list starts collapsed (and remembers its last state).
        skillsExpanded = prefs.skillsExpanded()
        skillSheets = SkillSheets(
            this, prefs, ui,
            { msg, long -> say(msg, long) },
            { renderSkills() }
        )
        NetworkPolicy.applyPrefs(prefs)
        Fa.apply(this)
        Theme.init(this)
        Theme.applyFromPrefs(this, prefs)
        appliedRevision = Theme.revision
        // Everything the PLATFORM draws — text cursor, selection handles and
        // highlight, the ActionMode bar, overscroll glow, Toast and Dialog
        // chrome — comes from the activity theme, not from our palette. Pick
        // the matching one before any view exists.
        setTheme(if (Theme.DARK) R.style.AppTheme else R.style.AppThemeLight)
        selTheme = prefs.themeMode()
        selLanguage = prefs.language()
        lastSavedKey = prefs.apiKey()

        // Same rule as the chat screen: the status bar sits on the app bar, so
        // it takes the app bar's colour, not the page's.
        window.statusBarColor = Theme.BG_ELEV
        window.navigationBarColor = Theme.BG
        window.setBackgroundDrawable(Theme.windowBg())

        val decor = window.decorView
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

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Theme.BG)
        scroll.isFillViewport = true
        // The scroller is vertical, so this is not about layout — it is about the
        // one piece of chrome the scroller draws itself. A ScrollView puts its
        // scrollbar on the trailing edge of its resolved direction, and the
        // Activity's decor resolves from the RESOURCE configuration, whose locale
        // this app never changes. Left alone, a Persian screen would have kept its
        // scrollbar on the right, against the reading edge.
        scroll.layoutDirection = Lang.direction(this)

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.layoutDirection = Lang.direction(this)
        // The screen's own gutter, PLUS whatever the system bars need — added, not
        // replaced. `fitsSystemWindows = true` used to be here, and the framework's
        // default implementation OVERWRITES a view's padding with the insets rather
        // than adding to them: on Android 15/16 and on MIUI, where the insets
        // actually reach this view, the 16dp side gutter was replaced with the
        // horizontal insets (zero on a portrait phone) and every card ran into both
        // edges of the glass. On Android 12 with a non-edge-to-edge window the decor
        // had already consumed the insets, nothing arrived, and the padding survived
        // by accident — which is exactly why this looked correct on one phone and
        // broken on another.
        val padH = Theme.dp(this, 16.0f)
        Ui.applyWindowInsets(panel, padH, 0, padH, Theme.dp(this, 40.0f))
        scroll.addView(panel)
        pageScroll = scroll
        pagePanel = panel
        // The hub is the first page. It is NOT animated in: the Activity itself
        // already arrives with the settings_enter transition, and a second
        // entrance on top of it would read as a stutter.
        buildPage(animate = false)

        setContentView(scroll)
    }

    // =====================================================================
    // Masthead
    // =====================================================================

    /**
     * The screen's top: a back affordance, the title at [Ui.Type.TITLE], a line
     * saying what is in here, and a full-width hairline closing the block.
     *
     * The old header was a 56dp row with a chevron and the word "Settings" at
     * body size — the same size as every row label under it — so the screen began
     * with no statement of what it was. This is the one place on the page that
     * uses the display size, which is what gives the column a top.
     */
    private fun masthead(): LinearLayout {
        val head = Ui.column(this)
        head.setPaddingRelative(0, Theme.dp(this, Ui.Space.S), 0, 0)

        val back = Ui.row(this)
        val backButton = Ui.circleButton(
            this,
            // BACK, so it points at the edge it returns to: chevron-left in
            // English, chevron-right in Persian. A fixed "chevron-left" here
            // would have pointed INTO the page on a mirrored screen — away from
            // the edge the gesture actually goes to.
            Lang.chevronBack(this),
            40.0f,
            20.0f,
            Theme.TEXT,
            0
        ) { finish() }
        // Ui.iconLabel maps both chevrons to a hard-coded English "Back", so the
        // one icon-only control on this screen would have been announced in the
        // wrong language. Named here instead.
        backButton.contentDescription = Lang.text(this, "Back", "بازگشت")
        back.addView(backButton)
        // The button's own optical inset is cancelled so the glyph, the title
        // below it and the section labels below THAT all start on one line.
        //
        // Mirrored, this still holds: circleButton pads (40-20)/2 = 10dp on all
        // four sides, so the glyph's leading edge sits 10dp inside the button's
        // leading edge — whichever side that is. A marginStart of 4-10 = -6dp
        // pulls the button 6dp PAST the column's start edge, which puts the glyph
        // back on the 4dp the title and the section labels use. Every term is
        // relative, so the whole calculation reflects with the layout.
        val backLp = Ui.wrapWrap()
        backLp.marginStart = Theme.dp(this, Ui.Space.XS) - Theme.dp(this, 10.0f)
        head.addView(back, backLp)

        val title = Ui.text(this, Fa.SETTINGS, Ui.Type.TITLE, Theme.TEXT, Theme.uiBold())
        title.setSingleLine(true)
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        // Slightly tight: a display-size line in a bold face sets loose by default
        // and reads as spaced-out rather than as a title.
        title.letterSpacing = tracking(-0.01f)
        Ui.rowLabel(title)
        val titleLp = Ui.matchWrap()
        titleLp.topMargin = Theme.dp(this, Ui.Space.S)
        titleLp.marginStart = Theme.dp(this, Ui.Space.XS)
        head.addView(title, titleLp)

        val caption = Ui.text(this, Fa.SET_SUBTITLE, Ui.Type.META, Theme.TEXT_MUTED, Theme.ui())
        caption.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        Ui.rowLabel(caption)
        val captionLp = Ui.matchWrap()
        captionLp.topMargin = Theme.dp(this, 3.0f)
        captionLp.marginStart = Theme.dp(this, Ui.Space.XS)
        head.addView(caption, captionLp)

        val rule = Ui.divider(this)
        val ruleLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Theme.hairline(this)
        )
        ruleLp.topMargin = Theme.dp(this, Ui.Space.XL)
        head.addView(rule, ruleLp)
        return head
    }

    // =====================================================================
    // Provider preset selector
    // =====================================================================

    /**
     * The provider quick-selector — the provider card's FIRST block.
     *
     * One collapsed luxury row (~56dp) instead of the old seven-row wall: the
     * presets are shortcuts, not the whole story, and a 448dp list shouted the
     * opposite — that the app supports exactly these seven and nothing else.
     * Tapping expands the seven compact rows with the same height animation
     * the model picker uses, and the hint line under the row says it
     * outright: any compatible endpoint can be typed in manually.
     */
    private fun presetSelector(): LinearLayout {
        val block = Ui.column(this)
        block.setPaddingRelative(
            0, Theme.dp(this, 14.0f), 0, Theme.dp(this, Ui.Space.M)
        )

        val captionLp = Ui.matchWrap()
        captionLp.marginStart = Theme.dp(this, Ui.Space.L)
        captionLp.marginEnd = Theme.dp(this, Ui.Space.L)
        captionLp.bottomMargin = Theme.dp(this, Ui.Space.S)
        block.addView(blockCaption(Fa.SET_PRESET), captionLp)

        // -- the collapsed selector row -----------------------------------
        val row = Ui.row(this)
        row.background = presetRowBg(false)
        row.minimumHeight = Theme.dp(this, 56.0f)
        row.setPaddingRelative(
            Theme.dp(this, Ui.Space.L), Theme.dp(this, 8.0f),
            Theme.dp(this, Ui.Space.M), Theme.dp(this, 8.0f)
        )

        val dot = View(this)
        val dotSize = Theme.dp(this, 8.0f)
        val dotLp = LinearLayout.LayoutParams(dotSize, dotSize)
        dotLp.marginEnd = Theme.dp(this, 12.0f)
        dotLp.gravity = Gravity.CENTER_VERTICAL
        row.addView(dot, dotLp)
        presetSelDot = dot

        val stack = Ui.column(this)
        val name = Ui.text(this, "", Ui.Type.BODY, Theme.TEXT, Theme.uiSemi())
        Ui.rowLabel(name)
        stack.addView(name, Ui.matchWrap())
        presetSelName = name
        val sub = Ui.text(this, "", Ui.Type.META, Theme.TEXT_MUTED, Theme.ui())
        // Latin identifiers: pin to LTR so "model · host" keeps its order
        // on RTL layouts.
        sub.textDirection = View.TEXT_DIRECTION_LTR
        Ui.rowLabel(sub)
        val subLp = Ui.matchWrap()
        subLp.topMargin = Theme.dp(this, 2.0f)
        stack.addView(sub, subLp)
        presetSelSub = sub
        val stackLp = Ui.grow()
        stackLp.gravity = Gravity.CENTER_VERTICAL
        row.addView(stack, stackLp)

        val chevron = ImageView(this)
        chevron.setImageDrawable(Icons.of("chevron-down", Theme.TEXT_MUTED, Ui.STROKE))
        chevron.scaleType = ImageView.ScaleType.FIT_CENTER
        chevron.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val chevSize = Theme.dp(this, 20.0f)
        val chevLp = LinearLayout.LayoutParams(chevSize, chevSize)
        chevLp.gravity = Gravity.CENTER_VERTICAL
        row.addView(chevron, chevLp)
        presetChevron = chevron

        row.setOnClickListener { togglePresetBox() }
        Ui.pressScale(row)
        val rowLp = Ui.matchWrap()
        rowLp.marginStart = Theme.dp(this, Ui.Space.M)
        rowLp.marginEnd = Theme.dp(this, Ui.Space.M)
        block.addView(row, rowLp)

        // -- the hint: shortcuts, not the whole menu -----------------------
        val hint = Ui.text(
            this, Fa.SET_PRESET_HINT, Ui.Type.MICRO, Theme.TEXT_MUTED, Theme.ui()
        )
        Ui.rowLabel(hint)
        val hintLp = Ui.matchWrap()
        hintLp.marginStart = Theme.dp(this, Ui.Space.L)
        hintLp.marginEnd = Theme.dp(this, Ui.Space.L)
        hintLp.topMargin = Theme.dp(this, 6.0f)
        block.addView(hint, hintLp)

        // -- the expandable body -------------------------------------------
        val body = Ui.column(this)
        body.visibility = View.GONE
        presetBody = body
        val rows = Ui.column(this)
        presetRows = rows
        val rowsLp = Ui.matchWrap()
        rowsLp.topMargin = Theme.dp(this, Ui.Space.S)
        rowsLp.marginStart = Theme.dp(this, Ui.Space.M)
        rowsLp.marginEnd = Theme.dp(this, Ui.Space.M)
        body.addView(rows, rowsLp)
        block.addView(body, Ui.matchWrap())

        refreshPresetSelector()
        block.layoutParams = Ui.matchWrap()
        return block
    }

    /** Label, base URL, model. No protocol: it is detected automatically per endpoint. */
    private val PRESETS = arrayOf(
        arrayOf(Fa.SET_PROTO_OPENAI, "https://api.openai.com/v1", "gpt-6-astra"),
        arrayOf(
            Fa.SET_PROTO_ANTHRO, "https://api.anthropic.com/v1",
            "claude-fable-5.1"
        ),
        arrayOf(
            Fa.SET_PROTO_GEMINI, "https://generativelanguage.googleapis.com/v1beta",
            "gemini-3.8-flash"
        ),
        arrayOf("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-6-astra"),
        arrayOf(
            "Groq", "https://api.groq.com/openai/v1",
            "qwen/qwen3.8-27b"
        ),
        arrayOf("DeepSeek", "https://api.deepseek.com", "deepseek-flash"),
        arrayOf(
            "Together", "https://api.together.xyz/v1",
            "gpt-6-astra"
        )
    )

    /** The preset matching the live base URL, or null for a custom endpoint. */
    private fun activePreset(): Array<String>? {
        val activeBase = etBase?.text?.toString()?.trimJava() ?: prefs.baseUrl().trimJava()
        return PRESETS.firstOrNull { it[1] == activeBase }
    }

    private fun togglePresetBox() {
        setPresetBoxExpanded(!presetExpanded, animate = true)
    }

    /**
     * Opens/closes the preset list with the same height animation the model
     * picker uses: the body grows from 0 to its measured height on the [Ui]
     * easing curve, the chevron rotating in sync.
     */
    private fun setPresetBoxExpanded(expanded: Boolean, animate: Boolean) {
        val body = presetBody ?: return
        if (expanded == presetExpanded && !animate) {
            return
        }
        presetExpanded = expanded
        presetBoxAnimator?.cancel()
        presetBoxAnimator = null
        presetChevron?.animate()?.cancel()
        presetChevron?.animate()?.rotation(if (expanded) 180.0f else 0.0f)
            ?.setDuration(Ui.D_FAST)?.setInterpolator(Ui.ease())?.start()
        if (!animate) {
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            return
        }
        if (expanded) {
            // The body is GONE so it has no height yet: measure it off-layout
            // at the width it will have, then grow from 0 to that height.
            val parentWidth = (body.parent as? View)?.width ?: 0
            body.visibility = View.VISIBLE
            if (parentWidth > 0) {
                body.measure(
                    View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }
            val target = body.measuredHeight
            if (target <= 0) {
                // Unmeasurable (first layout race) — fall back to instant show.
                body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                return
            }
            animatePresetBoxHeight(body, 0, target) {
                body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                body.requestLayout()
            }
        } else {
            val start = body.height
            if (start <= 0) {
                body.visibility = View.GONE
                return
            }
            animatePresetBoxHeight(body, start, 0) {
                body.visibility = View.GONE
                body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                body.requestLayout()
            }
        }
        // No tick here: the selector row has an onClick, so pressScale
        // already ticked on the press down.
    }

    /** Shared driver for the preset box height animation. */
    private fun animatePresetBoxHeight(
        body: View,
        from: Int,
        to: Int,
        onEnd: () -> Unit
    ) {
        val anim = ValueAnimator.ofInt(from, to)
        presetBoxAnimator = anim
        anim.addUpdateListener { v ->
            body.layoutParams.height = v.animatedValue as Int
            body.requestLayout()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (presetBoxAnimator == animation) {
                    presetBoxAnimator = null
                }
                onEnd()
            }

            override fun onAnimationCancel(animation: android.animation.Animator) {
                if (presetBoxAnimator == animation) {
                    presetBoxAnimator = null
                }
            }
        })
        anim.duration = Ui.D_BASE
        anim.interpolator = Ui.ease()
        anim.start()
    }

    /**
     * Builds the seven compact preset rows inside the expandable body. 48dp,
     * not 64: these are options in a picker, not cards on a wall — the
     * selector row above already carries the full-size presentation.
     */
    private fun buildPresetRows() {
        val list = presetRows ?: return
        list.removeAllViews()
        val active = activePreset()
        for (preset in PRESETS) {
            val isActive = preset === active
            val row = Ui.row(this)
            row.background = presetRowBg(isActive)
            row.minimumHeight = Theme.dp(this, 48.0f)
            row.setPaddingRelative(
                Theme.dp(this, Ui.Space.L), Theme.dp(this, 6.0f),
                Theme.dp(this, Ui.Space.M), Theme.dp(this, 6.0f)
            )

            val dot = View(this)
            dot.background = Theme.circle(
                if (isActive) Theme.ON_ACCENT else Theme.TEXT_FAINT
            )
            val dotSize = Theme.dp(this, 8.0f)
            val dotLp = LinearLayout.LayoutParams(dotSize, dotSize)
            dotLp.marginEnd = Theme.dp(this, 12.0f)
            dotLp.gravity = Gravity.CENTER_VERTICAL
            row.addView(dot, dotLp)

            val stack = Ui.column(this)
            val name = Ui.text(
                this, preset[0], Ui.Type.BODY,
                if (isActive) Theme.ON_ACCENT else Theme.TEXT,
                if (isActive) Theme.uiSemi() else Theme.ui()
            )
            Ui.rowLabel(name)
            stack.addView(name, Ui.matchWrap())
            val sub = Ui.text(
                this, preset[2] + "  ·  " + presetHost(preset[1]), Ui.Type.META,
                if (isActive) Theme.alpha(Theme.ON_ACCENT, 178) else Theme.TEXT_MUTED,
                Theme.ui()
            )
            // Latin identifiers: pin to LTR so "model · host" keeps its order
            // on RTL layouts.
            sub.textDirection = View.TEXT_DIRECTION_LTR
            Ui.rowLabel(sub)
            val subLp = Ui.matchWrap()
            subLp.topMargin = Theme.dp(this, 2.0f)
            stack.addView(sub, subLp)
            val stackLp = Ui.grow()
            stackLp.gravity = Gravity.CENTER_VERTICAL
            row.addView(stack, stackLp)

            if (isActive) {
                val check = ImageView(this)
                check.setImageDrawable(Icons.of("check", Theme.ON_ACCENT, Ui.STROKE))
                check.scaleType = ImageView.ScaleType.FIT_CENTER
                check.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                val checkSize = Theme.dp(this, 20.0f)
                val checkLp = LinearLayout.LayoutParams(checkSize, checkSize)
                checkLp.gravity = Gravity.CENTER_VERTICAL
                checkLp.marginStart = Theme.dp(this, Ui.Space.M)
                row.addView(check, checkLp)
            }

            val rowLp = Ui.matchWrap()
            rowLp.bottomMargin = Theme.dp(this, 8.0f)
            val captured = preset
            row.setOnClickListener { applyPreset(captured) }
            Ui.pressScale(row)
            list.addView(row, rowLp)
        }
    }

    /** One tap on a preset: fill the fields, reset the verdict, collapse. */
    private fun applyPreset(preset: Array<String>) {
        etBase?.setText(preset[1])
        etModel?.setText(preset[2])
        prefs.setBaseUrl(preset[1])
        prefs.setModel(preset[2])
        // The new model brings its own ceiling: max tokens and context window
        // jump to the highest the model accepts, so the preset runs at full
        // power without the user hunting down the provider's docs.
        if (applyModelCaps(preset[2])) {
            say(Fa.SET_CAPS_AUTO, false)
        }
        // All three values just changed, so the last verdict was about a
        // different endpoint. Clearing it is the same reset the old card
        // did with `tvTestStatus?.text = ""`, plus the pip.
        resetConnectionState()
        refreshPresetSelector()
        // A new endpoint means a new model list: if the picker is open,
        // re-fetch for the new address; the debounced base watcher
        // covers the typing case, this covers the row tap.
        ui.removeCallbacks(modelsFetchDebounced)
        ui.post(modelsFetchDebounced)
        setPresetBoxExpanded(false, animate = true)
    }

    /**
     * Text for the context-window field: the explicit setting when the user
     * set one, otherwise the selected model's known ceiling (the value the
     * engine is actually budgeting with), otherwise blank.
     */
    private fun contextFieldText(): String {
        val explicit = prefs.contextTokens()
        if (explicit > 0) return explicit.toString()
        return ModelCaps.forModel(prefs.model())?.contextTokens?.toString() ?: ""
    }

    /**
     * Points "max tokens" and "context window" at [model]'s ceiling.
     * Returns true when the model is known (fields + prefs updated),
     * false when it isn't (everything left untouched).
     */
    private fun applyModelCaps(model: String): Boolean {
        val caps = ModelCaps.forModel(model) ?: return false
        prefs.setMaxTokens(caps.maxOutputTokens)
        prefs.setContextTokens(caps.contextTokens)
        etMaxTok?.setText(caps.maxOutputTokens.toString())
        etContextTok?.setText(caps.contextTokens.toString())
        return true
    }

    /**
     * Refreshes the selector row (name, subtitle, dot) and rebuilds the
     * expanded rows. Called after a preset tap, after settings are applied,
     * and as the base URL is typed — so a hand-typed endpoint reads as
     * "Custom" instead of a stale preset name.
     */
    private fun refreshPresetSelector() {
        val preset = activePreset()
        val selName = presetSelName
        val selSub = presetSelSub
        if (selName != null && selSub != null) {
            if (preset != null) {
                selName.text = preset[0]
                selSub.text = preset[2] + "  ·  " + presetHost(preset[1])
            } else {
                val base = etBase?.text?.toString()?.trimJava()
                    ?: prefs.baseUrl().trimJava()
                selName.text = Fa.SET_PRESET_CUSTOM
                selSub.text = presetHost(base)
            }
        }
        presetSelDot?.background = Theme.circle(
            if (preset != null) Theme.ACCENT else Theme.TEXT_FAINT
        )
        // The rows carry per-state children (the check glyph, the subtitle
        // ink) that a background swap alone cannot update — rebuild them.
        // Cheap, and one definition of the row means the build and the
        // refresh cannot drift apart.
        buildPresetRows()
    }

    /** Host part of a preset base URL, for the row subtitle. */
    private fun presetHost(base: String): String {
        var host = base.trimJava()
        val scheme = host.indexOf("://")
        if (scheme >= 0) {
            host = host.substring(scheme + 3)
        }
        val slash = host.indexOf('/')
        if (slash >= 0) {
            host = host.substring(0, slash)
        }
        return host
    }

    /**
     * One definition of the preset row's surface, used by both the initial
     * build and every refresh — they used to be two copies that could (and
     * did) drift apart.
     *
     * R_MD (16dp) keeps the rows card-like rather than pill-like: full-width
     * pills read as buttons, not options.
     */
    private fun presetRowBg(active: Boolean) = if (active) {
        Theme.actionButton(Theme.R_MD, this)
    } else {
        Theme.rippleOver(
            Theme.roundRect(Theme.SURFACE_2, Theme.R_MD, this), Theme.R_MD, this
        )
    }


    /**
     * The connection row — the provider card's closing line.
     *
     * It answers the same question the old 152dp card at the top of the screen
     * did ("is this configuration going to work?"), in the place where the answer
     * is meaningful: directly under the base URL, the key and the model, which
     * are the only three values it reads. Everything is on one ~58dp row:
     *
     *  * a 9dp state pip — neutral before a test, muted while one runs, and one
     *    of the two diff inks afterwards;
     *  * a state line, which is either the test's own verdict or, when
     *    [Preflight] can already see the request is impossible, that problem said
     *    in one tight line;
     *  * a `host · model · protocol` subtitle, forced LTR because all three are
     *    Latin identifiers;
     *  * a compact Test affordance.
     *
     * The whole thing is rebuilt by [refreshConnectionCard] on every keystroke,
     * so it describes the live fields rather than what the screen opened with.
     */
    private fun connectionCard(): View {
        val row = Ui.row(this)
        row.minimumHeight = Theme.dp(this, 58.0f)
        row.setPaddingRelative(
            Theme.dp(this, Ui.Space.L), Theme.dp(this, Ui.Space.M),
            Theme.dp(this, Ui.Space.M), Theme.dp(this, Ui.Space.M)
        )

        // The pip sits in the same 20dp slot a cardRow glyph occupies, so the
        // state line starts exactly where every label above it does.
        val slot = LinearLayout(this)
        slot.gravity = Gravity.CENTER
        // The pip is the only thing naming this row, and a bare View has nothing
        // for a screen reader to announce.
        slot.contentDescription = Fa.SET_CONNECTION
        val dot = View(this)
        connDot = dot
        val dotSize = Theme.dp(this, 9.0f)
        slot.addView(dot, LinearLayout.LayoutParams(dotSize, dotSize))
        val slotSize = Theme.dp(this, Ui.Space.XL)
        val slotLp = LinearLayout.LayoutParams(slotSize, slotSize)
        slotLp.marginEnd = Theme.dp(this, Ui.Space.L)
        row.addView(slot, slotLp)

        val stack = Ui.column(this)

        val status = Ui.text(this, Fa.SET_CONN_UNTESTED, Ui.Type.LABEL, Theme.TEXT, Theme.uiSemi())
        tvTestStatus = status
        status.setSingleLine(true)
        status.ellipsize = android.text.TextUtils.TruncateAt.END
        Ui.rowLabel(status)
        stack.addView(status, Ui.matchWrap())

        // The summary and the problem share one slot: exactly one of them is ever
        // visible, so a bad configuration reads as a CORRECTION of the subtitle
        // rather than as a red block bolted underneath it.
        val summary = Ui.text(this, "", Ui.Type.META, Theme.TEXT_MUTED, Theme.ui())
        summary.setSingleLine(true)
        summary.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        Ui.rowLabel(summary)
        // AFTER rowLabel, not before it.
        //
        // The endpoint, the model and the protocol are Latin identifiers whatever
        // is typed, so the line is an LTR island — but [Ui.rowLabel] sets
        // FIRST_STRONG, and it used to run last and quietly undo exactly this.
        // The bug only hid because a host name usually starts with a Latin letter;
        // a Persian model name would have flipped the whole `host · model ·
        // protocol` line end to end.
        summary.textDirection = View.TEXT_DIRECTION_LTR
        // Forcing the direction is not enough on its own: an LTR paragraph inside
        // a mirrored row would strand itself at the far (left) edge, away from the
        // state line it belongs under. VIEW_START resolves against the LAYOUT
        // direction, so the island keeps its own reading order and still hugs the
        // reading edge — the same pairing the masked router keys use.
        summary.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        tvConnSummary = summary
        val summaryLp = Ui.matchWrap()
        summaryLp.topMargin = Theme.dp(this, 2.0f)
        stack.addView(summary, summaryLp)

        val problem = Ui.text(this, "", Ui.Type.META, Theme.DIFF_DEL, Theme.uiMedium())
        problem.setLineSpacing(Theme.dpf(this, 2.0f), 1.0f)
        problem.maxLines = 2
        problem.ellipsize = android.text.TextUtils.TruncateAt.END
        Ui.rowLabel(problem)
        tvConnProblem = problem
        val problemLp = Ui.matchWrap()
        problemLp.topMargin = Theme.dp(this, 2.0f)
        problem.visibility = View.GONE
        stack.addView(problem, problemLp)

        row.addView(stack, Ui.grow())

        val test = testButton()
        val testLp = Ui.wrapWrap()
        testLp.marginStart = Theme.dp(this, Ui.Space.M)
        row.addView(test, testLp)

        row.layoutParams = Ui.matchWrap()
        refreshConnectionCard()
        return row
    }

    /**
     * The compact Test affordance: a 36dp outlined pill, not the app's 48dp
     * [Ui.pillButton].
     *
     * A standard pill is taller than the row it now lives in and would have
     * forced the whole line to 72dp for a control that is pressed once per setup.
     * The outline is [Theme.BORDER_HI] over [Theme.SURFACE] — the same treatment
     * the three fields above it wear, so the row's trailing control matches them.
     */
    private fun testButton(): LinearLayout {
        val pill = Ui.row(this)
        pill.gravity = Gravity.CENTER
        pill.minimumHeight = Theme.dp(this, 36.0f)
        pill.background = Theme.rippleOver(
            Theme.roundStroke(Theme.SURFACE, Theme.BORDER_HI, Theme.R_PILL, 1, this),
            Theme.R_PILL, this
        )
        pill.setPaddingRelative(Theme.dp(this, Ui.Space.M), 0, Theme.dp(this, 14.0f), 0)

        val glyph = ImageView(this)
        glyph.setImageDrawable(Icons.of("plug", Theme.TEXT_MUTED, Ui.STROKE))
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(this, 15.0f)
        val glyphLp = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphLp.marginEnd = Theme.dp(this, 6.0f)
        pill.addView(glyph, glyphLp)

        val label = Ui.text(this, Fa.SET_TEST_SHORT, Ui.Type.META, Theme.TEXT, Theme.uiSemi())
        label.setSingleLine(true)
        pill.addView(label, Ui.wrapWrap())

        pill.contentDescription = Fa.SET_TEST
        pill.setOnClickListener { runTest() }
        Ui.pressScale(pill)
        return pill
    }

    /**
     * Repaints the connection summary from whatever is in the fields right now.
     *
     * Reads the FIELDS, not the saved preferences, because the two differ for as long
     * as the user is typing — and a summary that lags a keystroke behind is worse than
     * none, since it would confirm a setup the user has already changed.
     */
    private fun refreshConnectionCard() {
        val base = etBase?.text?.toString()?.trimJava() ?: prefs.baseUrl()
        val model = etModel?.text?.toString()?.trimJava() ?: prefs.model()
        var key = etKey?.text?.toString()?.trimJava() ?: prefs.apiKey()
        if (key.isEmpty()) {
            // A router-keys-only setup is valid and chats fine.
            key = prefs.apiKeys().firstOrNull() ?: ""
        }
        // The protocol is always auto-detected now; the summary shows the
        // heuristic guess, the request path verifies it at runtime.
        val protocol = LlmClient.resolveProtocol(LlmClient.PROTOCOL_AUTO, base, model)

        // ONE line, middle-ellipsised: host, model, and the protocol that will
        // actually be resolved. It used to wrap onto three, which is most of why
        // the old card stood 152dp tall.
        val host = Preflight.hostOf(base).ifEmpty { base }
        val line = StringBuilder()
        line.append(host)
        if (model.isNotEmpty()) {
            line.append(SEP).append(model)
        }
        line.append(SEP).append(protocol)
        tvConnSummary?.text = line.toString()

        val problem = Preflight.check(base, key, model)
        val view = tvConnProblem
        val summary = tvConnSummary
        if (view != null && summary != null) {
            if (problem == null) {
                view.visibility = View.GONE
                summary.visibility = View.VISIBLE
            } else {
                // The generic "Open Settings" hint is dropped — we ARE in
                // settings — so only a hint that names a real fix is appended.
                view.text = if (problem.hint == Fa.PRE_OPEN_SETTINGS) {
                    problem.message
                } else {
                    problem.message + " " + problem.hint
                }
                view.visibility = View.VISIBLE
                summary.visibility = View.GONE
            }
        }
        // A key stored without hardware protection is worth saying once, quietly,
        // where the key itself lives.
        val warn = tvKeyWarning
        if (warn != null) {
            val unprotected = prefs.apiKey().isNotEmpty() && !prefs.apiKeyIsEncrypted()
            warn.visibility = if (unprotected) View.VISIBLE else View.GONE
        }
        // A Preflight problem is a KNOWN failure, so the pip reports it before
        // anything is sent. Neither branch overwrites a test verdict that is
        // already on screen — that one was earned by an actual request.
        val state = tvTestStatus?.text?.toString() ?: ""
        if (state.isEmpty() || state == Fa.SET_CONN_UNTESTED) {
            paintConnDot(if (problem == null) Theme.TEXT_FAINT else Theme.DIFF_DEL)
        }
    }

    /** Repaints the connection row's state pip. */
    private fun paintConnDot(color: Int) {
        connDot?.background = Theme.circle(color)
    }

    /**
     * Puts the connection row back to "nothing has been tested yet".
     *
     * Called when a preset chip rewrites all three fields at once: the previous
     * verdict was about a different endpoint entirely, and leaving a green
     * "Connected" under a freshly-swapped provider is the screen lying.
     */
    private fun resetConnectionState() {
        tvTestStatus?.setTextColor(Theme.TEXT)
        tvTestStatus?.text = Fa.SET_CONN_UNTESTED
        paintConnDot(Theme.TEXT_FAINT)
        refreshConnectionCard()
    }

    // =====================================================================
    // Segments
    // =====================================================================

    /**
     * The segmented track, as a block INSIDE a group card.
     *
     * The track used to sit bare on the page ground with a hairline outline,
     * because a [Theme.SURFACE_2] track inside a [Theme.SURFACE_2] card is
     * invisible — and that left three of the screen's groups with a different
     * silhouette from the other six, which is exactly the split the redesign
     * removes. The fix is not to take the card away, it is to step the TRACK: the
     * ground is [Theme.SURFACE], the same token every field on this screen uses
     * to separate itself from the card it sits on, with the same [Theme.BORDER_HI]
     * hairline. A track now reads as an input, which is what it is.
     *
     * [Theme.R_MD] rather than a pill, so the track matches the fields above it
     * instead of introducing a third radius.
     *
     * [topDp] is the gap above the track: the full step when the track opens a
     * card, tightened when a [blockCaption] has already opened it and the two
     * have to read as one object.
     */
    private fun segmentContainer(topDp: Float): LinearLayout {
        val container = Ui.row(this)
        // Cells are laid out in CHOICE order, and choice order runs from the
        // reading edge — so "Auto"/"System"/"English" sit on the right in Persian.
        // The repaint helpers index the pill LISTS rather than the container's
        // children, so which physical slot a cell occupies never enters into
        // whether it is the one drawn as chosen.
        container.layoutDirection = Lang.direction(this)
        container.background = Theme.roundStroke(
            Theme.SURFACE, Theme.BORDER_HI, Theme.R_MD, 1, this
        )
        val pad = Theme.dp(this, Ui.Space.XS)
        container.setPadding(pad, pad, pad, pad)
        // The block's own insets match [cardBlock]'s, so a track and a row line up
        // down the card's start edge.
        val lp = Ui.matchWrap()
        lp.marginStart = Theme.dp(this, Ui.Space.L)
        lp.marginEnd = Theme.dp(this, Ui.Space.L)
        lp.topMargin = Theme.dp(this, topDp)
        lp.bottomMargin = Theme.dp(this, 14.0f)
        container.layoutParams = lp
        return container
    }

    /**
     * A segmented track with a [blockCaption] naming what it chooses.
     *
     * The appearance card holds two tracks, and two unlabelled rows of words
     * under one "Appearance" heading say nothing about which is which — least of
     * all to a screen reader, which would read six bare labels in a row. The
     * caption is the same MICRO treatment the preset strip already uses, so this
     * introduces no new shape.
     */
    private fun captionedTrack(caption: String, track: LinearLayout): LinearLayout {
        val block = Ui.column(this)
        val captionLp = Ui.matchWrap()
        captionLp.marginStart = Theme.dp(this, Ui.Space.L)
        captionLp.marginEnd = Theme.dp(this, Ui.Space.L)
        captionLp.topMargin = Theme.dp(this, 14.0f)
        block.addView(blockCaption(caption), captionLp)
        block.addView(track)
        block.layoutParams = Ui.matchWrap()
        return block
    }

    /**
     * The small grey label that names a free-form block inside a card — the
     * preset strip and each of the two appearance tracks.
     *
     * One definition, because these used to be one hand-rolled caption and would
     * have become three. The tracking is [tracking]-guarded: 6% is what gives a
     * MICRO Latin label its "caption" character, and the same 6% applied to
     * Persian opens gaps between letters that are drawn joined.
     */
    private fun blockCaption(value: String): TextView {
        val caption = Ui.text(this, value, Ui.Type.MICRO, Theme.TEXT_MUTED, Theme.uiSemi())
        caption.letterSpacing = tracking(0.06f)
        Ui.rowLabel(caption)
        return caption
    }

    /**
     * Letter-spacing that knows which script it is spacing.
     *
     * Tracking is a Latin typographic device. Persian is a CURSIVE script whose
     * letters join, and Android applies `letterSpacing` between those joined
     * forms too — so a tracked Persian heading reads as a word coming apart.
     * Every tracked line on this screen goes through here and gets zero in
     * Persian, which is the face's own designed fit.
     */
    private fun tracking(value: Float): Float = if (Lang.farsi(this)) 0.0f else value

    /**
     * A cell inside a segmented track: a solid [Theme.ACCENT] tile when chosen
     * (with an [Theme.ON_ACCENT] label, so it inverts correctly in both
     * palettes), bare otherwise. Exactly one thing in the track reads as chosen,
     * and no gradient is involved.
     *
     * [Theme.R_SM] inside the track's [Theme.R_MD] with 4dp of padding between
     * them is CONCENTRIC — the chosen tile's corners are struck from the same
     * centres as the track's. The old pill radius inside a pill track happened to
     * look right; inside a rounded rectangle it read as a lozenge dropped into a
     * box.
     */
    private fun segmentCellBg(selected: Boolean) = if (selected) {
        Theme.actionButton(Theme.R_SM, this)
    } else {
        Theme.rippleTransparent(Theme.R_SM, this)
    }

    /**
     * Repaints one segmented track. Shared by the theme and language tracks so
     * both behave identically: [pills] is the track and [isSelected] answers "is
     * cell i the chosen one".
     *
     * The pop the newly-selected cell used to do is gone — this design's motion
     * budget is near zero, and the fill change is already unambiguous.
     *
     * Mirror-safe by construction: [pills] is the order the cells were ADDED, and
     * `getChildAt(0)` is the order the label was added inside its cell. Neither is
     * the order they are drawn in. Under RTL the track paints itself from the
     * right, and index 0 is still index 0 — so the chosen tile and its inverted
     * label always land on the same cell the click came from. A repaint that
     * walked `container.getChildAt(i)` looking for a visual position is the shape
     * of the bug this avoids.
     */
    private fun paintSegment(pills: List<LinearLayout>, isSelected: (Int) -> Boolean) {
        for (i in pills.indices) {
            val cell = pills[i]
            val selected = isSelected(i)
            cell.background = segmentCellBg(selected)
            // The UNSELECTED cells carry full ink now, not muted.
            //
            // They were at TEXT_MUTED, which is 4.82:1 on the SURFACE_2 track — the
            // documented floor — for a control whose whole job is to show four
            // choices. Selection is already unambiguous: the chosen cell is a solid
            // pill. Dimming the others as well made three of the four options harder
            // to read than the body text around them.
            (cell.getChildAt(0) as TextView).setTextColor(
                if (selected) Theme.ON_ACCENT else Theme.TEXT
            )
        }
    }

    /**
     * One cell of a segmented track: a centred, single-line label on a
     * full-height touch target. Both tracks (protocol, theme) build their cells
     * here, so a protocol cell and a theme cell cannot end up disagreeing about
     * type size or padding — which is exactly what happened when each loop
     * hand-rolled its own.
     *
     * The label is `getChildAt(0)`; [paintSegment] repaints it in place.
     */
    private fun segmentCell(label: String): LinearLayout {
        val cell = LinearLayout(this)
        cell.gravity = Gravity.CENTER
        cell.setPadding(
            Theme.dp(this, 2.0f), Theme.dp(this, Ui.Space.M),
            Theme.dp(this, 2.0f), Theme.dp(this, Ui.Space.M)
        )
        // An honest touch target. 10dp of padding around a 13sp label is roughly
        // 36dp, under the 48dp the rest of the app holds itself to.
        cell.minimumHeight = Theme.dp(this, 44.0f)
        val view = Ui.text(this, label, Ui.Type.META, Theme.TEXT, Theme.uiSemi())
        view.gravity = Gravity.CENTER
        // A cell label is not always in the interface's language: the language
        // track deliberately puts "English" and "فارسی" side by side, and the
        // theme track is Latin in both. FIRST_STRONG lets each label order
        // itself by its own script instead of by the track around it. The
        // alignment stays CENTER — [Ui.rowLabel] would pin it to the start edge
        // and is therefore the wrong helper here.
        view.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        // Without this a long label wraps to two lines in a narrow cell while
        // a short one stays on one, so the cells end up different heights and
        // the whole track jumps.
        view.setSingleLine(true)
        view.ellipsize = android.text.TextUtils.TruncateAt.END
        cell.addView(view, Ui.wrapWrap())
        return cell
    }

    /**
     * The temperature meter — the first of the screen's two dials.
     *
     * It and [thinkLevelSlider] are assembled from the same three pieces
     * ([blockHead], [styleSeek], [meterCaptions]) and therefore cannot drift into
     * two different-looking sliders, which is what they had already done: one had
     * a chip readout and no captions, the other a plain-text readout, a
     * description line and a hint paragraph.
     */
    private fun temperatureBlock(): LinearLayout {
        val col = cardBlock(null)

        val readout = monoReadout(true)
        tvTemp = readout
        col.addView(blockHead("sparkle", Fa.SET_TEMP, readout), Ui.matchWrap())

        val temperature = SeekBar(this)
        sbTemp = temperature
        temperature.max = 200
        temperature.progress = Math.round(prefs.temperature() * 100.0f)
        styleSeek(temperature)
        val tempSbLp = Ui.matchWrap()
        tempSbLp.topMargin = Theme.dp(this, 10.0f)
        // `false`: merely OPENING this screen must not rewrite the stored
        // value. It used to — the readout and the setter shared one call, so a
        // saved 0.735 was silently rounded to 0.74 by looking at the screen.
        updateTemp(temperature.progress, false)
        temperature.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTemp(progress, fromUser)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        col.addView(temperature, tempSbLp)
        col.addView(meterCaptions(Fa.SET_TEMP_LOW, Fa.SET_TEMP_HIGH), Ui.matchWrap())

        val hint = Ui.text(this, Fa.SET_TEMP_H, Ui.Type.META, Theme.TEXT_MUTED, Theme.ui())
        hint.setLineSpacing(Theme.dpf(this, 4.0f), 1.0f)
        Ui.rowLabel(hint)
        val hintLp = Ui.matchWrap()
        hintLp.topMargin = Theme.dp(this, Ui.Space.S)
        col.addView(hint, hintLp)
        return col
    }

    /**
     * The reasoning dial: the same meter as [temperatureBlock], plus a live
     * description of what the chosen level actually does.
     *
     * The five-colour heat rail this replaced encoded "more thinking" as HUE,
     * which a monochrome palette cannot express. The ramp survives as LIGHTNESS
     * via [Theme.think] plus a typeface that steps regular → medium → semibold,
     * both applied in [refreshLevel].
     */
    private fun thinkLevelSlider(): LinearLayout {
        val col = cardBlock(null)

        // The same chip the temperature meter wears — but holding a WORD, so it
        // takes the interface's direction rather than the numeric chip's forced
        // LTR. [refreshLevel] repaints its ink and its face as the level steps.
        val readout = monoReadout(false)
        thinkReadout = readout
        col.addView(blockHead("sliders", Fa.SET_THINK_LEVEL, readout), Ui.matchWrap())

        val seek = SeekBar(this)
        sbThink = seek
        seek.max = 4
        seek.progress = idxOfLevel(selLevel)
        styleSeek(seek)
        val seekLp = Ui.matchWrap()
        seekLp.topMargin = Theme.dp(this, 10.0f)
        col.addView(seek, seekLp)
        col.addView(meterCaptions(Fa.TL_LOW, Fa.TL_MAX), Ui.matchWrap())

        // The live description of the CHOSEN level, under the rail where the two
        // captions frame it — it used to sit above the rail, between the label and
        // the control, which pushed the two apart.
        val desc = Ui.text(this, "", Ui.Type.META, Theme.TEXT, Theme.uiMedium())
        thinkDesc = desc
        Ui.rowLabel(desc)
        val descLp = Ui.matchWrap()
        descLp.topMargin = Theme.dp(this, Ui.Space.S)
        col.addView(desc, descLp)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                // ONLY a real drag changes the stored level.
                //
                // refreshLevel() parks the thumb on XHIGH while Dynamic Workflow
                // is locked; if that programmatic move also persisted, it would
                // overwrite the user's own choice and they would be stuck on
                // xhigh forever after switching the workflow back off. It would
                // also recurse (set progress -> listener -> refreshLevel -> …).
                if (!fromUser) {
                    return
                }
                selLevel = LEVELS[Math.max(0, Math.min(4, progress))]
                prefs.setThinkingLevel(selLevel)
                refreshLevel()
                bar?.let { Ui.tick(it) }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        val hint = Ui.text(
            this, Fa.SET_THINK_LEVEL_H, Ui.Type.META, Theme.TEXT_MUTED, Theme.ui()
        )
        hint.setLineSpacing(Theme.dpf(this, 4.0f), 1.0f)
        Ui.rowLabel(hint)
        val hintLp = Ui.matchWrap()
        hintLp.topMargin = Theme.dp(this, 6.0f)
        col.addView(hint, hintLp)

        refreshLevel()
        return col
    }

    /**
     * What the chosen reasoning level actually does, in one line.
     *
     * An instance method rather than the static table it used to be: these are
     * five sentences a user reads, they were the last untranslated prose on the
     * screen, and a `companion object` has no Context to ask which language to
     * write them in. [Lang.text] rather than [Fa] keys only because Fa.kt is not
     * this file's to edit — see the note in the report.
     */
    private fun levelDesc(index: Int): String = when (index) {
        0 -> Lang.text(this, "Fast and concise", "سریع و کوتاه")
        1 -> Lang.text(this, "Balanced for everyday tasks", "متعادل برای کارهای روزمره")
        2 -> Lang.text(
            this, "More detailed analysis and checking", "تحلیل و بازبینی دقیق‌تر"
        )
        3 -> Lang.text(
            this, "Multi-path exploration and verification", "بررسی چند مسیر و راستی‌آزمایی"
        )
        else -> Lang.text(
            this, "Maximum possible accuracy and depth", "بیشترین دقت و ژرفای ممکن"
        )
    }

    private fun idxOfLevel(level: String?): Int {
        for (i in LEVELS.indices) {
            if (LEVELS[i] == level) {
                return i
            }
        }
        return 1
    }

    private fun refreshLevel() {
        // Show what will ACTUALLY be used. Dynamic Workflow raises the floor to
        // XHIGH, and a readout still saying "medium" while the agent reasons at
        // xhigh is simply wrong.
        val idx = idxOfLevel(
            if (prefs.dynamicWorkflow() && selLevel != "max") "xhigh" else selLevel
        )
        val labels = arrayOf(Fa.TL_LOW, Fa.TL_MED, Fa.TL_HIGH, Fa.TL_XHIGH, Fa.TL_MAX)
        thinkReadout?.let {
            it.text = labels[idx]
            // Lightness AND weight step together. With the hue gone, one signal
            // on its own is too quiet to read as a ramp: [Theme.think] walks a
            // grey from faint to full text colour, and the face walks with it.
            it.setTextColor(Theme.think(idx))
            it.typeface = when {
                idx <= 1 -> Theme.ui()
                idx == 2 -> Theme.uiMedium()
                else -> Theme.uiSemi()
            }
        }
        thinkDesc?.let {
            it.text = levelDesc(idx)
        }
        sbThink?.let { bar ->
            // LOCKED while Dynamic Workflow is on. That mode raises the floor to
            // XHIGH, so leaving the slider draggable would let the user pick a
            // level the agent then silently ignores — the control would be lying.
            // It parks on XHIGH, dims, and stops accepting touches until the
            // toggle is turned off.
            val locked = prefs.dynamicWorkflow() && selLevel != "max"
            bar.isEnabled = !locked
            bar.alpha = if (locked) 0.55f else 1.0f
            if (locked && bar.progress != idx) {
                bar.progress = idx
            }
        }
    }

    private fun themeSegment(): LinearLayout {
        // Sits under a caption, so the gap above it closes to Space.S.
        val container = segmentContainer(Ui.Space.S)
        val labels = arrayOf(Fa.THEME_SYSTEM, Fa.THEME_LIGHT, Fa.THEME_DARK)
        themePills.clear()
        for (i in 0 until 3) {
            val value = THEMES[i]
            val cell = segmentCell(labels[i])
            cell.setOnClickListener {
                if (value != selTheme && !applyingTheme) {
                    applyingTheme = true
                    applyTextSettingsNow()
                    selTheme = value
                    prefs.setThemeMode(value)
                    // Deliberately do NOT touch the global palette here. Mutating
                    // it before recreate() leaves the whole visible tree painted
                    // in the old palette while every lazily-built drawable (focus
                    // rings, ripples, toasts) draws in the new one. onCreate
                    // applies it once, atomically, for the rebuilt tree.
                    recreate()
                }
            }
            Ui.pressScale(cell)
            themePills.add(cell)
            container.addView(
                cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            )
        }
        refreshThemeSeg()
        return container
    }

    private fun refreshThemeSeg() {
        paintSegment(themePills) { THEMES[it] == selTheme }
    }

    /**
     * The interface language, as the appearance card's second track.
     *
     * Each cell is written in its OWN script — `English` and `فارسی`, never
     * "Persian" and never "انگلیسی" — because this is the one control on the
     * screen whose job is to be legible to someone who cannot read the language
     * it is currently set to. An endonym is an identifier here, in the same
     * category as `OpenAI` and `Vega Agent`, which is why the Persian cell is a
     * literal rather than a translated string: `Fa.SET_LANGUAGE_FA` renders as
     * "Persian" while the interface is English, which is exactly the label this
     * cell must not carry.
     *
     * Changing it rebuilds the screen the same way a theme change does, and for
     * the same reason: [Fa] is read at paint time by every view already on
     * screen, so the only honest way to change it is to paint them all again.
     * `recreate()` is right HERE — this Activity is a leaf with no run state —
     * and is banned in `MainActivity`, which owns a live agent run.
     */
    private fun languageSegment(): LinearLayout {
        val container = segmentContainer(Ui.Space.S)
        val labels = arrayOf(Fa.SET_LANGUAGE_EN, FA_ENDONYM)
        langPills.clear()
        for (i in LANGUAGES.indices) {
            val value = LANGUAGES[i]
            val cell = segmentCell(labels[i])
            cell.setOnClickListener {
                if (value != selLanguage && !applyingTheme) {
                    applyingTheme = true
                    // FIRST, and for a sharper reason than the theme cells have:
                    // the pending 350ms text flush is dropped by the rebuild, so
                    // a base URL or a key that is still mid-edit would be lost —
                    // and unlike a theme flip, a language flip is something a
                    // user does WHILE setting the screen up for the first time.
                    applyTextSettingsNow()
                    selLanguage = value
                    prefs.setLanguage(value)
                    // Picking a language here answers the same question the
                    // first-launch picker asks, so it must not be asked again.
                    prefs.setLanguageChosen()
                    // Applied HERE, which is the opposite of what the theme cell
                    // above deliberately does — and the two are not inconsistent.
                    //
                    // A palette mutation is visible immediately: every drawable
                    // built lazily after it (ripples, focus rings) would paint in
                    // the new colours over a tree still painted in the old ones.
                    // The string table is not like that. It is read only at the
                    // instant something assigns `text =`, every view on screen
                    // already holds its string, and the one pending callback that
                    // would re-assign any was just flushed. What flipping it early
                    // buys is a screen that stays self-consistent if `recreate()`
                    // is DEFERRED — the same OEM relaunch quirk onStop guards
                    // against — instead of reading a stored "fa" through an
                    // English table for as long as the relaunch takes.
                    Fa.apply(this)
                    recreate()
                }
            }
            Ui.pressScale(cell)
            langPills.add(cell)
            container.addView(
                cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            )
        }
        refreshLanguageSeg()
        return container
    }

    private fun refreshLanguageSeg() {
        paintSegment(langPills) { LANGUAGES[it] == selLanguage }
    }

    // =====================================================================
    // Model picker
    // =====================================================================

    /**
     * The expandable box under the Model name field.
     *
     * When a base URL is entered, the model IDs served at `<base>/models` are
     * fetched and listed here; tapping one writes it into the Model name field
     * (and straight into prefs). The box opens collapsed — it is a shortcut,
     * not a setting — and only fetches while open, so a settings visit never
     * fires network traffic on its own.
     */
    private fun modelPickerBox(): LinearLayout {
        val box = Ui.column(this)
        box.layoutParams = Ui.matchWrap()

        val chevron = ImageView(this)
        chevron.setImageDrawable(Icons.of("chevron-down", Theme.TEXT_MUTED, Ui.STROKE))
        chevron.scaleType = ImageView.ScaleType.FIT_CENTER
        chevron.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val chevSize = Theme.dp(this, 20.0f)
        chevron.layoutParams = LinearLayout.LayoutParams(chevSize, chevSize)
        modelsChevron = chevron

        box.addView(
            Ui.cardRow(
                this, "list", Fa.SET_MODELS, Fa.SET_MODELS_H, chevron,
                Runnable { toggleModelsBox() }
            )
        )

        val body = Ui.column(this)
        body.visibility = View.GONE
        val bodyPad = Theme.dp(this, Ui.Space.L)
        body.setPaddingRelative(bodyPad, 0, bodyPad, Theme.dp(this, Ui.Space.M))
        modelsBody = body

        val status = Ui.text(
            this, Fa.SET_MODELS_HINT, Ui.Type.META, Theme.TEXT_MUTED, Theme.ui()
        )
        Ui.rowLabel(status)
        modelsStatus = status
        val statusLp = Ui.matchWrap()
        statusLp.bottomMargin = Theme.dp(this, Ui.Space.S)
        body.addView(status, statusLp)

        val fetchRow = Ui.row(this)
        fetchRow.addView(
            Ui.pillButton(
                this, Fa.SET_MODELS_FETCH, "refresh", Ui.SECONDARY
            ) { fetchModels() }
        )
        val fetchLp = Ui.matchWrap()
        fetchLp.bottomMargin = Theme.dp(this, Ui.Space.S)
        body.addView(fetchRow, fetchLp)

        val rows = Ui.column(this)
        modelsRows = rows
        body.addView(rows, Ui.matchWrap())

        box.addView(body)
        return box
    }

    private fun toggleModelsBox() {
        setModelsBoxExpanded(!modelsExpanded, animate = true)
    }

    /**
     * Opens/closes the model picker with a real height animation instead of
     * the old instant GONE/VISIBLE flip: the body grows from 0 to its measured
     * height (or shrinks back) on the [Ui] easing curve, the chevron rotating
     * in sync. Compositor-friendly properties only — no relayout storm.
     */
    private fun setModelsBoxExpanded(expanded: Boolean, animate: Boolean) {
        val body = modelsBody ?: return
        if (expanded == modelsExpanded && !animate) {
            return
        }
        modelsExpanded = expanded
        modelsBoxAnimator?.cancel()
        modelsBoxAnimator = null
        modelsChevron?.animate()?.cancel()
        modelsChevron?.animate()?.rotation(if (expanded) 180.0f else 0.0f)
            ?.setDuration(Ui.D_FAST)?.setInterpolator(Ui.ease())?.start()
        if (!animate) {
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            if (expanded) {
                val base = etBase?.text?.toString()?.trimJava() ?: ""
                if (modelsFetchKey() != modelsFetchedFor) {
                    fetchModels()
                } else {
                    renderModelRows(modelsCache)
                }
            }
            return
        }
        if (expanded) {
            // The body is GONE so it has no height yet: measure it off-layout
            // at the width it will have, then grow from 0 to that height.
            val parentWidth = (body.parent as? View)?.width ?: 0
            body.visibility = View.VISIBLE
            if (parentWidth > 0) {
                body.measure(
                    View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }
            val target = body.measuredHeight
            if (target <= 0) {
                // Unmeasurable (first layout race) — fall back to instant show.
                body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                afterModelsBoxOpened()
                return
            }
            animateModelsBoxHeight(body, 0, target) {
                body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                body.requestLayout()
                afterModelsBoxOpened()
            }
        } else {
            val start = body.height
            if (start <= 0) {
                body.visibility = View.GONE
                return
            }
            animateModelsBoxHeight(body, start, 0) {
                body.visibility = View.GONE
                body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                body.requestLayout()
            }
        }
        // No tick here: the header is a cardRow with an onClick, so pressScale
        // already ticked on the press down.
    }

    /** Shared driver for the model box height animation. */
    private fun animateModelsBoxHeight(
        body: View,
        from: Int,
        to: Int,
        onEnd: () -> Unit
    ) {
        val anim = ValueAnimator.ofInt(from, to)
        modelsBoxAnimator = anim
        anim.addUpdateListener { v ->
            body.layoutParams.height = v.animatedValue as Int
            body.requestLayout()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (modelsBoxAnimator == animation) {
                    modelsBoxAnimator = null
                }
                onEnd()
            }

            override fun onAnimationCancel(animation: android.animation.Animator) {
                if (modelsBoxAnimator == animation) {
                    modelsBoxAnimator = null
                }
            }
        })
        anim.duration = Ui.D_BASE
        anim.interpolator = Ui.ease()
        anim.start()
    }

    /** Fetch-or-render once the picker body is open. */
    private fun afterModelsBoxOpened() {
        if (modelsFetchKey() != modelsFetchedFor) {
            fetchModels()
        } else {
            renderModelRows(modelsCache)
        }
    }

    private fun setModelsStatus(value: String) {
        modelsStatus?.text = value
    }

    /**
     * Fetches the model list for the base URL currently in the field.
     *
     * Runs off the main thread with a generation guard, exactly like the
     * connection test: a second fetch (or leaving the screen) retires the
     * first one's callback instead of letting a stale response repaint.
     */
    /**
     * Fetches the model list for the base URL currently in the field.
     *
     * Two hard rules:
     * 1. No key, no request. The list is read from the endpoint ONLY after an
     *    API key has been entered — never before.
     * 2. No protocol guesswork by the user. The candidates from
     *    [LlmClient.protocolCandidates] are tried in order until one answers;
     *    a mismatch-shaped failure moves to the next candidate instead of
     *    surfacing as an error.
     *
     * Runs off the main thread with a generation guard, exactly like the
     * connection test: a second fetch (or leaving the screen) retires the
     * first one's callback instead of letting a stale response repaint.
     *
     * @param autoExpand when true and the fetch returns models while the
     * picker is still closed, the picker opens itself — this is how entering
     * a key produces the list without a second tap.
     */
    private fun fetchModels(autoExpand: Boolean = false) {
        val base = etBase?.text?.toString()?.trimJava() ?: prefs.baseUrl().trimJava()
        if (base.isEmpty()) {
            setModelsStatus(Fa.SET_MODELS_HINT)
            renderModelRows(emptyList())
            return
        }
        val url: String
        try {
            url = modelsListUrl(base)
        } catch (e: Exception) {
            setModelsStatus(Fa.SET_MODELS_FAILED)
            renderModelRows(emptyList())
            return
        }
        // The user's own endpoint, so the same policy the chat path uses:
        // http and https are both accepted for any host (the user chose it
        // deliberately), metadata hosts never.
        try {
            NetworkPolicy.requireUserEndpoint(url)
        } catch (e: Exception) {
            setModelsStatus(e.message?.takeIf { it.isNotBlankJava() } ?: Fa.SET_MODELS_FAILED)
            renderModelRows(emptyList())
            return
        }
        // Rule 1: nothing is read from the endpoint before a key exists. A
        // router-keys-only setup counts — it chats fine without the main key.
        val key = etKey?.text?.toString()?.trimJava() ?: ""
        val effKey = if (key.isNotEmpty()) key else prefs.apiKeys().firstOrNull() ?: ""
        if (effKey.isEmpty()) {
            setModelsStatus(Fa.SET_MODELS_NEED_KEY)
            renderModelRows(emptyList())
            return
        }
        val fetchKey = base + "\u0000" + key
        val model = etModel?.text?.toString() ?: ""
        val candidates = LlmClient.protocolCandidates(LlmClient.PROTOCOL_AUTO, base, model)
        val generation = ++modelsGeneration
        setModelsStatus(Fa.SET_MODELS_LOADING)
        renderModelRows(emptyList())
        Thread {
            val outcome: Result<List<String>> = try {
                var lastMismatch: LlmClient.LlmException? = null
                var ids: List<String>? = null
                for (protocol in candidates) {
                    try {
                        ids = fetchModelIds(url, effKey, protocol)
                        // The endpoint answered under this protocol: remember
                        // it, so chat requests skip the guessing entirely.
                        LlmClient.rememberProtocol(base, protocol)
                        break
                    } catch (e: LlmClient.LlmException) {
                        if (LlmClient.isProtocolMismatch(e)) {
                            lastMismatch = e
                            continue
                        }
                        throw e
                    }
                }
                val found = ids
                if (found != null) {
                    Result.success(found)
                } else {
                    Result.failure(
                        lastMismatch ?: LlmClient.LlmException(
                            Fa.SET_MODELS_FAILED, 0, false, 0L, ""
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
            ui.post {
                if (generation != modelsGeneration || isFinishing || isDestroyed) {
                    return@post
                }
                outcome.onSuccess { ids ->
                    modelsFetchedFor = fetchKey
                    modelsCache = ids
                    if (ids.isEmpty()) {
                        setModelsStatus(Fa.SET_MODELS_EMPTY)
                    } else {
                        setModelsStatus(
                            localizeDigits(ids.size.toString()) + " " + Fa.SET_MODELS_FOUND
                        )
                    }
                    renderModelRows(ids)
                    if (autoExpand && ids.isNotEmpty() && !modelsExpanded) {
                        setModelsBoxExpanded(true, animate = true)
                    }
                }.onFailure { e ->
                    modelsFetchedFor = null
                    setModelsStatus(
                        e.message?.takeIf { it.isNotBlankJava() } ?: Fa.SET_MODELS_FAILED
                    )
                    renderModelRows(emptyList())
                }
            }
        }.start()
    }

    /**
     * `<base>/models`, tolerating what users paste into the Base URL field: a
     * trailing slash, a `/models` tail already present, a full chat endpoint,
     * or (Gemini) an RPC-style `.../models/<name>:generateContent` tail.
     */
    private fun modelsListUrl(base: String): String {
        var n = NetworkPolicy.normalizeUserEndpoint(base.trimJava())
        while (n.endsWith("/")) {
            n = n.substring(0, n.length - 1)
        }
        require(n.isNotEmpty()) { "empty base URL" }
        var lower = n.lowercase(Locale.US)
        // A /models segment anywhere pins the root: ".../v1beta/models" and
        // ".../v1beta/models/gemini-x:generateContent" both mean the list lives
        // at the segment itself.
        val seg = lower.lastIndexOf("/models")
        if (seg >= 0) {
            return n.substring(0, seg + "/models".length)
        }
        for (tail in arrayOf("/chat/completions", "/messages")) {
            if (lower.endsWith(tail)) {
                n = n.substring(0, n.length - tail.length)
                lower = n.lowercase(Locale.US)
            }
        }
        return "$n/models"
    }

    @Throws(Exception::class)
    private fun fetchModelIds(url: String, key: String, protocol: String): List<String> {
        NetworkPolicy.requireUserEndpoint(url)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            val timeoutMs = Math.max(
                Prefs.MIN_TIMEOUT_SECONDS,
                Math.min(Prefs.MAX_TIMEOUT_SECONDS, prefs.timeoutSeconds())
            ) * 1000
            // The list is one small response; it must not be allowed to hang
            // for the user's whole chat timeout against a dead host.
            connection.connectTimeout = Math.min(15000, timeoutMs)
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "VegaAgent/1.1.0 (Android)")
            when (protocol) {
                LlmClient.PROTOCOL_ANTHROPIC -> {
                    if (key.isNotEmpty()) {
                        connection.setRequestProperty("x-api-key", key)
                    }
                    connection.setRequestProperty("anthropic-version", "2023-06-01")
                }

                LlmClient.PROTOCOL_GEMINI -> {
                    if (key.isNotEmpty()) {
                        connection.setRequestProperty("x-goog-api-key", key)
                    }
                }

                else -> {
                    if (key.isNotEmpty()) {
                        connection.setRequestProperty("Authorization", "Bearer $key")
                    }
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = try {
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            } catch (e: Exception) {
                ""
            }
            if (code !in 200..299) {
                // LlmException, not IOException: the candidate loop needs the
                // status code to tell "wrong protocol" (404/405/…) apart from
                // "right protocol, wrong key" (401) and other real failures.
                val detail = shortError(body)
                val message = if (detail.isNotEmpty()) "HTTP $code: $detail" else "HTTP $code"
                throw LlmClient.LlmException(message, code, false, 0L, "", body)
            }
            return parseModelIds(body, protocol)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Model IDs out of a `/models` response. OpenAI-compatible endpoints answer
     * `{"data":[{"id":"…"}]}`; Gemini answers `{"models":[{"name":"models/…"}]}`;
     * a few gateways wrap the OpenAI shape in a top-level "models" array.
     */
    private fun parseModelIds(json: String, protocol: String): List<String> {
        val ids = LinkedHashSet<String>()
        try {
            val root = JSONObject(json)
            if (protocol == LlmClient.PROTOCOL_GEMINI) {
                val arr = root.optJSONArray("models")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        var name = arr.optJSONObject(i)?.optString("name") ?: ""
                        if (name.startsWith("models/")) {
                            name = name.substring("models/".length)
                        }
                        if (name.isNotBlankJava()) {
                            ids.add(name)
                        }
                    }
                }
            } else {
                val data = root.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val id = data.optJSONObject(i)?.optString("id") ?: ""
                        if (id.isNotBlankJava()) {
                            ids.add(id)
                        }
                    }
                }
                if (ids.isEmpty()) {
                    val models = root.optJSONArray("models")
                    if (models != null) {
                        for (i in 0 until models.length()) {
                            val obj = models.optJSONObject(i)
                            val id = obj?.optString("id")?.takeIf { it.isNotBlankJava() }
                                ?: obj?.optString("name") ?: ""
                            if (id.isNotBlankJava()) {
                                ids.add(id)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Malformed body → empty list; the status line says so.
        }
        return ids.sorted()
    }

    /** One line of the error body, for the status line when the fetch fails. */
    private fun shortError(body: String): String {
        if (body.isBlankJava()) {
            return ""
        }
        return try {
            val root = JSONObject(body)
            val err = root.optJSONObject("error")
            val msg = err?.optString("message")?.takeIf { it.isNotBlankJava() }
                ?: root.optString("message").takeIf { it.isNotBlankJava() }
                ?: body.trimJava()
            msg.trimJava().take(160)
        } catch (e: Exception) {
            body.trimJava().take(160)
        }
    }

    private fun renderModelRows(ids: List<String>) {
        val rows = modelsRows ?: return
        rows.removeAllViews()
        val current = etModel?.text?.toString()?.trimJava() ?: ""
        val shown = ids.take(MAX_MODELS_SHOWN)
        for ((index, id) in shown.withIndex()) {
            if (index > 0) {
                rowDivider(rows, ROW_INSET)
            }
            rows.addView(modelRow(id, id == current))
        }
        val rest = ids.size - shown.size
        if (rest > 0) {
            if (shown.isNotEmpty()) {
                rowDivider(rows, ROW_INSET)
            }
            val more = Ui.text(
                this, "+" + localizeDigits(rest.toString()) + " " + Fa.SET_MODELS_MORE,
                Ui.Type.META, Theme.TEXT_MUTED, Theme.ui()
            )
            Ui.rowLabel(more)
            val moreLp = Ui.matchWrap()
            moreLp.topMargin = Theme.dp(this, Ui.Space.S)
            moreLp.marginStart = Theme.dp(this, Ui.Space.L)
            rows.addView(more, moreLp)
        }
    }

    /**
     * One tappable model ID. An LTR island, like the Model name field above
     * it: a model ID is a Latin identifier whatever the interface language, so
     * the row lays out left-to-right instead of inheriting the mirrored
     * direction.
     */
    private fun modelRow(id: String, selected: Boolean): LinearLayout {
        val row = Ui.row(this)
        row.layoutDirection = View.LAYOUT_DIRECTION_LTR
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = Theme.dp(this, 48.0f)
        row.setPaddingRelative(
            Theme.dp(this, Ui.Space.L), Theme.dp(this, 10.0f),
            Theme.dp(this, Ui.Space.L), Theme.dp(this, 10.0f)
        )
        row.background = Theme.rippleTransparent(Theme.R_SM, this)

        val label = Ui.text(this, id, Ui.Type.LABEL, Theme.TEXT, Theme.ui())
        label.setSingleLine(true)
        label.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        row.addView(label, Ui.grow())

        val check = ImageView(this)
        check.setImageDrawable(Icons.of("check", Theme.ACCENT, Ui.STROKE))
        check.scaleType = ImageView.ScaleType.FIT_CENTER
        check.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val checkSize = Theme.dp(this, 18.0f)
        val checkLp = LinearLayout.LayoutParams(checkSize, checkSize)
        checkLp.marginStart = Theme.dp(this, Ui.Space.M)
        check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        row.addView(check, checkLp)

        row.contentDescription = id
        row.setOnClickListener { selectModel(id, row) }
        Ui.pressScale(row)
        return row
    }

    /**
     * Writes the tapped model into the Model name field — and straight into
     * prefs, so the choice is durable even if the user leaves the screen
     * before the debounced instant-save fires.
     */
    private fun selectModel(id: String, row: View) {
        etModel?.setText(id)
        try {
            etModel?.setSelection(id.length)
        } catch (e: Exception) {
        }
        prefs.setModel(id)
        // Same ceiling rule as a preset tap / hand-typed change: a model the
        // picker returned is a model change, so its caps apply at once. (The
        // onPause change check would miss this path — prefs are already new.)
        if (applyModelCaps(id)) {
            say(Fa.SET_CAPS_AUTO, false)
        }
        Ui.tick(row)
        renderModelRows(modelsCache)
    }

    // =====================================================================
    // Connection test + save
    // =====================================================================

    private fun runTest() {
        val base = etBase?.text?.toString()?.trimJava() ?: ""
        var key = etKey?.text?.toString()?.trimJava() ?: ""
        if (key.isEmpty()) {
            // A router-keys-only setup is explicitly valid (Prefs.isConfigured
            // accepts it) and chats fine, but the test used to send no key at
            // all and reported a hard auth failure.
            key = prefs.apiKeys().firstOrNull() ?: ""
        }
        val model = etModel?.text?.toString()?.trimJava() ?: ""
        if (base.isEmpty() || model.isEmpty()) {
            say(Fa.SET_NEED_FIELDS, false)
            return
        }
        val generation = ++connectionTestGeneration
        tvTestStatus?.setTextColor(Theme.TEXT_MUTED)
        tvTestStatus?.text = Fa.SET_TESTING
        paintConnDot(Theme.TEXT_MUTED)
        Thread {
            // The protocol is auto-detected inside: the test walks the
            // candidates, so a wrong heuristic guess still reports success.
            val failure = LlmClient.testConnection(
                base, key, model, LlmClient.PROTOCOL_AUTO, prefs.timeoutSeconds()
            )
            ui.post {
                if (generation != connectionTestGeneration || isFinishing || isDestroyed) {
                    return@post
                }
                // The diff inks, not Theme.RED / Theme.GREEN.
                //
                // Those two are near-black GREYS in this palette — a deliberate
                // choice everywhere else, and the wrong one here: "reachable" and
                // "rejected" are opposites, not two amounts of one thing, and two
                // shades of grey cannot say which is which at a glance. The diff
                // pair is the palette's one sanctioned hue, defined for exactly
                // that distinction, and this is the only place on the screen that
                // borrows it.
                if (failure != null) {
                    tvTestStatus?.setTextColor(Theme.DIFF_DEL)
                    tvTestStatus?.text = shortFailure(failure)
                    paintConnDot(Theme.DIFF_DEL)
                } else {
                    tvTestStatus?.setTextColor(Theme.DIFF_ADD)
                    tvTestStatus?.text = Fa.SET_TEST_OK
                    paintConnDot(Theme.DIFF_ADD)
                }
            }
        }.start()
    }

    /**
     * The connection verdict line is single-line real estate: it shows the
     * failure's essence (the base message), not the 500-char server detail,
     * retry-in and request id that [LlmClient.httpMessage] appends for logs.
     * First line, capped — the full text stays in the exception for debugging.
     */
    private fun shortFailure(failure: String): String {
        var line = failure.trimJava()
        val nl = line.indexOf('\n')
        if (nl >= 0) {
            line = line.substring(0, nl).trimJava()
        }
        return Util.truncate(line, 140)
    }

    private fun updateTemp(progress: Int, persist: Boolean) {
        // Locale.US formats the VALUE — the app must never render 0,70 because a
        // phone is set to a comma locale — and [localizeDigits] then renders the
        // digits in the interface's own numerals. The separator stays an ASCII
        // '.': it is the one mark in the string that is part of the number's
        // notation rather than its script, and swapping it for U+066B would be
        // the only place in the app that does.
        tvTemp?.text = localizeDigits(String.format(Locale.US, "%.2f", progress / 100.0f))
        if (persist) {
            prefs.setTemperature(progress / 100.0f)
        }
    }

    /**
     * Rewrites the ASCII digits of an already-FORMATTED value in the interface's
     * own numerals, leaving everything else alone.
     *
     * [Lang.num] takes a number, and a temperature readout is a string with a
     * separator in it. Mapping digit by digit THROUGH that helper keeps one
     * forward digit table in the app instead of a second copy here — this file
     * already owns the reverse map ([normalizeDigits]) for what the user types,
     * and two tables pointing opposite ways is how they drift.
     */
    private fun localizeDigits(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            if (ch in '0'..'9') {
                sb.append(Lang.num(this, ch - '0'))
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun installInstantTextSettings() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scheduleTextSettings()
                // The connection summary describes the LIVE fields, so it has to move
                // with them — a summary a keystroke behind would confirm a setup the
                // user has already changed. The preset selector reads the same
                // field, so it follows too: a hand-typed URL shows as Custom.
                refreshConnectionCard()
                refreshPresetSelector()
            }

            override fun afterTextChanged(editable: Editable?) {}
        }
        etBase?.addTextChangedListener(watcher)
        etKey?.addTextChangedListener(watcher)
        etModel?.addTextChangedListener(watcher)
        etMaxTok?.addTextChangedListener(watcher)
        etContextTok?.addTextChangedListener(watcher)
        etTimeout?.addTextChangedListener(watcher)
        etSys?.addTextChangedListener(watcher)
        // The model picker follows the base URL: while its box is open, a
        // settled edit re-fetches the list for the new address. Debounced, so
        // typing the URL does not fire a request per keystroke.
        etBase?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                ui.removeCallbacks(modelsFetchDebounced)
                ui.postDelayed(modelsFetchDebounced, 700L)
            }

            override fun afterTextChanged(editable: Editable?) {}
        })
        // The model list is fetched only AFTER a key exists — never before. The
        // moment a distinct key settles in the field, the list is fetched for it
        // and the picker opens itself, so a pasted key produces the models with
        // no second tap. Debounced per distinct key, not per keystroke.
        etKey?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                ui.removeCallbacks(keyAutoFetchDebounced)
                ui.postDelayed(keyAutoFetchDebounced, 900L)
            }

            override fun afterTextChanged(editable: Editable?) {}
        })
        // These replace the feedback listener toggleRow() installs, so they have
        // to re-apply the haptic tick themselves — otherwise these three switches
        // would be the only ones in the app that flip silently.
        swWorkflow?.setOnCheckedChangeListener { view, checked ->
            prefs.setDynamicWorkflow(checked)
            // Switching the mode moves the reasoning dial to that mode's home
            // position: XHIGH (locked) while it is on, MEDIUM (freely
            // adjustable) when it is off. Leaving the dial wherever the forced
            // level had parked it would strand the user on xhigh with no
            // indication that it was the workflow's doing, not their choice.
            selLevel = if (checked) "xhigh" else "medium"
            prefs.setThinkingLevel(selLevel)
            sbThink?.progress = idxOfLevel(selLevel)
            Ui.tick(view)
            // Turning this on raises the effective reasoning level to XHIGH, so
            // repaint the slider — otherwise it keeps showing the old level while
            // the agent actually runs at a higher one.
            refreshLevel()
        }
        swWeb?.setOnCheckedChangeListener { view, checked ->
            prefs.setWebSearch(checked)
            Ui.tick(view)
        }
        swLocalNet?.setOnCheckedChangeListener { view, checked ->
            prefs.setAllowLocalNetwork(checked)
            Ui.tick(view)
        }
        swHaptic?.setOnCheckedChangeListener { view, checked ->
            prefs.setHapticEnabled(checked)
            // Re-applied like the switches above: flipping it ON still confirms
            // itself with a tick; flipping it OFF goes quiet, which is the point.
            Ui.tick(view)
        }
    }

    private fun scheduleTextSettings() {
        ui.removeCallbacks(applyTextSettings)
        ui.postDelayed(applyTextSettings, 350L)
    }

    /**
     * Writes every visible text field back to [prefs]. Each field is read only
     * when its page is on screen — the provider fields live on the hub, the
     * generation fields and the system prompt on the "Intelligence & behavior"
     * page — so leaving a sub-page flushes exactly what was editable there and
     * nothing else.
     */
    private fun applyTextSettingsNow() {
        ui.removeCallbacks(applyTextSettings)
        if (resetting) {
            // "Reset settings" clears prefs then recreates; recreate() runs
            // onPause first, which used to write the still-populated fields
            // straight back and silently un-reset base URL, model, max tokens
            // and the custom system prompt.
            return
        }
        etBase?.let { baseField ->
            val base = baseField.text.toString().trimJava()
            if (base.isNotEmpty()) {
                prefs.setBaseUrl(NetworkPolicy.normalizeUserEndpoint(base))
            }
        }
        etModel?.let { modelField ->
            val model = modelField.text.toString().trimJava()
            if (model.isNotEmpty()) {
                val oldModel = prefs.model()
                prefs.setModel(model)
                // A hand-typed model change brings the new model's ceiling
                // with it, the same as a preset tap does. Manual tweaks to
                // the two fields survive as long as the model itself is
                // untouched; an unknown model leaves everything alone.
                if (!model.equals(oldModel, ignoreCase = true)) {
                    applyModelCaps(model)
                }
            }
        }
        etKey?.let { keyField ->
            val key = keyField.text.toString().trimJava()
            if (key != lastSavedKey) {
                // The save itself no longer fails when the keystore is unavailable — the
                // key is stored unencrypted instead, because refusing to save it left the
                // app permanently unusable on those devices. Only a genuine disk failure
                // returns false now, and the warning is about PROTECTION, not about
                // whether the key was kept.
                if (prefs.setApiKey(key)) {
                    lastSavedKey = key
                    if (key.isNotEmpty() && !prefs.apiKeyIsEncrypted()) {
                        say(Fa.SET_KEYSTORE_UNAVAILABLE, true)
                    }
                } else {
                    say(Fa.ERR_UNKNOWN, true)
                }
            }
        }
        etMaxTok?.let { maxTokField ->
            val maxTokens = parseInt(maxTokField.text.toString(), -1)
            if (maxTokens > 0) {
                prefs.setMaxTokens(maxTokens)
            }
        }
        etContextTok?.let { ctxField ->
            val raw = ctxField.text.toString().trimJava()
            if (raw.isEmpty()) {
                // Cleared: back to following the model's ceiling automatically.
                prefs.setContextTokens(0)
            } else {
                val ctx = parseInt(raw, -1)
                if (ctx > 0) {
                    prefs.setContextTokens(ctx)
                }
            }
        }
        etTimeout?.let { timeoutField ->
            val timeout = parseInt(timeoutField.text.toString(), -1)
            if (timeout > 0) {
                // Prefs clamps to [MIN, MAX], so a typo like 1 or 99999 becomes a
                // usable value instead of an app that hangs or fails every request.
                prefs.setTimeoutSeconds(timeout)
            }
        }
        etSys?.let { sysField ->
            prefs.setSystemPrompt(sysField.text.toString().trimJava())
        }
        refreshPresetSelector()
        refreshConnectionCard()
    }

    override fun onPause() {
        applyTextSettingsNow()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // The palette is process-global; the chat screen's own theme toggle can
        // have swapped it while this screen sat on the back stack. Rebuild only
        // when the generation we painted with is no longer the live one.
        Theme.applyFromPrefs(this, prefs)
        if (Theme.revision != appliedRevision && !isFinishing) {
            appliedRevision = Theme.revision
            recreate()
        }
        // B2: skills live on disk and can change outside this screen (the
        // agent never writes them, but a file manager or a re-picked folder
        // does) — rescan every time we come back.
        renderSkills()
        // The battery exemption can only change on the system page this row
        // opens — repaint it on return so the state line is never stale.
        refreshBatteryRow()
    }

    override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
        super.onConfigurationChanged(configuration)
        // The screen is a single vertical scroll, so it reflows on its own; the
        // only change worth rebuilding for is a palette flip. Previously this
        // Activity declared no configChanges at all, so every rotation or
        // font-size change destroyed it — losing the scroll position, the
        // focused field, the pending router key and any in-flight connection
        // test (none of which are saved, since no view here has an id).
        Theme.applyFromPrefs(this, prefs)
        if (Theme.revision != appliedRevision && !isFinishing) {
            appliedRevision = Theme.revision
            recreate()
        }
    }

    override fun onStop() {
        super.onStop()
        // Guards against double-taps queueing two recreate()s. It is normally
        // moot because recreate() yields a fresh instance, but when it is
        // deferred (activity not resumed, OEM relaunch quirks) the theme cells
        // would stay dead forever with no way to recover in-screen.
        applyingTheme = false
    }

    /**
     * Mirrors the fade MainActivity opens this screen with, so leaving matches
     * arriving. Overriding finish() covers every exit — the masthead chevron, the
     * system back gesture and any programmatic close — in one place.
     *
     * A theme change closes this screen via recreate(), which must NOT be dressed
     * as a navigation; the flag lets that path fall through cleanly.
     */
    override fun finish() {
        super.finish()
        if (applyingTheme) {
            return
        }
        try {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.settings_pop_enter, R.anim.settings_pop_exit)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * On a sub-page, the system back gesture returns to the hub — the same move
     * the sub-page's own back chevron makes. Only the hub itself finishes the
     * Activity, mirroring MainActivity's drawer behaviour.
     */
    @Deprecated("Kept to preserve the hub's back behaviour")
    override fun onBackPressed() {
        if (page != PAGE_HUB) {
            showPage(PAGE_HUB)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        Sheet.dismissAll()
        connectionTestGeneration++
        ui.removeCallbacks(applyTextSettings)
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // =====================================================================
    // Pages — the hub and its two sub-pages
    // =====================================================================

    /**
     * Builds whichever page [page] names into [pagePanel]: drops the outgoing
     * page's views and every holder it bound, builds the new page, rewinds the
     * scroller and rewires the instant-save listeners for the fresh views.
     *
     * [animate] is false only for the very first build — the Activity itself
     * already arrives with the settings_enter transition, and a second entrance
     * on top of it would read as a stutter.
     */
    private fun buildPage(animate: Boolean) {
        val panel = pagePanel ?: return
        panel.animate().cancel()
        panel.removeAllViews()
        clearPageRefs()
        when (page) {
            PAGE_TOOLS -> buildToolsPage(panel)
            PAGE_BRAIN -> buildBrainPage(panel)
            PAGE_UI -> buildUiPage(panel, animate)
            else -> buildHub(panel, animate)
        }
        installInstantTextSettings()
        pageScroll?.scrollTo(0, 0)
        if (animate) {
            animatePage(page != PAGE_HUB)
        }
    }

    /**
     * Drops every view holder the outgoing page bound, so no callback from the
     * old page can ever touch the new one. Selections are re-read from [prefs]
     * rather than carried over — the page is rebuilt, not resumed.
     */
    private fun clearPageRefs() {
        ui.removeCallbacks(modelsFetchDebounced)
        ui.removeCallbacks(keyAutoFetchDebounced)
        etBase = null
        etKey = null
        etMaxTok = null
        etContextTok = null
        etTimeout = null
        etModel = null
        etNewKey = null
        etSys = null
        keysBox = null
        tvKeyCount = null
        sbTemp = null
        swWeb = null
        swWorkflow = null
        swLocalNet = null
        swHaptic = null
        tvTemp = null
        tvTestStatus = null
        tvConnSummary = null
        tvConnProblem = null
        tvKeyWarning = null
        connDot = null
        sbThink = null
        thinkReadout = null
        thinkDesc = null
        modelsBody = null
        modelsChevron = null
        modelsRows = null
        modelsStatus = null
        modelsExpanded = false
        // A stale fetch landing after the rebuild must not repaint the new page.
        modelsGeneration++
        modelsFetchedFor = null
        modelsCache = emptyList()
        lastKeyAutoFetch = ""
        modelsBoxAnimator?.cancel()
        modelsBoxAnimator = null
        themePills.clear()
        langPills.clear()
        skillsBox = null
        skillsListBox = null
        skillsToggleChevron = null
        selTheme = prefs.themeMode()
        selLevel = "medium"
        selLanguage = prefs.language()
    }

    /**
     * Switches to [newPage]. Anything typed on the outgoing page is flushed to
     * [prefs] first — [onPause] only fires when the Activity itself pauses, not
     * when the page changes inside it. The motion follows the target: a
     * sub-page RISES in, the hub SETTLES back.
     */
    private fun showPage(newPage: Int) {
        if (newPage == page || pagePanel == null) {
            return
        }
        applyTextSettingsNow()
        ui.removeCallbacks(applyTextSettings)
        page = newPage
        buildPage(animate = true)
    }

    /**
     * The page-change motion, in the register of the Activity transitions this
     * screen already uses. Forward reuses the settings_enter gesture — a short
     * rise with a fade, the "a section comes up" feel — and back mirrors
     * settings_pop_enter's gentle settle. Both run on the property animator, so
     * they need no extra stub surface.
     */
    private fun animatePage(forward: Boolean) {
        val panel = pagePanel ?: return
        panel.animate().cancel()
        if (forward) {
            panel.alpha = 0.0f
            panel.translationY = Theme.dpf(this, 28.0f)
            panel.animate()
                .alpha(1.0f)
                .translationY(0.0f)
                .setDuration(280L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            panel.alpha = 0.85f
            panel.scaleX = 0.99f
            panel.scaleY = 0.99f
            panel.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(240L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    /**
     * The main menu: provider and connection, the three destination rows,
     * reset and about. The key router, the generation controls, the reasoning
     * controls, the tools, the access switch and the custom prompt all live
     * behind the destination rows now — the hub stays a short menu and each
     * sub-page gets the room its controls need.
     *
     * [entrance] replays the staggered row entrance; it is true only when the
     * page was built for navigation, never for the first build (the Activity
     * itself already arrives with the settings_enter transition) and never for
     * a rebuild mid-typing.
     */
    private fun buildHub(panel: LinearLayout, entrance: Boolean) {
        panel.addView(masthead(), Ui.matchWrap())

        // --- provider + connection ------------------------------------------
        //
        // ONE card holds everything about "where do requests go": the preset strip,
        // the three fields the presets write, and — as the card's closing row — the
        // test that exercises exactly those three values.
        //
        // The test used to be a 152dp bordered card at the very top of the screen,
        // above the heading for the fields it tests. It answered the right question
        // ("is this configuration going to work?") in the wrong place: an icon
        // badge, a title, a summary, a red block and a 48dp button, all detached
        // from the inputs they describe. As a trailing row it is ~58dp, it sits
        // directly under the values it reads, and it still says the same three
        // things — what will be used, what [Preflight] already knows is wrong, and
        // what happened when you last pressed Test.
        panel.addView(Ui.sectionLabel(this, Fa.SET_PROVIDER))
        val provider = card()
        provider.addView(presetSelector())
        rowDivider(provider, 0.0f)
        etBase = field(
            provider, "globe", Fa.SET_BASE_URL, prefs.baseUrl(),
            InputType.TYPE_TEXT_VARIATION_URI, "https://api.openai.com/v1", true
        )
        rowDivider(provider, ROW_INSET)
        addKeyField(provider)
        rowDivider(provider, ROW_INSET)
        etModel = field(
            provider, "cpu", Fa.SET_MODEL, prefs.model(), InputType.TYPE_CLASS_TEXT, "gpt-4o",
            true
        )
        rowDivider(provider, ROW_INSET)
        provider.addView(modelPickerBox())
        rowDivider(provider, 0.0f)
        provider.addView(connectionCard())
        // Said once, quietly, next to the key it is about. `SecureStore` now saves a
        // key even when the device's keystore cannot protect it — refusing to save it
        // left the app permanently unusable on those devices — so the state has to be
        // visible somewhere rather than silently accepted.
        val keyWarning = cardNote(provider, Fa.SET_KEY_PLAIN, Theme.DIFF_DEL)
        keyWarning.visibility = View.GONE
        tvKeyWarning = keyWarning
        panel.addView(provider)

        // NOTE: there is no protocol section any more. The wire protocol is
        // detected automatically per endpoint (LlmClient.protocolCandidates):
        // the URL heuristic picks the first candidate and a mismatch-shaped
        // failure transparently retries the next one. Nothing here for the
        // user to choose — or to choose wrong.

        // --- destinations -----------------------------------------------------
        //
        // Three iOS-style menu rows in one card: a solid coloured tile, a title
        // naming the destination and a muted line naming what is inside, closed
        // by the direction-aware chevron. The tiles carry iOS's own system hues
        // (blue for tools, purple for intelligence, orange for the interface) —
        // the one place this screen is allowed colour, in the register of iOS
        // Settings. The separators start where the labels start, so the tile
        // column runs unbroken down the card.
        panel.addView(Ui.sectionLabel(this, Fa.SET_SECTIONS))
        val sections = card()
        sections.addView(
            Ui.iosRow(
                this, "tool", Theme.tileBlue(),
                Fa.SET_TOOLS, Fa.SET_TOOLS_SUB,
                Runnable { showPage(PAGE_TOOLS) }
            ),
            Ui.matchWrap()
        )
        rowDivider(sections, TILE_ROW_INSET)
        sections.addView(
            Ui.iosRow(
                this, "sparkle", Theme.tilePurple(),
                Fa.SET_BRAIN, Fa.SET_BRAIN_SUB,
                Runnable { showPage(PAGE_BRAIN) }
            ),
            Ui.matchWrap()
        )
        rowDivider(sections, TILE_ROW_INSET)
        sections.addView(
            Ui.iosRow(
                this, "sliders-h", Theme.tileOrange(),
                Fa.SET_UI, Fa.SET_UI_SUB,
                Runnable { showPage(PAGE_UI) }
            ),
            Ui.matchWrap()
        )
        panel.addView(sections)

        // NOTE: there is no appearance section on the hub any more. Theme,
        // language and haptics live on the "User Interface" page behind the row
        // above — they change nothing about what the agent DOES, so they get
        // their own destination instead of stretching the menu.

        // --- reset ----------------------------------------------------------
        panel.addView(Ui.sectionLabel(this, Fa.SET_RESET))
        val reset = resetSection()
        panel.addView(reset)

        // --- about ----------------------------------------------------------
        panel.addView(Ui.sectionLabel(this, Fa.SET_ABOUT))
        val about = aboutSection()
        panel.addView(about)

        if (entrance) {
            // The menu assembles itself on arrival: the four cards rise in one
            // after another while the labels and the masthead stay put.
            Ui.staggerIn(provider, 0)
            Ui.staggerIn(sections, 1)
            Ui.staggerIn(reset, 2)
            Ui.staggerIn(about, 3)
        }
    }

    /**
     * "User Interface": everything about how the app looks and feels — the
     * palette, the interface language and the haptic feedback — moved here from
     * the hub, where they stretched the menu without changing what the agent
     * does. Theme and language share one card (both answer "how does the app
     * look"); the haptic switch gets its own tile row.
     */
    private fun buildUiPage(panel: LinearLayout, entrance: Boolean) {
        panel.addView(pageMasthead(Fa.SET_UI, Fa.SET_PAGE_UI_SUB), Ui.matchWrap())

        // --- appearance -----------------------------------------------------
        //
        // TWO tracks, one card: the palette and the interface language are the
        // same question asked twice. The haptic switch used to ride this card;
        // it now gets its own tile row below, because a switch is not a
        // segmented choice and the card reads cleaner for it.
        panel.addView(Ui.sectionLabel(this, Fa.SET_APPEARANCE))
        val appearance = card()
        appearance.addView(captionedTrack(Fa.SET_THEME, themeSegment()))
        // Full-bleed, like every other rule under a block that has no glyph
        // column to run past.
        rowDivider(appearance, 0.0f)
        appearance.addView(captionedTrack(Fa.SET_LANGUAGE, languageSegment()))
        // ONE closing note for the group, so the card keeps the shape every other
        // group has: content, hairline, explanation.
        cardNote(appearance, Fa.SET_THEME_H + "\n" + Fa.SET_LANGUAGE_H)
        panel.addView(appearance)

        // --- haptics ----------------------------------------------------------
        panel.addView(Ui.sectionLabel(this, Fa.SET_HAPTIC))
        val haptic = card()
        swHaptic = Ui.iosToggleRow(
            haptic, "vibrate", Theme.tileTeal(),
            Fa.SET_HAPTIC, Fa.SET_HAPTIC_H, prefs.hapticEnabled()
        )
        panel.addView(haptic)

        if (entrance) {
            Ui.staggerIn(appearance, 0)
            Ui.staggerIn(haptic, 1)
        }
    }

    /**
     * "Tools & access": the key router, the tools the assistant uses, and the
     * network access it is granted — the three groups that used to stretch the
     * hub, now with room to breathe.
     */
    private fun buildToolsPage(panel: LinearLayout) {
        panel.addView(pageMasthead(Fa.SET_TOOLS, Fa.SET_PAGE_TOOLS_SUB), Ui.matchWrap())

        // --- key router -------------------------------------------------------
        //
        // The heading carries the counter. It used to float on its own line inside
        // the card, under the add row and above the list, belonging to neither —
        // and "how full is the router" is exactly the kind of fact a heading is
        // for.
        panel.addView(routerHeading())
        panel.addView(keyRouterSection())

        // --- tools ------------------------------------------------------------
        //
        // The web search tool and the installable agent skills: things the
        // assistant USES. Network reach is a permission, not a tool, so it
        // gets its own group below instead of sharing this card.
        panel.addView(Ui.sectionLabel(this, Fa.SET_TOOLS_GROUP))
        val tools = card()
        swWeb = toggleRow(tools, "search", Fa.SET_WEB, null, prefs.webSearch())

        // --- agent skills --------------------------------------------------
        //
        // "Add skill" is the hero action of this card: a full-width primary
        // pill with its own breathing room, then the installed-skills list
        // and the storage-folder row.
        rowDivider(tools, ROW_INSET)
        val addSkillWrap = LinearLayout(this)
        addSkillWrap.orientation = LinearLayout.VERTICAL
        addSkillWrap.setPadding(
            Theme.dp(this, Ui.Space.L), Theme.dp(this, Ui.Space.M),
            Theme.dp(this, Ui.Space.L), Theme.dp(this, Ui.Space.M)
        )
        addSkillWrap.addView(
            Ui.pillButton(this, Fa.SKILL_ADD, "wand", Ui.PRIMARY) {
                skillSheets.startAddSkill()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        tools.addView(addSkillWrap)

        val box = LinearLayout(this).also {
            it.orientation = LinearLayout.VERTICAL
        }
        skillsBox = box
        tools.addView(box)
        renderSkills()

        rowDivider(tools, ROW_INSET)
        cardNote(tools, Fa.SKILL_NOTE)
        panel.addView(tools)

        // --- access -----------------------------------------------------------
        panel.addView(Ui.sectionLabel(this, Fa.SET_ACCESS_GROUP))
        val access = card()
        swLocalNet = toggleRow(
            access, "server", Fa.SET_LOCAL_NET, Fa.SET_LOCAL_NET_H, prefs.allowLocalNetwork()
        )
        panel.addView(access)
    }

    /**
     * "Intelligence & behavior": how the assistant answers, how hard it
     * thinks, and the standing instructions it carries into every request.
     */
    private fun buildBrainPage(panel: LinearLayout) {
        panel.addView(pageMasthead(Fa.SET_BRAIN, Fa.SET_PAGE_BRAIN_SUB), Ui.matchWrap())

        // --- generation ----------------------------------------------------
        panel.addView(Ui.sectionLabel(this, Fa.SET_GENERATION))
        val generation = card()
        generation.addView(temperatureBlock())
        rowDivider(generation, ROW_INSET)
        etMaxTok = field(
            generation, "layers", Fa.SET_MAXTOK, prefs.maxTokens().toString(),
            InputType.TYPE_CLASS_NUMBER, "10000", true
        )
        rowDivider(generation, ROW_INSET)
        etContextTok = field(
            generation, "database", Fa.SET_CTXTOK, contextFieldText(),
            InputType.TYPE_CLASS_NUMBER,
            (ModelCaps.forModel(prefs.model())?.contextTokens
                ?: ModelCaps.DEFAULT_CONTEXT_TOKENS).toString(),
            true
        )
        rowDivider(generation, ROW_INSET)
        etTimeout = field(
            generation, "gauge", Fa.SET_TIMEOUT, prefs.timeoutSeconds().toString(),
            InputType.TYPE_CLASS_NUMBER, Prefs.DEFAULT_TIMEOUT_SECONDS.toString(), true
        )
        cardNote(generation, Fa.SET_TIMEOUT_H)
        cardNote(generation, Fa.SET_CTXTOK_H)
        panel.addView(generation)

        // --- reasoning -------------------------------------------------------
        //
        // Its own group, because the dial and the workflow toggle are one control
        // in two parts: turning Dynamic Workflow on RAISES the effective effort to
        // xhigh and locks the dial. Sitting eight rows apart inside a single
        // "Behavior" card, that relationship was invisible and the lock read as a
        // bug.
        panel.addView(Ui.sectionLabel(this, Fa.SET_REASONING))
        val reasoning = card()
        selLevel = prefs.thinkingLevel()
        reasoning.addView(thinkLevelSlider())
        rowDivider(reasoning, ROW_INSET)
        swWorkflow = toggleRow(
            reasoning, "zap", Fa.SET_WORKFLOW, Fa.SET_WORKFLOW_H, prefs.dynamicWorkflow()
        )
        panel.addView(reasoning)

        // --- background work ---------------------------------------------------
        //
        // A foreground service + wake lock keeps a run alive, but on most
        // phones the system still throttles the app unless the user exempts it
        // from battery optimization. One row that opens the exact system page
        // — and reports the live state when the user comes back — removes the
        // whole "the app died in the background" support class.
        panel.addView(Ui.sectionLabel(this, Fa.SET_BACKGROUND))
        val background = card()
        batteryRow = batteryExemptionRow(background)
        cardNote(background, Fa.SET_BATTERY_H)
        panel.addView(background)

        // --- custom instructions ----------------------------------------------
        panel.addView(Ui.sectionLabel(this, Fa.SET_CUSTOM))
        val custom = card()
        val promptBlock = cardBlock(custom)
        val systemPrompt = EditText(this)
        systemPrompt.typeface = Theme.ui()
        etSys = systemPrompt
        systemPrompt.setText(prefs.systemPrompt())
        systemPrompt.hint = Fa.SET_CUSTOM_HINT
        systemPrompt.setHintTextColor(Theme.TEXT_FAINT)
        systemPrompt.setTextColor(Theme.TEXT)
        systemPrompt.textSize = Ui.Type.LABEL
        systemPrompt.background = fieldBg(false)
        systemPrompt.setOnFocusChangeListener { _, hasFocus ->
            systemPrompt.background = fieldBg(hasFocus)
        }
        val promptPad = Theme.dp(this, Ui.Space.M)
        systemPrompt.setPadding(promptPad, promptPad, promptPad, promptPad)
        systemPrompt.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        // PROSE, in whichever language the user writes it — so it takes its
        // direction from its own first strong character rather than from the
        // interface. A Persian instruction reads right-to-left inside an English
        // interface and an English one reads left-to-right inside a Persian one.
        systemPrompt.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        // START, not a bare TOP. TOP alone leaves the horizontal gravity bits
        // empty; TextView reads that as "no opinion" and happens to land on the
        // same ALIGN_NORMAL, but it states nothing about which edge it means, and
        // an unstated edge is the one that gets "fixed" to a physical LEFT later.
        systemPrompt.gravity = Gravity.TOP or Gravity.START
        systemPrompt.minLines = 4
        systemPrompt.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        promptBlock.addView(systemPrompt, Ui.matchWrap())
        cardNote(custom, Fa.SET_CUSTOM_H)
        panel.addView(custom)
    }

    /**
     * A sub-page's top: the same silhouette as [masthead] — back affordance,
     * display-size title, muted line, hairline — except the back chevron
     * returns to the hub instead of finishing the Activity.
     */
    private fun pageMasthead(title: String, subtitle: String): LinearLayout {
        val head = Ui.column(this)
        head.setPaddingRelative(0, Theme.dp(this, Ui.Space.S), 0, 0)

        val back = Ui.row(this)
        val backButton = Ui.circleButton(
            this,
            // BACK, so it points at the edge it returns to: chevron-left in
            // English, chevron-right in Persian — the same rule as the hub's
            // own back button.
            Lang.chevronBack(this),
            40.0f,
            20.0f,
            Theme.TEXT,
            0
        ) { showPage(PAGE_HUB) }
        backButton.contentDescription = Lang.text(this, "Back", "بازگشت")
        back.addView(backButton)
        // The button's own optical inset is cancelled so the glyph, the title
        // below it and the section labels below THAT all start on one line —
        // the same arithmetic as [masthead].
        val backLp = Ui.wrapWrap()
        backLp.marginStart = Theme.dp(this, Ui.Space.XS) - Theme.dp(this, 10.0f)
        head.addView(back, backLp)

        val titleView = Ui.text(this, title, Ui.Type.TITLE, Theme.TEXT, Theme.uiBold())
        titleView.setSingleLine(true)
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        // Slightly tight: a display-size line in a bold face sets loose by default
        // and reads as spaced-out rather than as a title.
        titleView.letterSpacing = tracking(-0.01f)
        Ui.rowLabel(titleView)
        val titleLp = Ui.matchWrap()
        titleLp.topMargin = Theme.dp(this, Ui.Space.S)
        titleLp.marginStart = Theme.dp(this, Ui.Space.XS)
        head.addView(titleView, titleLp)

        val caption = Ui.text(this, subtitle, Ui.Type.META, Theme.TEXT_MUTED, Theme.ui())
        caption.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        Ui.rowLabel(caption)
        val captionLp = Ui.matchWrap()
        captionLp.topMargin = Theme.dp(this, 3.0f)
        captionLp.marginStart = Theme.dp(this, Ui.Space.XS)
        head.addView(caption, captionLp)

        val rule = Ui.divider(this)
        val ruleLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Theme.hairline(this)
        )
        ruleLp.topMargin = Theme.dp(this, Ui.Space.XL)
        head.addView(rule, ruleLp)
        return head
    }

    // =====================================================================
    // Key Router
    // =====================================================================

    /**
     * The key-router group. Its content is not row-shaped — an input paired with
     * a button, a counter, and a variable-length list — so it lives in a single
     * free-form [cardBlock] inside the group card. The long explanation moved out
     * to a footnote under the card.
     */
    private fun keyRouterSection(): LinearLayout {
        val card = card()
        val block = cardBlock(card)

        // Add row: masked input (same chrome as the primary key field) + button.
        val addRow = Ui.row(this)

        val box = Ui.row(this)
        // An LTR island, like a code block: an API key is always Latin, so the
        // whole field — the text AND the reveal button — is laid out as one
        // left-to-right unit rather than inheriting the row's direction.
        box.layoutDirection = View.LAYOUT_DIRECTION_LTR
        box.background = fieldBg(false)
        // setPadding, not setPaddingRelative, and that is correct HERE and only
        // here: the island has just pinned itself to LTR, so its physical left IS
        // its start. The 8/4 split leans the text away from the reveal button,
        // which must stay on the same side of the key in both languages.
        box.setPadding(Theme.dp(this, Ui.Space.S), 0, Theme.dp(this, Ui.Space.XS), 0)
        // The Add button beside it is the app's standard 48dp pill, and a 40dp
        // field next to a 48dp button was the one mismatched pair on the screen.
        // Matching the HEIGHT rather than the padding leaves the input's own
        // metrics — shared with the two key fields above — untouched.
        box.minimumHeight = Theme.dp(this, 48.0f)

        val input = EditText(this)
        input.typeface = Theme.ui()
        etNewKey = input
        input.hint = "sk-…"
        input.setHintTextColor(Theme.TEXT_FAINT)
        input.setTextColor(Theme.TEXT)
        input.textSize = Ui.Type.LABEL
        input.setSingleLine(true)
        input.background = null
        input.textDirection = View.TEXT_DIRECTION_LTR
        input.layoutDirection = View.LAYOUT_DIRECTION_LTR
        input.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.setOnFocusChangeListener { _, hasFocus ->
            box.background = fieldBg(hasFocus)
        }
        // 10dp, the same as field() and the primary key field: three inputs on one
        // screen used 8dp, 10dp and 11dp and therefore three different heights.
        val inputPadV = Theme.dp(this, 10.0f)
        input.setPadding(Theme.dp(this, Ui.Space.XS), inputPadV, 0, inputPadV)
        box.addView(input, Ui.grow())
        box.addView(revealButton(input))
        addRow.addView(box, Ui.grow())

        val addLp = Ui.wrapWrap()
        addLp.marginStart = Theme.dp(this, Ui.Space.S)
        addRow.addView(
            Ui.pillButton(this, Fa.SET_KEY_ADD, "plus", Ui.PRIMARY) { addRouterKey() }, addLp
        )
        block.addView(addRow, Ui.matchWrap())

        val rows = Ui.column(this)
        keysBox = rows
        val rowsLp = Ui.matchWrap()
        rowsLp.topMargin = Theme.dp(this, Ui.Space.S)
        block.addView(rows, rowsLp)
        refreshKeyRows()
        cardNote(card, Fa.SET_KEY_ROUTER_H)
        return card
    }

    /**
     * The key-router heading, with the `n/50` counter on its trailing edge.
     *
     * [Ui.sectionLabel] returns the heading ROW, so a trailing readout can be
     * appended to it — the label itself stays `getChildAt(0)`, which is the
     * contract that helper documents.
     */
    private fun routerHeading(): LinearLayout {
        val heading = Ui.sectionLabel(this, Fa.SET_KEY_ROUTER)
        val spacer = View(this)
        heading.addView(spacer, Ui.grow())
        // A count, so: numeric chip. The greedy spacer between the label and the
        // chip is what puts the counter on the heading's trailing edge — the left
        // in Persian — without either side needing to know which edge that is.
        val count = monoReadout(true)
        tvKeyCount = count
        heading.addView(count, Ui.wrapWrap())
        return heading
    }

    private fun addRouterKey() {
        val key = etNewKey?.text?.toString()?.trimJava() ?: ""
        if (key.isEmpty()) {
            return
        }
        val current = prefs.apiKeys()
        if (current.size >= Prefs.MAX_ROUTER_KEYS) {
            say(Fa.SET_KEY_FULL, false)
            return
        }
        if (current.contains(key)) {
            say(Fa.SET_KEY_DUP, false)
            return
        }
        if (!prefs.addApiKey(key)) {
            say(Fa.SET_KEYSTORE_UNAVAILABLE, true)
            return
        }
        etNewKey?.setText("")
        refreshKeyRows()
    }

    private fun refreshKeyRows() {
        val container = keysBox ?: return
        container.removeAllViews()
        val keys = prefs.apiKeys()
        // "۳/۵۰" in Persian. A count the interface is REPORTING is prose in the
        // interface's numerals, unlike the key strings themselves, which are
        // Latin secrets and stay exactly as typed.
        tvKeyCount?.text =
            Lang.num(this, keys.size) + "/" + Lang.num(this, Prefs.MAX_ROUTER_KEYS)

        if (keys.isEmpty()) {
            // No sunken panel: the group card is already SURFACE_2, so a panel in
            // the same token drew an invisible box around this line.
            val empty = Ui.text(this, Fa.SET_KEY_EMPTY, Ui.Type.META, Theme.TEXT_MUTED, Theme.ui())
            empty.gravity = Gravity.CENTER
            val emptyPad = Theme.dp(this, Ui.Space.M)
            empty.setPadding(0, emptyPad, 0, emptyPad)
            container.addView(empty, Ui.matchWrap())
            return
        }

        for (i in keys.indices) {
            val index = i
            // The FULL key, not the masked rendering: the row shows a mask for
            // shoulder-surfing safety, but this is the one place the user can
            // get their own secret back out of the store.
            val key = keys[index]
            val row = Ui.row(this)
            // The SAME ground as every field on this screen — a SURFACE fill with
            // a BORDER_HI hairline — rather than a borderless fill. The stored
            // keys sit directly under the input that adds them, and a bare tile
            // next to an outlined one made two adjacent objects of the same size
            // and colour look like a rendering slip.
            row.background = fieldBg(false)
            row.setPaddingRelative(
                Theme.dp(this, 10.0f), Theme.dp(this, Ui.Space.XS),
                Theme.dp(this, Ui.Space.XS), Theme.dp(this, Ui.Space.XS)
            )

            // The router tries keys in order, so the position is the one fact
            // about a key that matters; the glyph marks the row as a key.
            val glyph = ImageView(this)
            glyph.setImageDrawable(Icons.of("key", Theme.TEXT_MUTED, Ui.STROKE))
            glyph.scaleType = ImageView.ScaleType.FIT_CENTER
            glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val glyphSize = Theme.dp(this, 18.0f)
            val glyphLp = LinearLayout.LayoutParams(glyphSize, glyphSize)
            glyphLp.marginEnd = Theme.dp(this, Ui.Space.M)
            row.addView(glyph, glyphLp)

            val tv = Ui.text(this, maskKey(keys[i]), Ui.Type.META, Theme.TEXT, Theme.mono())
            tv.textDirection = View.TEXT_DIRECTION_LTR
            // Forcing the TEXT direction resolves the view to LTR, which sends the
            // glyphs to the far edge of a weighted slot; pinning the ALIGNMENT
            // keeps the masked key beside the glyph that labels it.
            tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            tv.setSingleLine(true)
            tv.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            row.addView(tv, Ui.grow())

            row.addView(
                Ui.iconButton(this, "copy", 18.0f, Theme.TEXT_MUTED) {
                    if (MarkdownRenderer.copyText(this, Fa.SET_API_KEY, key)) {
                        say(Fa.COPIED, false)
                    }
                }
            )
            row.addView(
                Ui.iconButton(this, "trash", 18.0f, Theme.TEXT_MUTED) {
                    prefs.removeApiKey(index)
                    refreshKeyRows()
                }
            )
            val rowLp = Ui.matchWrap()
            rowLp.topMargin = Theme.dp(this, 6.0f)
            container.addView(row, rowLp)
        }
    }

    // =====================================================================
    // Reset
    // =====================================================================

    /**
     * The reset group — a single row, not a card wrapped around a red button.
     * [Theme.RED] is near-black ink in this palette, so a "danger" pill would
     * have read as an ordinary primary button; the row's LABEL is the warning,
     * and the confirmation sheet is where the destructive step actually happens.
     */
    private fun resetSection(): LinearLayout {
        val card = card()
        card.addView(
            Ui.cardRow(this, "refresh", Fa.SET_RESET, Fa.SET_RESET_H, chevron()) {
                confirmReset()
            }
        )
        return card
    }

    /**
     * Rebuilds the skills block inside Tools & access: a collapsible header
     * ("اسکیل‌ها" + count + chevron), the installed-skill rows (or the
     * empty-state line) inside the collapsible list, and the storage-folder row
     * which stays visible — it says WHERE skills live, not which are installed.
     * Called after the card is built and after every add/delete/folder change.
     *
     * B10: the disk scan (listFiles + one SKILL.md parse per skill) runs on a
     * background thread; only the header count and the row views are posted
     * back to the UI thread. [skillsScanGen] drops stale scans when renders
     * overlap.
     */
    private fun renderSkills() {
        val box = skillsBox ?: return
        box.removeAllViews()

        // The collapsible header. The whole row is the toggle — a bigger target
        // than the chevron alone — and the count chip names how many rows the
        // list holds before it is opened. The count fills in when the scan lands.
        val count = monoReadout(true)
        count.text = "…"
        val chev = ImageView(this)
        chev.setImageDrawable(Icons.of("chevron-down", Theme.TEXT_MUTED, Ui.STROKE))
        chev.scaleType = ImageView.ScaleType.FIT_CENTER
        chev.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val chevSize = Theme.dp(this, 18.0f)
        chev.layoutParams = LinearLayout.LayoutParams(chevSize, chevSize)
        skillsToggleChevron = chev
        val trailing = Ui.row(this)
        val countLp = Ui.wrapWrap()
        countLp.marginEnd = Theme.dp(this, Ui.Space.S)
        trailing.addView(count, countLp)
        trailing.addView(chev, Ui.wrapWrap())
        box.addView(
            Ui.cardRow(
                this, "wand", Fa.SKILL_LIST, null, trailing,
                Runnable { toggleSkillsExpanded() }
            )
        )

        // The collapsible list itself — populated when the scan lands.
        val list = Ui.column(this)
        skillsListBox = list
        box.addView(list, Ui.matchWrap())
        applySkillsExpanded()

        // B8: the picked folder may be gone (SD card pulled, folder deleted
        // elsewhere). Show the dead path with a "missing" hint instead of
        // pretending all is well; install() recreates it lazily via mkdirs.
        val folder = prefs.skillsFolder()
        val folderSubtitle = when {
            folder.isBlankJava() -> Fa.SKILL_FOLDER_NONE
            File(folder).isDirectory -> folder
            else -> folder + "\n" + Lang.text(
                this,
                "⚠ folder missing — tap to pick a new one",
                "⚠ پوشه پیدا نشد — برای انتخاب پوشه جدید بزنید"
            )
        }
        box.addView(
            Ui.cardRow(
                this, "folder", Fa.SKILL_FOLDER,
                folderSubtitle,
                chevron(),
                Runnable { skillSheets.changeFolder() }
            )
        )

        val gen = ++skillsScanGen
        Thread({
            val installed = Skills.list(this, prefs)
            ui.post {
                if (gen != skillsScanGen || isFinishing || isDestroyed) {
                    return@post
                }
                count.text = Lang.num(this, installed.size)
                list.removeAllViews()
                if (installed.isEmpty()) {
                    val empty = TextView(this)
                    empty.typeface = Theme.ui()
                    empty.text = Fa.SKILL_NONE
                    empty.setTextColor(Theme.TEXT_FAINT)
                    empty.textSize = Ui.Type.META
                    empty.gravity = Gravity.CENTER
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = Theme.dp(this, Ui.Space.M)
                    lp.bottomMargin = Theme.dp(this, Ui.Space.M)
                    list.addView(empty, lp)
                } else {
                    for (info in installed) {
                        list.addView(skillSheets.skillRow(info))
                    }
                }
            }
        }, "vega-skills-scan").apply { isDaemon = true }.start()
    }

    /** Flips the installed-skills list open/closed and updates the header. */
    private fun toggleSkillsExpanded() {
        skillsExpanded = !skillsExpanded
        prefs.setSkillsExpanded(skillsExpanded)
        applySkillsExpanded()
    }

    /** Applies [skillsExpanded] to the list container and the header chevron. */
    private fun applySkillsExpanded() {
        skillsListBox?.visibility = if (skillsExpanded) View.VISIBLE else View.GONE
        // No "chevron-up" glyph in the set; a half-turn of chevron-down is the
        // same mark, and it stays direction-neutral in both languages.
        // Open list points up, closed list points down.
        skillsToggleChevron?.rotation = if (skillsExpanded) 180f else 0f
    }

    private fun confirmReset() {
        val sheet = Sheet(this)
        sheet.header("refresh", Fa.SET_RESET, null)

        val msg = TextView(this)
        msg.typeface = Theme.ui()
        msg.text = Fa.SET_RESET_MSG
        msg.setTextColor(Theme.TEXT_MUTED)
        msg.textSize = Ui.Type.LABEL
        msg.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        // A raw TextView, so it starts with none of the treatment Ui.text's
        // callers get: FIRST_STRONG plus a start-edge alignment is what makes the
        // warning read from the right in Persian and from the left in English.
        Ui.rowLabel(msg)
        val msgLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        msgLp.bottomMargin = Theme.dp(this, 18.0f)
        sheet.body.addView(msg, msgLp)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        // Said here rather than left to inherit. A Sheet is a Dialog in its own
        // window and takes NOTHING from this Activity's tree — [Sheet] mirrors
        // its own panel and body, and the moment a caller builds a container of
        // its own inside that body, the caller owns the question again. Mirrored,
        // this puts Cancel on the right and the destructive button on the left,
        // which is the mirror of where they sit in English.
        row.layoutDirection = Lang.direction(this)
        val cancel = Ui.pillButton(this, Fa.CANCEL, null, Ui.SECONDARY) { sheet.dismiss() }
        val cancelLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        cancelLp.marginEnd = Theme.dp(this, 8.0f)
        row.addView(cancel, cancelLp)
        row.addView(
            Ui.pillButton(this, Fa.SET_RESET, "refresh", Ui.DANGER) {
                resetting = true
                ui.removeCallbacks(applyTextSettings)
                prefs.clearAll()
                lastSavedKey = ""
                say(Fa.SET_RESET_DONE, false)
                sheet.dismiss()
                recreate()
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

    // =====================================================================
    // Building blocks
    // =====================================================================

    /**
     * A settings group: the shared [Ui.groupedCard] — a flat [Theme.SURFACE_2]
     * card at [Theme.R_CARD] with no border, no elevation and NO padding of its
     * own, so the rows inside run full-bleed and paint their own ripple to the
     * card's edges.
     *
     * The old version was a [Theme.glassCard] with a hairline border, a 2dp
     * elevation and 16dp of padding all round; all three are gone with the
     * grouped-card look.
     */
    private fun card(): LinearLayout = Ui.groupedCard(this)

    /**
     * A free-form padded block inside a group card, for the handful of controls
     * that are not row-shaped (the two sliders, the prompt box, the key list) and
     * so cannot use [Ui.cardRow]'s geometry. Its insets match a row's, so a block
     * and a row line up down the card's start edge.
     *
     * [parent] may be null when the caller wants to add the block itself (see
     * [thinkLevelSlider], which returns one).
     */
    private fun cardBlock(parent: LinearLayout?): LinearLayout {
        val block = Ui.column(this)
        block.setPaddingRelative(
            Theme.dp(this, Ui.Space.L), Theme.dp(this, Ui.Space.M),
            Theme.dp(this, Ui.Space.L), Theme.dp(this, Ui.Space.M)
        )
        block.layoutParams = Ui.matchWrap()
        if (parent != null) {
            parent.addView(block)
        }
        return block
    }

    /**
     * A group's explanation, as the LAST block inside its own card.
     *
     * These used to float on the page ground below the card, which is the
     * reference screens' habit and the wrong one here. It made the rhythm lumpy —
     * card, small gap, prose, big gap, heading, card — and it detached the prose
     * from the thing it describes, so a paragraph between two cards read as
     * belonging to neither. Under a hairline, on the card's own ground, it is
     * unambiguously part of the group and the page returns to a single beat:
     * heading, card, heading, card.
     *
     * Returned so a caller can dim it, hide it, or hold on to it — the API-key
     * warning is a note that only appears when the key is stored unprotected.
     */
    private fun cardNote(parent: LinearLayout, value: String): LinearLayout =
        cardNote(parent, value, Theme.TEXT_MUTED)

    /**
     * As [cardNote], in a specific ink — the API-key warning is the one note on
     * this screen that is not neutral.
     *
     * The hairline and the paragraph are returned as ONE block on purpose: the
     * warning is hidden whenever the key is properly protected, and hiding only
     * the text would leave its rule stranded at the bottom of the card.
     */
    private fun cardNote(parent: LinearLayout, value: String, ink: Int): LinearLayout {
        val block = Ui.column(this)
        rowDivider(block, 0.0f)
        // TEXT_MUTED, not TEXT_FAINT, for the neutral case. A note is the sentence
        // that explains what a setting DOES — the text most likely to be read
        // carefully by someone who is unsure — and it used to be set in the palette's
        // faintest ink, one step above the disabled tone.
        val view = Ui.text(this, value, Ui.Type.META, ink, Theme.ui())
        view.setLineSpacing(Theme.dpf(this, 4.0f), 1.0f)
        Ui.rowLabel(view)
        view.setPaddingRelative(
            Theme.dp(this, Ui.Space.L), Theme.dp(this, 14.0f),
            Theme.dp(this, Ui.Space.L), Theme.dp(this, 14.0f)
        )
        block.addView(view, Ui.matchWrap())
        parent.addView(block, Ui.matchWrap())
        return block
    }

    /**
     * A hairline between two rows of a card, inset by [insetDp] from the start
     * edge.
     *
     * [Ui.divider]'s own documentation says a divider inside a grouped card is
     * almost always the wrong answer, and until now this screen used none at all.
     * That is right for a card holding two or three unlike things, and wrong for
     * one holding eight identically-shaped rows: with nothing between them a
     * dense card reads as a single slab of text, and the eye has to use the
     * switches on the trailing edge to work out where one row stops.
     *
     * [ROW_INSET] parts the labels while the glyph column runs on unbroken, which
     * is what makes a list read as a list. Blocks that have no glyph — a note, a
     * meter, a segmented track — pass 0 and get a full-bleed rule instead, so the
     * hairline always starts where the content above it starts.
     */
    private fun rowDivider(parent: LinearLayout, insetDp: Float) {
        val line = Ui.divider(this)
        // Repainted to BORDER_HI, because [Ui.divider]'s own [Theme.BORDER] is
        // invisible HERE specifically.
        //
        // A card is SURFACE_2, and the two tokens are three levels apart on the
        // light palette (0xEDEDED on 0xF0F0F0) and two on the dark one (0x262626
        // on 0x242424) — a rule nobody can see, which would have looked exactly
        // like the "no separation at all" this replaces. BORDER_HI is twenty
        // levels clear of the card in BOTH palettes, so the line reads the same
        // either way. The masthead's rule keeps the default: it sits on the page
        // ground, where BORDER has contrast to spare.
        line.setBackgroundColor(Theme.BORDER_HI)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Theme.hairline(this)
        )
        params.marginStart = Theme.dp(this, insetDp)
        parent.addView(line, params)
    }

    /**
     * The head of a free-form block: the glyph in the same slot a [Ui.cardRow]
     * gives it, the label at body size, and a trailing readout.
     *
     * Sharing this is what keeps a meter aligned with the rows above and below
     * it. Both dials used to draw their own head, and neither drew the glyph at
     * all — so the two blocks in the middle of a card full of rows were the only
     * things on the screen whose labels started 36dp further in.
     */
    private fun blockHead(icon: String, label: String, trailing: View): LinearLayout {
        val head = Ui.row(this)

        val glyph = ImageView(this)
        glyph.setImageDrawable(Icons.of(icon, Theme.TEXT_MUTED, Ui.STROKE))
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(this, Ui.Space.XL)
        val glyphLp = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphLp.marginEnd = Theme.dp(this, Ui.Space.L)
        head.addView(glyph, glyphLp)

        val title = Ui.text(this, label, Ui.Type.BODY, Theme.TEXT, Theme.ui())
        Ui.rowLabel(title)
        head.addView(title, Ui.grow())

        val trailingLp = Ui.wrapWrap()
        trailingLp.marginStart = Theme.dp(this, Ui.Space.S)
        head.addView(trailing, trailingLp)
        return head
    }

    /**
     * The value chip a meter carries on its trailing edge.
     *
     * [numeric] separates the two things this chip is asked to hold, because
     * they want opposite treatment and getting either wrong is visible:
     *
     *  * **A number** (`0.70`, `3/50`) reads left-to-right in Persian exactly as
     *    it does in English — Persian digits carry the EN bidi class — so it
     *    keeps `TEXT_DIRECTION_LTR` and comes out in the written order. It gives
     *    up the mono face in Persian though: JetBrains Mono has no ۰-۹ at all, so
     *    asking for it would only guarantee a silent per-glyph fallback to a face
     *    nobody chose, at metrics that no longer line up. The chip's identity is
     *    its ground, stroke and radius, not its typeface.
     *  * **A word** (the reasoning level's name) must follow the INTERFACE. Under
     *    a forced LTR paragraph the two words of "خیلی زیاد" come out in the
     *    wrong order — which is exactly what this chip did before, because one
     *    helper served both cases.
     */
    private fun monoReadout(numeric: Boolean): TextView {
        val face = if (numeric && Lang.farsi(this)) Theme.ui() else Theme.mono()
        val view = Ui.text(this, "", Ui.Type.META, Theme.TEXT, face)
        view.textDirection = if (numeric) {
            View.TEXT_DIRECTION_LTR
        } else {
            Lang.textDirection(this)
        }
        view.setSingleLine(true)
        view.background = Theme.roundStroke(
            Theme.SURFACE, Theme.BORDER_HI, Theme.R_PILL, 1, this
        )
        val padH = Theme.dp(this, 10.0f)
        val padV = Theme.dp(this, 3.0f)
        view.setPaddingRelative(padH, padV, padH, padV)
        return view
    }

    /**
     * The one rail treatment on this screen.
     *
     * A default Android SeekBar draws a 2dp track with a 20dp thumb that carries
     * the platform's own 48dp ripple halo — which on a monochrome card reads as a
     * smudge following your finger. The thumb here is an explicit
     * [Theme.circle] sized to 16dp: deliberate, flat, the same ink as the filled
     * part of the rail, and the same on both dials.
     */
    private fun styleSeek(seek: SeekBar) {
        seek.splitTrack = false
        // BOTH dials run low -> high from the layout's START edge, so in Persian
        // they fill from the right.
        //
        // This is a reversal, and it is deliberate. The rail used to be pinned to
        // LTR on the reasoning that "a dial is an amount, not text". An amount is
        // still laid out along an AXIS, and the axis a reader scans is the axis
        // their language runs on: a Persian speaker reads the start of the range
        // where they start every other line on this screen, which is the right.
        // Pinned LTR, the "Precise" end of Temperature sat under the far edge and
        // dragging toward the reading edge made the number go DOWN — a mirrored
        // screen with one control still thinking in English.
        //
        // Both halves of the platform's behaviour move together here, so there is
        // no half-mirrored state to fall into: `AbsSeekBar` consults one flag
        // (`mirrorForRtl`, set by the Material seekbar style) for the fill it
        // draws AND for the value it computes from a touch. When the flag is off
        // the bar simply behaves as it does today.
        seek.layoutDirection = Lang.direction(this)
        seek.progressTintList = ColorStateList.valueOf(Theme.ACCENT)
        seek.progressBackgroundTintList = ColorStateList.valueOf(Theme.BORDER_HI)
        seek.thumbTintList = ColorStateList.valueOf(Theme.ACCENT)
        val thumbSize = Theme.dp(this, 16.0f)
        val thumb = Theme.circle(Theme.ACCENT)
        thumb.setSize(thumbSize, thumbSize)
        seek.thumb = thumb
        // An honest touch strip: the rail itself is 2dp, and a 2dp target is not
        // a control anyone can grab.
        seek.minimumHeight = Theme.dp(this, 32.0f)
    }

    /**
     * The two [Ui.Type.MICRO] labels naming the ends of a meter's range.
     *
     * The captions take the rail's direction, whatever it is — [startText] has to
     * land under the rail's ZERO end, and since [styleSeek] now mirrors the rail
     * with the interface, that end is the layout's start edge in both languages.
     * Asking `Lang.direction` in both places is what keeps them from disagreeing;
     * a caption row pinned to LTR under a mirrored rail would label "Precise" as
     * the maximum.
     */
    private fun meterCaptions(startText: String, endText: String): LinearLayout {
        val row = Ui.row(this)
        row.layoutDirection = Lang.direction(this)
        val low = Ui.text(this, startText, Ui.Type.MICRO, Theme.TEXT_FAINT, Theme.uiMedium())
        low.setSingleLine(true)
        row.addView(low, Ui.wrapWrap())
        val spacer = View(this)
        row.addView(spacer, Ui.grow())
        val high = Ui.text(this, endText, Ui.Type.MICRO, Theme.TEXT_FAINT, Theme.uiMedium())
        high.setSingleLine(true)
        row.addView(high, Ui.wrapWrap())
        return row
    }

    /**
     * Input ground inside a group card.
     *
     * [Theme.inputBg] fills with [Theme.SURFACE_2] — the card's own token — so on
     * this screen it drew an invisible box. A field steps to [Theme.SURFACE]
     * instead: lighter than the card on the light palette, darker on the dark
     * one, and clearly a field in both.
     *
     * The stroke WIDTH never changes between the two states, only its colour: a
     * GradientDrawable stroke is inset geometry, so a fatter focused ring would
     * reflow the text box every time focus moved.
     */
    private fun fieldBg(focused: Boolean): GradientDrawable = Theme.roundStroke(
        Theme.SURFACE, if (focused) Theme.ACCENT else Theme.BORDER_HI, Theme.R_SM, 1, this
    )

    /**
     * The trailing "this opens something" chevron, pointing away from the
     * layout's start edge.
     */
    private fun chevron(): ImageView {
        val view = ImageView(this)
        view.setImageDrawable(
            Icons.of(
                Lang.chevronForward(this),
                // TEXT_MUTED, not TEXT_FAINT. Measured on the light palette, FAINT is
                // 3.24:1 against SURFACE_2 — under the 4.5 floor, and this is the mark
                // that tells you a row opens something. MUTED is 4.82:1 and still
                // clearly secondary to the label it sits beside.
                Theme.TEXT_MUTED,
                Ui.STROKE
            )
        )
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val size = Theme.dp(this, 18.0f)
        view.layoutParams = LinearLayout.LayoutParams(size, size)
        return view
    }

    /**
     * A labelled input as a card row: the glyph and the label on the start edge,
     * the EditText itself as the row's TRAILING control.
     *
     * The label used to be a separate 12sp letter-spaced line above a full-width
     * box; as a row it matches every other row in the group and halves the
     * vertical space the provider card takes. The input keeps a weight of 1.5
     * against the label's 1.0, because a base URL needs the room and a label
     * does not.
     */
    private fun field(
        parent: LinearLayout,
        icon: String,
        label: String,
        value: String,
        inputType: Int,
        hint: String,
        ltr: Boolean
    ): EditText {
        val editText = EditText(this)
        editText.typeface = Theme.ui()
        editText.setText(value)
        editText.hint = hint
        editText.setHintTextColor(Theme.TEXT_FAINT)
        editText.setTextColor(Theme.TEXT)
        editText.textSize = Ui.Type.LABEL
        editText.setSingleLine(true)
        editText.inputType = inputType
        if (ltr) {
            editText.textDirection = View.TEXT_DIRECTION_LTR
            editText.layoutDirection = View.LAYOUT_DIRECTION_LTR
            editText.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        editText.background = fieldBg(false)
        editText.setOnFocusChangeListener { _, hasFocus ->
            editText.background = fieldBg(hasFocus)
        }
        // Space.M/Space.S, both on the scale, and taller than the 8dp it had.
        //
        // A LABEL-size input with 8dp of vertical padding, inside a row that has its
        // own 12dp, was the most cramped thing on the screen — and it sat next to the
        // key field, which used 11dp, so two adjacent inputs were different heights
        // for no reason anyone chose.
        val padH = Theme.dp(this, Ui.Space.M)
        val padV = Theme.dp(this, 10.0f)
        editText.setPadding(padH, padV, padH, padV)
        editText.minimumHeight = Theme.dp(this, 40.0f)
        editText.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f
        )
        parent.addView(Ui.cardRow(this, icon, label, null, editText, null))
        return editText
    }

    /**
     * The primary API key: the same card row as [field], except the trailing
     * control is an LTR island holding the masked input AND its reveal button.
     */
    private fun addKeyField(parent: LinearLayout) {
        val box = Ui.row(this)
        // An LTR island, like a code block: an API key is always Latin, so the
        // whole field — the text AND the reveal button — is laid out as one
        // left-to-right unit rather than inheriting the row's direction.
        box.layoutDirection = View.LAYOUT_DIRECTION_LTR
        box.background = fieldBg(false)
        // setPadding, not setPaddingRelative, and that is correct HERE and only
        // here: the island has just pinned itself to LTR, so its physical left IS
        // its start. The 8/4 split leans the text away from the reveal button,
        // which must stay on the same side of the key in both languages.
        box.setPadding(Theme.dp(this, Ui.Space.S), 0, Theme.dp(this, Ui.Space.XS), 0)

        val keyField = EditText(this)
        keyField.typeface = Theme.ui()
        etKey = keyField
        keyField.setText(prefs.apiKey())
        keyField.hint = "sk-…"
        keyField.setHintTextColor(Theme.TEXT_FAINT)
        keyField.setTextColor(Theme.TEXT)
        keyField.textSize = Ui.Type.LABEL
        keyField.setSingleLine(true)
        keyField.background = null
        keyField.textDirection = View.TEXT_DIRECTION_LTR
        keyField.layoutDirection = View.LAYOUT_DIRECTION_LTR
        keyField.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyField.setOnFocusChangeListener { _, hasFocus ->
            box.background = fieldBg(hasFocus)
        }
        val keyPadV = Theme.dp(this, 10.0f)
        // Nudge the text/hint a touch away from the eye icon so "sk-…" no longer
        // reads as if it's tucked under the eye.
        keyField.setPadding(Theme.dp(this, Ui.Space.XS), keyPadV, 0, keyPadV)
        box.addView(keyField, Ui.grow())
        box.addView(revealButton(keyField))

        box.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f
        )
        parent.addView(Ui.cardRow(this, "key", Fa.SET_API_KEY, null, box, null))
    }

    /**
     * The show/hide toggle at the end of an API-key field. Both key inputs (the
     * primary one and the router's) used to carry their own copy of this block,
     * and they had already drifted apart once.
     */
    private fun revealButton(target: EditText): ImageView {
        val eye = ImageView(this)
        var revealed = false
        eye.setImageDrawable(Icons.of("eye", Theme.TEXT_MUTED, Ui.STROKE))
        // The button sits INSIDE an LTR island, but what a screen reader says
        // about it is prose and follows the interface, not the island.
        eye.contentDescription = Lang.text(this, "Show or hide key", "نمایش یا پنهان کردن کلید")
        eye.scaleType = ImageView.ScaleType.FIT_CENTER
        // 36dp touch target with an 18dp glyph — was a bare 20dp icon.
        val eyeBox = Theme.dp(this, 36.0f)
        val eyePad = Theme.dp(this, 9.0f)
        eye.setPadding(eyePad, eyePad, eyePad, eyePad)
        eye.background = Theme.rippleTransparent(Theme.R_PILL, this)
        eye.layoutParams = LinearLayout.LayoutParams(eyeBox, eyeBox)
        eye.setOnClickListener {
            revealed = !revealed
            val caret = target.selectionEnd
            target.inputType = (
                if (revealed) {
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                ) or InputType.TYPE_CLASS_TEXT
            target.textDirection = View.TEXT_DIRECTION_LTR
            target.layoutDirection = View.LAYOUT_DIRECTION_LTR
            try {
                target.setSelection(caret)
            } catch (e: Exception) {
            }
            eye.setImageDrawable(
                Icons.of(if (revealed) "eye-off" else "eye", Theme.TEXT_MUTED, Ui.STROKE)
            )
        }
        return eye
    }

    /**
     * Switch row: a [Ui.cardRow] whose trailing control is the [Switch]. The
     * whole row is the touch target — a label next to a switch invites a tap on
     * the words, which used to do nothing.
     */
    private fun toggleRow(
        parent: LinearLayout,
        icon: String,
        label: String,
        subtitle: String?,
        checked: Boolean
    ): Switch {
        val toggle = Switch(this)
        toggle.isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        toggle.thumbTintList = ColorStateList(
            states, intArrayOf(Theme.ACCENT, if (Theme.DARK) Theme.TEXT_FAINT else Theme.SURFACE)
        )
        toggle.trackTintList = ColorStateList(
            states,
            intArrayOf(
                Theme.alpha(Theme.ACCENT, 110), Theme.alpha(Theme.TEXT_FAINT, 70)
            )
        )
        toggle.layoutParams = Ui.wrapWrap()
        // Flipping a setting confirms itself with a haptic tick. The pop that
        // used to ride along with it is outside this design's motion budget.
        toggle.setOnCheckedChangeListener { view, _ -> Ui.tick(view) }
        parent.addView(Ui.cardRow(this, icon, label, subtitle, toggle) { toggle.toggle() })
        return toggle
    }

    // =====================================================================
    // Battery-optimization exemption (background work)
    // =====================================================================

    /**
     * The battery row: icon, live state as the subtitle, chevron trailing.
     * Tapping opens the system's exemption page for this app.
     */
    private fun batteryExemptionRow(parent: LinearLayout): LinearLayout {
        val row = batteryExemptionRowView()
        parent.addView(row)
        return row
    }

    private fun batteryExemptionRowView(): LinearLayout {
        val chevron = ImageView(this)
        chevron.setImageDrawable(Icons.of("chevron-right", Theme.TEXT_MUTED, Ui.STROKE))
        chevron.scaleType = ImageView.ScaleType.FIT_CENTER
        chevron.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val cs = Theme.dp(this, 18.0f)
        chevron.layoutParams = LinearLayout.LayoutParams(cs, cs)
        return Ui.cardRow(
            this, "battery", Fa.SET_BATTERY,
            if (isBatteryExempt()) Fa.SET_BATTERY_OK else Fa.SET_BATTERY_TAP,
            chevron, Runnable { openBatteryExemption() }
        )
    }

    /**
     * Repaints the battery row when the user returns from the system page —
     * the exemption state can only change there, so onResume is the moment.
     */
    private fun refreshBatteryRow() {
        val row = batteryRow ?: return
        val parent = row.parent as? LinearLayout ?: return
        val index = parent.indexOfChild(row)
        if (index < 0) {
            return
        }
        parent.removeView(row)
        batteryRow = batteryExemptionRowView()
        parent.addView(batteryRow, index)
    }

    private fun isBatteryExempt(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < 23) {
                return true
            }
            val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
            power?.isIgnoringBatteryOptimizations(packageName) == true
        } catch (e: Exception) {
            false
        }
    }

    private fun openBatteryExemption() {
        try {
            if (Build.VERSION.SDK_INT < 23 || isBatteryExempt()) {
                return
            }
            val intent = Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ignored: Exception) {
            }
        }
    }

    // =====================================================================
    // About
    // =====================================================================

    /**
     * The About group — and the ONE place the Vega mark appears inside the app.
     * The chat screen deliberately shows no logo at all, so this row is where the
     * brand, the version and the project's two channels live. The mark is drawn
     * by [BrandMark] (vector paths, no raster resource) and takes its colour from
     * [Theme.TEXT], so it inverts with the palette.
     */
    private fun aboutSection(): LinearLayout {
        val card = card()

        val brandRow = Ui.cardRow(this, null, Fa.APP_NAME, Fa.SET_VERSION, null, null)
        val mark = ImageView(this)
        mark.setImageDrawable(BrandMark())
        mark.scaleType = ImageView.ScaleType.FIT_CENTER
        mark.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // Space.XL, the same box a cardRow glyph gets, with the same trailing gap.
        //
        // It was 40dp in a slot sized for 20dp, so the About row's leading edge sat
        // 20dp further out than the two channel rows directly beneath it — the one
        // misalignment on the screen, at the top of the last group.
        val markSize = Theme.dp(this, Ui.Space.XL)
        val markLp = LinearLayout.LayoutParams(markSize, markSize)
        markLp.marginEnd = Theme.dp(this, Ui.Space.L)
        mark.layoutParams = markLp
        // Index 0: cardRow was built WITHOUT a glyph, so the title stack is its
        // first child and the mark takes the leading slot a glyph would have had.
        // The params ride on the view because the three-argument
        // addView(child, index, params) is not in the stubbed API surface.
        brandRow.addView(mark, 0)
        card.addView(brandRow)
        rowDivider(card, ROW_INSET)
        card.addView(channelRow("Vega Enter", "https://t.me/VegaEnter"))
        rowDivider(card, ROW_INSET)
        card.addView(channelRow("ArchiveTel", "https://t.me/ArchiveTell"))
        return card
    }

    /**
     * One row linking to a Telegram channel — migrated here out of the chat
     * drawer. [Ui.cardRow] draws the glyph with `Icons.of(icon, Theme.TEXT, …)`,
     * NOT in Telegram's blue: this screen carries no hue at all, and a single
     * branded colour in an otherwise monochrome list reads as a rendering bug.
     */
    private fun channelRow(name: String, url: String): LinearLayout {
        val row = Ui.cardRow(
            this, "telegram", name, url.replace("https://", ""), chevron()
        ) { openLink(url) }
        row.contentDescription = name
        return row
    }

    /**
     * Opens an external link. A `t.me` URL resolves to the Telegram app when it
     * is installed and to the browser otherwise. Wrapped in try/catch so a device
     * with no handler — or an OEM that throws from the resolver — shows the link
     * in a toast instead of taking the app down.
     *
     * Deliberately a local copy of the chat screen's helper rather than a call
     * into it: neither Activity should reach into the other's private surface.
     */
    /**
     * Every transient message this screen shows, in one place — because the one
     * thing worth saying about a Toast under a mirrored interface is worth saying
     * once.
     *
     * A Toast is a SYSTEM window. It inherits nothing from this Activity: not the
     * palette, not the theme, and not `layoutDirection`. Nor can that be fixed
     * from here — `Toast.getView()` returns null from API 30 and `setView` throws,
     * so there is no view to reach into on any device this ships to.
     *
     * It does not need fixing. The platform's toast layout centres its text and
     * leaves `textDirection` at the default, which resolves to FIRST_STRONG — so a
     * Persian message lays itself out right-to-left from its own first strong
     * character, and the one Latin message here (a URL with no handler) lays
     * itself out left-to-right, both inside a centred block where alignment
     * cannot be wrong. What WOULD break is a message that mixed a leading Latin
     * token into Persian prose; every string below is one language or the other.
     */
    private fun say(message: String, long: Boolean) {
        Toast.makeText(
            this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    private fun openLink(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            say(url, true)
        }
    }

    companion object {
        private val LEVELS = arrayOf("low", "medium", "high", "xhigh", "max")
        private val THEMES = arrayOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK)

        /**
         * The two values [Prefs.setLanguage] accepts, in cell order. English
         * leads because it is the stored default; in Persian the track is
         * mirrored, so the leading cell is simply the one on the right.
         */
        private val LANGUAGES = arrayOf("en", "fa")

        /**
         * Persian's name for itself.
         *
         * A literal, and not [Fa.SET_LANGUAGE_FA], because that key answers
         * "what is this language called in the language you are reading" — it
         * says "Persian" to an English reader — and the one thing this cell must
         * never do is name Persian in a script a Persian-only reader cannot read.
         * Same category as `OpenAI` and `Vega Agent`: an endonym is a name.
         */
        private const val FA_ENDONYM = "فارسی"

        /**
         * The four settings pages. The hub is the main menu; the other three are
         * the destinations its iOS-style menu rows open.
         */
        private const val PAGE_HUB = 0
        private const val PAGE_TOOLS = 1
        private const val PAGE_BRAIN = 2
        private const val PAGE_UI = 3

        /**
         * The inset an in-card [Ui.divider] starts at: [Ui.Space.L] of row
         * padding plus a [Ui.Space.XL] glyph plus [Ui.Space.L] of gap, which is
         * exactly where a [Ui.cardRow]'s label begins. The rule therefore parts
         * the TEXT of two rows while the glyph column runs on unbroken.
         */
        private const val ROW_INSET = 52.0f

        /**
         * The inset an in-card [Ui.divider] starts at between two [Ui.iosRow]s:
         * [Ui.Space.L] of row padding plus the 30dp tile plus [Ui.Space.M] of
         * gap, which is exactly where the row's label begins — the iOS register,
         * where the separator runs from the text while the tile column continues
         * unbroken.
         */
        private const val TILE_ROW_INSET = 58.0f

        /** The separator in the connection row's one-line summary. */
        private const val SEP = "  ·  "

        /**
         * Cap for the model picker's list. A provider can serve hundreds of
         * IDs; the box shows the first hundred alphabetically and names the
         * remainder, instead of growing the settings screen without bound.
         */
        private const val MAX_MODELS_SHOWN = 100

        private fun parseInt(value: String, fallback: Int): Int = try {
            // Normalise Persian (۰-۹) and Arabic-Indic (٠-٩) digits to ASCII
            // first. A Persian keyboard enters Persian numerals, and "۱۰۰۰۰".toInt()
            // throws — which silently discarded the user's Maximum-tokens value.
            val parsed = normalizeDigits(value.trimJava()).toInt()
            if (parsed > 0) parsed else fallback
        } catch (e: Exception) {
            fallback
        }

        /** Maps Persian and Arabic-Indic digit code points onto ASCII 0-9. */
        private fun normalizeDigits(input: String): String {
            val sb = StringBuilder(input.length)
            for (ch in input) {
                val c = when (ch) {
                    in '۰'..'۹' -> '0' + (ch - '۰') // Persian ۰-۹
                    in '٠'..'٩' -> '0' + (ch - '٠') // Arabic ٠-٩
                    else -> ch
                }
                sb.append(c)
            }
            return sb.toString()
        }

        private fun maskKey(key: String?): String {
            if (key.isNullOrEmpty()) {
                return ""
            }
            if (key.length <= 10) {
                return "••••••"
            }
            return key.substring(0, 6) + "…" + key.substring(key.length - 4)
        }
    }
}
