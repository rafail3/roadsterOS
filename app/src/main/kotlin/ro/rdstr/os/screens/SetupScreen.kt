package ro.rdstr.os.screens

import android.app.Activity
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.rdstr.car.CarEvent
import ro.rdstr.car.Design
import ro.rdstr.car.Setup
import ro.rdstr.car.ShellAction
import ro.rdstr.car.ThemeMode
import ro.rdstr.os.CarRuntime
import ro.rdstr.os.boot.SystemPrep
import ro.rdstr.os.setup.SetupSteps
import ro.rdstr.os.setup.Updates
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.setup.SetupFile
import ro.rdstr.ui.setup.SetupPlace
import ro.rdstr.ui.setup.SetupState
import ro.rdstr.ui.setup.SetupView
import ro.rdstr.ui.setup.SetupWifi
import java.util.TimeZone

/**
 * SetupScreens.tsx and update.ts on the unit: the setup's screens from the car's name to the welcome, over the shell
 * while the setup runs. The system preparation runs SystemPrep's tasks one after the other; the update screen follows
 * Updates; the files screen checks the map on the unit (downloading it on Wi-Fi when missing) and the fonts.
 */
@Mirrors("SetupScreens.tsx", "update.ts")
class SetupScreen(private val activity: Activity) : Screen {
    override val view = SetupView(activity, object : SetupView.Actions {
        override fun next() = SetupScreen.next()
        override fun back() = CarRuntime.shellAction(ShellAction.SetupBack)
        override fun setCarName(name: String) = CarRuntime.shellAction(ShellAction.SetCarName(name))
        override fun retryPrepare() { prepare.value = prepare.value.retry() }
        override fun wifiScan() = CarRuntime.wifi.scan()
        override fun wifiConnect(ssid: String, password: String?) = CarRuntime.wifi.connect(ssid, password)
        override fun turnOnWifi() { CarRuntime.radios.location(true); CarRuntime.setWifi(true) }
        override fun install() = CarRuntime.ui.value.state.let {
            CarRuntime.updates.installAvailable(it.call != CarEvent.Call.State.IDLE, it.camera)
        }
        override fun setPhoneRegion(region: String) = CarRuntime.shellAction(ShellAction.SetPhoneRegion(region))
        override fun setDesign(design: Design) = CarRuntime.shellAction(ShellAction.SetDesign(design))
        override fun setTheme(mode: ThemeMode) = CarRuntime.shellAction(ShellAction.SetTheme(mode))
    })

    private val steps by lazy { SetupSteps(activity.applicationContext) }
    private val place by lazy { runCatching { steps.place() }.getOrNull() }
    private var release = ""

    override fun bind(scope: CoroutineScope) {
        // the setup moves past the update screen just before the unit restarts, and resumes after it
        CarRuntime.updates.beforeRestart = { if (CarRuntime.shell.value.setupStep == Setup.Step.UPDATE) next() }
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui, CarRuntime.updates.state, prepare, files) { shell, ui, update, p, f ->
                if (update is Updates.State.Available) release = update.release.version
                if (update is Updates.State.Restarting && release.isEmpty()) release = versionName()
                val here = place?.country.orEmpty()
                SetupState(
                    step = shell.setupStep,
                    carName = shell.carName,
                    prepare = p,
                    wifi = SetupWifi(
                        on = CarRuntime.radios.wifiOn, locationOn = CarRuntime.radios.locationOn,
                        networks = ui.wifi.seen.map { Setup.Network(it.ssid, it.signal, it.secure) },
                        connected = ui.wifi.connected, connecting = ui.wifi.connecting, wrongPassword = ui.wifi.wrongPassword,
                        saved = ui.wifi.saved, searching = ui.wifi.seen.isEmpty(),
                    ),
                    release = release,
                    update = CarRuntime.updates.install(update),
                    place = SetupPlace(here, TimeZone.getDefault().id, here.ifEmpty { "RO" } in ui.maps.installed, shell.phoneRegion),
                    design = shell.design,
                    theme = shell.theme,
                    files = f,
                ) to (!shell.setupDone && shell.setupStep.ordinal >= Setup.Step.CAR_NAME.ordinal)
            }.collect { (state, shown) ->
                view.visibility = if (shown) View.VISIBLE else View.GONE
                if (!shown) return@collect
                view.bind(state)
                if (state.step == Setup.Step.PREPARE) runPrepare(scope)
                if (state.step == Setup.Step.FILES) checkFiles(scope, state.place.country.ifEmpty { "RO" })
            }
        }
    }

    private var preparing: Job? = null

    /** The next waiting task as soon as none runs (SystemPrep.run, off the main thread). */
    private fun runPrepare(scope: CoroutineScope) {
        if (preparing?.isActive == true) return
        val task = prepare.value.next ?: return
        prepare.value = prepare.value.running(task)
        preparing = scope.launch {
            val ok = withContext(Dispatchers.IO) { SystemPrep(activity.applicationContext).run(task) }
            prepare.value = prepare.value.result(task, ok)
        }
    }

    private var checking: Job? = null

    /**
     * The files the OS ships with, one after the other, each at the pace the emulator shows (60 ms steps): the map of
     * the country, on the unit already or downloaded on Wi-Fi in the background, and the fonts, inside the app.
     */
    private fun checkFiles(scope: CoroutineScope, code: String) {
        if (checking?.isActive == true || files.value.all { it.state == Setup.TaskState.DONE }) return
        checking = scope.launch {
            if (!withContext(Dispatchers.IO) { steps.hasPack(code) }) CarRuntime.downloadPack(code)
            for (i in files.value.indices) {
                val ms = FILE_MS[i]
                val start = System.currentTimeMillis()
                while (true) {
                    val f = ((System.currentTimeMillis() - start).toFloat() / ms).coerceAtMost(1f)
                    files.value = files.value.mapIndexed { k, file -> if (k == i) file.copy(state = Setup.TaskState.RUNNING, fraction = f) else file }
                    if (f >= 1f) break
                    delay(TICK_MS)
                }
                files.value = files.value.mapIndexed { k, file -> if (k == i) file.copy(state = Setup.TaskState.DONE, fraction = 0f) else file }
            }
        }
    }

    private fun versionName() = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull().orEmpty()

    companion object {
        /** The system preparation, kept across pauses; it runs once per setup. */
        private val prepare = MutableStateFlow(Setup.Prepare())
        private val files = MutableStateFlow(listOf(
            SetupFile("Map of Romania", "363 MB", Setup.TaskState.WAITING),
            SetupFile("Fonts", "1.8 MB", Setup.TaskState.WAITING),
        ))
        private val FILE_MS = longArrayOf(1400, 900)
        private const val TICK_MS = 60L

        /**
         * The setup's next screen (Screen.tsx `next`), with whether the unit is on Wi-Fi and a newer version is out,
         * so the update screen shows only then. HomeActivity's start screens may call it too.
         */
        fun next() = CarRuntime.shellAction(ShellAction.SetupNext(
            wifi = CarRuntime.ui.value.wifi.connected != null,
            updateAvailable = CarRuntime.updates.state.value is Updates.State.Available,
        ))
    }
}
