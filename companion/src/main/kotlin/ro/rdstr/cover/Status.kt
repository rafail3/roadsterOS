package ro.rdstr.cover

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** What the app is doing right now, kept for its screen, which follows every change live. */
object Status {
    enum class Delivery { SENDING, SENT, NO_DEVICE, NO_DISPLAY, NEED_PERMISSION, PAUSED }

    const val TITLE = "title"
    const val ARTIST = "artist"
    const val APP = "app"
    const val PLAYING = "playing"
    const val DELIVERY = "delivery"
    const val DEVICE = "device"
    const val AT = "at"
    const val COVER_AT = "cover_at"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("status", Context.MODE_PRIVATE)

    fun track(context: Context, title: String, artist: String, app: String) =
        prefs(context).edit().putString(TITLE, title).putString(ARTIST, artist).putString(APP, app).putBoolean(PLAYING, true).apply()

    fun playing(context: Context, on: Boolean) = prefs(context).edit().putBoolean(PLAYING, on).apply()

    fun delivery(context: Context, state: Delivery, device: String? = null) =
        prefs(context).edit().putString(DELIVERY, state.name).putString(DEVICE, device).putLong(AT, System.currentTimeMillis()).apply()

    fun delivery(prefs: SharedPreferences): Delivery? = prefs.getString(DELIVERY, null)?.let { runCatching { Delivery.valueOf(it) }.getOrNull() }

    /** Nothing plays any more: the screen goes back to its empty state. */
    fun clear(context: Context) {
        coverFile(context).delete()
        prefs(context).edit().remove(TITLE).remove(ARTIST).remove("line").remove(APP).remove(PLAYING).putLong(COVER_AT, System.currentTimeMillis()).apply()
    }

    /** The JPEG that goes to the car, shown on the screen as it is. */
    fun coverFile(context: Context) = File(context.filesDir, "cover.jpg")

    fun cover(context: Context, jpeg: ByteArray) {
        val file = coverFile(context)
        val part = File(file.path + ".part")
        part.writeBytes(jpeg)
        part.renameTo(file)
        prefs(context).edit().putLong(COVER_AT, System.currentTimeMillis()).apply()
    }
}
