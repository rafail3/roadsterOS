package ro.rdstr.ui.shell

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import ro.rdstr.car.App
import ro.rdstr.car.CallView
import ro.rdstr.car.CarEvent
import ro.rdstr.car.LaunchableApp
import ro.rdstr.car.RulesState
import ro.rdstr.car.Shell
import ro.rdstr.car.ShellState
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.R
import ro.rdstr.ui.kit.CssFilter
import ro.rdstr.ui.kit.Motion
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.uiKey

/** What the shell's own views show (Screen.tsx's reads of the emulator's state). */
data class ShellModel(
    val shell: ShellState = ShellState(),
    val car: RulesState = RulesState(),
    /** The time shown, epoch ms. */
    val now: Long = 0,
    val btOn: Boolean = false,
    val wifiOn: Boolean = false,
    /** The network the unit is on, and its bars 0..3. */
    val wifiSsid: String? = null,
    val wifiLevel: Int = 0,
    val phoneStatus: CarEvent.PhoneStatus? = null,
    /** core-car HomeApps.shown. */
    val homeApps: List<LaunchableApp> = emptyList(),
    val unread: Int = 0,
    /** The cover's top and bottom colours (CoverColors) while Bluetooth music with a cover is the source, else null. */
    val cover: Pair<Int, Int>? = null,
    /** Reverse gear's camera. */
    val camera: Boolean = false,
    /** The frame's volume key held now ("VOL_UP", "VOL_DOWN"), or null. */
    val pressedKey: String? = null,
)

/** Everything Screen.tsx itself dispatches or calls. */
interface ShellActions {
    fun userActivity()
    /** Screen.tsx `pressRail`: music, radio, phone, maps, camera. */
    fun pressRail(id: String)
    /** store.launchApp. */
    fun launchApp(id: String)
    fun openDrawer()
    fun closeDrawer()
    fun openVolume()
    fun moveHomeApp(id: String, by: Int)
    fun toggleHomeApp(id: String)
    fun wake()
    /** A touch on the status bar, for the drawer's drag (Drawer.tsx `useDrawerDrag`); true while it takes it. */
    fun barDrag(e: MotionEvent): Boolean
}

/**
 * Screen.tsx `div.screen`: the shell's root, 1280 x 750 canvas px. The grid's cells (the status bar across the top,
 * 56 px; under it the rail 112 px, the field and the panel 400 px with 24 px off its right and bottom) and every other
 * layer over the whole screen, drawn in [Layer] order; other screens' views go in with [put].
 *
 * State (`.screen` classes): opening an app, after 420 ms the rail and field zoom to 1.02 and fade to 0.6 (280 ms),
 * back at once going home; the screen coming on, the bar, rail, field and panel settle from 1.06 over 650 ms; above
 * full brightness the whole screen goes through one brightness and saturation filter.
 */
@Mirrors("Screen.tsx")
@SuppressLint("ViewConstructor")
class ShellView(context: Context, private val actions: ShellActions) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    /** The clock block's place in the field ([Layer.CLOCK]). */
    val clockSlot = FrameLayout(context).apply { clipChildren = false }
    /** The always-on display's dial ([Layer.AOD_DIAL]). */
    val aodDialSlot = FrameLayout(context)

    private val ambient = Ambient(context)
    val bar = StatusBar(context, actions)
    private val rail = Rail(context) { actions.pressRail(it) }
    private val apps = HomeApps(context, actions)
    private val field = Field(context, clockSlot, apps)
    private val panel = Cell(context).apply { uiKey = "panel" }
    private val scrim = Scrim(context) { actions.closeDrawer() }
    private val bulges = Bulges(context)
    private val dimmer = Dimmer(context)
    private val aod = AlwaysOn(context, aodDialSlot) { actions.userActivity() }
    private val screenOff = ScreenOff(context) { actions.wake() }
    private val cells = listOf(Cell(context).apply { addView(bar) }, Cell(context).apply { addView(rail) }, Cell(context).apply { addView(field) }, panel)
    private val barCell get() = cells[0]
    private val railCell get() = cells[1]
    private val fieldCell get() = cells[2]
    private var inApp = false
    private var screenOn = true
    private val boost = Paint()
    private var boosted = false

    init {
        uiKey = null
        clipChildren = false
        put(Layer.AMBIENT, ambient)
        put(Layer.BAR, barCell)
        put(Layer.RAIL, railCell)
        put(Layer.FIELD, fieldCell)
        put(Layer.PANEL, panel)
        put(Layer.SCRIM, scrim)
        put(Layer.BULGES, bulges)
        put(Layer.DIMMER, dimmer)
        put(Layer.AOD, aod)
        put(Layer.SCREEN_OFF, screenOff)
    }

    /** Puts [view] at [layer], over the views already in that layer; [Layer.PANEL] puts it in the panel's cell. */
    fun put(layer: Layer, view: View) {
        when (layer) {
            Layer.CLOCK -> clockSlot.addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
            Layer.AOD_DIAL -> aodDialSlot.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            Layer.PANEL -> if (view !== panel) { panel.addView(view, LayoutParams(-1, -1)); return } else add(layer, view)
            else -> add(layer, view)
        }
    }

    private fun add(layer: Layer, view: View) {
        view.setTag(R.id.rdstr_layer, layer)
        var at = childCount
        while (at > 0 && (getChildAt(at - 1).getTag(R.id.rdstr_layer) as Layer).ordinal > layer.ordinal) at--
        addView(view, at, view.layoutParams as? LayoutParams ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The drawer clears the screen while its brightness slider is held (DrawerView.onClearing): no scrim then. */
    fun clearScrim(on: Boolean) = scrim.clear(on)

    /** Takes [view] out of the shell. */
    fun remove(view: View) = (view.parent as? ViewGroup)?.removeView(view)

    fun bind(m: ShellModel) {
        val s = m.shell
        val callFull = m.car.call != CarEvent.Call.State.IDLE && s.callView == CallView.FULL
        val camera = m.camera || s.cameraOpen
        val covered = s.drawer || s.recents || callFull || camera
        val app = s.app != App.HOME
        bar.bind(m)
        barCell.inert = covered
        railCell.inert = covered || app
        fieldCell.inert = covered || app
        panel.inert = covered || app
        if (app != inApp) {
            inApp = app
            for (c in listOf(railCell, fieldCell)) {
                c.animate().cancel()
                c.animate().scaleX(if (app) 1.02f else 1f).scaleY(if (app) 1.02f else 1f).alpha(if (app) 0.6f else 1f)
                    .setStartDelay(if (app) UiTheme.duration(420) else 0).setDuration(UiTheme.duration(280)).setInterpolator(Motion.interpolator(Motion.easeOut)).start()
            }
        }
        apps.bind(m.homeApps, m.unread)
        ambient.glow(m.cover)
        scrim.bind(s.drawer)
        bulges.bind(m.pressedKey)
        val veil = Shell.veil(s)
        dimmer.bind(veil.dark.toFloat())
        val boost = veil.brightness > 1
        if (boost != boosted || boost) {
            boosted = boost
            if (boost) {
                this.boost.colorFilter = ColorMatrixColorFilter(CssFilter.saturateBrightness(veil.saturation.toFloat(), veil.brightness.toFloat()))
                setLayerType(LAYER_TYPE_HARDWARE, this.boost)
            } else setLayerType(LAYER_TYPE_NONE, null)
        }
        aod.bind(s.aod, m.now, Shell.aodFace(s), s.clock24h)
        screenOff.bind(!s.screenOn)
        if (s.screenOn && !screenOn) wakeZoom()
        screenOn = s.screenOn
    }

    /** `wake-zoom`: the bar, rail, field and panel settle from 1.06 over 650 ms. */
    private fun wakeZoom() {
        if (UiTheme.speed <= 0f) return
        for (c in cells) {
            c.scaleX = 1.06f; c.scaleY = 1.06f
            c.animate().scaleX(if (inApp && c !== barCell && c !== panel) 1.02f else 1f).scaleY(if (inApp && c !== barCell && c !== panel) 1.02f else 1f)
                .setStartDelay(0).setDuration(UiTheme.duration(650)).setInterpolator(Motion.interpolator(Motion.easeOut)).start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (screenOn) wakeZoom() }

    private val hit = Rect()

    /** Sees every touch on the screen first (the developer overlays' dots). */
    var touchSpy: ((MotionEvent) -> Unit)? = null

    // a touch anywhere outside the row of icons ends their arranging
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        touchSpy?.invoke(e)
        if (e.actionMasked == MotionEvent.ACTION_DOWN && apps.arranging) {
            apps.getGlobalVisibleRect(hit)
            if (!hit.contains(e.rawX.toInt(), e.rawY.toInt())) apps.endArranging()
        }
        return super.dispatchTouchEvent(e)
    }

    /** The grid: bar 56 px across; rail 112, field the rest, panel 400 less 24 at its right and bottom. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val r = cell(c, w, h)
            c.measure(MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val box = cell(c, r - l, b - t)
            c.layout(box.left, box.top, box.right, box.bottom)
        }
    }

    private val box = Rect()

    private fun cell(c: View, w: Int, h: Int): Rect {
        val bar = (56 * d + 0.5f).toInt()
        val rail = (112 * d + 0.5f).toInt()
        val side = (400 * d + 0.5f).toInt()
        val gap = (24 * d + 0.5f).toInt()
        when (c) {
            barCell -> box.set(0, 0, w, bar)
            railCell -> box.set(0, bar, rail, h)
            fieldCell -> box.set(rail, bar, w - side, h)
            panel -> box.set(w - side, bar, w - gap, h - gap)
            else -> box.set(0, 0, w, h)
        }
        return box
    }

    /** A grid cell; `inert` lets every touch through to what is under it. */
    private class Cell(context: Context) : FrameLayout(context) {
        var inert = false
        init { clipChildren = false }
        override fun dispatchTouchEvent(e: MotionEvent): Boolean = if (inert) false else super.dispatchTouchEvent(e)
    }
}
