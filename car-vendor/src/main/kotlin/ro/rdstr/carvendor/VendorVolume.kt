package ro.rdstr.carvendor

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.math.abs

/**
 * The unit's volume, which the vendor's MCU link owns (com.ts.MainUI, level 0..30 mapped onto the music stream by
 * its own table): one level per step broadcast, and mute as the mute key. The broadcasts name MainUI's package, as
 * Android 8 delivers no implicit broadcast to a manifest receiver.
 */
class VendorVolume(private val context: Context) {
    fun step(delta: Int) = repeat(abs(delta)) { send(if (delta > 0) UP else DOWN) }

    /** The vendor's mute is a toggle; the rules send this only when the mute state changes. */
    fun mute(on: Boolean) = send(MUTE).also { Log.i(TAG, "mute $on") }

    private fun send(action: String) = runCatching { context.sendBroadcast(Intent(action).setPackage(PACKAGE)) }
        .onFailure { Log.w(TAG, "$action refused", it) }

    private companion object {
        const val TAG = "rdstr.volume"
        const val PACKAGE = "com.ts.MainUI"
        const val UP = "forfan.intent.action.VOLUMEUP"
        const val DOWN = "forfan.intent.action.VOLUMEDN"
        const val MUTE = "forfan.intent.action.MUTE"
    }
}
