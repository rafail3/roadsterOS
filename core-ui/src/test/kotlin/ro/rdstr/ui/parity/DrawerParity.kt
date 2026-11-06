package ro.rdstr.ui.parity

import android.view.View
import org.junit.Test
import ro.rdstr.car.ThemeMode
import ro.rdstr.ui.drawer.DrawerState
import ro.rdstr.ui.drawer.DrawerView
import ro.rdstr.ui.kit.Backdrop
import ro.rdstr.ui.kit.UiTheme

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
                bind(DrawerState(open = true, btOn = true, btDevice = "Pixel 8", wifiOn = true, wifiConnected = "Phone hotspot", locationOn = true, volume = 16, theme = if (light) ThemeMode.LIGHT else ThemeMode.DARK, brightness = 100))
            }
        }
        // Bluetooth, Wi-Fi and location switched off, mute on
        component("drawerpanel-off-$name") {
            DrawerView(paparazzi.context).apply {
                bind(DrawerState(open = true, muted = true, volume = 16, theme = if (light) ThemeMode.LIGHT else ThemeMode.DARK, brightness = 100))
            }
        }
    }
}
