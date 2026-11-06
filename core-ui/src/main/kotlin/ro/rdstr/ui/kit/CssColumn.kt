package ro.rdstr.ui.kit

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** A view's exact CSS height in canvas px, before it is rounded to device pixels. */
interface CssSized {
    val cssHeight: Float
}

/** [CssSized.cssHeight] when the view knows it, else its measured height in canvas px. */
fun View.cssHeight(): Float = (this as? CssSized)?.cssHeight ?: measuredHeight / resources.displayMetrics.density

/**
 * A vertical flex column laid out as the browser does: every child's top and bottom are placed at their exact CSS
 * positions and rounded to the device pixel one by one, so the rounding of each child does not add up down the column
 * (a 68 px row is 54.4 device px at density 128: in a plain LinearLayout ten rows end 4 px higher than in the browser).
 * [gap] is the CSS `gap` between children; padding and side margins work as in a LinearLayout.
 */
open class CssColumn(context: Context, var gap: Float = 0f) : LinearLayout(context), CssSized {
    private val d = resources.displayMetrics.density
    private var content = 0f

    init { orientation = VERTICAL }

    override val cssHeight: Float get() = content + (paddingTop + paddingBottom) / d

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        var y = 0f
        var first = true
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (!first) y += gap
            val lp = c.layoutParams as LayoutParams
            y += c.cssHeight() + top(lp) + bottom(lp)
            first = false
        }
        content = y
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY)
            setMeasuredDimension(measuredWidth, (cssHeight * d).roundToInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = paddingTop / d
        var first = true
        val width = r - l
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (!first) y += gap
            first = false
            val lp = c.layoutParams as LayoutParams
            y += top(lp)
            val h = c.cssHeight()
            val top = (y * d).roundToInt()
            val bottom = ((y + h) * d).roundToInt()
            val left = when {
                lp.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK == android.view.Gravity.CENTER_HORIZONTAL ->
                    (width - c.measuredWidth) / 2
                lp.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK == android.view.Gravity.END ->
                    width - paddingRight - lp.rightMargin - c.measuredWidth
                else -> paddingLeft + lp.leftMargin
            }
            c.layout(left, top, left + c.measuredWidth, bottom)
            y += h + bottom(lp)
        }
    }

    private fun top(lp: LayoutParams) = (lp as? Params)?.cssTop ?: (lp.topMargin / d)
    private fun bottom(lp: LayoutParams) = (lp as? Params)?.cssBottom ?: (lp.bottomMargin / d)

    /** Layout params with the CSS top and bottom margins exact (canvas px, negative allowed). */
    class Params(width: Int, height: Int, val cssTop: Float = 0f, val cssBottom: Float = 0f) : LayoutParams(width, height)
}
