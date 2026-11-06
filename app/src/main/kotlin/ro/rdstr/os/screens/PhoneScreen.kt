package ro.rdstr.os.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.App
import ro.rdstr.car.CarCommand
import ro.rdstr.car.PhoneGate
import ro.rdstr.car.ShellAction
import ro.rdstr.os.CarRuntime
import ro.rdstr.os.settings.BluetoothDevices
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.keyboard.KeyboardView
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.phone.KeyboardSlot
import ro.rdstr.ui.phone.PhoneAppView
import ro.rdstr.ui.phone.PhoneState

/**
 * PhoneApp.tsx on the unit: the phone app bound to the runtime (its phone book, history and messages, the phone's
 * network), its actions sent to CarRuntime, and our keyboard under its search and message fields.
 */
@Mirrors("PhoneApp.tsx")
class PhoneScreen(activity: Activity) : Screen {
    private val keyboard = KeyboardView(activity).also { kb -> UiTheme.link(kb) { kb.setTheme(UiTheme.light, UiTheme.glassy); kb.setFont(if (UiTheme.geist) "Geist" else "Inter") } }

    override val view = PhoneAppView(activity, object : PhoneAppView.Actions {
        override fun shellAction(action: ShellAction) = CarRuntime.shellAction(action)
        override fun carCommand(command: CarCommand) {
            if (command is CarCommand.Bluetooth.Connect) BluetoothDevices.connecting(command.device)
            CarRuntime.send(command)
        }
        override fun turnOnBluetooth() = CarRuntime.turnOnBluetooth()
        override fun openThread(number: String?) = CarRuntime.openThread(number)
        override fun markRead(ids: List<String>) = CarRuntime.markRead(ids)
    }, object : KeyboardSlot {
        override val view get() = keyboard
        override fun bind(open: Boolean, submitLabel: String, submitEnabled: Boolean, keys: KeyboardSlot.Keys) {
            keyboard.open = open
            keyboard.submitLabel = submitLabel
            keyboard.submitEnabled = submitEnabled
            keyboard.onKey = { keys.onKey(it.toString()) }
            keyboard.onBackspace = keys::onBackspace
            keyboard.onSubmit = keys::onSubmit
        }
    })

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            BluetoothDevices.start(view.context)
            combine(CarRuntime.shell, CarRuntime.ui, MapsScreen.speed, BluetoothDevices.state) { s, ui, speed, bt ->
                val btOn = CarRuntime.radios.bluetoothOn
                val screen = PhoneGate.screen(btOn, ui.state.hfpConnected)
                PhoneState(
                    open = s.app == App.PHONE,
                    screen = screen,
                    btTurningOn = ui.btTurningOn,
                    device = ui.phoneName,
                    operator = ui.phoneStatus?.operator.orEmpty(),
                    signal = ui.phoneStatus?.signal ?: 0,
                    battery = ui.phoneStatus?.battery ?: 0,
                    contacts = ui.contacts,
                    calls = ui.calls,
                    messages = ui.messages,
                    read = ui.read,
                    messagesSupported = ui.messagesSupported ?: true,
                    thread = ui.thread,
                    stopped = speed.stopped,
                    connecting = bt.connecting,
                    failed = bt.failed,
                    lockTypingWhileMoving = s.lockTypingWhileMoving,
                    region = s.phoneRegion,
                    saved = if (screen == PhoneGate.Screen.CONNECT) savedPhones() else emptyList(),
                )
            }.collect(view::bind)
        }
    }

    /** Saved phones that take calls (core-car PhoneGate.callCapable). */
    @SuppressLint("MissingPermission")
    private fun savedPhones(): List<String> = runCatching {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty()
            .filter { PhoneGate.callCapable(it.bluetoothClass?.majorDeviceClass ?: 0) }
            .mapNotNull { it.name }.sorted()
    }.getOrDefault(emptyList())
}
