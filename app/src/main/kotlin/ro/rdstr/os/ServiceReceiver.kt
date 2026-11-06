package ro.rdstr.os

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * adb shell am broadcast -a ro.rdstr.SERVICE -n ro.rdstr.os/.ServiceReceiver opens the service screen;
 * -a ro.rdstr.PREPARE runs the setup's system preparation and logs each task (logcat -s rdstr.setup);
 * -a ro.rdstr.UPDATE checks GitHub for a newer version (logcat -s rdstr.update).
 * Test commands (scripts/device-test.ps1), JSON as in the emulator and the journal:
 * -a ro.rdstr.SHELL --es json '{"type":"OpenApp","app":"SETTINGS"}' runs a shell action;
 * -a ro.rdstr.EVENT --es json '{...}' feeds a car event through the rules; -a ro.rdstr.COMMAND --es json '{...}' sends a
 * car command. Only adb's shell can send them: the receiver needs android.permission.DUMP (manifest).
 */
class ServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UPDATE) return CarRuntime.updates.check()
        val text = intent.getStringExtra("json")
        if (text != null) {
            runCatching {
                when (intent.action) {
                    SHELL -> CarRuntime.shellAction(ro.rdstr.car.DebugJson.shellAction(text))
                    EVENT -> CarRuntime.injectEvent(ro.rdstr.car.DebugJson.event(text))
                    COMMAND -> CarRuntime.send(ro.rdstr.car.DebugJson.command(text))
                }
            }.onFailure { android.util.Log.w("rdstr.test", "${intent.action} $text", it) }
            return
        }
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
        const val SHELL = "ro.rdstr.SHELL"
        const val EVENT = "ro.rdstr.EVENT"
        const val COMMAND = "ro.rdstr.COMMAND"
    }
}
