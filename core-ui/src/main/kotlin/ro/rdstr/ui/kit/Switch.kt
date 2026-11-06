package ro.rdstr.ui.kit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.tokens.Shadow
import kotlin.math.abs

/**
 * SettingsApp.tsx `Switch`: on Solid the round switch of `.switch` (music.css), on Glassy glisse's `Switch` at
 * `.set-switch-glass` (`zoom: 1.3333`, glassy.css), chosen as glassy.tsx `useGlisse` chooses.
 *
 * Controlled: a tap or a drag asks [onChange] for the new state, and the switch shows [checked] as the caller sets it.
 *
 * Solid: a 64 x 38 track, grey `rgba(120,120,128,.36)` off and `#34c759` on (220 ms `--seamless`), a 32 px white knob
 * with its drop shadow sliding 26 px on `--spring-glyph` (360 ms); a tap toggles.
 * Glassy: glisse's track (`--lg-track`, `--lg-on`) and glass knob, on glisse's springs (position and colour 1000/80,
 * lift 2000/80): pressed, the knob lifts and grows from 0.65 to 0.9 and turns to clear glass, under which the track
 * shows narrower and thinner, as glisse's lens draws it; it follows a horizontal drag, a vertical one gives the
 * touch back to the list, and a release flips it, or lands it on the side it was dragged to.
 */
@Mirrors("glassy.tsx")
class KitSwitch(context: Context) : KitView(context) {
    var checked = false
        set(v) { if (field == v) return; field = v; sync(); invalidate() }
    var onChange: ((Boolean) -> Unit)? = null

    private var glassy = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    // Solid
    private val track = ColorTransition(SOLID_OFF)
    private val knobX = Transition(0f)
    private val knob = CssBox().apply { radius = SOLID_KNOB / 2f; fill = -1; shadows = listOf(Shadow(false, 0f, 2f, 6f, 0f, 0x40000000)) }

    // Glassy
    private val position = Spring(0.0, GlisseTokens.SWITCH_K, GlisseTokens.SWITCH_D)
    private val tint = Spring(0.0, GlisseTokens.SWITCH_K, GlisseTokens.SWITCH_D)
    private val lift = Spring(0.0, GlisseTokens.LIFT_K, GlisseTokens.LIFT_D)
    private val thumb = CssBox().apply { radius = 999f; shadows = GlisseTokens.THUMB_SHADOW.map { it.scaled(ZOOM) } }
    private val thumbPath = Path()
    private val sat = CssFilter.saturateBrightness(1.5f, 1f)
    private var lensTrack = 0
    private var lensOn = 0
    private val lensContent: (Canvas) -> Unit = { c -> drawLens(c) }

    // the press
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var from = 0.0
    private var moved = false
    private var pressing = false

    init {
        isClickable = true
        onTheme()
        sync(instant = true)
    }

    override fun onTheme() {
        if (glassy != UiTheme.glassy) { glassy = UiTheme.glassy; requestLayout(); sync(instant = true) }
        val g = UiTheme.glisse
        lensTrack = CssFilter.apply(sat, g.track)
        lensOn = CssFilter.apply(sat, g.on)
    }

    private fun sync(instant: Boolean = false) {
        val now = Motion.now()
        val on = if (checked) 1.0 else 0.0
        if (instant || !isAttachedToWindow) {
            track.snap(if (checked) SOLID_ON else SOLID_OFF); knobX.snap(if (checked) SOLID_TRAVEL else 0f)
            position.set(on, true); tint.set(on, true)
        } else {
            track.to(if (checked) SOLID_ON else SOLID_OFF, now, UiTheme.duration(220), Motion.seamless)
            knobX.to(if (checked) SOLID_TRAVEL else 0f, now, UiTheme.duration(360), Motion.springGlyph)
            restartClock(); position.set(on); tint.set(on)
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (glassy) GLASS_W else SOLID_W
        val h = if (glassy) GLASS_H else SOLID_H
        setMeasuredDimension(resolveSize((w * d + 0.5f).toInt(), widthMeasureSpec), resolveSize((h * d + 0.5f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        if (glassy) drawGlass(canvas, now) else drawSolid(canvas, now)
    }

    private fun drawSolid(canvas: Canvas, now: Long) {
        paint.color = track.value(now)
        rect.set(0f, 0f, SOLID_W * d, SOLID_H * d)
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        canvas.save()
        canvas.translate((SOLID_INSET + knobX.value(now)) * d, SOLID_INSET * d)
        knob.draw(canvas, this, SOLID_KNOB * d, SOLID_KNOB * d)
        canvas.restore()
        if (track.running(now) || knobX.running(now)) postInvalidateOnAnimation()
    }

    private fun drawGlass(canvas: Canvas, now: Long) {
        val dt = frameSeconds(now)
        position.step(dt); tint.step(dt); lift.step(dt)
        val l = lift.value.coerceIn(0.0, 1.0).toFloat()
        val on = tint.value.coerceIn(0.0, 1.0).toFloat()
        val g = UiTheme.glisse
        val lens = lift.value > 0.01
        // the knob: translate(x, -50%) scale(0.65 + 0.25 lift) about its centre
        val s = (0.65 + 0.25 * lift.value).toFloat()
        val cx = (THUMB_LEFT + THUMB_W / 2f + position.value.toFloat() * TRAVEL) * d
        val cy = GLASS_H / 2f * d
        val tw = THUMB_W * d * s
        val th = THUMB_H * d * s
        thumbPath.reset(); thumbPath.addRoundRect(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2, th / 2, th / 2, Path.Direction.CW)

        canvas.save()
        if (lens) canvas.clipOutPath(thumbPath)
        rect.set(0f, 0f, GLASS_W * d, GLASS_H * d)
        paint.color = g.track; canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        paint.color = CssColor.fade(g.on, on); canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        canvas.restore()

        canvas.save()
        canvas.translate(cx, cy); canvas.scale(s, s); canvas.translate(-THUMB_W * d / 2, -THUMB_H * d / 2)
        lensScale = s; lensCx = cx; lensCy = cy; lensLift = l; lensTint = on
        thumb.under = if (lens) lensContent else null
        thumb.draw(canvas, this, THUMB_W * d, THUMB_H * d)
        // the white face over the glass, fading as it lifts
        paint.color = CssColor.fade(-1, 1f - 0.85f * l)
        rect.set(0f, 0f, THUMB_W * d, THUMB_H * d)
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        canvas.restore()
        if (position.moving || tint.moving || lift.moving) postInvalidateOnAnimation()
    }

    private var lensScale = 1f
    private var lensCx = 0f
    private var lensCy = 0f
    private var lensLift = 0f
    private var lensTint = 0f

    /**
     * What the lifted knob shows of the track (glisse's `.lg-switch-inset`): the track narrower and thinner, growing
     * with the lift, through `brightness(1 + 0.15 (1 - lift)) saturate(1.5)` (the knob's backdrop filter).
     * Drawn in the knob's space, so back to the switch's first.
     */
    private fun drawLens(c: Canvas) {
        c.save()
        c.translate(THUMB_W * d / 2, THUMB_H * d / 2); c.scale(1 / lensScale, 1 / lensScale); c.translate(-lensCx, -lensCy)
        // the inset bed is the track grown 12 px each side and 6 px above and below; its track is that less 24 and 12
        val w = GLASS_W * (0.85f + 0.1f * lensLift) * d
        val h = GLASS_H * 0.75f * (0.525f + 0.45f * lensLift) * d
        val cx = GLASS_W / 2 * d
        val cy = GLASS_H / 2 * d
        rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        val b = 1f + 0.15f * (1f - lensLift)
        paint.color = bright(lensTrack, b); c.drawRoundRect(rect, h / 2, h / 2, paint)
        paint.color = CssColor.fade(bright(lensOn, b), lensTint); c.drawRoundRect(rect, h / 2, h / 2, paint)
        c.restore()
    }

    private fun bright(c: Int, b: Float): Int {
        fun ch(v: Int) = (v * b + 0.5f).toInt().coerceAtMost(255)
        return (c and 0xFF000000.toInt()) or (ch(c shr 16 and 0xFF) shl 16) or (ch(c shr 8 and 0xFF) shl 8) or ch(c and 0xFF)
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!glassy) ask(!checked)
        return true
    }

    private fun ask(next: Boolean) {
        onChange?.invoke(next)
        // the caller may keep the old state: the switch then settles back to it
        post { sync() }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!glassy) return super.onTouchEvent(e)
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; downAt = Motion.now(); moved = false; pressing = true
                from = if (checked) 1.0 else 0.0
                restartClock(); lift.set(1.0); invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (pressing) {
                val dx = e.x - downX
                val dy = abs(e.y - downY)
                val held = moved || Motion.now() - downAt >= GlisseTokens.HOLD_MS
                if (!held && dy > 10 && dy > abs(dx) + 4) { settle(null); return true }
                if (held) parent?.requestDisallowInterceptTouchEvent(true)
                if (!moved && abs(dx) < 4) return true
                moved = true
                val value = from + dx / DRAG_TRAVEL
                val overshoot = if (value < 0) value else if (value > 1) value - 1 else 0.0
                restartClockIfIdle()
                position.set(value.coerceIn(0.0, 1.0) + overshoot / 22)
                tint.set(if (value > 0.5) 1.0 else 0.0)
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (pressing) {
                val value = from + (e.x - downX) / DRAG_TRAVEL
                val near = e.y >= -24 && e.y <= height + 24
                settle(if (moved) value > 0.5 else if (near) !checked else null)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> if (pressing) settle(null)
        }
        return true
    }

    private fun restartClockIfIdle() { if (!position.moving && !tint.moving && !lift.moving) restartClock() }

    private fun settle(next: Boolean?) {
        pressing = false
        restartClockIfIdle()
        lift.set(0.0)
        if (next == null || next == checked) sync() else ask(next)
        invalidate()
    }

    private companion object {
        // music.css .switch, .switch-knob
        const val SOLID_W = 64f
        const val SOLID_H = 38f
        const val SOLID_KNOB = 32f
        const val SOLID_INSET = 3f
        const val SOLID_TRAVEL = 26f
        const val SOLID_OFF = 0x5C787880
        const val SOLID_ON = 0xFF34C759.toInt()

        // glisse .lg-switch at --lg-scale 0.375, under glassy.css .set-switch-glass { zoom: 1.3333 }
        const val ZOOM = 1.3333f
        const val SCALE = 0.375f
        const val GLASS_W = 160f * SCALE * ZOOM
        const val GLASS_H = 67f * SCALE * ZOOM
        const val THUMB_W = 146f * SCALE * ZOOM
        const val THUMB_H = 92f * SCALE * ZOOM
        const val THUMB_LEFT = -21.95f * SCALE * ZOOM
        /** glisse switch.tsx TRAVEL, the knob's way at the switch's zoom */
        const val TRAVEL = 21.7125f * ZOOM
        /**
         * The same TRAVEL as glisse compares pointer moves with: in the emulator's pixels, which are the panel's.
         * Its thresholds (4, 10 and 24 px) are the panel's pixels too.
         */
        const val DRAG_TRAVEL = 21.7125f
    }
}
