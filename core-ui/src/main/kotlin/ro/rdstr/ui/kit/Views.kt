package ro.rdstr.ui.kit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import ro.rdstr.ui.tokens.Shadow

/** What every kit view shares: the theme link, canvas px to device px, and the frame clock of its motion. */
abstract class KitView(context: Context) : View(context) {
    /** Device px per canvas px. */
    protected val d: Float get() = resources.displayMetrics.density
    private var last = -1L

    init {
        @Suppress("LeakingThis")
        UiTheme.link(this) { onTheme(); invalidate() }
    }

    /** Reads the colours and the design again; called at attach and after every theme change (subclasses also call it at the end of their init). */
    protected abstract fun onTheme()

    /** Seconds since the last frame at the animation speed (0 when off), for the springs; capped at 0.1 s as glisse. */
    protected fun frameSeconds(now: Long): Double {
        val dt = if (last < 0) 0.0 else ((now - last) / 1000.0).coerceIn(0.0, 0.1)
        last = now
        return if (UiTheme.speed <= 0f) 1.0 else dt / UiTheme.speed
    }

    /** Restarts the frame clock: the next frame steps from now. */
    protected fun restartClock() { last = Motion.now() }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); last = -1L }
}

/** A text style of the CSS: size in canvas px, variable weight, letter-spacing in em, line-height (0 = normal). */
class TextStyle(val size: Float, val weight: Int = 400, val tracking: Float = 0f, val lineHeight: Float = 0f)

object Type {
    /** `.screen { font-feature-settings: "tnum" 1, "cv11" 1 }`, which every text on the screen inherits. */
    const val FEATURES = "'tnum' 1, 'cv11' 1"

    /**
     * [style] on [paint], in the theme's family. The weight is the font's own `wght` axis; the optical size stays
     * at the font's default, as `font-optical-sizing: none` keeps it in the emulator. Call it when the style or the
     * theme changes, not while drawing: each call makes a typeface for the axis.
     */
    fun apply(paint: Paint, style: TextStyle, context: Context) {
        paint.typeface = UiTheme.typeface(context)
        paint.textSize = style.size * context.resources.displayMetrics.density
        paint.letterSpacing = style.tracking
        paint.fontFeatureSettings = FEATURES
        paint.fontVariationSettings = "'wght' ${style.weight}"
    }
}

/**
 * A text in a [TextStyle]. With a line-height, every line is that tall with the extra split above and below its
 * glyphs, as CSS lays a line box out (Android adds line spacing below each line, so the text is drawn half of it
 * lower). Colour is the caller's (`setTextColor`).
 */
@SuppressLint("ViewConstructor")
class KitText(context: Context, style: TextStyle) : TextView(context) {
    var style: TextStyle = style; set(v) { field = v; applyStyle() }
    private var halfLeading = 0f

    init {
        includeFontPadding = false
        UiTheme.link(this) { applyStyle() }
        applyStyle()
    }

    private fun applyStyle() {
        typeface = UiTheme.typeface(context)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, style.size * resources.displayMetrics.density)
        letterSpacing = style.tracking
        fontFeatureSettings = Type.FEATURES
        fontVariationSettings = "'wght' ${style.weight}"
        if (style.lineHeight > 0f) {
            val m = paint.fontMetrics
            val leading = style.lineHeight * textSize - (m.descent - m.ascent)
            setLineSpacing(leading, 1f)
            halfLeading = leading / 2f
        } else { setLineSpacing(0f, 1f); halfLeading = 0f }
    }

    /**
     * Each line exactly its line-height tall: API 27 adds the spacing below the last line too, newer renderers (the
     * JVM's layoutlib) leave it out; both measure the same here.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (style.lineHeight <= 0f || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) return
        val lines = maxOf(1, layout?.lineCount ?: 1)
        setMeasuredDimension(measuredWidth, (lines * style.lineHeight * textSize + paddingTop + paddingBottom + 0.5f).toInt())
    }

    /** The baseline where CSS puts it: half the leading under the line box's top. */
    override fun getBaseline(): Int = super.getBaseline() + Math.round(halfLeading)

    override fun onDraw(canvas: Canvas) {
        if (halfLeading == 0f) return super.onDraw(canvas)
        canvas.save(); canvas.translate(0f, halfLeading); super.onDraw(canvas); canvas.restore()
    }
}

/**
 * An icon of the emulator's (`.icon i-…`, a generated `Icons` drawable) in the text colour, as the CSS masks it
 * with `currentColor`: the glyph's coverage in [color], centred and contained in a square of [size] canvas px
 * (28 by default, `.icon`).
 */
@SuppressLint("ViewConstructor")
class IconView(context: Context, icon: Int, size: Float = 28f) : View(context) {
    private var drawable: Drawable = context.getDrawable(icon)!!.mutate()
    var size = size; set(v) { field = v; requestLayout() }
    var color: Int = 0; set(v) { if (field != v) { field = v; drawable.setTint(v); invalidate() } }

    fun setIcon(icon: Int) { drawable = context.getDrawable(icon)!!.mutate().apply { setTint(color) }; requestLayout(); invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (size * resources.displayMetrics.density + 0.5f).toInt()
        setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = place(drawable, 0f, 0f, w.toFloat(), h.toFloat(), size * resources.displayMetrics.density)

    override fun onDraw(canvas: Canvas) = drawable.draw(canvas)

    companion object {
        /** [icon] contained in a [s] px square centred in the box ([l], [t], [r], [b]). */
        fun place(icon: Drawable, l: Float, t: Float, r: Float, b: Float, s: Float) {
            val x = (l + r - s) / 2f
            val y = (t + b - s) / 2f
            icon.setBounds((x + 0.5f).toInt(), (y + 0.5f).toInt(), (x + s + 0.5f).toInt(), (y + s + 0.5f).toInt())
        }
    }
}

/** This shadow with every length times [k] (an element under `zoom` or drawn at a scale of its own). */
fun Shadow.scaled(k: Float) = Shadow(inset, x * k, y * k, blur * k, spread * k, color)
