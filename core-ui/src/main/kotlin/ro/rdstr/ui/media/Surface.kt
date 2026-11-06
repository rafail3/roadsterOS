package ro.rdstr.ui.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.LinearLayout
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Glassy
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey
import kotlin.math.max

/** Where a surface's frost comes from: the app's own ground, frosted once (no live blur). */
fun interface FrostSource {
    /** Draws the frosted ground under [view], in [view]'s coordinates (the canvas is clipped to its box). */
    fun drawUnder(canvas: Canvas, view: View)
}

/**
 * A CSS box as a container (a flex row or column): a fill, an optional border and box-shadows, rounded; on Glassy
 * the material its glassy.css rule gives it ([GlassRule]). Pressed it takes [pressedFill] (Solid) or
 * `--glassy-fill-strong` (Glassy, with [glassPress]) over `--fast`. Shared by the media screens.
 */
open class Surface(context: Context, key: String?) : LinearLayout(context) {
    /**
     * BASE: `:where(…)` glass (the material's fill, sheen, frost, rim); TINTED: own colour under sheen, frost and rim;
     * CHOSEN: own colour under sheen and rim; RIM: a Solid `background` shorthand of higher specificity over the glass
     * (own colour, no sheen), the rim, drop and frost kept.
     */
    enum class GlassRule { NONE, BASE, TINTED, CHOSEN, RIM }

    var radius = 0f; set(v) { field = v; box.radius = v; invalidate() }
    var border = 0f; set(v) { field = v; box.border = v; invalidate() }
    var borderColor = 0; set(v) { field = v; invalidate() }
    var fill = 0; set(v) { if (field != v) { field = v; refill(false) } }
    var pressedFill: Int? = null
    var shadows: List<Shadow> = emptyList(); set(v) { field = v; invalidate() }
    var glassRule = GlassRule.NONE; set(v) { field = v; refill(false) }
    var glassPress = false
    var material: () -> Glassy = { Glassy.GLASSY_DARK }
    var frost: FrostSource? = null
    /** The frost of the screen behind (the kit's baked [ro.rdstr.ui.kit.Backdrop]) instead of an app's own ground. */
    var screenFrost = false

    private val box = CssBox()
    private val shown = ColorTransition(0)
    private val under: (Canvas) -> Unit = { c -> frost?.drawUnder(c, this) }

    init {
        uiKey = key
        setWillNotDraw(false)
        clipChildren = false
        @Suppress("LeakingThis")
        UiTheme.link(this) { refill(false) }
    }

    private fun glassOn() = UiTheme.glassy && glassRule != GlassRule.NONE

    private fun target(): Int = when {
        glassOn() && glassRule == GlassRule.BASE -> if (isPressed && glassPress) material().glassyFillStrong else material().glassyFill
        isPressed && pressedFill != null -> pressedFill!!
        else -> fill
    }

    private fun refill(animate: Boolean) {
        val t = target()
        if (animate) shown.to(t, Motion.now(), UiTheme.duration(UiTheme.base.fast), Easing.EASE) else shown.snap(t)
        invalidate()
    }

    override fun dispatchSetPressed(pressed: Boolean) { super.dispatchSetPressed(pressed); refill(true) }
    override fun drawableStateChanged() { super.drawableStateChanged(); refill(true) }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        box.borderColor = borderColor
        box.fill = shown.value(now)
        if (glassOn()) {
            val g = material()
            box.gradient = if (glassRule == GlassRule.RIM) null else g.glassySheen
            box.shadows = g.glassyShadow
            box.under = if (glassRule != GlassRule.CHOSEN && !screenFrost) under else null
            box.frost = if (glassRule != GlassRule.CHOSEN && screenFrost) g.glassyFrost else null
        } else {
            box.gradient = null; box.shadows = shadows; box.under = null; box.frost = null
        }
        box.draw(canvas, this, width.toFloat(), height.toFloat())
        super.onDraw(canvas)
        if (shown.running(now)) postInvalidateOnAnimation()
    }
}

/** A gap of [px] between a LinearLayout's children, as CSS `gap`. */
internal fun LinearLayout.gap(px: Int) {
    dividerDrawable = object : Drawable() {
        override fun getIntrinsicWidth() = px
        override fun getIntrinsicHeight() = px
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSPARENT
    }
    showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
}

/** An image drawn `object-fit: cover` over the view (rounded by the parent's clip); keyed `img`. */
@SuppressLint("ViewConstructor")
class CoverImage(context: Context, private val bitmap: Bitmap) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    init { uiKey = "img" }
    override fun onDraw(canvas: Canvas) {
        val s = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * s; val h = bitmap.height * s
        dst.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }
}
