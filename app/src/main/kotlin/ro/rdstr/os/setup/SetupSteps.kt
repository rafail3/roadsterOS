package ro.rdstr.os.setup

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log
import ro.rdstr.car.MapPacks
import ro.rdstr.car.Setup
import ro.rdstr.car.Updater
import ro.rdstr.map.MapBorders
import java.io.File

/**
 * The work behind the setup's screens on the unit, the screens' views coming with the shell: the Wi-Fi networks in
 * range and joining one, where the car is (country, time zone, how numbers are written) and the map files.
 */
@SuppressLint("MissingPermission") // location and Wi-Fi permissions: granted in the system preparation
class SetupSteps(private val context: Context) {
    private val wifi = context.getSystemService(WifiManager::class.java)

    /** The networks in range for the Wi-Fi screen, the joined one first (Setup.networkOrder). Starts a new scan. */
    fun networks(): List<Setup.Network> {
        wifi.startScan()
        val connected = wifi.connectionInfo?.ssid?.removeSurrounding("\"")
        val saved = wifi.configuredNetworks.orEmpty().mapNotNull { it.SSID?.removeSurrounding("\"") }.toSet()
        val seen = wifi.scanResults.orEmpty().filter { it.SSID.isNotEmpty() }.map {
            Setup.Network(it.SSID, WifiManager.calculateSignalLevel(it.level, 5), secured(it))
        }
        return Setup.networkOrder(seen, connected, saved)
    }

    private fun secured(r: ScanResult) = listOf("WEP", "PSK", "EAP", "SAE").any { r.capabilities.contains(it) }

    /** Joins [ssid]: a saved network by itself, an open one without a password, else with [password] (WPA). */
    fun join(ssid: String, password: String?): Boolean {
        val quoted = "\"$ssid\""
        val id = wifi.configuredNetworks.orEmpty().firstOrNull { it.SSID == quoted }?.networkId
            ?: wifi.addNetwork(WifiConfiguration().apply {
                SSID = quoted
                if (password == null) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE) else preSharedKey = "\"$password\""
            })
        return (id >= 0 && wifi.enableNetwork(id, true)).also { Log.i(TAG, "join $ssid -> $it") }
    }

    /** Where the car is, from the last GPS fix: the country, its time zone and its numbers; null without a fix. */
    class Place(val country: String, val zone: String?)

    fun place(): Place? {
        val fix = context.getSystemService(LocationManager::class.java).getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: return null
        val country = MapPacks.countryAt(MapPacks.parseBorders(MapBorders.geojson()), fix.longitude, fix.latitude)?.code ?: return null
        // the first of the country's zones is its main one (Europe/Bucharest for RO)
        val zone = android.icu.util.TimeZone.getAvailableIDs(country).firstOrNull()
        return Place(country, zone)
    }

    /** The map files: the preloaded country's pack must be on the unit; the one under the car comes on Wi-Fi. */
    val maps get() = File(context.getExternalFilesDir(null), "maps").apply { mkdirs() }

    fun hasPack(code: String) = File(maps, code + MapPacks.EXTENSION).length() > 0

    /** Downloads a country's pack from the `maps` release, checked against its digest. Call off the main thread. */
    fun downloadPack(code: String, onProgress: (Updater.Download) -> Unit): Boolean = runCatching {
        val release = Updater.parseRelease(Http.text(Updater.MAPS) ?: error("no maps release")) ?: error("bad maps release")
        val asset = release.assets.first { it.name == code + MapPacks.EXTENSION }
        val sha = Http.download(asset.url, File(maps, asset.name), asset.bytes, onProgress)
        if (asset.sha256 != null && sha != asset.sha256) { File(maps, asset.name).delete(); error("SHA-256 does not match") }
        true
    }.onFailure { Log.w(TAG, "pack $code", it) }.getOrDefault(false)

    private companion object { const val TAG = "rdstr.setup" }
}
