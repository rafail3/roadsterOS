package ro.rdstr.os

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * adb shell am broadcast -a ro.rdstr.SERVICE -n ro.rdstr.os/.ServiceReceiver opens the service screen;
 * -a ro.rdstr.PREPARE runs the setup's system preparation and logs each task (logcat -s rdstr.setup);
 * -a ro.rdstr.UPDATE checks GitHub for a newer version (logcat -s rdstr.update).
 */
class ServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UPDATE) return CarRuntime.updates.check()
        if (intent.action == PREPARE) {
            val pending = goAsync()
            Thread {
                val p = ro.rdstr.os.boot.SystemPrep(context.applicationContext).runAll()
                android.util.Log.i("rdstr.setup", "prepare ok=${p.ok} ${p.tasks}")
                pending.finish()
            }.start()
            return
        }
        context.startActivity(Intent(context, ServiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object {
        const val PREPARE = "ro.rdstr.PREPARE"
        const val UPDATE = "ro.rdstr.UPDATE"
    }
}
