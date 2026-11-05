package ro.rdstr.os.cover

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.rdstr.car.CoverFrame
import ro.rdstr.car.CoverLink
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.UUID

/**
 * The unit's end of the cover channel: an RFCOMM service the phone app connects to. Listens while Bluetooth is on,
 * waits without polling while it is off, and reads frames from each phone until it disconnects.
 */
class CoverServer(private val adapter: BluetoothAdapter?, private val onFrame: (CoverFrame) -> Unit) {
    private val bluetoothOn = Channel<Unit>(Channel.CONFLATED)

    fun bluetoothTurnedOn() { bluetoothOn.trySend(Unit) }

    // on API 27 the BLUETOOTH permission is granted at install; the runtime one lint expects is Android 12+
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        val adapter = adapter ?: return // no Bluetooth on this device, as in the emulator
        scope.launch(Dispatchers.IO) {
            val uuid = UUID.fromString(CoverLink.SERVICE_UUID)
            while (isActive) {
                if (!adapter.isEnabled) { bluetoothOn.receive(); continue }
                val server = try {
                    adapter.listenUsingRfcommWithServiceRecord(CoverLink.SERVICE_NAME, uuid)
                } catch (e: IOException) {
                    delay(RETRY_MS); continue
                } catch (e: SecurityException) {
                    return@launch
                }
                try {
                    while (isActive) {
                        val socket = server.accept()
                        launch { socket.use { read(DataInputStream(BufferedInputStream(it.inputStream))) } }
                    }
                } catch (e: IOException) {
                    // Bluetooth turned off or the service was closed: listen again when it is back
                } finally {
                    runCatching { server.close() }
                }
            }
        }
    }

    private fun read(input: DataInputStream) {
        val header = ByteArray(CoverLink.HEADER_SIZE)
        try {
            while (true) {
                input.readFully(header)
                // a foreign header leaves the stream out of step: drop the connection, the phone reconnects
                val length = CoverLink.payloadLength(header) ?: return
                val payload = ByteArray(length)
                input.readFully(payload)
                CoverLink.decode(payload)?.let(onFrame)
            }
        } catch (e: IOException) {
            // the phone disconnected
        }
    }

    private companion object { const val RETRY_MS = 3000L }
}
