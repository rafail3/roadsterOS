package ro.rdstr.os

import ro.rdstr.car.CarBus
import ro.rdstr.carsim.SimCarBusHolder

object CarBusFactory {
    fun create(): CarBus = SimCarBusHolder.bus
}
