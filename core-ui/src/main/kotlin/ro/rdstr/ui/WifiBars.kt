package ro.rdstr.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Wi-Fi signal as three arcs and a dot; arcs above [level] are drawn faint, level 0 draws everything faint. The
 * drawing is the component's 24x24 SVG: the same arcs, stroke 2.4 with round caps, dot of radius 1.6, faint = 0.3.
 */
@Mirrors("WifiBars.tsx")
class WifiBars(context: Context) : View(context) {
    /** 0..3 */
    var level: Int = 0
        set(value) {
            require(value in 0..3) { "level $value" }
            field = value
            invalidate()
        }

    /** The SVG's `currentColor`: the colour the parent gives its content. */
    var color: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        uiKey = "wifi-bars"
    }

    // `.wifi-bars { width: 24px; height: 24px }` (music.css)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (SIZE * resources.displayMetrics.density).roundToInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        // viewBox 0 0 24 24, fitted and centred (preserveAspectRatio xMidYMid meet). At its own size the picture is
        // scaled by the unrounded 24 dp (19.2 px at density 128), as the browser scales by its unrounded box; the
        // view itself is a whole number of pixels
        val fit = min(width, height).toFloat()
        val natural = SIZE * resources.displayMetrics.density
        val s = (if (abs(fit - natural) < 1f) natural else fit) / VIEWBOX
        canvas.save()
        canvas.translate((width - fit) / 2f, (height - fit) / 2f)
        canvas.scale(s, s)
        stroke.strokeWidth = STROKE
        for ((n, arc) in ARCS.withIndex()) {
            stroke.color = shade(level >= ARCS.size - n)
            canvas.drawArc(arc.oval, arc.start, arc.sweep, false, stroke)
        }
        fill.color = shade(level >= 1)
        canvas.drawCircle(12f, 19f, 1.6f, fill)
        canvas.restore()
    }

    private fun shade(on: Boolean): Int =
        if (on) color else Color.argb((Color.alpha(color) * FAINT).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** An SVG `a r r 0 0 1` from (x1, y) to (x2, y): the short clockwise arc, as a centre and angles in degrees. */
    private class Arc(x1: Float, x2: Float, y: Float, r: Float) {
        private val cx = (x1 + x2) / 2f
        private val cy = y + sqrt(r * r - (x2 - x1) * (x2 - x1) / 4f)
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val start = Math.toDegrees(atan2((y - cy).toDouble(), (x1 - cx).toDouble())).toFloat()
        val sweep = Math.toDegrees(atan2((y - cy).toDouble(), (x2 - cx).toDouble())).toFloat() - start
    }

    private companion object {
        const val SIZE = 24f
        const val VIEWBOX = 24f
        const val STROKE = 2.4f
        const val FAINT = 0.3f
        /** Outer to inner: M2.6 9.2a13.4…, M5.8 12.5a8.8…, M9 15.7a4.3… */
        val ARCS = listOf(Arc(2.6f, 21.4f, 9.2f, 13.4f), Arc(5.8f, 18.2f, 12.5f, 8.8f), Arc(9f, 15f, 15.7f, 4.3f))
    }
}
