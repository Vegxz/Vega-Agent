package com.vepro.code

import android.content.Context
import android.view.View

/**
 * Direction and numerals, derived from the one language flag in [Fa].
 *
 * ### The interface mirrors
 *
 * Persian is a right-to-left language and this build treats it as one: when
 * Persian is active [direction] returns RTL and the WHOLE chassis flips. The
 * drawer opens from the right, the composer's controls sit on the right, section
 * glyphs lead from the right, chevrons point left, and your own message bubble
 * hugs the left — because every one of those is the mirror image of where it sits
 * in English, which is what mirroring means.
 *
 * Two earlier builds got this wrong in opposite directions. v6 mirrored nothing
 * and only ran the *text* RTL, which left a Persian screen with English
 * furniture — a drawer sliding in from the wrong side of the language and an
 * outgoing bubble on the wrong edge of the conversation. v1 removed Persian
 * altogether. Neither is what a Persian speaker should get.
 *
 * ### Why this can be a one-line switch
 *
 * Because the layout code earned it. Every container in this app positions its
 * children with `setPaddingRelative`, `marginStart`/`marginEnd` and
 * `Gravity.START`/`Gravity.END`, and a source contract bans the physical
 * `Gravity.LEFT`/`RIGHT` forms outright. Those all resolve against the view's
 * layout direction, so setting the direction on each screen's root genuinely
 * flips the entire tree — insets, gaps, alignment and ripples together — instead
 * of needing a mirrored copy of every layout.
 *
 * ### What does NOT mirror
 *
 * Content that is not prose. Code blocks, file paths, URLs, API keys, model
 * names and numeric fields stay left-to-right islands inside the mirrored
 * chassis, because a path read right-to-left is unreadable. Those sites set
 * `LAYOUT_DIRECTION_LTR` or `TEXT_DIRECTION_LTR` on themselves deliberately.
 *
 * And prose in the OTHER language still reads correctly either way: message
 * bodies ask for `TEXT_DIRECTION_FIRST_STRONG`, so an English answer laid out on
 * a Persian screen runs left-to-right inside its own bubble, and a Persian answer
 * on an English screen runs right-to-left inside its own.
 */
object Lang {

    /**
     * Cached direction flag.
     *
     * [mirrored] is read once per view inside layout loops — every settings row,
     * every message, every sheet — and each read used to build a fresh [Prefs]
     * and hit SharedPreferences. The cache is dropped by [invalidate], which
     * `Fa.apply` calls, so the direction and the string table are refreshed
     * together and cannot disagree mid-screen.
     */
    @Volatile
    private var cached: Boolean? = null

    /** Drops the cached direction; called from [Fa.apply]. */
    fun invalidate() {
        cached = null
    }

    /** True when the interface language is Persian. */
    fun farsi(context: Context): Boolean {
        val known = cached
        if (known != null) {
            return known
        }
        val resolved = Prefs(context).language() == "fa"
        cached = resolved
        return resolved
    }

    /** The LAYOUT direction — RTL in Persian, LTR in English. */
    fun direction(context: Context): Int = if (farsi(context)) {
        View.LAYOUT_DIRECTION_RTL
    } else {
        View.LAYOUT_DIRECTION_LTR
    }

    /**
     * The TEXT direction for interface chrome — labels, buttons, section
     * headings, which are always in the interface's own language.
     *
     * PROSE does not come through here. Anything that can hold what the user or
     * the model wrote asks for `TEXT_DIRECTION_FIRST_STRONG` directly, so it
     * follows its own content rather than the interface.
     */
    fun textDirection(context: Context): Int = if (farsi(context)) {
        View.TEXT_DIRECTION_RTL
    } else {
        View.TEXT_DIRECTION_LTR
    }

    /**
     * Digits in the interface's own numerals: Latin in English, ۰-۹ in Persian.
     *
     * Lives here rather than in a screen because more than one screen needs it —
     * the activity strip, the workflow board and the thoughts panel all render
     * counts, and mixing ۵ with 5 on one screen is exactly the kind of detail
     * that makes an interface feel unfinished.
     */
    fun num(context: Context, value: Long): String {
        val plain = value.toString()
        if (!farsi(context)) {
            return plain
        }
        val sb = StringBuilder(plain.length)
        for (c in plain) {
            sb.append(if (c in '0'..'9') FA_DIGITS[c - '0'] else c)
        }
        return sb.toString()
    }

    fun num(context: Context, value: Int): String = num(context, value.toLong())

    /**
     * True when the LAYOUT is mirrored.
     *
     * Kept as a named helper rather than letting call sites test the language,
     * so the handful of places that must know which PHYSICAL edge they are
     * anchored to — a drawer's corner radii, its slide offset, a rail's inset,
     * an animation's travel — say WHY they are asking. Testing the language as a
     * proxy for the edge is how those sites and [direction] drifted apart before.
     */
    fun mirrored(context: Context): Boolean =
        direction(context) == View.LAYOUT_DIRECTION_RTL

    /** The chevron that points AWAY from the layout's start edge. */
    fun chevronForward(context: Context): String =
        if (mirrored(context)) "chevron-left" else "chevron-right"

    /** The chevron that points BACK, at the layout's start edge. */
    fun chevronBack(context: Context): String =
        if (mirrored(context)) "chevron-right" else "chevron-left"

    /** Picks between two literals for the handful of sites not worth a [Fa] key. */
    fun text(context: Context, english: String, persian: String): String =
        if (farsi(context)) persian else english

    private val FA_DIGITS = charArrayOf(
        '۰', '۱', '۲', '۳', '۴',
        '۵', '۶', '۷', '۸', '۹'
    )
}
