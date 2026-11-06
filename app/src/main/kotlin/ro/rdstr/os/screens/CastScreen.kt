package ro.rdstr.os.screens

import android.app.Activity
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ro.rdstr.car.App
import ro.rdstr.car.ShellAction
import ro.rdstr.os.CarRuntime
import ro.rdstr.ui.Mirrors
import ro.rdstr.ui.phone.PhoneScreenView

/** PhoneScreenApp.tsx on the unit: the receiver's picture in the view's surface (cast/CastReceiver), its state shown. */
@Mirrors("PhoneScreenApp.tsx")
class CastScreen(activity: Activity) : Screen {
    override val view = PhoneScreenView(activity, object : PhoneScreenView.Actions {
        override fun shellAction(action: ShellAction) = CarRuntime.shellAction(action)
        override fun setCastVisible(on: Boolean) = CarRuntime.setCastVisible(on)
        override fun castPicture(surface: Surface?) = CarRuntime.castSurface(surface)
    })

    override fun bind(scope: CoroutineScope) {
        scope.launch {
            combine(CarRuntime.shell, CarRuntime.ui) { s, ui -> s to ui }.collect { (s, ui) ->
                view.bind(s.app == App.PHONE_SCREEN, ui.state.cast, s.castVisible, device = ui.castDevice)
            }
        }
    }
}
