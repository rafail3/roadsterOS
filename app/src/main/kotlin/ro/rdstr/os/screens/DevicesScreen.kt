package ro.rdstr.os.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.CarCommand
import ro.rdstr.car.ShellAction
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.sheets.DevicesSheetView
import ro.rdstr.ui.sheets.DevicesState

/**
 * DevicesSheet.tsx on the unit. The saved phones are Android's bonded devices; the connected one is the hands-free
 * phone. The bus carries Connect and Disconnect; the search, pairing and forgetting commands are sent as the sheet
 * asks for them, and their states (connecting, failed, nearby, the code check) show once the bus reports them.
 */
@Mirrors("DevicesSheet.tsx")
object DevicesScreen {
    fun view(context: Context, scope: CoroutineScope): DevicesSheetView {
        RadioWatch.start(context)
        val view = DevicesSheetView(context)
        view.actions = object : DevicesSheetView.Actions {
            override fun setBluetooth(on: Boolean) { if (on) CarRuntime.turnOnBluetooth() else CarRuntime.radios.bluetooth(false) }
            override fun connect(name: String) = CarRuntime.send(CarCommand.Bluetooth.Connect(name))
            override fun disconnect() = CarRuntime.send(CarCommand.Bluetooth.Disconnect)
            override fun forget(name: String) = CarRuntime.send(CarCommand.Bluetooth.Forget(name))
            override fun scan() = CarRuntime.send(CarCommand.Bluetooth.Scan)
            override fun pair(name: String) = CarRuntime.send(CarCommand.Bluetooth.Pair(name))
            override fun confirmPairing(accept: Boolean) = CarRuntime.send(CarCommand.Bluetooth.ConfirmPairing(accept))
            override fun close() = CarRuntime.shellAction(ShellAction.CloseDevices)
        }
        scope.launch(Dispatchers.Main) {
            combine(CarRuntime.ui, CarRuntime.shell, RadioWatch.tick) { ui, shell, _ ->
                DevicesState(
                    open = shell.devices,
                    btOn = CarRuntime.radios.bluetoothOn,
                    connected = ui.phoneName.takeIf { ui.state.btConnected && it.isNotEmpty() },
                    saved = bonded(),
                )
            }.collect(view::bind)
        }
        return view
    }

    @SuppressLint("MissingPermission") // BLUETOOTH is granted at install on API 27
    private fun bonded(): List<String> =
        runCatching { BluetoothAdapter.getDefaultAdapter()?.takeIf { it.isEnabled }?.bondedDevices?.mapNotNull { it.name }?.sorted() }.getOrNull().orEmpty()
}
