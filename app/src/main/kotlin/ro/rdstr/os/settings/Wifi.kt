package ro.rdstr.os.settings

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import ro.rdstr.ui.Emulates

/** A network in range, from the last scan; [signal] in bars, 1 to 3. */
data class WifiNetwork(val ssid: String, val secure: Boolean, val signal: Int)

/**
 * What the Wi-Fi screens show: the saved networks, the one joined and the one joining, the one whose last join failed
 * on the password, and the networks the last scan found.
 */
data class WifiState(
    val saved: List<String> = emptyList(),
    val connected: String? = null,
    val connecting: String? = null,
    val wrongPassword: String? = null,
    val seen: List<WifiNetwork> = emptyList(),
)

/**
 * Wi-Fi as the settings and the setup drive it, with WifiManager: scans, joins with a password (WPA/WPA2 personal)
 * or none, and forgets. Android joins the strongest saved network in range by itself when Wi-Fi comes on. A join
 * that Android does not settle within [JOIN_TIMEOUT_MS] is given up. Changing networks made by other apps needs the
 * device owner (or the system uid of the platform key).
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION") // WifiConfiguration and its calls are the API on 27
class Wifi(
    private val context: Context,
    private val radios: Radios,
    private val current: () -> WifiState,
    private val update: ((WifiState) -> WifiState) -> Unit,
) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    /** The network added for the join in progress; dropped again when its password fails. */
    private var added: Int? = null
    private val giveUp = Runnable { update { it.copy(connecting = null) } }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> if (radios.wifiOn && radios.locationOn) update { it.copy(seen = networks()) }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> when (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)) {
                    // off: the network drops and the scan is forgotten
                    WifiManager.WIFI_STATE_DISABLED -> { main.removeCallbacks(giveUp); update { WifiState(saved = it.saved) } }
                    WifiManager.WIFI_STATE_ENABLED -> refresh()
                }
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO) ?: return
                    if (info.isConnected) joined() else if (info.state == NetworkInfo.State.DISCONNECTED) update { it.copy(connected = null) }
                }
                WifiManager.SUPPLICANT_STATE_CHANGED_ACTION ->
                    if (intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, 0) == WifiManager.ERROR_AUTHENTICATING) wrongPassword()
                // location off keeps Wi-Fi joined but stops finding networks: the list empties
                LocationManager.MODE_CHANGED_ACTION -> if (!radios.locationOn) update { it.copy(seen = emptyList()) }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        context.applicationContext.registerReceiver(receiver, filter)
        refresh()
    }

    /** The networks around the unit, from Android's last scan: one per name, the strongest; hidden ones left out. */
    @Emulates("store.WIFI_NETWORKS")
    fun networks(): List<WifiNetwork> = runCatching { wifi.scanResults }.getOrNull().orEmpty()
        .filter { it.SSID.isNotEmpty() }
        .groupBy { it.SSID }.values.map { same -> same.maxByOrNull { it.level }!! }
        .map { r -> WifiNetwork(r.SSID, SECURED.any { it in r.capabilities }, WifiManager.calculateSignalLevel(r.level, 3) + 1) }

    /** A scan: the list empties and fills with what Android finds; nothing without Wi-Fi and location on. */
    @Emulates("store.wifiScan")
    fun scan() {
        if (!radios.wifiOn || !radios.locationOn) return
        update { it.copy(seen = emptyList()) }
        if (!wifi.startScan()) update { it.copy(seen = networks()) }
    }

    /** Join a network: a saved or open one needs nothing; a new secured one needs its password, and a wrong one fails. */
    @Emulates("store.wifiConnect")
    fun connect(ssid: String, password: String? = null) {
        if (!radios.wifiOn) return
        val saved = config(ssid)
        val network = current().seen.find { it.ssid == ssid } ?: networks().find { it.ssid == ssid }
        if (saved == null && network == null) return
        update { it.copy(connecting = ssid, wrongPassword = null) }
        forgetAdded()
        val id = when {
            saved != null -> saved.networkId
            network!!.secure && password == null -> return wrongPassword()
            else -> wifi.addNetwork(newConfig(ssid, network.secure, password)).also { if (it >= 0) added = it }
        }
        if (id < 0 || !wifi.enableNetwork(id, true)) {
            Log.w(Radios.TAG, "wifi join $ssid refused")
            return update { it.copy(connecting = null) }
        }
        wifi.reconnect()
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, JOIN_TIMEOUT_MS)
    }

    /** Forget a saved network; the connected one drops with it. */
    @Emulates("store.wifiForget")
    fun forget(ssid: String) {
        configs().filter { unquote(it.SSID) == ssid }.forEach { wifi.removeNetwork(it.networkId) }
        wifi.saveConfiguration()
        update { it.copy(saved = it.saved - ssid, connected = if (it.connected == ssid) null else it.connected) }
    }

    /** The saved networks and the one joined, as Android has them now. */
    private fun refresh() {
        val saved = configs().map { unquote(it.SSID) }.distinct()
        update { it.copy(saved = saved, connected = joinedSsid()) }
    }

    private fun joined() {
        val ssid = joinedSsid() ?: return
        main.removeCallbacks(giveUp)
        if (added != null) { wifi.saveConfiguration(); added = null }
        update { it.copy(connected = ssid, connecting = if (it.connecting == ssid) null else it.connecting, saved = if (ssid in it.saved) it.saved else it.saved + ssid) }
    }

    private fun wrongPassword() {
        val ssid = current().connecting ?: return
        main.removeCallbacks(giveUp)
        forgetAdded()
        update { it.copy(connecting = null, wrongPassword = ssid) }
    }

    /** A network added for a join that did not work is not kept: it was never saved in the emulator either. */
    private fun forgetAdded() {
        added?.let { wifi.removeNetwork(it) }
        added = null
    }

    private fun joinedSsid(): String? = wifi.connectionInfo?.takeIf { it.networkId != -1 }?.let { unquote(it.ssid) }

    private fun configs(): List<WifiConfiguration> = runCatching { wifi.configuredNetworks }.getOrNull().orEmpty()

    private fun config(ssid: String) = configs().firstOrNull { unquote(it.SSID) == ssid }

    private fun newConfig(ssid: String, secure: Boolean, password: String?) = WifiConfiguration().apply {
        SSID = "\"$ssid\""
        if (secure) preSharedKey = "\"$password\"" else allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
    }

    private fun unquote(s: String?) = s.orEmpty().removeSurrounding("\"")

    private companion object {
        const val JOIN_TIMEOUT_MS = 20_000L
        val SECURED = listOf("WEP", "PSK", "EAP", "SAE")
    }
}
