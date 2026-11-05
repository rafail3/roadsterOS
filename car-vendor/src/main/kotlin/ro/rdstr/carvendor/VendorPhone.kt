package ro.rdstr.carvendor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Parcelable
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent
import ro.rdstr.car.Hfp

/**
 * Calls through the phone's hands-free link, with Android's own HFP client (BluetoothHeadsetClient, profile 16).
 * The class is hidden on Android 8.1, so its methods are reached by name; the vendor's MainUI uses the same service.
 *
 * In: call state and number, where the call is heard, the phone's battery, signal and network, the link itself.
 * Out: dial, answer, reject or hang up, keypad tones, call audio to the car or the phone, the car's microphone.
 */
@SuppressLint("MissingPermission")
class VendorPhone(private val context: Context) {
    private val _events = MutableSharedFlow<CarEvent>(extraBufferCapacity = 32)
    val events: Flow<CarEvent> = _events

    private var proxy: BluetoothProfile? = null
    /** The phone's calls by id, as BluetoothHeadsetClientCall objects; terminated ones are dropped. */
    private val calls = linkedMapOf<Int, Any>()
    private var status = CarEvent.PhoneStatus()

    fun start() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run { Log.w(TAG, "no bluetooth adapter"); return }
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                proxy = p
                val phone = p.connectedDevices.firstOrNull()
                Log.i(TAG, "hfp proxy ready, linked=${phone != null}")
                emit(CarEvent.HfpConnection(phone != null, phone?.name.orEmpty()))
                // a call already in progress when our app starts
                p.connectedDevices.firstOrNull()?.let { device ->
                    (invoke("getCurrentCalls", device) as? List<*>)?.filterNotNull()?.forEach(::track)
                    if (calls.isNotEmpty()) publishCalls()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                proxy = null
                Log.w(TAG, "hfp proxy lost")
            }
        }, Hfp.PROFILE)
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Hfp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(Hfp.ACTION_CALL_CHANGED)
            addAction(Hfp.ACTION_AG_EVENT)
            addAction(Hfp.ACTION_AUDIO_STATE_CHANGED)
        })
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Hfp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_DISCONNECTED) {
                        if (state == BluetoothProfile.STATE_DISCONNECTED) calls.clear()
                        // the name the phone gives itself in its Bluetooth settings
                        val name = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.name.orEmpty()
                        Log.i(TAG, "link ${if (state == BluetoothProfile.STATE_CONNECTED) "up" else "down"} name=$name")
                        emit(CarEvent.HfpConnection(state == BluetoothProfile.STATE_CONNECTED, name))
                    }
                }
                Hfp.ACTION_CALL_CHANGED -> intent.getParcelableExtra<Parcelable>(Hfp.EXTRA_CALL)?.let { track(it); publishCalls() }
                Hfp.ACTION_AG_EVENT -> {
                    val e = intent.extras ?: return
                    status = status.copy(
                        battery = if (e.containsKey(Hfp.EXTRA_BATTERY_LEVEL)) e.getInt(Hfp.EXTRA_BATTERY_LEVEL) else status.battery,
                        signal = if (e.containsKey(Hfp.EXTRA_NETWORK_SIGNAL_STRENGTH)) e.getInt(Hfp.EXTRA_NETWORK_SIGNAL_STRENGTH) else status.signal,
                        roaming = if (e.containsKey(Hfp.EXTRA_NETWORK_ROAMING)) e.getInt(Hfp.EXTRA_NETWORK_ROAMING) == 1 else status.roaming,
                        operator = e.getString(Hfp.EXTRA_OPERATOR_NAME) ?: status.operator,
                    )
                    Log.i(TAG, "status battery=${status.battery} signal=${status.signal} roaming=${status.roaming} operator=${status.operator}")
                    emit(status)
                }
                Hfp.ACTION_AUDIO_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, 0)
                    // only the settled states; the stack passes through "connecting" on the way
                    if (state == Hfp.AUDIO_CONNECTED || state == 0) {
                        Log.i(TAG, "audio ${if (state == Hfp.AUDIO_CONNECTED) "car" else "phone"}")
                        if (calls.isNotEmpty()) emit(CarEvent.CallAudio(state == Hfp.AUDIO_CONNECTED))
                    }
                }
            }
        }
    }

    private fun track(call: Any) {
        val id = read(call, "getId") as? Int ?: return
        if (read(call, "getState") == Hfp.TERMINATED) calls.remove(id) else calls[id] = call
    }

    private fun publishCalls() {
        val states = calls.values.mapNotNull { read(it, "getState") as? Int }
        val state = Hfp.state(states)
        // the number of the call the state is about: the incoming one first, else the newest
        val shown = calls.values.firstOrNull { read(it, "getState") == Hfp.INCOMING } ?: calls.values.lastOrNull()
        val number = shown?.let { read(it, "getNumber") as? String }.orEmpty()
        Log.i(TAG, "call $state calls=$states")
        emit(CarEvent.Call(state, number))
    }

    fun send(command: CarCommand.Phone) {
        val device = proxy?.connectedDevices?.firstOrNull() ?: run { Log.w(TAG, "no phone for $command"); return }
        val ok: Any? = when (command) {
            is CarCommand.Phone.Dial -> invoke("dial", device, command.number)
            // 0: answer without touching other calls (CALL_ACCEPT_NONE)
            CarCommand.Phone.Answer -> invoke("acceptCall", device, 0)
            CarCommand.Phone.HangUp ->
                if (calls.values.any { read(it, "getState") == Hfp.INCOMING }) invoke("rejectCall", device)
                else invoke("terminateCall", device, calls.values.lastOrNull())
            is CarCommand.Phone.Dtmf -> command.digit.firstOrNull()?.let { invoke("sendDTMF", device, it.code.toByte()) }
            is CarCommand.Phone.AudioInCar -> invoke(if (command.on) "connectAudio" else "disconnectAudio", device)
            // the car's microphone, as the vendor does it; the call itself stays up
            is CarCommand.Phone.Mute -> context.getSystemService(AudioManager::class.java).setMicrophoneMute(command.on)
        }
        Log.i(TAG, "send $command -> $ok")
    }

    /** Connect or drop the hands-free link of a saved phone, by the name the unit lists it under. */
    fun link(name: String?, connect: Boolean) {
        val device: BluetoothDevice = if (connect) BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.firstOrNull { it.name == name } ?: return
        else proxy?.connectedDevices?.firstOrNull() ?: return
        // ponytail: the hands-free link only; the vendor stack brings music (A2DP) up with it, check at the car
        Log.i(TAG, "${if (connect) "connect" else "disconnect"} -> ${invoke(if (connect) "connect" else "disconnect", device)}")
    }

    private fun emit(event: CarEvent) {
        _events.tryEmit(event)
    }

    private fun invoke(name: String, vararg args: Any?): Any? {
        val p = proxy ?: return null
        val method = p.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size } ?: run { Log.w(TAG, "no $name"); return null }
        return runCatching { method.invoke(p, *args) }.onFailure { Log.w(TAG, "$name failed", it) }.getOrNull()
    }

    private fun read(target: Any, getter: String): Any? = runCatching { target.javaClass.getMethod(getter).invoke(target) }.getOrNull()

    private companion object { const val TAG = "rdstr.phone" }
}
