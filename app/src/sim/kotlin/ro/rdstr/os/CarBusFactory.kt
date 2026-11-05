package ro.rdstr.os

import android.content.Context
import ro.rdstr.car.CarBus
import ro.rdstr.carsim.SimCarBusHolder

object CarBusFactory {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): CarBus = SimCarBusHolder.bus
}
