package ro.rdstr.car

import kotlinx.coroutines.flow.Flow

interface CarBus {
    val events: Flow<CarEvent>
    fun send(command: CarCommand)
}
