package ro.rdstr.ui.maps

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.Transition
import ro.rdstr.ui.kit.UiScale
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.uiKey

/** A CSS border width in canvas px: whole device pixels, as the browser draws it. */
internal fun View.border(css: Float): Float = UiScale.borderPx(css, resources.displayMetrics.density) / resources.displayMetrics.density

/**
 * A round glass button of the maps screen (`.glass-button`, music.css:107, with maps.css's day rules and Glassy):
 * [size] px (56; 44 for the credits) with its [glyph] of [glyphSize] centred, a 1 px edge; pressed it swells to 1.07
 * in 120 ms and settles back in 300 ms on `--spring-glyph`, and takes `--glassy-fill-strong` on Glassy. [ground] is
 * what lies under it, for the frost.
 */
@SuppressLint("ViewConstructor")
internal class MapButton(context: Context, key: String, private val size: Float, val glyph: View, private val glyphSize: Float) : CssGroup(context) {
    private val box = CssBox().apply { radius = 999f }
    private var down = false
    var ground = 0; set(v) { if (field != v) { field = v; theme() } }
    /** The glyph's colour, the button's `color`; the locate button's is the location blue. */
    var ink: () -> Int = { MapsLook.buttonInk }; set(v) { field = v; theme() }
    /** Called on each press and release (the locate glyph's own spring). */
    var onPress: ((Boolean) -> Unit)? = null

    init {
        uiKey = key
        setWillNotDraw(false)
        isClickable = true
        addView(glyph)
        UiTheme.link(this) { theme() }
        theme()
    }

    fun theme() {
        MapsLook.button(box, ground, down)
        (glyph as? IconView)?.color = ink()
        invalidate()
    }

    override fun measureCss(maxW: Float, maxH: Float) { cssW = size; cssH = size }

    override fun placeCss() {
        val at = (size - glyphSize) / 2
        place(glyph, at, at, glyphSize, glyphSize)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(false)
        }
        return super.onTouchEvent(e)
    }

    private fun press(on: Boolean) {
        if (down == on) return
        down = on
        val s = if (on) 1.07f else 1f
        animate().scaleX(s).scaleY(s).setDuration(UiTheme.duration(if (on) 120L else 300L))
            .setInterpolator(Motion.interpolator(Motion.springGlyph)).start()
        onPress?.invoke(on)
        if (UiTheme.glassy) theme()
    }

    override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
}

/**
 * The compass glyph (`span.map-compass`, maps.css:14-16): 26 px, a red half pointing north (its `<i>`) and a white
 * one (by day dark) pointing south: CSS border triangles `border: 6px solid transparent` with a 13 px coloured side,
 * each width floored to whole device pixels as the browser draws borders. It turns `rotate(-bearing)` in 200 ms on
 * `--ease-out`.
 */
internal class MapCompass(context: Context) : CssGroup(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val south = Path()
    private val north = Triangle(context)
    private val turn = Transition(0f)
    private val side get() = border(6f)
    private val tall get() = border(13f)

    init {
        uiKey = "map-compass"
        setWillNotDraw(false)
        addView(north)
        UiTheme.link(this) { paint.color = MapsLook.south; invalidate() }
    }

    override fun measureCss(maxW: Float, maxH: Float) { cssW = 26f; cssH = 26f }

    // `left: 50%; margin-left: -6px`; the north half at the top, the south one at the bottom
    override fun placeCss() = place(north, 7f, 0f, 2 * side, tall)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val l = 7f * d
        val r = l + 2 * side * d
        val top = h - tall * d
        south.rewind(); south.moveTo(l, top); south.lineTo(r, top); south.lineTo((l + r) / 2f, h.toFloat()); south.close()
    }

    /** The map's bearing (MapLibre's `rotate`, MapView.onRotate): the glyph turns to `-bearing`. */
    fun bearing(b: Double, animate: Boolean = true) {
        if (animate) turn.to(-b.toFloat(), Motion.now(), UiTheme.duration(200L), Motion.easeOut) else turn.snap(-b.toFloat())
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val now = Motion.now()
        canvas.save()
        canvas.rotate(turn.value(now), width / 2f, height / 2f)
        canvas.drawPath(south, paint)
        super.dispatchDraw(canvas)
        canvas.restore()
        if (turn.running(now)) postInvalidateOnAnimation()
    }

    /** `.map-compass i`: the red triangle pointing up. */
    private class Triangle(context: Context) : View(context) {
        private val path = Path()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MapsLook.RED }
        init { uiKey = "i" }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            path.rewind(); path.moveTo(w / 2f, 0f); path.lineTo(w.toFloat(), h.toFloat()); path.lineTo(0f, h.toFloat()); path.close()
        }
        override fun onDraw(canvas: Canvas) = canvas.drawPath(path, p)
    }
}

/**
 * The zoom pill (`div.map-zoom`, maps.css:17-20): 56 px wide, two 56 px keys inside one 1 px edge, rounded 28 px and
 * clipping them, the second key's 1 px top border between them. A key darkens to `rgba(255, 255, 255, .12)` while
 * pressed (`--fast`).
 */
internal class MapZoom(context: Context, zoom: (Double) -> Unit) : CssGroup(context) {
    private val box = CssBox().apply { radius = 28f }
    private val clip = Path()
    var ground = 0; set(v) { if (field != v) { field = v; theme() } }
    private val plus = Key(context, "+", false) { zoom(1.0) }
    private val minus = Key(context, "−", true) { zoom(-1.0) }

    init {
        uiKey = "map-zoom"
        setWillNotDraw(false)
        addView(plus)
        addView(minus)
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        MapsLook.button(box, ground)
        plus.theme(); minus.theme()
        invalidate()
    }

    override fun measureCss(maxW: Float, maxH: Float) { cssW = 56f; cssH = 2 * border(1f) + 112f }

    override fun placeCss() {
        val b = border(1f)
        place(plus, b, b, 56f - 2 * b, 56f)
        place(minus, b, b + 56f, 56f - 2 * b, 56f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = 28 * d
        clip.rewind(); clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())

    // `overflow: hidden`: a pressed key stays inside the rounded edge
    override fun dispatchDraw(canvas: Canvas) {
        canvas.save(); canvas.clipPath(clip); super.dispatchDraw(canvas); canvas.restore()
    }

    /** One key (`.map-zoom button`): its sign (`span`, 30 px, 400, line-height 1) centred; the second has the line on top. */
    @SuppressLint("ViewConstructor")
    private class Key(context: Context, sign: String, private val lined: Boolean, private val press: () -> Unit) : CssGroup(context) {
        private val text = KitText(context, TextStyle(30f, 400, lineHeight = 1f)).apply { text = sign; uiKey = "span"; includeFontPadding = false }
        private val bg = ColorTransition(0)
        private val paint = Paint()

        init {
            uiKey = "button"
            setWillNotDraw(false)
            isClickable = true
            setOnClickListener { press() }
            addView(text)
        }

        fun theme() { text.setTextColor(MapsLook.zoomInk); invalidate() }

        override fun measureCss(maxW: Float, maxH: Float) { cssW = maxW; cssH = maxH }

        override fun placeCss() {
            val top = if (lined) border(1f) else 0f
            val w = text.cssWidth()
            place(text, (cssW - w) / 2, top + (cssH - top - 30f) / 2, w, 30f)
        }

        override fun dispatchSetPressed(pressed: Boolean) {
            super.dispatchSetPressed(pressed)
            bg.to(if (pressed) CssColor.withAlpha(-1, 0.12f) else 0, Motion.now(), UiTheme.duration(UiTheme.base.fast), Motion.easeOut)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val now = Motion.now()
            val c = bg.value(now)
            if (CssColor.alpha(c) > 0) { paint.color = c; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint) }
            if (lined) { paint.color = MapsLook.edge; canvas.drawRect(0f, 0f, width.toFloat(), border(1f) * d, paint) }
            if (bg.running(now)) postInvalidateOnAnimation()
        }
    }
}
