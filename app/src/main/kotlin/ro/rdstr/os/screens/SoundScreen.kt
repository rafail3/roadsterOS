package ro.rdstr.os.screens

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ro.rdstr.car.ShellAction
import ro.rdstr.car.SoundSettings
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.sound.SoundActions
import ro.rdstr.ui.sound.SoundView

/** The sound window: [SoundView] bound to the shell's `sound` and `dsp`, its actions sent to [CarRuntime]. */
@Mirrors("SoundApp.tsx", "CarTop.tsx")
class SoundScreen(activity: Activity) : Screen {
    override val view = SoundView(activity).apply {
        actions = object : SoundActions {
            override fun setSound(next: SoundSettings) = CarRuntime.setSound(next)
            override fun close() = CarRuntime.shellAction(ShellAction.CloseSound)
        }
    }

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            CarRuntime.shell.map { it.sound to it.dsp }.distinctUntilChanged().collect { (open, dsp) -> view.bind(open, dsp) }
        }
    }
}
