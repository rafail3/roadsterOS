package ro.rdstr.os.settings

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.content.ComponentName
import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import ro.rdstr.os.AdminReceiver
import ro.rdstr.ui.Emulates

/**
 * The unit's radios as the settings and the drawer switch them: Bluetooth, Wi-Fi, location.
 * Location is a secure setting; only the device owner may change it (DevicePolicyManager.setSecureSetting).
 */
@SuppressLint("MissingPermission")
class Radios(private val context: Context) {
    private val wifi get() = context.applicationContext.getSystemService(WifiManager::class.java)

    val bluetoothOn: Boolean get() = BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
    val wifiOn: Boolean get() = wifi?.isWifiEnabled == true
    val locationOn: Boolean
        get() = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF

    fun bluetooth(on: Boolean) = report("bluetooth", on) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@report false
        if (on) adapter.enable() else adapter.disable()
    }

    fun wifi(on: Boolean) = report("wifi", on) { wifi?.setWifiEnabled(on) == true }

    @Emulates("store.setLocation")
    fun location(on: Boolean) = report("location", on) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) return@report false
        val mode = if (on) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF
        dpm.setSecureSetting(ComponentName(context, AdminReceiver::class.java), Settings.Secure.LOCATION_MODE, mode.toString())
        true
    }

    private fun report(what: String, on: Boolean, change: () -> Boolean): Boolean {
        val ok = runCatching(change).onFailure { Log.w(TAG, "$what refused", it) }.getOrDefault(false)
        Log.i(TAG, "$what ${if (on) "on" else "off"} -> $ok")
        return ok
    }

    companion object { const val TAG = "rdstr.settings" }
}
