package ro.rdstr.ui.sound

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import ro.rdstr.car.Sound
import ro.rdstr.ui.R
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.Gap
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.KitView
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.kit.px
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** [css] canvas px as whole device px. */
internal fun View.dpi(css: Float): Int = px(css).roundToInt()

internal fun gap(view: View, css: Float) = Gap(view.dpi(css))

/** settings.css `rgba(118, 118, 128, 0.24)`: the ground of `.set-seg` and `.set-stepper`. */
internal const val CONTROL_TRACK = 0x3D767680

/**
 * A [CssBox] as a view's background, its fill following the pressed and enabled states over `--fast`, as a CSS
 * `background` transition with an `:active` rule does. [rest] and [pressed] are the fills; [glass] keeps the Glassy
 * material (sheen, shadow, frost) under them.
 */
internal class BoxDrawable(private val view: View, val box: CssBox) : Drawable() {
    var rest = 0
    var pressed = 0
    private var down = false
    private val fill = ColorTransition(0)

    /** New fills (a theme or a state change of the caller's), shown at once. */
    fun colors(rest: Int, pressed: Int) { this.rest = rest; this.pressed = pressed; fill.snap(if (down) pressed else rest); invalidateSelf() }

    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean {
        val now = android.R.attr.state_pressed in state && android.R.attr.state_enabled in state
        if (now == down) return false
        down = now
        fill.to(if (down) pressed else rest, Motion.now(), UiTheme.duration(UiTheme.base.fast), Easing.EASE)
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val t = Motion.now()
        box.fill = fill.value(t)
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        box.draw(canvas, view, bounds.width().toFloat(), bounds.height().toFloat())
        canvas.restore()
        if (fill.running(t)) view.postInvalidateOnAnimation()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * settings.css `.set-inline`: a text button inside a row or a readout, `--set-blue` (`rgb(var(--on) / .3)` disabled),
 * pressed `rgb(var(--on) / .08)`; on Glassy the glass material, `--glassy-fill-strong` pressed.
 */
internal fun inlineButton(context: Context, label: String): KitText = KitText(context, TextStyle(17f, 600)).apply {
    uiKey = "set-inline"
    text = label
    gravity = Gravity.CENTER
    minHeight = dpi(48f)
    setPadding(dpi(16f), 0, dpi(16f), 0)
    val bg = BoxDrawable(this, CssBox().apply { radius = 12f })
    background = bg
    isClickable = true
    val theme = {
        val on = UiTheme.base.on
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(CssColor.withAlpha(on, 0.3f), UiTheme.settings.setBlue)))
        if (UiTheme.glassy) { val g = UiTheme.glass; bg.box.glassy(g); bg.colors(g.glassyFill, g.glassyFillStrong) }
        else { bg.box.solid(0); bg.colors(0, CssColor.withAlpha(on, 0.08f)) }
    }
    UiTheme.link(this) { theme() }
    theme()
}

/** settings.css `.set-stepper` button: 56 x 48, the icon 22 px in `--ink`, `rgb(var(--on) / .3)` disabled, pressed `rgb(var(--on) / .16)`. */
@SuppressLint("ViewConstructor")
internal class StepButton(context: Context, icon: Int, key: String) : FrameLayout(context) {
    private val glyph = IconView(context, icon, 22f).apply { uiKey = key }
    private val bg = BoxDrawable(this, CssBox().apply { radius = 10f })

    init {
        uiKey = "button"
        background = bg
        isClickable = true
        addView(glyph, LayoutParams(WRAP, WRAP, Gravity.CENTER))
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        bg.colors(0, CssColor.withAlpha(UiTheme.base.on, 0.16f))
        glyph.color = if (isEnabled) UiTheme.settings.ink else CssColor.withAlpha(UiTheme.base.on, 0.3f)
    }

    override fun setEnabled(enabled: Boolean) { if (enabled != isEnabled) { super.setEnabled(enabled); theme() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(MeasureSpec.makeMeasureSpec(dpi(56f), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dpi(48f), MeasureSpec.EXACTLY))
}

/**
 * SoundApp.tsx `Steps`: − value + over a fixed list of values (`.set-stepper` with `.snd-step-value`, 104 px at
 * least, 18 px). A stored value off the list sits at its nearest neighbour. On Glassy the stepper is glass.
 */
@SuppressLint("ViewConstructor")
class Stepper(context: Context, private val values: List<Int>, private val format: (Int) -> String) : LinearLayout(context) {
    var onChange: ((Int) -> Unit)? = null
    private var index = 0
    private val minus = StepButton(context, Icons.MINUS, "icon.i-minus")
    private val plus = StepButton(context, Icons.PLUS, "icon.i-plus")
    private val output = KitText(context, TextStyle(18f, 600)).apply { uiKey = "snd-step-value"; gravity = Gravity.CENTER; minWidth = dpi(104f) }
    private val box = CssBox().apply { radius = 12f }

    init {
        uiKey = "set-stepper"
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = dpi(3f)
        setPadding(p, p, p, p)
        dividerDrawable = gap(this, 4f)
        showDividers = SHOW_DIVIDER_MIDDLE
        setWillNotDraw(false)
        addView(minus); addView(output); addView(plus)
        minus.setOnClickListener { if (index > 0) onChange?.invoke(values[index - 1]) }
        plus.setOnClickListener { if (index < values.size - 1) onChange?.invoke(values[index + 1]) }
        UiTheme.link(this) { theme() }
        theme()
    }

    fun set(value: Int) {
        index = SoundModel.nearest(values, value)
        output.text = format(value)
        minus.isEnabled = index > 0
        plus.isEnabled = index < values.size - 1
    }

    private fun theme() {
        if (UiTheme.glassy) box.glassy(UiTheme.glass) else box.solid(CONTROL_TRACK)
        output.setTextColor(UiTheme.settings.ink)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
}

/**
 * SoundApp.tsx `Curve` (`svg.snd-curve`, viewBox 1000 x 160 stretched over 88 px): the shown page of bands lit
 * (`rgb(var(--on) / .05)`), the flat line (`--set-line`, 2 px), the lift or cut filled (`--set-blue` at 18 %) and the
 * curve (`--set-blue`, 3 px). The line moves to new values over 160 ms on `--seamless` (`transition: d`); the fill
 * does not move.
 */
class EqCurve(context: Context) : KitView(context) {
    private val n = Sound.BANDS.size
    private val from = FloatArray(2 * n)
    private val target = FloatArray(2 * n)
    private val shown = FloatArray(2 * n)
    private val morph = Transition(1f)
    private val line = Path()
    private val fill = Path()
    private var page = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var ready = false

    init { uiKey = "snd-curve"; onTheme() }

    override fun onTheme() = Unit

    fun set(values: List<Int>, page: Int) {
        this.page = page
        val now = Motion.now()
        if (ready) currentInto(from, now)
        SoundModel.curvePoints(values, target)
        if (!ready || !isAttachedToWindow) { morph.snap(1f); ready = true } else { morph.snap(0f); morph.to(1f, now, UiTheme.duration(160), Motion.seamless) }
        fillPath()
        invalidate()
    }

    private fun currentInto(out: FloatArray, now: Long) {
        val k = morph.value(now)
        for (i in out.indices) out[i] = from[i] + (target[i] - from[i]) * k
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fillPath()

    private fun sx() = width / SoundModel.CURVE_W
    private fun sy() = height / SoundModel.CURVE_H

    private fun build(path: Path, p: FloatArray) {
        val sx = sx(); val sy = sy()
        path.rewind()
        path.moveTo(0f, p[1] * sy); path.lineTo(p[0] * sx, p[1] * sy)
        for (i in 0 until n - 1) {
            val x0 = p[2 * i]; val y0 = p[2 * i + 1]; val x1 = p[2 * i + 2]; val y1 = p[2 * i + 3]
            val cx = (x0 + x1) / 2
            path.cubicTo(cx * sx, y0 * sy, cx * sx, y1 * sy, x1 * sx, y1 * sy)
        }
        path.lineTo(SoundModel.CURVE_W * sx, p[2 * n - 1] * sy)
    }

    private fun fillPath() {
        build(fill, target)
        fill.lineTo(width.toFloat(), SoundModel.CURVE_H / 2 * sy()); fill.lineTo(0f, SoundModel.CURVE_H / 2 * sy()); fill.close()
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val st = UiTheme.settings
        val w = width.toFloat(); val h = height.toFloat()
        if (page >= 0) {
            val span = w / ((n + SoundModel.BANDS_PER_PAGE - 1) / SoundModel.BANDS_PER_PAGE)
            paint.color = CssColor.withAlpha(UiTheme.base.on, 0.05f)
            canvas.drawRect(page * span, 0f, (page + 1) * span, h, paint)
        }
        stroke.color = st.setLine; stroke.strokeWidth = px(2f)
        canvas.drawLine(0f, h / 2, w, h / 2, stroke)
        paint.color = CssColor.fade(st.setBlue, 0.18f)
        canvas.drawPath(fill, paint)
        currentInto(shown, now)
        build(line, shown)
        stroke.color = st.setBlue; stroke.strokeWidth = px(3f)
        canvas.drawPath(line, stroke)
        if (morph.running(now)) postInvalidateOnAnimation()
    }
}

/**
 * SoundApp.tsx `Response` (`svg.snd-curve.is-small`, 72 px): a pair of speakers' response on a log axis from 20 Hz to
 * 20 kHz, bent down at the high-pass and the low-pass. Line and fill as [EqCurve]; the line moves over 160 ms.
 */
class ResponseCurve(context: Context) : KitView(context) {
    private val lo = Transition(-400f)
    private val hi = Transition(1400f)
    private var loTo = -400f
    private var hiTo = 1400f
    private var ready = false
    private val line = Path()
    private val fill = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init { uiKey = "snd-curve"; onTheme() }

    override fun onTheme() = Unit

    fun set(f: ro.rdstr.car.SpeakerFilter) {
        loTo = SoundModel.responseLo(f.highPass, f.highPassHz)
        hiTo = SoundModel.responseHi(f.lowPass, f.lowPassHz)
        val now = Motion.now()
        if (!ready || !isAttachedToWindow) { lo.snap(loTo); hi.snap(hiTo); ready = true }
        else { lo.to(loTo, now, UiTheme.duration(160), Motion.seamless); hi.to(hiTo, now, UiTheme.duration(160), Motion.seamless) }
        invalidate()
    }

    private fun build(path: Path, lo: Float, hi: Float) {
        val sx = width / SoundModel.CURVE_W; val sy = height / SoundModel.CURVE_H
        path.rewind()
        path.moveTo((lo - 140) * sx, 150 * sy)
        path.cubicTo((lo - 50) * sx, 150 * sy, (lo - 50) * sx, 30 * sy, (lo + 40) * sx, 30 * sy)
        path.lineTo((hi - 40) * sx, 30 * sy)
        path.cubicTo((hi + 50) * sx, 30 * sy, (hi + 50) * sx, 150 * sy, (hi + 140) * sx, 150 * sy)
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val st = UiTheme.settings
        val sx = width / SoundModel.CURVE_W; val sy = height / SoundModel.CURVE_H
        build(fill, loTo, hiTo)
        fill.lineTo(1400 * sx, 160 * sy); fill.lineTo(-400 * sx, 160 * sy); fill.close()
        paint.color = CssColor.fade(st.setBlue, 0.18f)
        canvas.drawPath(fill, paint)
        build(line, lo.value(now), hi.value(now))
        stroke.color = st.setBlue; stroke.strokeWidth = px(3f)
        canvas.drawPath(line, stroke)
        if (lo.running(now) || hi.running(now)) postInvalidateOnAnimation()
    }
}

/**
 * SoundApp.tsx `BandSlider`'s track (`.snd-band-track`, 56 px wide): the 6 px rail (`rgba(118, 118, 128, .32)`), the
 * blue fill from the centre to the knob, the white 40 x 24 knob and the zero tick over them (`::after`,
 * `rgb(var(--on) / .3)`). A drag sets the band from the finger's height on the track; held, the knob grows to 1.12
 * over 160 ms on `--spring-glyph`.
 */
@SuppressLint("ViewConstructor")
class BandTrack(context: Context) : FrameLayout(context) {
    var onChange: ((Int) -> Unit)? = null
    private var value = 0
    private var dragging = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val fill = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            paint.color = UiTheme.settings.setBlue
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, px(3f), px(3f), paint)
        }
    }.apply { uiKey = "snd-band-fill" }
    private val knobBox = CssBox().apply { radius = 12f; fill = -1; shadows = listOf(Shadow(false, 0f, 2f, 6f, 0f, 0x66000000)) }
    private val knob = object : View(context) {
        override fun onDraw(canvas: Canvas) = knobBox.draw(canvas, this, width.toFloat(), height.toFloat())
    }.apply { uiKey = "snd-band-knob" }

    init {
        uiKey = "snd-band-track"
        clipChildren = false
        setWillNotDraw(false)
        addView(fill); addView(knob)
        UiTheme.link(this) { invalidate(); fill.invalidate() }
    }

    fun set(value: Int) { if (value != this.value) { this.value = value; place() } }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = place()

    private fun place() {
        val w = width.toFloat(); val h = height.toFloat()
        if (h <= 0f) return
        val pos = SoundModel.bandPos(value)
        val cx = w / 2
        // the fill from the centre line to the knob; the knob's middle at the value
        val fTop = (1 - max(pos, 0.5f)) * h
        val fBottom = h - min(pos, 0.5f) * h
        fill.layout((cx - px(3f)).roundToInt(), fTop.roundToInt(), (cx + px(3f)).roundToInt(), fBottom.roundToInt())
        val kBottom = h - pos * h + px(12f)
        knob.layout((cx - px(20f)).roundToInt(), (kBottom - px(24f)).roundToInt(), (cx + px(20f)).roundToInt(), kBottom.roundToInt())
        knob.pivotX = knob.width / 2f; knob.pivotY = knob.height / 2f
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = RAIL
        val cx = width / 2f
        rect.set(cx - px(3f), 0f, cx + px(3f), height.toFloat())
        canvas.drawRoundRect(rect, px(3f), px(3f), paint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        paint.color = CssColor.withAlpha(UiTheme.base.on, 0.3f)
        val cx = width / 2f; val cy = height / 2f
        canvas.drawRect(cx - px(10f), cy - px(1f), cx + px(10f), cy + px(1f), paint)
    }

    private fun at(y: Float) = SoundModel.bandAt((height - y) / height)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                hold(true)
                at(e.y).let { if (it != value) onChange?.invoke(it) }
            }
            MotionEvent.ACTION_MOVE -> if (dragging) at(e.y).let { if (it != value) onChange?.invoke(it) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> hold(false)
        }
        return true
    }

    private fun hold(on: Boolean) {
        dragging = on
        val s = if (on) 1.12f else 1f
        knob.animate().scaleX(s).scaleY(s).setDuration(UiTheme.duration(160)).setInterpolator(Motion.interpolator(Motion.springGlyph)).start()
    }

    private companion object { const val RAIL = 0x52767680 }
}

/**
 * SoundApp.tsx `BandSlider` (`.snd-band`): the value above (16 px, 600, `--ink`; `--ink-muted` when flat), the track
 * (150 px on the fitted equalizer page) and the band's name (15 px, 500, `--ink-muted`), 10 px apart.
 */
@SuppressLint("ViewConstructor")
class BandSlider(context: Context) : LinearLayout(context) {
    private val valueText = KitText(context, TextStyle(16f, 600)).apply { uiKey = "snd-band-value" }
    val track = BandTrack(context)
    private val label = KitText(context, TextStyle(15f, 500)).apply { uiKey = "snd-band-label" }
    private var value = 0

    init {
        uiKey = "snd-band"
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        dividerDrawable = gap(this, 10f)
        showDividers = SHOW_DIVIDER_MIDDLE
        addView(valueText)
        addView(track, LayoutParams(dpi(56f), dpi(150f)))
        addView(label)
        UiTheme.link(this) { theme() }
    }

    fun set(name: String, value: Int) {
        this.value = value
        label.text = name
        valueText.text = SoundModel.signed(value)
        track.set(value)
        theme()
    }

    private fun theme() {
        val st = UiTheme.settings
        valueText.setTextColor(if (value == 0) st.inkMuted else st.ink)
        label.setTextColor(st.inkMuted)
    }
}

/**
 * CarTop.tsx `CarTop` (`svg.car-top`, viewBox 459 x 800 fitted into its box, xMidYMid meet): the car picture, the
 * four seats drawn faintly on the roof (lit `rgba(10, 132, 255, .78)` where the sound is focused, 200 ms on
 * `--seamless`), and a speaker in each door at `0.4 + 0.6 × level` with one or two waves (`--set-blue`, 3.5) as loud
 * as it plays. The pictures are scaled once per size; drawing allocates nothing.
 */
class CarTopView(context: Context) : KitView(context) {
    private val lit = BooleanArray(4)
    private val seatFill = Array(4) { ColorTransition(SEAT_FILL) }
    private val seatStroke = Array(4) { ColorTransition(SEAT_STROKE) }
    private val levels = FloatArray(4) { 1f }
    private val group = Array(4) { Transition(1f) }
    private val waveA = Array(4) { Transition(1f) }
    private val waveB = Array(4) { Transition(1f) }
    private var ready = false
    private var car: Bitmap? = null
    private var speaker: Bitmap? = null
    private var k = 1f
    private var ox = 0f
    private var oy = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    init { uiKey = "car-top"; onTheme() }

    override fun onTheme() = Unit

    /** Which seats are lit and how loud each speaker plays (FL, FR, RL, RR). */
    fun set(lit: BooleanArray, levels: FloatArray) {
        val now = Motion.now()
        val animate = ready && isAttachedToWindow
        for (i in 0 until 4) {
            val f = if (lit[i]) LIT_FILL else SEAT_FILL
            val s = if (lit[i]) LIT_STROKE else SEAT_STROKE
            if (animate) { seatFill[i].to(f, now, UiTheme.duration(200), Motion.seamless); seatStroke[i].to(s, now, UiTheme.duration(200), Motion.seamless) }
            else { seatFill[i].snap(f); seatStroke[i].snap(s) }
            this.lit[i] = lit[i]
            val was = this.levels[i]
            val l = levels[i]
            this.levels[i] = l
            if (animate) group[i].to(0.4f + 0.6f * l, now, UiTheme.duration(200), Motion.seamless) else group[i].snap(0.4f + 0.6f * l)
            // a wave that appears is drawn at once (a new element has no transition); one that stays fades
            wave(waveA[i], l > 0.02f, was > 0.02f, l, animate, now)
            wave(waveB[i], l > 0.5f, was > 0.5f, (l - 0.5f) * 2, animate, now)
        }
        ready = true
        invalidate()
    }

    private fun wave(t: Transition, on: Boolean, wasOn: Boolean, alpha: Float, animate: Boolean, now: Long) {
        if (!on) t.snap(0f) else if (animate && wasOn) t.to(alpha, now, UiTheme.duration(200), Motion.seamless) else t.snap(alpha)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        k = min(w / SoundModel.CAR_W, h / SoundModel.CAR_H)
        ox = (w - SoundModel.CAR_W * k) / 2
        oy = (h - SoundModel.CAR_H * k) / 2
        car = Bitmap.createScaledBitmap(pictures(resources).first, (SoundModel.PICTURE_W * k).roundToInt(), (SoundModel.CAR_H * k).roundToInt(), true)
        speaker = Bitmap.createScaledBitmap(pictures(resources).second, (SoundModel.SPEAKER_W * k).roundToInt(), (SoundModel.SPEAKER_H * k).roundToInt(), true)
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        var running = false
        paint.alpha = 255
        car?.let { b ->
            rect.set(ox + SoundModel.PICTURE_X * k, oy, ox + (SoundModel.PICTURE_X + SoundModel.PICTURE_W) * k, oy + SoundModel.CAR_H * k)
            canvas.drawBitmap(b, null, rect, paint)
        }
        canvas.save()
        canvas.translate(ox, oy); canvas.scale(k, k)
        for (i in 0 until 4) {
            val s = SoundModel.CAR_SEATS[i]
            val x = s[0]; val y = s[1]; val w = s[2]; val h = s[3]
            val fill = seatFill[i].value(now); val stroke = seatStroke[i].value(now)
            running = running || seatFill[i].running(now)
            seatRect(canvas, x, y, x + w, y + h * 0.62f, 14f, fill, stroke)
            seatRect(canvas, x + 4, y + h * 0.66f, x + w - 4, y + h * 0.88f, 8f, fill, stroke)
            seatRect(canvas, x + w / 2 - 12, y + h * 0.9f, x + w / 2 + 12, y + h, 5f, fill, stroke)
        }
        val blue = UiTheme.settings.setBlue
        for (i in 0 until 4) {
            val cx = SoundModel.CAR_SPEAKERS[i][0]; val cy = SoundModel.CAR_SPEAKERS[i][1]
            val side = if (i % 2 == 1) -1f else 1f
            val g = group[i].value(now)
            running = running || group[i].running(now) || waveA[i].running(now) || waveB[i].running(now)
            speaker?.let { b ->
                canvas.save()
                canvas.translate(cx, cy); canvas.scale(side, 1f)
                rect.set(-SoundModel.SPEAKER_W / 2, -SoundModel.SPEAKER_H / 2, SoundModel.SPEAKER_W / 2, SoundModel.SPEAKER_H / 2)
                paint.style = Paint.Style.FILL; paint.alpha = (g * 255).roundToInt()
                canvas.drawBitmap(b, null, rect, paint)
                canvas.restore()
            }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.5f; paint.strokeCap = Paint.Cap.ROUND
            arc(canvas, cx, cy, 34f, side, CssColor.fade(blue, g * waveA[i].value(now)))
            arc(canvas, cx, cy, 43f, side, CssColor.fade(blue, g * waveB[i].value(now)))
            paint.style = Paint.Style.FILL; paint.strokeCap = Paint.Cap.BUTT
        }
        canvas.restore()
        paint.alpha = 255
        if (running) postInvalidateOnAnimation()
    }

    private fun seatRect(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, rx: Float, fill: Int, stroke: Int) {
        rect.set(l, t, r, b)
        paint.style = Paint.Style.FILL; paint.color = fill
        canvas.drawRoundRect(rect, rx, rx, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; paint.color = stroke
        canvas.drawRoundRect(rect, rx, rx, paint)
        paint.style = Paint.Style.FILL
    }

    /** `wave(r)`: an arc of ±π/4.2 around the speaker, opening towards the cabin. */
    private fun arc(canvas: Canvas, cx: Float, cy: Float, r: Float, side: Float, color: Int) {
        if (CssColor.alpha(color) == 0) return
        paint.color = color
        rect.set(cx - r, cy - r, cx + r, cy + r)
        val start = if (side > 0) -SoundModel.WAVE_HALF_DEG else 180f - SoundModel.WAVE_HALF_DEG
        canvas.drawArc(rect, start, 2 * SoundModel.WAVE_HALF_DEG, false, paint)
    }

    companion object {
        // sound.css .car-seat rect, the same in light and dark
        private const val SEAT_FILL = 0x121E2228
        private const val SEAT_STROKE = 0x381E2228
        private const val LIT_FILL = 0xC70A84FF.toInt()
        private const val LIT_STROKE = 0xFF0A84FF.toInt()

        private var decoded: Pair<Bitmap, Bitmap>? = null

        /** The car and speaker pictures, decoded once for every view. */
        private fun pictures(res: android.content.res.Resources): Pair<Bitmap, Bitmap> = decoded ?: Pair(
            BitmapFactory.decodeResource(res, R.drawable.rdstr_car_top),
            BitmapFactory.decodeResource(res, R.drawable.rdstr_speaker),
        ).also { decoded = it }
    }
}

/** CSS `flex-wrap: wrap` with a [gap] both ways, as wide as its content up to what it is given (`.snd-readout-actions`). */
internal class WrapRow(context: Context, private val gapCss: Float) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec)
        val g = dpi(gapCss)
        var x = 0; var y = 0; var line = 0; var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > avail) { y += line + g; x = 0; line = 0 }
            x += c.measuredWidth
            widest = max(widest, x)
            x += g
            line = max(line, c.measuredHeight)
        }
        val maxContent = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }.let { kids -> kids.sumOf { it.measuredWidth } + g * max(0, kids.size - 1) }
        setMeasuredDimension(min(maxContent, avail).coerceAtLeast(widest.coerceAtMost(avail)), resolveSize(y + line, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val g = dpi(gapCss)
        var x = 0; var y = 0; var line = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > width) { y += line + g; x = 0; line = 0 }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + g
            line = max(line, c.measuredHeight)
        }
    }
}
