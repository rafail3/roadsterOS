package ro.rdstr.os.screens

import android.app.Activity
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ro.rdstr.car.App
import ro.rdstr.car.CallView
import ro.rdstr.car.CarEvent
import ro.rdstr.car.ShellAction
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.shell.ConnectionNote
import ro.rdstr.ui.shell.DevOverlaysView
import ro.rdstr.ui.shell.GestureActions
import ro.rdstr.ui.shell.GesturesView
import ro.rdstr.ui.shell.NoticeView
import ro.rdstr.ui.shell.RecentsActions
import ro.rdstr.ui.shell.RecentsState
import ro.rdstr.ui.shell.RecentsView

/** Gestures.tsx on the unit: the bottom handle and the side edges, there unless the drawer, a sheet or the camera is up. */
@Mirrors("Gestures.tsx")
class GesturesScreen(activity: Activity) : Screen {
    override val view = GesturesView(activity, object : GestureActions {
        override fun home() = CarRuntime.shellAction(ShellAction.Home)
        override fun openRecents() = CarRuntime.shellAction(ShellAction.OpenRecents)
        override fun back() = CarRuntime.shellAction(ShellAction.Back)
    })

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui) { s, ui -> !s.drawer && !s.devices && !s.networks && !(ui.camera || s.cameraOpen) }
                .collect { view.visibility = if (it) View.VISIBLE else View.GONE }
        }
    }
}

/** Recents.tsx on the unit: our open apps, with what each shows. */
@Mirrors("Recents.tsx")
class RecentsScreen(activity: Activity) : Screen {
    override val view = RecentsView(activity, object : RecentsActions {
        override fun openApp(app: App) = CarRuntime.shellAction(ShellAction.OpenApp(app))
        override fun closeApp(app: App) = CarRuntime.shellAction(ShellAction.CloseApp(app))
        override fun closeAll() = CarRuntime.shellAction(ShellAction.CloseAllApps)
        override fun close() = CarRuntime.shellAction(ShellAction.CloseRecents)
    })

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui, Covers.flow, MapsScreen.speed) { s, ui, cover, speed ->
                RecentsState(
                    open = s.recents,
                    apps = s.running,
                    track = ui.track ?: CarEvent.Track(""),
                    cover = cover?.bitmap,
                    kmh = speed.kmh.takeUnless { speed.stopped },
                    phone = ui.phoneName.takeIf { ui.state.hfpConnected },
                    radio = ui.radio ?: CarEvent.Radio(87500, CarEvent.Radio.Band.FM),
                )
            }.collect(view::bind)
        }
    }
}

/**
 * ConnectionNotice.tsx on the unit: a notice for every change of the Bluetooth link (the phone's name) or of the
 * Wi-Fi network (store.ts `connectionNotice`), hidden under a full call or the camera, lifted over the map offer.
 */
@Mirrors("ConnectionNotice.tsx")
class NoticeScreen(activity: Activity) : Screen {
    override val view = NoticeView(activity)
    private var note: ConnectionNote? = null
    private var bt: Boolean? = null
    private var wifi: String? = null
    private var device = ""
    private var id = 0

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui, mapOffer) { s, ui, offer ->
                if (ui.phoneName.isNotEmpty()) device = ui.phoneName
                val was = bt
                if (was != null) ConnectionNote.of(id + 1, was, ui.state.btConnected, device, wifi, ui.wifi.connected)?.let { note = it; id = it.id }
                bt = ui.state.btConnected
                wifi = ui.wifi.connected
                val callFull = ui.state.call != CarEvent.Call.State.IDLE && s.callView == CallView.FULL
                Triple(note, callFull || ui.camera || s.cameraOpen, offer)
            }.collect { (n, hidden, offer) -> view.bind(n, hidden, offer) }
        }
    }

    companion object {
        /** The map offer is on screen (MapOffer.tsx `.is-shown`), set by the map offer's glue: the notice then stands over it. */
        val mapOffer = kotlinx.coroutines.flow.MutableStateFlow(false)
    }
}

/** DevOverlays.tsx on the unit: the touches and the frame rate, as the Developer section has them. */
@Mirrors("DevOverlays.tsx")
class DevOverlaysScreen(activity: Activity) : Screen {
    override val view = DevOverlaysView(activity)

    override fun bind(scope: CoroutineScope) {
        scope.launch { CarRuntime.shell.collect { view.touches = it.showTouches; view.fps = it.showFrameRate } }
    }
}
