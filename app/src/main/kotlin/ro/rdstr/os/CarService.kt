package ro.rdstr.os

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import android.provider.Settings

class CarService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "roadsterOS", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, Notification.Builder(this, CHANNEL).setContentTitle("roadsterOS").setSmallIcon(android.R.drawable.ic_menu_compass).build())
        applyDeviceOwnerSettings()
        CarRuntime.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    /** Only effective once the installer made us device owner; harmless otherwise. */
    private fun applyDeviceOwnerSettings() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val admin = ComponentName(this, AdminReceiver::class.java)
        dpm.setLockTaskPackages(admin, arrayOf(packageName))
        dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1")
    }

    private companion object { const val CHANNEL = "rdstr" }
}
