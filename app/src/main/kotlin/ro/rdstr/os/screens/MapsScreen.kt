package ro.rdstr.os.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.App
import ro.rdstr.car.AudioSource
import ro.rdstr.car.CardPage
import ro.rdstr.car.HomeCard
import ro.rdstr.car.ShellAction
import ro.rdstr.car.SpeedWarning
import ro.rdstr.car.Speedometer
import ro.rdstr.map.MapStyle
import ro.rdstr.os.CarRuntime
import ro.rdstr.os.maps.Chime
import ro.rdstr.os.maps.MapHost
import ro.rdstr.os.maps.MapView
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.maps.MapStatus
import ro.rdstr.ui.maps.MapsAppView

/**
 * The Maps screen's glue (MapsApp.tsx): the screen open while the shell shows MAPS, the one map put into it then and
 * onto the card on home otherwise (with the street and speed layer going along), the unit's GPS fixes fed to the map,
 * the speed from core-car `Speedometer`, the limit sign and the ring from `SpeedWarning`, the chime when the car goes
 * over, and the buttons sent to the map and the shell.
 */
@Mirrors("MapsApp.tsx")
class MapsScreen(private val activity: Activity) : Screen, LocationListener {
    override val view = MapsAppView(activity)
    private val map: MapView = MapHost.view(activity)
    private val speedometer = Speedometer()
    private val warning = SpeedWarning()
    /** Made at the first chime, so a drive under the limits never builds it. */
    private var chime: Chime? = null
    private val style by lazy { MapStyle.load() }
    private var open = false

    init {
        view.actions = object : MapsAppView.Actions {
            override fun close() = CarRuntime.shellAction(ShellAction.Home)
            override fun headingUp(on: Boolean) { map.headingUp = on }
            override fun zoom(by: Double) = map.zoomBy(by)
            override fun recentre() = map.recentre()
        }
        MapHost.setSlot(MapHost.Slot.APP, view.mapSlot)
        map.onRotate = MapView.OnRotate { view.bearing(it) }
        map.onChange = ::status
        // the map's layer goes where the map is: in this screen, or on the card on home
        MapHost.onMapMoved { view.placeOverlay((map.parent as? ViewGroup)?.takeIf { it !== view.mapSlot }) }
        view.open(false, animate = false)
    }

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui) { s, u -> s to u }.collect { (shell, ui) ->
                val nowOpen = shell.app == App.MAPS
                if (nowOpen != open) {
                    open = nowOpen
                    // Screen.tsx `inCard`: the card shows the map page and the screen is on
                    MapHost.showMapIn(open)
                    view.open(open)
                }
                val card = HomeCard.shown(ui.state, CarRuntime.radios.bluetoothOn, shell.card)
                MapHost.cardInView(!open && card == CardPage.NAVIGATION && shell.screenOn)
                view.status.strip = HomeCard.strip(ui.state) != AudioSource.NONE
                status()
            }
        }
        // the frost under the glass is the map's land colour, as blurred over the map
        scope.launch { MapHost.light.collect { light -> view.ground = style.color(!light, "land") } }
        startGps()
    }

    /** The unit's GPS needs no network; the last fix shows the map at once while a new one is found. */
    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = activity.getSystemService(LocationManager::class.java)
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { map.setCar(it.longitude, it.latitude, null, driving = false) }
        lm.removeUpdates(this)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, MapView.GPS_EVERY_MS, 0f, this)
    }

    override fun onLocationChanged(l: Location) {
        // the GPS heading means something only while the car moves
        val moving = l.hasSpeed() && l.speed > MOVING_MPS
        speedometer.update(l.longitude, l.latitude, l.time, if (l.hasSpeed()) l.speed * 3.6 else null)
        map.setCar(l.longitude, l.latitude, if (l.hasBearing() && moving) l.bearing.toDouble() else null, driving = moving)
    }

    /** The street, the speed and the warning for the last fix (MapsApp.tsx, the GPS effect and `idle`). */
    private fun status() {
        val shell = CarRuntime.shell.value
        val r = speedometer.reading
        val w = warning.update(r, map.roadLimit, SystemClock.elapsedRealtime())
        // the chime can be switched off in the settings; the sign and the red ring stay
        if (w.chime && shell.speedChime) (chime ?: Chime().also { chime = it }).play()
        val ring = SpeedWarning.ring(r.kmh, w)
        view.status.bind(MapStatus(map.street, r.kmh, w.limit, w.over, ring.lit, ring.tone, shell.speedLimitSign))
        view.bind(map.follow, map.headingUp)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private companion object { const val MOVING_MPS = 1.5f }
}
