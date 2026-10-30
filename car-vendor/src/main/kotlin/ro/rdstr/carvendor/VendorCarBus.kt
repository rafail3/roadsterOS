package ro.rdstr.carvendor

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import ro.rdstr.car.CarBus
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent

class VendorCarBus : CarBus {
    override val events: Flow<CarEvent> = emptyFlow()
    override fun send(command: CarCommand) {
        Log.i("VendorCarBus", "send $command")
    }
}
