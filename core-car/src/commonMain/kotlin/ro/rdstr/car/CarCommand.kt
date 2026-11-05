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
        /** Stop receiving the station: the tuner goes quiet, the volume and its mute stay as they are. */
        @Serializable @SerialName("Radio.Pause") data object Pause : Radio()
        /** Receive the tuned station again. */
        @Serializable @SerialName("Radio.Play") data object Play : Radio()
        /** Sweep the band and list every station found. */
        @Serializable @SerialName("Radio.Scan") data object Scan : Radio()
    }

    @Serializable
    sealed class Phone : CarCommand() {
        @Serializable @SerialName("Phone.Dial") data class Dial(val number: String) : Phone()
        @Serializable @SerialName("Phone.Answer") data object Answer : Phone()
        @Serializable @SerialName("Phone.HangUp") data object HangUp : Phone()
        /** The car's microphone, not the call: the other side stops hearing the car. */
        @Serializable @SerialName("Phone.Mute") data class Mute(val on: Boolean) : Phone()
        /** A keypad tone during a call, for menus that ask for digits: 0-9, * or #. */
        @Serializable @SerialName("Phone.Dtmf") data class Dtmf(val digit: String) : Phone()
        /** Hear the call in the car, or move it to the phone. */
        @Serializable @SerialName("Phone.AudioInCar") data class AudioInCar(val on: Boolean) : Phone()
    }

    /** Phones by the name the unit lists them under: connect or drop a saved one, look for new ones and pair. */
    @Serializable
    sealed class Bluetooth : CarCommand() {
        @Serializable @SerialName("Bt.Connect") data class Connect(val device: String) : Bluetooth()
        @Serializable @SerialName("Bt.Disconnect") data object Disconnect : Bluetooth()
        /** Remove a saved phone from the unit; a connected one is dropped first. */
        @Serializable @SerialName("Bt.Forget") data class Forget(val device: String) : Bluetooth()
        @Serializable @SerialName("Bt.Scan") data object Scan : Bluetooth()
        @Serializable @SerialName("Bt.Pair") data class Pair(val device: String) : Bluetooth()
        /** The answer to the pairing code shown on both screens. */
        @Serializable @SerialName("Bt.ConfirmPairing") data class ConfirmPairing(val accept: Boolean) : Bluetooth()
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
