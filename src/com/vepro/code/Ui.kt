package com.vepro.code

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared view factories for the Vega UI.
 *
 * Everything a screen needs to compose a row, a chip, a pill or a card lives
 * here, so no screen hand-rolls its own version of a control. Layout-param
 * helpers are included on purpose: the old code spent five lines building every
 * `LayoutParams`, which is where inconsistent spacing creeps in.
 *
 * The look is strictly monochrome — see `Theme`. Nothing in this file paints a
 * gradient, a glow or a tinted panel; separation comes from FILL, and emphasis
 * from typeface weight. The motion budget is near zero: a press scale, a
 * cross-fade, a reveal, and nothing else.
 */
object Ui {

    const val PRIMARY = 0
    const val SECONDARY = 1
    const val DANGER = 2
    const val GHOST = 3

    /** The minimum touch target Android asks for, and the one every control here meets. */
    private const val TOUCH_DP = 48.0f

    /**
     * The one icon stroke width in the app, in DP as rendered on screen. Every
     * glyph, everywhere, uses it — so a button, a row and a chip never disagree
     * about how heavy an outline is.
     *
     * Public because screens draw glyphs too: a bare `ImageView` outside these
     * factories must be able to say [STROKE] rather than re-type a float.
     *
     * It reads as dp because [Icons.IconDrawable] now divides its canvas scale
     * back out. Until it did, this number was in 24-unit viewport space and the
     * width that actually reached the screen was `STROKE * glyphDp / 24` — so the
     * "one width" was really seven, from 1.11dp on a 14dp chevron to 1.74dp on a
     * 22dp glyph, and the constant did not do the single job it exists for.
     *
     * 1.5 rather than the old 1.9 because the meaning changed. 1.9 in viewport
     * units only ever rendered as 1.9dp on a 24dp box, and this app draws almost
     * nothing at 24dp — its glyphs are 14-22dp, rendering at 1.11-1.74dp. Keeping
     * 1.9 would therefore have made every outline in the app heavier than it has
     * ever looked. 1.5dp sits in the middle of the range being replaced, so the
     * spread collapses to nothing while the app's overall weight barely moves.
     *
     * There was briefly a second, heavier value for light-on-dark glyphs (the
     * arrow in the black send disc), on the theory that a light stroke reads
     * thinner than a dark one at the same width. It was wrong twice over: the
     * polarity flips with the theme — in dark mode ACCENT is near-white and
     * ON_ACCENT near-black, so that same arrow is *dark on light* — and
     * [pillButton] draws the identical polarity at this width anyway. Two
     * widths reintroduced exactly the disagreement this constant exists to
     * prevent. One value.
     *
     * 1.45, after a round trip through 1.7 that was simply too heavy.
     *
     * The reasoning for 1.7 was that a 1.5dp outline at a faint tint reads as a
     * grey suggestion rather than a shape. That is true of a *blurry* 1.5dp
     * outline — and blurriness was the actual defect, since a 24-unit drawing
     * scaled into an arbitrary box put every edge on a fractional pixel.
     * [Icons.IconDrawable] snaps the origin and the width to the pixel grid now,
     * so 1.45dp lands as one crisp column instead of two half-lit ones, and the
     * extra weight turned out to be paying for a problem that no longer exists.
     * At 1.7 the set read as drawn with a marker; the logo and the button glyphs
     * were the two places it showed most.
     *
     * 1.34, and this time the number was chosen by measuring what actually reaches
     * the screen rather than by taste.
     *
     * 1.45 was never rendered. [Icons.IconDrawable] snapped the width to a whole
     * pixel, so at 3x density every glyph from 14dp to 24dp came out at exactly 4px
     * — 1.33dp — and at 2x every one of them came out at 3px, or 1.50dp. The
     * constant was therefore decorative: two densities rendered two different
     * weights, neither of them 1.45, and the optical ramp that was supposed to
     * relieve large glyphs was quantised away entirely.
     *
     * Two changes fix that, and this value follows from them. The drawable snaps to
     * HALF pixels, which leaves enough resolution for the sizes in use to differ;
     * and its optical ramp now lightens SMALL glyphs as well as large ones, because
     * a 14dp glyph carrying a 20dp glyph's stroke is 43% more ink around 30% less
     * shape. That is what "the icons are too thick" was pointing at: not this
     * number, but this number applied unchanged to the 14-18dp sizes the interface
     * is almost entirely built from.
     *
     * 1.34 is the width that renders as 4px at 3x — i.e. exactly what the app
     * already looked like at its most common density — so nothing gets HEAVIER
     * anywhere, while 2x drops from 1.50dp to 1.25dp and the small glyphs at 3x
     * drop from 1.33dp to 1.17dp. Still exactly one value.
     */
    const val STROKE = 1.34f

    /**
     * The spacing scale. Six steps, all multiples of four, and the only gaps a
     * screen should use — a layout that reaches for `dp(context, 13f)` is
     * inventing a seventh step nobody else will match.
     */
    object Space {
        const val XS = 4.0f
        const val S = 8.0f
        const val M = 12.0f
        const val L = 16.0f
        const val XL = 20.0f
        const val XXL = 28.0f
    }

    /**
     * The type scale, in sp. [BODY] and [HEAD] are deliberately the same size:
     * a heading in this system is distinguished by WEIGHT, not by size, which is
     * what keeps a settings screen from looking like a ransom note.
     */
    object Type {
        const val TITLE = 22.0f

        /**
         * The step between [TITLE] and [HEAD], for a sheet's own heading.
         *
         * Declared because the app was already rendering it: `Sheet` asked for
         * `Type.HEAD + 2.0f`, which invented an undeclared 18sp step for every
         * sheet title in the app — the single most-repeated heading there is. A
         * scale that the most common heading in the product has to do arithmetic
         * to escape is missing a step, so here it is.
         */
        const val SUBTITLE = 18.0f

        const val HEAD = 16.0f
        const val BODY = 16.0f
        const val LABEL = 14.5f
        const val META = 13.0f
        const val MICRO = 11.5f
    }

    // ---- layout helpers ---------------------------------------------------

    /** MATCH_PARENT × WRAP_CONTENT — the default for a stacked block. */
    fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    /** WRAP_CONTENT × WRAP_CONTENT. */
    fun wrapWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    /** The greedy child of a row: 0 × WRAP_CONTENT, weight 1. */
    fun grow(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)

    /** Horizontal row, vertically centred. */
    fun row(context: Context): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        return row
    }

    /** Vertical stack. */
    fun column(context: Context): LinearLayout {
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        return column
    }

    /** A text view in one call: value, size (sp), colour, typeface. */
    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float,
        color: Int,
        face: android.graphics.Typeface
    ): TextView {
        val view = TextView(context)
        view.text = value
        view.textSize = sizeSp
        view.setTextColor(color)
        view.typeface = face
        return view
    }

    /**
     * Label inside a row: renders each script correctly but stays anchored to
     * the layout's START edge.
     *
     * [View.TEXT_DIRECTION_FIRST_STRONG] alone is right for a *paragraph* (a
     * whole English message should read left-to-right), but wrong for a short
     * label sharing a row with an icon: a Latin string inside an RTL layout
     * resolves the view to LTR and the glyphs jump to the far edge of the
     * weighted slot, leaving the label stranded away from its icon. Pinning
     * the ALIGNMENT to the layout start keeps the label beside its icon in both
     * languages while the text itself still shapes in its own direction.
     */
    fun rowLabel(view: TextView) {
        view.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        view.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    // ---- controls ---------------------------------------------------------

    /**
     * Round icon button.
     *
     * The padding is derived from the FULL box, not a 44dp inner box: the old
     * `(44 - iconDp) / 2` left `iconDp + 4` of drawing area, so a 24dp icon
     * actually rendered at 28dp and the ripple was a squircle rather than a
     * circle.
     */
    fun iconButton(
        context: Context,
        icon: String,
        iconDp: Float,
        color: Int,
        onClick: View.OnClickListener?
    ): ImageView {
        val view = ImageView(context)
        view.setImageDrawable(Icons.of(icon, color, STROKE))
        view.contentDescription = iconLabel(context, icon)
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        val boxSize = Theme.dp(context, TOUCH_DP)
        val pad = Math.max(0, Theme.dp(context, (TOUCH_DP - iconDp) / 2.0f))
        view.setPadding(pad, pad, pad, pad)
        view.background = Theme.rippleTransparent(TOUCH_DP / 2.0f, context)
        view.layoutParams = LinearLayout.LayoutParams(boxSize, boxSize)
        if (onClick != null) {
            view.setOnClickListener(onClick)
            // Same press physics as every other control — a bare ripple on a
            // transparent glyph is easy to miss on a busy background.
            pressScale(view)
        }
        return view
    }

    /**
     * Icon button on its own quiet surface — a soft chip instead of a bare
     * glyph. Used for the composer's tools and the settings back arrow, where a
     * naked icon reads as decoration rather than as something you can press.
     *
     * The chip is a borderless [Theme.SURFACE_2] fill now. A hairline ring
     * around a 38dp circle read as a second, competing outline next to the
     * glyph's own 1.9dp stroke; the new system separates by fill only.
     */
    fun softIconButton(
        context: Context,
        icon: String,
        sizeDp: Float,
        iconDp: Float,
        color: Int,
        onClick: View.OnClickListener?
    ): ImageView {
        val view = ImageView(context)
        view.setImageDrawable(Icons.of(icon, color, STROKE))
        view.contentDescription = iconLabel(context, icon)
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        val boxSize = Theme.dp(context, sizeDp)
        val radius = sizeDp / 2.0f
        val pad = Math.max(0, Theme.dp(context, (sizeDp - iconDp) / 2.0f))
        view.setPadding(pad, pad, pad, pad)
        view.background = Theme.rippleOver(
            Theme.roundRect(Theme.SURFACE_2, radius, context), radius, context
        )
        view.layoutParams = LinearLayout.LayoutParams(boxSize, boxSize)
        if (onClick != null) {
            view.setOnClickListener(onClick)
            pressScale(view)
        }
        return view
    }

    /**
     * A bare circular icon button — the header's menu / new-chat buttons and the
     * composer's `+`, `@` and send buttons.
     *
     * [fill] `== 0` means "no ground at all": a transparent circle with only a
     * ripple, for a glyph sitting directly on the page. Any other value is used
     * as a solid fill, so the send button is `fill = Theme.ACCENT` with
     * `tint = Theme.ON_ACCENT` and inverts correctly with the palette.
     *
     * The returned view carries `LinearLayout.LayoutParams`; a caller adding it
     * to a `FrameLayout` must pass its own `FrameLayout.LayoutParams` to
     * `addView`, or the frame will fail to cast them at measure time.
     */
    fun circleButton(
        ctx: Context,
        icon: String,
        sizeDp: Float,
        iconDp: Float,
        tint: Int,
        fill: Int,
        onClick: Runnable?
    ): ImageView {
        val view = ImageView(ctx)
        view.setImageDrawable(Icons.of(icon, tint, STROKE))
        view.contentDescription = iconLabel(ctx, icon)
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        val radius = sizeDp / 2.0f
        val boxSize = Theme.dp(ctx, sizeDp)
        val pad = Math.max(0, Theme.dp(ctx, (sizeDp - iconDp) / 2.0f))
        view.setPadding(pad, pad, pad, pad)
        view.background = if (fill == 0) {
            Theme.rippleTransparent(radius, ctx)
        } else {
            Theme.rippleOver(Theme.roundRect(fill, radius, ctx), radius, ctx)
        }
        view.layoutParams = LinearLayout.LayoutParams(boxSize, boxSize)
        if (onClick != null) {
            view.setOnClickListener { onClick.run() }
            pressScale(view)
        }
        return view
    }

    /**
     * The model-selector chip: `[glyph] Label ⌄` on a quiet pill.
     *
     * CHILD ORDER IS STABLE AND PART OF THE CONTRACT, because call sites repaint
     * the label in place rather than rebuilding the chip:
     *
     *  * `getChildAt(0)` — the leading glyph [ImageView]. **Always present**; it
     *    is simply `GONE` when [icon] is null, precisely so the label index
     *    never moves.
     *  * `getChildAt(1)` — the label [TextView].
     *  * `getChildAt(2)` — the trailing `chevron-down` [ImageView].
     */
    fun selectorChip(
        ctx: Context,
        icon: String?,
        label: String,
        heightDp: Float,
        onClick: Runnable?
    ): LinearLayout {
        val chip = row(ctx)
        val radius = heightDp / 2.0f
        chip.minimumHeight = Theme.dp(ctx, heightDp)
        chip.background = Theme.rippleOver(
            Theme.roundRect(Theme.SURFACE_2, radius, ctx), radius, ctx
        )
        chip.setPaddingRelative(Theme.dp(ctx, 10.0f), 0, Theme.dp(ctx, 8.0f), 0)

        val glyph = ImageView(ctx)
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (icon != null) {
            glyph.setImageDrawable(Icons.of(icon, Theme.TEXT_MUTED, STROKE))
        } else {
            glyph.visibility = View.GONE
        }
        val glyphSize = Theme.dp(ctx, 15.0f)
        val glyphParams = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphParams.marginEnd = Theme.dp(ctx, 6.0f)
        chip.addView(glyph, glyphParams)

        val view = text(ctx, label, Type.META, Theme.TEXT, Theme.uiMedium())
        view.setSingleLine(true)
        view.ellipsize = android.text.TextUtils.TruncateAt.END
        rowLabel(view)
        chip.addView(view, wrapWrap())

        val chevron = ImageView(ctx)
        chevron.setImageDrawable(Icons.of("chevron-down", Theme.TEXT_FAINT, STROKE))
        chevron.scaleType = ImageView.ScaleType.FIT_CENTER
        chevron.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val chevronSize = Theme.dp(ctx, 14.0f)
        val chevronParams = LinearLayout.LayoutParams(chevronSize, chevronSize)
        chevronParams.marginStart = Theme.dp(ctx, Space.XS)
        chip.addView(chevron, chevronParams)

        if (onClick != null) {
            chip.setOnClickListener { onClick.run() }
            pressScale(chip)
        }
        return chip
    }

    /**
     * The settings-group container: a flat [Theme.SURFACE_2] card that holds a
     * stack of [cardRow]s with no rules between them.
     *
     * Clipping is ON here (unlike a bordered card) because the rows inside paint
     * a full-bleed ripple: without the clip, pressing the first row squares off
     * the card's top corners for the length of the animation.
     */
    fun groupedCard(ctx: Context): LinearLayout {
        val card = column(ctx)
        card.background = Theme.roundRect(Theme.SURFACE_2, Theme.R_CARD, ctx)
        roundClip(card, Theme.R_CARD)
        val params = matchWrap()
        params.bottomMargin = Theme.dp(ctx, Space.M)
        card.layoutParams = params
        return card
    }

    /**
     * One row inside a [groupedCard]: outline glyph, title, optional grey
     * subtitle, optional [trailing] control (a `Switch`, a chevron, a value).
     *
     * The ripple is drawn with a ZERO radius on purpose — the parent card clips
     * it to the group's corners, so a rounded ripple here would leave an unlit
     * sliver at the top and bottom of the group.
     *
     * [trailing]'s existing `LinearLayout.LayoutParams` are reused when it has
     * them, so a caller can size a chevron or a switch itself and still get the
     * standard 12dp gap.
     */
    fun cardRow(
        ctx: Context,
        icon: String?,
        title: String,
        subtitle: String?,
        trailing: View?,
        onClick: Runnable?
    ): LinearLayout {
        val holder = row(ctx)
        // 58dp and a 12dp rhythm, both on the scale.
        //
        // 56dp with 10dp/14dp padding was three off-scale literals doing the job of
        // one step, and the row was the tightest thing on a settings screen made
        // almost entirely of rows. Space.M either side is the same visual result with
        // a number the rest of the app already uses.
        holder.minimumHeight = Theme.dp(ctx, 58.0f)
        holder.setPaddingRelative(
            Theme.dp(ctx, Space.L), Theme.dp(ctx, Space.M),
            Theme.dp(ctx, Space.L), Theme.dp(ctx, Space.M)
        )
        holder.background = Theme.rippleTransparent(0.0f, ctx)
        holder.layoutParams = matchWrap()

        if (icon != null) {
            val glyph = ImageView(ctx)
            // TEXT_MUTED, not TEXT.
            //
            // A row's glyph is not its subject — the label is. At full ink, twenty
            // 20dp glyphs down a settings screen are twenty marks competing with the
            // words beside them at exactly the same weight, which is most of why the
            // set read as "too dark". One level down is the same information, quieter,
            // and it matches every reference this interface is measured against.
            glyph.setImageDrawable(Icons.of(icon, Theme.TEXT_MUTED, STROKE))
            glyph.scaleType = ImageView.ScaleType.FIT_CENTER
            glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val glyphSize = Theme.dp(ctx, Space.XL)
            val glyphParams = LinearLayout.LayoutParams(glyphSize, glyphSize)
            glyphParams.marginEnd = Theme.dp(ctx, Space.L)
            holder.addView(glyph, glyphParams)
        }

        val stack = column(ctx)
        val titleView = text(ctx, title, Type.BODY, Theme.TEXT, Theme.ui())
        rowLabel(titleView)
        stack.addView(titleView, matchWrap())
        if (subtitle != null) {
            val subView = text(ctx, subtitle, Type.META, Theme.TEXT_MUTED, Theme.ui())
            rowLabel(subView)
            val subParams = matchWrap()
            subParams.topMargin = Theme.dp(ctx, 1.0f)
            stack.addView(subView, subParams)
        }
        holder.addView(stack, grow())

        if (trailing != null) {
            val existing = trailing.layoutParams
            val trailParams = if (existing is LinearLayout.LayoutParams) {
                existing
            } else {
                wrapWrap()
            }
            trailParams.marginStart = Theme.dp(ctx, Space.M)
            holder.addView(trailing, trailParams)
        }

        if (onClick != null) {
            holder.setOnClickListener { onClick.run() }
            pressScale(holder)
        }
        return holder
    }

    /**
     * The monochrome replacement for a coloured alert panel: a [Theme.SURFACE_2]
     * body with a 2dp rail down its START edge in [railColor]. Pair it with the
     * `"alert"` glyph and a `Theme.uiSemi()` label — weight and the rail carry
     * the warning now that every token is a grey.
     *
     * The layer order is the reverse of the obvious one: the RAIL is the bottom,
     * full-bleed layer and the SURFACE is inset 2dp on top of it, because
     * `LayerDrawable` stretches a layer to its bounds and cannot pin one to an
     * edge — `setLayerGravity` is API 23+ and outside the stub allowlist, so
     * "show 2dp of the layer underneath" is the only portable way to do this.
     *
     * Direction-aware by hand for the same reason: the stubbed `LayerDrawable`
     * exposes `setLayerInset` only, not `setLayerInsetRelative`, so the physical
     * side is chosen from [Lang.mirrored].
     */
    fun railPanel(ctx: Context, radius: Float, railColor: Int): Drawable {
        val rail = Theme.roundRect(railColor, radius, ctx)
        val body = Theme.roundRect(Theme.SURFACE_2, radius, ctx)
        val layers = LayerDrawable(arrayOf<Drawable>(rail, body))
        val railWidth = Math.max(1, Theme.dp(ctx, 2.0f))
        // Insets are PHYSICAL, so this has to ask which physical edge START is —
        // not which language is running. They were the same question only while
        // Persian mirrored the layout.
        if (Lang.mirrored(ctx)) {
            layers.setLayerInset(1, 0, 0, railWidth, 0)
        } else {
            layers.setLayerInset(1, railWidth, 0, 0, 0)
        }
        return layers
    }

    /**
     * Pill button in one of four variants, always with ripple feedback.
     *
     * All four are FLAT: a fill (or nothing), a label and an optional glyph.
     * PRIMARY carries no shadow — a solid black pill on an almost-white page is
     * already the loudest thing on screen, and the drop shadow it used to have
     * was the one place the flat system leaked depth. [primaryPill] is the
     * opt-in for a floating, elevated version.
     */
    fun pillButton(
        context: Context,
        label: String,
        icon: String?,
        variant: Int,
        onClick: View.OnClickListener?
    ): LinearLayout {
        val row = row(context)
        row.gravity = Gravity.CENTER
        val padV = Theme.dp(context, 11.0f)
        val padH = Theme.dp(context, Space.XL)
        row.setPadding(padH, padV, padH, padV)
        // TOUCH_DP, not 44: this is the app's standard button, and the constant a
        // few lines up calls 48dp "the minimum touch target Android asks for, and
        // the one every control here meets" — which was not true of the button
        // itself. The 11dp vertical padding is unchanged, so nothing reflows on a
        // button whose content already made it taller than the floor.
        row.minimumHeight = Theme.dp(context, TOUCH_DP)
        // Fully pill-shaped: the drawable clamps the radius to half the height,
        // so the same call reads correctly at 48dp and at 56dp.
        val radius = Theme.R_PILL

        val contentColor: Int = when (variant) {
            PRIMARY -> {
                row.background = Theme.actionButton(radius, context)
                Theme.ON_ACCENT
            }

            DANGER -> {
                // No red wash, no red ring: Theme.RED is near-black ink in this
                // palette, so the destructive action is a normal neutral pill
                // whose LABEL is the warning.
                row.background = Theme.rippleOver(
                    Theme.roundRect(Theme.SURFACE_2, radius, context), radius, context
                )
                Theme.RED
            }

            GHOST -> {
                row.background = Theme.rippleTransparent(radius, context)
                Theme.TEXT_MUTED
            }

            // SECONDARY and any unknown variant share the neutral treatment
            else -> {
                row.background = Theme.rippleOver(
                    Theme.roundRect(Theme.SURFACE_2, radius, context), radius, context
                )
                Theme.TEXT
            }
        }

        if (icon != null) {
            val glyph = ImageView(context)
            glyph.setImageDrawable(Icons.of(icon, contentColor, STROKE))
            glyph.scaleType = ImageView.ScaleType.FIT_CENTER
            val size = Theme.dp(context, 17.0f)
            val params = LinearLayout.LayoutParams(size, size)
            params.marginEnd = Theme.dp(context, Space.S)
            row.addView(glyph, params)
        }

        val label2 = text(context, label, Type.LABEL, contentColor, Theme.uiSemi())
        label2.setSingleLine(true)
        label2.ellipsize = android.text.TextUtils.TruncateAt.END
        row.addView(label2)

        if (onClick != null) {
            row.setOnClickListener(onClick)
            pressScale(row)
        }
        return row
    }

    /**
     * The floating primary pill — the drawer's "New chat" button. A [pillButton]
     * PRIMARY plus 3dp of elevation, so it reads as sitting above the list it
     * overlaps. The shadow inks come from [Theme.elevate] and are neutral black
     * washes; the outline is applied WITHOUT clipping, because the pill's own
     * ripple is already bounded and an un-antialiased clip steps its edge.
     */
    fun primaryPill(
        ctx: Context,
        label: String,
        icon: String?,
        onClick: Runnable?
    ): LinearLayout {
        val listener: View.OnClickListener? =
            onClick?.let { action -> View.OnClickListener { action.run() } }
        val pill = pillButton(ctx, label, icon, PRIMARY, listener)
        Theme.elevate(pill, 3.0f, Theme.R_PILL, false)
        return pill
    }

    /** Rounded square icon badge on a flat wash of the given colour. */
    fun iconBadge(
        context: Context,
        icon: String,
        color: Int,
        sizeDp: Float,
        iconDp: Float,
        radius: Float
    ): LinearLayout {
        val badge = LinearLayout(context)
        badge.gravity = Gravity.CENTER
        badge.background = Theme.iconChip(color, radius, context)
        val size = Theme.dp(context, sizeDp)
        badge.layoutParams = LinearLayout.LayoutParams(size, size)
        badge.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyph = ImageView(context)
        glyph.setImageDrawable(Icons.of(icon, color, STROKE))
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        val inner = Theme.dp(context, iconDp)
        badge.addView(glyph, LinearLayout.LayoutParams(inner, inner))
        return badge
    }

    /**
     * Small metadata pill: an optional 6dp dot, then a label. The neutral
     * variant ([tone] = 0) uses the quiet chip surface; any other tone washes
     * the pill in that tone — which, every token being a grey now, composites to
     * a grey too.
     */
    fun metaChip(context: Context, label: CharSequence, tone: Int, mono: Boolean): LinearLayout {
        val chip = row(context)
        val radius = Theme.R_PILL
        chip.background = if (tone == 0) {
            Theme.chip(radius, context)
        } else {
            Theme.iconChip(tone, radius, context)
        }
        val padH = Theme.dp(context, 9.0f)
        val padV = Theme.dp(context, Space.XS)
        chip.setPadding(padH, padV, padH, padV)
        if (tone != 0) {
            val dot = View(context)
            dot.background = Theme.circle(tone)
            val dotSize = Theme.dp(context, 6.0f)
            val dotLp = LinearLayout.LayoutParams(dotSize, dotSize)
            dotLp.marginEnd = Theme.dp(context, 6.0f)
            chip.addView(dot, dotLp)
        }
        val view = text(
            context, label, if (mono) 11.0f else Type.MICRO,
            if (tone == 0) Theme.TEXT_MUTED else tone,
            if (mono) Theme.mono() else Theme.uiSemi()
        )
        view.setSingleLine(true)
        view.ellipsize = android.text.TextUtils.TruncateAt.END
        if (mono) {
            view.textDirection = View.TEXT_DIRECTION_LTR
        }
        chip.addView(view)
        return chip
    }

    /**
     * Three pulsing dots — the app's one indeterminate progress indicator,
     * replacing the platform spinner (which cannot be themed on every OEM and
     * always looked borrowed).
     *
     * The animator is started on attach and cancelled on detach, so a row that
     * scrolls away or an Activity that is destroyed cannot leave it running.
     */
    fun pulseDots(context: Context, color: Int, dotDp: Float): LinearLayout {
        val holder = row(context)
        val size = Theme.dp(context, dotDp)
        val dots = ArrayList<View>(3)
        for (i in 0 until 3) {
            val dot = View(context)
            dot.background = Theme.circle(color)
            dot.alpha = 0.35f
            val lp = LinearLayout.LayoutParams(size, size)
            if (i > 0) {
                lp.marginStart = Theme.dp(context, dotDp * 0.6f)
            }
            holder.addView(dot, lp)
            dots.add(dot)
        }
        val animator = ValueAnimator.ofFloat(0.0f, 3.0f)
        animator.duration = 1080L
        animator.repeatCount = ValueAnimator.INFINITE
        animator.addUpdateListener { value ->
            val phase = value.animatedValue as Float
            for (i in dots.indices) {
                // Each dot peaks a third of a cycle after the one before it.
                var local = phase - i * 0.62f
                while (local < 0.0f) {
                    local += 3.0f
                }
                val wave = if (local < 1.0f) 1.0f - local else 0.0f
                dots[i].alpha = 0.32f + 0.68f * wave
                val scale = 0.82f + 0.28f * wave
                dots[i].scaleX = scale
                dots[i].scaleY = scale
            }
        }
        holder.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(target: View) {
                if (!animator.isStarted) {
                    animator.start()
                }
            }

            override fun onViewDetachedFromWindow(target: View) {
                animator.cancel()
            }
        })
        if (holder.isAttachedToWindow) {
            animator.start()
        }
        return holder
    }

    /** Accessibility label for the icon-only buttons. */
    /**
     * The spoken name of an icon-only control.
     *
     * Bilingual, because this is the ONLY name a screen reader has for every
     * icon-only button in the app — the send disc, the stop square, the overflow
     * dots, the reveal eye. It was a hard-coded English table, so a Persian user
     * with TalkBack on heard the entire interface announced in English while
     * reading it in Persian. There is no visible symptom, which is exactly why it
     * survived: nothing on screen changes, and only someone relying on it would
     * ever find out.
     *
     * Several of these have a [Fa] key already because they also appear as visible
     * labels elsewhere; the rest are [Lang.text] pairs, which is the documented
     * path for a string with exactly one call site.
     */
    private fun iconLabel(context: Context, icon: String): String = when (icon) {
        "send" -> Fa.SEND
        "stop" -> Fa.STOP
        "settings" -> Fa.SETTINGS
        "trash" -> Fa.DELETE
        "copy" -> Fa.COPY
        "folder" -> Lang.text(context, "Folder", "پوشه")
        "file" -> Lang.text(context, "File", "فایل")
        "menu" -> Lang.text(context, "Menu", "منو")
        "plus" -> Lang.text(context, "Add", "افزودن")
        "paperclip" -> Fa.ATTACH_FILE
        "at" -> Lang.text(context, "Pick file", "انتخاب فایل")
        "sun", "moon" -> Lang.text(context, "Toggle theme", "تغییر پوسته")
        "close", "x" -> Fa.CLOSE
        "eye", "eye-off" -> Lang.text(context, "Show or hide key", "نمایش یا پنهان کردن کلید")
        "check" -> Lang.text(context, "Select", "انتخاب")
        "minus" -> Fa.STOPPED
        "edit" -> Fa.EDIT
        "arrow-down" -> Lang.text(context, "Jump to latest", "رفتن به پایین")
        "sparkle" -> Fa.MODE_TITLE
        "more-vertical" -> Lang.text(context, "More options", "گزینه‌های بیشتر")
        "bulb", "bulb-on" -> Fa.TRAIL_THINKING
        "neuron" -> Fa.THINKING_LABEL
        // Whichever way it points, it means the same thing — and which way it
        // points is a function of the layout direction, not of the action.
        "chevron-left", "chevron-right" -> Lang.text(context, "Back", "بازگشت")
        else -> Lang.text(context, "Action", "عملیات")
    }

    /**
     * Insets a screen from the system bars WITHOUT destroying its own padding.
     *
     * ### The bug this exists to fix
     *
     * `view.fitsSystemWindows = true` looks like "keep my content out from under
     * the system bars", and it very nearly is — but the framework's default
     * implementation **REPLACES the view's padding** with the window insets. It
     * does not add to it. So a screen that set a 16dp side gutter and then asked
     * for `fitsSystemWindows` had that gutter silently overwritten with the
     * horizontal insets, which on an ordinary portrait phone are zero.
     *
     * That is why Settings looked correct on a Samsung A12 and wrong on a POCO:
     * whether it broke depended entirely on whether the platform DISPATCHED insets
     * to that view. On Android 12 with a non-edge-to-edge window the decor had
     * already consumed them, nothing reached the panel, and the padding survived by
     * accident. On Android 15 and 16 — and on MIUI/HyperOS, which forces
     * edge-to-edge earlier — the insets arrive, the padding is replaced with zeroes,
     * and every card in the screen runs into both edges of the glass.
     *
     * ### What this does instead
     *
     * Takes the padding the screen actually wants and ADDS the insets to it, so the
     * two compose instead of competing. The horizontal terms matter as much as the
     * vertical ones: a landscape display cutout, a curved edge, or a
     * gesture-navigation bar in landscape all report a left/right inset, and a
     * screen that only handled top and bottom would put content under the notch.
     *
     * Applied through a listener rather than `fitsSystemWindows` because a listener
     * is additive by construction — it is handed the insets and decides what to do
     * with them, which is the decision this app needs to make and the framework
     * default gets wrong.
     */
    fun applyWindowInsets(
        view: View,
        basePadStart: Int,
        basePadTop: Int,
        basePadEnd: Int,
        basePadBottom: Int
    ) {
        // Set the resting padding immediately. The listener may not fire at all on
        // a window that never dispatches insets, and a screen must never depend on
        // an inset callback to have its own margins.
        view.setPaddingRelative(basePadStart, basePadTop, basePadEnd, basePadBottom)
        view.fitsSystemWindows = false
        try {
            view.setOnApplyWindowInsetsListener { target, insets ->
                var top = 0
                var bottom = 0
                var left = 0
                var right = 0
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        val bars = insets.getInsets(
                            android.view.WindowInsets.Type.systemBars()
                        )
                        top = bars.top
                        bottom = bars.bottom
                        left = bars.left
                        right = bars.right
                    } else {
                        @Suppress("DEPRECATION")
                        top = insets.systemWindowInsetTop
                        @Suppress("DEPRECATION")
                        bottom = insets.systemWindowInsetBottom
                        @Suppress("DEPRECATION")
                        left = insets.systemWindowInsetLeft
                        @Suppress("DEPRECATION")
                        right = insets.systemWindowInsetRight
                    }
                } catch (ignored: Throwable) {
                    // An OEM that throws from its own inset plumbing must not take
                    // the screen down with it: the base padding is already applied,
                    // so the worst case is content under a bar rather than a crash.
                }
                // Physical left/right map onto start/end by the layout direction —
                // setPaddingRelative resolves against it, and these values do not.
                val insetStart = if (Lang.mirrored(target.context)) right else left
                val insetEnd = if (Lang.mirrored(target.context)) left else right
                target.setPaddingRelative(
                    basePadStart + insetStart,
                    basePadTop + top,
                    basePadEnd + insetEnd,
                    basePadBottom + bottom
                )
                insets
            }
            view.requestApplyInsets()
        } catch (ignored: Throwable) {
        }
    }

    fun roundClip(view: View, radius: Float) {
        roundOutline(view, radius)
        view.clipToOutline = true
    }

    /**
     * Gives a view a rounded outline — which is what the elevation shadow is
     * traced from — WITHOUT clipping its contents to it.
     *
     * Use this for a card that already paints its own rounded background and
     * border. Outline clipping is not anti-aliased, so enabling it on such a
     * card slices the outer edge of the border and produces the ragged rim that
     * is obvious against the light palette.
     */
    fun roundOutline(view: View, radius: Float) {
        val corner = Theme.dpf(view.context, radius)
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                outline.setRoundRect(0, 0, target.width, target.height, corner)
            }
        }
    }

    /**
     * Hairline rule in [Theme.BORDER]. Height is one *dp* (floored to a physical
     * pixel), not the literal `1` it used to be: on an xxxhdpi screen a single
     * pixel against the light palette's border colour is effectively invisible.
     *
     * Use it sparingly — this design groups by FILL, not by rule, so a divider
     * inside a grouped card is almost always the wrong answer.
     */
    fun divider(context: Context): View {
        val line = View(context)
        line.setBackgroundColor(Theme.BORDER)
        line.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Theme.hairline(context)
        )
        return line
    }

    /**
     * Section label: the small grey group header above a card or a list.
     *
     * It used to lead with a 3dp accent bar. That is gone — with an accent that
     * is simply "black", a leading tick on six headings just adds six marks to
     * the page. Plain muted text, aligned with the card's content, is the whole
     * treatment. The returned view is still a [LinearLayout] so the eleven call
     * sites are untouched; the label itself is `getChildAt(0)`.
     */
    fun sectionLabel(context: Context, label: String): LinearLayout {
        val row = row(context)

        // A HEADING, not a caption — and now a different SIZE from the prose it
        // introduces, not merely a different weight.
        //
        // It went from 12.5sp muted to 13sp semibold TEXT, which fixed the contrast
        // but left heading and footnote at exactly the same size, one step of weight
        // apart, both inset by the same 4dp. Nine of those down a column still read
        // as one undifferentiated list. LABEL over META is a real step in the type
        // scale, and it is the step every other heading in the app uses.
        val view = text(context, label, Type.LABEL, Theme.TEXT, Theme.uiSemi())
        // Slightly open, because a short heading in a semibold face at this size sets
        // tight enough to read as one word.
        //
        // Latin only. Persian is a JOINED script: letters within a word are drawn
        // connected, and tracking pushes those connections apart, so the same value
        // that opens up an English heading visibly breaks a Persian one into pieces.
        // Zero is not a compromise here — it is what correct letterform spacing in
        // this script already is.
        view.letterSpacing = if (Lang.farsi(context)) 0.0f else 0.01f
        rowLabel(view)
        // The start inset lives on the LABEL, not on the row, so it survives a
        // caller that supplies its own LayoutParams for the row.
        val labelParams = wrapWrap()
        labelParams.marginStart = Theme.dp(context, Space.XS)
        row.addView(view, labelParams)

        val params = matchWrap()
        // More air above than below, so a heading belongs to what FOLLOWS it. The
        // old 20/8 split read as evenly spaced, which is what let the eye attach a
        // heading to the group above.
        params.topMargin = Theme.dp(context, Space.XXL)
        params.bottomMargin = Theme.dp(context, Space.S)
        row.layoutParams = params
        return row
    }

    // ---- motion system ----------------------------------------------------
    //
    // One place that decides how this app moves, so a press, a toggle and a swap
    // share the same physics instead of each call site inventing its own
    // duration. Everything here is cheap: alpha, scale and translation only —
    // properties the compositor animates without a relayout. The budget is
    // deliberately tiny; there are no entrance animations any more.

    /** Duration scale: quick taps. */
    const val D_FAST = 120L

    /** Duration scale: the default for a visible state change. */
    const val D_BASE = 210L

    /** Duration scale: entrances and larger travel. */
    const val D_SLOW = 300L

    /**
     * The app's standard easing — a fast-out / slow-in curve. Motion starts
     * decisively and settles gently, which reads as "responsive" rather than
     * "floaty". Matches the platform's own emphasis curve closely enough that
     * app motion and system motion feel related.
     */
    fun ease(): android.view.animation.Interpolator =
        android.view.animation.PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f)

    /** Easing for something LEAVING the screen: gets out of the way quickly. */
    fun easeOut(): android.view.animation.Interpolator =
        android.view.animation.PathInterpolator(0.4f, 0.0f, 1.0f, 1.0f)

    /**
     * A gentle overshoot for confirmations — the thing you just switched ON
     * springs slightly past its resting size before settling.
     */
    fun spring(): android.view.animation.Interpolator =
        android.view.animation.OvershootInterpolator(1.6f)

    /**
     * Subtle press-scale on touch, with a light haptic tick on press.
     *
     * The scale is 0.97 — barely a nudge. At the old 0.955 a 56dp pill visibly
     * jumped, which is exactly the kind of motion this design has no budget
     * for; the haptic tick is what actually confirms the press.
     *
     * The listener returns false so the click still fires, and the scale is
     * reset on every pointer event that can end a gesture — previously a
     * two-finger tap could leave a button permanently shrunk.
     */
    fun pressScale(view: View) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(0.97f).scaleY(0.97f)
                        .setDuration(D_FAST)
                        .setInterpolator(ease())
                        .start()
                    tick(target)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_OUTSIDE -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(1.0f).scaleY(1.0f)
                        .setDuration(D_BASE)
                        .setInterpolator(spring())
                        .start()
                }
            }
            false
        }
    }

    /**
     * A short haptic tick. Wrapped because a device with haptics disabled — or
     * an OEM that throws from the vibrator service — must not take a tap down
     * with it.
     */
    fun tick(view: View) {
        try {
            view.performHapticFeedback(
                android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (ignored: Throwable) {
        }
    }

    /**
     * Cross-fades a view's content: fades out, runs [swap] to change what the
     * view shows, then fades back in. Used wherever a label/icon changes in
     * place (the mode pill, the theme toggle, the send/stop button) so the
     * change reads as a transition instead of a flicker.
     */
    fun swapContent(view: View, swap: Runnable) {
        view.animate().cancel()
        view.animate()
            .alpha(0.0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(D_FAST)
            .setInterpolator(easeOut())
            .withEndAction {
                try {
                    swap.run()
                } catch (ignored: Throwable) {
                }
                view.animate()
                    .alpha(1.0f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(D_BASE)
                    .setInterpolator(spring())
                    .start()
            }
            .start()
    }

    /**
     * Replaces a label's text as a hand-off: the outgoing line lifts and fades
     * away, the incoming one rises into its place.
     *
     * This is for STATUS text that is rewritten while the user is watching —
     * the review row's heading, its one-line digest, the running-tool pill.
     * Those used to change with a bare `text =`, which at a glance is
     * indistinguishable from a glitch: a sentence the eye was still reading is
     * simply a different sentence, with nothing to say a step completed.
     *
     * Asymmetric on purpose. The old line leaves in [D_FAST] because it is no
     * longer true, and the new one takes [D_BASE] to arrive because it is what
     * you are meant to read. They travel the same short distance in the same
     * direction, so the pair reads as one line being replaced from below rather
     * than two unrelated fades.
     *
     * Setting the same text twice is a no-op — the trail repaints on a timer,
     * and animating an unchanged string would leave the heading permanently
     * flickering.
     */
    fun swapText(label: TextView, next: CharSequence) {
        val current = label.text
        if (current != null && current.toString() == next.toString()) {
            return
        }
        // Nothing on screen yet, or the view is not attached: no transition to
        // make. Set it and leave, or the first paint fades in from nothing.
        if (current.isNullOrEmpty() || label.windowToken == null) {
            label.animate().cancel()
            label.alpha = 1.0f
            label.translationY = 0.0f
            label.text = next
            return
        }
        val travel = Theme.dpf(label.context, 6.0f)
        label.animate().cancel()
        label.animate()
            .alpha(0.0f)
            .translationY(-travel)
            .setDuration(D_FAST)
            .setInterpolator(easeOut())
            .withEndAction {
                label.text = next
                label.translationY = travel
                label.animate()
                    .alpha(1.0f)
                    .translationY(0.0f)
                    .setDuration(D_BASE)
                    .setInterpolator(ease())
                    .start()
            }
            .start()
        // The same guard reveal() carries: animate().cancel() drops a PENDING
        // withEndAction, so two swaps in quick succession could strand the label
        // at alpha 0 — an invisible heading that never comes back.
        label.postDelayed({
            if (label.alpha == 0.0f) {
                label.text = next
                label.alpha = 1.0f
                label.translationY = 0.0f
            }
        }, D_FAST + D_BASE + 90L)
    }

    /**
     * Animates a view in or out of the layout, fading and sliding a short
     * distance rather than snapping between VISIBLE and GONE.
     */
    fun reveal(view: View, show: Boolean) {
        val travel = Theme.dpf(view.context, Space.S)
        view.animate().cancel()
        if (show) {
            if (view.visibility == View.VISIBLE && view.alpha == 1.0f) {
                return
            }
            view.visibility = View.VISIBLE
            view.alpha = 0.0f
            view.translationY = -travel
            view.animate().alpha(1.0f).translationY(0.0f)
                .setDuration(D_BASE).setInterpolator(ease()).start()
        } else {
            if (view.visibility != View.VISIBLE) {
                return
            }
            view.animate().alpha(0.0f).translationY(-travel)
                .setDuration(D_FAST).setInterpolator(easeOut())
                .withEndAction {
                    view.visibility = View.GONE
                    view.translationY = 0.0f
                }
                .start()
            // Belt and braces — the same guard the copy panel and the sheets carry.
            //
            // The `animate().cancel()` at the top of this function drops a PENDING
            // withEndAction, so a hide interrupted by a second hide left the view
            // VISIBLE at alpha 0: invisible, but still occupying layout and still
            // taking taps. For the permission banners this drives, that is an
            // unseeable "Grant access" row quietly pushing the transcript down and
            // swallowing touches meant for the message underneath it.
            view.postDelayed({
                if (view.alpha == 0.0f && view.visibility == View.VISIBLE) {
                    view.visibility = View.GONE
                    view.translationY = 0.0f
                }
            }, D_FAST + 80L)
        }
    }
}
