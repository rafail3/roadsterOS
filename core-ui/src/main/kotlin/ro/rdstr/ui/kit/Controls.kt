package ro.rdstr.ui.kit

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.tokens.Icons

/**
 * controls.tsx `holdToRepeat`: a press sends once at once, again after 270 ms and then every 210 ms while the finger
 * stays on, as the unit's MCU repeats a held key (measured). One finger holds one key at a time, so one timer serves
 * the whole screen. A click without a touch (accessibility) sends once.
 *
 * controls.tsx `useFingerMouse` (a mouse drag scrolls a list and glides out) is what touch already does on Android,
 * so it has no counterpart here.
 */
@Mirrors("controls.tsx")
object HoldRepeat {
    const val FIRST_REPEAT_MS = 270L
    const val REPEAT_MS = 210L

    private val handler = Handler(Looper.getMainLooper())
    private var repeat: Runnable? = null
    private var stopHold: (() -> Unit)? = null

    private fun stop() {
        repeat?.let { handler.removeCallbacks(it) }
        repeat = null
        stopHold?.invoke(); stopHold = null
    }

    @SuppressLint("ClickableViewAccessibility")
    fun attach(view: View, send: () -> Unit, onHold: ((Boolean) -> Unit)? = null) {
        view.isClickable = true
        view.setOnClickListener { send() }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stop()
                    onHold?.invoke(true)
                    stopHold = { onHold?.invoke(false) }
                    v.isPressed = true
                    send()
                    val tick = object : Runnable { override fun run() { send(); handler.postDelayed(this, REPEAT_MS) } }
                    repeat = tick
                    handler.postDelayed(tick, FIRST_REPEAT_MS)
                }
                // pointerleave: the finger slid off the key
                MotionEvent.ACTION_MOVE -> if (e.x < 0 || e.y < 0 || e.x > v.width || e.y > v.height) { stop(); v.isPressed = false }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stop(); v.isPressed = false }
            }
            true
        }
    }
}

/**
 * music.css `.glass-button`: the round glass button (close, Bluetooth, sound). 56 px, `--button-glass` with a 1 px
 * `rgb(var(--on) / .12)` edge, a lit top line and a soft drop; pressed it swells to 1.07 in 120 ms and settles back
 * in 300 ms on `--spring-glyph`. On Glassy (glassy.css) the glass material, `--glassy-fill-strong` while pressed.
 */
@SuppressLint("ViewConstructor")
class GlassButton(context: Context, icon: Int, size: Float = 56f, private val iconSize: Float = 28f) : KitView(context) {
    var size = size; set(v) { field = v; requestLayout() }
    private val glyph: Drawable = context.getDrawable(icon)!!.mutate()
    private val box = CssBox().apply { radius = 999f; border = 1f }
    private val scale = Transition(1f)
    private var down = false

    init {
        isClickable = true
        onTheme()
    }

    override fun onTheme() {
        val b = UiTheme.base
        box.borderColor = CssColor.withAlpha(b.on, 0.12f)
        if (UiTheme.glassy) box.glassy(UiTheme.glass, if (down) UiTheme.glass.glassyFillStrong else UiTheme.glass.glassyFill)
        else {
            box.solid(b.buttonGlass)
            box.shadows = listOf(Shadow(true, 0f, 1f, 0f, 0f, CssColor.withAlpha(b.on, 0.3f)), Shadow(false, 0f, 8f, 24f, 0f, 0x59000000))
        }
        glyph.setTint(b.buttonGlassInk)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (size * d + 0.5f).toInt()
        setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec))
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
        scale.to(if (on) 1.07f else 1f, Motion.now(), UiTheme.duration(if (on) 120 else 300), Motion.springGlyph)
        if (UiTheme.glassy) box.fill = if (on) UiTheme.glass.glassyFillStrong else UiTheme.glass.glassyFill
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val s = scale.value(now)
        canvas.save()
        canvas.scale(s, s, width / 2f, height / 2f)
        box.draw(canvas, this, width.toFloat(), height.toFloat())
        IconView.place(glyph, 0f, 0f, width.toFloat(), height.toFloat(), iconSize * d)
        glyph.draw(canvas)
        canvas.restore()
        if (scale.running(now)) postInvalidateOnAnimation()
    }
}

/**
 * glassy.css `.glass-bead`: the glass bead at the position on the timeline and the volume rail (Glassy).
 * `rgb(255 255 255 / .88)` with its drop, rim and inner shading; its 2 px backdrop blur under that much white is
 * not drawn. Sizes from the tokens (`GlassBead`, `MusicSeekGlassBead`, …).
 */
class GlassBead {
    private val box = CssBox().apply {
        radius = 999f
        fill = 0xE0FFFFFF.toInt()
        shadows = listOf(
            Shadow(false, 0f, 4f, 14f, 0f, 0x52000000),
            Shadow(true, 0f, 0f, 0f, 1.5f, 0x8CFFFFFF.toInt()),
            Shadow(true, 0f, 2f, 1f, 0f, 0x80FFFFFF.toInt()),
            Shadow(true, -1f, -3f, 6f, 0f, 0x14000000),
        )
    }

    /** The bead of [size] canvas px centred on ([cx], [cy]) device px. */
    fun draw(canvas: Canvas, view: View, cx: Float, cy: Float, size: Float) {
        val s = size * view.resources.displayMetrics.density
        canvas.save(); canvas.translate(cx - s / 2, cy - s / 2)
        box.draw(canvas, view, s, s)
        canvas.restore()
    }
}

/** A gap of [px] between a LinearLayout's children (CSS `gap`), as its divider. */
internal class Gap(private val px: Int) : Drawable() {
    override fun getIntrinsicWidth() = px
    override fun getIntrinsicHeight() = px
    override fun draw(canvas: Canvas) = Unit
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSPARENT
}

/**
 * SettingsApp.tsx `Group`: a title (15 px, 600, `--ink-muted`), the rows on one rounded card (`.set-rows`, 18 px,
 * `--set-group`; on Glassy the glass material) and a footer (15 px, line-height 1.45, at most 70ch), 8 px apart.
 * Rows go in [rows].
 */
class SettingsGroup(context: Context) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private val titleView = KitText(context, TextStyle(15f, 600)).apply { visibility = GONE }
    private val footerView = KitText(context, TextStyle(15f, 400, lineHeight = 1.45f)).apply { visibility = GONE }
    val rows = SettingsRows(context)
    private val drop = CssBox().apply { radius = 18f }

    var title: CharSequence? = null; set(v) { field = v; titleView.text = v; titleView.visibility = if (v == null) GONE else VISIBLE }
    var footer: CharSequence? = null; set(v) { field = v; footerView.text = v; footerView.visibility = if (v == null) GONE else VISIBLE }

    init {
        orientation = VERTICAL
        dividerDrawable = Gap((8 * d + 0.5f).toInt())
        showDividers = SHOW_DIVIDER_MIDDLE
        addView(titleView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = (20 * d + 0.5f).toInt() })
        addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(footerView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = (20 * d + 0.5f).toInt(); rightMargin = leftMargin
        })
        footerView.maxWidth = (footerView.paint.measureText("0") * 70).toInt()
        setWillNotDraw(false)
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        val st = UiTheme.settings
        titleView.setTextColor(st.inkMuted)
        footerView.setTextColor(st.inkMuted)
        footerView.maxWidth = (footerView.paint.measureText("0") * 70).toInt()
        drop.shadows = if (UiTheme.glassy) UiTheme.glass.glassyShadow.filter { !it.inset } else emptyList()
        invalidate()
    }

    /** The card's outer drop, outside its clip, under it. */
    override fun dispatchDraw(canvas: Canvas) {
        if (drop.shadows.isNotEmpty() && rows.visibility == VISIBLE) {
            canvas.save(); canvas.translate(rows.left.toFloat(), rows.top.toFloat())
            drop.draw(canvas, this, rows.width.toFloat(), rows.height.toFloat())
            canvas.restore()
        }
        super.dispatchDraw(canvas)
    }
}

/** The card of a [SettingsGroup] (`.set-rows`): rounded, clipping its rows, solid or glass. */
class SettingsRows(context: Context) : LinearLayout(context) {
    private val box = CssBox().apply { radius = 18f }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 18 * resources.displayMetrics.density)
        }
        UiTheme.link(this) { theme() }
        theme()
    }

    private fun theme() {
        if (UiTheme.glassy) {
            val g = UiTheme.glass
            box.glassy(g); box.shadows = g.glassyShadow.filter { it.inset }
        } else box.solid(UiTheme.settings.setGroup)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) = box.draw(canvas, this, width.toFloat(), height.toFloat())
}

/**
 * SettingsApp.tsx `Row`: one line of a group, 68 px at least, 20 px in from each side, 16 px between its parts: the
 * label (19 px, 500), then a value (18 px, `--ink-muted`, at most half the row), any controls added with
 * [addControl], and a chevron when it opens something ([onPress]). A row below another has a 1 px `--set-line` at
 * its top. Pressable rows darken to `rgb(var(--on) / .08)` while pressed (`--fast`); [danger] rows read red (600)
 * and carry no chevron. [slider] rows (`.set-row.is-slider`) are 80 px and give a slider the whole width.
 */
@SuppressLint("ViewConstructor")
class SettingsRow(context: Context, label: CharSequence = "", slider: Boolean = false) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    val label = KitText(context, TextStyle(19f, 500)).apply { text = label; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val valueView = KitText(context, TextStyle(18f)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = GONE }
    private val chevron = IconView(context, Icons.CARET_RIGHT, 20f).apply { visibility = GONE }
    private val paint = Paint()
    private val press = ColorTransition(0)
    private val controls = ArrayList<View>()

    var value: CharSequence? = null; set(v) { field = v; valueView.text = v; valueView.visibility = if (v == null) GONE else VISIBLE }
    var danger = false; set(v) { field = v; theme(); refreshChevron() }
    var onPress: (() -> Unit)? = null; set(v) { field = v; isClickable = v != null; refreshChevron() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ((if (slider) 80 else 68) * d + 0.5f).toInt()
        val side = (20 * d + 0.5f).toInt()
        setPadding(side, 0, side, 0)
        dividerDrawable = Gap((16 * d + 0.5f).toInt())
        showDividers = SHOW_DIVIDER_MIDDLE
        setWillNotDraw(false)
        if (!slider) addView(this.label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(valueView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(chevron, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setOnClickListener { onPress?.invoke() }
        isClickable = false
        UiTheme.link(this) { theme() }
        theme()
    }

    /** A control after the value (a switch, a segmented control, a stepper); a slider row's slider takes the width. */
    fun addControl(view: View, fill: Boolean = false) {
        controls += view
        addView(view, indexOfChild(chevron), if (fill) LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f) else LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun refreshChevron() { chevron.visibility = if (onPress != null && !danger) VISIBLE else GONE }

    private fun theme() {
        val st = UiTheme.settings
        label.style = if (danger) TextStyle(19f, 600) else TextStyle(19f, 500)
        label.setTextColor(if (danger) DANGER else st.ink)
        valueView.setTextColor(st.inkMuted)
        chevron.color = CssColor.withAlpha(UiTheme.base.on, 0.42f)
        press.snap(if (isPressed && onPress != null) CssColor.withAlpha(UiTheme.base.on, 0.08f) else 0)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        valueView.maxWidth = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight) / 2
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun dispatchSetPressed(pressed: Boolean) {
        super.dispatchSetPressed(pressed)
        if (onPress == null) return
        press.to(if (pressed) CssColor.withAlpha(UiTheme.base.on, 0.08f) else 0, Motion.now(), UiTheme.duration(UiTheme.base.fast), Easing.EASE)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = Motion.now()
        val bg = press.value(now)
        if (CssColor.alpha(bg) > 0) { paint.color = bg; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint) }
        // .set-row + .set-row { box-shadow: inset 0 1px 0 var(--set-line) }
        if (((parent as? ViewGroup)?.indexOfChild(this) ?: 0) > 0) {
            paint.color = UiTheme.settings.setLine
            canvas.drawRect(0f, 0f, width.toFloat(), d, paint)
        }
        super.onDraw(canvas)
        if (press.running(now)) postInvalidateOnAnimation()
    }

    private companion object {
        /** settings.css `.set-row.is-danger .set-row-label { color: #ff453a }` */
        const val DANGER = 0xFFFF453A.toInt()
    }
}
