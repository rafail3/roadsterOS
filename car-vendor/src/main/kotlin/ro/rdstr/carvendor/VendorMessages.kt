package ro.rdstr.carvendor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import ro.rdstr.car.CarEvent
import ro.rdstr.car.Messages

/**
 * Text messages through the phone's message profile, with Android's MAP client (BluetoothMapClient, profile 18,
 * hidden on 8.1, reached by name). New messages arrive as a broadcast; a reply goes back with sendMessage.
 * Whether the unit's Bluetooth stack has the MAP client switched on is checked at the car.
 */
@SuppressLint("MissingPermission")
class VendorMessages(private val context: Context) {
    private val _events = MutableSharedFlow<CarEvent>(extraBufferCapacity = 16)
    val events: Flow<CarEvent> = _events
    private var proxy: BluetoothProfile? = null
    private val main = Handler(Looper.getMainLooper())
    /** A stack without the MAP client never answers the request; after this long the unit takes it as missing. */
    private val missing = Runnable {
        Log.i(TAG, "map client not on this unit")
        _events.tryEmit(CarEvent.MessageSupport(false))
    }

    fun start() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val asked = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                proxy = p
                main.removeCallbacks(missing)
                _events.tryEmit(CarEvent.MessageSupport(true))
                Log.i(TAG, "map proxy ready, linked=${p.connectedDevices.isNotEmpty()}")
                // the messages not read yet come in as the same broadcasts as new ones
                p.connectedDevices.firstOrNull()?.let { Log.i(TAG, "unread -> ${invoke("getUnreadMessages", it)}") }
            }

            override fun onServiceDisconnected(profile: Int) { proxy = null }
        }, Messages.PROFILE)
        Log.i(TAG, "map client asked -> $asked")
        if (asked) main.postDelayed(missing, ANSWER_MS) else _events.tryEmit(CarEvent.MessageSupport(false))
        context.registerReceiver(receiver, IntentFilter(Messages.ACTION_MESSAGE_RECEIVED))
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val number = intent.getStringExtra(Messages.EXTRA_SENDER_CONTACT_URI).orEmpty().removePrefix("tel:")
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val id = intent.getStringExtra(Messages.EXTRA_MESSAGE_HANDLE) ?: "${System.currentTimeMillis()}"
            val name = intent.getStringExtra(Messages.EXTRA_SENDER_CONTACT_NAME).orEmpty()
            Log.i(TAG, "message id=$id length=${text.length}")
            _events.tryEmit(CarEvent.Message(id, number, text, name, System.currentTimeMillis()))
        }
    }

    fun send(number: String, text: String): Boolean {
        val device = proxy?.connectedDevices?.firstOrNull() ?: run { Log.w(TAG, "no phone for a message"); return false }
        val ok = invoke("sendMessage", device, arrayOf(Uri.parse("tel:$number")), text, null, null) == true
        Log.i(TAG, "send message -> $ok")
        if (ok) _events.tryEmit(CarEvent.Message("sent-${System.currentTimeMillis()}", number, text, atMs = System.currentTimeMillis(), sent = true))
        return ok
    }

    private fun invoke(name: String, vararg args: Any?): Any? {
        val p = proxy ?: return null
        val method = p.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == args.size } ?: run { Log.w(TAG, "no $name"); return null }
        return runCatching { method.invoke(p, *args) }.onFailure { Log.w(TAG, "$name failed", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "rdstr.phone"
        const val ANSWER_MS = 10_000L
    }
}
