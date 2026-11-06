package ro.rdstr.ui.home.clock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import ro.rdstr.ui.tokens.Base

/**
 * The analog face (Clock.tsx `Dial`, clock.css "analog") drawn in its 480 x 480 viewBox units; the caller scales and
 * places it. Colours come from [setColors], the numerals' font from [setFont]; drawing allocates nothing.
 */
internal class Dial {
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = stroke(14f, Paint.Cap.BUTT)
    private val rim = stroke(2f, Paint.Cap.BUTT)
    private val minor = stroke(2f, Paint.Cap.ROUND)
    private val major = stroke(3f, Paint.Cap.ROUND)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numeral = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = 40f
        fontFeatureSettings = FEATURES
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val minorPath = Path()
    private val majorPath = Path()
    private val numeralX = FloatArray(4)
    private var numeralShift = 0f
    private var cap = 0
    private var capDot = 0

    init {
        for (i in 0 until 60) {
            if (i % 15 == 0) continue
            val isMajor = i % 5 == 0
            val path = if (isMajor) majorPath else minorPath
            val from = if (isMajor) 212 else 216
            path.moveTo(Ticks.x(i, from), Ticks.y(i, from))
            path.lineTo(Ticks.x(i, 226), Ticks.y(i, 226))
        }
    }

    /** The theme's dial tokens; hand colours are passed to [draw], as they fade with the theme. */
    fun setColors(t: Base) {
        // radialGradient cx 50% cy 42% r 60% of the face's box (12..468)
        face.shader = RadialGradient(240f, 12f + 0.42f * 456f, 0.6f * 456f, t.dialHi, t.dialLo, Shader.TileMode.CLAMP)
        inner.color = t.dialShadow
        rim.color = t.dialRim
        minor.color = t.tickMinor
        major.color = t.tick
        dot.color = t.tick
        numeral.color = t.inkMuted
        cap = t.hand
        capDot = t.second
    }

    /** The screen font at weight 500; [xHeight] per em places `dominant-baseline: middle`. */
    fun setFont(typeface: Typeface, xHeight: Float) {
        numeral.typeface = typeface
        numeral.fontVariationSettings = "'wght' 500"
        ro.rdstr.ui.kit.Type.linear(numeral)
        numeral.letterSpacing = -0.02f
        numeralShift = xHeight * numeral.textSize / 2
        for (i in 0 until 4) numeralX[i] = centred(numeral, NUMERALS[i], NUMERAL_X[i])
    }

    fun draw(c: Canvas, hourDeg: Float, minuteDeg: Float, secondDeg: Float, hand: Int, second: Int) {
        c.drawCircle(240f, 240f, 228f, face)
        c.drawCircle(240f, 240f, 221f, inner)
        c.drawCircle(240f, 240f, 228f, rim)
        c.drawPath(minorPath, minor)
        c.drawPath(majorPath, major)
        for (i in 0 until 60 step 15) c.drawCircle(Ticks.x(i, 222), Ticks.y(i, 222), 3.5f, dot)
        for (i in 0 until 4) c.drawText(NUMERALS[i], numeralX[i], NUMERAL_Y[i] + numeralShift, numeral)
        handPaint.color = hand
        drawHand(c, hourDeg, 235f, 120f, 10f, 150f, 5f)
        drawHand(c, minuteDeg, 236f, 60f, 8f, 212f, 4f)
        handPaint.color = second
        drawHand(c, secondDeg, 238.75f, 44f, 2.5f, 246f, 1.25f)
        handPaint.color = cap
        c.drawCircle(240f, 240f, 9f, handPaint)
        handPaint.color = capDot
        c.drawCircle(240f, 240f, 3.5f, handPaint)
    }

    private fun drawHand(c: Canvas, deg: Float, x: Float, y: Float, w: Float, h: Float, r: Float) {
        c.save()
        c.rotate(deg, 240f, 240f)
        c.drawRoundRect(x, y, x + w, y + h, r, r, handPaint)
        c.restore()
    }

    private fun stroke(width: Float, cap: Paint.Cap) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = cap
    }

    private companion object {
        val NUMERALS = arrayOf("12", "3", "6", "9")
        val NUMERAL_X = floatArrayOf(240f, 406f, 240f, 74f)
        val NUMERAL_Y = floatArrayOf(76f, 252f, 428f, 252f)
    }
}

/** `font-feature-settings` of `.screen`, inherited by every text. */
internal const val FEATURES = "'tnum' 1, 'cv11' 1"

/**
 * The left edge to draw [text] from so it sits centred on [center] as Blink centres it: Blink adds the letter spacing
 * after every character, Android half before and half after, so Android's run is moved back by half a spacing.
 */
internal fun centred(paint: Paint, text: String, center: Float): Float {
    paint.textAlign = Paint.Align.LEFT
    return center - paint.measureText(text) / 2 - paint.letterSpacing * paint.textSize / 2
}
