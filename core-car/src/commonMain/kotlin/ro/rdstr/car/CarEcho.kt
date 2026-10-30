package ro.rdstr.car

/** What a real unit reports back after a command; used by the simulator and the browser emulator. */
object CarEcho {
    fun respond(command: CarCommand): CarEvent? = when (command) {
        CarCommand.Media.Play -> CarEvent.Playback(true)
        CarCommand.Media.Pause -> CarEvent.Playback(false)
        CarCommand.Phone.Answer -> CarEvent.Call(CarEvent.Call.State.ACTIVE)
        CarCommand.Phone.HangUp -> CarEvent.Call(CarEvent.Call.State.IDLE)
        else -> null
    }
}
