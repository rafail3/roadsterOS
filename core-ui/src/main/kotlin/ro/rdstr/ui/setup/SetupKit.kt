package ro.rdstr.ui.setup

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import ro.rdstr.car.Setup
import ro.rdstr.ui.UiPart
import ro.rdstr.ui.UiParts
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Type
import ro.rdstr.ui.kit.UiScale
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Base
import ro.rdstr.ui.tokens.Sf
import ro.rdstr.ui.uiKey
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// The setup's own pieces (screen.css `.sf-*`): the frame's column, its buttons, rows, field and the boot screen's dots.
// Sizes are canvas px (dp here); colours are the theme's tokens (`Base`) and the setup's amber (`Sf`).

internal val View.d: Float get() = resources.displayMetrics.density
internal fun View.ipx(css: Float): Int = (css * d).roundToInt()

/** A length as the browser lays it out: device px floored to its 1/64 px layout unit. */
internal fun lu(px: Float): Float = floor(px * 64f + 1e-3f) / 64f

/** The look the setup draws with: the theme's tokens and the boot amber, the same in every theme. */
internal object SfLook {
    val base: Base get() = Base.of(UiTheme.light, UiTheme.glassy)
    val sf: Sf get() = Sf.of(UiTheme.light, UiTheme.glassy)

    /** `.sf` glow: `rgba(255, 138, 26, 0.10)` */
    const val GLOW = 0x1AFF8A1A
    /** `.sf::after`: `rgba(0, 0, 0, 0.28)`, one unit pixel in three */
    const val SCANLINE = 0x47000000
    /** `.sf-field.is-typing`: `rgba(255, 138, 26, 0.55)` */
    const val TYPING_BORDER = 0x8CFF8A1A.toInt()
    /** `.sf-row.is-connected`: `rgba(255, 164, 27, 0.10)` */
    const val CONNECTED_FILL = 0x1AFFA41B
    /** `.sf-row.is-running, .sf-row.is-done`: `rgba(255, 138, 26, 0.35)` */
    const val RUN_BORDER = 0x59FF8A1A
}

/** A text of the setup: a [KitText] in [style], one line unless [lines], under [key]. */
internal fun Context.sfText(key: String, style: TextStyle, lines: Int = 1): KitText = KitText(this, style).apply {
    uiKey = key
    if (lines == 1) { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
}

/**
 * A flex column: children one under the other, each at its CSS top margin and left offset (canvas px), stretched to
 * the width or at their own ([Params.stretch]). Positions run unrounded, as the browser lays them out, and each child
 * sits on the nearest whole pixel; [Params.fraction] keeps what was rounded away, for dots that snap as the browser's.
 */
open class SfColumn(context: Context) : ViewGroup(context) {
    class Params(val top: Float = 0f, val left: Float = 0f, val stretch: Boolean = true, val right: Float = 0f) :
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {
        var fractionX = 0f
        var fractionY = 0f
    }

    /** `padding` in canvas px: top, sides, bottom. */
    var padTop = 0f
    var padSide = 0f
    var padBottom = 0f
    /** `gap` between the children shown. */
    var gap = 0f

    override fun generateDefaultLayoutParams() = Params()
    override fun checkLayoutParams(p: LayoutParams?) = p is Params

    private fun params(v: View) = v.layoutParams as? Params ?: Params().also { v.layoutParams = it }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - 2 * padSide * d
        var y = padTop * d
        var first = true
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val p = params(c)
            val w = (inner - (p.left + p.right) * d).roundToInt().coerceAtLeast(0)
            c.measure(MeasureSpec.makeMeasureSpec(w, if (p.stretch) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            y += (p.top + if (first) 0f else gap) * d + c.measuredHeight
            first = false
        }
        y += padBottom * d
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else ceil(y - 1e-3f).toInt()
        setMeasuredDimension(width, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = padTop * d
        var first = true
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val p = params(c)
            y += (p.top + if (first) 0f else gap) * d
            first = false
            val x = lu((padSide + p.left) * d)
            val yy = lu(y)
            val left = x.roundToInt()
            val top = yy.roundToInt()
            p.fractionX = x - left
            p.fractionY = yy - top
            c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
            y += c.measuredHeight
        }
    }
}

/** A horizontal row of the setup's: [LinearLayout] with CSS gaps as margins between the children shown. */
internal fun LinearLayout.gapBefore(view: View, css: Float, size: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 0f) {
    val p = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
    if (childCount > 0) { if (orientation == LinearLayout.HORIZONTAL) p.leftMargin = (css * d).roundToInt() else p.topMargin = (css * d).roundToInt() }
    addView(view, p)
}

/** A [LinearLayout] that paints a CSS box behind its children; its insets are the box's border and [pad]. */
open class BoxLinear(context: Context) : LinearLayout(context), UiParts {
    val box = CssBox()
    override val uiBackground: Int? get() = box.fill

    init { setWillNotDraw(false) }

    /** Border (snapped as the browser does) plus [h], [v] canvas px of padding on each side. */
    fun pad(h: Float, v: Float = 0f, bottom: Float = v) {
        val b = UiScale.borderPx(box.border, d)
        setPadding((b + h * d).roundToInt(), (b + v * d).roundToInt(), (b + h * d).roundToInt(), (b + bottom * d).roundToInt())
    }

    override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
}

/**
 * `.sf-button`: 64 px tall, at least 150 wide, 28 px in from each side, fully round; `--glass-strong` with a
 * `--hairline-strong` edge, `--glass-pressed` while pressed; [primary] is the amber one, `--amber-line` pressed.
 * Disabled at 40 %. Text 20 px, 600.
 *
 * [segment] makes it a `.sf-segment` of the look screen's theme choice: 56 px tall, at least 130 wide, no edge nor
 * fill, `--ink-muted`; [primary] is then the chosen one, amber.
 */
@SuppressLint("ViewConstructor")
class SfButton(context: Context, primary: Boolean = false, private val segment: Boolean = false) : TextView(context), UiParts {
    var primary = primary; set(v) { if (field != v) { field = v; fill() } }
    private val box = CssBox().apply { radius = if (segment) 28f else 32f; border = if (segment) 0f else 1f }
    override val uiBackground: Int? get() = box.fill

    init {
        uiKey = if (segment) "sf-segment" else "sf-button"
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 1
        isClickable = true
        minWidth = ipx(if (segment) 130f else 150f)
        minimumWidth = minWidth
        if (!segment) setPadding(ipx(28f), 0, ipx(28f), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * d)
        fontFeatureSettings = Type.FEATURES
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        typeface = UiTheme.typeface(context)
        fontVariationSettings = "'wght' 600"
        fill()
    }

    private fun fill() {
        val b = SfLook.base
        val sf = SfLook.sf
        box.borderColor = if (primary) 0 else b.hairlineStrong
        box.fill = when {
            primary && isPressed && !segment -> sf.amberLine
            primary -> sf.amber
            segment -> 0
            isPressed -> b.glassPressed
            else -> b.glassStrong
        }
        setTextColor(when { primary -> sf.amberInk; segment -> b.inkMuted; else -> b.ink })
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    // called by TextView's constructor too, before this class has its box
    @Suppress("SENSELESS_COMPARISON")
    override fun drawableStateChanged() { super.drawableStateChanged(); if (box != null) fill() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(ipx(if (segment) 56f else 64f), MeasureSpec.EXACTLY))

    override fun onDraw(canvas: Canvas) {
        box.draw(canvas, this, width.toFloat(), height.toFloat())
        super.onDraw(canvas)
    }
}

/** Text in the boot screen's 5x7 pixel font (SetupScreens.tsx `PixelText`): [cell] canvas px per dot, upper case. */
class PixelText(context: Context, key: String) : View(context), UiParts {
    var text: String = ""; set(v) { if (field != v) { field = v; requestLayout(); invalidate(); contentDescription = v } }
    var color: Int = 0; set(v) { if (field != v) { field = v; invalidate() } }
    var cell = 2.5f
    override val uiColor: Int? get() = color

    init { uiKey = key }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((text.length * 6 * cell * d).roundToInt(), (7 * cell * d).roundToInt())

    override fun onDraw(canvas: Canvas) {
        BootPainter.shared.tinyText(canvas, text.uppercase(), 0.0, 0.0, (cell * d).toDouble(), color)
    }
}

/** The symbol, small (Boot.tsx `CubeMark`): the cube's dots in a square of [size] canvas px. */
@SuppressLint("ViewConstructor")
class CubeMark(context: Context, val size: Float) : View(context) {
    init { uiKey = "cube-mark" }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(ipx(size), ipx(size))

    override fun onDraw(canvas: Canvas) {
        // the cube spans about 76 dots each way
        val px = width.toDouble()
        BootPainter.shared.drawCube(canvas, px / 2, px / 2, px / 80)
    }
}

/**
 * Dots laid out by a CSS grid and painted as the browser paints boxes: each on its layout position (1/64 px), snapped
 * to whole pixels. [fractionX], [fractionY] are what the parent rounded away when it placed this view.
 */
abstract class DotGrid(context: Context) : View(context), UiParts {
    var fractionX = 0f
    var fractionY = 0f
    protected val paint = Paint()

    protected fun dot(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
        val l = (fractionX + x).roundToInt()
        val t = (fractionY + y).roundToInt()
        val r = (fractionX + x + w).roundToInt()
        val b = (fractionY + y + h).roundToInt()
        paint.color = color
        canvas.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), paint)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        (layoutParams as? SfColumn.Params)?.let { fractionX = it.fractionX; fractionY = it.fractionY }
    }
}

/**
 * `.sf-progress`: one block of 6x3 dots per screen of the setup, 14 px apart; full when done, a checkerboard on the
 * current one, faint (18 %) ahead. Dots 4 px, 1.6 px apart, `--amber-line`.
 */
class SfProgress(context: Context) : DotGrid(context) {
    /** The screen shown, 0..7. */
    var at = 0; set(v) { if (field != v) { field = v; invalidate() } }

    private val dotPx get() = lu(4f * d)
    private val gapPx get() = lu(1.6f * d)
    private val blockPx get() = 6 * dotPx + 5 * gapPx
    private val blockGap get() = lu(14f * d)

    init { uiKey = "sf-progress" }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((BLOCKS * blockPx + (BLOCKS - 1) * blockGap).roundToInt(), (3 * dotPx + 2 * gapPx).roundToInt())

    /** The checkerboard of the current block: dot [i] of 18, in rows of 6. */
    private fun lit(block: Int, i: Int) = block < at || (block == at && (i % 6 + i / 6) % 2 == 0)

    override fun onDraw(canvas: Canvas) {
        val c = SfLook.sf.amberLine
        val faint = ro.rdstr.ui.kit.CssColor.fade(c, FAINT)
        for (b in 0 until BLOCKS) for (i in 0 until 18) {
            dot(canvas, b * (blockPx + blockGap) + (i % 6) * (dotPx + gapPx), (i / 6) * (dotPx + gapPx), dotPx, dotPx, if (lit(b, i)) c else faint)
        }
    }

    override fun uiParts(out: MutableList<UiPart>) {
        val c = SfLook.sf.amberLine
        for (b in 0 until BLOCKS) {
            val bx = b * (blockPx + blockGap)
            out += UiPart("sf-block:$b", bx, 0f, blockPx, 3 * dotPx + 2 * gapPx, background = 0)
            for (i in 0 until 18) out += UiPart("sf-block:$b/i:$i", bx + (i % 6) * (dotPx + gapPx), (i / 6) * (dotPx + gapPx), dotPx, dotPx,
                background = c, opacity = if (lit(b, i)) 1f else FAINT)
        }
    }

    private companion object {
        const val BLOCKS = 8
        const val FAINT = 0.18f
    }
}

/**
 * `.sf-dots` (SetupScreens.tsx `DotBar`): 64 columns of 3 dots that fill left to right, two columns of the boot
 * screen's dither (rows 1 and 3) just ahead of the fill; unlit dots `--amber-line` at 16 %, lit ones `--amber`.
 * Dots 5 px, 2.5 px between rows and 3.6 between columns.
 */
class DotBar(context: Context) : DotGrid(context) {
    var fraction = 0f; set(v) { if (field != v) { field = v; invalidate() } }

    private val dotPx get() = lu(5f * d)
    private val rowGap get() = lu(2.5f * d)
    private val colGap get() = lu(3.6f * d)

    init { uiKey = "sf-dots" }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((COLS * dotPx + (COLS - 1) * colGap).roundToInt(), (3 * dotPx + 2 * rowGap).roundToInt())

    override fun onDraw(canvas: Canvas) {
        val sf = SfLook.sf
        val faint = ro.rdstr.ui.kit.CssColor.fade(sf.amberLine, FAINT)
        val full = (fraction * COLS).roundToInt()
        for (i in 0 until COLS * 3) {
            val c = i / 3
            dot(canvas, c * (dotPx + colGap), (i % 3) * (dotPx + rowGap), dotPx, dotPx, if (lit(i, full)) sf.amber else faint)
        }
    }

    /** Lit: the filled columns, and in the two after them the dots where index + column is even. */
    private fun lit(i: Int, full: Int): Boolean {
        val c = i / 3
        return c < full || (c < full + 2 && (i + c) % 2 == 0 && fraction > 0f && fraction < 1f)
    }

    override fun uiParts(out: MutableList<UiPart>) {
        val sf = SfLook.sf
        val full = (fraction * COLS).roundToInt()
        for (i in 0 until COLS * 3) {
            val on = lit(i, full)
            out += UiPart("i:$i", (i / 3) * (dotPx + colGap), (i % 3) * (dotPx + rowGap), dotPx, dotPx,
                background = if (on) sf.amber else sf.amberLine, opacity = if (on) 1f else FAINT)
        }
    }

    private companion object {
        const val COLS = 64
        const val FAINT = 0.16f
    }
}

/**
 * `.sf-row`: a line of a list, 68 px at least, 26 px in from each side, 18 px round, a `--hairline` edge on `--glass`:
 * its name (22 px, 600) over a detail (17 px, `--ink-muted`), and something at its right end. [state] gives the
 * running and done rows an amber edge and the skipped ones 60 %; [connected] the joined network's amber.
 */
class SfRow(context: Context, pressable: Boolean = false) : BoxLinear(context) {
    val text = LinearLayout(context).apply { orientation = VERTICAL; uiKey = "sf-row-text" }
    val name = context.sfText("sf-row-name", TextStyle(22f, 600))
    val detail = context.sfText("sf-row-detail", TextStyle(17f))
    var state: Setup.TaskState? = null; set(v) { field = v; theme() }
    var connected = false; set(v) { if (field != v) { field = v; theme() } }
    private var check: Drawable? = null
    private val pressable = pressable

    init {
        uiKey = "sf-row"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ipx(68f)
        box.radius = 18f
        box.border = 1f
        text.addView(name)
        text.gapBefore(detail, 3f)
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        // justify-content: space-between
        addView(View(context), LayoutParams(0, 0, 1f))
        isClickable = pressable
        UiTheme.link(this) { theme() }
        theme()
    }

    /** What sits at the right end, after a 24 px gap. */
    fun end(view: View) {
        while (childCount > 2) removeViewAt(2)
        addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = ipx(24f) })
    }

    /** [view] alone in the row, at its start (the Wi-Fi list's "Searching"). */
    fun only(view: View) {
        text.visibility = GONE
        getChildAt(1).visibility = GONE
        while (childCount > 2) removeViewAt(2)
        addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    override fun dispatchSetPressed(pressed: Boolean) { super.dispatchSetPressed(pressed); if (pressable) theme() }

    private fun theme() {
        val b = SfLook.base
        val sf = SfLook.sf
        val running = state == Setup.TaskState.RUNNING || state == Setup.TaskState.DONE
        box.border = if (connected) 2f else 1f
        box.borderColor = when { connected -> sf.amber; running -> SfLook.RUN_BORDER; else -> b.hairline }
        box.fill = when { pressable && isPressed -> b.glassPressed; connected -> SfLook.CONNECTED_FILL; else -> b.glass }
        pad(26f)
        alpha = if (state == Setup.TaskState.SKIPPED) 0.6f else 1f
        name.setTextColor(b.ink)
        detail.setTextColor(if (connected) sf.amberCaption else b.inkMuted)
        detail.style = TextStyle(17f, if (connected) 600 else 400)
        if (connected) {
            val icon = check ?: context.getDrawable(ro.rdstr.ui.tokens.Icons.CHECK)!!.mutate().also { check = it }
            val s = ipx(18f)
            icon.setBounds(0, 0, s, s)
            icon.setTint(sf.amberCaption)
            detail.setCompoundDrawables(icon, null, null, null)
            detail.compoundDrawablePadding = ipx(6f)
        } else detail.setCompoundDrawables(null, null, null, null)
        invalidate()
    }

    override fun uiParts(out: MutableList<UiPart>) {
        if (!connected) return
        // the check icon of the connected network's detail, drawn by the detail's text view
        val s = 18f * d
        out += UiPart("sf-row-text/sf-row-detail/icon.i-check", text.left + detail.left + detail.paddingLeft.toFloat(),
            text.top + detail.top + (detail.height - s) / 2f, s, s, background = SfLook.sf.amberCaption, color = SfLook.sf.amberCaption)
    }
}

/** A [PixelText] row state (`.sf-state`): `--ink-subtle`, amber on running and done rows, `--danger` on failed ones. */
internal fun stateColor(state: Setup.TaskState?): Int = when (state) {
    Setup.TaskState.RUNNING, Setup.TaskState.DONE -> SfLook.sf.amberCaption
    Setup.TaskState.FAILED -> SfLook.base.danger
    else -> SfLook.base.inkSubtle
}

/**
 * `.sf-field`: the text field, 76 px tall, 28 px in, 20 px round, `--glass` with a `--hairline-strong` edge (amber
 * while typing), `--glass-strong` pressed; its value at 34 px, 500, and the boot screen's block cursor while typing,
 * blinking every 530 ms. [right] leaves room for a button over its end (the password's eye).
 */
class SfField(context: Context, right: Float = 28f) : BoxLinear(context) {
    val value = context.sfText("sf-value", TextStyle(34f, 500))
    private val cursor = object : View(context), UiParts {
        override val uiBackground: Int? get() = SfLook.sf.amber
        override fun onDraw(canvas: Canvas) = canvas.drawColor(SfLook.sf.amber)
    }.apply { uiKey = "sf-cursor" }
    var typing = false; set(v) { if (field != v) { field = v; blink(); theme() } }
    private val blinker = object : Runnable {
        override fun run() {
            cursor.visibility = if (cursor.visibility == VISIBLE) INVISIBLE else VISIBLE
            postDelayed(this, BLINK_MS)
        }
    }

    init {
        uiKey = "sf-field"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        box.radius = 20f
        box.border = 1f
        val b = UiScale.borderPx(1f, d)
        setPadding((b + 28 * d).roundToInt(), 0, (b + right * d).roundToInt(), 0)
        addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(cursor, LayoutParams(ipx(16f), ipx(36f)).apply { leftMargin = ipx(2f) })
        cursor.visibility = GONE
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun blink() {
        removeCallbacks(blinker)
        cursor.visibility = if (typing) VISIBLE else GONE
        // prefers-reduced-motion: the cursor stays
        if (typing && UiTheme.speed > 0f) postDelayed(blinker, BLINK_MS)
    }

    override fun onDetachedFromWindow() { removeCallbacks(blinker); super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(ipx(76f), MeasureSpec.EXACTLY))

    override fun dispatchSetPressed(pressed: Boolean) { super.dispatchSetPressed(pressed); theme() }

    private fun theme() {
        val b = SfLook.base
        box.borderColor = if (typing) SfLook.TYPING_BORDER else b.hairlineStrong
        box.fill = if (isPressed) b.glassStrong else b.glass
        value.setTextColor(b.ink)
        invalidate()
    }

    private companion object {
        /** `animation: sf-blink 1060ms steps(1) infinite`, half on, half off */
        const val BLINK_MS = 530L
    }
}
