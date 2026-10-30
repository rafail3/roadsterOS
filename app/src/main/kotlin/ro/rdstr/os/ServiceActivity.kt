package ro.rdstr.os

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button

class ServiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_service)
        findViewById<Button>(R.id.android_settings).setOnClickListener {
            runCatching { stopLockTask() }
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        findViewById<Button>(R.id.release_owner).setOnClickListener {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            @Suppress("DEPRECATION")
            if (dpm.isDeviceOwnerApp(packageName)) dpm.clearDeviceOwnerApp(packageName)
            finish()
        }
    }
}
