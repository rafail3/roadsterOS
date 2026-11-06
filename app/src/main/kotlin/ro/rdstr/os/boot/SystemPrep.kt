package ro.rdstr.os.boot

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import ro.rdstr.car.Setup
import ro.rdstr.os.AdminReceiver
import ro.rdstr.os.HomeActivity

/**
 * The setup's system preparation on the unit (core-car Setup.Prepare): each task does its work and says whether it
 * worked. Signed with the public platform key, the app runs as the system user on firmware built with test keys;
 * as the device owner (set by the installer over adb) it hides packages, grants its permissions and holds HOME.
 */
class SystemPrep(private val context: Context) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, AdminReceiver::class.java)
    private val owner get() = dpm.isDeviceOwnerApp(context.packageName)

    fun run(task: Setup.Task): Boolean = runCatching {
        when (task) {
            Setup.Task.KEYS -> Process.myUid() == Process.SYSTEM_UID
            Setup.Task.PACKAGES -> hidePackages()
            Setup.Task.PERMISSIONS -> grantPermissions()
            Setup.Task.HOME -> holdHome()
        }
    }.onFailure { Log.w(TAG, "$task failed", it) }.getOrDefault(false).also { Log.i(TAG, "$task -> $it") }

    /** Runs every task in order, as the preparation screen does, and returns where it got. */
    fun runAll(): Setup.Prepare {
        var p = Setup.Prepare()
        while (true) {
            val task = p.next ?: return p
            p = p.running(task).result(task, run(task))
        }
    }

    /** The packages listed in assets/debloat.txt (one per line, # for comments) are hidden for the user, reversibly. */
    private fun hidePackages(): Boolean {
        if (!owner) return false
        val names = context.assets.open("debloat.txt").bufferedReader().readLines()
            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        return names.all { pkg ->
            val installed = runCatching { context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.isSuccess
            !installed || dpm.setApplicationHidden(admin, pkg, true) || dpm.isApplicationHidden(admin, pkg)
        }
    }

    private fun grantPermissions(): Boolean {
        if (!owner) return RUNTIME.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return RUNTIME.all { dpm.setPermissionGrantState(admin, context.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
    }

    /** roadsterOS is the home screen for good: the device owner's preferred HOME, kept across reinstalls. */
    private fun holdHome(): Boolean {
        if (owner) {
            val home = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
            dpm.addPersistentPreferredActivity(admin, home, ComponentName(context, HomeActivity::class.java))
        }
        val resolved = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    private companion object {
        const val TAG = "rdstr.setup"
        val RUNTIME = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
        )
    }
}
