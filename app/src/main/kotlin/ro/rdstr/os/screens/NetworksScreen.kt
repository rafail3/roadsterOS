package ro.rdstr.os.screens

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.ShellAction
import ro.rdstr.os.CarRuntime
import ro.rdstr.os.UiState
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.sheets.KeyboardSlot
import ro.rdstr.ui.sheets.NetworksSheetView
import ro.rdstr.ui.sheets.WifiItem
import ro.rdstr.ui.sheets.WifiListState
import ro.rdstr.ui.sheets.WifiNetworksView

/** NetworksSheet.tsx on the unit: the Wi-Fi sheet, and the networks list Settings > Wi-Fi shows too. */
@Mirrors("NetworksSheet.tsx")
object NetworksScreen {
    /** `wifiScan`, `wifiConnect`, `wifiForget`, `setLocation`: what the networks list asks, here or in Settings. */
    val listActions = object : WifiNetworksView.Actions {
        override fun scan() = CarRuntime.wifi.scan()
        override fun connect(ssid: String, password: String?) = CarRuntime.wifi.connect(ssid, password)
        override fun forget(ssid: String) = CarRuntime.wifi.forget(ssid)
        override fun setLocation(on: Boolean) { CarRuntime.radios.location(on) }
    }

    /** The list's state from the UI state and the radios; Android gives no list size up front, so it searches while empty. */
    fun list(ui: UiState) = WifiListState(
        wifiOn = CarRuntime.radios.wifiOn,
        locationOn = CarRuntime.radios.locationOn,
        saved = ui.wifi.saved,
        connected = ui.wifi.connected,
        connecting = ui.wifi.connecting,
        wrongPassword = ui.wifi.wrongPassword,
        seen = ui.wifi.seen.map { WifiItem(it.ssid, it.secure, it.signal) },
        searching = ui.wifi.seen.isEmpty(),
    )

    /** The sheet; [keyboard] is the screen's keyboard for passwords. */
    fun view(context: Context, scope: CoroutineScope, keyboard: KeyboardSlot?): NetworksSheetView {
        RadioWatch.start(context)
        val view = NetworksSheetView(context)
        view.networks.actions = listActions
        view.networks.keyboard = keyboard
        view.actions = object : NetworksSheetView.Actions {
            override fun setWifi(on: Boolean) = CarRuntime.setWifi(on)
            override fun close() = CarRuntime.shellAction(ShellAction.CloseNetworks)
        }
        scope.launch(Dispatchers.Main) {
            combine(CarRuntime.ui, CarRuntime.shell, RadioWatch.tick) { ui, shell, _ -> shell.networks to list(ui) }
                .collect { (open, list) -> view.bind(open, list) }
        }
        return view
    }
}
