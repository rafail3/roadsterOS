package ro.rdstr.cover

import android.content.Context

/** The last thing the sender did, kept for the app's screen. */
object Status {
    private const val PREFS = "status"

    fun sent(context: Context, title: String, device: String) =
        prefs(context).edit().putString("line", "Sent the cover of \"$title\" to $device").putLong("at", System.currentTimeMillis()).apply()

    fun note(context: Context, text: String) = prefs(context).edit().putString("line", text).putLong("at", System.currentTimeMillis()).apply()

    fun line(context: Context): String = prefs(context).getString("line", null) ?: "Nothing sent yet. Play a song with the phone connected to the car."

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
