package ro.rdstr.os.maps

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import ro.rdstr.car.MapPacks
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.Emulates

/**
 * The offer of the map of the country under the car, by the same rule as the emulator (core-car [MapPacks.offer]):
 * nothing for a pack on the unit, one turned down with "Not now", or one downloading; else the download over Wi-Fi,
 * or Wi-Fi first. "Not now" is kept across restarts.
 */
class PackOffer(private val context: Context, private val borders: List<MapPacks.Country>) {
    private val prefs = prefs(context)

    @Emulates("store.mapOffer")
    fun at(lon: Double, lat: Double): Pair<MapPacks.Country?, MapPacks.Offer> {
        val country = MapPacks.countryAt(borders, lon, lat)
        val installed = MapHost.packs(context).codes.toSet()
        return country to MapPacks.offer(country?.code, installed, onWifi(context), declined(), CarRuntime.ui.value.maps.downloading?.code)
    }

    /** "Not now": the country is not offered again. */
    @Emulates("store.declinePack")
    fun decline(code: String) = prefs.edit().putStringSet(DECLINED, declined() + code).apply()

    private fun declined(): Set<String> = prefs.getStringSet(DECLINED, emptySet()).orEmpty()

    companion object {
        private const val DECLINED = "declined"

        private fun prefs(context: Context) = context.getSharedPreferences("maps", Context.MODE_PRIVATE)

        /** A removed pack is offered again, even one turned down before. */
        fun forget(context: Context, code: String) {
            val p = prefs(context)
            p.edit().putStringSet(DECLINED, p.getStringSet(DECLINED, emptySet()).orEmpty() - code).apply()
        }

        fun onWifi(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = cm.activeNetwork ?: return false
            return cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }
}
