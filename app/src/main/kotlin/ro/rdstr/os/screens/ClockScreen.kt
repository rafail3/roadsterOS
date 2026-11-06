package ro.rdstr.os.screens

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.home.clock.ClockView
import ro.rdstr.ui.kit.UiTheme

/**
 * Clock.tsx on the unit: the home clock block bound to the unit's time (`store.useNow`), the shell's face, face mode
 * and 12/24 h, and the theme and font of UiTheme; a tap on it sends `ShellAction.ToggleFace` through the runtime.
 */
class ClockScreen(activity: Activity) : Screen {
    override val view = ClockView(activity) { CarRuntime.shellAction(it) }

    init {
        UiTheme.link(view) {
            view.setTheme(UiTheme.light, UiTheme.glassy)
            view.setFont(family())
        }
    }

    override fun bind(scope: CoroutineScope) {
        scope.launch { CarRuntime.shell.collect(view::setShell) }
        scope.launch { CarRuntime.time.now().collect { t -> view.now = { t } } }
    }
}

/** The CSS family name of the shell's font (`--font`). */
internal fun family() = if (UiTheme.geist) "Geist" else "Inter"
