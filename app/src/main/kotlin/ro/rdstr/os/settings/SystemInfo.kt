package ro.rdstr.os.settings

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import android.util.Log
import ro.rdstr.car.Settings

/** The About page: our version, the unit's Android, memory (ours and free) and storage, as the screen writes them. */
data class SystemInfo(
    val version: String,
    val android: String,
    val model: String,
    val appMemory: String,
    val freeMemory: String,
    val totalMemory: String,
    val freeStorage: String,
    val totalStorage: String,
    /** The Date & Time page: the time zone's id, and whether Android sets the time and the zone from the network and GPS. */
    val timeZone: String,
    val autoTime: Boolean,
    val autoTimeZone: Boolean,
) {
    companion object {
        fun read(context: Context): SystemInfo {
            val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            val disk = StatFs(Environment.getDataDirectory().path)
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
            return SystemInfo(
                version = version,
                android = Build.VERSION.RELEASE,
                model = Build.MODEL,
                // our own process, the figure the 30 MB budget is about
                appMemory = Settings.size(Debug.getPss() * 1024),
                freeMemory = Settings.size(memory.availMem),
                totalMemory = Settings.size(memory.totalMem),
                freeStorage = Settings.size(disk.availableBytes),
                totalStorage = Settings.size(disk.totalBytes),
                timeZone = java.util.TimeZone.getDefault().id,
                autoTime = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.AUTO_TIME, 1) == 1,
                autoTimeZone = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.AUTO_TIME_ZONE, 1) == 1,
            ).also { Log.i(Radios.TAG, "about $it") }
        }
    }
}
