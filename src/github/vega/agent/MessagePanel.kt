package github.vega.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The long-press panel for chat messages.
 *
 * Long-pressing a message (the user's own or the model's) shows a small
 * floating panel with three actions — Select, Copy, Share:
 *
 * - Copy: copies the whole message text and closes the panel.
 * - Select: opens the whole message in a selectable sheet, so the selection
 *   handles can span the message instead of a single rendered block. A model
 *   answer is a tree of many small TextViews (one per paragraph, table cell
 *   or math block), so the platform's own selection could only ever cover
 *   the leaf under the finger; the sheet holds every prose leaf in one
 *   TextView with the hardened Copy / Select-all toolbar from
 *   [MarkdownRenderer.installSelectionActions].
 * - Share: opens the Android share sheet with the message text and closes.
 *
 * The panel is a [PopupWindow], so it floats above the chat instead of
 * pushing rows around, and it dismisses on outside touch, on back press,
 * on any action, and as soon as its anchor view leaves the window (chat
 * rebuilds recycle the rows constantly).
 */
object MessagePanel {

    private var open: PopupWindow? = null
    private var openAnchor: View? = null

    /**
     * Installs the long-press behaviour on a message [TextView]. The view
     * must already be selectable ([MarkdownRenderer.installSelectionActions]
     * keeps that hardening); this only replaces what a long-press DOES.
     *
     * @param fullText supplies the whole message text at action time, so a
     * re-rendered row can never copy stale text.
     * @param messageRoot the view whose text leaves form the whole message
     * (the answer body for model turns, null for the single-view user
     * bubble); "Select" collects from it.
     */
    fun attach(
        context: Context,
        textView: TextView,
        fullText: () -> String,
        messageRoot: View? = null
    ) {
        textView.setOnLongClickListener(PanelOpener(context, textView, fullText, messageRoot))
        // A row that leaves the tree (new message, rebuild, trim) takes its
        // popup with it — otherwise the window leaks. One listener per view,
        // registered once here rather than on every show.
        textView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                if (openAnchor === v) {
                    dismiss()
                }
            }
        })
    }

    /** Closes the panel if it is showing. Safe to call at any time. */
    fun dismiss() {
        openAnchor = null
        val popup = open ?: return
        open = null
        try {
            popup.dismiss()
        } catch (ignored: Throwable) {
        }
    }

    private class PanelOpener(
        private val context: Context,
        private val anchor: TextView,
        private val fullText: () -> String,
        private val messageRoot: View?
    ) : View.OnLongClickListener {

        override fun onLongClick(v: View): Boolean {
            showPanel(context, anchor, fullText, messageRoot)
            // Consumed: the framework must NOT start text selection here.
            // "Select" opens the whole-message sheet explicitly.
            return true
        }
    }

    private fun showPanel(
        context: Context,
        anchor: TextView,
        fullText: () -> String,
        messageRoot: View?
    ) {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) {
            return
        }
        dismiss()
        val strip = buildStrip(context, anchor, fullText, messageRoot)
        val popup = PopupWindow(
            strip,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        // A background drawable is what lets outside touches dismiss a
        // PopupWindow; transparent keeps the card's own shape.
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            popup.elevation = Theme.dpf(context, 8.0f)
        }
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        strip.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pw = strip.measuredWidth
        val ph = strip.measuredHeight
        val metrics = context.resources.displayMetrics
        val margin = Theme.dp(context, 12.0f)
        var x = loc[0] + anchor.width / 2 - pw / 2
        val maxX = metrics.widthPixels - pw - margin
        x = if (maxX <= margin) margin else Math.min(Math.max(x, margin), maxX)
        var y = loc[1] - ph - Theme.dp(context, 8.0f)
        if (y < margin) {
            // No room above the message: drop below it instead.
            y = loc[1] + anchor.height + Theme.dp(context, 8.0f)
        }
        open = popup
        openAnchor = anchor
        try {
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        } catch (e: Exception) {
            open = null
            openAnchor = null
            return
        }
        // Fade + grow out of its own centre, on the shared easing curve.
        strip.pivotX = pw / 2.0f
        strip.pivotY = ph / 2.0f
        strip.alpha = 0.0f
        strip.scaleX = 0.9f
        strip.scaleY = 0.9f
        strip.animate()
            .alpha(1.0f).scaleX(1.0f).scaleY(1.0f)
            .setDuration(Ui.D_FAST).setInterpolator(Ui.ease())
            .start()
    }

    private fun buildStrip(
        context: Context,
        anchor: TextView,
        fullText: () -> String,
        messageRoot: View?
    ): LinearLayout {
        val strip = LinearLayout(context)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        // The three actions are symmetric; in RTL locales their order
        // mirrors with the rest of the interface.
        strip.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        strip.background = Theme.roundStroke(Theme.SURFACE_2, Theme.BORDER, 16.0f, 1, context)
        val pad = Theme.dp(context, 6.0f)
        strip.setPadding(pad, pad, pad, pad)
        addCell(strip, context, "select", Fa.SELECT) { onSelect(context, anchor, messageRoot) }
        addCell(strip, context, "copy", Fa.COPY) { onCopy(context, fullText) }
        addCell(strip, context, "share", Fa.SHARE) { onShare(context, fullText) }
        return strip
    }

    private fun addCell(
        parent: LinearLayout,
        context: Context,
        icon: String,
        label: String,
        onClick: () -> Unit
    ) {
        val cell = LinearLayout(context)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        cell.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        cell.setPadding(
            Theme.dp(context, 14.0f), Theme.dp(context, 8.0f),
            Theme.dp(context, 14.0f), Theme.dp(context, 8.0f)
        )
        cell.background = Theme.rippleTransparent(Theme.R_PILL, context)
        cell.isClickable = true
        cell.isFocusable = true
        cell.contentDescription = label

        val glyph = ImageView(context)
        glyph.setImageDrawable(Icons.of(icon, Theme.TEXT, Ui.STROKE))
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(context, 20.0f)
        cell.addView(
            glyph,
            LinearLayout.LayoutParams(glyphSize, glyphSize)
        )

        val name = TextView(context)
        name.text = label
        name.setTextColor(Theme.TEXT_MUTED)
        name.textSize = Ui.Type.MICRO
        name.typeface = Theme.uiMedium()
        name.setSingleLine(true)
        val nameLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        nameLp.topMargin = Theme.dp(context, 3.0f)
        cell.addView(name, nameLp)

        // No Ui.pressScale: this view may be mid-animation when touched, and
        // pressScale cancels the running animator. The ripple is the feedback.
        cell.setOnClickListener { onClick() }
        parent.addView(
            cell,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun currentText(fullText: () -> String): String = try {
        fullText()
    } catch (ignored: Throwable) {
        ""
    }

    private fun onCopy(context: Context, fullText: () -> String) {
        val text = currentText(fullText)
        // The hardened clipboard path: same one the selection toolbar uses,
        // with the MIUI application-context retry built in.
        if (MarkdownRenderer.copyText(context, Fa.APP_NAME, text)) {
            Toast.makeText(context, Fa.COPIED, Toast.LENGTH_SHORT).show()
        }
        dismiss()
    }

    private fun onShare(context: Context, fullText: () -> String) {
        val text = currentText(fullText)
        dismiss()
        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, text)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, Fa.SHARE))
        } catch (e: Exception) {
            Toast.makeText(context, Fa.SHARE_FAILED, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens the whole message — every prose leaf under [messageRoot], or the
     * anchor view alone — in a selectable sheet, so the selection handles
     * can span the message instead of one rendered block.
     */
    private fun onSelect(context: Context, anchor: View, messageRoot: View?) {
        dismiss()
        val text = collectMessageText(anchor, messageRoot)
        if (text.toString().isBlankJava()) {
            return
        }
        val sheet = Sheet(context)
        sheet.header("select", Fa.SELECT, null)
        val scroll = ScrollView(context)
        scroll.isVerticalScrollBarEnabled = false
        val tv = TextView(context)
        tv.text = text
        tv.setTextIsSelectable(true)
        MarkdownRenderer.installSelectionActions(context, tv)
        tv.typeface = Theme.ui()
        tv.setTextColor(Theme.TEXT)
        tv.textSize = Ui.Type.BODY
        tv.setLineSpacing(Theme.dpf(context, Ui.Space.XS), 1.0f)
        tv.textDirection = View.TEXT_DIRECTION_LOCALE
        val pad = Theme.dp(context, Ui.Space.M)
        tv.setPadding(pad, pad, pad, pad)
        scroll.addView(
            tv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val maxH = (context.resources.displayMetrics.heightPixels * 0.6f).toInt()
        val scrollLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        scrollLp.height = maxH
        sheet.body.addView(scroll, scrollLp)
        sheet.show()
    }

    /**
     * Every prose leaf of the message as one CharSequence, keeping the
     * rendered spans (so formulas keep their serif face in the sheet).
     * Code cards contribute their body text (never the header row with its
     * language label and copy button), so Select truly covers the message.
     */
    private fun collectMessageText(anchor: View, root: View?): CharSequence {
        val sb = SpannableStringBuilder()
        fun appendText(t: CharSequence) {
            if (t.toString().isBlankJava()) {
                return
            }
            if (sb.isNotEmpty()) {
                sb.append("\n\n")
            }
            sb.append(t)
        }
        fun collectCodeCard(card: View) {
            if (card.tag == MarkdownRenderer.TAG_CODE_BODY && card is TextView) {
                appendText(card.text)
                return
            }
            if (card is ViewGroup) {
                for (i in 0 until card.childCount) {
                    collectCodeCard(card.getChildAt(i) ?: continue)
                }
            }
        }
        fun collect(v: View) {
            when (v) {
                is TextView -> appendText(v.text)
                is ViewGroup -> {
                    if (v.tag == MarkdownRenderer.TAG_CODE_CARD) {
                        collectCodeCard(v)
                        return
                    }
                    for (i in 0 until v.childCount) {
                        val child = v.getChildAt(i) ?: continue
                        collect(child)
                    }
                }
            }
        }
        collect(root ?: anchor)
        return sb
    }
}
