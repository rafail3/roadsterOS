package ro.rdstr.ui.setup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.UiPart
import ro.rdstr.ui.UiParts
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.Type
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.kit.scaled
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey
import kotlin.math.roundToInt

/**
 * About's picture of the unit (emulator/components/UnitPicture.tsx): the piano-black frame with its pinholes, touch
 * keys and reflection, the complete boot picture on its screen, and the cube beside it. The frame is the unit's at full
 * size (1154x676) scaled as a whole to [frameWidth] canvas px; the view itself is `.about-hero`, its content at the end.
 * Theme and design independent.
 */
@Mirrors("UnitPicture.tsx")
class UnitPicture(context: Context, private val frameWidth: Float = 300f) : View(context), UiParts {
    private val k = frameWidth / FRAME_W
    private val bezel = CssBox().apply {
        radius = 22f * k
        gradient = ro.rdstr.ui.tokens.Gradient(180f, intArrayOf(0xFF0B0C0D.toInt(), 0xFF040405.toInt(), 0xFF020203.toInt()), floatArrayOf(0f, 0.55f, 1f))
        shadows = listOf(
            Shadow(true, 0f, 1f, 0f, 0f, 0x38FFFFFF), Shadow(true, 0f, -1f, 0f, 0f, 0x0FFFFFFF),
            Shadow(true, 1f, 0f, 0f, 0f, 0x0FFFFFFF), Shadow(true, -1f, 0f, 0f, 0f, 0x0FFFFFFF),
            Shadow(false, 0f, 0f, 0f, 1f, 0xE6000000.toInt()), Shadow(false, 0f, 6f, 14f, 0f, 0x73000000),
        ).map { it.scaled(k) }
    }
    private val screen = CssBox().apply {
        radius = 1f
        fill = 0xFF07080A.toInt()
        shadows = listOf(Shadow(false, 0f, 0f, 0f, 1f, 0xFF000000.toInt()), Shadow(false, 0f, 0f, 0f, 1.5f, 0x0DFFFFFF))
    }
    private val reflection = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = android.graphics.Path()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icons: List<Drawable> = KEYS.map { (icon) -> context.getDrawable(icon)!!.mutate().apply { setTint(KEY_INK) } }
    private var shot: Bitmap? = null

    init {
        uiKey = "about-hero"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        UiTheme.link(this) { Type.apply(ink, ro.rdstr.ui.kit.TextStyle(10f, 600, 0.04f), context); invalidate() }
    }

    private val d get() = resources.displayMetrics.density
    private val unitH get() = FRAME_H * k
    private val cube get() = (unitH * 0.74f).roundToInt().toFloat()
    /** `.about-hero { gap: 14px }`, `justify-content: flex-end`: where the frame starts. */
    private val frameX get() = width / d - cube - 14f - frameWidth

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (unitH * d).roundToInt())

    /** The screen's box in canvas px from this view's top-left. */
    private fun shown() = floatArrayOf(frameX + 100f * k, 36f * k, 1024f * k, 600f * k)

    override fun onDraw(canvas: Canvas) {
        val fx = frameX * d
        canvas.save()
        canvas.translate(fx, 0f)
        val w = frameWidth * d
        val h = unitH * d
        bezel.draw(canvas, this, w, h)
        // ::before: one diagonal reflection and a soft light at the top left, inside the frame's round corners
        if (reflection.shader == null) {
            val line = ro.rdstr.ui.kit.CssGradient.line(112f, w, h)
            reflection.shader = LinearGradient(line[0], line[1], line[2], line[3],
                intArrayOf(0x21FFFFFF, 0x0DFFFFFF, 0, 0, 0x09FFFFFF, 0), floatArrayOf(0f, 0.18f, 0.34f, 0.72f, 0.84f, 0.92f), Shader.TileMode.CLAMP)
            glow.shader = RadialGradient(0.2f * w, 0f, 0.6f * w, intArrayOf(0x12FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setScale(1f, 0.4f * h / (0.6f * w), 0.2f * w, 0f) })
            }
            val r = 22f * k * d
            clip.reset(); clip.addRoundRect(0f, 0f, w, h, r, r, android.graphics.Path.Direction.CW)
        }
        canvas.save(); canvas.clipPath(clip)
        canvas.drawRect(0f, 0f, w, h, reflection)
        canvas.drawRect(0f, 0f, w, h, glow)
        canvas.restore()
        // the keys, the pinholes and their labels, in the frame's own lengths times k
        canvas.save(); canvas.scale(k * d, k * d)
        ink.color = PIN_INK
        ink.textSize = 10f
        canvas.drawText("MIC", 30f + 7f + 7f, 36f + 9f, ink)
        canvas.drawText("RST", 30f + 7f + 7f, 36f + 34f + 9f, ink)
        ink.color = 0xFF000000.toInt()
        canvas.drawCircle(33.5f, 36f + 5f, 3.5f, ink); canvas.drawCircle(33.5f, 36f + 34f + 5f, 3.5f, ink)
        ink.textSize = 20f
        ink.color = KEY_INK
        KEYS.forEachIndexed { i, (_, sign) ->
            val top = 36f + KEY_TOPS[i]
            val shift = if (sign != null) -8f else 0f
            IconView.place(icons[i], 18f + shift, top, 18f + 64f + shift, top + 64f, 30f)
            icons[i].draw(canvas)
            if (sign != null) canvas.drawText(sign, 18f + 64f - 12f - ink.measureText(sign), top + 32f + 7f, ink)
        }
        canvas.restore()
        canvas.restore()
        // the screen: the boot picture as it stands once complete, one pixel per screen pixel
        val s = shown()
        val sw = (s[2] * d).roundToInt()
        val sh = (s[3] * d).roundToInt()
        canvas.save()
        canvas.translate((s[0] * d).roundToInt().toFloat(), (s[1] * d).roundToInt().toFloat())
        screen.draw(canvas, this, sw.toFloat(), sh.toFloat())
        val b = shot?.takeIf { it.width == sw && it.height == sh } ?: Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).also { bmp ->
            Canvas(bmp).apply { scale(sw / BootPainter.W, sh / BootPainter.H); drawColor(BootPainter.shared.background); BootPainter.shared.drawBoot(this, 9999.0, still = true) }
            shot = bmp
        }
        canvas.drawBitmap(b, 0f, 0f, null)
        canvas.restore()
        // the cube beside it, centred
        val c = cube * d
        canvas.save()
        canvas.translate(width - c, (height - c) / 2f)
        val px = c.roundToInt().toDouble()
        BootPainter.shared.drawCube(canvas, px / 2, px / 2, px / 80)
        canvas.restore()
    }

    override fun uiParts(out: MutableList<UiPart>) {
        val x = frameX * d
        val h = unitH * d
        out += UiPart("unit-mini", x, 0f, frameWidth * d, h)
        out += UiPart("unit-mini/unit-mini-bezel", x, 0f, frameWidth * d, h)
        val s = shown()
        out += UiPart("unit-mini/unit-mini-screen", s[0] * d, s[1] * d, s[2] * d, s[3] * d, background = 0xFF07080A.toInt())
        val c = cube * d
        out += UiPart("cube-mark", width - c, (height - c) / 2f, c, c)
    }

    override fun onDetachedFromWindow() { shot?.recycle(); shot = null; super.onDetachedFromWindow() }

    private companion object {
        const val FRAME_W = 1154f
        const val FRAME_H = 676f
        const val KEY_INK = 0xDBEBEEF2.toInt()
        const val PIN_INK = 0x9EEBEEF2.toInt()
        val KEYS = listOf<Pair<Int, String?>>(Icons.POWER to null, Icons.HOUSE to null, Icons.ARROW_U_UP_LEFT to null,
            Icons.SPEAKER_SIMPLE_NONE to "+", Icons.SPEAKER_SIMPLE_NONE to "−")
        val KEY_TOPS = floatArrayOf(84f, 184f, 284f, 384f, 484f)
    }
}
