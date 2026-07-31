package com.vepro.code

import android.os.SystemClock
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.Choreographer
import android.widget.TextView

/**
 * Word-by-word reveal for streamed model output.
 *
 * Each word arrives dim and lifts to full opacity over [DURATION_MS]. Because
 * every word carries its own birth time, a burst of tokens lands as a short
 * staggered cascade rather than all at once, which is what makes the effect
 * read as *writing* instead of *painting*.
 *
 * ### Opacity only — never blur
 *
 * This used to fade each word up from behind a 6dp zero-offset shadow halo, so
 * a word spent the first half of its life as an unreadable smear. On a phone,
 * at body-text size, in a script as dense as Persian, that reads as the screen
 * being out of focus rather than as text arriving — and it is also the most
 * expensive thing you can ask of the text pipeline, because a shadow layer
 * re-rasterises the glyph every frame for every word in flight, sixty times a
 * second.
 *
 * So the reveal is a pure alpha ramp. The glyph is sharp from its very first
 * frame and simply gains presence; it costs one integer write per word per
 * frame, and it stays legible the whole way in.
 *
 * A word is drawn as: crisp glyph at [START_ALPHA] (t=0) → crisp glyph rising
 * (0<t<1) → the plain glyph, span removed entirely (t=1).
 */
object StreamReveal {

    /**
     * How long one word takes to resolve.
     *
     * 1800ms, after 1500, 900, 340 and originally 220.
     *
     * The number stopped being the interesting part at 900. See [ease] — the curve
     * was compressing every one of these values into the same visible quarter-
     * second, which is why raising it three times changed almost nothing. With an
     * even ramp this figure is what you actually see.
     *
     * Each successive value was chosen for the same reason and did not go far
     * enough. 340ms is about twenty frames: long enough to measure, short enough
     * that the eye still reads it as a word APPEARING rather than as one being
     * written. And because the ramp was shorter than the gap between token
     * batches, each burst finished resolving before the next arrived — so the
     * effect was a sequence of discrete events, which is exactly the "fast and
     * chunky" reading in the report even though every frame was being drawn.
     *
     * Smoothness here is not a frame-rate problem. It is an OVERLAP problem: the
     * animation reads as continuous when a word is still resolving while its
     * neighbours begin, so that at any instant several words are at different
     * opacities and the boundary between written and unwritten is a soft gradient
     * rather than a hard edge. At 900ms against a typical 40-80ms token cadence,
     * ten or more words are always in flight — the text arrives as a wash moving
     * across the paragraph instead of word by word.
     *
     * It costs nothing extra: the work is one alpha write per in-flight word per
     * frame, and a longer ramp does not add frames, it only keeps more spans
     * alive across them. The eased curve below still brings a word to legible
     * weight in the first quarter of its life, so reading never waits on the
     * animation — you can read the tail while it is still settling.
     */
    const val DURATION_MS = 1800L

    /**
     * Extra delay applied per word inside a single flush, for the cascade.
     *
     * 110ms, after 95, 55, 26 and originally 16.
     *
     * This is what makes a burst read as writing rather than as a paste. A
     * provider hands over several words at once, and if they all start together
     * they all finish together — one visible event, however long the ramp. At
     * 95ms — roughly six frames — a flush of six words spreads over half a second,
     * and because that is comfortably inside [DURATION_MS] they overlap heavily: the
     * first word is barely a third resolved as the sixth begins, which is the soft
     * leading edge the whole effect depends on.
     */
    private const val STAGGER_MS = 110L

    /**
     * Longest cascade a single flush may spread over.
     *
     * Without a cap, a provider that delivers a whole paragraph in one chunk would
     * queue forty words at the full stagger and the last of them would still be
     * fading in two seconds after it arrived. Past this many words the stagger is
     * compressed so the tail always resolves promptly.
     *
     * Equal to [DURATION_MS], and it has to STAY equal — the cap is otherwise the
     * thing that flattens the cascade. At 420ms against a 900ms ramp a sixteen-word
     * burst compressed to a 26ms stagger, which is where the chunkiness came from on
     * providers that batch heavily: the ramp was long, the stagger was not, and the
     * whole burst still landed as one event. Equal to the ramp means the longest
     * possible cascade is one word's lifetime — the tail is never more than one ramp
     * behind the text, which is the honest bound.
     */
    private const val MAX_CASCADE_MS = 1800L

    /**
     * Opacity a word starts at, 0..1.
     *
     * Zero now, where it used to be 0.28. Starting at 28% was a hedge against a
     * word "popping", but with a ramp this short it produced the opposite
     * problem: every word appeared instantly at a legible-but-grey weight and
     * then merely firmed up, so the animation read as a flicker of grey text
     * rather than as writing. From zero, with the eased curve below, the first
     * third of the ramp does the visible work and the word arrives rather than
     * blinks.
     */
    private const val START_ALPHA = 0.0f

    /**
     * Alpha curve — smoothstep, `3t² − 2t³`.
     *
     * ### Why this changed, and why it is the actual fix
     *
     * This was a quintic ease-OUT, chosen so a word would reach readable weight
     * early and "not pay for the longer duration". It did that far too well: a
     * quintic ease-out is at 67% by t=0.25 and 97% by t=0.5, so whatever
     * [DURATION_MS] said, the visible part of the fade was over in the first
     * quarter of it. Raising the duration from 340ms to 900ms and then to 1500ms
     * barely changed what anybody saw, because the curve was spending 75% of every
     * ramp creeping between 97% and 100% opacity — invisible work. The animation
     * was reported as "still too fast" three times, and each time the number was
     * raised and the curve was left alone.
     *
     * Smoothstep spreads the change EVENLY: 16% at t=0.25, 50% at t=0.5, 84% at
     * t=0.75. So the perceived fade lasts as long as the ramp actually does, and
     * [DURATION_MS] finally means what it claims. It is also gentler at both ends
     * than a linear ramp, which is what stops a word from starting and stopping
     * abruptly — the two places a fade reads as mechanical.
     *
     * The cost is that a word is only half-visible halfway through its life, so
     * the leading edge of the text is genuinely soft rather than merely settling.
     * That is the effect being asked for.
     */
    private fun ease(t: Float): Float {
        val x = Math.max(0.0f, Math.min(1.0f, t))
        // 3x^2 - 2x^3
        return x * x * (3.0f - 2.0f * x)
    }

    /**
     * One word in flight. Removes itself from the paint's point of view as
     * soon as it has resolved, so a finished word costs exactly nothing.
     */
    class WordSpan(private val bornAt: Long) : CharacterStyle(), UpdateAppearance {

        /** True once this word has fully resolved and the span can be dropped. */
        fun done(now: Long): Boolean = now - bornAt >= DURATION_MS

        override fun updateDrawState(tp: TextPaint) {
            val elapsed = SystemClock.uptimeMillis() - bornAt
            if (elapsed >= DURATION_MS) {
                return
            }
            if (elapsed < 0L) {
                // Staggered into the future: hold at the starting opacity
                // rather than at zero, so a long burst never leaves a ragged
                // hole at the end of the line.
                tp.alpha = Math.round(255.0f * START_ALPHA)
                return
            }
            val t = ease(elapsed.toFloat() / DURATION_MS.toFloat())
            val level = START_ALPHA + (1.0f - START_ALPHA) * t
            tp.alpha = Math.round(255.0f * Math.min(1.0f, level))
        }
    }

    /**
     * Per-TextView reveal state. The streaming renderer rebuilds the tail's
     * `Spanned` from scratch on every flush, so birth times cannot live on the
     * spans — they live here, indexed by word ordinal, and are re-applied to
     * the freshly built text each time.
     */
    class Session {

        /** Birth time of word *i* of the current tail, in uptime millis. */
        private val born = ArrayList<Long>(64)

        private var view: TextView? = null
        private var ticking = false

        /**
         * Uptime by which every currently-stamped word has finished resolving.
         *
         * The frame loop is gated on this. Without it [tick] re-armed itself
         * unconditionally, so the very first flush started a 16ms
         * invalidate-the-whole-TextView loop that then ran until [detach] — i.e.
         * for the entire rest of the turn, right through every pause between token
         * bursts, with nothing left on screen to animate. On a long answer that is
         * thousands of pointless full-text redraws and the battery cost of a
         * 60fps animation for something that is visually static.
         */
        private var animatingUntil = 0L

        /**
         * One animation frame, driven by [Choreographer].
         *
         * Was a `Handler.postDelayed(16ms)` loop. 16ms is *approximately* a frame
         * on a 60Hz panel and nothing at all on the 90Hz and 120Hz panels most
         * phones now ship: the callback and the display's refresh drift in and out
         * of phase, so a ramp that is mathematically smooth is SAMPLED unevenly and
         * the result stutters. That is the largest single cause of the choppiness
         * in the report, and it cannot be fixed by adjusting durations.
         *
         * Choreographer delivers exactly one callback per real frame, at whatever
         * rate the display actually runs, so each frame samples the curve at the
         * moment it is drawn.
         */
        private val frame = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                ticking = false
                val target = view ?: return
                target.invalidate()
                // Keep the loop alive only while a word is still resolving. New
                // words re-arm it from apply().
                if (SystemClock.uptimeMillis() < animatingUntil) {
                    schedule()
                }
            }
        }

        /** Drops every stamp — used when the stream rewinds or restarts. */
        fun reset() {
            born.clear()
        }

        fun detach() {
            Choreographer.getInstance().removeFrameCallback(frame)
            ticking = false
            animatingUntil = 0L
            view = null
            // Stamps are per-tail and the tail is gone. Keeping them made a
            // re-attach inherit stale, already-finished birth times and skip
            // the reveal for the whole first flush.
            born.clear()
        }

        /**
         * Stamps and spans [text] in place. Words already known keep their
         * original birth time; words seen for the first time are stamped now,
         * staggered so a burst cascades instead of flashing.
         *
         * Only words that are still resolving get a span at all, so the cost
         * per flush is proportional to the handful of words in flight — not to
         * the length of the answer.
         */
        fun apply(target: TextView, text: Spannable) {
            view = target
            val now = SystemClock.uptimeMillis()
            val length = text.length
            var index = 0
            var word = 0
            var fresh = 0
            var animating = false
            // How far this flush's cascade may spread. A provider that delivers a
            // whole paragraph at once would otherwise queue forty words at the full
            // stagger and leave the last of them fading in a second after it
            // arrived — the tail would visibly lag the text. Compressing the stagger
            // for a large burst keeps the cascade's TOTAL length bounded, so the
            // effect degrades to "arrives together, smoothly" rather than "arrives
            // late".
            val incoming = Math.max(1, countWords(text) - born.size)
            val stagger = Math.min(
                STAGGER_MS, Math.max(1L, MAX_CASCADE_MS / incoming.toLong())
            )

            while (index < length) {
                // Leading whitespace belongs to the word that follows it, so a
                // word and the gap before it resolve together.
                val start = index
                while (index < length && isSpace(text[index])) {
                    index++
                }
                if (index >= length) {
                    break
                }
                while (index < length && !isSpace(text[index])) {
                    index++
                }
                val end = index

                val bornAt: Long
                if (word < born.size) {
                    bornAt = born[word]
                } else {
                    bornAt = now + fresh * stagger
                    born.add(bornAt)
                    fresh++
                }
                word++

                if (now - bornAt < DURATION_MS) {
                    animating = true
                    if (bornAt + DURATION_MS > animatingUntil) {
                        animatingUntil = bornAt + DURATION_MS
                    }
                    text.setSpan(
                        WordSpan(bornAt), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            // The tail shrank (a fence closed, a paragraph was committed):
            // forget the stamps past the end so the next words are treated as
            // new rather than inheriting a stale, already-finished time.
            while (born.size > word) {
                born.removeAt(born.size - 1)
            }

            if (animating) {
                schedule()
            }
        }

        private fun schedule() {
            if (ticking || view == null) {
                return
            }
            ticking = true
            Choreographer.getInstance().postFrameCallback(frame)
        }

        /** Words in [text], by the same rule [apply] splits them with. */
        private fun countWords(text: CharSequence): Int {
            var count = 0
            var i = 0
            val length = text.length
            while (i < length) {
                while (i < length && isSpace(text[i])) {
                    i++
                }
                if (i >= length) {
                    break
                }
                while (i < length && !isSpace(text[i])) {
                    i++
                }
                count++
            }
            return count
        }

        private fun isSpace(c: Char): Boolean =
            c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == ' ' || c == '‌'
    }

    /**
     * A fresh reveal session.
     *
     * No Handler any more: the frame loop is a [Choreographer] callback, which is
     * already bound to the looper of the thread that posts it — and the only
     * thread that ever touches a TextView is the main one.
     */
    fun session(): Session = Session()
}
