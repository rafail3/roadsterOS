package ro.rdstr.car

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ThemeMode { AUTO, LIGHT, DARK }
enum class Theme { LIGHT, DARK }
enum class Face { DIGITAL, ANALOG }

/** What fills the screen above home. */
enum class App { HOME, MUSIC }

/** What the shell itself remembers, independent of the car. */
@Serializable
data class ShellState(
    val theme: ThemeMode = ThemeMode.DARK,
    val drawer: Boolean = false,
    val face: Face = Face.DIGITAL,
    val font: String = "geist",
    val brightness: Int = 100,
    val headlights: Boolean = false,
    val screenOn: Boolean = true,
    val app: App = App.HOME,
    /** The saved-devices sheet over the music app. */
    val devices: Boolean = false,
    /** The Wi-Fi networks sheet. */
    val networks: Boolean = false,
    /** Our own apps opened since boot and not closed, most recently used first; never [App.HOME]. */
    val running: List<App> = emptyList(),
    /** The open-apps screen. */
    val recents: Boolean = false,
    /** When the frame Home key was last pressed, for the double press; null after a double press. */
    val lastHomeMs: Long? = null,
)

@Serializable
sealed class ShellAction {
    @Serializable @SerialName("CycleTheme") data object CycleTheme : ShellAction()
    @Serializable @SerialName("SetTheme") data class SetTheme(val mode: ThemeMode) : ShellAction()
    @Serializable @SerialName("OpenDrawer") data object OpenDrawer : ShellAction()
    @Serializable @SerialName("CloseDrawer") data object CloseDrawer : ShellAction()
    /** The drawer was released after a pull covering this fraction of its height. */
    @Serializable @SerialName("DrawerReleased") data class DrawerReleased(val fraction: Double) : ShellAction()
    @Serializable @SerialName("ToggleFace") data object ToggleFace : ShellAction()
    @Serializable @SerialName("SetFace") data class SetFace(val face: Face) : ShellAction()
    @Serializable @SerialName("SetFont") data class SetFont(val name: String) : ShellAction()
    @Serializable @SerialName("SetBrightness") data class SetBrightness(val value: Int) : ShellAction()
    @Serializable @SerialName("Headlights") data class Headlights(val on: Boolean) : ShellAction()
    @Serializable @SerialName("ToggleScreen") data object ToggleScreen : ShellAction()
    @Serializable @SerialName("Wake") data object Wake : ShellAction()
    /** The car switched off: the screen goes dark until it starts again. */
    @Serializable @SerialName("Sleep") data object Sleep : ShellAction()
    @Serializable @SerialName("Home") data object Home : ShellAction()
    @Serializable @SerialName("Back") data object Back : ShellAction()
    @Serializable @SerialName("OpenApp") data class OpenApp(val app: App) : ShellAction()
    @Serializable @SerialName("OpenDevices") data object OpenDevices : ShellAction()
    @Serializable @SerialName("CloseDevices") data object CloseDevices : ShellAction()
    @Serializable @SerialName("OpenNetworks") data object OpenNetworks : ShellAction()
    @Serializable @SerialName("CloseNetworks") data object CloseNetworks : ShellAction()
    /** The frame Home key; the unit delivers each press as one click, so a hold cannot be told apart. */
    @Serializable @SerialName("HomePressed") data class HomePressed(val atMs: Long) : ShellAction()
    @Serializable @SerialName("CloseApp") data class CloseApp(val app: App) : ShellAction()
    @Serializable @SerialName("CloseAllApps") data object CloseAllApps : ShellAction()
    /** The swipe up from the bottom edge: the open apps over the current app, without passing through home. */
    @Serializable @SerialName("OpenRecents") data object OpenRecents : ShellAction()
    @Serializable @SerialName("CloseRecents") data object CloseRecents : ShellAction()
}

/**
 * How the screen is drawn for a brightness: below neutral a black layer of opacity [dark] over it,
 * above neutral a colour filter multiplying [brightness] and [saturation] (1.0 = unchanged).
 */
@Serializable
data class Veil(val dark: Double, val brightness: Double, val saturation: Double)

/** Shell behaviour shared by the unit and the browser emulator. Pure. */
object Shell {
    const val DAY_START = 5
    const val DAY_END = 19
    /** Percent of the chosen brightness kept while the headlights are on, in the auto theme only. */
    const val HEADLIGHTS_DIM = 80
    /** Neutral: the panel as the vendor drives it, nothing drawn over it. */
    const val FULL_BRIGHTNESS = 100
    const val MIN_BRIGHTNESS = 10
    const val MAX_BRIGHTNESS = 150
    const val DARKEST_VEIL = 0.95
    /** Extra brightness and saturation at the maximum, as fractions. */
    const val BOOST_BRIGHTNESS = 0.25
    const val BOOST_SATURATION = 0.3
    const val DRAWER_OPEN_FRACTION = 1.0 / 3
    /** Two Home presses this close together open the open-apps screen. */
    const val DOUBLE_PRESS_MS = 500L

    fun reduce(s: ShellState, a: ShellAction): ShellState = when (a) {
        ShellAction.CycleTheme -> s.copy(theme = ThemeMode.entries[(s.theme.ordinal + 1) % ThemeMode.entries.size])
        is ShellAction.SetTheme -> s.copy(theme = a.mode)
        ShellAction.OpenDrawer -> s.copy(drawer = true)
        ShellAction.CloseDrawer -> s.copy(drawer = false)
        is ShellAction.DrawerReleased -> s.copy(drawer = a.fraction > DRAWER_OPEN_FRACTION)
        ShellAction.ToggleFace -> s.copy(face = if (s.face == Face.DIGITAL) Face.ANALOG else Face.DIGITAL)
        is ShellAction.SetFace -> s.copy(face = a.face)
        is ShellAction.SetFont -> s.copy(font = a.name)
        is ShellAction.SetBrightness -> s.copy(brightness = a.value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS))
        is ShellAction.Headlights -> s.copy(headlights = a.on)
        ShellAction.ToggleScreen -> s.copy(screenOn = !s.screenOn)
        ShellAction.Wake -> s.copy(screenOn = true)
        ShellAction.Sleep -> s.copy(screenOn = false, drawer = false)
        is ShellAction.OpenApp -> s.copy(
            app = a.app,
            running = if (a.app == App.HOME) s.running else listOf(a.app) + (s.running - a.app),
            drawer = false, devices = false, recents = false,
        )
        ShellAction.Home -> goHome(s)
        ShellAction.OpenDevices -> s.copy(devices = true, networks = false, drawer = false)
        ShellAction.CloseDevices -> s.copy(devices = false)
        ShellAction.OpenNetworks -> s.copy(networks = true, devices = false, drawer = false)
        ShellAction.CloseNetworks -> s.copy(networks = false)
        is ShellAction.HomePressed -> {
            val last = s.lastHomeMs
            when {
                s.recents -> goHome(s).copy(lastHomeMs = null)
                last != null && a.atMs - last <= DOUBLE_PRESS_MS -> s.copy(recents = true, drawer = false, devices = false, lastHomeMs = null)
                else -> goHome(s).copy(lastHomeMs = a.atMs)
            }
        }
        // a closed app starts from scratch next time; the host frees what it held; with none left the list closes onto home
        is ShellAction.CloseApp -> (s.running - a.app).let { left ->
            s.copy(
                running = left,
                app = if (s.app == a.app) App.HOME else s.app,
                devices = s.devices && s.app != a.app,
                recents = s.recents && left.isNotEmpty(),
            )
        }
        ShellAction.CloseAllApps -> s.copy(running = emptyList(), app = App.HOME, devices = false, recents = false)
        ShellAction.OpenRecents -> s.copy(recents = true, drawer = false, devices = false, lastHomeMs = null)
        ShellAction.CloseRecents -> s.copy(recents = false)
        // one layer at a time: the drawer, the open apps, a devices or networks sheet, then the app
        ShellAction.Back -> when {
            s.drawer -> s.copy(drawer = false)
            s.recents -> s.copy(recents = false)
            s.devices -> s.copy(devices = false)
            s.networks -> s.copy(networks = false)
            else -> s.copy(app = App.HOME)
        }
    }

    private fun goHome(s: ShellState) = s.copy(app = App.HOME, recents = false, drawer = false, devices = false, networks = false)

    fun effectiveTheme(s: ShellState, hour: Int): Theme = when (s.theme) {
        ThemeMode.LIGHT -> Theme.LIGHT
        ThemeMode.DARK -> Theme.DARK
        ThemeMode.AUTO -> if (hour in DAY_START until DAY_END) Theme.LIGHT else Theme.DARK
    }

    /** The brightness to draw: the chosen one, dimmed while the headlights are on in the auto theme. */
    fun effectiveBrightness(s: ShellState): Int =
        if (s.theme == ThemeMode.AUTO && s.headlights) s.brightness * HEADLIGHTS_DIM / 100 else s.brightness

    /** The panel's own brightness is the vendor's; ours is drawn over it, linear on each side of neutral. */
    fun veil(s: ShellState): Veil {
        val b = effectiveBrightness(s)
        if (b < FULL_BRIGHTNESS) {
            val dark = ((FULL_BRIGHTNESS - b) * DARKEST_VEIL / (FULL_BRIGHTNESS - MIN_BRIGHTNESS)).coerceAtMost(DARKEST_VEIL)
            return Veil(dark = dark, brightness = 1.0, saturation = 1.0)
        }
        val boost = (b - FULL_BRIGHTNESS).toDouble() / (MAX_BRIGHTNESS - FULL_BRIGHTNESS)
        return Veil(dark = 0.0, brightness = 1.0 + BOOST_BRIGHTNESS * boost, saturation = 1.0 + BOOST_SATURATION * boost)
    }
}
