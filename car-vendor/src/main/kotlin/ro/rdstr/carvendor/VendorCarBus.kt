package ro.rdstr.carvendor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge
import ro.rdstr.car.CarBus
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent

/** The real unit. Calls go through [VendorPhone]; the other parts of the car are not wired here yet. */
class VendorCarBus(context: Context) : CarBus {
    private val phone = VendorPhone(context.applicationContext).also { it.start() }
    private val messages = VendorMessages(context.applicationContext).also { it.start() }

    override val events: Flow<CarEvent> = merge(phone.events, messages.events)

    override fun send(command: CarCommand) {
        when (command) {
            is CarCommand.Phone.SendMessage -> messages.send(command.number, command.text)
            is CarCommand.Phone -> phone.send(command)
            is CarCommand.Bluetooth.Connect -> phone.link(command.device, connect = true)
            CarCommand.Bluetooth.Disconnect -> phone.link(null, connect = false)
            else -> Log.i("VendorCarBus", "send $command")
        }
    }
}
