package ro.rdstr.os.settings

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The devices sheet's search, pairing and connection states on the unit, with Android's own Bluetooth: discovery for
 * the phones nearby, a bond with the code check shown in our sheet instead of the system's dialog, the bond removed to
 * forget a phone, and the connection the vendor's Bluetooth app makes followed until it is up or given up.
 */
@SuppressLint("MissingPermission") // BLUETOOTH, BLUETOOTH_ADMIN and BLUETOOTH_PRIVILEGED are granted to the system app
object BluetoothDevices {
    data class State(
        val connecting: String? = null,
        val failed: String? = null,
        val scanning: Boolean = false,
        val searched: Boolean = false,
        val nearby: List<String> = emptyList(),
        val pairing: Pairing? = null,
    )
    data class Pairing(val name: String, val code: String, val confirmed: Boolean = false)

    /** A connection not up after this long is shown as failed (the emulator's phone answers or not at once). */
    private const val CONNECT_GIVE_UP_MS = 15_000L
    private const val TAG = "rdstr.bt"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private val main = Handler(Looper.getMainLooper())
    private var pending: BluetoothDevice? = null
    private var started = false
    private val adapter get() = BluetoothAdapter.getDefaultAdapter()

    fun start(context: Context) {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            // ahead of the system's pairing dialog, which our sheet replaces
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }
        context.applicationContext.registerReceiver(receiver, filter)
    }

    fun scan() {
        val a = adapter ?: return
        found.clear()
        _state.value = _state.value.copy(scanning = true, searched = false, nearby = emptyList())
        if (a.isDiscovering) a.cancelDiscovery()
        if (!a.startDiscovery()) _state.value = _state.value.copy(scanning = false, searched = true)
    }

    /** Pairs with a phone found nearby; the code check comes as [State.pairing]. */
    fun pair(name: String) {
        val device = found[name] ?: return
        adapter?.cancelDiscovery()
        pending = device
        if (!device.createBond()) Log.w(TAG, "bond to $name refused")
    }

    /** The code check answered: the same code on the phone, or not. */
    fun confirm(accept: Boolean) {
        val device = pending ?: return
        runCatching { device.setPairingConfirmation(accept) }.onFailure { Log.w(TAG, "pairing confirmation", it) }
        val p = _state.value.pairing
        _state.value = _state.value.copy(pairing = if (accept && p != null) p.copy(confirmed = true) else null)
        if (!accept) pending = null
    }

    /** Forgets a saved phone: its bond removed (a hidden method of the system's own Bluetooth settings). */
    fun forget(name: String) {
        val device = adapter?.bondedDevices?.firstOrNull { it.name == name } ?: return
        runCatching { device.javaClass.getMethod("removeBond").invoke(device) }.onFailure { Log.w(TAG, "forget $name", it) }
    }

    /** The vendor's app is asked to connect [name]; until it is up the sheet says Connecting, after the wait Failed. */
    fun connecting(name: String) {
        main.removeCallbacks(giveUp)
        _state.value = _state.value.copy(connecting = name, failed = null)
        main.postDelayed(giveUp, CONNECT_GIVE_UP_MS)
    }

    /** The hands-free link is up (CarRuntime's state): the wait ends. */
    fun connected() {
        if (_state.value.connecting == null) return
        main.removeCallbacks(giveUp)
        _state.value = _state.value.copy(connecting = null, failed = null)
    }

    private val giveUp = Runnable {
        val name = _state.value.connecting ?: return@Runnable
        _state.value = _state.value.copy(connecting = null, failed = name)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            when (intent.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _state.value = _state.value.copy(scanning = true)
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _state.value = _state.value.copy(scanning = false, searched = true)
                BluetoothDevice.ACTION_FOUND -> {
                    val name = device?.name ?: return
                    if (device.bondState == BluetoothDevice.BOND_BONDED || name in found) return
                    found[name] = device
                    _state.value = _state.value.copy(nearby = found.keys.toList())
                }
                BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                    val name = device?.name ?: return
                    val key = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
                    if (key < 0) return
                    pending = device
                    _state.value = _state.value.copy(pairing = Pairing(name, "%06d".format(key)))
                    abortBroadcast()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (device != pending) return
                    if (bond == BluetoothDevice.BOND_BONDED || bond == BluetoothDevice.BOND_NONE) {
                        pending = null
                        found.remove(device?.name)
                        _state.value = _state.value.copy(pairing = null, nearby = found.keys.toList())
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> if (device?.name == _state.value.connecting) connected()
            }
        }
    }
}
