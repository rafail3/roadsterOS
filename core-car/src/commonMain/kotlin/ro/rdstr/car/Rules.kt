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
    /** A phone is connected for calls; the phone app opens only then. */
    val hfpConnected: Boolean = false,
    val night: Boolean = false,
    val resumeAfterCall: Boolean = false,
    val lastSource: AudioSource = AudioSource.NONE,
    val volume: Int = Rules.DEFAULT_VOLUME,
    /** The radio receives its station; false while paused (not muted: the volume is untouched). */
    val radioOn: Boolean = true,
    /** The phone's music was playing when its link dropped: it plays again when the phone comes back. */
    val playOnConnect: Boolean = false,
    val cast: CarEvent.PhoneCast.State = CarEvent.PhoneCast.State.OFF,
    /** The source was playing when the phone's screen came on: it plays again when the screen goes, after any call. */
    val resumeAfterCast: Boolean = false,
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
    // the unit's volume level runs 0..30 (the vendor maps it onto Android's 0..100 music stream through a table);
    // the start level is the one last read on the unit, until our app reads it at boot
    const val MAX_VOLUME = 30
    const val DEFAULT_VOLUME = 16

    fun apply(s: RulesState, e: CarEvent): Pair<RulesState, List<Action>> = when (e) {
        is CarEvent.ReverseGear ->
            s.copy(camera = e.on) to listOf(if (e.on) Action.ShowCamera else Action.HideCamera)

        is CarEvent.Call -> call(s, e)
        is CarEvent.PhoneCast -> cast(s, e.state)
        is CarEvent.SteeringKey -> key(s, e.key)
        is CarEvent.PanelKey -> when (e.key) {
            // the frame's power key switches the screen off and on; mute stays on the wheel and the volume bar
            CarEvent.PanelKey.Key.POWER -> s to listOf(Action.ToggleScreen)
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
                val play = resume && s.playOnConnect
                val next = s.copy(btConnected = true, source = if (resume) AudioSource.BT else s.source, playOnConnect = false)
                next to buildList {
                    if (resume) add(Action.Send(CarCommand.Audio.Source(AudioSource.BT)))
                    if (play) add(Action.Send(CarCommand.Media.Play))
                }
            } else {
                val next = if (s.source == AudioSource.BT)
                    s.copy(btConnected = false, source = AudioSource.NONE, lastSource = AudioSource.BT, playing = false, playOnConnect = s.playing)
                else s.copy(btConnected = false)
                next to emptyList()
            }

        // a phone that drops mid-call takes the call with it
        is CarEvent.HfpConnection ->
            if (!e.connected && s.call != State.IDLE) call(s.copy(hfpConnected = false), CarEvent.Call(State.IDLE))
            else s.copy(hfpConnected = e.connected) to emptyList()

        // Playback reports come from the Bluetooth player only, so they select BT when nothing else is active.
        is CarEvent.Playback ->
            s.copy(playing = e.playing, source = if (e.playing && s.source == AudioSource.NONE) AudioSource.BT else s.source) to emptyList()
        is CarEvent.Track, is CarEvent.Radio, is CarEvent.CarDisplay, is CarEvent.CallAudio, is CarEvent.PhoneStatus,
        is CarEvent.VoiceAssistant, is CarEvent.Message, is CarEvent.MessageSupport -> s to emptyList()
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
        // choosing the radio, or tuning it, starts receiving again
        is CarCommand.Audio.Source -> if (c.source == s.source) s else s.copy(source = c.source, lastSource = s.source, radioOn = s.radioOn || c.source == AudioSource.RADIO)
        CarCommand.Radio.Pause -> s.copy(radioOn = false)
        CarCommand.Radio.Play, is CarCommand.Radio.Tune, is CarCommand.Radio.Seek, is CarCommand.Radio.Preset, is CarCommand.Radio.Band, CarCommand.Radio.Scan -> s.copy(radioOn = true)
        is CarCommand.Audio.Mute -> s.copy(muted = c.on)
        is CarCommand.Audio.Volume -> s.copy(volume = (s.volume + c.delta).coerceIn(0, MAX_VOLUME))
        else -> s
    }

    /**
     * A call stops the music at its first ring or its first dial, once: a later step of the same call (answered, a
     * second line) or a pause report from the phone must not lose the resume. When the call ends, is declined or is
     * missed, the source plays again, only if it was playing before.
     */
    private fun call(s: RulesState, e: CarEvent.Call): Pair<RulesState, List<Action>> = when (e.state) {
        State.RINGING, State.OUTGOING, State.ACTIVE -> {
            val pause = if (s.call != State.IDLE) null else pauseCommand(s)
            val show = if (e.state != State.ACTIVE || e.number.isNotEmpty()) listOf(Action.ShowCall(e)) else emptyList()
            val paused = pause?.let { onCommand(s, it) } ?: s
            paused.copy(call = e.state, resumeAfterCall = s.resumeAfterCall || pause != null) to show + listOfNotNull(pause?.let { Action.Send(it) })
        }
        State.IDLE -> {
            // with the phone's screen on, the source waits for it to go too
            val casting = s.cast == CarEvent.PhoneCast.State.LIVE
            val resume = if (s.resumeAfterCall && !casting) resumeCommand(s) else null
            val back = resume?.let { onCommand(s, it) } ?: s
            back.copy(call = State.IDLE, resumeAfterCall = false, resumeAfterCast = s.resumeAfterCast || (s.resumeAfterCall && casting)) to listOfNotNull(Action.HideCall, resume?.let { Action.Send(it) })
        }
    }

    /**
     * The phone's screen coming on stops everything that plays on the unit, so only its own sound is heard; when it
     * goes, the source plays again, only if it was playing before and no call is on (the call's end resumes it then).
     */
    private fun cast(s: RulesState, state: CarEvent.PhoneCast.State): Pair<RulesState, List<Action>> {
        val live = CarEvent.PhoneCast.State.LIVE
        return when {
            state == live && s.cast != live -> {
                val pause = pauseCommand(s)
                val paused = pause?.let { onCommand(s, it) } ?: s
                paused.copy(cast = state, resumeAfterCast = pause != null) to listOfNotNull(pause?.let { Action.Send(it) })
            }
            state != live && s.cast == live -> {
                val resume = if (s.resumeAfterCast && s.call == State.IDLE) resumeCommand(s) else null
                val back = resume?.let { onCommand(s, it) } ?: s
                back.copy(cast = state, resumeAfterCast = false, resumeAfterCall = s.resumeAfterCall || (s.resumeAfterCast && s.call != State.IDLE)) to
                    listOfNotNull(resume?.let { Action.Send(it) })
            }
            else -> s.copy(cast = state) to emptyList()
        }
    }

    /** What stops the source that plays: the phone's music paused, the radio's station paused (its volume untouched). */
    private fun pauseCommand(s: RulesState): CarCommand? = when {
        s.source == AudioSource.BT && s.playing -> CarCommand.Media.Pause
        s.source == AudioSource.RADIO && s.radioOn && !s.muted -> CarCommand.Radio.Pause
        else -> null
    }

    private fun resumeCommand(s: RulesState): CarCommand? = when (s.source) {
        AudioSource.BT -> CarCommand.Media.Play
        AudioSource.RADIO -> CarCommand.Radio.Play
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
            State.OUTGOING, State.ACTIVE -> listOf(Action.Send(CarCommand.Phone.HangUp))
            State.IDLE -> emptyList()
        }
    }
}
