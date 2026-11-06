package ro.rdstr.os.screens

import android.app.Activity
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.CarCommand
import ro.rdstr.car.ShellAction
import ro.rdstr.os.CarRuntime
import ro.rdstr.os.settings.BluetoothDevices
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.sheets.DevicesSheetView
import ro.rdstr.ui.sheets.DevicesState

/**
 * DevicesSheet.tsx on the unit. The saved phones are Android's bonded devices; the connected one is the hands-free
 * phone. Connect and Disconnect go over the bus to the vendor's Bluetooth app; the search, pairing with its code check,
 * forgetting and the connecting and failed states are Android's own Bluetooth (BluetoothDevices). The commands are
 * still sent, so the journal and the rules see them as in the emulator.
 */
@Mirrors("DevicesSheet.tsx")
class DevicesScreen(activity: Activity) : Screen {
    override val view = DevicesSheetView(activity)

    init {
        RadioWatch.start(activity)
        BluetoothDevices.start(activity)
        view.actions = object : DevicesSheetView.Actions {
            override fun setBluetooth(on: Boolean) { if (on) CarRuntime.turnOnBluetooth() else CarRuntime.radios.bluetooth(false) }
            override fun connect(name: String) { BluetoothDevices.connecting(name); CarRuntime.send(CarCommand.Bluetooth.Connect(name)) }
            override fun disconnect() = CarRuntime.send(CarCommand.Bluetooth.Disconnect)
            override fun forget(name: String) { BluetoothDevices.forget(name); CarRuntime.send(CarCommand.Bluetooth.Forget(name)) }
            override fun scan() { BluetoothDevices.scan(); CarRuntime.send(CarCommand.Bluetooth.Scan) }
            override fun pair(name: String) { BluetoothDevices.pair(name); CarRuntime.send(CarCommand.Bluetooth.Pair(name)) }
            override fun confirmPairing(accept: Boolean) { BluetoothDevices.confirm(accept); CarRuntime.send(CarCommand.Bluetooth.ConfirmPairing(accept)) }
            override fun close() = CarRuntime.shellAction(ShellAction.CloseDevices)
        }
    }

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.ui, CarRuntime.shell, RadioWatch.tick, BluetoothDevices.state) { ui, shell, _, bt ->
                if (ui.state.btConnected) BluetoothDevices.connected()
                DevicesState(
                    open = shell.devices,
                    btOn = CarRuntime.radios.bluetoothOn,
                    connected = ui.phoneName.takeIf { ui.state.btConnected && it.isNotEmpty() },
                    saved = bonded(),
                    connecting = bt.connecting,
                    failed = bt.failed,
                    scanning = bt.scanning,
                    searched = bt.searched,
                    nearby = bt.nearby,
                    pairing = bt.pairing?.let { DevicesState.Pairing(it.name, it.code, it.confirmed) },
                )
            }.collect(view::bind)
        }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH is granted at install on API 27
    private fun bonded(): List<String> =
        runCatching { BluetoothAdapter.getDefaultAdapter()?.takeIf { it.isEnabled }?.bondedDevices?.mapNotNull { it.name }?.sorted() }.getOrNull().orEmpty()
}
