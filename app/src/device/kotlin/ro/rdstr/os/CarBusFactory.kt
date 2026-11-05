package ro.rdstr.os

import android.content.Context
import ro.rdstr.car.CarBus
import ro.rdstr.carvendor.VendorCarBus

object CarBusFactory {
    fun create(context: Context): CarBus = VendorCarBus(context)
}
