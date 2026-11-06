package ro.rdstr.ui.home.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import ro.rdstr.ui.tokens.Base
import ro.rdstr.ui.tokens.Fonts
import java.util.Calendar

/**
 * Clock.tsx `Dial` in the always-on display (Screen.tsx `div.aod-dial`): the analog face filling the view, hands
 * turning in 600 ms as on home. The slot it goes in draws it at `opacity: 0.55; filter: grayscale(1)`.
 * Inputs: [setTheme], [setFont] and the time source [now]; it ticks on the second while shown.
 */
class AodDialView(context: Context) : View(context) {
    /** The unit's time in epoch ms, read on every tick in the default time zone. */
    var now: () -> Long = System::currentTimeMillis
        set(value) { field = value; tick(false) }

    private var t: Base = Base.DARK
    private val dial = Dial()
    private val hourHand = Hand(HAND_MS, t.easeOut)
    private val minuteHand = Hand(HAND_MS, t.easeOut)
    private val cal = Calendar.getInstance()
    private var second = 0
    private var family = ""
    private var running = false
    private var placed = false
    private val ticker = Runnable { tick(false) }

    init { dial.setColors(t) }

    fun setTheme(light: Boolean, glassy: Boolean) {
        t = Base.of(light, glassy)
        dial.setColors(t)
        invalidate()
    }

    /** The screen font by its CSS family name ("Inter", "Geist"). */
    fun setFont(family: String) {
        if (family == this.family) return
        this.family = family
        val tf = resources.getFont(Fonts.byFamily[family] ?: Fonts.INTER)
        val x = Rect()
        Paint().apply { typeface = tf; textSize = 1000f }.getTextBounds("x", 0, 1, x)
        dial.setFont(tf, -x.top / 1000f)
        invalidate()
    }

    private fun tick(place: Boolean) {
        cal.timeInMillis = now()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        val at = SystemClock.uptimeMillis()
        hourHand.set(HandAngles.hour(h, m, s), at, !place)
        minuteHand.set(HandAngles.minute(m, s), at, !place)
        second = s
        invalidate()
        removeCallbacks(ticker)
        if (running) postDelayed(ticker, 1000 - Math.floorMod(now(), 1000L))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        tick(place = !placed)
        placed = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        removeCallbacks(ticker)
    }

    override fun onDraw(canvas: Canvas) {
        val at = SystemClock.uptimeMillis()
        canvas.save()
        canvas.scale(width / 480f, height / 480f)
        dial.draw(canvas, hourHand.at(at), minuteHand.at(at), HandAngles.second(second), t.hand, t.second)
        canvas.restore()
        if (hourHand.moving(at) || minuteHand.moving(at)) postInvalidateOnAnimation()
    }

    private companion object { const val HAND_MS = 600L }
}
