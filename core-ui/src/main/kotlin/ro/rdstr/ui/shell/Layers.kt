package ro.rdstr.ui.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import ro.rdstr.car.Face
import ro.rdstr.car.Settings
import ro.rdstr.ui.kit.Backdrop
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBezier
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.CssStops
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.KitView
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.uiKey
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * `.screen`'s ground and `div.ambient`: `--canvas` under two soft glows (600 x 420 at 18 % 40 % and 520 x 380 at
 * 86 % 78 %, wider by day), fading out at 70 % of their radii. While Bluetooth music with a cover plays, the glows take
 * the cover's colours ([glow]), changing over 800 ms. On Glassy this is what the frost blurs: it is captured again
 * (Backdrop) each time it settles.
 */
class Ambient(context: Context) : KitView(context) {
    private val a = ColorTransition(0)
    private val b = ColorTransition(0)
    private val paintA = Paint()
    private val paintB = Paint()
    private val matrix = Matrix()
    private var drawnA = 0
    private var drawnB = 0
    private var drawnW = 0
    private var drawnLight = false
    private var cover: Pair<Int, Int>? = null
    private var captured = false

    init { uiKey = "ambient"; onTheme() }

    override fun onTheme() {
        a.snap(colorA()); b.snap(colorB())
        captured = false
    }

    private fun colorA() = cover?.let { CssColor.fade(it.first, if (UiTheme.light) 0.30f else 0.85f) } ?: UiTheme.base.ambientA
    private fun colorB() = cover?.let { CssColor.fade(it.second, if (UiTheme.light) 0.25f else 0.90f) } ?: UiTheme.base.ambientB

    /** The cover's top and bottom colours (core-car CoverColors), or null for the theme's glow. */
    fun glow(colors: Pair<Int, Int>?) {
        if (colors == cover) return
        cover = colors
        val now = Motion.now()
        if (isAttachedToWindow) {
            a.to(colorA(), now, UiTheme.duration(800), GLOW); b.to(colorB(), now, UiTheme.duration(800), GLOW)
        } else { a.snap(colorA()); b.snap(colorB()) }
        captured = false
        invalidate()
    }

    private fun shade(p: Paint, c: Int, cx: Float, cy: Float, rx: Float, ry: Float) {
        // an ellipse: a circle of radius rx stretched to ry; colour at the centre, its own transparent at 70 %
        p.shader = RadialGradient(0f, 0f, rx, intArrayOf(c, c and 0xFFFFFF, c and 0xFFFFFF), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP).apply {
            matrix.reset(); matrix.setScale(1f, ry / rx); matrix.postTranslate(cx, cy); setLocalMatrix(matrix)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val ca = a.value(now)
        val cb = b.value(now)
        val w = width.toFloat(); val h = height.toFloat()
        val light = UiTheme.light
        if (ca != drawnA || cb != drawnB || width != drawnW || light != drawnLight) {
            drawnA = ca; drawnB = cb; drawnW = width; drawnLight = light
            shade(paintA, ca, w * 0.18f, h * 0.40f, (if (light) 700 else 600) * d, (if (light) 480 else 420) * d)
            shade(paintB, cb, w * 0.86f, h * 0.78f, (if (light) 560 else 520) * d, (if (light) 420 else 380) * d)
        }
        canvas.drawColor(UiTheme.base.canvas)
        // the first gradient listed is on top
        canvas.drawRect(0f, 0f, w, h, paintB)
        canvas.drawRect(0f, 0f, w, h, paintA)
        if (a.running(now) || b.running(now)) postInvalidateOnAnimation()
        else if (!captured && UiTheme.glassy && width > 0) { captured = true; post { Backdrop.capture(this, UiTheme.glass.glassyFrost) } }
    }

    private companion object { val GLOW = CssBezier(0.37f, 0f, 0.63f, 1f) }
}

/**
 * Screen.tsx `AlwaysOn`: black, the clock (digital, or the analog dial in [dial]) and the date in a soft white; it
 * fades in and out over 600 ms. A touch while it shows brings the screen back and does nothing else.
 */
@SuppressLint("ViewConstructor")
class AlwaysOn(context: Context, val dial: FrameLayout, private val onTouch: () -> Unit) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private val time = KitText(context, TextStyle(168f, 600, -0.04f, 1f)).apply { uiKey = null; setTextColor(0x80FFFFFF.toInt()); isSingleLine = true }
    private val meridiem = KitText(context, TextStyle(40f, 400)).apply { uiKey = "aod-meridiem"; setTextColor(0x80FFFFFF.toInt()) }
    private val digital = ro.rdstr.ui.home.BaselineRow(context, 12f).apply { uiKey = "aod-time"; addView(time); addView(meridiem) }
    private val date = KitText(context, TextStyle(26f, 400)).apply { uiKey = "aod-date"; setTextColor(0x61FFFFFF) }
    private val shown = Transition(0f)
    private val grey = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }); alpha = (0.55f * 255).toInt() }
    var on = false; private set

    init {
        uiKey = "aod"
        setBackgroundColor(-0x1000000)
        alpha = 0f
        visibility = GONE
        dial.uiKey = "aod-dial"
        dial.setLayerType(LAYER_TYPE_HARDWARE, grey)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(dial, LinearLayout.LayoutParams((320 * d).toInt(), (320 * d).toInt()))
            addView(digital, LinearLayout.LayoutParams(-2, -2))
            addView(date, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (24 * d).toInt() })
        }
        addView(column, LayoutParams(-2, -2, Gravity.CENTER))
    }

    fun bind(on: Boolean, now: Long, face: Face, h24: Boolean) {
        if (on != this.on) {
            this.on = on
            contentDescription = if (on) "Always on display, tap to wake" else null
            if (on) visibility = VISIBLE
            animate().cancel()
            animate().alpha(if (on) 1f else 0f).setDuration(UiTheme.duration(600)).setInterpolator(Motion.interpolator(Motion.easeOut))
                .withEndAction { if (!this.on) visibility = GONE }.start()
        }
        if (!on && visibility == GONE) return
        dial.visibility = if (face == Face.ANALOG) VISIBLE else GONE
        digital.visibility = if (face == Face.ANALOG) GONE else VISIBLE
        val c = java.util.Calendar.getInstance().apply { timeInMillis = now }
        time.text = Settings.clockText(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), h24)
        meridiem.visibility = if (h24) GONE else VISIBLE
        meridiem.text = Settings.meridiem(c.get(java.util.Calendar.HOUR_OF_DAY))
        date.text = DATE.format(c.time)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!on) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) onTouch()
        return true
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean = if (on) onTouchEvent(e) else false

    private companion object { val DATE = SimpleDateFormat("EEEE d MMMM", Locale.UK) }
}

/**
 * Screen.tsx `button.screen-off`: black over everything while the screen is off, faded in over 240 ms (speeding
 * up); switched on, it lifts over 520 ms while the screen settles from its zoom. A tap wakes the screen.
 */
@SuppressLint("ViewConstructor")
class ScreenOff(context: Context, private val onWake: () -> Unit) : View(context) {
    var off = false; private set

    init {
        uiKey = "screen-off"
        setBackgroundColor(-0x1000000)
        alpha = 0f
        visibility = GONE
        contentDescription = "Screen off, tap to wake"
        setOnClickListener { onWake() }
    }

    fun bind(off: Boolean) {
        if (off == this.off) return
        this.off = off
        animate().cancel()
        if (off) {
            visibility = VISIBLE
            animate().alpha(1f).setDuration(UiTheme.duration(240)).setInterpolator(Motion.interpolator(ACCELERATE)).start()
        } else {
            // screen-lift: from black, whatever it showed
            alpha = 1f
            animate().alpha(0f).setDuration(UiTheme.duration(520)).setInterpolator(Motion.interpolator(Motion.easeOut)).withEndAction { visibility = GONE }.start()
        }
        isClickable = off
    }

    private companion object { val ACCELERATE = CssBezier(0.4f, 0f, 1f, 1f) }
}

/** `div.dimmer`: black at the veil's darkness (core-car Shell.veil), over 700 ms `--ease-out`. */
class Dimmer(context: Context) : View(context) {
    init { uiKey = "dimmer"; setBackgroundColor(-0x1000000); alpha = 0f; visibility = GONE }

    fun bind(dark: Float) {
        if (dark == tag) return
        tag = dark
        if (dark > 0f) visibility = VISIBLE
        animate().cancel()
        animate().alpha(dark).setDuration(UiTheme.duration(UiTheme.base.theme)).setInterpolator(Motion.interpolator(Motion.easeOut))
            .withEndAction { if (dark == 0f) visibility = GONE }.start()
    }
}

/** `div.scrim` under the drawer: `--scrim` over the screen while it is open (200 ms); a tap on it closes the drawer. */
@SuppressLint("ViewConstructor")
class Scrim(context: Context, private val onClose: () -> Unit) : View(context) {
    private var open = false

    init { uiKey = "scrim"; alpha = 0f; visibility = GONE; setOnClickListener { onClose() }; UiTheme.link(this) { setBackgroundColor(UiTheme.base.scrim) } }

    fun bind(open: Boolean) {
        if (open == this.open) return
        this.open = open
        if (open) visibility = VISIBLE
        isClickable = open
        animate().cancel()
        animate().alpha(if (open) 1f else 0f).setDuration(UiTheme.duration(UiTheme.base.base)).setInterpolator(Motion.interpolator(Motion.easeOut))
            .withEndAction { if (!this.open) visibility = GONE }.start()
    }
}

/**
 * Screen.tsx `svg.edge-bulge` x2: the frame's volume keys bulging the screen's left edge where they are (centres 520 and
 * 645 canvas px), black, while one is held: out over 520 ms with a small overshoot, back over 240 ms.
 */
class Bulges(context: Context) : KitView(context) {
    private val up = Transition(0f)
    private val down = Transition(0f)
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -0x1000000 }

    init {
        uiKey = null
        // M0 0C0 22 12 26 12 48V88C12 110 0 114 0 136Z
        path.moveTo(0f, 0f); path.cubicTo(0f, 22f, 12f, 26f, 12f, 48f); path.lineTo(12f, 88f); path.cubicTo(12f, 110f, 0f, 114f, 0f, 136f); path.close()
    }

    override fun onTheme() = Unit

    /** The key held now, VOL_UP or VOL_DOWN, or null. */
    fun bind(key: String?) {
        val now = Motion.now()
        for ((t, k) in listOf(up to "VOL_UP", down to "VOL_DOWN")) {
            val on = key == k
            if (on && t.target != 1f) t.to(1f, now, UiTheme.duration(520), OUT)
            if (!on && t.target != 0f) t.to(0f, now, UiTheme.duration(240), BACK)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        for ((t, centre) in listOf(up to 520f, down to 645f)) {
            val p = t.value(now)
            if (p <= 0f && !t.running(now)) continue
            // scale(0, 0.72) → scale(1, 1), from the left centre
            canvas.save()
            canvas.translate(0f, centre * d)
            canvas.scale(p * d, (0.72f + 0.28f * p) * d)
            canvas.translate(0f, -68f)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
        if (up.running(now) || down.running(now)) postInvalidateOnAnimation()
    }

    private companion object {
        val BACK = CssBezier(0.32f, 0f, 0.18f, 1f)
        val OUT = CssStops(floatArrayOf(0f, 0.22f, 0.56f, 0.86f, 1.03f, 1.07f, 1.05f, 1.01f, 0.995f, 1f), floatArrayOf(0f, 0.06f, 0.14f, 0.24f, 0.34f, 0.41f, 0.5f, 0.62f, 0.75f, 1f))
    }
}
