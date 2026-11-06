package ro.rdstr.os.screens

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.App
import ro.rdstr.car.CameraSettings
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent
import ro.rdstr.car.ShellAction
import ro.rdstr.car.SoundSettings
import ro.rdstr.car.TimeSource
import ro.rdstr.car.Updater
import ro.rdstr.os.CarRuntime
import ro.rdstr.os.setup.Updates
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.settings.About
import ro.rdstr.ui.settings.PackDownload
import ro.rdstr.ui.settings.RestartPromptView
import ro.rdstr.ui.settings.SettingsActions
import ro.rdstr.ui.settings.SettingsSlots
import ro.rdstr.ui.settings.SettingsState
import ro.rdstr.ui.settings.SettingsView
import ro.rdstr.ui.settings.Update

/**
 * SettingsApp.tsx on the unit: [CarRuntime.ui], its shell, the unit's clock and the updater's state go to
 * [SettingsView.bind]; it is open while the shell's app is Settings. [SettingsScreen.slots] makes the Wi-Fi list and the unit's
 * picture (NetworksSheet.tsx, UnitPicture.tsx); without them those places stay empty.
 */
@Mirrors("SettingsApp.tsx")
class SettingsScreen(activity: Activity) : Screen {
    override val view = SettingsView(activity, slots).apply { actions = SettingsGlue.Actions(activity) }

    override fun bind(scope: CoroutineScope) = SettingsGlue.follow(view, scope)

    companion object {
        /** The makers of the Wi-Fi list and the unit's picture, set before the activity makes its screens. */
        var slots: SettingsSlots? = null
    }
}

/** RestartPrompt.tsx on the unit: open while the runtime asks (`UiState.restartAsked`). */
@Mirrors("RestartPrompt.tsx")
class RestartPromptScreen(activity: Activity) : Screen {
    override val view = RestartPromptView(activity).apply { actions = SettingsGlue.Actions(activity) }

    override fun bind(scope: CoroutineScope) {
        scope.launch { CarRuntime.ui.collect { view.open = it.restartAsked } }
    }
}

internal object SettingsGlue {
    fun follow(view: SettingsView, scope: CoroutineScope) {
        val context = view.context
        val installed = CarRuntime.installedApps()
        var about = About()
        var aboutFor: String? = null
        var lastDownload: Updater.Download? = null
        scope.launch {
            combine(CarRuntime.ui, CarRuntime.shell, CarRuntime.time.now(), CarRuntime.updates.state) { ui, shell, now, update -> Quad(ui, shell, now, update) }
                .collect { (ui, shell, now, update) ->
                    view.open = shell.app == App.SETTINGS
                    // About is read when it opens, as the page reads SystemInfo once
                    if (shell.settingsPage != aboutFor) {
                        aboutFor = shell.settingsPage
                        if (shell.settingsPage == "about") about = CarRuntime.systemInfo().let {
                            About(it.version, it.android, it.model, "${context.resources.displayMetrics.widthPixels} × ${context.resources.displayMetrics.heightPixels}",
                                it.appMemory, it.freeMemory, it.totalMemory, it.freeStorage, it.totalStorage)
                        }
                    }
                    if (update is Updates.State.Downloading) lastDownload = update.download
                    val radios = CarRuntime.radios
                    view.bind(shell, ui.state, SettingsState(
                        wifiOn = radios.wifiOn,
                        locationOn = radios.locationOn,
                        wifiConnected = ui.wifi.connected,
                        btOn = radios.bluetoothOn,
                        device = ui.phoneName,
                        savedDevices = savedDevices(),
                        now = now,
                        timeZone = java.util.TimeZone.getDefault().id,
                        packs = ui.maps.installed,
                        downloading = ui.maps.downloading?.let { PackDownload(it.code, it.progress) },
                        installed = installed,
                        about = about,
                        update = update(update, lastDownload),
                        adbAddress = CarRuntime.adbAddress(),
                    ))
                }
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    @android.annotation.SuppressLint("MissingPermission") // BLUETOOTH is granted at install on API 27
    private fun savedDevices() = runCatching { BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.size ?: 0 }.getOrDefault(0)

    /** The updater's state as Software Update shows it. */
    private fun update(state: Updates.State, last: Updater.Download?): Update {
        val u = CarRuntime.updates
        val base = Update(checkedAt = u.lastChecked, installedAt = u.installedAt)
        return when (state) {
            Updates.State.Idle, Updates.State.NeedsWifi -> base
            Updates.State.Checking -> base.copy(check = Update.Check.CHECKING)
            Updates.State.UpToDate -> base.copy(check = Update.Check.LATEST)
            is Updates.State.Failed -> base.copy(check = Update.Check.FAILED)
            is Updates.State.Available -> base.copy(check = Update.Check.AVAILABLE, releaseOut = state.release.version, releaseBytes = state.apk.bytes)
            is Updates.State.Downloading -> base.copy(download = state.download)
            // the file is in: the row reads installed while Android swaps the app, then the new one counts down
            Updates.State.Installing -> base.copy(download = last?.copy(done = last.total), restartIn = Updater.RESTART_IN_S)
            is Updates.State.Restarting -> base.copy(restartIn = state.seconds)
        }
    }

    class Actions(private val context: Context) : SettingsActions {
        override fun shell(action: ShellAction) = CarRuntime.shellAction(action)
        override fun car(command: CarCommand) = CarRuntime.send(command)
        override fun setWifi(on: Boolean) = CarRuntime.setWifi(on)
        override fun setBluetooth(on: Boolean) = CarRuntime.switchBluetooth(on)
        override fun setLocation(on: Boolean) { CarRuntime.radios.location(on) }
        override fun setTimeSync(auto: Boolean, source: TimeSource) = CarRuntime.shellAction(ShellAction.SetTimeSync(auto, source))
        override fun shiftClock(ms: Long) { CarRuntime.time.set(System.currentTimeMillis() + ms) }
        override fun setTimeZone(id: String) { CarRuntime.time.setZone(id) }
        override fun timeZones(): List<String> = CarRuntime.time.zones()
        override fun zoneOffsetMinutes(id: String): Int = CarRuntime.time.offsetMinutes(id)
        override fun setVolume(level: Int) = CarRuntime.setVolume(level)
        override fun setSound(next: SoundSettings) = CarRuntime.setSound(next)
        override fun setCamera(next: CameraSettings) = CarRuntime.setCamera(next)
        override fun setCastVisible(on: Boolean) = CarRuntime.setCastVisible(on)
        override fun removePack(code: String) { CarRuntime.removePack(code) }
        override fun checkForUpdate() = CarRuntime.updates.check()

        /** Download and Install: waits while the car moves, a call runs, reverse is in or the space is short (Updater.installWait). */
        override fun installUpdate() {
            val available = CarRuntime.updates.state.value as? Updates.State.Available ?: return
            val ui = CarRuntime.ui.value
            val free = runCatching { StatFs(context.cacheDir.path).availableBytes }.getOrDefault(0L)
            // ponytail: no speed on the runtime yet, so a moving car is not held back here; Updater.installWait takes it once it is
            val wait = Updater.installWait(0.0, ui.state.call != CarEvent.Call.State.IDLE, ui.camera, free, available.apk.bytes)
            CarRuntime.updates.install(available.apk, wait)
        }

        override fun askRestart(on: Boolean) = CarRuntime.askRestart(on)
        override fun restartUnit() { CarRuntime.restartUnit() }
        override fun openAndroidSettings() { CarRuntime.openAndroidSettings() }
        override fun factoryReset() = CarRuntime.factoryReset()
    }
}
