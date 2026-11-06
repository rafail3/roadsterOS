package ro.rdstr.os.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.graphics.RectF
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.App
import ro.rdstr.car.CarCommand
import ro.rdstr.car.ShellAction
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.media.MediaAppFrame
import ro.rdstr.ui.media.MusicActions
import ro.rdstr.ui.media.MusicAppView
import ro.rdstr.ui.media.MusicState
import ro.rdstr.ui.media.SavedPhone

/**
 * MusicApp.tsx on the unit: the music app bound to the runtime (the track, the position, the cover from the cover
 * desk, the paired phones), open while the shell's app is MUSIC; what it sends goes to CarRuntime.
 */
@Mirrors("MusicApp.tsx", "media.tsx")
class MusicScreen(activity: Activity) : Screen {
    /** store `tryConnect`: the phone tried, and the last one that did not answer in [CONNECT_FAIL_MS]. */
    private val trying = MutableStateFlow<Pair<String?, String?>>(null to null)
    private var attempt: Job? = null
    private var scope: CoroutineScope? = null

    override val view = MusicAppView(activity, object : MusicActions {
        override fun carCommand(command: CarCommand) {
            if (command is CarCommand.Bluetooth.Connect) tryConnect(command.device)
            CarRuntime.send(command)
        }
        override fun shellAction(action: ShellAction) = CarRuntime.shellAction(action)
        override fun setVolume(level: Int) = CarRuntime.setVolume(level)
        override fun stepVolume(delta: Int) = CarRuntime.stepVolume(delta)
    })

    init { closeOntoCard(view) }

    private fun tryConnect(device: String) {
        trying.value = device to null
        attempt?.cancel()
        attempt = scope?.launch {
            delay(CONNECT_FAIL_MS)
            if (!CarRuntime.ui.value.state.btConnected) trying.value = null to device
        }
    }

    override fun bind(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui, Covers.flow, trying) { shell, ui, cover, (connecting, failed) ->
                val car = ui.state
                if (car.btConnected && connecting != null) trying.value = null to null
                view.open(shell.app == App.MUSIC)
                val track = ui.track
                MusicState(
                    connected = car.btConnected, playing = car.playing, muted = car.muted, volume = car.volume,
                    title = track?.title.orEmpty(), artist = track?.artist.orEmpty(), durationMs = track?.durationMs ?: 0,
                    positionMs = ui.positionMs, positionAt = ui.positionAt, device = ui.phoneName,
                    cover = cover?.bitmap, coverKey = cover?.bitmap,
                    saved = if (car.btConnected) emptyList() else savedPhones(),
                    connecting = connecting, failed = failed,
                )
            }.collect { view.bind(it) }
        }
    }

    /** The paired devices with their major class (the phone app keeps only phones). */
    @SuppressLint("MissingPermission")
    private fun savedPhones(): List<SavedPhone> = runCatching {
        BluetoothAdapter.getDefaultAdapter()?.takeIf { it.isEnabled }?.bondedDevices.orEmpty()
            .mapNotNull { d -> d.name?.let { SavedPhone(it, d.bluetoothClass?.majorDeviceClass ?: 0) } }.sortedBy { it.name }
    }.getOrDefault(emptyList())

    private companion object { const val CONNECT_FAIL_MS = 4000L }
}

/**
 * media.tsx `useCloseOntoCard`: the card's box on home in the app's layout. The screen grid (`var(--rail) 1fr 400px`
 * / `var(--bar) 1fr`) puts the panel at the right, 24 px in from the right and bottom edges, under the 56 px bar:
 * 880, 56, 376 x 670 on the 1280 x 750 canvas.
 */
internal fun closeOntoCard(view: MediaAppFrame) {
    view.closeOnto = RectF(880f, 56f, 1256f, 726f)
}
