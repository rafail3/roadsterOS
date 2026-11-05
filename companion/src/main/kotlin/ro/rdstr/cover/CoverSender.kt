package ro.rdstr.cover

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import ro.rdstr.car.CoverFrame
import ro.rdstr.car.CoverLink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Sends the current cover to the car over the cover channel. Only audio devices connected to the phone are tried;
 * a device without the channel (headphones, a speaker) is skipped for a while after one refused attempt.
 */
class CoverSender(private val context: Context) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val worker = Executors.newSingleThreadExecutor()
    private val uuid = UUID.fromString(CoverLink.SERVICE_UUID)
    private val sockets = HashMap<String, BluetoothSocket>()
    private val refusedAt = HashMap<String, Long>()
    @Volatile private var a2dp: BluetoothA2dp? = null
    @Volatile private var latest: CoverFrame? = null

    init {
        adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { a2dp = proxy as BluetoothA2dp; resend() }
            override fun onServiceDisconnected(profile: Int) { a2dp = null }
        }, BluetoothProfile.A2DP)
    }

    /** Shrinks and encodes the artwork off the main thread, then sends it. */
    // the encoding below runs on [worker], which lint cannot follow through the executor
    @SuppressLint("ThreadConstraint")
    fun send(title: String, artist: String, art: Bitmap) {
        worker.execute {
            val frame = CoverFrame(title, artist, jpeg(art))
            Status.cover(context, frame.jpeg)
            latest = frame
            deliver(frame)
        }
    }

    // runs on the worker: encoding a bitmap is too slow for the main thread
    private fun jpeg(art: Bitmap): ByteArray {
        val scale = COVER_PX.toFloat() / maxOf(art.width, art.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(art, (art.width * scale).toInt(), (art.height * scale).toInt(), true) else art
        return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
    }

    /** The car just connected: give it the cover of what is playing. */
    fun resend() {
        val frame = latest ?: return
        worker.execute { deliver(frame) }
    }

    fun close() {
        worker.execute { sockets.values.forEach { runCatching { it.close() } }; sockets.clear() }
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        worker.shutdown()
    }

    private fun allowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked in allowed()
    private fun deliver(frame: CoverFrame) {
        if (!allowed()) return Status.delivery(context, Status.Delivery.NEED_PERMISSION)
        val targets = a2dp?.connectedDevices.orEmpty()
        if (targets.isEmpty()) return Status.delivery(context, Status.Delivery.NO_DEVICE)
        val bytes = CoverLink.encode(frame)
        var sentTo: String? = null
        for (device in targets) {
            val refused = refusedAt[device.address]
            if (refused != null && SystemClock.elapsedRealtime() - refused < RETRY_REFUSED_MS) continue
            val name = device.name ?: device.address
            Status.delivery(context, Status.Delivery.SENDING, name)
            if (write(device, bytes)) {
                refusedAt.remove(device.address)
                sentTo = name
            }
        }
        if (sentTo != null) Status.delivery(context, Status.Delivery.SENT, sentTo)
        else Status.delivery(context, Status.Delivery.NO_DISPLAY, targets.first().let { it.name ?: it.address })
    }

    @SuppressLint("MissingPermission")
    private fun write(device: BluetoothDevice, bytes: ByteArray): Boolean {
        // a kept socket is tried first; after a write error one fresh connection is made
        repeat(2) { attempt ->
            // the app never scans, so there is no discovery to cancel before connecting
            val socket = sockets[device.address] ?: try {
                device.createRfcommSocketToServiceRecord(uuid).also { it.connect(); sockets[device.address] = it }
            } catch (e: IOException) {
                refusedAt[device.address] = SystemClock.elapsedRealtime()
                return false
            }
            try {
                socket.outputStream.write(bytes)
                socket.outputStream.flush()
                return true
            } catch (e: IOException) {
                runCatching { socket.close() }
                sockets.remove(device.address)
                if (attempt == 1) refusedAt[device.address] = SystemClock.elapsedRealtime()
            }
        }
        return false
    }

    private companion object {
        /** A device without the cover channel is asked again after this long, for example after an update of the car. */
        const val RETRY_REFUSED_MS = 10 * 60 * 1000L
        /** The same size as the large covers from the online catalogs: about 120 to 200 KB, about a second over Bluetooth. */
        const val COVER_PX = 1000
        const val JPEG_QUALITY = 90
    }
}
