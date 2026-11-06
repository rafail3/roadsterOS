package ro.rdstr.ui.sheets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.uiKey
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A CSS box painted as the view's background: a [CssBox] drawn over the view's bounds. Not a ColorDrawable, so the
 * parity capture leaves its colour to the picture (the box may be a gradient, a frost or a mask).
 */
class BoxDrawable(private val view: View, val box: CssBox) : Drawable() {
    override fun draw(canvas: Canvas) = box.draw(canvas, view, bounds.width().toFloat(), bounds.height().toFloat())
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A CSS flex container of one element (`display: flex` or a `display: grid` that places one row or column), laid out
 * in canvas px as floats and snapped to device pixels edge by edge, as a browser paints its boxes: gaps and padding of
 * fractional device pixels do not add up the way whole-pixel layouts round them. Its own box ([box]) is the
 * element's background, border and shadows. Children take [Item] parameters (flex-grow, flex-shrink with
 * `min-width: 0`, fixed sizes, margins, align-self); a [KitText] child is sized by its text's exact advance.
 */
@SuppressLint("ViewConstructor")
open class Flex(context: Context, key: String?, val column: Boolean = false) : ViewGroup(context) {
    enum class Align { START, CENTER, END, STRETCH }
    enum class Justify { START, CENTER, END, BETWEEN }

    var gap = 0f
    var padL = 0f; var padT = 0f; var padR = 0f; var padB = 0f
    /** `min-height` of this box, canvas px. */
    var minH = 0f
    var align = Align.STRETCH
    var justify = Justify.START
    /** Fixed size of this box in canvas px, NaN for its content (or the parent's stretch). */
    val box = CssBox()
    /**
     * Where this box really is minus where its pixels are, canvas px, set by the parent that placed it: children are
     * snapped from their exact positions on the screen, not from this box's rounded corner, as a browser snaps them.
     */
    var errX = 0f
    var errY = 0f
    /** The exact size the parent gave, canvas px, before it was snapped to the measure's whole pixels. */
    private var exactW = Float.NaN
    private var exactH = Float.NaN

    /** The width an EXACTLY [spec] stands for, canvas px: the parent's exact one when it gave it. */
    protected fun outerWidth(spec: Int): Float =
        if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY && !exactW.isNaN()) exactW else MeasureSpec.getSize(spec) / d
    protected fun outerHeight(spec: Int): Float =
        if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY && !exactH.isNaN()) exactH else MeasureSpec.getSize(spec) / d
    /** The content size of the last measure, canvas px. */
    var contentW = 0f; private set
    var contentH = 0f; private set

    /** `transform: scale()` while pressed, with the CSS transition in and out. */
    var pressScale = 1f
    var pressInMs = 120L
    var pressOutMs = 120L
    var pressEase: Easing = Motion.springGlyph
    /** The box's fill while pressed (`:active`), null for none. */
    var pressedFill: Int? = null
    /** Told of every press change (an `:active` rule on a descendant). */
    var onPressed: ((Boolean) -> Unit)? = null
    private var restFill = 0

    protected val d get() = resources.displayMetrics.density

    init {
        uiKey = key
        background = BoxDrawable(this, box)
        clipChildren = false
        clipToPadding = false
    }

    fun pad(t: Float, r: Float = t, b: Float = t, l: Float = r) { padT = t; padR = r; padB = b; padL = l; requestLayout() }

    class Item(
        var w: Float = Float.NaN, var h: Float = Float.NaN,
        var grow: Float = 0f, var shrink: Boolean = false,
        var mL: Float = 0f, var mT: Float = 0f, var mR: Float = 0f, var mB: Float = 0f,
        var alignSelf: Align? = null,
        /** `min-width`/`min-height` along the main axis, canvas px. */
        var minMain: Float = 0f,
        /** `margin-left: auto` (`margin-top` in a column): the free space goes before this item. */
        var pushEnd: Boolean = false,
        /** Out of the flow (`position: absolute`): the subclass measures and places it. */
        var absolute: Boolean = false,
    ) : LayoutParams(WRAP_CONTENT, WRAP_CONTENT) {
        internal var x = 0f; internal var y = 0f; internal var fw = 0f; internal var fh = 0f; internal var px = 0; internal var py = 0
    }

    fun <V : View> add(v: V, item: Item = Item()): V { addView(v, item); return v }

    override fun generateDefaultLayoutParams() = Item()
    override fun checkLayoutParams(p: LayoutParams?) = p is Item
    override fun generateLayoutParams(p: LayoutParams?) = Item()

    private fun kids() = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE && (it.layoutParams as? Item)?.absolute != true }

    /** The size [v] takes by its content, canvas px, with [maxW] (NaN: unbounded) for wrapping text. */
    private fun natural(v: View, maxW: Float): FloatArray {
        val dd = d
        if (v is Flex) {
            v.measure(if (maxW.isNaN()) MeasureSpec.UNSPECIFIED else MeasureSpec.makeMeasureSpec((maxW * dd).roundToInt(), MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            return floatArrayOf(v.fixedOr(v.contentW, true), v.fixedOr(maxOf(v.contentH, v.minH), false))
        }
        if (v is TextView) {
            val text = v.text
            val desired = Layout.getDesiredWidth(text, v.paint) / dd + (v.paddingLeft + v.paddingRight) / dd
            val lh = (v as? KitText)?.style?.lineHeight ?: 0f
            val single = v.maxLines == 1 || maxW.isNaN() || desired <= maxW
            val lines = if (single) 1 else {
                v.measure(MeasureSpec.makeMeasureSpec((maxW * dd).roundToInt(), MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
                v.layout?.lineCount ?: 1
            }
            val fm = v.paint.fontMetrics
            val line = if (lh > 0f) lh * v.textSize / dd else (fm.descent - fm.ascent) / dd
            val w = if (single) desired else maxW
            return floatArrayOf(w, line * lines + (v.paddingTop + v.paddingBottom) / dd)
        }
        v.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        return floatArrayOf(v.measuredWidth / dd, v.measuredHeight / dd)
    }

    /** This box's fixed size from its parent's [Item], or [content] plus padding. */
    private fun fixedOr(content: Float, horizontal: Boolean): Float {
        val p = layoutParams as? Item
        val fixed = if (horizontal) p?.w else p?.h
        return if (fixed != null && !fixed.isNaN()) fixed else content
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dd = d
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val outerW = if (wMode == MeasureSpec.UNSPECIFIED) Float.NaN else outerWidth(widthMeasureSpec)
        val outerH = if (hMode == MeasureSpec.UNSPECIFIED) Float.NaN else outerHeight(heightMeasureSpec)
        // the border is inside the box, around the padding
        val bw = if (box.border > 0f) ro.rdstr.ui.kit.UiScale.borderPx(box.border, dd) / dd else 0f
        val padL = padL + bw; val padT = padT + bw; val padR = padR + bw; val padB = padB + bw
        val kids = kids()
        val n = kids.size
        val items = kids.map { it.layoutParams as Item }
        val innerMainAvail = if (column) (if (hMode == MeasureSpec.EXACTLY) outerH - padT - padB else Float.NaN)
            else (if (wMode != MeasureSpec.UNSPECIFIED) outerW - padL - padR else Float.NaN)
        val innerCrossAvail = if (column) (if (wMode != MeasureSpec.UNSPECIFIED) outerW - padL - padR else Float.NaN)
            else (if (hMode == MeasureSpec.EXACTLY) outerH - padT - padB else Float.NaN)
        // main sizes
        val main = FloatArray(n)
        val cross = FloatArray(n)
        for (i in 0 until n) {
            val it = items[i]
            val fixedMain = if (column) it.h else it.w
            val fixedCross = if (column) it.w else it.h
            val crossLimit = if (!fixedCross.isNaN()) fixedCross else innerCrossAvail - (if (column) it.mL + it.mR else it.mT + it.mB)
            val nat = natural(kids[i], if (column) crossLimit else Float.NaN)
            main[i] = if (!fixedMain.isNaN()) fixedMain else max(it.minMain, if (column) nat[1] else nat[0])
            cross[i] = if (!fixedCross.isNaN()) fixedCross else (if (column) nat[0] else nat[1])
        }
        val margins = (0 until n).sumOf { i -> items[i].let { (if (column) it.mT + it.mB else it.mL + it.mR).toDouble() } }.toFloat()
        val used = main.sum() + margins + gap * max(0, n - 1)
        if (!innerMainAvail.isNaN()) {
            val free = innerMainAvail - used
            val grows = items.sumOf { it.grow.toDouble() }.toFloat()
            if (free > 0f && grows > 0f) for (i in 0 until n) main[i] += free * items[i].grow / grows
            if (free < 0f) {
                val shrinkable = (0 until n).filter { items[it].shrink }
                val base = shrinkable.sumOf { main[it].toDouble() }.toFloat()
                if (base > 0f) for (i in shrinkable) main[i] = max(0f, main[i] + free * main[i] / base)
            }
        }
        // text that shrank in a row may wrap taller
        if (!column) for (i in 0 until n) if (items[i].h.isNaN() && kids[i] is TextView && (kids[i] as TextView).maxLines != 1) {
            cross[i] = natural(kids[i], main[i])[1]
        }
        val mainTotal = main.sum() + margins + gap * max(0, n - 1)
        val crossMax = (0 until n).maxOfOrNull { i -> cross[i] + items[i].let { if (column) it.mL + it.mR else it.mT + it.mB } } ?: 0f
        contentW = (if (column) crossMax else mainTotal) + padL + padR
        contentH = (if (column) mainTotal else crossMax) + padT + padB
        val myW = when (wMode) { MeasureSpec.EXACTLY -> outerW; MeasureSpec.AT_MOST -> if (column || justify != Justify.START || items.any { it.grow > 0f }) outerW else minOf(outerW, contentW); else -> contentW }
        val myH = if (hMode == MeasureSpec.EXACTLY) outerH else maxOf(contentH, minH)
        val innerMain = if (column) myH - padT - padB else myW - padL - padR
        val innerCross = if (column) myW - padL - padR else myH - padT - padB
        // place along the main axis
        val free = innerMain - mainTotal
        val pushed = items.indexOfFirst { it.pushEnd }
        var at = (if (column) padT else padL) + if (pushed >= 0) 0f else when (justify) {
            Justify.CENTER -> free / 2f; Justify.END -> free; else -> 0f
        }
        val between = if (justify == Justify.BETWEEN && n > 1) gap + free / (n - 1) else gap
        for (i in 0 until n) {
            val it = items[i]
            val mStart = if (column) it.mT else it.mL
            val mEnd = if (column) it.mB else it.mR
            val cStart = if (column) it.mL else it.mT
            val cEnd = if (column) it.mR else it.mB
            val a = it.alignSelf ?: align
            val fixedCross = if (column) it.w else it.h
            val c = if (a == Align.STRETCH && fixedCross.isNaN()) innerCross - cStart - cEnd else cross[i]
            val cAt = (if (column) padL else padT) + cStart + when (a) {
                Align.CENTER -> (innerCross - cStart - cEnd - c) / 2f; Align.END -> innerCross - cStart - cEnd - c; else -> 0f
            }
            if (i == pushed && free > 0f) at += free
            val mAt = at + mStart
            if (column) { it.x = cAt; it.y = mAt; it.fw = c; it.fh = main[i] } else { it.x = mAt; it.y = cAt; it.fw = main[i]; it.fh = c }
            at = mAt + main[i] + mEnd + between
            snap(kids[i], it, it.x + errX, it.y + errY, it.fw, it.fh, dd)
        }
        setMeasuredDimension((myW * dd).roundToInt(), (myH * dd).roundToInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (v in kids()) {
            val it = v.layoutParams as Item
            v.layout(it.px, it.py, it.px + v.measuredWidth, it.py + v.measuredHeight)
        }
    }

    companion object {
        /** Measures [v] to the device pixels of the exact box ([x], [y], [w], [h]) and tells a [Flex] its error. */
        internal fun snap(v: View, it: Item, x: Float, y: Float, w: Float, h: Float, dd: Float) {
            val l = (x * dd).roundToInt(); val t = (y * dd).roundToInt()
            it.px = l; it.py = t
            // a measure with the same specs is skipped, and a content measure in between may have placed the children elsewhere
            if (v is Flex) { v.errX = x - l / dd; v.errY = y - t / dd; v.exactW = w; v.exactH = h; v.forceLayout() }
            // a line that fits its exact box is not cut for the fraction of a pixel the snapped box lost: it gets the pixel
            var extra = 0
            if (v is TextView && v.maxLines == 1) {
                val over = Layout.getDesiredWidth(v.text, v.paint) + v.totalPaddingLeft + v.totalPaddingRight - (((x + w) * dd).roundToInt() - l)
                if (over > 0f && over <= 1f) extra = 1
            }
            v.measure(MeasureSpec.makeMeasureSpec(((x + w) * dd).roundToInt() - l + extra, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(((y + h) * dd).roundToInt() - t, MeasureSpec.EXACTLY))
        }
    }

    // the press: a scale and an :active fill
    private val scale = ro.rdstr.ui.kit.Transition(1f)

    override fun setPressed(pressed: Boolean) {
        val was = isPressed
        super.setPressed(pressed)
        if (was == pressed) return
        if (pressScale != 1f) {
            scale.to(if (pressed) pressScale else 1f, Motion.now(), UiTheme.duration(if (pressed) pressInMs else pressOutMs), pressEase)
        }
        pressedFill?.let { f -> if (pressed) { restFill = box.fill; box.fill = f } else box.fill = restFill }
        onPressed?.invoke(pressed)
        invalidate()
    }

    /** Sets the box fill, remembered as the rest fill while a press shows [pressedFill]. */
    fun fill(c: Int) { if (isPressed && pressedFill != null) restFill = c else box.fill = c; invalidate() }

    override fun draw(canvas: Canvas) {
        val now = Motion.now()
        val s = scale.value(now)
        if (s != 1f) { canvas.save(); canvas.scale(s, s, width / 2f, height / 2f) }
        super.draw(canvas)
        if (s != 1f) canvas.restore()
        if (scale.running(now)) postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = if (isClickable) super.onTouchEvent(event) else false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // an outer box-shadow paints outside the box, which the parent would clip
        if (box.shadows.any { !it.inset }) (parent as? ViewGroup)?.clipChildren = false
    }
}

/** A text element: [KitText] with its key, one line ellipsized unless [lines] says otherwise. */
fun text(context: Context, key: String, style: TextStyle, lines: Int = 1): KitText = KitText(context, style).apply {
    uiKey = key
    // advances in fractions of a pixel, unhinted, as the browser lays text out
    paintFlags = paintFlags or android.graphics.Paint.SUBPIXEL_TEXT_FLAG or android.graphics.Paint.LINEAR_TEXT_FLAG
    // one line laid out at the box's width (a single line otherwise scrolls inside a very wide layout)
    if (lines == 1) { isSingleLine = true; setHorizontallyScrolling(false); ellipsize = TextUtils.TruncateAt.END } else maxLines = lines
    // `line-height: normal` is the font's ascent, descent and line gap, the gap split above and below
    if (style.lineHeight == 0f) paint.fontMetrics.let { fm -> if (fm.leading > 0f) this.style = TextStyle(style.size, style.weight, style.tracking, (fm.descent - fm.ascent + fm.leading) / textSize) }
}

/** An icon element (`span.icon.i-…`): its glyph masked in [IconView.color]; its background is the mask's, a box. */
fun icon(context: Context, key: String, res: Int, size: Float): IconView = IconView(context, res, size).apply {
    uiKey = key
    background = BoxDrawable(this, CssBox())
}
