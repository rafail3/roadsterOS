package ro.rdstr.ui.drawer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewGroup
import ro.rdstr.car.Shell
import ro.rdstr.car.ThemeMode
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.R
import ro.rdstr.ui.kit.CssColor
import ro.rdstr.ui.kit.Easing
import ro.rdstr.ui.kit.IconView
import ro.rdstr.ui.kit.KitPillSlider
import ro.rdstr.ui.kit.KitText
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.Scope
import ro.rdstr.ui.kit.TextStyle
import ro.rdstr.ui.kit.UiScale
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.sheets.Flex
import ro.rdstr.ui.sheets.icon
import ro.rdstr.ui.sheets.text
import ro.rdstr.ui.tokens.Base
import ro.rdstr.ui.tokens.Icons
import ro.rdstr.ui.tokens.Shadow
import ro.rdstr.ui.tokens.ToggleBt
import ro.rdstr.ui.tokens.ToggleLocation
import ro.rdstr.ui.tokens.ToggleMute
import ro.rdstr.ui.tokens.ToggleTheme
import ro.rdstr.ui.tokens.ToggleWifi
import ro.rdstr.ui.uiKey
import kotlin.math.min
import kotlin.math.roundToInt

/** What the drawer shows. [btDevice] is the connected phone's name, null with none; [wifiConnected] the joined network. */
data class DrawerState(
    val open: Boolean = false,
    val btOn: Boolean = false,
    val btDevice: String? = null,
    val wifiOn: Boolean = false,
    val wifiConnected: String? = null,
    val locationOn: Boolean = false,
    val muted: Boolean = false,
    val volume: Int = 0,
    val theme: ThemeMode = ThemeMode.DARK,
    val brightness: Int = 100,
)

/**
 * VolumeHud.tsx `useMuteFill`, provided with the volume panel: the transition (duration, easing) the volume fill takes
 * while mute changes, or null for the slider's own 120 ms.
 */
fun interface MuteFill {
    fun transition(muted: Boolean): Pair<Long, Easing>?
}

/**
 * Drawer.tsx `Drawer`: the quick settings pulled down from the status bar: six toggles, the brightness and volume
 * sliders and the closing handle (`section.drawer`, screen.css). Held, Bluetooth and Wi-Fi open their sheets and
 * Settings asks to restart. Holding the brightness slider clears everything else, so only the level shows over the
 * real screen ([onClearing] tells the screen to drop its scrim).
 */
@Mirrors("Drawer.tsx")
@SuppressLint("ViewConstructor")
class DrawerView(context: Context) : Flex(context, "drawer", column = true) {
    /** Exactly the calls Drawer.tsx makes. */
    interface Actions {
        /** `setBluetooth(on)` then `RadioSwitched(BLUETOOTH, on)`. */
        fun bluetooth(on: Boolean)
        /** `setWifi(on)` then `RadioSwitched(WIFI, on)`; with location off only the location prompt, the sheet after it. */
        fun wifi(on: Boolean)
        fun location(on: Boolean)
        /** `Audio.Mute`. */
        fun mute(on: Boolean)
        fun cycleTheme()
        /** `OpenApp(SETTINGS)`. */
        fun openSettings()
        /** `askRestart(true)`. */
        fun askRestart()
        fun openDevices()
        fun openNetworks()
        fun setBrightness(value: Int)
        fun setVolume(level: Int)
        fun close()
        /** `DrawerReleased(fraction)`. */
        fun released(fraction: Double)
    }

    var actions: Actions? = null
    var muteFill: MuteFill = DefaultMuteFill()
    /** The brightness slider is held: the screen's scrim goes while it is. */
    var onClearing: ((Boolean) -> Unit)? = null

    private var state = DrawerState()
    private val grid = DrawerGrid(context)
    private val bt = Toggle(context, "bt", Icons.BLUETOOTH, "Bluetooth")
    private val wifi = Toggle(context, "wifi", Icons.WIFI_HIGH, "Wi-Fi")
    private val location = Toggle(context, "location", Icons.NAVIGATION_ARROW_FILL, "Location")
    private val mute = Toggle(context, "mute", Icons.SPEAKER_SLASH_FILL, "Mute")
    private val theme = Toggle(context, "theme", Icons.MOON_FILL, "Theme")
    private val settings = Toggle(context, "settings", 0, "Settings")
    private val lightRow = Flex(context, "pill-slider.slider-light")
    private val light = KitPillSlider(context, Scope.SCREEN)
    private val volumeRow = Flex(context, "pill-slider")
    private val volume = KitPillSlider(context, Scope.SCREEN)
    private val handle = Flex(context, "handle.handle-bottom")
    private val handleLine = Flex(context, "handle-line")
    private val line = Paint()
    private var held = false
    private var dragging = false

    init {
        setWillNotDraw(false)
        pad(32f, 48f, 12f, 48f)
        gap = 16f
        add(grid, Item(h = 72f))
        for (t in listOf(bt, wifi, location, mute, theme, settings)) grid.addView(t)
        bt.onTap = { val on = !state.btOn; actions?.bluetooth(on) }
        bt.onHold = { actions?.openDevices() }
        wifi.onTap = { val on = !state.wifiOn; actions?.wifi(on) }
        wifi.onHold = { actions?.openNetworks() }
        location.onTap = { actions?.location(!state.locationOn) }
        mute.onTap = { actions?.mute(!state.muted) }
        theme.onTap = { actions?.cycleTheme() }
        settings.onTap = { actions?.openSettings() }
        settings.onHold = { actions?.askRestart() }

        lightRow.box.radius = 16f
        lightRow.add(light, Item(grow = 1f, h = 56f))
        lightRow.align = Align.CENTER
        volumeRow.align = Align.CENTER
        light.min = Shell.MIN_BRIGHTNESS
        light.max = Shell.MAX_BRIGHTNESS
        light.setIcon(Icons.SUN_FILL)
        light.onValue = { actions?.setBrightness(it) }
        light.onReadoutClick = { actions?.setBrightness(100) }
        light.onHeld = ::hold
        add(lightRow)

        volumeRow.add(volume, Item(grow = 1f, h = 56f))
        volume.min = 0
        volume.max = ro.rdstr.car.Rules.MAX_VOLUME
        volume.setIcon(Icons.SPEAKER_HIGH_FILL)
        volume.onValue = { actions?.setVolume(it) }
        volume.onIconClick = { actions?.mute(!state.muted) }
        add(volumeRow)

        handle.isClickable = true
        handle.setOnClickListener { actions?.close() }
        handle.onPressed = { handleLine.box.fill = if (it) UiTheme.base.inkMuted else UiTheme.base.inkSubtle; handleLine.invalidate() }
        handle.align = Align.CENTER
        handle.justify = Justify.CENTER
        handle.add(handleLine, Item(w = 48f, h = 4f))
        handleLine.box.radius = 2f
        add(handle, Item(w = 160f, h = 56f, alignSelf = Align.CENTER))
        UiTheme.link(this) { restyle() }
        restyle()
        bind(state)
    }

    fun bind(s: DrawerState) {
        val was = state
        state = s
        bt.show(s.btOn, if (!s.btOn) "Off" else s.btDevice ?: "On")
        wifi.show(s.wifiOn, if (!s.wifiOn) "Off" else s.wifiConnected ?: "On")
        location.show(s.locationOn, if (s.locationOn) "On" else "Off")
        mute.show(s.muted, if (s.muted) "On" else "Off")
        theme.setIcon(when (s.theme) { ThemeMode.AUTO -> Icons.CIRCLE_HALF; ThemeMode.LIGHT -> Icons.SUN_FILL; ThemeMode.DARK -> Icons.MOON_FILL })
        theme.show(s.theme == ThemeMode.AUTO, when (s.theme) { ThemeMode.AUTO -> "Auto"; ThemeMode.LIGHT -> "Day"; ThemeMode.DARK -> "Night" })
        light.value = s.brightness
        light.readout = "${s.brightness}%"
        val fill = muteFill.transition(s.muted)
        volume.fillMs = fill?.first ?: 120L
        volume.fillEase = fill?.second ?: Easing.LINEAR
        volume.muted = s.muted
        volume.value = if (s.muted) 0 else s.volume
        volume.readout = if (s.muted) "Muted" else s.volume.toString()
        volume.setIcon(if (s.muted) Icons.SPEAKER_SLASH_FILL else Icons.SPEAKER_HIGH_FILL)
        light.contentDescription = "Brightness ${s.brightness} percent"
        volume.contentDescription = if (s.muted) "Muted" else s.volume.toString()
        if (was.open != s.open || !isLaidOut) slide(s.open)
    }

    // the slide: 200 ms --ease-out from where it is; hidden fully above the screen when closed
    private fun slide(open: Boolean) {
        if (dragging) return
        animate().cancel()
        val to = if (open) 0f else -height.toFloat().coerceAtLeast(1f)
        if (!isLaidOut || !isAttachedToWindow || UiTheme.speed <= 0f) { translationY = if (open) 0f else -10000f; return }
        animate().translationY(to).setDuration(UiTheme.duration(UiTheme.base.base)).setInterpolator(Motion.interpolator(UiTheme.base.easeOut)).start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!state.open && !dragging) translationY = -h.toFloat()
    }

    private fun hold(on: Boolean) {
        if (held == on) return
        held = on
        restyle()
        val dur = UiTheme.duration(UiTheme.base.fast)
        for (v in listOf(grid, volumeRow, handle)) v.animate().alpha(if (on) 0f else 1f).setDuration(dur).setInterpolator(Motion.interpolator(UiTheme.base.easeOut)).start()
        onClearing?.invoke(on)
    }

    private fun restyle() {
        val b = UiTheme.base
        if (held) box.solid(0)
        else if (UiTheme.glassy) box.glassy(UiTheme.glass) else box.solid(b.sheet)
        // .slider-light.is-held: the sheet around the slider, 12 px out
        if (held) { lightRow.box.solid(b.sheet); lightRow.box.shadows = listOf(Shadow(false, 0f, 0f, 0f, 12f, b.sheet)) } else lightRow.box.solid(0)
        handleLine.box.fill = if (handle.isPressed) b.inkMuted else b.inkSubtle
        // .pill-slider.is-glass { height: var(--pill) }: the row is the slider's 48 px, the readout overflows it
        val row = if (UiTheme.glassy) 48f else 56f
        for (r in listOf(lightRow, volumeRow)) { val p = r.layoutParams as? Item; if (p != null && p.h != row) { p.h = row; r.requestLayout() } }
        (lightRow.getChildAt(0).layoutParams as Item).h = row; (volumeRow.getChildAt(0).layoutParams as Item).h = row
        for (t in listOf(bt, wifi, location, mute, theme, settings)) t.restyle()
        invalidate(); lightRow.invalidate(); handleLine.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // border-bottom: 1px, inside the box
        padB = 12f + UiScale.borderPx(1f, d) / d
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onDraw(canvas: Canvas) {
        if (held) return
        line.color = UiTheme.base.hairlineStrong
        canvas.drawRect(0f, height - UiScale.borderPx(1f, d), width.toFloat(), height.toFloat(), line)
    }

    /**
     * `useDrawerDrag`: the status bar pulls the drawer down. The screen feeds the bar's touches here: [intercept] from
     * the bar's `onInterceptTouchEvent` (true once the pull passes [DRAG_THRESHOLD], which cancels the press on the
     * bar's handle, so a pull never also counts as a tap) and [onTouch] from its `onTouchEvent`. The drawer follows
     * the finger from fully hidden, never past fully open; the release sends `DrawerReleased` and the slide finishes
     * from where it was let go.
     */
    inner class Drag {
        private var downY = Float.NaN
        private var distance = 0f

        fun intercept(e: MotionEvent): Boolean { onTouch(e); return dragging }

        fun onTouch(e: MotionEvent): Boolean {
            if (state.open && !dragging && e.actionMasked == MotionEvent.ACTION_DOWN) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; distance = 0f }
                MotionEvent.ACTION_MOVE -> if (!downY.isNaN()) {
                    distance = (e.rawY - downY) / d
                    if (distance >= DRAG_THRESHOLD) {
                        if (!dragging) { dragging = true; animate().cancel() }
                        translationY = (min(height.toFloat(), distance * d) - height)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!downY.isNaN()) {
                    downY = Float.NaN
                    val was = dragging
                    dragging = false
                    if (distance >= DRAG_THRESHOLD) {
                        actions?.released((distance / (height / d)).toDouble())
                        // the shell's answer may leave `open` unchanged: the slide still finishes
                        post { slide(state.open) }
                    }
                    return was
                }
            }
            return dragging
        }
    }

    val drag = Drag()

    private companion object {
        const val DRAG_THRESHOLD = 6f
    }
}

/** `useMuteFill`: for 480 ms after mute changes, the fill moves in 480 ms on `cubic-bezier(0.33, 1, 0.68, 1)`. */
class DefaultMuteFill : MuteFill {
    private var shown: Boolean? = null
    private var changedAt = Long.MIN_VALUE
    override fun transition(muted: Boolean): Pair<Long, Easing>? {
        val now = Motion.now()
        if (shown != null && shown != muted) changedAt = now
        shown = muted
        return if (now - changedAt < MS && UiTheme.speed > 0f) MS to EASE else null
    }
    private companion object {
        const val MS = 480L
        val EASE: Easing = ro.rdstr.ui.kit.CssBezier(0.33f, 1f, 0.68f, 1f)
    }
}

/**
 * `.drawer-grid`: six columns of `1fr` with 16 px between, one row. A `1fr` column never goes under its toggle's
 * min-content width (a long state line keeps its toggle wider), the others share what is left equally.
 */
@SuppressLint("ViewConstructor")
internal class DrawerGrid(context: Context) : Flex(context, "drawer-grid") {
    private val items = List(6) { Item() }
    private var xs = FloatArray(0)
    private var ws = FloatArray(0)


    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val dd = d
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) return setMeasuredDimension(0, (TOGGLE_H * dd).roundToInt())
        val total = outerWidth(widthMeasureSpec)
        val n = childCount
        val mins = FloatArray(n) { i -> (getChildAt(i) as Toggle).minContent() }
        val fixed = BooleanArray(n)
        val avail = total - GAP * (n - 1)
        var share: Float
        while (true) {
            val rest = avail - (0 until n).filter { fixed[it] }.sumOf { mins[it].toDouble() }.toFloat()
            val free = (0 until n).count { !fixed[it] }
            share = if (free == 0) 0f else rest / free
            val grow = (0 until n).filter { !fixed[it] && mins[it] > share }
            if (grow.isEmpty()) break
            grow.forEach { fixed[it] = true }
        }
        ws = FloatArray(n) { if (fixed[it]) mins[it] else share }
        xs = FloatArray(n)
        var x = 0f
        for (i in 0 until n) { xs[i] = x; x += ws[i] + GAP }
        for (i in 0 until n) Flex.snap(getChildAt(i), items[i], xs[i] + errX, errY, ws[i], TOGGLE_H, dd)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize((TOGGLE_H * dd).roundToInt(), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            v.layout(items[i].px, items[i].py, items[i].px + v.measuredWidth, items[i].py + v.measuredHeight)
        }
    }

    private companion object {
        const val GAP = 16f
        const val TOGGLE_H = 72f
    }
}

/**
 * Drawer.tsx `Toggle`: an icon disc, its name and its state in a 72 px pill (`.toggle`), the function's colour while
 * on (`--toggle-on`); a tap switches, a hold of [LONG_PRESS_MS] opens its details instead and the tap that ends it
 * is swallowed. Pressed it shrinks to 0.95 (120 ms) and springs back (420 ms, `--spring-glyph`). [kind] "settings"
 * is the gear image with its name only.
 */
@SuppressLint("ViewConstructor")
internal class Toggle(context: Context, private val kind: String, iconRes: Int, title: String) : Flex(context, "toggle.toggle-$kind") {
    var onTap: (() -> Unit)? = null
    var onHold: (() -> Unit)? = null
    private var on = false
    private val disc = Flex(context, "toggle-disc")
    private val glyph: IconView? = if (iconRes != 0) icon(context, "icon", iconRes, 30f) else null
    private val textBox = Flex(context, "toggle-text", column = true)
    private val title: KitText = text(context, "toggle-title", TextStyle(16f, 600, lineHeight = 1.2f)).apply { text = title }
    private val stateText: KitText? = if (kind != "settings") text(context, "toggle-state", TextStyle(14f, 500, lineHeight = 1.2f)) else null
    private val handler = Handler(Looper.getMainLooper())
    private var fired = false
    private val longPress = Runnable { fired = true; onHold?.invoke() }
    private var iconKey = 0

    init {
        isClickable = true
        align = Align.CENTER
        gap = 16f
        pad(0f, 24f, 0f, 32f)
        box.radius = 999f
        pressScale = 0.95f; pressInMs = 120L; pressOutMs = 420L
        add(disc, Item(w = 30f, h = 30f))
        disc.align = Align.CENTER; disc.justify = Justify.CENTER
        if (glyph != null) { disc.add(glyph, Item(w = 30f, h = 30f)); setIcon(iconRes) }
        else { disc.box.image = BitmapFactory.decodeResource(resources, R.drawable.rdstr_settings_gear) }
        add(textBox, Item(shrink = true))
        textBox.add(this.title)
        stateText?.let { textBox.add(it) }
        setOnClickListener { if (fired) fired = false else onTap?.invoke() }
    }

    fun setIcon(res: Int) {
        if (glyph == null || res == iconKey) return
        iconKey = res
        glyph.setIcon(res)
        glyph.uiKey = "icon." + resources.getResourceEntryName(res).removePrefix("rdstr_icon_").let { "i-" + it.replace('_', '-') }
    }

    fun show(on: Boolean, state: String) {
        val changed = on != this.on || stateText?.text?.toString() != state
        this.on = on
        stateText?.text = state
        isSelected = on
        contentDescription = "${title.text}, $state"
        if (changed) { restyle(); requestLayout() }
    }

    /** The narrowest the toggle lays out: its padding, disc, gap and the longer of its two lines. */
    fun minContent(): Float {
        val w = listOfNotNull(title, stateText).maxOf { android.text.Layout.getDesiredWidth(it.text, it.paint) } / d
        return 32f + 30f + 16f + w + 24f
    }

    fun restyle() {
        val b = UiTheme.base
        val light = UiTheme.light
        val onColor = when (kind) {
            "bt" -> ToggleBt.DARK.toggleOn; "wifi" -> ToggleWifi.DARK.toggleOn; "location" -> ToggleLocation.DARK.toggleOn
            "mute" -> ToggleMute.DARK.toggleOn; "theme" -> ToggleTheme.DARK.toggleOn; else -> 0
        }
        val g = UiTheme.glass
        when {
            // .screen.light .toggle(.is-on) outranks the glass, which only keeps its frost there
            light && on -> { box.solid(onColor); box.shadows = listOf(Shadow(false, 0f, 1f, 2f, 0f, 0x2E141C26)) }
            light -> { box.solid(0x12141C26); box.shadows = listOf(Shadow(true, 0f, 1f, 0f, 0f, 0x99FFFFFF.toInt())); if (UiTheme.glassy) box.frost = g.glassyFrost }
            UiTheme.glassy && on -> box.glassy(g, CssColor.fade(onColor, 0.86f))
            UiTheme.glassy -> box.glassy(g)
            on -> { box.solid(onColor); box.shadows = listOf(Shadow(true, 0f, 1f, 0f, 0f, 0x38FFFFFF)) }
            else -> { box.solid(0x24FFFFFF); box.shadows = listOf(Shadow(true, 0f, 1f, 0f, 0f, 0x24FFFFFF)) }
        }
        val ink = if (light && !on) b.ink else 0xFFFFFFFF.toInt()
        glyph?.color = ink
        title.setTextColor(ink)
        stateText?.setTextColor(ink)
        stateText?.alpha = if (on) 0.82f else 0.62f
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { fired = false; if (onHold != null) handler.postDelayed(longPress, LONG_PRESS_MS) }
            MotionEvent.ACTION_MOVE -> if (event.x < 0 || event.y < 0 || event.x > width || event.y > height) handler.removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        const val LONG_PRESS_MS = 500L
    }
}
