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

    /** The touch keys printed on the frame beside the screen. */
    @Serializable @SerialName("PanelKey")
    data class PanelKey(val key: Key) : CarEvent() {
        enum class Key { POWER, HOME, BACK, VOL_UP, VOL_DOWN }
    }

    @Serializable @SerialName("Illumination")
    data class Illumination(val on: Boolean) : CarEvent()

    @Serializable @SerialName("Power")
    data class Power(val acc: Boolean) : CarEvent()

    @Serializable @SerialName("BtConnection")
    data class BtConnection(val connected: Boolean, val device: String = "") : CarEvent()

    /** The phone connected for calls (the hands-free profile), apart from its music; [device] is its Bluetooth name. */
    @Serializable @SerialName("HfpConnection")
    data class HfpConnection(val connected: Boolean, val device: String = "") : CarEvent()

    @Serializable @SerialName("Track")
    data class Track(
        val title: String,
        val artist: String = "",
        val album: String = "",
        val durationMs: Long = 0,
    ) : CarEvent()

    @Serializable @SerialName("Playback")
    data class Playback(val playing: Boolean, val positionMs: Long = 0) : CarEvent()

    /**
     * The phone's calls as one state; OUTGOING while a call is dialled and rings at the other end. [held]: the shown
     * call is on hold; [conference]: calls merged into one; [other]: a second call, ringing ([Other.waiting]) or on hold.
     */
    @Serializable @SerialName("Call")
    data class Call(
        val state: State,
        val number: String = "",
        val name: String = "",
        val held: Boolean = false,
        val conference: Boolean = false,
        val other: Other? = null,
    ) : CarEvent() {
        enum class State { RINGING, OUTGOING, ACTIVE, IDLE }

        @Serializable
        data class Other(val number: String, val name: String = "", val waiting: Boolean)
    }

    /** The phone's voice assistant listens through the car's microphone. */
    @Serializable @SerialName("VoiceAssistant")
    data class VoiceAssistant(val active: Boolean) : CarEvent()

    /** A text message the phone received (MAP); [sent] for one sent from the car, kept in the thread. */
    @Serializable @SerialName("Message")
    data class Message(val id: String, val number: String, val text: String, val name: String = "", val atMs: Long = 0, val sent: Boolean = false) : CarEvent()

    /** Where the call is heard: in the car, or moved back to the phone itself. */
    @Serializable @SerialName("CallAudio")
    data class CallAudio(val inCar: Boolean) : CarEvent()

    /** What the phone reports over the hands-free link: battery and signal 0..5, -1 when not sent; the network's name. */
    @Serializable @SerialName("PhoneStatus")
    data class PhoneStatus(val battery: Int = -1, val signal: Int = -1, val operator: String = "", val roaming: Boolean = false) : CarEvent()

    /**
     * What the tuner plays: [rds] is the station name (RDS PS), [pty] the programme type code (see [Rds.ptyName]).
     * The vendor tuner library decodes no radio text (RDS RT), so there is no song title.
     */
    @Serializable @SerialName("Radio")
    data class Radio(
        val khz: Int,
        val band: Band,
        val rds: String = "",
        val signal: Int = 0,
        val pty: Int = 0,
        val stereo: Boolean = false,
    ) : CarEvent() {
        enum class Band { FM, AM }
    }

    @Serializable @SerialName("CarDisplay")
    data class CarDisplay(val mode: String) : CarEvent()
}
