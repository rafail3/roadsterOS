package ro.rdstr.ui.home.clock

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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import ro.rdstr.car.Bridge
import ro.rdstr.car.Face
import ro.rdstr.car.FaceMode
import ro.rdstr.car.ShellAction
import ro.rdstr.car.ShellState
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.tokens.Base
import ro.rdstr.ui.tokens.Fonts
import ro.rdstr.ui.tokens.Glassy
import ro.rdstr.ui.tokens.Shadow
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The home clock (emulator/components/Clock.tsx, emulator/styles/clock.css): the digital face with its rolling digits,
 * meridiem and date card, or the analog dial. The view is the `.clock-block` box, its bottom padding included; lengths
 * are canvas px drawn at the display density (1 px = 1 dp in our app). A tap on the time or the dial sends
 * [ShellAction.ToggleFace] while the face mode is BOTH.
 *
 * Inputs: [setShell] (face, face mode, 12/24 h), [setTheme], [setFont] and the time source [now]. It ticks on the
 * second while shown and redraws only when the shown time changes or something moves. The glassy date card's drop
 * shadow reaches 30 px past the view, as the CSS box-shadow does: the parent keeps `clipChildren = false`.
 */
@Mirrors("Clock.tsx")
class ClockView(context: Context, private val dispatch: (ShellAction) -> Unit) : View(context) {
    /** The unit's time in epoch ms, read on every tick in the default time zone. */
    var now: () -> Long = System::currentTimeMillis
        set(value) { field = value; tick(false) }

    private val d = resources.displayMetrics.density
    private var t: Base = Base.DARK
    private var light = false
    private var glassy = false
    private var themed = false
    private var family = ""

    private var analog = false
    private var switchable = true
    private var h24 = true

    // the time shown
    private val cal = Calendar.getInstance()
    private var text = ""
    private var meridiem = "AM"
    private var weekday = ""
    private var day = ""
    private var month = ""
    private var second = -1

    private val cells = DigitCells(t.count, (t.count * 0.6f).toLong())
    private val hourHand = Hand(HAND_MS, t.easeOut)
    private val minuteHand = Hand(HAND_MS, t.easeOut)
    private val dial = Dial()
    private val frame = FloatArray(3)

    // geometry, canvas px
    private var ascent = 0.96875f
    private var descent = 0.24121f
    private var clockW = 0f
    private var meridiemW = 0f
    private var contentW = 0f

    private val digit = text(t.sizeClock, 0f)
    private val meridiemPaint = text(32f, 0f)
    private val weekdayPaint = text(15f, 0.08f)
    private val dayPaint = text(60f, -0.03f)
    private val monthPaint = text(15f, 0f)

    // date card, in its own 128 x 128 box
    private val card = RectF(0f, 0f, CARD, CARD)
    private val cardBorder = RectF(0.5f, 0.5f, CARD - 0.5f, CARD - 0.5f)
    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardSheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val insetFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val insetPaths = arrayOfNulls<Path>(4)
    private val insetColors = IntArray(4)
    private var insetCount = 0
    private var dropShadow: Bitmap? = null
    private var dropPad = 0f
    private val dropRect = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // colours that fade with the theme (`.screen.theming`): color, background-color, border-color, the hands' fill
    private val fadeFrom = IntArray(SLOTS)
    private val fadeTo = IntArray(SLOTS)
    private val color = IntArray(SLOTS)
    private var fadeStart = NONE
    private var themingUntil = NONE

    private var pressed = false
    private var running = false
    private val ticker = Runnable { tick(false) }

    init {
        setTheme(light = false, glassy = false)
        setFont("Inter")
        setShell(ShellState())
    }

    /** Face, face mode and 12/24 h from the shell. */
    fun setShell(shell: ShellState) {
        val toAnalog = shell.face == Face.ANALOG
        switchable = shell.faceMode == FaceMode.BOTH
        isClickable = switchable
        contentDescription = when {
            !switchable -> "Clock"
            toAnalog -> "Clock, tap for the digital face"
            else -> "Clock, tap for the analog face"
        }
        val faceChanged = toAnalog != analog
        analog = toAnalog
        val hoursChanged = shell.clock24h != h24
        h24 = shell.clock24h
        // the dial mounts with the analog face: its hands start in place
        tick(placeHands = faceChanged && analog)
        if (faceChanged || hoursChanged) { relayout(force = true); invalidate() }
    }

    /** The effective theme and the design; a theme change fades the colours CSS transitions, the rest switch at once. */
    fun setTheme(light: Boolean, glassy: Boolean) {
        val fade = themed && light != this.light
        themed = true
        this.light = light
        this.glassy = glassy
        t = Base.of(light, glassy)
        dial.setColors(t)
        val g = Glassy.of(light)
        val glassCard = glassy && !light
        fadeTo[INK] = t.ink
        fadeTo[MUTED] = t.inkMuted
        fadeTo[DANGER] = t.danger
        // .date-card, `.screen.light .date-card` (wins over the glassy material by specificity), glassy.css
        fadeTo[CARD_BG] = if (light) LIGHT_CARD else if (glassCard) g.glassyFill else t.glassStrong
        fadeTo[CARD_BORDER] = t.hairline
        fadeTo[HAND] = t.hand
        fadeTo[SECOND] = t.second
        val at = SystemClock.uptimeMillis()
        if (fade) {
            fadeFrom.indices.forEach { fadeFrom[it] = color[it] }
            fadeStart = at
            themingUntil = at + THEMING_MS
        } else {
            fadeTo.copyInto(color)
            fadeStart = NONE
        }
        cardSheen.shader = if (glassCard) sheen(g) else null
        val shadows = when {
            light -> listOf(Shadow(true, 0f, 1f, 0f, 0f, LIGHT_RIM), Shadow(false, 0f, 1f, 2f, 0f, LIGHT_DROP))
            glassCard -> g.glassyShadow
            else -> listOf(Shadow(true, 0f, 1f, 0f, 0f, DARK_RIM))
        }
        insetCount = 0
        for (s in shadows.filter { it.inset }.asReversed()) {
            insetPaths[insetCount] = insetPath(s)
            insetColors[insetCount++] = s.color
        }
        dropShadow?.recycle()
        dropShadow = shadows.firstOrNull { !it.inset }?.let(::bake)
        invalidate()
    }

    /** The screen font by its CSS family name ("Inter", "Geist"). */
    fun setFont(family: String) {
        if (family == this.family) return
        this.family = family
        val tf = resources.getFont(Fonts.byFamily[family] ?: Fonts.INTER)
        val probe = Paint().apply { typeface = tf; textSize = 1000f }
        val fm = probe.fontMetrics
        ascent = -fm.ascent / 1000f
        descent = fm.descent / 1000f
        val x = Rect()
        probe.getTextBounds("x", 0, 1, x)
        weight(digit, tf, 600)
        weight(meridiemPaint, tf, 600)
        weight(weekdayPaint, tf, 600)
        weight(dayPaint, tf, 600)
        weight(monthPaint, tf, 500)
        dial.setFont(tf, -x.top / 1000f)
        relayout(force = true)
        invalidate()
    }

    // ---- time

    private fun motion() = ValueAnimator.areAnimatorsEnabled()

    private fun tick(placeHands: Boolean) {
        val at = SystemClock.uptimeMillis()
        cal.timeZone = TimeZone.getDefault()
        cal.timeInMillis = now()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        var changed = false
        val next = Bridge.clockText(h, m, h24)
        if (next != text) {
            // the digital face keeps counting while hidden, as its cells stay mounted
            cells.set(next, at, motion())
            text = next
            changed = !analog
        }
        val mer = Bridge.meridiem(h)
        if (mer != meridiem) { meridiem = mer; changed = changed || (!analog && !h24) }
        val wd = WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val dd = cal.get(Calendar.DAY_OF_MONTH).toString()
        val mo = MONTHS[cal.get(Calendar.MONTH)]
        if (wd != weekday || dd != day || mo != month) { weekday = wd; day = dd; month = mo; changed = changed || !analog }
        relayout(force = false)
        if (analog) {
            // `.screen.theming *` replaces the hands' transition for its 750 ms
            val ease = !placeHands && motion() && at >= themingUntil
            hourHand.set(HandAngles.hour(h, m, s), at, ease)
            minuteHand.set(HandAngles.minute(m, s), at, ease)
            if (s != second || placeHands) changed = true
        }
        second = s
        if (changed) invalidate()
        removeCallbacks(ticker)
        if (running) postDelayed(ticker, 1000 - Math.floorMod(now(), 1000L))
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        running = isVisible
        if (running) tick(false) else removeCallbacks(ticker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        removeCallbacks(ticker)
    }

    // ---- layout

    private fun relayout(force: Boolean) {
        var w = 0f
        for (ch in text) w += cellWidth(ch)
        clockW = w
        meridiemW = if (h24) 0f else 0.2f * 32f + meridiemPaint.measureText(meridiem)
        val content = if (analog) ANALOG else clockW + meridiemW + t.space6 + CARD
        if (force || content != contentW) { contentW = content; requestLayout() }
    }

    private fun cellWidth(ch: Char) = if (ch == ':') 0.32f * t.sizeClock else 0.62f * t.sizeClock

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // .analog sits 8 px up (margin-top: -8px); .clock-block's bottom padding follows the face
        val h = if (analog) ANALOG - 8f + t.space4 else t.sizeClock + t.space7
        setMeasuredDimension(resolveSize(ceil(contentW * d).toInt(), widthMeasureSpec), resolveSize(ceil(h * d).toInt(), heightMeasureSpec))
    }

    // ---- drawing

    override fun onDraw(canvas: Canvas) {
        val at = SystemClock.uptimeMillis()
        var moving = fade(at)
        canvas.save()
        canvas.scale(d, d)
        if (analog) {
            canvas.translate(0f, -8f)
            canvas.scale(ANALOG / 480f, ANALOG / 480f)
            dial.draw(canvas, hourHand.at(at), minuteHand.at(at), HandAngles.second(second), color[HAND], color[SECOND])
            moving = moving || hourHand.moving(at) || minuteHand.moving(at)
        } else {
            drawDigits(canvas, at)
            drawCard(canvas, clockW + meridiemW + t.space6)
            moving = moving || cells.moving(at)
        }
        canvas.restore()
        if (moving) postInvalidateOnAnimation()
    }

    private fun fade(at: Long): Boolean {
        if (fadeStart == NONE) return false
        val p = (at - fadeStart).toFloat() / t.theme
        if (p >= 1f) { fadeTo.copyInto(color); fadeStart = NONE; return false }
        val e = t.easeOut.at(p)
        for (i in 0 until SLOTS) color[i] = mixColor(fadeFrom[i], fadeTo[i], e)
        return true
    }

    private fun drawDigits(c: Canvas, at: Long) {
        val size = t.sizeClock
        val base = baseline(size, size, ascent, descent)
        val spacing = -0.04f * size
        var x = 0f
        for (i in 0 until cells.length) {
            val cell = cells.length - 1 - i
            val w = cellWidth(cells.char[cell])
            c.save()
            c.clipRect(x, 0f, x + w, size)
            val start = cells.inStart[cell]
            if (start != NONE && at - start < t.count) Roll.glyphIn((at - start).toFloat() / t.count, t.easeRoll, frame) else rest()
            glyph(c, cells.char, cell, x, w, base, spacing)
            // the leaving glyphs are absolutely placed, so they paint over the one coming in
            for (j in 0 until DigitCells.OUTS) {
                val out = cells.outStart[cell][j]
                val outMs = t.count * 0.6f
                if (out == NONE || at - out >= outMs) continue
                Roll.glyphOut((at - out) / outMs, frame)
                glyph(c, cells.outChar[cell], j, x, w, base, spacing)
            }
            c.restore()
            x += w
        }
        if (!h24) {
            // align-self: flex-end with margin-bottom 0.9em: the label's line box ends 28.8 px above the digits' bottom
            meridiemPaint.color = color[MUTED]
            val base32 = size - 0.9f * 32f - (descent * 32f).roundToInt()
            c.drawText(meridiem, clockW + 0.2f * 32f, base32, meridiemPaint)
        }
    }

    private fun rest() { frame[0] = 0f; frame[1] = 1f; frame[2] = 1f }

    /** One glyph of [chars] at [index] in the cell at [x] of width [w], moved by [frame] (`translateY(%) scale()` about the box centre, opacity). */
    private fun glyph(c: Canvas, chars: CharArray, index: Int, x: Float, w: Float, base: Float, spacing: Float) {
        if (frame[2] <= 0f) return
        val size = t.sizeClock
        digit.color = color[INK]
        digit.alpha = ((color[INK] ushr 24) * frame[2]).roundToInt()
        // the glyph's box is its advance plus the letter spacing Blink adds after it, centred in the cell
        val left = x + (w - (digit.measureText(chars, index, 1) + spacing)) / 2
        val moved = frame[0] != 0f || frame[1] != 1f
        if (moved) {
            val cx = x + w / 2
            c.save()
            c.translate(cx, size / 2 + frame[0] * size)
            c.scale(frame[1], frame[1])
            c.translate(-cx, -size / 2)
        }
        c.drawText(chars, index, 1, left, base, digit)
        if (moved) c.restore()
    }

    private fun drawCard(c: Canvas, x: Float) {
        c.save()
        c.translate(x, 0f)
        dropShadow?.let {
            dropRect.set(-dropPad, -dropPad, CARD + dropPad, CARD + dropPad)
            c.drawBitmap(it, null, dropRect, bitmapPaint)
        }
        cardFill.color = color[CARD_BG]
        c.drawRoundRect(card, CARD_R, CARD_R, cardFill)
        if (cardSheen.shader != null) c.drawRoundRect(card, CARD_R, CARD_R, cardSheen)
        for (i in 0 until insetCount) {
            insetFill.color = insetColors[i]
            c.drawPath(insetPaths[i]!!, insetFill)
        }
        cardStroke.color = color[CARD_BORDER]
        c.drawRoundRect(cardBorder, CARD_R - 0.5f, CARD_R - 0.5f, cardStroke)
        // a column centred in the 102 px content box: 15 + 4 + 60 + 6 + 15 = 100
        val top = 1f + t.space3 + (CARD - 2f - 2 * t.space3 - 100f) / 2
        weekdayPaint.color = color[DANGER]
        c.drawText(weekday, centred(weekdayPaint, weekday, CARD / 2), top + baseline(15f, 15f, ascent, descent), weekdayPaint)
        dayPaint.color = color[INK]
        c.drawText(day, centred(dayPaint, day, CARD / 2), top + 19f + baseline(60f, 60f, ascent, descent), dayPaint)
        monthPaint.color = color[MUTED]
        c.drawText(month, centred(monthPaint, month, CARD / 2), top + 85f + baseline(15f, 15f, ascent, descent), monthPaint)
        c.restore()
    }

    // ---- date card material, built when the theme changes

    /** `linear-gradient(angle, …)` over the padding box. */
    private fun sheen(g: Glassy): Shader {
        val a = Math.toRadians(g.glassySheen.angle.toDouble())
        val box = CARD - 2f
        val half = (abs(box * sin(a)) + abs(box * cos(a))).toFloat() / 2
        val dx = sin(a).toFloat() * half
        val dy = -cos(a).toFloat() * half
        val mid = CARD / 2
        return LinearGradient(mid - dx, mid - dy, mid + dx, mid + dy, g.glassySheen.colors, g.glassySheen.at, Shader.TileMode.CLAMP)
    }

    /** An inset shadow without blur: the padding box less itself shrunk by the spread and moved by the offset. */
    private fun insetPath(s: Shadow): Path {
        val r = CARD_R - 1f
        val outer = Path().apply { addRoundRect(RectF(1f, 1f, CARD - 1f, CARD - 1f), r, r, Path.Direction.CW) }
        val hole = RectF(1f + s.spread + s.x, 1f + s.spread + s.y, CARD - 1f - s.spread + s.x, CARD - 1f - s.spread + s.y)
        val hr = max(0f, r - s.spread)
        outer.op(Path().apply { addRoundRect(hole, hr, hr, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        return outer
    }

    /** An outer box-shadow baked once into a bitmap, cut out under the card as CSS paints it. */
    private fun bake(s: Shadow): Bitmap {
        dropPad = s.blur + s.spread + max(abs(s.x), abs(s.y))
        val size = CARD + 2 * dropPad
        val px = ceil(size * d).toInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(px / size, px / size)
        // blur radius B is a Gaussian of sigma B / 2; Android's radius r maps to sigma 0.57735 r + 0.5
        val radius = max(0.01f, (s.blur / 2 - 0.5f) / 0.57735f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.color; maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }
        val r = CARD_R + s.spread
        c.drawRoundRect(dropPad + s.x - s.spread, dropPad + s.y - s.spread, dropPad + CARD + s.x + s.spread, dropPad + CARD + s.y + s.spread, r, r, p)
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        c.drawRoundRect(dropPad, dropPad, dropPad + CARD, dropPad + CARD, CARD_R, CARD_R, clear)
        return bmp
    }

    // ---- touch: only the time (the digits' button) or the round dial

    private fun hit(px: Float, py: Float): Boolean {
        val x = px / d
        val y = py / d
        if (analog) return hypot(x - ANALOG / 2, y - (ANALOG / 2 - 8f)) <= ANALOG / 2
        val w = clockW + meridiemW
        val h = t.sizeClock
        if (x < 0 || y < 0 || x > w || y > h) return false
        // border-radius: var(--radius) clips the hit area at the corners
        val r = t.radius
        val cx = x.coerceIn(r, w - r)
        val cy = y.coerceIn(r, h - r)
        return hypot(x - cx, y - cy) <= r
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!switchable) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressed = hit(event.x, event.y)
            MotionEvent.ACTION_UP -> { if (pressed && hit(event.x, event.y)) performClick(); pressed = false; return true }
            MotionEvent.ACTION_CANCEL -> pressed = false
        }
        return pressed
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (switchable) dispatch(ShellAction.ToggleFace)
        return true
    }

    private fun text(size: Float, spacing: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = size
        letterSpacing = spacing
        fontFeatureSettings = FEATURES
    }

    private fun weight(p: Paint, tf: Typeface, w: Int) {
        p.typeface = tf
        p.fontVariationSettings = "'wght' $w"
        ro.rdstr.ui.kit.Type.linear(p)
    }

    private companion object {
        const val HAND_MS = 600L
        /** Screen.tsx THEME_FADE_MS: how long `.screen.theming` stays on. */
        const val THEMING_MS = 750L
        const val CARD = 128f
        const val CARD_R = 28f
        const val ANALOG = 440f
        const val LIGHT_CARD = 0xC7FFFFFF.toInt()
        const val LIGHT_RIM = 0xE6FFFFFF.toInt()
        const val LIGHT_DROP = 0x0F141C26
        const val DARK_RIM = 0x14FFFFFF
        const val INK = 0
        const val MUTED = 1
        const val DANGER = 2
        const val CARD_BG = 3
        const val CARD_BORDER = 4
        const val HAND = 5
        const val SECOND = 6
        const val SLOTS = 7
    }
}
