package ro.rdstr.os.maps

import android.content.Context
import android.view.ViewGroup
import ro.rdstr.map.MapBorders
import ro.rdstr.ui.Emulates
import ro.rdstr.car.MapPacks as Packs

/**
 * The one map of the unit: made once, and it lives either in the open Maps screen or on the card on home, moved
 * between the two as a view, never made twice. Main thread only.
 */
object MapHost {
    enum class Slot { APP, CARD }

    private var view: MapView? = null
    private var packs: MapPacks? = null
    private val slots = arrayOfNulls<ViewGroup>(Slot.entries.size)
    private var inApp = false
    private var moved: (() -> Unit)? = null
    private var light = false

    /** Every country's outline, read once: the country under the car, and the mask over those without a pack. */
    val borders: List<Packs.Country> by lazy { Packs.parseBorders(MapBorders.geojson()) }

    fun packs(context: Context): MapPacks = packs ?: MapPacks(MapPacks.dir(context)).also { packs = it }

    /**
     * The map's view, made at the first call with the application's context, so it can outlive any screen. The street
     * pill and the speed dial the emulator lays over it are the screens' own views beside it.
     */
    @Emulates("mapHost.mapHost")
    fun view(context: Context): MapView = view ?: MapView(context.applicationContext, packs(context)).also {
        it.night = !light
        it.missing = missing()
        view = it
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
    }

    @Emulates("mapHost.onMapMoved")
    fun onMapMoved(f: (() -> Unit)?) { moved = f }

    /**
     * The map's look: day or night colours, and the countries without a pack covered. The unit always draws from its
     * packs; the emulator can preview the same map from the network.
     */
    @Emulates("mapStyle.mapStyle")
    fun style(light: Boolean) {
        this.light = light
        view?.let { it.night = !light; it.missing = missing() }
    }

    /** A pack was downloaded or removed: the map reads the packs again and covers what is still missing. */
    fun packsChanged() {
        packs?.reload()
        view?.let { it.missing = missing(); it.invalidate() }
    }

    private fun missing(): List<Packs.Country> {
        val codes = packs?.codes.orEmpty()
        return borders.filter { it.code !in codes }
    }

    private fun place() {
        val target = slots[(if (inApp) Slot.APP else Slot.CARD).ordinal] ?: return
        val v = view(target.context)
        if (v.parent === target) return
        (v.parent as? ViewGroup)?.removeView(v)
        target.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        moved?.invoke()
    }
}
