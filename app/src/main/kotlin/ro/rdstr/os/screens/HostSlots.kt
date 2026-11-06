package ro.rdstr.os.screens

import android.content.Context
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.settings.SettingsSlots
import ro.rdstr.ui.setup.UnitPicture
import ro.rdstr.ui.sheets.KeyboardSlot
import ro.rdstr.ui.sheets.WifiNetworksView

/**
 * The views Settings shows from other screens: the networks list of the Wi-Fi sheet, fed from the runtime while it is
 * attached and typing on the screen's keyboard, and the unit's picture of the setup.
 */
object HostSlots : SettingsSlots {
    override fun wifiNetworks(context: Context): View = WifiNetworksView(context).apply {
        actions = NetworksScreen.listActions
        keyboard = object : KeyboardSlot {
            override fun open(onKey: (String) -> Unit, onBackspace: () -> Unit, onSubmit: () -> Unit) { NetworksScreen.keyboard?.open(onKey, onBackspace, onSubmit) }
            override fun close() { NetworksScreen.keyboard?.close() }
            override fun submit(label: String, enabled: Boolean) { NetworksScreen.keyboard?.submit(label, enabled) }
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            var job: Job? = null
            override fun onViewAttachedToWindow(v: View) {
                job = MainScope().launch(Dispatchers.Main) {
                    combine(CarRuntime.ui, RadioWatch.tick) { ui, _ -> NetworksScreen.list(ui) }.collect(::bind)
                }
            }
            override fun onViewDetachedFromWindow(v: View) { job?.cancel(); job = null }
        })
    }

    override fun unitPicture(context: Context, width: Float): View = UnitPicture(context, width)
}
