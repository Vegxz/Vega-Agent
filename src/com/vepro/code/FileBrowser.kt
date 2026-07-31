package com.vepro.code

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * File picker sheet — Vega monochrome restyle (behaviour unchanged).
 *
 * The tinted 34dp badges are gone. An entry is now a plain 20dp outline glyph in
 * [Theme.TEXT_MUTED] beside a [Ui.Type.BODY] name, sitting directly on the
 * sheet's ground: the icon says *what kind of thing this is*, the name carries
 * the weight, and the size keeps its own neutral chip so a long filename cannot
 * ellipsize away the one piece of metadata the row exists to show. The "use this
 * folder" action stays pinned to the top as a real primary pill.
 */
class FileBrowser(private val c: Context, private val onPick: OnPick?) {

    fun interface OnPick {
        fun picked(file: File)
    }

    private val root: File = Tools.externalRoot(c)
    private var cur: File = root
    private val sheet = Sheet(c)

    private lateinit var crumbWrap: HorizontalScrollView
    private lateinit var crumb: LinearLayout
    private lateinit var list: LinearLayout

    init {
        sheet.header("hard-drive", Fa.BROWSER_TITLE, Fa.BROWSER_SUB)
        build()
    }

    private fun build() {
        crumbWrap = HorizontalScrollView(c)
        crumbWrap.isHorizontalScrollBarEnabled = false

        crumb = LinearLayout(c)
        crumb.orientation = LinearLayout.HORIZONTAL
        crumb.gravity = Gravity.CENTER_VERTICAL
        crumb.layoutDirection = Lang.direction(c)
        crumbWrap.addView(crumb)

        val crumbParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        crumbParams.bottomMargin = Theme.dp(c, 10.0f)
        sheet.body.addView(crumbWrap, crumbParams)

        val scroll = ScrollView(c)
        scroll.isVerticalScrollBarEnabled = false
        list = LinearLayout(c)
        list.orientation = LinearLayout.VERTICAL
        // Rows are built from relative insets and START/END gravity, so one
        // assignment here mirrors the whole listing in Persian — the icon leads
        // from the right, the tick sits on the left, and the breadcrumb above
        // (which already asks) stops disagreeing with the list below it.
        list.layoutDirection = Lang.direction(c)
        scroll.addView(list)
        sheet.body.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight())
        )
        refresh()
    }

    /**
     * How tall the listing is, in pixels.
     *
     * Half the screen is the right PROPORTION and the wrong absolute size at both
     * ends of the device range: on a landscape phone (~360dp tall) it is a 180dp
     * slot showing three rows, and on a tall tablet it is a 500dp wall of
     * whitespace above a five-item folder. The fraction stays the basis — it is
     * what makes the sheet feel the same size on the phones this app is actually
     * used on — and is then clamped into a dp range that reads as a list either
     * way.
     *
     * The ceiling is itself capped at a fraction of the screen, so the FLOOR can
     * never exceed the window: without that, a 260dp minimum on a 360dp landscape
     * screen would push the header and the "use this folder" pill off the top.
     */
    private fun listHeight(): Int {
        val screen = c.resources.displayMetrics.heightPixels
        val ceiling = Theme.dp(c, LIST_MAX_DP).coerceAtMost((screen * LIST_MAX_FRACTION).toInt())
        val floor = Theme.dp(c, LIST_MIN_DP).coerceAtMost(ceiling)
        return (screen * LIST_FRACTION).toInt().coerceIn(floor, ceiling)
    }

    fun show() {
        sheet.show()
    }

    private fun refresh() {
        crumb.removeAllViews()

        val rootPath = root.absolutePath
        val currentPath = cur.absolutePath
        // The breadcrumb always opens with this word, then the path relative to
        // the workspace root.
        val rootName = "Storage"
        // removePrefix, not replace: a global replace also rewrote any later
        // occurrence of the root path inside the string.
        val label = if (currentPath == rootPath) {
            rootName
        } else {
            rootName + currentPath.removePrefix(rootPath)
        }

        val crumbRow = LinearLayout(c)
        crumbRow.orientation = LinearLayout.HORIZONTAL
        crumbRow.gravity = Gravity.CENTER_VERTICAL
        // A quiet neutral pill. Symmetric insets, so plain setPadding is correct
        // here — there is no start/end asymmetry to mirror.
        crumbRow.background = Theme.chip(Theme.R_PILL, c)
        val crumbPadH = Theme.dp(c, Ui.Space.M)
        val crumbPadV = Theme.dp(c, 7.0f)
        crumbRow.setPadding(crumbPadH, crumbPadV, crumbPadH, crumbPadV)

        val pin = ImageView(c)
        pin.setImageDrawable(Icons.of("hard-drive", Theme.TEXT_FAINT, Ui.STROKE))
        pin.scaleType = ImageView.ScaleType.FIT_CENTER
        pin.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val pinSize = Theme.dp(c, 14.0f)
        val pinParams = LinearLayout.LayoutParams(pinSize, pinSize)
        pinParams.marginEnd = Theme.dp(c, Ui.Space.S)
        crumbRow.addView(pin, pinParams)

        val crumbText = TextView(c)
        crumbText.text = label
        crumbText.setTextColor(Theme.TEXT_MUTED)
        crumbText.textSize = Ui.Type.MICRO
        crumbText.typeface = Theme.mono()
        // The label is "Storage" followed by a path, but a path segment can be a
        // file the user named in Persian. FIRST_STRONG anchors the leading word at
        // the reading edge and lets each run keep its own direction; forcing LTR
        // reordered any right-to-left segment.
        crumbText.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        crumbRow.addView(crumbText)
        crumb.addView(crumbRow)

        list.removeAllViews()
        list.addView(pickCurrentRow())

        if (cur.absolutePath != root.absolutePath) {
            list.addView(
                row("corner-up-left", Fa.BROWSER_UP, null, Theme.TEXT_MUTED, true) {
                    val parent = cur.parentFile
                    // Prefix, not length: a same-length sibling path is not an
                    // ancestor, and length alone let a symlinked or seeded path
                    // walk out of the sandbox.
                    if (parent != null && parent.absolutePath.startsWith(root.absolutePath)) {
                        cur = parent
                    }
                    refresh()
                }
            )
        }

        val children = cur.listFiles()?.filter { !it.name.startsWith(".") }?.toMutableList()
        if (children == null || children.isEmpty()) {
            val empty = TextView(c)
            empty.typeface = Theme.ui()
            empty.text = Fa.BROWSER_EMPTY
            empty.setTextColor(Theme.TEXT_FAINT)
            empty.textSize = Ui.Type.META
            empty.gravity = Gravity.CENTER
            empty.background = Theme.roundRect(Theme.SURFACE_2, Theme.R_CARD, c)
            val emptyPad = Theme.dp(c, Ui.Space.XXL)
            empty.setPadding(emptyPad, emptyPad, emptyPad, emptyPad)
            val emptyParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            emptyParams.topMargin = Theme.dp(c, 8.0f)
            list.addView(empty, emptyParams)
            return
        }

        // directories first, then case-insensitive by name
        children.sortWith(Comparator { a, b ->
            if (a.isDirectory != b.isDirectory) {
                if (a.isDirectory) -1 else 1
            } else {
                a.name.compareTo(b.name, ignoreCase = true)
            }
        })

        for (file in children) {
            if (file.isDirectory) {
                list.addView(folderRow(file))
            } else {
                val icon = when (Util.kindOf(Util.mimeOf(file.name))) {
                    "image" -> "image"
                    "video" -> "video"
                    "audio" -> "music"
                    else -> "file"
                }
                // The size gets its own chip. Appending it to the name meant a
                // long filename ellipsized the size away entirely — the one
                // piece of metadata the row existed to show.
                list.addView(
                    row(icon, file.name, Util.humanSize(file.length()), Theme.TEXT, false) {
                        sheet.dismiss()
                        onPick?.picked(file)
                    }
                )
            }
        }
    }

    /** "Select this folder" affordance pinned above the listing. */
    private fun pickCurrentRow(): LinearLayout {
        val row = Ui.pillButton(c, Fa.BROWSER_PICK, "check-circle", Ui.PRIMARY) {
            sheet.dismiss()
            onPick?.picked(cur)
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = Theme.dp(c, 10.0f)
        row.layoutParams = params
        return row
    }

    /** A directory: tapping the name enters it, tapping the tick picks it. */
    private fun folderRow(file: File): LinearLayout {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Theme.rippleTransparent(Theme.R_MD, c)
        // A real floor. These were the only tappable rows in the app with no
        // minimum height at all — about 39dp of padding plus a meta chip — in a
        // list where every tap is aimed at a specific filename.
        row.minimumHeight = Theme.dp(c, 48.0f)
        // Relative so the icon-side inset stays on the icon side in Persian.
        row.setPaddingRelative(
            Theme.dp(c, Ui.Space.M), Theme.dp(c, Ui.Space.S),
            Theme.dp(c, Ui.Space.XS), Theme.dp(c, Ui.Space.S)
        )

        val glyph = ImageView(c)
        glyph.setImageDrawable(Icons.of("folder", Theme.TEXT_MUTED, Ui.STROKE))
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(c, Ui.Space.XL)
        val glyphParams = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphParams.marginEnd = Theme.dp(c, Ui.Space.L)
        row.addView(glyph, glyphParams)

        val text = TextView(c)
        text.text = file.name
        text.setTextColor(Theme.TEXT)
        text.textSize = Ui.Type.BODY
        text.typeface = Theme.ui()
        text.setSingleLine(true)
        text.ellipsize = TextUtils.TruncateAt.MIDDLE
        text.textDirection = View.TEXT_DIRECTION_LTR
        // Keep the name beside its icon: a forced-LTR paragraph inside a weighted
        // slot otherwise drifts to the far edge of the row in Persian, leaving a
        // gap between the icon and the filename.
        text.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        row.addView(
            text,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        val pick = ImageView(c)
        pick.setImageDrawable(Icons.of("check", Theme.TEXT_MUTED, Ui.STROKE))
        pick.scaleType = ImageView.ScaleType.FIT_CENTER
        pick.contentDescription = Fa.BROWSER_PICK
        // 44dp, not 30: this was well under the minimum touch target.
        val pickBox = Theme.dp(c, 44.0f)
        val pickPad = Theme.dp(c, Ui.Space.M)
        pick.setPadding(pickPad, pickPad, pickPad, pickPad)
        pick.background = Theme.rippleTransparent(Theme.R_PILL, c)
        pick.setOnClickListener {
            sheet.dismiss()
            onPick?.picked(file)
        }
        row.addView(pick, LinearLayout.LayoutParams(pickBox, pickBox))

        val enter = View.OnClickListener {
            cur = file
            refresh()
        }
        text.setOnClickListener(enter)
        glyph.setOnClickListener(enter)
        row.setOnClickListener(enter)
        // The file picker was the one list in the app whose rows only rippled.
        // Every other tappable row — drawer chats, suggestions, mode rows, option
        // rows, settings cells — also scales under the finger.
        Ui.pressScale(row)

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = Theme.dp(c, 2.0f)
        row.layoutParams = params
        return row
    }

    /**
     * Generic list row: outline glyph, name, optional [meta] chip, optional
     * trailing [chevron]. [color] tints the LABEL only — the glyph is always
     * [Theme.TEXT_MUTED], so the row's emphasis lives in one place.
     */
    private fun row(
        icon: String,
        label: String,
        meta: String?,
        color: Int,
        chevron: Boolean,
        action: Runnable
    ): LinearLayout {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Theme.rippleTransparent(Theme.R_MD, c)
        // A real floor. These were the only tappable rows in the app with no
        // minimum height at all — about 39dp of padding plus a meta chip — in a
        // list where every tap is aimed at a specific filename.
        row.minimumHeight = Theme.dp(c, 48.0f)
        row.setPaddingRelative(
            Theme.dp(c, Ui.Space.M), Theme.dp(c, Ui.Space.S),
            Theme.dp(c, Ui.Space.M), Theme.dp(c, Ui.Space.S)
        )

        val glyph = ImageView(c)
        glyph.setImageDrawable(Icons.of(icon, Theme.TEXT_MUTED, Ui.STROKE))
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(c, Ui.Space.XL)
        val glyphParams = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphParams.marginEnd = Theme.dp(c, Ui.Space.L)
        row.addView(glyph, glyphParams)

        val text = TextView(c)
        text.text = label
        text.setTextColor(color)
        text.textSize = Ui.Type.BODY
        text.typeface = Theme.ui()
        text.setSingleLine(true)
        text.ellipsize = TextUtils.TruncateAt.MIDDLE
        text.textDirection = View.TEXT_DIRECTION_LTR
        // Keep the name beside its icon: a forced-LTR paragraph inside a weighted
        // slot otherwise drifts to the far edge of the row in Persian, leaving a
        // gap between the icon and the filename.
        text.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        row.addView(
            text,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )

        if (!meta.isNullOrEmpty()) {
            val chip = Ui.metaChip(c, meta, 0, true)
            val chipParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            chipParams.marginStart = Theme.dp(c, Ui.Space.S)
            row.addView(chip, chipParams)
        }

        if (chevron) {
            val arrow = ImageView(c)
            // Forward affordance points into the reading direction.
            arrow.setImageDrawable(
                Icons.of(
                    Lang.chevronForward(c),
                    Theme.TEXT_FAINT, Ui.STROKE
                )
            )
            arrow.scaleType = ImageView.ScaleType.FIT_CENTER
            arrow.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val arrowSize = Theme.dp(c, Ui.Space.L)
            val arrowParams = LinearLayout.LayoutParams(arrowSize, arrowSize)
            arrowParams.marginStart = Theme.dp(c, Ui.Space.S)
            row.addView(arrow, arrowParams)
        }

        row.setOnClickListener { action.run() }
        Ui.pressScale(row)

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.bottomMargin = Theme.dp(c, 2.0f)
        row.layoutParams = params
        return row
    }

    companion object {
        /** The original sizing rule, kept as the basis rather than the answer. */
        private const val LIST_FRACTION = 0.5f

        /**
         * Never taller than this share of the screen, so the clamp below cannot
         * squeeze the breadcrumb and the primary pill off a short window.
         */
        private const val LIST_MAX_FRACTION = 0.62f

        /** About five rows: fewer than that and the list stops reading as one. */
        private const val LIST_MIN_DP = 260.0f

        /** About nine rows. Past that a bottom sheet is just a bad full screen. */
        private const val LIST_MAX_DP = 440.0f
    }
}
