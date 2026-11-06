package ro.rdstr.ui.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.widget.FrameLayout
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitView
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.uiKey
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What Gestures.tsx dispatches. */
interface GestureActions {
    fun home()
    fun openRecents()
    fun back()
}

/**
 * Gestures.tsx: the handle along the bottom edge (flick up for home, push up and hold for the open apps) and a strip
 * along each side edge (pull in to go back, with an arrow that follows the finger). Touches anywhere else go through
 * to what is under. All distances are canvas px.
 */
@Mirrors("Gestures.tsx")
@SuppressLint("ViewConstructor")
class GesturesView(context: Context, private val actions: GestureActions) : KitView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleInk = ColorTransition(0)
    private val lift = Transition(0f)
    private val rect = RectF()
    private val arrow = CssBox().apply { radius = 999f; border = 1f }
    private val caret = context.getDrawable(Icons.CARET_LEFT)!!.mutate()
    private var held = false

    private enum class Strip { NAV, LEFT, RIGHT }
    private var strip: Strip? = null
    private var startX = 0f
    private var startY = 0f
    private var pull = -1f
    private var pullY = 0f
    private var armed = false
    // the nav handle's pause and its last two samples
    private var anchor = 0f
    private var timerOn = false
    private var opened = false
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private val openRecents = Runnable { timerOn = false; opened = true; actions.openRecents() }

    init { uiKey = null; onTheme() }

    override fun onTheme() {
        val b = UiTheme.base
        handleInk.snap(CssColor.fade(b.ink, if (held) 0.85f else 0.55f))
        armedLook()
        invalidate()
    }

    private fun armedLook() {
        val b = UiTheme.base
        val fill = when {
            !armed -> b.sheetRaised
            UiTheme.light -> b.ink
            else -> CssColor.lerp(b.sheetRaised, b.ink, 0.22f)
        }
        if (UiTheme.glassy) { arrow.glassy(UiTheme.glass, if (armed) fill else UiTheme.glass.glassyFill); if (armed) arrow.gradient = null } else arrow.solid(fill)
        arrow.borderColor = b.hairlineStrong
        caret.setTint(if (!armed) b.inkMuted else if (UiTheme.light) b.sheet else b.ink)
    }

    private fun stripAt(x: Float, y: Float): Strip? {
        val w = width / d; val h = height / d
        val cx = x / d; val cy = y / d
        return when {
            cy >= h - 28 -> Strip.NAV
            cy >= 56 && cx < 20 -> Strip.LEFT
            cy >= 56 && cx >= w - 20 -> Strip.RIGHT
            else -> null
        }
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            strip = stripAt(e.x, e.y)
            if (strip == null) return false
        }
        if (strip == null) return false
        return onTouchEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (strip) {
            Strip.NAV -> nav(e)
            Strip.LEFT, Strip.RIGHT -> edge(e)
            null -> return false
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) strip = null
        invalidate()
        return true
    }

    private fun nav(e: MotionEvent) {
        val travel = (startY - e.y) / d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = e.y; anchor = 0f; samples.clear(); opened = false
                press(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (opened) return
                samples.addLast(e.eventTime to travel); while (samples.size > 2) samples.removeFirst()
                lift.snap(max(0f, min(travel, RECENTS_PX)))
                if (travel < RECENTS_PX) { stopTimer(); return }
                // the timer fires once the finger rests; only real movement, not drift, restarts it
                if (!timerOn || abs(travel - anchor) > HOLD_SLOP_PX) {
                    stopTimer(); anchor = travel; timerOn = true
                    handler.postDelayed(openRecents, UiTheme.duration(RECENTS_HOLD_MS).coerceAtLeast(1))
                }
            }
            MotionEvent.ACTION_UP -> {
                stopTimer()
                if (!opened) {
                    val speed = if (samples.size == 2) (samples.last().second - samples.first().second) / max(1L, samples.last().first - samples.first().first) else Float.POSITIVE_INFINITY
                    if (travel >= RECENTS_PX && speed < FLICK_SPEED) { opened = true; actions.openRecents() }
                    else if (travel >= HOME_PX) actions.home()
                }
                release()
            }
            MotionEvent.ACTION_CANCEL -> { stopTimer(); release() }
        }
    }

    private fun stopTimer() { handler.removeCallbacks(openRecents); timerOn = false }

    private fun release() {
        lift.to(0f, Motion.now(), UiTheme.duration(160), Motion.easeOut)
        press(false)
    }

    private fun press(on: Boolean) {
        held = on
        val b = UiTheme.base
        handleInk.to(CssColor.fade(b.ink, if (on) 0.85f else 0.55f), Motion.now(), UiTheme.duration(b.fast), Motion.easeOut)
    }

    private fun edge(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = e.x; pull = -1f }
            MotionEvent.ACTION_MOVE -> {
                val dx = (if (strip == Strip.LEFT) e.x - startX else startX - e.x) / d
                pull = max(0f, dx)
                pullY = e.y / d - 56f
                val now = pull >= BACK_PX
                if (now != armed) { armed = now; armedLook() }
            }
            MotionEvent.ACTION_UP -> { if (armed) actions.back(); pull = -1f; armed = false; armedLook() }
            MotionEvent.ACTION_CANCEL -> { pull = -1f; armed = false; armedLook() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val w = width.toFloat(); val h = height.toFloat()
        // .nav-bar: 28 px at the bottom, the 120 x 4 handle 10 px above the edge, lifted by a quarter of the pull
        handlePaint.color = handleInk.value(now)
        val y = h - 14 * d - lift.value(now) * 0.25f * d
        rect.set(w / 2 - 60 * d, y, w / 2 + 60 * d, y + 4 * d)
        canvas.drawRoundRect(rect, 2 * d, 2 * d, handlePaint)
        if (pull >= 0f && strip != Strip.NAV && strip != null) {
            val left = strip == Strip.LEFT
            val s = 44 * d
            val x = (if (left) 4 * d + min(pull, BACK_PX) * 0.5f * d else w - 4 * d - s - min(pull, BACK_PX) * 0.5f * d)
            val top = 56 * d + pullY * d - 22 * d
            canvas.saveLayerAlpha(x - s, top - s, x + 2 * s, top + 2 * s, (min(1f, pull / BACK_PX) * 255).toInt())
            canvas.translate(x, top)
            arrow.draw(canvas, this, s, s)
            if (!left) canvas.scale(-1f, 1f, s / 2, s / 2)
            IconView.place(caret, 0f, 0f, s, s, 22 * d)
            caret.draw(canvas)
            canvas.restore()
        }
        if (handleInk.running(now) || lift.running(now)) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() { stopTimer(); super.onDetachedFromWindow() }

    private companion object {
        const val HOME_PX = 40f
        const val RECENTS_PX = 80f
        const val RECENTS_HOLD_MS = 150L
        const val HOLD_SLOP_PX = 8f
        const val FLICK_SPEED = 0.5f
        const val BACK_PX = 72f
    }
}
