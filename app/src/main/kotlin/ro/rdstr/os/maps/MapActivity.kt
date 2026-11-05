package ro.rdstr.os.maps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import ro.rdstr.car.MapPacks as Packs
import ro.rdstr.car.SpeedWarning
import ro.rdstr.car.Speedometer
import ro.rdstr.map.MapBorders
import java.io.File
import kotlin.math.cos

/**
 * The map full screen, from the packs on the unit and the unit's own GPS: no network. Until the shell has its maps
 * screen, this is where the map runs on the unit:
 *
 *   adb push RO.mbtiles /sdcard/Android/data/ro.rdstr.os/files/maps/RO.mbtiles
 *   adb shell pm grant ro.rdstr.os android.permission.ACCESS_FINE_LOCATION
 *   adb shell am start -n ro.rdstr.os/.maps.MapActivity [--ez demo true] [--ez day true] [--ef zoom 15.5]
 *
 * `demo` drives the car along its heading at 50 km/h, turning slowly, to measure the map without driving;
 * the frame and picture times go to logcat under the tag `rdstr.map`, with the speed and the street the car is on.
 */
class MapActivity : Activity(), LocationListener {
    private var map: MapView? = null
    private var packs: MapPacks? = null
    private val main = Handler(Looper.getMainLooper())
    private var demo: Runnable? = null
    /** The speed pill's reading, from the GPS fixes by the same calculation as the emulator. */
    private val speedometer = Speedometer()
    /** The limit sign, the dial's ring and its red over the limit, with a chime each time the car goes over (core-car). */
    private val speedWarning = SpeedWarning()
    /** Made at the first chime, so a drive under the limits never builds it. */
    private var chime: Chime? = null
    private var status = ""
    private var offer: PackOffer? = null
    private var offered = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(getExternalFilesDir(null), "maps").apply { mkdirs() }
        val p = MapPacks(dir)
        if (p.codes.isEmpty()) {
            p.close()
            setContentView(TextView(this).apply { text = "No map pack in ${dir.path}"; textSize = 20f; setPadding(48, 48, 48, 48) })
            return
        }
        packs = p
        val view = MapView(this, p).apply {
            night = !intent.getBooleanExtra("day", false)
            zoom = intent.getFloatExtra("zoom", 15.5f).toDouble()
        }
        map = view
        // the borders tell the country under the car and cover the ones without a pack
        val borders = Packs.parseBorders(MapBorders.geojson())
        view.missing = borders.filter { it.code !in p.codes }
        offer = PackOffer(this, borders, p.codes.toSet())
        view.onChange = ::logStatus
        setContentView(view)
        if (intent.getBooleanExtra("demo", false)) startDemo(view)
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        // full screen: the shell's own bars come later; the system ones stay hidden
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (demo == null) startGps()
    }

    override fun onPause() {
        (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        super.onPause()
    }

    override fun onDestroy() {
        chime?.release()
        demo?.let { main.removeCallbacks(it) }
        packs?.close()
        super.onDestroy()
    }

    /** The unit's GPS needs no network; the last fix shows the map at once while a new one is found. */
    @SuppressLint("MissingPermission")
    private fun startGps() {
        val view = map ?: return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { view.setCar(it.longitude, it.latitude, null) }
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_EVERY_MS, 0f, this)
    }

    override fun onLocationChanged(l: Location) {
        // the GPS heading means something only while the car moves
        val moving = l.hasSpeed() && l.speed > MOVING_MPS
        // the chip's Doppler speed when the fix has it, else the distance over the last seconds
        speedometer.update(l.longitude, l.latitude, l.time, if (l.hasSpeed()) l.speed * 3.6 else null)
        map?.setCar(l.longitude, l.latitude, if (l.hasBearing() && moving) l.bearing.toDouble() else null)
        logOffer(l.longitude, l.latitude)
    }

    /** What the offer card shows for the country under the car, logged when it changes. */
    private fun logOffer(lon: Double, lat: Double) {
        val (country, o) = offer?.at(lon, lat) ?: return
        val now = if (country == null || o == Packs.Offer.NONE) "none" else "${country.name}: $o, ${Packs.PACK_MB[country.code] ?: "?"} MB"
        if (now == offered) return
        offered = now
        android.util.Log.i(MapView.TAG, "offer: $now")
    }

    /** What the street pill and the speed dial show, logged when it changes; the chime plays as the car goes over. */
    private fun logStatus() {
        val view = map ?: return
        val r = speedometer.reading
        val w = speedWarning.update(r, view.roadLimit, SystemClock.elapsedRealtime())
        // the chime can be switched off in the settings; the sign and the red ring stay
        if (w.chime && ro.rdstr.os.CarRuntime.shell.value.speedChime) (chime ?: Chime().also { chime = it }).play()
        val ring = SpeedWarning.ring(r.kmh, w)
        // the limit sign on the speed can be switched off in the settings; the ring and its red stay
        val sign = if (ro.rdstr.os.CarRuntime.shell.value.speedLimitSign) "${w.limit ?: "none"}" else "hidden"
        val now = "street: ${view.street.ifEmpty { "(folded)" }} | ${r.kmh} km/h | limit: $sign | ring: ${ring.lit} ${ring.tone}"
        if (now == status) return
        status = now
        android.util.Log.i(MapView.TAG, "status: $now")
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun startDemo(view: MapView) {
        var lon = view.lon
        var lat = view.lat
        var heading = 20.0
        val step = object : Runnable {
            override fun run() {
                val km = DEMO_KMH * DEMO_TICK_MS / 3_600_000.0
                val r = Math.toRadians(heading)
                lat += km * cos(r) / 111.32
                lon += km * kotlin.math.sin(r) / (111.32 * cos(Math.toRadians(lat)))
                heading = (heading + 0.4) % 360
                speedometer.update(lon, lat, SystemClock.elapsedRealtime(), DEMO_KMH)
                view.setCar(lon, lat, heading)
                logOffer(lon, lat)
                main.postDelayed(this, DEMO_TICK_MS)
            }
        }
        demo = step
        main.post(step)
    }

    private companion object {
        const val GPS_EVERY_MS = 500L
        const val MOVING_MPS = 1.5f
        const val DEMO_KMH = 50.0
        const val DEMO_TICK_MS = 33L
    }
}
