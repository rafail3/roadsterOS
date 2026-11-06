package ro.rdstr.ui.sheets

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.WifiBars
import ro.rdstr.ui.kit.CssBox
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.GlassButton
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitSwitch
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.uiKey
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** `rgb(var(--on) / a)` in the current theme. */
internal fun on(a: Float) = CssColor.withAlpha(UiTheme.base.on, a)

/**
 * The Glassy material on a box of the `:where(…)` list of glassy.css (fill, sheen, frost, glassy shadow), or Solid
 * [fill] with [shadows]; [tinted] keeps [fill] under the sheen (the `:is(…)` list of coloured keys).
 */
internal fun CssBox.look(fill: Int, shadows: List<Shadow> = emptyList(), tinted: Boolean = false) {
    if (!UiTheme.glassy) { solid(fill); this.shadows = shadows; return }
    if (tinted) glassy(UiTheme.glass, fill) else glassy(UiTheme.glass)
}

/** A text button (`button` with its label as its own text): the label is the element, its box the background. */
@SuppressLint("ViewConstructor")
internal class TextButton(context: Context, key: String, style: TextStyle) : KitText(context, style) {
    val box = CssBox()
    var pressedFill: Int? = null
    var restFill = 0
    var pressScale = 1f
    private val scale = ro.rdstr.ui.kit.Transition(1f)

    init {
        uiKey = key
        paintFlags = paintFlags or android.graphics.Paint.SUBPIXEL_TEXT_FLAG or android.graphics.Paint.LINEAR_TEXT_FLAG
        maxLines = 1; isSingleLine = true
        gravity = Gravity.CENTER
        background = BoxDrawable(this, box)
        isClickable = true
    }

    fun fill(c: Int) { restFill = c; if (!(isPressed && pressedFill != null)) box.fill = c; invalidate() }

    override fun setPressed(pressed: Boolean) {
        val was = isPressed
        super.setPressed(pressed)
        if (was == pressed) return
        pressedFill?.let { box.fill = if (pressed) it else restFill }
        if (pressScale != 1f) scale.to(if (pressed) pressScale else 1f, Motion.now(), UiTheme.duration(if (pressed) 120 else 300), Motion.springGlyph)
        invalidate()
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val now = Motion.now()
        val s = scale.value(now)
        if (s != 1f) { canvas.save(); canvas.scale(s, s, width / 2f, height / 2f) }
        super.draw(canvas)
        if (s != 1f) canvas.restore()
        if (scale.running(now)) postInvalidateOnAnimation()
    }
}

/**
 * `div.devices` (DevicesSheet.tsx, NetworksSheet.tsx): the sheet's frame under the status bar, a dimming scrim and the
 * panel on the right (`.devices-panel`, music.css): 480 px wide, 8 px under the bar, 24 px from the right and bottom
 * edges, 396 px from the bottom while the keyboard is up ([keyboard]). The head holds the title, the radio's switch
 * and the close button. It slides in from 32 px right while fading (420 ms `--sheet-in`, 260 ms `--seamless`).
 * Laid out by the screen under the bar (`inset: var(--bar) 0 0 0`); measured without a fixed height it takes the
 * screen's height less the bar.
 */
@SuppressLint("ViewConstructor")
open class SheetFrame(context: Context, key: String, title: String) : ViewGroup(context) {
    val scrim = View(context).apply { uiKey = "devices-scrim"; isClickable = true }
    val panel = Flex(context, "devices-panel", column = true)
    val head = Flex(context, "devices-head")
    private val titleView = text(context, "h2", TextStyle(28f, 600, -0.01f)).apply { text = title }
    private val headEnd = Flex(context, "devices-head-end")
    val switch = KitSwitch(context)
    val close = GlassButton(context, Icons.X)
    private val scrimBox = CssBox()
    var open = false; private set
    private var keyboardUp = false
    private var bottom = BOTTOM
    private var bottomAnim: ValueAnimator? = null

    init {
        uiKey = key
        clipChildren = false
        scrim.background = BoxDrawable(scrim, scrimBox)
        scrim.contentDescription = "Close"
        close.uiKey = "glass-button"
        close.contentDescription = "Close"
        addView(scrim)
        addView(panel)
        panel.pad(24f); panel.gap = 20f
        panel.box.radius = 24f; panel.box.border = 1f
        panel.add(head)
        head.align = Flex.Align.CENTER; head.justify = Flex.Justify.BETWEEN
        head.add(titleView)
        head.add(headEnd)
        headEnd.align = Flex.Align.CENTER; headEnd.gap = 16f
        headEnd.add(switch)
        headEnd.add(close)
        UiTheme.link(this) { frameStyle() }
        frameStyle()
        show(false, animate = false)
    }

    private fun frameStyle() {
        scrimBox.fill = 0x66000000
        panel.box.borderColor = on(0.1f)
        panel.box.look(UiTheme.base.panel, listOf(Shadow(false, 0f, 18f, 48f, 0f, 0x73000000)))
        titleView.setTextColor(UiTheme.base.ink)
        switch.uiKey = if (UiTheme.glassy) "lg-switch.set-switch-glass" else "switch"
        restyle()
        invalidate(); panel.invalidate(); scrim.invalidate()
    }

    /** The theme changed: the content's colours. */
    protected open fun restyle() = Unit

    /** `.is-open`: the scrim fades in over 320 ms, the panel slides and fades in. */
    fun show(open: Boolean, animate: Boolean = true) {
        if (open == this.open && animate) return
        this.open = open
        isClickable = open
        visibility = VISIBLE
        val run = animate && UiTheme.speed > 0f && isAttachedToWindow
        val d = resources.displayMetrics.density
        scrim.animate().cancel(); panel.animate().cancel()
        if (!run) {
            scrim.alpha = if (open) 1f else 0f
            panel.alpha = if (open) 1f else 0f
            panel.translationX = if (open) 0f else 32 * d
            if (!open) visibility = INVISIBLE
            return
        }
        scrim.animate().alpha(if (open) 1f else 0f).setDuration(UiTheme.duration(320)).setInterpolator(Motion.interpolator(UiTheme.base.seamless)).start()
        panel.animate().alpha(if (open) 1f else 0f).translationX(if (open) 0f else 32 * d).setDuration(UiTheme.duration(420))
            .setInterpolator(Motion.interpolator(UiTheme.base.sheetIn)).withEndAction { if (!this.open) visibility = INVISIBLE }.start()
    }

    /** `.has-keyboard`: the panel's bottom edge rises over the keyboard (300 ms `--sheet-in`). */
    fun keyboard(up: Boolean) {
        if (up == keyboardUp) return
        keyboardUp = up
        bottomAnim?.cancel()
        val to = if (up) KEYBOARD_BOTTOM else BOTTOM
        if (UiTheme.speed <= 0f || !isAttachedToWindow) { bottom = to; requestLayout(); return }
        bottomAnim = ValueAnimator.ofFloat(bottom, to).apply {
            duration = UiTheme.duration(300); interpolator = Motion.interpolator(UiTheme.base.sheetIn)
            addUpdateListener { bottom = it.animatedValue as Float; requestLayout() }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = open

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec) - if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) 0 else (BAR * d).roundToInt()
        setMeasuredDimension(w, h)
        scrim.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        val wd = w / d; val hd = h / d
        val x = wd - RIGHT - WIDTH
        val l = (x * d).roundToInt(); val t = (TOP * d).roundToInt()
        panel.errX = x - l / d; panel.errY = TOP - t / d
        panel.measure(MeasureSpec.makeMeasureSpec(((x + WIDTH) * d).roundToInt() - l, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(((hd - bottom) * d).roundToInt() - t, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val d = resources.displayMetrics.density
        scrim.layout(0, 0, scrim.measuredWidth, scrim.measuredHeight)
        val x = ((width / d - RIGHT - WIDTH) * d).roundToInt()
        val y = (TOP * d).roundToInt()
        panel.layout(x, y, x + panel.measuredWidth, y + panel.measuredHeight)
    }

    private companion object {
        const val BAR = 56f
        const val TOP = 8f
        const val RIGHT = 24f
        const val WIDTH = 480f
        const val BOTTOM = 24f
        const val KEYBOARD_BOTTOM = 396f
    }
}

/** `.device-icon` (`.is-large`): a round disc with the glyph, accent-tinted for the connected row. */
internal class DeviceIcon(context: Context, res: Int, large: Boolean = false, bars: Boolean = false) : Flex(context, "device-icon") {
    val glyph: IconView? = if (bars) null else icon(context, "icon", res, if (large) 36f else 26f)
    val wifiBars: WifiBars? = if (bars) WifiBars(context).apply { level = 0 } else null
    private val size = if (large) 72f else 48f
    var connected = false; set(v) { field = v; restyle() }

    init {
        align = Align.CENTER; justify = Justify.CENTER
        box.radius = 999f
        glyph?.let { setGlyph(res); add(it, Item(w = if (large) 36f else 26f, h = if (large) 36f else 26f)) }
        wifiBars?.let { add(it, Item(w = 36f, h = 36f)) }
        restyle()
    }

    fun setGlyph(res: Int) { glyph?.setIcon(res); glyph?.uiKey = iconKey(res) }

    fun item() = Item(w = size, h = size)

    fun restyle() {
        val b = UiTheme.base
        // .saved-device.is-connected .device-icon outranks the glass: its tint stays, under the glass shadow
        if (connected) { box.solid(b.accentTint); if (UiTheme.glassy) box.shadows = UiTheme.glass.glassyShadow } else box.look(on(0.1f))
        val ink = if (connected) b.accent else on(0.86f)
        glyph?.color = ink
        wifiBars?.color = ink
        invalidate()
    }
}

/** The `icon.i-…` key of an icon resource. */
internal fun View.iconKey(res: Int) = "icon.i-" + resources.getResourceEntryName(res).removePrefix("rdstr_icon_").replace('_', '-')

/** A device's or network's line: what it is called and its state (`.device-text`). */
internal class DeviceText(context: Context) : Flex(context, "device-text", column = true) {
    val name = text(context, "device-name", TextStyle(20f, 600))
    val status = text(context, "device-status", TextStyle(15f))

    init { gap = 4f; add(name); add(status) }
}

/** The row's tappable part (`button.device-main`): icon, the two lines, anything trailing. */
internal class DeviceMain(context: Context, iconRes: Int) : Flex(context, "device-main") {
    val icon = DeviceIcon(context, iconRes)
    val label = DeviceText(context)

    init {
        isClickable = true
        align = Align.CENTER; gap = 16f; pad(12f, 16f)
        box.radius = 16f
        add(icon, icon.item())
        add(label, Item(shrink = true))
    }

    /** `:disabled`: no taps; dimmed to 0.55 unless it is the connected row (music.css). */
    fun lock(locked: Boolean, connected: Boolean) {
        isEnabled = !locked
        alpha = if (locked && !connected) 0.55f else 1f
    }

    fun restyle() {
        pressedFill = on(0.08f)
        fill(0)
        label.name.setTextColor(UiTheme.base.ink)
        icon.restyle()
    }
}

/** NetworksSheet.tsx `Signal`: the lock of a secured network and its bars (`.wifi-signal`). */
internal class Signal(context: Context) : Flex(context, "wifi-signal") {
    private val lock = icon(context, "icon.i-lock", Icons.LOCK, 16f)
    val bars = WifiBars(context)

    init {
        align = Align.CENTER; gap = 10f
        add(lock, Item(w = 16f, h = 16f))
        add(bars, Item(w = 24f, h = 24f))
    }

    fun set(secure: Boolean, level: Int) {
        lock.visibility = if (secure) VISIBLE else GONE
        bars.level = level.coerceIn(0, 3)
        lock.color = on(0.86f); bars.color = on(0.86f)
    }
}

/**
 * DevicesSheet.tsx `SavedRow`: a saved phone or network on a card that slides left to show its red remove key.
 * A sideways drag past [INTENT_PX] takes the row (a vertical one leaves it to the list), the card follows with a
 * stiffening give past either end, and the release leaves it open past half the key ([OPEN_AT]); a drag never
 * counts as a tap and a tap on an open row only closes it. Removing slides the row out (260 ms) and closes its gap
 * (300 ms) before [onForget].
 */
@SuppressLint("ViewConstructor")
class SavedRow(context: Context, iconRes: Int = Icons.DEVICE_MOBILE) : Flex(context, "saved-device") {
    var onSelect: (() -> Unit)? = null
    var onForget: (() -> Unit)? = null
    var onDrop: (() -> Unit)? = null
    /** Asked to open (its name) or close (null) the row: one row is open at a time. */
    var onOpen: ((Boolean) -> Unit)? = null
    var isOpen = false; set(v) { if (field != v) { field = v; if (!dragging) settle() } }

    private val trash = Flex(context, "device-trash")
    private val trashIcon = icon(context, "icon.i-trash", Icons.TRASH, 30f)
    private val swipe = Flex(context, "device-swipe")
    internal val main = DeviceMain(context, iconRes)
    private val drop = TextButton(context, "device-drop", TextStyle(16f, 600)).apply { text = "Disconnect"; setPadding(0, 0, 0, 0) }
    internal val signal = Signal(context)
    private var statusKey = "saved"
    private var dragging = false
    private var leaving = false
    private var offset = 0f
    private var downX = 0f; private var downY = 0f; private var from = 0f
    private var sideways: Boolean? = null
    private var swallow = false
    private val handler = Handler(Looper.getMainLooper())

    init {
        box.radius = 16f
        addView(trash, Item(absolute = true))
        trash.box.radius = 15f
        trash.align = Align.CENTER; trash.justify = Justify.CENTER; trash.padL = 16f
        trash.add(trashIcon, Item(w = 30f, h = 30f))
        trash.isClickable = true
        trash.setOnClickListener { remove() }
        trash.contentDescription = "Remove"
        add(swipe, Item(grow = 1f))
        swipe.align = Align.CENTER; swipe.gap = 12f
        swipe.box.radius = 16f; swipe.box.border = 1f
        swipe.add(main, Item(grow = 1f, shrink = true, minMain = 0f, h = Float.NaN))
        main.setOnClickListener { onSelect?.invoke() }
        main.add(signal, Item(pushEnd = true))
        signal.visibility = GONE
        swipe.add(drop, Item(h = 56f, mR = 12f))
        drop.setOnClickListener { onDrop?.invoke() }
        drop.visibility = GONE
        restyle()
    }

    /** The name, the status ([key]: connected, connecting, failed, saved) and whether a connection is running. */
    fun show(name: String, key: String, status: String, connectLocked: Boolean, canDrop: Boolean) {
        main.label.name.text = name
        main.label.status.text = status
        statusKey = key
        main.icon.connected = key == "connected"
        main.lock(key == "connected" || connectLocked, key == "connected")
        main.contentDescription = if (key == "connected") "$name, connected" else "Connect to $name"
        trash.contentDescription = "Remove $name"
        drop.visibility = if (key == "connected" && canDrop) VISIBLE else GONE
        restyle()
        requestLayout()
    }

    /** The trailing signal of a Wi-Fi row. */
    fun signal(secure: Boolean, level: Int) { signal.visibility = VISIBLE; signal.set(secure, level) }

    fun restyle() {
        val b = UiTheme.base
        val connected = statusKey == "connected"
        // .saved-device.is-swipeable stays clear; its glass shadow still drops under the card
        box.solid(0); if (UiTheme.glassy) box.shadows = UiTheme.glass.glassyShadow
        trash.box.look(0xFFE5453B.toInt(), tinted = true)
        trashIcon.color = -1
        swipe.box.borderColor = if (connected) b.accentLine else on(0.06f)
        when {
            connected -> { swipe.box.solid(b.panelRowOn); if (UiTheme.glassy) swipe.box.shadows = UiTheme.glass.glassyShadow }
            UiTheme.glassy -> swipe.box.glassy(UiTheme.glass, b.panelRow)
            else -> swipe.box.solid(b.panelRow)
        }
        main.restyle()
        main.label.status.setTextColor(when (statusKey) { "connected" -> b.accent; "failed" -> b.danger; else -> on(0.62f) })
        drop.box.radius = 28f
        drop.setPadding((20 * resources.displayMetrics.density).roundToInt(), 0, (20 * resources.displayMetrics.density).roundToInt(), 0)
        drop.box.look(on(0.12f)); drop.restFill = drop.box.fill; drop.pressedFill = if (UiTheme.glassy) null else on(0.2f)
        drop.setTextColor(b.ink)
        reveal()
        invalidate(); swipe.invalidate(); trash.invalidate()
    }

    private fun shown(): Float = if (leaving) -REVEAL else if (dragging) offset else if (isOpen) -REVEAL else 0f

    /** `--reveal`: the key's glyph fades and grows in as the card uncovers it. */
    private fun reveal() {
        val p = (-(if (dragging) offset else if (isOpen) -REVEAL else 0f) / REVEAL).coerceIn(0f, 1f)
        trashIcon.alpha = p
        trashIcon.scaleX = 0.6f + 0.4f * p; trashIcon.scaleY = trashIcon.scaleX
    }

    private fun settle() {
        val d = resources.displayMetrics.density
        swipe.animate().translationX(shown() * d).setDuration(UiTheme.duration(340)).setInterpolator(Motion.interpolator(UiTheme.base.sheetIn)).start()
        reveal()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val d = resources.displayMetrics.density
        // the key sits behind the card: 1 px in from the top, right and bottom, 112 px wide
        trash.measure(MeasureSpec.makeMeasureSpec((112 * d).roundToInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(measuredHeight - 2 * max(1, d.roundToInt()), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val px = max(1, resources.displayMetrics.density.roundToInt())
        trash.layout(width - px - trash.measuredWidth, px, width - px, px + trash.measuredHeight)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        val d = resources.displayMetrics.density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { if (leaving) return false; downX = e.x; downY = e.y; from = if (isOpen) -REVEAL else 0f; sideways = null; swallow = false }
            MotionEvent.ACTION_MOVE -> if (sideways == null) {
                val dx = (e.x - downX) / d; val dy = (e.y - downY) / d
                if (abs(dx) > INTENT_PX && abs(dx) > abs(dy)) { sideways = true; dragging = true; parent.requestDisallowInterceptTouchEvent(true); return true }
                else if (abs(dy) > INTENT_PX) sideways = false
            }
            MotionEvent.ACTION_UP -> if (isOpen && e.x < width - REVEAL * d) { onOpen?.invoke(false); return true }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val d = resources.displayMetrics.density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return false
            MotionEvent.ACTION_MOVE -> if (dragging) {
                var x = from + (e.x - downX) / d
                if (x > 0f) x = min(OVERDRAG_PX, x * 0.25f)
                if (x < -REVEAL) x = max(-REVEAL - OVERDRAG_PX, -REVEAL + (x + REVEAL) * 0.25f)
                offset = x
                swipe.translationX = x * d
                reveal()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                val open = offset < -OPEN_AT
                onOpen?.invoke(open)
                if (open == isOpen) settle()
            }
        }
        return true
    }

    private fun remove() {
        if (leaving) return
        leaving = true
        val d = resources.displayMetrics.density
        // slides out as one with its key, then its height and the gap above close
        animate().translationX(-1.1f * width).setDuration(UiTheme.duration(LEAVE_MS)).setInterpolator(Motion.interpolator(ro.rdstr.ui.tokens.Bezier(0.4f, 0f, 1f, 1f))).start()
        handler.postDelayed({
            val start = height
            ValueAnimator.ofInt(start, 0).apply {
                duration = UiTheme.duration(COLLAPSE_MS); interpolator = Motion.interpolator(UiTheme.base.sheetIn)
                addUpdateListener { val p = layoutParams as Item; p.h = (it.animatedValue as Int) / d; requestLayout() }
                start()
            }
        }, UiTheme.duration(LEAVE_MS))
        handler.postDelayed({ onOpen?.invoke(false); onForget?.invoke() }, UiTheme.duration(LEAVE_MS + COLLAPSE_MS))
    }

    private companion object {
        const val REVEAL = 96f
        const val OPEN_AT = REVEAL / 2
        const val INTENT_PX = 8f
        const val OVERDRAG_PX = 10f
        const val LEAVE_MS = 260L
        const val COLLAPSE_MS = 300L
    }
}

/** A found phone or a network in range (`li.saved-device.is-found`): no swipe, one tap. */
internal class FoundRow(context: Context, iconRes: Int) : Flex(context, "saved-device") {
    val main = DeviceMain(context, iconRes)
    val signal = Signal(context)

    init {
        box.radius = 16f; box.border = 1f
        add(main, Item(grow = 1f, shrink = true))
        main.add(signal, Item(pushEnd = true))
        signal.visibility = GONE
        restyle()
    }

    fun restyle() {
        box.borderColor = on(0.06f)
        box.look(on(0.06f))
        main.restyle()
        main.label.status.setTextColor(on(0.62f))
        invalidate()
    }
}

/** `li.devices-empty`: a line in place of an empty list. */
internal fun emptyLine(context: Context) = text(context, "devices-empty", TextStyle(16f)).apply {
    val p = (16 * resources.displayMetrics.density).roundToInt(); setPadding(p, p, p, p)
}

/** A `p` that wraps; [balanced] spreads its lines evenly (`text-wrap: balance`). */
internal fun paragraph(context: Context, key: String, style: TextStyle, center: Boolean, balanced: Boolean = false) =
    text(context, key, style, lines = 8).apply {
        if (center) gravity = Gravity.CENTER_HORIZONTAL
        breakStrategy = if (balanced) Layout.BREAK_STRATEGY_BALANCED else Layout.BREAK_STRATEGY_SIMPLE
    }

/** `.devices-off`: the large disc, a heading and the line that says how to turn the radio on. */
internal class OffPanel(context: Context, title: String, line: String, iconRes: Int, bars: Boolean) : Flex(context, "devices-off", column = true) {
    val disc = DeviceIcon(context, iconRes, large = true, bars = bars)
    private val h3 = text(context, "h3", TextStyle(24f, 600)).apply { text = title }
    private val p = paragraph(context, "p", TextStyle(17f, lineHeight = 1.4f), center = true).apply { text = line }

    init {
        align = Align.CENTER; justify = Justify.CENTER; gap = 12f; pad(0f, 24f)
        add(disc, disc.item())
        add(h3, Item(mT = 12f))
        add(p)
        restyle()
    }

    fun restyle() { disc.restyle(); h3.setTextColor(UiTheme.base.ink); p.setTextColor(on(0.62f)) }
}

/** What the devices sheet shows; [connected] is the connected phone's name, [pairing] the code check waiting. */
data class DevicesState(
    val open: Boolean = false,
    val btOn: Boolean = false,
    val connected: String? = null,
    val saved: List<String> = emptyList(),
    val connecting: String? = null,
    val failed: String? = null,
    val scanning: Boolean = false,
    val searched: Boolean = false,
    val nearby: List<String> = emptyList(),
    val pairing: Pairing? = null,
) {
    data class Pairing(val name: String, val code: String, val confirmed: Boolean = false)
}

/**
 * DevicesSheet.tsx `DevicesSheet`: the phones the unit paired with, to connect, disconnect or forget, and a search for
 * new ones with the code check of a pairing; Bluetooth's switch in the head, a note in place of the lists while it is
 * off.
 */
@Mirrors("DevicesSheet.tsx")
@SuppressLint("ViewConstructor")
class DevicesSheetView(context: Context) : SheetFrame(context, "devices", "Devices") {
    /** Exactly the calls DevicesSheet.tsx makes. */
    interface Actions {
        fun setBluetooth(on: Boolean)
        /** `Bt.Connect`, `Bt.Disconnect`, `Bt.Forget`, `Bt.Scan`, `Bt.Pair`, `Bt.ConfirmPairing`. */
        fun connect(name: String)
        fun disconnect()
        fun forget(name: String)
        fun scan()
        fun pair(name: String)
        fun confirmPairing(accept: Boolean)
        /** `CloseDevices`. */
        fun close()
    }

    var actions: Actions? = null
    private var state = DevicesState()
    private var openRow: String? = null
    private val off = OffPanel(context, "Bluetooth is off", "Turn on Bluetooth to see your devices and connect a phone.", Icons.BLUETOOTH, bars = false)
    private val pairingView = Flex(context, "devices-pairing", column = true)
    private val pairIcon = DeviceIcon(context, Icons.DEVICE_MOBILE, large = true)
    private val pairTitle = text(context, "h3", TextStyle(24f, 600))
    private val pairLine = text(context, "p", TextStyle(17f)).apply { text = "Check that the phone shows the same code" }
    private val code = text(context, "device-code", TextStyle(56f, 600, 0.06f, 1f))
    private val answers = Flex(context, "devices-pairing-actions")
    private val cancel = TextButton(context, "devices-answer", TextStyle(18f, 600)).apply { text = "Cancel" }
    private val accept = TextButton(context, "devices-answer", TextStyle(18f, 600)).apply { text = "Pair" }
    private val scroll = Flex(context, "devices-scroll", column = true)
    private val scroller = ScrollView(context).apply { isVerticalScrollBarEnabled = true; overScrollMode = OVER_SCROLL_NEVER }
    private val content = Flex(context, null, column = true)
    private val savedList = Flex(context, "devices-list", column = true)
    private val nearbySection = Flex(context, "devices-nearby", column = true)
    private val nearbyTitle = text(context, "h3", TextStyle(16f, 600)).apply { text = "Nearby" }
    private val nearbyList = Flex(context, "devices-list", column = true)
    private val foot = Flex(context, "devices-foot", column = true)
    private val footLine = paragraph(context, "p", TextStyle(15f), center = true, balanced = true).apply { text = "New phone? Search here, or pair it from the phone's Bluetooth settings." }
    private val search = Flex(context, "devices-search")
    private val chip = Flex(context, "devices-search-chip")
    private val chipGlyph = icon(context, "icon.i-magnifying-glass", Icons.MAGNIFYING_GLASS, 28f)
    private val searchText = text(context, "span", TextStyle(18f, 600))
    private val rows = LinkedHashMap<String, SavedRow>()
    private var searchWiggle: ValueAnimator? = null
    private var ready = false

    init {
        scrim.setOnClickListener { actions?.close() }
        close.setOnClickListener { actions?.close() }
        switch.onChange = { actions?.setBluetooth(it) }
        switch.contentDescription = "Bluetooth"
        panel.add(off, Flex.Item(grow = 1f))
        panel.add(pairingView, Flex.Item(grow = 1f))
        pairingView.align = Flex.Align.CENTER; pairingView.justify = Flex.Justify.CENTER; pairingView.gap = 12f
        pairingView.add(pairIcon, pairIcon.item())
        pairingView.add(pairTitle, Flex.Item(mT = 12f))
        pairingView.add(pairLine)
        pairingView.add(code, Flex.Item(mT = 12f, mB = 20f))
        pairingView.add(answers, Flex.Item(alignSelf = Flex.Align.STRETCH))
        answers.gap = 12f
        answers.add(cancel, Flex.Item(grow = 1f, h = 64f, w = 0f))
        answers.add(accept, Flex.Item(grow = 1f, h = 64f, w = 0f))
        cancel.setOnClickListener { actions?.confirmPairing(false) }
        accept.setOnClickListener { actions?.confirmPairing(true) }
        panel.add(scroll, Flex.Item(grow = 1f, mL = -8f, mR = -8f))
        scroll.pad(0f, 8f)
        scroll.add(scroller, Flex.Item(grow = 1f))
        scroller.addView(content)
        content.add(savedList)
        savedList.gap = 12f
        content.add(nearbySection)
        nearbySection.add(nearbyTitle, Flex.Item(mT = 24f, mB = 12f))
        nearbySection.add(nearbyList)
        nearbyList.gap = 12f
        panel.add(foot)
        foot.gap = 12f; foot.padT = 8f
        foot.add(footLine)
        foot.add(search, Flex.Item(alignSelf = Flex.Align.CENTER, h = 64f))
        search.align = Flex.Align.CENTER; search.gap = 14f; search.pad(0f, 28f, 0f, 8f)
        search.box.radius = 32f; search.box.border = 1f
        search.isClickable = true
        search.setOnClickListener { actions?.scan() }
        search.add(chip, Flex.Item(w = 48f, h = 48f))
        chip.align = Flex.Align.CENTER; chip.justify = Flex.Justify.CENTER; chip.box.radius = 999f
        chip.add(chipGlyph, Flex.Item(w = 28f, h = 28f))
        search.add(searchText)
        search.onPressed = { restyle() }
        ready = true
        bind(state)
    }

    fun bind(s: DevicesState) {
        val was = state
        state = s
        switch.checked = s.btOn
        // closing the panel while the code is on screen answers no, and leaves no row open
        if (was.open && !s.open) { openRow = null; if (s.pairing != null && !s.pairing.confirmed) actions?.confirmPairing(false) }
        val pairing = s.pairing?.takeIf { !it.confirmed }
        off.visibility = if (!s.btOn) VISIBLE else GONE
        pairingView.visibility = if (s.btOn && pairing != null) VISIBLE else GONE
        val list = s.btOn && pairing == null
        scroll.visibility = if (list) VISIBLE else GONE
        foot.visibility = if (list) VISIBLE else GONE
        if (pairing != null) {
            pairTitle.text = "Pair with ${pairing.name}"
            code.text = pairing.code.take(3) + " " + pairing.code.drop(3)
        }
        // saved rows, kept by name so an open or leaving row keeps its state
        for (gone in rows.keys - s.saved.toSet()) savedList.removeView(rows.remove(gone))
        for ((i, name) in s.saved.withIndex()) {
            val row = rows.getOrPut(name) { SavedRow(context).also { r ->
                r.onSelect = { actions?.connect(name) }
                r.onForget = { actions?.forget(name) }
                r.onDrop = { actions?.disconnect() }
                r.onOpen = { o -> openRow = if (o) name else null; rows.forEach { (n, v) -> v.isOpen = n == openRow } }
            } }
            if (savedList.indexOfChild(row) != i) { savedList.removeView(row); savedList.addView(row, i, Flex.Item()) }
            val (key, text) = when {
                s.connected == name -> "connected" to "Connected"
                s.connecting == name -> "connecting" to "Connecting…"
                s.failed == name -> "failed" to "Not available. Turn on Bluetooth on the phone."
                else -> "saved" to "Saved"
            }
            row.show(name, key, text, s.connecting != null, canDrop = true)
            row.isOpen = openRow == name
        }
        nearbySection.visibility = if (s.searched) VISIBLE else GONE
        nearbyList.removeAllViews()
        for (name in s.nearby) {
            val row = FoundRow(context, Icons.DEVICE_MOBILE)
            row.main.label.name.text = name
            row.main.label.status.text = if (s.pairing?.name == name && s.pairing.confirmed) "Pairing…" else "Tap to pair"
            row.main.lock(s.pairing != null, false)
            row.main.contentDescription = "Pair with $name"
            row.main.setOnClickListener { actions?.pair(name) }
            nearbyList.add(row)
        }
        if (s.nearby.isEmpty()) nearbyList.add(emptyLine(context).apply { text = if (s.scanning) "Looking for phones with Bluetooth on…" else "No new phones found." })
        // a search scrolls the nearby phones into view
        if (s.scanning && !was.scanning) scroller.post { scroller.smoothScrollTo(0, nearbySection.top) }
        searchText.text = if (s.scanning) "Searching…" else "Search"
        search.isEnabled = !s.scanning
        wiggle(s.scanning)
        show(s.open)
        restyle()
        requestLayout()
    }

    /** `device-search`: the glyph swings 3 px each way every 1400 ms while a search runs. */
    private fun wiggle(on: Boolean) {
        if (on == (searchWiggle != null)) return
        searchWiggle?.cancel(); searchWiggle = null
        chipGlyph.translationX = 0f
        if (!on || UiTheme.speed <= 0f) return
        val d = resources.displayMetrics.density
        searchWiggle = ValueAnimator.ofFloat(-3f, 3f).apply {
            duration = UiTheme.duration(700); repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            interpolator = Motion.interpolator(UiTheme.base.seamless)
            addUpdateListener { chipGlyph.translationX = (it.animatedValue as Float) * d }
            start()
        }
    }

    override fun restyle() {
        // the frame restyles once while this class is not built yet
        if (!ready) return
        val b = UiTheme.base
        off.restyle()
        pairIcon.restyle()
        pairTitle.setTextColor(b.ink); pairLine.setTextColor(on(0.62f)); code.setTextColor(b.ink)
        for (a in listOf(cancel, accept)) { a.box.radius = 32f; a.box.border = 1f; a.box.borderColor = on(0.12f) }
        cancel.box.look(on(0.1f)); cancel.restFill = cancel.box.fill; cancel.pressedFill = on(0.18f); cancel.setTextColor(b.ink)
        // .devices-answer.is-primary keeps its white (by day ink) over the glass
        accept.box.solid(if (UiTheme.light) b.ink else -1); accept.box.borderColor = 0
        if (UiTheme.glassy) accept.box.shadows = UiTheme.glass.glassyShadow
        accept.restFill = accept.box.fill; accept.pressedFill = on(0.86f); accept.setTextColor(if (UiTheme.light) b.canvas else 0xFF111111.toInt())
        for (r in rows.values) r.restyle()
        for (i in 0 until nearbyList.childCount) (nearbyList.getChildAt(i) as? FoundRow)?.restyle() ?: (nearbyList.getChildAt(i) as KitText).setTextColor(on(0.62f))
        nearbyTitle.setTextColor(on(0.62f))
        footLine.setTextColor(on(0.55f))
        val pressed = search.isPressed && search.isEnabled
        search.box.borderColor = on(0.12f)
        search.box.look(if (pressed) on(0.18f) else on(0.1f))
        chip.box.look(if (pressed) on(0.24f) else on(0.14f))
        val ink = if (state.scanning) on(0.72f) else b.ink
        chipGlyph.color = ink; searchText.setTextColor(ink)
        invalidate()
    }
}
