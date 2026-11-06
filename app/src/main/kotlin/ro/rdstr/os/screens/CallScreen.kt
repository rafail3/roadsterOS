package ro.rdstr.os.screens

import android.app.Activity
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.CallView
import ro.rdstr.car.CarCommand
import ro.rdstr.car.ShellAction
import ro.rdstr.os.CarRuntime
import ro.rdstr.os.UiState
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.overlays.CallBarView
import ro.rdstr.ui.overlays.CallOverlayView
import ro.rdstr.ui.overlays.CameraView
import ro.rdstr.ui.overlays.MessageBannerView
import ro.rdstr.ui.overlays.OverlayActions
import ro.rdstr.ui.overlays.VoiceView
import ro.rdstr.ui.phone.CallInfo
import ro.rdstr.ui.phone.CallTrack

/**
 * Overlays.tsx on the unit: what the overlays send, and the call as the store keeps it (when it was answered, the
 * muted microphone), which the unit's state does not carry. Shared by the call screen and the call bar.
 */
@Mirrors("Overlays.tsx")
object Calls : OverlayActions {
    private val track = CallTrack()
    /** Bumped when a sent command changes the shown call without an event from the phone (the microphone). */
    val changed = MutableStateFlow(0)

    override fun carCommand(command: CarCommand) {
        track.sent(command)
        CarRuntime.send(command)
        if (command is CarCommand.Phone.Mute) changed.value++
    }
    override fun shellAction(action: ShellAction) = CarRuntime.shellAction(action)
    override fun openThread(number: String) = CarRuntime.openThread(number)
    override fun dismissMessage() = CarRuntime.dismissMessage()

    fun info(ui: UiState): CallInfo? = track.update(ui.call, ui.callInCar, ui.contacts, System.currentTimeMillis())
}

private fun View.shown(on: Boolean) { visibility = if (on) View.VISIBLE else View.GONE }

/** The full call screen: shown while a call is up and the shell has it full (core-car Shell callView). */
class CallScreen(activity: Activity) : Screen {
    override val view = CallOverlayView(activity, Calls).apply { visibility = View.GONE }

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui, Calls.changed) { s, ui, _ -> Triple(s, ui, Calls.info(ui)) }.collect { (s, ui, call) ->
                view.shown(call != null && s.callView == CallView.FULL)
                if (call != null) view.bind(call, ui.contacts, s.phoneRegion)
            }
        }
    }
}

/** The call folded under the status bar: a call up, the shell has it as a bar, and no camera over it. */
class CallBarScreen(activity: Activity) : Screen {
    override val view = CallBarView(activity, Calls).apply { visibility = View.GONE }

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui, Calls.changed) { s, ui, _ -> Triple(s, ui, Calls.info(ui)) }.collect { (s, ui, call) ->
                view.shown(call != null && s.callView == CallView.BAR && !(ui.camera || s.cameraOpen))
                if (call != null) view.bind(call, s.phoneRegion)
            }
        }
    }
}

/** The phone's voice assistant listening. */
class VoiceScreen(activity: Activity) : Screen {
    override val view = VoiceView(activity, Calls).apply { visibility = View.GONE }

    override fun bind(scope: CoroutineScope) {
        scope.launch { CarRuntime.ui.collect { view.shown(it.voiceAssistant) } }
    }
}

/** A new text message, unless the full call or the camera is up. */
class MessageBannerScreen(activity: Activity) : Screen {
    override val view = MessageBannerView(activity, Calls)

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui) { s, ui -> s to ui }.collect { (s, ui) ->
                val callFull = ui.call != null && s.callView == CallView.FULL
                val covered = callFull || ui.camera || s.cameraOpen
                // ponytail: no shared speed yet, so the words always show (the car counts as standing)
                view.bind(if (covered) null else ui.newMessage, ui.contacts, s.phoneRegion, stopped = true)
            }
        }
    }
}

/**
 * The reverse camera: by reverse gear or opened by hand. On the unit the vendor's camera screen shows the AV_IN
 * picture; this view draws the lines, the label and the close key over the feed.
 */
class CameraScreen(activity: Activity) : Screen {
    override val view = CameraView(activity, Calls).apply { visibility = View.GONE }

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui) { s, ui -> s to ui }.collect { (s, ui) ->
                view.shown(ui.camera || s.cameraOpen)
                // ponytail: the CAN steering angle is not read on the unit yet; bending lines stay straight
                view.bind(ui.camera, s.camera, eps = 0)
            }
        }
    }
}
