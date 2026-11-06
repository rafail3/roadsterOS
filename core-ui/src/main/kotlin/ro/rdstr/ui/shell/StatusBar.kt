package ro.rdstr.ui.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import ro.rdstr.car.App
import ro.rdstr.car.AudioSource
import ro.rdstr.car.CarEvent
import ro.rdstr.car.Settings
import ro.rdstr.ui.WifiBars
import ro.rdstr.ui.home.BaselineRow
import ro.rdstr.ui.home.Row
import ro.rdstr.ui.home.line
import ro.rdstr.ui.kit.ColorTransition
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.KitView
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.MusicBar
import ro.rdstr.ui.uiKey
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max

/**
 * Screen.tsx `header.bar`: the status bar across the top, 56 px. Left: the time inside an app, the phone's network
 * and battery, the source; centre: the handle that opens the drawer; right: Wi-Fi, the volume (a tap shows the HUD),
 * the theme. At home and in Maps it lies on a fading `--sheet` gradient, in the other apps on nothing; over the
 * dark-ground apps (Music, Radio, Phone Screen) its ink is white whatever the theme (`MusicBar`).
 *
 * A drag anywhere on the bar is the drawer's ([onDrag], Drawer.tsx `useDrawerDrag`); a tap on the handle that was not
 * a drag opens it.
 */
@SuppressLint("ViewConstructor")
class StatusBar(context: Context, private val actions: ShellActions) : ViewGroup(context) {
    private val d = resources.displayMetrics.density
    private val left = Row(context, 16f).apply { uiKey = "bar-group" }
    private val right = Row(context, 16f).apply { uiKey = "bar-group"; gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END }
    private val clockText = context.line(TextStyle(16f, 600, 0.01f), null, false)
    private val meridiem = context.line(TextStyle(12.8f, 500), "bar-meridiem", false)
    private val clock = BaselineRow(context, 4f).apply { uiKey = "bar-clock"; addView(clockText); addView(meridiem) }
    private val network = Network(context)
    private val source = IconView(context, Icons.BLUETOOTH, 24f).apply { uiKey = "icon.i-bluetooth" }
    private val sourceLabel = context.line(TextStyle(16f, 500), "bar-text", false)
    private val handle = Handle(context)
    private val wifiBars = WifiBars(context).apply { uiKey = "wifi-bars.bar-wifi" }
    private val wifi = FrameLayout(context).apply { uiKey = "span"; addView(wifiBars) }
    private val volumeIcon = IconView(context, Icons.SPEAKER_HIGH_FILL, 24f).apply { uiKey = "icon.i-speaker-high-fill" }
    private val volume = FrameLayout(context).apply {
        uiKey = "bar-button"
        isClickable = true
        contentDescription = "Volume"
        addView(volumeIcon, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        setOnClickListener { actions.openVolume() }
    }
    private val theme = IconView(context, Icons.MOON_FILL, 24f).apply { uiKey = "icon" }
    private val fill = Paint()
    private var fillH = -1
    private var fillColor = 0
    private var shaded = true
    private var model: ShellModel? = null

    // the drag that belongs to the drawer
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var downX = 0f
    private var dragging = false

    init {
        uiKey = "bar"
        setWillNotDraw(false)
        left.addView(clock)
        left.addView(network, android.widget.LinearLayout.LayoutParams(-2, -2).apply { rightMargin = (4 * d).toInt() })
        left.addView(source); left.addView(sourceLabel)
        right.addView(wifi)
        // .bar-button { margin: 0 -12px }: a 48 px target around the 24 px glyph that takes no room
        right.addView(volume, android.widget.LinearLayout.LayoutParams((48 * d).toInt(), (56 * d).toInt()).apply { leftMargin = (-12 * d).toInt(); rightMargin = leftMargin })
        right.addView(theme)
        addView(left); addView(handle); addView(right)
        handle.setOnClickListener { actions.openDrawer() }
        UiTheme.link(this) { model?.let(::bind) }
    }

    fun bind(m: ShellModel) {
        model = m
        val b = UiTheme.base
        val s = m.shell
        val inApp = s.app != App.HOME
        // Music, Radio and Phone Screen keep a dark ground in either theme: white ink over them
        val darkGround = s.app == App.MUSIC || s.app == App.RADIO || s.app == App.PHONE_SCREEN
        val ink = if (darkGround) MusicBar.DARK.ink else b.ink
        val color = if (darkGround) 0xDBFFFFFF.toInt() else b.ink
        shaded = !inApp || s.app == App.MAPS
        // the time, at the bar's left edge inside an app
        clock.visibility = if (inApp) VISIBLE else GONE
        if (inApp) {
            val c = Calendar.getInstance().apply { timeInMillis = m.now }
            val h = c.get(Calendar.HOUR_OF_DAY)
            clockText.text = Settings.clockText(h, c.get(Calendar.MINUTE), s.clock24h)
            meridiem.visibility = if (s.clock24h) GONE else VISIBLE
            meridiem.text = Settings.meridiem(h)
            clockText.setTextColor(ink); meridiem.setTextColor(ink)
        }
        network.visibility = if (m.car.hfpConnected) VISIBLE else GONE
        if (m.car.hfpConnected) network.bind(m.phoneStatus ?: CarEvent.PhoneStatus(), color, ink)
        val radio = m.car.source == AudioSource.RADIO
        source.setIcon(if (radio) Icons.RADIO else Icons.BLUETOOTH)
        source.uiKey = if (radio) "icon.i-radio" else "icon.i-bluetooth"
        source.color = if (m.car.source != AudioSource.NONE) ink else color
        source.alpha = if (!radio && !m.btOn) 0.45f else 1f
        sourceLabel.text = when {
            m.car.source == AudioSource.NONE && m.car.btConnected -> "Bluetooth"
            m.car.source == AudioSource.BT -> "Bluetooth"
            radio -> "Radio"
            else -> "No source"
        }
        sourceLabel.setTextColor(color)
        handle.contentDescription = "Open quick settings"
        handle.line(b.inkSubtle, b.inkMuted)
        wifiBars.level = m.wifiLevel.coerceIn(0, 3)
        wifiBars.color = color
        wifi.alpha = if (m.wifiSsid == null) 0.45f else 1f
        wifi.contentDescription = when { m.wifiSsid != null -> "Wi-Fi, ${m.wifiSsid}"; m.wifiOn -> "Wi-Fi, not connected"; else -> "Wi-Fi off" }
        volumeIcon.setIcon(if (m.car.muted) Icons.SPEAKER_SLASH_FILL else Icons.SPEAKER_HIGH_FILL)
        volumeIcon.uiKey = if (m.car.muted) "icon.i-speaker-slash-fill" else "icon.i-speaker-high-fill"
        volumeIcon.color = if (m.car.muted) b.mute else color
        volume.contentDescription = if (m.car.muted) "Volume, muted" else "Volume"
        theme.setIcon(if (UiTheme.light) Icons.SUN_FILL else Icons.MOON_FILL)
        theme.uiKey = if (UiTheme.light) "icon.i-sun-fill" else "icon.i-moon-fill"
        theme.color = color
        theme.contentDescription = if (UiTheme.light) "Day theme" else "Night theme"
        fillColor = b.sheet; fillH = -1
        requestLayout(); invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (56 * d + 0.5f).toInt()
        val exactH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        val min = (200 * d + 0.5f).toInt()
        // align-items: center: each group as tall as its content
        val wrapH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST)
        for (g in listOf(left, right)) {
            g.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), wrapH)
            if (g.measuredWidth < min) g.measure(MeasureSpec.makeMeasureSpec(min, MeasureSpec.EXACTLY), wrapH)
        }
        handle.measure(MeasureSpec.makeMeasureSpec((160 * d + 0.5f).toInt(), MeasureSpec.EXACTLY), exactH)
        setMeasuredDimension(w, h)
    }

    /** `justify-content: space-between` with 24 px padding: left group, handle, right group. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pad = (24 * d + 0.5f).toInt()
        val w = r - l
        val h = b - t
        val lt = (h - left.measuredHeight) / 2
        val rt = (h - right.measuredHeight) / 2
        left.layout(pad, lt, pad + left.measuredWidth, lt + left.measuredHeight)
        right.layout(w - pad - right.measuredWidth, rt, w - pad, rt + right.measuredHeight)
        val free = w - 2 * pad - left.measuredWidth - right.measuredWidth - handle.measuredWidth
        val x = pad + left.measuredWidth + max(0, free) / 2
        handle.layout(x, 0, x + handle.measuredWidth, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (!shaded) return
        if (fillH != height) {
            fillH = height
            val c = fillColor
            fill.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(CssColor.fade(c, 0.58f), CssColor.fade(c, 0.40f), CssColor.fade(c, 0f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
    }

    // a drag on the bar pulls the drawer; the handle's tap is not sent when the finger travelled
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; actions.barDrag(e) }
            MotionEvent.ACTION_MOVE -> if (!dragging && (abs(e.y - downY) > slop || abs(e.x - downX) > slop)) {
                dragging = true
                handle.isPressed = false
                return true
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { downX = e.x; downY = e.y; dragging = false }
        actions.barDrag(e)
        return true
    }

    /** `button.handle > .handle-line`: 48 x 4 in `--ink-subtle`, `--ink-muted` while pressed (`--fast`). */
    private class Handle(context: Context) : KitView(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val color = ColorTransition(0)
        private var rest = 0
        private var held = 0
        private val rect = RectF()

        init { uiKey = "handle"; isClickable = true; onTheme() }

        override fun onTheme() = Unit

        fun line(rest: Int, held: Int) {
            this.rest = rest; this.held = held
            color.snap(if (isPressed) held else rest); invalidate()
        }

        override fun setPressed(pressed: Boolean) {
            val was = isPressed
            super.setPressed(pressed)
            if (was != pressed) { color.to(if (pressed) held else rest, Motion.now(), UiTheme.duration(UiTheme.base.fast), Easing.EASE); invalidate() }
        }

        override fun onDraw(canvas: Canvas) {
            val now = Motion.now()
            paint.color = color.value(now)
            rect.set((width - 48 * d) / 2, (height - 4 * d) / 2, (width + 48 * d) / 2, (height + 4 * d) / 2)
            canvas.drawRoundRect(rect, 2 * d, 2 * d, paint)
            if (color.running(now)) postInvalidateOnAnimation()
        }
    }

    /**
     * PhoneApp.tsx `PhoneNetwork` in the bar: five signal bars, the operator, Roaming abroad, the battery; a hairline
     * on its right keeps it apart from the source.
     */
    private class Network(context: Context) : Row(context, 10f) {
        private val d = resources.displayMetrics.density
        private val bars = Bars(context)
        private val operator = context.line(TextStyle(16f, 500), "bar-text", false)
        private val roaming = KitText(context, TextStyle(13f, 600, 0.02f)).apply { uiKey = "bar-roaming"; text = "Roaming" }
        private val roamingBg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val battery = Battery(context)
        private val line = Paint()

        init {
            uiKey = "bar-network"
            setWillNotDraw(false)
            // the battery's nub reaches past its box
            clipChildren = false
            roaming.setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
            roaming.background = object : android.graphics.drawable.Drawable() {
                override fun draw(canvas: Canvas) { val r = RectF(bounds); canvas.drawRoundRect(r, 6 * d, 6 * d, roamingBg) }
                override fun setAlpha(alpha: Int) = Unit
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
                @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
            addView(bars); addView(operator); addView(roaming); addView(battery)
            setPadding(0, 0, (16 * d).toInt(), 0)
        }

        override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

        fun bind(s: CarEvent.PhoneStatus, color: Int, ink: Int) {
            bars.set(s.signal, color)
            operator.visibility = if (s.operator.isEmpty()) GONE else VISIBLE
            operator.text = s.operator
            operator.setTextColor(color)
            roaming.visibility = if (s.roaming) VISIBLE else GONE
            roaming.setTextColor(ink)
            roamingBg.color = CssColor.fade(ink, 0.14f)
            battery.set(s.battery, color)
            line.color = UiTheme.base.hairlineStrong
            contentDescription = "${s.operator.ifEmpty { "Phone network" }}, signal ${s.signal} of 5${if (s.roaming) ", roaming" else ""}, phone battery ${s.battery * 20}%"
            invalidate()
        }

        override fun onDraw(canvas: Canvas) = canvas.drawRect(width - d, 0f, width.toFloat(), height.toFloat(), line)
    }

    /** `.phone-bars`: 4 px bars 2 px apart, 5 to 16 px tall; off ones at 28 % of the ink. */
    private class Bars(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var signal = 0
        private var color = 0
        private val rect = RectF()

        init { uiKey = "phone-bars" }

        fun set(signal: Int, color: Int) { this.signal = signal; this.color = color; invalidate() }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension((28 * d + 0.5f).toInt(), (16 * d + 0.5f).toInt())

        override fun onDraw(canvas: Canvas) {
            for (i in 0 until 5) {
                paint.color = if (i + 1 <= signal) color else CssColor.fade(color, 0.28f)
                val h = HEIGHTS[i] * d
                rect.set(i * 6 * d, height - h, (i * 6 + 4) * d, height.toFloat())
                canvas.drawRoundRect(rect, d, d, paint)
            }
        }

        private companion object { val HEIGHTS = floatArrayOf(5f, 8f, 11f, 14f, 16f) }
    }

    /** `.phone-battery`: 28 x 14, a 1.5 px edge at 60 % of the ink, a nub, filled to level / 5 (red at the last step). */
    private class Battery(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val nub = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private var level = 0
        private val rect = RectF()

        init { uiKey = "phone-battery" }

        fun set(level: Int, color: Int) {
            this.level = level.coerceIn(0, 5)
            edge.color = CssColor.fade(color, 0.6f); nub.color = edge.color
            fill.color = if (level <= 1) UiTheme.base.callRed else color
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension((28 * d + 0.5f).toInt(), (14 * d + 0.5f).toInt())

        override fun onDraw(canvas: Canvas) {
            val b = 1.5f * d
            edge.strokeWidth = b
            rect.set(b / 2, b / 2, width - b / 2, height - b / 2)
            canvas.drawRoundRect(rect, 4 * d - b / 2, 4 * d - b / 2, edge)
            // top: 3px; right: -4px from the padding box: past the battery's own box
            rect.set(width - b + 2 * d, b + 3 * d, width - b + 4 * d, b + 8 * d)
            canvas.drawRoundRect(rect, d, d, nub)
            // padding 2 inside the 1.5 edge, scaled from the left
            val inner = width - 2 * b - 4 * d
            rect.set(b + 2 * d, b + 2 * d, b + 2 * d + inner * level / 5f, height - b - 2 * d)
            if (level > 0) canvas.drawRoundRect(rect, 1.5f * d, 1.5f * d, fill)
        }
    }
}
