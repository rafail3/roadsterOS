package ro.rdstr.os.settings

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import ro.rdstr.os.AdminReceiver
import ro.rdstr.os.HomeActivity

/** The Developer section on the unit: adb, its Wi-Fi address, restarting roadsterOS, Android's own developer options. */
object DeveloperTools {
    private const val TAG = "rdstr.dev"
    const val ADB_PORT = 5555

    /** adb on or off; a device-owner global setting, so it holds across restarts until changed here. */
    fun setAdb(context: Context, on: Boolean) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        runCatching { dpm.setGlobalSetting(ComponentName(context, AdminReceiver::class.java), Settings.Global.ADB_ENABLED, if (on) "1" else "0") }
            .onFailure { Log.w(TAG, "adb not switched", it) }
        Log.i(TAG, "adb=$on")
    }

    /** "192.168.43.120:5555" on Wi-Fi, null otherwise. */
    @Suppress("DEPRECATION") // API 27: WifiInfo.ipAddress is the way to read it
    fun adbAddress(context: Context): String? {
        val ip = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}:$ADB_PORT"
    }

    /** Ends our process; an alarm opens home again half a second later, and the service starts with it. */
    fun restart(context: Context) {
        val home = Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(context, 0, home, PendingIntent.FLAG_CANCEL_CURRENT)
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RESTART_DELAY_MS, pending)
        Log.i(TAG, "restart")
        Process.killProcess(Process.myPid())
    }

    /** Android's developer options; the lock task allows the Settings app (CarService). */
    fun openAndroidSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        Log.i(TAG, "android settings ok=$ok")
        return ok
    }

    private const val RESTART_DELAY_MS = 500L
}
