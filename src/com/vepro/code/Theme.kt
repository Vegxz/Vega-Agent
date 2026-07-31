package com.vepro.code

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View

/**
 * "Vega" design system — the visual core of the app.
 *
 * It replaces the older tinted look with a strictly monochrome one, built on
 * four ideas:
 *
 *  1. **Zero chroma.** Every token is black, white, or a grey between them.
 *     The dark spine is near-black graphite, the light spine an almost-white
 *     page — the register ChatGPT and Grok settled on. Meaning that used to be
 *     carried by hue is re-encoded as lightness, typeface weight, glyph choice
 *     and a 2dp start rail.
 *  2. **One accent, inverted per theme.** [ACCENT] is near-black on light and
 *     near-white on dark, and [ON_ACCENT] is always its inverse — so "primary"
 *     reads as a solid fill with inverse content in both palettes, with zero
 *     call-site changes.
 *  3. **Flat geometry.** A 12 / 16 / 20 / 28 / pill radius scale, flat fills
 *     and hairline borders. No sheens, no glows, no gradient sweeps: the only
 *     surviving gradient is the 2dp run hairline, and it is greyscale.
 *  4. **Tone helpers instead of hand-mixed colours.** Cards, panels, chips,
 *     pills and rails are all one call, so no screen invents its own version
 *     of a surface.
 *
 * Everything is still drawn in code — flat fills and vector paths only, no
 * bitmaps, no androidx, no Compose.
 *
 * Constant names are kept from v5.x/v6/v7 so non-UI code (AgentService,
 * MarkdownRenderer) reads exactly as before; the former hue tokens (CYAN,
 * GREEN, RED, BLUE, YELLOW, ROSE) survive as greys.
 *
 * Note on the colour literals: an ARGB value such as `0xFF0D0D0D` is an `Int`
 * in Java but a `Long` in Kotlin, so every opaque colour is explicitly
 * narrowed with `.toInt()`.
 */
object Theme {

    /**
     * Light is the reference theme, so the field default is `false`: the
     * cold-start frame the platform paints from `AppThemeLight` then matches
     * the first frame the app draws. The user's actual preference still wins —
     * [applyFromPrefs] runs before any view is inflated, and `Prefs.themeMode()`
     * still defaults to "system".
     */
    var DARK: Boolean = false

    /**
     * Bumped on every [apply] call. Views are painted from the *global*
     * palette, so an Activity that rendered at revision N is stale the moment
     * another Activity applies a different palette. Each screen records the
     * revision it drew with and rebuilds when it no longer matches — this is
     * what stops the settings/chat screens from ending up in opposite themes.
     */
    var revision: Int = 0
        private set

    var ACCENT: Int = 0
    var ACCENT_DK: Int = 0
    var ACCENT_LT: Int = 0
    var BG: Int = 0
    var BG_ELEV: Int = 0
    var BLUE: Int = 0
    var BORDER: Int = 0
    var BORDER_HI: Int = 0
    var GREEN: Int = 0
    var GREEN_BG: Int = 0
    var ON_ACCENT: Int = 0
    var RED: Int = 0
    var RED_BG: Int = 0
    var SCRIM: Int = 0

    /**
     * Content drawn ON a [SCRIM] — always light, in BOTH themes. The scrim is a
     * dark wash over arbitrary imagery (a video thumbnail), so unlike ON_ACCENT
     * this one deliberately does NOT invert: black glyphs would vanish into it.
     */
    var ON_SCRIM: Int = 0
    var SURFACE: Int = 0
    var SURFACE_2: Int = 0
    var TEXT: Int = 0
    var TEXT_FAINT: Int = 0
    var TEXT_MUTED: Int = 0
    var YELLOW: Int = 0

    /**
     * Diff ink and wash — the ONE place this palette carries hue, in both themes.
     *
     * Everything else here is greyscale on purpose, and a diff is the single case
     * where that rule costs more than it buys: added and removed are not "more" and
     * "less" of the same thing, they are opposites, and lightness alone cannot say
     * so. Encoding them as two strengths of the same grey wash is exactly what made
     * a hunk read as unchanged context with a `+`/`−` glyph doing all the work.
     *
     * Deliberately restrained — GitHub's own diff values rather than alert colours:
     * a pale wash behind the line, a saturated-but-dark ink for the sign and the
     * text. They are semantic (they mean added / removed) and used NOWHERE else.
     */
    var DIFF_ADD: Int = 0
    var DIFF_ADD_BG: Int = 0
    var DIFF_DEL: Int = 0
    var DIFF_DEL_BG: Int = 0

    /** Legacy "read / tool tone" token — now a plain muted grey. */
    var CYAN: Int = 0

    /** Legacy hot end of the thinking-heat ramp — now a plain grey. */
    var ROSE: Int = 0

    /**
     * The accent tone that is *legible as text* on [SURFACE] in the current
     * palette — lighter than [ACCENT] in dark, darker in light. Use this for
     * accent-coloured labels, links and headings; use [ACCENT] for fills.
     */
    var ACCENT_TEXT: Int = 0

    /** Fill for the user's chat bubble. */
    var BUBBLE_USER: Int = 0

    /** Text colour inside the user's chat bubble. */
    var ON_BUBBLE_USER: Int = 0

    /** Per-level colors for the five thinking levels: low → max. */
    private val THINK_RAMP = IntArray(5)

    /**
     * Colour for thinking level [level] (0 = low … 4 = max), clamped.
     * Returns a value, never the shared array, so no caller can retain a
     * reference that a later [apply] would silently repaint.
     */
    fun think(level: Int): Int = THINK_RAMP[Math.max(0, Math.min(4, level))]

    /** Defensive copy of the whole ramp, for gradient rails. */
    fun thinkRamp(): IntArray = THINK_RAMP.copyOf()

    // ---- radius tokens ----------------------------------------------------

    /** Card corner radius (dp) — the one radius every large card shares. */
    const val R_CARD = 20.0f

    /** Small controls: chips, badges, inline pills. */
    const val R_SM = 12.0f

    /** Medium controls: buttons, inputs, list rows. */
    const val R_MD = 16.0f

    /** Large surfaces: sheets, the composer. */
    const val R_LG = 28.0f

    /** Effectively a pill — clamped by the drawable to half the height. */
    const val R_PILL = 999.0f

    // ---- typefaces --------------------------------------------------------

    private var UI: Typeface? = null
    private var UI_MED: Typeface? = null
    private var UI_SB: Typeface? = null
    private var UI_BOLD: Typeface? = null
    private var UI_XBOLD: Typeface? = null
    private var MONO: Typeface? = null

    /**
     * The display density, cached so a Drawable that has no Context can still
     * reason in dp.
     *
     * [Icons] is the reason this exists. Its `draw` works on a canvas it has
     * already scaled by `boxSize / 24`, and a Drawable gets no Context — so
     * without a cached density it had no way to convert a dp width into the
     * pre-scale number `Paint.setStrokeWidth` wants. Adding a Context parameter to
     * `Icons.of(...)` was the obvious alternative and is a trap: both
     * `tools/check_ui.py` and `test_every_icon_name_resolves` match icon names
     * POSITIONALLY, so a new leading argument makes them silently stop seeing any
     * icon name at all while still reporting PASS.
     *
     * Written before the typeface guard below, and again by every [dp] call — of
     * which the UI makes hundreds before the first glyph is drawn — so the 3.0
     * fallback is never what actually gets used.
     */
    var DENSITY: Float = 3.0f
        private set

    fun init(context: Context) {
        DENSITY = context.resources.displayMetrics.density
        if (UI != null) {
            return
        }
        try {
            val assets = context.assets
            UI = Typeface.createFromAsset(assets, "fonts/Vazirmatn-Regular.ttf")
            UI_MED = Typeface.createFromAsset(assets, "fonts/Vazirmatn-Medium.ttf")
            UI_SB = Typeface.createFromAsset(assets, "fonts/Vazirmatn-SemiBold.ttf")
            UI_BOLD = Typeface.createFromAsset(assets, "fonts/Vazirmatn-Bold.ttf")
            UI_XBOLD = Typeface.createFromAsset(assets, "fonts/Vazirmatn-ExtraBold.ttf")
            MONO = Typeface.createFromAsset(assets, "fonts/JetBrainsMono-Regular.ttf")
        } catch (e: Exception) {
            val fallback = Typeface.SANS_SERIF
            UI = fallback
            UI_MED = fallback
            UI_SB = fallback
            UI_BOLD = fallback
            UI_XBOLD = fallback
            MONO = Typeface.MONOSPACE
        }
    }

    /** True when the device (OS) is currently in night/dark mode. */
    fun systemInNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Resolves the effective dark state from the user's theme mode preference. */
    fun resolveDark(context: Context, prefs: Prefs): Boolean = when (prefs.themeMode()) {
        Prefs.THEME_LIGHT -> false
        Prefs.THEME_DARK -> true
        else -> systemInNight(context)
    }

    /** Applies the palette that matches the user's theme mode (system/light/dark). */
    fun applyFromPrefs(context: Context, prefs: Prefs) {
        apply(resolveDark(context, prefs))
    }

    /** False until the first [apply], so the very first call always paints. */
    private var applied = false

    fun apply(dark: Boolean) {
        // Idempotent: re-applying the palette that is already live must NOT bump
        // [revision], otherwise every screen would rebuild itself on every
        // resume and the staleness check would be meaningless.
        if (applied && dark == DARK) {
            return
        }
        applied = true
        DARK = dark
        revision++
        if (dark) {
            // Graphite — a strictly monochrome dark spine. Zero chroma: every
            // literal below is either a pure grey (R == G == B) or a black /
            // white alpha wash, so nothing on screen can read as "coloured" by
            // accident. The former hue tokens survive as greys purely so the
            // ~63 call sites that name them keep compiling.
            BG = 0xFF0D0D0D.toInt()
            BG_ELEV = 0xFF0D0D0D.toInt()
            SURFACE = 0xFF1A1A1A.toInt()
            SURFACE_2 = 0xFF242424.toInt()
            BORDER = 0xFF262626.toInt()
            BORDER_HI = 0xFF383838.toInt()
            TEXT = 0xFFF7F7F7.toInt()
            TEXT_MUTED = 0xFFA3A3A3.toInt()
            TEXT_FAINT = 0xFF737373.toInt()
            // Vega brand: near-white accent on dark, with a near-black
            // ON_ACCENT. The inversion is load-bearing — see the class doc.
            ACCENT = 0xFFF7F7F7.toInt()
            ACCENT_LT = 0xFFFFFFFF.toInt()
            ACCENT_DK = 0xFFE0E0E0.toInt()
            ACCENT_TEXT = 0xFFF7F7F7.toInt()
            ON_ACCENT = 0xFF0D0D0D.toInt()
            CYAN = 0xFFA3A3A3.toInt()
            ROSE = 0xFFE5E5E5.toInt()
            GREEN = 0xFFE5E5E5.toInt()
            GREEN_BG = 0x14FFFFFF
            RED = 0xFFF7F7F7.toInt()
            RED_BG = 0x1FFFFFFF
            // GitHub's dark diff values: a deep, low-chroma wash with a bright ink
            // over it, so a hunk stays legible on the near-black card.
            DIFF_ADD = 0xFF3FB950.toInt()
            DIFF_ADD_BG = 0x3A2EA043
            DIFF_DEL = 0xFFF85149.toInt()
            DIFF_DEL_BG = 0x3AF85149
            BLUE = 0xFFB8B8B8.toInt()
            YELLOW = 0xFFA3A3A3.toInt()
            SCRIM = 0xB3000000.toInt()
            ON_SCRIM = 0xFFFFFFFF.toInt()
            // The user's turn: a raised neutral chip, like ChatGPT's. Reads as
            // "your message" without shouting over the assistant's plain text.
            BUBBLE_USER = 0xFF242424.toInt()
            ON_BUBBLE_USER = 0xFFF7F7F7.toInt()
            THINK_RAMP[0] = 0xFF737373.toInt() // low    — faint
            THINK_RAMP[1] = 0xFF8C8C8C.toInt() // medium
            THINK_RAMP[2] = 0xFFA8A8A8.toInt() // high
            THINK_RAMP[3] = 0xFFCCCCCC.toInt() // xhigh
            THINK_RAMP[4] = 0xFFF7F7F7.toInt() // max    — full text weight
            return
        }
        // Paper — the monochrome light spine and the reference theme. An
        // almost-white page, true-white cards, neutral grey borders. The page
        // stays a step darker than the cards so the composer and the grouped
        // cards still read as objects sitting on it.
        BG = 0xFFF9F9F9.toInt()
        BG_ELEV = 0xFFF9F9F9.toInt()
        SURFACE = 0xFFFFFFFF.toInt()
        SURFACE_2 = 0xFFF0F0F0.toInt()
        BORDER = 0xFFEDEDED.toInt()
        BORDER_HI = 0xFFDCDCDC.toInt()
        TEXT = 0xFF0D0D0D.toInt()
        // Both secondary inks are a step darker than they were, to bring the
        // light palette up to the dark one's legibility rather than to change its
        // character.
        //
        // Measured against this page: TEXT_MUTED was 4.84 / 5.10 / 4.47 on
        // BG / SURFACE / SURFACE_2 — the last of those missing the 4.5 floor by
        // three hundredths — and is now 5.21 / 5.49 / 4.82. TEXT_FAINT was far
        // worse, 2.54 / 2.68 / 2.35, failing even the 3.0 large-text floor while
        // carrying real content: code-card language captions, every footnote, the
        // thinking hint, and all chevrons. It is now 3.50 / 3.69 / 3.24, which is
        // the same range dark mode's own TEXT_FAINT already sits in (3.27-4.10),
        // so the two themes finally agree about how quiet "faint" is.
        TEXT_MUTED = 0xFF696969.toInt()
        TEXT_FAINT = 0xFF858585.toInt()
        // Near-black accent with white content on top — the mirror of dark.
        ACCENT = 0xFF0D0D0D.toInt()
        ACCENT_LT = 0xFF3D3D3D.toInt()
        ACCENT_DK = 0xFF000000.toInt()
        ACCENT_TEXT = 0xFF0D0D0D.toInt()
        ON_ACCENT = 0xFFFFFFFF.toInt()
        CYAN = 0xFF6E6E6E.toInt()
        ROSE = 0xFF2B2B2B.toInt()
        GREEN = 0xFF2B2B2B.toInt()
        GREEN_BG = 0x140D0D0D
        RED = 0xFF0D0D0D.toInt()
        RED_BG = 0x1F0D0D0D
        // GitHub's light diff values.
        DIFF_ADD = 0xFF1A7F37.toInt()
        DIFF_ADD_BG = 0xFFE6FFEC.toInt()
        DIFF_DEL = 0xFFCF222E.toInt()
        DIFF_DEL_BG = 0xFFFFEBE9.toInt()
        BLUE = 0xFF4F4F4F.toInt()
        YELLOW = 0xFF6E6E6E.toInt()
        SCRIM = 0x660D0D0D
        ON_SCRIM = 0xFFFFFFFF.toInt()
        // Deliberately a full step darker than the page: the user bubble carries
        // no border any more, so this fill is the ONLY thing separating it from
        // the background. A lighter value vanished on dim or low-gamut screens
        // and the transcript lost its structure.
        BUBBLE_USER = 0xFFF0F0F0.toInt()
        ON_BUBBLE_USER = 0xFF0D0D0D.toInt()
        THINK_RAMP[0] = 0xFF9E9E9E.toInt()
        THINK_RAMP[1] = 0xFF7C7C7C.toInt()
        THINK_RAMP[2] = 0xFF5A5A5A.toInt()
        THINK_RAMP[3] = 0xFF3D3D3D.toInt()
        THINK_RAMP[4] = 0xFF0D0D0D.toInt()
    }

    fun ui(): Typeface = UI ?: Typeface.SANS_SERIF

    fun uiMedium(): Typeface = UI_MED ?: Typeface.SANS_SERIF

    fun uiSemi(): Typeface = UI_SB ?: Typeface.SANS_SERIF

    fun uiBold(): Typeface = UI_BOLD ?: Typeface.DEFAULT_BOLD

    fun uiXBold(): Typeface = UI_XBOLD ?: Typeface.DEFAULT_BOLD

    fun mono(): Typeface = MONO ?: Typeface.MONOSPACE

    fun dp(context: Context, value: Float): Int {
        val metrics = context.resources.displayMetrics
        DENSITY = metrics.density
        return Math.round(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)
        )
    }

    /** dp as a float — Kotlin will not widen Int to Float implicitly. */
    fun dpf(context: Context, value: Float): Float = dp(context, value).toFloat()

    /** A hairline that is always at least one physical pixel but never fat. */
    fun hairline(context: Context): Int = Math.max(1, dp(context, 1.0f))

    // ---- primitive drawables --------------------------------------------

    fun roundRect(color: Int, radius: Float, context: Context): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(color)
        drawable.cornerRadius = dpf(context, radius)
        return drawable
    }

    fun roundStroke(
        fill: Int,
        strokeColor: Int,
        radius: Float,
        strokeDp: Int,
        context: Context
    ): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(fill)
        drawable.cornerRadius = dpf(context, radius)
        drawable.setStroke(Math.max(1, dp(context, strokeDp.toFloat())), strokeColor)
        return drawable
    }

    /** Round-rect with per-corner radii, in dp, ordered TL, TR, BR, BL. */
    fun roundRectCorners(
        fill: Int,
        tl: Float,
        tr: Float,
        br: Float,
        bl: Float,
        context: Context
    ): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(fill)
        val a = dpf(context, tl)
        val b = dpf(context, tr)
        val c = dpf(context, br)
        val d = dpf(context, bl)
        drawable.cornerRadii = floatArrayOf(a, a, b, b, c, c, d, d)
        return drawable
    }

    /** A filled circle in [color] — dots, status pips, avatar grounds. */
    fun circle(color: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.OVAL
        drawable.setColor(color)
        return drawable
    }

    /**
     * Neutral ripple over nothing. The ink is a plain white / black wash — a
     * tinted ripple was the last place a hue leaked onto an otherwise
     * monochrome surface.
     */
    fun rippleTransparent(radius: Float, context: Context): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(if (DARK) 0x2BFFFFFF else 0x14000000),
        null,
        roundRect(-1, radius, context)
    )

    /** Ripple layered over an arbitrary background drawable. */
    fun rippleOver(background: Drawable, radius: Float, context: Context): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(if (DARK) 0x30FFFFFF else 0x1F000000),
            background,
            roundRect(-1, radius, context)
        )

    /** Ripple tinted with the given color, layered over a background. */
    fun rippleColorOver(
        background: Drawable,
        color: Int,
        radius: Float,
        context: Context
    ): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(alpha(color, 70)),
        background,
        roundRect(-1, radius, context)
    )

    // ---- surfaces ---------------------------------------------------------

    /**
     * Card surface: a FLAT [SURFACE] fill plus a hairline [BORDER].
     *
     * There used to be a vertical sheen here to fake depth. It is gone — the
     * monochrome system separates surfaces by lightness and a hairline only.
     * The `setStroke` call stays, at [hairline], because a GradientDrawable
     * stroke is INSET GEOMETRY: removing it (or changing its width) moves the
     * content box of ~30 surfaces by 2dp and reflows every one of them.
     */
    fun glassCard(radius: Float, context: Context): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(SURFACE)
        drawable.cornerRadius = dpf(context, radius)
        drawable.setStroke(hairline(context), BORDER)
        return drawable
    }

    /** Flat card — identical to [glassCard] now that the sheen is gone. */
    fun flatCard(radius: Float, context: Context): GradientDrawable =
        roundStroke(SURFACE, BORDER, radius, 1, context)

    /**
     * The recessed variant: one step *into* the page instead of out of it. Used
     * for inputs, segmented controls, code and diff blocks — surfaces that hold
     * content rather than present it.
     */
    fun sunkenCard(radius: Float, context: Context): GradientDrawable =
        roundStroke(SURFACE_2, BORDER, radius, 1, context)

    /**
     * A tappable row sitting ON A SHEET — same [SURFACE] fill as the sheet panel
     * it sits on, but a [BORDER_HI] outline instead of [BORDER].
     *
     * This exists because [flatCard] is invisible in that one position. A sheet
     * panel is itself [SURFACE] (see `Sheet.kt`), so a `flatCard` row on it was
     * SURFACE-on-SURFACE — a measured 1.00:1 fill ratio with a 1.17:1 hairline,
     * i.e. rows a user simply cannot see the edges of on the light palette. The
     * fill has to stay [SURFACE] so that a SELECTED row can still distinguish
     * itself with a [SURFACE_2] fill, which means the outline is the only channel
     * left — hence [BORDER_HI] (1.37:1), the app's already-defined stronger edge.
     *
     * Same 1dp hairline as every other card here, so this is a drop-in for
     * [flatCard] and moves nobody's content box.
     */
    fun sheetRow(radius: Float, context: Context): GradientDrawable =
        roundStroke(SURFACE, BORDER_HI, radius, 1, context)

    /**
     * Applies the system elevation shadow with neutral black spot/ambient
     * inks, and a matching outline so the shadow follows the corner radius.
     */
    fun elevate(view: View, elevationDp: Float, radius: Float) {
        elevate(view, elevationDp, radius, true)
    }

    /**
     * [clipChildren] = false keeps the rounded OUTLINE (so the shadow follows
     * the corners) without turning on `clipToOutline`.
     *
     * That distinction matters for any view whose own background already draws
     * the rounded shape and a 1dp border: outline clipping is not
     * anti-aliased, so it shaves the outer half of that border and leaves a
     * ragged, stair-stepped edge — very visible on the light palette, where the
     * border carries real contrast. Views that must clip real CONTENT to their
     * corners (an image, a filled tile) still pass true.
     */
    fun elevate(view: View, elevationDp: Float, radius: Float, clipChildren: Boolean) {
        if (Build.VERSION.SDK_INT < 21) {
            return
        }
        view.elevation = dpf(view.context, elevationDp)
        if (clipChildren) {
            Ui.roundClip(view, radius)
        } else {
            Ui.roundOutline(view, radius)
        }
        if (Build.VERSION.SDK_INT >= 28) {
            // Shadow colours are ALPHA-BEARING: the platform multiplies the
            // supplied colour by its own shadow falloff, so a fully opaque ink
            // produced a hard, dirty rim around every card instead of a soft
            // shade — worst on the low-elevation cards, where the rim is most
            // of what you see.
            //
            // Both palettes now pass a translucent PURE BLACK ink. Ambient (the
            // flat, all-round component) is lighter than spot (the directional
            // one), which is what makes an edge read as a shadow rather than a
            // border. The old light-mode ink carried a blue-grey cast; on a
            // monochrome page any chroma in a shadow reads as a colour bug.
            if (DARK) {
                view.outlineAmbientShadowColor = 0x40000000
                view.outlineSpotShadowColor = 0x66000000
            } else {
                view.outlineAmbientShadowColor = 0x14000000
                view.outlineSpotShadowColor = 0x24000000
            }
        }
    }

    /**
     * Text-field background. Flat [SURFACE_2] fill; the focused state swaps the
     * hairline border for a full-strength [ACCENT] one. The stroke WIDTHS are
     * unchanged from the previous system on purpose — they are inset geometry,
     * and altering them would resize the text box.
     */
    fun inputBg(focused: Boolean, radius: Float, context: Context): GradientDrawable {
        return if (focused) {
            roundStroke(SURFACE_2, ACCENT, radius, 2, context)
        } else {
            roundStroke(SURFACE_2, BORDER, radius, 1, context)
        }
    }

    /**
     * The composer shell — the one control the user touches most. A flat
     * [SURFACE] card; focused it gains an [ACCENT] ring. Both states share the
     * same geometry so nothing shifts when focus changes.
     */
    fun composerBg(focused: Boolean, radius: Float, context: Context): GradientDrawable {
        return if (focused) {
            // ACCENT at 60%, not solid.
            //
            // ACCENT is pure near-black on the light palette and near-white on the
            // dark one, so a solid 2dp focus ring drew a hard black — or hard white —
            // outline around the composer the moment the caret appeared. That is the
            // heaviest mark on the screen, sitting around the one control that is
            // ALWAYS on screen, and it is more emphasis than "this field has focus"
            // needs. Same colour, same width, same geometry; 40% less shout.
            roundStroke(SURFACE, alpha(ACCENT, 152), radius, 2, context)
        } else {
            roundStroke(SURFACE, if (DARK) BORDER else BORDER_HI, radius, 2, context)
        }
    }

    /**
     * Chip ground for an icon badge: a flat low-alpha wash of [color] with a
     * hairline neutral border. Every tone token is a grey now, so the wash
     * composites to a grey too.
     */
    fun iconChip(color: Int, radius: Float, context: Context): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(alpha(color, if (DARK) 34 else 20))
        drawable.cornerRadius = dpf(context, radius)
        drawable.setStroke(hairline(context), BORDER)
        return drawable
    }

    /**
     * A quiet tone panel — used by the reasoning panel and the tool cards. The
     * vertical falloff is gone: a flat wash of [color] plus a hairline neutral
     * border, so the panel sits on the page without any painted depth.
     */
    fun tonePanel(color: Int, radius: Float, context: Context): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(alpha(color, if (DARK) 26 else 16))
        drawable.cornerRadius = dpf(context, radius)
        drawable.setStroke(hairline(context), BORDER)
        return drawable
    }

    /** A quiet neutral pill (metadata chips, counters, breadcrumbs). */
    fun chip(radius: Float, context: Context): GradientDrawable =
        roundStroke(SURFACE_2, BORDER, radius, 1, context)

    /**
     * Primary button ground: a SOLID [ACCENT] fill with an [ON_ACCENT] ripple
     * over it. [ACCENT] inverts per palette, so this is a black pill under
     * white content in light mode and a white pill under black content in dark.
     */
    fun actionButton(radius: Float, context: Context): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(alpha(ON_ACCENT, 90)),
        // A solid ACCENT fill with a single hairline of its own ink at 12% over
        // the top.
        //
        // The fill alone is a flat silhouette: correct, and slightly cheap,
        // because on both palettes the button's edge is the highest-contrast line
        // on the screen and it lands wherever the anti-aliaser happens to put it.
        // The ring is drawn from ON_ACCENT — the colour of the glyph inside — so it
        // reads as a lit edge on the shape rather than as a border around it, and
        // it gives the disc a definite rim at every size the app draws it.
        //
        // Still no shadow: depth stays opt-in via elevate().
        rimmed(ACCENT, alpha(ON_ACCENT, 30), radius, context),
        roundRect(-1, radius, context)
    )

    /**
     * A filled round-rect with a hairline rim of [rim] on top of it.
     *
     * The flat system's one weakness is that a fill with no edge has no edge —
     * two neutral greys that differ by a few percent become one shape. A single
     * hairline costs nothing, breaks no rule about colour, and is the difference
     * between a control that looks drawn and one that looks placed.
     */
    fun rimmed(fill: Int, rim: Int, radius: Float, context: Context): LayerDrawable =
        LayerDrawable(
            arrayOf<Drawable>(
                roundRect(fill, radius, context),
                roundStroke(0, rim, radius, 1, context)
            )
        )

    /**
     * The stop state is the SAME solid [ACCENT] circle as [actionButton] — only
     * the glyph inside it changes (arrow → square). It used to be a tinted red
     * outline, which is exactly the kind of hue-carried meaning this system
     * removes. Note that none of the three button grounds carries a stroke, so
     * swapping between them never shifts the glyph by a hairline.
     */
    fun stopButton(radius: Float, context: Context): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(alpha(ON_ACCENT, 90)),
        // The same rimmed ground as [actionButton], for the reason the comment
        // above gives: identical grounds mean swapping arrow for square cannot
        // move the glyph by a hairline.
        rimmed(ACCENT, alpha(ON_ACCENT, 30), radius, context),
        roundRect(-1, radius, context)
    )

    /** The transient "stopping…" state: a flat [SURFACE_2] fill, no accent. */
    fun stoppingButton(radius: Float, context: Context): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(alpha(TEXT_MUTED, 45)),
        rimmed(SURFACE_2, alpha(TEXT, 22), radius, context),
        roundRect(-1, radius, context)
    )

    /**
     * The 2dp run hairline — the ONE surviving gradient in the system, and it is
     * greyscale, so it does not break the monochrome rule.
     *
     * A left-to-right linear ramp: transparent → [ACCENT] → transparent, so the
     * animated sweep fades in and out at both ends instead of clipping against a
     * hard edge. Everything the old version added on top (the CYAN tail, the
     * four-stop brand ramp) is gone.
     */
    fun runBar(context: Context): GradientDrawable {
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(alpha(ACCENT, 0), ACCENT, alpha(ACCENT, 0))
        )
        drawable.cornerRadius = dpf(context, 2.0f)
        return drawable
    }

    // ---- color math -------------------------------------------------------

    fun alpha(color: Int, a: Int): Int =
        (color and 0xFFFFFF) or (Math.max(0, Math.min(255, a)) shl 24)

    /** Blends color [b] into [a] by fraction [t] (0..1). */
    fun mix(a: Int, b: Int, t: Float): Int {
        val u = Math.max(0.0f, Math.min(1.0f, t))
        val ar = (a shr 16) and 255
        val ag = (a shr 8) and 255
        val ab = a and 255
        val aa = (a ushr 24) and 255
        val br = (b shr 16) and 255
        val bg = (b shr 8) and 255
        val bb = b and 255
        val ba = (b ushr 24) and 255
        val r = Math.round(ar + (br - ar) * u)
        val g = Math.round(ag + (bg - ag) * u)
        val bl = Math.round(ab + (bb - ab) * u)
        val al = Math.round(aa + (ba - aa) * u)
        return (al shl 24) or (r shl 16) or (g shl 8) or bl
    }

    fun lighten(color: Int, t: Float): Int = mix(color, 0xFFFFFFFF.toInt(), t)

    fun darken(color: Int, t: Float): Int = mix(color, 0xFF000000.toInt(), t)

    /** Window background (also paints the status-bar area under edge-to-edge). */
    fun windowBg(): ColorDrawable = ColorDrawable(BG)
}
