package ro.rdstr.os

import ro.rdstr.car.CarBus
import ro.rdstr.carvendor.VendorCarBus

object CarBusFactory {
    fun create(): CarBus = VendorCarBus()
}
