package ro.rdstr.car

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ro.rdstr.car.CarEvent.Call.State
import ro.rdstr.car.CarEvent.SteeringKey.Key

@Serializable
data class RulesState(
    val source: AudioSource = AudioSource.NONE,
    val playing: Boolean = false,
    val muted: Boolean = false,
    val camera: Boolean = false,
    val call: State = State.IDLE,
    val btConnected: Boolean = false,
    val night: Boolean = false,
    val resumeAfterCall: Boolean = false,
    val lastSource: AudioSource = AudioSource.NONE,
    val volume: Int = Rules.DEFAULT_VOLUME,
)

@Serializable
sealed class Action {
    @Serializable @SerialName("ShowCamera") data object ShowCamera : Action()
    @Serializable @SerialName("HideCamera") data object HideCamera : Action()
    @Serializable @SerialName("ShowCall") data class ShowCall(val call: CarEvent.Call) : Action()
    @Serializable @SerialName("HideCall") data object HideCall : Action()
    @Serializable @SerialName("Send") data class Send(val command: CarCommand) : Action()
    @Serializable @SerialName("Night") data class Night(val on: Boolean) : Action()
    @Serializable @SerialName("SaveState") data object SaveState : Action()
    @Serializable @SerialName("RestoreState") data object RestoreState : Action()
    @Serializable @SerialName("ToggleScreen") data object ToggleScreen : Action()
    @Serializable @SerialName("Home") data object Home : Action()
    @Serializable @SerialName("Back") data object Back : Action()
    @Serializable @SerialName("ShowVolume") data object ShowVolume : Action()
}

/** The single place that decides what happens when the car says something. Pure, no Android. */
object Rules {
    // the unit's music stream runs 0..100; the start level is a placeholder until it is read from the unit
    const val MAX_VOLUME = 100
    const val DEFAULT_VOLUME = 40

    fun apply(s: RulesState, e: CarEvent): Pair<RulesState, List<Action>> = when (e) {
        is CarEvent.ReverseGear ->
            s.copy(camera = e.on) to listOf(if (e.on) Action.ShowCamera else Action.HideCamera)

        is CarEvent.Call -> call(s, e)
        is CarEvent.SteeringKey -> key(s, e.key)
        is CarEvent.PanelKey -> when (e.key) {
            // a short press on the frame's power key mutes and unmutes, as the unit does
            CarEvent.PanelKey.Key.POWER -> key(s, Key.MUTE).let { (next, actions) -> next to actions + Action.ShowVolume }
            CarEvent.PanelKey.Key.HOME -> s to listOf(Action.Home)
            CarEvent.PanelKey.Key.BACK -> s to listOf(Action.Back)
            CarEvent.PanelKey.Key.VOL_UP -> key(s, Key.VOL_UP)
            CarEvent.PanelKey.Key.VOL_DOWN -> key(s, Key.VOL_DOWN)
        }

        is CarEvent.Illumination -> s.copy(night = e.on) to listOf(Action.Night(e.on))

        is CarEvent.Power ->
            if (!e.acc) s to listOf(Action.SaveState)
            else s to buildList {
                add(Action.RestoreState)
                if (s.source != AudioSource.NONE) add(Action.Send(CarCommand.Audio.Source(s.source)))
                if (s.source == AudioSource.BT && s.playing) add(Action.Send(CarCommand.Media.Play))
            }

        is CarEvent.BtConnection ->
            if (e.connected) {
                val resume = s.source == AudioSource.NONE && s.lastSource == AudioSource.BT
                val next = s.copy(btConnected = true, source = if (resume) AudioSource.BT else s.source)
                next to if (resume) listOf(Action.Send(CarCommand.Audio.Source(AudioSource.BT))) else emptyList()
            } else {
                val next = if (s.source == AudioSource.BT)
                    s.copy(btConnected = false, source = AudioSource.NONE, lastSource = AudioSource.BT, playing = false)
                else s.copy(btConnected = false)
                next to emptyList()
            }

        // Playback reports come from the Bluetooth player only, so they select BT when nothing else is active.
        is CarEvent.Playback ->
            s.copy(playing = e.playing, source = if (e.playing && s.source == AudioSource.NONE) AudioSource.BT else s.source) to emptyList()
        is CarEvent.Track, is CarEvent.Radio, is CarEvent.CarDisplay -> s to emptyList()
    }

    /** A level picked on screen, as the commands to send: relative steps, mute at zero, sound back above it. */
    fun setVolume(s: RulesState, level: Int): List<CarCommand> {
        val target = level.coerceIn(0, MAX_VOLUME)
        return buildList {
            if (target != s.volume) add(CarCommand.Audio.Volume(target - s.volume))
            if (target == 0 && !s.muted) add(CarCommand.Audio.Mute(true))
            if (target > 0 && s.muted) add(CarCommand.Audio.Mute(false))
        }
    }

    /** Commands coming from our own UI that change what the rules must remember. */
    fun onCommand(s: RulesState, c: CarCommand): RulesState = when (c) {
        is CarCommand.Audio.Source -> if (c.source == s.source) s else s.copy(source = c.source, lastSource = s.source)
        is CarCommand.Audio.Mute -> s.copy(muted = c.on)
        is CarCommand.Audio.Volume -> s.copy(volume = (s.volume + c.delta).coerceIn(0, MAX_VOLUME))
        else -> s
    }

    private fun call(s: RulesState, e: CarEvent.Call): Pair<RulesState, List<Action>> = when (e.state) {
        State.RINGING -> s.copy(call = State.RINGING) to listOf(Action.ShowCall(e))
        State.ACTIVE -> {
            val pause = pauseCommand(s)
            s.copy(call = State.ACTIVE, resumeAfterCall = pause != null) to listOfNotNull(pause?.let { Action.Send(it) })
        }
        State.IDLE -> {
            val resume = if (s.resumeAfterCall) resumeCommand(s) else null
            s.copy(call = State.IDLE, resumeAfterCall = false) to listOfNotNull(Action.HideCall, resume?.let { Action.Send(it) })
        }
    }

    private fun pauseCommand(s: RulesState): CarCommand? = when {
        !s.playing -> null
        s.source == AudioSource.BT -> CarCommand.Media.Pause
        s.source == AudioSource.RADIO -> CarCommand.Audio.Mute(true)
        else -> null
    }

    private fun resumeCommand(s: RulesState): CarCommand? = when (s.source) {
        AudioSource.BT -> CarCommand.Media.Play
        AudioSource.RADIO -> CarCommand.Audio.Mute(false)
        AudioSource.NONE -> null
    }

    private fun key(s: RulesState, k: Key): Pair<RulesState, List<Action>> = when (k) {
        // one step at a time, with the same mute at zero as a level picked on screen
        Key.VOL_UP, Key.VOL_DOWN -> {
            val commands = setVolume(s, s.volume + if (k == Key.VOL_UP) 1 else -1)
            commands.fold(s, ::onCommand) to commands.map { Action.Send(it) } + Action.ShowVolume
        }
        Key.MUTE -> s.copy(muted = !s.muted) to listOf(Action.Send(CarCommand.Audio.Mute(!s.muted)))
        Key.NEXT, Key.PREV -> {
            val up = k == Key.NEXT
            val cmd: CarCommand? = when (s.source) {
                AudioSource.BT -> if (up) CarCommand.Media.Next else CarCommand.Media.Prev
                AudioSource.RADIO -> CarCommand.Radio.Seek(up)
                AudioSource.NONE -> null
            }
            s to listOfNotNull(cmd?.let { Action.Send(it) })
        }
        Key.MODE -> {
            val next = when (s.source) {
                AudioSource.BT -> AudioSource.RADIO
                AudioSource.RADIO -> if (s.btConnected) AudioSource.BT else AudioSource.RADIO
                AudioSource.NONE -> AudioSource.RADIO
            }
            if (next == s.source) s to emptyList()
            else s.copy(source = next, lastSource = s.source) to listOf(Action.Send(CarCommand.Audio.Source(next)))
        }
        Key.PHONE -> s to when (s.call) {
            State.RINGING -> listOf(Action.Send(CarCommand.Phone.Answer))
            State.ACTIVE -> listOf(Action.Send(CarCommand.Phone.HangUp))
            State.IDLE -> emptyList()
        }
    }
}
