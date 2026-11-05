package ro.rdstr.cover

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log

/** A Bluetooth device connected: start [CoverListener] again if it stopped when Bluetooth turned off. */
class BluetoothWake : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        Log.i("rdstr", "device connected, starting the cover listener")
        NotificationListenerService.requestRebind(ComponentName(context, CoverListener::class.java))
    }
}
