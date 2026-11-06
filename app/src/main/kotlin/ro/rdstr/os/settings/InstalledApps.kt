package ro.rdstr.os.settings

import android.content.Context
import android.content.Intent
import android.util.Log
import ro.rdstr.car.LaunchableApp

/** The other apps on the unit that can be started from home: launcher apps, without ours and the vendor's car stack. */
object InstalledApps {
    private val HIDDEN = listOf("com.ts.", "com.autochips.", "com.android.settings", "com.mediatek.")

    fun read(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { (pkg, _) -> pkg != context.packageName && HIDDEN.none { pkg.startsWith(it) } }
            .distinctBy { it.first }
            .map { (pkg, label) -> LaunchableApp(pkg, label) }
            .sortedBy { it.label.lowercase() }
    }

    /** Opens another app full screen; our apps open inside the shell instead. */
    fun launch(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = intent != null && runCatching { context.startActivity(intent) }.isSuccess
        Log.i(Radios.TAG, "launch $pkg -> $ok")
        return ok
    }
}
