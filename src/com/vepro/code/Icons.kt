package com.vepro.code

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Feather-style 24x24 line icons, stored as SVG path data and rasterised by a
 * tiny built-in path parser. Keeping the geometry in code means no drawable
 * XML, no vector-asset tooling and no dependency on androidx.
 */
object Icons {

    /** Icon name → SVG path data on a 24x24 viewport. */
    private val D: Map<String, String> = buildMap {
        put("menu", "M3 12h18M3 6h18M3 18h18")
        put(
            "settings",
            "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"
        )
        put("arrow-up", "M12 19V5M5 12l7-7 7 7")
        put("plus", "M12 5v14M5 12h14")
        put("x", "M18 6 6 18M6 6l12 12")
        // "stopped, not failed" — an interrupted step, and the quietest possible
        // mark for it. A cross says the tool said no; a bar says nobody knows.
        put("minus", "M5 12h14")
        put(
            "image",
            "M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2z M8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z M21 15l-5-5L5 21"
        )
        put(
            "paperclip",
            "M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"
        )
        put(
            "sun",
            "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10z M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"
        )
        put("moon", "M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z")
        put("check", "M20 6 9 17l-5-5")
        put("chevron-down", "M6 9l6 6 6-6")
        put("chevron-left", "M15 18l-6-6 6-6")
        put("chevron-right", "M9 18l6-6-6-6")
        put(
            "eye-off",
            "M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24M1 1l22 22"
        )
        put("lock", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z M7 11V7a5 5 0 0 1 10 0v4")
        put("sliders", "M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6")
        put("message", "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z")
        put(
            "server",
            "M20 3H4a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1h16a1 1 0 0 0 1-1V4a1 1 0 0 0-1-1z M20 15H4a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1h16a1 1 0 0 0 1-1v-4a1 1 0 0 0-1-1z M7 6.5h.01M7 18.5h.01"
        )
        put("tool", "M14.7 6.3a4 4 0 0 0-5.6 5.6L3 18l3 3 6.1-6.1a4 4 0 0 0 5.6-5.6l-2.9 2.9-2.1-2.1 2.9-2.9z")
        put("folder", "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z")
        put("file", "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6M16 13H8M16 17H8M10 9H8")
        put("search", "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z M21 21l-4.35-4.35")
        put(
            "cpu",
            "M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z M9 9h6v6H9z M9 1v3M15 1v3M9 20v3M15 20v3M20 9h3M20 14h3M1 9h3M1 14h3"
        )
        put("trash", "M3 6h18M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M10 11v6M14 11v6")
        put("edit", "M12 20h9M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4z")
        put("eye", "M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z")
        put("zap", "M13 2 3 14h9l-1 8 10-12h-9l1-8z")
        put("shield", "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z M9 12l2 2 4-4")
        put("terminal", "M4 17l6-6-6-6M12 19h8")
        put("alert", "M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z M12 9v4M12 17h.01")
        put("copy", "M9 9h11a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1z M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1")
        put("refresh", "M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15")
        put("play", "M5 3l14 9-14 9V3z")
        put("stop", "M6 6h12v12H6z")
        put("send", "M12 19V5M5 12l7-7 7 7")
        put("check-circle", "M22 11.08V12a10 10 0 1 1-5.93-9.14 M22 4 12 14.01l-3-3")
        put("plug", "M12 22v-5M9 8V2M15 8V2M6 8h12v3a5 5 0 0 1-5 5h-2a5 5 0 0 1-5-5V8z")
        put("music", "M9 18V5l12-2v13 M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0z M21 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z")
        put("video", "M23 7l-7 5 7 5V7z M14 5H3a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z")
        put("diamond", "M12 2l4 6-4 14-4-14 4-6z")
        put("list", "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01")
        put("help", "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3M12 17h.01")
        put("at", "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-3.92 7.94")
        put("corner-up-left", "M9 14L4 9l5-5 M4 9h11a4 4 0 0 1 4 4v3")
        put(
            "hard-drive",
            "M22 12H2 M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z M6 16h.01M10 16h.01"
        )
        put("file-plus", "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M12 18v-6 M9 15h6")
        // --- v9 additions -------------------------------------------------
        put("sparkle", "M12 3l1.9 4.9L19 9.8l-4.4 2.6L13.8 18 12 14.6 10.2 18l-.8-5.6L5 9.8l5.1-1.9L12 3z M19 16.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7.7-1.8z")
        put("arrow-down", "M12 5v14M19 12l-7 7-7-7")
        put("code", "M8 6l-6 6 6 6M16 6l6 6-6 6")
        put("gauge", "M12 21a9 9 0 1 1 9-9 M12 12l4.5-4.5 M12 12h.01")
        put("key", "M15.5 3a5.5 5.5 0 0 0-5.2 7.3L3 17.6V21h3.4l1.2-1.2v-2h2v-2h2l1.4-1.4A5.5 5.5 0 1 0 15.5 3z M17.5 7.5h.01")
        // Pushpin — the drawer's "keep this conversation at the top" mark. Feather
        // has no pin, so this is drawn to match its weight: a flat head, a
        // tapering body and a short stem.
        put("pin", "M9 3h6 M10 3v6l-3 4h10l-3-4V3 M12 17v4")
        put("layers", "M12 2 3 7l9 5 9-5-9-5z M3 12l9 5 9-5 M3 17l9 5 9-5")
        put("info", "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M12 16v-5 M12 8h.01")
        // The review section's mark. A lamp, not a dot: that row is where the
        // model works a request out, and a bulb says "an idea is forming" at a
        // glance in a way a circle never did. Three strokes — glass, collar,
        // base — so it still reads at 20dp.
        put(
            "bulb",
            "M9.2 18h5.6 M10.2 21.2h3.6 M12 2.6a6.4 6.4 0 0 0-3.8 11.6c.6.4 1 1.1 1.1 1.9h5.4c.1-.8.5-1.5 1.1-1.9A6.4 6.4 0 0 0 12 2.6z"
        )
        // The same lamp with its light on. Swapped in while the model is
        // actually reasoning, so the mark itself carries the state instead of
        // needing a separate spinner next to it.
        put(
            "bulb-on",
            "M9.2 18h5.6 M10.2 21.2h3.6 M12 5.4a5.6 5.6 0 0 0-3.3 10.1c.5.4.9 1 1 1.6h4.6c.1-.6.5-1.2 1-1.6A5.6 5.6 0 0 0 12 5.4z M12 1.2v1.7 M4.5 4.5l1.2 1.2 M19.5 4.5l-1.2 1.2 M1.4 12h1.7 M20.9 12h1.7"
        )
        // Model reasoning: three nodes and the paths between them — a thought
        // being worked out. Replaces the sparkle, which this app already spends
        // on "assistant mode" and which said "magic" where it should say "work".
        put(
            "neuron",
            "M4 12A2 2 0 1 0 8 12A2 2 0 1 0 4 12 M15.4 6.6A2 2 0 1 0 19.4 6.6A2 2 0 1 0 15.4 6.6 M15.4 17.4A2 2 0 1 0 19.4 17.4A2 2 0 1 0 15.4 17.4 M7.8 11.1 15.6 7.5 M7.8 12.9 15.6 16.5"
        )
        put("compass", "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M16.2 7.8l-2.4 6.4-6.4 2.4 2.4-6.4 6.4-2.4z")
        // The Telegram paper-plane — the classic mark with the folded crease.
        // Drawn as a filled glyph (Icons.filled) in Telegram blue for the
        // drawer's "Telegram" row.
        put(
            "telegram",
            "M9.78 18.65l.28-4.23 7.68-6.92c.34-.31-.07-.46-.52-.19L7.74 13.3 3.64 12c-.88-.25-.89-.86.2-1.3l15.97-6.16c.73-.33 1.43.18 1.15 1.3l-2.72 12.81c-.19.91-.74 1.13-1.5.71L12.6 16.3l-1.99 1.93c-.23.23-.42.42-.83.42z"
        )
        // Globe — the first-launch language chooser. Circle plus an equator and
        // two meridians, drawn as strokes (Icons.of).
        put(
            "globe",
            "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M2 12h20 M12 2a15 15 0 0 1 0 20 M12 2a15 15 0 0 0 0 20"
        )
        // Overflow menu — Feather's three dots at (12,5), (12,12), (12,19).
        // Emitted as near-zero-length relative horizontal segments: the ROUND
        // strokeCap turns each into a round dot of the stroke's own width, the
        // same idiom "list", "alert" and "server" already use for their dots.
        // Three real circles, not three zero-length segments.
        //
        // `M12 5h.01` is a dot only because the ROUND cap draws one, which makes its
        // diameter EXACTLY the stroke width — so at 3x density the overflow button
        // rendered as three hard 4px blobs and got heavier every time the stroke did.
        // Explicit geometry decouples the two, and this glyph is filled, so the dots
        // are the size they are drawn at and nothing else.
        put(
            "more-vertical",
            "M12 4.05a1.05 1.05 0 1 0 0 2.1 1.05 1.05 0 0 0 0-2.1z " +
                "M12 10.95a1.05 1.05 0 1 0 0 2.1 1.05 1.05 0 0 0 0-2.1z " +
                "M12 17.85a1.05 1.05 0 1 0 0 2.1 1.05 1.05 0 0 0 0-2.1z"
        )
    }

    /**
     * Glyphs whose shape IS the drawing, so stroking them would be wrong.
     *
     * A dot has no outline. Stroking one makes its size a function of the stroke
     * width, which is how the overflow button ended up as the heaviest small mark in
     * the interface. Listed here rather than left to call sites because a glyph that
     * must be filled must be filled *everywhere* — there are three call sites for
     * this one and they would have drifted.
     */
    private val ALWAYS_FILLED: Set<String> = setOf("more-vertical")

    /** Parsed paths, memoised by requested name (unknown names cache "help"). */
    private val CACHE = HashMap<String, Path>()

    private fun path(name: String): Path = CACHE.getOrPut(name) {
        SvgPath.parse(D[name] ?: D["help"])
    }

    /**
     * The default [strokeVp] is [Ui.STROKE], not a local literal. It used to be
     * 2.0f, which quietly outvoted the design system: any call site that omitted
     * the argument inherited a heavier outline than the one every [Ui] factory
     * draws, so glyphs sitting side by side disagreed for no stated reason.
     * There is deliberately only one width — see [Ui.STROKE].
     */
    fun of(name: String, color: Int, strokeVp: Float = Ui.STROKE): Drawable =
        IconDrawable(path(name), color, strokeVp, ALWAYS_FILLED.contains(name))

    fun filled(name: String, color: Int): Drawable =
        IconDrawable(path(name), color, 0.0f, true)

    fun view(context: Context, name: String, sizeDp: Float, color: Int): ImageView {
        val image = ImageView(context)
        image.setImageDrawable(of(name, color))
        val size = Theme.dp(context, sizeDp)
        image.layoutParams = LinearLayout.LayoutParams(size, size)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        return image
    }

    /** Draws a 24x24 viewport path centred and uniformly scaled into its bounds. */
    class IconDrawable(
        private val src: Path,
        iconColor: Int,
        private val strokeVp: Float,
        private val filled: Boolean
    ) : Drawable() {

        // Built explicitly rather than with apply {}: inside an apply block the
        // name `color` would resolve to Paint.color, not the constructor param.
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            paint.color = iconColor
            paint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val box = bounds
            if (box.width() <= 0 || box.height() <= 0) {
                return
            }
            val scale = Math.min(box.width(), box.height()) / 24.0f
            val save = canvas.save()
            val extent = 24.0f * scale
            // Snapped to whole pixels.
            //
            // The glyph is a 24-unit drawing scaled into an arbitrary box, so its
            // origin landed on a fractional pixel more often than not — and a
            // 1.7px stroke straddling two pixel columns is drawn as two half-lit
            // columns instead of one solid one. That is the "low quality, blurry"
            // look: nothing about the geometry was wrong, every edge was simply
            // being anti-aliased across a seam it did not need to cross.
            //
            // Rounding the translation is the whole fix. It costs at most half a
            // pixel of centring error, which is invisible, and it buys a crisp
            // edge on every stroke in the app.
            canvas.translate(
                Math.round(box.left + ((box.width() - extent) / 2.0f)).toFloat(),
                Math.round(box.top + ((box.height() - extent) / 2.0f)).toFloat()
            )
            canvas.scale(scale, scale)
            if (!filled) {
                // Divide the canvas scale back out, so the outline lands at
                // `strokeVp` DP on screen whatever size the glyph is drawn at.
                //
                // Assigning strokeVp directly — inside a canvas already scaled by
                // `boxSize / 24` — made the rendered width `strokeVp * boxDp / 24`,
                // i.e. it tracked the glyph's size. Across the sizes this app
                // actually uses that was a 57% spread from one glyph to the next: a
                // 14dp chevron drew at 1.11dp while a 22dp suggestion glyph drew at
                // 1.74dp, so small glyphs read visibly thinner and greyer than large
                // ones. That is the exact disagreement Ui.STROKE's own doc comment
                // says the constant exists to prevent.
                // On top of the density correction, an OPTICAL one — and it now runs
                // in BOTH directions, because "the same width on a smaller glyph"
                // is not the same weight.
                //
                // Held at 1.0 below the anchor, a 14dp glyph carried the same
                // measured stroke as a 20dp one — which is 43% more ink relative to
                // the shape it outlines. That is what "the icons are too thick" was
                // describing: not the constant, but the constant applied to the
                // 14-18dp sizes this interface is almost entirely built from. The
                // ramp keeps the stroke a near-constant PROPORTION of the glyph, so
                // a small button and a large one read as the same weight.
                //
                // The previous attempt at a small-glyph ramp went the wrong way,
                // ADDING up to 12% on the theory that a shrinking shape reads
                // thinner. It stacked on a nominal width that was already heavy and
                // the whole icon set came out inked.
                val sizeDp = if (Theme.DENSITY > 0.0f) {
                    Math.min(box.width(), box.height()) / Theme.DENSITY
                } else {
                    ANCHOR_DP
                }
                val ramp = when {
                    sizeDp <= 0.0f -> MAX_OPTICAL
                    sizeDp >= ANCHOR_DP -> 1.0f - (sizeDp - ANCHOR_DP) * FALL_PER_DP
                    else -> 1.0f - (ANCHOR_DP - sizeDp) * RISE_PER_DP
                }
                // Clamped at BOTH ends, and MAX_OPTICAL is what makes the promise in
                // Ui.STROKE's doc enforceable rather than aspirational: whatever the
                // arithmetic above produces, no glyph is ever drawn heavier than the
                // nominal width. It used to be a declared constant that nothing read.
                val optical = Math.min(MAX_OPTICAL, Math.max(MIN_OPTICAL, ramp))
                val onScreen = strokeVp * Theme.DENSITY * optical
                // Snapped to HALF pixels, not whole ones.
                //
                // Whole-pixel snapping is what made a thin line crisp, and it also
                // quantised the entire ramp out of existence: at 3x density every
                // size from 14dp to 20dp rounded to the same 4px, so the correction
                // above could not have any effect at all. A half-pixel grid keeps
                // the crispness that matters — the stroke's CENTRE still lands on a
                // predictable subdivision, and the origin is snapped whole — while
                // leaving enough resolution for the sizes actually in use to differ.
                val snapped = Math.max(
                    MIN_PIXELS, Math.round(onScreen * 2.0f).toFloat() / 2.0f
                )
                paint.strokeWidth = if (scale > 0.0f) snapped / scale else snapped
            }
            canvas.drawPath(src, paint)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = 24

        override fun getIntrinsicHeight(): Int = 24

        fun setColor(color: Int) {
            paint.color = color
            invalidateSelf()
        }

        private companion object {
            /** Glyph size the nominal stroke width is authored for. */
            const val ANCHOR_DP = 20.0f

            /** Relief per dp ABOVE the anchor, where a fixed width reads heavy. */
            const val FALL_PER_DP = 0.008f

            /**
             * Relief per dp BELOW the anchor, where a fixed width reads heavier
             * still: a 14dp glyph is 30% smaller than the anchor but was drawn with
             * 100% of its stroke.
             */
            const val RISE_PER_DP = 0.022f

            /**
             * The ramp can only ever lighten, so the ceiling is exactly 1.0 — the
             * nominal width is the heaviest any glyph is drawn.
             */
            const val MAX_OPTICAL = 1.0f

            /** Floor on the correction, so a 10dp glyph never disappears. */
            const val MIN_OPTICAL = 0.84f

            /**
             * Never thinner than this on screen.
             *
             * One pixel, not more: at 1x density the arithmetic lands near 1px on
             * purpose, and forcing a floor above it would make the LOWEST-density
             * devices the heaviest, which is backwards.
             */
            const val MIN_PIXELS = 1.0f
        }
    }

    /**
     * Minimal SVG path-data parser: supports M/L/H/V/C/S/Q/T/A/Z in both
     * absolute and relative form, implicit command repetition, and elliptical
     * arcs converted to [Path.arcTo].
     */
    internal object SvgPath {

        private const val TWO_PI = 6.283185307179586

        fun parse(data: String?): Path {
            val path = Path()
            if (data == null) {
                return path
            }
            val tokens = tokenize(data)

            var i = 0
            var command = '\u0000'      // active command (persists for implicit repeats)
            var prevUpper = '\u0000'    // previous command, upper-cased (S/T reflection)
            var cx = 0.0f               // current point
            var cy = 0.0f
            var startX = 0.0f           // current sub-path start (for Z)
            var startY = 0.0f
            var cubicCtrlX = 0.0f       // last cubic control point (for S)
            var cubicCtrlY = 0.0f
            var quadCtrlX = 0.0f        // last quadratic control point (for T)
            var quadCtrlY = 0.0f

            while (i < tokens.size) {
                val token = tokens[i]
                if (token is Char) {
                    command = token
                    i++
                }
                val active = command
                val relative = Character.isLowerCase(active)
                val upper = Character.toUpperCase(active)

                when (upper) {
                    'M' -> {
                        var x = num(tokens, i)
                        var y = num(tokens, i + 1)
                        if (relative) {
                            x += cx
                            y += cy
                        }
                        cx = x
                        cy = y
                        path.moveTo(cx, cy)
                        // an implicit repeat after a moveto is a lineto
                        command = if (relative) 'l' else 'L'
                        startX = cx
                        startY = cy
                        i += 2
                    }

                    'L' -> {
                        var x = num(tokens, i)
                        var y = num(tokens, i + 1)
                        if (relative) {
                            x += cx
                            y += cy
                        }
                        cx = x
                        cy = y
                        path.lineTo(cx, cy)
                        i += 2
                    }

                    'H' -> {
                        var x = num(tokens, i)
                        if (relative) {
                            x += cx
                        }
                        cx = x
                        path.lineTo(cx, cy)
                        i += 1
                    }

                    'V' -> {
                        var y = num(tokens, i)
                        if (relative) {
                            y += cy
                        }
                        cy = y
                        path.lineTo(cx, cy)
                        i += 1
                    }

                    'C' -> {
                        var x1 = num(tokens, i)
                        var y1 = num(tokens, i + 1)
                        var x2 = num(tokens, i + 2)
                        var y2 = num(tokens, i + 3)
                        var x = num(tokens, i + 4)
                        var y = num(tokens, i + 5)
                        if (relative) {
                            x1 += cx; y1 += cy
                            x2 += cx; y2 += cy
                            x += cx; y += cy
                        }
                        cubicCtrlX = x2
                        cubicCtrlY = y2
                        path.cubicTo(x1, y1, cubicCtrlX, cubicCtrlY, x, y)
                        cx = x
                        cy = y
                        i += 6
                    }

                    'S' -> {
                        var x2 = num(tokens, i)
                        var y2 = num(tokens, i + 1)
                        var x = num(tokens, i + 2)
                        var y = num(tokens, i + 3)
                        if (relative) {
                            x2 += cx; y2 += cy
                            x += cx; y += cy
                        }
                        // first control point is the reflection of the previous
                        // one when the preceding command was also a cubic
                        val smooth = prevUpper == 'C' || prevUpper == 'S'
                        val ctrl1X = if (smooth) (cx * 2.0f) - cubicCtrlX else cx
                        val ctrl1Y = if (smooth) (cy * 2.0f) - cubicCtrlY else cy
                        path.cubicTo(ctrl1X, ctrl1Y, x2, y2, x, y)
                        cubicCtrlX = x2
                        cubicCtrlY = y2
                        cx = x
                        cy = y
                        i += 4
                    }

                    'Q' -> {
                        var x1 = num(tokens, i)
                        var y1 = num(tokens, i + 1)
                        var x = num(tokens, i + 2)
                        var y = num(tokens, i + 3)
                        if (relative) {
                            x1 += cx; y1 += cy
                            x += cx; y += cy
                        }
                        cx = x
                        cy = y
                        path.quadTo(x1, y1, cx, cy)
                        quadCtrlX = x1
                        quadCtrlY = y1
                        i += 4
                    }

                    'T' -> {
                        var x = num(tokens, i)
                        var y = num(tokens, i + 1)
                        if (relative) {
                            x += cx
                            y += cy
                        }
                        val smooth = prevUpper == 'Q' || prevUpper == 'T'
                        val ctrlX = if (smooth) (cx * 2.0f) - quadCtrlX else cx
                        val ctrlY = if (smooth) (cy * 2.0f) - quadCtrlY else cy
                        path.quadTo(ctrlX, ctrlY, x, y)
                        quadCtrlX = ctrlX
                        quadCtrlY = ctrlY
                        cx = x
                        cy = y
                        i += 2
                    }

                    'A' -> {
                        val rx = num(tokens, i)
                        val ry = num(tokens, i + 1)
                        val rotation = num(tokens, i + 2)
                        val largeArc = num(tokens, i + 3) != 0.0f
                        val sweep = num(tokens, i + 4) != 0.0f
                        var x = num(tokens, i + 5)
                        var y = num(tokens, i + 6)
                        if (relative) {
                            x += cx
                            y += cy
                        }
                        arc(path, cx, cy, rx, ry, rotation, largeArc, sweep, x, y)
                        cx = x
                        cy = y
                        i += 7
                    }

                    'Z' -> {
                        path.close()
                        // tolerate a stray number directly after a close
                        if (i < tokens.size && tokens[i] !is Char) {
                            i++
                        }
                        cx = startX
                        cy = startY
                    }

                    else -> i++ // unknown token: skip it
                }
                prevUpper = upper
            }
            return path
        }

        /** Reads the number at [index], or 0 when it is missing or not numeric. */
        private fun num(tokens: List<Any>, index: Int): Float {
            if (index < 0 || index >= tokens.size) {
                return 0.0f
            }
            val token = tokens[index]
            return if (token is Float) token else 0.0f
        }

        /** Splits path data into command chars and floats. */
        private fun tokenize(data: String): List<Any> {
            val tokens = ArrayList<Any>()
            val length = data.length
            var i = 0
            while (i < length) {
                val ch = data[i]
                if (Character.isLetter(ch)) {
                    tokens.add(ch)
                    i++
                } else if (ch == ' ' || ch == ',' || ch == '\t' || ch == '\n' || ch == '\r') {
                    i++
                } else {
                    var end = if (data[i] == '+' || data[i] == '-') i + 1 else i
                    var seenDot = false
                    while (end < length) {
                        val c = data[end]
                        if (c in '0'..'9') {
                            end++
                        } else if (c == '.' && !seenDot) {
                            end++
                            seenDot = true
                        } else if (c == 'e' || c == 'E') {
                            end++
                            if (end < length && (data[end] == '+' || data[end] == '-')) {
                                end++
                            }
                        } else {
                            break
                        }
                    }
                    if (end == i) {
                        i++
                    } else {
                        try {
                            tokens.add(data.substring(i, end).toFloat())
                        } catch (e: Exception) {
                            tokens.add(0.0f)
                        }
                        i = end
                    }
                }
            }
            return tokens
        }

        /**
         * Endpoint-parameterised elliptical arc → centre parameterisation, then
         * [Path.arcTo]. Degenerate radii collapse to a straight line, per spec.
         *
         * Intermediate radii stay `Float` (as in the original) so the widening
         * points, and therefore the rounding, are unchanged.
         */
        private fun arc(
            path: Path,
            x1: Float,
            y1: Float,
            rxIn: Float,
            ryIn: Float,
            rotationDeg: Float,
            largeArc: Boolean,
            sweep: Boolean,
            x2: Float,
            y2: Float
        ) {
            if (rxIn == 0.0f || ryIn == 0.0f) {
                path.lineTo(x2, y2)
                return
            }
            var rx = Math.abs(rxIn)
            var ry = Math.abs(ryIn)

            val radians = Math.toRadians(rotationDeg.toDouble() % 360.0)
            val cos = Math.cos(radians)
            val sin = Math.sin(radians)

            val halfDx = (x1 - x2) / 2.0
            val halfDy = (y1 - y2) / 2.0
            val xp = (cos * halfDx) + (sin * halfDy)
            val yp = (-sin * halfDx) + (halfDy * cos)

            var rx2 = (rx * rx).toDouble()
            var ry2 = (ry * ry).toDouble()
            val xp2 = xp * xp
            val yp2 = yp * yp

            // scale the radii up when they are too small to span the endpoints
            val lambda = (xp2 / rx2) + (yp2 / ry2)
            if (lambda > 1.0) {
                val scale = Math.sqrt(lambda)
                rx = (rx * scale).toFloat()
                ry = (ry * scale).toFloat()
                rx2 = (rx * rx).toDouble()
                ry2 = (ry * ry).toDouble()
            }

            val sign = if (largeArc == sweep) -1.0 else 1.0
            val numerator = ((rx2 * ry2) - (rx2 * yp2)) - (ry2 * xp2)
            var denominator = (rx2 * yp2) + (ry2 * xp2)
            if (denominator == 0.0) {
                denominator = 1.0E-9
            }
            val coefficient = sign * Math.sqrt(Math.max(0.0, numerator / denominator))

            val rxD = rx.toDouble()
            val ryD = ry.toDouble()
            val cxp = ((rxD * yp) / ryD) * coefficient
            val cyp = coefficient * ((-ry * xp) / rxD)

            val centerX = ((cos * cxp) - (sin * cyp)) + ((x1 + x2) / 2.0)
            val centerY = (sin * cxp) + (cos * cyp) + ((y1 + y2) / 2.0)

            val ux = (xp - cxp) / rxD
            val uy = (yp - cyp) / ryD
            val startAngle = angle(1.0, 0.0, ux, uy)
            var sweepAngle = angle(ux, uy, ((-xp) - cxp) / rxD, ((-yp) - cyp) / ryD)

            if (!sweep && sweepAngle > 0.0) {
                sweepAngle -= TWO_PI
            } else if (sweep && sweepAngle < 0.0) {
                sweepAngle += TWO_PI
            }

            path.arcTo(
                RectF(
                    (centerX - rxD).toFloat(),
                    (centerY - ryD).toFloat(),
                    (centerX + rxD).toFloat(),
                    (centerY + ryD).toFloat()
                ),
                Math.toDegrees(startAngle).toFloat(),
                Math.toDegrees(sweepAngle).toFloat()
            )
        }

        /** Signed angle between two vectors. */
        private fun angle(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val dot = (ax * bx) + (ay * by)
            var magnitude = Math.sqrt(((ax * ax) + (ay * ay)) * ((bx * bx) + (by * by)))
            if (magnitude == 0.0) {
                magnitude = 1.0E-9
            }
            val theta = Math.acos(Math.max(-1.0, Math.min(1.0, dot / magnitude)))
            return if (((ax * by) - (ay * bx)) < 0.0) -theta else theta
        }
    }
}
