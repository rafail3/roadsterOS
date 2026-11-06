package ro.rdstr.ui.parity

import android.view.View
import org.junit.Test
import ro.rdstr.car.ThemeMode
import ro.rdstr.ui.drawer.DrawerState
import ro.rdstr.ui.drawer.DrawerView
import ro.rdstr.ui.kit.Backdrop
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.car.MapPacks
import ro.rdstr.ui.sheets.AlertPromptView
import ro.rdstr.ui.sheets.DevicesSheetView
import ro.rdstr.ui.sheets.DevicesState
import ro.rdstr.ui.sheets.MapOfferState
import ro.rdstr.ui.sheets.MapOfferView
import ro.rdstr.ui.sheets.NetworksSheetView
import ro.rdstr.ui.sheets.WifiItem
import ro.rdstr.ui.sheets.WifiListState

/**
 * Parity scenes of the drawer, the devices and Wi-Fi sheets, the two prompts and the map offer, in the emulator's
 * states of the capture script's block for them (emulator/scripts/capture-layout.mjs): the URL's sample car and
 * the clicks each scene makes.
 */
class DrawerParity : ParityCapture() {
    /**
     * The look, and the frost of an empty background: the browser isolates a component scene by hiding the rest of
     * the screen, so its glass frosts a transparent page.
     */
    private fun look(light: Boolean, glassy: Boolean) {
        UiTheme.set(light, glassy, geist = true, speed = 0f)
        val blank = View(paparazzi.context).apply {
            measure(View.MeasureSpec.makeMeasureSpec(1024, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1024, 600)
        }
        Backdrop.capture(blank, UiTheme.glass.glassyFrost)
    }

    @Test
    fun drawer() = looks { name, light, glassy ->
        look(light, glassy)
        // the sample car: the phone connected, the hotspot joined, location on, volume 16, night theme
        component("drawerpanel-$name") {
            DrawerView(paparazzi.context).apply {
                // the scene shows the drawer without the screen's scrim
                frostOverScrim = false
                bind(DrawerState(open = true, btOn = true, btDevice = "Pixel 8", wifiOn = true, wifiConnected = "Phone hotspot", locationOn = true, volume = 16, theme = if (light) ThemeMode.LIGHT else ThemeMode.DARK, brightness = 100))
            }
        }
        // Bluetooth, Wi-Fi and location switched off, mute on
        component("drawerpanel-off-$name") {
            DrawerView(paparazzi.context).apply {
                frostOverScrim = false
                bind(DrawerState(open = true, muted = true, volume = 16, theme = if (light) ThemeMode.LIGHT else ThemeMode.DARK, brightness = 100))
            }
        }
    }

    @Test
    fun sheets() = looks { name, light, glassy ->
        look(light, glassy)
        val ctx = paparazzi.context
        // the sample phones: the first connected, the second out of range, a tablet
        val phones = DevicesState(open = true, btOn = true, connected = "Pixel 8", saved = listOf("Pixel 8", "Galaxy S23", "Galaxy Tab S9"))
        component("devices-$name") { DevicesSheetView(ctx).apply { bind(phones) } }
        component("devices-off-$name") { DevicesSheetView(ctx).apply { bind(DevicesState(open = true, saved = phones.saved)) } }
        // the store's four networks after a scan, the hotspot saved and joined
        val list = WifiListState(
            wifiOn = true, locationOn = true, saved = listOf("Phone hotspot"), connected = "Phone hotspot",
            seen = listOf(WifiItem("Phone hotspot", true, 3), WifiItem("Home", true, 2), WifiItem("Cafe Free Wi-Fi", false, 2), WifiItem("Office 5G", true, 1)),
        )
        component("networks-$name") { NetworksSheetView(ctx).apply { bind(true, list) } }
        component("networks-join-$name") { NetworksSheetView(ctx).apply { bind(true, list); networks.setJoining("Home") } }
    }

    @Test
    fun prompts() = looks { name, light, glassy ->
        look(light, glassy)
        val ctx = paparazzi.context
        component("prompt-location-$name") { AlertPromptView(ctx, restart = false).apply { show(true, animate = false) } }
        component("prompt-restart-$name") { AlertPromptView(ctx, restart = true).apply { show(true, animate = false) } }
        // Budapest, Hungary's pack not on the unit, on the hotspot
        component("mapoffer-$name") { MapOfferView(ctx).apply { bind(MapOfferState(shown = true, offer = MapPacks.Offer.DOWNLOAD, name = "Hungary", mb = 358)) } }
    }
}
