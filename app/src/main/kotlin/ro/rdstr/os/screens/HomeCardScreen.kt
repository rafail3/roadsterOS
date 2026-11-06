package ro.rdstr.os.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.App
import ro.rdstr.car.CardPage
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent
import ro.rdstr.car.HomeCard
import ro.rdstr.car.ShellAction
import ro.rdstr.os.CarRuntime
import ro.rdstr.os.maps.MapHost
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.home.CardActions
import ro.rdstr.ui.home.CardState
import ro.rdstr.ui.home.HomeCardView

/**
 * HomeCard.tsx and NowPlaying.tsx on the unit: the card on home bound to the runtime (core-car HomeCard for the
 * pages), the map's place on it given to MapHost, and what its pages send.
 */
@Mirrors("HomeCard.tsx", "NowPlaying.tsx")
class HomeCardScreen(activity: Activity) : Screen {
    override val view = HomeCardView(activity, object : CardActions {
        override fun showCard(page: CardPage) = CarRuntime.shellAction(ShellAction.ShowCard(page))
        override fun openApp(app: App) = CarRuntime.shellAction(ShellAction.OpenApp(app))
        override fun pressSound(page: CardPage, press: HomeCard.Press) = CarRuntime.pressSound(page, press)
        override fun toggleFavorite(khz: Int) = CarRuntime.toggleFavorite(khz)
        override fun connect(device: String) = CarRuntime.send(CarCommand.Bluetooth.Connect(device))
        override fun openDevices() = CarRuntime.shellAction(ShellAction.OpenDevices)
        override fun turnOnBluetooth() = CarRuntime.switchBluetooth(true)
    })

    init { MapHost.setSlot(MapHost.Slot.CARD, view.mapSlot) }

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui, Covers.flow) { shell, ui, cover ->
                val car = ui.state
                val btOn = CarRuntime.radios.bluetoothOn
                CardState(
                    pages = HomeCard.pages(car, btOn),
                    shown = HomeCard.shown(car, btOn, shell.card),
                    strip = HomeCard.strip(car),
                    car = car,
                    track = ui.track ?: CarEvent.Track(""),
                    cover = cover?.bitmap,
                    positionMs = ui.positionMs,
                    positionAt = ui.positionAt,
                    radio = ui.radio ?: CarEvent.Radio(DEFAULT_KHZ, CarEvent.Radio.Band.FM),
                    favorite = ui.radio?.let { it.khz in ui.favorites } ?: false,
                    saved = if (btOn) savedPhones() else emptyList(),
                )
            }.collect(view::bind)
        }
    }

    /** The saved (paired) devices by name, as the music app's NoPhone lists them. */
    @SuppressLint("MissingPermission")
    private fun savedPhones(): List<String> = runCatching {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty()
            .filter { it.bluetoothClass?.majorDeviceClass != BluetoothClass.Device.Major.AUDIO_VIDEO }
            .mapNotNull { it.name }.sorted()
    }.getOrDefault(emptyList())

    private companion object { const val DEFAULT_KHZ = 87500 }
}
