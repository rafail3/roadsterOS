package ro.rdstr.ui.volume

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.tokens.Base
import ro.rdstr.ui.tokens.Bezier
import ro.rdstr.ui.tokens.Fonts
import ro.rdstr.ui.tokens.Glassy
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** What the volume bar asks of the car; the display app's runtime implements it. */
interface VolumeActions {
    /** A level picked on the slider (`store.setVolume`): the shared rules turn it into steps, 0 mutes. */
    fun setVolume(level: Int)

    /** The mute key: sends `CarCommand.Audio.Mute(on)`. */
    fun mute(on: Boolean)
}

/**
 * The volume bar (`div.volume-hud`, screen.css `.volume-hud`): mute key, slider and number in a 360x64 capsule, 72 dp
 * from the top, centred. Laid out over the whole screen (1280x750 dp) above the panels; touches outside the capsule, or
 * while it is hidden, pass through. It comes up for 1500 ms after every change of [setState], stays while a finger
 * holds the slider, and shakes when muting.
 */
@Mirrors("VolumeHud.tsx")
class VolumeHudView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var actions: VolumeActions? = null

    private val model = VolumeHudModel()
    private val d = resources.displayMetrics.density

    // capsule geometry in its own coordinates, px (screen.css .volume-hud and children, box-sizing border-box)
    private val w = 360 * d
    private val h = 64 * d
    private val top = 72 * d
    private val border = 1 * d
    private val contentLeft = border + 8 * d
    private val contentRight = w - border - 24 * d
    private val gap = 8 * d
    private val muteSize = 48 * d
    private val glyphSize = 28 * d
    private val numMin = 40 * d
    private val sliderPad = 8 * d
    private val trackH = 6 * d
    private val cy = h / 2
    private var left = 0f

    private var base = Base.DARK
    private var glassy: Glassy? = null

    private val panel = RectF(0f, 0f, w, h)
    private val padding = RectF(border, border, w - border, h - border)
    private val borderRect = RectF(border / 2, border / 2, w - border / 2, h - border / 2)
    private val layerRect = RectF()
    private val track = RectF()
    private val fill = RectF()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = border }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = 24 * d
        fontFeatureSettings = "'tnum' 1, 'cv11' 1"
    }
    private var baseline = 0f
    private var insets: Array<Path> = emptyArray()
    private var insetColors = IntArray(0)
    private var drop: Bitmap? = null
    private var dropMargin = 0f

    private val speaker: Drawable = context.getDrawable(Icons.SPEAKER_HIGH_FILL)!!.mutate()
    private val speakerSlash: Drawable = context.getDrawable(Icons.SPEAKER_SLASH_FILL)!!.mutate()

    // transitions, as the CSS declares them
    private val easeOut = interpolator(base.easeOut)
    private val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f) // CSS `ease`, the default of `.volume-mute`
    private val muteEase = interpolator(MuteFill.EASE)
    private val shakeEase = PathInterpolator(0.36f, 0.07f, 0.19f, 0.97f)
    private val showT = Tween()
    private val fillT = Tween()
    private val mutedT = Tween()
    private val pressT = Tween()

    private enum class Target { NONE, SLIDER, MUTE }
    private var target = Target.NONE
    private var picked = -1
    private val hide = Runnable { val now = now(); model.tick(now); afterChange(now) }

    init {
        setTheme(light = false, glassy = false)
        setFont("Inter")
    }

    /** The car's level, mute and the status bar's open count (`UiState.volumeOpened`); any change brings the bar up. */
    fun setState(level: Int, muted: Boolean, opened: Int) {
        val now = now()
        val first = !started
        started = true
        if (!model.update(level, muted, opened, now, motion()) && !first) return
        val to = fillFraction(model.volume, model.muted)
        if (first) {
            fillT.jump(to); mutedT.jump(if (muted) 1f else 0f)
        } else {
            if (model.muteFill.active(now)) fillT.go(to, now, ms(MuteFill.MS), muteEase) else fillT.go(to, now, ms(base.fast), easeOut)
            mutedT.go(if (muted) 1f else 0f, now, ms(base.base), easeOut)
        }
        tintGlyphs()
        afterChange(now)
    }
    private var started = false

    fun setLevel(level: Int) = setState(level, model.muted, model.opened)
    fun setMuted(muted: Boolean) = setState(model.volume, muted, model.opened)
    fun setOpened(opened: Int) = setState(model.volume, model.muted, opened)

    /** A screen shows its own volume control (music app, drawer): changes no longer bring the bar up. */
    fun setQuiet(quiet: Boolean) { model.quiet = quiet }

    /** Day/night theme and Solid/Glassy design of the screen. */
    fun setTheme(light: Boolean, glassy: Boolean) {
        base = Base.of(light, glassy)
        this.glassy = if (glassy) Glassy.of(light) else null
        val g = this.glassy
        borderPaint.color = base.hairlineStrong
        if (g == null) {
            sheenPaint.shader = null
            insets = emptyArray()
            drop = null
        } else {
            sheenPaint.shader = sheen(g)
            val inner = g.glassyShadow.filter { it.inset }
            // the first shadow is on top: drawn last
            insets = Array(inner.size) { i -> insetPath(inner[inner.size - 1 - i]) }
            insetColors = IntArray(inner.size) { i -> inner[inner.size - 1 - i].color }
            bakeDrop(g)
        }
        tintGlyphs()
        invalidate()
    }

    /** CSS font family of the screen ("Inter", "Geist"). */
    fun setFont(family: String) {
        textPaint.typeface = resources.getFont(Fonts.byFamily[family] ?: Fonts.INTER)
        textPaint.fontVariationSettings = "'wght' 600"
        val fm = textPaint.fontMetrics
        // the line box is centred in the capsule; the glyphs sit on its baseline
        baseline = cy - (fm.ascent + fm.descent) / 2
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
        left = (width - w) / 2
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hide)
    }

    override fun onDraw(canvas: Canvas) {
        val now = now()
        val shown = showT.value(now)
        if (shown <= 0f) return
        val shake = if (model.shaking(now)) keyframes((now - model.shakeFrom).toFloat() / SHAKE_MS, SHAKE_X) * d else 0f
        canvas.save()
        canvas.translate(left + shake, top - 8 * d * (1 - shown))
        val alpha = (shown * 255).toInt()
        if (alpha < 255) {
            layerRect.set(-dropMargin, -dropMargin, w + dropMargin, h + dropMargin)
            canvas.saveLayerAlpha(layerRect, alpha)
        }
        drawCapsule(canvas)
        drawContent(canvas, now)
        canvas.restore()
        if (alpha < 255) canvas.restore()
        if (showT.running(now) || fillT.running(now) || mutedT.running(now) || pressT.running(now) || model.animating(now)) postInvalidateOnAnimation()
    }

    private fun drawCapsule(canvas: Canvas) {
        val g = glassy
        val r = h / 2
        drop?.let { canvas.drawBitmap(it, -dropMargin, -dropMargin, null) }
        paint.color = g?.glassyFill ?: base.sheetRaised
        canvas.drawRoundRect(panel, r, r, paint)
        if (g != null) {
            canvas.drawRoundRect(padding, r - border, r - border, sheenPaint)
            for (i in insets.indices) { paint.color = insetColors[i]; canvas.drawPath(insets[i], paint) }
        }
        canvas.drawRoundRect(borderRect, r - border / 2, r - border / 2, borderPaint)
    }

    private fun drawContent(canvas: Canvas, now: Long) {
        val muted = model.muted
        // mute key: pressed background, then the glyph (rocks while shaking)
        val mx = contentLeft + muteSize / 2
        val pressed = pressT.value(now)
        if (pressed > 0f) {
            paint.color = scaleAlpha(base.glassPressed, pressed)
            canvas.drawCircle(mx, cy, muteSize / 2, paint)
        }
        val glyph = if (muted) speakerSlash else speaker
        canvas.save()
        if (model.shaking(now)) canvas.rotate(keyframes((now - model.shakeFrom).toFloat() / SHAKE_MS, ROCK_DEG), mx, cy)
        glyph.draw(canvas)
        canvas.restore()

        // number, right-aligned at the content edge, at least 40 wide
        val label = if (muted) MUTED else NUMBERS[model.number(now).coerceIn(0, MAX_VOLUME)]
        val numLeft = contentRight - max(numMin, textPaint.measureText(label))
        textPaint.color = mix(base.ink, base.mute, mutedT.value(now))
        canvas.drawText(label, contentRight, baseline, textPaint)

        // track and fill between the key and the number
        track.set(sliderLeft() + sliderPad, cy - trackH / 2, numLeft - gap - sliderPad, cy + trackH / 2)
        val tr = trackH / 2
        paint.color = base.hairlineStrong
        canvas.drawRoundRect(track, tr, tr, paint)
        val f = fillT.value(now)
        if (f > 0f) {
            fill.set(track.left, track.top, track.left + track.width() * f, track.bottom)
            fillPaint.color = base.ink
            canvas.drawRoundRect(fill, tr, tr, fillPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = now()
        val x = e.x - left
        val y = e.y - top
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!model.shown || x < 0 || x > w || y < 0 || y > h) return false
                target = when {
                    x >= sliderLeft() && x <= sliderRight() -> Target.SLIDER
                    hypot(x - contentLeft - muteSize / 2, y - cy) <= muteSize / 2 -> Target.MUTE
                    else -> Target.NONE
                }
                if (target == Target.SLIDER) { model.hold(now); picked = -1; pick(x) }
                if (target == Target.MUTE) pressT.go(1f, now, ms(base.fast), ease)
                afterChange(now)
            }
            MotionEvent.ACTION_MOVE -> if (target == Target.SLIDER) pick(x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (target == Target.SLIDER) model.release(now)
                if (target == Target.MUTE) {
                    pressT.go(0f, now, ms(base.fast), ease)
                    val inside = hypot(x - contentLeft - muteSize / 2, y - cy) <= muteSize / 2
                    if (e.actionMasked == MotionEvent.ACTION_UP && inside) { performClick(); actions?.mute(!model.muted) }
                }
                target = Target.NONE
                afterChange(now)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** The slider is the whole strip between the key and the number, its padding included. */
    private fun sliderLeft() = contentLeft + muteSize + gap

    private fun sliderRight(): Float {
        val label = if (model.muted) MUTED else NUMBERS[model.number(now()).coerceIn(0, MAX_VOLUME)]
        return contentRight - max(numMin, textPaint.measureText(label)) - gap
    }

    private fun pick(x: Float) {
        val left = sliderLeft()
        val level = levelAt(x, left, sliderRight() - left)
        // one call per new level: the runtime turns each into steps from the level it last saw
        if (level != picked) { picked = level; actions?.setVolume(level) }
    }

    /** Starts the show/hide transition and the hide timer after anything that may have poked the model. */
    private fun afterChange(now: Long) {
        showT.go(if (model.shown) 1f else 0f, now, ms(base.base), easeOut)
        removeCallbacks(hide)
        if (model.hideAt != NEVER) postDelayed(hide, max(0L, model.hideAt - now))
        invalidate()
    }

    private fun tintGlyphs() {
        val l = contentLeft + (muteSize - glyphSize) / 2
        val t = cy - glyphSize / 2
        for (g in arrayOf(speaker, speakerSlash)) {
            g.setBounds(l.toInt(), t.toInt(), (l + glyphSize).toInt(), (t + glyphSize).toInt())
            g.setTint(if (model.muted) base.mute else base.ink)
        }
    }

    /** `--glassy-sheen` over the padding box: the CSS gradient line through the centre at its angle. */
    private fun sheen(g: Glassy): Shader {
        val s = g.glassySheen
        val a = Math.toRadians(s.angle.toDouble())
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        val half = (abs(padding.width() * dx) + abs(padding.height() * dy)) / 2
        val cx = padding.centerX()
        val cyy = padding.centerY()
        return LinearGradient(cx - dx * half, cyy - dy * half, cx + dx * half, cyy + dy * half, s.colors, s.at, Shader.TileMode.CLAMP)
    }

    /** An inset `box-shadow` with no blur: the padding box less itself moved by the offset and shrunk by the spread. */
    private fun insetPath(s: Shadow): Path {
        val r = h / 2 - border
        val outer = Path().apply { addRoundRect(padding, r, r, Path.Direction.CW) }
        val sp = s.spread * d
        val hole = RectF(padding.left + s.x * d + sp, padding.top + s.y * d + sp, padding.right + s.x * d - sp, padding.bottom + s.y * d - sp)
        val rh = max(0f, r - sp)
        outer.op(Path().apply { addRoundRect(hole, rh, rh, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        return outer
    }

    /** The outer `box-shadow` layers baked once into a bitmap, cut out under the capsule as CSS draws them. */
    private fun bakeDrop(g: Glassy) {
        drop = null
        val outer = g.glassyShadow.filter { !it.inset }
        if (outer.isEmpty()) { dropMargin = 0f; return }
        dropMargin = outer.maxOf { it.blur + it.spread + max(abs(it.x), abs(it.y)) } * d
        val m = dropMargin
        val bmp = Bitmap.createBitmap((w + 2 * m).toInt(), (h + 2 * m).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()
        for (s in outer.asReversed()) {
            val sp = s.spread * d
            rect.set(m + s.x * d - sp, m + s.y * d - sp, m + w + s.x * d + sp, m + h + s.y * d + sp)
            // CSS blur radius b is a Gaussian of sigma b/2; Skia's blur radius r has sigma 0.57735 r + 0.5
            val sigma = s.blur * d / 2
            p.maskFilter = if (sigma > 0.5f) BlurMaskFilter((sigma - 0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL) else null
            p.color = s.color
            c.drawRoundRect(rect, h / 2 + sp, h / 2 + sp, p)
        }
        p.maskFilter = null
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        rect.set(m, m, m + w, m + h)
        c.drawRoundRect(rect, h / 2, h / 2, p)
        drop = bmp
    }

    /** A `@keyframes` track at [t] 0..1, the animation's timing function applied to each segment, as CSS does. */
    private fun keyframes(t: Float, values: FloatArray): Float {
        var i = 1
        while (i < KEY_AT.size - 1 && t > KEY_AT[i]) i++
        val p = ((t - KEY_AT[i - 1]) / (KEY_AT[i] - KEY_AT[i - 1])).coerceIn(0f, 1f)
        return values[i - 1] + (values[i] - values[i - 1]) * shakeEase.getInterpolation(p)
    }

    private fun now() = SystemClock.uptimeMillis()

    /** Off in the developer options (animator duration scale 0) is the unit's reduced motion. */
    private fun motion() = ValueAnimator.areAnimatorsEnabled()

    /** A CSS duration; reduced motion makes transitions instant (screen.css `transition-duration: 0.01ms`). */
    private fun ms(duration: Long) = if (motion()) duration else 0L

    /** One CSS transition of a number: retargets from wherever it is. */
    private class Tween {
        private var from = 0f
        private var to = 0f
        private var start = 0L
        private var duration = 0L
        private var interp: TimeInterpolator? = null

        fun value(now: Long): Float {
            val i = interp
            if (i == null || duration <= 0 || now >= start + duration) return to
            return from + (to - from) * i.getInterpolation((now - start).toFloat() / duration)
        }

        fun running(now: Long) = duration > 0 && now < start + duration

        fun go(target: Float, now: Long, ms: Long, ease: TimeInterpolator) {
            if (target == to) return
            from = value(now); to = target; start = now; duration = ms; interp = ease
        }

        fun jump(v: Float) { from = v; to = v; duration = 0 }
    }

    private companion object {
        const val MUTED = "Muted"
        val NUMBERS = Array(MAX_VOLUME + 1) { it.toString() }
        // screen.css @keyframes volume-shake (translate x, px) and volume-rock (rotate, deg)
        val KEY_AT = floatArrayOf(0f, 0.14f, 0.30f, 0.46f, 0.62f, 0.78f, 1f)
        val SHAKE_X = floatArrayOf(0f, -9f, 8f, -6f, 4f, -2f, 0f)
        val ROCK_DEG = floatArrayOf(0f, -14f, 12f, -8f, 5f, -2f, 0f)

        fun interpolator(b: Bezier) = PathInterpolator(b.x1, b.y1, b.x2, b.y2)

        fun scaleAlpha(color: Int, k: Float) = ((color ushr 24) * k).toInt() shl 24 or (color and 0xFFFFFF)

        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
