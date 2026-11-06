package ro.rdstr.os.screens

import android.app.Activity
import android.view.View
import kotlinx.coroutines.CoroutineScope
import ro.rdstr.ui.shell.Layer

/**
 * One screen's glue, made once per activity: its view (a core-ui view) and the collectors that keep it bound to
 * `CarRuntime` while the activity is in front. Its actions call `CarRuntime` (`shellAction`, `send`, the `@Emulates`
 * functions).
 */
interface Screen {
    val view: View

    /** Collects the runtime's state into [view] while [scope] lives (from resume to pause). */
    fun bind(scope: CoroutineScope)
}

/**
 * Every screen the shell stacks, one line each: the layer its view goes in and the glue that makes it. The shell's
 * own root (Screen.tsx, [ShellScreen]) is made by HomeActivity; [Layer.PANEL] goes in the panel's grid cell,
 * [Layer.CLOCK] in the field above the icons, [Layer.AOD_DIAL] in the always-on display.
 */
object Screens {
    val ALL: List<Pair<Layer, (Activity) -> Screen>> = listOf(
        Layer.PANEL to ::HomeCardScreen,
        Layer.CLOCK to ::ClockScreen,
        Layer.AOD_DIAL to ::AodDialScreen,
        Layer.MAPS to ::MapsScreen,
        Layer.SOUND to ::SoundScreen,
        Layer.MUSIC to ::MusicScreen,
        Layer.RADIO to ::RadioScreen,
        Layer.RECENTS to ::RecentsScreen,
        Layer.GESTURES to ::GesturesScreen,
        Layer.VOLUME to ::VolumeScreen,
        Layer.SETTINGS to ::SettingsScreen,
        Layer.RESTART_PROMPT to ::RestartPromptScreen,
        Layer.NOTICE to ::NoticeScreen,
        Layer.DEV to ::DevOverlaysScreen,
        Layer.STARTUP to ::SetupScreen,
    )
}
