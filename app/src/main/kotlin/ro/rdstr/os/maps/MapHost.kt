package ro.rdstr.os.maps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.ViewGroup
import java.util.Calendar
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ro.rdstr.car.Shell
import ro.rdstr.car.Theme
import ro.rdstr.map.MapBorders
import ro.rdstr.map.PackMask
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.Emulates
import ro.rdstr.car.MapPacks as Packs

/**
 * The one map of the unit: made once, and it lives either in the open Maps screen or on the card on home, moved
 * between the two as a view, never made twice. Main thread only.
 *
 * The screens drive the map through [view]: [MapView.setCar] on each position, [MapView.follow], [MapView.headingUp],
 * [MapView.zoomBy], [MapView.recentre], and listen with [MapView.onChange] (street, limit, follow) and
 * [MapView.onRotate] (compass). The host keeps the map's colours on the shell's theme ([light]) and its cover on the
 * installed packs by itself.
 */
object MapHost {
    enum class Slot { APP, CARD }

    private var view: MapView? = null
    private var packs: MapPacks? = null
    private val slots = arrayOfNulls<ViewGroup>(Slot.entries.size)
    private var inApp = false
    private var cardInView = false
    private var moved: (() -> Unit)? = null
    private val dayTheme = MutableStateFlow(false)

    /**
     * The map in its day colours, as the shell's theme is now (Screen.tsx `light = effectiveTheme(shell, hour) ===
     * "LIGHT"`, given to MapsApp): the chosen theme, or in the automatic one the clock's hour, checked on every
     * minute and clock change. The screens' day variants can read it too.
     */
    val light: StateFlow<Boolean> = dayTheme

    /** Every country's outline, read once: the country under the car, and the mask over those without a pack. */
    val borders: List<Packs.Country> by lazy { Packs.parseBorders(MapBorders.geojson()) }
    private val mask by lazy { PackMask(borders.map { it.code to it.polygons }) }

    fun packs(context: Context): MapPacks = packs ?: MapPacks(MapPacks.dir(context)).also { packs = it }

    /**
     * The map's view, made at the first call with the application's context, so it can outlive any screen. The street
     * pill and the speed dial the emulator lays over it are the screens' own views beside it.
     */
    @Emulates("mapHost.mapHost")
    fun view(context: Context): MapView = view ?: MapView(context.applicationContext, packs(context)).also {
        view = it
        val app = context.applicationContext
        followTheme(app)
        installed(CarRuntime.ui.value.maps.installed)
        it.seen = inApp || cardInView
    }

    /** The Maps screen and the card each say where the map goes in them; a slot that leaves passes null. */
    @Emulates("mapHost.setSlot")
    fun setSlot(slot: Slot, parent: ViewGroup?) {
        val old = slots[slot.ordinal]
        slots[slot.ordinal] = parent
        // a parent going away must not keep the map, nor the map keep it
        view?.let { v -> if (parent == null && old != null && v.parent === old) { old.removeView(v); v.dropPictures() } }
        place()
    }

    /** The open Maps screen takes the map; closed, the card has it. */
    @Emulates("mapHost.showMapIn")
    fun showMapIn(app: Boolean) {
        inApp = app
        place()
        view?.seen = inApp || cardInView
    }

    /**
     * The home card's map page is on screen (MapsApp.tsx `inCard`: the card shows NAVIGATION and the screen is on).
     * With the Maps screen open or this, the map works; else it does nothing until seen again.
     */
    fun cardInView(inView: Boolean) {
        cardInView = inView
        view?.seen = inApp || cardInView
    }

    /** Called after the map moved into the other slot, laid out there; landed on the card, it already follows the car. */
    @Emulates("mapHost.onMapMoved")
    fun onMapMoved(f: (() -> Unit)?) { moved = f }

    /**
     * The map's look: day or night colours, and the countries without a pack covered. The unit always draws from its
     * packs; the emulator can preview the same map from the network. The host calls it on every theme change.
     */
    @Emulates("mapStyle.mapStyle")
    fun style(light: Boolean) {
        dayTheme.value = light
        view?.night = !light
    }

    /** A pack was downloaded or removed: the map reads the packs again and draws what changed; the cover follows [installed]. */
    fun packsChanged() {
        packs?.reload()
        view?.packsChanged()
    }

    private var following = false

    /**
     * The theme and the installed packs as they change, for as long as the process runs: the emulator restyles the
     * map on `light` and `maps.installed` (MapsApp.tsx `setStyle(mapStyle(light, missing, offline))`).
     */
    private fun followTheme(app: Context) {
        if (following) return
        following = true
        val scope = MainScope()
        scope.launch { CarRuntime.shell.map { it.theme }.distinctUntilChanged().collect { applyTheme() } }
        scope.launch { CarRuntime.ui.map { it.maps.installed }.distinctUntilChanged().collect { installed(it) } }
        val clock = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = applyTheme() }
        app.registerReceiver(clock, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        applyTheme()
    }

    private fun applyTheme() =
        style(Shell.effectiveTheme(CarRuntime.shell.value, Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) == Theme.LIGHT)

    /** MapsApp.tsx `missing`: the borders whose code is not in `maps.installed`. */
    private fun installed(codes: List<String>) {
        if (mask.update(codes)) view?.missing = mask.missing
    }

    private fun place() {
        val target = slots[(if (inApp) Slot.APP else Slot.CARD).ordinal] ?: return
        val v = view(target.context)
        if (v.parent === target) return
        (v.parent as? ViewGroup)?.removeView(v)
        target.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // MapsApp.tsx onMapMoved: `map.resize(); if (!open) setFollow(true)`; addView lays the map out again
        if (!inApp) v.follow = true
        moved?.invoke()
    }
}
