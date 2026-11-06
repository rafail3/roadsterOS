package ro.rdstr.os.screens

import android.app.Activity
import android.view.MotionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ro.rdstr.car.App
import ro.rdstr.car.AudioSource
import ro.rdstr.car.CarCommand
import ro.rdstr.car.HomeApps
import ro.rdstr.car.HomeCard
import ro.rdstr.car.LaunchableApp
import ro.rdstr.car.Messages
import ro.rdstr.car.ShellAction
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.shell.ShellActions
import ro.rdstr.ui.shell.ShellModel
import ro.rdstr.ui.shell.ShellView

/**
 * Screen.tsx on the unit: the shell's root view bound to the runtime, and what its bar, rail, icons, always-on display
 * and screen-off layer send.
 */
@Mirrors("Screen.tsx")
class ShellScreen(activity: Activity) : Screen {
    private val pressed = MutableStateFlow<String?>(null)
    private var installed: Pair<List<String>, List<LaunchableApp>>? = null
    private var wifi: Pair<String?, Int> = null to 0

    override val view = ShellView(activity, object : ShellActions {
        override fun userActivity() = CarRuntime.userActivity()

        override fun pressRail(id: String) = when (id) {
            "music" -> {
                CarRuntime.switchBluetooth(true)
                CarRuntime.send(CarCommand.Audio.Source(AudioSource.BT))
                CarRuntime.shellAction(ShellAction.OpenApp(App.MUSIC))
            }
            "radio" -> {
                CarRuntime.send(CarCommand.Audio.Source(AudioSource.RADIO))
                CarRuntime.shellAction(ShellAction.OpenApp(App.RADIO))
            }
            "maps" -> CarRuntime.shellAction(ShellAction.OpenApp(App.MAPS))
            "phone" -> openPhone()
            // the picture without reverse gear: the music keeps its level
            else -> CarRuntime.shellAction(ShellAction.OpenCamera)
        }

        override fun launchApp(id: String) {
            when (id) {
                "PHONE" -> openPhone()
                "PHONE_SCREEN", "MAPS", "MUSIC", "RADIO", "SETTINGS" -> CarRuntime.shellAction(ShellAction.OpenApp(App.valueOf(id)))
                else -> CarRuntime.launch(id)
            }
        }

        override fun openDrawer() = CarRuntime.shellAction(ShellAction.OpenDrawer)
        override fun closeDrawer() = CarRuntime.shellAction(ShellAction.CloseDrawer)
        override fun openVolume() = CarRuntime.openVolume()
        override fun moveHomeApp(id: String, by: Int) = CarRuntime.shellAction(ShellAction.MoveHomeApp(id, by))
        override fun toggleHomeApp(id: String) = CarRuntime.shellAction(ShellAction.ToggleHomeApp(id))
        override fun wake() = CarRuntime.shellAction(ShellAction.Wake)
        override fun barDrag(e: MotionEvent): Boolean = drawerDrag?.invoke(e) ?: false
    })

    /** store.openPhone: the phone app, and Bluetooth switched on when it is off. */
    private fun openPhone() {
        CarRuntime.shellAction(ShellAction.OpenApp(App.PHONE))
        CarRuntime.openPhone()
    }

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui, CarRuntime.time.now(), Covers.flow, pressed) { shell, ui, now, cover, key ->
                val chosen = shell.homeApps
                val apps = installed?.takeIf { it.first == chosen }?.second
                    ?: HomeApps.shown(chosen, if (chosen.all { a -> HomeApps.OWN.any { it.id == a } }) emptyList() else CarRuntime.installedApps()).also { installed = chosen to it }
                val ssid = ui.wifi.connected
                if (ssid != wifi.first) wifi = ssid to (ssid?.let { s -> CarRuntime.wifi.networks().find { it.ssid == s }?.signal ?: 3 } ?: 0)
                ShellModel(
                    shell = shell,
                    car = ui.state,
                    now = now,
                    btOn = CarRuntime.radios.bluetoothOn,
                    wifiOn = CarRuntime.radios.wifiOn,
                    wifiSsid = ssid,
                    wifiLevel = wifi.second,
                    phoneStatus = ui.phoneStatus,
                    homeApps = apps,
                    unread = if (ui.messagesSupported == false) 0 else Messages.conversations(ui.messages, ui.read, ui.contacts).sumOf { it.unread },
                    // while Bluetooth music with a cover plays, the home glow takes its colours
                    cover = cover?.takeIf { ui.state.source == AudioSource.BT }?.let { it.top to it.bottom },
                    camera = ui.camera,
                    pressedKey = key,
                )
            }.collect(view::bind)
        }
        // a new source brings its page forward on the card
        scope.launch {
            CarRuntime.ui.map { it.state.source }.distinctUntilChanged().drop(1).collect {
                HomeCard.forSource(CarRuntime.ui.value.state, CarRuntime.radios.bluetoothOn)?.let { page -> CarRuntime.shellAction(ShellAction.ShowCard(page)) }
            }
        }
        // the frame's volume keys: the edge bulges for a moment after each press (the MCU reports presses, not releases)
        scope.launch {
            CarRuntime.ui.map { it.lastEvent }.distinctUntilChanged().collect { e ->
                val key = KEYS.firstOrNull { e == "PanelKey(key=$it)" } ?: return@collect
                pressed.value = key
                delay(BULGE_MS)
                if (pressed.value == key) pressed.value = null
            }
        }
    }

    companion object {
        /** The drawer's drag from the status bar (Drawer.tsx `useDrawerDrag`), set by the drawer's glue; true while it takes the touch. */
        var drawerDrag: ((MotionEvent) -> Boolean)? = null

        private val KEYS = listOf("VOL_UP", "VOL_DOWN")
        private const val BULGE_MS = 300L
    }
}
