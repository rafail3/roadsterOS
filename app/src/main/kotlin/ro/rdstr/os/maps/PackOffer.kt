package ro.rdstr.os.maps

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ro.rdstr.car.MapPacks
import ro.rdstr.ui.Emulates

/**
 * The offer of the map of the country under the car, by the same rule as the emulator (core-car [MapPacks.offer]):
 * nothing for a pack on the unit, one turned down with "Not now", or one downloading; else the download over Wi-Fi,
 * or Wi-Fi first. "Not now" is kept across restarts.
 */
class PackOffer(private val context: Context, private val borders: List<MapPacks.Country>, private val installed: Set<String>) {
    private val prefs = context.getSharedPreferences("maps", Context.MODE_PRIVATE)

    /** A country whose pack is being downloaded; none yet, as there is no download source. */
    var downloading: String? = null

    @Emulates("store.mapOffer")
    fun at(lon: Double, lat: Double): Pair<MapPacks.Country?, MapPacks.Offer> {
        val country = MapPacks.countryAt(borders, lon, lat)
        return country to MapPacks.offer(country?.code, installed, wifi(), declined(), downloading)
    }

    /** "Not now": the country is not offered again. */
    @Emulates("store.declinePack")
    fun decline(code: String) = prefs.edit().putStringSet(DECLINED, declined() + code).apply()

    private fun declined(): Set<String> = prefs.getStringSet(DECLINED, emptySet()).orEmpty()

    private fun wifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private companion object {
        const val DECLINED = "declined"
    }
}
