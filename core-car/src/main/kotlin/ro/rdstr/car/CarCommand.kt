package ro.rdstr.car

enum class AudioSource { NONE, BT, RADIO }

/** Everything roadsterOS asks the car (through the head unit) to do. */
sealed class CarCommand {

    sealed class Media : CarCommand() {
        data object Play : Media()
        data object Pause : Media()
        data object Next : Media()
        data object Prev : Media()
    }

    sealed class Radio : CarCommand() {
        data class Tune(val khz: Int) : Radio()
        data class Seek(val up: Boolean) : Radio()
        data class Preset(val index: Int) : Radio()
        data class Band(val band: CarEvent.Radio.Band) : Radio()
    }

    sealed class Phone : CarCommand() {
        data class Dial(val number: String) : Phone()
        data object Answer : Phone()
        data object HangUp : Phone()
        data class Mute(val on: Boolean) : Phone()
    }

    sealed class Audio : CarCommand() {
        /** Relative step: +1 / -1. */
        data class Volume(val delta: Int) : Audio()
        data class Mute(val on: Boolean) : Audio()
        data class Source(val source: AudioSource) : Audio()
    }

    sealed class CarDisplay : CarCommand() {
        data class SetMode(val mode: String) : CarDisplay()
        data class SetTime(val hour: Int, val minute: Int) : CarDisplay()
    }

    sealed class Camera : CarCommand() {
        data object Show : Camera()
        data object Hide : Camera()
    }
}
