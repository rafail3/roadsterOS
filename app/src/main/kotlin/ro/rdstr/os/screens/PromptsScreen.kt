package ro.rdstr.os.screens

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ro.rdstr.car.CallView
import ro.rdstr.car.CarEvent
import ro.rdstr.car.MapPacks
import ro.rdstr.car.ShellAction
import ro.rdstr.os.CarRuntime
import ro.rdstr.os.maps.MapHost
import ro.rdstr.os.maps.PackOffer
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.sheets.AlertPromptView
import ro.rdstr.ui.sheets.MapOfferState
import ro.rdstr.ui.sheets.MapOfferView

/** LocationPrompt.tsx and RestartPrompt.tsx on the unit. */
@Mirrors("LocationPrompt.tsx", "RestartPrompt.tsx")
object PromptsScreen {
    /** "Wi-Fi needs Location": `answerWifiNeedsLocation`. */
    fun location(context: Context, scope: CoroutineScope): AlertPromptView {
        val view = AlertPromptView(context, restart = false)
        view.actions = object : AlertPromptView.Actions {
            override fun primary() = CarRuntime.answerWifiNeedsLocation(true)
            override fun cancel() = CarRuntime.answerWifiNeedsLocation(false)
        }
        scope.launch(Dispatchers.Main) { CarRuntime.ui.map { it.wifiNeedsLocation }.distinctUntilChanged().collect { view.show(it) } }
        return view
    }

    /** "Restart the unit?": `restartUnit()`, `askRestart(false)`. */
    fun restart(context: Context, scope: CoroutineScope): AlertPromptView {
        val view = AlertPromptView(context, restart = true)
        view.actions = object : AlertPromptView.Actions {
            override fun primary() { CarRuntime.restartUnit() }
            override fun cancel() = CarRuntime.askRestart(false)
        }
        scope.launch(Dispatchers.Main) { CarRuntime.ui.map { it.restartAsked }.distinctUntilChanged().collect { view.show(it) } }
        return view
    }
}

/**
 * MapOffer.tsx on the unit: the offer for the country under the car (PackOffer, the same rule as the emulator),
 * from the GPS every few seconds; hidden under a full-screen call, the camera and the drawer.
 */
@Mirrors("MapOffer.tsx")
object MapOfferScreen {
    private const val GPS_EVERY_MS = 5_000L

    @SuppressLint("MissingPermission") // location is granted to the device owner
    fun view(context: Context, scope: CoroutineScope): MapOfferView {
        val view = MapOfferView(context)
        val offers = PackOffer(context, MapHost.borders)
        val fix = MutableStateFlow<Location?>(null)
        val declined = MutableStateFlow(0)
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { fix.value = l }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        runCatching {
            context.getSystemService(LocationManager::class.java)?.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_EVERY_MS, 0f, listener, Looper.getMainLooper())
        }
        var code = ""
        view.actions = object : MapOfferView.Actions {
            override fun decline() { offers.decline(code); declined.value++ }
            override fun download() = CarRuntime.downloadPack(code)
            override fun openNetworks() = CarRuntime.shellAction(ShellAction.OpenNetworks)
        }
        scope.launch(Dispatchers.Main) {
            combine(CarRuntime.ui, CarRuntime.shell, fix, declined) { ui, shell, at, _ ->
                val (country, offer) = at?.let { offers.at(it.longitude, it.latitude) } ?: (null to MapPacks.Offer.NONE)
                val downloading = ui.maps.downloading
                code = downloading?.code ?: country?.code.orEmpty()
                val hidden = shell.drawer || ui.camera || shell.cameraOpen ||
                    (ui.state.call != CarEvent.Call.State.IDLE && shell.callView == CallView.FULL)
                MapOfferState(
                    shown = !hidden && (offer != MapPacks.Offer.NONE || downloading != null),
                    offer = offer,
                    name = MapHost.borders.firstOrNull { it.code == code }?.name ?: code,
                    mb = MapPacks.PACK_MB[code] ?: 0,
                    progress = downloading?.progress,
                    waiting = downloading != null && ui.wifi.connected == null,
                )
            }.collect(view::bind)
        }
        return view
    }
}
