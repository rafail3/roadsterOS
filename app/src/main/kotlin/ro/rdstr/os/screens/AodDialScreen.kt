package ro.rdstr.os.screens

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.home.clock.AodDialView
import ro.rdstr.ui.kit.UiTheme

/**
 * Screen.tsx `div.aod-dial` on the unit: the analog dial of the always-on display, on the unit's time. Its 320 px slot
 * draws it grey at 0.55.
 */
class AodDialScreen(activity: Activity) : Screen {
    override val view = AodDialView(activity)

    init {
        UiTheme.link(view) {
            view.setTheme(UiTheme.light, UiTheme.glassy)
            view.setFont(family())
        }
    }

    override fun bind(scope: CoroutineScope) {
        scope.launch { CarRuntime.time.now().collect { t -> view.now = { t } } }
    }
}
