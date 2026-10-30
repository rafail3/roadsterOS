package ro.rdstr.carsim

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import ro.rdstr.car.CarBus
import ro.rdstr.car.CarCommand
import ro.rdstr.car.CarEvent
import ro.rdstr.car.JournalLine

/** Plays the car: replays recorded journals and echoes what a real unit would answer to commands. */
class SimCarBus(private val scope: CoroutineScope) : CarBus {

    private val flow = MutableSharedFlow<CarEvent>(extraBufferCapacity = 64)
    override val events: Flow<CarEvent> = flow

    fun emit(event: CarEvent) {
        flow.tryEmit(event)
    }

    fun replay(lines: List<JournalLine>): Job = scope.launch {
        var last = 0L
        for (line in lines) {
            delay(line.t - last)
            last = line.t
            flow.emit(line.event)
        }
    }

    override fun send(command: CarCommand) {
        when (command) {
            CarCommand.Media.Play -> emit(CarEvent.Playback(true))
            CarCommand.Media.Pause -> emit(CarEvent.Playback(false))
            CarCommand.Phone.Answer -> emit(CarEvent.Call(CarEvent.Call.State.ACTIVE))
            CarCommand.Phone.HangUp -> emit(CarEvent.Call(CarEvent.Call.State.IDLE))
            else -> Unit
        }
    }
}

/** Process-wide instance so the broadcast receiver and the service share one bus. */
object SimCarBusHolder {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val bus = SimCarBus(scope)
}
