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
    @Serializable @SerialName("Home") data object Home : ShellAction()
    @Serializable @SerialName("Back") data object Back : ShellAction()
    @Serializable @SerialName("OpenApp") data class OpenApp(val app: App) : ShellAction()
    @Serializable @SerialName("OpenDevices") data object OpenDevices : ShellAction()
    @Serializable @SerialName("CloseDevices") data object CloseDevices : ShellAction()
}

/** The layer drawn over the whole screen: black below neutral brightness, white above it. */
@Serializable
data class Veil(val white: Boolean, val opacity: Double)

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
    const val BRIGHTEST_VEIL = 0.2
    const val DRAWER_OPEN_FRACTION = 1.0 / 3

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
        is ShellAction.OpenApp -> s.copy(app = a.app, drawer = false, devices = false)
        ShellAction.Home -> s.copy(app = App.HOME, drawer = false, devices = false)
        ShellAction.OpenDevices -> s.copy(devices = true, drawer = false)
        ShellAction.CloseDevices -> s.copy(devices = false)
        // one layer at a time: the drawer first, then the devices sheet, then the app
        ShellAction.Back -> when {
            s.drawer -> s.copy(drawer = false)
            s.devices -> s.copy(devices = false)
            else -> s.copy(app = App.HOME)
        }
    }

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
        return if (b < FULL_BRIGHTNESS) {
            Veil(white = false, opacity = ((FULL_BRIGHTNESS - b) * DARKEST_VEIL / (FULL_BRIGHTNESS - MIN_BRIGHTNESS)).coerceAtMost(DARKEST_VEIL))
        } else {
            Veil(white = true, opacity = (b - FULL_BRIGHTNESS) * BRIGHTEST_VEIL / (MAX_BRIGHTNESS - FULL_BRIGHTNESS))
        }
    }
}
