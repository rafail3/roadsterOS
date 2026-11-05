package ro.rdstr.os.maps

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import ro.rdstr.map.Font
import ro.rdstr.map.MapCanvas

/** The map's canvas on the unit: android.graphics, with one reused path and paint per kind of drawing. */
class AndroidMapCanvas : MapCanvas {
    lateinit var canvas: Canvas

    private val path = Path().apply { fillType = Path.FillType.WINDING }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val typefaces = mapOf(
        Font.REGULAR to Typeface.DEFAULT,
        Font.BOLD to Typeface.DEFAULT_BOLD,
        Font.ITALIC to Typeface.create(Typeface.DEFAULT, Typeface.ITALIC),
    )

    private fun build(parts: List<FloatArray>, close: Boolean): Path {
        path.rewind()
        path.fillType = Path.FillType.WINDING
        for (p in parts) {
            if (p.size < 2) continue
            path.moveTo(p[0], p[1])
            var i = 2
            while (i < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
            if (close) path.close()
        }
        return path
    }

    override fun clear(color: Int) = canvas.drawColor(color)

    override fun fill(rings: List<FloatArray>, color: Int) {
        fillPaint.color = color
        canvas.drawPath(build(rings, true), fillPaint)
    }

    override fun stroke(lines: List<FloatArray>, color: Int, width: Float, dash: FloatArray?) {
        linePaint.color = color
        linePaint.strokeWidth = width
        linePaint.pathEffect = dash?.let { DashPathEffect(floatArrayOf(it[0] * width, it[1] * width), 0f) }
        canvas.drawPath(build(lines, false), linePaint)
    }

    override fun rotate(degrees: Float, cx: Float, cy: Float) {
        canvas.save()
        canvas.rotate(degrees, cx, cy)
    }

    override fun clip(left: Float, top: Float, right: Float, bottom: Float) {
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
    }

    override fun restore() = canvas.restore()

    private fun font(size: Float, font: Font) {
        textPaint.textSize = size
        textPaint.typeface = typefaces[font]
    }

    override fun textWidth(text: String, size: Float, font: Font): Float {
        font(size, font)
        return textPaint.measureText(text)
    }

    override fun text(text: String, x: Float, y: Float, size: Float, font: Font, color: Int, halo: Int, haloWidth: Float, degrees: Float) {
        font(size, font)
        val m = textPaint.fontMetrics
        // the baseline that centres the glyphs on y
        val baseline = y - (m.ascent + m.descent) / 2
        canvas.save()
        if (degrees != 0f) canvas.rotate(degrees, x, y)
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = haloWidth * 2
        textPaint.strokeJoin = Paint.Join.ROUND
        textPaint.color = halo
        canvas.drawText(text, x, baseline, textPaint)
        textPaint.style = Paint.Style.FILL
        textPaint.color = color
        canvas.drawText(text, x, baseline, textPaint)
        canvas.restore()
    }
}
