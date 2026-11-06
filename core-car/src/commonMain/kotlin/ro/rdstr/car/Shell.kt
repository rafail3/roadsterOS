package ro.rdstr.car

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ThemeMode { AUTO, LIGHT, DARK }
enum class Theme { LIGHT, DARK }
enum class Face { DIGITAL, ANALOG }
/** Which clock home shows: both, switched with a tap on the clock, or always the one chosen. */
enum class FaceMode { BOTH, DIGITAL, ANALOG }

/** How fast the shell's own animations run: as designed, twice as fast, or not at all. */
enum class AnimationSpeed { NORMAL, FAST, OFF }

/** The clock on the always-on display: the one home shows, or a fixed face. */
enum class AodFace { HOME, DIGITAL, ANALOG }

/** Where the automatic clock comes from: Android's internet time, or the GPS fixes. */
enum class TimeSource { NETWORK, GPS }

/** How a call shows: not at all, over the whole screen, or as a bar at the top while the rest of the shell is used. */
enum class CallView { NONE, FULL, BAR }

/** What fills the screen above home. */
enum class App { HOME, MUSIC, RADIO, MAPS, PHONE, SETTINGS }

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
    /** The sound window (equalizer, balance, sound field, loudness, bass, filters) over the app it was opened from. */
    val sound: Boolean = false,
    /** The Wi-Fi networks sheet. */
    val networks: Boolean = false,
    /** Our own apps opened since boot and not closed, most recently used first; never [App.HOME]. */
    val running: List<App> = emptyList(),
    /** The open-apps screen. */
    val recents: Boolean = false,
    /** When the frame Home key was last pressed, for the double press; null after a double press. */
    val lastHomeMs: Long? = null,
    /** The card page on home chosen last; [HomeCard.shown] falls back when it is not there. */
    val card: CardPage? = null,
    /** The big clock on the 24-hour clock, or the 12-hour one. */
    val clock24h: Boolean = true,
    /** The two notes when the car goes over the speed limit. */
    val speedChime: Boolean = true,
    /** The highest volume level the car starts with; 0 keeps the level it was switched off with. */
    val startVolume: Int = 0,
    val callView: CallView = CallView.NONE,
    /** Writing a message is blocked while the car moves; off unless chosen in the settings. */
    val lockTypingWhileMoving: Boolean = false,
    /** The app icons on home, in order: our apps by name (MAPS, MUSIC...), other apps by package. */
    val homeApps: List<String> = HomeApps.DEFAULT,
    /** The road's speed limit sign on the map's speed; on unless switched off in the settings. */
    val speedLimitSign: Boolean = true,
    val faceMode: FaceMode = FaceMode.BOTH,
    /** The clock sets itself from [timeSource]; off, it keeps the time set by hand. */
    val autoTime: Boolean = true,
    val timeSource: TimeSource = TimeSource.NETWORK,
    /** The dash display was matched to the unit's clock once and follows it since. */
    val dashMatched: Boolean = false,
    /** Left alone [aodAfterMin] minutes, the screen goes black with only the clock and the date. */
    val aodEnabled: Boolean = true,
    val aodAfterMin: Int = 5,
    val aodFace: AodFace = AodFace.HOME,
    /** The always-on display shows now. */
    val aod: Boolean = false,
    /** The Developer section shows after About. */
    val developer: Boolean = false,
    /** adb reaches the unit (Settings.Global.ADB_ENABLED, set as device owner). */
    val adbEnabled: Boolean = true,
    /** Developer tools on our own screens: a dot under each finger, the frame rate in a corner, the animation speed. */
    val showTouches: Boolean = false,
    val showFrameRate: Boolean = false,
    val animationSpeed: AnimationSpeed = AnimationSpeed.NORMAL,
    /** False after a factory reset: the unit starts in setup until it is finished. */
    val setupDone: Boolean = true,
    /** The sound processor's settings, as last set from the sound window (Sound). */
    val dsp: SoundSettings = SoundSettings(),
    /** The reverse camera's picture, its lines and the music while reversing (Camera). */
    val camera: CameraSettings = CameraSettings(),
    /** Every car event is written to a file on the unit, for troubleshooting. */
    val recordEvents: Boolean = false,
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
    @Serializable @SerialName("OpenSound") data object OpenSound : ShellAction()
    @Serializable @SerialName("CloseSound") data object CloseSound : ShellAction()
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
    /** A swipe on the card on home, or a new source, brings this page forward. */
    @Serializable @SerialName("ShowCard") data class ShowCard(val page: CardPage) : ShellAction()
    @Serializable @SerialName("SetClock24h") data class SetClock24h(val on: Boolean) : ShellAction()
    @Serializable @SerialName("SetSpeedChime") data class SetSpeedChime(val on: Boolean) : ShellAction()
    @Serializable @SerialName("SetStartVolume") data class SetStartVolume(val level: Int) : ShellAction()
    /** Every preference back to its default; what is open and what the car reports stay. */
    @Serializable @SerialName("ResetSettings") data object ResetSettings : ShellAction()
    /**
     * The phone's call changed state (sent with every Call event): a call ringing in shows as the banner at the top,
     * one placed from here fills the screen, an ended one goes.
     */
    @Serializable @SerialName("CallChanged") data class CallChanged(val state: CarEvent.Call.State) : ShellAction()
    /** A tap on the call bar brings the call back over the whole screen. */
    @Serializable @SerialName("ExpandCall") data object ExpandCall : ShellAction()
    @Serializable @SerialName("SetTypingLock") data class SetTypingLock(val on: Boolean) : ShellAction()
    /** Put an app on home (at the end, up to [HomeApps.MAX]) or take it off. */
    @Serializable @SerialName("ToggleHomeApp") data class ToggleHomeApp(val id: String) : ShellAction()
    /** Move an app on home this many places earlier (negative) or later, as a drag drops it. */
    @Serializable @SerialName("MoveHomeApp") data class MoveHomeApp(val id: String, val by: Int) : ShellAction()
    /** The other apps installed on the unit now (by package); one removed comes off home and frees its place. */
    @Serializable @SerialName("AppsInstalled") data class AppsInstalled(val ids: List<String>) : ShellAction()
    @Serializable @SerialName("SetRecording") data class SetRecording(val on: Boolean) : ShellAction()
    @Serializable @SerialName("SetSpeedLimitSign") data class SetSpeedLimitSign(val on: Boolean) : ShellAction()
    /** Both faces switched with a tap, or one face that a tap leaves as it is. */
    @Serializable @SerialName("SetFaceMode") data class SetFaceMode(val mode: FaceMode) : ShellAction()
    @Serializable @SerialName("SetTimeSync") data class SetTimeSync(val auto: Boolean, val source: TimeSource) : ShellAction()
    @Serializable @SerialName("SetDashMatched") data class SetDashMatched(val on: Boolean) : ShellAction()
    @Serializable @SerialName("SetAod") data class SetAod(val on: Boolean) : ShellAction()
    @Serializable @SerialName("SetAodAfter") data class SetAodAfter(val minutes: Int) : ShellAction()
    @Serializable @SerialName("SetAodFace") data class SetAodFace(val face: AodFace) : ShellAction()
    /** Off hides the section and takes the screen tools off with it; adb stays as it was. */
    @Serializable @SerialName("SetDeveloper") data class SetDeveloper(val on: Boolean) : ShellAction()
    @Serializable @SerialName("SetAdb") data class SetAdb(val on: Boolean) : ShellAction()
    @Serializable @SerialName("SetShowTouches") data class SetShowTouches(val on: Boolean) : ShellAction()
    @Serializable @SerialName("SetShowFrameRate") data class SetShowFrameRate(val on: Boolean) : ShellAction()
    @Serializable @SerialName("SetAnimationSpeed") data class SetAnimationSpeed(val speed: AnimationSpeed) : ShellAction()
    @Serializable @SerialName("FinishSetup") data object FinishSetup : ShellAction()
    @Serializable @SerialName("SetSound") data class SetSound(val settings: SoundSettings) : ShellAction()
    @Serializable @SerialName("SetCamera") data class SetCamera(val settings: CameraSettings) : ShellAction()
    /** The screen was left alone long enough (see [Shell.aodDue]). */
    @Serializable @SerialName("EnterAod") data object EnterAod : ShellAction()
    /** A touch, a key, a call or reverse gear. */
    @Serializable @SerialName("ExitAod") data object ExitAod : ShellAction()
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

    /** What a factory reset leaves: every default, and setup to go through again. */
    fun factoryState() = ShellState(setupDone = false)

    /** What every animation duration of the shell is multiplied by. */
    fun animationScale(s: ShellState): Float = when (s.animationSpeed) {
        AnimationSpeed.NORMAL -> 1f
        AnimationSpeed.FAST -> 0.5f
        AnimationSpeed.OFF -> 0f
    }

    /** The waits the always-on display offers, in minutes. */
    val AOD_MINUTES = listOf(1, 2, 5, 10, 15)

    /**
     * The always-on display is due: on, not showing yet, the screen lit and left alone long enough, and nothing that
     * needs the screen ([busy]: a call, the reverse camera, the map).
     */
    fun aodDue(s: ShellState, idleMs: Long, busy: Boolean): Boolean =
        s.aodEnabled && !s.aod && s.screenOn && !busy && idleMs >= s.aodAfterMin * 60_000L

    /** The face the always-on display draws. */
    fun aodFace(s: ShellState): Face = when (s.aodFace) {
        AodFace.HOME -> s.face
        AodFace.DIGITAL -> Face.DIGITAL
        AodFace.ANALOG -> Face.ANALOG
    }

    /** The car events that end the always-on display, as a touch does: a key, reverse gear, a call, the car starting. */
    fun wakesAod(e: CarEvent): Boolean = when (e) {
        is CarEvent.SteeringKey, is CarEvent.PanelKey -> true
        is CarEvent.ReverseGear -> e.on
        is CarEvent.Call -> e.state != CarEvent.Call.State.IDLE
        is CarEvent.Power -> e.acc
        else -> false
    }

    fun reduce(s: ShellState, a: ShellAction): ShellState = when (a) {
        ShellAction.CycleTheme -> s.copy(theme = ThemeMode.entries[(s.theme.ordinal + 1) % ThemeMode.entries.size])
        is ShellAction.SetTheme -> s.copy(theme = a.mode)
        ShellAction.OpenDrawer -> s.copy(drawer = true)
        ShellAction.CloseDrawer -> s.copy(drawer = false)
        is ShellAction.DrawerReleased -> s.copy(drawer = a.fraction > DRAWER_OPEN_FRACTION)
        // a tap on the clock switches the face only while both are allowed
        ShellAction.ToggleFace -> if (s.faceMode != FaceMode.BOTH) s else s.copy(face = if (s.face == Face.DIGITAL) Face.ANALOG else Face.DIGITAL)
        is ShellAction.SetFace -> s.copy(face = a.face)
        is ShellAction.SetFont -> s.copy(font = a.name)
        is ShellAction.SetBrightness -> s.copy(brightness = a.value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS))
        is ShellAction.Headlights -> s.copy(headlights = a.on)
        ShellAction.ToggleScreen -> s.copy(screenOn = !s.screenOn)
        ShellAction.Wake -> s.copy(screenOn = true)
        ShellAction.Sleep -> s.copy(screenOn = false, drawer = false, aod = false)
        is ShellAction.OpenApp -> s.copy(
            app = a.app,
            running = if (a.app == App.HOME) s.running else listOf(a.app) + (s.running - a.app),
            drawer = false, devices = false, recents = false,
        )
        ShellAction.Home -> goHome(s)
        ShellAction.OpenDevices -> s.copy(devices = true, networks = false, drawer = false)
        ShellAction.OpenSound -> s.copy(sound = true, drawer = false, devices = false)
        ShellAction.CloseSound -> s.copy(sound = false)
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
        is ShellAction.ShowCard -> s.copy(card = a.page)
        is ShellAction.SetClock24h -> s.copy(clock24h = a.on)
        is ShellAction.SetSpeedChime -> s.copy(speedChime = a.on)
        is ShellAction.SetStartVolume -> s.copy(startVolume = a.level.coerceIn(0, Rules.MAX_VOLUME))
        ShellAction.ResetSettings -> ShellState().copy(
            app = s.app, running = s.running, recents = s.recents, devices = s.devices, networks = s.networks,
            headlights = s.headlights, screenOn = s.screenOn, card = s.card, callView = s.callView,
        )
        is ShellAction.CallChanged -> s.copy(callView = when {
            a.state == CarEvent.Call.State.IDLE -> CallView.NONE
            s.callView == CallView.NONE -> if (a.state == CarEvent.Call.State.RINGING) CallView.BAR else CallView.FULL
            else -> s.callView
        })
        is ShellAction.SetTypingLock -> s.copy(lockTypingWhileMoving = a.on)
        is ShellAction.ToggleHomeApp -> s.copy(homeApps = HomeApps.toggle(s.homeApps, a.id))
        is ShellAction.MoveHomeApp -> s.copy(homeApps = HomeApps.move(s.homeApps, a.id, a.by))
        is ShellAction.AppsInstalled -> s.copy(homeApps = HomeApps.installedOnly(s.homeApps, a.ids))
        is ShellAction.SetRecording -> s.copy(recordEvents = a.on)
        is ShellAction.SetSpeedLimitSign -> s.copy(speedLimitSign = a.on)
        is ShellAction.SetTimeSync -> s.copy(autoTime = a.auto, timeSource = a.source)
        is ShellAction.SetDashMatched -> s.copy(dashMatched = a.on)
        is ShellAction.SetAod -> s.copy(aodEnabled = a.on, aod = s.aod && a.on)
        is ShellAction.SetAodAfter -> s.copy(aodAfterMin = a.minutes.coerceIn(AOD_MINUTES.first(), AOD_MINUTES.last()))
        is ShellAction.SetAodFace -> s.copy(aodFace = a.face)
        is ShellAction.SetDeveloper -> if (a.on) s.copy(developer = true)
            else s.copy(developer = false, showTouches = false, showFrameRate = false, animationSpeed = AnimationSpeed.NORMAL)
        is ShellAction.SetAdb -> s.copy(adbEnabled = a.on)
        is ShellAction.SetShowTouches -> s.copy(showTouches = a.on)
        is ShellAction.SetShowFrameRate -> s.copy(showFrameRate = a.on)
        is ShellAction.SetAnimationSpeed -> s.copy(animationSpeed = a.speed)
        ShellAction.FinishSetup -> s.copy(setupDone = true)
        is ShellAction.SetSound -> s.copy(dsp = Sound.clamp(a.settings))
        is ShellAction.SetCamera -> s.copy(camera = Camera.clamp(a.settings))
        ShellAction.EnterAod -> if (s.aodEnabled && s.screenOn) s.copy(aod = true, drawer = false) else s
        ShellAction.ExitAod -> s.copy(aod = false)
        is ShellAction.SetFaceMode -> s.copy(faceMode = a.mode, face = when (a.mode) {
            FaceMode.DIGITAL -> Face.DIGITAL
            FaceMode.ANALOG -> Face.ANALOG
            FaceMode.BOTH -> s.face
        })
        ShellAction.ExpandCall -> if (s.callView == CallView.NONE) s else s.copy(callView = CallView.FULL, drawer = false, recents = false)
        // one layer at a time: the drawer, the open apps, a devices or networks sheet, then the app
        ShellAction.Back -> when {
            // the call over everything is the top layer: Back folds it into its bar, the app under it stays
            s.callView == CallView.FULL -> s.copy(callView = CallView.BAR)
            s.drawer -> s.copy(drawer = false)
            s.recents -> s.copy(recents = false)
            s.devices -> s.copy(devices = false)
            s.sound -> s.copy(sound = false)
            s.networks -> s.copy(networks = false)
            else -> s.copy(app = App.HOME, card = cardAfter(s))
        }
    }

    /** Home goes home in a call too: the call folds into its bar. */
    private fun goHome(s: ShellState) = s.copy(
        app = App.HOME, recents = false, drawer = false, devices = false, networks = false, sound = false, card = cardAfter(s),
        callView = if (s.callView == CallView.FULL) CallView.BAR else s.callView,
    )

    /** Leaving the map, the card on home shows it. */
    private fun cardAfter(s: ShellState) = if (s.app == App.MAPS) CardPage.NAVIGATION else s.card

    private val prefsJson = Json { ignoreUnknownKeys = true }

    /** The preferences only, as the unit keeps them across starts: nothing open, no car state. */
    fun preferences(s: ShellState) = ShellState(
        theme = s.theme, face = s.face, font = s.font, brightness = s.brightness,
        clock24h = s.clock24h, speedChime = s.speedChime, startVolume = s.startVolume,
        lockTypingWhileMoving = s.lockTypingWhileMoving, homeApps = s.homeApps, recordEvents = s.recordEvents, speedLimitSign = s.speedLimitSign, faceMode = s.faceMode,
        autoTime = s.autoTime, timeSource = s.timeSource, dashMatched = s.dashMatched,
        aodEnabled = s.aodEnabled, aodAfterMin = s.aodAfterMin, aodFace = s.aodFace,
        developer = s.developer, adbEnabled = s.adbEnabled, showTouches = s.showTouches, showFrameRate = s.showFrameRate,
        animationSpeed = s.animationSpeed, setupDone = s.setupDone,
        // the call level is for this drive only
        dsp = s.dsp.copy(callVolume = Sound.CALL_VOLUME_DEFAULT),
        camera = s.camera,
    )

    fun encodePreferences(s: ShellState): String = prefsJson.encodeToString(ShellState.serializer(), preferences(s))

    /** A stored text from an older version or a damaged one gives the defaults, never a crash at boot. */
    fun decodePreferences(text: String?): ShellState =
        text?.let { runCatching { preferences(prefsJson.decodeFromString(ShellState.serializer(), it)) }.getOrNull() } ?: ShellState()

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
