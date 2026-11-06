package ro.rdstr.ui.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiScale
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.uiKey
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** CSS alignment values: `align-items`/`align-self` (START, CENTER, END, STRETCH) and `justify-content` (BETWEEN too). */
enum class Align { START, CENTER, END, STRETCH, BETWEEN }

/** How a [Box] lays out its children: flex row or column, a grid of fixed or fr tracks, wrapping rows, or one cell. */
enum class Dir { ROW, COLUMN, GRID, WRAP, STACK }

/**
 * A child's CSS properties in a [Box], in canvas px (NaN = auto), and the box the layout gave it ([x], [y], [cw],
 * [ch], relative to the parent's border box, unrounded as the browser lays out).
 */
class Lp : ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT) {
    var w = Float.NaN
    var h = Float.NaN
    var minW = 0f
    var minH = 0f
    var maxW = Float.POSITIVE_INFINITY
    var maxH = Float.POSITIVE_INFINITY
    /** `max-width: [maxFrac] * 100%` of the parent's content box. */
    var maxFrac = 0f
    /** `flex: [grow]`: from a basis of 0, a share of the room left. */
    var grow = 0f
    /** `min-width: 0` on a flex item: it may shrink under its content (a text then ends in an ellipsis). */
    var shrink = false
    var self: Align? = null
    var ml = 0f; var mt = 0f; var mr = 0f; var mb = 0f
    /** `position: absolute` within the padding box, at whichever of the offsets are set. */
    var abs = false
    var left = Float.NaN; var top = Float.NaN; var right = Float.NaN; var bottom = Float.NaN
    /** `position: sticky; top: [stickTop]` inside the nearest scrolling [Box]. */
    var sticky = false
    var stickTop = 0f

    var x = 0f; var y = 0f; var cw = 0f; var ch = 0f
}

val View.lp: Lp get() = (layoutParams as? Lp) ?: (this as? Box)?.own ?: Lp().also { layoutParams = it }

/**
 * A CSS box: a flex, grid or single-cell layout of its children in canvas px, and its own painting ([css]: shadows,
 * fill, sheen, border; [topLine] for a `border-top` alone) with an optional pressed look ([pressFill], [pressScale]).
 *
 * The layout is computed once for the whole tree by the outermost box, unrounded, as the browser lays out; each view
 * is then placed on the pixel grid the way the browser snaps a box when it paints (its edges rounded to whole pixels,
 * from the screen's origin). A box with [scrolls] scrolls its content vertically and keeps its [Lp.sticky] children
 * in view.
 */
@SuppressLint("ViewConstructor")
open class Box(context: Context, key: String? = null) : ViewGroup(context) {
    protected val d: Float get() = resources.displayMetrics.density

    var dir = Dir.COLUMN
    /** Gap along the main axis (a grid's column gap) and across it (a grid's or wrap's row gap). */
    var gap = 0f
    var gap2 = 0f
    var padL = 0f; var padT = 0f; var padR = 0f; var padB = 0f
    var align = Align.STRETCH
    var justify = Align.START
    /** Grid columns: positive in px, negative as fr weights (`minmax(0, 1fr)`). */
    var tracks = FloatArray(0)
    var justifyItems = Align.STRETCH

    val css = CssBox()
    /** Its own CSS properties while it is not in a [Box] (the outermost box keeps its parent's layout params). */
    internal val own = Lp()
    /** A second background image over [css] (the glass sheen over a gradient of its own). */
    var sheen: CssBox? = null
    /** Paints [css]; off for plain containers. */
    var painted = false
    /** `border-top: 1px solid [topLine]` (0 for none); the box's content starts below it. */
    var topLine = 0
    /** The background while pressed, eased over `--fast`; 0 keeps [css]'s fill. */
    var pressFill = 0
    var restFill = 0
    var pressScale = 1f
    var pressInMs = 120L
    var pressOutMs = 300L
    var pressEase: Easing = Motion.springGlyph
    /** Called at attach and after every theme change, to set colours. */
    var onTheme: (Box.() -> Unit)? = null
        set(v) { field = v; if (isAttachedToWindow) v?.invoke(this) }
    var scrolls = false

    /** Where the layout put this box on the screen, unrounded canvas px (set by the parent before it lays out). */
    internal var absX = 0f
    internal var absY = 0f
    private var laid = false

    private val fill = ColorTransition(0)
    private val scale = Transition(1f)
    private val linePaint = Paint()

    init {
        uiKey = key
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        UiTheme.link(this) { onTheme?.invoke(this); fill.snap(currentFill()); invalidate() }
    }

    private fun currentFill() = if (isPressed && pressFill != 0) pressFill else restFill

    /** Adds [v] with its CSS properties set by [props]; returns it. */
    fun <V : View> add(v: V, props: Lp.() -> Unit = {}): V {
        addView(v, ((v.layoutParams as? Lp) ?: (v as? Box)?.own ?: Lp()).apply(props))
        return v
    }

    override fun generateLayoutParams(p: LayoutParams?) = Lp()
    override fun checkLayoutParams(p: LayoutParams?) = p is Lp
    override fun generateDefaultLayoutParams() = Lp()
    override fun shouldDelayChildPressedState() = scrolls

    // ---- layout -------------------------------------------------------------------------------------------------

    private val flowKids = ArrayList<View>()

    /** Lays this box out at width [fw] and height [fh] (NaN: from the content, [fw] at most [maxW]); sets its size. */
    internal fun layoutCss(fw: Float, fh: Float, maxW: Float) {
        val lp = lp
        val b = borderW()
        val left = padL + b
        val padH = left + padR + b
        val top = padT + lineW() + b
        val padV = top + padB + b
        var innerW = if (fw.isNaN()) Float.NaN else max(0f, fw - padH)
        val innerMaxW = if (fw.isNaN()) max(0f, min(maxW, lp.maxW) - padH) else innerW
        val minInnerH = max(0f, lp.minH - padV)
        val innerH = if (fh.isNaN()) Float.NaN else max(0f, fh - padV)
        flowKids.clear()
        for (i in 0 until childCount) getChildAt(i).let { if (it.visibility != GONE && !it.lp.abs) flowKids += it }
        val content = when (dir) {
            Dir.ROW -> row(innerW, innerMaxW, innerH, minInnerH)
            Dir.COLUMN -> column(innerW, innerMaxW, innerH, minInnerH)
            Dir.GRID -> grid(innerW, innerH)
            Dir.WRAP -> wrap(innerW, innerMaxW)
            Dir.STACK -> stack(innerW, innerMaxW, innerH, minInnerH)
        }
        if (innerW.isNaN()) innerW = content[0]
        lp.cw = max(innerW + padH, lp.minW)
        lp.ch = if (fh.isNaN()) max(content[1] + padV, lp.minH) else fh
        for (k in flowKids) { val c = k.lp; c.x += left; c.y += top }
        // absolute children against the padding box
        for (i in 0 until childCount) {
            val k = getChildAt(i)
            val c = k.lp
            if (k.visibility == GONE || !c.abs) continue
            val bw = lp.cw - 2 * b; val bh = lp.ch - 2 * b
            val w = if (!c.w.isNaN()) c.w else if (!c.left.isNaN() && !c.right.isNaN()) bw - c.left - c.right else Float.NaN
            val h = if (!c.h.isNaN()) c.h else if (!c.top.isNaN() && !c.bottom.isNaN()) bh - c.top - c.bottom else Float.NaN
            size(k, w, h, bw)
            c.x = b + if (!c.left.isNaN()) c.left else if (!c.right.isNaN()) bw - c.right - c.cw else if (c.self == Align.CENTER) (bw - c.cw) / 2f else padL
            c.y = b + if (!c.top.isNaN()) c.top else if (!c.bottom.isNaN()) bh - c.bottom - c.ch else padT
        }
        laid = true
    }

    private fun lineW() = if (topLine != 0) UiScale.borderPx(1f, d) / d else 0f
    /** The border as the browser draws it: whole device pixels. */
    private fun borderW() = if (css.border > 0f) UiScale.borderPx(css.border, d) / d else 0f

    private fun outerW(c: Lp) = c.cw + c.ml + c.mr
    private fun outerH(c: Lp) = c.ch + c.mt + c.mb
    private fun alignOf(c: Lp) = c.self ?: align

    private fun row(innerW: Float, maxInner: Float, innerH: Float, minInnerH: Float): FloatArray {
        val kids = flowKids
        for (k in kids) size(k, Float.NaN, Float.NaN, maxInner)
        val gaps = gap * max(0, kids.size - 1)
        var content = gaps
        for (k in kids) content += outerW(k.lp)
        val lineW = if (innerW.isNaN()) min(content, maxInner) else innerW
        // `flex: 1` items start from nothing and share the room left
        var total = gaps
        for (k in kids) total += if (k.lp.grow > 0f) k.lp.ml + k.lp.mr else outerW(k.lp)
        var free = lineW - total
        val grow = kids.sumOf { it.lp.grow.toDouble() }.toFloat()
        if (grow > 0f) {
            val share = max(0f, free)
            for (k in kids) if (k.lp.grow > 0f) {
                val w = min(share * k.lp.grow / grow, k.lp.maxW)
                size(k, w, Float.NaN, maxInner)
                free -= k.lp.cw
            }
        }
        if (free < 0f) free = shrink(kids, -free, horizontal = true, maxInner)
        val lineH = if (!innerH.isNaN()) innerH else max(minInnerH, kids.maxOfOrNull { outerH(it.lp) } ?: 0f)
        for (k in kids) {
            val c = k.lp
            if (alignOf(c) == Align.STRETCH && c.h.isNaN()) size(k, c.cw, lineH - c.mt - c.mb, maxInner)
        }
        var x = start(free, kids.size)
        val between = if (justify == Align.BETWEEN && kids.size > 1) free / (kids.size - 1) else 0f
        for (k in kids) {
            val c = k.lp
            c.x = x + c.ml
            c.y = cross(alignOf(c), lineH, c.ch, c.mt, c.mb)
            x += outerW(c) + gap + between
        }
        return floatArrayOf(lineW, lineH)
    }

    private fun column(innerW: Float, maxInner: Float, innerH: Float, minInnerH: Float): FloatArray {
        val kids = flowKids
        // widths: stretched to the column's width, else their own within it
        var widest = 0f
        for (k in kids) {
            val c = k.lp
            val stretch = alignOf(c) == Align.STRETCH && c.w.isNaN()
            val avail = ((if (innerW.isNaN()) maxInner else innerW) - c.ml - c.mr).let { if (c.maxFrac > 0f) it * c.maxFrac else it }
            size(k, if (stretch && !innerW.isNaN()) avail else Float.NaN, Float.NaN, avail)
            widest = max(widest, outerW(c))
        }
        val colW = if (innerW.isNaN()) min(widest, maxInner) else innerW
        if (innerW.isNaN()) for (k in kids) {
            val c = k.lp
            if (alignOf(c) == Align.STRETCH && c.w.isNaN() && c.cw != colW - c.ml - c.mr) size(k, colW - c.ml - c.mr, Float.NaN, colW)
        }
        val gaps = gap * max(0, kids.size - 1)
        var content = gaps
        for (k in kids) content += outerH(k.lp)
        val colH = if (innerH.isNaN()) max(content, minInnerH) else innerH
        var total = gaps
        for (k in kids) total += if (k.lp.grow > 0f && !innerH.isNaN()) k.lp.mt + k.lp.mb else outerH(k.lp)
        var free = colH - total
        val grow = kids.sumOf { it.lp.grow.toDouble() }.toFloat()
        if (grow > 0f && !innerH.isNaN()) {
            val share = max(0f, free)
            for (k in kids) if (k.lp.grow > 0f) {
                val h = max(min(share * k.lp.grow / grow, k.lp.maxH), k.lp.minH)
                size(k, k.lp.cw, h, colW)
                free -= k.lp.ch
            }
        }
        if (free < 0f) free = shrink(kids, -free, horizontal = false, colW)
        var y = start(free, kids.size)
        val between = if (justify == Align.BETWEEN && kids.size > 1) free / (kids.size - 1) else 0f
        for (k in kids) {
            val c = k.lp
            c.y = y + c.mt
            c.x = cross(alignOf(c), colW, c.cw, c.ml, c.mr)
            y += outerH(c) + gap + between
        }
        return floatArrayOf(colW, colH)
    }

    /** Takes [over] px from the children that may shrink, in proportion to their size; returns the space left (≤ 0). */
    private fun shrink(kids: List<View>, over: Float, horizontal: Boolean, maxInner: Float): Float {
        val able = kids.filter { it.lp.shrink }
        val base = able.sumOf { (if (horizontal) it.lp.cw else it.lp.ch).toDouble() }.toFloat()
        if (base <= 0f) return -over
        var left = over
        for (k in able) {
            val c = k.lp
            val s = if (horizontal) c.cw else c.ch
            val cut = min(s, over * s / base)
            left -= cut
            if (horizontal) size(k, s - cut, Float.NaN, maxInner) else size(k, c.cw, s - cut, maxInner)
        }
        return -max(0f, left)
    }

    private fun start(free: Float, n: Int) = when (justify) {
        Align.CENTER -> free / 2f
        Align.END -> free
        else -> 0f
    }

    private fun cross(a: Align, line: Float, size: Float, m1: Float, m2: Float) = when (a) {
        Align.CENTER -> m1 + (line - m1 - m2 - size) / 2f
        Align.END -> line - m2 - size
        else -> m1
    }

    private fun grid(innerW: Float, innerH: Float): FloatArray {
        val kids = flowKids
        val n = tracks.size.coerceAtLeast(1)
        val fixed = tracks.filter { it > 0f }.sum()
        val fr = -tracks.filter { it < 0f }.sum()
        val room = (if (innerW.isNaN()) fixed else innerW) - fixed - gap * (n - 1)
        val cols = FloatArray(n) { val t = tracks.getOrElse(it) { -1f }; if (t > 0f) t else if (fr > 0f) max(0f, room) * -t / fr else 0f }
        val rows = (kids.size + n - 1) / n
        val heights = FloatArray(rows)
        kids.forEachIndexed { i, k ->
            val c = k.lp
            val cw = cols[i % n] - c.ml - c.mr
            size(k, if (c.w.isNaN() && justifyItems == Align.STRETCH) cw else Float.NaN, Float.NaN, cw)
            heights[i / n] = max(heights[i / n], outerH(c))
        }
        // auto rows share the height left over (align-content: normal)
        if (!innerH.isNaN() && rows > 0) {
            val extra = innerH - heights.sum() - gap2 * (rows - 1)
            if (extra > 0f) for (r in 0 until rows) heights[r] += extra / rows
        }
        var y = 0f
        kids.forEachIndexed { i, k ->
            val c = k.lp
            val col = i % n
            val row = i / n
            if (col == 0 && row > 0) y += heights[row - 1] + gap2
            if (alignOf(c) == Align.STRETCH && c.h.isNaN()) size(k, c.cw, heights[row] - c.mt - c.mb, cols[col])
            var x = 0f
            for (j in 0 until col) x += cols[j] + gap
            c.x = x + cross(justifyItems, cols[col], c.cw, c.ml, c.mr)
            c.y = y + cross(alignOf(c), heights[row], c.ch, c.mt, c.mb)
        }
        return floatArrayOf(cols.sum() + gap * (n - 1), heights.sum() + gap2 * max(0, rows - 1))
    }

    private fun wrap(innerW: Float, maxInner: Float): FloatArray {
        val lineMax = if (innerW.isNaN()) maxInner else innerW
        var x = 0f
        var y = 0f
        var lineH = 0f
        var widest = 0f
        for (k in flowKids) {
            val c = k.lp
            size(k, Float.NaN, Float.NaN, lineMax)
            if (x > 0f && x + outerW(c) > lineMax) { y += lineH + gap2; x = 0f; lineH = 0f }
            c.x = x + c.ml; c.y = y + c.mt
            x += outerW(c) + gap
            lineH = max(lineH, outerH(c))
            widest = max(widest, x - gap)
        }
        return floatArrayOf(if (innerW.isNaN()) widest else innerW, if (flowKids.isEmpty()) 0f else y + lineH)
    }

    private fun stack(innerW: Float, maxInner: Float, innerH: Float, minInnerH: Float): FloatArray {
        var w = 0f
        var h = 0f
        for (k in flowKids) {
            val c = k.lp
            size(k, if (c.w.isNaN() && justify == Align.STRETCH && !innerW.isNaN()) innerW - c.ml - c.mr else Float.NaN, Float.NaN, maxInner)
            w = max(w, outerW(c)); h = max(h, outerH(c))
        }
        val bw = if (innerW.isNaN()) min(w, maxInner) else innerW
        val bh = if (innerH.isNaN()) max(h, minInnerH) else innerH
        for (k in flowKids) {
            val c = k.lp
            if (alignOf(c) == Align.STRETCH && c.h.isNaN()) size(k, c.cw, bh - c.mt - c.mb, bw)
            c.x = cross(if (justify == Align.BETWEEN) Align.START else justify, bw, c.cw, c.ml, c.mr)
            c.y = cross(alignOf(c), bh, c.ch, c.mt, c.mb)
        }
        return floatArrayOf(bw, bh)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentBox = parent is Box
        if (!parentBox) {
            // the outermost box: lay the whole tree out from the space it is given
            val wm = MeasureSpec.getMode(widthMeasureSpec)
            val hm = MeasureSpec.getMode(heightMeasureSpec)
            val ws = MeasureSpec.getSize(widthMeasureSpec) / d
            val hs = MeasureSpec.getSize(heightMeasureSpec) / d
            val lp = lp
            val fw = if (!lp.w.isNaN()) lp.w else if (wm == MeasureSpec.EXACTLY) ws else Float.NaN
            val fh = if (!lp.h.isNaN()) lp.h else if (hm == MeasureSpec.EXACTLY) hs else Float.NaN
            layoutCss(fw, fh, if (wm == MeasureSpec.UNSPECIFIED) Float.POSITIVE_INFINITY else ws)
            lp.x = 0f; lp.y = 0f
        }
        val lp = lp
        setMeasuredDimension(
            ((absX + lp.cw) * d).roundToInt() - (absX * d).roundToInt(),
            ((absY + lp.ch) * d).roundToInt() - (absY * d).roundToInt(),
        )
        if (!parentBox) measureKids()
    }

    /** Measures every child at the pixel size its rounded edges give, from this box's screen position. */
    private fun measureKids() {
        for (i in 0 until childCount) {
            val k = getChildAt(i)
            if (k.visibility == GONE) continue
            val c = k.lp
            val ax = absX + c.x
            val ay = absY + c.y
            val w = ((ax + c.cw) * d).roundToInt() - (ax * d).roundToInt()
            val h = ((ay + c.ch) * d).roundToInt() - (ay * d).roundToInt()
            if (k is Box) { k.absX = ax; k.absY = ay }
            if (k is Txt) k.subX = ax * d - (ax * d).roundToInt()
            k.measure(MeasureSpec.makeMeasureSpec(max(0, w), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(max(0, h), MeasureSpec.EXACTLY))
            if (k is Box) k.measureKids()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val ox = (absX * d).roundToInt()
        val oy = (absY * d).roundToInt()
        for (i in 0 until childCount) {
            val k = getChildAt(i)
            if (k.visibility == GONE) continue
            val c = k.lp
            val x = ((absX + c.x) * d).roundToInt() - ox
            val y = ((absY + c.y) * d).roundToInt() - oy
            k.layout(x, y, x + k.measuredWidth, y + k.measuredHeight)
        }
        if (scrolls) { scrollTo(0, scrollY.coerceIn(0, maxScroll())); stick() }
    }

    // ---- painting -----------------------------------------------------------------------------------------------

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val now = Motion.now()
        val on = isPressed
        if (pressFill != 0) fill.to(if (on) pressFill else restFill, now, UiTheme.duration(if (on) 0 else UiTheme.base.fast), Easing.EASE)
        if (pressScale != 1f) scale.to(if (on) pressScale else 1f, now, UiTheme.duration(if (on) pressInMs else pressOutMs), pressEase)
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        val now = Motion.now()
        val s = scale.value(now)
        if (s != 1f) { canvas.save(); canvas.scale(s, s, width / 2f, height / 2f) }
        super.draw(canvas)
        if (s != 1f) canvas.restore()
        if (scale.running(now) || fill.running(now)) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (scrolls) canvas.translate(0f, scrollY.toFloat())
        if (painted) {
            css.fill = fill.value(Motion.now())
            css.draw(canvas, this, width.toFloat(), height.toFloat())
            sheen?.draw(canvas, this, width.toFloat(), height.toFloat())
        }
        if (topLine != 0) { linePaint.color = topLine; canvas.drawRect(0f, 0f, width.toFloat(), UiScale.borderPx(1f, d), linePaint) }
        if (scrolls) canvas.translate(0f, -scrollY.toFloat())
    }

    /** Sets the resting fill (and the pressed one) of [css]. */
    fun fillWith(rest: Int, pressed: Int = 0) {
        restFill = rest; pressFill = pressed; css.fill = rest; fill.snap(if (isPressed && pressed != 0) pressed else rest); painted = true; invalidate()
    }

    /** Eases the resting fill to [rest] over [ms] (a CSS `transition: background`); [pressed] as in [fillWith]. */
    fun fadeTo(rest: Int, ms: Long, pressed: Int = 0, ease: Easing = Motion.seamless) {
        restFill = rest; pressFill = pressed; painted = true
        if (!isPressed || pressed == 0) fill.to(rest, Motion.now(), UiTheme.duration(ms), ease)
        invalidate()
    }

    // ---- scrolling ----------------------------------------------------------------------------------------------

    private val scroller by lazy { OverScroller(context) }
    private var velocity: VelocityTracker? = null
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRect = RectF()
    /** The scrollbar thumb's colour (`::-webkit-scrollbar-thumb`), 0 for none. */
    var thumbColor = 0

    private fun contentH(): Float {
        var h = 0f
        for (i in 0 until childCount) getChildAt(i).let { if (it.visibility != GONE) h = max(h, it.lp.y + it.lp.ch + it.lp.mb) }
        return h + padB
    }

    private fun maxScroll() = max(0, (contentH() * d).roundToInt() - height)

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (!scrolls) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; lastY = e.y; dragging = !scroller.isFinished; scroller.forceFinished(true) }
            MotionEvent.ACTION_MOVE -> if (abs(e.y - downY) > slop && maxScroll() > 0) { dragging = true; lastY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!scrolls) return super.onTouchEvent(e)
        val v = velocity ?: VelocityTracker.obtain().also { velocity = it }
        v.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; lastY = e.y }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.y - downY) > slop) dragging = true
                if (dragging) { scrollTo(0, (scrollY + (lastY - e.y)).roundToInt().coerceIn(0, maxScroll())); lastY = e.y }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    v.computeCurrentVelocity(1000)
                    scroller.fling(0, scrollY, 0, -v.yVelocity.roundToInt(), 0, 0, 0, maxScroll())
                    postInvalidateOnAnimation()
                }
                dragging = false
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scrolls && scroller.computeScrollOffset()) { scrollTo(0, scroller.currY); postInvalidateOnAnimation() }
    }

    /** Scrolls so the child [v] is at the top (`scrollIntoView({ block: "start" })`), smoothly unless motion is off. */
    fun scrollToChild(v: View) {
        val target = (v.lp.y * d).roundToInt().coerceIn(0, maxScroll())
        if (UiTheme.speed <= 0f) scrollTo(0, target)
        else { scroller.startScroll(0, scrollY, 0, target - scrollY, UiTheme.duration(400).toInt()); postInvalidateOnAnimation() }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        stick()
    }

    /** Sticky children stay at their top offset while their own box is still in view (CSS sticky positioning). */
    private fun stick() {
        val top = scrollY / d
        for (i in 0 until childCount) stickIn(getChildAt(i), 0f, top, Float.POSITIVE_INFINITY)
    }

    private fun stickIn(v: View, originY: Float, top: Float, limit: Float) {
        val c = v.lp
        val y = originY + c.y
        if (c.sticky) v.translationY = (min(max(0f, top + c.stickTop - y), limit - y - c.ch) * d).coerceAtLeast(0f)
        if (v is Box && !v.scrolls) for (i in 0 until v.childCount) stickIn(v.getChildAt(i), y, top, y + c.ch)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!scrolls || thumbColor == 0) return
        val content = contentH() * d
        if (content <= height + 0.5f) return
        // ::-webkit-scrollbar { width: 4px }, its thumb rounded 2px: the visible share of the content, at its place
        val track = height.toFloat()
        val h = max(track * track / content, 18 * d)
        val y = scrollY + (track - h) * scrollY / (content - track)
        thumbRect.set(width - 4 * d, y, width.toFloat(), y + h)
        thumb.color = thumbColor
        canvas.drawRoundRect(thumbRect, 2 * d, 2 * d, thumb)
    }

    companion object {
        /** Sizes [v] as a CSS box: [w] and [h] definite unless NaN; an auto width takes its content, at most [maxW]. */
        internal fun size(v: View, w: Float, h: Float, maxW: Float) {
            val c = v.lp
            val fw = if (!w.isNaN()) w else c.w
            val fh = if (!h.isNaN()) h else c.h
            val cap = min(maxW, c.maxW)
            when (v) {
                is Box -> v.layoutCss(fw, fh, cap)
                is Txt -> {
                    c.cw = if (!fw.isNaN()) fw else min(v.contentWidth(), cap)
                    c.ch = if (!fh.isNaN()) fh else v.contentHeight(c.cw)
                }
                else -> {
                    val dd = v.resources.displayMetrics.density
                    if (fw.isNaN() || fh.isNaN()) v.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                    c.cw = if (!fw.isNaN()) fw else v.measuredWidth / dd
                    c.ch = if (!fh.isNaN()) fh else v.measuredHeight / dd
                }
            }
            c.cw = max(c.cw, c.minW)
            c.ch = max(c.ch, c.minH)
        }
    }
}

/** A round-rect path helper for clips. */
internal fun Path.roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float) { reset(); addRoundRect(l, t, r, b, radius, radius, Path.Direction.CW) }
