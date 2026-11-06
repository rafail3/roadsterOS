package ro.rdstr.os.setup

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ro.rdstr.car.Updater
import java.io.File
import ro.rdstr.ui.Emulates

/**
 * New versions from GitHub Releases (core-car [Updater]): checked once in the background each time the unit joins
 * a Wi-Fi network, offered once per version, downloaded with live figures, checked against GitHub's SHA-256 and
 * installed with PackageInstaller (silent for the system user and the device owner). Installing replaces our own
 * process; the new one says so, counts [Updater.RESTART_IN_S] seconds down and restarts the unit.
 */
class Updates(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Available(val release: Updater.Release, val apk: Updater.Asset, val popUp: Boolean) : State
        data class Downloading(val download: Updater.Download) : State
        data object Installing : State
        data class Restarting(val seconds: Int) : State
        data class Failed(val reason: String) : State
        /** A check asked for without Wi-Fi: the screen offers to connect. */
        data object NeedsWifi : State
        data object UpToDate : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var onWifi = false

    private val current get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** When this version went on the unit, for Settings > About > Software Update (ms since 1970). */
    @Emulates("store.installedAt")
    val installedAt: Long get() = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    /** The last check that got an answer, or 0. */
    val lastChecked: Long get() = prefs.getLong(CHECKED, 0L)

    /** Follows Wi-Fi: a network joined (not one kept) starts a check in the background. */
    fun watchWifi() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(wifi, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val joined = Updater.checkOnJoin(onWifi, true)
                onWifi = true
                if (joined) check()
            }
            override fun onLost(network: Network) { onWifi = false }
        })
    }

    /** Settings > About and the setup check on demand; the result lands in [state]. */
    @Emulates("store.checkForUpdate")
    fun check() {
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        if (!onWifi) { _state.value = State.NeedsWifi; return }
        _state.value = State.Checking
        Thread {
            val result = Updater.check(current, Http.text(Updater.LATEST))
            Log.i(TAG, "check $current -> $result")
            if (result != Updater.Check.Failed) prefs.edit().putLong(CHECKED, System.currentTimeMillis()).apply()
            releaseOut(result)
        }.start()
    }

    /**
     * A newer version on GitHub, or none, as the check found it. The emulator sets this by hand (its control panel's
     * switch, and none once an update is installed); on the unit, the version just installed is what the next check
     * compares against.
     */
    @Emulates("store.setReleaseOut")
    private fun releaseOut(result: Updater.Check) {
        _state.value = when (result) {
            is Updater.Check.Available -> State.Available(result.release, result.apk, Updater.popUp(result, prefs.getString(DISMISSED, null)))
            Updater.Check.UpToDate -> State.UpToDate
            Updater.Check.Failed -> State.Failed("no answer from GitHub")
        }
    }

    /** "Later": this version is not offered in a pop-up again; About still lists it. */
    fun later(version: String) = prefs.edit().putString(DISMISSED, version).apply()

    /**
     * Downloads and installs [apk] once nothing holds it back ([wait] from Updater.installWait: parked, no call, no
     * reverse, enough space). A digest that does not match throws the file away.
     */
    fun install(apk: Updater.Asset, wait: Updater.Wait) {
        if (wait != Updater.Wait.NONE) { _state.value = State.Failed("waiting: $wait"); return }
        Thread {
            runCatching {
                val file = File(context.cacheDir, Updater.APK)
                val sha = Http.download(apk.url, file, apk.bytes) { _state.value = State.Downloading(it) }
                if (apk.sha256 != null && sha != apk.sha256) { file.delete(); error("SHA-256 does not match") }
                _state.value = State.Installing
                commit(file)
            }.onFailure {
                Log.w(TAG, "update failed", it)
                _state.value = State.Failed(it.message ?: "update failed")
            }
        }.start()
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            apk.inputStream().use { input -> s.openWrite(Updater.APK, 0, apk.length()).use { out -> input.copyTo(out); s.fsync(out) } }
            prefs.edit().putBoolean(UPDATED, true).commit()
            val done = PendingIntent.getBroadcast(context, id, Intent(context, Installed::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(done.intentSender)
        }
    }

    /** At each start: right after an update the unit says so, counts down and restarts. */
    fun afterStart() {
        if (!prefs.getBoolean(UPDATED, false)) return
        prefs.edit().putBoolean(UPDATED, false).apply()
        countdown(Updater.RESTART_IN_S)
    }

    private fun countdown(seconds: Int) {
        _state.value = State.Restarting(seconds)
        if (seconds > 0) main.postDelayed({ countdown(seconds - 1) }, 1000) else restart()
    }

    /** REBOOT is a signature permission: granted with the platform key; without it the update still took effect. */
    @SuppressLint("MissingPermission")
    private fun restart() {
        runCatching { context.getSystemService(PowerManager::class.java).reboot(null) }.onFailure { Log.w(TAG, "reboot refused", it) }
    }

    /** PackageInstaller's answer; a failed install is logged, a successful one restarts our process by itself. */
    class Installed : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // not the system user nor the device owner: Android asks on screen
                (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return
            }
            Log.i(TAG, "install status=$status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            if (status != PackageInstaller.STATUS_SUCCESS) context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putBoolean(UPDATED, false).apply()
        }
    }

    private companion object {
        const val TAG = "rdstr.update"
        const val DISMISSED = "dismissed"
        const val UPDATED = "updated"
        const val CHECKED = "checked"
    }
}
