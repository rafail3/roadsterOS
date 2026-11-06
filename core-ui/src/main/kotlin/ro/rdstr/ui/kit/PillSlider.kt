package ro.rdstr.ui.kit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import ro.rdstr.ui.Mirrors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which custom properties a control reads: the screen's (`:root`, the drawer) or the settings app's (`.settings-app`). */
enum class Scope {
    SCREEN, SETTINGS;

    val ink get() = if (this == SETTINGS) UiTheme.settings.ink else UiTheme.base.ink
    val inkMuted get() = if (this == SETTINGS) UiTheme.settings.inkMuted else UiTheme.base.inkMuted
    val glass get() = if (this == SETTINGS) UiTheme.settings.glass else UiTheme.base.glass
    val hairline get() = if (this == SETTINGS) UiTheme.settings.hairline else UiTheme.base.hairline
    val hairlineStrong get() = if (this == SETTINGS) UiTheme.settings.hairlineStrong else UiTheme.base.hairlineStrong
    val sheet get() = if (this == SETTINGS) UiTheme.settings.sheet else UiTheme.base.sheet
}

/**
 * Drawer.tsx `PillSlider`: a thick track filled from the left, dragged anywhere along it, the icon inside the filled
 * end and the readout after it (`.pill-slider`, screen.css); on Glassy the icon, glisse's `Slider` and the readout
 * in one row (`.pill-slider.is-glass`, glassy.css), chosen as glassy.tsx `useGlisse` chooses.
 *
 * Controlled: a press or a drag sends [onValue] whole values from [min] to [max]; the slider shows [value].
 * [onHeld] follows the press (the drawer clears to the screen while brightness is held). [onIconClick] makes the
 * icon a button (`button.pill-icon`, the volume's mute), [onReadoutClick] the readout (`button.pill-readout`).
 */
@Mirrors("glassy.tsx")
@SuppressLint("ViewConstructor")
class KitPillSlider(context: Context, private val scope: Scope = Scope.SETTINGS) : KitView(context) {
    var min = 0
    var max = 100
    var value = 0; set(v) { if (field != v) { field = v; changed() } }
    var muted = false; set(v) { if (field != v) { field = v; colours(animate = true) } }
    /** The readout's text; null leaves no readout. */
    var readout: String? = null; set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }
    /** A fill colour of the caller's in place of `color-mix(in srgb, var(--ink) 76%, transparent)` (`fillStyle`). */
    var fillColor: Int? = null; set(v) { field = v; invalidate() }
    var onValue: ((Int) -> Unit)? = null
    var onHeld: ((Boolean) -> Unit)? = null
    var onIconClick: (() -> Unit)? = null
    var onReadoutClick: (() -> Unit)? = null

    private var icon: Drawable? = null
    fun setIcon(res: Int) { icon = context.getDrawable(res)!!.mutate(); colours(animate = false); invalidate() }

    private var glassy = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val rect = RectF()
    private var baseline = 0f

    // Solid
    private val pill = CssBox().apply { radius = 999f; border = 1f }
    private val scale = Transition(1f)
    private val edge = ColorTransition(0)
    private val fillW = Transition(0f)
    private val iconInk = ColorTransition(0)
    private val readoutInk = ColorTransition(0)

    // Glassy
    private val lift = Spring(0.0, GlisseTokens.LIFT_K, GlisseTokens.LIFT_D)
    private val thumb = CssBox().apply { radius = 999f; shadows = GlisseTokens.THUMB_SHADOW }
    private val thumbPath = Path()
    private val sat = CssFilter.saturateBrightness(1.5f, 1f)
    private var lensRail = 0
    private var lensFill = 0
    private var lensLift = 0f
    private var lensProgress = 0f
    private var lensTx = 0f
    private var lensTy = 0f
    private var lensScale = 1f
    private var sliderLeft = 0f
    private var sliderRight = 0f
    private val lens: (Canvas) -> Unit = { c -> drawLens(c) }

    // the press
    private var held = false
    private var target = Target.NONE
    private var grab = 0f

    private enum class Target { NONE, TRACK, ICON, READOUT }

    init {
        isClickable = true
        onTheme()
    }

    override fun onTheme() {
        if (glassy != UiTheme.glassy) { glassy = UiTheme.glassy; requestLayout() }
        Type.apply(text, TextStyle(16f, 500), context)
        val m = text.fontMetrics
        baseline = -(m.ascent + m.descent) / 2f
        pill.fill = scope.glass
        lensRail = CssFilter.apply(sat, UiTheme.glisse.rail)
        lensFill = CssFilter.apply(sat, UiTheme.glass.lgFill)
        colours(animate = false)
        fillW.snap(targetFill())
    }

    private val fraction get() = if (max > min) ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f) else 0f
    private val filled get() = fraction > 0f

    private fun colours(animate: Boolean) {
        val now = Motion.now()
        val dur = if (animate) UiTheme.duration(UiTheme.base.fast) else 0L
        edge.to(if (held) scope.hairlineStrong else scope.hairline, now, dur, Motion.easeOut)
        val ink = if (glassy) scope.ink else if (muted) UiTheme.base.mute else if (filled) scope.sheet else scope.ink
        iconInk.to(ink, now, dur, Motion.easeOut)
        readoutInk.to(if (muted) UiTheme.base.mute else if (target == Target.READOUT) scope.ink else scope.inkMuted, now, dur, Motion.easeOut)
        if (!animate) { edge.snap(edge.target); iconInk.snap(iconInk.target); readoutInk.snap(readoutInk.target) }
        invalidate()
    }

    /** `.pill-fill` width in px of the padding box: `calc(var(--pill) + (100% - var(--pill)) * var(--fill))`, 0 when empty. */
    private fun targetFill(): Float {
        val inner = pillRight() - 2 * BORDER
        return if (filled) PILL + (inner - PILL) * fraction else 0f
    }

    private fun changed() {
        fillW.to(targetFill(), Motion.now(), UiTheme.duration(120), Easing.LINEAR)
        colours(animate = true)
    }

    private fun readoutWidth(): Float = readout?.let { max(READOUT_MIN, text.measureText(it) / d) } ?: 0f
    private fun pillRight(): Float = width / d - (if (readout != null) GAP + readoutWidth() else 0f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize((TARGET * d + 0.5f).toInt(), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { fillW.snap(targetFill()) }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        if (glassy) drawGlass(canvas, now) else drawSolid(canvas, now)
        readout?.let {
            text.color = readoutInk.value(now)
            canvas.drawText(it, width.toFloat(), height / 2f + baseline, text)
        }
        if (scale.running(now) || edge.running(now) || fillW.running(now) || iconInk.running(now) || readoutInk.running(now) || lift.moving) postInvalidateOnAnimation()
    }

    private fun drawSolid(canvas: Canvas, now: Long) {
        val pr = pillRight() * d
        val s = scale.value(now)
        canvas.save()
        canvas.translate(pr / 2f, height / 2f); canvas.scale(s, s); canvas.translate(-pr / 2f, -PILL * d / 2f)
        pill.borderColor = edge.value(now)
        pill.draw(canvas, this, pr, PILL * d)
        val fw = fillW.value(now)
        if (fw > 0f) {
            paint.color = fillColor ?: CssColor.fade(scope.ink, 0.76f)
            rect.set(BORDER * d, BORDER * d, (BORDER + fw) * d, (PILL - BORDER) * d)
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        }
        icon?.let {
            it.setTint(iconInk.value(now))
            IconView.place(it, BORDER * d, 0f, (BORDER + PILL) * d, PILL * d, ICON * d)
            it.draw(canvas)
        }
        canvas.restore()
    }

    private fun drawGlass(canvas: Canvas, now: Long) {
        val dt = frameSeconds(now)
        lift.step(dt)
        icon?.let {
            it.setTint(iconInk.value(now))
            IconView.place(it, 0f, 0f, PILL * d, height.toFloat(), ICON * d)
            it.draw(canvas)
        }
        sliderLeft = (PILL + GAP) * d
        sliderRight = pillRight() * d
        val w = sliderRight - sliderLeft
        val top = (height - SLIDER_H * d) / 2f
        val p = fraction
        val l = lift.value.coerceIn(0.0, 1.0).toFloat()
        val lensOn = lift.value > 0.01
        val s = (0.6 + 0.4 * lift.value).toFloat()
        val cx = sliderLeft + (w - KNOB_HIT * d) * p + KNOB_HIT / 2f * d
        val cy = top + SLIDER_H / 2f * d
        val tw = KNOB_W * d * s
        val th = SLIDER_H * d * s
        thumbPath.reset(); thumbPath.addRoundRect(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2, th / 2, th / 2, Path.Direction.CW)
        // glisse .lg-slider-track: 6 px at 17 px from the top, the fill ending under the knob's middle
        canvas.save()
        if (lensOn) canvas.clipOutPath(thumbPath)
        rect.set(sliderLeft, top + 17 * d, sliderRight, top + 23 * d)
        paint.color = UiTheme.glisse.rail; canvas.drawRoundRect(rect, 3 * d, 3 * d, paint)
        rect.right = sliderLeft + (w - KNOB_HIT * d) * p + KNOB_HIT / 2f * d
        paint.color = UiTheme.glass.lgFill; canvas.drawRoundRect(rect, 3 * d, 3 * d, paint)
        canvas.restore()
        canvas.save()
        canvas.translate(cx, cy); canvas.scale(s, s); canvas.translate(-KNOB_W * d / 2, -SLIDER_H * d / 2)
        lensLift = l; lensProgress = p; lensTx = cx; lensTy = cy; lensScale = s
        thumb.under = if (lensOn) lens else null
        thumb.draw(canvas, this, KNOB_W * d, SLIDER_H * d)
        paint.color = CssColor.fade(-1, 1f - 0.85f * l)
        rect.set(0f, 0f, KNOB_W * d, SLIDER_H * d)
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        canvas.restore()
    }

    /** glisse `.lg-slider-magnified`: the track a little thicker under the lifted knob, its fill giving way at the ends. */
    private fun drawLens(c: Canvas) {
        c.save()
        c.translate(KNOB_W * d / 2, SLIDER_H * d / 2); c.scale(1 / lensScale, 1 / lensScale); c.translate(-lensTx, -lensTy)
        val w = sliderRight - sliderLeft
        val h = LINE * (0.6f + 0.4f * lensLift) * d
        rect.set(sliderLeft, lensTy - h / 2, sliderRight, lensTy + h / 2)
        val b = 1f + 0.15f * (1f - lensLift)
        paint.color = bright(lensRail, b); c.drawRoundRect(rect, h / 2, h / 2, paint)
        val travel = (w - KNOB_HIT * d) * lensProgress
        val half = KNOB_HIT / 2f * d
        rect.right = sliderLeft + travel + min(half, travel * 4) + max(0f, half - (w - KNOB_HIT * d - travel) * 4)
        paint.color = bright(lensFill, b); c.drawRoundRect(rect, h / 2, h / 2, paint)
        c.restore()
    }

    private fun bright(c: Int, b: Float): Int {
        fun ch(v: Int) = (v * b + 0.5f).toInt().coerceAtMost(255)
        return (c and 0xFF000000.toInt()) or (ch(c shr 16 and 0xFF) shl 16) or (ch(c shr 8 and 0xFF) shl 8) or ch(c and 0xFF)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                target = hit(e.x)
                if (target == Target.NONE) return false
                hold(true)
                if (target == Target.TRACK) {
                    parent?.requestDisallowInterceptTouchEvent(!glassy)
                    if (glassy) {
                        // a press on the knob keeps where it took it; elsewhere the knob jumps there (a range input)
                        val w = sliderRight - sliderLeft
                        val cx = sliderLeft + (w - KNOB_HIT * d) * fraction + KNOB_HIT / 2f * d
                        grab = if (abs(e.x - cx) <= KNOB_HIT / 2f * d) e.x - cx else 0f
                        restartClock(); lift.set(1.0, UiTheme.speed <= 0f)
                    }
                    valueAt(e.x)
                }
                colours(animate = true)
            }
            MotionEvent.ACTION_MOVE -> if (target == Target.TRACK) {
                if (glassy) parent?.requestDisallowInterceptTouchEvent(true)
                valueAt(e.x)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val inside = e.actionMasked == MotionEvent.ACTION_UP && e.x >= 0 && e.x <= width && e.y >= 0 && e.y <= height
                if (inside && target == Target.ICON) onIconClick?.invoke()
                if (inside && target == Target.READOUT) onReadoutClick?.invoke()
                if (inside) performClick()
                target = Target.NONE
                hold(false)
                if (glassy) { restartClock(); lift.set(0.0, UiTheme.speed <= 0f) }
                colours(animate = true)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun hit(x: Float): Target {
        val pr = pillRight() * d
        if (x > pr) return if (onReadoutClick != null && x >= pr + GAP * d) Target.READOUT else Target.NONE
        if (glassy) return if (x >= (PILL + GAP) * d) Target.TRACK else if (onIconClick != null) Target.ICON else Target.NONE
        return if (x >= (BORDER + PILL) * d) Target.TRACK else if (onIconClick != null) Target.ICON else Target.NONE
    }

    private fun hold(on: Boolean) {
        if (held == on) return
        held = on
        if (!glassy) scale.to(if (on) 1.012f else 1f, Motion.now(), UiTheme.duration(UiTheme.base.fast), Motion.easeOut)
        onHeld?.invoke(on)
    }

    private fun valueAt(x: Float) {
        val f = if (glassy) {
            val w = sliderRight - sliderLeft
            ((x - grab - sliderLeft - KNOB_HIT / 2f * d) / (w - KNOB_HIT * d)).coerceIn(0f, 1f)
        } else {
            val left = (BORDER + PILL) * d
            val right = (pillRight() - BORDER) * d
            ((x - left) / (right - left)).coerceIn(0f, 1f)
        }
        val v = (min + f * (max - min)).roundToInt()
        if (v != value) onValue?.invoke(v)
    }

    private companion object {
        // screen.css .pill-slider, .pill, .pill-icon, .pill-readout
        const val PILL = 48f
        const val BORDER = 1f
        const val ICON = 22f
        const val GAP = 16f
        const val TARGET = 56f
        const val READOUT_MIN = 64f
        // glisse .lg-slider: 40 px tall, a 36 px native thumb, the glass knob 60 x 40 at scale 0.6, the lens line 8 px
        const val SLIDER_H = 40f
        const val KNOB_HIT = 36f
        const val KNOB_W = 60f
        const val LINE = 8f
    }
}
