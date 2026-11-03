package ro.rdstr.car

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Everything the car (through the head unit) tells roadsterOS. */
@Serializable
sealed class CarEvent {

    @Serializable @SerialName("ReverseGear")
    data class ReverseGear(val on: Boolean) : CarEvent()

    @Serializable @SerialName("SteeringKey")
    data class SteeringKey(val key: Key) : CarEvent() {
        enum class Key { VOL_UP, VOL_DOWN, NEXT, PREV, MODE, PHONE, MUTE }
    }

    @Serializable @SerialName("Illumination")
    data class Illumination(val on: Boolean) : CarEvent()

    @Serializable @SerialName("Power")
    data class Power(val acc: Boolean) : CarEvent()

    @Serializable @SerialName("BtConnection")
    data class BtConnection(val connected: Boolean, val device: String = "") : CarEvent()

    @Serializable @SerialName("Track")
    data class Track(
        val title: String,
        val artist: String = "",
        val album: String = "",
        val durationMs: Long = 0,
    ) : CarEvent()

    @Serializable @SerialName("Playback")
    data class Playback(val playing: Boolean, val positionMs: Long = 0) : CarEvent()

    @Serializable @SerialName("Call")
    data class Call(val state: State, val number: String = "", val name: String = "") : CarEvent() {
        enum class State { RINGING, ACTIVE, IDLE }
    }

    @Serializable @SerialName("Radio")
    data class Radio(val khz: Int, val band: Band, val rds: String = "", val signal: Int = 0) : CarEvent() {
        enum class Band { FM, AM }
    }

    @Serializable @SerialName("CarDisplay")
    data class CarDisplay(val mode: String) : CarEvent()
}
