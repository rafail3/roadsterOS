package ro.rdstr.os.screens

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.App
import ro.rdstr.car.CarCommand
import ro.rdstr.car.Radio
import ro.rdstr.car.ShellAction
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.drawer.DrawerState
import ro.rdstr.ui.drawer.DrawerView

/**
 * Bumped whenever Bluetooth, Wi-Fi or location switch, which Android tells by broadcast rather than through the
 * UI state; the screens that show the radios read them again then.
 */
internal object RadioWatch {
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        context.applicationContext.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { _tick.value++ }
        }, filter)
    }
}

/** Drawer.tsx on the unit: the drawer view fed from CarRuntime, its calls sent to it. */
@Mirrors("Drawer.tsx")
object DrawerScreen {
    /** The drawer, kept up to date on the main thread while [scope] lives. */
    fun view(context: Context, scope: CoroutineScope): DrawerView {
        RadioWatch.start(context)
        val view = DrawerView(context)
        view.actions = object : DrawerView.Actions {
            // setBluetooth(on): on asks for the last phone once the adapter is on; then the list opens (or closes)
            override fun bluetooth(on: Boolean) {
                if (on) CarRuntime.turnOnBluetooth() else CarRuntime.radios.bluetooth(false)
                CarRuntime.shellAction(ShellAction.RadioSwitched(Radio.BLUETOOTH, on))
            }
            override fun wifi(on: Boolean) = CarRuntime.switchWifi(on)
            override fun location(on: Boolean) { CarRuntime.radios.location(on) }
            override fun mute(on: Boolean) = CarRuntime.send(CarCommand.Audio.Mute(on))
            override fun cycleTheme() = CarRuntime.shellAction(ShellAction.CycleTheme)
            override fun openSettings() = CarRuntime.shellAction(ShellAction.OpenApp(App.SETTINGS))
            override fun askRestart() = CarRuntime.askRestart(true)
            override fun openDevices() = CarRuntime.shellAction(ShellAction.OpenDevices)
            override fun openNetworks() = CarRuntime.shellAction(ShellAction.OpenNetworks)
            override fun setBrightness(value: Int) = CarRuntime.shellAction(ShellAction.SetBrightness(value))
            override fun setVolume(level: Int) = CarRuntime.setVolume(level)
            override fun close() = CarRuntime.shellAction(ShellAction.CloseDrawer)
            override fun released(fraction: Double) = CarRuntime.shellAction(ShellAction.DrawerReleased(fraction))
        }
        scope.launch(Dispatchers.Main) {
            combine(CarRuntime.ui, CarRuntime.shell, RadioWatch.tick) { ui, shell, _ ->
                val radios = CarRuntime.radios
                DrawerState(
                    open = shell.drawer,
                    btOn = radios.bluetoothOn,
                    btDevice = ui.phoneName.takeIf { ui.state.btConnected && it.isNotEmpty() },
                    wifiOn = radios.wifiOn,
                    wifiConnected = ui.wifi.connected,
                    locationOn = radios.locationOn,
                    muted = ui.state.muted,
                    volume = ui.state.volume,
                    theme = shell.theme,
                    brightness = shell.brightness,
                )
            }.collect(view::bind)
        }
        return view
    }
}
