package ro.rdstr.ui.maps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.View
import ro.rdstr.car.SpeedWarning
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the map's status shows (MapsApp.tsx: `street`, `speed.kmh`, `warning`, `shell.speedLimitSign`): the street
 * the car is on ("" off a named road), its speed, the posted limit of its road (null with none), whether it is over
 * it, and the speed ring (core-car `SpeedWarning.ring`). [sign] is the setting that shows the limit sign.
 */
data class MapStatus(
    val street: String = "",
    val kmh: Int = 0,
    val limit: Int? = null,
    val over: Boolean = false,
    val lit: Int = 0,
    val tone: SpeedWarning.Tone = SpeedWarning.Tone.WHITE,
    val sign: Boolean = true,
)

/**
 * The street pill, the speed dial and the limit sign (`div.map-status`, MapsApp.tsx:219-234, maps.css:28-54), in the
 * map's own layer: 32 px in and 44 px up in the Maps screen; on the card on home ([onCard]) 14 px in, 34 px up (118
 * over a sound strip) and drawn at 0.78 from its bottom left, the street at most 250 px wide.
 *
 * Off a named road the street pill folds away (its width to 0 and its gap with it in 520 ms on `--spring-swap`,
 * opacity in 220 ms, scale 0.92) keeping its last name while it goes, and the speed slides into its place. Over the
 * limit the number turns red (260 ms) and the dial pulses once; the sign pops in (480 ms `--spring-glyph`, opacity
 * 160 ms) and a new limit's number swaps in (260 ms).
 */
class MapStatusView(context: Context) : CssGroup(context) {
    private val slot = StreetSlot(context)
    private val dial = SpeedDial(context)
    private var state = MapStatus()
    private var bound = false

    /** The card on home, and a sound strip under its map. */
    var onCard = false; set(v) { if (field != v) { field = v; slot.maxW = if (v) 250f else 520f; scaleX = if (v) CARD_SCALE else 1f; scaleY = scaleX; requestLayout() } }
    var strip = false; set(v) { if (field != v) { field = v; (parent as? View)?.requestLayout() } }
    /** What lies under the pill and the dial, for the frost on Glassy. */
    var ground = 0; set(v) { if (field != v) { field = v; slot.theme(); dial.theme() } }

    init {
        uiKey = "map-status"
        addView(slot)
        addView(dial)
    }

    /** Shows [s]; the first state is drawn at once, later ones move as the emulator's transitions do. */
    fun bind(s: MapStatus) {
        val animate = bound
        bound = true
        state = s
        slot.show(s.street, animate)
        dial.show(s, animate)
        requestLayout()
    }

    override fun measureCss(maxW: Float, maxH: Float) {
        measureBox(slot, maxW, 64f)
        cssW = slot.shownW + slot.gap + 84f
        cssH = 74f
    }

    // `align-items: flex-end`: the pill on the bottom line, the dial 10 px below it (`margin-bottom: -10px`)
    override fun placeCss() {
        place(slot, 0f, cssH - 64f, slot.shownW, 64f)
        place(dial, slot.shownW + slot.gap, 0f, 84f, 84f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { pivotX = 0f; pivotY = h.toFloat() }

    private fun ground() = ground

    /** `.map-street-slot` and its pill: the slot's width runs 0 to the pill's own width, the pill clips into it. */
    private inner class StreetSlot(context: Context) : CssGroup(context) {
        val pill = KitText(context, TextStyle(22f, 600)).apply {
            uiKey = "map-pill.map-street"
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        private val box = CssBox().apply { radius = 32f }
        var maxW = 520f
        private val open = Transition(0f)
        private val fade = Transition(0f)
        val shownW get() = natural * open.value(Motion.now())
        val gap get() = 12f * open.value(Motion.now())
        private var natural = 0f

        init {
            uiKey = "map-street-slot"
            addView(pill)
            setWillNotDraw(false)
            UiTheme.link(this) { theme() }
            theme()
        }

        fun theme() {
            MapsLook.panel(box, ground(), 0.82f, 0.92f)
            pill.setTextColor(MapsLook.strongInk)
            invalidate()
        }

        fun show(street: String, animate: Boolean) {
            // the last name stays written while the pill folds away
            if (street.isNotEmpty()) pill.text = street
            val to = if (street.isEmpty()) 0f else 1f
            val now = Motion.now()
            if (animate) {
                open.to(to, now, UiTheme.duration(520L), Motion.springSwap)
                fade.to(to, now, UiTheme.duration(220L), Motion.easeOut)
            } else { open.snap(to); fade.snap(to) }
            step()
        }

        private val frame = Runnable { step() }

        private fun step() {
            val now = Motion.now()
            val p = open.value(now)
            alpha = fade.value(now)
            scaleX = 0.92f + 0.08f * p; scaleY = scaleX
            visibility = if (p <= 0f && !open.running(now)) INVISIBLE else VISIBLE
            this@MapStatusView.requestLayout()
            if (open.running(now) || fade.running(now)) postOnAnimation(frame)
        }

        override fun measureCss(maxW: Float, maxH: Float) {
            // `.map-pill`: 24 px each side and the 1 px edge around the name, at most 520 (250 on the card)
            natural = minOf(this.maxW, pill.cssWidth() + 48f + 2 * border(1f))
            cssW = shownW
            cssH = 64f
        }

        override fun placeCss() = place(pill, 0f, 0f, shownW, 64f)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            pivotX = 0f; pivotY = h / 2f
            val p = (24 * d).toInt()
            pill.setPadding(p, 0, p, 0)
        }

        override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
    }

    /** `.map-speed`: the round dial, its ring of ticks, the number over its unit, and the limit sign at its top right. */
    private inner class SpeedDial(context: Context) : CssGroup(context) {
        private val box = CssBox().apply { radius = 999f }
        private val ticks = SpeedTicks(context)
        private val number = KitText(context, TextStyle(30f, 700, -0.03f, 1f)).apply { uiKey = "b"; includeFontPadding = false }
        private val unit = KitText(context, TextStyle(13f, 600, lineHeight = 1f)).apply { uiKey = "small"; text = "km/h"; includeFontPadding = false }
        private val sign = LimitSign(context)
        private val red = ColorTransition(0)
        private var over = false
        private var pulseAt = -1L

        init {
            uiKey = "map-speed"
            setWillNotDraw(false)
            addView(ticks); addView(number); addView(unit); addView(sign)
            UiTheme.link(this) { theme() }
            theme()
        }

        fun theme() {
            MapsLook.panel(box, ground(), 0.9f, 0.94f)
            unit.setTextColor(MapsLook.speedInk)
            red.snap(if (over) MapsLook.RED else MapsLook.strongInk)
            number.setTextColor(red.target)
            ticks.theme()
            invalidate()
        }

        fun show(s: MapStatus, animate: Boolean) {
            number.text = s.kmh.toString()
            ticks.show(s.lit, s.tone, animate)
            sign.show(if (s.sign) s.limit else null, animate)
            val now = Motion.now()
            val ink = if (s.over) MapsLook.RED else MapsLook.strongInk
            if (animate) red.to(ink, now, UiTheme.duration(260L), Motion.easeOut) else red.snap(ink)
            // `.map-speed.is-over { animation: speed-over }`: one pulse each time the class comes on
            if (s.over && !over && animate && UiTheme.speed > 0f) pulseAt = now
            over = s.over
            invalidate()
        }

        override fun measureCss(maxW: Float, maxH: Float) { cssW = 84f; cssH = 84f }

        override fun placeCss() {
            val b = border(1f)
            // `inset: -1px` from the padding box
            place(ticks, b - 1f, b - 1f, 84f, 84f)
            val nw = number.cssWidth()
            val uw = unit.cssWidth()
            val top = b + (84f - 2 * b - (30f + 3f + 13f)) / 2
            place(number, (84f - nw) / 2, top, nw, 30f)
            place(unit, (84f - uw) / 2, top + 33f, uw, 13f)
            // `top: -16px; right: -20px` from the padding box
            place(sign, 84f - b + 20f - 46f, b - 16f, 46f, 46f)
        }

        override fun onDraw(canvas: Canvas) {
            val now = Motion.now()
            number.setTextColor(red.value(now))
            if (pulseAt >= 0) {
                val s = pulse(now - pulseAt)
                scaleX = s; scaleY = s
                if (s == 1f && now - pulseAt >= UiTheme.duration(PULSE_MS)) pulseAt = -1
            }
            box.draw(canvas, this, width.toFloat(), height.toFloat())
            if (red.running(now) || pulseAt >= 0) postInvalidateOnAnimation()
        }

        /** `@keyframes speed-over`: 1 to 1.1 by 35 %, back to 1, each part on `--spring-glyph`. */
        private fun pulse(ms: Long): Float {
            val total = UiTheme.duration(PULSE_MS).toFloat()
            val t = (ms / total).coerceIn(0f, 1f)
            return if (t < 0.35f) 1f + 0.1f * Motion.springGlyph.at(t / 0.35f)
            else 1.1f - 0.1f * Motion.springGlyph.at((t - 0.35f) / 0.65f)
        }
    }

    /** `.map-limit`: a white disc in a 5 px red ring with the posted limit, as on the road. */
    private inner class LimitSign(context: Context) : CssGroup(context) {
        private val box = CssBox().apply {
            radius = 999f; fill = -1; border = 5f; borderColor = 0xFFE3141B.toInt()
            shadows = listOf(Shadow(false, 0f, 3f, 10f, 0f, CssColor.withAlpha(0xFF000000.toInt(), 0.35f)))
        }
        private val number = KitText(context, TextStyle(19f, 900, -0.04f)).apply { uiKey = "b"; setTextColor(0xFF111111.toInt()); includeFontPadding = false }
        private val pop = Transition(0f)
        private val fade = Transition(0f)
        private val swap = Transition(1f)

        init {
            uiKey = "map-limit"
            setWillNotDraw(false)
            addView(number)
        }

        fun show(limit: Int?, animate: Boolean) {
            val now = Motion.now()
            // the last limit stays written while the sign shrinks away; a new one swaps in
            if (limit != null && number.text.toString() != limit.toString()) {
                number.text = limit.toString()
                if (animate && UiTheme.speed > 0f) { swap.snap(0f); swap.to(1f, now, UiTheme.duration(260L), Motion.easeOut) }
                requestLayout()
            }
            val to = if (limit != null) 1f else 0f
            if (animate) {
                pop.to(to, now, UiTheme.duration(480L), Motion.springGlyph)
                fade.to(to, now, UiTheme.duration(160L), Motion.easeOut)
            } else { pop.snap(to); fade.snap(to) }
            if (to > 0f || fade.running(now)) visibility = VISIBLE else if (!animate) visibility = INVISIBLE
            invalidate()
        }

        override fun measureCss(maxW: Float, maxH: Float) { cssW = 46f; cssH = 46f }

        override fun placeCss() {
            val w = number.cssWidth()
            val h = number.paint.fontMetrics.let { (it.descent - it.ascent) / d }
            place(number, (46f - w) / 2, (46f - h) / 2, w, h)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { pivotX = w / 2f; pivotY = h / 2f }

        override fun onDraw(canvas: Canvas) {
            val now = Motion.now()
            val s = 0.3f + 0.7f * pop.value(now)
            scaleX = s; scaleY = s
            alpha = fade.value(now)
            if (fade.value(now) <= 0f && !fade.running(now)) visibility = INVISIBLE
            val k = swap.value(now)
            number.alpha = k
            number.scaleX = 0.7f + 0.3f * k; number.scaleY = number.scaleX
            box.draw(canvas, this, width.toFloat(), height.toFloat())
            if (pop.running(now) || fade.running(now) || swap.running(now)) postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val CARD_SCALE = 0.78f
        const val PULSE_MS = 560L
    }
}

/**
 * The speed ring (`svg.map-speed-ticks`, MapsApp.tsx:44-63): 60 ticks in the 84 px box, from radius 34 to 39.5,
 * 1.7 px wide with round caps, tick 0 at the bottom going clockwise. The first [lit] ticks take the tone's colour;
 * each tick's colour changes in 240 ms on `--ease-out`, 5 ms after the one before, so a change sweeps round.
 */
internal class SpeedTicks(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val from = IntArray(TICKS)
    private val to = IntArray(TICKS)
    private val start = LongArray(TICKS)
    private var duration = 0L
    private var lit = 0
    private var tone = SpeedWarning.Tone.WHITE
    private val geometry = FloatArray(TICKS * 4).also { g -> for (i in 0 until TICKS) Ticks.line(i, g, i * 4) }

    init { uiKey = "map-speed-ticks" }

    fun theme() {
        for (i in 0 until TICKS) { to[i] = colour(i); from[i] = to[i]; start[i] = 0 }
        invalidate()
    }

    private fun colour(i: Int) = if (i >= lit) MapsLook.tickOff else when (tone) {
        SpeedWarning.Tone.WHITE -> MapsLook.tickLit
        SpeedWarning.Tone.ORANGE -> MapsLook.ORANGE
        SpeedWarning.Tone.RED -> MapsLook.RED
    }

    fun show(lit: Int, tone: SpeedWarning.Tone, animate: Boolean) {
        if (lit == this.lit && tone == this.tone) return
        val now = Motion.now()
        this.lit = lit; this.tone = tone
        duration = UiTheme.duration(240L)
        for (i in 0 until TICKS) {
            val next = colour(i)
            if (next == to[i]) continue
            from[i] = if (animate) value(i, now) else next
            to[i] = next
            start[i] = now + UiTheme.duration(i * 5L)
        }
        invalidate()
    }

    private fun value(i: Int, now: Long): Int {
        if (duration <= 0L || now >= start[i] + duration) return to[i]
        if (now <= start[i]) return from[i]
        return CssColor.lerp(from[i], to[i], Motion.easeOut.at((now - start[i]).toFloat() / duration))
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val k = width / 84f
        paint.strokeWidth = 1.7f * k
        var running = false
        for (i in 0 until TICKS) {
            paint.color = value(i, now)
            if (now < start[i] + duration) running = true
            val o = i * 4
            canvas.drawLine(geometry[o] * k, geometry[o + 1] * k, geometry[o + 2] * k, geometry[o + 3] * k, paint)
        }
        if (running) postInvalidateOnAnimation()
    }

    private companion object { const val TICKS = SpeedWarning.TICKS }
}

/** The speed ring's geometry, in the 84 px box: tick [i] from radius 34 to 39.5 at `180 + 6i` degrees. */
internal object Ticks {
    fun line(i: Int, out: FloatArray, at: Int) {
        val a = Math.toRadians(180.0 + i * 360.0 / SpeedWarning.TICKS)
        out[at] = (42 + 34 * sin(a)).toFloat()
        out[at + 1] = (42 - 34 * cos(a)).toFloat()
        out[at + 2] = (42 + 39.5 * sin(a)).toFloat()
        out[at + 3] = (42 - 39.5 * cos(a)).toFloat()
    }
}
