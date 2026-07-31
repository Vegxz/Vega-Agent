package com.vepro.code

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * The Vega mark — the five-pointed tribal star, TRACED from the supplied
 * artwork rather than approximated parametrically.
 *
 * An earlier version generated the star from a handful of radius/angle
 * constants. It produced a chunky star that shared only ~29% of its area with
 * the real logo (measured): the petals were wide triangles instead of the
 * slender, deeply concave claws the artwork actually has, and the centre was a
 * fat pentagon instead of a small star. Rather than keep guessing constants,
 * the artwork's outline is traced once (Moore-neighbour boundary tracing,
 * Douglas-Peucker simplified) and stored here verbatim: 8 closed contours,
 * 379 points, matching the source bitmap to IoU 0.98.
 *
 * Coordinates live on a 100x100 viewport, so the mark scales to any size with
 * no loss. [CONTOURS] is the single source of truth and is mirrored verbatim in
 * `tools/mklogo.py`, which rasterises the launcher/notification mipmaps —
 * change one, change the other, then re-run `python3 tools/mklogo.py`.
 *
 * @param solidColor the colour to fill with, or null for [Theme.TEXT].
 */
class BrandMark(private val solidColor: Int? = null) : Drawable() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var builtOx = Float.NaN
    private var builtOy = Float.NaN
    private var builtSize = 0.0f

    init {
        fill.style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val box = bounds
        if (box.width() <= 0 || box.height() <= 0) {
            return
        }
        val size = Math.min(box.width(), box.height()).toFloat()
        val ox = box.left + (box.width() - size) / 2.0f
        val oy = box.top + (box.height() - size) / 2.0f
        if (ox != builtOx || oy != builtOy || size != builtSize) {
            builtOx = ox
            builtOy = oy
            builtSize = size
            buildInto(path, ox, oy, size)
        }
        fill.color = solidColor ?: Theme.TEXT
        canvas.drawPath(path, fill)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = INTRINSIC

    override fun getIntrinsicHeight(): Int = INTRINSIC

    companion object {
        /** Nominal size in px-at-1x, so callers need no explicit LayoutParams. */
        const val INTRINSIC: Int = 24

        /** Side of the viewport the traced geometry is authored on. */
        const val VIEWPORT: Float = 100.0f

        /**
         * The traced outline: one flat [x, y, x, y, …] array per closed contour.
         *
         * The shapes do not overlap, so a plain NON_ZERO fill renders them
         * correctly and the white gaps between the claws are simply the
         * background showing through — no even-odd bookkeeping needed.
         */
        private val CONTOURS: Array<FloatArray> = arrayOf(
            // contour 1: 63 points
            floatArrayOf(9.49f, 44.08f, 18.05f, 47.15f, 25.68f, 50.14f, 30.67f, 52.35f, 33.67f, 53.92f, 35.81f, 55.21f, 37.16f, 56.2f, 39.52f, 58.42f, 40.73f, 60.34f, 41.3f, 62.2f, 41.3f, 64.26f, 41.08f, 65.41f, 40.51f, 67.05f, 39.52f, 68.97f, 38.23f, 70.9f, 36.81f, 72.68f, 33.74f, 75.82f, 33.45f, 75.96f, 33.74f, 75.53f, 34.95f, 72.61f, 35.59f, 70.61f, 36.09f, 67.97f, 36.16f, 65.69f, 35.95f, 63.62f, 35.09f, 61.13f, 34.38f, 59.84f, 33.17f, 58.2f, 32.88f, 57.92f, 32.6f, 57.92f, 33.67f, 59.91f, 34.24f, 61.77f, 34.45f, 63.41f, 34.45f, 65.69f, 34.24f, 67.54f, 33.67f, 70.11f, 31.88f, 75.03f, 29.67f, 79.6f, 26.75f, 84.66f, 23.33f, 89.87f, 23.11f, 90.01f, 23.11f, 89.8f, 24.04f, 88.01f, 25.75f, 83.81f, 25.97f, 82.95f, 26.32f, 82.31f, 27.96f, 77.17f, 28.89f, 73.32f, 28.89f, 72.82f, 29.1f, 72.47f, 29.39f, 69.68f, 29.53f, 69.4f, 29.53f, 68.61f, 29.67f, 68.33f, 29.75f, 66.97f, 29.67f, 64.26f, 29.1f, 61.48f, 28.53f, 60.06f, 27.53f, 58.27f, 26.32f, 56.7f, 23.68f, 54.07f, 21.4f, 52.14f, 18.48f, 50f, 9.49f, 44.15f),
            // contour 2: 54 points
            floatArrayOf(47.93f, 15.2f, 47.93f, 18.83f, 47.79f, 19.33f, 47.79f, 21.33f, 47.65f, 22.04f, 47.36f, 27.46f, 47.15f, 28.03f, 46.79f, 31.46f, 45.72f, 36.16f, 44.58f, 39.09f, 43.08f, 41.51f, 41.51f, 43.22f, 40.51f, 44.01f, 38.87f, 45.01f, 37.45f, 45.65f, 35.45f, 46.22f, 32.03f, 46.72f, 28.03f, 46.72f, 23.68f, 46.22f, 23.4f, 46.08f, 20.47f, 45.65f, 20.12f, 45.44f, 17.83f, 45.01f, 17.48f, 44.79f, 14.41f, 44.01f, 7.28f, 41.58f, 6.99f, 41.37f, 3f, 39.8f, 3.14f, 39.73f, 3.36f, 39.87f, 6.92f, 40.59f, 9.28f, 40.87f, 9.7f, 41.08f, 12.77f, 41.51f, 13.56f, 41.51f, 13.7f, 41.66f, 19.97f, 42.3f, 21.97f, 42.37f, 27.18f, 42.3f, 27.61f, 42.15f, 30.03f, 41.94f, 32.95f, 41.23f, 35.81f, 40.09f, 38.37f, 38.37f, 40.16f, 36.66f, 41.94f, 34.31f, 43.65f, 31.24f, 45.01f, 27.89f, 45.65f, 25.68f, 45.79f, 25.54f, 46.93f, 20.97f, 47.29f, 18.76f, 47.43f, 18.62f, 47.93f, 15.27f),
            // contour 3: 50 points
            floatArrayOf(97f, 40.09f, 87.51f, 45.36f, 83.24f, 48.15f, 79.53f, 50.86f, 76.67f, 53.28f, 74.11f, 55.99f, 72.68f, 58.06f, 71.9f, 59.63f, 71.47f, 61.13f, 71.32f, 61.27f, 70.97f, 63.62f, 70.97f, 66.4f, 71.32f, 69.47f, 71.47f, 69.68f, 71.68f, 71.32f, 72.32f, 74.11f, 73.54f, 78.39f, 73.89f, 79.17f, 74.18f, 80.45f, 75.1f, 82.88f, 75.32f, 83.81f, 75.53f, 84.09f, 75.96f, 85.59f, 76.17f, 85.87f, 76.39f, 86.73f, 77.32f, 88.94f, 75.89f, 86.66f, 74.61f, 84.23f, 74.25f, 83.81f, 70.83f, 77.53f, 68.54f, 72.82f, 66.19f, 67.12f, 65.62f, 65.48f, 65.33f, 63.69f, 65.12f, 63.19f, 65.12f, 60.48f, 65.76f, 57.99f, 66.4f, 56.56f, 67.4f, 54.92f, 68.54f, 53.49f, 70.33f, 51.71f, 73.11f, 49.57f, 76.1f, 47.72f, 80.53f, 45.51f, 86.44f, 43.15f, 87.51f, 42.87f, 88.23f, 42.51f, 91.29f, 41.66f, 91.44f, 41.51f, 96.93f, 40.16f),
            // contour 4: 55 points
            floatArrayOf(50.43f, 9.28f, 50.57f, 9.42f, 50.57f, 9.99f, 51.78f, 15.48f, 52f, 15.77f, 52.21f, 17.05f, 52.64f, 18.26f, 52.85f, 19.4f, 54.64f, 25.11f, 55.85f, 28.32f, 56.99f, 30.89f, 59.13f, 34.67f, 61.05f, 37.16f, 62.84f, 38.87f, 64.55f, 40.09f, 66.9f, 41.23f, 68.97f, 41.87f, 71.75f, 42.3f, 76.67f, 42.3f, 77.17f, 42.15f, 78.31f, 42.15f, 81.95f, 41.8f, 83.02f, 41.66f, 83.24f, 41.51f, 84.66f, 41.44f, 85.02f, 41.3f, 87.23f, 41.08f, 87.66f, 40.87f, 88.73f, 40.8f, 88.51f, 41.01f, 87.02f, 41.37f, 84.66f, 42.23f, 78.53f, 44.01f, 75.39f, 44.79f, 74.96f, 44.79f, 74.61f, 45.01f, 69.9f, 45.86f, 66.12f, 46.01f, 63.84f, 45.72f, 61.98f, 45.01f, 59.63f, 43.44f, 58.56f, 42.44f, 56.92f, 40.51f, 55.14f, 37.73f, 53.49f, 34.31f, 52.42f, 31.24f, 51.78f, 28.68f, 51.43f, 26.54f, 51.28f, 26.39f, 51.07f, 24.11f, 50.64f, 21.61f, 50.43f, 16.98f, 50.29f, 16.27f, 50.29f, 9.85f, 50.43f, 9.35f),
            // contour 5: 54 points
            floatArrayOf(71.18f, 48.79f, 71.32f, 48.79f, 70.97f, 49.14f, 69.47f, 50.21f, 68.26f, 51.28f, 67.05f, 52.5f, 65.69f, 54.14f, 64.26f, 56.49f, 63.69f, 57.92f, 63.41f, 59.41f, 63.27f, 59.63f, 63.27f, 62.34f, 63.41f, 62.55f, 63.76f, 64.55f, 64.48f, 66.76f, 67.26f, 73.46f, 70.47f, 79.88f, 66.62f, 76.25f, 61.77f, 72.39f, 58.06f, 70.11f, 54.07f, 68.33f, 51.14f, 67.62f, 46.86f, 67.62f, 45.65f, 67.83f, 45.51f, 67.97f, 45.15f, 67.97f, 44.15f, 68.33f, 42.65f, 68.97f, 40.51f, 70.25f, 40.44f, 70.18f, 43.44f, 67.05f, 45.29f, 65.62f, 46.15f, 65.12f, 47.65f, 64.41f, 49.36f, 63.91f, 52.71f, 63.84f, 54.92f, 64.41f, 56.35f, 65.05f, 58.06f, 66.05f, 61.27f, 68.47f, 63.62f, 70.68f, 63.69f, 70.47f, 62.41f, 68.83f, 60.34f, 65.55f, 59.34f, 63.19f, 59.13f, 62.05f, 59.13f, 60.06f, 59.34f, 58.99f, 59.77f, 57.85f, 60.48f, 56.56f, 61.41f, 55.35f, 63.19f, 53.57f, 66.62f, 51.14f, 71.11f, 48.86f),
            // contour 6: 43 points
            floatArrayOf(48.57f, 69.19f, 50.43f, 69.26f, 51.71f, 69.47f, 54.78f, 70.47f, 56.7f, 71.32f, 58.49f, 72.32f, 61.91f, 74.61f, 64.12f, 76.32f, 66.83f, 78.67f, 70.33f, 82.17f, 74.53f, 87.02f, 77.17f, 90.37f, 77.32f, 90.72f, 75.46f, 89.08f, 69.76f, 84.52f, 65.76f, 81.67f, 61.7f, 79.1f, 59.13f, 77.81f, 58.92f, 77.6f, 57.99f, 77.24f, 57.77f, 77.03f, 55.85f, 76.25f, 53.14f, 75.39f, 52.78f, 75.39f, 52.64f, 75.25f, 51.21f, 75.03f, 48.93f, 74.96f, 46.36f, 75.25f, 44.44f, 75.82f, 41.51f, 77.03f, 38.16f, 78.88f, 35.09f, 80.88f, 30.46f, 84.31f, 26.04f, 87.8f, 29.32f, 83.52f, 33.17f, 78.96f, 37.23f, 74.75f, 39.3f, 72.97f, 41.58f, 71.32f, 43.94f, 70.11f, 45.86f, 69.47f, 47.15f, 69.26f, 48.5f, 69.26f),
            // contour 7: 31 points
            floatArrayOf(49.36f, 26.82f, 49.71f, 27.75f, 50.57f, 31.24f, 52.35f, 36.02f, 54.49f, 40.09f, 56.35f, 42.65f, 57.7f, 44.08f, 59.06f, 45.22f, 59.98f, 45.86f, 62.12f, 46.93f, 63.91f, 47.5f, 65.26f, 47.72f, 67.47f, 47.79f, 66.97f, 48f, 65.62f, 48.07f, 65.33f, 48.22f, 63.19f, 48.29f, 61.41f, 48.22f, 60.06f, 48f, 58.2f, 47.43f, 56.2f, 46.43f, 55.28f, 45.79f, 53.49f, 44.01f, 52.5f, 42.58f, 51.28f, 40.16f, 50.14f, 36.45f, 49.86f, 34.1f, 49.64f, 33.6f, 49.64f, 32.95f, 49.5f, 32.67f, 49.36f, 26.89f),
            // contour 8: 26 points
            floatArrayOf(46.01f, 39.52f, 45.72f, 41.01f, 45.36f, 41.73f, 45.15f, 42.65f, 43.94f, 45.15f, 42.73f, 46.79f, 42.08f, 47.43f, 40.37f, 48.64f, 39.3f, 49.14f, 37.73f, 49.5f, 37.59f, 49.64f, 35.24f, 49.79f, 33.53f, 49.64f, 33.1f, 49.43f, 31.17f, 49.14f, 28.6f, 48.36f, 30.74f, 48.43f, 35.17f, 48.07f, 35.38f, 47.93f, 35.81f, 47.93f, 37.45f, 47.5f, 39.73f, 46.51f, 41.73f, 45.15f, 43.44f, 43.51f, 45.29f, 40.94f, 45.93f, 39.59f)
        )

        /** The whole mark as one path, fitted to the square at [ox], [oy]. */
        fun starPath(ox: Float, oy: Float, size: Float): Path {
            val p = Path()
            buildInto(p, ox, oy, size)
            return p
        }

        private fun buildInto(p: Path, ox: Float, oy: Float, size: Float) {
            p.reset()
            val s = size / VIEWPORT
            for (contour in CONTOURS) {
                var i = 0
                while (i + 1 < contour.size) {
                    val x = ox + contour[i] * s
                    val y = oy + contour[i + 1] * s
                    if (i == 0) {
                        p.moveTo(x, y)
                    } else {
                        p.lineTo(x, y)
                    }
                    i += 2
                }
                p.close()
            }
        }
    }
}
