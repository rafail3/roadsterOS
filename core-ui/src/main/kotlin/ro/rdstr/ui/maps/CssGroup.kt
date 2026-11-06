package ro.rdstr.ui.maps

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * A box of the emulator's layout drawn here: its children placed at the CSS geometry in canvas px (fractions kept),
 * each edge rounded to the device pixel as Chrome paints a box at the screen's 0.8 scale, so sizes do not add up
 * whole-pixel rounding along a row the way a LinearLayout would. Subclasses give their size ([cssW], [cssH], set in
 * [measureCss]) and place their children in [placeCss].
 */
abstract class CssGroup(context: Context) : FrameLayout(context) {
    protected val d = resources.displayMetrics.density
    /** This box's size in canvas px, after a measure. */
    var cssW = 0f; protected set
    var cssH = 0f; protected set

    init {
        clipChildren = false
        clipToPadding = false
    }

    /** Sets [cssW] and [cssH]; [maxW] and [maxH] are the room the parent gives, in canvas px. */
    protected abstract fun measureCss(maxW: Float, maxH: Float)

    protected abstract fun placeCss()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureCss(MeasureSpec.getSize(widthMeasureSpec) / d, MeasureSpec.getSize(heightMeasureSpec) / d)
        setMeasuredDimension(snap(cssW), snap(cssH))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = placeCss()

    protected fun snap(css: Float) = (css * d).roundToInt()

    /** Lays [v] out at ([x], [y]) [w] x [h] canvas px from this box's top left. */
    protected fun place(v: View, x: Float, y: Float, w: Float, h: Float) {
        if (v.visibility == GONE) return
        val l = snap(x); val t = snap(y); val r = snap(x + w); val b = snap(y + h)
        v.measure(MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(b - t, MeasureSpec.EXACTLY))
        v.layout(l, t, r, b)
    }

    /** Measures a nested box for the room this one has, so its [cssW] and [cssH] are known. */
    protected fun measureBox(v: CssGroup, maxW: Float, maxH: Float) =
        v.measure(MeasureSpec.makeMeasureSpec(snap(maxW), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(snap(maxH), MeasureSpec.AT_MOST))
}

/** The advance of a one-line text in canvas px, unrounded, as the browser lays it out. */
internal fun TextView.cssWidth(): Float = paint.measureText(text, 0, text.length) / resources.displayMetrics.density
