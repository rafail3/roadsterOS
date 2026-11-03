package ro.rdstr.car

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class AudioSource { NONE, BT, RADIO }

/** Everything roadsterOS asks the car (through the head unit) to do. */
@Serializable
sealed class CarCommand {

    @Serializable
    sealed class Media : CarCommand() {
        @Serializable @SerialName("Media.Play") data object Play : Media()
        @Serializable @SerialName("Media.Pause") data object Pause : Media()
        @Serializable @SerialName("Media.Next") data object Next : Media()
        @Serializable @SerialName("Media.Prev") data object Prev : Media()
    }

    @Serializable
    sealed class Radio : CarCommand() {
        @Serializable @SerialName("Radio.Tune") data class Tune(val khz: Int) : Radio()
        @Serializable @SerialName("Radio.Seek") data class Seek(val up: Boolean) : Radio()
        @Serializable @SerialName("Radio.Preset") data class Preset(val index: Int) : Radio()
        @Serializable @SerialName("Radio.Band") data class Band(val band: CarEvent.Radio.Band) : Radio()
    }

    @Serializable
    sealed class Phone : CarCommand() {
        @Serializable @SerialName("Phone.Dial") data class Dial(val number: String) : Phone()
        @Serializable @SerialName("Phone.Answer") data object Answer : Phone()
        @Serializable @SerialName("Phone.HangUp") data object HangUp : Phone()
        @Serializable @SerialName("Phone.Mute") data class Mute(val on: Boolean) : Phone()
    }

    @Serializable
    sealed class Audio : CarCommand() {
        /** Relative step: +1 / -1. */
        @Serializable @SerialName("Audio.Volume") data class Volume(val delta: Int) : Audio()
        @Serializable @SerialName("Audio.Mute") data class Mute(val on: Boolean) : Audio()
        @Serializable @SerialName("Audio.Source") data class Source(val source: AudioSource) : Audio()
    }

    @Serializable
    sealed class CarDisplay : CarCommand() {
        @Serializable @SerialName("CarDisplay.SetMode") data class SetMode(val mode: String) : CarDisplay()
        @Serializable @SerialName("CarDisplay.SetTime") data class SetTime(val hour: Int, val minute: Int) : CarDisplay()
    }

    @Serializable
    sealed class Camera : CarCommand() {
        @Serializable @SerialName("Camera.Show") data object Show : Camera()
        @Serializable @SerialName("Camera.Hide") data object Hide : Camera()
    }
}
