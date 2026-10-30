package ro.rdstr.carsim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ro.rdstr.car.Journal
import java.io.File

/**
 * adb shell am broadcast -a ro.rdstr.sim -e event '{"type":"ReverseGear","on":true}'
 * adb shell am broadcast -a ro.rdstr.sim -e file /sdcard/rdstr/incoming-call.jsonl
 */
class SimReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("event")?.let { text ->
            runCatching { SimCarBusHolder.bus.emit(Journal.decodeEvent(text)) }
                .onFailure { Log.w(TAG, "bad event: $text", it) }
        }
        intent.getStringExtra("file")?.let { path ->
            runCatching {
                val lines = File(path).readLines().filter { it.isNotBlank() }.map(Journal::decode)
                SimCarBusHolder.bus.replay(lines)
            }.onFailure { Log.w(TAG, "bad file: $path", it) }
        }
    }

    private companion object { const val TAG = "SimReceiver" }
}
