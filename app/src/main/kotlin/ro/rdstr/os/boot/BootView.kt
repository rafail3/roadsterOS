package ro.rdstr.os.boot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import ro.rdstr.car.BootArt
import kotlin.math.floor
import kotlin.math.min

/**
 * The boot screen and the setup's start screen, drawn dot by dot on the unit's 1024x600 pixels as the browser
 * emulator draws them (emulator/components/Boot.tsx): the dots, the font and the timeline come from core-car BootArt,
 * and every position, size and colour below is the emulator's. A screen of another size gets the picture scaled to fit.
 */
class BootView(context: Context, private val mode: Mode, private val version: String = "", private val onDone: () -> Unit) : View(context) {
    enum class Mode { BOOT, START }

    private val word = BootArt.word()
    private val cube = BootArt.cube()
    private val colors = BootArt.COLORS.map(Color::parseColor).toIntArray()
    private val phosphor = Color.parseColor(BootArt.PHOSPHOR)
    private val caption = Color.parseColor(BootArt.CAPTION)
    private val bar = Color.parseColor(BootArt.BAR)
    private val subtitle = Color.parseColor(BootArt.SUBTITLE)
    private val background = Color.parseColor(BootArt.BACKGROUND)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = bar }
    private val band = Paint()
    private val start = SystemClock.uptimeMillis()
    private var done = false
    /** The tube's scanlines and dark corners, drawn once; the start screen's still parts, drawn once. */
    private val glass by lazy { glass() }
    private val still by lazy { startStill() }

    init {
        isClickable = true
        contentDescription = if (mode == Mode.BOOT) "roadsterOS is starting" else "Touch to start the setup"
        setOnClickListener { finish() }
    }

    private fun finish() {
        if (done) return
        done = true
        onDone()
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start).toDouble()
        canvas.drawColor(background)
        val scale = min(width / W, height / H)
        canvas.save()
        canvas.translate((width - W * scale) / 2, (height - H * scale) / 2)
        canvas.scale(scale, scale)
        if (mode == Mode.BOOT) drawBoot(canvas, t) else drawStart(canvas, t)
        canvas.restore()
        if (mode == Mode.BOOT && t >= BootArt.DONE_MS + HOLD_MS) finish() else postInvalidateOnAnimation()
    }

    private fun rect(c: Canvas, x: Double, y: Double, w: Double, h: Double, color: Int) {
        fill.color = color
        c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), fill)
    }

    private fun tinyText(c: Canvas, text: String, x: Double, y: Double, cell: Double, color: Int): Double {
        text.forEachIndexed { k, ch ->
            val g = BootArt.TINY[ch] ?: BootArt.TINY.getValue(' ')
            for (i in 0 until 35) if (g[i] == '#') rect(c, x + (k * 6 + i % 5) * cell, y + (i / 5) * cell, cell * 0.8, cell * 0.8, color)
        }
        return text.length * 6 * cell
    }

    /** [p] per face (top, left, right): 0 hidden .. 1 whole; dots dissolve in by their fixed order. */
    private fun drawCube(c: Canvas, cx: Double, cy: Double, pitch: Double, p: DoubleArray = WHOLE, flash: Boolean = false) {
        for (d in cube) {
            val k = p[d.face]
            if (d.order > k) continue
            // a dot that just arrived burns bright for a moment, like phosphor
            val fresh = k < 1 && d.order > k - 0.18
            rect(c, cx + d.x * pitch, cy + d.y * pitch, pitch * 0.72, pitch * 0.72, if (fresh || (flash && d.glyph)) phosphor else colors[d.color])
        }
    }

    /** Each letter dissolves in while it drops into place; the depth grows out behind the face. */
    private fun drawWord(c: Canvas, ox: Double, oy: Double, pitch: Double, letter: (Int) -> Double = { 1.0 }, depth: Double = 1.0) {
        for (d in word.dots) {
            val k = letter(d.letter)
            if (k <= 0 || (d.layer != 0 && depth <= 0) || d.order > k * 1.15) continue
            val drop = (1 - BootArt.outBack(k)) * -18
            val shift = if (d.layer != 0) -d.layer * 2 * (1 - BootArt.outCubic(depth)) + d.layer * 2 else 0.0
            val color = if (d.layer == 0 && k < 1 && d.order > k * 1.15 - 0.2) phosphor else colors[d.color]
            rect(c, ox + (d.x + shift) * pitch, oy + (d.y + shift) * pitch + drop, pitch * 0.78, pitch * 0.78, color)
        }
    }

    /** A soft brighter band rolling down the tube, as an old screen's refresh does. */
    private fun roll(c: Canvas, y: Double) {
        band.shader = LinearGradient(0f, (y - 60).toFloat(), 0f, (y + 60).toFloat(),
            intArrayOf(Color.argb(0, 255, 200, 140), Color.argb(alpha(0.045), 255, 200, 140), Color.argb(0, 255, 200, 140)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, (y - 60).toFloat(), W, (y + 60).toFloat(), band)
    }

    private fun drawBoot(c: Canvas, t: Double) {
        val f = BootArt.frame(t, word.letters)
        f.tube?.let { rect(c, it.x, it.y, it.w, it.h, Color.argb(alpha(it.alpha), 255, 241, 201)) }
        f.cube?.let { drawCube(c, 512.0, 168.0, 3.4, it, f.flash) }
        f.letters?.let { l -> drawWord(c, 512 - word.width * 4 / 2.0, 322.0, 4.0, { l[it] }, f.depth) }
        if (f.typed >= 0) {
            val cell = 2.0
            val x = 512 - BootArt.CAPTION_TEXT.length * 6 * cell / 2
            val w = tinyText(c, BootArt.CAPTION_TEXT.take(f.typed), x, 492.0, cell, caption)
            if (f.cursor) rect(c, x + w + 2, 492.0, 5 * cell, 7 * cell, caption)
        }
        roll(c, BootArt.rollY(t))
        c.drawBitmap(glass, 0f, 0f, null)
        // the picture flickers twice right after the tube lights
        if (f.flicker) rect(c, 0.0, 0.0, W.toDouble(), H.toDouble(), Color.argb(alpha(0.35), 7, 8, 10))
    }

    private fun drawStart(c: Canvas, t: Double) {
        c.drawBitmap(still, 0f, 0f, null)
        // the cursor after "touch to start" blinks; the rest of the screen is the bitmap drawn once
        if (floor(t / BootArt.BLINK_MS).toInt() % 2 == 1) tinyText(c, "_", 100 + START_LABEL.length * 12.0, 525.0, 2.0, caption)
        roll(c, BootArt.rollY(t))
        c.drawBitmap(glass, 0f, 0f, null)
    }

    private fun startStill(): Bitmap {
        val b = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
        val s = Canvas(b)
        s.drawColor(background)
        fun bar(y: Double, label: String) {
            s.drawRect(72f, y.toFloat(), 952f, (y + 40).toFloat(), stroke)
            tinyText(s, label, 100.0, y + 13, 2.0, caption)
            for (i in 0 until 40) for (j in 0 until 9) {
                if (BootArt.bayer(i, j) > 1 - i / 40.0) continue
                rect(s, 760 + i * 4.6, y + 4 + j * 3.6, 3.0, 2.6, bar)
            }
        }
        bar(48.0, "${BootArt.CAPTION_TEXT} $version")
        drawCube(s, 262.0, 300.0, 4.2)
        drawWord(s, 420.0, 240.0, 4.6)
        tinyText(s, BootArt.SUBTITLE_TEXT, 424.0, 412.0, 2.0, subtitle)
        bar(512.0, START_LABEL)
        return b
    }

    private fun glass(): Bitmap {
        val b = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
        val g = Canvas(b)
        val line = Color.argb(alpha(0.3), 0, 0, 0)
        for (y in 0 until H.toInt() step 3) rect(g, 0.0, y.toDouble(), W.toDouble(), 1.0, line)
        // transparent out to 240 px from the centre, then darker towards the corners
        val corners = Paint().apply {
            shader = RadialGradient(512f, 300f, 660f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(alpha(0.6), 0, 0, 0)),
                floatArrayOf(0f, 240f / 660f, 1f), Shader.TileMode.CLAMP)
        }
        g.drawRect(0f, 0f, W, H, corners)
        return b
    }

    private fun alpha(a: Double) = (a * 255 + 0.5).toInt().coerceIn(0, 255)

    private companion object {
        const val W = 1024f
        const val H = 600f
        /** After the picture is complete the boot screen stays this long before what comes next. */
        const val HOLD_MS = 800
        val WHOLE = doubleArrayOf(1.0, 1.0, 1.0)
        const val START_LABEL = "TOUCH TO START"
    }
}
