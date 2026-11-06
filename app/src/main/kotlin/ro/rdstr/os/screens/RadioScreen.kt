package ro.rdstr.os.screens

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.App
import ro.rdstr.car.AudioSource
import ro.rdstr.car.CardPage
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent
import ro.rdstr.car.HomeCard
import ro.rdstr.car.ShellAction
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.media.RadioActions
import ro.rdstr.ui.media.RadioAppView
import ro.rdstr.ui.media.RadioState
import ro.rdstr.ui.media.Station

/**
 * RadioApp.tsx on the unit: the radio app bound to the runtime, open while the shell's app is RADIO. The tuned
 * station is the tuner's last report; the stations listed are those the tuner reported (`UiState.heard`), which is
 * what a scan fills on the unit.
 */
@Mirrors("RadioApp.tsx")
class RadioScreen(activity: Activity) : Screen {
    override val view = RadioAppView(activity, object : RadioActions {
        override fun carCommand(command: CarCommand) = CarRuntime.send(command)
        override fun shellAction(action: ShellAction) = CarRuntime.shellAction(action)
        override fun pressSound(page: CardPage, press: HomeCard.Press) = CarRuntime.pressSound(page, press)
        override fun toggleFavorite(khz: Int) = CarRuntime.toggleFavorite(khz)
    })

    init { closeOntoCard(view) }

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui) { shell, ui ->
                view.open(shell.app == App.RADIO)
                val r = ui.radio ?: CarEvent.Radio(DEFAULT_KHZ, CarEvent.Radio.Band.FM)
                RadioState(
                    band = r.band, khz = r.khz, rds = r.rds, pty = r.pty, stereo = r.stereo,
                    stations = ui.heard.values.map { Station(it.band, it.khz, it.rds, it.pty) }.sortedBy { it.khz },
                    favorites = ui.favorites,
                    scan = null,
                    radioOn = ui.state.radioOn, own = ui.state.source == AudioSource.RADIO, muted = ui.state.muted,
                )
            }.collect { view.bind(it) }
        }
    }

    private companion object { const val DEFAULT_KHZ = 87_500 }
}
