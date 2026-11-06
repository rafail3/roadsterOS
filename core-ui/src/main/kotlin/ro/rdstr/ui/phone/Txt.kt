package ro.rdstr.ui.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Type
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.uiKey
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A text as the browser sets it in a [Box]: its box as wide as its words (unrounded), each line as tall as the
 * browser's line box (`line-height: normal` is the font's ascent and descent each rounded to whole pixels, as Chrome
 * takes them), the baseline where the browser puts it, an ellipsis when a single line has less room than it needs.
 * [multi] lets the words wrap within the box's width. A plain view, lighter than a TextView; [text] is also its
 * content description.
 */
@SuppressLint("ViewConstructor")
class Txt(context: Context, style: TextStyle, key: String? = null, text: CharSequence = "") : View(context) {
    var style: TextStyle = style
        set(v) { field = v; applyStyle(); requestLayout(); invalidate() }
    var text: CharSequence = text
        set(v) { if (field.toString() != v.toString()) { field = v; contentDescription = v; layout = null; requestLayout(); invalidate() } }
    var color = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var multi = false
    var centered = false
    /** The part of a pixel the browser's unrounded position is right of the rounded one, set by the layout. */
    internal var subX = 0f

    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG or TextPaint.LINEAR_TEXT_FLAG)
    private var ascent = 0f
    private var linePx = 0f
    private var baseline = 0f
    private var layout: StaticLayout? = null
    private var shown: CharSequence = ""
    private var shownFor = -1

    init {
        uiKey = key
        contentDescription = text
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        UiTheme.link(this) { applyStyle(); requestLayout(); invalidate() }
        applyStyle()
    }

    private val d get() = resources.displayMetrics.density

    private fun applyStyle() {
        Type.apply(paint, style, context)
        val fm = paint.fontMetrics
        ascent = (-fm.ascent).roundToInt().toFloat()
        val normal = ascent + fm.descent.roundToInt()
        linePx = if (style.lineHeight > 0f) style.lineHeight * paint.textSize else normal
        baseline = (linePx - normal) / 2f + ascent
        layout = null; shownFor = -1
    }

    /** The width of the words on one line, canvas px. */
    fun contentWidth(): Float = paint.measureText(text, 0, text.length) / d

    /** The height of the lines at [width] canvas px. */
    fun contentHeight(width: Float): Float = if (text.isEmpty()) 0f else (if (multi) lines(width) else 1) * linePx / d

    private fun lines(width: Float): Int = build(ceil(width * d + 0.5f).toInt()).lineCount

    private fun build(widthPx: Int): StaticLayout {
        layout?.let { if (it.width == widthPx) return it }
        val align = if (centered) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, maxOf(1, widthPx))
            .setAlignment(align).setIncludePad(false).setLineSpacing(0f, 1f).build().also { layout = it }
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        if (!multi) {
            if (shownFor != width) {
                shownFor = width
                // the words still fit at the browser's unrounded width, a part of a pixel wider than this view
                shown = if (paint.measureText(text, 0, text.length) <= width + 0.75f) text
                    else TextUtils.ellipsize(text, paint, width.toFloat(), TextUtils.TruncateAt.END)
            }
            val x = if (centered) (width - paint.measureText(shown, 0, shown.length)) / 2f else 0f
            canvas.drawText(shown, 0, shown.length, x + subX, baseline, paint)
            return
        }
        // a wrapped text: each line on the browser's pitch, its baseline where the browser's line box puts it
        val l = build(ceil(width + 0.5f).toInt())
        for (i in 0 until l.lineCount) {
            val y = i * linePx + baseline
            canvas.drawText(text, l.getLineStart(i), trimmedEnd(l, i), l.getLineLeft(i) + subX, y, paint)
        }
    }

    private fun trimmedEnd(l: StaticLayout, i: Int): Int {
        var e = l.getLineEnd(i)
        while (e > l.getLineStart(i) && text[e - 1].isWhitespace()) e--
        return e
    }
}
