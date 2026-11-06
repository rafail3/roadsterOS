package ro.rdstr.os.screens

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.App
import ro.rdstr.car.CarCommand
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.kit.UiTheme
import ro.rdstr.ui.volume.VolumeActions
import ro.rdstr.ui.volume.VolumeHudView

/**
 * VolumeHud.tsx on the unit: the volume bar on the car's level and mute, up on every change or when the status bar
 * opens it, quiet while the music app or the drawer shows its own volume control.
 */
@Mirrors("VolumeHud.tsx")
class VolumeScreen(activity: Activity) : Screen {
    override val view = VolumeHudView(activity).apply {
        actions = object : VolumeActions {
            override fun setVolume(level: Int) = CarRuntime.setVolume(level)
            override fun mute(on: Boolean) = CarRuntime.send(CarCommand.Audio.Mute(on))
        }
    }

    init {
        UiTheme.link(view) {
            view.setTheme(UiTheme.light, UiTheme.glassy)
            view.setFont(family())
        }
    }

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.ui, CarRuntime.shell) { ui, s -> ui to s }.collect { (ui, s) ->
                view.setQuiet(s.app == App.MUSIC || s.drawer)
                view.setState(ui.state.volume, ui.state.muted, ui.volumeOpened)
            }
        }
    }
}
