package ro.rdstr.os.cast

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import android.view.Surface
import ro.rdstr.car.CarEvent
import ro.rdstr.car.CarEvent.PhoneCast.State
import java.io.File
import java.net.InetAddress

/**
 * The unit as a Miracast receiver: over Wi-Fi Direct it tells phones it is a display named roadsterOS (the WFD
 * information element, set through WifiP2pManager.setWFDInfo, which needs CONFIGURE_WIFI_DISPLAY: the platform
 * signature), stays findable, and when a phone connects opens a [CastSession] to it. States go to [onState].
 */
@SuppressLint("MissingPermission") // location and Wi-Fi permissions are granted at install as device owner (API 27)
class CastReceiver(private val context: Context, private val onState: (CarEvent.PhoneCast) -> Unit) {
    private val p2p = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    @Volatile private var session: CastSession? = null
    private var running = false

    /** Where the picture is drawn; the phone screen view hands it over while it is shown. */
    @Volatile var surface: Surface? = null
        set(value) { field = value; session?.surface = value }

    private val events = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            @Suppress("DEPRECATION") // the typed getter needs API 33
            val info = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO) ?: return
            @Suppress("DEPRECATION")
            val group = intent.getParcelableExtra<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
            if (info.isConnected) connected(group) else disconnected()
        }
    }

    fun start() {
        if (running) return
        running = true
        val ch = p2p.initialize(context, Looper.getMainLooper(), null).also { channel = it }
        hidden("setDeviceName", ch, String::class.java to CarEvent.PhoneCast.NAME)
        setWfd(ch, enabled = true)
        context.registerReceiver(events, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
        p2p.discoverPeers(ch, listener("discoverPeers"))
        onState(CarEvent.PhoneCast(State.VISIBLE))
    }

    fun stop() {
        if (!running) return
        running = false
        session?.close()
        session = null
        runCatching { context.unregisterReceiver(events) }
        channel?.let { ch ->
            setWfd(ch, enabled = false)
            p2p.stopPeerDiscovery(ch, listener("stopPeerDiscovery"))
            p2p.removeGroup(ch, listener("removeGroup"))
        }
        onState(CarEvent.PhoneCast(State.OFF))
    }

    private fun connected(group: WifiP2pGroup?) {
        val ch = channel ?: return
        if (session != null) return
        val phone = group?.let { if (it.isGroupOwner) it.clientList.firstOrNull()?.deviceName else it.owner?.deviceName }.orEmpty()
        onState(CarEvent.PhoneCast(State.CONNECTING, phone))
        p2p.requestConnectionInfo(ch) { info ->
            Thread({
                // the phone is the group owner as a rule; when the unit is, the phone's address comes from the ARP table
                val source = if (!info.isGroupOwner) info.groupOwnerAddress else group?.getInterface()?.let(::clientAddress)
                if (source == null) {
                    Log.w(TAG, "no address for the phone")
                    return@Thread
                }
                val ended: (CarEvent.PhoneCast) -> Unit = { e -> if (e.state == State.VISIBLE) session = null; onState(e) }
                session = CastSession(source, CONTROL_PORT, phone, ended).also { it.surface = surface; it.start() }
            }, "rdstr-cast-connect").start()
        }
    }

    private fun disconnected() {
        val ended = session ?: return
        ended.close()
        session = null
        onState(CarEvent.PhoneCast(State.VISIBLE))
        channel?.let { p2p.discoverPeers(it, listener("discoverPeers")) }
    }

    /** The phone's address on the group's interface, once its DHCP lease shows in /proc/net/arp. */
    private fun clientAddress(iface: String): InetAddress? {
        repeat(ARP_TRIES) {
            File("/proc/net/arp").readLines().drop(1).map { it.split(Regex("\\s+")) }
                .firstOrNull { it.size >= 6 && it[5] == iface && it[2] != "0x0" }?.let { return InetAddress.getByName(it[0]) }
            Thread.sleep(ARP_WAIT_MS)
        }
        return null
    }

    /** The WFD information element: a primary sink, session available, control port 7236 (hidden API, reflection). */
    private fun setWfd(ch: WifiP2pManager.Channel, enabled: Boolean) {
        val type = runCatching { Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo") }.getOrNull()
            ?: return run { Log.w(TAG, "no WifiP2pWfdInfo") }
        val info = type.getDeclaredConstructor().newInstance()
        fun set(name: String, arg: Any, cls: Class<*>) {
            runCatching { type.getMethod(name, cls).invoke(info, arg) }.onFailure { Log.w(TAG, name, it) }
        }
        set("setWfdEnabled", enabled, java.lang.Boolean.TYPE)
        set("setDeviceType", PRIMARY_SINK, Integer.TYPE)
        set("setSessionAvailable", enabled, java.lang.Boolean.TYPE)
        set("setControlPort", CONTROL_PORT, Integer.TYPE)
        set("setMaxThroughput", MAX_THROUGHPUT_MBPS, Integer.TYPE)
        hidden("setWFDInfo", ch, type to info)
    }

    private fun hidden(name: String, ch: WifiP2pManager.Channel, arg: Pair<Class<*>, Any>) {
        runCatching {
            WifiP2pManager::class.java.getMethod(name, WifiP2pManager.Channel::class.java, arg.first, WifiP2pManager.ActionListener::class.java)
                .invoke(p2p, ch, arg.second, listener(name))
        }.onFailure { Log.w(TAG, "$name failed", it) }
    }

    private fun listener(name: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = Unit
        override fun onFailure(reason: Int) { Log.w(TAG, "$name failed: $reason") }
    }

    private companion object {
        const val TAG = "rdstr.cast"
        const val CONTROL_PORT = 7236
        const val PRIMARY_SINK = 1
        const val MAX_THROUGHPUT_MBPS = 50
        const val ARP_TRIES = 20
        const val ARP_WAIT_MS = 250L
    }
}
