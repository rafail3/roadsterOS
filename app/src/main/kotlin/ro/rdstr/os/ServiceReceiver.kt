package ro.rdstr.os

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** adb shell am broadcast -a ro.rdstr.SERVICE */
class ServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(Intent(context, ServiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
