package ro.rdstr.carvendor

import android.util.Log
import android.view.Surface

/**
 * The rear camera's picture on a surface of ours, for the camera opened without reverse gear. The vendor's camera
 * screen closes itself unless reverse gear is in, so the picture comes straight from the AutoChips BackCar SDK, as
 * MainUI's BackcarService takes it: setVideoSurface, then start; stop when the view goes. The class sits in a system
 * jar of the unit, not in our APK, hence reflection. Nothing here touches the volume: the vendor lowers the music on
 * the reverse signal only.
 */
class VendorCameraFeed {
    private val backCar: Class<*>? = runCatching { Class.forName("com.autochips.backcar.BackCar") }
        .onFailure { Log.w(TAG, "BackCar SDK not found", it) }.getOrNull()

    /** False when the SDK is missing or refuses a caller outside the vendor's process. */
    fun start(surface: Surface): Boolean = call("setVideoSurface", Surface::class.java to surface) && call("start")

    fun stop() {
        call("stop")
    }

    private fun call(name: String, vararg args: Pair<Class<*>, Any>): Boolean {
        val type = backCar ?: return false
        return runCatching {
            type.getMethod(name, *args.map { it.first }.toTypedArray()).invoke(null, *args.map { it.second }.toTypedArray())
        }.onFailure { Log.w(TAG, "BackCar.$name failed", it) }.isSuccess
    }

    private companion object { const val TAG = "rdstr.camera" }
}
