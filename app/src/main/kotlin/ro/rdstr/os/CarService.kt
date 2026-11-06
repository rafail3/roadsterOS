package ro.rdstr.os

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder

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
        // Android's Settings may open over the lock task, from the Developer section
        dpm.setLockTaskPackages(admin, arrayOf(packageName, "com.android.settings"))
        // adb follows the Developer section (DeveloperTools, applied as CarRuntime starts)
        // the phone app reads the phone book and call history the Bluetooth sync copies to the unit
        // and the phone's messages (the MAP client's broadcast needs RECEIVE_SMS, a reply SEND_SMS)
        for (permission in arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
            // the clock set from GPS fixes
            Manifest.permission.ACCESS_FINE_LOCATION)) {
            dpm.setPermissionGrantState(admin, packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
    }

    private companion object { const val CHANNEL = "rdstr" }
}
