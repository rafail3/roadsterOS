package ro.rdstr.os.settings

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ro.rdstr.car.TimeSource
import ro.rdstr.car.TimeZones
import ro.rdstr.os.AdminReceiver
import java.util.TimeZone
import ro.rdstr.car.Settings as CarSettings

/**
 * The unit's clock: Android's own internet time (Settings.Global.AUTO_TIME, switched as device owner), the time of
 * the GPS fixes, or the time set by hand; and its zone. Setting the clock or the zone needs SET_TIME and
 * SET_TIME_ZONE, which Android grants only to apps signed with the platform key or installed in /system/priv-app.
 */
class TimeKeeper(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val locations = context.getSystemService(LocationManager::class.java)
    private var following = false

    // API 27: LocationListener has four abstract methods, so no lambda
    private val gps = object : LocationListener {
        override fun onLocationChanged(fix: Location) = onFix(fix)
        @Deprecated("API 27 still calls it") override fun onStatusChanged(provider: String, status: Int, extras: Bundle) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    /** The sync chosen in the settings: Android's internet time only for NETWORK, GPS fixes followed only for GPS. */
    fun apply(auto: Boolean, source: TimeSource) {
        val network = auto && source == TimeSource.NETWORK
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm.isDeviceOwnerApp(context.packageName)) runCatching {
            dpm.setGlobalSetting(ComponentName(context, AdminReceiver::class.java), android.provider.Settings.Global.AUTO_TIME, if (network) "1" else "0")
        }.onFailure { Log.w(Radios.TAG, "auto time not switched", it) }
        follow(auto && source == TimeSource.GPS)
        Log.i(Radios.TAG, "time sync auto=$auto source=$source")
    }

    @SuppressLint("MissingPermission") // ACCESS_FINE_LOCATION, granted by the device owner at start
    private fun follow(on: Boolean) {
        if (on == following) return
        following = on
        runCatching {
            if (on) locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_EVERY_MS, 0f, gps, Looper.getMainLooper())
            else locations.removeUpdates(gps)
        }.onFailure { Log.w(Radios.TAG, "gps time not followed", it) }
    }

    private fun onFix(fix: Location) {
        // the fix's time, carried forward by how long it took to reach us
        val age = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000
        CarSettings.gpsClock(System.currentTimeMillis(), fix.time + age)?.let { set(it) }
    }

    /** Sets the clock: the settings' date and time steppers, or a GPS fix. */
    fun set(ms: Long): Boolean =
        runCatching { alarms.setTime(ms) }.onFailure { Log.w(Radios.TAG, "clock not set", it) }.isSuccess
            .also { Log.i(Radios.TAG, "clock -> $ms ok=$it") }

    fun setZone(id: String): Boolean =
        runCatching { alarms.setTimeZone(id) }.onFailure { Log.w(Radios.TAG, "time zone not set", it) }.isSuccess
            .also { Log.i(Radios.TAG, "time zone -> $id ok=$it") }

    /** Every zone named after a place, by city (core-car TimeZones). */
    fun zones(): List<String> = TimeZones.places(TimeZone.getAvailableIDs().toList())

    /** The zone's offset from GMT right now, summer time included. */
    fun offsetMinutes(id: String): Int = TimeZone.getTimeZone(id).getOffset(System.currentTimeMillis()) / 60_000

    private companion object { const val GPS_EVERY_MS = 60_000L }
}
